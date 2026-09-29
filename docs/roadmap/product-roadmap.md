# Matter, Motion & Mechanics 产品路线

本文记录尚未完成的功能、实现顺序和完成条件。当前实现见 [系统总览](../architecture/system-overview.md)，具体需求的代码入口从 [项目 README](../../README.md)选择。

路线基线：`0dffd5a`（2026-09-24）。`playable-lifecycle`、`world-interaction-and-integration`、`physics-completion`、`rendering-performance-and-gpu` 四份专题计划和 `remaining-work-implementation-plan` 总计划都已移入 [归档提案](../archive/proposals/)。归档只作为验收标准、反例、故障注入矩阵和已知失败模式的来源，实现一律按当前架构全新设计；下文登记其中仍然成立的工作包和当前状态。

## 按计划找实现

| 要推进的工作 | 先看当前实现 |
|---|---|
| 统一黏度、动量扩散、分层与对流 | [材料状态](../architecture/material-state.md)、[流体求解器](../architecture/fluid-solver.md) |
| 实体双向作用与排水 | [实体输入](../architecture/runtime-and-lifecycle.md#world-inputs)、[流体求解器](../architecture/fluid-solver.md) |
| gas 创造端口与气液 species 交换 | [气体状态](../architecture/gas-state.md)、[化学状态](../architecture/chemistry-state.md) |
| 可放置的有限换热器 | [热学状态](../architecture/thermal-state.md#有限热容量设备) |
| 界面与视觉 | [客户端代码](../maintenance/routing.md#client)、[客户端与协议](../architecture/system-overview.md#查询和展示) |
| 外部 Mod 查询、转移与事件 | [外部 API](../architecture/external-api.md)、[容器事务](../architecture/world-ownership-and-containers.md) |
| 原版行为（灭火、湿润、岩浆、红石） | [世界归属与容器事务](../architecture/world-ownership-and-containers.md) |
| 性能与生产 GPU | [性能与后端规划](performance-and-backends.md)、[性能代码](../maintenance/routing.md#performance) |
| 分区、端口和世界范围扩张 | [区域存档](../architecture/region-state.md)、[世界归属](../architecture/world-ownership-and-containers.md) |

先在下文确认本次功能的完成条件，再沿对应专题进入代码。验收方法见 [验证指南](../maintenance/validation.md)和 [验收与发布](acceptance-and-release.md)。

## 长期产品目标

1. 一个方块空间可包含任意体积、组分和凝聚/气相库存，不受原版“一格一种流体”限制。
2. 可互溶液体连续混合；不可互溶液体共享机械压力但保留界面并按物性分层。
3. 开放空间使用有界的环境大气语义；密闭空间保存有限气体库存、温度和压力，小孔以有限速率连通。
4. 材料、species、phase、reaction 和热物性数据驱动；除显式 source/sink 外，质量、体积、组成、电荷和能量可审计闭合。
5. 颗粒覆盖沉淀、细沙等宏观沉降/沉积，不承诺通用刚体、弹性固体或无依据的全套 MPM。
6. 先把有界 controller 做成可靠、可恢复、可观察、可与其他 Mod 组合的工业流体系统，再评审天然水体和开放世界 ownership。
7. 以纯 Java `model` / `solver` / `application` 保留 CPU 规范实现，并在量化边界稳定后交付生产 GPU 求解后端；后端切换不得改变库存、事务或失败语义。

近期不承诺：逐格求解所有开放空气、逐分子/离子粒子、弱酸/活度/完整动力学平衡、通用刚体或弹塑性体、无界洞穴 flood fill、海洋尺度 20 Hz。生产 GPU 后端是明确后续阶段，不与首个 API/原版交互增量绑定。

## 长期不变量

未来工作仍必须维持六层分离：

| 层 | 允许持有 | 禁止行为 |
|---|---|---|
| 物理原始数据 | 体积、逐材料质量、species/电荷、solid SVQ/组成、gas inventory、各载体 `U` | 从温度、颜色、density 或 mesh 反算库存 |
| 数值离散 | MAC 速度、压力、PLIC/cut fraction、曲率、LTE grid | 把平滑场变成持久 source/sink |
| 渲染重建 | palette、外表面、液液界面、法线、颜色 | 客户端修改服务端状态或掩盖输运错误 |
| 调度/后端 | ACTIVE/REDUCED/SLEEP/UNLOADED、CPU/GPU 选择 | 因降频、休眠或后端切换改变守恒结果 |
| 世界事务 | ownership、container/port transfer、原版交互 | 在可恢复 WAL/HEAD 之前修改多个持久域 |
| 协议/存档 | stable ID、generation、capability、checksum | 写 runtime handle/GPU buffer，或猜测未知布局 |

所有新入口还必须显式声明 BV/nanos/SVQ/AU/kg/K/EU 等单位、server-thread 所有权、all-or-nothing/partial 语义、source/sink 分类、预算和失败状态。

## 当前产品基座

当前支持范围是最多 4 个显式闭壳 controller region，CPU `FluidDomain` 为权威求解器聚合根。

边界和控制器已有生存配方；自定义创造栏把快速入门、闭壳方块、探针、actual 空容器、server-resolved source templates、指示剂与固定温度源按产品顺序列出。

激活后自动运行并持久保存玩家暂停意图，`/mmm` 不再是正常创建、选择和暂停的前置条件。

已经具备：连续体积 PIC/FLIP、可变密度材料、液气/液液界面与润湿、密闭气腔、world ownership、profile-bound 玩家/发射器容器事务、守恒化学、active/deposit 颗粒、总内能、导热、反应热以及固液/气液相变。

这一基座的限制也必须如实保留：

- 不可互溶相目前共享局部速度场，不声称解析切向滑移、乳化和液滴破碎。
- 实体运动已通过 loader-neutral moving-body request 扰动 marker 动量；浸没观察已连续积分 AABB、派生液层和既有 cut-micro geometry，liquid boundary 使用实际当 tick 位姿差而非供下一 tick 使用的原版响应速度。当前 kinematic AABB 近似仍不是移动 cut-cell、压力面积积分或严格双向动量/排水闭合。
- 固液已支持有界、数据化的压力平衡曲线，气液使用绝对压力和 component 分压；更高压力、更多材料和跨相溶解仍需分别验证物性域。
- 气体已从 exact inventory/volume/`U` 派生完整 ideal-mixture 声学闭合，固定压力钳制已移除；接触液体的有限 cavity node 已以 `V*beta_s` 集总自由度进入同一 pressure solve，环境与真空保持 Dirichlet。当前仍不解析逐 cell 质量、动量、总能量与声波；空间化必须先建立这些唯一原始数据，再把逐 cell 非零 pressure compliance 接入同一全马赫方程，不能并存两份库存。
- Query API 已冻结同步只读单格观察，并已有只查询闭壳相邻液体的可选 Jade 适配；container durable completion 已有 committed event。尚无 WAILA/HWYLA、直接自由面目标、稳定 transfer API、其他提交类型事件或其他 loader 发现桥。
- 主 Mod 已有 field probe 的 server-authored 液体/solid/gas 有界观察、controller 的 select/pause/resume/availability console、管理员 empty/vent/release/remove 预览确认流程，以及 creative-only fixed reservoir setpoint。控制器空手激活与持物入口分离，availability pause 不遮断 summary；在 HEAD、registry 和 WAL 仍可证明时，数值暂停区域仍可用私有候选完成只减库存的清理。破坏性动作展示实际移除量和 blocker，以短时同玩家一次性 token 确认，并通过 ACTIVE/REMOVED HEAD、network tombstone 与 quarantine 恢复闭合；这些仍是内部产品界面，不扩展外部 Query/transfer ABI。
- 客户端液面仍由快照体积字节的经验阈值重建，没有服务端 PLIC/共享面几何，也没有液液界面锐化的独立物理路径。
- section 是一个 region 原子 generation 内的存储分片，不具有独立提交、卸载或 ownership 生命周期。
- controller 外天然洞穴、河流、海洋和世界生成水不在当前 ownership 范围。

## 路线是怎么走到这里的

这一步是为了说明为什么下面只列剩余工作，也是为了让后来者不再重复已完成的重建。完整记录见 [架构重整实施记录](../archive/progress/code-architecture-restructuring-implement.md)与 [版本历史](../progress/version-history.md)。

| 阶段 | 提交范围 | 结果 |
|---|---|---|
| M1–M9 物理与事务基座 | 建库至 `M9Close`（2026-08-25） | 多区域、多材料、表面张力、气腔、ownership/容器 WAL、化学、颗粒、温度与相变逐项落地 |
| 冻结产品计划 | `65b3791`–`a56513d`（2026-08-26） | 四份实施计划把“创造模式完整体验”拆成可验收的 A/B/C/D 工作包，同时拆出 `api`/`core`/`fabric` |
| 架构重整 | `BeforeReArch`（2026-08-29）至 `FinishReArch`（2026-09-01），S00–S21 | 生产代码按 `api ← model ← solver ← application` 拆分；`FluidDomain` 成为唯一 live 聚合根；后端交接、调度、事务、GPU 各自有唯一 owner |
| 重整后的数值与事务收口 | `d747f8e`–`PseudoAstra`（2026-09-11） | 修复重整暴露的压力/气体/热学/颗粒/容器缺陷，并把实机日志转成常驻场景 |
| 覆盖与判断能力加固 | `c88e34e`–`2b44a62`（2026-09-22） | fuzzer 具备拒绝判定、独立账本比对、过程覆盖与最小化 |
| CPU 热点优化 | `e3f3dd5`–`Handoff2Api`（2026-09-23） | 化学交接、参考能缓存、压力装配去重；大域从持续超时降到不再触发暂停 |
| 床面输运补正 | `0dffd5a`（2026-09-24） | 水流起动、薄床阻力与局部唤醒按真实流场重建 |

重整是必要的重构，不是偏离主线的内容：它把四份计划里“跨域事务”“后端交接”“app 用例”的前置从散落在单个 god service 里提到了实际模块边界，S20/S21 收口时也明确保留了尚未实现的物理能力（连续介质黏性、空间气体、实体严格双向），没有把它们伪装成已完成。

代价同样要如实记录：从计划冻结到回到物理主线，约 100 个提交花在重建与它暴露的缺陷上。因此本路线现在只保留**未完成项**，已完成项一律移入 architecture 或版本历史；每个新增量必须先确认下表状态，避免第二次重建。

## 推进次序

| 顺序 | 产品阶段 | 前置依赖 | 完成时必须证明 |
|---|---|---|---|
| 1 | 物性统一与动量扩散 | 已冻结的 material registry 黏度定义 | 黏度只有一份权威定义；剪切扩散守恒并给出离散谱界 |
| 2 | 密度、分层与自然对流 | 顺序 1 | 组成密度只来自 mass/volume；Ra 判据在声明有效域内复现 |
| 3 | 展示几何契约与重建 | snapshot identity/sequence、材料 palette | 服务端已提交状态足以唯一重建液面；客户端不再用体积阈值和增厚修补 |
| 4 | gas 创造端口与气液 species 交换 | 顺序 3 的观察入口、现有 exact gas inventory | 玩家可从创造入口建立 sealed CO2 场景；两侧 inventory/组成/`U` 联合提交 |
| 5 | 实体双向边界 | 顺序 1 的黏性面应力 | 排水、压力面积分与液体/实体冲量在同一事务内闭合 |
| 6 | 跨域恢复判定器与可放置的有限换热器 | 现有 container/ownership 恢复状态 | 判定器只有一份；设备 kg/`U` 只存于 region HEAD，两流体零物质交换 |
| 7 | 公共转移 API 与原版行为 | 顺序 4 的 typed payload、顺序 6 的恢复判定器 | 外部 actor 可 crash-safe 收敛；原版行为逐项有库存/能量账本 |
| 8 | section 生命周期与显式端口 | profile、事务与网络 API | 跨 section/region 仍只有完整代次，不依赖永久 chunk ticket |
| 9 | 有界范围扩张 | 高休眠率、恢复/性能基准 | 天然小水体在硬预算内可接管、释放和异常恢复 |
| 10 | 生产 GPU 后端 | immutable step input、完整 candidate、CPU 对照与 profile | GPU 超时/设备丢失保留上一完整状态；库存和误差检查与后端无关 |
| 11 | 大尺度世界评审 | 前述全部量化 | 河流/海洋方案有成本模型、持久模型和明确适用条件后才可开工 |

阶段编号只表达依赖，不绑定发布版本；各阶段应拆成 routing 可选择的小增量。

## 阶段 1–2：物性统一与对流

工作包 C01。当前 material registry 已按 `Pa·s` 保存分段 `mu(T)` 与有效温区，`MaterialRegistryCodec` 把它写进世界能力包；缺的是一份权威定义和它的动量扩散。

### 1a 统一黏度来源

- `GranularCarrier` 仍自带 `dynamic_viscosity`，与 material registry 的分段 `mu(T)` 重复。删掉这份副本，让 `granular_carriers/*.json` 只绑定 liquid material 与 Reynolds 上限，颗粒阻力统一从 material state 取 `mu(T)`。
- 该改动会改变世界能力包的 registry fingerprint 和载荷解释，属于本阶段的第一步，不与 solver 改动放在同一提交。
- 完成条件：仓库内不再有第二份液体黏度真值；`materials`、`granular`、`region-state` 三个最近门通过。

### 1b 有限体积黏性扩散

- 在 P2G 外力之后、压力投影之前加入 Newtonian 动量扩散 `rho·du/dt = div(tau)`，`tau = mu*(grad u + grad u^T)`。
- face 与 control volume 复用 pressure 相同的 cut aperture、实际 liquid control volume 和 owner 分类；两侧黏度用守恒调和平均。相同连续液体的内部 face 应力冲量严格反向。
- 未实现 gas viscosity 前，液气自由面使用零切向 traction，不能把 sealed gas 当 no-slip wall；固定边界默认 no-penetration，切向条件由显式 boundary 定义选择。
- 显式子步的稳定步长必须从本次实际离散矩阵求界（容量 `C_i = rho_i*V_i` 与各开放 face 的 block conductance），sliver cut-cell 的真实体积进入分子；零或负容量、非有限 conductance、无法证明的谱界直接 PAUSED。不能加 velocity clamp 或“高黏度每帧衰减”。
- 完成条件：等密度等温剪切层与解析扩散解收敛对照，总动量只由 boundary impulse ledger 改变；均匀满格、极小 cut volume、零 aperture 三例都验证谱界；`free-surface,cut-cell` 为最近门。

### 1c 密度、分层与对流

- 组成密度直接来自实际 mass/volume，不加“盐度密度公式”；Boussinesq 只作参考态附近的温差修正，必须声明 `|rho-rho_ref|/rho_ref` 有效比例，越界 PAUSED。
- fixture：同温盐水在下保持稳定、反置后发生 Rayleigh–Taylor 型翻转；同组成温差分层；热盐竞争分别报告组成项与温差项；受热底板封闭池用 registry 的 `rho, mu, beta, k, cv` 计算 `Ra`，用两个相差固定倍数、都在线性域的振幅证明归一化增长率一致。
- 完成条件：全部 fixture 的体积、逐材料质量、species、`U` 与 source/sink 闭合；稳定分层的静水速度残差有量化上限；`materials,thermal` 为最近门。

## 阶段 3：展示几何契约与重建

工作包 D01–D03。当前客户端 `FluidSurfaceGeometry` 用 `SURFACE_THRESHOLD = 8` 的体积阈值决定是否出液面，并用固定厚度盒体补足薄体；`MaterialSurfaceGeometry` 负责液液界面。这些是展示启发式，不是服务端几何。

- D01：把 snapshot 扩展为有界、按 interface cell 与 shared face 稀疏编码的展示输入：cell 坐标、cut-cell aperture 摘要、总 liquid fraction、服务端 PLIC plane 的规范化 normal/offset 及其固定点量化、每个不可互溶 phase pair 的 plane/fraction/label side 与唯一 owner、shared face 的 canonical identity。量化误差只影响展示位置，服务端 nanos 不变。
- D02：mesher 拆成纯数据阶段（decode/validate → labeled clipped polyhedra → 每 shared face 一次 canonical planar arrangement → 抵消同 label 覆盖 → 三角化 → normal/attribute）。共享界面只生成一次并有稳定属主与法向；三相接触线由同一 face arrangement 交点生成 canonical junction key。不得用 `INTERFACE_INSET` 类偏移错开外表面与内部界面。
- D03：validator 对每类 surface 计算 label partition 覆盖/重叠、index bounds、finite、面积、重复三角形、有向边关联、boundary loop、winding 与 bounds；frame key 固定为 `(dimension, region UUID, network generation, sequence)`，迟到包装不进当前 frame。
- 完成条件：同一已提交状态重复构建得到相同 canonical digest；稀薄液体、斜 cut-cell 和多材料界面不再依赖体积阈值与增厚；`protocol` 承载 snapshot identity 与 tombstone 覆盖，`materials` 承载 mesher fixture。

## 阶段 4：gas 创造端口与气液 species 交换

工作包 B08 与 C05。创造目录当前只有 `mmm:source/material/*`、`mmm:source/reagent/*`、`mmm:source/granular/*`，没有 gas 入口，因此玩家无法在不使用命令的前提下建立 sealed CO2 场景。registry 侧已冻结 `gas_liquid_pairs/co2_water.json`、`species/co2.json` 与 `thermal_species/water_co2.json`，缺的是入口和联合 candidate。

### 4a gas 创造端口

- 只允许提交时仍为创造模式的真实 `ServerPlayer`：`gas source/vent` 解析壳面与内侧 gas cell，目标必须属于一个 sealed node；ambient target 需要玩家显式确认 source/sink 语义。dispenser、fake player、automation 与 public provider 全部拒绝。
- source descriptor 只含 gas component stable ID、请求标准体积 nanos 和可选 K，服务端从 world gas/thermal/chemical registry 构造实际 kg/species/residual/`U`。
- insert/extract 在私有 region candidate 中联合更新 component standard nanos、kg、species/residual、node `U` 与 gas ledger，HEAD 仍是唯一提交点；descriptor item before = after，但 creative-source actor sequence/receipt 必须 durable。
- 不修改 pressure：pressure 由提交后的 inventory/available volume/`U` 派生。超出 P/T/容量域整次拒绝。
- 最近门 `air-network,thermal,containers`。

### 4b 气液 species 交换

- 从 PLIC/cut face 得到有限界面面积与所属 gas node；从 gas inventory 派生 component 分压，从 liquid exact species/volume 派生浓度；在 registry 声明的 P/T/concentration 有效域内求平衡驱动力。
- 用串联膜阻力求有限速率通量（`delta/D` 与映射后的 gas 阻力同为 `s/m`），连续 `n_extent` 只用于决定向零量化多少完整 exact batch，提交仍只搬运 exact truth；不足一个 batch 时留在原库存等待后续驱动力。
- 一次 batch 联合更新 gas standard nanos/kg/species/residual、liquid species/marker mass residual、两侧总 `U` 与 node/chemical ledger；任一侧容量、温度反演、lineage、预算或审计失败时整个 interface 不修改。多个 interface 按 canonical cell/pair 顺序处理，预算耗尽的剩余 interface 保持 dirty。
- logical step 顺序冻结为：mechanics/projection → marker advection → gas topology remap → conduction → reaction → ordinary phase change → gas-liquid species transfer → granular/reseed → audit。本步新溶解的 species 首次参加反应在下一 logical step，该一步延迟进入 fixture。
- 完成条件：sealed CO2 headspace 加不饱和水使压力下降且液相 species 增加、升温降压脱气方向反转并受界面速率限制、ambient 交换差额进入明确 source/sink、cavity split/merge 同 step 时 species 只随 lineage 搬运一次、未声明 pair 的 gas 与 liquid 永不跨相交换；`gas-liquid,chemistry,thermal` 为最近门。

## 阶段 5：实体双向边界

工作包 C02。当前 `MinecraftEntityFluidInteraction` 以 AABB 湿体积比例和固定 drag/neutral-submergence 参数修改实体速度，同时向 core 提交 moving-body marker 动量；实体力和液体冲量不是同一离散边界的镜像，实体占位也未进入容量。

按依赖拆成可独立关闭的切片，前一步都应能单独退化回当前行为：

1. **表面积分浮力**：在静水 pressure 场对实体表面做 `-p n dA` 积分，与 `rho g V_displaced` 对照，姿态与采样分辨率的误差必须有上限。
2. **移动 pressure boundary**：实体表面法向速度进入 cut-face 与 target divergence，由 projection 本身排开液体；不得直接缩放 marker volume。
3. **统一占位容量**：moving solid occupancy 与 world cut-cell、deposit、coherent solid 共用容量；无容量时返回明确 overflow/rejection，不能让实体永久重叠或静默删液体。
4. **黏性剪切**：复用阶段 1 的 Newtonian surface stress，与 pressure impulse 分列；首个盒体实现不叠加经验形状阻力。
5. **镜像冲量**：`Delta p_fluid + impulse_entity + impulse_fixed + impulse_kinematic_external = roundoff`，并记录 displacement work；实体在 commit 前消失或 teleport 时不能把未应用冲量伪装成已闭合。
6. **严格双向（可在前面切片稳定后单独评审）**：把受支持 body 的三维平移速度作为 pressure system 的附加未知量，用实际质量矩阵与同一 face coupling operator 组成对称 Schur 系统，使 added mass 进入离散系统本身；不承诺通用转动惯量，旋转表面速度只能是明确的 `KINEMATIC_EXTERNAL`。

受支持实体的质量必须来自数据定义（entity type/tag → kg、coupling mode、几何 provider、物性有效域）；没有定义的实体按 `KINEMATIC_EXTERNAL` 处理，能排水并记 external boundary，但不声称严格反作用。

最近门随切片选择 `entity-fluid`、`free-surface`、`shape`、`lifecycle`。

## 阶段 6：跨域恢复判定器与有限换热器

工作包 B00 与 C04。`ContainerTransferJournal`、`WorldActionJournal` 与 `FiniteDeviceJournal` 可以有各自的严格载荷形状，但必须委托同一个 JDK-only 恢复判定器，而不是各自解释“before/after/third”。每个外部域提供 canonical identity、revision、exact before/after truth、durable readback 结果和 `BEFORE`/`AFTER`/`THIRD`/`UNAVAILABLE` 分类，现有 container recovery 先改为委托它，并按完整笛卡尔矩阵做故障注入。

core 已有有限设备状态、多端口交换 planner 与单 region 联合提交，`finite_thermal_devices/finite_heat_exchanger.json` 与 `thermal_materials/copper_heat_exchanger.json` 也已冻结；缺的是世界对象、接触几何和生产调度。

- 新增与 fixed `thermal_reservoir` 不同 ID、模型和 tooltip 的方块；`thermal_reservoir` 继续表示无限热浴，不用一个 BlockEntity 布尔在两种真值之间切换。
- BlockEntity 只保存 provider/object locator 与展示配置；region HEAD 中的 `FiniteThermalDeviceState` 才是 kg、`U`、material fingerprint 与 generation 的权威。UI 温度由 body `U` 反演，不从 BlockEntity 恢复。
- 放置与拆除使用包含 world before/after 与可选 item actor 的 `FiniteDeviceJournal`，委托跨域恢复判定器，不串行执行两个 journal；破坏设备时若携带能量，掉落物必须完整保存 body truth，或把实际 `U`/质量明确结算到 sink。
- contact resolver 只接受实际壳体 face 与 region interior 相邻、属于该 device type 声明的 port；总 conductance 由壁面与流体膜串联热阻推导，膜厚来自 SI 几何，不使用每 tick 倍率。同一 cell 多 face 先合并。
- 每个 thermal logical step 只构造一次 LTE grid，按 canonical locator 排序批量 plan，联合预检所有 cell/body delta 后一次 apply；一个 body 或 cell 失败时本次 device exchange 整体不修改。
- 跨 region 设备在 multi-HEAD coordinator 完成前明确返回 `UNSUPPORTED_MULTI_REGION`，不给两个单 region body 各复制一份 `U`。
- 完成条件：热流体 A、冷流体 B、实体墙与换热器的最小 fixture 中 A/B 材料与 species 零交换，`delta U_A + delta U_B + delta U_device + roundoff = 0`，移除并重新放置不制造也不删除 `U`；最近门 `thermal,region-state,ownership`。

## 阶段 7：公共转移 API 与原版行为

这是 [外部 API](../architecture/external-api.md) 已冻结语义之后的写入侧。当前只有同步只读 Query 与 container committed event。

- 受控转移：在 `api` 定义 JDK-only 的 request/plan/commit，覆盖 liquid nanos 与 granular SVQ，携带 stable resource identity、typed amount、可选质量/组成/能量与 actor transaction identity；plan token 只在 server 内存存活到过期 tick，重启后失效，调用方用同一 operation identity 重试，由 actor durable sequence 判定已提交或 stale。gas 与 coherent solid 只在各自有真实消费者和完整事务后才加入 union。
- 外部 actor 必须能跨重启解析：稳定 `providerId`/codec ID/objectId/epoch、有界 canonical before/after truth、durable save 证据与 receipt。只有能持久保存 epoch/sequence/last receipt 的 actor 才可注册公开写入。
- 事件：只在对应 commit point 之后发布，逐类型使用封闭 record，不预建通用 `type + Map` 事件；监听器异常隔离、不可回滚、允许漏失，并声明能否由 Query 恢复。
- 原版行为逐项定义并验收，不绑成一次发布：灭火（每类火焰的接触、最低液量、温度/相态和蒸气去向）、湿润/冲刷（方块的有限吸收库存或明确的 external sink）、岩浆/高温（材料、热通量和 reaction/phase 事务表达产物）、红石/生物/寻路（只用稳定查询快照和阈值，不暴露 live solver 数组）。每项归属一个世界能力、有独立数据资源、本地化失败反馈和相关测试。
- 阶段关闭条件：至少一个观察适配能在不唤醒区域的情况下显示局部派生温度；至少一个只读外部适配和一个 crash-safe 外部转移适配通过重启恢复；原版行为逐项有库存/能量账本和实际 world fixture，不以视觉结果代替守恒。

## 阶段 8–11：范围与后端

- section 生命周期：当前 section 只是 region checkpoint 内的切片。若要独立休眠/加载，必须先冻结 manifest/commit 模型，回答 marker/gas edge/deposit/thermal flux 属于哪个完整 generation、缺失一片时整 region 停止还是能证明边界通量为零、多个 section HEAD 如何共同提交、网络快照如何拒绝迟到包复活旧状态。区域之间默认不直接连通，交换只经显式端口或另行评审的相邻合并协议。
- 范围扩张次序：controller 内显式端口与 section 休眠 → 有硬边界/预算的小型原版池体接管与释放 → 邻近河段的有限 ownership 与 chunk 生命周期 → 只有上述事务、休眠率和性能门稳定后才单独评审海洋与世界生成水。不申请永久 chunk ticket。
- 性能：占比未知前不修改 40 ms pacing 与连续 3 步 200 ms 暂停阈值；代表性静态域至少 80% 体积可守恒休眠后才扩大范围。CPU 已消除三类重复开销（精确数量重复求和、候选状态序列化往返、同次投影内重复矩阵装配），继续优化必须对照原输入和轨迹，证明只删除了重复工作。
- 生产 GPU：当前 probe 只覆盖单一常密度 carrier。完整后端要求承载 `FluidDomainState` 的全部材料、变密度、表面张力、气体、热学、化学、颗粒与相变；常规步 data 常驻设备，按需回读；设备丢失时暂停区域并从最近 CPU 可读 checkpoint 恢复；库存与误差检查与后端无关。GPU 数值能力缺口（含 reseed 路径与 CPU 的差异）必须显式关闭，不能用阈值、钳制或渲染补偿掩盖。

## 阶段关闭与文档规则

日常小增量只跑 [routing](../maintenance/routing.md) 指定的相关测试；产品阶段关闭或发布才按 [验收与发布](acceptance-and-release.md) 汇总受影响层。自动门不能观察的视觉或真实 world 行为，才请求一次单场景实机验证，并给出单一场景、操作步骤、预期效果和日志依据。

阶段实现后，把仍有约束力的数据结构、公式、提交顺序、预算和失败语义移入对应 architecture；本页只保留尚未完成的目标、依赖与适用条件，已完成项从本页删除。历史发布事实进入 [版本历史](../progress/version-history.md)，不让历史叙述重新成为当前规范。
