# Matter, Motion & Mechanics 维护路由

本文只回答“代码在哪里、改完默认跑什么”。状态契约从 [`../README.md`](../README.md) 选择；未来工作见 [`../roadmap/product-roadmap.md`](../roadmap/product-roadmap.md)。

## 模块与主 Mod 职责

- `api/src/main/java/cc/ecisr/mmm/api/`：只放 JDK-only 的稳定外部契约；禁止 Fabric、Minecraft、NBT、live domain 引用。
- `core/src/main/java/cc/ecisr/mmm/`：物理算法、registry 值对象、typed container truth 和权威内存状态；禁止 `net.minecraft`、`net.fabricmc`。
- `fabric/src/main/java/cc/ecisr/mmm/`：Fabric/Minecraft 编排、数据包/持久化适配和世界事务。
- `fabric/src/client/java/cc/ecisr/mmm/`：只读客户端重建与可选观察层格式化。
- `gpu-probe/`：Vulkan probe 与后端实验；数值代码依赖 `core`，世界集成 compile-only 依赖 `fabric`。

| 职责 | 首要入口 | 必须保持 |
|---|---|---|
| 初始化、tick、payload 注册 | `fabric/src/main/java/cc/ecisr/mmm/MmmMod.java` | 生命周期、registry 和网络注册成套 |
| 正常游玩入口 | `block/FluidControllerBlock.java`、`block/MmmBlocks.java`、`item/MmmCreativeEntries.java`、`resources/data/mmm/recipe/` | 激活即运行；潜行交互只切换持久暂停意图；配方、创造栏和注册同步 |
| 产品目录与创造源 | `item/MmmCreativeEntries.java`、`MmmItems.java`、`CreativeSourceItem.java`、`simulation/CreativeSourceCatalog.java`、`core/.../transfer/CreativeSourceDescriptor.java` | 自定义 tab 顺序稳定；descriptor 只表达意图；每次使用按 committed world registries 服务端解析 exact amount/kg/U；不把 template 当 actual inventory |
| 快速入门与物品提示 | `item/QuickStartGuideItem.java`、`client/QuickStartScreen.java`、`block/MmmBlockItem.java`、`assets/mmm/lang/` | 指南与 tooltip 只描述当前产品边界；资源、注册和创造栏同步；不从客户端文案获得写权限 |
| `/mmm` 命令 | `MmmCommands.java` | 状态操作留在服务层；完整输出写 transcript |
| Minecraft 编排 | `fabric/.../simulation/FluidSimulationService.java`、`FluidWorldTargetResolver.java` | 服务层每域先过 availability；目标解析只读世界与区域，不提交事务；不把世界事务下沉到 solver |
| 持久区域管理 | `FluidRegionOperations.java`、`FluidRegionPersistence.java` | checkpoint 成功后才释放；当前功能布局必须精确匹配；committed metadata 先登记再做 runtime admission；不改写不匹配载荷 |
| 区域身份与生命周期 | `FluidRegion.java`、`FluidRegionLifecycle.java`、`ThermalActivity.java`、`api/.../RegionAvailabilityReason.java` | operator intent 与封闭 availability reason 正交；UNLOADED 仍占预算；motion/thermal 调度不改变库存 |
| 区域登记与预算 | `FluidRegionManager.java` | 完整 candidate 可只读预检预算/overlap；首个 HEAD 成功前不得登记；已提交 metadata 不因当前政策预算被丢弃 |
| 世界配置 | `FluidServerConfig.java` | 只接受当前完整配置；接入政策与预算在启动时冻结；未知 key 直接拒绝，旧 schema 的世界 LOCKED |
| registry snapshot codec | `MaterialRegistryCodec.java`、`ChemicalRegistryCodec.java`、`GranularRegistryCodec.java`、`GasRegistryCodec.java`、`ThermalRegistryCodec.java`、`StrictNbtFields.java` | 只做规范编解码，没有自己的世界目录；compound 字段集合和类型必须精确；`snapshotBytes`/`writeSnapshot`/`readSnapshot` 是唯一入口，能力包的成员字节与校验和必须共享它 |
| 世界能力包 | `simulation/persistence/WorldCapabilityBundleStore.java`、`WorldCapabilityStartup.java`、`WorldCapabilityBundleHead.java`、`WorldCapabilityBundleManifest.java`、`WorldCapabilityBundleMetadataCodec.java`、`WorldCapabilityProfileCodec.java`、`core/.../worldprofile/` | HEAD rename 是唯一 commit point；成员字节取自各 registry codec 的 `snapshotBytes`；`inspect` 只看目录名不解码载荷；四种 presence 与四种启动状态各自只有一种可证明的处置；staging 只 quarantine 不删除；恢复不查定义源；每个发布边界都有故障注入点；权威域只有 `container-transfer`/`ownership`/`region-state`，per-kind registry 目录是未知条目并 LOCKED |
| 世界碰撞 | `MinecraftCollisionWorldPort.java`、`FluidRegionWorldInteraction.java`、`core/.../world/` | Minecraft shape 先冻结为纯 Java `CollisionField`；挤水只按 marker 账本 |
| 实体流体交互 | `EntityMotionTracker.java`、`MinecraftEntityFluidInteraction.java`、`FluidRegionLifecycle.java`、`core/.../fluid/FlipFluidDomain.sampleAxisAlignedBody`、`core/.../world/MovingBodyFluidCoupling.java`、`FlipFluidDomain.applyMovingBodyCoupling` | `entity-fluid`；`EntityMotionTracker` 跨相邻服务器观测冻结本 tick 实际位移，不用 `Entity#oldPosition`；Fabric 每 tick 冻结实体 AABB、原版响应速度和该位移，core 按派生水平液层与 cut-micro geometry 连续积分湿体积；只有到期 motion step 消费一次实际位移速度的 liquid request；core 回报实际合计冲量 |
| controller footprint | `simulation/controller/` | 非加载读取；破口、缺 chunk、多内腔、重叠或超预算拒绝 |
| 区域 checkpoint | `simulation/persistence/`、`RegionStateLayout.java`、`GenerationSequence.java` | 双槽完整 generation、语义 layout、CRC、HEAD 最后发布；所有 WAL/HEAD 紧邻代次用统一 overflow-safe 检查；atomic rename/delete 后同步目录 entry |
| takeover | `TakeoverTransaction.java`、`TakeoverBackupStore.java` | journal/WARNING 完成前禁止世界修改 |
| ownership | `simulation/ownership/`、`FluidRegionPersistence.java` | WAL→WARNING→世界→region HEAD→committed；未知状态保留并锁定 |
| 容器事务 | `core/.../transfer/ContainerTransferActor.java`、`ContainerTransferJournal.java`、`fabric/.../simulation/ControllerItemTransferCoordinator.java`、`MinecraftContainerActorAccess.java`、`ContainerTransferItems.java`、`ContainerTransferStore.java`、`ContainerTransferRecovery.java`、`FluidTransferService.java` | coordinator 独占 WAL→staged HEAD→actor 收敛顺序；journal 与 actor identity 是 loader-neutral 数据；Minecraft actor 定位、冲突分类、NBT/ItemStack 映射与 durable save 只在 Fabric adapter；actor durable save 前不得删 WAL |
| typed 容器真值 | `core/.../transfer/`、`fabric/.../item/ContainerItemStacks.java` | 体积/质量/species/SVQ/U、world profile、actor identity 与 journal 的纯 Java 真值不引用 Minecraft；actual 非空容器必须绑定 committed profile；`ItemStack` component 编解码只留在 Fabric adapter；持久编码不携带 runtime handle |
| 内置 field probe | `item/FieldProbeItem.java`、`simulation/ShellFluidQuery.java`、`core/.../fluid/CellCarrierInventoryView.java`、`FluidWorldTargetResolver.java` | 壳面两侧 owner 必须唯一；液体/carrier identity 同代同格；Top-N 有界；不加载、不唤醒、不 checkpoint、不建派生拓扑 |
| controller console | `core/.../controller/`、`network/Controller*Payload.java`、`client/ControllerConsoleScreen.java`、`FluidSimulationService.controllerSummary` | server-authored bounded summary；UNLOADED 不发明 live inventory；动作 token 一次性且 player+region 隔离；每次动作重验距离/owner/权限；只含 select/pause/resume/availability refresh |
| fixed thermal reservoir 调节 | `thermal/ThermalReservoirSummary.java`、`WorldPositionActionSessions.java`、`network/ThermalReservoir*Payload.java`、`client/ThermalReservoirScreen.java` | setpoint 限于 committed thermal registry 公共有效域；creative-only；一次性 player+world-position token；只改未来 boundary flux，不直接改 region U |
| 活动求解器 | `core/.../fluid/FlipFluidDomain.java` | CPU 规范实现；mechanics→conduction→reaction→phase→granular→reseed |
| 后端完整候选 | `FluidDomainState.java`、`FluidStepRequest.java`、`FluidStepCandidate.java`、`FluidSolverBackend.java`、`fabric/.../simulation/FluidSimulationService.stepMotionOnce`、`FluidRegion.adoptStepCandidate` | 后端只计算私有完整后继并回报实际 moving-body 液体冲量；输入有硬上限；不持有世界、actor、HEAD 或提交权；生产 motion step 与 replay 同路，校验通过后才原子替换 live solver，候选失败不改动 live domain |
| 自由面/压力 | `FlipPressurePhaseGeometry.java`、`FlipFreeSurfaceGeometry.java`、`FlipVolumeDriftCorrection.java`、`FlipVelocityExtrapolator.java` | 守恒 marker footprint 决定 pressure component；含真实 marker 的 pressure cell 使用 exact PLIC，footprint-only cell 使用重建 fallback；贴壁 marker 低占位格不降成 air fringe；carrier face 与库存仍取实际 marker；PCG 使用绝对 RMS 容差，未收敛候选安全暂停 |
| cut-cell/CCD | `FlipCutCellGeometry.java`、`FlipParticleCollision.java` | pressure 与碰撞共享 aperture；checkpoint/handoff 保留 raw micro mask；只截断命中法向 |
| reseed | `FlipParticleReseeder.java` | 保持体积、质量、动量、质心和相身份 |
| 材料与单位边界 | `FluidMaterial*.java`、`BuiltinFluidMaterials.java`、`units/SolverUnitScale.java` | marker 体积与 solver mass 独立；SI/容器只在统一边界换成 m³/kg；stable ID 上链，runtime handle 不上链 |
| 气体 | `fluid/air/`、`GasRegistryCodec.java`、`AirCavityScanner.java`、`GasLiquidPhaseModel.java` | component nanos/kg/species/source/sink 闭合；gas mass/residual 均为 kg；component-local allowed species 由 world registry 冻结并在所有发布入口验证；热区域持久拓扑同代 |
| 化学 | `chemistry/`、`FlipChemicalParticleState.java`、`units/SolverUnitScale.java` | exact species/组成/电荷/kg 残差；indicator provenance 不隔离反应 |
| 颗粒 | `granular/`、`GranularCouplingFields.java` | SVQ、kg 质量、组成独立；active/deposit/coherent 不混用 |
| 热学 | `thermal/`、`FlipThermalState.java`、`FlipThermalParticleState.java` | 总 U 权威；温度派生；`T_eq(p)` curve 是唯一压力域/平衡真值；超域暂停；exact phase batch 选择最近可表示平衡；通量、反应热、相变、功联合审计 |
| 有限热设备 | `FiniteThermalBodyState.java`、`FiniteThermalDeviceState.java`、`FiniteThermalDeviceInventory.java`、`FiniteThermalDeviceAudit.java`、`FiniteThermalExchange.java`、`ThermalCellGrid.java`、`FlipThermalState.java`、`FlipFluidDomain.java`、`FluidRegion.java`、`FiniteThermalDeviceCodec.java`、`RegionState.java` | provider/object 只是稳定 locator；DEVICE material + kg + U + generation 由 region HEAD extension 持有；同 region body/cell candidate 预检后一次内存提交并在下一 HEAD 联合发布，solver/device 各自审计 exchange 与 roundoff；跨 region 不走此路径 |
| 客户端快照 | `FluidSnapshotPublisher.java`、`fabric/.../network/FluidProtocol.java`、`fabric/src/client/java/cc/ecisr/mmm/client/` | publisher 只构造/广播派生载荷；语义 layout 精确协商；不得反写物理或恢复库存 |
| 外部 Query API | `api/.../query/`、`FluidCellInventoryView.java`、`FabricFluidQueryApi.java`、`api/fabric/MmmFabricApi.java` | server-thread only；不加载、不唤醒、不建网格、不返回 live domain |
| 外部 committed event | `api/.../event/`、`CommittedEventHub.java`、`api/fabric/MmmFabricApi.java` | 只在 durable commit 后发布；监听器异常隔离；事件不是库存日志 |
| Jade 观察适配 | `fabric/src/main/java/cc/ecisr/mmm/compat/jade/`、`fabric/src/client/java/cc/ecisr/mmm/compat/jade/` | Jade 保持可选；服务端只调 Query API；展示摘要有界且不可回写；改依赖/API 后编译 main 与 client |
| 命令审计 | `logging/` | UTF-8 观测；记录失败不能改变命令结果 |

## 高频定位

- 精确水量丢失：`setSolid`、marker create/remove、transfer primitive、`escapedSink`。
- 压力/散度：`solvePressure`、`applyPressureMatrix`、`projectVelocity`。
- 小水滴慢落：`FlipVelocityExtrapolator`、`trilinearValid`。
- 水量正常但液面下降：drift correction、reseed 分布；禁止 renderer 增厚或强制找平。
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
```

| 改动 | selector | 追加条件 |
|---|---|---|
| marker 材料/质量/材料界面 | `materials` | 同时改存档或协议才追加最近门 |
| 表面张力/润湿 | `surface-tension` | 同时改材料状态追加 `materials` |
| 气体库存/EoS/孔流 | `air-network` | 改 pressure 消费运行 `gas-liquid` |
| cavity 扫描/几何边 | `air-cavity` | 改共享 cut-face 追加 `cut-cell` |
| 活动气液耦合 | `gas-liquid` | 显式选择包含完整平衡门；同时固定有限密闭库存零容量时 typed rejection 且提交前 inventory/audit 原样保留 |
| 密闭 partial gas owner / `empty` 后边界注液 | `controller-gas-lineage` | 以 production liquid/gas registry 复现 `34 m³std → empty → 16 BV`，连续 200 个 backend candidate/capture/restore；改 scanner 几何时先跑 `air-cavity`，改压力消费时再跑 `gas-liquid` |
| 默认 controller 单步 CPU 硬预算 | `production-live-budget` | 20 个 live logical step 各自不超过 40 ms；逐步按生产顺序计时（capture → 完整 candidate → restore），不能用 pacing 或关闭物理绕过；同形状的 10 步预热另报，必须保留逐步 marker、substep、PCG 和 residual 证据 |
| manager | `region-manager` | 跨 lifecycle/存档追加最近门 |
| lifecycle/tick | `lifecycle` | 覆盖 PAUSED/跳过/due tick 的 motion-input exactly-once 调度；同时覆盖后端候选的紧邻 generation/backend id/布局校验与原子替换；同时改 ownership 才追加 `ownership` |
| 实体 moving-body 接线 | `entity-fluid` | 覆盖跨 tick 位移观测、bodyMotion→request 转换与预算；同时覆盖静态/动态实体不放大液面，以及走动实体必须产生可观察液体响应并在静止后衰减回静止包络 |
| controller 注液/生产 step/权威 handoff/逐帧预算 | `production-replay` | 默认 `8×8×8` 闭壳注入 16 BV，JVM 预热后每个 live step 不超过 40 ms；另以 `(5,0,4)+16 BV`、40 步后 `(3,3,5)+14 BV` 的非平面注液轨迹建立两条 1000 步门：隔离 gas 的门约束尾部/终值动能及 exact 体积/质量；正式 thermal/gas/phase 定义的门每步经过 backend capture/restore，要求初始 `64 m³std` 库存在两次注液后 source/sink 不变、容量依次为 `48/34 m³`、总 `U` 闭合且压力逐 node 满足 inventory/volume/temperature EoS。thermal-off 的恢复/清空路径另要求 `34 m³std` 库存经 `empty` 扩容到 `64 m³`、边界注入 16 BV 后在 `48 m³` 容量内连续通过 200 个 backend candidate/restore，期间 partial gas owner、source/sink 与总库存不变。正式半满状态还要求 capture/restore、phase-off backend candidate/restore 等价，并要求独立预热后新建 domain 的首个 phase-on production step 在 40 ms 内闭合 gas、pending volume 与总 `U`；环境初始化和先空腔后注液压缩的平面自由面分别连续执行 500/1000 次 phase-off backend candidate/restore，以 opt-in mechanical audit 约束动能峰值/尾部/终值、质心漂移、体力-pressure 功残差、gas lineage 和总 `U`；同一平面场景还以 500 个 candidate 约束静止玩家尺寸 kinematic boundary 不放大动能，并核对液体/外部约束反作用冲量；phase-off backend candidate 的复制耗时仅作诊断，phase-on 长跑会引入真实非平衡相变/自然对流，不能冒充纯机械平衡门 |
| checkpoint/配置/恢复 | `region-state` | 自动门无法覆盖事务时才做真实重启；world capability bundle 的发布/恢复/故障注入同属此门，不为它新增 selector |
| protocol/snapshot | `protocol` | 改注册入口才启动专服 |
| field probe/controller console/thermal setpoint payload | `protocol` | 改 carrier 观察追加对应 `granular`/`air-network`/`thermal`；改 summary lifecycle 追加 `lifecycle` |
| controller/footprint | `boundary` | 改预算追加 `region-manager` |
| pressure/free-surface/MAC/drift | `free-surface` | 改 marker 分布追加 `reseeding` |
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
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat clean assemble compileTestJava --offline --no-daemon
```

- mods 目录：`Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\mods`。
- 同一 Mod id 只保留一个启用 JAR；复制后比较 SHA-256。
- 只有视觉、真实 `VoxelShape` 或真实世界事务无法由自动门观察时才请求进游戏，并给出单一场景、命令、预期和日志证据。

## GPU probe

`gpu-probe` 不修改世界或存档，当前主 Mod 仍以 CPU 为规范后端。入口位于 `gpu-probe/src/main/java/cc/ecisr/mmm/gpuprobe/`；改 descriptor/buffer/stage 时同步 Java binding、shader 和 readback。设备性能使用 Vulkan timestamp，调度与超时仍使用 wall time。生产 GPU 后端必须经 `core` 的不可变输入/完整 candidate 边界接入，不能取得世界或持久化提交权。
