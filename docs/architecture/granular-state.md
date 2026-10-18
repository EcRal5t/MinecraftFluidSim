# 颗粒与沉积状态契约

修改颗粒运动、沉积、孔隙水或析出与溶解时读本文。

单位与整数编码统一见[设计书](system-overview.md#units)。

## 修改入口

- **库存和压力耦合**
  - 代码入口：
    - [GranularInventory](../../model/src/main/java/mmm/granular/GranularInventory.java)
    - [GranularCouplingFields](../../model/src/main/java/mmm/granular/GranularCouplingFields.java)
  - 测试选择器：`granular`

- **运动、反应和沉积**
  - 代码入口：
    - [GranularMechanicalStage](../../solver/src/main/java/mmm/fluid/GranularMechanicalStage.java)
    - [GranularPressureCoupling](../../solver/src/main/java/mmm/fluid/GranularPressureCoupling.java)
    - [GranularReactionStage](../../solver/src/main/java/mmm/fluid/GranularReactionStage.java)
    - [GranularTransitionStage](../../solver/src/main/java/mmm/fluid/GranularTransitionStage.java)
  - 测试选择器：`granular`

- **水流起动与薄沙床阻力**
  - 代码入口：
    - [GranularBedShear](../../solver/src/main/java/mmm/fluid/GranularBedShear.java)
    - [BodyForceStage](../../solver/src/main/java/mmm/fluid/BodyForceStage.java)
    - [GranularDepositDynamics](../../model/src/main/java/mmm/granular/GranularDepositDynamics.java)
  - 测试选择器：`granular`

- **定义与存档**
  - 代码入口：[GranularRegistryCodec](../../fabric/src/main/java/mmm/granular/GranularRegistryCodec.java)
  - 测试选择器：`region-state`

- **物品转移**
  - 代码入口：
    - [MinecraftContainerTransferPlan](../../fabric/src/main/java/mmm/simulation/MinecraftContainerTransferPlan.java)
    - [ContainerItemMapper](../../fabric/src/main/java/mmm/item/ContainerItemMapper.java)
  - 测试选择器：`containers,granular`

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [定义与功能配置](#registry-与功能配置)
- [独立保存的数据](#独立保存的数据)
- [数量、布局与容量](#数量布局与容量)
- [生产模型](#生产模型)
- [固液相转移](#固液相转移)
- [容器与清空](#容器与清空)
- [审计与可观察量](#审计与可观察量)

## Registry 与功能配置

颗粒功能依赖材料功能，沉淀还依赖化学功能。
世界能力包保存材料、载体和反应的稳定身份、整数批次及依赖指纹，区域载荷采用相同功能布局。
定义或身份不匹配时保留原存档并锁定。

`ParticulateMaterial` 定义密度、代表粒径、孔隙率、安息角、摩擦、恢复、临界 Shields 数及侵蚀率。
数据定义的 `reference_density_kg_per_m3` 使用 kg/m³，注册表快照以 `ReferenceDensityKgPerM3Bits` 保存原始位。
`GranularCarrier` 保存液相材料对应的阻力标定范围；动态黏度由材料注册表提供。
载体、组成、沉积组和单步工作量具有容量限制，具体常量由对应库存与配置代码维护。
容量不足时保留待处理库存，材料和组成身份随物质交接。

## 独立保存的数据

- 活动颗粒保存位置、速度、材料、整数 SVQ、kg 质量、组成、内能、确定性身份及支撑接触状态。
- 沉积组按单元保存材料、SVQ、kg 质量、组成和内能，床层形状与混合孔隙率由库存派生。
- 连续凝固相由相变层的 coherent store 保存，具有独立库存与容量。
- 外部源汇、边界冲量及反应审计分别记账。

同代数组索引连接载体、组成与内能，拆并和移除共同更新。
质量和内能总账按实际存储值汇总，存储顺序变化应保持账本一致。
活动颗粒体积改变后重新判定接触和沉积资格。

合并按实际质量汇总位置和速度，并检查代表球、联合占容及热状态。
单点表示能够保留输入角动量时才合并；含未承接相对旋转的来源保留独立载体。
转换中采用的空间与速度近似按宏观动量、势能及耗散评价，精度要求见[整合计划](../roadmap/solver-consolidation-plan.md#精度与物理取舍)。

## 数量、布局与容量

固体库存采用整数 SVQ，`1 m³ = 2^27 SVQ`，默认 `0.5 m` 单元为 `2^24 SVQ`。
颗粒与沉积质量使用[统一质量整数](system-overview.md#units)，拆分以 SVQ 比例分配整数并保留余数；随后的内能分配使用实际整数质量份额，覆盖抽取、沉积唤醒和床层移动。
活动颗粒采用结构分离数组；沉积采用按单元组织的有限规范组。

材料孔隙率 `phi_i` 描述静止松散床中的空隙比例。
混合床采用各材料松散体积可加的近似：

```text
Vbulk = sum_i(Vs_i / (1 - phi_i))
Vpore = Vbulk - sum_i(Vs_i)
phi_effective = 1 - sum_i(Vs_i) / Vbulk
```

床体积计算后统一向上取整到 SVQ，用于床高、静态装填和孔隙阻力。
液体容量由几何开放体积减固体骨架体积得到，孔隙水继续由普通液体载体保存。
床体形成和孔隙率变化通过联合占容计划交接液体。

## 生产模型

活动代表颗粒承担重力、阻力、压力和碰撞，平静库存转为单元床层。
液体几何、质量与动量输运、压力和黏性使用共同主干，颗粒和床层通过惯性、占容与阻力项耦合。
床层以安息角松弛、有限侵蚀和局部休眠控制成本。

### Active parcel 力学

每个机械子步先预测重力，再联合求解液体、颗粒阻力与压力。
浸没比例和液体物性按连续位置采样，阻力采用球形颗粒关系：

```text
Re = rho_f * d * |u_f - u_p| / mu
Cd = max(24/Re * (1 + 0.15*Re^0.687), 0.44)
```

零滑移采用有限 Stokes 系数，高 Reynolds 数沿牛顿阻力近似推进。
每个机械子步以初始滑移计算阻力系数，随后按该正系数隐式求解有限质量的液固速度。
液固反作用等大反向，阻力消耗相对动能；系数的时间滞后是一阶近似，误差由代表沉降速度、阻力响应和正常时间步收敛评价。
该关系的精度受球形代表粒径和阻力相关式适用条件限制。

代表球与固定微盒、实际床体及其他代表球共用预测接触集合。
固定几何保留连续碰撞检测，将实际轨迹经过的接触平面交给质量加权位置投影；球间接触按共同终点约束求解。
位置与法向速度采用固定次数的对称投影 Gauss–Seidel 扫描，每行按实际质量施加等大反向冲量，固定接触承担外壳或床体反冲。
材料恢复以压缩阶段释放的动能限定回弹，切向运动按 Coulomb 摩擦处理；接触动能损失沿碰撞热量交接进入内能。
每个已解析接触分量的数值计算量与接触行数及固定扫描次数成正比，细尺度残余运动由后续正常步骤推进。
位置发布前共同核对接触、占容及坐标表示，固定几何动量变化记录为反冲量。
代表球之间采用集中定义的、随球半径缩放的不可解析接触容差，创建、运动和恢复共用；真实库存容量按整数占容检查。
位置修正的最大距离及质量高度差独立记录，用于量化粗尺度接触的势能误差。

接触模块分工：

- `GranularActiveContacts`：预测接触、局部位置约束与轨迹发布。
- `GranularContactCompression`：固定成本的成对冲量与质量加权位置投影。
- `GranularComponentResponse`：接触组件的压缩、恢复、摩擦和回弹能量预算。
- `GranularSpawnAdmission`：创建、冷恢复和运动落点的球体准入。

接触组件的具体迭代、索引、缓存与收敛阈值由这些实现维护。
正常密集场景的连续推进与完整步成本由[验证指南](../maintenance/validation.md)检查。

### 载体属性工作区

`LiquidCarrierProperties` 为液体黏性、颗粒阻力、床面剪切与 Darcy 阻力提供共同物性。
密度来自实际质量与体积；温度来自真实载体内能；黏度来自已绑定材料的 `mu(T)`。
热学启用时，湿格必须提供有效温度；无热布局使用标准温度。

工作区按阶段批量构建，库存、组成、内能或位置改变后重建。
温度观察保持载体内能，候选和恢复从各自状态派生工作区。
单格黏度采用主导材料近似；材料温区外取声明端点值，并发布越域诊断。

### 统一排水与压力

每子步从活动颗粒、沉积和连续固相建立共同固体占容。
活动颗粒使用归一化三线性核分摊至最多八格，沉积和连续固相按所属单元计入。
压力求解中的孔隙面积、体积导数和气腔响应使用同一子步初态。

令 `D u` 为固定孔口的面通量散度，`G v` 为实际单元 CIC 占容变化率，`I u` 为颗粒位置处的归一化液体速度：

```text
q = (u, v)
C = (D + G*I, -G)
C q = 0
L = M + dt * B^T K B
q_next = L^-1 (M*q_star + dt*cellVolume*C^T*p)
A_pressure = rho_reference*cellVolume*C*L^-1*C^T + A_gas_storage
```

`M` 使用液体面与颗粒的实际惯性，`B` 采样相对滑移，`K` 为正阻力系数。
`GranularImplicitDrag` 提供含阻力惯性的压力算子，`PressureLinearSolver` 与 `PressurePreconditioner` 为纯液体和含颗粒区域提供共同 PCG 及不完全 Cholesky 预条件。
压力与阻力修正联合写入最终速度。
阻力惯性在本子步分解一次，有限气腔的非线性状态方程复用这些因子；Courant 时间细分覆盖同一完整候选。

`GranularKernel.volumeGradient` 对整数占容同源的、截断域归一化单元 CIC 权重求 binary64 一阶导数。
压力散度采用颗粒与液体的相对速度 `G*(I*u-v)`，压力反力严格使用同一矩阵的转置。
归一化采样 `I` 将颗粒压力冲量等大反向交给液体，内部总冲量闭合。
`PressureFaceGraph` 保存这些局部稀疏项，预条件器保留实际邻接项及完整对角，远邻项由完整压力算子求解。

`GranularFaceVolumes` 为物质输运提供有界的活动颗粒面占容近似；其余真实孔口用于液体和气体的体积分配。
实际单元孔容由整数固体占容扣除，物质通量按最终速度与真实供体液体比例推进。
单元 CIC 与面占容的空间差异按时间一阶体积平均模型评价，终点库存和容量由共同分配事务精确闭合。

域外截断的 CIC 轴在半格范围内保持同一单元归属，其竖向导数无法解析静水浮力。
该处按局部实际液体密度与浸没固体体积补充被截断的竖向浮力，反向冲量记录为固定墙反力，机械功单独计入固定作用观察账。
一般位置沿实际 CIC 压力导数获得浮力，近墙补项避免重复。
实际 CIC 体积变化、近墙浮力、内部压力总冲量和有限气腔代数分别由颗粒算子验证覆盖。

实际位置的整数 SVQ 分配必须总量精确、逐格满足联合容量，并随位置一起保存和恢复。
`GranularIntegerAllocation` 为共同候选选择合法核份额，液体输运、气腔和颗粒提交使用同一结果。
位置或数量改变后重新规划，原来源改变时计划失效。

`GranularQuantizationError` 按格计算面通量预测与最终整数核占容变化的实际差额，包含空间离散与整数舍入差异。
共同输运沿开放通路重分配对应液体，保持物质、组成、动量及内能交接，最终逐格核对真实容量。
容量、核结点反向运动和连续推进要求见[计划 M06](../roadmap/solver-consolidation-plan.md#m06-联合占容与整数可表示性)。

### Deposit 松弛与局部唤醒

持续低速受支撑的颗粒可以转入沉积。
计划先确认到接收床面的可达路径，再按完整候选的未来床高选择接收层，联合核对活动球相容性和液固容量。
沉积后由重力驱动的安息角松弛形成稳定坡面，投放过程允许瞬态陡坡；局部地貌和显式静止球共同表示最终沙堆。
接收床是从所在单元底面起、覆盖该单元横截面的长方体。

沉积与松弛共同交接整数体积、质量、组成和内能。
液体排开与气腔后继采用相同未来占容；转换前后的宏观重心与势能同时检查。
床层松弛采用局部水平移动加竖直下落路径，障碍物决定接收位置是否可达。

`GranularLiquidDisplacement.prepare` 准备液体排开，`GranularInventory.WakePlan` 准备床库存取出与活动颗粒追加。
来源、目标位置、组成、能量及账本全部预检后提交。
起动势能变化由 `GranularBedPotential` 按实际整数质量、床层形状与目标高度计算。
`GranularBedShear` 读取床格及上方格开放水平 MAC 面的实际 SI 质量和持久动量，以统一缩放支付该势能变化。
全部来源在首次颗粒写入前预检，再提交零初速颗粒与预备格面动量；实际动量差作为床面反冲。
实际动能变化与势能变化之差进入 `GranularDepositDynamics.Stats.mechanicalRounding`，热库存保持原有交接规则。
颗粒随后由共同压力与阻力加速。

脏列及相邻列执行有限的安息角松弛，安静后休眠。
混合表示的宏观坡度按共同体积分配观察；显式代表球附近的表面高度误差以球半径量化，纯床体区域沿用静态安息角要求。
真实流动步仍检查暴露床面的剪切，几何变化、沉积和反应唤醒局部邻域。
操作预算耗尽后保留库存和待处理列；冷恢复从已提交床层重建调度状态。

该床层采用单元地貌近似，代表颗粒转为床层时的细尺度运动会耗散。
转换耗散、联合提交和代表性床面流程的整合要求见[计划 M07](../roadmap/solver-consolidation-plan.md)。

### 水流起动与床面输运

`GranularBedShear` 从床面上方液流、材料黏度和床面距离估计剪切，应力通过 Shields 判据判断起动：

```text
theta = tau / ((rho_s-rho_f)*g*d)
```

壁面关系采用 Spalding 模型，侵蚀量受超临界程度、注册表速率、可用 SVQ 和几何容量约束。
几何与能量条件通过后释放零初速颗粒，液流通过共同阻力和压力传递动量。
静止或亚临界水流保持床库存，薄床上方的自由流动按层状近似保留。

### 薄沙床与孔隙阻力

`BodyForceStage` 使用 Kozeny–Carman 渗透率：

```text
Kp = d²*phi³ / (180*(1-phi)²)
H = (1-f)*h
Kt = f*Kp + (1-f)*H²/3
u_next = u / (1 + dt*mu/rho*resistance)
```

`f` 为床层占格比例，切向按床层与自由层平行流动近似，法向叠加实际床厚的阻力。
每面统一执行隐式 Darcy 更新，再进入压力投影。
该模型覆盖薄床自由流与满床孔隙流，精度由单元内层状流动假设限制。

## 固液相转移

析出与溶解按注册表完整反应批次规划，联合验证液体物种、固体组成、真实质量、内能及未来占容。
活动固体使用连续位置的核份额，沉积和连续固相使用各自空间归属。
目标载体或组成容量不足时保留原库存和待处理单元。

转移物质携带原速度：液体、活动固体取实际速度，沉积取静止速度。
液体物种按实际质量份额分配，固液转移的整数质量按同一实际质量权重交接；组成质量余量重新计算并保持整组精确闭合。
物种量子数与数值样本数分别结算，空物种样本保留正的实际质量及对应内能。
接收载体按实际质量合并输入动量，最终速度的存储误差独立评价。
新析出位置优先由转移质量一阶矩确定，再检查代表球、联合容量和热状态。
表示受限时采用合法的确定性生成位置；这种空间近似可能改变重心、势能及未承接的内部角动量。

反应计划在首次写入前准备物种、固体、能量、审计与待同步相变体积。
部分 extent 保持完整，预算耗尽后由后续步骤继续扫描。
连续凝固与熔化由 coherent store 处理，热力学规则见[热学参考](thermal-state.md#相变)。

## 容器与清空

干颗粒容器保存完整 SVQ、逐材料质量及可选固相组成。
玩家和发射器作用于区域实际目标格，并通过容器 WAL 联合提交区域与物品。
部分提取先规划剩余颗粒的合法占容与热状态，再准备气腔容量和功的后继。

凝聚相清空联合提交液体、活动颗粒、沉积和连续固相的移出账，气体由独立排气操作处理。
待同步相变几何先经过真实力学同步，流程见[运行时参考](runtime-and-lifecycle.md#phase-work)。

## 审计与可观察量

体积、逐材料质量和可选物种分别满足：

```text
initial + external_source - escaped_sink
= active + deposit + coherent + transferred_inventory
```

机械质量、组成质量余量和内能使用各自实际存储列汇总；冲量与热能在对应账本中闭合。
`status` 的颗粒数、沉积组数、床高、孔隙率和休眠列数均为只读摘要。
恢复使用完整库存和接触状态，再重建几何与派生属性。

固液饱和参数 `saturation_concentration_mol_per_m3` 以反应进度的 mol/m³ 表示；内部每个 extent 对应 1 μmol，计量系数作用于各真实物种。
精确批次的 extent、物种 μmol 和 SVQ 由注册表共同定义。
