# Real Water 维护路由

本文面向接棒开发者，回答“某项功能要去哪里改、改完验证什么”。当前基线为 `0.8.2`，Minecraft Java 26.2、Fabric Loader 0.19.3、Fabric API 0.157.0+26.2，Java 25 字节码。

已确认的多材料、气体、化学、颗粒、世界接管与 GPU 长期路线见 [`long-term-roadmap.md`](long-term-roadmap.md)。本文仍只描述当前 0.8 代码路径。

## 先读结论

- 当前活动求解器是 3D PIC/FLIP：[`FlipFluidDomain.java`](../src/main/java/com/example/fluid/FlipFluidDomain.java)。
- [`FluidDomain.java`](../src/main/java/com/example/fluid/FluidDomain.java) 是早期 VOF 对照实现，Minecraft 服务不再使用它。
- 不要用“墙边吸水、角落偏置、受支撑强制找平”等经验规则修物理问题。先检查相分类、P2G/G2P、压力边界、粒子碰撞和表面重建。
- 粒子携带的 `particleVolume` 是总水量真值；平滑后的 `volume[]` 只用于密度、显示和查询，不能代替精确粒子体积。
- 当前只有一个 `12×8×12` 方块的临时域，每个方块轴向 2 格，即 `24×16×24` 求解格。
- 一个满求解格初始化 8 个粒子，所以一个满水方块约 64 个粒子。

## 数据流

```text
命令 / 生命周期
    -> FluidSimulationService（域、世界、调度、实体、碰撞）
        -> FlipFluidDomain（P2G、压力、G2P、粒子移动）
        -> FluidSnapshotPayload（密度、marker mask、solid mask）
            -> ClientFluidVolumeRenderer（插值、隐式面、marching tetrahedra）
```

Godot 参考工程位于 `D:\Proj\Godot\260331-FluidSim`。对应流程是 `clear -> p2g -> grid_prep -> divergence -> jacobi -> project -> g2p -> advect`；重点文件为 `p2g.glsl`、`grid_prep.glsl`、`jacobi.glsl`、`project.glsl`、`g2p.glsl`、`advect.glsl`。

## 功能路由表

| 要改的功能 | 首要文件 | 关联文件与注意事项 |
|---|---|---|
| 新增或修改 `/realwater` 子命令 | `RealWaterCommands.java` | 实际状态操作放进 `FluidSimulationService`，命令层只解析参数和回显 |
| Mod 初始化、事件注册、网络注册 | `RealWaterMod.java` | 服务器 tick、启动恢复、停止恢复都在这里挂钩 |
| 域尺寸、帧预算、快照频率 | `FluidSimulationService.java` 顶部常量 | 同步检查网络量、客户端网格和基准测试 |
| demo 初始水体 | `FluidSimulationService.createDemo` | 创建后默认暂停；不要直接改世界方块 |
| 捕获原版水 | `captureNearbyWater` | `capture` 只读；`takeover` 才能移除原版纯水块 |
| takeover 与恢复 | `FluidSimulationService`、`TakeoverBackupStore.java`、`TakeoverTransaction.java` | 必须先原子发布 v1 NBT 和 WARNING，再修改世界；恢复与文件删除都完成后事务才解锁 |
| takeover codec/文件层 | `TakeoverJournalCodec.java`、`TakeoverJournalFiles.java` | v1 字段保持兼容；旧/损坏 journal 禁止覆盖；原子移动不可用时拒绝接管，不退化为普通 move |
| 放置方块挤水 | `refreshCollisions`、`FlipFluidDomain.setSolid` | `setSolid` 返回实际删除的粒子体积；禁止改回平滑密度估算 |
| pour/drain | `pourWater`、`drainWater`、`addVolumeNear`、`removeVolumeNear` | 单位换算是每方块 8 个 cell-volume |
| 服务器步进和自动降频 | `tick`、`stepOnce`、`updateStepPacing` | 40 ms 是软预算；连续 3 次超过 200 ms 才硬暂停 |
| 浮力与阻力 | `applyEntityForces` 至 `applyFluidForce` | 当前是水推动实体的单向耦合；原版 `isInWater` 实体会跳过 |
| P2G 质量/速度传输 | `FlipFluidDomain.rebuildGridFromParticles`、`splatCenter`、`splatFaceField` | 固体/域边界做核权重归一化，避免显示质量在墙边变少 |
| 流体相分类 | `markerCount`、`isFluid` | 压力域由粒子中心实际占格决定，不能用 P2G 微量权重判定 |
| 重力和 CFL 子步 | `calculateSubsteps`、`applyGravity` | `status` 中 `substeps` 用来区分高速细分与 JVM 冷启动 |
| 压力求解 | `solvePressure`、`applyPressureMatrix`、`applyPreconditioner` | 当前是 PCG；状态会显示迭代数和 residual |
| 压力投影 | `projectVelocity`、`enforceSolidFaceVelocities` | 固体面法向速度必须为零 |
| PIC/FLIP 混合 | `gridToParticles`、`FLIP_RATIO` | 当前 90% FLIP / 10% PIC；改动后必须测长期稳定和耗时 |
| 粒子移动与墙碰撞 | `advectParticles`、`constrainToSolidFaces` | 逐轴 CCD，只限制墙面法向坐标，沿墙切向必须保持自由 |
| 粒子生成/删除 | `spawnCellVolume`、`spawnParticle`、`removeParticlesInCell` | 总体积来自 `particleVolume[]`，部分粒子允许小于 1/8 格 |
| 服务器到客户端快照 | `FluidSnapshotPayload.java`、`sendSnapshot` | 修改 record 字段时必须同时改 codec、clear、客户端插值与构造器 |
| 水面颜色、透明度、阈值 | `ClientFluidVolumeRenderer.java` 顶部常量 | `ISO_LEVEL`、`MARKER_INTERIOR_LEVEL` 会直接影响浅水可见性 |
| 水面平滑和空洞 | `ClientFluidVolumeRenderer.scalar` | 密度卷积负责塑形，marker mask 负责保证浅水内部种子 |
| 表面拓扑 | `renderFluidSurface`、`renderTetrahedron` | 当前 marching tetrahedra 用退化 quad 输出三角形 |
| 快照时间插值 | `interpolate`、`SnapshotTransition` | 当前 200 ms；marker 使用前后帧并集以避免闪烁 |
| 橙色碰撞层和黄色边界 | `renderSolidCell`、`renderBounds` | 由 `/realwater debug` 控制，默认关闭 |
| 客户端事件绑定 | `RealWaterModClient.java` | 接收 payload、extract、render、close 均在这里注册 |
| 版本号与依赖 | `gradle.properties` | 发布新 jar 前递增 `version` |

## 关键不变量

1. `FlipFluidDomain.totalVolume()` 在普通 step 前后应保持不变。
2. 固体覆盖水时，`setSolid` 删除多少粒子体积，服务就只重放多少；容量不足才计入 `escaped`。
3. `markerCount` 决定压力相；`volume[]` 是平滑 P2G 密度，二者不能混用。
4. `copyMarkerMask()` 保证稀薄浅水仍有隐式面内部；删除该字段会重现 `dense 0` 时水面消失。
5. 碰撞不能用粒子球 AABB 锁住所有方向；否则墙角和沿墙流动会被错误阻止。
6. takeover journal 必须先于世界修改写入，且恢复时只覆盖当前仍为空气或水的位置。
7. 不得自动替换 waterlogged 方块；当前 takeover 只处理 `Blocks.WATER`。
8. 服务端是物理真值，客户端渲染不得反向修改水量。
9. journal 或 WARNING 任一处于未解决状态时必须锁住新 takeover；临时文件不是已发布真值，可在确认没有已发布文件时清理。

## 常见现象到代码位置

- 水量数字下降、粒子数下降：检查 `setSolid`、`removeParticlesInCell`、`addVolumeNear` 和 `escaped`。
- 水量不变但视觉消失：检查 payload 的 marker mask、`scalar` 和等值面阈值。
- 墙边出现固定退缩带：检查逐轴碰撞与相分类，不要添加吸墙项。
- 水面有内部三角洞：检查 marker 内部种子、卷积场和 marching tetrahedra，而非修改压力。
- 水一直不平：先看 `substeps`、PCG residual、粒子占格分布和 FLIP 比例。
- 开局慢、之后恢复：查看 JVM 冷启动、PCG 次数和 `substeps`；调度恢复阈值在服务类顶部。
- takeover 后出现原版水：检查 `maintainTakeover`、是否为 waterlogged、域外水是否流入。
- 崩溃后水没恢复：检查世界目录的 `realwater-takeover-backup.nbt` 和启动日志。
- 客户端解码断线：检查 payload record 与 codec 字段顺序是否两端一致。

## 测试入口

活动求解器回归：

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
./gradlew.bat compileTestJava --offline
& "$env:JAVA_HOME\bin\java.exe" -cp 'build\classes\java\main;build\classes\java\test' com.example.fluid.FlipFluidDomainValidation
```

密度分布诊断：

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp 'build\classes\java\main;build\classes\java\test' com.example.fluid.FlipFluidSurfaceDiagnostic
```

`FluidDomainValidation`、`FluidDomainBenchmark`、`FluidDomainSurfaceDiagnostic` 只对应旧 VOF 对照实现，不代表当前游戏路径。

M0 takeover codec、真实文件与故障注入回归：

```powershell
./gradlew.bat runTakeoverJournalValidation --offline --no-daemon
```

该套件覆盖正常创建/删除、写入前、原子发布后、移除第 N 块、恢复第 N 块、删除前、损坏/未知版本、维度未加载、残留临时文件、原子移动不可用、WARNING 发布失败、删除失败、世界恢复失败和玩家实体方块保护。

指令 transcript 文件回归：

```powershell
./gradlew.bat runCommandAuditValidation --offline --no-daemon
```

`RealWaterCommandLog` 包装两个命令树并收集处理器输出，`CommandAuditFile` 只负责 UTF-8 追加格式。目标为服务器游戏目录的 `logs/realwater-commands.log`；`/realwater logpath` 可显示绝对路径。该文件不是物理真值、存档或恢复 journal，写入失败只进入普通错误日志，不能阻塞或改变命令结果。Brigadier 在进入处理器前拒绝的拼写/语法错误只存在于 `latest.log`。

独立 M1-B Windows/NVIDIA GPU probe：

```powershell
./gradlew.bat :gpu-probe:runGpuFlipValidation --offline --no-daemon `
  '-PgpuProbeNativesDir=Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\26.2-Fabric 0.19.3-natives\lwjgl\3.4.1-snapshot\x64' `
  -PgpuProbeDevice=0
```

入口在 `gpu-probe/src/main/java/com/example/gpuprobe`：`VulkanCapabilityProbe` 负责无窗口 Vulkan/Shaderc/VMA 能力门，`VulkanFlipPipeline` 负责 device-local 3D 数值阶段、staging 上传/快照和每步 64 B 诊断回读，`GpuFlipProbeValidation` 负责 CPU/GPU、两种内存模式、静水、守恒与两档 benchmark，`GpuProbeService` 和 `RealWaterGpuProbeMod` 只提供不读写世界的游戏内可视化命令。实测与未完成边界见 [`gpu-probe-report.md`](gpu-probe-report.md)。

游戏内基本回归顺序：

```text
/realwater demo
/realwater run
/realwater status
/realwater remove

/realwater capture
/realwater pour 4
/realwater run
/realwater status

/realwater takeover
/realwater run
/realwater remove
```

## 构建与部署

项目：`D:\Proj\260817-MinecraftMod\fabric-example-mod`

目标 mods：`Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\mods`

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat clean assemble compileTestJava --offline --no-daemon
```

部署前只读检查 `javaw.exe/java.exe` 命令行是否包含 `26.2-Fabric 0.19.3`。游戏运行时不要替换 jar，也不要替用户结束进程；请用户关闭游戏后再复制。部署时主 Mod 只保留一个版本的 `realwater-<version>.jar`；可另外保留一个 `realwater-gpu-probe-<version>.jar`，但同一 Mod id 不得有重复版本。复制后比较 SHA-256。

## 下一阶段建议

1. 按 [`m0-takeover-smoke.md`](m0-takeover-smoke.md) 补完测试世界的强制中断恢复和邻接更新冒烟；正常 takeover → remove 已通过，仍需保留备份、命令计数和日志。
2. probe 0.1.3 的 device-local 可视化、暂停多步、连续运行、status、两档 benchmark 和 remove 已通过，0.1.1 的 CPU fallback 已通过；后续补 dedicated-server classpath 冒烟。probe 不得进入正式存档或接管世界。
3. 继续 M1 的压力算法对照、变密度 CPU 候选、timestamp query/ring staging 和设备丢失恢复检查点，不把当前固定迭代 Jacobi 冻结为正式压力算法。
4. M1 门通过后再做动态域/分块域设计，避免当前 12×8×12 人工边界成为不可见墙；chunk-section 格式不得直接序列化当前单域或 GPU 缓冲。
5. 之后考虑实体到水的反向动量耦合、粒子 reseeding、自由表面压力权重和更高分辨率。

扩大功能前保持“小域、可移除、可恢复、可量化状态”这四个安全边界。
