# 世界 ownership 与容器事务契约

修改世界方块接管与释放、桶、发射器、区域清理或事务恢复时读本文。单位与整数编码统一见[设计书](system-overview.md#units)；容器账本以物理 m³ 换算，命令和回执使用 m³。

## 修改入口

| 职责 | 代码入口 | 测试选择器 |
|---|---|---|
| 世界方块归属 | [OwnershipTransactionCoordinator](../../fabric/src/main/java/mmm/simulation/OwnershipTransactionCoordinator.java)、[OwnershipCoordinator](../../fabric/src/main/java/mmm/simulation/ownership/OwnershipCoordinator.java) | `ownership` |
| 容器操作路由 | [FluidPortBlock](../../fabric/src/main/java/mmm/block/FluidPortBlock.java)、[FluidWorldTargetResolver](../../fabric/src/main/java/mmm/simulation/FluidWorldTargetResolver.java)、[PlayerContainerTransferAdapter](../../fabric/src/main/java/mmm/simulation/PlayerContainerTransferAdapter.java)、[DispenserContainerTransferAdapter](../../fabric/src/main/java/mmm/simulation/DispenserContainerTransferAdapter.java)、[MinecraftContainerTransferPlan](../../fabric/src/main/java/mmm/simulation/MinecraftContainerTransferPlan.java) | `containers` |
| 物品数据与 WAL 文件 | [ContainerItemMapper](../../fabric/src/main/java/mmm/item/ContainerItemMapper.java)、[ContainerJournalCodec](../../fabric/src/main/java/mmm/simulation/persistence/ContainerJournalCodec.java)、[ContainerJournalFileStore](../../fabric/src/main/java/mmm/simulation/persistence/ContainerJournalFileStore.java) | `containers` |
| 事务提交与恢复 | [ContainerTransferCoordinator](../../app/src/main/java/mmm/application/ContainerTransferCoordinator.java)、[ContainerRecoveryCoordinator](../../app/src/main/java/mmm/application/ContainerRecoveryCoordinator.java)、[CrossDomainRecovery](../../model/src/main/java/mmm/recovery/CrossDomainRecovery.java) | `containers` |
| 清空、排气和移除 | [MinecraftRegionLifecycle](../../fabric/src/main/java/mmm/simulation/MinecraftRegionLifecycle.java)、[RegionLifecycleUseCase](../../app/src/main/java/mmm/application/RegionLifecycleUseCase.java) | `lifecycle,region-state` |
| 转移前同步相变几何 | [MinecraftPendingPhaseWorkBarrier](../../fabric/src/main/java/mmm/simulation/MinecraftPendingPhaseWorkBarrier.java) | `containers` |

执行方式见[验证指南](../maintenance/validation.md#host)。

## 三个持久域

controller 区域跨越三个独立的持久域：

- **region-state**：液体、材料、化学、颗粒、气体和热学库存，提交点是区域 HEAD；
- **Minecraft 世界与 actor**：方块、玩家手中物品、发射器物品栏；
- **sidecar WAL**：保存足以确定恢复方向的前态与后态。

跨域操作先持久准备完整后态，提交由区域 HEAD 决定。容器转移与 ownership 释放采用区域先提交；原版水接管先移除世界水方块并保存维度，再提交区域。`CrossDomainRecovery` 是所有跨域事务的共同判定器：区域 HEAD 仍在 base 时回滚外部成员或丢弃未提交候选，HEAD 已到 target 时补齐外部后态。外部成员暂不可读时保留 WAL 等待，出现第三态时保留事务并锁定。

## Ownership 模型

controller 内部接管的世界方块记为空气，水体由区域库存表示。committed registry 记录区域、维度、generation 与确切的接管方块及其原状态。日常核对只分类 `OWNED_AIR`、`PREIMAGE_PRESENT` 和 `OCCUPIED`，冲突方块保持原样。

**接管**：world bridge 生效时，新区域接管 footprint 内部的纯 `Blocks.WATER`。扫描与世界修改在同一 server tick 内完成，先准备并回读完整区域 generation，再发布 ownership WAL，然后逐块比较原状态并移除水方块、保存维度，最后发布区域 HEAD。扫描后被玩家改动的方块使事务中止。区域只有在首个 HEAD 回读成功后才登记到 live catalog 并对查询、网络和其他 tick 可见。

**释放**：要求凝聚相、密闭气体、气体内能和有限设备全部为空，且没有挂起的 WAL。先发布不再持有 ownership 的区域 target，再恢复原状态并保存世界；玩家已占据的位置保留并计入结果。

未知文件、损坏数据、身份不符或 generation 歧义都原样保留并锁定对应区域；无法识别所属区域的 store 问题形成全局创建锁。

## 原版小池接管与归还

物质场探针右击水池预览，限时潜行右击确认。`NativePoolScanner` 在已加载世界中寻找连通水源块，要求水平敞开水面与完整实心池底、池壁，范围包含一层顶部空气。footprint 记录 `CLOSED` / `POOL`、大气开口和已归还位置，池体使用环境气体。水源块按每块 1 m³ 初始化，沿接管事务发布。

归还只把完整方块体积、纯水组成、参考温度内能且静止的水生成原版水源；热水、混合物、零头、颗粒和运动中的水继续留在区域内，玩家可以继续提取。`RETURN_WATER` 采用区域先提交：目标 HEAD 同时保存库存扣除与已归还位置，随后生成世界水源并保存世界。已归还位置从模拟空间排除，方块修改钩子阻止原版水进入仍受接管的空间。库存和设备全部为空时，沿移除事务结束区域。

入口：`MinecraftPoolOperations`、`MinecraftRegionActivation.activatePool`、`NativePoolScanner`、`OwnershipTransactionCoordinator.returnPoolWater`。

## 区域生命周期

- 控制器区域要求完整闭壳，池体要求天然底壁与大气开口；所在区块是否加载决定关联物理组能否运行。footprint 的外壳、内部集合和所需区块集合都来自持久数据，准入与刷新时重新核对，区块缺失时暂停运行，不强制加载。
- UNLOADED 区域不持有 solver，仍占用 catalog 配额；已提交 manifest 先登记再做运行准入，超预算或重叠只阻止恢复，详见[区域 checkpoint 契约](region-state.md)。
- 区域受管理期间玩家无法破坏控制器与外壳，需先通过生命周期操作释放并移除。

破坏性操作经控制器控制台的服务端预览/确认协议执行，要求与 `/mmm` 命令相同的管理员权限。预览令牌短时有效、绑定玩家且只能确认一次，确认时重新核对 generation、权限、WAL、可用性与设备身份。预览前先执行一次仅含力学的计算以完成相变后的几何与压力同步，未完成时以 `PENDING_PHASE_WORK` 阻止。四个动作依次进行：

| 动作 | 效果 |
|---|---|
| `EMPTY_CONDENSED` | 清除液体、化学、颗粒与凝聚相库存及其内能，计入操作者外部汇；保留 ownership 与密闭气体 |
| `VENT_SEALED_GAS` | 清除密闭气体库存与内能；闭壳气体拓扑保留，结果是零库存的密闭真空，显式 resume 后仍可运行 |
| `RELEASE_OWNERSHIP` | 库存与设备为空、无 WAL 后恢复接管方块原状态 |
| `REMOVE_EMPTY_REGION` | ownership 已释放且隔离区可接纳时发布 `REMOVED` HEAD，注销运行时并隔离旧载荷 |

操作后区域保持暂停。纯 wall-time 性能暂停和数据可证明的数值暂停区域都允许这些只减少库存的操作以及容器装取。控制器方块只在空手时处理选择与运行意图，持物右击交给物品处理。

## 容器数据

- 液体容量以 `long` 纳体积计，1 方块体积 = 10⁹ nanos；材料桶保存逐材料纳体积与 kg 质量。
- 化学桶另存物种 μmol 整数、`quantaPerMol` 与整数质量余量。
- 干颗粒容器保存整数 SVQ、逐材料 kg 与固相组成。
- 热液体另存与 registry 绑定的总内能。
- 非空容器保存创建时的世界 profile 指纹，跨世界物品以 `FOREIGN_PROFILE` 拒绝。
- 物品数量固定为 1；损耗、tooltip、颜色和温度显示不参与结算。

item fingerprint 完整编码类型、数量、内容、整数质量、热内容与 profile 指纹。`ContainerItemMapper` 独占 fingerprint 与 `ItemStack` 的双向映射，各容器形态字段互斥。液体抽取可生成不足 1 m³ 的部分桶。

创造栏物品保存生成意图（材料、数量、温度），服务端每次使用时确认创造模式，按已提交 registry 解析出实际库存与内能。

## 玩家与发射器路由

`mmm:fluid_port` 是控制器外壳上的容器口。`FACING` 指向外侧，反方向紧邻的内部方块提供转移目标；事务从与端口接触面相邻的求解格开始，直接命中内部方块时使用中心格。端口保持完整方块碰撞，自身不流动，也不连接两个区域。

- 玩家从端口正面命中时使用端口内侧目标；侧面、背面、普通围墙和多区域归属都拒绝。
- 发射器须紧邻端口正面并朝向端口，液体与颗粒共用此路由。
- 无外壳遮挡时，直接命中区域内部液体的桶操作保持可用。
- 目标解析只读，要求端口区块与目标 solver 已驻留；创造气体源按壳面命中定位气格。

`FluidWorldTargetResolver` 集中执行目标选择；公共 Transfer API 在预览与提交前同样核对发射器朝向、端口和内部格，见[受控转移](external-api.md#受控转移)。

插入与抽取在私有候选上规划实际份额，请求数量是上限，返回实际转移量。同一区域或同一 actor 已有 WAL 时拒绝新事务。actor 身份是 loader 无关的封闭数据：玩家 UUID，或发射器的维度、方块坐标与槽位。world bridge 只决定是否接管壳内原版流体，关闭后已有容器库存仍可转移。

## 跨持久域 WAL

容器事务的顺序：

1. 发布 `PREPARED` WAL，保存事务与区域身份、base/target generation、资源类型、actor 和前态物品。
2. 从 base 状态恢复未注册的私有 solver，执行精确转移，并把 target generation 写入非活动 slot、回读。
3. 以 `REGION_COMMITTED` 覆盖 WAL，写入实际量、后态物品、耦合内能和 target manifest 校验和。
4. 发布区域 HEAD，server thread 接管已验证的私有域。
5. actor 物品收敛到后态，玩家数据或发射器所在维度完成持久保存并回读确认后删除 WAL。

| 区域 HEAD | WAL 阶段 | 恢复动作 |
|---|---|---|
| base | 任意 | actor 为前态时丢弃 WAL；后态或第三态锁定 |
| target，校验和相符 | `REGION_COMMITTED` | actor 幂等收敛到后态，持久保存并回读后删除 WAL |
| target | `PREPARED` | 锁定 |
| 其他 | 任意 | 锁定 |

actor 离线或区块未加载时保留 WAL，在玩家加入或区块加载事件上重试。I/O 失败按指数退避重试，每次重新核对 HEAD、校验和与 actor。玩家存档通过 [PlayerInventorySaveVerifier](../../fabric/src/main/java/mmm/simulation/persistence/PlayerInventorySaveVerifier.java) 强制写入并回读核对。

`ContainerJournalCodec` 只接受当前格式的完整字段集合；`container-transfer` 目录出现未知文件或损坏数据时形成全局容器事务锁，清空与删除区域同样以挂起 WAL 为阻止条件。

## 守恒

插入时物品资源减少量等于区域外部源增加量，抽取时区域外部汇增加量等于物品资源增加量。材料质量、物种、固相组成和内能分别闭合。

部分倾倒按实际两份质量与组成求热容：小热容份额按源温度计算内能，大热容份额取精确相减后的余量。物品与区域的耦合内能在 WAL 中只舍入一次，差额计入热舍入账。创造放出记为区域外部源；创造装取从区域删除实际库存并保留玩家物品。

<a id="有限换热器安装与拆卸"></a>
## 有限设备与固定表面装拆

`FiniteHeatExchangerOperations` 组织有限设备、世界方块与物品的共同事务。区域 HEAD 保存设备热库存与表面组分，`FiniteDeviceJournalStore` 保存操作身份、提交代、物品前后态与世界位置。换热器安装后替换围墙槽位，拆卸后恢复围墙并交回设备物品；表面材料装填与取回走同一事务，附着面及吸附量保存在墙载体中。

表面材料物品携带装填质量、被吸附物种、量子余量和内能，取回时按当前墙温与材料状态函数计算内能。区域需先取回材料和设备再释放归属。恢复规则与容器 WAL 相同。

入口：[FiniteHeatExchangerOperations](../../fabric/src/main/java/mmm/simulation/FiniteHeatExchangerOperations.java)、[FiniteDeviceJournalStore](../../fabric/src/main/java/mmm/simulation/persistence/FiniteDeviceJournalStore.java)、[SurfaceCoatingContents](../../model/src/main/java/mmm/thermal/SurfaceCoatingContents.java)、[SurfaceThermalState](../../model/src/main/java/mmm/thermal/SurfaceThermalState.java)。

## 耕地湿润

原版 `FarmlandBlock.MOISTURE` 的 0–7 档表示土壤水库存，每档 1 L。耕地表面实际接触水相且能足额提供 1 L 时，湿度加一；油和其他非水材料不参与。提取的质量、物种与内能记入区域向土壤的外部汇。雨水和原版水块补水作为独立环境来源，原版湿度降低视为向环境散失。接触判定使用四侧 15/16 方块高度与同代水相材料层，顶部淹水按固定微盒碰撞的顶面查询。

`FarmlandMoistureOperations` 组织吸水事务，`FarmlandJournalStore` 保存逐耕地位置的湿度前后态与实际提取清单，恢复沿 `CrossDomainRecovery` 进行。未完成 WAL 涉及的耕地由 `LevelBlockMutationMixin` 阻止普通世界改写，恢复完成后解除。静水休眠的区域仍会扫描水接触，补水成功后唤醒力学。
