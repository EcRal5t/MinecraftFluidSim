# 代码架构重整实施进度

执行 [`../archive/proposals/code-architecture-restructuring-plan.md`](../archive/proposals/code-architecture-restructuring-plan.md) 的交付结果与 revision 索引，供后续代码审阅定位每次结构变化的范围。当前约束以 architecture 与 routing 为准；每个切片的验证门与实现细节见对应提交信息。

## 切片交付

| 切片 | 交付 | revision |
|---|---|---|
| 提案 | 架构重整实施提案（S00–S21）入库 | `1cf9e20` |
| S00 | `verifyArchitecture` 静态门：import 方向、静态 locator、core 包环、1000 行硬门（allowlist 逐项绑定关闭切片）；`FluidDomainState` raw-bit 逐位 parity 表征（capture→restore→capture、phase-off/on candidate→restore→capture）挂入既有 lifecycle 门；基线指标见 `docs/maintenance/architecture-baseline.md` | `fc911a8` |
| S01 | `FlipFluidDomain` 的全部无副作用观察拆为 `DomainMetrics`、`CellInventoryReader`、`CarrierInventoryReader` 三个 package-private 类，public 签名保留为一行委托；气体压力 facade 保留原实现（`gaugePressure` 推进诊断计数，`observedGaugePressure` 是只读变体，二者不可合并） | `2e564d6` |
| S02 | `LiquidParticleStore` 成为 marker 主列（位置×3、速度×3、体积、solver 质量、材料 handle、压力相标签 + count + capacity）的唯一所有者；初始容量、增长公式、预算异常与 swap-remove 顺序逐字迁移；reseed、平流、化学反应扫描与显示快照以 solver 内不逃逸的 `DirectArrays` 视图继续整列消费 | `2ede2ff` |
| S03 | `carrierMutationRevision` + `CarrierMutationCoordinator`：marker 结构变更收敛为 append、swap-remove、wholesale count 三个原语，plan 绑定 revision、提交顺序固定（主列→化学列→热列→count→revision→网格失效），注册/容量/兼容性拒答全部前移到 plan 阶段；源码清单门（挂入 verifyArchitecture）钉死结构调用只出现在 coordinator；`CarrierMutationValidation` 覆盖空域、last/middle 序数、容量边界、stale plan 与 wholesale 预算场景 | `bed290d` |
| S04a | `MarkerGridIndex`/`MarkerGridBuilder` 接管派生 marker 网格（cell 归属链与计数、逐格精确体积/质量、重构密度、压力相 occupancy 条目）；per-marker 散射环整体保留在 builder 内以维持原浮点累加顺序 | `e74f8f2` |
| S04b | `MacVelocityGrid`、`PressureProjectionWorkspace`、`SurfaceTensionWorkspace` 接管 MAC、PCG 与表面张力三类可丢弃 workspace；`MarkerGridBuilder` 与观察 reader 改经 owner 访问网格 | `56436fa` |
| S04c | `DomainGeometry` 接管权威 solid mask 与 cut-cell 微几何；仅有的两个写点是碰撞编辑提交与 wholesale restore | `b7ca809` |
| S05a | `MaterialTransferPlanner` 接管 pure/material/mixture 精确插入路径：共享容量规划、piece 推导、化学预算与外部 source ledger、热插入 plan 全部在 planning 阶段完成，提交段经 spawn→coordinator 写入且无业务拒答；public 重载链保留委托；提取（extract）半边待 S05b | `a6636ca` |

## 已定边界决策（审阅时按此为准）

- `gridDirty` 保留为显式失效标志，不替换为 revision 门：它混合载体结构变更、几何编辑与每 substep 平流位置三类失效源，结构 revision 无法表达位置失效；revision 门等待输入真正可 revision 化的 workspace。
- cell 尺寸（sizeX/Y/Z/cellCount）留在 domain 作为不可变身份，未迁入 `DomainGeometry`；`geometryRevision` 因尚无消费者而未引入。
- 化学列的整批重建（restore 路径）与 `thermalState.restore` 是列替换而非序数变更，不受 coordinator 管辖。
- S03 的 plan 目前在生产路径内联构造，revision 绑定的真正跨期调用方自 S05 的 transfer planner 开始出现。
- split/merge/reseed 的 plan 化编排保留在 reseeder，待 S11 阶段化时收敛。
