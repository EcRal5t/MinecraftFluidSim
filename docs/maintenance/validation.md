# 修改后的验证

先明确当前需要回答的问题与成本预期。
有 95% 把握的改动跳过额外验证、校验和测试；物理主干改动按范围选择完整步生产预算，展示改动分别记录快照与客户端成本。
有具体未知风险时，从下列入口选择能回答该问题的最小场景，复用同一次构建产物。

## 演示面板与小场景

2026-10-28，`/mmm demo`／`demos` 和快速入门手册统一进入演示面板。当前面板为水箱三配方、水流池、气压三预设。最终构建保留生产 Vulkan 后端；本轮未启动 Minecraft。

`build/demo-workbench-final-build.log` 记录客户端编译、`protocol,lifecycle,boundary` 和组装成功。覆盖演示请求／回复、会话及代次、维度、实际不可用状态、持久水箱记录、四朝向壳体扫描与接口定位，以及现有生命周期清理流程。`build/demo-workbench-tank-final.log` 记录最终 GPU 产物重建、三配方连续回放和组装成功，其余回放复用该次 classpath。

| 场景 | 自动化结果 | 本地日志 |
|---|---|---|
| 水箱 `tank` | 同一控制器区域依次重置为空箱、油水、稀酸碱，各 120 步；室温常压复位、真实桶量、失败候选保留旧库存、checkpoint 连续推进。油质心 0.891 格高于水的 0.134 格；酸碱各消耗 398487 量子并生成等量水 | `build/demo-workbench-tank-final.log` |
| 水流 `flow` | 120 步，池壁／障碍使用共享布局；玩家进入、移动、离开，水向空侧扩散；暂停、手动两步、恢复、编辑及再次推进 | `build/demo-workbench-flow-final.log` |
| 常压 `ambient` | 120 步，两侧液位保持接近，完成状态操作及编辑后续算 | `build/demo-workbench-ambient-final.log` |
| 加压 `air` | 120 步，加压侧下降、对侧上升，完成状态操作及编辑后续算 | `build/demo-workbench-air-final.log` |
| 低压 `vacuum` | 120 步，低压侧上升、对侧下降，完成状态操作及编辑后续算 | `build/demo-workbench-vacuum-final.log` |
| 小控制器 `controller` | 20 步，真实水桶注液、暂停、手动两步、舀出、WAL／checkpoint 和 GPU 续算 | `build/demo-workbench-controller-final.log` |

所有回放逐步检查整数质量／元素收支、热账和展示快照。热运行完整应用步中位耗时：水箱 18.464 ms、水流 23.716 ms、常压 36.132 ms、加压 35.023 ms、低压 34.813 ms、控制器 18.307 ms；均未触发连续三步超 200 ms 暂停。这些是上述小场景的离线测量，64³／50 ms 仍按原性能输入单独评价。

无游戏自动化调用生产场景初始化、调度和库存事务；实际屏幕绘制、鼠标命中、网络往返、世界脚手架清理及跨游戏重启仍需实机观察。必要时只用 `/mmm demo` 一处入口，依次选择场景、开始、暂停、单步、重置和移除；预期保持位置、按真实状态启用按钮、清除原样外壳，并在失败时显示具体原因。原控制台删除水箱后，工作台依靠持久删除记录重新建立场景。

<a id="高浓度析盐"></a>
### 高浓度析盐

2026-10-28，`precipitation` 复用真实水箱和桶投料流程，酸碱各投入 1000 mol，检查实际生成固体。最终产物的 `build/precipitation-final-assembly500.log` 记录空箱、油水、高浓度酸碱各 500 步成功，随后完成暂停手动两步、checkpoint 恢复续算、清空沉淀及空箱续算，`assemble` 成功。每个接受步检查整数质量、元素、热账和设备驻留。酸碱各消耗 999111710 物种量子，并生成等量水，末态水温 300.505 K；整个流程质量差为零。

1497 个热运行应用步的中位为 15.063 ms，P95 为 21.450 ms，最大 458.832 ms；有一次超过 200 ms，随后恢复，未触发连续三步超限的生产暂停。该成本属于小水箱，64³ 的性能结论继续使用原输入。

```powershell
./gradlew.bat :fabric:runGpuProductionDemoValidation -PgpuDemo=precipitation -PgpuDemoSteps=500 --console=plain --max-workers=1
```

相变中间状态的专项恢复可复用同次构建的 classpath，额外加 Java 参数 `-Dmmm.demoPhaseRestore=true`，执行 `mmm.simulation.GpuProductionDemoValidation precipitation 120 complete`。测试捕捉实际 GPU 待位移状态，恢复到宿主、提交 checkpoint，再继续生产调度；未捕捉到该状态时直接报告未覆盖。解析尺度以内的体积余量由 `CarrierMutationValidation` 的明确输入覆盖，检查库存原量恢复及后续排水；真实场景按当次实际轨迹报告余量。基础面板酸碱配方继续使用稀溶液观察中和与 pH。

`build/precipitation-final-pending120.log` 在高浓度酸碱已经生成颗粒时捕捉待位移状态，完成 checkpoint 恢复和其后 120 步流程；本次捕捉时最大超容为零。`build/precipitation-pending-cpu-final.log` 的 `CarrierMutationValidation` 与 `GranularCarrierValidation` 覆盖精确体积余量、接触分辨率及 pending 床格的恢复约束。

同次生产源码的 `build/precipitation-final-gpu-regressions.log` 通过固液反应、七种热相变／膜传质、常驻状态与实体 checkpoint 回归；`build/precipitation-final-flow120.log`、`build/precipitation-final-controller20.log` 分别通过水流池 120 步和控制器 20 步及真实桶事务。临时逐步诊断已删除。本轮继续采用无头执行，未启动 Minecraft。

## GPU 演示完整流程

2026-10-28，演示重放入口为 `:fabric:runGpuProductionDemoValidation`。使用生产 Vulkan provider、世界注册表、场景初始化、应用服务、调度策略、状态查询、快照、恢复和库存编辑。未启动 Minecraft 客户端或服务器；世界方块与区块可用性由离线端口提供。控制器使用实际壳体扫描、端口解析、ItemStack、容器事务、WAL 和 checkpoint；capture/takeover 使用真实采样与接管事务。

```powershell
./gradlew :fabric:runGpuProductionDemoValidation -PgpuDemo=chemistry -PgpuDemoSteps=120 --console=plain
```

`gpuDemo` 接受 `flow`、`tank`、`precipitation`、`ambient`、`demo`、`material`、`chemistry`、`air`、`vacuum`、`tension`、`controller`、`capture`、`takeover`。`tank` 依次重置并运行三种生产水箱配方；`precipitation` 使用同一流程的高浓度析盐输入，追加固体生成、恢复和清空检查；`tension` 顺序运行相同初态的 30° 和 120° 液滴。其余场景的默认 `gpuDemoStage=complete` 包含物理结果、暂停／运行、手动步进、恢复和编辑后的续算；`initial`、`motion`、`scheduled` 供失败定位，分别仅覆盖对应前缀。`demo` 验证选择器保留原大型溃坝，对应游戏命令 `damdemo`；`material`／`chemistry` 保留原初始化的基线重放。

每个接受步检查四相整数质量与元素收支、热账、设备驻留及展示快照。环境交换单独记账，日志的 `total_mass_delta` 是包含真实环境收支的库存变化；断言比较的是扣除这些收支后的质量差。大型溃坝保留原 24×16×24 全能力场景和 324 m³ 初始水量，并执行真人尺寸实体的进入、移动和离开。

2026-10-28，RTX 5070 Ti 上的原场景基线结果如下。步数为调度推进次数，随后另有暂停、手动步进、导出恢复、编辑及续算。常温水场景逐步检查质量加权水温，捕获账本闭合但整体异常冷却的失真。

| 场景 | 完整结果 | 本地日志 |
|---|---|---|
| `demo` | 500 步，实体进入／移动／离开；水向空侧扩散、重心下降，水温 293.154 K | `build/gpu-demo-build17.log` |
| `material` | 120 步，油水均流动，油层保持在水层上方 | `build/gpu-material-final17.log` |
| `chemistry` | 120 步，酸、碱消耗与生成水各为 754005632 物种量子，水温 292.980 K | `build/gpu-chemistry-final18.log` |
| `air` | 120 步，加压侧液位下降，对侧上升 | `build/gpu-air-build18.log` |
| `vacuum` | 120 步，低压侧液位上升，对侧下降 | `build/gpu-vacuum-final18.log` |
| `tension` | 两种接触角各 200 步；30°／120° 的接触面积分别为 93.517／77.408 m²，低接触角更铺展 | `build/gpu-tension-final17.log` |
| `controller` | 20 步及真实水桶注入、暂停手动推进、舀出、持久事务与续算 | `build/gpu-controller-final17.log` |
| `capture` | 40 步，采样水体并保留世界水块，完成后续状态操作 | `build/gpu-capture-final17.log` |
| `takeover` | 40 步，接管 32 个水源方块、恢复原坐标并释放接管 | `build/gpu-takeover-final17.log` |

主水池 499 个热运行完整应用步的中位耗时为 64.662 ms，P95 为 88.447 ms，最大 146.017 ms；未触发耗时暂停。启动准备与首步导入单独记录。

同次最终构建的设备驻留与七种热相变／膜传质验证通过，记录在 `build/gpu-air-build18.log`，`:fabric:assemble` 成功。容器参考能量与化学抽取聚焦回归分别见 `build/demo-container-temperature.log`、`build/demo-chemical-transfer.log`。静态架构检查仍有一项既有失败：`VulkanLiquidRegion` 1745 行超过 1000 行限制，见 `build/demo-architecture-final.log`。

日志分别记录世界启动时的求解器准备、完整应用步 `offline_scope_ms` 与额外验收导出 `validation_scope_ms`。调度沿用连续三步超过 200 ms 暂停的生产规则。离线耗时未包含真实玩家网络投递、渲染和 Minecraft 世界采样成本；64³／50 ms 保留为待评估目标，本轮以完整流程及演示效果交付。

构建后可复用 `fabric/build/gpu-production-demo-work/classpath.txt` 直接执行 `mmm.simulation.GpuProductionDemoValidation <场景> <步数> complete`。

本机 NVIDIA 驱动缺少安装 INF 声明的 `VulkanDriverName`／`VulkanDriverNameWoW` 注册项，独立 `vulkaninfo` 同样无法发现设备。经用户授权补回两张卡的缺失项后，默认加载器可发现 RTX 5070 Ti 和 RTX 4060 Ti，记录在 `build/vulkan-driver-repair.log`；无需设置驱动路径环境变量。Mod 使用标准 Vulkan 驱动发现，启动失败时提示检查系统驱动。最终构建日志为 `build/gpu-final-assemble.log`。

最终产物在未设置驱动覆盖变量的新进程中通过 `demo 20 complete`，包含实体进出、暂停手动步进、恢复与编辑后续算，日志为 `build/gpu-default-driver-final.log`。

## 控制器界面与交互

2026-10-28，审查覆盖空手激活／潜行切换、探针打开、选择、暂停／继续、刷新、显示设置、生命周期预览／取消／确认、温度源输入，以及容器和比较器关联入口。客户端按请求身份处理回复，实际 Minecraft 窗口未启动。

- `:fabric:compileClientJava`、`protocol,lifecycle` 和 GPU `controller 20 complete` 通过，日志 `build/controller-interaction-validation.log`。覆盖生产摘要及令牌、运行／暂停、性能重试与数值故障保留，以及注液、GPU 推进、手动步进、装取、WAL 与 checkpoint。
- 最终 `protocol,lifecycle` 和 `:fabric:assemble` 通过，日志 `build/controller-interaction-final.log`。响应协议检查主动打开、原请求代次、目标消失与预览关联；生命周期检查运行时阻止破坏操作、暂停后预览和恢复运行使旧预览失效。
- 同次产物运行 `ContainerTransferStoreValidation --rapid-pour` 通过，日志 `build/controller-clear-vent-remove.log`。24 次全能力注液后执行持久清空、排气、恢复、重新注液及最终删除记录，确认保留的空气腔几何不会被误判为有气体库存。实际世界方块释放沿既有归属事务，此测试未执行真实世界释放。

界面源代码已检查换行、滚动、重复提交、窗口缩放后的温度输入保留，以及关闭或切换页面后的迟到回复。实际字体绘制、鼠标命中和玩家网络往返仍需实机观察。单一观察场景为 `/mmm controllerdemo`：探针打开控制器，在较小窗口滚动摘要，暂停／继续，发出刷新后立即关闭；再暂停并依次预览、取消及确认清空／排气／释放／移除。预期摘要完整可读，关闭后操作回复不会重新弹窗，最终移除关闭控制器页面；服务端异常查 `logs/latest.log`，自动化依据为上述三份日志。

<a id="gpu-演示完整流程"></a>
## GPU 生产接入的最新短链

2026-10-27，本轮停止性能优化，完成已有实现、热相变与气液膜传质接入。

- `:gpu-kernel:runGpuSolidLiquidReactionsValidation` 通过：实际析出、精确质量／物种／SVQ、非零动量及 U/K/PE 结算；容量不足的私有候选保留原库存，足够容量下完成发布。日志 `build/gpu-completion-solid.log`。
- `:gpu-kernel:runGpuResidentStateValidation` 通过：ServiceLoader 发现生产后端，连续机械／热步采用设备状态，拒绝候选保留来源，常规查询、展示和显式 checkpoint 导出成功。规定运动实体完成插入、移动、具名反力结果与 checkpoint 恢复后继续推进。
- `:gpu-kernel:runGpuThermalPhaseValidation` 通过：冻结、熔化、汽化、凝结各执行两次热步，随后机械结算、展示及 checkpoint 恢复；总质量与精确内能账闭合。两项集成结果共用日志 `build/gpu-completion-integration.log`。

- `:gpu-kernel:runGpuThermalPhaseValidation` 的七场景运行通过：四种热相变、有限气腔 CO₂ 溶解／析气和外界析气。膜传质检查整数质量与物种守恒、内能账、机械接续、checkpoint 导出与恢复续算；外界源汇恢复后无重复记账。日志 `build/gpu-film-completion.log`。机械状态复制与扩容同时保留气体物种声明表。

主模组默认启用 Vulkan。`build/gpu-final-delivery.log` 记录生产设备状态短链与 `:fabric:assemble` 成功；产物 `fabric/build/libs/mmm-fabric-0.17.0.jar` 内嵌 GPU provider、膜传质 shader 和 Windows x64 运行库。

上述验证覆盖实际设备执行与生产接口。游戏画面、复杂 VoxelShape 和跨游戏重启观察仍沿各自人工场景；性能结论沿用下方原场景证据。

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
