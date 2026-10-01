# 修改后的验证

先运行与改动直接相关的测试。需要修改测试场景时，从下表打开测试源码。涉及其他模块的数据或提交步骤，再追加对应测试。

## CPU 测试怎么运行

在仓库根目录执行，例如：

```powershell
./gradlew.bat :fabric:runCpuValidation '-PcpuValidation=region-state,containers' --offline --no-daemon
```

`cpuValidation` 是测试选择器，多个名称用逗号连接。[CpuValidationSuite](../../fabric/src/test/java/cc/ecisr/mmm/validation/CpuValidationSuite.java) 定义了全部名称与组合。[fabric/build.gradle](../../fabric/build.gradle) 定义执行任务。

| 选择方式 | 含义 |
|---|---|
| 指定名称，例如 `thermal` | 完整运行该领域；多个名称会去重 |
| 省略参数或使用 `fast` | 运行默认组合，提供短模式的场景使用短模式 |
| `milestone` | 较广的阶段验收组合，仍使用各场景的短模式 |
| `soak` | 完整运行 `gas-liquid`、`free-surface`、`reseeding` |
| `full` | 完整运行所有已登记的领域 |
| `list` | 输出当前组合与选择器名称 |

显式写出的领域名称运行完整模式，即使它也出现在 `fast` 中。`production-live-budget` 和 `controller-gas-lineage` 需要显式选择，或通过 `full` 执行。Gradle 的 `check` 任务没有隐式包含所有 CPU 场景。

<a id="cpu"></a>
## 按改动选择测试

| 改动 | 选择器 | 测试源码入口 |
|---|---|---|
| 极端温度、压力与相变连续推进 | `thermal-extremes` | [ExtremeThermalValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/ExtremeThermalValidation.java) |
| 材料、质量、混合与液液界面 | `materials` | [M3ConnectedVesselValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/M3ConnectedVesselValidation.java) |
| 表面张力和润湿 | `surface-tension` | [M4SurfaceTensionValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/M4SurfaceTensionValidation.java) |
| 气体库存、状态方程与孔流 | `air-network` | [AirNetworkValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/air/AirNetworkValidation.java) |
| 气腔扫描和连通关系 | `air-cavity` | [AirCavityScannerValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/AirCavityScannerValidation.java) |
| 液体与气体压力耦合 | `gas-liquid` | [M5GasLiquidCouplingValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/M5GasLiquidCouplingValidation.java) |
| 控制器清空后再注液的气腔归属 | `controller-gas-lineage` | [ProductionFluidReplayValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/ProductionFluidReplayValidation.java) |
| 生产场景耗时 | `production-live-budget` | [ProductionFluidReplayValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/ProductionFluidReplayValidation.java) |
| 连续注液、后端恢复和长时间物理行为 | `production-replay` | [ProductionFluidReplayValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/ProductionFluidReplayValidation.java) |
| 区域数量、预算、重叠和选择 | `region-manager` | [FluidRegionManagerValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/FluidRegionManagerValidation.java) |
| 调度、暂停、完整状态交接 | `lifecycle` | [FluidRegionLifecycleValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/FluidRegionLifecycleValidation.java)、[FluidDomainStateParityValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/FluidDomainStateParityValidation.java) |
| 实体位移观测和液体响应 | `entity-fluid` | [EntityFluidWiringValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/EntityFluidWiringValidation.java)、[ProductionFluidReplayValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/ProductionFluidReplayValidation.java) |
| 压力、自由面、速度传递与平流 | `free-surface` | [FlipFluidFreeSurfaceValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/FlipFluidFreeSurfaceValidation.java) |
| 粒子拆分、合并和数据列搬移 | `reseeding` | [FlipFluidReseedingValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/FlipFluidReseedingValidation.java)、[CarrierMutationValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/CarrierMutationValidation.java) |
| 黏性解析解、谱界、局部网格碰撞和连续碰撞检测 | `cut-cell` | [FlipCutCellValidation](../../solver/src/test/java/cc/ecisr/mmm/fluid/FlipCutCellValidation.java) |
| Minecraft 方块形状采样 | `shape` | [FluidCutCellShapeValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/FluidCutCellShapeValidation.java) |
| 存档、世界能力包、配置和启动恢复 | `region-state` | [RegionRepositoryAdapterValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/persistence/RegionRepositoryAdapterValidation.java)、[WorldCapabilityBundleStoreValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/persistence/WorldCapabilityBundleStoreValidation.java)、[WorldCapabilityStartupValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/persistence/WorldCapabilityStartupValidation.java) |
| 网络、查询、探针和提交通知 | `protocol` | [FluidNetworkProtocolValidation](../../fabric/src/test/java/cc/ecisr/mmm/network/FluidNetworkProtocolValidation.java)、[ShellFluidQueryValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/ShellFluidQueryValidation.java)、[CommittedEventHubValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/CommittedEventHubValidation.java) |
| 控制器外壳和内腔扫描 | `boundary` | [FluidBoundaryScannerValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/controller/FluidBoundaryScannerValidation.java) |
| 开发场景的世界接管备份 | `takeover` | [TakeoverJournalValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/TakeoverJournalValidation.java) |
| 桶、发射器、库存编码与事务恢复 | `containers` | [M6ContainerValidation](../../fabric/src/test/java/cc/ecisr/mmm/item/M6ContainerValidation.java)、[ContainerApplicationValidation](../../application/src/test/java/cc/ecisr/mmm/application/ContainerApplicationValidation.java)、[ContainerTransferStoreValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/ContainerTransferStoreValidation.java) |
| 世界方块归属和恢复 | `ownership` | [M6OwnershipValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/ownership/M6OwnershipValidation.java) |
| 命令日志 | `audit` | [CommandAuditFileValidation](../../fabric/src/test/java/cc/ecisr/mmm/logging/CommandAuditFileValidation.java) |
| 化学反应和组分输运 | `chemistry` | [M7ChemistryValidation](../../fabric/src/test/java/cc/ecisr/mmm/chemistry/M7ChemistryValidation.java)、[M7ChemicalTransportValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/M7ChemicalTransportValidation.java) |
| 颗粒运动、沉积和固液转移 | `granular` | [M8GranularValidation](../../fabric/src/test/java/cc/ecisr/mmm/granular/M8GranularValidation.java)、[GranularReactionCandidateValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/GranularReactionCandidateValidation.java) |
| 内能、导热、反应热、相变和设备换热 | `thermal` | [M9ThermalValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/M9ThermalValidation.java) |
| 零导热平流、caloric 空间观察与冷热尖峰单调性 | `thermal` | [LiquidThermalAdvectionValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidThermalAdvectionValidation.java) |
| 有限热接触解析解、跨热容分段熵增与逐载体正温 | `thermal` | [ThermalContactValidation](../../model/src/test/java/cc/ecisr/mmm/thermal/ThermalContactValidation.java) |

表中是定位场景的入口，完整调用列表以 `CpuValidationSuite` 为准。长场景的注液坐标、循环步数和逐步断言留在测试代码中维护。

自然对流的线性响应测量使用 `./gradlew.bat :fabric:runConvectionExperiment --offline --no-daemon`。该试算报告两级网格、临界 Ra 两侧和两倍振幅的归一化增长率，并检查库存与能量闭合。Ra 增长率属于[阶段 1c 的待验收项](../roadmap/product-roadmap.md#1c-密度分层与对流)，试算执行成功表示已取得测量数据。

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
[ProductionStepBenchmark](../../fabric/src/test/java/cc/ecisr/mmm/simulation/ProductionStepBenchmark.java)
测量控制器半满场景与大型 demo，各预热 20 步、采样 20 步，报告分阶段耗时和内存分配量，并检查物质与能量收支。
测量范围是 CPU 计算流程，适合同机同配置的优化对比；评估耗时收益时应结合多次测量的波动。

组合过程使用 [CompositeStepBenchmark](../../fabric/src/test/java/cc/ecisr/mmm/simulation/CompositeStepBenchmark.java)，例如：

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

完整计时和线程分配量使用相同工作范围，额外状态导出、审计与 CSV 格式化位于范围外。
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

`granular` 中的 [GranularCarrierValidation](../../solver/src/test/java/cc/ecisr/mmm/fluid/GranularCarrierValidation.java) 检查多 parcel 阻力交换的动量守恒、能量耗散和顺序无关性、有限颗粒质量的压力功和连续性、跨单元面的连续液体接触、静水梯度给出的浮力、步末阻力有效性检查，以及小时间步下对压力零点的独立性。`containers` 保留正常操作随机序列的种子 4、54、103，覆盖薄液层反冲、密集颗粒排水与高压下的时间细化。

同一 `granular` 选择器中的 [GranularBedWakeValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/GranularBedWakeValidation.java) 使用正式全能力注册表、启用相变，并逐步经过后端捕获/恢复：细砂沉积并休眠后，弱刚体扰动应无侵蚀，较强扰动应重新起动并实际水平搬运，减弱为静止物体后应再次沉积、休眠。逐步记录起动数、活动数、床面 Shields 数与真实接触数，检查固体质量/体积和气体、内能收支；另检查释放瞬间的零速度、内能搬运、碰撞空间及干区/隔墙误触发。`GranularCarrierValidation` 同时检查薄床切向自由流动和时间细化。

批量排查使用 [ControllerInventoryDurabilityValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/ControllerInventoryDurabilityValidation.java) 的带种子操作序列，每次操作后执行完整状态往返。参数依次是起始种子、种子数量、操作数和是否禁用沙粒（可省略，默认 `false`）：

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

[ArchitectureDependencyValidation](../../fabric/src/test/java/cc/ecisr/mmm/validation/ArchitectureDependencyValidation.java) 检查模块 import 方向、纯 Java 模块包循环、持久化与运行时依赖隔离、唯一服务器运行时注册点，以及生产类 1000 行上限。超过 600 行会提示，超过 1000 行会失败。[CarrierMutationPointInventoryValidation](../../fabric/src/test/java/cc/ecisr/mmm/validation/CarrierMutationPointInventoryValidation.java) 同时检查粒子结构修改的调用位置。

需要单独运行 model、solver、application 的验证时，使用根任务 `runModuleValidation`。当前源码规模可用 [Python 工具](pytool.md)重新统计。

修改客户端类型、Jade 依赖或界面后，编译服务端和客户端源码：

```powershell
./gradlew.bat :fabric:compileJava :fabric:compileClientJava --offline --no-daemon
```

<a id="gpu"></a>
## GPU probe 验证

任务定义在 [kernel 构建文件](../../gpu-probe-kernel/build.gradle)和 [Fabric 适配构建文件](../../gpu-probe/build.gradle)。

改适配器或模块依赖时，先运行：

```powershell
./gradlew.bat :gpu-probe-kernel:compileJava :gpu-probe:compileJava :gpu-probe:runAdapterValidation verifyArchitecture --offline --no-daemon
```

改设备资源、着色器、checkpoint 或数值算法时，运行基础数值验证：

```powershell
./gradlew.bat :gpu-probe-kernel:runGpuFlipValidation -PgpuProbeSkipLarge=true -PgpuProbeSkipLong=true --offline --no-daemon
```

粒子分布或能量变化需要长时间验证，可先用 100 步定位，再按风险运行默认 1000 步：

```powershell
./gradlew.bat :gpu-probe-kernel:runGpuLongRunSurfaceValidation -PgpuProbeLongRunSteps=100 --offline --no-daemon
```

修改设备能力检测或初始化时，再运行 `:gpu-probe-kernel:runHeadlessGpuProbe`。

GPU 粒子合并使用原始 float 的精确二进制值判断体积能否守恒，着色器中的 `precise` 保证编译器保留该运算顺序。长跑比较精确库存、机械能、空间限制与展示覆盖范围。并行归约允许解析得到的舍入误差，CPU/GPU 逐格轨迹按各自数值容差比较。

设备性能使用 Vulkan timestamp 测量，调度与超时使用实际经过时间。生产 GPU 的完整功能要求见 [性能与后端规划](../roadmap/performance-and-backends.md)。

## 需要进入游戏时

当问题依赖实际画面、Minecraft 方块形状或磁盘重启过程时，准备一个可重复场景，写明操作、预期现象和需要保留的日志。发布构建、产物校验和部署步骤见 [验收与发布](../roadmap/acceptance-and-release.md)。

## 正常操作随机仿真

`./gradlew.bat :fabric:runNormalPlayFuzz '-PfuzzArgs=16,80,false,8,0,13'` 扫描 13 种几何、每种 16 个种子，每个种子 80 步；每步检查库存、能量和 capture/restore。
单个复现用 `-PfuzzArgs=replay,1,203,80`；严格子序列最小化用 `-PfuzzArgs=minimize,1,203`。`reported` 运行既有历史种子集。

分数抽取后的盘上气腔归属回归使用 `-PfuzzArgs=replay,partialdisk,1,200,49`；颗粒阻力外层收敛与完整候选时间细化回归使用 `-PfuzzArgs=replay,17,7810,37`。两条序列都保留逐动作守恒审计及恢复检查。

极端温压测试使用正式材料定义，在零重力封闭域设置 `10 Pa / 10^7 K`、`10^12 Pa / 10 K`、`10^12 Pa / 150 K`、`10^12 Pa / 10^7 K` 四组初态，连续执行开启相变的后端步骤并检查气体和内能收支及恢复。低压高温与 150 K 高压用例还要求实际发生相变。10 K 用例检查没有可接受冻结批次时仍能守恒推进。上述数值为基准验证范围；各物理量输入范围由对应材料与状态方程定义约束。
