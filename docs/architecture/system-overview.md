# 当前设计书

本文按完整职责链组织当前模型、数据归属和代码索引。开发时阅读本文及[当前实施计划](../roadmap/solver-consolidation-plan.md)，按改动范围查阅[验证指南](../maintenance/validation.md)。稳定公式和字段详见各专题；待办由实施计划维护，验证方法与预算由验证指南维护。

当前支持最多四个空间选区或有界静水池区域。默认格长为 0.5 m；液体采用单元有限体积与 MAC 动量，气体采用逐格组分与内能，液气共用 MAC 运动和局部有限压缩状态方程，颗粒采用活动代表粒子与床层。固定碰撞每个求解单元保留一个连通开放空间，隔墙以求解网格解析。

## 模块职责

| 模块 | 主要职责 |
|---|---|
| [api](../../api/src/main/java/mmm/api/) | 外部查询、事件与数量类型 |
| [model](../../model/src/main/java/mmm/) | 材料、物种、库存、内能及存档模型 |
| [solver](../../solver/src/main/java/mmm/fluid/) | 状态与候选契约、宿主库存编辑、几何及恢复 |
| [app](../../app/src/main/java/mmm/application/) | 调度、用例、区域身份和提交协调 |
| [gpu-kernel](../../gpu-kernel/src/main/java/mmm/gpu/) | Vulkan 设备状态、唯一时间推进、候选与观察；无头验证位于 `src/test` |
| [fabric](../../fabric/src/main/java/mmm/) | 世界与物品操作、存档、运行时、网络和客户端展示 |

世界读取、方块修改、物品变化和磁盘提交由服务器主线程协调。`MmmRuntimeFactory` 绑定世界定义和 Vulkan 后端；`RegionApplicationService` 与区域仓库负责候选代次和身份交接。架构依赖与类规模由 `verifyArchitecture` 检查。

<a id="哪些数据决定物质库存"></a>
## 数据归属

| 数据 | 保存内容与写入者 |
|---|---|
| 世界定义 | 材料、物种、热参数及注册 ID，启动时加载，设备读取定义表 |
| 已提交设备代 | 液体体积、整数质量、组成、内能、MAC 动量，气体、颗粒、固相、实体及相变待处理状态 |
| 设备候选 | 同一输入代生成的完整后继；应用层采用或释放 |
| 宿主状态 | `FluidDomainState` 为可保存状态；`HostFluidState` 提供初始化、库存编辑、几何与恢复操作 |
| 世界与物品 | 方块、容器、区域 HEAD 和 WAL，通过事务协调共同恢复 |
| 派生量 | 几何、物性、压力、温度、展示和索引从同代库存准备 |

液体库存按单元和材料保存精确参考体积、质量、物种及内能；颗粒、连续固相与逐格气体分别保存自身库存及收支。涉及多相的编辑以同一计划交接物质、组成、动量和内能。质量、物种和体积余量保留整数或精确表示，几何与力学采用浮点近似。专题见[材料](material-state.md)、[化学](chemistry-state.md)、[气体](gas-state.md)、[颗粒](granular-state.md)、[热学](thermal-state.md)和[存档](region-state.md)。

### 空间域与计算分块

一个区域持有完整物理域、材料定义和提交代。域内采用固定 8³ 单元块，块内保持规则网格，实际登记的块按空间顺序紧凑存储。单块与多块使用同一套寻址和求解。单元坐标、六邻接和 MAC 面身份由 `SpatialGrid` 统一提供；相邻块引用同一个共享面，压力包含跨块项，输运按同一份接受通量更新两侧库存。

固定拓扑由 `VulkanSpatialGrid` 编码为独立只读设备数据，创建域时上传，液体、气体、颗粒、热学及观察内核共同引用。热路径直接读取六邻格和面端点，任意坐标通过驻留块哈希查找。压力多重网格一次生成各层父子格映射，系数随当前状态更新。颗粒床层按实际驻留的竖向列存储；缺块处保留明确的几何隔断。块决定存储，物理连通和设备接触决定共同推进或暂停的范围。0.5 m／1 m 动态叶格的守恒表示和几何成本见[两级空间分辨率](fluid-solver.md#两级空间分辨率)。

空间选区和水池按实际空间占地登记、采样碰撞与检查世界区块。区域内固体及开放空间由实际碰撞形状决定。资源计费使用驻留单元总数，长窄通道可跨越多个块；每区域和服务器总量继续受配置预算约束。所有块共享一个候选和区域 HEAD，完整 checkpoint 保存块成员与空间地址，恢复时重建紧凑索引。

入口：[SpatialGrid](../../model/src/main/java/mmm/spatial/SpatialGrid.java)、[VulkanSpatialGrid](../../gpu-kernel/src/main/java/mmm/gpu/VulkanSpatialGrid.java)、[spatial-grid.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/spatial-grid.glsl)、[FluidRegionFactory.grid](../../fabric/src/main/java/mmm/simulation/FluidRegionFactory.java)、[PhysicalActivityGraph](../../solver/src/main/java/mmm/fluid/PhysicalActivityGraph.java)。跨块完整步验证入口为 `runGpuSpatialDomainValidation`。

### 数据驱动的物质定义

世界启动时从 `data/mmm/mmm` 下的 JSON 加载液体、气体组分、化学物种与反应、颗粒及热学定义，形成完整的 `FluidDefinitions`。库存功能开关控制实际参与的物理过程；所有宿主编辑、容器操作、恢复和 GPU 表生成均读取这份世界定义。液体密度、温度黏度曲线、表面张力、接触角、热浮力参数与颜色位于 `fluid_materials`；组分摩尔质量、扩散与混合液体物性位于 `species`，溶液黏度修正参数位于 `chemical_phases`，气体种类与环境比例位于 `gas_components`。热容、参考内能、导热、相变及有限墙继续由对应热学 JSON 提供。

液液界面张力由可选 `fluid_interfaces` 定义。世界定义随能力包保存，checkpoint 引用其指纹；宿主与 GPU 从同一份数值构建状态函数与材料表。求解配置保存数值参数，纯水和原版桶的密度由材料定义提供。入口：[WorldMaterialDataPackLoader](../../fabric/src/main/java/mmm/simulation/WorldMaterialDataPackLoader.java)、[RegistryBundleLoader](../../fabric/src/main/java/mmm/simulation/RegistryBundleLoader.java)、[FluidDefinitions](../../solver/src/main/java/mmm/fluid/FluidDefinitions.java)、[VulkanFluidStateCodec](../../gpu-kernel/src/main/java/mmm/gpu/VulkanFluidStateCodec.java)。

<a id="units"></a>
### SI 物理量与整数编码

Minecraft 一个方块的边长标定为 1 m。物理定义统一使用 SI，化学真实物种使用 mol。
下表是单位约定的统一入口；专题维护各自字段和公式。

| 数量 | 单位与编码 | 入口 |
|---|---|---|
| 长度、时间、速度 | m、s、m/s | `FluidSolverConfig` |
| 质量、温度、压力、能量 | kg、K、Pa、J | `SolverUnitScale`、`ThermalStateFunction` |
| 密度、动力黏度、表面张力 | kg/m³、Pa·s、N/m | 材料定义 |
| 比热、总热容 | J/(kg·K)、J/K | `ThermalMaterialDefinition` |
| 导热率、热导 | W/(m·K)、W/K | 热材料与热接触 |
| 化学物质量、摩尔质量、摩尔参考能、浓度 | mol、kg/mol、J/mol、mol/m³ | `ChemicalSpecies`、`ChemicalPhaseDefinition`、`ThermalSpeciesOffset` |
| 化学整数库存 | 1 quantum = 1 μmol；10⁶ quanta = 1 mol | `ChemicalAmount`、组成池与反应批次 |
| 液体体积编码 | 1 nanos = 10⁻⁹ m³ | `ContainerFluidLedger` |
| 气体体积编码 | 1 nanos = 10⁻⁹ 标准 m³；标准态为 101325 Pa、293.15 K | `GasRegistry`、`AirNetwork` |
| 固体体积编码 | 1 SVQ = 2⁻²⁷ m³；默认单元为 2²⁴ SVQ | `SolidVolume` |
| 液体内部体积与求解质量 | 体积乘 h³ 得 m³；浮点质量权重由整数 kg 库存除 h³ 派生 | `SolverUnitScale` |
| 全部载体的质量与化学质量余量 | `long massUnits`，每份 10⁻¹⁰ kg（100 ng），编码 `mmm:mass_1e-10_kg` | `Mass` 与各载体库存 |

默认 h = 0.5 m，一格满水内部体积为 1、质量权重为 1000，对应 0.125 m³、125 kg。
位置和速度采用 m 与 m/s。液体保存归一化体积与物理质量整数；质量权重供求解器计算使用，由 `SolverUnitScale.normalizedMass` 只读派生。
化学转移账本的 `normalizedVolume` 保存归一化体积，质量与余量保存统一质量整数；容器库存的 nanos 保存物理体积。
SVQ 在默认单元中对应 2⁻²⁴ 的归一化体积；液体体积由 `SignedDyadic` 保存，可保留小于一个 SVQ 的有效余量。
整数占容、向上／向下换算及 float 可表示性沿现有算法执行。

气体状态方程、相平衡和液气共同压力使用绝对压力；查询时按接口声明输出绝对压或相对于环境的表压。
局部气相实际容量以 m³ 表示，标准体积由库存按标准态定义保存，两者分别参与状态方程。
仿真步长和累计仿真时间用 s；Minecraft tick 是服务器调度次数，正常节拍为 20 tick/s。
计算耗时用实际时钟 ns 或 ms 衡量，pacing 和重试保持请求推进的仿真时长。

油采用材料质量与化学质量余量，物种列为空。汞采用真实 Hg，玩法摩尔质量为 0.200 kg/mol，密度为 13600 kg/m³。
水、常见离子和 CO₂ 沿用整数原子量精度；水为 0.018 kg/mol，默认浓度为 500000/9 mol/m³。乙醇按 0.046 kg/mol 表示，水与乙醇共享水溶液库存；液体导热率按组成混合，热容采用水溶液载体曲线，蒸气作为独立气体组分。常压沸点和汽化焓采用 [NIST 乙醇数据](https://webbook.nist.gov/cgi/cbook.cgi?ID=C64175&Mask=5) 的工程近似，理想混合不解析水—乙醇共沸。物种可声明扩散率与有效摩尔体积；未声明时不支持相应扩散模型。
物种质量与余量共同核对机械 kg 库存。化学数量上限、默认批次及物性值分别由代码和数据定义维护。

质量定点尺度兼顾微摩尔组成与区域容量：1 μmol 水为 180 份、H⁺ 为 10 份、Hg 为 2000 份。
每列和受检总量（含累计 source/sink 审计）的正值上限约为 9.223372×10⁸ kg；现有化学区域数量上限对应的 Hg 质量为 4×10⁸ kg，默认格长下 9216 格满汞约为 1.567×10⁷ kg。
物性定义仍以 SI 保存。新建库存按最接近值、恰好中点取偶数量化一次，误差至多 50 ng；真实物种每个 μmol 的质量及相变批次须能精确换成整数。
因此自定义摩尔质量采用 0.0001 kg/mol 的整数倍，沿用玩法所需的近似精度。
拆分以整数比例分配，保留份额承接余数，合并和差额使用受检整数运算。
内部允许保留正体积、零机械质量的几何尾项，其内能为零；对外物质交接按同材料实际库存合并质量与体积，完整保留整数物种。
机械、热学、密度及显示读取派生 kg；物质审计、容器、内存交接与存档直接比较整数。
纯液体 handoff 和 checkpoint 也携带质量列，恢复完整保存的库存。


玩家展示由 `UnitFormat` 按数量选择 μmol/mmol/mol/kmol、mL/L/m³、J/kJ/MJ、Pa/kPa/MPa。
当前温度输入和展示统一保留 K；绝对压力与标准态在文字中注明。
显示舍入保持只读，原始整数编码留在开发诊断；一桶的游戏操作容量为一个方块体积，即 1 m³。

- 单位与转换入口：
  - [Mass](../../model/src/main/java/mmm/units/Mass.java)：质量编码、新建数量化与整数比例分配。
  - [SolverUnitScale](../../model/src/main/java/mmm/units/SolverUnitScale.java)
  - [ChemicalAmount](../../model/src/main/java/mmm/chemistry/ChemicalAmount.java)
  - [UnitFormat](../../model/src/main/java/mmm/units/UnitFormat.java)
- 验证入口：`region-state`、`containers`、`protocol` 及 GPU 场景。

库存和单位转换采用整数与精确数量运算；几何和力学采用浮点离散。未解析薄层与细尺度运动按单元尺度近似。

<a id="runtime"></a>
## 从游戏输入到设备候选

设备代分别记录几何缓存可复用性、气体活动摘要有效性和机械阶段观察有效性。反应后的同代气体摘要可继续划分活动组；气流和颗粒事件从实际执行阶段读取。宿主规划按当前几何重建相关分组，全加载快照也保留组间隔离。

1. 启动恢复世界能力包及未完成事务，组装设备后端和区域服务。设备创建与预热共用初始化故障处理；失败释放设备并记录原因，世界保留不可推进的区域运行时。
2. 世界输入采集碰撞、热接触与实体状态；`RegionTickUseCase` 按暂停、休眠与节拍选择机械步或独立热步。游戏内手动单步复用当前实体占位、活动组与提交后响应流程；连续单步使用零规定速度并保留已配置驱动的当前姿态，服务器 tick 保持原值。
3. 请求捕获已提交代与输入，Vulkan 在私有状态执行计算，生成完整设备候选与统计。
4. 应用层核对区域身份、代次、后端与布局，采用候选后再发布实体和世界效果。失败候选释放，已提交代继续有效。
5. 日常查询与展示读取设备观察；checkpoint 和宿主编辑显式导出所需代次。

固定世界方块与域外有限空气由维度共享热场持有。原版方块标签选择材料，未配置方块采用默认石材；铁、铜、金、木材、玻璃及绝热材料复用世界热学定义。每个固体按实际占据体积保存质量与内能，同格空气按微盒连通分量保存实际组成、质量与内能。区域设备行引用唯一世界节点，多个区域可共同加热同一面墙。

空间选区由探针两角确认，碰撞与容器独立。液体或颗粒接近边缘时，既有 GPU 观察输出六方向标志；运行入口先做预算、区块与重叠预检，再按 8³ 计算块生成更大候选，搬迁库存、气体、动量、几何及待处理任务。环境空气交给新气格、原版水接管及区域布局沿同一候选和世界发布记录采用。

环境扩散每 10 个服务器 tick 排队，优先加入该维度下一次成功的区域 GPU 提交；无区域步时独立推进。区域墙接触随实际接受步长执行，失败候选同时丢弃区域和环境更改。静态拓扑驻留设备，正常步只复制可变库存及账本；墙投影未失效时复用已有值。环境远场为 293.15 K，有限空气可积热，远场实际收支单独记账。

有限换热器保留自身物品库存；普通世界墙、筛表面装填与吸附物由共享环境持有。预热和装填先从当前世界代刷新投影，再共同准备区域与世界编辑。checkpoint 通过唯一世界 HEAD 发布环境、全部区域和删除标记；区域双槽镜像在恢复时追平同一组数据。

物料箱的九个槽保存玩家放入的实际容器，空槽保持为空；液体、颗粒与气体共用正式容器载荷及持久身份。容器接口连接外侧物料箱和内侧实际接触格，按设置的在线速率与红石信号执行计划、WAL、HEAD 和容器后态提交；取样组成来自接触格，出料要求对应物相的容器。内置端口直接使用共同容器事务，不占用公共外部 actor 注册额度。满箱和断料保持配置等待，暂停与卸载保留运行意图，到期供料唤醒休眠区域。持单个容器交换当前槽，空手取回，潜行空手切槽，探针打开选槽面板。容器离开箱体时移除箱内事务身份；未决交接保护对应槽位和箱体拆除。

接口 `FACING` 指向外侧连接面，`mode` 方块状态由已保存的运行配置派生，配置后和加载后的首个 tick 同步给模型。传感器仪表朝向 `FACING`，默认测点位于反方向；模型随六向放置、旋转和镜像保持同一含义。配方、配方书解锁与获取顺序见[生存模式入门](../gameplay/survival-progression.md)，贴图与模型来源见[工业展示素材](../maintenance/visual-assets.md)。

阀门闸板沿流向贯穿方块，五档开度改变通流截面；方块碰撞与显示共享该几何，固定碰撞编辑负责占容和物料交接。红石信号的目标开度在碰撞事务成功后提交；区域暂不可用或占容受阻时，方块按当前信号继续尝试，直至动作完成或信号改变。

传感器从已提交状态读取指定格域的液位、液体温度、气体温度、绝对压力或液体截面流量。温度按所选物相的质量加权，该相没有库存时输出零信号；压力排除全封闭格，按实际开放体积加权；流量按实际接受面量和对应仿真步时长派生。阈值与滞环或连续映射产生 0—15 红石输出。探针打开方块原生配置面板，设置随方块实体保存。

每次观测同时发布不可变 `ProcessSensorSnapshot`，统一保存读数、单位、状态、区域身份和仿真时长。聊天、配置面板、曲线摘要及外部显示读取同一结果；无样品和不可用状态没有数值。客户端接收有界摘要，实时观测不写入世界存档，重载等待重新采样。

筛网按实际网格面保存孔径与开孔率，液气通流和颗粒运动读取同一面配置。活动颗粒碰到筛面时按材料的一次粒径决定穿过或截留，截留库存进入上游床层；床层沿坡面滑移与沉降时也按同一筛孔限制跨面移动。床层占容影响通流，接口从实际接触格取出滤渣，筛网随方块刷新、转向与拆除更新。

管理员使用 `/mmm process drive <秒数>` 启动对准格的规定往复驱动，`stop` 停止驱动，`sample` 读取局部库存。机械驱动器方块安装在区域外壳，点击后沿所点面的法线驱动内部构件，潜行点击停止。驱动配置按区域保存，运动、压力作用和外功沿共同候选结算。

区域按同代物理连通关系划分活动组：开放格面、局部气体、颗粒接触范围、实体扫掠范围和有限热设备端口共同决定分组。缺失区块使关联组暂停，其余组沿同一设备候选继续推进；暂停组保留库存、内能、动量和未决相变任务。详见[运行时](runtime-and-lifecycle.md)。

世界方块变更唤醒相邻区域并刷新实际碰撞与热墙。固定几何包含选区内部的微盒及紧邻外侧面的固体微面，宿主挤排、GPU 输运和壁面剪切共用其开放面积；外侧墙变化与内部碰撞沿同一候选提交。活塞机座与运动头按完整方块表达，被推动方块采用实际形状；运动部件采集逐步姿态与表面速度，结束后把占容及有限墙库存交回静态几何。实体输入合并扫掠分组，按实际占据的半开单元范围检查可用性和设备预算；受阻实体保留旧占位，贴面接触保持相邻组独立。

接触速度包括平动和绕作用中心的转动。压力与黏性作用产生冲量、力矩和对应功；有限轴向惯量参与共同压力响应，规定运动按实际作用结算外功。位移历史按维度与实体 UUID 保存，进入新维度后的首次观测采用零规定速度。

外部模组可通过 MMM 的 `MovingStructureApi` 提交固定轴向碰撞盒和带时长的平移段。`ExternalMovingStructures` 维护稳定分片身份、已接受进度及恢复匹配，`MinecraftRegionInputs` 统一检查完整结构的扫掠可用性和运动体预算，并排除原实体的大包围盒。初始结构从区域外进入或经原位装配交接进入，退出或原位拆解后清除。生命周期描述在普通世界碰撞刷新前核对，固定微格、运动体及普通墙热库存沿同一候选采用；旋转和外部动力负载绑定见 [Create 适配计划](../roadmap/create-compatibility-plan.md)。

宿主快照及编辑候选使用区域当前有效组可用性，容器预览与执行共用该状态；待位移相变按涉及的物理组检查，暂停组任务保留。卸载清理同代设备库存、端口和派生缓存，恢复从已发布 checkpoint 重建。区域 tick 统一处理设备失效，暂停并清理未发布实体响应，保留 HEAD/WAL 恢复路径。

工业布局与离线夹具共用方块几何、有限墙初始化和端口生成。液体输入按实际端口接触面选择最低的可容纳网格，同高度按剩余容量选择；气体按气体容量、颗粒按接触液量选择。逆流装置由窄通道、有限铜壁与绝热填充构成，铜壁沿普通设备的质量、内能与热接触规则工作；填充材料的绝热条件由热学 JSON 声明。串级蒸馏建造两台小批次单元，实际馏分容器交给下一台；各台保留独立的锅液、气体、冷墙与物料箱库存。

主要入口：

- [MmmRuntimeFactory](../../fabric/src/main/java/mmm/simulation/MmmRuntimeFactory.java)、[MinecraftSimulationLoop](../../fabric/src/main/java/mmm/simulation/MinecraftSimulationLoop.java)：世界服务、设备和 tick。
- [PhysicalActivityGraph](../../solver/src/main/java/mmm/fluid/PhysicalActivityGraph.java)、[VulkanLiquidWorkspace](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidWorkspace.java)：同代物理分组和设备活动输入。
- [MinecraftGroupAvailability](../../fabric/src/main/java/mmm/simulation/MinecraftGroupAvailability.java)、[MinecraftRegionInputs](../../fabric/src/main/java/mmm/simulation/MinecraftRegionInputs.java)、[MinecraftWorldUpdates](../../fabric/src/main/java/mmm/simulation/MinecraftWorldUpdates.java)：区块可用性、世界碰撞、活塞运动和实体输入。
- [WorldThermalEnvironment](../../fabric/src/main/java/mmm/simulation/WorldThermalEnvironment.java)、[FiniteHeatExchangerOperations](../../fabric/src/main/java/mmm/simulation/FiniteHeatExchangerOperations.java)：普通墙库存、真实接触与有限设备。
- [MinecraftProcessOperations](../../fabric/src/main/java/mmm/simulation/MinecraftProcessOperations.java)：规定运动、取样及驱动配置恢复。
- [IndustrialOperations](../../fabric/src/main/java/mmm/simulation/IndustrialOperations.java)、[IndustrialDialogs](../../fabric/src/main/java/mmm/simulation/IndustrialDialogs.java)：物料箱、端口调度、阀门占容、传感器与原生交互面板。
- [MaterialBoxItemStorage](../../fabric/src/main/java/mmm/simulation/MaterialBoxItemStorage.java)：九槽实际容器的 Fabric 自动化入口，完整物品组件、嵌套事务回滚和箱体占用；区域交接继续走持久容器事务。契约见[外部 API](external-api.md#fabric-物料箱自动化)。
- [MaterialBoxBlockEntity](../../fabric/src/main/java/mmm/block/MaterialBoxBlockEntity.java)、[GasContents](../../model/src/main/java/mmm/transfer/GasContents.java)：有限批次与气体载荷。
- [IndustrialLayout](../../fabric/src/main/java/mmm/simulation/IndustrialLayout.java)、[IndustrialWorkbench](../../fabric/src/main/java/mmm/simulation/IndustrialWorkbench.java)、[GpuControllerFixture](../../fabric/src/test/java/mmm/simulation/GpuControllerFixture.java)：共享三维方块布局、有限墙初始化与工艺验证；离线夹具按正式碰撞采样入口生成微盒占据。
- [RegionTickUseCase](../../app/src/main/java/mmm/application/RegionTickUseCase.java)、[RegionApplicationService](../../app/src/main/java/mmm/application/RegionApplicationService.java)：调度与提交；[MinecraftRegionTick](../../fabric/src/main/java/mmm/simulation/MinecraftRegionTick.java) 的 `manualStep` 负责原生单步输入和实体响应，失败采用共同暂停分类。
- [FluidRegion](../../fabric/src/main/java/mmm/simulation/FluidRegion.java)：已提交代、`hostSnapshot`、`beginHostEdit/commitHostEdit` 和设备候选采用。
- [VulkanFluidSolverBackend](../../gpu-kernel/src/main/java/mmm/gpu/VulkanFluidSolverBackend.java)、[VulkanFluidStateCodec](../../gpu-kernel/src/main/java/mmm/gpu/VulkanFluidStateCodec.java)：设备请求、导入与导出。

<a id="physics"></a>
## 完整计算步

设备准备同代固定几何、相占据与物性，以共享有限应变冲量结算黏性，再计算力和一个有界批次的半隐式共同压力。上风开放微盒与液面平面裁剪普通面扫掠需求，两相按有限驻留时间接受出流，共同面速度由实际接受量确定。运动体的压力、反冲与外功使用源态共同压力；目标姿态关闭孔容时，设备沿源开放面搜索仍有孔容的接收格，内部空间搜索完成后处理开放外界。普通邻接与闭格直达关系共同生成出边和反向索引，宿主只读取搜索队列计数。液气质量、组分、携带内能、动量与耗散热使用同一组路由，气体压缩功按后继库存与目标孔容结算，同格接续刷新气体热状态和液气共同状态方程。无可达接收空间时保留已提交状态与世界刷新请求。

液体输运按来源温度、各份实际质量与组成分配携带内能；液体热容由载体材料曲线和质量决定。导热、反应和相变按完整请求时长执行，共用输运后的接触几何。导热以载体有限热容和接触热导计算成对热量，流体与有限墙的两端使用同一接受量；高刚性接触会减慢趋近平衡的速度。液体壁面共用自然与强制对流膜闭合，读取同代物性、区域重力和相对切向速度，与实际润湿面积、墙厚及墙导热率共同形成热阻。完整步和独立热步共用该接触流程，公式见[热状态](thermal-state.md#共同凝聚相有限体积导热)。

相转换共用液相候选行，按实际供体、容量、可用内能与局部平衡接受完整物种批次，覆盖本步所有活动格。气液接触按同格界面与六组相邻配对顺序执行七轮，每轮按接受量交接物料和携带内能、结算重力功，并归集供体步初共同 MAC 速度对应的入射动量与动能。每格跨格转出和表面吸附共用步初液气质量预算，本步流入质量从下一步开始参与转出预算。批末按实际接收量一次结算共同面动量与混合热，空格的表面吸附热交给有限墙，其余热量交给实际库存。混合热在批末加入，因此本步后续接触的温度反馈具有有限步近似；物料与能量按实际接受量守恒。

物种数量与参考内能随接受量增量更新，温度、热容与共同压力读取当前候选。气体在同一热容分段内直接更新温度，跨段或组成变化使热状态失效时重新求值。转移响应包含组成和潜热反馈，湿面与干壁共用状态方程。凝固与熔化采用逐格物理约束，统计记录实际变相格数与 64 位守恒批次数。相转换的物料、动量及能量在提交前统一结算，并重建后继相形状。

机械能结算后交接目标面数据，使扩散的面惯量与动量保持同代。独立热步沿用同一候选与提交协议，相变产生的待位移状态交给后续力学处理。算法与尺度见[流体求解](fluid-solver.md)、[气体](gas-state.md)和[热学](thermal-state.md)。

固定表面附着在有限墙的指定面，表面材料、装填量、吸附物与分数量子进度随设备库存和物品事务交接。气体接触按实际开放面积、有限位点和局部分压交换物种，实际质量、动量与内能进入共同候选；墙与涂层、吸附物共享总内能和温度。催化表面按液体实际接触面积等比例改变已有反应的正逆速率，继续使用共同的反应物竞争与热结算。筛面接触使用筛实体面积，普通墙使用实际接触面。

候选代保留权威库存、未决计算、物性表和可复用几何。流动阶段完成后，热接触、导热和各反应阶段复用已经结束使用的临时列；共享暂存池按同时执行的阶段分配地址，能量与机械功账列独立保留至整步结算。液体反应与相变共用物种工作行，固液待处理索引按实际固液规则分配。气液交换把接触提案与接受量计算合并，接收库存和热量完成独立结算；未发布液相行供凝固按实际组成与内能检查活动并继续处理，最后统一组装、检查容量并发布液相，前后阶段分别保留能量审计。凝固先根据平衡状态求转换需求，再检查实际容量和能量；容量不足时丢弃未发布候选并重新分配。`VulkanRegionReactions` 统一录制和调度热化学阶段，黏性、气体输运、导热、相变及液相行按阶段使用专用管线。导热接触图容量反馈汇总全部热浴需求。气体路由的质量余项由实际质量减去物种质量求得。已提交代与候选代各自持有工作区，失败候选保持原子代次隔离。

状态方程保存与步长无关的压力顺应性，压力装配时应用当前时长；宿主导入和实体几何变化重新准备相应派生状态。`VulkanBufferCopyPlan` 并行交接明确的持久字段，压力、输运与热接触复用绑定当前布局和缓冲区的命令模板。颗粒调度读取本次候选的实际颗粒与床层计数：空库存完成必要快照及账目初始化后进入共同输运，有库存时执行运动和床层转换。相变生成的颗粒随后继代进入同一流程。复制计划和命令资源随工作区释放。

<a id="mechanics"></a>
<a id="transport"></a>
<a id="thermochemistry"></a>
设备阶段索引：

- [VulkanLiquidRegion](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidRegion.java)：区域状态、候选槽、同布局编辑导入与采用。
- [VulkanLiquidWorkspace](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidWorkspace.java)、[VulkanLiquidWorkspaceSections](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidWorkspaceSections.java)、[VulkanLiquidThermalStages](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidThermalStages.java)：工作区布局、机械阶段及热学阶段录制。
- [VulkanScratchPool](../../gpu-kernel/src/main/java/mmm/gpu/VulkanScratchPool.java)：流动、导热与反应之间的临时列复用；各布局声明同时使用的列，库存和最终账目保持独立。
- [VulkanLiquidGeometry](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidGeometry.java)、[VulkanMixtureState](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMixtureState.java)、[VulkanMixturePressure](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMixturePressure.java)、[VulkanCoupledPressure](../../gpu-kernel/src/main/java/mmm/gpu/VulkanCoupledPressure.java)：单轴界面几何、局部液气状态方程与共同压力装配；内部面先裁剪整面 PLIC 干区，再与实际开放微面求交，统一得到气液接触面积；[VulkanPressureKernel](../../gpu-kernel/src/main/java/mmm/gpu/VulkanPressureKernel.java) 和 [VulkanPressureMultigrid](../../gpu-kernel/src/main/java/mmm/gpu/VulkanPressureMultigrid.java) 按解析尺度预算完成机械 PCG，预存多重网格父子映射；实际颗粒耦合行采用紧凑矩阵自由求积，其余行使用等价 CSR，初值沿绝对压力交接。
- [plic-face.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/plic-face.glsl)、[VulkanLiquidViscosity](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidViscosity.java)：共同单侧 PLIC 面积、湿壁有效距离、无滑移应力、反冲与耗散热；热接触复用相同单侧面积。验证入口为 `runGpuWetWallValidation`、`runGpuChannelFlowValidation` 和 `runGpuEntityBodyCouplingBenchmark`，公式及尺度见[液体黏性应力](fluid-solver.md#液体黏性应力)。
- [VulkanBufferCopyPlan](../../gpu-kernel/src/main/java/mmm/gpu/VulkanBufferCopyPlan.java)、[VulkanLiquidWorkspaceCopies](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidWorkspaceCopies.java)：明确持久字段的并行复制、候选布局与资源释放。
- [VulkanMixtureFlux](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMixtureFlux.java)、[mixture-flux.comp](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/mixture-flux.comp)：有限驻留面量、设备内多源搜索和共同路由索引；`recordRouteSeeds`、`finishRouteSearch`、`recordRoutes` 依次准备接收空间、传播关闭格目的地和生成出入索引。开放外界承接气体排放。[VulkanLiquidGather](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidGather.java)、[VulkanLiquidMomentum](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidMomentum.java) 和 [VulkanLocalGasTransport](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLocalGasTransport.java) 按同一成员关系交接液气库存、动量、热量与气体压缩功。验证入口为 `runGpuResidentStateValidation`、完整生产颗粒输入和活塞泵送 GameTest。
- [VulkanLiquidMaterialRoutes](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidMaterialRoutes.java)、[VulkanLiquidGather](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidGather.java)、[VulkanLiquidMomentum](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidMomentum.java)：物质输运、归并及动量。
- [VulkanMechanicalEnergy](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMechanicalEnergy.java)：液气与颗粒的共同机械能结算；外侧面混合热按实际质量分配，连通体沿真实外排气路完全排空后将剩余机械能记入环境交换。
- [VulkanGranularMotion](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGranularMotion.java)、[VulkanGranularTransitions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGranularTransitions.java)：颗粒运动、筛面截留与床层迁移；气体占容随共同局部状态更新。
- [VulkanThermalContacts](../../gpu-kernel/src/main/java/mmm/gpu/VulkanThermalContacts.java)、[VulkanThermalConduction](../../gpu-kernel/src/main/java/mmm/gpu/VulkanThermalConduction.java)、[VulkanRegionReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanRegionReactions.java)：实际组成的热载体、共同壁面对流膜、接触热导、成对有限热交换与反应；`runGpuThermalContactsBenchmark` 核对热导及有限热量。
- [FixedSurfaceState](../../model/src/main/java/mmm/thermal/FixedSurfaceState.java)、[SurfaceMaterialDefinition](../../model/src/main/java/mmm/thermal/SurfaceMaterialDefinition.java)、[SurfaceThermalState](../../model/src/main/java/mmm/thermal/SurfaceThermalState.java)：固定表面库存、数据定义与有限墙温度；[VulkanGasLiquidMassTransfer](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasLiquidMassTransfer.java) 结算实际吸附与解吸，[VulkanLiquidReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidReactions.java) 应用接触催化。
- [VulkanLiquidThermodynamics](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidThermodynamics.java)、[liquid-caloric-state.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/liquid-caloric-state.glsl)：载体分段热容和温度反演，供库存与扩散携能共用。
- [VulkanGasThermodynamics](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasThermodynamics.java)、[gas-thermodynamics.comp](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/gas-thermodynamics.comp)、[gas-caloric.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/gas-caloric.glsl)、[gas-thermodynamic-state.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/gas-thermodynamic-state.glsl) 和 [mixture-state.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/mixture-state.glsl)：气体组成物性和共享分段温度反演；热状态发布与相间传质从实际库存和内能求值，纯内能变化复用当前热容段，跨段时执行完整反演。
- [VulkanLiquidDiffusion](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidDiffusion.java)、[VulkanGasLiquidMassTransfer](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasLiquidMassTransfer.java) 与 [gas-liquid-mass-transfer.comp](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/gas-liquid-mass-transfer.comp)：同相扩散，以及共用守恒交接的 Henry 有限速率传质、多挥发组分局部相平衡和共同动量结算；[mixture-pressure-eos.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/mixture-pressure-eos.glsl) 统一局部混合状态和相变接受批次的压力求值。
- [VulkanSolidLiquidReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanSolidLiquidReactions.java)、[VulkanCondensedPhaseChange](../../gpu-kernel/src/main/java/mmm/gpu/VulkanCondensedPhaseChange.java)：颗粒溶解析出和凝固融化，沿同一库存、动量和内能候选提交。
- [ChemicalReaction](../../model/src/main/java/mmm/chemistry/ChemicalReaction.java)、[ChemicalRegistry](../../model/src/main/java/mmm/chemistry/ChemicalRegistry.java)、[VulkanLiquidReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidReactions.java)：计量定义、温度相关正逆动力学、共同反应物竞争与内能结算。`runGpuLiquidReactionsValidation` 覆盖快速反应、竞争、可逆平衡、温度反馈和小量进度恢复。
- [VulkanShaderLayout](../../gpu-kernel/src/main/java/mmm/gpu/VulkanShaderLayout.java) 与各阶段的 `Header/Field`：从宿主布局声明生成 shader 字段常量。

核心液体输运、压力、黏性、导热及实体耦合的阶段编号由宿主声明生成 shader 常量；其余气体、颗粒、相变及观察内核按职责命名阶段、状态、控制字和间接派发槽。shader 变量区分物理量、整数配额、空间坐标与存储索引，打包记录使用具名成员和步长，编码尺度与数值阈值在对应内核中具名保存。观察和展示归约明确区分库存、温度、能量、空间位置和输出编码；共享 GLSL 片段与引用方使用一致的函数名称。

<a id="candidate"></a>
## 初始化与库存编辑

宿主初态捕获统一准备逐格气体库存，覆盖无热学液体和颗粒状态。世界化学能力要求热学，相变要求热学与化学；设备导入和步请求同步检查这组契约。宿主底层仍可保存独立化学状态。

初始化器建立宿主状态和固定几何，再导入设备。冷导入和宿主编辑导入按同代库存计算各载体温度与热容，暂停时也可立即显示温度。运行态编辑捕获同代状态并在私有副本上规划接受量、后继库存与外部效果；设备候选采用后，容器、世界或设备事务按 WAL 和 HEAD 协议落实。相同布局的后继复用设备资源。容器投料和取样沿共享开放面按跨格路径选择附近库存或容积；当前格内按一个连通开放空间处理，非零范围可穿过开放气孔，局部取样限定选中格。宿主编辑读取逐格气体状态，按旧干口和未来接收干容转移受挤气体；同批缩容后仍有干容的格参与接收并结算压缩功，联合交接库存、动量与热账。

- [HostFluidState](../../solver/src/main/java/mmm/fluid/HostFluidState.java)、[DomainInventory](../../solver/src/main/java/mmm/fluid/DomainInventory.java)、[DomainCollision](../../solver/src/main/java/mmm/fluid/DomainCollision.java)、[DomainPhaseInventory](../../solver/src/main/java/mmm/fluid/DomainPhaseInventory.java)：宿主状态与有限库存、几何编辑。
- [HostMixtureEdit](../../solver/src/main/java/mmm/fluid/HostMixtureEdit.java)、[SpatialGasEdits](../../solver/src/main/java/mmm/fluid/SpatialGasEdits.java)、[CellMacStage](../../solver/src/main/java/mmm/fluid/CellMacStage.java)：外部物料、碰撞及占容编辑共同准备液气库存、面动量和实际载体耗散热；开放外界面保留环境惯量。
- [LiquidCollisionEdit](../../solver/src/main/java/mmm/fluid/LiquidCollisionEdit.java)：批量碰撞先按未来孔容保留本格库存，再沿原开放面搬迁超容量部分；质量、物种和携能复用共同库存转移。`HostFluidState.applyCollisionEdits` 一次发布完整几何与库存，空间不足时保留原状态，世界采样层读取位移统计。
- [GasInitialization](../../solver/src/main/java/mmm/fluid/GasInitialization.java)：显式气腔初态导入逐格库存；续算和存档直接保存空间状态。
- [PressureSystem](../../solver/src/main/java/mmm/fluid/PressureSystem.java)：宿主共享相几何与局部状态方程压力查询。
- [MovingBodyWetGeometry](../../solver/src/main/java/mmm/fluid/MovingBodyWetGeometry.java)、[WaterContactGeometry](../../solver/src/main/java/mmm/fluid/WaterContactGeometry.java)：基于共享 PLIC 和逐格气压计算实体浸液、压力作用与水接触。
- [FluidRegionFactory](../../fabric/src/main/java/mmm/simulation/FluidRegionFactory.java)、[FluidRegionWorldInteraction](../../fabric/src/main/java/mmm/simulation/FluidRegionWorldInteraction.java)：创建与世界碰撞。
- [MinecraftContainerTransferPlan](../../fabric/src/main/java/mmm/simulation/MinecraftContainerTransferPlan.java)、[OwnershipTransactionCoordinator](../../fabric/src/main/java/mmm/simulation/OwnershipTransactionCoordinator.java)：容器与世界归属事务。容器接触选择、请求解析和执行共用单笔操作的宿主候选，提交核对求解代次；公开预览释放其候选，正式提交按当前状态重新计算实际接受量。

<a id="persistence"></a>
## 保存和故障恢复

保存显式捕获已提交设备代，完整写入同代库存、动量、内能与待处理状态。冷恢复按 HEAD 和 WAL 分类未决事务，读取完整同代数据和世界定义，准备碰撞并导入设备。碰撞刷新受阻按世界输入失败暂停，保留刷新请求，世界条件恢复后重新规划。有限设备恢复读取原子发布的正式 WAL，中断发布留下的普通临时文件保留在磁盘并跳过。公开转移的持久化收据在 WAL 扫描完整且缺少对应日志时归类为未提交；扫描存在存储问题时保持待恢复状态。宿主几何编辑的气体挤排按连通域有效气容建立线性供需匹配，实际物种、内能和动量沿同一转移记录交接。普通候选失败保留原代；设备丢失时暂停受影响区域，按事务与最近一致 checkpoint 恢复。HEAD 损坏或事务成员不明确时保留具体暂停原因。协议见[区域存档](region-state.md)与[世界归属和容器](world-ownership-and-containers.md)。

- [MinecraftRegionCheckpointSource](../../fabric/src/main/java/mmm/simulation/MinecraftRegionCheckpointSource.java)、[RegionCheckpointRestorer](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointRestorer.java)：捕获与恢复。
- [FluidRegionOperations](../../fabric/src/main/java/mmm/simulation/FluidRegionOperations.java)：区域生命周期与持久化协调；卸载区域的运行意图由 `setUnloadedRunIntent` 同步读取完整 checkpoint、提交新代并释放宿主状态，保留原有可用性，未完成事务先执行恢复。
- [FabricTransferApi](../../fabric/src/main/java/mmm/simulation/FabricTransferApi.java)、[PublicTransferReceiptStore](../../fabric/src/main/java/mmm/simulation/persistence/PublicTransferReceiptStore.java)：公开转移收据分类与持久结果，启动时由 `reconcilePendingReceipts` 对照 WAL 索引。
- 验证入口：`region-state,containers,ownership,lifecycle`，以及 `runGpuResidentStateValidation`。

<a id="client"></a>
## 查询与展示

区域观察读取已提交代的库存摘要、液位、热设备及实体接触；展示快照传给客户端生成液面、颗粒与显色。登录、切换维度及复活时向玩家补发当前维度的已加载区域快照，暂停和休眠区域也参与补发。查询不改变设备代。容器与接口使用实际接受量及同代库存，传感器按测量范围内的固定开放容量计算液位；水湿接触在成功提交后应用灭火效果。入口：[FluidSnapshotPublisher](../../fabric/src/main/java/mmm/simulation/FluidSnapshotPublisher.java)、[VulkanRegionObservation](../../gpu-kernel/src/main/java/mmm/gpu/VulkanRegionObservation.java)、[VulkanRegionDisplay](../../gpu-kernel/src/main/java/mmm/gpu/VulkanRegionDisplay.java)。详见[展示](fluid-presentation.md)与[外部 API](external-api.md)。

### 样品分析与工艺曲线

容器分析从手持物品的实际载荷生成服务端只读摘要，沿匹配的世界定义把组成与内能换算为展示数量。液体浓度以样品总体积计算，气体体积与浓度注明标准态；完整物种库存提供摩尔分数。客户端支持滚动和两份样品并排比较，基准在维度切换与断线时清除。分析保持区域与物品库存，使用时无需区域回读。

传感器在原有观测完成后追加有界会话记录。区域在成功机械步或独立热步后累计仿真时长；暂停、测点不可用和物理组缺失使曲线断开，暂停时的手动步留下独立观测点。配置和区域实例改变后清空旧序列。样品和曲线均由客户端发起打开请求，以会话标识与维度匹配回复；曲线按递增请求序号接收更新。关闭、换维或测点卸载后停止请求，旧会话回复丢弃；记录随方块实体会话释放。显示模式和样品基准由各客户端独立保存。

- [SampleAnalysis](../../fabric/src/main/java/mmm/simulation/SampleAnalysis.java)、[SampleAnalysisPayload](../../fabric/src/main/java/mmm/network/SampleAnalysisPayload.java)、[SampleAnalysisScreen](../../fabric/src/client/java/mmm/client/SampleAnalysisScreen.java)：载荷分析、展示摘要与比较界面。
- [ProcessTrendHistory](../../fabric/src/main/java/mmm/network/ProcessTrendHistory.java)、[ProcessTrendNetwork](../../fabric/src/main/java/mmm/network/ProcessTrendNetwork.java)、[ProcessTrendScreen](../../fabric/src/client/java/mmm/client/ProcessTrendScreen.java)：测点记录、只读请求与曲线。
- [ObservationOpen](../../fabric/src/client/java/mmm/client/ObservationOpen.java)：打开请求、维度与回复序号的会话匹配。
- [FluidRenderBatch](../../fabric/src/client/java/mmm/client/FluidRenderBatch.java)、[FluidRenderTiming](../../fabric/src/client/java/mmm/client/FluidRenderTiming.java)：持久绘制数据、透明排序与异步 GPU 计时。
- 验证入口：`protocol` 中的 `ClientSurfaceValidation`、`SampleAnalysisValidation`、`SampleComparisonValidation`、`ObservationOpenValidation` 与 `ProcessTrendHistoryValidation`；专服请求处理见 `MultiplayerObservationGameTest`。

<a id="gameplay"></a>
## 游戏入口

探针选区建立区域；桶、接口、发射器、气体源、换热器和耕地通过区域事务交接库存。演示复用相同初始化、调度与提交流程；原版静水池接管与归还使用世界归属日志。命令见[命令速查](../maintenance/commands.md)，后续玩法见[产品路线](../roadmap/product-roadmap.md)。

薄壁管道按有限管段保存液体、气体、床库存与内能，沿开放管口按压差和高差输运。区域端口在共同候选中交接物质及能量，管壁复用世界环境热场；管网、环境和区域通过同一世界 HEAD 保存。管件几何、段平均模型及操作见[薄壁管道](physical-pipes.md)。

- [PipeNetworkSolver](../../solver/src/main/java/mmm/pipe/PipeNetworkSolver.java)：管段流动、相变、床载与热量结算。
- [PipeRuntime](../../fabric/src/main/java/mmm/simulation/PipeRuntime.java)、[PipeRegionExchange](../../fabric/src/main/java/mmm/simulation/PipeRegionExchange.java)：世界管网、区域交接与生命周期。
- 验证入口：`pipe-network`、`PipeLiquidTransportValidation` 与 `PipeWallHeatValidation`。

区域创建与环境热场的完整方案见[空间选区与环境热场](../archive/proposals/region-and-environment-heat-plan.md)，涵盖空间选择、范围增长及世界坐标热库存；实施状态由当前计划维护。

<a id="performance"></a>
<a id="diagnosis"></a>
## 性能和故障定位

完整步计时覆盖输入、设备计算、提交和必要交接；编辑与保存分别记录回读、规划和上传成本，冷初始化单列。40 ms 后按节拍降频，连续三步超过 200 ms 暂停。原 64³／0.5 m 完整生产步采用 50 ms 预算。验证场景与判定方法见[验证指南](../maintenance/validation.md)，设备性能要求见[后端参考](../roadmap/performance-and-backends.md)。
