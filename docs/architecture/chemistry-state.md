# 守恒化学状态契约

修改化学组分、反应、传质或 pH 时读本文。单位与内部编码统一见[设计书](system-overview.md#units)。

## 修改入口

| 职责 | 代码入口 | 测试选择器 |
|---|---|---|
| 组分状态与反应 | [RegionChemistry](../../model/src/main/java/mmm/chemistry/RegionChemistry.java)、[VulkanLiquidReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanLiquidReactions.java)、[VulkanRegionReactions](../../gpu-kernel/src/main/java/mmm/gpu/VulkanRegionReactions.java) | `chemistry`；[M7ChemistryValidation](../../fabric/src/test/java/mmm/chemistry/M7ChemistryValidation.java) 检查定义 |
| 液体单元组成 | [LiquidCellInventory](../../model/src/main/java/mmm/inventory/LiquidCellInventory.java) | `chemistry,cell-inventory` |
| 定义编码 | [ChemicalRegistryCodec](../../fabric/src/main/java/mmm/chemistry/ChemicalRegistryCodec.java) | `region-state` |

数据包定义位于 `fabric/src/main/resources/data/mmm/mmm/` 下的 `species`、`chemical_phases`、`reactions`、`solid_liquid_reactions` 与 `gas_liquid_mass_transfers`。执行方式见[验证指南](../maintenance/validation.md#host)。

## 定义与注册表

化学能力依赖材料与热学。首次启用时数据包定义被规范化为世界能力包中的 registry snapshot，此后其 stable ID、精确有理数定义与指纹是世界权威；数据包变化只报告漂移。

- **species**：stable ID、组成基元整数向量、电荷、精确摩尔质量（kg/mol）。
- **phase**：绑定机械材料，声明允许物种与 `default_concentration_mol_per_m3`。油的允许物种为空，机械质量全部进入质量余量。
- **reaction**：反应物/生成物整数计量、允许物相与动力学参数。

精确有理数接受整数、有限小数或 `numerator/denominator`。注册表校验 ID、质量、组成与电荷守恒，按 stable ID 排序后对规范字节计算 SHA-256 指纹。各类容量上限由 `ChemicalLimits` 维护。

## 库存

`1 quantum = 1 μmol`，物种数量为非负整数，运算检查溢出。

- `LiquidCellInventory.Stock` 按 `(cell, material, supportMask)` 保存材料、体积、质量、物种组成、质量余量与内能，同格各开放分量分别保存组成。
- `RegionChemistry` 保存注册表引用、化学源汇审计与待处理单元；逐物种总量从单元库存汇总。
- `ChemicalContents` 是容器中的完整化学库存，按物相分组保存纳体积、质量、余量与排序物种列。

```text
massUnits = speciesMassUnits + massResidualUnits
```

体积通量先分配整数质量，物种按接受的质量份额分配，参考能随物种、显热随质量与供体温度分配；最大份额承接物种与内能余数，来源总量精确闭合。反应、拆分与容器转移都先准备完整后继，在首次写入前验证代次、质量、物种与容量。

## 液相反应

相同开放支撑、相同化学物相的库存构成局部反应网络，按共同温度与组成提出反应进度：

- 有限速率反应使用质量作用正逆速率与 Arrhenius 温度系数（浓度 mol/m³，活化能 J/mol，参考温度 K），按净速率及自身导数半隐式推进。
- 快速解离与中和作为快速极限参加同一批竞争。
- 所有反应共同统计物种净消耗，用共享缩放限制反应物，再按整数计量批次执行 `n_new = n_old + S b`。反应列表顺序不决定优先权，产物从下一个局部子步起参与反应。
- 反应批次同时受内能约束：物相与物种参考能决定最低可用内能，超出时共同缩小批次。反应保持单元总内能，温度由更新后的组成反演，反应热表现为参考能与显热之间的转换。
- 有限速率的亚量子进度按 `(格、材料、支撑、规则)` 存入反应进度列，随设备候选与存档交接。

局部子步加单反应线性化对强耦合刚性网络存在时间误差，属于支持范围内的近似。

### 二氧化碳水合与碱吸收

首批水溶液物种包含溶解 CO₂、H₂O、OH⁻、H⁺ 与 HCO₃⁻。`co2_hydration` 与 `co2_hydroxide` 共用同一 CO₂ 库存与有限速率反应批次，气液接触负责溶解与脱除，氢氧化钠由试剂容器提供。动力学是 298.15 K 附近的稀水溶液工程近似，代表温区 278.15–308.15 K，稀溶液按零反应体积近似；速率常数、活化能与参考能见 `reactions/` 数据。

## 水溶液物种扩散

`AqueousDiffusion` 从扩散率、有效摩尔体积与电荷生成交换规则，`VulkanLiquidDiffusion` 在实际湿面上按 Fick 浓差提出有限交换：中性溶质独立交换，离子按电中性组合交换，反向水量按有效体积配平以保持液体参考体积。整数物种、质量、内能与动量沿同一接受量交接，亚量子余数复用反应进度列。

## 气液传质与相平衡

`GasLiquidMassTransferDefinition` 关联液相、气体组分与共同物种，登记在化学注册表并纳入指纹。

- **Henry 传质**：CO₂–水采用稀溶液 `c_eq = H(T)p`，液膜与气膜串联得到有效系数，按实际接触面积与请求时长有限速率推进；默认温区 273.15–373.15 K。
- **挥发平衡**：水与乙醇共用 aqueous 液相，按理想混合 `p_i = x_i p_i^sat(T)` 做局部相平衡闭合，半隐式响应包含压力、液相组成与潜热温度反馈。

两类规则共用接触、供体与内能限制以及整数转移。真实接触由开放微盒、PLIC 平面与液相支撑掩码确定，设备先处理格内界面，再按三轴奇偶面批次处理跨格交换；未润湿墙面按当前气体的混合露点组成冷凝并预留墙热量。挥发物按完整守恒批次调整液体体积，稀溶 CO₂ 采用固定液体体积近似。液体与气体的物种、质量和内能随同一接受量更新，跨格转移携带动量并结算动能与势能。亚量子余量按 `(cell, material, supportMask, definitionId)` 随 checkpoint 保存。

## 固液溶解和析出

盐的溶解度按溶剂实际物种量与温度计算，默认 NaCl–水数据覆盖 273.15–373.15 K。有限传质由扩散系数、颗粒总实体积与物理粒径决定，球形颗粒面积取 `6V/d`，与代表粒子数量无关；接触面积按液体可达比例近似，尚无颗粒时以液体可达面积启动晶核。实际批次共同结算液固库存、内能、势能与携带动量。颗粒侧规则见[颗粒状态](granular-state.md#固液相转移)。

## 求解顺序

机械输运与导热之后依次执行同相扩散、液相反应与固液反应、统一气液交换，最后是凝聚相变的剩余批次。新溶解物种在后续逻辑步参与反应。热学开启时，反应计划先证明物种与质量闭合，再验证候选组成下的正温可表示性，然后统一提交。密度、压力与客户端颜色随后从已提交库存派生。

### pH 与显色

`ChemicalReactionEngine.ph` 派生强酸强碱的显示近似：以净 H⁺/OH⁻ 浓度 c（mol/m³）计算 `strength = min(7, log10(1 + c / 0.001))`，酸侧显示 `7 − strength`，碱侧显示 `7 + strength`。水相定义需允许 H₂O、H⁺ 与 OH⁻；缺少水相、必要物种或化学能力时显示无数据，液液界面两侧按各自物相取值。

`ChemicalDisplaySnapshot` 每格保留至多四种展示物种及权重，pH 按物相逐列保存，以 0.1 为步长编码 0–14（无数据为 255）。客户端提供组分、pH、温度三种显色模式，默认组分；温度读取凝聚相单元展示值（K，步长 1/16 K）。模式与温度范围由客户端显示设置持有，切换时复用网格只刷新颜色。

## 跨层约定

- 区域 checkpoint 在单元记录中保存组成，manifest 绑定物种 palette、注册表指纹与总账；载荷中的化学部分与世界化学配置完全一致。
- 容器转移先验证目标物品能完整表示物种、质量与余量，无法表示时在修改 solver 前拒绝。部分抽取按体积分配物种与余量。
- 源汇账的每个转移组保存材料、归一化体积、kg 质量与余量、整数 μmol 物种；碰撞逸出、清空、创造放出与装取共用这份账本。创造放出记外部源，创造装取从区域删除实际库存。
- 客户端只接收量化展示数据，不参与库存结算。`chemstatus` 是管理员只读诊断。

## 数量容量

整数库存每区域上限 2×10¹⁵ μmol，每桶上限 1.024×10¹² μmol，一桶水（约 55556 mol）或一桶汞均在上限内。数据包、注册表、区域与容器都保存 `QuantaPerMol`，指纹包含 SI 定义与每 mol 的整数尺度。
