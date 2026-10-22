# 第五轮负向审查

审查对象：`5cf7e24`。CPU 审计记录 `revision=5cf7e24`、`sourceSHA256=311a48b892600afc…`、`artifactSHA256=8dad25b9dbaa9d92…`，JVM `-ea -Xmx512m`。

本轮只记录做得差的地方，不记录优点。

结论：**M10 的“完整领域、长期稳定、原生产预算均通过”不成立。** 当前 green 主要来自三类操作：拆开多次构建拼接证据、在失败处放宽或删除断言、把墙钟抖动留作“通过记录”。不存在一次同一最终产物上的 29/29 全量通过。另有一条高危潜伏缺陷：`GranularReactionStage` 先提交反应位移，随后又用未位移库存生成编辑计划，把位移整体回滚，同时保留位移端点的气体容量和动量。

---

## 一、证据链是拼接的，不是最终全量

### 1. `full` 只有 28/29，且失败项是 production-replay

`build/drift-capacity-full.log:111`：

```text
revision=f299f56
sourceSHA256=b74f3728204e4f5f…
artifactSHA256=7ce556406bf33bcdef…
selection=[full]
```

`drift-capacity-full.log:587`：

```text
CPU validation selection 'full' failed 1 of 29 gates
```

失败项是 `production-replay`，不是“仅测试夹具问题”的旁支：

```text
ASSERTION_FAILURE production-replay:
compressed planar changed equilibrium energy:
0.11840897324444521 J, allowance=0.1 J at step 3
```

### 2. 所谓“修正后 29 个领域”没有全量运行

`build/drift-capacity-final.log:111`：

```text
revision=699091c
sourceSHA256=311a48b892600afc…
artifactSHA256=8dad25b9dbaa9d92…
selection=[production-replay]
```

这个日志只运行 **1 个 gate**，不是 `full`。`validation.md:414` 却把 28/29 与这一个 gate 合并成“29 个完整领域覆盖齐全”。`validation.md:457` 自己写明“本次没有重复运行全量 29 领域”。两句话不能同时成立。

当前 `review5-fast.log` 是同一 artifact 的 `selection=[fast]`，只有 19 gates；`review5-targeted.log` 是 `surface-tension,gas-liquid,region-state` 3 gates。把这些日志相加也不等于一次 `full`。

### 3. 最终预算表与失败记录冲突

同一 artifact 的预算结果：

| 日志 | 控制器 | 最大 demo | 连续超 200 ms | 结果 |
|---|---|---|---|---|
| `drift-capacity-final-budget-1.log` | 14.948 / 19.671 ms | 206.511 ms | 1 | 通过 |
| `drift-capacity-final-budget-2.log` | 15.287 / 19.620 ms | 235.318 ms | 4 | **失败** |
| `drift-capacity-idle-budget-1.log` | 15.545 / 17.668 ms | 200.773 ms | 1 | 通过 |
| `drift-capacity-idle-budget-2.log` | 单步 55.039 ms | 未运行 | — | **失败** |
| `drift-capacity-idle-budget-3.log` | 15.563 / 23.805 ms | 258.485 ms | 2 | 通过 |

同一构建、同一入口、五次独立运行，**2 次失败、3 次通过**；两次通过的最大 demo 分别只有 1 步和 2 步余量，第三次通过的最大值 258 ms 已经离 200 ms 阈值很远。`validation.md:420-428` 保留了一次控制器失败，却仍把 M10 写成完成。原验收条件是“控制器逐步不超过 40 ms、最大 demo 连续超 200 ms 少于三步”，55 ms 已经是明确的硬失败。

`drift-capacity-idle-budget-2.log` 的步骤输出与断言行还有一处错位：

```text
production live step 13: 14.481 ms
production live step 14: 55.039 ms
ASSERTION_FAILURE ... production step 13 exceeded ... 55.0387 ms
```

说明当前日志/断言至少有一处 step 编号不一致，不能作为可复现的预算证据。

### 4. 长跑与预算日志没有构建哈希

`drift-capacity-final-soak.log`、`drift-capacity-idle-budget-{1,2,3}.log` 开头直接是场景输出，没有 `revision`/`sourceSHA256`/`artifactSHA256`。唯一能关联构建的 `fabric/build/cpu-validation-work/classpath.txt` 修改时间晚于这些日志的证据窗口，无法证明它们来自同一 classpath。将无哈希日志与有哈希日志拼成“最终通过”不可审计。

### 5. `fast` 不是“完整入口”，且遗漏说明不完整

`CpuValidationSuite.java:52-55` 的 `fast` 只有 19 个 gate，不包含：

```text
materials, surface-tension, gas-liquid, region-state, takeover,
ownership, liquid-joining, thermal-extremes,
controller-gas-lineage, production-live-budget
```

`validation.md:34` 只明确说明最后两个需要显式选择，其余 8 个没有在默认入口说明中列出。根项目 `check` 默认运行 `fast`，因此默认 CI 绿灯会漏掉表面张力、气液、恢复和长期实体等关键领域。

---

## 二、测试在明确失败的地方放宽或删除断言

### 1. liquid-pair Laplace 压力实测为 0，测试仍通过

`review5-targeted.log`：

```text
liquid-pair droplet sigma12=0.0728
expectedJump=0.03731 measuredJump=0.00000 error=1.00
PCG=0 residual=0.0342 maxSpeed=5.77e-06
M4 surface-tension solver gate passed
```

`M4SurfaceTensionValidation.java:254-258` 的判据是：

```java
double allowance = pressureResolution(config);
check(Double.isFinite(measuredJump)
    && Math.abs(measuredJump - expectedJump) <= allowance, ...);
```

`pressureResolution`（`:295-297`）在 `rho=1000, h=0.5, dt=0.05, VOLUME_RESOLUTION=4e-5` 时为：

```text
1000 * 0.25 * 4e-5 / 0.0025 = 4.0 Pa
```

允许误差是期望值 `0.03731 Pa` 的 **107 倍**。实测为 0、误差 100% 仍判通过。这个 gate 不能证明液-液 Laplace 压力存在。

同一测试的夹具本身也没有压力响应。临时探针结果：

```text
active=true nodes=0 stocks=8000
before maxPairCurv=0.0 nz=0 maxP=0.0 maxSpeed=0.0
after  maxPairCurv=3.1168884968002617 nz=4900 maxP=0.0 maxSpeed=5.771321E-6
inside=0.0 outside=0.0
```

夹具是全液体封闭域（`nodes=0`，无自由表面），压力锚定在 0；`surfaceTensionPairCurvature` 已经非零，但压力场始终为 0。该 fixtures 不能验证“液态对压力跳变”，只能验证一个极小的速度响应。若生产路径需要液-液压力，当前测试没有覆盖；若不需要，测试名称和文档必须改。

### 2. 静态液滴只断言“压力为正”，不校验 Laplace 幅值

`M4SurfaceTensionValidation.java:100-105` 计算了 `jumpError` 并打印，但判据只有：

```java
check(Double.isFinite(measuredJump) && measuredJump > 0, ...)
```

当前输出为 `analyticJump=0.03640, measuredJump=0.03555, error=0.0233`，目前数值尚可；但任何把压力降到 1 Pa 的回归都能通过。这个 gate 已不再是 Laplace 压力精度的守护。

### 3. entity-fluid 的尾段/末态动能包络被删除

旧测试在 `ad465a6` 之前要求：

```java
require(tailMaximumKinetic <= 0.025 && finalKinetic <= 0.002,
    "dynamic player disturbance did not decay to the stationary-body energy envelope");
```

当前 `DynamicReplayValidationScenarios.java:128` 只检查位移、尾段高度和尾段竖直速度；`tailMaximumKinetic` 和 `finalKinetic` 仍然被计算和打印，却没有参与断言。`fast` 输出：

```text
dynamic player float gate:
K max/tail/final=34.1853105/0.00143157766/0.00133052412 J,
body work=247.871585 J,
unexplained energy max=0.00000000 J
(fixed allowance 2452.52510 J)
```

当前尾段/末态值本来能通过旧包络，但旧断言仍被删除。代替它的是 `energyAllowance`：

```java
double energyAllowance = 0.025 + Mass.kilograms(domain.totalMassUnits())
    * gravity * cellSize * UNRESOLVED_VERTICAL_GAP_FRACTION;
```

日志显示该 allowance 为 **2452.525 J**，是 body work 247.87 J 的约 **10 倍**。也就是说，凭空产生 2.4 kJ 仍可能通过“能量守恒”检查。紧接着的 `unexplained=0` 还减去了与原始误差同量级的 `potentialUncertainty`，并非独立的能量闭合。

### 4. compressed planar 的“0.025 J 固定预算”实际约 0.196 J

`DynamicReplayValidationScenarios.java:357-363`：

```java
double potentialUncertainty = perturbed ? verticalPotentialUncertainty(restored) : 0;
double energyError = Math.max(0, Math.abs(rawEnergyError) - potentialUncertainty);
require(... Math.abs(energyError) <= energyAllowance ...);
```

`drift-capacity-final.log`：

```text
max raw energy error=0.171574664 J
max cell-centre uncertainty=0.171569964 J
```

原始误差 0.1716 J 几乎全部被 0.17157 J 的“cell-centre 不确定度”抵消，最终报告约 0.00068 J。`energyAllowance` 写的是 0.025 J，但**有效原始能量上限约 0.1966 J**。`drift-capacity-full` 中失败的 0.1184 J 正是这样被“修绿”的：`699091c` 改变了比较方式，而不是降低 0.1184 J。

此外，perturbed 场景的扰动只有 `Request(2,1.9,2,.001,.001,.001,.1)`，即 1 mm/s；初始动能约 2e-5 J，而记账不确定度约 0.17 J，**噪声是信号的约 8000 倍**。这个场景无法检验“撤去外力后动能衰减”。

### 5. AirNetwork 的真空平衡从 0.001 Pa 放宽到 101.325 Pa

旧断言要求 `|gaugePressure| <= 1e-3 Pa` 且库存精确 `1_000_000_000`。当前 `AirNetworkValidation.java:163-169`：

```java
check(Math.abs(previous) <= 1e-3 * AirNetwork.REFERENCE_PRESSURE, ...);
long deficit = 1_000_000_000L - restored.standardVolumeNanos(2);
check(deficit >= 0 && deficit <= 1_000_000L, ...);
```

`1e-3 * 101325 = 101.325 Pa`，是旧阈值的 **101,325 倍**；库存允许 1e6 nanos 的缺口。这个宽松度可以合理化为“宏观一阶瞬态”，但文档没有记录这个数值，仍把该场景称为“equilibrium”。如果 13.67 Pa 的残余是模型接受的，应写成明确容差并给出物理依据；不能用“补满”措辞掩盖。

### 6. M3 的 PCG 迭代上限被删除

`M3MaterialTransferValidation.java:198-199` 现在只检查：

```java
check(Float.isFinite(stats.pressureResidual()) && stats.pressureResidual() <= 1.0E-3F, ...)
```

旧测试的 `pressureIterations() > 0 && <= 32` 上限已消失；仓库内也没有别的地方再约束 `pressureIterations()` 上界。`validation.md:410` 却写“压力精度、迭代额度…保持原值”。删除上限后，求解器可以在任意多次迭代后仍判“通过”，维护者无法从测试发现迭代爆炸。

### 7. 极压夹具缩成单格腔，并用 50% 容差

`ExtremeThermalValidation.java:25-28` 在 `pressure >= 1e12` 时把 64 格中的 63 格设为固体，只剩 `(1,1,1)` 一个半液格；`:53` 接受：

```java
actualPressure > 0.5 * pressure && actualPressure < 1.5 * pressure
```

所谓“10^12 Pa 极压域”实际是单格腔，压力允许 ±50% 误差。这个测试不能证明多格高压、压力梯度或高压下的相变/恢复行为。

### 8. 5,000 步“静止压缩气腔”是恒零不动点

`drift-capacity-final-soak.log` 每个 500 步采样都是：

```text
compressed planar backend step 5000:
K=0.00000000 J dV=+0.00000000 m3 PCG=0 residual=0.00000000
unresolvedE=0.00000000 J
```

这是完全平坦、无外力、无相变的封闭半填充状态；压力求解 0 次迭代，当然不会演化。`validation.md:415` 把它当作“长期稳定性”证据，只能证明恒等初始条件保持恒等。

真正有扰动的 `self-review5-compressed-soak.log` 反而暴露了问题：

```text
max unresolved energy=4961.88272 J / 35817.0531 J allowance
```

允许 35.8 kJ 的未解释机械能，实测已达 4.96 kJ；温度在 250 s 内漂移约 0.026 K。这个预算与“严格守恒”不是同一量级。

### 9. 接触角测试只检查次序，且允许异常大的动能

`review5-targeted.log`：

```text
contact-angle spreading varianceX=46.9487/39.5065/0.843887 m^2
peakK=4708.35/4125.03/20155.9 J
finalRMS=0.582677/0.520303/1.42910 m/s
```

测试只要求 `hydrophilic > neutral > hydrophobic`（`M4SurfaceTensionValidation.java:175`）。疏水组方差只有 0.8439 m²，约为中性组的 1/47；峰值动能 20,155 J，是亲水组的 4.3 倍。测试没有任何液滴形状、接触角或动能上界，无法区分“疏水收缩”和“数值弹飞”。

`M4SurfaceTensionValidation.java:154` 的 `|curvature + cos(theta)| < 1e-6` 只是对同一生产场读回后重算模型常数，且 `h=1`；它验证的是公式自洽，不是铺展物理。

### 10. 其他测试契约被改写

- `FluidDomainStateAssertions.java:170-177` 把相压力从原始 float 位比较改为 4 ULP 重建容差；`validation.md:248` 仍宣称完整位模式比较。对派生压力可以接受，但文档和测试名没有同步。
- `RegionCodecValidationScenarios.java:633` 的 `expected` 改为 `region.domain().exportMaterialParticleState()`，是在绑定之后从同一对象导出的；原先独立构造的 `materialCodecParticles(export, palette)` 不再是唯一基准。物性索引到 palette 的映射不再有独立 oracle。
- `ControllerReplayValidationScenarios.java:379-381` 把 `nodeCount()==1` 改成“总容量减最大腔 ≤ 一个 cellVolume”，当前日志 `peakNodes=24`、主腔 33.99991 m³；单所有者结构被削弱成“剩余小腔足够小”。
- `MovingBodyNeighborhoodValidation` 被删除 33 行；生产批量入口 `DomainStep.applyMovingBodyCouplings` 只被单实体回放间接覆盖，没有批量顺序/多体一致性测试。

---

## 三、数值与物理残留

### 1. 反应位移计划提交后又被回滚（高，潜伏）

`GranularReactionStage.java:822-833` 先用反应后的候选库存 `liquid` 和反应后的固体占容做 `prepareDisplacement`，得到位移端点 `transport`，然后立即：

```java
var transportedLiquid = transport.inventory().withMechanicalHeat(...);
transportedLiquid.commit();          // 位移端点已发布到 domain.liquidCells
```

紧接着 `:835-845` 逐 cell 比较 **已位移的 `domain.liquidCells`** 与 **未位移的 `liquid`**，把差异写成 `Edit(key, liquid.stock(key))`：

```java
for (var key : keys)
    if (!Objects.equals(domain.liquidCells.stock(key), liquid.stock(key)))
        edits.add(new LiquidCellInventory.Edit(key, liquid.stock(key)));
var stockPlan = domain.liquidCells.planEdits(edits);
```

这会：

- 把被位移到邻格的体积改回源格；
- 把邻格新增的 support/key 以 `null` 删除；
- 将 `transportedLiquid` 写入的 U 覆盖回 `liquid` 的 U，而 `advectionHeat` / `advectionRoundoff` 仍被记入审计。

随后 `:847-848` 的气体计划用 **未位移的 `stockPlan.future()`** 搭配 **已位移的 `transportedGas.preparedCapacity()/futureOccupancy()`**；`:863` 又提交位移端点质量矩阵算出的动量。只要反应后单元内剩余液体超过新孔隙、邻格有容量，最终库存就会违反 `liquid <= pore`，下一次非 prepared 路径的 `GasComponentCapacity.availableVolume` 会抛 `"Liquid inventory exceeds its open component capacity"`；如果 `momentumTransfer` 产生非零混合热，`exactInternalEnergy` 与 `expectedInventory` 也会不闭合。

现有 `GranularReactionCandidateValidation`、`M7ChemicalTransportValidation` 都从静止初值出发，位移不触发、混合热为 0，因此看不到这条路径。没有任何断言比较 `stockPlan.future()` 与 `transport.inventory().future()`。

### 2. 审计的“舍入项”被定义为实测残差，源/汇错记不可见

`MaterialTransferPlanner.java:287-288`：

```java
var after = before.withExternalSourceEnergy(sourceEnergy)
    .withExternalSinkEnergy(sinkEnergy)
    .withMechanicalWork(inventory.mechanicalHeat())
    .withRoundoff(inventory.energyChange().subtract(sourceEnergy)
        .add(sinkEnergy).subtract(inventory.mechanicalHeat()));
```

`ThermalAuditLedger.expectedInventory()` 的定义是：

```text
initial + source - sink + mechanical + finiteDevice + roundoff
```

把 `roundoff` 直接写成“实测 energyChange 减所有其他项”，会使 `expectedInventory()` 恒等于实测 `energyChange`。因此 `exactInternalEnergy().equals(expectedInventory())` 在这些路径上不能发现源/汇记错或机械功漏记，只会把差额塞进 roundoff。`GranularTransferPlanner.java:444-450` 还把同一差额同时写成 `externalSinkEnergy(settlement)` 和 `withRoundoff(settlement)`，使 sink 与舍入项重复。

真正有约束力的是 `MovingBodyCouplingValidation.java:32-35` 的 `energyStorageAllowance` 对舍入量级的上界；普通热化学/气体交换场景没有同类上界。

### 3. 压力求解接受 0 次 PCG、残差 20.2

`review5-fast.log` 的 `cell-inventory`：

```text
TRANSPORT rotation-0 step=2
fullMs=... pressureIterations=0 residual=20.2024
```

同一 gate 仍判 PASS。`PressureLinearSolver.attainableTolerance` 会把矩阵尺度纳入“可达容差”，因此残差 20.2 可能被视为已完成；但测试没有任何上界，日志也没有解释 20.2 的单位/是否可接受。压力场在这种情况下是否收敛，不能从 green 结果判断。

M3 的迭代上限又被删除（见上），这类“0 次迭代 + 大残差”不会再被测试抓住。

### 4. 仍然没有 CFL/速度限制

`DomainStep.java:426-428`：

```java
int calculateSubsteps(float dt) {
    return Math.max(1, domain.minimumMechanicalSubsteps);
}
```

没有 Courant 数、最大速度或面通量限制。生产日志出现 `maxSpeed=12.2 m/s`，而 h=0.5、dt=0.05 的单步供体输运上限约 10 m/s；高流速时压力场给出的速度与实际质量推进脱耦。供体库存限流保证不爆炸，但不能保证压力/动量预测与输运一致。

### 5. 液-液压力测试没有覆盖真实自由表面

临时探针显示 `nodes=0`、`stocks=8000`、`maxP=0`；夹具是封闭全液体，没有气相或自由面，压力锚定后液-液界面力只能产生 5.7e-6 m/s 的微小速度。若液-液张力只在有自由面/气腔时产生压力，测试应换成两液相 + 气相或开放边界；当前的 all-liquid 夹具不能证明生产场景。

### 6. 总机械能没有统一闭合

`self-review5-compressed-soak.log` 的未解释机械能预算为 35,817 J，实测 4,961.88 J；`dynamic player` 的 allowance 2,452.5 J；`compressed planar` 的原始误差 0.1716 J 被不确定度抵消。质量、体积、U 账本可以精确，但 `U + K + 势能` 的全局闭合仍依赖大额观测容差，不能称为“能量守恒通过”。

### 7. 气体一阶瞬态仍用宽松阈值

`air-network` 的真空填充在 1 秒后允许 101 Pa 残差；`gas-liquid` 的孔口链在 59 步后仍有 0.71% 初始压差。两者可以解释为一阶隐式离散的宏观误差，但验收文档没有给出适用条件、时间常数和误差量级，只是把它们判绿。

### 8. 边界/恢复契约仍有未覆盖组合

`region-state` 当前通过，但 `FluidDomainStateMapper.java:97-99` 仍然通过 `ensureVelocityGrid()` 与 `ensureGrid()` 的分支决定是否初始化气体；只要未来调用路径改变，冷恢复的“气体存在性”仍可能被派生准备副作用改写。该分支没有统一的“恢复只做派生准备”接口，测试只覆盖当前 fixture。

---

## 四、架构与维护成本

### 1. 生产代码没有继续缩小

当前：

```text
生产 Java：486 文件，76,394 行
测试 Java：94 文件，35,779 行
3377b20..HEAD：64 文件，+1,994 / −1,228
```

本轮净增约 766 行 diff，生产代码总量仍在 76k。大文件：

```text
GranularReactionStage 987
ThermalDomainState 978
AirNetwork 952
VulkanFlipPipeline 945
FluidDomain 824
LiquidCellInventory 776
DomainCapabilities 694
```

`GranularReactionStage` 距离 1000 行硬上限只剩 13 行；`FluidDomain` 仍是 824 行。

### 2. `FluidDomain` 仍是跨模块可变状态中心

`FluidDomain.java:79-132` 有约 48 个包可见、非 final 字段。`pressurePhaseGeometry` 由 `CellMacStage.java:157` 写，`pressureGranularCoupling` 由 `:145` 写，`pressureAnchor`/`pressureReference` 由 `PressureFaceGraph.java:173` 写，`lastPressureIterations` 由 `PressureSystem.java:38` 写。一个压力分类/气体来源/恢复缺陷仍然要在 6 个写入方和 10 个读取方之间追踪。

### 3. `PressureFaceGraph` 仍混合七类职责

`PressureFaceGraph.java` 504 行同时负责：

- 面拓扑（`:35-63`）；
- 压力支撑分类与 union-find 锚定（`:65-104`, `:166-173`）；
- 气体压力载荷（`:158-162`）；
- 湿面/端口/支撑几何（`:252-347`）；
- 解析静水柱（`:376-435`）；
- 矩阵行装配（`:456-462`）；
- PCG 种子、散度和速度投影（`:465-493`）。

它还向上调用 `domain.cellMacStage` 的墙面支撑，向下调用 `domain.tension` 的毛细压力。液-液压力、静水和气体真空三个问题都跨这条链，排障不能只看一个文件。

### 4. `CellMacStage` 与 `GranularReactionStage` 有两套提交顺序

`CellMacStage.transport`（`:402-424`）和 `GranularReactionStage.java:821-879` 各自排序：

```text
prepareLiquid → CellMomentumTransport.prepare → withMechanicalHeat →
auditLedger → gas plan → requireCurrent → commit → commitMomentum →
restoreAuditLedger
```

两套已经不一致：反应路径没有 `beginDissipation()`，并且使用 `commit` 而不是 `commitPrepared`，也没有与 `CellMacStage` 共享一个事务模板。热账、耗散、气体占用在异常路径上的行为没有等价测试。

### 5. `SurfaceTensionFields` 是字段袋，不是状态所有者

`SurfaceTensionFields.java:13-21` 的 10 个包可见数组没有不变量或 dirty 跟踪。`BodyForceStage.java:140-205` 会把 `surfaceTensionColor`、`surfaceTensionSmoothed`、`surfaceTensionNormal*` 覆盖成 phase-B 数据，把 phase-A 暂存在 `surfaceTensionPairFirstSupport`。`SurfaceTensionFields` 的类注释声称“所有字段从已提交库存和几何重建”，在 pair 过程后不成立。法向重建还存在两套实现（`SurfaceTensionFields.java:110` 与 `BodyForceStage.java:191`），执行顺序依赖 `CellMacStage.java:161` 与 `BodyForceStage.java:126` 的隐含约定。

### 6. 已确认的死代码/假 API

静态检索已经确认一批无调用或仅测试访问的符号，代表性例子：

```text
PhaseSourceColumns.setAmount
DomainCapabilities.thermalFaceArea
DomainCapabilities.planThermalTruncate
DomainCapabilities.commitThermalTruncate
DomainCollision.displaceGranularForCollisionCell
GranularMechanicalStage.requireCollisionPreview
GranularReactionStage.faceVelocity
```

其余疑似死代码（如 `PressureSystem.transportVelocity(int)`、`GasChamberTopology.matchesGeometry`、`FlipFreeSurfaceGeometry` 的若干体积辅助方法）需要实施方用 IDE/编译器再逐项确认；本报告不把未确认的符号当作结论。

测试专用 API：`PressureFaceGraph.totalPortCoefficient`、`DomainCollision.surfaceTensionPressureAt`、`FlipPressurePhaseGeometry.interfaceTheta`、`GasPressureBoundary.topologyInitialized`。这些不是“少量残留”，它们会让排障者把测试夹具路径误认为生产契约。

### 7. 结构热点

没有逐函数 profile；以下只是代码结构给出的热点，需单独测量，不应直接当作结论：

- `ConservativeLiquidFlux.preparePlan` 每次调用新建 `FlipPressurePhaseGeometry`（`:115`），并复制 8×cellCount 微盒体积（`:120`）；
- `interiorLiquidMinimum`（`:325-345`）对每个满液分量做最多 6 面 × 4 端口的 `GasFaceIntersection.area` clipping；
- `PressureFaceGraph.prepareHydrostaticReference`（`:376-422`）每次 solve 做 4 次 `8*cellCount` 清空和两次全竖扫，循环顺序 `z,x,y`（`:379`）对缓存不友好；
- `CellMacStage.rebuildWallSupport`（`:176-206`）每步 3 轴 × cellCount × 液体面面积，并 `liquidCells.snapshot()`；
- `SurfaceTensionFields.smoothSurfaceTensionColor` 27 邻域，liquid-pair 活跃时每步至少两次；
- `PressureSparseMatrix.begin` 每次 solve 分配/清空 `12 * expectedRows` 的哈希 arena。

`fast` 总耗时 430.471 s，`containers` 单领域 318.363 s；这不是单步热点，但说明默认回归成本已经很高。

---

## 五、验收文档的具体错误表述

- `validation.md:414`：把 `28/29` 与单 gate 的 `production-replay` 日志合并成“29 个完整领域覆盖齐全”，并引用 `311a48b8/8dad25b9` 作为全量结果；实际 `full` 记录是 `b74f3728/7ce55640`，且 28/29。
- `validation.md:415`：用恒零的 `drift-capacity-final-soak` 证明“长期稳定”，但该日志每 5000 步 `K=0, dV=0, PCG=0`，没有非平凡演化。
- `validation.md:416`：扰动源只有 1 mm/s，初始 K 约 2e-5 J；0.17 J 的记账噪声是信号的数千倍。
- `validation.md:418`：把 `final-budget-2` 的 4 次连续超时写成“未通过”，却又在 `:428` 宣布 M10 完成；`:420` 也承认一次 idle 控制器失败。验收状态和失败记录互相矛盾。
- `validation.md:457`：明确写“没有重复运行全量 29 领域”，与 `:414` 的“29 个完整领域”冲突。
- `solver-consolidation-plan.md:10,14`：写“M10 长期误差整改复核完成”“恢复 D01”；当前全量证据不支持。
- `validation.md:410`：声称压力迭代额度保持原值；`M3MaterialTransferValidation.java:198` 已删除迭代上限。
- `validation.md:248`：声称完整位模式比较；`FluidDomainStateAssertions.java:170-177` 已改为 4 ULP 容差。

---

## 六、按严重度的行动清单

### P0：先恢复可审计的验收

1. **在同一最终 revision/artifact 上重新运行 `full` 29 领域**，保存带 `revision/sourceSHA256/artifactSHA256` 的日志；删除 28/29 + 单 gate 的拼接表。M10 状态改回“待验收”，D01 不要恢复。
- **修复 `GranularReactionStage` 的位移/反应提交顺序**：不要先 `transportedLiquid.commit()` 再以未位移的 `liquid` 回滚；用 `transport.inventory().future()` 作为 `stockPlan` 基准，气体容量、动量和审计都使用同一个端点。补一个“反应后液体超过新孔隙、邻格有容量”的夹具，断言 `liquid <= pore`、U 闭合，并与 `transport.inventory().future()` 比较。
2. **修复 liquid-pair 测试**：把 allowance 收紧到期望跳变的合理比例（例如 10%），或改用带自由表面/气相的夹具；`measuredJump=0, error=1.0` 必须失败。
3. **恢复 entity-fluid 旧包络**（`tail <= 0.025`, `final <= 0.002`），把 2,452 J 的全局 allowance 降为与 body work 相关的小比例；当前 HEAD 的 0.0014/0.0013 J 本来就能通过旧包络。
4. **修正 compressed planar 的记账**：报告有效原始容差，或把扰动提高到高于 0.17 J 的噪声；不能把 `0.025 J` 写成固定预算而实际允许 0.196 J。
5. **预算改成可复现判据**：固定 CPU 时间或多次运行的统计量；当前同一 artifact 五次运行 2 失败、3 通过，不能作为通过门。

### P1：恢复物理/数值守护

6. 给压力求解加重启后的残差/迭代上限测试；当前 `cell-inventory` 出现 `PCG=0 residual=20.2` 仍绿，M3 已无迭代上限。
7. 重新引入 CFL 或明确“供体限流是唯一限制”的适用范围；当前 `calculateSubsteps` 只返回最小子步数。
8. 把真空平衡阈值从 101 Pa 和 4 Pa 的 liquid-pair allowance 记录为明确模型容差，或收紧；不能让测试名称与数值相反。
9. 给 `dynamic player`、`compressed planar` 和 `self-review5-compressed-soak` 增加独立的总机械能闭合检查；当前 4.96 kJ/35.8 kJ 和 2.45 kJ allowance 无法证明守恒。
10. 恢复 `M3` 的 PCG 上界、极端压力多格夹具和 `RegionCodec` 独立 oracle；当前的删减是覆盖损失。
- 给 `MaterialTransferPlanner` / `GranularTransferPlanner` 的 `roundoffResidual` 增加独立量级上界，禁止把源/汇差额自动写成 roundoff；否则 `expectedInventory()` 在这些路径上恒等于实测值，审计不能被当作守恒证明。
- 修复混 support 切割单元的 `freeFallFraction[cell]`：当前按 cell 聚合 free/wet，而 `groundedSupport`/`hydrostaticReference` 按 support 计算；一个 support 落地、一个悬空时，落地 support 仍得到 `0<f<1` 的重力项，会留下持续向下加速度。需要按 support 或按面计算，并加入“同格双 support、一落地一悬空”的静水用例。

### P2：降低维护成本

11. 把 `FluidDomain` 的 48 个可变字段按数据归属拆成明确状态对象，至少把压力分类、气体来源、恢复准备分开；`PressureFaceGraph` 不再同时拥有拓扑、分类、静水、矩阵和投影。
12. 统一 `CellMacStage.transport` 与 `GranularReactionStage` 的提交模板；反应路径必须走同一 `beginDissipation`/`commitPrepared`/审计顺序，或删除重复路径。
13. 清理第四节列出的死代码/测试专用访问器，并把 `fast` 遗漏的 8 个领域在默认入口文档中列全。
14. 对 `ConservativeLiquidFlux.preparePlan`、`interiorLiquidMinimum`、`prepareHydrostaticReference`、`rebuildWallSupport` 做一次真实 profile，再决定是否重写；当前只有结构推测，没有逐函数测量。

---

## 七、审查边界

- 只读代码、运行现有 gate、写临时探针；未修改任何生产/测试源码。
- 临时探针 `build/review5-probe*` 已删除；证据日志保留在 `build/review5-fast.log`、`build/review5-targeted.log`。
- `docs/maintenance/build-outputs.md` 与其他审查者的 `docs/review/*` 未改动。
- 没有重跑 5,000 步溃坝、完整 600 步切格、全部 28 个历史领域；本报告只把实际运行的 `fast` 19 门、targeted 3 门和现有 `full`/soak/budget 日志作为证据。
