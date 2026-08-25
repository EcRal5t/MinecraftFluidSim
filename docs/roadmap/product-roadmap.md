# Real Water 产品路线

本文件定义产品要走向哪里、阶段依赖和完成条件。它不重复当前 codec 或公式；已生效的组件、真值和 logical-step 数据流见 [`../architecture/system-overview.md`](../architecture/system-overview.md)，主题细节见 [`../architecture/`](../README.md)，代码与最近验证入口见 [`../maintenance/routing.md`](../maintenance/routing.md)。

## 长期产品目标

1. 一个方块空间可包含任意体积、组分和凝聚/气相库存，不受原版“一格一种流体”限制。
2. 可互溶液体连续混合；不可互溶液体共享机械压力但保留界面并按物性分层。
3. 开放空间使用有界的环境大气语义；密闭空间保存有限气体库存、温度和压力，小孔以有限速率连通。
4. 材料、species、phase、reaction 和热物性数据驱动；除显式 source/sink 外，质量、体积、组成、电荷和能量可审计闭合。
5. 颗粒覆盖沉淀、细沙等宏观沉降/沉积，不承诺通用刚体、弹性固体或无依据的全套 MPM。
6. 先把有界 controller 做成可靠、可恢复、可观察、可与其他 Mod 组合的工业流体系统，再评审天然水体和开放世界 ownership。

近期不承诺：逐格求解所有开放空气、逐分子/离子粒子、弱酸/活度/完整动力学平衡、通用刚体或弹塑性体、无界洞穴 flood fill、海洋尺度 20 Hz、首版生产 GPU 后端。

## 当前产品基座

当前生产边界是最多 4 个显式闭壳 controller region，CPU `FlipFluidDomain` 为权威核心。已经具备：连续体积 PIC/FLIP、可变密度材料、液气/液液界面与润湿、密闭气腔、world ownership、玩家/发射器容器事务、守恒化学、active/deposit 颗粒、总内能、导热、反应热以及固液/气液相变。

这一基座的限制也必须如实保留：

- 不可互溶相目前共享局部速度场，不声称解析切向滑移、乳化和液滴破碎。
- 实体耦合目前主要是流体对实体的单向采样，不是完整双向动量/排水求解。
- 固液平衡温度还没有数据化压力曲线；气液已经使用绝对压力和 component 分压。
- 客户端只显示服务端快照的重建结果；尚无稳定的外部局部查询/转移 API。
- section 是一个 region 原子 generation 内的存储分片，不具有独立提交、卸载或 ownership 生命周期。
- controller 外天然洞穴、河流、海洋和世界生成水不在当前 ownership 范围。

## 长期不变量

未来工作仍必须维持六层分离：

| 层 | 允许持有 | 禁止行为 |
|---|---|---|
| 物理真值 | 体积、逐材料质量、species/电荷、solid SVQ/组成、gas inventory、各载体 `U` | 从温度、颜色、density 或 mesh 反算库存 |
| 数值离散 | MAC 速度、压力、PLIC/cut fraction、曲率、LTE grid | 把平滑场变成持久 source/sink |
| 渲染重建 | palette、外表面、液液界面、法线、颜色 | 客户端修改服务端状态或掩盖输运错误 |
| 调度/后端 | ACTIVE/REDUCED/SLEEP/UNLOADED、CPU/GPU 选择 | 因降频、休眠或后端切换改变守恒结果 |
| 世界事务 | ownership、container/port transfer、原版交互 | 在可恢复 WAL/HEAD 之前修改多个持久域 |
| 协议/存档 | stable ID、generation、capability、checksum | 写 runtime handle/GPU buffer，或猜测未知布局 |

所有新入口还必须显式声明 BV/nanos/SVQ/AU/kg/K/EU 等单位、server-thread 所有权、all-or-nothing/partial 语义、source/sink 分类、预算和失败状态。

## 推进次序

| 顺序 | 产品阶段 | 前置依赖 | 完成时必须证明 |
|---|---|---|---|
| 1 | 只读观察与外部 Mod API | 当前 controller/registry/checkpoint | 查询不加载、不唤醒、不回写；stable ID/单位/线程/失败语义冻结 |
| 2 | 原版行为与受控转移 API | 查询 API、现有 WAL | 灭火/湿润/岩浆等逐项守恒，外部 actor 可 crash-safe 收敛 |
| 3 | 物理完整性增量 | 可观察 fixture | 压力相关固液、双向实体等分别有数据域与守恒门，不互相绑架发布 |
| 4 | section 生命周期与显式端口 | profile、事务与网络 API | 跨 section/region 仍只有完整代次，不依赖永久 chunk ticket |
| 5 | 有界范围扩张 | 高休眠率、恢复/性能基准 | 天然小水体在硬预算内可接管、释放和异常恢复 |
| 6 | 大尺度世界评审 | 前述全部量化 | 河流/海洋方案有成本模型、持久模型和明确停止门后才可开工 |

阶段编号只表达依赖，不绑定发布版本；各阶段应拆成 routing 可选择的小增量。

## 下一阶段：原版行为与外部 Mod API

先冻结 API 的单位、事务、线程和失败语义，再按小增量实现：

1. 只读查询：dimension + region + world/cell position → 有效性、材料、体积分数、派生温度和相态。
2. 观察适配：为 Jade/WAILA/HWYLA 提供接口；查询不得唤醒、加载或回写区域，主 Mod 不增加准星 HUD。
3. 受控转移 API：stable material/species ID、纳体积/SVQ、质量和总内能必须显式，复用现有区域/容器事务。
4. 事件 API：checkpoint、ownership、phase transition 和安全暂停只发布已提交事件，不暴露半代次。
5. 原版交互：灭火、湿润、岩浆反应、红石、生物和寻路逐项定义并验收，不以“一格水”假设直接映射连续库存。

该阶段不扩大开放世界 ownership，也不允许外部 Mod 直接修改平滑 density、pressure、runtime handle 或客户端 mesh。

### API 形状

只读查询的最小结果应包含：region identity、state generation、world/cell 位置是否有效、总液相体积、规范材料/phase 分数、可选 species 摘要、派生温度/相态以及“为什么不可查询”的结构化状态。pressure 与内部调试字段不默认承诺为稳定 API。

受控转移采用显式 request/plan/commit：调用者给出 dimension、实际 world target、stable resource identity、typed amount、可选质量/组成/能量和 actor transaction identity；主 Mod 返回实际可转量或完整拒绝。API 不能让调用者持有 live domain 引用，也不能跨 tick 重用未绑定 generation 的 plan。

事件只在 HEAD 或同等外部 commit point 后发布，并携带 region generation 与 transaction ID。监听者错过事件时应能通过查询恢复当前状态，事件流本身不是库存日志。

### 原版行为拆分

- 灭火：先定义每类火焰需要的接触、最低液量、温度/相态和产生的蒸气/能量去向。
- 湿润/冲刷：定义方块或实体吸收的实际 nanos、可接受材料和返回 source/sink；不能只检查“cell 看起来湿”。
- 岩浆/高温方块：用材料、热通量和 reaction/phase 事务表达产物，避免在 world event 中直接删一格水、放一格石头。
- 红石、生物、寻路：使用稳定查询快照和阈值语义，不给 AI 或 comparator 暴露 live solver 数组。

每项行为都有独立 feature gate、数据资源、本地化失败反馈和最近门；不要求把所有原版互动绑成一次发布。

### 阶段关闭条件

- API 文档与 codec/ABI fixture 覆盖 stable ID、单位、线程、generation 和错误分类。
- Jade/WAILA/HWYLA 适配可在不唤醒区域的情况下显示局部派生温度；主 Mod 不增加准星 HUD。
- 至少一个只读外部适配和一个 crash-safe 外部转移适配通过重启恢复。
- 原版行为逐项有库存/能量账本和实际 world fixture，不以视觉结果代替守恒。

## 热力学后续

### 压力相关固液平衡

当前气液相变已经使用绝对压力和 water-vapor 分压；固液相变只把绝对压力作为有效域安全门，平衡温度仍为常量。

后续可将 solid-liquid transition 扩展为 registry 数据化的 `T_eq(p)`：

- 先支持单调分段曲线或经过验证的 Clapeyron 关系；
- pressure 来自同一 logical step 的绝对压力场；
- transition fingerprint 进入 world registry identity；
- 相变规划同时闭合 latent heat、质量、SVQ、体积功和组成；
- 无曲线的定义保持常温常压模型，不引入材料特判。

这项扩展不需要重构权威内能或相变提交顺序，但必须新增物性有效域和压力曲线量化 fixture。

pressure 必须取同一 logical state 的绝对压力：液体机械 pressure 加对应 gas/ambient 基准。相变候选发布前绑定该 pressure generation；不能从 `status` 文本或上一 tick 网格读取。第一增量只接受单调分段数据或经物性来源验证的 Clapeyron 参数，并在三相点/曲线外明确拒绝，而不是外推到所有压力。

### 更完整的化学热力学

弱酸、缓冲液、活度、有限速率动力学、可逆平衡和气相化学暂不承诺。若进入实现，先冻结 solver 规模、exact inventory 与近似的适用范围，不能逐分子粒子化。

优先顺序应是：先扩展数据定义与单 cell 离线平衡器，再接入同 chemical phase 的 bounded dirty-cell solver，最后才考虑跨相质量传递。任何 activity/kinetics 近似都需要声明浓度、温度、压力有效域和 stiffness 处理；预算耗尽应延期或暂停，不能截断 species。

## 实体双向耦合

目标不是立即引入通用刚体求解器，而是把当前单向效果逐步变成可审计的双向边界：

1. 在实体离散表面采样流体压力，验证静水浮力与姿态无关性。
2. 将黏性和形状阻力与压力力分开，并给出适用 Reynolds 范围。
3. 把实体表面速度作为移动 solid boundary 接入压力 projection。
4. 将压力/阻力反作用冲量回写流体，独立 boundary/entity ledger 闭合。
5. 实体占据体积进入统一容量并挤开等量液体；空间不足时显式失败或产生已定义的外排路径。

每一步都应能在关闭后退化为当前行为。首版可使用有界表面采样，不要求联合求解 Minecraft 刚体约束。

## 性能与后端评审

1. 建立代表性 controller profile，分别量化 solver、世界交互、网络快照和 readback。
2. 占比未知前不修改 40 ms pacing 与连续 3 步 200 ms 暂停阈值。
3. 只有 profile 证明 CPU 路径达到明确停止门后，才评审生产 GPU backend；CPU 继续作为规范和恢复后端。
4. 活动成本目标随扰动表面积/活动 section 增长；代表性静态域至少 80% 体积可守恒休眠后才扩大范围。

详细停止门见 [`performance-and-backends.md`](performance-and-backends.md)。

后端抽象只有在 profile 确认边界后才提取。数值 worker/GPU 只消费 immutable step input，在 tick boundary 返回完整 candidate；世界读写、ownership、actor 和 HEAD 发布留在 server thread。超时保留上一完整 state，不能展示或持久化半步。

若阶段性能不达标，依次采用：缩小 active neighborhood、降低更新频率、增加守恒休眠/宏观 node、限制产品范围，最后才降低空间分辨率。不得先放松 ledger 或使用材料特判跳过昂贵物理。

## 视觉与界面质量

- 为开放域和密闭域分别建立最小黑色三角面 fixture。
- 分开评估 mesh 拓扑缺陷、液液物理锐化和逐顶点法线平滑。
- 不得用 renderer 增厚、强制找平或隐藏三角形掩盖 marker、相输运或 snapshot 问题。

外表面与液液内部界面必须分别重建；不可互溶 pair 的共享面只生成一次并有稳定归属/法向。未来液液锐化属于物理输运，逐顶点法线属于展示，两者需要不同 fixture 和开关。颜色、指示剂与温度映射只读，不成为混合比例或能量真值。

任何正式 gameplay 方块/item/API 入口都需同时提供模型/贴图、取得方式或配方、创造标签、`zh_cn`/`en_us`、tooltip、失败反馈及必要声音/粒子；缺失资源意味着功能仍是开发入口。

## 世界范围

天然洞穴、河流、海洋和开放世界接管仍在范围外。进入评审前必须满足：

- 有界 controller 的 ownership、checkpoint 和容器恢复在异常退出下稳定；
- 活动 section、气腔搜索和 chunk 生命周期有明确硬预算；
- 原版/其他 Mod 流体交互具有显式 source/sink 和冲突语义；
- 不依赖永久 chunk ticket 或无界空气 flood fill。

### section 与端口的前置架构

当前 section 只是 region checkpoint 内的切片。若要独立休眠/加载，必须新冻结 manifest/commit 模型，至少回答：

- 跨 section marker、gas edge、deposit 和 thermal flux 属于哪个完整 generation；
- 一片缺失时整个 region 停止，还是能证明边界通量为零后局部推进；
- ownership 与多个 section HEAD 如何共同提交；
- network snapshot 如何拒绝迟到 section 包复活旧状态；
- CPU 与可选 backend 如何从同一持久 truth 重建，而不保存 GPU buffer。

区域之间默认不直接连通。未来质量/动量/能量交换只通过显式端口或另行评审的相邻合并协议，并复用 typed transfer 与 WAL。默认不申请永久 chunk ticket；若提供管理员常载工业域，必须限制数量、尺寸、持续时间并可确认释放。

### 范围扩张次序

1. controller 内显式端口和 section 休眠。
2. 有硬边界/预算的小型原版池体接管与释放。
3. 邻近河段的有限 ownership、流入流出和 chunk 生命周期。
4. 只有上述事务、休眠率和性能门稳定后，才单独评审海洋与世界生成水。

天然空气 cavity 搜索默认仍需严格 BV/tick/region 总预算；触达未加载边界、已知环境连接或预算上限时分类为 ambient 并停止，不能为了证明“密闭”扫描整片洞穴。

## 模型停止条件

- 颗粒继续使用 active parcel + deposit cell；只有量化证明它无法跨过既定质量/成本门，才评审 MPM 或其他重模型。
- 若扩大范围失败，依次缩小活动区、降低更新率、采用更稀疏的宏观状态，最后才降低空间分辨率。
- 不先牺牲守恒，也不以经验特判或渲染修补替代物理机制。

## 验证与文档关闭规则

日常小增量只跑 [`../maintenance/routing.md`](../maintenance/routing.md) 指定的最近门；产品阶段关闭或发布才按 [`acceptance-and-release.md`](acceptance-and-release.md) 汇总受影响层。自动门不能观察的视觉或真实 world 行为，才请求一次单场景实机验证。

阶段实现后，把仍有约束力的数据结构、公式、提交顺序、预算和失败语义移入对应 architecture；roadmap 只保留尚未完成的目标、依赖与停止门。历史发布事实进入 [`../progress/version-history.md`](../progress/version-history.md)，不让历史叙述重新成为当前规范。
