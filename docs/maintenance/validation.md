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

### 工业装置

工业流程使用 `IndustrialLayout` 的方块布局和生产容器事务。箱、端口、筛、热墙及表面材料加载正式数据，逐步输出来自已提交库存。端口额度按在线 tick 计算，离线夹具与游戏入口共用配额和实际接触格选择。

```powershell
./gradlew.bat :fabric:runGpuProcessFlowsValidation '-PgpuProcessScenario=industrial,industrial_valve,industrial_screen,industrial_crystallization,industrial_heat,industrial_distillation,industrial_adsorption,industrial_catalysis' -PgpuProcessSteps=20 --console=plain --max-workers=1
./gradlew.bat :fabric:runGameTest --console=plain --max-workers=1
```

`industrial` 负责有限批次进出料、气体载荷与恢复，`industrial_valve` 负责真实开口和接受面流量；筛分、结晶、隔墙换热、蒸馏、吸附再生和催化分别由对应场景连续推进。蒸馏两阶段可分别用 `industrial_distillation_single` 和 `industrial_distillation_reflux` 运行，前者比较单级冷壁与暖壁，后者比较三层回流与关闭回流。各流程自带所需运行时长，按本轮改动选择受影响场景。

日常蒸馏定位使用 `industrial_distillation_probe` 或 `industrial_distillation_closed_probe`，分别观察开启和关闭回流。`gpuProcessSteps` 在短诊断中表示从首次 tick 开始的总步数，包含预装；结束后结算完整物料与能量并标记 `process_diagnostic_complete`。输出包含实际启动／运行步数、推进秒数、产物、液体体积回收比例，以及准备、端口事务、完整物理步和观察的分项耗时。完整蒸馏入口保留生产运行窗口与分离判定。

```powershell
./gradlew.bat :fabric:runGpuProcessFlowsValidation -PgpuProcessScenario=industrial_distillation_probe -PgpuProcessSteps=20 --offline --no-daemon --max-workers=1
uv run pytool/distillation_reference.py
```

三层塔启动段的最新 20 步短诊断推进 1 s 物理时间，分项合计 37.19 s；每 tick 平均准备 43.32 ms、端口事务及查询 1776.16 ms、完整物理推进 36.41 ms、观察 3.80 ms，质量、元素、携能和有限墙收支通过。该记录覆盖实际端口持久事务；区域创建、JVM 启动和最终结算单列。后续优先处理同一代重复状态读取与逐端口完整候选交接的结构成本。

当前已通过物料箱与端口、阀门及双阀泵送、筛分和滤渣收集、冷却结晶与收集回投再溶解、固定表面催化，以及吸附穿透、再生和再吸附流程。局部相平衡下的 11 个热相变场景均已通过，覆盖冻结、熔化、蒸发、冷凝、溶解、析气、混合物和多接触，并检查质量、热账与恢复续算。

隔墙换热完成金属与石墙的连续进出料、停热排空和蓄热释放。两侧产物保持盐分隔离，金属墙的冷侧升温高于石墙；停热后隔墙从内能峰值分别释放约 35.95 kJ 和 10.68 kJ，完整质量与进出料能量收支通过。

单级蒸馏冷壁收集约 16.1 L，乙醇摩尔分数由原料的 23.6% 提高至 38.3%，釜底为 21.9%；暖壁收集约 0.97 L。三层塔开启回流时收集约 37.61 L、实际回流约 42.23 L，关闭回流时回流量为零；在相近回收比例下，产物乙醇摩尔分数分别为 43.30% 和 32.49%。两种三层工况的各层最低有效液柱均超过 0.94 m，升气、降液、物料、元素、进出料携能和有限墙热账全部通过。各蒸馏工况从首次投料开始保留完整产物，收集物种量受实际累计冷凝量约束。

工艺效能按明确工况评价。上述蒸馏比较包含启动产物，匹配的液体体积回收比例约为累计投料的 0.0889%；回流量统计窗口为正式运行段。数据驱动脚本在无非凝气、101.325 kPa、每次完全平衡并冷凝的条件下，给出乙醇摩尔分数 39.92%、59.11%、76.08%、87.59% 的四次接触参照。实际塔层为池面接触，有限冷壁与气相排放共同影响分离。当前金属隔墙短窗口的冷侧平均升温约 0.00325 K，以 60 K 入口温差计算的温升有效度约 0.00542%。后续工艺评价同时记录回收率、各层组成、冷热流量、有限墙内能和同一时间窗口内的热耗。平衡级与回流条件参照 [LearnChemE](https://learncheme.com/quiz-yourself/interactive-self-study-modules/mccabe-thiele-diagrams/mccabe-thiele-diagrams-summary/)。

GameTest 在实际服务器中运行传感器红石控制、箱端口装取、方块实体设置恢复、筛的放置转向拆除、原版活塞运动与双阀泵送。测试世界位于 `fabric/build/gametest/world`，独立于玩家存档。GPU 场景与游戏服务器串行运行。

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

蒸馏沿热池、气道与普通冷墙推进，从投料开始按集液格温度控制真实容器收集。全部产物共同构成馏分，比较原料和产品组成、冷墙吸热、累计冷凝物种及装置与容器的完整收支。具体步数、配方与夹具由 [GpuProcessFlowsValidation](../../fabric/src/test/java/mmm/simulation/GpuProcessFlowsValidation.java) 维护。

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

<a id="流体显色模式"></a>
### 流体显色模式

组分、逐相 pH 与凝聚相温度沿同代快照展示。光影包可能重算水体基色，以原版渲染和无着色状态对照诊断读数。

<a id="performance"></a>
## 性能和长期场景

完整步计时包含输入、设备执行、提交和必要世界交接；冷初始化、编辑及存档分别计量。比较使用同一硬件、场景、功能及物理时长。场景与当前预算见 [GPU 演示](#gpu-演示完整流程)及[后端参考](../roadmap/performance-and-backends.md)。

### 完整生产步预算

原 64³／0.5 m 切格场景采用 50 ms 预算，每步推进完整 0.05 s。保留水／油、局部气体与生产热化学定义，显式选择实际设备，预热后连续采样。

```powershell
./gradlew.bat :gpu-kernel:runGpuLiquidMechanicalBenchmark '-PgpuProbeDeviceName=RTX 5070 Ti' -PgpuMechanicalGrids=64 -PgpuMechanicalScene=cut -PgpuMechanicalGas=true -PgpuMechanicalThermal=true -PgpuMechanicalProduction=true -PgpuMechanicalWarmup=5 -PgpuMechanicalSamples=50 --offline --console=plain --max-workers=1
```

记录完整步墙钟中位数、P95、最大值、GPU 时间、提交次数、显存和实际推进时长。设备以日志中的 `Device` 为准，名称参数按本机显卡调整。该输入的颗粒布局启用、实际颗粒与床层为空；32³／256 颗粒输入单独评价。Minecraft 世界操作、展示与冷初始化分列，长流程使用上方生产演示和组合工艺入口。

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
