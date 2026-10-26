# 运行时与区域操作

本文用于修改服务器启动、区域调度、运行状态和控制器操作。计算公式见 [流体求解器](fluid-solver.md)，磁盘提交见 [区域存档](region-state.md)。

- **启动、组装服务与停服**
  - 代码入口：
    - [MmmRuntimeFactory](../../fabric/src/main/java/mmm/simulation/MmmRuntimeFactory.java)
    - [MinecraftSimulationLoop](../../fabric/src/main/java/mmm/simulation/MinecraftSimulationLoop.java)
  - 测试选择器：`region-state,lifecycle`

- **tick 调度与性能暂停**
  - 代码入口：
    - [RegionTickUseCase](../../app/src/main/java/mmm/application/RegionTickUseCase.java)
    - [MinecraftRegionTick](../../fabric/src/main/java/mmm/simulation/MinecraftRegionTick.java)
  - 测试选择器：`lifecycle`

- **单步计算和结果提交**
  - 代码入口：
    - [RegionApplicationService](../../app/src/main/java/mmm/application/RegionApplicationService.java)
    - [FluidRegionRepository](../../fabric/src/main/java/mmm/simulation/FluidRegionRepository.java)
  - 测试选择器：`lifecycle`，受影响的物理测试

- **区域预算和可用性**
  - 代码入口：
    - [RegionCatalog](../../app/src/main/java/mmm/application/RegionCatalog.java)
    - [FluidRegionOperations](../../fabric/src/main/java/mmm/simulation/FluidRegionOperations.java)
  - 测试选择器：`region-manager`

- **控制器摘要与操作授权**
  - 代码入口：
    - [ControllerQueryUseCase](../../app/src/main/java/mmm/application/ControllerQueryUseCase.java)
    - [RegionLifecycleUseCase](../../app/src/main/java/mmm/application/RegionLifecycleUseCase.java)
    - [MinecraftRegionLifecycle](../../fabric/src/main/java/mmm/simulation/MinecraftRegionLifecycle.java)
  - 测试选择器：`lifecycle,protocol`

- **实体与碰撞输入**
  - 代码入口：[MinecraftRegionInputs](../../fabric/src/main/java/mmm/simulation/MinecraftRegionInputs.java)
  - 测试选择器：`entity-fluid` 或 `shape,cut-cell`

执行方式见 [验证指南](../maintenance/validation.md)。改类职责或依赖时，运行 `verifyArchitecture`。

<a id="startup"></a>
## 服务器启动与服务组装

[MmmMod](../../fabric/src/main/java/mmm/MmmMod.java) 在 `SERVER_STARTING` 通过 [MmmRuntimeRegistry](../../fabric/src/main/java/mmm/simulation/MmmRuntimeRegistry.java)
建立当前服务器的运行时。Registry 按 `MinecraftServer` 保存实例，后续方块、网络和 API 回调从这里查找服务。

[MmmRuntimeFactory](../../fabric/src/main/java/mmm/simulation/MmmRuntimeFactory.java) 负责按顺序构造服务和注入依赖：

1. [WorldStartupCoordinator](../../fabric/src/main/java/mmm/simulation/WorldStartupCoordinator.java) 读取世界配置，验证或恢复世界能力包。
   [RegistryBundleLoader](../../fabric/src/main/java/mmm/simulation/RegistryBundleLoader.java) 提供材料定义的加载和验证。
2. [CommittedWorldRegistries](../../fabric/src/main/java/mmm/simulation/CommittedWorldRegistries.java) 保存这次启动确认的材料定义与功能配置，供区域和物品使用。
3. 建立区域管理与存档服务，完成已删除区域的文件隔离，并扫描世界归属日志和容器事务日志。
4. 组装运行策略、单步执行器、区域输入、tick、可用性检查、控制器操作、容器及观察服务。

依赖已加载 Minecraft 世界的恢复放在 `SERVER_STARTED`。[MinecraftSimulationLoop](../../fabric/src/main/java/mmm/simulation/MinecraftSimulationLoop.java) 负责此时的恢复、后续服务器 tick 和停服顺序。

[MmmRuntime](../../fabric/src/main/java/mmm/simulation/MmmRuntime.java) 提供按具体业务命名的入口。
世界内可变运行政策归 [MinecraftRuntimePolicy](../../fabric/src/main/java/mmm/simulation/MinecraftRuntimePolicy.java)，同步力学计算归 [MinecraftMotionExecutor](../../fabric/src/main/java/mmm/simulation/MinecraftMotionExecutor.java)，
管理员写操作归 [MinecraftRegionOperatorActions](../../fabric/src/main/java/mmm/simulation/MinecraftRegionOperatorActions.java)。

新增服务由 `MmmRuntimeFactory` 组装并注入对应业务模块，实例随所属服务器创建和释放。静态服务器实例查找统一通过 `MmmRuntimeRegistry`；类职责、依赖方向与注册点由架构检查约束。

外部 Mod 可以在启动前订阅事件。Registry 暂存事件中心，运行时建立后接管同一实例。启动前的只读查询返回暂不可用；查找服务本身没有初始化世界的副作用。

玩家协议会话由 [ProtocolSessions](../../fabric/src/main/java/mmm/network/ProtocolSessions.java) 随当前运行时保存。
[FluidProtocolServer](../../fabric/src/main/java/mmm/network/FluidProtocolServer.java) 保存配置协商期间的短期任务。
可选 GPU probe 使用自己的生命周期实例，通过 [MmmGpuProbeHostIntegration](../../gpu-probe/src/main/java/mmm/gpuprobe/MmmGpuProbeHostIntegration.java) 接入主模组。

区域求解默认使用 Vulkan。主模组已打包 GPU kernel、provider 服务声明以及 Windows x64 运行库；将主模组与 Fabric API 放入 Minecraft 26.2 / Fabric 的 `mods` 目录即可使用。`MmmRuntimeFactory` 经 `ServiceLoader` 创建设备常驻区域后端，默认设备编号为0，停服时释放设备资源。多显卡环境可用 Java 参数 `-Dmmm.gpuDevice=1` 选择其他设备，也可用 `-Dmmm.gpuDeviceName=显卡名称片段` 按名称选择。设备须支持当前内核所需的 Vulkan、FP64 与64位整数原子操作；缺少运行库或设备能力会报告具体启动错误。

Vulkan 后端沿现有 `RegionApplicationService` 请求、私有候选和区域原子提交执行机械步与热步。日常观察、显示、单元库存查询和实体采样读取已提交设备代；存档、世界碰撞编辑与容器操作通过显式冷导出交接，后续求解重新导入设备。CPU 与 Vulkan 共用区域存档格式。

设备请求失败时保留已提交区域，私有候选失败沿区域数值暂停流程报告。Java 参数 `-Dmmm.solverBackend=cpu` 显式选择 CPU 后端；此时 `-Dmmm.pressureCompute=vulkan` 可选择 GPU 压力计算服务。独立 `/mmmgpu` probe 保留自己的生命周期，主模组的正常 GPU 执行由主模组内嵌 kernel 提供。

<a id="tick"></a>
## 一次区域计算

1. `MinecraftSimulationLoop` 检查世界、区域可用性和恢复工作，把可以处理的区域交给 `MinecraftRegionTick`。
2. `MinecraftRegionInputs` 刷新到期的碰撞与热交换输入，记录实体观测，生成本 tick 的 [RegionRuntime](../../app/src/main/java/mmm/application/RegionRuntime.java) 调度快照。
3. `RegionTickUseCase` 根据运行状态和执行间隔选择 `NONE`、`MOTION` 或 `THERMAL`。同一个 tick 选择一项，力学计算优先于单独热计算。
4. 到期的计算通过用例持有的 `StepExecutor` 调用 `RegionApplicationService`。请求包含区域 UUID、预期求解器代次、预算和本次输入。
5. 结果成功提交后，`MinecraftRegionTick` 应用本次原生实体冲量、推进有限设备交换并更新热观察、休眠判断和快照。完整耗时从输入捕获开始覆盖这些工作，最后统一记录统计与 pacing，沿用原40／200 ms调度。

失败或跳过的计算保持原有调度进度。力学与热休眠分别记录，因此静水仍可导热、发生相变，温度稳定的液体也仍可运动。

静水休眠只切换调度状态，保留粒子速度、动量和动能，唤醒后从这些原始数据继续计算。连续 200 个完成步满足低液体速度、颗粒静止、压力残差及无外界扰动等条件后才允许休眠；内部导热、相变待处理几何和气体孔流也会阻止进入休眠。

气体活动通过 `GasPressureBoundary` 使用当前热力学压力与孔流共用的整数均压结算判断。所有连接都已没有可转移的均压余量时，气体允许力学休眠；浮点压差残差不再单独阻止休眠。判断不使用孔面积或单步流率，因此尚未均压的细小孔流仍保持活动，即便当前单步转移舍入为零。无孔连接的孤立节点不受此条件影响，也不引入额外的压力截断阈值。

### 运行状态与性能暂停

| 状态 | 行为 |
|---|---|
| `ACTIVE` | 按 20 Hz 目标推进 |
| `REDUCED` | 单步超过 40 ms 后，按 1–4 tick 间隔推进完整的一步 |
| `PAUSED` | 玩家暂停，或区域因外壳、身份、数值问题暂时停止计算 |
| `PERFORMANCE_PAUSE` | 连续三步超过 200 ms 后暂停，可通过 `run` 或手动 `step` 重试 |
| `UNLOADED` | 释放求解器数组，保留区域元数据和预算预留 |

存档记录玩家运行意图、恢复状态、执行间隔和 tick。连续过载、恢复计数属于本次进程的调度记录。性能重试清除这次耗时记录；数值失败仍按其具体原因处理。

新控制器首次成功提交后开始运行。外壳破损造成的暂停保留玩家意图，条件恢复后按该意图继续。缺少世界区块时暂停受影响的物理活动组；全部组等待时设置 `CHUNK_MISSING` 并保留内存求解域。

<a id="candidate"></a>
## 后端计算与提交

[FluidDomainState](../../solver/src/main/java/mmm/fluid/FluidDomainState.java) 保存完整物理输入。
力学计算使用 [FluidStepRequest](../../solver/src/main/java/mmm/fluid/FluidStepRequest.java) / [FluidStepCandidate](../../solver/src/main/java/mmm/fluid/FluidStepCandidate.java)，
单独热计算使用 [FluidThermalStepRequest](../../solver/src/main/java/mmm/fluid/FluidThermalStepRequest.java) / [FluidThermalStepCandidate](../../solver/src/main/java/mmm/fluid/FluidThermalStepCandidate.java)。

后端在私有状态上完成计算。`RegionApplicationService` 检查后端标识、紧邻代次和完整 `Layout`，其中包括网格、求解配置、预算、外壳、载体种类、注册表指纹和功能配置。`FluidRegionRepository` 在替换当前状态前再次核对区域身份。

CPU 可以把已算好的私有 `FluidDomain` 交给区域，省去正常帧的序列化与重建。该对象只交接一次。提交前，如存档、重放或诊断需要不可变状态，调用 `candidate.state()` 生成快照；提交后该对象已成为活动状态，历史候选的 `state()` 会拒绝调用。

其他后端可以提供完整不可变状态，接受相同检查。成功结果向 Minecraft 层返回本步统计和实体作用冲量。布局、容量或收支校验失败时，返回对应失败类型，区域保留原状态并暂停计算。

本次输入还会携带世界热交换面和最近一次真实压力投影等计算数据。这些数据供后续计算使用，区域存档保存的物质库存另由 [存档专题](region-state.md)定义。

<a id="admission"></a>
## 区域登记与可用性

[RegionCatalog](../../app/src/main/java/mmm/application/RegionCatalog.java) 保存 UUID、维度、包围盒、预算预留和区域对象标识，负责数量限制、重复身份、重叠、选择与替换。
[FluidRegionManager](../../fabric/src/main/java/mmm/simulation/FluidRegionManager.java) 负责 Minecraft 数据与这些描述之间的转换。

新区域在首次 HEAD 提交成功后登记。恢复时，已经提交的元数据先登记，再检查当前预算和运行条件。暂时超出预算的区域仍保留身份与预留量，等待以后恢复求解器。

[FluidRegionOperations](../../fabric/src/main/java/mmm/simulation/FluidRegionOperations.java) 依次检查区域自身的数值失败、世界方块归属与政策、目录预算、外壳和区块。数值或归属问题在原处阻止运行；政策、预算或外壳造成的卸载需要先成功保存完整 checkpoint。世界区块缺失时继续检查已加载外壳，将单元可用性交给活动组；其他独立组保持运行。

冷恢复通过 `RegionRepositoryAdapter.restoreIncrementally` 分批读取同一 HEAD 的 section。完整区域磁盘库存到齐后才建立完整 CPU 求解域，再检查各活动组所需的世界区块。世界区块缺失期间保留读取进度；缺失或损坏的磁盘分片保留具体恢复错误。

[RegionAvailabilityReason](../../api/src/main/java/mmm/api/RegionAvailabilityReason.java) 给出具体不可用原因。
控制器扫描在 [controller 包](../../fabric/src/main/java/mmm/simulation/controller/)：它检查已经加载的世界，要求唯一封闭内腔、完整所需区块、无重叠且预算足够。

<a id="console"></a>
## 控制器查询和确认操作

[MinecraftControllerConsole](../../fabric/src/main/java/mmm/simulation/MinecraftControllerConsole.java) 先校验服务器线程、玩家距离、控制器方块和区域归属。
[ControllerSummaryAdapter](../../fabric/src/main/java/mmm/simulation/ControllerSummaryAdapter.java) 提供摘要数据，`ControllerQueryUseCase` 验证身份和字段后，
生成摘要并签发绑定玩家与区域的一次性令牌。

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
## 转移前同步相变几何

相变发布实际库存及几何后，将受影响格加入待机械推进集合。成功机械步根据现有库存、占容和气腔求解压力及运动，再清除集合。相变阶段同时结算物质携带的内能、动能与模型中的势能交接。桶、发射器、管理员转移或生命周期操作若遇到这项记录，先调用 [MinecraftPendingPhaseWorkBarrier](../../fabric/src/main/java/mmm/simulation/MinecraftPendingPhaseWorkBarrier.java)。

它通过当前后端执行一次仅含力学的计算，完成已有相变几何的力学同步。该次计算跳过导热、反应和相变，完成后清除已有待推进格。提交成功后，以新的已提交区域状态准备容器事务或操作预览；失败时保留相应的阻止原因。

<a id="world-inputs"></a>
## 控制器液位与比较器

`FluidControllerBlock.getAnalogOutputSignal` 通过 `MinecraftRegionObservation.controllerComparatorSignal` 读取 `ControllerComparatorSignals` 的已提交缓存。输出采用原版容器比例：空液为0；正液量输出 `1 + floor(14 × min(1, 液体体积／固定开放容量))`，满液为15。分母是固定方块碰撞留下的内腔容量，包含颗粒和连续固相所在的几何空间；实体、颗粒或固相运动均使用同一容量标尺。固定容量由 `FixedGeometrySnapshot` 构造时累加，读取为常数成本。固定方块碰撞改变时使用新的几何容量。

`FluidRegion` 在成功采用运动、热、容器和生命周期候选，以及可用性、卸载、恢复改变后发布集中观察；`FluidRegionManager` 统一登记、替换和移除观察对象。快照发布入口补齐直接库存编辑，更新发生在检查在线玩家之前。`ControllerComparatorSignals` 仅在实际信号改变时通知原生比较器，不按 tick 扫描区域库存。主动暂停和静水平衡休眠保留稳定信号；区域不可用、卸载、移除和未激活时输出0。

比较器读取只访问位置缓存，不加载区块、不恢复求解器、不唤醒区域、不生成网络快照，也不提交 checkpoint。服务端恢复区域后由相同观察入口重新发布液位。

## 实体与碰撞输入

`MinecraftRegionInputs` 按五 tick 节拍刷新到期的世界碰撞输入。[MinecraftCollisionWorldPort](../../fabric/src/main/java/mmm/simulation/MinecraftCollisionWorldPort.java)
把 `VoxelShape` 采样为纯 Java [CollisionField](../../model/src/main/java/mmm/world/CollisionField.java)，变化的网格单元同时唤醒力学与热计算。暂停组保留已有固定碰撞；所需世界区块重新加载后立即请求重新采样。

[FluidRegionWorldInteraction](../../fabric/src/main/java/mmm/simulation/FluidRegionWorldInteraction.java) 先比较采样结果与当前几何。
存在变化时，从完整状态快照重建私有流体域，在副本上完成碰撞编辑、颗粒处理及液体排出和回填。`FluidRegion.adoptCollisionCandidate` 核对原对象、求解器代次和功能布局，再通过完整状态捕获同步并校验气体拓扑；
成功后一次替换流体域并递增求解器代次。持久化 HEAD 代次保持原值，沿既有 checkpoint 流程存盘。

密闭气体失去全部可用容量时，本次碰撞候选被拒绝，原几何、物质库存和收支记录保留，调用方按碰撞失败暂停区域。没有碰撞变化时直接返回，不创建私有副本。`region-manager` 覆盖排液提交、过期候选拒绝、气腔消失时保持原状态，以及修正碰撞场后的再次提交。

实体位置、包围盒和原版速度响应留在 Fabric。输入适配器比较相邻服务器 tick 的实际观测位置，得到本 tick 位移；`Entity#oldPosition` 会在 Minecraft tick 内被重写，无法提供这里需要的完整位移。首次观测或中断一 tick 的实体以零规定速度提交形状。

求解器按实体包围盒、同代 PLIC 与固定开放微盒计算浸湿体积、湿表面及压力面积分；有限气腔对干表面的作用同时计入。[MovingBodyFluidCoupling](../../model/src/main/java/mmm/world/MovingBodyFluidCoupling.java) 把稳定实体 UUID、局部包围盒和实际位移速度交给力学请求，已接受的实体表随区域状态保存恢复。
到期力学请求提供完整目标表，空表移除已消失实体；超过256个请求整份拒绝。私有候选按联合占容刷新容量、开口与规定体积通量，压力转置给出逐实体反力，规定速度参与外功。成功力学提交后才对仍匹配捕获位置的原生实体应用一次响应；失败、跳过和热轴清除待响应。相变机械同步保留已接受姿态并采用零规定速度。当前实现外部规定运动的包围盒耦合；有限质量实体动态平移的联立方案尚未实现。

水接触灭火复用同一已提交 PLIC／开放微盒的实体湿接触快照。`MovingBodyWetGeometry.capture` 的 `waterContactVolumeCubicMetres` 按 C05 的物理材料层计算实际水交叠体积，水油交界分别裁剪；单一水层直接复用已有交叠体积。`MinecraftRegionInputs.captureEntities` 还观察燃烧中的骑乘、飞行等实体，灭火资格独立于机械响应资格。成功力学提交后，UUID 对应实体仍存活且包围盒匹配本次观察、有正水交叠体积时调用原版 `clearFire`；失败、跳过、热轴及姿态变化均丢弃待处理响应。该行为沿用区域实体交互开关。

这是水湿接触对原版燃烧标记的玩法响应近似，区域物质与内能保持原值；蒸发、吸收和燃烧放热尚未建模。材料身份来自服务端库存与物理层，客户端外观不参与判断。

外部运动实体的默认响应采用等效水动力质量：720 kg/m³ × 包围盒体积 × 求解器重力／宿主重力；零重力时使用720 kg/m³ × 体积。宿主运动约束保持自己的重力，液体力由真实压力积分给出。


## 物理活动组

一个区域中的固定开放空间按实际通路组织活动组。共享有限气腔、颗粒支撑、实体作用范围、导热接触或有限设备的空间共同推进。组的划分遵循物理关联；Minecraft section 继续负责存档和加载。

力学与热化学分别维护活动状态。安静组进入休眠后，压力、黏性、体力、输运及热化学阶段跳过对应工作，原库存、MAC 动量、压力与内能保留。局部物质、温度或运动输入唤醒关联组，几何及设备连接改变时重建关联关系。只读观察返回已维护的活动统计。

所有组共用区域的权威库存与收支账。后端在完整私有候选中计算活动组，同一候选提交其全部后继；跨 section 面继续按现有唯一归属结算。进程内候选交接保存调度状态；冷恢复从完整物理库存重新建立活动状态。

[MinecraftGroupAvailability](../../fabric/src/main/java/mmm/simulation/MinecraftGroupAvailability.java) 的 `prepare` 采样每个求解单元及其邻接方块的世界区块，交给 `PhysicalActivityGroups.prepareAvailability`。一个组含有不可用单元时，力学与热化学共同暂停，库存、MAC 动量和内能保留；其他独立组沿同一区域候选继续推进。实体及外部交换也按组可用性捕获输入。全部组等待区块时区域保留完整内存求解域。区块恢复后，相关组唤醒，碰撞输入刷新。

## 原版静水池操作

[NativePoolScanner](../../fabric/src/main/java/mmm/simulation/controller/NativePoolScanner.java) 识别预算内、已加载、底壁完整实心、水面水平且顶部敞开的有限静水池。[MinecraftPoolOperations](../../fabric/src/main/java/mmm/simulation/MinecraftPoolOperations.java) 的 `probe` 提供管理员预览与10秒内潜行确认，接管沿区域激活与世界归属事务执行，每个原版水源记为1 m³水。池壁破损或大气开口被填堵时暂停。

探针对池底或池壁确认归还时，`returnWater` 先同步待处理相变几何并暂停池体，再由 `FluidRegionOperations.returnPoolWater` 将完整、静止、常温纯水方块交还世界。混合物、额外内能、运动及零头保持在模拟库存中，可通过容器取走；全部内容交接后由生命周期操作移除空池。归还按确认时实际库存执行。`allowsBlockChange` 与世界写入钩子统一管理已接管池体内的原版水写入，持久事务重放通过 `withWorldWrite` 执行。
