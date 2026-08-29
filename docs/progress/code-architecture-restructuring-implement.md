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
| S05b | 提取半边收进 `MaterialTransferPlanner`：`extractChemical`（桶预算与调用方表示检查、逐组 cell 分配、整 marker 移除加末位拆分）、`extractMixture`（确定性 cell/marker 顺序，账本编辑完成后才压缩）、`removeVolumeNear`；`RemovedMaterialLedger` 因碰撞位移路径仍使用而留在 domain | `6dafcf4` |
| S05c | `MaterialTransferPlanner.insertChemical` 收口化学插入（逐 species 二进制体积分配、registry 兼容检查、任意 piece 不可表示即整体拒绝）；`CondensedInventoryClearPlanner` 接管 operator clear/vent 与 liquid/condensed 清空的全部 plan 构建，commit 留在 domain 经 coordinator 执行 | `b5af9e1` |
| S05复审 | 外部审阅修正：pending phase-work 拒答从 exact 提取扩展到全部液体转移（mixture/material/chemical 插入返回失败，at-most 插入/排出返回 0）；at-most pure 插入收进 `MaterialTransferPlanner.insertVolumeAtMost`（cell 容量、marker 预算、区域化学预算与 external-source require 全部前置，管理员 pour 首次把插入的默认 parcel 记入 chemical external source）；`removeParticlesInCell` 拆 plan/commit，escaped-sink ledger 在首个 marker 变更前验证；`WaterClearPlan`/`CondensedClearPlan` 绑定 carrier 结构 revision 与 `ChemicalParcelStore` 新增的 parcel 真值 revision；架构门 import 正则改为完整导入名，persistence/adapter 两条边界规则由 `ArchitectureGateSelfCheck` 违规 fixture 锁定 | `7fac60b` |
| S06 | `GranularDomainState` 接管 granular 真值所有权（registry、active parcel/deposit store、composition pool、聚合 inventory 与 mass/species 审计账本；composition pool 保持延后绑定）；`GranularTransferPlanner` 接管 exact dry-container 插入/提取（piece 推导、邻近 cell 选择、loose packing 容量规划与热能结算，拒答全部前置）；`DomainInventoryLifecyclePlanner` 接管 granular 清空与联合 operator clear 规划，plan 联合列出 SVQ/kg/species/U 并绑定 granular 结构 revision；`GranularParcelStore`/`GranularDepositStore` 新增结构 revision 计数器；precipitation、deposit dynamics 与 mechanics 未迁移，仅改经 state owner 访问 | `0cd10b6` |
| S07a | `GasDomainState` 接管 gas 真值（AirNetwork、sealed boundary、vented flag、previous sealed node count、cell membership/lineage、pressure-active flag）；`GasPressureBoundary` 只读视图承接 pressure stage 的 node 压力/owner 读取与全部 adapter 读取；`airNetwork()` facade 删除，Fabric 156 处调用迁移至 read view；air-demo 的 `setStandardVolume` 变更收敛为 domain 窄接口 `setGasNodeStandardVolume`（网络内部自行记账 source/sink）；topology bootstrap/refresh/remap 迁移与 vent 编排待 S07b/S07c | `818f412` |
| S07b | `GasTopologyCoordinator` 接管 gas topology 全生命周期：bootstrap、restore（含逐 cell membership 校验与 topology key 校验）、export、refresh/remap（stable overlap、adjacent lineage fallback、disappearing-lineage fallback、closed-orphan 归并规则与异常分类逐字保留）、`refreshAvailableVolume`、ambient exchange（orifice transport 的 `step(dt)`）与 persistence 同步；`AirCavityScanner` scratch 归属 coordinator，`GasTopologyPlan` 随迁；domain 仅保留一行委托 | `0f1e554` |
| S07c | `GasLifecyclePlanner` 收口 sealed-gas vent 的联合 plan/commit（gas inventory + gas energy + audit：pending/审计闭合/stale-plan 校验前置，commit 一趟把全部 sealed cell 转为 inventory-free ambient topology；thermal gas energy 仍由 thermal state 规划与结算，不在 gas state 复制）；`CondensedInventoryClearPlanner` 回归 condensed 单一职责。S07 完成：`airNetwork()` facade 已删，persistence 仅经 domain facade 取快照，5 门全过 | `50608e0` |
| S08 | `PressureProjectionStage` 接管压力投影全管线（gas lineage 刷新、reference anchoring、含表面张力/体积漂移/granular 移动固体贡献的 target divergence 装配、`projectVelocities`）；`PressureSystem` 接管 PCG 循环、矩阵应用与对角预条件（循环顺序、float/double 类型、绝对 RMS 容差、迭代预算逐字保留，结果经 `PressureProjectionResult` 返回，未收敛由 stage 抛出原文 typed exception）；`PressureBoundaryView` 作为 stage 的 gas 压力只读缝；pending phase-work plan 仍在 solve 之后、`projectVelocities` 之前由 step 管线生成。被表面张力与相变路径共享的 helper（face open、interface theta、variable-density 链、carrier face）留在 domain 供 stage 调用；旧 `solvePressure`/`projectVelocity` 删除 | `46890dc` |
| S09a | `GranularMechanicalStage` 接管 substep 内 granular 力学全簇：parcel 速度推进（采样载体流的 `Environment` 随迁，含 `addFaceImpulse` 面冲量散布）、碰撞容量 preview 与本地几何消解、pressure coupling 换轨、advection 后 collision commit；返回 typed `StepStats`，boundary impulse 累加留在 step 管线；granular compaction/deposit dynamics 属逻辑轴后半，仍留 domain 待 S11 | `b130b5f` |
| S09b | `BodyForceStage` 接管 porous damping（Koerny-Carman 渗透率链随迁）、gravity、Boussinesq thermal buoyancy、solid-face velocity enforcement；`GridToParticleStage` 接管 air 外推 + FLIP/PIC 混合（`FLIP_RATIO`/`lerp` 随迁，rebuild 路径改调 stage）；`ParticleAdvectionStage` 接管碰撞平流与 chemical dirty-cell 首尾标记。lifecycle 耦合的 scratch（buoyancy 列、chemical trail、extrapolator、collision resolver）留在 domain；liquid-pair surface tension force 链与 P2G/pipeline 组装待 S09c | （由下一次提交补记） |

revision 列由该切片的下一次提交补记，避免提交引用自身哈希。

## 已定边界决策（审阅时按此为准）

- `gridDirty` 保留为显式失效标志，不替换为 revision 门：它混合载体结构变更、几何编辑与每 substep 平流位置三类失效源，结构 revision 无法表达位置失效；revision 门等待输入真正可 revision 化的 workspace。
- cell 尺寸（sizeX/Y/Z/cellCount）留在 domain 作为不可变身份，未迁入 `DomainGeometry`；`geometryRevision` 因尚无消费者而未引入。
- 化学列的整批重建（restore 路径）与 `thermalState.restore` 是列替换而非序数变更，不受 coordinator 管辖。
- S03 的 plan 目前在生产路径内联构造，revision 绑定的真正跨期调用方自 S05 的 transfer planner 开始出现。
- split/merge/reseed 的 plan 化编排保留在 reseeder，待 S11 阶段化时收敛。
- 碰撞批次位移的化学 escaped-sink ledger 仍在位移完成后的恢复/记账点验证；其前置验证属于 `CollisionEditPlan` 批次规划，待碰撞流切片收口。本轮修复的是 drain 与 `setVolume` 路径经 `removeParticlesInCell` 的逐格原子性。
- granular clear plan 不绑定 liquid carrier revision：联合 operator clear 先提交 condensed（推进 carrier revision），且 granular 真值与 marker 结构无关；granular 真值由 granular 结构 revision 与 SVQ/质量/U raw bits 绑定，thermal revision 待 S10 引入。
- pressure projection 的 anchor/reference/iterations/residual 写回 domain 字段是有意保留：reference 与 pressure 数组是跨 stage 的权威状态（同一 logical step 内的 phase transfer 与状态输出消费），不属于 S09 点 5 要清除的 stage 间私有 scratch；stage 的全部 gas 读取统一经 `PressureBoundaryView`。
