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

* `mmm.units.SignedDyadic` 下沉（携带 `MAX_SIGNIFICAND_BITS` 预算，`ThermalLimits` 旧常量删除），`fluid.air→thermal` 边消除，core 包环清零并删除 CYCLE_ALLOWLIST
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
* aggregate 继续留在 `mmm.fluid`：当前 stage 热路径依赖同包的 package-private primitive access；此时强搬 `solver/domain` 会迫使内部数组变成广泛 public。`FluidDomainState` 也暂不搬到 `model/state`，因为它仍组合 `FlipParticleState` 等 fluid-side handoff 类型，先搬会制造 `model → solver` 反向依赖

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

### S14 (`a295212`)

* S13 复审确认 candidate identity/layout/generation 的 application + repository 双重校验仍闭合，checkpoint WAL fact 在 server-thread command→port 同步调用内不存在可插入变更；遗留缺陷是 application 接线仍位于带 I/O 的 service 构造器末端，任一 adapter 的 `get(server)` 都能在非启动语境触发 policy、能力包发布、metadata 恢复和 WAL 扫描
* 新建 `MmmRuntimeRegistry` / `MmmRuntimeFactory` / `MmmRuntime`：`SERVER_STARTING` 是唯一主运行时构造入口，所有 Fabric、命令、item、network 与 gpu-probe 消费者显式 `require/find` runtime；删除 `FluidSimulationService.SERVICES` / `COMMITTED_EVENTS` / `get/find/removeServer`，主运行时静态索引只剩 registry。pre-start event subscription 由 registry slot 保留同一 event hub，不初始化 world state
* `WorldStartupCoordinator` 将 policy、capability、definition comparison 与 frozen registry validation 表达为 typed state/stage/code/severity；`MmmRuntimeFactory` 才把 issue 投影到 logger，并固定 metadata load→removed quarantine recovery→startup lock projection→ownership discovery→container WAL discovery→无 I/O service construction 的顺序。依赖已加载 level 的 takeover/ownership/container actor recovery 保持到 `SERVER_STARTED`
* `RegistryBundleLoader` 接管 datapack definitions 与跨 registry 支持性验证；复审旧顺序发现首次发布先于支持性验证，可能把 codec 可读但求解器不支持的 definitions 永久发布。current definitions 现先验证再参与首次 publication；committed bundle 仍独立复验，当前 definitions drift/不可用不改写既有权威
* composition types 置于 `simulation` 包而未机械照抄提案的 `bootstrap` 子包：factory 需要组装现有 package-private region/persistence adapters；为目录外观把这些构造面全部改成 public 会扩大写面。文件职责与 runtime 入口已独立，待后续 adapter 真正分包时再随窄接口移动
* `FluidSimulationService` 构造器降为纯接线，当前 3050 行；配置、能力启动、registry 提取、region metadata/quarantine、ownership 与 container discovery 全部移出。`region-state,lifecycle,containers,ownership` 启动矩阵与 `verifyArchitecture` 通过，覆盖 READY、unreadable policy 的 proven-but-restricted、definition drift、LOCKED_PROFILE/unknown durable evidence、能力包 recovery 与 removed quarantine convergence

### S15a (`956e94a`)

* S14 复审沿真实 Fabric 注入点确认 `SERVER_STARTING` 虽位于 `setupServer` 之前，但 reloadable resources 已存在且 startup 不读取 level；factory 抛错时服务器启动必然中止，清掉尚无 runtime 可承接的 pre-start event hub 不会丢失可交付事件。typed outer startup state 也确实覆盖“能力获准但 frozen registry 无效”的 fail-closed 情形，因此 S14 无需返工；剩余结构债务仍是 3050 行 service 同时承担 tick/query/lifecycle
* 新建 loader-neutral `RegionRuntime` / `RegionTickUseCase` / `RegionStepExecutor`。Fabric 每 tick 冻结 tick、interval、短期 pacing streak 与正交 readiness；use case 生成唯一 `NONE` / `MOTION` / `THERMAL` plan，motion 到期时优先且同 tick 不再执行 thermal-only，只有选中的 motion plan 能消费 moving-body request
* 40 ms pacing、1–4 tick interval、八个 accepted fast step 后逐级恢复、连续三步严格超过 200 ms 后 auto-pause 的状态迁移移出 `FluidSimulationService`。计时使用可注入单调时钟；只有 application accepted candidate 才推进 interval/streak，rejected result 或 typed solver exception 保持旧 runtime 证据
* `FluidSimulationService#tickRegion` 仍负责 Minecraft collision/entity 输入冻结、实体侧响应、thermal/hydrostatic observation、错误本地化和 snapshot 输出，但不再自行计算 due axis 或 pacing；旧 `FluidRegionLifecycle#consumesMotionInput` 与 service 的五个调度常量/`updateStepPacing` 已删除。service 当前 3054 行，S15 尚未完成，query/lifecycle/catalog/admission 与完整 `MinecraftRegionInputs` adapter 留给后续闭合子切片
* existing `lifecycle` 调度表征被替换为 use-case 级测试：覆盖 paused/skipped/thermal-only/motion-over-thermal、单次 step executor 调用、40/200 ms 边界、连续过载与八步恢复；真实 `RegionApplicationService` candidate adoption 另经确定性 50 ms clock 验证 accepted outcome→interval=2。`lifecycle` 与 `verifyArchitecture` 通过

### S15b (`dd43746`)

* S15a 复审确认 tick plan/application adoption/pacing 的单轴顺序已闭合，但 `RegionApplicationService` 仍混入 controller summary，service 又在 bounded port read 前直接签发 token；port miss 或投影异常会留下客户端从未收到的有效授权 generation
* 新建 loader-neutral `ControllerQueryUseCase`，独占 bounded summary projection 与 `ControllerConsoleSessions` 的 issue/consume/forget。`ControllerSummaryCommand` 现在携带已由 Minecraft adapter 授权的 viewer/region identity；port view 先验证 region identity、dimension/profile 与正 generation，并以完整 `ControllerSummary` 构造验证投影，全部成功后才签发最终 token
* `RegionApplicationService` 删除 controller port/summary 职责并缩至 step + checkpoint；`FluidSimulationService` 仍负责 server-thread、距离、方块和 controller owner 校验，summary、普通动作与破坏性 preview 统一委托 query use case 的同一 token owner。service 当前 3053 行
* existing lifecycle metadata-only summary 表征改走 `ControllerQueryUseCase`；existing protocol token 表征改为 use-case 级 stale/replay、player/region isolation，并新增同一测试内的 port miss/非法 view 不推进未披露 generation。`lifecycle,protocol` 与 `verifyArchitecture` 通过

### S15c (`4a62a70`)

* S15b 复审确认 controller query 已形成独立读/授权边界；破坏性 region lifecycle 的四种提交仍跨 candidate HEAD、ownership 与 REMOVED quarantine，当前直接统一会退化成通用回调包装。先迁同样有独立身份且无 region commit 权的 fixed thermal reservoir 交互
* 新建 loader-neutral `ThermalReservoirUseCase`，独占 bounded summary、player+dimension+block token、动作授权顺序和 registry 闭区间 setpoint 判定。summary 先完整验证当前 setpoint/公共温区再签发；动作保持旧顺序：距离由 Fabric 先验，随后消费 token，再判 creative、authoritative writes、当前方块/BlockEntity 与 registry availability；这些拒答后的 token 均不可重放
* `FluidSimulationService` 不再直接持有 `WorldPositionActionSessions` 或构造 reservoir summary；只把 Minecraft facts 投影给 use case、把 typed decision 本地化，AUTHORIZED 后写 world-owned BlockEntity 并刷新相邻已加载 thermal boundary。use case 不取得 region `U` 或 world 写权；service 当前 3065 行
* existing protocol thermal-reservoir token 表征替换为 use-case 级 stale/replay、denied-consumes-token 与温区闭端点/NaN 拒答；未新增 selector。`protocol` 与 `verifyArchitecture` 通过

### S15d (`3cba502`)

* S15c 复审确认 reservoir 的 token→typed policy→world owner 写入顺序闭合；region destructive lifecycle 则自然分成 loader-neutral 授权与三类 Fabric durable transaction，先迁不会取得 HEAD/ownership/quarantine 写权的授权半边
* 新建 loader-neutral `RegionLifecycleUseCase`：controller pause/resume 按 permission→authoritative writes→operator-vented resume gate→idempotence 返回封闭 decision；preview 统一固定 200 server ticks，use case 独占 `LifecycleOperationSessions` 的 issue/consume/forget，并在确认时返回 `AUTHORIZED` / `TOKEN_INVALID` / 当前 `BLOCKED` 列表
* Fabric 继续冻结 permission、WAL、availability、solver generation、finite-device identity、ownership count、quarantine capacity 与 bounded inventory quantities；错 viewer 不消费 owner nonce，同 owner 的 generation/action/permission/WAL/availability/expiry mismatch 会消费并失效，context 匹配后仍以确认时 blockers 为准。只有 AUTHORIZED 才进入既有 inventory candidate→HEAD、ownership release 或 REMOVED tombstone durable adapter
* `FluidSimulationService` 删除直接 lifecycle session 所有权与 run-intent policy 分支，但 durable commit/facts adapter 尚未提取，当前 3072 行；S15 lifecycle 尚未完成。existing lifecycle 表征改走 use case 并保留全部 commit gate matrix，追加 current blocker after valid token 与 run-intent typed decisions；`lifecycle` 与 `verifyArchitecture` 通过

### S15e (`8c92644`)

* S15d 复审确认 authorization 已闭合，但继续增加只返回 decision 的薄类会让 service 继续变长；转向机械搬走 `FluidRegionManager` 的实际规则。耦合审计证明 duplicate/budget/overlap/selection/replacement 只需 UUID、dimension stable ID、AABB 与 reserved cells/markers，不需要 Minecraft、solver 或 repository
* 新建 loader-neutral 泛型 `RegionCatalog<T>`，以 opaque runtime owner + immutable descriptor 独占有序登记、首项默认选择、显式选择/删除 fallback、预算统计与预检、face-touching AABB、atomic selected replacement、committed metadata 无条件登记和按 owner identity/descriptor 的 runtime re-admission
* `FluidRegionManager` 从 244 行降为 107 行，只把 `FluidRegion` 投影成 descriptor并映射现有配置/result DTO；旧 `FluidRegion#spatiallyOverlaps` 删除，避免 Fabric 与 core 两套 placement 规则。复审迁移时特意保留旧 `int * float` 上界再提升 double 的舍入顺序，未借 descriptor 改变边界几何
* existing `region-manager` 表征原样覆盖新 catalog 经 adapter 的 admission/selection/replacement/overlap/committed-metadata 规则；未新增测试或 selector。`region-manager` 与 `verifyArchitecture` 通过

### S15f (`a3fb2fe`)

* S15e 复审沿 `ensureAvailable` 的真实副作用顺序确认 catalog 规则虽已独立，runtime admission 仍把四类内在失败、ownership/policy、catalog、边界分类与 unload 副作用混在一个方法内；其中数值/ownership 必须保留已加载 solver，而 policy/catalog/boundary 必须先 checkpoint 再释放，不能合并成单一“拒绝”分支
* 新建 loader-neutral `RegionAdmissionService`，以分阶段 facts 固定 chemistry→granular→thermal→numerical、unmanaged bypass、ownership→policy、catalog、boundary 的顺序，并返回 `AVAILABLE` / `PROCEED` / `BLOCK_IN_PLACE` / `UNLOAD`。reason 与 detail 的组合由 record 构造器 fail closed，application 不接触 Minecraft、repository 或 solver
* `FluidRegionOperations` 改为惰性冻结每一阶段 fact，只有前一阶段通过才读取 ownership、计算 catalog admission 或扫描边界，保持原有观察与成本顺序；adapter 仍独占中文 ownership 诊断、checkpoint-and-unload、restore、collision 登记和 snapshot publication。没有用通用 callback 把这些写权倒灌给 application
* existing `region-manager` 表征内替换同一 admission 不变量测试，覆盖内在失败优先级、unmanaged bypass、ownership 胜过 disabled policy、就地阻断与 checkpointed unload 分类，以及 catalog/boundary 封闭 reason；未新增 selector。`region-manager,region-state` 与 `verifyArchitecture` 通过

### S15 收缩复审一 (`976090b`)

* 对 `BeforeReArch` 到 S15f 做生产代码审计：Java 生产源由 57,389 行/285 文件增至 63,150 行/369 文件，净增 5,761 行（10.0%）和 84 个文件；S15a–S15f 自身净增 640 行。确认当前已进入“抽取速度大于删除速度”的迁移层堆积，暂停进入 S16，S15 后续改以职责消失、入口减少和生产代码净下降为切片证据
* 横向检查 S13–S15 类型后，tick use case 拥有 pacing/clock 状态机，query/reservoir/lifecycle 拥有 token 与授权规则，catalog 拥有索引/预算/选择状态；`RegionAdmissionService` 则无状态、无完整工作流、无第二个生产调用者，只把 `ensureAvailable` 已有顺序包装成四组 fact/decision 往返，未转移实际职责
* 删除该 ceremonial application layer 和只验证其 DTO 映射的新增测试；`FluidRegionOperations` 恢复单一、可顺读的 admission 工作流，仍按 chemistry→granular→thermal→numerical→unmanaged→ownership→policy→catalog→boundary 惰性取证，typed reason、就地阻断与 checkpoint-before-unload 语义不变
* 本切片删除 1 个生产类型，按 git 物理行差量生产 Java 净减 108 行、测试净减 41 行；不是合并语句、删除注释或迁移到别处。`region-manager,region-state` 与 `verifyArchitecture` 通过

### S15 全面收缩审计 (`53b2345`)

* 对 S00–S15 新增生产类型做一次性全量分类，而不是继续逐类型停轮：逐项区分真实状态/生命周期 owner、数值算法/stage、事务与外部边界、以及纯转发/镜像类型。`RegionRuntime`、runtime/startup composition、candidate/repository、token/session、各 carrier owner 与 stage 均持有不可替代的不变量；`FlipThermalState` 仍承担 fluid persistence/reseed 类型到 thermal owner 的适配，强删会扩大 thermal 写面，留待 S21 的显式 thermal port 收口。本轮不为减少文件数破坏这些边界。
* 复核 S15→S16 接续：container 主线需要的 `MmmRuntime` 组装入口、application command/result/port、`RegionRepository`、`RegionCatalog`、solver candidate 与 durable transaction owner 均未改动。上一切片删除的无状态 admission 包装也不是 S16 前置；runtime admission 继续直接消费 catalog typed result，并保持 checkpoint-before-unload 顺序。
* 删除只有一个消费者的独立 `RegionStepExecutor` 文件，把同一窄测试/执行缝收回 `RegionTickUseCase.StepExecutor`；`RegionApplicationService` 直接实现消费者拥有的接口。调度状态机、可注入时钟与 candidate adoption 不变。
* `FluidRegionManager` 继续保留必要的 Minecraft `FluidRegion`→loader-neutral descriptor 映射，但删除对 `RegionCatalog.Limits`、`BudgetUsage`、`AdmissionResult` 的三套镜像 record、转换器和重复校验；world config、service、persistence 与既有测试直接消费 catalog 的规范类型。没有把 descriptor 映射扩散给调用方，也没有删除 catalog 边界。
* 本提交删除 1 个生产文件，生产 Java 净减 36 行；连同前一收缩提交，S15f 峰值 63,150 行/369 文件降为 63,006 行/367 文件，净减 144 行和 2 个文件。相对重构前仍净增 5,617 行（9.8%）和 82 个生产文件；这是真实 owner/stage/application 边界的当前成本，不再用本轮高风险合并掩盖。`region-manager,lifecycle,region-state` 与 `verifyArchitecture` 通过；下一轮停止收缩复审，回到未完成的 S15 主线，再顺接 S16。

### S15g (`e68950c`)

* 上一轮收缩复审沿 S15→S16 真实接缝再确认：收回 `StepExecutor` 和 catalog 规范 DTO 只改变类型归属，没有改变 step executor 数量、catalog 状态或 durable transaction owner，S16 所需 command/port/repository/runtime 入口仍完整。本切片因此回到 S15 尚未闭合的 world-input 编排，而不继续横向删层。
* 将既有 `MinecraftEntityFluidInteraction` 演进并迁名为 `MinecraftRegionInputs`，由同一 server-thread adapter 独占五 tick 碰撞刷新、thermal boundary 刷新、实体发现/观测/响应、moving-body request 以及唤醒后的 `RegionRuntime` 冻结。`FluidSimulationService` 保留线程/身份/availability、command 构造、typed failure、本步结果、thermal/hydrostatic post-observation 与 snapshot，不再自行拼接这些输入。
* 删除仅服务该 adapter 的独立 `EntityMotionTracker`，把相邻服务器 tick 的 actual-displacement history 收为私有嵌套状态；同时删除 service 中无调用者的 `stepThermalOnce`。没有新增通用 collision/entity/snapshot port，也没有复制 capture DTO：生产 Java 净减 5 行，service 由 3073 行降至 3039 行，生产源由 367 个降至 366 个。
* 差异复审发现碰撞 changed-cells 不能只唤醒 motion；迁移后仍同时以 `BOUNDARY_CHANGE` 唤醒 motion 与 thermal。thermal refresh 的 typed 失败本地化、先收集全部实体观测再响应、每 game tick 更新 motion history、request 顺序/预算以及只有 motion plan 消费 request 均保持原语义。
* existing `EntityFluidWiringValidation` 与 `ProductionFluidReplayValidation` 只替换同一 owner/类型表征，未新增测试。`lifecycle,entity-fluid` 通过，最终实体步时门最大值 30.307 ms；`verifyArchitecture` 通过（366 个生产源）。无需进游戏：本切片不涉及视觉、真实 `VoxelShape` 差异或跨重启事务。S15 仍未完成，下一切片从 durable lifecycle adapter 边界继续，完成后再进入 S16。

### S15完成 (`5345f0b`)

* S15g 复审逐 tick 检查 availability、boundary refresh、实体 history 与 axis selection 的先后关系，确认 availability 失败不会推进实体位移历史，collision changed 仍同时唤醒 motion/thermal，motion request 仍只被唯一到期 motion plan 消费。真正未闭合的是 service 继续拥有完整 command/result/failure/post-observation，以及 lifecycle facts 与三种 durable protocol。
* 新建 `MinecraftRegionTick`，以既有 `MinecraftRegionInputs` 与 loader-neutral `RegionTickUseCase` 为上下游，完整接管单个已可用 region 的 tick：冻结 command identity/phase budget、执行唯一轴、只对 accepted result 写回 pacing/统计/实体冲量、观察 thermal/hydrostatic、映射 typed failure 并按原节拍发布 snapshot。`FluidSimulationService#tick` 只保留全局 transfer/takeover maintenance、dimension failure 与 availability，随后委托；手动 step/collision/restore 入口也复用同一 failure/input seam，不保留第二份实现。
* 新建 `MinecraftRegionLifecycle`，完整接管 controller re-resolution、run intent、preview facts、nonce authorization，以及 inventory candidate→staged HEAD→durable HEAD classification→live adoption、ownership release、REMOVED HEAD→network tombstone→runtime unregister→quarantine convergence。三类事务仍分别调用现有窄 operation，不建立通用 callback port；HEAD 已提交后的 live/snapshot/quarantine 失败继续返回 committed-with-recovery，而不假装回滚。
* `FluidSimulationService` 由 3039 行降至 2412 行，删除 651 行 tick/lifecycle 实现和对应常量/import，只增加 24 行接线/委托；两个真实 adapter 共 768 行，因此本切片生产 Java 净增 141 行、生产源增 2 个。该净增是明确事务与世界输出边界的构造成本，不再通过把它们塞回 god service 或压缩语句伪造净下降。
* S15 提案中的字段分类不机械拆 record：`FluidRegionLifecycle` 已拥有 availability/operator intent 状态机，`PersistedRuntime` 已冻结 checkpoint runtime；identity/checkpoint source 的进一步模型化会触碰 S17。fixture/demo/takeover 也不为一个调用者预建 `DebugFixtureUseCase`；命令路由在 S19、aggregate fixture surface 在 S21 处理。runtime admission 的 ceremonial wrapper 仍保持删除。
* S15 六个跨层门通过：`lifecycle`、`entity-fluid`、`production-replay`、`protocol`、`region-state`、`ownership`；`verifyArchitecture` 通过（368 个生产源）。首轮 `entity-fluid` 只有 walking-body wall-clock 47.697 ms 超限，其库存/冲量/衰减断言均通过；相同未经过新 adapter 的 selector 隔离复跑为 27.268 ms，判定为计时离群。production live 20 步 median/max 为 13.285/20.790 ms，phase-on 为 15.161 ms。无需进游戏：没有视觉、真实 `VoxelShape` 差异或自动门不可观察的跨重启场景。S15 至此闭合，下一轮进入 S16。

### S16完成 (`d80173e`)

* 先沿 S15→S16 接缝复核 tick、runtime composition、candidate/HEAD 与 container 现状：S15 的 tick/lifecycle 拆分没有复制 container commit point，也没有改变 S16 所需 region checkpoint seam；真正遗留的是 recovery index、runtime-dirty、actor convergence 仍由 `FluidSimulationService` 持有，`FluidTransferService` 又同时负责 Minecraft 输入输出、内容规划和 exact solver primitive。因而没有返工 S15，而是以这两个真实混合职责为迁移边界。
* 新建 loader-neutral `ContainerTransferUseCase` / `ContainerTransferCoordinator` / `ContainerJournalRepository`：command 只携带 stable region/dimension/generation、actor、before/source fingerprint、typed resource/amount 与 profile/thermal identity；use case 在 `PREPARED` 前拒绝 foreign profile、错误 thermal/registry truth，coordinator 独占 `PREPARED WAL → exact solver mutation → staged checkpoint → REGION_COMMITTED WAL → HEAD`。成功 result 返回 exact actual settlement、coupled energy、committed generation 与 transaction identity；无转移但 WAL 删除失败也保留 transaction/index，不再把 durable cleanup 失败伪装成普通拒答。
* player 与 dispenser 现在共同经过唯一 `MinecraftContainerTransferPlan`，由它统一 capability、contents、typed amount 与 exact primitive 选择；`PlayerContainerTransferAdapter` 只处理准星/手、文案、声音和玩家输出，`DispenserContainerTransferAdapter` 只处理朝向/slot/方块实体输出。mixin 与 dispenser registry 已直连二者，旧 1121 行 `FluidTransferService`、旧 `ControllerItemTransferCoordinator` 和恢复 helper 全部删除；pure/material/chemical/granular/thermal 的 `ItemStack`↔fingerprint 映射收进 `ContainerItemMapper`。
* `ContainerRecoveryCoordinator` 接管 boot index、store issue、solver-dirty、一次审计、THIRD lock 与 unavailable actor 集；启动期只由 `MmmRuntimeFactory` discover/read 一次，normal tick 只处理尚未审计的内存索引并明确跳过 unavailable actor。玩家只在该 UUID 协议加入事件、dispenser 只在所属 dimension/chunk load 事件重试；actor after 应用后强制保存、exact-after 回读、adopt live checkpoint、删除 WAL/index，最后才发布 committed event。成功响应丢失仍由同一索引幂等收敛。
* 原 768 行 `ContainerTransferStore` 拆为严格、无 I/O 解释职责的 `ContainerJournalCodec`（598 行）与仅做 discover/read/write/delete、回读、atomic replace 和目录 durability 的 `ContainerJournalFileStore`（160 行）。`FluidSimulationService` 删除 container 状态机和 actor/persistence 写权，由 2412 行降至 2099 行；旧 store/recovery/transfer allowlist 与路由入口同步删除。
* existing `containers` selector 内加入 application 故障矩阵，覆盖无故障路径与六个 fault boundary、foreign profile 在 WAL 前拒绝、success response loss、WAL cleanup failure、actor unload 与精确匹配 availability event；原 persistence/exact-settlement 表征继续复用，没有新增 selector。最终 `materials,chemistry,granular,thermal,containers,protocol` carrier 集、修正后的 `containers` 最近门及 `verifyArchitecture` 全部通过，架构门扫描 376 个生产源且 core/application 的 Minecraft 类型搜索为空。
* 本阶段生产 Java 按 git 物理差量为 +2402/-2013，净增 389 行，生产源 368→376；增长来自显式 application/recovery/actor 边界与故障测试所需端口，而不是保留旧 facade：同轮删除 1450 行的旧 transfer service/coordinator/recovery，最大新生产文件为 598 行，未新增 size allowlist。无需进游戏：本轮不涉及视觉、真实 `VoxelShape`，跨重启事务边界已由 fault/recovery 状态机自动表征。

### S17完成 (`bdf4dc0`)

* 先把 S16 当作未受信任的事务迁移逐边界复核，而不是只重跑测试：对照旧 coordinator 检查 `PREPARED WAL → exact solver mutation → staged generation → committed WAL → HEAD → actor after/save/readback → WAL delete/event`，并逐项检查 committed-WAL 写失败、HEAD 响应丢失、actor unavailable、player UUID 与 dispenser dimension/chunk 精确重试。player/dispenser 都只经唯一 `MinecraftContainerTransferPlan` 调实际 primitive，boot discovery 仍只在 runtime factory，normal tick 不轮询 unavailable actor；未发现需要抢修的 S16 缺口。S16 新增的“零转移但 WAL 清理失败仍保留 recovery identity”是 fail-closed 修正，不是语义回退。
* 将 checkpoint 逻辑 DTO 迁入 `model.region`：`RegionCheckpoint` 组合 manifest/sections，`RegionIdentity`、`RegionGeometry`、`RegionRuntimeIntent` 分别持有 stable identity、solver/controller geometry 与持久运行意图。`BlockPos`/lifecycle/runtime class 不再进入 manifest；既有 block/chunk packed 编码被显式保留并复现旧 footprint 完整 chunk 覆盖校验，因此 NBT schema、CRC 字段顺序与 HEAD identity 没有变化。
* 新建 loader-neutral `RegionCheckpointSource` 与唯一 live adapter `MinecraftRegionCheckpointSource`。assembler 按 pure/material/chemical/granular/thermal 明确选择 carrier、gas、granular、thermal、finite-device 与 pending-phase contributors，仍沿用既有 codec/ledger 规则；capture public surface 不再接受 `FluidRegion`。`FluidRegionPersistence` 只在 adapter 侧完成 Minecraft metadata 映射。
* 原 1480 行 `RegionState` 的 capture、reconstruct、validation 三个职责分别进入 `RegionCheckpointAssembler`（598 行）、`RegionCheckpointRestorer`（526 行）和 `RegionCheckpointValidator`（558 行）；`RegionState` 降为 27 行 durable-store DTO adapter。restorer 返回现有 `Flip*State`、gas/granular/pending/device immutable truth，并由同一 checkpoint 提供 metadata；没有再造一套与 `FluidDomainState` 平行的 aggregate DTO。单个 restorer 实例只执行一次完整 validator，thermal restore 不再重复多轮全 checkpoint 审计。
* `simulation.persistence` 对 `FluidRegion`、`FluidRegionLifecycle`、`FluidRegionFootprint` 的 import 归零，model/source/assembler/restorer/validator 对 Minecraft/NBT import 归零；architecture gate 的 S17 runtime-import 与 `RegionState` size allowlist 已删除。原 region-state 测试只替换同一 capture/reconstruct 入口，并继续逐 layout 比较 manifest、section、extension、ledger、checksum 与 reconstructed state，没有新增 selector。
* `region-state` 全门通过（含 current pure/material/chemical/granular/thermal、双槽故障矩阵和 capability bundle）；去掉 S17 例外后的 `verifyArchitecture`/直接 architecture gate 通过，扫描 385 个生产源且新类型没有 size warning。代码提交生产 Java +2293/-1769，净增 524 行，测试净增 45 行，生产源 376→385；新增量是 stable metadata/source 与三个被独立消费的职责边界，旧 1480 行实现已删除而非并存。S18 的 slot/HEAD 文件协议完全未动。无需进游戏：没有视觉、真实 `VoxelShape` 或自动门不可观察的跨重启场景。

### S18完成 (`99d96da`)

* 先沿 S17→S18 接缝把 checkpoint model、packed block/chunk 编码、dense section 覆盖、validator 与 restore cache 当作未受信任实现复核，并逐项追踪 region HEAD、ACTIVE/REMOVED slot、quarantine、ownership WAL 与 startup discovery 调用图。S17 的 stable metadata 映射、CRC 输入、跨 section identity/ledger 和“校验成功后才缓存”均保持闭合，没有为 S18 返工模型或 codec。
* 删除 1058 行 `RegionStateFiles`，由唯一 public `RegionGenerationStore` 协调 package-private `RegionHeadStore`、`ActiveGenerationStore`、`RemovedGenerationStore`、`RemovedQuarantineStore` 和 `RegionDiscovery`。只有 coordinator 能发布 HEAD；子 store 分别拥有严格 codec/file access、ACTIVE 回读重建、REMOVED tombstone、quarantine 分类/收敛与启动发现，不向业务层暴露第二个 writer。全部新生产类低于 600 行，S18 size allowlist 已删除。
* 删除混合职责名 `FluidRegionPersistence`：`RegionRepositoryAdapter` 只负责 checkpoint assembly/generation repository、metadata、当前 profile/registry restore 与必须的 ownership read guard；`OwnershipTransactionCoordinator` 负责 Minecraft footprint/preimage、acquire/release/reconcile 与 live generation 写回，并继续委托既有低层 `OwnershipCoordinator` 执行 WAL 恢复状态机。没有照提案预建无 loader-neutral 调用者的 ownership application port，也没有复制 `CrossDomainRecovery`。
* 深审删除路径时发现旧实现会直接清空 inactive/temp 与 `HEAD.nbt.tmp`，无法区分 writer staging 和未知证据。现在所有递归删除先验证规范化/real path 位于 region root 内且不跟随符号链接；ACTIVE、REMOVED、rollback、首发 staging 与 `recoverprevious` 都必须按当前 HEAD、identity、generation、checksum 和严格文件布局证明目标，失败则保留证据并 LOCKED。既有 persistence 拒绝场景改为同时覆盖普通 publish、REMOVED prepare 和 previous-generation HEAD staging，确认旧 HEAD 始终权威。
* quarantine 容量只在 REMOVED commit 前准入；HEAD 发布后的收敛签名删除无效预算参数，明确已提交事务必须幂等走到 exact AFTER，不能因重启后的预算变化被再次拒绝。HEAD/slot 的 force、回读、atomic rename、directory sync 与 fault point 顺序逐项保持；ownership/container 的 generation 与 checksum 边界未改。
* `region-state,ownership,containers` 三门与 `verifyArchitecture` 通过，architecture 扫描 391 个生产源。代码提交生产 Java +1829/-1336，净增 493 行；测试 +105/-63，净增 42 行；生产源 385→391。新增量主要是持久状态分类、可证明删除和真实 repository/ownership 边界，旧两个大类均已删除而非并存 façade。无需进游戏：没有视觉、真实 `VoxelShape`，跨重启事务和文件树决策已由现有故障/恢复矩阵自动观察。

### S19a

* 先沿 S17/S18 接缝把持久层当未受信任实现复核，而不是重跑测试：`prepare`/`commit` 与已删除的 `RegionStateFiles` 逐项对照，确认首次发布路径、slot 选择、`alreadyCommitted` 短路、generation 单调性检查、`readPrevious`/`activatePrevious` 与 quarantine 分类语义均未漂移；`prepare` 中旧实现的无条件 `deleteTree(temporary/inactive)` 已由 `clearInactiveForPublication` 的“先验证 writer 归属再删”取代，`current == null` 分支目录必为空，两条路径等价。`ActiveGenerationStore.readGeneration` 对 REMOVED HEAD 的拒答保证 `prepare` 不会复活已移除区域。S18 无需返工。
* 复核还发现两处与本切片无关的既有问题：`RegionGenerationStore.readManifest` 只有测试消费者；`MmmCommands` 的 `thermalSourceStatus`/`setThermalSource`/`aimedThermalReservoir` 自 `a756645` 起已从命令树摘除（`playable-lifecycle-plan.md` 明确“不要用 `/mmm thermalsource` 作为正式路径”），成为绕过 `ThermalReservoirUseCase` 的死代码。前者保留（测试仍按原入口逐 layout 比对 manifest），后者连同失效 import 一并删除；`README.md` 的可调恒温边界条目仍描述这个已不存在的命令，本次一并按实际正式路径（放置后用 field probe 右击打开服务端签发界面）更正。
* `FluidSimulationService` 余下的最大非编排职责是 `/mmm` 开发测试域与接管事务。新建 `MinecraftTakeoverAdapter` 独占接管事务的三项状态（被移除水方块的 preimage、所属维度、维护开关）与 `lastFailure`，`begin`/`restore`/`maintainActive`/`lockDescription` 全部随迁；它没有任何协作对象，是本次唯一零参数构造的新 owner。
* 新建 `MinecraftDebugFixtures` 独占 gas 演示玻璃脚手架状态与 dam break、分层材料、酸碱、表面张力、密闭气压、原版水捕获六类场景的构造与场景数学；四个场景共用的“清脚手架→结束接管→拒绝覆盖持久控制器区域→准入瞬态区域”前奏收敛为一个 `admitTransientRegion`，`lastFailure` 也归它所有。需要回到活动 runtime 的三件事（碰撞刷新/可用性重检、单步同步 motion、控制器激活）与一项世界能力绑定收进由 fixtures 自己声明的 `MinecraftDebugFixtures.Runtime` 窄接口，service 只提供实现，不把 `stepMotionOnce` 或 `activateController` 公开。
* `RestorationStats` 与 `CaptureResult` 由 service 的嵌套 record 升为 `simulation` 顶层 record：`MinecraftTakeoverAdapter`、fixtures、`MmmCommands` 三方共用，继续嵌套会让搬出的类反向引用 god service。`bindDefaultMaterialState` 下沉为 `FluidRegionFactory` 的纯函数（只依赖 domain 与 `BuiltinFluidMaterials`），controller 激活与 fixtures 共用一条实现；`bindConfiguredState` 留在 service 作为私有方法，fixtures 经 `Runtime` 调用，不复制注册表绑定规则。
* `FluidSimulationService` 由 2099 行降至 1516 行，删除 15 个失效 import；`MmmCommands` 由 627 降至 594。`verifyArchitecture`、`lifecycle`、`region-state` 通过（架构门扫描 395 个生产源，两个新类均低于 600 行 warning 线）。无需进游戏：本切片只做职责搬移，不涉及视觉、真实 `VoxelShape` 或跨重启事务。

### S19a 已定边界决策

* `CELLS_PER_BLOCK` / `CELL_VOLUMES_PER_BLOCK` 仍留在 `FluidSimulationService`：`MinecraftRegionInputs`、`MinecraftContainerTransferOperations` 与 fixtures 三方共用同一方块↔子格约定，把它搬进 `FluidRegionFactory` 会立刻与后者的 `cellsPerBlock` 形参自相矛盾；真正归属应随“区域分配工作流”切片决定，不在本次提交里顺手改名。
* 场景失败文案的归属随之明确：接管事务的失败写在 `MinecraftTakeoverAdapter`，场景失败写在 `MinecraftDebugFixtures`。service 的 `lastTakeoverFailure()` 改为返回 fixtures 的 lastFailure，而 fixtures 在每条被 takeover 阻断的路径上都已把 `RestorationStats.failure()` 记进自己的 lastFailure，因此 `/mmm` 五个 demo/capture 子命令的用户可见文案逐字不变。

### S19b

* 沿 S19a 的成员消费面逐项审计 `FluidSimulationService`：`/mmm chemstatus` 与 `/mmm granstatus` 自 `a756645` 的静态 locator 迁移起被整批摘除后没有重新接线，但 `chemistryInventoryStatus`/`granularInventoryStatus` 的实现与 `README.md` 的文档都还在——这是迁移副作用造成的真实回归，不是有意裁撤。两个管理员命令按当前 `MmmRuntimeRegistry` 入口原样恢复（admin 权限、`succeed` 返回值沿用旧语义），service 侧实现零改动；`selectedRegionIn` 因此重新有消费者。
* 删除两处真正的死表面：`regionAtBlock`（无任何调用者，`targets.regionAtBlock` 才是唯一实现）与 `cleanupContainerTransfers`（与 `recoverContainerTransfers` 逐字相同的别名，`tick` 改用后者）。
* `verifyArchitecture`（395 个生产源）与 `containers`、`audit` 通过；无需进游戏：命令树与死成员清理不涉及视觉、真实 `VoxelShape` 或跨重启事务。

### S19g

* 先把 S19b 当作未受信任的回归修复复核：恢复后的 `chemstatus`/`granstatus` 与删除前版本逐项保持 admin permission、`audit(...)` 包装、消息正文和 `inventory.startsWith("region ")` 返回值规则；被删的 `regionAtBlock` 确无调用者，`cleanupContainerTransfers` 也只是 `recoverContainerTransfers` 的逐字别名。S19b 无需返工。
* 接手时工作树已有未跟踪的 `ClientRegionSnapshotStore` 与 `FluidSurfaceGeometry` 草稿，但前者引用未声明的 `latestThermal`/`stagedBuffer`，后者把 fluid/material/granular/quad 四个职责重新塞进 521 行单类且依赖尚未迁出的嵌套 frame，不能编译，也不能作为已完成切片接线。本轮没有照草稿保留第二个 god geometry，而是沿真实 owner 边界收口整个客户端 renderer。
* `ClientRegionSnapshotStore` 现在独占五类 payload ledger、各 region 最新 accepted frame、generation/sequence/tombstone 决策、200 ms 插值和按 dimension 发布的 immutable frame lists；`ChemicalFrame` 没有删除，因为 material surface 的 `chemicalArgb` 确实经 `MaterialFrame` 消费它。accept/clear 的同步边界和 thermal/material/granular 联动清理顺序保持原样。
* fluid marching-tetrahedra/solid/gas/bounds、material/chemical surface、granular deposit/parcel 与共享 quad emission 分别进入 `FluidSurfaceGeometry`、`MaterialSurfaceGeometry`、`GranularSurfaceGeometry`、`QuadGeometry`；`FluidRenderBackend` 独占唯一 `StagedVertexBuffer`、pipeline/upload/execute/close。`ClientFluidVolumeRenderer` 只保留 store accept/extract、frame→builder→backend 编排和组合关闭，由 833 行降为 69 行，没有 ledger 或 GPU resource 双重 owner。
* 结构对照除编译外还比较了旧 renderer 与七个新 owner 的数值 literal、字符串 literal 多重集，均完全一致；颜色、`SURFACE_THRESHOLD`、`ISO_LEVEL`、`MARKER_INTERIOR_LEVEL`、`SNAPSHOT_BLEND_NANOS`、vertex 顺序与 `DEBUG_FILLED_BOX` pipeline 未改。`compileClientJava`、`protocol` 与 `verifyArchitecture` 一次 Gradle 运行全部通过，架构门扫描 401 个生产源；本切片 client 生产源 8→14、行数 1383→1590，新增 207 行来自唯一 owner 边界与可读展开，最大新类 311 行。无需进游戏：这是逐方法结构搬移，自动编译/协议门和 literal parity 未发现视觉输入差异。

### S19c

* 先复审 S19g 的帧身份与资源所有权：五类 snapshot ledger 仍在同一锁内完成 accept/extract，material frame 继续携带同 sequence 的 chemical frame，renderer 只有一个 backend/staged buffer 关闭路径；没有发现并发、插值或 GPU 生命周期缺口，故不返工已闭合的客户端拆分。
* 新建 `MinecraftControllerConsole`，完整接管 field probe carrier/condensed-cell 解析、controller summary/action、lifecycle preview/confirm、viewer session 清理和 thermal reservoir summary/setpoint。它直接持有 target resolver、query/lifecycle/use case、region tick、registry/startup/manager；仅把现有 region availability 工作流作为窄函数缝注入，没有以 service callback 重新制造隐藏 facade。
* 对照迁移前实现逐守卫确认顺序未变：controller 先重验 server thread、距离、方块和 owner identity，再消费 action token；thermal token 仍先于权限、startup、目标/registry 检查消费，写入仍只更新 BlockEntity，随后刷新相邻已加载 thermal boundary 并按 `BOUNDARY_CHANGE` 唤醒。field probe 仍只观察唯一已加载同代 region，不加载、不唤醒、不 checkpoint。
* `FieldProbeItem` 与协议交互 handler 已改走 runtime 的语义化 `controllerConsole()`；service 删除 190 行交互实现而不保留委托镜像。`protocol,lifecycle` 与 `verifyArchitecture` 通过，架构门扫描 402 个生产源。无需进游戏：本切片没有视觉、真实 `VoxelShape` 或自动门不可观察的跨重启事务。

### S19d

* 先复审 S19c 的组合方向和授权语义：新 console 的实际依赖与守卫顺序闭合，但 `MmmRuntime` 从 service 内部反取 console 是过渡接线。现在 factory 显式把 console 与其他语义 owner 交给 runtime；adapter 只读 runtime accessor，不再把 service 当 locator 后的万能对象。
* 新建 runtime-owned `ProtocolSessions`，把 negotiated layout/capability 校验、player session accept/remove/clear 从 `FluidProtocolServer` 的静态 `Map<MinecraftServer,...>` 移入单服务器 runtime；configuration listener 的短期 `PENDING` 仍只绑定握手 task。shutdown 由 runtime 自己清空 sessions，协议门原有 negotiation matrix 替换同一不变量并增加 accept/remove/rejected-session 表征。
* 新建窄 `MinecraftRegionObservation`，只暴露主域是否存在、向玩家发送全部域、向 level 重发选中域；network join 与 GPU probe 改用它，container retry 直达 runtime-owned recovery，握手读取 committed world profile，Fabric API 直取 runtime-owned Query。network、API、GPU 对 `FluidSimulationService` import 归零，service 的无调用 Query/snapshot convenience surface 同步删除。
* 架构门删除 `FluidProtocolServer` locator allowlist，并新增带自检 fixture 的外部 adapter→god service import 禁令；indicator/controller block 与命令的既有依赖分别明确登记到 S19e/S19f，没有隐式遗留。`compileJava`、`gpu-probe:compileJava`、`protocol` 与新增规则后的 `verifyArchitecture` 通过，架构门扫描 404 个生产源。无需进游戏：协议会话与 snapshot 路由均有自动表征，不涉及视觉或真实 `VoxelShape`。

### S19e

* 先复审 S19d 后的 runtime 边界，确认 network/API/GPU 已不再反取 service；进一步发现容器 actor 初稿虽改读新 registry owner，却仍借 service 完成目标解析、availability、事务与 snapshot，因而没有把消费者真正迁出。最终新增 `MinecraftRegionAvailability`，player/dispenser adapter 直接持有 target、availability、transaction、snapshot 与 committed world owner，`MinecraftContainerTransferPlan` 也只持事务 adapter，不再依赖 service。
* 新建 `CommittedWorldRegistries`，独占 startup 证明后的 profile/registry、actual 容器 profile 绑定、材料解析与液体 U 绑定；creative source、容器 plan/operations、命令 giver 全部改读该 owner。`CELLS_PER_BLOCK`/`CELL_VOLUMES_PER_BLOCK` 归入 `FluidRegionFactory`，两个 region factory 删除永远取同一常量的形参。
* controller 激活与 indicator 编辑分别进入 `MinecraftRegionActivation`、`MinecraftIndicatorEditing`；创建前 WAL/ownership/admission/footprint 检查、首个 HEAD 后才登记 live owner、失败文案和 indicator checkpoint-warning 语义均保持。block/item 只从 runtime 取得对应语义 owner，架构门删除二者的 S19e 例外。
* `FluidSimulationService` 降至 984 行；`containers,materials,chemistry` 与 `verifyArchitecture` 一次 Gradle 运行通过，架构门扫描 408 个生产源。无需进游戏：本切片未改真实 `VoxelShape`，world truth/容器代数有领域门，controller commit 顺序与 indicator checkpoint 路径为逐分支结构迁移。

### S19f

* 先复审 S19e 的消费方向，确认 container plan/actor 已不再反取 service；所有新 owner 仍由 service 构造再显式交给 runtime，这是 S21 删除 service 时上移 composition 的已登记边界，不在命令切片制造重复构造根。
* `/mmm` 的 demo/materialdemo/chemistrydemo/controllerdemo/tensiondemo/airdemo/capture/takeover 改为直达 `MinecraftDebugFixtures`；fixtures 仅公开既有场景命令面，构造与 consumer-owned `Runtime` 仍保持包内。`MmmMod` 的脚手架清理同样直达 owner，service 的场景委托镜像全部删除。
* 新建小型 `MinecraftRegionAdministration`，组合现有 catalog/persistence/application/recovery owner，接管 regions list/select/checkpoint/recoverprevious；`SelectionResult` 升为顶层类型，未把低层 `FluidRegionOperations` 整体公开。checkpoint 的 pending-WAL 预检、generation 重验、typed status 到原文案映射保持不变。
* 其余 run/pause/step/remove/编辑/flag/fault operator 动作仍明确借 service，架构 allowlist 从 S19f 改绑 S21；不是通过新 facade 隐藏未拆职责。service 降至 894 行。`audit,lifecycle,containers` 与 `verifyArchitecture` 一次 Gradle 运行通过，架构门扫描 410 个生产源；无需进游戏。

### S19h / S19 收口

* 先复审 S19f 的权限与提交边界：所有迁移后的 handler 仍在同一个 `MmmCommandLog.run` 审计包装内；region administration 只接收已解析参数，checkpoint 仍经 application 的 pending-WAL 与 generation 重验。没有发现权限、transcript 或 commit-point 回退。
* 新建只读 `RegionDiagnosticsReport`，接管 status、thermal/gas summary、ownership、chemistry inventory 与 granular inventory；构造只注入 manager/operations/takeover/fixtures/committed world truth 及窄 flag/budget supplier。region summaries 继续只归 `MinecraftRegionAdministration`，没有为了照抄提案制造第二份 catalog 报告。旧实现与新 owner 三组报告的字符串 literal 多重集逐项一致，命令结果文案未改。
* `MmmCommands` 的 status/ownership/chemstatus/granstatus 与 step/pause 失败附带状态全部改走 runtime diagnostics；service 的约 230 行只读报告面删除并降至 `arch_baseline.py` 计数 661 行，低于 1000 行硬门，因此删除 size allowlist。service 仍有命令 operator 写动作、tick 与 recovery/shutdown 消费者，其唯一外部 import 例外明确绑定 S21；本轮没有建立替代 god facade。
* 收口复核发现 Brigadier 树虽已指向语义 owner，但 handler 仍与树同居；最终拆出 package-private `MmmCommandHandlers`。`MmmCommands` 由 618 行降为 159 行，只保留树、权限、参数与统一 audit；475 行 handler 承接原方法，service import 的 S21 allowlist 也精确随迁，架构门同时守住两个根包命令类，未以改名绕过禁令。
* routing、architecture baseline 与 README 命令表同步为当前权威面；过期的 persistence→live 与 core cycle 方向项从当前 allowlist 表移除，模块规模更新为 api 11/263、core 214/33393、fabric 161/26342、client 14/1590、gpu-probe 12/3867。`audit,protocol,lifecycle,containers` 与 `verifyArchitecture` 一次 Gradle 运行通过；命令类最终拆分后再跑最近的 `audit` 与架构门，扫描 412 个生产源并通过。S19e 另已通过 `materials,chemistry`，S19g 另已通过 client compile/protocol。无需进游戏：S19 没有新增真实 `VoxelShape`、跨重启事务格式或无法自动观察的视觉参数变化。

S19 完成条件复核：network/item/block/client/API/GPU 与 `MmmCommands` Brigadier 树对 `FluidSimulationService` 零 import；协议会话由单服务器 runtime owner 持有；renderer 为 69 行编排且 ledger/GPU 各有唯一 owner；命令的场景、world truth、region administration 与诊断均已迁出。剩余 service 删除及 `MmmCommandHandlers` operator 写动作迁移按架构门明示归 S21。

### S20 前置复审

* 沿 S19 的真实消费边界重新核对 runtime accessor、协议会话、容器 actor、方块/物品与命令路由；代码中已迁移 owner 仍然闭合，但 `system-overview` 的主数据流还把命令、方块和容器统一画入 `FluidSimulationService`，会让后续模块依赖审核重新引入 god-service 方向。
* 权威数据流已改为 `MmmRuntime` 显式语义 owner 分流；service 只标记尚未收口的 server tick、operator 写动作与 shutdown/recovery。本修正不改运行时行为，但先消除了 S20 物理模块分层的错误依赖依据。

### S20a

* 没有机械照搬提案的目标包名：当前真实 DAG 是 `api ← model ← solver ← application`。`chemistry/granular/thermal/gas/transfer/controller/worldprofile` 与 immutable particle/region checkpoint state 无活动 `FluidDomain` 依赖，因此进入 `model`；`fluid` 求解器根与 stage 进入 `solver`；已经 loader-neutral 的 command/result/use case/port 与 checkpoint source 进入 `application`。这一切片只移动源文件和 Gradle 所有权，不改 package、类名、算法或持久/协议布局。
* `core` 现在无生产源码，只作为兼容 artifact 名重导出 `api/model/solver/application`；Fabric 显式编译并内嵌四个实际 artifact，避免空 `core` jar 隐式丢类。物理模块分别产出 sources jar，且使用 Java 25 release 在无 Minecraft classpath 下独立编译。
* 没有为模块外观新增重复测试；把既有 thermal owner、cut-cell solver stage 与 container application fault-matrix 表征分别迁入所属模块，各模块有独立 JavaExec 门，Fabric CPU selector 通过显式 test artifact 继续聚合同一类。`runModuleValidation`、Fabric test compile 与更新扫描根后的 `verifyArchitecture` 通过；当前规模为 model 128/17173、solver 71/15010、application 24/1848，core 为 0 个生产源。

### S20b

* 建立 loader-neutral `gpu-kernel`：Vulkan pipeline、Shaderc/VMA capability probe、四组 headless validation、checkpoint/scenario/benchmark 与 shader 资源整体迁入该 artifact；其生产源码和资源对 Minecraft/Fabric import 为零。可选 Loom 模块只剩 mod initializer、server/world collision adapter 与服务生命周期，并通过 nested kernel jar 消费实现。
* 把原本混在 server service 的固定 probe scenario、CPU fallback 构造、checkpoint/manual-step 规则及 benchmark 迁入 kernel；世界 `VoxelShape` 采样仍留在 adapter，但八位 block mask 到 solver-cell 的纯展开规则由 kernel 独占。没有改变网格、迭代数、checkpoint 节拍、shader binding 或碰撞位序。
* headless/GPU JavaExec 门移到 kernel，原 `gpu-probe:*` 任务仅保留兼容委托；原 GPU correctness 中唯一的 Brigadier/Fabric 命令树断言替换为 adapter 自有 `runAdapterValidation`，避免 kernel 反依赖 loader。独立 kernel compile、adapter main/test compile、command adapter 门与 `verifyArchitecture` 通过，架构门扫描 415 个生产源。

### S20c

* 原 1237 行 `VulkanFlipPipeline` 拆出 `VulkanDeviceContext`、`FlipGpuResources`、`FlipKernelBindings`、`GpuStepScheduler` 与 `GpuReadback` 五个 native owner，pipeline 降至 808 行且不再创建/销毁任何 Vulkan handle。instance/device/queue、27 个 storage buffer、shader/descriptor/pipeline、三槽 command/fence/query ring 和 bulk host transfer 分别有唯一字段与显式逆序 close；dispatch 次序、push layout、descriptor binding、buffer byte layout和四 physical substep 未改。
* completion executor、slot future 与 fence wait 全部进入 scheduler；slot 复用仍在重录 command pool 前等待前次 completion，close 先聚合完成异常并等待 device idle，再销毁 ring。readback 继续在每次同步 copy 前等待全部 submitted work，device-local 与 host-visible 两条路径仍由同一 API 表征。
* 真实 GPU gate 暴露了 S17 后未闭合的旧数据流：GPU checkpoint 的 binary solid cell 被直接交给已采用八位 cut-cell mask 的 `FlipParticleState`，导致完整固体统计/CPU recovery 变成零。边界现把 `0/1` 严格验证并映射为 `0x00/0xff`；既有 checkpoint 断言替换为 initial/final exact solid count。capability probe 通过（RTX 5070 Ti，Shaderc/VMA/dispatch 均正常），GPU correctness 已越过 checkpoint、两种 memory mode、timestamp/ring、碰撞与 pressure gates。
* 最后的既有 long-run surface 门仍失败：新实现 GPU cells=1728、CPU=2840；用 `7af2590` 独立 worktree 对照为 GPU=1727、CPU=2840，证明不是本切片的同步/所有权回退。未放宽断言、调常数或钳制算法；该数值缺陷保留为 S20 收口前必须明确处理的门。

### S20d

* 删除 `GpuProbeService` 的 static `WeakHashMap<MinecraftServer,…>`、`get/removeServer` locator 与全局 capability report；`MmmGpuProbeMod` 实例现在在 `SERVER_STARTING` 创建唯一 service，在 tick 只驱动同一 server identity，并于 `SERVER_STOPPING` close 后清空实例字段。重叠 server lifecycle fail closed，不再让命令或 tick 隐式创建 backend owner。
* 新建窄 `GpuProbeHostIntegration`，只暴露主域冲突只读判断、probe snapshot/removal 发布与主域 snapshot 复发；唯一 `MmmGpuProbeHostIntegration` 才允许 import host `MmmRuntimeRegistry`/network protocol。service 不再接触主 runtime registry、payload 或 protocol sender，世界碰撞读取仍留在 adapter。
* 架构门删除 GPU locator 与 `VulkanFlipPipeline` size allowlist，并新增 adapter host-boundary 规则和 self-check fixture；除唯一 integration implementation 外，`gpu-probe` 对 host simulation/network import 为零。adapter compile/command tree 与 `verifyArchitecture` 通过，扫描 422 个生产源，pipeline 仅产生 808 行普通 warning。

### S20 完成

* S20 最终模块边界为 `api ← model ← solver ← application`，Fabric 显式组合这四个实际 artifact；`core` 不再拥有生产源码。可选 GPU 路径分为 loader-neutral `gpu-kernel` 与 Fabric `gpu-probe` adapter，后者产出的 mod jar 已核对内嵌 kernel jar、shader 与 kernel class，不依赖开发源码目录才能装载。
* Vulkan native 所有权已分别闭合在 device、resources、bindings、scheduler 与 readback owner；adapter host bridge 只有一个实现，static server locator 已删除。README、system overview、routing、performance/backend roadmap 与 architecture baseline 同步为当前依赖方向、运行时数据流、模块规模和选择器入口，不再保留 S19 的过期 god-service 图示。
* 使用 JDK 26.0.2、Java 25 target 的最终干净运行 `clean assemble runModuleValidation :fabric:runCpuValidation verifyArchitecture -PcpuValidation=milestone --offline --no-daemon` 成功：三个模块边界门、23 个 CPU 里程碑门、carrier inventory 与 422 个生产源架构扫描全部通过；可选 GPU adapter 门与真实 capability probe 也已分别通过。没有需要进游戏观察的视觉、真实 `VoxelShape` 或跨重启场景。
* 完整 GPU correctness 仍由既有 long-run surface 门拒绝：GPU 约 1728 cells、当前 CPU conservative reseeding 为 2840 cells；在拆 owner 前的 `7af2590` 独立 worktree 中复现为 1727/2840，排除了 S20 生命周期、scheduler 或 readback 回退。该差异没有通过经验阈值、速度钳制、渲染补偿或特例掩盖；GPU backend 继续不具备生产资格，后续应实现与 CPU 同一守恒不变量下的 GPU reseeding，而不是把它误记为本轮架构迁移已修复。
* S20 的五个实现提交为 `eec1ccd`（运行时权威流）、`b0af168`（物理模块）、`7af2590`（GPU kernel）、`29c8b0e`（Vulkan owner）与 `d646aee`（adapter 生命周期）。结构重组至此闭合；未把独立的 GPU 数值能力缺口隐藏在架构完成声明中。

### S20 数值收口复审

* 重新从离散守恒与动量方程审计 long-run surface 差异，而不是沿 owner 拆分倒推。根因是 CPU/GPU 已有压力几何、传输、平流、碰撞与重采样语义漂移，同时 CPU 还用 `FlipVolumeDriftCorrection` 把 marker 体积误差写成不可压缩方程的伪源项。最终删除该修正及其配置投影入口；pressure unknown 只取 PLIC 液体占比中心包含的 cell，不再由 marker 存在覆盖，也不再用薄层 `theta` 经验下限掩盖几何退化。
* P2G 保留按 marker 体积归一的质量/动量守恒；G2P 的 PIC/FLIP 比例改由单步非增动能约束解析取得，不再固定耗散来冒充黏性。网格速度以 H(div) 一致的面场重建，marker 使用 RK4 平流，边界碰撞只移除向外法向速度。CPU 与 GPU 在当前共同能力子集内使用同一离散规则；连续介质黏性扩散仍是尚未实现的独立物理项。
* 表面张力按拓扑互斥地离散：有 pressure unknown 的已解析液气界面走 sharp ghost-fluid jump，只有亚格 carrier 跨面走互补 balanced-force CSF；液液界面继续走无序材料对的 balanced-force 路径。接触角由 `sigma_sl - sigma_sg = -sigma cos(theta)` 的 Young 固壁表面能产生，固壁面积来自同一 cut-cell microgeometry；曲率法向不再被接触角旋转，也没有材料 ID 特判。
* 重采样的 split/merge 在 CPU/GPU 上共享守恒条件：只有二进制浮点和能精确回收较小 marker 体积时才合并，否则保留 marker 并登记 conservation refusal。GPU shader 使用 GLSL `precise` 阻止编译器把 `(large + small) - large == small` 重关联成恒真式；1000 步门用每个 raw float 构造的 `BigDecimal` 权威账本验证 CPU/GPU 都保持 `2592.0 BV`，而在线并行归约只在解析舍入界内作为诊断。
* `GpuCompactDiagnostics` 独占 compact frame 解码、pressure convergence、在线体积误差界与 reseed budget 校验，`VulkanFlipPipeline` 降至 942 行。异步 completion 现在捕获本次 submission 的 substep time，不再读取提交后已复位的可变 `recordingTimeStep`，因此初始化 submission 与四 physical substep 的诊断属于各自帧。
* 当前 GPU 能力边界明确收窄为单一、常密度 carrier；多材料/变密度、表面张力、空间气体/热力学尚未移植，不能以 CPU fallback 或 probe 通过宣称 production parity。现有集中参数 sealed-cavity 气压边界仍由 CPU 门覆盖，但空间可压缩气体求解留给后续物理切片。最终验证包括 GPU 1000 步 exact ledger、完整短程 solver/memory matrix、CPU `free-surface,reseeding,cut-cell,surface-tension,materials,gas-liquid` 六门、production replay、三个 loader-neutral module 门，以及扫描 425 个生产源的架构门，均通过；无需进游戏观察。

### 空间可压缩气体起始切片

* 先复审 S20 数值收口与现有 gas owner：液体的守恒 P2G/G2P、`H(div)` 平流、压力几何和 CPU/GPU 共同子集可以复用，但当前 `PressureSystem` 是严格不可压 Poisson operator；`AirNetwork` 则只拥有 node 级 exact inventory/`U` 和孔流，没有空间动量。若直接新增逐格气体而保留可独立演化的 node inventory，会制造双重物理真值，因此本切片先冻结表示切换和全马赫方程，不伪造尚未存在的空间 owner。
* `GasThermalModel.HydrodynamicState` 现在从同一 non-vacuum inventory、available volume 与权威 `U` 派生 `T`、`p_abs`、`rho`、`C_v`、`R`、`gamma`、绝热体积模量、声速和 pressure compliance；`absolutePressure` 与绝热压缩复用该闭合。等温和热 EoS 都删除固定 `-0.99 P0..20 P0` 压力窗与 `1e-9 m³` 体积修补：有限库存零体积显式拒绝，正体积直接保留状态方程结果，真空明确没有 continuum pressure unknown。无热孔流的源密度也不再固定为 `1.2 kg/m³`，而从同一 node inventory/available volume 派生；环境标准态密度由 registry ambient mixture 取得，不按 component ID 分支。
* 现有同一不变量测试已替换：`air-network` 验证 registry-mixture 密度、压缩密度、连续近真空、原 ceiling 外有限高压和零体积拒绝；`thermal` 验证标准态密度/`gamma`/体积模量/声速/compliance 恒等式、vacuum exclusion，以及超过旧压力窗后仍满足 `p*V^gamma` 的绝热路径。`air-network,thermal,gas-liquid` 三个最近门和扫描 425 个生产源的架构门均通过。架构文档冻结统一 Helmholtz 压力式及其 `beta_s -> 0` 不可压极限，并明确空间候选还必须共同守恒逐格质量、组分、动量和总能量；本轮没有把一个未接线的 storage 数组冒充空间气体完成。

### 液气界面与集总可压缩气腔

* 复审发现热力学闭合本身成立，但 pressure matrix、surface-tension RHS 与 velocity projection 曾各自重算液气面系数，而且有限密闭气体仍被整步冻结为 Dirichlet pressure；直接把气体 cell 加入旧 Poisson 又会与 node inventory 形成双重真值。现以唯一有向 `PressureFaceCoupling` 统一三处消费：`J = p_liquid - p_gas`，两侧空间段采用串联质量阻抗 `m_f = rho_ref/(rho_l*d_l + rho_g*d_g)`，静态 Laplace jump 恰好给出零冲量。交换朝向只反转 jump，matrix coefficient 不变；不存在密度比、材料 ID、界面正则化或经验下限分支。
* 接触液体的有限 sealed gas node 以一个集总 pressure unknown 进入与液体相同的对称 PCG block。node 行的储存对角为 `rho_ref*V*beta_s/(dt^2*h^3)`：无热路径由同一 ideal-mixture inventory 得到 `beta=1/p_abs`，热路径从同一 `U` 得到绝热 `beta_s=1/(gamma*p_abs)`；face 向 liquid/gas 两行加入相同对角和相反 off-diagonal，capillary jump 以等量反号进入 RHS。开放环境和真空仍为 Dirichlet，真空不伪造 continuum unknown。
* 该 pressure unknown、PCG 向量与 active-node ordinal 仅为当次 projection scratch；`AirNetwork` 的组分/质量和 `GasEnergyStore` 的 `U` 仍是唯一气体真值。下一次 topology refresh 从真实液体面通量改变后的 cavity volume 重新求非线性 EoS 与绝热功，不把线性解压力持久化或反算成第二份库存。当前 node 没有内部速度、逐格动量、声波或混合层，因此只宣称完成液体与有限气腔的一步隐式机械闭合，不宣称空间气体完成。
* 同一不变量门现覆盖 1000:1 密度比的精确串联 mobility、two-sided 到 zero gas segment 的精确退化、静态 capillary jump 零寄生速度、有限气腔与表面张力共面时的库存不变、等温储存行和热绝热储存行。最终 `gas-liquid,surface-tension,free-surface,thermal` 与扫描 426 个生产源的架构门通过；`production-replay` 通过全部 500 步 ambient、1000 步 compressed thermal gas、body/ledger/candidate restore 场景，总耗时 74.397 s，实时 20-step median/max 为 20.037/26.154 ms，phase-on 为 27.766 ms。没有需要进游戏观察的视觉、真实 `VoxelShape` 或跨重启事务场景。

### S21 完成

* 先复审 S20 数值收口、空间气体起始切片与液气气腔闭合：当前守恒 P2G/G2P、压力投影、表面张力、热力学闭合和集总气腔没有阻断 S21 的已知矛盾。连续介质黏性、逐格气体动量/声学/混合、GPU 完整材料与热化学状态、实体压力的严格双向耦合仍是需要新增物理状态和 stage 的能力项；没有用经验常数、钳制、材料特判或架构包装把它们伪装成已完成。同步修正 performance roadmap 中已经失效的 GPU marker-surface 失败描述：共同能力子集的 1000 步 exact ledger 已闭合，GPU 尚不能生产化的原因是完整 `FluidDomainState` 能力与接线仍缺失。
* 删除最后的 `FluidSimulationService`。`MmmRuntimeFactory` 现在是唯一显式 composition root；`MinecraftSimulationLoop`、`MinecraftRuntimePolicy`、`MinecraftMotionExecutor`、`MinecraftRegionOperatorActions`、`MinecraftOwnershipIssues` 与 `MinecraftDebugScaffolding` 分别独占 tick/recovery/shutdown、world-local policy、同步 motion transaction、管理员写动作、ownership view 与可逆调试脚手架。命令、激活、fixture 和 lifecycle 直达这些语义 owner，`MmmRuntime` 只暴露行为入口。迁移审计还修复了一个真实回归：collision refresh 不可用时保留上一统计，碰撞编辑异常先登记 typed block 再继续抛出，恢复原事务可观察顺序。
* 删除 `FlipThermalState`；`ThermalDomainState` 直接作为运行时热真值，`FluidThermalBinding` 只承担 fluid construction、snapshot/restore、reseed 与 gas compliance 的无状态类型映射。`FluidDomain` 保持唯一 public live aggregate，但其实现按同一 Java 对象的 storage/contracts/mechanics/step/inventory/collision/capability/gas-granular 分区；所有数组只有 `FluidDomainStorage` 一份，分区不是互相同步的第二状态 owner。public leaf 为 17 行，最大分区 750 行。27 行 `RegionState` 保留为 current durable store 的活动 DTO adapter，不作为旧格式兼容 facade 删除。
* `thermal`、`region-state` 与 `production-replay` 的长实现按场景文件拆分，selector 名称、执行顺序、short/full 语义与场景步数不变；完整能力测试统一从 `FullCapabilityTestFixture` 加载 production registries。复审 chemistry 同一不变量时发现旧 fixture 把标准 0.125 m³ marker 错当成 oversized marker，已改为从合法 0.25 m³ current state 验证一次 split、exact species ledger 与 indicator provenance。最终 clean compile 又暴露 GPU adapter 仍引用已删除的 `PCG` 枚举，现将 `pcg` 别名解析到 kernel 唯一的 `PCG_DIAGONAL`，没有恢复第二种算法。
* 迁移 allowlist 已全部删除。`verifyArchitecture` 对依赖方向、唯一 runtime locator、GPU host 边界、carrier mutation 与生产类 1000 行硬门直接失败；最终扫描 439 个生产源，超限为 0。`uv run pytool/unused_imports.py` 对全部改动 Java 无报告，`uv run pytool/arch_baseline.py` 已回填当前规模。
* 最终 JDK 26.0.2 / Java 25 target 干净运行 `clean assemble compileTestJava runModuleValidation :fabric:runCpuValidation verifyArchitecture -PcpuValidation=milestone --offline --no-daemon` 成功：模块门、23 个 CPU 里程碑门、production replay 和架构门全部通过，live 20 步 median/max 为 4.361/6.441 ms，phase-on 为 11.327 ms。另行 full soak 已通过 gas-liquid、5000 步 free-surface 与长程 reseeding；GPU 1000 步 exact-ledger correctness、headless Vulkan capability 与 Fabric adapter validation 均通过。没有视觉、真实 `VoxelShape` 或自动门不可观察的跨重启差异，不需要进游戏验收。

### S21 游戏回归复审

* 用户实机日志暴露了旧 production 门的盲区：正式 controller replay 一直关闭相变，唯一 phase-on 场景也只有单个平面 step，因而没有覆盖“空闭壳先建立 `64 m³std` 气体、再注入 16 BV、长期相变、再次转移”的真实路径。按日志坐标建立的 400 步 full-budget phase-on 回放连续揭示并固定了总压/分压混淆、蒸气 caloric 数据域不足、跨 caloric segment 绝热压缩被拒、单液体 cell 邻接多气腔被误判非法，以及 pending phase-work 令转移永久返回零五个失败层；该场景归入既有 `production-replay`，没有新增 selector。
* 液气饱和方向现在只比较由液温得到的 `p_sat` 与气体 component partial pressure；有限非负总压（包括真空）不再被拿去查询固液 `T_eq(p)` 的压力端点。exact phase batch 在跨过平衡点时只选择严格缩小 `|p_sat-p_partial|` 的可表示状态，删除“至少一批”造成的逐步蒸发/凝结往返。一个液体 cell 邻接多个独立气腔是合法几何：planner 保留 node owner，按绝对分压失衡选择本 pressure interval 的一次交换，若受库存/能量/组分/容量约束不可执行再尝试下一 node；没有合并气腔、deadband、迟滞阈值或材料 ID 分支。
* `GasThermalModel` 用 `∫Cv(T)/T dT = R ln(V_initial/V_final)` 的分段解析等熵路径取代单段跨界拒答，可精确穿过共同 caloric boundaries 并可逆往返；内置 water vapor 按 NIST Shomate 数据换算为 `250–1700 K` 的五段 `cv`，不把 293 K 常量外推到压缩高温。能量、gas inventory、species、质量 residual、source/sink 和 pending `-pΔV` 继续由原 exact ledger 闭合。
* solver 仍拒绝在未结算 phase-work 上直接修改物质；Minecraft host 新增单一同步 barrier，管理员 `pour`/`drain`、玩家桶和发射器在转移前先通过当前权威 backend 运行一次禁止新相变的 mechanical candidate，exactly-once 消费旧 lien 后才进入库存/WAL 事务。控制器 `empty` 仍必须走控制器界面的 `EMPTY_CONDENSED` 预览/确认，命令不绕过生命周期授权；README 已消除这一入口歧义。
* 连续三次超过 200 ms 的 wall-time 保护改为封闭的 `PERFORMANCE_PAUSE`，与物理候选失败的 `NUMERICAL_PAUSE` 分离；`/mmm run` 只清除前者的短期 overrun 证据并从同一已提交状态重试，不会解锁无法解释的数值状态。实机日志中显示的 11–20 ms 本身不是超时证据，其实际暂停来自旧液气压力域误判；最终干净回放的 production live median/max 为 4.287/5.073 ms，phase-on production step 为 8.293 ms，均低于 40 ms 门。
* `entity-fluid` 的走动玩家尺度回放测得峰值反作用冲量 `1305.92590 kg·m/s`、液体动能峰值 `1906.20569 J`，对比有限排水体积的 added-mass 能标 `4313.34720 J`，且停止后回落到 `0.001070 J`；S21 未改 target resolver 或 moving-body 算法，没有证据支持准星距离或玩家动量耦合发生代码回退，故未凭主观观感修改这两条物理/交互语义。
* 最终 JDK 26.0.2 / Java 25 target 验证通过 `thermal,gas-liquid,lifecycle,entity-fluid,production-replay` 五个最近 CPU 门、loader-neutral module validation、完整 assemble/test compile 与扫描 441 个生产源的 architecture gate；GPU 按 routing 的最近门通过 `--skip-large --skip-long` 短程 solver/memory/collision matrix、headless Vulkan capability 和 Fabric adapter validation。GPU 仍只代表单一常密度 carrier 的共同能力子集，空间气体、热化学和完整 `FluidDomainState` 尚未迁移，不把 probe 结果表述为 production parity。

### S21 实机大域性能与负载削减复审

* 用户实机 `/mmm demo` 的 `24×16×24`、1,728 liquid cells、13,824 markers 首步达到 `490.40 ms`，暴露出原 `production-live-budget` 只覆盖小 `8³` material-only 域的盲区。按同一 solid/marker 数量建立完整 chemistry/granular/gas/thermal/phase fixture，并替换进既有 selector：小域 20 步仍逐步受 40 ms 门约束；大域五个生产 CPU step 必须不形成 wall-time policy 所定义的三次连续 `>200 ms`，且每步继续核对 exact 体积与质量，不关闭相变、热学或审计。
* 根因不是压力算法在首帧需要异常迭代：复现场景前四步均为 PCG 62。S20 后 CPU 正常帧却执行 input capture → private restore → solve → candidate capture → adoption restore，把完整多载体代次在同一进程内重复构造。现在 immutable request 与失败隔离不变；CPU 成功 candidate single-use 转移已经求值的私有 aggregate，application/repository 先重验 backend、紧邻 generation 和完整 layout，领取后不得再从已成为 live owner 的对象物化历史 state。checkpoint/replay/diagnostic 在领取前仍可按需生成完整 immutable state；eager/GPU candidate 仍走完整 state adoption。这也固定了未来 device-resident GPU 常规帧不得强制 full readback 的所有权语义。
* 热化学 handoff 中同一 immutable mixture、stable mixture、runtime mixture 和 base energy 不再按 marker 重建；restore target 直接装入已提交 liquid `U`，不先计算随后覆盖的初值，也不重复 phase scan/liquid energy restore。`SignedDyadic.sum` 改为按 binary64 固有 2,046 个 exponent 分桶、每桶 signed 128-bit 精确累加后合并，和逐项 `BigInteger` exact add bit-for-bit 等价；8,192 个跨宽 exponent/正负值的既有 exact-audit 场景固定了等价性。没有引入经验常数、速度钳制、材料分支或账本近似。
* 独立冷 JVM 的大域五步为约 `218.710/149.851/182.103/179.787/180.631 ms`；最终完整 production replay 中为 `247.472/212.572/151.504/162.799/192.351 ms`，最大连续超限为 2，低于暂停条件 3。默认 controller 20 步最终 median/max 为 `12.864/16.380 ms`。这关闭了当前自动暂停回归，但 9,216-cell CPU 稳态仍明显不能达到 20 Hz，因此不把结果表述为生产 GPU 已无必要。
* `PERFORMANCE_PAUSE` 现在只阻止增加库存与继续自动 stepping；`drain`、玩家/发射器装取、非 controller `empty` 和 controller destructive lifecycle 可在其余 ownership/boundary/WAL/audit/phase-work 门全部通过时减少负载。controller lifecycle 在预览和确认两个事务边界都重新执行完整 availability 检查，仅容忍已有的 wall-time pause，不能让旧 performance reason 掩盖随后出现的开壳、缺 chunk 或 ownership 变化。真正的 `NUMERICAL_PAUSE`、热/气/颗粒失败和持久事务歧义仍 fail closed。`/mmm run` 继续只清短期 performance evidence。controllerdemo 的移除入口在命令失败消息与 README 中明确为：手持物质场探针普通右击控制器，依次预览并确认 `EMPTY_CONDENSED`、`VENT_SEALED_GAS`、`RELEASE_OWNERSHIP`、`REMOVE_EMPTY_REGION`；命令不绕过一次性控制台授权。
* 最终门通过 `lifecycle,containers,production-live-budget` 与完整 `production-replay`；后者继续覆盖 1,040 次压缩气体 controller candidate、phase-on 蒸发/凝结、200 次 partial gas lineage、500 步平面静水和 1,000 步压缩气体静水，总 `U`、gas inventory、source/sink、phase work、体积、质量与 raw handoff parity 均闭合。loader-neutral module validation、GPU kernel/main-mod adapter 编译与 adapter validation 通过，architecture gate 扫描 441 个生产源通过。

### S21 最大装量大域压力迭代复审

* 后续实机日志在无 solid collision 的 `/mmm demo` 中报告 324 BV、2,592 liquid cells、约 18k markers、3 substeps/231 PCG 和连续 `>200 ms` 暂停。复审命令生成器后确认 324 BV 是空场景的正确初始装量：dam-break 区域为 `x<9,y<12,z<24`，共 2,592 满 cell；上一门把玩家首次场景中的四层实心地板误写进 fixture，实际只覆盖 1,728 cell，而且只跑五步，恰好避开第 12 步后稳定出现的 3-substep 区段。既有 `production-live-budget` 已替换为最大装量 20,736 初始 marker、完整能力、20 个 production ownership-transfer step；phase-idle 步要求 exact liquid volume/mass 不变，相变步由 candidate 的 gas/thermal/species/energy audit 闭合，不再把真实蒸发误报为液体漂移。
* 性能剖析定位到 PCG 每次迭代都重新扫描 9,216 cells，并重复计算同一 projection 内不变的 PLIC/cut-face、变密度 mobility、fluid/gas classification 与 diagonal。`PressureProjectionStage` 现在按原 RHS 遍历顺序建立活动 liquid unknown 列表；`PressureSystem` 在 PCG 前一次装配六向 face coefficient 与 diagonal，residual、preconditioner、matrix-vector、dot 和向量更新只遍历活动 liquid/gas unknown。同一次 projection 中这些量本来就是固定线性算子的系数，因此这是消除重复装配，不是近似、冻结跨步物理或改变 pressure 方程。
* 为排除“更快但换了算法”，matrix-vector 保留原六向项的 float 累加顺序与最后的 diagonal 运算顺序；改动前后的 20 步逐步得到相同 marker 演化、CFL substeps、PCG iteration、residual 与第 17 步 62 次相变操作。相同后半段从约 `272–297 ms` 降到 `144–179 ms`；最终门最大 `193.220 ms`，连续 `>200 ms` 为 0。JFR 显示余下主要成本已转为真实 P2G marker scatter、advection 与 thermochemistry，未继续重写这些会改变浮点散射顺序的算法，也没有提高 200 ms 阈值、降采样、减少迭代、关闭能力、速度钳制或材料特判。复用的 JFR 聚合器已放入 `pytool/jfr_hotspots.py` 并登记。
* 手动 `/mmm step` 现在与 `/mmm run` 一样，作为从同一已提交状态发起的显式 retry，只清除 `PERFORMANCE_PAUSE` 的 wall-time evidence 后重新经过完整 availability；`NUMERICAL_PAUSE`、气体/热学/颗粒、边界、ownership 和事务失败仍保持封闭。step 失败文案不再一律误报“未完整加载或边界打开”，而是附带当前 typed status。既有 `lifecycle` 门替换同一重试不变量，固定 performance 可清除、numerical 不可清除。
* JDK 26.0.2 / Java 25 target 下，`production-live-budget`、`free-surface,gas-liquid,lifecycle`、loader-neutral module validation、GPU kernel/adapter compile 与 adapter validation、扫描 441 个生产源的 architecture gate 全部通过。默认 `8³` live 20 步 median/max 为 `11.641/20.330 ms`；最大装量 20 步覆盖 1→3 substeps、74→267 aggregate PCG 和真实相变。该结果修复 CPU wall-time 锁死，但 9,216-cell 域仍不满足 20 Hz，完整材料/gas/thermal GPU 后端的路线不变。

### S21 开放域气体截留与热力学有效域复审

* 用户真实四层地板 `/mmm demo` 在运行约百步后以 `Gas isentrope leaves the common caloric validity range` 进入 `NUMERICAL_PAUSE`。原最大装量 wall-time fixture 是无地板的 2,592-cell 场景，只跑 20 步；它覆盖性能却不会走到液面翻卷后出现的有限气泡。既有 `production-replay` 现在增加与世界命令相同的 `24×16×24`、四层 full-solid、1,728 liquid cells、13,824 markers、完整 gas/thermal/phase 能力的 200-step backend candidate 回放；门必须实际观察到 ambient 截留和 air/water-vapor 混合节点，并逐步要求 gas source/sink 与 exact total-`U` audit 闭合。性能计时仍只留在 `production-live-budget`，避免长时正确性场景改变被测门的预热所有权。
* 第一处失败是内置 air 仍使用 `250–600 K` 的单段 `718 J/(kg·K)`，把数据端点误当作现实停止边界。`mmm:air` 现在由 NIST 干空气 N₂/O₂/Ar 组成和各组分 Shomate caloric 数据导出：标准态摩尔质量给出 `rho=1.2038886846941566 kg/m³`，逐区间的焓积分减去通用气体常数得到 `100–1700 K` 七段质量比 `cv`；标准态所在的 `250–350 K` 独立成段，其余边界按物性曲率合并，避免宽区间平均热容把 293 K 的 `γ` 明显偏移，也避免 16 段线性查表在大域热循环中造成可测的 wall-time 回退。这与 water-vapor 的共同上界一致，但仍不外推物性；registry fingerprint 随标准密度真值更新，不兼容旧 registry 的行为不保留。
* 延长数据域后，回放揭示剩余“绝热越界”并非真实低温，而是 topology remap 把约 `0.05 m³` 旧气泡瞬时当作膨胀到约 `0.7–1.5 m³`。根因是开放 ambient cell 被液体夹断并并入既有 sealed bubble 时，新节点因已有旧贡献而跳过环境截留；同时，节点中本代新出现但上一代没有 owner 的真空体积也没有进入 inherited volume。现在 remap 分别累计上一代 ambient lineage 的本代实际体积，按环境组成记 external source；上一代无 owner 的本代体积只建立零库存真空控制体积；二者与旧 sealed inventory/`U`/volume 合并后才对剩余几何差额结算功。同一 cell 的旧 sealed overlap 仍只认旧 full membership，不让 interface lineage 重复分配库存。
* 同一复审补全真空控制体积语义：零库存节点若仍有 topology destination，其旧体积参与 merge；新出现的无 owner 体积也参与同一 inherited-volume 列，所以有限气体向真空自由膨胀保持 `U` 不变。真空若被载体完全挤至零且没有后继，则没有库存、能量或体积要转移，不触发 closed-gas lineage loss。常态测试分别固定首次 ambient 截留、首次无 owner 真空、cut-cell gas/vacuum merge 的 source、温度与 exact `U`，原 controller partial-bubble 门同时固定零体积真空消失仍合法。实现没有温度/速度钳制、deadband、材料 ID 分支或把水蒸气数据域向低温硬扩；越出所有组分真实共同 caloric 域仍会拒绝候选。
* 性能复审没有把热力学修复掩盖成阈值调整：JFR 定位到气液扫描为每个候选 cell 调用 `depositKeys()`，即使无颗粒库存也反复遍历全域 9,216 个 deposit head。生产 `ThermalCarrierContext` 现在从 `GranularDepositStore` 的目标 cell canonical sparse chain 读取 heat bath；平衡 interface 只做温度/饱和方向判定，直到首个可执行方向才构造 exact liquid inventory 与 thermal candidate。P2G marker loop 另把同一 marker 的 immutable 位置、体积、质量和材料列各读取一次，继续按原 center→pressure→X/Y/Z face 次序累加。三项都只删除不参与候选的重复派生或数组读取，不改变 phase extent、浮点加法顺序、操作预算或账本；`thermal,gas-liquid,air-network,air-cavity,granular,free-surface` 与包含 200 步四层地板场景的完整 `production-replay` 通过，后者仍得到 5,007 次 phase operation 并闭合 gas/source/sink/总 `U`；loader-neutral module validation 与扫描 441 个生产源的 architecture gate 通过。
* 最终无并行 Minecraft 进程、无 JFR 的 `production-live-budget` 通过：默认 `8³` live 域 20 步 median/max 为 `11.302/16.863 ms`；最大装量 `24×16×24` 域 20 步最大 `174.813 ms`、连续超过 `200 ms` 为 0，并覆盖 1→3 substeps、74→267 aggregate PCG 与第 17 步 62 次真实相变操作。该结果关闭本轮 CPU 自动暂停回归，但 9,216-cell CPU 域仍不满足 20 Hz，完整材料/gas/thermal 后端迁移 GPU 的产品路线不变。

### S21 化学容器热审计实机回归

* 先确认上一轮开放域气体修复及性能收口已经以 `9a1a10a`、`486502b` 提交且工作树干净，再按用户实机日志追踪本轮故障。主崩溃不是 Iris、GPU 或 Realms：运行中的 `chemistrydemo` 被空材料桶抽取后，下一次 backend capture 在 `FlipThermalParticleState` 拒绝了不闭合的 thermal handoff；日志尾部另有 server runtime 已停止后 play disconnect listener 仍调用 `require` 的次生异常。
* 根因位于多 marker 容器能量边界。旧路径逐 marker 用 binary64 `previousEnergy-newEnergy` 记 sink，再把这些值精确求和后舍入为一个 item `double`；运行过反应、传导和 marker 演化后，逐项表示、实际 raw carrier `U_before-U_after` 与整桶一次舍入不再相同。现在提取计划在修改前按每个 raw carrier 能量和 planned new energy 构造 exact dyadic 总差，以它作为真实搬运能量；marker 截断先按实际 carrier 差闭合 region，整件物品只舍入一次，再把 item raw energy 与 exact carrier 差单列为 thermal roundoff。最终 external sink 与 `ThermalContents` raw energy 完全相等，没有放宽 handoff 审计、温度修补或材料特判；材料 mixture 与 chemical 两条抽取路径共用同一结算。
* 既有 chemical container round-trip 表征已替换为 production registries、0.5 m cell、HCl/NaOH 对半、40 个完整 logical step 后抽取 8 BV 的场景；它要求下一次 thermal capture 成功、external sink 等于物品能量，并明确证明该场景的 exact carrier/item representation residual 非零且只出现在 roundoff。play disconnect listener 改为可选查找 runtime：正常在线事件仍清理 console viewer/session，shutdown 已先移除 runtime 时不再制造第二个停止异常。
* JDK 26.0.2 / Java 25 target 下，`chemistry,thermal,containers,protocol,cut-cell` 五个最近 CPU selector、loader-neutral `runModuleValidation` 与扫描 441 个生产源的 architecture gate 全部通过；改动 Java 的 unused-import 审计和 `git diff --check` 通过，architecture baseline 也按当前 441 个生产源回填。该回归现可由自动 capture、item/sink 与 exact roundoff 共同观察，不需要再次进游戏验证。

### S21 材料桶热库存复审

* 复审上一提交后确认材料桶和化学桶已经把 registry-bound 总内能 `U` 写入 `ThermalContents`，并用 underlying hash 绑定精确体积、逐材料质量和完整化学组成；温度从这些真值反演，不应增加独立平均温度字段。热区域还会拒绝缺失、损坏或 registry 不符的热 component，因此完整舀取再倒出不会按环境温度补值。
* 复审同时发现部分倾倒仍把 `U` 按总纳体积比例拆分。这个关系只对同质且质量随体积同比的库存成立；多材料密度/热容不同，或 chemical split 因 species quantum 产生实际组成差异时，会让两侧温度改变。新增 loader-neutral `LiquidContainerThermodynamics`：从源桶完整库存与 `U` 反演共同温度，再以实际选中材料 kg/species 计算搬运 `U`，item 余量使用减法承接浮点余数；纯水才保留同质体积分拆。材料 insertion 在修改 solver 前冻结完整 selected/remaining settlement，并在提交后 raw 校验同一计划；化学路径复用其已规划的 exact split。没有 region/WAL roundoff owner 的部分水桶工作台合并现在拒绝带热 component 的输入，不再静默剥掉 `U`。
* 既有 `containers` 常态表征替换为异质材料与 chemical species 两类反例：相同 50% 总体积按旧算法得到 `11000 EU`，实际选中质量/热容要求 `15500 EU`；新路径的 selected/remaining 都反演回源桶 `320 K`，raw `U` 仍由减法闭合，异质库存的体积拆分被拒绝。其余不作为桶内热力学库存保存的量也已明确：marker 空间分布、压力和温度分布在桶内平衡化；流体动量/动能由玩家容器操作作为外部机械边界吸收，当前不会随 item 往返。
* JDK 26.0.2 / Java 25 target 下，最近 `containers,thermal` 联合门、loader-neutral `runModuleValidation` 与扫描 442 个生产源的架构门通过；材料/部分桶的玩家与发射器入口仍只经受管 transaction，材料桶本身没有原版放置行为。部分水桶工作台合并的生产匹配器显式拒绝 `ThermalContents`，避免配方在没有 region/WAL roundoff owner 时静默丢失 `U`。

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
* `RegionRuntime` 是单次 tick 的 scheduling snapshot，不与 `FluidRegion.PersistedRuntime` 争夺权威，也不持有 solver/domain。`MinecraftRegionInputs` 作为 server-thread input owner 独占到期的碰撞/热边界刷新、实体观测/响应、相邻 tick 位移历史和 runtime 冻结；moving-body request 仍只由 motion plan 消费。`MinecraftRegionTick` 独占单区域 command/result、snapshot 与 post-step observation，不建立通用 collision/entity/snapshot port。
* controller token 只能在 bounded view 已存在、region identity 匹配且完整 summary 字段验证成功后签发。generation 是 authorization nonce，不是 query revision；失败 read/projection 不消耗 generation，旧 token 仍按“最新同 viewer/region token”规则决定是否有效。
* fixed thermal reservoir 的 setpoint owner 仍是 BlockEntity；application 只拥有 summary/token/policy，不建立虚假的 region thermal port。设定变化只影响未来 boundary flux，Fabric 在成功写入后刷新相邻 region 并按 boundary change 唤醒，不能直接把温差换算成 region `U`。
* `RegionLifecycleUseCase` 只拥有 run-intent/preview authorization，不以 callback 取得 persistence 或 world 写权。`MinecraftRegionLifecycle` 冻结 Minecraft facts，并分别编排 inventory candidate+HEAD、ownership journal 和 REMOVED tombstone/quarantine 三种提交协议；不得用一个 `commit(operation)` 通用 port 抹平它们的 commit point 与失败分类。
* `RegionCatalog<T>` 的泛型值只作 opaque runtime owner；所有规则只读 immutable descriptor。Fabric 暂保留 `FluidRegionManager` descriptor adapter 来稳定大量调用方，但 limits/usage/admission result 直接使用 catalog 的规范类型，adapter 不再复制 DTO，也不计算预算、overlap、selection 或 replacement；后续调用者迁完可删除，不把 Minecraft identity 引入 core。
* runtime admission 不为“层次完整”单独建立无状态 application service。它与 ownership、boundary、restore 和 checkpointed unload 是一条惰性取证的 server-thread 工作流；`RegionCatalog` 继续独占 loader-neutral catalog 规则，但 adapter 直接消费其 typed result。数值/ownership 就地阻断，其他拒答只有完整 checkpoint 成功后才释放 solver。
* S17 不新增 `RestoredRegionState` 镜像 aggregate：现有 `FlipParticleState`/`FlipMaterialParticleState`/`FlipChemicalParticleState`、gas/granular/thermal/pending/device snapshot 已是 loader-neutral immutable truth；`RegionCheckpointRestorer` 返回这些规范类型，identity/geometry/runtime 由同一个 `RegionCheckpoint` 提供。再包一层 nullable 多载体 record 只会复制 layout 不变量。live `FluidRegion` 的构造/恢复仍由 admission 后的 Minecraft adapter 执行。
* S18 不新增没有 loader-neutral 调用者的 ownership application port。实际事务需要 Minecraft footprint/preimage 与 server-level actor，故由 `OwnershipTransactionCoordinator` 作为 adapter owner；它委托既有低层 WAL coordinator。`RegionRepositoryAdapter` 保留 `OwnershipStore` 只读 guard，因为 checkpoint/restore 必须对同一区域 sidecar 歧义 fail closed，但 ownership 世界修改不再属于 repository。REMOVED quarantine 预算只在提交前 admission；提交后收敛不重新接收预算。
