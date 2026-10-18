# 内能、热输运与相变契约

修改内能、热传导、反应热、相变和热设备时读本文。

## 修改入口

- **内能状态与流体数据映射**
  - 代码入口：
    - [ThermalDomainState](../../model/src/main/java/mmm/thermal/ThermalDomainState.java)
    - [FluidThermalBinding](../../solver/src/main/java/mmm/fluid/FluidThermalBinding.java)
  - 测试选择器：`thermal`

- **热浮力有效范围与分层**
  - 代码入口：
    - [LiquidThermalExpansion](../../model/src/main/java/mmm/thermal/LiquidThermalExpansion.java)
    - [LiquidCarrierProperties](../../solver/src/main/java/mmm/fluid/LiquidCarrierProperties.java)
    - [LiquidStratificationValidation](../../fabric/src/test/java/mmm/fluid/LiquidStratificationValidation.java)
  - 测试选择器：`materials,thermal`

- **传热与反应顺序**
  - 代码入口：
    - [ThermochemistryPipeline](../../solver/src/main/java/mmm/fluid/ThermochemistryPipeline.java)
    - [ThermalCarrierTransport](../../model/src/main/java/mmm/thermal/ThermalCarrierTransport.java)
    - [LiquidThermalConduction](../../solver/src/main/java/mmm/fluid/LiquidThermalConduction.java)
    - [ThermalTransport](../../model/src/main/java/mmm/thermal/ThermalTransport.java)
  - 测试选择器：`thermal`

- **固液和气液相变**
  - 代码入口：
    - [CondensedPhaseChangeStage](../../solver/src/main/java/mmm/fluid/CondensedPhaseChangeStage.java)
    - [GasLiquidPhaseChangeStage](../../solver/src/main/java/mmm/fluid/GasLiquidPhaseChangeStage.java)
    - [GasPhaseInterfaceResolver](../../solver/src/main/java/mmm/fluid/GasPhaseInterfaceResolver.java)
  - 测试选择器：`thermal`

- **相变体积的累计、恢复与力学同步**
  - 代码入口：[PendingPhaseVolumeWork](../../solver/src/main/java/mmm/fluid/PendingPhaseVolumeWork.java)
  - 测试选择器：`runThermalValidation --args="--phase-volume"`、`region-state`

- **有限热容量设备**
  - 代码入口：
    - [FiniteThermalDeviceInventory](../../model/src/main/java/mmm/thermal/FiniteThermalDeviceInventory.java)
    - [FiniteThermalExchange](../../model/src/main/java/mmm/thermal/FiniteThermalExchange.java)
    - [FiniteThermalDeviceCodec](../../fabric/src/main/java/mmm/simulation/persistence/FiniteThermalDeviceCodec.java)
  - 测试选择器：`thermal,region-state`

- **恒温源调节**
  - 代码入口：
    - [ThermalReservoirUseCase](../../app/src/main/java/mmm/application/ThermalReservoirUseCase.java)
    - [ThermalReservoirScreen](../../fabric/src/client/java/mmm/client/ThermalReservoirScreen.java)
  - 测试选择器：`protocol,thermal`

- **气体热力学与定义**
  - 代码入口：
    - [GasThermalModel](../../model/src/main/java/mmm/thermal/GasThermalModel.java)
    - [ThermalRegistryCodec](../../fabric/src/main/java/mmm/thermal/ThermalRegistryCodec.java)
  - 测试选择器：`thermal`

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [计算边界与单位](#计算边界与单位)
- [权威数据](#权威数据)
- [状态函数](#状态函数)
- [热输运](#热输运)
- [相变](#相变)
- [反应热](#反应热)
- [计算顺序](#logical-step-顺序)
- [能量审计](#能量审计)
- [持久化与容器](#持久化与容器)
- [调度与观察](#调度与观察)

## 计算边界与单位

热学随液体、颗粒、沉积、连续固相和气腔保存总内能。
内能使用 J；质量用 kg、温度用 K，热容用 J/K，导热率用 W/(m·K)。
参考态允许总内能为负，实际温度须有限且为正。

`ThermalDomainState` 统一管理各载体内能、审计和派生热网格。
`ThermalCellOperations` 处理局部平衡与能量分配，`FluidThermalBinding` 连接流体候选和热状态。
只有液体 marker 的 solver mass 需要乘 `cellSize³` 换算为 kg；其他载体与设备直接保存 kg。

单位约定统一见[设计书](system-overview.md#units)。
物种参考能字段为 `reference_internal_energy_j_per_mol`，材料参考能、比热、导热率分别使用 `reference_specific_energy_j_per_kg`、`specific_heat_j_per_kg_k`、`conductivity_w_per_m_k`。
相变批次质量字段为 `mass_kg_per_batch`，热注册表单位身份为 `mmm:si_mol`。

### 热化学尺度依据

| 数据 | 当前取值与处理依据 |
|---|---|
| CO₂ 水中相对气相参考能 | −19400 J/mol；旧数据 −19400000 以每千 mol 表示，除以 1000 保持原有溶解热效应 |
| 中和生成水的物种参考能 | −57300 J/mol；沿用玩法的强酸强碱近似，纠正原先把 J/mol 数值写入每千 mol 字段造成的千倍缩小 |
| Na⁺、Cl⁻ 固相参考能 | 各 −1950 J/mol，盐析出参考能差为 −3900 J/mol；同样纠正原先千倍缩小，反向溶解吸热 |
| 固液、气液潜热及比热 | 已按 J/kg 与 J/(kg·K) 定义，保留数值；同一物种跨相的参考能一致时严格相消 |
| 气液相变的容量变化 | SVQ 直接乘 2⁻²⁷ m³；清除重复乘 h³ 的尺度错误，默认格长下原容量变化被缩小 8 倍 |

盐溶解量级参照 [NIST-JANAF 的 NaCl 溶解焓资料](https://janaf.nist.gov/pdf/JANAF-FourthEd-1998-Chlorine.pdf)，采用玩法精度 3.9 kJ/mol。
反应在当前集总状态函数中按参考内能差处理；溶液体积变化及其压力功沿现有近似忽略。
纯换算与尺度纠错分别验证，纠错后的温升由实际 mol 数、参考能差和载体热容共同决定。

气液传质批次必须满足 `molesPerBatch × 10^6 = liquidQuantaPerBatch`。
Henry 常数为 mol/(m³·Pa)，气体分压采用绝对压力，液相浓度为 mol/m³；`D/filmThickness` 为 m/s，面积乘浓度差和仿真秒得到 mol。
CO₂ 默认批次为 0.001 mol、1000 μmol、24055 标准 nanos；气液独立 kg 差由批次质量余量保存。

## 权威数据

- 各液体 marker、active parcel、deposit group、coherent group 和 sealed gas node 保存总内能 `U`。
- 世界热学注册表保存材料参考能、分段比热、导热率、物种能量偏移、相变和恒温面定义。
- 外部热源、热汇、机械功、有限设备交换及舍入差分别记账。
- 温度、热容、焓、颜色与界面摘要从实际库存和 `U` 派生。

同一载体的质量、组成和内能使用相同索引与代次。
拆分、合并、沉积、提取和相变必须联合交接物质与内能；空载体内能为零。
所有相关库存、目标容量、热状态及账本在首次写入前完成预检，过期计划失效，失败保留原状态。

部分搬运按实际转移质量、整数物种和材料状态函数分配内能，剩余份额承接守恒余量。
多来源合并先汇总实际内能，再写入目标；最终存储舍入差独立入账。
各载体拟存储的内能都须能反演为有效状态，有效的单元平均值不能掩盖无效载体。

注册表与功能布局随世界能力包固定。缺失物性、引用错误或指纹不一致按恢复规则报告并保留原存档。
显示协议对温度和压力的量化、饱和只影响展示，模拟继续使用实际状态。

## 状态函数

对材料 `h`、质量 `m` 和物种数量 `n_s`：

```text
U_h(T) = m * u_ref,h
       + sum_s(n_s * epsilon_h,s)
       + m * integral(T_ref,h .. T) cv,h(theta) dtheta
```

每个 caloric segment 内比热为常量，积分与温度反演共用分段线性曲线。
同格异质载体使用局部热平衡温度（LTE），按完整质量、组成和总内能求解。
首尾段向标定温区外连续延拓；外推的相态精度受物性模型限制。
恒温设备等外部设定值仍受已提交注册表的标定范围约束。

相变平台由上下相在平衡温度处的状态能差决定。
候选必须满足有效正温、正热容、正密度、相变定义的适用条件以及库存和能量审计。
温度从实际写入的内能重新推导；舍入差进入账本。

参考能和热网格可以缓存。缓存绑定材料定义、实际质量、完整组成和相关代次，输入改变后重新求值。
缓存布局、求和实现与数值搜索策略见本节入口代码。

### 批量格温度观察

黏性和颗粒载体属性使用一次建格、多格读取的批量观察。
该过程只更新派生工作区，保留所有载体 `U`；局部反应或设备交换仅分配受影响单元的能量。
库存、组成、位置或内能变化后重建工作区，候选和冷恢复各自重新派生。

连续空间温度观察使用连通单相区域中的正三线性权重。
开放支撑被外壳截断时归一化；切割单元和同格多相采用所属单元近似。
质量、体积、内能和参考能沿同一权重采样，实际物种库存及内能归属保持原样。
热浮力与组成密度使用一致的空间支撑，避免采样差异产生虚假体力。

## 热输运

导热以有限热导接触交换实际载体内能。接触两侧的能量变化等大反向；导热率趋零时通量连续趋零。
普通世界外壳默认绝热，注册表声明的恒温方块与 `thermal_reservoir` 按相邻面热流计入外部源汇。
世界采样得到的面温度是本次计算输入，下一次采样替换。

`ThermalContactExchange` 对单个接触求解后向 Euler 热流。
进入第一侧的热量 `q` 满足：

```text
q = G * dt * (T_second(U_second - q) - T_first(U_first + q))
```

正热容保证有限接触的平衡方向。显式推进受最小载体热容量与总热导限制；
所需子步较多时改用正反向隐式接触扫描。这是时间一阶分裂，误差通过时间细化观察。
提交前验证全部拟存储状态，热浴交换和实际舍入差分别入账。

### 满液体域的有限载体导热

完全开放、充满液体、各格都有 marker 且属于同一压力物相的矩形域，使用 `LiquidThermalConduction`。
它按真实位置和体积建立正对称的紧支撑 B-spline 接触，按标准积分点标定扩散率。
绝热外壳使用镜像支撑，恒温墙热阻考虑真实墙距。

该离散适合近均匀的体积积分点；载体聚集、位移和重播种会改变离散误差。
代表性验证检查热扩散、线性恒温场、冷热尖峰和异质材料，具体误差值留在测试与验证证据中。
热学重播种保留总内能并控制同质载体的温度范围。

`thermal_reservoir` 的设定温度由世界 BlockEntity 保存。
调整温度会刷新相邻区域的恒温面并唤醒热学，能量随后通过有限热流进入区域。

### 有限热容量设备

`FiniteThermalBodyState` 保存设备材料、质量、总内能、注册表指纹和代次。
设备定位信息由 `FiniteThermalDeviceState` 保存；完整设备库存随 region HEAD 提交。
世界对象负责定位和接触，设备内能由区域库存持有。

`FiniteThermalExchange` 联合规划设备与有限流体端口的换热。
同一单元的多面热导先合并，再计算镜像热量；稳定步长覆盖所有可经过的比热段。
设备、流体实际内能及双方审计全部通过预检后一起提交，成功交换使旧计算候选失效。
跨区域设备需要协调所有区域的提交。

设备的世界接入、接触生成与生产调度状态见[当前实施计划](../roadmap/solver-consolidation-plan.md)。

### 有限体积离散

自由面、切割单元及固体混合域由 `ThermalTransport` 建立载体接触图。
同格热阻采用单元尺度混合长度，相邻格的接触面积受 PLIC 和切割几何限制：

```text
v_p = V_p / h³
k_pq = harmonic(k_p, k_q)
G_same_cell = 2*k_pq*h*v_p*v_q/v_cell
G_face = k_pq*A_contact/h*(v_p/v_cell_a)*(v_q/v_cell_b)
G_boundary = 2*k_p*A_contact/h*(v_p/v_cell)
Delta U_p = G*(T_q-T_p)*dt; Delta U_q = -Delta U_p
```

接触求解期间保持位置、质量、材料和组成不变，直接推进对应载体内能。
零导热载体随物质搬运内能；同格温差按有限速率消散。
气体孔流及气腔壁热交换由气体处理器执行。

内置物性的数值与采样分段以数据定义为准。
空气和蒸气的热容依据 [NIST 数据](https://webbook.nist.gov/chemistry/)标定，生成工具见[Python 工具](../maintenance/pytool.md)。

### 热浮力

液体采用小密度变化的 Boussinesq 近似，在重力项中使用：

```text
rho(T) = rho_ref * (1 - beta*(T-T_ref))
Delta u_y = g * dt * thermal_density_correction_face / rho_face
```

参考密度来自同一组成的实际质量与体积，温度来自实际载体内能。
组成密度和热密度修正使用一致的空间支撑、面惯性与界面距离加权。
这项体力修正保持质量与体积库存原样。

每种液体声明 `T_ref`、`beta` 和允许的相对热密度变化，内置上限为 `0.05`。
逐载体检查适用范围，再进行空间平均；越界报告具体材料和温度。
大温差相变测试可在微重力下单独验证热力学模型。

### 自然对流的线性有效域

常物性、小扰动、自由滑移受热液池使用 Rayleigh 数与线性增长率作代表性校验：

```text
nu = mu/rho
alpha = k/(rho*cv)
Ra = g*beta*DeltaT*H³/(nu*alpha)
```

验证覆盖临界点两侧的扰动衰减与增长，并检查网格和时间细化。
解析条件参见 [Rayleigh 原论文](https://doi.org/10.1080/14786441608635602)。
夹具尺寸、扰动与误差阈值由测试维护。

机械诊断分别记录黏性和速度传递耗散，当前导热方程尚未统一把机械耗散回填载体内能。
有限振幅运动的总能量评价需考虑这项模型限制。

### 气体能量与功

气腔从组分、容量和总内能派生共同温度与压力。
压缩、膨胀使用分段比热的理想气体等熵关系，孔流搬运源侧焓；合腔汇总内能，分腔按共同温度分配。
状态方程、真空和容量功的完整规则见[气体参考](gas-state.md#状态方程)。

## 相变

相变依赖热学功能，按注册表定义的完整守恒批次推进整数 extent。
批次数受可用物质、内能、容量、饱和条件和共享单步操作预算限制。
物质、组成、速度、内能、气腔后继和审计属于同一提交计划。
暂时无法表示完整批次时保留库存和待处理任务，定义与身份错误按具体原因报告。

试算使用未来实际载体的质量与组成求共同温度。
选定批次后准备完整内能分配与气腔拓扑，在首次物质写入前核对全部来源和代次。
提交后从实际载体重建受影响单元，其他单元保留原始内能。

### 固液

固液相变联合交接 kg、液体体积、coherent SVQ、组成与内能。
同格其他材料参与容量和局部热平衡，保持各自身份。
两相在固定区域体积内守恒交换内能，相界位移登记待同步体积。

平衡温度 `T_eq(p)` 使用最近真实压力投影的绝对压力。
注册表曲线在数据内线性插值，在数据外沿最近端点割线延伸；结果仍须为有效绝对温度。
冷恢复先重建压力投影，再执行需要该压力的热学计算。

批次搜索选择更接近平衡温度的可提交整数状态。
剩余温差反映批次量化、库存或容量限制，避免连续步骤在相邻批次间反复冻融。
搜索细节由 `ThermalPhaseExtentCurve` 与相变阶段代码维护。

内置冰/水使用固定体积批及单一固相载体。
熔化曲线采样自 [IAPWS 熔化曲线](https://iapws.org/public/documents/MdUFK/MeltSub2011.pdf)，
高压下各冰相的密度差异仍属于该表示的近似。

### 气液

气液方向由蒸气分压与液体温度对应的饱和压力决定：

```text
p_sat(T) = P0 * exp[-L/R * (1/T - 1/T_eq)]
p_partial = p_absolute * Vstd,vapor / Vstd,total
```

潜焓从上下相参考状态能和体积功推导，气体常数来自标准体积定义。
沸腾或凝结同时受真实热量、库存、目标容量与批预算限制。
化学启用时，仅允许气体组分声明且具有气相参考能的物种挥发。

接口使用 PLIC 的实际湿干接触，供体、接收者和新增位置属于对应液体连通分量。
同格其他材料保留身份并参与热浴和容量。
单格邻接多个独立气腔时保留各腔；每个压力区间选择分压失衡最大的可执行气腔交换。

搜索先在当前接触控制体积内评价候选饱和残量，SVQ 体积变化直接乘 2⁻²⁷ m³。
选定沸腾批次后准备完整拓扑，并以合并后库存、内能及实际容量复核饱和方向；超出平衡的完整批次保留到后续步骤。
完整汽化将凝聚相剩余内能交给气体，完全凝结将气体剩余内能交给接收载体。
凝结后仍有气体容量时按剩余蒸气分压评价饱和；全部蒸气凝结且容量同时归零时删除对应气腔。
有限气体保有正容量，零库存真空可随几何消失。

集总气体采用零输入动量的凝结近似：新增液体为零速，加入已有载体时按实际质量混合，保留原液体动量。
此过程的混合动能损失尚未统一转为热，适用于平均气体动量可忽略的场景。

## 反应热

反应先规划完整整数 extent，再联合提交物种、固体、内能与审计。
物种参考能随组成搬运；反应改变参考能后，总内能通过状态函数表现为温度变化。
外部注热才进入热源列，同一反应能量按一条账结算。

析出、溶解和合并使用完整未来载体库存预检热状态，再分配组成记录并提交物质。
失败保留原库存及待处理任务。

## Logical step 顺序

完整计算步见[当前设计书](system-overview.md#physics)。热化学轴的顺序为：

1. 捕获颗粒反应待处理单元，完成载体交接和有限导热。
2. 液相化学反应，再执行颗粒析出与溶解。
3. 固液相变，再以剩余共享预算执行气液相变。
4. 颗粒形态转换、重播种和局部唤醒，最后检查审计。

`PendingPhaseVolumeWork` 精确累计逐格待同步体积，随完整候选和存档保留。
载体已经保存相变后的几何，下一次真实压力投影完成力学同步后清除记录。
该记录标记同步需求，相界内部交换的能量已在相变步骤结算。

外部物质转移前先完成已有相变几何同步。
管理员、玩家容器和发射器共用这一流程，具体入口见[相变几何同步](runtime-and-lifecycle.md#phase-work)。
失败保留待处理记录和原库存。

## 能量审计

每个可发布状态满足：

```text
U_inventory
= U_initial + external_source - external_sink
 + mechanical_work + finite_device_exchange + roundoff_residual
```

库存汇总液体、活动颗粒、沉积、连续固相和气腔的实际内能。
载体间导热、反应交接、相变与气腔拆并属于内部转移。
物质进出区域、恒温热浴和管理员注热按实际交接进入外部源汇。

有限设备与流体分别保存方向相反的交换账，双方实际内能变化与舍入账在区域内联合闭合。
管理员清空同样记录真实移出内能；排气使密闭节点成为零库存、零内能的真空。
实际存储的舍入差进入精确账本，温度保持为派生观察。

## 持久化与容器

完整热区域同时保存载体、气腔归属、所有内能、审计、热浮力身份和待同步相变体积。
捕获前同步共同几何，恢复先验证载体数量、身份、能量汇总和账本，再绑定求解器。
冷恢复从保存的实际内能推导温度。

`ThermalContents` 将物品完整物质库存、注册表指纹与总内能绑定，桶内采用一个平衡温度。
部分倾倒按实际材料、物种和状态函数拆分，两份内能共同保存源能量，存储舍入纳入区域事务。
抽取多载体时先汇总真实移出能量，在物品存储处统一舍入，差额进入区域舍入账。

容器 WAL 同时保存物质量、物品前后状态和耦合能量；物品耐久存盘后才清理事务。
缺少能量结算能力的配方不能剥离带热量的物品数据。
字段和提交顺序见[存档参考](region-state.md)与[容器事务](world-ownership-and-containers.md)。

## 调度与观察

力学与热学是独立活动轴。导热、反应、相变、气体变化、设备交换与载体运动按实际变化唤醒对应计算。
内部热流也可能改变浮力或气压，因此热学推进后可唤醒力学；玩家暂停优先。
休眠保持库存，恢复条件由调度模块维护。

查询与界面只读实际库存派生的温度、总内能及气腔摘要。
运行时物理状态失败归入数值暂停；持久格式、定义或身份损坏归入锁定，恢复依据保留的权威状态与事务记录。
