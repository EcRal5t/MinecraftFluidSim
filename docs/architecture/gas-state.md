# 局部气体状态契约

气体按求解单元保存组分、整数质量、物种、质量余量与内能。液气共用 MAC 面动量和压力推进。单位与编码见[设计书](system-overview.md#units)，活动进度与运行证据分别见[实施计划](../roadmap/solver-consolidation-plan.md)和[验证指南](../maintenance/validation.md)。

## 状态与空间归属

`SpatialGasState` 按固定格索引保存库存与内能，空格显式保存零库存、零内能。温度、压力和气相体积由同代物质、内能、孔容与液体参考体积派生。计算格保持一个固定开放连通空间；固定碰撞由每格 `2×2×2` 微盒表示，隔墙须由网格解析。

局部气体保留温差和组分差，沿实际开放面有限输运。液气共速描述可解析分开的气柱、液流和大界面；同一格面的两相逆流、未解析气泡滑移以及声波细节采用粗尺度近似。

液体界面为单轴平面：由体积分数梯度选择坐标轴，按开放微盒的两层截面积直接求平面位置。单侧相接触为矩形；相邻单元的气相矩形交集给出通气面积。固定墙开口与相接触共同决定通路。几何近似允许阶梯自由面与斜向接触误差，物质库存由守恒交接维护。

## 共同液气状态方程

液体精确库存保存参考体积 `V0`，真实占容 `Vl` 随共同绝对压力变化。令孔容为 `C`、液体体积模量为 `B`、参考压力为 `p0`，气体库存的总气体常数为 `R`，局部温度为 `T`：

```text
P = B + p0
Vl = V0 (P - p) / B
Vg = R T (P - p) / (B p)
Vl + Vg = C
V0 p² + (C B + R T - P V0)p - P R T = 0
```

采用稳定的解析正根，压力随压缩平滑趋近 `P`。常压下 `Vl = V0`，气体回到 `Vg = R T/p0`；纯液不足时剩余孔容为真空。真实液体密度为 `m/Vl`。这是服务方块尺度交互的有限压力粗尺度状态方程；精确库存和能量仍沿实际份额守恒。 以指定体积、温度和压力初始化气体时，同一方程反算标准态库存。

气体热状态按注册材料的分段热容和参考能反演。`R = p0 Vstd/T0`，`gamma = 1 + R/Cv`。令 `s = (P-p)/B`，共同压力的储容为：

```text
S = [V0/B + R T P / (B p² (1 + (gamma-1)s))] / (dt² h³)
```

真空的气体储容贡献为零。共同压力使用实际面惯性、开放面积和该储容；含颗粒时复用颗粒有限质量与阻力耦合。求解增量与状态方程压力共同作用，绝对压力保持在零与 `P` 之间。该半隐式推进采用时间分裂近似。

液体弹性势能由同一状态方程派生：

```text
W = B V0 (Vl/V0 - 1)² / 2
```

它参加机械能交接；显热由物质内能保存。

## 面输运与功

共同压力首先给出半隐式预测面速度；上风 PLIC 平面与实际扫掠棱柱确定液体通量，气体通量取同一开放棱柱的剩余体积。液体与气体分别按供体库存限定出流，实际接受量之和确定共同面速度；两相库存、动量和能量按这些面量交接。接收格由同一状态方程按实际后继重新计算占容和压力。

气体的标准体积、质量、物种与质量余量沿共同整数份额交接；同一面只有一份实际气体质量通量，它与液体通量共同进入 MAC 动量。 气体输运与机械重力势能使用同一面路线索引访问实际交接量。完全排空时最后一份真实库存承接内能尾数，空行保持零内能，浮点存储差单独记账。

局部压缩功沿同一状态方程积分。先从实际接受通量得到平流后参考气容 `Vadv = Vold − Vout + Vin`，冻结该库存的步初热容 `Cv`。将 `Cv dT = −p dVg` 与气相状态方程联立，得到绝热不变量：

```text
a = Cv B / (P R)
b = Cv R / (Cv B + P R)
(Vg + b T) T^a = (Vadv + b T0) T0^a
```

以对数形式联立终态孔容求温度和压力，宿主编辑与 GPU 推进采用同一规则。材料分段热容和参考能确定最终内能，实际内能变化记为气体功；冻结热容引入有限步近似，液体弹性势能变化单独结算。真空气格没有热库存与压缩功。液气共同的格面动能、实际质量路线重力势能、液体弹性能、气体功与颗粒机械量沿同一机械体结算；导热、相变与外部能源沿各自接受量记账。

开放域外侧采用环境压力、组成及温度。外界半控制体提供相应惯性，实际入流创建组分和内能，实际出流移出同一库存并记源汇。外界压力功按 `p0 × (实际气体流入体积 − 实际气体流出体积)` 记入机械能账。密封外侧由几何阻断。环境库存仅在明确初始化与实际入流时生成。

## 编辑、相变与恢复

宿主冷启动按实际逐格孔容生成初态。显式的集总场景初态可在首次导入时按干体积一次分配到单元；连续推进始终读取局部状态。宿主注液、取液、注气及固体编辑准备完整私有后继，局部状态方程与热功随同一事务交接。

注液或碰撞占据气容时，宿主以编辑前共同压力下的气容变化计算待挤出的库存份额。一次连通访问使用源格旧干口与接收格未来干口，按未来干容向可达接收格分配；接收干容累计达到待挤气体积时停止选择。气体份额携带原组分、质量、物种、内能和面动量，接收库存的原有温度和组分保持独立的实际初态后合并；搬入体积与原气容共同参与接收格压缩功结算。该冷编辑采用有限空间内的准静态排气近似，连续推进使用设备面输运。液封或闭壳使气体无可达出口时，剩余气体留在原格按绝热状态方程压缩；孔容归零且无出口的编辑由事务拒绝。开放外侧的实际逸出计入环境去向账。外部装填或几何操作改变液体弹性能，热审计记入实际进入内能的气体压缩功和混合耗散。

设备凝固、融化及固液传质按液气共同质量与开放外侧半格环境惯量交接 MAC 动量，实际动能变化结算给区域内热载体。气液相变和膜传质按接触单元读取局部气体与液体。蒸发可直接写入空气体行，凝结和吸收消耗相应局部库存；物种、参考能、相变热与容量由共同候选交接。

有限墙的真实气体接触面支持建立液相。混合气首滴使用各挥发组分的共同露点和对应组成；已有液相使用实际组分计算平衡。气体内能按接受量分配给液体与墙，多个接触共享墙体的有限热容，墙的温度、库存和审计随同一请求提交。墙面液体导热使用共同湿壁几何给出的接触距离。

区域保存逐格组分、内能、注册表身份和四项气体来源／去向账。温压和接触面积恢复后重新派生，活动组暂停保留全部局部库存与内能。气体排空同时移出物质和内能，后续可由注气、真实外界入流或相变重新填充。

## 组分与收支

默认组分为 N₂、O₂、H₂O 蒸气、CO₂ 和乙醇蒸气，组分及环境比例由 `data/mmm/mmm/gas_components` JSON 定义，摩尔质量读取对应化学物种。标准态为 101325 Pa、293.15 K。气体标准体积与实际占容分别用于库存编码和状态方程。

每组分的质量与物种质量余量使用统一整数编码。环境输入、环境输出、相间输入与相间输出分别保存完整组分账。区域四相汇总时，相间交换互相抵消；环境来源和去向作为外部收支。温度、压力和密度均由真实库存派生。

## 代码入口

| 职责 | 入口 |
|---|---|
| 持久局部状态 | [SpatialGasState](../../model/src/main/java/mmm/fluid/air/SpatialGasState.java)、[VulkanGasStateLayout](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasStateLayout.java) |
| 混合状态方程 | [MixtureVolumeModel](../../model/src/main/java/mmm/thermal/MixtureVolumeModel.java)、[VulkanMixtureState](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMixtureState.java) |
| 气体热状态 | [GasThermalModel](../../model/src/main/java/mmm/thermal/GasThermalModel.java)、[VulkanGasThermodynamics](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasThermodynamics.java) |
| 共同几何与气相开口 | [VulkanLiquidGeometry](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidGeometry.java)、[VulkanGasGeometry](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasGeometry.java) |
| 共同面通量与局部气体输运 | [VulkanMixtureFlux](../../gpu-kernel/src/main/java/mmm/gpu/VulkanMixtureFlux.java)、[VulkanLocalGasTransport](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLocalGasTransport.java) |
| 相变与膜传质 | [VulkanGasLiquidMassTransfer](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasLiquidMassTransfer.java) |
| 宿主编辑与查询 | [SpatialGasEdits](../../solver/src/main/java/mmm/fluid/SpatialGasEdits.java)、[DomainGasInventory](../../solver/src/main/java/mmm/fluid/DomainGasInventory.java) |
| 导入导出与恢复 | [VulkanFluidStateCodec](../../gpu-kernel/src/main/java/mmm/gpu/VulkanFluidStateCodec.java)、[GasRegionStateCodec](../../fabric/src/main/java/mmm/simulation/persistence/GasRegionStateCodec.java) |
| 逐格气体压力、组分与小气容验证 | [GpuThermalPhaseValidation](../../gpu-kernel/src/test/java/mmm/gpu/GpuThermalPhaseValidation.java) |
