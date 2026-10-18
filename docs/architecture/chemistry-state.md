# 守恒化学状态契约

修改化学组分、反应、指示剂或 pH 时读本文。

## 修改入口

- **组分状态和反应**
  - 代码入口：
    - [ChemicalParcelStore](../../model/src/main/java/mmm/chemistry/ChemicalParcelStore.java)
    - [ChemicalReactionAllocation](../../model/src/main/java/mmm/chemistry/ChemicalReactionAllocation.java)
    - [ChemicalReactionStage](../../solver/src/main/java/mmm/fluid/ChemicalReactionStage.java)
  - 测试选择器：`chemistry`

- **粒子上的化学数据**
  - 代码入口：
    - [FlipChemicalParticleState](../../model/src/main/java/mmm/fluid/FlipChemicalParticleState.java)
    - [CarrierMutationCoordinator](../../solver/src/main/java/mmm/fluid/CarrierMutationCoordinator.java)
  - 测试选择器：`chemistry,reseeding`

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

`ExactRational` 在原输入位数上限内规范化分子与分母；二进制幂分母按分子尾零位精确约分，一般分母沿用整数最大公约数。共同的 `ExactRational.add` 先按分母最大公约数预约分，再按规范化结果检查原有位数上限；精确相消直接得到零。气体几何权重求和复用该规则，最终结果的表示预算与定义输入限制各自保持原值。

当前数量上限包括 256 species、64 phases、512 reactions、每 parcel 16 species、每步 131072 次 reaction evaluation 和每区域 `2e15` quanta。这些上限都在分配内存和修改状态前检查。

## 独立保存的数据

- 液体载体保存机械材料、体积和质量，化学侧列保存整数物种、组成、质量余量和指示剂来源。
- 区域保存逐物种总量、电荷、组成及外部源汇审计。
- `FluidMixture` 保存机械材料库存，`ChemicalContents` 保存完整化学容器库存，物品按其实际类型保存。

`1 quantum = 1 μmol`，`10^6 quanta = 1 mol`，物种数量为非负整数，数量运算检查溢出。
每份载体通过独立 handle 引用不可变组成记录；载体索引、材料物相、组成和内能保持同代一致。

`planMarkerRemap` 在写入前验证完整后继记录、来源代次、容量及物种总量，再统一替换组成。
拆并、删除和新增共同更新相关侧列，反应调度与源汇账保持连续。
快照采用稳定物种身份与规范记录，恢复先在私有状态中校验完整库存及审计，再绑定活动域。
组成池的共享记录、缓存和容量增长策略由实现维护。

质量关系为：

```text
massUnits = speciesMassUnits + massResidualUnits
```

摩尔质量定义使用 kg/mol；整数 μmol 乘精确摩尔质量得到可表示的质量整数，质量余量沿用同一编码。
拆并和转移按共同物质份额搬运质量余量，并保留对应审计。

## 反应模型

当前采用强电解质完整解离与同格、同化学物相的瞬时计量反应。
弱酸、缓冲液、活度和有限速率平衡超出这一模型的支持范围。

平流后按 `(cell, chemicalPhase)` 汇总整数物种，依规范反应顺序求完整化学计量 extent。
候选联合核对物种、组成、电荷、质量和残差，保持实际材料身份及载体体积。
反应调度保存待处理单元与轮转位置，预算耗尽后继续保留任务；冷恢复从实际库存重建调度。

反应后的物种按质量份额分配到可表示载体，来源总量保持精确。
实际搬移质量携带原速度，接收者按输入动量混合，组成、质量与速度在同一计划提交。
候选质量由原始质量整数加上物种质量整数变化得到，整组总质量精确闭合。
温度从候选质量、组成和原内能反演，完整计划通过后才写入。

容量、物种槽位或有效热状态暂时受限时，保留原库存与待处理单元。
同格异位置的质量重分配保存线动量，内部角动量和重心变化受空间表示限制。
固液转移的对应近似见[固液相转移](granular-state.md#固液相转移)，混合耗散按热机械收支范围评价。
### Indicator 与 pH

indicator 添加只标记当时可接受 indicator 的互溶连通 parcel，受 100 m³ 硬上限；超过上限整次拒绝。标记随 advection、reseed 和容器传输，后续混入的未标记 parcel 不自动继承。

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
区域 source/sink 的 group 聚合对归一化体积、mechanical kg mass 与 chemical kg residual 三列分别按输入 `double` 的精确二进制值求和，最后各自只舍入一次；
不能用顺序相关的逐 marker 浮点累加制造 species/mass 审计差额，也不能由 species 质量反算 mechanical mass 或 residual。

每个 source/sink transfer group 同时保存 material/indicator identity、归一化体积、kg 质量与余量、整数 μmol 物种。
collision escape、`empty`、创造放出和创造装取共用这份完整账本。

## 功能布局

区域载荷中的 chemistry presence 必须与本次启动的 chemistry 配置完全一致。功能配置变化不会向既有 checkpoint 添加或删除化学原始数据。

容器转移先规划完整的 marker 分配。低于一个默认化学量子的注入体积返回容量受限结果；拆分的尾部与前一份均分，避免生成无法携带物种的微小 marker。抽取若不能把某份 marker 分成两份非空化学载体，则继续查找其他 marker。只有全部体积、质量、物种和内能都可表示时才提交，失败保留原库存。

所有外部凝聚相注入同时检查局部开放空间和区域总容量。总容量从几何体积减去整数固体 SVQ 与实际液体库存计算；已有密闭气体的区域必须留下正气腔体积。液体 marker 在局部短暂聚集时，其他单元的空位仍受该全域约束。


## 数量容量与最小转移

整数库存每区域上限 2×10¹⁵ μmol，即 2×10⁹ mol；每桶上限 1.024×10¹² μmol，即 1.024×10⁶ mol。
一桶水约 55555.56 mol，一桶汞为 68000 mol，均在上限内。
水的一颗化学量子对应 1.8×10⁻⁸ kg，按参考密度折合 1.8×10⁻¹¹ m³；实际操作还受 nanos、液体浮点载体与完整反应批次限制。
所有加法、乘法及 mol→整数 μmol 换算检查溢出；`ChemicalAmount.quanta` 要求完整 μmol。

油的纯质量记录贯穿组成池、拆并、输运、容器与审计，空物种列随物质保留其质量余量。
具有真实物种的载体继续遵守现有最小可表示库存要求。
数据包及注册表、区域与容器保存 `QuantaPerMol` / `quantaPerMol`；指纹包含 SI 定义和每 mol 的整数尺度。
