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

可选 GPU probe 使用独立版本线，完整 0.1.0–0.1.16 结果见 [`m1-gpu-probe-report.md`](m1-gpu-probe-report.md)。
