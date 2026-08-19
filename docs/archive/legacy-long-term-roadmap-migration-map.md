# 旧长期路线迁移覆盖表

本表说明 [`legacy-long-term-roadmap.md`](legacy-long-term-roadmap.md) 每一节现在由哪里负责。旧文件保留逐字快照，但不再承担规范职责。

| 旧章节 | 当前归属 |
|---|---|
| 零、发布切割线 | [产品路线](../roadmap/product-roadmap.md)、[验收与发布门](../roadmap/acceptance-and-release.md) |
| 一、产品目标与近期不做 | [产品路线](../roadmap/product-roadmap.md)，平台/规模限制另见 [性能与后端](../roadmap/performance-and-backends.md) |
| 二、物理/数值/渲染/调度/事务/协议/feature flag 分层 | [跨层契约](../roadmap/cross-cutting-contracts.md)，调度细节见 [性能与后端](../roadmap/performance-and-backends.md) |
| 三、目标架构、区域、section 持久化、ownership | [世界集成](../roadmap/world-integration.md)；当前已实现格式见 [`region-state v1`](../architecture/region-state-v1.md) |
| 四、材料、混合物与化学模型 | [材料与化学](../roadmap/materials-and-chemistry.md) |
| 五、多密度、自由表面、实体耦合 | [流体与界面](../roadmap/fluids-and-interfaces.md)；已完成 M2.5 见 [进度索引](../progress/README.md) |
| 六、空气与其他气体 | [气体与颗粒](../roadmap/gases-and-granular.md) |
| 七、颗粒物与沉积物 | [气体与颗粒](../roadmap/gases-and-granular.md) |
| 八、桶、控制器与世界桥 | [世界集成](../roadmap/world-integration.md) |
| 九、性能与 GPU | [性能与后端](../roadmap/performance-and-backends.md)；已完成 probe 细节见 [M1 报告](../progress/m1-gpu-probe-report.md) |
| 十、M0–M10 实施与验收 | 未完成阶段见 [产品路线](../roadmap/product-roadmap.md) 及其主题路由；M0–M2.5 见 [进度索引](../progress/README.md) |
| 十一、统一测试矩阵 | [验收与发布门](../roadmap/acceptance-and-release.md) |
| 十二、可行性与停止条件 | [验收与发布门](../roadmap/acceptance-and-release.md)、[性能与后端](../roadmap/performance-and-backends.md) |
| 十三、接下来三项 | [产品路线](../roadmap/product-roadmap.md) |

迁移原则：

- 已完成事实进入 `progress/`。
- 当前生效行为进入 `architecture/`。
- 未实现但仍有效的技术约束进入 `roadmap/`。
- 已被替代的措辞、日期状态和旧路径只留在 archive。
