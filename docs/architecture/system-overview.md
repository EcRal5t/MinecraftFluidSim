# 系统架构总览

本文说明 Matter, Motion & Mechanics 当前产品基线的组件关系、权威状态、单位、执行顺序和失败模型。具体算法与事务细节由同目录主题文档继续约束。

## 产品边界

当前系统只在显式、闭合、预算受限且 chunk 完整加载的 controller 区域内提供连续流体。边界和控制器可制作并从创造栏取得；普通空手右击控制器会创建或选择区域并立即运行，空手潜行右击切换持久的运行意图。`/mmm` 只承担 fixture、诊断、恢复和管理员操作。天然河流、海洋、无界洞穴空气和永久 chunk ticket 不属于当前运行模型。

CPU `FluidDomain` 是规范求解器聚合根。GPU probe、客户端 renderer、tooltip、命令 status 和外部 Mod Query API 都只能读取已提交状态。

## 模块边界

| 模块 | 职责 | 依赖边界 |
|---|---|---|
| `api` | stable ID、单位化 Query、committed event 与 typed quantity 契约 | 只依赖 JDK，不暴露 solver 数组或 Minecraft 类型 |
| `core` | PIC/FLIP、材料、气体、化学、颗粒、热学、typed container truth、候选状态与 loader-neutral application command/result/port | 依赖 `api` 和纯 Java 库，不引用 Fabric/Minecraft、NBT 或 network payload |
| `fabric` | Mod 生命周期、世界读取/写入、NBT、数据包、网络、物品适配与命令 | 依赖并内嵌 `api`、`core`；服务器主线程拥有提交权；`ContainerItemMapper` 独占事务 fingerprint 与 `ItemStack` 的双向映射，`ContainerItemStacks` 只构造并校验 components |
| `gpu-probe` | Vulkan 能力验证、只读对照和未来后端实验 | 数值部分依赖 `core`；Fabric 集成只以 compile-only 接入主 Mod |

`FluidDomainState` 是完整、不可变的数值后端交接状态。motion 使用 `FluidStepRequest` / `FluidStepCandidate`，静水 thermal-only 轴使用 `FluidThermalStepRequest` / `FluidThermalStepCandidate`；两者都绑定 base generation 且只能返回其紧邻后继。`CpuFluidSolverBackend` 对两类请求都先恢复私有 `FluidDomain`，完整成功后才返回 candidate。未来 GPU 后端必须消费同一完整输入并返回同一完整 candidate；世界事务、ownership、actor、调度和 HEAD 发布不进入数值后端。

生产 motion step、静水 thermal-only step 与测试侧 replay 都走完整候选边界。`MinecraftRegionInputs` 先刷新到期的碰撞/热边界，冻结实体观测、实际逐 tick 位移、moving-body request 和只含调度事实的 `RegionRuntime`；`MinecraftRegionTick` 把 region UUID、expected solver generation 与 phase budget 固定进本 tick command，并独占 accepted result 写回、thermal/hydrostatic observation、typed failure 映射与 snapshot 节拍。`RegionTickUseCase` 根据 tick/interval 与正交 motion/thermal readiness 生成唯一 `NONE` / `MOTION` / `THERMAL` plan，motion 到期时不会再执行同 tick 的 thermal-only 轴。到期轴才经该 use case 自有的 `StepExecutor` 进入 `RegionApplicationService`；该用例只在 accepted step 后按注入的单调时钟推进 pacing 与连续过载证据，rejected/typed exception 不改变调度状态。`RegionRepository` 只提供完整 immutable capture 和 atomic adopt，不暴露 live domain。application 同时验证 backend id、紧邻 generation 与 `FluidDomainState.Layout`（网格/config/预算、sealed boundary、carrier kind、registry fingerprint、capability 与 thermal budget），port 在发布前按当前 live identity 重验。accepted result 只返回本步统计与 moving-body impulse，不把完整 candidate arrays 再交给 adapter。后端抛出的 typed 拒答不会改动 live domain，因此失败步既不会留下预先写入的 marker 冲量，也不会留下只完成传导或部分反应/相变的热轴状态。

每个 `MinecraftServer` 的主运行时只由 `MmmRuntimeRegistry` 索引。`SERVER_STARTING` 显式调用 `MmmRuntimeFactory`：`WorldStartupCoordinator` 依次读取 world policy、在首次发布前验证当前 definitions、证明或恢复 world capability bundle、提取并复验 frozen registries，再由 factory 按固定顺序构造 region repository、收敛 removed quarantine、扫描 ownership 与 container WAL，最后才构造无 I/O 的 `FluidSimulationService`。startup state 和 issue 是 typed data；logger 只在 factory 边界投影它们，不参与状态机。需要已加载 level 的 takeover、ownership 与 container actor 收敛推迟到 `SERVER_STARTED`，停止时由 registry 注销并清理 event hub。

adapter 查找只能使用 `MmmRuntimeRegistry.require/find`；查找本身不再隐式读取或发布世界状态。`MmmFabricApi.events` 仍允许在 `SERVER_STARTING` 前登记监听器：registry slot 暂存同一个 thread-safe hub，runtime 建立后接管它；只读 Query 的 pre-start lookup 返回 unavailable/wrong-thread，不触发 runtime 初始化。

运行时只向外部 adapter 提供按行为命名的 owner：controller console、region activation、indicator editing、debug fixture、region administration/diagnostics/observation、player/dispenser container actor、container recovery、Query、committed world registries 与 protocol sessions。`FluidSimulationService` 仅作为尚未收口的 server tick、operator 写动作与 shutdown/recovery 编排 owner，不再是方块、物品、容器、协议、只读观察或已迁移命令的通用入口。协议协商状态由每个 `MmmRuntime` 的 `ProtocolSessions` 按玩家持有；`FluidProtocolServer` 只保留 configuration listener 对应的短期 pending task，不再维护 `MinecraftServer` 静态会话表。network、外部 API 与 GPU probe 不得依赖 `FluidSimulationService`。

## 组件与数据流

```text
数据包定义
  -> WorldStartupCoordinator               policy / capability / typed issues
  -> RegistryBundleLoader                  pre-publication + frozen-bundle validation
  -> world-local canonical registry snapshot + fingerprint
  -> MmmRuntimeFactory / MmmRuntimeRegistry 唯一 server runtime lifetime

Minecraft 命令、方块、物品、容器、协议与 chunk 事件
  -> MmmRuntime 语义 owner              console / activation / indicator / fixture
                                          administration / diagnostics / observation
                                          container actors / recovery / Query / protocol
  -> 各 owner 自有的 Minecraft 授权、世界事实与事务边界

server tick、实体观测与尚未迁出的 operator 写动作
  -> FluidSimulationService                 全局 maintenance、dimension/availability 与过渡编排
  -> MinecraftRegionInputs                  碰撞/热边界/实体事实与 RegionRuntime
  -> MinecraftRegionTick                    单区域 tick command/result、失败映射与输出
  -> RegionTickUseCase / RegionRuntime       唯一轴计划、step timing 与 pacing
  -> RegionApplicationService               loader-neutral command/result 编排
       -> RegionRepository                  step capture/adopt
       -> RegionCheckpointPort              expected-generation HEAD 发布
  -> ControllerQueryUseCase                 bounded summary + viewer token
       -> ControllerSummaryPort             bounded controller read model
  -> MinecraftRegionLifecycle               controller facts、durable HEAD/ownership/REMOVED 收敛
       -> RegionLifecycleUseCase             run intent 与 preview/confirm 授权
  -> RegionCatalog / FluidRegionManager      身份、预算、placement、选择
  -> FluidRegionOperations                   availability、restore、checkpointed unload
  -> FluidRegion                            durable metadata、runtime、生命周期
  -> CollisionWorldPort                     捕获不可变碰撞场
  -> FluidDomain                            CPU aggregate、mechanics 与载体库存
       -> AirNetwork                        密闭气体节点与孔流
       -> ChemicalParcelStore               marker 化学 parcel
       -> GranularInventory                 active/deposit 固体
       -> FlipThermalState                  各载体总内能与相变

MinecraftRegionCheckpointSource            live region → stable checkpoint source
RegionCheckpointAssembler / Restorer       完整逻辑 checkpoint 装配/immutable state 重建
RegionRepositoryAdapter                     checkpoint/restore、metadata 与 ownership read guard
RegionGenerationStore                       ACTIVE/REMOVED 双槽事务与唯一 HEAD 发布者
Head/Active/Removed/Quarantine stores       package-private 严格 codec、文件与恢复分类
OwnershipTransactionCoordinator             Minecraft 方块 preimage 与 ownership 事务入口
ContainerTransferCoordinator                region/item WAL→staged HEAD→HEAD
ContainerRecoveryCoordinator                boot-indexed actor 收敛与事件重试
ContainerJournalCodec / FileStore           严格 NBT / durable file 边界

已提交区域状态
  -> FluidSnapshotPublisher / protocol payload
  -> 客户端 surface、material、chemical、granular、thermal 重建
  -> MmmQueryApi / FabricFluidQueryApi
  -> 外部 Mod 的同步只读单格观察

durable container completion
  -> MmmEventApi / CommittedEventHub
  -> 外部 Mod 的易失提交通知（漏失后由 Query API 恢复）
```

Minecraft adapter 负责读取世界、选择区域和提交世界修改。`MinecraftCollisionWorldPort` 把 `VoxelShape` 保守采样成纯 Java `CollisionField` 后再交给世界交互服务；数值核心不持有 `ServerLevel`、ItemStack durable save 或磁盘所有权。持久层保存稳定 ID 和完整库存，不序列化 Java 对象图、GPU buffer 或客户端 mesh。

## 权威真值表

| 领域 | 权威真值 | 只读派生或数值状态 |
|---|---|---|
| 液体 | marker 位置、速度、体积、逐材料质量和相身份 | cell volume、density、pressure、PLIC、曲率 |
| 化学 | species quanta、组成、电荷、质量 residual、indicator provenance、source/sink | pH、Top-N species、颜色 |
| 颗粒 | active/deposit 的 SVQ、质量、材料、组成、孔隙率和审计 | bulk fill、显示高度、sample |
| 连续固相 | coherent group 的 SVQ、质量、组成和总内能 | 固相显示 fraction |
| 气体 | component standard-volume nanos、质量、species/residual、节点总内能和 source/sink | 温度、绝对压力、密度、孔流速度 |
| 热学 | 每个物质载体的总内能、external source/sink、mechanical work、finite-device exchange 和 roundoff | 温度、热容、LTE grid、浮力修正 |
| 有限热设备 | region HEAD 内按 provider/object 定位的 kg、总内能与 generation | 世界位置、接触端口、温度、换热 UI |
| 世界 | region HEAD、ownership committed/WAL、actor durable item state | controller UI、status、debug layer |

任何“派生”列都不能创建、删除、缩放或恢复“权威”列。状态跨层搬运时必须同时提交所有受影响真值，而不是先改机械库存再尝试补化学或能量。

## 单位与精确量

- `BV`：一个 Minecraft 方块体积；几何上按 `1 m³` 解释。
- 默认 solver cell edge 为 `0.5 m`，所以 1 BV 包含 `2×2×2` cells，每 cell 为 `0.125 m³`。
- 液体容器用 `long` nanovolume：`1 BV = 1,000,000,000 nanos`。
- 固体用整数 SVQ：`1 BV = 2^27 SVQ`，默认 cell 为 `2^24 SVQ`。
- 化学用整数 quanta：`1 AU = 1,000,000,000 quanta`；AU 是求解器量纲，不宣称等于 mol。
- marker `particleMass` 是与 solver-cell 体积配对的质量权重；所有 SI、化学与容器边界只通过 `SolverUnitScale` 乘 `cellSize³` 得到 kg。
- solid mass、chemical species/residual、gas component mass 和容器 mass 都直接保存 kg；只有 liquid marker mass 使用 solver weight。gas component 的 standard-volume nanos 与 kg 分开保存。
- 热学当前使用 SI-compatible K、Pa、J、W/mK 与 J/(kg K)。内部审计保留 raw double 或 `SignedDyadic`，不从显示小数恢复。

浮点 marker 尾数、整数 SVQ/nanos/quanta 和独立 residual 都是合法真值。换算必须指定向上、向下或 exact；不能先四舍五入后把差额静默归零。

## 一个 logical step

1. 捕获本步 inventory、source/sink/work 与 pending phase-volume 审计基线。
2. 根据 world/cut-cell、统一固体 occupancy 和当前 gas topology 建立机械输入。
3. P2G，施加重力、热浮力、表面张力、气压和颗粒/实体耦合。
4. 构造目标散度，以 PCG 求解变系数 pressure projection。
5. G2P、FLIP/PIC 混合、marker advection 与 CCD；颗粒完成运动/碰撞提交。
6. 从新载体位置重建 thermal grid，提交内部导热和固定温度边界通量。
7. 提交化学与固液反应 extent，再提交 reaction heat。
8. 依次计划并提交固液、气液相变；体积变化写入 pending pressure work。
9. 处理沉积/唤醒和 marker reseed，同时搬运材料、化学和内能侧列。
10. 重建所有账本；只有全部闭合才发布本步诊断并评估 motion/thermal activity。

相变产生的凝聚相体积/功由下一次真实 pressure projection exactly-once 消费。未消费记录存在时，不能用 `empty`、卸载或重排把它绕过。

## 生命周期和调度

`FluidRegion` 同时拥有身份、硬预算预留、controller footprint、checkpoint generation、motion lifecycle 和正交 thermal activity：

- `ACTIVE`：按 20 Hz 目标推进。
- `REDUCED`：wall-time 超过 40 ms 后按 1–4 tick pacing 推进同一个 logical step。
- `PAUSED`：operator 暂停、边界/身份失败或连续三步超过 200 ms；库存仍保留。
- `UNLOADED`：solver arrays 已释放，但 metadata 和 catalog reservation 仍保留。
- motion sleep 与 thermal sleep 独立；静水仍可能导热或相变，静温也可能继续运动。

`RegionRuntime` 是 Fabric 在一次 tick 内捕获的 loader-neutral 调度值，不是第二个区域聚合根，也不持有 solver、世界或持久化对象。checkpoint 继续只保存 operator intent、resume state、step interval 与 tick；hard-overrun/recovery streak 是进程内短期证据。`MinecraftRegionInputs` 独占到期的碰撞/热边界刷新、实体观测与逐 tick 响应，并在这些输入可能唤醒区域之后冻结 runtime；`MinecraftRegionTick` 应用 accepted pacing、thermal/hydrostatic post-observation 与 snapshot 输出。`FluidSimulationService` 的 server tick 只维持全局 maintenance、dimension/availability，并把已可用的单个 region 交给该 adapter。

新控制器在首次成功提交后直接进入运行态。玩家的暂停意图随 checkpoint 持久化；边界损坏、chunk 缺失或候选失败造成的 availability pause 不得覆盖该意图，条件恢复后按原意图继续。

controller console 的 Minecraft adapter 先校验 server thread、距离、方块与当前 controller owner，再把 viewer/region identity 交给 `ControllerQueryUseCase`。该用例只从 `ControllerSummaryPort` 的 bounded view 构造 summary，并在 view 存在、identity 匹配且全部字段可投影后才签发同 viewer/region 的一次性 generation；miss、非法 view 和投影失败都不能留下未披露但可消费的 token。动作与 lifecycle preview 都经同一用例消费该 token。

controller console 还提供四个管理员破坏性生命周期动作：清空凝聚相、排放密闭气体、释放 ownership、移除空区域。`MinecraftRegionLifecycle` 冻结 permission、WAL、availability、solver/device、ownership 与实际库存摘要后，`RegionLifecycleUseCase` 生成 200 tick、同玩家的一次性 preview，并在确认时消费 nonce、重验 player/region/generation/action/context 后返回当前 blockers；错 viewer 不消费 owner token，同 owner 的 stale/过期尝试会使 token 失效。只有 `AUTHORIZED` 才进入同一 adapter 内职责分明的 durable 路径。清空与排放先在私有完整 candidate 上联合审计并发布新 HEAD，再替换 live domain；ownership release 调现有 ownership transaction；区域移除按 REMOVED HEAD→network tombstone→runtime unregister→quarantine convergence 收口，commit point 后的输出/收敛失败不会被误报成未提交。

availability 采用 `RegionAvailabilityReason` 封闭分类；预算、重叠、chunk、边界、WAL、数值拒答和政策停用不再靠错误文本驱动状态。runtime admission 是 `FluidRegionOperations` 内一个按副作用顺序惰性取证的 server-thread 工作流：先检查内在数值失败与非托管 bypass，再读取 ownership/policy、catalog admission 和世界边界；数值失败与 ownership 就地阻断，policy/catalog/boundary 拒答则先发布合法完整 checkpoint 再卸载。已提交 metadata 即使当前 runtime budget 无法 admission 也继续登记并保留 reservation；只有 solver restore/step 被阻止。

调度和后端选择不得改变守恒结果。缺 chunk 或 controller 破损时不能把缺失区域当墙继续半步。

## 原子状态与失败模型

系统区分两类失败：

- **LOCKED**：磁盘格式、identity、registry fingerprint、generation、checksum 或 WAL 状态无法被唯一解释。原文件保留，拒绝任何会覆盖证据的操作。
- **PAUSED**：当前库存可解释，但候选数值步越过物性有效域、容量、预算、审计或 wall-time 安全门。保留内存/已提交状态，允许修正外部条件后重试。

region HEAD 是区域物理状态唯一提交点。正常库存由 `ACTIVE` HEAD 指向完整 generation；终止区域由严格 `REMOVED` HEAD 指向零库存 tombstone，UUID 永不复用。ownership 与 container WAL 分别把世界方块和 actor item 收敛到该 HEAD；它们不能成为第二个物理真值。`RegionGenerationStore` 是唯一能组合 slot 写入与 HEAD 发布顺序的 coordinator；package-private 子 store 不能被业务层当成独立 writer。任何 publish 都必须遵守“完整写入、回读验证、HEAD 最后替换”。`REMOVED` HEAD 发布后才发送更高 network generation 的移除 tombstone、注销 runtime owner，并把旧 ACTIVE payload 收敛到持久 quarantine；崩溃恢复不能从旧 payload 重建 solver。

需要把 region HEAD 与一个或多个外部持久成员收敛的 journal 统一委托 JDK-only `CrossDomainRecovery`：adapter 只能把 durable readback 分类为 exact `BEFORE`、`AFTER`、`THIRD` 或 `UNAVAILABLE`。HEAD 为 base 时只接受全 before；HEAD 为 checksum 匹配的 target 且 journal 已提交时才收敛 remaining before；third 锁定，unavailable 等待明确加载事件。normal tick 不轮询 unavailable actor。

world-local chemical、granular、gas 和 thermal registry snapshot 的首次发布同样必须回读验证、atomic rename 并同步目录 entry。reader 只接受当前 writer 的精确 compound 字段集合与 NBT 类型；额外字段也属于未知语义，必须保留 snapshot 并 LOCKED，不能忽略后再按当前定义重建。

## 线程与只读边界

- 世界读取、方块修改、玩家/发射器物品变更和 lifecycle 编排只在服务器主线程发生。
- 数值代码只消费已冻结的 region arrays、registry 和 boundary sample。
- checkpoint 捕获的是一个完整 logical state；不发布半步或半个 gas remap。
- 客户端只接受当前 region generation 的递增 snapshot sequence；迟到包、移除 tombstone 前的包和其他 dimension 的包不能复活状态。
- Query API 只允许服务器主线程同步调用，只扫描已加载 owner 的权威 marker/side columns；它不建网格、不加载 chunk、不分配 solver、不唤醒、不 checkpoint、不推进 logical step，也不返回 live domain 引用。
- Query 的 `regionNetworkGeneration` 标识网络中的区域 incarnation，不是 checkpoint generation 或逐 tick revision。观察者需要新鲜值时必须重新查询，不能把它当作缓存时钟。
- Minecraft 实体发现、世界坐标 AABB、原版响应速度和本 tick 实际位移留在 Fabric。实际位移由 Mod 跨相邻服务器观测自己冻结，不能用 `Entity#oldPosition` 代替：该字段会被 `snapTo`/`absSnapTo` 与 `ServerLevel#tickNonPassenger`（在 `Entity#tick` 之前调用）重写，而客户端移动包由 `MinecraftServer#processPacketsAndTick` 在 level tick 之前应用；在 server tick 末尾读它只会得到 tick 内的残余运动，会系统性丢失走动玩家的主要位移。首次观测或中断一 tick 的实体没有位移，只参与实体侧响应，不产生 moving-body request。core 只读地积分 AABB 与派生水平液层及已有 cut-micro geometry 的连续湿体积，再由 moving-body request 携带局部 SI 位置、实际当 tick 位移速度和湿体积并只提交 marker 动量。实际液体冲量可诊断，尚未实现的刚体质量/压力面积分/实体动态占位 cut-cell 不能由 Fabric 猜测补齐。
- renderer、Jade/HWYLA 适配、status 和 GPU probe 都不能唤醒、加载、checkpoint 或回写区域。

## 主题文档

- mechanics、自由面与碰撞：[`fluid-solver.md`](fluid-solver.md)
- 材料、质量与液液界面：[`material-state.md`](material-state.md)
- 密闭气体：[`gas-state.md`](gas-state.md)
- 化学：[`chemistry-state.md`](chemistry-state.md)
- 颗粒与沉积：[`granular-state.md`](granular-state.md)
- 热输运、相变和反应热：[`thermal-state.md`](thermal-state.md)
- checkpoint：[`region-state.md`](region-state.md)
- ownership、容器与世界事务：[`world-ownership-and-containers.md`](world-ownership-and-containers.md)
- 外部只读查询：[`external-api.md`](external-api.md)
