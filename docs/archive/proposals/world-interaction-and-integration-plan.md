# 世界行为与外部 Mod 集成实施计划

## 目标与边界

目标是让原版世界和其他 Mod 通过稳定、可恢复、loader-neutral 的端口观察或转移物质，而不是直接接触 Fabric service、solver 数组或客户端 mesh。

当前 Query、Jade 闭壳观察、typed quantity、内部容器 WAL 和 durable container committed event 已存在。缺口是公开的受控 transfer request/plan/commit、可持久定位的外部 actor、更多已提交事件，以及逐项守恒的原版行为。首个公开写入范围只覆盖已有 exact primitive 与 WAL 已证明的 liquid nanos 和 granular SVQ；gas 与 coherent solid 不借一个宽泛 union 提前承诺，分别在有真实消费者和完整事务后追加。

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

在 `api` 中定义 JDK-only 请求和结果；名字使用语义，不以数字后缀区分格式。最小形状：

- `TransferTarget`：dimension stable ID、world position、可选 region UUID、face/port identity；
- `TransferActorIdentity`：provider ID、actor kind、provider 内稳定 object ID、slot/channel；
- `TransferResource`：首个封闭 union 只有 liquid 与 granular；liquid 携带 nanos、逐材料 kg、可选 chemical groups/species/residual 和可选 raw `U`，granular 携带 SVQ、逐材料 kg 与可选 solid composition；
- `TransferRequest`：insert/extract、最大或精确量、composition/energy policy、transaction UUID；
- `TransferPlan`：绑定 region generation、actor before fingerprint、实际 settlement、过期条件；
- `TransferResult`：committed/rejected/unavailable/conflict 与结构化原因。

公开 API 不返回 live plan 实现、domain、runtime handle 或 mutable collection。计划不得跨 tick 无期限保存；commit 必须重新核对 generation、target、actor before truth 和 registry fingerprint。

### B01：公开值对象与操作语义

`api` 中的 public shape 采用语义名称，不暴露 core 内部 handle。拆为：

- `TransferDirection`：相对 region 的 `INSERT`/`EXTRACT`；
- `TransferTarget`：dimension stable ID、world metres/block position、可选 region UUID、命中 face 与可选 port ID；
- `TransferActorIdentity`：provider ID、actor kind、object ID、slot/channel，全部 canonical 且长度有界；
- `LiquidTransferPayload`：actual nanos、按 stable material 排序的 nanos/kg、可选 chemical groups 与 kg residual、可选 `ThermalPayload(registryFingerprint, rawUBits)`；
- `GranularTransferPayload`：actual SVQ、排序 group 的 material/SVQ/kg、可选 composition；
- `TransferConstraint`：exact 或 at-most、允许 stable resource/mixture policy、是否要求完整 chemistry/thermal；
- `TransferRequest`：transaction UUID、target、actor、direction、constraint；insert 的物质来自 actor capture，不能由调用者另传一份冲突 payload；
- `TransferPlanSummary`：actual typed settlement、base/target generation、过期 tick、结构化拒绝原因；不含可执行 callback；
- `TransferResult`：committed/rejected/unavailable/conflict/stale/locked，committed 时返回 actual payload 与 generation。

`at-most` 只允许在 planner 先求出可完整表示 settlement 后提交；WAL、actor after 和 event 始终记录 actual，不记录请求上限冒充结算。insert/extract 都必须选择 composition policy：`PRESERVE_ALL` 是首个默认；调用方无法表示 mixture/species/`U` 时拒绝，不提供“丢掉附加列”的模式。

API 包只依赖 JDK。core 提供 public payload 与 `FluidMixture`/`ChemicalContents`/`GranularContents`/`ThermalContents` 的严格映射；避免复制算法，但 public ABI 也不直接暴露可变 pool/ledger。所有集合 defensive copy、canonical sort，并在构造阶段做长度、checked arithmetic、finite raw bits 和 stable ID 校验。

### B02：发现、线程与授权

`MmmFabricApi.transfers(server)` 返回绑定该 server 的 `MmmTransferApi`。`plan`/`commit` 只允许 server main thread；wrong-thread 返回结构化失败，不把任务排队到未来 tick。`plan` 只读 actor/region并绑定当前 tick、profile fingerprint、region generation 和 actor before fingerprint；默认在 tick boundary 过期。提供 `execute(request)` 作为同 tick plan+commit convenience，reference provider 优先使用它。

provider 注册发生在 loader 初始化，而不是某个世界启动后；Fabric adapter 保存 `providerId -> TransferActorProvider` runtime map。provider 接口可以接触 Minecraft server/world 类型，因此留在 `fabric` 发现层；持久 identity、before/after truth 和 recovery decision 仍是 JDK-only。注册重复 provider ID、过长 ID 或启动后替换实现直接拒绝。

授权分别检查：调用 Mod 是否只 plan、是否可 commit、target port 是否接受该方向/resource，以及 actor provider 是否认领 identity。不要让任意获得 region UUID 的调用者绕开实际 world target或 provider actor。API 不承诺跨线程 future，也不允许监听器在 committed event 回调中重入同一 region transfer；重入返回 `BUSY`。

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

### B03：单 region 公共 coordinator

不要让 public API 直接调用 `FlipFluidDomain.tryInsert...`。提取一个由现有玩家/发射器和公共 provider 共同调用的 `RegionTransferPlanner`，只负责 exact region candidate；`ControllerItemTransferCoordinator` 演化为或委托给统一 `RegionActorTransferCoordinator`，独占以下顺序：

1. 解析 profile-ready target、owner、cell/port、actor provider和当前 before truth。
2. 检查同 actor、同 region 没有 WAL；生成/核对 caller transaction UUID。
3. core 在不可变region快照上规划actual payload、source/sink与可选coupled `U`；provider的纯映射在captured before truth上应用同一actual payload得到after actor truth。两者都不修改live对象。
4. 发布 PREPARED WAL，保存 base/target generation、profile/registry fingerprint、before fingerprint 和 planned actual settlement。
5. 在私有 region candidate 应用 exact settlement，完整 audit 后 stage/force/readback target slot。
6. 用 target manifest checksum 与 actor after fingerprint 覆盖为 REGION_COMMITTED WAL并 force。
7. 再核对 HEAD 仍为 base，然后发布 target HEAD；从此 region 不回滚。
8. provider 幂等 apply-after 并执行其声明的 durable save；返回的证据必须绑定 object ID 与 after fingerprint。
9. 回读 actor after truth；一致后删除 WAL并同步目录 entry。
10. 发布 committed event。监听器异常或服务器在 9/10 之间退出不改变库存；事件允许漏失。

player/dispenser 继续使用专用 Minecraft actor adapter，但物质 planner 与 WAL state machine 不再拥有第二份实现。创造 source descriptor 是特殊 actor：before=after descriptor，region insert 仍记 external source；它不允许 EXTRACT。创造装取使用真实 container actor并走普通 after stack。

### B04：provider recovery 决策表

| HEAD | WAL | actor state | 动作 |
|---|---|---|---|
| base | PREPARED/REGION_COMMITTED | before | 删除已知 staging与 WAL；region/actor 均保持 before |
| base | 任意 | after/third | CONFLICT，保留 WAL；不能倒推 actor 或提前提交 HEAD |
| target + checksum | REGION_COMMITTED | before | 幂等 apply after、force save、回读后删 WAL |
| target + checksum | REGION_COMMITTED | after | force/证明 durable、删 WAL |
| target | PREPARED | 任意 | LOCKED；缺少权威 after/checksum，保留证据 |
| 其他 generation/checksum | 任意 | 任意 | LOCKED |
| base/target 可判定 | provider 缺失或 actor chunk 未加载 | unknown | UNAVAILABLE，保留 WAL 等待明确 provider/chunk 事件；不每 tick重试 |

provider 的 durable proof 不是随意 boolean。reference provider 若使用 BlockEntity inventory，必须在 owning dimension flush 完成后回读 before/after component fingerprint；若当前 Minecraft API 不能单对象 force，沿用 dispenser 的低频 dimension flush，而不是 dirty flag。provider 抛异常、返回错误 object ID 或 after fingerprint 不一致都保留 WAL。

WAL strict codec 扩展为 actor 封闭 union；未知 actor kind、额外字段、错 NBT type、generation overflow、profile mismatch 和 actual payload 账本不闭合保持原文件并锁定。不要保存 provider Java class、lambda、registry object 或世界坐标以外的 runtime handle。

### B05：reference provider

仓库内实现一个最小 `reference_tank` BlockEntity，只为证明公共 API，不成为第二套 MMM inventory：

- tank 自己持有一个完整可持久 typed liquid 或 granular payload、capacity 与 generation；不支持两类同时非空；
- tank 的 `providerId/objectId/slot` 可由 dimension + block position稳定恢复；
- interaction/红石触发一次明确方向的 transfer，目标必须是相邻 controller port/壳内侧；
- provider capture、after、durable save 只通过公开 SPI；实现代码不得 import MMM internal simulation/coordinator 类；
- mixture/species/thermal 无法表示时在 plan 阶段拒绝；不转成“最接近的一桶水”；
- unload 后 WAL 为 UNAVAILABLE；重新加载触发一次 recovery；第三状态锁定而不是覆盖 tank。

随后选一个现有玩家 material bucket 路径让其也调用 `RegionTransferPlanner`，用测试证明相同输入得到相同 actual payload、source/sink 和 candidate checksum。reference provider完成前不向第三方宣称写 API 稳定。

## 原版行为框架

每种行为实现为三层：

- Fabric detector：读取方块/实体事件并冻结 world snapshot；
- core planner：只消费 stable IDs、几何、库存和物性，返回 world + region candidate；
- Fabric coordinator：持久准备、发布 HEAD、修改世界、发布事件。

不要在 block callback 内直接 drain marker 或 setBlock。若行为只影响 region 库存、不修改持久 world，可走单 HEAD candidate；涉及方块变化则需要类似 ownership 的 world preimage/WAL。

### 灭火

第一项推荐实现灭火，因为输入/结果较窄：

- detector 给出火焰 stable kind、接触面积/方向、相邻 cell 和完整 world preimage；
- data definition 给出该 fire kind 的 quench energy、允许接触相态/材料性质和世界 after state；这些是显式世界行为数据，不按 material ID 写死；
- planner 计算接触液体在共同物性域内可接收的能量。火是外部能量 source；先把 quench energy 加入实际载体 `U`，再由正常气液 phase planner决定是否以及多少蒸发，不能预先删除固定 nanos；
- 只有有限接触面积和现有热容量能完整承接 quench candidate 时才提交方块熄灭；phase 产生的蒸气进入所属 sealed gas，ambient 份额进入明确 source/sink；
- 世界方块与 region HEAD 不能出现一个提交另一个丢失的状态。

不要用“附近存在水 marker”直接移除火，也不要固定每次删除一桶。若未来要模拟冲刷带走的液体，另立实际物质 transfer，不把灭火本身伪装成 liquid sink。

### B06：world action WAL

灭火等“region + world block”操作使用独立、通用但封闭的 `WorldActionJournal`，不滥用长期 ownership registry。journal 保存 action ID/kind、profile fingerprint、region/dimension、base/target generation、target manifest checksum、每个 block 的 canonical before/after、actual material/energy settlement 和 phase。提交顺序：

1. detector 在主线程冻结 before blocks、contact geometry和目标 region generation；
2. core 生成完整 region + world candidate；
3. stage/回读 region target；
4. 发布 PREPARED world-action WAL；
5. 用 actual settlement/checksum 覆盖为 REGION_COMMITTED WAL；
6. 发布 region HEAD；
7. 对每个 block 再比较 before，幂等应用 after并执行 owning dimension durable save；
8. 回读 after，删除 WAL，发布 committed event。

HEAD 仍为 base 时恢复保持/还原 before并删除已知 staging；HEAD 为 target 时只收敛 world after。玩家在 HEAD 前把 fire 改成第三状态则中止；HEAD 后出现第三状态形成 CONFLICT，不能覆盖玩家的新方块。未知 action kind或 block state codec 保留 WAL并锁对应 region。

### B07：灭火最小实现

最小数据只覆盖原版 `minecraft:fire`，但算法读取 stable fire definition。contact 必须是 flame face 与已加载 region 内 liquid PLIC/cut-face 的实际面积，不扫描“附近六格有无水”。thermal 默认 profile是前置；无 thermal candidate 不提供缩水版行为。

验收 fixture 固定比较：零接触、接触但热容量不足、可完整 quench、sealed headspace 发生后续蒸发、每个 WAL 故障点。通过证据包括 fire world state、region HEAD generation、外部 energy source、liquid/gas kg/species/`U` 和 pending phase-work，而不只是火焰消失。最近门为 `thermal,ownership`；journal strict/fault 覆盖若能归入 ownership 现有事务测试就不新增 selector。

### B08：gas 创造端口与后续公开范围

C05 气液 species 的玩家场景需要建立非 ambient gas inventory。P1 先交付一个只允许创造玩家使用的 `gas source/vent` 正式端口：

- target 由壳面和内侧 gas cell 唯一解析，必须属于一个 sealed node；ambient target 需要玩家明确确认 source/sink 语义；
- source descriptor 只含 gas component stable ID、请求标准体积 nanos和可选 K，服务端从 world gas/thermal/chemical registry 构造实际 component kg/species/residual/`U`；
- insert/extract 在私有 region candidate 中联合更新 component standard nanos、kg、species/residual、node `U` 与 gas ledger，HEAD 是唯一提交点；创造 source descriptor before=after，insert 记 external source，vent/extract 记 actual external sink；
- 不修改 pressure；pressure 由提交后的 inventory/available volume/`U` 派生。超出 P/T/容量域整次拒绝；
- probe 显示 actual settlement 与 node lineage，端口不持有第二份 gas inventory。

这条路径是创造产品入口，不等同于 public Mod gas transfer。只有 `GasContents`、gas actor WAL、reference provider和 committed typed event 都完成后，才把 gas 加入 B01 union；coherent solid同理。不要让 P1 为了一个可控 CO2 场景提前承担跨 Mod gas tank ABI。

### 湿润与冲刷

为吸水方块/实体定义有限库存 owner：容量、接受材料、当前 nanos/质量/species/U 和释放路径。若 Minecraft 对象不能可靠持久这些字段，则把交换分类为 external sink/source 并明确不可逆，不伪装成守恒储罐。

冲刷必须定义被移除方块/颗粒的产物去向；视觉粒子不是库存。

### B09：有限吸收与冲刷切片

首个wettable对象必须二选一并写入data definition：

- **finite owner**：BlockEntity/item实际保存typed liquid truth，注册为B02 actor provider，通过B03 coordinator吸收/释放；容量、允许material/chemical/temperature和durable save均可恢复；或
- **external sink**：world action明确记录actual nanos/kg/species/`U`进入sink，对象只保存有限wetness展示状态；后续“变干”不能无中生有返还物质。

detector冻结接触PLIC面积、world before和owner before；planner按actual contact/owner capacity求exact settlement。湿润视觉值由owner inventory或已提交sink event派生，不能反向决定吸收量。

冲刷另行定义solid source owner、临界wall shear与产物route。若产物进入MMM granular，联合world block preimage、SVQ/kg/composition/`U`和region HEAD；若变成掉落item，item actor truth也进入同一WAL；如果既无granular也无可靠item表示，拒绝该定义。首个提交只实现一个finite absorber，不把冲刷一起塞入。

### 岩浆与高温反应

复用 thermal/reaction/phase planner：输入为接触面积、世界热边界或有限设备状态、液体组成；输出为反应 extent、产物 carrier、gas、U 和 world block candidate。禁止在世界事件里写死“水 + 岩浆 = 石头”后再删除流体。

### B10：高温世界边界与产物

先把原版lava/magma按data定义成world thermal/chemical provider：stable block-state selector、无限/有限热源分类、surface temperature或finite `U` owner、接触phase/material要求和可生成产物定义。普通lava block若没有可持久finite energy，必须明确是external heat source；不能既不降温又声称能量闭合为内部交换。

最小切片只做有限面积热通量，使water通过现有gas-liquid phase正常蒸发；不立即生成stone。第二切片才增加data-driven reaction：exact liquid/gas/solid extent、状态函数能差、world after block和collision/ownership变化共同进入B06 WAL。产物若是world stone，它的质量离开MMM carrier进入world sink/owner ledger；若保留coherent solid，就不能同时再放一个代表同一质量的stone block。

fixture覆盖无接触、热源有效域、sealed pressure抑制沸腾、world block第三状态、phase pending work和产物二选一，最近门`thermal,gas-liquid,ownership`。

### 红石、生物与寻路

这些首先是只读消费者：

- comparator 使用 controller summary 的有界离散值，不每 tick 扫全 region；
- 生物湿润/窒息/温度使用 Query observation 和明确阈值；
- pathfinding 使用低频、缓存有时限的 occupancy/cost snapshot，不持有 live solver；
- Query 失败时使用保守“不知道”，不能回退到方块外观猜测连续库存。

### B11：只读原版消费者

按成本从低到高分提交：

1. comparator只读取controller summary中已经维护的、data-defined metric和0–15映射；只在summary revision变化时更新neighbor，不每tick扫描region。
2. entity wet/submerged/temperature效果在Fabric主线程对有界AABB采样Query observation；阈值、采样频率和效果映射数据化。Query非OK视为unknown，不施加或延续旧效果。
3. pathfinding使用region publisher生成的低分辨率、带expiry/generation的cost snapshot；只读已加载region，不让每个mob调用cell Query。tombstone/generation变化使cache失效。

这些consumer不写region，不使用WAL，也不唤醒。若某项后来需要吸收热/物质，移动到B09 actor/world action框架，不能在只读callback里顺便drain。

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

### B12：事件逐项冻结

不要一次加入一个带`type + Map<String,Object>`的通用事件。每个事件是封闭record，只有对应commit point已经存在才开放：

- `ExternalTransferCommittedEvent`：B03 WAL删除后；transaction/provider/actor、direction、actual public payload、region checkpoint generation；
- `WorldActionCommittedEvent`：B06 WAL删除且world after durable后；action kind、blocks有界摘要、actual material/energy settlement；
- `RegionCheckpointCommittedEvent`：HEAD回读完成后；region/profile/checkpoint generation和reason，不携带库存副本；
- `RegionAvailabilityChangedEvent`：状态转换已在主线程登记后；old/new reason与operator intent，事件不等同checkpoint；
- phase event只有exact batch进入完整accepted candidate后发布；如果logical step尚未checkpoint，要明确它是runtime committed generation而非durable HEAD，或推迟到下一HEAD。不要混用。

监听器始终server main thread、异常隔离、不可取消、不可回滚、允许漏失。每种事件提供`recoverableByQuery`说明；phase历史通常不能由当前Query重建，因此不能假装可靠审计流。ABI测试覆盖字段canonical/上限、监听器重入BUSY、异常隔离和server shutdown清订阅，最近门`protocol`加直接commit领域。

## 验证与提交顺序

### 代码入口与最近门

| 工作包 | 首要入口 | 关键输出 | 最近 selector |
|---|---|---|---|
| B01 | `api/.../transfer/`、`core/.../transfer/` | public payload/request/result 与 core 严格映射 | `protocol,containers` |
| B02 | `api/fabric/MmmFabricApi`、新 provider registry | server-bound discovery、main-thread/authorization | `protocol` |
| B03 | `ControllerItemTransferCoordinator`、`FluidTransferService` | 统一 planner/coordinator，不旁建 WAL | `containers` |
| B04 | `ContainerTransferJournal/Store/Recovery`、actor union | provider strict codec 与完整 recovery table | `containers` |
| B05 | 新 `compat/reference/` 或 `integration/reference/` | 只经 public API 的 tank provider | `containers,protocol` |
| B06/B07 | 新 world-action coordinator/store、thermal planner、Fabric block detector | fire HEAD+world WAL | `thermal,ownership` |
| B08 | gas inventory exact primitive、target resolver、creative source | single-HEAD gas source/vent | `air-network,thermal,containers` |
| B09 | wettable provider、world/actor coordinator | finite absorber；冲刷另切片 | `containers,ownership` |
| B10 | thermal boundary/reaction/world candidate | lava heat flux；产物另切片 | `thermal,gas-liquid,ownership` |
| B11 | controller summary、Query、snapshot cache | comparator/entity/path只读consumer | `protocol,lifecycle`，按consumer追加 |
| B12 | `api/.../event/`、`CommittedEventHub` | 逐record committed event | `protocol`加直接commit领域 |
| Query gas/solid | `api/.../query/`、`FabricFluidQueryApi`、Jade/probe | 封闭 observation，不改变旧 liquid result | `protocol,air-network,granular` |

每个 selector 只在实际跨层时追加；例如 B01 的纯值对象先跑 `protocol`，接 core payload映射才加 `containers`。不因为总计划列出 thermal/gas 就累计全门。

### 自动覆盖

1. request/payload 构造拒绝超长 ID、重复/乱序 component、overflow、NaN `U`、amount/resource kind 不匹配和缺 required fingerprint。
2. plan 后 region generation、profile fingerprint、actor before、target owner或 tick 任一变化，commit 返回 STALE且文件/region/actor字节不变。
3. insert/extract exact 与 at-most 都证明 request、actual、WAL、after actor、source/sink/event同量。
4. B04 每个 recovery 行和每个故障点均覆盖；provider missing/chunk unloaded只在事件后重试，不形成 tick 日志风暴。
5. reference tank的 block entity save/reload后 fingerprint不变；HEAD target + before actor可幂等完成，third state保留 WAL。
6. 玩家 material bucket迁入统一 planner前后，对同一 frozen state输出 identical actual settlement和候选 ledger。
7. fire覆盖 before 被玩家改变、HEAD前崩溃、HEAD后崩溃、world durable save失败、thermal/phase candidate失败；失败时不删火且不改 region。
8. gas source/vent覆盖 sealed/ambient、component不允许 species、P/T越界、capacity、topology lineage变化和 source/sink/`U`闭合。

### 提交顺序与人工验收

1. B01 public 值对象与校验，不注册可写入口。
2. B02 provider registry、thread/authorization、只读 plan。
3. B03/B04 统一 coordinator、journal actor union和故障恢复。
4. B05 reference provider insert，再提交 extract与跨重启恢复。
5. 迁移一个现有 internal container planner，删除重复路径。
6. 按真实消费者扩展 gas/solid Query；不要为“也许有用”扩字段。
7. B06 world-action journal/core planner，再单独提交 B07 fire detector/resources。
8. B08 gas creative port；public gas ABI留到另一次明确评审。
9. B09、B10、B11按明确玩家/适配需求各自开切片；P2只要求其中B07灭火，不能用未实现的列表阻止已经闭合的公共transfer。
10. B12只在对应commit point稳定后逐event提交，不预建空事件层次。

真实游戏只验证两个无法由纯门完全观察的事务：reference provider在 REGION_COMMITTED/actor-before 状态退出重进后收敛；一处实际 fire 接触液面后 HEAD/world共同完成。给出各自单一场景、预期 generation/WAL和日志证据，不联调多个第三方 Mod。
