# 物理完整性实施计划

## 执行原则

本计划包含五个彼此独立的物理增量。它们共享库存与能量账本，但不应在同一提交中实现。每个增量先建立最小离线/CPU fixture，再改生产路径；无法跨过适用域或预算时暂停候选，不以钳制和材料特判继续运行。

## 密度、分层与自然对流

### 当前基础

cell 机械密度已经由实际 marker mass / volume 重建。化学 species mass 加入 marker 独立质量后，盐水密度会自然增加；thermal Boussinesq 是参考态附近的温差修正，不应覆盖组成密度。因此首要工作不是再加一条“盐度密度公式”，而是验证从 chemical transfer、reseed、P2G、face coefficient 到 buoyancy 的整条链。

### 工作项

1. 建立两层静水 fixture：同温纯水在上、盐水在下，应保持稳定；反置后应发生 Rayleigh–Taylor 型翻转。
2. 建立同质量分数、上下温差 fixture，验证热膨胀在 registry 有效域内改变浮力而不改库存质量。
3. 建立热盐水分层 fixture，分别计算组成密度贡献与温度贡献，检查符号竞争。
4. 建立受热底板/冷却顶板的封闭池 fixture，观察自然对流 onset、总质量和总 U。
5. 诊断输出每 cell 的 inventory density、reference density、thermal density delta、最终 pressure coefficient；这些只读诊断不能持久化。

### 算法约束

- 组成密度直接来自实际 mass/volume，不使用 concentration lookup 覆盖质量。
- 若 thermal expansion 使用 Boussinesq，必须声明 `|rho-rho_ref|/rho_ref` 的最大有效比例；越界转为 PAUSED 或进入另行实现的全变密度状态方程。
- face `1/rho` 仍按界面距离守恒加权；同密度 fixture 必须退化。
- 未解析的分子扩散可使 marker-scale 混合变慢，但不能通过 reseed 跨不同不可合并 composition 吞并组分。若要增加扩散，使用守恒 pairwise flux 并有稳定性 substep，不做每 tick 百分比平均。

### 通过门

所有 fixture 的体积、逐材料质量、species、U 与 source/sink 闭合；稳定分层的静水速度残差有量化上限；反置分层确实交换位置，但不要求匹配湍流实验的细节尺度。

## 实体双向流体边界

### 当前基础和需替换内容

`MinecraftEntityFluidInteraction` 当前以 AABB 网格采样湿体积分数，用固定 drag/neutral-submergence 参数修改实体速度，同时向 core 提交 moving-body marker 动量。它已经能产生划水和入水扰动，但实体力和液体冲量不是同一离散边界的严格镜像，实体占位也未进入容量/pressure。固定经验参数只能作为临时产品近似，应由可审计的压力、黏性和排水模型替换。

### 状态与请求

在 core 定义 immutable moving solid snapshot：

- stable transient body ID，仅在本 step 内匹配结果；
- local pose/AABB 或有界凸面片集合；
- 表面速度、实际 Minecraft 质量或明确 kinematic 标志；
- displaced volume、face sample area/normal；
- 最大 body 数、面片数、覆盖 cell 数。

Fabric 负责从实体碰撞形状冻结 snapshot。core 只返回每 body 的 pressure impulse、viscous impulse、displacement work 和未承接 boundary impulse。不要把 `Entity` 引用放入 request/candidate。

### 推进切片

1. **表面积分浮力**：在静水 pressure 场对实体表面 `-p n dA` 积分，与 `rho g V_displaced` 对照；姿态/采样分辨率误差必须有上限。
2. **移动 pressure boundary**：实体表面法向速度进入 cut-face/target divergence，使 projection 本身排开液体；不得直接缩放 marker volume。
3. **统一占位容量**：moving solid occupancy 与 world cut-cell、deposit、coherent solid 共用容量。进入液体时排出等量 volume；无容量时返回明确 overflow/escaped settlement 或拒绝候选。
4. **黏性/形状阻力**：与 pressure impulse 分列。若采用阻力系数模型，必须按 Reynolds/形状有效域数据化，并在超域停用该项或暂停，不能用单常数覆盖所有实体。
5. **镜像冲量**：液体 momentum delta + entity impulse + fixed-boundary impulse + external kinematic work 闭合。Minecraft 实体一侧实际应用后记录 settlement；实体消失/teleport 时不把未应用冲量伪装为已闭合。

### 调度顺序

主线程冻结实体 snapshot → backend candidate 应用 moving boundary 并返回 per-body impulse → tick boundary 接受完整 fluid candidate → Fabric 对仍匹配 identity/pose tolerance 的实体施加冲量。若实体在计算期间改变到无法匹配，拒绝 candidate 或把 body 明确视为 kinematic external boundary；不能提交流体反冲却丢弃实体侧账本。

CPU 同步实现可以先保持同 tick。异步/GPU 接入前必须冻结 pose tolerance 和 stale request 处理。

## 压力相关相变

### 当前基础

固液相变已有 registry-bound、压力严格递增的分段 `T_eq(p)`；气液使用绝对压力与 component 分压。相变联合搬运质量、体积/SVQ、species/residual 和 U，并把凝聚相体积功留给下一次 projection exactly-once 消费。

### 尚需闭合

1. 明确每 cell 绝对压力来源：最近一次真实 projection gauge pressure + 环境绝对压力或 sealed node 界面压力。无真实 projection 的新/重构 cell 不能使用默认零压假装有效。
2. pressure sample 与相变 carrier 的空间归属一致；液气界面不能误用另一 gas node 的 pressure。
3. 高静水柱 fixture 验证随深度变化的固液平衡温度；曲线端点外有相库存时整 candidate PAUSED。
4. 相变导致的体积变化、latent heat、species 分配和 pending work 在 split/merge/reseed/checkpoint 后仍 exactly-once。
5. 多材料同 cell 中只对 registry 声明的 transition 执行，不根据温度选择“最像的材料”。
6. phase operation budget 耗尽时保留未执行 inventory 和确定队列；不能截断一个 exact batch。

不要通过外推 `T_eq`、钳制 pressure 或从温度倒算已相变质量来提高通过率。

## 有限热容量换热器世界接线

### 当前基础

core 已有 `FiniteThermalBodyState`、device locator/inventory/audit、稳定交换 planner和单 region 联合提交。region HEAD 持有设备 kg/U/generation；provider/object 只是定位符。缺口是世界对象 enrollment、接触几何、tick 调度、UI 和跨 region 协调。

### 单区域可放置设备

先交付一个只接触单 region 的换热方块：

1. BlockEntity 只保存 provider/object locator 和展示配置，不保存可覆盖 region `U` 的温度或能量副本。
2. 放置/首次接触时 enrollment coordinator 在 region candidate 中创建 device body，绑定 world thermal registry fingerprint、DEVICE material、kg、U 和 generation。
3. contact resolver 从真实方块 face、region interior 和 cut-face aperture 生成端口；同一 cell 多面先聚合 conductance。
4. 每个 logical thermal step 只重建一次 LTE grid，批量 plan 所有 device exchange，再由 region 一次提交。
5. 方块破坏必须先从 region HEAD 事务性 unenroll；若设备携带能量，掉落物必须能完整保存 body truth，或明确把实际 U/质量结算到 sink。不能只删 BlockEntity。
6. UI 温度由 region body `U` 反演；locator 失联时 region 保留 body 并 PAUSED/LOCKED 分类，不从 BlockEntity 恢复。

两个互不混合的流体可通过同一 device body 的两个 contact group 交换 U。两侧都在同一 region 时使用现有单 HEAD路径；位于不同 region 时必须等待 multi-HEAD coordinator，不能逐个提交造成设备 U 被中间状态重复使用。

### 验收

热流体 A、冷流体 B、实体墙和换热器组成最小 fixture。A/B 的材料与 species 零交换；`delta U_A + delta U_B + delta U_device + roundoff = 0`；移除并重新放置设备不会制造或删除 U。

## 气液 species 交换

### 当前基础

sealed gas component 已独立保存 standard-volume nanos、kg、允许 species、mass residual 和 U。液相已有 chemical parcel。缺口是两相之间有限速率溶解/脱气的联合 candidate，而不是“把 pressure 变成组分”。

### 数据定义

world registry 对每个 gas component/liquid species pair 定义：

- 两侧 stable species mapping 与质量基准；
- Henry 常数或选定逸度模型及温度依赖；
- pressure、temperature、ionic strength/concentration 有效域；
- liquid-side/gas-side 传质系数模型与界面面积尺度；
- 溶解焓或跨相状态函数 offset；
- 单步最大 transfer 和 dirty interface 预算。

不声明 pair 就不交换。CO2 可作为首个数据化 pair，但算法不得写死 CO2 ID。

### 算法

1. 从 PLIC/cut-face 得到有限 gas-liquid 界面面积和所属 gas node。
2. 从 gas inventory 派生 component 分压，从 liquid exact species/volume 派生浓度。
3. 在物性有效域内求平衡驱动力；按有限膜传质通量和 `dt` 规划 bounded extent。
4. 将 extent 量化成两侧都可表示的 exact species batch，保留 residual；不足一个 batch 时延期，不删除尾量。
5. 联合更新 gas component nanos/kg/species/residual、liquid species/mass residual、两侧 U 和 source/sink/audit。
6. topology remap、phase change 和 gas-liquid species transfer 使用固定 logical-step 顺序，并共享 node lineage；顺序写入 architecture。
7. 任一侧容量、温压域、energy inversion 或 audit 失败时不修改任何一侧。

### fixture

- sealed CO2 headspace + unsaturated water：pressure 下降、液相 species 增加、总 kg/species/U 闭合；
- 降压或升温脱气：方向反转并受界面面积/速率限制；
- ambient 曝气：交换差额明确进入 ambient source/sink；
- cavity split/merge 同时发生时，species 只随联合 lineage 搬运一次；
- 无声明 pair 的 gas 和 liquid 永不跨相交换。

## 推荐顺序与验证门

推荐按风险排序：密度 fixture → 压力相变 fixture → 实体表面积分/占位 → 单 region 换热器 → 气液 species → 跨 region 热设备。

最近门分别使用 `materials`/`free-surface`、`thermal`/`gas-liquid`、`free-surface`/`shape`、`thermal`/`region-state`、`gas-liquid`/`chemistry`。只有真实实体碰撞形状和可放置换热器需要最终单场景进游戏验证。
