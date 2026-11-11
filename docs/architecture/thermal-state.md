# 内能、热输运与相变契约

修改内能、热传导、反应热、相变和热设备时读本文。

## 修改入口

- 内能、状态函数和热账：[ThermalDomainState](../../model/src/main/java/mmm/thermal/ThermalDomainState.java)、[ThermalRegistry](../../model/src/main/java/mmm/thermal/ThermalRegistry.java)。
- 设备热接触与导热：[VulkanThermalContacts](../../gpu-kernel/src/main/java/mmm/gpu/VulkanThermalContacts.java)、[VulkanThermalConduction](../../gpu-kernel/src/main/java/mmm/gpu/VulkanThermalConduction.java)。
- 设备反应与相变：[VulkanRegionReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanRegionReactions.java)、[VulkanCondensedPhaseChange](../../gpu-kernel/src/main/java/mmm/gpu/VulkanCondensedPhaseChange.java)、[VulkanSolidLiquidReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanSolidLiquidReactions.java)、[VulkanGasLiquidMassTransfer](../../gpu-kernel/src/main/java/mmm/gpu/VulkanGasLiquidMassTransfer.java)。
- 世界墙与有限设备：[WorldThermalEnvironment](../../fabric/src/main/java/mmm/simulation/WorldThermalEnvironment.java)、[WorldThermalTopology](../../fabric/src/main/java/mmm/simulation/WorldThermalTopology.java)、[BlockThermalMaterials](../../fabric/src/main/java/mmm/simulation/BlockThermalMaterials.java)、[FiniteHeatExchangerOperations](../../fabric/src/main/java/mmm/simulation/FiniteHeatExchangerOperations.java)。
- 库存编辑及恢复：[HostFluidState](../../solver/src/main/java/mmm/fluid/HostFluidState.java)、[RegionCheckpointRestorer](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointRestorer.java)。
- 验证：`runGpuThermalPhaseValidation`、`runGpuThermalContactsBenchmark`、GPU 演示和 `region-state`，按改动范围选择。

## 计算范围与单位

热学随液体、颗粒、沉积、连续固相和逐格气体保存总内能 `U`（J）；温度、热容、焓与显示摘要都从实际库存和 `U` 派生。参考态允许总内能为负，实际温度须有限且为正。单位约定见[设计书](system-overview.md#units)。

热学 JSON 的主要字段：物种参考能 `reference_internal_energy_j_per_mol`，材料参考能、比热、导热率 `reference_specific_energy_j_per_kg`、`specific_heat_j_per_kg_k`、`conductivity_w_per_m_k`，相变批次质量 `mass_kg_per_batch`。溶解热、中和热和析盐热均由物种参考能之差表达，取值以数据文件为准。

## 权威数据

- 各载体库存保存总内能 `U`；世界热学注册表保存材料参考能、分段比热、导热率、物种能量偏移、相变和恒温面定义。
- 外部热源、热汇、机械功、有限设备交换及舍入差分别记账。
- 注册表随世界能力包固定；缺失物性、引用错误或指纹不一致按恢复规则报告并保留原存档。

拆分、合并、沉积、提取和相变联合交接物质与内能，空载体内能为零；相关库存、容量和账本在首次写入前完成预检，失败保留原状态。

部分搬运先确定实际整数质量与物种，再分配内能。设来源参考能为 `B_source`、显热为 `S_source = U_source − B_source`，每个正质量份额按自身组成计算 `B_part`，携带：

```text
U_part = B_part + (m_part / m_source) * S_source
```

最大质量份额承接浮点余量，多来源合并先汇总实际内能再写入目标，存储舍入差独立入账。液体面流、固体排液、外部提取、碰撞拆分和颗粒移动共用这一规则。原版水桶等只记录材料的容器在启用化学的世界中使用默认组成计算参考能，保持转移前后温度基准一致。

## 状态函数

对材料 `h`、质量 `m` 和物种数量 `n_s`：

```text
U_h(T) = m * u_ref,h
       + sum_s(n_s * epsilon_h,s)
       + m * integral(T_ref,h .. T) cv,h(theta) dtheta
```

每个分段内比热为常量，积分与温度反演共用分段线性曲线；首尾段向标定温区外连续延拓。物种参考能注册时换算为每微摩尔系数，整数物种数量直接乘该系数。

物性观察与反应、相变候选使用凝聚相局部热平衡温度，按完整质量、组成和总内能求解。局部能量分配按共同温度一次生成各载体内能，最大热容载体承接余数。相变平台由上下相在平衡温度处的状态能差决定。候选须满足有效正温、正热容、正密度和能量审计；温度从实际写入的内能重新推导。

连续空间温度观察在连通单相区域中使用正三线性权重，被外壳截断时归一化。热浮力与组成密度使用同一空间支撑，避免采样差异产生虚假体力。

## 热输运

导热以有限热导接触交换实际载体内能，接触两侧的能量变化等大反向。注册表声明的恒温方块与 `thermal_reservoir` 按相邻面热流计入外部源汇；`thermal_reservoir` 的设定温度由方块实体保存，调整后刷新相邻区域的恒温面。

### 共同凝聚相有限体积导热

液体、固相、气体、有限墙与恒温面构成同一张热接触图。热导使用材料调和平均、实际开放面积及库存占据份额；同格与固壁采用半格混合长度，相邻格采用一格长度，切格隔墙隔离接触。同格气液接触与相间传质共用 PLIC 内部界面面积，热阻由两相到界面的距离及各自导热率相加。恒温面与有限墙读取同代 PLIC 单侧面积，液体与气体分别取得实际湿面和干面。

液体与有限墙、恒温壁之间使用局部对流膜：当前液体物性、热膨胀系数与区域重力决定自然对流，液体相对墙面的切向速度决定强制对流。格长作为特征长度，竖壁使用 [Churchill–Chu 自然对流式](https://doi.org/10.1016/0017-9310(75)90243-4)，水平壁只在浮力不稳定方向增强换热，强制对流使用 [NASA 平板相关式](https://ntrs.nasa.gov/api/citations/20080022409/downloads/20080022409.pdf)。自然和强制 Nusselt 数按立方和合成，膜热导取该结果与几何导热的较大值，再与有限墙半厚度热阻串联。绝热材料的接触热导为零。

每条接触用同一份有限热量更新两端。`Cmin_i` 为载体最小正热容，`λ_i = Σ_j G_ij / Cmin_i`：

```text
G_ij = k_ij * A_effective / length,   k_ij = harmonic(k_i, k_j)
q_ij = dt G_ij (T_j - T_i) / (1 + dt (λ_i + λ_j))
U_i(new) = U_i(old) + Σ_j q_ij,       q_ji = -q_ij
```

恒温环境取 `λ_j = 0`，实际收放热量进入环境账。每个载体的交换权重总和小于其最小热容，温度保持在自身与接触邻居的范围内；小时间步趋近显式有限体积导热，强接触和粗时间步下均温较慢。导热阶段保持位置、质量、材料和组成不变。

气体组分比热与参考能共同决定气格温度，导热率按标准体积分数混合。N₂、O₂、CO₂ 的热容由 [NIST Shomate 系数](https://webbook.nist.gov/chemistry/)积分，水蒸气采用 [JANAF 气态水焓表](https://janaf.nist.gov/tables/H-064.html)，分段数据由 [Python 工具](../maintenance/pytool.md) 生成。

### 有限热墙与设备

`FiniteThermalBodyState` 保存设备材料、质量、总内能、注册表指纹和代次。世界固体、环境空气及墙面装填的热库存由 `WorldThermalEnvironment` 按维度和方块坐标持有，多个区域的接触引用同一世界节点；`WorldThermalTopology` 按碰撞微盒计算固体体积、连通空气及实际接触。新增、拆除与移动按实际库存结算，区域与环境候选共同采用，世界 HEAD 保存同一时点的环境和区域 checkpoint。

有限换热器安装在模拟区域相邻的空位，库存由区域持有，端口方向读取定义；区域成功步推进设备，暂停时保留库存。拆卸物品携带完整设备状态，再安装保持原热库存。安装、拆卸和破坏交接见[世界与容器事务](world-ownership-and-containers.md#有限换热器安装与拆卸)。

### 方块热材料

[BlockThermalMaterials](../../fabric/src/main/java/mmm/simulation/BlockThermalMaterials.java) 按方块标签 `data/mmm/tags/block/ordinary_<材料>_thermal_walls.json` 为固体选择有限墙定义 `data/mmm/mmm/finite_thermal_devices/ordinary_<材料>_wall.json`。每格墙的质量与初温来自定义文件，初温统一为 293.15 K。一个方块同时列入两个标签时加载报错。

| 标签 | 墙体质量 | 包含方块 |
|---|---|---|
| `metal` | 7800 kg | 铁块；本 Mod 的金属管、管道阀、管道筛网、阀门、机械驱动器、物料箱 |
| `copper` | 8960 kg | 全部铜块与切制铜块（含各氧化阶段、涂蜡、台阶和楼梯） |
| `gold` | 19300 kg | 金块 |
| `stone` | 2500 kg | 石头、圆石、石砖、深板岩、深板岩圆石、红砖块 |
| `wood` | 600 kg | 木板、原木、木台阶、木楼梯、木栅栏、木活板门、木门 |
| `glass` | 2500 kg | 玻璃、染色玻璃、玻璃板及其染色变体、本 Mod 的玻璃管 |
| `insulation` | 100 kg，导热率为零 | 本 Mod 的绝热块、绝热玻璃、绝热管、绝热玻璃管、流体端口、工艺传感器 |

以下方块使用专属定义：筛网及管道筛网的筛网部件使用 `mmm:screen`，有限换热器使用 `finite_heat_exchanger` 设备库存，`thermal_reservoir` 是设定温度的恒温面。

其余与流体接触的固体都按 `mmm:ordinary_stone_wall` 结算，例如泥土、沙子、砂岩、混凝土和其他模组方块。这些方块各带 2500 kg、293.15 K 的石质热库存，会从热流体吸热、向冷流体放热；在蒸气空间里，室温石墙成为额外冷凝面，凝液积在这些墙边，蒸馏或冷凝装置流入产品口的液量随之减少。装置出现预期之外的吸热或冷凝时，先用 [ApparatusDiagnostics](../../fabric/src/test/java/mmm/simulation/ApparatusDiagnostics.java) 列出逐墙能量变化与逐格液量，再给相应方块补标签。

### 固定表面吸附与催化

固定表面绑定有限墙的一面，保存材料身份、装填质量、已吸附物种与分数量子交换进度。表面材料由世界数据定义容量、温压适用范围、有效面积、交换速率、吸附物参考能和热容，以及适用的催化反应。墙体、装填材料和吸附物共享一份总内能。安装时表面载荷的内能进入墙体，拆卸时按当前墙温分出；同一面补充相同材料时合并库存，更换材料先取出原载荷。

每种吸附物按局部分压和温度得到竞争占位比例。有限速率交换由实际接触面积、占位差与请求时长决定，并受气体库存、空位点和两侧可用热量约束；低分压或升温促使解吸。筛的实体比例参与接触面积计算。液体催化接触将面积对应的倍率同时作用于已有反应的正逆方向，再进入原有竞争与参考能结算。

### 热浮力

液体采用线性体膨胀的正密度近似，在重力项中使用：

```text
rho(T) = rho_ref / (1 + beta*(T-T_ref))
Delta u_y = g * dt * thermal_density_correction_face / rho_face
```

每种液体声明 `T_ref` 与 `beta`。参考密度来自实际单元质量与体积，同格多种液体按实际质量汇总浮力修正。该体力修正保持质量与体积库存原样，大温差沿同一粗尺度关系延拓，真实相态由相变模型处理。

自由滑移受热液池的宏观对流以 Rayleigh 数 `Ra = g*beta*DeltaT*H³/(nu*alpha)` 与解析增长率参照评价（[Rayleigh 原论文](https://doi.org/10.1080/14786441608635602)），验证覆盖临界点两侧的增长与衰减方向。

### 耗散热与机械功

黏性应变、床层阻力和实体接触按实际动能损失生成热量，登记到源液体单元并随实际质量通量搬运。实体接触逐面使用实际冲量 `I = mΔv`，外功为规定实体速度与冲量的点积，外功扣除局部动能变化得到接触阻力热。压力、重力与外部实体的可逆做功由机械审计记录。格内多相按实际热容分摊正热，按最低允许温度以上的可用显热分摊负热。

气体的压缩和膨胀功按逐格状态结算，开放外侧的环境压力功使用实际净流入体积；完整规则见[气体参考](gas-state.md#共同液气状态方程)。

## 相变

相变依赖热学与化学功能，按注册表定义的完整守恒批次推进整数 extent。批次数受实际供体、内能、容量、饱和条件和跨格质量预算限制；物质、组成、速度、内能和气体后继属于同一提交计划。暂时无法表示完整批次时保留库存和待处理任务。

相界位移后，单元库存立即保存相变后的体积，待机械推进的格集合随候选和存档保留，下一次压力投影完成力学同步。外部物质转移前先完成已有的相变几何同步，见[相变几何同步](runtime-and-lifecycle.md#phase-work)。

### 固液

固液相变联合交接质量、液体体积、固相 SVQ、组成与内能，同格其他材料参与容量和局部热平衡。平衡温度 `T_eq(p)` 使用局部共同状态方程的绝对压力，注册表曲线在数据内线性插值、数据外沿端点割线延伸。批量受实际容量和全单元正温下限的能量约束，剩余温差反映批次量化与容量限制，避免相邻批次间反复冻融。

内置冰/水使用固定体积批与单一固相载体，熔化曲线采样自 [IAPWS 熔化曲线](https://iapws.org/public/documents/MdUFK/MeltSub2011.pdf)。

### 气液

挥发组分采用局部相平衡闭合，目标为 `p_i^eq = x_i p_i^sat(T)`，纯物质的摩尔分率为一。每条接触从当前库存和内能计算温度、热容与共同压力，按压力、组成和潜热的联合响应求取平衡差额。同一液体载体的全部物种共享热库存。水和乙醇采用理想混合近似，不解析共沸；网格内接触按快速相变近似处理。CO₂ 的 Henry 溶解保留有限速率，见[化学状态](chemistry-state.md#气液传质与相平衡)。

未润湿的有限墙面按当前气体和墙温求混合露点组成，冷凝热经墙面导热进入墙的真实热库存；绝热墙面没有这条散热路径，蒸气在其表面保持气态。

挥发与凝结按完整批次修改液体参考体积，逐格混合物状态方程重新计算占容和压力。每格跨格转出以步初液气质量为预算；本步混合热在批末按实际接收量结算，因此本步后续接触的温度反馈为有限步近似。总显热不足时拒绝候选。

## 反应热

反应先规划完整整数 extent，再联合提交物种、固体、内能与审计。物种参考能随组成搬运；反应改变参考能后，总内能通过状态函数表现为温度变化，溶液体积变化及其压力功忽略。外部注热才进入热源列。固液反应按实际内能、各物相正温下限和反应计量限定批量，其余物质继续参与后续传热和反应。

## 热化学顺序

完整计算步见[设计书](system-overview.md#physics)。热化学部分依次为：载体交接和有限导热；同相扩散、液相反应、颗粒析出与溶解；气液交换与凝聚相变；颗粒形态转换与局部唤醒；审计。

## 能量审计

每个可发布状态按下式核对实际内能：

```text
U_inventory
= U_initial + external_source - external_sink
 + mechanical_work + finite_device_exchange + roundoff_residual
```

载体间导热、反应和相变属于内部转移；物质进出区域、恒温热浴和管理员注热进入外部源汇。有限设备与流体分别保存方向相反的交换账，在区域内联合闭合。质量和物种使用精确整数守恒；内能使用浮点数，由 `ThermalAuditLedger` 按库存与交接规模判断数值误差，超出容差的差额拒绝发布。

## 持久化与容器

液体、颗粒、沉积、连续固相和逐格气体的内能随各自库存保存，区域 manifest 记录逐 section 热总账；冷恢复核验正温、状态方程与完整能量账后再建立派生温度。字段见[存档参考](region-state.md)。

`ThermalContents` 将物品完整物质库存、注册表指纹与总内能绑定，桶内采用一个平衡温度；部分倾倒按状态函数拆分，存储舍入进入区域事务。容器 WAL 同时保存物质量、物品前后状态和耦合能量，见[容器事务](world-ownership-and-containers.md)。耕地吸水把实际水相库存及其内能作为外部汇提交，见[耕地湿润](world-ownership-and-containers.md#耕地湿润)。

## 调度与观察

力学与热学是独立活动轴，导热、反应、相变、气体变化与设备交换按实际变化唤醒对应计算；热学推进可唤醒力学，玩家暂停优先。运行时物理状态失败归入数值暂停，持久格式、定义或身份损坏归入锁定。
