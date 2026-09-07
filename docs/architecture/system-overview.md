# 系统总览

本文用于理解模块关系、物理数据和一次计算的顺序。已有明确修改目标时，可以从 [README 需求表](../../README.md)直接进入专题。

## 模块职责

| 模块 | 保存或执行什么 | 主要依赖 |
|---|---|---|
| [api](../../api/src/main/java/cc/ecisr/mmm/api/) | 外部查询、提交通知和带单位的数量类型 | JDK |
| [model](../../model/src/main/java/cc/ecisr/mmm/) | 材料定义、物质收支、载体状态、容器数据和存档模型 | api、必要的纯 Java 编解码库 |
| [solver](../../solver/src/main/java/cc/ecisr/mmm/fluid/) | CPU 物理计算、完整计算输入和候选结果 | model |
| [application](../../application/src/main/java/cc/ecisr/mmm/application/) | 调度、授权、事务协调，以及读取和提交区域的接口 | solver、model |
| [fabric](../../fabric/src/main/java/cc/ecisr/mmm/) | 世界与物品操作、持久化、数据包、命令和网络 | api、model、solver、application |
| [fabric client](../../fabric/src/client/java/cc/ecisr/mmm/) | 快照插值、几何生成和界面显示 | 客户端协议与展示数据 |
| [gpu-probe-kernel](../../gpu-probe-kernel/src/main/java/cc/ecisr/mmm/gpuprobe/) | Vulkan 计算实验、回读与独立验证 | solver |
| [gpu-probe](../../gpu-probe/src/main/java/cc/ecisr/mmm/gpuprobe/) | 可选 GPU probe 的 Minecraft 适配 | kernel，编译时引用 fabric |
| [core](../../core/build.gradle) | 聚合发布 api/model/solver/application | 无生产源码 |

世界读取、方块修改、物品变化和磁盘提交由 Fabric 在服务器主线程执行。数值模块接收纯 Java 输入，计算私有的下一步状态。客户端和外部查询读取服务端提供的数据。

依赖方向、包循环、运行时注册点和生产类 1000 行上限由 [ArchitectureDependencyValidation](../../fabric/src/test/java/cc/ecisr/mmm/validation/ArchitectureDependencyValidation.java) 检查。执行方式见 [验证指南](../maintenance/validation.md#architecture)。

## 从游戏操作到计算结果

```text
方块 / 物品 / 命令 / 网络请求
  → MmmRuntime 中对应的业务处理器
  → application 用例检查请求与当前状态
  → solver 在私有状态上计算完整结果
  → application 与区域仓库核对身份、代次和布局
  → Fabric 提交区域、世界或物品的变化
  → 发布客户端快照与适用的提交通知
```

服务器 tick 从 [MinecraftSimulationLoop](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftSimulationLoop.java) 开始，经过 [MinecraftRegionInputs](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionInputs.java)、[MinecraftRegionTick](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftRegionTick.java) 和 [RegionTickUseCase](../../application/src/main/java/cc/ecisr/mmm/application/RegionTickUseCase.java) 进入 [RegionApplicationService](../../application/src/main/java/cc/ecisr/mmm/application/RegionApplicationService.java)。启动顺序、状态提交、暂停、区域预算和控制器授权详见 [运行时与区域操作](runtime-and-lifecycle.md)。

## 哪些数据决定物质库存

| 领域 | 保存的原始数据 | 根据原始数据计算的量 |
|---|---|---|
| 液体 | 粒子位置、速度、体积、逐材料质量和相身份 | 单元体积、密度、压力、界面与曲率 |
| 化学 | 组分数量、组成、电荷、质量余量、指示剂来源和收支 | pH、主要组分列表、颜色 |
| 颗粒 | 活动与沉积固体的 SVQ、质量、材料、组成、孔隙率和收支 | 填充率、显示高度和样本 |
| 连续固相 | 固相组的 SVQ、质量、组成和总内能 | 显示体积分数 |
| 气体 | 各组分标准体积数量、质量、化学组分、节点内能和收支 | 温度、绝对压力、密度、声速与孔流速度 |
| 热学 | 各物质载体总内能、外部热量收支、机械功、设备换热和舍入差额 | 温度、热容、单元热平衡与浮力修正 |
| 有限热设备 | 区域存档中的设备质量、内能和代次 | 温度、接触位置与换热显示 |
| 世界与物品 | 区域 HEAD、世界归属日志和已存盘物品状态 | 控制台、诊断与调试显示 |

库存结算使用左侧的原始数据。一个操作改变多种载体时，它们的物质、组成和能量一起校验、一起提交。温度、颜色和渲染网格可以重新生成。

### 单位

- `BV` 是一个方块的体积，按 `1 m³` 计算。默认求解单元边长为 `0.5 m`，一个方块包含八个单元。
- 液体容器用整数纳体积：`1 BV = 1,000,000,000 nanos`。
- 固体用整数 `SVQ`：`1 BV = 2^27 SVQ`，默认单元为 `2^24 SVQ`。
- 化学用整数 quanta：`1 AU = 1,000,000,000 quanta`。AU 是本求解器使用的化学数量单位。
- 液体粒子质量权重通过 [SolverUnitScale](../../model/src/main/java/cc/ecisr/mmm/units/SolverUnitScale.java) 乘以 `cellSize³` 换成 kg。固体、气体、化学组分及容器质量直接保存 kg。
- 热学使用 K、Pa、J、W/(m·K) 和 J/(kg·K)。内能审计使用原始 double 或 `SignedDyadic` 精确表示。

浮点粒子数量与整数容器数量之间的转换需要明确舍入方向，并保留不能表示的差额。详细数据布局在 [材料](material-state.md)、[化学](chemistry-state.md)、[颗粒](granular-state.md)、[气体](gas-state.md)和[热学](thermal-state.md)专题中。

## 一个完整计算步

[FluidDomainStepCoordinator](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainStepCoordinator.java) 组织下列步骤：

1. 记录物质和能量收支，以及待结算的相变体积功。
2. 根据碰撞形状、固体占据体积和气体拓扑建立力学输入。
3. 把粒子速度传到网格（P2G），施加重力、浮力、表面张力，以及气体、颗粒和实体作用。
4. 用 PCG 联合求解液体压力和接触液体的有限密闭气体响应。环境与真空使用给定压力条件。
5. 把速度传回粒子（G2P），推进粒子位置并处理碰撞，完成颗粒运动。
6. 在新位置上重建热学网格，计算导热与恒温源热通量。
7. 执行化学反应和颗粒反应，计入反应热。
8. 执行固液、气液相变，把体积变化记入待结算压力功。
9. 处理沉积、唤醒和粒子拆分/合并，同步搬移材料、化学和内能数据。
10. 核对全部收支，成功后发布本步统计并判断后续活动状态。

力学顺序在 [MechanicalStepPipeline](../../solver/src/main/java/cc/ecisr/mmm/fluid/MechanicalStepPipeline.java)，传热、反应和相变顺序在 [ThermochemistryPipeline](../../solver/src/main/java/cc/ecisr/mmm/fluid/ThermochemistryPipeline.java)。具体离散方法见 [流体求解器](fluid-solver.md)，相变功的后续处理见 [转移前结算相变功](runtime-and-lifecycle.md#phase-work)。

## 存档和故障恢复

服务器首次启动时，把数据包中的材料定义与功能布局保存为世界能力包。已有世界使用已提交的包解释库存。启动后数据包与它不同会报告差异，原存档继续使用原定义。格式、身份或布局无法验证时保留文件并锁定，处理方式见 [区域存档](region-state.md)。

区域保存使用双槽完整代次。先写完整内容并回读验证，最后替换 `HEAD`，由它指向正式生效的一代。删除区域写入 `REMOVED` 删除标记，然后发送客户端移除消息、注销运行时并隔离旧载荷。

桶和发射器的操作还涉及物品存盘，世界接管涉及原版方块。它们使用 WAL（预写事务日志）记录操作前后状态，再按区域 HEAD 的提交结果完成恢复。具体决策表见 [世界归属与容器事务](world-ownership-and-containers.md)。

| 失败类型 | 处理方式 |
|---|---|
| `LOCKED` | 格式、身份、校验和或日志无法确定解释，保留磁盘文件并阻止覆盖 |
| 数值暂停 | 本步容量、温度、压力或收支校验失败，保留上一完整物理状态 |
| `PERFORMANCE_PAUSE` | 连续计算超时，保留状态并允许显式重试 |

## 查询和展示

[FluidSnapshotPublisher](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidSnapshotPublisher.java) 生成展示消息，[ClientRegionSnapshotStore](../../fabric/src/client/java/cc/ecisr/mmm/client/ClientRegionSnapshotStore.java) 按区域代次和序号接收，再交给客户端几何与绘制代码。旧包在区域移除后失效。

[MmmFabricApi](../../fabric/src/main/java/cc/ecisr/mmm/api/fabric/MmmFabricApi.java) 提供同步单格查询和容器提交完成事件。查询读取已加载区域；事件在物品强制保存且事务日志删除后发布。参数、单位、线程和失败状态见 [外部 API](external-api.md)，界面与绘制入口见 [客户端路由](../maintenance/routing.md#client)。
