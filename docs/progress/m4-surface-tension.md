# M4 表面张力与润湿

M4/M4.1 已完成活动核心液气表面张力、固体接触角和液液 `σ12` 的首版 CPU 门。本文只记录已经签收的实现与验证边界；实机视觉复看由用户在游戏内完成，界面锐化仍属后续渲染里程碑。

## 实现边界

- `FluidMaterial` 冻结 `surfaceTension`（非负，0 = M3 退化）与 `contactAngleRadians`（开区间 `(0, π)`，`π/2` 中性）。
- 活动核心 `FlipFluidDomain` 在 `σ>0` 时重建自由液气面压力跳跃 `p_interface = σ κ`：
  - 颜色场由 `exactCellMarkerVolume / openVolume` 构造并一次 3x3x3 平滑，`κ = -div(n)`。
  - 压力跳跃同时进入压力 RHS（自由面散度源）和投影（ghost-fluid `theta`），因此静滴在 PCG 收敛后投影速度为零。
  - `σ=0` 时不构建表面张力场，压力算子逐位退化回 M3。
- 固体润湿只通过 Young 边界作用于曲率法向：近壁法向满足 `n·n_solid = -cosθ`；没有吸墙、墙角偏置或受支撑强制找平。
- `FluidMaterialRegistry` 增加运行时-only 的 `LiquidPairTension`（非负，phase handle 无序对去重）；活动核心对每个 `σ12>0` 的相 pair 以相体积分数场构造 balanced-force CSF 动量，再进入同一压力投影。
- wire 与 `region-state` 字节仍不携带 M4/M4.1 字段，decode 侧以 `σ=0`、`π/2`、空 pair 表重建；内建材料保持 M3 缺省，因此 v2 round-trip 无损。真实数值要等格式迁移评审通过后再进入存档。
- 正式世界入口仍只创建纯水。

## 验证结果

| 门 | 内容 | 结果 |
|---|---|---|
| `runSurfaceTensionCurvatureValidation` | 平面 `κ=0`；R=3/4/5 球面 `κ=2/R` 误差 2.4% / 2.0% / 0.14%；Laplace 跳跃 `σκ` 误差 2.0% | 通过 |
| `runM4SurfaceTensionValidation` | 活动核心静滴平均液相压力 `0.03676` vs `2σ/R=0.03640`（误差 1.0%）；10 步无持续寄生流；合并/断开 20 步体积/质量账本逐位闭合；θ=30° 与 θ=120° 润湿铺展差异；液液 `σ12` 满域液滴 Laplace 跳跃（误差 20.5%）与 5 步无持续寄生流 | 通过 |
| `runM3ConnectedVesselValidation` | M3 同密度退化、变密度连通器、材料/相/质量账本和 M3 界面 surface 均保持通过 | 通过 |
| `runFlipFreeSurfaceValidation` | M2.5 纯水 PLIC、cut-cell、漂移约束和 5000 步长期表面未回归 | 通过 |

## 已量化的限制

- 液液 `σ12` 使用 phase 无序对；同一 phase 内混相组成依赖的 `σ12` 仍未建模。
- 多材料格中液气 `σ` 与接触角取该格 dominant phase 的第一个 registry material；混相组成依赖的表面张力仍未建模。
- 液液 CSF 使用相体积分数场一次平滑后的曲率；对满域同密度液滴误差约 20.5%，与 M4 液气首版同属网格分辨率受限。
- 润湿门使用放大 `σ=100` 和 1200 步短域，用于在日行门内放大接触角差异；真实水参数需由实机视觉 smoke 确认。
- 当前 `σ`/接触角/`σ12` 真实数值尚未进入 wire 或存档，因此重启后的材料门区域仍以 `(0, π/2, 空 pair 表)` 重建。
- 液液界面曲率锐化与逐顶点法线平滑未在本阶段实现；当前液液面仍为 M3 的 partitioned mesh，不得用渲染收缩替代物理。
