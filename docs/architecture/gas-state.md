# 气体状态契约

修改气腔扫描、气体库存、压力响应或孔流时读本文。

## 修改入口

| 要改什么 | 代码入口 | 测试选择器 |
|---|---|---|
| 气腔几何和归属 | [GasRuntimeGeometry](../../solver/src/main/java/mmm/fluid/GasRuntimeGeometry.java)、[GasTopologyCoordinator](../../solver/src/main/java/mmm/fluid/GasTopologyCoordinator.java) | `air-cavity` |
| 气体库存与孔流 | [AirNetwork](../../model/src/main/java/mmm/fluid/air/AirNetwork.java) | `air-network` |
| 气液相变库存与账本提交计划 | [AirNetwork](../../model/src/main/java/mmm/fluid/air/AirNetwork.java) 的 `planPhaseSource` / `planPhaseSink` / `commitPhaseMutation`、[GasLiquidPhaseChangeStage](../../solver/src/main/java/mmm/fluid/GasLiquidPhaseChangeStage.java) | `thermal`、`air-network` |
| 热孔流整数均压 | [GasEnergyStore](../../model/src/main/java/mmm/thermal/GasEnergyStore.java)、[GasTransferSearch](../../model/src/main/java/mmm/thermal/GasTransferSearch.java)、[GasTransferValidation](../../fabric/src/test/java/mmm/thermal/GasTransferValidation.java) | `thermal` |
| 温度、压力和声学状态 | [GasThermalModel](../../model/src/main/java/mmm/thermal/GasThermalModel.java) | `thermal` |
| 气液压力耦合 | [PressureBoundaryView](../../solver/src/main/java/mmm/fluid/PressureBoundaryView.java)、[PressureProjectionWorkspace](../../solver/src/main/java/mmm/fluid/PressureProjectionWorkspace.java) | `gas-liquid` |
| 定义与存档 | [GasRegistryCodec](../../fabric/src/main/java/mmm/fluid/air/GasRegistryCodec.java) | `region-state` |

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [模型边界](#模型边界)
- [权威数据](#权威数据)
- [拓扑变化](#拓扑变化)
- [压力与热耦合](#压力与热耦合)
- [持久化](#持久化)

## 模型边界

- 完整干面连通的开放空间采用无限环境节点；密闭气腔及通过受限通道交换的气腔保存有限库存。
- controller 的已验证闭壳 footprint 可声明 sealed domain boundary。命令临时域保持环境开放。
- `GasRuntimeGeometry` 从原始库存、邻格相分数和固定碰撞连通分量重建 PLIC。正干微盒形成气腔，部分充液格的干区持有实际归属。
- 节点 pressure、density、temperature、孔流速度和边均为派生运行态，不作独立库存存储。

### 微盒气体几何

`GasComplementGeometry` 将固定开放微盒扣除 PLIC 湿区，生成气体块和共享干面。`MicroBoxPlaneVolume` 与 `GasGeometryOverlap` 使用浮点几何积分，`PlicPlaneOffset` 使用二分求逆。几何观察值可封装成精确数量类型供后续分配；其精度由浮点几何决定。

`GasComponentCapacity` 按原始液体库存和固体占容计算剩余容量，允许 `2e-7` 单元体积的几何误差。`GasPieceCapacity` 依据正干体积按比例分配容量，最后一份承接精确余量。气腔库存和内能继续采用精确数量结算。

`GasFaceIntersection` 计算共享干面，解析尺度的液封保持分隔；`GasGeometryTransition` 复用同一对新旧 PLIC 的交体积。液体几何在捕获与恢复时统一重建。

### 运行中的共同几何

[GasRuntimeGeometry](../../solver/src/main/java/mmm/fluid/GasRuntimeGeometry.java) 保存一份不可变的几何、拓扑、库存容量和 `8N` 微盒归属。每个格子的完整液体分量元组包括 count、精确体积和位置第一矩，零体积分量也保留。构造直接读取原始载体列，未来编辑同时覆盖各目标格的完整元组后重建全域 PLIC，包含受梯度影响的邻格。完整固体占容 SVQ 副本与固定碰撞掩码共同参与容量计算。

PLIC 重建先生成全域当前液体比例与孔隙标量。自身完整元组、占容、掩码和尺度相同，且六个轴向邻格的实际固体标志与液体比例相同时，复用已捕获的完整平面及独立分量求逆证书；其余格子按当前来源重新求解。压力单元判定继续从当前标量与平面计算。

同次只读沸腾搜索先核对全域原始液体元组、固体占容、掩码与尺度，由 `PhaseSource` 保存当前 frame 的不可变来源元组与原始位置、体积副本。每个批次在私有工作区重建一次变化格 PLIC，供接触与饱和预检共同读取；进入完整规划时复制该结果，独立保存运行帧，再生成变化格气体块、相邻通路及库存容量证书。搜索绑定一个编辑格，将该格及读取其比例的六个相邻格划为变化区域；固定区域的气体块和通路直接复用，沿完整干面预先合并；几何体积、原始库存容量、舍入误差和有限孔口面积各按固定分量汇总一次。候选重连变化区域的通路，按最小气体块编号生成完整节点归属，再按本次归属汇总固定及变化区域的原始容量；有限孔口继续保留实际面积的孔流连接，外壳环境归属随本次连通结果更新。固体占容或外壳状态不同的候选执行完整拓扑计算。来源绑定域、frame、库存及载体代次，提交前继续完整核对原始来源；搜索结束后释放私有工作区。

该搜索同时保存私有的固定碰撞几何、固体掩码和占容输入。液体候选沿用同一固体占容时直接读取这些固定输入，完整候选分别持有自己的 PLIC 数组；固定子图和容量汇总按需准备一次，几何目录在首个完整候选前准备。固体占容不同的候选重新构造占容视图并执行完整几何规划。

微盒输出、固定掩码、孔隙与面积及体积尺度相同时，干区块复用已有几何记录。容量分配按当前浮点几何权重和原始库存余量生成。

端口复用按气体块编号、轴的规范顺序归并内部和外壳端口。相同几何中的缺项表示零面积；变化单元及消失邻居继续按当前几何生成端口。

原始液体或固体占容变化后，旧 frame 继续标识原库存的空间位置；重建规划旧新几何间的完整库存与内能交接。有限体积输运按最终 binary32 载体位置和预览颗粒占容准备完整气腔后继，首次写入前复核来源与热账，颗粒和液体发布后安装同一后继。相变和清空计划分别绑定完整来源与目标元组，在首次载体写入前复核来源，载体提交后复核目标。观察中的单格摘要仅在该格所有正气体块具有同一归属时返回节点，多归属或无气体返回 `-1`。

一次同步相变搜索在准备时核对完整来源，候选共用固定外壳、来源占容和局部几何工作区，以 frame 与库存代次核对搜索期间的状态。提交前按实际载体、占容与未来几何检查来源及后继。

`GasTopologyCoordinator` 保存最近一次成功提交或恢复的 frame、气体网络及网络代次。刷新先更新库存网格；这些身份、外壳状态及完整载体几何均相同时，沿用已提交拓扑与热账。载体矩、固定掩码、固体占容、frame 或网络代次改变后，执行完整规划。热学内能变化沿原拓扑直接参与状态方程求值。

环境截留量按标准体积 nanos 就近舍入。正截留体积舍入为零时，在库存提交前抛出 `GasTopologyBlockedException`。整数舍入对应的压力误差为 `|Δp| ≤ p₀·(0.5×10⁻⁹ m³)/V`，其中 `V` 是截留体积；体积到 nanos 的 binary64 换算误差另计。

`AirCavityScanner` 保留格级离线对照入口。运行中的气体归属与节点容量由上述微盒 frame 提供。

## 权威数据

- 每个有限节点以 canonical minimum-piece key 和完整微盒归属标识。
- 每个气体组分保存稳定 component ID、标准体积 nanos、独立质量 kg、可选 species 和 chemical mass residual kg；气体的两个质量列都不使用凝聚相 solver mass weight。
- source、sink、质量 roundoff 和 residual roundoff 是独立审计列。
- ambient 节点不持有有限库存；开放域中新密闭腔从环境建立时明确记 external source。已启动闭域内没有旧 owner 的新 cavity 是显式真空节点，其 component 库存、质量和 `U` 均为零，不得伪造 ambient source。

`GasRegistry` 规范化 component 定义和 ambient fraction；ambient fraction 必须按 exact rational 精确和为 1。标准态固定为 `101325 Pa`、`293.15 K`。

每个 component 分别定义标准态 kg/m³、允许承载的 species、默认 species quanta/m³ 和质量 residual/m³；默认 species 必须属于该 component 的允许集合。

创建 ambient inventory 时用 largest-remainder 对整数 nanos 确定分配。

component 与 species 的隶属关系是 world-local registry 原始数据，不由运行时库存临时推断。相变 source、拓扑 remap、恢复和 source/sink audit 都必须在发布前验证该关系；一个已知 chemical species 也不能挂到未声明它的 gas component。

气液相变只把同时被 component 允许且具有 host gas thermal offset 的 species 视为可挥发，其他 species 留在凝聚相。允许集合非空只声明能力，当前功能布局中实际出现 species 时仍必须同时存在匹配的 chemical registry 和 host gas thermal species offset。

`AirNetwork` 对节点、库存、可用体积、edge 和物质审计维护同一个单调 revision。节点创建、库存设置、拆分、合并和排气先准备完整节点、连接及累计审计后继，再统一发布；质量和残差先计算本次净差额，再加入已有舍入账本。孔流连接须引用已有节点或环境，单次请求的面积与流量系数先接受校验，再合并平行开口。topology remap 绑定规划时 revision；提交前 gas inventory 与 gas-energy 两个 owner 必须同时接受各自 generation，之后才发布任一侧。几何仍相同但库存、体积、edge 或 `U` 已改变的旧计划必须整体拒绝，不能覆盖较新的 owner 原始数据。

相变后的拓扑计划同时绑定具体的父相变计划；首次相变写入前和父计划提交后均检查该身份。相同基础 revision 上准备的其他相变计划使其后继失效。

气体拓扑计划同时绑定来源 frame、不可变的 cut-micro 掩码、外壳密闭状态及完整载体元组。操作级清空在任何库存写入前复核来源，重新生成全干候选并核对微盒归属和节点容量。排气计划同时绑定来源 frame 与实际载体几何；位置第一矩或固体占容变化使旧计划失效。

表示硬上限为：registry 64 个 component、单 node 32 个 component、单 component 16 个 species、单 region 65536 个 sealed node、stable ID 128 字符、单 extension 16 MiB。到达上限时明确拒绝完整候选。

区域库存摘要和 source/sink 使用 `GasAuditLedger`，按注册表组分范围累加 nanos、species 与精确二进制质量和 residual；实际节点库存继续使用 `GasInventory`。审计、拓扑源汇、排气与恢复共用区域摘要。恢复先在局部候选中验证完整组分和审计闭合，再一次发布节点、账本和 revision。

node ID 只是当前 topology 的稠密 ordinal；持久身份由微盒归属和 minimum-piece key 决定。component stable ID、standard-volume nanos、kg 和 species/residual 独立保存，不能从理想气体状态方程反算库存。

凝聚相与气相搬运 chemical residual 时，只在相边界用该区域冻结的 `cellSize³` 做 signed solver-weight/kg 换算；气相内部输运、分裂、合并和持久化始终保持 kg。

## 拓扑变化

`GasTopologyCoordinator` 统一处理平流后继。存在重叠时按交体积分配；旧节点没有任何重叠后继时，沿新旧干面连通图寻找最近可达后继。容量不超过单元 1% 的未解析气团，允许沿开放碰撞微盒跨越最多两条微盒边合并表示，库存与内能随之交接。较大气腔继续要求实际干区可达。固定几何移动时，整体迁移气腔携带的旧控制体积先抵扣新开放空间；剩余新空间才作为真空扩展处理，避免重复容量制造压缩功。

[GasTopologyCoordinator](../../solver/src/main/java/mmm/fluid/GasTopologyCoordinator.java) 在同一开放微盒内计算旧新干区的正体积交集，确定每个旧节点的全部正后继。相同微盒的干侧半空间相等，或任一干区覆盖整个微盒时，直接复用已有精确干区体积。旧有限节点只有一个后继时使用完整交接比例 `1`，包含环境后继和真空旧控制体积；多后继分配与旧环境截留使用旧块的已认证物理容量乘以本次交集占旧干区的比例。每块交集计算一次，分配共用保存的结果。持续归属环境的微盒保持环境连接，直接跳过库存权重积分与累加；有限库存、旧控制体积和外部源汇按实际交接计算。

有限来源的所有原微盒在未来仅指向同一节点，且至少一个微盒具有上述正交集证书时，直接确认完整比例 `1`。缺少正交集或存在多个潜在目标时，继续逐块积分并确定实际后继。相变饱和残量读取同一交接计划中的交集，直接按实际存活接触求值。

- 一对一直接携带完整库存、原始内能及旧控制体积；多个后继才按共同权重拆分。多对一合并组分、species、质量余量和内能。
- 一对多保留每个微盒交集结果的有理数叶项。`ExactGasPartitionWeights` 用向外舍入区间与输入分母给出的非零下限认证比例、整数余数排序和 binary64 舍入，精度随具体判定细化。相等余数按后继编号排序。单项与 registry 定义保持既有规模限制，多个交集共用叶项表示。
- `GasInventory.partition` 保持逐组件 nanos 与 species 的整数总量，质量列由最大份额承接余数；内能按共同温度拆分。`GasTopologyVolumePartition` 复用同一权重对象分配旧控制体积；非最大份额各舍入一次，最大精确权重承接实际储存子列的余量，正份额无法表示时拒绝候选。环境截留从绝对交集叶项求体积，保留原物理单位。
- 已有真空节点合并时携带自己的旧控制体积。液体退让使已有有限气腔扩大时，新增干区进入最终容量，按旧控制体积计算绝热功。撤去固定固体后新开放微盒的实际干容量作为新暴露的真空体积参与合并，保持自由膨胀的内能处理。全新且没有有限或环境来源的独立气腔初始化为零库存真空。
- 旧有限库存与新环境归属存在正交集时，实际库存份额进入 sink。旧环境干区被截为有限节点时，其交集容量按环境组成进入 source。
- 延迟的液体编辑可通过当前已发表载体几何证明去向：旧、当前及未来几何均为同一个封闭连通气腔，固定掩码与固体占容保持一致，旧到当前及当前到未来分别有正干区交集时，集总均匀库存沿该实际气道整体交接；单一库存的绝热过程按总体积比连续合成。其他缺少正空间交集的有限库存以 `GasTopologyBlockedException` 拒绝，扩展支持需要实际扫掠轨迹或可达路径证明。空库存且没有后继的节点可以消失。

闭域完成初次环境库存建立后，拓扑重建保持封闭物质总量。节点容量来自逐微盒原始库存证书；每个节点只汇总其自身容量。

外部单格替换保存完整 gas/thermal 拓扑后继，联合校核液体 source/sink 与气体容量功账本，载体替换后直接提交同一后继。

单格全填支持经 `StaticGasPistonSweep` 认证的准静态活塞过程：原格完全开放、无附加固体占容，原液面沿单一坐标轴，整个源气腔位于该格；液面向干侧端面单调推进，其正开口连接唯一直接接收腔，两腔与环境及其他有限节点隔绝。接收腔及其余格的实际库存、PLIC 和容量保持原值。操作先在这对闭合腔内守恒合并库存与内能，并保留两腔的原控制体积，再准静态压缩至接收腔最终容量。真空接收腔的原容量同样参与热学压缩比；功、正温与完整热账在液体写入前预检。普通机械子步、相变及一般拓扑重建继续按原空间交集和有限速率孔流处理。

操作级液体移除、材料与化学容器提取及液体清空同样在首次载体写入前准备完整气腔后继。多格提取使用同一份未来库存覆盖；依次校验液体热汇、物品能量舍入及气体 source/sink/work/roundoff，载体发布后提交已准备的库存、内能和微盒归属。移液产生的独立空腔随本次操作成为真空；后来撤墙使其与有限气腔合并时，携带实际真空控制体积，按自由膨胀保持气体内能。中途持久化同步保持相同物理结果。

公开凝聚相清空在移除液体和连续固相前准备完整气腔后继，保留活动颗粒与沉积的实际占容，并联合预检移除热汇与气体容量功。全库存操作级清空使用其整体计划提交。

材料、化学与操作级注液按将要发布的 binary32 位置、体积及分量归属准备完整气腔后继，联合校验液体热源、能量舍入与气体容量功。载体发布后立即提交该后继；注液压缩的绝热功保留到后续撤墙自由膨胀，读取或持久化同步保持相同温度和内能。有限气体失去可达后继或热状态无法表示时，注液返回未转移并保留原始库存与账本。

每次气液候选覆盖未来完整 count/V/M1 和固体占容，重建受影响邻格的 PLIC、微盒连通及节点容量，气体与热学两侧计划在首次载体写入前完成。相变期间保留联合守恒的 gas `U`，继承体积直接采用本次目标容量；后续真实液体与颗粒运动改变容量时结算等熵功。逻辑步末与捕获前重建共同几何，使载体、微盒归属、库存及内能处于同一提交时点。

## 压力与热耦合

- 无热状态时使用等温状态方程和有限速率孔流。
- 热状态存在时，气体总内能与节点库存共同派生温度、绝对压力、密度和声学闭合；孔流同步搬运组分与能量。
- 密度按各组分原始 kg 的精确二进制和除以实际可用体积求出，在最终 kg/m³ 单位舍入一次。等温和热学状态共用该求值，保留总质量中间和溢出、但最终密度有限的合法状态，以及分量密度各自下溢、但总密度仍可表示的状态。
- 气液相变用 water-vapor 分压计算饱和条件，受状态方程、潜热、库存、容量和批预算共同限制。有限非负总压包括真空；它不被误当作固液 `T_eq(p)` 曲线的查询坐标，液气方向只由液温给出的饱和压与实际 vapor partial pressure 决定。
- 一个界面 cell 可以同时含多种合法 liquid carrier。某条气液 transition 只结算其目标 liquid 的体积、质量、组成和相变能量；同格其他 liquid 原样加入该 cell 的 LTE heat-bath inventory，并计入冷凝容量，但不被改材料、不被并入目标组成，也不能仅因存在就触发候选暂停。
- 所有 candidate 必须能由 world thermal registry 的连续状态函数从权威 `U` 唯一反演；声明 caloric 温区之外沿最近端点 `cv` 延拓，不因标定端点暂停。凝聚相的 `T_eq(p)` 在数据内插值、数据外沿端点割线延伸，详见热学专题。
- 一个 liquid cell 可同时邻接多个独立 sealed node。gas topology 继续保留各 node 的库存和 `U`，phase planner 不合并它们；每个 pressure interval 只向绝对分压失衡最大的可执行 node 提交一次 cell 交换，随后由下一 projection 同步该体积变化。
- 气液接口读取实际 PLIC 的正湿干接触面，包括同微盒的部分界面及微面上的湿干交集。候选绑定液体固定连通分量和接触的气体块，汽化供体、凝结接收者及新增位置均限制在该液体分量内。完整汽化沿原接触块的正空间交集跟踪后继气腔。
- mechanics 可从所有液气界面选择一个统一 sealed-node gauge reference，在压力求解中只求相对压力并在绝对观察/热学边界加回；该数值换元不修改 node inventory、U 或不同 node 间的压差。接触液体的有限 node 会得到当次 projection 的集总可压缩 pressure unknown；该项为同一 EoS 线性化的临时求解变量，不写入权威 node pressure 列。

当前 `AirNetwork` 是有限体积热力学与孔流网络模型；空间 FLIP 网格承载不可压凝聚相。有限 node 与液体接触时已用 `C_V = V*beta_s` 作为集总储存项进入同一压力系统，液体侵入/退出气腔的面通量与 node 压力响应因而在本子步隐式闭合；等温 `beta=1/p_abs`，热状态 `beta_s=1/(gamma*p_abs)`。

压力节点观察读取实际微盒归属。气体工作数组按实际节点数量扩容，转移预算中的气体与颗粒编号保持独立。

纯液体的仿射压力路径直接从当前 `GasRuntimeGeometry` 的 PLIC 生成湿面与自由面片段。每份接触使用规范的 `8*cell+micro` 气体块编号，气压参考值按实际接触节点的顺应性加权；环境接触采用零参考，真空接触采用实际真空压力。准备阶段校核 frame 身份、外壳状态、固定微盒及完整液体分量元组，发布阶段复核气体、热学、载体与速度输入。同格多个气腔分别参与这条路径的储存项。颗粒混合压力路径仍采用整格耦合，相关格须具有唯一气体归属。

frame 生成的压力几何从其完整液体元组读取原始体积。按需的 `liquidSpatialInput` 入口沿用同一来源检查，同时捕获实际载体的体积矩和质量矩，以及对应的湿覆盖、组件孔隙率与液体孔隙体积；数据角色和单位见[空间输入](fluid-solver.md#网格与-marker)。

活动颗粒的占据率支撑涉及有限气体节点时，该节点也进入压力系统。多个支撑单元属于同一节点时先合并体积导数，再与颗粒有限质量组成对称压力项；气体容量变化和颗粒受压使用同一份导数。离散方程见 [颗粒压力耦合](granular-state.md#统一排水与压力)。

气腔节点按集总参数处理，未引入空间速度自由度与腔内混合层。后续迁移到空间动量内核时，需保留精确组分、质量与能量账本，并依据状态方程闭合密度与可压缩散度。

空间化时 node inventory 与逐 cell inventory 不能同时成为原始数据。进入空间内核的连通气体必须由逐控制体的 component nanos/kg/species/residual、动量和总能量唯一持有；`AirNetwork` 只能在未空间解析的腔体继续持有 exact node 总账，或作为逐格总账可证明的 coarse projection。两种表示之间的切换必须是同一候选内的守恒 repartition，不能先复制 node 再让两侧各自演化。

### 状态方程

无热状态的等温 gauge pressure 为：

```text
p_gauge = P0 * (Vstd / Vavailable - 1)
rho_gas = total_mass_kg / Vavailable
```

热状态使用节点权威 U 反演 `T`，再计算：

```text
p_abs = P0 * (Vstd / Vavailable) * (T / T0)
rho_gas = total_mass_kg / Vavailable
R_total = P0 * Vstd / T0
gamma = 1 + R_total / C_v,total
K_s = gamma * p_abs
c = sqrt(K_s / rho_gas)
beta_s = 1 / K_s
```

`GasThermalModel.HydrodynamicState` 是上述局部 ideal-gas mixture 闭合的唯一实现：`C_v,total` 来自同一库存和权威 `U` 所在 caloric segment 或端点延拓，`R_total` 来自同一 exact standard volume；绝热体积模量 `K_s`、声速 `c` 与压力 compliance `beta_s` 均严格依状态方程导数推导。

caloric 标定端点不截断内禀温度，相平衡曲线继续拥有自己的压力数据域。若要支持非理想气体，应扩展数据驱动的状态方程及其导数，不能在 solver 中按 component ID 修正压力。

零库存 sealed node 表示真空：`p_abs = 0`、`rho = 0`、`U = 0`，几何体积变化不产生绝热功。真空的温度没有热力学定义；只读显示使用区域插入温度作为约定值，不能据此反算库存。孔流可按有限速率把环境或相邻节点的真实库存搬入真空。

等温与热状态都不钳制派生压力：正 available volume 直接使用状态方程，有限库存落入零 available volume 是奇异候选并显式拒绝。非空库存的绝对压力因而始终为正，可连续逼近真空，也可表达有限的高压状态；算法不再以固定压力窗替代状态方程。

无传质绝热体积变化按分段常 `c_v` 的 exact ideal-gas isentrope 计算新温度与 `delta U`，通过 `∫Cv(T)/T dT = R ln(V_initial/V_final)` 解析穿越所有 caloric segment 及其端点延拓。能量变化由实际质量缩放后的热容积分求得；每公斤显热超出 binary64 时仍保留有限的实际热量。有限体积比不会得到数学上精确的 `0 K`，但可低于 1 K 并在界面舍入显示为零，只有路径低于 binary64 最小正常正数或不再有限才拒绝。

非空但接近绝对零度的节点仍按库存/容量/`U` 得到真实近真空压力。若其顺应性大到不能表示为 float PCG gas row，本次 projection 将该真实压力作为 Dirichlet 边界，不创建伪造的有限顺应性，也不抬高温度或压力；随后 gas topology remap 仍从实际新旧容量 exactly-once 结算等熵 `delta U`。只有可表示的正常节点进入联合 liquid/gas pressure unknown。

### 孔流

部分开放 face 聚合为面积 `A` 的 edge，流量采用 CONTAM 型孔流：

```text
Q = Cd * A * sqrt(2 * abs(delta_p) / rho_source)
```

气腔重连和拓扑提交按无向节点对汇总平行开口。面积为 `ΣAᵢ`，流量系数为 `Σ(CdᵢAᵢ)/ΣAᵢ`；两项和使用精确二进制累加，最后各自一次舍入，保持连接输入次序和端点方向的一致性。

实际孔流量通过源库存的 `Vstd/Vavailable` 换成标准体积流量；无限环境使用 `T0/Tambient`，密度也按环境温度推导。一个 step 的 transfer 由完整候选的均压条件限制：热状态搬运源侧焓 `h = u + p*v`，用候选两侧库存与 `U` 重新推导压力，在整数 nanos 上寻找保持源压不低于目标压力的最大可表示份额。ambient 流入记 source，流出记 sink；节点间转移不记外部源汇。

整数混合物按 largest remainder 分配组分与 species；不同热容和温度下，可执行份额在整数轴上允许出现间断。`GasTransferSearch` 按每组分 `floor(a*w/W)..ceil(b*w/W)` 的数量范围构造焓、分段热容与压力的上下界，优先搜索较大的份额；只有能够证明全区间源压低于目标压力时才跳过该区间。每次搜索最多展开 4096 个区间。极端量化或能量基准使压力范围无法在预算内确定时，以 `TransferSearchBudgetExceededException` 拒绝私有候选并保留原状态，区域进入数值暂停。

搬运焓的区间界沿用实际能量 API 的参考能与舍入顺序。每公斤显热积分溢出时，用精确二进制积分按质量区间缩放，与参考能合计后再求上下界。温度反演的区间界则从各材料原始参考温度积分，与实际舍入后的参考能精确求和；大参考能相消时仍保留小热量。热量包络在材料参考温度与热容段结点之间为连续分段仿射函数，逐段以精确热容量求逆，最后向外舍入。所得温度界同时包住反演快路允许的一次温度 ULP 误差。无法表示的区间界保守扩展至对应无穷值，未能确定的搬运能量保留该区间继续搜索。

休眠观察复用这项整数均压限制，在可用库存和目标数量容量内搜索当前热力学状态可执行的份额；观察搜索预算不足时保守保持活动，由真实转移候选报告数值暂停。全部 edge 的均压可转移量为零时，气体允许休眠；存在浮点压差但已达到整数均压状态的节点无需反复执行空步骤。仍有均压余量的细小孔保持活动，判断与孔面积、单步时长无关，避免把低流率误认为平衡。`air-network` 同时验证均压后连续空步骤、环境流入/流出，以及微小孔的非平衡状态。

## 持久化

气体作为完整热区域 generation 的 required payload 发布。快照与 section 都保存唯一 `pieceNodeOrdinal` 主列，每格八项，`-1` 表示无干区、`0` 表示环境、正整数表示规范有限节点。节点按全域最小气体块编号排序，库存与热能保持该顺序；section 切片保留全域 key，并记录 `sealedPieceCount`。

恢复先从已提交液体、颗粒和 incoming thermal 快照中的 coherent 占容共同生成 frame，再校核完整 `8N` 归属、节点数与 minimum-piece key。随后恢复对应库存和内能列。section 组装校核完整覆盖、重复覆盖、节点引用和逐 section 计数；解码对大小、溢出、EOF 及尾随字节严格校验。显式真空节点以空库存原样保存。

持久 payload 还包含 registry fingerprint、unit identity、component palette、节点总账与 source/sink/roundoff 审计。

gas registry fingerprint 同时绑定每个 component 的允许 species 集合；不匹配时原样保留并锁定。edge、pressure、temperature 和 flow velocity 从完整库存与几何重建。

管理员 `VENT_SEALED_GAS` 是一个联合持久候选：所有 sealed component nanos/kg/species/residual 按实际值进入 gas escaped sink，所有 gas node `U` 按实际值进入 thermal external sink。

操作保留由闭壳几何派生的完整 node membership、available volume 与 edge，并将各 node 的 component inventory 和对应 `U` 精确置零，提交结果为显式密闭真空。

plan 同时绑定 `AirNetwork` revision 与完整 thermal owner revision；commit 在首次变更前联合校验两者，避免拓扑过期或 audit successor 冲突导致部分提交。

真空节点可以 capture、恢复、清空凝聚相、再次排气并在显式 resume 后继续运行；后续注液、蒸发或拓扑 merge/split 只能从真实 transfer/phase source 建立新库存，不能用环境默认库存补回。
