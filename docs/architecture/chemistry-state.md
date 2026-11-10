# 守恒化学状态契约

修改化学组分、反应或 pH 时读本文。

## 修改入口

- **组分状态和反应**
  - 代码入口：
    - [RegionChemistry](../../model/src/main/java/mmm/chemistry/RegionChemistry.java)
    - [VulkanLiquidReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidReactions.java)：分组、热可行性、最终库存及内能提交
    - [VulkanRegionReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanRegionReactions.java)：组装设备反应、相变与传质阶段
  - 验证入口：[M7ChemistryValidation](../../fabric/src/test/java/mmm/chemistry/M7ChemistryValidation.java) 检查定义；GPU 演示和反应场景验证设备推进
  - 测试选择器：`chemistry`

- **液体单元组成与提交**
  - 代码入口：
    - [LiquidCellInventory](../../model/src/main/java/mmm/inventory/LiquidCellInventory.java)
  - 测试选择器：`chemistry,cell-inventory`

- **化学定义编码**
  - 代码入口：[ChemicalRegistryCodec](../../fabric/src/main/java/mmm/chemistry/ChemicalRegistryCodec.java)
  - 测试选择器：`region-state`

执行方式见 [验证指南](../maintenance/validation.md#host)。

## 本文内容

- [Registry](#registry)
- [独立保存的数据](#独立保存的数据)
- [反应模型](#反应模型)
- [求解顺序](#求解顺序)
- [跨层边界](#跨层边界)
- [功能布局](#功能布局)

单位与内部编码统一见[设计书](system-overview.md#units)。

## Registry

新世界默认启用全部能力；世界化学能力依赖材料与热学，反应按同代内能结算。首次启用时，数据包定义被规范化为 world-local registry snapshot；此后该 snapshot 的 stable ID、exact rational 定义、预算和 fingerprint 是世界权威。

当前数据包与 snapshot 不一致只报告 definition mismatch，不重解释已有库存。snapshot 未知、损坏或身份不符时区域和容器状态保留并 LOCKED。

### 定义层次与规范化

数据包分别提供 species、chemical phase 和 reaction；species JSON 的 `molar_mass_kg_per_mol` 值以 kg/mol 解释：

- species：stable ID、组成基元整数向量、电荷、精确摩尔质量 kg/mol；
- phase：绑定机械材料，声明允许物种、`default_concentration_mol_per_m3`；油的允许物种和默认浓度均为空，机械质量全部进入 kg 余量；
- reaction：反应物/生成物整数计量和允许 chemical phase。

exact rational 只接受整数、有限小数或 `numerator/denominator` 文本。解析前先限制 ID 长度、文本长度、小数 scale 和整数位宽，避免用短文本触发无界 `BigInteger` 工作。
registry 校验未知 ID、重复 ID、非正质量、组成/电荷/质量不守恒、无界解离和预算，然后按 stable ID 排序并对 canonical UTF-8 bytes 计算 SHA-256。

`ExactRational` 在原输入位数上限内规范化分子与分母；二进制幂分母按分子尾零位精确约分，一般分母沿用整数最大公约数。共同的 `ExactRational.add` 先按分母最大公约数预约分，再按规范化结果检查原有位数上限；精确相消直接得到零。几何采用浮点权重，物种与单位换算继续遵守精确数量规则。

定义、每份最终库存、每步反应评估和区域物种总量的容量由 `ChemicalLimits` 维护。反应工作向量按注册表物种数分配，最终库存准备时检查紧凑物种列容量。

## 独立保存的数据

- `LiquidCellInventory.Stock` 直接保存材料、体积、质量、不可变物种组成、质量余量与内能。
- `RegionChemistry` 保存区域注册表引用、化学源汇审计与待处理单元；逐物种总量、电荷和组成直接从单元库存汇总。
- `FluidMixture` 保存机械材料库存，`ChemicalContents` 保存完整化学容器库存，物品按其实际类型保存。

`1 quantum = 1 μmol`，`10^6 quanta = 1 mol`，物种数量为非负整数，数量运算检查溢出。
每份单元记录的键为 `(cell, material, supportMask)`，同格各固定开放分量分别保存组成。
实际体积通量先分配整数质量，物种按接受的质量份额分配；各份额按实际物种分配参考能，按实际质量与供体温度分配载体显热。最大实际质量份额承接物种与内能余量，来源总量精确闭合。零质量几何尾项保留其精确参考体积、组成和零内能。

反应通过单元库存计划准备完整后继，在首次写入前验证来源代次、质量、物种和容量。
化学源汇账与待处理单元随 `FluidDomainState` 单独交接。审计计划绑定区域化学对象与原源汇账身份，库存计划绑定实际单元库存代次，准备与提交共同保持原子性。
checkpoint 在单元记录中保存组成，manifest 绑定物种 palette、注册表指纹与总账；冷恢复校验后绑定当前注册表。

质量关系为：

```text
massUnits = speciesMassUnits + massResidualUnits
```

摩尔质量定义使用 kg/mol；整数 μmol 乘精确摩尔质量得到可表示的质量整数，质量余量沿用同一编码。
拆并和转移按共同物质份额搬运质量余量，并保留对应审计。

## 反应模型

反应定义给出计量关系、正逆质量作用速率和 Arrhenius 温度系数。相同开放支持、相同化学物相的库存组成一个局部网络，按共同温度和组成提出反应进度。快速解离与中和作为快速极限参加同一批竞争。

设备阶段在局部子步内计算全部提案。有限速率使用净正逆速率及本反应导数作半隐式推进；所有反应共同统计各物种的净消耗需求，用共享缩放限制全部反应物，然后按完整整数计量批次执行 `n_new = n_old + S b`。产物从下一个局部子步起参与反应。多个反应物约束一起作用，低量竞争留下的整数余量保留在实际库存。规则 ID 的规范排序用于存储身份，反应列表顺序不决定优先权。

全部候选反应同时接受内能限制：物相参考能和物种参考能决定最低可用内能，超出时共同缩小反应批次。实际反应保持单元总内能，更新组成后的热模型反演共同温度，下一子步直接使用温度反馈。有限速率的亚量子进度按 `(格、材料、支持、规则)` 存入共享反应进度，后续按当时库存重新限制，随设备候选与区域存档交接。

`ChemicalReaction.Kinetics` 的正逆速率以 mol/m³ 浓度定义，速率常数的量纲随反应级数变化；活化能使用 J/mol，参考温度使用 K。快速规则省略该参数，`FINITE_RATE` 要求至少一个方向具有正速率。注册表检查元素、质量和电荷守恒，允许竞争、催化物种与反应循环。首批采用有限局部子步和单反应线性化，强耦合刚性网络的时间误差属于支持范围。

设备中间组成可临时超过单行物种槽位；接受结果按实际载体质量重新分配并构造稀疏物种表。参考能、显热与整数物种变更使用同一接受批次，库存容量不足时保留已提交状态并扩容后重新计算。

## 水溶液物种扩散

`AqueousDiffusion` 从物种扩散率、有效摩尔体积和电荷生成交换规则；`VulkanLiquidDiffusion` 在实际湿面上按 Fick 浓差提出有限交换。中性溶质独立交换，离子按电中性的组合交换；反向水量按有效体积配平。该近似保持液体参考体积，质量随真实组成交换。油等其他液相保留各自身份。

三个轴向的奇偶面批次依次读取当前库存，溶质和反向水共同接受供体限制。整数物种、实际质量、携带内能和动量沿同一接受量交接；接受的完整交换批次须为仍在原格的物种保留实际液体质量。亚量子物种及水量余数复用反应进度列，随候选和 checkpoint 保存。完全排空时，最后一份物质带走剩余内能；随后结算的动能与重力势能差沿实际排空路线交给存活接收库存。

## 气液传质与相平衡

`GasLiquidMassTransferDefinition` 关联液相、气体组分与共同物种，登记在化学注册表并参与世界能力快照和指纹。CO₂–水采用稀溶液 Henry 关系 `c_eq = H(T)p`，液膜与气膜串联后的有效系数决定净转移。默认温度范围为 273.15–373.15 K；超出范围时保留已记录的摩尔余量。

水与乙醇共用 aqueous 液相，以物种摩尔分率和各自纯组分饱和压力计算理想平衡 `p_i = x_i p_i^sat(T)`。液气相变采用局部相平衡闭合，Henry 关系采用有限速率；两者共用接触、供体和内能限制，以及实际整数转移。液体体积按挥发物的完整守恒批次调整，稀溶 CO₂ 采用固定液体体积近似。液体中仍有质量或物种时保留其实际体积，全部库存转移时最后一批携带剩余内能。液体与逐格气体的物种、质量和内能随同一接受量更新，跨格转移携带动量并结算动能和势能。

设备执行格内界面和三个轴向的奇偶面批次，按开放微盒、PLIC 平面及液相支持掩码确定真实接触。每条规则读取先前已接受的库存、组成和内能，计算温度、共同压力与转移需求。挥发平衡的半隐式响应包含压力、液相组成和潜热温度反馈；未润湿墙面使用当前气体的混合露点组成，并预留有限墙热量。Henry 传质由实际面积、有效系数和请求时长决定。亚量子余量按 `(cell, material, supportMask, definitionId)` 随 checkpoint 保存，微盒内相分布采用支持掩码近似。

## 固液溶解和析出

盐的溶解度以溶剂实际物种量和温度计算，默认 NaCl–水数据覆盖 273.15–373.15 K。扩散系数、颗粒总实体积和物理粒径决定有限传质，球形颗粒面积使用 `6V/d`；代表粒子数量变化不改变总面积。接触面积按液体可达比例近似；尚无颗粒时以实际液体可达面积启动晶核，传质膜尺度沿材料粒径估算。供体按各自可用组成、质量与实体积依次扣取，实际批次共同结算液固库存、内能、势能和携带动量。孔内扩散和复杂颗粒形状暂不解析。

### pH 与显色

`ChemicalReactionEngine.ph` 集中派生强酸强碱的显示近似：以净 H⁺/OH⁻ 浓度 c（mol/m³）计算 `strength = min(7, log10(1 + c / 0.001))`，酸侧显示 `7-strength`，碱侧显示 `7+strength`。
水相定义需包含水溶剂的默认组成，并允许 H₂O、H⁺ 与 OH⁻。`CarrierInventoryReader.copyChemicalDisplaySnapshot` 按互溶物相汇总适用水相的物种与实际体积；油等其他库存保留自己的物性与显示归属。

`ChemicalDisplaySnapshot` 保留每格至多四种展示物种及权重，pH 按材料快照的 `phaseGroups` 顺序逐相保存单元列。
pH 以 0.1 为步长编码 0–14，中性为 70，无数据为 255。缺少水相、必要物种或化学能力时显示无数据；液液界面两侧按各自物相取值。

客户端统一提供组分、pH、温度三种显色模式，默认组分。组分使用材料与既有化学配色；pH 与温度各自使用独立色带。
温度读取服务端凝聚相单元展示值，单位 K、量化步长 1/16 K，空值为 65535；气体温度按所在气格观察。
模式和温度范围由客户端显示设置持有，控制器界面可打开显色设置。几何、化学及温度随同一已接受快照交接；模式、范围或展示值变化复用网格并刷新颜色，暂停区域立即响应。

## 二氧化碳水合与碱吸收

首批水溶液包含溶解 CO₂、H₂O、OH⁻、H⁺ 和 HCO₃⁻。`co2_hydration` 与 `co2_hydroxide` 共用同一 CO₂ 库存和有限速率反应批次；气液接触负责溶解与脱除，氢氧化钠由现有试剂容器提供。采样与混合液容器保留碳酸氢根库存。稀水溶液采用零反应体积近似，参考体积随现有液相库存交接。

动力学使用 298.15 K 附近的稀水溶液工程近似，代表性温度范围为 278.15–308.15 K。水合通路的水浓度取 55555.6 mol/m³ 时，正向有效一级速率为 0.037 s⁻¹；氢氧根通路的正向系数为 7.1 m³/(mol·s)，逆向为 0.00018 s⁻¹。数据依据 [Johnson, 1982](https://doi.org/10.4319/lo.1982.27.5.0849) 的稀释极限测量；水合通路折算成完整计量反应的正向系数为 6.66×10⁻⁷ m³/(mol·s)，逆向系数为 76 m³/(mol·s)。采用 Arrhenius 温度系数，[水合](https://odr.chalmers.se/server/api/core/bitstreams/1d7ac544-f796-4fb8-b5ca-e40b9847a86a/content)与[碱吸收](https://doi.org/10.1039/C4CP00100A)正向活化能分别取 61.4 和 55 kJ/mol；逆向活化能按参考反应热确定。HCO₃⁻ 参考能为 −67.6 kJ/mol，以现有水和溶解 CO₂ 相对基准表示约 −48.2 kJ/mol 的碱吸收热；反应生成物的参考能变化与显热按共同热状态结算。

## 求解顺序

每个计算步中，机械输运与导热完成后按单元、开放支撑和化学物相分组反应。反应保持参与单元的总内能，提交后的物种与质量通过热状态函数重新推导共同温度；反应热体现为组成基准能与显热之间的转换。密度、压力和客户端颜色随后从已提交库存重新派生。

热学开启时，reaction plan 先证明 species/质量闭合，再用各载体原有总内能、候选组成与候选质量验证正温可表示性，成功后统一提交 species 与质量。
颗粒 precipitation 使用同一 liquid chemical ledger，并在 granular/thermal 联合 plan 中验证 solid composition、SVQ、容量和 U；
颗粒反应按同格总内能守恒，依据最终液固库存重新分配各载体内能与温度。
同相扩散后执行液相反应与固液反应，再执行统一气液交换；凝聚相变使用剩余相变批次。新溶解物种在后续逻辑步参与反应。

## 跨层边界

- region checkpoint 保存完整 parcel、registry identity 和审计账本。
- 容器转移先验证目标物品可完整表示 species、质量和 residual，再进入 WAL 事务。
- 创造模式放出是 external source；创造模式装取不能制造隐藏 sink。
- 客户端只接收 Top-N species 与逐相 pH 等量化展示；这些字段不能恢复或结算库存。
- `chemstatus` 是管理员只读诊断并写 command transcript。

`ChemicalContents` 使用 canonical group 表，每组绑定 mechanical material、nanovolume、kg mass/residual 和排序 species 列。
物品总量必须与各组 exact sum 一致；部分抽取按体积分配 quanta 和 residual，并把确定性 remainder 留给一侧。
容器无法表示完整结果时，在 solver 修改前拒绝。

`ChemicalTransferLedger.Group.normalizedVolume` 与 `totalNormalizedVolume()` 使用液体归一化体积，经区域 `SolverUnitScale` 转换为 m³。
区域 source/sink 的 group 聚合对归一化体积按输入浮点数的精确二进制值求和，最后舍入一次。
机械质量与化学质量余量使用统一的 100 ng 整数编码，合并、拆分和审计直接核对整数；余量保存机械质量减去组成质量的差额。

每个 source/sink transfer group 同时保存 material identity、归一化体积、kg 质量与余量、整数 μmol 物种。
collision escape、`empty`、创造放出和创造装取共用这份完整账本。

## 功能布局

区域载荷中的 chemistry presence 必须与本次启动的 chemistry 配置完全一致。功能配置变化不会向既有 checkpoint 添加或删除化学原始数据。

容器转移先规划完整的单元库存分配，全部体积、整数质量、物种和内能均可表示时统一提交。
默认组成的外部来源沿用化学量子的创建限制；已有物质拆分允许部分数值样本的物种列为空，以实际质量及组成质量余量承载对应份额。
失败保留原库存。

外部凝聚相注入按可达开放格和实际操作量交接库存；气液共同占容与压力由局部状态方程计算。


## 数量容量与最小转移

整数库存每区域上限 2×10¹⁵ μmol，即 2×10⁹ mol；每桶上限 1.024×10¹² μmol，即 1.024×10⁶ mol。
一桶水约 55555.56 mol，一桶汞为 68000 mol，均在上限内。
水的一颗化学量子对应 1.8×10⁻⁸ kg，按参考密度折合 1.8×10⁻¹¹ m³；实际操作还受 nanos、整数质量与完整反应批次限制。
所有加法、乘法及 mol→整数 μmol 换算检查溢出；`ChemicalAmount.quanta` 要求完整 μmol。

油的纯质量记录贯穿库存、拆并、输运、容器与审计，空物种列随物质保留其质量余量。
具有真实物种的载体继续遵守现有最小可表示库存要求。
数据包及注册表、区域与容器保存 `QuantaPerMol` / `quantaPerMol`；指纹包含 SI 定义和每 mol 的整数尺度。


