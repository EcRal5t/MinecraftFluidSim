# 世界 ownership 与容器事务契约

## 三个持久域

controller 产品跨越三个彼此不能假装原子的持久域：

- region-state：marker、材料、化学、固体、气体和热学库存，唯一 commit point 是 region HEAD；
- Minecraft world/actor：方块、玩家手中物品或 dispenser inventory；
- sidecar WAL/registry：记录跨域事务足以确定恢复方向的 before/after truth。

所有跨域操作都采用“先持久准备与可重建结果，后发布 region HEAD，再把外部域收敛到已提交结果”的次序。WAL 不是另一份库存，也不能成为绕开 region HEAD 的提交点。

## Ownership 模型

controller 内部由空气和独立 ownership registry 表示。原版水方块不是 solver 库存的副本。

新激活区域只有在 world bridge 已生效时才接管内部纯 `Blocks.WATER`：

1. 只读扫描 controller footprint 和实际原版水；
2. 准备新的完整 region generation；
3. 发布 ownership WAL 与 WARNING；
4. 修改世界方块；
5. 发布 region HEAD；
6. 建立 committed ownership 记录并清理 WAL。

恢复根据 region HEAD 与 WAL 的 base/target generation 判定重放或回滚。未知文件、损坏 NBT、身份不符、generation 歧义或玩家已放置的非水方块一律保留并锁定，不猜测修复，也不覆盖玩家方块。

已加载 solver 在 step、empty、checkpoint 和转移前也必须检查自己的 ownership sidecar；锁只作用于对应区域，但无法识别 region 的 store issue 会形成全局创建锁。

### 接管事务

扫描与世界修改在同一个 server tick 内进行，候选只接受纯原版 water，并保存每个 block 的完整 preimage。region target slot 必须先写完、回读并验证，然后才发布 ownership WAL 与醒目的 WARNING 文件。移除每个方块时再次比较 preimage；扫描后被玩家改变的方块导致事务中止，不会被静默跳过。

HEAD 发布前崩溃时，恢复看到 `HEAD == baseGeneration`，把世界尽可能恢复到 preimage 并丢弃 inactive staging；玩家已占据的位置优先，记为 conflict 而不覆盖。HEAD 发布后崩溃时，`HEAD == targetGeneration`，region inventory 已权威，恢复只重建/核对 committed ownership registry，不再恢复水 preimage。

### 释放事务

release 只有在所有凝聚相库存为空且没有未决容器 WAL 时才允许。它使用同一套 base/target 判定，但世界操作方向相反：先尝试恢复已登记 preimage，再发布空 ownership 的 region target。玩家占据的位置保持不变并进入结果统计。

committed registry 记录 region、dimension、generation 和确切 owned block/preimage 集。日常 reconcile 只分类 `OWNED_AIR`、`PREIMAGE_PRESENT` 和 `OCCUPIED`，不自动把冲突方块改回空气或水。

## 区域生命周期

- controller 必须有完整闭壳和全部已加载 chunk。
- UNLOADED 区域不持 solver，但仍占 manager 的 cell/marker 预算。
- checkpoint 成功后才能释放 solver；恢复成功后先刷新 collision，再发送 snapshot。
- 删除区域前必须处理 ownership，并要求实际凝聚相库存为空。`remove` 不把未知状态解释成可删除数据。

边界壳、interior set 和 required chunk set 来自持久 footprint。controller 方块存在不等于区域可运行：每次 admission/refresh 都必须核对 dimension、controller identity、完整闭壳和 chunk load。边界打开时停止 step 和修改操作；不会用 chunk ticket 把缺失世界强行加载。

`demo` 只替换当前选择的开发域；`empty` 清除凝聚相真值并把移除库存记入 escaped sink，但保留 controller、ownership 和 sealed gas。区域有未决 WAL、pending phase-work 或不闭合 audit 时拒绝清空。

## 容器真值

- 液体容量使用 `long` 纳体积，1 block volume = 1,000,000,000 nanos。
- 材料桶保存每个稳定材料的纳体积与独立质量。
- 化学桶另外保存 species quanta、indicator 和 mass residual。
- 干颗粒容器使用整数 SVQ、逐材料质量与可选 solid composition。
- 热液体另外保存 registry-bound 总内能。
- item count 必须为 1；damage、tooltip、durability bar、颜色和温度不参与结算。

item fingerprint 编码实际可结算的整组数据，而非仅 item id：kind、typed amount、stable mixture/chemical/granular contents、raw mass bits、可选 ThermalContents 以及其 registry/underlying hash。`ContainerTransferJournal` 只保存这一数据，`ContainerTransferItems` 独占 fingerprint 与 `ItemStack` 的双向映射。空桶、部分桶、材料桶、化学桶和颗粒容器是不同 shape；shape 之间缺字段或同时出现冲突 component 都拒绝。

液体抽取允许生成不足 1 BV 的 partial item；放入已有部分桶时仍按实际剩余容量规划。`FluidMixture`、`ChemicalContents`、`GranularContents` 和 `ThermalContents` 都有自己的规范编码与总账，item component 只是这些真值的载体。

## 玩家与发射器路由

- 玩家只按准星命中的 controller 壳面和其正后方实际内部格选择区域。
- 发射器只按发射方向正对的壳面中心路由；壳边、壳角、多个候选和未加载目标拒绝。
- 插入/抽取原语 all-or-nothing。insert 先规划 marker/parcel 和容量，extract 先做双精度预检，再按整 marker 与尾拆分结算。
- 创造模式放出是 external source；创造模式装取仍从 solver 删除实际库存，不能制造隐藏 sink。

目标解析发生在事务准备之前。玩家命中壳的外侧、内部格、区域 UUID 和 dimension 必须唯一；dispenser 朝向只参与最初的目标解析，其 dimension、block position 和 slot 构成持久 actor identity。actor identity 使用封闭的 typed union，各 actor kind 只能携带自己的完整字段，不能用 null 填充无关字段。同一 region 或同一确切 actor identity 存在 WAL 时不得开始另一笔容器事务。恢复时 actor 离线或 owning chunk 未加载是 `UNAVAILABLE`，保留 WAL 等待；slot/block entity 改变或当前 item 既不等于 before 也不等于 after 是 `CONFLICT`，保留 WAL 并 LOCKED，不能每 tick 猜测或把物品塞到其他手/slot。将来增加外部 Mod actor 时必须给它独立的稳定 provider/type identity 和跨重启可解析状态，不能把运行期 callback 写进 WAL。

insert/extract 返回“实际转移量”，WAL 和 after item 必须使用这个量而非请求量。液体用 nanos，颗粒用 SVQ；两个资源域不能共用一个模糊 `amount`。含热液体还要按同一 exact 体积分拆 coupled `U`，最后一个接收浮点余数。

## 跨持久域 WAL

容器事务覆盖 region generation、actor item 和可选 registry：

1. `PREPARED` 保存 transaction/region/dimension、base/target generation、operation、typed resource、actor 和 before item。
2. solver 执行 exact transfer。
3. stage 并回读完整 target generation。
4. `REGION_COMMITTED` 增加实际量、after item 和 target manifest checksum。
5. 原子发布 region HEAD。
6. 修改 actor item，并等待玩家存档或 dispenser chunk 完成 durable save。
7. 删除 WAL。

恢复必须核对 HEAD、checksum、actor、before/after item、resource kind、registry fingerprint 和 generation。region、ownership 与 container 的紧邻代次统一拒绝负数和 `long` 溢出，不能让 `Long.MAX_VALUE + 1` 被误认成合法 target。任何歧义都保留 WAL 并锁定；不得因为 item 看起来相似就推断完成状态。

当前 WAL 有材料布局和含热布局。reader 只接受当前 writer 产生的完整字段集合：缺字段、错 NBT 类型、额外未知字段和溢出的 generation pair 都保持原文件并锁定，不做补全或忽略。

`ControllerItemTransferCoordinator` 独占 WAL、staged checkpoint、HEAD 发布与 actor 收敛顺序；`FluidTransferService` 只负责 Minecraft item 分类、目标解析和选择 exact solver primitive。更精确的运行序列是：

1. 验证目标区域可修改、无其他 WAL、actor item count 为 1，并捕获 before fingerprint。
2. 原子发布 `PREPARED` WAL；此时 after=before，尚无 target checksum。
3. 在 live solver 执行 exact transfer，并标记该 transaction 的 solver-dirty。
4. 构造最终 after item；将 solver 候选 capture 到 inactive region slot并完整回读。
5. 原子用 `REGION_COMMITTED` WAL 覆盖原 WAL，写入实际量、after fingerprint、coupled energy 和 target manifest checksum。
6. 仅在这份 WAL 已 durable 后发布 region HEAD。HEAD 一旦指向 target，region 侧不可回滚。
7. 把 actor item 收敛为 after；玩家调用 player save，dispenser 调用 owning level/chunk save。
8. durable save 成功后才删除 WAL；删除失败只留下可幂等收敛的 WAL，不重复资源转移。

WAL/WARNING/committed/HEAD 的文件内容在 rename 前 `force`，rename 与事务完成后的 delete 还要同步父目录 entry；支持目录 channel 的文件系统执行 directory fsync。Windows JDK 不暴露目录 channel，因此仍依赖同卷原子 rename 与已强制写入的文件内容，但不会把一次普通进程崩溃误当成已提交。

`REGION_COMMITTED` 是“WAL 已经拥有权威 after item”的含义，不表示 HEAD 必然已经移动；实际 commit 仍由 HEAD 判断。

### 恢复决策表

| Region HEAD | WAL phase | 恢复动作 |
|---|---|---|
| `base` | 任意 | live solver 未变时丢弃 inactive staging 与 WAL，actor 保持 before；同进程 solver 已变则锁定并要求重启从 base 恢复 |
| `target` + checksum 相符 | `REGION_COMMITTED` | 幂等把 actor 收敛到 after，强制保存，再删除 WAL |
| `target` | `PREPARED` | 锁定：region 已提交但 WAL 没有权威 after item |
| 其他 generation 或 checksum 不符 | 任意 | 锁定：无法证明 region 属于本事务 |

恢复在判定前还必须验证 dimension、resource kind、base/target 连续性、actor identity、before/after shape、所有适用 registry fingerprint 和 thermal underlying hash。任一未知内容都原样保留。

container-transfer 根目录是严格短期恢复域：未知文件名、目录、残留 `.tmp`、文件名与 transaction UUID 不同或损坏 NBT 会形成全局容器事务锁，因为无法安全得知它属于哪个区域。`empty` 和 region 删除也必须把“存在任何未决或不可读 WAL”视为拒绝条件。

## 守恒边界

对 insert：item resource 减少量 = region external source 增加量；对 extract：region external sink/transfer ledger 增加量 = item resource 增加量。材料质量、species、solid composition 和总内能分别闭合，不能只凭体积相等通过事务。

创造放出没有物品侧减少，因此明确记 region external source；创造装取仍必须从 region 删除，只是 Minecraft 不消费/替换玩家物品。这个不对称是显式 actor 语义，不得隐藏在 tooltip 或 item damage 中。
