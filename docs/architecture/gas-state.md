# 局部气体状态契约

气体按求解单元保存组分、整数质量、物种、质量余量与内能，液气共用 MAC 面动量与压力推进。单位与编码见[设计书](system-overview.md#units)，进度与运行证据分别见[实施计划](../roadmap/solver-consolidation-plan.md)和[验证指南](../maintenance/validation.md)。

## 代码入口

| 职责 | 入口 |
|---|---|
| 持久局部状态 | [SpatialGasState](../../model/src/main/java/mmm/fluid/air/SpatialGasState.java)、[VulkanGasStateLayout](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasStateLayout.java) |
| 混合状态方程 | [MixtureVolumeModel](../../model/src/main/java/mmm/thermal/MixtureVolumeModel.java)、[VulkanMixtureState](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMixtureState.java) |
| 气体热状态 | [GasThermalModel](../../model/src/main/java/mmm/thermal/GasThermalModel.java)、[VulkanGasThermodynamics](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasThermodynamics.java) |
| 几何与气相开口 | [VulkanLiquidGeometry](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidGeometry.java)、[VulkanGasGeometry](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasGeometry.java) |
| 面通量与气体输运 | [VulkanMixtureFlux](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMixtureFlux.java)、[VulkanLocalGasTransport](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLocalGasTransport.java) |
| 相变与膜传质 | [VulkanGasLiquidMassTransfer](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasLiquidMassTransfer.java) |
| 宿主编辑与查询 | [SpatialGasEdits](../../solver/src/main/java/mmm/fluid/SpatialGasEdits.java)、[DomainGasInventory](../../solver/src/main/java/mmm/fluid/DomainGasInventory.java) |
| 导入导出与存档 | [VulkanFluidStateCodec](../../gpu-kernel/src/main/java/mmm/gpu/VulkanFluidStateCodec.java)、[GasRegionStateCodec](../../fabric/src/main/java/mmm/simulation/persistence/GasRegionStateCodec.java) |
| 验证 | [GpuThermalPhaseValidation](../../gpu-kernel/src/test/java/mmm/gpu/GpuThermalPhaseValidation.java) |

## 状态与空间归属

`SpatialGasState` 按固定格索引保存库存与内能，空格保存零库存、零内能。温度、压力与气相体积由同代物质、内能、孔容与液体参考体积派生。每个计算格是一个固定开放连通空间，隔墙须由 `2×2×2` 微盒解析。

局部气体保留温差与组分差，沿实际开放面有限输运。液气共速描述能解析分开的气柱、液流与大界面；同一格面的两相逆流、未解析气泡滑移与声波细节为粗尺度近似。相邻单元的气相矩形交集给出通气面积，固定墙开口与相接触共同决定通路。

## 共同液气状态方程

液体保存参考体积 `V0`，真实占容 `Vl` 随共同绝对压力变化。孔容 `C`、液体体积模量 `B`、参考压力 `p0`、气体库存总气体常数 `R`、局部温度 `T`：

```text
P = B + p0
Vl = V0 (P - p) / B
Vg = R T (P - p) / (B p)
Vl + Vg = C
V0 p² + (C B + R T - P V0)p - P R T = 0
```

取稳定解析正根，压力随压缩平滑趋近 `P`。常压下 `Vl = V0`、`Vg = R T/p0`；没有气体且液体不足时剩余孔容为真空。真实液体密度为 `m/Vl`。这是服务方块尺度交互的有限压力粗尺度状态方程，以指定体积、温度与压力初始化气体时用同一方程反算标准态库存。

气体热状态按注册材料的分段热容与参考能反演，`R = p0 Vstd/T0`，`gamma = 1 + R/Cv`。令 `s = (P-p)/B`，共同压力的储容为：

```text
S = [V0/B + R T P / (B p² (1 + (gamma-1)s))] / (dt² h³)
```

真空的气体储容为零。绝对压力保持在 0 与 `P` 之间。液体弹性势能由同一方程派生并参加机械能交接：

```text
W = B V0 (Vl/V0 - 1)² / 2
```

## 面输运与压缩功

压力给出半隐式预测面速度后，上风 PLIC 平面与扫掠棱柱确定液体通量，气体通量取同一棱柱的剩余开放体积。两相分别受供体库存限制，实际接受量之和确定共同面速度；标准体积、质量、物种与余量沿整数份额交接，同一面只有一份气体质量通量，与液体通量共同进入 MAC 动量。接收格按后继库存重新计算占容与压力。完全排空时最后一份库存承接内能尾数。

局部压缩功沿状态方程积分。由实际接受通量得到平流后参考气容 `Vadv = Vold − Vout + Vin`，以步初热容 `Cv` 联立 `Cv dT = −p dVg` 与气相状态方程，得到绝热不变量：

```text
a = Cv B / (P R)
b = Cv R / (Cv B + P R)
(Vg + b T) T^a = (Vadv + b T0) T0^a
```

与终态孔容联立求温度和压力，宿主编辑与 GPU 推进使用同一规则；实际内能变化记为气体功，固定步初热容引入有限步近似。

开放域外侧采用环境压力、组成与温度，外界半控制体提供惯性；入流创建组分与内能，出流移出同一库存并记源汇，外界压力功按 `p0 × (实际流入体积 − 实际流出体积)` 记入机械能账。

## 编辑、相变与恢复

宿主冷启动按实际逐格孔容生成初态。注液、取液、注气与固体编辑准备完整私有后继：被占据的气容按编辑前压力计算待挤出份额，沿源格干口与接收格未来干口的连通关系分配到可达格，携带原组分、质量、内能与面动量，并在接收格结算压缩功。这是准静态排气近似；液封或闭壳使气体无出口时，剩余气体按绝热状态方程原地压缩，孔容归零且无出口的编辑被拒绝。开放外侧的逸出计入环境去向账。

凝固、融化与固液传质按液气共同质量交接 MAC 动量，动能变化交给区域内热载体。气液相变与膜传质读取接触单元的局部气体与液体：蒸发可直接写入空气体行，凝结和吸收消耗相应局部库存。有限墙的气体接触面可建立液相，混合气首滴使用各挥发组分的共同露点，已有液相按实际组成计算平衡；多个接触共享墙体有限热容。规则见[气液传质与相平衡](chemistry-state.md#气液传质与相平衡)和[气液相变](thermal-state.md#气液)。

区域保存逐格组分、内能、注册表身份与四项气体来源/去向账，温压与接触面积恢复后重新派生。

## 组分与收支

默认组分为 N₂、O₂、H₂O 蒸气、CO₂ 与乙醇蒸气，组分与环境比例由 `data/mmm/mmm/gas_components` 定义，摩尔质量读取对应化学物种。标准态为 101325 Pa、293.15 K，标准体积用于库存编码，实际占容用于状态方程。

环境输入、环境输出、相间输入与相间输出分别保存完整组分账；区域汇总时相间交换互相抵消，环境来源和去向作为外部收支。
