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
隐式阻力用步末滑移迭代，液固反作用等大反向，消耗相对动能。
该关系的精度受球形代表粒径和阻力相关式适用条件限制。

代表球与固定微盒、实际床体及其他代表球在共同时间轴上处理最早碰撞。
成对法向冲量按实际质量分配，固定接触按材料恢复和 Coulomb 摩擦处理。
位置发布前共同核对接触、占容及坐标表示，边界动量变化记录为反冲量。

接触模块分工：

- `GranularActiveContacts`：空间查询、共同时间轴与轨迹发布。
- `GranularComponentResponse`：接触组件的压缩、恢复、摩擦和法向复检。
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

令 `D u` 为液体孔隙面通量散度，`G v` 为颗粒运动导致的体积变化：

```text
q = (u, v)
C = (D, -G)
C q = 0
L = M + dt * B^T K B
q_next = L^-1 (M*q_star + dt*cellVolume*C^T*p)
A_pressure = rho_reference*cellVolume*C*L^-1*C^T + A_gas_storage
```

`M` 使用液体面与颗粒的实际惯性，`B` 采样相对滑移，`K` 为正阻力系数。
`GranularImplicitDrag` 与 `GranularCoupledPressureSolver` 共享阻力响应和质量矩阵。
压力与阻力修正联合写入最终速度，非线性迭代及时间细分覆盖同一完整候选。

宏观压力动量守恒还要求同步平移条件 `C t_axis = 0`。
现有孔隙面面积采用相邻可用体积分数的较小值，颗粒采用连续核导数，二者存在空间不一致。
因此内部压力可留下液固总冲量；外壳截断核也可能遗漏浮力。
离散压力功的一致性需要与这些空间误差分别检查，处理范围见[计划 M05](../roadmap/solver-consolidation-plan.md#m05-液体与颗粒共同压力离散)。

实际位置的整数 SVQ 分配必须总量精确、逐格满足联合容量，并随位置一起保存和恢复。
`GranularIntegerAllocation` 为共同候选选择合法核份额，液体输运、气腔和颗粒提交使用同一结果。
位置或数量改变后重新规划，原来源改变时计划失效。

浮点位移与整数核份额产生的局部量化差由 `GranularQuantizationError` 估计。
液体重分配保持物质、组成、动量及内能共同输运，超出表示误差范围时重新计算完整子步。
容量、核结点反向运动和连续推进要求见[计划 M06](../roadmap/solver-consolidation-plan.md#m06-联合占容与整数可表示性)。

### Deposit 松弛与局部唤醒

持续低速受支撑的颗粒可以转入沉积。
计划先确认到接收床面的可达路径，再联合计算最终床高、邻列安息角、活动球相容性和液固容量。
接收床是从所在单元底面起、覆盖该单元横截面的长方体。

沉积与松弛共同交接整数体积、质量、组成和内能。
液体排开与气腔后继采用相同未来占容；转换前后的宏观重心与势能同时检查。
床层松弛采用局部水平移动加竖直下落路径，障碍物决定接收位置是否可达。

`GranularLiquidDisplacement.prepare` 准备液体排开，`GranularInventory.WakePlan` 准备床库存取出与活动颗粒追加。
来源、目标位置、组成、能量及账本全部预检后提交。
起动的正势能由邻近液流水平动能支付，床面反冲量独立记录，颗粒随后由共同压力与阻力加速。

脏列及相邻列执行有限的安息角松弛，安静后休眠。
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
