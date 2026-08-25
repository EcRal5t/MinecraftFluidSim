# Matter, Motion & Mechanics 文档索引

仓库维护当前架构契约、维护路由、尚未实现的产品路线和一份精选发布历史；探索草案与重复完成报告不作为长期入口。

## 当前架构

- [系统组件、真值与 logical-step 数据流](architecture/system-overview.md)
- [CPU 流体求解器](architecture/fluid-solver.md)
- [材料与独立质量状态](architecture/material-state.md)
- [区域 checkpoint](architecture/region-state.md)
- [世界 ownership 与容器事务](architecture/world-ownership-and-containers.md)
- [气体库存与拓扑](architecture/gas-state.md)
- [守恒化学](architecture/chemistry-state.md)
- [颗粒与沉积](architecture/granular-state.md)
- [内能、热输运与相变](architecture/thermal-state.md)
- [外部只读 Query API](architecture/external-api.md)

## 维护与未来

- [代码职责和最小验证](maintenance/routing.md)
- [下一产品工作](roadmap/product-roadmap.md)
- [性能与后端评审](roadmap/performance-and-backends.md)
- [验收和发布](roadmap/acceptance-and-release.md)
- [项目运行与构建](../README.md)

## 历史档案

- [主 Mod 版本历史](progress/version-history.md)

版本历史可以保留发布时使用的旧名称、格式代号和当时的限制，但不定义当前行为。判断现状时只沿当前 architecture 和代码入口。

## 更新规则

1. 已生效的状态边界或算法契约只写入 `architecture/`。
2. 代码入口或最近验证门变化时更新 `maintenance/routing.md`。
3. 尚未实现且已经决定要做的工作写入 `roadmap/`；探索性草案留在 issue、任务讨论或 Git 分支，不进入规范文档。
4. 实现完成后，把仍有约束力的结论并入 architecture，然后从 roadmap 删除该任务。
5. architecture 不能退化成摘要：应记录维护实现所需的组件职责、权威数据布局、单位/公式、logical-step 顺序、预算、事务状态机和失败语义。
6. `progress/version-history.md` 只记录发布事实，不承担当前规范、路线或迁移说明。
7. 本地 `maintenance/next-session-initial-prompt.md` 是可随当前工作面整理的会话交接提示，不替代上述三类长期文档。
