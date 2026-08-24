# M9 温度、相变与反应热：独立设计蓝图

> 状态：实现前提案，供另一位 AI 分增量执行。  
> 基线：主 Mod 0.14.0，M0–M8 已关闭；本文不修改任何既有冻结格式。  
> 设计日期：2026-08-24。  
> 相关现行契约：[`fluid-solver.md`](../../architecture/fluid-solver.md)、[`world-ownership-and-containers.md`](../../architecture/world-ownership-and-containers.md)、[`region-state-v1.md`](../../architecture/region-state-v1.md)、[`chemistry-state.md`](../../architecture/chemistry-state.md)、[`granular-state.md`](../../architecture/granular-state.md)、[`routing.md`](../../maintenance/routing.md)。

## 0. 先给结论

M9 应采用一套统一的**总内能状态函数**，而不是给 marker 增加一个可写温度，也不是在反应完成后凭经验“加几度”：

1. 液体 marker、M8 active parcel/deposit、新增的连续凝固相以及密闭气体节点分别保存**广延总内能 `U`**；温度、热容量、显热、化学热和潜热均从权威 registry 与库存派生。
2. 化学形成能和相态参考能属于同一状态函数。绝热反应或相变保持总 `U` 不变，组成/相态改变后由同一个 `U -> T` 反演自然产生升温或降温。反应热和潜热不是 external source。
3. 液体携带 marker `U` 对流；网格只建立瞬时局部热平衡节点并做有限体积导热。每个共享面只计算一次通量，向两侧施加等量反向能量增量。
4. 首版采用单格局部热平衡（LTE）闭合：同一 solver cell 内的液体、颗粒、沉积孔隙相和 M9 连续固相共用一个派生温度；气体节点保持独立均匀温度。该闭合简单、守恒，且有明确停止门，不引入未经验证的换热系数。
5. 熔化/凝固使用固定网格焓法。凝固生成的是新的 M9 **连续凝固相 cell store**，不是 M8 颗粒/沉积，也不是 Minecraft 方块；它以无孔隙静止固相占据量进入既有 cut-cell/压力耦合。自由漂浮刚体冰块不在 M9 v1 范围内。
6. 沸腾/冷凝必须等待独立的 gas `region-state v4` 评审。M9 首个增量只做 transient CPU 热状态；生产持久化最终使用完整联合 `region-state v6`，绝不把热字段写入 M7 v3、气体 v4 或 M8 v5。
7. 新跨层版本建议冻结为：server config v6、thermal registry store v1、`region-state v6`、protocol v5、`ThermalContents v1`、container-transfer WAL v5。旧 reader/recovery 全部保留。
8. M9 完成条件不是“温度看起来在变化”，而是：固定体积绝热闭系统的内部能量闭合；有机械功时 `U + source/sink + work` 闭合；相变前后质量、组分和各自体积账本闭合；持久化/容器/协议身份不明一律保留并 LOCKED。

这套切法的关键收益是：M7 反应顺序、M8 颗粒模型、M5 气体网络都可以复用，但没有任何旧格式被偷偷重新解释。

## 1. 已核对的当前实现事实

设计必须以当前代码而不是理想化架构为准：

- `FluidMaterial` 目前只有参考密度、互溶组、表面张力和接触角；其注释明确说明后加字段不是现有 wire/save 格式。M9 热物性不能直接追加后假装旧 region-state 已保存。
- `FlipMaterialParticleState` 对每个 marker 保存独立 raw `double` 质量；`FlipChemicalParticleState` 再正交保存 species quanta、组成、indicator 与 residual。M9 应继续使用正交 handoff，不扩写 `FluidMixture v1`。
- `FlipFluidDomain.step()` 当前顺序是：机械 substep（含 gas、pressure、advection）→ M7 dirty-cell reaction → M8 precipitation/dissolution → granular compaction/deposit dynamics → marker reseed。热提交顺序必须显式重排或插入，不能藏在某个现有调用内部。
- `ChemicalParcelStore.reactDirtyCells()` 已经是全局 plan/validate 后一次 commit，但 `ChemicalReactionEngine.InventoryResult` 只暴露最终 inventory 和 evaluation 数，没有逐反应 extent。采用状态函数后不需要靠 extent 计算热量；仍需把 plan 和 commit API 分开，才能把 species、marker mass 和 `U` 做联合预检。
- `GranularPhaseTransferEngine` 已有 exact extent/SVQ batch 与 per-cell plan；当前多个 cell/rule 可以顺序提交。M9 只要求每个既有 phase-transfer commit 同时提交对应 `U`，不得出现 solid/species 已变而能量未变。
- `AirNode` 只保存标准体积 nanos 与派生 available volume；`AirNetwork` 是 runtime-only 等温理想气体近似。非等温气体需要节点组分与 `U`，并需要独立 v4 评审后才能进入持久控制器。
- `FluidRegionLifecycle` 目前只有一条 hydrostatic sleep 轴；`HYDROSTATIC_SLEEP` 会阻止整个 solver step。M9 必须把运动休眠和热休眠拆成正交状态，否则静止但仍有热通量的区域会永久冻结。
- 当前完整联合存档是 v5，v4 明确保留；protocol 的 feature 代次为 material v2、chemical v3、granular v4；container WAL 当前 v4 并区分 `FLUID_NANOS`/`GRANULAR_SVQ`。

## 2. 范围与明确非目标

### 2.1 M9 必须交付

- 内能、热容量、温度派生、导热和随物质对流；
- 液体、M8 颗粒/沉积、M9 连续固相、气体节点之间的统一能量账本；
- 固定温度外部边界与默认绝热边界；
- M7 反应热和 M8 固液反应热；
- 熔化/凝固、沸腾/冷凝的显式质量/组分/体积/能量转移；
- 气体压缩/膨胀温变、气体边传质携能；
- 独立 thermal sleep、唤醒和预算；
- world registry、region-state、容器、WAL、protocol、诊断与迁移边界。

### 2.2 M9 v1 明确不做

- 不做可压缩液体总能量 Navier–Stokes；液体和普通固体仍使用当前定密度/独立质量体积模型。
- 不做温度依赖黏度、表面张力、接触角或密度反馈；也不先加 Boussinesq 经验补丁。需要自然对流时另立量化增量。
- 不做辐射换热、燃烧、火焰、气相化学或局部气体 CFD。
- 不把凝固体升级成自由运动刚体；首版连续固相是固定网格静止基体。需要漂浮冰块时另评审 rigid-body/fragment 模型。
- 不做 MPM，不改变 M8 的低成本 active parcel + deposit 生产模型。
- 不让客户端温度颜色、tooltip、耐久条参与结算。
- 不自动按 biome、方块颜色、当前数据包或显示温度猜测旧世界能量。

## 3. 单位、符号与数值表示

### 3.1 世界单位

新增 world thermal registry 必须保存 `unitSystemId = realwater:si_block_v1`，默认映射为：

- `1 BV = 1 m³`
- `1 MU = 1 kg`
- `1 PU = 1 Pa`
- `1 TU = 1 K`
- `1 EU = 1 J`
- solver 时间单位为秒

AU 仍是 M7 的游戏化 amount unit，不宣称等于 mol；化学形成能用 `EU/AU` 表示。以后若换单位，只能新 registry/version 显式迁移，不能改同一存档的比例。

### 3.2 每类权威真值

| 对象 | 新增权威真值 | 仍为派生/数值状态 |
|---|---|---|
| 液体 marker | `particleInternalEnergy[ordinal]` raw finite `double` EU | 温度、cell 热容、cell 导热率 |
| M8 active parcel | parcel 总 `U` | parcel 温度、所处 cell 温度 |
| M8 deposit group | group 总 `U` | deposit/LTE 温度、有效导热率 |
| M9 连续固相 | stable phase id、mass、exact occupied SVQ、optional composition、总 `U` | 温度、cell solid fraction、压力 coupling field |
| sealed gas node | 每组分 standard-volume nanos、独立 mass、optional species quanta、总 `U` | `T`、`p_abs`、`p_gauge`、available volume |
| 外部交换 | source、sink、mechanical-work ledger | UI 统计 |

`U` 可为负，因为热力学参考零点任意；必须 finite。液/固/气质量、液体体积、solid SVQ、gas standard nanos、species quanta 与 `U` 始终是互相独立的真值，registry 只提供状态方程与创建/校验关系。尤其禁止由理想气体 EoS 反算 gas mass 后覆盖质量账本。

### 3.3 浮点闭合策略

逐 parcel 使用 raw `double`，与现有质量路径一致；但累计能量跨大量导热面后不能仅凭普通求和声称“精确”。建议增加：

- `SignedDyadic`：把任意 finite IEEE-754 double 精确转成 `BigInteger significand × 2^exponent`；仅用于逻辑步审计、source/sink、checkpoint，不进入每个 face 的热点循环。
- `ThermalRoundoffResidual`：每个逻辑步对比变更前后的 exact-dyadic 总和，把纯浮点舍入差单独累计。它不是 source/sink，也不能用于掩盖物理漏账。
- hard gate：单步 residual 大于 `max(32 ULP of inventory sum, configured absolute audit floor)`，或累计 residual 超过同尺度预算时 PAUSED。阈值按表示误差定义，不按“看起来差不多”定义。

所有持久 `U` 写 raw bits；manifest 同时保存按 canonical 顺序重算的 total energy ledger。解码时必须逐 section、逐 carrier、全局三层复核。

## 4. World-authoritative thermal registry

### 4.1 为什么必须独立 registry

M3 `FluidMaterial`、M7 chemical registry、M8 granular registry 均已冻结，且它们各自有不同的 world snapshot/fingerprint 语义。M9 应新增 `ThermalRegistry`/`ThermalRegistryStore v1`，以稳定 ID 引用旧 registry，而不是给旧定义加字段后用当前数据包重解释旧世界。

首次创建世界 snapshot 时读取 data pack；以后世界 snapshot 权威。当前 data pack 只做差异诊断。未知 ID、缺定义、重复定义、依赖 fingerprint 不匹配、非法热容或非单调状态函数都必须保留文件并 LOCKED。

### 4.2 建议的数据层

建议目录：

```text
data/*/realwater/thermal_materials/*.json
data/*/realwater/thermal_species/*.json
data/*/realwater/thermal_gases/*.json
data/*/realwater/phase_transitions/*.json
data/*/realwater/thermal_boundaries/*.json
```

核心定义：

```text
ThermalMaterialDefinition
  id
  carrierKind = LIQUID | GRANULAR | COHERENT_SOLID
  carrierId                    // FluidMaterial / ParticulateMaterial / M9 phase id
  referenceTemperatureTu
  referenceSpecificEnergyEuPerMu
  caloricSegments[]            // [minT,maxT,specificCv], 连续、正斜率
  conductivityEuPerSecondMeterTu
  validityMinTu, validityMaxTu

ThermalSpeciesOffset
  chemicalSpeciesId
  hostThermalMaterialId
  referenceInternalEnergyEuPerAu

ThermalGasComponent
  id
  optionalChemicalSpeciesId
  referenceTemperatureTu
  referenceMassMuPerStandardBv       // 只用于创建/校验，不回填库存
  cvEuPerStandardBvTu
  referenceEnergyEuPerStandardBv
  conductivity...

PhaseTransitionDefinition
  id
  lowerPhaseThermalId
  upperPhaseThermalId
  equilibriumModel
  exactTransferBatch
  interfaceRequirement
  validityPressureRange

ThermalBoundaryDefinition
  block/tag id
  kind = ADIABATIC | FIXED_SURFACE_TEMPERATURE
  surfaceTemperatureTu          // 仅 fixed reservoir
```

`caloricSegments` 首版可只有一个常数 `c_v` 段，但格式直接允许分段常数热容。积分得到连续分段线性的 `u(T)`，反演只需二分定位段并做一次线性求解，无需完整 IAPWS/Cantera runtime。

### 4.3 Registry 必须验证的约束

- 每个活动 liquid material、granular material、M9 phase、gas component 恰有一个 thermal model；不允许 fallback。
- `T > 0`，每段 `c_v > 0`，温区连续、无重叠、`u(T)` 严格单调。
- conductivity finite 且 `>= 0`；零表示绝热材料，不表示缺数据。
- chemical/granular dependency fingerprint 与权威 world snapshot 一致。
- species offset 对所有活动 chemical species/host 组合完整；缺失不能按零猜。
- phase transition 两侧的参考能差必须和声明的 latent internal energy/batch 完全一致；不允许状态函数与 phase rule 各有一套潜热。
- 同一个 `(carrier, pressure range)` 的相变温区不能冲突；循环相变图必须拒绝。
- exact transfer batch 的 species、mass、source volume、target volume、gas nanos 均须正、可表示并在双向转移时可逆。
- built-in 水数据应从冻结版本的权威资料离线生成，并把来源版本写进 definition metadata；运行时不联网。水/蒸汽广域状态可参考 [IAPWS-95](https://iapws.org/relguide/IAPWS95-2018.pdf)，常压液态简化可参考 [IAPWS SR6-08](https://iapws.org/documents/release/LiquidWater)，饱和线可参考 [IAPWS SR1-86](https://www.iapws.org/relguide/Supp-sat.html)。首版不要手写一组无有效温区的常数。

## 5. 统一热力学状态函数

### 5.1 凝聚相

对一个 parcel/group `a`：

```text
B_a = m_a * u_ref(carrier)
    + Σ_s amountAu(s) * speciesOffset(s, host)

U_model,a(T) = B_a + m_a * ∫[T_ref..T] c_v,carrier(τ) dτ
C_a(T) = dU_model,a/dT = m_a * c_v,carrier(T)
```

其中 marker 的 `m_a` 始终使用现有独立质量真值，绝不从 species 或体积倒算。M7 residual 继续只是质量闭合真值；首版热容按 carrier 总质量计算，不按溶质浓度经验修正。

同一 cell 的 LTE 节点把所有成员的 `B`、分段热容和 `U` 相加，求唯一 `T`。如果 `U` 不在所有活动模型的共同 validity range 内，停止并 PAUSED，禁止外推或钳制温度。

热力学参考零点本来就是约定；可观测的是状态差。将 reference state 与 world fingerprint 一同冻结，可避免不同数据来源混用。Cantera 也明确区分 species/phase 状态模型，并说明参考焓/内能零点任意；本文只实现项目需要的常热容/分段热容子集，参见 [Cantera thermodynamic model 文档](https://cantera.org/stable/reference/thermo/index.html) 与 [species heat-capacity integration](https://www.cantera.org/3.1/reference/thermo/species-thermo.html)。

### 5.2 反应热为何不另加 source

M7 反应前后总 `U` 不变，但 species inventory 改变，因此 `Σ amount × speciesOffset` 改变；`U -> T` 反演会把同等能量转为或吸收自显热。这就是反应热。

因此：

- 不在 `ChemicalReaction` 追加 `heatReleased` 字段；
- 不按 reaction extent 向 marker 额外写 `ΔU`；
- 不把 neutralization heat 记 external source；
- 可把 `B_after - B_before` 作为只读 reaction-heat diagnostic；
- 数据路径天然无关：只要最终组成相同，最终参考能相同，不会因 reaction order 重复放热。

M7 反应已经规定 `ΔV = 0`，所以常压反应焓与内部能差之间没有本项目可表示的 `pΔV` 修正。将来若允许非零反应体积，必须同时进入 phase/work plan，不能继续沿用此简化。

### 5.3 潜热

相态参考能差编码潜热。对固定熔点的两相材料：

```text
U_solid(Tm) = sensible solid energy at Tm
U_liquid(Tm) = U_solid(Tm) + m * L_internal
```

当 cell 总 `U` 位于这两个边界之间时，`T = Tm`，多余能量决定相分数。多个 transition 恰好同温时，按各自尚可转换的 latent capacity 比例分配，并用 stable transition id 处理整数余数；不得用遍历先后令某材料独占潜热。

固定网格焓法是成熟的相变离散，而不是项目特异补丁；其焓方程、apparent-capacity/source-based 变体和有限体积离散可参考 Voller、Swaminathan、Thomas 的综述：[Fixed grid techniques for phase change problems](https://onlinelibrary.wiley.com/doi/10.1002/nme.1620300419)。

## 6. 热输运

### 6.1 热节点重建

新增 `ThermalCellGrid`，每个逻辑热步从权威 parcel 重建；它不是第二套库存。每个非空 cell 聚合：

- liquid markers；
- cell 内 M8 active parcels；
- M8 deposit groups（含孔隙液体）；
- M9 coherent-solid groups；
- 几何/物相 derived fraction；
- `B_cell`、`U_cell`、piecewise heat capacity、effective conductivity、derived `T_cell`。

首版采用 LTE：上述凝聚物共用一个 cell 温度。deposit 与孔隙液体不再引入经验 `hA`；active parcel 也在其所在 cell 内平衡。LTE 是常见的多相/多孔介质闭合，但并非所有尺度都成立；其适用性依赖相间温差、流速和尺度，参见 [Quintard & Whitaker 的 transient LTE 约束研究](https://www.sciencedirect.com/science/article/pii/0017931095000288/pdf)。若代表性 active parcel 出现显著 Biot/停留时间问题，必须先量化再评审 LTNE 双温模型，不能在 M9 v1 偷加 Ranz–Marshall 常数。

cell effective conductivity 首版使用体积分数加权的平行均质闭合：

```text
k_cell = Σ phaseVolumeFraction_a * k_a
```

跨 face 使用串联热阻：

```text
G_ij = A_contact / (d_i / k_i + d_j / k_j)
Q_ij = G_ij * (T_j - T_i) * dt_thermal
```

等距 cell 即 harmonic face conductivity。`A_contact` 只来自数值几何：cut-cell aperture、granular/M9 solid occupancy 和两侧 PLIC liquid polygon 在共享 face 上的交集；禁止用 marker count、渲染 mesh 或 `min(cell volume fraction)` 经验替代。应给 `FlipFreeSurfaceGeometry` 增加只读 face polygon/contact-area 查询，而不是复用客户端 mesher。

### 6.2 显式有限体积导热

首版建议显式、守恒、独立 thermal subcycle：

```text
dt_limit,i = C_i / Σ_faces G_if
thermalSubsteps = ceil(logicalDt / min_i(dt_limit,i))
```

每个 substep 按 canonical `x -> y -> z` face 顺序计划所有 `Q`，同一 face 只拥有一次；对一侧 `+Q`、另一侧 `-Q`。固定温度 reservoir face 的反向量进入 external source/sink。显式扩散项的标准有限体积形式可对照 NIST FiPy 的 [ExplicitDiffusionTerm](https://pages.nist.gov/fipy/en/latest/generated/fipy.terms.explicitDiffusionTerm.html)；若 substep 数超过硬预算，PAUSED，不用钳制通量或改 40/200 ms 调度阈值。

每个 substep 完成后把 node `U` 按状态函数重新分配回成员，使所有成员得到同一 `T`；最后一个 stable ordinal 吸收可表示余数，exact-dyadic 差进入 `ThermalRoundoffResidual`。

### 6.3 对流、split、merge 和 reseed

- 液体对流：marker 直接携带 `U`，网格不另做能量 advection。
- marker split：先由 parent `U` 派生 `T`，按每个 child 的 `B_child + C_child(T-Tref)` 分配；最后 child 接收 raw remainder。
- marker merge：质量、体积、species、indicator 按既有规则合并，`U_new = ΣU_old`；随后派生新温度。
- material/chemical transfer：按同温状态函数计算被转移部分 `U`，而不是简单按 raw `U` 百分比切分形成能。
- granular active/deposit split/merge/sleep/wake：每次既有库存操作必须有同事务 thermal hook；不允许 orphan energy。

## 7. 固体边界与固液换热

### 7.1 Minecraft/static wall

默认所有未声明方块边界为 `ADIABATIC`。这是闭系统的安全默认，也不会凭空假定世界方块有无限热容。

显式标为 `FIXED_SURFACE_TEMPERATURE` 的 block/tag 是外部无限 reservoir：

- face surface temperature 从 world thermal registry 派生；
- 只用 wall face 与 cell center 之间的半格热阻；
- 进入区域的能量记 source，离开区域的能量记 sink；
- 方块本身不保存 `U`，所以不得声称其温度会随换热变化。

有限热容量 Minecraft 方块需要 world block thermal-state ownership，属于新持久域，不在 M9 v1。

### 7.2 M8 颗粒/沉积

M8 的 SVQ、mass、composition、porosity 不变。M9 只增加正交能量状态：

- active parcel 与所在 cell LTE；
- deposit skeleton、pore liquid 与同 cell LTE；
- dry deposit 仍可和相邻 thermal node 导热；
- deposit dynamics 移动多少 solid truth，就按相同状态函数搬运相应 `U`；
- `GranularStateSnapshot`/v5 codec 不新增字段，v6 的 `ThermalStateSnapshot` 按 stable active ordinal 和 canonical deposit key 保存能量。

运行时可把 `U` 与 parcel/group 共置以减少索引错误，但 capture/persistence 必须走独立 M9 snapshot；代码注释要明确“runtime co-location is not v5 format”。

## 8. 熔化与凝固

### 8.1 新的连续固相 store

新增 `CoherentPhaseStore`，每个 cell 可有少量 canonical group：

```text
cellIndex
phaseStableId
occupiedVolumeSvq
massBits
optional stable chemical composition + residual
internalEnergyBits
```

它表示固定网格中的连续/枝晶固相：无孔隙、速度为零，不是 M8 granular。`CoherentPhaseCouplingFields` 与 `GranularCouplingFields`、cut-cell 组合成统一 `OccupiedVolumeField`，供 free surface、pressure open volume、gas available volume、collision 与 volume drift 使用。不要复制另一套 pressure 几何。

凝固体在 cell 内可为部分体积；现有液体仍在剩余 open volume 内运动。这个模型适合容器内凝固/熔化，不代表自由刚体漂浮。

### 8.2 exact transfer batch

每个 transition 声明一个可双向反演的 batch：

```text
extentPerBatch
sourceLiquidVolumeSvqPerBatch
targetSolidVolumeSvqPerBatch
massPerBatch (canonical rational -> checked double)
speciesStoichiometryPerExtent
compositionResidualMassPerBatch
latentInternalEnergyPerBatch (由两相状态函数复核，非第二真值)
```

复用 `SolidVolume.SVQ_PER_BV` 只作为体积量子单位，不把 coherent solid 写进 M8 store。对液体 marker 继续使用 `SolidVolume.addCellVolumeSvqExact` 风格的“只应用可精确表示 delta”原语；不可表示时 plan 失败，不先改 marker。

相变 planner：

1. 只扫描 thermal dirty cells，按 cell、transition stable id、marker ordinal 排序。
2. 从 cell 总 `U` 和组成求 equilibrium target phase fraction。
3. 向目标取不超过可用质量/组分、几何容量、marker/group budget 的完整 batches。
4. 同时计划 liquid marker volume/mass/species/residual/`U` 更新、coherent group 更新、occupied-volume delta 和 mechanical work。
5. 所有候选都能表示后才 commit；任一失败保持当前 plan 未提交并 PAUSED。

含溶质时由 rule 明确哪些 species 进入固相、哪些留在液相；不得默认把整份 mixture 冻结。built-in water/ice 可只转移 H2O carrier，盐离子留在液相，composition residual 按 exact batch 转移。若剩余 marker 会变成空 composition 或负质量，plan 拒绝。

### 8.3 体积变化与压力

`ΔV = V_target - V_source` 是独立显式 volume event：

- 更新 coherent occupancy 后进入下一 mechanical projection 的 moving-solid/volume target；
- 同步改变 gas available volume；
- 按界面压力记录 `-p ΔV` mechanical-work transfer；
- 若正体积膨胀在闭域中没有可表示空间，当前不可压缩模型不能物理解压，必须 PAUSED 并报告 validity exceeded，不能静默丢体积或钳制 phase fraction。

首版使用一阶 operator split：相变在逻辑步末提交，新的 occupancy 和 pending `ΔV/dt` 在下一 mechanical step 消费；pending volume-work record 是 v6 持久真值，消费后清零。这样崩溃恢复不会重复或丢失相变排水。

## 9. 非等温气体、压缩/膨胀与相间换热

### 9.1 必须先完成的独立 v4 评审

在任何生产 region 保存 gas `U` 之前，先单独产出 `docs/architecture/gas-state-v4.md` 并实现/验证 **无热字段** 的 gas `region-state v4`：

- 持久 sealed node identity/topology；
- 每 gas component 的 standard-volume nanos；
- 每 component 的独立 raw mass、optional chemical species quanta 及各自 source/sink ledger；
- ambient source、vented sink 与 topology ledger；
- available volume 仍由 owned geometry 重建并复核；
- v4 不含 granular，不含 thermal，不占用 M9 字段；
- v1/v2/v3 可经显式 gas bootstrap 进入 v4；v5 不倒退迁移到 v4。

随后 v6 复用已经评审的 gas schema，并联合 v5 granular + M9 thermal。这个顺序满足“先单独评审 v4”，又不把热塞进 v4。

### 9.2 气体热状态

sealed node 保存 component nanos、component mass、optional species quanta 与总 `U`。四套库存分别闭合；每个 component 的 standard volume 定义在 `(P0,T0)`，所以：

```text
Vstd_total = Σ Vstd_i
C_v,node = Σ Vstd_i * cvStd_i
T_node = inverse(U_node, composition)
p_abs = P0 * (Vstd_total / V_available) * (T_node / T0)
p_gauge = p_abs - P0
```

这是 M5 等温式的严格非等温扩展。M9 thermal gate 下不再把超压/低压事件钳到 `MAX_GAUGE_PRESSURE`/`MIN_GAUGE_PRESSURE` 后继续；触及 validity range 必须 PAUSED，因为 pressure clipping 会破坏功和能量闭合。

气体 topology：

- merge：component nanos、mass、species、`U` 分别求和，再派生 `T`；
- split：每个 component 按 child available volume 做 largest-remainder exact nanos/species 分配，mass 按同一密度份额分配；各 child 以 parent `T` 计算 `U`，最后 child 接收 mass/energy 余数；
- vent：component nanos、mass、species 和携带能量分别进入 sink；
- ambient inflow：按 world snapshot 的环境组成与 `T_ambient` 创建 nanos/mass/species/`U`，分别进入 source。

### 9.3 压缩功

每次 `updateAvailableVolume` 前保存 `V1`，重建后得到 `V2`。对无传质、绝热理想气体子步，使用解析 reversible update，而不是经验温升：

```text
R_node = P0 * Vstd_total / T0
gammaMinusOne = R_node / C_v,node
T2 = T1 * (V1 / V2) ^ gammaMinusOne
ΔU = C_v,node * (T2 - T1) = W_into_gas
```

第一定律的符号约定为 `dU = δQ - p dV`，可对照 [NASA 的 first-law 说明](https://www1.grc.nasa.gov/beginners-guide-to-aeronautics/first-law-internal-energy/)；理想气体混合物的 `pV=nRT`、`u(T)`/`h(T)`关系可参考 [NASA/TP-20230015593](https://ntrs.nasa.gov/api/citations/20230015593/downloads/main_revised_TP_number.pdf)。

该 `ΔU` 同时进入 signed `mechanicalWorkIntoThermal` ledger。当前 FLIP 压力解没有完整 kinetic-energy truth，因此 M9 只能严格声称：

```text
U_now + externalOut = U_initial + externalIn + mechanicalWorkIntoThermal + roundoffResidual
```

固定几何绝热闭系统中 mechanical work 为零，总 `U` 闭合；规定 piston/gas fixture 中按 work ledger 闭合。不得宣称当前不可压缩 FLIP 已实现“内能+动能+势能”的全总能量守恒。

### 9.4 气体边流和换热

orifice transfer 同时移动 exact component nanos、独立 mass、optional species，并按 source state 携带**焓** `h=u+p v`；source 减同量、target 加同量，节点总能量守恒。ambient 边则进入 source/sink。不得只搬 standard volume 后让 target 重新按自身温度生成质量、组分或能量。

gas node 与液面/solid wall 的换热作为 `ThermalGraph` 中的 lumped-node edge；接触面积来自 PLIC liquid-gas interface/cut-cell 几何。gas node 在一步内均匀温度是 M5 lumped-network 的同一尺度假设。

## 10. 沸腾与冷凝

沸腾/冷凝只在 non-isothermal gas、v4 prerequisite 和 v6 联合状态完成后进入生产。

### 10.1 平衡判据

- 只在已解析的 liquid-gas interface 上发生；不在无接触的 bulk cell 猜气泡或液滴。
- vapor partial pressure 从 component standard nanos 按 Dalton 关系派生。
- water built-in 使用冻结版本的 IAPWS saturation correlation/table；不要手写固定 100°C 条件，也不要用当前 gauge pressure 的特异 if/else。
- 通用 data pack transition 可提供有有效温区的 monotone `p_sat(T)` 分段表；loader 验证单调和两端范围。

planner 联立能量、vapor partial pressure、liquid/gas mass/species、gas nanos、available volume 求 equilibrium extent；使用有界二分，不按 tick 经验蒸发百分比。transfer batch 显式移动：

- liquid volume/mass/species/residual/`U`；
- gas component standard nanos/mass/species/`U`；
- latent internal energy（由状态函数自然体现）；
- `ΔV` 和 mechanical work。

冷凝在现有液面 marker 上合并；若无可接受 receiver，但存在 solid wall interface，可在 collision-valid 点创建 marker。没有界面或 marker budget 时 plan 不提交。

## 11. 反应、沉淀、相变的提交顺序

建议冻结一个 logical step 的生产顺序：

1. **起始审计**：capture raw ledgers、pending phase volume/work、thermal dirty set。
2. **mechanical substeps**：rebuild P2G/geometry → adiabatic gas volume-work update → gas edge mass/enthalpy transfer → granular preview → gravity/surface tension → pressure/projection → G2P/advection → granular collision commit。marker/parcel `U` 只随载体移动。
3. **thermal rebuild**：按新位置建立 condensed cell nodes、gas/interface edges。
4. **conduction/exchange plan+commit**：执行独立 thermal subcycles；内部 face pairwise 守恒，reservoir 写 source/sink。
5. **M7 reaction joint plan+commit**：先得到 species/mass replacements；保持 group 总 `U`，按新状态函数重分配 marker `U`；全部可表示后同时 commit。
6. **M8 solid-liquid reaction joint commit**：每个既有 precipitation/dissolution plan 同时计划 liquid/granular `U`、formation/host offset 和体积功；当前 cell plan 原子提交，已提交的早先 cell 保持现有语义。
7. **M9 equilibrium phase plan+commit**：先 melt/freeze，再 boil/condense；同 cell 多 transition 按 latent capacity 比例处理。
8. **granular deposit dynamics/compaction**：任何 parcel↔deposit、split/merge 同时搬运 `U`。
9. **marker reseed**：material/phase/chemistry/indicator/volume/mass/`U` 联合 split/merge。
10. **最终 rebuild/audit**：重算 temperature、global energy identity、dirty/flux stats，再判断 motion sleep 与 thermal sleep。

这个顺序规定反应热先影响本步相变；相变产生的 volume delta 在下一 mechanical step 通过持久 pending record 排水。不得把 phase change 放进 pressure 循环中一半提交。

为支持第 5 步，应把 `ChemicalParcelStore.reactDirtyCells()` 拆成：

```text
ReactionPlan planDirtyCells(...)
void commit(ReactionPlan, markerMass, particleInternalEnergy)
```

非 thermal gate 仍可由原 facade 调用 plan+commit，保持 M7 行为与最近门。

## 12. Source、sink、work 与管理命令

新增 `ThermalAuditLedger`，以 canonical `SignedDyadic` 累计：

```text
initialization / bootstrap
container import
container export
creative/admin source
empty escaped sink
fixed-temperature boundary
ambient gas inflow/outflow
mechanical work into thermal subsystem
roundoff residual                 // 独立字段，不归 source/sink
```

source/sink 表示跨 ownership 边界的方向；携带的 `U` 可因参考零点为负，不能强制数值正。heat-only reservoir 流量则按 `ΔU` 正负路由。

命令语义：

- `demo` 仍替换当前开发域；增加 thermal fixture 参数，不创建第二开发域。
- `empty` 先拒绝 pending container WAL，再联合预检 liquid/chemical/granular/coherent/gas/thermal clear；被删物质的 `U` 进入 escaped sink，pending phase/work 清零并写完整 checkpoint。
- 新增只读 `/realwater thermalstatus`：registry fingerprint、min/max T、total U、source/sink/work/residual、max face flux、thermal dirty/sleep、phase/gas validity counters。
- 新增显式 `/realwater thermal initialize <temperature-tu>`，见迁移章节；不能自动执行。

## 13. 运动休眠与热休眠分离

不要把所有组合塞进新的大 enum。保留 `FluidRegionLifecycle.State` 表示 motion scheduling，新增正交 `ThermalActivity`：

```text
ACTIVE
REDUCED
THERMAL_SLEEP
```

服务层分别询问：

```text
canRunMechanicalStep()
canRunThermalStep()
```

`HYDROSTATIC_SLEEP + ACTIVE thermal` 必须继续跑导热/反应/相变；thermal step 产生 phase volume、gas pressure、实体边界或将来 buoyancy 变化时唤醒 motion。只有 motion 和 thermal 都 sleep 才完全跳过 solver。

thermal sleep candidate 必须同时满足连续 quiet steps：

- 所有 internal/reservoir face 的计划 `|Q|` 不超过表示误差阈值；
- 无 dirty chemistry、precipitation、phase equilibrium 或 pending phase volume/work；
- 无 gas edge transfer、topology change、ambient exchange或 pressure work；
- 没有仍可提交的非平衡 phase extent；零通量下已达到平衡的 partial latent plateau 可以休眠，库存与相分数原样保存；
- energy audit residual 正常。

唤醒事件：block/collision/thermal-boundary change、container transfer、admin source、chemical dirty、granular local wake、gas topology/flow、邻面出现可表示热通量、phase condition 变化。客户端观察永不唤醒。

quiet step 数和 flux tolerance 是调度/表示阈值，不是材料经验参数；应放在 `ThermalLimits` 并由 thermal validation 固定。不可因为 CPU profile 未知而修改现有 40/200 ms wall-time 阈值。

## 14. 持久化与迁移

### 14.1 版本矩阵

| 层 | M9 版本 | 规则 |
|---|---:|---|
| world server config | v6 | 增加单一 `thermalEnabled` 及硬预算；默认 false，requires materials |
| thermal registry store | v1 | world-authoritative definitions + dependency fingerprints |
| gas prerequisite | region-state v4 | 单独评审；只含 gas base state，无 thermal/granular |
| complete region | region-state v6 | material + optional chemistry + optional granular + reviewed gas + required thermal |
| network | protocol v5 | display-only temperature/phase/gas thermal snapshot |
| item | `ThermalContents v1` | 与底层 contents hash 绑定的总 `U` |
| transfer WAL | v5 | primary fluid nanos/solid SVQ + coupled energy + thermal identity |

`region-state v6` 不是“v5 加可选未知 extension”：thermal gate 下 thermal manifest/section extension 必须完整存在。建议：

```text
manifest thermal extension
  thermalRegistryFingerprint
  dependency fingerprints
  capabilities
  temperature unit / EU identity
  liquid/active/deposit/coherent/gas counts
  per-carrier + gas nanos/mass/species + global energy ledgers
  source/sink/work/roundoff
  pending phase volume/work
  initialization record

section thermal extension
  liquid marker energy bits aligned with section marker ordinal
  granular active energy aligned with global stable ordinal
  deposit group energy aligned with canonical group key
  coherent phase groups
  gas node/component nanos/mass/species/energy owned by canonical section/node identity
```

缺一项、重复项、ordinal 不完整、raw bits 非 finite、ledger 不闭、fingerprint 不符、v6 capabilities 与 payload 不符：保留 slot/HEAD/WAL，区域 LOCKED。不能按当前 registry 重建 `U`。

### 14.2 旧世界初始化不是自动迁移

v1/v2/v3/v5 没有热真值，不能从“它看起来是水”猜 `U`。唯一允许路径是 operator-authorized initialization：

1. 区域必须 PAUSED、ownership 完整、无 pending container WAL、HEAD/sections 全验证。
2. operator 明确给出初始 `T`；不得从 biome 或客户端颜色取值。
3. 从旧物质真值和权威 thermal registry 计算初始 `U`。
4. 对 gas：按已评审的 M5 bootstrap 语义创建/捕获 topology、ambient composition 与 inventory；v5 不倒退写 v4。
5. 写 `InitializationRecord(sourceVersion, temperatureBits, gasBootstrapKind, operator/time metadata)` 与 initial ledger。
6. stage/verify v6 完整 generation，HEAD 最后发布；失败保留旧 HEAD。

有未决旧 v1–v4 container WAL 时拒绝初始化。v1–v4 reader/recovery 继续存在；若 WAL 跨越 thermal initialization generation 而缺能量，保留并 LOCKED 人工处置。

### 14.3 Unload/checkpoint

只有 v6 完整 payload、registry identity、source/sink/work 和所有 inventory ledger 都验证后才能发布 HEAD 或进入 `UNLOADED`。thermal ACTIVE 不妨碍 checkpoint，但 pending cell plan 不得跨 checkpoint；只有已提交的 `pendingPhaseVolumeWork` 可持久。

## 15. 容器与 WAL

### 15.1 `ThermalContents v1`

容器内部首版视为 well-mixed/LTE，只需一个总 `U`：

```text
version
thermalRegistryFingerprint
underlyingContentsKind
underlyingCanonicalContentsSha256
internalEnergyBits
```

底层 hash 覆盖 pure/material/chemical/granular contents 的全部 canonical bytes。hash、kind、registry、质量/体积/组分任一不符即拒绝；tooltip 只能显示派生温度。

部分装取按目标内容在 source 温度下的状态函数计算 `U_piece`，donor 用 subtraction remainder 保守。单次 item count 继续必须为 1。

玩家/发射器仍只按实际命中的 target region 路由，能量必须和同一批实际物质进入/离开同一区域；不得因温度查询扩大搜索到邻近 controller。`FluidMixture v1`、`ChemicalContents v1`、`GranularContents v1` 均保持原字节，只新增正交 `ThermalContents v1`。

vanilla water bucket 可以附加自定义 data component：

- 从 thermal region 装取时总是写 `ThermalContents`；
- 无 component 的原版水桶放入 thermal region 时，是定义明确的 ambient-temperature external source，并记账；
- 数据被第三方剥离的自定义/混合容器仍不得按外观猜内容，留待 M10 API。

### 15.2 container-transfer WAL v5

保留 v4 primary `ResourceKind.FLUID_NANOS/GRANULAR_SVQ`，再新增 coupled：

```text
transferredInternalEnergyBits
thermalRegistryFingerprint
before/after ThermalContents fingerprint
target v6 manifest checksum/generation
```

一次 transfer 的物质和能量是同一 WAL transaction，不新增可单独成功的“热 resource”。顺序不变：PREPARED → solver 联合修改 → stage/verify v6 → REGION_COMMITTED → HEAD → actor after durable save → delete WAL。

旧 v1/v2/v3/v4 reader/recovery 保留，包括 v1 material WAL 缺独立质量时的既有人工处置语义；旧 WAL 不包含能量，只有在 thermal initialization 之前完成恢复才可自动处理。跨过初始化边界一律保留 LOCKED。

创造放出仍是 external material + energy source；创造装取不制造隐藏 sink。普通 `empty` 把能量写 escaped sink。

## 16. Protocol v5 与客户端

新增严格 protocol v5/capability `CAP_THERMAL_SNAPSHOT`；thermal server 只接受 v5，不伪装降级。payload 只包含展示量：

- region id/generation/sequence 与 thermal registry fingerprint；
- 每 cell quantized temperature（建议 palette 范围内 16-bit，不发 raw `U`）；
- quantized coherent phase fraction；
- gas node quantized temperature/pressure display；
- 可选 thermal debug flags。

v5 可组合 material/chemical/granular 展示，但 exact energy、source/sink、species、SVQ 仍不发客户端。客户端 ledger 延续 generation/sequence/palette/registry 严格拒绝规则。

普通材质颜色不应在首个增量自动按温度改色；先提供 debug overlay。任何可见温度颜色、蒸汽粒子、冰面 mesh 都必须来自 accepted snapshot，不能反写 server。

## 17. 失败语义与硬预算

新增 `ThermalLimits` 至少冻结：

- max thermal registry definitions/segments/transitions/gas components；
- max coherent groups per cell/region；
- max thermal dirty cells/face evaluations/substeps/phase operations per logical step；
- max serialized thermal extension bytes；
- min/max valid absolute temperature；
- max dyadic significand bits；
- audit residual tolerance；
- thermal sleep quiet steps/representable flux floor。

以下情况全部是 fail-closed PAUSED，不做修复性 clamp：

- `U -> T` 无解、超 validity range 或 `C <= 0`；
- conductivity/face area/flux 非 finite；
- thermal substeps、phase operations 或 store capacity 超预算；
- phase expansion 无几何容量；
- gas pressure/temperature超模型范围；
- composition/host thermal offset 缺失；
- v6/WAL/item/registry identity 不匹配；
- energy ledger 不闭或 roundoff residual 超界。

运行时异常要区分：`thermal-definition LOCKED`、`thermal-transport PAUSED`、`phase-change PAUSED`、`gas-thermal PAUSED`、`thermal-persistence LOCKED`，并在 status 给出 exact region/generation/cell/transition。

## 18. 建议代码职责

新增包 `com.example.thermal`，避免把第二职责全部堆进 `FlipFluidDomain`：

| 类型 | 单一职责 |
|---|---|
| `ThermalMaterialDefinition` / `ThermalRegistry` | stable definition 与 runtime handle |
| `ThermalDefinitionCodec` / `ThermalDataPackLoader` | strict canonical data pack decode |
| `ThermalRegistryStore` | world snapshot/fingerprint/dependency identity |
| `ThermalStateFunction` | `U(T)`、`T(U)`、heat capacity、formation/phase offsets |
| `ThermalInventory` | liquid/solid/gas energy arrays与 split/merge hooks |
| `ThermalCellGrid` | 从权威 inventory 重建 LTE cell nodes |
| `ThermalContactGeometry` | PLIC/cut-cell/occupied-volume contact area，只读 |
| `ThermalTransport` | face graph、stability subcycle、pairwise flux plan |
| `ThermalReactionAdapter` | chemical/granular plan 与 energy state-function 联合预检 |
| `CoherentPhaseStore` / `PhaseChangeEngine` | M9 continuous solid 与 melt/freeze plan |
| `GasThermalState` / `GasEnergyTransport` | gas component、EoS、work、enthalpy flow |
| `ThermalAuditLedger` / `SignedDyadic` | source/sink/work/exact audit |
| `ThermalStateSnapshot` / `ThermalRegionStateCodec` | v6 handoff/typed extensions |
| `ThermalContents` | item v1 canonical thermal component |
| `ThermalActivity` | thermal sleep/dirty/wake，不管 motion state |

现有类的最小改动点：

- `FlipFluidDomain`：持有/调用上述对象，定义冻结 step order；不要内联 registry/codec。
- `FlipParticleReseeder`：新增 `U` split/merge 通道，与 material/phase/chem 同事务。
- `ChemicalParcelStore`：拆 plan/commit；非 thermal facade 保持。
- granular store/inventory：增加原子 thermal hook；v5 snapshot 不变。
- `AirNetwork`：保留 M5 API 的 isothermal degeneration；非等温逻辑委托 gas thermal state。
- `FluidRegionLifecycle`/service：motion 与 thermal 两轴调度。
- persistence/config/item/network/commands：各自新增独立 v6/v5/v1 入口。

实现每一增量后同步更新 [`routing.md`](../../maintenance/routing.md) 的职责、冻结格式和最近 selector。文件真正出现第二职责时再拆，不为“预备未来”建空框架。

## 19. 推荐实施增量

### M9.0：冻结契约与纯状态函数

- 把本文裁决提升为正式 `docs/architecture/thermal-state.md`；
- 建 thermal definitions/registry/loader 的纯 Java 类型；
- 完成 `U(T)`/`T(U)`、formation offset、phase plateau 与 `SignedDyadic`；
- 只用 transient CPU fixture，不接 config、world、wire/save。

停止门：状态函数非单调、reference/latent 双定义、无法对同一最终组成给出路径无关能量时，不进入 solver。

### M9.1：液体能量对流与导热

- `particleInternalEnergy[]`、handoff、insert/extract、reseed；
- `ThermalCellGrid`、PLIC contact area、显式 FV thermal subcycle；
- adiabatic/fixed-temperature wall；
- transient conduction fixture。

此时仍不开放生产 `thermalEnabled`，不写 region-state。

### M9.2：化学与 M8 固体热状态

- reaction plan/commit 分离；
- species/host formation offsets，neutralization/dissociation heat；
- active/deposit `U`、LTE、compaction/deposit dynamics hooks；
- precipitation/dissolution 联合 energy commit。

停止门：任何 existing M7/M8 fixture 在 thermal off 时不逐位退化，或 thermal on 时反应/沉淀需要额外 source 才闭合。

### M9.3：熔化/凝固

- coherent phase store/coupling field；
- exact phase batches、enthalpy equilibrium、pending volume/work；
- melt/freeze transient fixture；
- 明确连续固相静止模型的适用范围。

### 独立 G4 前置：gas region-state v4

- 单独设计、审阅、实现、验证 gas-only v4；
- 不带 thermal/granular；
- 完成后才能开始下一增量。不要把它混入 M9 首个增量或用 v6 文档替代评审。

### M9.4：非等温气体与沸腾/冷凝

- gas component/`U`、non-isothermal EoS；
- topology split/merge、enthalpy edge transfer、ambient ledger；
- analytic compression/expansion work；
- liquid-gas heat exchange与 saturation equilibrium phase transfer。

### M9.5：world registry 与 region-state v6

- thermal registry store v1；
- complete v6 manifest/section extension、integrity/discovery/HEAD；
- operator-authorized v1/v2/v3/v5 initialization；
- unload/reload/unknown/corrupt/fingerprint lock。

直到 v6 完整恢复门通过才把 config v6 `thermalEnabled` 接入 production controller。

### M9.6：容器、WAL、protocol 与诊断

- `ThermalContents v1`、item tooltip；
- container WAL v5 与 legacy recovery；
- protocol v5/debug overlay；
- `thermalstatus`、`empty`、demo、source/sink/work transcript。

### M9.7：调度、性能与里程碑关闭

- thermal dirty set、thermal sleep/唤醒；
- profile solver/world/readback 占比，但不先改 40/200 ms 阈值；
- 自动 milestone 门；
- 自动门看不到时，再给用户一个合并实机场景、单一命令、明确预期和日志证据。

每个增量形成一个小 commit；本提案本身不要求 commit。

## 20. 验证策略：少而有力

不要为每个类新增测试。建议新增一个可选 selector `thermal`，主体集中在 `M9ThermalValidation`，按场景方法复用 fixture；跨层格式改动只追加现有最近 selector。

### 20.1 `thermal` selector 的最小场景

1. **状态函数往返**：各 segment/transition 边界 `T -> U -> T`；同最终组成不同 reaction path 得相同参考能。
2. **绝热均温**：两 cell 不同温度、无外界；face pairwise flux 后总 `U` 闭合，温度单调趋同且不 overshoot。
3. **marker advection/reseed**：搬运、split、merge 后 volume/mass/species/`U` 各自 ledger 闭合。
4. **fixed wall**：区域 `ΔU` 与 boundary source/sink exact 对消。
5. **neutralization**：HCl/NaOH 最终 species 正确，总 `U` 不变，派生温升方向/量由 formation offsets 决定。
6. **precipitation/dissolution**：液/solid composition、mass、SVQ、`U` 和回转闭合。
7. **Stefan fixture**：一维 melt/freeze front 与固定网格焓基准趋势/收敛一致；总能量、phase mass、volume event 闭合。
8. **gas compression**：固定 inventory 的解析 `T V^(γ-1)` 与 work ledger；split/merge/edge/vent nanos+energy 闭合。
9. **boil/condense round-trip**：partial pressure/saturation 驱动，liquid/gas mass、species、volume、nanos、`U` 回转闭合。
10. **dual sleep**：hydrostatic 时继续导热；热平衡后 thermal sleep；任一 source/phase/gas event 精确唤醒。

### 20.2 跨层最近门

- 定义/registry：`thermal`。
- solver/phase：`thermal`；若改 pressure/cut-cell/air shared path，仅追加最近的 `free-surface`、`cut-cell` 或 `gas-liquid`。
- chemistry/granular adapter：`thermal` 加最近 `chemistry` 或 `granular`，不累计所有旧门。
- v4/v6：`region-state`。
- item/WAL：`containers`。
- protocol：`protocol`。
- milestone/release 才运行 routing 的 `milestone` 汇总；部署前才 `clean assemble compileTestJava`，并确认 mods 目录同一 Mod id 只有一个启用 JAR。

测试判据必须同时检查物理 ledgers 与派生温度，不能只断言“温度变了”。

## 21. 里程碑关闭条件

M9 只有满足全部条件才能正式关闭：

- thermal off 时 M0–M8 生产路径与冻结格式不回退；
- thermal registry world-authoritative，unknown/corrupt/mismatch 全部 LOCKED；
- 每个活动物质 inventory 有且只有一个 `U`，不存在可写 temperature；
- conduction 内部 face pairwise conservation，fixed boundary source/sink 对消；
- reaction/precipitation/phase change 不把热量伪装成 external source；
- melt/freeze 与 boil/condense 的 mass/species/liquid volume/solid SVQ/gas nanos/`U` 同时闭合；
- gas compression/expansion按 analytic work ledger 闭合，不靠 pressure clamp；
- motion sleep 与 thermal sleep 独立，所有规定事件能唤醒；
- v4 独立气体评审已完成，v6 是完整联合代次；v3/v4/v5 均未回填热字段；
- `ThermalContents v1`、WAL v5、protocol v5 的身份和 legacy 失败语义通过；
- 旧世界只经显式 initialization 进入 v6，无自动温度猜测；
- routing、正式 architecture、progress/acceptance 文档和版本号同步；
- 若真实场景需要 LTE 以外的换热、自由冰刚体、温变物性或全机械总能量，先达到停止门并另立评审，不预建框架。

## 22. 给执行 AI 的工作清单

每个增量开始前：

1. 重新读根 `README.md`、本文引用的 architecture、`routing.md`、product roadmap；
2. `git status --short`，保留用户/其他 AI 的未提交改动；
3. 只打开本增量需要的代码和最近 selector；
4. 先写本增量的不变量与失败语义，再改代码；
5. 所有 transfer 先 plan/validate，后 commit；
6. 新持久字段先写 identity/ledger/unknown-state 测试，再开放生产入口；
7. thermal-off degeneration 必须是显式门，不靠 null 恰巧跳过；
8. 不用显示温度、registry density、渲染 fraction 回填任何库存；
9. 不因异常视觉或温度曲线增加材料 ID 特判；先检查 state function、contact geometry、operator split 和 ledger；
10. 每完成一个可独立回退的小增量即 commit，并同步 routing/architecture；日常只跑最近门。

这份蓝图的最终裁决是：**M9 的中心不是“温度字段”，而是可持久、可转移、可审计的总内能；温度、反应热和潜热都必须是这套状态的结果。**
