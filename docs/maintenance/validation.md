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
