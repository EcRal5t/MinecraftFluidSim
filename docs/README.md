# Real Water 文档索引

仓库只维护当前契约、当前维护路由和尚未实现的产品路线。

## 当前架构

- [CPU 流体求解器](architecture/fluid-solver.md)
- [材料与独立质量状态](architecture/material-state.md)
- [区域 checkpoint](architecture/region-state.md)
- [世界 ownership 与容器事务](architecture/world-ownership-and-containers.md)
- [气体库存与拓扑](architecture/gas-state.md)
- [守恒化学](architecture/chemistry-state.md)
- [颗粒与沉积](architecture/granular-state.md)
- [内能、热输运与相变](architecture/thermal-state.md)

## 维护与未来

- [代码职责和最小验证](maintenance/routing.md)
- [下一产品工作](roadmap/product-roadmap.md)
- [性能与后端评审](roadmap/performance-and-backends.md)
- [验收和发布](roadmap/acceptance-and-release.md)
- [项目运行与构建](../README.md)

## 更新规则

1. 已生效的状态边界或算法契约只写入 `architecture/`。
2. 代码入口或最近验证门变化时更新 `maintenance/routing.md`。
3. 尚未实现且已经决定要做的工作写入 `roadmap/`；探索性草案留在 issue、任务讨论或 Git 分支，不进入规范文档。
4. 实现完成后，把仍有约束力的结论并入 architecture，然后从 roadmap 删除该任务。
5. 每份文档只描述当前行为或尚未实现的工作。
