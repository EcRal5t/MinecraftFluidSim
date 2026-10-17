# 修改后的验证

先运行与改动直接相关的测试。需要修改测试场景时，从下表打开测试源码。涉及其他模块的数据或提交步骤，再追加对应测试。

## CPU 测试怎么运行

在仓库根目录执行，例如：

```powershell
./gradlew.bat :fabric:runCpuValidation '-PcpuValidation=region-state,containers' --offline --no-daemon
```

`cpuValidation` 是测试选择器，多个名称用逗号连接。[CpuValidationSuite](../../fabric/src/test/java/mmm/validation/CpuValidationSuite.java) 定义了全部名称与组合。[fabric/build.gradle](../../fabric/build.gradle) 定义执行任务。

| 选择方式 | 含义 |
|---|---|
| 指定名称，例如 `thermal` | 完整运行该领域；多个名称会去重 |
| 名称加 `:short`，例如 `cut-cell:short` | 单独运行该领域的短入口；没有短入口的领域运行完整入口并标明 `full` |
| 省略参数或使用 `fast` | 运行默认组合，提供短模式的场景使用短模式 |
| `milestone` | 较广的阶段验收组合，仍使用各场景的短模式 |
| `soak` | 完整运行 `gas-liquid`、`free-surface`、`reseeding` |
| `full` | 完整运行所有已登记的领域 |
| `list` | 输出当前组合与选择器名称 |

显式写出的领域名称运行完整模式，即使它也出现在 `fast` 中。`production-live-budget` 和 `controller-gas-lineage` 需要显式选择，或通过 `full` 执行。Gradle 的 `check` 任务没有隐式包含所有 CPU 场景。

同一领域同时选择短模式和完整模式时，完整模式优先。基线测量可加 `-PcpuValidationKeepGoing=true`，记录当前领域的首个失败后继续下一领域，最后仍以失败退出；默认在首个失败处停止。输出区分断言、参数配置与执行失败，并记录每个领域的耗时。超时或主动中止的进程须另行登记，已开始的场景不计作通过。

`cut-cell:short` 保留参数、黏性、微盒几何、CCD、容量及恢复对照，省略 600 步障碍物流动。长场景单独运行：

```powershell
./gradlew.bat :solver:runSolverValidation --args="--flow-only" --offline --no-daemon
```

相变待同步体积的聚焦验证使用 `:fabric:runThermalValidation --args="--phase-volume"`，复用固液待处理任务、沸腾与凝结后的真实压力投影、恢复及容量拒绝断言，同时检查逐格精确相消与快照独立性。完整热学验证仍使用 `thermal`。

气腔刷新、持久化同步、压缩功、排气及气液相变的热账交接可用 `:fabric:runThermalValidation --args="--gas-topology"` 聚焦运行，复用现有热学场景。沸腾搜索固定输入与端口复用的对照归属 `air-cavity`。

`free-surface:short` 省略长静水、5,000 步溃坝、300 步台阶流动，以及输运中的连续交接轨迹和 1 秒、4 秒多网格旋流。其他短组合仍可能包含成本较高的领域；具体省略项由对应入口输出。

<a id="cpu"></a>
## 按改动选择测试

| 改动 | 选择器 | 测试源码入口 |
|---|---|---|
| 极端温度、压力与相变连续推进 | `thermal-extremes` | [ExtremeThermalValidation](../../fabric/src/test/java/mmm/fluid/ExtremeThermalValidation.java) |
| 材料、质量、混合与液液界面 | `materials` | [M3ConnectedVesselValidation](../../fabric/src/test/java/mmm/fluid/M3ConnectedVesselValidation.java) |
| 表面张力和润湿 | `surface-tension` | [M4SurfaceTensionValidation](../../fabric/src/test/java/mmm/fluid/M4SurfaceTensionValidation.java) |
| 气体库存、状态方程与孔流 | `air-network` | [AirNetworkValidation](../../fabric/src/test/java/mmm/fluid/air/AirNetworkValidation.java) |
| 气腔扫描和连通关系 | `air-cavity` | [AirCavityScannerValidation](../../fabric/src/test/java/mmm/fluid/AirCavityScannerValidation.java) |
| 液体与气体压力耦合 | `gas-liquid` | [M5GasLiquidCouplingValidation](../../fabric/src/test/java/mmm/fluid/M5GasLiquidCouplingValidation.java) |
| 控制器清空后再注液的气腔归属 | `controller-gas-lineage` | [ProductionFluidReplayValidation](../../fabric/src/test/java/mmm/simulation/ProductionFluidReplayValidation.java) |
| 生产场景耗时 | `production-live-budget` | [ProductionFluidReplayValidation](../../fabric/src/test/java/mmm/simulation/ProductionFluidReplayValidation.java) |
| 连续注液、后端恢复和长时间物理行为 | `production-replay` | [ProductionFluidReplayValidation](../../fabric/src/test/java/mmm/simulation/ProductionFluidReplayValidation.java) |
| 区域数量、预算、重叠和选择 | `region-manager` | [FluidRegionManagerValidation](../../fabric/src/test/java/mmm/simulation/FluidRegionManagerValidation.java) |
| 调度、暂停、完整状态交接 | `lifecycle` | [FluidRegionLifecycleValidation](../../fabric/src/test/java/mmm/simulation/FluidRegionLifecycleValidation.java)、[FluidDomainStateParityValidation](../../fabric/src/test/java/mmm/simulation/FluidDomainStateParityValidation.java) |
| 实体位移观测和液体响应 | `entity-fluid` | [EntityFluidWiringValidation](../../fabric/src/test/java/mmm/simulation/EntityFluidWiringValidation.java)、[ProductionFluidReplayValidation](../../fabric/src/test/java/mmm/simulation/ProductionFluidReplayValidation.java) |
| 压力、自由面、速度传递与平流 | `free-surface` | [FlipFluidFreeSurfaceValidation](../../fabric/src/test/java/mmm/fluid/FlipFluidFreeSurfaceValidation.java) |
| 实际质量的稀疏速度传递与转置虚功 | `marker-transfer` | [MarkerVelocityTransferValidation](../../fabric/src/test/java/mmm/fluid/MarkerVelocityTransferValidation.java) |
| 仿射速度、压力惯性、功与有限气体储存 | `marker-pressure` | [MarkerPressureValidation](../../fabric/src/test/java/mmm/fluid/MarkerPressureValidation.java) |
| 粒子拆分、合并和数据列搬移 | `reseeding` | [FlipFluidReseedingValidation](../../fabric/src/test/java/mmm/fluid/FlipFluidReseedingValidation.java)、[CarrierMutationValidation](../../fabric/src/test/java/mmm/fluid/CarrierMutationValidation.java) |
| 黏性解析解、谱界、局部网格碰撞和连续碰撞检测 | `cut-cell` | [FlipCutCellValidation](../../solver/src/test/java/mmm/fluid/FlipCutCellValidation.java) |
| Minecraft 方块形状采样 | `shape` | [FluidCutCellShapeValidation](../../fabric/src/test/java/mmm/simulation/FluidCutCellShapeValidation.java) |
| 存档、世界能力包、配置和启动恢复 | `region-state` | [RegionRepositoryAdapterValidation](../../fabric/src/test/java/mmm/simulation/persistence/RegionRepositoryAdapterValidation.java)、[WorldCapabilityBundleStoreValidation](../../fabric/src/test/java/mmm/simulation/persistence/WorldCapabilityBundleStoreValidation.java)、[WorldCapabilityStartupValidation](../../fabric/src/test/java/mmm/simulation/persistence/WorldCapabilityStartupValidation.java) |
| 网络、查询、探针和提交通知 | `protocol` | [FluidNetworkProtocolValidation](../../fabric/src/test/java/mmm/network/FluidNetworkProtocolValidation.java)、[ShellFluidQueryValidation](../../fabric/src/test/java/mmm/simulation/ShellFluidQueryValidation.java)、[CommittedEventHubValidation](../../fabric/src/test/java/mmm/simulation/CommittedEventHubValidation.java) |
| 控制器外壳和内腔扫描 | `boundary` | [FluidBoundaryScannerValidation](../../fabric/src/test/java/mmm/simulation/controller/FluidBoundaryScannerValidation.java) |
| 开发场景的世界接管备份 | `takeover` | [TakeoverJournalValidation](../../fabric/src/test/java/mmm/simulation/TakeoverJournalValidation.java) |
| 桶、发射器、库存编码与事务恢复 | `containers` | [M6ContainerValidation](../../fabric/src/test/java/mmm/item/M6ContainerValidation.java)、[ContainerApplicationValidation](../../app/src/test/java/mmm/application/ContainerApplicationValidation.java)、[ContainerTransferStoreValidation](../../fabric/src/test/java/mmm/simulation/ContainerTransferStoreValidation.java) |
| 世界方块归属和恢复 | `ownership` | [M6OwnershipValidation](../../fabric/src/test/java/mmm/simulation/ownership/M6OwnershipValidation.java) |
| 命令日志 | `audit` | [CommandAuditFileValidation](../../fabric/src/test/java/mmm/logging/CommandAuditFileValidation.java) |
| 化学反应和组分输运 | `chemistry` | [M7ChemistryValidation](../../fabric/src/test/java/mmm/chemistry/M7ChemistryValidation.java)、[M7ChemicalTransportValidation](../../fabric/src/test/java/mmm/fluid/M7ChemicalTransportValidation.java) |
| 颗粒运动、沉积和固液转移 | `granular` | [M8GranularValidation](../../fabric/src/test/java/mmm/granular/M8GranularValidation.java)、[GranularReactionCandidateValidation](../../fabric/src/test/java/mmm/fluid/GranularReactionCandidateValidation.java) |
| 内能、导热、反应热、相变和设备换热 | `thermal` | [M9ThermalValidation](../../fabric/src/test/java/mmm/fluid/M9ThermalValidation.java) |
| 零导热平流、连续组成密度与热浮力、caloric 空间观察及冷热尖峰单调性 | `thermal` | [LiquidThermalAdvectionValidation](../../fabric/src/test/java/mmm/fluid/LiquidThermalAdvectionValidation.java)、[LiquidStratificationValidation](../../fabric/src/test/java/mmm/fluid/LiquidStratificationValidation.java) |
| 有限热接触解析解、跨热容分段熵增与逐载体正温 | `thermal` | [ThermalContactValidation](../../model/src/test/java/mmm/thermal/ThermalContactValidation.java) |

表中是定位场景的入口，完整调用列表以 `CpuValidationSuite` 为准。长场景的注液坐标、循环步数和逐步断言留在测试代码中维护。

输运与空间矩的聚焦入口复用现有断言：

```powershell
./gradlew.bat :fabric:runLiquidTransportValidation --args="components" --offline --no-daemon
./gradlew.bat :fabric:runLiquidTransportValidation --args="geometry-remap" --offline --no-daemon
./gradlew.bat :fabric:runLiquidTransportValidation --args="handoff-short" --offline --no-daemon
./gradlew.bat :fabric:runCarrierMutationValidation --offline --no-daemon
./gradlew.bat :fabric:runRemapMomentsValidation --args="transport-moments" --offline --no-daemon
./gradlew.bat :fabric:runRemapMomentsValidation --args="joint-moments" --offline --no-daemon
```

液体输运参数为 `--fast`、`components`、`chemical-tails`、`geometry-remap`、`representation`、`mass`、`quantization`、`continuity`、`handoff`、`handoff-short`；空间矩参数为 `transport-moments`、`joint-moments`、`sweep-only`、`footprint-only`、`common-mesh`。省略参数执行完整入口，未知参数直接拒绝。`handoff-short` 从实际载体速度构建流场，检查零通量、正向进出抵消、均匀平移、独立粒子到达、矩重建及两条平流路径的步长约束；长轨迹使用 `handoff` 与 `continuity`。`geometry-remap` 复用零时长正出流、断开分量容量、低 quanta 拒绝与完整逻辑步矩统计用例。`transport-moments` 同时用逐载体精确参考检查微盒汇总结果，覆盖非二进制份额、供给重分配、零出流和完全流出，逐位核对最终观察及舍入差额。

`common-mesh` 单独运行真实 L 形来源首步之后的压力场，检查共同运动网格的正体积、无全局重叠、时间减半的第一矩一致性及数量分配前的机械支撑，并报告参考几何成本。该参考处于测试代码中，一般场 advection、最终库存及后续 CPU 步按整合计划继续验收。解析的共享面切向流量矩与交叠检查同时属于默认及 `sweep-only`。

`joint-moments` 覆盖四点薄三维支撑、三轴排列、分组合并、位置推进、面法向投影、节点不足拒绝、独立小速度模式、悬殊质量的第一矩及位置—速度交叉矩。原始正来源的一、二阶矩用精确有理数独立计算；同时复用低 quanta 旋转与小份额剪切。有界流入形状对照检查原支撑正交补、多个质量份额的共同投影、完整机械矩及原线性剪切；三轴正反向的一、二点同速来源通过实际 remap 发布后检查接收格的秩四支撑、正权重、精确库存、动量、动能与 `kg·m²` 增量上界，零时和零通量保持原秩。`sweep-only` 用矩形和仿射楔体的解析二阶矩检查精确扫掠前缀及完整协方差。`representation` 组合完整逻辑步中的分次输运、静止饱和液体的压缩排液以及私有候选预算拒绝；分次累计用例用固壁隔开的液体分区分别提供力学输运与末段排液。

薄层速度拟合可通过 `:fabric:runMarkerPressureValidation --args="--thin-layer"` 单独核对三轴薄层、共面支撑、实际动量与角动量、动能、同速零梯度，以及全输入轴的拟合/转置和压力惯量。相同断言同时参与完整压力核。

`--args="--shared-trace"` 聚焦三轴斜薄共享面，核对面上点值与两切向斜率连续、实际动量和角动量、投影动能、转置虚功及三轴刚体旋转核。相同断言同时参与 `--kernel`。

压力核可通过 `:fabric:runMarkerPressureValidation --args="--kernel"` 单独运行仿射拟合、转置虚功、固定壁面投影、独立通量、实际质量 Gram 矩阵、有限气体储存和极端数值对照；完整 `marker-pressure` 另含生产张力与壁面输运场景。

`--args="--chemical-pressure"` 复用原热化学抽取场景的库存与实体输入，在前四个逻辑步的首个压力失败处报告实际残差分区、拉回系数的精确因式差及速度空间秩。包级验收继续运行 `chemistry:short` 的完整物理断言。

`--args="--pressure-gauge"` 聚焦部分充液格的压力参考不变性，核对真实湿体积行、有限气体恒压载荷与固定墙投影后的实际载体力。相同断言同时参与 `--kernel`。

密闭控制器注液的有界诊断使用 `:fabric:runBoundaryPourDiagnostic --offline --no-daemon`。它执行真实完整逻辑步及捕获恢复，从首个失败来源重放力学子步，报告实际质量动能、气腔储存功、压力残差功、仿射梯度及薄层载体元组。压力与输运数据通过各模块的包内诊断视图读取；通量重放在独立实例上执行既有规划阶段。追加 `--args=--flux` 聚焦有限气腔的连续通量、供给缩放、逐面舍入与容量修正；先核验原始库存、PLIC、独立到达预留和全部最终面通量相同，再报告各阶段差额。

`POUR full` 分别报告捕获、独立恢复与完整后端计算耗时，成功时列出完成和累计预留子步数。`POUR replay` 保留原始异常，列出失败阶段、完成和累计预留子步数、当前子步耗时及累计计算耗时；累计计算包含来源恢复、子步选择和力学计算，详细快照与日志输出单独执行。完成子步包含此前细化后丢弃的尝试，预留子步遵循生产累计上限。最多检查四个逻辑步；全步已失败时，即使力学短重放完成或达到预算，诊断仍以失败退出。完整场景验收使用 `gas-liquid:short`。

追加 `--args="--l-shape --flux"` 可复用三格 L 形输入快速重放第二步的输运失败；去掉 `--flux` 可查看详细压力和空间矩。该输入与 `common-mesh` 共用来源夹具，逐次尝试完整步，发生失败时保存来源并重放，继续使用非零退出码报告失败。

`--args="--gas-geometry"` 单独校验压力准备和发布中的当前微盒归属：同格两个独立气腔、实际接触气压参考、液体第一矩变化、frame 替换后的几何与候选拒绝。

`--args="--micro-velocity-support"` 检查该模型的单点、共线、共面及相邻 float 近共面布局，分别报告采样、舍入惯性、压力和联合矩阵的秩。实际采样的零空间必须保持零液体体积通量；另复用每个断开微盒有八个粒子的旋涡场景，检查内部运动的表示能力。入口汇总全部不满足项后失败，候选模型满足所有条件时通过。

`--args="--micro-velocity-moments"` 在上述对照后，按体积加权平均速度的精确零空间选择内部旋涡基底，再检查实际粒子质量支撑。覆盖 256 种掩码、两种质量和位置布局，以及两粒子共线、稠密和断开微盒；校验空间基底与采样矩阵一致、压力连续性、虚功和墙面反力，并比较准备前后的原始状态。静水对照先校核实际质量积分与几何压力功的相容性；偏移输入另输出原始体积第一矩与 PLIC 的差额。该入口在来源或积分条件不满足时失败，支持范围与后续处理按 M01、M02 的完成要求确定。

自然对流增长率随 `thermal` 选择器常驻校验：两级网格、临界 Ra 两侧、两倍线性振幅，以及体积、材料质量、species、壁面 source/sink、舍入与载体内能闭合。单独运行用 `./gradlew.bat :fabric:runConvectionExperiment --args="--verify-growth" --offline --no-daemon`；省去 `--verify-growth` 可仅输出测量。物性缩放、墙面条件与热量近似见[线性对流有效域](../architecture/thermal-state.md#自然对流的线性有效域)。

粒子分布研究使用 `./gradlew.bat :fabric:runMarkerDistributionExperiment --offline --no-daemon`。默认输出固壁法向轮廓、正反向平流、满液体与自由面解析保面积映射、重播种二阶矩和 5,000 步溃坝的空间误差，并对照封闭满液体约束前后的压力体积；用 `--args="analytic transport"` 可只运行指定对照。`--args="free-analytic"` 从逆解析映射独立积分液体覆盖率，报告两级参考积分的差异，并检查积分点及网格细化后 L1 误差递减和整体体积闭合。默认构建的完整 `free-surface` 同时运行 5,000 步溃坝，逐步检查精确单元容量、共享压力库存、满格积分点分布及累计质量舍入界。`free-surface` 守护 G2P 数值依赖分量隔离、未使用外推面的隔离、有限气体耦合、满液体/自由面切换的工作区更新，以及精确满液体判定和微小真实缺口，`reseeding` 守护旋转协方差、秩损失拒绝、线性速度场和体积推导的拆分空间矩误差界。空间验收见[单元有限体积输运](../architecture/fluid-solver.md#单元有限体积输运)，完整步的成本要求见[生产资格](../roadmap/performance-and-backends.md#有限体积输运的生产资格)。

### 什么时候追加测试

- 改存档字段或恢复状态：追加 `region-state`。
- 改压力算法并影响气体：追加 `gas-liquid`；改变粒子分布时追加 `reseeding`。
- 改气体热力学：运行 `thermal`；改等温状态方程追加 `air-network`，改压力矩阵接入追加 `gas-liquid,free-surface`。
- 改气腔与局部方块形状共享的几何：追加 `cut-cell`；Minecraft 采样变化再追加 `shape`。
- 改控制器摘要或预览授权：使用 `protocol,lifecycle`；改删除提交再加 `region-state,ownership`。
- 改容器提交后的事件：使用 `protocol,containers`。
- 改颗粒与气体、压力、热学或存档共享的数据：追加相应领域。
- 改后端状态交接：运行 `lifecycle` 的完整数据位模式比较，再运行受影响载体的测试。

新增回归场景放入负责该行为的现有测试。只有新失败类型确实属于独立领域时，才增加选择器。

<a id="performance"></a>
## 性能和长期场景

快速离线测量使用 `./gradlew.bat :fabric:runStepBenchmark --offline`。
[ProductionStepBenchmark](../../fabric/src/test/java/mmm/simulation/ProductionStepBenchmark.java)
测量控制器半满场景与大型 demo，各预热 20 步、采样 20 步，分别报告捕获、计算、提交和完整步的均值、中位数、p95 与最大值，以及调用线程的内存分配量，并检查物质与能量收支。
测量范围是 CPU 计算流程，适合同机同配置的优化对比；评估耗时收益时应结合多次测量的波动。

组合过程使用 [CompositeStepBenchmark](../../fabric/src/test/java/mmm/simulation/CompositeStepBenchmark.java)，例如：

```powershell
./gradlew.bat :fabric:runCompositeBenchmark -PbenchmarkScene=thermochem -PbenchmarkWarmup=20 -PbenchmarkSamples=80 --offline
```

`benchmarkOutput` 指定输出前缀（建议绝对路径），默认写入根目录 `build/composite-profile/<scene>.csv`。
`-PbenchmarkJfr=true` 另录制采样阶段的 JFR；采样运行的耗时应与普通计时分开报告。

| scene | 测量范围与实际过程 |
|---|---|
| `granular` | 256 个初始细砂 parcel，连续推进至沉积后，向床面附近液体施加刚体扰动；需要足够步数形成沉积。CSV 分别记录沉积、唤醒、接触粒子和冲量，接触不保证唤醒 |
| `granular-active` / `granular-large` | 每次重放同一初态的完整后端步并提交，分别保留 256 / 1,536 个活动 parcel；初态捕获在计时外，适合对比持续活动的耦合算子成本 |
| `thermochem` | 每步注入带酸碱和盐离子的液体，结算待处理相变体积并执行完整步；采样阶段必须有实际化学变化和析出 |
| `mixed` | 显式安排三个区域：隔轮运动、每轮独立热轴、保持静止；记录实际入口调用次数，测量离线组合工作量 |
| `presentation` | 对固定大型状态分别测量捕获、恢复、四类展示快照与液面网格生成的时间和分配量 |

完整计时包含并行工作任务的等待时间。分配量统计调用线程，工作线程分配另用 JFR 排查；额外状态导出、审计与 CSV 格式化位于测量范围外。
连续推进场景包含请求捕获、计算和提交；`thermochem` 还包含注液操作。
这些场景不包含 Minecraft 活动分类、到期调度、世界读取、网络编码、磁盘写入或 GPU 绘制。
`mixed` 的调用次数统计全部区域；粒子、固体和内能列描述第一个区域，其余区域也逐步检查气体与内能收支。
应在同机交错启动独立 JVM 重复测量，并查看逐步过程计数；诊断列相同只能说明这些观测一致，完整状态等价需要另行比较。

`production-live-budget` 调用 `defaultLiveStepsRemainBounded`，验证两种生产负载：

| 场景 | 规模 | 耗时要求 |
|---|---|---|
| 默认控制器注入 16 BV | `8×8×8` 场景，20 个实际计算步 | 每步不超过 40 ms |
| 空场景最大装量的 `demo` | `24×16×24` 网格，2,592 个液体单元、20,736 个初始粒子，完整物理功能，20 步 | 连续超过 200 ms 的步数小于三步 |

报告分别记录 JVM 预热和被测步的耗时，并保留粒子数、体积、相变操作、子步数、PCG 次数与残差。优化后用同一物理配置复测，保持原有预算和守恒检查。

`production-replay` 检查长时间注液、清空、气腔归属、相变功结算、实体响应以及后端捕获/恢复。它关注每步物质与能量收支。性能是否达标由上面的独立测试判定。

`granular` 中的 [GranularCarrierValidation](../../solver/src/test/java/mmm/fluid/GranularCarrierValidation.java) 检查多 parcel 阻力交换的动量守恒、能量耗散和顺序无关性、有限颗粒质量的压力功和连续性、跨单元面的连续液体接触、静水梯度给出的浮力、步末阻力有效性检查，以及小时间步下对压力零点的独立性。`containers` 保留正常操作随机序列的种子 4、54、103，覆盖薄液层反冲、密集颗粒排水与高压下的时间细化。

`./gradlew.bat :fabric:runGranularCarrierValidation --offline --no-daemon` 单独执行完整颗粒载体校验。加 `--args="--contacts"` 聚焦固定墙与床体连续碰撞、Coulomb 摩擦、真实支撑年龄、容量暂停、合并后联合球体几何、六轴悬殊质量碰撞的动量与质量第一矩，以及碰撞改道后的三球接触链。

`./gradlew.bat :fabric:runThermalValidation --args="--granular-energy" --offline --no-daemon` 聚焦颗粒内能列的插入、部分提取、床层转换、清空和精确热账。省去参数执行完整热学入口，沿用 `thermal` 的全部场景。

同一 `granular` 选择器中的 [GranularBedWakeValidation](../../fabric/src/test/java/mmm/fluid/GranularBedWakeValidation.java) 使用正式全能力注册表、启用相变，并逐步经过后端捕获/恢复：细砂沉积并休眠后，弱刚体扰动应无侵蚀，较强扰动应重新起动并实际水平搬运，减弱为静止物体后应再次沉积、休眠。逐步记录起动数、活动数、床面 Shields 数与真实接触数，检查恢复后与每步液体、active、deposit、coherent 的联合逐格容量，以及固体质量/体积和气体、内能收支；另检查释放瞬间的零速度、内能搬运、碰撞空间及干区/隔墙误触发。`GranularCarrierValidation` 同时检查薄床切向自由流动和时间细化。

批量排查使用 [ControllerInventoryDurabilityValidation](../../fabric/src/test/java/mmm/simulation/ControllerInventoryDurabilityValidation.java) 的带种子操作序列，每次操作后执行完整状态往返。参数依次是起始种子、种子数量、操作数和是否禁用沙粒（可省略，默认 `false`）：

```powershell
./gradlew.bat :fabric:runNormalPlayFuzz '-PfuzzArgs=0,200,60' --offline --no-daemon
```

扩展动作包含独立热轴、移动刚体、油、成批注入和随机注入点；容量拒绝记录为 skip，强制仿真中断使任务失败。首参数也可用冒号分隔一组种子，或用 `reported` 运行九类已报告异常的全部 28 个固定种子：

```powershell
./gradlew.bat :fabric:runNormalPlayFuzz '-PfuzzArgs=reported,1,80' --offline --no-daemon
./gradlew.bat :fabric:runNormalPlayFuzz '-PfuzzArgs=1393:1433,1,80' --offline --no-daemon
```

失败报告包含种子、操作序号、完整操作轨迹和根因分类。原动作集的种子 4、54、103 保持在既有 `containers` 回归中。

`controller-gas-lineage` 集中复现清空扩大气体空间后，从外壳附近再次注液的过程。修改扫描几何时先运行 `air-cavity`，修改气压作用时再运行 `gas-liquid`。

<a id="architecture"></a>
## 架构与编译检查

修改类职责、依赖或模块位置时运行：

```powershell
./gradlew.bat verifyArchitecture --offline --no-daemon
```

`verifyArchitecture` 是仓库根任务，会调用 `:fabric:runArchitectureValidation`；它不是 `:fabric:runCpuValidation` 的 `cpuValidation` 选择器。架构检查应使用上面的根任务，不要写成 `-PcpuValidation=architecture` 或 `:fabric:verifyArchitecture`。

[ArchitectureDependencyValidation](../../fabric/src/test/java/mmm/validation/ArchitectureDependencyValidation.java) 检查模块 import 方向、纯 Java 模块包循环、持久化与运行时依赖隔离、唯一服务器运行时注册点，以及生产类 1000 行上限。超过 600 行会提示，超过 1000 行会失败。[CarrierMutationPointInventoryValidation](../../fabric/src/test/java/mmm/validation/CarrierMutationPointInventoryValidation.java) 同时检查粒子结构修改的调用位置。

需要单独运行 model、solver、app 的验证时，使用根任务 `runModuleValidation`。当前源码规模可用 [Python 工具](pytool.md)重新统计。

修改客户端类型、Jade 依赖或界面后，编译服务端和客户端源码：

```powershell
./gradlew.bat :fabric:compileJava :fabric:compileClientJava --offline --no-daemon
```

<a id="gpu"></a>
## GPU probe 验证

任务定义在 [kernel 构建文件](../../gpu-kernel/build.gradle)和 [Fabric 适配构建文件](../../gpu-probe/build.gradle)。

改适配器或模块依赖时，先运行：

```powershell
./gradlew.bat :gpu-kernel:compileJava :gpu-probe:compileJava :gpu-probe:runAdapterValidation verifyArchitecture --offline --no-daemon
```

改设备资源、着色器、checkpoint 或数值算法时，运行基础数值验证：

```powershell
./gradlew.bat :gpu-kernel:runGpuFlipValidation -PgpuProbeSkipLarge=true -PgpuProbeSkipLong=true --offline --no-daemon
```

粒子分布或能量变化需要长时间验证，可先用 100 步定位，再按风险运行默认 1000 步：

```powershell
./gradlew.bat :gpu-kernel:runGpuLongRunSurfaceValidation -PgpuProbeLongRunSteps=100 --offline --no-daemon
```

修改设备能力检测或初始化时，再运行 `:gpu-kernel:runHeadlessGpuProbe`。

Windows 上若 `vkCreateInstance` 返回 `-9`，可在当前 PowerShell 进程设置 `$env:VK_LOADER_DEBUG='error,warn,driver'` 后运行 headless probe，检查加载器实际发现的驱动清单和 DLL。日志出现 `Found no drivers` 时，核对当前显卡驱动对应的 `VulkanDriverName` 注册项和 ICD 清单。可用 `$env:VK_DRIVER_FILES` 指定清单验证已安装驱动；清单中的 `ICD.library_path` 应指向驱动 DLL 的绝对路径，必要时在 `build` 下生成清单副本。任务继承此环境变量，验证后恢复原值。驱动发现机制与环境变量见 [Khronos 加载器文档](https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderDriverInterface.md#overriding-the-default-driver-discovery)。

GPU 校验中的 CPU 对照通过 `CpuFluidSolverBackend` 计算完整候选结果，按投影后的运动自动细分时间步，再接收完成的区域状态；同一外部步始终推进完整的配置时长。

GPU 粒子合并使用原始 float 的精确二进制值判断体积能否守恒，着色器中的 `precise` 保证编译器保留该运算顺序。长跑比较精确库存、机械能、空间限制与展示覆盖范围。并行归约允许解析得到的舍入误差，CPU/GPU 逐格轨迹按各自数值容差比较。

设备性能使用 Vulkan timestamp 测量，调度与超时使用实际经过时间。生产 GPU 的完整功能要求见 [性能与后端规划](../roadmap/performance-and-backends.md)。

## 需要进入游戏时

当问题依赖实际画面、Minecraft 方块形状或磁盘重启过程时，准备一个可重复场景，写明操作、预期现象和需要保留的日志。发布构建、产物校验和部署步骤见 [验收与发布](../roadmap/acceptance-and-release.md)。

## 正常操作随机仿真

`./gradlew.bat :fabric:runNormalPlayFuzz '-PfuzzArgs=16,80,false,8,0,13'` 扫描 13 种几何、每种 16 个种子，每个种子 80 步；每步检查库存、能量和 capture/restore。
单个复现用 `-PfuzzArgs=replay,1,203,80`；严格子序列最小化用 `-PfuzzArgs=minimize,1,203`。`reported` 运行既有历史种子集。

抽取往返传递完整化学账本及内能，核对凝聚态库存与能量；液体位置改变时的气腔压缩功按气体与热学审计核对。随机温度可能超过材料的 Boussinesq 适用温区：这类机械候选应整体拒绝，测试核对拒绝前后状态完全一致，并单独报告 `FUZZ VALIDITY REJECTIONS` 次数。其他机械求解失败继续作为失败反例。

分数抽取后的盘上气腔归属回归使用 `-PfuzzArgs=replay,partialdisk,1,200,49`；颗粒阻力外层收敛与完整候选时间细化回归使用 `-PfuzzArgs=replay,17,7810,37`。两条序列都保留逐动作守恒审计及恢复检查。

极端温压测试使用正式材料定义，在零重力封闭域设置 `10 Pa / 10^7 K`、`10^12 Pa / 10 K`、`10^12 Pa / 150 K`、`10^12 Pa / 10^7 K` 四组初态，连续执行开启相变的后端步骤并检查气体和内能收支及恢复。低压高温与 150 K 高压用例还要求实际发生相变。10 K 用例检查没有可接受冻结批次时仍能守恒推进。上述数值为基准验证范围；各物理量输入范围由对应材料与状态方程定义约束。


## 共同主干收尾记录

2026-10-17：纯液体和含固相区域已改用共同 MAC 压力、隐式黏性与守恒输运，仿射压力分支、专用液体形状和高阶矩重建路径已删除。本次按用户要求停止扩展范围，保存可继续开发的阶段结果；M02/M03/M07/M10 等整包完成状态保持未验收。

复用 `:fabric:compileTestJava :solver:compileTestJava` 产物运行以下入口（主类均在 `mmm.fluid`）：

| 入口 | 本次结果 |
|---|---|
| `MarkerPressureValidation` | 通过同速、薄层梯度、静止满液体恢复及 L 形 8 步 |
| `CellRemapMomentsValidation` | 全部通过：L 形、不同格长及方向平移、两种密度库存、三轴旋转；旋转保留原 80% 角动量要求 |
| `GasPhaseInterfaceValidation` | 通过断开接触、5 类有限气体相变各 3 步及恢复；库存和热账闭合 |
| `LiquidViscosityValidation` | 通过隐式衰减、墙面动量、气侧自由表面、高黏度完整步与恢复；剪切／旋涡误差随网格加密约降为四分之一 |
| `M9ThermalValidation --gas-topology` | 气腔持久化恢复、压缩／膨胀和通风流程通过；综合入口随后停在相变用例 `phaseGeometryRemapIsPreparedBeforeCarrierCommit` 的单次完全汽化断言，仍未全部通过 |
| `GranularCarrierValidation --contacts` | 颗粒接触、宏观动量和恢复回归通过，坐标舍入尺度的相切允许误差 |
| `GranularBedWakeValidation` | 最新低耗散主干通过启动 3 步及恢复；完整循环在第 39 步以后遇到接触 KKT 残差约 `1.7e-31`、误差界约 `1.4e-42`，反复细分超过预算。原后段容量量化问题已增加有限量子重分配，但尚未重放到原失败步 |

最新沙床启动的完整步（捕获、候选计算及恢复）为 916、759、1146 ms。性能预算仍未达标。三轴旋转四步后的动能约 195.7 J，替换前为 87.9 J，保留原角动量验收阈值。此前注液 200 步通过记录来自前序版本，本轮未重复长程注液性能采样。

后续集中处理颗粒接触求解的过严残差认证，以及分步相变模型下的连续汽化验收。本次按用户限制控制投入，未继续扩展接触算子或相变算法。最新日志为本地 `tmp/fix-*.log`。
