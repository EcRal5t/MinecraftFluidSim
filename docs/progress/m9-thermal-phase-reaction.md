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
