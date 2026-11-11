# 运行时与区域操作

修改服务器启动、区域调度、运行状态、实体与碰撞输入或探针操作时读本文。计算公式见[流体求解器](fluid-solver.md)，磁盘提交见[区域存档](region-state.md)。

| 职责 | 代码入口 | 测试选择器 |
|---|---|---|
| 启动、服务组装与停服 | [MmmRuntimeFactory](../../fabric/src/main/java/mmm/simulation/MmmRuntimeFactory.java)、[MinecraftSimulationLoop](../../fabric/src/main/java/mmm/simulation/MinecraftSimulationLoop.java) | `region-state,lifecycle` |
| tick 调度与性能暂停 | [RegionTickUseCase](../../app/src/main/java/mmm/application/RegionTickUseCase.java)、[MinecraftRegionTick](../../fabric/src/main/java/mmm/simulation/MinecraftRegionTick.java) | `lifecycle` |
| 单步计算与提交 | [RegionApplicationService](../../app/src/main/java/mmm/application/RegionApplicationService.java)、[FluidRegionRepository](../../fabric/src/main/java/mmm/simulation/FluidRegionRepository.java) | `lifecycle` 与受影响的物理测试 |
| 区域预算与可用性 | [RegionCatalog](../../app/src/main/java/mmm/application/RegionCatalog.java)、[FluidRegionOperations](../../fabric/src/main/java/mmm/simulation/FluidRegionOperations.java) | `region-manager` |
| 控制器摘要与操作授权 | [ControllerQueryUseCase](../../app/src/main/java/mmm/application/ControllerQueryUseCase.java)、[RegionLifecycleUseCase](../../app/src/main/java/mmm/application/RegionLifecycleUseCase.java)、[MinecraftRegionLifecycle](../../fabric/src/main/java/mmm/simulation/MinecraftRegionLifecycle.java) | `lifecycle,protocol` |
| 实体与碰撞输入 | [MinecraftRegionInputs](../../fabric/src/main/java/mmm/simulation/MinecraftRegionInputs.java) | `entity-fluid`、`shape,cut-cell` |

执行方式见[验证指南](../maintenance/validation.md)。改动类职责或依赖方向时运行 `verifyArchitecture`。

<a id="startup"></a>
## 服务器启动与服务组装

[MmmMod](../../fabric/src/main/java/mmm/MmmMod.java) 在 `SERVER_STARTING` 通过 [MmmRuntimeRegistry](../../fabric/src/main/java/mmm/simulation/MmmRuntimeRegistry.java) 建立当前服务器的运行时，方块、网络与 API 回调都从这里查找服务。`MmmRuntimeFactory` 按顺序组装：

1. `WorldStartupCoordinator` 读取世界配置，验证或恢复世界能力包。
2. `CommittedWorldRegistries` 保存本次启动确认的材料定义与功能配置。
3. 建立区域管理与存档服务，完成删除区域的隔离，扫描 ownership 与容器事务日志。
4. 组装运行策略、单步执行器、区域输入、tick、可用性、控制器操作、容器与观察服务。

依赖已加载世界的恢复放在 `SERVER_STARTED`，由 `MinecraftSimulationLoop` 负责，同时负责后续 tick 与停服顺序。新增服务由 `MmmRuntimeFactory` 组装注入，实例随服务器创建和释放。外部 Mod 可在启动前订阅事件，运行时建立后接管同一事件中心；启动前的查询返回暂不可用。

区域求解默认使用 Vulkan。主模组打包了 GPU kernel、provider 服务声明与 Windows x64 运行库，放入 `mods` 目录即可使用。默认使用设备 0，多显卡环境用 `-Dmmm.gpuDevice=1` 或 `-Dmmm.gpuDeviceName=名称片段` 选择。设备须支持当前内核所需的 Vulkan、FP64 与 64 位整数原子操作，缺失时报告具体启动错误。设备故障停止受影响区域，恢复流程以 HEAD 与日志核对外部事务后从一致 checkpoint 恢复。

<a id="tick"></a>
## 一次区域计算

1. `MinecraftSimulationLoop` 检查世界、区域可用性与恢复工作，把可处理的区域交给 `MinecraftRegionTick`。
2. `MinecraftRegionInputs` 刷新到期的碰撞与热交换输入，记录实体观测，生成本 tick 的 [RegionRuntime](../../app/src/main/java/mmm/application/RegionRuntime.java) 调度快照。
3. `RegionTickUseCase` 选择 `NONE`、`MOTION` 或 `THERMAL`，同一 tick 只执行一项，力学优先。
4. 到期计算经 `RegionApplicationService` 执行，请求包含区域 UUID、预期求解器代次、预算与本次输入。
5. 提交成功后应用实体冲量、推进有限设备交换，更新热观察、休眠判断与快照，并记录本 tick 完整耗时。

失败或跳过的计算保持原调度进度。力学与热休眠分别记录：静水仍可导热和相变，温度稳定的液体仍可运动。休眠只切换调度状态，速度、动量与动能原样保留；液体与颗粒长期静止、压力残差收敛且无外界扰动时才进入力学休眠，导热、待处理相变几何与气体输运会阻止休眠。

### 运行状态

| 状态 | 行为 |
|---|---|
| `ACTIVE` | 按 20 Hz 推进 |
| `REDUCED` | 单步超出预算后，按 1–4 tick 间隔推进完整的一步 |
| `PAUSED` | 玩家暂停，或因身份、数值问题停止 |
| `PERFORMANCE_PAUSE` | 连续严重超时后暂停，可通过 `run` 或 `step` 重试 |
| `UNLOADED` | 释放求解器数组，保留元数据与预算预留 |

存档保存运行意图、恢复状态、执行间隔与 tick；超时计数只在本进程内有效。新选区首次提交后开始运行，方块变化刷新碰撞与热接触，运行意图保持不变。

<a id="candidate"></a>
## 后端计算与提交

[FluidDomainState](../../solver/src/main/java/mmm/fluid/FluidDomainState.java) 保存完整物理输入。力学计算使用 `FluidStepRequest` / `FluidStepCandidate`，单独热计算使用 `FluidThermalStepRequest` / `FluidThermalStepCandidate`。

后端在私有状态上完成计算，候选持有完整设备后继。`RegionApplicationService` 核对后端标识、紧邻代次与完整布局（网格、求解配置、预算、几何、载体种类、注册表指纹、功能配置），`FluidRegionRepository` 替换前再次核对区域身份。成功候选交接一次并返回统计与实体冲量；失败或过期候选释放资源，已提交代保持有效。日常观察、显示、查询与实体采样读取已提交设备代；存档与编辑显式捕获状态，编辑在宿主私有副本上规划后写入设备候选。

<a id="admission"></a>
## 区域登记与可用性

[RegionCatalog](../../app/src/main/java/mmm/application/RegionCatalog.java) 保存 UUID、维度、包围盒与预算预留，负责数量限制、重复身份、重叠、选择与替换。新区域在首个 HEAD 提交后登记；恢复时已提交元数据先登记，再检查预算与运行条件，暂时超预算的区域保留身份与预留量。

`FluidRegionOperations` 依次检查数值失败、方块归属与政策、目录预算和区块：数值或归属问题在原处阻止运行；政策或预算导致的卸载需先成功保存完整 checkpoint。冷恢复分批读取同一 HEAD 的 section，完整库存到齐且区块可用后导入设备。[RegionAvailabilityReason](../../api/src/main/java/mmm/api/RegionAvailabilityReason.java) 给出具体不可用原因。

### 物理活动组

`PhysicalActivityGraph` 根据同代设备摘要合并开放格面、逐格气体、颗粒支持与接触、实体扫掠范围和有限热设备端口，`MinecraftGroupAvailability` 采集区块可用性。任一成员不可用时整组暂停，保留库存、内能、动量与相变待处理状态；所有开放组都不可用时设置 `CHUNK_MISSING`。可用性恢复后刷新碰撞并唤醒区域。清空、排气、移除、归还原版水与演示重置要求全部相关区块已加载。

<a id="console"></a>
## 控制器查询与确认操作

`MinecraftControllerConsole` 校验线程、距离、目标位置与区域归属，`ControllerQueryUseCase` 生成摘要并签发绑定玩家与区域的一次性令牌。UNLOADED 区域显示已知元数据。玩家可选择、暂停、继续和刷新区域；显式继续或刷新可重试性能暂停，数值故障保持可见。

清空、排气、释放归属和删除采用预览与确认两步：玩家先暂停区域，管理员权限通过后同步待处理相变并生成短时有效的预览，列出实际移除量与阻止原因；确认时重新校验玩家、区域代次、动作与当前状态，随后由 Minecraft 层执行对应事务。四种操作的语义见[区域生命周期](world-ownership-and-containers.md#区域生命周期)。

<a id="phase-work"></a>
## 转移前同步相变几何

相变发布实际库存与几何后，把受影响格加入待机械推进集合，下一个成功的力学步求解压力与运动后清除集合。桶、发射器、管理员转移或生命周期操作遇到该集合时，先经 [MinecraftPendingPhaseWorkBarrier](../../fabric/src/main/java/mmm/simulation/MinecraftPendingPhaseWorkBarrier.java) 执行一次仅含力学的计算（跳过导热、反应与相变），提交成功后再基于新状态准备事务或预览；失败时保留阻止原因。

<a id="world-inputs"></a>
## 传感器

`ProcessSensorBlockEntity` 保存测量类型、范围、阈值与输出，`IndustrialOperations.tickSensor` 周期读取目标区域的已提交观察：液位为测量范围内液体体积与开放体积之比，温度按对应相质量加权，压力与流量读取实际状态。读数按上下限、方向与模拟/开关模式转换成 0–15 红石信号，区域不可用时输出零。`FluidRegion` 发布集中观察，`FluidRegionManager` 管理观察对象的登记与移除。

## 碰撞与实体输入

**碰撞**：`MinecraftRegionInputs` 周期刷新到期的世界碰撞。[MinecraftCollisionWorldPort](../../fabric/src/main/java/mmm/simulation/MinecraftCollisionWorldPort.java) 把 `VoxelShape` 采样为 [CollisionField](../../model/src/main/java/mmm/world/CollisionField.java)，变化的格同时唤醒力学与热计算。[FluidRegionWorldInteraction](../../fabric/src/main/java/mmm/simulation/FluidRegionWorldInteraction.java) 在私有副本上完成碰撞编辑、颗粒处理以及液体排出和回填，`FluidRegion.adoptCollisionCandidate` 核对身份后一次替换流体域。密闭气体失去全部容量且无干口可排时拒绝该候选并暂停区域，原状态保留。

**实体**：实体位置、包围盒与原版速度响应留在 Fabric。输入适配器比较相邻服务器 tick 的观测位置得到本 tick 位移（首次观测按零速度提交）。求解器按实体包围盒、同代 PLIC 与开放微盒计算浸湿体积、湿表面与压力面积分，并计入局部气体压力。[MovingBodyFluidCoupling](../../model/src/main/java/mmm/world/MovingBodyFluidCoupling.java) 把实体 UUID、局部包围盒与位移速度交给力学请求；压力转置给出逐实体反力，提交成功后才对仍匹配观测位置的实体施加一次响应。外部运动实体默认按等效水动力质量响应：720 kg/m³ × 包围盒体积，并按求解器与宿主重力之比缩放。有限质量实体的动态联立尚未实现。

**灭火**：同一湿接触快照给出实际水交叠体积（水油分层裁剪）。提交成功后，燃烧中的实体若仍匹配观测位置且水交叠为正，调用原版 `clearFire`。区域物质与内能保持不变，蒸发与燃烧放热尚未建模。

## 原版静水池

[NativePoolScanner](../../fabric/src/main/java/mmm/simulation/controller/NativePoolScanner.java) 识别预算内、已加载、底壁实心、水面水平且顶部敞开的静水池，[MinecraftPoolOperations](../../fabric/src/main/java/mmm/simulation/MinecraftPoolOperations.java) 提供管理员预览与限时潜行确认，每个原版水源记为 1 m³ 水。池壁破损或开口被堵时暂停。归还与事务规则见[原版小池接管与归还](world-ownership-and-containers.md#原版小池接管与归还)。
