# 修改后的验证

先明确当前需要回答的问题与成本预期。
有九成把握的改动跳过额外验证、校验和测试；当前 solver 整合优先运行完整步生产预算。
有具体未知风险时，从下列入口选择能回答该问题的最小场景，复用同一次构建产物。

## CPU 测试怎么运行

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

显式写出的领域名称运行完整模式，即使它也出现在 `fast` 中。`production-live-budget` 和 `controller-gas-lineage` 需要显式选择，或通过 `full` 执行。Gradle 的 `check` 任务没有隐式包含所有 CPU 场景。

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

报告分别记录 JVM 预热和被测步的耗时，并保留粒子数、体积、相变操作、子步数、PCG 次数与残差。优化后用同一物理配置复测，保持原有预算和守恒检查。

`production-replay` 检查长时间注液、清空、气腔归属、相变功结算、实体响应以及后端捕获/恢复。它关注每步物质与能量收支。性能是否达标由上面的独立测试判定。

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

### 第二轮审阅整改：2026-10-21

第二轮审阅以 `07dc0ab` 为基线，报告库存、颗粒、气液、化学、热学与气腔失败。修复按职责分批构建，已通过且未受后续改动影响的流程复用相应正式产物；临时诊断用于定位，完整领域的通过状态以正式入口为准。

| 正式流程 | 实际结果与日志 |
|---|---|
| 单元库存、动量、墙面交接及编辑恢复 | `review2-shared-core.log`：`cell-inventory` 完整通过，2.678 s，覆盖共享数量分配与浮点内能分份 |
| 化学输运、抽取、热量与恢复 | `review2-functional5.log`：`chemistry` 完整通过，3.283 s |
| 气腔连通、液塞及恢复 | `review2-functional6.log`：`air-cavity` 完整通过，0.172 s |
| 气液正负压静水及连续压缩 | `review2-functional9.log`：两种静水各 1,000 步、48 批连续填充压缩完成；该次最后的孔流夹具失败，领域未整次通过 |
| 有限孔流 | 修正孔口夹具的实际连通通道后，`review2-orifice.log` 复用正式 Gradle 产物运行同一 `M5GasLiquidCouplingValidation --orifice`；59 步后压差余量为初值的 0.71% |
| 切格正常流动 | `review2-integration.log`：完整 `cut-cell` 通过，含 600 步障碍物流动，2.987 s |
| 自由面、静水、旋流、台阶和溃坝 | `review2-integration2.log`：完整 `free-surface` 通过，22.592 s，含 5,000 步溃坝及库存、容量和恢复检查 |
| 沙床生命周期 | `review2-shared-core.log` 的正式 `runGranularBedValidation` 完成 674 步，弱扰动、强起动、再沉积与恢复通过；平均完整步 14.762 ms。全部入床按原终速预算检查，随后按生产稳定时窗判定休眠 |
| 热学库存、相变、反应及恢复 | `review2-thermal-flow.log` 的完整入口依次通过定义、气腔、反应、凝聚相、所有者、接触、颗粒内能、容器及局部热浮力；该次停于随后热平流中的旧载体身份夹具，整次入口未通过 |
| 热平流、分层与自然对流 | `review2-thermal-flow-complete.log` 的 `--flow` 通过完整热平流和稳定／不稳定分层；`review2-thermal-convection.log` 的 `--convection` 通过两级网格、临界 Rayleigh 数上下的四组宏观增长及后续换热，停于最后的拥挤单元精度认证。修复后的末项另列，未重跑已通过部分 |
| 实际载体内能分配 | `review2-thermal-assignment.log`：正式 `--assignment` 通过热量所有者、热接触及拥挤单元的能量分配，保留实际 U、舍入账和原子性检查 |
| 原完整步墙钟预算 | `review2-final-budget2.log`：控制器中位数 17.645 ms、最大 27.830 ms，逐步低于 40 ms；最大 demo 中位数 191.559 ms、最大 286.903 ms，超过 200 ms 最多连续两步，低于原三步暂停阈值。两种负载均完成原 20 步采样，预算通过 |

日志均位于仓库 `build/`。最终预算直接复用 `review2-shared-core.log` 的正式 Gradle 产物与导出的完整 classpath：版本 `07f8b98` 加工作区修改，源码 SHA-256 `50b99cfc2315593d252eca71a850c628eb2f2116bb9d7209999cd2e1344c519f`，本地产物 SHA-256 `30e7129a5468394d878e7cea4fd1bbc9884f3eed534f65964af17d0ef9348404`。停止 Gradle daemon 后运行，默认优先级、`-ea -Xmx512m`、JDK 26.0.2，原输入、装量、预热、采样、子步及压力迭代额度均保留。最大 demo 采样阶段实际发生相变。

预算后修改涉及冷恢复原子性、无可选黏性定义时的热浮力温度准备，以及局部热分配的线性化处理。完整材料定义下的力学与输运主干沿用上述预算结果；后两项的热学后继按表中入口覆盖。有九成把握的未受影响部分复用已有结果，未重复整组或长流程。

最终实现提交为 `804277e`。`review2-thermal-assignment.log` 已实际重编译受影响的 model、solver、app、fabric 生产与测试源码并通过末项；随后 `review2-final-manifest.log` 以 `:fabric:runCpuValidation -PcpuValidation=list` 导出同一正式产物的元数据，源码 SHA-256 `93abba40bd5336d45298c88ac92aec55772d7a453c83f3acfed07afbad0f8dc4`，本地产物 SHA-256 `e3016c2bb53c8ee9b899d57009a0209133a76766f14d4efc2a1fa9994561c852`。`list` 仅导出选择器与构建身份，其通过状态用于说明构建与来源。

5,000 步溃坝在第 1,000–5,000 步的 RMS 速度为 0.091–0.106 m/s，动能 112–152 J，后段保持有界。静水与压力误差按共享的 `FluidPhysicalAssertions` 评价宏观水头、动能和库存质心；整数质量、物种、体积及内能交接继续使用原精确断言。

`runCpuValidation` 在执行前输出 Git 版本、源码与本地 classpath 产物 SHA-256、JVM、堆限制、断言开关、选择器和完整 classpath。源码摘要包含五个 CPU 模块的源码、资源及构建配置。增量构建按内容判定；换行转换导致的时间戳变化不能单独判断产物是否过期。

### 历史结果与适用范围

`e7d1155` 的空闲窗口预算见 `build/review-live-budget-idle.log`：默认进程优先级，控制器中位数 16.510 ms、最大 20.662 ms；最大 demo 中位数 151.959 ms、最大 216.803 ms，超过 200 ms 最多连续一步。demo 初始库存为 2,592 条、324 m³，原预热与采样数量不变。这份结果对应当时构建，本轮物理算子修改后的预算另行登记。并行游戏、浏览器负载及提升进程优先级的失败测量保留为调度诊断。

旧 `build/m10-*` 目录包括正式入口、临时探针、保存前缀、后缀重放及失败日志，供排障定位使用。整段完成资格以正式入口、实际构建和完整动作数为准；前缀拼接或临时改写算子产生的结果需沿正式入口完成后再计入。`partialdisk49-same-geometry.log` 实际完成 49 个动作。

当前模型采用一阶时间分裂和正权动量混合；机械动能差审计与实际热账分别解释。全域物理黏性、阻力及实体做功的完整热机械结算由产品路线维护。实施状态见[当前计划](../roadmap/solver-consolidation-plan.md)，更早记录见[阶段记录](../archive/progress/solver-validation-2026-10-18.md)。
<a id="si-units"></a>
### SI 与定点质量

单位迁移后的构建与契约记录位于 `build/mass-final-build13.log`、`mass-final-build12.log`、`mass-final-contracts29.log`。
稳定单位与库存编码以[设计书](../architecture/system-overview.md#units)为准；当前流程资格采用本节列出的共同库存主干结果。
