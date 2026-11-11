# 外部 API 契约

接入其他 Mod，或修改查询、提交通知、探针和 Jade 时读本文。单位与整数编码统一见[设计书](system-overview.md#units)。

## 修改入口

| 职责 | 代码入口 | 测试选择器 |
|---|---|---|
| 接口发现 | [MmmFabricApi](../../fabric/src/main/java/mmm/api/fabric/MmmFabricApi.java) | `protocol` |
| 只读查询 | [FabricFluidQueryApi](../../fabric/src/main/java/mmm/simulation/FabricFluidQueryApi.java)、[FluidCellInventoryView](../../solver/src/main/java/mmm/fluid/FluidCellInventoryView.java) | `protocol` |
| 提交完成事件 | [CommittedEventHub](../../fabric/src/main/java/mmm/simulation/CommittedEventHub.java)、[ContainerRecoveryCoordinator](../../app/src/main/java/mmm/application/ContainerRecoveryCoordinator.java) | `protocol,containers` |
| 受控转移与回执 | [MmmTransferApi](../../api/src/main/java/mmm/api/transfer/MmmTransferApi.java)、[FabricTransferApi](../../fabric/src/main/java/mmm/simulation/FabricTransferApi.java)、[ContainerTransferManifests](../../model/src/main/java/mmm/transfer/ContainerTransferManifests.java)、[PublicTransferReceiptStore](../../fabric/src/main/java/mmm/simulation/persistence/PublicTransferReceiptStore.java) | `protocol,containers` |
| 测点摘要 | [ProcessSensorSnapshot](../../api/src/main/java/mmm/api/sensor/ProcessSensorSnapshot.java)、[ProcessSensorBlockEntity](../../fabric/src/main/java/mmm/block/ProcessSensorBlockEntity.java)、[ProcessSensorSnapshotCodec](../../fabric/src/main/java/mmm/sensor/ProcessSensorSnapshotCodec.java) | `ProcessSensorSnapshotValidation` 等 |
| 外部规定运动结构 | [MovingStructureApi](../../api/src/main/java/mmm/api/motion/MovingStructureApi.java)、[ExternalMovingStructures](../../fabric/src/main/java/mmm/simulation/ExternalMovingStructures.java) | `structure-handoff`、`external-motion` |
| 探针与控制台 | [ShellFluidQuery](../../fabric/src/main/java/mmm/simulation/ShellFluidQuery.java)、[MinecraftControllerConsole](../../fabric/src/main/java/mmm/simulation/MinecraftControllerConsole.java)、[ControllerQueryUseCase](../../app/src/main/java/mmm/application/ControllerQueryUseCase.java) | `protocol,lifecycle` |
| 恒温源界面 | [ThermalReservoirUseCase](../../app/src/main/java/mmm/application/ThermalReservoirUseCase.java) | `protocol` |
| 物料箱存储 | [MaterialBoxItemStorage](../../fabric/src/main/java/mmm/simulation/MaterialBoxItemStorage.java)、[MaterialBoxBlockEntity](../../fabric/src/main/java/mmm/block/MaterialBoxBlockEntity.java) | `containers` |

执行方式见[验证指南](../maintenance/validation.md#host)。Jade 适配分为[服务端](../../fabric/src/main/java/mmm/compat/jade/)和[客户端](../../fabric/src/client/java/mmm/compat/jade/)。

## 稳定范围

loader 无关的契约位于 `api` 模块：`mmm.api.query`（同步查询）、`mmm.api.transfer`（受控转移与共享数量类型）、`mmm.api.event`（提交完成通知）、`mmm.api.motion`（规定运动结构）、`mmm.api.sensor`（测点摘要）。Fabric 调用方通过 `MmmFabricApi.query/transfers/events/movingStructures(server)` 取得实现。

`MmmQueryApi.UNIT_SYSTEM = "SI;chemical-quantum=1umol;volume-nanos=1e-9m3;SVQ=2^-27m3"`。接口只使用 JDK 类型，不暴露 Minecraft 对象、solver 数组或活动区域引用。所有调用在对应服务器主线程执行，状态都是封闭枚举，`detail` 文本供人阅读，调用方按状态码分支。其他加载器与 WAILA 接入见[产品路线](../roadmap/product-roadmap.md)。

## 只读查询

`FluidQueryRequest` 包含稳定 dimension ID（≤128 字符）、可选 region UUID 和世界坐标（m，一方块 = 1 m）。查询只读取已登记、已加载且可用的区域，不加载区块、不恢复存档、不唤醒或推进模拟。

| 状态 | 含义 |
|---|---|
| `OK` | 返回完整 observation |
| `EMPTY` | 位置有效，该格无液体 |
| `INVALID_REQUEST` | ID、坐标或请求形状无效 |
| `WRONG_THREAD` | 不在服务器主线程 |
| `NO_REGION` | 无登记区域，或指定 UUID 不属于该位置 |
| `REGION_NOT_LOADED` | 区域元数据存在，solver 未驻留 |
| `REGION_UNAVAILABLE` | 区域锁定或不可用，附带封闭的 `RegionAvailabilityReason` |
| `OUTSIDE_INTERIOR` | 位于 controller footprint 内但在可求解内部之外 |
| `STATE_NOT_READY` | 库存存在，observation 暂不能完整构造 |

只有 `OK` 携带数据。`RegionAvailabilityReason` 区分 profile、registry/HEAD、外壳/区块、重叠、预算、WAL、挂起工作、权限、数值暂停、性能暂停与政策停用；其中 `PERFORMANCE_PAUSE` 是 wall-time 调度保护，可重试，`NUMERICAL_PAUSE` 表示候选物理或账本无法闭合。

`FluidCellObservation` 返回 dimension、region UUID、`regionNetworkGeneration`、格坐标与边长、总液体体积（m³）、质量（kg）、密度（kg/m³）、可选温度（K），以及按 stable ID 排序的材料体积/质量与物种整数量子（1 quantum = 1 μmol）。数据直接取自该格的权威库存，温度由该格总内能反演。`regionNetworkGeneration` 标识区域实例，独立于 checkpoint generation；获取最新状态需重新查询。

## 测点摘要

`ProcessSensorBlockEntity.snapshot()` 返回不可变的 `ProcessSensorSnapshot`，服务端显示适配与客户端护目镜读取同一摘要。读取摘要不触发额外采样或模拟推进。

`measure` 与 `unit()` 标明物理量：液位为 0–1 比例，温度为 K，压力为绝对 Pa，液体流量为 L/s。`RUNNING` 与 `PAUSED` 携带数值、区域 UUID、实例代次、求解代次与仿真秒数；`WAITING`、`UNAVAILABLE` 与 `NO_SAMPLE` 不携带数值。摘要与曲线历史属于会话状态，方块存档保存测点配置与红石输出，加载后从 `WAITING` 开始。

## 外部规定运动结构

`MovingStructureApi` 让外部 Mod（首个目标是 Create）把自身的结构运动交给 MMM 推进流体。外部 Mod 保有原世界结构、实体与动力网络，MMM 按实际位置、形状与时长处理流体与碰撞。

**注册与采样**：适配器用稳定 provider ID 注册 `Provider`，`sample(dimensionId)` 返回当前结构列表。每个 `Structure` 包含结构 UUID、可选实体 UUID、`revision`、世界坐标平移 `source`/`target`、正的 `durationSeconds`、固定形状 `pieces`（稳定键 + 局部轴对齐 `Box`，单位 m）和 `retired` 标记。同一运动段保持 revision、起终点、时长与分块；下一段使用更大的 revision，以已接受终点为源点。

**进度**：`status(providerId, regionId)` 返回逐区域 `Progress`（revision、已接受位姿、完成比例）与状态：`WAITING`（尚无区域观测）、`LAGGING`（数据待处理）、`BUSY`（前一段未完成）、`ACCEPTED`、`REJECTED`（输入或可用条件需修正）。结构与其他运动体共同受区域运动体预算约束，拒绝时保留已接受状态。

**装配与拆解**：普通运动结构须从区域外进入、退役时整体离开。区域内装配和拆解经 `Provider.handoffs(dimensionId)` 提供最新 `Handoff`：`ASSEMBLE` 或 `DISASSEMBLE`、静止结构帧与 `FixedBlock` 列表（稳定键、世界坐标、`blockId`）。适配器在修改世界方块前发布意图，每次生命周期变化使用递增 revision。交接要求结构位于同一区域、形状与固定碰撞微格对齐、所需区块可用；MMM 核对真实世界微格后，在同一候选中转换固定碰撞与运动体归属，普通墙的内能和表面材料随方块在固定墙与结构之间交接（环境存档中记为 `CARRIED` 节点）。条件暂不满足时保持待处理并在后续刷新重试。

重启后适配器在区域首次刷新前重新注册 provider，并重放最近的生命周期意图与未完成运动段。当前支持轴对齐平移和同一区域内的装配/拆解；旋转、跨区域热库存转移与真实 Create 26.2 类型绑定见 [Create 适配计划](../roadmap/create-compatibility-plan.md)。

## 内置探针与控制界面

物质场探针是服务端权威的内部入口，读取的载体摘要属于内部诊断结构。

- **壳面查询**：普通使用经 `ShellFluidQuery` 对命中面两侧调用公开查询；潜行使用向内寻找第一格凝聚相，再读取同一区域、同一实例代次的固体与气体载体摘要。两份数据身份不一致时整次拒绝。
- **控制台**：对区域内位置使用时打开服务端生成的有界摘要（身份、footprint、预算、运行意图、可用性、库存、温度范围、数值诊断）。界面提供 select、pause、resume 与刷新可用性，每个动作携带一次性令牌，服务端重新核对距离、位置与区域归属。UNLOADED 区域只展示 manifest 汇总。破坏性生命周期操作见[区域生命周期](world-ownership-and-containers.md#区域生命周期)。
- **恒温源**：打开 setpoint 界面，要求创造权限；setpoint 是热注册表公共温区闭区间内的有限 K 值。提交后持久化方块实体、刷新相邻区域的外侧热流并唤醒热调度。

## Jade 适配

Jade 是可选的 compile-only 依赖，未安装时不加载任何 Jade 类。服务端 provider 通过 Query API 观察命中面近侧或跨过一格的反侧位置，非 `OK` 结果不显示 tooltip。客户端收到体积、质量、密度、可选温度与至多四项材料和物种，超出项显示隐藏数量。

## 提交完成事件

`MmmEventApi` 可在 world service 初始化前注册监听器，回调在服务器主线程执行，单个监听器异常相互隔离，服务器关闭时清空订阅。

`ContainerTransferCommittedEvent` 在区域 HEAD 已指向 target、actor 后态物品已持久保存且 WAL 删除后发布，携带事务 UUID、维度、region UUID、checkpoint generation、相对区域的 insert/extract 方向与 `TransferQuantity`（液体纳体积与可选 raw `U`，颗粒 SVQ）。

事件是即时通知：服务器可能在删除 WAL 后、发布事件前退出，监听器也可能晚注册。接入方通过查询或回执补读当前状态。checkpoint、世界归属、相变与安全暂停事件尚未提供。

## Fabric 物料箱自动化

物料箱通过 `ItemStorage.SIDED` 提供九槽 `SlottedStorage<ItemVariant>`，六个面与无方向查询共用同一存储。每槽保存一只实际容器，外部可模拟、插入和提取整只容器，物料、组分、内能与其他组件随容器保留。

箱内的转移身份组件不进入外部 `ItemVariant`，重新入箱即建立新身份。挂起 WAL 的槽位拒绝搬运；开放中的 Fabric 事务占用箱体，期间暂停玩家换料、端口、公共转移和拆除。该接口只移动物品，区域库存交接走下文的受控转移。Create 漏斗等标准 Fabric 调用方可直接使用此入口。

## 受控转移

**actor 登记**：接入方每次服务器启动注册稳定 provider ID，其解析器把 `ActorRef(providerId, objectId)` 解析为 `BlockInventoryAddress`（维度、方块坐标、槽位），解析过程不加载区块、不修改库存。内置 `mmm:block_inventory` provider 可直接用 `new BlockInventoryAddress(...).actorRef(registrationId)`。`registerActor` 把外部身份绑定到真实容器实例并返回 `actorGeneration`；每次成功转移推进代次，更换容器实例需使用新的 object ID。

**请求**：`TransferRequest` 携带调用方生成的操作 UUID、actor 与预期代次、区域与维度身份、世界坐标、方向（`INSERT` 注入区域、`EXTRACT` 提取到容器）与数量上限：

| 资源 | 数量类型 | 单位 |
|---|---|---|
| 液体 | `TransferQuantity.Liquid` | nanos（10⁻⁹ m³） |
| 颗粒 | `TransferQuantity.Granular` | SVQ（2⁻²⁷ m³） |
| 气体 | `TransferQuantity.Gas` | 标准体积 nanos |

实际组成、质量与携带内能由来源库存计算。

**路径**：容器须位于显式 `mmm:fluid_port` 正面相邻格，请求坐标是端口反方向紧邻的内部方块，且属于指定的唯一已加载区域。路由规则与玩家相同，见[玩家与发射器路由](world-ownership-and-containers.md#玩家与发射器路由)。

**计划与提交**：`plan` 返回有效期内的临时 token 与实际可转移的 `TransferManifest`：按材料列出数量、整数质量与余量（单位 10⁻¹⁰ kg）、物种 μmol、raw `U` 与注册表指纹，可能少于请求上限。`commit(planId)` 重新核对容器、端口与区域后沿共同容器事务提交。区域或容器变化、计划过期、服务器重启都要求重新计划。

**回执**：每个 actor 保存最近一次终态回执与当前未完成操作。重试同一请求返回原结果，内容冲突返回 `CONFLICT`。`receipt(operationId)` 跨重启可读，恢复中返回 `PENDING`，超出保留范围返回 `RECEIPT_UNAVAILABLE`。回执在区域 HEAD、后态物品与 actor 新代次持久保存后发布。

| 状态 | 含义 |
|---|---|
| `UNAVAILABLE` | provider 未注册或方块库存未加载 |
| `STALE_PLAN` | 计划过期或路径改变 |
| `STALE_ACTOR` | 容器实例或代次变化 |
| `INVALID_REQUEST` | 路径或请求无效 |
| `PENDING` | 事务已发布，恢复中 |

内置端口通过 `IndustrialOperations.transferBatch` 直接执行同一容器事务，不登记公共 actor。
