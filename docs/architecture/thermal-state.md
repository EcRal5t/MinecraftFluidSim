# 内能、热输运与相变契约

## 计算边界与单位

热学不是一张附着在渲染格上的温度场，而是凝聚相载体和气腔库存上的守恒 side column。内部能量单位 `EU` 在内置物性中按 SI 数值标定为焦耳；质量边界按 kg、长度按 m、时间按 s，因此热容为 `EU/K`、导热率为 `EU/(s·m·K)`。参考温度不是零开尔文，故总内能可以为负；符号本身不表示无效。

`FlipThermalState` 负责液体 marker、active parcel、deposit 和 coherent group 的内能与局部热平衡；`GasThermalModel` 负责 gas node；`ThermalTransport` 只做 pairwise 有限体积导热；phase/reaction engine 只产生不可变候选，最终提交仍由 domain 统一完成。这个职责边界避免求解器、持久化和命令各自发明温度修复路径。

## 权威真值

- 液体 marker、active parcel、deposit group、coherent condensed group 和 sealed gas node 分别保存总内能 `U`。
- world thermal registry snapshot 保存材料 caloric segment、参考能、导热系数、有效温区、species offset、transition 和固定温度边界定义。
- external energy source、sink、mechanical work 与 reaction/phase transfer 都有独立审计列。
- 温度、热容、焓、密度修正、颜色和 tooltip 由状态函数派生，不持有或修复库存。

只有 liquid marker mass 是 solver weight，并通过 `SolverUnitScale` 的 `cellSize³` 边界换算为 kg。active parcel、deposit、coherent phase、气体、chemical species/residual 和有限设备的质量已经是 kg，不再缩放；相变只在实际更新 liquid marker 时把搬运的 kg 换回 solver mass。

同一载体的 identity、质量/体积、组成和 `U` 必须同 ordinal、同 generation。空载体不能残留内能；非空载体不能缺内能。cell 温度是把其中所有载体带入 LTE 反演后的临时结果，不是另一份可独立恢复的真值。

热学 registry 是 world snapshot：

- material 定义 carrier kind/id、参考温度、参考比内能、导热率和最多 16 个 caloric segment；`DEVICE` 专用于 region-owned 有限热容量设备，不混入 solver carrier ordinal；
- species offset 定义某 host material 中每 AU 的参考内能偏移；
- gas component 允许的 species 在 chemistry 启用时必须全部具有对应 host gas species offset，避免库存先提交、温度反演后失败；
- transition 定义上下相、有界 `T_eq(p)` 曲线、exact 两侧体积量子、exact 质量批和接口要求；曲线端点是压力有效域的唯一真值，不重复保存参考温度或上下限；
- boundary 定义哪些世界方块提供固定温度面。

总材料数、segment、species offset、transition、boundary、cell carrier 和 phase operation 均有硬上限。world fingerprint 或引用不一致时保留存档并 LOCKED，不按当前数据包重解释。

## 状态函数

每种载体用分段 caloric definition 计算 `U(T)` 并反演 `T(U)`。同格异质载体使用 bounded LTE common-temperature inversion；状态函数不得读取客户端数据或经验 tick 倍率。

对质量 `m`、host material `h` 和 species 库存 `n_s`：

```text
U_h(T) = m * u_ref,h
       + sum_s(n_s * epsilon_h,s)
       + m * integral(T_ref,h .. T) cv,h(theta) dtheta
```

caloric segment 内 `cv` 为常量，所以积分与反演都是分段闭式线性计算，不用迭代近似。异质 cell 的 LTE 先取所有 carrier 有效温区交集，再在合并后的 segment 边界上反演 `sum U_i(T) = U_cell`。无共同温区、能量落在共同有效域外或热容量非正都拒绝候选。

相变平台由上下相在 `T_eq` 的状态函数能隙定义。能量落在两端之间时，派生温度保持 `T_eq`，相分数由 exact phase inventory 决定；相分数本身不是库存列。

候选状态必须同时满足：

- 所有材料的温度有效域；
- transition 的 pressure/temperature 有效域；
- 有限、正的热容量与密度；
- 精确库存和能量审计闭合。

越界时报告载体、候选温度/压力和有效区间并安全暂停，不能钳制设定值或丢弃能量。

## 热输运

- 导热使用有限体积 face flux，同一 face 的两侧能量变化严格相反。
- 流液接触面积来自 PLIC/cut-face；solid、deposit、coherent 和 gas 使用各自实际 occupancy/available volume。
- 普通世界边界默认绝热。registry 声明的 fixed-temperature 方块和 `thermal_reservoir` 是无限热浴，但只经相邻 face flux 进入 source/sink。
- 液体热膨胀使用 registry-bound Boussinesq 定义，只向现有 y-face 动量路径提供派生体力，不改变质量或体积真值。
- 气体孔流同时搬运组分与能量；species 的 host-gas 状态能进入气体温度反演与相变批能量，压缩/膨胀功进入 mechanical work。

### 有限热容量设备

`FiniteThermalBodyState` 是 loader-neutral 设备 body 真值：绑定 world thermal registry fingerprint、稳定 `DEVICE` material ID、generation、kg 和总 `U`。`FiniteThermalDeviceState` 另存 provider ID、provider 内稳定 object ID 和 device type；这些字段只定位世界对象或外部 Mod 端口，不拥有能量。规范排序后的 `FiniteThermalDeviceInventory` 作为 manifest-only extension 随同 region HEAD 提交，空集合用 extension 缺席表示。codec 回读时重新验证 registry fingerprint、`DEVICE` material、material carrier ID 与 device type；BlockEntity 不能另存一份可反向覆盖 `U` 的 setpoint。

`FiniteThermalExchange` 对一个设备 body 和最多 16 个独立流体能量端口做有界显式规划。每个端口提供当前温度、当前 caloric interval 内的热容、包含流体/壁面/接触面积热阻的总 conductance，以及该线性化的温度有效域。每个 substep 使用同一 body 温度计算镜像通量，设备 `U` 的变化与各端口增量加总，并把 IEEE-754 总和差记为 exact dyadic roundoff。稳定步长同时受设备完整物性域内最小 `0.5*C_body/sum(G)` 与每个 `0.5*C_port/G_port` 限制；不能只取设备初始温度的热容，否则跨入低热容 caloric segment 后会失稳。超过冻结 substep 预算或任一方越出物性域时整体拒绝计划。

这个 planner 不搬运任何物质，也没有世界、region HEAD 或 block entity 提交权。同一区域路径先批量重建一次 LTE grid，冻结每个独立 cell port 的温度、热容和共同有效域；同一 cell 的多面 conductance 必须先聚合，不能伪装成多个能量 owner。body candidate、所有 cell `delta U`、solver `finiteDeviceExchange` 与两侧 roundoff 全部预检后才修改载体，提交异常会恢复 carrier U；下一 checkpoint 把 body 与流体一起交给 HEAD。接触多个区域时不得调用这条单区域路径，必须先验证全部 candidate，再通过跨 region HEAD 协调提交。当前尚未接入世界对象 enrollment、接触几何生成和生产 tick 调度，因此固定温度 boundary 仍是唯一生产世界热边界。

### 有限体积离散

两个相邻 LTE cell 的 face conductance 与热量转移为：

```text
k_face = 2*k_a*k_b/(k_a+k_b)
G_face = k_face*A_contact/dx
Delta U_a = G_face*(T_b-T_a)*dt
Delta U_b = -Delta U_a
```

`A_contact` 由 PLIC 液面和 cut geometry 限制在一个 face 面积内。固定温度边界位于 cell 中心半个网格距之外，故 `G_boundary = 2*k_cell*A_contact/dx`。没有 registry boundary 的外露 face 返回绝热，不会默认与 293.15 K 环境交换。

显式 Jacobi 更新按

```text
dt_stable = min_cell(0.5 * C_cell / sum_faces G_face)
```

自动分 substep；`0.5` 保证一对相同 cell 单调趋近平衡而非一步交换温度。所需 substep 超过 8 时安全暂停。hot loop 使用 `double`，每个 face 对的增减严格镜像；commit 边界用 `SignedDyadic` 精确求和记录 IEEE-754 有限数，舍入残差单列保存，不能伪装成 source 或 sink。

内置水在 293.15 K 使用 `rho = 1000 kg/m³`、`cv = 4184 J/(kg·K)`、`k = 0.598 W/(m·K)`；水蒸气使用 `cv = 1410 J/(kg·K)`、`k = 0.025 W/(m·K)`。一个 0.5 m cell 满水的热容量约 `523 kJ/K`；4 BV 水约 `16.736 MJ/K`。四个完整 reservoir 接触面总导热为 `2.392 W/K`，因此现实标定下温升本来就慢，极端热源只是高功率边界，不是比热缩放。

### 热浮力

液体采用 Boussinesq 近似，只在重力冲量中加入

```text
relative buoyancy = beta * (T - T_ref)
Delta u_y = g * dt * volume-weighted(relative buoyancy)
```

内置 `beta` 在 293.15 K 附近冻结为：水 `2.07e-4/K`、油 `7.00e-4/K`、汞 `1.82e-4/K`。该项不修改密度、质量或体积真值，也不适用于越出物性有效域的大温差外推。

### 气体能量与功

gas node 的共同温度由各 component 的质量、species 和 node `U` 反演；非等温绝对压力为

```text
p = P0 * (Vstd / Vavailable) * (T / T0)
```

在单一 caloric segment 内，几何改变按常 `cv` 理想气体等熵关系计算压缩/膨胀，`Delta U` 记为进入 gas inventory 的 mechanical work。孔流搬运源状态焓 `h = u + p*v`；node 合并时能量相加，分裂时所有 child 保持 parent 温度且最后一个 child 接受浮点余数。跨 caloric segment 或越过 gas 压力有效窗拒绝候选。

## 相变

相变门依赖热学门。每个 transition 用完整最小守恒量子定义，运行时按可用能量、库存、目标容量、饱和条件和统一操作预算求最大整数 extent。

每次固液或气液扫描先从当前全部载体构造一次派生 LTE grid；无相库存或方向上没有可搬运载体的 cell 不构造完整 inventory。气液 extent 的二分候选冻结该 cell 的 exact dyadic 总 `U` 与不变 solid heat-bath inventory，只替换候选 liquid inventory。真实 phase commit 后重建载体 ordinal，但只按守恒余量重新求解被编辑 cell；未编辑 cell 必须已有同代 LTE 状态并原样保留，不能借此反算或改写其 `U`。

### 固液

- 联合搬运物理 kg、condensed weight、液体 marker、coherent SVQ、材料身份、可选 chemical composition/species 和 `U`。
- 体积变化与机械功写入 pending phase-work，由下一次 pressure projection exactly-once 消费。
- 固液规划从最近一次真实 projection 的 cell gauge pressure 加环境压力得到绝对压力，再取 `T_eq(p)`。曲线使用 registry-bound、按压力严格递增的分段线性点，只在端点闭区间内插值；常温曲线也必须显式给出两个端点。固液曲线不必覆盖标准压力，因此可以表达只有高压区有效的相平衡；液气曲线必须覆盖标准压力，以定义其气体标准体积和潜焓参考态。任何曲线都禁止外推或钳制，有相库存的 cell 越界时整个 thermal candidate 安全暂停，不能静默当作无相变。

内置冰/水 transition 每批为 `0.001 kg`，两侧分别为 `146 SVQ` 与 `134 SVQ`。其 `10 kPa–2 MPa` 平衡曲线取自 [IAPWS R14-08(2011) Ice Ih 熔化压力关系](https://iapws.org/documents/release/MeltSub)的反演采样，包含 1 atm 采样点，在当前分段上相对原关系的最大温度插值误差低于 `4e-5 K`；曲线外拒绝候选。规划器在共享 phase-operation 预算内寻找 exact batch lattice 上离 `T_eq(p)` 最近的可表示状态；候选必须严格缩小温差，跨过平台时只在跨越后的格点更近时提交，随后保持稳定，不允许每步冻融往返。由 batch 大小造成的剩余温差是明确量化误差；库存、容量或操作预算先到达时停止。

### 气液

- liquid water 与 water vapor 使用同一个 transition 的质量、潜热和 exact standard-volume batch。
- 沸腾/凝结由 water-vapor 分压、温度和 transition 饱和压力关系决定，并受 sealed/ambient gas inventory 容量约束。
- commit 同时更新 liquid marker、gas component nanos/kg、gas node energy、source/sink 与 topology remap；任何列不闭合则整批拒绝。

水/蒸气的方向由 ideal-mixture component 分压和数据化的 Clausius–Clapeyron 饱和关系决定：

```text
p_sat(T) = P0 * exp[-L/R * (1/T - 1/T_eq)]
p_partial = p_absolute * Vstd,vapor / Vstd,total
```

`p_sat > p_partial` 允许沸腾，反向允许凝结，相等时不转移。这里 `L` 从上下相 `U(T_eq)` 加 `p*Delta V` 推导，不另存一个可能漂移的潜热常量。

内置 batch 为 `0.001 kg`：液体体积 `134 SVQ`，沸点体积 `228122 SVQ`，换成标准状态约 `0.001335254 m³std`；这组数给出约 `1000 kg/m³` 液水、`0.588 kg/m³` 沸点蒸气和 `461.52 J/(kg·K)` 水蒸气气体常数。上下相状态函数在 1 atm 给出 `2.2567 MJ/kg` 汽化焓。

每个 logical step 的固液与气液共享最多 4096 次 phase operation；按 0.05 s step 和 1 g batch，理论数值上限为 `81.92 kg/s`。这是防止无界工作量的预算，不是现实相界动力学。真实速率由传入的有限热通量、库存、容量与饱和条件限制；若量化 fixture 需要界面动力学，应新增有适用域的数据模型，不能把 tick 倍率塞入潜热。

气液候选先用当前 marker geometry 同步 gas topology，再规划 inventory/energy；提交后再次验证 node membership、available volume 和 energy ordinal。这样任何可发布 logical state 都不会出现 marker 已变而 gas node 仍属于上一拓扑的半代次。

## 反应热

化学和固液反应先完成 exact extent 规划，再将定义的能量变化提交给同一 cell 的热载体。反应热不得绕过 chemical/solid 账本单独修改温度。

species reference offset 是组成携带的能量真值。reaction extent 改变 species 时，反应热表现为同一总 `U` 下 base energy 的改变，随后 LTE 温度自然变化；外部热浴或命令注热才进入 external source/sink。禁止既修改 species offset 又额外重复加一份反应热。

## Logical step 顺序

1. mechanics、pressure 与 advection；
2. carrier handoff 和有限体积 conduction；
3. 化学与固液 reaction extent；
4. reaction heat；
5. 固液和气液 phase transition；
6. granular transition、reseed 与局部唤醒；
7. 审计闭合后，pending phase-work 留给下一 projection。

每一步都先在快照上形成候选。相变产生的体积改变不能回头修改已经完成的 projection，因此作为带 generation 的 pending volume/work 保存，并且只由下一次 projection 消费一次。存在 pending work 时，`empty`、不完整 checkpoint 和绕过 projection 的转移会被拒绝。

## 能量审计

每次可发布状态必须满足：

```text
U_inventory
= U_initial + external_source - external_sink
 + mechanical_work + finite_device_exchange + roundoff_residual
```

`U_inventory` 分别汇总 liquid marker、active parcel、deposit、coherent group 和 gas node。载体间 conduction、reaction carrier handoff、phase transfer、gas merge/split 都是内部转移，不进入 external source/sink；只有 ownership 边界物质进出、固定温度 reservoir 或 operator source 才改变外部列。有限设备位于 solver carrier 边界外、region HEAD 边界内：流体侧把实际请求量记入 `finite_device_exchange`，cell 分配舍入只进 solver roundoff；设备侧独立保存相反的 `solver_exchange` 与 planner roundoff，并要求 body 总 `U` 等于其 expected inventory。两份 roundoff 相加必须等于 region 内 body+fluid 的实际总 `U` 变化。audit 不闭合时保留候选前状态并 PAUSED，不能通过重算温度“修好”。

## 持久化与容器

- 完整热区域 generation 必须联合保存 marker/solid/gas topology、所有载体 `U`、audit、Boussinesq identity 和 pending phase-work。
- capture 前执行 gas topology synchronization，确保 marker geometry、gas membership 和 gas energy ordinal 同代一致。
- `ThermalContents` 将物品中的液体物质真值与同一 registry fingerprint 下的总内能绑定；温度 tooltip 只读。
- 容器 WAL 在含热转移中同时保存 exact liquid amount、item before/after 和 coupled energy；物品 durable save 前不得清理。

section payload 逐 ordinal 保存 liquid/active/deposit energy raw bits 和 coherent group；manifest 保存各载体 exact dyadic 汇总、gas node energy、audit、registry identity、phase-work 及 section ledger。恢复时先验证 cardinality 与所有汇总，再构造 solver；派生温度不落盘，也不用于补全缺失 `U`。

## 调度与观察

motion 和 thermal activity 是正交轴。只有温度残差、边界通量、反应/相变或局部几何变化达到唤醒条件时才推进热学；休眠不得改变任何库存。

`status` 可读取全域温度范围、总 `U`、source/sink/work、phase batch 和气体节点摘要。Query API 可返回单格权威载体反演出的可选温度；查询只读 marker/side columns，不唤醒、不建网格，也不回写热状态。

thermal activity 与 motion pacing 相互独立：有 face transfer、reservoir exchange、finite-device exchange、chemistry/granular/phase dirty、gas activity、carrier motion、pending work 或未闭合 audit 时保持 ACTIVE；连续安静观察先进入 REDUCED，再进入 SLEEP。boundary、容器、管理员 source、有限设备、化学、颗粒、气体、相变或 carrier movement 都是精确唤醒原因。

以下失败属于 PAUSED 而非 LOCKED：候选温度/压力越出已知物性范围、显式传导 substep 超预算、phase candidate 无法闭合或运行时 audit 失败。registry identity、持久 payload、ordinal/generation 身份不明则属于 LOCKED；两者都禁止猜测修复。
