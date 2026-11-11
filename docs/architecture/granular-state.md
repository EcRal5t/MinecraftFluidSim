# 颗粒与沉积状态契约

修改颗粒运动、沉积、孔隙水或析出与溶解时读本文。单位与整数编码统一见[设计书](system-overview.md#units)。

## 修改入口

| 职责 | 代码入口 | 验证 |
|---|---|---|
| 库存与压力耦合字段 | [GranularInventory](../../model/src/main/java/mmm/granular/GranularInventory.java)、[GranularCouplingFields](../../model/src/main/java/mmm/granular/GranularCouplingFields.java) | GPU 颗粒及完整步场景 |
| 运动与接触 | [VulkanGranularMotion](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGranularMotion.java) | GPU 颗粒及完整步场景 |
| 液固共同压力与阻力 | [VulkanMixturePressure](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMixturePressure.java) | GPU 颗粒及完整步场景 |
| 床层转移、起动与松弛 | [VulkanGranularTransitions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGranularTransitions.java)、[GranularDepositDynamics](../../model/src/main/java/mmm/granular/GranularDepositDynamics.java) | GPU 颗粒及完整步场景 |
| 析出与溶解 | [VulkanSolidLiquidReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanSolidLiquidReactions.java) | GPU 颗粒及完整步场景 |
| 定义与存档 | [GranularRegistryCodec](../../fabric/src/main/java/mmm/granular/GranularRegistryCodec.java) | `region-state` |
| 物品转移 | [MinecraftContainerTransferPlan](../../fabric/src/main/java/mmm/simulation/MinecraftContainerTransferPlan.java)、[ContainerItemMapper](../../fabric/src/main/java/mmm/item/ContainerItemMapper.java) | `containers` |

执行方式见[验证指南](../maintenance/validation.md)。

## 定义与功能配置

颗粒功能依赖材料功能，析出与溶解还依赖化学功能。世界能力包保存材料、载体和反应的稳定身份、整数批次及依赖指纹，区域载荷采用相同功能布局。

- `ParticulateMaterial` 定义密度、代表粒径、孔隙率、安息角、摩擦、恢复系数、临界 Shields 数与侵蚀率；参考密度以 kg/m³ 定义。
- `GranularCarrier` 保存液相材料对应的阻力标定范围，动态黏度由材料注册表提供。

载体、组成、沉积组和单步工作量都有容量上限，容量不足时保留待处理库存。

## 库存

- **活动颗粒**：位置、速度、材料、整数 SVQ、kg 质量、组成、内能、确定性身份与支撑接触状态，按结构分离数组保存。
- **沉积**：按单元保存材料、SVQ、kg 质量、组成和内能；床层形状与混合孔隙率由库存派生。
- **连续固相**：由相变层的 coherent store 保存。
- 外部源汇、壁面冲量与反应审计分别记账。

固体体积采用整数 SVQ，`1 m³ = 2^27 SVQ`，默认 0.5 m 单元为 `2^24 SVQ`。拆分按 SVQ 比例分配整数质量并保留余数，内能按实际整数质量份额分配。合并按实际质量汇总位置与动量，动能损失与势能变化交给内能。

混合床按各材料松散体积可加近似：

```text
Vbulk = sum_i(Vs_i / (1 - phi_i))
Vpore = Vbulk - sum_i(Vs_i)
phi_effective = 1 - sum_i(Vs_i) / Vbulk
```

床体积向上取整到 SVQ，用于床高、静态装填和孔隙阻力。孔容等于几何开放体积减去固体骨架体积，孔隙水仍由普通液体库存保存，液气实际占容由局部状态方程求得。

## 生产模型

活动代表颗粒承担重力、阻力、压力与碰撞，平静后转为单元床层。液体几何、输运、压力与黏性使用共同主干，颗粒与床层通过惯性、占容和阻力耦合。床层以安息角松弛、有限侵蚀和局部休眠控制成本。

### 活动颗粒力学

每个机械子步先预测重力，再与液体共同求解压力和最终速度，最后处理接触并结算整数占容。阻力采用球形颗粒关系：

```text
Re = rho_f * d * |u_f - u_p| / mu
Cd = max(24/Re * (1 + 0.15*Re^0.687), 0.44)
```

阻力系数按子步初始滑移计算，再隐式求解有限质量的液固速度；反作用等大反向，消耗的相对动能进入内能。精度受代表粒径与球形假设限制。

代表球与固定微盒、床体及其他代表球共用接触模型：

- 固定几何沿实际轨迹做连续碰撞检测，球间接触采用软代表接触与局部质量加权位置投影。
- 速度响应在同一冲量扫描中处理法向恢复与 Coulomb 摩擦，回弹能量受压缩释放动能约束，球间冲量成对守恒，固定表面承担反作用。
- 接触组件保留整体平移与可解析旋转，未解析的形变与自旋可以耗散；摩擦、非弹性碰撞与多体压缩的动能损失连同压力、阻力交换一起结算到内能。
- 受支撑的静止代表累积接触时间，满足沉积条件的一组代表共同转入床体。

接触迭代次数、空间索引与收敛阈值由 `GranularActiveContacts`、`GranularComponentResponse` 等实现维护；密集场景的连续推进由[验证指南](../maintenance/validation.md)检查。

### 载体物性

`LiquidCarrierProperties` 为液体黏性、颗粒阻力、床面剪切与 Darcy 阻力提供共同物性：密度来自实际质量与体积，温度来自载体内能，黏度来自材料 `mu(T)`。无热布局使用标准温度，材料温区外使用端点近似。同格物性按实际材料体积与水溶液组成求值。

### 统一压力

每子步从活动颗粒、沉积和连续固相建立共同固体占容。活动颗粒以归一化三线性核分摊至最多八格，权重按固定几何的开放微格比例计算，整数份额合计等于颗粒实体积；宿主规划、设备计算与存档共用这一规则。

`VulkanGranularGeometry` 提供流体面孔隙率 `Af` 与颗粒互补份额。固定床与连续固相的面孔隙率取相邻两格的实际孔容平均：

```text
epsilon_face = 1 − (Vfixed_left + Vfixed_right) / (Vopen_left + Vopen_right)
```

液体与颗粒在同一压力方程中投影，颗粒通过互补面份额形成固相体积散度 `B`：

```text
q = (u, v), H = [C, B], M = diag(M_face, M_solid)
R = (M + dt * D)^-1
q_next = R * (M q_star + dt * f_known + dt * h^3 * H^T p)
H q_next = gas_volume_response
A_pressure = rho_reference * h^3 * H R H^T + A_gas_storage
```

`M_face` 使用实际液体与气体质量，`D` 是按源滑移准备的液固阻力。液体承担面压力的 `Af` 份额，活动颗粒按代表体积权重承担互补份额，两者合计恢复完整开放面载荷，在代表尺度上给出静水浮力。液固力的动能变化、势能变化与气体压缩功沿共同机械体结算到内能，见[单元库存与 MAC 动量](fluid-solver.md#单元库存与-mac-动量)。

### 沉积与床层松弛

持续低速且受支撑的颗粒转入沉积：计划先确认到接收床面的可达路径，按未来床高选择接收层，并联合核对液固容量。接收床是从单元底面起、覆盖整个横截面的长方体。沉积、松弛与唤醒共同交接整数体积、质量、组成和内能，液体排开与局部气体后继使用同一未来占容。

脏列及相邻列按材料安息角做有限松弛，路径由水平移动加竖直下落构成，跨列转换要求重力势能不增加，安静后休眠。几何变化、沉积和反应唤醒局部邻域，预算耗尽时保留待处理列，冷恢复从床层重建调度状态。

### 水流起动

`GranularBedShear` 读取床面上方开放 MAC 面的液体质量与动量，按 Spalding 壁面关系估计剪切，再用 Shields 判据判断起动：

```text
theta = tau / ((rho_s - rho_f) * g * d)
```

侵蚀量受超临界程度、注册表速率、可用 SVQ 与几何容量约束。起动释放零初速颗粒，起动势能由上方液体动量支付，动量差作为床面反冲；颗粒随后由共同压力与阻力加速。

## 固液相转移

析出与溶解按注册表完整反应批次规划，联合核对液体物种、固体组成、质量、内能与未来占容。传质面积按单格真实固体体积与一次粒径得到总球面面积，润湿面积受液体体积限制（扩散膜厚取半个粒径），与代表颗粒数量无关。

转移物质携带原速度，整数质量按实际质量权重交接，组成余量保持整组闭合。新析出物优先按质量合并到同组成的活动代表；没有空间时并入同格床库存，床体承担反冲，动能与势能差交给内能。预算耗尽时部分 extent 由后续步骤继续。

连续凝固与熔化由 coherent store 保存固相，相变改变孔容并登记待机械同步格，后续共同压力与状态方程处理占容变化。热力学规则见[相变](thermal-state.md#相变)。

固液饱和参数 `saturation_concentration_mol_per_m3` 以反应进度的 mol/m³ 表示，每个 extent 对应 1 μmol。

## 容器与清空

干颗粒容器保存 SVQ、逐材料质量与可选固相组成，经容器 WAL 与区域联合提交。部分提取先规划剩余颗粒的合法占容与热状态。凝聚相清空联合移出液体、活动颗粒、沉积与连续固相，待同步相变几何先经过力学同步，见[运行时参考](runtime-and-lifecycle.md#phase-work)。

## 审计

体积、逐材料质量与物种分别满足：

```text
initial + external_source - escaped_sink
= active + deposit + coherent + transferred_inventory
```

机械质量、组成余量和内能按实际存储列汇总，冲量与热能在各自账本闭合。`status` 显示颗粒数、沉积组数、床高、孔隙率和休眠列数。
