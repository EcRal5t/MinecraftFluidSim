# M8 推行计划书（DeepSeek）：颗粒沉淀与低成本沉积

本文用于指导另一个 AI 从 M7Close（主 Mod 0.13.0）出发完成 M8。M8 的路线原文只有三行，因此本文先冻结设计门，再给出低成本模型选型、文件落点、API 形状、`FlipFluidDomain` 集成点、验证门、实施顺序与关闭条件。文档只写本项目专属决策；通用工程共识不重复。

## 0. 基线与范围

- 基线：M7Close，主 Mod `0.13.0`，Java 25，JDK 26.0.2 本机仅用于 Gradle。
- 活动核心：`src/main/java/com/example/fluid/FlipFluidDomain.java`。禁止切回 `FluidDomain.java`。
- M8 目标：数据驱动颗粒材料、浮力/阻力、孔隙和精确排水；安息角、沉积休眠、反应沉淀与局部唤醒。
- M8 首选模型：**Stokes 终端松弛 Lagrangian 颗粒 + Eulerian 沉积格 + 沉积斜坡门**。只有该模型无法通过守恒门与形态门时才评审 MPM/Drucker–Prager，不预建 MPM 框架。
- M8 不做：
  - 不做通用刚体、弹性固体、颗粒-实体双向碰撞。
  - 不做逐颗粒接触、支撑链、DEM。
  - 不做开放世界沙地、天然河床扫描。
  - 不把颗粒库存、沉积格、孔隙体积塞进现有 `region-state v1/v2/v3`、`protocol v1/v2/v3`、`container-transfer WAL v1/v2/v3`。
  - 不修改 M7 化学真值、`FluidMixture v1` 与 `FlipParticleState`/`FlipMaterialParticleState`/`FlipChemicalParticleState` 的既有字节语义。
  - 不改变 `markerCount > 0` 决定压力流体格这一条；颗粒不产生第二套压力相。

## 1. 必读文件

开工前按顺序读：

1. 根 `README.md`。
2. `docs/architecture/fluid-solver.md`（活动 CPU 求解器契约）。
3. `docs/architecture/world-ownership-and-containers.md`（M6 容器/WAL/ownership）。
4. `docs/architecture/region-state-v1.md`（checkpoint 原子提交）。
5. `docs/architecture/chemistry-state.md`（M7 v3 边界，尤其“不得把沉淀回填 v3”）。
6. `docs/maintenance/routing.md`（代码职责、最小门选择）。
7. `docs/roadmap/product-roadmap.md` 的 M8 与“接下来三项”。
8. `docs/roadmap/gases-and-granular.md` 的 M8 段。
9. 可选：`docs/progress/m5-air-network.md`（M5 runtime-only 瞬态 demo 模式，M8.0 照此办理）。

## 2. M8 分层结论

| 层 | M8 决策 |
|---|---|
| 物理真值 | 新增两套独立真值：自由颗粒账本（逐颗粒 volume/mass）与沉积格账本（逐格逐材料 solidVolume/mass，孔隙体积为派生量）。它们与液体 marker 体积、材料质量、化学 quanta 互不换算。 |
| 数值离散 | 颗粒不增加压力网格分辨率；压力格保持当前分辨率。颗粒固体体积以“有效开孔体积”进入 `freeSurfaceGeometry` 与 `volumeDriftDivergence`，不修改 cut-cell 面 aperture。 |
| 渲染重建 | M8.0 不做客户端颗粒渲染；用 `status`/transcript 验收。M8.2 再评审协议 v4 展示段。 |
| 调度策略 | 沉积格天然休眠（无粒子）；局部唤醒只由强流体扰动或反应触发，数量受预算，不因普通支撑变化全量粒子化。 |
| 世界事务 | M8.0 颗粒为 runtime-only，只允许瞬态开发域；控制器持久域拒绝 bindGranular 与颗粒转移，直到 M8.2 新格式评审。 |
| 协议/存档 | M8.0 不修改 protocol、region-state、WAL。未来版本预留：`region-state v4` 留给气体持久化，颗粒持久化用 `region-state v5`；protocol v4 可作颗粒展示；container WAL v4 可作含颗粒转移。 |

## 3. 低成本模型评审结论

### 3.1 候选模型

**A. Stokes 终端松弛颗粒 + Eulerian 沉积格 + 沉积斜坡门（首选）**

- 自由颗粒是 Lagrangian 点（代表球形颗粒 parcel），携带材料 handle、体积、质量、速度。
- 水中受力 = 重力 + 浮力 + Stokes 阻力，解析积分，无需逐颗粒邻居搜索。
- 颗粒速度用终端速度与松弛时间解析推进，无条件稳定。
- 沉积格是 Eulerian 网格上的稀疏 linked entry，保存材料、solidVolume、mass；孔隙体积由材料 packing fraction 派生。
- 安息角不靠接触力学，而靠“沉积斜坡门”：向某格添加颗粒后若柱高与四邻柱高差超过 `tan(安息角)`，该颗粒不沉积并沿最陡下降方向滚动。
- 局部唤醒从沉积格最表层按预算把 solidVolume 转回自由颗粒。

**B. MPM/Drucker–Prager（仅触发评审，不预建）**

仅在 A 无法通过第 9 节守恒/形态门时，做一个独立的最小 MPM/Drucker–Prager 验证（小型沙柱、单轴压缩、浸水坍塌），不修改生产路径。

### 3.2 选择理由

- A 的计算量随自由颗粒数和沉积格数增长，不引入颗粒接触或大矩阵。
- A 的体积账本全部精确：自由颗粒体积和、沉积格 solidVolume/mass 和分别闭合；水体积账本不因颗粒运动变化。
- A 的孔隙排水由“有效开孔体积”统一表达，避免 Godot 二维沙粒支撑链式经验公式。
- A 的形态（安息角）通过斜坡门产生，可用自动门测量；失败时能定位到斜坡门参数。

### 3.3 MPM 触发条件

满足以下任一条，进入 MPM 评审，不继续堆叠 A 的经验规则：

1. 沙堆安息角自动门连续 3 次无法落在目标角度 ±3° 内。
2. 浸水沙柱排水或水表上升门无法在 5,000 步内收敛到理论值 ±5%。
3. 密闭容器总体积门出现水或颗粒账本不守恒。
4. 局部唤醒门出现沉积体体积 + 自由颗粒体积不守恒。

## 4. 设计门冻结

### 4.1 颗粒材料数据契约

新文件 `src/main/java/com/example/granular/GranularMaterial.java`：

```java
public record GranularMaterial(
        String id,
        double referenceDensity,        // kg/m^3, > 0
        double grainDiameter,           // m, > 0
        double packingFraction,         // (0,1)，随机密排固体体积分数
        double angleOfReposeRadians     // (0, PI/2)
) {
    // id 与 FluidMaterial 相同的 stable key 规则
    public static GranularMaterial withReposeDegrees(String id, double referenceDensity,
            double grainDiameter, double packingFraction, double angleOfReposeDegrees);
}
```

校验：

- `id` 使用 `[a-z0-9_.-]+:[a-z0-9/._-]+`。
- `referenceDensity` 正有限。
- `grainDiameter` 正有限；首版不允许 > `0.05 m`（大于该值 Stokes 假设明显失效，拒绝而不是静默错误）。
- `packingFraction` 在 `(0.0, 1.0)` 且首版要求 `packingFraction <= 0.74`（最密球堆积上限）。
- `angleOfReposeRadians` 在 `(0.0, Math.PI / 2.0)`。

`GranularMaterialRegistry.java`：

- 按声明顺序分配 runtime handle；id 唯一。
- 提供 `size()`、`material(int handle)`、`handle(String id)`、`materials()`。
- 与 `FluidMaterialRegistry` 一样，runtime handle 永远不上链。

`BuiltinGranularMaterials.java` 首版内置：

| id | 密度 kg/m³ | 粒径 mm | packing | 安息角 |
|---|---|---:|---:|---:|---:|
| `realwater:fine_sand` | 2600 | 0.5 | 0.62 | 32° |
| `realwater:light_beads` | 500 | 0.1 | 0.60 | 20° |
| `realwater:precipitate` | 2800 | 0.01 | 0.50 | 25° |

首版只为测试提供数据；正式数据驱动 loader 放到 M8.1 末或 M8.2（见第 10 节）。

### 4.2 颗粒库存真值

自由颗粒与沉积格是两套独立真值，但颗粒可以原子地转换为沉积格（沉积）或从沉积格转回颗粒（唤醒）。

#### 4.2.1 单位

- 域内所有颗粒体积/沉积 solidVolume 一律使用 solver cell volume 单位（1 cell volume = `cellSize³`；`cellSize=0.5 m` 时为 `0.125 m³`）。
- 质量沿用现有 `particleMass` 的 solver mass 单位（`体积(域单位) * 密度(kg/m³)`）。
- 服务层进出容器时，体积用 `FluidTransferService.cellVolumeToNanos` / `nanosToCellVolumeAtMost` 换算；不得在域内新造第二套换算。

#### 4.2.2 自由颗粒账本

`GranularSolver` 内并行数组，禁止对象数组：

- `float[] grainX, grainY, grainZ`
- `float[] grainVelocityX, grainVelocityY, grainVelocityZ`
- `double[] grainVolume`（正有限，域单位）
- `double[] grainMass`（正有限，solver mass 单位）
- `int[] grainMaterial`（runtime handle）
- `int grainCount`

总账本：

- `totalGrainVolume = Σ grainVolume[i]`（double 精确和，稳定 ordinal 顺序）。
- `totalGrainMass = Σ grainMass[i]`（double 精确和）。
- 删除、沉积、唤醒必须 swap-remove 或显式移动，保持账本闭合。

#### 4.2.3 沉积格账本

`GranularDepositField` 稀疏 linked entry，禁止每格一个 `Map`/对象：

- `int[] head`（cell -> 首 entry 或 -1）
- `int[] material`
- `double[] solidVolume`（正有限）
- `double[] mass`（正有限）
- `int[] next`
- `int entryCount`
- `double[] cellSolidVolume`（缓存）
- `double[] cellPoreVolume`（派生缓存）

派生量：

- 单 entry：`poreVolume = solidVolume * (1 - φ)/φ`。
- 多 entry：`poreVolume = Σ_i solidVolume_i * (1 - φ_i)/φ_i`。
- `bulkVolume = solidVolume + poreVolume`。
- 孔隙体积是派生量，不是第三套可独立修改的账本；任何 solidVolume/mass 变更后必须重算。

### 4.3 浮力/阻力模型

首版采用 Stokes 阻力；方程用 SI 单位写出，代码在域单位与 SI 间只做一次显式换算。

对一颗颗粒：

- `V_m3 = grainVolume * cellSize³`
- `m_kg = grainMass * cellSize³`（因此质量账本与惯量一致，体积账本与浮力一致）
- `rho_f` = 所在格当前流体密度 `cellDensity`；不在流体内时用空气：`rho_f = 1.225 kg/m³`，`mu = 1.81e-5 Pa·s`
- `mu` = 流体动力黏度；首版固定为水 `1.002e-3 Pa·s`（配置文件可改，数据驱动黏度不提前做）
- `F_b = (m_kg - rho_f * V_m3) * g`
- `c = 3π mu d`
- `v_t = F_b / c`
- `tau = m_kg / c`

解析积分（terminal relaxation）：

```text
v_rel = v_grain - u_fluid
v_rel = v_rel * exp(-dt/tau) + v_t_vector * (1 - exp(-dt/tau))
v_grain = u_fluid + v_rel
```

其中 `v_t_vector` 只沿重力方向（Y 轴），其值为 `v_t`；水平方向相对速度按 `exp(-dt/tau)` 松弛。`tau <= 0` 或 `dt/tau` 过大的情况不用显式欧拉，直接钳制到终速。

补充规则：

- `rho_f > m_kg/V_m3` 时 `v_t < 0`，颗粒上浮；轻颗粒门只验证方向与稳态值，不要求加速过程逐帧物理。
- 空气中：`u_fluid = 0`，使用空气密度/黏度，不施加空气浮力之外的额外经验阻力。
- 颗粒速度硬上限：首版 `maxGrainSpeed = 5.0 m/s`；超过按该值缩放速度方向，避免单步跨格。
- 颗粒亚步：若 `tau < dt`，允许同一 `GranularSolver.step` 内最多 4 个亚步，保持终速解析；不允许缩小流体 `dt`。

### 4.4 孔隙与精确排水

#### 4.4.1 有效开孔体积

`FlipFluidDomain` 新增 `double[] granularSolidVolume`（域单位），由 `GranularSolver` 在每子步重建：

```text
granularSolidVolume[cell] = suspendedSolidVolume[cell] + depositedSolidVolume[cell]
effectiveOpenVolume(cell) = max(cutCells.openCellVolume(cell) - granularSolidVolume[cell], FRACTION_EPSILON)
```

当颗粒未绑定时，`granularSolidVolume` 全零，`effectiveOpenVolume == cutCells.openCellVolume`，既有路径逐位不变。

`effectiveOpenVolume` 用于：

1. `FlipFreeSurfaceGeometry.rebuild`：`liquidFraction = exactCellVolume / effectiveOpenVolume`（仍钳制到 `[0,1]`）。
2. `FlipVolumeDriftCorrection.targetDivergence` 的 `openVolume`。
3. `rebuildSurfaceTensionPressure` 中的 `surfaceTensionColor` 液体分数。
4. `totalOpenVolume()` 与 `tryInsertExactMaterial/Mixture/Chemical` 的格容量检查。

不用于：

- `markerCount > 0` 压力相判定。
- cut-cell 面 aperture（面开口仍由 `FlipCutCellGeometry` 决定）。
- `AirCavityScanner`（M8.0 不耦合气体；M8.2 后若同时开气体门，需单独评审气体有效体积）。

#### 4.4.2 精确排水语义

- 颗粒运动、沉积、唤醒均不修改 `particleVolume` 账本。
- 颗粒进入满水格时允许该格短暂 `exactCellVolume > effectiveOpenVolume`；压力漂移修正以 `effectiveOpenVolume` 为目标，把过量水挤出到邻格。挤出量不是从平滑 density 估算，而是由有效体积差驱动；水账本不变。
- `tryInsertGrains` 必须 all-or-nothing：插入前检查全局容量 `totalWaterVolume + totalGrainVolume + insertedVolume <= totalOpenVolume`；拒绝时零残留。总水量守恒由“不删水”保证。
- 密闭满容器插入颗粒：全局容量检查失败，显式拒绝，不删除水、不制造隐藏 sink。
- 露天或带空气腔的容器插入颗粒：允许局部过满，靠压力步把水排到空气腔；空气腔为 ambient 时水表上升量应逼近 `insertedVolume / 横截面积`。

#### 4.4.3 孔隙水

- 沉积格内水仍然只是普通 marker；孔隙水参与压力，没有“隐藏孔隙水库存”。
- 沉积格排空水（例如 `/realwater drain`）后，沉积格 solidVolume/mass 不减少，只有水 markers 离开；孔隙体积成为空气可占空间。
- 再次注水时，容量检查只按 `effectiveOpenVolume` 计算，不按沉积格 `bulkVolume` 计算。

### 4.5 沉积休眠与局部唤醒

#### 4.5.1 沉积条件

颗粒在以下条件**同时**满足时原子转为沉积格 entry：

1. **支撑**：`y == 0`（域底边界），或 `solid[x][y-1][z]`，或 `depositSolidVolume[cell(x,y-1,z)] > 0`。
2. **低速**：`|v_grain - u_fluid| <= depositionVelocity`，连续 `depositionSettleSteps` 个子步满足（默认 `0.02 m/s`、3 步）。
3. **容量**：该格 `solidVolume + poreVolume + grainSolidVolume + grainPoreVolume <= cutCells.openCellVolume`。
4. **斜坡门**：把该颗粒体积临时计入所在列后，与 x±1、z±1 柱高的最大高差不大于 `tan(angleOfReposeRadians)`。

柱高定义（域单位，无量纲，单位 cell）：

```text
columnSolidVolume(x,z) = Σ_y depositedSolidVolume[cell(x,y,z)]
columnPoreVolume(x,z)  = Σ_y cellPoreVolume[cell(x,y,z)]
columnHeight(x,z)      = columnSolidVolume + columnPoreVolume
```

#### 4.5.2 斜坡不满足时滚动

- 取最陡下降方向 `grad = (columnHeight(x+1,z)-columnHeight(x-1,z), columnHeight(x,z+1)-columnHeight(x,z-1))`。
- 水平速度设为 `ROLL_SPEED * normalize(-grad)`，垂直速度置零；`ROLL_SPEED = max(0.15 m/s, 0.25 * |v_t|)`。
- 该颗粒本子步不沉积；下一子步继续按支撑/低速/斜坡门判定。
- 不做沉积后的全局斜坡松弛（avalanche），避免与“局部唤醒”冲突。

#### 4.5.3 休眠

- 沉积格天然休眠：它不是自由颗粒，`GranularSolver.step` 不遍历它。
- 普通流体静水压力变化、邻格沉积增加、支撑关系变化均不得唤醒沉积格。
- `status` 只报告 `depositedSolidVolume` 总量与 `dormantCells` 数量。

#### 4.5.4 局部唤醒

唤醒触发器只允许两类：

1. 该沉积格 `markerCount > 0` 且局部流体速度 `|u_fluid| > wakeFluidSpeed`（默认 `0.5 m/s`）。
2. M8.2 反应沉淀/溶解（按化学计量精确数量）。

唤醒预算：

- 每格每次最多唤醒 `MAX_WAKE_VOLUME_PER_CELL = 0.25` 域体积。
- 每域每子步最多唤醒 `MAX_WAKE_VOLUME_PER_STEP = 1.0` 域体积。
- 每格每次最多生成 `MAX_WAKE_GRAINS_PER_CELL = 8` 个颗粒。
- 从沉积格 entry 按“最大 solidVolume entry 优先、相同则 material handle 小优先”的确定性顺序取出；取出的体积/质量精确转入新自由颗粒。
- 新自由颗粒速度初始化为当前格流体速度；位置在格中心。
- 任一预算不足则整格不唤醒。

### 4.6 反应沉淀（M8.2，不在 M8.0 实现）

M8.2 才允许化学反应创建/溶解颗粒。设计边界先冻结：

- 沉淀数据路径：`data/<namespace>/realwater/granular_materials/*.json` 与 `data/<namespace>/realwater/precipitations/*.json`。
- 沉淀反应只允许 `aqueous species -> granular material`，产物颗粒体积 = 反应物 species exact mass 总和 / 颗粒材料密度（域单位）。
- 反应在 M7 化学步骤之后、reseeding 之前执行；不修改 M7 `ChemicalReactionEngine`，新增 `GranularPrecipitationEngine`。
- 沉淀反应必须通过 M7 同级的 exact-rational 校验：未知引用、负系数、质量/电荷不守恒、体积非正、循环、超预算全部 fail-closed。
- 沉淀会同时修改化学 species 账本与颗粒账本；因此只有新 `region-state v5` + `protocol v4` + `container-transfer WAL v4` 评审落地后才允许对持久控制器区域启用。
- 在 v5 落地前，沉淀只能在瞬态开发域运行；持久控制器区域即使 `chemistryEnabled && granularEnabled` 也只允许纯液相化学 v3，不允许沉淀。

### 4.7 版本预留与持久化边界

- M8.0：无任何新持久化、无新协议、无新 WAL。
- `region-state v4` 预留给气体持久化评审；M8 颗粒持久化必须使用新 `region-state v5`，并保留 v1/v2/v3 reader 与 canonical bytes。
- 颗粒持久化需要独立格式评审，不得把 `GranularSolver` 内部数组直接序列化。
- 在 v5 落地前：
  - `bindGranular` 只允许 transient 区域（`FluidRegion.controllerManaged() == false`）。
  - `FluidRegionPersistence` 遇到带颗粒的控制器区域不得 checkpoint，必须 fail-closed。
  - `FluidTransferService` 对控制器区域的颗粒桶入口拒绝并保留原物品。

## 5. 新文件落点

### 5.1 `com.example.granular` 包（纯 Java，不 import Minecraft）

| 文件 | 职责 |
|---|---|
| `GranularMaterial.java` | 颗粒材料定义，stable key 与硬校验 |
| `GranularMaterialRegistry.java` | runtime handle 分配、id 唯一、查询 |
| `BuiltinGranularMaterials.java` | 内置 `fine_sand`/`light_beads`/`precipitate`；display ARGB 单独放渲染端 |
| `GranularSolverConfig.java` | 黏度、空气密度、沉积/唤醒阈值、滚动速度、硬预算 |
| `GranularLimits.java` | 材料数、颗粒数、沉积 entry、唤醒预算、最大粒径等硬上限 |
| `GranularEnvironment.java` | `GranularSolver` 读取流体速度、密度、solid 查询、cell 定位的接口 |
| `GranularDepositField.java` | 稀疏沉积格账本、柱高、孔隙派生、原子 add/remove |
| `GranularSolver.java` | 自由颗粒账本、Stokes 积分、沉积/滚动/唤醒、all-or-nothing 插入 |
| `GranularTransferLedger.java` | 插入/唤醒/沉淀的颗粒体积/质量账本，checked arithmetic |

### 5.2 `fluid` 包内改动

| 文件 | 改动 |
|---|---|
| `FlipFluidDomain.java` | 新增 `granularSolver`、`granularSolidVolume`；实现 `GranularEnvironment`；在 `rebuildGridFromParticles` 与 step 末尾调用颗粒重建/推进；`effectiveOpenCellVolume(index)`；`tryInsertGrains`；`totalOpenVolume` 扣除颗粒体积；`bindGranular` |
| `FlipFreeSurfaceGeometry.java` | `rebuild` 增加 `double[] granularSolidVolume` 参数，液体分数使用有效开孔体积 |
| `FlipVolumeDriftCorrection.java` | 接收 `openVolume` 的调用方改为传 `effectiveOpenVolume`；该类本身不改 |
| `FlipParticleState.java`/`FlipMaterialParticleState.java`/`FlipChemicalParticleState.java` | 不改 |
| `FluidSimulationService.java` | `granularFeatureEnabled`；`createGranularDemo`；控制器区域拒绝 `bindGranular`；`grainStatus` 查询 |
| `FluidServerConfig.java` | 配置格式 v5，新增 `granularEnabled`，默认 false；v1–v4 读取强制 `granular=false` |
| `RealWaterCommands.java` | `/realwater granular [true\|false]`、`/realwater granulardemo [settle\|heap\|float]`、`/realwater grainstatus` |
| `RealWaterMod.java` | 按服务 config 初始化 granular 门（启动绑定，重启生效） |

### 5.3 测试与构建

| 文件 | 职责 |
|---|---|
| `src/test/java/com/example/granular/M8GranularValidation.java` | M8 自动门：终速、轻颗粒上浮、精确排水、沉积/安息角、唤醒守恒、插入拒绝零残留 |
| `CpuValidationSuite.java` | 新增 `granular` selector，加入 `FAST_GROUP` 与 `MILESTONE_GROUP`；不新增 JavaExec task |

## 6. `FlipFluidDomain` 集成细节

### 6.1 新字段

```java
private GranularSolver granularSolver;
private final double[] granularSolidVolume;   // cellCount
private boolean granularActive;
```

构造函数始终分配 `granularSolidVolume = new double[cellCount]`，全零。`effectiveOpenCellVolume`：

```java
private float effectiveOpenCellVolume(int index) {
    return (float) Math.max(FlipFreeSurfaceGeometry.MINIMUM_THETA * 1.0E-4F,
        cutCells.openCellVolume(index) - granularSolidVolume[index]);
}
```

颗粒未绑定时 `granularSolidVolume` 全零，因此 `effectiveOpenCellVolume == cutCells.openCellVolume`；不接受分支差异。

### 6.2 `bindGranular`

```java
public void bindGranular(GranularMaterialRegistry registry) {
    if (granularSolver != null) throw new IllegalStateException("Granular solver is already bound");
    // 纯水与多材料液体均可；颗粒材料独立于 FluidMaterialRegistry
    this.granularSolver = new GranularSolver(sizeX, sizeY, sizeZ, config.cellSize(),
            maximumGranularParticleCount, registry, granularConfig);
    this.granularActive = true;
}
```

服务层在调用前检查 `region.controllerManaged() == false`，持久区域直接拒绝。

### 6.3 `rebuildGridFromParticles` 插入点

当前顺序在 `freeSurfaceGeometry.rebuild` 之前：

```java
if (granularSolver != null) {
    granularSolver.rebuildCellSolidVolume(granularSolidVolume);
} else {
    Arrays.fill(granularSolidVolume, 0.0);
}
freeSurfaceGeometry.rebuild(solid, markerCount, cutCells, granularSolidVolume);
```

`rebuildSurfaceTensionPressure` 中的 `surfaceTensionColor` 分母同步改为 `effectiveOpenCellVolume(index)`。

### 6.4 step 插入点

当前 `step()` 子步顺序为 P2G → gas → forces → pressure → project → extrapolate → G2P → advect。颗粒步插入在 `extrapolateVelocityIntoAir()` 之后、`gridToParticles()` 之前：

```java
extrapolateVelocityIntoAir();
if (granularSolver != null) {
    granularSolver.step(dt, this::grainEnvironment); // 后文
}
gridToParticles();
advectParticles(dt);
```

理由：颗粒阻力使用该子步投影后的最终 MAC 速度；颗粒位置变化在下一子步 P2G 前进入 `granularSolidVolume`。

### 6.5 `GranularEnvironment` 域内实现

域内用 lambda/内部类实现：

```java
private GranularEnvironment grainEnvironment() {
    return new GranularEnvironment() {
        public float fluidVelocityX(float x, float y, float z) { return sampleX(velocityX, x, y, z); }
        public float fluidVelocityY(float x, float y, float z) { return sampleY(velocityY, x, y, z); }
        public float fluidVelocityZ(float x, float y, float z) { return sampleZ(velocityZ, x, y, z); }
        public float fluidDensity(int cell) { return (float) cellDensity[cell]; }
        public boolean isFluidCell(int cell) { return !solid[cell] && markerCount[cell] > 0; }
        public boolean pointInsideSolid(float x, float y, float z) { return particleCollision.centerInSolid(x, y, z); }
        public int cellIndex(float x, float y, float z) { return particleCellIndex(x, y, z); }
    };
}
```

`particleCellIndex(float)` 目前只有 `particleCellIndex(int particle)`；M8 增加一个按坐标定位的重载（或直接使用 `(int)Math.floor(...)` 并 clamp，逻辑与 marker 一致）。

### 6.6 容量检查改动

- `totalOpenVolume()` 改为 `Σ max(0.0, cutCells.openCellVolume(i) - granularSolidVolume[i])`。
- `tryInsertExactMaterial` 的 `capacity` 改为 `Math.max(0.0, effectiveOpenCellVolume(index) - exactCellVolume[index])`。
- 水插入/提取原语仍不感知颗粒；容量变化自动体现。

### 6.7 `tryInsertGrains`

首版签名：

```java
public record GranularInsertion(boolean inserted, double volume, double mass) { }

public GranularInsertion tryInsertGrains(
        int centerX, int centerY, int centerZ,
        int materialHandle, double volume, double mass,
        int maxRadius, boolean externalSource);
```

语义：

1. 校验 `volume > 0`、`mass > 0` 有限、`maxRadius >= 0`、`materialHandle` 有效。
2. 计划：在 `maxRadius` 内按距离排序，目标格必须非 solid、且 `cellSolidVolume + plannedSolidVolume <= cutCells.openCellVolume`（颗粒允许进入满水格，但不允许超过颗粒自身堆积容量）。
3. 全局容量：`totalVolumeExact() + totalGrainVolume() + volume <= totalOpenVolume()`；不满足则拒绝。
4. 颗粒数预算：需要的新颗粒数不超过 `maxGrains - grainCount`；一个请求最多拆成 `MAX_INSERT_GRAINS_PER_REQUEST = 64` 个颗粒，每颗体积按材料名义球体积 `π/6 d³ / cellSize³` 拆分，尾颗余量。
5. 计划成功后一次提交；任何一步失败零残留。
6. `externalSource` 为 true 时记入 `GranularTransferLedger` 的 external-source（创造模式放出）；生存/发射器守恒转移记普通插入。

### 6.8 与 reseeding/chemistry 的顺序

- 颗粒步不参与 marker reseeding；marker reseeding 不得合并/删除颗粒。
- M8.0 无化学沉淀，`chemicalParcels.reactDirtyCells` 与 `granularSolver.step` 互不调用。
- M8.2 沉淀引擎在 `reactDirtyCells` 之后、`reseeding` 之前调用；调用后两套账本必须同时闭合。

## 7. 服务层与命令

### 7.1 配置门

`FluidServerConfig.FORMAT_VERSION` 升为 5：

- 新增属性 `granularEnabled`，默认 false。
- v1–v4 配置读取时 `granularEnabled=false`，其余字段照旧。
- `granularEnabled` 不要求 `materialsEnabled` 或 `chemistryEnabled`。
- `chemistryEnabled=true` 仍只要求 `materialsEnabled=true`。
- 门只启动绑定，游戏内修改写 `server.properties` 并提示重启。

### 7.2 `/realwater granular [true|false]`

- 管理员命令；写入世界配置，下次重启生效。
- 与 `materials`/`chemistry` 相同的成功/失败提示模式。

### 7.3 `/realwater granulardemo [settle|heap|float]`

- 瞬态开发域，不写 `region-state`，与 `airdemo` 同生命周期：`remove`、其他 demo、正常关服恢复玻璃/水。
- `settle`：水中均匀悬浮 `fine_sand`，用于沉降与排水自动/实机场景。
- `heap`：水上点源投放 `fine_sand`，用于安息角与沉积休眠。
- `float`：水中投放 `light_beads`，用于轻颗粒上浮。
- 若当前活动区域是 `controllerManaged()`，拒绝覆盖。
- 命令创建后 `paused=true`；用户 `/realwater run` 后观察。

### 7.4 `/realwater grainstatus`

- 管理员按需完整颗粒库存，写 transcript：
  - 自由颗粒总数、总 solidVolume、总 mass。
  - 沉积格数、总 solidVolume、总 poreVolume（派生）。
  - 逐材料 volume/mass。
  - 最近一次唤醒格数与体积。
- `status` 只显示一行颗粒摘要；完整库存只在 `grainstatus` 与 transcript。

## 8. 渲染与协议

M8.0 不新增协议、不做客户端颗粒渲染。验收依据：

1. 自动门日志。
2. `/realwater status` 颗粒摘要。
3. `/realwater grainstatus` 完整库存与 transcript。
4. 实机场景中玻璃/水/颗粒若不可见，由后续协议 v4 展示段解决，**不得**用现有 marker mask 假装颗粒。

M8.2 协议 v4 展示段只允许：

- 沉积格 mask + 材料色。
- 自由颗粒 cell mask（不发送逐颗粒精确体积）。
- 以上字段均为 display data，不结算。

## 9. 验证门

### 9.1 新 selector

`CpuValidationSuite` 注册：

```java
gate(gates, "granular", () -> M8GranularValidation.main(NONE),
        () -> M8GranularValidation.main(FAST));
```

并把 `granular` 加入 `FAST_GROUP` 与 `MILESTONE_GROUP`。

### 9.2 自动门清单

`M8GranularValidation` 只放 M8 领域直接门：

| 门 | 通过条件 |
|---|---|
| 终速沉降 | 静止水柱中单颗粒速度在 1,000 步内逼近 Stokes 解析终速，误差 `<= max(2e-3 m/s, 5%)` |
| 轻重浮沉 | `light_beads` 在水中终速向上，`fine_sand` 终速向下；水平无持续寄生速度 |
| 精确排水 | 带 ambient 空气腔的水槽插入颗粒后，总水体积不变；5,000 步内水表上升 `V_solid/A` 误差 `<= 5%`；满密闭容器插入拒绝且零残留 |
| 沉积/安息角 | `heap` 场景沉积完成后，沿沙堆外表面测得最大坡角与目标安息角差 `<= 3°` |
| 休眠/唤醒 | 沉积后自由颗粒数为 0 或速度全低于阈值；对沉积格施加局部流场，唤醒体积 `<= 预算` 且沉积+自由颗粒总 solidVolume/mass 不变 |
| 插入拒绝 | 超预算或超总容量插入失败后，自由颗粒与沉积格账本逐位不变 |

### 9.3 回归 selector

- 改 `FlipFreeSurfaceGeometry` 或 `volumeDriftDivergence` 调用点：`free-surface`。
- 改 `FlipCutCellGeometry` 或面 aperture：`cut-cell`。
- 改服务/命令/配置门：`protocol`。
- 改水容量检查：`containers`。
- M8.0 完成合并前跑一次 `runCpuValidation -PcpuValidation=granular,free-surface,containers`。
- M8 关闭跑 `runCpuValidation -PcpuValidation=milestone`。

## 10. 实施顺序

### M8.0：颗粒库存 + Stokes 输运 + 沉积骨架（runtime-only）

1. 新建 `com.example.granular` 包：`GranularMaterial`、`GranularMaterialRegistry`、`BuiltinGranularMaterials`、`GranularSolverConfig`、`GranularLimits`、`GranularEnvironment`、`GranularTransferLedger`。
2. 新建 `GranularDepositField`，先支持单材料 add/remove、柱高、孔隙派生；多材料 entry 结构一次到位。
3. 新建 `GranularSolver`：自由颗粒账本、Stokes 积分、solid 碰撞、沉积判定（支撑/低速/容量/斜坡门）、滚动、唤醒。
4. `FlipFluidDomain`：`granularSolidVolume` 数组、`bindGranular`、`effectiveOpenCellVolume`、`rebuildGridFromParticles` 插入、step 插入、容量检查修改、`tryInsertGrains`。
5. `FlipFreeSurfaceGeometry.rebuild` 改签名并接 `granularSolidVolume`；`FlipVolumeDriftCorrection` 调用点改传有效开孔。
6. `FluidServerConfig` v5 + `granularEnabled`。
7. `FluidSimulationService`：granular 门初始化、`createGranularDemo`、控制器区域拒绝、`grainStatus`。
8. `RealWaterCommands`：`granular`、`granulardemo`、`grainstatus`。
9. `M8GranularValidation` + `CpuValidationSuite`。
10. 跑 `runCpuValidation -PcpuValidation=granular,free-surface,containers`。
11. 文档最小维护：`routing.md` 增加 granular 行；`progress/m8-...md` 记录 M8.0 证据。

M8.0 结束标准：自动门全绿；纯水无颗粒路径与 M7 行为逐位一致；`granulardemo` 可在无客户端渲染时通过 `status` 观察。

### M8.1：精确排水、安息角、休眠/唤醒关闭

1. 完成 `tryInsertGrains` 全局容量与零残留测试。
2. 安息角自动门标定斜坡门与滚动参数；失败则记录并触发 MPM 评审条件 1。
3. 精确排水自动门标定 `volumeDriftCorrection` 与有效开孔配合；失败则记录并触发 MPM 评审条件 2/3。
4. 唤醒自动门与 `grainstatus`。
5. 若仍通过，M8.1 关闭；若触发 MPM 评审，另开最小 MPM 验证，不在本计划内扩展。

### M8.2：反应沉淀与持久化（新格式评审后）

1. 数据驱动 loader：`granular_materials` 与 `precipitations`，复用 M7 exact-rational 风格校验。
2. `GranularPrecipitationEngine`：在 `reactDirtyCells` 后、reseeding 前运行；原子修改 chemical parcels 与颗粒账本。
3. 评审并实现 `region-state v5`（v4 留给气体）：保留 v1/v2/v3 reader；v5 manifest 保存颗粒/沉积账本与化学 v3 字段；HEAD 仍最后发布。
4. 评审并实现 `protocol v4` 展示段与 `container-transfer WAL v4` 颗粒容器转移。
5. 控制器区域允许 bindGranular 与颗粒转移。
6. 运行 `region-state`、`protocol`、`containers`、`chemistry`、`granular` 全门。

### M8.3：关闭

1. 跑 `runCpuValidation -PcpuValidation=milestone`。
2. 跑 `clean assemble compileTestJava --offline --no-daemon`（部署前）。
3. 实机单场景（见下）验收并记录日志。
4. 更新 `docs/progress/m8-...md`、`version-history.md`、根 `README.md`、`routing.md`、`product-roadmap.md` M8 状态。
5. 主 Mod 版本按 `acceptance-and-release.md` 决定，不随开发次数自动递增。

### 10.1 实机单场景（需要视觉时）

- 场景：`/realwater granulardemo heap` 后 `/realwater run`。
- 预期：玻璃水槽内沙粒从点源下沉，底部形成锥形沙堆；水面比初始略高；`/realwater status` 显示 `granular grains=0 depositedSolid=…`，`/realwater grainstatus` 与 transcript 显示颗粒与沉积账本闭合。
- 证据：命令 transcript 无错误；`grainstatus` 总 solidVolume/mass 与投入量一致；水体积与投入前一致。
- 说明：该场景只在 M8.1 后、且协议 v4 渲染或临时 debug overlay 可用时请求用户进游戏；自动门无法观察真实渲染，才发请求。

## 11. 风险与回退

| 风险 | 回退 |
|---|---|
| `effectiveOpenVolume` 改动破坏纯水 M7 行为 | 颗粒未绑定全零路径必须逐位一致；M8.0 前跑 `free-surface` 回归；失败则回退该改动，不提交 |
| Stokes 模型使沙粒沉降过慢/过快 | M8.1 终速门先关闭解析一致性；实机速度不可接受时再评审 Schiller-Naumann 终端模型，仍不引入 DEM |
| 斜坡门无法形成稳定安息角 | 触发 MPM 评审条件 1；不继续叠加经验修正 |
| 局部唤醒放大沉积 | 预算硬限制 + 每格最多 8 颗粒 + 账本校验；任何一步超预算整格不唤醒 |
| 反应沉淀破坏化学 v3 | M8.2 前不允许在持久区域启用沉淀；v5 评审落地前沉淀只允许瞬态域；M7 v3 reader/字节不变 |
| 颗粒状态被误持久化 | M8.0 不写 v1/v2/v3；`FluidRegionPersistence` 对 transient 域本来不 checkpoint；服务层显式拒绝控制器域 bindGranular |
| 新 selector 拖慢日常门 | `granular` fast 模式只跑终速/浮沉/插入拒绝；长期沉积与排水门只进入显式 `granular` 全模式与 `soak`（如稳定后加入） |

## 12. 完成清单（另一 AI 自查）

- [ ] `granularEnabled` 默认 false，旧配置读取为 false，重启后生效。
- [ ] `GranularMaterial`/Registry 硬校验齐全，内置 3 个材料。
- [ ] 自由颗粒与沉积格账本分别闭合；孔隙仅为派生。
- [ ] Stokes 终速解析积分；轻/重浮沉方向正确。
- [ ] `effectiveOpenVolume` 用于自由面、drift、表面张力色与水容量；颗粒未绑定路径逐位不变。
- [ ] `tryInsertGrains` all-or-nothing；满密闭容器拒绝零残留。
- [ ] 沉积条件四门：支撑、低速、容量、斜坡；安息角自动门通过。
- [ ] 唤醒触发器与预算冻结；唤醒前后固相总体积/质量守恒。
- [ ] M8.0 未修改 region-state/protocol/container WAL；控制器持久域拒绝颗粒绑定。
- [ ] `CpuValidationSuite` 增加 `granular`，默认 `fast` 包含该 selector。
- [ ] `granulardemo`/`grainstatus` 可用，transcript 完整。
- [ ] 文档更新：`routing.md`、`progress/m8-...md`，README 命令表增加颗粒命令。
