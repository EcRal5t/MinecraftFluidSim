# S19 收尾实施路线图：network / command / item / snapshot adapter 收窄

## 文档定位

本文是 [`code-architecture-restructuring-plan.md`](code-architecture-restructuring-plan.md) S19 工作包的**收尾执行规格**。S00–S18 与 S19a/S19b 已完成；调研基线为提交 `e043ead`（`FluidSimulationService` 1509 行、`MmmCommands` 614 行、`ClientFluidVolumeRenderer` 833 行）。

面向接手的执行者：假定你能完成局部 Java 重构，但不能依赖你自行推断跨域不变量或提交顺序。本文给出消费面清单、文件级工作包、边界、禁止事项、停止条件与验收门。原文中"必须/不得"是架构约束，"建议"允许在给出等价证据后调整。**本文不能迷信：若实现时发现事实与本文不符，以代码与 architecture/routing 为准，并把偏差记录进进度文档。**

执行结果继续记录在 [`../../progress/code-architecture-restructuring-implement.md`](../../progress/code-architecture-restructuring-implement.md)。

## 剩余差距（对照原完成条件）

原 S19 完成条件逐条对照当前状态：

| 条件 | 现状 |
|---|---|
| network/command/item 无 `FluidSimulationService` import | 未达成：`FluidProtocolServer`(6 处)、`FieldProbeItem`、`ChemicalIndicatorItem`、`FluidControllerBlock`、`MmmCommands`(33 处) 仍直接 import |
| `ClientFluidVolumeRenderer` 只编排，<400 行，ledger/GPU 无双重 owner | 未达成：833 行单类同时拥有 5 个 payload ledger、几何生成与 `StagedVertexBuffer` |
| `FluidSimulationService` 无消费者后删除（否则 allowlist 绑定 S21） | 未达成：15 个文件仍引用；SIZE_ALLOWLIST 已绑定 S21 |
| `protocol,audit,lifecycle,containers` 及领域门通过 | 基线全绿，作为每切片回归底线 |

另有一项目前由架构门登记、绑定 S19 的方向性违规：`FluidProtocolServer` 持有 `Map<MinecraftServer, Map<UUID, …>>` 静态会话表（`LOCATOR_ALLOWLIST`）。

## 消费面清单（先读这个再动手）

每个消费者实际用到的 service 成员，及其应迁往的 owner。`simulation` 包内的调用者（同包，无 import）也一并列出，因为 S19e 需要它们改走新 owner。

| 消费者 | 使用的成员 | 目标 owner |
|---|---|---|
| `network/FluidProtocolServer` | `retryUnavailableContainerTransfers`、`sendSnapshots`、`forgetInteractiveViewer`、`applyControllerConsoleAction`+`controllerSummary`、`preview/confirmControllerLifecycleOperation`、`applyThermalReservoirSetpoint`+`thermalReservoirSummary`、`worldCapabilityProfile`（HandshakeTask） | S19c `MinecraftControllerConsole` + S19d `MmmRuntime` 访问面 |
| `item/FieldProbeItem` | `thermalReservoirSummary`、`controllerSummary`、`firstCondensedProbePoint`、`observeCellCarriers` | 前两个→S19c；后两个→S19c 的探针观察面 |
| `item/ChemicalIndicatorItem` | `editIndicatorAtAim`（+ `IndicatorUseResult` 类型） | S19e 指示器编辑适配器 |
| `block/FluidControllerBlock` | `activateController`（+ `ControllerActivation` 类型） | S19e `MinecraftRegionActivation` |
| `MmmCommands` | 全部 `/mmm` 子命令后端（见 S19f 分组） | S19f 逐组改指 owner |
| `simulation/CreativeSourceCatalog` | `resolveMaterial`、`bindActualContainerProfile`、`attachLiquidThermalContents`、`chemicalRegistry`、`granularRegistry`、`worldCapabilityProfile` | S19e world-truth owner |
| `simulation/MinecraftContainerTransferPlan` | `acceptsActualContainerProfile`、`worldCapabilityProfile`、`chemicalRegistry`、`granularRegistry`、`containerTransactions()`、`CELL_VOLUMES_PER_BLOCK` | S19e world-truth owner |
| `simulation/MinecraftContainerTransferOperations` | `thermalRegistryFingerprint`、`CELL_VOLUMES_PER_BLOCK` | S19e world-truth owner |
| `simulation/Player-/DispenserContainerTransferAdapter` | `authoritativeWritesReady`、`acceptsActualContainerProfile`、`transferTargetAtBlock`、`aimedRegion`、`ensureRegionAvailableFor`/`ensureRegionAvailable`、`cellAtBlock`、`aimedCellNear`、`aimedGranularCellNear`、`bindActualContainerProfile`、`sendSnapshot(For)`、`containerTransactions()` | S19e world-truth owner + 现有 `FluidWorldTargetResolver`/`MinecraftContainerTransactionAdapter` 直连 |
| `gpu-probe/GpuProbeService` | `exists()`、`sendSnapshot(level)` | S19d `MmmRuntime` 访问面 |
| `api/fabric/MmmFabricApi` | `queryApi(server)` | S19d `MmmRuntime` 访问面 |
| `MmmRuntime` / `MmmRuntimeFactory` | 构造 + `recoverTakeoverBackup`/`recoverWorldOwnership`/`recoverContainerTransfers`/`checkpointForShutdown`/`restoreTakeover` | 保留（runtime 组合根） |

注意反向依赖：`FluidSnapshotPublisher`（simulation 包）调用 `FluidProtocolServer.send(...)` 发送 payload。S19d 收口会话表时**不得**破坏这条发送路径；`send` 的静态形态可以保留。

### S19c：控制台与温度源共享适配器 `MinecraftControllerConsole`

**动机**：`controllerSummary`/`applyControllerConsoleAction` 的"server-thread + 距离 + 方块 + owner 重解析"guard 同时被网络端点与 field probe 使用；温度源交互（summary + setpoint + 相邻区域唤醒）同理。这是网络端点解耦的前置。

**新建** `fabric/.../simulation/MinecraftControllerConsole.java`，迁入并独占：

- `controllerSummary(ServerPlayer, BlockPos)`
- `applyControllerConsoleAction(...)` + 私有 `changeControllerRunIntent`
- `previewControllerLifecycleOperation` / `confirmControllerLifecycleOperation`（目前是 `MinecraftRegionLifecycle` 的透传，保留透传但由本类持有）
- `forgetInteractiveViewer(UUID)`
- `thermalReservoirSummary` / `applyThermalReservoirSetpoint` + 私有 `thermalTarget` / `refreshThermalReservoir`
- `firstCondensedProbePoint` / `observeCellCarriers`（探针的服务端观察面，与 console 同属"玩家交互入口 guard"）

**操作**：

1. 构造参数显式注入：`FluidWorldTargetResolver`、`ControllerQueryUseCase`、`MinecraftRegionLifecycle`、`ThermalReservoirUseCase`、`MinecraftRegionTick`（thermal boundary 刷新）、`ThermalRegistry`、`WorldStartupCoordinator.Result`、`FluidRegionManager`，以及两个窄函数：`BooleanSupplier authoritativeWritesReady` 与 `BiFunction<ServerLevel, FluidRegion, Boolean> availability`（服务端 `ensureRegionAvailable` 的方法引用——`applyControllerConsoleAction` 的 REFRESH 分支需要它）。全部 guard 顺序、token 消费顺序、失败文案逐字保留。
2. `authoritativeWriteFailure` 的文案分支随迁（它只被温度源授权用到）。
3. `CarrierCellObservation` record 随迁为该类嵌套类型；`FieldProbeItem` 与 `FluidProtocolServer` 改用它。
4. service 保留同名 public 委托（一行）供过渡，S19f 再删。

**边界**：不得把 `Component` 文案下沉进 core use case（它们已返回封闭 decision，本地化映射留在 Fabric，这是既有约定）。不得在本切片顺带修改 `ControllerQueryUseCase`/`ThermalReservoirUseCase` 的语义。

**停止条件**：若发现 `applyControllerConsoleAction` 的 REFRESH 分支依赖了未列出的 service 状态，停止并先记录，不要用回调把整个 service 倒灌进来。

**验收门**：`protocol`、`lifecycle`、`verifyArchitecture`。

### S19d：runtime 语义访问面、网络端点解耦与会话表收口

**动机**：这是 S19 的主目标——`network`/`gpu-probe`/`api` 不再 import `FluidSimulationService`，并删除 `LOCATOR_ALLOWLIST` 中绑定 S19 的 `FluidProtocolServer` 条目。

**目标文件**：`simulation/MmmRuntime.java`、`network/FluidProtocolServer.java`、`validation/ArchitectureDependencyValidation.java`、`gpu-probe/.../GpuProbeService.java`、`api/fabric/MmmFabricApi.java`、`MmmMod.java`。

**操作**：

1. `MmmRuntime` 增加语义访问器（而非把 service 整个暴露出去）：`controllerConsole()`、`snapshots()`（`FluidSnapshotPublisher`）、`containerRecovery()`、`worldProfile()`、`queryApi(server)`、`regionInputObservation()`（`exists()`/`sendSnapshot(level)` 这类 gpu-probe 需要的最小面）。实现上可以先是对 service 包私有 getter 的委托——**门只检查 import 方向，不检查组合层级**；把组合真正上移到 `MmmRuntimeFactory` 属于 S21 的 service 删除切片，不要在这里一次做完。
2. `FluidProtocolServer`：
   - 三个 payload receiver 改从 `runtime.controllerConsole()` 取交互面；
   - JOIN/DISCONNECT 改用 `runtime.containerRecovery().retryPlayer(...)`、`runtime.snapshots().sendTo(player, ...)` 等；
   - `HandshakeTask` 的 offer 改用 `runtime.worldProfile()`；
   - 删除对 `FluidSimulationService` 的 import。
3. **会话表收口**：把 `SESSIONS`（`Map<MinecraftServer, Map<UUID, Negotiation>>`）从静态字段迁为 `MmmRuntime` 持有的实例状态（或 runtime 拥有的 `ProtocolSessions` 小对象），`FluidProtocolServer` 的静态 `send(...)` 改为先经 `MmmRuntimeRegistry.find(server)` 取 runtime 再查会话。`MmmMod` 停服时的 `clearServer(server)` 相应改为 runtime 生命周期内清理（`MmmRuntime.close` 已有钩子）。`PENDING` 表以 `ServerConfigurationPacketListenerImpl` 为 key，不属于 locator 违规，可留静态。
   - **必须**保持：configuration 阶段 runtime 可能尚未建立（`SERVER_STARTING` 之后才有），`HandshakeTask` 构造时 `MmmRuntimeRegistry.require` 的失败语义要与现状一致——先核对 `MmmRuntimeFactory` 的注册时机再动。
4. 架构门新增规则并加自检 fixture（沿用 S05 的 `ArchitectureGateSelfCheck` 做法）：`cc.ecisr.mmm.network`、`cc.ecisr.mmm.item`、`cc.ecisr.mmm.block`、根包 `MmmCommands`、`fabric/src/client`、gpu-probe 的 adapter 类，import `cc.ecisr.mmm.simulation.FluidSimulationService` 即违规。删除 `LOCATOR_ALLOWLIST` 的 `FluidProtocolServer` 条目。
5. `GpuProbeService`、`MmmFabricApi` 同步改指 runtime 访问器。

**边界**：不得让 `MmmRuntime` 变成第二个 god facade——每个访问器必须对应一个已被既有切片建立的 owner（console/publisher/recovery/profile/query），不为"层次完整"新增无调用者的访问器。不得改动 payload codec 或协商协议本身。

**停止条件**：若 `FluidProtocolServer.send` 改走 runtime 后出现"runtime 未建立但仍需发送"的路径（例如停服广播），停止并重新设计会话归属，不要用静态兜底绕过门。

**验收门**：`protocol`、`lifecycle`、`containers`（JOIN/DISCONNECT 的 actor retry）、`verifyArchitecture`（新规则生效且 allowlist 条目删除）。

### S19e：item / creative-source / container-plan 的 world truth 收口

**动机**：`item`、`block` 包与容器转移 plan 对 service 的依赖全部是"读 committed world registry/profile + 绑定 actual 容器 profile"，这是一块独立真值，不该借道 god service。

**新建** 一个 owner（建议名 `CommittedWorldRegistries` 或 `WorldContainerTruth`，放 `simulation` 包），暴露：

- `profile()`（fail-closed 空值语义同现状）、`hasCapability(...)`
- `chemicalRegistry()` / `granularRegistry()` / `thermalRegistry()`（保留现状的"不可用即抛 IllegalStateException"语义）
- `thermalRegistryFingerprint()`
- `resolveMaterial(String stableId)`
- `acceptsActualContainerProfile(stack)` / `bindActualContainerProfile(stack)`
- `attachLiquidThermalContents(stack, temperatureK)` / `attachDebugLiquidThermalContents(stack)`（连同私有 `validateLiquidTemperature`）

**操作**：

1. 构造输入直接用 `WorldStartupCoordinator.Result`（`profile()` + `registries()`），与 `MinecraftRegionLifecycle` 的既有做法一致；`startup.profileProven()` 的判定随迁。
2. 迁移调用方：`CreativeSourceCatalog`、`MinecraftContainerTransferPlan`、`MinecraftContainerTransferOperations`、`Player-/DispenserContainerTransferAdapter`（profile/registry/ItemStack 绑定部分）、`MmmCommands` 的 `materialbucket`/`granularcontainer`/`reagent` 三个 giver。
3. `CELLS_PER_BLOCK` / `CELL_VOLUMES_PER_BLOCK` 在本切片给出最终归属：建议移入 `FluidRegionFactory` 并**同时删除** `createCommandRegion`/`createControllerRegion` 恒以该常量为实参的 `cellsPerBlock` 形参（否则常量与形参自相矛盾）；随后 `MinecraftContainerTransferOperations`、`MinecraftDebugFixtures`、`MinecraftRegionInputs` 改引新家。若执行者发现还有非恒定调用点，保留形参并把常量留在原地，同时在进度文档记录理由。
4. `ChemicalIndicatorItem.editIndicatorAtAim` 与 `FluidControllerBlock.activateController`：
   - `activateController`（约 130 行创建工作流）抽为 `simulation/MinecraftRegionActivation.java`，构造注入 `FluidRegionManager`、`FluidRegionOperations`、`MinecraftRegionTick`、`FluidSnapshotPublisher`、`MinecraftTakeoverAdapter`（restore 前置）、`CommittedWorldRegistries`、`ControllerCreationPlan` 随迁；`MinecraftDebugFixtures.Runtime.activateController` 改指它。
   - `editIndicatorAtAim` 是"准星→cell→域编辑→checkpoint"的物品交互，抽为 item 侧可调用的 `simulation/MinecraftIndicatorEditing.java`（或并入 S19c 的交互 guard 适配器，二选一，以构造参数更少者为准）；`IndicatorUseResult` 随迁。

**边界**：`ItemStack` component 编解码只留在 `ContainerItemStacks`（routing 既有约定），新 owner 只做"committed world truth → ItemStack 绑定"，不得复制 codec。不得改变"actual 非空容器必须绑定 committed profile，未证明的 profile 直接拒绝"的语义。

**验收门**：`containers`、`materials`、`chemistry`（indicator 路径）、`verifyArchitecture`。

### S19f：`MmmCommands` 命令树收口

**操作**：

1. 按 S19a/S19c/S19e 建立的 owner 逐组重指：
   - `demo`/`materialdemo`/`chemistrydemo`/`controllerdemo`/`tensiondemo`/`airdemo`/`capture`/`takeover` → runtime 暴露的 fixtures/takeover（S19a 已建好对象，只差 `MmmRuntime` 访问器）；
   - `materialbucket`/`granularcontainer`/`reagent`/`chemstatus`/`granstatus` → world-truth owner + 既有 status 方法；
   - `ownership`/`regions list|select|checkpoint|recoverprevious` → `FluidRegionOperations`（`select` 返回 `FluidSimulationService.SelectionResult` 的，把该 record 一并升为顶层，模式同 S19a 的 `RestorationStats`）。
2. 剩余 operator 动作（`run`/`pause`/`step`/`status`/`remove`/`empty`/`pour`/`drain`/`refresh`/`entities`/`debug`/`controllers`/`worldbridge`/`transferwal`）若在一个切片内全部抽 owner 风险过高，允许走**明示过渡**：在架构门为"根包 `MmmCommands` import `FluidSimulationService`"加一条绑定 S21 的 allowlist 条目，并在 `architecture-baseline.md` 的方向性违规表登记。**不允许**不加 allowlist 地留待"以后再说"。
3. 命令树本身只保留参数解析、权限与 `audit(...)` 包装；`MmmCommandLog` 包住最终 result 的现状保持不变。
4. 全部用户文案逐字保留；本轮不做集中本地化重构（原文 op 5 的"typed reason 与本地化映射集中"若要做，另立切片并先给出文案不变证明）。

**验收门**：`audit`、`lifecycle`、`containers`、`verifyArchitecture`。

### S19g：客户端 `ClientFluidVolumeRenderer` 拆分（三步，各自提交）

按原文 op 6 的顺序，每步只做搬移，**不得**改变视觉阈值、颜色、iso level、`SNAPSHOT_BLEND_NANOS` 或 render pipeline：

1. **`ClientRegionSnapshotStore`**：迁入 5 个 ledger（`FluidSnapshotLedger` 及 material/chemical/granular/thermal 四个）、`latest`/`latestMaterials`/`latestGranular`/`latestThermal` 四个 map、三个 `extracted*` volatile 列表、5 个 `accept(...)`、`extract(context)`、`clear()`、`interpolate`、`compatible` 与 `SnapshotTransition`/`MaterialFrame`/`ChemicalFrame`/`GranularFrame` record。产出：不可变 frame 列表的快照视图。注意核对 `ChemicalFrame` 是否真的有消费者（chemical payload 是并入 material 上色的，`latest` 里没有 chemical map）——若确无消费者，连同其 record 一起删除并在进度文档记录。
2. **纯 geometry builder**：`FluidSurfaceGeometry`（`buildGeometry`/`renderFluidSurface`/`setSurfaceNode`/`renderTetrahedron`/`isoPoint`/`emitSurfaceTriangle`/`scalar`/`kernelWeight`/`lerp`/`renderFluidCell`/`renderSolidCell`/`renderBounds`/`neighborFill`/`hasVisibleFluid`/`isSolid`/`inside`/`index`/`gasNodeMask`/`renderGasCell`/`isGas`）、`MaterialSurfaceGeometry`（`buildMaterialGeometry`/`chemicalArgb`/`phaseArgb`/`blendArgb`/`emitMaterialTriangle`/`emitMaterialVertex`）、`GranularSurfaceGeometry`（`buildGranularGeometry`/`renderDepositCell`/`granularFill`/`granularIndex`）、共享的 `QuadGeometry`（`box`/`quadX`/`quadY`/`quadZ`/`vertex`）。颜色与阈值常量随各自 builder 迁移。builder 接口收 `VertexConsumer`，不持有 renderer/store。
3. **`FluidRenderBackend`**：`StagedVertexBuffer`、`render(context)` 的 pipeline 选择、`execute`、`close()`。

完成后 `ClientFluidVolumeRenderer` 只做 store.extract → builder → backend 编排，目标 <400 行；ledger 与 GPU 资源不得有双重 owner。

**边界**：不新增通用 port/接口；三个类是 Fabric client 内部实现细节，routing 的"客户端快照"行同步更新。是否为 geometry builder 建 JVM 级 fixture 由执行者按"新失败模式无法归属现有 selector 才新增测试"的原则自行判断——`protocol` 门已覆盖 snapshot/layout/tombstone 语义，不要为覆盖率造测试。

**停止条件**：任何一步拆完出现视觉差异（颜色、面剔除、插值），立即回退该步并先解释来源，不得用调阈值的方式"修"。

**验收门**：`protocol`、`verifyArchitecture`；视觉本身不可自动观察，若需要进游戏，给单一场景（一个 controller 域注水后 20 tick 内水面/材质/颗粒三相渲染与当前版本逐帧一致）。

### S19h：诊断报告迁出与文档同步（收尾）

1. 新建 `simulation/RegionDiagnosticsReport`（只读），迁入 `status`、`thermalSummary`、`gasSummary`、`componentNanos`、`chemistryInventoryStatus`、`granularInventoryStatus`、`ownershipReport`、`regionSummaries`（约 290 行）。构造注入 `FluidRegionManager`、`FluidRegionOperations`、`takeover`（`pendingBlockCount`/`lockDescription`）、`debugFixtures.lastFailure()` supplier、`ownershipIssues` supplier、feature flag supplier、`serverConfig` 的 phase 预算 supplier。`status` 的输出格式逐字保留。
2. 文档同步：`routing.md` 的相关行（`/mmm` 命令、controller console、field probe、客户端快照、world truth）、`architecture-baseline.md`（超大类表、>600 行清单、方向性违规表、模块规模）、`README.md` 命令表。
3. 复核 S19 全部完成条件；把仍未满足的项（预计只剩"`FluidSimulationService` 删除"一项）在 `architecture-baseline.md` 明示绑定 S21。

**验收门**：`audit`、`protocol`、`lifecycle`、`containers`、`verifyArchitecture`。

## 已定边界决策（接手者不要重新裁决）

以下决策在 S19a/S19b 已定并写入进度文档，除非发现事实错误否则沿用：

- `MinecraftDebugFixtures.Runtime` 是由 fixtures 自己声明的窄接口（consumer-owned，同 `RegionTickUseCase.StepExecutor` 先例），service 以匿名实现提供碰撞刷新/单步 motion/控制器激活/能力绑定；`stepMotionOnce` 与 `activateController` 保持 service private。
- 接管失败文案归 `MinecraftTakeoverAdapter`，场景失败文案归 `MinecraftDebugFixtures`；service 的 `lastTakeoverFailure()` 委托 fixtures。
- `RestorationStats`/`CaptureResult` 是 `simulation` 顶层 record（S19a 升出），`SelectionResult` 在 S19f 按同一模式处理。
- `bindDefaultMaterialState` 是 `FluidRegionFactory` 的纯函数，controller 激活与 fixtures 共用；`bindConfiguredState` 留在 service。
- `/mmm thermalsource` 的删除是**有意的**（`playable-lifecycle-plan.md` 明确不走该命令，正式路径是 field probe 右击打开服务端签发界面），不要把它加回命令树；`/mmm chemstatus`/`granstatus` 的恢复同样是定论。
- `CELLS_PER_BLOCK`/`CELL_VOLUMES_PER_BLOCK` 的最终归属在 S19e 决定（见该工作包第 3 步），此前不要单独搬动。

## 执行协议

1. 每轮先审计上一轮改动是否足够、有无缺陷（自省逻辑/架构/实现，不是重跑测试），再选路由表最近 selector。`docs/maintenance/routing.md` 是验证路由唯一权威。
2. 实现小而直接，优先复用既有 owner；文件出现第二职责或难以维护时拆分并更新 routing。
3. 文档不写中间态；**不需要兼容旧实现/旧存档/旧代码**，不设计迁移路径。
4. 新增覆盖优先替换同一不变量的既有测试。
5. 只有视觉、真实 `VoxelShape` 或跨重启事务无法自动观察时才请求进游戏，并给单一场景、步骤、预期与日志证据。
6. 不用经验常数、速度钳制、渲染增厚、材料 ID 或特例判定掩盖仿真算法异常。
7. 适时提交；Gradle 尽少跑，一个切片只跑最近 selector。命令模板：

```bash
./gradlew.bat :fabric:runCpuValidation -PcpuVerification=lifecycle --offline --no-daemon
```

   （实际属性名是 `-PcpuValidation=...`；`verifyArchitecture` 单独跑。）
8. **用 Python 改 Java 文件必须 `read_bytes()`/`write_bytes()`**（LF 会被翻成 CRLF）；提交前对比 `git diff --stat` 与 `git diff --stat -w`。S19a 留下的 `tmp/s19a_cut.py`（锚点切行）与 `tmp/s19a_verify.py`（搬移后逐行验证等价）可复用；可复用脚本放 `pytool/` 并登记 `docs/maintenance/pytool.md`（`unused_imports.py` 已登记）。
9. **绝对不要 `git stash`**；任何 `.git` 写操作先取得用户许可；命令行不用 `..` 相对路径。
10. 警惕架构膨胀；新增类型必须持有不可替代的不变量，纯转发/镜像类型一律不建。
