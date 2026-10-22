§108§ 审查基于 `a681aa9`（干净工作树）完成，未修改任何现有代码；临时探针已删除。结论先说：

**核心整合的物理骨架方向合理，质量、体积和内部能量的账本大体扎实；但“M10 综合验收完成”不成立。** 当前 HEAD 的三个 canonical gate 直接失败，长流程证据大多早于最后一轮代码改动，且部分“通过”记录来自未入库的探针和一份修改过的生产代码副本。因此这次整合**降低了 solver 的代码量，却没有达到“可稳定验收、可低成本排障”的目标**。

---

## 一、我实际跑出的结果

| Gate | 结果 | 说明 |
|---|---|---|
| `reseeding:short` | **PASS**（1.1 s） | 单元库存、旋转、双密度、宏观惯性通过 |
| `marker-transfer` | **PASS**（0.03 s） | 格面惯性/速度传递通过 |
| `cut-cell:short` | **PASS**（0.4 s） | 黏性解析误差随网格下降 |
| `free-surface:short` | **FAIL**（0.19 s） | 第一个场景 `Prepared phase geometry changed saved liquid occupation` |
| `granular:short` | **FAIL**（2.0 s） | `Invalid caloric candidate partially published` |
| `production-live-budget` | **FAIL**（两次） | 演示场景连续超 200 ms 分别 3 次、11 次；控制器 18–21 ms 通过 |

对 `free-surface:short` 跳过那个过期断言后继续执行其余短场景，`fractionalHydrostaticColumnsBalanceGravity` 仍然失败：fraction=0.6 时解析值 981 Pa，实际 2943 Pa。

`thermal` 全量、5,000 步溃坝、600 步切格、沙床生命周期、33 组操作矩阵和三个固定种子，我没有在当前树上重跑；下面的证据审计说明它们目前不能作为 HEAD 的通过证据。

> 计时受同机负载影响；两次预算运行都比 21:22 的旧日志（1 次超时、最大 228 ms）差很多。这至少说明预算结论目前**不能稳定复现**，不能作为“已通过”的正式记录。

---

## 二、仿真算法与守恒

### 2.1 结构上做得好的部分

- **单一液体权威**：`LiquidCellInventory.Stock` 保存体积、整数质量、物种、内能和指示剂；运输、相变、编辑和恢复都走这一份库存。旧的采样点权威链已删除，方向正确。
- **质量守恒**：运输、拆分、相变、颗粒排水和外部交接都采用“总量减已分配”的整数运算；`planRoutes` 对每个来源核对路由体积总和，`partitionEnergy` 对总质量与总内能做精确断言。我没有找到静默丢失或重复计入质量的路径。
- **体积守恒**：`SignedDyadic` 保存精确体积；`PhaseQuantitySettlement` 把未量化的尾部留在原来源，接收容量按真实孔容限制。预算日志中 20 步总体积稳定在 128.000000000；溃坝场景的 2591.999960065 是 2⁻¹⁵ 传输字量化的尾差，不是泄漏。
- **动量**：格面动量按本次实际整数质量通量推进；压力算子用同一套面系数构造梯度和散度（`PressureFaceGraph` 的 `pressureVelocity` 与 `addDivergence` 共用系数），颗粒耦合也以转置形式进入。双控制体积的动量输运保持成对冲量。
- **混合热**：我按双控制体积推导过 `MacMomentumAdvection` 的热账。对于“保留质量 + 全部入流”的混合组，`0.5·m·(v−v_new)²` 的总和正好等于动能损失；多入流时也没有少记。这里没有发现能量凭空产生或单边漏记。
- **气体压缩功**：`GasThermalModel.compressAdiabatically` 按实际终点体积求等熵温度，再积分内能变化；功的符号和终点结算是对的。压力耦合使用步初切线储容，属于一阶时间分裂近似，和文档声明一致。
- **内部能量账本**：运输后的 U 用精确 dyadic 求和，写回 double 的差值记入 `energyRoundoff`；热审计用 `expectedInventory()` 核对。短流程和 `reseeding` 场景都通过了这一层。

### 2.2 仍然存在的守恒/审计缺口

1. **生产步没有总量断言。** `FluidDomainStepCoordinator` 把 `massBefore/massAfter` 放进 `StepStats`，但生产路径没有比较它们；`relativeMassError()` 也没有被生产代码使用。质量守恒是结构性结论，不是每步运行期认证。加一个精确的 `totalMassUnits` 比较成本很低，建议补上。
2. **总能量闭合只在审计模式下检查。** `MechanicalAuditAccumulator.finish` 只闭合“末动能 − 初动能 − 各阶段做功”，`thermalInventoryMechanicalWork` 只是报告值，没有和动能损失做交叉核对；生产请求默认 `mechanicalAuditEnabled=false`。液体的压力投影功没有进入 U 账本，气体功有单独账本。因此“内能守恒”是强的，“总机械能+内能守恒”在正常游戏路径上没有强制认证。
3. **部分格的压力定义不统一。** `PressureFaceGraph.hydrostaticOffset` 返回的是“液体体积平均/质心压力” `0.5·ρ·g·h·f`，而 `fractionalHydrostaticColumnsBalanceGravity` 断言的是格心压力 `ρ·g·h·(f−0.5)`。f=0.6 时两者相差 3 倍。代码的取法作为有限体积格均值是说得通的，但测试、查询接口和沸腾判据必须使用同一个定义；现在这个契约没有定下来。
4. **固体/切格的面开口与孔容是两套近似。** 压力散度用 `open/h` 的几何开口，颗粒面再用 `fixedFraction`/`activeVolume` 修正；运输容量用 `open volume − solid SVQ`。质量不会因此丢失（结算仍然有界），但床面和切角处的压力驱动与真实孔容可能不一致，容易产生残余速度或错误压力。这是模型近似，需要明确适用范围。
5. **气腔来源的继任仍有硬失败。** 旧日志中的 `named=5` 就在 `GasTopologyBlockedException: Allocated finite gas has no successor` 处失败。代码通过 `gasTags` 归一化到最终干空间，质量本身是守恒的，但来源权重可能在某个节点归零，导致没有后继。这类失败目前直接抛异常，没有降级路径。

### 2.3 稳定性与时间步

`FluidDomainStepCoordinator.calculateSubsteps` 现在只返回 `max(1, minimumMechanicalSubsteps)`；`maxCourantNumber` 仍然被配置、校验和持久化，但生产路径没有读取它，`courantRate()` 只被测试使用。预算日志中出现过 `maxSpeed=11.8 m/s`，而 h=0.5 m、dt=0.05 s，CFL≈1.18。运输被供体库存限制在“每步最多一格”，所以不会因为超大通量直接发散；代价是**速度场和实际质量推进在高流速下脱耦**，前锋速度被网格限制，压力场可能过驱动。文档 `fluid-solver.md:30` 仍写着“Courant 超限时后端细化完整力学步”，与实现不符。

短期看这是“有界但降精度”；长期看它让高流速场景的表现依赖子步数，属于需要明确取舍的点：要么恢复 Courant 细化，要么删除 `maxCourantNumber` 并明确“供体限流”是唯一限制。

---

## 三、流体特性是否还能正确仿真

- **静水与压力梯度**：满液格通过；部分液格的压力定义如上存在 3 倍偏差，这会直接影响自由面附近的侧向压力梯度和沸腾压力。
- **热对流**：机制仍在（密度修正 + 热浮力），但当前 HEAD 没有新鲜的 `thermal` 通过证据；旧日志早于最后一轮库存/热学改动。
- **漩涡**：一阶迎风动量输运的数值黏性很大。`persistentMacMomentumRetainsSmoothVortex` 在 40 步内允许 2% 动能损失，我这个场景通过了；但在 1 m/s 量级，数值黏性约 0.2 m²/s，2 m 涡旋的衰减时间约十几秒。玩法尺度可以接受，跨尺度不保证。
- **气体膨胀**：等熵终点功正确；压力耦合是一阶线性化储容，压缩/膨胀的冲量与最终体积功在输运被裁剪时会有一阶差。
- **薄层/液滴**：2⁻¹⁵ 的传输字（h=0.5 时约 3.8 mL）以下的有效库存会永久留在原格；作为“亚解析残量”是可接受的，但它意味着很小的液滴不能靠输运移动，和“标准 PLIC 自由面”不是同一精度层级。文档应把这个阈值写成明确的传输分辨率。

---

## 四、累赘代码、架构与测试

### 4.1 确实减少了代码量

按 `3d69b22` 到 HEAD 统计：

- 生产代码约 **80,515 → 71,956 行（−10.6%）**；
- solver **27,512 → 19,040 行（−31%）**；
- 测试 **40,598 → 34,248 行（−15.6%）**。

solver 的缩减是真实的，旧的采样点/仿射压力/高阶矩路径确实被删掉了。

### 4.2 但清理没有做完

至少还有约 **670 行**属于死代码或重复实现：

- `ExactQuantitySettlement`（191 行）与 `PhaseQuantitySettlement.ResidualRepair` 是两套最大流结算，前者只作为罕见的尾部回退；
- `LiquidCellInventory.planTransport` / `FaceFlux` / `Route` / `splitVolume` / `validateFace` 这一条约 92 行的链路只被测试调用，生产走 `planRoutes`；
- `ConservativeLiquidFlux` 的 `capacityCorrectionVolume`、`maximumCapacityResidual`、`allocationArcCount`、`liquidSourceEntryCount`、`gasPredictionAdjustmentVolume` 等诊断量**永远是 0**：`correction`/`maxResidual` 只被清零，从未赋值；
- `PressureProjectionWorkspace.rhs`、`gasRhs` 也是永远为零的字段，`PressureSystem.buildRhs` 仍在读它们；
- `ExactVolumeAllocation.allocateCapped`（37 行）无调用者；`PhaseChangeStageSupport` 的若干 `toCoherent/toMixture/fromCoherent/fromMixture` 无调用者；
- 四个已无引用的类：`DivergenceConformingVelocityField`、`CellSpeciesInventory`、`ExactCellVolumeSums`、`MarkerNeighborhoodIndex`，合计约 245 行。

这些不是“少量残留”，而是会误导排障的假信号：一个排查容量问题的开发者看到 `maximumCapacityResidual()==0`，会以为没有超容。

### 4.3 架构集中度

`FluidDomain` 被拆成 `FluidDomainState/Storage/Contracts/Capabilities/MechanicalFields/InventoryTransactions/CollisionState/GasAndGranular` 等多个包内类，共用同一批数组。文件数减少了，但**单个物理步仍然要跨 7–8 个文件阅读**；`GranularReactionStage` 已到 942 行（架构警告线 600，失败线 1000），`ThermalDomainState` 872 行，`AirNetwork` 851 行，`FluidDomainCapabilities` 745 行，`LiquidCellInventory` 708 行。这缓解了“文件数量”，没有缓解“单点改动牵涉面”。

### 4.4 测试

- 94 个测试文件、约 34k 行；`ThermalGasValidationScenarios` 约 200 KB、`M8GranularValidation` 约 165 KB、`GranularCarrierValidation` 约 99 KB，阅读成本很高。
- `MacMomentumAdvectionValidation` 和 `LiquidHandoffContinuityValidation` 没有任何 Gradle 任务或 selector 调用，属于孤儿测试；而动量平流恰是新主干的核心。
- `CpuValidationSuite` 的 `reseeding` 短模式只跑 `FlipFluidReseedingValidation`，跳过 `CarrierMutationValidation`；`validation.md` 把它们并列展示，容易误以为短模式覆盖了载体变更。
- `LiquidVolumeTransportValidation` 的 `mass/quantization/representation/geometry-remap/handoff/...` 全部落到同一个 case 分支，文档却把它们写成不同的聚焦入口。
- `d7b329d` 把 `ThermalContactValidation` 的 ULP 精确断言、溢出/次正规/多段热容测试删掉，改成 1e-6/1e-4 容差；新 `ThermalContactExchange` 是 8 次固定迭代 + 1e-4 K 残差，极端尺度不再有测试守护。删除部分实现绑定断言是合理的，但极端数值范围属于真实的失败模式，建议保留至少一组。
- `free-surface` 和 `granular` 的失败说明：测试集里已经存在**未更新的旧断言**，而验收文档仍按旧结果宣称通过。

---

## 五、M10 证据链的问题（最严重）

这是本次审查最需要强调的部分。

1. **长流程日志早于最后一轮代码改动。**
   - `final-dam-bounded-momentum.log` 是 19:58；`CellMomentumTransport`/`ConservativeLiquidFlux`/`CellLiquidVolumeTransport` 的最后一轮修改在 21:16–21:17；`a681aa9` 在 21:24。
   - `partialdisk49` 20:04、33 组矩阵 20:08–20:24、种子 54/103 20:21，都早于 `d7b329d`（21:07）和 `a681aa9`（21:24）。
   - 只有 21:22 的两项预算测量在最后一轮改动之后。

2. **“33 组操作组合全部完成”与引用的日志直接矛盾。**
   - `build/m10-joint-core/shared-capacity-named-matrix.log`：`named=3` 在 generation 0 就以 `Thermal LTE energy is outside the positive-temperature domain` 失败。
   - `build/m10-joint-core/mixed-pressure-named-matrix.log`：`named=5` 在 generation 220 以 `GasTopologyBlockedException: Allocated finite gas has no successor: node=7` 失败。
   - `named5-retained-source-suffix.log` 是从保存输入续跑的 220→240，不是该场景的完整通过。
   - `build/m10-joint-core/container-operation-matrix.log` 第一行就是 `AssertionError: granular pressure replay seed=4, action=25 ... Liquid inventory exceeds its open component capacity`。

3. **三个固定种子的说法不完整。**
   - seed=54、103 有 60 动作的完整日志（但时间早于最终代码）。
   - seed=4 的 `final-seed-continuations.log` 在 action 51 以 `Bounded liquid volume allocation has insufficient recipient capacity` 失败；`final-seed4-suffix.log` 只覆盖 action 52→60。文档写成“三个固定种子的原六十动作全部完成”，不准确。

4. **部分“通过”来自未入库的探针和修改过的生产代码。**
   - `build/m10-joint-core/src/mmm/simulation/` 下有 `NamedMatrixProbe`、`MatrixResumeProbe`、`ContinuousPhase400Probe` 等未跟踪文件；它们通过反射调用测试内部的 lambda。
   - `build/m10-precipitate/src/mmm/fluid/GranularReactionStage.java` 与 HEAD 的生产文件相差 141 插入/85 删除，mtime 19:58，缺少 `d7b329d` 才加入的 `energyLimitedExtent`；而 20:20 的沉淀 PASS 日志是这份副本跑出来的。
   - 这意味着“最终构建通过”的证据并非来自仓库中的代码，无法复现。

5. **文档与代码不一致。**
   - `plan` 第 10、51–53、88 行宣称 M00–M10 完成、5,000 步溃坝/热平流/分层/对流/33 组/固定种子全部完成。
   - `validation.md` 第 392–394、396–397、417–424 行把这些旧日志当作当前证据；第 428 行的“515 份生产源码”也与当前统计（489 份 `src/main`）不符。
   - `fluid-solver.md:30` 的 Courant 细化在代码中已不存在。

---

## 六、误差是否发散

在我跑过的短场景里，没有看到发散：预算场景 20 步体积恒定，速度峰值 11.8 m/s 后回落；`reseeding` 的旋转动能 4 步衰减约 0.5%；`cut-cell` 的黏性误差随网格下降。供体限流、正权迎风和压力投影的隐式求解共同保证了有界性。

但“长时间不发散”目前没有 HEAD 上的新鲜证据：5,000 步溃坝、600 步切格、沙床生命周期的日志都在最后一轮改动之前。当前更现实的风险是**硬失败**而不是数值爆炸：PCG 不收敛会抛 `PressureSolveBlockedException`，气腔没有后继会抛 `GasTopologyBlockedException`，空行预条件会抛异常；这些都会让一个正常操作直接停住，表现上很像“无止境排障”。

---

## 七、对“降低维护和排障成本”的判断

**有真实改善：**

- solver 减少 31%，旧的采样点、仿射压力、高阶矩路径被整块删除；
- 液体数据只剩一份权威库存，运输链从“采样点→单元→新采样点”缩短为“库存→通量→结算→动量”；
- 质量、体积、物种和 U 的精确账本集中在少数模块；
- 设计书的 135 个文件链接全部有效，代码索引本身是准确的。

**但目标没有达成：**

- “M10 完成”是一个错误信号；下一个接手者会先花时间怀疑自己的环境，而不是直接看到红灯。
- 证据链不可复现（旧日志、未入库探针、修改过的生产副本），这正是“反复排障”的温床。
- 诊断接口返回假零值，测试集本身有未更新断言和孤儿测试，排障时需要同时怀疑代码、测试和日志。
- 单步仍然跨 7–8 个 `FluidDomain*` 文件和多个大阶段类；文件数下降，阅读面没有同比下降。
- 94 个测试文件、34k 行测试代码，加上 110 KB 的三份主文档，维护成本仍然很高。

---

## 八、建议的优先级（只给建议，不改代码）

1. **撤回“M10 完成”状态**，在当前树上重新建立验收基线；至少让 `free-surface:short`、`granular:short`、`production-live-budget` 变绿，并记录 commit hash、命令和原始输出。
2. **清理证据链**：把 `build/` 里的探针移入测试或工具目录；删除或归档旧日志；禁止用修改过的生产副本生成 PASS。
3. **处理两个 free-surface 问题**：
   - 几何断言：`d7b329d` 已把持久化改成“保存重建输入、由输入派生 PLIC”，测试还在比较 PLIC 输出；要么更新断言，要么明确持久化契约。
   - 部分格压力：统一“格平均压力”和“格心压力”的定义，并同步查询、沸腾判据和测试。
4. **处理 `granular` 的拒绝语义**：`energyLimitedExtent` 让无效 caloric 候选静默跳过，但测试和文档要求“原子拒绝”；选择一种语义并统一。
5. **决定 Courant 策略**：恢复 `calculateSubsteps` 的 Courant 细化，或删除 `maxCourantNumber` 并修正文档；同时加一个高流速场景，检查压力场和实际质量推进不脱耦。
6. **补两步总量断言**：每步精确比较 `totalMassUnits` 与 `totalVolumeExact`；在审计模式下把 `thermalInventoryMechanicalWork` 与机械能损失做交叉核对。
7. **删除死代码与假诊断**：`ExactQuantitySettlement`（或合并）、`planTransport` 测试专用链、四个无引用类、`correction/maxResidual` 等零值诊断、`PressureProjectionWorkspace.rhs/gasRhs`。这些清理的收益不是行数，而是减少误导。
8. **修复或明确 33 组/种子/容器的失败**：`named=3`、`named=5`、seed4 的 action 51、container matrix 的 seed4 action 25，都是当前可复现的阻塞点。

---

## 九、审查边界

- 审查对象：`a681aa9`，工作树中 Java 源码无未提交改动；`docs/maintenance/build-outputs.md` 的修改和 `docs/review/` 是另一位审查者的工作，我没有触碰。
- 我写的临时探针（用于确认部分格压力差值和 `granular` 拒绝行为）已删除；`build/review-*.log` 也已清理。
- 我没有运行 `thermal` 全量、5,000 步溃坝和 33 组矩阵；这些在当前树上应视为**未验证**，而不是“按旧证据通过”。

**一句话总结：** 这次整合把液体主干从“多套采样点表示”收敛成了一套可解释的单元库存 + MAC 动量，物理方向是对的；但验收系统没有跟上最后一轮改动，三个 gate 红灯、证据不可复现、诊断和测试仍有大量假信号。现在适合继续修整合，不适合宣布 M10 完成或恢复 D01。