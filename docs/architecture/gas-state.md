# 气体状态契约

修改气腔扫描、气体库存、压力响应或孔流时读本文。

单位与整数编码统一见[设计书](system-overview.md#units)。

## 修改入口

- **气腔几何和归属**
  - 代码入口：
    - [GasComplementGeometry](../../solver/src/main/java/mmm/fluid/GasComplementGeometry.java)：`capture` 生成干微盒及开口
    - [GasRuntimeGeometry](../../solver/src/main/java/mmm/fluid/GasRuntimeGeometry.java)
    - [GasTopologyCoordinator](../../solver/src/main/java/mmm/fluid/GasTopologyCoordinator.java)
  - 测试选择器：`air-cavity`

- **气体库存与孔流**
  - 代码入口：[AirNetwork](../../model/src/main/java/mmm/fluid/air/AirNetwork.java)
  - 测试选择器：`air-network`

- **气液相变库存与账本提交计划**
  - 代码入口：[AirNetwork](../../model/src/main/java/mmm/fluid/air/AirNetwork.java) 的批量库存计划与一次重映射接口、
    [GasLiquidPhaseChangeStage](../../solver/src/main/java/mmm/fluid/GasLiquidPhaseChangeStage.java)
  - 测试选择器：`thermal`、`air-network`

- **整体隐式孔流与焓交接**
  - 代码入口：
    - [GasEnergyStore](../../model/src/main/java/mmm/thermal/GasEnergyStore.java)
    - [GasOrificeFlow](../../model/src/main/java/mmm/fluid/air/GasOrificeFlow.java)
    - [GasTransferValidation](../../fabric/src/test/java/mmm/thermal/GasTransferValidation.java)
  - 测试选择器：`thermal`

- **温度、压力和声学状态**
  - 代码入口：[GasThermalModel](../../model/src/main/java/mmm/thermal/GasThermalModel.java)
  - 测试选择器：`thermal`

- **气液压力耦合**
  - 代码入口：
    - [PressureSystem](../../solver/src/main/java/mmm/fluid/PressureSystem.java) 与 [PressureFaceGraph](../../solver/src/main/java/mmm/fluid/PressureFaceGraph.java)
    - [GasVolumeProposal](../../solver/src/main/java/mmm/fluid/GasVolumeProposal.java) 与 [ConservativeLiquidFlux](../../solver/src/main/java/mmm/fluid/ConservativeLiquidFlux.java)
    - [PressureBoundaryView](../../solver/src/main/java/mmm/fluid/PressureBoundaryView.java)
  - 测试选择器：`gas-liquid`

- **定义与存档**
  - 代码入口：[GasRegistryCodec](../../fabric/src/main/java/mmm/fluid/air/GasRegistryCodec.java)
  - 测试选择器：`region-state`

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [模型边界](#模型边界)
- [权威数据](#权威数据)
- [拓扑变化](#拓扑变化)
- [压力与热耦合](#压力与热耦合)
- [持久化](#持久化)

## 模型边界

气体采用有限气腔与孔流网络。
密闭腔和固定受限通道两侧保存有限库存，经完整固定开放面上的正干口连通至外部的空间采用无限环境节点。
控制器验证的闭壳限定生产区域，命令临时域保持环境开放。

腔内按共同温度、压力处理，支持压缩、膨胀、传热、相变和有限孔流。
空间动量、声波传播及腔内混合层超出这一集总模型的支持范围。

### 微盒气体几何

`GasComplementGeometry.capture` 从固定开放微盒扣除 PLIC 湿区，生成气体块及共享干面。
捕获按微盒及面顺序线性扫描，延续时复用未变化单元及未受编辑影响的开口，输出不可变几何。
几何积分和 PLIC 求逆使用浮点计算，后续库存分配保留整数和精确数量结算。
解析尺度的液封通过共享干面面积为零阻断气腔。
采用低 Mach 集总均压模型：完整固定开放面上的任何实际正干口将两侧气体合为同腔，液面切过单元面时保持同一规则。
方块固定碰撞形成的受限孔口保留有限面积孔流。
完整固定开放面内由液体形状形成的细颈道沿用腔内瞬时均压近似，腔内声学和颈道瞬态压降按单元解析尺度简化。

节点容量由原始液体库存、固体占容和开放体积推导，再按干区几何分配到微盒。
浮点几何误差与整数库存结算分别评价，具体容差由几何实现与测试维护。

### 运行中的共同几何

`GasRuntimeGeometry` 保存不可变的共同几何、容量和每格八项微盒归属。
输入包括单元液体分量的体积与固定开放支持、固体占容、固定碰撞和尺度。
未来载体编辑同时覆盖相关输入，并重建受影响的 PLIC 与邻格连接，再经 `GasComplementGeometry.capture` 生成共同后继。

液体输运、颗粒运动、相变和外部编辑都先准备完整气腔后继，再联合提交载体、归属、库存与内能。
计划绑定原几何、库存和热学代次，来源变化后重新规划。
捕获和冷恢复共用这套几何；工作区及缓存从实际状态派生。

单格摘要仅在所有正气体块归属相同时返回一个节点。
完整拓扑始终保留各微盒归属，压力查询的整格限制见[压力与热耦合](#压力与热耦合)。
离线格级对照入口为 `AirCavityScanner`。

## 权威数据

- 有限节点由规范微盒归属和最小气体块 key 标识，节点编号是本次拓扑内的稠密索引。
- 组分保存稳定 ID、整数标准体积 nanos、独立 kg 质量及可选物种和质量余量。
- 热区域逐节点保存总内能 `U`。
- 外部源汇按标准 nanos、质量整数、物种与质量余量整数独立记账。

气体质量与质量余量使用[统一质量整数](system-overview.md#units)，拆并转移精确闭合。压力、密度和温度由实际库存、容量与内能派生。
环境节点按世界定义提供组成，标准态为 `101325 Pa`、`293.15 K`。
环境比例精确和为一，整数库存按共同权重分配。

注册表声明每个组分的密度、允许物种和默认组成。
相变、拓扑、源汇和恢复均检查物种隶属关系；挥发物种还须具有气相热学参考能。
注册表、节点、组分和载荷容量由对应定义与 codec 限定。

`AirNetwork` 统一管理节点、库存、容量、连接和物质审计的修订号。
气体与热学计划在首次修改前共同核对来源，全部后继通过预检后一起发布。
跨相计划还绑定未来载体几何，过期后继不能覆盖更新的库存。

已启动闭域中新暴露且缺少来源的气腔保存显式真空，组分、质量和内能为零。
从环境截留气体时按真实容量建立库存并记外部源；闭域内拓扑变化保持物质守恒。

## 拓扑变化

`GasTopologyCoordinator` 根据新旧干区的正体积交集和可达连接交接库存：

- 一对一携带完整库存、原始内能与旧控制体积。
- 一对多按共同几何权重拆分，整数 nanos 和物种总量保持一致，质量与能量余量统一分配。
- 多对一汇总组分、物种、质量余量和内能。
- 有限库存进入环境记热汇和物质汇；环境被截成有限腔记对应外部源。

旧节点失去几何交叠时，库存按固定开口分量上的时序可达图延续。
同一单元的固定开口分量承接液面重建带来的气泡位置和形状变化，跨单元连接取旧或新完整干面以及真实相接的时序干面。
满液单元与固定隔墙切断搬运路径，有限孔口继续由孔流网络结算。
同一开口分量内的多个无交叠后继按实际容量分配库存与内能，这是单元尺度的一阶形状近似；已有交叠的分裂保持实际交叠权重。
后继为环境节点时，库存与内能共同进入外部汇。
有限库存缺少合法后继时报告具体原因，保留原完整状态。

液体退让扩大已有气腔时，按原控制体积计算膨胀功。
撤去固定固体暴露的新空间按真空容量合并，采用自由膨胀的能量处理。
已有真空节点参与拆并时保留其实际控制体积，避免重复计算容量与功。

外部注液、提取和清空联合准备未来载体几何、气腔后继及热账。
普通注液压缩按体积变化结算功，提取形成的独立空腔保存真空。
同次提交后，观察和存盘读取相同后继。

相变按未来单元液体体积、组成分量与固体占容重建气腔，联合交接库存与内能。
相界内部交换保持两相总内能；随后真实运动改变容量时照常结算气体机械功。
相变热力学与待同步体积见[热学参考](thermal-state.md#相变)。

## 压力与热耦合

纯液体和含固相区域共用 MAC 压力主干。
接触液体的有限气腔以集总可压缩储存项进入同一压力系统，体积随正绝对压力沿当前库存与内能确定的等熵曲线变化。
有界 Newton 迭代更新气体储容切线，共同 PCG 使用同一液体和颗粒惯性响应。
收敛判定核对实际非线性容量误差与液体方程残差，容量误差按单元解析尺度评价。
活动颗粒影响气腔容量时，以相同体积导数构造压力响应，方程见[颗粒参考](granular-state.md#统一排水与压力)。

求解可使用共同气压参考以减小数值相消，节点库存、内能和实际压差保持原值。
临时压力未知量在每次投影中重建，实际节点压力继续从库存、容量和内能求得。

`PressureFaceGraph` 按液体接触面的开放微盒解析气体节点，液体压力、颗粒压力与阻力使用同一组面份额。
同一 MAC 面可以连接多个独立气腔，压力作用与体积散度严格互为转置。

压力解给出的气腔体积作为本步预测，与液体原库存共同进入固定开放分量的容量分配图。
气体可退让到原归属分量及完整固定开放面连接的新干区，液体经过实际供体到接收分量的通路重新分配。
实际孔容限制双方的联合分配，避免先固定气体空间后制造液体无法到达的接收要求。
优先保持气腔预测体积；仅当共同残量图无法增广时，压缩已到达气腔以释放恰好需要的空间，并保留正的可表示份额。
库存与内能随最终实际气腔几何交接，压力、温度和压缩功由实际容量重新结算；空间分配与压力预测的差异属于时间一阶误差。

气液相变按蒸气分压评价饱和条件，保留同格其他材料及独立气腔身份。
多接触界面的选择和联合能量事务见[气液相变](thermal-state.md#气液)。

### 状态方程

无热状态采用标准温度下的等温关系；热状态从实际内能反演共同温度：

```text
p_abs = P0 * (Vstd / Vavailable) * (T / T0)
p_gauge = p_abs - P0
rho_gas = total_mass_kg / Vavailable
R_total = P0 * Vstd / T0
gamma = 1 + R_total / C_v,total
K_s = gamma * p_abs
beta_s = 1 / K_s
```

`GasThermalModel.HydrodynamicState` 从同一份库存和比热定义推导压力、绝热体积模量及顺应性。
非空库存要求正容量和有效温度，派生压力直接使用状态方程。
空库存真空的绝对压力、密度、内能和无传质容量功均为零；展示温度采用区域配置值。

无传质的绝热容量变化满足：

```text
integral(Cv(T)/T dT) = R_total * ln(V_initial/V_final)
```

各比热段解析衔接，温区外沿端点热容延拓，内能变化记机械功。
孔流搬运源侧焓 `h = u + p*v`。
拆分气腔按原共同温度分配能量，合并汇总原始内能，存储舍入差单独入账。

压力曲线沿同一分段热容积分求解 `integral(Cp(T)/T dT) = R_total * ln(p_final/p_initial)`，再由状态方程派生正体积与顺应性。
最终几何交接沿实际体积变化结算机械功，保持内能、潜热和外部流审计的独立来源。

### 孔流

部分开放面聚合为有限面积的连接，使用孔流关系：

```text
Q = Cd * A * sqrt(2 * abs(delta_p) / rho_source)
```

同一节点对的平行开口合并面积与面积加权流量系数。
实际体积流量按源库存和容量换算为标准体积流量，环境流入同时考虑环境温度。
节点间交换为内部转移，环境流入流出分别记外部源汇。

每步一次读取各节点实际 EOS、温度、热容、库存与容量，建立正导通率的端口图。
热气体的标准体积储容为 `C = Vstd/(gamma*p_abs)`，等温气体取 `gamma=1`。
真空节点采用流入气体的实际物性建立初始储容。
冻结本步物性和孔口导通率后，`GasOrificeFlow` 求整体后向 Euler 储容方程：

```text
(C + dt*L_g) * p_next = C*p_old + ambient_terms
q_standard = dt*g*(p_next_source-p_next_target)
```

同一供体的所有出边联合限制可用库存和可携焓，流入库存留待后续步骤参与供给。
`GasInventory.partitionTransport` 一次分配供体所有份额；无法表示的整数余量留在供体。
`GasEnergyStore.planFlow` 按本步源侧温度计算每份实际整数库存携带的焓，在出入两侧使用同一通量。
环境流入、流出分别记物质与热源汇。
全部节点终态一次反演并核验有效温度和 EOS，库存与热账共同预检后一次提交。

该集总模型适用准静态气腔均压和有限孔口传质，冻结物性及算子分裂带来时间一阶温压误差。
验收检查宏观压差响应、正温、液封、库存与热账，并用时间细化评价误差。
无开放端口的液封气腔保持独立，腔内声波和喷流形状受当前模型解析度限制。
活动检查读取同代节点状态与可表示的压力驱动份额，维持微小孔流的连续推进。

## 持久化

热区域完整保存气体组分、节点内能、微盒归属和审计。
`pieceNodeOrdinal` 每格八项，`-1` 为无干区、`0` 为环境、正数为规范有限节点。
节点按最小气体块 key 排序，库存和内能采用同一顺序。

恢复从已提交液体、颗粒、连续固相与碰撞重建几何，验证完整归属、节点身份、容量及账本后发布。
载荷还绑定注册表指纹、单位与组分定义，字段校验见[存档参考](region-state.md)。
连接、压力、温度和孔流从恢复后的实际状态派生。

`VENT_SEALED_GAS` 联合移出密闭气体物质与内能，分别计入气体汇和热汇。
操作保留几何归属、容量与连接，将有限节点变为显式真空，并可正常保存与恢复。
后续库存来自真实孔流、相变或拓扑来源。

气体默认物种组成使用 `concentrationMolPerStandardCubicMeter`，以标准态 mol/m³ 表示，生成库存时转换为整数 μmol。
注册表快照分别以 `StandardDensityKgPerM3Bits`、`DefaultResidualDensityKgPerM3Bits`、`ConcentrationMolPerStandardM3` 保存标准态密度、质量余量密度与物种浓度；标准条件使用 `StandardPressurePaBits`、`StandardTemperatureKBits`。
标准体积按 nanos 编码取最近整数，低于半个 nanos 的环境截留量产生零库存；误差为每次不足 0.5×10⁻⁹ 标准 m³。
