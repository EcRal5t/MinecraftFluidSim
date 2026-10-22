§364§ 第四轮审查基于 `3377b20`（生产源码 `3679a24`，源码 SHA `c9d89b67…`，审计产物 SHA `5d112185…`）。结论：**当前代码已经明确回退到“M10 待整改”，这个状态判断是正确的；但实现本身仍有跨模块的物理与恢复缺陷，默认验收组合远未转绿。**

与前三轮相比，这一轮最重要的进步是文档和自审终于如实记录了失败，没有再把分段通过包装成最终完成。下面是我独立复跑和代码核查的结果。

---

## 一、当前实际 gate 结果

| Gate | 结果 | 失败点 |
|---|---|---|
| `cell-inventory` | **PASS** | 库存、动量、墙面交接和编辑恢复 |
| `free-surface:short` | **PASS** | 短自由面、静水 |
| `air-cavity` | **PASS** | 分量接口、有限容量、液封 |
| `chemistry:short` | **PASS** | 局部混合、材料分离、恢复 |
| `thermal` | **PASS** | 热平流、导热、分层、自然对流 |
| `cut-cell` / `marker-transfer` / `marker-pressure` / `shape` | **PASS** | 黏性、切格、动量与压力传递 |
| `granular:short` | **FAIL** | 沙床 519 步仍有 1 个活动颗粒，超过沉降时限 |
| `gas-liquid:short` | **FAIL** | 真空容量测试没有触发预期超容 |
| `region-state` | **FAIL** | 冷恢复改变了气体存在性 |
| `surface-tension` | **FAIL** | 接触角扩展次序反向 |
| `air-network` | **FAIL** | 环境孔口没有把真空节点充到平衡 |
| `containers:short` | **FAIL** | seed=4、action=57，往返后压力差 1 个 float ULP |
| `production-live-budget` | **FAIL**（独立运行） | 溃坝最大 581 ms，连续 7 步超过 200 ms |

`fast` 组合已包含 `air-network`、`granular`、`containers`，也包含 `production-replay` 与 `entity-fluid`；前三者我已实测失败，后两者在自审中失败。因此默认 `fast` 当前不是绿灯。

自审登记的全量结果是 **29 个领域中 18 个通过、11 个失败**，与我复跑到的失败范围一致。

---

## 二、这轮确实改好的地方

- `air-cavity` 从上一轮的几何失败变成通过，分量接口、实际容量和液封路径已经联调完成。
- `cell-inventory` 通过，说明共同库存、MAC 动量、墙面停止和编辑恢复没有被这轮压力/气体改动破坏。
- `thermal` 完整通过，热平流、导热、稳定/不稳定分层和自然对流都有本轮结果。
- `free-surface:short` 通过；自审中的 5,000 步溃坝也通过，说明长流程在大尺度上仍有界。
- `PressureSparseMatrix` 把液体面、气体储容和颗粒耦合组装到同一个 CSR，方向是合理的，减少了多套矩阵。
- 自审报告明确列出了静水真空、颗粒浮力次序、气体来源分组和冷恢复问题，这对后续排障是正向资产。

---

## 三、仍然阻塞的核心问题

### 1. 静水支撑覆盖真实真空，真空失去压力接触

`PressureFaceGraph.prepareHydrostaticReference`：

```java
if (upperWeight > 0 && domain.liquidGrid.cellStockMass[cell] > 0
        && !hasFiniteGas(cell, support))
    pressureSupports[cell] |= support;
```

`hasFiniteGas` 只检查节点是否有连续状态。一个库存为 0 的真空节点同样满足 `!hasFiniteGas`，于是该支撑被划进液体压力行；随后 `pressureAtMicro` 优先消费液体行，真实真空节点不再参与压力接触和体积散度。

这正是 `gas-liquid` 现在的失败点：

```text
PressureCapacityValidation.vacuumCapacityClosure
Vacuum predictor did not exercise finite capacity overrun
```

这个断言不是测试写错，而是生产缺陷的体现：真空被错误地当成液体支撑后，预测速度不会在真空容量上触发超容。

**影响：** 真空腔旁的液体会表现出错误的压力闭合，压缩/膨胀预测和真空吸液都会偏。应把“已知静水参考载荷”和“压力自由度分类”分开，保留真空节点的 C/CT 接触。

### 2. 颗粒浮力晚于隐式阻力，沉降出现系统性上偏

`MechanicalStepPipeline` 的顺序是：

```text
重力 → 热浮力 → 表面张力 → predictDrag → pressureStage.solve(dt)
```

`PressureSystem.solveProjection` 在压力求解阶段才调用 `GranularPressureCoupling.prepare(dt)`，把颗粒的已知浮力载荷直接加到速度上。于是浮力绕过了已经施加的隐式阻力。

自审给出的固定点偏差是：

```text
v新 = (v旧 − gΔt)/(1 + γ) + b gΔt
```

对细砂 `ρ粒=2600`、`ρ液=1000`、`dt=0.05 s`，偏差量级约 `0.189 m/s`，足以改变沉降方向。

实际结果是沙床 519 步仍有 `active=1`、`deposits=16`、`sleeping=16`，最终在 `GranularBedWakeValidation.java:445` 超过沉降时限。质量、体积和能量账本都还闭合，但颗粒永远停不下来。

### 3. 容量分组被当成来源归属，受限孔口两侧会整腔混合

`ConservativeLiquidFlux.prepareGasGroups` 把旧气体和流入气体并入容量组，`inheritGasReservoirs` 再按旧腔容量比例把整组来源铺到每个干分量：

```java
for (int node = head[group]; node >= 0; node = next[node])
    gasReservation.add(row, node, dry * availableVolume(node) / total[group]);
```

这段代码的用途是容量可行性，但它同时决定了库存来源。两个相邻半液格经固定受限孔口连接时，只要互补气相流量超过解析量，就可能被并成一组；最终交换比例主要由旧腔容量决定，小流量幅度被消掉。

质量与 U 仍然整数闭合，所以现有守恒审计抓不住这种**错误的分布**。自审也把它列为“源码数据流确认、本轮没有动态夹具”的缺陷。

**影响：** 受限孔口两侧的气体可能在一次小流量交换里整腔混合，液封和孔流的物理语义被破坏。需要把容量分组和来源输运拆成两套数据规则，受限孔口继续由 `GasOrificeFlow` 结算。

### 4. 冷恢复会初始化原本不存在的气体

`FluidDomainStateMapper.restore` 在 `state.gas() == null` 时只绑定注册表，然后执行：

```java
domain.domainFlowFields.ensureGrid();
```

`ensureGrid` 经 `CellMacStage.rebuildGeometry` 触发 `GasTopologyCoordinator.refresh()`；`refresh` 在 `frame == null` 时直接 `commit(...)`，为剩余封闭空间创建环境气体。

所以冷恢复后 `gas presence changed`，`region-state` 在 `RegionGenerationValidationScenarios.java:73` 失败。审计可以通过“环境来源”闭合，但恢复语义已经改变。

**影响：** 存档中“没有气腔”的区域，载入后会凭空出现气体库存和来源账。这个副作用会在长时间运行和跨重启中累积。

### 5. 润湿宏观次序反向

当前 `surface-tension` 实测：

```text
contact-angle spreading varianceX = 16.9837 / 23.2160 / 29.5578 m²
peakK = 2980.95 / 2646.01 / 2730.47 J
```

测试要求亲水表面的扩展方差大于中性、中性大于疏水；当前正好反过来。第三轮曾记录 `23.98 / 22.10 / 15.09` 的正确次序，这轮新加入的墙面接触法向和曲率重构造成了回归。

这不是容差问题：接触角改变后，液滴的宏观铺展方向反了。`M4SurfaceTensionValidation.java:413` 的断言应当保留。

### 6. 孔口真空平衡仍未收敛到验收要求

`air-network` 在 1 秒后失败：

```text
ambient orifice did not fill a vacuum node to equilibrium
AirNetworkValidation.java:907
```

自审已经指出，当前一阶隐式离散加整数通量量子，在单节点方程上会留下约 13.67 Pa 的瞬态误差，而终端一个 nanos 约对应 0.000101 Pa。这个差距需要明确为模型适用的瞬态误差，或者调整离散；不能用“更长时间”掩盖。

### 7. 容器往返的压力字段有 1 个 float ULP 差异

`containers` 在 seed=4、action=57 失败，根因是：

```text
phase-pressure field raw bits changed at [263]:
-94941.97 vs -94941.96
```

对应约 0.0078125 Pa。库存和内能仍然精确闭合，所以这不是质量或能量丢失，而是“恢复后派生压力是否要求逐位相等”的契约问题。

需要明确选择：

- 压力是持久权威字段：恢复必须逐位复制；
- 压力是派生字段：往返比较使用容差，精确库存和 U 继续逐位比较。

当前测试用 `assertSameState` 比较原始 float 位，和实现“重建派生压力”的策略冲突。

---

## 四、守恒与流体特性

**仍然成立：**

- 质量、物种、体积：`cell-inventory`、`free-surface`、`thermal` 的整数/精确账本通过；自审中的 5,000 步溃坝库存、容量和恢复检查通过。
- 内能与相变热账：`thermal` 完整通过，颗粒压实/沉降/松弛的机械热与 U 舍入分开入账。
- 气体库存：自审记录 64 m³std 总量闭合；当前 `air-cavity` 的容量与液封通过。
- 动量：共同 CSR 的液体面、气体储容和颗粒耦合共用一套转置关系，`cell-inventory` 与 `marker-*` 通过。
- 数值稳定性：5,000 步溃坝的动能从约 563 J 降到末态 291 J，中间最低 203 J；短流程没有出现爆炸。

**仍然有问题：**

1. **真空压力接触缺失**：如上，静水分类吞掉了真实真空。
2. **颗粒浮力/阻力次序**：沉降出现系统性上偏，沙床无法完成生命周期。
3. **气体来源混合**：总量守恒，局部腔体分布错误；现有审计无法发现。
4. **恢复副作用**：冷恢复凭空初始化气体，来源账可以闭合但物理状态改变。
5. **润湿方向反向**：接触角没有按物理方向影响铺展。
6. **孔口瞬态误差**：一阶隐式离散 + 整数量子，1 秒均压仍不符合验收。
7. **完整机械能闭合**：全域黏性、阻力和实体做功尚未统一结算；当前只能确认 U、K 和各阶段做功分别有账，不能宣称所有场景的 `U+K+势能` 严格守恒。自审也已明确这一点。

所以“误差是否发散”要分两层回答：

- **数值上**，已跑的 5,000 步溃坝和热对流没有发散，短流程也有界；
- **物理上**，颗粒浮力次序、气体来源混合和润湿反向会持续积累系统性偏差，不会以数值爆炸的形式出现，但会让长时间行为越来越不物理。

---

## 五、代码、架构与测试

**体量：**

- 生产代码约 **487 个 Java 文件、76,095 行**；
- 测试约 **94 个文件、35,406 行**；
- 本轮 `cfe5547..3377b20` 变更 73 个文件，**+3,460 / −2,745 行**。

**集中度没有改善：**

- `GranularReactionStage` 987 行；
- `ThermalDomainState` 978 行；
- `AirNetwork` 952 行；
- `VulkanFlipPipeline` 945 行；
- `FluidDomain` 826 行；
- `LiquidCellInventory` 776 行；
- `DomainCapabilities` 694 行。

更重要的是，当前三个 P1 缺陷跨越了四条职责链：

```text
静水真空：PressureFaceGraph 分类 + BodyForceStage + GranularPressureCoupling + PressureSystem
气体来源：ConservativeLiquidFlux 分组 + GasTopologyCoordinator 归属 + GasVolumeAllocation
冷恢复：  FluidDomainStateMapper + CellMacStage + GasTopologyCoordinator.refresh
润湿：    DomainFlowFields 曲面法向 + 墙面接触 + 压力载荷
```

这正是“排障要读、改一大堆文件”的典型形态。文件数下降没有带来职责集中；`FluidDomain` 仍然集中大量转发和状态，压力分类、已知载荷和来源归属散在多个阶段。

**测试质量：**

- 新增的 `PressureCapacityValidation` 是一个好的聚焦测试，它直接抓到了真空压力接触缺陷。
- `M8GranularValidation` 的沙床生命周期是有意义的完整流程测试；它现在暴露了沉降问题。
- `M4SurfaceTensionValidation` 保留了接触角次序断言，失败是真实回归。
- `CapillaryNeckValidation` 被删除 223 行，属于覆盖损失；需要确认是否有等价的新测试承接。
- `containers` 的精确压力断言与派生压力策略冲突，需要统一契约。
- `production-live-budget` 在独立入口失败，而在自审的完整组合中通过，说明预算测量依赖 JVM 预热/机器状态；至少要把预热策略和测量条件固定下来。

---

## 六、是否达到“降低维护和排障成本”

**这一轮有一个真实进步：** 文档不再谎报绿灯。`solver-consolidation-plan.md` 明确写“M10 待整改与验收”，`validation.md` 也记录了 18/29 通过和 11 个失败领域。这比前几轮“完成”后继续引用旧日志要好，至少下一个接手者不会被虚假状态误导。

**但目标本身没有达成：**

- 默认 `fast` 仍有多领域失败；
- 失败集中在压力分类、浮力次序、来源归属和恢复语义，都是跨模块交接问题；
- 现有守恒审计只能证明总量闭合，无法证明局部来源和物理响应正确；
- 单点缺陷需要在 4–6 个文件之间追踪；
- 测试集仍有精确断言与派生状态冲突，长流程入口和独立入口结果不一致。

因此当前适合继续整改，**不适合宣布 M10 完成，也不适合恢复 D01**。

---

## 七、建议的优先级

**P0：先让核心物理和恢复契约正确**

1. **分离静水参考载荷和压力自由度**：保留真空节点及其 C/CT 接触，`PressureFaceGraph` 的支撑分类不能再把零库存真空并入液体行；用 `PressureCapacityValidation` 作为回归。
2. **统一颗粒体力次序**：把重力、静水浮力和残余压力准备在同一步完成，再交给隐式阻力；用完整沙床生命周期回归。
3. **拆分气体容量分组与来源归属**：容量组只约束接收量，来源沿实际通量交接；受限孔口继续由孔流结算，补一个两半液格受限孔口的动态夹具。
4. **恢复不得初始化气体**：为冷恢复提供“只重建派生网格、不创建气腔”的路径；`region-state` 保留气体存在性断言。
5. **修正润湿次序**：沿曲面法向、墙面接触角和压力载荷整链检查，恢复亲水 > 中性 > 疏水的扩展次序。

**P1：收敛验收契约**

6. 明确孔口 1 秒均压的验收范围，按一阶离散和整数量子给出合理终态误差，或者改离散；不要只延长时限。
7. 明确恢复后压力是权威字段还是派生字段：前者逐位复制，后者用容差比较；库存和 U 继续精确比较。
8. 固定 `production-live-budget` 的预热和测量条件，并在空闲机器上重复；当前独立运行最大 581 ms、连续 7 步超限，不能算通过。
9. 为气体来源混合、真空接触和颗粒沉降各保留一个最小动态回归，避免只靠源码审阅。

**P2：清理与证据**

10. 恢复或替代 `CapillaryNeckValidation` 的覆盖，避免删掉真实的失败模式。
11. 继续压缩 `FluidDomain`、`GranularReactionStage`、`ThermalDomainState`、`AirNetwork` 的职责；优先把静水载荷、气体来源和恢复准备集中到明确的数据归属。
12. `validation.md` 保留自审的未通过范围，删除任何会被误读为最终通过的旧分段表。
13. 继续保留本轮的自审报告；它比“完成”声明更有维护价值。

---

## 八、审查边界

- 我只读代码、跑 gate、写临时脚本；没有修改任何现有源码。
- 本轮没有遗留临时探针；证据日志保留在 `build/round4-functional.log`、`round4-region-budget.log`、`round4-containers.log`、`round4-surface-air.log`。
- `docs/maintenance/build-outputs.md` 和 `docs/review/` 中其他审查者的内容我没有触碰。
- 未重跑 `production-replay`、`entity-fluid`、`controller-gas-lineage` 和完整 5,000 步溃坝；它们沿用当前源码的自审记录，不作为本轮新通过证据。

一句话总结：**这一轮的自审和文档是诚实且有价值的，`air-cavity`、`cell-inventory`、`thermal` 等主干也确实在推进；但真空压力接触、颗粒浮力次序、气体来源归属、冷恢复和润湿次序仍是实打实的模型缺陷，默认 fast 仍未转绿。M10 应继续保持“待整改”，先完成这三条职责链，再谈验收。**