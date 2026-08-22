# M7 守恒化学推行计划

## 0. 文档定位

本计划用于把当前主 Mod `0.12.0` 的 M0–M6 基线推进到 M7。目标读者是接手实现的另一个 AI；内容只保留项目特有约束、容易误判的设计点、代码落点、事务顺序和验收门，不复述 Java、Fabric、Git 或单元测试常识。

本文是实施提案，不替代现行规范。开始每一轮前按以下顺序重新读取当前事实，并用符号搜索确认文件仍存在：

1. `README.md`
2. `docs/README.md`
3. `docs/maintenance/routing.md`
4. `docs/architecture/fluid-solver.md`
5. `docs/architecture/m3-material-state-migration.md`
6. 涉及存档、容器或 ownership 时读 `docs/architecture/world-ownership-and-containers.md`
7. `docs/roadmap/product-roadmap.md` 的 M7
8. `docs/roadmap/materials-and-chemistry.md`
9. `docs/roadmap/cross-cutting-contracts.md`
10. `docs/roadmap/acceptance-and-release.md`

本文基于提交 `4822e28`。若代码已经前进，以现行 architecture、routing 和代码为准；不得为了贴合本文回退已通过的契约。

---

# 1. M7 的完成定义

M7 关闭时，活动 CPU 域必须具备以下完整闭环：

```text
数据驱动 species / chemical phase / reaction definitions
        ↓
marker 稀疏化学库存与显式离子
        ↓
同一数值格、同一可混相内的确定性计量反应
        ↓
region-state v3 / chemical container / container WAL v3
        ↓
protocol v3 的只读展示字段
        ↓
客户端组分色、游戏化 pH 和通用指示剂显示
```

以下结果必须同时成立：

- `NaNO3 + KCl` 与 `NaCl + KNO3` 完全解离后得到逐 species 完全相同的离子库存；
- `HCl + NaOH` 按限制反应物完全中和，酸或碱过量时只留下正确的过量离子；
- 每个逻辑化学步前后，species AU、声明组成基元、电荷、质量和 `particleVolume` 体积账本按各自定义闭合；
- 物种可随 marker 输运、分裂、合并、checkpoint、重启、装桶、倒桶和 container WAL 恢复而不丢失；
- 指示剂标志随原 parcel 移动，不能因后来混合、reaction 或 renderer 插值复制到未标记 parcel；
- 化学门关闭时，M0–M6 的求解、v1/v2 存档、v1/v2 wire 和旧容器行为不改变；
- 颜色、pH 和网络量化字段始终是派生展示，不能参与反应或库存结算。

M7 不是“在水面上加酸碱颜色”。任何只满足视觉结果、但没有跨重采样和重启守恒的实现都不算完成。

---

# 2. 首版范围冻结

## 2.1 纳入 M7

- `AU` 的精确整数化表示与溢出边界；
- species、aqueous chemical phase、reaction 的 codec、装载、规范化和世界级定义指纹；
- 强电解质完全解离；
- 无动力学、同格、同可混相的整数计量反应；
- 显式 `H+`、`OH-`、旁观离子和水 species；
- 游戏化 0–14 pH；
- 通用指示剂和抹除器；
- marker chemical parcel、稀疏池、hard cap、压缩和诊断；
- chemistry-aware reseeding；
- region-state v3 与 v1/v2→v3 原子迁移；
- chemical container component 与 container WAL v3；
- protocol v3 与客户端派生颜色；
- 管理员开发入口、自动门和最小实机 smoke。

## 2.2 明确不纳入

- 弱酸、缓冲液、活度、离子强度、平衡常数、可逆平衡和反应动力学；
- 有限溶解度、析出、沉淀、颗粒生成和沉积；这些属于 M8；
- 温度、内能、反应热、相变和温压依赖；这些属于 M9；
- 催化剂、界面反应和跨不可互溶界面的反应；
- volume-changing reaction；M7 loader 只接受声明体积变化为零的反应；
- 气相化学。产品路线的 M7 首批验收是 aqueous 强电解质，而 M5 残留说明曾把气相反应标为 M7。M7.0 必须在 decision record 中明确：本次关闭按主 M7 验收只做液相，气相化学待气体持久库存和热力学边界具备后另立增量；不得顺手把 runtime-only `AirNetwork` 扩成第二套化学系统；
- GPU chemistry 布局和 GPU solver 接入；M7 先维持 CPU 权威；
- 调度阈值重调。化学引入的 wall time 必须单独量化，不能用放宽 `REDUCED/PAUSED` 阈值掩盖；
- 开放世界水、河流、海洋和外部 Mod 化学 API。

---

# 3. 当前基线中不能复用为 M7 真值的格式

M7 开始时，下列格式都已经冻结在不含化学库存的旧语义：

| 边界 | 当前格式 | M7 处理 |
|---|---:|---|
| 纯水 region state | v1 | reader 与 canonical bytes 保持不变 |
| 多材料 region state | v2 | reader 与 canonical bytes 保持不变；迁移到 v3 |
| 纯水 snapshot | protocol v1 | 保持不变 |
| 多材料 snapshot | protocol v2 | 保持不变；化学门开启时严格协商 v3 |
| 材料桶混合物 | `FluidMixture` v1 | 不加可选 species 字段；新增 `ChemicalContents` |
| region↔item WAL | container-transfer v2 | 保留 reader/recovery；化学事务写 v3 |
| server config | v3 | 新增 config v4 |

禁止以下兼容性假象：

- 把 species 编成 `FluidMixture` 的伪 material ID；
- 把 mixture handle 放进 `RegionExtension`，而 marker 主状态仍声称是 v2；
- 在 protocol v2 尾部直接追加客户端必读字段；
- 用 material density、颜色或 tooltip 文本反推 chemical inventory；
- 在 WAL v2 的 `materialId` 字符串中塞一个新化学格式。

这五种做法都会让状态在某条旧 reader、重启或恢复路径中静默退化。

---

# 4. M7 的真值与派生量

| 状态 | 真值 | 表示要求 | 禁止用途 |
|---|---|---|---|
| 液体体积 | `Σ particleVolume` | 继续使用现有 float marker ledger | 不从 AU、density 或 mesh 反推 |
| 化学物质量 | 逐 species `long amountQuanta` | 固定比例 AU 量子，所有加减 checked | 不用颜色或 fraction byte 结算 |
| 声明组成 | `Σ amount × compositionVector` | 整数/大整数校验 | 不从 species 名字猜元素 |
| 电荷 | `Σ amount × charge` | 有符号 checked 账本 | 不以“看起来中性”替代校验 |
| 质量 | 现有 `particleMass` + species 推导质量与显式 residual 的一致关系 | 保留 raw double 持久语义；reaction 的定义质量严格守恒 | 不从参考 density 回填 |
| 指示剂 | marker/parcel 二值标志 | 无 AU、无质量、无浓度 | 不参与 reaction、density 或 pH |
| pH | `H+`/`OH-` 与局部体积的派生显示 | 有界、确定性、无 NaN | 不持久为库存，不反向生成离子 |
| 客户端颜色 | material/species 展示字段 + pH + 标记比例 | 只读量化 | 不回传服务端、不作为相身份 |

`particleMass` 在 M3 已经是独立质量真值；M7 不能悄悄改成每次都由 species 浮点重算。正确桥接是：

```text
particleMass
  = speciesAmounts × exact massPerAmount
  + explicit massResidual
```

其中 reaction 必须保持 species 部分的精确质量不变，迁移和定点化造成的不可表达差额进入 residual。任何操作都必须同时验证区域 `particleMass` 总账与 `speciesMass + residual` 总账。

---

# 5. M7.0：生产代码之前的设计门

M7.0 不接 Fabric gameplay，不升主 Mod 版本。它必须生成一份 `docs/progress/m7.0-chemistry-state-review.md`，冻结下列决策后才允许进入 M7.1。

## 5.1 AU 量子

建议候选：

```text
1 AU = 1_000_000_000 chemical quanta
```

设计门必须用首批内建 reagent、最大单桶、最大默认区域和最大合法 species 数验证：

- 单 parcel、单 region 和 container 聚合都不会 `long` overflow；
- 所有 reaction coefficient 在量子尺度上整除，不需要反应时浮点取整；
- 10,000 次 split/merge/container round-trip 后逐 species `long` 总和逐位不变；
- 对超过边界的 definition 或状态在分配数组前拒绝。

若 `1e9` 不满足，替代比例必须写入 registry snapshot 和 region-state v3，不能成为代码外的隐式常量。

## 5.2 稀疏库存候选

在同一个最大默认域 fixture 中比较：

### A. 绝对 parcel record + handle

```text
marker -> int mixtureHandle
handle -> sorted (speciesHandle, absolute amountQuanta)[] + massResidual
```

优点是 split、merge、reaction 和序列化的正确性最直接。相同 record 可 intern；纯水常见 marker 仍有较高共享率。

### B. 规范化组成 handle + marker scale/residual

```text
marker -> compositionHandle + amountScale + residualHandle
```

共享率更高，但必须证明任意 split/merge 不产生无界 residual 和 handle 组合。

### C. 固定少量 inline slots + overflow arena

可能减少间接访问，但对超过 inline 数的组合、排序和 codec 更复杂。

本计划建议首版选择 A，且用 packed arrays/arena 实现，不给每个 marker 分配 `Map` 或独立对象。M7.0 可用证据选择 B/C，但后续 state、codec、reseeding 和门必须全部按 decision record 同步，不能混搭。

## 5.3 必测 workload

- 73,728 markers 的纯水 no-op；
- 每 marker 1、4、8、16 species；
- 两股盐溶液持续翻转混合；
- 酸碱在每个 CFL substep 后反应；
- 反复 split/merge 10,000 轮；
- handle 近似最坏情况：相邻 parcel 组成各不相同；
- pool 达软阈值时 compact；
- pool 到 hard cap 前最后一个成功操作和第一个失败操作；
- checkpoint canonicalization；
- protocol v3 候选编码的最大包。

## 5.4 设计门输出

必须记录：

```text
AU scale
mixture record layout
entry layout
handle 0 语义
intern key
compaction trigger
hard cap
每 marker 摊销字节
默认最大域额外 heap
纯水 chemistry-on no-op 开销
强混合 p50/p95 step wall time
最大 v3 snapshot 字节
overflow/failure 原子语义
选择方案及拒绝其它方案的证据
```

建议停止线：默认最大区域的 chemistry 额外常驻 heap 不超过 64 MiB；chemistry 开启但没有活动 chemical dirty cell 时中位 step 开销不超过关闭时的 5%。若达不到，先修改表示或 dirty tracking，不进入 solver 正式接线。

---

# 6. 数据定义与世界级 registry

## 6.1 不重写 M3 mechanical material registry

M7 首版保留：

```text
FluidMaterial
FluidMaterialRegistry
material runtime handle
miscibility phase handle
```

它们继续负责机械材料、参考密度、表面张力和接触角。新增 chemistry overlay：

```text
ChemicalSpecies
ChemicalPhaseDefinition
ChemicalReaction
ChemicalRegistry
```

`ChemicalPhaseDefinition` 通过稳定 material ID 绑定现有 mechanical material，并声明允许 species、默认 carrier inventory 和是否接受指示剂。运行时 chemical handle 与 material handle 都不能上链。

## 6.2 数据包路径

建议固定为：

```text
data/<namespace>/realwater/species/*.json
data/<namespace>/realwater/chemical_phases/*.json
data/<namespace>/realwater/reactions/*.json
```

最小 schema 形状：

```json
{
  "schema": 1,
  "composition": {"H": 1, "Cl": 1},
  "charge": 0,
  "mass_per_au": "36.460000",
  "base_color": "#fff8f2e8"
}
```

```json
{
  "schema": 1,
  "material": "realwater:water",
  "category": "liquid",
  "allowed_species": ["realwater:water", "realwater:h_plus"],
  "default_inventory_per_bv": {"realwater:water": "55.500000"},
  "accepts_indicator": true
}
```

```json
{
  "schema": 1,
  "kind": "complete_dissociation",
  "phase": "realwater:aqueous",
  "reactants": {"realwater:hcl": 1},
  "products": {"realwater:h_plus": 1, "realwater:chloride": 1},
  "volume_delta_bv_per_extent": "0"
}
```

reaction coefficient 只允许正整数；`mass_per_au` 和默认 amount 使用 canonical decimal string 解析为约分后的 exact rational，禁止先转 `double` 再验证。

## 6.3 loader 必须拒绝

- 非法/重复 ID、未知 species/phase/material；
- 非正 species mass、负 amount、零或负 reaction coefficient；
- composition key 空白、系数为零、整数 overflow；
- reaction 前后组成向量、电荷或 exact mass 不守恒；
- M7 reaction 的非零体积变化；
- `interface_contact=true`、气相、颗粒相、温压条件或 catalyst；
- chemical phase 与 mechanical miscibility/phase 约束冲突；
- complete-dissociation 图有环；
- 同一 phase 中一个可消费 species 被两个 instantaneous reaction 竞争；
- instantaneous reaction 依赖图无终止秩或必须靠 stable-ID 顺序才能决定不同最终库存。

最后三条是首版确定性的关键。M7 不引入 priority 来掩盖歧义；定义表达不了的网络直接拒绝，未来用独立 reaction solver 设计门扩展。

## 6.4 首批内建定义

至少包含：

```text
H2O
H+
OH-
Na+
K+
Cl-
NO3-
HCl
NaOH
NaNO3
KCl
NaCl
KNO3
```

并为当前所有可持久 mechanical materials 提供不可反应的 carrier species/default inventory，使 v2→v3 不会因油、汞等已有 marker 无法映射而失败。虚构 carrier 可以使用抽象 composition basis，但仍必须有稳定 ID、质量和组成。

## 6.5 世界级 immutable registry snapshot

数据包定义不能在 `/reload` 后重新解释已持久化的 region 或 item。首次以 chemistry 门启动世界时，原子发布：

```text
<world>/realwater/chemistry/registry.nbt
```

至少保存：

```text
schema version
AU scale
pH display constant
canonical species/phase/reaction definitions
stable sorted palettes
SHA-256 fingerprint
```

后续启动：

- 数据包 canonical definitions 与 snapshot 相同：正常使用；
- 数据包不同：保存的 snapshot 继续作为已有状态权威，禁止激活新 definitions，状态报告 mismatch；不重解释旧库存；
- snapshot 缺失而已有 v3 region/WAL/component：全局 chemistry LOCKED；
- snapshot schema 未知或损坏：全局 chemistry LOCKED；
- 不提供自动 definition migration；显式 registry migration 工具不属于 M7。

功能门关闭时不得删除 snapshot 或 v3 状态。

---

# 7. 化学运行时表示

下述接口名可调整，职责不能合并回 `FlipFluidDomain` 巨型类。

## 7.1 `ChemicalMixturePool`

建议职责：

- packed offsets/lengths/species handles/amount quanta；
- canonical sort、去零和 checked sum；
- immutable record interning；
- split、combine、reaction result 的规划；
- soft compaction 和 hard cap admission；
- runtime handle 重写；
- 创建/复用/压缩/overflow 统计。

handle 只在一个 loaded domain 内有效。持久化时写 canonical mixture table index；恢复时重新 intern。不要把 Java hash iteration order、object identity 或 runtime handle 写入 checksum。

## 7.2 `ChemicalParcelStore`

建议由一个内聚 store 拥有：

```text
int[] mixtureHandle
boolean/bitset indicatorMarked
double[] massResidual
dirty marker/cell tracking
```

它向 solver 暴露 marker copy/swap/remove/split/merge 回调。不要为了 M7 一次性重写位置、速度、体积和 M3 material arrays；只把新增化学状态与其守恒操作从 domain/reseeder 中隔离。

## 7.3 hard limits

M7.0 必须冻结且随后写入 config v4 或 registry snapshot：

```text
maximum species definitions
maximum composition bases
maximum reaction definitions
maximum species entries per parcel
maximum live mixture handles per region
maximum packed entries per region
maximum dirty chemical cells per logical step
maximum reaction evaluations per logical step
maximum chemical component bytes
maximum indicator flood-fill visited cells
```

建议起始候选分别为 `256 / 64 / 512 / 16`；后四项依据 M7.0 workload 决定，不能直接等于无限 marker/cell budget。

达到 hard cap 的语义：

1. 当前逻辑 chemical step 使用只读旧状态做 plan；
2. 规划阶段发现 handle/entry/op budget 不足；
3. 整个 chemical step 不提交任何 parcel 修改；
4. region 标记 `chemistryBlocked` 并进入可读的 PAUSED/availability failure；
5. checkpoint 仍保存最后一个完整状态；
6. 不丢 trace species、不截断列表、不退化成平均颜色。

---

# 8. 确定性 reaction engine

## 8.1 规范化顺序

对一个 cell/phase 的聚合库存按以下顺序执行：

1. 将 complete-dissociation reactions 推到固定点；
2. 按依赖拓扑序执行 instantaneous reactions；
3. 每条 reaction extent：

```text
min(floor(reactantAmountQuanta / stoichiometricCoefficient))
```

4. extent 为零时跳过；
5. 生成物使用 checked integer multiply/add；
6. 去除零 amount，canonical sort；
7. 验证 amount、composition、charge、species mass；
8. 最后生成新 immutable record。

stable reaction ID 只用于 canonical serialization 和同一拓扑层的稳定遍历。loader 已保证同层没有竞争消费者，因此遍历顺序不能影响终态。

## 8.2 cell 聚合边界

reaction group key 至少是：

```text
(cell index, mechanical phase label / compatible chemical phase)
```

同格但以下情况不能聚合：

- phase 不同；
- materials 属于不可互溶组；
- chemical phase 不允许某 species；
- 中间有 solid/cut-cell 阻隔且 marker 实际不属于同一 cell group。

不扫描相邻格寻找 reaction partner。只有数值输运已把 reactants 带入同一 cell/phase 后才反应。

## 8.3 reaction 后重新分配

对 cell/phase 中按 marker ordinal 排序的 marker：

- 先聚合逐 species exact `long` 总量；
- reaction 后按 marker `particleVolume` 比例重新分配每个 species；
- 使用 exact largest-remainder apportionment；余数相同时 marker ordinal 小者优先；
- float volume 权重按其二进制精确值解释，reference oracle 可用大整数分数；优化实现必须与 oracle 一致；
- 每个 species 分配后的 `long` 和必须逐位等于 cell reaction result；
- material/phase handle 不因化学混合而改变；
- indicator bit 保留在原 marker，不参与比例分配；
- reaction 前后 cell 总 `particleVolume` 和速度不变；
- particle mass 由新 species mass 与 residual 重新协调，使 region 的 raw-double 质量账本符合定义。

若不同 indicator bit 的 marker 在同格，species 仍可混合和反应；但标志不随 species 分配扩散。

## 8.4 质量 residual 协调

每个 chemical step 先记录按 marker ordinal 求和的 `particleMass`。reaction 定义 exact-mass 守恒，所以目标总质量等于旧总质量。新 parcel 的 species-derived mass 计算后：

- 旧 residual 聚合总量不得消失；
- residual 按与 species 相同的确定规则分配；
- 最后一个有资格 marker 承担 double rounding reconciliation；
- 非有限、负总质量或无法保持正 marker mass 时整步拒绝；
- validation 同时比较 raw bits/既有 M3 账本与 exact rational species mass + residual 账本。

不得通过修改 `FluidMaterial.referenceDensity` 来“修正”反应质量。

---

# 9. 接入 `FlipFluidDomain` 的准确时序

当前每个 CFL substep 的主要顺序是：

```text
rebuild grid
gas update
save P2G velocity
gravity
surface tension
pressure
G2P
advect
```

M7 建议固定为：

```text
rebuild grid
gas update
save P2G velocity
gravity
surface tension
pressure
G2P
advect
rebuild post-advection chemical cell membership
ChemicalStep plan
ChemicalStep validate
ChemicalStep commit
```

原因：

- 同一外部 tick 的 CFL substep 数不能改变“何时才有资格同格反应”的语义；
- reaction 后的质量应在下一 substep 的 P2G/变密度压力中可见；
- M7 reaction 不改体积和速度，不需要在同一 substep 重跑 pressure；
- 若只在所有 substeps 结束后反应，高速穿越/混合结果会依赖调度步长。

全部 substeps 后再执行现有 reseeding。reseeding 必须消费 chemistry-aware 回调；之后 rebuild 维持现有行为。

## 9.1 dirty tracking

chemical cell 进入 dirty 的条件：

- post-advection marker membership 改变；
- container/reagent 插入；
- v2→v3 restore canonicalization；
- marker split/merge 改变 parcel；
- definition normalization 尚未完成；
- 外部 debug 化学操作。

纯水、无可反应 species 且 membership/handle 未变的格不运行 reaction engine。hydrostatic sleep 还必须要求：

```text
no dirty chemical cells
no pending reaction plan
no pending indicator operation
no chemistryBlocked recovery
```

化学输入必须 wake region；化学状态不能因 region 进入 REDUCED 或 sleep 被跳过到永远不处理。

---

# 10. chemistry-aware reseeding

当前 `FlipParticleReseeder` 只复制 material/phase/mass arrays，且只把相同 material/phase marker 互相 merge。M7 必须显式扩展，不可在 reseed 结束后凭位置猜回 chemical state。

## 10.1 split

- 新 marker 继承 material、phase 和 indicator bit；
- 每个 species amount 按两个新 `particleVolume` exact apportion；
- 两边 amount 和等于旧 parcel；
- mass residual 和 `particleMass` 使用同一 split/reconciliation 规则；
- mixture pool admission 先规划两个 target handles，再改 marker arrays；
- admission 失败时 split 取消，几何与所有 ledger 都保持原状。

## 10.2 merge

- material/phase 不同：继续禁止；
- indicator bit 不同：必须禁止，否则一个二值 marker 无法表达标记体积分数；
- indicator 相同、chemical mixtures 不同：允许通过 exact combine 形成新 record；
- combine handle admission、合并位置合法性和动量/质量规划全部成功后才删除 source；
- compact 时 chemical arrays 与已有 marker arrays 使用同一 source→destination 映射。

## 10.3 重构边界

推荐给 reseeder 增加包内 `MarkerAuxiliaryState` 回调：

```text
canMerge(target, source)
planMerge(target, source)
commitMerge(...)
planSplit(source, destination, firstVolume, secondVolume)
copy(source, destination)
compact(source, destination)
```

默认 no-chemistry 实现只维持当前 material/phase 行为；chemical 实现委托给 `ChemicalParcelStore`。不要继续向 `reseed(...)` 参数表追加四五个平行数组。

---

# 11. pH 的首版定义

pH 只在 reaction 已完成的 cell/phase 聚合库存上计算。建议游戏化公式：

```text
net = H_plus_amount - OH_minus_amount
concentration = abs(net) / liquidVolumeBV
strength = min(7, log10(1 + concentration / C0))

net = 0  -> pH 7
net > 0  -> pH 7 - strength
net < 0  -> pH 7 + strength
```

`C0` 是 registry snapshot 中的正 exact rational AU/BV，只影响显示映射，不影响 reaction。M7.0 用首批 reagent 浓度冻结 `C0`，并要求：

- 酸/碱关于 7 对称；
- 浓度单调；
- 零体积、非有限转换和 overflow 有明确拒绝/neutral 行为；
- clamp 后严格在 `[0,14]`；
- 客户端 byte 量化 round-trip 有界；
- pH 不持久化；restore 后从库存重算；
- pH 不用于判断中和是否发生。

若希望改用另一公式，必须保持这些性质并更新 registry fingerprint；不能在同一 world 无迁移改变显示比例。

---

# 12. 通用指示剂与抹除器

## 12.1 添加算法

1. 从命中的 occupied liquid cell/phase 开始；
2. 用 6 邻接 flood fill；
3. 邻接要求 shared cut-cell face 可通、mechanical phase/互溶组兼容、chemical phase `accepts_indicator=true`；
4. 只访问当前有 marker 的液格；
5. 聚合候选 marker 的 exact `particleVolume`；
6. 一旦证实总量 `>100 BV`，停止规划并整次拒绝；
7. 输出“已确认至少 X BV，单次上限 100 BV”，不报告伪精确总体积；
8. 全部通过后一次性把候选 marker 标为 true；
9. 已标 marker 不重复计为新增，但仍属于连通范围。

扫描期间不改状态，因此超限、chunk/region availability 变化或预算失败都不会留下半个标记区域。

## 12.2 后续输运语义

- bit 随 marker advection；
- split 两边都继承；
- 不同 bit 不 merge；
- 同格 chemical redistribution 不复制 bit；
- 后来倒入的未标液体保持 false；
- 客户端用格内 `markedVolume / totalVolume` 的量化比例混色。

## 12.3 抹除

抹除器从目标液相做同样的可通行 flood fill，然后清除其中所有 true marker。它不受 100 BV gameplay 添加上限限制，否则多个标记团合并后可能无法清除；但仍受 `maximum indicator visited cells` 安全预算。超出安全预算时整次拒绝并保留所有 bits。

## 12.4 持久和容器

indicator bit 是 parcel 状态，因此必须进入：

```text
region-state v3 marker record
ChemicalContents parcel groups
container-transfer WAL v3 fingerprint/after item
protocol v3 marked fraction
```

它不进入 species 列表、composition、电荷或质量账本。

---

# 13. server config v4 与功能门

`FluidServerConfig` 增加：

```text
chemistryEnabled=false
chemistry limits（M7.0 冻结的 world/operator 可调子集）
```

兼容语义：

- config v1：materials/worldBridge/chemistry 都 false；
- config v2：worldBridge/chemistry false；
- config v3：chemistry false；
- config v4：显式读取 chemistry；
- `chemistryEnabled=true` 且 `materialsEnabled=false`：配置无效并 fail closed，不自动替管理员开启另一功能；
- chemistry 与 materials 一样是 startup-bound；命令只保存下次启动值；
- chemistry 可在 worldBridge 关闭时供瞬态开发域验证；真实装桶/倒桶仍要求 worldBridge；
- 关闭 chemistry 不删除 registry snapshot、v3 regions、chemical items 或 WAL。

命令建议：

```text
/realwater chemistry
/realwater chemistry true|false
```

输出必须同时区分：

```text
current startup gate
persisted next-start value
registry loaded/mismatch/locked
v3 region count
pending chemical WAL count
```

---

# 14. region-state v3

M7 必须占用 region-state v3，因为 chemical marker state 是物理真值，不是可丢弃 extension。原路线中“气体若持久化需要 v3”随之顺延：气体未来单独评审 v4，M7 不顺手持久化气体。

## 14.1 v3 generation 内容

在 v2 material/section 状态之外增加：

```text
chemistry schema version
registry fingerprint
AU scale
stable species palette
canonical mixture table
per-marker mixture table index
per-marker indicator bit
per-marker mass residual raw bits（若 M7.0 选定该布局）
region total per-species amounts
region composition totals
region charge total
region species-derived mass + residual ledger
per-section corresponding ledgers
chemistry-inclusive checksum
```

持久 mixture table 必须按 canonical record bytes 排序并重新编号。runtime handle 不能直接写入；section marker 只引用当前 generation 内 table index。

## 14.2 decode 顺序

1. 读取小固定头和 declared counts；
2. 对照 config/manifest hard limits；
3. 验证 registry fingerprint 与 AU scale；
4. 再分配 palette/table/marker arrays；
5. 验证 stable IDs、canonical sort、无重复/零 amount；
6. 验证每个 table index；
7. 回算 section/region species、composition、charge、mass residual、volume、marker count；
8. 校验 checksum；
9. 全部成功后构造 `FlipChemicalParticleState` 并重新 intern。

任何一步失败都不得回退为 v2 或“忽略 chemistry extension”。

## 14.3 v2→v3 迁移

对每个 marker：

1. 用 stable material ID 找到 chemical phase/default carrier；
2. `particleVolume` raw bits 原样保留；
3. `particleMass` raw bits原样保留；
4. 按 default inventory 和 AU scale 生成 canonical species amount；
5. 不能由整数量子精确表达的质量差进入 residual；
6. material/phase 不变，indicator=false；
7. 生成完整目标 generation；
8. 写 inactive slot/temp、force、读回并校验全部账本；
9. 最后原子发布 HEAD；
10. 迁移失败保留旧 HEAD 和旧 v2 状态。

迁移必须有 crash injection：写前、table 后、section N 后、rename 前、HEAD 前、HEAD 后、cleanup 前。重启只能看到完整 v2 或完整 v3。

## 14.4 gate/downgrade

- chemistry 开启：新 controller region 和迁移后的 region 写 v3；
- chemistry 关闭：现有 v1/v2 按旧路径运行，不主动升级；
- chemistry 关闭但发现 v3：保留 metadata/ownership，region 不加载 solver、不写低版本 checkpoint，状态为可读 LOCKED；
- v3 不提供 downgrade；
- v1/v2 encoder、CRC 和 golden bytes 不改。

---

# 15. chemical container 与精确转移

## 15.1 新 `ChemicalContents`

不要修改 `FluidMixture` v1。给 `MATERIAL_BUCKET` 增加独立 typed data component，例如：

```text
ChemicalContents
  schemaVersion
  registryFingerprint
  parcelGroups[]

parcelGroup
  stable materialId
  stable chemicalPhaseId
  volumeNanos
  mass raw bits
  mass residual raw bits
  indicatorMarked
  sorted (stable speciesId, amountQuanta)[]
```

同 material/phase/indicator 的 groups 在桶中视为已充分混合并运行 complete dissociation/reaction 后 canonicalize。不可互溶 group 分开；true/false indicator group 分开。这样既不会保存逐 marker 垃圾，也不会把标记比例压成一个 bool。

组件使用 bounded `Codec` + `StreamCodec`，不使用无界 textual blob。建议总编码上限 32 KiB；确切上限由 M7.0 冻结。所有 count 在 allocate 前检查。

## 15.2 与 legacy components 的关系

chemical bucket 仍携带 M6 总体积/总质量所需字段，以便 UI 和共有结算，但 chemical component 是 species 真值。读取时：

- chemical component 存在：legacy `fluid_volume`、`fluid_mass_bits` 和 material totals 必须与其逐位一致；
- chemical component 不存在：继续走 `FluidMixture` v1；
- 两者矛盾、部分缺字段或 fingerprint 未知：容器 malformed，拒绝 transfer/crafting/stack merge；
- 不从 legacy mixture 自动猜 chemical species；只有显式 v2→chemical conversion path 使用 registry default carrier。

## 15.3 solver API

新增与旧 API 分离的 all-or-nothing primitive：

```text
tryInsertExactChemical(...)
tryExtractExactChemical(...)
tryExtractAvailableChemical(...)
```

返回/接收 stable-domain transfer object，至少闭合：

```text
volume
material mass
species amounts
composition
charge
mass residual
indicator groups
```

插入必须先规划 marker capacity、mixture pool、species entries 和 chemical ops，再一次提交。抽取只能按 marker ledger 选择整 marker + 必要的尾部分裂；尾分裂按第 10 节 exact apportion。任何一项不足，solver 与 item 都不变。

旧 `tryInsertExactMixture`/`tryExtractExactMixture` 保留给 chemistry-off/v2 路径。

## 15.4 tooltip

chemical material bucket 的 tooltip 必须列出每个 hard-bounded species 的本地化名称、AU 数量、总量派生 pH 和“含指示剂/标记比例”。不得退回仅显示“x 种材料混合物”。species 上限使完整列出可控；若 UI 需要折叠，仍提供展开键并保证所有 species 可见。

---

# 16. container-transfer WAL v3

WAL v3 保存完整 canonical `ChemicalContents` before/after fingerprint 或其带校验的 canonical bytes，不能只保存总量和 material mixture。

真实顺序继续遵循现行 M6 契约，不按旧提案重排：

```text
PREPARED WAL
→ solver modification
→ WAL 更新为 REGION_COMMITTED 且含 authoritative after item
→ region checkpoint / HEAD
→ caller 修改 actor item
→ 后续 tick 幂等重放 after 并 durable save player/dispenser owner
→ 删除 WAL
```

v3 增加验证：

```text
registry fingerprint
chemical schema
species/composition/charge/mass/volume ledgers
indicator groups
canonical component hash
base/target region-state format/generation
```

恢复逻辑按 WAL 自己的 version 解码，不能按当前 chemistry gate 猜格式：

- v1 pure reader/recovery 保留；
- v2 material reader/recovery 保留；
- v3 chemical reader/recovery 新增；
- chemistry gate 关闭但存在合法 v3 WAL：不能删除或降级，保持 pending/locked，待功能与 registry 可用后收敛；
- v3 unknown species、fingerprint mismatch、actor ambiguity 或 HEAD 非 base/target：锁定，不猜 after item。

fault suite 覆盖每个 durable boundary，验证旧完整或新完整，不只验证 happy path。

---

# 17. protocol v3 与客户端

## 17.1 协商矩阵

```text
chemistry=false, materials=false -> strict v1
chemistry=false, materials=true  -> strict v2 + CAP_MATERIAL_SNAPSHOT
chemistry=true                   -> strict v3 + CAP_MATERIAL_SNAPSHOT + CAP_CHEMICAL_SNAPSHOT
```

v3 使用独立 payload type、ledger、client region state 和 tombstone cleanup。不要让 v3 包写入 v2 map，也不要用 v2 extension 承载必需 chemistry 字段。

## 17.2 v3 payload

v3 自身携带重建所需的 v2 geometry/material/phase 字段，避免 v2 geometry 包与另一个 chemistry 包跨 sequence 错代。再增加 display-only：

```text
registry/display palette fingerprint
species display palette (stable ID + base ARGB)
per-cell sparse visible species fractions
per-cell quantized pH
per-cell quantized indicator-marked volume fraction
```

不得携带：

```text
exact amountQuanta
mass residual
reaction extent
authoritative charge/composition totals
runtime mixture handles
```

建议 M7.0 比较 dense fields、每格 top-K sparse fields 和服务器预混色三种格式。首选每格 top-K（候选 K=4）+ residual display fraction：服务端只量化展示贡献，客户端根据 palette、material fractions、pH 和 indicator fraction 计算最终颜色。K、选择规则和最大包必须写入 decision record。

## 17.3 客户端颜色

渲染顺序建议：

1. 继续用 material/phase fields 重建几何；
2. 用 visible species fractions 得到基础溶液色；
3. 只在 `indicatorFraction>0` 时按 pH 色带混入；
4. 混入强度乘 indicator fraction；
5. material/phase 界面仍按现有分相几何，不因 density 卷积画成化学混合；
6. 客户端任何颜色结果不回写 physics。

protocol validation 必测所有 v1/v2 golden paths、1/2/3 capability matrix、长度攻击、palette 重复、非法 fraction、迟到旧 sequence、remove tombstone、dimension 切换、断线和重新配置清理。

Item Data Component 的精确内容同步与流体 surface snapshot 是不同边界：玩家持有物品可同步其 bounded authoritative component；区域 snapshot 仍只发送展示字段。

---

# 18. gameplay 与开发入口

正式化学物品至少包括：

- 通用指示剂；
- 指示剂抹除器；
- chemical-capable material bucket（复用现有 item，增加 component/tooltip）；
- 对应模型、纹理、creative entry、`en_us`/`zh_cn` 文本和必要配方/获取方式。

为了自动与实机验收，先提供管理员入口：

```text
/realwater chemistrydemo exchange_a
/realwater chemistrydemo exchange_b
/realwater chemistrydemo neutral
/realwater chemistrydemo excess_acid
/realwater chemistrydemo excess_base
/realwater reagent hcl|naoh|nano3|kcl|nacl|kno3 [concentration]
/realwater chemstatus [region]
```

`chemistrydemo` 创建的是可重复 fixture，不代替 controller 多区域。reagent command 生成经过 registry 校验和完全解离规范化的 bounded chemical container，不允许写任意未验证 NBT。

`chemstatus` 至少报告：

```text
region UUID/generation/state
registry fingerprint
species count
active/live mixture handles
packed entries
created/reused/compacted handles
dirty cells
reaction evaluations/extents
maximum residual
pH min/max
indicator marked BV
last chemistry block/overflow reason
```

完整逐 species authoritative inventory 只向有权限管理员按需输出并写 transcript；普通 snapshot/status 不广播精确库存。

---

# 19. 建议代码落点

## 19.1 新包

建议建立 `src/main/java/com/example/chemistry/`：

```text
ChemicalSpecies
ChemicalPhaseDefinition
ChemicalReaction
ChemicalRegistry
ChemicalDefinitionCodec / Loader
ChemicalRegistryStore
ChemicalMixtureRecord
ChemicalMixturePool
ChemicalParcelStore
ChemicalStep
ChemicalLedger
ChemicalDiagnostics
ChemicalDisplaySnapshot
```

具体类数可合并，但必须保持：definition、runtime inventory、reaction engine、persistence adapter 和 display snapshot 不互相反向依赖。

## 19.2 新跨层类型

建议：

```text
com.example.fluid.FlipChemicalParticleState
com.example.item.ChemicalContents
com.example.network.FluidChemicalSnapshotPayload
com.example.network.FluidChemicalSnapshotLedger
```

## 19.3 必然修改

```text
FlipFluidDomain
FlipParticleReseeder
FluidRegion / FluidRegionFactory
FluidRegionPersistence
RegionManifest / RegionSectionState / RegionState / RegionStateCodec / Integrity
FluidServerConfig
FluidSimulationService / FluidRegionOperations
FluidTransferService
ContainerTransferJournal / Store / Recovery
RealWaterComponents / Items / Mod
FluidProtocol / ProtocolServer
RealWaterModClient / ClientFluidVolumeRenderer
RealWaterCommands
build.gradle（新增定向门）
resources（definitions/items/lang/models/textures/recipes）
docs/maintenance/routing.md
```

不要改动旧 `FluidDomain.java`，不要把 Minecraft ItemStack/NBT/ResourceManager 依赖引入纯 reaction engine。

---

# 20. 分轮实施与提交门

每轮只在前一轮所有门通过并提交后开始。版本在 M7 关闭前保持 `0.12.0`，除非现行 release 规则已改变。

## Round 0 — 基线与计划确认

产物：

- clean worktree/当前 commit/version 记录；
- 当前 v1/v2 protocol、region-state、FluidMixture v1、WAL v2 golden fixtures；
- 受影响文件清单；
- 本计划与现行 roadmap 差异清单。

不改 gameplay。

## Round 1 — M7.0 数值、表示和 wire 原型

只做可丢弃 prototype/benchmark 与 decision record：

- AU scale；
- A/B/C sparse representation；
- mass residual；
- exact apportion oracle；
- pool cap/compaction；
- v3 display encoding；
- pH `C0`；
- aqueous-only scope resolution。

推荐提交：

```text
M7: freeze conservative chemistry state design
```

若设计门未过，不进入 Round 2。

## Round 2 — M7.1 definition registry 与纯 reaction core

实现：

- codecs/loader/canonical bytes/fingerprint；
- built-in definitions；
- immutable registry；
- exact inventory/reaction engine；
- pH derivation；
- 纯 Java validation。

此轮不接 solver、不注册 item、不改存档/wire。

新增最近门：

```text
runM7ChemistryCoreValidation
```

推荐提交：

```text
M7: add validated chemical registry and reaction core
```

## Round 3 — M7.2 marker transport 与活动 solver

实现：

- mixture pool/parcel store；
- `FlipChemicalParticleState`；
- domain post-advection chemical step；
- two-phase plan/commit；
- chemistry-aware reseeding；
- exact transfer object 的域内基础；
- dirty tracking、sleep/wake 和 diagnostics；
- chemistry-off bitwise degeneration。

新增最近门：

```text
runM7ChemicalTransportValidation
```

同时保留 `runM3ConnectedVesselValidation` 和 `runFlipReseedingValidation` 的受影响回归，但日常提交仍按 routing 选择最小一项。

推荐提交：

```text
M7: integrate conservative chemical marker transport
```

## Round 4 — M7.3 config、registry snapshot 与 region-state v3

实现：

- config v4/gate；
- world registry snapshot；
- v3 manifest/sections/mixture table/integrity；
- v1/v2 readers 保持；
- v2→v3 staged migration；
- unknown/corrupt/over-budget/fingerprint mismatch 锁定；
- loaded/unloaded region restore。

最近门：

```text
runM7ChemicalPersistenceValidation
```

它可复用/扩展 `runFluidRegionPersistenceValidation` fixture，但职责和 fault matrix 已显著扩大时应独立 task。改服务器配置/资源 reload 注册后再跑一次 EULA=true dedicated-server startup，并用 `stop` 正常退出。

推荐提交：

```text
M7: persist chemical region state v3
```

## Round 5 — M7.4 chemical containers 与 WAL v3

实现：

- `ChemicalContents` codecs；
- bucket canonicalization/tooltip；
- exact chemical insert/extract；
- player/dispenser transfer；
- WAL v3 与旧 reader；
- actor durable recovery；
- debug reagent acquisition；
- hopper/drop/death/disconnect component round-trip。

最近门：

```text
runM7ChemicalContainerValidation
runContainerTransferValidation（只在 WAL/recovery 提交）
```

推荐提交：

```text
M7: bridge chemical containers through WAL v3
```

## Round 6 — M7.5 protocol、renderer 与指示剂 gameplay

实现：

- strict protocol v3；
- v3 payload/ledger/tombstone/cleanup；
- client composition/pH/indicator color；
- indicator flood fill、标志和 eraser；
- item/resource/lang/recipe；
- `chemistrydemo` 和 `chemstatus`。

最近门：

```text
runFluidNetworkProtocolValidation
runM7IndicatorValidation
```

改 payload/entrypoint/item/resource 注册后跑 EULA=true startup，并用 `stop` 正常退出。只有颜色和真实交互自动门无法观察时才请求用户进游戏。

推荐提交：

```text
M7: expose pH and parcel indicator rendering
```

## Round 7 — M7.6 关闭里程碑

执行：

- 受影响层的完整回归，不重跑不相关 GPU/长期门；
- 一次有界 chemistry soak；
- 两个最小实机 smoke；
- architecture/routing/progress/roadmap/README 更新；
- clean assemble；
- 发布版本评审，预计主 Mod `0.13.0`，以当时 acceptance 文档为准；
- 部署时保证同一 Mod id 只有一个启用 jar 并记录 SHA-256。

推荐提交：

```text
Close M7 conservative chemistry milestone
```

---

# 21. 自动验收矩阵

## 21.1 definition/codec

- canonical JSON 读取后顺序无关、canonical bytes/fingerprint 相同；
- 非 canonical 输入可读取但 snapshot 写出 canonical；
- duplicate/unknown/negative/overflow 拒绝；
- composition、电荷、mass 不守恒的 reaction 拒绝；
- dissociation cycle、竞争消费者、非终止图拒绝；
- nonzero volume delta、gas/interface/catalyst 条件拒绝；
- 所有 built-in definitions 自检；
- registry snapshot 完整 round-trip、截断、bit flip、unknown schema、fingerprint mismatch。

## 21.2 reaction core

- HCl、NaOH、四种盐完全解离且 neutral parent species 为零；
- 两种盐组合得到逐 species、composition、charge、mass 完全相同；
- 等量 HCl/NaOH 中和后 `H+`/`OH-` 都为零；
- 酸过量、碱过量各验证 exact remainder；
- spectator ions 不消失、不互换为伪 product；
- 输入 record 顺序、marker ordinal permutation 不改变 cell aggregate final inventory；
- 0、最大合法量和 overflow 边界；
- 10,000 reaction/canonicalization 循环无 drift；
- pH neutral、对称、单调、clamp、volume 边界。

## 21.3 transport/solver

- species 随 marker advection，不能留在旧 cell；
- 同 cell/same phase 反应；相邻 cell 不反应；
- solid wall 两侧不反应；immiscible phase 不反应；
- CFL 产生不同 substep 数时，固定 fixture final inventory 相同；
- chemical step budget failure 整步零修改并暂停；
- pool compaction 前后所有 marker record 相同；
- gate off 时 M3 reference fixture 粒子/网格关键输出逐位相同；
- chemistry on 的纯水 fixture 不改变体积、速度和压力拓扑；
- reaction 质量变化分配后，下一 substep variable-density P2G 使用新 marker mass；
- sleep 不吞 pending reaction，container/indicator 操作能 wake。

## 21.4 reseeding

- split 后逐 species、composition、charge、mass/residual、volume 和 indicator 语义闭合；
- same flag merge 可组合不同 mixtures；
- different flag merge 必须拒绝；
- material/phase 跨界仍拒绝；
- compact arrays 后 chemical state 与 marker identity 同步；
- repeated split/merge 10,000 次逐 species long 不漂移；
- handle cap failure 不留下已移动位置、半个 destination 或已删 source。

## 21.5 persistence

- v1/v2 golden bytes、CRC 和 reader 不变；
- v2→v3 pure/material marker 体积和 raw mass 保持；
- v3 mixture table canonical order 与 runtime handle 分配顺序无关；
- section/manifest per-species、composition、charge、mass residual totals 回和；
- 坏 handle index、duplicate species、zero entry、unknown species、wrong fingerprint、wrong AU scale 拒绝；
- count/length 超预算在 allocate 前拒绝；
- 所有迁移 fault points 只恢复完整 v2 或 v3；
- gate off 读取 v3 只锁定/保留，不写 downgrade；
- unloaded→restore 保持 chemical state 并暂停。

## 21.6 container/WAL

- pure chemical water、单盐、两盐、酸、碱、已中和、酸过量、碱过量桶 codec round-trip；
- flagged/unflagged groups 共桶后比例不丢；
- 1 BV 与 partial extract/insert 逐项闭合；
- marker/pool budget 不足时 item/solver 均不变；
- material-only legacy bucket 路径保持；malformed dual representation 拒绝；
- tooltip 能看到所有 bounded species，不只显示数量；
- player、dispenser、hopper、drop/pickup、death、disconnect、creative 逐 component round-trip；
- WAL v1 pure/v2 material recovery 不回归；
- v3 每个 fault boundary 收敛；
- gate off、actor unload、registry mismatch、HEAD 其它 generation 保持 pending/locked，不删除。

## 21.7 indicator

- 100 BV 正好成功；超过 100 BV 全拒绝；
- 超限反馈为已确认下限，不伪报完整体积；
- 不跨 solid/cut-cell closed face/immiscible phase；
- 后加入液体不被标；
- split 继承，different-bit merge 拒绝；
- eraser 清除目标连通范围；安全预算不足全拒绝；
- checkpoint/restart/container/WAL 保持；
- indicator 对 species、charge、mass、volume 和 solver velocity 零影响。

## 21.8 network/client

- v1/v2/v3 offer/response matrix；缺 capability 可读拒绝；
- v1/v2 payload golden bytes 不变；
- v3 palette/count/field 长度攻击拒绝；
- v3 packet 不含 exact AU/residual；
- top-K/residual display 量化误差在 M7.0 上限内；
- pH/indicator byte 解码边界；
- generation/sequence/tombstone/reconnect/dimension switch 不复活旧状态；
- indicator=0 时 pH 色不污染外观；indicator=1 与中间 fraction 单调；
- phase 界面 geometry 与 v2 等价。

---

# 22. 性能与调度验收

M7 不负责解决现有 CPU backend 的总体 `REDUCED/PAUSED` 问题，但必须保证化学没有制造新的无界热点。

每次 milestone benchmark 记录：

```text
solver total step ms
chemical gather/plan/commit ms
dirty/total cell ratio
reaction evaluations
live handles / packed entries
intern hit rate
compaction count/ms
chemistry heap estimate
GC count/time
snapshot bytes
region-state bytes/checkpoint ms
container WAL bytes/fsync ms
REDUCED/PAUSED transitions and cause
```

必须分四个 fixture：

1. chemistry gate off；
2. gate on但纯水无 dirty chemistry；
3. 首批酸碱反应的典型小域；
4. hard-cap 前强搅拌域。

若典型小域频繁因 chemistry 进入 REDUCED/PAUSED：先 profile dirty-cell rebuild、intern/compaction、exact apportion 和 snapshot；不修改通用 `40 ms / 200 ms` pacing 语义，不等待未来 GPU 自动解决。GPU 后端只有在以后真实承载 chemical inventory/reaction 后才可能降低这部分 CPU 成本。

---

# 23. 实机 smoke

自动门全部通过后，只请求用户验证两类自动化无法完整观察的行为。

## Smoke A — 中和与颜色

前置：materials、chemistry、worldBridge 按命令提示启用并重启；使用一个小 controller owned region。

步骤：

1. 倒入带通用指示剂的 HCl solution；
2. 记录酸性色和 `/realwater chemstatus`；
3. 倒入等量 NaOH；
4. 观察颜色向 neutral 变化；
5. 再加少量 HCl，观察酸过量；
6. 离开使 region unload，返回并重启世界；
7. 比较 generation、逐 species ledger、pH 和 marked BV。

通过：视觉连续、状态账本完全符合自动 reference、重启无跳色/丢 species。

## Smoke B — 指示剂 parcel 语义和容器

1. 两个相连、合计小于 100 BV 的液体部分中，只对当前连通体添加指示剂；
2. 后倒入未标同相液体；
3. 观察新液体稀释可见标记比例但不瞬间全染；
4. 用桶抽取混合 parcel，确认 tooltip 同时列 species、pH 和指示剂状态；
5. 通过玩家与 dispenser 各完成一次倒入/抽取；
6. 使用 eraser；
7. 重启检查 item 与 region。

真实强制崩溃只在自动 fault injection 无法代表 Minecraft actor durable-save 边界时做一次，且使用备份世界；不重演 M6 全套 ownership smoke。

---

# 24. 立即停止并回到设计的信号

出现任一项，不继续叠补丁：

- 每 marker 出现 Java `Map` 或无界对象链；
- reaction 结果依赖 hash iteration、definition 文件顺序或 marker 遍历顺序；
- 为处理两条竞争 reaction 引入隐藏 priority；
- 小量 species 被当误差删除；
- pool 满后继续分配、覆盖旧 handle 或只保留主成分；
- reaction 修改了 `particleVolume` 却没有显式 pressure volume source；
- `particleMass` 与 species mass 不一致但没有 residual 账本；
- marker split/merge 后只能靠全域“重新估算”species；
- flagged 与 unflagged marker 被 merge 成一个 bool；
- chemistry 只存在于 renderer/snapshot，不在 region state；
- v3 被 chemistry-off 代码写回 v2；
- `/reload` 改变已有 species 的含义；
- chemical item 可被 legacy reader 当普通材料桶结算；
- WAL 恢复需要从 tooltip、颜色或当前 item 外观猜状态；
- protocol v3 发送 exact 区域 AU 库存；
- pH 被持久化后与实际离子不一致；
- 隔墙、相邻格或不可互溶界面发生 reaction；
- 为降低化学开销放宽通用 PAUSED 阈值；
- 把气相化学、沉淀、温度或 GPU 后端混入首批强电解质提交。

---

# 25. 文档与路由关闭要求

M7.0 通过后，把已经冻结的 numeric representation、registry 和 reaction deterministic restrictions 从本 proposal 提炼到正式 roadmap/architecture；不要让执行真值长期只存在于 archive。

M7 关闭时至少更新：

```text
docs/architecture/chemistry-state.md（新）
docs/architecture/fluid-solver.md
docs/architecture/m3-material-state-migration.md 或其后继格式文档
docs/architecture/world-ownership-and-containers.md
docs/maintenance/routing.md
docs/maintenance/next-session-initial-prompt.md
docs/progress/README.md
docs/progress/m7-conservative-chemistry.md（新）
docs/roadmap/product-roadmap.md
docs/roadmap/materials-and-chemistry.md
docs/roadmap/cross-cutting-contracts.md
README.md
```

职责分别是：当前行为、代码/最小门路由、完成证据、未来约束和用户入口。已验证事实从 proposal 移出后，本文件继续只作实施史。

---

# 26. M7 最终放行清单

只有全部为真才标记 M7 完成：

- [ ] M7.0 representation/codec/budget decision record 已通过；
- [ ] chemistry core 对首批盐与酸碱验收逐 species exact；
- [ ] 每步 volume/mass/species/composition/charge ledger 闭合；
- [ ] chemical step 是预算内 two-phase plan/commit；
- [ ] reseeding、compaction 和 transfer 不丢 indicator/species/residual；
- [ ] config v4 默认关闭且依赖 materials gate；
- [ ] immutable world registry snapshot 已实现；
- [ ] region-state v3 与 v2→v3 crash-safe migration 已通过；
- [ ] `ChemicalContents` 与 WAL v3 已通过所有 durable boundary；
- [ ] strict protocol v3/client ledger/tombstone 已通过；
- [ ] 颜色只在客户端派生；区域 snapshot 不含 exact AU；
- [ ] 100 BV indicator 添加与大连通 eraser 语义已通过；
- [ ] chemistry-off M0–M6 回归和 v1/v2 golden bytes 通过；
- [ ] 典型小域没有新增无界 REDUCED/PAUSED；
- [ ] EULA=true registration startup 与正常 `stop` 通过；需要 actor durable save 时再进入对应专项场景；
- [ ] 两个 M7 实机 smoke 通过，或明确证明无需人工观察；
- [ ] architecture/routing/progress/roadmap/README 已同步；
- [ ] clean build、版本、部署 JAR 与 SHA-256 已记录。

最终成功语义是：

> M7 的液相不再只是“某种颜色的材料”，而是携带可持久、可装桶、可恢复的显式 species 库存；强电解质和中和在同一数值混合边界内确定性发生；所有反应前后体积、质量、组成与电荷账本闭合；指示剂只追踪 parcel 可见性；旧格式和功能门关闭路径不会静默吞掉化学真值。
