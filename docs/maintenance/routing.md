# Matter, Motion & Mechanics 维护路由

本文只回答“代码在哪里、改完默认跑什么”。状态契约从 [`../README.md`](../README.md) 选择；未来工作见 [`../roadmap/product-roadmap.md`](../roadmap/product-roadmap.md)。

## 模块与主 Mod 职责

- `api/src/main/java/cc/ecisr/mmm/api/`：只放 JDK-only 的稳定外部契约；禁止 Fabric、Minecraft、NBT、live domain 引用。
- `model/src/main/java/cc/ecisr/mmm/`：registry、exact ledger、typed container truth、immutable carrier state 和 region checkpoint model；依赖 `api`，禁止活动 solver 与 loader 类型。
- `solver/src/main/java/cc/ecisr/mmm/fluid/`：CPU `FluidDomain`、stage pipeline 与完整 state/request/candidate/backend；依赖 `model`，禁止 Minecraft/Fabric/NBT/network payload。
- `application/src/main/java/cc/ecisr/mmm/application/`：loader-neutral command/result/use case/port 与 checkpoint export 边界；依赖 `solver` / `model`。
- `core/`：无生产源码的聚合发布 artifact，只重导出 `api` / `model` / `solver` / `application`。
- `fabric/src/main/java/cc/ecisr/mmm/`：Fabric/Minecraft 编排、数据包/持久化适配和世界事务。
- `fabric/src/client/java/cc/ecisr/mmm/`：只读客户端重建与可选观察层格式化。
- `gpu-probe-kernel/`：loader-neutral Vulkan device/resource/binding/scheduler/readback、shader 与 headless validation；依赖 `solver`，禁止 Minecraft/Fabric 与主 runtime/network。
- `gpu-probe/`：可选 Fabric lifecycle、命令、只读世界碰撞和 snapshot adapter；内嵌 kernel，compile-only 依赖 `fabric`，不取得世界或持久事务写权。

| 职责 | 首要入口 | 必须保持 |
|---|---|---|
| 初始化、tick、payload 注册 | `fabric/src/main/java/cc/ecisr/mmm/MmmMod.java` | `SERVER_STARTING` 先建立 runtime；其余 callback 只从 runtime registry 取服务；registry 和网络注册成套 |
| 正常游玩入口 | `block/FluidControllerBlock.java`、`simulation/MinecraftRegionActivation.java`、`item/ChemicalIndicatorItem.java`、`simulation/MinecraftIndicatorEditing.java`、`block/MmmBlocks.java`、`item/MmmCreativeEntries.java`、`resources/data/mmm/recipe/` | controller 激活/选择/暂停与 indicator 编辑各由专职 server-thread owner 编排；配方、创造栏和注册同步 |
| 产品目录与创造源 | `item/MmmCreativeEntries.java`、`MmmItems.java`、`CreativeSourceItem.java`、`simulation/CreativeSourceCatalog.java`、`model/.../transfer/CreativeSourceDescriptor.java` | 自定义 tab 顺序稳定；descriptor 只表达意图；每次使用按 committed world registries 服务端解析 exact amount/kg/U；不把 template 当 actual inventory |
| 快速入门与物品提示 | `item/QuickStartGuideItem.java`、`client/QuickStartScreen.java`、`block/MmmBlockItem.java`、`assets/mmm/lang/` | 指南与 tooltip 只描述当前产品边界；资源、注册和创造栏同步；不从客户端文案获得写权限 |
| `/mmm` 命令 | `MmmCommands.java`、`MmmCommandHandlers.java`、`simulation/MinecraftRegionOperatorActions.java`、`MinecraftDebugFixtures.java`、`MinecraftRegionAdministration.java`、`RegionDiagnosticsReport.java` | `MmmCommands` 只建 Brigadier 树并统一审计；handler 只做命令结果映射；operator 写、场景、区域 catalog/checkpoint 与只读诊断分别直达语义 owner；完整输出写 transcript |
| Minecraft 编排 | `fabric/.../simulation/MinecraftSimulationLoop.java`、`MinecraftRuntimePolicy.java`、`MinecraftMotionExecutor.java`、`MinecraftPendingPhaseWorkBarrier.java`、`MinecraftRegionOperatorActions.java`、`MinecraftRegionTick.java`、`MinecraftRegionInputs.java`、`MinecraftRegionLifecycle.java`、`MinecraftRegionAvailability.java`、`FluidWorldTargetResolver.java` | loop 独占全局 tick/startup recovery/shutdown 顺序；policy 独占 world-local 配置；motion executor 独占同步 motion transaction；phase-work barrier 只在 host transfer 前用权威 backend 消费旧 lien；operator owner 独占管理员写动作；tick/input/availability/lifecycle/target 各维持原单一职责，不建立通用 service facade |
| server runtime / world startup | `simulation/MmmRuntimeRegistry.java`、`MmmRuntimeFactory.java`、`MmmRuntime.java`、`WorldStartupCoordinator.java`、`RegistryBundleLoader.java`、`CommittedWorldRegistries.java`、`network/ProtocolSessions.java` | 只有 registry 按 `MinecraftServer` 索引主运行时；factory 显式构造并接线所有语义 owner，runtime 只暴露按行为命名的入口，不暴露 manager/domain；构造期 I/O/发现顺序只在 factory；world-dependent recovery 只在 `SERVER_STARTED`；pre-start event subscription 不初始化 world state |
| region tick 用例 | `application/.../application/RegionTickUseCase.java`、`RegionRuntime.java` | frozen runtime 只含 tick/pacing/readiness；plan 每 tick 只选 `NONE`/`MOTION`/`THERMAL` 一个轴且 motion 优先；窄 step executor 由 use case 自己拥有；只有 accepted step 更新 pacing/过载证据；单调时钟可注入，禁止 Minecraft/world import |
| controller query 用例 | `application/.../application/ControllerQueryUseCase.java`、`command/ControllerSummaryCommand.java`、`port/ControllerSummaryPort.java`、`model/.../controller/ControllerConsoleSessions.java` | adapter 先授权 viewer/owner facts；bounded view 成功验证后才签发 token；summary projection、consume/forget 由同一用例拥有；miss/错 identity/非法 view 不产生未披露 token |
| region lifecycle | `application/.../application/RegionLifecycleUseCase.java`、`model/.../controller/LifecycleOperationSessions.java`、`LifecycleOperationPreview.java`、`fabric/.../simulation/MinecraftRegionLifecycle.java` | application 按 permission/write/vented/idempotence 封闭 run intent，preview 固定 200 tick，confirm 消费 nonce 并重验 context/blockers；Minecraft adapter 冻结事实，并分别执行 inventory candidate+HEAD、ownership release、REMOVED tombstone/quarantine，禁止用通用 callback 抹平 commit point |
| region application 用例 | `application/.../application/RegionApplicationService.java`、`application/command/`、`application/result/`、`application/port/` | command 只有 stable identity、expected generation、预算与已判定 facts；result 为封闭状态和 bounded data；实现 `RegionTickUseCase.StepExecutor` 供 tick 用例调用；禁止 Minecraft/NBT/network payload import，不返回 live domain |
| region application adapters | `FluidRegionRepository.java`、`ControllerSummaryAdapter.java`、`FluidRegionOperations.java` | repository 只做 immutable solver capture/atomic candidate adopt；checkpoint port 发布前重验 generation/ownership；summary adapter 只投影 bounded read model |
| 持久区域管理与 runtime admission | `FluidRegionOperations.java`、`RegionRepositoryAdapter.java` | repository 组合 checkpoint source/assembler、generation store、当前 registries/profile 与 live restore，并只读 ownership guard；committed metadata 先登记；admission 按内在失败→ownership/policy→catalog→boundary 惰性检查；数值/ownership 就地阻断，其他拒答只有 checkpoint 成功后才释放；当前功能布局必须精确匹配，不改写不匹配载荷 |
| 区域身份与生命周期 | `FluidRegion.java`、`FluidRegionLifecycle.java`、`ThermalActivity.java`、`api/.../RegionAvailabilityReason.java` | operator intent 与封闭 availability reason 正交；UNLOADED 仍占预算；motion/thermal 调度不改变库存 |
| 区域 catalog 与预算 | `application/.../application/RegionCatalog.java`、`FluidRegionManager.java` | catalog 只保存 opaque owner 与 UUID/dimension/AABB/reservation descriptor，独占 limits/usage/admission result 与 duplicate/budget/overlap/selection/replacement；manager 只做 Minecraft descriptor 映射并直接返回 catalog 类型，不建立镜像 DTO；首个 HEAD 成功前不得登记；已提交 metadata 不因当前政策预算被丢弃 |
| 世界配置 | `FluidServerConfig.java`、`WorldStartupCoordinator.java` | 只接受当前完整配置；接入政策与预算在启动时冻结；不可读 policy 保留原文件、使用默认预算且返回 typed restricted state；未知 key 直接拒绝，旧 schema 的世界 LOCKED |
| registry snapshot codec | `MaterialRegistryCodec.java`、`ChemicalRegistryCodec.java`、`GranularRegistryCodec.java`、`GasRegistryCodec.java`、`ThermalRegistryCodec.java`、`StrictNbtFields.java` | 只做规范编解码，没有自己的世界目录；compound 字段集合和类型必须精确；`snapshotBytes`/`writeSnapshot`/`readSnapshot` 是唯一入口，能力包的成员字节与校验和必须共享它 |
| 世界能力包 | `simulation/persistence/WorldCapabilityBundleStore.java`、`WorldCapabilityStartup.java`、`WorldCapabilityBundleHead.java`、`WorldCapabilityBundleManifest.java`、`WorldCapabilityBundleMetadataCodec.java`、`WorldCapabilityProfileCodec.java`、`model/.../worldprofile/` | HEAD rename 是唯一 commit point；成员字节取自各 registry codec 的 `snapshotBytes`；`inspect` 只看目录名不解码载荷；四种 presence 与四种启动状态各自只有一种可证明的处置；staging 只 quarantine 不删除；恢复不查定义源；每个发布边界都有故障注入点；权威域只有 `container-transfer`/`ownership`/`region-state`，`quarantine` 是已知非权威证据根，per-kind registry 目录是未知条目并 LOCKED |
| 世界碰撞 | `MinecraftRegionInputs.java`、`MinecraftCollisionWorldPort.java`、`FluidRegionWorldInteraction.java`、`model/.../world/` | input adapter 控制五 tick 刷新节拍；Minecraft shape 先冻结为纯 Java `CollisionField`；changed cells 同时唤醒 motion/thermal；挤水只按 marker 账本 |
| 实体流体交互 | `MinecraftRegionInputs.java`、`application/.../application/RegionTickUseCase.java`、`solver/.../fluid/FluidDomain.sampleAxisAlignedBody`、`model/.../world/MovingBodyFluidCoupling.java`、`FluidDomain.applyMovingBodyCoupling` | `entity-fluid`；input adapter 的相邻 server-tick 历史冻结本 tick 实际位移，不用 `Entity#oldPosition`，并每 game tick 应用实体侧响应；solver 按派生水平液层与 cut-micro geometry 连续积分湿体积；只有到期 motion plan 消费一次 liquid request，thermal-only 不消费；solver 回报实际合计冲量 |
| controller footprint | `simulation/controller/` | 非加载读取；破口、缺 chunk、多内腔、重叠或超预算拒绝 |
| 区域 checkpoint | `model/region/RegionCheckpoint.java`、`RegionIdentity.java`、`RegionGeometry.java`、`RegionRuntimeIntent.java`、`application/checkpoint/RegionCheckpointSource.java`、`MinecraftRegionCheckpointSource.java`、`simulation/persistence/RegionCheckpointAssembler.java`、`RegionCheckpointValidator.java`、`RegionCheckpointRestorer.java`、`RegionState.java`、`RegionStateCodec.java`、`RegionGenerationStore.java`、`RegionHeadStore.java`、`ActiveGenerationStore.java`、`RemovedGenerationStore.java`、`RemovedQuarantineStore.java`、`RegionDiscovery.java` | source 是唯一 live-region→stable value/snapshot 映射；assembler/validator/restorer 处理逻辑 checkpoint；generation coordinator 独占 ACTIVE/REMOVED prepare/commit/abort/read/discover 与 HEAD 发布，五个子 store/discovery 均 package-private；未知持久证据保留并 LOCKED |
| 跨域恢复代数 | `model/.../recovery/CrossDomainRecovery.java`、各 journal adapter、`RegionGenerationStore.java`、`RemovedQuarantineStore.java` | HEAD 只认 base 或 checksum 匹配的 target；外部成员逐一分类 BEFORE/AFTER/THIRD/UNAVAILABLE；base 只接受全 BEFORE，target 只收敛 BEFORE；UNAVAILABLE 只由对应加载事件重试；container 与 REMOVED quarantine recovery 都必须委托该代数 |
| takeover | `TakeoverTransaction.java`、`TakeoverBackupStore.java` | journal/WARNING 完成前禁止世界修改 |
| ownership | `OwnershipTransactionCoordinator.java`、`simulation/ownership/`、`RegionRepositoryAdapter.java` | Minecraft coordinator 负责 footprint/preimage、checkpoint capture 与 live result；低层 `OwnershipCoordinator` 独占 WAL→WARNING→世界→region HEAD→committed/recovery 状态机；repository 只保留 checkpoint/restore 所需 read guard；未知状态保留并锁定 |
| 容器事务 | `application/.../application/ContainerTransferUseCase.java`、`ContainerTransferCoordinator.java`、`ContainerRecoveryCoordinator.java`、`model/.../transfer/ContainerTransferActor.java`、`ContainerTransferJournal.java`、`fabric/.../simulation/MinecraftContainerTransferPlan.java`、`PlayerContainerTransferAdapter.java`、`DispenserContainerTransferAdapter.java`、`MinecraftPendingPhaseWorkBarrier.java`、`MinecraftContainerTransactionAdapter.java`、`MinecraftContainerActorAdapter.java`、`MinecraftContainerRecoveryAdapter.java`、`MinecraftRegionAvailability.java`、`fabric/.../simulation/persistence/ContainerJournalCodec.java`、`ContainerJournalFileStore.java`、`fabric/.../item/ContainerItemMapper.java` | player/dispenser 各自持有 target/availability/transaction/snapshot owner 并共用唯一 plan；若存在 phase lien，barrier 先发布 phase-off mechanical successor，再以其 committed region state 为容器 WAL base；plan 不借 service 取得 world truth 或事务；recovery 独占 boot index、solver-dirty、UNAVAILABLE/THIRD 与事件重试；回读完成前不得删 WAL |
| committed world / typed 容器真值 | `simulation/CommittedWorldRegistries.java`、`model/.../transfer/`、`fabric/.../item/ContainerItemStacks.java` | startup result 是 committed profile/registry 唯一来源；capability、registry、actual profile 与液体 U 绑定只经 world owner；体积/质量/species/SVQ/U、actor 与 journal 的纯 Java 真值不引用 Minecraft；`ItemStack` component 编解码只留在 Fabric adapter；持久编码不携带 runtime handle |
| 内置 field probe | `item/FieldProbeItem.java`、`simulation/ShellFluidQuery.java`、`MinecraftControllerConsole.java`、`solver/.../fluid/CellCarrierInventoryView.java`、`FluidWorldTargetResolver.java` | 壳面两侧 owner 必须唯一；液体/carrier identity 同代同格；Top-N 有界；不加载、不唤醒、不 checkpoint、不建派生拓扑 |
| controller console | `MinecraftControllerConsole.java`、`application/.../application/ControllerQueryUseCase.java`、`RegionLifecycleUseCase.java`、`model/.../controller/`、`ControllerSummaryAdapter.java`、`network/Controller*Payload.java`、`client/ControllerConsoleScreen.java` | Minecraft adapter 重验玩家距离/方块/owner 后才消费一次性 token；query use case 验证 bounded view 后签发 generation；UNLOADED 不发明 live inventory；run intent 与破坏性 preview 授权由 lifecycle use case 封闭，durable commit 不离开 Fabric |
| fixed thermal reservoir 调节 | `application/.../application/ThermalReservoirUseCase.java`、`model/.../thermal/ThermalReservoirSummary.java`、`WorldPositionActionSessions.java`、`network/ThermalReservoir*Payload.java`、`client/ThermalReservoirScreen.java` | summary 验证后才签发 player+world-position token；距离由 adapter 先验，token 后按 creative/write/target/registry 顺序判定；setpoint 限于 committed thermal registry 闭区间；BlockEntity 写入只改未来 boundary flux，不直接改 region U |
| 活动求解器聚合根 | `solver/.../fluid/FluidDomain.java`、`FluidDomainStorage.java`、`FluidDomainContracts.java`、`FluidDomainMechanicalFields.java`、`FluidDomainStepCoordinator.java`、`FluidDomainInventoryTransactions.java`、`FluidDomainCollisionState.java`、`FluidDomainCapabilities.java`、`FluidDomainGasAndGranular.java`、`FluidDomainStateMapper.java` | public leaf 是唯一 live aggregate；package-private implementation partitions 共用同一对象/数组而不形成第二 owner；capture/restore 装配集中；mechanics→conduction→reaction→phase→granular→reseed |
| 力学 stage 边界 | `MechanicalStageAssembly.java`、`MechanicalStepPipeline.java`、`ParticleToGridStage.java`、`GranularMechanicalStage.java`、`BodyForceStage.java`、`PressureProjectionStage.java`、`GridToParticleStage.java`、`ParticleAdvectionStage.java` | assembly 只负责一次性接线；stage 只持有稳定 workspace 与本 stage 的窄 `Access`，不得保存 aggregate/facade 或通过通用 god adapter 取得无关状态 |
| 热化学 stage 边界 | `ThermochemistryStageAssembly.java`、`ThermochemistryPipeline.java`、`ThermalTransportStage.java`、`ChemicalReactionStage.java`、`GranularReactionStage.java`、`CondensedPhaseChangeStage.java`、`GasLiquidPhaseChangeStage.java`、`GasPhaseInterfaceResolver.java`、`PhaseChangeStageSupport.java` | pipeline 是 conduction→chemical reaction→granular reaction→condensed phase→gas phase 的唯一生产顺序源；gas phase stage 独占 extent/commit，resolver 只解析相邻 sealed owner 并按热力学失衡排序；两类 phase 共享 exact carrier/composition 操作而不互相持有；assembly/stage 不保存 aggregate |
| 后端完整候选 | `FluidDomainState.java`、`FluidStepRequest.java`、`FluidStepCandidate.java`、`FluidThermalStepRequest.java`、`FluidThermalStepCandidate.java`、`FluidSolverBackend.java`、`application/RegionApplicationService.java`、`FluidRegionRepository.java` | 后端只计算私有完整后继；application 校验 backend/紧邻 generation/完整 `Layout`，repository 按 live identity 重验后 atomic adopt；同进程 CPU prepared aggregate 只能领取一次，领取后不得再物化历史 state；accepted result 只携带 stats/impulse，不向 adapter 返回 candidate/arrays |
| 自由面/压力/传递 | `FlipPressurePhaseGeometry.java`、`PressureFaceCoupling.java`、`PressureProjectionWorkspace.java`、`PressureProjectionStage.java`、`PressureSystem.java`、`FlipFreeSurfaceGeometry.java`、`FlipTransferScheme.java`、`GridToParticleStage.java`、`DivergenceConformingVelocityField.java`、`ParticleAdvectionStage.java`、`FlipVelocityExtrapolator.java` | 守恒 marker footprint/PLIC 的 center containment 决定 liquid pressure unknown；`PressureFaceCoupling` 唯一持有 liquid-liquid/liquid-gas mobility 与 jump 符号；有限 sealed node 的 `V*beta` row 与 liquid 联合 PCG，环境/真空仍为 Dirichlet；每次 projection 在 workspace 装配一次固定 unknown 集与 face matrix，PCG 迭代只更新向量；物理 pressure 不接收体积漂移或过容量伪源；G2P 取能量可接受的最大 FLIP 增量；全开面平流使用散度一致 `H(div)`/RK4，partial cut-face 保留 cut-cell 路径；PCG 使用全 block 绝对 RMS 容差，未收敛候选安全暂停 |
| 表面张力/润湿 | `BodyForceStage.java`、`SurfaceTensionWorkspace.java`、`FlipCutCellGeometry.java`、`FluidDomain.rebuildSurfaceTensionPressure` | 已解析液气面走 sharp jump，亚格 carrier 面走互补 CSF；液液走 pairwise balanced-force；Young 接触角只进入 cut-wall surface energy，不旋转曲率法向 |
| cut-cell/CCD | `FlipCutCellGeometry.java`、`FlipParticleCollision.java` | pressure 与碰撞共享 aperture；checkpoint/handoff 保留 raw micro mask；只截断命中法向 |
| logical step 尾部与 reseed | `LogicalStepTailAssembly.java`、`GranularTransitionStage.java`、`ParticleReseedStage.java`、`FlipParticleReseeder.java`、`ConservativeMarkerSampling.java`、`CarrierMutationCoordinator.java` | granular compaction/deposit 与 reseed 分列；reseed 保持体积、质量、动量、质心和相身份；不可精确表示的 float merge 保留 residual marker 并单列 conservation refusal；coordinator 同序搬运 chemistry/thermal side column |
| 材料与单位边界 | `FluidMaterial*.java`、`BuiltinFluidMaterials.java`、`units/SolverUnitScale.java` | marker 体积与 solver mass 独立；SI/容器只在统一边界换成 m³/kg；stable ID 上链，runtime handle 不上链 |
| 气体 | `model/.../fluid/air/`、`model/.../thermal/GasThermalModel.java`、`GasRegistryCodec.java`、`solver/.../fluid/AirCavityScanner.java`、`PressureBoundaryView.java`、`PressureProjectionWorkspace.java`、`GasLiquidPhaseModel.java` | component nanos/kg/species/source/sink 闭合；gas mass/residual 均为 kg；温度/压力/密度/绝热体积模量/声速/compliance 来自同一 inventory+volume+U 闭合；有限 sealed node 只在 pressure scratch 中增加集总 `V*beta` unknown，node inventory/U 仍唯一；真空无 continuum pressure unknown；component-local allowed species 由 world registry 冻结并在所有发布入口验证；热区域持久拓扑同代 |
| 化学 | `chemistry/`、`FlipChemicalParticleState.java`、`units/SolverUnitScale.java` | exact species/组成/电荷/kg 残差；indicator provenance 不隔离反应 |
| 颗粒 | `granular/`、`GranularCouplingFields.java` | SVQ、kg 质量、组成独立；active/deposit/coherent 不混用 |
| 热学 | `thermal/ThermalDomainState.java`、`thermal/ThermalCellOperations.java`、`thermal/`、`fluid/FluidThermalBinding.java`、`FlipThermalParticleState.java` | carrier owner/审计与无库存的 LTE/reaction/phase 派生算法分离；fluid snapshot/restore/reseed 类型映射只在无状态 binding；总 U 权威；温度派生；`T_eq(p)` curve 是唯一压力域/平衡真值；超域暂停；exact phase batch 选择最近可表示平衡；通量、反应热、相变、功联合审计 |
| 有限热设备 | `FiniteThermalBodyState.java`、`FiniteThermalDeviceState.java`、`FiniteThermalDeviceInventory.java`、`FiniteThermalDeviceAudit.java`、`FiniteThermalExchange.java`、`ThermalCellGrid.java`、`ThermalDomainState.java`、`FluidDomain.java`、`FluidRegion.java`、`FiniteThermalDeviceCodec.java`、`RegionState.java` | provider/object 只是稳定 locator；DEVICE material + kg + U + generation 由 region HEAD extension 持有；同 region body/cell candidate 预检后一次内存提交并在下一 HEAD 联合发布，solver/device 各自审计 exchange 与 roundoff；跨 region 不走此路径 |
| 客户端快照 | `FluidSnapshotPublisher.java`、`fabric/.../network/FluidProtocol.java`、`client/ClientRegionSnapshotStore.java`、`FluidSurfaceGeometry.java`、`MaterialSurfaceGeometry.java`、`GranularSurfaceGeometry.java`、`QuadGeometry.java`、`FluidRenderBackend.java`、`ClientFluidVolumeRenderer.java` | publisher 只构造/广播派生载荷；store 独占五类 ledger、generation/sequence/tombstone 与插值 frame；geometry 只读 immutable frame；backend 独占 staged GPU buffer；renderer 只编排，语义 layout 精确协商且不得反写物理或恢复库存 |
| 外部 Query API | `api/.../query/`、`FluidCellInventoryView.java`、`FabricFluidQueryApi.java`、`api/fabric/MmmFabricApi.java` | server-thread only；不加载、不唤醒、不建网格、不返回 live domain |
| 外部 committed event | `api/.../event/`、`CommittedEventHub.java`、`api/fabric/MmmFabricApi.java` | 只在 durable commit 后发布；监听器异常隔离；事件不是库存日志 |
| Jade 观察适配 | `fabric/src/main/java/cc/ecisr/mmm/compat/jade/`、`fabric/src/client/java/cc/ecisr/mmm/compat/jade/` | Jade 保持可选；服务端只调 Query API；展示摘要有界且不可回写；改依赖/API 后编译 main 与 client |
| 命令审计 | `logging/` | UTF-8 观测；记录失败不能改变命令结果 |
| 区域诊断 | `simulation/RegionDiagnosticsReport.java`、`MinecraftRegionAdministration.java` | diagnostics 只读 selected/live/committed owner，保持 status/ownership/chemistry/granular 原输出；catalog list/select/checkpoint/recovery 不与报告 owner 复制 |

## 高频定位

- 精确水量丢失：`setSolid`、marker create/remove、transfer primitive、`escapedSink`。
- 压力/散度：`solvePressure`、`applyPressureMatrix`、`projectVelocity`。
- 小水滴慢落：`FlipVelocityExtrapolator`、`trilinearValid`。
- 水量正常但液面下降：先区分 raw-float exact marker ledger、顺序相关 reduction、8-bit 展示量化和 containing-cell 分布，再查 P2G face weight、G2P 能量约束、pressure PLIC、`H(div)` 平流与 reseed；禁止向物理 RHS 塞漂移源、增加第二位置投影、renderer 增厚或强制找平。
- partial 方块或穿墙：cut-cell aperture、marker CCD、world shape adapter。
- 水体存在但客户端消失：snapshot ledger、marker mask、renderer scalar/iso threshold。
- 液液界面异常：material fraction、surface mesher、surface tension；视觉法线与物理锐化分开评估。
- 气压异常：cavity scanner、available volume、EoS、orifice edge、interface pressure。
- 热区暂停：thermal/gas registry fingerprint、candidate T/P 有效域、U/source/sink/work、phase pending-work、gas topology ordinal。
- ownership 锁：ownership store 的 WAL/committed 与 region HEAD base/target。
- 容器锁：`<world>/mmm/container-transfer/` 扫描、HEAD checksum、actor before/after durable state；不要手删未知 WAL。

## CPU 验证

所有门由一个 runner 执行。小改动只选表中最近 selector，不累计其他领域：

```powershell
./gradlew.bat :fabric:runCpuValidation --offline --no-daemon
./gradlew.bat :fabric:runCpuValidation '-PcpuValidation=region-state,containers' --offline --no-daemon
./gradlew.bat :fabric:runCpuValidation -PcpuValidation=milestone --offline --no-daemon
./gradlew.bat :fabric:runCpuValidation -PcpuValidation=soak --offline --no-daemon
./gradlew.bat :fabric:runCpuValidation -PcpuValidation=list --offline --no-daemon
./gradlew.bat verifyArchitecture --offline --no-daemon
```

`verifyArchitecture` 是独立静态门（物理模块/import 方向、唯一 runtime locator、loader-neutral 包环、生产类 1000 行硬上限），不进入任何 CPU selector；当前没有迁移 allowlist，基线见 [`architecture-baseline.md`](architecture-baseline.md)。

| 改动 | selector | 追加条件 |
|---|---|---|
| marker 材料/质量/材料界面 | `materials` | 同时改存档或协议才追加最近门 |
| 表面张力/润湿 | `surface-tension` | 同时改材料状态追加 `materials` |
| 气体库存/EoS/孔流 | `air-network` | 改 pressure 消费运行 `gas-liquid` |
| 气体热力学/声学闭合 | `thermal` | 覆盖跨 caloric segment 的解析等熵路径与可逆往返；同时改等温 EoS 追加 `air-network`，接入 pressure operator 再追加 `gas-liquid,free-surface` |
| cavity 扫描/几何边 | `air-cavity` | 改共享 cut-face 追加 `cut-cell` |
| 活动气液耦合 | `gas-liquid` | 显式选择包含完整平衡门；同时固定有限密闭库存零容量时 typed rejection 且提交前 inventory/audit 原样保留 |
| 密闭 partial gas owner / `empty` 后边界注液 | `controller-gas-lineage` | 以 production liquid/gas registry 复现 `34 m³std → empty → 16 BV`，连续 200 个 backend candidate/capture/restore；改 scanner 几何时先跑 `air-cavity`，改压力消费时再跑 `gas-liquid` |
| 生产 CPU wall-time | `production-live-budget` | 默认 `8³` controller 的 20 个 live logical step 各自不超过 40 ms；另取空场景最大装量 `/mmm demo` 的 `24×16×24`、2,592 liquid cells、20,736 初始 markers 和完整能力，连续跑 20 个 production CPU ownership-transfer step，不得形成三步连续超过 200 ms；phase-idle 步逐步要求 exact liquid volume/mass 不变，相变步继续由完整 candidate audit 闭合；不能用 pacing、提高阈值、关闭物理或给被测大域强制 warmup 绕过，保留逐步 volume、marker、phase operation、substep、PCG 和 residual 证据 |
| catalog/manager/runtime admission | `region-manager` | admission 涉及 checkpoint/restore 时追加 `region-state`；跨 lifecycle 再追加最近门 |
| lifecycle/tick/application step | `lifecycle` | 覆盖 PAUSED/跳过/due tick、motion-over-thermal 唯一轴与 motion-input exactly-once；覆盖 40/200 ms 边界、连续过载、八步恢复以及 `PERFORMANCE_PAUSE` 与 `NUMERICAL_PAUSE` 的分类；`run`/手动 `step` 显式重试只能清除前者；同时覆盖 application command 的 stale/backend/完整 layout 拒答、atomic adopt、checkpoint WAL 短路与 bounded summary；同一门固定完整 handoff raw-bit parity |
| 实体 moving-body 接线 | `entity-fluid` | 覆盖跨 tick 位移观测、bodyMotion→request 转换与预算；同时覆盖静态/动态实体不放大液面，以及走动实体必须产生可观察液体响应并在静止后衰减回静止包络 |
| controller 注液/生产 step/权威 handoff/逐帧预算 | `production-replay` | 默认 `8×8×8` 闭壳注入 16 BV，JVM 预热后每个 live step 不超过 40 ms；另以 `(5,0,4)+16 BV`、40 步后 `(3,3,5)+14 BV` 的非平面注液轨迹建立两条 1000 步门：隔离 gas 的门约束尾部/终值动能及 exact 体积/质量；正式 thermal/gas/phase 定义的门每步经过 backend capture/restore，要求初始 `64 m³std` 库存在两次注液后 source/sink 不变、容量依次为 `48/34 m³`、总 `U` 闭合且压力逐 node 满足 inventory/volume/temperature EoS。另从完整空闭壳的 `64 m³std` 气体真值复现用户 `(7,6,7)+16 BV`，连续运行 400 个 phase-on full-budget backend candidate；中途以 phase-off mechanical barrier exact 消费 pending `-pΔV` 后再次注入 16 BV，尾部同样结算后从 `(4,1,4)` 抽取 16 BV，并逐步要求 gas/thermal audit 闭合。thermal-off 的恢复/清空路径另要求 `34 m³std` 库存经 `empty` 扩容到 `64 m³`、边界注入 16 BV 后在 `48 m³` 容量内连续通过 200 个 backend candidate/restore，期间 partial gas owner、source/sink 与总库存不变。正式半满状态还要求 capture/restore、phase-off backend candidate/restore 等价，并要求独立预热后新建 domain 的首个 phase-on production step 在 40 ms 内闭合 gas、pending volume 与总 `U`；环境初始化和先空腔后注液压缩的平面自由面分别连续执行 500/1000 次 phase-off backend candidate/restore，以 opt-in mechanical audit 约束动能峰值/尾部/终值、质心漂移、体力-pressure 功残差、gas lineage 和总 `U`；同一平面场景还以 500 个 candidate 约束静止玩家尺寸 kinematic boundary 不放大动能，并核对液体/外部约束反作用冲量；phase-off backend candidate 的复制耗时仅作诊断，phase-on 长跑会引入真实非平衡相变/自然对流，不能冒充纯机械平衡门 |
| checkpoint/配置/恢复 | `region-state` | 自动门无法覆盖事务时才做真实重启；world capability bundle 的发布/恢复/故障注入同属此门，不为它新增 selector |
| protocol/snapshot | `protocol` | 改注册入口才启动专服 |
| field probe/controller console/thermal setpoint payload | `protocol` | 改 carrier 观察追加对应 `granular`/`air-network`/`thermal`；改 summary 或破坏性 preview token 追加 `lifecycle`；改 REMOVED 持久边界追加 `region-state`/`ownership` |
| controller/footprint | `boundary` | 改预算追加 `region-manager` |
| pressure/free-surface/MAC/G2P/advection | `free-surface` | 改 marker 分布追加 `reseeding`；改 surface tension 追加 `surface-tension`；改 gas pressure 边界追加 `gas-liquid` |
| cut-cell/CCD | `cut-cell` | Minecraft adapter 追加 `shape` |
| `VoxelShape` adapter | `shape` | 不运行长期 solver 门 |
| marker split/merge | `reseeding` | 改 checkpoint 追加 `region-state` |
| takeover | `takeover` | 真实世界只在发布 smoke |
| ownership | `ownership` | 改 checkpoint 契约追加 `region-state` |
| 容器/物品/WAL | `containers` | 改协议追加 `protocol` |
| transcript | `audit` | 命令包装且自动门不足才进服 |
| 化学 | `chemistry` | 只追加直接跨层的最近门 |
| 颗粒 | `granular` | 共享 pressure/air/存档改动追加对应门 |
| 热学/相变 | `thermal` | 共享 solver/air/chemistry/granular/存档/容器按需追加 |
| 后端 state/candidate | `thermal` | 只追加实际跨过的 carrier 门；改变 checkpoint 形状才追加 `region-state` |
| 外部 Query API/ABI | `protocol` | 改 marker/species/thermal 映射时追加对应最近门 |
| 外部 committed event/typed quantity API/ABI | `protocol` | 改 container commit 边界或 ItemStack 映射追加 `containers` |

新增覆盖优先替换同一不变量的已有测试。只有新失败模式无法归属现有领域时才新增 selector；长期场景进入 `soak`。

## 构建与部署

只有需要部署或发布时运行：

```powershell
./gradlew.bat clean assemble compileTestJava --offline --no-daemon
```

- mods 目录：`Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\mods`。
- 同一 Mod id 只保留一个启用 JAR；复制后比较 SHA-256。
- 只有视觉、真实 `VoxelShape` 或真实世界事务无法由自动门观察时才请求进游戏，并给出单一场景、命令、预期和日志证据。

## GPU probe

`gpu-probe` 不修改世界或存档，当前主 Mod 仍以 CPU 为规范后端。数值入口位于 `gpu-probe-kernel/src/main/java/cc/ecisr/mmm/gpuprobe/`，Fabric 入口位于 `gpu-probe/src/main/java/cc/ecisr/mmm/gpuprobe/`。改 buffer layout 时同步 `FlipGpuResources`、`FlipKernelBindings`、shader 与 readback；改提交顺序时只经 `GpuStepScheduler`；主 Mod 冲突与 snapshot 只经 `GpuProbeHostIntegration`。GPU reseed 的 raw-float merge 可表示性判定必须保留 GLSL `precise`，否则编译器代数重排会把守恒检查消掉。设备性能使用 Vulkan timestamp，调度与超时仍使用 wall time。当前 kernel 只有单一常密度 carrier，不含材料/表面张力/gas/thermal candidate；生产 GPU 后端必须经 `solver` 的不可变输入/完整 candidate 边界接入，不能取得世界或持久化提交权。

最近静态门为 `:gpu-probe-kernel:compileJava :gpu-probe:compileJava :gpu-probe:runAdapterValidation verifyArchitecture`。改 native owner、shader、checkpoint 或 GPU 数值时先跑 `:gpu-probe-kernel:runGpuFlipValidation -PgpuProbeSkipLarge=true -PgpuProbeSkipLong=true`；长程分布/能量最近门为 `:gpu-probe-kernel:runGpuLongRunSurfaceValidation -PgpuProbeLongRunSteps=100`，完整 1000 步再去掉该属性或运行默认任务。改 capability/device 初始化另跑 `:gpu-probe-kernel:runHeadlessGpuProbe`。长跑以每个 raw float 的精确二进制有理数和比较库存；GPU reduction 只在解析舍入界内作在线诊断。其余只比较机械能非增、域边界和发布 footprint 等可比不变量，不要求 CPU/GPU 浮点归约后的逐格轨迹一致；失败不能以阈值放宽、速度钳制或展示修补替代。
