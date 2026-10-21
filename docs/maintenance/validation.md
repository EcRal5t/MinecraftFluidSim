# 修改后的验证

先明确当前需要回答的问题与成本预期。
有九成把握的改动跳过额外验证、校验和测试；当前 solver 整合优先运行完整步生产预算。
有具体未知风险时，从下列入口选择能回答该问题的最小场景，复用同一次构建产物。

## CPU 测试怎么运行

完整工程入口为根项目 `runValidation`，统一执行 CPU 领域、架构和 GPU 命令适配器验证。默认使用 `fast`，指定 `-PcpuValidation=full` 运行全部 CPU 领域；根项目 `check` 接入相同入口。模块的独立 main 验证任务继续供定位使用。

```powershell
./gradlew.bat test runValidation -PcpuValidation=full -PcpuValidationKeepGoing=true --continue --max-workers=1 --offline --no-daemon
```

在仓库根目录执行，例如：

```powershell
./gradlew.bat :fabric:runCpuValidation '-PcpuValidation=region-state,containers' --offline --no-daemon
```

`cpuValidation` 是测试选择器，多个名称用逗号连接。[CpuValidationSuite](../../fabric/src/test/java/mmm/validation/CpuValidationSuite.java) 定义了全部名称与组合。
[fabric/build.gradle](../../fabric/build.gradle) 定义执行任务。

| 选择方式 | 含义 |
|---|---|
| 指定名称，例如 `thermal` | 完整运行该领域；多个名称会去重 |
| 名称加 `:short`，例如 `cut-cell:short` | 单独运行该领域的短入口；没有短入口的领域运行完整入口并标明 `full` |
| 省略参数或使用 `fast` | 运行默认组合，提供短模式的场景使用短模式 |
| `milestone` | 较广的阶段验收组合，仍使用各场景的短模式 |
| `soak` | 完整运行 `gas-liquid`、`free-surface`、`cell-inventory` |
| `full` | 完整运行所有已登记的领域 |
| `list` | 输出当前组合与选择器名称 |

显式写出的领域名称运行完整模式，即使它也出现在 `fast` 中。`production-live-budget` 和 `controller-gas-lineage` 需要显式选择，或通过 `full` 执行。根项目 `check` 默认运行 `fast`，完整验收须显式指定 `-PcpuValidation=full`。

同一领域同时选择短模式和完整模式时，完整模式优先。基线测量可加 `-PcpuValidationKeepGoing=true`，记录当前领域的首个失败后继续下一领域，最后仍以失败退出；默认在首个失败处停止。输出区分断言、参数配置与执行失败，并记录每个领域的耗时。超时或主动中止的进程须另行登记，已开始的场景不计作通过。

`cut-cell:short` 保留参数、黏性、微盒几何、CCD、容量及恢复对照，省略 600 步障碍物流动。长场景单独运行：

```powershell
./gradlew.bat :solver:runSolverValidation --args="--flow-only" --offline --no-daemon
```

相变待同步体积的聚焦验证使用 `:fabric:runThermalValidation --args="--phase-volume"`，复用固液待处理任务、沸腾与凝结后的真实压力投影、恢复及容量拒绝断言，同时检查逐格精确相消与快照独立性。完整热学验证仍使用 `thermal`。

气腔刷新、持久化同步、压缩功、排气及气液相变的热账交接可用 `:fabric:runThermalValidation --args="--gas-topology"` 聚焦运行，复用现有热学场景。沸腾搜索固定输入与端口复用的对照归属 `air-cavity`。

气液物种转换及其热学后继可用 `:fabric:runThermalValidation --args="--phase-species"` 聚焦运行；场景沿用完整热学入口。

热平流与分层后继使用 `:fabric:runThermalValidation --args="--flow"`；自然对流及后续换热使用 `--convection`；实际载体内能分配使用 `--assignment`。这些选择器共用完整热学入口的场景，已通过且未受改动影响的部分直接复用。
热平流、分层、自然对流及换热后继可用 `:fabric:runThermalValidation --args="--flow"` 聚焦运行，与完整入口共用同一场景列表。

`free-surface:short` 省略长静水、5,000 步溃坝、300 步台阶流动，以及输运中的连续交接轨迹和 1 秒、4 秒多网格旋流。其他短组合仍可能包含成本较高的领域；具体省略项由对应入口输出。

<a id="cpu"></a>
## 按改动选择测试

- **极端温度、压力与相变连续推进**
  - 选择器：`thermal-extremes`
  - 测试源码入口：[ExtremeThermalValidation](../../fabric/src/test/java/mmm/fluid/ExtremeThermalValidation.java)

- **材料、质量、混合与液液界面**
  - 选择器：`materials`
  - 测试源码入口：[M3ConnectedVesselValidation](../../fabric/src/test/java/mmm/fluid/M3ConnectedVesselValidation.java)

- **表面张力和润湿**
  - 选择器：`surface-tension`
  - 测试源码入口：[M4SurfaceTensionValidation](../../fabric/src/test/java/mmm/fluid/M4SurfaceTensionValidation.java)
  - 同一构建的聚焦入口：`M4SurfaceTensionValidation --wetting` 复用润湿 1,200 步及液液界面后继，`--liquid-pair` 单独运行该后继，`--curvature` 保留共同曲率场景。直接启动时使用 `fabric/build/cpu-validation-work/classpath.txt`、相同工作目录与 `-ea -Xmx512m`，记录对应构建摘要。

- **气体库存、状态方程与孔流**
  - 选择器：`air-network`
  - 测试源码入口：[AirNetworkValidation](../../fabric/src/test/java/mmm/fluid/air/AirNetworkValidation.java)

- **气腔扫描和连通关系**
  - 选择器：`air-cavity`
  - 测试源码入口：[AirCavityScannerValidation](../../fabric/src/test/java/mmm/fluid/AirCavityScannerValidation.java)

- **液体与气体压力耦合**
  - 选择器：`gas-liquid`
  - 测试源码入口：[M5GasLiquidCouplingValidation](../../fabric/src/test/java/mmm/fluid/M5GasLiquidCouplingValidation.java)

- **控制器清空后再注液的气腔归属**
  - 选择器：`controller-gas-lineage`
  - 测试源码入口：[ProductionFluidReplayValidation](../../fabric/src/test/java/mmm/simulation/ProductionFluidReplayValidation.java)

- **生产场景耗时**
  - 选择器：`production-live-budget`
  - 测试源码入口：[ProductionFluidReplayValidation](../../fabric/src/test/java/mmm/simulation/ProductionFluidReplayValidation.java)

- **连续注液、后端恢复和长时间物理行为**
  - 选择器：`production-replay`
  - 测试源码入口：[ProductionFluidReplayValidation](../../fabric/src/test/java/mmm/simulation/ProductionFluidReplayValidation.java)

- **区域数量、预算、重叠和选择**
  - 选择器：`region-manager`
  - 测试源码入口：[FluidRegionManagerValidation](../../fabric/src/test/java/mmm/simulation/FluidRegionManagerValidation.java)

- **调度、暂停、完整状态交接**
  - 选择器：`lifecycle`
  - 测试源码入口：
    - [FluidRegionLifecycleValidation](../../fabric/src/test/java/mmm/simulation/FluidRegionLifecycleValidation.java)
    - [FluidDomainStateParityValidation](../../fabric/src/test/java/mmm/simulation/FluidDomainStateParityValidation.java)

- **实体位移观测和液体响应**
  - 选择器：`entity-fluid`
  - 测试源码入口：
    - [EntityFluidWiringValidation](../../fabric/src/test/java/mmm/simulation/EntityFluidWiringValidation.java)
    - [ProductionFluidReplayValidation](../../fabric/src/test/java/mmm/simulation/ProductionFluidReplayValidation.java)

- **压力、自由面、速度传递与平流**
  - 选择器：`free-surface`
  - 测试源码入口：[FlipFluidFreeSurfaceValidation](../../fabric/src/test/java/mmm/fluid/FlipFluidFreeSurfaceValidation.java)

- **实际格面惯性与初始动量交接**
  - 选择器：`marker-transfer`
  - 测试源码入口：[MarkerVelocityTransferValidation](../../fabric/src/test/java/mmm/fluid/MarkerVelocityTransferValidation.java)

- **共同压力、薄层与有限气腔**
  - 选择器：`marker-pressure`
  - 测试源码入口：[MarkerPressureValidation](../../fabric/src/test/java/mmm/fluid/MarkerPressureValidation.java)

- **单元库存、动量、墙面交接与正常事务**
  - 选择器：`cell-inventory`
  - 测试源码入口：
    - [LiquidCellInventoryValidation](../../solver/src/test/java/mmm/fluid/LiquidCellInventoryValidation.java)
    - [MacMomentumAdvectionValidation](../../solver/src/test/java/mmm/fluid/MacMomentumAdvectionValidation.java)
    - [CarrierMutationValidation](../../fabric/src/test/java/mmm/fluid/CarrierMutationValidation.java)
    - [LiquidMacroscopicMotionValidation](../../solver/src/test/java/mmm/fluid/LiquidMacroscopicMotionValidation.java)：组合运行 `joint-moments`，其他正常轨迹通过该类的对应选择器运行。

- **相向液体的连续合流**
  - 选择器：`liquid-joining`，显式完整场景
  - 测试源码入口：[LiquidHandoffContinuityValidation](../../fabric/src/test/java/mmm/fluid/LiquidHandoffContinuityValidation.java)

- **黏性解析解、谱界、局部网格碰撞和连续碰撞检测**
  - 选择器：`cut-cell`
  - 测试源码入口：[FlipCutCellValidation](../../solver/src/test/java/mmm/fluid/FlipCutCellValidation.java)

- **Minecraft 方块形状采样**
  - 选择器：`shape`
  - 测试源码入口：[FluidCutCellShapeValidation](../../fabric/src/test/java/mmm/simulation/FluidCutCellShapeValidation.java)

- **存档、世界能力包、配置和启动恢复**
  - 选择器：`region-state`
  - 测试源码入口：
    - [RegionRepositoryAdapterValidation](../../fabric/src/test/java/mmm/simulation/persistence/RegionRepositoryAdapterValidation.java)
    - [WorldCapabilityBundleStoreValidation](../../fabric/src/test/java/mmm/simulation/persistence/WorldCapabilityBundleStoreValidation.java)
    - [WorldCapabilityStartupValidation](../../fabric/src/test/java/mmm/simulation/persistence/WorldCapabilityStartupValidation.java)

- **网络、查询、探针和提交通知**
  - 选择器：`protocol`
  - 测试源码入口：
    - [FluidNetworkProtocolValidation](../../fabric/src/test/java/mmm/network/FluidNetworkProtocolValidation.java)
    - [ShellFluidQueryValidation](../../fabric/src/test/java/mmm/simulation/ShellFluidQueryValidation.java)
    - [CommittedEventHubValidation](../../fabric/src/test/java/mmm/simulation/CommittedEventHubValidation.java)

- **控制器外壳和内腔扫描**
  - 选择器：`boundary`
  - 测试源码入口：[FluidBoundaryScannerValidation](../../fabric/src/test/java/mmm/simulation/controller/FluidBoundaryScannerValidation.java)

- **开发场景的世界接管备份**
  - 选择器：`takeover`
  - 测试源码入口：[TakeoverJournalValidation](../../fabric/src/test/java/mmm/simulation/TakeoverJournalValidation.java)

- **桶、发射器、库存编码与事务恢复**
  - 选择器：`containers`
  - 测试源码入口：
    - [M6ContainerValidation](../../fabric/src/test/java/mmm/item/M6ContainerValidation.java)
    - [ContainerApplicationValidation](../../app/src/test/java/mmm/application/ContainerApplicationValidation.java)
    - [ContainerTransferStoreValidation](../../fabric/src/test/java/mmm/simulation/ContainerTransferStoreValidation.java)

- **世界方块归属和恢复**
  - 选择器：`ownership`
  - 测试源码入口：[M6OwnershipValidation](../../fabric/src/test/java/mmm/simulation/ownership/M6OwnershipValidation.java)

- **命令日志**
  - 选择器：`audit`
  - 测试源码入口：[CommandAuditFileValidation](../../fabric/src/test/java/mmm/logging/CommandAuditFileValidation.java)

- **化学反应和组分输运**
  - 选择器：`chemistry`
  - 测试源码入口：
    - [M7ChemistryValidation](../../fabric/src/test/java/mmm/chemistry/M7ChemistryValidation.java)
    - [M7ChemicalTransportValidation](../../fabric/src/test/java/mmm/fluid/M7ChemicalTransportValidation.java)

- **颗粒运动、沉积和固液转移**
  - 选择器：`granular`
  - 测试源码入口：
    - [M8GranularValidation](../../fabric/src/test/java/mmm/granular/M8GranularValidation.java)
    - [GranularReactionCandidateValidation](../../fabric/src/test/java/mmm/fluid/GranularReactionCandidateValidation.java)

- **内能、导热、反应热、相变和设备换热**
  - 选择器：`thermal`
  - 测试源码入口：[M9ThermalValidation](../../fabric/src/test/java/mmm/fluid/M9ThermalValidation.java)

- **零导热平流、连续组成密度与热浮力、caloric 空间观察及冷热尖峰单调性**
  - 选择器：`thermal`
  - 测试源码入口：
    - [LiquidThermalAdvectionValidation](../../fabric/src/test/java/mmm/fluid/LiquidThermalAdvectionValidation.java)
    - [LiquidStratificationValidation](../../fabric/src/test/java/mmm/fluid/LiquidStratificationValidation.java)

- **有限热接触解析解、跨热容分段熵增与逐载体正温**
  - 选择器：`thermal`
  - 测试源码入口：[ThermalContactValidation](../../model/src/test/java/mmm/thermal/ThermalContactValidation.java)

以上是定位场景的入口，完整调用列表以 `CpuValidationSuite` 为准。长场景的注液坐标、循环步数和逐步断言留在测试代码中维护。

输运与空间矩的聚焦入口复用现有断言：

```powershell
./gradlew.bat :fabric:runLiquidTransportValidation --args="components" --offline --no-daemon
./gradlew.bat :fabric:runLiquidTransportValidation --args="geometry-remap" --offline --no-daemon
./gradlew.bat :fabric:runLiquidTransportValidation --args="handoff-short" --offline --no-daemon
./gradlew.bat :fabric:runCarrierMutationValidation --offline --no-daemon
./gradlew.bat :fabric:runRemapMomentsValidation --args="transport-moments" --offline --no-daemon
./gradlew.bat :fabric:runRemapMomentsValidation --args="joint-moments" --offline --no-daemon
```

常用输运参数为 `components`、`geometry-remap` 和 `handoff-short`，长轨迹使用 `handoff` 与 `continuity`。
空间矩入口 [LiquidMacroscopicMotionValidation](../../solver/src/test/java/mmm/fluid/LiquidMacroscopicMotionValidation.java) 按 `transport-moments`、`joint-moments` 或 `common-mesh` 选择场景。`cell-inventory` 组合只运行其中的 `joint-moments`；完整宏观入口使用 `:fabric:runRemapMomentsValidation`，省略参数。
省略参数执行完整入口，全部参数及逐项断言以对应测试源码为准。

`common-mesh` 运行三格 L 形连续 8 步并逐步恢复，检查库存、宏观位移和动能。`transport-moments` 追加多方向平移和多密度库存；`joint-moments` 与 `footprint-only` 保留为宏观旋转和材料输运的选择器。具体通过状态见[验证证据](#当前验证证据)。

压力短回归使用 `:fabric:runMarkerPressureValidation`；`--args="--thin-layer"` 聚焦薄层库存、动能和恢复，`--args="--kernel"` 运行共同压力与 L 形流程。

注液使用 `:fabric:runBoundaryPourDiagnostic`，默认执行 4 个完整步及捕获恢复；`--args="--steps=200"` 指定步数，`--args="--l-shape"` 切换 L 形来源，`--flux` 追加输运统计。每步报告完整耗时、捕获与计算耗时、子步、压力迭代、残差和动能；失败保留异常并以非零状态退出。

自然对流随 `thermal` 选择器检查宏观增长：一档普通扰动、两级网格、临界 Ra 两侧，增长率按空间离散与时间步长的误差范围评价，同时检查体积、材料质量、物种、壁面热源汇、舍入与载体内能闭合。
单独运行用 `./gradlew.bat :fabric:runConvectionExperiment --args="--verify-growth" --offline --no-daemon`；
省去 `--verify-growth` 可仅输出测量。物性缩放、墙面条件与热量近似见[自然对流的宏观增长](../architecture/thermal-state.md#自然对流的宏观增长)。

粒子分布离线研究使用 `./gradlew.bat :fabric:runMarkerDistributionExperiment --offline --no-daemon`。
用 `--args="analytic transport"` 选择解析平流对照，`--args="free-analytic"` 观察自由面覆盖误差与细化。
完整 `free-surface` 包含长时溃坝，检查库存、逐格容量和压力工作区交接。
`cell-inventory` 集中检查库存分配、低速与少数相连续流、墙面动量与热量交接、宏观旋转及正常库存事务；其他领域直接运行所属场景。
场景参数在测试源码维护，生产成本按[完整步预算](#performance)评价。

### 什么时候追加测试

- 改存档字段或恢复状态：追加 `region-state`。
- 改压力算法并影响气体：追加 `gas-liquid`；改变库存输运或动量表示时追加 `cell-inventory`。
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
| 默认控制器注入 16 m³ | `8×8×8` 场景，20 个实际计算步 | 每步不超过 40 ms |
| 空场景最大装量的 `demo` | `24×16×24` 网格，2,592 条初始 Stock（9×12×24）、液体体积 324 m³，完整物理功能，20 步 | 连续超过 200 ms 的步数小于三步 |

定位最大场景成本时，`ProductionFluidReplayValidation --demo-budget` 可直接运行同一最大 demo；它保留原场景、预热、20 个完整步和暂停判据。正式预算汇总仍采用同时覆盖控制器与最大 demo 的 `production-live-budget`。

报告分别记录 JVM 预热和被测步的耗时，并保留粒子数、体积、相变操作、子步数、PCG 次数与残差。优化后用同一物理配置复测，保持原有预算和守恒检查。

`production-replay` 检查长时间注液、清空、气腔归属、相变功结算、实体响应以及后端捕获/恢复。它关注每步物质与能量收支。性能是否达标由上面的独立测试判定。
同一构建的 `ProductionFluidReplayValidation --compressed-soak` 延长压缩平面气腔至 5,000 步，沿用原场景及固定误差预算，逐段输出累计机械能偏差。使用 `fabric/build/cpu-validation-work/classpath.txt`、同一工作目录与 `-ea -Xmx512m`，记录对应构建摘要。

`granular` 中的 [GranularCarrierValidation](../../solver/src/test/java/mmm/fluid/GranularCarrierValidation.java)
覆盖液固阻力动量交换、耗散、压力功、浮力和跨格连续接触。
`containers` 的种子 4、54、103 保留正常操作中的薄层反冲、颗粒排水与高压时间细化回放。

`./gradlew.bat :fabric:runGranularCarrierValidation --offline --no-daemon` 单独执行完整颗粒载体校验。
加 `--args="--contacts"` 聚焦固定墙与床体连续碰撞、Coulomb 摩擦、真实支撑年龄、容量暂停、合并后联合球体几何、六轴悬殊质量碰撞的动量与质量第一矩，以及碰撞改道后的三球接触链。

`./gradlew.bat :fabric:runThermalValidation --args="--granular-energy" --offline --no-daemon` 聚焦颗粒内能列的插入、部分提取、床层转换、清空和精确热账。省去参数执行完整热学入口，沿用 `thermal` 的全部场景。

[GranularBedWakeValidation](../../fabric/src/test/java/mmm/fluid/GranularBedWakeValidation.java)
使用正式全能力注册表与逐步捕获恢复，覆盖“沉积休眠 → 弱扰动 → 强扰动起动搬运 → 再次沉积休眠”。
每步检查液固联合容量、物质与内能收支，并记录起动、接触和床面受力。
该流程与薄床自由流动、时间细化共同构成正常沙床验收，具体场景参数由测试维护。
单独运行使用 `./gradlew.bat :fabric:runGranularBedValidation --offline`。

批量排查使用 [ControllerInventoryDurabilityValidation](../../fabric/src/test/java/mmm/simulation/ControllerInventoryDurabilityValidation.java) 的带种子操作序列，
每次操作后执行完整状态往返。参数依次是起始种子、种子数量、操作数和是否禁用沙粒（可省略，默认 `false`）：

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

`verifyArchitecture` 是仓库根任务，会调用 `:fabric:runArchitectureValidation`；
它不是 `:fabric:runCpuValidation` 的 `cpuValidation` 选择器。架构检查应使用上面的根任务，不要写成 `-PcpuValidation=architecture` 或 `:fabric:verifyArchitecture`。

[ArchitectureDependencyValidation](../../fabric/src/test/java/mmm/validation/ArchitectureDependencyValidation.java) 检查模块 import 方向、
纯 Java 模块包循环、持久化与运行时依赖隔离、唯一服务器运行时注册点，以及生产类 1000 行上限。超过 600 行会提示，超过 1000 行会失败。
[LiquidInventoryAuthorityValidation](../../fabric/src/test/java/mmm/validation/LiquidInventoryAuthorityValidation.java) 同时检查生产代码中的旧粒子库存写入。

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

Windows 上若 `vkCreateInstance` 返回 `-9`，可在当前 PowerShell 进程设置 `$env:VK_LOADER_DEBUG='error,warn,driver'` 后运行 headless probe，
检查加载器实际发现的驱动清单和 DLL。日志出现 `Found no drivers` 时，核对当前显卡驱动对应的 `VulkanDriverName` 注册项和 ICD 清单。
可用 `$env:VK_DRIVER_FILES` 指定清单验证已安装驱动；清单中的 `ICD.library_path` 应指向驱动 DLL 的绝对路径，必要时在 `build` 下生成清单副本。
任务继承此环境变量，验证后恢复原值。驱动发现机制与环境变量见 [Khronos 加载器文档](https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderDriverInterface.md#overriding-the-default-driver-discovery)。

GPU 校验中的 CPU 对照通过 `CpuFluidSolverBackend` 计算完整候选结果；占容几何要求时间细化时，从原始请求重新准备完整候选，再接收完成的区域状态。同一外部步推进完整配置时长，液体平流由供体库存与接收容量限流。

GPU 粒子合并使用原始 float 的精确二进制值判断体积能否守恒，着色器中的 `precise` 保证编译器保留该运算顺序。长跑比较精确库存、机械能、空间限制与展示覆盖范围。并行归约允许解析得到的舍入误差，CPU/GPU 逐格轨迹按各自数值容差比较。

设备性能使用 Vulkan timestamp 测量，调度与超时使用实际经过时间。生产 GPU 的完整功能要求见 [性能与后端规划](../roadmap/performance-and-backends.md)。

## 需要进入游戏时

当问题依赖实际画面、Minecraft 方块形状或磁盘重启过程时，准备一个可重复场景，写明操作、预期现象和需要保留的日志。发布构建、产物校验和部署步骤见 [验收与发布](../roadmap/acceptance-and-release.md)。

## 正常操作随机仿真

`./gradlew.bat :fabric:runNormalPlayFuzz '-PfuzzArgs=16,80,false,8,0,13'` 扫描 13 种几何、每种 16 个种子，每个种子 80 步；每步检查库存、能量和 capture/restore。
单个复现用 `-PfuzzArgs=replay,1,203,80`；严格子序列最小化用 `-PfuzzArgs=minimize,1,203`。`reported` 运行既有历史种子集。

抽取往返传递完整化学账本及内能，核对凝聚态库存与能量；液体位置改变时的气腔压缩功按气体与热学审计核对。宽温浮力按正的体膨胀关系推进，正常热操作继续完成机械后继。非法物性、库存或能量保持原子拒绝；正常输入中的求解失败继续作为失败反例。

分数抽取后的盘上气腔归属回归使用 `-PfuzzArgs=replay,partialdisk,1,200,49`；颗粒阻力外层收敛与完整候选时间细化回归使用 `-PfuzzArgs=replay,17,7810,37`。两条序列都保留逐动作守恒审计及恢复检查。


## 当前验证证据

### 整改后再次自审：2026-10-21

本次按实际数据流审查来源、液封、墙面冲量、真实库存内能与测试调整。确认并修复两项遗漏：新增真空原先采用全域净孔容，隔离气腔的增减会相互抵消；析出溶解的固定区域内能结算后仍执行了额外绝热功。前者改为各接收气腔的局部释放与净扩容，后者与凝聚相变共用内部转换入口。普通液流孔容未变时跳过释放量计算。

测试直接核对批量隔离气腔编辑与分开编辑的逐腔库存和 U；析出—溶解场景加入密闭气腔，核对实际总内能及恢复后的力学后继。删除两种相同实体循环相互比较的旧测试，改为 `MovingBodyCouplingValidation`：直接比较实体外功与实际 MAC 动能、库存内能增量，墙面冲量进入同一动量等式，舍入项受实际载体存储精度限制。头部气腔额外比较宏观压力与全腔压力体积均值，防止小气泡截留大量气体仍通过容量判据。

| 范围 | 实际结果与构建对应关系 |
|---|---|
| 完整热学、颗粒、容器 | `build/self-review5-conversion.log`：`thermal` 15.225 秒、`granular` 26.888 秒、`containers` 216.681 秒全部通过，CPU 总计 258.795 秒，Gradle 4 分 56 秒成功。源码摘要 `da4f5abd788589c9…`，产物摘要 `c32eaad2840a764a…`；生产物理实现提交为 `0e5b57d`，之后仅增加等价的未变孔容快路 |
| 实体、连续生产重放、液封与来源 | `build/self-review5-final.log`：三个完整领域通过，CPU 56.368 秒，Gradle 1 分 17 秒成功。源码摘要 `b09383a5f6404790…`，产物摘要 `6a15c97ca08f1487…`；与上一行生产实现相同，包含加强后的头部压力检查 |
| 最终构建与实际功热检查 | `build/self-review5-body-final.log`：`test runValidation assemble -PcpuValidation=entity-fluid --offline --no-daemon --max-workers=1` 成功，实体完整 10.215 秒，Gradle 40 秒，架构与命令适配器通过。源码摘要 `df0af9f810d13097…`，产物摘要 `5a8dbfc2f8d511ef…`；包括未变孔容快路与直接实际 U 检查 |
| 压缩气腔长重放 | `build/self-review5-compressed-soak.log`，同一编译产物直接执行 `ProductionFluidReplayValidation --compressed-soak`。沿用原场景、单步长度和固定误差预算，延长至 5,000 步；每步质量、总气容、全气腔 EOS 与内能账闭合。K 峰值/末态 162.138/4.97996 J，末态质心位移 0.0000196 m，已扣除已知热浮力功与质心不确定度的最大机械能偏差为 4,961.88 J，原固定预算 35,817.05 J |

压缩场景主气腔温度从 386.116412 K 缓慢变为 386.142447 K，250 秒仿真时间内约增加 0.026 K；机械能偏差也存在缓慢漂移。该结果支持这一时长与尺度内的宏观运动稳定，保留现有数值误差，不能推断无限时长误差有界。当前严格守恒要求仍由质量、物种、实际容量与载体内能结算守护。

独立预算沿用原预热、装量、步数和墙钟判据，未改变运行优先级。当前计时未通过；采样同时检测到磁盘清理及游戏进程在使用 CPU。下列失败完整保留，最终构建已就绪，待空闲窗口复核。

| 运行 | 控制器中位数 / 最大值 | demo 最大值 / 连续超 200 ms | 结果 |
|---|---|---|---|
| `build/self-review5-budget.log` | 17.717 / 22.898 ms | 318.591 ms / 3 步 | 未通过 |
| `build/self-review5-budget-final.log`，含未变孔容快路 | 24.788 / 30.959 ms | 355.541 ms / 8 步 | 未通过；首步 CPU 234.375 ms、墙钟 323.567 ms |

未受本次模型改动影响的完整领域复用下节结果；本次没有重复运行全量 29 领域。当前 M10 保留物理复核结果，完整步计时资格待复核。

### 第四轮整改交付：2026-10-21

第四轮交付时 M10 覆盖全部 29 个领域，采用一次完整运行及同一生产实现的化学领域替换结果。当时 `test runValidation assemble -PcpuValidation=chemistry --offline --no-daemon --max-workers=1` 成功，构建、架构与 GPU 命令适配器通过。没有运行硬件 GPU 数值场景。最新资格采用上节自审复核记录。

| 范围 | 构建与实际结果 |
|---|---|
| 全量运行 | `build/review4-delivery-full.log`，执行 `test runValidation assemble -PcpuValidation=full -PcpuValidationKeepGoing=true --continue --offline --no-daemon --max-workers=1`。CPU 379.957 秒，28/29 领域通过，唯一失败是旧化学无反应微基准增幅 5.20% 超过 5%；整条命令失败退出。源码摘要 `c6c7bea64c5be9ef…`，产物摘要 `7ccdf91a606f367a…`，包含随后提交为 `884103f` 的测试调用迁移 |
| 化学替换与最终构建 | `6fa937f`；生产实现与全量运行相同，仅替换测试。删除无反应化学的重复计时及无判定内存统计，改为有初始流速的共同库存与 MAC 一致性。`build/review4-delivery-chemistry.log`：完整化学 3.811 秒通过，酸碱反应 p95=0.233 ms；Gradle 20 秒成功。源码摘要 `74978efbc5d688e1…`，产物摘要 `665a10d61d377d55…`。其余 28 个领域复用上一行结果 |
| 真空、液封及孔流 | `gas-liquid` 完整 35.720 秒、`air-cavity`、`air-network` 均通过。覆盖原真空超容与闭合、未解析薄隙和真实半格真空的区分，以及两侧独立半液气腔的小流量受限孔流。物种与 U 继续精确核对；一阶隐式均压按固定时限的宏观压力误差评价 |
| 颗粒与连续操作 | `granular` 完整 25.202 秒通过，沉降—起动—再沉积 815 步完成；`containers` 完整 197.786 秒通过，包含原注液、固体插入、随机操作、重启及长沙床/析出流程；`region-state` 4.687 秒通过 |
| 分层、热与润湿 | `materials`、`thermal`、`thermal-extremes`、`surface-tension` 完整通过。原 RT 宏观分层位移要求保留；三组接触角扩展方差 47.7642／43.9082／13.6103 m²，亲水至疏水依次降低 |
| 长期误差 | 原 5,000 步溃坝通过库存、容量及恢复断言，末态 K=3.47422 J、中心 X=5.99983 m、RMS=0.0160421 m/s；原 1,000 步压缩平面气腔 K 峰值/末态 162.138/15.229 J、质心位移 0.000123 m，库存、全部气腔 EOS 与内能账逐步闭合，机械误差受固定空间分辨预算约束 |
| 实体与墙面交接 | `entity-fluid` 完整 6.519 秒通过；动态浮体尾段位移范围 0.000701 m，液体 K 峰值/尾段/末态 91.514/7.639/4.018 J，实际实体外功 246.181 J。`cell-inventory` 保留墙面冲量、停动转热、完全流出后的热量交接与失败原子性 |

最终产物使用 `fabric/build/cpu-validation-work/classpath.txt`，从对应工作目录以 `java -ea -Xmx512m -cp <classpath> mmm.validation.CpuValidationSuite production-live-budget` 启动两个独立 JVM，沿用原入口的预热、装量和 20 个完整步。没有在两个入口前增加预热或改变预算。

| 预算运行 | 小控制器中位数 / 最大值 | 最大 demo 单步最大值 | 连续超过 200 ms | 结果 |
|---|---|---|---|---|
| `build/review4-delivery-budget-1.log` | 13.216 / 19.436 ms | 265.472 ms | 1 | 通过 |
| `build/review4-delivery-budget-2.log` | 14.488 / 19.311 ms | 179.736 ms | 0 | 通过 |
| 完整组合内的预算 | 4.010 / 5.807 ms | 115.129 ms | 0 | 通过；JVM 已由前序领域预热 |

原标准为控制器逐步不超过 40 ms、最大 demo 不连续三步超过 200 ms；第一独立运行包含一次超 200 ms。库存、物种、真实容量和内能审计继续精确核对。压缩气腔和有限幅实体运动按实际外功、已知热浮力功及固定空间误差预算评价机械总能量；这些有限时长记录支持代表场景有界，长期通用行为仍由后续具体产品流程评价。

毫米尺度毛细断颈时刻的旧精度要求已退出本模型验收；现有 Laplace 跳跃、零张力响应、长润湿及库存恢复覆盖宏观张力职责。大文件继续按实际数据归属维护；本轮集中压力预测、曲率与墙面接触角、气体来源及控制体积、动量与热量提交，并在设计书中提供按现象定位表。

### 完整自审基线：2026-10-21

在 `4e6bfb9` 上执行以下完整入口，生产源码为 `3679a2471128686e6a37f4b7ba72d335d3d9b354`；本次审查未改生产源码或测试：

```powershell
./gradlew.bat test runModuleValidation verifyArchitecture :fabric:runCpuValidation -PcpuValidation=full -PcpuValidationKeepGoing=true --continue --max-workers=1 --offline --no-daemon
```

日志：`build/self-audit-full-20261021.log`。Gradle 总耗时 3 分 6 秒，失败退出；CPU 完整组合执行 159.490 秒，29 个领域中 18 个通过、11 个失败。每个失败领域记录首个失败，其后子场景尚未完成。`runModuleValidation` 与 `verifyArchitecture` 通过，后者扫描 501 个生产源文件。Gradle `test` 另报 `gpu-probe` 有测试源码但未发现测试；该模块采用 main 验证入口，此失败属于任务配置，未运行硬件 GPU 数值场景。

| 当前通过范围 | 结果 |
|---|---|
| CPU 完整领域 | `chemistry,thermal,air-cavity,region-manager,lifecycle,production-live-budget,free-surface,cell-inventory,liquid-joining,marker-transfer,marker-pressure,cut-cell,shape,protocol,boundary,takeover,ownership,audit` |
| 当前源码原 5,000 步溃坝 | 库存、容量及恢复断言通过；1,000–5,000 步采样动能为 562.567／432.479／277.365／203.253／291.495 J，末态中心 X=6.01042 m，RMS=0.146943 m/s |
| 原完整步预算 | 控制器最大 11.443 ms；最大 demo 最大 153.140 ms，连续超 200 ms 为 0。该入口在完整组合中执行，JVM 已由前序领域预热 |

| 首个失败所在领域 | 失败事实与审查结论 |
|---|---|
| `granular` | 细砂未在沉降时间内完成生命周期；静水浮力晚于隐式阻力加入，存在系统性速度偏差 |
| `thermal-extremes` | 10 K、10¹² Pa 夹具初始化超过定点质量范围，第二组热学步骤尚未执行 |
| `materials` | 强制 PCG 次数处于 1–32；解析静水平衡允许零迭代，日志未输出实际次数，不能据此认定超过上限 |
| `surface-tension` | 三组接触角扩展方差 16.9837／23.2160／29.5578 m²，宏观次序反向，根因待整改 |
| `air-network` | 一秒后强制到达 10⁻³ Pa 且库存恰好到环境量，与当前一阶隐式及整数通量契约不符；完整孔流后续尚未执行 |
| `gas-liquid` | 真空预测未触发原容量场景；静水支撑覆盖真实真空压力接触，属于生产缺陷 |
| `entity-fluid` | 动态玩家液体动能峰值／尾段／末态为 706.034／660.672／561.786 J，超过静止体能量阈值；须结合持续实体输入功评价，当前数据不能证明发散 |
| `controller-gas-lineage` | 末态 17 气腔，气体 64 m³std 收支精确闭合；大腔约 33.979 m³，残余约 0.0206 m³。须检查连续几何及宏观压力，节点数不足以判定库存丢失 |
| `production-replay` | 34 m³ 头部气腔条件未满足；后续流程未完成 |
| `region-state` | 冷恢复将未初始化气体变成环境库存，属于恢复副作用 |
| `containers` | seed=4、action=57 的派生压力相差一 float ULP（0.0078125 Pa），未见该次失败伴随库存或 U 丢失；后续操作未完成 |

只读审查另确认两相切格的容量分组会触发受限孔口两侧整腔来源混合。以上结果对应整改前源码，问题位置、守恒范围、液封及维护成本结论保存在[自审报告](../review/Self-Review-2026-10-21.md)。当前实现的资格采用本节最新完整构建记录。

<a id="si-units"></a>
### SI 与定点质量

单位迁移后的构建与契约记录位于 `build/mass-final-build13.log`、`mass-final-build12.log`、`mass-final-contracts29.log`。
稳定单位与库存编码以[设计书](../architecture/system-overview.md#units)为准；当前流程资格采用本节列出的共同库存主干结果。
