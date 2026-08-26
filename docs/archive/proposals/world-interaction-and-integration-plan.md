# 世界行为与外部 Mod 集成实施计划

## 目标与边界

目标是让原版世界和其他 Mod 通过稳定、可恢复、loader-neutral 的端口观察或转移物质，而不是直接接触 Fabric service、solver 数组或客户端 mesh。

当前 Query、Jade 闭壳观察、typed quantity、内部容器 WAL 和 durable container committed event 已存在。缺口是公开的受控 transfer request/plan/commit、可持久定位的外部 actor、更多已提交事件，以及逐项守恒的原版行为。

本计划不扩大天然河流/海洋 ownership，不允许任意外部 callback 参与崩溃恢复，也不承诺把 Forge/NeoForge adapter 与 Fabric 同时交付。先完成 loader-neutral 契约和 Fabric 参考适配，其他 loader 只需替换发现与世界端口。

## API 分层

### 观察端口

保持现有同步 Query 语义：server thread、已加载区域、不加载、不唤醒、不回写、非 OK 不返回部分 observation。扩展观察字段时按独立库存类型增加封闭结果：

- gas：node 是否 ambient、component stable ID、standard volume nanos、kg、species、可选派生 K/Pa；
- granular/coherent solid：SVQ、kg、stable material/composition；
- phase：只返回稳定 phase identity 和实际 carrier amount，不承诺内部 PLIC/pressure；
- controller summary：与单格库存 Query 分离。

直接自由面 Jade 目标需要服务端 ray/position 解析，但最终仍调用 Query。客户端不得用 mesh triangle 反推 region/cell 后请求写入。

### 受控转移端口

在 `api` 中定义 JDK-only 请求和结果；名字使用语义，不以数字后缀区分格式。建议最小形状：

- `TransferTarget`：dimension stable ID、world position、可选 region UUID、face/port identity；
- `TransferActorIdentity`：provider ID、actor kind、provider 内稳定 object ID、slot/channel；
- `TransferResource`：封闭 liquid/gas/granular/coherent resource，stable IDs 和明确单位；
- `TransferRequest`：insert/extract、最大或精确量、composition/energy policy、transaction UUID；
- `TransferPlan`：绑定 region generation、actor before fingerprint、实际 settlement、过期条件；
- `TransferResult`：committed/rejected/unavailable/conflict 与结构化原因。

公开 API 不返回 live plan 实现、domain、runtime handle 或 mutable collection。计划不得跨 tick 无期限保存；commit 必须重新核对 generation、target、actor before truth 和 registry fingerprint。

### 外部 actor 持久协议

这是公开写入的关键门。provider 必须注册一种可跨重启解析的 actor adapter：

1. `providerId` 和 `objectId` 稳定、长度有界、canonical。
2. capture 返回封闭 before fingerprint；fingerprint 足以判断 before/after/conflict。
3. apply-after 只在 region HEAD 已提交后执行，且必须幂等。
4. durable-save 返回“已经强制保存”的证据；异步 future 或普通 dirty flag 不算完成。
5. recovery 只能通过持久 identity 重新发现 actor，不能保存 Java callback/class name。
6. provider 缺失或 actor 所在 chunk 未加载返回 UNAVAILABLE 并保留 WAL；actor 变成第三状态返回 CONFLICT/LOCKED。

复用 `ContainerTransferJournal` 的 generation/settlement/after truth 与 `ControllerItemTransferCoordinator` 的提交顺序。若现有 journal 的 actor union 无法表示 provider identity，直接扩展封闭 union 和严格 codec；不要旁建“API transfer journal”。

## 事务顺序

外部转移应与容器保持同一个骨架：

1. 主线程解析实际 world target 和唯一 owner。
2. adapter capture actor before truth；验证没有同 region/actor WAL。
3. core 规划实际可转 settlement，包括体积/质量/species/residual/SVQ/U。
4. 发布 PREPARED WAL。
5. 在私有 region candidate 应用 exact transfer，capture target generation 并回读验证。
6. WAL 写入实际 settlement、after truth 和 target checksum。
7. 发布 region HEAD。
8. 幂等应用 actor after truth并强制保存。
9. 删除 WAL。
10. 发布 committed event；监听器失败不得回滚事务。

如果资源跨两个 region 或设备两侧分别属于不同 region，不能连续执行两次单 region transfer。先设计 multi-HEAD coordinator：锁定 canonical region UUID 顺序、准备全部 target slots、发布协调 WAL、逐 HEAD 收敛并允许恢复完成。没有该 coordinator 前明确拒绝跨 region 原子请求。

## 原版行为框架

每种行为实现为三层：

- Fabric detector：读取方块/实体事件并冻结 world snapshot；
- core planner：只消费 stable IDs、几何、库存和物性，返回 world + region candidate；
- Fabric coordinator：持久准备、发布 HEAD、修改世界、发布事件。

不要在 block callback 内直接 drain marker 或 setBlock。若行为只影响 region 库存、不修改持久 world，可走单 HEAD candidate；涉及方块变化则需要类似 ownership 的 world preimage/WAL。

### 灭火

第一项推荐实现灭火，因为输入/结果较窄：

- detector 给出火焰 stable kind、接触面积、相邻 cell 和 world preimage；
- planner 计算所需液体 nanos、材料允许性、液体温度、显热/潜热、蒸气 component 和剩余能量；
- 只有实际可移除物质和能量达到灭火条件才提交方块熄灭；
- 蒸发质量进入 sealed gas 或 ambient sink，能量写入相应载体/source/sink；
- 世界方块与 region HEAD 不能出现一个提交另一个丢失的状态。

不要用“附近存在水 marker”直接移除火，也不要固定每次删除一桶。

### 湿润与冲刷

为吸水方块/实体定义有限库存 owner：容量、接受材料、当前 nanos/质量/species/U 和释放路径。若 Minecraft 对象不能可靠持久这些字段，则把交换分类为 external sink/source 并明确不可逆，不伪装成守恒储罐。

冲刷必须定义被移除方块/颗粒的产物去向；视觉粒子不是库存。

### 岩浆与高温反应

复用 thermal/reaction/phase planner：输入为接触面积、世界热边界或有限设备状态、液体组成；输出为反应 extent、产物 carrier、gas、U 和 world block candidate。禁止在世界事件里写死“水 + 岩浆 = 石头”后再删除流体。

### 红石、生物与寻路

这些首先是只读消费者：

- comparator 使用 controller summary 的有界离散值，不每 tick 扫全 region；
- 生物湿润/窒息/温度使用 Query observation 和明确阈值；
- pathfinding 使用低频、缓存有时限的 occupancy/cost snapshot，不持有 live solver；
- Query 失败时使用保守“不知道”，不能回退到方块外观猜测连续库存。

## 外部工业/科技 Mod 适配

先做一个仓库内 reference provider，模拟外部 tank 或 pipe，但只通过公开 API：

- provider 的资源以 stable ID/typed quantity 映射；无法表示 mixture/species/U 时在 plan 阶段拒绝或只接受明确纯资源；
- 不能只转 volume 丢掉独立 mass、species 或 energy；
- 对方 API 若只有“毫桶整数”，由 adapter 明确取整并保留未转余量，不把差额记 roundoff sink；
- capability discovery 在 Fabric 模块，映射逻辑尽量是 loader-neutral adapter；
- provider unload 时保留 WAL 为 UNAVAILABLE，重新加载后幂等恢复。

reference provider 通过后，再针对具体 Mod 写薄 adapter。不要在 core 引入任何第三方 Mod 类。

## 事件

只发布已提交事实。候选事件包括 checkpoint、ownership、phase transition、安全暂停和外部 transfer，但逐项冻结字段：

- transaction/event ID、dimension、region、committed generation；
- stable resource/phase/reason；
- typed actual settlement；
- 是否可由 Query 恢复当前状态。

事件不是可靠日志，不提供重放权。需要可靠订阅的外部系统应保存自己的游标并用 Query 对账，不能要求 MMM 为监听器保留第二份库存日志。

## 验证与提交顺序

推荐切片：

1. API request/result/actor identity 纯值对象和边界校验。
2. provider registry 与 Fabric discovery，不开放 commit。
3. journal 扩展和 recovery decision table。
4. reference provider 完整 insert/extract 与崩溃点。
5. 让一个现有内部容器路径调用公共 planner，证明语义一致。
6. 单个原版灭火行为。
7. Query gas/solid 扩展或自由面 Jade，根据实际消费者需求选择其一。

最近门以 `protocol`、`containers` 为主；原版世界事务按 routing 追加对应 ownership/thermal 最近门。真实游戏只验证一个行为和一次跨重启 actor recovery，不把多个 Mod 的联调塞入同一验收。
