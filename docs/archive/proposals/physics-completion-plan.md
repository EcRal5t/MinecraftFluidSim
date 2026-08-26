# 物理完整性实施计划

## 执行原则

本计划包含五个彼此独立的物理增量。它们共享库存与能量账本，但不应在同一提交中实现。每个增量先建立最小离线/CPU fixture，再改生产路径；无法跨过适用域或预算时暂停候选，不以钳制和材料特判继续运行。

## 密度、分层与自然对流

### 当前基础

cell 机械密度已经由实际 marker mass / volume 重建。化学 species mass 加入 marker 独立质量后，盐水密度会自然增加；thermal Boussinesq 是参考态附近的温差修正，不应覆盖组成密度。因此首要工作不是再加一条“盐度密度公式”，而是验证从 chemical transfer、reseed、P2G、face coefficient 到 buoyancy 的整条链。

当前还有一个必须先承认的缺口：`FluidMaterial` 没有统一动态黏度，只有 granular carrier 为颗粒阻力另存黏度；mechanical core 也没有可声明误差的 Newtonian momentum diffusion。因此现状可以验证密度分层和温差浮力方向，却不能用 Rayleigh 数声称“自然对流 onset”，实体也不能从同一物性得到黏性剪切。C01 先统一物性与黏性离散，再关闭自然对流验收；不要把 PIC 数值耗散当成物理黏度。

### C01：统一黏度、分层与对流

#### 物性定义

把动态黏度放入 world-local material registry，按 `Pa·s` 定义分段 `mu(T)`、有效温区和最大适用剪切/Reynolds 诊断域。首个模型只承诺 Newtonian liquid；不声明则不能参与需要黏度的 fixture。`GranularCarrier` 删除自己的重复 `dynamic_viscosity`，只绑定 liquid material并保留颗粒相关的最大 Reynolds 有效域；granular drag通过统一 material state取 `mu(T)`。profile bundle material fingerprint因此必须覆盖黏度定义。

不要从 stable material ID选择黏度，也不要用 density推算。chemical composition当前只通过实际 mass/volume影响机械密度；如果未来 viscosity需要composition，另加数据化混合模型和有效域，本切片不猜测。

#### 动量扩散

在 P2G外力后、pressure projection前加入有限体积 Newtonian viscous step：

```text
tau = mu * (grad(u) + grad(u)^T)
rho * du/dt = div(tau)
```

face/control-volume使用与pressure相同的cut aperture和liquid occupancy；两侧黏度使用守恒调和平均。固定world boundary默认no-penetration，切向条件由明确boundary定义选择no-slip/free-slip；moving body表面速度在C02接入同一通量。pairwise内部应力冲量严格反向，fixed boundary份额进入boundary impulse ledger。

首个实现可用显式substep，稳定步长由离散扩散谱界推导：`dt <= rho_min * dx^2 / (2*d*mu_max)`，其中有效维数和aperture进入实际上界；所需substep超过冻结预算时PAUSED。不能加velocity clamp或“高黏度每tick衰减”。若profile证明显式成本不可接受，再独立实现隐式Helmholtz solve并与显式小步对照。

#### fixture 与判据

1. 等密度、等温、无外力剪切层：速度分布与解析扩散解对照，网格加密时误差收敛；总动量只由boundary ledger改变。
2. 同温稳定盐水分层和反置分层：组成密度只来自mass/volume；稳定例沿用已有free-surface静水残差门，反置例重心势能下降且所有inventory闭合。
3. 同组成温差分层：Boussinesq有效比例沿用registry声明，越界PAUSED；不得改marker mass/volume。
4. 水平受热层：用registry `rho, mu, beta, k, cv`计算 `Ra = g*beta*DeltaT*H^3/(nu*alpha)`。低于相应边界条件的解析临界值时扰动衰减，高于时出现增长；边界条件和理论临界值作为fixture定义记录，不写进通用solver。
5. 热盐竞争：分别报告inventory density、Boussinesq delta和最终body force，翻转条件由两项符号/量级自然决定。

所有测试固定真实SI物性、几何、dt和解析/registry有效域。接受阈值优先复用pressure、thermal和resolution-convergence已有误差门；若必须新增数值阈值，在测试中由解析离散误差阶次推导并记录，不把它放入生产算法。

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

### C02：实体质量、几何与联合提交

Minecraft 实体没有通用可靠的 kg 接口，不能把 bounding-box 大小乘一个隐藏常数称为“实际质量”。新增数据驱动 moving-body definition：entity type/tag → kg、coupling mode、允许的几何 provider和物性有效域。world profile不需要把实体状态并入region layout，但当前definition fingerprint要进入step request/诊断；热重载只影响尚未冻结的下一步。至少为player和一个简单测试实体提供明确定义；没有definition的实体按`KINEMATIC_EXTERNAL`处理，能排水并把work/impulse记入external boundary，但不声称严格实体反作用。

首个几何只支持从Fabric冻结的轴对齐盒六个解析面，不做体素湿采样。snapshot保存前后pose、每面area/normal/centroid、表面速度和解析displaced volume；重复面、负面积、重叠body、越界cell覆盖或超预算整request拒绝。未来boat/复杂shape通过另一个有界surface provider接入，不在core按entity ID特判。

moving occupancy与world cut-cell、deposit/coherent occupancy合成时保留owner分类，不能把动态body烘成持久raw mask。每个face的开口变化和body normal velocity进入同一target-divergence；投影输出的pressure face impulse按相反符号累计给body。C01的viscous face stress使用相同surface velocity/area，pressure与viscous分列。

同步CPU路径的提交顺序：

1. END_SERVER_TICK主线程冻结entity identity、dimension、pose/velocity和definition；
2. backend在私有state上构造dynamic occupancy、求pressure/viscous candidate并返回per-body settlement；
3. 接受前重新解析entity UUID，要求dimension、alive状态、pose revision/AABB raw bits与request完全匹配；首个同步实现不使用经验pose tolerance；
4. 捕获entity pre-velocity；在所有检查完成后进入主线程不可重入提交段，交换fluid candidate并应用entity impulse。正常Minecraft velocity写入必须是同步非抛出；若出现异常，恢复base domain与pre-velocity并把candidate整体判失败，不能留下单边冲量；
5. 记录 `Delta p_fluid + impulse_entity + impulse_fixed + impulse_kinematic_external = roundoff` 和 displacement work；
6. 唤醒只发生在成功candidate覆盖的region，不因只读湿度Query唤醒。

异步/GPU阶段不能沿用raw-bit pose相等，因为一帧延迟会使所有dynamic candidate过期；进入D08前另行冻结可证明的predictor/stale协议。不要现在加入任意厘米容差。

body进入液体前先验证新几何available volume。首个产品场景要求存在gas/free-surface headspace；若sealed满液体导致不可压缩容量为负，整candidate PAUSED并向entity施加零MMM冲量，不删液体或缩marker。后续要阻止实体穿入需要接入Minecraft碰撞/约束，不能用escaped sink解决。

### 推进切片

1. **表面积分浮力**：在静水 pressure 场对实体表面 `-p n dA` 积分，与 `rho g V_displaced` 对照；姿态/采样分辨率误差必须有上限。
2. **移动 pressure boundary**：实体表面法向速度进入 cut-face/target divergence，使 projection 本身排开液体；不得直接缩放 marker volume。
3. **统一占位容量**：moving solid occupancy 与 world cut-cell、deposit、coherent solid 共用容量。进入液体时排出等量 volume；无容量时返回明确 overflow/escaped settlement 或拒绝候选。
4. **黏性剪切**：先只使用 C01 的 Newtonian surface stress，与 pressure impulse 分列。首个盒体实现不额外叠加经验形状阻力；若解析度不足且必须引入 drag correlation，另以 Reynolds/形状有效域数据化、与解析 pressure/viscous 不重复计力，并单独验收。
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

### C03：pressure provenance 与 phase queue

pressure是数值状态，不进入库存 checkpoint，但相变若消费它就必须知道它来自哪一次真实projection。新增runtime-only `PressureProjectionSnapshot`：region logical generation、geometry revision、gas topology lineage、每个fluid cell的gauge pressure与相邻gas/ambient absolute reference。只有完成PCG residual/divergence/audit门的projection才能发布snapshot；碰撞、moving occupancy、gas remap、marker geometry或pending work改变会使不匹配cell失效。

thermal/phase step只能消费当前logical candidate中刚完成的projection，或明确证明geometry/gas/occupancy revision未变的最近snapshot。新restore的region先执行mechanical projection再允许pressure-dependent phase；不能从checkpoint缺失pressure补零。cell absolute pressure计算一次并携带provenance到solid-liquid/gas-liquid planner，液气界面绑定实际gas node key而非稠密runtime ordinal。

phase operation queue是确定的 `(cell, transitionStableId, direction, exactExtent)` 候选顺序。预算耗尽只保留dirty/retry标志和未修改inventory；不要把部分planned batch持久为第二库存。一个batch内同时验证liquid marker/continuous solid、species/residual、gas component、所有`U`和target capacity。提交成功产生带generation的pending volume/work；下一次真实projection消费后删除，失败重试不能重复。

验证至少覆盖：restore后phase等待projection、gas topology改变使旧sample失效、高静水柱深度趋势、curve端点内/外、reseed/split/merge后pending work、phase预算恰好耗尽与`long` extent overflow。最近门为`thermal,gas-liquid`；改变pressure snapshot发布再加`free-surface`。

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

### C04：世界端有限设备协议

fixed `thermal_reservoir`继续表示无限热浴；新增不同ID、模型和tooltip的`finite_heat_exchanger`，禁止用一个BlockEntity boolean切换两种真值。

#### locator 与 enrollment

BlockEntity只保存`providerId=mmm`、由dimension+position派生的canonical object ID、device type和可选展示方向。region HEAD中的`FiniteThermalDeviceState`保存material registry fingerprint、DEVICE material、kg、`U`、body generation和contact groups。

设备在controller激活前已位于壳体时，footprint scan把locator/contact geometry纳入初始region candidate，body initial kg/`U`由server验证的creative device descriptor或实际item contents给出，并与首个HEAD同代提交。已存在region上的新增/拆除不直接在place/break callback改BlockEntity：

- place：world before是可替换boundary位置；准备region enrollment candidate和world-action WAL，HEAD后收敛block/BlockEntity；
- survival detach：after item完整携带`FiniteThermalBodyContents(fingerprint, material, kg, rawU, generation)`，region HEAD移除body后再把item/world durable保存；
- creative break：允许把device kg/`U`明确记入device escaped sink后删除，但仍需region candidate + world WAL；
- locator block被第三方破坏/移动：region保留body真值并`PAUSED_DEVICE_MISSING`，不能从缺失BlockEntity恢复或自动sink。

finite device audit增加独立kg与`U`的external source/sink/actor transfer：creative descriptor enrollment记source；实际device item enrollment/拆除是region↔item transfer；creative break记actual sink。solver↔device heat exchange仍是region内部镜像项，不能与device创建/销毁source混在一起。

post-creation place/detach使用一个包含world before/after与可选item actor before/after的`FiniteDeviceJournal`，复用B03/B06的generation、checksum和recovery规则但不串行执行两个journal。顺序固定为stage region→PREPARED journal→REGION_COMMITTED journal→HEAD→world block/BlockEntity after→item actor after→两者durable save/readback→删journal。HEAD为base时保持world/item before；HEAD为target时幂等收敛两种外部域；任一第三状态保留journal并锁定。不能HEAD后只保存掉落物、world block仍处于before就提前删除WAL。

同一position只能enroll一个body；object ID、body generation、region generation和world block preimage都进入操作candidate。drop cloning、silk touch或creative pick-block不能复制已enrolled body truth；pick-block只得到未绑定creative descriptor。

#### contact geometry 与调度

resolver只接受实际壳体face与region interior相邻、属于该body device type声明的port。每个contact记录cell、face normal、cut aperture、wall thickness、wall conductivity和接触面积；总conductance由串联热阻推导：

```text
1 / G = wallThickness / (kWall * A)
      + fluidFilmThickness / (kFluid * A)
```

film thickness若未解析，来自device/port data的SI几何，不使用tick倍率。相同cell多face先合并一个energy owner；同body最多16端口的现有硬上限继续生效。两个contact group可位于同region中由一个body交换热量，但不把material/species搬过wall。

每个thermal logical step：一次构造LTE grid→解析全部device contact→按canonical locator排序→为每个body批量plan→联合预检所有cell/body delta→一次apply→更新双边audit。一个body或cell失败时，本次region device exchange整体不修改；是否允许其他独立body继续需另有transaction partition证明，首个实现整批拒绝更清晰。

跨region locator在P1明确`UNSUPPORTED_MULTI_REGION`并不给两个单region body各自复制一份`U`。只有multi-HEAD coordinator完成后才开放。

#### UI 与验收

controller/probe显示locator、body kg/`U`、派生K、端口数、每步实际exchange和失联reason。UI没有set temperature按钮。自动fixture覆盖initial enrollment、place/detach各WAL故障点、contact去重、两流体零mass/species transfer、device substep跨caloric segment、checkpoint restore和missing locator。最近门`thermal,region-state,ownership`按实际切片追加；真实游戏只在A12同一场景验证可放置/重进。

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

### C05：首个 CO2 数据集与联合 candidate

#### registry 增量

为首个可玩pair增加数据，不在算法中检查CO2 ID：

- gas component：stable ID、标准态density/standard-volume-to-kg、允许gas species和thermal host；
- liquid species：aqueous chemical phase中的稳定CO2载体species、组成/电荷/exact mass；本计划不把它自动解释为弱酸，也不新增pH平衡；
- gas/liquid mapping：一个exact batch在gas侧的standard nanos/kg/species与liquid侧quanta/kg residual的对应关系；两侧composition和mass必须在registry load时闭合；
- equilibrium：首个ideal-dilute Henry relation `c_eq = k_H(T) * p_component`，包含`k_H`温度函数、P/T/concentration有效域；
- transport：liquid/gas diffusion coefficient和物理film thickness。使用串联film resistance，不用“每tick转百分之几”：

```text
J = A * (c_eq - c_liquid)
    / (delta_liquid / D_liquid + mappedGasResistance)
extent = J * dt
```

gas-side单位映射和`mappedGasResistance`由pair definition明确；无可验证gas resistance的第一数据可声明liquid-side controlled并把gas resistance设为物理上的零边界条件，而不是隐藏经验常数。溶解/脱气能量差由gas host与liquid host的species state-function offset在同一T下推导，不另存会漂移的“反应热”。

world profile的chemical/gas/thermal fingerprint必须一起覆盖这些定义。当前world bundle不可变；数据包变化只报告definition mismatch，不给已有world追加pair。

#### logical-step顺序

固定为mechanics/projection → marker advection → gas topology remap → conduction → reaction → ordinary liquid/gas phase change → gas-liquid species transfer → granular/reseed → audit。若water phase change先改变界面/available volume，则species transfer使用其联合candidate中的新lineage和新interface geometry；不能对旧node再转一次。

每个interface由PLIC面积、liquid chemical phase和canonical gas node key唯一识别。planner从exact gas inventory派生partial pressure，从liquid quanta/实际liquid volume派生concentration，在有效域求signed extent，然后向零方向量化为最大的完整exact batch。小于一batch保留在原库存等待未来驱动力，不建立“丢失尾量”字段。

一次batch联合更新gas standard nanos/kg/species/residual、liquid species/marker mass residual、两侧总`U`、node/chemical ledger和roundoff。任一capacity、temperature inversion、topology lineage、operation budget或audit失败，整个interface candidate不修改；多个interface按canonical cell/pair顺序处理，预算耗尽的剩余interface保持dirty。

#### 玩家入口与观察

使用A07/B08的gas source/vent把CO2 actual inventory注入sealed headspace；不允许`/mmm airdemo`作为P1证据。field probe/Jade gas extension显示component standard nanos/kg、partial/absolute pressure与K；liquid probe显示aqueous species quanta。sealed吸收、升温/降压脱气和ambient交换三个场景都能从正常入口建立。

#### 自动门

除原fixture外覆盖exact batch边界、零界面面积、film thickness/D有效域、cavity split/merge同step、water vapor phase同step、reaction同cell、U offset、预算耗尽和profile fingerprint mismatch。最近门`gas-liquid,chemistry,thermal`；只改data codec时从最直接的`gas-liquid`开始，跨层后再追加。

## 推荐顺序与验证门

推荐依赖顺序：

1. A01同步冻结material黏度snapshot/codec；C01-a统一granular carrier读取；`materials,granular,region-state`按切片选择。
2. C01-b有限体积viscous step与解析剪切fixture；`free-surface`，改cut aperture才加`cut-cell`。
3. C01-c密度/热盐/Rayleigh fixture，修复fixture实际暴露的路径；`materials,thermal`。
4. C03 pressure provenance与压力相变fixture；`thermal,gas-liquid`，改projection发布才加`free-surface`。
5. C02-a loader-neutral盒面snapshot、静水pressure面积分；`free-surface,shape`。
6. C02-b dynamic occupancy/target divergence、entity impulse joint commit；`free-surface,shape,lifecycle`按实际层选择。
7. C04 enrollment/contact/tick，再分别提交place/detach world事务与资源；`thermal,region-state,ownership`。
8. C05 registry/exact mapping、equilibrium/flux、联合candidate、gas创造入口；`gas-liquid,chemistry,thermal`。
9. multi-region heat device只在公共multi-HEAD coordinator存在后单独评审，不属于P1。

| 工作包 | 首要代码入口 | 必须删除/替换的临时路径 |
|---|---|---|
| C01 | `FluidMaterial*`、`GranularCarrier`、P2G/pressure前mechanics | granular独立黏度真值、把PIC耗散称为viscosity |
| C02 | `MinecraftEntityFluidInteraction`、`MovingBodyFluidCoupling`、`FluidStepRequest/Candidate`、cut-cell/pressure | fixed drag、neutral-submergence、AABB湿格比例施力 |
| C03 | pressure projection result、`ThermalPhaseChangeEngine`、`PendingPhaseVolumeState` | 默认零pressure、curve clamp/extrapolation |
| C04 | `FiniteThermal*`、`ThermalCellGrid`、`FluidRegion`、world-action coordinator、新block/entity | BlockEntity temperature/`U`副本、把fixed reservoir改名复用 |
| C05 | gas/chemical/thermal registry、PLIC interface、gas topology lineage、phase/chem candidate | per-tick百分比、CO2 ID分支、从pressure反推gas inventory |

每个work package完成后把最终公式、状态、logical-step顺序和失败语义并入对应architecture，再从本计划删除已完成段。A12是一座统一创造实验室，实体与可放置换热器不再分别请求额外人工场景；真实`VoxelShape`若自动`shape`门仍无法观察，才提出一个单独盒体/斜面场景。
