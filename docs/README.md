# 文档目录

开发阅读顺序与固定要求见 [AGENTS.md](../AGENTS.md)。接到具体需求时，用 [项目 README 的需求表](../README.md#接到需求后从这里开始)定位专题；当前推进顺序与完成条件查 [产品路线](roadmap/product-roadmap.md)。下表供按主题查阅；每份专题都在开头列出代码入口和测试。

## 当前实现

| 文档 | 什么时候读 |
|---|---|
| [系统总览](architecture/system-overview.md) | 需要了解模块依赖、数据归属和完整计算流程 |
| [运行时与区域操作](architecture/runtime-and-lifecycle.md) | 修改启动、调度、暂停、区域预算、控制器查询或确认操作 |
| [流体求解器](architecture/fluid-solver.md) | 修改压力、自由面、粒子传递、碰撞或计算后端 |
| [材料状态](architecture/material-state.md) | 修改材料注册、质量、混合与液液界面 |
| [气体状态](architecture/gas-state.md) | 修改气腔扫描、气体库存、状态方程或孔流 |
| [化学状态](architecture/chemistry-state.md) | 修改组分、反应、指示剂或化学输运 |
| [颗粒状态](architecture/granular-state.md) | 修改颗粒运动、沉积、孔隙水或固液转移 |
| [热学状态](architecture/thermal-state.md) | 修改内能、导热、相变或有限热设备 |
| [区域存档](architecture/region-state.md) | 修改世界能力包、存档格式、提交顺序或恢复 |
| [世界归属与容器事务](architecture/world-ownership-and-containers.md) | 修改接管/释放世界方块、桶、发射器或事务日志 |
| [外部 API](architecture/external-api.md) | 修改查询、提交通知、探针或 Jade 适配 |

## 开发与发布

- [按需求和故障找代码](maintenance/routing.md)：游戏入口、客户端、物理模块和诊断路径。
- [验证指南](maintenance/validation.md)：按改动选择测试、静态检查与 GPU 验证。
- [正常操作 fuzzer](maintenance/fuzz-harness.md)：随机操作与极端状态下的强制中断排查工具、模式与覆盖边界。
- [命令速查](maintenance/commands.md)：游戏内开发、诊断和恢复命令。
- [Python 工具](maintenance/pytool.md)：源码规模、import 和 JFR 分析。
- [产品路线](roadmap/product-roadmap.md)：后续功能的顺序和完成条件。
- [性能与后端规划](roadmap/performance-and-backends.md)：性能指标、范围扩张和生产 GPU 要求。
- [验收与发布](roadmap/acceptance-and-release.md)：发布前检查与部署。

## 历史资料

[自由面探索索引](archive/free-surface-unification-research.md)整理可复用成果、数值反例和离线对照入口。[版本历史](progress/version-history.md)记录各次发布的变化。[归档目录](archive/README.md)保存旧提案、审查记录和已完成的实施报告。查找当前代码时使用上面的专题。

## 维护这些文档

新增或调整功能时，更新它对应的专题。代码改名、移动或测试入口变化时，同时修正该专题开头的链接；需求分类变化时再调整项目 README 和维护路由。

当前算法、数据格式和提交步骤写在 `architecture/`。未来工作写在 `roadmap/`，完成后把仍需维护的设计说明移入对应专题，再删去已完成的计划。发布变化记在版本历史中。

路由页负责告诉读者去哪里，专题负责解释那里如何工作。一个规则在最贴近实现的专题中完整说明，其他入口链接到它。链接应指向真实文件或具体章节，代码路径写全。

写作时先说谁负责什么，再分段说明执行顺序、数据和失败处理。一次操作有多个步骤时用列表；比较入口时用表格。保留便于搜索的类名、状态名和公式，并在首次出现时解释意思。

过时文档原样移入 `archive/`，更新当前入口中的引用。会话交接时提供下一步目标；固定要求、现行契约与任务完成条件分别由 AGENTS.md、架构专题和产品路线维护。
