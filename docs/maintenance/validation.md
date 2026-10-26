# 修改后的验证

先明确当前需要回答的问题与成本预期。
有 95% 把握的改动跳过额外验证、校验和测试；物理主干改动按范围选择完整步生产预算，展示改动分别记录快照与客户端成本。
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

<a id="fluid-presentation"></a>
## 展示几何与光影接入

阶段范围与完成条件见实施计划 D01–D03；本节维护执行方法、成本口径及实际证据。
有 95% 把握的改动跳过额外检查，针对具体未知风险选择已有入口，同一源码状态复用构建产物。

### 自动化与离线成本

- D01 沿 `protocol` 检查新几何字段、同代接收、迟到帧和移除游标，沿 `materials` 覆盖实际几何输入。
- D02／D03 复用相关几何测试，按改动覆盖薄层、格内液滴、斜切单元、多材料、共享面、朝向、有限坐标和重复重建一致性。
- 重构绑死旧阈值、增厚或网格形状的断言；新增常态测试对应新增失败类型。
- `presentation` 分列快照与网格时间、分配量；编码字节数、客户端排序、上传和 GPU 绘制按实际入口补充测量。

现有选择器命令：

```powershell
./gradlew.bat :fabric:runCpuValidation '-PcpuValidation=protocol,materials' --offline --no-daemon
./gradlew.bat :fabric:runCompositeBenchmark -PbenchmarkScene=presentation --offline --no-daemon
```

现有离线 benchmark 的范围见下节，GPU 绘制和游戏帧耗时单独记录。
原物理步 40／200 ms 预算保持原口径；展示快照、客户端 CPU 与 GPU 成本分别评价。
客户端数值预算在实现时依据目标硬件、分辨率、包预设及代表负载确定，记录后使用同一条件判定。

### 2026-10-23 展示实现记录

`build/presentation/final.log` 的 `protocol,materials` 通过，构建产物摘要为 `250f38568bbd9b82e684e5d765c78a73c56d1960cee2361ddf6de8d1bd22ddbe`。覆盖几何编码、迟到／移除、接齐发布、缓存与显色、真实薄层、斜切体积／法向、共享界面及重复构建。9216 格斜切传输夹具含 73728 片，编码 2819182 bytes、6 个分片，单次冷编码约 66 ms；该值用于规模记录。

同次 `presentation` 使用既有大型静态场景、5 次预热和 10 次采样，原始数据在 `build/composite-profile/presentation.csv`。四类展示快照平均 14.681 ms，网格平均 4.537 ms、最大 7.969 ms，网格分配约 3.56 MiB。该场景包含 2592 个有液单元；捕获和恢复另列。网格更新以此代表负载 16 ms 为客户端 CPU 目标，暂停与同几何帧复用网格。上传和提交已具备运行日志；GPU 执行时间与游戏帧预算待实际硬件测量。

Iris 接口依据为本地 `origin/26.2` 的 Iris 1.11.2／Sodium 0.9.1 源码。普通渲染、Photon v1.4、Complementary Unbound r5.9.3 的加载、表面、阴影、液下与资源重载均标记为**未实测**。源码接入和 CPU 测试不计为视觉通过。

### 单一人工场景

使用同一玻璃控制器内腔，保持加载，通过现有材料容器加入水、油、汞与少量颗粒。
内腔取 4×4×4 方块，底部先加入一桶汞、四桶水和一桶油，再加入少量细沙；在控制器处暂停观察，沿玻璃侧面接近液体界面。部署 `fabric/build/libs/mmm-fabric-0.17.0.jar`，日志读取游戏目录 `logs/latest.log` 中 `MMM presentation CPU per draw` 与 `MMM Iris 26.2` 记录。Photon 关闭 `WATER_DISPLACEMENT`；Complementary 关闭 `WAVING_WATER_VERTEX`，保留法线波纹。

1. 观察静止分层及玻璃后的界面，随后用一次注液制造扰动，查看液面、材料接触与颗粒。
2. 暂停区域，切换组分、pH、温度及色带范围，观察立即换色、无数据颜色与正常遮挡。
3. 将相机移入、移出水体并靠近玻璃及材料界面，观察内外可见性和浸没状态。
4. 在同一场景切换普通渲染、Photon v1.4、Complementary Unbound r5.9.3，观察深度、阴影与水下效果；记录关闭附加几何波动等实际设置。
5. 对自动化尚未覆盖的资源生命周期执行重载、区域移除和重进，观察旧网格及 GPU 资源的释放与重建。

预期为界面清晰、透明叠加合理、液面位置与相机介质判断一致、色带可辨认，资源变化后保持可用。
多层吸收与折射按已声明近似评价。画面证据使用同场景截图或录像，日志记录版本、区域身份、资源重建及异常原因；具体诊断字段随实际入口登记。

### 主要接入对象与证据

| 对象 | 本地参考 | 当前证据 |
|---|---|---|
| Iris 26.2 | `D:\Proj\260817-MinecraftMod\Iris` 的 `origin/26.2`，检查时提交 `8f3a7a35d` | 2026-10-23 源码读取：Minecraft 26.2、Iris 1.11.2、Sodium 0.9.1；具备公开管线映射，公开阴影注册／回调接口缺席 |
| Photon v1.4 | `D:\Proj\260817-MinecraftMod\shaderpacks\photon_v1.4` | 2026-10-23 静态检查材料映射、顶点波动、颜色处理、深度与水下流程 |
| Complementary Unbound r5.9.3 | `D:\Proj\260817-MinecraftMod\shaderpacks\ComplementaryUnbound_r5.9.3` | 2026-10-23 静态检查水分类、顶点属性及依赖方块内高度的附加波动 |

以上为源码接入依据，D01–D03 的游戏加载、视觉及成本结果尚待实现后记录。旧 Euphoria 组合与其他本地包作为兼容差异参考。
每次实际运行登记 Mod 产物摘要、Minecraft／Fabric／Iris／Sodium 版本、光影包版本或摘要、资源包、包设置、硬件、驱动、分辨率与负载。
分别报告加载、表面、透明叠加、诊断显色、液下、阴影、重载及专项效果的结果，注明已观察的近似和限制。
包侧改动登记用途、对应版本和可复现补丁位置；声明兼容范围依据实际结果。

<a id="流体显色模式"></a>
### 流体显色模式

组分、逐相 pH、凝聚相温度沿同一已接受快照显示；暂停换色、固定温度范围及无数据颜色的人工观察合并到上述场景。
光影包对水颜色的重算可能改变诊断读数，D02 的诊断着色和 D03 的视觉结果在本节登记。

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

当前整块迁移先测耗时，再集中验证物理结果。完整步目标为同一现有场景争取十倍提速和 `64³`、20 Hz；以下分项入口用于定位成本，完整功能与完整步预算在生产接入后判定：

```powershell
./gradlew.bat :gpu-kernel:runGpuRegionStorageBenchmark --offline --no-daemon
./gradlew.bat :gpu-kernel:runGpuResidentPressureBenchmark -PgpuResidentPressureGrid=64 -PgpuPressureSamples=10 --offline --no-daemon
./gradlew.bat :gpu-kernel:runGpuLiquidGeometryBenchmark -PgpuProbeDevice=0 -PgpuGeometrySamples=10 -PgpuGeometryProjection=true -PgpuGeometryGrids=64 --offline --no-daemon
./gradlew.bat :gpu-kernel:runGpuDyadicSumBenchmark -PgpuProbeDevice=0 -PgpuDyadicSamples=10 --offline --no-daemon
./gradlew.bat :gpu-kernel:runGpuLiquidGatherBenchmark -PgpuProbeDevice=0 -PgpuGatherSamples=10 --offline --no-daemon
./gradlew.bat :gpu-kernel:runGpuLiquidCapacityBenchmark -PgpuCapacityGrids=64 -PgpuCapacityScene=all -PgpuCapacityRounds=512 -PgpuCapacityControl=true -PgpuCapacityFlowRatio=true --offline --no-daemon
```

`runGpuLiquidGatherBenchmark` 用 `gpuGatherSamples` 设置采样次数，固定运行32³和64³。CPU 初始化一次已接受单元体积预算，计时期间每次从同一设备捕获准备候选，执行材料路线、数量分配、库存归并、MAC 搬运、耗散热写入与精确账归总后释放候选；最后单独提交一次并抽查 checkpoint。它测量固定路线的阶段组合。

`runGpuLiquidCapacityBenchmark` 接受 `full/dam/all` 场景，`gpuCapacityControl` 与 `gpuCapacityFlowRatio` 只在计时后读取控制状态和实际运输量。成本同时报告可行状态与保留流量，删除全部运动的结果按失败处理。

`runGpuLiquidGeometryBenchmark` 的 `gpuGeometryProjection=false` 测库存派生几何；设为 `true` 追加重力、物理 CSR、压力、格面投影与 PLIC 面扫掠偏好。`gpuGeometryGrids` 接受逗号分隔格数，`gpuGeometrySamples` 控制采样次数，固定先预热5次。投影模式每次从零压力开始，并计入代次复制、命令录制、各批执行、状态回读和接管。

前者计设备代次复制、极小写入与提交，保留一个旧捕获；后者在初始化时上传固定制造矩阵，后续每次从相同零压力求解，计入命令录制、候选复制、全部迭代、状态回读和提交。两者均未覆盖流体几何、输运和热化学。完整物理验证集中后置，下面的既有验证入口按实际风险选取。


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

### B00 共同恢复判定

2026-10-23：C02/B00统一编译成功；修正 ownership WAL 阶段推进的原子替换后，复用已有产物定点编译 `OwnershipFiles` 并运行 `M6OwnershipValidation`，23项场景全部通过。覆盖正常接管／释放、HEAD前后与世界修改中途故障、玩家方块保护、损坏证据、目标checksum／阶段不匹配、幂等恢复。日志 `build/gas-source/b00-ownership.log`。

`ContainerTransferStoreValidation` 的共同恢复判定、WAL编码与前态／后态分类场景通过；随后快速注液暴露C05对零质量几何尾项求温度的问题，已改为按实际非零物质量建立传质任务。定点 `--rapid-pour` 原24次注液与重启流程通过，日志 `build/gas-source/core-2.log`。完整控制器操作矩阵复用此前B08通过记录。
### C05 气液传质与实体几何首切片

2026-10-23：统一 `:fabric:compileTestJava --offline --no-daemon` 成功，日志 `build/gas-source/c05-checked-compile.log`。复用该 classpath 的 `M5GasLiquidCouplingValidation --mass-transfer`、`MovingBodyCouplingValidation`、`EntityFluidWiringValidation` 和 `M7RegionChemistryPersistenceValidation` 全部通过，日志分别为 `build/gas-source/checked-0.log` 至 `checked-3.log`。传质覆盖密闭溶解降压、抽空脱气、环境吸收／加热脱气、亚微摩尔恢复和零预算延期恢复。实体覆盖实际 PLIC 排水与静水压力面积分、现有接触冲量／功／热，以及实体身份表存盘。

定义和接触面积入口此前已通过（`M7ChemistryValidation --fast`、`M5GasLiquidCouplingValidation --contacts`）；`FluidRegionLifecycleValidation` 包含慢传质保持热轴活动的场景并通过。实体完整移动占容与压力联立的后续结果见下节。

`M5GasLiquidCouplingValidation --mass-transfer-cost`：9,216 格、2,592 份库存、480 个接触，20 次预热与20次计时，阶段平均 5.970 ms、每步分配 5,982,168 bytes。日志 `build/gas-source/c05-cost.log`；这是传质阶段成本，完整步生产预算沿既有证据。

传质温度敏感性按 `|ΔT|≤|ΔE|/C` 和 `|ΔH/H|≈|B|ΔT/T²` 评价。以既有机械误差基准 0.025 J、此用例62.5 kg水及4180 J/(kg·K)估算，温度影响约9.57×10⁻⁸ K，293.15 K下Henry相对影响约2.67×10⁻⁹。这是基准误差的量纲上界估算；薄层应按实际较小热容量评价。本轮实际热机械场景的温升结果见下节。
### B08 创造气体操作

2026-10-23：`:fabric:runCpuValidation -PcpuValidation=containers :fabric:assemble --offline --no-daemon` 成功；containers 用时 286.7 秒，包含气体标准体积 WAL 保存恢复和全部现有故障提交时点的重试检查。日志：`build/gas-source/validation.log`。

复用构建 classpath 执行 `mmm.thermal.GasTransferValidation --external` 通过，覆盖当前 PLIC 的湿／干位置、指定温度组分注入、非法输入原子拒绝、指定组分抽取、混合气抽空及环境配方再注入，核对库存与内能源汇。日志：`build/gas-source/external.log`。此项使用修正后的 0.5 m 生产单元夹具；未追加游戏内操作观察。


**99% 的所谓“验收证据”根本不需要做。本节留空即可**





### C02 完整实体耦合、热机械收支与 C04 有限换热器

2026-10-23：复用统一编译产物并定点编译本轮修改，完整实体目标表的进入、移动、离开、容量失败源状态不变、超额整份拒绝、一次响应与 checkpoint 后继通过。`FluidDomainStateParityValidation`、`MovingBodyCouplingValidation` 及原生输入适配覆盖这些职责；本轮采用外部规定运动，有限质量动态平移尚未实现。

`build/gas-source/mechanical-thermal.log` 的实际载体温升：

| 场景 | 动能前→后（J） | 载体 ΔU（J） | 最大绝对温升（K） |
|---|---:|---:|---:|
| 六步封闭水剪切 | 62.5000005877 → 30.8155968333 | 31.6844011545 | 9.52439620505×10⁻⁷ |
| 六步封闭液固滑移 | 2.5390625 → 0.0395623303272 | 2.49949917898 | 3.82951270694×10⁻⁸ |

水剪切的局部应力耗散为 5.84410061772×10⁻⁵ J，其余机械交换为 31.6843427135 J，包含压力与粗网格离散交换，按独立观察项记录。`build/gas-source/body-coupling.log` 的持续规定运动覆盖正向、静止、反向、静止四段，累计外功 0.0143319285 J，实际 ΔU=0.416786136 J，最大绝对温升 2.53766927×10⁻⁷ K；每步实际 K/U/W 闭合。ΔU 同时包含初始运动衰减，累计外功与温升分别报告。

`build/gas-source/device-codec.log` 的 `RegionRepositoryAdapterValidation` 通过，包含有限设备 place/remove/drop 的 typed WAL 往返、完整质量与内能、PREPARED/base 丢弃、HEAD target 后世界／物品部分完成恢复、checksum 不符与第三种状态拒绝、不可用成员等待。`build/gas-source/integrated-0.log` 的有限端口换热通过，覆盖实际几何端口载体及联合能量提交。C04 原生游戏安装、查询、拆卸和破坏恢复尚未在游戏内运行；D03 光影视觉仍未验收。

`build/gas-source/solute-freeze-fixed.log` 的 `--solute-freeze` 通过：16 批冻结后 CO₂=1000 保留液相，质量 625000000000 units 不变，ΔU=−1093·2⁻³⁷ J（约 −7.95×10⁻⁹ J），精确对应存储舍入账。气液传质后的持续运行按全气液物种和质量守恒评价。旧 `build/gas-source/solute-freeze.log` 保存调整断言前的失败记录，当时误要求浮点内能逐位不变；修正后按内能与舍入账精确对应判定。

最终原负载热运行见 `build/gas-source/entity-budget-optimized.log`：同一 JVM 先执行原 `controller-gas` 1040 步，再运行原 20+20 实体预算，保留原场景与 40／200 ms 判据。

| 场景 | 完整步均值 / p95 / 最大（ms） | 调用线程分配（MiB/步） | 判定 |
|---|---:|---:|---|
| controller-entity | 9.270 / 11.237 / 13.258 | 6.94 | 原逐步40 ms通过 |
| demo-entity | 107.167 / 139.063 / 159.179 | 118.05 | 原连续三步200 ms通过 |

实际 `artifactSHA256=f56aea4dde326419ddea368f674df7e59381954cecee6384a56553f18fbbe918`。本轮复用产物定点编译，日志构建元数据的旧 revision/source 摘要保留原含义，不能代表本次源码状态。`contact-optimized.log`、`mass-transfer-optimized.log` 亦通过。区域 tick 的完整成本覆盖输入捕获、求解、原生实体响应、有限设备交换及热观察，最后统一记录和 pacing。

此前独立冷启动小控制器首次69.76 ms、诊断44.98 ms（CPU15.625 ms，sample0）超过40 ms；优化前 `hot-2` 的 controller均9.739 ms、最大15.136 ms通过，demo连续三步200 ms失败。这些记录保留为相应运行范围；最终热运行按上述优化后原场景结果判定。

### 公共转移与有限容器交接

2026-10-23：`public-api-compile.log` 编译通过；`public-api-validation.log` 的 `ContainerTransferStoreValidation --public-transfers` 覆盖操作 UUID、actor 实例／代次、回执失败保留 WAL、恢复一次交接、重启及最近回执替换。`bounded-containers.log` 的 M6 12 项通过；`carried-granular-energy.log` 的 M9 `--granular-energy` 通过，包括从加热颗粒提取实际 U 后重新注入。

`container-transfer-complete.log` 前 20 项通过，后续旧操作矩阵在 `cold acid-base precipitation` 失败：1 K 的 Henry 温度外推溢出。该记录保留为失败结果，修复使用原 40 步冷态场景的短入口 `ContainerTransferStoreValidation --cold-acid-base`，不据此声称完整矩阵通过。

温区修复后的 `delivery-final-compile.log` 编译通过，`cold-acid-base-fixed.log` 原 40 步通过；`henry-temperature-range-fixed.log` 与 `henry-registry.log` 分别覆盖范围外库存／内能／源汇保持、无时间积压及定义存档往返。`henry-temperature-range.log` 是新测试误比较快照对象身份的失败记录；修正后精确比较库存条目和源汇内容。`water-contact-fire.log` 与 `entity-fire-wiring.log` 通过实际水／油／混层接触和实体响应适配，原生实体燃烧效果尚待游戏内观察。

2026-10-23：控制器比较器的 `controller-comparator-lifecycle.log` 通过固定容量、部分填充、暂停、不可用、卸载／恢复与移除；`product-delivery-package.log` 打包及架构检查通过。

### 耕地湿润

`farmland-water-contact.log` 的 `MovingBodyCouplingValidation --water-contact` 通过实际水相足额提取、油层排除、重复接触面去重、剂量不足原子拒绝及质量／物种／内能账。`farmland-recovery.log` 的 `RegionRepositoryAdapterValidation --farmland` 通过批成员的持久往返、提交前后恢复、第三态与不可读等待。`farmland-delivery-build.log` 的 `:fabric:compileTestJava :fabric:assemble verifyArchitecture --offline --no-daemon` 通过，538个生产源文件完成架构扫描。

原生游戏灌溉与跨重启尚待观察。单一场景：在运行中的控制器内部放一块干耕地，让一侧接触MMM水相，保持周围无原版水块且无降雨；观察湿度上升与区域水量对应减少（每档1 L），暂停后停止补水，退出重进后保持已交接状态。对照同场景的油侧接触应保持干燥。异常日志入口为 `Farmland transfer`／`Farmland WAL`；自动化证据仅覆盖上述核心与恢复短流程。

### 分片渐进加载

2026-10-23：`RegionRepositoryAdapterValidation --section-loading` 通过。复用跨 section 区域，覆盖 HEAD 绑定读取、混代与槽复用拒绝、缺片保留 metadata、同 tick 读取额度、取消后重新加载、全片齐备才采用以及文件修复后恢复原库存。日志 `build/gas-source/section-loading-validation.log`。

统一 `:fabric:compileTestJava :fabric:assemble verifyArchitecture` 通过，日志 `build/gas-source/section-loading-build.log`。求解主干与跨片通量未改；本轮未重复物理预算。最终完整组装仍集中执行，独立活动组休眠与大区域恢复成本尚未交付。

同产物完整 `RegionRepositoryAdapterValidation` 通过，包含上述分片专项、各布局编解码、发布故障、删除与隔离、上一代显式恢复和损坏文件拒绝。日志 `build/gas-source/section-persistence-validation.log`。首次运行发现化学缺片的旧发现断言，已迁移为保留元数据、严格读取拒绝；未完成删除事务继续沿原完整恢复屏障处理。

### 物理活动组

2026-10-23：`PhysicalActivityGroupsValidation` 和 `PhysicalActivityThermalValidation` 通过，统一选择器为 `activity-groups`。覆盖双舱休眠、局部注入后的真实流动、另一舱库存与 MAC 原值保持、局部升温后的实际导热、冷舱 raw U 保持、总热账闭合、候选交接、只读观察、设备连接和拆墙合组。日志 `build/gas-source/activity-groups.log`、`activity-thermal.log`。首轮分别发现恢复统计遗漏和夹具采用错误格长，修复后通过。

`controller-gas-lineage,production-live-budget` 同 JVM 通过，日志 `build/gas-source/activity-production-budget-final.log`。小控制器20步均低于40 ms；溃坝20步最大223.639 ms，连续超过200 ms为1步，低于3步暂停条件。首次预算回归的旧断言遗漏 CO₂ 传质导致的液体质量变化，已保留无传质时的质量不变要求并区分真实跨相交换；原失败记录在 `activity-production-budget.log`。复用 classpath 的构建元数据仍显示先前源码标识，日志中的实际 artifactSHA256 对应运行产物。

活动组尚未进行原生游戏观察。世界区块缺失时的组级暂停与恢复证据见下节；CPU 冷恢复仍读取同代完整区域库存。

活动组统一编译、打包与架构检查通过，日志 `build/gas-source/activity-package.log`；539个生产源码文件完成架构扫描。

### 显式容器接口

2026-10-23：ShellFluidQueryValidation 通过，覆盖六向正面、背面／侧面／缺方向拒绝、跨维度、多区域归属、角落和未加载区域拒绝；目标查询保持库存及生命周期不变。日志 uild/gas-source/port-targets.log。首次编译发现Minecraft 26.2朝向属性类型已采用 EnumProperty<Direction>，修正后统一编译、打包与架构检查通过，540个生产源码文件完成扫描，日志 uild/gas-source/port-package-final.log。

原生游戏待观察：在已启用双格内腔侧壁安装蓝框朝外的容器接口，玩家从正面倒入并取回一桶，再用朝向接口的发射器完成相同操作。预期容器和区域库存对应增减，背面与普通围墙不转移；/mmm status 显示实际库存变化。模型显示和游戏交互尚未实测。

活动组气体慢流补充：PhysicalActivityGroupsValidation 同产物通过，日志 uild/gas-source/activity-gas-wake.log。双舱中的微量通气口单步尚未产生库存变化时，有可表示平衡迁移量的有限气腔所属组持续运行，另一舱休眠；关闭通气口后允许两组休眠，库存与物质审计保持。最终统一编译、打包和架构检查通过，日志 uild/gas-source/port-and-gas-package.log，包含更新后的快速入门资源。

### 原版静水池与活动组区块暂停

2026-10-23：统一 `:fabric:compileTestJava :fabric:assemble verifyArchitecture --offline --no-daemon` 通过，543个生产源码完成架构扫描，日志 `build/gas-source/pool-delivery-build.log`。产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`。

`pool-functional-fixed.log` 中 `activity-groups,boundary,ownership` 通过。覆盖缺世界输入时独立舱继续流动，等待舱液体、颗粒、气体、MAC 动量与原始内能保持，候选交接和重新加载；池体扫描覆盖天然完整底壁、水平静水、敞口及尺寸预算；归还覆盖实际水量、余量、热水保留、HEAD 前后和部分世界输出中断恢复，以及空池解除归属时保持空气。

最终 `region-state,ownership` 组合通过，日志 `build/gas-source/pool-persistence-final.log`，归属事务共27项场景。存档包含池体几何与已归还位置、完整区域分片恢复和能力关闭后的保留锁定。首次活动组测试夹具用了1 m格长，修正为颗粒要求的0.5 m，原记录为 `pool-functional.log`；首次存档组合因通用布局错误遮住具体热能力锁定原因失败，调整检查顺序后通过。编译中发现的归还操作受检异常处理已修正，最终交付构建通过。

相同求解器产物的原负载热预算 `controller-gas-lineage,production-live-budget` 通过，日志 `build/gas-source/pool-production-budget.log`。小控制器20步最大7.283 ms，低于40 ms；最大溃坝20步最大271.535 ms，连续超过200 ms为2步，低于3步暂停条件。世界可用性输入与磁盘恢复未计入此纯生产计算场景的实测范围。

原生池体交互、画面及跨游戏重启尚未实测。单一场景：建一池由完整实心方块围住、顶部敞开的2×2静水，管理员用探针正常右击预览并潜行确认；用容器取走少量水，再对池底预览、确认归还，退出并重进。预期完整常温静止纯水回到原版，零头仍可装取，取空后再次确认结束接管，水量不恢复为初始装量。用 `/mmm status` 对照剩余库存；异常日志检索 `ownership`、`Native water`、`Pool water return`。GPU 计算进展见下一节。


### 当前模型的 Vulkan 计算

2026-10-23：硬件为 RTX 5070 Ti，Vulkan 1.4.341。`build/gas-source/gpu-device-start.log` 的设备初始化、Shaderc 编译和实际 dispatch 通过。当前新增入口使用现有单元库存与 MAC 压力模型，旧 FLIP probe 的结果保留其原有适用范围。

```powershell
./gradlew.bat :gpu-kernel:runGpuInventoryTransportValidation :gpu-kernel:runGpuPressureValidation --offline --no-daemon
./gradlew.bat :gpu-kernel:runGpuCurrentPhysicsValidation "-PgpuPhysicsSelection=controller-gas-lineage,production-live-budget" --offline --no-daemon
```

`DeviceLiquidInventoryValidation` 通过任意精度二进制库存、64 位质量和物种、原始内能位与空混合物的设备列往返。`ComputeExecutionValidation` 通过计算作用域的嵌套、线程与恢复规则。`gpu-hybrid-build.log` 中编译、打包与架构扫描通过；相同日志的库存内核验证覆盖超过 2^53 的数量、供体超支、接收溢出、4097 行并行处理、连续常驻、失败保留原代次，以及完整导出后重新分配设备资源并续算。该恢复场景没有注入真实设备丢失。

首次逐次回读压力乘积的实现通过 `activity-groups,controller-gas-lineage`，日志 `gpu-current-physics-fixed.log`；生产预算在 `gpu-hybrid-budget.log` 失败：最大溃坝 357.252 ms，连续 7 步超过 200 ms。该结果推动压力迭代整段迁入设备，原预算保持。

整段压力迭代的定点结果见 `build/gpu-resident-pressure-repair.log`：GPU 完成 10 次压力求解，包含气压下限触发及解除、连续 8 步液体推进；与 CPU 的最大 MAC 动量差为 0。累计上传 92,480 B、回读 18,896 B，Vulkan timestamp 累计 0.341 ms。这里的设备时间仅覆盖计算 dispatch，完整步成本由上述生产预算入口单独判定。首次编译的 GLSL 保留字冲突已修复。


`gpu-liquid-quantities.log` 的生产分份验证通过：Long.MAX_VALUE 质量和物种、接近 1 的份额、2^53 尾数、零质量物种及多个供体的结果与 CPU 一致；实际 `planRoutes` 批量执行设备分份后，完整库存、转移记录及提交结果相同，1101 位精确体积尾项保持。2 次设备批提交累计上传 568 B、回读 368 B、计算 9.6 μs。`gpu-device-loss-recovery.log` 的注入故障通过原请求完整重算、下一步 CPU 续算、源状态保持与一次释放；普通计算异常继续报告。真实驱动设备丢失尚未人为触发。

首次整段串行 IC(0) 压力与 GPU 分份集成的 `gpu-resident-integrated-budget.log` 完成气体传质场景，最大溃坝预算失败：最大 742.716 ms，20 步全部超过 200 ms，压力设备计算累计 4.965 s。该产物还会按微小容量变化重建工作区；后续改为容量复用并替换串行三角求解。此处保留失败证据，最终结果另列。


2026-10-24：先按生产耗时调整实现，停止重复一致性验证。并行 Jacobi-PCG 的首次完整预算 `gpu-parallel-delivery.log` 中小控制器最大 13.405 ms；最大溃坝仍连续 3 步超过 200 ms，因此该次判为失败。后续 `gpu-performance-profile.log` 的独立冷启动预算用于 JFR 定位，同样保留其失败结果。

`gpu-pressure-layout-cost.log` 使用同一制造矩阵比较提交布局。9261 行的设备内循环耗时 15.202 ms，多工作组分阶段提交为 10.212 ms；512 行分别为 1.989 ms 和 6.307 ms。两种布局共用相同着色器阶段，具体布局选择留在代码。着色器与计算管线改为创建设备服务时准备，工作区扩容只更新缓冲区绑定；活动组热路径复用一次根查找，并直接返回已压平的父节点。

最终原输入、装量、步数及预算的热运行 `gpu-cached-pipeline-budget.log` 通过。原气体场景用于同 JVM 预热，完整步计时仍包含请求捕获、恢复、求解与候选接管；该次还启用了 JFR 采样。小控制器中位 4.737 ms、最大 7.151 ms，满足逐步 40 ms；最大溃坝 20 步最大 160.168 ms，超过 200 ms 的步数为 0。压力累计 516 次设备求解，整数质量／物种累计 515 批、195762 个供体。此结果代表当前混合后端。

早于管线复用的规模成本见 `gpu-pressure-scale.log`：同一 7 点制造矩阵，8³／16³／32³／64³ 的 GPU 热态完整求解均值分别为 2.146／8.496／26.361／204.842 ms；相同矩阵的独立 CPU Jacobi-PCG 为 1.028／2.408／24.701／459.002 ms。64³ 时设备逻辑存储约 59.7 MB、主机 staging 约 94.8 MB，每次上传约 31.2 MB、回读约 2.1 MB。这些数字只覆盖压力，不代表完整区域 20 Hz，也没有测量 Minecraft 同时渲染。

最终打包与架构检查 `gpu-performance-package.log` 通过，扫描 557 个生产源文件。主模组与可选 Vulkan 组件产物分别为 `fabric/build/libs/mmm-fabric-0.17.0.jar`、`gpu-probe/build/libs/mmm-gpu-probe-0.1.17.jar`。

2026-10-24：`gpu-resident-contract-compile.log` 中 `:fabric:compileTestJava :gpu-kernel:compileJava` 通过。该次仅检查常驻状态访问契约的调用迁移，尚未运行新后端性能或物理验证。

2026-10-24：设备代次与压力成本记录：

- `build/gpu-resident-storage-compile.log` 编译通过。`build/gpu-resident-storage-cost.log` 的代次管理成本入口在 RTX 5070 Ti 上运行，1／16／64 MiB 状态的中位耗时分别为 0.149／0.162／0.292 ms，64 MiB 最大 0.533 ms。每次上传 0 B、回读 4 B；保留旧捕获时复用三份设备缓冲区，主机 staging 共 3 MiB。该入口不执行流体方程。
- 同一日志的原 64³ 制造压力矩阵复测：热态完整压力求解均值 209.349 ms，设备时间 170.503 ms。并行汇总工作组结果后，`build/gpu-pressure-parallel-reduction-cost.log` 中对应均值为 91.118 ms，设备时间 23.089 ms，仍包含每次约 31.2 MB 上传及 2.1 MB 压力回读。两次使用同一输入、1e-5 容差和 200 次迭代上限，实际均为 128 次迭代；性能入口自身的残差重算检查通过。
- `build/gpu-pressure-resident-generation-cost.log` 运行共用 `VulkanPressureKernel` 与设备代次存储，每次保持同一制造问题、零初始压力和原迭代要求。5 次预热后 10 次采样，中位 24.540 ms、最大 26.386 ms、设备平均 22.776 ms；每次上传 0 B、回读 4 B，显存内复制 46,923,936 B。两代逻辑设备存储约 89.5 MiB，主机 staging 共 3 MiB。设备报告每次收敛才提交；该结果仅覆盖压力，完整 64³ 流体步、原完整场景十倍提速及本次迁移后的集中物理验证尚未完成。


2026-10-24：常驻液体阶段链的最终成本见 `build/gpu-resident-liquid-flux-preferences-cost.log`（RTX 5070 Ti，5次预热、10次采样）。包含候选复制、接管和投影后的 PLIC 面扫掠偏好；每次从零压力求解。64³ `dam-mixture` 含196,608份库存，26次压力迭代，RMS 9.05097805e-6，沿用1e-5容差与200次上限；`cut-partial-mixture` 含524,288份库存，本输入压力迭代为0。两场景每步上传均为0 B，主机 staging 均为3 MiB。

| 64³场景 | 中位 / 最大 ms | 设备平均 ms | 每步回读 B | 逻辑设备存储 MiB |
|---|---:|---:|---:|---:|
| dam-mixture | 10.090 / 12.053 | 7.557 | 52 | 313.1 |
| cut-partial-mixture | 3.638 / 4.169 | 2.967 | 16 | 353.1 |

此前未包含面扫掠偏好的 `build/gpu-resident-liquid-fp32-geometry-cost.log` 两场景中位分别为9.673和3.359 ms，保留其原阶段范围。

首次 Jacobi-PCG 的 `build/gpu-resident-liquid-pressure-failure.log` 达到200次上限后 RMS 仍为2.6125e-5，该次失败。多重网格在相同容差和上限下26次收敛；零初始压力的完整录制成本中位22.470 ms（`gpu-resident-liquid-multigrid-zero-cost.log`），分批每8次迭代执行后为10.105 ms（`gpu-resident-liquid-pressure-batches-cost.log`），最终含面扫掠偏好的成本见上表。几何计算按4e-5体积分辨率处理，精确库存、int64数量、动力学和压力保留原精度。

本次成本覆盖常驻库存→几何→重力→真实七点 CSR→压力→格面投影→PLIC 面扫掠偏好。末阶段根据真实共享开放端口和源侧 PLIC 薄片生成有符号格面体积 `(Qt,Ql)`；该成本记录止于路线偏好；后续已接受路线的数量、库存与 MAC 分项见下文。真实接收容量、有限气腔、热化学、完整区域应用层接管与完整步成本待完成；集中物理检查尚未开始。动态容量布局、逻辑计数头和多批存储代码已落地，分层前缀扫描已用于下列精确二进制加减的变长 limb 分配。完整64³流体步20 Hz及原场景十倍提速继续按完整链交付后判定。


`build/gpu-resident-dyadic-sum-cost.log` 的设备精确二进制七项加减分项完成长度测量→`VulkanPrefixScan` 变长 limb 分配→结果写入。`runGpuDyadicSumBenchmark` 由 `gpuProbeDevice` 选择设备、`gpuDyadicSamples` 指定采样次数；本次均预热5次、采样10次，每步上传0 B、回读4 B。

| 场景 | 操作数 | 中位 / 最大 ms | 设备平均 ms | 逻辑设备存储 MiB |
|---|---:|---:|---:|---:|
| compact-remainders | 524,288 | 1.679 / 2.966 | 1.323 | 216.0 |
| wide-tails（1−2^-1074） | 8,192 | 0.265 / 1.943 | 0.057 | 5.4 |

每场计时后抽取3项结果，精确值检查通过。该记录覆盖库存余量加减和变长索引分配，通量容量接受与完整后端输运接入仍待实现，完整物理检查尚未开展。


2026-10-24：固定源、已接受单元体积预算的当前组合成本见 `build/gpu-resident-liquid-material-transport-cost.log`。RTX 5070 Ti，5次预热、10次采样；每单元两种材料和对应物种，相邻单元各保留一半并交换一半，源质量、物种量和内能不同。CPU 预置每单元已接受的保留与跨格体积，设备生成分材料路线→实际整数数量分配→同材料归并及动态 CSR→MAC 搬运、未解析格面合并与壁面处理→耗散热写入→机械热与舍入账精确归总在同一设备候选中执行。每次上传0 B、回读4 B，主机 staging 为3 MiB。

| 规模 | 库存行数 | 中位 / 最大 ms | 设备平均 ms | 逻辑设备存储 MiB |
|---|---:|---:|---:|---:|
| 32³ | 65,536 | 2.936 / 4.129 | 1.138 | 163.6 |
| 64³ | 524,288 | 9.298 / 10.219 | 8.379 | 1306.5 |

计时不接管候选，固定源输入始终保留。计时后单独提交一次，检查逻辑计数和三个单元的材料、整数质量、物种、质量余量及精确体积；内能按分配到库存的实际单元热量检查，采用4 ulp存储精度。少量格面与单元的动量、热量和热写入舍入展开检查通过。计时后在设备上独立归总全部端点 `ΣU后−ΣU前−机械热`，结果与保存的精确舍入账完全相等。新增整数能量归约同时通过跨工作组大数抵消及正负 `2^-1074` 余量检查。输入使用零参考能表，格面采用静态开放面积与规定速度；这些结果覆盖已接受单元体积的材料分份、数量、库存、MAC、热写入与精确账，容量接受、压力、有限气腔和完整区域后端接入继续待完成，集中物理检查尚未开展。

64³ 材料路线临时 limb 预留17,825,792项，实际9,699,328项；最终路线 limb 预留7,340,032项，实际1,048,576项。初次计算管线构造在采样前完成。`VulkanLiquidCellVolumes` 当前只有着色器编译证据，尚未实际 dispatch 或计成本。

`build/gpu-resident-liquid-quantity-gather-mac-ledger-cost.log` 保留尚未包含材料路线的精确账组合，较早的 `gpu-resident-liquid-quantities-gather-cost.log` 和 `gpu-resident-liquid-quantity-gather-mac-cost.log` 分别保留数量归并与MAC阶段范围；当前组合以上表为准。这些固定源分项与前述几何／投影分项分别计时，完整流体步预算按实际连续后端接入后评价。

2026-10-24：整数容量阶段的64³成本见 `build/gpu-resident-liquid-capacity-cell-64.log` 的 full 和 `build/gpu-resident-liquid-capacity-cell-first-64.log` 的 dam（RTX 5070 Ti）。输入采用 q=2^-40 的几何量子，偏好确有单元或有限组容量冲突。每场只测一次，无预热；初始上传与管线构造在计时前，表中墙钟包含冷主机提交成本。计时后才读取完整 accepted 列统计非保留运输量，普通分批反馈仍为8 B。

| 64³容量场景 | 实际波数 | 冷墙钟 / 设备 ms | 上传 / 正常回读 B | 运输保留比例 |
|---|---:|---:|---:|---:|
| full-circulation | 1 | 9.459 / 0.642 | 0 / 16 | 99.9999999998% |
| dam-gas-groups | 33 | 14.510 / 1.656 | 0 / 64 | 6.15530% |

两场均返回可行并提交，设备分配194.1 MiB，少量来源配额与接收上下限检查通过。dam 保留520Q，原偏好8448Q（Q=2^40）。按该输入现有非零偏好边，底部30行均为满格且左端无补液，其向右流量必须沿行归零；每个 z 切片仅顶行向右最多4Q、下一行向右最多4Q、顶行向下总量最多Q/8，可实现上限共520Q，即原偏好的6.15530%。当前实际运输达到这一支持图上界。单元约束收敛后才处理组预算，避免无效底层偏好抢占前沿的流量配额。此前 `cell-64` 的 dam 为32波、冷墙钟8.039 ms／设备3.498 ms、0上传／52 B回读，保留316Q（3.74053%，相当于可行上界60.7692%）；该记录保留优化前的成本与过量限流范围。

旧范围保留：`gpu-resident-liquid-capacity-cost.log` 的局部推送四场均达到512轮上限；`gpu-resident-liquid-capacity-bfs-64.log` 的 full 成功而 dam 超限；`gpu-resident-liquid-capacity-prune-64.log` 的比例削减 dam 超限；`gpu-resident-liquid-capacity-saturate-64.log` 虽满足容量却删除全部 dam 运输，由非零运动检查拒绝。这些记录均不构成有效推进性能结果。

容量阶段约束从可行的原地保留出发；颗粒位移、冻结膨胀与精确完全排空尚未覆盖。`VulkanLiquidCapacityInputs`、`VulkanLiquidAcceptedVolumes` 和各材料输运阶段已进入连续机械步成本入口，连续整链证据见下文；`VulkanGasGeometry` 的固定源气体阶段成本见文末。当前分项结果继续服务于完整64³步50 ms及原完整场景十倍提速，有限气腔、其他物相、应用层交接和集中物理检查均按完整职责链完成后判定。

2026-10-24：常驻普通环境液体连续机械正式成本通过，日志 `build/gpu-resident-liquid-mechanical-cost.log`。每场5步预热、10步采样，逐步实际提交并从上一代继续推进。范围为几何／重力／压力→容量→精确七路体积→材料与数量分配／归并→MAC→未来几何→混合及墙面耗散热与精确账→设备代次提交；输入采用显式夹具参考能表和 q=2^-40。压力每批8次迭代，容量每批32波，达到可行状态后同批剩余命令保持库存。有限气腔、热化学、其他物相、生产后端接管、世界和展示成本尚未包含。

```powershell
./gradlew.bat :gpu-kernel:runGpuLiquidMechanicalBenchmark '-PgpuMechanicalGrids=32,64' -PgpuMechanicalWarmup=5 -PgpuMechanicalSamples=10 --offline --no-daemon
```

| 规模 | 中位 / 最大 ms | 设备平均 ms | 每步上传 / 平均回读 B | 设备 / staging MiB |
|---|---:|---:|---:|---:|
| 32³ | 12.496 / 26.392 | 7.489 | 0 / 64.0 | 241.6 / 3.0 |
| 64³ | 36.675 / 44.107 | 29.024 | 0 / 90.4 | 1928.9 / 3.0 |

32³实际库存行由24,576增至28,707，64³由196,608增至211,606，连续流动改变了库存分布。末步容量分别39／82波可行，压力分别19／24次收敛，RMS分别4.6105394e-6／7.3679684e-6。64³全部10个采样步低于50 ms；该结果尚未覆盖完整物理模型或原完整场景十倍提速，集中物理检查继续后置。

此前 `build/gpu-resident-liquid-mechanical-short.log`、`build/gpu-resident-liquid-mechanical-64-short.log` 各保留1步预热、3步连续提交的短跑范围，终态成本以上表为准。

2026-10-24：固定源气体几何与状态方程成本见 `build/gpu-resident-gas-geometry-cost.log`（RTX 5070 Ti）。复用液体几何夹具，初始化时生成同代气体库存；每场5次预热、10次采样。计时范围为精确正干容和共享干口→真实连接与气腔 CSR→节点体积归约→混合气分段热容反演及状态派生。分段材料采用不同参考温度与物种参考能；液体几何、初始库存上传和管线构造在计时前完成。

```powershell
./gradlew.bat :gpu-kernel:runGpuGasGeometryBenchmark -PgpuProbeDevice=0 -PgpuGasSamples=10 --console=plain
```

| 场景 | 规模 | 有限气腔数 | 中位 / 最大 ms | 设备平均 ms | 逻辑设备存储 MiB |
|---|---:|---:|---:|---:|---:|
| 坝体混合气 | 32³ | 1 | 1.031 / 1.425 | 0.468 | 46.2 |
| 切割混合气 | 32³ | 8,212 | 1.100 / 1.283 | 0.647 | 51.2 |
| 坝体混合气 | 64³ | 1 | 3.848 / 3.997 | 3.411 | 367.6 |
| 切割混合气 | 64³ | 65,576 | 5.337 / 5.685 | 4.759 | 407.6 |

四场连通阶段均在首批4轮后收敛，计时期间每步上传0 B、回读12 B，主机 staging 为3 MiB。每次从同一设备源准备候选，计时后单独提交并读取首个节点的派生状态。这组结果测量固定源气体阶段的附加成本，有限气腔压力耦合、气体做功、拓扑变更后的库存转移与连续完整步仍待接入；集中物理检查继续后置。

2026-10-24：工作区整体扩容实现通过 `:gpu-kernel:compileJava`，日志 `build/gpu-resident-liquid-workspace-compile.log`。本次未人为构造容量溢出或运行额外 GPU 场景，正常路径继续使用既有预留；实际扩容恢复的运行证据待补充。共同机械能三项着色器 `mechanical-bodies.comp`、`liquid-thermodynamics.comp`、`mechanical-energy.comp` 已通过 shaderc 编译，连续调用、附加成本和物理核对仍待接入后测量。

2026-10-24：固定源有限气腔共同压力短成本见 `build/gpu-resident-gas-pressure-64-dam.log` 和 `build/gpu-resident-gas-pressure-64-cut-degree.log`（RTX 5070 Ti，各1次预热、3次采样）。同代液体几何、气体拓扑与 EOS 在计时前准备；每次从零压力开始，计时包括候选准备、重力、共同压力装配、PCG／多重网格／气体关联面矩阵乘积和速度响应。真实气腔分别为1和65,576，预留容量均为262,144。

```powershell
./gradlew.bat :gpu-kernel:runGpuGasPressureBenchmark -PgpuProbeDevice=0 -PgpuGasPressureGrid=64 -PgpuGasPressureScene=cut -PgpuGasPressureWarmup=1 -PgpuGasPressureSamples=3 --offline --no-daemon
```

| 64³ 场景 | 中位 / 最大 ms | 设备平均 ms | 迭代 / RMS | 每步上传 / 回读 B | 设备 MiB |
|---|---:|---:|---:|---:|---:|
| 单气腔坝体 | 22.230 / 23.110 | 12.995 | 32 / 3.47309172e-6 | 0 / 52 | 656.7 |
| 65,576 个切割气腔 | 16.449 / 17.579 | 12.304 | 20 / 5.88960171e-6 | 0 / 40 | 698.7 |

两场求解均成功。多气腔分项已消除小行空工作组成本；同输入原成本保存在 `build/gpu-resident-gas-pressure-64-cut.log`，设备35.911 ms、中位40.266 ms，求解迭代和残差相同。上述结果覆盖压力阶段；气体库存搬运、实际压缩功、共同机械能和连续完整步需合并测量。Java 及压力着色器编译通过，集中物理验证继续后置。

2026-10-24：共同机械能接入后的64³连续液体短成本见 `build/gpu-resident-liquid-common-energy-short64.log`（1步预热、3步采样）。在既有容量／库存／MAC链上加入压力与实际质量路线的机械连通体、分段热容和单元共同温度、源动能与投影动能之差、实际质量路线的势能变化、共同内能分配及端点精确舍入账。中位34.993 ms、最大37.767 ms、设备平均28.634 ms，上传0 B、平均回读84 B，逻辑设备2083.2 MiB、staging 3 MiB。末步容量79波、压力24次、RMS 7.175476428e-6，实际库存200,412行和415,306个limb。本次属于追加功能后的短成本，有限气腔库存后继、黏性等剩余力学项、其他物相与生产交接仍待合并；完整场景十倍提速和完整模型20 Hz继续按整链判定。

2026-10-24：有限气腔库存后继已进入连续机械候选。`build/gpu-resident-liquid-gas-mechanical-short32.log` 与 `build/gpu-resident-liquid-gas-mechanical-short64.log` 各1步预热、3步采样，覆盖旧气腔几何／EOS、共同压力、实际容量、液体搬运、未来气腔、来源分配、保留库存 EOS 与绝热功、气体归并、共同机械能及设备提交。32³中位20.982／最大21.687 ms、设备13.663 ms；64³中位109.187／最大138.062 ms、设备108.504 ms。两场均连续提交成功，64³耗时未达50 ms。剩余力学项、孔流、颗粒、热化学、生产交接与常规展示仍未包含。

`build/gpu-resident-liquid-gas-compact-short64.log` 改由实际气体库存推导预留，并沿正常工作区扩容处理实际新增气腔；计时内发生库存与来源组扩容、候选丢弃和从原捕获重算。64³短跑中位83.408／最大217.659 ms、设备64.851 ms，平均上传6784 B、回读241.3 B，设备2554.1 MiB，仍未达标。上传来自扩容布局元数据，正常同布局步保持零上传。

后续用同一连续输入、3步预热和1步采样定位设备阶段。`build/gpu-resident-liquid-gas-remap-profile64.log` 的末步气腔合并7.493 ms，其中组分计数1.079、组分归并2.665、物种与能量归并3.646 ms；材料路线9.018 ms。工作组并行归并和精确分配简化后，`build/gpu-resident-liquid-gas-optimized-profile64.log` 末次成功候选对应两阶段为0.189和4.236 ms，材料分配本身3.738 ms。该次采样包含一次扩容重算，完整墙钟145.913 ms、设备68.252 ms、上传10176 B、回读328 B；末步旧599气腔合并为4气腔，来源组600。细分时间用于定位计算成本，含诊断输出与扩容的完整耗时继续按超预算记录；尚未作完整模型性能或物理验收。
2026-10-24：候选准备改为只在设备复制权威库存、MAC、固定几何、压力缓存、静态物性表和头信息；各临时列由本步内核重建。第一次正式运行 `build/gpu-resident-liquid-gas-mechanical-cost.log` 在预热时因固定四次扩容重试上限停止，未形成性能结果。移除该人工次数限制后，每次重试必须严格增加实际需要的容量；需求已满足却再次报告不足会直接报错。`build/gpu-resident-liquid-gas-resident-copy-cost.log` 的64³、5步预热／10步采样全部提交成功，中位52.608 ms、最大325.358 ms、设备平均63.022 ms；平均上传6105.6 B／回读293.6 B，设备2555.1 MiB。真实扩容与整步重算均计入，预算仍失败。

`build/gpu-resident-liquid-gas-measured-capacity-cost.log` 在同一输入中合并EOS与压力首批，并先按来源／目标CSR预报所有气体份额、组分与物种空间，随后统一扩容。5步预热／10步采样全部完成，中位90.697 ms、最大165.608 ms、设备平均51.571 ms；平均上传2086.4 B／回读201.6 B、设备2559.0 MiB。采样第0和第7步各有一次扩容重算，其余步设备40.548–46.781 ms、墙钟48.484–123.795 ms且上传0 B。第6代一次增长组分、物种、份额和份额物种四列；第13代一次增长份额与份额物种，增长记录与逐步成本均在计时结束后输出。此运行期间有其他Java编译活动，主机耗时波动待独立定位；本次按未达到50 ms记录，未作达标推断。

颗粒共同压力、整数支撑、共享热容反演与共同机械能的新增内核已有Java／shaderc编译证据，尚未进入颗粒完整连续成本或集中物理验证。`build/gpu-resident-caloric-inventory-compile.log` 和 `build/gpu-resident-granular-energy-compile.log` 保存本轮编译结果。

2026-10-24：`build/gpu-resident-granular-motion-short.log` 在 RTX 5070 Ti 的64³网格上执行固定源接触至占容分项，各1次预热、3次采样。256／1536个实际粒子生成384／2304个接触；中位4.020／2.585 ms、最大6.106／3.540 ms、设备平均1.318／1.390 ms；每次上传0 B、回读20 B，设备分配32.7／46.4 MiB。计时包含设备空间桶、实际粒子对与固定外壳接触、压缩恢复摩擦、扫掠位置、质量加权去穿透、整数八格支撑和占容；输入为接地水平粒子阵列，零沉积、零固定内部微盒。本次实际编译并执行新增着色器，设备状态均成功。该记录属于固定源分项，颗粒共同压力、联合排水容量、气体后继和共同机械能、沉积床接触及连续完整步尚无本轮运行证据；集中物理检查继续后置。入口为 `:gpu-kernel:runGpuGranularMotionBenchmark`，可用 `gpuGranularGrid/gpuGranularCounts/gpuGranularWarmup/gpuGranularSamples` 指定规模和采样。
`build/gpu-resident-liquid-gas-isolated-cost.log` 复用同产物，在暂停其他代理编译的条件下运行同64³、5步预热／10步采样，并仅对目标Java进程录制 `build/gpu-liquid-mechanical.jfr`。中位64.045 ms、最大75.647 ms、设备平均49.134 ms；上传0 B、平均回读166.8 B、设备2558.2 MiB。采样期没有扩容，增长全部发生在预热期间。末步压力64次、容量170波，真实气体36节点／46组分／46物种。JFR未定位到明显Java计算热循环，主要推进样本在设备提交与等待；录制内GC最大暂停3.414 ms，后段最大约1.75 ms。即使隔离并行编译，该范围仍超过50 ms，不能归因于外部编译后直接宣布达标。剩余液体力学项接入与共同压力成本优化继续进行。
`build/gpu-resident-liquid-gas-full-forces-cost.log` 在64³、5步预热／10步采样中加入源库存温度、黏性、热浮力、表面张力入口、局部热量随整数质量路线搬运以及共同机械能的热／外功抵扣，并使用粗层分解复用的多重网格。夹具采用内置水／油黏性和膨胀参数，沿用原零表面张力、零液相对张力和中性接触角；静态壁面无滑移，没有规定运动接触。因此本次没有非零毛细力或移动实体成本证据。中位68.061 ms、最大193.972 ms、设备平均61.697 ms，平均上传1315.2 B／回读180 B、设备2869.5 MiB；前9个采样无扩容，第9号样本因份额容量增长重算，全部成功提交。末步压力56次、容量161波。该液气力学范围仍未达50 ms，孔流、颗粒、热化学、生产接管和常规展示成本继续待合并。


常驻命令与归约成本继续采用同一64³、5步预热／10步采样。共同能量最终汇总改为工作组并行后，短分项观察从约2.24 ms降至0.63 ms。粗层逆算子复用与容量条件派发后的正式记录为 `build/gpu-resident-capacity-bind-cost64.log`：中位66.482 ms、最大159.878 ms、设备平均52.829 ms，实际扩容计入，仍未达到50 ms。`build/gpu-resident-pressure-batch16-cost64.log` 增加每批压力迭代数及录制／等待分项，中位66.653 ms、最大194.900 ms、设备平均60.174 ms；无扩容步骤的命令录制耗时7.466–11.850 ms，设备等待还包含提交与主机唤醒成本。本轮据此推进命令复用，原精度和功能范围保持；尚未开展集中物理验证。

`build/gpu-resident-command-templates-cost64.log` 在相同64³、5步预热／10步采样中使用不可变描述符与工作区二级命令复用，覆盖扩容迁移和旧模板释放。中位63.255 ms、最大208.665 ms、设备平均52.610 ms，平均上传1315.2 B／回读148.8 B；第8号样本实际扩容并重算。稳定步命令录制为4.125–5.438 ms，最佳步骤墙钟51.332 ms／设备44.273 ms。当前液气力学范围仍超过50 ms，孔流、颗粒、热化学及生产交接成本尚待完整合并。

2026-10-24：独立孔流完整连续链成本见 `build/gpu-resident-gas-flow-parallel-pcg-cost64.log`（RTX 5070 Ti，5步预热、10步采样）。64³夹具为一个连通大气腔与32,768个受限孔口相连的小气腔，合计32,769腔；同对气腔的最多六个孔面共同归并为一条导度。库存使用既有混合气注册表、真实整数质量／物种与异温内能，逐步实际提交并从上一代继续推进。计时包含候选复制、源EOS、半隐式标准体积储容网络和共享PCG、同步路线、整数分配、保留气体EOS、实际排气冷却、接收归并及设备提交。

```powershell
./gradlew.bat :gpu-kernel:runGpuGasFlowBenchmark -PgpuGasFlowGrid=64 -PgpuGasFlowWarmup=5 -PgpuGasFlowSamples=10 --offline --no-daemon --console=plain
```

该范围中位22.952 ms、最大24.988 ms、设备平均20.621 ms，每步上传0 B、回读24 B，逻辑设备458.8 MiB。初始气腔对容量为1，设备需求反馈增长到32,768后，从原捕获按相同提交步序重新计算；一次增长发生在预热，全部15步提交成功。大气腔首组分的标准体积实际减少431,622,828,073 nanos，末态读取在计时后进行。该结果覆盖独立孔流链，区域全模型与原完整场景十倍提速继续按合并后的实测结果评价；集中物理检查尚未开始。

原普通CSR按单线程扫描大气腔长行，`build/gpu-resident-gas-flow-cost64.log` 同输入中位67.025 ms、最大68.791 ms、设备65.139 ms。当前 `pressureOperator` 保留同一矩阵、PCG和容差，短行单线程，长行工作组归约。`build/gpu-resident-gas-flow-parallel-pcg-profile64.log` 首步包含扩容重算，冷墙钟60.756 ms、设备20.147 ms，其中PCG初始化2.642 ms、八次迭代录制1.794 ms、最终残差0.134 ms；分项服务于成本定位，正式连续结果以上述5／10记录为准。32³此前普通CSR的1步预热／3步采样结果保存在 `build/gpu-resident-gas-flow-short32.log`，中位9.865 ms、最大12.137 ms，保留其实现范围。

2026-10-24：孔流已加入 `VulkanLiquidRegion` 的同一液气机械候选，气体压缩与共同机械能完成后，由最终库存／未来气腔几何推导孔流，实际搬运整数库存并结算保留气体冷却，最终归总整步舍入账后一次提交。`build/gpu-resident-liquid-aperture-short32.log` 的原32³坝体1步预热／3步采样全部提交，末态没有受限孔口。`build/gpu-resident-liquid-aperture-cut-retry32.log` 的32³切格原输入连续4步全部提交，末态有13,735对孔流连接，孔流PCG为34次、RMS 7.36178264e-12；中位77.866 ms、最大131.894 ms、设备28.309 ms，平均上传5664 B／回读177.3 B、设备507.3 MiB。采样中实际出现材料工作区增长并从原捕获重算，计时包含增长和命令录制。

首次切格连续运行 `build/gpu-resident-liquid-aperture-cut-short32.log` 在材料容量不足后，未来液体几何继续读取未生成的库存，额外报告体积错误，最终状态65。几何入口现遵循候选错误状态停止后续派生；原输入按真实材料需求将每窗口limb容量从4增为8后完成重算。`build/gpu-resident-liquid-aperture-cut-failure32.log` 保留修复前的首步分项：首步成功，11,941对孔流连接，PCG34次、RMS 1.78729848e-10、设备24.942 ms，冷墙钟154.160 ms；这份首步记录用于定位，连续流程以上述4步结果为准。集中物理检查继续后置。

`build/gpu-resident-liquid-aperture-cost64.log` 使用同次产物完成原64³坝体5步预热／10步采样，包含孔流入口及零孔直接完成路径。中位63.746 ms、最大172.379 ms、设备平均55.287 ms，平均上传1699.2 B／回读154.8 B，设备2947.2 MiB。第8号采样实际增长份额物种容量并重算，15步全部提交；末态孔流对数为0。此记录评价原坝体机械回归及追加入口成本；实际非零孔流的区域范围由前述32³切格连续结果覆盖，独立64³大气腔网络成本沿用其单独记录。

`build/gpu-resident-liquid-aperture-cut-cost64.log` 的原64³切格5／10请求在预热期间因液体容量状态3、512轮停止，未形成完整步性能结果。真实初始拓扑为65,576腔，初始化交接已完成；`GasStateSnapshot` 采用真实piece成员、规范minimum-key和完整归属约束，交接节点数量由实际成员决定。此失败保留原输入、dt=.05和容量轮数，后续沿同一容量算法定位结构原因。该次构建含正在评估的对称气体块预条件，原double算子对照及容量失败的独立定位继续由压力／容量性能工作维护。集中物理验收尚未开展。

2026-10-24：设备导热内核的连续成本见 `build/gpu-resident-conduction-normal64.log`（RTX 5070 Ti，5步预热、10步采样）。262,144个库存热载体、782,336条三维有限体积接触，采用声明于夹具的分段水热容、两侧恒温热库和 .05 s 步长，实际从上一代内能与温度继续推进。普通场景 h=.5 m，包含设备候选复制、接触邻接、热容、稳定显式导热、精确端点／热库能量归约和提交。中位2.234 ms、最大3.633 ms、设备平均1.160 ms，每步上传0 B、回读12 B，设备269.2 MiB。当前输入直接给定有限体积接触，真实液气颗粒空间接触采样及完整区域流程继续接入。

`build/gpu-resident-conduction-normal32.log` 的1步预热／3步采样中位1.772 ms、最大8.201 ms、设备.290 ms。相同导热入口的刚性场景采用 h=1e-5 m，按实际质量和面积缩放有限体积参数，进入分段热容隐式导热与共同 PCG；32³与64³均连续提交4步，分别见 `build/gpu-resident-conduction-stiff32.log`、`build/gpu-resident-conduction-stiff64.log`。32³中位20.767 ms、最大27.329 ms、设备10.735 ms；64³中位94.472 ms、最大103.676 ms、设备58.794 ms。刚性64³尚不满足完整步预算；这组极细网格数据用于识别刚性求解成本。以上均为导热内核范围，完整模型20 Hz、原场景十倍提速与集中物理验证尚未完成。

导热成本入口：`./gradlew :gpu-kernel:runGpuThermalConductionBenchmark -PgpuThermalGrid=64 -PgpuThermalWarmup=5 -PgpuThermalSamples=10 -PgpuThermalScene=normal`。`gpuThermalScene=stiff` 使用同一求解链的刚性输入；固定材料定义与夹具参数由对应入口维护。

2026-10-24：真实库存接触与导热短链见 `build/gpu-resident-thermal-contacts-short.log`（RTX 5070 Ti）。8³切格混合液体／气体夹具使用 h=.5 m，并按实际体积调整既有 h=.25 夹具的液体质量与内能；声明水／油／砂热导率为 .6／.15／1.2 W/(m K)，混合气组分使用 .026 W/(m K)，保留原异质分段热容与物种参考能。一个活动颗粒按整数SVQ配额实际跨两格，一个沉积组具有独立库存与内能；左侧热库为330 K。

```powershell
./gradlew.bat :gpu-kernel:runGpuThermalContactsBenchmark --offline --no-daemon --console=plain
```

1步预热／3步采样共4次连续提交完成，133个真实气腔对应1159个唯一热载体、1292份材料热容分项、1539个空间样本及12,085条实际接触。气腔各格样本与活动颗粒各支撑共用对应库存的唯一内能；唯一载体数量与实际库存一致，样本数量大于载体数量的检查通过。接触容量从1按实际需求增长一次，失败候选从同一原捕获重算，增长发生在预热。中位2.685 ms、最大4.023 ms、设备平均1.250 ms，采样每步上传0 B／回读32 B，设备6.7 MiB。活动颗粒内能由14282.2266 J变为14282.1198 J；末态读取在计时后进行。

本次覆盖真实液体／混合气／活动颗粒／沉积库存的同代热反演、空间接触建图、共享导热求解和唯一内能写回，当时使用共同 `solveImplicit` 编排；当前入口已迁至 `recordGraphPrepare/solveGraphImex/recordGraphFinish`，与空间接触共用局部隐式模型，以下历史数值保留原实现范围。有限热设备、区域调度接入及集中物理验收继续按完整职责链推进；该短链成本保留8³输入范围。

2026-10-24：同一接触短链加入129组真实连续固相库存，原夹具固体占容与独立正SVQ固相均由 `VulkanCoherentGeometry` 生成逐格占容，再经 `VulkanGranularGeometry` 合并活动颗粒与沉积占容，液体和气体几何读取同一结果。日志 `build/gpu-resident-thermal-coherent-contacts-short.log`：8³、133气腔、1288唯一载体／1421热容分项／1668空间样本／13,849接触，1步预热加3步采样连续4提交成功，接触容量实际增长一次。中位2.460 ms、最大4.566 ms、GPU平均1.286 ms，0 B上传／32 B回读，7.6 MiB。选定连续固相内能16603.0884→16603.0779 J，活动颗粒14282.2266→14282.1197 J。此证据覆盖五类实际库存的共同接触链与连续固相设备几何构造。

2026-10-24：导热已进入 `VulkanLiquidRegion` 的机械／有限气体候选，实际在孔流后刷新温度、生成接触、写回内能，并归总热库和整步舍入账。`build/gpu-resident-region-thermal-short8.log` 使用8³坝体、1步预热／3步采样，连续4步提交成功，预热包含气腔库存容量增长重算。该次包含开发中的统一容量求解器，中位78.063 ms、最大79.325 ms、GPU平均33.482 ms，每步上传0 B／回读92 B，设备7.7 MiB。成本不满足目标；本结果用于确认实际调用链及定位成本，尚未进入集中物理验收。

对应命令：`./gradlew :gpu-kernel:runGpuLiquidMechanicalBenchmark -PgpuMechanicalGrids=8 -PgpuMechanicalGas=true -PgpuMechanicalThermal=true -PgpuMechanicalWarmup=1 -PgpuMechanicalSamples=3`。随后 `build/gpu-resident-region-thermal-profile8.log` 的同场景1步预热／1步分项采样显示，投影与容量阶段GPU31.674 ms，共同压力3.465 ms，物质输运／未来几何／机械体.728 ms，气体重映射／共同能量／实际导热／精确账合计1.449 ms。容量接受末态14轮成功；主要成本集中在统一容量的残量推进，继续处理其并行算法及无工作派发。分项采样的墙钟含计时输出开销。


2026-10-25：统一容量器已删除逐根串行BFS增广，普通流动与颗粒排液共用满液散度树、并行残量标签、活动提案和受影响顶点配额。阶段由设备反馈选择独立命令批次，无工作阶段间接派发零工作组。保持原七路偏好、输入规模和512轮上限。原失败输入重放逐项检查全部供体数量、接收上下限和有限组容量，且实际跨格运输量为正。

本机本轮Vulkan枚举的0号设备是RTX 4060 Ti，1号设备是RTX 5070 Ti；性能记录明确区分硬件。`build/gpu-capacity-surface-tree-granular-replay.log` 在4060 Ti上以35轮完成原32³颗粒输入，GPU 2.226 ms；`build/gpu-capacity-surface-tree-cut-replay.log` 在同设备上完成原64³切格输入，GPU 36.370 ms。随后5070 Ti的 `build/gpu-capacity-cut64-5070-replay.log` 以91轮完成，GPU 43.174 ms，冷重放墙钟69.189 ms；这份记录包含冷命令录制与读回，作为容量阶段成本。

5070 Ti上同一8³坝体、液气与真实导热的1步预热／1步分项采样见 `build/gpu-resident-region-thermal-capacity-profile8-5070.log`：采样步墙钟20.361 ms、GPU 8.669 ms，上传0 B、回读128 B。投影／容量2.940 ms、压力3.491 ms、输运／未来几何.612 ms，气体后继／共同能量／导热／精确账1.490 ms。与先前同设备8³分项中容量31.674 ms的记录相比，容量调度成本明显降低；该短记录仍保留当前功能范围。

原64³切格加导热首次运行 `build/gpu-resident-region-capacity-cut64-short.log` 在冷导入创建整个工作区ByteBuffer时超过2 GiB，未开始推进。冷导入改为设备清零与按长整型偏移分块上传库存、静态表和布局头后，`build/gpu-resident-region-capacity-cut64-streamed.log` 在5070 Ti上完成1步预热／3步采样，连续4步成功提交。中位332.806 ms、最大442.453 ms、GPU平均303.746 ms，平均上传6440 B／回读266.7 B，逻辑设备6001.8 MiB、staging 3 MiB。预热中实际增长热接触容量，采样首步增长材料limb窗口并从原捕获重算；后两采样零上传，末步容量直接接受、压力24次、实际孔流109601对。该完整候选范围显著超过50 ms，尚无完整模型20 Hz或十倍提速结论。

有限设备实际接触短链见 `build/gpu-resident-finite-device-contacts-complete8.log`：5070 Ti、8³切格、133气腔，1289唯一载体、1422热容分项、1668空间样本和13857接触；1步预热／3步采样连续4提交，接触容量实际增长一次。中位2.755 ms、最大3.871 ms、GPU平均1.343 ms，正常步上传0 B／回读32 B，逻辑设备7.6 MiB。设备内能308000→307779.297 J、代次4，显式导出和精确交换审计通过。首次记录 `build/gpu-resident-finite-device-contacts-short8.log` 已完成4次提交，但末态导出遗漏ByteBuffer翻转；修复后复用原输入完成以上短链。该范围覆盖液气、活动颗粒、沉积、连续固相与有限设备的共同接触和导热；设备在生产区域请求中的接入继续按实施计划推进。

2026-10-25：稳定64³完整步分项见 `build/gpu-resident-region-cut64-complete-profile.log`：5070 Ti、原切格、液气与真实导热，3步预热／1步采样；采样墙钟291.922 ms、GPU279.190 ms，上传0 B／回读220 B。源气腔4.616 ms、压力17.274 ms、投影／容量4.231 ms、物质输运／未来几何14.963 ms，气体后继／共同能量／导热／精确账238.106 ms。末步容量零修正轮接受；完整步仍约为50 ms预算的5.8倍，继续按完整职责链改造主要算法。

2026-10-25：容量接收配额改为活动边64位CAS领取，流量单边写入并原子更新两端余额，删除每轮接收CSR配额扫描与余额重算。5070 Ti原输入短重放：`build/gpu-capacity-cut64-atomic-replay.log` 为100轮、GPU37.903 ms、冷墙钟73.614 ms；`build/gpu-capacity-granular32-atomic-replay.log` 为35轮、GPU3.447 ms、冷墙钟22.240 ms。两者全部供体配额、接收上下限和有限组检查通过，保持七路偏好及512轮上限。64³困难输入的容量成本仍占用大部分50 ms预算，尚未满足完整步目标。

2026-10-25：长热接触行改为分块归约后，`build/gpu-resident-region-cut64-thermal-tiles-profile.log` 在5070 Ti的原64³切格液气导热场景完成3步预热／1步采样。稳定步墙钟127.357 ms、GPU115.537 ms，零上传；其中孔流41.445 ms、热接触与导热13.071 ms。该记录仍按全部接触分配热库列，随后已由独立紧凑热库编号替换。

紧凑热库与有限设备短链见 `build/gpu-thermal-compact-bath-device-short8.log`：8³、1预热／3采样连续4次提交成功，接触与热库分别实际增长，热库容量414；中位1.761 ms、GPU平均.569 ms、上传0 B／回读36 B，逻辑设备4.5 MiB。设备内能308000→307779.297 J、代次4，精确交换审计通过。`build/gpu-thermal-tiled-stiff32.log` 的32³刚性导热连续3步完成，共5次Newton迭代，采样中位29.896 ms、GPU平均12.927 ms；覆盖分块隐式残量、PCG与精确热量写回。

孔流实际长行分块见 `build/gpu-gas-flow-tiled-short64.log`：5070 Ti、64³多孔中心气腔、32769节点／32768气腔对，1步预热／3步采样连续4提交，中位20.213 ms、最大25.528 ms、GPU平均17.883 ms，正常上传0 B／回读24 B、逻辑设备459.4 MiB；气体数量实际跨孔搬运，容量增长一次。以上独立范围与整区域完整步分别记录，完整模型20 Hz仍待达成。

2026-10-25：容量改为从实际七路偏好并行撤回违反单元／有限组容量的运输；原地保留不满足容量时延迟构建统一残量图。单元树已删除，512轮保持。5070 Ti的原输入重放 `build/gpu-capacity-retraction-cut64.log` 为12轮、GPU4.610 ms、冷墙钟22.132 ms，保留约97.5%非留存偏好运输；`build/gpu-capacity-retraction-granular32.log` 为39轮、GPU4.640 ms、冷墙钟21.747 ms，保留接近全部原偏好。两者全部供体、接收及有限组整数检查通过。

既有64³满格循环和有限组坝体见 `build/gpu-capacity-retraction-normal64-complete.log`：分别2轮／GPU1.579 ms和33轮／GPU12.392 ms，均通过全部整数约束及非零全场运输检查。坝体保留约3.0%可行表面运输；旧三点移动断言已改为全场实际运输检查，原夹具的封闭满层单向偏好需要撤回。此前局部标签与单元树的失败记录保留在 `build/gpu-capacity-lazy-graph-normal64.log` 等日志中。

合并偏好撤回、孔流分块、热接触分块与热库紧凑记录后的原64³切格液气导热连续候选见 `build/gpu-resident-region-cut64-compact-complete.log`：5070 Ti、3步预热／3步采样，连续6提交成功；中位119.381 ms、最大124.947 ms、GPU平均108.894 ms，采样上传0 B／平均回读252 B，逻辑设备5143.8 MiB。预热中真实增长接触、热库和材料窗口，热库实际12197边。末步孔流114007对、111次PCG，容量2轮接受。完整步仍超过50 ms，尚无十倍整步提速或20 Hz结论。

2026-10-25 性能优先连续运行：`build/gpu-resident-cut64-shortrows-performance.log` 使用 `-PgpuProbeDeviceName=5070` 固定 RTX 5070 Ti，原64³切格液气导热、3步预热／3步采样、连续6提交。短气腔行合并执行、整数分份商估计后精确修正、黏性系数缓存及气压邻接系数缓存后，中位104.251 ms、最大106.597 ms、GPU平均87.818 ms，采样上传0 B、平均回读252 B，逻辑设备5407.8 MiB。末步孔流109次PCG、容量2轮。50 ms目标仍未达到，功能与物理验证暂停，下一步集中替换稠密输运、粗图求解和重复源几何计算。数字设备序号来自驱动枚举，后续可比测量固定设备名称。

同日 `build/gpu-resident-cut64-fused-performance.log` 在相同原64³切格、5070 Ti、3预热／3采样下连续6次提交：中位84.426 ms、最大93.084 ms、GPU平均75.885 ms；上传0 B、平均回读276 B、逻辑设备5386.2 MiB。实际采用源几何复用、稀疏液体路线、短长气腔行分派、FP32孔流校正与FP64真实残差、压缩黏性缓存，以及接触写入时同步生成邻接度数／热库索引。末步压力32轮、容量2轮、孔流129轮，原误差判定成功。首两步按真实需求增长接触、热库与材料临时窗口，route limbs无伪增长。末步压力阶段20.313 ms（含黏性5.509 ms）、输运／后继几何14.127 ms、气体重映射／共同能量7.028 ms、孔流29.125 ms、导热8.509 ms。50 ms整步目标仍未达到，其他功能验证继续后置。


2026-10-25：生产格式冷导入、候选丢弃、机械／独立热请求、精确检查点和紧凑展示三步交接见 `build/gpu-resident-ledger-display-validation.log`。每步液体体积134.39999771118164、质量16799.9997139008，GPU精确库存归约与热账逐项闭合；派生温度与液体展示已实际执行。此入口仍直接调用区域候选，完整生产backend采用链另行记录。

颗粒生命周期七步短链见 `build/gpu-granular-transitions-short.log`：实际床层松弛、活动颗粒沉积和四颗粒再起动均发生，SVQ8137258、质量整数1576309710741守恒，动态调度与冷交接检查通过。接入生命周期后的32³、256颗粒、有限气体与导热四步见 `build/gpu-region-integrated-granular-thermal.log`，三次采样中位52.354 ms、最大64.713 ms，GPU平均33.105 ms，运行中实际发生容量增长。相同日志中的有限设备与连续固体导热连续四次提交，完整区域精确热账闭合。

实体模块四步短链见 `build/gpu-entity-body-short.log`：重叠AABB合并占容0.46875 m³（62914560 SVQ），实际黏性剪切与规定压力载荷共同产生逐实体反力；末步外功与源项归总均为−45.9813087177 J。该证据覆盖实体模块，完整区域共同求解的运行结果单独登记。


32³液气、256活动颗粒、1沉积床及真实导热连续候选见 `build/gpu-resident-granular-thermal-complete32.log`：1步预热／3步采样连续4次提交；中位51.980 ms、最大65.221 ms、GPU平均32.887 ms。首采样零上传／30.583 ms，后两步实际增长气腔与来源组并从原捕获重算，平均上传15088 B／回读266.7 B，逻辑设备514 MiB。最终保留256活动颗粒和1沉积组，累计竖向位移-44.0718 m，容量74轮接受。此结果确认实际联合调用链，集中物理验证继续后置。


2026-10-25：`build/gpu-chemistry-backend-short.log` 首次执行固液析出与真实生产后端采用。固液入口产生新床层2600 SVQ，Na/Cl物种、质量、精确液体体积、非零MAC动量及U/K/PE检查通过。生产后端先丢弃候选，再连续采用机械／热／机械三代，期间再次丢弃私有后继；每代摘要质量167999997139008整数单位保持，GPU实体浸液查询和显式存档通过。`build/gpu-production-query-display-short.log` 补充实际单格材料／物种／温度、水接触及GPU材料分层展示执行，全部通过。

`build/gpu-compact-surface-protocol.log` 覆盖紧凑展示的网络往返、相机点查询与客户端小网格网格化。64³原尺寸生成2097152个按需凸片的字段，编码9961598 B；该CPU参考投影加往返总耗时383.950 ms，生产投影已改由GPU直接生成材料分层。这个数字不能作为GPU展示成本，生产展示完整成本须另测。

2026-10-25：`build/gpu-resident-cut64-graph-performance.log` 在相同 RTX 5070 Ti、原64³切格、3预热／3采样下连续6次提交，中位82.437 ms、最大83.014 ms、GPU平均72.808 ms，上传0 B、回读252 B／步、逻辑设备5127.4 MiB。使用共享体积描述符、局部显隐黏性、共用液气面裁剪和真实空间粗校正；末步共同压力32轮、容量2轮、孔流81轮，既有残差条件成功。50 ms尚未达成，功能扩展和集中物理验证保持暂停。计时后通过 `-PgpuMechanicalPressureDump=<绝对路径>` 导出 `build/gpu-cut64-aperture-graph.bin`，用于分析实际强连接及求解复杂度，导出不计入上述成本。

2026-10-26：`build/gpu-resident-cut64-once-mg-performance.log` 沿用上述设备、原场景及连续6步。完整液体多重网格每次组装只构建一次，延拓与后平滑合并后，中位78.509 ms、最大78.941 ms、GPU平均68.376 ms。`build/gpu-resident-cut64-short-assembly-performance.log` 进一步把孔流短行组装直接分配到线程、长行保留工作组归约，中位80.781 ms、GPU平均69.668 ms；末步孔流组装由2.295 ms降至0.616 ms，整步仍未达到50 ms。两次均零上传、连续提交成功；耗时优先，其他验证继续暂停。

2026-10-26：孔流改为独立低度节点消元、核心求解及回代后，`build/gpu-resident-cut64-schur-performance.log` 在相同原64³、3预热／3采样中连续6步成功，中位64.220 ms、最大65.580 ms、GPU平均56.096 ms；末步核心120行，压缩建图3.975 ms、分解求解1.419 ms、回代和原图残差0.126 ms，原图RMS为6.9572e-13。复用同一编译产物关闭分项日志，`build/gpu-resident-cut64-schur-normal-performance.log` 中位61.781 ms、最大62.368 ms、GPU平均56.044 ms。两次均12次提交／步、上传0 B、回读116 B／步、逻辑设备5237.4 MiB。分项查询和打印计入前一组墙钟；正常链仍超过50 ms，其他功能与物理验证保持暂停。

2026-10-26：`build/gpu-resident-cut64-matrixfree-thermal-performance.log` 在原64³、3预热／3采样中连续6步成功，中位55.524 ms、最大56.100 ms、GPU平均48.710 ms。孔流大气腔行并行化及 FP64 分块分解后，末步压缩建图0.341 ms、核心 CSR 0.074 ms、分解求解0.580 ms、回代和原图残差0.122 ms；气体体积交接短行改造约1.0 ms。显式导热直接使用空间样本，末步6.796 ms，其中样本算子4.129 ms，正常步仅发生热库容量增长，未生成完整接触图。体与未来气体连通性状态合批回读后，每步11次提交、上传0 B、回读120 B，逻辑设备4721.1 MiB。相同产物关闭分项日志的 `build/gpu-resident-cut64-matrixfree-normal-performance.log` 中位56.212 ms、最大57.088 ms、GPU平均51.352 ms。运行间存在波动，两组墙钟均超过50 ms，继续性能工作。

延长同一原场景至3预热／12采样后，`build/gpu-resident-cut64-continued-performance.log` 连续15步提交成功，中位69.728 ms、最大262.372 ms、GPU平均70.090 ms，末步65次提交；刚性导热生成约573万接触并扩容整区后重算，该成本包含在计时内。后期孔流核心增长至559行，回到104次迭代；计时后导出的 `build/gpu-cut64-aperture-step15.bin` 显示，继续两层独立低度消元可将相应图的504行核心依次压缩至214、120行。`build/gpu-resident-cut64-pressure-restarts.log` 仅增加共同压力既有控制字段的读回，确认后期单步111次迭代中有49次下限截断重启，FREE集合改变重启为零。短跑约56 ms不能代表该场景的持续成本；50 ms与完整模型十倍提速均未达成，继续按这三项结构性原因改造，其他功能和物理对照暂停。

`build/gpu-resident-cut64-active-solve-performance.log` 在同一原64³、3预热／12采样中完成15次提交并正常退出，中位67.625 ms、最大114.172 ms、GPU平均58.283 ms，逻辑设备4930.8 MiB。孔流逐层消元后全部采用小核心分解；刚性导热只增长独立系数缓冲，采样期没有整区扩容或力学重算，实际2048 B上传来自新建的小图布局头。共同压力内层允许中间越界、在自由子问题收敛后判断活动集，全部采样的约束试探及活动集切换为零；末步仍需79轮，压力28.686 ms，说明液气耦合预条件仍是主要成本。末步容量6.201 ms、输运及未来几何12.067 ms、气体重映射与共同能量5.023 ms、孔流9.041 ms、导热28.720 ms。导热刚性指标为410.093，最小热容4.2e-7 J/K；两次Newton分别需3和2次PCG，气体夹具实际具有分段热容。前一轮 `build/gpu-resident-cut64-multilevel-performance.log` 已完成15次提交，但退出时独立系数缓冲重复释放导致驱动异常；资源释放已移除被提前销毁的登记，本轮正常退出。性能仍未达标，下一步改造整张液气图的预条件和局部刚性热求解，继续暂停其他验证。

`build/gpu-resident-cut64-stiff-scale-performance.log` 在相同15步中补充既有空间接触遍历的刚性计数，中位65.737 ms、最大112.000 ms、GPU平均57.654 ms。末步按 `2 dt ΣG / Cmin > 1` 分类，只有1个刚性热载体及1个样本，刚性内部接触0条、刚性与非刚性之间4条、非刚性之间5,738,183条；最小热容4.2e-7 J/K，最大刚性指标284.648。该结果支持按唯一热载体局部隐式求解，避免单个极小热容触发全区重复热算子。

局部显隐分区导热进入原场景后，`build/gpu-resident-cut64-imex-performance.log` 完成同样的3预热／12采样和15次提交，中位64.897 ms、最大78.983 ms、GPU平均54.430 ms。末步3个刚性载体均无刚性邻接，直接按分段热容求根，导热4.909 ms；压力28.027 ms、容量7.927 ms、输运及未来几何12.764 ms、气体重映射与共同能量5.541 ms、孔流8.846 ms。热流仍按实际接触成对结算，非刚性试算复用，刚性接触只修正受影响样本；本轮仅检验连续执行与成本，集中物理观察继续后置。整步50 ms仍未达成。首次合批运行 `build/gpu-resident-cut64-local-thermal-performance.log` 在末步漏做受影响样本压紧，导致修正未执行并返回16384；压紧已移至接触计数批次，本次原15步成功。

2026-10-26：共同压力捕获状态新增独立性能重放 `:gpu-kernel:runGpuCommonPressureReplay`，输入 `build/gpu-cut64-common-pressure.bin`，保留原右端、热启动、下界、1e-5 残差目标与200轮预算。冷端仅转换实际液气 CSR，未执行 CPU 参考求解。`build/gpu-common-pressure-graph-replay.log` 中全图预条件以28轮收敛，但建图约64 ms、求解约700 ms，GPU平均753.757 ms，性能失败。`build/gpu-common-pressure-graph-profile.log` 的原状态时间戳定位到粗层构建和限制阶段的串行嵌套邻接循环：首层粗对角约12 ms，首层限制在整次求解累计约245 ms。该实现继续改为长行分块与并行成员归约；未以此恢复完整场景或集中物理验证。

同一捕获状态改用短行线程、长行分块及成员归约后，`build/gpu-common-pressure-parallel-graph-replay.log` 的三次采样均以27轮收敛，中位58.348 ms、GPU平均43.296 ms；末次建图10.842 ms、求解46.007 ms（两者为墙钟）。`build/gpu-common-pressure-parallel-graph-profile.log` 显示源图压紧约.24 ms，原串行长行成本已消除；当前12层聚合及逐层派发仍使压力单项超过整步分配预算，继续改造聚合降维。重放已采用与生产相同原则的长行分块乘积，完整区域测量继续等待压力成本合格。

同一捕获状态的强聚合分项记录见 `build/gpu-common-pressure-aggregate-profile.log`：74743个实际行、614811项，六层规模为74743／9582／3844／2320／293／15；33轮达到原残差目标。单次分项采样墙钟53.764 ms，建图8.163 ms、求解38.149 ms（墙钟），GPU30.004 ms。分项打印包含在总墙钟中，不能作为无诊断的稳态完整步结果；压力单项成本仍不合格。后续加性预条件与紧凑压力派发改动尚无运行证据。

2026-10-26：删除气体与机械连通体的主机反馈重试，连通边使用并行根链接与路径压缩；共同压力压紧有效行，并合并输运／孔流准备和导热／最终账。原64³、3预热／12采样的 `build/gpu-resident-cut64-fused-chain.log` 完成15步，中位57.459 ms、最大72.785 ms、GPU平均52.361 ms。共享面面积同时用于压力与实际输运后，`build/gpu-resident-cut64-shared-flux.log` 同样完成15步，中位61.834 ms、最大89.132 ms、GPU平均55.937 ms；该表示统一尚未获得性能资格。

共同压力无工作向量阶段改用零组间接派发后，`build/gpu-resident-cut64-pressure-dispatch.log` 在第15步暴露容量撤回循环，未形成完整性能结果。保存输入为 `build/gpu-liquid-capacity-shared-flux-failure.bin`；`build/gpu-capacity-shared-flux-replay.log` 稳定复现512轮耗尽，停在撤回阶段3。常规本地撤回现限制为一次初始化，剩余需求直接进入同一并行残量图，保留实际七路流量和原512轮总预算。`build/gpu-capacity-shared-flux-fixed-replay.log` 以185轮完成，GPU84.977 ms；`build/gpu-capacity-original-unified-replay.log` 的原64³失败输入以166轮完成，GPU57.294 ms。源额度、接收上下限与有限组容量均通过重放内置检查，但容量单项严重超支，完整场景和其他验证继续暂停。`build/gpu-capacity-shared-flux-phases.log` 确认185轮中初始BFS6轮、余额推送／重标记178轮，下一步须缩短该执行链并处理主要连续容量约束。

容量图改为隐式邻接后，`build/gpu-capacity-implicit-colored-replay.log` 在同一新失败输入以120轮完成，GPU97.840 ms，设备内存由292.7降至185.0 MiB。仅保存组成员CSR，供体／接收者边直接枚举；未改原512轮预算。`-PgpuCapacityStages=true` 的分项入口见 `build/gpu-capacity-implicit-profile.log`：130轮、GPU111.579 ms，长行色0／色1分别20.505／68.949 ms，短行合计16.011 ms，初始建表不足1 ms。色1唯一长顶点是公共汇总节点。该证据否定当前长行单工作组执行方式的性能资格，下一步恢复长行跨工作组并行，保持隐式邻接与精确需求限额；完整场景和其他验证继续暂停。

多工作组长行版的 `build/gpu-capacity-parallel-long-replay.log` 对同一新输入以91轮完成，GPU26.687 ms、设备182.1 MiB。短行活动队列在 `build/gpu-capacity-frontier-replay.log`／`build/gpu-capacity-frontier-profile.log` 分别为39.104／31.342 ms，没有形成收益，已删除。恢复稠密短行后，原64³失败输入 `build/gpu-capacity-original-parallel-long.log` 为25轮、GPU7.700 ms；32³颗粒失败输入 `build/gpu-capacity-granular-parallel-long.log` 为277轮、GPU40.676 ms。均保留原输入与512轮预算，重放内置源额度、接收上下限及有限组检查通过。

`build/gpu-resident-cut64-unified-parallel-long.log` 原64³、3预热／12采样连续15步完成：中位86.843 ms、最大96.381 ms、GPU平均71.306 ms，设备4855.2 MiB；末步压力92轮、容量103轮。原容量短重放提速不能替代完整步性能资格，当前整步结果仍失败。共享面通量之后的逐供体限流仍会破坏连续压力解，主要容量约束需前移到共同连续求解，精确残量修正应缩至量化范围；集中物理验证与剩余功能验收继续后置。


2026-10-27：恢复上风 PLIC 薄片扫掠输运。此前共享两侧接液面积被误用为实际液体输运面积，空供体可产生虚假 Ql 并抹掉 incomingDry；86.843 ms 结果不作为当前方案资格。原64³第13个连续步暴露孔流压力残差失败，捕获 `build/gpu-aperture-failure.bin`（65,992节点、118,860对）。新增 `runGpuGasFlowReplay`，用 `-PgpuGasFlowReplayFile=...` 复用实际 Schur 路径。失败原因是全图减去首节点约1.13e20 Pa再加回，普通节点丢失精度；基准改为环境压力，方程和容差保持。`build/gpu-aperture-fixed-replay.log` 原图 RMS 4.037e-8，满足现有浮点舍入界；图中极小储容的高气压来源仍待后续物理审查。

`build/gpu-resident-cut64-upwind-fixed.log`：原64³切格、液气＋导热、3预热／12采样连续15步完成，中位70.705 ms、最大93.528 ms、GPU平均59.329 ms，设备4854.5 MiB。末步压力76轮、容量33轮，孔流原图RMS3.940e-8。完整步仍超预算，集中物理验证保持后置；该链也尚未覆盖全部生产职责。
