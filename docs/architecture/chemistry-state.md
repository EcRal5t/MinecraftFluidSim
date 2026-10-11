# 守恒化学状态契约

修改化学组分、反应、指示剂或 pH 时读本文。

## 修改入口

| 要改什么 | 代码入口 | 测试选择器 |
|---|---|---|
| 组分状态和反应 | [ChemicalParcelStore](../../model/src/main/java/cc/ecisr/mmm/chemistry/ChemicalParcelStore.java)、[ChemicalReactionAllocation](../../model/src/main/java/cc/ecisr/mmm/chemistry/ChemicalReactionAllocation.java)、[ChemicalReactionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/ChemicalReactionStage.java) | `chemistry` |
| 粒子上的化学数据 | [FlipChemicalParticleState](../../model/src/main/java/cc/ecisr/mmm/fluid/FlipChemicalParticleState.java)、[CarrierMutationCoordinator](../../solver/src/main/java/cc/ecisr/mmm/fluid/CarrierMutationCoordinator.java) | `chemistry,reseeding` |
| 指示剂操作 | [ChemicalIndicatorItem](../../fabric/src/main/java/cc/ecisr/mmm/item/ChemicalIndicatorItem.java)、[MinecraftIndicatorEditing](../../fabric/src/main/java/cc/ecisr/mmm/simulation/MinecraftIndicatorEditing.java) | `chemistry` |
| 化学定义编码 | [ChemicalRegistryCodec](../../fabric/src/main/java/cc/ecisr/mmm/chemistry/ChemicalRegistryCodec.java) | `region-state` |

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [Registry](#registry)
- [独立保存的数据](#独立保存的数据)
- [反应模型](#反应模型)
- [求解顺序](#求解顺序)
- [跨层边界](#跨层边界)
- [功能布局](#功能布局)

## Registry

化学功能默认关闭并依赖材料功能。首次启用时，数据包定义被规范化为 world-local registry snapshot；此后该 snapshot 的 stable ID、exact rational 定义、预算和 fingerprint 是世界权威。

当前数据包与 snapshot 不一致只报告 definition mismatch，不重解释已有库存。snapshot 未知、损坏或身份不符时区域和容器状态保留并 LOCKED。

### 定义层次与规范化

数据包分别提供 species、chemical phase 和 reaction；species JSON 的 `mass_per_au` 值以 kg/AU 解释：

- species：stable ID、组成基元整数向量、电荷、每 AU exact kg；
- phase：绑定一个机械 material，声明允许 species、默认组成和 indicator 接受性；
- reaction：反应物/生成物整数计量和允许 chemical phase。

exact rational 只接受整数、有限小数或 `numerator/denominator` 文本。解析前先限制 ID 长度、文本长度、小数 scale 和整数位宽，避免用短文本触发无界 `BigInteger` 工作。registry 校验未知 ID、重复 ID、非正质量、组成/电荷/质量不守恒、无界解离和预算，然后按 stable ID 排序并对 canonical UTF-8 bytes 计算 SHA-256。

当前数量上限包括 256 species、64 phases、512 reactions、每 parcel 16 species、每步 131072 次 reaction evaluation 和每区域 `2e15` quanta。这些上限都在分配内存和修改状态前检查。

## 独立保存的数据

- marker 的材料质量/体积与 chemical parcel 分开保存。
- parcel 保存 stable species quanta、组成、净电荷、marker mass residual 和 indicator provenance。
- 区域保存逐 species 总量、组成/电荷审计、external source、escaped sink 与 residual ledger。
- `FluidMixture` 只保存机械材料库存；`ChemicalContents` 保存完整化学容器库存。两者不能同时作为同一物品的原始数据。

`1 AU = 1,000,000,000 quanta`。species 绝对库存是非负 `long`，加减乘和总账使用 checked arithmetic。

marker 保存 mixture handle 与 indicator bit；handle 指向 `ChemicalMixturePool` 中的不可变 `ChemicalMixtureRecord`，记录包含按 runtime species handle 排序的 `(species, amountQuanta)` 数组和 raw mass-residual bits。

每个活动 marker 独立拥有一个 pool handle；多个 handle 可共享同一不可变组成记录。修改时替换该 handle 的记录，删除时释放 handle。marker ordinal、handle 和 material/chemical-phase 绑定必须同时有效。

整批载体重建通过 `planMarkerRemap` 校验完整记录表、注册表 handle 和最终区域总量，生成绑定 store 与 mutation revision 的不可变计划。提交复用保留 ordinal 的独立 handle，释放缩容尾部并为新增 ordinal 分配 handle，最后一次更新总量；dirty cell、反应轮转位置及 source/sink 账本保持连续。过期计划和超出最终库存上限的候选在写入之前拒绝。

`FlipChemicalParticleState` 捕获时对不可变运行时记录去重，按 species 数量、规范 species handle/数量和 residual raw bits 排序。构造器逐项检查顺序、逐 marker 质量关系、species 总量和 residual 总账；同一注册表下恢复复用记录，重新建立独立的 marker handle 和记录引用数组。恢复时按已知 marker 数预留组成池容量，避免逐级扩容复制。

稳定 ID 表由快照在首次导出时生成不可变副本。运行时 species handle 的顺序由注册表的 stable ID 排序确定，因此延迟生成保持原有表顺序与 marker 索引。冷恢复仍从稳定记录转换并执行完整校验。不可变化学记录按注册表对象身份缓存一份精确 species 质量；缓存值与注册表引用一起发布，重复校验保持相同有理数结果。

质量关系为：

```text
particleMass * cellSize^3 = exact_species_mass_kg + massResidual_kg
```

`ChemicalSpecies.massKilogramsPerAu` 与 parcel residual 都使用 kg；`SolverUnitScale` 是它们与 liquid marker solver mass 之间的唯一换算。reaction 改变 species 列；residual、marker 体积、机械材料和 phase 保持原值。逐载体质量随实际 species 分配改变时，速度通过同格质量转移的动量计划更新。split/merge/transfer 按同一物质权重搬运 residual，raw-bit checkpoint 后仍须闭合允许的 ULP 预算。

## 反应模型

- 当前只支持强电解质完整解离和同格、同 chemical phase 的瞬时计量反应。
- 定义使用 exact rational 化学计量；提交必须同时闭合 species、组成、电荷、质量和残差。
- 不实现弱酸、缓冲液、活度、有限速率动力学或通用平衡求解。
- 指示剂是 parcel provenance：随 marker/reseed/容器输运，不隔离反应，也不自动传播到后来混入的 marker。

反应引擎对完成 advection 的 marker 按 `(cell, chemicalPhase)` 分组，汇总每个 species 的 integer quanta。对 canonical reaction 顺序计算最大完整 extent：每个反应物库存除以对应计量系数并取最小值。只有完整整数 extent 可提交；products、composition basis、charge 和 exact species mass 必须同时回和。

反应调度保存 dirty 集与轮转起点，backend handoff 完整传递两者。每步预算按完整 cell 分配；额度用尽后保留余下任务，下一步从该 cell 继续。region HEAD 冷恢复从 committed marker 位置标记所有占用格，后续按精确 dirty 集增量求值。

反应后的格总库存按各 marker 的 exact species mass 转成 binary64 权重，以最大余数法确定性分配；余数相同时按 marker ordinal 排序。分配计划验证每个载体的物种槽位、正质量、区域总量与候选温度，再统一提交。同格、同 chemical phase 内移出的实际质量携带原速度，接收质量按输入动量与保留质量混合；动量误差受 binary32 速度舍入限制，质量混合保持动能不增。完整速度计划在提交前验证，与组成和质量一起生效。混合耗散的热结算沿用热机械收支的适用范围。载体数、容量或可用内能限制当前候选时，原库存、质量与速度逐位保留，所属 cell 保持 dirty，其他可提交的 cell 继续推进。表示条件改变后沿同一入口重试。

若求值后的逐 species integer inventory 与输入逐位相同，该组只占用求值预算并清除 dirty 状态，不进入分配、热候选验证或 parcel 覆写，marker 的组成、残差、质量和 indicator 原样保留。

当前反应是瞬时同格模型，不应用 Arrhenius 速率、平衡常数或跨格搜索。

### Indicator 与 pH

indicator 添加只标记当时可接受 indicator 的互溶连通 parcel，受 100 BV 硬上限；超过上限整次拒绝。标记随 advection、reseed 和容器传输，后续混入的未标记 parcel 不自动继承。

pH 是根据局部 `H+`/`OH-` 相对数量派生的 0–14 展示指标，供指示剂显色与界面显示。客户端接收每格至多四个展示 species、量化 pH 和可见 indicator fraction，专用于渲染展示。

## 求解顺序

每个 logical step 中，机械 advection 与导热完成后按当前 cell 和 chemical phase 分组反应。反应保持每份液体载体的总内能，提交后的 species 与质量通过热状态函数重新推导温度；反应热体现为组成基准能与显热之间的转换。密度、压力和客户端颜色随后从已提交库存重新派生。

热学开启时，reaction plan 先证明 species/质量闭合，再用各载体原有总内能、候选组成与候选质量验证正温可表示性，成功后统一提交 species 与质量。颗粒 precipitation 使用同一 liquid chemical ledger，并在 granular/thermal 联合 plan 中验证 solid composition、SVQ、容量和 U；颗粒反应按同格总内能守恒，依据最终液固库存重新分配各载体内能与温度。

## 跨层边界

- region checkpoint 保存完整 parcel、registry identity 和审计账本。
- 容器转移先验证目标物品可完整表示 species、indicator、质量和 residual，再进入 WAL 事务。
- 创造模式放出是 external source；创造模式装取不能制造隐藏 sink。
- 客户端只接收 Top-N species、pH 和 indicator 等量化展示；这些字段不能恢复或结算库存。
- `chemstatus` 是管理员只读诊断并写 command transcript。

`ChemicalContents` 使用 canonical group 表，每组绑定 mechanical material、indicator、nanovolume、kg mass/residual 和排序 species 列。物品总量必须与各组 exact sum 一致；部分抽取按体积分配 quanta 和 residual，并把确定性 remainder 留给一侧。容器无法表示完整结果时，在 solver 修改前拒绝。

区域 source/sink 的 group 聚合对 volume、mechanical kg mass 与 chemical kg residual 三列分别按输入 `double` 的精确二进制值求和，最后各自只舍入一次；不能用顺序相关的逐 marker 浮点累加制造 species/mass 审计差额，也不能由 species 质量反算 mechanical mass 或 residual。

区域 source/sink 不只是 species 总量：每个 transfer group 同时保存 material/indicator identity、volume、mass、residual 和 species。collision escape、`empty`、创造放出和创造装取分别走同一 ledger，不允许“只记总质量”后丢失组成证据。

## 功能布局

区域载荷中的 chemistry presence 必须与本次启动的 chemistry 配置完全一致。功能配置变化不会向既有 checkpoint 添加或删除化学原始数据。

容器转移先规划完整的 marker 分配。低于一个默认化学量子的注入体积返回容量受限结果；拆分的尾部与前一份均分，避免生成无法携带物种的微小 marker。抽取若不能把某份 marker 分成两份非空化学载体，则继续查找其他 marker。只有全部体积、质量、物种和内能都可表示时才提交，失败保留原库存。

所有外部凝聚相注入同时检查局部开放空间和区域总容量。总容量从几何体积减去整数固体 SVQ 与实际液体库存计算；已有密闭气体的区域必须留下正气腔体积。液体 marker 在局部短暂聚集时，其他单元的空位仍受该全域约束。
