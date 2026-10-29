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

- `-PgpuDemo`：指定场景，支持 `tank`、`flow`、`ambient`、`air`、`vacuum`、`controller`、`precipitation`、`tension`、`demo`、`material`、`chemistry`、`capture`、`takeover`、`droplet`、`droplet-tail`。
- `-PgpuDemoSteps`：推进步数（省略时使用场景默认步数）。
- `-PgpuDemoStage`：阶段选择器，默认 `complete`（包含物理推进、暂停、手动步进、导出恢复与编辑后续算）；可选 `initial`、`motion`、`scheduled` 用于故障分段定位。
- `-PgpuDemoLiquidDiagnostic=true`：逐步输出单元库存、上下格面速度和液体连通情况，适合追踪空中薄层；默认关闭。连通按有库存单元的六邻接估算，支撑按下方固定碰撞估算；另列无固定碰撞的微量悬空单元数与实际库存体积。每步同时保留实际体积和整数质量，结合前后步变化判断运动，诊断导出不计入推进耗时。

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

### 悬空水片与渲染回归

2026-10-29，全程使用源码分析和无头 Vulkan 重放，未启动 Minecraft 或使用 computer-use。

- `materials,protocol`：零体积片不发射表面、不裁掉真实邻面、不触发水下介质；正厚度片保留。客户端材料、颗粒顶点和负坐标相机锚点补偿通过。
- `:gpu-kernel:runGpuResidentStateValidation`：极小液量与极小气量覆盖全开放单元、固定碰撞与颗粒孔隙率；验证体积及小液量表面积，连同设备提交、恢复通过。
- `:gpu-kernel:runGpuLiquidQuantityValidation`、`:gpu-kernel:runGpuInventoryTransportValidation`：整数数量与库存输运通过。
- `droplet`、`droplet-tail` 各 40 步：一个容量量子和半个容量量子的水分别从单元 y=3 落到 y=0，整数质量始终为 1，体积精确不变。调用方式与其他生产场景相同。
- `tank`、`air`、`flow`、`precipitation` 各 120 步完整流程通过；水箱与析盐各包含三个配方。水箱三配方及加压 U 管结束时悬空库存为零；水箱未出现相同悬空库存连续四步不变。每步质量、元素与能量收支通过，含环境交换的场景按源汇结算。

同一批构建产物在 RTX 5070 Ti 上串行重放，非冷导入完整步的中位数／P95 分别为：水箱 16.01／21.49 ms、气压 16.51／22.30 ms、水流 23.75／35.29 ms、析盐 37.10／102.71 ms。析盐最大单步 243.12 ms，所有场景均未触发连续三步超过 200 ms 的暂停。诊断导出与逐步断言在计时之外。

64³ 几何阶段抽查（`runGpuLiquidGeometryBenchmark -PgpuGeometryGrids=64 -PgpuGeometrySamples=3`）中位数：混合溃坝 1.60 ms、部分切格混合物 15.60 ms；该入口不包含完整物理步。日志为 `build/surface-cpu.log`、`build/surface-droplet.log`、`build/surface-tail.log`、`build/surface-*-fixed.log`、`build/surface-delivery.log`。源码检查确认透明提交时点、Iris 顶点格式及主画面／阴影坐标；实际画面与跨批次透明排序效果尚未验收。

`:fabric:build` 通过，产物已部署到指定游戏版本的 `mods/mmm-fabric-0.17.0.jar`，SHA-256 为 `805C2B33E9981A40B0D8DEDDCFF422D6A7C06FCC3370A4F99FA0CA711D8DA433`。`verifyArchitecture` 仍被未改动的 `VulkanLiquidRegion` 1796 行超过 1000 行限制阻塞。

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

2026-10-29，以下命令通过。客户端渲染修复使用 Minecraft 26.2 的图集定义 ID，保留纹理绑定 ID；测试执行实际几何发射，不创建 Minecraft 窗口或图形设备。

```powershell
./gradlew.bat :fabric:compileClientJava :fabric:runHostValidation '-PhostValidation=protocol,lifecycle,boundary,region-manager' :fabric:runDemoInteractionValidation :fabric:assemble --console=plain --max-workers=1
```

`DemoInteractionValidation` 驱动生产工作台请求，世界读写通过测试适配器提供，区域目录、外壳扫描、会话及演示存档使用正式实现。全部七个预设依次执行重置、开始、暂停、单步、原位重置、移除和异位重建；覆盖权限／世界未就绪、首次放置失败后换位置、旧请求与跨维请求、远离场景、单步失败、配方失败、临时场景替换失败、目标空间及区域重叠拒绝，以及水箱被外部删除后的恢复。失败用例检查原区域、壳体与会话保留，并继续执行成功流程。

`ClientSurfaceValidation` 覆盖材料与颗粒的空帧、诊断显色、组分与阴影顶点字段，及界面共用请求状态的失败后续操作、旧回复拒绝。`protocol` 同时验证控制器错误回复编解码与所需能力协商；`lifecycle,boundary,region-manager` 覆盖区域操作、壳体扫描和目录规则。物理推进沿上述 GPU 场景入口单独验证。

本轮补充验证：`lifecycle,protocol` 通过；生命周期实际写入世界能力包及演示记录后再次启动。`tank 120` 和 `controller 20` 通过，控制器从未标注温度的原版水桶开始，经真实计划、WAL、HEAD、恢复及创造模式取水，核对物质与内能。`ContainerTransferStoreValidation --journal-thermal` 通过，覆盖带独立来源库存的日志与公共转移回执。完整 `containers` 选择器在日志及恢复用例通过后进入较长的 CPU 物理矩阵，本轮终止该部分，未计为全套通过。

小型 U 管常压、加压及低压各连续 500 步通过，均包含暂停单步、导出恢复、编辑后续算和生产超时暂停规则。末态两臂液位差分别约 2 cm、22 cm、18 cm，加压与低压方向相反；主体水温约 293.14 K，质量按环境交换后的整数收支严格闭合。低压重放出现高温微气泡，其气体质量占比很小，主体气温约 300 K；温度验收按实际库存质量加权，同时记录局部极值及其库存占比。

真实方块写入、鼠标命中、字体排版及图形绘制由图形客户端观察。入口为 `/mmm demo`：清水开始后切到油水和酸碱，切换显色，暂停、单步、重置、移除，再切换水流池及三种气压预设。带光影包和资源重载的观察仍单独保留。

2026-10-29 图形客户端复测通过：使用现有 Minecraft 26.2 / Fabric 环境，安装 Iris、Sodium，光影包关闭。独立操作原存档重进、清水重置与运行、暂停单步、油水与酸碱切换、组分／pH 显色、水流池和三种气压预设，观察到实际流体与预期方向的液位响应。普通原版桶能注入，创造空桶取出后得到携带热状态的水桶；控制器摘要及清空预览可读，取消返回正常。场景移除清理壳体和液体，重建清水保存后再次进入仍保持库存与暂停状态。最终加压和低压的两臂高低在默认正面视角可辨认。

保留的轻微表现：普通水桶右击时客户端预测可能短暂显示外部原版水，服务端确认后消失；最终世界方块和区域库存正确。U 管液面有持续小幅波动。以上现象未阻断已测操作。

### GPU 短链与单元验收

生产 Vulkan 后端接入的短链单元验证任务：

- `:gpu-kernel:runGpuSolidLiquidReactionsValidation`：固液析出、精确质量与物种 SVQ、非零动量及内能结算；容量不足时保留候选库存。
- `:gpu-kernel:runGpuResidentStateValidation`：生产 ServiceLoader 发现、设备状态连续推进、实体运动插入与 checkpoint 导出恢复。
- `:gpu-kernel:runGpuThermalPhaseValidation`：冻结、熔化、汽化、凝结热相变，有限气腔 CO₂ 溶解与外界析气膜传质；检查整数质量、物种与内能账闭合。

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

阶段范围与完成条件见实施计划 D01–D03；本节维护执行方法、成本口径及实际证据。
有 95% 把握的改动跳过额外检查，针对具体未知风险选择已有入口，同一源码状态复用构建产物。

### 自动化与离线成本

- D01 沿 `protocol` 检查新几何字段、同代接收、迟到帧和移除游标。
- D02／D03 复用相关几何测试，按改动覆盖薄层、格内液滴、斜切单元、多材料、共享面、朝向、有限坐标和重复重建一致性。
- 重构绑死旧阈值、增厚或网格形状的断言；新增常态测试对应新增失败类型。
- `presentation` 分列快照与网格时间、分配量；编码字节数、客户端排序、上传和 GPU 绘制按实际入口补充测量。

现有选择器命令：

```powershell
./gradlew.bat :fabric:runHostValidation -PhostValidation=protocol --offline --no-daemon
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

完整步计时包含输入、设备执行、提交和必要世界交接；冷初始化、编辑及存档分别计量。比较使用同一硬件、场景、功能及物理时长。场景与当前预算见 [GPU 演示](#gpu-演示完整流程)及[后端参考](../roadmap/performance-and-backends.md)。历史 CPU 预算数据仅适用于原执行条件。

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

## 需要进入游戏时

当问题依赖实际画面、Minecraft 方块形状或磁盘重启过程时，准备一个可重复场景，写明操作、预期现象和需要保留的日志。发布构建、产物校验和部署步骤见 [验收与发布](../roadmap/acceptance-and-release.md)。

<a id="当前验证证据"></a>
## 当前验证基线与状态

2026-10-29 GPU 主线迁移：`runGpuResidentStateValidation` 通过，覆盖设备提交、导出恢复及同布局导入候选的拒绝和采用；`verifyArchitecture` 扫描 613 个文件通过，区域类 722 行、工作区 980 行。运行记录 `build/gpu-mainline-resident.log`。

同日 `:fabric:runHostValidation -PhostValidation=region-state,containers,ownership,lifecycle` 通过，包含容器存储 19 个场景、世界归属 27 个场景及生命周期和区域存档；记录 `build/gpu-mainline-host.log`。这次构建同时编译 Fabric 测试源码。

2026-10-30，主线迁移后的 controller 20 步、tank 120 步、flow 120 步及 precipitation 120 步完整流程通过。覆盖真实容器 WAL、暂停与单步、编辑后续算、实体交互、checkpoint 恢复及沉淀清空；水箱和析盐各运行三种配方。记录分别为 `build/gpu-mainline-controller.log`、`build/gpu-mainline-tank.log`、`build/gpu-mainline-flow.log`、`build/gpu-mainline-precipitation.log`。

同日 `runGpuResidentStateValidation` 和 `runGpuThermalPhaseValidation` 通过，新增覆盖已有实体工作区的同布局编辑候选复用、拒绝和采用；七类热相变保留整数质量、热账、机械续算与 checkpoint。记录为 `build/gpu-mainline-resident-final.log`。

设备故障生命周期测试使用受控后端模拟读回失败，以及 HEAD 发布后导入失败；验证暂停和按已发布库存恢复运行意图。该证据不包含真实 Vulkan 设备丢失注入。

同日 `:gpu-kernel:runGpuActivityMaskValidation :fabric:runHostValidation -PhostValidation=lifecycle` 通过。两个隔离空间分别运行和暂停，机械步与独立热步均确认活动侧有输运或热量变化，暂停侧液体、气体、颗粒、冰及其内能保持，暂停格面动量、实体占位和未决相变保留。分组断言覆盖有限气腔、设备端口、实体扫掠、颗粒接触及区块恢复。记录 `build/gpu-mainline-activity-final.log`。

布局整理后，热相变、固液反应和整数库存输运验证通过，记录 `build/gpu-layout-consolidation.log`、`build/gpu-layout-transport-followup.log`。宿主各模块测试源码、Fabric 客户端编译及架构检查通过，扫描 612 个生产源，记录 `build/gpu-mainline-integration-compile.log`。

热边界温度候选验证通过：取消候选保持源库存与参数，单次采用后热步续算使用新温度；准备过程只回读 4 字节执行状态。环境参数更新保留常驻库存。

已知数值问题：resident 的实体续算曾间歇报告容量 `INFEASIBLE`，实际执行 14 轮，上限 512 轮，未触及轮数上限。原版和整理后的黏性 shader 隔离重放均通过；随后三次开启失败捕获的重放也均通过，未取得失败残量图。原因未定，未调整预算或数值参数。记录 `build/gpu-mainline-cell-observation.log`、`build/entity-capacity-attempt-1.log` 至 `-3.log`。

2026-10-30 最终统一交付构建通过：`assemble verifyArchitecture :fabric:runHostValidation :gpu-kernel:runGpuActivityMaskValidation :gpu-kernel:runGpuThermalPhaseValidation :fabric:runGpuProductionDemoValidation`，选择 `hostValidation=region-state,containers,ownership,lifecycle`、`gpuDemo=flow`、`gpuDemoSteps=120`、`gpuDemoStage=complete`，使用 `--offline --no-daemon --console=plain --max-workers=1`。共 59 秒，39 个任务中 20 个复用产物；架构扫描 611 个生产源通过。水流场景完成实体交互、保存恢复、暂停手动单步、库存编辑及续算；双组与七类热相变均通过。记录 `build/gpu-mainline-delivery.log`，发布产物 `fabric/build/libs/mmm-fabric-0.17.0.jar`。
