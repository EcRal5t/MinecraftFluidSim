# 内能、热输运与相变契约

修改内能、热传导、反应热、相变和热设备时读本文。

## 修改入口

| 要改什么 | 代码入口 | 测试选择器 |
|---|---|---|
| 内能状态与流体数据映射 | [ThermalDomainState](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalDomainState.java)、[FluidThermalBinding](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidThermalBinding.java) | `thermal` |
| 热浮力有效范围与分层 | [LiquidThermalExpansion](../../model/src/main/java/cc/ecisr/mmm/thermal/LiquidThermalExpansion.java)、[LiquidCarrierProperties](../../solver/src/main/java/cc/ecisr/mmm/fluid/LiquidCarrierProperties.java)、[LiquidStratificationValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidStratificationValidation.java) | `materials,thermal` |
| 传热与反应顺序 | [ThermochemistryPipeline](../../solver/src/main/java/cc/ecisr/mmm/fluid/ThermochemistryPipeline.java)、[ThermalTransport](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalTransport.java)、[ThermalImplicitTransport](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalImplicitTransport.java) | `thermal` |
| 固液和气液相变 | [CondensedPhaseChangeStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/CondensedPhaseChangeStage.java)、[GasLiquidPhaseChangeStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GasLiquidPhaseChangeStage.java)、[GasPhaseInterfaceResolver](../../solver/src/main/java/cc/ecisr/mmm/fluid/GasPhaseInterfaceResolver.java) | `thermal` |
| 有限热容量设备 | [FiniteThermalDeviceInventory](../../model/src/main/java/cc/ecisr/mmm/thermal/FiniteThermalDeviceInventory.java)、[FiniteThermalExchange](../../model/src/main/java/cc/ecisr/mmm/thermal/FiniteThermalExchange.java)、[FiniteThermalDeviceCodec](../../fabric/src/main/java/cc/ecisr/mmm/simulation/persistence/FiniteThermalDeviceCodec.java) | `thermal,region-state` |
| 恒温源调节 | [ThermalReservoirUseCase](../../application/src/main/java/cc/ecisr/mmm/application/ThermalReservoirUseCase.java)、[ThermalReservoirScreen](../../fabric/src/client/java/cc/ecisr/mmm/client/ThermalReservoirScreen.java) | `protocol,thermal` |
| 气体热力学与定义 | [GasThermalModel](../../model/src/main/java/cc/ecisr/mmm/thermal/GasThermalModel.java)、[ThermalRegistryCodec](../../fabric/src/main/java/cc/ecisr/mmm/thermal/ThermalRegistryCodec.java) | `thermal` |

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [计算边界与单位](#计算边界与单位)
- [权威数据](#权威数据)
- [状态函数](#状态函数)
- [热输运](#热输运)
- [相变](#相变)
- [反应热](#反应热)
- [Logical step 顺序](#logical-step-顺序)
- [能量审计](#能量审计)
- [持久化与容器](#持久化与容器)
- [调度与观察](#调度与观察)

## 计算边界与单位

热学不是一张附着在渲染格上的温度场，而是凝聚相载体和气腔库存上的守恒 side column。内部能量单位 `EU` 在内置物性中按 SI 数值标定为焦耳；质量边界按 kg、长度按 m、时间按 s，因此热容为 `EU/K`、导热率为 `EU/(s·m·K)`。参考温度不是零开尔文，故总内能可以为负；符号本身不表示无效。

`ThermalDomainState` 组合液体、气体、active parcel、deposit 和 coherent group 的内能 owner、审计与可重建 LTE grid；`ThermalCellOperations` 只执行局部热平衡、reaction/phase 候选、机械功结算、能量重分配和展示派生，不拥有库存。

`GasThermalModel` 负责 gas node 状态函数，`ThermalTransport` 只做 pairwise 有限体积导热；fluid 层通过无状态 `FluidThermalBinding` 映射 snapshot/restore/reseed 与 gas compliance，运行时和 stage 直接使用 `ThermalDomainState`。

最终提交仍经明确 owner 入口完成，温度相关修正由对应热学处理器统一执行。

## 权威数据

- 液体 marker、active parcel、deposit group、coherent condensed group 和 sealed gas node 分别保存总内能 `U`。
- world thermal registry snapshot 保存材料 caloric segment、参考能、导热系数、标定温区、species offset、transition 和固定温度边界定义。
- external energy source、sink、mechanical work 与 reaction/phase transfer 都有独立审计列。
- 温度、热容、焓、密度修正、颜色和 tooltip 由状态函数派生，不持有或修复库存。

网络 thermal snapshot 只是有界显示投影：非空温度以 `1/16 K` 量化，超过 16-bit palette 时饱和到最高显示码；gas gauge pressure 同样饱和到 wire integer。该饱和不修改、拒绝或反向解释权威 `U`、真实派生温度与 EoS pressure，因此显示带宽不能成为模拟提交条件。

只有 liquid marker mass 是 solver weight，并通过 `SolverUnitScale` 的 `cellSize³` 边界换算为 kg。active parcel、deposit、coherent phase、气体、chemical species/residual 和有限设备的质量已经是 kg，不再缩放；相变只在实际更新 liquid marker 时把搬运的 kg 换回 solver mass。

同一载体的 identity、质量/体积、组成和 `U` 必须同 ordinal、同 generation。空载体不能残留内能；非空载体不能缺内能。cell 温度是把其中所有载体带入 LTE 反演后的临时结果，不是另一份可独立恢复的原始数据。

各能量 owner 的增量写入口只在 thermal 包内可见；跨包只获得读视图或由 `ThermalDomainState` 暴露的明确事务入口。跨 owner 计划绑定 liquid、granular/coherent、gas 与 audit 四项 revision 的完整元组，不能把它们求和压成可能碰撞的单值；gas remap 和 operator clear 在第一处库存修改前完成完整 source/sink/work/roundoff dyadic 链的构造。

后续任一 owner generation 不匹配或精确账本溢出都拒绝整个计划。handoff 对 coherent snapshot 只调用独立的 canonical validator，不能借公开 restore 入口改写 live store。

热学 registry 是 world snapshot：

- material 定义 carrier kind/id、参考温度、参考比内能、导热率和最多 16 个 caloric segment；`DEVICE` 专用于 region-owned 有限热容量设备，不混入 solver carrier ordinal；
- species offset 定义某 host material 中每 AU 的参考内能偏移；
- gas component 允许的 species 在 chemistry 启用时必须全部具有对应 host gas species offset，避免库存先提交、温度反演后失败；
- transition 定义上下相、分段线性 `T_eq(p)` 曲线、exact 两侧体积量子、exact 质量批和接口要求；曲线端点记录压力采样范围，不重复保存参考温度或上下限；
- boundary 定义哪些世界方块提供固定温度面。

总材料数、segment、species offset、transition、boundary、cell carrier 和 phase operation 均有硬上限。world fingerprint 或引用不一致时保留存档并 LOCKED，不按当前数据包重解释。

## 状态函数

每种载体用分段 caloric definition 计算 `U(T)` 并反演 `T(U)`。同格异质载体使用 LTE common-temperature inversion；状态函数不得读取客户端数据或经验 tick 倍率。

对质量 `m`、host material `h` 和 species 库存 `n_s`：

```text
U_h(T) = m * u_ref,h
       + sum_s(n_s * epsilon_h,s)
       + m * integral(T_ref,h .. T) cv,h(theta) dtheta
```

`ThermalStateFunction` 的液体、LTE 与候选求值共用最多 16,384 项参考能贡献缓存。每项绑定不可变的 host/species offset 定义和整数 quanta；命中时复用原有精确乘除后得到的 binary64 贡献。质量参考项和逐 species 相加仍按调用路径原有顺序执行，质量与 residual 不参与物种贡献的缓存键。槽位冲突重新精确计算，原子发布完整缓存项保证并发读取时定义、数量与结果一致。缓存随状态函数实例释放，首次出现化学参考能计算时才分配。

caloric segment 内 `cv` 为常量，所以积分与反演都是分段闭式线性计算，不用迭代近似。声明的首尾温度是数据标定范围而不是库存可表达性的硬墙：内禀候选落在表外时，状态函数沿用最近端点 segment 的 `cv` 连续延拓 `U(T)`；不钳制温度，也不改变权威 `U`。

端点定比热外推保证数值连续，极远处的相态精度仍受热量模型限制。例如内置水、冰的外推内能差在约 112.5 K 以下反号，极冷液态初态可能没有向平衡温度靠近的守恒冻结批次，求解会保留当前相态并继续推进。高压熔点曲线延伸本身不会修正这项低温热量近似。

异质 cell 在所有 carrier 合并后的 segment 边界上反演 `sum U_i(T) = U_cell`，因此即使声明温区不重叠仍有唯一 LTE 温度。严格正温数值域下界是 binary64 的最小正常正数，不是 1 K 或其他经验墙；有限过程可连续逼近并在 UI 舍入显示为 `0 K`。

### 批量格温度观察

液体黏性与颗粒载体属性工作区复用相变扫描的 `preparePhaseScan` + `phaseScanTemperatureInCell`：先一次建格，再逐格读取。这条路径只更新派生 LTE grid，**不重新分配也不修改任何载体 `U`**。

`observeTemperatureInCell` 用于独立的局部只读观测，保留调用内的 inventory 列表。它还会扫描全域固相库存，不用于「每 parcel × 8 支撑格 × 每子步」的热点。后端恢复为每份私有候选创建独立的热学状态，批量工作区随该候选使用。

批量读取的缓存只在明确的阶段内有效，跨库存、组成、内能或位置修改即失效；它不进入 `FluidDomainState`，也不进存档，恢复后重建。

只有能量低于该数学域、数值非有限或热容量非正才拒绝候选。固定温度 reservoir 等外部设定值仍限于 committed registry 的共同标定闭区间，不能借延拓注入任意边界条件。

相变平台由上下相在 `T_eq` 的状态函数能隙定义。能量落在两端之间时，派生温度保持 `T_eq`，相分数由 exact phase inventory 决定；相分数本身不是库存列。

候选状态必须同时满足：

- 所有材料在严格正温域内都有可逆的 `U(T)`/`T(U)`；
- transition 的 pressure/temperature 有效域；
- 有限、正的热容量与密度；
- 精确库存和能量审计闭合。

相平衡 pressure curve、外部设定值或严格正温域越界时报告载体、候选温度/压力和区间并安全暂停；材料 caloric 标定端点本身不再触发暂停，且任何路径都不能钳温或丢弃能量。

## 热输运

- 导热使用有限体积 face flux，同一 face 的两侧能量变化严格相反。
- 流液接触面积来自 PLIC/cut-face；solid、deposit、coherent 和 gas 使用各自实际 occupancy/available volume。
- 普通世界边界默认绝热。registry 声明的 fixed-temperature 方块和 `thermal_reservoir` 是无限热浴，但只经相邻 face flux 进入 source/sink。主线程解析得到的 face 温度场不是持久库存，却必须作为本次 immutable backend input 冻结并完整交给候选；候选提交后仍由下一次世界采样替换，region HEAD 不以该派生场恢复热库存。
- 液体热膨胀使用 registry-bound Boussinesq 定义，只向现有 y-face 动量路径提供派生体力，不改变质量或体积原始数据。
- 气体孔流同时搬运组分与能量；species 的 host-gas 状态能进入气体温度反演与相变批能量，压缩/膨胀功进入 mechanical work。

`thermal_reservoir` 的 setpoint 原始数据由世界 BlockEntity 持有，不进入 region HEAD。`ThermalReservoirUseCase` 只拥有 bounded summary、world-position token 与权限/范围决策；Minecraft adapter 才能在 token 校验失效处理和当前方块重验后写 BlockEntity。

写入只改变未来固定温度 face flux，随后刷新相邻已加载区域并以 `BOUNDARY_CHANGE` 唤醒 thermal activity；不得把新设定值反算成一笔直接注入 region 的 `U`。

### 有限热容量设备

`FiniteThermalBodyState` 是 loader-neutral 设备 body 原始数据：绑定 world thermal registry fingerprint、稳定 `DEVICE` material ID、generation、kg 和总 `U`。

`FiniteThermalDeviceState` 另存 provider ID、provider 内稳定 object ID 和 device type；这些字段只定位世界对象或外部 Mod 端口，不拥有能量。规范排序后的 `FiniteThermalDeviceInventory` 作为 manifest-only extension 随同 region HEAD 提交，空集合用 extension 缺席表示。

codec 回读时重新验证 registry fingerprint、`DEVICE` material、material carrier ID 与 device type；BlockEntity 不能另存一份可反向覆盖 `U` 的 setpoint。

`FiniteThermalExchange` 对一个设备 body 和最多 16 个独立流体能量端口做有界显式规划。每个端口提供当前温度、当前 caloric interval 或端点延拓区间内的热容、包含流体/壁面/接触面积热阻的总 conductance，以及该线性化区间。

每个 substep 使用同一 body 温度计算镜像通量，设备 `U` 的变化与各端口增量加总，并把 IEEE-754 总和差记为 exact dyadic roundoff。稳定步长同时受设备所有已声明 segment 中最小 `0.5*C_body/sum(G)` 与每个 `0.5*C_port/G_port` 限制；不能只取设备初始温度的热容，否则跨入低热容 caloric segment 后会失稳。

超过冻结 substep 预算、温度低于严格正温下界或数值不再有限时整体拒绝计划。

这个 planner 不搬运任何物质，也没有世界、region HEAD 或 block entity 提交权。同一区域路径先批量重建一次 LTE grid，冻结每个独立 cell port 的温度、热容和当前线性区间；同一 cell 的多面 conductance 必须先聚合，不能伪装成多个能量 owner。

body candidate、所有 cell `delta U`、solver `finiteDeviceExchange` 与两侧 roundoff 全部预检后才修改载体，提交异常会恢复 carrier U；下一 checkpoint 把 body 与流体一起交给 HEAD。

接触多个区域时不得调用这条单区域路径，必须先验证全部 candidate，再通过跨 region HEAD 协调提交。当前尚未接入世界对象 enrollment、接触几何生成和生产 tick 调度，因此固定温度 boundary 仍是唯一生产世界热边界。

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
dt_stable = min_cell(0.5 * C_min_cell / sum_faces G_face)
```

自动分 substep；`C_min_cell` 汇总各载体在全部 caloric segment 的最小热容，覆盖跨段更新，`0.5` 保证一对相同 cell 单调趋近平衡。所需显式 substep 超过 8 时，采用逐面后向 Euler 求解：正序、逆序各推进半个 logical step，热流由交换后的两端温度确定。每个面的标量能量方程以两端初始温度给出区间，使用当前分段热容求根。这是时间一阶的分裂离散，时间细化控制其截断误差。

所有导热接触两端温度逐值相等时，以一个零交换步骤完成几何校验和 cell 内载体的 LTE 分配。有限设备与全部端口等温时采用相同规则，保留设备代次和原始内能。hot loop 使用 `double`，每个 face 对的增减严格镜像；commit 边界用 `SignedDyadic` 精确求和记录 IEEE-754 有限数，舍入残差单列保存。

批量求和按 binary64 固有的 2,046 个 dyadic exponent 分桶，每桶以有符号 128-bit 两字累加后只做一次 `BigInteger` 合并；它与逐项 exact add bit-for-bit 等价，不是近似归约，也不会按载体种类跳过审计。

内置 `mmm:air` 按 [NIST 干空气组成](https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=921756)中的 N₂/O₂/Ar（CO₂ 在 registry 中是独立 component）以及 [N₂](https://webbook.nist.gov/cgi/cbook.cgi?ID=7727-37-9&cTG=on)、[O₂](https://webbook.nist.gov/cgi/cbook.cgi?ID=C7782447&Mask=1001&Table=on&Type=JANAFG)、[Ar](https://webbook.nist.gov/cgi/cbook.cgi?ID=C7440371&Mask=11) 的 caloric 数据，在冻结标准态由理想气体关系得到 `rho = 1.2038886846941566 kg/m³`，并把 `Cp-R` 的区间焓积分换算为覆盖 `100–1700 K` 的七段质量比 `cv`；标准态所在的 `250–350 K` 单独成段，其余边界随物性曲率展开，避免宽区间平均值歪曲常温声学闭合，也不把热表查找膨胀到 material 的段数硬上限。

表外内禀状态使用首尾 segment 的 `cv` 连续延拓。内置水在 293.15 K 使用 `rho = 1000 kg/m³`、`cv = 4184 J/(kg·K)`、`k = 0.598 W/(m·K)`；水蒸气的 `k = 0.025 W/(m·K)`，`cv` 则按 [NIST water-vapor Shomate 数据](https://webbook.nist.gov/cgi/cbook.cgi?ID=C7732185&Table=on&Type=JANAFG)换算为覆盖 `250–1700 K` 的五段标定数据。

一个 0.5 m cell 满水的热容量约 `523 kJ/K`；4 BV 水约 `16.736 MJ/K`。四个完整 reservoir 接触面总导热为 `2.392 W/K`，因此现实标定下温升本来就慢，极端热源只是高功率边界，不是比热缩放。

### 热浮力

液体采用 Boussinesq 近似，只在重力冲量中加入

```text
relative buoyancy = beta * (T - T_ref)
Delta u_y = g * dt * volume-weighted(relative buoyancy)
```

内置 `beta` 在 293.15 K 附近冻结为：水 `2.07e-4/K`、油 `7.00e-4/K`、汞 `1.82e-4/K`。该项不修改密度、质量或体积原始数据，也不适用于越出物性有效域的大温差外推。

`MaterialRegistry.MaterialEntry.thermalExpansion` 保存每种液体的 `T_ref`、`beta` 和允许的 `|rho(T)-rho(T_ref)|/rho(T_ref)`。此处参考态使用同一组成的实际质量/体积密度；热密度修正为 `rho(T) = rho(T_ref)*(1-beta*(T-T_ref))`。组成差异继续由变密度压力离散计算。

内置相对热密度变化上限为 `0.05`，闭区间内计算上述线性模型，越界抛 `ValidityExceededException`，后端拒绝完整候选并使生产区域数值暂停。这个上限约束 Boussinesq 小密度变化近似；常数 `beta` 的标定精度仍受参考温度附近的物性数据限制。零重力计算省去热浮力，极端 caloric、气体状态和相变夹具在微重力条件下验证各自的有效域。

热浮力复用 `LiquidCarrierProperties` 的批量 LTE 温度，同格按逐 marker 体积汇总各自已绑定的膨胀定义，再按两侧真实 marker 体积求面平均。物性观察保持所有载体 `U` 的原始位模式。热区域 manifest 的浮力身份绑定公式与空间平均约定，材料参数由世界材料指纹绑定。

静水分层夹具要求最大粒子速度小于 `1e-8 m/s`。组成反置夹具要求盐组分质心穿过池体中面；温差与热盐竞争夹具检查热量或盐组分质心的运动方向。机械诊断分别记录黏性耗散、P2G/G2P 能量变化和热库存收支。

当前热学载体按粒子所属单元汇总和分配内能。粒子在格内的小位移尚不改变热库存的空间归属，因此对背景温度梯度的线性热平流响应存在分辨率限制。自然对流的临界 Ra 与增长率收敛仍待连续热平流离散完成，验收入口见[产品路线](../roadmap/product-roadmap.md#1c-密度分层与对流)。

### 气体能量与功

gas node 的共同温度由各 component 的质量、species 和 node `U` 反演；非等温绝对压力为

```text
p = P0 * (Vstd / Vavailable) * (T / T0)
```

几何改变使用分段常 `cv` 的理想气体等熵闭式积分：逐段满足 `∫Cv(T)/T dT = R ln(V_initial/V_final)`，在各 component caloric 边界间以指数式求解，必要时精确走过多个 segment，并在首尾标定段之外使用同一端点 `cv` 继续解析路径；`Delta U` 记为进入 gas inventory 的 mechanical work。

该过程没有迭代容差、温度钳制、1 K 停止墙或跨标定端点拒答；只有结果低于 binary64 严格正温数值域、指数溢出或状态/能量非有限才拒绝候选。孔流搬运源状态焓 `h = u + p*v`；node 合并时能量相加，分裂时所有 child 保持 parent 温度且最后一个 child 接受浮点余数。

空 component inventory 是显式真空而不是缺失数据：其 `U`、绝对压力、密度和任意无传质体积变化功都严格为零。显示温度使用区域插入温度作为约定值；持久化与 remap 必须保留空 inventory/零 `U`，不能以环境状态补齐。

## 相变

相变功能依赖热学功能。每个 transition 用完整最小守恒量子定义，运行时按可用能量、库存、目标容量、饱和条件和统一操作预算求最大整数 extent。整格组成按 registry 物种容量聚合，目标载体分别验证物种槽位与量子容量；提交前完成液态区域容量预留，凝结优先选择可承接的已有 marker，再使用可用的新 marker 槽位。

logical thermal axis 在最后一次真实 carrier 修改后从全部载体构造派生 LTE grid；若 chemistry 与 granular phase 统计都证明零提交，后续固液和气液扫描复用导热留下的同代 grid，独立 phase 入口则自行重建。

无相库存、已处于平衡或方向上没有可搬运载体的 cell 不构造完整 inventory；只有首个可执行气液 interface 才生成液体和固体热平衡候选对象。deposit heat-bath 通过目标 cell 的 canonical sparse group 视图读取，不为每个 phase cell 扫描全 region。

气液 extent 的二分候选冻结该 cell 的 exact dyadic 总 `U` 与不变 solid heat-bath inventory，只替换候选 liquid inventory。真实 phase commit 后重建载体 ordinal，但只按守恒余量重新求解被编辑 cell；未编辑 cell 必须已有同代 LTE 状态并原样保留，不能借此反算或改写其 `U`。

若沸腾候选移走最后一个凝聚相载体，而分配给新气体的 `U` 不能同时使该 cell 的剩余 `U` 精确为零，则该 extent 只是没有可用热浴的不可执行候选；二分规划缩小或跳过它，不得把仍然合法的候选前库存暂停为“空 cell 保留能量”。只有已经提交的空 carrier cell 实际持有非零 `U` 才是状态不变量失败。候选热浴逐 marker 使用计划提交的剩余质量与组成构造，完整移除的 marker 不再贡献热容量。气液能量交换按气体前后实际 binary64 内能的精确二进制差计算，避免大气腔的能量舍入掩盖空载体中的余能。

固液和气液相变的容量检查共用 `PhaseChangeStageSupport`：活动颗粒按连续位置的三线性权重分摊整数 SVQ，沉积按所属单元计入；coherent 库存由相变层另行计入。这与运动、沉积及恢复检查使用同一份空间归属规则。

导热若所有 face 与 reservoir 的实际通量都严格为零，并且每个 carrier 从自身 raw `U` 恢复出的温度已位于该 cell 的 LTE 容差内，则保持全部 carrier `U` raw bits 不变；否则仍执行完整 cell 能量分配与验证。这里的短路只识别数学上的零转移，不把小通量钳为零。

### 固液

- 联合搬运物理 kg、condensed weight、液体 marker、coherent SVQ、材料身份、可选 chemical composition/species 和 `U`。coherent 部分拆分先分配整数 species，再由实际移出质量减去移出 species 质量求残差，保留部分接收剩余残差，各份质量列分别闭合。
- 每条 transition 在该 lower coherent phase 或目标 upper liquid 存在时查询压力并规划。同格其他 liquid/coherent carrier 保留材料和组成，并以原有体积参与整格容量约束、以内能参与 LTE heat bath。
- coherent group/组成池、液体 marker 总预算或 coherent exact split 暂时不能表示完整批次时，该批次零修改延期；只有已提交 coherent 几何与 cut-cell 容量矛盾、组成 capability 不匹配或账本不闭合才暂停合法候选链。
- 相变按固定区域体积联合守恒两相内能。体积变化记录为待处理几何，下一次压力投影完成力学同步后清除；内部相界位移不另记外部 `-pΔV` 功。
- 固液规划从最近一次真实 projection 的 cell gauge pressure 加环境压力得到绝对压力，再取 `T_eq(p)`。hydrostatic thermal-only 私有候选冻结并传递该 projection，但它不是库存列、不进入 checkpoint、也不能反向恢复物质量；冷恢复先执行一次 motion 重建 projection。曲线使用 registry-bound、按压力严格递增的分段线性点；固液相变在数据范围内插值，在范围外沿最近端点的割线斜率延伸，低压端延伸至真空压力。端点表示已知数据的覆盖范围。相变温度必须保持有限且为有效绝对温度；超出热容采样温区时，沿用材料状态函数已有的端点热容延伸。registry 校验采样点的上下相能量差为正。常温曲线也显式给出两个端点。液气曲线仍须覆盖标准压力，用于定义标准体积和潜焓参考态；液气方向通过分压与饱和压力计算，不使用固液线性延伸规则。

内置冰/水 transition 每批为 `0.001 kg`，两侧分别为 `146 SVQ` 与 `134 SVQ`。熔化曲线采样自 [IAPWS R14-08(2011) 方程 1–5](https://iapws.org/public/documents/MdUFK/MeltSub2011.pdf)，覆盖 Ice Ih、III、V、VI、VII 的稳定熔化分支，最高采样温度为 `715 K`；更高压力沿最后两个采样点的割线延伸。低压段保留原有 `611.657 Pa–2 MPa` 细分采样。生成脚本见 [Python 工具](../maintenance/pytool.md)。游戏内各冰相共用一个固态载体及固定体积批；这保留了高压熔点变化，尚未表达各冰相的密度差异。

规划器在共享 phase-operation 预算内寻找 exact batch lattice 上离 `T_eq(p)` 最近的可表示状态；候选必须严格缩小温差，跨过平台时只在跨越后的格点更近时提交，随后保持稳定，不允许每步冻融往返。

由 batch 大小造成的剩余温差是明确量化误差；库存、容量或操作预算先到达时停止。

### 气液

- liquid water 与 water vapor 使用同一个 transition 的质量、潜热和 exact standard-volume batch。
- 沸腾/凝结由 water-vapor 分压、温度和 transition 饱和压力关系决定，并受 sealed/ambient gas inventory 容量约束。
- commit 同时更新 liquid marker、gas component nanos/kg、gas node energy、source/sink 与 topology remap；任何列不闭合则整批拒绝。
- 每次气液 commit 还按实际增加/删除的 liquid SVQ 写入带 cell 的 exact pending volume；下一次真实 projection 完成力学同步，随后清除该 entry。capture/restore 必须保留尚未结算的原始 pending 值。
- 同一 transition、gas node 与 exact liquid temperature 在一个 logical step 内只有一个热力学方向；首次观察到的沸腾或凝结方向是该状态点的唯一方向，后续候选若试图在同一状态点反向则不提交。不同温度的热/冷界面仍独立求值。
- exact batch 搜索以 `|p_sat-p_partial|` 为物理残差；跨越平衡点的最小批只有在严格缩小该残差时才提交，不能因“至少执行一批”在两个可表示状态间往返。
- 一个液体 cell 同时接触多个互不连通的 sealed node 是合法界面，不得因此暂停或合并气腔。一次 pressure interval 内该 cell 只向一个 node 提交一次守恒交换：按 `|p_sat-p_partial|` 从大到小尝试，最强候选若受库存、能量、组分或容量约束无法执行再尝试下一 node；相同驱动力按稳定 node ID 决定顺序。
- chemistry 启用时，每个 gas transition 先把允许挥发且具有 host-gas energy offset 的 stable species 编译为本步 runtime handle 集；液体 marker 仍按 ordinal 搬运 exact species、质量 residual 与 `U`，runtime handle 不持久化。

水/蒸气的方向由 ideal-mixture component 分压和数据化的 Clausius–Clapeyron 饱和关系决定：

```text
p_sat(T) = P0 * exp[-L/R * (1/T - 1/T_eq)]
p_partial = p_absolute * Vstd,vapor / Vstd,total
```

`p_sat > p_partial` 允许沸腾，反向允许凝结，相等时不转移。这里 `L` 从上下相 `U(T_eq)` 加 `p*Delta V` 推导，不另存一个可能漂移的潜热常量。transition 的压力曲线只在标准压力处定义参考平衡态；当前总气压不是 Clausius–Clapeyron 的独立输入，因此有限非负总压（包括真空）不会因落在固液曲线端点外而被拒绝，实际方向始终由蒸气分压与液体温度决定。模型不单列临界流体；气体密度直接由库存质量除以实际可用体积得到，可连续接近液体密度，不设置密度上限或额外相图直线。

内置 batch 为 `0.001 kg`：液体体积 `134 SVQ`，沸点体积 `228122 SVQ`，换成标准状态约 `0.001335254 m³std`；这组数给出约 `1000 kg/m³` 液水、`0.588 kg/m³` 沸点蒸气和 `461.52 J/(kg·K)` 水蒸气气体常数。上下相状态函数在 1 atm 给出 `2.2567 MJ/kg` 汽化焓。

每个 logical step 的固液与气液共享最多 4096 次 phase operation；按 0.05 s step 和 1 g batch，理论数值上限为 `81.92 kg/s`。这是防止无界工作量的预算，不是现实相界动力学。真实速率由传入的有限热通量、库存、容量与饱和条件限制；若量化 fixture 需要界面动力学，应新增有适用域的数据模型，不能把 tick 倍率塞入潜热。

气液候选先用当前 marker geometry 同步 gas topology，再规划 inventory/energy。凝结和固液相变的体积增长须同时满足局部容量与全域凝聚态库存的剩余容量；密闭域仍有气体库存时，必须留下严格正的气体体积。同一 liquid thermal material 的候选状态函数可把 cell 内剩余 marker 聚合为一份 extensive inventory；实际删除、截断、species 与 residual 搬运仍保留逐 marker exact plan。

每次真实气液 commit 后都从 carrier truth 重建 cell-marker ordinal 并完整重扫 gas topology：marker ordinal 存活并不能证明 air membership 未变，partial cell 可能被填满或越过 interface 判据。

零库存、零内能的真空节点可在拓扑重映射时缩至零体积，压力和密度保持零；非空气体仍要求严格正体积。密闭全域容量已经包含已有气腔控制体积；未归属局部空隙只能按全域新增容量继承真空空间，避免局部几何权重与归一化体积重复计算压缩功。

该重扫联合搬运 gas inventory/U 与 lineage，保持相变两相总内能不变。固定区域内的相界位移属于内部交换，不能再作为外部机械功单独扣除。随后液体和颗粒真实运动改变气腔容量时，仍按新旧几何执行气体等熵功；相变记录只要求力学同步。

力学 P2G、pressure footprint/PLIC、表面张力和速度外推统一在 logical step 末重建一次，capture 前也从完整 geometry 重扫。这样任何可发布 logical state 都不会出现 marker 已变而 gas node 仍属于上一拓扑的半代次，也不会因容量快路提前结算一次、projection 再重复结算一次体积功。

## 反应热

化学和固液反应先完成 exact extent 规划，再将定义的能量变化提交给同一 cell 的热载体。反应热不得绕过 chemical/solid 账本单独修改温度。

species reference offset 是组成携带的能量原始数据。reaction extent 改变 species 时，反应热表现为同一总 `U` 下 base energy 的改变，随后 LTE 温度自然变化；外部热浴或命令注热才进入 external source/sink。禁止既修改 species offset 又额外重复加一份反应热。

## Logical step 顺序

1. mechanics、pressure 与 advection；
2. 在 conduction 前冻结本步颗粒反应 dirty cell；
3. carrier handoff 和有限体积 conduction；
4. 液相 chemical reaction（species 与 reaction energy 在同一 owner plan 中结算）；
5. granular precipitation/dissolution（liquid/solid composition、SVQ、质量、U 与 pending volume 联合结算）；
6. 固液 phase transition，再以剩余共享预算执行气液 phase transition；
7. granular transition、reseed 与局部唤醒；
8. 审计闭合后，pending phase-work 留给下一 projection。

每一步都先在快照上形成候选。相变产生的体积改变不能回头修改已经完成的 projection，因此作为带 generation 的 pending volume/work 保存，并且只由下一次真实 projection 结算一次。

marker 已经包含相变后的几何体积；pending 仅用于标记下一步力学网格同步，不重复引入散度源与能量变化。两相内部交换已在相变步骤守恒结算；压力投影后的运动照常产生可审计的动能与气体容量变化。

物质转移前需要完成已有相变几何的力学同步。Minecraft 管理员操作、玩家容器和发射器共用同一处理过程：

1. 适配器提交仅含力学的私有候选，完成压力投影、颗粒运动与沉积、守恒粒子拆并。
2. 这次计算跳过导热、化学反应和相变，同步已有几何后清除待处理记录。
3. 确认结算成功，再进入物质或容器事务。失败时保留待处理记录，并阻止清空、非完整存档和跳过压力投影的转移。

代码入口见 [相变几何同步](runtime-and-lifecycle.md#phase-work)。

## 能量审计

每次可发布状态必须满足：

```text
U_inventory
= U_initial + external_source - external_sink
 + mechanical_work + finite_device_exchange + roundoff_residual
```

`U_inventory` 分别汇总 liquid marker、active parcel、deposit、coherent group 和 gas node。载体间 conduction、reaction carrier handoff、phase transfer、gas merge/split 都是内部转移，不进入 external source/sink；只有 ownership 边界物质进出、固定温度 reservoir 或 operator source 才改变外部列。

有限设备位于 solver carrier 边界外、region HEAD 边界内：流体侧把实际请求量记入 `finite_device_exchange`，cell 分配舍入只进 solver roundoff；设备侧独立保存相反的 `solver_exchange` 与 planner roundoff，并要求 body 总 `U` 等于其 expected inventory。

两份 roundoff 相加必须等于 region 内 body+fluid 的实际总 `U` 变化。audit 不闭合时保留候选前状态并 PAUSED，不能通过重算温度“修好”。

管理员清空也遵守同一审计：`EMPTY_CONDENSED` 把 liquid/active/deposit/coherent 的实际 `U` 记入 external sink，`VENT_SEALED_GAS` 把全部 sealed gas node 的实际 `U` 记入 external sink，并与对应物质库存及 topology 在同一私有 candidate/HEAD 中提交。预览展示的能量来自权威总 `U`，不是由温度反算；有限设备非空时 ownership 释放与区域移除都被阻止。

## 持久化与容器

- 完整热区域 generation 必须联合保存 marker/solid/gas topology、所有载体 `U`、audit、Boussinesq identity 和 pending phase-work。
- capture 前执行 gas topology synchronization，确保 marker geometry、gas membership 和 gas energy ordinal 同代一致。
- `ThermalContents` 将物品中的液体物质原始数据与同一 registry fingerprint 下的总内能绑定；桶内只假设一个平衡温度，该温度由完整质量、组分和总 `U` 反演，不另存第二份温度原始数据。若界面展示温度，它只能是只读派生。
- 纯水等同质库存可以按实际体积分拆 `U`。材料或化学混合物的部分倾倒必须先从源桶完整库存和 `U` 反演共同温度，再按实际选中材料质量、species 与 reference offset 计算搬运能量；余下 item `U` 用源 `U` 减去搬运量得到。禁止在密度、热容或组成不同的库存上用总液量比例分热能。没有 region/WAL roundoff owner 的工作台配方不得剥离或合并带 `ThermalContents` 的物品。
- 多 marker 抽取先以 raw binary64 `U_before-U_after` 的 exact dyadic 和确定真实 carrier 能量变化，再只在整件物品边界舍入一次；region external sink 必须等于最终 `ThermalContents` 的 raw `double`，两者与 exact carrier 变化的差只记入 `roundoff_residual`。不得逐 marker 舍入后再把近似和冒充物品能量，也不得用放宽 handoff 审计掩盖差额。
- 容器 WAL 在含热转移中同时保存 exact liquid amount、item before/after 和 coupled energy；物品 durable save 前不得清理。

section payload 逐 ordinal 保存 liquid/active/deposit energy raw bits 和 coherent group；manifest 保存各载体 exact dyadic 汇总、gas node energy、audit、registry identity、phase-work 及 section ledger。恢复时先验证 cardinality 与所有汇总，再构造 solver；派生温度不落盘，也不用于补全缺失 `U`。

## 调度与观察

motion 和 thermal activity 是正交轴。只有温度残差、边界通量、反应/相变或局部几何变化达到唤醒条件时才推进热学；休眠不得改变任何库存。

`status` 可读取全域温度范围、总 `U`、source/sink/work、phase batch 和气体节点摘要。Query API 可返回单格权威载体反演出的可选温度；查询只读 marker/side columns，不唤醒、不建网格，也不回写热状态。

thermal activity 与 motion pacing 相互独立：有 face transfer、reservoir exchange、finite-device exchange、chemistry/granular/phase dirty、gas activity、carrier motion、pending work 或未闭合 audit 时保持 ACTIVE；连续安静观察先进入 REDUCED，再进入 SLEEP。

内部导热即使没有外部 source/sink 变化，也会改变热浮力和气体压力。`ThermalActivity.Observation.requiresMotion` 将非零面热量转移、温度变化及已有反应/相变活动一并交给力学调度：单独热计算后唤醒力学，联合计算后阻止静水休眠。此观察不修改库存，玩家暂停仍优先。

boundary、容器、管理员 source、有限设备、化学、颗粒、气体、相变或 carrier movement 都是精确唤醒原因。

PAUSED 覆盖运行时内能、压力、已提交相库存的守恒或几何不变量失败，以及有限热设备的步数预算耗尽。registry identity、持久 payload、ordinal/generation 身份损坏归入 LOCKED，恢复依据来自权威状态与事务证据。
