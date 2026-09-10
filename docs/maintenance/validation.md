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
| 局部网格碰撞和连续碰撞检测 | `cut-cell` | [FlipCutCellValidation](../../solver/src/test/java/cc/ecisr/mmm/fluid/FlipCutCellValidation.java) |
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

表中是定位场景的入口，完整调用列表以 `CpuValidationSuite` 为准。长场景的注液坐标、循环步数和逐步断言留在测试代码中维护。

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

`production-live-budget` 调用 `defaultLiveStepsRemainBounded`，验证两种生产负载：

| 场景 | 规模 | 耗时要求 |
|---|---|---|
| 默认控制器注入 16 BV | `8×8×8` 场景，20 个实际计算步 | 每步不超过 40 ms |
| 空场景最大装量的 `demo` | `24×16×24` 网格，2,592 个液体单元、20,736 个初始粒子，完整物理功能，20 步 | 连续超过 200 ms 的步数小于三步 |

报告分别记录 JVM 预热和被测步的耗时，并保留粒子数、体积、相变操作、子步数、PCG 次数与残差。优化后用同一物理配置复测，保持原有预算和守恒检查。

`production-replay` 检查长时间注液、清空、气腔归属、相变功结算、实体响应以及后端捕获/恢复。它关注每步物质与能量收支。性能是否达标由上面的独立测试判定。

`controller-gas-lineage` 集中复现清空扩大气体空间后，从外壳附近再次注液的过程。修改扫描几何时先运行 `air-cavity`，修改气压作用时再运行 `gas-liquid`。

<a id="architecture"></a>
## 架构与编译检查

修改类职责、依赖或模块位置时运行：

```powershell
./gradlew.bat verifyArchitecture --offline --no-daemon
```

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
