# 当前设计书

本文按一次完整职责链组织当前模型、跨模块契约和代码索引。开发时完整阅读本文与[当前实施计划](../roadmap/solver-consolidation-plan.md)，按本轮改动查阅[验证指南](../maintenance/validation.md)中的场景与预算。
具体公式、字段和操作细节从各节的参考链接继续查阅。实施状态集中在计划，运行结果集中在验证指南。

当前运行范围为最多四个闭壳控制器区域，CPU `FluidDomain` 为物理聚合根。默认格长为 0.5 m，气体采用有限气腔与局部孔流，颗粒采用活动代表粒子与床层。玩法范围和后续能力的目标由[产品路线](../roadmap/product-roadmap.md)维护。

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
| [gpu-kernel](../../gpu-kernel/src/main/java/mmm/gpuprobe/) / [gpu-probe](../../gpu-probe/src/main/java/mmm/gpuprobe/) | Vulkan 实验与 Minecraft 适配 | solver / kernel、fabric |
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

`FluidDomain.markCellInventoryChanged` 集中推进库存代次并标记派生工作区失效。
库存汇总与面惯性在同一次重建中准备；显式速度发布先准备面惯性，再接收调用方速度。
速度查询和展示导出通过 `DomainFlowFields.ensureVelocityGrid` 准备库存与 MAC，不触发气腔初始化。
相占据由 `PhaseOccupancyTransport.ensure/publish` 集中准备与发布，库存编辑标记失效，捕获读取当前库存对应的占据；旧占据保留到气体来源交接结束。
实际库存仍由对应库存对象持有，处理器组织操作与提交顺序。跨相操作准备整笔热账，发布时将各物相增量合入当前账本；碰撞计划同时保存库存与热学代次，在首次写入前核对。

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
| 液体 | 单元／材料／开放分量的体积、整数质量、组成、内能与指示剂；微盒相占据；MAC 格面 SI 动量 | 速度、密度、PLIC、压力、曲率与显示点样本 |
| 化学 | 物种数量、组成、电荷、质量余量、指示剂与收支 | pH、颜色、主要组分 |
| 颗粒 | active/deposit 的 SVQ、质量、材料、组成、孔隙率、位置及接触状态 | 占容、床高、混合有效孔隙率与样本 |
| 连续固相 | coherent group 的 SVQ、质量、组成与内能 | 固相显示比例 |
| 气体 | 各组分标准体积 nanos、质量、物种、节点内能与收支 | 温度、压力、密度、孔流和临时压力自由度 |
| 热学 | 各实际载体总内能及 source/sink/work/roundoff | 温度、热容、LTE 与热浮力 |
| 有限热设备 | region 中的设备身份、质量、内能与代次 | 温度、接触位置与展示配置 |
| 世界与物品 | 区域 HEAD、WAL、已存盘物品状态 | 控制台和诊断 |

同一操作涉及多种物相时，物质、组成、动量和携带内能按同一计划交接。
化学绑定与恢复按实际库存检查材料物相、允许物种和指示剂；空 palette 可保留尚未使用的材料。公开组成赋值要求物种质量不超过载体质量，溶液初始化按实际总质量分配溶质与溶剂。
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

气体状态方程、Henry 关系和相平衡使用绝对压力。液体投影的机械压力采用相对于环境的表压；查询时按接口声明换算。
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

服务器 tick 依次刷新碰撞、热交换与实体输入，调度器选择 `NONE`、`MOTION` 或 `THERMAL`。力学优先于独立热轴；暂停、pacing 跳过和热轴均保留运动输入的一次使用规则。静水可继续导热、反应或相变；热量变化又可唤醒力学。玩家运行意图与外壳破损、区块卸载等可用性原因分别保存。

- **启动与依赖组装**
  - 主要文件：
    - [MmmRuntimeFactory](../../fabric/src/main/java/mmm/simulation/MmmRuntimeFactory.java)
    - [WorldStartupCoordinator](../../fabric/src/main/java/mmm/simulation/WorldStartupCoordinator.java)
  - 验证入口 / 参考：`region-state,lifecycle`；[运行时参考](runtime-and-lifecycle.md#startup)

- **tick、调度、实体和碰撞输入**
  - 主要文件：
    - [MinecraftSimulationLoop](../../fabric/src/main/java/mmm/simulation/MinecraftSimulationLoop.java)
    - [MinecraftRegionInputs](../../fabric/src/main/java/mmm/simulation/MinecraftRegionInputs.java)
    - [RegionTickUseCase](../../app/src/main/java/mmm/application/RegionTickUseCase.java)
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

1. 捕获库存、内能收支和待同步相变体积；从碰撞、固体占容与气腔建立同代输入。
2. 从单元整数质量构造 MAC 双控制体积惯性，以格面动量派生速度，执行多孔阻力、局部应变耗散和重力、浮力、张力等作用。
3. 以液体面质量、颗粒质量和两相体积散度共同求解压力与有限气腔响应，同时修正两相速度，再准备颗粒碰撞位置与实际整数占容。
4. 以共享通量在私有库存中执行一次完整时长的粗单元输运，用同一次实际整数质量通量推进 MAC 动量，联合提交液体、颗粒和气腔后继。
5. 在新位置上完成导热、液相反应、颗粒反应、固液与气液相变。相变共用操作预算，体积变化留待后续真实力学同步。
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

固定碰撞由 Minecraft `VoxelShape` 保守采样成每格 `2×2×2` 微盒掩码，压力开口、液体通量和 CCD 读取同一几何。活动颗粒、沉积和 coherent 固相共享固体骨架占容；液体可用容量为开放体积减去全部固体占容，孔隙水由普通液体库存持有。

压力系统使用同一面系数组装矩阵、预条件器和速度修正。
`PressureLinearSolver` 与 `PressurePreconditioner` 统一 PCG 及不完全 Cholesky 预条件，颗粒阻力按有限质量成对处理。
`PressureSparseMatrix` 一次组装液体、颗粒和气体共同 CSR，矩阵乘与 IC(0) 直接复用该算子；真空活动集沿当前自由行求解。
压力支撑分类直接读取同代气体几何已准备的分量孔容与实际干容，精确容量由共同库存准备一次。
真实真空腔与有库存气腔均保留自己的压力接触。静水参考只提供已知载荷，压力自由度由实际容量与气腔归属决定。
静水承载使用实际液体接触及统一的未解析薄干缝近似，接地传播与上覆压力使用同一判定。可解析真空继续保留其压力与运动响应。
已解析液气界面采用给定压力或有限气腔储存项，状态方程按液体压载区间给出有限体积响应，参加一次半隐式压力预测。低于几何体积解析尺度的干容保留气体库存、容量与内能，机械响应并入液体连续压力；气腔自由度按液体与颗粒算子的实际耦合准备。
活动颗粒体积分配到共同 MAC 面，液体读取剩余开口，两相真实有限质量与各自体积散度的转置共同构成压力算子。
压力同时更新液体与颗粒速度，接触和位置预测随后准备实际整数占容，真实终点孔容由共同有界输运结算。
`PressureSystem` 集中准备共同压力预测：两相先接收已知界面载荷及静水浮力，再执行有限质量阻力，随后求解共同残余压力。
已知气压与后续压力增量分别保存，真空下限逐气腔换算；观察字段发布两者之和。力积分按实际动能变化交接内能，输运按实际整数质量结算势能变化；颗粒按接受位置及实际速度结算机械能变化。气腔绝热功按同一机械连通体内的真实载体质量分配，液体内部压力功引起的局部温度分配采用体平均近似。液体的有符号机械热沿实际质量通量输运，颗粒热写入对应库存，均随候选一次提交。
闭壳耦合系统以主气腔压力为数值基准求解增量，液体、气体储容、颗粒反作用和真空下限统一平移，发布时恢复实际压力。均压静水保持零增量，避免相消假流及其后续容量功。
作用与反作用、外部约束冲量和压力功按实际写回结算；整数库存按最终位置独立核对真实容量，具体离散见[颗粒参考](granular-state.md#统一排水与压力)。
实体接触以实际面冲量和规定速度结算有符号外功，接触阻力热写入相邻实际液体；动量、热量与墙面停动反力共同准备并发布。
`MovingBodyCouplingValidation` 直接核对实体外功与实际 MAC 动能、库存内能增量，固定墙反冲计入同一冲量等式。

液体持久动量位于 MAC 格面，格面速度从同一双控制体积的实际质量与动量派生。
`CellMomentumTransport` 把本次完整输运的实际整数质量通量提升到格面，`MacMomentumAdvection` 采用正权迎风混合。
新湿面继承供体速度，局部混合损失转入内能，随质量搬运后与最终库存一次提交。
固定开口与联合容量约束液体运动；颗粒代表球通过共同接触响应处理法向、恢复和摩擦。

- **库存、PLIC 与速度工作区失效**
  - 主要文件：
    - [CellMacStage](../../solver/src/main/java/mmm/fluid/CellMacStage.java)：按物质数量、相几何与固相源状态准备惯性、墙面支撑和共同格面，内能更新复用原数量
    - [DomainFlowFields](../../solver/src/main/java/mmm/fluid/DomainFlowFields.java)
    - [CellLiquidGrid](../../solver/src/main/java/mmm/fluid/CellLiquidGrid.java)：单元库存派生索引与物性
  - 验证选择器 / 参考：`free-surface,materials`；[流体离散](fluid-solver.md#单元库存与-mac-动量)

- **压力与气腔耦合**
  - 主要文件：
    - [PressureSystem](../../solver/src/main/java/mmm/fluid/PressureSystem.java)：`solve` 组织已知载荷、阻力、共同压力基准、矩阵及速度修正，集中发布实际压力
    - [PressureLinearSolver](../../solver/src/main/java/mmm/fluid/PressureLinearSolver.java)
    - [PressureSparseMatrix](../../solver/src/main/java/mmm/fluid/PressureSparseMatrix.java)：液体、气体与颗粒共同 CSR 算子
    - [PressurePreconditioner](../../solver/src/main/java/mmm/fluid/PressurePreconditioner.java)
    - [PressureFaceGraph](../../solver/src/main/java/mmm/fluid/PressureFaceGraph.java)：共同压力支撑、接液面积与散度系数；气压在每次装配读取实际状态，液体输运直接使用固定孔口邻接
    - [PressureBoundaryView](../../solver/src/main/java/mmm/fluid/PressureBoundaryView.java)
  - 验证选择器 / 参考：`marker-pressure,gas-liquid`；[压力离散](fluid-solver.md#自由面与压力)

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
  - 验证入口 / 参考：`MacMomentumAdvectionValidation`、`free-surface`；[库存与动量](fluid-solver.md#单元库存与-mac-动量)

- **液固压力、阻力与整数占容**
  - 主要文件：
    - [GranularPressureCoupling](../../solver/src/main/java/mmm/fluid/GranularPressureCoupling.java)
    - [GranularImplicitDrag](../../solver/src/main/java/mmm/fluid/GranularImplicitDrag.java)
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
`PhaseQuantitySettlement` 先按固定开放分量结算共同连续体积通量，以同一库存容量图满足原供体库存、接收分量容量与有限气体正容量。满液分量新增干容由同代上风气体通量提供，已有自由界面与气体膨胀保持可动；相变减液形成的新气容随库存交接。
库存拆分直接传递整数质量与物种列，热参考能共用该列；接收格汇总后生成组成对象，气体来源与终点干容共用同次准备结果。
内部超出容量区间的体积沿已有通量路线线性交接，数值投影和精确提交复用连通树；剩余拥堵和气腔容量限制由按需展开的残量图处理。MAC 动量直接读取源速度和已接受的整数质量流，双单元质量准备一次，混合与转热共用非零质量流列表。
压力和 PLIC 给出面通量偏好，正常运动与颗粒排水共用双向邻接路线和一次容量投影。
材料随后共享已经接受的分量路线，分别拆分实际体积、整数质量、物种与内能；少量材料沿同一分量流动。
气相互补通量按已接受的液相 `QL` 计算双向 `Qt−QL`，覆盖容量修正引起的反向气流和原总通量为零的面。整数质量、动量和气体来源使用同次结算结果。
每个非空有限气腔在其真实支撑内保留正容量；容量约束与物质来源分别准备，气体来源沿实际干空间变化交接。
气体膨胀由实际液流推进，终态状态方程与热功读取真实容量。
每个机械子步执行一次完整时长的有限体积推进，按实际份额分配材料整数质量、物种与内能，活动域发布一次。
原地全量保留的库存行直接共享，整份跨格转移直接携带原质量、组成和内能，真实拆分才分配各项整数份额。
分量路线按原来源直接分组进入库存，目标按单元聚合并一次合并有序行，质量和物种共用同次分配权重。
MAC 动量输运读取同次结算的实际整数质量通量。

每个粗单元固定开放分量使用一套粗梯度 PLIC 平面，按最终库存液量确定偏移；持久微盒列保存共同重建输入，积分形状由 PLIC 工作区派生。
未解析液片与气隙共用单元尺度的形状近似：微量液体保留库存与携带能量，周围气体端口按单元近似保持通行；薄干隙传递粗尺度静水支撑。实际孔隙体积完整保留，已解析液塞与固定墙继续阻断气体。
气体来源按固定开放分量汇总，随实际宏观互补通量和本地保留空间推进，最终按真实分量容量交接。完整固定开口参与 MAC 来源输运，受限孔口的库存与焓由孔流网络结算。
新开放空腔使用显式真空来源。
同一粗单元固定开放分量内的干片合并气腔归属，跨完整固定开口的可解析粗分量气体共压，面内未解析液膜允许合并。
完整液塞和固定墙阻断气体，有限固定孔口保留孔流。

气腔以干微盒连通和规范 minimum-piece key 标识。`GasRuntimeGeometry` 保存同代几何、拓扑与库存容量；
未来编辑按最终相占据、单元库存和固体占容准备后继。
`GasComponentCapacity` 一次准备实际 `pore−Stock`，气片存在性、拓扑、来源交接和热力学容量共用该结果。未变库存行与孔容直接共享分量容量。
`GasComplementGeometry.capture` 顺序扫描微盒与面，复用未变化单元和相邻面的几何，生成不可变后继；同代几何复用拓扑和气腔容量。
气体库存、内能和旧控制体积沿同一份最终来源一次分配。
持久化保留相占据与微盒归属；环境交换记 source/sink，新开放真空空间保持明确归属。
真实运动改变容量时，对每个旧气源汇总实际体积形变，沿状态方程做一次绝热积分，再按同一库存份额分配内能；新气腔合并直接相加。接收腔的实际容量与继承控制容量共同决定旧气源形变，原有真空与新增孔容一并参加。表示交接和相变内部交换按固定内能结算。
气体来源与绝热控制体积分别交接：旧腔库存、内能及旧容量使用同一来源权重；局部液体移动形成的真空标签携带零库存。真正新增的零能量空间由实际净新增孔容给出。

气体采用集总气腔与有限孔流模型，按库存、容量和内能派生压力、温度及顺应性。
完整固定开口两侧的实际粗分量干容量超过共同解析量时采用低 Mach 共压近似，微量气体空间沿真实干口连接。
受限固定孔口保留有限流量，完整满液单元和固定墙保持阻断。
孔流每步冻结实际状态方程与热容导出的储容及正孔口导通率，整体后向 Euler 方程求共享通量。
供体联合限制所有出边的库存与可携焓，再统一分配整数物种并成对搬运同一能量通量。
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
    - [LiquidCellInventory](../../model/src/main/java/mmm/inventory/LiquidCellInventory.java)：`planRoutes` 分配物质，向动量算子交付实际数量并汇入机械热，统一生成私有后继与能量舍入账
    - [LiquidTransportRoutes](../../model/src/main/java/mmm/inventory/LiquidTransportRoutes.java)：共同容量投影交给库存的扁平路线列，供体确定材料身份，目标库存首次出现时建立键
    - [PhaseQuantitySettlement](../../solver/src/main/java/mmm/fluid/PhaseQuantitySettlement.java)：共同分量的双精度容量投影、同图精确端点结算与材料份额分配
    - [FluidThermalBinding](../../solver/src/main/java/mmm/fluid/FluidThermalBinding.java)：`liquidEnergyPartitioner` 按实际参考能与来源显热分份
    - [CellMomentumTransport](../../solver/src/main/java/mmm/fluid/CellMomentumTransport.java)
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
    - [GasTopologyCoordinator](../../solver/src/main/java/mmm/fluid/GasTopologyCoordinator.java)：按最终来源准备库存、内能和控制体积；`balanceMechanicalWork` 将各旧腔实际功交给同一机械连通体，统一预检后提交
    - [AirNetwork](../../model/src/main/java/mmm/fluid/air/AirNetwork.java)
    - [GasOrificeFlow](../../model/src/main/java/mmm/fluid/air/GasOrificeFlow.java)
    - [GasEnergyStore](../../model/src/main/java/mmm/thermal/GasEnergyStore.java)
    - [GasThermalModel](../../model/src/main/java/mmm/thermal/GasThermalModel.java)
  - 验证选择器 / 参考：`air-cavity,air-network,gas-liquid,thermal`；[气体参考](gas-state.md)

- **库存提交与来源校验**
  - 主要文件：
    - [LiquidCellInventory](../../model/src/main/java/mmm/inventory/LiquidCellInventory.java)
  - 验证入口 / 参考：`materials,containers`；[交接不变量](fluid-solver.md#物质交接不变量)

<a id="thermochemistry"></a>
## 热化学与物相转换

各实际载体保存总内能 `U`，温度与热容由现有分段状态函数反演。LTE 工作区用于接触、反应与相变候选；只读观察、属性计算与真实能量重分配有各自入口。导热成对搬运内能，恒温热浴的实际通量记外部 source/sink。反应按精确物种批次改变组成和参考能，相变按实际批次联合修改两相库存与内能。
温度、接触与载体能量准备按稳定的单元顺序遍历，同代热视图复用已准备的载体列表；库存增量索引提前筛去缺少反应物的区域。
导热接触与求解共享一次实际载体采集，终态与热账在首次写入前准备完成。
相变候选复用同代 LTE 的单元载体索引与参考能，固相能量目标在准备阶段匹配一次，提交直接写入已准备目标；每格存储余差在整批热账汇总一次。凝聚相变先按分段平衡曲线在非负压力上的完整温度范围筛选可能方向，候选再采样一次实际压力；恒定平衡温度直接比较温度。气腔相变共用一次库存、容量与内能派生的压力、温度和热容。
导热后的实际内能、温度与热容直接供同代相变扫描使用；反应改变库存或内能后按热状态代次重新准备。载体和热壁之间可解析的温差进入共同导热计算，低于导热分辨率时保留原始内能并省去接触图准备。
载体内能按共同温度一次分配，最大热容载体承接存储余量，实际写入的内能汇总与舍入账保持一致。热浮力读取实际库存温度，温度准备与可选黏性定义分别处理。

热化学顺序为导热 → 液相反应 → 颗粒反应 → 固液相变 → 气液相变，之后执行颗粒形态转换。凝固、熔化、汽化和凝结在固定区域体积下联合守恒两相内能，`PendingPhaseVolumeWork` 保存几何同步任务；下一次真实投影完成后清除。物品转移遇到待同步几何时，先执行仅含力学的私有候选，再准备物品事务。
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
    - [ThermalTransport](../../model/src/main/java/mmm/thermal/ThermalTransport.java)
    - [LiquidThermalConduction](../../solver/src/main/java/mmm/fluid/LiquidThermalConduction.java)
    - [ThermochemistryPipeline](../../solver/src/main/java/mmm/fluid/ThermochemistryPipeline.java)：`stepTransport` 与反应、相变顺序
  - 验证选择器 / 参考：`thermal`；[热学参考](thermal-state.md)

- **反应、析出与溶解**
  - 主要文件：
    - [ChemicalReactionStage](../../solver/src/main/java/mmm/fluid/ChemicalReactionStage.java)
    - [GranularReactionStage](../../solver/src/main/java/mmm/fluid/GranularReactionStage.java)：`precipitate`、`depositPrecipitate` 共用活动代表生成与组成合并，未解析析出物向合法床库存交接物质、反冲和内能。
    - [DomainCapabilities](../../solver/src/main/java/mmm/fluid/DomainCapabilities.java)：热能力、载体内能与联合相变交接
  - 验证选择器 / 参考：
    - `chemistry,granular,thermal`；[化学参考](chemistry-state.md)
    - [固液转移](granular-state.md#固液相转移)

- **相变批次、接触与未来气腔**
  - 主要文件：
    - [CondensedPhaseChangeStage](../../solver/src/main/java/mmm/fluid/CondensedPhaseChangeStage.java)
    - [GasLiquidPhaseChangeStage](../../solver/src/main/java/mmm/fluid/GasLiquidPhaseChangeStage.java)：`propose`、`closeChamber` 联合组分库存、气温与真实容量的分压响应；`admitCondensation` 共同分配批次和正容量，`step` 一次提交两相库存与内能。
    - [GasLiquidPhaseModel](../../model/src/main/java/mmm/thermal/GasLiquidPhaseModel.java)：饱和压力、解析斜率及潜热
    - [GasPhaseInterfaceResolver](../../solver/src/main/java/mmm/fluid/GasPhaseInterfaceResolver.java)
  - 验证选择器 / 参考：`thermal,gas-liquid`；[相变参考](thermal-state.md#相变)

- **沉积、起动、床层与液体位移**
  - 主要文件：
    - [GranularTransitionStage](../../solver/src/main/java/mmm/fluid/GranularTransitionStage.java)
    - [GranularInventory](../../model/src/main/java/mmm/granular/GranularInventory.java)
    - [GranularDepositDynamics](../../model/src/main/java/mmm/granular/GranularDepositDynamics.java)
    - [GranularLiquidDisplacement](../../solver/src/main/java/mmm/fluid/GranularLiquidDisplacement.java)
    - [GranularBedShear](../../solver/src/main/java/mmm/fluid/GranularBedShear.java)
  - 验证选择器 / 参考：`granular`；[床层参考](granular-state.md#deposit-松弛与局部唤醒)

<a id="candidate"></a>
<a id="persistence"></a>
## 存档和故障恢复

完整状态由 `FluidDomainStateMapper` 捕获与恢复。
气体初始化状态随同保存；尚未初始化气体的恢复只准备库存与速度，气腔库存由后续明确的物理准备创建。
正常候选共享已验证的液体不可变库存行与气相几何，独立复制 MAC 动量和气体网络；非液体内能由 `ThermalOwnerSnapshot` 携带。
调度进度、待同步相变几何、世界热交换面与最近真实压力投影随同交接。`FluidDomainState.Layout` 绑定网格、配置、载体布局、预算和注册表身份；区域 checkpoint 的持久字段另由存档模型定义。

`CpuFluidSolverBackend` 在私有域完成整个逻辑步；力学细化从原始请求重新恢复，保留总推进时间、外部输入和一次提交。`RegionApplicationService` 与仓库核对后端、区域身份、紧邻求解器代次和完整布局。CPU 可把已求解私有对象交接一次；其他后端提供完整不可变状态。候选失败保留原区域，成功后发布该候选的完整统计。

存盘以 `cell-mac` 格式保存单元液体记录、微盒相占据、规范分区的三轴格面动量、碰撞掩码和非液体库存。
液体记录直接携带组成和内能，热扩展保存非液体内能及总账。
双槽写入、回读并验证全部成员，再原子替换 `HEAD`。删除发布 `REMOVED` 终止代次后完成移除通知、注销与载荷隔离。捕获读取已提交的同代库存和气腔状态；冷恢复完整验证支撑、容量、物性与账本。

桶、发射器和世界方块操作使用 WAL 绑定区域与外部域的前后状态；恢复按 HEAD 与外部已存盘状态确定继续提交、清理或保留证据。`LOCKED` 对应无法解释的持久身份、格式或事务状态；数值暂停和性能暂停保留上一个完整物理状态。

- **完整候选与接管**
  - 主要文件：
    - [FluidDomainState](../../solver/src/main/java/mmm/fluid/FluidDomainState.java)
    - [FluidDomainStateMapper](../../solver/src/main/java/mmm/fluid/FluidDomainStateMapper.java)
    - [ThermalOwnerSnapshot](../../model/src/main/java/mmm/thermal/ThermalOwnerSnapshot.java)
    - [CpuFluidSolverBackend](../../solver/src/main/java/mmm/fluid/CpuFluidSolverBackend.java)
    - [RegionApplicationService](../../app/src/main/java/mmm/application/RegionApplicationService.java)
    - [FluidRegionRepository](../../fabric/src/main/java/mmm/simulation/FluidRegionRepository.java)
  - 验证选择器 / 参考：`lifecycle`；[后端字段](fluid-solver.md#后端候选接口)

- **区域 checkpoint、HEAD 与冷恢复**
  - 主要文件：
    - [RegionCheckpointAssembler](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointAssembler.java)
    - [RegionCheckpointRestorer](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointRestorer.java)
    - [RegionGenerationStore](../../fabric/src/main/java/mmm/simulation/persistence/RegionGenerationStore.java)
  - 验证选择器 / 参考：`region-state`；[存档参考](region-state.md)

- **玩家、发射器及世界事务**
  - 主要文件：
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

服务端按已提交状态生成有界快照，客户端按区域身份、网络代次和 sequence 接收并插值。移除消息使旧包失效。显示温度、压力、颜色与网格采用各自量化，库存、状态方程和物理计算读取原始数据。

Query API 在服务器线程读取已加载区域，单格观察保持只读；容器提交事件在区域与物品均已持久完成、WAL 清理后发布。未加载区域提供已知元数据及不可用原因。

- **快照、网络和接收顺序**
  - 主要文件：
    - [FluidSnapshotPublisher](../../fabric/src/main/java/mmm/simulation/FluidSnapshotPublisher.java)
    - [FluidProtocol](../../fabric/src/main/java/mmm/network/FluidProtocol.java)
    - [ClientRegionSnapshotStore](../../fabric/src/client/java/mmm/client/ClientRegionSnapshotStore.java)
  - 验证选择器 / 参考：`protocol`

- **液面、材料界面与颗粒渲染**
  - 主要文件：
    - [FluidSurfaceGeometry](../../fabric/src/client/java/mmm/client/FluidSurfaceGeometry.java)
    - [MaterialSurfaceGeometry](../../fabric/src/client/java/mmm/client/MaterialSurfaceGeometry.java)
    - [GranularSurfaceGeometry](../../fabric/src/client/java/mmm/client/GranularSurfaceGeometry.java)
  - 验证选择器 / 参考：`protocol,materials`；展示目标见[产品路线](../roadmap/product-roadmap.md#阶段-3展示几何契约与重建)

- **外部查询、Jade 与事件**
  - 主要文件：
    - [MmmFabricApi](../../fabric/src/main/java/mmm/api/fabric/MmmFabricApi.java)
    - [FabricFluidQueryApi](../../fabric/src/main/java/mmm/simulation/FabricFluidQueryApi.java)
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

- **创造来源与指示剂**
  - 主要文件：
    - [CreativeSourceCatalog](../../fabric/src/main/java/mmm/simulation/CreativeSourceCatalog.java)
    - [MinecraftIndicatorEditing](../../fabric/src/main/java/mmm/simulation/MinecraftIndicatorEditing.java)
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
  - 入口：`GranularImplicitDrag.responseDelta`、`PressureLinearSolver`、`PressurePreconditioner`、`GranularActiveContacts`、`GranularContactCompression`
  - 运行方法：沙床启动短重放、原慢步及完整生命周期

- **GPU 设备、资源、提交与回读**
  - 入口：
    - [VulkanDeviceContext](../../gpu-kernel/src/main/java/mmm/gpuprobe/VulkanDeviceContext.java)
    - [FlipGpuResources](../../gpu-kernel/src/main/java/mmm/gpuprobe/FlipGpuResources.java)
    - [GpuStepScheduler](../../gpu-kernel/src/main/java/mmm/gpuprobe/GpuStepScheduler.java)
    - [flip.comp](../../gpu-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/flip.comp)
    - [MmmGpuProbeHostIntegration](../../gpu-probe/src/main/java/mmm/gpuprobe/MmmGpuProbeHostIntegration.java)
  - 运行方法：[GPU 验证](../maintenance/validation.md#gpu)；[后端扩展要求](../roadmap/performance-and-backends.md)

<a id="diagnosis"></a>
### 按现象定位

| 现象 | 首先核对 | 继续跟到哪里 |
|---|---|---|
| 液面降低、水量异常 | `CellInventoryReader` 的实际体积与材料质量 | `LiquidCellInventory.Plan` 与 `MaterialTransferPlanner` 的 source/sink；再查快照量化 |
| 低速平流或热对流停滞 | 接受的分量 `QL`、原供体库存与材料份额 | `PhaseQuantitySettlement`、`LiquidCellInventory.planRoutes` 及携带内能 |
| 穿墙、小液滴或薄层异常 | `CollisionField` 与 `FlipCutCellGeometry` | `LiquidCollisionEdit`、共同面通量与压力支撑 |
| 冲击后动量或热量不闭合 | 最终 MAC 动量、墙面冲量与局部机械热 | `CellMacStage.stopWallMomentum`、`CellMomentumTransport` 及对应库存提交 |
| 气压或气腔归属异常 | `GasRuntimeGeometry` 的微盒、容量与节点归属 | `GasTopologyCoordinator` 的来源交接及 `GasThermalModel` 的库存／容量／U |
| 近空液体温度异常或压缩功去向异常 | 实际载体 M、U 及旧气腔做功 | `PressureSystem` 的实际动能变化、`CellMacStage.finishMechanicalHeat` 的质量势能通量及 `GasTopologyCoordinator.balanceMechanicalWork` 的机械连通体 |
| 等容交接后气体异常升温 | `GasTopologyCoordinator` 的旧腔控制体积与各接收气腔的净扩容 | 物质来源权重、局部释放的真空与绝热功结算 |
| 均压静水或微扰后出现气穴与温漂 | `PressureSystem` 的共同压力增量、`ConservativeLiquidFlux` 的实际干面接触 | `PhaseQuantitySettlement` 的内部液量下限，再跟到实际气容及压缩功 |
| 接触角铺展方向异常 | `SurfaceTensionFields` 的曲率及墙面法向通量 | 同一字段进入共同压力载荷的实际做功 |
| 温度、反应或相变无进展 | 实际组成、U、批次及候选不可执行原因 | `ThermalCaloricInversion`、`GasLiquidPhaseModel`、`GasLiquidPhaseChangeStage`、未来气腔与热账计划 |
| 颗粒占容、压力或沉积异常 | `GranularCouplingFields`、碰撞预览及整数分配 | `GranularPressureCoupling`、`GranularSettlementGeometryPlan`、`GranularBedShear` |
| 容器／区域锁定 | HEAD、WAL 与外部已存盘状态的分类 | `ContainerRecoveryCoordinator`、`OwnershipCoordinator`；[恢复决策](world-ownership-and-containers.md#恢复决策表) |
