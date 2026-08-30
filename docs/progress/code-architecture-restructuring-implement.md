# 代码架构重整实施进度

执行 `../archive/proposals/code-architecture-restructuring-plan.md`的交付结果与 revision 索引，供后续代码审阅定位每次结构变化的范围。当前约束以 architecture 与 routing 为准；每个切片的验证门与实现细节见对应提交信息。

## 切片交付

### 提案 (`1cf9e20`)

* 架构重整实施提案（S00–S21）入库

### S00 (`fc911a8`)

* `verifyArchitecture` 静态门：import 方向、静态 locator、core 包环、1000 行硬门（allowlist 逐项绑定关闭切片）
* `FluidDomainState` raw-bit 逐位 parity 表征（capture→restore→capture、phase-off/on candidate→restore→capture）挂入既有 lifecycle 门
* 基线指标见 `docs/maintenance/architecture-baseline.md`

### S01 (`2e564d6`)

* `FlipFluidDomain` 的全部无副作用观察拆为 `DomainMetrics`、`CellInventoryReader`、`CarrierInventoryReader` 三个 package-private 类，public 签名保留为一行委托
* 气体压力 facade 保留原实现（`gaugePressure` 推进诊断计数，`observedGaugePressure` 是只读变体，二者不可合并）

### S02 (`2ede2ff`)

* `LiquidParticleStore` 成为 marker 主列（位置×3、速度×3、体积、solver 质量、材料 handle、压力相标签 + count + capacity）的唯一所有者
* 初始容量、增长公式、预算异常与 swap-remove 顺序逐字迁移
* reseed、平流、化学反应扫描与显示快照以 solver 内不逃逸的 `DirectArrays` 视图继续整列消费

### S03 (`bed290d`)

* `carrierMutationRevision` + `CarrierMutationCoordinator`：marker 结构变更收敛为 append、swap-remove、wholesale count 三个原语，plan 绑定 revision、提交顺序固定（主列→化学列→热列→count→revision→网格失效），注册/容量/兼容性拒答全部前移到 plan 阶段
* 源码清单门（挂入 verifyArchitecture）钉死结构调用只出现在 coordinator
* `CarrierMutationValidation` 覆盖空域、last/middle 序数、容量边界、stale plan 与 wholesale 预算场景

### S04a (`e74f8f2`)

* `MarkerGridIndex`/`MarkerGridBuilder` 接管派生 marker 网格（cell 归属链与计数、逐格精确体积/质量、重构密度、压力相 occupancy 条目）
* per-marker 散射环整体保留在 builder 内以维持原浮点累加顺序

### S04b (`56436fa`)

* `MacVelocityGrid`、`PressureProjectionWorkspace`、`SurfaceTensionWorkspace` 接管 MAC、PCG 与表面张力三类可丢弃 workspace
* `MarkerGridBuilder` 与观察 reader 改经 owner 访问网格

### S04c (`b7ca809`)

* `DomainGeometry` 接管权威 solid mask 与 cut-cell 微几何
* 仅有的两个写点是碰撞编辑提交与 wholesale restore

### S05a (`a6636ca`)

* `MaterialTransferPlanner` 接管 pure/material/mixture 精确插入路径：共享容量规划、piece 推导、化学预算与外部 source ledger、热插入 plan 全部在 planning 阶段完成，提交段经 spawn→coordinator 写入且无业务拒答
* public 重载链保留委托
* 提取（extract）半边待 S05b

### S05b (`6dafcf4`)

* 提取半边收进 `MaterialTransferPlanner`：`extractChemical`（桶预算与调用方表示检查、逐组 cell 分配、整 marker 移除加末位拆分）、`extractMixture`（确定性 cell/marker 顺序，账本编辑完成后才压缩）、`removeVolumeNear`
* `RemovedMaterialLedger` 因碰撞位移路径仍使用而留在 domain

### S05c (`b5af9e1`)

* `MaterialTransferPlanner.insertChemical` 收口化学插入（逐 species 二进制体积分配、registry 兼容检查、任意 piece 不可表示即整体拒绝）
* `CondensedInventoryClearPlanner` 接管 operator clear/vent 与 liquid/condensed 清空的全部 plan 构建，commit 留在 domain 经 coordinator 执行

### S05复审 (`7fac60b`)

* 外部审阅修正：pending phase-work 拒答从 exact 提取扩展到全部液体转移（mixture/material/chemical 插入返回失败，at-most 插入/排出返回 0）
* at-most pure 插入收进 `MaterialTransferPlanner.insertVolumeAtMost`（cell 容量、marker 预算、区域化学预算与 external-source require 全部前置，管理员 pour 首次把插入的默认 parcel 记入 chemical external source）
* `removeParticlesInCell` 拆 plan/commit，escaped-sink ledger 在首个 marker 变更前验证
* `WaterClearPlan`/`CondensedClearPlan` 绑定 carrier 结构 revision 与 `ChemicalParcelStore` 新增的 parcel 真值 revision
* 架构门 import 正则改为完整导入名，persistence/adapter 两条边界规则由 `ArchitectureGateSelfCheck` 违规 fixture 锁定

### S06 (`0cd10b6`)

* `GranularDomainState` 接管 granular 真值所有权（registry、active parcel/deposit store、composition pool、聚合 inventory 与 mass/species 审计账本；composition pool 保持延后绑定）
* `GranularTransferPlanner` 接管 exact dry-container 插入/提取（piece 推导、邻近 cell 选择、loose packing 容量规划与热能结算，拒答全部前置）
* `DomainInventoryLifecyclePlanner` 接管 granular 清空与联合 operator clear 规划，plan 联合列出 SVQ/kg/species/U 并绑定 granular 结构 revision
* `GranularParcelStore`/`GranularDepositStore` 新增结构 revision 计数器
* precipitation、deposit dynamics 与 mechanics 未迁移，仅改经 state owner 访问

### S07a (`818f412`)

* `GasDomainState` 接管 gas 真值（AirNetwork、sealed boundary、vented flag、previous sealed node count、cell membership/lineage、pressure-active flag）
* `GasPressureBoundary` 只读视图承接 pressure stage 的 node 压力/owner 读取与全部 adapter 读取
* `airNetwork()` facade 删除，Fabric 156 处调用迁移至 read view
* air-demo 的 `setStandardVolume` 变更收敛为 domain 窄接口 `setGasNodeStandardVolume`（网络内部自行记账 source/sink）
* topology bootstrap/refresh/remap 迁移与 vent 编排待 S07b/S07c

### S07b (`0f1e554`)

* `GasTopologyCoordinator` 接管 gas topology 全生命周期：bootstrap、restore（含逐 cell membership 校验与 topology key 校验）、export、refresh/remap（stable overlap、adjacent lineage fallback、disappearing-lineage fallback、closed-orphan 归并规则与异常分类逐字保留）、`refreshAvailableVolume`、ambient exchange（orifice transport 的 `step(dt)`）与 persistence 同步
* `AirCavityScanner` scratch 归属 coordinator，`GasTopologyPlan` 随迁
* domain 仅保留一行委托

### S07c (`50608e0`)

* `GasLifecyclePlanner` 收口 sealed-gas vent 的联合 plan/commit（gas inventory + gas energy + audit：pending/审计闭合/stale-plan 校验前置，commit 一趟把全部 sealed cell 转为 inventory-free ambient topology；thermal gas energy 仍由 thermal state 规划与结算，不在 gas state 复制）
* `CondensedInventoryClearPlanner` 回归 condensed 单一职责
* S07 完成：`airNetwork()` facade 已删，persistence 仅经 domain facade 取快照，5 门全过

### S08 (`46890dc`)

* `PressureProjectionStage` 接管压力投影全管线（gas lineage 刷新、reference anchoring、含表面张力/体积漂移/granular 移动固体贡献的 target divergence 装配、`projectVelocities`）
* `PressureSystem` 接管 PCG 循环、矩阵应用与对角预条件（循环顺序、float/double 类型、绝对 RMS 容差、迭代预算逐字保留，结果经 `PressureProjectionResult` 返回，未收敛由 stage 抛出原文 typed exception）
* `PressureBoundaryView` 作为 stage 的 gas 压力只读缝
* pending phase-work plan 仍在 solve 之后、`projectVelocities` 之前由 step 管线生成
* 被表面张力与相变路径共享的 helper（face open、interface theta、variable-density 链、carrier face）留在 domain 供 stage 调用
* 旧 `solvePressure`/`projectVelocity` 删除

### S09a (`b130b5f`)

* `GranularMechanicalStage` 接管 substep 内 granular 力学全簇：parcel 速度推进（采样载体流的 `Environment` 随迁，含 `addFaceImpulse` 面冲量散布）、碰撞容量 preview 与本地几何消解、pressure coupling 换轨、advection 后 collision commit
* 返回 typed `StepStats`，boundary impulse 累加留在 step 管线
* granular compaction/deposit dynamics 属逻辑轴后半，仍留 domain 待 S11

### S09b (`f0dba7a`)

* `BodyForceStage` 接管 porous damping（Koerny-Carman 渗透率链随迁）、gravity、Boussinesq thermal buoyancy、solid-face velocity enforcement
* `GridToParticleStage` 接管 air 外推 + FLIP/PIC 混合（`FLIP_RATIO`/`lerp` 随迁，rebuild 路径改调 stage）
* `ParticleAdvectionStage` 接管碰撞平流与 chemical dirty-cell 首尾标记
* lifecycle 耦合的 scratch（buoyancy 列、chemical trail、extrapolator、collision resolver）留在 domain
* liquid-pair surface tension force 链与 P2G/pipeline 组装待 S09c

### S09c (`93753a2`)

* liquid-pair surface tension force 链（逐相对 color 场、平滑、法线、成对曲率与面力散布）迁入 `BodyForceStage`
* 与 pressure 侧共享的 color/normals/curvature 机制留在 domain 放宽共用（`rebuildSurfaceTensionPressure` 属 rebuild 尾部的 pressure seam，不迁移）

### S09d (`bd37dd3`)

* `ParticleToGridStage` 接管 P2G 重建链：marker 网格散射与 PIC 归一化、P2G 权重快照、free-surface/压力分类/granular coupling/gas lineage/表面张力压力五个派生视图重建，尾部经兄弟 stage 完成 solid-face enforce 与 air 外推（构造期显式注入，不经 facade 字段）
* `rebuildGridFromParticles` 保留为一行委托，restore 直调路径零改动

### S09e (`4171585`)

* `MechanicalStepPipeline` 组装单个力学 substep：gas step → P2G → granular → porous/gravity/buoyancy/tension → enforce → pressure solve → pending phase-work plan/consume → velocity projection → G2P → advection → collision commit，逐字保留顺序与审计增量
* 返回 typed `SubstepOutcome`（pressure 迭代数、granular stats、pending-work 消费标志），boundary impulse 累加留在 step 循环
* `MechanicalAuditAccumulator` 抽为独立文件（pipeline 填力学段，domain 填 carrier/reseed 段并以原 ULP 限值收口）
* `step` 的力学部分只剩 substep 循环内一次 pipeline 调用
* S09 完成：11 门全过，lifecycle 门含 phase-off/on candidate raw-bit parity 表征

### S10a (`52cf213f`)

* `cc.ecisr.mmm.units.SignedDyadic` 下沉（携带 `MAX_SIGNIFICAND_BITS` 预算，`ThermalLimits` 旧常量删除），`fluid.air→thermal` 边消除，core 包环清零并删除 CYCLE_ALLOWLIST
* `FlipThermalState.MarkerContext` 上提为 thermal 包 `ThermalCarrierContext`（thermal owner 读载体真值的正式入口）
* `ThermalAuditState` 接管审计账本所有权（每处 `withX` 链保持单次原子替换）并自带 revision 计数
* `LiquidEnergyColumn` 接管液体序数列（能量序数存储、精确和、逐温建模、液体序数提交与审计记账，`resetMarkerTemperature` 的跨 carrier 全域审计增量留在 facade）
* `FlipThermalState` 对液体路径转为一行委托

### S10b (`30e1856e`)

* `GasEnergyStore` 接管 gas 节点能量列与 gas 热模型绑定（节点读写、压力/温度/密度反演、remap/vent/transfer 能量 plan 及其审计记账、restore 前置校验、revision 计数）
* region 级联合库存闭合检查（`planGasVent` 的 `exactEnergy` 对照）留在 facade，plan 构建下沉 store
* `GranularEnergyState` 接管 granular 与 coherent 固体能量真值（`GranularEnergyColumn` + `CoherentPhaseStore`、全部序数/键石编辑及审计记账、granular 建模与 restore 入口、revision 计数）
* `GasRemapPlan`/`GasVentPlan` 随 owner 迁移

### S10c (`b1297047`)

* `ThermalDomainState` 成为 thermal 聚合 owner（registry 映射、派生 LTE 网格、transport、四族 carrier owner、audit state 与 finite-device coordinator 的组装，cell 网格协调、granular 反应 plan、phase candidate、pending phase-work、repartition 与显示快照逻辑逐字迁入）
* `FiniteDeviceExchangeCoordinator` 接管有限器件接触/联合 cell plan/回滚安全提交（device U 不回 BlockEntity，状态与交换审计留在 region）
* `FlipThermalState` 降到 412 行纯委托（仅 capture/restore 与 reseed adapter 因绑定 fluid 侧 persistence/reseeder 类型而留在 facade），SIZE_ALLOWLIST 删除其条目，adapter 边界规则扩展到 thermal 可变 owner
* `thermalRevision()` 聚合各 owner 单调 revision

### S10复审 (`e13174d8`)

* 外部审阅修正（S06–S10 复核）：`removeVolumeNear` 改为跨 cell 联合 plan（`removeVolumeAcrossCells`：全部 cell 先对共享 escaped-sink ledger 规划、联合 ledger 一次 require、统一截断后一次清扫、一次记账，任一 cell 拒绝则整体不变）
* `commitOperatorClear` 对 condensed/granular/gas topology 三个子计划统一 preflight 后才进入无拒绝提交（granular 审计总量检查前移到 `inventory.clear()` 之前——`clear()` 统计在删除前采集，与原 post-clear 检查逐位等价；gas topology preflight 以新鲜几何扫描比对 plan 的 sealed 计数与 cell membership）
* granular 插入的 thermal 规划改为 composition 先行 + `DraftParcelContext` overlay（不在规划期触碰 parcel store，候选失败不再推进 mutationRevision）
* granular extraction 的 removedEnergy 溢出检查前移到首个删除之前
* thermal owner 可变面收窄（`GasEnergyStore.nodeEnergies()` 返回防御拷贝、各 owner 增量变更器 package-private 化、facade reseed/restore 经 `ThermalDomainState` 显式 seam、组件级 store 测试迁入 thermal 测试包）

### S10复审收口 (`d113ea9`, `e31e77d`)

* 二次复核修正：gas inventory/energy remap plan 同时绑定各自 revision，拓扑 refresh 与 operator clear 在任一 owner 修改前完成联合 preflight 和完整 audit successor 计算
* granular composition 失败回滚恢复 LIFO allocation
* thermal owner 写面继续收窄
* 力学 pipeline/stage 改为显式 `Workspace` + 各 stage 窄 `Access`，一次性接线集中在 `MechanicalStageAssembly`，不再保存 `FlipFluidDomain`
* `ThermalCellOperations` 接管无库存的 LTE/reaction/phase/work/repartition/display 算法，`ThermalDomainState` 收敛为 owner/coordinator 聚合与稳定委托面

### S10终审补漏 (`92a0951`)

* 审计发现 `thermalRevision()` 把四个 owner revision 相加会让不同 owner-generation tuple 碰撞，并存在 long overflow/ABA
* 改为绑定 liquid/granular/gas/audit 四列的 exact `ThermalDomainState.Revision` 值对象，operator clear 与 lifecycle plan 按完整 tuple 判 stale

### S11a (`811579d`)

* 建立 `ThermochemistryPipeline` 与一次性 `ThermochemistryStageAssembly`，生产顺序唯一化为 conduction→chemical reaction→granular reaction→condensed phase→gas phase
* 传导和液相 reaction 算法迁出 aggregate，standalone thermal/phase 入口复用同一 stage

### S11b (`d9d1e1a`)

* `GranularTransitionStage` 接管 active compaction/deposit transition，`ParticleReseedStage` 接管 reseed 及 thermal roundoff/grid rebuild
* 两者由 `LogicalStepTailAssembly` 显式接线，reseed 仍经 carrier mutation coordinator 同序更新 side column

### S11c (`38ab801`)

* `GranularReactionStage` 接管 exact precipitation/dissolution 的候选、solid/liquid composition、容量、thermal joint plan、pending volume 与提交
* aggregate 中重复实现删除，共享 helper 只保留真实跨 stage 消费者

### S11完成 (`2c9abbb`)

* `CondensedPhaseChangeStage` 与 `GasLiquidPhaseChangeStage` 实际接管固液/气液 exact-batch 搜索及提交
* `PhaseChangeStageSupport` 只共享 composition、marker removal/spawn、pending volume 和受控 topology/coherent seam，不保存 aggregate
* `FlipFluidDomain` 删除 1000 余行相变算法，`step` 只组合 mechanical pipeline、thermochemistry pipeline、granular transition、reseed 与审计诊断
* S11 七个 selector 与架构门通过，live budget median/max 为 4.272/8.633 ms（门限 40 ms）

### S12a (`1db5d34`)

* 将真实求解器聚合根从 `FlipFluidDomain` 机械切换为 `FluidDomain`；core、Fabric、全部现有 validation 与 GPU probe 消费者同批迁移，旧类和旧 import 归零，没有保留继承式 compatibility 双路径
* aggregate 继续留在 `cc.ecisr.mmm.fluid`：当前 stage 热路径依赖同包的 package-private primitive access；此时强搬 `solver/domain` 会迫使内部数组变成广泛 public。`FluidDomainState` 也暂不搬到 `model/state`，因为它仍组合 `FlipParticleState` 等 fluid-side handoff 类型，先搬会制造 `model → solver` 反向依赖

### S12完成 (`fed05cf`)

* `FluidDomainStateMapper` 集中完整 authoritative capture/restore 的 owner、registry、gas topology、thermal 与 pending-work 装配；aggregate 的两个入口缩为稳定委托
* S11 复审发现 production hydrostatic thermal axis 仍直接修改 live domain：传导成功而后续 reaction/phase 拒答时会留下前序提交。新增 `FluidThermalStepRequest` / `FluidThermalStepCandidate`，`CpuFluidSolverBackend` 只在私有 `FluidDomain` 执行 thermal-only axis，随后与 motion candidate 共用 backend id、紧邻 generation 与布局 adoption 门
* candidate restore 不保存逐步 diagnostics；旧调度器却从 replacement domain 读取重置后的 transport/reaction/phase/transition 统计，并把 pacing 中仅完成 thermal 的 tick 当成 motion 完成。accepted outcome 现携带完整本步统计，thermal activity 只消费该 outcome，motion quiet streak 只在实际 due motion step 后推进
* `FluidSolverBackend` 现在明确同时提供 motion 与 thermal-only 完整候选；开发 air demo 的初始化 motion 也改走 production candidate 边界，不再直接 step live aggregate
* validation 沿既有 `lifecycle` parity 表征增加 thermal-only candidate→restore→capture raw-bit 对照，并验证 backend 求值不修改原 live input；未新增 selector
* S12 完整门通过：`lifecycle`、full `production-replay`、23 项 milestone 与 `verifyArchitecture`；full production replay 的 live 20 步 median/max 为 11.711/18.683 ms，phase-on production step 为 12.754 ms，均低于 40 ms

### S12复审 (`6fa3567`)

* S12 的 adopt “layout 校验”实际只核对网格、marker budget 与 solver config；future backend 仍可把 sealed boundary、carrier kind 或 registry/capability identity 换掉后被接受。新增 immutable `FluidDomainState.Layout`，把这些稳定定义与 thermal transport budget 一起绑定，existing lifecycle adoption 表征替换为明确的 layout-substitution 拒答
* layout 重验使用 aggregate 的 cheap identity view，不为 adopt 再复制完整 marker/carrier state

### S13 (`8b169d0`)

* 新建 `core/.../application`：motion/thermal step、显式 checkpoint 和 controller summary 各有 loader-neutral command、封闭 result 与实际消费的窄 port；没有预建 ownership/container/entity/collision/snapshot 空接口
* `RegionApplicationService` 接管 capture→backend→candidate identity/layout validation→atomic adopt。`FluidRegionRepository` 在发布前按当前 live generation/layout 再验证；accepted result 只返回 step stats 与 moving-body impulse，不把完整 candidate arrays 或 live domain 交回 Fabric
* `/mmm regions checkpoint` 现在冻结 region UUID、expected checkpoint generation 与 container-WAL fact；pending WAL 在触达持久 port 前拒绝，`FluidRegionOperations` 作为 checkpoint adapter 重验 controller/loaded/generation/ownership 后才进入既有 HEAD 发布
* controller summary 的 Minecraft 线程/距离/方块/owner 校验仍在 service；`ControllerSummaryAdapter` 只投影 use-case-specific bounded read model，application 绑定一次性 summary generation，UNLOADED inventory-unavailable 语义不变
* `FluidSimulationService` 删除 summary 装配和直接 solver capture/adopt，降至 3249 行；architecture gate 新增 application 禁止 adapter/payload import 的自检规则
* existing lifecycle 表征改为 application command/result 测试，覆盖 stale generation、backend mismatch、完整 layout substitution、checkpoint WAL 短路与 metadata-only summary；`lifecycle,protocol,region-state` 和 `verifyArchitecture` 通过

---

revision 列由该切片的下一次提交补记，避免提交引用自身哈希。S10 复审期间曾以 tree-filter 重写未推送历史规范化行尾（CRLF→LF，仓库约定 LF、仅 .bat 为 CRLF）；S10a–S10复审 四行的 revision 列由本次提交按重写后哈希校正，S09e 及更早的哈希不受影响。

## 已定边界决策（审阅时按此为准）

* `gridDirty` 保留为显式失效标志，不替换为 revision 门：它混合载体结构变更、几何编辑与每 substep 平流位置三类失效源，结构 revision 无法表达位置失效；revision 门等待输入真正可 revision 化的 workspace。
* cell 尺寸（sizeX/Y/Z/cellCount）留在 domain 作为不可变身份，未迁入 `DomainGeometry`；`geometryRevision` 因尚无消费者而未引入。
* 化学列的整批重建（restore 路径）与 `thermalState.restore` 是列替换而非序数变更，不受 coordinator 管辖。
* S03 的 plan 目前在生产路径内联构造，revision 绑定的真正跨期调用方自 S05 的 transfer planner 开始出现。
* split/merge 的数值计划仍由 `FlipParticleReseeder` 负责；S11 已把 logical-step reseed 提交编排收进 `ParticleReseedStage`，结构变化继续只经 `CarrierMutationCoordinator` 更新 marker 与全部 side column。
* 碰撞批次位移的化学 escaped-sink ledger 仍在位移完成后的恢复/记账点验证；其前置验证属于 `CollisionEditPlan` 批次规划，待碰撞流切片收口。本轮修复的是 drain 与 `setVolume` 路径经 `removeParticlesInCell` 的逐格原子性。
* S09 的 stage 类（pipeline、P2G、body force、G2P、平流、granular 力学、压力投影）不保存 `FlipFluidDomain`；稳定 SoA/workspace 与可能在 lifecycle 中重新绑定的 scratch 分别经构造期 `Workspace` 和各 stage 独立的窄 `Access` 注入。当前 aggregate root 只发起 `MechanicalStageAssembly` 的一次性接线，S12 迁名不会改变 stage 契约。
* `ThermalDomainState` 只组合 carrier owner、audit、LTE grid、transport 与有限设备 coordinator，并对外保留稳定委托面；局部 reaction/phase thermal candidate、pending phase-work、repartition、cell observation 和 display 派生算法由无库存的 `ThermalCellOperations` 承担。S11 的 domain 级 extent 搜索和跨 carrier 提交已进入专职 stage，没有把 thermal owner 的局部算法复制回 pipeline。
* granular clear plan 不绑定 liquid carrier revision：联合 operator clear 先提交 condensed（推进 carrier revision），且 granular 真值与 marker 结构无关；granular 真值由 granular 结构 revision 与 SVQ/质量/U raw bits 绑定。thermal stale identity 是 liquid/gas/granular/audit 四个 owner generation 的 exact `ThermalDomainState.Revision` tuple，不能求和或压成 hash；任何 U 或账本真值变更必推进其中一列。granular clear plan 仍不绑定它——联合 clear 先提交 condensed 会推进 thermal truth，且 U raw bits 已是精确绑定。
* S11 没有在每个 logical step 尾部追加全 marker/solid/gas 的 O(N) 二次审计扫描：各 owner 的 plan/commit 已在真实变更点结算 species、质量、SVQ、U、source/sink 与 operation budget；`CpuFluidSolverBackend` 的私有完整 candidate 是跨 stage 事务边界，任一 stage 失败都不发布 live state。pipeline 只在全部 stage 返回后替换 diagnostics；发布边界继续执行既有完整 capture/restore/audit 校验。
* 提案表中“S11 后 `FlipFluidDomain` 约 1000 行以下”不作为本切片完成条件：S11 已删除全部 logical-step 热化学/相变/transition/reseed 算法，但 facade 仍承载 transfer、lifecycle、collision、capture/restore 与调试构造入口，共 4067 行。为追求行数而把这些非 S11 职责塞进 thermochemistry stage 会制造第二职责；它们按 S12 aggregate/facade 迁移和后续 application 切片处理。
* pressure projection 的 anchor/reference/iterations/residual 写回 domain 字段是有意保留：reference 与 pressure 数组是跨 stage 的权威状态（同一 logical step 内的 phase transfer 与状态输出消费），不属于 S09 点 5 要清除的 stage 间私有 scratch；stage 的全部 gas 读取统一经 `PressureBoundaryView`。
* S12 删除旧 `FlipFluidDomain`，而不是为无仓库消费者的旧名保留 facade。`FluidDomain` 当前仍有 4031 行，组合的 authoritative owner/workspace/pipeline 已独立，但 application、fixture、collision 与一部分 mechanics seam 尚未迁完；其 size allowlist 明确延至 S21，不能把这些 seam 塞回 stage 或用压缩格式假装达标。
* `FlipThermalState` 暂留 414 行 package-private 纯委托：它仍是 fluid-side persistence/reseed 类型到 `ThermalDomainState` 的适配边界。S12 不通过公开 thermal owner mutator 来强删它；待显式 thermal port 取代这些 fluid 类型后再删除。
* S12 没有建立只把 `new FluidDomain` 包一层的伪 test fixture builder。当前 Fabric debug fixture 与 application transfer 仍需 aggregate 的受控构造/变更入口；这些调用按 S13–S16 用例迁移后，S21 才集中 test fixture 并收窄 public surface。
* S13 没有照提案一次性创建 `OwnershipPort`、`ContainerActorPort`、`ContainerJournalRepository`、`CollisionWorldPort`、`EntityMotionPort` 和 `SnapshotSink`：本切片实际迁移的 step 已由 Fabric 在调用前冻结 moving-body facts，checkpoint 与 summary 也各自有窄 port；没有调用者的接口无法从用例倒推出原子性和失败语义。ownership/container ports 分别等待对应用例切片，collision/entity/snapshot 随 S15 tick 编排迁移。
* application step result 不返回 `FluidStepCandidate`/`FluidThermalStepCandidate`。完整 state 只在 backend 与 repository adoption 边界内存在；Fabric 调度真正需要的是 accepted stats 与 moving-body impulse，借 result 暴露 candidate 会把不受限数组重新变成 adapter 的隐式读口。
