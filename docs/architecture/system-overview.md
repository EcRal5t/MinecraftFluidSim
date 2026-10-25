# 当前设计书

本文按一次完整职责链组织当前模型、跨模块契约和代码索引。开发时完整阅读本文与[当前实施计划](../roadmap/solver-consolidation-plan.md)，按本轮改动查阅[验证指南](../maintenance/validation.md)中的场景与预算。
具体公式、字段和操作细节从各节的参考链接继续查阅。实施状态集中在计划，运行结果集中在验证指南。

固定碰撞在每个求解单元内保留一个连通开放空间；隔墙以求解网格解析。固定几何编辑和恢复统一执行此限制，库存、压力和格面惯性使用同一空间归属。

当前运行范围为最多四个闭壳控制器或有界静水池区域，CPU `FluidDomain` 为物理聚合根。默认格长为 0.5 m，气体采用有限气腔与局部孔流，颗粒采用活动代表粒子与床层。玩法范围和后续能力的目标由[产品路线](../roadmap/product-roadmap.md)维护。

## 导航

- [模块职责](#模块职责)
- [权威数据与单位](#哪些数据决定物质库存)
- [输入与候选准备](#runtime)
- [完整计算步](#physics)
- [力学与占容](#mechanics)
- [守恒输运与气腔交接](#transport)
- [热化学与物相转换](#thermochemistry)
- [提交与恢复](#persistence)
- [查询与展示](#client)
- [游戏入口](#gameplay)
- [性能与故障定位](#performance)

## 模块职责

| 模块 | 数据或职责 | 主要依赖 |
|---|---|---|
| [api](../../api/src/main/java/mmm/api/) | 外部查询、提交通知、带单位的数量类型 | JDK |
| [model](../../model/src/main/java/mmm/) | 材料定义、库存、物种、内能、载体与存档模型 | api |
| [solver](../../solver/src/main/java/mmm/fluid/) | 几何、力学、输运、热化学及完整私有候选 | model |
| [app](../../app/src/main/java/mmm/application/) | 调度、授权、用例、区域身份与提交协调 | solver、model |
| [fabric](../../fabric/src/main/java/mmm/) | Minecraft 世界与物品操作、持久化、命令和网络 | api、model、solver、app |
| [fabric client](../../fabric/src/client/java/mmm/) | 快照、几何和界面 | 客户端协议与展示数据 |
| [gpu-kernel](../../gpu-kernel/src/main/java/mmm/gpuprobe/) / [gpu-probe](../../gpu-probe/src/main/java/mmm/gpuprobe/) | Vulkan 库存内核、可选混合压力计算、实验与 Minecraft 适配 | solver / kernel、fabric |
| [core](../../core/build.gradle) | 聚合发布 api/model/solver/app | 无生产源码 |

依赖方向为 `api ← model ← solver ← app ← fabric`，Java 根包为 `mmm`，app 对应包为 `mmm.application`。
世界读取、方块修改、物品变化与磁盘提交由服务器主线程执行；数值模块计算私有后继。[ArchitectureDependencyValidation](../../fabric/src/test/java/mmm/validation/ArchitectureDependencyValidation.java)
检查依赖方向、包循环、注册点与生产类行数。

`FluidDomain` 集中保存运行字段、公共类型和外部调用契约，包内处理器通过组合访问同一域。
阶段直接持有该域，内部调用沿对应职责处理器进行；Fabric 的 `MmmRuntimeFactory` 组装世界服务与后端。
职责入口如下：

- [DomainFlowFields](../../solver/src/main/java/mmm/fluid/DomainFlowFields.java)：库存派生网格、几何、速度与表面张力工作区。
- [DomainStep](../../solver/src/main/java/mmm/fluid/DomainStep.java)：完整力学步、独立热轴、实体输入与机械观察。
- [DomainInventory](../../solver/src/main/java/mmm/fluid/DomainInventory.java)：物质查询、注入、提取及外部库存事务。
- [DomainCollision](../../solver/src/main/java/mmm/fluid/DomainCollision.java)：固定碰撞编辑、容量修改、批量初始化与材料绑定。
- [DomainCapabilities](../../solver/src/main/java/mmm/fluid/DomainCapabilities.java)：化学和热能力绑定、实际热量所有者及相变接口。
- [DomainPhaseInventory](../../solver/src/main/java/mmm/fluid/DomainPhaseInventory.java)：颗粒与气体库存、共同物性及完整状态交接。

`CellMacStage.publishMaterialChange` 集中预检和发布液体、动量、各业务载体、相占据及气腔，统一推进库存代次与化学失效。正常输运、颗粒排水、反应、相变、物质编辑和碰撞编辑沿此入口提交。
`MechanicalEnergySettlement.prepare` 结算力积分与实际质量路线；`prepareDisplacement` 为内部转换结算排水势能与气腔反向功。外部插入保留外部做功归属。
`DomainFlowFields.preparePhysicalState` 明确准备会初始化气腔的物理状态；只读库存与速度分别使用 `ensureInventoryGrid`、`ensureVelocityGrid`。
`FluidDomain.markCellInventoryChanged` 集中推进库存代次并标记派生工作区失效。
库存汇总与面惯性在同一次重建中准备；显式速度发布先准备面惯性，再接收调用方速度。
速度查询和展示导出通过 `DomainFlowFields.ensureVelocityGrid` 准备库存与 MAC，不触发气腔初始化。
相占据由 `PhaseOccupancyTransport.ensure/publish` 集中准备与发布，库存编辑标记失效，捕获读取当前库存对应的占据；旧占据保留到气体来源交接结束。
实际库存仍由对应库存对象持有，处理器组织操作与提交顺序。跨相操作准备整笔热账，发布时将各物相增量合入当前账本；碰撞计划同时保存库存、MAC 动量与热学代次，在首次写入前核对。

排障先沿规则所有者定位，再看该次调用者：

| 现象 | 先查的规则入口 |
|---|---|
| 编辑后物性、面质量或恢复占据陈旧 | `FluidDomain.markCellInventoryChanged`、`DomainFlowFields` 的分层准备、`PhaseOccupancyTransport.ensure/publish` |
| 跨相操作丢热账或重复计功 | 对应编辑计划的整笔预检与提交增量、`ThermalDomainState` 的库存与账本 |
| 液封误通、环境气体被微膜截留 | `GasFaceIntersection` 的共享干区、`GasComplementGeometry` 的端口；节点容量仍读实际库存 |
| 静水梯度或床层压力异常 | `PressureFaceGraph.prepareWetFaces/prepareHydrostaticReference/barotropicAcceleration` 的接液载荷与支撑参考，`GranularFaceVolumes` 的双单元孔隙率 |
| 尾液动量增长或输运耗散异常 | `CellMomentumTransport`、`MacMomentumAdvection` 的实际质量通量与局部控制体合并 |
| 沸腾、凝结推进停滞 | `GasLiquidPhaseChangeStage` 的气腔压力、温度与容量联合响应 |

## 哪些数据决定物质库存

| 领域 | 保存的权威数据 | 派生量 |
|---|---|---|
| 液体 | 单元／材料／开放分量的体积、整数质量、组成、内能；微盒相占据；MAC 格面 SI 动量 | 速度、密度、PLIC、压力、曲率与显示点样本 |
| 化学 | 物种数量、组成、电荷、质量余量与收支 | pH、颜色、主要组分 |
| 颗粒 | active/deposit 的 SVQ、质量、材料、组成、孔隙率、位置及接触状态 | 占容、床高、混合有效孔隙率与样本 |
| 连续固相 | coherent group 的 SVQ、质量、组成与内能 | 固相显示比例 |
| 气体 | 各组分标准体积 nanos、质量、物种、节点内能与收支 | 温度、压力、密度、孔流和临时压力自由度 |
| 热学 | 各实际载体总内能及 source/sink/work/roundoff | 温度、热容、LTE 与热浮力 |
| 有限热设备 | region 中的设备身份、质量、内能与代次 | 温度、接触位置与展示配置 |
| 世界与物品 | 区域 HEAD、WAL、已存盘物品状态 | 控制台和诊断 |

同一操作涉及多种物相时，物质、组成、动量和携带内能按同一计划交接。
化学绑定与恢复按实际库存检查材料物相、允许物种；空 palette 可保留尚未使用的材料。公开组成赋值要求物种质量不超过载体质量，溶液初始化按实际总质量分配溶质与溶剂。
液体由 `LiquidCellInventory.Stock` 保存物质记录，`PhaseOccupancyState` 保存空间占据，MAC 动量独立保存；颗粒、沉积、连续固相和气腔由各自库存模块持有。
派生索引、PLIC、温度和展示网格读取同代库存，提交前统一核对来源与代次。

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

气体状态方程和相平衡使用绝对压力。液体投影的机械压力采用相对于环境的表压；查询时按接口声明换算。
气腔实际容量以 m³ 表示，标准体积由库存按标准态定义保存，两者分别参与状态方程。
仿真步长和累计仿真时间用 s；Minecraft tick 是服务器调度次数，正常节拍为 20 tick/s。
计算耗时用实际时钟 ns 或 ms 衡量，pacing 和重试保持请求推进的仿真时长。

油采用材料质量与化学质量余量，物种列为空。汞采用真实 Hg，玩法摩尔质量为 0.200 kg/mol，密度为 13600 kg/m³。
水、常见离子和 CO₂ 沿用整数原子量精度；水为 0.018 kg/mol，默认浓度为 500000/9 mol/m³。
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
- 验证入口：`M7ChemistryValidation`、`M7ChemicalTransportValidation`、`thermal`、`region-state`、`containers`、`protocol`。

库存、物种、反应批次、单位换算和提交审计保留必要的整数与精确数量运算。几何和力学采用浮点标准离散；未解析薄层、液滴和细尺度运动按单元尺度近似。表示转换耗散明确进入对应能量结算，舍入与离散误差分别评价。精度取舍和验收要求见[实施计划](../roadmap/solver-consolidation-plan.md#精度与物理取舍)。

<a id="runtime"></a>
## 物理活动组

[PhysicalActivityGroups](../../solver/src/main/java/mmm/fluid/PhysicalActivityGroups.java) 按固定开放通路、有限气腔、颗粒支撑、实体与热接触组织同域活动组。力学和热化学分别休眠，局部输入唤醒相关组；共享设备的端口单元随 tick 输入建立关联。压力、黏性、体力、输运与热化学阶段共用组的活动状态，休眠库存与 MAC 原量保留。通气口按整数平衡迁移量识别持续压差驱动，相关有限气腔组保持运行。所有组仍沿完整区域候选和一个 HEAD 提交。

世界输入由 [MinecraftGroupAvailability](../../fabric/src/main/java/mmm/simulation/MinecraftGroupAvailability.java) 的 `prepare` 按求解单元及其邻接方块采样已加载区块，`PhysicalActivityGroups.prepareAvailability` 将缺失条件传播到物理关联的整个组。受影响组保留库存、动量与内能，其他独立组继续；全部组等待时区域保留完整内存求解域。区块恢复时请求刷新碰撞并唤醒相关组。冷恢复分批读取同代完整区域磁盘库存，全部到齐后建立完整 CPU `FluidDomain`，世界缺区块按组暂停。

`FluidDomainStateMapper` 保存进程内候选调度状态与世界单元可用性，冷恢复重新观察物理状态；`RegionDiagnosticsReport` 只读展示组数及活动／休眠单元。验证入口为 `activity-groups`，稳定契约见[运行时与生命周期](runtime-and-lifecycle.md#物理活动组)。

## 从游戏操作到计算结果

```text
世界 / 物品 / 网络请求
  → Fabric 读取输入与解析身份
  → app 检查请求并捕获完整计算状态
  → solver 计算私有候选
  → app 与区域仓库核对身份、代次、布局
  → Fabric 接管后继或提交世界 / 物品事务
  → 发布查询、快照与适用的提交通知
```

启动时先发布或恢复世界能力包，绑定该世界的材料定义和功能布局，再恢复区域与未完成事务。已提交包解释已有库存，数据包漂移通过诊断报告。定义、格式或身份错误保留原文件并锁定相应持久域。

服务器 tick 依次刷新碰撞、热交换与实体输入，调度器选择 `NONE`、`MOTION` 或 `THERMAL`。力学优先于独立热轴；暂停、pacing 跳过和热轴均保留运动输入的一次使用规则。静水可继续导热、反应或相变；热量变化又可唤醒力学。玩家运行意图与外壳破损等区域可用性原因分别保存；世界区块卸载按物理活动组暂停。

- **启动与依赖组装**
  - 主要文件：
    - [MmmRuntimeFactory](../../fabric/src/main/java/mmm/simulation/MmmRuntimeFactory.java)
    - [WorldStartupCoordinator](../../fabric/src/main/java/mmm/simulation/WorldStartupCoordinator.java)
  - 验证入口 / 参考：`region-state,lifecycle`；[运行时参考](runtime-and-lifecycle.md#startup)

- **tick、调度、实体和碰撞输入**
  - 主要文件：
    - [MinecraftSimulationLoop](../../fabric/src/main/java/mmm/simulation/MinecraftSimulationLoop.java)
    - [MinecraftGroupAvailability](../../fabric/src/main/java/mmm/simulation/MinecraftGroupAvailability.java)：`prepare` 组织世界区块可用性，`loadedShellFailure` 检查已加载池壁与外壳，`boxAvailable` 筛选实体输入。
    - [MinecraftCollisionWorldPort](../../fabric/src/main/java/mmm/simulation/MinecraftCollisionWorldPort.java)：`capture` 保留暂停组的固定碰撞，恢复后重新采样世界。
    - [MinecraftRegionInputs](../../fabric/src/main/java/mmm/simulation/MinecraftRegionInputs.java)：捕获机械及燃烧实体的已提交湿接触，`commitEntityResponse` 在成功提交后施加冲量与水接触灭火。
    - [RegionTickUseCase](../../app/src/main/java/mmm/application/RegionTickUseCase.java)
  - 几何入口：[MovingBodyWetGeometry](../../solver/src/main/java/mmm/fluid/MovingBodyWetGeometry.java) 的 `capture` 共用物理材料层，返回独立水接触体积；[MovingBodyCouplingValidation](../../fabric/src/test/java/mmm/fluid/MovingBodyCouplingValidation.java) 覆盖干身、水和水油交界。
  - 红石观察：[ControllerComparatorSignals](../../fabric/src/main/java/mmm/simulation/ControllerComparatorSignals.java) 按已提交液体／固定开放容量缓存控制器比较器值，由区域采用与生命周期入口更新；[FluidControllerBlock](../../fabric/src/main/java/mmm/block/FluidControllerBlock.java) 只读缓存。
  - 验证入口 / 参考：`lifecycle,entity-fluid,shape`；[输入参考](runtime-and-lifecycle.md#world-inputs)

- **容量与库存编辑**
  - `setVolume` 与整批 `setVolumes` 共用单元目标替换，按真实开放容量裁剪，并在一份候选中结算整数质量、物种、内能、MAC 动量和气腔后继。
    初始化一次收集全部目标格后提交；材料注册直接绑定现有库存与动量。
    候选格枚举读取库存派生量与固定碰撞；容量及热量预检保持来源气体状态，接受后的计划统一准备动量与气腔交接。
  - 主要文件：
    - [DomainCollision](../../solver/src/main/java/mmm/fluid/DomainCollision.java)：`setVolumes`、`bindMaterialRegistry`
    - [DomainInventory](../../solver/src/main/java/mmm/fluid/DomainInventory.java)
    - [MaterialTransferPlanner](../../solver/src/main/java/mmm/fluid/MaterialTransferPlanner.java)：`replaceCellVolumes`
    - [FluidRegionFactory](../../fabric/src/main/java/mmm/simulation/FluidRegionFactory.java)：原版水采集与材料绑定
    - [MinecraftDebugFixtures](../../fabric/src/main/java/mmm/simulation/MinecraftDebugFixtures.java)：整批演示初始化
  - 验证入口 / 参考：`LiquidCellInventoryValidation`、`LiquidThermalAdvectionValidation`、`materials,containers,granular`；[材料参考](material-state.md)

<a id="physics"></a>
## 一个完整计算步

[DomainStep](../../solver/src/main/java/mmm/fluid/DomainStep.java) 组织下列职责链：

1. 捕获库存、内能收支和待机械推进的相变格集合；从碰撞、固体占容与气腔建立同代输入。
2. 从单元整数质量构造 MAC 双控制体积惯性，以格面动量派生速度，执行多孔阻力、局部应变耗散和重力、浮力、张力等作用。
3. 以液体面质量、颗粒质量和两相体积散度共同求解压力与有限气腔响应，同时修正两相速度，再准备颗粒碰撞位置与实际整数占容。
4. 以共享通量在私有库存中执行一次完整时长的粗单元输运，用同一次实际整数质量通量推进 MAC 动量，联合提交液体、颗粒和气腔后继。
5. 在新位置上完成导热、液相反应、颗粒反应、固液与气液相变，最后处理气液物种传质。相变共用操作预算，体积变化留待后续真实力学同步。
6. 执行颗粒沉积、起动和床层松弛，随物质搬运组成与内能。
7. 核对全部收支，成功后交接完整候选、发布统计，并更新运动与热活动状态。

力学阶段在 [MechanicalStepPipeline](../../solver/src/main/java/mmm/fluid/MechanicalStepPipeline.java)，热化学阶段在 [ThermochemistryPipeline](../../solver/src/main/java/mmm/fluid/ThermochemistryPipeline.java)，
颗粒形态转换由 `GranularTransitionStage` 组织。
管线和各阶段直接访问 `FluidDomain` 及其职责处理器，导热由 `ThermochemistryPipeline.stepTransport` 调用实际载体热交换。
每个机械子步执行一次完整时长的粗单元液体推进；供体库存和接收容量限定实际流量，未解析快流通过限流与混合耗散处理。
机械子步数按显式最小要求设置，颗粒轨迹细分保持完整逻辑步的总推进时长和输入的一次使用规则。
导热按可解析温差准备接触图；载体与热库的温差均低于模型分辨率时保留原始内能，温度误差受该温差尺度限制。反应与相变继续读取实际组成和内能。
查询、几何、反应与恢复共用对应库存模块的数量和单位规则。

<a id="mechanics"></a>
## 力学与占容

纯液体与含固相区域共用 MAC 压力、局部应变耗散和有限体积输运。液体逐材料质量和体积决定惯性与组成密度；不可互溶相共享宏观速度和机械压力，保留材料身份与界面张力。热浮力按正的体膨胀关系推导密度修正，黏度读取世界材料定义的 `mu(T)`。

固定碰撞由 Minecraft `VoxelShape` 保守采样成每格 `2×2×2` 微盒掩码，压力开口、液体通量和 CCD 读取同一几何。固定掩码、孔容、面开口与墙面积由不可变快照共同持有，状态恢复和气体帧共享该快照，真实改壳时复制并生成新快照。活动颗粒、沉积和 coherent 固相共享固体骨架占容；液体可用容量为开放体积减去全部固体占容，孔隙水由普通液体库存持有。

压力系统共用同一体积散度及其转置，已知压力、迭代修正和最终速度采用同一个有限质量与隐式阻力响应。
`PressureLinearSolver` 与 `PressurePreconditioner` 统一 PCG 及不完全 Cholesky 预条件，颗粒阻力按有限质量成对处理。
`PressureSparseMatrix` 组装无阻力响应下的共同 CSR，提供预条件；实际含阻力算子通过 `GranularImplicitDrag` 已有稀疏分解应用速度响应，复用三角回代，真空活动集沿当前自由行求解。纯液体自然采用无阻力响应。
压力支撑分类直接读取同代气体几何已准备的分量孔容与实际干容，精确容量由共同库存准备一次。
真实真空腔与有库存气腔均保留自己的压力接触。静水参考只提供已知载荷，压力自由度由实际容量与气腔归属决定。
静水承载使用实际液体接触及统一的未解析薄干缝近似，接地传播与上覆压力使用同一判定。可解析真空继续保留其压力与运动响应。
已解析液气界面采用给定压力或有限气腔储存项，状态方程按液体压载区间给出有限体积响应，参加一次半隐式压力预测。低于几何体积解析尺度的干容保留气体库存、容量与内能，机械响应并入液体连续压力；气腔自由度按液体与颗粒算子的实际耦合准备。本步旧气源的实际机械模式随压力装配保存，没有液体面或固相压缩响应的未解析气腔按固定内能重映射空间表示。
活动颗粒体积分配到共同 MAC 面，液体读取剩余开口，两相真实有限质量与各自体积散度的转置共同构成压力算子。
压力同时更新液体与颗粒速度，接触和位置预测随后准备实际整数占容，真实终点孔容由共同有界输运结算。
`PressureSystem` 集中准备共同压力预测：两相先接收已知界面载荷及静水浮力，再执行有限质量阻力，随后求解共同残余压力。
已知气压与后续压力增量分别保存，真空下限逐气腔换算；观察字段发布两者之和。力积分按实际动能变化交接内能，输运按实际整数质量结算势能变化；颗粒按接受位置及实际速度结算机械能变化。气腔绝热功按同一机械连通体内的真实载体质量分配，液体内部压力功引起的局部温度分配采用体平均近似。液体的有符号机械热沿实际质量通量输运，颗粒热写入对应库存，均随候选一次提交。
闭壳耦合系统以主气腔压力为数值基准求解增量，液体、气体储容、颗粒反作用和真空下限统一平移，发布时恢复实际压力。均压静水保持零增量，避免相消假流及其后续容量功。
作用与反作用、外部约束冲量和压力功按实际写回结算；整数库存按最终位置独立核对真实容量，具体离散见[颗粒参考](granular-state.md#统一排水与压力)。
实体的完整 UUID／AABB／规定速度目标表替换已接受实体表，重叠实体使用联合占容；实际开放容量、面开口和规定通量参与共同压力。压力散度的转置给出逐实体冲量，规定速度与冲量结算有符号外功，剪切损失写入实际接液库存。目标表与物质候选一起提交，Fabric 在成功力学步后应用一次响应；跳过、失败和独立热轴清除待响应输入。相变机械同步保留已接受位置并使用零规定速度。
当前支持外部规定运动；有限质量实体的动态平移联立尚未实现。
`MovingBodyCouplingValidation` 直接核对实体外功与实际 MAC 动能、库存内能增量，固定墙反冲计入同一冲量等式。

液体持久动量位于 MAC 格面，格面速度从同一双控制体积的实际质量与动量派生。
`CellMomentumTransport` 从已接受的整数质量通量直接生成相邻格面的输运，`MacMomentumAdvection` 对实际承载质量的双控制体积采用正权迎风混合。
新湿面继承供体速度，局部混合损失转入内能，随质量搬运后与最终库存一次提交。
固定开口与联合容量约束液体运动；颗粒代表球通过共同接触响应处理法向、恢复和摩擦。

- **库存、PLIC 与速度工作区失效**
  - 主要文件：
    - [FixedGeometrySnapshot](../../solver/src/main/java/mmm/fluid/FixedGeometrySnapshot.java)：固定掩码、孔容、面开口、墙面积及按网格尺寸生成的只读面索引，供状态、气体帧与候选共享。
    - [FlipCutCellGeometry](../../solver/src/main/java/mmm/fluid/FlipCutCellGeometry.java)：`snapshot` / `restoreSnapshot` 交接固定几何，`replaceMask` 在真实编辑时隔离写入。
    - [CellMacStage](../../solver/src/main/java/mmm/fluid/CellMacStage.java)：按物质数量准备惯性、墙面约束和持久动量，内能更新复用原数量
    - [DomainFlowFields](../../solver/src/main/java/mmm/fluid/DomainFlowFields.java)
    - [CellLiquidGrid](../../solver/src/main/java/mmm/fluid/CellLiquidGrid.java)：单元库存派生索引与物性
  - 验证选择器 / 参考：`free-surface,materials`；[流体离散](fluid-solver.md#单元库存与-mac-动量)

- **压力与气腔耦合**
  - 主要文件：
    - [PressureSystem](../../solver/src/main/java/mmm/fluid/PressureSystem.java)：`prepareGeometry` 集中准备相几何与固相压力份额；`prepareOperator` 准备未知行和载荷，`solve` 组织共同基准、矩阵及速度修正，并发布实际压力
    - [PressureLinearSolver](../../solver/src/main/java/mmm/fluid/PressureLinearSolver.java)：求解显式传入的未知行与约束，返回双精度压力，由 `PressureSystem` 发布
    - [PressureSparseMatrix](../../solver/src/main/java/mmm/fluid/PressureSparseMatrix.java)：无阻力响应的共同 CSR，用于压力预条件及可选设备矩阵乘法
    - [SparseMatrixCompute](../../solver/src/main/java/mmm/fluid/SparseMatrixCompute.java)、[ComputeExecution](../../solver/src/main/java/mmm/fluid/ComputeExecution.java)：可选 CSR 计算服务与候选内的调用作用域
    - [PressurePreconditioner](../../solver/src/main/java/mmm/fluid/PressurePreconditioner.java)
    - [PressureFaceGraph](../../solver/src/main/java/mmm/fluid/PressureFaceGraph.java)：每格一个固定开放连通域对应的压力工作区、接液面积与散度系数；气压在每次装配读取实际状态，液体输运直接使用固定孔口邻接
    - [PressureBoundaryView](../../solver/src/main/java/mmm/fluid/PressureBoundaryView.java)
  - 验证选择器 / 参考：`marker-pressure,gas-liquid`；[压力离散](fluid-solver.md#自由面与压力)

- **实体占容与提交响应**
  - 主要文件：
    - [EntityBodyState](../../solver/src/main/java/mmm/fluid/EntityBodyState.java)：规范完整目标表，随状态交接与存档恢复。
    - [EntityBodyCoupling](../../solver/src/main/java/mmm/fluid/EntityBodyCoupling.java)：`replace` 更新联合占容与规定通量，`applyShear`、`finishPressure` 结算剪切、逐实体冲量和外功。
    - [CpuFluidSolverBackend](../../solver/src/main/java/mmm/fluid/CpuFluidSolverBackend.java)：在私有候选中替换目标表并发布成功结果。
    - [MinecraftRegionInputs](../../fabric/src/main/java/mmm/simulation/MinecraftRegionInputs.java)、[MinecraftRegionTick](../../fabric/src/main/java/mmm/simulation/MinecraftRegionTick.java)：捕获完整输入、成功后一次提交原生实体响应，完整步计时包含设备交换。
  - 验证入口：`MovingBodyCouplingValidation`、`FluidDomainStateParityValidation`、`EntityFluidWiringValidation`。

- **黏性与材料物性**
  - 主要文件：
    - [LiquidViscosityStage](../../solver/src/main/java/mmm/fluid/LiquidViscosityStage.java)
    - [ViscousMacDiscretization](../../solver/src/main/java/mmm/fluid/ViscousMacDiscretization.java)
    - [LiquidCarrierProperties](../../solver/src/main/java/mmm/fluid/LiquidCarrierProperties.java)
  - 验证选择器 / 参考：`cut-cell,thermal`；[材料物性](material-state.md)

- **持久 MAC 动量与质量一致输运**
  - 主要文件：
    - [MacVelocityGrid](../../solver/src/main/java/mmm/fluid/MacVelocityGrid.java)
    - [MacMomentumState](../../model/src/main/java/mmm/fluid/MacMomentumState.java)
    - [CellMomentumTransport](../../solver/src/main/java/mmm/fluid/CellMomentumTransport.java)
    - [MacMomentumAdvection](../../solver/src/main/java/mmm/fluid/MacMomentumAdvection.java)
    - [MechanicalEnergySettlement](../../solver/src/main/java/mmm/fluid/MechanicalEnergySettlement.java)：`prepare` 一次归集合并同一机械体的实际 K/PE 与气腔功，准备液体和颗粒热量；`Plan` 统一预检和提交
  - 验证入口 / 参考：`MacMomentumAdvectionValidation`、`free-surface`；[库存与动量](fluid-solver.md#单元库存与-mac-动量)

- **液固压力、阻力与整数占容**
  - 主要文件：
    - [GranularPressureCoupling](../../solver/src/main/java/mmm/fluid/GranularPressureCoupling.java)
    - [GranularImplicitDrag](../../solver/src/main/java/mmm/fluid/GranularImplicitDrag.java)：有限质量阻力分解与共同压力速度响应
    - [GranularFaceVolumes](../../solver/src/main/java/mmm/fluid/GranularFaceVolumes.java)
    - [GranularCouplingFields](../../model/src/main/java/mmm/granular/GranularCouplingFields.java)
  - 验证选择器 / 参考：`granular`；[压力与占容](granular-state.md#统一排水与压力)

- **碰撞与颗粒接触**
  - 主要文件：
    - [LiquidCollisionEdit](../../solver/src/main/java/mmm/fluid/LiquidCollisionEdit.java)
    - [GranularActiveContacts](../../solver/src/main/java/mmm/fluid/GranularActiveContacts.java)
    - [GranularComponentResponse](../../solver/src/main/java/mmm/fluid/GranularComponentResponse.java)
    - [GranularContactCompression](../../solver/src/main/java/mmm/fluid/GranularContactCompression.java)
    - [GranularSpawnAdmission](../../solver/src/main/java/mmm/fluid/GranularSpawnAdmission.java)
  - 验证选择器 / 参考：`cut-cell,shape,granular`；[碰撞参考](fluid-solver.md#固体与碰撞)

- **曲率、界面张力与润湿**
  - 主要文件：
    - [SurfaceTensionFields](../../solver/src/main/java/mmm/fluid/SurfaceTensionFields.java)：共同曲率、接触角与全部界面字段；接触角沿固定墙法向通量施加一次
    - [HeightFunctionCurvature](../../solver/src/main/java/mmm/fluid/HeightFunctionCurvature.java)
    - [BodyForceStage](../../solver/src/main/java/mmm/fluid/BodyForceStage.java)：液液物相对的显式 CSF。
  - 验证选择器 / 参考：`surface-tension`；[界面离散](fluid-solver.md#plic表面张力与润湿)

<a id="transport"></a>
## 守恒输运与气腔交接

液体输运以粗单元的固定开放连通分量为库存和容量单位。
`PressureSystem.volumeFlux` 提供共同 MAC 速度与实际面开口决定的总通量 `Qt`，颗粒本步实际整数占容决定终点孔容。
`ConservativeLiquidFlux` 从八微盒 PLIC 几何取得液相扫掠偏好，汇总为分量之间的有向面通量。
`PhaseQuantitySettlement` 按固定开放分量结算实际面出流，终点体积为原库存加实际入流、减实际出流。供体出流受原库存限制，接收分量满足孔容与有限气体正容量；满液支撑先沿物理邻接树一次校正散度，剩余库存、孔容或气腔留容约束再沿残量图按需调整。满液分量新增干容由同代上风气体通量提供，已有自由界面与气体膨胀保持可动；相变减液形成的新气容随库存交接。
库存按实际出流体积分配整数质量，再按接受的质量份额分配物种，未流出部分留在原库存；热参考能读取实际物种列，显热沿同一质量份额分配。所有原供体扣除出流后，再合并接收量并生成组成对象，循环流动共用同一原始库存代次。气体来源与终点干容共用同次准备结果。
容量残量图中的原地余量提供可调出流额度，交给库存的路线只保存实际跨格转移。MAC 动量直接读取源速度和已接受的整数质量流，双单元质量准备一次，混合与转热共用非零质量流列表。

未来库存先结算真实容量，再一次生成后继相占据与气体几何；空格和满格共享均匀描述，部分填充分量采用 PLIC。库存只改变组成或内能、实际空间保持一致时，共享已有相几何、拓扑与容量。两类相变在同一数量候选上完成动量准备及最终内能赋值。
压力和 PLIC 给出面通量偏好，正常运动与颗粒排水共用双向邻接路线和一次容量投影。
材料共享已经接受的分量出流路线，分别分配实际流出的体积、整数质量、物种与内能；少量材料沿同一分量流动。
气相互补通量按已接受的液相 `QL` 计算双向 `Qt−QL`，覆盖容量修正引起的反向气流和原总通量为零的面。整数质量、动量和气体来源使用同次结算结果。
每个非空有限气腔在其真实支撑内保留正容量；容量约束与物质来源分别准备，气体来源沿实际干空间变化交接。
气体膨胀由实际液流推进，终态状态方程与热功读取真实容量。
每个机械子步执行一次完整时长的有限体积推进，按实际份额分配材料整数质量、物种与内能，活动域发布一次。
无出流库存沿用原行，整份单面跨格转移直接携带原质量、组成和内能；部分出流的质量余量留在原库存，全出空时由最大出流份额承接质量余量；物种和内能余量由最大实际质量份额承接。
实际出流按原来源分组进入库存，物种共用已经接受的整数质量份额；来源先保留数量余量，目标再按单元聚合并一次合并有序行。
MAC 动量输运读取同次结算的实际整数质量通量。

每个粗单元固定开放分量使用一套粗梯度 PLIC 平面，按最终库存液量确定偏移；持久微盒列保存共同重建输入，积分形状由 PLIC 工作区派生。
未解析液片与气隙共用单元尺度的形状近似：微量液体保留库存与携带能量，周围气体端口按单元近似保持通行；薄干隙传递粗尺度静水支撑。实际孔隙体积完整保留，已解析液塞与固定墙继续阻断气体。
气体来源按固定开放分量汇总，随实际宏观互补通量和本地保留空间推进，最终按真实分量容量交接。完整固定开口参与 MAC 来源输运，受限孔口的库存与焓由孔流网络结算。
新开放空腔使用显式真空来源。
同一粗单元固定开放分量内的干片合并气腔归属，跨完整固定开口的可解析粗分量气体共压，面内未解析液膜允许合并。
完整液塞和固定墙阻断气体，有限固定孔口保留孔流。

气腔以干微盒连通和规范 minimum-piece key 标识。`GasRuntimeGeometry` 保存同代几何、拓扑与库存容量；
未来编辑按最终相占据、单元库存和固体占容准备后继。
固定几何每格最多一个开放连通域。容量、气体来源和压力工作区统一按单元索引；八微盒索引保留实际几何、PLIC 与孔口积分。`GasComponentCapacity` 一次准备实际 `pore−Stock`，气片存在性、拓扑、来源交接和热力学容量共用该结果。未变库存行与孔容直接共享分量容量。
`GasComplementGeometry.capture` 顺序扫描微盒与面，复用未变化单元和相邻面的几何，生成不可变后继；同代几何复用拓扑和气腔容量。
气体库存、内能和旧控制体积沿同一份最终来源一次分配。
持久化保留相占据与微盒归属；环境交换记 source/sink，新开放真空空间保持明确归属。
真实机械模式或外部显式置换改变容量时，对每个旧气源汇总实际体积形变，沿状态方程做一次绝热积分，再按同一库存份额分配内能；新气腔合并直接相加。接收腔的实际容量与继承控制容量共同决定旧气源形变，原有真空与新增孔容一并参加。没有机械模式的未解析气腔空间重映射、表示交接和相变内部交换按固定内能结算，状态方程始终读取实际库存、容量与内能。
气体来源与绝热控制体积分别交接：旧腔库存、内能及旧容量使用同一来源权重；局部液体移动形成的真空标签携带零库存。真正新增的零能量空间由实际净新增孔容给出。

气体采用集总气腔与有限孔流模型，N₂、O₂、水蒸气与 CO₂ 共用实际容量和总内能，按组分库存派生共同温度、总压、分压及顺应性。环境空气由一处摩尔配方生成，普通推进与恢复保持已有组成。
完整固定开口两侧的实际粗分量干容量超过共同解析量时采用低 Mach 共压近似，微量气体空间沿真实干口连接。
受限固定孔口保留有限流量，完整满液单元和固定墙保持阻断。
热状态孔流按规范孔口顺序正向、逆向各推进半步。每孔冻结正导通率，使用两腔连续库存与等熵排气焓求局部隐式响应，按实际库存分辨率结束求根，再一次分配整数物质与同一能量通量；下一孔读取已更新的状态。
这是时间一阶的集总近似，气腔内部声波和空间动量受该模型限制。

- **共享通量、供体与接收容量**
  - 主要文件：
    - [CellMacStage](../../solver/src/main/java/mmm/fluid/CellMacStage.java)：准备共同通量、动量与混合热并联合提交；同代 `rebuildWallSupport` 与 `constrainUnresolvedWallMomentum` 集中处理贴墙未解析法向支撑及能量交接
    - [ConservativeLiquidFlux](../../solver/src/main/java/mmm/fluid/ConservativeLiquidFlux.java)：几何液相偏好、粗分量通量与单次完整库存推进
    - [PhaseOccupancyState](../../model/src/main/java/mmm/inventory/PhaseOccupancyState.java)：不可变微盒液相体积
    - [PhaseOccupancyTransport](../../solver/src/main/java/mmm/fluid/PhaseOccupancyTransport.java)：局部八微盒形状归一与外部编辑的气体来源准备
    - [PhaseSourceColumns](../../solver/src/main/java/mmm/fluid/PhaseSourceColumns.java)：复用粗分量气体来源与面份额的连续稀疏列
    - [GranularLiquidDisplacement](../../solver/src/main/java/mmm/fluid/GranularLiquidDisplacement.java)
  - 验证选择器 / 参考：`free-surface`；`LiquidVolumeTransportValidation components` / `chemical-tails`；[有限体积输运](fluid-solver.md#几何与单元输运)

- **材料、物种、内能与动量交接**
  - 主要文件：
    - [LiquidCellInventory](../../model/src/main/java/mmm/inventory/LiquidCellInventory.java)：`planRoutes` 一次分配实际出流的整数质量、物种与内能，原来源扣除出流后再合并接收量；动量与来源热写入同批机械热列，统一生成私有后继与能量舍入账；`Plan.withInternalEnergiesByStock` 在同一数量候选上赋值最终内能。
    - [LiquidTransportRoutes](../../model/src/main/java/mmm/inventory/LiquidTransportRoutes.java)：按原供体分组的实际跨格出流列，保存面号、目标支撑与正体积，材料身份来自原库存。
    - [PhaseQuantitySettlement](../../solver/src/main/java/mmm/fluid/PhaseQuantitySettlement.java)：以原库存加减实际面流结算分量容量，先做满液散度树校正，再按需处理未解容量残量，分配实际出流的材料份额。
    - [FluidThermalBinding](../../solver/src/main/java/mmm/fluid/FluidThermalBinding.java)：`liquidEnergyPartitioner` 按实际参考能与来源显热分份
    - [CellMomentumTransport](../../solver/src/main/java/mmm/fluid/CellMomentumTransport.java)：三轴共用 `MacMomentumAdvection` 工作区，从实际质量通量生成双控制体积输运，按承载质量的格面结算动量与局部混合热，每轴保存独立的最终动量。
    - [CellCarrierInventoryView](../../solver/src/main/java/mmm/fluid/CellCarrierInventoryView.java)
  - 验证入口 / 参考：`LiquidVolumeTransportValidation`、`free-surface,thermal`；[单元输运](fluid-solver.md#几何与单元输运)

- **浮点几何与气腔容量**
  - 主要文件：
    - [FlipPressurePhaseGeometry](../../solver/src/main/java/mmm/fluid/FlipPressurePhaseGeometry.java)
    - [PlicPlaneOffset](../../solver/src/main/java/mmm/fluid/PlicPlaneOffset.java)
    - [GasComplementGeometry](../../solver/src/main/java/mmm/fluid/GasComplementGeometry.java)：`capture` 线性捕获与未变几何复用
    - [GasChamberCapacity](../../solver/src/main/java/mmm/fluid/GasChamberCapacity.java)：按固定开放分量直接汇总库存派生的气容量
    - [GasComponentCapacity](../../solver/src/main/java/mmm/fluid/GasComponentCapacity.java)
    - [GasPartitionWeights](../../model/src/main/java/mmm/fluid/air/GasPartitionWeights.java)
  - 验证选择器 / 参考：`air-cavity`；[气体几何](gas-state.md#分量气体几何)

- **气体拓扑、库存与体积功**
  - 主要文件：
    - [GasRuntimeGeometry](../../solver/src/main/java/mmm/fluid/GasRuntimeGeometry.java)：固定开放分量的干区几何、节点归属和实际容量；存盘时展开微盒节点索引
    - [GasChamberTopology](../../solver/src/main/java/mmm/fluid/GasChamberTopology.java)：真实干区连通与冷恢复归属校验
    - [GasVolumeAllocation](../../solver/src/main/java/mmm/fluid/GasVolumeAllocation.java)：完整未来帧及一次归并的旧节点到新节点稀疏来源，库存、内能、控制体积和做功共用相同目标顺序
    - [GasTopologyCoordinator](../../solver/src/main/java/mmm/fluid/GasTopologyCoordinator.java)：`planAfterMechanicalTransport` 按旧来源实际机械模式准备库存、内能、控制体积与气腔功；`planAfterLiquidDisplacement` 处理显式置换，反向功由共同机械能计划结算，统一预检后提交。
    - [BuiltinGasDefinitions](../../model/src/main/java/mmm/fluid/air/BuiltinGasDefinitions.java)、[GasRegistry](../../model/src/main/java/mmm/fluid/air/GasRegistry.java)：组分物性、环境配方及指定温压的库存生成
    - [AirNetwork](../../model/src/main/java/mmm/fluid/air/AirNetwork.java)
    - [GasOrificeFlow](../../model/src/main/java/mmm/fluid/air/GasOrificeFlow.java)
    - [GasEnergyStore](../../model/src/main/java/mmm/thermal/GasEnergyStore.java)：`planFlow` 按实际供体余量计算焓流，与 `AirNetwork` 共同准备并提交参与端口的库存和内能。
    - [GasThermalModel](../../model/src/main/java/mmm/thermal/GasThermalModel.java)
  - 验证选择器 / 参考：`air-cavity,air-network,gas-liquid,thermal`；[气体参考](gas-state.md)

- **库存提交与来源校验**
  - 主要文件：
    - [LiquidCellInventory](../../model/src/main/java/mmm/inventory/LiquidCellInventory.java)
  - 验证入口 / 参考：`materials,containers`；[交接不变量](fluid-solver.md#物质交接不变量)

<a id="thermochemistry"></a>
## 热化学与物相转换

有限换热器的 [FiniteHeatExchangerOperations](../../fabric/src/main/java/mmm/simulation/FiniteHeatExchangerOperations.java) 组织安装、查询、拆卸与成功步端口换热；[FiniteDeviceJournalStore](../../fabric/src/main/java/mmm/simulation/persistence/FiniteDeviceJournalStore.java) 保存世界／物品双成员 WAL，[FiniteThermalDeviceItemCodec](../../model/src/main/java/mmm/thermal/FiniteThermalDeviceItemCodec.java) 携带完整设备热库存。[FiniteHeatExchangerBlockEntity](../../fabric/src/main/java/mmm/block/FiniteHeatExchangerBlockEntity.java) 保存槽位定位，`FluidDomain.finiteThermalPortContacts` 和 `FluidRegion.commitFiniteThermalPortExchange` 联合提交实际载体与设备热量。具体契约见[热设备](thermal-state.md#有限热容量设备)及[安装恢复](world-ownership-and-containers.md#有限换热器安装与拆卸)。

气腔通过 `ThermalGasCarriers` 按实际干容生成每个空间支撑唯一的混合热载体，壁面、液面和有限热设备使用共同热交换视图，接收热量归并到唯一气腔内能。

各实际载体保存总内能 `U`，温度与热容由现有分段状态函数反演。LTE 工作区用于接触、反应与相变候选；只读观察、属性计算与真实能量重分配有各自入口。导热成对搬运内能，恒温热浴的实际通量记外部 source/sink。反应按精确物种批次改变组成和参考能，相变按实际批次联合修改两相库存与内能。
温度、接触与载体能量准备按稳定的单元顺序遍历，同代热视图复用已准备的载体列表；库存增量索引提前筛去缺少反应物的区域。
导热接触与求解共享一次实际载体采集，终态与热账在首次写入前准备完成。
固定库存的热曲线在一次热分配或孔流求根内复用；质量或材料变化后重新准备。相变候选复用同代 LTE 的单元载体索引与参考能，固相能量目标在准备阶段匹配一次，提交直接写入已准备目标；每格存储余差在整批热账汇总一次。凝聚相变先按分段平衡曲线在非负压力上的完整温度范围筛选可能方向，候选再采样一次实际压力；恒定平衡温度直接比较温度。气腔相变共用一次库存、容量与内能派生的压力、温度和热容。
导热后的实际内能、温度与热容直接供同代相变扫描使用；反应改变库存或内能后按热状态代次重新准备。载体和热壁之间可解析的温差进入共同导热计算，低于导热分辨率时保留原始内能并省去接触图准备。
载体内能按共同温度一次分配，最大热容载体承接存储余量，实际写入的内能汇总与舍入账保持一致。热浮力读取实际库存温度，温度准备与可选黏性定义分别处理。

热化学顺序为导热 → 液相反应 → 颗粒反应 → 固液相变 → 气液相变 → 气液物种传质，之后执行颗粒形态转换。凝固、熔化、汽化和凝结在固定区域体积下联合守恒两相内能，`PendingPhaseMotionWork` 保存几何同步任务；下一次真实投影完成后清除。物品转移遇到待同步几何时，先执行仅含力学的私有候选，再准备物品事务。
`ThermochemistryPipeline` 集中调用上述阶段，各阶段直接读取域的库存、能力和提交接口。
蒸发产生的气体库存与内能按来源单元和固定孔隙分量进入实际新生干容；旧气体按原来源迁移。凝结联合分配步初库存与真实气容，同批蒸发产生的气体在下一步参与凝结。液封隔开的新旧气腔分别结算。

颗粒运动、初始化、提取、沉积和起动读取共同整数占容。活动颗粒与床层转换同时规划来源、接收位置、材料组成、内能、势能变化和床体反力；起动提升功由实际液流提供。表示转换主动耗散与既有黏性、阻力等物理耗散分别结算；各项实现范围详见热学、颗粒参考，交付要求由实施计划维护。
外部连续固体插入同样先准备本地占容、液体位移、MAC 动量、气腔和热量来源，再共同发布；捕获与恢复读取此次提交后的气腔归属。

- **内能、状态函数与导热**
  - 主要文件：
    - [ThermalDomainState](../../model/src/main/java/mmm/thermal/ThermalDomainState.java)
    - [ThermalCaloricInversion](../../model/src/main/java/mmm/thermal/ThermalCaloricInversion.java)
    - [ThermalCellEnergyAssignment](../../model/src/main/java/mmm/thermal/ThermalCellEnergyAssignment.java)：一次准备实际载体内能及精确汇总
    - [ThermalCellOperations](../../model/src/main/java/mmm/thermal/ThermalCellOperations.java)：`preparePhaseCandidateCells` 复用单元载体索引，`preparePhaseSolidEnergies` 准备固相写入目标，`commitPhaseBatch` 提交整批热账
    - [ThermalGasCarriers](../../model/src/main/java/mmm/thermal/ThermalGasCarriers.java)：实际气体干容接触样本与气腔内能归并
    - [CarrierThermalConduction](../../solver/src/main/java/mmm/fluid/CarrierThermalConduction.java)
    - [ThermochemistryPipeline](../../solver/src/main/java/mmm/fluid/ThermochemistryPipeline.java)：`stepTransport` 与反应、相变顺序
  - 验证选择器 / 参考：`thermal`；[热学参考](thermal-state.md)

- **反应、析出与溶解**
  - 主要文件：
    - [RegionChemistry](../../model/src/main/java/mmm/chemistry/RegionChemistry.java)：区域注册表、化学源汇审计与反应调度；库存数量和组成由 `LiquidCellInventory` 保存。
    - [ChemicalReactionStage](../../solver/src/main/java/mmm/fluid/ChemicalReactionStage.java)
    - [GranularReactionStage](../../solver/src/main/java/mmm/fluid/GranularReactionStage.java)：`precipitate`、`depositPrecipitate` 共用活动代表生成与组成合并，未解析析出物向合法床库存交接物质、反冲和内能。
    - [DomainCapabilities](../../solver/src/main/java/mmm/fluid/DomainCapabilities.java)：热能力、载体内能与联合相变交接
  - 验证选择器 / 参考：
    - `chemistry,granular,thermal`；[化学参考](chemistry-state.md)
    - [固液转移](granular-state.md#固液相转移)

- **相变批次、接触与未来气腔**
  - 主要文件：
    - [CondensedPhaseChangeStage](../../solver/src/main/java/mmm/fluid/CondensedPhaseChangeStage.java)：冻结膨胀沿 `PhaseQuantitySettlement` 的共同容量网络排液；容量不足时只缩减受阻位置的冻结提议，保留独立区域的可执行批次。
    - [GasLiquidPhaseChangeStage](../../solver/src/main/java/mmm/fluid/GasLiquidPhaseChangeStage.java)：`propose`、`closeChamber` 联合组分库存、气温与真实容量的分压响应；`admitCondensation` 共同分配批次和正容量，`step` 一次提交两相库存与内能。
    - [GasLiquidMassTransferStage](../../solver/src/main/java/mmm/fluid/GasLiquidMassTransferStage.java)：有限膜速率与整数物种、质量、内能联合提交；稳定库存身份的待处理时长和摩尔余量随区域状态保存。
    - [GasLiquidContactGeometry](../../solver/src/main/java/mmm/fluid/GasLiquidContactGeometry.java)：实际 PLIC 与开放微盒提供按材料、气腔聚合的物理界面面积。
    - [GasLiquidPhaseModel](../../model/src/main/java/mmm/thermal/GasLiquidPhaseModel.java)：饱和压力、解析斜率及潜热
    - [GasPhaseInterfaceResolver](../../solver/src/main/java/mmm/fluid/GasPhaseInterfaceResolver.java)
  - 验证选择器 / 参考：`thermal,gas-liquid`；[相变参考](thermal-state.md#相变)

- **沉积、起动、床层与液体位移**
  - 主要文件：
    - [GranularTransitionStage](../../solver/src/main/java/mmm/fluid/GranularTransitionStage.java)
    - [GranularInventory](../../model/src/main/java/mmm/granular/GranularInventory.java)
    - [GranularDepositDynamics](../../model/src/main/java/mmm/granular/GranularDepositDynamics.java)：`settlementTarget` 统一选择本列与真实可达的下坡邻列，按原安息角、整数容量与非增势能准备入床计划。
    - [GranularLiquidDisplacement](../../solver/src/main/java/mmm/fluid/GranularLiquidDisplacement.java)
    - [GranularBedShear](../../solver/src/main/java/mmm/fluid/GranularBedShear.java)
  - 验证选择器 / 参考：`granular`；[床层参考](granular-state.md#deposit-松弛与局部唤醒)

<a id="candidate"></a>
<a id="persistence"></a>
## 存档和故障恢复

完整状态由 `FluidDomainStateMapper` 捕获与恢复。
气体初始化状态随同保存；恢复交接库存和动量，速度在查询或机械步骤中按需准备；气腔库存由后续明确的物理准备创建。
正常候选直接绑定已验证的固定几何，共享只读网格面索引、液体不可变库存行与气相几何，独立复制 MAC 动量和气体网络；颗粒与沉积库存列按实际数量增长，保留原容量上限；非液体内能由 `ThermalOwnerSnapshot` 携带。气体交接由 `FluidDomainState.GasContinuation` 持有捕获副本，存档分区投影在保存时生成。
调度进度、待同步相变几何与世界热交换面随同交接。压力在源侧按当前气腔、静水与毛细参考规范化，候选直接承接不可变残差；保存时从完整快照导出实际压力。`FluidDomainState.Layout` 绑定网格、配置、载体布局和注册表身份；区域 checkpoint 的持久字段另由存档模型定义。

`CpuFluidSolverBackend` 在私有域完成整个逻辑步；力学细化从原始请求重新恢复，保留总推进时间、外部输入和一次提交。`RegionApplicationService` 与仓库核对后端、区域身份、紧邻求解器代次和完整布局。CPU 可把已求解私有对象交接一次；其他后端提供完整不可变状态。候选失败保留原区域，成功后发布该候选的完整统计。

可选 Vulkan 混合计算沿同一 CPU 私有候选运行，共同 CSR 的 float64 完整压力迭代与液体批量整数质量、物种分份在设备执行。CPU 准备共同几何、耦合矩阵与精确 dyadic 体积路线，使用设备份额继续结算动量、内能及载体后继。`LiquidCellInventory.planRoutes` 的可选数量接口直接接收设备结果，原子提交保持完整区域候选。

设备常驻液体入口由 `VulkanLiquidRegion` 持有同代库存与 MAC，依次记录库存派生几何、重力、真实七点 CSR、压力、格面投影与 PLIC 面扫掠偏好，再准备容量、材料路线与库存后继。几何工作区采用 float32，解析尺度为 4e-5 体积；精确库存、int64 数量、动力学与压力保持原精度。`VulkanLiquidGeometry.recordTransportPreferences` 在投影后，根据真实共享开放端口与源侧 PLIC 薄片计算格面有符号体积 `(Qt,Ql)`，写入 binding 13 的 `faceFlux`。`advanceMechanical` 在同一候选中完成容量、精确体积、材料与数量输运、MAC、未来几何、机械热和精确账，再连续提交设备代次。源与未来几何采用独立绑定，保留投影后的源速度。`MechanicalDefinitions` 显式提供材料相序、物种质量、参考能和容量预留，`fromRegistries` 从真实流体、化学与热注册表组装这些列；缺失的物种偏移保留为未声明状态。生产区域的其他物相继续迁移。

`VulkanLiquidCellVolumes` 汇总精确单元体积并给出几何量子供给；`VulkanLiquidCapacityInputs` 将固定开放面、固体占容与投影偏好转换为整数输入，有限气腔通过旧节点的真实支撑提供组容量。`VulkanLiquidCapacity` 将供体、七路接收单元、有限组及总量汇集节点组成带上下界的运输残量图；普通液体流动和新固体占容排液共用同一算法。初始流保留实际七路投影偏好。满液散度先沿真实双向开放面的单元树成对调整原地余量与邻格流量，汇入允许自由出液且有接收余量的单元；剩余差额通过并行残量标签与稀疏配额推进。实际提案边和受影响顶点按轮次登记，大连接组采用工作组归约；已满足需求的零距离顶点继续参加余额发送和标签提升。树准备、残量建层与流量推进使用独立命令批次，无工作阶段由设备派发零工作组。最终接受量使每个供体完整分配原库存、接收单元满足绝对上下限、有限组满足容量；源库存允许超过未来单元上限。无法满足约束的候选保留残量割集供上层缩步，成功后才发布七路整数接受量。`VulkanLiquidAcceptedVolumes` 将整数结果转为七路精确 dyadic 体积，全部小于几何量子的尾数留在原格。普通步的几何量化与精确库存分开；完全排空和强制位移仍需精确余量路线。

已接受单元体积的设备处理按材料路线→数量分份→库存归并→MAC 搬运、未解析格面合并与壁面处理→耗散热写入组织。`VulkanLiquidMaterialRoutes` 将已接受单元 dyadic 体积按材料相序与源库存分配，按源 CSR 保存实际非零方向及精确原地余量；水平两相的设备材料分份已接入固定源与连续机械成本入口。`VulkanLiquidCellVolumes` 负责精确单元体积合计与整数容量供给，已接入连续机械阶段入口。`VulkanLiquidRouteQuantities` 根据实际路线体积派发整数质量、物种和参考能，源内能分配差 `ΣrouteU−sourceU` 由每源8项浮点展开保存。`VulkanLiquidGather` 合并同材料到达库存，重建单元、物种与 dyadic CSR，保存每目标7项 `ΣrouteU−outU` 展开。`VulkanLiquidMomentum.recordTransport/recordResolve` 沿整数质量通量搬运 MAC 动量，生成单元耗散热与墙面冲量；`recordHeatPosting` 按目标质量分配热量、更新库存内能，并保留每库存和单元的舍入展开。`VulkanEnergyLedger` 将数量分份、归并与热写入的有符号浮点展开精确归总为设备 dyadic 项；机械热总量独立归总，用于共同热账交接。以上接口已用于固定源的材料输运分项，并与容量接受组合进入连续机械步；有限气腔库存后继已共同提交，完整生产后端仍待接入。

`VulkanLiquidStateLayout` 将分配容量与设备逻辑计数分开，导入和导出按有效前缀处理；`VulkanGasStateLayout` 同样保存实际逻辑计数、arena 起点和可增长容量，覆盖气体库存、拓扑、收支与节点内能列。`VulkanGasGeometry` 准备源与后继气体几何；`VulkanGasLineage` 按真实干空间流量生成来源／目标关系，`VulkanGasRemap` 在绝热功前后分别处理保留库存和后继归并。`VulkanRegionStorage` 的冷导入先在设备清零工作区，再按长整型偏移分块上传实际库存、静态表与布局头；主机编码缓冲随实际库存和分块大小分配。它支持候选扩容、按列复制及同一私有代次的多批执行，最终批完成后才接管。`VulkanLiquidRegion.Workspace` 集中持有一代完整布局；正常步只复制权威列、静态表和必要缓存，临时列在本步重建。`VulkanBufferCopyPlan` 合并持久列复制；已提交液面与气体几何在下一步复用，实际几何变化时重新生成。`VulkanComputeCommandCache` 按设备 arena 与布局复用求解小批命令。气体搬运先按来源关系计算全部嵌套库存空间需求。容量不足时读取需求计数、丢弃失败候选，并从原捕获重新准备更大工作区；源列按旧布局读取，后继按新布局写入，编译后的管线继续复用。`VulkanPressureMultigrid` 为结构网格压力提供多重网格预条件；`VulkanPrefixScan` 记录分层 uint32 exclusive scan，供动态 CSR、容量组成员和变长 limb 建表使用。

`VulkanLiquidRegion.GasAperture` 在未来气体库存、压缩功与共同机械能准备后，按同代真实气腔体积求解有限孔流。孔流拥有独立PCG及热状态工作区，复用已完成拓扑交接的库存池；实际保留气体冷却、接收焓流、环境收支与整步舍入账沿同一候选提交。原请求的提交步序在容量重算时保持，气腔对和嵌套库存需求参加整体工作区增长。无受限孔口时清零本步孔流能量账并直接完成候选。

设备共同机械能由 `VulkanMechanicalBodies` 根据压力湿面、实际质量路线和有限气腔归属建立连通体，`VulkanLiquidThermodynamics` 从后继库存及真实热容分段派生各库存温度、热容和最低温度内能，并按单元总内能反演混合液体的共同温度。`VulkanMechanicalEnergy` 汇总源动能与投影动能之差、实际垂向质量输运的势能变化和气体做功，按连通体向后继热载体分配能量；加热按热容分配，冷却按可释放显热分配。已经随物质路线搬运的局部耗散热从共同修正中抵扣，规定运动的外功独立加入。实际内能端点差与物理做功之间的舍入量交给同一个精确能量账。规约根据设备成员表生成实际计算块数。普通环境与有限气腔的液体连续步均已使用这些内核，气体做功按实际后继控制体积结算。颗粒扩展沿同一热容反演与共同机械能规则：压力／阻力联系、实际支撑与接触组连接机械体，实际接纳后的动能和高度进入功计算，热量写回颗粒自身内能；完整颗粒调用链继续实施。

`VulkanGasPressure` 把液体单元与有限气腔放入同一个压力系统，气体压缩响应由同代库存和状态方程提供；液体块使用多重网格，气体行使用同一 PCG 中的气体预条件。气体矩阵乘积按真实关联面计算，小气腔直接求和、大气腔分块规约，设备根据实际气腔数量间接派发。压力阶段输出预测体积响应和面动能，权威气体内能仍由实际接受的体积变化决定。`VulkanGasAdiabaticWork` 读取旧源实际控制体积、未来控制体积与同一套分段热容函数，生成候选内能及精确端点做功展开，供气体库存搬运与共同机械能结算使用。

设备计算服务由服务器运行时创建，并通过单一 `ComputeExecution` 作用域绑定。明确的设备丢失会释放服务、丢弃候选，从原始请求在 CPU 重算后沿普通提交链接管；后续请求继续 CPU 并记录恢复原因。其他计算异常保留原区域。启动选择见[运行时](runtime-and-lifecycle.md#startup)。完整设备常驻区域状态、各物相输运及 checkpoint 恢复继续按[实施计划](../roadmap/solver-consolidation-plan.md)推进。

存盘以 `cell-mac` 格式保存单元液体记录、微盒相占据、规范分区的三轴格面动量、碰撞掩码和非液体库存。
液体记录直接携带组成和内能，热扩展保存非液体内能及总账。
双槽写入、回读并验证全部成员，再原子替换 `HEAD`。删除发布 `REMOVED` 终止代次后完成移除通知、注销与载荷隔离。捕获读取已提交的同代库存和气腔状态；冷恢复完整验证支撑、容量、物性与账本。

桶、发射器和世界方块操作使用 WAL 绑定区域与外部域的前后状态；恢复按 HEAD 与外部已存盘状态确定继续提交、清理或保留证据。`LOCKED` 对应无法解释的持久身份、格式或事务状态；数值暂停和性能暂停保留上一个完整物理状态。

- **完整候选与接管**
  - 主要文件：
    - [FluidStateAccess](../../solver/src/main/java/mmm/fluid/FluidStateAccess.java)：持有同一代状态的引用，布局查询无需完整导出；应用层在成功、拒绝或异常后关闭捕获引用。`FluidDomainState` 直接实现 CPU 状态访问。
    - [FluidDomainState](../../solver/src/main/java/mmm/fluid/FluidDomainState.java)
    - [FluidDomainStateMapper](../../solver/src/main/java/mmm/fluid/FluidDomainStateMapper.java)
    - [ThermalOwnerSnapshot](../../model/src/main/java/mmm/thermal/ThermalOwnerSnapshot.java)
    - [CpuFluidSolverBackend](../../solver/src/main/java/mmm/fluid/CpuFluidSolverBackend.java)
    - [RegionApplicationService](../../app/src/main/java/mmm/application/RegionApplicationService.java)
    - [FluidRegionRepository](../../fabric/src/main/java/mmm/simulation/FluidRegionRepository.java)
  - 验证选择器 / 参考：`lifecycle`；[后端字段](fluid-solver.md#后端候选接口)

- **区域 checkpoint、HEAD 与冷恢复**
  - 主要文件：
    - [RegionCheckpointAssembler](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointAssembler.java)：`sectionKey` 共用 `RegionGeometry.worldBlockPosition` 的整数世界原点与局部偏移换算，保存、校验和恢复使用同一分区规则。
    - [RegionCheckpointRestorer](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointRestorer.java)
    - [RegionGenerationStore](../../fabric/src/main/java/mmm/simulation/persistence/RegionGenerationStore.java)：绑定 HEAD 的分片读取与完整共同提交。
    - [RegionRepositoryAdapter](../../fabric/src/main/java/mmm/simulation/RegionRepositoryAdapter.java)：`restoreIncrementally` 分批驻留同代 section，完整后恢复整个 CPU 求解域；世界缺区块保持磁盘读取进度。
    - [RegionDiscovery](../../fabric/src/main/java/mmm/simulation/persistence/RegionDiscovery.java)：启动只读取清单，保留尚未加载的库存汇总。
  - 验证选择器 / 参考：`region-state`；[存档参考](region-state.md)

- **玩家、发射器及世界事务**
  - 主要文件：
    - [MinecraftPoolOperations](../../fabric/src/main/java/mmm/simulation/MinecraftPoolOperations.java)：`probe` 完成管理员探针预览确认，`returnWater` 暂停并归还可表示水块，余量取走后移除；`allowsBlockChange` 隔离池内原版水写入。
    - [NativePoolScanner](../../fabric/src/main/java/mmm/simulation/controller/NativePoolScanner.java)：已加载静水、天然底壁、大气开口与尺寸预算。
    - [FluidPortBlock](../../fabric/src/main/java/mmm/block/FluidPortBlock.java)：六向 `FACING` 朝外的显式容器口，保持外壳碰撞。
    - [FluidWorldTargetResolver](../../fabric/src/main/java/mmm/simulation/FluidWorldTargetResolver.java)：`transferTargetAtBlock` / `resolvePortTarget` 统一正面端口与唯一已加载内侧目标；`gasShellTargetAtBlock` 定位创造气体源的真实壳面。
    - [PlayerContainerTransferAdapter](../../fabric/src/main/java/mmm/simulation/PlayerContainerTransferAdapter.java) / [DispenserContainerTransferAdapter](../../fabric/src/main/java/mmm/simulation/DispenserContainerTransferAdapter.java)：玩家命中面、发射器朝向和真实容器槽位适配。
    - [MinecraftContainerTransferPlan](../../fabric/src/main/java/mmm/simulation/MinecraftContainerTransferPlan.java)
    - [ContainerTransferCoordinator](../../app/src/main/java/mmm/application/ContainerTransferCoordinator.java)
    - [ContainerRecoveryCoordinator](../../app/src/main/java/mmm/application/ContainerRecoveryCoordinator.java)
    - [OwnershipTransactionCoordinator](../../fabric/src/main/java/mmm/simulation/OwnershipTransactionCoordinator.java)
  - 验证选择器 / 参考：`containers,ownership`；[事务参考](world-ownership-and-containers.md)

- **相变同步与碰撞编辑**
  - 主要文件：
    - [MinecraftPendingPhaseWorkBarrier](../../fabric/src/main/java/mmm/simulation/MinecraftPendingPhaseWorkBarrier.java)
    - [FluidRegionWorldInteraction](../../fabric/src/main/java/mmm/simulation/FluidRegionWorldInteraction.java)
  - 验证选择器 / 参考：`thermal,region-manager`；[同步顺序](runtime-and-lifecycle.md#phase-work)

<a id="client"></a>
## 查询和展示

展示输入、当前绘制行为及 Iris 接口依据见[流体展示参考](fluid-presentation.md)。D01–D03 的待实施范围与完成条件由实施计划维护。

服务端从已提交单元库存、同代 PLIC 与固定开放微盒生成局部凸片；材料按库存份额在单元内沿重力方向分区，界面位置采用单元尺度近似。客户端裁剪共享面并生成一份带双侧材料及法向的网格，相机介质读取同一凸片。完整几何经过有界分片传输，接齐后按区域身份、网络代次和 sequence 接收；移除消息使旧包失效。显示温度、压力、颜色与网格采用各自量化，库存、状态方程和物理计算读取原始数据。

客户端显色由 `FluidDisplaySettings` 保存当前玩家的组分、pH 或温度模式及固定温度色带范围，控制器界面的显色入口打开 `FluidDisplayScreen`。
`FluidColorMapping` 集中映射颜色，`MaterialFrame` 持有同一已接受快照的几何、逐相 pH 与凝聚相温度。展示值改变复用网格并刷新颜色；模式与范围变化在下一显示帧生效，暂停区域保持相同物理状态。
pH 从适用水相的 H⁺/OH⁻ 与水相体积派生，油等物相显示无数据；中性和无数据分别编码。温度直接读取服务端单元凝聚相展示值，色带以 K 标注。具体近似及编码见[化学参考](chemistry-state.md#ph-与显色)。

Query API 在服务器线程读取已加载区域，单格观察保持只读；容器提交事件在区域与物品均已持久完成、WAL 清理后发布。未加载区域提供已知元数据及不可用原因。

- **快照、网络和接收顺序**
  - 主要文件：
    - [FluidSnapshotPublisher](../../fabric/src/main/java/mmm/simulation/FluidSnapshotPublisher.java)
    - [FluidSurfaceInput](../../solver/src/main/java/mmm/fluid/FluidSurfaceInput.java)：局部裁剪片及相机点归属
    - [FluidSurfaceProjection](../../solver/src/main/java/mmm/fluid/FluidSurfaceProjection.java)：库存、PLIC 与开放微盒的同代投影
    - [FluidSnapshotPartPayload](../../fabric/src/main/java/mmm/network/FluidSnapshotPartPayload.java)：完整快照分片和接齐发布
    - [FluidProtocol](../../fabric/src/main/java/mmm/network/FluidProtocol.java)
    - [ClientRegionSnapshotStore](../../fabric/src/client/java/mmm/client/ClientRegionSnapshotStore.java)
  - 验证选择器 / 参考：`protocol`

- **液面、材料界面与颗粒渲染**
  - 主要文件：
    - [ClientFluidVolumeRenderer](../../fabric/src/client/java/mmm/client/ClientFluidVolumeRenderer.java)：协调快照、几何与绘制
    - [FluidRenderBackend](../../fabric/src/client/java/mmm/client/FluidRenderBackend.java)：材质和诊断管线、GPU 缓冲与渲染 pass 提交
    - [FluidSurfaceGeometry](../../fabric/src/client/java/mmm/client/FluidSurfaceGeometry.java)
    - [MaterialSurfaceGeometry](../../fabric/src/client/java/mmm/client/MaterialSurfaceGeometry.java)：复用表面网格并准备已着色三角形
    - [FluidMaterialSurfaceMesher](../../solver/src/main/java/mmm/fluid/FluidMaterialSurfaceMesher.java)：共享面裁剪、双侧材料与一致朝向；[SurfacePolyhedron](../../solver/src/main/java/mmm/fluid/SurfacePolyhedron.java) 执行凸体裁剪
    - [IrisFluidCompatibility](../../fabric/src/client/java/mmm/client/IrisFluidCompatibility.java)：可选管线、当前光影材料映射及版本接口；客户端 mixin 提供阴影提交和相机介质
    - [FluidColorMapping](../../fabric/src/client/java/mmm/client/FluidColorMapping.java)：组分、逐相 pH、凝聚相温度与无数据颜色
    - [FluidDisplaySettings](../../fabric/src/client/java/mmm/client/FluidDisplaySettings.java) / [FluidDisplayScreen](../../fabric/src/client/java/mmm/client/FluidDisplayScreen.java)：本机设置、模式选择、图例与温度范围
    - [CarrierInventoryReader](../../solver/src/main/java/mmm/fluid/CarrierInventoryReader.java)：`copyChemicalDisplaySnapshot` 汇总水相 pH；`copyThermalDisplaySnapshot` 导出凝聚相温度
    - [GranularSurfaceGeometry](../../fabric/src/client/java/mmm/client/GranularSurfaceGeometry.java)
  - 验证选择器 / 参考：`protocol,materials`；展示目标见[产品路线](../roadmap/product-roadmap.md#阶段-3展示几何契约与重建)

- **外部查询、转移、Jade 与事件**
  - 主要文件：
    - [MmmFabricApi](../../fabric/src/main/java/mmm/api/fabric/MmmFabricApi.java)
    - [FabricFluidQueryApi](../../fabric/src/main/java/mmm/simulation/FabricFluidQueryApi.java)
    - [MmmTransferApi](../../api/src/main/java/mmm/api/transfer/MmmTransferApi.java) / [FabricTransferApi](../../fabric/src/main/java/mmm/simulation/FabricTransferApi.java)：`registerActor`、`plan`、`commit` 与实际数量／组成／内能清单；预览与提交重验真实发射器正面端口及紧邻内部目标
    - [PublicTransferReceiptStore](../../fabric/src/main/java/mmm/simulation/persistence/PublicTransferReceiptStore.java)：持久实例绑定、未完成操作与最近终态回执；物品恢复沿共同容器 WAL
    - [ContainerTransferManifests](../../model/src/main/java/mmm/transfer/ContainerTransferManifests.java)：从实际容器前后态生成物质与热量交接清单
    - [CommittedEventHub](../../fabric/src/main/java/mmm/simulation/CommittedEventHub.java)
    - [Jade 适配](../../fabric/src/main/java/mmm/compat/jade/)
  - 验证选择器 / 参考：`protocol,containers`；[外部 API 参考](external-api.md)

<a id="gameplay"></a>
## 游戏入口

- **方块、物品、创造栏、配方与文字**
  - 主要文件：
    - [MmmBlocks](../../fabric/src/main/java/mmm/block/MmmBlocks.java)
    - [MmmItems](../../fabric/src/main/java/mmm/item/MmmItems.java)
    - [MmmCreativeEntries](../../fabric/src/main/java/mmm/item/MmmCreativeEntries.java)
    - [资源](../../fabric/src/main/resources/)
  - 验证选择器 / 参考：资源编译；涉及操作再选择对应领域

- **控制器激活、暂停、清空与拆除**
  - 主要文件：
    - [FluidControllerBlock](../../fabric/src/main/java/mmm/block/FluidControllerBlock.java)
    - [MinecraftRegionActivation](../../fabric/src/main/java/mmm/simulation/MinecraftRegionActivation.java)
    - [MinecraftControllerConsole](../../fabric/src/main/java/mmm/simulation/MinecraftControllerConsole.java)
    - [MinecraftRegionLifecycle](../../fabric/src/main/java/mmm/simulation/MinecraftRegionLifecycle.java)
  - 验证选择器 / 参考：`boundary,lifecycle,region-manager`；[授权与操作](runtime-and-lifecycle.md#console)

- **耕地湿润与外部土壤汇**
  - 主要文件：
    - [WaterContactGeometry](../../solver/src/main/java/mmm/fluid/WaterContactGeometry.java)：`capture` 按同代微盒／PLIC及水相材料层裁剪接触面，提供实际来源库存。
    - [FarmlandMoistureOperations](../../fabric/src/main/java/mmm/simulation/FarmlandMoistureOperations.java)：`tickAvailable` 准备足额水相提取与湿度后态，`recoverIndexed` 恢复未完成事务。
    - [FarmlandJournalStore](../../fabric/src/main/java/mmm/simulation/persistence/FarmlandJournalStore.java)：保存逐耕地湿度前后态、真实提取清单及区域HEAD身份。
    - [LevelBlockMutationMixin](../../fabric/src/main/java/mmm/mixin/LevelBlockMutationMixin.java)：未完成事务的位置保持前后状态，限定本次恢复写入豁免。
  - 验证入口：`MovingBodyCouplingValidation --water-contact`、`RegionRepositoryAdapterValidation --farmland`。
  - 契约：[耕地湿润](world-ownership-and-containers.md#耕地湿润)、[吸水内能](thermal-state.md#耕地吸水的内能交接)；当前实现与验收状态见实施计划。

- **材料与试剂创造来源**
  - 主要文件：
    - [CreativeSourceCatalog](../../fabric/src/main/java/mmm/simulation/CreativeSourceCatalog.java)
    - [CreativeGasSource](../../model/src/main/java/mmm/transfer/CreativeGasSource.java)：气体创造模板及标准体积
    - [DomainGasInventory](../../solver/src/main/java/mmm/fluid/DomainGasInventory.java)：有限气腔点定位、局部气体库存与内能源汇联合提交
    - [PlayerContainerTransferAdapter](../../fabric/src/main/java/mmm/simulation/PlayerContainerTransferAdapter.java)：真实壳面注气／抽气，复用容器 WAL 和区域 HEAD
  - 验证选择器 / 参考：`containers,chemistry`

- **恒温热源与快速入门界面**
  - 主要文件：
    - [ThermalReservoirUseCase](../../app/src/main/java/mmm/application/ThermalReservoirUseCase.java)
    - [ThermalReservoirScreen](../../fabric/src/client/java/mmm/client/ThermalReservoirScreen.java)
    - [QuickStartScreen](../../fabric/src/client/java/mmm/client/QuickStartScreen.java)
  - 验证选择器 / 参考：`thermal,protocol`

- **命令、开发场景与诊断报告**
  - 主要文件：
    - [MmmCommands](../../fabric/src/main/java/mmm/MmmCommands.java)
    - [MmmCommandHandlers](../../fabric/src/main/java/mmm/MmmCommandHandlers.java)
    - [MinecraftDebugFixtures](../../fabric/src/main/java/mmm/simulation/MinecraftDebugFixtures.java)
    - [RegionDiagnosticsReport](../../fabric/src/main/java/mmm/simulation/RegionDiagnosticsReport.java)
  - 验证选择器 / 参考：`audit` 及对应业务领域；[命令速查](../maintenance/commands.md)

<a id="performance"></a>
## 性能与诊断

完整步成本涵盖输入捕获、私有域恢复、全部力学子步、热化学、重试和接管。独立冷恢复、世界输入、展示、网络与磁盘 I/O 分列测量。`RegionTickUseCase` 根据完整执行时间进行 pacing 和连续过载暂停，调度保持库存与逻辑步时长。

成功步的 `StepStats.mechanicalCorrection` 保存力积分、输运势能、气腔反向功及机械体合并后的有符号与绝对修正量；开启机械审计时，`MicroBoxPlaneVolume.verticalMoment` 计算同一 PLIC 的竖向第一矩，区分格心势能表示交换与已解析的压力／力学交换。长重放累计有符号量与绝对变差，分别按几何空间范围和实际机械输入评价；已解析交换中的压力耗散与热浮力结合原机械审计分项判断。黏性、混合等物理耗散继续单独结算。

故障诊断保留原异常及其 `cause` 链。
`MechanicalStepPipeline` 通过 suppressed 异常附加失败阶段、机械子步、时间间隔和区域规模；`ThermochemistryPipeline` 同样附加导热、液体反应、固相反应或相变阶段。
后端保留领域异常或以候选求解异常携带原始原因。
先定位具体阶段和最内层原因，再检查该输入的真实库存、开放分量、实际固体终点和气腔容量。
容量异常沿 `PhaseQuantitySettlement` 的供体、接收分量与气腔组追查；压力异常结合残差对应的体积误差评价，热异常结合载体质量、组成和内能定位。

- **生产完整步与阶段成本**
  - 入口：
    - [ProductionStepBenchmark](../../fabric/src/test/java/mmm/simulation/ProductionStepBenchmark.java)
    - [CompositeStepBenchmark](../../fabric/src/test/java/mmm/simulation/CompositeStepBenchmark.java)
    - [ProductionFluidReplayValidation](../../fabric/src/test/java/mmm/simulation/ProductionFluidReplayValidation.java)
  - 运行方法：[预算与测量](../maintenance/validation.md#performance)

- **库存、几何与速度重复重建**
  - 入口：`CellMacStage`、`LiquidCellInventory`、`GasRuntimeGeometry`、`FluidDomainStateMapper`
  - 运行方法：同一原输入的阶段计时及 [JFR 工具](../../pytool/jfr_hotspots.py)

- **液固压力、阻力与接触成本**
  - 入口：`GranularImplicitDrag.prepare/applyResponse`、`PressureLinearSolver`、`PressurePreconditioner`、`GranularActiveContacts`、`GranularContactCompression`
  - 运行方法：沙床启动短重放、原慢步及完整生命周期

- **GPU 设备、资源、提交与回读**
  - 入口：
    - [VulkanDeviceContext](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanDeviceContext.java)
    - [DeviceLiquidInventory](../../model/src/main/java/mmm/inventory/DeviceLiquidInventory.java)：`encode/decode` 交接液体与物种 CSR、整数质量和原始内能
    - [DeviceDyadicArray](../../model/src/main/java/mmm/units/DeviceDyadicArray.java)：无损体积的变长 limb、符号和指数列
    - [VulkanInventoryTransport](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanInventoryTransport.java)：int64 设备常驻库存输运与 checkpoint，候选存储交由 `VulkanRegionStorage`；完整区域输运接入待完成
    - [VulkanRegionStorage](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanRegionStorage.java)：`capture/prepare` 持有设备代次；`Candidate.execute/beginBatch/continueBatch/finishBatch/commit` 复制并执行候选，按批读取状态，最终批成功后切换持有引用；`prepareReallocated` 为容量增长准备新缓冲区；`initialize/read` 显式分段导入与导出。仍被捕获的旧代次保留，已释放的设备缓冲区复用。
    - [VulkanLiquidStateLayout](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidStateLayout.java)、[VulkanGasStateLayout](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGasStateLayout.java)：液气列布局、容量、逻辑计数与显式导入导出；`copyColumns` 记录设备扩容复制
    - [VulkanLiquidRegion](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidRegion.java)：常驻液体代次、`rebuildGeometry/prepareMechanicalFields` 阶段入口与 `advanceMechanical` 连续候选提交；`MechanicalDefinitions.fromRegistries` 组装真实物性表
    - [VulkanFluidSolverBackend](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanFluidSolverBackend.java)、[VulkanFluidStateCodec](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanFluidStateCodec.java)：生产格式冷导入、私有候选评估和按代持有的区域状态；`PreparedFluidState.commit` 由应用层采用，显式检查点导出精确库存与调度进度。
    - [ResidentFluidState](../../solver/src/main/java/mmm/fluid/ResidentFluidState.java)：设备代次的查询与展示契约；[VulkanRegionObservation](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanRegionObservation.java)、[VulkanRegionInventorySummary](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanRegionInventorySummary.java) 归约常数尺寸的运动、热学和库存观察，控制器轮询保持设备常驻。
    - [VulkanBodyObservation](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanBodyObservation.java)、[VulkanCellObservation](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanCellObservation.java)、[VulkanWaterContacts](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanWaterContacts.java)：从固定代次读取实体浸液、单元材料／物种和面水接触，派生输出独立分配。
    - [VulkanLiquidDisplay](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidDisplay.java)、[VulkanRegionDisplay](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanRegionDisplay.java)、[VulkanGranularDisplay](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGranularDisplay.java)：设备派生平面／材料分层、化学／温度字段和颗粒显示采样；[CompactFluidSurface](../../solver/src/main/java/mmm/fluid/CompactFluidSurface.java) 保存紧凑几何，客户端按需产生凸片，网络沿同一字段交接。
    - [VulkanGranularTransitions](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGranularTransitions.java)：同代活动颗粒沉积、床层松弛、剪切再起动及精确库存重组；[GpuGranularTransitionsBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuGranularTransitionsBenchmark.java) 覆盖连续生命周期。
    - [VulkanEntityBodyCoupling](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanEntityBodyCoupling.java)：实体AABB合并占容、共享面面积、规定速度接触、压力反力与外功；[GpuEntityBodyCouplingBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuEntityBodyCouplingBenchmark.java) 执行真实剪切和逐实体汇总。
    - [VulkanLiquidReactions](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidReactions.java)、[VulkanSolidLiquidReactions](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanSolidLiquidReactions.java)：精确化学计量、物种CSR增长、固液SVQ转移和动量／热量结算；实际短链入口分别为 `GpuLiquidReactionsValidation`、`GpuSolidLiquidReactionsValidation`。
    - [VulkanCommandTemplates](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanCommandTemplates.java)、[FlipKernelBindings](../../gpu-kernel/src/main/java/mmm/gpuprobe/FlipKernelBindings.java)：工作区持有可复用的压力／容量二级命令，缓冲区对象身份与切片对应不可变描述符；工作区增长或关闭时释放旧模板
    - [VulkanLiquidGeometry](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidGeometry.java)、[VulkanLiquidProjection](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidProjection.java)：库存派生 PLIC、切格与 MAC 几何，重力、物理 CSR 和格面压力响应；`recordTransportPreferences` 生成 PLIC 面扫掠偏好
    - [VulkanPressureMultigrid](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanPressureMultigrid.java)：结构网格多重网格预条件；活动集改变时构建最粗层逆算子，迭代内复用矩阵向量乘积，设备控制实际构建派发
    - [VulkanLiquidRouteLayout](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidRouteLayout.java)：实际非零路线的源 CSR、方向掩码、精确体积、整数数量、物种与内能列
    - [VulkanLiquidCellVolumes](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidCellVolumes.java)：精确单元体积合计、dyadic CSR 与整数容量供给
    - [VulkanLiquidCapacityInputs](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidCapacityInputs.java)：固定几何、占容与投影偏好生成普通环境液体步的整数容量输入
    - [VulkanLiquidCapacity](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidCapacity.java)、[VulkanLiquidCapacityLayout](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidCapacityLayout.java)：普通流动与固体排液共用七路整数运输、单元绝对上下限与有限组容量；原地保留满足容量时并行撤回违规偏好流，按每边最大请求撤回并归还供体；需要重新分配时延迟构建同一下界残量图，以64位原子配额与增量余额推进；`recordInitialize/recordIterations/recordFinish` 按设备阶段反馈组织私有候选的稀疏并行推进；[GpuLiquidCapacityReplay](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuLiquidCapacityReplay.java) 重放保存的真实失败输入
    - [VulkanLiquidAcceptedVolumes](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidAcceptedVolumes.java)：已接受整数流量转为精确 dyadic 七路体积，原格完整保留亚量子尾数
    - [VulkanGasGeometry](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGasGeometry.java)：设备气体几何、真实干口、气腔连通与成员 CSR；源与后继几何分别服务于压力和库存搬运
    - [GpuGasGeometryBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuGasGeometryBenchmark.java)：固定源坝体与切割场景的气体几何、气腔连通、体积归约和分段热容状态方程成本
    - [VulkanGasThermodynamics](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGasThermodynamics.java)：同代气腔体积归约、真实注册表的分段热容与参考能、从节点内能派生温度和压力，供共同压力和保留库存做功使用
    - [VulkanGasPressure](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGasPressure.java)：共同液气压力装配、真实气腔关联面的矩阵乘积、绝对压力下限与格面响应；[GpuGasPressureBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuGasPressureBenchmark.java) 测量固定源完整压力阶段成本
    - [VulkanGasAdiabaticWork](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGasAdiabaticWork.java)：实际源控制体积的分段热容绝热做功、候选内能和精确端点差；库存搬运前调用
    - [VulkanGasLineage](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGasLineage.java)、[VulkanGasRemap](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGasRemap.java)：实际干空间通量的来源与目标 CSR、保留与逸出库存、环境捕获、未来气腔组分和物种归并、绝热功与精确内能舍入交接
    - [VulkanGasRoutes](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGasRoutes.java)、[VulkanGasFlow](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGasFlow.java)：拓扑变化与孔流共用的来源／目标 CSR；`recordAssemble` 合并同对气腔的开口并组装标准体积储容方程，[VulkanGasFlowMixedPressure](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGasFlowMixedPressure.java) 使用预编译的 FP32 物理导通算子、FP32 校正与 FP64 压力和真实残差，短行合并点积归约，长行按实际边分块；可靠残差更新共用原迭代预算，小批设备命令按 arena 复用；`recordRoutes` 按供体可排量同步准备份额。`VulkanGasRemap.transportLayout`、`recordTransportVolumes` 和 `recordFinish` 处理整数库存、实际保留气体的排气冷却、环境焓流及精确能量舍入账；[GpuGasFlowBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuGasFlowBenchmark.java) 提供多孔中心气腔的独立连续成本入口。[GpuGasFlowBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuGasFlowBenchmark.java) 连续提交有限气腔网络，覆盖共享大气腔、同对多孔面与容量需求增长。
    - [VulkanLiquidMaterialRoutes](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidMaterialRoutes.java)：已接受单元体积→分材料七路 dyadic 库存，临时 limb 分配与精确原地余量
    - [VulkanLiquidRouteQuantities](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidRouteQuantities.java)：实际体积分份、物种 CSR 分配、参考能与8项内能误差展开
    - [VulkanLiquidGather](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidGather.java)：同材料到达归并、动态库存 CSR、精确体积和7项内能误差展开
    - [VulkanLiquidMomentum](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidMomentum.java)：`recordTransport/recordResolve/recordHeatPosting`，MAC 搬运、耗散热与墙面冲量、库存内能和舍入交接
    - [VulkanGranularStateLayout](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGranularStateLayout.java)、[VulkanGranularGeometry](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGranularGeometry.java)：活动粒子与沉积库存、整数八格支撑配额及占容；完整颗粒调用链正在实施
    - [VulkanGranularMotion](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGranularMotion.java)、[VulkanGranularContacts](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGranularContacts.java)：设备空间桶生成实际粒子对、固定微盒与沉积床接触；压缩、恢复和摩擦给出成对冲量，扫掠和质量加权去穿透准备私有终点；实际位置与支撑交给共同排水容量接受。`recordPublishPositions` 写入候选终点与机械能高度，失败丢弃整份候选；[GpuGranularMotionBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuGranularMotionBenchmark.java) 测接触至整数占容分项成本
    - [VulkanGranularPressure](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGranularPressure.java)、[VulkanCoupledPressure](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanCoupledPressure.java)：固体体积散度、隐式相间阻力、共同关联算子及其转置，共用唯一 PCG；位置与能量在联合容量接纳后提交
    - [VulkanGranularContacts](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanGranularContacts.java)：设备接触表的成对冲量、摩擦、接触组恢复能量与固定反力／外功；接触几何和完整移动调用链正在接入
    - [VulkanMechanicalBodies](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanMechanicalBodies.java)：压力湿面、实际质量路线、有限气腔、颗粒压力／阻力联系与实际支撑构成的机械连通体和成员 CSR
    - [VulkanLiquidThermodynamics](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidThermodynamics.java)：液体与颗粒库存共享真实注册表的分段热容反演，派生温度、热容与最低温度内能
    - [VulkanThermalConduction](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanThermalConduction.java)：唯一库存热载体的有限体积导热，稳定显式子步与刚性分段热容隐式求解共用实际接触 CSR；长接触行的热导、热流、Newton 残量与矩阵乘积共用并行分块归约；隐式雅可比复用共同 PCG。热库接触具有独立紧凑编号与增长容量，最终按接触热流写回内能并输出精确账的端点与逐子步热库通量项。[GpuThermalConductionBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuThermalConductionBenchmark.java) 测量连续导热内核成本
    - [VulkanThermalContacts](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanThermalContacts.java)：从液体、气腔、活动颗粒、沉积、连续固相及有限热设备库存准备唯一热载体与实际材料质量分项；按真实支撑生成格内样本、微盒覆盖和相邻有限体积接触，写接触时同步生成邻接度数及紧凑热库索引，气腔的各格样本共享唯一库存内能；有限设备端口依据实际液／气／固覆盖面积连接库存载体，共享导热与精确交换账；`recordPublish` 将求解后内能写回对应库存，设备代次沿请求结算。[GpuThermalContactsBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuThermalContactsBenchmark.java) 连续运行同代热反演、实际接触、导热和库存发布。
    - [VulkanLiquidForces](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidForces.java)：从材料热膨胀律与单元共同温度派生热浮力，按 MAC 对偶质量向格面施加动量变化
    - [VulkanSurfaceTension](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanSurfaceTension.java)：高度函数与接触角曲率、液气界面平衡压力及不混溶液相对的表面张力；已与热浮力进入连续步，非零毛细场景成本待测
    - [VulkanLiquidViscosity](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidViscosity.java)、[VulkanMechanicalHeatTransport](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanMechanicalHeatTransport.java)：材料温度黏性、壁面与规定运动接触的隐式耗散；共享面逆质量，预计算颜色偏移、墙面导通及活跃应变阻尼，正反遍历复用同一算子；局部热量随实际整数质量路线搬运；已进入连续液气机械候选
    - [VulkanMechanicalEnergy](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanMechanicalEnergy.java)：液体与实际接纳颗粒运动的共同力学做功、热载体分配和内能端点舍入流；沉积床按所属单元参与热量分配，设备间接派发实际归约块，连通体最终汇总由工作组协作完成
    - [VulkanEnergyLedger](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanEnergyLedger.java)：`record` 按二进制位归总有符号能量项，输出精确 dyadic 热量与舍入账；中间累计采用设备整数列
    - [GpuLiquidGatherBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuLiquidGatherBenchmark.java)：固定源已接受单元体积的材料路线、数量分配、库存归并、MAC、热写入与精确账分项成本及少量 checkpoint
    - [GpuLiquidMechanicalBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuLiquidMechanicalBenchmark.java)：普通环境与有限气腔的液体连续提交成本，覆盖压力、容量、材料库存、MAC、气体搬运与做功、热与精确账；网格、预热和采样由 Gradle 属性指定
    - [GpuLiquidCapacityBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuLiquidCapacityBenchmark.java)：违反接收容量的满格循环与有限组溃坝偏好，报告容量成本、有效状态及实际运输保留比例
    - [VulkanDyadicSum](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanDyadicSum.java)、[liquid-dyadic.glsl](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/liquid-dyadic.glsl)、[liquid-dyadic-sum.comp](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/liquid-dyadic-sum.comp)：设备七项精确加减，测量输出长度、前缀扫描分配变长 limb、写入结果；用于库存归并的精确体积合计
    - [VulkanPrefixScan](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanPrefixScan.java)、[prefix-scan.comp](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/prefix-scan.comp)：设备分层前缀和、CSR 偏移与数量溢出状态
    - [GpuLiquidGeometryBenchmark](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuLiquidGeometryBenchmark.java)：常驻几何与液体投影阶段成本；调用命令和已测范围见验证指南
    - [VulkanPressureKernel](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanPressureKernel.java)：`record` 录入共同 CSR 的完整压力迭代，调用方提供常驻数据切片与提交批次；[pressure.comp](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/pressure.comp) 执行并行归约与收敛状态输出
    - [VulkanSparseMatrixCompute](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanSparseMatrixCompute.java)、[VulkanSparseMatrixProvider](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanSparseMatrixProvider.java)：设备 CSR 完整压力迭代、数量服务与运行时 provider
    - [LiquidQuantityCompute](../../model/src/main/java/mmm/inventory/LiquidQuantityCompute.java)、[VulkanLiquidQuantityCompute](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanLiquidQuantityCompute.java)：`partition` 批量计算供体的实际质量和物种份额，共享压力设备与调用作用域
    - [liquid-quantities.comp](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/liquid-quantities.comp)：整数质量、物种分份和余量承接
    - [FlipGpuResources](../../gpu-kernel/src/main/java/mmm/gpuprobe/FlipGpuResources.java)
    - [GpuStepScheduler](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuStepScheduler.java)
    - [flip.comp](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/flip.comp)
    - [MmmGpuProbeHostIntegration](../../gpu-probe/src/main/java/mmm/gpuprobe/MmmGpuProbeHostIntegration.java)
  - 运行方法：[GPU 验证](../maintenance/validation.md#gpu)；[后端扩展要求](../roadmap/performance-and-backends.md)
  - 验证入口：`DeviceLiquidInventoryValidation`、`GpuInventoryTransportValidation`、`GpuLiquidQuantityValidation`、`GpuPressureValidation`、`GpuCurrentPhysicsValidation`、`ComputeExecutionValidation`、`ComputeDeviceLossValidation`、`GpuPressureScaleBenchmark`；性能入口 `GpuRegionStorageBenchmark` 测代次管理，`GpuResidentPressureBenchmark` 测固定矩阵的常驻压力求解

<a id="diagnosis"></a>
### 按现象定位

| 现象 | 首先核对 | 继续跟到哪里 |
|---|---|---|
| 液面降低、水量异常 | `CellInventoryReader` 的实际体积与材料质量 | `LiquidCellInventory.Plan` 与 `MaterialTransferPlanner` 的 source/sink；再查快照量化 |
| 低速平流或热对流停滞 | 接受的分量 `QL`、原供体库存与材料份额 | `PhaseQuantitySettlement`、`LiquidCellInventory.planRoutes` 及携带内能 |
| 穿墙、小液滴或薄层异常 | `CollisionField` 与 `FlipCutCellGeometry` | `LiquidCollisionEdit`、共同面通量与压力支撑 |
| 冲击后动量或热量不闭合 | 最终 MAC 动量、墙面冲量与局部机械热 | `CellMacStage.stopWallMomentum`、`CellMomentumTransport` 及对应库存提交 |
| 气压或气腔归属异常 | `GasRuntimeGeometry` 的微盒、容量与节点归属 | `GasTopologyCoordinator` 的来源交接及 `GasThermalModel` 的库存／容量／U |
| 近空液体温度异常或压缩功去向异常 | 实际载体 M、U、旧气腔机械模式及做功 | `MechanicalEnergySettlement.prepare` 的实际 K/PE 和气腔功分项，`PressureSystem.sourceGasDoesVolumeWork` 的旧来源分类 |
| 等容交接后气体异常升温 | `GasTopologyCoordinator` 的旧腔控制体积与各接收气腔的净扩容 | 物质来源权重、局部释放的真空与绝热功结算 |
| 均压静水或微扰后出现气穴与温漂 | `PressureSystem` 的共同压力增量、`ConservativeLiquidFlux` 的实际干面接触 | `PhaseQuantitySettlement` 的内部液量下限，再跟到实际气容及压缩功 |
| 接触角铺展方向异常 | `SurfaceTensionFields` 的曲率及墙面法向通量 | 同一字段进入共同压力载荷的实际做功 |
| 温度、反应或相变无进展 | 实际组成、U、批次及候选不可执行原因 | `ThermalCaloricInversion`、`GasLiquidPhaseModel`、`GasLiquidPhaseChangeStage`、未来气腔与热账计划 |
| 颗粒占容、压力或沉积异常 | `GranularCouplingFields`、碰撞预览及整数分配 | `GranularPressureCoupling`、`GranularSettlementGeometryPlan`、`GranularBedShear` |
| 容器／区域锁定 | HEAD、WAL 与外部已存盘状态的分类 | `ContainerRecoveryCoordinator`、`OwnershipCoordinator`；[恢复决策](world-ownership-and-containers.md#恢复决策表) |
