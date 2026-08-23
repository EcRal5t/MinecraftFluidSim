# M7 conservative chemistry state

本文冻结 M7 液相守恒化学的物理真值、定义顺序、预算和跨层版本边界。M7 不改变
[`fluid-solver.md`](fluid-solver.md) 的 CPU 权威关系；marker 体积、材料质量与化学库存是三套必须分别闭合的真值。

## 范围与功能门

- M7 只包含液相强电解质、同格且同 chemical phase 的完整解离/瞬时计量反应、只读游戏化 pH 与指示剂。
- `chemistryEnabled` 是独立启动门且要求 `materialsEnabled=true`。门关闭时不分配化学数组，M0--M6 的
  `region-state v1/v2`、protocol v1/v2、`FluidMixture v1` 与 container-transfer WAL v1/v2 字节和行为不变。
- M7 使用独立 `region-state v3`、protocol v3、container-transfer WAL v3。气体持久化如需实现必须另评
  `region-state v4`，不能借 M7 v3 携带。
- 气相化学、沉淀、热力学、GPU、wall-time 阈值、液液锐化与逐顶点法线均不在 M7。

## 化学真值

- `1 AU = 1,000,000,000 quanta`。每个 species 的绝对库存是非负 `long`；所有加、减、乘、汇总用 checked arithmetic。
- marker 保存 packed mixture handle 与 indicator bit。handle 0 表示没有化学状态，任何 v3 marker 必须引用有效非零 handle。
- handle 指向按 runtime species handle 排序的最多 16 个 `(species, amountQuanta)` primitive entry，以及
  `massResidual` 的 raw-double bits。marker 不持有 `Map`、list 或对象链。
- `particleMass = exact-rational species mass + massResidual`。reaction 只改变 species，不改 residual、体积、速度、
  material 或 phase；checkpoint 必须逐 raw bit 往返 residual。
- 无外部源汇时 species、组成基元和电荷按 `long` 精确闭合；reaction 前后 exact species mass 相等。重分配保持格内
  marker 总质量，误差不超过 `max(1e-9 MU, 8 ulp)`。
- insert、extract、empty、collision displacement/escape 都返回完整 chemical transfer ledger。不存在“删除后按默认水补回”。

## 定义与确定性

数据路径固定为 `data/<namespace>/realwater/species`、`chemical_phases`、`reactions` 下的 JSON；stable ID
由资源 namespace/path 得到。数值用 exact rational 字符串：整数、有限小数或 `numerator/denominator`，解析和守恒校验
不得经过 `double`。

registry 对 stable ID 排序后分配 runtime handle，以 canonical UTF-8 bytes 计算 SHA-256 fingerprint。首次启用化学时
原子发布 `<world>/realwater/chemistry/registry.nbt`；之后 snapshot 是已有库存的唯一解释。数据包定义变化只产生 mismatch 报告，不重解释
存量。未知/损坏 snapshot、v3 存在而 snapshot 缺失、fingerprint/identity/generation 失配一律保留并全局 `LOCKED`。

loader 在发布前拒绝未知引用、重复 ID、负库存/系数、组成/电荷/exact mass 不守恒、非零体积变化、完整解离环、
竞争瞬时消费者和非终止图。complete dissociation 按确定性拓扑序先运行，之后运行无竞争 instantaneous reaction；M7
首批瞬时反应是 `H+ + OH- -> H2O`。

每个外部 solver step 仅在最终 advection 后、reseeding 前执行一次 chemistry step。marker 按 `(cell, chemical phase)`
聚合；不同 chemical phase、相邻格和墙后库存不得反应。indicator 不隔离反应，重分配后仍附着原 marker。逐 species
使用 marker 二进制精确体积权重 largest-remainder，余数按 marker ordinal 决胜，不依赖 hash、文件或遍历顺序。
若逐 species 独立分配使极小 marker 的所有项同时舍入为零，则按 marker ordinal 对空 parcel 排序，从当前总量最大的
非空 parcel（再按 ordinal）搬运其最大 species 项的一枚既有 quantum；该最小确定性修复不改变任何 species 总账，且只在
维持“每个 live marker 必须有非空 parcel”所必需时执行。反应后总 quanta 少于 live marker 数时仍整体拒绝。

pH 是只读展示：`C=|H+-OH-|/BV`、`C0=10^-6 AU/BV`、`strength=min(7,log10(1+C/C0))`；酸为
`7-strength`，碱为 `7+strength`，相等为 7。indicator=0 时 pH 不参与颜色。

## 硬预算

| 项目 | 上限 |
|---|---:|
| species / composition bases / chemical phases / reactions | 256 / 64 / 64 / 512 |
| composition / charge / stoichiometric coefficient 绝对值 | 64 / 16 / 64 |
| parcel species entries | 16 |
| region live handles / packed entries | `reservedMarkerCapacity + 1` / `16 * handle cap` |
| logical-step reaction evaluations | 131,072 |
| region / bucket total quanta | 2,000,000,000,000,000 / 1,024,000,000,000 |
| bucket groups / species entries / component bytes | 16 / 64 / 64 KiB |
| indicator 添加 / 扫描 | 100 BV / region 完整 cell budget |
| protocol v3 visible species | 每格 Top 4，其余只形成展示 residual |

任一 plan 的 pool、entry、operation、scan 或算术预算失败时不得提交部分 parcel 修改；区域进入带可读原因的
`chemistry-blocked PAUSED`。预算失败不是调度超时，不能改现有 40/200 ms pacing。

## 跨层格式

- 内存 handoff `FlipChemicalParticleState` 组合 material handoff、registry fingerprint、canonical mixture table、
  per-marker table index 和 indicator bits；它不是 wire/save codec。
- v3 manifest 保存 fingerprint、AU scale、stable species palette、canonical mixture table 与 region species/residual ledgers；
  section 保存 per-marker mixture index、indicator bit和 section ledgers。累计 external-source/escaped-sink 通过有界 canonical
  manifest extension 持久化并在恢复时复核。零 marker 化学域的 canonical mixture table 必须为空；非空域必须至少一项，且
  每个 marker 仍须引用有效 table index。HEAD 仍是最后且唯一提交点。
- material bucket 的 legacy 表示（`material_id`/`mixture_components`）与 chemical 表示（总 volume/mass +
  `ChemicalContents v1`）互斥。双表示、foreign fingerprint、超预算或账本失配保持物品不变并拒绝。
- protocol v3 只发 bounded display palette、Top-4 权重、量化 pH/indicator fraction；不发 exact AU、residual 或权威 ledger。
- WAL v3 固定为 `PREPARED(before)` → solver 修改 → stage v3 checkpoint → `REGION_COMMITTED(after/checksum)` → 发布 HEAD
  → actor after → durable save → 删除 WAL。gate 关闭、actor 未加载、registry/checksum/generation 歧义都保留并锁定；
  v1/v2 reader/recovery 不变。

## 玩法与只读展示

legacy 材料桶倒入化学域时，新增 marker 明确绑定该 material 的 registry default carrier；空材料桶从化学域抽取时生成
`ChemicalContents v1`。原版空桶只允许抽取未标记且只含规范 H2O carrier 的 parcel。创造放出累计 external source，创造装取
仍替换手中容器，不产生隐藏 sink。

通用指示剂与可重复使用抹除器按正面积 cut-face 和 compatible chemical phase flood fill；扫描上限为 region 完整 cell
预算，添加上限为 100 BV，任何超限都在修改前整体拒绝。后来混入的 marker 保持自身 indicator provenance。

客户端只从 material、bounded species palette、pH 与 indicator fraction 派生颜色；indicator 为零时 pH 不改变颜色。
`/realwater chemstatus` 是管理员按需的完整逐 species 观测并进入 transcript，display snapshot 和 tooltip 都不参与结算。

## 兼容与提交

v1/v2 到 v3 只在非活动槽生成完整新代，回读验证后发布 HEAD；崩溃后只能看到旧完整代或新完整 v3。化学门关闭发现
v3 时保留并锁定，不降级。unknown/corrupt/over-budget 永不通过“合理默认”修复。ownership sidecar 与 HEAD 语义不变。
