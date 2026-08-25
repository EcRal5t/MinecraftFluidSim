# M9 温度、相变和反应热（修订中）

> 2026-08-25 修订状态：真实可调热源 smoke 随后发现 condensed mass 的 `h^3` 单位遗漏、controller headspace
> 被误判 ambient，以及底部热源没有自然对流；因此本页此前的“完成”仅保留为 0.15.0 历史证据，M9 已重新开放。
> 修订契约以 [`../architecture/thermal-state.md`](../architecture/thermal-state.md) 为准。

## 2026-08-25 production revision

- 新增唯一 `CondensedMassScale`，把 M3/M8 frozen solver mass weight 经 `h³` 转成状态函数使用的 kg；旧 v6 因错误
  能量尺度只读保留，修订 runtime 与 checkpoint 独占 region-state v7，旧代次只能由 operator 显式初温重建。
- controller footprint 明确选择 sealed solver boundary，闭壳 headspace 现在生成真实 gas node；命令域及有缺口几何仍 vent。
- water/oil/mercury 使用 world-bound model identity 的 Boussinesq 派生体力接入既有 y-face/projection，底部储层能够驱动
  自然对流而不修改质量、体积或能量真值。
- boil/condense 从“每界面格每步固定 1 个最小量子”改为纯 plan + 单次 commit 的多批 extent；二分搜索受统一预算、
  liquid/gas inventory、cell capacity、候选 LTE/气体有效域与 saturation direction 同时约束。自动 fixture 已证明一次提交和
  一次反向凝结都可超过 1 批，并闭合 condensed kg、gas mass/nanos、可选 species/residual 与总 `U`。
- `/realwater status` 保留只读派生温度，并新增最近 solid/gas phase 批数以及每个 gas node 的 water-vapor kg/standard volume；
  不增加准星温度 HUD，Jade/WAILA/HWYLA 查询边界仍留在 M10。

最近门：`thermal`、完整 `gas-liquid`、`chemistry` 通过；等待一次 v7 controller + reservoir 合并实机 smoke 后关闭修订版 M9。

## M9.12 gas persistence prerequisite design

- Froze [`../architecture/gas-state-v4.md`](../architecture/gas-state-v4.md). Gas v4 is a complete material + optional
  chemistry + gas generation and explicitly excludes granular/thermal state.
- Stable component nanos, raw mass, optional species/residual and source/sink audit are independent truth. Runtime node
  slots are replaced by canonical minimum-cell keys plus complete section membership; volume/pressure/edges stay derived.
- v1/v2/v3 require an explicit ambient bootstrap, v5 cannot migrate backward, and complete v6 must reuse the reviewed
  gas payload before adding per-node energy.

## M9.13 gas component runtime core

- Replaced the M5 node's undifferentiated long inventory with a canonical stable-component inventory. Standard-volume
  nanos, raw mass, optional species/residual and their source/sink ledgers now move together through orifice flow,
  split/merge, venting and topology remap; the legacy total-nanos API and isothermal EoS remain unchanged.
- Added the canonical gas/ambient registry candidate and built-in `realwater:air`. Topology remap now partitions every
  component by exact overlap weights and explicitly declares ambient source/escaped sink deltas.
- Added a two-component/species fixture to the existing `air-network` gate. `air-network`, `air-cavity`, and the full
  53-second `gas-liquid` fixture passed; the latter retained 0.91% compression-head and 6.73% suction-head error.

## M9.14 complete gas `region-state v4`

- Added strict outer v4 dispatch and required typed gas manifest/section payloads. The manifest saves canonical node keys,
  complete component inventories, source/sink and dyadic roundoff audit; every section saves full sealed-membership cells.
- Capture/reconstruction recomputes section cardinalities, minimum-cell topology keys and the four-column audit. Fresh-domain
  restore rebuilds cavity geometry, requires exact per-cell topology equality, then derives available volume/edges before
  binding inventory. Foreign gas identity, missing payload or topology disagreement fails closed.
- The existing `region-state` selector now covers v4 codec, atomic publish/read, registry mismatch, missing section payload
  and live-domain restore; it passed with all v1/v2/v3/v5/v6 compatibility fixtures unchanged.

## M9.15 authoritative gas registry store

- Added atomic `gas/registry.nbt` v1 publication/readback for standard state, component mass/residual creation relations,
  optional default species, ambient fractions, canonical bytes and fingerprint.
- Existing valid snapshots remain authoritative when current candidates differ; unknown layout/corruption is preserved and
  fails closed. The v4 persistence fixture now verifies first publication plus mismatch retention under `region-state`.

本文记录 M9 的增量、自动门与实机证据。正式契约见
[`../architecture/thermal-state.md`](../architecture/thermal-state.md)。M9 从已验收主 Mod 0.14.0 开始，于
2026-08-25 以主 Mod 0.15.0 关闭；CPU 仍是规范后端。

## M9.0 architecture freeze

2026-08-24 完成两份独立提案与现行 M0--M8 契约的对照，冻结以下裁决：

- 广延总内能是新增真值，温度只由 world-authoritative 状态函数派生；
- reaction/phase formation energy 属于状态函数，内部反应热和潜热不伪装成 external source；
- 热输运采用 LTE cell node 与 pairwise conservative finite-volume conduction；默认 Minecraft wall 绝热；
- 凝固使用独立 coherent phase store，不把静止 marker 留作压力流体，也不借 M8 granular 表示连续冰；
- 运动休眠与热休眠正交；
- config v6、thermal registry v1、完整 region-state v6、protocol v5、ThermalContents v1 与 WAL v5 各自门控；
- 无热真值的旧 generation 只能由 operator 显式给初温迁移，不能自动猜环境温度；
- production 非等温气体及 boil/condense 以前置、无热字段的独立 gas region-state v4 评审为门。

本增量只改文档，按 routing 不运行构建门。

## Planned increments

1. 纯 Java thermal registry/state function/audit 与单一 `thermal` selector；
2. transient 液体 `U`、handoff/reseed/transfer 与 finite-volume conduction；
3. M7/M8 joint energy commit、fixed-temperature boundary 与审计；
4. coherent phase melt/freeze 与 occupied-volume coupling；
5. 独立 gas v4 基态评审与实现，随后 non-isothermal gas/boil/condense；
6. world thermal registry、operator initialization 与 complete region-state v6；
7. ThermalContents/WAL v5、protocol v5、diagnostics、dual sleep；
8. milestone/release 自动门与单一合并实机场景。

## M9.1 pure thermal state core

新增 `com.example.thermal` 的有界 definition/strict JSON codec/datapack loader、canonical `ThermalRegistry`、
分段常热容 `ThermalStateFunction`、exact-batch phase definition 与 `SignedDyadic` audit。registry fingerprint 对定义输入顺序
不敏感，runtime handle 不跨层；species formation offset 与 phase reference-energy gap 共同保证 reaction heat/latent heat
来自同一状态函数。新增唯一 `thermal` selector，不创建 Gradle task；覆盖 definition 拒绝、canonical fingerprint、
负内能/分段往返、路径无关反应温升、相变平台与大数抵消审计。

## M9.2 transient liquid energy and conduction

液体 marker 增加正交的 transient 总内能列，由 `FlipThermalState` 独立持有；domain 只提供 material、chemical mixture、
cell 与 cut-face 编排。handoff、spawn/remove、exact extraction、clear、material relabel 与 reseed merge/split 都同步提交
能量和 `SignedDyadic` source/sink/roundoff audit，温度仍只从当前质量、组成和总内能反演。

`ThermalCellGrid` 只建立 LTE 派生 node，`ThermalTransport` 以 harmonic conductivity 和 pairwise conservative
finite-volume flux 做显式传导，并按 Fourier stability limit 有界 substep。当前只接受 0.5 m cell 和完整开放 cut face；
partial cut/PLIC contact、M8 固相能量、持久化与 production config 仍被明确拒绝，不能把 transient 状态误当生产格式。

最近门：`thermal,free-surface,reseeding` 通过；未请求实机。

## M9.3 reaction heat and fixed reservoirs

逻辑步顺序改为先导热、后 M7 reaction。`ChemicalParcelStore` 在完整 reaction candidate 闭合后、任何 store mutation
以前调用 thermal validator；候选组成和质量在保持 marker `U` 不变时必须仍能反演到共同定义温区。失败保留原 species、
质量、dirty bits 和能量，成功则形成能变化自然表现为温升/降温，不增加 external source。

thermal registry 增加有界 canonical boundary definition；普通 face 仍默认绝热。`ThermalTransport` 支持无限热容的
fixed-surface-temperature face，半格热阻进入 stability limit，reservoir exchange 进入 external source/sink，浮点残差
只进 roundoff。每个 logical transport step 保存 marker 能量快照；任一 substep 无法表示时回滚整步，禁止暴露前几个
substep 的部分提交。

最近门：`thermal,chemistry,free-surface` 通过；未请求实机。世界 block/tag 解析与 production gate 尚未开放。

## M9.4 world thermal registry v1

新增 `<world>/realwater/thermal/registry.nbt` 的严格有界 snapshot。首次启用以 temporary file 写入、force、回读校验后
原子发布；已有 snapshot 永远是 authoritative，当前 datapack fingerprint 变化只报告 mismatch。unit system、chemical/
granular dependency fingerprint、canonical bytes 或定义重建 identity 任一失配都拒绝读取；目录中未知文件原地保留并
锁定，不能静默忽略。materials、species formation offset、phase transition 和 boundary definition 全部进入同一 v1。

最近门：`thermal` 通过；仍未开放 config/region-state，因此没有旧世界被自动初始化。

## M9.5 complete region-state v6 liquid inventory

新增 required typed thermal manifest/section extension，把逐 liquid marker raw `U`、section/carrier/global canonical dyadic
账本、world thermal fingerprint 和完整 source/sink/work/roundoff audit 放进同一个 v6 generation。core manifest、section、
checksum 与 NBT codec 都显式门控 v6；v1/v2/v3/v5 canonical bytes 不变，预留 v4 仍不由版本号猜测读取。

`RegionState.captureThermal` 当前只接受已经绑定 material、可选 chemistry、且没有 M8 库存的 transient liquid 域；遇到
active/deposit/coherent/gas 库存直接拒绝，防止发布“不完整 v6”。`RegionStateFiles` 在 inactive slot 回读时必须拿到
fingerprint 相同的 authoritative thermal registry 并重建 liquid + thermal 两侧，之后才允许 HEAD 发布。缺 extension、
ordinal/count/energy/audit 不闭合、未知 registry 或原始位不一致都保留旧代次并失败关闭。

最近门：`thermal,region-state` 通过；尚未接 production config/旧世界 initialization，也未声称 M8/gas joint v6 完成。

## M9.6 coherent phase inventory and inert melt/freeze core

新增独立于 M8 granular 的 `CoherentPhaseStore`：每个 cell+phase group 持有 exact occupied SVQ、质量、可选
species 组成和总内能；canonical merge、按 SVQ 比例 split 以及无副作用的 add/take 预检均有硬预算。thermal LTE grid
现在统一从 liquid marker 与 coherent group 的权威库存派生温度、热容和导热率，二者间的 cell-local 固液换热不制造
外部 source/sink。coherent exact SVQ 通过 `GranularCouplingFields` 的 fixed occupancy 与既有压力、排水和碰撞场共享，
但没有冒充 M8 deposit/parcel。

新增 exact-batch `ThermalPhaseChangeEngine` 与显式 `stepInertPhaseChangeOnly` 提交路径。首个隔离增量只接受
`COHERENT_SOLID <-> LIQUID`、无组成、无 chemistry/granular 的同格转移；计划由总内能状态函数和平衡温度决定，
提交前验证压力域、联合 exact 容量、marker/group/operation 预算、质量与 SVQ 可表示性。冻结与熔化都保持 cell 总内能，
潜热仅来自两相 reference energy 差，不记 external source/sink；失败检查在权威库存变更前完成。production tick、机械功、
有组成相变、气液相变以及 coherent v6 persistence 仍保持关闭。

在唯一 `thermal` selector 中扩展现有相变场景，覆盖 coherent merge/split、exact-batch planner、固液 LTE，以及
一格液体从 295 K 冻结到 50% 固相、再由 305 K 熔回液体的往返；逐次核对 liquid volume、solid SVQ、总质量、
总内能与 audit identity。最近门：`thermal,free-surface,granular` 通过；未请求实机。

## M9.7 coherent phase region-state v6

把 M9.6 coherent group 纳入既有 v6 handoff 与同代 section/manifest extension。每个 group 按所属 world section 保存
global cell、stable phase ID、exact occupied SVQ、raw mass、可选 canonical species composition/residual 和 raw `U`；
section ledger 与 manifest 的 liquid/coherent carrier ledger 分别以 canonical dyadic sum 复核，最后再与完整 thermal audit
对消。composition 直接自包含于 typed thermal payload，不再保留没有真实 pool 可指向的占位 index。

`FlipThermalParticleState` 现在是 liquid+coherent 正交 handoff；构造、restore 与 region reader 均验证 canonical group、
thermal carrier kind、状态函数有效域、registry fingerprint、cell ownership 以及 liquid+coherent exact geometric capacity。
旧 liquid-only v6 bytes 保持不变，因为此前 capture 明确拒绝任何 coherent group；因此不存在需猜测迁移的已发布
non-empty coherent v6。M8 active/deposit 与 gas energy ordinals 仍显式拒绝。

扩展既有 `region-state` 场景，用非零 coherent energy 完成 capture、codec、atomic publish、reconstruct、domain restore
全往返，并单独覆盖 composition payload 的无损 codec。最近门：`thermal,region-state` 通过；未请求实机。

## M9.8 server config v6 boundary

`FluidServerConfig` 升至 v6，新增显式 `thermalEnabled`、`phaseChangeEnabled`、每 logical step 最大 thermal substep
与 phase operation 预算。thermal 要求 materials，phase-change 要求 thermal；预算不能超过代码硬上限。v1--v5 reader
一律把两个新门置 false，v6 缺任一门或预算则拒绝，默认新世界也保持关闭。现阶段 simulation service 刻意不消费
这两个门，避免在 M8/gas/container 联合能量尚未完成时产生“配置已开但库存不完整”的生产状态。

扩展既有 world-config persistence 场景，覆盖 v6 round-trip、v5 安全退化、v6 缺键拒绝和依赖链。最近门：
`region-state` 通过；未请求实机。

## M9.9 granular raw-energy column core

新增 `GranularEnergyColumn`，active parcel 以 M8 ordinal 对齐，deposit 以 canonical
`(cell, materialHandle, compositionHandle)` 对齐，只保存 raw finite `U`；质量、SVQ、组成和位置仍只属于 M8 store。
该列提供有界 add、swap-remove、merge、按 exact SVQ 比例 split/take、deposit move、canonical handoff 与 clear，
所有算术在修改前检查 finite/预算。扩展唯一 `thermal` 场景验证跨 active/deposit 的 split/merge/move 后，剩余库存与
移出能量用 `SignedDyadic` 精确回和。最近门：`thermal` 通过。此提交只建立正交 owner，尚未解除 domain 对
granular+thermal 同时绑定的拒绝。

## M9.10 granular joint energy integration

`FlipThermalState` 把 M8 active parcel 与 deposit 作为 LTE carrier 接入同一 cell grid；domain 的 insert、碰撞、
deposit/wake/move/merge、clear 和固液 reaction 路径都同步预检并提交正交 `U`。沉淀/溶解保持参与库存总能量，
formation offset 由状态函数自然产生反应温升，失败时 M7/M8/thermal 三侧均不暴露部分修改。

最近门：`thermal,granular,chemistry` 通过；未请求实机。

## M9.11 authoritative gas v4 prerequisite

冻结并实现独立 `region-state v4` 气体基态：canonical cavity topology、逐稳定组分 nanos/mass/species/residual、
source/sink audit 与 world-authoritative `GasRegistryStore v1`。恢复必须先从已提交几何重扫并逐格匹配，未知、损坏、
registry identity 或 section membership 失配均保留并 LOCKED；v4 不含 M8/M9 字段，也不占用 v5/v6 语义。

最近门：`region-state,gas-liquid` 通过。

## M9.12 non-isothermal gas energy

气体节点增加正交总 `U`，温度、压力与密度由组分、可用体积和状态函数派生。拓扑 split/merge 同步分配能量；
有限孔流携带源侧焓；无传质体积变化使用常 `cv` 理想混合物解析绝热功并写 mechanical-work ledger。
旧 M5 等温路径在 thermal 未绑定时保持不变。

最近门：`thermal,air-network,gas-liquid` 通过。

## M9.13 complete gas + thermal region-state v6

v6 现在要求同代完整 gas v4 topology/inventory 与每个 sealed node 的 raw `U` ordinal；即使零节点也必须存在 typed
gas/thermal payload。capture、codec、inactive-slot 回读和 domain restore 都联合验证 material、可选 chemistry/granular、
gas registry、thermal registry、拓扑 ordinal、可用体积派生状态及 exact audit，之后才允许发布 HEAD。

最近门：`thermal,region-state` 通过。

## M9.14 sealed liquid/gas phase transfer

新增液气界面相变模型：Clausius--Clapeyron 饱和压由两侧 state-function latent enthalpy 与 exact gas batch 体积推导；
water vapor 作为非 ambient 稳定气体组分。沸腾/冷凝每次只提交完整 transition batch，联合移动液体 SVQ/mass、
gas nanos/mass 与 `U`，并以精确全局余量反求 LTE cell energy。液体体积改变后立即重建空腔可用体积，膨胀/压缩功
在同一次逻辑操作返回前进入 mechanical-work ledger；无 receiver、容量、组分或状态函数有效解时不提交。

最近门：`thermal` 与完整 `gas-liquid` 通过；未请求实机。

## M9.15 ThermalContents v1 core

新增 canonical `ThermalContents v1` data component：只保存 authoritative thermal registry fingerprint、底层液体内容
kind/canonical SHA-256 与 raw 总 `U`。pure water、material mixture 和 chemical contents 各自按独立真值生成绑定 hash；
底层 volume/mass/composition 任一变化都会使旧热组件失配。热能按实际 nanovolume 拆分，剩余侧由原总量减去转移量，
不把温度或 tooltip 值用于结算；空容器和 dry granular 容器拒绝携带热真值。

最近门：`containers` 通过。WAL v5 与 production transfer 仍在后续联合增量完成。

## M9.16 container-transfer WAL v5 codec

container-transfer store 在有热事务时写 v5，否则 thermal-off 仍写 v4。v5 把 `FLUID_NANOS` 与 thermal registry
fingerprint/raw 转移 `U` 放在同一 WAL record，before/after item fingerprint 同时保存并复核 `ThermalContents v1`；
committed item 的能量差必须逐 raw bits 等于声明转移量。`GRANULAR_SVQ` 拒绝热列，缺字段、底层内容 hash 失配、
registry identity 不一致或 item/energy delta 不闭合均保留 WAL 并失败。

既有 v1--v4 fluid reader/recovery 保持，v1 material 缺独立质量仍锁定人工处置。最近门：`containers` 通过；
production transfer service 和 authoritative registry recovery 接线尚未在本增量开放。

## M9.17 protocol v5 display boundary

新增严格 protocol v5 与 `CAP_THERMAL_SNAPSHOT`。v5 内嵌 v2 material geometry，并可组合现有 v3 chemical 或
v4 granular+chemical 显示层；thermal 层只携 world registry fingerprint、逐格 16-bit 量化温度、8-bit coherent
fraction/debug flags 与有界 gas node 温压，不发送 raw `U`、source/sink/work 或 exact 相库存。客户端 ledger 联合拒绝
旧序列、未知 palette、thermal/chemical/granular fingerprint 变化与协商层缺失；accepted thermal 数据目前只作为
只读 frame，不改变服务端状态或默认材质颜色。

最近门：`protocol` 通过；默认 9,216-cell、含 v4+v3 组合层的 v5 payload 保持在 512 KiB 硬门内。

## M9.18 orthogonal thermal activity core

新增独立 `ThermalActivity`，不扩张 `FluidRegionLifecycle` 的 motion enum，也不修改 40/200 ms wall-time 阈值。
transport 现在报告实际 substep 中最大可表示能量传递；只有连续 quiet steps 内该值与 reservoir exchange 均为零，
且 chemistry/granular/phase/gas/pending work 全部 clean、audit 正常，才从 ACTIVE 经 REDUCED 进入 SLEEP。
boundary、container、admin、chemistry、granular、gas 或 phase 事件显式唤醒；PAUSED 保留可读失败原因。

最近门：`thermal` 通过，并证明 motion 已处于 `HYDROSTATIC_SLEEP` 时 thermal 轴仍可 step。production region/service
持有与事件接线在后续增量完成。

## M9.19 production registry and complete v6 wiring

server 启动现在实际消费 config v6 的 `thermalEnabled`：先加载/冻结 authoritative gas registry，再以当前 authoritative
chemical/granular fingerprints 加载或首次发布 thermal registry；已有 v4/v6 却缺 snapshot、未知布局、损坏或依赖 identity
失配均保持证据并全局 LOCKED。bundled definitions 覆盖三种液体、active granular carriers、air/water-vapor、coherent ice、
固液与液气 transition，以及所有活动 chemical host/species offset；启动验证不允许缺 carrier 或把缺 formation offset 当零。

新 controller 与开发域在 material/chemistry/granular 绑定完成后，以冻结标准初温 293.15 K 创建 gas topology 与每个库存的
权威 `U`。checkpoint 只发布完整 v6；恢复固定按 material/optional chemistry、optional granular、gas、thermal 顺序联合重建，
ownership recovery 也使用相同 registries 验证 HEAD。v1/v2/v3/v5 在 thermal on 时仍可被选中，但 restore 明确拒绝隐式赋温并
等待后续 operator initialization；thermal off 遇 v6 则保留并锁定。live protocol offer 已实际选择 v5 capability。

扩展既有 `region-state` 场景，覆盖 production coordinator 的 v6 checkpoint/reload、thermal-off 锁定以及旧 v2 不自动赋温。
最近门：`thermal,region-state,ownership,protocol` 通过；尚未完成显式旧世界初始化命令、production v5 snapshot body、
container transfer service、phase/dual-sleep tick 编排。

## M9.20 production protocol v5 snapshots

thermal-enabled service 现在在握手选择 v5 后实际发送 `FluidThermalSnapshotPayload`，不再回落到 v4/v3/v2 body。
solver 通过一次 LTE rebuild 生成只读量化 frame：每格 1/16 K 温度、coherent occupied fraction/debug flags，以及每个
canonical sealed gas node 的 1/16 K 温度和 10 Pa gauge pressure；empty cell 使用保留码。service 让内嵌 v3 或 v4 层
复用同一个 v2 material identity/sequence，并补齐 per-region removal 与 clear-all v5 tombstone。raw `U`、audit、exact
phase inventory 和 gas component truth 均未进入协议。

最近门：`thermal,protocol` 通过；默认材质仍不按温度改色，accepted thermal frame 只为 debug/后续展示服务。

## M9.21 production logical step and dual scheduling

production `FlipFluidDomain.step(phase,budget)` 现在按冻结顺序执行 mechanics/advection、conduction、M7 reaction、M8
solid-liquid reaction、M9 melt/freeze、M9 boil/condense、granular transitions 与 joint thermal reseed；phase-change 与
thermal transport 分别消费 config v6 的 operation/substep 硬预算。phase operation 由同一个总预算先分给 condensed，余量
再给 gas，不能各自重复消费上限。已有 `step()` overload 保持 thermal-off/测试调用不启 phase 的旧行为。

`FluidRegion` 持有正交 `ThermalActivity`。motion 进入 `HYDROSTATIC_SLEEP` 后，service 仍调用 thermal-only logical axis；
连续 200 个无可表示通量、reaction/phase/granular/gas/work/载体移动且 audit 闭合的 quiet step 才 thermal sleep。碰撞、
管理员/容器物质移动、reaction、granular、gas 与 phase activity 唤醒相应轴；thermal transport/phase 失败记录独立可读原因并
PAUSED，不修改既有 40/200 ms wall-time 阈值。status 报告 thermal activity、U、source/sink/work、coherent SVQ、audit，
gas 压力也改读非等温派生值。

最近门：`thermal,lifecycle,chemistry,granular,gas-liquid,region-state` 通过。本增量当时仍对 composition-bearing liquid
与同格 granular heat bath 显式 fail-closed；该限制随后由 M9.24 的 exact species/LTE joint plan 解除。

## M9.22 production container and collision energy transfer

液体 exact transfer 现在把总 `U` 作为体积、质量/组成之外的独立返回账本。注入可显式接收 item/collision 携带的
raw `U`，并在任何 marker 落地前用 prospective 完整库存求解 LTE 与状态函数有效域；提交时一次写入 energy ordinals，
不可表示的温度或预算拒绝保持零残留。舀取按实际 marker truncation 返回被移出的能量，不从 tooltip 或环境温度反算。

玩家和发射器的 production 路径严格验证 `ThermalContents v1` 与 world fingerprint。部分纯水、材料混合物和化学桶都按
实际 nanovolume 同时结算 underlying inventory 与 `U`；热区域拒绝缺失、损坏或 foreign thermal identity，非热区域也拒绝
吞掉带热真值的物品。controller 事务实际发布 WAL v5：PREPARED 保存注入请求上限能量或舀取占位，solver 后的
REGION_COMMITTED 保存实际 coupled energy、after item 与 v6 checkpoint checksum，HEAD 和 actor durable save 顺序不变。
恢复在 HEAD 判定前验证 authoritative thermal registry；创造放出沿用 unchanged after-item 表达并由 WAL 明确认作外部 source。

物品总能量只有一个 IEEE-754 raw double；部分桶与 carrier dyadic 和之间出现不可避免的 1 ulp 时，WAL 采用物品侧真实
`after-before`，区域 audit 把差值单独记为 roundoff，禁止伪装 source/sink。容器事件精确唤醒 thermal activity。
Minecraft collision relocation 也在既有删除循环中同步累计逐材料体积/质量、完整 chemical ledger 与液体 `U`，再用同一
exact insertion 原语恢复；失败才保留 escaped sink。该路径不再扫描全域 marker，也修正了非化学尾 marker 被整颗删除却只
报告部分体积的旧结算边角。

最近门：`thermal,containers,chemistry,shape` 通过；尚未请求实机。

## M9.23 explicit old-region thermal initialization

新增管理员选区入口 `/realwater regions thermalinit <temperatureK>`。它只接受 metadata-only、operator-paused 的
controller，要求 thermal/gas world registry 可用、ownership 无未决状态，并在 service 层硬拒绝任何未完成或不可识别的
container WAL。允许的源版本严格为 v1/v2/v3/v5；v4 gas 基态不被猜测升级。旧物质、可选 chemistry/granular 先按各自
authoritative snapshot 精确重建，再从当前 committed solver 几何扫描 gas topology、一次性记 ambient gas source，最后按
operator 给出的 raw 温度为所有 liquid/active/deposit/gas carrier 建立首个权威 `U`。

初始化记录使用独立 `realwater:thermal_initialization_v1` manifest extension，保存 source format、raw temperature bits、
`current_geometry_ambient_v1` gas bootstrap 和稳定 operator metadata；不修改冻结的 thermal payload v1、granular v5 或 gas v4。
完整 v6 先写 inactive slot、回读并联合重建验证，HEAD 最后切换。失败时若旧 HEAD 仍可确认则卸载临时 solver；HEAD 状态
不可确认则保留证据并 LOCKED。后续 v6 checkpoint 与重启 restore 都继承同一初始化记录。

扩展既有 `region-state` 门覆盖 v1/v2/v3/v5 四条初始化、记录继承/重启恢复，以及越界温度失败后旧 HEAD 与 unloaded
状态保持。最近门：`region-state` 通过；尚未请求实机。

## M9.24 composition-bearing condensed phase and granular LTE

凝固/融化 production planner 不再把 composition-bearing water 或同格 granular 当作永久 fail-closed 条件。冻结候选按
非 indicator marker 的 exact SVQ/mass 进行 deterministic split preview，同时生成 M7 kept/removed mixture、species quanta、
mass residual 与对应 thermal `U` 搬运计划；提交时 species/residual 从 liquid parcel 移入 coherent composition，属于内部相间
转移，不写 chemical 或 thermal external sink。融化对 coherent composition 做 exact proportional take，并生成新的非 indicator
liquid parcel，禁止按当前 datapack default 猜测回填。indicator-bearing marker 完整保留，不参与冻结分配。

候选相态温度现在以同格全部 liquid/coherent phase inventory 加 unchanged active parcel/deposit 共同反演 LTE；相变仍只消耗
完整 transition batch，并通过单调二分选择不越过平衡温度的最大 extent。容量预检联合 liquid、coherent 与 granular exact SVQ，
提交后的 cell energy 用全局 exact inventory 余量重分配，末位 double 误差只进入既有 roundoff ledger。删除旧的重复 inert
freeze/melt commit 路径，气液路径暂保持独立。

bundled thermal definitions 为冰相补齐水相允许的全部 13 个 species offset；相同 formation offset 保证潜热仍只来自 ice/water
material reference-energy gap，不引入物种特判。唯一 `thermal` fixture 扩展为 chemistry+granular 同格冻结/融化，验证跨液相与
coherent 的 species/residual、indicator 原始 volume/mass、颗粒 SVQ/mass、凝聚相总质量和 energy/audit 闭合。

最近门：`thermal,chemistry,granular` 通过；尚未请求实机。

## M9.25 checkpointable phase-volume work

凝聚相 freeze/melt 与 M8 precipitation/dissolution 的每次已提交体积变化现在同时生成 exact signed cell-volume event。SVQ
部分直接保存为 `integer * 2^-24`，液体 marker 的任意 float volume 则保存其精确 IEEE-754 dyadic 值；没有把后者反算或
取整成固体 SVQ。正膨胀的 planner 仍先以 unified occupied-volume capacity 限制 extent，避免用后续压力步骤掩盖越界。

pending event 使用独立 manifest-only `realwater:pending_phase_volume_v1`，只随完整 region-state v6 generation 发布；缺少
extension 是 canonical empty。codec 对 cell 顺序、cell count、dyadic canonical bytes、significand/serialized budget 和尾随字节
严格 fail-closed，region-state 还验证每个目标格在同代 liquid/coherent/active/deposit 中确有 thermal carrier。restore 不把记录
当作已完成，而是唤醒 motion 并保留到下一次真实 pressure projection。

mechanical step 先让已有 occupancy/volume-drift 进入共享 pressure solve；projection 成功后才以目标格 absolute pressure 计划并
提交 `-p delta V`，写 thermal mechanical-work/roundoff ledger，再清空 pending。它不额外注入第二份 divergence source，因而同一
相变既不会重复排水，也不会重复做功。pending 阻止 thermal sleep，checkpoint 允许保存，`empty` 明确拒绝。气液相变继续由
gas topology remap 当场结算 available-volume compression work，不生成第二份 condensed pending record。

`thermal` fixture 覆盖不等相体积冻结、投影前未记功、投影后 exactly-once 消费与 audit 闭合；`region-state` fixture 覆盖正负
dyadic record 的 codec/files/domain restore。最近门：`thermal,chemistry,granular,region-state` 通过；尚未请求实机。

## M9.26 composition-bearing gas/liquid phase transfer

production gas/liquid planner 不再调用 inert-only 拒绝器。气相 thermal material 是否具有某 species offset，现同时作为该 host
能够权威携带该 species 的 registry 声明；选择完全来自 world thermal snapshot，不按 water/H2O runtime ID 特判。bundled
water-vapor 新增与 liquid water 相同的 H2O formation offset，保持既有 latent gap 只来自 material state-function baseline。

沸腾使用 M7 exact binary-volume allocator 对非 indicator marker 做 selective split：gas-admitted species 与 residual 进入同一个
water-vapor component，未声明的离子/盐全部留液相；若完整删除会遗留非挥发 species，就保留一个 exact SVQ 的液相 carrier，
不足一个完整 transition batch 则本次不提交。chemical truncate commit 新增逐 species 闭合复核。冷凝从实际 component portion
读取 species/residual，只合并到非 indicator marker，否则新建 marker；chemistry 区域遇到 species-less legacy vapor 明确阻塞，
不按 default inventory 猜测恢复。

气液候选现在以最终 liquid inventory 加同格 unchanged active/deposit 共同反演 LTE，condensation capacity 也包含颗粒 SVQ。
gas node U、liquid/granular cell U、gas nanos/mass/species/residual、liquid mass/volume/species/residual 先联合验证再提交；随后既有
gas available-volume remap 结算 expansion/compression work，不生成第二份 condensed pending work。

一个现有 `thermal` fixture 扩展覆盖 production logical step 沸腾与直接冷凝：H2O 进入气相，Na+、indicator raw volume/mass 和
granular SVQ 保持，冷凝后 species/residual 返回且 gas/thermal audit 闭合。最近门：`thermal,chemistry,granular` 通过；尚未请求实机。

## M9.27 production contact geometry and fixed world reservoirs

production conduction 不再把非完整 cut-face 当成绝热墙，也不再用整格面积跨越自由面。每个 canonical face 的液体覆盖率由
现有 server PLIC 平面截面给出，active/deposit/coherent 的 exact SVQ occupancy 作为同格固相覆盖加入，两个 LTE cell 取共享
最小覆盖后再乘 cut-face aperture 与物理面积。thermal-only 轴在计算前强制刷新同一 grid geometry，避免消费陈旧 PLIC。

`ThermalBoundaryResolver` 现在把 authoritative registry 的 block/tag target 解析为 bounded runtime face field；unknown target、
缺失 tag 或 direct/tag/tag 重叠在启动时 LOCKED。方块扫描变化只更新派生面温度并精确唤醒 thermal axis，不进入 region-state。
domain 仅在实际 blocked cut-face 与有物质覆盖的面积上施加 fixed reservoir，交换量进入 source/sink。bundled
`minecraft:ice` 使用物理相平衡温度 273.15 K，普通方块继续默认绝热。

`thermal,cut-cell` 与随后 `thermal,shape` 最近门通过；fixture 覆盖 50% 微体素孔口的非零守恒导热，以及 production face
field 的一次变更检测、温度变化和 source/audit 闭合。尚未请求实机。

## M9.28 production source and failure-path closure

管理员 `materialbucket` 与 `reagent` 生成非空液体 source 时，如果 thermal 门开启，会从 authoritative thermal registry
按冻结标准温度 293.15 K 和物品已有的独立 mass/species 真值计算 total `U`，再绑定 `ThermalContents v1`；空桶、indicator/
eraser 工具和 dry granular container 不伪造液体热状态。由此 creative/debug 放出仍由 production WAL 记 material+energy source，
不再因缺少 energy truth 被正确但无用地拒绝。

非等温 gas topology remap、压缩或孔流一旦越出状态函数有效域，现在统一转为 `gas-thermal PAUSED`；自动 tick 与管理员
`/step` 都复用相同的安全暂停/诊断路径。没有修改气体方程、40/200 ms wall-time 门或任何 pacing 阈值。

调试 source 增量的 `thermal,containers` 与失败语义增量的 `thermal,gas-liquid` 最近门通过；完整 gas-liquid 门仍保持密闭压缩
0.91% 液柱误差、负压吸升 6.73% 误差与有限速率孔流账本闭合。尚未请求实机。

## M9.29 product entry and release candidate

补齐 `/realwater thermal [true|false]` 与 `/realwater phasechange [true|false]`，两者只原子修改下次启动配置；依赖链阻止
materials 仍被 chemistry/granular/thermal 使用时关闭，也阻止 phasechange 仍开启时关闭 thermal。管理员 `status` 现以
protocol v5 相同的 1/16 K 只读量化报告凝聚相温度范围，同时保留 authoritative U、source/sink/work、coherent SVQ、
pending work 与 audit；派生温度不参与任何结算。

主 Mod 升为 0.15.0 验收候选。2026-08-25 的 `milestone` 在同一 JVM 内通过当前全部 21 个短领域，runner 10.275 s；
随后 `thermal,protocol` 完整门再次通过。限定根项目的 EULA=true 专服以 thermal/phasechange 都开启启动，成功发布并读取
gas registry `41b54b…f777` 与 thermal registry `f402a2…6879`，命令查询确认两个 live gate 均开启并由 `stop` 正常退出。
`clean assemble compileTestJava --offline --no-daemon` 从零成功（27 s）；限定根项目的专服随后实际加载 realwater 0.15.0，
重读已发布 registry、确认两个 live gate 开启并由 `stop` 正常退出。候选 JAR SHA-256 为
`7CE4F7E6AC596695CA54C56EF1840408F8AB5EC2CC947C168493BA707788139A`；部署副本 hash 相同，原 0.14.0 已可恢复地改为
`.disabled`，mods 目录中主 `realwater` id 只有 0.15.0 一个启用 JAR（独立只读 `realwater_gpu_probe` id 不冲突）。真实客户端中的固定冰面换热、WAL v5
容器交互和 v6 跨重启仍按架构停止门合并为唯一一次人工 smoke，完成前不在进度索引宣告 M9 已验收。

## M9.30 legacy restore feedback and production phase stabilization

首次实机 smoke 在旧 region-state v2 checkpoint 3 上开启 thermal 后，`status` 仍报告 256 markers /
32.000 cells，但因缺少权威内能而不能发 protocol v5 thermal snapshot，因此客户端视觉上水面消失。
这不是 inventory loss，但原实现把受控迁移等待误报为可重试的 restore failure，也没有给玩家足够证据。
现在 v1/v2/v3/v5 先从 metadata 进入稳定 operator-required 暂停态：保留 HEAD 和预算，不周期重试、
不发布快照，每区域只警告一次，并向每个玩家一次性报告 checkpoint/marker/体积及唯一显式入口。
`thermalinit` 成功切换 v6 HEAD 后立即推送第一帧 thermal snapshot，暂停区域不再需要额外 `step` 或调试命令才恢复显示。
其他恢复损坏也在本次启动稳定锁定，不再造成 tick 级日志风暴。

将该真实世界复制到 workspace 后执行显式 272.5 K 初始化，production 相变路径又暴露并修正了五类非经验性错误：
微小 coherent carrier 吸收 floating conservation remainder 的选择不稳定；prospective exact batch 越出热定义有效域时误停整域；
恢复与相变把瞬态 liquid marker 聚集错当固定超容；marker raw float 尾数被强制视为 exact SVQ；
气液相变把同格 coherent solid 错当不兼容库存。修正后用最大热容载体吸收只保存在 double 层的精度余量，
对 marker 只计算保守 SVQ 上/下界，并把不可表示的局部候选留为未提交 no-op；已提交真值的异常仍按原契约 PAUSED/LOCKED。

世界副本在多次 checkpoint 与跨重启中累计推进超过 300 logical steps；最终路径从 v6 checkpoint 6 连续推进
240 步后发布 checkpoint 8，重启恢复 441 markers 又推进 20 步，audit 仍 closed，正常停服发布 checkpoint 9。
原实机世界的 HEAD/slot/registry/config 时间戳保持用户 06:49--06:50 的原值，本次调试没有修改它。
`thermal,gas-liquid,chemistry,granular,region-state,lifecycle,protocol` 七个直接受影响的完整门同步通过，
密闭气体平衡仍为既有 0.91% 正压液柱误差和 6.73% 负压吸升误差；没有修改任何 wall-time 或物理停止阈值。
第一次 `clean assemble compileTestJava --offline --no-daemon` 从零成功（19 s），生成稳定化候选
`86F2B2EC…16B21`；补齐初始化后立即发帧后再次从零成功（17 s）。最终部署 JAR SHA-256 为
`641DAC2F8D2F4056C79F9771A87F4E1C00B6D2911DA015026C1DF896CD77D75D`，且是 mods 目录唯一启用的主
`realwater` id。两版前置候选分别可恢复地保存为
`realwater-0.15.0-pre-legacy-restore-fix.jar.disabled` 与
`realwater-0.15.0-pre-immediate-thermal-snapshot.jar.disabled`；独立 `realwater_gpu_probe` id 不冲突。

## M9.31 real-controller sign-off and milestone closure

用户在真实 Minecraft 26.2 / Fabric 0.19.3 controller 世界确认最终候选的迁移与显示路径：原 v2 checkpoint 3
保存 4.00 BV / 32.00 cells / 256 markers；执行 `/realwater regions thermalinit 272.5` 后原子发布
v6 checkpoint 4，区域保持暂停且水体立即重新出现。随后 `status` 报告 272.50 K、
`U=-2.76479e+09 EU`、source/sink/work 全零、coherent 0、pending-work=no、audit=closed。
这同时证明无热真值时的视觉缺席没有删除库存，以及成功迁移后的首帧 protocol v5 快照无需额外 step。

按里程碑验收层汇总：

- 物理真值：thermal 与跨 M7/M8/gas 联合门保持体积、质量、组成、SVQ、gas nanos 和内能账本闭合；真实迁移前后 4.00 BV 不变。
- 数值离散：相变 exact batch、有限体积导热、压力/PLIC/cut-face 和气液平衡门无 NaN；完整气体门保持 0.91%/6.73% 既有误差。
- 渲染重建：用户确认 protocol v5 首帧恢复同一水体；温度/相分数仍只读，不反写物理。
- 调度性能：motion/thermal 双轴 lifecycle、休眠与唤醒自动门通过；40/200 ms pacing 阈值未改，代表性 profile 仍是独立技术债。
- 存档事务：v1/v2/v3/v5 显式初始化、v4/v6 严格分派、故障矩阵、世界副本 checkpoint 6→8→restart→9 与真实 v2→v6 HEAD 切换通过。
- 网络协议：protocol v5 capability、组合层、重连/清理和初始化后立即发帧通过；exact U 不上链。
- 游戏交互：真实 controller 跨重启与可见性通过；固定冰面 adapter 和 WAL v5 容器事务由 production fixture 覆盖，未把未执行的人工作业记为实机证据。
- 发布资源：thermal/phase 门、registry、命令反馈、既有中英资源打包、thermal-on 专服启动、两次 clean 构建与唯一启用 Mod id 均通过；最终 JAR SHA-256 为
  `641DAC2F8D2F4056C79F9771A87F4E1C00B6D2911DA015026C1DF896CD77D75D`。

最终树的 `milestone` 再次通过全部 21 个短领域，runner 9.622 s；此前直接受影响的
`thermal,gas-liquid,chemistry,granular,region-state,lifecycle,protocol` 完整门也已通过。未运行与本次关闭风险无关的
soak、GPU 或额外实机场景。M9 以主 Mod 0.15.0 正式关闭；下一产品里程碑为 M10。

## M9.32 operator clear audit hotfix

真实 controller 在凝固后暴露了关闭验收未覆盖的 `empty` 组合：固定冰方块被挖除只会移除 world-derived
temperature boundary，不会删除已提交的 coherent solid；这部分库存继续存在是预期语义。凝固生成的 pending
volume/work 也必须先由下一次真实 pressure projection 消费，所以 `pending-work=yes` 时拒绝 `empty` 仍是正确停止门。
用户关闭 phasechange、重启并执行一个机械步后，状态正确变为 `pending-work=no`，但旧实现随后在 checkpoint capture
报 `Thermal carrier handoff does not close its audit ledger`。

根因不是存档损坏：`WaterClearPlan` 错把包含 liquid、coherent、granular 与 gas 的 whole-domain `exactInternalEnergy()`
全部记为 liquid sink，却只删除 marker；coherent 与 gas 能量仍在库存，因此 handoff 必然不闭合。同时 M8 的 operator
clear 从未扩展到 M9 coherent phase，若只把能量选择改成 liquid-only，命令虽可发布却会留下连续冰相。

修复保持普通 liquid primitive 的职责不变：`removeAllWaterExact` 只计划/结算 marker U；管理员 `empty` 改为一个
domain-owned operator plan，联合预检 liquid/chemical、coherent composition/U 与 M8 granular sink，提交后立即按新
occupancy remap 保留的 gas truth，再发布同一完整 checkpoint。composition-bearing coherent group 只通过 world thermal
registry 中唯一的 solid→liquid transition 解析化学 material ownership；缺失或歧义时预检拒绝，不猜测。gas inventory
本身不被 `empty` 删除。命令同时允许 dedicated-server console 使用其明确 dimension，不再无意义要求 player entity。

保留的 `thermal` selector 新增一个凝固→消费 pending work→operator clear 夹具，并携 sealed thermal gas 验证最终
`exactInternalEnergy == audit.expectedInventory`。`thermal`、`chemistry`、`region-state` 与命令 `audit` 最近门全部通过。
随后从用户原世界只读复制 checkpoint 7：恢复得到 469 markers、29.92 cells、38096656 coherent SVQ、
`pending-work=yes`、audit closed；一个 phase-disabled mechanical step 后 pending 变为 no。修复后的 console `empty`
清除 3.739 BV liquid、109 coherent groups 和零 granular，原子发布 checkpoint 8；状态为 marker/coherent/U 全零且
audit closed。正常停服发布 checkpoint 9，第二次启动恢复 0 markers，仍为 `pending-work=no`、audit closed，并正常
发布 checkpoint 10。整个复现只修改 workspace 世界副本，用户原世界保持 checkpoint 7 不变。

最终 `clean assemble compileTestJava --offline --no-daemon` 从零成功（18 s）。部署 JAR SHA-256 为
`E61739D14AD34E26638B75AAB76F4A4348726743E83AF23B23FA1DB63D4AABE0`；部署后 mods 目录只有这一份启用的
`realwater` 0.15.0。上一候选 `641DAC2F…7D75D` 已可恢复地保存为
`realwater-0.15.0-pre-operator-clear-audit-fix.jar.disabled`；只读 GPU probe 仍是独立 `realwater_gpu_probe` id。

## M9.33 adjustable thermal reservoir test entry

新增 `realwater:thermal_reservoir` 作为显式 operator 放置的可调无限热浴。它以 vanilla block entity 的 raw
IEEE-754 bits 保存设定值，默认 293.15 K，同时属于合法 controller 闭壳；`ThermalBoundaryResolver` 在既有
world-registry 静态 block/tag 定义之前识别该唯一 code-owned 方块，并仍只把设定值转换为 runtime cut-face field。
修改设定值不直接修改 carrier U，实际交换继续由有限体积导热提交并进入 external source/sink audit。缺失、非 finite
或低于 1 K 的持久值不回退默认温度。管理员准星对准八格内方块后使用 `/realwater thermalsource [K]` 查询或设置；
没有增加准星流体温度查询或 HUD，Jade/WAILA/HWYLA 所需的局部派生温度 API 明确留给 M10。

最近 `thermal,boundary` 两门通过（runner 1.297 s）。限定根项目的 EULA=true 专服成功注册该 block/item/entity
和命令；隔离世界放置后读得默认 `temperature_bits=4643864765530531430`，保存、停服、重启后逐位一致，随后已删除
测试块并取消 force-load。`clean assemble compileTestJava --offline --no-daemon` 从零成功（16 s）。部署 JAR
SHA-256 为 `84BCA0555598D2DB0ABE78D558FE0893A1EDE63DC56A8D7199B25FE1946CB8F9`，mods 目录只有这一份启用的主
`realwater` id；上一版已可恢复地保存为 `realwater-0.15.0-pre-thermal-reservoir.jar.disabled`。真实 controller
上 source/sink 增长、温度变化和资源外观仍合并为一次人工 smoke，不在执行前记作通过。

用户随后在原 controller `7705a5f8` 的底部闭壳 `-4,-60,0` 完成该唯一实机 smoke。4.000 BV、256 markers
以 293.13 K / U=0 注入；100000 K reservoir 推进 20 步后仍保持 4.000 BV，U 增至 222578 EU、external source
由 2.56934 增至 222581 EU，派生范围升为 293.13..293.19 K 且 audit closed。将同一方块改为 1 K 再推进 20 步，
U 降至 221947 EU、source 不再增长、audit 仍 closed；约 631 EU 的冷源 sink 增量因既有约 -2.76479e9 EU 的
signed-reference 历史项和 status 六位有效数字而未在摘要中显出尾数，但由 U/source/work 不变量与 exact closed audit
共同闭合。最后 `empty` 成功清除 4.000 BV / 330 markers 并发布 checkpoint 14。可调热源、冷源、cut-face 输运、
source/sink 审计及带非零换热历史的 operator clear 因此通过真实世界验收；没有借本次验收新增局部温度 HUD。

## M9.34 closed v6 gas-history initialization hotfix

用户继续热学场景后，legacy v6 checkpoint 18 的显式初温被“gas truth tied to the legacy topology”拒绝。对原世界
只读解码确认该代仍保存 32.0 cells / 432 markers，但 gas 没有 node 或 section membership，mass/residual roundoff
均为零；仅有已经精确抵消的历史周转：air source=sink 180266917 standard-volume nanos，water-vapor source=sink
4005762 nanos。旧判定把任何非空累计账本都误当成 live topology truth，因而比冻结契约更严格。

修复没有放宽活气体迁移：只有 node 为空、全部 membership 因结构约束为零、两个 roundoff 为零且 source 与 sink
逐 component/mass/species/residual 完全相等时，才承认它是没有剩余库存的 closed historical turnover。新几何仍只由
operator 授权的 current-geometry ambient bootstrap 建立；旧周转随后同时加到新 source/sink，既不丢独立审计真值，
也不根据旧记录猜节点。任何 live node、不相等账本、roundoff 或 pending phase work 继续保留旧 HEAD 并拒绝初始化。

`air-network,region-state,thermal` 三个 routing 最近门通过；`clean assemble compileTestJava --offline --no-daemon`
从零成功（20 s）。从原世界只读复制 checkpoint 18 后，开发专服执行 `thermalinit 293.15` 原子发布 v7 generation 19；
自动卸载发布 checkpoint 20，重启并加载边界 chunks 后恢复 432 markers / 4.00 BV，status 报新 sealed gas node
`V0=60.00 m³`、累计 `drawn=60.18 m³ / vented=0.18 m³`、thermal ACTIVE、pending-work=no、audit=closed。
再执行一个 production step 后物质/能量审计仍 closed。原世界 HEAD 仍为 checkpoint 18，未被开发服启动或写入。

主 Mod 补丁版本为 0.15.2，部署 JAR SHA-256
`28B79A27C08DA36648F79552CDEDA0C82B611521FDB2B5A3811D8B8BCB1C8421`；mods 目录只有该主 Mod JAR 启用，
0.15.1 已可恢复地改名为 `realwater-0.15.1-pre-v6-closed-gas-history.jar.disabled`，GPU probe 仍为独立 Mod id。
