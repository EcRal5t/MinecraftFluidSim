# 守恒化学状态契约

修改化学组分、反应、指示剂或 pH 时读本文。

## 修改入口

- **组分状态和反应**
  - 代码入口：
    - [ChemicalParcelStore](../../model/src/main/java/mmm/chemistry/ChemicalParcelStore.java)
    - [ChemicalReactionStage](../../solver/src/main/java/mmm/fluid/ChemicalReactionStage.java)
  - 测试选择器：`chemistry`

- **液体单元组成与提交**
  - 代码入口：
    - [LiquidCellInventory](../../model/src/main/java/mmm/inventory/LiquidCellInventory.java)
  - 测试选择器：`chemistry,cell-inventory`

- **指示剂操作**
  - 代码入口：
    - [ChemicalIndicatorItem](../../fabric/src/main/java/mmm/item/ChemicalIndicatorItem.java)
    - [MinecraftIndicatorEditing](../../fabric/src/main/java/mmm/simulation/MinecraftIndicatorEditing.java)
  - 测试选择器：`chemistry`

- **化学定义编码**
  - 代码入口：[ChemicalRegistryCodec](../../fabric/src/main/java/mmm/chemistry/ChemicalRegistryCodec.java)
  - 测试选择器：`region-state`

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [Registry](#registry)
- [独立保存的数据](#独立保存的数据)
- [反应模型](#反应模型)
- [求解顺序](#求解顺序)
- [跨层边界](#跨层边界)
- [功能布局](#功能布局)

单位与内部编码统一见[设计书](system-overview.md#units)。

## Registry

化学功能默认关闭并依赖材料功能。首次启用时，数据包定义被规范化为 world-local registry snapshot；此后该 snapshot 的 stable ID、exact rational 定义、预算和 fingerprint 是世界权威。

当前数据包与 snapshot 不一致只报告 definition mismatch，不重解释已有库存。snapshot 未知、损坏或身份不符时区域和容器状态保留并 LOCKED。

### 定义层次与规范化

数据包分别提供 species、chemical phase 和 reaction；species JSON 的 `molar_mass_kg_per_mol` 值以 kg/mol 解释：

- species：stable ID、组成基元整数向量、电荷、精确摩尔质量 kg/mol；
- phase：绑定机械材料，声明允许物种、`default_concentration_mol_per_m3` 与 indicator 接受性；油的允许物种和默认浓度均为空，机械质量全部进入 kg 余量；
- reaction：反应物/生成物整数计量和允许 chemical phase。

exact rational 只接受整数、有限小数或 `numerator/denominator` 文本。解析前先限制 ID 长度、文本长度、小数 scale 和整数位宽，避免用短文本触发无界 `BigInteger` 工作。
registry 校验未知 ID、重复 ID、非正质量、组成/电荷/质量不守恒、无界解离和预算，然后按 stable ID 排序并对 canonical UTF-8 bytes 计算 SHA-256。

`ExactRational` 在原输入位数上限内规范化分子与分母；二进制幂分母按分子尾零位精确约分，一般分母沿用整数最大公约数。共同的 `ExactRational.add` 先按分母最大公约数预约分，再按规范化结果检查原有位数上限；精确相消直接得到零。几何采用浮点权重，物种与单位换算继续遵守精确数量规则。

当前数量上限包括 256 species、64 phases、512 reactions、每 parcel 16 species、每步 131072 次 reaction evaluation 和每区域 `2e15` quanta。这些上限都在分配内存和修改状态前检查。

## 独立保存的数据

- `LiquidCellInventory.Stock` 直接保存材料、体积、质量、不可变物种组成、质量余量、指示剂与内能。
- 区域保存逐物种总量、电荷、组成及外部源汇审计。
- `FluidMixture` 保存机械材料库存，`ChemicalContents` 保存完整化学容器库存，物品按其实际类型保存。

`1 quantum = 1 μmol`，`10^6 quanta = 1 mol`，物种数量为非负整数，数量运算检查溢出。
每份单元记录的键为 `(cell, material, supportMask)`，同格各固定开放分量分别保存组成。
物种、整数质量、质量余量和内能随同一共享通量分配；供体承接可表示性余数，来源总量精确闭合。

反应和指示剂操作通过单元库存计划准备完整后继，在首次写入前验证来源代次、质量、物种和容量。
化学源汇账、待处理单元和轮转位置随 `FluidDomainState` 单独交接。
checkpoint 在单元记录中保存组成，manifest 绑定物种 palette、注册表指纹与总账；冷恢复校验后绑定当前注册表。

质量关系为：

```text
massUnits = speciesMassUnits + massResidualUnits
```

摩尔质量定义使用 kg/mol；整数 μmol 乘精确摩尔质量得到可表示的质量整数，质量余量沿用同一编码。
拆并和转移按共同物质份额搬运质量余量，并保留对应审计。

## 反应模型

当前采用强电解质完整解离与同格、同化学物相的瞬时计量反应。
弱酸、缓冲液、活度和有限速率平衡超出这一模型的支持范围。

平流后按 `(cell, supportMask, chemicalPhase)` 汇总整数物种，依规范反应顺序求完整化学计量 extent。
候选联合核对物种、组成、电荷、质量和残差，保持实际材料身份及载体体积。
反应调度保存待处理单元与轮转位置，预算耗尽后继续保留任务；冷恢复从实际库存重建调度。

反应后的整数物种按实际单元记录质量份额分配，保留各记录的质量、体积和 MAC 动量。
组成质量余量按实际质量减去新物种质量重新计算，物种、实际质量和余量分别精确闭合。
`ChemicalReactionStage` 以同格、同开放分量、同化学物相为一组，准备一次库存计划与 LTE 内能分配。
温度由候选质量、组成与原总内能推导，完整预检后联合提交。

容量、物种槽位或有效热状态暂时受限时，保留原库存和待处理单元。
物质输运的空间误差随共同单元输运评价；固液转移见[颗粒参考](granular-state.md#固液相转移)。

### Indicator 与 pH

indicator 添加只标记当时可接受 indicator 的互溶连通单元库存，受 100 m³ 硬上限；超过上限整次拒绝。标记随物质平流和容器传输，后续混入的未标记库存 不自动继承。

pH 沿用既定强酸强碱的显示近似：以净 H⁺/OH⁻ 浓度 c（mol/m³）计算 `strength = min(7, log10(1 + c / 0.001))`，酸侧显示 `7-strength`，碱侧显示 `7+strength`。
这个展示近似保留原显色响应。

pH 是根据局部 `H+`/`OH-` 相对数量派生的 0–14 展示指标，供指示剂显色与界面显示。客户端接收每格至多四个展示 species、量化 pH 和可见 indicator fraction，专用于渲染展示。

## 求解顺序

每个 logical step 中，机械 advection 与导热完成后按当前 cell 和 chemical phase 分组反应。反应保持每份液体载体的总内能，提交后的 species 与质量通过热状态函数重新推导温度；反应热体现为组成基准能与显热之间的转换。密度、压力和客户端颜色随后从已提交库存重新派生。

热学开启时，reaction plan 先证明 species/质量闭合，再用各载体原有总内能、候选组成与候选质量验证正温可表示性，成功后统一提交 species 与质量。
颗粒 precipitation 使用同一 liquid chemical ledger，并在 granular/thermal 联合 plan 中验证 solid composition、SVQ、容量和 U；
颗粒反应按同格总内能守恒，依据最终液固库存重新分配各载体内能与温度。

## 跨层边界

- region checkpoint 保存完整 parcel、registry identity 和审计账本。
- 容器转移先验证目标物品可完整表示 species、indicator、质量和 residual，再进入 WAL 事务。
- 创造模式放出是 external source；创造模式装取不能制造隐藏 sink。
- 客户端只接收 Top-N species、pH 和 indicator 等量化展示；这些字段不能恢复或结算库存。
- `chemstatus` 是管理员只读诊断并写 command transcript。

`ChemicalContents` 使用 canonical group 表，每组绑定 mechanical material、indicator、nanovolume、kg mass/residual 和排序 species 列。
物品总量必须与各组 exact sum 一致；部分抽取按体积分配 quanta 和 residual，并把确定性 remainder 留给一侧。
容器无法表示完整结果时，在 solver 修改前拒绝。

`ChemicalTransferLedger.Group.normalizedVolume` 与 `totalNormalizedVolume()` 使用液体归一化体积，经区域 `SolverUnitScale` 转换为 m³。
区域 source/sink 的 group 聚合对归一化体积按输入浮点数的精确二进制值求和，最后舍入一次。
机械质量与化学质量余量使用统一的 100 ng 整数编码，合并、拆分和审计直接核对整数；余量保存机械质量减去组成质量的差额。

每个 source/sink transfer group 同时保存 material/indicator identity、归一化体积、kg 质量与余量、整数 μmol 物种。
collision escape、`empty`、创造放出和创造装取共用这份完整账本。

## 功能布局

区域载荷中的 chemistry presence 必须与本次启动的 chemistry 配置完全一致。功能配置变化不会向既有 checkpoint 添加或删除化学原始数据。

容器转移先规划完整的单元库存分配，全部体积、整数质量、物种和内能均可表示时统一提交。
默认组成的外部来源沿用化学量子的创建限制；已有物质拆分允许部分数值样本的物种列为空，以实际质量及组成质量余量承载对应份额。
失败保留原库存。

所有外部凝聚相注入同时检查局部开放空间和区域总容量。总容量从几何体积减去整数固体 SVQ 与实际液体库存计算；已有密闭气体的区域必须留下正气腔体积。同次计划中的材料共同抵扣容量，气腔空间按真实拓扑保留。


## 数量容量与最小转移

整数库存每区域上限 2×10¹⁵ μmol，即 2×10⁹ mol；每桶上限 1.024×10¹² μmol，即 1.024×10⁶ mol。
一桶水约 55555.56 mol，一桶汞为 68000 mol，均在上限内。
水的一颗化学量子对应 1.8×10⁻⁸ kg，按参考密度折合 1.8×10⁻¹¹ m³；实际操作还受 nanos、整数质量与完整反应批次限制。
所有加法、乘法及 mol→整数 μmol 换算检查溢出；`ChemicalAmount.quanta` 要求完整 μmol。

油的纯质量记录贯穿组成池、拆并、输运、容器与审计，空物种列随物质保留其质量余量。
具有真实物种的载体继续遵守现有最小可表示库存要求。
数据包及注册表、区域与容器保存 `QuantaPerMol` / `quantaPerMol`；指纹包含 SI 定义和每 mol 的整数尺度。
