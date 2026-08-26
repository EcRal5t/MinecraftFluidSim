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
- `TransferActorIdentity`：provider ID、actor kind、provider 内稳定 object ID、slot/channel、持久 actor epoch；
- `TransferResource`：首个封闭 union 只有 liquid 与 granular；liquid 携带 nanos、逐材料 kg、可选 chemical groups/species/residual 和可选 raw `U`，granular 携带 SVQ、逐材料 kg 与可选 solid composition；
- `TransferOperationIdentity`：actor epoch、严格单调actor operation sequence和仅用于追踪的caller UUID；exactly-once键是`(actor identity, epoch, sequence)`，不依赖永久保存全部UUID；
- `TransferRequest`：insert/extract、最大或精确量、composition/energy policy、operation identity；
- `TransferPlanToken`：server生成的有界opaque nonce，绑定完整request digest、profile/registry、target owner、region generation、actor before truth/sequence和过期tick；
- `TransferResult`：committed/rejected/unavailable/conflict 与结构化原因。

公开API固定为`plan(request) -> TransferPlanSummary`、`commit(planToken) -> TransferResult`和同tick便利方法`execute(request)`。不返回live plan实现、domain、runtime handle或mutable collection。plan只在server内存的有界表中活到过期tick；token不可序列化为未来承诺。commit只接受原token，不重新读取客户端request来“再规划”，并重新核对generation、target、actor before truth/sequence和registry fingerprint。重启后token失效，调用方使用同一operation identity重试，由actor durable sequence判定已提交或stale，绝不能重新执行。

### B01：公开值对象与操作语义

`api` 中的 public shape 采用语义名称，不暴露 core 内部 handle。拆为：

- `TransferDirection`：相对 region 的 `INSERT`/`EXTRACT`；
- `TransferTarget`：dimension stable ID、world metres/block position、可选 region UUID、命中 face 与可选 port ID；
- `TransferActorIdentity`：provider ID、actor kind、object ID、slot/channel和actor epoch，全部 canonical 且长度有界；
- `LiquidTransferPayload`：actual nanos、按 stable material 排序的 nanos/kg、可选 chemical groups 与 kg residual、可选 `ThermalPayload(registryFingerprint, rawUBits)`；
- `GranularTransferPayload`：actual SVQ、排序 group 的 material/SVQ/kg、可选 composition；
- `TransferConstraint`：exact 或 at-most、允许 stable resource/mixture policy、是否要求完整 chemistry/thermal；
- `TransferOperationIdentity`：actor epoch、非负单调sequence和trace UUID；同一actor的新操作只能使用当前`nextSequence`，UUID重复本身不改变顺序语义；
- `TransferRequest`：operation identity、target、actor、direction、constraint；insert 的物质来自 actor capture，不能由调用者另传一份冲突 payload；
- `TransferPlanSummary`：opaque `TransferPlanToken`、request digest、actual typed settlement、base/target generation、actor before/after sequence、过期 tick、结构化拒绝原因；不含可执行 callback或可变actor truth；
- `TransferCompletionReceipt`：operation identity、request digest、target identity、actual payload、committed region generation、actor after sequence与result digest；受与request相同的payload上限约束并作为actor after truth的一部分持久保存；
- `TransferResult`：committed/rejected/unavailable/conflict/stale/locked，committed 时返回 actual payload 与 generation。

`at-most` 只允许在 planner 先求出可完整表示 settlement 后提交；WAL、actor after receipt 和 event 始终记录 actual，不记录请求上限冒充结算。insert/extract 都必须选择 composition policy：`PRESERVE_ALL` 是首个默认；调用方无法表示 mixture/species/`U` 时拒绝，不提供“丢掉附加列”的模式。

API 包只依赖 JDK。core 提供 public payload 与 `FluidMixture`/`ChemicalContents`/`GranularContents`/`ThermalContents` 的严格映射；避免复制算法，但 public ABI 也不直接暴露可变 pool/ledger。所有集合 defensive copy、canonical sort，并在构造阶段做长度、checked arithmetic、finite raw bits 和 stable ID 校验。

### B02：发现、线程与授权

`MmmFabricApi.transfers(server)` 返回绑定该 server 的 `MmmTransferApi`。`plan`/`commit` 只允许 server main thread；wrong-thread 返回结构化失败，不把任务排队到未来 tick。`plan` 只读 actor/region并绑定当前 tick、profile fingerprint、region generation、actor exact before truth及其epoch/nextSequence；默认在 tick boundary 过期。提供 `execute(request)` 作为同 tick plan+commit convenience，reference provider 优先使用它。

server-owned plan table有per-server和per-provider硬entry/byte上限；创建新plan前只清理已过期/已消费token，容量不足返回`BUDGET`，不能驱逐仍有效token让另一个调用方获得不确定STALE。token commit最多消费一次；成功、拒绝或过期后移除，server stop全部失效且不落盘。

provider 注册发生在 loader 初始化，而不是某个世界启动后；Fabric adapter 保存 `providerId -> TransferActorProvider` runtime map。provider 接口可以接触 Minecraft server/world 类型，因此留在 `fabric` 发现层；持久 identity、before/after truth 和 recovery decision 仍是 JDK-only。注册重复 provider ID、过长 ID 或启动后替换实现直接拒绝。

授权分别检查：调用 Mod 是否只 plan、是否可 commit、target port 是否接受该方向/resource，以及 actor provider 是否认领 identity。不要让任意获得 region UUID 的调用者绕开实际 world target或 provider actor。API 不承诺跨线程 future，也不允许监听器在 committed event 回调中重入同一 region transfer；重入返回 `BUSY`。

### 外部 actor 持久协议

这是公开写入的关键门。provider 必须注册一种可跨重启解析的 actor adapter：

1. `providerId`、provider actor codec ID、`objectId`和actor epoch稳定、长度有界、canonical；codec ID改变时旧WAL不能交给新codec猜测。
2. capture返回有界canonical `ActorTruth`：epoch、`nextSequence`、完整last `TransferCompletionReceipt`、provider-defined exact payload及fingerprint。WAL保存exact before/after truth和fingerprint，不能只保存fingerprint后要求恢复阶段猜after。
3. provider提供纯`planAfter(beforeTruth, actualSettlement, operationIdentity)`；成功after必须把`nextSequence`加一并持久保存完整last receipt和after fingerprint。`apply-after`只在region HEAD已提交后执行，且对同一after truth幂等。
4. sequence小于当前next且不等于last applied时返回`STALE_RETIRED`，大于next返回`OUT_OF_ORDER`，epoch不匹配返回`ACTOR_REPLACED`；任何一种都不得重新规划。这样旧receipt正文可以压缩为actor high-watermark而不重新接受旧请求。
5. durable-save返回“已经强制保存”的证据；异步future或普通dirty flag不算完成。只有provider能持久保存epoch/sequence/last receipt的actor才可注册公开写入。
6. recovery只能通过持久identity重新发现actor，不能保存Java callback/class name。
7. provider缺失或actor所在chunk未加载返回UNAVAILABLE并保留WAL；actor变成第三状态返回CONFLICT/LOCKED。actor明确退休前必须持久保留epoch/high-watermark tombstone；否则object ID不得复用。

复用 `ContainerTransferJournal` 的 generation/settlement/after truth 与 `ControllerItemTransferCoordinator` 的提交顺序。若现有 journal 的 actor union 无法表示 provider identity，直接扩展封闭 union 和严格 codec；不要旁建“API transfer journal”。

## 事务顺序

外部转移应与容器保持同一个骨架：

1. 主线程解析实际 world target 和唯一 owner。
2. adapter capture actor exact before truth；验证operation epoch/sequence、request digest、last receipt并确认没有同region/actor WAL。若同一epoch/sequence及digest已在last receipt中，直接返回原actual result；同sequence不同digest返回`OPERATION_MISMATCH`，旧sequence返回`STALE_RETIRED`，都不接触region。
3. core 规划实际可转 settlement，包括体积/质量/species/residual/SVQ/U。
4. 发布 PREPARED WAL。
5. 在私有 region candidate 应用 exact transfer，capture target generation 并回读验证。
6. WAL 写入实际 settlement、exact actor after truth、operation identity和target checksum。
7. 发布 region HEAD。
8. 幂等应用包含新sequence与last receipt的actor after truth并强制保存、回读。
9. 只有after truth和receipt均durable后删除WAL。
10. 发布 committed event；监听器失败不得回滚事务。

如果资源跨两个 region 或设备两侧分别属于不同 region，不能连续执行两次单 region transfer。先设计 multi-HEAD coordinator：锁定 canonical region UUID 顺序、准备全部 target slots、发布协调 WAL、逐 HEAD 收敛并允许恢复完成。没有该 coordinator 前明确拒绝跨 region 原子请求。

### B03：单 region 公共 coordinator

不要让 public API 直接调用 `FlipFluidDomain.tryInsert...`。提取一个由现有玩家/发射器和公共 provider 共同调用的 `RegionTransferPlanner`，只负责 exact region candidate；`ControllerItemTransferCoordinator` 演化为或委托给统一 `RegionActorTransferCoordinator`，独占以下顺序：

1. 解析 profile-ready target、owner、cell/port、actor provider和当前 before truth。
2. 检查同actor、同region没有WAL；核对caller operation epoch/sequence、trace UUID与request digest。若actor last receipt的epoch/sequence和request digest匹配则返回原committed result；同sequence但digest不同返回`OPERATION_MISMATCH`，sequence已越过或跳号则拒绝。trace UUID不承担去重，不能用更换UUID绕过sequence门。
3. core 在不可变region快照上规划actual payload、source/sink与可选coupled `U`；provider的纯映射在captured before truth上应用同一actual payload得到after actor truth。两者都不修改live对象。
4. 发布PREPARED WAL，保存base/target generation、profile/registry fingerprint、provider actor codec ID、operation identity、exact before/after actor truth及planned actual settlement；opaque truth有逐项byte上限和checksum。
5. 在私有 region candidate 应用 exact settlement，完整 audit 后 stage/force/readback target slot。
6. 用 target manifest checksum 与 actor after fingerprint 覆盖为 REGION_COMMITTED WAL并 force。
7. 再核对 HEAD 仍为 base，然后发布 target HEAD；从此 region 不回滚。
8. provider幂等apply-after并执行其声明的durable save；返回的证据必须绑定object ID、epoch、after sequence、last receipt和after fingerprint。
9. 回读actor after truth；exact truth、fingerprint和receipt一致后删除WAL并同步目录entry。
10. 发布 committed event。监听器异常或服务器在 9/10 之间退出不改变库存；事件允许漏失。

player/dispenser继续使用专用Minecraft actor adapter，但物质planner与WAL state machine不再拥有第二份实现。创造source descriptor只允许真实creative `ServerPlayer`，descriptor item本身before=after；其operation epoch/sequence/last receipt由server-owned player creative-source actor state持久保存，region insert仍记external source。dispenser、fake player与public provider不能认领该actor；它不允许EXTRACT。创造装取使用真实container actor并走普通after stack。

### B04：provider recovery 决策表

| HEAD | WAL | actor state | 动作 |
|---|---|---|---|
| base | PREPARED/REGION_COMMITTED | before | 删除已知 staging与 WAL；region/actor 均保持 before |
| base | 任意 | after/third | CONFLICT，保留 WAL；不能倒推 actor 或提前提交 HEAD |
| target + checksum | REGION_COMMITTED | exact before | 幂等apply exact after、force save、回读epoch/sequence/receipt后删WAL |
| target + checksum | REGION_COMMITTED | exact after + receipt | force/证明durable、删WAL |
| target | PREPARED | 任意 | LOCKED；缺少权威 after/checksum，保留证据 |
| 其他 generation/checksum | 任意 | 任意 | LOCKED |
| base/target 可判定 | provider 缺失或 actor chunk 未加载 | unknown | UNAVAILABLE，保留 WAL 等待明确 provider/chunk 事件；不每 tick重试 |

provider 的 durable proof 不是随意 boolean。reference provider 若使用 BlockEntity inventory，必须在 owning dimension flush 完成后回读 exact after truth、epoch/sequence/last receipt与component fingerprint；若当前 Minecraft API 不能单对象 force，沿用 dispenser 的低频 dimension flush，而不是 dirty flag。provider 抛异常、返回错误 object ID、receipt或after fingerprint不一致都保留 WAL。

WAL strict codec扩展为actor封闭union；未知actor kind/actor codec、额外字段、错NBT type、generation/sequence overflow、profile mismatch和actual payload账本不闭合保持原文件并锁定。provider-defined exact truth只允许有界canonical bytes、codec ID和checksum，不保存provider Java class、lambda、registry object或世界坐标以外的runtime handle。

### B05：reference provider

仓库内实现一个最小 `reference_tank` BlockEntity，只为证明公共 API，不成为第二套 MMM inventory：

- tank自己持有一个完整可持久typed liquid或granular payload、capacity、actor epoch、next operation sequence和last completion receipt；不支持两类同时非空；
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

### B00：跨域 journal 的共同恢复代数

`ContainerTransferJournal`、`WorldActionJournal`与`FiniteDeviceJournal`可以有不同strict shape，但必须委托同一个JDK-only恢复判定器，而不是各自解释“before/after/third”。每个外部域都提供canonical identity、revision、exact before truth、exact after truth、durable-readback结果和`BEFORE/AFTER/THIRD/UNAVAILABLE`分类。共同规则固定为：

1. region HEAD只允许精确等于WAL的base，或等于target generation且manifest checksum匹配；其他状态一律LOCKED。
2. HEAD为base时，合法提交顺序尚未触碰任何外部after；只有所有外部域均为BEFORE时才能删除已知staging和WAL。任一AFTER或THIRD都是冲突证据，保留WAL且绝不能覆盖成before。
3. HEAD为target且WAL已是REGION_COMMITTED时，每个外部域可独立处于BEFORE或AFTER；对BEFORE幂等应用exact after，对AFTER只验证durability，全部完成后才能删WAL。任一THIRD锁定整个操作，不继续其他写入。
4. HEAD为target但WAL仍是PREPARED时缺少权威target/after证明，保持全部证据并LOCKED；不能从HEAD或世界外观补WAL字段。
5. UNAVAILABLE不改变任何truth，保留WAL并只在明确的provider/chunk/world加载事件后重试；不形成每tick轮询。
6. 多个world block或world+item的部分AFTER不算third；只有每个成员都逐一匹配其canonical before或after时才允许收敛剩余BEFORE。manifest成员、顺序和checksum必须在PREPARED时冻结。

每个具体journal的测试从这六条生成完整笛卡尔矩阵。实现者不得用“正常顺序不可能出现”跳过故障注入，因为崩溃、管理员操作和其他Mod都可能留下可观察组合。

B00只交付loader-neutral truth分类、decision/result值对象、矩阵生成fixture和让现有container recovery委托该判定器；不新增public transfer入口、world action store或fire行为。它可以在P1中先完成，C04复用同一判定器；B03/B06随后只能增加各自strict journal codec和external-domain adapter，不能复制switch表。

### B06：world action WAL

灭火等“region + world block”操作使用独立、通用但封闭的 `WorldActionJournal`，不滥用长期 ownership registry。journal 保存 action ID/kind、profile fingerprint、region/dimension、base/target generation、target manifest checksum、每个 block 的 canonical before/after、actual material/energy settlement 和 phase。提交顺序：

1. detector 在主线程冻结 before blocks、contact geometry和目标 region generation；
2. core 生成完整 region + world candidate；
3. stage/回读 region target；
4. 发布 PREPARED world-action WAL；
5. 用 actual settlement/checksum 覆盖为 REGION_COMMITTED WAL；
6. 发布 region HEAD；
7. 对每个 block 再比较 before，幂等应用 after并执行 owning dimension durable save；
8. 逐block回读after与durable save，删除WAL，发布committed event。

恢复严格使用共同代数：HEAD为base且全部block仍为before时只删除已知staging/WAL，不执行“还原before”；出现after或third都保留证据并CONFLICT。HEAD为target时允许多个block处于before/after混合并只补齐before；任一third形成CONFLICT，不能覆盖玩家的新方块。未知action kind或block state codec保留WAL并锁对应region。

### B07：灭火最小实现

最小数据只覆盖原版 `minecraft:fire`，但算法读取 stable fire definition。contact 必须是 flame face 与已加载 region 内 liquid PLIC/cut-face 的实际面积，不扫描“附近六格有无水”。thermal 默认 profile是前置；无 thermal candidate 不提供缩水版行为。

验收 fixture 固定比较：零接触、接触但热容量不足、可完整 quench、sealed headspace 发生后续蒸发、每个 WAL 故障点。通过证据包括 fire world state、region HEAD generation、外部 energy source、liquid/gas kg/species/`U` 和 pending phase-work，而不只是火焰消失。最近门为 `thermal,ownership`；journal strict/fault 覆盖若能归入 ownership 现有事务测试就不新增 selector。

### B08：gas 创造端口与后续公开范围

C05 气液 species 的玩家场景需要建立非 ambient gas inventory。P1 先交付一个只允许提交时仍为creative的真实`ServerPlayer`使用的`gas source/vent`正式端口；dispenser、fake player、automation与public provider全部拒绝这条无限source路径：

- target 由壳面和内侧 gas cell 唯一解析，必须属于一个 sealed node；ambient target 需要玩家明确确认 source/sink 语义；
- source descriptor 只含 gas component stable ID、请求标准体积 nanos和可选 K，服务端从 world gas/thermal/chemical registry 构造实际 component kg/species/residual/`U`；
- insert/extract在私有region candidate中联合更新component standard nanos、kg、species/residual、node `U`与gas ledger，HEAD是唯一提交点；descriptor item before=after，但player creative-source actor sequence/receipt必须durable；insert记external source，vent/extract逐actual truth记external sink；
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
| B00 | 现有`ContainerTransferRecovery`、ownership recovery、core新decision值对象 | JDK-only共同恢复判定器与生成矩阵 | `containers,ownership`，先选承载主要修改的一项 |
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
2. plan后region generation、profile fingerprint、actor exact before/epoch/sequence、target owner或tick任一变化，commit返回STALE且文件/region/actor字节不变；伪造、串actor、串server或过期token同样拒绝。
3. insert/extract exact与at-most都证明request、actual、WAL、after actor receipt、source/sink/event同量；成功响应丢失后以相同operation重试返回原result且generation不再增加，旧sequence重放只返回`STALE_RETIRED`。
4. B04与共同跨域代数的每一行、多个外部成员的before/after组合和每个故障点均覆盖；provider missing/chunk unloaded只在事件后重试，不形成tick日志风暴。
5. reference tank的block entity save/reload后exact truth、epoch/sequence/receipt/fingerprint不变；HEAD target + before actor可幂等完成，third state保留WAL；actor退休后object ID复用不同epoch不能接管旧WAL。
6. 玩家 material bucket迁入统一 planner前后，对同一 frozen state输出 identical actual settlement和候选 ledger。
7. fire覆盖 before 被玩家改变、HEAD前崩溃、HEAD后崩溃、world durable save失败、thermal/phase candidate失败；失败时不删火且不改 region。
8. gas source/vent覆盖sealed/ambient、component不允许species、P/T越界、capacity、topology lineage变化和source/sink/`U`闭合；dispenser/fake player/automation调用creative source时双方字节不变。

### 提交顺序与人工验收

1. B00共同恢复判定器和现有container委托；不注册新写入口。
2. P1需要的B08 gas creative port可在A07/A08后独立完成；public gas ABI留到另一次明确评审。
3. P2开始B01 public值对象与校验，不注册可写入口。
4. B02 provider registry、thread/authorization、只读plan/token table。
5. B03/B04统一coordinator、journal actor union和故障恢复，只复用B00。
6. B05 reference provider insert，再提交extract与跨重启恢复。
7. 迁移一个现有internal container planner，删除重复路径。
8. 按真实消费者扩展gas/solid Query；不要为“也许有用”扩字段。
9. B06 world-action journal/core planner，再单独提交B07 fire detector/resources。
10. B09、B10、B11按明确玩家/适配需求各自开切片；P2只要求其中B07灭火，不能用未实现的列表阻止已经闭合的公共transfer。
11. B12只在对应commit point稳定后逐event提交，不预建空事件层次。

先用restart harness和故障注入关闭全部recovery行；只有自动门仍无法观察真实BlockEntity/world save接线时才请求游戏。允许的人工证据是两个分别申请的单一场景：TX-01让reference provider在REGION_COMMITTED/actor-before状态退出重进后收敛；VI-01让一处实际fire接触液面后HEAD/world共同完成。每次只给该场景的步骤、预期generation/WAL和日志证据，不合并两场景，也不联调多个第三方Mod。
