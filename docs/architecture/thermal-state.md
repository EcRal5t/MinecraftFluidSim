# 内能、热输运与相变契约

修改内能、热传导、反应热、相变和热设备时读本文。

## 修改入口

| 要改什么 | 代码入口 | 测试选择器 |
|---|---|---|
| 内能状态与流体数据映射 | [ThermalDomainState](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalDomainState.java)、[FluidThermalBinding](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidThermalBinding.java) | `thermal` |
| 热浮力有效范围与分层 | [LiquidThermalExpansion](../../model/src/main/java/cc/ecisr/mmm/thermal/LiquidThermalExpansion.java)、[LiquidCarrierProperties](../../solver/src/main/java/cc/ecisr/mmm/fluid/LiquidCarrierProperties.java)、[LiquidStratificationValidation](../../fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidStratificationValidation.java) | `materials,thermal` |
| 传热与反应顺序 | [ThermochemistryPipeline](../../solver/src/main/java/cc/ecisr/mmm/fluid/ThermochemistryPipeline.java)、[ThermalCarrierTransport](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalCarrierTransport.java)、[LiquidThermalConduction](../../solver/src/main/java/cc/ecisr/mmm/fluid/LiquidThermalConduction.java)、[ThermalTransport](../../model/src/main/java/cc/ecisr/mmm/thermal/ThermalTransport.java) | `thermal` |
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

热学数据为凝聚相载体和气腔库存上的守恒 side column。内部能量单位 `EU` 在内置物性中按 SI 数值标定为焦耳；质量边界按 kg、长度按 m、时间按 s，热容为 `EU/K`，导热率为 `EU/(s·m·K)`。参考温度取物性基准态，总内能可为负值。

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

同一载体的 identity、质量/体积、组成和 `U` 必须同 ordinal、同 generation；非空载体持有内能，空载体零内能。单元温度为所有载体带入 LTE 反演的瞬态结果，由实时状态推导。

活动颗粒联合沉积先按最终 deposit identity 汇总原始 `U`，整体检查新增组数和有限值。每个目标组以精确二进制和计算一次 binary64 舍入，并预先构造相应 `roundoff_residual`。库存、组成与热计划全部核对当前代次后，按同一组降序 ordinal 删除活动载体，再提交完整沉积内能与账本。

活动颗粒、沉积和 coherent 同种库存的部分搬移，质量与内能都直接按 SVQ 比例求小份额，大份额承接精确相减后一次舍入的余量；两份列作为同一拆分结果参与预检、提交与能量审计，整体取出保留原始列。coherent 的整数物种沿最大余数分配，小份额化学质量残差由实际质量减去精确物种质量得到，大份额接收残差余量；相变候选使用同一拆分后的剩余组成。

各能量 owner 的增量写入口只在 thermal 包内可见；跨包只获得读视图或由 `ThermalDomainState` 暴露的明确事务入口。跨 owner 计划绑定 liquid、granular/coherent、gas 与 audit 四项 revision 的完整元组，不能把它们求和压成可能碰撞的单值；gas remap 和 operator clear 在第一处库存修改前完成完整 source/sink/work/roundoff dyadic 链的构造。

后续任一 owner generation 不匹配或精确账本溢出都拒绝整个计划。handoff 对 coherent snapshot 只调用独立的 canonical validator，不能借公开 restore 入口改写 live store。

热学 registry 是 world snapshot：

- material 定义 carrier kind/id、参考温度、参考比内能、导热率和最多 16 个 caloric segment；`DEVICE` 专用于 region-owned 有限热容量设备，不混入 solver carrier ordinal；
- species offset 定义某 host material 中每 AU 的参考内能偏移；
- gas component 允许的 species 在 chemistry 启用时必须全部具有对应 host gas species offset，避免库存先提交、温度反演后失败；
- transition 定义上下相、分段线性 `T_eq(p)` 曲线、exact 两侧体积量子、exact 质量批和接口要求；曲线端点记录压力采样范围，不重复保存参考温度或上下限；
- boundary 定义哪些世界方块提供固定温度面。

总材料数、segment、species offset、transition、boundary、区域实际载体预算和 phase operation 均有硬上限。同格 LTE 反演按完整实际库存求解，使用与真实热网格相同的区域载体预算。world fingerprint 或引用不一致时保留存档并 LOCKED，不按当前数据包重解释。

## 状态函数

每种载体用分段 caloric definition 计算 `U(T)` 并反演 `T(U)`。同格异质载体使用 LTE common-temperature inversion；状态函数不得读取客户端数据或经验 tick 倍率。

对质量 `m`、host material `h` 和 species 库存 `n_s`：

```text
U_h(T) = m * u_ref,h
       + sum_s(n_s * epsilon_h,s)
       + m * integral(T_ref,h .. T) cv,h(theta) dtheta
```

绑定同一不可变 `ThermalRegistry` 的液体、LTE 与候选状态函数共用最多 16,384 项参考能贡献缓存。每项绑定不可变的 host/species offset 定义和整数 quanta；命中时复用精确乘除后得到的 binary64 贡献。机器字宽内的贡献使用检查溢出的整数乘法与最大公约数约分，超出范围时使用有界精确有理数；两种路径保持规范分子、分母转换后的 binary64 位模式一致。先计算质量参考项，再按 stable species ID 的规范顺序相加；Map 与组成记录保持相同的 binary64 位模式，质量与 residual 不参与物种贡献的缓存键。槽位冲突重新精确计算，原子发布完整缓存项保证并发读取时定义、数量与结果一致。缓存随世界热学注册表释放，首次出现化学参考能计算时才分配；私有候选交接保留同一份定义与派生贡献。

不可变化学组成记录的 offset 查找使用世界热学注册表内按 thermal material 与 chemical species handle 索引的派生表；表与化学注册表对象身份一起发布。切换注册表重新推导，缺失的 host/species 定义仍在求值时拒绝。零参考能项直接返回零，其他项沿用精确有理数贡献缓存。

液体参考能按 ordinal 保存有界派生缓存，键包括 thermal material、质量的原始 binary64 位、完整不可变组成和化学注册表对象身份。温度与 LTE 载体视图共用该缓存；每次读取核对实际输入，质量、组成、材料或注册表变化立即重新求值，内能变化直接进入温度反演。

`LiquidEnergyColumn.CaloricInventory` 保存候选的不可变材料、实际质量、组成和已计算参考能。同一候选的 `U(T)`、`T(U)` 与舍入误差预检复用这份数据，候选期间保持实际载体库存和内能原样。

caloric segment 内 `cv` 为常量，积分与反演均为分段闭式线性计算。真实载体与异质库存共用反演器：从参考温度建立精确显热锚点，区间端点能量、质量乘热容和选区比较保留 dyadic 精度，区间内选择较近的端点计算温差，温度以 binary64 表示。实际解须具有有限正温与有限正热容。当前定比热段的快速求解以最终温度的 ULP 验证误差包络与所属区间；相消或跨段时使用完整分段计算。声明的首尾温度为数据标定范围：内禀候选超出标定范围时，状态函数沿用最近端点 segment 的 `cv` 连续延拓 `U(T)`，保持权威 `U` 守恒。

端点定比热外推保证数值连续，极远处的相态精度仍受热量模型限制。例如内置水、冰的外推内能差在约 112.5 K 以下反号，极冷液态初态可能没有向平衡温度靠近的守恒冻结批次，求解会保留当前相态并继续推进。高压熔点曲线延伸本身不会修正这项低温热量近似。

异质 cell 在所有 carrier 合并后的 segment 结点上反演 `sum U_i(T) = U_cell`，在全温区求解唯一 LTE 温度。实际载体、观察、反应与相变候选、提交分配和同种液体重建共用分段反演：原始内能、参考能、质量与显热曲线以精确二进制值比较和相减，选中温区后才舍入最终温度；单份物品的原始 double 内能沿用其权威数值。常见单份载体使用有温度误差包络的同段快速求解。正向求能量保留正常运算顺序；中间积分溢出时，精确合计质量缩放后的显热与参考能，再一次舍入实际内能。严格正温数值域下界为 binary64 最小正常正数；有限过程可连续逼近，UI 舍入显示为 `0 K`。

普通建格先验证每个真实载体的质量、内能与正温反演，再精确汇总原始 `U` 并求单元平均温度，避免有效平均值掩盖无效载体。格内分配先精确汇总所有拟写入值，优先由最大热容载体承接守恒余量；该载体无法在既有温度校验内表示余量时，按规范 ordinal 尝试其他载体。全部候选均不可表示时保持所有载体原样，通过后才提交并记录最终 binary64 舍入差。局部反应或相变重建时，被编辑单元使用已预检的守恒候选 `U` 完成重新分配，其他单元仍逐载体验证；相变的局部重建保留其他单元已求出的温度并刷新精确内能汇总。候选期间新增载体的临时能量列按该提交时点处理。

默认液体注入先规划整批载体在配置温度下的原始内能，逐份验证实际质量、组成和拟存储内能的正温与有限热容。首次写入前预检完整热源账本、预期库存的精确求和与热学代次；追加完成后一起发布规划内能和账本，热源保留实际存储内能的精确和。无法表示的候选保持全部库存、账本对象与修订号。显式总内能注入先反演共同温度，再逐份验证实际舍入后的载体内能、正温与有限热容。规划时预构建完整热账本并验证预期库存的精确求和；提交绑定原账本对象，统一确认全部目标内能槽后一起发布内能与已规划账本。可表示的舍入差保留在 `roundoff_residual`，外部热源保留请求内能。

颗粒单份与批量注入按实际质量、完整组成和拟存储内能逐份反演，验证正温与有限热容。首次写入前检查整批活动颗粒容量、完整外部热源字段与预期库存；提交一起发布内能列和审计账本，拒绝时颗粒、组成池、内能、账本与修订号保持原值。

外部追加 coherent group 时，传入库存与合并后的实际质量、完整组成和 raw 内能分别通过正温校验。库存写入前构造完整审计候选：source 保留传入内能，合并储存的精确能量差进入 roundoff；拒绝时库存、账本与修订号保持原值。

单元与批量预检保留原有温度、热容和 caloric interval，并在首次写入前验证每份拟存储内能及其精确总和。提交完成后，单元内能、温度与热容按实际载体列重新推导，扫描和即时观察使用同一份库存；请求目标与实际存储的舍入差进入审计。带候选内能的纯重建用于准备提交，后续显式内能分配发布实际结果。

载体校验与单元温度反演按独立索引范围执行，全部完成后再进入后续阶段。组成参考能的派生缓存先绑定本批次的 registry，库存与能量审计读取实际载体列。恢复热状态在私有候选中逐份验证快照内能、实际质量和完整组成，全部载体与审计通过后才绑定候选；液体、活动颗粒、沉积和气体节点直接使用快照内能。气体首次绑定逐节点验证实际存储内能的温度、热容与绝对压力。固定凝固物占据量与当前颗粒、沉积联合重建，液体容量始终包含全部固体载体。

### 批量格温度观察

液体黏性与颗粒载体属性工作区复用相变扫描的 `preparePhaseScan` + `phaseScanTemperatureInCell`：先一次建格，再逐格读取。这条路径只更新派生 LTE grid，**不重新分配也不修改任何载体 `U`**。

`observeTemperatureInCell` 用于独立的局部只读观测，保留调用内的 inventory 列表。它还会扫描全域固相库存，不用于「每 parcel × 8 支撑格 × 每子步」的热点。后端恢复为每份私有候选创建独立的热学状态，批量工作区随该候选使用。

批量读取的缓存只在明确的阶段内有效，跨库存、组成、内能或位置修改即失效；它不进入 `FluidDomainState`，也不进存档，恢复后重建。

局部温度编辑、液固反应与有限设备交换只重新分配被编辑单元的载体内能。其他单元保留每份载体的原始 `U`，格内温差继续由有限速率导热推进。

`prepareSpatialObservation` 提供独立的连续空间温度观察，供热平流验证使用。液体按 cell-center 三线性权重分配最多八份只读 caloric portion；支撑格须持有液体、完全开放且与原载体属于同一 pressure phase，各格之间沿完全开放面连通。外壳附近对剩余正权重归一化，partial cut-cell 与含多相单元沿用所属单元。其他凝聚态载体保持所属单元。

所有真实载体先由原始整数 species 库存求参考能并通过正温反演。每份 portion 的质量、内能和完整参考能沿同一权重保留精确乘积；求解前精确抵扣参考能，以原始 caloric 定义反演格温度。体积沿同一权重生成观察份额，species、质量和能量 owner 保持原样。正权重保证观察温度位于贡献载体的温度范围内；均匀 quadrature 的内域线性温度场可精确重建，外壳截断与 partial cut-cell 的误差需分别观察。

该工作区按需分配，重建支撑几何后读取，失败批次不能继续读旧结果。黏度与相变继续读取所属单元的 LTE grid。热区域的组成密度与热浮力复用这套正权重支撑，分别从真实质量/体积和逐载体温度推导。零导热平流用例使用散度为零的真实 MAC 平流、两个微小时间间隔和两级网格，验证空间温度和机械热浮力的格内一阶响应、载体 `U` 位模式和交接恢复。

只有能量低于该数学域、数值非有限或热容量非正才拒绝候选。固定温度 reservoir 等外部设定值仍限于 committed registry 的共同标定闭区间，不能借延拓注入任意边界条件。

相变平台由上下相在 `T_eq` 的状态函数能隙定义。能量落在两端之间时，派生温度保持 `T_eq`，相分数由瞬态相库存实时推导。

候选状态必须同时满足：

- 所有材料在严格正温域内都有可逆的 `U(T)`/`T(U)`；
- transition 的 pressure/temperature 有效域；
- 有限、正的热容量与密度；
- 精确库存和能量审计闭合。

相平衡 pressure curve、外部设定值或严格正温域越界时报告载体、候选温度/压力和区间并安全暂停；材料 caloric 标定端点本身不再触发暂停，且任何路径都不能钳温或丢弃能量。

## 热输运

- 导热使用有限体积 face flux，同一 face 的两侧能量变化严格相反。
- 流液接触面积来自 PLIC/cut-face；solid、deposit、coherent 和 gas 使用各自实际 occupancy/available volume。
- 普通世界边界默认绝热。registry 声明的 fixed-temperature 方块和 `thermal_reservoir` 是无限热浴，只经相邻 face flux 进入 source/sink。主线程解析的 face 温度场作为本次不可变计算输入冻结并提交给候选，由下一次世界采样替换，不进入持久化。
- 液体热膨胀使用 registry-bound Boussinesq 定义，只向现有 y-face 动量路径提供派生体力，不改变质量或体积原始数据。
- 气体孔流同时搬运组分与能量；species 的 host-gas 状态能进入气体温度反演与相变批能量，压缩/膨胀功进入 mechanical work。

`ThermalContactExchange` 求解单个有限热导接触的 backward-Euler 能量变化。载体接口绑定同一份初始质量、组成参考能与 `U`；混合单元使用其完整 caloric 曲线，单载体使用材料状态函数，固定温度面使用无限热浴。对于进入第一侧的能量 `q`，求解 `q = G*dt*(T_second(U_second-q)-T_first(U_first+q))`，有限第二侧接收相反的能量变化。求解跨越 caloric 分段，正热容保证唯一解及冷热温度顺序；零热导返回零，有限热导和时间步趋零时通量连续趋零。

单元导热接触表按正导热率载体数预分配：同格采用两两接触，相邻格采用共享面上的载体配对，外侧面预留有限热浴接触。关闭或绝热的面保留未用槽位；接触顺序与逐项热导计算保持固定。

隐式导热以正反两次半时间步扫描调用该求解器，调用方负责提交能量与记录热浴、舍入收支。凝聚态导热统一更新真实载体内能；满液体矩形域使用空间积分点接触，自由面、切割单元和固体混合域使用单元混合长度及 PLIC/cut-face 热阻。

### 满液体域的有限载体导热

`LiquidThermalConduction` 为完全开放、总体积填满区域、各格均有 marker 且属于同一 pressure phase 的矩形液体域构造接触。该路径读取真实位置、体积和已绑定的导热定义；区域中有 active parcel、deposit 或 coherent group 时采用单元有限体积路径。互溶材料可以有不同 caloric、导热率和 species 参考能。

接触采用正对称的张量积三次 B-spline 权重。令 `v_p=V_p/h³`、`d=(x_q-x_p)/h`，在标准积分点间距 `h/2` 上使用紧支撑权重：

```text
B3(s) = (4-6*s²+3*s³)/6                 for 0 <= s < 1
        (2-s)³/6                        for 1 <= s < 2
        0                               otherwise
f(d) = B3(2*abs(d_x))*B3(2*abs(d_y))*B3(2*abs(d_z))
M = (v_nominal/2) * sum_standard_neighbors f(d) * d_x²
G_pq = harmonic(k_p,k_q) * h * v_p * v_q * f(d) / M
Delta U_p = G_pq * (T_q-T_p) * dt; Delta U_q = -Delta U_p
```

`M` 由标准 `2×2×2` 积分点的离散二阶矩直接求出，使该规则的扩散率对应 registry 的 `k/(rho*cv)`。紧支撑随位置连续变化，载体跨格时邻域索引保持全部有效接触。外壳通过镜像积分点延续绝热支撑；恒温墙把镜像法向的一阶矩换成与真实墙距对应的正热导。均匀积分点上的线性温度场在相反恒温板之间保持稳定。

`ThermalCarrierTransport` 直接更新真实载体 `U`，每个接触保持正温、同质温度最大值原理与镜像能量。显式步长取逐载体全部 caloric 分段最小热容量的 `0.5*C_min/sum(G)`；超过八个显式子步时使用有限接触隐式扫描。提交前验证全部 prospective caloric 状态；失败恢复原始载体能量与派生格，成功后才记录热浴与舍入。零接触或零转移保持载体能量位模式，同格温差以有限速率推进。

单元接触先按拓扑分配独立数组区间，几何与恒温面回调按原顺序读取，再并行计算接触系数并按单元顺序压紧有效项。逐格状态函数求解和逐载体温度求值可独立并行；热通量累加、隐式接触扫描、库存校验与内能提交保持固定顺序。由此保留逐载体热量收支和原有离散结果。

该离散的精度条件是近均匀的体积积分点。位移、聚集和重播种改变离散矩，对应误差通过解析场和网格细化检查。两级扩散首模态的相对增长率误差约 `0.321%` 与 `0.0803%`。热学重播种保留总内能及同质载体的温度范围；合并按同温、同密度、同 species 浓度分组，空间矩约束保留同密度的仿射温度场。拆分复制父载体温度，线性温度场的子点误差受 `|grad T|*h*cbrt(V)/4` 限制，并报告质量加权 RMS；位移与累计空间矩误差界见[空间积分误差](fluid-solver.md#空间积分误差与分布诊断)。常态校验还覆盖小正导热率极限、线性恒温墙、冷热尖峰、异质 caloric/species 参考能、隔离载体及候选拒绝。

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

交换计划保存各端口的初始温度、热容和线性区间，提交时从当前载体重新推导并核对。cell 分配计划同时核对热学修订号、载体所属单元、原始内能和 caloric 库存，保障组成或质量变化后的计划失效。

body candidate、所有 cell `delta U`、solver `finiteDeviceExchange` 与两侧 roundoff 全部预检后才修改载体，提交异常会恢复 carrier U。成功交换递增求解器代次，使交换前的计算候选失效，并唤醒力学与热学以重建热浮力和气体压力；下一 checkpoint 把 body 与流体一起交给 HEAD。

接触多个区域时不得调用这条单区域路径，必须先验证全部 candidate，再通过跨 region HEAD 协调提交。当前尚未接入世界对象 enrollment、接触几何生成和生产 tick 调度，因此固定温度 boundary 仍是唯一生产世界热边界。

### 有限体积离散

自由面、切割单元和固体混合域使用 `ThermalTransport` 构造有限的载体接触图。令 `v_p=V_p/h³`、`v_cell=sum(v_p)`，同格与跨格接触为：

```text
k_pq = harmonic(k_p,k_q)
G_same_cell = 2*k_pq*h*v_p*v_q/v_cell
G_face = k_pq*A_contact/h*(v_p/v_cell_a)*(v_q/v_cell_b)
G_boundary = 2*k_p*A_contact/h*(v_p/v_cell)
Delta U_p = G*(T_q-T_p)*dt; Delta U_q = -Delta U_p
```

接触装配在当次固定载体拓扑上一次读取每个载体的导热率与体积，所有成对 conductance 共用这份派生数组；实际 `U` 的推进仍读取并写入对应载体。

生产导热入口先完整建格，接触求解期间保持位置、质量、组成和材料不变；结束后保留这份拓扑及 caloric 库存，按原载体顺序刷新内能和单元温度，并逐载体验证正温。公开的独立 `ThermalTransport.step` 继续完整重建末态，支持调用前更新载体库存。接触两端逐值等温时，在载体和接触预检后直接返回零交换，保留全部内能位模式。

接触图按原有生成顺序存于两侧 ordinal、conductance 与热浴温度的基础类型数组，显式累加与隐式正反向遍历共用该顺序。列表读取按需生成接触对象。

同格接触使用 `h/2` 混合长度近似未解析的载体间热阻；此路径的精度由单元尺度控制。`A_contact` 由 PLIC 液面和 cut geometry 限制在一个 face 面积内。固定温度面距单元中心 `h/2`，外露面默认绝热。气体孔流及气腔壁热交换由各自处理器执行。

显式 Jacobi 更新按

```text
dt_stable = min_carrier(0.5 * C_min_carrier / sum_contacts G)
```

自动分 substep；`C_min_carrier` 使用载体全部 caloric segment 的最小热容，覆盖跨段更新。所需显式 substep 超过 8 时，正序、逆序各推进半个 logical step，以有限接触后向 Euler 求解热流。这是时间一阶的分裂离散，时间细化控制其截断误差。

零导热载体保持原始 `U`，随粒子平流搬运。正导热载体的格内温差以有限速率变化，导热率趋零时通量连续趋零。所有载体在提交前逐一接受正温反演；同质绝热冷热尖峰的显式与大步长隐式用例检查载体温度范围和能量闭合。反应、相变与有限设备的局部能量交接继续由对应处理器执行。

所有导热接触两端温度逐值相等时，以一个零交换步骤完成几何与正温校验，保留载体内能位模式。有限设备与全部端口等温时也保留设备代次和原始内能。hot loop 使用 `double`，每个接触对的增减严格镜像；提交时用 `SignedDyadic` 精确求和记录 IEEE-754 有限数，舍入残差单列保存。

`SignedDyadic` 保存规范化的整数尾数和二进制指数。小尾数使用 signed long 精确相加、比较与单位转换；溢出或跨越机器字宽时切换到有界 `BigInteger`。需要写入 codec 时按需生成相同的规范整数表示，相等判定、舍入结果和持久格式共用这一数学值。

热账本六项的精确和与字段求值顺序无关，相消后的最终规范值接受尾数预算与指数范围校验。相交或相邻的二进制区间先做精确合并；不可表示的分离指数项在移位分配前拒绝，临时位宽随输入项的尾数位数有界。

批量求和按 binary64 固有的 2,046 个 dyadic exponent 分桶，每桶以有符号 128-bit 两字累加后只做一次 `BigInteger` 合并；该算法与逐项精确相加二进制等价，覆盖所有载体审计。

内置 `mmm:air` 按 [NIST 干空气组成](https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=921756)中的 N₂/O₂/Ar（CO₂ 在 registry 中是独立 component）以及 [N₂](https://webbook.nist.gov/cgi/cbook.cgi?ID=7727-37-9&cTG=on)、[O₂](https://webbook.nist.gov/cgi/cbook.cgi?ID=C7782447&Mask=1001&Table=on&Type=JANAFG)、[Ar](https://webbook.nist.gov/cgi/cbook.cgi?ID=C7440371&Mask=11) 的 caloric 数据，在冻结标准态由理想气体关系得到 `rho = 1.2038886846941566 kg/m³`，并把 `Cp-R` 的区间焓积分换算为覆盖 `100–1700 K` 的七段质量比 `cv`；标准态所在的 `250–350 K` 单独成段，其余边界随物性曲率展开，避免宽区间平均值歪曲常温声学闭合，也不把热表查找膨胀到 material 的段数硬上限。

表外内禀状态使用首尾 segment 的 `cv` 连续延拓。内置水在 293.15 K 使用 `rho = 1000 kg/m³`、`cv = 4184 J/(kg·K)`、`k = 0.598 W/(m·K)`；水蒸气的 `k = 0.025 W/(m·K)`，`cv` 则按 [NIST water-vapor Shomate 数据](https://webbook.nist.gov/cgi/cbook.cgi?ID=C7732185&Table=on&Type=JANAFG)换算为覆盖 `250–1700 K` 的五段标定数据。

一个 0.5 m cell 满水的热容量约 `523 kJ/K`；4 BV 水约 `16.736 MJ/K`。四个完整 reservoir 接触面总导热为 `2.392 W/K`。极端热源按高功率边界输入计算，不缩放比热。

### 热浮力

液体采用 Boussinesq 近似，只在重力冲量中加入

```text
relative buoyancy = beta * (T - T_ref)
rho_i = sum(w_pi*m_p) / sum(w_pi*V_p)
thermal density correction_i = sum(w_pi*m_p*beta_p*(T_p-T_ref,p)) / sum(w_pi*V_p)
Delta u_y = g * dt * thermal density correction_face / rho_face
```

内置 `beta` 在 293.15 K 附近冻结为：水 `2.07e-4/K`、油 `7.00e-4/K`、汞 `1.82e-4/K`。该项不修改密度、质量或体积原始数据，也不适用于越出物性有效域的大温差外推。

`MaterialRegistry.MaterialEntry.thermalExpansion` 保存每种液体的 `T_ref`、`beta` 和允许的 `|rho(T)-rho(T_ref)|/rho(T_ref)`。此处参考态使用同一组成的实际质量/体积密度；热密度修正为 `rho(T) = rho(T_ref)*(1-beta*(T-T_ref))`。组成差异继续由变密度压力离散计算。

内置相对热密度变化上限为 `0.05`，每个真实载体在空间平均前接受闭区间校验，越界抛 `ValidityExceededException`，后端拒绝完整候选并使生产区域数值暂停。这个上限约束 Boussinesq 小密度变化近似；常数 `beta` 的标定精度仍受参考温度附近的物性数据限制。零重力计算省去热浮力，极端 caloric、气体状态和相变夹具在微重力条件下验证各自的有效域。

`LiquidCarrierProperties` 在同一三线性支撑上汇总组成质量、体积和逐载体热密度修正，两个密度字段再按 pressure face 使用的界面距离加权。相邻单元没有 marker 中心但具有正 P2G 质量的开放 Y 面，按实际 P2G 核和粒子质量平均热密度修正。每个载体的温度由实际 `U`、质量及 species 参考能反演；对应的膨胀定义来自已绑定世界材料条目。物性观察保持所有载体 `U` 的原始位模式。热区域 manifest 的浮力身份绑定公式、空间支撑和面平均约定，材料参数由世界材料指纹绑定。

静水分层夹具要求最大粒子速度小于 `1e-8 m/s`。组成反置夹具要求盐组分质心穿过池体中面；温差与热盐竞争夹具检查热量或盐组分质心的运动方向。机械诊断分别记录黏性耗散、P2G/G2P 能量变化和热库存收支。

热盐补偿夹具将逐载体的 `rho_p*(1-beta_p*(T_p-T_ref,p))` 设为同一参考密度，验证格内平流后两项仍精确对应，以及压力投影后保持静水。MAC 使用 binary32；补偿场的速度残差上限取一份重力预测冲量的 ulp，当前默认网格、`g=9.81 m/s²`、`dt=0.05 s` 下为 `2.98023e-8 m/s`。反置分层经 CPU 后端的整步 CFL 细化计算，并逐步检查完整候选的库存与能量。

热浮力保留格内小位移的连续响应。凝聚态几何统一按有限载体接触导热；自然对流的线性增长率由 `thermal` 常驻校验。

### 自然对流的线性有效域

受热底板夹具采用满液体封闭池，所有墙面法向不可穿透、切向 `FREE_SLIP`，上下板恒温、侧壁绝热。物性从已绑定的 `rho, mu, beta, k, cv` 派生：

```text
nu = mu/rho
alpha = k/(rho*cv)
Ra = g*beta*DeltaT*H^3/(nu*alpha)
a = pi*H/L
q^2 = pi^2+a^2
Ra_c = (q^2)^3/a^2
```

长宽比 `L/H=2` 的首模态临界值约为 `761.0085`，自由滑移判据参见 [Rayleigh 原论文](https://doi.org/10.1080/14786441608635602)。`Pr=nu/alpha=1` 时，解析归一化增长率为 `lambda*H^2/alpha = -q^2 + sqrt(Ra*a^2/q^2)`。

`LiquidConvectionExperiment` 经真实黏性、浮力、压力、速度交接、平流与有限导热推进，在 `Ra/Ra_c=0.8,1.2` 验证衰减与增长。网格高度取 `8,16`，单元保持 `0.5 m`；物性随池高缩放，使长宽比、Pr 和 `alpha/H^2=0.01 s^-1` 一致。温差为 `10 K`，扰动振幅为 `0.01,0.02 K`，深度方向为均匀模态。两振幅增长率须相差不超过 3%；增长率误差受空间与时间离散上限约束，细网格误差须小于粗网格的 60%。

该验收采用参考温度附近的常物性线性 Boussinesq 热量模型。黏性耗散为速度的二阶量，保持独立的机械诊断；当前热方程推进有限导热与外部热量，机械耗散尚未回填载体 `U`。P2G/G2P 传递耗散另行记录。每步分别核对体积、材料质量、species、内能、壁面 source/sink、舍入差额及机械收支。有限振幅非线性对流的热力学总能量闭合需后续加入耗散热。

### 气体能量与功

gas node 的共同温度由各 component 的质量、species 和 node `U` 反演；非等温绝对压力为

```text
p = P0 * (Vstd / Vavailable) * (T / T0)
```

几何改变使用分段常 `cv` 的理想气体等熵闭式积分：逐段满足 `∫Cv(T)/T dT = R ln(V_initial/V_final)`，在各 component caloric 边界间以指数式求解，必要时精确走过多个 segment，并在首尾标定段之外使用同一端点 `cv` 继续解析路径；`Delta U` 记为进入 gas inventory 的 mechanical work。

体积比的对数和温度指数按可表示的最终状态求值；机械功由初末温度之间的显热积分求出，再加到原始 `U`，同体积或温差无法表示时保留原内能。只有结果低于 binary64 严格正温数值域、最终状态或能量非有限才拒绝候选。孔流搬运源状态焓 `h = u + p*v`；node 合并时能量相加，分裂时按 parent 温度计算所有 child 的能量，并由该温度下总热容最大的 child 承接余数，同热容按节点顺序取首个。其余能量精确求和后承接列一次舍入，逐列校验严格正温域；舍入差计入既有能量账本。

空 component inventory 表示显式真空：其 `U`、绝对压力、密度和无传质体积变化功严格为零。显示温度使用区域插入温度；持久化与重映射保留空 inventory 与零 `U`。

## 相变

相变功能依赖热学功能。每个 transition 用完整最小守恒量子定义，运行时按可用能量、库存、目标容量、饱和条件和统一操作预算求最大整数 extent。整格组成按 registry 物种容量聚合，目标载体分别验证物种槽位与量子容量；提交前完成液态区域容量预留，凝结优先选择可承接的已有 marker，再使用可用的新 marker 槽位。

logical thermal axis 在最后一次真实 carrier 修改后从全部载体构造派生 LTE grid；若 chemistry 与 granular phase 统计都证明零提交，后续固液和气液扫描复用导热留下的同代 grid，独立 phase 入口则自行重建。

无相库存、已处于平衡或方向上没有可搬运载体的 cell 不构造完整 inventory；只有首个可执行气液 interface 才生成液体和固体热平衡候选对象。deposit heat-bath 通过目标 cell 的 canonical sparse group 视图读取，不为每个 phase cell 扫描全 region。

气液 extent 的候选保存该 cell 的 exact dyadic 总 `U` 与不变 solid heat-bath inventory，只替换候选 liquid inventory。真实 phase commit 后重建载体 ordinal，但只按守恒余量重新求解被编辑 cell；未编辑 cell 必须已有同代 LTE 状态并原样保留，不能借此反算或改写其 `U`。

沸腾候选移走最后一个凝聚相载体时，单元剩余 `U` 须精确归零；该条件参与每个候选的可提交性校验。候选热浴逐 marker 使用计划提交的剩余质量与组成构造，完整移除的 marker 不贡献热容量。气液能量交换按气体前后实际 binary64 内能的精确二进制差计算。

固液和气液相变的容量检查共用 `PhaseChangeStageSupport`：活动颗粒按连续位置的三线性权重分摊整数 SVQ，沉积按所属单元计入；coherent 库存由相变层另行计入。这与运动、沉积及恢复检查使用同一份空间归属规则。

导热若所有载体接触与 reservoir 的实际通量都严格为零，则逐载体验证正温并保持全部 `U` raw bits。小通量按原值进入有限接触计算。

### 固液

- 联合搬运物理 kg、condensed weight、液体 marker、coherent SVQ、材料身份、可选 chemical composition/species 和 `U`。coherent 部分拆分先分配整数 species，再由实际移出质量减去移出 species 质量求残差，保留部分接收剩余残差，各份质量列分别闭合。
- 每条 transition 在该 lower coherent phase 或目标 upper liquid 存在时查询压力并规划。同格其他 liquid/coherent carrier 保留材料和组成，并以原有体积参与整格容量约束、以内能参与 LTE heat bath。
- coherent group/组成池、液体 marker 总预算或 coherent exact split 暂时不能表示完整批次时，该批次零修改延期；只有已提交 coherent 几何与 cut-cell 容量矛盾、组成 capability 不匹配或账本不闭合才暂停合法候选链。
- 相变按固定区域体积联合守恒两相内能。体积变化记录为待处理几何，下一次压力投影完成力学同步后清除；内部相界位移不另记外部 `-pΔV` 功。
- 固液规划从最近一次真实 projection 的 cell gauge pressure 加环境压力得到绝对压力，再取 `T_eq(p)`。hydrostatic thermal-only 私有候选冻结并传递该 projection 作为输入计算值，不持久化至 checkpoint；冷恢复先执行一次 motion 重建 projection。曲线使用 registry-bound、按压力严格递增的分段线性点；固液相变在数据范围内插值，在范围外沿最近端点的割线斜率延伸，低压端延伸至真空压力。端点表示已知数据的覆盖范围。相变温度保持有限且为有效绝对温度；超出热容采样温区时沿用材料状态函数端点热容延伸。registry 校验采样点上下相能量差为正。常温曲线显式给出两个端点。液气曲线覆盖标准压力以定义标准体积与潜焓参考态；液气方向由分压与饱和压力计算，独立于固液线性延伸规则。

内置冰/水 transition 每批为 `0.001 kg`，两侧分别为 `146 SVQ` 与 `134 SVQ`。熔化曲线采样自 [IAPWS R14-08(2011) 方程 1–5](https://iapws.org/public/documents/MdUFK/MeltSub2011.pdf)，覆盖 Ice Ih、III、V、VI、VII 的稳定熔化分支，最高采样温度为 `715 K`；更高压力沿最后两个采样点的割线延伸。低压段保留原有 `611.657 Pa–2 MPa` 细分采样。生成脚本见 [Python 工具](../maintenance/pytool.md)。游戏内各冰相共用一个固态载体及固定体积批；这保留了高压熔点变化，尚未表达各冰相的密度差异。

规划器在共享 phase-operation 预算内寻找 exact batch lattice 上离 `T_eq(p)` 最近的可表示状态；候选必须严格缩小温差，跨过平台时只在跨越后的格点更近时提交，随后保持稳定，不允许每步冻融往返。

由 batch 大小造成的剩余温差是明确量化误差；库存、容量或操作预算先到达时停止。

### 气液

- liquid water 与 water vapor 使用同一个 transition 的质量、潜热和 exact standard-volume batch。
- 沸腾/凝结由 water-vapor 分压、温度和 transition 饱和压力关系决定，并受 sealed/ambient gas inventory 容量约束。
- commit 同时更新 liquid marker、gas component nanos/kg、gas node energy、source/sink 与 topology remap；任何列不闭合则整批拒绝。
- 当前集总气腔在区域参考系中采用零输入动量的凝结近似。新增 marker 使用零速；加入已有 marker 时，候选按实际接收质量预先求出质量加权速度，提交质量和速度，保存原有液体动量，误差限于最终 binary32 速度舍入。
- 每次气液 commit 还按实际增加/删除的 liquid SVQ 写入带 cell 的 exact pending volume；下一次真实 projection 完成力学同步，随后清除该 entry。capture/restore 必须保留尚未结算的原始 pending 值。
- 同一 transition、gas node 与 exact liquid temperature 在一个 logical step 内只有一个热力学方向；首次观察到的沸腾或凝结方向是该状态点的唯一方向，后续候选若试图在同一状态点反向则不提交。不同温度的热/冷界面仍独立求值。
- exact batch 搜索以饱和驱动力的方向限制整数 extent：沸腾后的 `p_sat >= p_partial`，凝结后的 `p_sat <= p_partial`。搜索选择预算内满足全部提交条件的最大批数，保持两相联合内能；薄液体的有限热容量同样参加候选反演。库存可表示性连续且容量、操作预算充足时，剩余转移量小于一个最小相变量子；组成份额或载体容量受限时，余量保留在原库存，等待库存、热通量或几何变化。
- 一个液体 cell 同时接触多个互不连通的 sealed node 是合法界面，不得因此暂停或合并气腔。一次 pressure interval 内该 cell 只向一个 node 提交一次守恒交换：按 `|p_sat-p_partial|` 从大到小尝试，最强候选若受库存、能量、组分或容量约束无法执行再尝试下一 node；相同驱动力按稳定 node ID 决定顺序。
- chemistry 启用时，每个 gas transition 先把允许挥发且具有 host-gas energy offset 的 stable species 编译为本步 runtime handle 集；液体 marker 仍按 ordinal 搬运 exact species、质量 residual 与 `U`，runtime handle 不持久化。
- 凝结的接收者按最终合并组成校验。零整数 species、正质量 residual 的气体份额可加入已有化学载体；生成独立化学 marker 时，移入份额须含正整数 species。

零输入动量近似适用于平均气体动量可忽略的场景；气体空间动量及蒸发带出的动量仍需由空间气体模型承载。凝结质量 `dm` 即时加入质量 `m`、速度 `v` 的 marker 时，速度混合减少的动能为 `m*dm/(2*(m+dm))*|v|²`，另有最终速度舍入误差。当前两相内能事务保持总 `U`，该动能差尚未转为混合热；对应温度误差可按动能差除以接收单元热容量估计。

整数 species 份额使气液候选的可行集合出现间断：部分移出可能缺少可表示的保留或移出载体，完整移出仍可执行。搜索先求值最大批数，再从高到低划分整数区间；候选空缺仅影响该批数。区间裁剪依据实际库存的质量与 species 份额范围、逐载体原始参考能、分段热容及向外舍入的压力界。沸腾按实际移出顺序构造保留库存，凝结覆盖所有接收者及新增载体；只有整段资源不可表示或饱和驱动力严格反向时才排除区间。宽区间或无法求界的区间继续划分，单步至多检查共享预算内的 4096 个整数批数。

水/蒸气的方向由 ideal-mixture component 分压和数据化的 Clausius–Clapeyron 饱和关系决定：

```text
p_sat(T) = P0 * exp[-L/R * (1/T - 1/T_eq)]
p_partial = p_absolute * Vstd,vapor / Vstd,total
```

`p_sat > p_partial` 允许沸腾，反向允许凝结，相等时不转移。这里 `L` 从上下相 `U(T_eq)` 加 `p*Delta V` 推导，`Delta V` 使用参考平衡温度下的两相实际体积；气体常数由蒸气标准体积推导。transition 压力曲线在标准压力处定义参考平衡态。Clausius–Clapeyron 方程由蒸气分压与液体温度直接确定相变方向，有限非负总压（包括真空）均受支持。模型不单列临界流体；气体密度直接由库存质量除以实际可用体积得到，可连续接近液体密度，不设置密度上限或额外相图直线。

内置 batch 为 `0.001 kg`：液体体积 `134 SVQ`，沸点体积 `228122 SVQ`，换成标准状态约 `0.001335254 m³std`；这组数给出约 `1000 kg/m³` 液水、`0.588 kg/m³` 沸点蒸气和 `461.52 J/(kg·K)` 水蒸气气体常数。上下相状态函数在 1 atm 给出 `2.2567 MJ/kg` 汽化焓。

每个 logical step 的固液与气液共享最多 4096 次 phase operation 作为单步防失控数值预算（0.05 s step 与 1 g batch 下上限为 `81.92 kg/s`）。真实速率由热通量、库存、容量与饱和条件约束；若测试场景需要动力学速率，应引入对应物理模型，潜热项保持热力学定义不变。

气液候选先用当前 marker geometry 同步 gas topology，再规划 inventory/energy。凝结和固液相变的体积增长须同时满足局部容量与全域凝聚态库存的剩余容量；密闭域仍有气体库存时，必须留下严格正的气体体积。同一 liquid thermal material 的候选状态函数可把 cell 内剩余 marker 聚合为一份 extensive inventory；实际删除、截断、species 与 residual 搬运仍保留逐 marker exact plan。

每次真实气液 commit 后都从 carrier truth 重建 cell-marker ordinal 并完整重扫 gas topology：marker ordinal 存活不保证 air membership 不变，partial cell 可能被填满或越过 interface 判据。

零库存、零内能的真空节点可在拓扑重映射时缩至零体积，压力和密度保持零；非空气体仍要求严格正体积。密闭全域容量已经包含已有气腔控制体积；未归属局部空隙只能按全域新增容量继承真空空间，避免局部几何权重与归一化体积重复计算压缩功。

该重扫联合搬运 gas inventory/U 与 lineage，保持相变两相总内能不变。固定区域内的相界位移属于内部交换，不计入外部机械功。随后液体和颗粒真实运动改变气腔容量时，仍按新旧几何执行气体等熵功；相变记录只要求力学同步。

力学 P2G、pressure footprint/PLIC、表面张力和速度外推统一在 logical step 末重建一次，capture 前也从完整 geometry 重扫。这样任何可发布 logical state 都不会出现 marker 已变而 gas node 仍属于上一拓扑的半代次，也不会因容量快路提前结算一次、projection 再重复结算一次体积功。

## 反应热

化学和固液反应先完成 exact extent 规划，再将定义的能量变化提交给同一 cell 的热载体。反应热不得绕过 chemical/solid 账本单独修改温度。

固液反应在分配新固体组成 handle 前，预检完整候选热库存；预检失败时保持物质库存、内能与组成池不变。

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

每一步都先在快照上形成候选。相变产生的体积改变记录为带 generation 的 pending volume/work，由下一次真实 projection 统一结算一次。

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

流体端口的目标为单元内实际载体 `U` 的精确和加设备热量，保留 `SignedDyadic` 至扣除 caloric reference 后再反演。计划仅预检和分配非零换热单元；零热量端口及未参与的单元保留全部载体内能位模式。所有修改目标通过只读预检后，再与设备库存及审计一起提交。

单元分配在写入前校验各载体拟存储的原始内能及其精确总和，失败时保留原温度、热容和 caloric interval。提交后温度、热容与单元总内能由实际写入的载体内能重新推导，实际总和与请求目标的差额计入舍入审计。

两份 roundoff 相加必须等于 region 内 body+fluid 的实际总 `U` 变化。audit 不闭合时保留候选前状态并 PAUSED，禁止通过重算温度掩盖差额。

管理员清空也遵守同一审计：`EMPTY_CONDENSED` 把 liquid/active/deposit/coherent 的实际 `U` 记入 external sink，`VENT_SEALED_GAS` 把全部 sealed gas node 的实际 `U` 记入 external sink，并与对应物质库存及 topology 在同一私有 candidate/HEAD 中提交。预览展示的能量直接取自权威总 `U`；有限设备非空时阻止释放 ownership 与移除区域。

## 持久化与容器

- 完整热区域 generation 必须联合保存 marker/solid/gas topology、所有载体 `U`、audit、Boussinesq identity 和 pending phase-work。
- capture 前执行 gas topology synchronization，确保 marker geometry、gas membership 和 gas energy ordinal 同代一致。
- `ThermalContents` 将物品中的液体物质原始数据与同一 registry fingerprint 下的总内能绑定；桶内只假设一个平衡温度，该温度由完整质量、组分和总 `U` 反演，不另存第二份温度原始数据。若界面展示温度，它只能是只读派生。
- 纯水等同质库存按实际体积直接计算小份额 `U`，大份额接收精确相减后一次舍入的余量。材料或化学混合物的部分倾倒先从源桶完整库存和 `U` 反演共同温度，再按两份实际材料质量、species 与 reference offset 求状态能；小热容份额直接保存该能量，大热容份额接收源 `U` 减去小份额后的单次舍入余量。两份均须通过实际库存的正温反演，源温度的保留精度受 binary64 能量表示限制。没有 region/WAL roundoff owner 的工作台配方不得剥离或合并带 `ThermalContents` 的物品。
- 注入向区域提交选中份额的实际 `U`，余下物品保存剩余份额的 `U`。物品扣能从 raw 源内能与 raw 剩余内能的精确二进制差推导，在 coupled energy/WAL 中只舍入一次；region external source 使用这一记录值。区域实收能量与记录扣能的差额归入 `roundoff_residual`，载体内能保持原样。拆分的总内能表示误差由大热容份额的一次舍入产生，物品自身保存原始内能，不另存审计列。
- 多 marker 抽取先以 raw binary64 `U_before-U_after` 的 exact dyadic 和确定真实 carrier 能量变化，再只在整件物品边界舍入一次；region external sink 必须等于最终 `ThermalContents` 的 raw `double`，两者与 exact carrier 变化的差只记入 `roundoff_residual`。不得逐 marker 舍入后再把近似和冒充物品能量，也不得用放宽 handoff 审计掩盖差额。
- 容器 WAL 在含热转移中同时保存 exact liquid amount、item before/after 和 coupled energy；物品 durable save 前不得清理。

section payload 逐 ordinal 保存 liquid/active/deposit energy raw bits 和 coherent group；manifest 保存各载体 exact dyadic 汇总、gas node energy、audit、registry identity、phase-work 及 section ledger。热域的外部注入温度也是环境气体的配置温度，其 Kelvin 原始 double 随内存交接与 manifest 保存；恢复沿用该配置完成材料温区校验，后续注入内能与环境气体热力学保持同一输入。恢复时先验证 cardinality 与所有汇总，再构造 solver；载体派生温度由实际 `U` 反演。

## 调度与观察

motion 和 thermal activity 是正交轴。只有温度残差、边界通量、反应/相变或局部几何变化达到唤醒条件时才推进热学；休眠不得改变任何库存。

`status` 可读取全域温度范围、总 `U`、source/sink/work、phase batch 和气体节点摘要。Query API 可返回单格权威载体反演出的可选温度；查询只读 marker/side columns，不唤醒、不建网格，也不回写热状态。

thermal activity 与 motion pacing 相互独立：有 face transfer、reservoir exchange、finite-device exchange、chemistry/granular/phase dirty、gas activity、carrier motion、pending work 或未闭合 audit 时保持 ACTIVE；连续安静观察先进入 REDUCED，再进入 SLEEP。

内部导热即使没有外部 source/sink 变化，也会改变热浮力和气体压力。`ThermalActivity.Observation.requiresMotion` 将非零面热量转移、温度变化及已有反应/相变活动一并交给力学调度：单独热计算后唤醒力学，联合计算后阻止静水休眠。此观察不修改库存，玩家暂停仍优先。

boundary、容器、管理员 source、有限设备、化学、颗粒、气体、相变或 carrier movement 都是精确唤醒原因。

PAUSED 覆盖运行时内能、压力、已提交相库存的守恒或几何不变量失败，以及有限热设备的步数预算耗尽。registry identity、持久 payload、ordinal/generation 身份损坏归入 LOCKED，恢复依据来自权威状态与事务证据。
