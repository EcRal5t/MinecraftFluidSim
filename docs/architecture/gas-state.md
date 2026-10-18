# 气体状态契约

修改气腔扫描、气体库存、压力响应或孔流时读本文。

单位与整数编码统一见[设计书](system-overview.md#units)。

## 修改入口

- **气腔几何和归属**
  - 代码入口：
    - [GasRuntimeGeometry](../../solver/src/main/java/mmm/fluid/GasRuntimeGeometry.java)
    - [GasTopologyCoordinator](../../solver/src/main/java/mmm/fluid/GasTopologyCoordinator.java)
  - 测试选择器：`air-cavity`

- **气体库存与孔流**
  - 代码入口：[AirNetwork](../../model/src/main/java/mmm/fluid/air/AirNetwork.java)
  - 测试选择器：`air-network`

- **气液相变库存与账本提交计划**
  - 代码入口：[AirNetwork](../../model/src/main/java/mmm/fluid/air/AirNetwork.java) 的 `planPhaseSource` / `planPhaseSink` / `commitPhaseMutation`、
    [GasLiquidPhaseChangeStage](../../solver/src/main/java/mmm/fluid/GasLiquidPhaseChangeStage.java)
  - 测试选择器：`thermal`、`air-network`

- **热孔流整数均压**
  - 代码入口：
    - [GasEnergyStore](../../model/src/main/java/mmm/thermal/GasEnergyStore.java)
    - [GasTransferSearch](../../model/src/main/java/mmm/thermal/GasTransferSearch.java)
    - [GasTransferValidation](../../fabric/src/test/java/mmm/thermal/GasTransferValidation.java)
  - 测试选择器：`thermal`

- **温度、压力和声学状态**
  - 代码入口：[GasThermalModel](../../model/src/main/java/mmm/thermal/GasThermalModel.java)
  - 测试选择器：`thermal`

- **气液压力耦合**
  - 代码入口：
    - [PressureBoundaryView](../../solver/src/main/java/mmm/fluid/PressureBoundaryView.java)
    - [PressureProjectionWorkspace](../../solver/src/main/java/mmm/fluid/PressureProjectionWorkspace.java)
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
密闭腔和受限通道两侧保存有限库存，完整干面连通至外部的空间采用无限环境节点。
控制器验证的闭壳限定生产区域，命令临时域保持环境开放。

腔内按共同温度、压力处理，支持压缩、膨胀、传热、相变和有限孔流。
空间动量、声波传播及腔内混合层超出这一集总模型的支持范围。

### 微盒气体几何

`GasComplementGeometry` 从固定开放微盒扣除 PLIC 湿区，生成气体块及共享干面。
几何积分和 PLIC 求逆使用浮点计算，后续库存分配保留整数和精确数量结算。
解析尺度的液封通过共享干面连通性阻断气腔。

节点容量由原始液体库存、固体占容和开放体积推导，再按干区几何分配到微盒。
浮点几何误差与整数库存结算分别评价，具体容差由几何实现与测试维护。

### 运行中的共同几何

`GasRuntimeGeometry` 保存不可变的共同几何、容量和每格八项微盒归属。
输入包括完整液体分量的体积与位置矩、固体占容、固定碰撞和尺度。
未来载体编辑同时覆盖相关输入，并重建受影响的 PLIC 与邻格连接。

液体输运、颗粒运动、相变和外部编辑都先准备完整气腔后继，再联合提交载体、归属、库存与内能。
计划绑定原几何、库存和热学代次，来源变化后重新规划。
捕获和冷恢复共用这套几何；局部工作区及缓存从实际状态派生。

单格摘要仅在所有正气体块归属相同时返回一个节点。
完整拓扑始终保留各微盒归属，压力查询的整格限制见[压力与热耦合](#压力与热耦合)。
离线格级对照入口为 `AirCavityScanner`。

## 权威数据

- 有限节点由规范微盒归属和最小气体块 key 标识，节点编号是本次拓扑内的稠密索引。
- 组分保存稳定 ID、整数标准体积 nanos、独立 kg 质量及可选物种和质量余量。
- 热区域逐节点保存总内能 `U`。
- 外部源汇、质量舍入与余量舍入独立记账。

气体质量直接使用 kg。压力、密度和温度由实际库存、容量与内能派生。
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

旧节点失去重叠后继时，沿实际干面连通查找可达后继。
极小未解析气团沿开放碰撞微盒的连通分量寻找最近的可解析气体后继，物质与内能随之守恒交接。
后继为环境节点时，两项同时进入外部汇。
该近似的尺度和可达判定由拓扑实现及液封测试维护。
有限库存缺少合法后继时报告具体原因，保留原完整状态。

液体退让扩大已有气腔时，按原控制体积计算膨胀功。
撤去固定固体暴露的新空间按真空容量合并，采用自由膨胀的能量处理。
已有真空节点参与拆并时保留其实际控制体积，避免重复计算容量与功。

外部注液、提取和清空联合准备未来载体几何、气腔后继及热账。
普通注液压缩按体积变化结算功，提取形成的独立空腔保存真空。
同次提交后，观察和存盘读取相同后继。

`StaticGasPistonSweep` 还支持轴向液面单调推进的准静态活塞操作。
适用几何为完全开放的源格、无附加固体占容，以及源腔通过唯一直接开口连接隔绝的接收腔。
它先守恒合并两腔库存、内能与原容量，再压缩至最终容量；普通力学仍按有限速率孔流推进。
具体几何准入由该处理器维护。

相变按未来液体位置、体积、组成分量与固体占容重建气腔，联合交接库存与内能。
相界内部交换保持两相总内能；随后真实运动改变容量时照常结算气体机械功。
相变热力学与待同步体积见[热学参考](thermal-state.md#相变)。

## 压力与热耦合

纯液体和含固相区域共用 MAC 压力主干。
接触液体的有限气腔以集总可压缩储存项进入同一压力系统，压力未知量由状态方程线性化派生。
活动颗粒影响气腔容量时，以相同体积导数构造压力响应，方程见[颗粒参考](granular-state.md#统一排水与压力)。

求解可使用共同气压参考以减小数值相消，节点库存、内能和实际压差保持原值。
临时压力未知量在每次投影中重建，实际节点压力继续从库存、容量和内能求得。

`PressureBoundaryView.wholeCellGasNode` 从微盒归属解析整格气体节点。
同格存在多个独立气腔时，拓扑保留各腔，该整格压力查询会报告 `GasTopologyBlockedException`。
相关整合要求集中在[当前实施计划](../roadmap/solver-consolidation-plan.md)。

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

极低温导致顺应性超出压力工作数组表示范围时，压力投影使用该节点实际压力作为给定值。
后续几何交接仍按实际容量结算功，数值表示限制的处理由压力实现负责。

### 孔流

部分开放面聚合为有限面积的连接，使用孔流关系：

```text
Q = Cd * A * sqrt(2 * abs(delta_p) / rho_source)
```

同一节点对的平行开口合并面积与面积加权流量系数。
实际体积流量按源库存和容量换算为标准体积流量，环境流入同时考虑环境温度。
节点间交换为内部转移，环境流入流出分别记外部源汇。

单步转移受真实可用库存、整数 nanos 与完整候选均压条件限制。
热状态同时搬运组分、物种及源侧焓，再以候选库存与内能重新求压力。
整数分配可能出现不可表示的份额，有界搜索选择可提交转移，具体策略由 `GasTransferSearch` 维护。

休眠检查沿用可执行的整数均压条件。
已无可转移份额时允许休眠，微小孔仍有压差驱动的可执行流量时保持活动。
搜索预算不足保留活动，真实计算报告具体失败原因。

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
