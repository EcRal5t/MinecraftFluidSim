# M3 material snapshot and persistence migration

本文冻结 M3 多材料跨层状态的兼容边界。协议 v2、`region-state v2` 与客户端界面渲染都已实现，并统一由
`materialsEnabled` 配置门控制；门关闭时全部路径严格退化为纯水 v1。活动 CPU 真值见
[`fluid-solver.md`](fluid-solver.md)，纯水事务语义仍以 [`region-state-v1.md`](region-state-v1.md) 为准。

## 功能门

`materialsEnabled` 保存在世界配置里，配置格式自身有版本：v1 配置继续可读并强制 `materialsEnabled=false`，保存时写 v2。

- 门只在服务器启动时读取一次。`/realwater materials <bool>` 只原子保存“下次重启生效”的配置，绝不在现有连接中途切换协议。
- 门关闭：live offer 严格 v1，region-state 只写 v1，活动核心只走纯水路径。
- 门开启：live offer 只提供 protocol 2 且要求 material capability，控制器区域使用 `region-state v2`，客户端重建 partitioned mesh。
- 门开启但磁盘上是 v1 的控制器区域，在非活动槽原子迁移为单材料水 v2 后才恢复 solver；门关闭却发现 v2 时保留并锁住该区域，不静默丢弃。

## 不可混用的状态

- marker `particleVolume` 与显式 `particleMass` 分别是体积、质量真值；稳定 material ID 决定逐材料账本归属。
- 运行时 material/phase handle、格内相分数、dominant label、平滑 density 与 mesh 都是可重建状态，不进入物理账本。
- M3 phase 由稳定 miscibility-group key 重建；当前进程内整数 handle 不写入网络或存档。
- M6 后正式 controller 桶入口可精确装取/放出已注册材料与 canonical 混合物；ownership acquire、原版方块采样与瞬态 takeover 仍只把原版水解释为纯水。多材料容器与跨持久域事务见 [`world-ownership-and-containers.md`](world-ownership-and-containers.md)。

## 网络迁移

现有 `fluid_snapshot_v1` packet ID 与 codec 保持逐字节不变。`fluid_snapshot_v2` 是独立 codec/type，携带材料
palette 与量化展示字段；两种 payload 都注册，但发送由协商会话隔离，不做降级伪装。

- v1 只表示 pure-water palette。协商到 v1 的客户端可以继续观察纯水区域。
- v2 palette 使用稳定 material ID 和展示属性；per-cell phase/material 重建字段是量化展示数据，不携带精确质量。
- 门开启时缺少 v2/capability 的连接在配置阶段得到可读拒绝，不能把多材料区域降级成蓝色水。
- v1/v2 均保持 region UUID、generation、sequence 与 tombstone 排序；未知 palette 或必需段拒绝整份快照。
- 客户端只读消费 palette/相字段，绝不回传或结算物理库存；v1 与 v2 各自持有独立 ledger、状态 map 和清理路径。

## `region-state v2` 迁移

HEAD 的 envelope、双不可变槽和“最后原子发布 HEAD”保持不变。格式版本属于 manifest/section generation；
reader 按 manifest version 分派，不能根据缺失字段猜版本。v1 的 canonical bytes 与 CRC 路径未被触碰：
完整性编码已把 Version/Palette 固化进 CRC，v2 因此是显式分支而不是 v1 的扩展。

v2 manifest 在 v1 元数据之外包含：

- 有序的稳定 material palette：material ID、miscibility-group key、参考密度 raw bits；
- 精确总质量 raw bits，以及按 palette 顺序的 marker count、体积和质量账本；
- 每个 section descriptor 的 marker count、体积、质量和完整 section checksum。

v2 section 在 v1 marker 位置/体积、速度和 solid slice 之外必须包含每 marker material palette index 与
`particleMass` raw bits。运行时 phase handle 不持久化；加载后由 palette 的 miscibility-group key 确定性重建。
所有必需字段都进入 section/manifest checksum，不使用可跳过 extension 承载物理真值。

上述字段已先在不可变 `FlipMaterialParticleState` 内存 handoff 中冻结并通过导出/恢复账本门；它不是 codec，
`region-state v2` 仍独立执行长度、checksum、generation、palette 与预算校验。

账本校验的数值边界：总账与逐材料账本都按原始 marker ordinal 精确求和后逐位比对。禁止要求“逐材料子账再相加”
逐位等于总账——浮点加法不满足结合律，重排求和顺序会得到不同 raw bits。因此 section 与 manifest 各自按 ordinal
重算，而不是互相二次求和；校验也不只依赖 checksum：重算 CRC 后篡改单 marker 质量仍会被精确账本本身拒绝。

## 发布、失败与兼容

1. v1 纯水 generation 可继续读写 v1，不因安装新版本自动改盘。
2. v1→v2 时，每个 marker 显式映射到默认水 material；质量由 marker 体积与冻结的 v1 solver 密度生成，并在写盘前回和。
3. 完整 v2 写入非活动临时槽，force、读回并校验身份、generation、palette index、逐材料/总账和预算后，才原子发布槽。
4. HEAD 仍是唯一 commit point。故障后只能读到旧完整 v1 或新完整 v2，不能选择未被 HEAD 指向的“较新”槽。
5. 未知版本、损坏/缺失 palette、定义冲突、混合 generation、checksum/账本不符或超预算状态必须保留并锁住区域。
6. 旧 Mod 遇到 v2 会按现有 unknown-version 路径锁住，不能覆盖。v2 不支持静默降级回 v1；需要显式导出/迁移工具。

只有 controller-managed 区域可进入 v2。`demo`、`capture` 与 `takeover` 继续瞬态，避免与原版水恢复重复。

## 已通过的跨层门

1. 只读多材料内存快照证明外表面与内部界面字段最小充分（`runCpuValidation -PcpuValidation=materials`）。
2. network v2 codec/ordering/capability 与门控 live 协商，且保留 v1 round-trip（`runCpuValidation -PcpuValidation=protocol`）。
3. `region-state v2` records/codec、v1→v2 双槽跨格式故障矩阵、重算 CRC 后的质量篡改拒绝，以及 feature-on
   控制器的 v1→v2 恢复与新 v2 checkpoint 生命周期（`runCpuValidation -PcpuValidation=region-state`）。
4. 客户端 partitioned mesh 与瞬态 `/realwater materialdemo` 入口；渲染外观已由实机视觉场景确认，结果与已知限制见
   [`../progress/m3-multi-fluid.md`](../progress/m3-multi-fluid.md)。

后续阶段修改这些边界时，仍只跑 [`../maintenance/routing.md`](../maintenance/routing.md) 指定的对应一项门。
