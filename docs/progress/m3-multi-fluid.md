# M3 多液体、可变密度与连通器

M3 于 `26.2` branch 关闭，主 Mod 版本 `0.9.1`。本文只记录已完成结果与验收证据；当前契约见
[`../architecture/fluid-solver.md`](../architecture/fluid-solver.md) 与
[`../architecture/m3-material-state-migration.md`](../architecture/m3-material-state-migration.md)。

基线：Minecraft 26.2、Fabric Loader 0.19.3、Fabric API 0.157.0+26.2、Java 25。活动核心仍是 `FlipFluidDomain`，CPU 权威。

## 完成范围

- marker 携带材料 handle、相标签和显式 `particleMass`；marker 体积与逐材料质量是两套互不换算的真值账本。
- 同密度两材料严格逐位退化为纯水解：压力相、面系数、矩阵、RHS、解、投影速度、PCG 迭代与残差全部相同。
- interface-distance-weighted conservative `1/rho` 面系数接入活动核心变系数压力算子。
- registry、互溶组聚合、不可互溶分层、多材质只读快照与 partitioned marching tetrahedra 内部界面。
- 门控 `region-state v2`、门控 network protocol v2 + material capability、客户端 partitioned mesh。
- 瞬态 `/realwater materialdemo` 与 `/realwater materials <bool>`（只保存下次重启生效的配置）。

正式世界入口（桶、原版方块替换、takeover）仍只创建纯水；完整桶矩阵留到 M6.1。

## 自动门证据

`runM3ConnectedVesselValidation`（聚合 CPU 门）：

```
water-oil-1.25:1          rhoInterface=850.000 pressureErr=1.60e-14 PCG=34 residual=2.95e-12 maxVelocity=7.84e-14
mercury-water-13.6:1      rhoInterface=4150.000 pressureErr=6.00e-16 PCG=26 residual=1.88e-13 maxVelocity=3.28e-15
active water-oil-1.25:1   pressureErr=1.71e-07 PCG=4 residual=0.000712 maxSpeed=6.33e-08
active mercury-water-13.6:1 pressureErr=9.94e-08 PCG=4 residual=4.09e-05 maxSpeed=7.45e-08
active unequal-area connector headDiff 4.000 -> 0.188 in 1201 steps, maxSpeed=0.0859
active density inversion COM heavy/light 4.000/2.050 -> 2.345/3.784, maxSpeed=0.316
material surfaces outer=672 interface=138 junction=238 planeError=0.00 colourChecked=672 minInterfaceLevel=0.577
M3 connected-vessel gate passed: cells=60 markers=60 volume=7.500 BV mass=7500.000 MU
```

`runFluidRegionPersistenceValidation`、`runFluidNetworkProtocolValidation` 通过；主 Mod EULA=false 启动门在
protocol 注册增量时通过一次。

完成条件对应关系：同液 U 形管等高由 `maxVelocity <= 1e-8` 的静止解给出；不同截面连通器静水平衡由活动核心
`headDiff 4.000 -> 0.188` 给出；不同密度液柱压强匹配由 1.25:1 与 13.6:1 解析静压误差给出；油水翻转后稳定分层由
活动核心重轻液质心反转给出；外表面与内部界面可见且不重合由 `planeError=0.00`、`minInterfaceLevel=0.577` 与实机确认给出。

## 实机视觉验收

单一实机场景只回答自动门无法观察的问题：客户端是否同时渲染外表面与液液界面。

首次复看（0.9.0）发现两个真缺陷，均已修复并补入上述定向门：

1. 外表面主导材料误判。`floor(质心/cellSize)` 在格心节点网格上会把等值面点归到自由面外侧空格；该格所有材料分数为 0，
   而 `bestFraction = -1` 起步的扫描落到 palette 0，于是油的 +y/+x 面被涂成水蓝色。改为只让含 marker 的格投票的
   加权采样，无法解析时跳过三角形。回归证据：旧逻辑把 `x=3.18`（油侧）判成 material 0。
2. 液液界面与自由面共面闪烁。界面原本裁剪到 `ISO_LEVEL`，实测会探到自由面外侧（level `0.483` < `0.5`）。改为按
   inset 裁到严格内侧。

第二次复看（0.9.1）确认颜色与闪烁问题消失。

## 已量化的已知限制

`M3StratificationDiagnostic`（诊断，不挂任何 Gradle 门）在封闭水槽复现 `materialdemo` 比例，4000 步：

- 分层正确：水质心 `2.360`，油质心 `4.656`；`dVolume=0.000e+00`、`dMass=0.000e+00` 精确守恒。
- 油质心以上的水单调衰减 `1.684% -> 1.003% -> 0.504% -> 0.325%`，末态 `15/4597` marker。少量滞留是 M3 尺度的
  预期结果：亚格尺度下单个 marker 的质量被并入该格质量加权密度，浮力恢复被稀释；reseeding 按设计不跨材料合并；
  表面张力要到 M4。**不得**用渲染补丁或跨材料合并掩盖。
- 界面视觉锯齿属预期，不是重建缺口。实测界面位置很平：`rms=0.093 cells`、`span=3.275..3.660`（总跨度不足半格），
  且网格连续（约 132 格覆盖面上 1428 个三角形）。阈值敏感性排除了自身实现：`MIN_PHASE_PRESENCE` 从 0.05 降到
  1e-7 三角形数不变（1428），`INTERFACE_INSET` 从 0.06 降到 0 仅多 2 个三角形（1430），粗糙度完全不变。
  锯齿来自逐 tetrahedron 分片平面重建（每方块 2 格）、逐三角形平坦着色，以及真实的亚格混相
  （665 个有液格中 236 格同时含两相，少数相平均占比 `0.252`）。这与 roadmap"不得声称已解析界面滑移或乳化"的
  边界一致；后续物理改进属 M4 曲率/表面张力，渲染改进属逐顶点法线平滑，均不在 M3。

## 相关提交

- `dd9b8b0` 瞬态多材料 demo 入口
- `ac800f0` 外表面着色与界面共面修复（含门扩展）
- `1e7e669` 分层与界面粗糙度诊断
- `f5b6b41` / `8d2994e` 版本 0.9.0 / 0.9.1
