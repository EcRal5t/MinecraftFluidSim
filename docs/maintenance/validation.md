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

本轮 S1—S8 的功能证据集中在[仿真能力验证](#simulation-capabilities)和[稀疏空间域验证](#spatial-domain)。普通墙、周期投料、规定运动、局部气体及统一相间交换沿同一生产候选推进；组合流程使用实际物料容器、取样、提取和 checkpoint 续算。

游戏内工艺入口为 `/mmm process feed <间隔tick>`、`/mmm process drive <秒数>`、`/mmm process sample`、`/mmm process stop`。机械驱动器方块安装在区域外壳，点击启动、潜行点击停止；区域状态使用 `/mmm regions checkpoint` 保存。

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

水流及小型 U 管常压、加压、低压场景各连续 500 步通过，包含实体交互、暂停单步、导出恢复及编辑后续算。U 管加压与低压的液位变化方向相反，常压两臂保持接近；质量按环境交换后的整数收支闭合。气体物性增量更新后水流 500 步通过，记录 `build/gas-caloric-formal-flow.log`；U 管记录 `build/final-pressure-air.log`、`build/final-pressure-ambient.log` 和 `build/final-pressure-vacuum.log`。

真实方块写入、鼠标命中、字体排版及图形绘制由图形客户端观察。入口为 `/mmm demo`：清水开始后切到油水和酸碱，切换显色，暂停、单步、重置、移除，再切换水流池及三种气压预设。带光影包和资源重载的观察仍单独保留。

2026-10-29 图形客户端复测通过：使用现有 Minecraft 26.2 / Fabric 环境，安装 Iris、Sodium，光影包关闭。独立操作原存档重进、清水重置与运行、暂停单步、油水与酸碱切换、组分／pH 显色、水流池和三种气压预设，观察到实际流体与预期方向的液位响应。普通原版桶能注入，创造空桶取出后得到携带热状态的水桶；控制器摘要及清空预览可读，取消返回正常。场景移除清理壳体和液体，重建清水保存后再次进入仍保持库存与暂停状态。最终加压和低压的两臂高低在默认正面视角可辨认。

保留的轻微表现：普通水桶右击时客户端预测可能短暂显示外部原版水，服务端确认后消失；最终世界方块和区域库存正确。U 管液面有持续小幅波动。以上现象未阻断已测操作。

### GPU 短链与单元验收

生产 Vulkan 后端接入的短链单元验证任务：

- `:gpu-kernel:runGpuSolidLiquidReactionsValidation`：固液析出、精确质量与物种 SVQ、非零动量及内能结算；容量不足时保留候选库存。
- `:gpu-kernel:runGpuResidentStateValidation`：生产 ServiceLoader 发现、设备状态连续推进、实体运动插入与 checkpoint 导出恢复。
- `:gpu-kernel:runGpuThermalPhaseValidation`：冻结、熔化、汽化、凝结热相变，有限气腔 CO₂ 溶解与外界析气膜传质；检查整数质量、物种与内能账闭合。

<a id="simulation-capabilities"></a>
## 仿真能力补全验证

<a id="spatial-domain"></a>
### 稀疏空间域与材料数据

空间域对比入口使用正式 Vulkan 后端和世界 JSON 定义：

```powershell
./gradlew.bat :gpu-kernel:runGpuSpatialDomainValidation --offline --console=plain --max-workers=1
```

2026-11-09，RTX 5070 Ti 上运行 8 步，将相同流动与温差从单块内部平移到跨块通道。稀疏 L 形域实际驻留 1152 格，包围盒为 2304 格；缺失块保持缺失。两份整数质量守恒，跨块液体转移 3.12082 个归一化格容；对应单元最大体积差为 0.000185132 格容、温差为 0.00349018 K。中途恢复 checkpoint 后继续推进；人为卸载跨块通路一侧时，整个物理组的液体、气体、内能及 MAC 动量保持不变。日志 `build/s8-followup.log` 中此任务通过，同批 Fabric 测试编译的结果单独登记。

设备候选、拒绝、采用、查询、展示、实体运动及 checkpoint 的 `runGpuResidentStateValidation` 通过，记录 `build/s8-runtime.log`。同批颗粒沉积、再启动和松弛通过，固体 SVQ 与整数质量保持不变。`runGpuActivityMaskValidation` 通过，活动组正常推进，暂停组保存各相库存、热量和动量，记录 `build/s8-host-activity.log`。

六条工艺使用跨计算块的通道与投料位置，沿已有入口运行；冷凝为 1200 步。每条流程检查容器投料、物质与能量收支、停止、取样、保存与恢复续算，全部通过。加热、析出、冷凝及驱动记录于 `build/s8-process.log`，吸收与碳化记录于 `build/s8-process-targeted.log`。冷壁样品为 4.114 mL，乙醇摩尔分率从原料的 0.23591 提高到 0.34829。

每次 `motion.step` 的墙钟耗时包含冷导入首步，容器 WAL、采样、存档和绘制在计时外：

| 工艺 | 步数 | 中位数 ms | P95 ms | 最大 ms |
| --- | ---: | ---: | ---: | ---: |
| 加热 | 20 | 17.000 | 31.501 | 220.106 |
| 吸收与脱除 | 40 | 18.030 | 39.472 | 227.822 |
| 结晶 | 20 | 37.991 | 77.464 | 118.604 |
| 蒸发与冷凝 | 1200 | 12.148 | 16.826 | 1643.850 |
| 碳化 | 20 | 17.255 | 37.300 | 153.322 |
| 规定运动驱动 | 40 | 13.429 | 20.759 | 101.643 |

小场景仍有冷导入及相变长步尖峰，后续集中优化。完整步流程与原 64³ 后端预算分别评价。

`:gpu-kernel:runGpuLiquidDiffusionValidation` 通过：修改世界定义中的水密度、液体及氮气热容，宿主实际库存和 GPU 物性表随定义更新；同一入口的有限扩散、末份溶质、垂向动量和携能交接通过。记录 `build/s8-data-diffusion.log`。

宿主 `region-state` 整组通过，包含实际缺块的 L 形域发布 HEAD、读取 NBT 并恢复液体、局部气体、颗粒、热状态及非零共享面动量；完整库存总账与块成员保持一致。世界自定义物性、能力包和启动恢复同批通过，记录 `build/s8-host-region-accepted.log`。`LiquidCellInventoryValidation` 的稀疏坐标、唯一共享面及颗粒精确分份通过，记录 `build/s8-host-spatial.log`。归属事务、协议／客户端几何和封闭空间扫描分别通过，记录 `build/s8-host-ownership.log`、`build/s8-host-protocol.log`、`build/s8-host-boundary.log`。

`containers`、`lifecycle` 整组通过，包含容器库存、20 项持久事务、共享墙登记、生命周期和实体输入，记录 `build/s8-host-containers.log`、`build/s8-host-lifecycle.log`。`assemble verifyArchitecture` 通过，架构检查扫描 598 个生产源，依赖方向与单元库存写入职责通过，记录 `build/s8-delivery-build.log`。

最终 `assemble verifyArchitecture :fabric:runHostValidation -PhostValidation=shape` 通过，记录 `build/s8-delivery-final.log`。楼梯放置使用 310 K 的正式化学、气体及热定义，实际搬迁 6 格液体，体积、整数质量、全部物种和液体携能保持；气体缩容功进入机械功账，总内能与热审计精确闭合。库存验证中的零质量尾量及稀疏空间合同随后通过，记录 `build/s8-host-spatial.log`。交付产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`。

新拓扑完整生产 64³／0.5 m 基准沿下文同一命令执行，预热 5 步、测量 10 步。2026-11-09 实测中位数 **137.567 ms**、最大 **234.404 ms**、GPU 均值 **97.867 ms**；单 bank 工作区 **3,760,883,808 B**，设备分配总量 10793.2 MiB。实际子步数为 `[1,1,1,1,1,2,2,2,2,2]`，每步提交 15–27 次。记录 `build/s8-production64.log`。当前未达到 50 ms 预算，较 2026-11-05 生产基线更慢；功能交付后需整体降低完整步计算与提交成本。

S1 的针对性入口：

```powershell
./gradlew.bat :gpu-kernel:runGpuWetWallValidation :gpu-kernel:runGpuEntityBodyCouplingBenchmark :gpu-kernel:runGpuChannelFlowValidation --offline --no-daemon --console=plain --max-workers=1
```

2026-11-05，RTX 5070 Ti 上按主轴液面几何验证满液、半液和薄至 1/32768 格的贴壁液膜；干顶墙接触面积为零，静止液体无额外生热，黏性动能与热量结算闭合。记录 `build/s5-s1-regression3.log`。

压差通道采用相同初始液位差、0.5 m 格长和 16 个 0.05 s 设备机械步。基准、黏度加倍、通道长度加倍、截面加宽的归一化转移量依次为 5.61901921、5.28871587、3.97673898、7.38734165；入口核对整数质量和热账。该场景覆盖有限液柱短时流量趋势。记录同上。

2026-11-05，统一气液传质的沸腾、混合液沸腾、混合液冷凝和相邻双气格接触场景通过，检查质量、物种、内能审计、机械续算与 checkpoint；记录 `build/s5-local-layout-validation2.log`。完全汽化后的空相行、机械续算和恢复通过，记录 `build/s5-empty-final.log`。固液传质验证通过新颗粒床、库存容量和双供体物种／质量结算，记录 `build/s5-coupled-acceptance.log` 中的固液任务输出。

同日 `region-state` 全组通过，覆盖液体、局部气体、化学、颗粒、热载体及世界能力定义的持久恢复，记录 `build/host-mixture-region-state-current.log`；生命周期、区域登记及气体初态库存检查通过，记录 `build/host-mixture-lifecycle-current.log` 和 `build/host-mixture-manager-current2.log`。

共同液气及外界半格惯量接入后，固液多供体、颗粒床、冻结和融化回归通过，记录 `build/s5-mixture-solid-phase.log`。

同格半液的气液热接触复用 PLIC 内部界面面积；热气向冷液传热且两相热账闭合，记录 `build/plic-internal-thermal.log`。

逐格气体初始化和共享 PLIC 查询迁移后，`:fabric:compileTestJava`、`MarkerVelocityTransferValidation` 与 `AirNetworkValidation` 通过；覆盖实体浸液、水接触、局部气压作用及冷恢复，以及显式气体初态和整数库存分份；命令与输出记录于 `build/spatial-gas-host-queries.log`。

宿主冷编辑沿真实干口转移受挤气体后，1 m³ 桶量投料、封闭气体绝热压缩、1/256 格薄膜的干口与湿面、CO₂ 物种质量与残差分份、气体库存和热账、已有混合编辑与容器全组通过；记录 `build/gas-split-host-validation3.log`。

共享开放面候选迁移后，宿主 `MarkerVelocityTransferValidation` 通过，覆盖封闭隔板两侧投料和提取、打开通路后的提取、路径距离与本格取样；记录 `build/container-reachable-faces-host.log`。

微量混合液容器取样按整数体积回算实际扣取量后，宿主 `containers` 全组通过；覆盖局部微升量级样品与剩余库存的整数质量、物种和内能交接，记录 `build/small-chemical-sample-host.log`。

同日宿主 `containers` 全组通过，覆盖 WAL 和恢复、整数库存、气体功审计及部分水桶最终可表示的微小内能；使用既有 classpath 和独立编译目录，记录 `build/container-fix-validation.log`。 架构检查扫描 603 个生产源，依赖方向、包环与液体库存写入职责检查通过，记录 `build/current-architecture-validation2.log`。

2026-11-05，共同压力及接收体 EOS 容量约束下，生产水流完成 120 步，非冷导入步墙钟中位数 36.113 ms、P95 43.979 ms、最大 54.182 ms；同批气体脉冲、气袋及纯气 EOS／功恒等式通过，记录 `build/mixture-receiver-eos-flow120.log`。加压、常压和低压三种 U 管各完成 120 步，记录 `build/mixture-u-air-current.log`、`build/mixture-u-ambient-current.log` 和 `build/mixture-u-vacuum-current.log`。工艺加热场景通过，记录 `build/process-heat-evaporation-eos2.log`；扩散的单量子溶质排空与完整内能交接通过，记录 `build/diffusion-empty-quantum.log`。

实际容器组合工艺复现：

```powershell
./gradlew.bat :fabric:runGpuProcessFlowsValidation -PgpuProcessScenario=all -PgpuProcessSteps=20 -PgpuProbeDevice=0 --offline --no-daemon --console=plain --max-workers=1
```

该入口执行有限墙加热、CO₂ 吸收脱除、晶种结晶、双挥发冷凝、碱液碳化及规定运动驱动，均通过容器事务完成投料与取样，并在 checkpoint 恢复后续算。普通场景按 20 个 0.05 s 步推进；驱动至少 40 步，冷凝运行 1200 步。冷凝从热池底部接口经 15 次实际容器事务连续投入同配方液体，热池保留自由高度，并使用抽气后的气道和 285 K 普通金属干冷壁，从固定冷壁接触格取样。

2026-11-05，六条组合工艺完成实际投料、取样、checkpoint 恢复及续算。干冷壁样品为 4.287 mL，乙醇摩尔分率为 0.34626，原料为 0.23591。吸收脱除使用 1 mol/m³ 稀碳酸水，气体抽除后继续推进，液相无机碳减少且气相 CO₂ 增加。记录 `build/gas-caloric-formal-process-all.log`；实体关闭格处理更新后的规定运动驱动复验记录 `build/final-pressure-drive-final.log`。

该组小型区域的每次 `motion.step` 墙钟时间如下，包含冷导入首步；投料 WAL、采样、存档与实际绘制在计时外。

| 工艺 | 步数 | 中位数 ms | P95 ms | 最大 ms |
| --- | ---: | ---: | ---: | ---: |
| 加热 | 20 | 19.319 | 36.366 | 211.009 |
| 吸收与脱除 | 40 | 17.379 | 29.596 | 120.248 |
| 结晶 | 20 | 39.677 | 68.846 | 114.223 |
| 蒸发与冷凝 | 1200 | 12.303 | 16.572 | 1541.264 |
| 碳化 | 20 | 17.786 | 38.819 | 117.214 |
| 规定运动驱动 | 40 | 12.825 | 19.192 | 104.837 |

完整水流 500 步、实体交互、暂停单步、checkpoint 恢复、库存编辑与续算通过，记录 `build/gas-caloric-formal-flow.log`。当前交付 `assemble` 通过，记录 `build/pause-delivery-assemble.log`；架构检查及测试源码编译证据见 `build/s1-s7-integrated-build.log`，气体物性更新的针对性编译及运行记录见 `build/gas-caloric-cache-phase.log`。

64³ 全能力设备预算使用同一切格几何、两相体积分数与初始速度，按正式支持的 0.5 m 格长加载生产物性、逐格气体、热、扩散、反应、相变及固态布局，并执行候选提交：

```powershell
./gradlew.bat :gpu-kernel:runGpuLiquidMechanicalBenchmark -PgpuMechanicalGrids=64 -PgpuMechanicalScene=cut -PgpuMechanicalGas=true -PgpuMechanicalThermal=true -PgpuMechanicalProduction=true -PgpuMechanicalWarmup=5 -PgpuMechanicalSamples=10 --offline --no-daemon --console=plain --max-workers=1
```

该后端预算输入超出默认区域登记规模；相对原 0.25 m 切格机械输入，其边长加倍、物理库存按格容增至 8 倍，分别记录结果。它覆盖设备候选及提交，Minecraft 世界交互与绘制另计。2026-11-05，关闭阶段诊断、预热 5 步并测量 10 步：生产 64³ 中位 99.928 ms、最大 175.604 ms，GPU 均值 74.344 ms，单 bank 工作区为 3,713,109,520 B；测量步实际子步数为 `[1,1,1,1,1,2,2,2,2,2]`，记录 `build/gas-caloric-formal-production64.log`。原 64³ 切格机械场景中位 140.351 ms、最大 191.693 ms，记录 `build/gas-caloric-formal-mechanical64.log`；32³／256 颗粒场景中位 157.254 ms、最大 200.338 ms，记录 `build/gas-caloric-formal-mechanical32.log`。当前耗时高于 50 ms 目标，后续继续优化。

气体及相变 13 个场景与有限墙通过，记录 `build/final-pressure-phase.log`、`build/final-pressure-walls.log`。纯真空实体关闭与重开、有库存实体对齐关闭通过，记录 `build/entity-vacuum-closure.log`、`build/aligned-body-check.log`。

局部气体点查询使用共同 PLIC 后，宿主完整气体传输验证通过，覆盖点位置、局部库存和内能编辑及 EOS 功恒等式；记录 `build/host-gas-point-validation.log`。

同一批串行验证通过竞争反应、局部气体脉冲与气袋、空液相行，以及 flow 120 步的编辑和恢复；flow 119 个非冷导入步中位数 15.202 ms、P95 23.810 ms、最大 36.045 ms，记录 `build/indexed-pending-integration.log`。

原规模成本继续使用 `runGpuLiquidMechanicalBenchmark` 的 64³ 切格及 32³／256 颗粒场景。该入口覆盖连续设备机械步及可选导热，反应、相变、生产调度、世界与展示另由生产演示评价；两类成本分别登记。湿壁派生列在 64³ 时每代占 24 MiB，两代共 48 MiB，几何复用时随同代派生字段复制。

同日统一构建 `assemble verifyArchitecture :fabric:runHostValidation :gpu-kernel:runGpuChannelFlowValidation :gpu-kernel:runGpuThermalContactsBenchmark :gpu-kernel:runGpuThermalPhaseValidation :fabric:runGpuProductionDemoValidation` 通过；参数为 `hostValidation=region-state`、`gpuDemo=flow`、`gpuDemoSteps=120`、`gpuDemoStage=complete`，使用 `--offline --no-daemon --console=plain --max-workers=1`。架构扫描 611 个生产源，完整区域存档及世界定义恢复通过。有限热设备与区域热步的能量账闭合，七类热相变均含机械续算和 checkpoint。水流场景完成实体交互、暂停单步、导出恢复、库存编辑和继续推进；119 个非冷导入步墙钟中位数 23.092 ms、P95 30.538 ms、最大 47.036 ms，未触发耗时暂停。该计时涵盖生产演示的输入、设备与采用流程，逐步诊断在计时之外。记录 `build/simulation-s1-delivery.log`，产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`。

### 竞争反应与有限动力学

```powershell
./gradlew.bat :gpu-kernel:runGpuLiquidReactionsValidation --console=plain
```

2026-10-30，五个 GPU 场景通过，日志 `build/competitive-reactions.log`。快速解离与中和保持原有整数结果；两条等速反应共享 100000 个反应物量子，分别生成 50000；正逆速率比 2:1 的反应在 100 个连续步后得到 33333/66667 的平衡分配。10 个量子的慢反应在中途捕获、重建设备存储并恢复进度后生成 4 个产物量子，保留其余 6 个。高温与放热反馈改变竞争产物分配，实际质量和内能保持闭合。该入口验证局部反应职责，完整工艺和完整步耗时由组合场景另行登记。

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

气体留容与实体排水使用 `:gpu-kernel:runGpuLiquidCapacityInputsValidation`：直接运行生产输入及容量内核，检查静止实体占容后的跨单元排水、有限气体正干容、整数守恒及总孔容不足时的拒绝。实体插入、移动、同布局编辑与存档恢复的完整交接由 resident 场景覆盖。

## 需要进入游戏时

当问题依赖实际画面、Minecraft 方块形状或磁盘重启过程时，准备一个可重复场景，写明操作、预期现象和需要保留的日志。发布构建、产物校验和部署步骤见 [验收与发布](../roadmap/acceptance-and-release.md)。

<a id="当前验证证据"></a>
## 当前验证基线与状态

2026-11-08 正常运行误暂停复审：宿主 `region-manager,lifecycle,region-state` 通过，记录 `build/pause-review-validation.log`；最终 `assemble`、`region-manager`、`runGpuEntityBodyCouplingBenchmark` 及生产水流 `flow 120 complete` 通过（47 秒），记录 `build/pause-review-delivery.log`。回归覆盖同批碰撞缩容后共用剩余干容接收气体、碰撞阻塞保留刷新请求，以及有限设备 WAL 中断发布的临时文件与正式日志共存和单独残留。水流完成实体交互、暂停单步、checkpoint 恢复、库存编辑与续算，质量及环境收支和热账通过。

同轮 `runGpuResidentStateValidation` 通过（26 秒），记录 `build/pause-closure-final.log`。区域低侧与高侧分别执行实体完全关闭外侧纯气格、实际外排、候选提交、checkpoint 恢复及续算；整数气体质量、物种、环境能量与热账闭合，关闭步舍入账增量小于 1e-6 J。密封外侧的无出口拒绝由实体内核回归保留。极小液量、切格孔隙、设备候选拒绝与采用、热状态及驻留状态恢复继续通过。产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`。

2026-11-07 深入逻辑复审：`:fabric:runHostValidation -PhostValidation=lifecycle,region-state` 通过（28 秒），记录 `build/logic-review-deep-final.log`。回归覆盖跨维度实体位移历史隔离、目标维度恢复连续运动、实体贴住暂停单元时保持可用且实际伸入时受阻；空池的普通固定墙允许解除归属，归还候选采用后保留墙体温度与热账，安装换热器继续阻止释放。现有热学存档场景增加普通墙，从 REMOVED 隔离 payload 读取完整热库存与审计。区域库存、MAC、实体、相变及能力包恢复验证通过；`assemble` 已生成 `fabric/build/libs/mmm-fabric-0.17.0.jar`。

2026-11-07 扩展逻辑复审：`:fabric:runHostValidation -PhostValidation=lifecycle,protocol` 通过（34 秒），记录 `build/logic-review-followup-validation.log`。新增回归覆盖卸载区域修改暂停／运行意图后的元数据重读、手动单步的实体与驱动占位及零规定速度、驱动停止后移除占位，以及按点击位置停止驱动时保留另一选中区域和周期投料。切换维度及复活后的快照补发复用正式协议会话和区域展示发送，服务端编译及既有协议、客户端表面与查询验证通过。主模组 `assemble` 已完成，产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`；本轮沿现有支持范围检查区域管理、演示及世界交互，耗时参数保持原值。

2026-11-07 逻辑复审：`runHostValidation -PhostValidation=lifecycle` 通过（32 秒），新增覆盖静水休眠时周期投料到期、间隔执行、玩家暂停、区块不可用及卸载状态，记录 `build/logic-review-lifecycle.log`。复用本次编译 classpath 执行 `ContainerTransferStoreValidation --public-transfers` 通过，覆盖 WAL 存储存在扫描问题时保留待恢复收据、健康索引缺少对应日志时归类未提交，以及既有持久收据与 actor 代次恢复，记录 `build/logic-review-public-transfers.log`。`assemble :fabric:testClasses` 通过（25 秒），记录 `build/logic-review-delivery.log`，产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`。

2026-10-30 S1 交接复审修复：`assemble verifyArchitecture :fabric:runHostValidation :gpu-kernel:runGpuResidentStateValidation :gpu-kernel:runGpuActivityMaskValidation :gpu-kernel:runGpuGranularTransitionsBenchmark` 通过，宿主选择 `lifecycle,containers,region-state,protocol`，记录 `build/review-s1-handoff.log`（45 秒）。回归覆盖全加载宿主规划的独立组和几何变更、化学／相变能力依赖、无热学颗粒的缺气腔冷导入与推进、反应布局机械／热候选后的部分可用性，以及同代非零颗粒和气流控制计数的观察。上下两层沉积床分别覆盖松弛和起动，并检查上层暂停时的质量、体积与内能保留。架构扫描 611 个生产源通过。

同轮默认生产水流 120 步与七类热相变通过，记录 `build/review-s1-production.log`（32 秒）。覆盖保存恢复、暂停单步、编辑续算、整数质量和热账；本轮确认事件观察正确，未新增关于提前休眠的实测结论。构建产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`。

2026-10-30 初始化与活动组交接复审：`assemble verifyArchitecture :fabric:runHostValidation :gpu-kernel:runGpuResidentStateValidation :fabric:runGpuProductionDemoValidation` 通过，选择 `hostValidation=lifecycle,containers,region-state`、`gpuDemo=flow`、`gpuDemoSteps=120`、`gpuDemoStage=complete`。记录 `build/review-state-isolation.log`，耗时 54 秒。新增宿主回归覆盖设备创建／首次上传失败及释放异常、暂停组相变任务保留时另一组容器预览与装取、超过设备预算的暂停实体观测及扫掠保留；设备失败采用受控注入。真实 GPU 回归检查冷导入 300 K 和编辑导入 350 K 的即时热显示，完整水流完成保存恢复、单步及编辑续算。架构扫描 611 个生产源通过；产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`。

2026-10-30 其余 shader 可读性整理：53 个 shader／共享片段完成主要变量、函数、阶段、记录字段及魔数命名。常驻状态、七类热相变、固液反应、整数输运及 74,743 行压力图重放通过，对应任务输出见 `build/shader-readability-integration.log`。完整 GPU 水流 120 步、实体耦合、checkpoint 恢复、暂停单步和编辑续算通过，记录 `build/shader-readability-delivery.log`。相变行共享库同步改名后，`assemble :gpu-kernel:runGpuThermalPhaseValidation :gpu-kernel:runGpuSolidLiquidReactionsValidation` 通过，最终证据为 `build/shader-readability-phase-final.log`。算法、布局、参数和迭代预算保持不变，产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`。

2026-10-30 主线审阅修复：`runHostValidation -PhostValidation=lifecycle,containers,region-state` 通过，记录 `build/mainline-review-regression.log`。新增交接回归覆盖有限热设备更新库存后的卸载恢复、容器预览与执行排除暂停组、恢复组重新参与编辑，以及实体扫掠进入暂停组后保留旧姿态。设备故障使用受控后端覆盖整个区域 tick 的统一处理、共享设备失效及释放异常；真实 Vulkan 设备丢失注入仍未执行。

同轮 `assemble verifyArchitecture :fabric:runGpuProductionDemoValidation -PgpuDemo=flow -PgpuDemoSteps=120 -PgpuDemoStage=complete` 通过，记录 `build/mainline-review-delivery.log`。架构扫描 611 个生产源；真实 GPU 水流完成 120 步、实体交互、checkpoint 恢复、暂停单步、库存编辑与续算。产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`。源码哈希独立重算一致，覆盖 903 个文件，其中 GPU 模块 182 个（含 Java、shader 与构建配置），记录 `build/mainline-review-metadata.log`。

2026-10-30 核心 shader 命名与常量整理：`runGpuResidentStateValidation`、`runGpuLiquidCapacityInputsValidation`、`runGpuThermalPhaseValidation`、`runGpuInventoryTransportValidation` 通过。覆盖生产 shader 编译、实体与 checkpoint 续算、容量接受与真实不可行拒绝、七类热相变、库存跨工作组输运及候选回滚。记录 `build/shader-naming-integration.log`；本轮复用现有场景，未改变算法及数值预算。`assemble` 通过，记录 `build/shader-naming-delivery.log`，产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`。

2026-10-29 GPU 主线迁移：`runGpuResidentStateValidation` 通过，覆盖设备提交、导出恢复及同布局导入候选的拒绝和采用；`verifyArchitecture` 扫描 613 个文件通过，区域类 722 行、工作区 980 行。运行记录 `build/gpu-mainline-resident.log`。

同日 `:fabric:runHostValidation -PhostValidation=region-state,containers,ownership,lifecycle` 通过，包含容器存储 19 个场景、世界归属 27 个场景及生命周期和区域存档；记录 `build/gpu-mainline-host.log`。这次构建同时编译 Fabric 测试源码。

2026-10-30，主线迁移后的 controller 20 步、tank 120 步、flow 120 步及 precipitation 120 步完整流程通过。覆盖真实容器 WAL、暂停与单步、编辑后续算、实体交互、checkpoint 恢复及沉淀清空；水箱和析盐各运行三种配方。记录分别为 `build/gpu-mainline-controller.log`、`build/gpu-mainline-tank.log`、`build/gpu-mainline-flow.log`、`build/gpu-mainline-precipitation.log`。

同日 `runGpuResidentStateValidation` 和 `runGpuThermalPhaseValidation` 通过，新增覆盖已有实体工作区的同布局编辑候选复用、拒绝和采用；七类热相变保留整数质量、热账、机械续算与 checkpoint。记录为 `build/gpu-mainline-resident-final.log`。

设备故障生命周期测试使用受控后端模拟读回失败，以及 HEAD 发布后导入失败；验证暂停和按已发布库存恢复运行意图。该证据不包含真实 Vulkan 设备丢失注入。

同日 `:gpu-kernel:runGpuActivityMaskValidation :fabric:runHostValidation -PhostValidation=lifecycle` 通过。两个隔离空间分别运行和暂停，机械步与独立热步均确认活动侧有输运或热量变化，暂停侧液体、气体、颗粒、冰及其内能保持，暂停格面动量、实体占位和未决相变保留。分组断言覆盖有限气腔、设备端口、实体扫掠、颗粒接触及区块恢复。记录 `build/gpu-mainline-activity-final.log`。

布局整理后，热相变、固液反应和整数库存输运验证通过，记录 `build/gpu-layout-consolidation.log`、`build/gpu-layout-transport-followup.log`。宿主各模块测试源码、Fabric 客户端编译及架构检查通过，扫描 612 个生产源，记录 `build/gpu-mainline-integration-compile.log`。

热边界温度候选验证通过：取消候选保持源库存与参数，单次采用后热步续算使用新温度；准备过程只回读 4 字节执行状态。环境参数更新保留常驻库存。

2026-10-30 实体容量问题定位：`build/entity-capacity-capture.log` 在第 4 次 backend 流程捕获 `INFEASIBLE`，14 轮、上限 512 轮。失败输入保存于 `build/entity-capacity-failure.bin`；独立整数最大流及生产 GPU 重放一致确认，三组上限之和比液体源总量少 4,541,619,097 个容量量子，约 0.00051632 m³。GPU 重放的剩余正收支恰为该缺口，记录 `build/entity-capacity-replay.log`。根因是位移路径在未来孔容上沿用旧气体的压力预测留容，跳过普通路径的本组库存参考限制；固定体积补偿无法保证容量一致。

修复后普通输运与位移共用未来干容参考，删除固定补偿，预算保持不变。确定性容量输入回归在原实现上返回错误的 `INFEASIBLE`，记录 `build/entity-capacity-before-fix.log`；修复实现通过同一场景，并保持真实孔容不足时拒绝。24 次 resident 完整流程通过，覆盖实体插入、移动、同布局编辑与 checkpoint 续算，液体、气体库存和热账保持，记录 `build/entity-capacity-fixed-resident.log`。七类热相变及 flow 120 步完整流程通过，记录 `build/entity-capacity-regression.log`。

本次修复的 `assemble :gpu-kernel:runGpuLiquidCapacityInputsValidation` 交付构建通过，记录 `build/entity-capacity-delivery.log`；产物为 `fabric/build/libs/mmm-fabric-0.17.0.jar`。

2026-10-30 最终统一交付构建通过：`assemble verifyArchitecture :fabric:runHostValidation :gpu-kernel:runGpuActivityMaskValidation :gpu-kernel:runGpuThermalPhaseValidation :fabric:runGpuProductionDemoValidation`，选择 `hostValidation=region-state,containers,ownership,lifecycle`、`gpuDemo=flow`、`gpuDemoSteps=120`、`gpuDemoStage=complete`，使用 `--offline --no-daemon --console=plain --max-workers=1`。共 59 秒，39 个任务中 20 个复用产物；架构扫描 611 个生产源通过。水流场景完成实体交互、保存恢复、暂停手动单步、库存编辑及续算；双组与七类热相变均通过。记录 `build/gpu-mainline-delivery.log`，发布产物 `fabric/build/libs/mmm-fabric-0.17.0.jar`。
