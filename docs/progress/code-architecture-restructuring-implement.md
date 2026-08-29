# 代码架构重整实施进度

执行 [`../archive/proposals/code-architecture-restructuring-plan.md`](../archive/proposals/code-architecture-restructuring-plan.md) 的进度与 revision 索引。本文不承担规范职责：当前约束以 architecture 与 routing 为准，细节见各提交信息。

| 日期 | 切片 | 交付 | revision |
|---|---|---|---|
| 2026-08-29 | 提案 | 架构重整实施提案入库（S00–S21） | `1cf9e20` |
| 2026-08-29 | S00 | `verifyArchitecture` 静态门（import 方向/locator/包环/行数 allowlist）、`FluidDomainState` raw-bit parity 表征挂入 lifecycle 门、基线文档与 pytool 脚本 | `fc911a8` |
| 2026-08-29 | S01 | 只读观察拆出 `DomainMetrics`/`CellInventoryReader`/`CarrierInventoryReader`；发现并避免 `gaugePressure`/`observedGaugePressure` 诊断计数合并回归；`FlipFluidDomain` −326 行 | `2e564d6` |
| 2026-08-29 | S02 | `LiquidParticleStore` 接管 marker 主列唯一所有权（9 列 + count + capacity，逐字保真）；in-place 算法改用不逃逸 `DirectArrays` | `2ede2ff` |
| 2026-08-29 | S03 | `carrierMutationRevision` + `CarrierMutationCoordinator`（append/swap-remove/wholesale 单入口）；结构变更点清单门；`CarrierMutationValidation` 场景；split/merge/reseed 编排留待 S11 | `bed290d` |
| 2026-08-29 | S04a | `MarkerGridIndex`/`MarkerGridBuilder` 拆出派生 marker 网格；自审修复 wholesale 失效契约不对称；论证 `gridDirty` 的 revision 替换不可表达平流失效，暂缓 | `e74f8f2` |
