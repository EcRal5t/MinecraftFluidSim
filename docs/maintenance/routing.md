# Real Water 维护路由

本文只回答“代码在哪里、改完默认跑什么”。当前流体架构见 [`../architecture/fluid-solver.md`](../architecture/fluid-solver.md)，完成史见 [`../progress/README.md`](../progress/README.md)，未来路线见 [`../roadmap/product-roadmap.md`](../roadmap/product-roadmap.md)。

## 不可混用的状态

- 物理真值：marker `particleVolume` 精确和。
- 数值离散：`markerCount` 压力相、平滑 `volume[]`、PLIC、cut-cell 和压力目标。
- 渲染重建：density + marker + solid，只读消费服务端快照。
- 调度策略：ACTIVE/REDUCED/休眠/UNLOADED 与 CPU wall-time pacing。
- 世界事务：瞬态 takeover 与持久 controller ownership 都只在各自 journal/WARNING 原子发布后修改原版方块；region/container 转移另由独立 WAL 跨持久域收敛。

## 主 Mod 代码职责

| 职责 | 首要入口 | 必须保持 |
|---|---|---|
| 初始化、tick、网络与启动恢复 | `src/main/java/com/example/RealWaterMod.java` | 生命周期和 payload 注册成套 |
| `/realwater` 命令 | `src/main/java/com/example/RealWaterCommands.java` | 状态操作留在服务层；完整输出写 transcript |
| 多区域 Minecraft 编排和 takeover | `src/main/java/com/example/simulation/FluidSimulationService.java` | 每域先通过 availability；takeover 事务不下沉到 solver |
| checkpoint/UNLOADED/删除操作 | `src/main/java/com/example/simulation/FluidRegionOperations.java` | 先发布成功再释放；恢复保持暂停并刷新当前碰撞 |
| 世界碰撞与实体采样 | `src/main/java/com/example/simulation/FluidRegionWorldInteraction.java` | `VoxelShape` 保守采样；挤水只按 marker 账本；实体单向耦合 |
| 域构造与只读原版水采样 | `src/main/java/com/example/simulation/FluidRegionFactory.java` | marker 上限等于 manager 预留；不从 density 结算 |
| 区域身份、solver ownership、运行态 | `src/main/java/com/example/simulation/FluidRegion.java` | UNLOADED 不持 solver 但仍占预算；恢复先刷新碰撞 |
| 调度生命周期 | `src/main/java/com/example/simulation/FluidRegionLifecycle.java` | 调度状态不得移动、创建、删除或缩放 marker |
| 区域登记、选择、空间与预算 | `src/main/java/com/example/simulation/FluidRegionManager.java` | 拒绝不替换旧域；报告 required/per-region/remaining |
| 世界配置 | `src/main/java/com/example/simulation/FluidServerConfig.java` | v1–v5 配置 fail-closed；旧 reader 强制未知新门关闭；chemistry/granular/precipitation 依赖链严格；预算与各功能门重启后生效 |
| controller 持久化协调 | `src/main/java/com/example/simulation/FluidRegionPersistence.java` | manifest admission、checkpoint、quarantine 与 takeover journal 分离；按功能门严格分派 v5/v3/v2/v1，v4 保留并拒绝 |
| `region-state v1`/门控 v2/v3/v5 | `src/main/java/com/example/simulation/persistence/`、`docs/architecture/region-state-v1.md`、`docs/architecture/chemistry-state.md`、`docs/architecture/granular-state.md` | 两槽完整代次、CRC、账本和 HEAD 最后发布；discovery 校验完整 committed generation；v1/v2/v3 canonical bytes 不变；v5 是含 required typed granular extension 的完整联合代次，缺 authoritative registry 时锁定；v4 仅为气体评审预留 |
| M3 跨层迁移边界 | `docs/architecture/m3-material-state-migration.md` | 单一 `materialsEnabled` 门；network v2 与 region-state v2 独立版本；运行时 handle 不上链 |
| 控制器与边界块 | `src/main/java/com/example/block/RealWaterBlocks.java`、`src/main/java/com/example/block/FluidControllerBlock.java` | feature flag 默认关闭；只读激活；资源同步 |
| 闭合边界扫描 | `src/main/java/com/example/simulation/controller/` | 非加载读取；缺 chunk/破口/多内腔/超预算拒绝或整域暂停 |
| 活动 PIC/FLIP 编排 | `src/main/java/com/example/fluid/FlipFluidDomain.java` | 唯一活动核心；旧 `FluidDomain` 不接回 Minecraft |
| 自由面几何 | `src/main/java/com/example/fluid/FlipFreeSurfaceGeometry.java` | exact per-cell marker 和构造分数；`markerCount` 仍定压力相 |
| MAC 空气外推 | `src/main/java/com/example/fluid/FlipVelocityExtrapolator.java` | 开放压力面播种；G2P 不采未定义空气零值 |
| marker 分布压力约束 | `src/main/java/com/example/fluid/FlipVolumeDriftCorrection.java` | P2G 只生成目标散度，不结算或找平精确水量 |
| M5 气体网络引擎 | `src/main/java/com/example/fluid/air/AirNode.java`、`src/main/java/com/example/fluid/air/AirEdge.java`、`src/main/java/com/example/fluid/air/AirNetwork.java` | 纳体积量子精确库存，`sealed + vented == drawn` 恒等；EoS/孔流只是派生；runtime-only，不是协议或存档 |
| M5 空腔拓扑扫描 | `src/main/java/com/example/fluid/AirCavityScanner.java` | 预算内 flood fill；触达边界层或超预算即 ambient，绝不保存为有限密闭库存；部分开口面成为几何孔流边 |
| runtime cut-cell | `src/main/java/com/example/fluid/FlipCutCellGeometry.java` | 散度、矩阵、预条件器和投影共享 face aperture |
| marker CCD | `src/main/java/com/example/fluid/FlipParticleCollision.java` | 只截断命中法向，切向自由 |
| 守恒 reseeding | `src/main/java/com/example/fluid/FlipParticleReseeder.java` | merge/split 保持体积、质量、动量、质心和硬预算；不跨 material/phase |
| marker 内存交接 | `src/main/java/com/example/fluid/FlipParticleState.java`、`src/main/java/com/example/fluid/FlipMaterialParticleState.java` | 纯水与多材料类型分离；都不是协议或存档格式 |
| solver 参数 | `src/main/java/com/example/fluid/FluidSolverConfig.java` | CFL、压力容差、FLIP 比例或时间步只属数值离散；改动选择对应 solver 门 |
| M3 材料与 CPU 门 | `src/main/java/com/example/fluid/FluidMaterial.java`、`src/main/java/com/example/fluid/FluidMaterialRegistry.java`、`src/main/java/com/example/fluid/BuiltinFluidMaterials.java`、`src/main/java/com/example/fluid/FluidMaterialRenderSnapshot.java`、`src/main/java/com/example/fluid/FluidMaterialSurfaceMesher.java`、`src/test/java/com/example/fluid/M3ConnectedVesselExperiment.java`、`src/test/java/com/example/fluid/M3MaterialTransferValidation.java`、`src/test/java/com/example/fluid/M3MaterialDynamicsValidation.java`、`src/test/java/com/example/fluid/M3MaterialSurfaceValidation.java` | snapshot/mesh 只读重建，量化字段不结算库存；M6 容器可向 controller 区域转移已注册材料，但 ownership acquire 与瞬态 takeover 仍只采集纯原版水 |
| takeover 事务 | `src/main/java/com/example/simulation/TakeoverTransaction.java`、`src/main/java/com/example/simulation/TakeoverBackupStore.java` | journal/WARNING 完成前禁止世界修改 |
| M6 世界 ownership 事务 | `src/main/java/com/example/simulation/ownership/`、`src/main/java/com/example/simulation/FluidRegionPersistence.java`、`docs/architecture/world-ownership-and-containers.md` | prepare→WAL→WARNING→世界修改→region HEAD 最后发布→committed 重建；恢复按 region HEAD 对比 WAL base/target 判定；首次/失败 staging 只清理自有暂存，unknown/corrupt/身份失配锁定；已加载区域也必须在 step/empty/checkpoint 前检查 per-region sidecar；玩家方块优先；不碰 takeover-journal v1 |
| M6 容器纳体积账本 | `src/main/java/com/example/item/ContainerFluidLedger.java` | 容器真值是 long 纳体积（1 BV=1e9）；换算半量子内舍入、漂移有界并记录，不用 damage/density 结算 |
| M6 精确转移原语 | `src/main/java/com/example/fluid/FlipFluidDomain.java` 的 `tryInsertExactVolume`/`tryExtractExactVolume`、`tryInsertExactMaterial`、`tryInsertExactMixture`/`tryExtractExactMixture` | all-or-nothing；insert 先规划后落子、refuse 零残留；extract 双精度预检+整 marker+尾拆分；逐材料体积和独立质量都必须闭合，服务层再换算纳体积量子 |
| M6/M8 容器物品/组件 | `src/main/java/com/example/item/RealWaterComponents.java`、`ContainerFluidLedger.java`、`FluidMixture.java`、`GranularContents.java`、`PartialWaterBucketItem.java`、`MaterialBucketItem.java`、`GranularContainerItem.java`、`RealWaterItems.java` | 液体纳体积、固体 SVQ、raw mass 与 optional solid composition 是独立真值；液体/颗粒表示互斥；registry stable ID/fingerprint 入 item；tooltip/durability bar 只读；干式容器一次只容纳 1 BV skeleton |
| M6 部分桶合并配方 | `src/main/java/com/example/item/RealWaterRecipes.java`、`src/main/resources/data/realwater/recipe/partial_water_bucket_merge.json` | 动态配方：先填满一桶，溢出作为 recipe remainder 留在第二输入；精确账本由 `ContainerFluidLedger.mergeOutput/mergeRemainder` 结算 |
| M6/M8 容器入口（玩家/发射器） | `src/main/java/com/example/simulation/FluidTransferService.java`、`src/main/java/com/example/mixin/ItemStackMixin.java`、`RealWaterMod.java` 的 dispenser 注册 | worldBridge 门控；只按准星/发射方向命中的实际区域路由；液体按 primitive 实际 marker delta 结算，颗粒只在显式 controller 内按 exact SVQ ledger 全量注入/有界舀取；提交前验证 item 可表示性；堆叠/损坏/双表示安全拒绝；只有创造放出记 external source，scoop 不制造隐藏 sink |
| M6/M7/M8 容器转移 WAL | `src/main/java/com/example/simulation/ContainerTransferJournal.java`、`ContainerTransferStore.java`、`ContainerTransferRecovery.java`、`FluidTransferService` 的 controller 路径 | PREPARED 携带请求上限→solver 修改→stage/verify 完整 region generation→REGION_COMMITTED 携带 typed 实际量和 after/checksum→HEAD→actor after durable save→清理；v4 用独立 `FLUID_NANOS`/`GRANULAR_SVQ` 字段，v1–v3 reader/recovery 保持；registry/gate/identity/actor/HEAD 任一歧义保留并锁定 |
| M7 化学真值与定义 | `src/main/java/com/example/chemistry/`、`docs/architecture/chemistry-state.md` | exact rational 定义、stable-ID canonical registry、packed parcel、逐 species/组成/charge/mass 守恒和全部硬预算；reaction 保留 marker residual，完整 transfer/audit 与 parcel/bucket 小预算分离；不得借 display 值结算 |
| M7 marker/存档/容器接入 | `FlipChemicalParticleState`、`ChemicalContents`、`FluidTransferService`、`ContainerTransferStore`、`RegionState` | indicator provenance 不隔离反应；只有创造放出记 external source；legacy/chemical item 表示互斥；chemical v3 WAL 在 gate/registry identity 不明时保留，actor durable save 前不得删除 |
| M7 protocol/玩法 | `FluidChemicalSnapshotPayload`、`FluidChemicalSnapshotLedger`、`ChemicalIndicatorItem`、`RealWaterCommands` | protocol v3 只发 Top-4/pH/indicator 展示；指示剂/抹除器是不消耗 operator 工具并按 cut-face/phase 全量 plan；`chemstatus` 完整库存只供管理员并写 transcript |
| M8 颗粒定义、库存与世界 registry | `src/main/java/com/example/granular/`、`data/*/realwater/particulate_materials`、`data/*/realwater/granular_carriers`、`data/*/realwater/solid_liquid_reactions`、`docs/architecture/granular-state.md` | strict data-pack definition 只用于首次 world snapshot；store v3 冻结 stable-ID/fingerprint、exact extent/SVQ batch、固液规则及 chemical registry fingerprint 依赖，旧 v2 不重解释并锁定；solid SVQ、独立质量与固相组成分别闭合；runtime handle 不上链；creation/phase transfer/runtime collision 必须先过联合 occupancy/audit plan |
| M8 `region-state v5` | `GranularStateSnapshot`、`GranularRegionStateCodec`、`RegionState`、`FluidRegionPersistence` | v5 是完整 material + optional chemistry + granular 代次；active/deposit、solid composition/species/audit 分账并逐材料/逐 section/global 闭合；任一 reactive live/audit payload 都要求相同 chemical identity；runtime composition handle 必须映射 stable table；v1/v2/v3 只经 inactive slot + HEAD 原子迁移；v4 不读不写 |
| M8 protocol/诊断/operator 清空 | `GranularDisplaySnapshot`、`FluidGranularSnapshotPayload`、`FluidGranularSnapshotLedger`、`RealWaterCommands`、`FluidSimulationService.emptySelectedRegion` | protocol v4 只发量化 bulk/porosity/top-material 与最多 4096 个 active sample，可选内嵌 v3 化学展示；exact SVQ/mass/species 不上链；`granstatus` 的完整库存/审计/boundary impulse 只在服务端给管理员并写 transcript；`granularcontainer` 是管理员惰性 canonical debug source；`empty` 拒绝未决容器 WAL，先联合预检 liquid/chemical 与 granular clear plan，再记 escaped sink、清空并发布完整 v5 checkpoint |
| M9 热定义、状态函数、transient 液体能量与审计 | `src/main/java/com/example/thermal/`、`FlipThermalState`、`FlipThermalParticleState`、`docs/architecture/thermal-state.md` | world-authoritative thermal definition 候选使用 canonical stable ID；总内能是独立真值，温度/热容/反应热/潜热只由状态函数派生；marker 能量由正交 state owner 持有，domain 只编排；`SignedDyadic` 只用于提交与持久化审计，不进 face hot loop；当前只允许 0.5 m/full-face transient 液相，尚未开放 production thermal gate |
| takeover codec/files | `src/main/java/com/example/simulation/TakeoverJournalCodec.java`、`src/main/java/com/example/simulation/TakeoverJournalFiles.java` | v1 可读；未知/损坏/恢复失败锁住新 takeover |
| 指令 transcript | `src/main/java/com/example/logging/RealWaterCommandLog.java`、`src/main/java/com/example/logging/CommandAuditFile.java` | 只做 UTF-8 观测；失败不得改变命令结果 |
| protocol/session | `src/main/java/com/example/network/FluidProtocol.java`、`src/main/java/com/example/network/FluidProtocolServer.java` | live offer 由 granular/chemistry/materials 门决定严格 v4/v3/v2/v1 及必需 capability；不做降级伪装 |
| 多区域快照 | `FluidSnapshotPayload`/`Ledger`、`FluidMaterialSnapshotPayload`/`Ledger`、`FluidChemicalSnapshotPayload`/`Ledger`、`FluidGranularSnapshotPayload`/`Ledger` | v1/v2/v3/v4 type 独立；dimension/id/generation/sequence/palette/fingerprint/tombstone 一致；v3/v4 exact inventory 不上链 |
| 客户端水面与颗粒展示 | `src/client/java/com/example/client/ClientFluidVolumeRenderer.java` | 每域按当前维度合并；各协议状态/ledger/清理互相独立；v4 deposit surface 与 active sample 只读，不得反写物理 |
| 客户端握手与绑定 | `src/client/java/com/example/client/RealWaterModClient.java` | configuration/play 注册、断线与重配置清理成套 |

详细的 M2.5 数值定义只在 [`../progress/m2.5-fluid-foundation.md`](../progress/m2.5-fluid-foundation.md) 保存；M3 材料/界面的验收结果与已量化限制只在 [`../progress/m3-multi-fluid.md`](../progress/m3-multi-fluid.md) 保存。

## 高频定位

- 精确水量丢失：`setSolid`、marker 创建/删除、服务层 `escaped`。
- 压力/散度：`solvePressure`、`applyPressureMatrix`、`projectVelocity`。
- 小水滴慢落：`FlipVelocityExtrapolator`、`trilinearValid`。
- 水量不变但水面下降：`FlipVolumeDriftCorrection`、reseeding 分布；禁止 renderer 增厚或强制找平。
- partial 方块/穿墙：`FlipCutCellGeometry`、`FlipParticleCollision`、world interaction mask。
- 水量正常但水面消失：snapshot marker mask、renderer scalar/iso threshold。
- 调度异常：服务 `tickRegion`/`stepOnce`、`FluidRegionLifecycle`、wall time/substeps/residual。
- 液液界面不可见或与外表面重合：`FluidMaterialSurfaceMesher` 的 phase 裁剪、快照量化分数、客户端 `MaterialFrame` 重建。
- 多材料区域被锁住或只显示纯水：`materialsEnabled` 本次启动值、config 版本、manifest formatVersion、协商 capability。
- 密闭液面不响应或气压异常：`AirCavityScanner` 环境判定与扫描预算、`AirNetwork` EoS/孔流、`FlipFluidDomain` 界面压力两处消费点。
- 气体账本不平或腔体拓扑事件异常：`AirNetwork` 的 remap/merge/split 与开孔 vent 记账。
- ownership 状态不明或区域被锁：`/realwater ownership`、`OwnershipStore.scanIssues/recoverUnfinished`、WAL 与 region HEAD 的 base/target 判定。
- 容器转移锁或物品/区域不一致：`<world>/realwater/container-transfer/` 严格扫描结果、`ContainerTransferRecovery` 的 HEAD 判定、actor 是否在线/已加载；不要手删未知或损坏 WAL。
- 化学区域锁或颜色/库存不符：先查 registry fingerprint、v3 HEAD 与 `/realwater chemstatus`；颜色、pH 和 Top-4 residual 都是只读展示，不能用于修复库存。

## 可选 GPU probe

`gpu-probe` 不修改世界或存档，主 Mod 始终 CPU 权威。

| 范围 | 首要入口 |
|---|---|
| 命令、demo、fallback 与 wall-time 保护 | `gpu-probe/src/main/java/com/example/gpuprobe/RealWaterGpuProbeMod.java`、`gpu-probe/src/main/java/com/example/gpuprobe/GpuProbeService.java` |
| Vulkan buffer/dispatch/ring/fence/timestamp | `gpu-probe/src/main/java/com/example/gpuprobe/VulkanFlipPipeline.java` |
| 完整代次 checkpoint/CPU 恢复 | `gpu-probe/src/main/java/com/example/gpuprobe/GpuProbeCheckpoint.java` |
| shader 与资源加载 | `gpu-probe/src/main/resources/assets/realwater_gpu_probe/shaders/flip.comp`、`gpu-probe/src/main/java/com/example/gpuprobe/GpuProbeShaderSource.java` |
| 能力/设备探测 | `gpu-probe/src/main/java/com/example/gpuprobe/VulkanCapabilityProbe.java` |
| 小域、长期、occupancy、64³ 门 | `gpu-probe/src/main/java/com/example/gpuprobe/GpuFlipProbeValidation.java`、`gpu-probe/src/main/java/com/example/gpuprobe/GpuLongRunSurfaceValidation.java`、`gpu-probe/src/main/java/com/example/gpuprobe/GpuOccupancyBreakEvenValidation.java`、`gpu-probe/src/main/java/com/example/gpuprobe/GpuLargeDomainValidation.java` |

改 descriptor/buffer/stage 时同步 Java 分配绑定、shader、readback 和验证。Vulkan timestamp 只观测设备性能，调度/超时仍使用 wall time。

## 最小充分验证

所有主 Mod CPU 门由一个 JVM runner 执行，避免每个历史阶段各保留一个 Gradle task/JVM。默认命令运行短门集合；定向选择和分层集合使用同一入口：

```powershell
# 13 个日常短门；不含磁盘故障矩阵、M3/M4 集成和长期平衡
./gradlew.bat runCpuValidation --offline --no-daemon

# 一个或多个直接受影响领域；显式 selector 总是运行该领域完整门
./gradlew.bat runCpuValidation -PcpuValidation=containers --offline --no-daemon
./gradlew.bat runCpuValidation -PcpuValidation=region-state,ownership --offline --no-daemon

# 19 个领域的短里程碑门；长期场景另行隔离
./gradlew.bat runCpuValidation -PcpuValidation=milestone --offline --no-daemon

# 仅 M5 平衡、5,000 步自由面与两项 5,000 步 reseeding 长跑
./gradlew.bat runCpuValidation -PcpuValidation=soak --offline --no-daemon

# 查询 selector；`full` 才把全部领域按完整模式串行运行
./gradlew.bat runCpuValidation -PcpuValidation=list --offline --no-daemon
```

2026-08-23 同一机器热构建基线：旧 21 个 JavaExec 全门为 82.4 秒；新 `fast`/`milestone` 总耗时分别为 10.0/14.0 秒（runner 本体 2.6/6.7 秒）；`soak` 为 67 秒，`full` 为 71 秒（runner 本体 62.8 秒），二者都不属于普通里程碑。`check` 不隐式依赖 runner。默认一个小增量只选最贴近改动的 selector，不重复叠加编译任务。

| 改动 | `cpuValidation` selector | 升级条件 |
|---|---|---|
| 文档 | 无 | 无代码/资源变化就不构建 |
| M3 marker 材料/相/质量、变密度压力或材料界面 mesh | `materials` | 已含原 1D 候选的活动三维连通器覆盖；同时改 wire/存档才追加对应 selector |
| M4 曲率/Laplace、活动表面张力、润湿或液液界面 | `surface-tension` | 解析曲率已合入活动门；同时改材料状态才追加 `materials` |
| M5 气体 EoS/孔流/库存账本（无网格） | `air-network` | 改活动压力消费才运行完整 `gas-liquid` |
| M5 空腔扫描/环境判定/几何边 | `air-cavity` | 改 cut-cell 共享面定义才追加 `cut-cell` |
| M5 活动气液耦合/界面气压 | `gas-liquid` | 显式选择运行完整 42 秒平衡门；普通 `milestone` 只做一阶 wiring/ledger smoke |
| `airdemo`、材料门或 live 协商 | `protocol` | 改 entrypoint/payload 注册才跑 EULA=true server 并以 `stop` 退出 |
| manager | `region-manager` | 同时跨 lifecycle/存档才追加对应 selector |
| lifecycle/tick | `lifecycle` | 同时改 ownership 才追加 `ownership` |
| region-state/checkpoint | `region-state` | 真实重启仅在自动门无法覆盖事务时做 |
| protocol/snapshot | `protocol` | 改入口/注册才跑 EULA=true server 并以 `stop` 退出 |
| controller/footprint | `boundary` | 改 admission/budget 才追加 `region-manager` |
| 压力/自由面/MAC/drift | `free-surface` | 显式选择含 5,000 步静水；改 marker 分布才追加 `reseeding` |
| cut-cell/CCD | `cut-cell` | 改 Minecraft shape adapter 追加 `shape`；跨压力共享面追加 `free-surface` |
| Minecraft `VoxelShape` adapter | `shape` | 不因纯 adapter 改动运行长期 solver 门 |
| marker merge/split/长期分布 | `reseeding` | 显式选择含两项 5,000 步；改 checkpoint/state 才追加 `region-state` |
| takeover 事务 | `takeover` | 真实世界只在事务里程碑 smoke |
| M6 ownership 事务/WAL/committed | `ownership` | 改 region-state 共享契约才追加 `region-state` |
| M6 容器账本、物品、入口与转移 WAL | `containers` | 已合并账本/原语与 WAL/recovery；改协议才追加 `protocol` |
| transcript | `audit` | 命令包装变化且自动门不足才进服 |
| M7 definition/registry/parcel/reaction/pH/indicator/化学守恒 | `chemistry` | 改 marker merge/split 才追加 `reseeding`；改存档/容器/协议再分别追加最近 selector |
| M8 颗粒 definition/registry、SVQ、parcel/deposit、力学/排水/孔隙/休眠 | `granular` | 改共享 pressure/cut-cell/air 路径才追加最近 `free-surface`/`cut-cell`/`gas-liquid`；改持久化/容器/协议/化学桥再分别追加对应 selector |
| M9 热定义/状态函数/输运/相变与反应热 | `thermal` | 改共享 solver/pressure/cut-cell/air、chemistry、granular、持久化、容器或协议时才追加相应最近 selector |
| GPU shader/dispatch/压力 | device 0 `--skip-large` | hierarchy/layout/capacity/大域性能才跑 64³ |
| Vulkan device/queue/memory/lifecycle | device 0 快速门 + device 1 专项 | 双卡完整门只在发布里程碑 |

新增 CPU 覆盖必须扩展 `CpuValidationSuite` 的最近 selector，禁止再为阶段/小功能增加独立 JavaExec task。只有新的失败模式无法归属现有领域时才新增 selector；长期场景必须进入 `soak`，并优先替换同不变量的旧长期场景。已被生产路径严格覆盖的选择实验、无入口 diagnostic 与旧 solver 验证应删除，数值证据保留在 progress 文档。clean build、GPU、服务器启动和人工 smoke 仍按风险触发，不能累计成每提交清单。

## 构建与部署边界

只有需要部署或发布时才运行：

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat clean assemble compileTestJava --offline --no-daemon
```

- mods：`Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\mods`。
- 同一 Mod id 只启用一个 jar；旧版改 `.disabled`，复制后比较 SHA-256。
- 只有自动门无法覆盖真实渲染、碰撞采样或世界事务时才请求用户进游戏。
