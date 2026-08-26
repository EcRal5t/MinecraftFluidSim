# 剩余工作实施总计划

## 文档定位

本目录中的计划用于把当前数值与事务基座推进成可正常游玩的产品，并为后续 AI 提供可直接执行的工作分解。它们不是当前行为的规范来源；开始任何工作前仍应先读：

- [`../../architecture/system-overview.md`](../../architecture/system-overview.md)
- 对应领域的 [`../../architecture/`](../../architecture/) 文档
- [`../../maintenance/routing.md`](../../maintenance/routing.md)
- [`../../roadmap/product-roadmap.md`](../../roadmap/product-roadmap.md)

计划只描述尚未闭合的工作。实现完成后，把最终数据结构、公式、提交顺序和失败语义写入 architecture，更新 routing，然后从产品路线中删除已完成项。不要把实现过程中的临时类型、过渡格式或探索失败写入稳定文档。

项目明确不承担已有世界或区域载荷的迁移。这个条件允许直接替换配置、协议和存档形状，但仍不允许自动删除、覆盖或“修复”无法解释的数据；未知数据继续原样保留并 LOCKED。

## 当前基座与真正缺口

以下能力已经存在，不应重做：

- 控制器、边界方块、配方、创造栏入口、右击激活与自动 tick；
- CPU 权威 PIC/FLIP、多材料、独立质量、化学、颗粒、密闭气体、总内能和相变；
- 双槽 region generation、HEAD 最后发布、ownership 与容器 WAL；
- loader-neutral `api`/`core` 与 Fabric adapter 分层；
- 同步 Query、Jade 闭壳观察、durable container committed event；
- immutable `FluidDomainState` / step request / complete candidate 后端边界；
- 实体运动向 marker 写入动量的有界 moving-body 路径；
- 压力相关固液平衡曲线、独立 gas component kg/species/U；
- 有限热设备的 core 状态、单区域交换规划和 region HEAD 持久真值。

剩余工作集中在“世界端产品闭合”：默认配置可理解且跨重启稳定、无需管理命令的完整游戏循环、真实世界行为与外部 Mod 写入端口、实体和跨相物理闭合、渲染质量、量化性能以及生产 GPU 后端。

## 不得跨越的共同边界

所有工作包都必须保持：

1. marker 体积、逐材料质量、species、solid SVQ/质量/组成、gas component 库存与各载体总 `U` 相互独立；派生场不得反向结算库存。
2. 世界、actor 和 region 是不同持久域；跨域修改必须有可恢复计划和唯一 commit point。
3. Minecraft/Fabric 只负责发现、快照和提交，不能把 `Level`、`Entity`、`ItemStack`、NBT 或 loader callback 放入 core。
4. GPU、worker、renderer、Query 和兼容层没有世界写入、WAL 删除或 HEAD 发布权。
5. 无法唯一解释时 LOCKED；候选物理状态越出有效域、容量或预算时 PAUSED。不能钳制、猜测或删除库存。
6. 任何新近似都写明空间/时间尺度、误差观测和停止门。不得用材料 ID 特判或经验速度修正掩盖异常。
7. 功能关闭必须停止入口，不得让同一存档在重启后被另一种载荷布局重新解释。

## 推荐推进顺序

### 工作线 A：可玩生命周期与世界配置

先执行 [`playable-lifecycle-plan.md`](playable-lifecycle-plan.md)。这是其他工作的入口门，因为当前功能门可在已有区域后被修改，重启时会让区域因布局不匹配锁定。目标是把功能能力变成世界首次初始化时冻结的 profile，把运行预算和玩家偏好与载荷布局分开，并提供无需命令的创建、检查、暂停、删除和错误反馈。

关闭条件：新建世界使用默认设置即可创建包含目标能力的区域、退出再进入、继续运行；普通玩家不需要 `/mmm thermal` 一类命令才能获得完整载荷；改变运行偏好不会改变 checkpoint 解释方式。

### 工作线 B：世界行为、受控端口与外部 Mod

随后执行 [`world-interaction-and-integration-plan.md`](world-interaction-and-integration-plan.md)。先交付 loader-neutral 只读/写入端口和一个内部 Fabric 消费者，再逐项接入原版行为。不要同时实现灭火、湿润、岩浆、红石、生物和寻路；每个行为单独提交、单独守恒、单独 feature policy。

关闭条件：至少一个外部 provider 可通过稳定 actor identity 完成 crash-safe transfer；Jade 等观察层只用 Query；至少一个原版行为以实际库存/能量驱动，而不是检查方块外观。

### 工作线 C：物理完整性

按 [`physics-completion-plan.md`](physics-completion-plan.md) 分开推进密度验收、实体双向边界、压力相变校验、有限热设备世界接线和气液 species 交换。这些是独立增量，不能合成一次大重写。优先完成会直接改善玩家体验且不引入新状态模型的密度 fixture 和实体排水；之后接换热器和跨相 species。

关闭条件：每个增量都有守恒账本、有效域、预算和自动 fixture；视觉效果不能作为唯一通过条件。

### 工作线 D：渲染、性能与生产 GPU

最后按 [`rendering-performance-and-gpu-plan.md`](rendering-performance-and-gpu-plan.md) 推进。渲染缺陷与物理输运缺陷分别定位；profile 先分离 solver、世界交互、网络和 readback，再决定 GPU hot path。生产 GPU 必须实现完整后端候选，不能扩张 GPU probe 的只读捷径。

关闭条件：代表性区域可稳定游玩；客户端无已知系统性洞面/黑三角；GPU 设备丢失只会暂停或回到 CPU 可解释状态，不会产生半步库存。

## 每个增量的固定执行模板

其他 AI 开始一个增量时，按以下次序执行：

1. 从 routing 选首要代码入口和唯一最近验证 selector。
2. 写下本次涉及的权威真值、派生字段、commit point、失败分类和硬预算。
3. 先建立 loader-neutral request/candidate/settlement，再接 Fabric 世界读取与提交。
4. 复用现有 `FluidRegionOperations`、container coordinator、ownership transaction、Query 和 backend candidate；不得旁建第二事务路径。
5. 若新增持久字段，writer/reader/validator/账本/故障注入同时修改，不提供缺字段默认值。
6. 优先替换同一不变量的已有覆盖；只有无法归属的新失败模式才新增常态测试。
7. 运行 routing 指定的最近门。只有真实 `VoxelShape`、跨重启世界事务或视觉无法自动观察时才请求单一游戏场景。
8. 提交一个可回退、独立闭合的 Git commit；不要把格式整理、资源重做和另一物理领域混入。
9. 更新 architecture/routing/roadmap，只记录最终稳定状态。

## 推荐提交切片

以下切片可以跨多轮执行，但每片必须自己闭合：

1. 冻结 world capability profile，移除会改变已有区域布局的运行期 toggle。
2. 新世界完整默认 profile、首次初始化反馈和跨重启 fixture。
3. 控制器状态/错误 UI 与创造模式材料工具，不依赖管理员命令。
4. 公共 transfer request/plan/commit 契约和 provider actor identity。
5. 一个内部 Fabric adapter 走完整公共端口，证明端口不是旁路。
6. 灭火或湿润中的一个守恒世界行为。
7. 密度/分层/自然对流 fixture 与诊断，修复由 fixture 暴露的问题。
8. 实体占位和 pressure boundary，再加入严格动量 ledger。
9. 有限热设备 enrollment、接触几何、生产调度和可放置方块。
10. 气液 species 两相联合 candidate 与一个 CO2 fixture。
11. mesh 拓扑、法线和 snapshot/readback profile 分别闭合。
12. 完整 GPU backend、CPU 对照、超时和设备丢失恢复。

不要用一个“全面重构”提交覆盖这些切片。若某文件出现第二职责，先提取纯数据/规划器或 adapter，再做功能更改。

## 产品完成判定

“创造模式可以自由体验”至少意味着：

- 新世界无需修改配置或执行管理员命令即可创建并运行完整能力区域；
- 玩家可从创造栏取得边界、控制器、材料/容器和热设备，且有本地化反馈；
- 放置、装取、暂停、重启、边界暂时不可用和再次加载都有确定行为；
- 实体进入液体会排水、扰动液面并获得由相同边界求得的反作用；
- 温度、组分、气体和错误状态可由 Query/Jade 或控制器界面观察；
- 代表性规模不会持续越过 pacing/暂停预算；
- 所有跨世界/物品/外部 Mod 的修改均可在崩溃后唯一收敛；
- 客户端展示不制造、隐藏或修复服务端库存。

GPU 后端不是小型创造区域可玩的绝对前置，但它是脱离原型和扩大规模的明确完成项。CPU 规范实现、存档和事务边界在 GPU 交付后仍保留。
