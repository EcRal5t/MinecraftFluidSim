# M7 推行计划书（DeepSeek）

本计划用于指导另一个 AI 从 M6Close（`4822e28`）出发完成 M7 守恒化学。只写本项目专属决策、文件落点、API 形状、校验门与关闭条件；通用工程共识不重复。

## 0. 基线与范围

- 基线：`4822e28`（M6Close，主 Mod `0.12.0`）。
- 活动核心：`src/main/java/com/example/fluid/FlipFluidDomain.java`。禁止切回 `FluidDomain.java`。
- M7 关闭线：**species/reaction codec + 稀疏 mixture 库存 + 强电解质解离 + 同格计量反应 + 游戏化 pH + 指示剂 parcel 标记**，全部 CPU-only、runtime-only。
- M7 不做：
  - 不做格间化学输运/扩散/对流（mixture 不随 marker 平流）。
  - 不做有限溶解度、弱酸/缓冲液、活度、动力学、完整化学平衡。
  - 不做 protocol v3、`region-state v3`、客户端 pH/指示剂渲染、任何持久化。
  - 不修改 `FlipParticleState` / `FlipMaterialParticleState` 的既有纯水/v2 交接格式，不修改 `region-state v1/v2` canonical bytes。
  - 不把颜色当作组分真值；颜色字段只作 display data。

M7 是 runtime-only，和 M5 气体网络一样：不写存档、不进 wire、不改变 `markerCount` 压力相定义。

## 1. 关键架构决策

1. **化学物质量用整数纳 AU 记账**。定义 `1 AU = 1_000_000_000` quanta。`SparseMixture` 内所有 species 量都是 `long` quanta；加减、反应计量都用 `Math.addExact` / 限制性整数运算，不引入 double 找零。这样 `NaNO3+KCl` 与 `NaCl+KNO3` 的离子库存可以逐 quantum 相等。
2. **质量是独立 double 真值**。每个 species 声明 `massPerAmount`（MU/AU，正有限）。`SparseMixture.mass()` 按 `amountQuanta * massPerAmount / 1_000_000_000.0` 求和；反应质量守恒用声明值校验，不按元素反推。marker `particleMass` 仍是流体力学质量真值；M7 注入化学物质时同步调整 marker 质量，使 `sum(particleMass)` 与 cell mixture mass 一致。
3. **cell-local mixture，不做 per-marker map**。`FlipFluidDomain` 内维护一个 `CellChemistryState`，只有被化学注入碰过的格才有稀疏 mixture。marker 不携带 species map，继续只携带 material/phase/volume/mass。
4. **M7 只允许纯水相（aqueous）格参与反应**。`bindChemistry` 要求 `materialRegistry == null` 且 `hasNonDefaultMaterialState() == false`。这样化学不会与 M3 多材料界面发生歧义。
5. **反应瞬时完成**。M7 没有反应速率：注入物种后立即解离并反复运行不可逆反应直到耗尽或到达硬上限。没有动力学，因此 `step()` 内不需要逐子步反应 pass；`step()` 仍只负责流体。pH 是查询态。
6. **体积不参与反应**。M7 不产生体积源，`particleVolume` 精确和不受化学影响；未来膨胀/收缩再显式进入压力步。
7. **指示剂是 marker 上的 runtime-only 二值标记**。存储在 `FlipFluidDomain` 的 `byte[] particleIndicatorFlag`，不进入 `FlipParticleState`/`FlipMaterialParticleState`/region-state/wire。reseeding merge/split 必须保留标记，见 §5。

## 2. 新文件落点

### 2.1 `fluid/chemistry` 包（纯 Java，不 import Minecraft）

在 `src/main/java/com/example/fluid/chemistry/` 新建：

| 文件 | 职责 |
|---|---|
| `FluidSpecies.java` | species 定义：id、元素组成、电荷、`massPerAmount`；canonical 排序；严格校验 |
| `FluidChemistryPhase.java` | 相定义：id、允许的 species 列表；M7 只用 `realwater:aqueous` |
| `FluidReaction.java` | 不可逆计量反应：id、phaseId、reactants、products；系数为正整数 |
| `FluidDissociationRule.java` | 强电解质完全解离：speciesId -> 产物系数 |
| `FluidChemistryRegistry.java` | 声明顺序分配进程内 species/phase handle；全量校验；硬上限 |
| `SparseMixture.java` | 不可变稀疏组分库存：`long[] amountQuanta`，按 species handle 索引；仅在有正量的格存在 |
| `FluidChemistry.java` | 纯化学内核：注入、解离、反应、质量/电荷/元素账本、pH |
| `FluidChemistryCodec.java` | 严格字符串 v1 codec：registry 与 mixture 的 encode/decode，raw bits 往返 |
| `FluidChemistryLimits.java` | 硬上限常量：物种数、元素数、反应数、mixture 最大物种数、每格最大 AU 等 |

### 2.2 `fluid` 包内改动

| 文件 | 改动 |
|---|---|
| `FlipFluidDomain.java` | 增加 `bindChemistry`、`tryInjectSpecies`、`cellMixture`、`cellPh`、`cellResidualQuanta`、indicator 标记与 flood fill；字段增长处同步新数组 |
| `FlipParticleReseeder.java` | `reseed` 增加 `byte[] particleIndicatorFlag` 参数；merge 取 OR，split 继承 |
| `FlipFluidDomain` 内部新 package-private 类 `CellChemistryState` | 维护 cell index -> `SparseMixture` + `long residualQuanta`；从 marker 质量惰性初始化纯水 H2O 基准；只允许 aqueous 格 |

### 2.3 测试与构建

| 文件 | 职责 |
|---|---|
| `src/test/java/com/example/fluid/chemistry/M7ChemistryValidation.java` | M7.0/M7.1 纯化学门：codec、loader 拒绝、离子等价、中和过量、随机守恒 |
| `src/test/java/com/example/fluid/M7ChemistrySolverValidation.java` | M7.2/M7.3 活动核心门：bind/注入/质量一致/pH/指示剂/纯水不退化 |
| `CpuValidationSuite` | 注册 `chemistry-core`、`chemistry-solver` selector；不新增 JavaExec task |

## 3. 数据模型与内置定义

### 3.1 `FluidSpecies`

```java
public record FluidSpecies(
        String id,
        java.util.Map<String,Integer> composition, // element key -> positive int count, sorted
        int charge,
        double massPerAmount
)
```

校验：
- `id` 用与 `FluidMaterial` 相同的 key 规则：`[a-z0-9_.-]+:[a-z0-9/._-]+`。
- `composition` 非空；element key 只允许 `[a-z][a-z0-9_.-]*`；count > 0；map 排序后 `Map.copyOf`。
- `charge` 是 `int`；`massPerAmount` 正有限。
- 提供 `long amountToQuanta(double au)` 只做显式 `Math.round` 并抛溢出；`double quantaToMass(long q)`。

### 3.2 `FluidChemistryPhase`

```java
public record FluidChemistryPhase(String id, java.util.List<String> speciesIds)
```

- `id` 与 miscibility group 同命名空间；M7 只允许 `realwater:aqueous`。
- `speciesIds` 非空、不重复、排序、所有 id 合法。

### 3.3 `FluidReaction` / `FluidDissociationRule`

```java
public record FluidReaction(
        String id,
        String phaseId,
        java.util.Map<String,Integer> reactants, // sorted species id -> positive int
        java.util.Map<String,Integer> products)
public record FluidDissociationRule(String speciesId, java.util.Map<String,Integer> products)
```

- reactants 与 products 的 key 集合不相交；products 非空。
- 系数只允许 `1..16`（硬编码 M7 范围）。
- 禁止自反应 `H+ + OH- -> H2O` 以外的可逆/双向语义；没有 `reversible` 字段。

### 3.4 `FluidChemistryRegistry` 校验顺序

构造时按以下顺序失败，错误信息包含具体 id：

1. 数量硬上限：`MAX_SPECIES=256`、`MAX_PHASES=64`、`MAX_REACTIONS=256`、`MAX_DISSOCIATION_RULES=256`。
2. id 唯一性（species、phase、reaction、dissociation 各自独立）。
3. phase 引用的 species 存在；reaction/dissociation 引用的 species 存在。
4. 每个 reaction 与 dissociation 的元素账本精确相等（integer 向量逐元素相等）。
5. 每个 reaction 与 dissociation 的电荷守恒：`sum(coef*charge) == 0`。
6. 质量守恒：`abs(sum(reactant coef*massPerAmount) - sum(product coef*massPerAmount)) <= 1e-9 * max(1, abs(reactantMass))`。`massPerAmount` 以 double 存储，不追求 bitwise；但元素与电荷是 integer 精确。
7. reaction 的 phaseId 存在，且所有 reactant/product 都在该 phase 的 `speciesIds` 中；dissociation 的 species 与其产物必须在同一 phase。
8. 强电解质解离只允许“分子盐 -> 离子”；禁止离子继续有 dissociation rule（避免递归歧义）。

Registry 暴露：
- `int speciesHandle(String id)` / `FluidSpecies species(int handle)` / `int size()`。
- `int phaseHandle(String id)` / `FluidChemistryPhase phase(int handle)`。
- `List<FluidReaction> reactions()`（按声明顺序，就是运行顺序）。
- `java.util.Map<Integer, FluidDissociationRule> dissociationBySpeciesHandle()`。

### 3.5 `SparseMixture`

不可变。内部 `long[] amountQuanta` 长度 = registry species count；只允许非负；总量不超过 `FluidChemistryLimits.MAX_TOTAL_QUANTA_PER_CELL`。

核心 API：
- `long amount(int speciesHandle)`
- `long totalQuanta()`
- `double mass(FluidChemistryRegistry registry)`：Kahan 求和，结果必须有限；溢出抛 `IllegalStateException`。
- `long charge(FluidChemistryRegistry registry)`：`Math.addExact` 累加 `amount * species.charge`。
- `long[] elementCounts(FluidChemistryRegistry registry)`：`Math.addExact` 累加 `amount * count`。
- `int speciesCount()`
- `String encode(FluidChemistryRegistry registry)` / 静态 `decode(...)`：格式 `v1;speciesHandle:quanta;...`，按 handle 升序，quanta 用十进制字符串，最大长度 `FluidChemistryLimits.MAX_ENCODED_LENGTH`。

### 3.6 `FluidChemistry` 内核

```java
public final class FluidChemistry {
    public FluidChemistry(FluidChemistryRegistry registry);

    /** 注入并立即完全解离、跑完不可逆反应；输入不可变，返回新 mixture。 */
    public SparseMixture addAmount(SparseMixture mixture, int speciesHandle, long amountQuanta);

    /** 对所有不可逆反应按 registry 声明顺序反复扫描，直到不动或 MAX_REACTION_PASSES。 */
    public SparseMixture runReactions(SparseMixture mixture);

    public double ph(SparseMixture mixture, double waterAmountAu, double volumeBv);
}
```

`addAmount` 内部顺序：
1. 复制 `amountQuanta` 数组；`amountQuanta <= 0` 抛 `IllegalArgumentException`；总量超硬上限抛 `IllegalStateException`。
2. 加上目标 species。
3. 解离：若该 species 有 `FluidDissociationRule`，把该 species 全量替换为产物 `amount * coef`。M7 只允许一层；若产物还有规则，在 registry 构造时已拒绝。
4. `runReactions`：最多 `MAX_REACTION_PASSES = 16` 轮；每轮按声明顺序扫所有 reaction；对每个 reaction 计算 `runs = min(amount(reactant)/coef)`，若 `runs > 0` 则减 reactant、加 product。全部使用 `Math.addExact` 和 checked subtract；一轮无变化则停止。若第 16 轮仍有变化则抛 `IllegalStateException`（M7 内置反应不会触发）。
5. 返回新 `SparseMixture`。

### 3.7 `FluidChemistryCodec`

严格字符串 v1，禁止多字段猜测。registry 格式：

```text
v1
species <count>
<id>,<charge>,<massBits>,<element>:<count>|...
...
phase <count>
<id>,<speciesId>|...
...
reaction <count>
<id>,<phaseId>,<reactantId>:<coef>|...,<productId>:<coef>|...
...
dissociation <count>
<speciesId>,<productId>:<coef>|...
...
```

- `massBits` 用 `Long.toUnsignedString(Double.doubleToRawLongBits(value), 16)`，解码用 `parseUnsignedLong`。
- 解码必须拒绝：未知前缀、缺行、重复 id、空行、长度超过 `FluidChemistryLimits.MAX_ENCODED_LENGTH`。
- 解码后构造 `FluidChemistryRegistry` 复验全部守恒，不单独信任文本。

## 4. 内置化学定义

新建 `src/main/java/com/example/fluid/chemistry/BuiltinFluidChemistry.java`（或者 `com.example.fluid.BuiltinFluidChemistry`，二选一，不要重复）。M7 使用抽象 AU，不宣称现实 mol；以下 `massPerAmount` 是游戏 MU/AU，且保证反应质量守恒：

| species id | charge | composition | massPerAmount |
|---|---|---|---|
| `realwater:water` | 0 | H:2, O:1 | 1.0 |
| `realwater:h_plus` | +1 | H:1 | 0.5 |
| `realwater:oh_minus` | -1 | O:1, H:1 | 0.5 |
| `realwater:cl_minus` | -1 | Cl:1 | 0.5 |
| `realwater:na_plus` | +1 | Na:1 | 0.5 |
| `realwater:k_plus` | +1 | K:1 | 0.5 |
| `realwater:no3_minus` | -1 | N:1, O:3 | 0.5 |
| `realwater:hcl` | 0 | H:1, Cl:1 | 1.0 |
| `realwater:naoh` | 0 | Na:1, O:1, H:1 | 1.0 |
| `realwater:nacl` | 0 | Na:1, Cl:1 | 1.0 |
| `realwater:kcl` | 0 | K:1, Cl:1 | 1.0 |
| `realwater:nano3` | 0 | Na:1, N:1, O:3 | 1.0 |
| `realwater:kno3` | 0 | K:1, N:1, O:3 | 1.0 |

Dissociation rules：
- `hcl -> h_plus + cl_minus`
- `naoh -> na_plus + oh_minus`
- `nacl -> na_plus + cl_minus`
- `kcl -> k_plus + cl_minus`
- `nano3 -> na_plus + no3_minus`
- `kno3 -> k_plus + no3_minus`

Reaction（`realwater:aqueous`）：
- `H+ + OH- -> H2O`，reactants `h_plus:1, oh_minus:1`，products `water:1`。

相：
- `realwater:aqueous` 包含上述全部 species。

验收点对应：
- 1 AU 的 `NaNO3 + KCl` 与 1 AU 的 `NaCl + KNO3` 注入纯水后，离子 species `na_plus, k_plus, no3_minus, cl_minus` 的 quanta 必须完全相等，且总质量、总电荷、各元素 count 一致。
- `2 HCl + 1 NaOH` 注入后：`h_plus` 剩余 1 AU、`oh_minus` 为 0、`cl_minus` 2 AU、`na_plus` 1 AU、反应生成的 `water` 1 AU（在 mixture 内，溶剂基准另计）。

## 5. `FlipFluidDomain` 集成（M7.2/M7.3）

### 5.1 新字段

```java
private FluidChemistry chemistry;          // null == 未绑定，纯水路径不变
private CellChemistryState cellChemistry;  // null == 未绑定
private byte[] particleIndicatorFlag = new byte[initialParticleCapacity];
```

`ensureParticleCapacity` 增长时同步 `Arrays.copyOf(particleIndicatorFlag, capacity)`；`removeParticle` 交换时同步交换 flag；`spawnParticle` 新 marker flag 置 0；`restoreParticleState`/`restoreMaterialParticleState` 恢复后全部 flag 置 0。

### 5.2 `bindChemistry`

```java
public void bindChemistry(FluidChemistryRegistry registry)
```

- `registry == null` 抛。
- `chemistry != null` 抛（禁止重复绑定）。
- `materialRegistry != null` 或 `hasNonDefaultMaterialState()` 抛：M7 化学只允许纯水区域。
- 构造 `FluidChemistry` 与 `CellChemistryState`。
- `CellChemistryState` 惰性初始化：未注入的格不持有 mixture。

### 5.3 `tryInjectSpecies`

```java
public ChemicalInjection tryInjectSpecies(int x, int y, int z, String speciesId, long amountQuanta)
```

返回 record：
```java
public record ChemicalInjection(boolean injected, long amountQuanta, double massDelta,
                                SparseMixture afterMixture, String failure) { }
```

执行顺序：
1. `chemistry == null` 或参数非法或 `amountQuanta <= 0` -> `injected=false`，`failure` 写原因。
2. `inside(x,y,z)`；`ensureGrid()`；`markerCount[cell] == 0` 拒绝。
3. 该格所有 marker 必须是 aqueous 纯水（`materialRegistry == null` 下 material handle 0，phase label 0 天然满足）。
4. 若 `cellChemistry` 没有该格 mixture：以 `exactCellMarkerMass[cell]` 初始化 H2O 基准：
   - `waterQuanta = Math.round(exactCellMarkerMass[cell] * QUANTA_PER_AU / waterMassPerAmount)`（massPerAmount=1.0，即 `mass * 1e9`）。
   - `residualQuanta = waterQuanta - exactCellMarkerMass[cell] * 1e9`（double 差取整到 long；注意这里不是找零而是记录溶剂量化残差）。
   - 若 `waterQuanta < 0` 抛。
5. `after = chemistry.addAmount(baseMixture, registry.speciesHandle(speciesId), amountQuanta)`。
6. `massDelta = after.mass(registry) - baseMixture.mass(registry)`。
7. 按体积比例把 `massDelta` 分配到该格全部 marker 的 `particleMass` 上（保持每个 marker 质量正有限；分配后 `sum(particleMass)` 与 `after.mass()` 的相对误差不超过 `1e-9`）。
8. 写回 `cellChemistry`；`gridDirty = true`；返回 `injected=true`。

注意：`tryInjectSpecies` 是 all-or-nothing；任何异常路径不得修改 marker 或 mixture。

### 5.4 pH 查询

```java
public double cellPh(int x, int y, int z)
```

- `chemistry == null` 或无 mixture -> `7.0`。
- 取 `h = mixture.amount(h_plus)`, `oh = mixture.amount(oh_minus)`, `water = mixture.amount(water)`（若 water 为 0 用 `cellChemistry.baseWaterQuanta`）。
- `net = (h - oh) / 1e9`；`ref = max(water, 1.0) / 1e9`；`ratio = net / ref`。
- `ph = 7.0 - log10(max(1e-14, ratio))`；若 `net < 0` 用 `7.0 + log10(max(1e-14, -ratio))`；手动 clamp `[0,14]`。纯水/未污染格返回 7.0。

### 5.5 指示剂 parcel 标记（M7.3）

`particleIndicatorFlag` 是 `byte`，0=未标记，1=通用指示剂。M7 只有一种指示剂，不引入多色标志。

- `public int addIndicator(int x, int y, int z)`：
  - `ensureGrid()`；种子格必须有 `markerCount>0` 且 phase 为 aqueous。
  - BFS 面相邻扩展：仅当邻居格 `markerCount>0`、`phaseLabel` 相同、非 solid，且共享 face 可通（直接复用 `faceActive` 使用的 `cutCells.xFaceOpen` 等，但这里只要两个格都非 solid 且 openArea>0）。
  - 累计 `exactCellMarkerVolume`；若累计 `> 800.0`（100 BV × 8 cell/BV）立即失败并返回 `-1`，不得部分标记。
  - 成功后把 visited 格内所有 marker 的 `particleIndicatorFlag` 置 1；返回标记 marker 数。
- `public int clearIndicator(int x, int y, int z)`：同样 BFS 连通域，置 0；返回清除 marker 数。
- 不修改 `particleVolume`、`particleMass`、`markerCount`、material/phase。

### 5.6 reseeding 保留标志

`FlipParticleReseeder.reseed` 增加 `byte[] particleIndicatorFlag` 参数（放在 `particlePhaseLabel` 之后）。规则：
- merge：`flags[target] = (byte)(flags[target] | flags[source])`。
- split：新粒子继承 `flags[source]`。
- compact/removeParticle 的交换已经在 `FlipFluidDomain` 侧同步完成，reseeder 内不负责 compaction。

这一改动属于共享 reseeding 契约变更，必须跑 `runCpuValidation -PcpuValidation=reseeding`。

## 6. Feature gate 与命令

M7 是未完成里程碑，按 cross-cutting 契约使用独立配置门 `chemistryEnabled`，默认关闭。

1. `FluidServerConfig` 升级 `FORMAT_VERSION = 4`，新增 `boolean chemistryEnabled`。
   - v1 读取：`materials=false`、`worldBridge=false`、`chemistry=false`。
   - v2 读取：`worldBridge=false`、`chemistry=false`。
   - v3 读取：`chemistry=false`。
   - v4 必须提供 `chemistryEnabled`；非法值 fail-closed。
   - `save` 总是写 v4。
   - 新增 `withChemistryEnabled(boolean)`。
2. `FluidSimulationService` 构造绑定 `chemistryFeatureEnabled = serverConfig.chemistryEnabled()`；新增 `chemistryFeatureEnabled()` 与 `setChemistryFeatureForRestart`（模式与 materials/worldbridge 完全一致）。
3. `RealWaterCommands` 增加：
   - `/realwater chemistry`：显示本次启动值 + 配置值。
   - `/realwater chemistry true|false`：仅原子保存下次重启值，当前会话不切换。
4. M7 关闭前不把 `chemistryEnabled` 接到控制器区域创建、demo 或桶入口；`bindChemistry` 只由测试和后续 M7 世界入口调用。关闭时已有区域继续纯水路径，不创建化学状态。

## 7. 测试与 Gradle 门

### 7.1 `M7ChemistryValidation`（默认门）

放在 `src/test/java/com/example/fluid/chemistry/M7ChemistryValidation.java`，纯 main，无 Minecraft 依赖。覆盖：

1. codec round-trip：built-in registry 与多个 `SparseMixture` 严格往返；非法文本拒绝。
2. registry 拒绝：重复 id、未知 species、反应电荷/元素/质量不守恒、phase 不允许、dissociation 递归。
3. 硬上限：超过 `MAX_SPECIES`、`MAX_SPECIES_PER_MIXTURE`、`MAX_TOTAL_QUANTA_PER_CELL` 的注入/构造被拒绝，不 OOM。
4. `NaNO3 + KCl` 与 `NaCl + KNO3`：注入等量后逐 species quanta 相等；总质量、总电荷、逐元素 count 相等。
5. `HCl + NaOH`：`2:1` 与 `1:2` 两组，验证限制反应物、过量物剩余、生成水正确；`1:1` 时 H+/OH- 归零，pH 为 7。
6. 随机守恒：固定种子，随机物种/量注入同一 mixture，每次 `addAmount` 后校验质量、电荷、元素 count 与“纯水基准 + 注入”理论值一致；至少 10,000 次。
7. `SparseMixture` 不可变与 encode/decode 排序。

在 `CpuValidationSuite` 注册 `chemistry-core` selector，复用唯一的 `runCpuValidation` Gradle task：

```groovy
gate(gates, "chemistry-core", () -> M7ChemistryValidation.main(NONE));
```

### 7.2 `M7ChemistrySolverValidation`（M7.2 起加）

放在 `src/test/java/com/example/fluid/M7ChemistrySolverValidation.java`。覆盖：

1. 未绑定化学时 `step()` 与固定纯水 fixture 完全一致（比较 volume、mass、pressure residual、marker count；原孤立 `FlipFluidDomainValidation` 已被分层活动门替代）。
2. `bindChemistry` 拒绝多材料/重复绑定。
3. `tryInjectSpecies` 注入 `1 AU HCl` 到静态水格：
   - 返回 `massDelta` 等于 HCl 的声明质量；
   - 注入后该格 `sum(particleMass)` 等于 mixture mass（容差 1e-9）；
   - `particleVolume` 总和不变；
   - 再次注入 `1 AU NaOH` 后 `cellPh == 7`、H+/OH- 为 0。
4. all-or-nothing：非法物种、空格、超量注入失败后 marker 与 mixture 不变。
5. 指示剂：BFS 连通域标记/清除；100 BV 上限拒绝且无部分标记；reseeding 后标记不丢失（构造过密格触发 merge，或欠密格触发 split，验证 OR/继承规则）。
6. 纯水 `exportParticleState` 在绑定化学后仍可导出且格式不变（证明未污染 v1 交接）。

在 `CpuValidationSuite` 注册 `chemistry-solver` selector，调用 `com.example.fluid.M7ChemistrySolverValidation`。

### 7.3 最小门选择

- 改 `fluid/chemistry/*` 或 `BuiltinFluidChemistry`：只跑 `runCpuValidation -PcpuValidation=chemistry-core`。
- 改 `FlipFluidDomain` 化学注入/指示剂/质量一致：跑 `runCpuValidation -PcpuValidation=chemistry-solver`。
- 一旦改 `FlipParticleReseeder`：合跑 `-PcpuValidation=chemistry-solver,reseeding`（共享契约）。
- 一旦改 `FluidServerConfig`/命令：按 routing 原则，在 M7.2 结束后合跑 `-PcpuValidation=chemistry-solver,region-state` 确认 v4 迁移与 fail-closed。日常不累计。
- 不跑 GPU、不跑 EULA server、不跑 5,000 步。

## 8. 实施顺序

### M7.0：纯化学数据与内核

1. 新建 `FluidSpecies`、`FluidChemistryPhase`、`FluidReaction`、`FluidDissociationRule`、`FluidChemistryLimits`。
2. 新建 `FluidChemistryRegistry` 及全部校验。
3. 新建 `SparseMixture`。
4. 新建 `FluidChemistry` 的 `addAmount`/`runReactions`。
5. 新建 `FluidChemistryCodec`。
6. 写 `BuiltinFluidChemistry`。
7. 写 `M7ChemistryValidation` 覆盖 7.1 中第 1–5、7 项（随机守恒可随后补）。
8. 注册 `chemistry-core` selector，跑绿。

### M7.1：内置化学验收

1. 补 `M7ChemistryValidation` 的随机守恒（第 6 项）。
2. 补 codec 对全部 built-in 的往返与非法文本表。
3. 跑 `runCpuValidation -PcpuValidation=chemistry-core`，记录输出与耗时。

### M7.2：活动核心 cell-local 注入

1. 在 `FlipFluidDomain` 加 `chemistry`/`cellChemistry` 字段与 `bindChemistry`。
2. 新建 `CellChemistryState`（package-private）。
3. 实现 `tryInjectSpecies` 与 `cellMixture`/`cellPh`/`cellResidualQuanta`。
4. 写 `M7ChemistrySolverValidation` 第 1–4 项。
5. 注册 `chemistry-solver` selector，跑绿。
6. 升级 `FluidServerConfig` v4 与 `FluidSimulationService`/`RealWaterCommands`；合跑 `-PcpuValidation=chemistry-solver,region-state` 确认 v1–v4 读取、保存与 fail-closed。

### M7.3：指示剂 parcel 标记

1. `FlipFluidDomain` 加 `particleIndicatorFlag` 生命周期维护。
2. `FlipParticleReseeder` 增加标志参数与 merge/split 规则。
3. 实现 `addIndicator`/`clearIndicator` BFS。
4. 补 `M7ChemistrySolverValidation` 第 5–6 项。
5. 跑 `runCpuValidation -PcpuValidation=chemistry-solver,reseeding`。

### M7 关闭

1. 更新 `docs/maintenance/routing.md`：新增 M7 定位与最小门。
2. 写 `docs/progress/m7-chemistry.md`：完成范围、自动门输出、已知限制（不输运、不持久化、不支持多材料相）。
3. 更新 `docs/progress/README.md` 一行。
4. 更新 `docs/roadmap/product-roadmap.md`：M7 标记已完成，保留 M8–M10；把“先冻结 species/reaction codec...”改为已完成摘要。
5. 若活动核心行为变化，更新 `docs/architecture/fluid-solver.md` 增加 M7 边界：化学 runtime-only、cell-local、`markerCount` 与 `particleVolume` 不变。
6. 版本号：建议 `0.13.0`；若 M7 只关闭纯化学门且无 gameplay 入口，也可先不升版本，按验收门决定。
7. 关闭前跑 `check` 与 `runCpuValidation -PcpuValidation=milestone`；M7 selectors 加入 milestone 短门。不需要 GPU、EULA server 或 `soak`，除非改动风险直接涉及对应长期稳定性。

## 9. 风险与回退

- **cell-local 化学与 marker 平流脱节**：M7 明确不输运；如果评审认为“同格计量反应”必须在动态网格中随 marker 走，则把 `CellChemistryState` 从 cell-keyed 改为 parcel-keyed（mixture handle 挂在 marker 上，reseeding 携带 handle）。这是 M7 最大的架构分叉，不应在实现中途切换。
- **H2O 基准量化残差**：`cellResidualQuanta` 只记录不丢弃；关闭 M7 时报告最大残差与触发格数。若残差在随机注入中超过 `1 quantum`/格，说明 H2O 基准量化方案需要改，不能静默继续。
- **reseeding 契约变更**：M7.3 改了 reseeder 签名，必须显式选择 `reseeding`；若该门变慢或失败，不得绕过。
- **配置门**：v4 迁移必须保持 v1/v2/v3 旧配置可读且 chemistry/worldBridge/materials 关闭规则不变；任何 fail-closed 放宽都违反 M3/M6 契约。

## 10. 完成清单（另一 AI 自查）

- [ ] `FluidChemistryRegistry` 构造对所有内置定义通过，元素/电荷整数守恒、质量 double 容差守恒。
- [ ] `M7ChemistryValidation` 中 `NaNO3+KCl` 与 `NaCl+KNO3` 离子库存逐 species 相等。
- [ ] `M7ChemistryValidation` 中 `HCl+NaOH` 两组过量正确，`1:1` pH=7。
- [ ] 随机守恒 10,000 次无质量/电荷/元素漂移。
- [ ] `FlipFluidDomain.bindChemistry` 拒绝多材料与重复绑定。
- [ ] `tryInjectSpecies` all-or-nothing；注入后 marker mass 与 mixture mass 一致；`particleVolume` 不变。
- [ ] 指示剂 flood fill 100 BV 拒绝且无部分标记；reseeding 后标记不丢。
- [ ] 纯水路径未绑定化学时 `exportParticleState`/`step` 行为不变。
- [ ] `FluidServerConfig` v1–v4 迁移门通过。
- [ ] `chemistry-core`、`chemistry-solver`、`reseeding`、`region-state` selectors 全绿。
- [ ] routing/progress/roadmap/fluid-solver 文档同步。
