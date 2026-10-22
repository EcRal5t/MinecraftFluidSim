# 文档目录

日常跨模块开发使用三份主文档：

| 主文档 | 唯一维护的内容 |
|---|---|
| [当前设计书](architecture/system-overview.md) | 当前模型、完整职责链、跨模块契约和代码索引 |
| [当前实施计划](roadmap/solver-consolidation-plan.md) | 当前进度、下一轮交付、依赖及完成条件 |
| [验证指南](maintenance/validation.md) | 命令、场景、误差与预算判定、最新验证证据 |

首次阅读规则见 [AGENTS.md](../AGENTS.md)。先完整理解设计书与实施计划，再按改动进入下列参考。
专题开头的修改入口用于继续定位具体文件。

## 按需参考

| 需要查什么 | 文档 |
|---|---|
| 后端字段、MAC、PLIC、压力、速度传递与碰撞离散 | [流体离散参考](architecture/fluid-solver.md) |
| 材料定义、质量、黏度与混合物换算 | [材料参考](architecture/material-state.md) |
| 气腔几何、库存分配、状态方程与有限孔流 | [气体参考](architecture/gas-state.md) |
| 物种、反应与 pH 展示 | [化学参考](architecture/chemistry-state.md) |
| 展示快照、表面绘制、材质与 Iris 接口依据 | [流体展示参考](architecture/fluid-presentation.md) |
| 颗粒接触、整数占容、床层与固液转移 | [颗粒参考](architecture/granular-state.md) |
| 状态函数、热接触、相变及内能分配 | [热学参考](architecture/thermal-state.md) |
| 启动、输入刷新、调度、授权与区域操作 | [运行时参考](architecture/runtime-and-lifecycle.md) |
| 能力包、checkpoint 字段、HEAD 与冷恢复 | [存档参考](architecture/region-state.md) |
| 世界归属、容器载荷、WAL 与恢复分类 | [事务参考](architecture/world-ownership-and-containers.md) |
| 外部查询、提交事件、线程和单位 | [接口参考](architecture/external-api.md) |
| 后续玩家能力、产品阶段与范围 | [长期产品路线](roadmap/product-roadmap.md) |
| 范围扩张与完整 GPU 后端要求 | [后端扩展参考](roadmap/performance-and-backends.md) |
| 发布检查与部署 | [验收与发布](roadmap/acceptance-and-release.md) |
| 随机操作与短重放 | [Fuzzer](maintenance/fuzz-harness.md) |
| 游戏命令、Python 工具及输出清理 | [命令](maintenance/commands.md)、[pytool](maintenance/pytool.md)、[构建输出](maintenance/build-outputs.md) |

## 历史资料

[归档目录](archive/README.md)保存历史方案、反例与阶段记录，[版本历史](progress/version-history.md)保存发布变化。[自由面探索索引](archive/free-surface-unification-research.md)提供旧实验和离线对照入口。

## 更新规则

- 模型或职责链改变：更新设计书；稳定公式、数据归属或提交契约改变时更新对应参考。
- 代码移动或调用迁移：更新设计书的阶段索引及涉及的专题入口。
- 进度改变：只在实施计划登记完成项、剩余项和下一步，链接对应验证证据。
- 运行验证：在验证指南记录被测构建、原输入、完成步数、结果及计时口径；旧结果移入归档。
- 长期目标改变：更新产品路线；当前施工进度继续由实施计划维护。

同一条规则在其负责的文档完整说明，其他页面链接过去。入口目录保持简短，历史探索留在 Git、会话和归档中。

## 内容与排版

- 设计文档保留模型、数据归属、单位、提交顺序、守恒与恢复契约，以及影响使用的近似条件。
  缓存布局、搜索分支、求解器内部阈值和测试夹具参数由代码与测试维护，文档链接到职责入口。
- 每段说明一个主题，长段按完整句子换行；较长句子在自然停顿处换行，代码和链接保持完整。
- 文件索引和多条件流程使用分组列表，每个文件链接独占一行；简短对照继续使用表格。
- 新内容先查现有归属，优先修改对应章节。详细算法说明仅在理解模型或安全修改确有需要时保留。
