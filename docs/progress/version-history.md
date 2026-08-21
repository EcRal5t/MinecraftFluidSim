# 主 Mod 版本历史

本文只记录已经发布或成为当前基线的主 Mod 增量。当前行为契约见 [`../architecture/fluid-solver.md`](../architecture/fluid-solver.md)，不从旧版本描述反推现行代码。

## 0.3–0.5：有界世界交互

- **0.3**：加入 `/realwater pour|drain` 与定时碰撞刷新。
- **0.4**：加入自由面反扩散和 surface diagnostic；这属于旧 VOF 阶段，活动核心后来由 PIC/FLIP 替代。
- **0.5**：加入服务器权威的实体浮力/阻力采样；保持单向耦合。

## 0.6–0.6.1：活动 PIC/FLIP

- **0.6**：以 Godot 参考的 3D PIC/FLIP 循环替代活动 VOF；marker 携带精确体积，客户端使用 marching tetrahedra 重建。
- **0.6.1**：修正压力相 containing-cell 分类和 3D normal-only 碰撞；客户端密度卷积只影响重建。

## 0.7–0.8.2：世界事务与观测

- **0.7**：首次加入可逆 `/realwater takeover`，只移除纯原版水并在 remove/关服时恢复。
- **0.7.1**：碰撞挤水改按 marker 精确体积结算；快照增加 marker occupancy 以保持浅水可见。
- **0.8**：加入磁盘 `takeover-journal v1`，世界修改前原子发布。
- **0.8.1**：保持 v1 可读并强化 fail-closed、WARNING 和可重试恢复边界。
- **0.8.2**：加入 `/realwater` 与 `/realwatergpu` 的 UTF-8 命令 transcript；只做观测。

## M2 与 M2.5

- **M2**：多区域 manager、控制器可见边界、完整 chunk 门、protocol v1、独立生命周期和 `region-state v1`。详见 [`m2-region-state-smoke.md`](m2-region-state-smoke.md)。
- **M2.5**：PLIC fluid-air 压力距离、runtime cut-cell、有效 MAC 面外推、守恒 marker reseeding 和压力-only rest-volume 约束。详见 [`m2.5-fluid-foundation.md`](m2.5-fluid-foundation.md)。

## 0.9–0.9.1：M3 多液体与可变密度

- **0.9.0**：M3 验收候选。marker 材料/相/质量账本、变密度 `1/rho` 面系数、互溶组聚合、不可互溶分层、门控 `region-state v2`、门控 protocol v2 + material capability、客户端 partitioned mesh，以及瞬态 `/realwater materialdemo` 与只影响下次重启的 `/realwater materials`。
- **0.9.1**：修复实机复看发现的两个渲染缺陷——外表面主导材料在自由面外侧空格被误判为 palette 0，以及液液界面裁剪到等值面导致与自由面共面闪烁；并对噪声级相分数设下限。详见 [`m3-multi-fluid.md`](m3-multi-fluid.md)。

## 0.10.0：M4 表面张力与润湿

- **0.10.0**：液气界面 `p_interface = σ κ` ghost-fluid 压力跳跃进入活动压力 RHS 与投影；近壁曲率法向施加 Young 接触角，不写死吸墙或墙角偏置。液液 `σ12` 以 `FluidMaterialRegistry.LiquidPairTension` 定义并按 balanced-force CSF 接入活动核心。`σ=0` 逐位退化回 M3，wire 与 `region-state` 字节仍不携带 M4/M4.1 字段。自动门覆盖静滴 Laplace 跳跃、液液 `σ12` 跳跃、无持续寄生流、合并/断开守恒和不同接触角铺展；另提供瞬态 `/realwater tensiondemo [角度]` 开发命令用于实机视觉验证。详见 [`m4-surface-tension.md`](m4-surface-tension.md)。

可选 GPU probe 使用独立版本线，完整 0.1.0–0.1.16 结果见 [`m1-gpu-probe-report.md`](m1-gpu-probe-report.md)。

## 0.11.0：M5 空气区域网络与气液压力

- **0.11.0**：密闭气腔 flood fill 识别（预算 fail-open）、`long` 纳体积量子精确气体账本（`sealed + vented == drawnFromAmbient`）、等温状态方程、CONTAM 幂律有限速率孔流与拓扑事件库存迁移进入活动核心；自由面 ghost-fluid 跳跃升级为 `σκ + p_gas`，环境空气贡献为零，开放水域与 M4 逐位一致。气体状态 runtime-only，不进 wire/存档；新增瞬态 `/realwater airdemo [pressure|vacuum]` 与 status 气体段。详见 [`m5-air-network.md`](m5-air-network.md)。
