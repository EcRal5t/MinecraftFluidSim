# 按需求找代码

从 [项目 README](../../README.md) 选择主题后，可以直接打开代码。本文用于进一步区分职责，以及从故障现象定位模块。物理和存档的详细规则放在对应专题中。

## 本页导航

- [物品、方块和命令](#gameplay)
- [启动、调度和控制器](#runtime)
- [物理计算](#physics)
- [存档、世界方块和容器](#persistence)
- [客户端、协议和外部接口](#client)
- [性能与 GPU](#performance)
- [按现象排查](#diagnosis)

<a id="gameplay"></a>
## 物品、方块和命令

### 物品、配方和说明文字

| 改动 | 首先打开 | 继续跟到哪里 |
|---|---|---|
| 注册方块、物品和创造栏顺序 | [MmmBlocks](../../fabric/src/main/java/cc/ecisr/mmm/block/MmmBlocks.java)、[MmmItems](../../fabric/src/main/java/cc/ecisr/mmm/item/MmmItems.java)、[MmmCreativeEntries](../../fabric/src/main/java/cc/ecisr/mmm/item/MmmCreativeEntries.java) | [配方](../../fabric/src/main/resources/data/mmm/recipe/)与[模型](../../fabric/src/main/resources/assets/mmm/models/) |
| 控制器空手右击、选择或暂停 | [FluidControllerBlock](../../fabric/src/main/java/cc/ecisr/mmm/block/FluidControllerBlock.java) | [MinecraftRegionActivation](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionActivation.java) |
| 指示剂涂写和擦除 | [ChemicalIndicatorItem](../../fabric/src/main/java/cc/ecisr/mmm/item/ChemicalIndicatorItem.java) | [MinecraftIndicatorEditing](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftIndicatorEditing.java) |
| 创造模式物质来源 | [CreativeSourceItem](../../fabric/src/main/java/cc/ecisr/mmm/item/CreativeSourceItem.java) | [CreativeSourceCatalog](../../fabric/src/main/java/cc/ecisr/mmm/simulation/CreativeSourceCatalog.java)、[CreativeSourceDescriptor](../../model/src/main/java/cc/ecisr/mmm/transfer/CreativeSourceDescriptor.java) |
| 快速入门界面 | [QuickStartGuideItem](../../fabric/src/main/java/cc/ecisr/mmm/item/QuickStartGuideItem.java) | [QuickStartScreen](../../fabric/src/client/java/cc/ecisr/mmm/client/QuickStartScreen.java) |
| 方块物品提示和翻译 | [MmmBlockItem](../../fabric/src/main/java/cc/ecisr/mmm/block/MmmBlockItem.java) | [语言文件](../../fabric/src/main/resources/assets/mmm/lang/) |

创造模式的物质描述符记录玩家想生成什么。实际使用时，`CreativeSourceCatalog` 按当前世界保存的材料定义计算体积、质量和内能。修改描述符时，同时检查实际生成物的计算路径。

方块和物品的注册、配方、模型与创造栏入口需要配套更新。文字和模型改动先编译资源；涉及控制器行为时运行 `lifecycle`，涉及物品库存时运行 `containers`。命令写法见 [验证指南](validation.md)。

### 命令和诊断输出

[MmmCommands](../../fabric/src/main/java/cc/ecisr/mmm/MmmCommands.java) 定义 Brigadier 命令树和审计包装，[MmmCommandHandlers](../../fabric/src/main/java/cc/ecisr/mmm/MmmCommandHandlers.java) 把业务结果转换为命令反馈。沿具体命令继续查：

- 管理员增减库存、运行或单步：[MinecraftRegionOperatorActions](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionOperatorActions.java)。
- 创建复现场景：[MinecraftDebugFixtures](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftDebugFixtures.java)。
- 列出、选择、保存或恢复区域：[MinecraftRegionAdministration](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionAdministration.java)。
- 状态、归属、化学和颗粒报告：[RegionDiagnosticsReport](../../fabric/src/main/java/cc/ecisr/mmm/simulation/RegionDiagnosticsReport.java)。
- 完整命令记录：[logging 包](../../fabric/src/main/java/cc/ecisr/mmm/logging/)。

新增命令时，把操作放进对应业务类，再在命令树注册。诊断报告读取当前区域状态，命令日志负责保存完整输出。审计修改运行 `audit`，业务修改运行对应领域测试。游戏里的命令见 [速查表](commands.md)。

<a id="runtime"></a>
## 启动、调度和控制器

| 需求 | 首先打开 | 详细说明 |
|---|---|---|
| 注册 Mod 事件或网络消息 | [MmmMod](../../fabric/src/main/java/cc/ecisr/mmm/MmmMod.java) | [启动顺序](../architecture/runtime-and-lifecycle.md#startup) |
| 添加运行时服务或修改世界启动 | [MmmRuntimeFactory](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MmmRuntimeFactory.java)、[WorldStartupCoordinator](../../fabric/src/main/java/cc/ecisr/mmm/simulation/WorldStartupCoordinator.java) | [启动顺序](../architecture/runtime-and-lifecycle.md#startup) |
| 改服务器 tick、停服或启动恢复 | [MinecraftSimulationLoop](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftSimulationLoop.java) | [区域计算](../architecture/runtime-and-lifecycle.md#tick) |
| 改单区域调度、性能暂停或热休眠 | [RegionTickUseCase](../../application/src/main/java/cc/ecisr/mmm/application/RegionTickUseCase.java)、[MinecraftRegionTick](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionTick.java) | [区域计算](../architecture/runtime-and-lifecycle.md#tick) |
| 改实体观测或世界碰撞输入 | [MinecraftRegionInputs](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionInputs.java)、[MinecraftCollisionWorldPort](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftCollisionWorldPort.java) | [实体与碰撞](../architecture/runtime-and-lifecycle.md#world-inputs) |
| 排查碰撞排液、气腔容量拒绝或候选提交 | [FluidRegionWorldInteraction](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidRegionWorldInteraction.java)、[FluidRegion](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidRegion.java)；回归选择器 `region-manager` | [实体与碰撞](../architecture/runtime-and-lifecycle.md#world-inputs) |
| 改区域数量、预算、重叠或选择 | [RegionCatalog](../../application/src/main/java/cc/ecisr/mmm/application/RegionCatalog.java)、[FluidRegionManager](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidRegionManager.java) | [区域登记与可用性](../architecture/runtime-and-lifecycle.md#admission) |
| 改控制台摘要、会话令牌或动作授权 | [ControllerQueryUseCase](../../application/src/main/java/cc/ecisr/mmm/application/ControllerQueryUseCase.java)、[RegionLifecycleUseCase](../../application/src/main/java/cc/ecisr/mmm/application/RegionLifecycleUseCase.java) | [控制器操作](../architecture/runtime-and-lifecycle.md#console) |
| 改清空、排气、释放或移除的提交步骤 | [MinecraftRegionLifecycle](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionLifecycle.java) | [世界与容器事务](../architecture/world-ownership-and-containers.md#区域生命周期) |

这些改动通常从 `lifecycle` 或 `region-manager` 验证开始。实体运动使用 `entity-fluid`，控制器扫描使用 `boundary`，消息和界面使用 `protocol`。涉及磁盘提交时再加 `region-state`。

<a id="physics"></a>
## 物理计算

[FluidDomain](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomain.java) 是运行中的物理状态对象。总计算顺序在 [FluidDomainStepCoordinator](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainStepCoordinator.java)，捕获和恢复状态在 [FluidDomainStateMapper](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainStateMapper.java)。从下表选择具体算法，避免从整个状态对象逐个方法翻找。

| 修改内容 | 首先打开 | 需要了解的数据和算法 |
|---|---|---|
| 力学计算顺序 | [MechanicalStepPipeline](../../solver/src/main/java/cc/ecisr/mmm/fluid/MechanicalStepPipeline.java)、[MechanicalStageAssembly](../../solver/src/main/java/cc/ecisr/mmm/fluid/MechanicalStageAssembly.java) | [流体求解器](../architecture/fluid-solver.md) |
| 压力矩阵、自由面、气液压力耦合 | [PressureProjectionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureProjectionStage.java)、[PressureSystem](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureSystem.java)、[PressureProjectionWorkspace](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureProjectionWorkspace.java)、[PressureFaceCoupling](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureFaceCoupling.java) | [自由面与压力](../architecture/fluid-solver.md#自由面与压力) |
| 粒子到网格、网格到粒子的速度传递 | [ParticleToGridStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/ParticleToGridStage.java)、[GridToParticleStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GridToParticleStage.java)、[MarkerTransferBudget](../../solver/src/main/java/cc/ecisr/mmm/fluid/MarkerTransferBudget.java)、[ResolvedMarkerVelocity](../../solver/src/main/java/cc/ecisr/mmm/fluid/ResolvedMarkerVelocity.java)、[FlipTransferScheme](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipTransferScheme.java) | [流体求解器](../architecture/fluid-solver.md#网格与-marker) |
| 压力修正的实际粒子冲量、功与回映射散度 | [PressureTransferExperiment](../../fabric/src/test/java/cc/ecisr/mmm/fluid/PressureTransferExperiment.java)；独立 `main`，对照满液与自由面并分解 PIC、FLIP 系数及外推贡献；`--rotation` 核对固定质量映射的刚体旋转、力矩与虚功 | [物理缺口](../roadmap/product-roadmap.md#物理缺口的依赖安排) |
| 实际质量的固定速度映射与压力离散核 | [MarkerVelocityTransfer](../../solver/src/main/java/cc/ecisr/mmm/fluid/MarkerVelocityTransfer.java)、[MarkerPressureOperator](../../solver/src/main/java/cc/ecisr/mmm/fluid/MarkerPressureOperator.java)；独立验证入口 [MarkerVelocityTransferValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/MarkerVelocityTransferValidation.java)、[MarkerPressureValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/MarkerPressureValidation.java) | [流体求解器](../architecture/fluid-solver.md#网格与-marker) |
| 自由面附近的不可压速度延拓 | [DivergenceFreeVelocityExtension](../../solver/src/main/java/cc/ecisr/mmm/fluid/DivergenceFreeVelocityExtension.java)、[FlipVelocityExtrapolator](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipVelocityExtrapolator.java)；选择器 `free-surface,reseeding,gas-liquid` | [网格与 marker](../architecture/fluid-solver.md#网格与-marker) |
| 粒子运动和碰撞 | [ParticleAdvectionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/ParticleAdvectionStage.java)、[DivergenceConformingVelocityField](../../solver/src/main/java/cc/ecisr/mmm/fluid/DivergenceConformingVelocityField.java)、[FlipParticleCollision](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipParticleCollision.java) | [固体与碰撞](../architecture/fluid-solver.md#固体与碰撞) |
| 切格内断开液体的库存归属、开放微面通量、化学体积尾数与容量抵扣 | [CellRemapMicroTopology](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellRemapMicroTopology.java)、[CellRemapPorts](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellRemapPorts.java)、[CellRemapChemicalSupport](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellRemapChemicalSupport.java)、[CellCarrierRemapPlan](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellCarrierRemapPlan.java)；[LiquidVolumeTransportValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidVolumeTransportValidation.java) 选择器 `components,chemical-tails` | [有限体积输运](../architecture/fluid-solver.md#单元有限体积输运) |
| 粒子聚集、局部容量估计、空间采样与重播种误差 | [MarkerDistributionExperiment](../../fabric/src/test/java/cc/ecisr/mmm/fluid/MarkerDistributionExperiment.java) 的 `free-analytic` / `dam` 对照（有限体积路径的 5,000 步溃坝同时纳入完整 `free-surface`）、[ClosedLiquidVolumeConstraint](../../solver/src/main/java/cc/ecisr/mmm/fluid/ClosedLiquidVolumeConstraint.java)、[FlipPressurePhaseGeometry](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipPressurePhaseGeometry.java) | [空间积分误差](../architecture/fluid-solver.md#空间积分误差与分布诊断)、[单元输运验收](../architecture/fluid-solver.md#单元有限体积输运) |
| 双路径交接、零通量保持与离线参考 | [CellCarrierRemapPlan](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellCarrierRemapPlan.java)、[BallisticLiquidRemap](../../solver/src/main/java/cc/ecisr/mmm/fluid/BallisticLiquidRemap.java)、[LiquidVolumeTransportValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidVolumeTransportValidation.java) 的 `handoff` / `continuity` 参数、[LiquidHandoffContinuityValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidHandoffContinuityValidation.java)（`--join` 单独运行实际连接与持续主分量观察）；逐步矩误差读 `StepStats.liquidTransport`；[探索索引](../archive/free-surface-unification-research.md) | [有限体积输运](../architecture/fluid-solver.md#单元有限体积输运) |
| 单元液体通量、容量与积分点重建 | [CellLiquidVolumeTransport](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellLiquidVolumeTransport.java)、[ConservativeLiquidFlux](../../solver/src/main/java/cc/ecisr/mmm/fluid/ConservativeLiquidFlux.java)、[CellCarrierRemapPlan](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellCarrierRemapPlan.java)、[CellRemapMarkerColumns](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellRemapMarkerColumns.java)、[CellRemapInventoryAudit](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellRemapInventoryAudit.java)、[LiquidCellQuadrature](../../solver/src/main/java/cc/ecisr/mmm/fluid/LiquidCellQuadrature.java)；选择器 `free-surface,cut-cell,thermal,chemistry,reseeding` | [有限体积输运](../architecture/fluid-solver.md#单元有限体积输运) |
| 单格液体初始化与完整库存替换预检 | [CellVolumeReplacementPlan](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellVolumeReplacementPlan.java)、[CarrierMutationCoordinator](../../solver/src/main/java/cc/ecisr/mmm/fluid/CarrierMutationCoordinator.java)、[ChemicalParcelStore](../../model/src/main/java/cc/ecisr/mmm/chemistry/ChemicalParcelStore.java) 的 `AuditExchange`、[ThermalDomainState](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalDomainState.java) 的 `LiquidReplacementPlan`；[CarrierMutationValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/CarrierMutationValidation.java) 的 `cellVolumeReplacementPreflightsAllOwners` | [有限体积输运](../architecture/fluid-solver.md#单元有限体积输运) |
| 输运中的材料质量、整数 species 与热量范围 | [CellRemapMassAllocation](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellRemapMassAllocation.java)、[CellSpeciesInventory](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellSpeciesInventory.java)、[ExactVolumeAllocation](../../solver/src/main/java/cc/ecisr/mmm/fluid/ExactVolumeAllocation.java)、[CellRemapHeatBalance](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellRemapHeatBalance.java) | [有限体积输运](../architecture/fluid-solver.md#单元有限体积输运) |
| 颗粒路径积分排水与固体表示切换 | [SweptGranularVolumeGradient](../../solver/src/main/java/cc/ecisr/mmm/fluid/SweptGranularVolumeGradient.java)、[GranularLiquidDisplacement](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularLiquidDisplacement.java)、[GranularPressureCoupling](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularPressureCoupling.java)；选择器 `granular,cut-cell` | [颗粒压力耦合](../architecture/granular-state.md#统一排水与压力) |
| 多实体局部粒子索引 | [MarkerNeighborhoodIndex](../../solver/src/main/java/cc/ecisr/mmm/fluid/MarkerNeighborhoodIndex.java)、[MovingBodyNeighborhoodValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/MovingBodyNeighborhoodValidation.java)；选择器 `entity-fluid` | [实体与碰撞](../architecture/runtime-and-lifecycle.md#world-inputs) |
| 表面张力和接触角 | [SurfaceTensionWorkspace](../../solver/src/main/java/cc/ecisr/mmm/fluid/SurfaceTensionWorkspace.java)、[BodyForceStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/BodyForceStage.java)、[FlipCutCellGeometry](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipCutCellGeometry.java) | [材料与界面](../architecture/material-state.md) |
| 液气曲率、Laplace 压力与液颈分离 | [HeightFunctionCurvature](../../solver/src/main/java/cc/ecisr/mmm/fluid/HeightFunctionCurvature.java)、[FluidDomainMechanicalFields](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainMechanicalFields.java)、[CapillaryNeckValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/CapillaryNeckValidation.java) | `M4SurfaceTensionValidation --curvature`：平面、部分格液层、球面与压力步；完整 `surface-tension` 增加固定围墙液颈的空间/时间对照 |
| 粒子拆分、合并和附属数据搬移 | [FlipParticleReseeder](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipParticleReseeder.java)、[MomentPreservingMergePlan](../../solver/src/main/java/cc/ecisr/mmm/fluid/MomentPreservingMergePlan.java)、[QuadratureMomentFit](../../solver/src/main/java/cc/ecisr/mmm/fluid/QuadratureMomentFit.java)、[CarrierMutationCoordinator](../../solver/src/main/java/cc/ecisr/mmm/fluid/CarrierMutationCoordinator.java) | [空间积分误差](../architecture/fluid-solver.md#空间积分误差与分布诊断) |
| 材料注册、质量和单位换算 | [FluidMaterialRegistry](../../model/src/main/java/cc/ecisr/mmm/fluid/FluidMaterialRegistry.java)、[SolverUnitScale](../../model/src/main/java/cc/ecisr/mmm/units/SolverUnitScale.java)、[ProportionalDoubleSplit](../../model/src/main/java/cc/ecisr/mmm/units/ProportionalDoubleSplit.java)；全域体积汇总见 [DomainMetrics](../../solver/src/main/java/cc/ecisr/mmm/fluid/DomainMetrics.java) 和 [SignedDyadic](../../model/src/main/java/cc/ecisr/mmm/units/SignedDyadic.java) | [材料状态](../architecture/material-state.md) |
| 气腔扫描、气体流动和状态方程 | [AirCavityScanner](../../solver/src/main/java/cc/ecisr/mmm/fluid/AirCavityScanner.java)、[AirNetwork](../../model/src/main/java/cc/ecisr/mmm/fluid/air/AirNetwork.java)、[GasThermalModel](../../model/src/main/java/cc/ecisr/mmm/thermal/GasThermalModel.java)、[GasTransferSearch](../../model/src/main/java/cc/ecisr/mmm/thermal/GasTransferSearch.java) | [气体状态](../architecture/gas-state.md) |
| 区域气体摘要、精确审计与恢复前校验 | [GasAuditLedger](../../model/src/main/java/cc/ecisr/mmm/fluid/air/GasAuditLedger.java)、[GasRegionAudit](../../model/src/main/java/cc/ecisr/mmm/fluid/air/GasRegionAudit.java)、[AirNetwork](../../model/src/main/java/cc/ecisr/mmm/fluid/air/AirNetwork.java) 的 `sealedInventory` / `restoreNodes`；`AirNetworkValidation.validateRegionalComponentAggregation` | [权威数据](../architecture/gas-state.md#权威数据) |
| 气液相变提交前的库存与累计账本校验 | [AirNetwork](../../model/src/main/java/cc/ecisr/mmm/fluid/air/AirNetwork.java) 的 `PhaseMutationPlan`、[GasLiquidPhaseChangeStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GasLiquidPhaseChangeStage.java)；数量容量、热能列与计划 revision 检查见 `ThermalGasValidationScenarios`、`AirNetworkValidation` | [相变](../architecture/thermal-state.md#相变) |
| 化学反应、组分输运和质量转移动量 | [ChemicalReactionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/ChemicalReactionStage.java)、[CarrierMassMomentum](../../solver/src/main/java/cc/ecisr/mmm/fluid/CarrierMassMomentum.java)、[ChemicalParcelStore](../../model/src/main/java/cc/ecisr/mmm/chemistry/ChemicalParcelStore.java)、[ChemicalMixturePool](../../model/src/main/java/cc/ecisr/mmm/chemistry/ChemicalMixturePool.java) | [化学状态](../architecture/chemistry-state.md) |
| 颗粒球体连续碰撞与接触冲量 | [GranularCollisionGeometry](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularCollisionGeometry.java)、[GranularSphereCollision](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularSphereCollision.java)、[FlipGranularCollision](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipGranularCollision.java)、[GranularCarrierValidation](../../solver/src/test/java/cc/ecisr/mmm/fluid/GranularCarrierValidation.java) | [颗粒状态](../architecture/granular-state.md) |
| 颗粒运动、反应和沉积 | [FlipGranularCollision](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipGranularCollision.java)、[GranularMechanicalStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularMechanicalStage.java)、[GranularReactionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularReactionStage.java)、[JointPhaseCapacity](../../solver/src/main/java/cc/ecisr/mmm/fluid/JointPhaseCapacity.java)、[GranularReactionMomentum](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularReactionMomentum.java)、[GranularTransitionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularTransitionStage.java) | [颗粒状态](../architecture/granular-state.md) |
| 沉积库存、混合孔隙率与床体体积 | [GranularDepositStore](../../model/src/main/java/cc/ecisr/mmm/granular/GranularDepositStore.java)、[SolidVolume](../../model/src/main/java/cc/ecisr/mmm/granular/SolidVolume.java) | [数量、布局与容量](../architecture/granular-state.md#数量布局与容量) |
| 沉积路径、多单元共同床转换与固定床反冲 | [GranularDepositDynamics](../../model/src/main/java/cc/ecisr/mmm/granular/GranularDepositDynamics.java)、[GranularSettlementGeometryPlan](../../model/src/main/java/cc/ecisr/mmm/granular/GranularSettlementGeometryPlan.java)、[GranularBedAdmission](../../model/src/main/java/cc/ecisr/mmm/granular/GranularBedAdmission.java)、[GranularSphereGeometry](../../model/src/main/java/cc/ecisr/mmm/granular/GranularSphereGeometry.java)、[FluidDomainStepCoordinator](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainStepCoordinator.java)、[GranularCollisionGeometry](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularCollisionGeometry.java)；竖直路径、水平连通、空腔与最终床体检查见 [GranularCarrierValidation](../../solver/src/test/java/cc/ecisr/mmm/fluid/GranularCarrierValidation.java) 的 `depositPathsRespectSuspendedBeds` / `relaxationRequiresAnActualHorizontalPath` / `cutBedGeometryMatchesFinalPlan` | [颗粒状态](../architecture/granular-state.md#deposit-松弛与局部唤醒) |
| 床转换重力势能预检 | [GranularBedPotential](../../model/src/main/java/cc/ecisr/mmm/granular/GranularBedPotential.java)、[GranularInventory](../../model/src/main/java/cc/ecisr/mmm/granular/GranularInventory.java)、[SignedDyadic](../../model/src/main/java/cc/ecisr/mmm/units/SignedDyadic.java) | [颗粒状态](../architecture/granular-state.md#deposit-松弛与局部唤醒) |
| 颗粒合并的角动量与质量一阶矩 | [GranularMergeKinematics](../../model/src/main/java/cc/ecisr/mmm/granular/GranularMergeKinematics.java)、[GranularParcelStore](../../model/src/main/java/cc/ecisr/mmm/granular/GranularParcelStore.java)、[GranularInventory](../../model/src/main/java/cc/ecisr/mmm/granular/GranularInventory.java)、[GranularReactionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularReactionStage.java)；选择器 `granular` | [颗粒状态](../architecture/granular-state.md#独立保存的数据) |
| 沙床被水流冲起、薄床阻力与局部休眠 | [GranularBedShear](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularBedShear.java)、[GranularDepositDynamics](../../model/src/main/java/cc/ecisr/mmm/granular/GranularDepositDynamics.java)、[BodyForceStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/BodyForceStage.java) | [水流起动](../architecture/granular-state.md#水流起动与床面输运) |
| 颗粒阻力、压力响应与步长细化 | [GranularImplicitDrag](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularImplicitDrag.java)、[GranularCoupledPressureSolver](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularCoupledPressureSolver.java)、[GranularPressurePreconditioner](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularPressurePreconditioner.java)、[GranularMechanicalStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularMechanicalStage.java)、[GranularPressureCoupling](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularPressureCoupling.java)、[GranularQuantizationError](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularQuantizationError.java)、[CpuFluidSolverBackend](../../solver/src/main/java/cc/ecisr/mmm/fluid/CpuFluidSolverBackend.java) | [颗粒压力耦合](../architecture/granular-state.md#统一排水与压力)、[CPU 后端](../architecture/fluid-solver.md#后端候选接口) |
| Newtonian 黏性应力、谱界与壁面冲量 | [LiquidViscosityStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/LiquidViscosityStage.java)、[ViscousMacDiscretization](../../solver/src/main/java/cc/ecisr/mmm/fluid/ViscousMacDiscretization.java)、[ViscousStressOperator](../../solver/src/main/java/cc/ecisr/mmm/fluid/ViscousStressOperator.java) | [液体黏性应力](../architecture/fluid-solver.md#液体黏性应力) |
| 液体物性定义绑定 | [LiquidMaterialBinding](../../solver/src/main/java/cc/ecisr/mmm/fluid/LiquidMaterialBinding.java) | [材料状态](../architecture/material-state.md) |
| 液体组成密度、热浮力、温度与黏度的按格派生 | [LiquidCarrierProperties](../../solver/src/main/java/cc/ecisr/mmm/fluid/LiquidCarrierProperties.java)、[LiquidThermalSupport](../../solver/src/main/java/cc/ecisr/mmm/fluid/LiquidThermalSupport.java)、[FluidDomainMechanicalFields](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainMechanicalFields.java)、[MaterialRegistry](../../solver/src/main/java/cc/ecisr/mmm/fluid/MaterialRegistry.java) | [组成密度](../architecture/material-state.md#求解器边界)、[热浮力](../architecture/thermal-state.md#热浮力)、[载体属性工作区](../architecture/granular-state.md#载体属性工作区)、[黏度求值](../architecture/material-state.md#黏度求值) |
| 盐水、温差分层与自然对流增长率 | [LiquidStratificationValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidStratificationValidation.java)、[LiquidConvectionExperiment](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidConvectionExperiment.java)；选择器 `thermal` | [热浮力](../architecture/thermal-state.md#热浮力)、[线性对流有效域](../architecture/thermal-state.md#自然对流的线性有效域) |
| 传热、反应热和相变顺序 | [ThermochemistryPipeline](../../solver/src/main/java/cc/ecisr/mmm/fluid/ThermochemistryPipeline.java)、[ThermalDomainState](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalDomainState.java) | [热学状态](../architecture/thermal-state.md) |
| 零导热平流、连续温度观察与导热单调性 | [ThermalSpatialObservation](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalSpatialObservation.java)、[LiquidThermalSupport](../../solver/src/main/java/cc/ecisr/mmm/fluid/LiquidThermalSupport.java)、[LiquidThermalAdvectionValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidThermalAdvectionValidation.java)；选择器 `thermal` | [空间温度观察](../architecture/thermal-state.md#批量格温度观察)、[热输运](../architecture/thermal-state.md#热输运) |
| 有限热接触、异质热容与载体有效性 | [ThermalCaloricInversion](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalCaloricInversion.java) 共享精确分段反演与质量缩放后的能量求值；[ThermalContactExchange](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalContactExchange.java)、[ThermalCarrierTransport](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalCarrierTransport.java)、[ThermalTransport](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalTransport.java)、[ThermalContactValidation](../../model/src/test/java/cc/ecisr/mmm/thermal/ThermalContactValidation.java)；选择器 `thermal` | [热输运](../architecture/thermal-state.md#热输运)、[状态函数](../architecture/thermal-state.md#状态函数) |
| 逐载体原始内能分配、相变热计划与舍入温度区间 | [ThermalCellEnergyAssignment](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalCellEnergyAssignment.java)、[ThermalCellOperations](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalCellOperations.java)、[ThermalDomainState](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalDomainState.java) 的 `PhaseCellEnergyPlan`、[ThermalCaloricBounds](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalCaloricBounds.java) 的 `assignedTemperatureBound`；`ThermalGasValidationScenarios.phaseGasCapacityIsAtomicAndRetainsSmallerBatch` | [状态函数](../architecture/thermal-state.md#状态函数)、[相变](../architecture/thermal-state.md#相变) |
| 满液体域的连续有限导热与积分点留距 | [LiquidThermalConduction](../../solver/src/main/java/cc/ecisr/mmm/fluid/LiquidThermalConduction.java)、[ThermalCarrierTransport](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalCarrierTransport.java)、[ConservativeMarkerSampling](../../solver/src/main/java/cc/ecisr/mmm/fluid/ConservativeMarkerSampling.java)；解析模态在 [LiquidThermalAdvectionValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidThermalAdvectionValidation.java)，选择器 `thermal,free-surface,cut-cell,reseeding` | [有限载体导热](../architecture/thermal-state.md#满液体域的有限载体导热)、[网格与 marker](../architecture/fluid-solver.md#网格与-marker) |
| 固液或气液相变 | [PhaseChangeStageSupport](../../solver/src/main/java/cc/ecisr/mmm/fluid/PhaseChangeStageSupport.java)、[CondensedPhaseChangeStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/CondensedPhaseChangeStage.java)、[GasLiquidPhaseChangeStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GasLiquidPhaseChangeStage.java)、[GasPhaseInterfaceResolver](../../solver/src/main/java/cc/ecisr/mmm/fluid/GasPhaseInterfaceResolver.java) | [相变](../architecture/thermal-state.md#相变) |
| 固液整数批次空档与温差区间排除 | [CondensedPhaseBatchBounds](../../solver/src/main/java/cc/ecisr/mmm/fluid/CondensedPhaseBatchBounds.java)、[CondensedPhaseChangeStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/CondensedPhaseChangeStage.java)；`ThermalPhaseValidationScenarios.coherentPhaseInventoryAndExactBatchPlanning` | [固液](../architecture/thermal-state.md#固液) |
| 气液整数候选与区间裁剪 | [GasPhaseBatchSearch](../../solver/src/main/java/cc/ecisr/mmm/fluid/GasPhaseBatchSearch.java) 搜索间断的整数可行集合；[GasBoilingBatchBounds](../../solver/src/main/java/cc/ecisr/mmm/fluid/GasBoilingBatchBounds.java)、[GasCondensingBatchBounds](../../solver/src/main/java/cc/ecisr/mmm/fluid/GasCondensingBatchBounds.java) 构造实际候选库存范围；[ThermalCaloricBounds](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalCaloricBounds.java) 提供共享热量及温度包络 | [相变](../architecture/thermal-state.md#气液) |
| 有限热容量设备 | [FiniteThermalDeviceInventory](../../model/src/main/java/cc/ecisr/mmm/thermal/FiniteThermalDeviceInventory.java)、[FiniteThermalExchange](../../model/src/main/java/cc/ecisr/mmm/thermal/FiniteThermalExchange.java) | [有限热设备](../architecture/thermal-state.md#有限热容量设备) |
| 计算后端、完整输入和结果提交 | [FluidSolverBackend](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidSolverBackend.java)、[FluidDomainState](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainState.java)、[RegionApplicationService](../../application/src/main/java/cc/ecisr/mmm/application/RegionApplicationService.java) | [后端提交过程](../architecture/runtime-and-lifecycle.md#candidate) |

每份专题开头列出了适用测试。涉及多个载体时，检查材料、化学、颗粒、气体和内能数据是否一起转移。

<a id="persistence"></a>
## 存档、世界方块和容器

| 修改内容 | 首先打开 | 详细说明 |
|---|---|---|
| 世界配置和材料定义加载 | [FluidServerConfig](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidServerConfig.java)、[RegistryBundleLoader](../../fabric/src/main/java/cc/ecisr/mmm/simulation/RegistryBundleLoader.java)、[CommittedWorldRegistries](../../fabric/src/main/java/cc/ecisr/mmm/simulation/CommittedWorldRegistries.java) | [世界能力包](../architecture/region-state.md#世界能力包) |
| 世界能力包写入和启动恢复 | [WorldCapabilityBundleStore](../../fabric/src/main/java/cc/ecisr/mmm/simulation/persistence/WorldCapabilityBundleStore.java)、[WorldCapabilityStartup](../../fabric/src/main/java/cc/ecisr/mmm/simulation/persistence/WorldCapabilityStartup.java) | [区域存档](../architecture/region-state.md) |
| 区域状态捕获和恢复 | [MinecraftRegionCheckpointSource](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionCheckpointSource.java)、[RegionCheckpointAssembler](../../fabric/src/main/java/cc/ecisr/mmm/simulation/persistence/RegionCheckpointAssembler.java)、[RegionCheckpointRestorer](../../fabric/src/main/java/cc/ecisr/mmm/simulation/persistence/RegionCheckpointRestorer.java) | [区域存档](../architecture/region-state.md) |
| 双槽存档、HEAD 和删除标记 | [RegionGenerationStore](../../fabric/src/main/java/cc/ecisr/mmm/simulation/persistence/RegionGenerationStore.java)、[RemovedQuarantineStore](../../fabric/src/main/java/cc/ecisr/mmm/simulation/persistence/RemovedQuarantineStore.java) | [提交模型](../architecture/region-state.md#提交模型) |
| 接管或归还世界方块 | [OwnershipTransactionCoordinator](../../fabric/src/main/java/cc/ecisr/mmm/simulation/OwnershipTransactionCoordinator.java)、[OwnershipCoordinator](../../fabric/src/main/java/cc/ecisr/mmm/simulation/ownership/OwnershipCoordinator.java) | [世界归属](../architecture/world-ownership-and-containers.md) |
| 开发场景的接管备份 | [TakeoverTransaction](../../fabric/src/main/java/cc/ecisr/mmm/simulation/TakeoverTransaction.java)、[TakeoverBackupStore](../../fabric/src/main/java/cc/ecisr/mmm/simulation/TakeoverBackupStore.java) | [世界归属](../architecture/world-ownership-and-containers.md) |
| 玩家和发射器转移物质 | [PlayerContainerTransferAdapter](../../fabric/src/main/java/cc/ecisr/mmm/simulation/PlayerContainerTransferAdapter.java)、[DispenserContainerTransferAdapter](../../fabric/src/main/java/cc/ecisr/mmm/simulation/DispenserContainerTransferAdapter.java)、[MinecraftContainerTransferPlan](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftContainerTransferPlan.java) | [容器事务](../architecture/world-ownership-and-containers.md#玩家与发射器路由) |
| 物品库存编码 | [ContainerItemMapper](../../fabric/src/main/java/cc/ecisr/mmm/item/ContainerItemMapper.java)、[ContainerItemStacks](../../fabric/src/main/java/cc/ecisr/mmm/item/ContainerItemStacks.java) | [容器数据](../architecture/world-ownership-and-containers.md) |
| 容器热量拆分与区域结算 | [LiquidContainerThermodynamics](../../model/src/main/java/cc/ecisr/mmm/transfer/LiquidContainerThermodynamics.java)、[ThermalContents](../../model/src/main/java/cc/ecisr/mmm/transfer/ThermalContents.java)、[MinecraftContainerTransferOperations](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftContainerTransferOperations.java) | [持久化与容器](../architecture/thermal-state.md#持久化与容器) |
| 玩家物品存盘确认与静默保存失败 | [MinecraftContainerActorAdapter](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftContainerActorAdapter.java)、[PlayerInventorySaveVerifier](../../fabric/src/main/java/cc/ecisr/mmm/simulation/persistence/PlayerInventorySaveVerifier.java) | [跨持久域 WAL](../architecture/world-ownership-and-containers.md#跨持久域-wal) |
| 事务日志和故障恢复 | [ContainerTransferCoordinator](../../application/src/main/java/cc/ecisr/mmm/application/ContainerTransferCoordinator.java)、[ContainerRecoveryCoordinator](../../application/src/main/java/cc/ecisr/mmm/application/ContainerRecoveryCoordinator.java)、[CrossDomainRecovery](../../model/src/main/java/cc/ecisr/mmm/recovery/CrossDomainRecovery.java) | [恢复决策表](../architecture/world-ownership-and-containers.md#恢复决策表) |
| 转移前同步上一阶段的相变几何 | [MinecraftPendingPhaseWorkBarrier](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftPendingPhaseWorkBarrier.java) | [相变几何同步](../architecture/runtime-and-lifecycle.md#phase-work) |

存档格式和能力包使用 `region-state`，世界归属使用 `ownership`，容器使用 `containers`，开发接管使用 `takeover`。跨区域和物品的改动通常需要同时运行 `region-state,containers`。

<a id="client"></a>
## 客户端、协议和外部接口

[FluidSnapshotPublisher](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidSnapshotPublisher.java) 从服务端状态生成展示快照，[FluidProtocol](../../fabric/src/main/java/cc/ecisr/mmm/network/FluidProtocol.java) 定义消息，[ClientRegionSnapshotStore](../../fabric/src/client/java/cc/ecisr/mmm/client/ClientRegionSnapshotStore.java) 管理客户端的区域代次、快照顺序和插值帧。

| 需求 | 首先打开 |
|---|---|
| 液面、材料界面或颗粒几何 | [FluidSurfaceGeometry](../../fabric/src/client/java/cc/ecisr/mmm/client/FluidSurfaceGeometry.java)、[MaterialSurfaceGeometry](../../fabric/src/client/java/cc/ecisr/mmm/client/MaterialSurfaceGeometry.java)、[GranularSurfaceGeometry](../../fabric/src/client/java/cc/ecisr/mmm/client/GranularSurfaceGeometry.java) |
| GPU 顶点缓冲或绘制顺序 | [FluidRenderBackend](../../fabric/src/client/java/cc/ecisr/mmm/client/FluidRenderBackend.java)、[ClientFluidVolumeRenderer](../../fabric/src/client/java/cc/ecisr/mmm/client/ClientFluidVolumeRenderer.java) |
| 协议协商或玩家连接状态 | [ProtocolSessions](../../fabric/src/main/java/cc/ecisr/mmm/network/ProtocolSessions.java)、[FluidProtocolServer](../../fabric/src/main/java/cc/ecisr/mmm/network/FluidProtocolServer.java) |
| 控制器界面和消息 | [ControllerConsoleScreen](../../fabric/src/client/java/cc/ecisr/mmm/client/ControllerConsoleScreen.java)、[MinecraftControllerConsole](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftControllerConsole.java)、[network 包](../../fabric/src/main/java/cc/ecisr/mmm/network/) |
| 物质场探针 | [FieldProbeItem](../../fabric/src/main/java/cc/ecisr/mmm/item/FieldProbeItem.java)、[ShellFluidQuery](../../fabric/src/main/java/cc/ecisr/mmm/simulation/ShellFluidQuery.java)、[FluidWorldTargetResolver](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidWorldTargetResolver.java) |
| 恒温源调节界面 | [ThermalReservoirScreen](../../fabric/src/client/java/cc/ecisr/mmm/client/ThermalReservoirScreen.java)、[ThermalReservoirUseCase](../../application/src/main/java/cc/ecisr/mmm/application/ThermalReservoirUseCase.java) |
| 外部查询和提交通知 | [MmmFabricApi](../../fabric/src/main/java/cc/ecisr/mmm/api/fabric/MmmFabricApi.java)、[FabricFluidQueryApi](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FabricFluidQueryApi.java)、[CommittedEventHub](../../fabric/src/main/java/cc/ecisr/mmm/simulation/CommittedEventHub.java) |
| Jade 服务端和客户端适配 | [服务端适配](../../fabric/src/main/java/cc/ecisr/mmm/compat/jade/)、[客户端适配](../../fabric/src/client/java/cc/ecisr/mmm/compat/jade/) |

协议和查询修改先运行 `protocol`。控制台授权追加 `lifecycle`，容器提交通知追加 `containers`，Jade 或客户端类型变化需要编译 main 和 client。界面读取已提交的状态；涉及库存的修改由服务端业务模块执行。

快照表面复用与化学颜色更新由 [ClientSurfaceValidation](../../fabric/src/test/java/cc/ecisr/mmm/client/ClientSurfaceValidation.java) 覆盖，随 `protocol` 运行。

接口参数、单位和线程要求见 [外部 API](../architecture/external-api.md)。液面重建算法见 [流体求解器](../architecture/fluid-solver.md)。

<a id="performance"></a>
## 性能与 GPU

短时离线分阶段测量入口是 [ProductionStepBenchmark](../../fabric/src/test/java/cc/ecisr/mmm/simulation/ProductionStepBenchmark.java)，任务 `:fabric:runStepBenchmark`；输出状态捕获、后端计算、提交耗时与线程分配量。

颗粒活动、沉积扰动、持续反应与析出、独立热轴及展示阶段测量见 [CompositeStepBenchmark](../../fabric/src/test/java/cc/ecisr/mmm/simulation/CompositeStepBenchmark.java)，任务 `:fabric:runCompositeBenchmark`。场景范围和参数见 [性能验证](validation.md#performance)。

CPU 性能问题先从 [ProductionFluidReplayValidation](../../fabric/src/test/java/cc/ecisr/mmm/simulation/ProductionFluidReplayValidation.java) 的 `defaultLiveStepsRemainBounded` 复现，再结合 [jfr_hotspots.py](../../pytool/jfr_hotspots.py) 定位耗时。测试名称为 `production-live-budget`，指标见 [验证指南](validation.md#performance)。

独立数值计算的范围批次与异常等待位于 [CpuWorkBatch](../../model/src/main/java/cc/ecisr/mmm/compute/CpuWorkBatch.java)。库存几何与速度重建看 [ParticleToGridStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/ParticleToGridStage.java)，三轴 P2G 看 [MarkerGridBuilder](../../solver/src/main/java/cc/ecisr/mmm/fluid/MarkerGridBuilder.java)，单元分组与接收格归并看 [CellCarrierRemapPlan](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellCarrierRemapPlan.java)，热接触区间看 [ThermalTransport](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalTransport.java) 和 [ThermalCarrierContacts](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalCarrierContacts.java)。

化学状态交接的捕获、规范排序和恢复复用位于 [FlipChemicalParticleState](../../model/src/main/java/cc/ecisr/mmm/fluid/FlipChemicalParticleState.java)、[FluidDomainCollisionState](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainCollisionState.java)；精确质量复用位于 [ChemicalMixtureRecord](../../model/src/main/java/cc/ecisr/mmm/chemistry/ChemicalMixtureRecord.java)。热参考能重复计算从 [ThermalStateFunction](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalStateFunction.java) 和 [ThermalReferenceEnergyCache](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalReferenceEnergyCache.java) 排查。

GPU probe 分为纯 Java/Vulkan 的 `gpu-probe-kernel` 和 Fabric 适配模块 `gpu-probe`：

- 设备与资源：[VulkanDeviceContext](../../gpu-probe-kernel/src/main/java/cc/ecisr/mmm/gpuprobe/VulkanDeviceContext.java)、[FlipGpuResources](../../gpu-probe-kernel/src/main/java/cc/ecisr/mmm/gpuprobe/FlipGpuResources.java)、[FlipKernelBindings](../../gpu-probe-kernel/src/main/java/cc/ecisr/mmm/gpuprobe/FlipKernelBindings.java)。
- 计算提交与回读：[GpuStepScheduler](../../gpu-probe-kernel/src/main/java/cc/ecisr/mmm/gpuprobe/GpuStepScheduler.java)、[GpuReadback](../../gpu-probe-kernel/src/main/java/cc/ecisr/mmm/gpuprobe/GpuReadback.java)。
- 生成场景与 CPU 对照候选计算：[GpuProbeScenario](../../gpu-probe-kernel/src/main/java/cc/ecisr/mmm/gpuprobe/GpuProbeScenario.java)；驱动发现失败的诊断方法见 [GPU 验证](validation.md#gpu)。
- 着色器：[flip.comp](../../gpu-probe-kernel/src/main/resources/assets/mmm_gpu_probe/shaders/flip.comp)。修改缓冲布局时同步资源分配、绑定和回读。
- Minecraft 接入：[MmmGpuProbeHostIntegration](../../gpu-probe/src/main/java/cc/ecisr/mmm/gpuprobe/MmmGpuProbeHostIntegration.java)。它负责只读的主区域冲突检查和快照适配。

当前 probe 支持单一常密度载体。生产 GPU 还需要完整支持材料、气体、化学、颗粒、热学和相变，并接入 `FluidSolverBackend` 的完整状态与结果接口。具体要求见 [性能与后端规划](../roadmap/performance-and-backends.md)，执行命令见 [GPU 验证](validation.md#gpu)。

<a id="diagnosis"></a>
## 按现象排查

### 总水量正常，画面中的液面却下降

先比较 [CellInventoryReader](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellInventoryReader.java) 的粒子体积统计和客户端快照，区分原始浮点数求和、8-bit 展示量化，以及粒子落在哪些网格单元造成的差异。

若服务端分布也在变化，依次查 [FlipTransferScheme](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipTransferScheme.java) 的粒子到网格权重、[GridToParticleStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GridToParticleStage.java) 的能量约束、[FlipPressurePhaseGeometry](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipPressurePhaseGeometry.java) 的压力几何，以及 [ParticleAdvectionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/ParticleAdvectionStage.java) 和 [FlipParticleReseeder](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipParticleReseeder.java)。有通量重建与两条路径交接的质心变化查 [CellCarrierRemapPlan](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellCarrierRemapPlan.java) 的共享面第一矩、全部材料与到达液体共同填充率，以及 [CellRemapMoments](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellRemapMoments.java) 的有界共同平移、实际 float 位置归属及残差诊断。最终容量使用 [ConservativeLiquidFlux](../../solver/src/main/java/cc/ecisr/mmm/fluid/ConservativeLiquidFlux.java) 的本次精确计划，颗粒表示转换由 [GranularLiquidDisplacement](../../solver/src/main/java/cc/ecisr/mmm/fluid/GranularLiquidDisplacement.java) 提供容量。对应交接验证位于 [LiquidVolumeTransportValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidVolumeTransportValidation.java)，饱和格 MAC 仿射误差和容量来源查 [CellRemapMomentsValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/CellRemapMomentsValidation.java)。运行 `free-surface,reseeding`；涉及气压时追加 `gas-liquid`。

跨格温度梯度查 [CellPolynomialField](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellPolynomialField.java) 的速度回溯与坐标变换，以及 [CellCarrierRemapPlan](../../solver/src/main/java/cc/ecisr/mmm/fluid/CellCarrierRemapPlan.java) 的接收格系数合并；解析张量场与不同供给质量的验证位于 [LiquidThermalAdvectionValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidThermalAdvectionValidation.java)。运行 `thermal`。

修复应落在产生误差的步骤。渲染增厚、强制找平或给压力方程添加虚拟体积源会掩盖问题，使体积、动量和画面失去对应关系。

### 水量或质量实际丢失

从 [CarrierMutationCoordinator](../../solver/src/main/java/cc/ecisr/mmm/fluid/CarrierMutationCoordinator.java) 的粒子增删、[MaterialTransferPlanner](../../solver/src/main/java/cc/ecisr/mmm/fluid/MaterialTransferPlanner.java) 的转移和 [FluidDomainCollisionState](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainCollisionState.java) 的挤水处理开始，对照 source/sink（加入量与移除量）记录。材料量运行 `materials`，容器运行 `containers`，粒子拆并运行 `reseeding`。

### 穿墙、小水滴异常或画面消失

- 穿过台阶、栅栏等局部形状：查 [MinecraftCollisionWorldPort](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftCollisionWorldPort.java)、[FlipCutCellGeometry](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipCutCellGeometry.java) 和 [FlipParticleCollision](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipParticleCollision.java)，运行 `shape,cut-cell`。
- 小水滴下落过慢、自由面出现额外冲量：查 [FlipVelocityExtrapolator](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipVelocityExtrapolator.java) 的正 P2G 权重面播种，以及 [DivergenceFreeVelocityExtension](../../solver/src/main/java/cc/ecisr/mmm/fluid/DivergenceFreeVelocityExtension.java) 的已知速度面；运行 `free-surface`。
- 服务端水量正常、客户端不显示：从 [ClientRegionSnapshotStore](../../fabric/src/client/java/cc/ecisr/mmm/client/ClientRegionSnapshotStore.java) 的代次与顺序查起，再看 [FluidSurfaceGeometry](../../fabric/src/client/java/cc/ecisr/mmm/client/FluidSurfaceGeometry.java) 的显示阈值，运行 `protocol` 并复核游戏画面。
- 液液界面异常：先核对材料相分组，再区分 [MaterialSurfaceGeometry](../../fabric/src/client/java/cc/ecisr/mmm/client/MaterialSurfaceGeometry.java) 的几何与 [SurfaceTensionWorkspace](../../solver/src/main/java/cc/ecisr/mmm/fluid/SurfaceTensionWorkspace.java) 的物理作用。

### 气压异常或热区域暂停

气压先查 [AirCavityScanner](../../solver/src/main/java/cc/ecisr/mmm/fluid/AirCavityScanner.java) 的气腔归属与可用体积，再查 [GasThermalModel](../../model/src/main/java/cc/ecisr/mmm/thermal/GasThermalModel.java) 的库存、内能与状态方程。相关测试为 `air-cavity,air-network,gas-liquid`。

热暂停先读取具体失败状态，再对照 [热学专题](../architecture/thermal-state.md)检查注册表指纹、正温要求、压力有限性、内能收支、相变待同步几何和气体拓扑。1 K 与热容标定端点本身都不会触发暂停；端点以外的热容函数使用连续延拓。测试使用 `thermal`。

### 区域或容器被锁定

世界方块归属问题从 [OwnershipCoordinator](../../fabric/src/main/java/cc/ecisr/mmm/simulation/ownership/OwnershipCoordinator.java) 查日志与区域 HEAD 的原代次/目标代次。容器问题从 [ContainerRecoveryCoordinator](../../application/src/main/java/cc/ecisr/mmm/application/ContainerRecoveryCoordinator.java) 查 `<world>/mmm/container-transfer/` 的日志、HEAD 校验和，以及玩家或发射器已存盘的物品状态。

恢复表中的 `THIRD` 表示外部数据既不等于操作前也不等于操作后，`UNAVAILABLE` 表示暂时无法读取。保留日志，按 [恢复决策表](../architecture/world-ownership-and-containers.md#恢复决策表)判断下一步。对应测试为 `ownership` 或 `containers`。
