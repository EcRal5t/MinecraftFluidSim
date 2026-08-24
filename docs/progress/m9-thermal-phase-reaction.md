# M9 temperature, phase change and reaction heat

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
[`../architecture/thermal-state.md`](../architecture/thermal-state.md)。M9 开始于已验收主 Mod 0.14.0；CPU 仍是规范后端。

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
