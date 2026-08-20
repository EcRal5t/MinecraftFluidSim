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

补充记录：

- 主 Mod 版本演进：[`version-history.md`](version-history.md)
- 当前分支的 M2.5 最终实现提交：`44b6182`
- M2.5 验收记录提交：`31639c4`

完成新里程碑时，在这里增加一行，并把详细结果放入独立文件。不要把未来任务复制进本目录。
