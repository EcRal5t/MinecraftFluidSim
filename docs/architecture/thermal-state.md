# 内能、热输运与相变契约

## 权威真值

- 液体 marker、active parcel、deposit group、coherent condensed group 和 sealed gas node 分别保存总内能 `U`。
- world thermal registry snapshot 保存材料 caloric segment、参考能、导热系数、有效温区、species offset、transition 和固定温度边界定义。
- external energy source、sink、mechanical work 与 reaction/phase transfer 都有独立审计列。
- 温度、热容、焓、密度修正、颜色和 tooltip 由状态函数派生，不持有或修复库存。

凝聚相 solver mass weight 与物理 kg 只通过 `CondensedMassScale` 的 `cellSize³` 边界换算。气体质量已经是 kg，不再缩放。

## 状态函数

每种载体用分段 caloric definition 计算 `U(T)` 并反演 `T(U)`。同格异质载体使用 bounded LTE common-temperature inversion；状态函数不得读取客户端数据或经验 tick 倍率。

候选状态必须同时满足：

- 所有材料的温度有效域；
- transition 的 pressure/temperature 有效域；
- 有限、正的热容量与密度；
- 精确库存和能量审计闭合。

越界时报告载体、候选温度/压力和有效区间并安全暂停，不能钳制设定值或丢弃能量。

## 热输运

- 导热使用有限体积 face flux，同一 face 的两侧能量变化严格相反。
- 流液接触面积来自 PLIC/cut-face；solid、deposit、coherent 和 gas 使用各自实际 occupancy/available volume。
- 普通世界边界默认绝热。registry 声明的 fixed-temperature 方块和 `thermal_reservoir` 是无限热浴，但只经相邻 face flux 进入 source/sink。
- 液体热膨胀使用 registry-bound Boussinesq 定义，只向现有 y-face 动量路径提供派生体力，不改变质量或体积真值。
- 气体孔流同时搬运组分与能量；压缩/膨胀功进入 mechanical work。

## 相变

相变门依赖热学门。每个 transition 用完整最小守恒量子定义，运行时按可用能量、库存、目标容量、饱和条件和统一操作预算求最大整数 extent。

### 固液

- 联合搬运物理 kg、condensed weight、液体 marker、coherent SVQ、材料身份、可选 chemical composition/species 和 `U`。
- 体积变化与机械功写入 pending phase-work，由下一次 pressure projection exactly-once 消费。
- 当前固液平衡温度使用绝对压力有效域作为安全门；压力曲线尚未数据化，见产品路线。

### 气液

- liquid water 与 water vapor 使用同一个 transition 的质量、潜热和 exact standard-volume batch。
- 沸腾/凝结由 water-vapor 分压、温度和 transition 饱和压力关系决定，并受 sealed/ambient gas inventory 容量约束。
- commit 同时更新 liquid marker、gas component nanos/kg、gas node energy、source/sink 与 topology remap；任何列不闭合则整批拒绝。

## 反应热

化学和固液反应先完成 exact extent 规划，再将定义的能量变化提交给同一 cell 的热载体。反应热不得绕过 chemical/solid 账本单独修改温度。

## Logical step 顺序

1. mechanics、pressure 与 advection；
2. carrier handoff 和有限体积 conduction；
3. 化学与固液 reaction extent；
4. reaction heat；
5. 固液和气液 phase transition；
6. granular transition、reseed 与局部唤醒；
7. 审计闭合后，pending phase-work 留给下一 projection。

## 持久化与容器

- 完整热区域 generation 必须联合保存 marker/solid/gas topology、所有载体 `U`、audit、Boussinesq identity 和 pending phase-work。
- capture 前执行 gas topology synchronization，确保 marker geometry、gas membership 和 gas energy ordinal 同代一致。
- `ThermalContents` 将物品中的液体物质真值与同一 registry fingerprint 下的总内能绑定；温度 tooltip 只读。
- 容器 WAL 在含热转移中同时保存 exact liquid amount、item before/after 和 coupled energy；物品 durable save 前不得清理。

## 调度与观察

motion 和 thermal activity 是正交轴。只有温度残差、边界通量、反应/相变或局部几何变化达到唤醒条件时才推进热学；休眠不得改变任何库存。

`status` 可读取全域温度范围、总 `U`、source/sink/work、phase batch 和气体节点摘要。局部温度由未来外部观察 API 提供，查询不得唤醒或回写热状态。
