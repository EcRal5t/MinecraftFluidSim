# 历史文档

这里保存旧提案、审查记录和已完成工作的实施报告。归档文件保持原文，里面的相对链接和代码名称沿用记录时的写法。当前修改入口见 [项目 README](../../README.md)。

| 资料 | 用途 |
|---|---|
| [代码架构重整实施记录](progress/code-architecture-restructuring-implement.md) | 按 S00–S21 切片和提交号追溯结构变化 |
| [架构重整基线](maintenance/architecture-baseline.md) | 查看重整完成时的源码规模与本机性能数据 |
| [CPU 状态交接与参考能优化](maintenance/cpu-hotspot-optimization-2026-09-23.md) | 2026-09-23 分阶段性能、缓存命中、状态摘要及长跑对照 |
| [CPU 性能审查与补正](maintenance/cpu-hotspot-review-2026-09-24.md) | 组合测量纠错、颗粒优化拆分对照及撤回原始列接口的依据 |
| [细沙床面起动补正](maintenance/granular-bed-entrainment-2026-09-24.md) | 休眠检查、床面剪切、薄床阻力及完整水流搬运轨迹 |
| [旧提案](proposals/) | 查询当时的方案、阶段划分和取舍。四份专题计划与 [剩余工作实施总计划](proposals/remaining-work-implementation-plan.md) 仍提供工作包依赖图、故障注入矩阵和验收标准，但状态已过期，当前剩余工作与顺序以 [产品路线](../roadmap/product-roadmap.md)为准 |
| [历史审查](review/) | 查询当时发现的问题与审查意见 |

这次整理将实施记录和基线从活动目录移到这里。当前架构检查命令在 [验证指南](../maintenance/validation.md#architecture)，当前规模用 [Python 工具](../maintenance/pytool.md)重新统计。

[版本历史](../progress/version-history.md)继续记录发布变化，保留在 `progress/`。
