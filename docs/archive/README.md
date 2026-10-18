# 历史文档

这里保存旧提案、审查记录和已完成工作的实施报告。归档文件保持原文，里面的相对链接和代码名称沿用记录时的写法。当前修改入口见 [项目 README](../../README.md)。

| 资料 | 用途 |
|---|---|
| [共同主干阶段验证记录](progress/solver-validation-2026-10-18.md) | 2026-10-17 至 2026-10-18 的局部流程、沙床生命周期及前序预算成绩；最新证据由验证指南维护 |
| [原代码路由细项](progress/routing-2026-10-18.md) | 2026-10-18 合并到当前设计书前的代码索引细项 |
| [求解器整合计划：2026-10-17 调整前备份](proposals/solver-consolidation-plan-2026-10-17.md) | 原文保留旧的高阶矩精度要求与工作包依赖，供追溯；当前精度取舍和执行路线见[整合计划](../roadmap/solver-consolidation-plan.md) |
| [自由面统一算法探索](free-surface-unification-research.md) | 各路线的有效成果、失败限制、保存位置与现行双路径修复的离线对照 |
| [代码架构重整实施记录](progress/code-architecture-restructuring-implement.md) | 按 S00–S21 切片和提交号追溯结构变化 |
| [架构重整基线](maintenance/architecture-baseline.md) | 查看重整完成时的源码规模与本机性能数据 |
| [CPU 状态交接与参考能优化](maintenance/cpu-hotspot-optimization-2026-09-23.md) | 2026-09-23 分阶段性能、缓存命中、状态摘要及长跑对照 |
| [CPU 性能审查与补正](maintenance/cpu-hotspot-review-2026-09-24.md) | 组合测量纠错、颗粒优化拆分对照及撤回原始列接口的依据 |
| [细沙床面起动补正](maintenance/granular-bed-entrainment-2026-09-24.md) | 休眠检查、床面剪切、薄床阻力及完整水流搬运轨迹 |
| [旧会话交接提示](maintenance/next-session-initial-prompt.md) | 保留旧交接文本；当前开发要求见 [AGENTS.md](../../AGENTS.md)，施工进度与下一步见[实施计划](../roadmap/solver-consolidation-plan.md) |
| [旧提案](proposals/) | 查询当时的方案、阶段划分和取舍。四份专题计划与 [剩余工作实施总计划](proposals/remaining-work-implementation-plan.md) 提供历史依赖、故障注入矩阵和验收输入，当前进度由[实施计划](../roadmap/solver-consolidation-plan.md)维护，后续能力由[产品路线](../roadmap/product-roadmap.md)安排 |
| [历史审查](review/) | 查询当时发现的问题与审查意见 |

这次整理将实施记录和基线从活动目录移到这里。当前架构检查命令在 [验证指南](../maintenance/validation.md#architecture)，当前规模用 [Python 工具](../maintenance/pytool.md)重新统计。

[版本历史](../progress/version-history.md)继续记录发布变化，保留在 `progress/`。
