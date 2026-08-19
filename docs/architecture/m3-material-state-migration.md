# M3 material snapshot and persistence migration

本文冻结 M3 多材料跨层状态的兼容边界，不表示协议或 `region-state v2` 已经实现。活动 CPU 真值仍见
[`fluid-solver.md`](fluid-solver.md)，已发布的纯水事务语义仍以 [`region-state-v1.md`](region-state-v1.md) 为准。

## 不可混用的状态

- marker `particleVolume` 与显式 `particleMass` 分别是体积、质量真值；稳定 material ID 决定逐材料账本归属。
- 运行时 material/phase handle、格内相分数、dominant label、平滑 density 与 mesh 都是可重建状态，不进入物理账本。
- M3 phase 由稳定 miscibility-group key 重建；当前进程内整数 handle 不写入网络或存档。
- 正式世界入口在 v2 reader/writer、故障门和 feature gate 完成前仍只创建纯水。

## 网络迁移

现有 `fluid_snapshot_v1` packet ID 与 codec 保持逐字节不变。独立 `fluid_snapshot_v2` codec/type 与 material
capability 已通过 standalone 门，但 live server offer/receiver 仍固定 v1，等待 controller feature gate；不能在 v1
的可选 extension 中藏入客户端必须理解的材料字段。

- v1 只表示 pure-water palette。协商到 v1 的客户端可以继续观察纯水区域。
- v2 palette 使用稳定 material ID 和展示属性；per-cell phase/material 重建字段是量化展示数据，不携带精确质量。
- 服务端若存在启用的多材料玩法，缺少 v2/capability 的连接必须在配置阶段得到可读拒绝，不能把多材料区域降级成蓝色水。
- v1/v2 均保持 region UUID、generation、sequence 与 tombstone 排序；未知 palette 或必需段拒绝整份快照。
- 客户端只读消费 palette/相字段，绝不回传或结算物理库存。

## `region-state v2` 迁移

HEAD 的 envelope、双不可变槽和“最后原子发布 HEAD”保持不变。格式版本属于 manifest/section generation；
reader 按 manifest version 分派，不能根据缺失字段猜版本。

v2 manifest 在 v1 元数据之外必须包含：

- 有序的稳定 material palette：material ID、miscibility-group key、参考密度 raw bits；
- 精确总质量 raw bits，以及按 palette 顺序的 marker count、体积和质量账本；
- 每个 section descriptor 的 marker count、体积、质量和完整 section checksum。

v2 section 在 v1 marker 位置/体积、速度和 solid slice 之外必须包含每 marker material palette index 与
`particleMass` raw bits。运行时 phase handle 不持久化；加载后由 palette 的 miscibility-group key 确定性重建。
所有必需字段都进入 section/manifest checksum，不使用可跳过 extension 承载物理真值。

上述字段已先在不可变 `FlipMaterialParticleState` 内存 handoff 中冻结并通过导出/恢复账本门；它不是 codec，
`region-state v2` 仍必须独立执行长度、checksum、generation、palette 与预算校验。

## 发布、失败与兼容

1. v1 纯水 generation 可继续读写 v1，不因安装新版本自动改盘。
2. v1→v2 时，每个 marker 显式映射到默认水 material；质量由 marker 体积与冻结的 v1 solver 密度生成，并在写盘前回和。
3. 完整 v2 写入非活动临时槽，force、读回并校验身份、generation、palette index、逐材料/总账和预算后，才原子发布槽。
4. HEAD 仍是唯一 commit point。故障后只能读到旧完整 v1 或新完整 v2，不能选择未被 HEAD 指向的“较新”槽。
5. 未知版本、损坏/缺失 palette、定义冲突、混合 generation、checksum/账本不符或超预算状态必须保留并锁住区域。
6. 旧 Mod 遇到 v2 会按现有 unknown-version 路径锁住，不能覆盖。v2 不支持静默降级回 v1；需要显式导出/迁移工具。

只有 controller-managed 区域可进入 v2。`demo`、`capture` 与 `takeover` 继续瞬态，避免与原版水恢复重复。

## 实施顺序与门

1. 先实现不涉及网络/存档的只读多材料内存快照，并证明外表面与内部界面字段最小充分。
2. 独立 network v2 codec/ordering/capability 门已完成并保留 v1 round-trip；尚未注册 live play 路径。
3. 下一步新增 region-state v2 records/codec 与 v1→v2 双槽故障矩阵；在此之前不允许正式多材料 checkpoint。
4. 最后接入 controller feature gate 和客户端 mesh；自动几何门通过后只安排一个明确的实机视觉场景。
