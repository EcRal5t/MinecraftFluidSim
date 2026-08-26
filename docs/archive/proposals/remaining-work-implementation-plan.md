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

## 计划审计结论

现有四条工作线覆盖了主要技术领域，但原先还不足以直接推出“创造模式完整体验”，原因不是缺少更多底层名词，而是缺少以下闭环：

- 没有把“完整体验”冻结成逐场景验收表，容易把“类和测试存在”误当成玩家能够取得、操作、观察并重启恢复；
- 创造栏当前只暴露边界、控制器、固定温度源和指示剂工具，材料桶、试剂、颗粒、气体与热设备的正式取得路径没有逐项定义；
- world profile 与四份 registry 仍被描述成先后独立发布，没有一个同时绑定 profile、全部 fingerprint 和唯一 layout 的原子 commit point；
- controller summary、单格 probe、危险删除确认、温度源设定和错误分类只写了目标，没有确定交互路由与只读/写入边界；
- “自然对流”和实体黏性力依赖液体黏度，但当前材料真值没有统一的动态黏度定义；如果不先补齐物性和动量扩散，验收只能观察数值耗散而不能声称物理闭合；
- 气液 species 虽有算法计划，却没有 CO2 等气相库存的正常创造入口、观察入口和 exact gas-side settlement，玩家无法建立可验证场景；
- 渲染计划没有明确替换 marker 强制增厚、interface inset 等展示修补，仍可能用可见性掩盖服务端/协议缺口；
- 外部 transfer 的首个公开范围把 liquid、gas、granular、coherent 一次并列，超过现有 typed container/WAL 基座，容易形成名义通用、实际旁路的 API。

本轮计划把这些缺口改为有依赖、有代码入口、有最近 selector、有提交边界和玩家证据的工作包。没有写入计划的功能不属于“顺便实现”；发现新的权威真值或跨域提交时，先回到本总计划登记依赖。

## 里程碑与范围

不要把“创造模式可完整体验”和“所有长期路线完成”混成一个关闭条件：

| 里程碑 | 内容 | 必须完成 | 明确不阻塞 |
|---|---|---|---|
| P0 安全启动 | 世界能力与 registry bundle 一次冻结，旧/未知载荷 fail-closed | A01–A03、A05；A04只是入口索引 | 新原版行为、GPU |
| P1 创造模式完整体验 | 可发现、可建立、可投料/装取、可观察、可暂停/拆除、可跨重启；现有材料、化学、颗粒、气体、热学、相变和实体边界都有正常场景 | A06–A12、B00、B08、C01–C05、D01–D05；D05不达门时追加D06 | 公共外部 provider、天然水体、生产 GPU |
| P2 世界与生态集成 | 公共 liquid/granular transfer、reference provider、至少一个守恒原版行为 | B01–B07 | 全部原版行为一次交付、其他 loader、public gas ABI |
| P3 规模与后端 | M/L与多region profile、CPU优化；证据证明必要后再交付完整GPU backend | D06–D10，并复用D04 harness | 海洋/无界洞穴 |

工作包 ID 由主题计划定义。后续提交、测试输出和文档关闭说明都引用这些 ID，避免“继续完善”这种无法判断完成的描述。

带连字符的名字是同一工作包的强制内部切片，不是新里程碑：A01只有`A01-schema`与`A01-store`都完成才可关闭；C01/C05的registry定义属于A01-schema并必须先于首次发布，`C01-solver`/`C05-solver`随后只消费冻结定义。不得以“以后做solver”为由发布缺少最终默认definition的bundle。

## 创造模式场景矩阵

P1必须保证下表每个场景都能从全新默认world、无需`/mmm`到达。自动fixture可为隔离失败使用不同临时world，不要求玩家在一次人工运行中依次表演全部场景；命令fixture可继续用于开发，但不能作为产品入口或单独通过证据。

| 场景 | 玩家入口 | 预期物理/事务 | 必须可观察的证据 | 主要工作包 |
|---|---|---|---|---|
| CX-01 首次世界与重进 | 正常进入世界 | profile + registry bundle 原子发布；能力全开；无 region 时不创建库存 | controller console 显示 profile 已冻结及 aggregate fingerprint 短摘要 | A01–A03、A05；A04仅索引 |
| CX-02 建壳与生命周期 | 创造栏取得 boundary/controller，围成闭壳并空手激活 | 首个 HEAD 成功后才宣称创建；自动运行；潜行空手暂停/继续 | 明确显示 operator intent 与 availability；退出重进后自动继续 | A06、A09、A11 |
| CX-03 水与部分容器 | 原版水桶、空桶、材料容器 | 实际 nanos、kg、可选 `U` 同 WAL 提交；创造放出记 source，装取记真实 sink/transfer | 手中 stack 与 controller inventory 增减一致 | A07、现有 container coordinator |
| CX-04 多材料 | 正式 water/oil/mercury source 与空材料桶 | 密度分层、互溶/不可互溶和共享 pressure 走同一材料真值 | probe 显示 stable material、nanos/kg；客户端显示外表面与单一液液界面 | A07、C01、D01–D03 |
| CX-05 化学与指示剂 | HCl、NaOH 等正式 reagent source、indicator/eraser | exact species、组成、电荷、质量 residual 与反应热闭合 | probe/Jade 显示 Top-N/pH/温度；混合后反应物和产物变化可见 | A07、A08 |
| CX-06 颗粒与沉淀 | fine sand、light beads source；饱和 Na/Cl 试剂 | active/deposit、排水、沉浮、安息角；沉淀联合 chemistry/SVQ/U | probe 显示 active/deposit/SVQ；可见沉积而非只出粒子 | A07、C01 |
| CX-07 密闭气体 | 留出 headspace，继续注液或使用正式 gas source/vent | sealed component 库存、pressure、U 与拓扑 lineage 闭合 | probe 显示 ambient/sealed、component、Pa/K；压缩时液面响应 | A08、B08、C05 |
| CX-08 导热与相变 | 可设定固定温度 boundary；水与冷/热面接触 | 有限体积热通量、冰/水与水/蒸气 exact batch、pending work exactly-once | 温度、相库存、gas vapor 与暂停原因可见 | A08、C03 |
| CX-09 有限换热器 | 创造栏取得独立 finite exchanger，先于或在 region 中 enrollment | body kg/`U` 只在 HEAD；两流体零物质交换、能量镜像 | UI 显示 body K/`U`、端口和 exchange；重进不重置温度 | A07、C04 |
| CX-10 实体边界 | 玩家或有定义质量的测试实体进入有 headspace 的池 | moving occupancy 排开等量液体；pressure impulse 与实体反作用闭合 | 液面排水、实体浮力、per-body ledger 摘要 | C02、A08 |
| CX-11 边界故障与恢复 | required chunk 卸载后再加载；自动fixture另覆盖第三方边界破坏 | availability pause 不覆盖 operator intent；不半步、不申请永久 ticket | console 给出结构化原因；恢复后按原意图继续 | A06、A09 |
| CX-12 清空与拆除 | controller console 的确认操作 | `empty` 联合凝聚相并记 sink、不清 gas；sealed gas须先经`vent gas`实际结算，remove/release遵守gas/WAL/ownership/phase-work门 | 操作前预览实际影响；有sealed gas时remove明确拒绝；vent后才允许最终tombstone | A10、A11 |

P1在自动门仍无法观察真实接线/视觉时只允许两项人工证据：A12-TX跨重启事务和RV-01同壳自由面/斜cut-cell/液液界面视觉场景，分别申请。P2在同样条件下可增加TX-01 reference provider跨重启insert/extract、VI-01一个守恒灭火场景。P3增加自动PF-01代表性CPU/GPU profile。每个主题计划给出详细证据。

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

## 工作包依赖图

实施者只选所有前置已关闭的一个横切片。表中的“同提交禁止”表示共享上下文不等于应该一起修改：

| 工作包 | 直接前置 | 产出 | 同提交禁止 |
|---|---|---|---|
| A01-schema 默认registry形状与数据 | 当前registry codecs、C01-registry黏度定义、C05-registry CO2/component/pair定义 | P1全部默认定义的strict codec与canonical fingerprint | solver、controller UI |
| A01-store profile/registry bundle | A01-schema、region HEAD模式 | 单一world capability commit point；首次发布后不追加definition | controller UI、GPU |
| A02/A03 boot与policy | A01-store | READY/LOCKED/POLICY状态和只读capability命令 | content资源重做 |
| A05 controller创建 | A02/A03 | 首个完整HEAD后才宣称创建 | public transfer ABI |
| A06 controller恢复/availability | A05 | 重进、chunk、边界与operator intent闭环 | content资源重做 |
| A07 creative source与目录 | A05、现有container primitive | 无命令正式投料/装取 | 新物理算法 |
| A08 field probe | A02、现有Query | 无Jade只读观察闭环 | destructive coordinator |
| A09 controller console | A06、A08 | 有界summary、权限与stale action门 | destructive coordinator |
| A10 lifecycle operations | A06/A09、B00、ownership/container WAL | empty/vent/release/remove预览与提交 | renderer |
| B00 跨域恢复代数 | 现有container/ownership recovery state | JDK-only BEFORE/AFTER/THIRD/UNAVAILABLE判定器与矩阵生成 | public API、fire资源 |
| B01/B02 public transfer shape/discovery | A02、现有typed truth | loader-neutral request/plan/result | provider commit |
| B03/B04 coordinator/recovery | B00、B01/B02 | 单region public crash-safe transfer | reference block资源 |
| B05 reference provider | B03/B04 | 跨重启真实consumer | fire |
| B06/B07 world action/fire | A02、B00、C03、现有ownership模式 | 首个守恒原版行为 | 其他原版行为 |
| C01-solver viscosity/density/convection | A01-store中已冻结的material snapshot | 统一物性、黏性与分层门 | entity/world adapter |
| C03 pressure phase | A06、现有thermal/phase | pressure provenance、相变门 | exchanger |
| C02 moving solid | C01-solver、C03 projection边界 | 排水、pressure/viscous双向冲量 | GPU |
| C04 finite exchanger | A07/A09、B00、现有finite body core | enrollment/contact/tick/UI | multi-region device |
| B08 gas creative port | A07/A08、现有gas inventory | 可建立sealed component场景 | public gas ABI |
| B09–B12 后续世界消费者/事件 | B03/B06与相应真实consumer | finite wetting、高温、只读原版consumer、逐项event | 一次实现全部原版行为 |
| C05-solver gas-liquid species | B08、C03、A01-store中已冻结的CO2定义 | CO2联合candidate与玩家场景 | 弱酸/动力学 |
| D01–D03 rendering | A02 protocol、现有PLIC/material state | 无增厚的surface payload/mesh/frame | solver physics修补 |
| D04 profile harness | 完整`FluidDomainState`后端边界 | 可重放runner、固定采样协议与host identity | hot-path优化 |
| D05 P1 CPU门 | A07/A08、C01–C05、D01–D04的fixture state | S单region与四region aggregate报告 | GPU kernel |
| D06 CPU优化 | D05或P3 M报告暴露的具体hot path | 同input digest不变的证据驱动优化 | GPU kernel |
| D07–D10 production GPU | C01–C05、D04/D05 profile、完整backend state | 完整candidate、observation/full readback与故障恢复 | 世界/HEAD权限 |

允许的并行只限互不修改同一职责的工作树；例如A08 summary值对象与C01纯Java viscosity值对象可独立开发，但在各自门通过前不合成一个提交。一个work package暴露未登记的新前置时，先更新本表和对应主题计划。

## 可执行切片说明模板

每次交给另一个AI的任务必须从主题计划复制并填写以下八项；缺一项不开始改代码：

```text
Work package: A01 / B03 / C02 ...
Goal: 本提交关闭的一个可观察失败
Authoritative truth: 会读写的库存/identity/ledger
Derived state: 可重建且绝不反向结算的字段
Commit point: HEAD / WAL+HEAD / 只读无提交
Failure: LOCKED / PAUSED / REJECTED / UNAVAILABLE 的触发条件
Hard budget: count/bytes/substeps/scan/body等上限
Entry + selector: routing中的首要入口与唯一最近门
```

完成提交时在commit message或交接中报告actual selector、是否改持久shape、是否需要A12-TX或RV-01人工证据。不要写“全部测试通过”却不列selector。

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

1. A01-schema先冻结P1所需的全部registry形状和默认数据，包括C01-registry黏度与C05-registry CO2/component/pair；再由A01-store/A02/A03发布bundle、移除改变layout的toggle并收缩policy。这里不实现C01/C05 solver。
2. A05–A06：controller创建、availability、自动恢复和跨重启fixture。
3. A07–A09/A11：正式creative source/container、probe/console、温度设定、quick start和资源。
4. B00：从现有container/ownership recovery提取JDK-only共同判定器和笛卡尔故障矩阵，不实现public API或fire。
5. A10：复用B00完成empty/vent/release/REMOVED HEAD与quarantine收敛。
6. C01-solver：消费已冻结黏度定义，完成动量扩散、分层/自然对流fixture与诊断，修复fixture暴露的问题。
7. C03→C02：pressure provenance/phase门，再做moving occupancy、surface pressure和联合body-pressure求解。
8. C04：复用B00完成有限热设备enrollment、接触几何、生产调度和可放置方块。
9. B08→C05-solver：gas创造端口消费已冻结gas component；随后完成gas-liquid species联合candidate和CO2 fixture，不在已发布world追加registry定义。
10. D01–D06：surface payload/label-union mesh/frame，再做S/M/S4-mixed profile；只在D05失败时把对应D06纳入P1。
11. A12 restart harness、必要时分别执行A12-TX/RV-01，关闭P1。
12. B01–B04：公共liquid/granular transfer request/plan/commit与provider actor recovery。
13. B05：reference provider；再让一个现有内部container使用共享planner。
14. B06/B07：单个守恒fire world action，不同时接湿润/岩浆；关闭P2最低范围。
15. B09–B12按真实消费者分别推进；不阻塞已定义的P2最低范围。
16. D07–D10：完整GPU backend、CPU对照、timeout/observation/full readback/device-lost恢复。

不要用一个“全面重构”提交覆盖这些切片。若某文件出现第二职责，先提取纯数据/规划器或 adapter，再做功能更改。

## 产品完成判定

P1 的关闭证据是：A01–A03、A05–A12、B00、C01–C05、B08、D01–D05 的相应前置均完成；A04只是维护入口索引。自动覆盖必须先通过；若真实UI/跨重启接线或视觉仍无法自动观察，人工证据才按A12-TX和RV-01分别执行两个单一场景，不能合并成一座难以定位失败的全功能实验室。D05不达门时，实际需要的D06切片也成为P1前置。某个底层类存在、命令fixture可运行或截图“看起来像”都不能单独关闭场景。

“创造模式可以自由体验”至少意味着：

- 新世界无需修改配置或执行管理员命令即可创建并运行完整能力区域；
- 玩家可从创造栏取得边界、控制器、材料/容器和热设备，且有本地化反馈；
- 放置、装取、暂停、重启、边界暂时不可用和再次加载都有确定行为；
- 实体进入液体会排水、扰动液面并获得由相同边界求得的反作用；
- 温度、组分、气体和错误状态可由 Query/Jade 或控制器界面观察；
- 代表性规模不会持续越过 pacing/暂停预算；
- 所有跨世界/物品/外部 Mod 的修改均可在崩溃后唯一收敛；
- 客户端展示不制造、隐藏或修复服务端库存。

还必须逐行关闭CX-01至CX-12；若一个场景因明确产品范围暂不实现，应从默认创造目录和宣传中移除，并在本计划重新定义P1，而不是保留一个不可达按钮。P2/P3可以在P1之后继续，但不得改变profile/HEAD/独立库存语义。

GPU 后端不是小型创造区域可玩的绝对前置，但它是脱离原型和扩大规模的明确完成项。CPU 规范实现、存档和事务边界在 GPU 交付后仍保留。
