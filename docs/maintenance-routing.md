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
| 单域生命周期、世界采样、调度、实体力、快照 | `src/main/java/com/example/simulation/FluidSimulationService.java` | 域尺寸/频率变化同时检查 payload、renderer 与 benchmark |
| 活动 PIC/FLIP 核心 | `src/main/java/com/example/fluid/FlipFluidDomain.java` | P2G、PCG、投影、90/10 G2P、逐轴碰撞；Minecraft 不使用旧 `FluidDomain` |
| CPU marker 内存交接状态 | `src/main/java/com/example/fluid/FlipParticleState.java` | 不可变位置/体积/速度/solid；`particleVolume` 账本必须闭合；不是存档或渲染协议 |
| CPU 变密度面系数实验 | `src/test/java/com/example/fluid/VariableDensityPressureExperiment.java` | 门在 `VariableDensityFaceCoefficientValidation.java`；它只选 M3 候选，尚未接入活动核心 |
| solver 参数 | `src/main/java/com/example/fluid/FluidSolverConfig.java` | 改 CFL、压力容差或 FLIP 比例时跑长期数值门 |
| takeover 编排 | `TakeoverTransaction.java`、`TakeoverBackupStore.java` | journal/WARNING 发布完成前禁止修改世界 |
| takeover codec 与原子文件 | `TakeoverJournalCodec.java`、`TakeoverJournalFiles.java` | v1 必须继续可读；未知/损坏/恢复失败状态必须锁住新 takeover |
| 指令 transcript | `logging/RealWaterCommandLog.java`、`CommandAuditFile.java` | 只做 UTF-8 观测，失败不得改变命令结果 |
| 网络快照 | `network/FluidSnapshotPayload.java` | record、codec、`clear()`、服务端构造与客户端读取必须同步 |
| 客户端水面 | `src/client/java/com/example/client/ClientFluidVolumeRenderer.java` | density/marker/solid、200 ms 插值、marching tetrahedra |
| 客户端绑定 | `src/client/java/com/example/client/RealWaterModClient.java` | payload 接收、extract、render、close |

高频定位：

- 精确水量或粒子丢失：`setSolid`、`spawnCellVolume`、`removeParticlesInCell`、服务层 `escaped`。
- 压力/散度：`isFluid`、`solvePressure`、`applyPressureMatrix`、`projectVelocity`。
- 墙边/墙角卡住：`advectParticles`、`constrainToSolidFaces`；碰撞只约束墙面法向。
- 水量正常但水面消失：payload marker mask、renderer `scalar` 与等值面阈值。
- 调度异常：服务 `stepOnce`、`updateStepPacing`、status 的 wall time/substeps/PCG residual。

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
| CPU 物理/碰撞/调度 | `compileTestJava` + `FlipFluidDomainValidation`；表面相关再跑 surface diagnostic | takeover 改动另跑 16 场景；只有请求进服验证或发布时才 clean build |
| takeover/codec/文件事务 | `runTakeoverJournalValidation` | 真实世界修改只按 smoke 文档并保留备份 |
| 指令 transcript | `runCommandAuditValidation` | Fabric 命令包装变化再进服 |
| GPU shader、dispatch、压力数值 | RTX 5070 Ti `--skip-large` 快速门；长期表面相关先跑 `runGpuLongRunSurfaceValidation` | hierarchy、容量、buffer 布局或 64³ 性能变化才跑完整 device 0 |
| GPU occupancy 性能矩阵 | RTX 5070 Ti `runGpuOccupancyBenchmark` | 仅重测 break-even 或发布相关性能结论时运行；不因普通代码改动重复，且不据此切正式后端 |
| 设备选择、queue family、显存类型、Vulkan 生命周期 | device 0 快速门 + device 1 对应专项 | 发布里程碑才做双卡完整门 |
| entrypoint、依赖、资源路径、环境注解 | `:gpu-probe:runServer`，保持 `eula=false` | 客户端渲染/命令打包变化再请求进服 smoke |

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
- 先检查 `javaw.exe/java.exe` 命令行；包含 `26.2-Fabric 0.19.3` 时请用户关闭，绝不代为结束进程。
- 同一 Mod id 只启用一个 jar；旧版改为 `.disabled`，复制后比较 SHA-256。
- 请求人工 smoke 只针对自动门无法覆盖的打包命令、真实渲染、真实碰撞采样或世界事务。

probe 0.1.16 将每个 50 ms GPU 物理步明确离散为四个 12.5 ms 子步，并把域/solid 接触半径对齐到 CPU 的 `0.45h`；碰撞仍只裁剪墙面法向，切向自由。独立 1,000 步门要求 GPU 与 CPU 保持相同的三层 marker 和近似边缘密度，覆盖此前约 979–1,304 步出现的额外 GPU 坍缩。完整四子步后，5070 Ti 当前参考域为 11.646 ms CPU wall 对 2.257 ms GPU completed-batch throughput wall，即 5.16×；单 marker crossover 后移到约 1,024 流体格。GPU 的 36,000 步无窗口特征化仍从第 5,000 步起暴露长期 marker 压缩，精确 `particleVolume` 未变；CPU 与 GPU 当前都未实现 reseeding，因此这是一项共享的结构性风险，但现有长测数据只来自 GPU。它属于 M2.5，不能用渲染找平关闭。2026-08-19 的实机渲染复测在第 1,317 步确认原固定边缘/角落凹坑显著消退；RTX 4060 Ti 又完成 31 分 44 秒、38,073 步稳定性门，最终体积仍为 2,592.000 格、recovery 为 0，并正常 remove。M1 已完成，probe 仍是只读可选观测而非正式后端。M0 强制中断恢复和邻接更新人工门仍按 [`m0-takeover-smoke.md`](m0-takeover-smoke.md) 执行。
