# 修改后的验证

先明确当前需要回答的问题与成本预期。
有 95% 把握的改动跳过额外验证、校验和测试；当前 solver 整合优先运行完整步生产预算。
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

显式写出的领域名称运行完整模式，即使它也出现在 `fast` 中。根项目 `check` 默认运行 19 个领域的 `fast`；该组合省略 `materials`、`surface-tension`、`gas-liquid`、`region-state`、`takeover`、`ownership`、`liquid-joining`、`thermal-extremes`、`controller-gas-lineage`、`production-live-budget`。省略的领域须显式选择，完整验收使用 `-PcpuValidation=full`。

同一领域同时选择短模式和完整模式时，完整模式优先。基线测量可加 `-PcpuValidationKeepGoing=true`，记录当前领域的首个失败后继续下一领域，最后仍以失败退出；默认在首个失败处停止。输出区分断言、参数配置与执行失败，并记录每个领域的耗时。超时或主动中止的进程须另行登记，已开始的场景不计作通过。

`CpuValidationSuite` 与直接启动的 `ProductionFluidReplayValidation` 在日志开头记录构建时的 `revision`、`sourceSHA256`，以及本次实际 classpath 产物的 `artifactSHA256`、JVM 参数和选择器。构建元数据随测试资源生成；直接 CLI 的长重放和预算沿用同一记录入口。最终全量、长跑及预算分别保存日志，并核对产物摘要；预算注明独立冷启动或同一 JVM 已运行物理场景后的热运行。某次全量失败后，在另一构建补跑失败领域得到的记录分别保留各自范围。

`cut-cell:short` 保留参数、黏性、微盒几何、CCD、容量及恢复对照，省略 600 步障碍物流动。长场景单独运行：

```powershell
./gradlew.bat :solver:runSolverValidation --args="--flow-only" --offline --no-daemon
```

相变待机械推进格的聚焦验证使用 `:fabric:runThermalValidation --args="--phase-motion"`，复用固液待处理任务、沸腾与凝结后的真实压力投影、恢复及容量拒绝断言，同时检查标记合并、快照独立性与完全汽化终态的正式存档恢复。完整热学验证仍使用 `thermal`。

气腔刷新、持久化同步、压缩功、排气及气液相变的热账交接可用 `:fabric:runThermalValidation --args="--gas-topology"` 聚焦运行，复用现有热学场景。沸腾搜索固定输入与端口复用的对照归属 `air-cavity`。

气液物种转换及其热学后继可用 `:fabric:runThermalValidation --args="--phase-species"` 聚焦运行；场景沿用完整热学入口。

热平流与分层后继使用 `:fabric:runThermalValidation --args="--flow"`；自然对流及后续换热使用 `--convection`；实际载体内能分配使用 `--assignment`。这些选择器共用完整热学入口的场景，已通过且未受改动影响的部分直接复用。

`free-surface:short` 省略长静水、5,000 步溃坝、300 步台阶流动，以及达到 `U*t/L=1` 的 20 秒多网格旋流；短入口保留 0.1 秒旋流。单独旋流入口为 `FlipFluidFreeSurfaceValidation --vortex`，记录动能与角动量相对值；三轴旋转入口 `LiquidMacroscopicMotionValidation joint-moments` 覆盖 `ω*t=0.8 rad`。其他短组合仍可能包含成本较高的领域；具体省略项由对应入口输出。

`ProductionFluidReplayValidation --controller-gas` 复用生产重放中的完整连续注液与气体库存场景，用于定位注液后继的容量、压缩功和 EOS；完整验收仍由 `production-replay` 执行全部场景。

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
    - [LiquidMacroscopicMotionValidation](../../fabric/src/test/java/mmm/fluid/LiquidMacroscopicMotionValidation.java)：完整组合运行 `joint-moments` 与 `sweep-only`，覆盖旋转及多轴、多格长平移。

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
空间矩入口 [LiquidMacroscopicMotionValidation](../../fabric/src/test/java/mmm/fluid/LiquidMacroscopicMotionValidation.java) 按 `transport-moments`、`joint-moments` 或 `common-mesh` 选择场景。`cell-inventory` 组合只运行其中的 `joint-moments`；完整宏观入口使用 `:fabric:runRemapMomentsValidation`，省略参数。
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
- 改后端状态交接：运行 `lifecycle`，再运行受影响载体的测试。库存、组成、MAC 动量及内能原始列保持精确内容或原始浮点位比较；由残余压力和已知载荷重建的凝聚相压力，按局部压力与大气压的较大尺度允许 4 个 float ULP，独立于原始列的位模式要求。

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

D01 进入前性能验收采用热运行：同一 JVM 已完成生产物理场景后，执行上述原负载与墙钟判据，代表采样步应低于 200 ms 并留出余量。独立冷启动结果单列记录，JIT 编译期间超时不阻挡本轮交付。主线程 CPU 时间用于区分计算成本与外部调度影响，每次超时和失败均按实际结果记录。

完整 `full` 组合提供同一 JVM 的热运行预算；单独复测可使用 `-PcpuValidation=production-replay,production-live-budget`，先完成正常生产重放，再执行原预算。保留原场景内部的预热、采样步数与 512 MiB 堆，记录实际 JVM 参数。

报告分别记录 JVM 预热和被测步的耗时，并保留粒子数、体积、相变操作、子步数、PCG 次数与残差。优化后用同一物理配置复测，保持原有预算和守恒检查。

`production-replay` 检查长时间注液、清空、气腔归属、相变功结算、实体响应以及后端捕获/恢复。它关注每步物质与能量收支。性能是否达标由上面的独立测试判定。
同一构建的 `ProductionFluidReplayValidation --compressed-soak` 分别运行压缩平面气腔的静止场及单次有限扰动场各 5,000 步；完整 `production-replay` 共用这两个场景，各运行 1,000 步。静止场检查固定 0.025 J 的累计能量与实际内能变化；有限扰动的初始动能须高于格中心势能观测误差，并检查撤力后动能衰减、实际 U 增量及热账。原始机械能误差、位置观测误差和有效误差上限同时报告，实际 U 与库存继续独立约束。使用 `fabric/build/cpu-validation-work/classpath.txt`、同一工作目录与 `-ea -Xmx512m`，保留日志头的构建及产物摘要。静止场守住离散平衡，有限扰动场用于评价演化中的误差与衰减。

玩家受迫扰动按本次实际峰能衡量撤力后的尾能衰减，并检查位移、尾部摆幅和速度；静止玩家保留固定小能量限制。每步墙面做功、实际 K/PE/U 变化及气体库存独立闭合。真空投影同时检查容量互补性、零气体库存、零气体 U，以及实际液体动能损失与液体内能的反向交接和审计来源。

物理验收覆盖正常尺度下的分层、液封、惯性、旋转、孔流、热对流及有限气腔压缩。气体孔流按有限时长的一阶瞬态评价；一秒真空填充的现有判据为参考压与环境库存的 0.1% 量级，具体场景和容差由测试维护。`thermal-extremes` 覆盖物性范围、正温、状态方程及可表示库存；10¹² Pa 使用小型多格连通气液腔，检查连续相变与恢复，初始压力按整数库存粒度和状态反演的宏观精度评价。

`granular` 中的 [GranularCarrierValidation](../../solver/src/test/java/mmm/fluid/GranularCarrierValidation.java)
覆盖液固阻力动量交换、耗散、压力功、浮力和跨格连续接触。
`containers` 的种子 4、54、103 保留正常操作中的薄层反冲、颗粒排水与高压时间细化回放。

`./gradlew.bat :fabric:runGranularCarrierValidation --offline --no-daemon` 单独执行完整颗粒载体校验。
加 `--args="--contacts"` 聚焦固定墙与床体连续碰撞、Coulomb 摩擦、真实支撑年龄、容量暂停、合并后联合球体几何、六轴悬殊质量碰撞的动量与质量第一矩，以及碰撞改道后的三球接触链。

`./gradlew.bat :fabric:runThermalValidation --args="--granular-energy" --offline --no-daemon` 聚焦颗粒内能列的插入、部分提取、床层转换、清空和精确热账。省去参数执行完整热学入口，沿用 `thermal` 的全部场景。

[GranularBedWakeValidation](../../fabric/src/test/java/mmm/fluid/GranularBedWakeValidation.java)
使用正式全能力注册表与逐步捕获恢复，覆盖“沉积休眠 → 弱扰动 → 强扰动起动搬运 → 再次沉积休眠”。
每步检查液固联合容量、物质与内能收支，以及独立的 U+K+PE 与外功变化，并记录起动、接触和床面受力。
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

### D01 进入前审查与验收

2026-10-23：本轮首先按生产调用链审查实现，覆盖单元库存与压力、气腔来源和孔流、导热与相变、完整候选与接管、保存和冷恢复。进入前基线 `build/d01-entry-full.log` 已通过 29/29（469.312 s），代码审查仍找到下列三项问题，因此以修复后的产物重新验收。

| 代码问题 | 修复与针对性证据 |
|---|---|
| 冻结排液失败把所有相变提议一起缩减 | 共同残量网络返回实际受阻位置，保留独立区域批次。`GasPhaseInterfaceValidation` 的隔离冻结场景在旧实现失败；修复后化学开启、关闭均能只冻结可执行区域，质量、潜热及热账保持 |
| 两气腔孔流在每次提交时遍历全网络 | `AirNetwork` 与 `GasEnergyStore` 共用原数量分配和焓流规则，局部准备、核对、提交；`GasTransferValidation` 覆盖准备失败保留原状态，以及无关气腔数量增加时端口准备量不变 |
| 大世界坐标使库存或动量落入错误存档区段 | `RegionGeometry.worldBlockPosition` 保留整数世界原点，三处持久化分区计算统一调用。既有完整热学 codec 场景在原点、普通跨区段和大正／负坐标运行，包含末格活动颗粒、床库存、连续固相、气体和 MAC；旧实现的大正坐标场景出现热账不闭合，修复后整套 `RegionRepositoryAdapterValidation` 通过 |

交叉代码复核确认：失败容量搜索给出最终残量可达集合，重试从原始库存准备；局部孔流在物质、源汇、EOS 和热账均准备成功后发布，同步更新缓存和代次。全网状态准备保留在每步和拓扑重映射职责内。

过程构建 `build/d01-final-full.log` 在 `15899b0` 编译成功，但架构检查发现 `AirNetwork` 超过行数上限；CPU 组合在颗粒领域主动中止，未计作完整通过。随后复用相同质量汇总实现，删除重复循环；中间产物从 `58bfce3` 构建，记录如下。

| 中间产物运行范围 | 结果 |
|---|---|
| 完整组合与工程检查 | `build/d01-qualified-full.log`：29/29，390.727 s；架构、GPU 命令适配器、测试与 assemble 通过。全量进程内预算为控制器 median/max 2.402/3.619 ms，demo max 64.954 ms |
| 静止与有限扰动长重放 | `build/d01-qualified-soak.log`：各 5,000 步通过。独立内能最大误差分别为 0.000458956056/0.025 J、0.00301947277/0.291949620 J；扰动末态动能 0.0922147914 J，库存升温 4.89265073/5.36399240 J |
| 独立冷 JVM 原预算 | `build/d01-qualified-budget-1.log`、`-2.log`：均失败，demo max 760.846/918.257 ms，连续超过 200 ms 为 7/17 步；输入、预热、堆大小及阈值保持 |

上述构建的 sourceSHA256 为 `16705d443fb6f9e2ef03080db7a1b8bc356c157a80335ad2df77a4c0f7995acf`，artifactSHA256 为 `4dff5267700fe51b1db55691485d0e7fdb65e1b638f870d2040559c48e960eec`。全量进程内的预算结果不足以替代独立冷启动验收。

冷启动定位保留 `build/d01-budget-gc-probe.log`、`d01-budget-jfr-probe.log` 与 `d01-budget.jfr`。GC 暂停累计约 0.305 s，最大约 10 ms；主线程采样保持 RUNNABLE，未见足以解释差距的锁等待。并发 C2 编译耗时合计约 19.208 s。恢复原修复前五个核心类的对照 `d01-budget-baseline-control.log` 同样失败（demo 887.765 ms／连续 6 步），说明问题在本轮三项修复之前已存在。减少编译线程的诊断对照曾通过，但改变了 JVM 参数，仅用于定位。后续代码优化的临时覆盖产物与 `d01-cold-*.log` 也不计为正式同产物验收；目标版本 Java 25 的对照 `d01-cold-java25-budget.log` 也未通过。当前代码优化包括共享固定几何和面索引、逐格压力与机械功工作区、按需气体存档投影、库存事务分段与常用精确数转换；冷启动问题仍在处理。

2026-10-23 最终验收按用户确认采用热运行，冷启动超时保留为运行限制。逐格容量表示、局部热曲线复用、颗粒库存按需分配及源侧压力规范化已完成；代码与架构审查发现的三项问题均已修复，可进入 D01。

| 最终产物与运行范围 | 结果 |
|---|---|
| 源码与产物 | `bda28d0f94098295d9539c21d36629503aab81a5`；sourceSHA256 `a3eb01a5077bc7742ad51d83fd68dc31336aec2ebb8cc890f1dceae3ad426fd5`；artifactSHA256 `7e6ca6c9c1d9bc2113a91e888a82e7c52203170467c154c1e1b2fb92290279a7` |
| 完整领域与构建 | `build/d01-warm-qualified-full.log`：29/29，584.086 s；`test runValidation assemble` 成功，架构扫描 499 个生产源码，GPU 命令适配器通过。容器正常操作与故障恢复矩阵 363.140 s |
| 原负载热预算 | 同一全量 JVM：控制器 median/max 4.061/6.691 ms，最大 demo 147.752 ms；20 步均低于 200 ms，峰值保留约 52 ms 余量。输入、内部预热、采样步数、40/200 ms 判据和 512 MiB 堆保持 |
| 静止与有限扰动长重放 | `build/d01-warm-qualified-soak.log`：各 5,000 步通过。静止最大独立能量误差 0.000458956056/0.025 J；扰动最大误差 0.00301947277/0.291949620 J，最大／尾段／末态动能 4.00066044/0.186123047/0.0922147914 J，库存增热 4.89265073/5.36399240 J。质量、液体体积、气体库存、EOS 与热账通过 |

上述两份最终日志的源码和实际 classpath 产物摘要一致，均使用 JDK 26.0.2、`-ea -Xmx512m`，编译目标为 Java 25。热预算反映已运行物理场景后的完整步成本；本次最终验收未再执行独立冷启动预算。硬件 GPU 数值与人工游戏画面未在本轮运行。

中间产物单列保留：`build/d01-reduced-full.log` 的 `7d55c9a` 通过 29/29（576.515 s）及构建；`d01-pressure-focused.log` 暴露未经规范化的压力残差交接错误，源侧恢复原物理压力规范化后，`d01-normalized-focused.log` 的生产重放、区域恢复和液气耦合通过。其颗粒测试仍直接读取尚未准备的派生场，随后改为先准备库存网格，最终全量已覆盖该后继。`d01-cell-capacity-budget.log`、`d01-pressure-continuation-budget.log`、`d01-sparse-stores-budget.log` 均为独立冷启动失败的临时覆盖产物，不计作最终热预算结果。

### 空气成分化验收

2026-10-23：默认环境改为 N₂/O₂/CO₂ 干气混合物，湿配方使用同一入口加入 H₂O 蒸气。气腔库存、物种及质量余量、孔流、热学、相变、完整候选和保存恢复已迁移。新增行为复用原验证入口：10,000 次微量补气与一次等总量来源逐列一致；10,000 次低流量输运实际搬运 288 nanos 的 CO₂；真实四组分异温孔流逐步保持完整库存与能量账，末态压差 0.000528257 Pa，供体／接收体温度为 858.725988／386.813349 K，闭域内能误差为 0 J。湿气相变保持非水库存，并经过完全汽化后的正式恢复及机械推进。

| 运行范围 | 结果与证据 |
|---|---|
| 完整组合 | `build/air-mixture-qualified-full.log`：26/29，830.917 s。热学发现等温孔口顺序影响整数分配，后续统一规范节点顺序；实体流体与生产预算分别出现 53.230 ms 和最大 demo 320.300 ms／连续 6 步超限。其余领域通过，包含生产连续重放、两档旋流、颗粒床层、存档与容器故障恢复；容器领域 462.281 s。该次源码摘要为 `fc89b06b7d9741a14ed4a42d5c3fe68f3494aad01638e7b87e0d6cb7b3735ddd`，产物摘要为 `c027dc4669c860edbd966c0f76d393c44851beb557348e5d79cd7534130f2d9e` |
| 最终源码与产物 | `a681bf618fc71b23448865ef7cb32d1e2e0dbae7`；sourceSHA256 `36d0bd38d7178d9f353eb42e40c7adc559ffe6de68b110688372aa1c55b29723`；artifactSHA256 `aaf616bd93f3e661c0152d32010ca0de2e075d769bc0f34daa4be4a1cdee4615`；JDK 26.0.2，`-ea -Xmx512m` |
| 最终针对性组合与构建 | `build/air-mixture-final-focused.log`：`thermal,air-network,entity-fluid,production-live-budget` 4/4，19.408 s；`runValidation assemble` 成功，架构扫描 499 个生产源码、GPU 命令适配器通过。最终代码还集中复用固定环境样本，以及单个孔流求根内不变的供体热状态；各次提交后重新准备状态 |
| 长重放 | `build/air-mixture-final-soak.log`：静止与有限扰动各 5,000 步；静止最大独立能量误差 0.000458956 J／容差 0.025 J；扰动初始 K 5.33899240 J，最大／尾段／末态 K 为 4.00066044／0.186123047／0.0922147914 J；独立能量误差最大 0.003019473 J／原容差 0.291949620 J，实际库存增热最大 4.89265073 J／原容差 5.36399240 J。体积、库存、EOS 与机械修正分项预算通过 |

| 日志（`build/`） | 控制器中位数／最大值 | 最大 demo 完整步／连续超 200 ms | 结果 |
|---|---|---|---|
| `air-mixture-final-budget-1.log` | 19.939／25.706 ms | 215.393 ms／1 步 | 独立 JVM 通过 |
| `air-mixture-final-budget-2.log` | 20.844／27.226 ms | 215.460 ms／1 步 | 独立 JVM 通过 |
| `air-mixture-final-focused.log` | 3.374／5.677 ms | 136.633 ms／0 步 | 组合内暖运行通过 |

最终聚焦组合、长重放及两次独立预算复用同一产物；前一次完整组合与最终聚焦组合分别列出实际范围。两次独立预算沿用原装量、功能、预热、步数、512 MiB 堆及控制器 40 ms／最大 demo 连续三步超过 200 ms 的暂停判据。对照下方 GPT-2 单组分基线，独立运行的控制器中位数从 15.004–17.895 ms 升至 19.939–20.844 ms，最大 demo 峰值从约 159.3 ms 升至约 215.4 ms，增加约 35%；第一轮有 1 帧、第二轮有 2 帧超 200 ms，最大连续超限均为 1 帧。硬件 GPU 数值及人工游戏场景未在本轮运行。

### GPT-2 第二轮整改验收

最终同产物完整组合 29/29、两组 5,000 步长重放及两次独立生产预算均通过。过程运行 `build/review2-integrated-full.log` 在 `1cdf133` 为 28/29；热学完整入口仍绑定旧单次孔流公式，后续还发现旧相变夹具要求干腔凝结后气体库存不变。两项改为分步解析充填与真实相变后继断言，保留压力、焓、质量和能量要求，完整热学补跑通过 `build/review2-phase-m9-complete.log`。该补跑与第一次完整组合分别保留实际范围。

针对性场景覆盖：4/8/16 腔的带热串联孔流与端口重排、16→17→16 项反应中间态、只读设备查询后的相变缓存、微量设备热输入、跨比热段与刚性换热、1/2/8 等物性气体组分的相同面积与传热、干腔首份凝结、冻结连通排液及完全封闭受限、碰撞计划过期动量保护。

完全汽化使用真实终态经过 `RegionCheckpointAssembler`、`RegionState.validate`、`RegionCheckpointRestorer` 和恢复后机械推进，覆盖原保存准入失败。该专门场景未写磁盘文件；实际文件编解码与恢复由完整 `region-state` 场景覆盖。

| 最终验收 | 结果与证据 |
|---|---|
| 固定源码与产物 | `a5c1a84d18d85fc65b054cd5246291d58393749b`；sourceSHA256 `876fb6ea4f4dab2936c604fb1c1ddb319d3fd5835d570f36e35153b4f1dfacf9`；artifactSHA256 `4187eda83be67ba74eaacc9453dc7069a2a586e64f5ebb651874df2e5fd07913`；JDK 26.0.2，`-ea -Xmx512m` |
| 完整组合与构建 | `build/review2-qualified-full.log`：`full` 29/29，313.075 s；`runValidation assemble` 成功；架构扫描 500 个生产源码、GPU 命令适配器通过；硬件 GPU 数值未运行 |
| 旋流与床层 | 两网格旋流 20 s、`U*t/L=1`：8²/16² 的动能比例 0.503115/0.679629，角动量比例 0.718216/0.834269；三轴旋转 `ω*t=0.8 rad` 通过；相变开启床层完整 686 步，82 次起动后再沉积，逐步独立能量断言通过 |
| 长重放 | `build/review2-qualified-soak.log`：静止与有限扰动各 5,000 步；静止最大独立能量误差 0.000451361 J／容差 0.025 J；扰动初始 K 5.33899240 J，最大／尾段／末态 K 为 4.00066031／0.185056837／0.0918822741 J；独立能量误差最大 0.002319434 J／原容差 0.291949620 J，实际库存增热最大 4.97555724 J／原容差 5.36399240 J；体积、库存、EOS 及机械修正分项预算通过 |

| 日志（`build/`） | 控制器中位数／最大值 | 最大 demo 完整步／连续超 200 ms | 结果 |
|---|---|---|---|
| `review2-qualified-budget-1.log` | 15.004／20.440 ms | 159.296 ms／0 步 | 独立 JVM 通过 |
| `review2-qualified-budget-2.log` | 17.895／32.851 ms | 159.327 ms／0 步 | 独立 JVM 通过 |
| `review2-qualified-full.log` | 3.365／4.611 ms | 78.374 ms／0 步 | 组合内暖运行通过 |

四份日志的源码及实际 classpath 产物摘要一致，长重放与独立预算复用同次构建。原输入、装量、步数、预热、堆大小及 40/200 ms 判据保持。

### 流体显色模式

2026-10-23：本轮围绕指示剂职责删除、逐相 pH 编码与客户端显色缓存执行针对性验证。协议和客户端表面验证通过，覆盖三个模式、精确中性色、无数据、温度范围变化及复用相同网格；化学验证包含油水同格的水相分母、无酸碱物种定义与已有输运／反应守恒流程。

| 执行范围 | 结果与日志（`build/`） |
|---|---|
| 服务端、客户端及测试源码编译 | `fluid-display-compile.log` 通过 |
| 协议与客户端表面缓存 | `fluid-display-validation.log`：`protocol` 通过，1.411 s；后续 chemistry 因新增夹具缺少必需反应定义中止，夹具已修正 |
| 化学、存档及容器完整流程 | `fluid-display-final.log`：`chemistry` 3.335 s、`region-state` 4.333 s、`containers` 216.965 s，全部通过；包含容器 100 次往返零物种漂移、WAL 故障恢复与控制器操作矩阵 |
| 相变物种／热量交接及架构 | 同次 `runThermalValidation --args="--phase-species"`、`verifyArchitecture`、`assemble` 通过；501 个生产源码；原行数提示保留 |

协议验证之后仅调整测试夹具和设置界面纵向布局；物理、网络编码及颜色映射共用此前产物。界面布局调整后由 `fluid-display-package.log` 记录最终客户端编译和打包。未执行全领域长跑及生产预算，本轮物理求解主干保持。

实际游戏画面尚未确认。可用一个四槽对照场景完成视觉检查：

1. 在启用化学和热学的创造世界建四个小型闭壳控制器区域，依次放入酸溶液、纯水与少量油、碱溶液、纯水。第四槽一侧接恒温源，运行至出现温差后暂停全部区域；酸碱试剂来自创造栏或 `/mmm reagent hcl`、`/mmm reagent naoh`。
2. 用探针打开任一控制器的“流体显色设置”，依次选择组分、pH、温度。保持区域暂停，检查即时换色与图例：酸、纯水、碱依次落在酸侧、中性绿、碱侧；油面灰色；温度模式显示冷热差异。
3. 改变温度色带范围，确认色值随范围变化且液面位置稳定。另一客户端保持不同模式，分别观察同一区域。

日志依据：切换前后比较 `/mmm status`、`/mmm chemstatus` 及控制器库存和 checkpoint 代次，显示操作保持其不变。缺少化学或热学数据时，对应模式为灰色；温度含义为凝聚相单元展示值。


### GPT-1 历史产物的综合验收

GPT-1 的 14 项整改完成。同一最终产物通过完整领域、长重放与两次独立生产预算。针对性结果：异温孔流在 dt=0.05 s 时供体平衡温度 872.552306 K，模型解析值 872.552370 K，逐步压力方向与总内能通过；`GasTransferValidation`、`AirNetworkValidation` 通过。共同气体导热场景覆盖热壁升温增压、液气成对换热、有限设备及热调度。

原湿床夹具关闭相变后运行 260 个完整后端步，最大单步独立总能量误差 2.04581×10⁻⁵ J；实际沉积步误差 4.41224×10⁻⁶ J。最终完整组合中的相变开启湿床场景完成 714 步，沉降、81 次起动及再沉积通过，每步独立总能量断言保持；平均完整步 10.530 ms、峰值 67.958 ms。

| 验收对象 | 最终产物结果 |
|---|---|
| 源码、产物与 JVM | `74c1f0eca7098e0189babd2961e48cbd8d116e12`；sourceSHA256 `497f8a6c142fa9687db2f4ecc5ad09d981d8c6786dcdcd55d8ec6962ec620c30`；artifactSHA256 `969889a975106f61ce0b844aa9f4b5d4f876eb6929c040e8d683d67a94af77c0`；JDK 26.0.2，`-ea -Xmx512m` |
| 完整领域与构建 | `build/review-gpt1-qualified-full.log`：`full` 29/29，327.419 s；`runValidation assemble` 成功；架构扫描 500 个生产源码、GPU 命令适配器通过；硬件 GPU 数值未运行 |
| 静止与有限扰动长重放 | `build/review-gpt1-qualified-soak.log`：各 5,000 步；静止末态 K 2.99662×10⁻⁷ J、最大独立能量误差 0.000451361 J／容差 0.025 J；扰动初始 K 5.33899240 J，最大／尾段／末态 K 为 4.00066031／0.185056837／0.0918822741 J；最大独立能量误差 0.002319434 J／原容差 0.291949620 J，实际库存增热最大 4.97555724 J／原容差 5.36399240 J；液体体积、库存及 EOS 断言通过 |
| 机械修正独立观测 | 扰动 5,000 步累计修正 +1.23824446 J、绝对变差 449.422660 J；空间表示交换 −0.697587422 J／几何范围 0.724915622 J，已解析交换 +1.93583188 J，已解析绝对变差 16.6008650 J；同一 PLIC 的势能变化 +0.002353670 J。绝对变差记录往返交换，结合有符号累计量与原独立总能量判定 |
| 原正式预算 | 以下两个独立 JVM 均通过；输入、装量、步数、预热、堆大小与 40/200 ms 判据保持 |

| 日志（`build/`） | 控制器中位数／最大值 | 最大 demo 完整步／连续超 200 ms | 结果 |
|---|---|---|---|
| `review-gpt1-qualified-budget-1.log` | 15.225／22.276 ms | 219.021 ms／1 步 | 通过 |
| `review-gpt1-qualified-budget-2.log` | 16.538／29.356 ms | 161.567 ms／0 步 | 通过 |
| `review-gpt1-qualified-full.log` | 2.400／3.940 ms | 158.139 ms／0 步 | 完整组合中的暖运行通过 |

四份最终日志的源码与实际 classpath 产物摘要一致；长重放与独立预算复用完整组合的编译产物。第一轮独立预算存在一次 219.021 ms 尖峰，连续超限未达到原三步暂停条件。气体导热接入后静止压缩场存在温度均衡过程，静止微量运动与总能量仍在原容差内。

本轮过程记录：`build/review-gpt1-full.log` 为 26/29；`build/review-gpt1-final-full.log` 在 `ae1370b` 上为 27/29，剩余失败为颗粒路径夹具的旧断言和几何受限析出。后者由先准备实际固体布局、按整数批次准入再移交动量修复，原 seed 54 的完整操作短重放通过。最终资格采用本节另行登记的固定产物完整运行。

### 第五轮历史产物的综合验收

同一最终产物通过 `full` 29/29、静止与有限扰动各 5,000 步重放，以及两个独立 JVM 的原生产预算。最大 demo 的两次峰值为 153.537 ms 和 162.791 ms，距 200 ms 分别保留 46.463 ms 和 37.209 ms；两轮均无超 200 ms 步。输入、装量、预热、步数、JVM 堆大小和原预算阈值保持。

| 验收对象 | 最近综合产物结果 |
|---|---|
| 源码、产物与 JVM | `cb925f9cc40d6ad9da966541a02295798c0ba98f`；sourceSHA256 `53376fedda882374c33db213dff9bf8aa7b6887be12f9288a3d3eeaea0123eaa`；artifactSHA256 `16b140a8c71ecfd34a3d3313eb6e7c647f0ee77035443caf9fa1a1483cd204a9`；JDK 26.0.2，`-ea -Xmx512m` |
| 完整 CPU 领域 | `build/review5-final-integrated-full.log`，同次 `full` 29/29，313.375 s；含热对流、惯性旋转、墙面交接、气体来源与液封、全部容器随机操作、原整桶及单桶沉积后继；容器领域 180.926 s |
| 构建、架构与 GPU 命令适配器 | 同次 `test runValidation assemble` 成功；架构扫描 501 个生产源码，GPU 命令适配器通过；硬件 GPU 数值未运行 |
| 静止与有限扰动长重放 | `build/review5-final-integrated-soak.log`，各 5,000 步通过；静止 K 与原始能量误差为 0；有限扰动初始 K 5.33899240 J，最大／尾段／末态 K 为 4.00066031／0.183101864／0.0907492362 J，独立能量误差最大 0.000294805105 J／容差 0.291949620 J，实际库存增热最大 4.90988971 J／容差 5.36399240 J；库存与 EOS 保持 |
| 独立 JVM 正式预算 | 两次通过，详见下表；完整步包含捕获输入、私有候选恢复、全部物理计算与候选接管 |

| 日志 | 控制器中位数／最大值 | 最大 demo 完整步／连续超 200 ms | 结果 |
|---|---|---|---|
| `review5-immutable-geometry-budget.log` | 15.725／19.874 ms | 153.537 ms／0 步 | 通过 |
| `review5-immutable-geometry-budget-2.log` | 15.747／18.417 ms | 162.791 ms／0 步 | 通过 |
| `review5-final-integrated-full.log` | 2.060／2.379 ms | 79.616 ms／0 步 | 完整组合中的暖运行通过 |

四份最终日志的源码与 classpath 产物摘要完全一致。第一轮预算由 Gradle 启动，`keepGoing=false`；第二轮与完整组合为直接 JVM，`keepGoing=true`，长重放采用其独立入口。主线程 CPU 时间随每步墙钟一并记录；全量暖运行与独立预算分别登记。

长期静止场保持 V=32 m³、Pg=165591.223673 Pa、T=386.116412384 K；有限扰动场撤去输入后持续衰减。末态质心变化约 1.67×10⁻⁶ m；几何质心对应的势能观测不确定性 72.2883131 J 保留为诊断，独立能量容差仍为原 0.291949620 J。

测试迁移删除旧保留路线、粒子池及 `solid` 派生列的装配代码；新增量模型补充零质量出流携带物种参考能的回归断言，固定几何快照补充候选替壳隔离断言。物种、质量、体积、能量、恢复及宏观物理断言保留，原 40/200 ms 生产要求保持。

### 既往失败与诊断范围

以下记录对应第五轮整改前或过程中的产物，用于定位反例。最终验收采用上节单独登记的完整运行与产物摘要。

| 记录 | 实际范围与限制 |
|---|---|
| `build/review5-qualified-full.log`、`review5-qualified-soak.log`、`review5-qualified-budget-{1,2}.log` | `d3356a2` 的完整组合 29/29、两组 5,000 步通过；独立预算最大 demo 分别 281.761 ms／连续 3 步、501.258 ms／连续 4 步，均失败，后续整链成本重构取代该资格 |
| `build/review5-shared-preparation-build-budget.log` | 仅减少重复准备的 `24f0406` 仍失败，最大 demo 419.473 ms、连续超时 5 步 |
| `build/review5-face-increment-budget.log`、`review5-face-increment-profile.log` | 中间面增量模型在最大 demo 第九步失败；独立体积分份可产生零质量、正物种出流，参考能失配。最终由实际质量→物种→参考能的共同数量链修复 |
| `build/review5-linear-flux-budget.log`、`review5-linear-flux-budget-{1,2}.log` | `0fb5037` 的线性散度校正已跑通连续流程；构建内预算峰值 266.372 ms，独立运行一次控制器 62.493 ms 失败，另次 demo 峰值 218.211 ms，余量不足；随后完成固定几何与区域化学职责替换 |
| `build/review5-final-full.log` | `7653ffa8` 的完整组合为 27/29，整桶安息角及连续注液第 544 步伪压缩功失败；后续由颗粒支撑/下坡入床与气腔实际机械模式职责链修复 |
| `build/drift-capacity-full.log` | `f299f56` 的一次 `full` 为 28/29，`production-replay` 在压缩扰动场景失败；整条入口失败 |
| `build/drift-capacity-final.log` | 另一构建只补跑完整 `production-replay`；该领域通过，未运行一次新的 `full` |
| `build/drift-capacity-final-soak.log` | 静止场 5,000 步保持恒等状态；原微扰初始 K 约 0.000021356 J，远低于约 0.171570 J 的位置观测误差，有限幅运动的检验能力不足；日志缺少独立产物摘要 |
| `build/self-review5-compressed-soak.log` | 旧 5,000 步扰动场记录最大未解释机械能 4,961.88 J、宽松预算 35,817.05 J 和约 0.026 K 温漂；质量与 U 账闭合，机械能误差仍需整改 |
| `build/self-review5-conversion.log`、`self-review5-final.log`、`self-review5-body-final.log` | 分别覆盖所选热学、颗粒、容器、实体及生产流程；构建摘要各自不同，保留各日志实际范围 |
| `build/review4-delivery-full.log` 与 `review4-delivery-chemistry.log` | 前者 `full` 为 28/29，化学微基准失败；后者改写该测试后单独通过化学领域，未形成同一最终产物的 29/29 全量结果 |
| `build/self-audit-full-20261021.log` | 整改前完整组合 18/29，11 个领域失败，后续子场景未完成；当时的自审记录保留在 Git 历史 |

第五轮审阅所涉旧预算的全部运行结果如下。部分直接 CLI 日志缺少构建和产物摘要，按各自原始输出保留范围。

| 历史日志 | 控制器最大完整步 | 最大 demo 完整步／连续超 200 ms | 结果 |
|---|---|---|---|
| `build/drift-capacity-final-budget-1.log` | 19.671 ms | 206.511 ms／1 步 | 通过 |
| `build/drift-capacity-final-budget-2.log` | 19.620 ms | 235.318 ms／4 步 | 失败 |
| `build/drift-capacity-idle-budget-1.log` | 17.668 ms | 200.773 ms／1 步 | 通过 |
| `build/drift-capacity-idle-budget-2.log` | 55.039 ms | 未运行 | 失败 |
| `build/drift-capacity-idle-budget-3.log` | 23.805 ms | 258.485 ms／2 步 | 通过 |

这五次运行含两次硬失败，已有通过记录的耗时余量不足。控制器旧日志还存在输出与断言步号错位，本轮已统一为完成步编号；最终预算采用新记录入口。

<a id="si-units"></a>
### SI 与定点质量

单位迁移的历史记录位于 `build/mass-final-build13.log`、`mass-final-build12.log`、`mass-final-contracts29.log`。稳定单位与库存编码以[设计书](../architecture/system-overview.md#units)为准；当前流程资格采用本节的最终验收记录。
