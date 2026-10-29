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
- phase：绑定机械材料，声明允许物种、`default_concentration_mol_per_m3`；油的允许物种和默认浓度均为空，机械质量全部进入 kg 余量；
- reaction：反应物/生成物整数计量和允许 chemical phase。

exact rational 只接受整数、有限小数或 `numerator/denominator` 文本。解析前先限制 ID 长度、文本长度、小数 scale 和整数位宽，避免用短文本触发无界 `BigInteger` 工作。
registry 校验未知 ID、重复 ID、非正质量、组成/电荷/质量不守恒、无界解离和预算，然后按 stable ID 排序并对 canonical UTF-8 bytes 计算 SHA-256。

`ExactRational` 在原输入位数上限内规范化分子与分母；二进制幂分母按分子尾零位精确约分，一般分母沿用整数最大公约数。共同的 `ExactRational.add` 先按分母最大公约数预约分，再按规范化结果检查原有位数上限；精确相消直接得到零。几何采用浮点权重，物种与单位换算继续遵守精确数量规则。

定义、每份最终库存、每步反应评估和区域物种总量的容量由 `ChemicalLimits` 维护。反应工作向量按注册表物种数分配，最终库存准备时检查紧凑物种列容量。

## 独立保存的数据

- `LiquidCellInventory.Stock` 直接保存材料、体积、质量、不可变物种组成、质量余量与内能。
- `RegionChemistry` 保存区域注册表引用、化学源汇审计、待处理单元与反应轮转位置；逐物种总量、电荷和组成直接从单元库存汇总。
- `FluidMixture` 保存机械材料库存，`ChemicalContents` 保存完整化学容器库存，物品按其实际类型保存。

`1 quantum = 1 μmol`，`10^6 quanta = 1 mol`，物种数量为非负整数，数量运算检查溢出。
每份单元记录的键为 `(cell, material, supportMask)`，同格各固定开放分量分别保存组成。
实际体积通量先分配整数质量，物种按接受的质量份额分配，参考能读取同一物种列；最大实际质量份额承接物种与内能余量，来源总量精确闭合。原生零质量几何尾项保留组成和零内能。

反应通过单元库存计划准备完整后继，在首次写入前验证来源代次、质量、物种和容量。
化学源汇账、待处理单元和轮转位置随 `FluidDomainState` 单独交接。审计计划绑定区域化学对象与原源汇账身份，库存计划绑定实际单元库存代次，准备与提交共同保持原子性。
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

设备反应阶段依据物种库存、反应计量和热可行性确定实际反应量。瞬时中间态可暂时包含超过单份库存槽位数的物种，热学计算直接按工作向量分配的物种数量和实际载体质量计算基准能。

整条反应链完成后，再将最终向量生成各份 `ChemicalMixtureRecord` 并检查库存容量；物种条目超限保留原库存及待处理任务。热量不足时按整格原内能限制实际反应程度，可行的部分反应和待处理状态共同发布，补热后继续推进。

反应后的整数物种按实际单元记录质量份额分配，保留各记录的质量、体积和 MAC 动量。
组成质量余量按实际质量减去新物种质量重新计算，物种、实际质量和余量分别精确闭合。
设备反应按同格、同开放分量、同化学物相组织库存，准备一次库存计划与 LTE 内能分配。
温度由候选质量、组成与原总内能推导，完整预检后联合提交。

最终库存容量受限时保留原库存和待处理单元；反应评估预算按单元轮转，后续步骤继续尚未处理的任务。
物质输运的空间误差随共同单元输运评价；固液转移见[颗粒参考](granular-state.md#固液相转移)。

## 气液有限速率传质

`GasLiquidMassTransferDefinition` 由化学注册表保存，关联液相、气体组分与共同物种；定义随世界能力快照存盘并参加指纹。数据包目录为 `mmm/gas_liquid_mass_transfers`。世界装配核对气液物种对应关系和两侧热学状态函数。

稀溶液平衡使用 `c_eq = H(T) p`，其中 c 的单位为 mol/m³、p 为 Pa，`H(T) = H_ref exp[B(1/T − 1/T_ref)]`。液膜与气膜串联得到 `1/K_L = 1/k_L + HRT/k_G`；净传质率为 `A K_L (H p − c)`。有限气腔的气体分压与液体浓度随同一转移量变化，以隐式标量式推进，再结算整数物种批次。

首个定义是 CO₂ 与水相的稀溶液交换，采用零偏摩尔体积近似：水相几何体积保持，溶解物质量改变密度和惯性。两侧参考能差通过内能守恒产生溶解温度变化；膜速度表示当前玩法尺度的有效传质速度。该近似适用于稀溶液，不包含高浓度溶液体积变化与气泡成核动力学。

`GasLiquidContactGeometry.capture` 从当前物理 PLIC 和固定开放微盒计算 m² 接触面积，按液体库存身份和实际气腔聚合。格内截面与跨格湿干面共用实际开放几何；同一支撑中的材料按密度分层分配界面，覆盖在水上的油隔开水与气体。薄层采用 PLIC 可解析的平面接触面积，未解析粗糙度进入有效膜速度。
### pH 与显色

`ChemicalReactionEngine.ph` 集中派生强酸强碱的显示近似：以净 H⁺/OH⁻ 浓度 c（mol/m³）计算 `strength = min(7, log10(1 + c / 0.001))`，酸侧显示 `7-strength`，碱侧显示 `7+strength`。
水相定义需包含水溶剂的默认组成，并允许 H₂O、H⁺ 与 OH⁻。`CarrierInventoryReader.copyChemicalDisplaySnapshot` 按互溶物相汇总适用水相的物种与实际体积；油等其他库存保留自己的物性与显示归属。

`ChemicalDisplaySnapshot` 保留每格至多四种展示物种及权重，pH 按材料快照的 `phaseGroups` 顺序逐相保存单元列。
pH 以 0.1 为步长编码 0–14，中性为 70，无数据为 255。缺少水相、必要物种或化学能力时显示无数据；液液界面两侧按各自物相取值。

客户端统一提供组分、pH、温度三种显色模式，默认组分。组分使用材料与既有化学配色；pH 与温度各自使用独立色带。
温度读取服务端凝聚相单元展示值，单位 K、量化步长 1/16 K，空值为 65535；气腔温度另由气腔展示字段保存。
模式和温度范围由客户端显示设置持有，控制器界面可打开显色设置。几何、化学及温度随同一已接受快照交接；模式、范围或展示值变化复用网格并刷新颜色，暂停区域立即响应。

气液交换的待处理状态按 `(cell, material, supportMask, definitionId)` 保存未处理时长与带符号摩尔余量，随内存交接和 checkpoint 恢复，当前接触气腔由新几何重新解析。实际气体与液体物种按完整整数批次转移，亚 μmol 进度留待后续步骤。外界传质把真实物种、质量与内能记入源汇；零偏摩尔体积传质的化学账本允许零体积。

Henry 液膜关系仅在定义声明的温度区间内执行，参考温度须位于该区间。默认 CO₂–水采用 273.15–373.15 K 的常压液水拟合范围。范围外停止该接口的传质，其它反应、相变与热过程继续执行；此前的亚 μmol 余量保留，范围外经历的时间不积压到回暖后的传质步骤。

## 求解顺序

每个计算步中，机械输运与导热完成后按单元、开放支撑和化学物相分组反应。反应保持参与单元的总内能，提交后的物种与质量通过热状态函数重新推导共同温度；反应热体现为组成基准能与显热之间的转换。密度、压力和客户端颜色随后从已提交库存重新派生。

热学开启时，reaction plan 先证明 species/质量闭合，再用各载体原有总内能、候选组成与候选质量验证正温可表示性，成功后统一提交 species 与质量。
颗粒 precipitation 使用同一 liquid chemical ledger，并在 granular/thermal 联合 plan 中验证 solid composition、SVQ、容量和 U；
颗粒反应按同格总内能守恒，依据最终液固库存重新分配各载体内能与温度。
气液有限速率传质在普通反应、固液及气液相变之后执行，新溶解物种在下一逻辑步参与反应。

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

所有外部凝聚相注入同时检查局部开放空间和区域总容量。总容量从几何体积减去整数固体 SVQ 与实际液体库存计算；已有密闭气体的区域必须留下正气腔体积。同次计划中的材料共同抵扣容量，气腔空间按真实拓扑保留。


## 数量容量与最小转移

整数库存每区域上限 2×10¹⁵ μmol，即 2×10⁹ mol；每桶上限 1.024×10¹² μmol，即 1.024×10⁶ mol。
一桶水约 55555.56 mol，一桶汞为 68000 mol，均在上限内。
水的一颗化学量子对应 1.8×10⁻⁸ kg，按参考密度折合 1.8×10⁻¹¹ m³；实际操作还受 nanos、整数质量与完整反应批次限制。
所有加法、乘法及 mol→整数 μmol 换算检查溢出；`ChemicalAmount.quanta` 要求完整 μmol。

油的纯质量记录贯穿库存、拆并、输运、容器与审计，空物种列随物质保留其质量余量。
具有真实物种的载体继续遵守现有最小可表示库存要求。
数据包及注册表、区域与容器保存 `QuantaPerMol` / `quantaPerMol`；指纹包含 SI 定义和每 mol 的整数尺度。


