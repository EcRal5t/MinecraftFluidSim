# 运行时与区域操作

本文用于修改服务器启动、区域调度、运行状态和控制器操作。计算公式见 [流体求解器](fluid-solver.md)，磁盘提交见 [区域存档](region-state.md)。

| 要修改的部分 | 代码入口 | 测试选择器 |
|---|---|---|
| 启动、组装服务与停服 | [MmmRuntimeFactory](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MmmRuntimeFactory.java)、[MinecraftSimulationLoop](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftSimulationLoop.java) | `region-state,lifecycle` |
| tick 调度与性能暂停 | [RegionTickUseCase](../../application/src/main/java/cc/ecisr/mmm/application/RegionTickUseCase.java)、[MinecraftRegionTick](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionTick.java) | `lifecycle` |
| 单步计算和结果提交 | [RegionApplicationService](../../application/src/main/java/cc/ecisr/mmm/application/RegionApplicationService.java)、[FluidRegionRepository](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidRegionRepository.java) | `lifecycle`，受影响的物理测试 |
| 区域预算和可用性 | [RegionCatalog](../../application/src/main/java/cc/ecisr/mmm/application/RegionCatalog.java)、[FluidRegionOperations](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidRegionOperations.java) | `region-manager` |
| 控制器摘要与操作授权 | [ControllerQueryUseCase](../../application/src/main/java/cc/ecisr/mmm/application/ControllerQueryUseCase.java)、[RegionLifecycleUseCase](../../application/src/main/java/cc/ecisr/mmm/application/RegionLifecycleUseCase.java)、[MinecraftRegionLifecycle](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionLifecycle.java) | `lifecycle,protocol` |
| 实体与碰撞输入 | [MinecraftRegionInputs](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionInputs.java) | `entity-fluid` 或 `shape,cut-cell` |

执行方式见 [验证指南](../maintenance/validation.md)。改类职责或依赖时，运行 `verifyArchitecture`。

<a id="startup"></a>
## 服务器启动与服务组装

[MmmMod](../../fabric/src/main/java/cc/ecisr/mmm/MmmMod.java) 在 `SERVER_STARTING` 通过 [MmmRuntimeRegistry](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MmmRuntimeRegistry.java) 建立当前服务器的运行时。Registry 按 `MinecraftServer` 保存实例，后续方块、网络和 API 回调从这里查找服务。

[MmmRuntimeFactory](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MmmRuntimeFactory.java) 负责按顺序构造服务和注入依赖：

1. [WorldStartupCoordinator](../../fabric/src/main/java/cc/ecisr/mmm/simulation/WorldStartupCoordinator.java) 读取世界配置，验证或恢复世界能力包。[RegistryBundleLoader](../../fabric/src/main/java/cc/ecisr/mmm/simulation/RegistryBundleLoader.java) 提供材料定义的加载和验证。
2. [CommittedWorldRegistries](../../fabric/src/main/java/cc/ecisr/mmm/simulation/CommittedWorldRegistries.java) 保存这次启动确认的材料定义与功能配置，供区域和物品使用。
3. 建立区域管理与存档服务，完成已删除区域的文件隔离，并扫描世界归属日志和容器事务日志。
4. 组装运行策略、单步执行器、区域输入、tick、可用性检查、控制器操作、容器及观察服务。

依赖已加载 Minecraft 世界的恢复放在 `SERVER_STARTED`。[MinecraftSimulationLoop](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftSimulationLoop.java) 负责此时的恢复、后续服务器 tick 和停服顺序。

[MmmRuntime](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MmmRuntime.java) 提供按具体业务命名的入口。世界内可变运行政策归 [MinecraftRuntimePolicy](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRuntimePolicy.java)，同步力学计算归 [MinecraftMotionExecutor](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftMotionExecutor.java)，管理员写操作归 [MinecraftRegionOperatorActions](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionOperatorActions.java)。

外部 Mod 可以在启动前订阅事件。Registry 暂存事件中心，运行时建立后接管同一实例。启动前的只读查询返回暂不可用；查找服务本身没有初始化世界的副作用。

玩家协议会话由 [ProtocolSessions](../../fabric/src/main/java/cc/ecisr/mmm/network/ProtocolSessions.java) 随当前运行时保存。[FluidProtocolServer](../../fabric/src/main/java/cc/ecisr/mmm/network/FluidProtocolServer.java) 保存配置协商期间的短期任务。可选 GPU probe 使用自己的生命周期实例，通过 [MmmGpuProbeHostIntegration](../../gpu-probe/src/main/java/cc/ecisr/mmm/gpuprobe/MmmGpuProbeHostIntegration.java) 接入主模组。

<a id="tick"></a>
## 一次区域计算

1. `MinecraftSimulationLoop` 检查世界、区域可用性和恢复工作，把可以处理的区域交给 `MinecraftRegionTick`。
2. `MinecraftRegionInputs` 刷新到期的碰撞与热交换输入，记录实体观测，生成本 tick 的 [RegionRuntime](../../application/src/main/java/cc/ecisr/mmm/application/RegionRuntime.java) 调度快照。
3. `RegionTickUseCase` 根据运行状态和执行间隔选择 `NONE`、`MOTION` 或 `THERMAL`。同一个 tick 选择一项，力学计算优先于单独热计算。
4. 到期的计算通过用例持有的 `StepExecutor` 调用 `RegionApplicationService`。请求包含区域 UUID、预期求解器代次、预算和本次输入。
5. 结果成功提交后，`MinecraftRegionTick` 更新统计、休眠判断和快照输出。`RegionTickUseCase` 使用可注入的单调时钟记录耗时，并调整执行间隔。

失败或跳过的计算保持原有调度进度。力学与热休眠分别记录，因此静水仍可导热、发生相变，温度稳定的液体也仍可运动。

### 运行状态与性能暂停

| 状态 | 行为 |
|---|---|
| `ACTIVE` | 按 20 Hz 目标推进 |
| `REDUCED` | 单步超过 40 ms 后，按 1–4 tick 间隔推进完整的一步 |
| `PAUSED` | 玩家暂停，或区域因外壳、身份、数值问题暂时停止计算 |
| `PERFORMANCE_PAUSE` | 连续三步超过 200 ms 后暂停，可通过 `run` 或手动 `step` 重试 |
| `UNLOADED` | 释放求解器数组，保留区域元数据和预算预留 |

存档记录玩家运行意图、恢复状态、执行间隔和 tick。连续过载、恢复计数属于本次进程的调度记录。性能重试清除这次耗时记录；数值失败仍按其具体原因处理。

新控制器首次成功提交后开始运行。缺少区块或外壳破损造成的暂停保留玩家意图，条件恢复后按该意图继续。

<a id="candidate"></a>
## 后端计算与提交

[FluidDomainState](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainState.java) 保存完整物理输入。力学计算使用 [FluidStepRequest](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidStepRequest.java) / [FluidStepCandidate](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidStepCandidate.java)，单独热计算使用 [FluidThermalStepRequest](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidThermalStepRequest.java) / [FluidThermalStepCandidate](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidThermalStepCandidate.java)。

后端在私有状态上完成计算。`RegionApplicationService` 检查后端标识、紧邻代次和完整 `Layout`，其中包括网格、求解配置、预算、外壳、载体种类、注册表指纹和功能配置。`FluidRegionRepository` 在替换当前状态前再次核对区域身份。

CPU 可以把已算好的私有 `FluidDomain` 交给区域，省去正常帧的序列化与重建。该对象只交接一次。提交前，如存档、重放或诊断需要不可变状态，调用 `candidate.state()` 生成快照；提交后该对象已成为活动状态，历史候选的 `state()` 会拒绝调用。

其他后端可以提供完整不可变状态，接受相同检查。成功结果向 Minecraft 层返回本步统计和实体作用冲量。布局、容量或收支校验失败时，返回对应失败类型，区域保留原状态并暂停计算。

本次输入还会携带世界热交换面和最近一次真实压力投影等计算数据。这些数据供后续计算使用，区域存档保存的物质库存另由 [存档专题](region-state.md)定义。

<a id="admission"></a>
## 区域登记与可用性

[RegionCatalog](../../application/src/main/java/cc/ecisr/mmm/application/RegionCatalog.java) 保存 UUID、维度、包围盒、预算预留和区域对象标识，负责数量限制、重复身份、重叠、选择与替换。[FluidRegionManager](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidRegionManager.java) 负责 Minecraft 数据与这些描述之间的转换。

新区域在首次 HEAD 提交成功后登记。恢复时，已经提交的元数据先登记，再检查当前预算和运行条件。暂时超出预算的区域仍保留身份与预留量，等待以后恢复求解器。

[FluidRegionOperations](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidRegionOperations.java) 依次检查区域自身的数值失败、世界方块归属与政策、目录预算、外壳和区块。数值或归属问题在原处阻止运行；政策、预算或外壳造成的卸载需要先成功保存完整 checkpoint。

[RegionAvailabilityReason](../../api/src/main/java/cc/ecisr/mmm/api/RegionAvailabilityReason.java) 给出具体不可用原因。控制器扫描在 [controller 包](../../fabric/src/main/java/cc/ecisr/mmm/simulation/controller/)：它检查已经加载的世界，要求唯一封闭内腔、完整所需区块、无重叠且预算足够。

<a id="console"></a>
## 控制器查询和确认操作

[MinecraftControllerConsole](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftControllerConsole.java) 先校验服务器线程、玩家距离、控制器方块和区域归属。[ControllerSummaryAdapter](../../fabric/src/main/java/cc/ecisr/mmm/simulation/ControllerSummaryAdapter.java) 提供摘要数据，`ControllerQueryUseCase` 验证身份和字段后，生成摘要并签发绑定玩家与区域的一次性令牌。

查询失败时返回失败状态。`UNLOADED` 区域显示已知元数据与未加载状态；实时库存在求解器加载后查询。

运行和暂停由 `RegionLifecycleUseCase` 按权限、可写状态与现有意图判定。清空、排气、释放归属和删除采用预览与确认两步：

1. `MinecraftRegionLifecycle` 使用与 Brigadier 命令相同的管理员权限，收集区域、物品事务、库存和设备状态。
2. 用例生成有效期为 200 tick 的预览，列出实际移除量和阻止执行的原因。
3. 确认时校验玩家、区域代次、动作与当前状态，使用并使该 nonce 失效。同一玩家的过期或过时代次尝试会使令牌失效，其他玩家的尝试保留原玩家令牌。
4. 授权通过后，Minecraft 层执行对应事务。清空和排气提交库存及新 HEAD，释放操作调用世界归属事务，删除操作发布 `REMOVED` 标记并完成文件隔离。

提交完成后的网络通知或文件整理失败，按已提交状态继续恢复。四种操作的完整顺序见 [区域生命周期](world-ownership-and-containers.md#区域生命周期)。

单纯性能暂停允许规定的库存操作。数值暂停区域在已加载且 HEAD、注册表和事务日志均可验证时，也可通过私有候选执行清理。具体操作仍会检查外壳、归属、未完成事务、收支和设备状态，并在预览中列出阻止原因。

排气保留气腔空间，把气体库存与内能结算为零，形成密闭真空。操作后区域保持暂停，玩家可显式恢复运行。

<a id="phase-work"></a>
## 转移前结算相变功

相变造成的体积变化会留下待结算的压力功 `-pΔV`，由后续真实压力投影处理。桶、发射器、管理员转移或生命周期操作若遇到这项记录，先调用 [MinecraftPendingPhaseWorkBarrier](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftPendingPhaseWorkBarrier.java)。

它通过当前后端执行一次仅含力学的计算，结清已有压力功。该次计算跳过导热、反应和相变，因此不会立即新增相变待结算记录。结算成功后，以新的已提交区域状态准备容器事务或操作预览；失败时保留相应的阻止原因。

<a id="world-inputs"></a>
## 实体与碰撞输入

`MinecraftRegionInputs` 按五 tick 节拍刷新到期的世界碰撞输入。[MinecraftCollisionWorldPort](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftCollisionWorldPort.java) 把 `VoxelShape` 采样为纯 Java [CollisionField](../../model/src/main/java/cc/ecisr/mmm/world/CollisionField.java)，变化的网格单元同时唤醒力学与热计算。

实体位置、包围盒和原版速度响应留在 Fabric。输入适配器比较相邻服务器 tick 的实际观测位置，得到本 tick 位移；`Entity#oldPosition` 会在 Minecraft tick 内被重写，无法提供这里需要的完整位移。首次观测或中断一 tick 的实体只接收实体侧响应。

求解器按实体包围盒、液层与局部碰撞几何计算浸湿体积。[MovingBodyFluidCoupling](../../model/src/main/java/cc/ecisr/mmm/world/MovingBodyFluidCoupling.java) 把位置、实际位移速度和湿体积交给力学请求。到期的力学计算使用一次该请求，热计算保留独立流程。当前模型采用运动学包围盒近似，双向动量与动态排水仍在产品路线中。
