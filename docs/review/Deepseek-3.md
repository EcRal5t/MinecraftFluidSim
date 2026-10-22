§315§ 第三回审查基于 `cfe5547c`（生产代码提交 `804277e4`），审计产物 SHA 为 `e3016c2b…`。结论很明确：**这一轮修掉了上一轮的几个核心账本问题，但“M10 完成”仍然不成立；默认 `fast` 至少有 `granular` 和 `air-cavity` 两个门是红的，预算在最终产物上两次复测都不通过。**

---

## 一、最终产物的实际 gate 结果

| Gate | 结果 | 说明 |
|---|---|---|
| `cell-inventory` | **PASS** | 库存、MAC、动量、编辑恢复都通过 |
| `chemistry:short` | **PASS** | 化学输运、抽取、热量和恢复通过 |
| `thermal` | **PASS** | 定义、气腔、相变、热平流、分层、自然对流通过 |
| `free-surface:short` | **PASS** | 短自由面场景通过 |
| `containers:short` | **PASS**（320.6 s） | 12 + 6 + 19 个容器/持久化场景 |
| `cut-cell:short` | **PASS** | 黏性解析误差随网格下降 |
| `marker-transfer` / `marker-pressure` / `shape` | **PASS** | — |
| `granular:short` | **FAIL** | `LiquidCarrierProperties.refresh` 空指针 |
| `air-cavity` | **FAIL** | 液塞8步只前移 0.058 格，断言要求 1 格 |
| `gas-liquid:short` | **FAIL** | 封闭真空自发产生 7.6e-7 J 动能和 3.7e-8 J 内能 |
| `production-live-budget` | **FAIL**（两次） | 溃坝最大 260 ms / 305 ms，连续超 200 ms 4 次 / 3 次 |

所有结果都来自同一个最终产物：`revision=cfe5547c`、`sourceSHA256=93abba…`、`artifactSHA256=e3016c2b…`。这与 `validation.md` 登记的最终 manifest 一致，所以不是“陈旧 classpath”问题。

`granular` 和 `air-cavity` 都在默认 `fast` 组合里，默认验收门当前是红的。`production-replay` 没有重跑，不能算绿。

---

## 二、这一轮确实修好的部分

先说公平的部分：

- `CarrierMutationValidation` 现在通过，颗粒插入的外部来源能量不再被旧审计覆盖。`GranularLiquidDisplacement` 改成在 `solidPublication.run()` 之后把本次液体运输的机械热和舍入追加到当前账本，方向正确。
- 外部抽取后的相占据失效问题修好了。`PhaseOccupancyTransport` 增加 `phaseOccupancyDirty` 与 `publish`，普通库存编辑会重新派生，`chemistry` 的恢复不再失败。
- 热恢复现在用实际液体/凝聚相/颗粒/气体能量的精确和与审计账本比对，`thermal` 完整通过。
- 切格的静水压力重建明显改善。`PressureFaceGraph` 用 `supportDepth` 和实际液柱积分替代了原来的单元尺度近似，`cut-cell:short` 通过。
- `containers`、`free-surface`、`chemistry`、`thermal` 的通过说明液体库存、容器、化学和热学主干没有被这轮改动破坏。

代码体量也继续下降：当前约 **464 个生产 Java 文件、65,124 行**，测试 **94 个文件、33,556 行**。旧的精确结算和点权威路径确实删掉了。

---

## 三、仍然阻塞的四个问题

### 1. `granular` 的 NPE：`LiquidCarrierProperties` 失去了空材料保护

当前失败栈：

```text
LiquidCarrierProperties.refresh(LiquidCarrierProperties.java:145)
DomainPhaseInventory.derivedCarrierState(DomainPhaseInventory.java:40)
GranularBedShear.prepareFlow(GranularBedShear.java:118)
DomainStep.granularDepositEnvironment(DomainStep.java:224)
GranularBedWakeValidation.macLiftPaysPotentialAndRecoil(GranularBedWakeValidation.java:46)
```

`refresh` 里仍保留 `definitions == null` 的黏度分支，却把“湿格”判据改成了：

```java
boolean wet = handle >= 0 && grid.cellStockMass[cell] > 0 && grid.stockCount[cell] > 0;
...
String materialId = materials.material(handle).id();
```

`materials == null` 时直接 NPE。`GranularBedWakeValidation.macLiftPayPotentialAndRecoil` 构造的是没有绑定材料注册表的 3×3×3 网格，因此 `runGranularBedValidation` 的默认入口无法完成。

`review2-shared-core.log` 记录的 674 步沙床生命周期发生在 05:23，而最终提交 `804277e` 在 05:42 才改变这个判据；文档把这份旧日志当成最终证据，实际上最终代码连 `GranularBedWakeValidation.validate()` 的第一项都过不去。

影响：默认 `fast` 红；`runGranularBedValidation` 无法作为沙床生命周期的正式入口；同时说明“湿格的载体属性”对无材料域没有定义清楚。

### 2. `air-cavity` 的液塞测试夹具与物理量级不匹配

当前失败点是 `AirCavityScannerValidation.java:498`：

```java
check(moment / mass > 4.25, "The inertia-driven liquid plug did not advance through its original cell face");
```

我复制同一夹具做了只读探针，得到：

```text
initial moment=3.50000 K=15.6250
step=0 moment=3.54017 K=1.61382
step=1 moment=3.54777 K=0.570605
...
step=7 moment=3.55836 K=0.00109181
```

液塞从 3.50 只走到 3.558，0.058 格；测试要求超过 4.25，也就是至少走一格。原因不是“液塞没有惯性”，而是两侧气腔各只有 `12,000 nL`（1.2×10⁻⁵ m³，约 0.012 L），液塞是 1 m³。气体被压缩几个数量级后压力立刻把液塞顶住，8 步内动能从 15.6 J 降到 0.001 J。

这是**测试夹具的物理量级错误**：要观察“惯性驱动一格”，气腔体积必须远大于液塞排开体积，或者直接把气体压力设成不阻挡的边界。当前文档却把 `review2-functional6.log` 的 0.172 s PASS 当成最终证据；那份日志在 04:01，早于最终压力/库存改动。

影响：默认 `fast` 红；如果后续排查液塞惯性，会误以为生产代码坏了。

### 3. `gas-liquid` 的封闭真空不再精确静止

当前失败点是 `M5GasLiquidCouplingValidation.java:513`：

```java
check(domain.liquidKineticEnergyJoules() <= 1e-12,
      "An unforced closed vacuum created heat or liquid motion");
```

只读探针结果：

```text
initial K=0
step=0 K=7.58398e-07, vmax=2.06142e-05, U=3.7e-08 J
step=1 K=8.01811e-07, vmax=2.077e-05
```

质量、体积和气体库存仍然精确；液体压力场全部落在 `-101325 Pa` 表压（真空边界），但求解器留下了一个约 2×10⁻⁵ m/s 的残余速度，进而把 0.8 µJ 动能转成内能。这个量对玩法没有可见影响，但它违反了测试的“精确零运动”契约。

这有两种处理方向：

- 保留“封闭真空绝对静止”的契约，就需要把真空节点作为固定边界或单独约束，不能让它作为零储容未知量参与 PCG；
- 接受它作为数值残差，就把断言改成基于装置尺度的物理阈值，例如 `FluidPhysicalAssertions.staticMotion`，不要用 `1e-12` J 这种与压力容差无关的绝对值。

另外，`gas-liquid` 的毛细投影仍有一个代码级问题：`PressureFaceGraph.pressureAtSide` 给液体侧和气体侧都加了同一个 `0.5(fill+peer)J`。面方程两侧系数相反，这个常数项会相互抵消，投影实际上不施加 Laplace 跳跃；而 `PressureSystem.liquidPressureAt` 又给查询加完整 `J`。`M5` 的静态测试只检查了查询值，没有检查投影产生的力，所以这个不一致不会被现有测试抓住。液相查询、相变判据和投影应使用同一个跳跃定义。

### 4. 预算在最终产物上两次不通过

最终产物上我跑了两次 `production-live-budget`：

| 运行 | 控制器中位数/最大 | 溃坝最大 | 连续 >200 ms | 结果 |
|---|---|---|---|---|
| 第一次 | 16.050 / 18.017 ms | 260.119 ms | **4** | FAIL |
| 第二次 | 22.666 / 35.394 ms | 305.405 ms | **3** | FAIL |

控制器 40 ms 预算是过的；失败的是最大 demo 的连续暂停规则。第二次运行连续三步超过 200 ms，已经达到失败条件。

文档引用的 `review2-final-budget2.log` 首行明确写着：

```text
CPU validation revision=07f8b98e...
sourceSHA256=50b99cfc...
```

而最终源码是 `804277e` / `cfe5547`，source SHA 是 `93abba40…`。`review2-final-budget2` 是在最终提交之前、基于 07f8b98 加工作区修改测的，不能作为最终产物的预算证据。`validation.md` 自己也写了“版本 `07f8b98` 加工作区修改”，但表格仍把它标成最终通过。

即使按他们最乐观的那次记录，demo 最大 286.903 ms、连续两步超过 200 ms，距离“连续三步”的失败线也只有一步。这个预算目前没有稳定余量。

---

## 四、守恒与物理特性

**仍然成立的部分：**

- 质量、体积：`cell-inventory`、`containers`、`free-surface` 的整数质量和精确体积账本都通过；预算日志中控制器体积始终 128.000000000，溃坝体积 2591.9976 只是传输字量化尾差。
- 内能：`thermal` 通过局部热平衡、热平流、分层和自然对流；`cell-inventory` 通过 U 舍入与机械热账。
- 颗粒插入：审计覆盖顺序已修正，`cell-inventory` 通过。
- 相占据/化学恢复：`chemistry` 通过。
- 切格静水：`cut-cell:short` 通过；新的 `PressureCapacityValidation.cutHydrostatics` 覆盖了部分切格静水。
- 演示场景没有数值爆炸；自由面短场景、容器长序列、热对流都稳定。

**仍然存在的物理/守恒缺口：**

1. **液体能量分份改为浮点份额。** `LiquidCellInventory.partitionEnergy` 删除了“总和等于源 U”的精确检查，改成 double 份额 + 最大份额吸收余量；`prepareTransport` 用端点差记录 roundoff。当前 `FluidThermalBinding` 和 `splitEnergy` 都会把余量给最大份额，所以测试能过，但接口层不再有强制后置条件。未来换一个 `EnergyPartitioner` 就可以静默丢 U。建议保留一个便宜的 `sum(shares) == source.internalEnergy()` 后置检查，或者在接口文档里写死“最大份额吸收余量”的契约。
2. **`ThermalCellEnergyAssignment` 不再验证最大热容份额恢复的温度。** 它现在把 `requested - others` 全部给最大容量载体，只检查每个载体的能量可表示；局部 LTE 的 `restored temperature` 检查被删掉了。`thermal` 当前通过，但极端分段热容的守护变弱。
3. **毛细压力没有进入投影。** 如上，`pressureAtSide` 的常数项在液体/气体两侧相互抵消；`liquidPressureAt` 加完整 `J`。这会让气液界面的表面张力只影响查询和相变判据，不影响动量方程。
4. **没有 CFL/速度上限。** `maxCourantNumber` 仍然不存在；供体库存把质量推进限制在每子步一格，h=0.5、dt=0.05 对应约 10 m/s，而预算日志里 `maxSpeed` 出现过 12.2 m/s。高流速下压力场和实际质量前锋会脱耦。文档现在承认“供体限流”，但这是一个明确的模型适用范围，不能当成完全等价的动量推进。
5. **长流程没有最终产物证据。** 5,000 步溃坝、600 步切格、完整沙床生命周期、完整热对流日志都早于最终提交；当前只有短入口和 `thermal` 全量是新鲜的。误差是否在长流程发散，仍然未在 HEAD 认证。

---

## 五、代码、架构与测试

**代码量：**

- 生产：约 464 个 Java 文件、65,124 行；
- 测试：94 个文件、33,556 行；
- `FluidDomain` 826 行，`GranularReactionStage` 987 行，`ThermalDomainState` 984 行，`AirNetwork` 952 行，`LiquidCellInventory` 763 行。

solver 的缩减是真实的，但单步仍然跨 `FluidDomain` + 多个 `Domain*` 处理器 + 大阶段类；最大文件仍接近 1000 行。`FluidDomain` 继续集中 175 个一行转发方法和大量 record，文件数下降没有同比例降低阅读面。

**死代码/假诊断：**

- `ConservativeLiquidFlux` 仍保留若干无调用者的诊断方法；
- `PressureProjectionWorkspace` 仍分配 `divergence` 等字段但没有实际读取；
- `LiquidCellInventory.Plan` 的 `volumeChange` / `massChange` 访问器已删除，但相关字段仍为零；
- 这些不是本轮主要矛盾，但会让排障者把“零值诊断”当成真实结论。

**测试质量：**

- 新 `GranularBedWakeValidation` 的 `macLiftPaysPotentialAndRecoil` 在最终代码上 NPE，说明这组测试没有被完整跑过；
- `AirCavity` 的液塞夹具用 0.012 L 气体驱动 1 m³ 液塞，物理量级不成立；
- `M5` 用 `1e-12 J` 检查封闭真空，和压力求解的容差尺度不匹配；
- `review2-functional6.log` 本身还是 `granular` / `gas-liquid` / `thermal` 三个失败、`air-cavity` 一个通过的日志，文档只引用了其中的 air-cavity 通过项。

这比“测试太严”更麻烦：**测试夹具本身没有被最终代码验证过**。一个接手者看到 `granular` 的 NPE，会先怀疑材料绑定；看到 `air-cavity`，会先怀疑液塞惯性；看到 `gas-liquid`，会先怀疑真空压力。三个方向都要读不同的文件，这正是“无止境排障”的来源。

---

## 六、验收证据链

这是本轮最严重的问题，和上一轮同类：

1. `validation.md:404` 引用 `review2-functional6.log` 的 `air-cavity` 通过；该日志是 04:01 的，最终压力/库存改动在 05:42；当前同一 gate 失败。
2. `validation.md:409` 引用 `review2-shared-core.log` 的 674 步沙床生命周期；该日志 05:23，`804277e` 05:42，当前 `runGranularBedValidation` 第一项 NPE。
3. `validation.md:413` 引用 `review2-final-budget2.log` 的预算通过；该日志首行 `revision=07f8b98`，不是最终 `804277e`/`cfe5547`；当前两次运行都失败。
4. `validation.md:415` 自己写明“版本 `07f8b98` 加工作区修改”，但表格仍把它当成最终预算；`validation.md:419` 又写“最终实现提交 804277e”。这两句已经把证据链断裂说清楚了。
5. `solver-consolidation-plan.md` 现在写“M10 整合与第二轮审阅整改完成”“M00–M10 共同主干与本轮整合验收已完成”。当前默认 `fast` 两个门红，预算红，不能成立。

---

## 七、对“降低维护和排障成本”的判断

**有真实进展：**

- 液体库存、质量、体积、物种、内能仍然集中在一套权威数据上；
- 上一轮的颗粒热账、相占据恢复、切格静水、化学输运都修好了；
- `cell-inventory`、`containers`、`chemistry`、`thermal`、`free-surface` 的通过说明主干不是全面崩坏；
- solver 代码量继续下降，旧的精确/点权威路径确实删掉了。

**但目标没有达成：**

- 默认 `fast` 仍然红，且失败点换成了新的夹具/NPE；
- 最终预算没有通过；
- 文档继续引用旧构建、旧日志和旧提交；
- 失败测试的物理量级和阈值本身不可信；
- 一次完整排障仍要同时读代码、测试夹具、日志来源和文档状态。

**结论：这一版适合继续修整合，不适合宣布 M10 完成，也不适合恢复 D01。**

---

## 八、建议的优先级

**P0：先让默认 fast 变绿**

1. 在 `LiquidCarrierProperties.refresh` 对 `materials == null` / `definitions == null` 给出明确语义，或在 `GranularBedWakeValidation` 绑定材料状态；然后重跑 `granular` 和 `runGranularBedValidation`。
2. 修正 `AirCavityScannerValidation` 的气腔体积（至少让液塞排开体积远小于气腔体积），或把断言改成能量/位移的物理界限；重跑 `air-cavity`。
3. 决定 `gas-liquid` 真空的契约：要么把真空行做成固定边界，要么把 `1e-12` 断言换成 `FluidPhysicalAssertions` 的尺度阈值；同时修 `pressureAtSide` 的毛细常数项。
4. 在最终产物上重新测预算；当前两次失败，不能再引用 `07f8b98` 的日志。若预算仍失败，先优化 demo 或明确调整暂停预算，不能继续标绿。

**P1：守恒与模型**

5. 给 `partitionEnergy` 加一个 cheap 的源 U 总和后置检查，保留浮点分份但防止静默丢能。
6. 决定 `ThermalCellEnergyAssignment` 是否恢复最大载体的温度闭合检查；当前极端分段热容只有“可表示”保障。
7. 明确无 CFL 的适用范围；给高流速场景加一个压力/质量前锋不脱耦的测试，或恢复 Courant 细化。
8. 在最终代码上重跑 5,000 步溃坝、完整切格、沙床生命周期和完整热对流；没有这些，不能声称长流程不发散。

**P2：清理与证据**

9. 移除 `ConservativeLiquidFlux` / `PressureProjectionWorkspace` 中仍为零的死诊断，避免下一个排障者读假信号。
10. 删除 `validation.md` 中 674 步、air-cavity、预算的旧引用，改成“当前构建实际结果 + 失败原因”；`solver-consolidation-plan.md` 的 M10 状态改回“未通过最终验收”。
11. 继续压缩 `FluidDomain` 和最大阶段类；当前 826/987/984/952 行的文件仍然是跨文件阅读的主要成本。

---

## 九、审查边界

- 我只读代码、跑 gate、写临时探针；没有修改任何现有源码。
- 临时探针 `build/round3-probe*` 已删除；新证据保留在 `build/round3-functional.log`、`build/round3-budget.log`、`build/round3-budget2.log`、`build/round3-containers.log`、`build/round3-quick2.log`。
- `docs/review/` 和 `docs/maintenance/build-outputs.md` 是另一位审查者的工作，我没有触碰。
- 未重跑 `production-replay`、`air-network`、完整 5,000 步溃坝和完整切格；这些当前都应视为“未在最终产物验证”。

一句话总结：**这一轮把上一轮的库存、相占据、切格和热账问题修掉了，但默认 fast 仍有 `granular` NPE 和 `air-cavity` 夹具失败，`gas-liquid` 的真空/毛细契约未收口，预算在最终产物上两次不通过；文档却继续引用旧提交的绿灯。现在需要的是先恢复真实绿灯，再谈 M10 完成。**