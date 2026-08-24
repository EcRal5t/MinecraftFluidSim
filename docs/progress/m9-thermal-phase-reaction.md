# M9 temperature, phase change and reaction heat

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
