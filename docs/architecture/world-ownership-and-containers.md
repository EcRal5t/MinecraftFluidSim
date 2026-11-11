# 世界 ownership 与容器事务契约

修改世界方块接管与释放、桶、发射器、清理区域或事务恢复时读本文。

单位与整数编码统一见[设计书](system-overview.md#units)。
`ContainerFluidLedger.cubicMetersToNanos` 与 `nanosToCubicMeters` 接受和返回物理 m³；归一化液体体积先通过 `SolverUnitScale` 换算。
容器注入、抽取与 `ChemicalContents.toLedger` 显式接收目标区域的尺度；命令和操作回执使用 m³。

## 修改入口

- **世界方块归属**
  - 代码入口：
    - [OwnershipTransactionCoordinator](../../fabric/src/main/java/mmm/simulation/OwnershipTransactionCoordinator.java)
    - [OwnershipCoordinator](../../fabric/src/main/java/mmm/simulation/ownership/OwnershipCoordinator.java)
  - 测试选择器：`ownership`

- **容器操作入口**
  - 代码入口：
    - [FluidPortBlock](../../fabric/src/main/java/mmm/block/FluidPortBlock.java)：六向容器口与外壳方块。
    - [FluidWorldTargetResolver](../../fabric/src/main/java/mmm/simulation/FluidWorldTargetResolver.java)：集中解析正面端口与直接内部目标。
    - [PlayerContainerTransferAdapter](../../fabric/src/main/java/mmm/simulation/PlayerContainerTransferAdapter.java)
    - [DispenserContainerTransferAdapter](../../fabric/src/main/java/mmm/simulation/DispenserContainerTransferAdapter.java)
    - [MinecraftContainerTransferPlan](../../fabric/src/main/java/mmm/simulation/MinecraftContainerTransferPlan.java)
  - 测试选择器：`containers`

- **物品数据与日志文件**
  - 代码入口：
    - [ContainerItemMapper](../../fabric/src/main/java/mmm/item/ContainerItemMapper.java)
    - [ContainerJournalCodec](../../fabric/src/main/java/mmm/simulation/persistence/ContainerJournalCodec.java)
    - [ContainerJournalFileStore](../../fabric/src/main/java/mmm/simulation/persistence/ContainerJournalFileStore.java)
  - 测试选择器：`containers`

- **事务提交与恢复**
  - 代码入口：
    - [ContainerTransferCoordinator](../../app/src/main/java/mmm/application/ContainerTransferCoordinator.java)
    - [ContainerRecoveryCoordinator](../../app/src/main/java/mmm/application/ContainerRecoveryCoordinator.java)
    - [CrossDomainRecovery](../../model/src/main/java/mmm/recovery/CrossDomainRecovery.java)
  - 测试选择器：`containers`

- **清空、排气和移除**
  - 代码入口：
    - [MinecraftRegionLifecycle](../../fabric/src/main/java/mmm/simulation/MinecraftRegionLifecycle.java)
    - [RegionLifecycleUseCase](../../app/src/main/java/mmm/application/RegionLifecycleUseCase.java)
  - 测试选择器：`lifecycle,region-state`

- **转移前同步相变几何**
  - 代码入口：[MinecraftPendingPhaseWorkBarrier](../../fabric/src/main/java/mmm/simulation/MinecraftPendingPhaseWorkBarrier.java)
  - 测试选择器：`containers`

执行方式见 [验证指南](../maintenance/validation.md#host)。

## 三个持久域

controller 产品跨越三个具有独立生命周期的持久域：

- region-state：marker、材料、化学、固体、气体和热学库存，唯一 commit point 是 region HEAD；
- Minecraft world/actor：方块、玩家手中物品或 dispenser inventory；
- sidecar WAL/registry：记录跨域事务足以确定恢复方向的 before/after truth。

跨域操作先持久准备可重建结果，最终提交由 region HEAD 决定。容器和 ownership 释放采用区域先提交；原版水接管先移除并耐久保存世界水方块，再提交区域。共同恢复判定器按明确的提交顺序决定回滚外部前态或完成后态。

## Ownership 模型

controller 内部接管的方块标记为空气，水体由 solver 内部粒子与网格权威表示。

新激活区域只有在 world bridge 已生效时才接管内部纯 `Blocks.WATER`：

1. 只读扫描 controller footprint、required chunk、预算、既有 region overlap 和实际纯原版水 preimage，并绑定当前 profile fingerprint；
2. 构造尚未登记到 live catalog 的完整 domain candidate，初始化 profile 要求的全部 side column，再准备新的完整 region generation；
3. 发布 ownership WAL 与 WARNING；
4. 修改世界方块并完成所属维度耐久保存；
5. 持久写入包含 target checksum 的提交阶段，再发布 region HEAD；
6. 建立 committed ownership 记录并清理 WAL。

无水接管时同样先 stage、回读并发布首个 region HEAD。只有 HEAD 回读成功后，`MinecraftRegionActivation` 才把 candidate 登记并选入 live catalog、发送 snapshot 和返回创建成功；此前 Query、网络和其他 tick 看不到该区域。

失败 candidate 不进入 catalog，也不发送 removal 来掩盖幽灵状态。controller domain 的原版水采样只接受持久 footprint interior 内的纯 `Blocks.WATER`，不会把 waterlogged、相邻其他内腔或 dense bounds 中不归属的水复制成 marker。

恢复根据 region HEAD、WAL 的 base/target generation、target manifest checksum、操作 UUID 和提交阶段，由 `CrossDomainRecovery` 判定重放或回滚。`OwnershipTransactionCoordinator` 负责 Minecraft footprint/preimage、live checkpoint capture
与结果写回，并把 WAL→世界→HEAD→committed 状态机委托给既有低层 `OwnershipCoordinator`；
方块读取、修改和保存由世界适配器负责。

未知文件、损坏 NBT、身份不符、generation 歧义或玩家已放置的非水方块一律保留并锁定，不猜测修复，也不覆盖玩家方块。

已加载 solver 在 step、empty、checkpoint 和转移前也必须检查自己的 ownership sidecar；锁只作用于对应区域，但无法识别 region 的 store issue 会形成全局创建锁。

### 接管事务

扫描与世界修改在同一个 server tick 内进行，候选只接受纯原版 water，并保存每个 block 的完整 preimage。
region target slot 必须先写完、回读并验证，然后才发布 ownership WAL 与醒目的 WARNING 文件。
移除每个方块时再次比较 preimage；扫描后被玩家改变的方块导致事务中止，不会被静默跳过。

HEAD 发布前崩溃时，恢复看到 `HEAD == baseGeneration`，把世界尽可能恢复到 preimage；只有 identity、紧邻 generation 与严格文件布局都证明是本事务的 inactive staging 才可丢弃，未知证据保留并锁定。

玩家已占据的位置优先，记为 conflict 而不覆盖。HEAD 发布后崩溃时，`HEAD == targetGeneration`，region inventory 已权威，恢复只重建/核对 committed ownership registry，不再恢复水 preimage。

### 释放事务

release 只有在凝聚相、密闭气体、gas `U` 和有限设备库存全部为空，且 ownership/container WAL 无歧义时才允许。它使用共同区域先提交判定：先发布空 ownership 的 region target，再尝试恢复已登记 preimage并耐久保存世界。玩家占据的位置保持不变并进入结果统计。

committed registry 记录 region、dimension、generation 和确切 owned block/preimage 集。日常 reconcile 只分类 `OWNED_AIR`、`PREIMAGE_PRESENT` 和 `OCCUPIED`，不自动把冲突方块改回空气或水。

## 原版小池接管与归还

`MinecraftPoolOperations` 由探针提供玩家操作：正常右击预览，限时潜行右击同池确认。服务器保存玩家、维度、选择范围及原水方块状态，确认重新扫描。`NativePoolScanner` 在已加载世界中寻找连通水源块，要求水平敞开水面和完整实心池底、池壁；范围包含一层顶部空气，按稠密网格预算接纳。

`RegionGeometry.ControllerFootprint` 与 Fabric 范围描述保存 `CLOSED` / `POOL`、大气开口和已归还位置。池体使用环境气体，普通控制器使用密闭外壳。完整描述随 manifest 编码、校验和与恢复传递。水源块按每块1 m³初始化，完整能力候选沿既有 ACQUIRE 事务发布后才登记。

归还从当前库存准备私有候选。只有完整方块体积、对应质量、纯水组成、参考温度内能和零格面动量的水才生成原版水源。热水、混合物、零头、颗粒和运动中的水保持原库存；没有通过原版水表示的内容继续由区域保存。归还操作暂停区域，玩家可以继续提取余量。

`RETURN_WATER` 使用区域先提交：目标 HEAD 同时保存实际库存扣除和已归还位置，WAL保存原版水后态及继续接管的位置；随后生成世界水源、耐久保存世界并清理事务。已归还位置从模拟空间排除，方块修改钩子阻止原版水进入仍受接管的空间。恢复按共同判定器完成后态，遇到占据位置保留事务。全部库存和设备为空时解除剩余归属，沿原移除事务结束区域。接管时保存的历史水方块只参与接管失败回滚。

主要入口：`MinecraftPoolOperations.probe`、`MinecraftRegionActivation.activatePool`、`NativePoolScanner.scan`、`OwnershipTransactionCoordinator.returnPoolWater`、`OwnershipCoordinator.returnWater`、`MaterialTransferPlanner.returnNativeWaterCells`。验证沿 `boundary`、`ownership` 和区域存档入口组织。

## 区域生命周期

- 控制器检查闭壳，池体检查天然底壁与大气开口；世界区块是否加载决定关联物理组的运行资格。
- UNLOADED 区域不持 solver，但仍占 catalog 的 cell/marker reservation。
- 已提交 manifest 的 metadata 先登记、再按本次启动的总预算和 peer overlap 做 runtime admission；临时超预算或重叠只阻止 solver restore，不得让 committed region 从 owner 索引消失。
- checkpoint 成功后才能释放 solver；恢复成功后先刷新 collision，再发送 snapshot。
- controller boundary 与 controller block 在区域仍受生命周期管理时不可被玩家破坏；必须先通过显式生命周期操作解除 ownership 并移除区域。
- 删除区域前必须证明凝聚相、密闭气体、gas `U`、有限设备、ownership、所有 WAL 和 pending phase-work 全为空，并通过 quarantine admission。`remove` 不把未知状态解释成可删除数据。

边界壳、interior set 和 required chunk set 来自持久 footprint。controller 方块存在不等于区域可运行：每次 admission/refresh 都必须核对 dimension、controller identity、
完整闭壳和 chunk load。边界打开时停止 step 和修改操作；不会用 chunk ticket 把缺失世界强行加载。

破坏性操作只通过 controller console 的服务端预览/确认协议进入，要求与 `/mmm` Brigadier 命令相同的 server command-source 内建管理员权限。
预览 token 只存在内存、短时有效、绑定玩家和一次确认；确认重新验证 generation、动作、权限、WAL、availability、solver 与有限设备 identity。
四个动作的职责严格分开：

- `EMPTY_CONDENSED` 联合清除 liquid、chemical、active/deposit/coherent condensed truth，把实际物质与凝聚相 `U` 记入 operator escaped/external sink；保留 controller、ownership 和 sealed gas。
- `VENT_SEALED_GAS` 联合清除 sealed component nanos/kg/species/residual 与实际 gas `U`，分别记入 gas escaped sink 和 thermal external sink；
  闭壳的完整 gas membership、available volume 与 edge 保持不变，提交结果为库存与 `U` 严格为零的密闭真空。
  显式 resume 后仍可运行，后续库存只能来自真实容器、相变或拓扑 transfer。
- `RELEASE_OWNERSHIP` 只在凝聚相、气体和有限设备为空且无 WAL/phase work 后恢复 preimage；玩家已经占据的方块优先，不被覆盖。
- `REMOVE_EMPTY_REGION` 只在上述库存为空、ownership 已释放且 quarantine 可接纳时发布 `REMOVED` HEAD。随后发送 network 删除标记、注销 runtime，并把旧 ACTIVE payload 收敛到 `mmm/quarantine/removed-region/`；UUID 永不复用。

旧的 `/mmm empty` 只保留给非 controller 开发域；controller 管理的区域必须手持物质场探针普通右击控制器，在控制台按 `EMPTY_CONDENSED`、`VENT_SEALED_GAS`、`RELEASE_OWNERSHIP`、`REMOVE_EMPTY_REGION` 依次预览并确认。

控制器方块只在真正空手时处理 select/run-intent 激活；任何持物右击都先交还物品入口，不能吞掉探针或容器交互。纯 wall-time `PERFORMANCE_PAUSE` 不代表库存或账本不可解释，因此仍允许 `drain`、容器装取和上述只减少库存的 lifecycle；
已加载且 HEAD、registry 与 WAL 均可证明的 `NUMERICAL_PAUSE` 也允许上述私有候选式 lifecycle 减库存，以便清空、排气、释放和移除故障区域。

预览记录当前状态前，先通过共享的相变几何处理器执行一次仅含力学的计算，完成相变后几何与压力的同步。失败时，预览保留 `PENDING_PHASE_WORK` 阻止原因。

两类操作后都保持暂停，需显式 resume。边界、ownership、pending WAL、无效 HEAD、registry/profile、开放 audit、无法结算的 phase work 或 quarantine blocker 仍在预览中明确列出并阻止提交。

## 容器数据

- 液体容量使用 `long` 纳体积，1 block volume = 1,000,000,000 nanos。
- 材料桶保存每个稳定材料的纳体积与独立 kg 质量。
- 化学桶另外保存 species μmol 整数、quantaPerMol 和整数质量余量。
- 干颗粒容器使用整数 SVQ、逐材料 kg 质量与可选 solid composition。
- 热液体另外保存 registry-bound 总内能。
- 任何非空 MMM actual container 还保存创建它的 world capability profile fingerprint；空容器不伪造 profile 身份。
- item count 必须为 1；damage、tooltip、durability bar、颜色和温度不参与结算。

item fingerprint 完整编码各字段以供结算：kind、typed amount、stable mixture/chemical/granular contents、统一质量整数 `massUnits`、可选 ThermalContents 及其 registry/underlying
hash，以及 actual inventory 的 world profile fingerprint。

`ContainerTransferJournal` 只保存这一数据，Fabric `ContainerItemMapper` 独占 fingerprint 与 `ItemStack` 的双向映射。
空桶、部分桶、材料桶、化学桶、颗粒容器和 creative descriptor 是不同 shape；shape 之间缺字段或同时出现冲突 component 都拒绝。

actual container 缺失、损坏或不匹配当前世界 profile 时在准备 WAL 前以 `FOREIGN_PROFILE` 拒绝，恢复也必须以已提交世界 profile 复核 WAL；不能按当前数据包重新解释旧世界库存。

液体抽取允许生成不足 1 m³ 的 partial item；放入已有部分桶时仍按实际剩余容量规划。`FluidMixture`、`ChemicalContents`、`GranularContents` 和 `ThermalContents` 都有自己的规范编码与总账，item component 只是这些原始数据的载体。

创造栏中的 `CreativeSourceDescriptor` 记录生成意图（材料 ID、数量、温度），具体库存由服务端的 `CreativeSourceCatalog` 依据世界材料定义实例化。

每次使用时服务端重新确认玩家仍处于创造模式，按当前已提交 world registry 和封闭 catalog 解析材料/species/SVQ/kg/U，拒绝未知 source、错误数量和越过物性有效域的温度。
descriptor item 在事务前后保持相同；对热区域，coordinator 用独立的 server-resolved actual source stack 准备 coupled `U`，不能从 descriptor 猜能量。

## 玩家与发射器路由

`mmm:fluid_port` 是控制器外壳上的显式容器 I/O 口。六向 `FACING` 指向外侧正面，反方向紧邻的内部方块提供转移目标。容器事务以该方块与端口接触面相邻的求解格为起点，并检查该格活动组可用性；直接命中的内部方块使用中心格。驱动方块以同一规则选择接触格。端口保持完整方块碰撞与固定固体几何，自身没有自主流动，也不连接两个区域。

- 玩家从端口正面命中时，装入和装取均使用端口内侧目标。侧面、背面、壳边、壳角及多个区域归属均拒绝。命中普通围墙或无效端口直接结束本次操作。
- 发射器必须紧邻端口正面并朝向端口，其发射方向与端口 `FACING` 相反。液体与颗粒共用这一路由。
- 无外壳遮挡时，直接命中区域内部液体的桶操作和既有内部目标路由保持可用。
- 端口所在 chunk 及目标区域 solver 已驻留才可解析；目标选择只读，不加载 chunk、不恢复区域。后续写入仍需通过区域可用性和事务检查。
- 创造气体源沿真实壳面命中规则定位目标气格，使用逐格气体库存与内能源汇事务。

插入和抽取先在私有候选上规划实际份额，再共同提交区域与容器后态。请求数量是上限，返回实际转移量；容量、来源库存或可结算精度可能使实际量更小，未选库存完整保留。创造模式放出记为 external source，创造模式装取仍从区域删除实际库存。

typed actual container 与 creative source 的 controller-region 转移依赖世界能力包和区域写入可用；world bridge 决定是否接管或恢复壳内原版世界流体。关闭 bridge 后，已有 typed inventory 仍可沿容器事务转移。

`FluidWorldTargetResolver.transferTargetAtBlock` 集中执行目标选择，`resolvePortTarget` 要求朝向匹配、同维度、唯一已加载区域及其紧邻内部格。玩家与发射器适配将结果交给共同容器计划；公共 Transfer API 在预览和提交前同时核对真实发射器朝向、端口及请求内部格，见[受控转移](external-api.md#受控转移)。

目标解析发生在事务准备之前。发射器的 dimension、整数 block coordinates 和 slot 构成持久 actor 定位；已发布 WAL 按保存的区域 HEAD、物品前后态及 actor 身份恢复。端口后续改变不改变已发布事务的恢复方向。

actor identity 与 journal 位于 core，是不引用 `BlockPos`、`ItemStack` 或 loader 类型的封闭数据；各 actor kind 只能携带自己的完整字段，不能用 null 填充无关字段。

同一 region 或同一确切 actor identity 存在 WAL 时不得开始另一笔容器事务。恢复时 actor 离线或 owning chunk 未加载是 `UNAVAILABLE`，保留 WAL 等待；
slot/block entity 改变或当前 item 既不等于 before 也不等于 after 是 `CONFLICT`，保留 WAL 并 LOCKED，不能每 tick 猜测或把物品塞到其他手/slot。

将来增加外部 Mod actor 时必须给它独立的稳定 provider/type identity 和跨重启可解析状态，不能把运行期 callback 写进 WAL。

insert/extract 返回“实际转移量”，WAL 和 after item 必须记录该实际转移量。液体用 nanos，颗粒用 SVQ；
两个资源域不能共用模糊 `amount`。含热纯水可按同质体积分拆 coupled `U`；材料/化学混合物必须按源桶共同温度和实际选中质量/组分计算 coupled `U`，item 余量以源 `U` 减法结算浮点余数，
禁止按体积比例折算热容量。

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

`model/recovery/CrossDomainRecovery` 是 region HEAD 与一个或多个外部持久成员的共同判定器；
现有 container adapter 只负责把 durable actor readback 分类为 `BEFORE`、`AFTER`、`THIRD` 或 `UNAVAILABLE`，不能复制 commit switch。

ownership 与容器已委托同一判定器：区域先提交允许丢弃未提交候选或完成外部后态；外部先提交在 base HEAD 时执行外部回滚。不可读成员等待，第三态保留事务供冲突处理。世界恢复完成及 WAL 清理之前需确认维度保存完成。设备 journal 沿同一规则提供自己的具体载荷。region、ownership 与 container 的紧邻代次统一拒绝负数和 `long` 溢出，不能让 `Long.MAX_VALUE + 1` 被误认成合法 target。

任何歧义都保留 WAL 并锁定；不得因为 item 看起来相似就推断完成状态。

当前 WAL 有材料布局和含热布局。`ContainerJournalCodec` 只接受当前 writer 产生的完整字段集合：缺字段、错 NBT 类型、额外未知字段和溢出的 generation pair 都保持原文件并锁定，
不做补全或忽略。`ContainerJournalFileStore` 只负责 discover/read/write/delete、临时文件回读、atomic replace 与目录同步，不解释 HEAD/actor/recovery 状态。

loader-neutral `ContainerTransferUseCase` 验证 actor/item/profile/thermal truth，`ContainerTransferCoordinator` 独占 WAL、staged checkpoint 与 HEAD
发布顺序；Fabric `MinecraftContainerTransferPlan` 是 player/dispenser 共用的 capability、contents、exact primitive 选择，两个 actor adapter 只分别负责准星/手或朝向/槽位以及最终
Minecraft 输出。

mixin 与原版 dispenser registry 分别直连这两个 adapter，不保留旧 transfer service。更精确的运行序列是：

1. 验证目标区域可修改、无其他 WAL、actor item count 为 1，并捕获 before fingerprint。
2. 原子发布 `PREPARED` WAL；此时 after=before，尚无 target checksum。
3. 从 live base capture 恢复一个未注册私有 solver，在私有 solver 上执行 exact transfer；拒答或 after-item 校验失败时删除 PREPARED，live solver 与 HEAD 均保持 base。
4. 构造最终 after item；从同一个私有 solver candidate capture 到 inactive region slot 并完整回读。
5. 原子用 `REGION_COMMITTED` WAL 覆盖原 WAL，写入实际量、after fingerprint、coupled energy 和 target manifest checksum。
6. 仅在这份 WAL 已 durable 后发布 region HEAD；发布成功后 server thread 原子接管已验证的私有 domain，并推进 solver generation 使旧 backend candidate 失效。
   HEAD 一旦指向 target，region 侧不可回滚；若 publish 后的 live 接管异常，则锁定并从 target HEAD 收敛，不能退回 base。
7. actor adapter 把 item 收敛为 after；`ContainerRecoveryCoordinator` 随即或在对应 player/chunk availability 事件上驱动玩家同步 save 或 owning dimension flushing
   save。Minecraft 当前没有公开的单 chunk durable save，因此 dispenser 完成路径宁可承担低频的 dimension flush，也不能用非 flush autosave 后提前删除 WAL。
8. durable save 后回读 exact after truth；只有回读仍为 after 才由 recovery coordinator 删除 WAL 并移出 boot index。删除失败只留下可幂等收敛的 WAL，不重复资源转移；committed event 只能在删除完成后发布。

玩家保存由原版 `PlayerList.saveAll` 执行，但其正常返回不能单独证明存盘成功。适配器先使用原版 player serializer 捕获当前数据并拒绝编码错误，再保存；
[PlayerInventorySaveVerifier](../../fabric/src/main/java/mmm/simulation/persistence/PlayerInventorySaveVerifier.java) 强制写入并回读 `playerdata/<uuid>.dat`，
逐项核对 UUID、完整 Inventory、SelectedItemSlot 和 equipment（包括副手）。核对完成后仍需确认在线物品等于事务 after。
缺失、过期或读取失败的文件保留 WAL，等待 I/O 重试。

WAL/WARNING/committed/HEAD 的文件内容在 rename 前 `force`，rename 与事务完成后的 delete 还要同步父目录 entry；
支持目录 channel 的文件系统执行 directory fsync。Windows JDK 不暴露目录 channel，因此仍依赖同卷原子 rename 与已强制写入的文件内容，但不会把一次普通进程崩溃误当成已提交。

`REGION_COMMITTED` 是“WAL 已经拥有权威 after item”的含义，不表示 HEAD 必然已经移动；实际 commit 仍由 HEAD 判断。

### 恢复决策表

| Region HEAD | WAL phase | 恢复动作 |
|---|---|---|
| `base` | 任意 | container 私有 candidate 从未取得 live 提交权；actor exact before 时丢弃未提交 WAL，actor after/third 锁定 |
| `target` + checksum 相符 | `REGION_COMMITTED` | actor before/after 均可幂等收敛；若同进程 live 仍为 base，先从 target generation 完整恢复并接管，再删除 WAL；actor 强制保存并回读 exact after，third 锁定 |
| `target` | `PREPARED` | 锁定：region 已提交但 WAL 没有权威 after item |
| 其他 generation 或 checksum 不符 | 任意 | 锁定：无法证明 region 属于本事务 |

任一外部成员 `UNAVAILABLE` 时不改任何 truth 并保留 WAL。玩家 actor 只在该玩家完成协议加入时重试；dispenser actor 只在其 owning dimension/chunk 加载时重试，不在 tick 中轮询。

I/O 失败保留独立的重试状态，由单调时钟安排 1、2、4、8、16、30 秒退避，后续最多每 30 秒重试一次，每次重新核对 HEAD、校验和与 actor。
首次失败报告日志，连续失败不逐 tick 重复报告。重试期间 actor 离线则转入上述事件等待；身份、物品或校验和冲突，以及运行时校验异常仍保持锁定。
成功删除 WAL 后才发布完成事件。`ContainerApplicationValidation` 通过可注入时钟验证这些时序，无需实际等待。

多成员事务只有每个成员都分别匹配 before 或 after 才能收敛剩余 before；成员索引与顺序由 journal 冻结。
恢复在判定前还必须验证 dimension、resource kind、base/target 连续性、actor identity、before/after shape、所有适用 registry fingerprint 和 thermal underlying hash。

任一未知内容都原样保留。

container-transfer 根目录是严格短期恢复域：未知文件名、目录、残留 `.tmp`、文件名与 transaction UUID 不同或损坏 NBT 会形成全局容器事务锁，因为无法安全得知它属于哪个区域。`empty` 和 region 删除也必须把“存在任何未决或不可读 WAL”视为拒绝条件。

## 守恒边界

对 insert：item resource 减少量 = region external source 增加量；对 extract：region external sink/transfer ledger 增加量 = item resource 增加量。

材料质量、species、solid composition 和总内能分别闭合，不能只凭体积相等通过事务。含热抽取以实际 carrier `U` 的 exact dyadic 变化为物理量，整件物品只舍入一次；
external sink 使用物品保存的 raw energy，舍入差单列 thermal roundoff，不能逐 marker 累积近似后令 item 与 region 分叉。

液体部分倾倒由实际两份质量与组成求热容，小热容份额按源温度直接计算内能，大热容份额接收精确相减后一次舍入的余量；纯水按小体积份额执行同一余量分配。
区域接收选中份额内能，物品保存剩余份额内能。raw 源物品与 raw 剩余物品的精确内能差在 coupled energy/WAL 中舍入一次，作为 external source；
它与区域实收能量的差额进入 thermal roundoff。所有份额必须满足实际库存的正温校验，极小余量的热状态受原始能量列的可表示精度限制。

创造放出没有物品侧减少，因此明确记 region external source；创造装取仍必须从 region 删除，只是 Minecraft 保留玩家物品。这个不对称是显式 actor 语义，不得隐藏在 tooltip 或 item damage 中。


## 有限设备与固定表面装拆

`FiniteHeatExchangerOperations` 组织区域有限设备、世界方块与物品的共同事务。区域 HEAD 保存热库存及表面组分，`FiniteDeviceJournalStore` 保存操作身份、提交代、完整物品前后态和对应世界位置。有限换热器安装后替换围墙槽位，拆卸后恢复围墙并交回实际设备物品；表面材料装填与取回使用同一事务，附着面及吸附量保存在墙载体中。

表面材料物品携带装填质量、被吸附物种、分数量子余量和内能。取回的内能由当前共同墙温和材料状态函数计算，墙与物品按同一接受量交接。保留装填的区域继续持有这些库存，先拆卸可取回材料和设备，再释放区域归属。已卸载区域的表面查询直接读取 HEAD 中的设备库存。

恢复按 HEAD 和 WAL 决定操作前态或已提交后态，并补齐世界和物品成员。玩家离线、区块不可用或磁盘写入失败时保留未完成操作，恢复后继续；实际后态完成持久保存后清理 WAL。普通墙的多个接触面共享有限内能，静止表面材料随设备 checkpoint 保存。

入口：[FiniteHeatExchangerOperations](../../fabric/src/main/java/mmm/simulation/FiniteHeatExchangerOperations.java)、[FiniteDeviceJournalStore](../../fabric/src/main/java/mmm/simulation/persistence/FiniteDeviceJournalStore.java)、[SurfaceCoatingContents](../../model/src/main/java/mmm/thermal/SurfaceCoatingContents.java)、[SurfaceThermalState](../../model/src/main/java/mmm/thermal/SurfaceThermalState.java)。

## 耕地湿润

原版 `FarmlandBlock.MOISTURE` 保存0–7档土壤水库存，每档1 L，满湿最多7 L。MMM补水要求耕地表面实际接触水相，接触库存足额提供1 L后才提交湿度加一；不足时保留原库存与世界湿度。油和其他非水材料不参与补水。

水相提取携带实际材料质量、species与内能，区域分别记录向原版土壤的外部汇。土壤的原版状态保存量化水库存；雨水和原版水块补水为独立环境来源，原版湿度降低为土壤向环境散失。土壤使用现有方块状态，无新增BlockEntity。

`FarmlandMoistureOperations.tickAvailable` 组织可用区域的吸水事务，`FarmlandJournalStore` 保存操作UUID、区域、维度、能力profile、base/target代次、目标checksum、提交阶段，以及逐耕地位置的湿度前后态和真实提取清单。清单携带实际来源库存，不借用容器身份。区域HEAD决定物质提交，WAL负责恢复对应MOISTURE；base保留前态，target补齐世界后态，未知第三态暂停并保留恢复证据。私有完整候选准备成功后依次写PREPARED、准备目标checkpoint、写已提交阶段WAL、推进HEAD，再补齐各耕地MOISTURE后态；世界统一耐久保存并回读后才清理WAL。`recoverIndexed` 沿共同 `CrossDomainRecovery` 恢复，第三态或暂不可读时保留WAL并暂停区域，重试不重复提水或增加湿度。

接触以四侧实际15/16方块高度和同代水相材料层裁剪；顶部淹水按当前固定微盒碰撞的1 m顶面查询，保留这项网格近似。实现状态与验证范围见当前实施计划。

未完成WAL涉及的位置通过 `LevelBlockMutationMixin` 暂停普通世界状态改写；事务内部仅放行当前维度、位置和预期湿度后态，写入结束即撤销豁免。恢复完成后解除保护，防止随机失湿、踩踏等改变已保存的前后状态。未知WAL证据的保护范围限于已登记控制器附近的耕地。主动暂停区域停止补水，静水休眠中的区域仍可按有界扫描找到实际水接触，成功补水后唤醒力学。
