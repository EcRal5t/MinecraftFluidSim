# Real Water 维护路由

当前主 Mod：`0.8.2`。可选 GPU probe：`0.1.16`。版本历史、实测数据和长期阶段定义分别见 [`fluid-prototype.md`](fluid-prototype.md)、[`gpu-probe-report.md`](gpu-probe-report.md) 与 [`long-term-roadmap.md`](long-term-roadmap.md)。本文只回答“代码在哪里、改完跑什么”。

## 不可混用的状态

- 物理真值：marker 的 `particleVolume`；精确加水、删水和固体挤水只按它结算。
- 数值离散：`markerCount` 决定压力流体格；平滑 `volume[]` 只是 P2G 密度。
- 渲染重建：density 塑形，marker mask 保证浅水内部，solid mask 裁剪固体；渲染不得反改水量。
- 调度：CPU 主域以完整 step wall time 调频；GPU probe 的 Vulkan timestamp 只衡量设备命令流，异步 ring 的超时保护仍看服务器线程 scheduling/backpressure wall。
- 世界事务：`capture` 只读；只有 `takeover` 在 journal 与 WARNING 原子发布后移除纯 `Blocks.WATER`。waterlogged 不在范围内。

## 主 Mod 路由

| 职责 | 首要文件 | 修改时必须联查 |
|---|---|---|
| `/realwater` 命令树 | `src/main/java/com/example/RealWaterCommands.java` | 状态操作应留在服务层；输出经过命令 transcript 包装 |
| 初始化、tick、启动/停止恢复、网络注册 | `src/main/java/com/example/RealWaterMod.java` | 服务生命周期与 payload 注册 |
| 多区域 Minecraft 编排、独立 tick、物理交互、全域快照与 takeover | `src/main/java/com/example/simulation/FluidSimulationService.java` | 每域必须先完整通过 availability 才能读/步进；快照必须使用本域 id/generation/sequence；takeover 世界事务仍只在服务层 |
| checkpoint 重试、UNLOADED 往返、持久删除与区域管理 | `src/main/java/com/example/simulation/FluidRegionOperations.java` | 缺 chunk/破边界先暂停并发布，成功才释放；恢复保持暂停并通过回调刷新当前碰撞；不直接 step solver 或修改世界方块 |
| 指定区域的 Minecraft 碰撞采样与实体单向耦合 | `src/main/java/com/example/simulation/FluidRegionWorldInteraction.java` | 不得隐式读取 selected region；碰撞挤水仍按 marker 账本恢复；实体只受 region AABB 内本域流速影响 |
| CPU 域数组构造与只读原版水采样 | `src/main/java/com/example/simulation/FluidRegionFactory.java` | marker 硬上限必须等于 manager 预留；采样只写 `particleVolume`，不得从平滑 density 结算 |
| 区域身份、硬 marker 预算、metadata-only solver ownership 与独立运行态 | `src/main/java/com/example/simulation/FluidRegion.java` | tick/调频/碰撞/实体/debug 跟随区域；UNLOADED 仍占预算但不持 solver/marker 数组；restore 后必须先重采样碰撞；精确水量仍只来自 marker `particleVolume` |
| ACTIVE/REDUCED/静水休眠与 checkpoint 门控的 UNLOADED 状态 | `src/main/java/com/example/simulation/FluidRegionLifecycle.java` | `runFluidRegionLifecycleValidation`；缺完整已发布 checkpoint 或未释放 live solver 必须拒绝 UNLOADED；restore 保持 operator pause；状态只属调度，不得从 density 推断水量 |
| 区域登记、选择、原子替换、空间互斥与预算拒绝 | `src/main/java/com/example/simulation/FluidRegionManager.java` | 拒绝不得移除旧区域；报告 required/per-region/remaining；loaded/UNLOADED 预留相同，默认最多四域，同维重叠必须拒绝 |
| 世界级 controller flag 与单域/总预算配置 | `src/main/java/com/example/simulation/FluidServerConfig.java` | `<world>/realwater/server.properties` v1；配置原子替换失败不得改变运行态；预算在重启后生效，未知版本不得覆盖 |
| controller region-state 生命周期协调 | `src/main/java/com/example/simulation/FluidRegionPersistence.java` | 启动只读 manifest 并作 metadata admission；保存先发布再更新 checkpoint；删除先 quarantine；不得耦合 takeover journal |
| `region-state v1` 数据、codec、CRC 与两槽原子文件 | `src/main/java/com/example/simulation/persistence/`、`docs/region-state-v1.md` | marker 原始顺序/position/`particleVolume`/velocity/solid 必须闭合；section UUID/dimension/generation/CRC 与 manifest 一致；HEAD 永远最后发布；改动跑 `runFluidRegionPersistenceValidation` |
| 控制器与可见边界方块注册/交互 | `src/main/java/com/example/block/RealWaterBlocks.java`、`FluidControllerBlock.java` | 默认 feature flag 关闭；只读激活，不得扩大 takeover；同步 blockstate/model/item/loot/lang 资源 |
| 可见闭合边界扫描、footprint 与非加载 chunk 复核 | `src/main/java/com/example/simulation/controller/FluidBoundaryScanner.java`、`FluidRegionFootprint.java`、`FluidControllerRegions.java` | 只认专用边界/控制器；任何目标 block state 前先 `getChunkNow`；缺 chunk、破口、多内腔或预算超限必须拒绝/整域暂停 |
| 活动 PIC/FLIP 核心与 marker 硬上限 | `src/main/java/com/example/fluid/FlipFluidDomain.java` | P2G、PCG、投影、90/10 G2P、逐轴碰撞；所有 marker 创建/恢复路径受实例预算约束；Minecraft 不使用旧 `FluidDomain` |
| CPU marker 内存交接状态 | `src/main/java/com/example/fluid/FlipParticleState.java` | 不可变位置/体积/速度/solid；`particleVolume` 账本必须闭合；不是存档或渲染协议 |
| CPU 变密度面系数实验 | `src/test/java/com/example/fluid/VariableDensityPressureExperiment.java` | 门在 `VariableDensityFaceCoefficientValidation.java`；它只选 M3 候选，尚未接入活动核心 |
| solver 参数 | `src/main/java/com/example/fluid/FluidSolverConfig.java` | 改 CFL、压力容差或 FLIP 比例时跑长期数值门 |
| takeover 编排 | `TakeoverTransaction.java`、`TakeoverBackupStore.java` | journal/WARNING 发布完成前禁止修改世界 |
| takeover codec 与原子文件 | `TakeoverJournalCodec.java`、`TakeoverJournalFiles.java` | v1 必须继续可读；未知/损坏/恢复失败状态必须锁住新 takeover |
| 指令 transcript | `logging/RealWaterCommandLog.java`、`CommandAuditFile.java` | 只做 UTF-8 观测，失败不得改变命令结果 |
| 协议版本/capability 协商与服务器 session 门 | `network/FluidProtocol.java`、`FluidProtocolServer.java`、`FluidProtocolOfferPayload.java`、`FluidProtocolResponsePayload.java` | 配置 task 未完成不得进入 play；协议版本独立于 journal/region-state；改动跑 `runFluidNetworkProtocolValidation` |
| 多区域网络快照与乱序水位 | `network/FluidSnapshotPayload.java`、`FluidSnapshotLedger.java` | dimension/id/generation/sequence/palette 和可跳过长度段必须同步；removal tombstone 禁止旧包复活 |
| 客户端多区域水面 | `src/client/java/com/example/client/ClientFluidVolumeRenderer.java` | 每 region 独立 200 ms 插值，再按当前 dimension 合并 marching-tetrahedra draw；density/marker/solid 不得反改物理 |
| 客户端握手与绑定 | `src/client/java/com/example/client/RealWaterModClient.java` | configuration offer/response、play snapshot、重配置/断线清理必须成套 |

高频定位：

- 精确水量或粒子丢失：`setSolid`、`spawnCellVolume`、`removeParticlesInCell`、服务层 `escaped`。
- 压力/散度：`isFluid`、`solvePressure`、`applyPressureMatrix`、`projectVelocity`。
- 墙边/墙角卡住：`advectParticles`、`constrainToSolidFaces`；碰撞只约束墙面法向。
- 水量正常但水面消失：payload marker mask、renderer `scalar` 与等值面阈值。
- 调度异常：服务 `tickRegion`、`stepOnce`、`updateStepPacing`，`FluidRegionLifecycle` 与 status 的 wall time/substeps/PCG residual；先区分 operator pause、availability block 和 lifecycle state。

## GPU probe 路由

`gpu-probe` 不接管世界、不写存档，主 Mod 始终 CPU 权威。

| 职责 | 文件 | 修改时必须保持 |
|---|---|---|
| Fabric 命令与入口 | `RealWaterGpuProbeMod.java` | 命令树、transcript 和独立 probe 生命周期一致 |
| demo/benchmark 生命周期、CPU fallback、wall-time 保护 | `GpuProbeService.java` | 主 Mod CPU 权威；timestamp 不参与超时决策 |
| Vulkan FLIP 资源与 dispatch/三槽 staging ring/fence/内存/timestamp | `VulkanFlipPipeline.java` | shader binding/stage、buffer/readback 和生命周期验证同步 |
| GPU 完整代次检查点与 CPU 恢复 | `GpuProbeCheckpoint.java`、`GpuProbeService.java` | 初始 generation 0 必须成功；每 20 步替换一次；失败只使用上一完整代次并保持暂停 |
| 主计算 shader | `src/main/resources/assets/realwater_gpu_probe/shaders/flip.comp` | stage 编号和 Java 常量一致 |
| shader 资源加载与缺失诊断 | `GpuProbeShaderSource.java` | jar 资源缺失必须明确失败 |
| 无窗口 Vulkan/Shaderc/VMA 能力探测 | `VulkanCapabilityProbe.java` | 不创建窗口/surface，不触碰世界 |
| 已加载区块的冻结只读碰撞采样 | `WorldCollisionSnapshot.java` | 只用非加载读取，不持 chunk ticket |
| 小域正确性与快速 benchmark | `GpuFlipProbeValidation.java` | marker 账本、内存模式和压力容差 |
| GPU 长期表面/marker CPU 对照门 | `GpuLongRunSurfaceValidation.java` | 1,000 个 50 ms 物理步；检查 marker 层数、压力流体格、边缘/内部 density；不把平滑 density 当水量 |
| M1 occupancy CPU/GPU break-even 发布矩阵 | `GpuOccupancyBreakEvenValidation.java` | 独立任务，固定初态七批中位数；分离网格、marker 数、压力流体格数；不得用 timestamp 代替 throughput wall |
| 64³ 深层 MGH、压力算法与 2,097,152-marker 发布门 | `GpuLargeDomainValidation.java` | 仅 hierarchy、布局、容量或大域性能变化时运行 |

`flip.comp` stage 编号必须与 `VulkanFlipPipeline` 常量一致。改 descriptor binding、push constant 或 buffer 布局时，同时修改 Java 分配/绑定、shader 声明、readback 和验证。MGH/MG2/Jacobi 共用细网格 Poisson 离散；压力候选不得改变粒子体积、marker 分类或渲染字段。

## 按改动选择测试

默认采用最小充分门，不为普通改动重复跑两张显卡或 64³：

| 改动范围 | 默认验证 | 何时升级 |
|---|---|---|
| 文档、路由、纯日志文字 | `compileJava` 或无需运行时门 | 命令树/资源打包变化才跑专服或进服 |
| CPU 变密度面系数实验 | `runVariableDensityFaceCoefficientValidation` | 将来接入活动 3D 核心时再升级到 `FlipFluidDomainValidation`、M2.5 分数界面门和 M3 连通器门 |
| CPU 区域登记、容量、运行态、独立 tick 或选择逻辑 | `runFluidRegionManagerValidation` + `runFluidRegionLifecycleValidation`；静水提交或 marker 预算变化再跑 `FlipFluidDomainValidation` | 涉及持久 metadata admission/solver ownership 时同时跑 `runFluidRegionPersistenceValidation` |
| `region-state`、server config、checkpoint、UNLOADED 或删除 | `runFluidRegionPersistenceValidation` + `runFluidRegionManagerValidation` + `runFluidRegionLifecycleValidation` | 改 entrypoint/资源再跑 EULA=false 专服；真实跨 chunk 离开/返回与重启闭环在里程碑 smoke 时进服 |
| 网络协议、payload header、capability 或客户端排序 | `runFluidNetworkProtocolValidation` + `compileTestJava` | 改 Fabric 配置/入口注册再跑 EULA=false `:runServer`；真实多人/重连渲染只有需要时进服 |
| 控制器、边界扫描、footprint 或完整加载判定 | `runFluidBoundaryScannerValidation` + `runFluidRegionManagerValidation` | 方块注册/资源变化再跑 EULA=false `runServer`；真实放置、右键和跨 chunk 卸载只有请求进服时再 smoke |
| CPU 物理/碰撞/调度 | `compileTestJava` + `FlipFluidDomainValidation`；表面相关再跑 surface diagnostic | takeover 改动另跑 16 场景；只有请求进服验证或发布时才 clean build |
| takeover/codec/文件事务 | `runTakeoverJournalValidation` | 真实世界修改只按 smoke 文档并保留备份 |
| 指令 transcript | `runCommandAuditValidation` | Fabric 命令包装变化再进服 |
| GPU shader、dispatch、压力数值 | RTX 5070 Ti `--skip-large` 快速门；长期表面相关先跑 `runGpuLongRunSurfaceValidation` | hierarchy、容量、buffer 布局或 64³ 性能变化才跑完整 device 0 |
| GPU occupancy 性能矩阵 | RTX 5070 Ti `runGpuOccupancyBenchmark` | 仅重测 break-even 或发布相关性能结论时运行；不因普通代码改动重复，且不据此切正式后端 |
| 设备选择、queue family、显存类型、Vulkan 生命周期 | device 0 快速门 + device 1 对应专项 | 发布里程碑才做双卡完整门 |
| entrypoint、依赖、资源路径、环境注解 | 主 Mod 用 `:runServer`，probe 用 `:gpu-probe:runServer`，保持 `eula=false` | 客户端渲染/握手或命令打包变化且自动门不足时再请求进服 smoke |

GPU 快速门：

```powershell
./gradlew.bat :gpu-probe:runGpuFlipValidation --offline --no-daemon `
  '-PgpuProbeNativesDir=Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\26.2-Fabric 0.19.3-natives\lwjgl\3.4.1-snapshot\x64' `
  -PgpuProbeDevice=0 -PgpuProbeSkipLarge=true
```

GPU 1,000 物理步表面/marker 对照门：

```powershell
./gradlew.bat :gpu-probe:runGpuLongRunSurfaceValidation --offline --no-daemon `
  '-PgpuProbeNativesDir=Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\26.2-Fabric 0.19.3-natives\lwjgl\3.4.1-snapshot\x64' `
  -PgpuProbeDevice=0
```

M1 occupancy 发布矩阵：

```powershell
./gradlew.bat :gpu-probe:runGpuOccupancyBenchmark --offline --no-daemon `
  '-PgpuProbeNativesDir=Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\26.2-Fabric 0.19.3-natives\lwjgl\3.4.1-snapshot\x64' `
  -PgpuProbeDevice=0
```

CPU 与事务入口：

```powershell
./gradlew.bat runVariableDensityFaceCoefficientValidation --offline --no-daemon
./gradlew.bat runFluidRegionManagerValidation --offline --no-daemon
./gradlew.bat runFluidRegionLifecycleValidation --offline --no-daemon
./gradlew.bat runFluidRegionPersistenceValidation --offline --no-daemon
./gradlew.bat runFluidBoundaryScannerValidation --offline --no-daemon
./gradlew.bat runFluidNetworkProtocolValidation --offline --no-daemon
./gradlew.bat runTakeoverJournalValidation runCommandAuditValidation --offline --no-daemon
& "$env:JAVA_HOME\bin\java.exe" -ea -cp 'build\classes\java\main;build\classes\java\test' com.example.fluid.FlipFluidDomainValidation
& "$env:JAVA_HOME\bin\java.exe" -ea -cp 'build\classes\java\main;build\classes\java\test' com.example.fluid.FlipFluidSurfaceDiagnostic
```

需要进服验证或发布时的 clean 构建：

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat clean assemble compileTestJava --offline --no-daemon
```

## 部署边界

- mods：`Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\mods`。
- 开发阶段优先跑定向自动门；只有自动门覆盖不了、确实需要用户进游戏验证时才做 Mod 构建与部署，并同时给出待验证指令。
- 同一 Mod id 只启用一个 jar；旧版改为 `.disabled`，复制后比较 SHA-256。
- 请求人工 smoke 只针对自动门无法覆盖的打包命令、真实渲染、真实碰撞采样或世界事务。

probe 0.1.16 将每个 50 ms GPU 物理步明确离散为四个 12.5 ms 子步，并把域/solid 接触半径对齐到 CPU 的 `0.45h`；碰撞仍只裁剪墙面法向，切向自由。独立 1,000 步门要求 GPU 与 CPU 保持相同的三层 marker 和近似边缘密度，覆盖此前约 979–1,304 步出现的额外 GPU 坍缩。完整四子步后，5070 Ti 当前参考域为 11.646 ms CPU wall 对 2.257 ms GPU completed-batch throughput wall，即 5.16×；单 marker crossover 后移到约 1,024 流体格。GPU 的 36,000 步无窗口特征化仍从第 5,000 步起暴露长期 marker 压缩，精确 `particleVolume` 未变；CPU 与 GPU 当前都未实现 reseeding，因此这是一项共享的结构性风险，但现有长测数据只来自 GPU。它属于 M2.5，不能用渲染找平关闭。2026-08-19 的实机渲染复测在第 1,317 步确认原固定边缘/角落凹坑显著消退；RTX 4060 Ti 又完成 31 分 44 秒、38,073 步稳定性门，最终体积仍为 2,592.000 格、recovery 为 0，并正常 remove。M1 已完成，probe 仍是只读可选观测而非正式后端。M0 也已完成：强制中断恢复 60/60 个纯水方块、清除 journal/WARNING、解除 lock，邻接结构正常；仅在事务或接管边界变化时按 [`m0-takeover-smoke.md`](m0-takeover-smoke.md) 重跑。

M2 的自动实现门现已闭合：protocol v1、多区域客户端排序、可见控制器边界、跨 chunk 完整加载、世界级 feature flag/预算、管理命令和 `region-state v1` 均已接通。持久化按 Minecraft section 保存 marker 原始顺序、position/`particleVolume`、velocity 与 solid slice；两槽完整代次校验后最后原子替换 HEAD。缺任一 chunk、破边界或关闭控制器功能门会先整域暂停并 checkpoint，成功才释放 solver 进入 metadata-only `UNLOADED`；恢复保持 operator pause 且先重采样当前碰撞。自动 fault matrix、反复 unload/restore、损坏拒绝和 metadata budget 门在 `runFluidRegionPersistenceValidation`。仍无永久 chunk ticket，takeover 边界与 journal v1 未变，GPU probe 仍只读可选。M2 最终签收只剩真实客户端跨 chunk 离开/返回与重启 smoke。
