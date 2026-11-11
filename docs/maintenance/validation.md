# 验证指南

先明确当前需要回答的问题与成本预期。
有 95% 把握的改动跳过额外验证、校验和测试；物理主干改动按范围选择完整步生产预算，展示改动分别记录快照与客户端成本。
有具体未知风险时，从下列入口选择能回答该问题的最小场景，复用同一次构建产物。

| 任务 | 先读章节 | 入口 |
|---|---|---|
| 排错某物理现象 | [仿真能力验证](#simulation-capabilities)中对应职责的任务表；装置内出现意外吸热或冷凝时读[工业装置](#工业装置)的装置诊断 | `:gpu-kernel:runGpu*`；`ApparatusDiagnostics` |
| 改工业装置 | [工业装置](#工业装置)、[实际容器组合工艺](#实际容器组合工艺) | `:fabric:runGpuProcessFlowsValidation -PgpuProcessScenario=...` |
| 改 GPU 着色器 | [GPU 短链与单元验收](#gpu-短链与单元验收)、[GPU 内核基准](#gpu)、[完整生产步预算](#完整生产步预算) | `:gpu-kernel:` 验证与基准任务 |
| 改交互、演示或客户端 | [GPU 演示与场景验证](#gpu-演示完整流程)、[演示与客户端交互自动化](#演示与客户端交互自动化) | `:fabric:runDemoInteractionValidation`、`:fabric:runGameTest` |
| 改类职责或模块依赖 | [架构与编译检查](#architecture) | `verifyArchitecture` |

最新运行结果集中在[当前验证基线与状态](#当前验证证据)。

<a id="gpu-演示完整流程"></a>
## GPU 演示与场景验证

演示重放入口为 Gradle 任务 `:fabric:runGpuProductionDemoValidation`，使用生产 Vulkan 后端、场景初始化、调度策略、快照恢复与库存事务。游戏内对应工作台交互命令 `/mmm demo` 与控制器命令 `/mmm tankdemo`。

```powershell
./gradlew.bat :fabric:runGpuProductionDemoValidation -PgpuDemo=<场景> -PgpuDemoSteps=<步数> --console=plain
```

物理与组合工艺的验证入口见[仿真能力验证](#simulation-capabilities)和[稀疏空间域验证](#spatial-domain)。普通墙、箱与端口转移、规定运动、局部气体及统一相间交换沿同一生产候选推进；组合流程使用实际物料容器、取样、提取和 checkpoint 续算。

游戏内使用 `/mmm plant <场景>` 建造工业装置，物料箱和端口完成持续进出料，探针配置阀门、筛与测点。规定运动沿 `/mmm process drive <秒数>` 和机械驱动器执行；`/mmm process sample` 读取局部库存，`/mmm process stop` 停止驱动。区域状态使用 `/mmm regions checkpoint` 保存。

### Fabric 物品自动化

物料箱事务用例通过 Fabric Knot 加载实际数据组件和事务补丁，只初始化测试所需注册表，不启动客户端、服务器或世界。运行时依赖 Fabric mixin，使用独立任务：

```powershell
./gradlew.bat :fabric:runMaterialBoxStorageValidation --offline --no-daemon --max-workers=1
```

已覆盖九槽容量、六面及无方向 provider、缺失方块实体、三相容器完整载荷、默认水桶与含热水桶、内部身份隔离、嵌套提交和回滚、模拟跨箱搬运、槽位锁及失效视图。容器后态重建另检查自定义名称、显式删除组件、旧库存字段清除和恢复幂等。普通破坏与爆炸的前置保护已核对 Minecraft 调用顺序，世界事件场景留待服务器集成验证。

Create 源码用于核对标准存储调用链；本项目当前没有 Create 26.2 依赖。上述结果覆盖 Fabric 接入基础，实际 Create 漏斗、机械臂和其他绑定按 [Create 适配计划](../roadmap/create-compatibility-plan.md) 验收。

内置端口使用独立的 `industrial-port` 逻辑场景：先填满 1024 个公共 actor 注册名额，再通过生产 `transferBatch` 执行实际有限批次，检查配额、换批身份、提交前存盘失败及 HEAD 后故障恢复。另以 1 μmol 化学样品和 1 nL 配额检查可解析但无法分割的请求：保存回调为零，体积、质量、内能、HEAD 和 WAL 均保持不变。该入口不启动游戏世界或推进泵场景。

```powershell
./gradlew.bat :fabric:runHostValidation -PhostValidation=industrial-port --offline --no-daemon --max-workers=1
```

### 测点显示与外部运动结构

```powershell
./gradlew.bat :fabric:runHostValidation -PhostValidation=sensor-display,external-motion --offline --no-daemon --max-workers=1
```

`sensor-display` 检查传感器生产快照、单位、状态、区域身份、重配置及重载，以及客户端初始摘要和增量同步。`FakeCreateDisplayValidation` 作为只读消费者，检查读取不改写红石输出和历史。

`external-motion` 运行已有实体流体联动用例，并通过 `FakeCreateMotionValidation` 调用正式提供器采集、结构整体验证与接受进度入口。覆盖不同供帧时长、稳定分片身份、实体去重、域外进入、分段欠帧、反转、拒绝重试、256 片段总预算、扫掠途中不可用、恢复和退出重放。自动 tick 对输入拒绝的等待重试路径通过源码核对；此入口不运行服务器调度或 GPU。

两个 `FakeCreate` 类仅存在于测试源码。Create 的局部形状与坐标约定来自适配计划固定的上游提交；当前验证不加载 Create，不涉及真实 Display Link、护目镜、装配拆解或动力网络。`sensor-display,protocol`、`external-motion`、模组构建、GameTest 编译与架构边界检查均已通过。

### 原位装配与拆解

```powershell
./gradlew.bat :fabric:runHostValidation -PhostValidation=structure-handoff,external-motion :fabric:assemble verifyArchitecture --offline --no-daemon --max-workers=1
```

`ExternalStructureHandoffValidation` 通过正式碰撞刷新入口执行多片与半砖装拆，核对同格无关遮罩、质量、体积、内能、已接受代次及分片身份。装配后的平移使用生产采集和接受进度入口，以测试侧接受体状态推进到目标，再执行拆解；同时检查提前拆解、半程恢复、缺少提供器、部分世界状态、预算和重复交接。运动接受状态由 host 夹具提供，此入口不执行 GPU 运动求解或 Create 世界事件。

`structure-handoff`、`external-motion`、构建与架构检查均已通过。共享环境合并后，普通墙热库存保存在 `WorldThermalEnvironment`，结构携带库存使用带归属信息的 `CARRIED` 节点；区域热设备为接触投影。验证须覆盖节点和归属的存档往返、GPU 导出、热库存守恒，以及几何与环境原生候选联合采用。合并验证入口：

```powershell
./gradlew.bat :fabric:runHostValidation -PhostValidation=environment,structure-handoff,external-motion :gpu-kernel:runGpuEnvironmentProductionValidation :fabric:assemble verifyArchitecture --offline --no-daemon --max-workers=1
```

实际 Create 生命周期绑定、跨模组存档协调和跨区域库存转移按适配计划继续验证。

空间选区、环境热场、薄壁管道与展示的集成验证通过：模组构建、GameTest 编译、几何与区域存档、区域合并、管网、环境、工业端口、协议及外部结构交接均完成定向验证。GPU 环境生产场景完成 11 个接受步，覆盖流体—墙体—空气传热、开放气流、共享表面库存和共同候选；20 步工业流程完成真实端口事务、机械推进与持久恢复。完整服务器泵送同时覆盖外侧墙体、相变与移动实体交接，泵送结果见下方对应场景。

### 工业装置

工业流程使用 `IndustrialLayout` 的方块布局和生产容器事务。箱、端口、筛、热墙及表面材料加载正式数据，逐步输出来自已提交库存。端口额度按在线 tick 计算，离线夹具与游戏入口共用配额和实际接触格选择。

```powershell
./gradlew.bat :fabric:runGpuProcessFlowsValidation '-PgpuProcessScenario=industrial,industrial_valve,industrial_screen,industrial_crystallization,industrial_heat,industrial_distillation,industrial_adsorption,industrial_catalysis' -PgpuProcessSteps=20 --console=plain --max-workers=1
./gradlew.bat :fabric:runGameTest --console=plain --max-workers=1
```

`industrial` 负责有限批次进出料、气体载荷与恢复，`industrial_valve` 负责真实开口和接受面流量；筛分、结晶、隔墙换热、蒸馏、吸附再生和催化分别由对应场景连续推进。蒸馏使用 `industrial_distillation_cascade` 执行两次实际馏分交接，`industrial_distillation_single` 比较单级冷壁与暖壁。各流程按目标产物或温升结束，运行上限由场景维护。

日常定位先使用单步定量入口：`GpuThermalContactsBenchmark` 比较实际湿壁热导与有限交换热量，`GpuSolidLiquidReactionsValidation <device> direct` 比较析出量与溶解度、扩散和整数批次，`GpuThermalPhaseValidation` 的 `surface` 模式分别比较吸附和解吸量，`GpuLiquidReactionsValidation <device> 2` 比较可逆动力学与有限时间解析解。各入口读取当前生产定义或自身声明的物理夹具参数。

```powershell
./gradlew.bat :gpu-kernel:runGpuThermalContactsBenchmark -PgpuThermalContactsGrid=4 -PgpuThermalContactsWarmup=0 -PgpuThermalContactsSamples=1 --offline --no-daemon --max-workers=1
./gradlew.bat :gpu-kernel:runGpuThermalPhaseValidation -PgpuPhaseCases=surface --offline --no-daemon --max-workers=1
uv run pytool/distillation_reference.py
```

壁面接触热导与有限交换热量同解析解闭合，有限墙与流体热账平衡。结晶析出量与溶解度、扩散速率一致；固定表面吸附与解吸量与有限速率动力学参照一致，质量和内能闭合；可逆化学反应进度与解析解一致；气体绝热路径的压力功积分与内能变化、公开转移持久化检查均通过。

装置诊断使用 `industrial_distillation_probe`、`industrial_heat_probe` 和 `industrial_heat_stone_probe`。参数表示从首次 tick 开始的总步数，包含充液；结束后结算物料与能量并标记 `process_diagnostic_complete`。蒸馏短诊断记录料池和冷凝端的液量、温度、蒸气库存与控制信号；完整流程比较实际有限批次与 Rayleigh 参照。`industrial_distillation_warm` 可按已经完成的冷壁工况时长单独运行暖壁。逆流流程使用 `industrial_heat` 配对比较：铜壁达到 5 K 出口温升后结束供料观察，石墙使用相同时长，两者随后检查停止热料后的蓄热释放；`industrial_heat_metal` 和 `industrial_heat_stone` 可单独运行。

[ApparatusDiagnostics](../../fabric/src/test/java/mmm/simulation/ApparatusDiagnostics.java) 为任意 `IndustrialLayout` 装置定位意外的吸热面和冷凝面。投料前用区域、世界注册表和布局构造，记录每台有限墙设备的初始内能；运行中调用 `print(前缀, 阈值J)`，输出两类行：`<前缀>_device rel=x, y, z id=... dE_J=...` 列出能量变化超过阈值的墙体，坐标相对布局原点；`<前缀>_block rel=x,y,z liquid_m3=...` 列出外壳内部存液超过 1 cm³ 的方块。蒸馏与逆流诊断场景每 400 步以 2×10⁵ J 阈值调用一次，新装置诊断照此加入一行调用。墙体材料的选取规则见[方块热材料](../architecture/thermal-state.md#方块热材料)。

GPU 进程串行运行，复用同一次构建。直接运行 JVM 时将 `java.io.tmpdir` 指向项目 `build` 下的临时目录，场景世界与玩家存档分开保存。

长工艺循环在内存中执行生产容器转移计划，使用真实接触格、逐 tick 配额及实际物料和携能。换热与蒸馏同一 tick 的各端口顺序修改共同宿主候选，并在物理步前统一提交；T01 单独覆盖 BoxActor、WAL、checkpoint 和恢复。小型装置、短诊断与按工艺目标结束的循环共同缩短反馈时间，64³ 完整生产步预算单独计量。

当前已通过物料箱与端口、阀门及双阀泵送、筛分和滤渣收集、冷却结晶与收集回投再溶解、固定表面催化，以及吸附穿透、再生和再吸附流程。逐格物理接受的 12 个热相变与表面交换场景通过，覆盖冻结、熔化、蒸发、冷凝、溶解、析气、混合物、多接触、液体耗尽及吸附／解吸，并检查质量、热账与恢复续算。表面吸附和解吸均与有限速率动力学参照一致。

逆流场景两侧各装填 500 L，以 2 L/s 对向流动，核对双侧组成隔离、出口温升趋势以及停止供料后的有限墙蓄热释放，流体与有限墙热账闭合。

蒸馏由 100 L 含乙醇原料开始，乙醇摩尔分率 0.236。单级冷壁约 50 s 切取 20 L，馏分 0.359，Rayleigh 平衡切割参照 0.380，釜残 0.204；暖壁同时长只冷凝约 1 L。串级第二台以第一台实际馏分为进料，29 s 切取 4 L，馏分 0.466，参照 0.530，两段共计 79 s。冷凝热进入铁质冷墙，外壳、填充与压力传感器均为绝热材料；传感器改用石材时成为塔顶分凝面，第二台馏分升至 0.512。有限排气量由预置气体箱容纳，质量、元素、容器携能和有限墙热账守恒闭合。


GameTest 在实际服务器中运行传感器红石控制、箱端口装取、方块实体设置恢复、筛的放置转向拆除、原版活塞运动与双阀泵送。默认测试世界位于 `fabric/build/gametest/world`，独立于玩家存档。重复测试可用 `-PgameTestRunDir=build/gametest-<本轮名称>` 选择全新目录，避免已有区域占用测试世界的区域额度。路径相对于 `fabric` 模块；GPU 场景与游戏服务器串行运行。

定向运行时，将 `fabric-api.gametest.filter` 设为完整测试资源 ID。下面的命令使用新的测试目录，结束后恢复原有环境变量：

```powershell
$previousGameTestOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$previousGameTestOptions -Dfabric-api.gametest.filter=mmm_gametest:industrial_control_game_test_sensor_controls_finite_port".Trim()
    ./gradlew.bat :fabric:runGameTest '-PgameTestRunDir=build/gametest-sensor-example' --offline --no-daemon --max-workers=1
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousGameTestOptions
}
```

### 演示运行与边界检查

水箱三配方、水流池、油水、大型溃坝和常压／加压／低压气管通过 120 步完整流程，包含暂停、手动步、checkpoint 恢复和编辑后续算。控制器完成 20 步完整流程；微量液滴完成短重放，世界水体采样完成 40 步恢复与释放。工作台预设切换、重置、移除重建、协议和生命周期检查均通过。

气压初始化在修改气体库存前结算待推进相变。宿主气体挤排按连通域有效气容进行线性供需匹配，保存实际物种、内能和动量交接；大场景采用 3 GiB Java 堆。设备接受体积描述和尾数保留至提交后流量观测。工业夹具从实际布局和区域原点推导阀门、筛面及端口坐标。阀门完整开闭对照通过：开启时流量与出液为正，关闭时均为零，质量闭合。

### 演示选择器与场景参数

- `-PgpuDemo`：指定场景，支持 `tank`、`flow`、`ambient`、`air`、`vacuum`、`controller`、`precipitation`、`tension`、`demo`、`material`、`chemistry`、`capture`、`takeover`、`droplet`、`droplet-tail`。
- `-PgpuDemoSteps`：推进步数（省略时使用场景默认步数）。
- `-PgpuDemoStage`：阶段选择器，默认 `complete`（包含物理推进、暂停、手动步进、导出恢复与编辑后续算）；可选 `initial`、`motion`、`scheduled` 用于故障分段定位。
- `-PgpuDemoLiquidDiagnostic=true`：逐步输出单元库存、上下格面速度和液体连通情况，适合追踪空中薄层；默认关闭。连通按有库存单元的六邻接估算，支撑按下方固定碰撞估算；另列无固定碰撞的微量悬空单元数与实际库存体积。每步同时保留实际体积和整数质量，结合前后步变化判断运动，诊断导出不计入推进耗时。

| 场景 (`gpuDemo`) | 步数 | 物理过程与验证要点 | 验收指标 |
|---|---|---|---|
| `tank` | 120 | 依次重置为清水、油水、稀酸碱三种生产配方；室温常压复位与真实桶量事务 | 清水下落铺开、油水分层、酸碱中和并生成水，质量守恒闭合 |
| `flow` | 500 | 水向空侧扩散；实体进入、移动、离开及交互扰动 | 自由面平稳推进，实体受力反馈正确 |
| `ambient` | 500 | 小型 U 管双侧连通水柱常压平衡 | 两侧液位保持接近，主体温度稳定 |
| `air` | 500 | 同一 U 管左气腔加压 | 加压侧液位下降、对侧上升 |
| `vacuum` | 500 | 同一 U 管左气腔降压 | 低压侧液位上升、对侧下降 |
| `controller` | 20 | 真实水桶注液、舀出、WAL 与 checkpoint 导出及恢复 | 事务前后库存严格守恒，GPU 状态无损续算 |
| `precipitation` | 500 | 高浓度酸碱中和反应、结晶析出、沉淀清空及待位移恢复 | 固液相变严格守恒，颗粒占容与排水正确，清空后连续续算 |
| `tension` | 200 | 相同初态对比 30° 与 120° 润湿接触角液滴 | 30° 充分铺展，120° 保持收缩，接触面积符合预期 |
| `demo` | 500 | 大型溃坝（24×16×24 空间，324 m³ 水量），实体进出扰动 | 水体坍塌铺展平稳，质心单调下降，水温保持稳定 |
| `material` | 120 | 油水双相分层流动 | 密度差驱动自然分层，轻相稳定保持在重相上方 |
| `chemistry` | 120 | 酸碱连续中和输运与反应热结算 | 水的反应净增量等于酸的净消耗量，碱的净消耗量包含碳酸氢根形成，质量与元素账闭合 |
| `capture` | 40 | 采样世界水体并保留原方块 | 采样边界解析正确，支持后续状态操作 |
| `takeover` | 40 | 接管 32 个水源方块并在完成后归还 | 动态世界方块状态无损接管与释放 |

每个接受步检查四相整数质量与元素收支、热账、设备驻留及展示快照。环境交换单独记账，扣除环境交换后的系统质量差为零。调度沿用连续三步超过 200 ms 暂停的生产规则。

### 微量液体与渲染

`droplet`、`droplet-tail` 检查微量液体下落与精确库存保持；`materials,protocol` 检查零体积裁剪片、邻面遮挡、相机介质及客户端顶点。设备极小液量、切格和孔隙场景使用 `runGpuResidentStateValidation`。实际透明排序与光影表现按[展示验证](#fluid-presentation)观察。

<a id="高浓度析盐"></a>
### 高浓度析盐

高浓度析盐复用真实水箱与桶投料流程，以超过当前温度溶解度的实际试剂库存验证颗粒析出、反应热、恢复与沉淀清空。普通酸碱配方保持稀溶液的中和与 pH 展示。

```powershell
./gradlew.bat :fabric:runGpuProductionDemoValidation -PgpuDemo=precipitation -PgpuDemoSteps=500 --console=plain --max-workers=1
```

- 核心流程：清水、油水、高浓度酸碱各运行 500 步，包含暂停、手动单步、checkpoint 恢复续算、清空沉淀及后续清水续算。
- 相变待位移状态恢复：附加 Java 参数 `-Dmmm.demoPhaseRestore=true` 执行 `mmm.simulation.GpuProductionDemoValidation precipitation 120 complete`。在生成颗粒时捕捉 GPU 待位移状态并恢复至宿主，校验精确体积余量与接触约束。
- 验收指标：每个接受步检查四相整数质量、物种摩尔数、元素收支及内能账闭合；沉淀析出与溶解体积交接正确，全程质量差为零。

### 控制器交互与界面

控制器交互覆盖空手激活、潜行切换、探针交互、运行与暂停、生命周期预览／取消／确认、温度源输入、清空／排气／释放／移除以及比较器关联。

- 自动化验证：
  - `:fabric:compileClientJava` 编译客户端；`runHostValidation -PhostValidation=protocol` 执行协议编解码、请求代次与客户端几何验证。
  - `:fabric:runGpuProductionDemoValidation -PgpuDemo=controller -PgpuDemoSteps=20` 覆盖生产注液、GPU 推进、手动步进、装取与 WAL/checkpoint 持久化。
  - `ContainerTransferStoreValidation --rapid-pour` 覆盖 24 次连续注液后的持久清空、排气、恢复与重注液。
- 实机观察：执行 `/mmm tankdemo`，使用探针打开控制器，滚动摘要面板，测试暂停／继续与刷新，依次预览、取消及确认清空、排气与移除。操作回复与生命周期状态保持同步，关闭后不重新弹窗。

### 演示与客户端交互自动化

```powershell
./gradlew.bat :fabric:compileClientJava :fabric:runHostValidation '-PhostValidation=protocol,lifecycle,boundary,region-manager' :fabric:runDemoInteractionValidation --console=plain --max-workers=1
```

`DemoInteractionValidation` 驱动正式工作台请求，覆盖预设切换、重置、开始、暂停、单步、移除和重建，以及操作失败后的继续使用。`protocol` 覆盖请求代次、客户端几何和错误回复，`lifecycle,boundary,region-manager` 覆盖区域操作、外壳扫描与目录规则。

游戏内使用 `/mmm demo` 观察鼠标命中、字体排版、显色及实际绘制；依次操作水箱、水流池与气压预设，保存后重进检查库存和运行意图。光影与资源重载使用下方单一人工场景。

### GPU 短链与单元验收

生产 Vulkan 后端接入的短链单元验证任务：

- `:gpu-kernel:runGpuSolidLiquidReactionsValidation`：固液析出、精确质量与物种 SVQ、非零动量及内能结算；容量不足时保留候选库存。
- `:gpu-kernel:runGpuResidentStateValidation`：生产 ServiceLoader 发现、设备状态连续推进、实体运动插入与 checkpoint 导出恢复。
- `:gpu-kernel:runGpuThermalPhaseValidation`：冻结、熔化、汽化、凝结热相变，有限气腔 CO₂ 溶解与外界析气膜传质；检查整数质量、物种与内能账闭合。

<a id="simulation-capabilities"></a>
## 仿真能力验证

按修改的物理职责选择已有入口，检查整数物料收支、能量账、实际容量及正常流程。完整工艺通过正式容器投料和取样，并在 checkpoint 恢复后继续推进。

<a id="spatial-domain"></a>
### 稀疏空间域与材料数据

```powershell
./gradlew.bat :gpu-kernel:runGpuSpatialDomainValidation :gpu-kernel:runGpuLiquidDiffusionValidation --offline --console=plain --max-workers=1
```

空间域场景比较域内与跨块的同一通道，检查共享通量、压力耦合、热交换、暂停组及恢复。扩散场景覆盖末份物种、垂向携能，并通过修改世界 JSON 定义检查宿主库存和 GPU 物性使用同一数据。

### 壁面、传热、反应与颗粒

| 修改范围 | GPU 任务 | 判定重点 |
|---|---|---|
| 湿壁与通道 | `runGpuWetWallValidation`、`runGpuChannelFlowValidation` | 实际湿面积、黏度／长度／截面的流量趋势、耗散热 |
| 实体作用 | `runGpuEntityBodyCouplingBenchmark` | 真实接触、反冲及带符号外功 |
| 有限墙与导热 | `runGpuThermalContactsBenchmark`、`runGpuThermalConductionBenchmark` | 同量反号热交换、有限热容、热浴收支及分段物性 |
| 竞争与可逆反应 | `runGpuLiquidReactionsValidation` | 共享反应物、温度反馈、整数进度与恢复 |
| 颗粒与床层 | `runGpuGranularTransitionsBenchmark`、`runGpuSolidLiquidReactionsValidation` | 沉降、唤醒、新床生成、占容与固液交接 |
| 活动组 | `runGpuActivityMaskValidation` | 活动侧推进、暂停侧库存与动量保持 |

上述任务均位于 `:gpu-kernel:`，按改动选择运行。

### 实际容器组合工艺

```powershell
./gradlew.bat :fabric:runGpuProcessFlowsValidation -PgpuProcessScenario=all -PgpuProcessSteps=20 '-PgpuProbeDeviceName=RTX 5070 Ti' --offline --console=plain --max-workers=1
```

入口包含有限墙加热、CO₂ 吸收脱除、晶种结晶、双挥发组分蒸发冷凝、碱液碳化与规定运动驱动。检查实际进出料、取样、停止、checkpoint 与恢复续算；质量和物种按整数核对，热账包含外部热源及机械功。

蒸馏沿热池、气道与普通冷墙推进，从投料开始通过冷凝段完整开放截面上的液体端口收集实际馏分。全部产物共同构成馏分，比较原料和产品组成、冷墙吸热、累计冷凝物种及装置与容器的完整收支。具体步数、配方与夹具由 [GpuProcessFlowsValidation](../../fabric/src/test/java/mmm/simulation/GpuProcessFlowsValidation.java) 维护。

<a id="cpu"></a>
<a id="host"></a>
## 宿主事务、存档与协议验证

宿主验证选择器覆盖定义、几何、生命周期、存档、容器、世界归属和协议。连续物理推进使用 GPU 验证入口。多个名称用逗号分隔，`list` 列出选择器；省略或使用 `fast` / `full` 运行当前组合。任务定义见 [fabric/build.gradle](../../fabric/build.gradle)，选择器见 [HostValidationSuite](../../fabric/src/test/java/mmm/validation/HostValidationSuite.java)。

```powershell
./gradlew.bat :fabric:runHostValidation '-PhostValidation=region-state,containers,ownership,lifecycle' --offline --no-daemon --max-workers=1
./gradlew.bat :fabric:runHostValidation -PhostValidation=list --offline --no-daemon
```

改存档和恢复选 `region-state`；改容器或物品 WAL 选 `containers`；改世界归属选 `ownership`；改生命周期选 `lifecycle`；改客户端消息选 `protocol`；改碰撞几何选 `shape`。设备状态与候选交接运行 `:gpu-kernel:runGpuResidentStateValidation`，物理变化按具体 GPU 演示或内核场景选择。只对新增失败模式补充常态用例。

<a id="fluid-presentation"></a>
## 展示几何与光影接入

展示目标见[产品路线](../roadmap/product-roadmap.md#阶段-3展示几何材质与光影接入)；本节维护执行方法、成本口径和实机结果。同一源码状态复用构建产物，针对具体风险选择验证入口。

### 自动化与离线成本

- 沿 `protocol` 检查几何字段、同代接收、迟到帧和移除游标。
- 复用相关几何测试，按改动覆盖薄层、格内液滴、斜切单元、多材料、共享面、朝向、有限坐标和重复重建一致性。
- 重构绑死旧阈值、增厚或网格形状的断言；新增常态测试对应新增失败类型。
- `presentation` 分列快照与网格时间、分配量；编码字节数、客户端排序、上传和 GPU 绘制按实际入口补充测量。

现有选择器命令：

```powershell
./gradlew.bat :fabric:runHostValidation -PhostValidation=protocol --offline --no-daemon
```

客户端 CPU 展示目标为 16 ms；分别计量快照和网格生成，并检查同几何帧与暂停状态的复用。

### 实机画面与性能

在原版、Photon v1.4 和 Complementary Unbound r5.9.3 下，分别从液体外部及内部观察油水多层界面与网格表现。Photon 设置 `WATER_DISPLACEMENT=false`，Complementary 设置 `WAVING_WATER_VERTEX=false`；F3+T 资源重载通过。主画面与阴影网格复用稳定，同几何帧与相机移动时不触发顶点重建与额外上传，冷网格构建与预热后网格耗时均满足客户端展示预算。

进一步人工验证场景（玻璃控制器内腔 4×4×4）：
1. 底部依次加入一桶汞、四桶水、一桶油及少量细沙，观察分层与玻璃侧面界面。
2. 暂停区域，切换组分、pH、温度及色带范围，观察实时换色与遮挡。
3. 移动视角进出液体并靠近界面，核对水下介质判定与内外可见性。
4. 切换上述光影包，核对深度、阴影、折射与水下效果。
5. 执行重载、区域移除与重进，确认 GPU 网格资源正常释放与重建。

### 样品与工艺曲线

`protocol` 覆盖容器分析的质量／摩尔分数与浓度分母、已保存内能推导温度、只读库存、消息长度限制和基准生命周期；同时覆盖传感器历史的容量、暂停、手动步进、不可用断线及区域重建。

实机潜行持水桶对空气使用后打开样品面板，保存基准后正常显示双栏比较。油水演示外壳安装液位传感器，潜行空手右键显示暂停状态和 1.00 s 样本；执行 `/mmm step 2` 后显示 1.10 s 新样本，两次暂停观察之间保留断线。关闭面板、退出世界均正常完成。

多人观察以 `protocol` 和专服 `MultiplayerObservationGameTest.independentObservers` 覆盖会话、请求序号、基准、维度与距离校验。两个独立客户端状态对象分别保存基准和打开请求；两名模拟玩家读取同一测点，回复保持各自会话标识与独立请求序号。测试覆盖不同会话、过期回复、超出距离、维度不符、关闭、断线及换维清理。显示模式保存在客户端进程内，渲染读取本进程的独立设置。

### 工业外观与生存获取

工业资源检查纹理引用、物品透明通道及接口六朝向的停止／进料／出料模型。专服 `IndustrialControlGameTest.portAppearanceTracksSettings` 检查接口配置和加载后的标记同步；`SurvivalProgressionGameTest` 使用真实配方管理器检查 15 个配方匹配、产物和配方书触发，并覆盖容器槽存取、保存恢复、拆除掉落和生存工具开采。素材来源见[工业展示素材](visual-assets.md)，玩家操作见[生存模式入门](../gameplay/survival-progression.md)。

定向服务器验证使用测试资源 ID 与独立目录，命令见上方工业装置验证。

完整构建、`protocol` 和 `verifyArchitecture` 通过。配方及解锁、接口标记、多人观察、真实容器槽、拆除掉落、生存开采和传感器控制流程均通过专服测试；`containers` 的库存、交接及故障恢复用例通过，包含空槽地址的未决事务锁定。

双阀泵送的完整服务器场景通过。封闭气腔的 1 m³ 活塞行程保持质量并增加气体内能，四次循环从约 50 L 投料中送出 24.09 L；首次循环后完成 checkpoint、求解器卸载和异步恢复，随后完成满腔堵塞、回退解堵和开阀排液。过程检查联合物料和热账，非预期暂停立即结束用例。

<a id="流体显色模式"></a>
### 流体显色模式

组分、逐相 pH 与凝聚相温度沿同代快照展示。光影包可能重算水体基色，以原版渲染和无着色状态对照诊断读数。

<a id="performance"></a>
## 性能和长期场景

完整步计时包含输入、设备执行、提交和必要世界交接；冷初始化、编辑及存档分别计量。比较使用同一硬件、场景、功能及物理时长。场景与当前预算见 [GPU 演示](#gpu-演示完整流程)及[后端参考](../roadmap/performance-and-backends.md)。

<a id="environment-thermal-cost"></a>
### 环境热场成本研究

[EnvironmentThermalCostProbe](../../solver/src/test/java/mmm/thermal/EnvironmentThermalCostProbe.java)测量方块级热场的 CPU 数组更新成本，服务于[空间选区与环境热场方案](../archive/proposals/region-and-environment-heat-plan.md)。合成场景采用连续铁/空气层和有限热容面交换，固定邻接预先建立；每次计时包含内能复制、温度计算、全部面热量及后继缓冲区发布。场景参数由探针维护。

```powershell
javac --release 25 -d build/environment-thermal-cost solver/src/test/java/mmm/thermal/EnvironmentThermalCostProbe.java
java -cp build/environment-thermal-cost mmm.thermal.EnvironmentThermalCostProbe
```

环境热网格的 CPU 数组更新开销在 64³ 尺度下保持在 1 ms 以内，远低于 50 ms 主物理步预算；能量存储严格守恒。该测量针对环境外层的 CPU 算术成本，选区内固定方块、世界采样与 GPU 步进分别计量。

#### 独立热响应对照

下列算例用常物性两体的连续时间解析解作为参考，比较实际温度曲线和热账；它不使用生产步进的有限时间离散公式。算例隔离指定接触，不连接其他空气格、热源或远场，并关闭相变、反应和运动。水初温为 350 K，铁初温为 293.15 K。每个热容和面积采用 SI 单位，Minecraft 方块边长为 1 m，求解格长 `h = 0.5 m`。

| 载体 | 数据定义和质量 | 热容及参考内能 |
|---|---|---|
| 水 | 密度 1000 kg/m³，导热率 0.598 W/(m·K)；满求解格 0.125 m³、125 kg，80% 装液为 0.1 m³、100 kg | 比热 4184 J/(kg·K)，对应 523000 或 418400 J/K。293.15 K 的材料参考比内能为 0；纯水 H₂O 的物种参考偏移为 −57300 J/mol，摩尔质量为 0.018 kg/mol。按理想量近似，100 kg 水在 293.15 K 时 `U ≈ −318333333.33 J`；350 K 时 `U ≈ −294547293.33 J`。实际组成按整数 μmol 和质量编码计算。 |
| 铁墙 | 一个完整方块取 7800 kg，导热率 50 W/(m·K) | 比热 500 J/(kg·K)，`C = 3900000 J/K`；293.15 K 的参考比内能为 146575 J/kg，对应 `U = 1143285000 J`。 |
| 环境空气 | 1 m³ 标准体积、101325 Pa、293.15 K；标准组成 N₂ 0.78958、O₂ 0.21、CO₂ 0.00042 | 质量约 1.19919268 kg。采用气体定义的定容比热 `Cᵥ`，293.15 K 的混合总热容约 865.56655 J/K；三种材料与其物种在此参考温度的参考能均为 0。实际整数库存有量化误差。 |

完整铁墙中心到接触面距离为 0.5 m。对水—墙接触，选取水的自由液面水平、接触墙面竖直且液体沿墙面法向贯穿整格的几何：满格的湿面积为 0.25 m²；80% 装液的水平液面高度为 0.4 m，湿面积为 0.20 m²。这两种几何的液柱中心到墙面距离均为 `h/2 = 0.25 m`；一般斜向或局部 PLIC 几何须按实际湿面积及液柱体积求距离，装液比例不直接指定湿面积。

串联热阻和两体解析解为：

```text
G = A / (d_iron / k_iron + d_water / k_water)                 [W/K]
C_eff = C_water C_iron / (C_water + C_iron)                  [J/K]
Q(t) = C_eff (T_water,0 − T_iron,0)
       × (1 − exp[−G (1/C_water + 1/C_iron) t])              [J]
ΔT_water = −Q/C_water,  ΔT_iron = Q/C_iron                    [K]
```

这里 `d_iron/k_iron + d_water/k_water = 0.4281 m²·K/W`。正的 `Q` 从水传给铁。若只存在这条接触，以 `t = 0.05 s` 为对照时：

| 几何 | `G` W/K | 解析 `Q` J | 水温变化 K | 铁温变化 K |
|---|---:|---:|---:|---:|
| 满水格，湿面积 0.25 m² | 0.5840 | 1.6601 | −3.174×10⁻⁶ | +4.257×10⁻⁷ |
| 80% 水，湿面积 0.20 m² | 0.4672 | 1.3281 | −3.174×10⁻⁶ | +3.405×10⁻⁷ |

固定膜厚和材料时，热导随实际接触面积成正比；一个 0.25 m × 0.25 m 的全湿微面具有 `G = 0.1460 W/K`。内部微面及部分墙面应按真实相交面积累计。绝热方块与绝热玻璃的导热率均为 0，因此穿过绝热固体的 `G = 0`。

铁—环境空气接触单独采用 `G = A / (d_iron/k_iron + 1/h_air)`；取 `h_air = 5 W/(m²·K)`、`A = 1 m²` 得 `G = 4.762 W/K`。孤立铁墙初温 350 K、空气初温 293.15 K 时，以上同一指数解在 0.5 s 给出 `Q ≈ 135.17 J`，铁降温约 `3.47×10⁻⁵ K`，空气升温约 `0.156 K`。空气温度仍在 300 K 以下，可使用表内同一段 `Cᵥ`。生产 GPU 夹具中的部分接触直接设定 `G = 5 W/K`，其理论值须按该输入另算；其他空气邻面或干气接触同样须纳入总热流。

#### 生产 GPU 的独立理论检查

[GpuEnvironmentTheoryValidation](../../gpu-kernel/src/test/java/mmm/gpu/GpuEnvironmentTheoryValidation.java)通过正式设备候选推进，参照 [ThermalTheoryOracle](../../gpu-kernel/src/test/java/mmm/gpu/ThermalTheoryOracle.java) 的两体指数解、三体容量加权矩阵指数解和串联热阻。解析参照独立于生产离散推进公式。

```powershell
./gradlew.bat :gpu-kernel:runGpuEnvironmentTheoryValidation -PgpuProbeDevice=0 --offline --console=plain --max-workers=1
```

此夹具采用 19.5 kg 薄铁片，体积 0.0025 m³、厚度 0.01 m、热容 9750 J/K，接触面积 0.25 m²；水为满 0.5 m 求解格。它与上表完整铁方块的几何和热容分别计算。
通过正式设备候选推进，参照 ThermalTheoryOracle 的指数解和串联热阻。水—薄铁片、薄铁片—有限空气以及异质热容串联稳态的相对误差均小于 0.05%，能量账闭合；步长减半时离散误差单调下降。

串联稳态包含两个不同热容节点及两端恒温边界，从解析稳态初始化后运行四个 0.5 s 步，温度漂移为 0 K。水—铁及水—铁—空气曲线同样检查步长减半后误差下降；所有场景同时核对联合能量账。

环境推进使用全图共同稳定系数，保持不同材料之间的稳态热阻比例。该近似会减小有效时间步，极小热容节点会使整个环境图的瞬态响应变慢；环境温度采用低频粗尺度近似，传热速率检查需保留实际热容与时间步。

环境集成的目标为每主步新增平均 ≤0.5 ms、p95 ≤1 ms，低频环境扩散事件新增成本 p95 ≤1 ms；性能判定保持下节原完整步预算。上方 CPU 数组结果用于热网格算术成本参照，生产 GPU 完整步单独计量。

#### GPU 完整步实测

[GpuEnvironmentMechanicalBenchmark](../../gpu-kernel/src/test/java/mmm/gpu/GpuEnvironmentMechanicalBenchmark.java)使用 64³ 个 0.5 m 求解格、6144 面世界墙、24576 个墙面接触、31232 个环境节点和 85824 条扩散连接。水／油、局部气体、颗粒库存布局、热化学和相变路径启用，实际颗粒为空，外缘为完整墙壳。开放气流的功能检查由 `runGpuEnvironmentProductionValidation` 单独覆盖。

```powershell
./gradlew.bat :gpu-kernel:runGpuEnvironmentMechanicalBenchmark -PgpuProbeDevice=0 -PgpuEnvironmentWarmup=10 -PgpuEnvironmentSamples=60 --offline --console=plain --max-workers=1
```

单个常驻区域在 64³ 网格下，普通完整步与周期性环境扩散步的 P95 均满足 50 ms 预算。环境热场耦合增量平均 ≤0.5 ms，低频扩散步新增 ≤1 ms，能量账闭合。
生产热场连续推进覆盖共享墙与表面、外部库存编辑、气流携能和环境扩散，能量收支闭合。区域扩容、协议、生命周期和热传导理论检查沿用已通过且未受本轮变更影响的结果。

### 完整生产步预算

原 64³／0.5 m 切格场景采用 50 ms 预算，每步推进完整 0.05 s。保留水／油、局部气体与生产热化学定义，显式选择实际设备，预热后连续采样。

```powershell
./gradlew.bat :gpu-kernel:runGpuLiquidMechanicalBenchmark '-PgpuProbeDeviceName=RTX 5070 Ti' -PgpuMechanicalGrids=64 -PgpuMechanicalScene=cut -PgpuMechanicalGas=true -PgpuMechanicalThermal=true -PgpuMechanicalProduction=true -PgpuMechanicalWarmup=5 -PgpuMechanicalSamples=50 --offline --console=plain --max-workers=1
```

记录完整步墙钟中位数、P95、最大值、GPU 时间、提交次数、显存和实际推进时长。设备以日志中的 `Device` 为准，名称参数按本机显卡调整。该输入的颗粒布局启用、实际颗粒与床层为空；32³／256 颗粒输入单独评价。Minecraft 世界操作、展示与冷初始化分列，长流程使用上方生产演示和组合工艺入口。

64³／0.5 m 切格与复杂几何场景采用 50 ms 生产预算（对应 20 TPS 下单步 0.05 s 物理时长）。预热后完整步墙钟中位数与 P95 均稳定在 50 ms 预算内。32³／256 颗粒场景在活动颗粒运动、沉积与相变交接中持续推进并保持预算。

当前实现通过 12 项相变与表面场景、连续生产推进、驻留状态流程和完整活塞泵 GameTest。相变场景核对精确质量、能量账、机械续算及 checkpoint；驻留场景覆盖冷导入、失败候选隔离、移动实体、开放排气和机械／热学续算。有限热墙检查覆盖热导、实际换热量及热浴容量增长。双阀活塞泵完成 4 个循环，并通过 checkpoint 恢复、满腔受阻与回退解堵；Fabric 成品构建及测试源码编译通过。

<a id="architecture"></a>
## 架构与编译检查

修改类职责、依赖或模块位置时运行：

```powershell
./gradlew.bat verifyArchitecture --offline --no-daemon
```

`verifyArchitecture` 是仓库根任务，会调用 `:fabric:runArchitectureValidation`；
架构检查直接使用该根任务，不要写成 `-PhostValidation=architecture` 或 `:fabric:verifyArchitecture`。

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

生产物理主干使用 Vulkan。内核和底层任务定义在 [kernel 构建文件](../../gpu-kernel/build.gradle)。

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

### 底层驱动排查

设备状态交接使用 `:gpu-kernel:runGpuResidentStateValidation`，实现变更按受影响的设备场景选择对应任务。Windows 环境若 `vkCreateInstance` 返回 `-9` 或无可用设备，可设置 `$env:VK_LOADER_DEBUG='error,warn,driver'`，核对显卡驱动及 ICD 路径。

气体留容与实体排水使用 `:gpu-kernel:runGpuLiquidCapacityInputsValidation`：直接运行生产输入及容量内核，检查静止实体占容后的跨单元排水、有限气体正干容、整数守恒及总孔容不足时的拒绝。实体插入、移动、同布局编辑与存档恢复的完整交接由 resident 场景覆盖。

## 需要进入游戏时

当问题依赖实际画面、Minecraft 方块形状或磁盘重启过程时，准备一个可重复场景，写明操作、预期现象和需要保留的日志。发布构建、产物校验和部署步骤见 [验收与发布](../roadmap/acceptance-and-release.md)。

## 单方块物理管道

[管道架构与验证入口](../architecture/physical-pipes.md)记录四种薄壁管材、管阀和管筛的真实几何、独立管腔库存、区域端口交接及共享环境热场。宿主选择 `shape,pipe-network,lifecycle,environment,protocol`；独立管腔使用 `:solver:runPipeLiquidTransportValidation`、`:solver:runPipeWallHeatValidation`。前者覆盖有限分支流量、压差、高差、气液相变、冻结区块和守恒账，后者覆盖真实有限管壁与混合气液弹性能。普通区域保留 GPU 流动、沉积和热场回归，独立管网使用自己的生产求解入口验证。

<a id="当前验证证据"></a>
## 当前验证基线与状态

| 验证领域 | 主要任务 / 入口 | 覆盖范围与关键断言 | 状态 |
|---|---|---|---|
| **GPU 物理主线** | `:gpu-kernel:runGpuResidentStateValidation`<br>`:fabric:runGpuProductionDemoValidation` | 驻留状态推进、同布局导入/导出恢复、四相物料与内能账闭合、12 类热相变与表面交换 | 通过 |
| **宿主事务与持久化** | `:fabric:runHostValidation -PhostValidation=region-state,containers,ownership,lifecycle` | 区域 HEAD 双槽原子提交、WAL 恢复、容器物品转移、世界方块接管与释放 | 通过 |
| **工业装置流程** | `:fabric:runGpuProcessFlowsValidation`<br>`:fabric:runGameTest` | 进出料端口配额、阀门流量、换热壁面、蒸馏串级、吸附再生与双阀泵送 | 通过 |
| **展示与光影兼容** | `:fabric:runHostValidation -PhostValidation=protocol`<br>客户端实机观察 | 顶点与索引生成、原版/Photon/Complementary 渲染与阴影、水下介质判定 | 通过 |
| **架构与依赖隔离** | `verifyArchitecture`<br>`:fabric:compileClientJava` | 纯 Java 模块隔离、行数限制（<1000 行）、客户端/服务端编译解耦 | 通过 |
