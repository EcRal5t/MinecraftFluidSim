§205§ 重新审阅结论：**实现方说“改完了”，但当前 HEAD 的验收状态不支持“M10 完成”。**

当前代码基线是 `07dc0ab`（代码提交 `e7d1155`）。我按最初的要求重新跑了默认 `fast` 覆盖到的核心 gate，并抽查了 `soak`、预算和长流程证据：

- **通过**：`free-surface:short`、`containers`（386 s，19 个场景）、`liquid-joining`、`marker-transfer`、`marker-pressure`、`cut-cell:short`、`shape`
- **失败**：`cell-inventory`、`granular`、`gas-liquid`、`chemistry`、`thermal`、`air-cavity`、`production-live-budget`

默认 `fast` 本身就包含 `cell-inventory`、`granular`、`chemistry`、`thermal`、`air-cavity` 这五个失败领域，所以**默认验收门现在是红的**。这不是“个别长场景待补”，是主干正常流程没有跑通。

---

## 一、当前 HEAD 的实际 gate 结果

| Gate | 结果 | 失败位置 |
|---|---|---|
| `cell-inventory` | **FAIL**（2.1 s） | `CarrierMutationValidation.java:140`：颗粒初始化后液体位移/气腔/热账综合不变量失败 |
| `granular` | **FAIL**（2.4 s） | `GranularCarrierValidation.java:1258`：输运后载体属性缓存仍指向旧格 |
| `gas-liquid` | **FAIL**（0.09 s） | `M5GasLiquidCouplingValidation.java:55`：有限气体储容与毛细压差没有共享同一平衡界面 |
| `chemistry` | **FAIL**（1.8 s） | `M7ChemicalTransportValidation.java:548`：抽取后恢复，相占据与液体库存不一致 |
| `thermal` | **FAIL**（1.0 s） | `ThermalGasValidationScenarios.java:856`：`Gas restore requires an uninitialized topology` |
| `air-cavity` | **FAIL**（0.23 s） | `AirCavityScannerValidation.java:82`：完整微面制造了完整格面，或切分了格内凸气体体积 |
| `production-live-budget` | **FAIL**（本次运行） | 溃坝最大 1651.98 ms，连续超 200 ms 6 步 |
| `free-surface:short` | PASS（4.7 s） | — |
| `containers` | PASS（386 s） | 12+6+19 个场景 |
| `liquid-joining` | PASS（20 s） | — |
| `marker-transfer` / `marker-pressure` / `cut-cell:short` / `shape` | PASS | — |

预算失败需要加一句环境说明：本次溃坝步的**墙钟** 1651 ms，但主线程 CPU 只有 187–312 ms，说明这次运行受到同机调度/抢占影响；同时实现方自己的 `review-live-budget-idle.log` 也不能证明来自 HEAD（见第五节）。所以预算结论应记为“**当前无法稳定复现，且证据不来自最终源码**”，不能记为“通过”。

---

## 二、守恒与事务

### 2.1 仍然成立的部分

- 整数质量、精确体积、物种和内能的账本结构还在。运输按来源精确闭合，`LiquidCellInventory.prepareSourcePartition` 继续核对路由体积总和，`partitionEnergy` 继续核对总质量和来源 U。
- 旧的 2⁻¹⁵ 传输字和 `ExactQuantitySettlement` 已被删除，新的 `PhaseQuantitySettlement` 用精确 `SignedDyadic` 结算，最大的偏好份额吸收精确余量。未解析的小流量不再被固定量化死区吞掉。
- 压力算子仍共用一套面系数，梯度与散度互为转置；固体/颗粒项也以转置进入。`marker-pressure`、`marker-transfer` 通过。
- 墙面停止的动量与热量在 `MacMomentumAdvectionValidation` 类测试中仍能闭合；颗粒接触的成对冲量也通过。
- 容器路径本次完整通过，质量、体积、WAL 和恢复在 `containers` 覆盖范围内是可靠的。

### 2.2 仍然存在的账本缺口

**（1）颗粒插入会丢掉热审计的外部来源能量。这是本次新出现的明确问题。**

`GranularLiquidDisplacement.publish` 的执行顺序是：

1. 先捕获 `audit`；
2. 提交液体库存和动量；
3. 执行 `solidPublication.run()`，其中 `commitExternalActiveInsertions` 会把插入颗粒的参考能记入 `externalSource`；
4. 最后用第 1 步捕获的旧 `audit` 覆盖回去。

代码位置：`GranularLiquidDisplacement.java:40-57`、`GranularTransferPlanner.java:89-94` 与 `:245-258`、`GranularEnergyState.java:210-227`。

结果是颗粒的 U 已经进入 `exactInternalEnergy()`，但审计账本没有对应的 `externalSource`。`cell-inventory` 正是卡在包含 `exactInternalEnergy().equals(thermalAudit().expectedInventory())` 的复合断言上。质量本身没有丢，**热账丢了**；之后冷恢复会在 `FluidThermalBinding` 的审计闭合检查处失败。

**（2）外部抽取后相占据可能保持旧值，导致恢复失败。**

`MaterialTransferPlanner.extractMixture` 只提交液体库存/气体/热账，没有在无气腔或未刷新气腔的路径上重建 `domain.phaseOccupancy`。`DomainPhaseInventory.captureAuthoritativeState` 会把这个旧相占据一起存下来；恢复时 `PhaseOccupancyTransport.requireGeometry` 发现“相占据总量与液体库存不一致”，直接拒绝。`chemistry` gate 的恢复失败就是这条链。

`phaseOccupancy` 只在 `PhaseOccupancyTransport.ensure`、`GasTopologyCoordinator.commit`、状态恢复等少数位置更新；普通外部编辑没有统一失效规则。

**（3）生产步仍没有总量断言。**

`DomainStep.step` 记录了 `massBefore/massAfter`，但没有比较；`StepStats.relativeMassError()` 在生产路径没有消费者。`CellMacStage.transport`、`GranularReactionStage`、`GranularLiquidDisplacement` 都调用了 `expectedInventory()`，但没有把返回值与 `exactInternalEnergy()` 比较。上述颗粒插入的审计错误能穿过多步而无人发现，原因就在这里。

**（4）删除精确回退后，容量失败的语义变硬。**

`ExactQuantitySettlement` 删除后，`PhaseQuantitySettlement` 的 `CapacityUnavailable` 直接向上抛，正常步会直接失败/暂停，不再有第二次精确分配尝试。质量不会因此丢失，但长流程的“可完成性”风险上升；需要明确这是产品可接受的失败策略。

**（5）测试/命令路径的 `setMarkerChemical` 允许负质量余量。**

`DomainCollision.setMarkerChemical` 没有检查 `speciesMass <= stock.massUnits()`，任意大的物种量会生成负的 `massResidual`。库存质量本身不变，但化学审计和后续反应资格会被这个不一致污染。它主要影响开发夹具，属于需要收口的接口约束。

---

## 三、流体特性与物理正确性

### 3.1 静水与压力梯度

- 满液格的静水压力、压力梯度、转置关系都通过；`free-surface:short` 现在也通过。
- 部分液格现在统一采用“液体体积平均压力”：测试期望从 `f−0.5` 改为 `0.5f`，代码 `PressureFaceGraph.hydrostaticOffset` 返回 `0.5ρgh·f`。这是一次**有意的模型口径变更**，本身说得通。
- 但这个口径对**切格**不成立：`liquidFraction = liquid/openCellVolume`，而自由面气体侧的平衡需要的是 `liquid/(openArea·h)`。对于底部半格开放、内部液面为 f 的切格，代码给出的湿深约是真实值的 2 倍；单个八分之一开放格可差 8 倍。后果是自由面上出现量级接近 1 g 的虚假向上加速度，水可能从台阶/半砖表面“浮起”。当前静水测试只覆盖未切格，没有覆盖这个几何。
- 侧向自由面坡度力还有 `1/(1+f)` 量级的偏小，属于同一压力重建误差的一部分。

### 3.2 毛细与气液界面

`gas-liquid` 在新树上直接失败。代码里仍存在两套权重：

- 压力投影的气体侧使用 `-surfaceTensionPressure·liquidFraction`；
- 查询/沸腾判据 `liquidPressureAt` 加的是完整 `+surfaceTensionPressure`。

满液格 f=1 时两套符号/权重就足以让 `p_liquid − p_gas − jump` 不闭合；部分液格更严重。这个不一致在上一轮审查中已指出，本次仍未解决，而且现在被 gate 直接抓到。

### 3.3 漩涡、旋转与数值耗散

- 动量输运仍是一阶供体迎风；常量平移保持，旋转会有明显的数值黏性。
- 当前测试的门槛是“40 步损失不超过 2% 动能”和“4 步角动量损失不超过 20%”，这是稳定性门槛，不是旋转质量门槛。玩法尺度的 1–2 m 涡旋仍会在数秒内明显衰减。
- 这可以接受为“工业玩法近似”，但文档需要把适用范围写清楚，不能把它宣传成通用的低耗散旋转模型。

### 3.4 气体膨胀与气腔

- 等熵压缩功仍按实际终点体积结算，`GasThermalModel.compressAdiabatically` 的方向和符号没有问题。
- 新的 `GasComponentCapacity` 把干片、拓扑和热力学容量统一到 `pore − Stock`，方向正确。
- 但 `air-cavity` 的微面几何不变量失败，说明新的“完整微面”路径可能把两个微盒错误合并成一个完整格面，或者把格内凸气体体积切开；`thermal` 的气体恢复又被新的 `restoreOwners` 守卫挡住。气体拓扑这一块当前没有可信的通过证据。

### 3.5 热学

`thermal` 在 1 秒内就失败，失败点是恢复前置条件，不是热学计算本身；因此本轮**没有任何新鲜的热平流、导热、相变或自然对流证据**。旧日志全部早于 `e7d1155`，不能用于 HEAD。

已有的热学改动中，复杂分段热容的接触求解仍然采用“括号内返回下界”的保守策略，能量成对交接没有问题，但平衡精度没有强测试；极端温压测试也被删掉了。

### 3.6 时间步与稳定性

- `maxCourantNumber` 已从 `FluidSolverConfig` 删除，`calculateSubsteps` 只返回显式最小子步数；液体没有速度/CFL 限制。
- 运输被供体库存限制在“每子步最多一格”，h=0.5、dt=0.05 时对应约 10 m/s 的前锋速度上限；预算日志中控制器出现过 `maxSpeed=12.2 m/s`，已经超过这个上限。结果是质量推进和压力/速度场在高流速下脱耦：压力场可以给出更高的速度，但实际搬运被截断。
- 这种方案稳定、不会因为大通量直接爆炸，但它让高流速前锋速度依赖网格，属于需要明确声明的模型限制。计划/设计书里仍写着 Courant 细化，和实现不一致。

---

## 四、代码与测试的冗余

### 4.1 代码量确实下降了

按当前树统计约 **501 个生产文件、76,771 行**；`a681aa9` 约 78,296 行，`3d69b22` 约 86,681 行。solver 从 20,457 行降到 18,906 行，比整合前少约 10,000 行。旧的 `ExactQuantitySettlement`、`ExactVolumeAllocation`、`CellSpeciesInventory`、`DivergenceConformingVelocityField`、`ExactCellVolumeSums` 已删除，这部分清理是真实的。

### 4.2 集中度没有下降

- `FluidDomain` 现在是 **819 行**，吸收了原来的 `FluidDomainContracts`（181 行）和 `FluidDomainStorage`（162 行），包含 117 个字段、175 个一行转发方法和 38 个结果/异常记录。
- `FluidDomain*` + `Domain*` 家族共 12 个文件、4,214 行；比 `a681aa9` 的 4,550 行少 336 行，但“单点改动跨多个文件”的阅读面没有实质缩小。
- 最大的几个生产文件仍在 900–990 行：`GranularReactionStage` 987、`ThermalDomainState` 986、`AirNetwork` 952、`VulkanFlipPipeline` 945。`GranularReactionStage` 距离 1000 行硬上限只剩 13 行。

### 4.3 仍有一些死代码/假诊断

- `ConservativeLiquidFlux` 还有 8 个无调用者的诊断方法（`componentCount`、`gasSourceEntryCount`、`materialRouteCount`、`faceFluxCorrectionVolume`、`maximumFaceFluxCorrection`、`positive`、`donor`、`receiver`）。
- `PressureProjectionWorkspace` 仍分配并清零 `pressureAirNodeId`、`divergence`，但没有任何读取。
- `PressureFaceGraph.ensurePortCoefficients`、`pressureDifference` 无调用者。
- `LiquidCellInventory.Plan.volumeChange/massChange` 无调用者。
- 这些量不大，但和“删除常零统计/无效压力缓冲”的文档声明冲突，也会继续误导排障。

### 4.4 测试覆盖被明显削弱

本轮 `e7d1155` 重写了大量测试，删除的断言多于新增（约 183 行断言删除、129 行新增）。具体例子：

| 测试 | 旧要求 | 新要求 |
|---|---|---|
| `M6ContainerValidation` | 10,000 次循环、漂移 ≤2,000 quanta | 100 次循环、漂移 ≤200 quanta；`testRoundTrip` 整段删除 |
| `AirCavityScannerValidation` | 精确 64 片/144 端口/96 外部端口；精确复用与拓扑拒绝 | 只要求端口非空；精确复用和拒绝测试删除 |
| `CarrierMutationValidation` | 有限 U/溢出比热、舍入到负温、超大账本拒绝 | 改为正常插入守恒 + 一个低于绝对零的拒绝；极端拒绝路径删除 |
| `M7ChemicalTransportValidation` | 盐水密度 ±1e-9、fresh ±1e-9；混合物预算和 handoff 拒绝 | 盐水 1% 级、fresh 1e-3；预算/handoff 拒绝删除 |
| `FlipFluidFreeSurfaceValidation` | 层体积误差 <1e-7；质心漂移 <5e-4；最大速度 <1e-4 | 层误差 ≤0.101；质心漂移 ≤0.00632；允许 RMS 约 0.02–0.05 m/s（按解算体积能量预算） |
| `GranularCarrierValidation` | 精确接触/操作次数、ULP 级位置误差 | 容差改为 `positionTolerance`，操作次数改为范围 |

这些修改有些有模型理由（例如 2⁻¹⁵ 传输字删除后，旧的几何精确断言不再适用），但**删除极端/拒绝测试、放宽静水运动上限**会把真实回归掩盖掉。当前几个 gate 仍然红灯，说明这轮“放宽到通过”也没有成功。

另外，`cell-inventory` gate 的第四个入口是 `LiquidMacroscopicMotionValidation.main(new String[]{"joint-moments"})`，只跑了该类的一个子集；`validation.md` 的索引也只列了三个类，没有列 `LiquidMacroscopicMotionValidation`。`full` 会运行全部 gate，但 `fast` 现在包含五个失败领域。

---

## 五、验收证据链

这是本次审阅最严重的问题之一。

1. **`review-functional-final.log` 是 00:22 的旧构建，且本身 4/5 失败。**
   - 该日志的 `cell-inventory` 以 `Thermal LTE energy is outside the positive-temperature domain` 失败；
   - `containers` 以 `Cannot set temperature in an empty cell` 失败；
   - `granular` 以球体穿透失败；
   - `chemistry` 以“Global capacity rejection: gas presence changed”失败；
   - 只有 `free-surface` 通过。
   - 文档只引用了其中的 `free-surface` 通过项，并写明“旧夹具已迁移，整组测试留待相关改动需要时执行”。这意味着最终声明完成时，`cell-inventory` 和 `containers` 的完整组合并没有在新的代码上跑过。

2. **最终预算日志不能证明来自 HEAD。**
   - `build/review-build-final.log` 显示 `:solver:compileJava UP-TO-DATE`、`:fabric:compileJava UP-TO-DATE`，只执行了 `compileTestJava`。
   - `GasComponentCapacity.class` 的 mtime 是 01:20:27，而 `GasComponentCapacity.java` 是 01:30:32；该文件在 `e7d1155` 中被修改（47 插入/51 删除）。
   - `review-live-budget-idle.log` 在 01:31:43 生成，晚于该源码修改，但生产 class 没有重编译。
   - 所以“最终源码基线 `e7d1155`”这句话没有构建证据支撑。

3. **复用的长流程日志全部早于 `e7d1155`，且部分来自未入库探针。**
   - `build/m10-precipitate/src/.../GranularReactionStage.java` 仍在，和 HEAD 相差 158 插入/104 删除；20:20 的沉淀 PASS 是这份副本跑出来的。
   - `build/m10-joint-core/src/` 下有 `NamedMatrixProbe`、`MatrixResumeProbe`、`ContinuousPhase400Probe` 等未跟踪文件，通过反射调用测试内部 lambda。
   - `container-operation-matrix.log` 本身就是失败日志（seed=4, action=25，`Liquid inventory exceeds its open component capacity`）。
   - `shared-capacity-named-matrix.log` 的 `named=3` 在 generation 0 失败；`mixed-pressure-named-matrix.log` 的 `named=5` 在 generation 220 失败；`final-seed4-suffix.log` 只覆盖 action 52–60。
   - `partialdisk49-same-geometry.log` 是 49 个动作，文档却写成“原二百动作”；HEAD 的 demo fixture 是 2,592 个初始单元，文档仍写 2,880 条初始库存。

4. **文档状态与实现矛盾。**
   - `solver-consolidation-plan.md` 现在写“M10 审阅整改完成”“M00–M10 完成”。
   - `validation.md` 的新章节写“最终源码按原输入……通过完整步墙钟预算”。
   - 实际 HEAD 的 `fast` 有五个领域失败，`gas-liquid`、`air-cavity`、预算也未过。这会让下一个接手者先相信绿灯，再花时间发现红灯。

---

## 六、误差是否发散

我跑过的短流程没有出现数值爆炸：`free-surface:short` 200 步通过，`containers` 的长操作序列通过，预算控制器 20 步体积稳定在 128.000000000。失败更多是**硬失败**：相占据恢复拒绝、气体拓扑恢复拒绝、气液界面不闭合、颗粒热账不闭合、载体缓存过期。

但“长时间不发散”仍没有 HEAD 证据：

- 5,000 步溃坝、600 步切格、沙床生命周期、热对流/分层都来自旧代码或旧探针；
- 没有 CFL/速度限制，高流速下质量推进被供体截断，压力场可以继续给出超网格速度；
- 切格自由面的湿深近似在水平切割格上可能产生约 1 g 的虚假升力，当前静水测试不覆盖；
- PCG 不收敛、空气体节点、气腔拓扑异常都直接抛异常并暂停区域。

因此当前状态是“短流程有界，长流程未认证，主要风险是硬失败和局部物理偏差”。

---

## 七、是否达到“降低维护和排障成本”

**部分达到，整体没有达到。**

正面的部分：

- solver 代码量显著下降，旧表示和旧搜索路径确实删除了；
- 液体库存、质量、物种、U 的账本集中，短流程的守恒断言仍然强；
- 设计书的 143 个 Java 链接全部有效，代码索引质量不错；
- `containers` 和 `liquid-joining` 的完整通过说明容器/持久层没有被这次重构彻底破坏。

负面的部分：

- 默认 `fast` 红了五个领域，`M10` 却标记完成；
- 验收证据来自旧构建、未入库探针和修改过的生产副本，无法复现；
- 关键失败是“恢复/审计/缓存/界面平衡”这类最容易在正常游戏中偶发的问题，正是最消耗排障时间的一类；
- 测试被放宽或删除后仍然红，说明当前不是“测试过严”，而是主干还没有收敛；
- `FluidDomain` 819 行、六个 handler 的职责链仍需跨多文件阅读，架构集中度没有明显改善。

结论：**这次整合在代码体量上是前进的，在验收和可维护性上是后退的。** 当前不适合宣布 M10 完成，也不适合恢复 D01。

---

## 八、按严重度的阻塞清单

**P0：必须先修，否则不能验收**

1. `GranularLiquidDisplacement.publish` 的审计捕获/覆盖顺序：颗粒插入的 `externalSource` 被旧账本覆盖，`cell-inventory` 失败；修复后增加“插入 → 捕获 → 恢复”的热账断言。
2. 外部抽取/编辑后的 `phaseOccupancy` 失效与重建：`chemistry` 的恢复失败；所有 `MaterialTransferPlanner` 提交路径都要统一刷新或置空相占据。
3. 气液界面的毛细压差符号/权重：`PressureFaceGraph` 的 `-σ·f` 与 `PressureSystem.liquidPressureAt` 的 `+σ` 必须统一；`gas-liquid` 失败。
4. 气体恢复与微面几何：`thermal` 的 `Gas restore requires an uninitialized topology` 与 `air-cavity` 的完整微面不变量；先确定是测试契约还是生产行为，再修。
5. `publishMomentum` 在 `gridDirty` 时使用旧的 `massX`：`granular` 的载体缓存失败；要么在入口 `ensureGrid`，要么明确调用契约并修测试。

**P1：影响物理质量或长期稳定性**

6. 切格部分液格静水偏移使用 `h·liquidFraction`，缺少 `openCellVolume/(openArea·h)` 修正；需要水平切割格的静水测试。
7. 没有 CFL/速度限制；高流速下质量与压力场脱耦。恢复 Courant 细化，或删除相关文档承诺并明确“供体限流”是唯一限制。
8. 生产步没有总质量/体积/总能量断言；`massBefore/massAfter` 只覆盖液体。
9. 删除 `ExactQuantitySettlement` 后容量失败直接抛；需要明确正常流程的失败策略，补长流程验证。
10. 颗粒碰撞提交和液体/气体提交的顺序仍可能在异常路径上留下部分状态；`DomainCollision` 的审计预检变量实际未使用。
11. 应力/压力功归因：`wallBoundaryWork()` 恒为 0，阻力、颗粒接触和表面张力功都被算进 `pressureProjectionWorkJoules`；审计残差不受影响，但阶段成本/能量报告不可信。

**P2：清洁度与可维护性**

12. 还原被删除的极端/拒绝测试，或为每个删除项提供等价的新测试；特别是 `CarrierMutation` 的热容量拒绝、`AirCavity` 的精确拓扑/复用、`M6` 的 10,000 次往返、`M7` 的混合物预算。
13. 修正 `cell-inventory` gate 只跑 `LiquidMacroscopicMotionValidation` 的 `joint-moments` 子集；补全 `validation.md` 索引。
14. 清理 `ConservativeLiquidFlux` 的 8 个死诊断、`PressureProjectionWorkspace` 的两个死数组、`PressureFaceGraph` 的两个死方法、`Plan` 的两个死访问器。
15. 统一测试证据：把 `build/` 里的探针移入仓库测试/工具目录；日志记录 commit、命令、JVM、堆和构建时间；禁止用修改过的生产副本生成 PASS。

---

## 九、审查边界

- 我只读代码、跑 gate、写临时探针；没有修改任何现有源码。
- 临时探针已删除；本次新生成的证据日志保留在 `build/rereview-functional.log`、`build/rereview-budget.log`、`build/rereview-thermal.log`、`build/rereview-short2.log`。
- `docs/maintenance/build-outputs.md` 的修改和 `docs/review/` 是另一位审查者的工作，我没有触碰。
- 本次未重跑 5,000 步溃坝、600 步切格完整版、沙床生命周期、热对流/分层和 33 组矩阵；它们当前应视为“未在 HEAD 验证”，而不是“按旧证据通过”。

一句话：**核心守恒骨架仍然值得保留，`e7d1155` 的代码缩减也是真的；但这一版没有通过自己的默认验收门，M10 的“完成”结论必须撤回，先让 `fast` 和 `soak` 在当前树上真正变绿，再谈恢复 D01。**