# Create 适配计划

本计划以 Minecraft 26.2 / Fabric 的物料箱自动化为当前交付，并为 Create 可用后的运动、流体、动能与显示接入确定职责。现阶段没有可用的 Create 26.2 依赖；本轮通过 Fabric API 和项目逻辑验证，不进行 Create 实机测试。各阶段完成情况继续记录在[实施计划](solver-consolidation-plan.md)，验证结果记录在[验证指南](../maintenance/validation.md)。

源码参考为 Fabricators-of-Create/Create 的 `mc1.21.1/fabric/dev` 开发分支，[提交 `7f1f67b6`](https://github.com/Fabricators-of-Create/Create/tree/7f1f67b6ecbbde512e8d35404f3621a6875ce838)；另查阅 `mc1.20.1/fabric/dev` 的[提交 `4c3fe779`](https://github.com/Fabricators-of-Create/Create/tree/4c3fe779d135d592548e4cb09da04d742d2637b7)。这些是源码参考，26.2 移植后需重新核对名称、时序、单位和行为。1.21.1 源码位于本地 `build/upstream/CreateFabric121/src/main/java/com/simibubi/create/`，不参与本项目构建。

## 接入矩阵

| 能力 | 本轮基础 | Create 26.2 可用后的交付 |
|---|---|---|
| 物料箱物品搬运 | 通过 Fabric `ItemStorage` 事务搬运实际容器，检查嵌套提交、回滚与未决事务保护 | 用漏斗和仓储网络验证插入、取出与重载；机械臂直接选择箱体需另行注册交互点 |
| 流体管道 | 保留完整容器和现有区域事务；明确单位与库存交接规则 | 明确支持的流体表示与精度，沿 Fabric 流体存储和区域 WAL 完成原子交接 |
| 测量显示 | 传感器维护已接受读数、状态和单位 | 将服务端读数接入 Display Link；护目镜所需客户端数据单独同步 |
| 平移结构 | 研究局部方块碰撞、位姿、身份和组装交接 | 以实际碰撞分块驱动流体，覆盖停止、卸载、拆解与恢复 |
| 旋转结构与动能 | 保留刚体真实几何和物理功要求 | 实现旋转几何、力矩及 Create 负载映射，贯通流体与动力网络 |

## 物料箱与容器

本轮在 `MaterialBoxBlockEntity` 为九个槽提供 Fabric 物品存储入口。插入和提取以完整、真实容器为单位，容器中的材料、物种、质量、内能与数据组件随物品保留。箱内事务身份只在容器进入时建立，离开时清除；自动化与玩家交互共享同一槽位规则。未决交接锁住对应槽和箱体拆除，已有槽位身份及持久代次继续供恢复使用。

内置端口沿共同容器事务直接完成区域与物品交接，持续换桶保持公共 actor 注册数量不变。实际物品前态先持久保存，区域 HEAD 决定恢复方向，物品后态保留非库存组件；无需为每只新容器建立外部调用者的持久回执。

Fabric 事务可模拟、嵌套、提交和回滚；存储实现需在事务最终提交前保存快照，回滚恢复全部槽位，只有最终提交才通知持久化。测试覆盖空槽、满槽、不支持的物品、逐个容器、组件保留、嵌套回滚及锁定槽。对应入口为 `fabric/src/main/java/mmm/block/MaterialBoxBlockEntity.java`、`fabric/src/main/java/mmm/simulation/IndustrialOperations.java`，区域和容器事务见 `fabric/src/main/java/mmm/simulation/MinecraftContainerTransferPlan.java`、`OwnershipTransactionCoordinator.java`。

上游 `content/kinetics/mechanicalArm/ArmInteractionPoint.java:102,107–123` 和 `foundation/blockEntity/behaviour/inventory/InvManipulationBehaviour.java:63–66,94–104` 使用 Fabric 物品存储。本轮验证 Fabric 26.2 存储接口，Create 实际调用须待对应版本发布后验证。Create 不作为本轮编译或运行的硬依赖。

上游 `AllArmInteractionPointTypes` 登记各类专用目标，机械臂通过 `ArmInteractionPointType.getPrimaryType` 选择可用交互点。物料箱提供 `ItemStorage` 后可准备漏斗与箱体的搬运路径，机械臂可经漏斗间接操作；直接选择箱体需在真实 Create 版本注册相应交互点类型。上游 `ItemCountDisplaySource.java:44–49` 在事务中模拟逐槽提取来计数，因此该显示代表当前可提取数量，受未决事务锁保护的槽不计入该数值。

## 流体管道与物质账

上游 `content/fluids/FluidNetwork.java:185–240` 在嵌套事务中模拟，再于外层事务提交输入与输出；`content/fluids/transfer/GenericItemFilling.java` 和 `GenericItemEmptying.java` 使用 `FluidStorage.ITEM` 与容器上下文。Fabric 26.2 的 `FluidConstants.BUCKET` 为 81000 droplets，本项目一桶为 10⁹ nanos，即 81 droplets 对应 10⁶ nanos（1 L）。若开放低于该量子的物质交接，必须为双方保存可恢复的精确余数。

`FluidVariant` 可携带组件，其数量可能被拆分和合并；把整批物种、质量或内能直接复制到每份 variant 会重复库存。接入前要选定明确可表示的材料、组成、温度和剩余量规则，分份与合并共用项目的整数库存和能量结算。普通水也要记录来源和能量；不同组成或温度的流体按已定义混合模型处理。

Fabric 的内存事务与区域 HEAD、容器 WAL、外部世界存档的持久提交各有生命周期。直接管道接入须设计可恢复的交接和中断对账，避免在 `insert`、`extract` 或普通提交回调中直接执行跨域持久提交。验收包括模拟回滚、分批搬运、跨重启恢复以及双方质量、物种、能量的守恒。当前优先使用装有完整载荷的实际容器完成自动化。

## 传感器与显示

传感器沿 `IndustrialOperations.tickSensor` 使用已提交区域代的液位、温度、绝对压力或流量；暂停保留末次读数，不可用时呈现相应状态。数据展示复用同一观察，保持液位 0–1、温度 K、压力 Pa 和流量 L/s，无需额外触发 GPU 读取。

上游 `api/behaviour/display/DisplaySource.java:41–48,146–152` 登记来源并生成文本，`content/redstone/displayLink/DisplayLinkBlockEntity.java:71–132` 调度目标更新。Create 26.2 可用后，服务端 Display Link 直接读取传感器当前已接受的读数和状态；配置和刷新间隔遵循实际版本接口。

上游 `content/equipment/goggles/GoggleOverlayRenderer.java:97–111` 从客户端方块实体读取 `api/equipment/goggles/IHaveGoggleInformation` 提示。护目镜接入需为 `ProcessSensorBlockEntity` 增加有界客户端同步，复用服务端同一观察，明确初始加载、变化、暂停和不可用状态。阈值开关读取整数范围，物理量的缩放和极值须结合实际版本与支持量程选定。验收覆盖正常读数、单位、暂停、区域不可用、状态恢复和客户端重载。

## 运动结构与流体

### 组装、采样和拆解

上游 `content/contraptions/piston/MechanicalPistonBlockEntity.java:49–88` 组装并从世界移除方块后创建运动实体；`bearing/MechanicalBearingBlockEntity.java:167–195` 采用相同职责链。`Contraption.java:1062,1154` 负责移除和放回，`AbstractContraptionEntity.java:668–695` 停止、还原并移除实体。

MMM 的 `MinecraftWorldUpdates` 负责原版活塞运动和固定碰撞交接；接入 Create 时扩展同一职责链：组装时把固定形状交给运动体，拆解时把已接受运动形状交还固定碰撞。已接受代、世界当前状态和挂起的目标运动共同决定交接时点；遇到不可用区域保留已接受状态。停止、区块卸载、重新加载和结构删除也要处理完整生命周期。

上游 `Contraption.getBlocks()` 提供局部方块，`getContraptionWorld()` 提供查询碰撞所需的局部世界；`Contraption.java:1482–1501` 根据各方块 `getCollisionShape` 生成局部方盒。实体局部到世界的变换见 `AbstractContraptionEntity.toGlobalVector/toLocalVector`（:344–370），旋转中心为局部 `(0.5, 0.5, 0.5)`，再叠加实体 anchor。适配器在服务端保存上一次已接受位姿及当前实际位姿，按区域时间推进采集累计运动；停机时保留位置。局部形状以实体 UUID、局部坐标和确定排序的形状分片建立稳定身份。

`MinecraftRegionInputs.captureEntities` 当前将普通实体的大包围盒作为运动体。Create 结构接入时需从普通实体路径排除，再提交其实际局部形状，避免重复占容。区域每步现有 `MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP=256` 覆盖所有运动体；大结构需按完整结构设计形状合并和预算，使用明确的支持范围，保持全部实际碰撞，超出时给出可观察结果。

第一轮运动接入限定姿态固定且世界轴对齐的平移构件，按真实碰撞分盒提交位置与速度。验收覆盖装配、平移排水、停止、拆解、卸载和恢复，检查液体及气体库存、静态与运动几何交接，以及同一区域其他实体的共存。

### 旋转与动力

`MovingBodyFluidCoupling.Request` 当前只保存轴对齐 `Bounds`；角速度参与表面点速度。`EntityBodyCoupling` 和 `VulkanEntityBodyCoupling` 用盒的真实交叠计算占容与开口，`VulkanLiquidRegion.interpolateBodies` 对盒极值插值。自由旋转必须把局部碰撞形状及刚体位姿贯穿占容、开口、湿接触、子步插值、设备编码和力矩计算，或采用明确精度及误差的守恒近似。旋转外包盒用于空间粗筛；90° 两端之间的中间姿态同样需要旋转形状。

上游 `KineticBlockEntity.getSpeed()` 返回过载状态下的有效转速，`calculateStressApplied()` 给出配置的基础负载；`KineticNetwork.getActualStressOf()` 将贡献乘以绝对理论转速。该版 `convertToAngular` 为 `speed × 0.3` 度／tick；实际平移速度、方向、停止和限速由对应驱动器决定，应以实际位姿变化为准。

Create 应力单位是游戏量纲，项目 `BodyImpulse` 和 `externalWorkJoules` 使用物理量。双向动力交接需先定义应力单位与力矩、功率的标定、网络更新和过载处理，并在已接受物理步结算，保证候选失败时双方运动和库存仍一致。先交付指定运动驱动流体及其冲量与外部功审计，再按明确的动力契约接入负载回写。验收包括反转、停转、过载、不同转速、功与角冲量方向及候选失败恢复。

## 真实版本集成

Create 26.2 依赖可用后，核对映射、注册方式、组装及 tick 时序、碰撞上下文、运动实体持久化和显示／动力 API，再实现对应绑定。验证顺序为 Fabric 自动化、服务端集成场景和实际 Create 组合；涵盖容器传送、显示、结构交接与动力反馈。当前源码参考和纯逻辑验证作为实现依据，实际版本的构建及运行结果另行记录。
