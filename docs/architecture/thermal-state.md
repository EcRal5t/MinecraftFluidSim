# M9 conservative thermal and phase state

本文冻结 M9 温度、相变与反应热的权威真值、状态函数、输运、提交顺序、休眠和跨层格式边界。
活动 CPU 求解器仍是规范后端；marker 体积、逐材料质量、M7 species、M8 solid SVQ、气体库存与 M9 内能分别
结算。温度、热容场、相分数、颜色和客户端网格都不是库存。

## 范围与功能门

- `thermalEnabled` 是独立启动门并要求 `materialsEnabled=true`；`phaseChangeEnabled` 又要求
  `thermalEnabled=true`。旧 config reader 必须强制两个新门关闭。
- 门关闭时不分配热数组、连续固相或热 dirty queue，M0--M8 活动路径和既有格式必须逐位退化。
- M9 包含凝聚相内能、随物质平流、有限体积导热、显式固定温度边界、M7/M8 反应热、熔化/凝固、
  非等温密闭气体和沸腾/冷凝，以及与运动休眠正交的热休眠。
- 首版不做辐射、燃烧、气相化学、温变黏度/表面张力、凝固点降低、自由冰刚体、
  固相应力、热致颗粒侵蚀或 GPU 热后端。
- 气体生产持久化必须先由独立的 `region-state v4` 基态评审冻结库存、拓扑与恢复语义。该 v4 不携热状态、
  不携 granular；完成前 M9 的气体热学只能处于不开放生产存档的隔离增量。

## 单位与物理真值

world thermal registry 冻结 `unitSystemId=realwater:si_block_v1`：`1 BV=1 m³`、`1 MU=1 kg`、
`1 TU=1 K`、`1 EU=1 J`，solver 时间单位为秒。同一 world snapshot 的单位不得无迁移改变。

M0--M8 已冻结的 condensed raw mass 并不是 MU：它由 `normalized cell volume * referenceDensity`
生成，是压力、化学 residual、颗粒和容器共同持久化的 **solver mass weight**。在固定网格上，M9 只能经一个集中换算进入
SI 状态函数：

```text
physical volume [m3] = normalized cell volume * h^3
physical mass [kg]    = condensed solver mass weight * h^3
condensed weight      = physical mass [kg] / h^3
```

气体 component mass 已直接使用 kg，禁止再次乘 `h^3`。phase definition 的 `mass_mu_per_batch` 也始终是 kg；
固液相变在两个 condensed owner 间搬运对应 solver weight，气液相变则必须在 condensed weight 与 gas kg 的边界显式换算。
不得把换算散落在热容、相变或容器调用点，也不得借修复 M9 静默改写 M3/M7/M8 的独立 raw mass。

M9 新增以下互相独立的权威真值：

1. 每个液体 marker 的 raw finite `double internalEnergy`；
2. 每个 M8 active parcel 与 deposit group 的 raw finite `double internalEnergy`；
3. 每个 M9 coherent phase group 的 stable phase ID、exact occupied SVQ、独立质量、可选组成和
   raw finite `double internalEnergy`；
4. 完成气体 v4 前置后，每个 sealed gas node 的逐组分 standard-volume nanos、独立质量、可选 species 与总内能；
5. 区域 thermal external source、external sink、mechanical work 和 roundoff residual 审计。

内能可以为负，因为参考零点任意；必须 finite。持久化逐库存写 raw IEEE-754 bits，并以 canonical dyadic sum
复核 section、carrier 和 global 三层账本。`SignedDyadic` 只用于提交审计和 codec，不进入逐 face 热循环。

以下全部是派生或可重建数值状态：温度、热容量、导热率、热通量、LTE cell node、相分数、气压、显示颜色。
禁止从温度、参考密度、客户端快照或当前数据包反算并覆盖任一库存。

## World-authoritative thermal registry

M9 使用独立 `ThermalRegistryStore v1`，布局为 `<world>/realwater/thermal/registry.nbt`。首次启用时从 data pack
严格装载并原子发布；之后 snapshot/fingerprint 是已有热库存的唯一解释，当前 data pack 只用于 mismatch 诊断。
unknown/corrupt/missing、依赖 registry fingerprint 失配或已有 M9 state 却缺 snapshot 时保留证据并全局 `LOCKED`。

定义分为：

- `ThermalMaterialDefinition`：stable thermal ID、carrier kind/ID、共同参考温度与参考比内能、连续分段常数
  `c_v(T)`、导热率和有效温区；
- `ThermalSpeciesOffset`：stable chemical species/host 对应的参考形成内能；
- `PhaseTransitionDefinition`：两侧 thermal phase、平衡模型、exact 可逆 transfer batch、界面要求和压力有效域；
- `ThermalGasComponent`：standard state、质量创建/校验关系、`c_v` 与参考内能；
- `ThermalBoundaryDefinition`：Minecraft block/tag 的 `ADIABATIC` 或 `FIXED_SURFACE_TEMPERATURE` 边界。

`realwater:thermal_reservoir` 是唯一 code-owned 的可调边界入口：方块 ID 声明无限热浴语义，vanilla chunk 内的
block entity 以 raw IEEE-754 bits 保存 operator 设定温度，默认新放置为 293.15 K。它不加入或改写既有
`ThermalRegistryStore` fingerprint，因为旧代码不可能创建该新方块，只有显式放置才引入该边界；data pack 的静态
block/tag 映射仍只由 world snapshot 解释。缺失 block entity、非 finite 或小于 1 K 的持久设定值不得猜测默认值，
接触该面的区域按 thermal-definition failure 保留并暂停，operator 可显式重设并重启后恢复。

每个活动 carrier 必须恰有一个 thermal model，不允许运行时 fallback。loader 在发布前拒绝未知引用、重复定义、
非正温度/热容、负导热率、断裂或重叠的温区、非单调 `U(T)`、相变循环、不可逆 batch、潜热与状态函数不一致、
依赖 fingerprint 失配及全部预算/算术越界。零导热率明确表示绝热，不表示缺数据。

## 统一状态函数

对任一凝聚相库存 `a`：

```text
B_a = m_a * u_ref(carrier) + sum(amount_s * species_offset(s, host))
U_a(T) = B_a + m_a * integral[T_ref..T] c_v,carrier(tau) d tau
C_a(T) = dU_a/dT
```

分段常数正热容使 `U(T)` 连续严格单调；反演只定位一段后做一次线性求解。`U` 超出定义共同有效域、温度非正、
热容非正或反演无解时 plan 不提交并 `thermal-definition PAUSED`，禁止外推或钳制温度。

反应热不作为 external source，也不在反应提交后额外写 `delta U`。M7/M8 反应保持参与库存的总 `U`，组成变化使
参考形成能 `B` 改变，随后同一个 `U -> T` 反演自然给出升温或降温。这样相同最终组成与路径无关，也不会因
reaction order 重复放热。只读诊断可报告反应前后 `B` 差。

潜热同样是两个 phase 状态函数在平衡温度/压力处的参考能差，不是第二个能量账本。cell 总 `U` 落在两相边界间时
温度停留在平衡平台，多余内能确定可提交的相变 extent。相同温度的多个 transition 按尚可转换 latent capacity
比例分配，stable transition ID 只用于整数余数破同。

## 热输运

物质直接携带 `U`，所以 marker、active parcel 和 gas edge 的运动同时平流能量。split/merge、容器转移、
parcel/deposit、相变与 reaction 必须在同一 plan/commit 中搬运对应 `U`；不存在 orphan energy。

每个 logical thermal step 从权威库存重建 `ThermalCellGrid`。首版采用局部热平衡（LTE）：同格液体、active parcel、
deposit、coherent solid 共用一个派生温度。grid 聚合 `B`、总 `U`、分段热容、有效导热率和物相接触几何；
它不是第二套库存。

跨 face 使用守恒有限体积导热：

```text
G_ij = A_contact / (d_i / k_i + d_j / k_j)
Q_ij = G_ij * (T_j - T_i) * dt_thermal
```

`A_contact` 只从共享 cut-face、总 occupied-volume 和服务端 PLIC 接触几何派生；不得使用 marker count、渲染 mesh
或材料 ID 特判。每个 canonical x/y/z face 只计划一次，对两侧施加等量反向 `U` 变化。显式 Jacobi 子循环采用
`dt_limit = 0.5 C/sum(G)` 的单调界；`C/sum(G)` 的 positivity 极限会允许等容量两格在一步中交换温度，不作为生产上限。
超过 hard substep/face budget 时整步拒绝并 PAUSED，禁止钳通量或借 wall-time pacing
掩盖不稳定。

普通 Minecraft 方块默认 `ADIABATIC`，避免凭空假定无限热容。只有 world registry 显式声明的
`FIXED_SURFACE_TEMPERATURE` face 和显式放置的 `realwater:thermal_reservoir` 是外部储层，反向能量进入
source/sink 审计。reservoir 设定值只改变下一次边界解析，不直接写任一 carrier `U`；低于相邻 LTE 温度时是冷源，
高于时是热源。它同时是合法 controller 闭壳块，只在实际 blocked cut-face 的物质覆盖面积上交换热量。
有限热容 world block 需要独立
ownership/persistence 评审，不在 M9 v1。

### 自然对流

只有导热不能使下方有限接触热源成为可用的宏观加热入口。M9 使用低马赫不可压流中标准的 Boussinesq
近似，把温度造成的密度差作为派生体力加入 mechanical substep；它不修改 marker mass、volume 或能量库存：

```text
a_b = -g * beta(T) * (T - T_ref) e_g
```

`beta(T)`、共同参考温度和有效温差必须来自 world-authoritative thermal material definition，允许分段常数并要求
finite、非负和连续有效域。face 加速度只从相邻同 carrier 的服务端 LTE 温度、cut-face aperture 与已有 P2G 支撑派生；
压力投影随后消去不可压缩部分。无热状态或定义 `beta=0` 时精确退化；超出 Boussinesq 有效温差时进入可读
thermal-validity PAUSED，异 carrier face 则不猜混合系数。该项是物理模型的一部分，不记 thermal external source/sink，
也不伪装成热膨胀体积或完整机械总能量守恒。

## 连续固相与熔化/凝固

凝固生成独立 `CoherentPhaseStore`，不是 M8 granular/deposit，也不是继续参加压力解的静止液体 marker。
每格保存少量 canonical group：phase stable ID、occupied SVQ、质量、可选组成和 `U`。它表示固定网格、无孔隙、
零速度的连续固相；首版不声称自由冰块会漂浮、断裂或成为刚体。

coherent occupancy 与 granular occupancy 合成唯一 `OccupiedVolumeField`，供 free-surface、pressure、cut-cell、
gas available volume、collision、volume drift 和插入容量共同消费，禁止复制局部压力几何。

相变 planner 只按完整 exact batch 转移液体 volume/mass/species/residual/`U` 与 coherent group 库存。
composition-bearing 液体的 species quanta 与 residual 从 M7 parcel 先完整预演拆分，再原样并入 coherent composition；
融化时以新建非 indicator parcel 返回，禁止借当前默认化学库存重建。indicator provenance 不进入固相，带 indicator 的
marker 不参与冻结分配且不得因相变缩放。同格 active parcel/deposit 保持 M8 库存身份不变，但作为热浴加入同一个 LTE
候选反演；因此相变可以和颗粒交换显热，不能把颗粒存在当作拒绝相变的特异条件。
同格 coherent group 也是气液相变的 unchanged LTE 热浴，不得仅因连续固相存在就拒绝沸腾/冷凝。
液体 marker 的 raw float 体积仍是权威真值；整数 SVQ 上/下界只用于候选容量和可移除量预检，
不得反向量化或改写 marker 尾数。已提交库存的 identity/audit/状态函数无效仍 PAUSED；
但仅 prospective exact batch 无法分配该 marker 尾数、容纳于当前格或在 data-pack 有效域内反演 LTE 时，该候选局部 no-op，不暴露部分修改，也不把整域误停。
相态变化的 `delta V` 是显式 volume event，并在下一 mechanical projection 消费同一 pending volume/work record；
其 `-p delta V` 进入 mechanical-work ledger。正膨胀在闭域中无可表示容量时 PAUSED，不删体积、不钳相分数。
pending record 使用 exact `SignedDyadic` cell-volume（SVQ 事件为 `integer * 2^-24`，marker float 保留其精确二进制值），
属于独立的 manifest-only v7 extension `realwater:pending_phase_volume_v1`；extension 缺失是唯一 canonical empty 表示。
checkpoint/restart 后必须唤醒 motion，在下一次真实 pressure projection 已消费新 occupancy/volume drift 后按该格 absolute
pressure 记一次 `-p delta V`，联合验证 carrier 能量后清除记录。不得同时再注入 divergence source 而重复排水，也不得提前
清除、在 `empty` 中吞掉或因 thermal sleep 丢失。气液相变已由 gas available-volume remap 当场结算 gas compression work，
不再生成同一 pending condensed-volume work。

## 非等温气体与沸腾/冷凝

完成独立 gas v4 基态后，v6 联合状态复用其 gas component/nanos/mass/species 语义并增加节点总 `U`。节点温度与压力：

```text
C_v = sum(component standardVolume * cvStd)
T = inverse(U, composition)
p_abs = P0 * (Vstd_total / V_available) * (T / T0)
```

topology merge/split、孔流、ambient inflow/outflow 必须同时搬运 nanos、质量、species 和能量；孔流携带源侧焓。
无传质绝热体积变化用状态方程解析计算压缩功，并把同一 `delta U` 写 mechanical-work ledger。触及 model validity
window 时 PAUSED，不能继续使用会破坏能量闭合的 pressure clamp。

沸腾/冷凝只在服务端解析的 liquid-gas interface 上按 saturation relation 计划，不用固定 100 C 或每 tick 百分比。
plan 联合移动液体与气体的 volume/nanos、质量、species、residual 和 `U`；潜热由两侧状态函数自然体现。
没有界面、receiver、预算或几何容量时不提交。气体 v4 前置完成前这些路径不开放 production controller。

composition-bearing 气液相变不按 material/species ID 写死挥发性：gas thermal material 上存在对应
`ThermalSpeciesOffset` 才声明该 species 可由该气相承载。沸腾对每个非 indicator liquid marker 做 exact binary-volume
species split，只把已声明 species 与其独立 mass residual 移入 gas component；未声明的溶质留在保底正体积液相，indicator
marker 原始 volume/mass/provenance 不参与分配。同格 active/deposit 只作为 LTE 热浴与 unified occupancy capacity，不是拒绝条件。
冷凝按实际 gas component species/residual 回写非 indicator marker，没有可合并目标时新建非 indicator marker；chemistry
开启但旧 vapor 缺 species 真值时保留并 PAUSED，禁止按 liquid default 或当前 data pack 猜测 H2O。bundled water-vapor host
只声明 `realwater:h2o`，所以现有离子/盐不会被隐式蒸馏到气相。

## 反应、相变与每步提交顺序

一个完整 logical step 固定为：

1. 捕获 raw inventory/source/sink/work 与 pending volume 审计；
2. mechanical substeps：P2G/occupied geometry、gas work/edge transfer、granular preview、压力、G2P/advection 与
   granular collision commit；`U` 只随载体移动；
3. 从新位置重建 thermal grid，计划并提交 conduction/reservoir exchange；
4. M7 reaction 联合 plan/commit：保持参与 marker 总 `U`，同时提交 species/mass/reaction dirty；
5. M8 solid-liquid reaction 联合 plan/commit：同时提交 liquid/granular `U`、composition 与 volume event；
6. M9 equilibrium phase plan/commit：先 melt/freeze，再 boil/condense；
7. granular deposit/compaction/wake 搬运对应 `U`；
8. marker reseeding 联合 split/merge volume/mass/material/phase/chemistry/indicator/`U`；
9. 重建账本并验证 thermal identity，再更新 thermal dirty/sleep。

任一 plan 的库存、状态函数、几何、pool、operation、substep、算术或 audit 预算失败时不得暴露部分修改。
已有 M8 的逐 cell 已提交事务保持其既有语义，但每个单独 cell commit 必须包含完整能量侧。

## 能量闭合与失败语义

固定几何绝热系统要求：

```text
U_now + external_sink = U_initial + external_source + mechanical_work + roundoff_residual
```

内部导热、反应、相变和载体重排不记 external source/sink。当前 FLIP 没有完整动能/势能真值，因此 M9 只声称
thermal subsystem 加显式 work ledger 闭合，不声称全机械总能量守恒。

每次 joint commit 还必须验证热学路径未意外改变不相关账本：液体 marker mass/volume、M7 species/组成/电荷、
M8 solid SVQ/mass/composition 和 gas nanos/mass/species 均按该事务声明的 transfer 精确闭合。纯 double 重分配误差
只进入独立 roundoff residual；超过基于 ULP/硬绝对 floor 的预算即 PAUSED，不能归入 source/sink。

失败原因至少区分 `thermal-definition LOCKED`、`thermal-transport PAUSED`、`phase-change PAUSED`、
`gas-thermal PAUSED` 和 `thermal-persistence LOCKED`，并报告 region/generation/cell/transition。

## 运动休眠与热休眠

`FluidRegionLifecycle` 继续表达 motion scheduling；新增正交 `ThermalActivity`，不把所有组合塞入同一 enum。
`HYDROSTATIC_SLEEP + thermal ACTIVE/REDUCED` 必须继续运行导热、反应与相变；只有两轴都 sleep 才完全跳过。

thermal sleep 要求连续 quiet steps 内：所有内部/边界可表示通量为零，无 dirty chemistry/precipitation/phase，
无 pending volume/work，无 gas topology/flow/work，且 audit 正常。处于平衡的 partial latent plateau 可以休眠。
方块边界变化、容器转移、管理员 source、chemical/granular dirty、gas topology 或可表示热通量精确唤醒；
调整 thermal reservoir 设定值属于方块边界变化；
客户端观察永不唤醒。不得修改现有 40/200 ms wall-time 阈值。

## 跨层格式与迁移

| 层 | M9 格式 | 边界 |
|---|---:|---|
| server config | v6 | `thermalEnabled`、`phaseChangeEnabled` 与硬预算；旧 reader 强制关闭 |
| world thermal registry | v1 | definitions、单位、依赖 fingerprints 与 canonical SHA-256 |
| gas prerequisite | region-state v4 | 单独评审的 gas 基态；不携 thermal/granular |
| legacy complete thermal region | region-state v6 | 旧实现把 condensed solver mass weight 错当 kg；只读保留，不能进入修订 runtime |
| complete region | region-state v7 | material + optional chemistry/granular + reviewed gas + corrected SI thermal |
| protocol | v5 | temperature/phase/gas display only，不发 exact `U` |
| item | `ThermalContents v1` | 与 underlying canonical contents hash 绑定的总 `U` |
| container WAL | v5 | 物质 primary resource 与 coupled energy 同一事务；v1--v4 reader 保持 |

`region-state v7` 是一个完整联合 generation，不是 v5/v6 HEAD 加 sidecar。manifest/section 必需 typed thermal extension，
逐 marker/active/deposit/coherent/gas energy ordinal 必须完整且 canonical；HEAD 仍是唯一且最后提交点。
恢复时可接受由 FLIP advection 留下、尚待下一次压力投影解决的局部 liquid marker 聚集；
但同格 fixed coherent + granular occupancy 必须独立满足已提交几何容量，禁止为通过恢复而删除或缩放任何库存。
已提交但尚未投影的凝聚相/固液反应 volume event 只使用上述 optional manifest extension；它与 required thermal payload 同代发布，
只允许引用存在 thermal carrier 的格。读取未知、非 canonical、越预算、cell 失配或非 v7 携带均锁定，禁止猜测清零。
reader 精确接受 `{1,2,3,4,5,6,7}`，但 v4 只按其独立 gas capability 读取，不能由版本号猜联合内容。

v1/v2/v3/v5 没有热真值；v6 有旧质量基准下的热真值。两者都禁止自动按环境、biome、当前 registry 或外观猜或重解释
`U`。旧 controller 只有在 PAUSED、
ownership 完整、无 pending WAL 且 operator 显式给定初始温度后，才能在 inactive slot 生成并回读验证完整 v7，
最后切换 HEAD；修订实现统一生成 v7，初始化 record 保存 source version、raw temperature bits、gas bootstrap 与 operator metadata。
v6→v7 时旧 `U` 明确作为被 operator 替代的旧基准证据保留在上一槽；material、chemistry、granular 与 gas 库存从同一旧 HEAD
回读，禁止缩放或按 reference density 重建。初始化温度为全部现存 carrier 建立新的 SI `U` 与 audit baseline。
这种旧代次在启动后是稳定的 operator-required 等待态：保留 HEAD/marker/体积，暂停且不发布 protocol v5 thermal snapshot，
每区域只记一次警告并向每个玩家提供一次库存证据，不能作为可重试的恢复错误周期性扫描。
失败仍以旧 HEAD 为权威。旧 WAL 跨初始化代次且缺能量时保留并 LOCKED 人工处置。

`ThermalContents v1` 只保存 registry fingerprint、underlying contents kind/hash 与 total `U`，tooltip 温度只读。
容器物质与能量必须由同一 WAL v5 跨 item/region 提交；单次 count 仍为 1，目标仍只按实际命中区域路由。
创造放出记 material+energy source，创造装取不制造 sink；`empty` 联合预检并清除 liquid marker、coherent phase
与 M8 active/deposit，只把这些实际删除载体的 `U` 记 escaped sink；gas inventory 不属于该凝聚相清空。

protocol v5 严格协商 thermal capability，只发有界量化温度、coherent phase fraction、gas display 和 debug flags。
客户端不得收到 exact energy/source/sink，也不得回写 server state。

## 硬预算与验收停止门

预算必须覆盖 registry definitions/segments/transitions/gas components、coherent group、dirty cell、face evaluation、
thermal substep、phase operation、serialized extension 和 dyadic audit。预算在分配或部分提交前验证；超限 PAUSED。

M9 只有在下列门全部满足后关闭：

- thermal off 时 M0--M8 活动路径与旧格式逐位退化；
- 每个活动物质库存有且只有一个 `U`，不存在可写温度；
- 绝热 face pairwise 守恒，fixed-temperature boundary 与 source/sink 对消；
- M7/M8 reaction、melt/freeze、boil/condense 与 gas work 不依赖经验温升或额外内部 source；
- 质量、组成、体积/SVQ/gas nanos 和能量同时闭合；
- motion/thermal sleep 独立，所有规定事件能唤醒；
- thermal registry、v4/v6/v7、item/WAL/protocol identity 不明时保留并 LOCKED；
- 旧世界和旧 v6 只经显式 initialization 进入 v7；
- 自动门通过；只有真实渲染、世界热边界和跨重启交互无法自动观察时才请求一次合并实机 smoke。
