# 当前设计书

本文按完整职责链组织当前模型、数据归属和代码索引。稳定公式和字段详见各专题；待办由[当前实施计划](../roadmap/solver-consolidation-plan.md)维护，验证方法与预算由[验证指南](../maintenance/validation.md)维护。实现步骤、内部阈值和工作区布局以代码为准。

## 阅读导航

先读本节和[模块职责](#模块职责)，再按任务类型阅读相应章节。

| 任务 | 本文章节 | 专题 | 代码入口 |
|---|---|---|---|
| 排错某物理现象 | [完整计算步](#physics)及其设备阶段索引；热墙与环境见[从游戏输入到设备候选](#runtime) | [热状态](thermal-state.md)、[流体求解](fluid-solver.md)、[气体](gas-state.md) | `VulkanRegionReactions`、`VulkanThermalContacts`、`VulkanGasLiquidMassTransfer`；装置级定位用 `ApparatusDiagnostics` |
| 改工业装置 | [从游戏输入到设备候选](#runtime)的工业方块与布局各段 | [方块热材料](thermal-state.md#方块热材料)、[世界与容器事务](world-ownership-and-containers.md) | `IndustrialLayout` 与各装置布局类、`IndustrialOperations`、`IndustrialWorkbench`、`GpuControllerFixture` |
| 改 GPU 着色器 | [完整计算步](#physics)的设备阶段索引 | [GPU 执行原则](gpu-execution-principles.md)、相应物理专题 | `gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/`、`VulkanShaderLayout` 与对应 `Vulkan*` 阶段类 |
| 跑验证 | 各章末尾的验证入口 | [验证指南](../maintenance/validation.md)开头导航 | `fabric/src/test/java/mmm/simulation/` 下的 `*Validation` 主类 |

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

一个区域持有完整物理域、材料定义和提交代。域内采用固定 8³ 单元块，块内保持规则网格，实际登记的块按空间顺序紧凑存储，单块与多块使用同一套寻址和求解。单元坐标、六邻接和 MAC 面身份由 `SpatialGrid` 统一提供；相邻块引用同一个共享面，压力包含跨块项，输运按同一份接受通量更新两侧库存。固定拓扑在创建域时上传为只读设备数据，各物理内核共同引用。块决定存储，物理连通和设备接触决定共同推进或暂停的范围。网格布局与多重网格预条件见[空间分辨率与网格布局](fluid-solver.md#两级空间分辨率)。

空间选区和水池按实际占地登记、采样碰撞与检查世界区块，区域内固体及开放空间由实际碰撞形状决定。资源计费使用驻留单元总数，每区域和服务器总量受配置预算约束。所有块共享一个候选和区域 HEAD，完整 checkpoint 保存块成员与空间地址。

入口：[SpatialGrid](../../model/src/main/java/mmm/spatial/SpatialGrid.java)、[VulkanSpatialGrid](../../gpu-kernel/src/main/java/mmm/gpu/VulkanSpatialGrid.java)、[spatial-grid.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/spatial-grid.glsl)、[FluidRegionFactory.grid](../../fabric/src/main/java/mmm/simulation/FluidRegionFactory.java)、[PhysicalActivityGraph](../../solver/src/main/java/mmm/fluid/PhysicalActivityGraph.java)。跨块完整步验证入口为 `runGpuSpatialDomainValidation`。

### 数据驱动的物质定义

世界启动时从 `data/mmm/mmm` 下的 JSON 加载液体、气体组分、化学物种与反应、颗粒及热学定义，形成完整的 `FluidDefinitions`。宿主编辑、容器操作、恢复和 GPU 表生成均读取这份世界定义；世界定义随能力包保存，checkpoint 引用其指纹。各目录的字段由对应专题说明：液体材料见[材料](material-state.md)，物种与反应见[化学](chemistry-state.md)，气体组分见[气体](gas-state.md)，热容、相变与有限墙见[热学](thermal-state.md)。

入口：[WorldMaterialDataPackLoader](../../fabric/src/main/java/mmm/simulation/WorldMaterialDataPackLoader.java)、[RegistryBundleLoader](../../fabric/src/main/java/mmm/simulation/RegistryBundleLoader.java)、[FluidDefinitions](../../solver/src/main/java/mmm/fluid/FluidDefinitions.java)、[VulkanFluidStateCodec](../../gpu-kernel/src/main/java/mmm/gpu/VulkanFluidStateCodec.java)。

<a id="units"></a>
### SI 物理量与整数编码

Minecraft 一个方块的边长标定为 1 m。物理定义统一使用 SI，化学真实物种使用 mol。下表是单位约定的统一入口。

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

质量以整数份额保存：新建库存按最接近值量化一次，拆分按整数比例分配并由保留份额承接余数，合并和差额使用受检整数运算。真实物种每个 μmol 的质量须能精确换成整数，因此自定义摩尔质量取 0.0001 kg/mol 的整数倍。机械、热学、密度及显示读取派生 kg；物质审计、容器、内存交接与存档直接比较整数。

状态方程、相平衡和共同压力使用绝对压力，查询时按接口声明输出绝对压或表压。仿真步长和累计仿真时间用 s；Minecraft tick 是服务器调度次数，正常节拍为 20 tick/s。玩家展示由 `UnitFormat` 选择量级，温度统一以 K 展示；一桶的游戏操作容量为 1 m³。

入口：[Mass](../../model/src/main/java/mmm/units/Mass.java)、[SolverUnitScale](../../model/src/main/java/mmm/units/SolverUnitScale.java)、[ChemicalAmount](../../model/src/main/java/mmm/chemistry/ChemicalAmount.java)、[UnitFormat](../../model/src/main/java/mmm/units/UnitFormat.java)。验证入口为 `region-state`、`containers`、`protocol` 及 GPU 场景。

<a id="runtime"></a>
## 从游戏输入到设备候选

1. 启动恢复世界能力包及未完成事务，组装设备后端和区域服务。设备初始化失败时释放设备并记录原因，世界保留不可推进的区域运行时。
2. 世界输入采集碰撞、热接触与实体状态；`RegionTickUseCase` 按暂停、休眠与节拍选择机械步或独立热步。游戏内手动单步复用同一输入与提交流程。
3. 请求捕获已提交代与输入，Vulkan 在私有状态执行计算，生成完整设备候选与统计。
4. 应用层核对区域身份、代次、后端与布局，采用候选后再发布实体和世界效果。失败候选释放，已提交代继续有效。
5. 日常查询与展示读取设备观察；checkpoint 和宿主编辑显式导出所需代次。

区域按同代物理连通关系划分活动组：开放格面、局部气体、颗粒接触范围、实体扫掠范围和有限热设备端口共同决定分组。缺失区块使关联组暂停，其余组沿同一设备候选继续推进；暂停组保留库存、内能、动量和未决相变任务。详见[运行时](runtime-and-lifecycle.md)。

**世界热场。** 固定世界方块与域外有限空气由维度共享热场持有，每个固体按实际占据体积保存质量与内能，材料由方块标签选择，见[方块热材料](thermal-state.md#方块热材料)。区域设备行引用唯一世界节点，多个区域可共同加热同一面墙。区域墙接触随实际接受步长执行，环境内部扩散与远场交换低频推进；失败候选同时丢弃区域和环境更改。checkpoint 通过唯一世界 HEAD 发布环境、全部区域和删除标记。有限换热器保留自身物品库存，普通墙、筛表面装填与吸附物由共享环境持有。

**区域扩展。** 空间选区由探针两角确认。液体或颗粒接近边缘时，GPU 观察给出扩展方向；运行入口预检预算、区块与重叠后，按 8³ 计算块生成更大候选，搬迁库存、气体、动量、几何及待处理任务，环境空气与原版水沿同一候选接管。

**世界碰撞与运动。** 世界方块变更唤醒相邻区域并刷新碰撞与热墙。固定几何包含选区内部的微盒及紧邻外侧面的固体微面，宿主挤排、GPU 输运和壁面剪切共用其开放面积。活塞、实体与外部模组结构（`MovingStructureApi`）作为运动体参与共同候选：接触速度含平动与转动，压力与黏性作用产生冲量、力矩和外功，运动结束后占容及有限墙库存交回静态几何。外部结构的旋转与动力负载见 [Create 适配计划](../roadmap/create-compatibility-plan.md)。

**工业方块。** 物料箱保存玩家放入的实际容器；流体端口连接外侧物料箱和内侧实际接触格，按在线速率与红石信号执行容器事务，取样组成来自接触格。阀门的五档开度改变通流截面，碰撞与显示共享同一几何。传感器从已提交状态读取液位、温度、绝对压力或截面流量，发布不可变 `ProcessSensorSnapshot` 并映射为红石输出。筛网按实际网格面保存孔径与开孔率，液气通流与颗粒截留读取同一配置。机械驱动器沿所点面的法线驱动内部构件，运动与外功沿共同候选结算。配方与获取顺序见[生存模式入门](../gameplay/survival-progression.md)，贴图来源见[工业展示素材](../maintenance/visual-assets.md)。

**工业布局。** 游戏内建造与离线夹具共用方块布局、有限墙初始化和端口生成。逆流装置由窄通道、有限铜壁与绝热填充构成；蒸馏单元以绝热玻璃为外壳，蒸气在铁质冷墙上冷凝，串级蒸馏把实际馏分容器交给下一台。

主要入口：

- [MmmRuntimeFactory](../../fabric/src/main/java/mmm/simulation/MmmRuntimeFactory.java)、[MinecraftSimulationLoop](../../fabric/src/main/java/mmm/simulation/MinecraftSimulationLoop.java)：世界服务、设备和 tick。
- [RegionTickUseCase](../../app/src/main/java/mmm/application/RegionTickUseCase.java)、[RegionApplicationService](../../app/src/main/java/mmm/application/RegionApplicationService.java)、[MinecraftRegionTick](../../fabric/src/main/java/mmm/simulation/MinecraftRegionTick.java)：调度、提交与手动单步。
- [FluidRegion](../../fabric/src/main/java/mmm/simulation/FluidRegion.java)：已提交代、`hostSnapshot`、宿主编辑与设备候选采用。
- [PhysicalActivityGraph](../../solver/src/main/java/mmm/fluid/PhysicalActivityGraph.java)、[MinecraftGroupAvailability](../../fabric/src/main/java/mmm/simulation/MinecraftGroupAvailability.java)：活动组与区块可用性。
- [MinecraftRegionInputs](../../fabric/src/main/java/mmm/simulation/MinecraftRegionInputs.java)、[MinecraftWorldUpdates](../../fabric/src/main/java/mmm/simulation/MinecraftWorldUpdates.java)、[ExternalMovingStructures](../../fabric/src/main/java/mmm/simulation/ExternalMovingStructures.java)：世界碰撞、活塞、实体与外部结构输入。
- [WorldThermalEnvironment](../../fabric/src/main/java/mmm/simulation/WorldThermalEnvironment.java)、[FiniteHeatExchangerOperations](../../fabric/src/main/java/mmm/simulation/FiniteHeatExchangerOperations.java)：普通墙库存、真实接触与有限设备。
- [IndustrialOperations](../../fabric/src/main/java/mmm/simulation/IndustrialOperations.java)、[IndustrialDialogs](../../fabric/src/main/java/mmm/simulation/IndustrialDialogs.java)、[MinecraftProcessOperations](../../fabric/src/main/java/mmm/simulation/MinecraftProcessOperations.java)：物料箱、端口、阀门、传感器、驱动与配置面板。
- [MaterialBoxBlockEntity](../../fabric/src/main/java/mmm/block/MaterialBoxBlockEntity.java)、[MaterialBoxItemStorage](../../fabric/src/main/java/mmm/simulation/MaterialBoxItemStorage.java)、[GasContents](../../model/src/main/java/mmm/transfer/GasContents.java)：物料箱库存、Fabric 自动化（契约见[外部 API](external-api.md#fabric-物料箱自动化)）与气体载荷。
- [IndustrialLayout](../../fabric/src/main/java/mmm/simulation/IndustrialLayout.java)：装置布局记录、`Scenario` 分派，以及共用的外壳、端口与物料箱、有限墙放置。各装置布局位于 [DistillationLayout](../../fabric/src/main/java/mmm/simulation/DistillationLayout.java)、[CounterflowLayout](../../fabric/src/main/java/mmm/simulation/CounterflowLayout.java)、[ScreeningLayout](../../fabric/src/main/java/mmm/simulation/ScreeningLayout.java)（筛分与冷壁结晶）、[SurfaceChannelLayout](../../fabric/src/main/java/mmm/simulation/SurfaceChannelLayout.java)（吸附与催化）和 [ConnectedTanksLayout](../../fabric/src/main/java/mmm/simulation/ConnectedTanksLayout.java)（双罐阀门）。
- [IndustrialWorkbench](../../fabric/src/main/java/mmm/simulation/IndustrialWorkbench.java)、[GpuControllerFixture](../../fabric/src/test/java/mmm/simulation/GpuControllerFixture.java)：游戏内建造与离线夹具。
- [GpuProcessFlowsValidation](../../fabric/src/test/java/mmm/simulation/GpuProcessFlowsValidation.java)：`-PgpuProcessScenario` 场景分派。工业场景按装置族分文件：[IndustrialPortFlows](../../fabric/src/test/java/mmm/simulation/IndustrialPortFlows.java)、[CounterflowFlows](../../fabric/src/test/java/mmm/simulation/CounterflowFlows.java)、[SurfaceProcessFlows](../../fabric/src/test/java/mmm/simulation/SurfaceProcessFlows.java)、[ScreeningFlows](../../fabric/src/test/java/mmm/simulation/ScreeningFlows.java)、[DistillationFlows](../../fabric/src/test/java/mmm/simulation/DistillationFlows.java)；[ProcessFlowSupport](../../fabric/src/test/java/mmm/simulation/ProcessFlowSupport.java) 提供共用料液与端口工具，[ApparatusDiagnostics](../../fabric/src/test/java/mmm/simulation/ApparatusDiagnostics.java) 输出任意装置的逐墙能量变化和逐格液量。
- [VulkanFluidSolverBackend](../../gpu-kernel/src/main/java/mmm/gpu/VulkanFluidSolverBackend.java)、[VulkanFluidStateCodec](../../gpu-kernel/src/main/java/mmm/gpu/VulkanFluidStateCodec.java)：设备请求、导入与导出。

<a id="physics"></a>
## 完整计算步

一个完整步在设备上依次执行以下阶段，全部写入同一份候选，提交前统一结算物料、动量与能量：

1. **几何与力学**：准备同代固定几何、相占据与物性，以有限应变冲量结算黏性，再求半隐式液气共同压力。运动体的压力、反冲与外功使用源态共同压力。
2. **输运**：两相按有限驻留时间接受出流，共同面速度由实际接受量确定。目标姿态关闭孔容时，设备搜索仍有孔容的接收格；无可达接收空间时保留已提交状态。液气质量、组分、携带内能、动量与耗散热使用同一组路由，气体压缩功按后继库存与孔容结算。
3. **导热**：以载体有限热容和接触热导计算成对热量，流体与有限墙两端使用同一接受量。液体壁面共用自然与强制对流膜，与实际润湿面积、墙厚及墙导热率共同形成热阻，公式见[热状态](thermal-state.md#共同凝聚相有限体积导热)。
4. **相变与传质**：按实际供体、容量、可用内能与局部平衡接受完整物种批次。气液接触按同格界面与相邻配对交接物料和携带内能，混合热在批末按实际接收量结算，本步内的温度反馈为有限步近似。凝固与熔化采用逐格物理约束。
5. **表面与反应**：固定表面按实际开放面积、有限位点和局部分压交换物种，墙、涂层与吸附物共享内能；催化表面按接触面积改变已有反应的正逆速率。液体反应按温度相关动力学与共同反应物竞争推进。
6. **颗粒**：按候选中的实际颗粒与床层执行运动、筛面截留和床层转换；相变生成的颗粒随后继代进入同一流程。

独立热步沿用同一候选与提交协议，只执行导热、相变与反应。导热、反应和相变按完整请求时长执行，共用输运后的接触几何。已提交代与候选代各自持有工作区，失败候选不影响已提交代。算法与尺度见[流体求解](fluid-solver.md)、[气体](gas-state.md)和[热学](thermal-state.md)。

<a id="mechanics"></a>
<a id="transport"></a>
<a id="thermochemistry"></a>
设备阶段索引：

- [VulkanLiquidRegion](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidRegion.java)：区域状态、候选槽、编辑导入与采用。
- [VulkanLiquidWorkspace](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidWorkspace.java)、[VulkanLiquidWorkspaceSections](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidWorkspaceSections.java)、[VulkanLiquidThermalStages](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidThermalStages.java)、[VulkanScratchPool](../../gpu-kernel/src/main/java/mmm/gpu/VulkanScratchPool.java)：工作区布局、阶段录制与临时列复用。
- [VulkanBufferCopyPlan](../../gpu-kernel/src/main/java/mmm/gpu/VulkanBufferCopyPlan.java)、[VulkanLiquidWorkspaceCopies](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidWorkspaceCopies.java)：持久字段复制与候选布局。
- [VulkanLiquidGeometry](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidGeometry.java)、[FlipPressurePhaseGeometry](../../solver/src/main/java/mmm/fluid/FlipPressurePhaseGeometry.java)、[plic-face.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/plic-face.glsl)：宿主与设备共用的界面重建与气液接触面积。
- [VulkanMixtureState](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMixtureState.java)、[VulkanMixturePressure](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMixturePressure.java)、[VulkanCoupledPressure](../../gpu-kernel/src/main/java/mmm/gpu/VulkanCoupledPressure.java)、[VulkanPressureKernel](../../gpu-kernel/src/main/java/mmm/gpu/VulkanPressureKernel.java)、[VulkanPressureMultigrid](../../gpu-kernel/src/main/java/mmm/gpu/VulkanPressureMultigrid.java)、[mixture-pressure-eos.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/mixture-pressure-eos.glsl)：局部液气状态方程与共同压力求解。
- [VulkanLiquidViscosity](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidViscosity.java)：湿壁无滑移应力、反冲与耗散热，公式见[液体黏性应力](fluid-solver.md#液体黏性应力)。
- [VulkanMixtureFlux](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMixtureFlux.java)、[mixture-flux.comp](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/mixture-flux.comp)：有限驻留面量与接收空间搜索。
- [VulkanLiquidMaterialRoutes](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidMaterialRoutes.java)、[VulkanLiquidGather](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidGather.java)、[VulkanLiquidMomentum](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidMomentum.java)、[VulkanLocalGasTransport](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLocalGasTransport.java)：液气库存、动量、热量与压缩功的路由交接。
- [VulkanMechanicalEnergy](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMechanicalEnergy.java)：液气与颗粒的共同机械能结算。
- [VulkanGranularMotion](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGranularMotion.java)、[VulkanGranularTransitions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGranularTransitions.java)：颗粒运动、筛面截留与床层迁移。
- [VulkanThermalContacts](../../gpu-kernel/src/main/java/mmm/gpu/VulkanThermalContacts.java)、[VulkanThermalConduction](../../gpu-kernel/src/main/java/mmm/gpu/VulkanThermalConduction.java)：热载体、壁面对流膜、接触热导与成对有限热交换。
- [VulkanRegionReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanRegionReactions.java)：热化学阶段的统一录制与调度。
- [VulkanLiquidThermodynamics](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidThermodynamics.java)、[liquid-caloric-state.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/liquid-caloric-state.glsl)：液体分段热容与温度反演。
- [VulkanGasThermodynamics](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasThermodynamics.java)、[gas-caloric.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/gas-caloric.glsl)、[mixture-state.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/mixture-state.glsl)：气体组成物性与温度反演。
- [VulkanLiquidDiffusion](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidDiffusion.java)、[VulkanGasLiquidMassTransfer](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasLiquidMassTransfer.java)：同相扩散、Henry 有限速率传质、多挥发组分相平衡及表面吸附与解吸。
- [VulkanSolidLiquidReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanSolidLiquidReactions.java)、[VulkanCondensedPhaseChange](../../gpu-kernel/src/main/java/mmm/gpu/VulkanCondensedPhaseChange.java)：颗粒溶解析出和凝固融化。
- [ChemicalReaction](../../model/src/main/java/mmm/chemistry/ChemicalReaction.java)、[ChemicalRegistry](../../model/src/main/java/mmm/chemistry/ChemicalRegistry.java)、[VulkanLiquidReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidReactions.java)：反应计量、正逆动力学、反应物竞争与接触催化。
- [FixedSurfaceState](../../model/src/main/java/mmm/thermal/FixedSurfaceState.java)、[SurfaceMaterialDefinition](../../model/src/main/java/mmm/thermal/SurfaceMaterialDefinition.java)、[SurfaceThermalState](../../model/src/main/java/mmm/thermal/SurfaceThermalState.java)：固定表面库存、数据定义与墙温。
- [VulkanShaderLayout](../../gpu-kernel/src/main/java/mmm/gpu/VulkanShaderLayout.java) 与各阶段的 `Header/Field`：从宿主布局声明生成 shader 字段常量。

shader 变量区分物理量、整数配额、空间坐标与存储索引，打包记录使用具名成员，编码尺度与数值阈值在对应内核中具名保存；共享 GLSL 片段与引用方使用一致的函数名称。

<a id="candidate"></a>
## 初始化与库存编辑

世界化学能力要求热学，相变要求热学与化学；设备导入和步请求同步检查这组契约。初始化器建立宿主状态和固定几何，再导入设备，导入时即按同代库存求出温度与热容。运行态编辑捕获同代状态，在私有副本上规划接受量、后继库存与外部效果；设备候选采用后，容器、世界或设备事务按 WAL 和 HEAD 协议落实。容器投料和取样沿共享开放面选择附近库存或容积；编辑挤出的气体按未来接收干容转移，库存、动量与热账联合交接。

- [HostFluidState](../../solver/src/main/java/mmm/fluid/HostFluidState.java)、[DomainInventory](../../solver/src/main/java/mmm/fluid/DomainInventory.java)、[DomainCollision](../../solver/src/main/java/mmm/fluid/DomainCollision.java)、[DomainPhaseInventory](../../solver/src/main/java/mmm/fluid/DomainPhaseInventory.java)：宿主状态、有限库存与几何编辑。
- [HostMixtureEdit](../../solver/src/main/java/mmm/fluid/HostMixtureEdit.java)、[SpatialGasEdits](../../solver/src/main/java/mmm/fluid/SpatialGasEdits.java)、[CellMacStage](../../solver/src/main/java/mmm/fluid/CellMacStage.java)、[LiquidCollisionEdit](../../solver/src/main/java/mmm/fluid/LiquidCollisionEdit.java)：外部物料、碰撞及占容编辑的液气库存、面动量与耗散热。
- [GasInitialization](../../solver/src/main/java/mmm/fluid/GasInitialization.java)、[PressureSystem](../../solver/src/main/java/mmm/fluid/PressureSystem.java)：气腔初态与宿主压力查询。
- [MovingBodyWetGeometry](../../solver/src/main/java/mmm/fluid/MovingBodyWetGeometry.java)、[WaterContactGeometry](../../solver/src/main/java/mmm/fluid/WaterContactGeometry.java)：实体浸液、压力作用与水接触。
- [FluidRegionFactory](../../fabric/src/main/java/mmm/simulation/FluidRegionFactory.java)、[FluidRegionWorldInteraction](../../fabric/src/main/java/mmm/simulation/FluidRegionWorldInteraction.java)：创建与世界碰撞。
- [MinecraftContainerTransferPlan](../../fabric/src/main/java/mmm/simulation/MinecraftContainerTransferPlan.java)、[OwnershipTransactionCoordinator](../../fabric/src/main/java/mmm/simulation/OwnershipTransactionCoordinator.java)：容器与世界归属事务；公开预览与正式提交各自持有候选。

<a id="persistence"></a>
## 保存和故障恢复

保存显式捕获已提交设备代，完整写入同代库存、动量、内能与待处理状态。冷恢复按 HEAD 和 WAL 分类未决事务，读取完整同代数据和世界定义，准备碰撞并导入设备。普通候选失败保留原代；碰撞刷新受阻或设备丢失时暂停受影响区域，保留具体原因，条件恢复后按事务与最近一致 checkpoint 继续。协议见[区域存档](region-state.md)与[世界归属和容器](world-ownership-and-containers.md)。

- [MinecraftRegionCheckpointSource](../../fabric/src/main/java/mmm/simulation/MinecraftRegionCheckpointSource.java)、[RegionCheckpointRestorer](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointRestorer.java)：捕获与恢复。
- [FluidRegionOperations](../../fabric/src/main/java/mmm/simulation/FluidRegionOperations.java)：区域生命周期与持久化协调。
- [FabricTransferApi](../../fabric/src/main/java/mmm/simulation/FabricTransferApi.java)、[PublicTransferReceiptStore](../../fabric/src/main/java/mmm/simulation/persistence/PublicTransferReceiptStore.java)：公开转移收据与启动对账。
- 验证入口：`region-state,containers,ownership,lifecycle`，以及 `runGpuResidentStateValidation`。

<a id="client"></a>
## 查询与展示

区域观察读取已提交代的库存摘要、液位、热设备及实体接触，查询不改变设备代；展示快照传给客户端生成液面、颗粒与显色。玩家登录、切换维度及复活时补发当前维度的已加载区域快照。入口：[FluidSnapshotPublisher](../../fabric/src/main/java/mmm/simulation/FluidSnapshotPublisher.java)、[VulkanRegionObservation](../../gpu-kernel/src/main/java/mmm/gpu/VulkanRegionObservation.java)、[VulkanRegionDisplay](../../gpu-kernel/src/main/java/mmm/gpu/VulkanRegionDisplay.java)。详见[展示](fluid-presentation.md)与[外部 API](external-api.md)。

容器分析从手持物品的实际载荷生成服务端只读摘要，按世界定义换算组成与内能，客户端可并排比较两份样品。传感器在每次观测后追加有界会话记录，客户端以曲线显示；暂停、测点不可用和物理组缺失使曲线断开。

- [SampleAnalysis](../../fabric/src/main/java/mmm/simulation/SampleAnalysis.java)、[SampleAnalysisPayload](../../fabric/src/main/java/mmm/network/SampleAnalysisPayload.java)、[SampleAnalysisScreen](../../fabric/src/client/java/mmm/client/SampleAnalysisScreen.java)：样品分析与比较界面。
- [ProcessTrendHistory](../../fabric/src/main/java/mmm/network/ProcessTrendHistory.java)、[ProcessTrendNetwork](../../fabric/src/main/java/mmm/network/ProcessTrendNetwork.java)、[ProcessTrendScreen](../../fabric/src/client/java/mmm/client/ProcessTrendScreen.java)：测点记录与曲线。
- [ObservationOpen](../../fabric/src/client/java/mmm/client/ObservationOpen.java)：打开请求与回复的会话匹配。
- [FluidRenderBatch](../../fabric/src/client/java/mmm/client/FluidRenderBatch.java)、[FluidRenderTiming](../../fabric/src/client/java/mmm/client/FluidRenderTiming.java)：持久绘制数据、透明排序与 GPU 计时。
- 验证入口：`protocol` 中的客户端与观察验证；专服请求处理见 `MultiplayerObservationGameTest`。

<a id="gameplay"></a>
## 游戏入口

探针选区建立区域；桶、接口、发射器、气体源、换热器和耕地通过区域事务交接库存。演示复用相同初始化、调度与提交流程，工作台管理预设切换、重置和区域生命周期。毛细实验在创建瞬态域时派生专用材料定义。工业装置在放置方块前统一检查全部单元的区域名额、单元预算及与现有区域的重叠。原版静水池接管与归还使用世界归属日志。命令见[命令速查](../maintenance/commands.md)，后续玩法见[产品路线](../roadmap/product-roadmap.md)。

- [DemoWorkbench.execute](../../fabric/src/main/java/mmm/simulation/DemoWorkbench.java)、[MinecraftDebugFixtures.runTransient](../../fabric/src/main/java/mmm/simulation/MinecraftDebugFixtures.java)：演示操作、世界脚手架和候选采用。
- [FluidRegionFactory.createTensionDomain](../../fabric/src/main/java/mmm/simulation/FluidRegionFactory.java)、[DebugScenarioInitialization.tensionDefinitions](../../fabric/src/main/java/mmm/simulation/DebugScenarioInitialization.java)：毛细实验的域与材料初始化。
- [IndustrialWorkbench.build](../../fabric/src/main/java/mmm/simulation/IndustrialWorkbench.java)、[MinecraftRegionActivation.preflightSelections](../../fabric/src/main/java/mmm/simulation/MinecraftRegionActivation.java)：多台装置的整体预检和生产选区创建。
- 验证入口：`runDemoInteractionValidation`、`runGpuProductionDemoValidation`、`runGpuProcessFlowsValidation`。

薄壁管道按有限管段保存液体、气体、床库存与内能，沿开放管口按压差和高差输运。区域端口在共同候选中交接物质及能量，管壁复用世界环境热场；管网、环境和区域通过同一世界 HEAD 保存。详见[薄壁管道](physical-pipes.md)。

- [PipeNetworkSolver](../../solver/src/main/java/mmm/pipe/PipeNetworkSolver.java)：管段流动、相变、床载与热量结算。
- [PipeRuntime](../../fabric/src/main/java/mmm/simulation/PipeRuntime.java)、[PipeRegionExchange](../../fabric/src/main/java/mmm/simulation/PipeRegionExchange.java)：世界管网、区域交接与生命周期。
- 验证入口：`pipe-network`、`PipeLiquidTransportValidation` 与 `PipeWallHeatValidation`。

<a id="performance"></a>
<a id="diagnosis"></a>
## 性能和故障定位

完整步计时覆盖输入、设备计算、提交和必要交接；编辑与保存分别记录回读、规划和上传成本，冷初始化单列。超出节拍预算时降频，持续严重超时暂停区域。64³／0.5 m 完整生产步采用 50 ms 预算。验证场景与判定方法见[验证指南](../maintenance/validation.md)，设备性能要求见[后端参考](../roadmap/performance-and-backends.md)。
