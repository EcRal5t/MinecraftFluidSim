# Real Water 维护路由

本文只回答“代码在哪里、改完默认跑什么”。当前流体架构见 [`../architecture/fluid-solver.md`](../architecture/fluid-solver.md)，完成史见 [`../progress/README.md`](../progress/README.md)，未来路线见 [`../roadmap/product-roadmap.md`](../roadmap/product-roadmap.md)。

## 不可混用的状态

- 物理真值：marker `particleVolume` 精确和。
- 数值离散：`markerCount` 压力相、平滑 `volume[]`、PLIC、cut-cell 和压力目标。
- 渲染重建：density + marker + solid，只读消费服务端快照。
- 调度策略：ACTIVE/REDUCED/休眠/UNLOADED 与 CPU wall-time pacing。
- 世界事务：只有 takeover 在 journal + WARNING 发布后移除纯 `Blocks.WATER`。

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
| 世界配置 | `src/main/java/com/example/simulation/FluidServerConfig.java` | v1/v2/v3 配置 fail-closed；v1 强制 materials/worldBridge 关闭，v2 强制 worldBridge 关闭；预算与各功能门重启后生效 |
| controller 持久化协调 | `src/main/java/com/example/simulation/FluidRegionPersistence.java` | manifest admission、checkpoint、quarantine 与 takeover journal 分离；按材料门分派 v1/v2 |
| `region-state v1`/门控 v2 | `src/main/java/com/example/simulation/persistence/`、`docs/architecture/region-state-v1.md` | 两槽完整代次、CRC、账本和 HEAD 最后发布；v1 canonical bytes 不变 |
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
| M3 材料与 CPU 门 | `src/main/java/com/example/fluid/FluidMaterial.java`、`src/main/java/com/example/fluid/FluidMaterialRegistry.java`、`src/main/java/com/example/fluid/BuiltinFluidMaterials.java`、`src/main/java/com/example/fluid/FluidMaterialRenderSnapshot.java`、`src/main/java/com/example/fluid/FluidMaterialSurfaceMesher.java`、`src/test/java/com/example/fluid/M3ConnectedVesselExperiment.java`、`src/test/java/com/example/fluid/M3MaterialTransferValidation.java`、`src/test/java/com/example/fluid/M3MaterialDynamicsValidation.java`、`src/test/java/com/example/fluid/M3MaterialSurfaceValidation.java` | snapshot/mesh 只读重建，量化字段不结算库存；正式世界入口（桶、方块、takeover）仍只创建纯水 |
| takeover 事务 | `src/main/java/com/example/simulation/TakeoverTransaction.java`、`src/main/java/com/example/simulation/TakeoverBackupStore.java` | journal/WARNING 完成前禁止世界修改 |
| M6 世界 ownership 事务 | `src/main/java/com/example/simulation/ownership/` | prepare→WAL→WARNING→世界修改→region HEAD 最后发布→committed 重建；恢复按 region HEAD 对比 WAL base/target 判定；玩家方块优先；不碰 takeover-journal v1 |
| M6 容器纳体积账本 | `src/main/java/com/example/item/ContainerFluidLedger.java` | 容器真值是 long 纳体积（1 BV=1e9）；换算半量子内舍入、漂移有界并记录，不用 damage/density 结算 |
| M6 精确转移原语 | `src/main/java/com/example/fluid/FlipFluidDomain.java` 的 `tryInsertExactVolume`/`tryExtractExactVolume` | all-or-nothing；insert 先规划后落子、refuse 零残留；extract 双精度可用性预检+整 marker+尾拆分；返回精确 double 实际量，由服务层换算量子 |
| M6 容器物品/组件 | `src/main/java/com/example/item/RealWaterComponents.java`、`PartialWaterBucketItem.java`、`RealWaterItems.java` | `water_volume` 组件是容器真值（partial ∈ (0,1BV)）；耐久条/tooltip 只读；满/空在服务层规范化为原版桶 |
| M6 部分桶合并配方 | `src/main/java/com/example/item/RealWaterRecipes.java`、`src/main/resources/data/realwater/recipe/partial_water_bucket_merge.json` | 动态配方：先填满一桶，溢出作为 recipe remainder 留在第二输入；精确账本由 `ContainerFluidLedger.mergeOutput/mergeRemainder` 结算 |

| M6 桶入口（玩家/发射器） | `src/main/java/com/example/simulation/FluidTransferService.java`、`src/main/java/com/example/mixin/ItemStackMixin.java`、`RealWaterMod.java` 的 dispenser 注册 | worldBridge 门控；只拦截选中区域或目标在区域内；成功/失败都不得让原版水绕过 ledger；容器真值仍由账本/组件结算 |
| M6 容器转移 WAL | `src/main/java/com/example/simulation/ContainerTransferJournal.java`、`ContainerTransferStore.java`、`FluidTransferService` 的 controller 路径 | 只用于 controller 区域玩家/发射器桶转移；PREPARED→solver 修改→REGION_COMMITTED→checkpoint→物品修改→清理；恢复按 region HEAD 对比 base/target，HEAD=base 丢弃、HEAD=target 收敛 after、其他锁定；玩家离线/发射器未加载保持 pending 并锁住新转移 |

| takeover codec/files | `src/main/java/com/example/simulation/TakeoverJournalCodec.java`、`src/main/java/com/example/simulation/TakeoverJournalFiles.java` | v1 可读；未知/损坏/恢复失败锁住新 takeover |
| 指令 transcript | `src/main/java/com/example/logging/RealWaterCommandLog.java`、`src/main/java/com/example/logging/CommandAuditFile.java` | 只做 UTF-8 观测；失败不得改变命令结果 |
| protocol/session | `src/main/java/com/example/network/FluidProtocol.java`、`src/main/java/com/example/network/FluidProtocolServer.java` | live offer 由材料门决定严格 v1 或严格 v2 + material capability；不做降级伪装 |
| 多区域快照 | `src/main/java/com/example/network/FluidSnapshotPayload.java`、`src/main/java/com/example/network/FluidSnapshotLedger.java`、`src/main/java/com/example/network/FluidMaterialSnapshotPayload.java`、`src/main/java/com/example/network/FluidMaterialSnapshotLedger.java` | v1/v2 type 独立；dimension/id/generation/sequence/palette/tombstone 一致 |
| 客户端水面 | `src/client/java/com/example/client/ClientFluidVolumeRenderer.java` | 每域独立插值，按当前维度合并；v1 与 v2 状态/ledger/清理互相独立；不得反写物理 |
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

默认一个小增量只跑最贴近改动的一项。定向 Gradle task 已包含所需编译，不重复叠加 `compileJava`/`compileTestJava`。

| 改动 | 默认一项 | 升级条件 |
|---|---|---|
| 文档 | 无 | 无代码/资源变化就不构建 |
| M3 marker 材料/相/质量、变密度压力或材料界面 mesh | `runM3ConnectedVesselValidation` | 该门已含活动核心连通器与翻转分层；只有同时改 wire/存档才加对应门 |
| 1D 变密度面系数实验 | `runVariableDensityFaceCoefficientValidation` | 已接入 3D 连通器，改该系数改跑 M3 连通器门 |
| M4 表面张力曲率/Laplace 独立实验 | `runSurfaceTensionCurvatureValidation` | 接入活动核心压力算子后才加对应 solver 门 |
| M4 活动核心表面张力/润湿/液液 | `runM4SurfaceTensionValidation` | 该门已含静滴 Laplace、液液 `σ12` 跳跃、无持续寄生流、合并/断开守恒和接触角铺展；只有改 wire/存档才加对应门 |
| M5 气体 EoS/孔流/库存账本（无网格） | `runAirNetworkValidation` | 接入活动核心后改跑 M5 耦合门 |
| M5 空腔扫描/环境判定/几何边 | `runAirCavityScannerValidation` | 改 cut-cell 共享面定义才加 cut-cell 门 |
| M5 活动核心气液耦合/界面气压 | `runM5GasLiquidCouplingValidation` | 该门已含逐位退化、准静态单调压缩、负压悬液与孔链均压；只有改 wire/存档才加对应门 |
| `airdemo` 玻璃脚手架/gas debug extension | `runFluidNetworkProtocolValidation` | 改 entrypoint/payload 注册才跑 EULA=false server；玻璃恢复账本无自动门，属实机 smoke 项 |
| 材料门配置或 live 协商 | `runFluidNetworkProtocolValidation` | 改 payload 注册/entrypoint 才跑 EULA=false server |
| manager | `runFluidRegionManagerValidation` | 同时跨 lifecycle/persistence 才加对应门 |
| lifecycle/tick | `runFluidRegionLifecycleValidation` | 同时改 ownership 才加 persistence |
| region-state/checkpoint | `runFluidRegionPersistenceValidation` | 真实重启仅里程碑 smoke |
| protocol/snapshot | `runFluidNetworkProtocolValidation` | 改入口/注册才跑 EULA=false server |
| controller/footprint | `runFluidBoundaryScannerValidation` | 改 admission/budget 才加 manager |
| 压力/自由面/MAC/drift | `runFlipFreeSurfaceValidation` | 改长期 marker 分布才加 reseeding |
| cut-cell/CCD | `runFlipCutCellValidation` | 改 Minecraft shape adapter 改跑 shape 门；跨压力共享面才加 free-surface |
| Minecraft `VoxelShape` adapter | `runFluidCutCellShapeValidation` | 不因纯 adapter 改动跑长期 solver 门 |
| marker merge/split/长期分布 | `runFlipReseedingValidation` | 改 checkpoint/state 才加 persistence |
| takeover 事务 | `runTakeoverJournalValidation` | 真实世界只在事务里程碑 smoke |
| M6 ownership 事务/WAL/committed | `runM6OwnershipValidation` | 改 region-state 共享契约才加 persistence 门；真实世界只在事务里程碑 smoke |
| M6 容器账本/换算/物品/桶入口 | `runM6ContainerValidation` | 改协议 payload 才加 protocol 门；新增 mixin/资源打包跑 EULA=false server |
| M6 容器转移 WAL | `runContainerTransferValidation` | 改恢复/actor 收敛才加 EULA=true server；真实崩溃收敛在 Smoke C |
| transcript | `runCommandAuditValidation` | 命令包装变化且自动门不足才进服 |
| GPU shader/dispatch/压力 | device 0 `--skip-large` | hierarchy/layout/capacity/大域性能才跑 64³ |
| Vulkan device/queue/memory/lifecycle | device 0 快速门 + device 1 专项 | 双卡完整门只在发布里程碑 |

5,000 步、benchmark、clean build 和人工 smoke 不是日常每提交门。新增覆盖优先扩展最近的现有小门；人工验证每次只回答一个自动化无法观察的问题，不累计重演旧 smoke。

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
