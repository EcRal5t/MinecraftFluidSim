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

两个 `FakeCreate` 类仅存在于测试源码。Create 的局部形状与坐标约定来自适配计划固定的上游提交；当前验证不加载 Create，不涉及真实 Display Link、护目镜、装配拆解或动力网络。`sensor-display,protocol`、`external-motion`、模组构建、GameTest 编译与架构边界检查已通过；日志位于 `build/validation/create-display-motion-build.log` 和 `build/validation/create-external-motion.log`。

### 原位装配与拆解

```powershell
./gradlew.bat :fabric:runHostValidation -PhostValidation=structure-handoff,external-motion :fabric:assemble verifyArchitecture --offline --no-daemon --max-workers=1
```

`ExternalStructureHandoffValidation` 通过正式碰撞刷新入口执行多片与半砖装拆，核对同格无关遮罩、质量、体积、内能、已接受代次及分片身份。装配后的平移使用生产采集和接受进度入口，以测试侧接受体状态推进到目标，再执行拆解；同时检查提前拆解、半程恢复、缺少提供器、部分世界状态、预算和重复交接。运动接受状态由 host 夹具提供，此入口不执行 GPU 运动求解或 Create 世界事件。

此前 `structure-handoff`、`external-motion`、构建与架构检查的日志为 `build/validation/create-structure-handoff.log` 和 `build/validation/create-structure-handoff-final.log`。共享环境合并后，普通墙热库存保存在 `WorldThermalEnvironment`，结构携带库存使用带归属信息的 `CARRIED` 节点；区域热设备为接触投影。验证须覆盖节点和归属的存档往返、GPU 导出、热库存守恒，以及几何与环境原生候选联合采用。合并验证入口：

```powershell
./gradlew.bat :fabric:runHostValidation -PhostValidation=environment,structure-handoff,external-motion :gpu-kernel:runGpuEnvironmentProductionValidation :fabric:assemble verifyArchitecture --offline --no-daemon --max-workers=1
```

实际 Create 生命周期绑定、跨模组存档协调和跨区域库存转移按适配计划继续验证。

空间选区、环境热场、薄壁管道与展示的集成验证通过：模组构建、GameTest 编译、几何与区域存档、区域合并、管网、环境、工业端口、协议及外部结构交接均完成定向验证。GPU 环境生产场景完成 11 个接受步，覆盖流体—墙体—空气传热、开放气流、共享表面库存和共同候选；20 步工业流程完成真实端口事务、机械推进与持久恢复。完整服务器泵送同时覆盖外侧墙体、相变与移动实体交接。最终构建与生产流程日志为 `build/merge-render-final.log`、`build/merge-render-final-flows.log`，泵送结果见下方对应场景。

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

壁面单次接触的参考热导为 26.3583369 W/K，GPU 为 26.3583398 W/K；参考交换热量为 140.208330 J，GPU 为 140.208346 J，有限墙与流体热账闭合。结晶参照为 49 批、2,450 SVQ，实际接受量一致。固定表面一步吸附 1,590 量子、一步解吸 626 量子，均与有限速率参照一致，质量和内能闭合。可逆反应在 5 s 时的解析产物为 66,666.646 量子，GPU 为 66,667。气体绝热路径的压力功积分与内能变化、公开转移持久化检查均通过。

装置诊断使用 `industrial_distillation_probe`、`industrial_heat_probe` 和 `industrial_heat_stone_probe`。参数表示从首次 tick 开始的总步数，包含充液；结束后结算物料与能量并标记 `process_diagnostic_complete`。蒸馏短诊断记录料池和冷凝端的液量、温度、蒸气库存与控制信号，完整流程比较实际有限批次与 Rayleigh 参照。`industrial_distillation_warm` 可按已经完成的冷壁工况时长单独运行暖壁。逆流流程使用 `industrial_heat` 配对比较：铜壁达到 5 K 出口温升后结束供料观察，石墙使用相同时长，两者随后检查停止热料后的蓄热释放；`industrial_heat_metal` 和 `industrial_heat_stone` 可单独运行。

GPU 进程串行运行，复用同一次构建。直接运行 JVM 时将 `java.io.tmpdir` 指向项目 `build` 下的临时目录，场景世界与玩家存档分开保存。

长工艺循环在内存中执行生产容器转移计划，使用真实接触格、逐 tick 配额及实际物料和携能。换热与蒸馏同一 tick 的各端口顺序修改共同宿主候选，并在物理步前统一提交；T01 单独覆盖 BoxActor、WAL、checkpoint 和恢复。小型装置、短诊断与按工艺目标结束的循环共同缩短反馈时间，64³ 完整生产步预算单独计量。

当前已通过物料箱与端口、阀门及双阀泵送、筛分和滤渣收集、冷却结晶与收集回投再溶解、固定表面催化，以及吸附穿透、再生和再吸附流程。逐格物理接受的 12 个热相变与表面交换场景通过，覆盖冻结、熔化、蒸发、冷凝、溶解、析气、混合物、多接触、液体耗尽及吸附／解吸，并检查质量、热账与恢复续算。表面吸附和解吸分别接受 1590、626 μmol，与有限速率参照一致。

逆流场景两侧各装填 500 L，以 2 L/s 对向流动。铜壁装置启动 50.65 s、运行 53.50 s 后，冷侧末次出口由 280 K 升至 285.0005 K，同期收集液平均升温 3.9615 K；UA 为 739.02 W/K，ε-NTU 稳态温升参照为 6.4919 K。石墙在相同 104.15 s 窗口内的末次出口温变为 −0.1511 K，同期平均温变为 −0.1818 K，稳态温升参照为 0.0475 K；短时出口包含充液与相变过程的热响应。停止热料并排空热侧后，铜壁与石墙在 30 s 内分别释放 672.82 kJ、1.33 kJ。两侧组成隔离、实际进出料、流体与有限墙热账均通过。

蒸馏由 100 L、乙醇摩尔分数 23.5911% 的原料开始：第一段在 74.55 s 收集 20.0013 L、35.9052% 的馏分；该桶实际组成与内能进入第二台，在 62.70 s 收集 4.0036 L、53.1433% 的馏分。两段共 137.25 s，外部供热合计 32.7654 MJ。按各段实际切取体积和进料组成计算的常压 Rayleigh 参照分别为 38.0087%、53.0392%。两段有限排气量分别为 1.1972 m³、0.9753 m³ 标准体积，均由预置气体箱容纳；质量、元素、乙醇、容器携能和有限墙热账通过。

参照脚本读取生产物性，按每次 20% 体积切割逐份更新残液和蒸气组成；两次理想切割的串级结果为 38.01%、55.33%。

相同 74.55 s 窗口内，400 K 暖壁仅收集 0.6731 L，285 K 冷壁收集 20.0013 L。暖壁对照累计排气 14.4200 m³ 标准体积，操作中更换已满气体容器后继续。

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
| `chemistry` | 120 | 酸碱连续中和输运与反应热结算 | 反应物等摩尔消耗并生成水，反应热平稳扩散 |
| `capture` | 40 | 采样世界水体并保留原方块 | 采样边界解析正确，支持后续状态操作 |
| `takeover` | 40 | 接管 32 个水源方块并在完成后归还 | 动态世界方块状态无损接管与释放 |

每个接受步检查四相整数质量与元素收支、热账、设备驻留及展示快照。环境交换单独记账，扣除环境交换后的系统质量差为零。调度沿用连续三步超过 200 ms 暂停的生产规则。

### 微量液体与渲染

`droplet`、`droplet-tail` 检查微量液体下落与精确库存保持；`materials,protocol` 检查零体积裁剪片、邻面遮挡、相机介质及客户端顶点。设备极小液量、切格和孔隙场景使用 `runGpuResidentStateValidation`。实际透明排序与光影表现按[展示验证](#fluid-presentation)观察。

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
  - `:fabric:compileClientJava` 编译客户端；`runHostValidation -PhostValidation=protocol` 执行协议编解码、请求代次与客户端几何验证。
  - `:fabric:runGpuProductionDemoValidation -PgpuDemo=controller -PgpuDemoSteps=20` 覆盖生产注液、GPU 推进、手动步进、装取与 WAL/checkpoint 持久化。
  - `ContainerTransferStoreValidation --rapid-pour` 覆盖 24 次连续注液后的持久清空、排气、恢复与重注液。
- 实机观察：执行 `/mmm controllerdemo`，使用探针打开控制器，滚动摘要面板，测试暂停／继续与刷新，依次预览、取消及确认清空、排气与移除。操作回复与生命周期状态保持同步，关闭后不重新弹窗。

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

RTX 5070 Ti、Minecraft 26.2、Iris 1.11.2 和 Sodium 0.9.1：使用 `/mmm demo` 的油水 4 m³ 场景，暂停后执行 `/mmm step 20`，分别在原版、Photon v1.4 和 Complementary Unbound r5.9.3 下从液体外部及内部观察。Photon 设置 `WATER_DISPLACEMENT=false`，Complementary 设置 `WAVING_WATER_VERTEX=false`；F3+T 资源重载成功。编译、`protocol` 和 `verifyArchitecture` 已通过。

运行日志每 10 秒汇总一次。GPU 时间为每次绘制的设备时间；CPU 构建、排序、上传与提交为该窗口内累计耗时。

| 场景 | 主画面 GPU 均值／最大值 | 阴影 GPU 均值 | 网格复用 |
|---|---:|---:|---|
| 原版，19:46:07 | 6／10 μs | — | 1156 次主绘制，顶点重建与上传均为 0 |
| Photon，19:48:40 | 64／654 μs | 10 μs | 顶点重建与上传均为 0 |
| Complementary，19:49:23 | 128／735 μs | 10 μs | 顶点重建与上传均为 0 |

原版移动视角的 19:46:47 窗口执行一次索引排序，上传 18816 B 索引，顶点重建与上传均为 0。首次冷网格构建约 22 ms，预热后的单次构建约 8–13 ms。日志归档于 `build/validation/client-presentation.log`。

进一步人工验证场景（玻璃控制器内腔 4×4×4）：
1. 底部依次加入一桶汞、四桶水、一桶油及少量细沙，观察分层与玻璃侧面界面。
2. 暂停区域，切换组分、pH、温度及色带范围，观察实时换色与遮挡。
3. 移动视角进出液体并靠近界面，核对水下介质判定与内外可见性。
4. 切换上述光影包，核对深度、阴影、折射与水下效果。
5. 执行重载、区域移除与重进，确认 GPU 网格资源正常释放与重建。

### 样品与工艺曲线

`protocol` 覆盖容器分析的质量／摩尔分数与浓度分母、已保存内能推导温度、只读库存、消息长度限制和基准生命周期；同时覆盖传感器历史的容量、暂停、手动步进、不可用断线及区域重建。

实机潜行持水桶对空气使用后打开样品面板，保存基准后正常显示双栏比较。油水演示外壳安装液位传感器，潜行空手右键显示暂停状态和 1.00 s 样本；执行 `/mmm step 2` 后显示 1.10 s 新样本，两次暂停观察之间保留断线。关闭面板、退出世界均正常完成。

多人观察以 `protocol` 和专服 `MultiplayerObservationGameTest.independentObservers` 覆盖会话、请求序号、基准、维度与距离校验。两个模拟玩家读取同一测点，回复保持各自会话标识；关闭、断线和换维的客户端状态由独立状态用例覆盖。范围和证据见[多人观察验证](multiplayer-observation.md)。

### 工业外观与生存获取

工业资源检查纹理引用、物品透明通道及接口六朝向的停止／进料／出料模型。专服 `IndustrialControlGameTest.portAppearanceTracksSettings` 检查接口配置和加载后的标记同步；`SurvivalProgressionGameTest` 使用真实配方管理器检查 15 个配方匹配、产物和配方书触发，并覆盖容器槽存取、保存恢复、拆除掉落和生存工具开采。素材来源见[工业展示素材](visual-assets.md)，玩家操作见[生存模式入门](../gameplay/survival-progression.md)。

定向服务器验证使用测试资源 ID 与独立目录，命令见上方工业装置验证。

完整构建、`protocol` 和 `verifyArchitecture` 通过，日志位于 `build/validation/industrial-survival-build.log`。配方及解锁、接口标记、多人观察、真实容器槽、拆除掉落、生存开采和传感器控制流程均通过专服测试；`containers` 的库存、交接及故障恢复用例通过，包含空槽地址的未决事务锁定。

双阀泵送的完整服务器场景通过。封闭气腔的 1 m³ 活塞行程保持质量并增加气体内能，四次循环从约 50 L 投料中送出 24.09 L；首次循环后完成 checkpoint、求解器卸载和异步恢复，随后完成满腔堵塞、回退解堵和开阀排液。过程检查联合物料和热账，非预期暂停立即结束用例。日志为 `build/merge-render-pump.log`。

<a id="流体显色模式"></a>
### 流体显色模式

组分、逐相 pH 与凝聚相温度沿同代快照展示。光影包可能重算水体基色，以原版渲染和无着色状态对照诊断读数。

<a id="performance"></a>
## 性能和长期场景

完整步计时包含输入、设备执行、提交和必要世界交接；冷初始化、编辑及存档分别计量。比较使用同一硬件、场景、功能及物理时长。场景与当前预算见 [GPU 演示](#gpu-演示完整流程)及[后端参考](../roadmap/performance-and-backends.md)。

<a id="environment-thermal-cost"></a>
### 环境热场成本研究

[EnvironmentThermalCostProbe](../../solver/src/test/java/mmm/thermal/EnvironmentThermalCostProbe.java)测量方块级热场的 CPU 数组更新成本，服务于[空间选区与环境热场方案](../roadmap/region-and-environment-heat-plan.md)。合成场景采用连续铁/空气层和有限热容面交换，固定邻接预先建立；每次计时包含内能复制、温度计算、全部面热量及后继缓冲区发布。场景参数由探针维护。

```powershell
javac --release 25 -d build/environment-thermal-cost solver/src/test/java/mmm/thermal/EnvironmentThermalCostProbe.java
java -cp build/environment-thermal-cost mmm.thermal.EnvironmentThermalCostProbe
```

2026-11-10，Intel Core i5-13400F、JDK 26.0.2，预热后单次运行结果如下。立方盒尺寸按世界方块计；32³ 方块对应现有 64³／0.5 m 机械域。数组内存为持久原始数组净载荷，构图临时数组与对象开销另计。

| 盒尺寸／外层厚度 | 热格数 | 相邻面数 | 数组 MiB | 构图 ms | 更新 p50 ms | p95 ms | 最大 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| 32³／2 格 | 13,888 | 34,704 | 1.059 | 5.052 | 0.0535 | 0.0571 | 0.0821 |
| 32³／4 格 | 31,232 | 85,824 | 2.501 | 5.839 | 0.1371 | 0.1432 | 0.1560 |
| 64³／4 格 | 111,104 | 305,472 | 8.899 | 9.846 | 0.5240 | 0.5815 | 0.7930 |

同次三节点水—铁—空气计算的温度变化分别为 −0.000598071 K、+0.000628216 K、+0.0415795 K，总能量存储差为 0 J。该短检查针对同量反号交接与温变方向。上述测量范围为环境外层的 CPU 更新，未包含选区内固定方块、复杂形状空气分量、世界采样、GPU 提交与传输、持久化和主仿真。

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

这里 `d_iron/k_iron + d_water/k_water = 0.4280602007 m²K/W`。正的 `Q` 从水传给铁。若只存在这条接触，以 `t = 0.05 s` 为对照时：

| 几何 | `G` W/K | 解析 `Q` J | 水温变化 K | 铁温变化 K |
|---|---:|---:|---:|---:|
| 满水格，湿面积 0.25 m² | 0.5840300023 | 1.660105229 | −3.17419738×10⁻⁶ | +4.25668007×10⁻⁷ |
| 80% 水，湿面积 0.20 m² | 0.4672240019 | 1.328084184 | −3.17419738×10⁻⁶ | +3.40534406×10⁻⁷ |

固定膜厚和材料时，热导随实际接触面积成正比；一个 0.25 m × 0.25 m 的全湿微面具有 `G = 0.1460075006 W/K`。内部微面及部分墙面应按真实相交面积累计。绝热方块与绝热玻璃的导热率均为 0，因此穿过绝热固体的 `G = 0`。

铁—环境空气接触单独采用 `G = A / (d_iron/k_iron + 1/h_air)`；取 `h_air = 5 W/(m²·K)`、`A = 1 m²` 得 `G = 4.761904762 W/K`。孤立铁墙初温 350 K、空气初温 293.15 K 时，以上同一指数解在 0.5 s 给出 `Q ≈ 135.171106 J`，铁降温约 `3.46593×10⁻⁵ K`，空气升温约 `0.156165 K`。空气温度仍在 300 K 以下，可使用表内同一段 `Cᵥ`。生产 GPU 夹具中的部分接触直接设定 `G = 5 W/K`，其理论值须按该输入另算；其他空气邻面或干气接触同样须纳入总热流。

#### 生产 GPU 的独立理论检查

[GpuEnvironmentTheoryValidation](../../gpu-kernel/src/test/java/mmm/gpu/GpuEnvironmentTheoryValidation.java)通过正式设备候选推进，参照 [ThermalTheoryOracle](../../gpu-kernel/src/test/java/mmm/gpu/ThermalTheoryOracle.java) 的两体指数解、三体容量加权矩阵指数解和串联热阻。解析参照独立于生产离散推进公式。

```powershell
./gradlew.bat :gpu-kernel:runGpuEnvironmentTheoryValidation -PgpuProbeDevice=0 --offline --console=plain --max-workers=1
```

此夹具采用 19.5 kg 薄铁片，体积 0.0025 m³、厚度 0.01 m、热容 9750 J/K，接触面积 0.25 m²；水为满 0.5 m 求解格。它与上表完整铁方块的几何和热容分别计算。2026-11-10 的生产 GPU 结果：

| 检查 | GPU 实测 | 独立理论 | 误差 |
|---|---:|---:|---:|
| 水—薄铁片首步平均热流，1 s | 33.98605 W | 33.98817 W | 0.00625% |
| 水—薄铁片 20 s 累计传热 | 679.31778 J | 679.33899 J | 0.00312% |
| 薄铁片—有限空气，0.5 s、0.25 m² | 35.487857 J | 35.499544 J | 0.0329% |
| 异质热容串联稳态 | 27.537171 W | 27.540984 W | 0.01384% |

串联稳态包含两个不同热容节点及两端恒温边界，从解析稳态初始化后运行四个 0.5 s 步，温度漂移为 0 K。面积减半的环境接触传热为 17.743929 J，绝热接触为 0 J。内部单微面面积为 0.0625 m²，20 s 累计传热实测 169.912989 J，解析值 169.914316 J；步长减半使相对误差从 0.00078% 降至 0.00039%。水—铁及水—铁—空气曲线同样检查步长减半后误差下降；所有场景同时核对联合能量账。

环境推进使用全图共同稳定系数，保持不同材料之间的稳态热阻比例。该近似会减小有效时间步，极小热容节点会使整个环境图的瞬态响应变慢；环境温度采用低频粗尺度近似，传热速率检查需保留实际热容与时间步。

环境集成的目标为每主步新增平均 ≤0.5 ms、p95 ≤1 ms，低频环境扩散事件新增成本 p95 ≤1 ms；性能判定保持下节原完整步预算。上方 CPU 数组结果用于热网格算术成本参照，生产 GPU 完整步单独计量。

#### GPU 完整步实测

[GpuEnvironmentMechanicalBenchmark](../../gpu-kernel/src/test/java/mmm/gpu/GpuEnvironmentMechanicalBenchmark.java)使用 64³ 个 0.5 m 求解格、6144 面世界墙、24576 个墙面接触、31232 个环境节点和 85824 条扩散连接。水／油、局部气体、颗粒库存布局、热化学和相变路径启用，实际颗粒为空，外缘为完整墙壳。开放气流的功能检查由 `runGpuEnvironmentProductionValidation` 单独覆盖。

```powershell
./gradlew.bat :gpu-kernel:runGpuEnvironmentMechanicalBenchmark -PgpuProbeDevice=0 -PgpuEnvironmentWarmup=10 -PgpuEnvironmentSamples=60 --offline --console=plain --max-workers=1
```

2026-11-10，RTX 5070 Ti：单个常驻区域先从同一已提交状态计算无共享环境的基线候选并丢弃，再计算和提交环境耦合候选。每 10 步合入一次 0.5 s 环境扩散。预热 10 对后采样 60 对，避免同时保留两个 64³ 区域的显存成本。

| 事件 | 样本数 | 基线均值／p95 ms | 耦合均值／p95 ms | 配对增量均值／p95 ms |
|---|---:|---:|---:|---:|
| 普通完整步 | 54 | 54.0107／58.9131 | 53.7501／59.2112 | −0.2606／3.0117 |
| 合并环境扩散 | 6 | 54.7681／56.0488 | 54.8975／57.0274 | 0.1294／1.5812 |

普通步 CPU 命令录制平均增加 0.1145 ms，新增提交次数为 0。完整步和 GPU 计时存在配对波动，负增量按噪声记录。当前完整步 50 ms 目标、普通步与低频扩散新增 p95 ≤1 ms 目标均未达到；6 个扩散事件是短样本。世界和区域能量账在测量后闭合。以上计时包含正式设备候选、热账和提交，世界采样、冷初始化、扩容导入、存档及绘制另计。

本次联合回归通过 `region-state,containers,ownership,lifecycle,protocol,environment`、`runGpuRegionExpansionValidation`、`verifyArchitecture` 和 `:fabric:build`。GPU 扩容覆盖边缘库存观察、不同布局候选的丢弃与采用、新空气交接，以及旧液体、气体和面动量保持。生产热场检查覆盖共享墙与表面、外部库存编辑、气流携能及拒绝候选；理论任务覆盖热流数值、稳态、面积、绝热和时间步收敛。

### 完整生产步预算

原 64³／0.5 m 切格场景采用 50 ms 预算，每步推进完整 0.05 s。保留水／油、局部气体与生产热化学定义，显式选择实际设备，预热后连续采样。

```powershell
./gradlew.bat :gpu-kernel:runGpuLiquidMechanicalBenchmark '-PgpuProbeDeviceName=RTX 5070 Ti' -PgpuMechanicalGrids=64 -PgpuMechanicalScene=cut -PgpuMechanicalGas=true -PgpuMechanicalThermal=true -PgpuMechanicalProduction=true -PgpuMechanicalWarmup=5 -PgpuMechanicalSamples=50 --offline --console=plain --max-workers=1
```

记录完整步墙钟中位数、P95、最大值、GPU 时间、提交次数、显存和实际推进时长。设备以日志中的 `Device` 为准，名称参数按本机显卡调整。该输入的颗粒布局启用、实际颗粒与床层为空；32³／256 颗粒输入单独评价。Minecraft 世界操作、展示与冷初始化分列，长流程使用上方生产演示和组合工艺入口。

当前气液交接采用逐轮接受量直接结算和两遍热量分配，几何路由在设备生成。64³／0.5 m 输入已完成预热 5 步及连续 50 步的相变热分配续算，每步完整推进 0.05 s，均为一个机械子步。最终版本的 50 ms 预算、中位数、P95、最大值及设备内存等待独占 RTX 5070 Ti 复测；预算测量期间关闭同卡游戏和渲染任务。并行客户端运行期间的墙钟样本用于流程观察。

32³／256 颗粒完整生产输入使用 `dam` 场景，预热 5 步后连续推进 10 步，每步 0.05 s。设备路由实现每步上传 0 B、平均回读 340.8 B，样本提交次数为 16—36 次，实际颗粒持续运动并进入沉积与相变交接。该运行与 Minecraft 客户端共用显卡，独占耗时另行采样。运行入口沿用上方命令，将网格改为 32、场景改为 `dam`，并设置 `-PgpuMechanicalGranular=256`；分项诊断增加 `-PgpuMechanicalStages=true`。

当前实现通过 12 项相变与表面场景、连续生产推进、驻留状态和完整活塞泵 GameTest。相变场景核对精确质量、能量账、机械续算及 checkpoint；驻留场景覆盖冷导入、失败候选隔离、移动实体、开放排气和机械／热学续算；活塞泵覆盖双阀泵送、checkpoint 恢复、满腔受阻与回退解堵。构建及测试源码编译通过。对应入口输出为 `build/optimization-heat-validation.log`、`build/optimization-routes-resident.log`、`build/optimization-routes-pump.log` 和 `build/optimization-routes-particles.log`。

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
