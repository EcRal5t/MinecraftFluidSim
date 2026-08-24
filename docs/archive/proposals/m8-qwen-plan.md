# M9 设计蓝图：温度、相变与反应热

> 目标读者：执行 M9 实现的 AI/开发者。本文是**设计冻结草案 + 逐增量施工指南**。
> 基线：主 Mod 0.14.0（M8 已闭合），Minecraft 26.2 / Fabric，Java 25，CPU 权威求解。
> 前置阅读：根 `README.md`、`docs/architecture/fluid-solver.md`、`docs/architecture/granular-state.md`、
> `docs/architecture/chemistry-state.md`、`docs/roadmap/materials-and-chemistry.md`、`docs/maintenance/routing.md`。
> 完成后：正式冻结文档落在 `docs/architecture/thermal-state.md`，进度记录 `docs/progress/m9-thermal-phase-reaction.md`，
> 路由更新 `docs/maintenance/routing.md`。本文可归档。

---

## 0. 总原则（与项目既有惯例一致，不可违反）

1. **守恒优先**：内能是独立物理真值，温度只是派生量；绝热封闭域总能量必须闭合（容差内，见 §2.4）。
2. **门控退化**：热学门关闭时，活动 M0–M8 路径必须**逐位一致**（不分配任何热学数组、不进任何热学分支）。
3. **不回填**：热状态不得进入 M7 v3、M8 v5、气体预留 v4；region-state 使用独立完整 **v6**，v4 继续保持预留。
4. **不预建**：不做辐射、不做湍流对流模型、不做温度相关反应速率（Arrhenius）、不做蒸汽 species 化学、不做固体应力学。
5. **失败即暂停**：任何预算/表示/守恒失败 → 区域可读 PAUSED（仿 `blockGranular`），禁止猜测修复、禁止静默丢弃库存。
6. **显示只读**：客户端温度着色/相态图标只从量化展示快照派生，不反写物理。
7. **无经验补丁**：输运用标准扩散/焓法；出现异常先查物理离散，不加特异化钳制。
8. **日常只跑最近门**：每个增量只跑 routing 指定 selector；里程碑关闭才汇总。

---

## 1. 物理模型冻结

### 1.1 单位

- `TU` = 开尔文（K），温度。
- `EU` = 焦耳（J），能量。
- 沿用既有约定：`particleMass` 是"每 cell 体积单位的质量"，物理千克 = `mass × cellVolume_m³`（M8 力学已验证该约定）。
  因此 marker 存储的 `particleEnergy` 采用同一约定：**物理焦耳 = `particleEnergy × cellVolume_m³`**，
  即 `particleEnergy` 是"每 cell 体积单位的能量密度当量"。这样 `ΔE = m·c·ΔT` 在 marker 账本上直接成立，无需换算。
- 当前生产分辨率仍为 0.5 m solver cell（M8 SVQ 冻结线）；热学绑定同样强制 0.5 m，改变分辨率需独立迁移评审。

### 1.2 真值与派生量

| 类别 | 量 | 存储 |
|---|---|---|
| 真值 | 每个液体/冻结 marker 的 `particleEnergy`（raw `double`，可为负——参考态见 §1.3） | marker SoA 新增一列 |
| 真值 | 外部热能 source/sink 审计（raw `double`，稳定材料无关，按域累计） | 域字段，进 v6 manifest |
| 派生 | marker 温度 `T = invert(E, m, material, composition)` | 每步派生，不存储 |
| 派生 | 冻结分数 / 相态标签（液/固）、沸腾状态 | 从能量区间派生 |
| 派生 | 气腔温度（runtime-only，见 §1.7） | 不持久化 |
| 派生 | 展示温度场（量化 8-bit） | 只进 protocol |

选择 `double` 而非 `long` 量子的理由：能量与质量同属连续广延量，扩散每步产生任意实数通量；
项目对质量的既有裁决就是"raw double + 容差闭合"（`massClose` 模式），能量完全沿用，避免为扩散引入
largest-remainder 量子簿记的复杂度。**容差门与质量门同构**（§2.4）。

### 1.3 焓法（enthalpy method）与参考态

每个材料定义一条分段比焓曲线 `h(T)`，marker 温度由 `E/m = h(T)` 反解：

```text
T < T_m        : h = c_solid · (T − T_ref)
T = T_m 平台   : h = c_solid·(T_m − T_ref) + φ·L_f        φ∈[0,1] 熔化分数
T_m < T < T_b  : h = c_solid·(T_m − T_ref) + L_f + c_liquid·(T − T_m)
T = T_b 平台   : … + c_liquid·(T_b − T_m) + ψ·L_v         ψ∈[0,1] 汽化分数（仅用于触发，见 §1.6）
```

- `T_ref` 全局冻结为 **273.15 K**（所有材料统一，保证跨材料换热时能量账本只有一个参考系）。
- 负能量合法（冰低于 0 °C 时 `E < 0`），`double` 容差与符号无关。
- 反解是闭式分段线性，禁止迭代求根。
- 溶液（M7）有效热容 = 组分质量加权比热（§1.5），相变参数只取载体材料（溶剂）值；溶质只改变热容，不改变
  凝固/沸腾点（首版不做凝固点降低——若未来要做，是独立评审项，不得顺手加）。

### 1.4 热输运

1. **平流**：能量随 marker 移动，天然守恒，零成本。冻结（固相）marker 不平流（§1.6）。
2. **传导**（核心数值件）：
   - P2G：`cellEnergy[]`、`cellHeatCapacityMass[]` 按 containing-cell 精确累加（与 `exactCellMarkerVolume` 同构，
     `double` 数组）；冻结 marker、M8 deposit、active parcel 一并入账（§1.8）。
   - 网格扩散采用**对称成对半隐式面通量**（与 M8 Darcy 阻尼同构，无条件稳定、结构性守恒）：
     ```text
     Q_face = dt · k_face · A_face / h · (T_a − T_b)
     u*_face 形式：T_a' = T_a − Q/(m_a c_a)，T_b' = T_b + Q/(m_b c_b)
     k_face = 2 k_a k_b / (k_a + k_b)   （harmonic closure，与 M8 渗透率一致）
     ```
     通量上限钳制为"两侧可用焓差的一半"（防止显式过冲产生负绝对温度）；该钳制是物理可用量约束，不是调参。
   - 每个 logical step 至多 `MAX_THERMAL_SUBSTEPS = 4` 个扩散子步；超过即按 4 步提交并在诊断计数。
   - G2P：cell 能量变化 `ΔE_cell` 按各 marker 占该格热容的比例分摊回 marker（largest-remainder 末项取余，
     保证分摊精确回和到 `ΔE_cell`）。
3. **固体边界换热**：见 §1.9。
4. 禁止用平滑温度场决定相变归属；相变只看 marker 自身能量。

### 1.5 化学与颗粒的热容

- `ThermalRegistry`（§3.1）按稳定 species ID 声明 `specificHeatJPerKgK`（可缺省 → 取其所在液体材料载体热容）。
- 溶液 marker 有效 `m·c` = Σ(species 质量 × species 比热) + 非化学载体部分；该值每步从当前 mixture 派生，不存储。
- M8 颗粒材料在热注册表中声明 `c`、`k`：active parcel 按自身能量与所在格交换（牛顿弛豫，守恒成对），
  deposit 直接进格热容。颗粒不参与相变（M9 范围外，显式声明）。

### 1.6 相变

**凝固/熔化（液↔固，同材料）**：
- 能量落在 `T_m` 平台且 `φ` 达到 1/0 边界时翻转相态标签。固相 marker：
  - 速度置零且不进 P2G 动量、不参与 G2P 速度、不被平流/碰撞扫掠（与"静止沉积"同级的低成本表示，无应力学）；
  - 仍占体积、仍进 `markerCount`（压力流体格）、仍进传导账本；
  - 冻结域内同格冻结 marker 之间允许 reseeding merge（同材料同相），不跨相 merge。
- 密度变化：`ρ_solid` 独立声明（水→冰 917）。相态翻转或温度变化引起的体积变化走 §1.10 显式体积源。
- 凝固排盐：首版不做（溶液冻结时溶质保留在剩余液相——若整格全冻，溶质随固相 marker 的 mixture 冻结保存，
  熔化后原样恢复；species/组成账本在相变中**逐位不变**）。

**沸腾/冷凝（液↔气）**：
- M5 气体是 runtime-only 环境储层语义（`sealed+vented==drawn`），M9 保持一致：
  - **沸腾**：`ψ` 达 1 的 marker 只在**接触自由面或密闭气腔**时汽化；整份移除该 marker（体积+质量+能量），
    质量与能量记入域 `escapedSink` 审计（新增热能维度），释放的体积自然成为气腔 `V_avail`。
    内部（无自由面邻接）过热 marker 不汽化，只维持 `T ≥ T_b`——与"沸腾需要界面"的物理一致，也避免
    凭空在液体内部造气腔。
  - **冷凝**：气腔在界面格按牛顿换热被冷却到露点（首版露点 = `T_b`）以下时，从环境储层"draw"生成
    液体 marker（材料 = 该气腔邻界面液的载体材料；无液体邻接则不冷凝），生成量受气腔体积与
    `MAX_PHASE_OPS` 限制，质量/能量记入 `externalSource` 审计。
- 这套语义与 M5"环境是无限储层、重启重建环境平衡"完全一致：重启后蒸汽质量不恢复，审计账本保留痕迹。
  完整蒸汽 species 守恒是未来里程碑，不在 M9。

### 1.7 气体压缩/膨胀温变（runtime-only）

- 每个密闭腔新增 runtime `cavityTemperature`（double），重启/新建一律重建为环境温度 `T_ambient`（默认 293.15 K）。
- 体积变化时按绝热不变量更新：`T' = T · (V/V')^(γ−1)`，`γ = 1.4` 冻结，钳制在 `[150, 1500]` K（数值窗口，同 M5 钳制哲学）。
- 腔压修正：`p_gas` 在等温 EoS 结果上乘 `T/T_ambient`（理想气体），进入既有 `p_interface` 路径。
- 腔与界面液按成对牛顿换热交换能量，腔侧能量是 runtime double，不进任何账本持久化。

### 1.8 与 M8 颗粒的耦合

- 只换热，不改动任何 M8 守恒账本：SVQ、质量、species、composition 在热学路径中逐位不变。
- 热学不唤醒 sleeping deposit；热应力、热致侵蚀不实现。
- `granularReadyForHydrostaticSleep` 扩展为同时要求热静止（§1.11）。

### 1.9 世界固体边界换热

- 每种方块按"边界温度 + 换热系数"分类，内建表（数据包可扩展，见 §3.2）：
  - 普通方块：`T_ambient`（293.15 K），低换热系数；
  - 岩浆块/火/岩浆源相邻：高温源（如 1300 K），高换热系数；
  - 冰/浮冰/蓝冰：273.15 K 冷源；
  - 其他默认环境温度。
- 换热只发生在域边界 cut-face（`cutCells` 开口面且邻方块非空气）；通量 `Q = h_c·A·(T_block − T_cell)·dt`，
  同样受可用焓钳制。
- **所有跨界能量记入外部热能 source/sink 审计**（环境温度块也记——账本必须完整，净效果在平衡时为零）。

### 1.10 热膨胀与相变体积源

- 每 logical step 温度更新后，每个液态 marker 的目标体积 `V_target = m / ρ(T, composition)`
  （`ρ` 从材料参考密度 + 线性膨胀系数 `β` 派生：`ρ(T) = ρ_ref·(1 − β·(T − T_ref))`，`β` 可缺省为 0）。
- 实际体积变化按**每步至多 1% 相对变化**有界推进（剩余留到下步），避免压力系统被单步大源项击穿；
  该体积变化就是显式源项——`particleVolume` 直接改变，既有 `exactCellMarkerVolume`/压力/drift 路径自动消费，
  不新增第二套体积场（遵守 `materials-and-chemistry.md` 第 17 行）。
- 相变体积差（冰膨胀等）在相态翻转步一次性按同一 1% 有界通道推进。
- 冻结的固相 marker 同样按 `ρ_solid` 走该通道。

### 1.11 热休眠（与速度休眠分离）

- 域级热静止判据（全部满足且连续 `HYDROSTATIC_QUIET_STEPS` 步）：
  1. 每步最大 |ΔT|（全格）< `1e-4 K`；
  2. 无相变发生（凝固/熔化/汽化/冷凝计数为 0）；
  3. 边界热通量绝对值 < 阈值（即与外界也达到平衡）；
  4. 既有速度静止判据全部满足（含 `granularReadyForHydrostaticSleep`）。
- 速度静止但热不静止的域进入新增生命周期档位 `THERMAL_ACTIVE`：只跑热扩散子循环（不平流、不压力投影，
  marker 静止），成本与活动格数成正比；热静止后才允许进入既有静水休眠。
- 唤醒：边界方块温度变化（岩浆流入/火烧等世界事件）、容器注入不同温度液体、命令注入 → 立即退出热静止。

---

## 2. 数据结构与账本

### 2.1 marker 侧

`FlipFluidDomain` 新增（仅热学门开启时分配）：
- `double[] particleEnergy`（随 marker SoA，reseeding merge/split 按质量比例拆分能量并末项取余，守恒闭合）；
- `byte[] particleThermalFlags`（bit0 = 固相冻结；其余保留）；
- `double[] cellEnergy, cellHeatMass`（每格，P2G 用，`ensureGrid` 时清零重建）；
- `double[] cavityTemperature`（挂在 `AirNetwork` 节点，runtime）。

### 2.2 域审计

- `double thermalExternalSource, thermalExternalSink`（累计物理焦耳当量，同 §1.2 约定存"每 cell 体积单位"）；
- 相变内部转移**不记**外部审计（与 M8 内部相转移一致），但记诊断计数。

### 2.3 材料/定义侧

`ThermalMaterialDefinition`（稳定 ID = 液体材料稳定 ID）：
`specificHeatLiquid`、`specificHeatSolid`（可缺省）、`meltingPointK`/`latentHeatFusionJPerKg`（成对出现或成对缺省）、
`boilingPointK`/`latentHeatVaporJPerKg`（同上）、`solidDensity`（可缺省=液相密度）、`thermalExpansionBeta`（可缺省 0）、
`thermalConductivity`。全部十进制字符串解析（沿用 `GranularDefinitionCodec` 严格风格）。

### 2.4 闭合门（每个提交前后验证）

```text
Σ marker E + 边界累计注入 − 累计抽出   容差内相等（massClose 同构：max(1e-9, 8·ulp)）
Σ marker mass、Σ species、Σ SVQ        逐位不变（热学路径不得触碰）
Σ marker volume                        与显式体积源账本一致（膨胀量可回和）
相变计数 × 单份能量                     与平台焓差一致
```

任一失败 → `blockThermal(reason)` → 区域 PAUSED，当前 plan 不提交，之前已提交的格保留为真值（与 M8.6 语义一致）。

---

## 3. 注册表、配置与跨层格式

### 3.1 `ThermalRegistryStore`（世界权威快照）

完全复刻 `GranularRegistryStore` 模式：
- 路径 `<world>/realwater/thermal/registry.nbt`，FORMAT_VERSION 1，atomic 首发 + 读回校验 + 未知文件拒绝；
- 内容：液体材料热参数、species 比热、M7 内建反应 ΔH 表（按稳定规则名）、M8 `solid_liquid_reactions` ΔH 表
  （按稳定规则 ID）、边界方块温度表版本；
- canonical bytes + SHA-256 fingerprint；**不改动** `ChemicalRegistry` canonical 编码（避免 M7 世界指纹漂移）；
- 首次发布依赖：`materialsEnabled` 且 datapack 定义合法；与已存在 v6 区域冲突时锁定（同 M8 模式）。

### 3.2 数据包路径

```text
data/<namespace>/realwater/thermal_materials/*.json      （文件名=液体材料稳定 ID 尾段）
data/<namespace>/realwater/thermal_species/*.json        （可选；缺省取载体热容）
data/<namespace>/realwater/reaction_enthalpies/*.json    （稳定规则名 → ΔH，exact rational 字符串，J per extent）
```

loader 复用 M7/M8 的有界 exact-rational 语义；拒绝未知引用、负热容、缺失成对参数、溢出。

### 3.3 server config v6

- 新增 `thermalEnabled`（要求 `materialsEnabled`）、`phaseChangeEnabled`（要求 `thermalEnabled`）；
- 反应热不单独设门：`thermalEnabled && chemistryEnabled` 时自动生效（ΔH 缺失的规则按 0 处理并告警一次）；
- `maximumThermalSubsteps`（默认 4，上限 8）；
- v1–v5 reader 强制两个新门关闭；依赖链在构造器、重启命令、启动装配三处一致（复刻 M8 做法）。

### 3.4 region-state v6

- 完整代次 = material + optional chemistry + granular + **thermal**；reader 精确集合 `{1,2,3,5,6}`，v4 维持拒绝；
- 新增 typed extension：
  - manifest：`realwater:thermal_manifest_v1` —— 能力位、热注册表 fingerprint、总能量 bits、source/sink 审计、
    逐材料能量账本、逐 section 能量账本（三层复核，复刻 M8 颗粒 manifest 结构）；
  - section：`realwater:thermal_section_v1` —— 每 marker 能量 bits（按 ordinal 对齐既有 section marker 数组）
    + 固相标志位图；
- **相态不单独持久化**：固相标志必须写入（它影响平流语义，重启后不能靠温度猜测）；
- v1/v2/v3/v5 → v6 迁移：在 inactive slot 生成完整新代次，所有既有 marker 能量按 `T_ambient = 293.15 K`
  初始化（`E = m·c_liquid·(T_ambient − T_ref)`，无相变平台），读回验证后才切 HEAD；旧 canonical bytes 不变；
- admission：v6 要求热学门 + 世界热注册表 fingerprint 匹配；缺失/失配 → 保留并 LOCKED。

### 3.5 protocol v5（展示）

- 严格协商：`CAP_THERMAL_SNAPSHOT`，要求 material capability；chemistry/granular 层沿用 v4 的内嵌模式
  （v5 payload 内嵌 v2 material 几何 + 可选 v3 化学层 + v4 颗粒层，逐层版本不变）；
- 新增展示字段：逐格 8-bit 量化温度（调色板固定冷蓝→白→炽橙三段，服务端只发量化值）、
  逐格固相标志位图、沸腾界面标志；**不发**精确能量、焓、审计；
- 客户端：液体表面按温度着色（叠加在材料色上）、冻结格半透明白、沸腾面微闪；全部只读。

### 3.6 容器与 WAL（最后一个增量，可整体延期而不破坏前序）

- 液体容器物品新增可选组件 `thermal_contents`（能量 bits + 派生温度仅展示）；与 `granular_contents` 互斥，
  与液体组件**可组合**（热是液体的属性）；空桶/空颗粒容器不携带；
- `FluidMixture`/`ChemicalContents` 编码不变，热信息独立组件，避免动 M6/M7 已冻结编码；
- container-transfer WAL v5：新增可选 `ThermalEnergyBits` 字段（`FLUID_NANOS` 专属；`GRANULAR_SVQ` 拒绝携带），
  shape 校验核对 before/after 能量差 == 转移液体能量；v1–v4 reader/recovery 不变；
- tooltip 显示温度（只读）；命令 `/realwater heatbucket <kelvin>` 生成显式 creative/debug 热源容器。

---

## 4. 每步顺序（热学接入后的完整 logical step）

```text
每个 CFL substep：
  rebuildGridFromParticles（含 cellEnergy/cellHeatMass P2G）
  → 气体网络（含腔温绝热更新）
  → M8 颗粒力学/碰撞/压力（既有顺序逐位不变）
  → 液体压力/投影/G2P/advect/commit（既有）
substep 结束后（每 logical step 各一次，顺序冻结）：
  1. M7 aqueous reaction（既有）
  2. M8 solid-liquid phase transfer（既有）→ 其 ΔH 释放/吸收进当格能量（§5）
  3. 热扩散子循环（≤4 子步：网格成对通量 + 边界换热 + 颗粒换热）
  4. 反应热与 M7/M8 已提交转移的能量结算（与 2 同事务，见下）
  5. 相变判定与执行（凝固/熔化 → 沸腾/冷凝；每次均 plan/commit + §2.4 验证）
  6. 热膨胀体积源（有界 1% 通道）
  7. M8 deposit/relax/wake（既有）
  8. reseeding（merge/split 同时拆分能量）
  9. 最终账本重建与闭合验证
```

注意：反应热在**反应提交事务内**同步结算能量（同 commit 的一部分），不允许"先反应后补能量"的中间态暴露；
若能量结算失败，整个反应 plan 拒绝（与 M8 相转移同构）。

---

## 5. 反应热细则

- ΔH 单位：J per reaction extent（M7 extent = 化学计量一次；M8 extent = `solidVolumeSvqPerExtent` 对应一次），
  exact rational 存储，负值 = 放热。
- 释放目标：反应发生格的液体 marker，按质量加权分摊（largest-remainder 末项取余）；该格无液体 marker
  （M8 沉淀到空格场景不存在——沉淀必有载体；若出现）→ 该规则本步跳过并计数。
- M7 每步每格仍只运行一次反应；ΔH 不改变反应是否发生（首版无温度门控），只改变能量账本。
- 中和热等内建值写进 bundled `reaction_enthalpies`（示例：H+ + OH- → H2O，ΔH = −57.3 kJ/mol 换算到 AU 体系，
  数值按项目 AU 定义缩放，保持与物种 `massPerAu` 一致的量纲）。

---

## 6. 预算表（冻结值）

| 项目 | 上限 |
|---|---:|
| thermal materials / species overrides / enthalpy rules | 256 / 512 / 1024 |
| thermal substeps / logical step | 4（配置上限 8） |
| 每 logical step 相变操作（凝固+熔化+汽化+冷凝合计） | 65,536 |
| 每 logical step 边界换热面 | 域表面积（无额外分配，逐面遍历） |
| 单步单 marker 相对体积变化 | 1% |
| 腔温数值窗口 | [150, 1500] K |
| protocol 温度层增量 | ≤ 3 × cellCount 字节 + 固相位图 |

---

## 7. 增量计划与验收门（复刻 M8 节奏）

> 每个增量：先更新进度文档 → 实现 → 只跑最近门 → 提交。测试不无限扩张：全程只新增 **1 个 `thermal` selector**，
> 断言追加进同一入口；不新增 Gradle task。

### M9.0 设计门
- 将本文 §1–§6 冻结为 `docs/architecture/thermal-state.md`；
- 可删除隔离原型（不进 source set）验证：焓反解闭式、成对半隐式扩散的绝热闭合、凝固前沿 1D 解析对照、
  沸腾干涸质量/能量账本；
- 门：`runCpuValidation` 既有 13+1 域全绿（确认基线），原型结果记入进度文档。

### M9.1 热学 core（无 solver/无 Minecraft）
- `ThermalRegistry`、`ThermalDefinitionCodec`、`ThermalMaterialDefinition`、焓反解、预算与校验；
- `thermal` selector 首版：定义合法性、fingerprint canonical、焓反解往返、负能量/平台边界。

### M9.2 输运 core + solver 绑定
- `FlipFluidDomain.bindThermal`（0.5 m 强制、fingerprint 一次绑定）；`particleEnergy` SoA；
  P2G/G2P 能量、成对扩散、reseeding 能量拆分；
- 门：`thermal`（绝热两格混合终温解析值、四格环流能量逐位回和、门关闭逐位退化对照）；
  因触碰共享 grid 路径，追加 `free-surface` 复跑。

### M9.3 世界边界换热 + 审计
- 边界方块温度表、cut-face 换热、source/sink 审计、`/realwater thermalstatus`（管理员，服务端精确账本）；
- 门：`thermal`（岩浆块加热水槽升温曲线单调、平衡后净通量为零、审计回和）。

### M9.4 相变
- 凝固/熔化（固相标志、平流排除、密度体积源）、沸腾/冷凝（环境储层 + 审计）、`phaseChangeEnabled` 门；
- 门：`thermal`（1D 凝固前沿位置对照、冰浮起密度门、沸腾干涸后质量/能量全进 sink、过热内部不汽化）；
  因改压力流体格语义（冻结 marker），追加 `cut-cell` 复跑。

### M9.5 反应热
- M7/M8 ΔH 接入（事务内结算）、bundled 中和热/沉淀热；
- 门：`thermal,chemistry,granular`（中和升温与解析焓一致、沉淀放热闭合、无化学门时逐位退化）。

### M9.6 持久化：config v6 + region-state v6
- `ThermalRegistryStore` 首发/锁定、v5→v6 原子迁移、三层账本、admission；
- 门：`region-state`（round-trip、迁移、缺注册表锁定、v4 仍拒绝、门关闭保留 v5 不升级）。

### M9.7 protocol v5 + 客户端
- 协商、量化温度层、固相/沸腾展示、客户端着色（只读）；
- 门：`protocol,thermal`（v4 客户端不受影响、512 KiB 包体门、乱序/指纹拒绝）。

### M9.8 容器热账本 + WAL v5（可延期项）
- `thermal_contents` 组件、WAL v5、`heatbucket` 命令；
- 门：`containers,thermal`（热液体注入/舀取能量闭合、v1–v4 WAL 不变）。

### M9.9 热休眠 + 空门性能
- `THERMAL_ACTIVE` 生命周期档、热静止判据、空热学门 benchmark（冻结 5% 或 0.5 ms 包络，不越 40 ms pacing）；
- 门：`thermal` + milestone 空门基准。

### M9.10 候选发布 + 实机验收
- `clean assemble`、`:runServer`、JAR 部署（同 M8.10 清单）；
- 人工门（自动门不可观察项收敛为一次实机）：真实 controller 内岩浆块加热→沸腾可见、水结冰体积膨胀、
  中和升温显示、重启后 v6 恢复、热容器转移。

---

## 8. 必须更新的文档/路由（里程碑关闭时）

1. `docs/architecture/thermal-state.md`（M9.0 冻结，随增量修订）；
2. `docs/progress/m9-thermal-phase-reaction.md`（每增量记录门与耗时）；
3. `docs/maintenance/routing.md`：新增 M9 三行（定义/注册表、region-state v6、protocol/诊断），
   `thermal` selector 进门表；`FluidServerConfig` 行升 v6；WAL 行升 v5；
4. `docs/roadmap/product-roadmap.md`：M9 段落改为已完成（关闭时），"接下来三项"前移；
5. `docs/architecture/fluid-solver.md`：状态边界加热学真值一段；
6. `README.md` 与 `version-history.md`（0.15.0）。

## 9. 明确不做（防止范围漂移）

- 温度相关反应速率、溶解度温度依赖、凝固点降低；
- 蒸汽 species 守恒、气相化学、湿度；
- 辐射传热、对流子网格模型；
- 固相应力/断裂/蠕变（冻结液体只是"不动的 marker"）；
- M8 颗粒相变、热致侵蚀、热唤醒；
- GPU 路径（probe 不消费热状态）；
- 40/200 ms pacing 与自动暂停阈值改动。

## 10. 风险登记

| 风险 | 缓解 |
|---|---|
| 扩散+相变触碰共享压力路径引起旧域回退 | 每增量按 §7 追加受影响 selector 复跑；门关闭逐位退化对照从 M9.2 起常驻 |
| 能量 double 容差在大量换热后漂移 | 容差门与质量门同构；审计账本独立回和；超差即 PAUSED 不掩盖 |
| 沸腾移除 marker 与 reseeding/化学指针交互 | 相变在 reseeding 之前执行（§4 顺序冻结），移除走既有 swap-remove + 化学 handle 释放路径 |
| 世界热注册表与既有化学指纹耦合 | 热注册表独立存储，绝不改 `ChemicalRegistry` canonical 编码 |
| 冰膨胀顶破容器域 | 体积源走 1% 有界通道 + 既有满域拒绝语义；密闭满域冻结直接 PAUSED（可读失败） |
