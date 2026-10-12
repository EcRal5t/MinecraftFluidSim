# 世界 ownership 与容器事务契约

修改世界方块接管与释放、桶、发射器、清理区域或事务恢复时读本文。

## 修改入口

| 要改什么 | 代码入口 | 测试选择器 |
|---|---|---|
| 世界方块归属 | [OwnershipTransactionCoordinator](../../fabric/src/main/java/mmm/simulation/OwnershipTransactionCoordinator.java)、[OwnershipCoordinator](../../fabric/src/main/java/mmm/simulation/ownership/OwnershipCoordinator.java) | `ownership` |
| 容器操作入口 | [PlayerContainerTransferAdapter](../../fabric/src/main/java/mmm/simulation/PlayerContainerTransferAdapter.java)、[DispenserContainerTransferAdapter](../../fabric/src/main/java/mmm/simulation/DispenserContainerTransferAdapter.java)、[MinecraftContainerTransferPlan](../../fabric/src/main/java/mmm/simulation/MinecraftContainerTransferPlan.java) | `containers` |
| 物品数据与日志文件 | [ContainerItemMapper](../../fabric/src/main/java/mmm/item/ContainerItemMapper.java)、[ContainerJournalCodec](../../fabric/src/main/java/mmm/simulation/persistence/ContainerJournalCodec.java)、[ContainerJournalFileStore](../../fabric/src/main/java/mmm/simulation/persistence/ContainerJournalFileStore.java) | `containers` |
| 事务提交与恢复 | [ContainerTransferCoordinator](../../app/src/main/java/mmm/application/ContainerTransferCoordinator.java)、[ContainerRecoveryCoordinator](../../app/src/main/java/mmm/application/ContainerRecoveryCoordinator.java)、[CrossDomainRecovery](../../model/src/main/java/mmm/recovery/CrossDomainRecovery.java) | `containers` |
| 清空、排气和移除 | [MinecraftRegionLifecycle](../../fabric/src/main/java/mmm/simulation/MinecraftRegionLifecycle.java)、[RegionLifecycleUseCase](../../app/src/main/java/mmm/application/RegionLifecycleUseCase.java) | `lifecycle,region-state` |
| 转移前同步相变几何 | [MinecraftPendingPhaseWorkBarrier](../../fabric/src/main/java/mmm/simulation/MinecraftPendingPhaseWorkBarrier.java) | `containers,thermal` |

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [三个持久域](#三个持久域)
- [Ownership 模型](#ownership-模型)
- [区域生命周期](#区域生命周期)
- [容器数据](#容器数据)
- [玩家与发射器路由](#玩家与发射器路由)
- [跨持久域 WAL](#跨持久域-wal)
- [守恒边界](#守恒边界)

## 三个持久域

controller 产品跨越三个具有独立生命周期的持久域：

- region-state：marker、材料、化学、固体、气体和热学库存，唯一 commit point 是 region HEAD；
- Minecraft world/actor：方块、玩家手中物品或 dispenser inventory；
- sidecar WAL/registry：记录跨域事务足以确定恢复方向的 before/after truth。

所有跨域操作都采用“先持久准备与可重建结果，后发布 region HEAD，再把外部域收敛到已提交结果”的次序。WAL 专用于记录跨域事务步骤与故障回滚，事务最终以 region HEAD 提交为准。

## Ownership 模型

controller 内部接管的方块标记为空气，水体由 solver 内部粒子与网格权威表示。

新激活区域只有在 world bridge 已生效时才接管内部纯 `Blocks.WATER`：

1. 只读扫描 controller footprint、required chunk、预算、既有 region overlap 和实际纯原版水 preimage，并绑定当前 profile fingerprint；
2. 构造尚未登记到 live catalog 的完整 domain candidate，初始化 profile 要求的全部 side column，再准备新的完整 region generation；
3. 发布 ownership WAL 与 WARNING；
4. 修改世界方块；
5. 发布 region HEAD；
6. 建立 committed ownership 记录并清理 WAL。

无水接管时同样先 stage、回读并发布首个 region HEAD。只有 HEAD 回读成功后，`MinecraftRegionActivation` 才把 candidate 登记并选入 live catalog、发送 snapshot 和返回创建成功；此前 Query、网络和其他 tick 看不到该区域。

失败 candidate 不进入 catalog，也不发送 removal 来掩盖幽灵状态。controller domain 的原版水采样只接受持久 footprint interior 内的纯 `Blocks.WATER`，不会把 waterlogged、相邻其他内腔或 dense bounds 中不归属的水复制成 marker。

恢复根据 region HEAD 与 WAL 的 base/target generation 判定重放或回滚。`OwnershipTransactionCoordinator` 负责 Minecraft footprint/preimage、live checkpoint capture 与结果写回，并把 WAL→世界→HEAD→committed 状态机委托给既有低层 `OwnershipCoordinator`；它不复制恢复代数。

未知文件、损坏 NBT、身份不符、generation 歧义或玩家已放置的非水方块一律保留并锁定，不猜测修复，也不覆盖玩家方块。

已加载 solver 在 step、empty、checkpoint 和转移前也必须检查自己的 ownership sidecar；锁只作用于对应区域，但无法识别 region 的 store issue 会形成全局创建锁。

### 接管事务

扫描与世界修改在同一个 server tick 内进行，候选只接受纯原版 water，并保存每个 block 的完整 preimage。region target slot 必须先写完、回读并验证，然后才发布 ownership WAL 与醒目的 WARNING 文件。移除每个方块时再次比较 preimage；扫描后被玩家改变的方块导致事务中止，不会被静默跳过。

HEAD 发布前崩溃时，恢复看到 `HEAD == baseGeneration`，把世界尽可能恢复到 preimage；只有 identity、紧邻 generation 与严格文件布局都证明是本事务的 inactive staging 才可丢弃，未知证据保留并锁定。

玩家已占据的位置优先，记为 conflict 而不覆盖。HEAD 发布后崩溃时，`HEAD == targetGeneration`，region inventory 已权威，恢复只重建/核对 committed ownership registry，不再恢复水 preimage。

### 释放事务

release 只有在凝聚相、密闭气体、gas `U` 和有限设备库存全部为空，且 ownership/container WAL 无歧义时才允许。它使用同一套 base/target 判定，但世界操作方向相反：先尝试恢复已登记 preimage，再发布空 ownership 的 region target。玩家占据的位置保持不变并进入结果统计。

committed registry 记录 region、dimension、generation 和确切 owned block/preimage 集。日常 reconcile 只分类 `OWNED_AIR`、`PREIMAGE_PRESENT` 和 `OCCUPIED`，不自动把冲突方块改回空气或水。

## 区域生命周期

- controller 必须有完整闭壳和全部已加载 chunk。
- UNLOADED 区域不持 solver，但仍占 catalog 的 cell/marker reservation。
- 已提交 manifest 的 metadata 先登记、再按本次启动的总预算和 peer overlap 做 runtime admission；临时超预算或重叠只阻止 solver restore，不得让 committed region 从 owner 索引消失。
- checkpoint 成功后才能释放 solver；恢复成功后先刷新 collision，再发送 snapshot。
- controller boundary 与 controller block 在区域仍受生命周期管理时不可被玩家破坏；必须先通过显式生命周期操作解除 ownership 并移除区域。
- 删除区域前必须证明凝聚相、密闭气体、gas `U`、有限设备、ownership、所有 WAL 和 pending phase-work 全为空，并通过 quarantine admission。`remove` 不把未知状态解释成可删除数据。

边界壳、interior set 和 required chunk set 来自持久 footprint。controller 方块存在不等于区域可运行：每次 admission/refresh 都必须核对 dimension、controller identity、完整闭壳和 chunk load。边界打开时停止 step 和修改操作；不会用 chunk ticket 把缺失世界强行加载。

破坏性操作只通过 controller console 的服务端预览/确认协议进入，要求与 `/mmm` Brigadier 命令相同的 server command-source 内建管理员权限。预览 token 只存在内存、短时有效、绑定玩家和一次确认；确认重新验证 generation、动作、权限、WAL、availability、solver 与有限设备 identity。四个动作的职责严格分开：

- `EMPTY_CONDENSED` 联合清除 liquid、chemical、active/deposit/coherent condensed truth，把实际物质与凝聚相 `U` 记入 operator escaped/external sink；保留 controller、ownership 和 sealed gas。
- `VENT_SEALED_GAS` 联合清除 sealed component nanos/kg/species/residual 与实际 gas `U`，分别记入 gas escaped sink 和 thermal external sink；闭壳的完整 gas membership、available volume 与 edge 保持不变，提交结果为库存与 `U` 严格为零的密闭真空。显式 resume 后仍可运行，后续库存只能来自真实容器、相变或拓扑 transfer。
- `RELEASE_OWNERSHIP` 只在凝聚相、气体和有限设备为空且无 WAL/phase work 后恢复 preimage；玩家已经占据的方块优先，不被覆盖。
- `REMOVE_EMPTY_REGION` 只在上述库存为空、ownership 已释放且 quarantine 可接纳时发布 `REMOVED` HEAD。随后发送 network 删除标记、注销 runtime，并把旧 ACTIVE payload 收敛到 `mmm/quarantine/removed-region/`；UUID 永不复用。

旧的 `/mmm empty` 只保留给非 controller 开发域；controller 管理的区域必须手持物质场探针普通右击控制器，在控制台按 `EMPTY_CONDENSED`、`VENT_SEALED_GAS`、`RELEASE_OWNERSHIP`、`REMOVE_EMPTY_REGION` 依次预览并确认。

控制器方块只在真正空手时处理 select/run-intent 激活；任何持物右击都先交还物品入口，不能吞掉探针或容器交互。纯 wall-time `PERFORMANCE_PAUSE` 不代表库存或账本不可解释，因此仍允许 `drain`、容器装取和上述只减少库存的 lifecycle；已加载且 HEAD、registry 与 WAL 均可证明的 `NUMERICAL_PAUSE` 也允许上述私有候选式 lifecycle 减库存，以便清空、排气、释放和移除故障区域。

预览记录当前状态前，先通过共享的相变几何处理器执行一次仅含力学的计算，完成相变后几何与压力的同步。失败时，预览保留 `PENDING_PHASE_WORK` 阻止原因。

两类操作后都保持暂停，需显式 resume。边界、ownership、pending WAL、无效 HEAD、registry/profile、开放 audit、无法结算的 phase work 或 quarantine blocker 仍在预览中明确列出并阻止提交。

## 容器数据

- 液体容量使用 `long` 纳体积，1 block volume = 1,000,000,000 nanos。
- 材料桶保存每个稳定材料的纳体积与独立 kg 质量。
- 化学桶另外保存 species quanta、indicator 和 kg mass residual。
- 干颗粒容器使用整数 SVQ、逐材料 kg 质量与可选 solid composition。
- 热液体另外保存 registry-bound 总内能。
- 任何非空 MMM actual container 还保存创建它的 world capability profile fingerprint；空容器不伪造 profile 身份。
- item count 必须为 1；damage、tooltip、durability bar、颜色和温度不参与结算。

item fingerprint 完整编码各字段以供结算：kind、typed amount、stable mixture/chemical/granular contents、raw kg mass bits、可选 ThermalContents 及其 registry/underlying hash，以及 actual inventory 的 world profile fingerprint。

`ContainerTransferJournal` 只保存这一数据，Fabric `ContainerItemMapper` 独占 fingerprint 与 `ItemStack` 的双向映射。空桶、部分桶、材料桶、化学桶、颗粒容器和 creative descriptor 是不同 shape；shape 之间缺字段或同时出现冲突 component 都拒绝。

actual container 缺失、损坏或不匹配当前世界 profile 时在准备 WAL 前以 `FOREIGN_PROFILE` 拒绝，恢复也必须以已提交世界 profile 复核 WAL；不能按当前数据包重新解释旧世界库存。

液体抽取允许生成不足 1 BV 的 partial item；放入已有部分桶时仍按实际剩余容量规划。`FluidMixture`、`ChemicalContents`、`GranularContents` 和 `ThermalContents` 都有自己的规范编码与总账，item component 只是这些原始数据的载体。

创造栏中的 `CreativeSourceDescriptor` 记录生成意图（材料 ID、数量、温度），具体库存由服务端的 `CreativeSourceCatalog` 依据世界材料定义实例化。

每次使用时服务端重新确认玩家仍处于创造模式，按当前已提交 world registry 和封闭 catalog 解析材料/species/SVQ/kg/U，拒绝未知 source、错误数量和越过物性有效域的温度。descriptor item 在事务前后保持相同；对热区域，coordinator 用独立的 server-resolved actual source stack 准备 coupled `U`，不能从 descriptor 猜能量。

## 玩家与发射器路由

- 玩家只按准星命中的 controller 壳面和其正后方实际内部格选择区域。
- 发射器只按发射方向正对的壳面中心路由；壳边、壳角、多个候选和未加载目标拒绝。
- 插入/抽取原语 all-or-nothing。insert 先规划 marker/parcel 和容量，extract 先做双精度预检，再按整 marker 与尾拆分结算。
- 创造模式放出是 external source；创造模式装取仍从 solver 删除实际库存，不能制造隐藏 sink。

typed actual container 与 creative source 的 controller-region 转移只依赖世界能力包和区域写入可用，不依赖 world-bridge 政策；world bridge 只决定是否接管或恢复壳内原版世界流体。关闭 bridge 不能把已经由 controller/WAL 持有的 typed inventory 变成不可转移状态。

目标解析发生在事务准备之前。玩家命中壳的外侧、内部格、区域 UUID 和 dimension 必须唯一；dispenser 朝向只参与最初的目标解析，其 dimension、整数 block coordinates 和 slot 构成持久 actor identity。

actor identity 与 journal 位于 core，是不引用 `BlockPos`、`ItemStack` 或 loader 类型的封闭数据；各 actor kind 只能携带自己的完整字段，不能用 null 填充无关字段。

同一 region 或同一确切 actor identity 存在 WAL 时不得开始另一笔容器事务。恢复时 actor 离线或 owning chunk 未加载是 `UNAVAILABLE`，保留 WAL 等待；slot/block entity 改变或当前 item 既不等于 before 也不等于 after 是 `CONFLICT`，保留 WAL 并 LOCKED，不能每 tick 猜测或把物品塞到其他手/slot。

将来增加外部 Mod actor 时必须给它独立的稳定 provider/type identity 和跨重启可解析状态，不能把运行期 callback 写进 WAL。

insert/extract 返回“实际转移量”，WAL 和 after item 必须记录该实际转移量。液体用 nanos，颗粒用 SVQ；两个资源域不能共用模糊 `amount`。含热纯水可按同质体积分拆 coupled `U`；材料/化学混合物必须按源桶共同温度和实际选中质量/组分计算 coupled `U`，item 余量以源 `U` 减法结算浮点余数，禁止按体积比例折算热容量。

## 跨持久域 WAL

容器事务覆盖 region generation、actor item 和可选 registry：

1. `PREPARED` 保存 transaction/region/dimension、base/target generation、operation、typed resource、actor 和 before item。
2. 从 base 冻结状态恢复私有 solver candidate，并只在该 candidate 执行 exact transfer。
3. 从私有 candidate stage 并回读完整 target generation；live solver 仍保持 base。
4. `REGION_COMMITTED` 增加实际量、after item 和 target manifest checksum。
5. 原子发布 region HEAD。
6. 修改 actor item，并等待玩家存档或 dispenser chunk 完成 durable save。
7. 删除 WAL。

恢复必须核对 HEAD、checksum、actor、before/after item、resource kind、world profile fingerprint、各适用 registry fingerprint 和 generation。

`core/recovery/CrossDomainRecovery` 是 region HEAD 与一个或多个外部持久成员的共同判定器；现有 container adapter 只负责把 durable actor readback 分类为 `BEFORE`、`AFTER`、`THIRD` 或 `UNAVAILABLE`，不能复制 commit switch。

未来 world action、外部 provider 或设备 journal 也必须委托同一判定器。region、ownership 与 container 的紧邻代次统一拒绝负数和 `long` 溢出，不能让 `Long.MAX_VALUE + 1` 被误认成合法 target。

任何歧义都保留 WAL 并锁定；不得因为 item 看起来相似就推断完成状态。

当前 WAL 有材料布局和含热布局。`ContainerJournalCodec` 只接受当前 writer 产生的完整字段集合：缺字段、错 NBT 类型、额外未知字段和溢出的 generation pair 都保持原文件并锁定，不做补全或忽略。`ContainerJournalFileStore` 只负责 discover/read/write/delete、临时文件回读、atomic replace 与目录同步，不解释 HEAD/actor/recovery 状态。

loader-neutral `ContainerTransferUseCase` 验证 actor/item/profile/thermal truth，`ContainerTransferCoordinator` 独占 WAL、staged checkpoint 与 HEAD 发布顺序；Fabric `MinecraftContainerTransferPlan` 是 player/dispenser 共用的 capability、contents、exact primitive 选择，两个 actor adapter 只分别负责准星/手或朝向/槽位以及最终 Minecraft 输出。

mixin 与原版 dispenser registry 分别直连这两个 adapter，不保留旧 transfer service。更精确的运行序列是：

1. 验证目标区域可修改、无其他 WAL、actor item count 为 1，并捕获 before fingerprint。
2. 原子发布 `PREPARED` WAL；此时 after=before，尚无 target checksum。
3. 从 live base capture 恢复一个未注册私有 solver，在私有 solver 上执行 exact transfer；拒答或 after-item 校验失败时删除 PREPARED，live solver 与 HEAD 均保持 base。
4. 构造最终 after item；从同一个私有 solver candidate capture 到 inactive region slot 并完整回读。
5. 原子用 `REGION_COMMITTED` WAL 覆盖原 WAL，写入实际量、after fingerprint、coupled energy 和 target manifest checksum。
6. 仅在这份 WAL 已 durable 后发布 region HEAD；发布成功后 server thread 原子接管已验证的私有 domain，并推进 solver generation 使旧 backend candidate 失效。HEAD 一旦指向 target，region 侧不可回滚；若 publish 后的 live 接管异常，则锁定并从 target HEAD 收敛，不能退回 base。
7. actor adapter 把 item 收敛为 after；`ContainerRecoveryCoordinator` 随即或在对应 player/chunk availability 事件上驱动玩家同步 save 或 owning dimension flushing save。Minecraft 当前没有公开的单 chunk durable save，因此 dispenser 完成路径宁可承担低频的 dimension flush，也不能用非 flush autosave 后提前删除 WAL。
8. durable save 后回读 exact after truth；只有回读仍为 after 才由 recovery coordinator 删除 WAL 并移出 boot index。删除失败只留下可幂等收敛的 WAL，不重复资源转移；committed event 只能在删除完成后发布。

玩家保存由原版 `PlayerList.saveAll` 执行，但其正常返回不能单独证明存盘成功。适配器先使用原版 player serializer 捕获当前数据并拒绝编码错误，再保存；[PlayerInventorySaveVerifier](../../fabric/src/main/java/mmm/simulation/persistence/PlayerInventorySaveVerifier.java) 强制写入并回读 `playerdata/<uuid>.dat`，逐项核对 UUID、完整 Inventory、SelectedItemSlot 和 equipment（包括副手）。核对完成后仍需确认在线物品等于事务 after。缺失、过期或读取失败的文件保留 WAL，等待 I/O 重试。

WAL/WARNING/committed/HEAD 的文件内容在 rename 前 `force`，rename 与事务完成后的 delete 还要同步父目录 entry；支持目录 channel 的文件系统执行 directory fsync。Windows JDK 不暴露目录 channel，因此仍依赖同卷原子 rename 与已强制写入的文件内容，但不会把一次普通进程崩溃误当成已提交。

`REGION_COMMITTED` 是“WAL 已经拥有权威 after item”的含义，不表示 HEAD 必然已经移动；实际 commit 仍由 HEAD 判断。

### 恢复决策表

| Region HEAD | WAL phase | 恢复动作 |
|---|---|---|
| `base` | 任意 | container 私有 candidate 从未取得 live 提交权；actor exact before 时丢弃未提交 WAL，actor after/third 锁定 |
| `target` + checksum 相符 | `REGION_COMMITTED` | actor before/after 均可幂等收敛；若同进程 live 仍为 base，先从 target generation 完整恢复并接管，再删除 WAL；actor 强制保存并回读 exact after，third 锁定 |
| `target` | `PREPARED` | 锁定：region 已提交但 WAL 没有权威 after item |
| 其他 generation 或 checksum 不符 | 任意 | 锁定：无法证明 region 属于本事务 |

任一外部成员 `UNAVAILABLE` 时不改任何 truth 并保留 WAL。玩家 actor 只在该玩家完成协议加入时重试；dispenser actor 只在其 owning dimension/chunk 加载时重试，不在 tick 中轮询。

I/O 失败保留独立的重试状态，由单调时钟安排 1、2、4、8、16、30 秒退避，后续最多每 30 秒重试一次，每次重新核对 HEAD、校验和与 actor。首次失败报告日志，连续失败不逐 tick 重复报告。重试期间 actor 离线则转入上述事件等待；身份、物品或校验和冲突，以及运行时校验异常仍保持锁定。成功删除 WAL 后才发布完成事件。`ContainerApplicationValidation` 通过可注入时钟验证这些时序，无需实际等待。

多成员事务只有每个成员都分别匹配 before 或 after 才能收敛剩余 before；成员索引与顺序由 journal 冻结。恢复在判定前还必须验证 dimension、resource kind、base/target 连续性、actor identity、before/after shape、所有适用 registry fingerprint 和 thermal underlying hash。

任一未知内容都原样保留。

container-transfer 根目录是严格短期恢复域：未知文件名、目录、残留 `.tmp`、文件名与 transaction UUID 不同或损坏 NBT 会形成全局容器事务锁，因为无法安全得知它属于哪个区域。`empty` 和 region 删除也必须把“存在任何未决或不可读 WAL”视为拒绝条件。

## 守恒边界

对 insert：item resource 减少量 = region external source 增加量；对 extract：region external sink/transfer ledger 增加量 = item resource 增加量。

材料质量、species、solid composition 和总内能分别闭合，不能只凭体积相等通过事务。含热抽取以实际 carrier `U` 的 exact dyadic 变化为物理量，整件物品只舍入一次；external sink 使用物品保存的 raw energy，舍入差单列 thermal roundoff，不能逐 marker 累积近似后令 item 与 region 分叉。

液体部分倾倒由实际两份质量与组成求热容，小热容份额按源温度直接计算内能，大热容份额接收精确相减后一次舍入的余量；纯水按小体积份额执行同一余量分配。区域接收选中份额内能，物品保存剩余份额内能。raw 源物品与 raw 剩余物品的精确内能差在 coupled energy/WAL 中舍入一次，作为 external source；它与区域实收能量的差额进入 thermal roundoff。所有份额必须满足实际库存的正温校验，极小余量的热状态受原始能量列的可表示精度限制。

创造放出没有物品侧减少，因此明确记 region external source；创造装取仍必须从 region 删除，只是 Minecraft 保留玩家物品。这个不对称是显式 actor 语义，不得隐藏在 tooltip 或 item damage 中。
