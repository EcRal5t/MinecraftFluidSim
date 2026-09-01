# 颗粒与沉积状态契约

## Registry 与功能门

颗粒门默认关闭并依赖材料门；沉淀还依赖化学门。world granular registry snapshot 保存 stable material/carrier/reaction ID、exact extent/SVQ batch、化学依赖和 fingerprint。未知、损坏或 identity mismatch 保留并 LOCKED。

区域载荷中的 granular 与 chemistry presence 必须与启动时冻结的门一致；不从其他布局构造空颗粒层。

`ParticulateMaterial` 至少固定以下计算定义：颗粒密度、代表粒径、孔隙率、安息角、静/动摩擦系数、恢复系数、临界 Shields 数、侵蚀率、单 parcel 容量、显示信息和 reactive 标志。`GranularCarrier` 将液相材料绑定到动力黏度与允许的最大 Reynolds 数。注册表验证所有范围和引用后冻结；运行时只使用冻结 ordinal，不按数据包名称重复解析。

当前硬上限为：256 个 particulate material、512 个 carrier、512 个 reaction；单 region 16384 个 active parcel、单 server 65536 个；单 cell 8 个 deposit group、单 region 65536 个、单 server 262144 个；solid composition 最多 16 项；每 logical step 131072 次颗粒操作。单 active parcel 最大为 `cell SVQ / 64`。预算不足时延期、拒绝或暂停，不能合并不同 truth 来腾位置。

## 独立真值

- solid volume 使用整数 SVQ；每种材料质量以 kg 独立保存。
- reactive solid composition/species 与机械材料身份分开保存。
- active parcel、deposit group 和 coherent condensed phase 是不同载体，不互相替代；active parcel 的连续支撑接触年龄与支撑标志是沉积状态机的权威侧列。
- deposit 保存 cell、材料、SVQ、质量、组成、porosity 和孔隙水相关状态。
- external source、escaped sink、boundary impulse 与反应审计独立闭合。

显示高度、颜色、active sample、bulk fraction 和 porosity tooltip 都是只读派生，不能结算库存。

## 数量、布局与容量

- `1 BV` 是一个 Minecraft 方块体积；生产子格边长为 `0.5 m`，所以每格为 `0.125 BV`。
- solid volume 以 `SVQ` 整数记账；SVQ 与 BV 的换算常量在存储、网络和容器中一致，禁止先转 `double` 再结算。
- active parcel 使用结构分离数组保存位置、速度、材料 ordinal、SVQ、逐材料 kg 质量、组成 handle、连续支撑接触年龄、支撑标志、内能和确定性身份。数组 ordinal 是同代 side column 的共同键。
- deposit 以 cell 为定位键，cell 内是有硬上限的规范化 group 列表；相同机械身份、组成和孔隙率的 group 才能合并。
- coherent condensed group 属于相变层，具有自己的 cell、SVQ、质量、组成和内能；它不占 active parcel 或 deposit 的容量。

孔隙率 `phi` 描述骨架体积中可由孔隙水占据的比例。由 solid skeleton volume `Vs` 派生 bulk envelope 时使用

```text
Vbulk = Vs / (1 - phi)
Vpore = Vbulk - Vs
```

实现用定点数向安全方向取整。`Vbulk` 参与 cell 可用体积和压力投影，`Vs` 仍是固体库存；二者不能互相回填。孔隙水始终是普通液体 marker 真值，deposit 只提供可用空间，不保存一份隐含水量。

## 生产模型

生产模型是低成本 active parcel + deposit cell：

- active parcel 接受重力、浮力、阻力、压力和 cut-cell 碰撞；
- solid occupancy 与 liquid marker occupancy 共享统一容量、排水和压力耦合；
- 沉积使用材料安息角、邻域支撑与确定性转移；
- deposit porosity 决定孔隙水空间，但不会隐式创建或删除液体；
- 平静库存可休眠，局部碰撞、液流、边界变化或反应只唤醒受影响邻域。

不预建 MPM 或通用刚体/弹性框架。只有量化证明当前模型越过既定成本或质量停止门，才单独评审替代模型。

### Active parcel 力学

parcel 的物理质量列直接使用 kg，SVQ 通过 `SVQ_PER_BV` 直接给出 m³；两者不依赖 liquid solver cell 的质量标度。每步先从同格液相采样速度、压力梯度、密度和载体黏度，再计算重力、浮力和阻力。颗粒反作用冲量只在写入 liquid MAC grid 时经 `SolverUnitScale` 换成 solver momentum。球形颗粒的相对 Reynolds 数为

```text
Re = rho_f * d * |u_f - u_p| / mu
```

阻力使用 Schiller–Naumann 相关式：`Re < 1000` 时 `Cd = 24/Re * (1 + 0.15 Re^0.687)`，更高时取其常用高 Reynolds 极限 `0.44`。参与阻力的代表颗粒数按同一格的实际浸没比例缩放；不能让一小层液体对整份 parcel 施加满浸没阻力，再把该反冲写入同一小层液体。同格有限液体质量按各 parcel 的浸没固体体积分配，parcel 与该载体份额做两体指数松弛；因此拆分等价 parcel 不改变总耦合，合计反冲不越过共同动量平衡点，阻力也不能凭显式叠加增加系统动能。超过 carrier 声明的有效 Reynolds 数、非有限物性或负容量会安全暂停，而不是钳制到一个看似稳定的值。

浮力和阻力对 parcel 的冲量有等量反向项写回液体动量；cut-cell 无法承接的部分进入 boundary impulse ledger。碰撞使用与流体一致的几何快照、连续碰撞检测和材料摩擦/恢复定义，不依赖渲染表面。

### 统一排水与压力

每个 logical step 从 active、deposit bulk envelope 和 coherent store 构造统一 solid occupancy。occupancy 的时变差进入压力方程目标散度，固体移动造成的排水因而与液体不可压缩约束在同一次 projection 中闭合。可用体积为几何开放体积减去固体 bulk envelope；负容量、重复占用或未消费的相变体积功都拒绝提交。

projection 消费的 phase volume/work 和 granular occupancy delta 都具有 generation 身份；重试不能再次消费，checkpoint 也不能只保存其中一半。

backend/checkpoint handoff 必须逐列保存 active parcel 的接触年龄与支撑标志；它们决定尚未完成的 active→deposit 状态转换，不能在每次候选交接时清零。恢复后的容量验证必须重新使用运行时 `GranularCouplingFields` 的同一 positioned-parcel exact attribution：active parcel 按其连续位置的最多八格权重分摊，deposit 与 coherent fixed occupancy 走各自的 cell-local 归属。不得在恢复门把整份 active parcel 临时塞进 floor cell；否则一个运行时合法 generation 会被另一套投影误判为过容量。恢复门只验证统一 solid occupancy 与几何容量，不能把 liquid marker 的短时聚集改写成 solid 库存。

### Deposit 松弛与局部唤醒

- active parcel 连续受到支撑且速度低于阈值满 3 次接触后，才原子转入 deposit；转换保持 SVQ、质量、组成和内能不变。
- deposit 只在 dirty column 及相邻列执行确定性安息角松弛；邻居起点按 generation/cell 旋转，避免固定方向偏置。
- 单次形态转移有严格 SVQ 上限，直到局部坡度不超过材料安息角或预算耗尽；预算耗尽保留 dirty 状态到后续 step，不丢库存。
- 液流的 bed shear 以 Shields 判据与材料阈值比较；越阈值时按 registry 侵蚀率把 exact SVQ 唤醒为 active parcel。
- 无扰动列连续 20 个 logical step 后休眠。几何变化、parcel 碰撞、压力/液流、相变或反应只标脏受影响邻域。

dirty queue、安静年龄和邻居轮转步不是库存，不能用于恢复 SVQ/kg/组成；但它们决定有界局部工作是否完成，必须随每次 immutable backend handoff 交给私有候选，否则每 tick 重建会让沉积列永远无法休眠。region HEAD 冷恢复不信任这些调度值，而是从 committed deposit 重新标脏并完成一次安全重检。

这里的 deposit 是低成本地貌松弛，不声称求解连续颗粒应力、力链或弹塑性。若 fixture 显示安息角、排水或侵蚀误差越过产品停止门，应先量化失败机制，再评审更重模型。

## 固液相转移

- precipitation/dissolution 由 registry 的 exact reaction extent 规划，同时验证 liquid species、solid composition、质量和体积容量。
- commit 必须联合更新 chemistry、solid inventory、source/sink、热能和相变 volume/work；任一候选失败则整批不落地。
- 连续凝固相由 coherent store 保存；熔化后恢复到液体 marker。它不借用 active/deposit 表示。
- pending phase volume/work 由下一次 pressure projection exactly-once 消费；未消费时拒绝 `empty` 和不安全 checkpoint 操作。

reaction/phase candidate 必须先在不可变快照上规划 exact extent，再一次提交所有 side column。规划预算以 cell、reaction 和 batch 操作计数，有硬上限；预算耗尽延期，不允许把半个 extent 留在液相、另一半留在固相。marker 的 binary32 体积列也是候选表示格：优先结果若不能把总 solid SVQ 精确分配到当前 marker，规划器必须确定性重分配或按完整 reaction batch 缩小 extent；剩余 species quanta 无法让每个 marker 保持非空、或任何合法整批都不可表示时，本步延后而不暂停合法 base。没有可溶 solid 且液相连一个完整 aqueous stoichiometric extent 都不具备时，按 exact species quanta 直接判定为无候选，不进入完整 planner；这不改变任何正向或反向可表示反应。

## 容器与清空

- 干颗粒容器一次只保存一个可完整表示的 SVQ 库存，包含逐材料质量和可选固相组成。
- 玩家和发射器只作用于 controller 内实际目标格，使用与区域 checkpoint 联合的容器 WAL。
- `empty` 先联合预检液体、化学、coherent、active 和 deposit sink candidate，再一次提交；气体库存不属于凝聚相清空。
- reactive solid 的 transfer/audit group 对 mechanical kg mass 与 composition kg residual 分列按输入 `double` 的精确二进制值聚合，最后各自只舍入一次；不得由 species 质量反算机械质量，也不得让 parcel 顺序制造虚假的 composition/mass 审计差额。

## 审计与可观察量

颗粒审计至少验证：

```text
initial + external source - escaped sink
= active + deposit + coherent + transferred inventory
```

等式分别对 SVQ、逐材料质量和可选 species/composition 成立；冲量和热能在各自账本中闭合。`status` 的 active 数、deposit group 数、solid SVQ、bulk occupancy 和休眠列数都是从权威列聚合的只读摘要，不得作为恢复来源。
