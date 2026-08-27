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
| `/mmm` 命令 | `MmmCommands.java` | 状态操作留在服务层；完整输出写 transcript |
| Minecraft 编排 | `fabric/.../simulation/FluidSimulationService.java`、`FluidWorldTargetResolver.java` | 服务层每域先过 availability；目标解析只读世界与区域，不提交事务；不把世界事务下沉到 solver |
| 持久区域管理 | `FluidRegionOperations.java`、`FluidRegionPersistence.java` | checkpoint 成功后才释放；profile/layout/aggregate/material palette 必须精确匹配；不改写不匹配载荷 |
| 区域 identity/availability | `api/.../RegionAvailabilityReason.java`、`FluidRegion.java`、`FluidRegionLifecycle.java`、`ThermalActivity.java` | reason 封闭且可 Query；availability 不覆盖 operator intent；motion/thermal 调度不改变库存 |
| 区域登记与预算 | `FluidRegionManager.java` | 完整 candidate 可只读预检预算/overlap；首个 HEAD 成功前不得登记；合法 committed metadata 即使越过运行预算也保留 reservation |
| 世界运行策略 | `FluidServerConfig.java` | 只含 controller/worldbridge admission、quarantine 与运行预算；不得定义 inventory capability/layout |
| world capability 启动/单提交点 | `simulation/persistence/WorldCapabilityStartup.java`、`WorldCapabilityBundleStore.java`、`WorldCapabilityLayoutResolver.java` | profile 与五 registry 同 slot、HEAD 最后；已有 HEAD 只从 slot 恢复；非 READY 不产生新权威数据 |
| world-local registry snapshot codec | `MaterialRegistryStore.java`、`ChemicalRegistryStore.java`、`GranularRegistryStore.java`、`GasRegistryStore.java`、`ThermalRegistryStore.java`、`simulation/persistence/DurableFileOperations.java`、`StrictNbtFields.java` | 只由 bundle store 统一发布；compound 字段集合和类型必须精确；未知内容保留并锁定 |
| 世界碰撞 | `MinecraftCollisionWorldPort.java`、`FluidRegionWorldInteraction.java`、`core/.../world/` | Minecraft shape 先冻结为纯 Java `CollisionField`；挤水只按 marker 账本 |
| 实体流体交互 | `MinecraftEntityFluidInteraction.java`、`MovingBodyDataPackLoader.java`、`core/.../world/MovingBodySurfaceSnapshot.java`、`core/.../fluid/MovingBodyBoundary.java`、`FluidRegion.prepareStepCandidate` | Fabric 冻结旧/当前 AABB 与 identity；core 联合 occupancy/pressure/viscosity/CCD；主线程 raw-bit 复核后联合提交或回滚 |
| controller footprint | `simulation/controller/` | 非加载读取；破口、缺 chunk、多内腔、重叠或超预算拒绝 |
| 区域 checkpoint | `simulation/persistence/`、`RegionStateLayout.java`、`GenerationSequence.java` | 双槽完整 generation、语义 layout、CRC、HEAD 最后发布；所有 WAL/HEAD 紧邻代次用统一 overflow-safe 检查；atomic rename/delete 后同步目录 entry |
| 危险 lifecycle | `core/.../controller/LifecycleOperation*.java`、`FluidSimulationService.java`、`LifecycleOperation*Payload.java`、`client/.../LifecycleConfirmationScreen.java`、`RemovedRegionManifest.java`、`RegionStateFiles.java` | preview 绑定玩家/generation/权限/WAL/availability；确认后只走 candidate/WAL/HEAD；REMOVED HEAD 后注销 runtime 并收敛只读 quarantine；controller 方块破坏不得旁路 |
| 跨域恢复代数 | `core/.../recovery/CrossDomainRecovery.java`、各 journal adapter | HEAD 只认 base 或 checksum 匹配的 target；外部成员逐一分类 BEFORE/AFTER/THIRD/UNAVAILABLE；base 只接受全 BEFORE，target 只收敛 BEFORE；UNAVAILABLE 只由明确加载事件重试 |
| takeover | `TakeoverTransaction.java`、`TakeoverBackupStore.java` | journal/WARNING 完成前禁止世界修改 |
| ownership | `simulation/ownership/`、`FluidRegionPersistence.java` | WAL→WARNING→世界→region HEAD→committed；未知状态保留并锁定 |
| 容器事务 | `core/.../transfer/ContainerTransferActor.java`、`ContainerTransferJournal.java`、`fabric/.../simulation/ControllerItemTransferCoordinator.java`、`MinecraftContainerActorAccess.java`、`ContainerTransferItems.java`、`ContainerTransferStore.java`、`ContainerTransferRecovery.java`、`FluidTransferService.java` | coordinator 独占 WAL→staged HEAD→actor 收敛顺序；journal 与 actor identity 是 loader-neutral 数据；Minecraft adapter 分类 actor truth、应用 after、durable save 并 exact-after 回读；回读完成前不得删 WAL |
| typed 容器与创造 source | `core/.../transfer/`、`fabric/.../item/ContainerItemStacks.java`、`CreativeSourceItem.java`、`simulation/ContainerTransferItems.java`、`CreativeSourceCatalog.java` | actual 体积/质量/species/SVQ/U/profile 与 descriptor 意图严格分离；已装填 actual 只接受当前 committed profile，source 只由服务端 world registry 解析并走既有 WAL/HEAD；持久编码不携带 runtime handle |
| 活动求解器 | `core/.../fluid/FlipFluidDomain.java` | CPU 规范实现；mechanics→conduction→reaction→phase→granular→reseed |
| 后端完整候选 | `FluidDomainState.java`、`FluidStepRequest.java`、`FluidStepCandidate.java`、`FluidSolverBackend.java` | 后端只计算私有完整后继；moving-body 输入有硬上限；不持有世界、actor、HEAD 或提交权 |
| 自由面/压力 | `FlipFreeSurfaceGeometry.java`、`FlipVolumeDriftCorrection.java`、`FlipVelocityExtrapolator.java` | marker count 决定压力相；平滑 volume 不结算库存 |
| cut-cell/CCD | `FlipCutCellGeometry.java`、`FlipParticleCollision.java` | pressure 与碰撞共享 aperture；checkpoint/handoff 保留 raw micro mask；只截断命中法向 |
| reseed | `FlipParticleReseeder.java` | 保持体积、质量、动量、质心和相身份 |
| 材料与单位边界 | `FluidMaterial*.java`、`BuiltinFluidMaterials.java`、`units/SolverUnitScale.java` | marker 体积与 solver mass 独立；SI/容器只在统一边界换成 m³/kg；stable ID 上链，runtime handle 不上链 |
| 气体 | `fluid/air/`、`GasRegistryStore.java`、`AirCavityScanner.java`、`GasLiquidPhaseModel.java` | component nanos/kg/species/source/sink 闭合；gas mass/residual 均为 kg；component-local allowed species 由 world registry 冻结并在所有发布入口验证；热区域持久拓扑同代 |
| 化学 | `chemistry/`、`FlipChemicalParticleState.java`、`units/SolverUnitScale.java` | exact species/组成/电荷/kg 残差；indicator provenance 不隔离反应 |
| 颗粒 | `granular/`、`GranularCouplingFields.java` | SVQ、kg 质量、组成独立；active/deposit/coherent 不混用 |
| 热学 | `thermal/`、`FlipThermalState.java`、`FlipThermalParticleState.java` | 总 U 权威；温度派生；`T_eq(p)` curve 是唯一压力域/平衡真值；超域暂停；exact phase batch 选择最近可表示平衡；通量、反应热、相变、功联合审计 |
| 有限热设备 | `FiniteThermalBodyState.java`、`FiniteThermalDeviceState.java`、`FiniteThermalDeviceInventory.java`、`FiniteThermalDeviceAudit.java`、`FiniteThermalExchange.java`、`ThermalCellGrid.java`、`FlipThermalState.java`、`FlipFluidDomain.java`、`FluidRegion.java`、`FiniteThermalDeviceCodec.java`、`RegionState.java` | provider/object 只是稳定 locator；DEVICE material + kg + U + generation 由 region HEAD extension 持有；同 region body/cell candidate 预检后一次内存提交并在下一 HEAD 联合发布，solver/device 各自审计 exchange 与 roundoff；跨 region 不走此路径 |
| 客户端快照 | `FluidSnapshotPublisher.java`、`fabric/.../network/FluidProtocol.java`、`FluidProtocolOfferPayload.java`、`fabric/src/client/java/cc/ecisr/mmm/client/` | offer 携带 service-owned profile fingerprint/layout/capability；publisher 只广播派生载荷；不得反写物理或恢复库存 |
| 外部 Query API | `api/.../query/`、`FluidCellInventoryView.java`、`FabricFluidQueryApi.java`、`api/fabric/MmmFabricApi.java` | server-thread only；不加载、不唤醒、不建网格、不返回 live domain |
| 外部 committed event | `api/.../event/`、`CommittedEventHub.java`、`api/fabric/MmmFabricApi.java` | 只在 durable commit 后发布；监听器异常隔离；事件不是库存日志 |
| Jade 观察适配 | `fabric/src/main/java/cc/ecisr/mmm/compat/jade/`、`fabric/src/client/java/cc/ecisr/mmm/compat/jade/` | Jade 保持可选；服务端只调 Query API；展示摘要有界且不可回写；改依赖/API 后编译 main 与 client |
| 主 Mod field probe | `item/FieldProbeItem.java`、`simulation/ShellFluidQuery.java`、`FluidWorldTargetResolver.java`、`core/.../fluid/CellCarrierInventoryView.java` | 服务端壳面解析必须唯一；普通使用观察内侧格，潜行使用以 cell-boundary DDA 找首个凝聚相格；液相消费 Query，solid/gas 只读同一 cell 权威载体；固定条目上限；不得读取 mesh、加载、建网格或唤醒区域；最近门 `protocol`，改 carrier 追加直接领域门 |
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
- REMOVED 收敛：`region-state/<uuid>/HEAD.nbt` 与 `quarantine/removed-region/<uuid>/<generation>/quarantine.nbt` 必须绑定同一 tombstone；不要手删旧 payload 或把 quarantine 搬回 ACTIVE slot。

## CPU 验证

所有门由一个 runner 执行。小改动只选表中最近 selector，不累计其他领域：

```powershell
./gradlew.bat runCpuValidation --offline --no-daemon
./gradlew.bat runCpuValidation '-PcpuValidation=region-state,containers' --offline --no-daemon
./gradlew.bat runCpuValidation -PcpuValidation=milestone --offline --no-daemon
./gradlew.bat runCpuValidation -PcpuValidation=soak --offline --no-daemon
./gradlew.bat runCpuValidation -PcpuValidation=list --offline --no-daemon
```

| 改动 | selector | 追加条件 |
|---|---|---|
| marker 材料/质量/材料界面 | `materials` | 同时改存档或协议才追加最近门 |
| 表面张力/润湿 | `surface-tension` | 同时改材料状态追加 `materials` |
| 气体库存/EoS/孔流 | `air-network` | 改 pressure 消费运行 `gas-liquid` |
| cavity 扫描/几何边 | `air-cavity` | 改共享 cut-face 追加 `cut-cell` |
| 活动气液耦合 | `gas-liquid` | 显式选择包含完整平衡门 |
| manager | `region-manager` | 跨 lifecycle/存档追加最近门 |
| lifecycle/tick | `lifecycle` | 同时改 ownership 追加 `ownership` |
| checkpoint/配置/恢复 | `region-state` | 自动门无法覆盖事务时才做真实重启 |
| protocol/snapshot | `protocol` | 改注册入口才启动专服 |
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
