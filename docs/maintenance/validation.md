# 验证指南

先明确当前需要回答的问题与成本预期。
有 95% 把握的改动跳过额外验证、校验和测试；物理主干改动按范围选择完整步生产预算，展示改动分别记录快照与客户端成本。
有具体未知风险时，从下列入口选择能回答该问题的最小场景，复用同一次构建产物。

<a id="gpu-演示完整流程"></a>
## GPU 演示与场景验证

演示重放入口为 Gradle 任务 `:fabric:runGpuProductionDemoValidation`，使用生产 Vulkan 后端、场景初始化、调度策略、快照恢复与库存事务。游戏内对应工作台交互命令 `/mmm demo` 与控制器命令 `/mmm controllerdemo`。

```powershell
./gradlew.bat :fabric:runGpuProductionDemoValidation -PgpuDemo=<场景> -PgpuDemoSteps=<步数> --console=plain
```

### 演示选择器与场景参数

- `-PgpuDemo`：指定场景，支持 `tank`、`flow`、`ambient`、`air`、`vacuum`、`controller`、`precipitation`、`tension`、`demo`、`material`、`chemistry`、`capture`、`takeover`。
- `-PgpuDemoSteps`：推进步数（省略时使用场景默认步数）。
- `-PgpuDemoStage`：阶段选择器，默认 `complete`（包含物理推进、暂停、手动步进、导出恢复与编辑后续算）；可选 `initial`、`motion`、`scheduled` 用于故障分段定位。

| 场景 (`gpuDemo`) | 步数 | 物理过程与验证要点 | 验收指标 |
|---|---|---|---|
| `tank` | 120 | 依次重置为清水、油水、稀酸碱三种生产配方；室温常压复位与真实桶量事务 | 清水下落铺开、油水分层、酸碱中和并生成水，质量守恒闭合 |
| `flow` | 120 | 水向空侧扩散；实体进入、移动、离开及交互扰动 | 自由面平稳推进，实体受力反馈正确 |
| `ambient` | 500 | 小型 U 管双侧连通水柱常压平衡 | 两侧液位保持接近，主体温度稳定 |
| `air` | 500 | 同一 U 管左气腔加压 | 加压侧液位下降、对侧上升 |
| `vacuum` | 500 | 同一 U 管左气腔降压 | 低压侧液位上升、对侧下降 |
| `controller` | 20 | 真实水桶注液、舀出、WAL 与 checkpoint 导出及恢复 | 事务前后库存严格守恒，GPU 状态无损续算 |
| `precipitation` | 500 | 高浓度酸碱中和反应、结晶析出、沉淀清空及待位移恢复 | 固液相变严格守恒，颗粒占容与排水正确，清空后连续续算 |
| `tension` | 200 | 相同初态对比 30° 与 120° 润湿接触角液滴 | 30° 充分铺展，120° 保持收缩，接触面积符合预期 |
| `demo` | 500 | 大型溃坝（24×16×24 空间，324 m³ 水量），实体进出扰动 | 水体坍塌铺展平稳，质心单调下降，水温保持稳定 |
| `material` | 120 | 油水双相分层流动 | 密度差驱动自然分层，轻相稳定保持在重相上方 |
| `chemistry` | 120 | 酸碱连续中和输运与反应热结算 | 反应物等摩尔消耗并生成水，反应热平稳扩散 |
| `capture` | 40 | 采样世界水体并保留原方块 | 采样边界解析正确，支持后续状态操作 |
| `takeover` | 40 | 接管 32 个水源方块并在完成后归还 | 动态世界方块状态无损接管与释放 |

每个接受步检查四相整数质量与元素收支、热账、设备驻留及展示快照。环境交换单独记账，扣除环境交换后的系统质量差为零。调度沿用连续三步超过 200 ms 暂停的生产规则。

<a id="高浓度析盐"></a>
### 高浓度析盐

高浓度析盐复用真实水箱与桶投料流程，酸碱各投料 1000 mol，验证固体颗粒析出与沉淀清空。

```powershell
./gradlew.bat :fabric:runGpuProductionDemoValidation -PgpuDemo=precipitation -PgpuDemoSteps=500 --console=plain --max-workers=1
```

- 核心流程：清水、油水、高浓度酸碱各运行 500 步，包含暂停、手动单步、checkpoint 恢复续算、清空沉淀及后续清水续算。
- 相变待位移状态恢复：附加 Java 参数 `-Dmmm.demoPhaseRestore=true` 执行 `mmm.simulation.GpuProductionDemoValidation precipitation 120 complete`。在生成颗粒时捕捉 GPU 待位移状态并恢复至宿主，校验精确体积余量与接触约束。
- 验收指标：每个接受步检查四相整数质量、物种摩尔数、元素收支及内能账闭合；沉淀析出与溶解体积交接正确，全程质量差为零。

### 控制器交互与界面

控制器交互覆盖空手激活、潜行切换、探针交互、运行与暂停、生命周期预览／取消／确认、温度源输入、清空／排气／释放／移除以及比较器关联。

- 自动化验证：
  - `:fabric:compileClientJava` 编译客户端；`runCpuValidation -PcpuValidation=protocol` 执行协议编解码、请求代次与客户端几何验证。
  - `:fabric:runGpuProductionDemoValidation -PgpuDemo=controller -PgpuDemoSteps=20` 覆盖生产注液、GPU 推进、手动步进、装取与 WAL/checkpoint 持久化。
  - `ContainerTransferStoreValidation --rapid-pour` 覆盖 24 次连续注液后的持久清空、排气、恢复与重注液。
- 实机观察：执行 `/mmm controllerdemo`，使用探针打开控制器，滚动摘要面板，测试暂停／继续与刷新，依次预览、取消及确认清空、排气与移除。操作回复与生命周期状态保持同步，关闭后不重新弹窗。

### 演示与客户端交互自动化

2026-10-29，以下命令通过。客户端渲染修复使用 Minecraft 26.2 的图集定义 ID，保留纹理绑定 ID；测试执行实际几何发射，不创建 Minecraft 窗口或图形设备。

```powershell
./gradlew.bat :fabric:compileClientJava :fabric:runCpuValidation '-PcpuValidation=protocol,lifecycle,boundary,region-manager' :fabric:runDemoInteractionValidation :fabric:assemble --console=plain --max-workers=1
```

`DemoInteractionValidation` 驱动生产工作台请求，世界读写通过测试适配器提供，区域目录、外壳扫描、会话及演示存档使用正式实现。全部七个预设依次执行重置、开始、暂停、单步、原位重置、移除和异位重建；覆盖权限／世界未就绪、首次放置失败后换位置、旧请求与跨维请求、远离场景、单步失败、配方失败、临时场景替换失败、目标空间及区域重叠拒绝，以及水箱被外部删除后的恢复。失败用例检查原区域、壳体与会话保留，并继续执行成功流程。

`ClientSurfaceValidation` 覆盖材料与颗粒的空帧、诊断显色、组分与阴影顶点字段，及界面共用请求状态的失败后续操作、旧回复拒绝。`protocol` 同时验证控制器错误回复编解码与所需能力协商；`lifecycle,boundary,region-manager` 覆盖区域操作、壳体扫描和目录规则。物理推进沿上述 GPU 场景入口单独验证。

本轮补充验证：`lifecycle,protocol` 通过；生命周期实际写入世界能力包及演示记录后再次启动。`tank 120` 和 `controller 20` 通过，控制器从未标注温度的原版水桶开始，经真实计划、WAL、HEAD、恢复及创造模式取水，核对物质与内能。`ContainerTransferStoreValidation --journal-thermal` 通过，覆盖带独立来源库存的日志与公共转移回执。完整 `containers` 选择器在日志及恢复用例通过后进入较长的 CPU 物理矩阵，本轮终止该部分，未计为全套通过。

小型 U 管常压、加压及低压各连续 500 步通过，均包含暂停单步、导出恢复、编辑后续算和生产超时暂停规则。末态两臂液位差分别约 2 cm、10 cm、6 cm，加压与低压方向相反；主体水温约 293.14 K，质量按环境交换后的整数收支严格闭合。低压重放的高温微气泡约占有限气体质量的十亿分之一，主体气温约 295 K；温度验收按实际库存质量加权，局部极值记录为诊断。

真实方块写入、鼠标命中、字体排版及图形绘制由图形客户端观察。入口为 `/mmm demo`：清水开始后切到油水和酸碱，切换显色，暂停、单步、重置、移除，再切换水流池及三种气压预设。带光影包和资源重载的观察仍单独保留。

### GPU 短链与单元验收

生产 Vulkan 后端接入的短链单元验证任务：

- `:gpu-kernel:runGpuSolidLiquidReactionsValidation`：固液析出、精确质量与物种 SVQ、非零动量及内能结算；容量不足时保留候选库存。
- `:gpu-kernel:runGpuResidentStateValidation`：生产 ServiceLoader 发现、设备状态连续推进、实体运动插入与 checkpoint 导出恢复。
- `:gpu-kernel:runGpuThermalPhaseValidation`：冻结、熔化、汽化、凝结热相变，有限气腔 CO₂ 溶解与外界析气膜传质；检查整数质量、物种与内能账闭合。

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

`CpuValidationSuite` 在运行开头输出构建版本、源码摘要、JVM 参数与选择器清单。全量、长重放及预算测试以实际退出码与断言结果为准，并区分独立冷启动与 JVM 预热后的热运行。

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
  - 同一构建的聚焦入口：`M4SurfaceTensionValidation --wetting` 复用润湿 1,200 步及液液界面后继，`--liquid-pair` 单独运行该后继，`--curvature` 保留共同曲率场景。直接运行测试类时附加 JVM 参数 `-ea -Xmx512m`。

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

展示基准：2592 个有液单元代表场景下，四类展示快照平均 ~15 ms，网格生成平均 ~5 ms（以 16 ms 为客户端 CPU 目标）；同几何帧与暂停状态复用网格。

### 单一人工场景与光影接入

验证环境与目标光影：
- Iris 1.11.2 / Sodium 0.9.1（Minecraft 26.2）
- Photon v1.4（需关闭 `WATER_DISPLACEMENT`）
- Complementary Unbound r5.9.3（需关闭 `WAVING_WATER_VERTEX`，保留法线波纹）

人工验证场景（玻璃控制器内腔 4×4×4）：
1. 底部依次加入一桶汞、四桶水、一桶油及少量细沙，观察分层与玻璃侧面界面。
2. 暂停区域，切换组分、pH、温度及色带范围，观察实时换色与遮挡。
3. 移动视角进出液体并靠近界面，核对水下介质判定与内外可见性。
4. 切换上述光影包，核对深度、阴影、折射与水下效果。
5. 执行重载、区域移除与重进，确认 GPU 网格资源正常释放与重建。

<a id="流体显色模式"></a>
### 流体显色模式

组分、逐相 pH 与凝聚相温度沿同代快照展示。光影包可能重算水体基色，以原版渲染和无着色状态对照诊断读数。

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

性能验收采用热运行基准（预热后采样）。单独复测使用 `-PcpuValidation=production-replay,production-live-budget`。
`production-replay` 检查长时间注液、清空、气腔归属、相变功结算、实体响应及后端捕获恢复的收支守恒；`ProductionFluidReplayValidation --compressed-soak` 覆盖气腔静止场与扰动衰减场。物理验收覆盖分层、液封、惯性、孔流、热对流与气腔压缩。

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
架构检查直接使用该根任务，不要写成 `-PcpuValidation=architecture` 或 `:fabric:verifyArchitecture`。

[ArchitectureDependencyValidation](../../fabric/src/test/java/mmm/validation/ArchitectureDependencyValidation.java) 检查模块 import 方向、
纯 Java 模块包循环、持久化与运行时依赖隔离、唯一服务器运行时注册点，以及生产类 1000 行上限。超过 600 行会提示，超过 1000 行会失败。
[LiquidInventoryAuthorityValidation](../../fabric/src/test/java/mmm/validation/LiquidInventoryAuthorityValidation.java) 同时检查生产代码中的旧粒子库存写入。

需要单独运行 model、solver、app 的验证时，使用根任务 `runModuleValidation`。当前源码规模可用 [Python 工具](pytool.md)重新统计。

修改客户端类型、Jade 依赖或界面后，编译服务端和客户端源码：

```powershell
./gradlew.bat :fabric:compileJava :fabric:compileClientJava --offline --no-daemon
```

<a id="gpu"></a>
## GPU 内核基准与驱动排查

生产物理主干已默认启用 Vulkan 后端。内核与底层任务定义在 [kernel 构建文件](../../gpu-kernel/build.gradle) 和 [Fabric 适配构建文件](../../gpu-probe/build.gradle)。

### 离线内核基准测试

```powershell
# 区域存储代次与压力求解基准
./gradlew.bat :gpu-kernel:runGpuRegionStorageBenchmark --offline --no-daemon
./gradlew.bat :gpu-kernel:runGpuResidentPressureBenchmark -PgpuResidentPressureGrid=64 -PgpuPressureSamples=10 --offline --no-daemon

# 连续机械步基准（网格尺寸 32/64）
./gradlew.bat :gpu-kernel:runGpuLiquidMechanicalBenchmark '-PgpuMechanicalGrids=32,64' -PgpuMechanicalWarmup=5 -PgpuMechanicalSamples=10 --offline --no-daemon

# 气体几何与压力耦合基准
./gradlew.bat :gpu-kernel:runGpuGasPressureBenchmark -PgpuProbeDevice=0 -PgpuGasPressureGrid=64 -PgpuGasPressureScene=cut -PgpuGasPressureWarmup=1 -PgpuGasPressureSamples=3 --offline --no-daemon

# 导热求解基准
./gradlew.bat :gpu-kernel:runGpuThermalConductionBenchmark -PgpuThermalGrid=64 -PgpuThermalWarmup=5 -PgpuThermalSamples=10 -PgpuThermalScene=normal --offline --no-daemon
```

### 底层驱动与适配器验证

改动设备资源、着色器或数值算法时运行基础验证：

```powershell
./gradlew.bat :gpu-kernel:compileJava :gpu-probe:compileJava :gpu-probe:runAdapterValidation verifyArchitecture --offline --no-daemon
./gradlew.bat :gpu-kernel:runGpuFlipValidation -PgpuProbeSkipLarge=true -PgpuProbeSkipLong=true --offline --no-daemon
```

Windows 环境若 `vkCreateInstance` 返回 `-9` 或无可用设备，在当前会话设置 `$env:VK_LOADER_DEBUG='error,warn,driver'` 后运行 `:gpu-kernel:runHeadlessGpuProbe`。若日志提示 `Found no drivers`，核对显卡驱动对应的 `VulkanDriverName` 注册表项与 ICD 路径配置。

## 需要进入游戏时

当问题依赖实际画面、Minecraft 方块形状或磁盘重启过程时，准备一个可重复场景，写明操作、预期现象和需要保留的日志。发布构建、产物校验和部署步骤见 [验收与发布](../roadmap/acceptance-and-release.md)。

## 正常操作随机仿真

```powershell
# 扫描 13 种几何、每种 16 个种子，每个种子 80 步，检查库存守恒与状态恢复
./gradlew.bat :fabric:runNormalPlayFuzz '-PfuzzArgs=16,80,false,8,0,13' --offline --no-daemon

# 单个复现与最小化
./gradlew.bat :fabric:runNormalPlayFuzz '-PfuzzArgs=replay,1,203,80' --offline --no-daemon
./gradlew.bat :fabric:runNormalPlayFuzz '-PfuzzArgs=minimize,1,203' --offline --no-daemon
```

回归测试包含分数抽取后的盘上气腔归属（`replay,partialdisk,1,200,49`）与颗粒阻力外层收敛（`replay,17,7810,37`），均保留逐动作守恒审计及恢复检查。

<a id="当前验证证据"></a>
## 当前验证基线与状态

生产物理主干已默认启用 Vulkan GPU 后端，最新基线与证据由前文对应入口维护：

| 验证领域 | 覆盖内容与完成标准 | 主要验证入口 | 当前状态 |
|---|---|---|---|
| **交互演示面板** | 水箱三配方、水流池、U形管气压、控制器注液装取 | `:fabric:runGpuProductionDemoValidation` | 离线与回放全通过，中位 15~36 ms，无连续超限 |
| **高浓度析盐** | 500 步高浓度酸碱析盐、沉淀清空、待位移 checkpoint 恢复 | `-PgpuDemo=precipitation -PgpuDemoSteps=500` | 质量守恒闭合，末态水温正常，连续推进无异常 |
| **控制器界面与交互** | 探针打开、摘要更新、暂停/恢复、清空/排气/释放/删除工作流 | `:fabric:compileClientJava`、`protocol,lifecycle` | 协议闭合，生命周期与持久化状态机通过 |
| **GPU 生产接入** | 析出、驻留状态、热相变、气液膜传质、ServiceLoader 发现 | `:gpu-kernel:runGpuThermalPhaseValidation` 等短链 | 默认启用 Vulkan，各物种/能量账本守恒 |
| **CPU 生产预算** | 控制器 16 m³（≤40 ms）、最大 demo 324 m³（连续超 200 ms < 3 步） | `-PcpuValidation=production-live-budget` | 热运行基准达标 |
| **架构规范** | 依赖方向、1000 行限制、权威写入隔离 | `verifyArchitecture` | 扫描全部源码通过 |

早期演进过程中的分项调试日志与淘汰方案已归档至 Git 提交历史；日常开发与交付以本基准及前文具体命令为准。
