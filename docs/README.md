# Real Water 文档索引

这里是仓库文档的唯一入口。先按问题类型选择目录，不要在所有 Markdown 中全文猜测当前规范。

| 目录 | 唯一职责 | 不应包含 |
|---|---|---|
| [`architecture/`](architecture/fluid-solver.md) | 当前已经生效的系统契约、状态边界和格式 | 未来计划、逐提交历史、验收流水账 |
| [`maintenance/`](maintenance/routing.md) | 当前代码职责路由、最小测试选择和会话交接 | 里程碑实现史、产品愿景 |
| [`progress/`](progress/) | 已完成里程碑、版本历史、自动门与实机验收证据 | 未承诺的未来设计 |
| [`roadmap/`](roadmap/product-roadmap.md) | 尚未完成的产品目标、里程碑和停止条件 | 已完成阶段的实现细节 |
| [`archive/`](archive/README.md) | 被替代的旧文档和非规范提案 | 当前决策或必须执行的维护规则 |

## 常用入口

- 当前 CPU 流体不变量：[`architecture/fluid-solver.md`](architecture/fluid-solver.md)
- 当前区域存档格式：[`architecture/region-state-v1.md`](architecture/region-state-v1.md)
- 代码在哪里、改完跑什么：[`maintenance/routing.md`](maintenance/routing.md)
- 已完成到哪里：[`progress/README.md`](progress/README.md)
- 下一阶段做什么：[`roadmap/product-roadmap.md`](roadmap/product-roadmap.md)
- 项目运行命令与构建：仓库根目录 [`README.md`](../README.md)

## 更新规则

1. 当前行为变化，修改 `architecture/`；不要在架构文档里追加“某天完成了什么”。
2. 代码职责或最小验证变化，修改 `maintenance/routing.md`。
3. 里程碑关闭，把结果和证据写入 `progress/`，同时从 roadmap 删除已完成的实施细节，只保留新的未来边界。
4. 新设想先进入 `archive/proposals/`；只有评审通过的未来约束才进入 roadmap。
5. 被替代但仍有追溯价值的长文移入 `archive/`，不要让多个文档同时自称当前规范。
6. 文档重命名或移动时必须更新仓库内全部链接；文档改动本身不运行代码测试。
