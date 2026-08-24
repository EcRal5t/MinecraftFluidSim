# 已完成进度

本目录只保存已经完成的实现与验收证据。当前流体架构以 [`../architecture/fluid-solver.md`](../architecture/fluid-solver.md) 为准，持久化格式以 [`../architecture/region-state-v1.md`](../architecture/region-state-v1.md) 为准；未来工作以 [`../roadmap/product-roadmap.md`](../roadmap/product-roadmap.md) 为准。

| 阶段 | 完成日期 | 结论 | 证据 |
|---|---|---|---|
| M0 takeover 事务 | 2026-08-19 | journal v1、WARNING、故障注入和真实强制中断恢复完成 | [`m0-takeover-smoke.md`](m0-takeover-smoke.md) |
| M1 可选 GPU probe | 2026-08-19 | probe 0.1.16 完成双卡、64³、恢复、长期表面和实机稳定性门；仍只读可选 | [`m1-gpu-probe-report.md`](m1-gpu-probe-report.md) |
| M2 多区域与持久化 | 2026-08-19 | 控制器、闭合边界、跨 chunk 完整加载、protocol v1 与 `region-state v1` 完成 | [`m2-region-state-smoke.md`](m2-region-state-smoke.md) |
| M2.5 自由表面基础 | 2026-08-20 | PLIC、cut-cell、MAC 外推、守恒 reseeding 和体积分布压力约束完成 | [`m2.5-fluid-foundation.md`](m2.5-fluid-foundation.md)、[`m2.5-smoke.md`](m2.5-smoke.md) |
| M3 多液体与可变密度 | 2026-08-20 | 材料/质量账本、变密度连通器、门控 protocol v2 与 `region-state v2`、液液界面渲染完成 | [`m3-multi-fluid.md`](m3-multi-fluid.md) |
| M4 表面张力与润湿 | 2026-08-21 | 液气 `σ κ` 压力跳跃、无持续寄生流、合并/断开守恒、Young 接触角铺展和液液 `σ12` balanced-force 完成；实机球冠视觉 smoke 通过；`σ=0` 逐位退化 M3，wire/存档仍不带 M4/M4.1 字段 | [`m4-surface-tension.md`](m4-surface-tension.md) |
| M5 空气区域网络与气液压力 | 2026-08-22 | 密闭气腔识别、纳体积精确气体账本、等温 EoS、有限速率孔流与 `p_gas` 自由面边界完成；准静态压缩 0.91%、负压悬液 6.73%、孔链有限均压；开放水域与 M4 逐位一致，气体状态 runtime-only | [`m5-air-network.md`](m5-air-network.md) |
| M6.0 ownership 设计评审 | 2026-08-22 | 选定"空气 + 独立 registry"路线，拒绝代理占位方块；冻结共同状态机、acquire 提交顺序、崩溃恢复判定表与格式边界；不升版本 | [`m6.0-ownership-review.md`](m6.0-ownership-review.md) |
| M6.1a 无容器 crash-safe ownership | 2026-08-22 | config v3 `worldBridgeEnabled`、分段世代、per-region WAL 与 region-HEAD 键控恢复、激活 acquire/保守 release/启动恢复全部落地并过实机 Smoke A；不升版本 | [`m6.1a-world-ownership.md`](m6.1a-world-ownership.md) |
| M6 世界 ownership、流体桥与容器 | 2026-08-23 | crash-safe ownership、精确部分/材料混合桶、玩家/发射器统一转移与跨持久域 WAL v2 完成；完整 CPU 回归、专用服务器启动与实机 Smoke C 通过，主 Mod 0.12.0 | [`m6-world-bridge-and-containers.md`](m6-world-bridge-and-containers.md) |
| M7.0 守恒化学状态设计评审 | 2026-08-23 | 冻结 absolute parcel record、exact codec/fingerprint、硬预算、v3 边界和守恒验收；纯 Java core 门通过，未打开 gameplay | [`m7.0-chemistry-state-review.md`](m7.0-chemistry-state-review.md) |
| M7 守恒化学 | 2026-08-23 | marker parcel、强电解质/中和、v3 region/protocol/WAL、化学桶、指示剂与 source/sink 审计完成；自动发布门与合并实机场景通过，主 Mod 0.13.0 | [`m7-conservative-chemistry.md`](m7-conservative-chemistry.md) |
| M8 颗粒沉淀与低成本沉积 | 2026-08-24 | 守恒 active parcel/deposit、排水/浮力/阻力/孔隙、安息角沉积、休眠/局部唤醒、固液相转移、v5 region/protocol v4/container WAL v4 与干颗粒容器完成；双 AI 关闭审阅 finding 已修复，自动发布门和合并实机场景通过，主 Mod 0.14.0 | [`m8-granular-deposition.md`](m8-granular-deposition.md) |

补充记录：

- 主 Mod 版本演进：[`version-history.md`](version-history.md)
- 当前分支的 M2.5 最终实现提交：`44b6182`
- M2.5 验收记录提交：`31639c4`

完成新里程碑时，在这里增加一行，并把详细结果放入独立文件。不要把未来任务复制进本目录。
