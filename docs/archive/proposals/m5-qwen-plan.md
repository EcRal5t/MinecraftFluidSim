# Archived proposal: M5 空气区域网络与气液压力执行计划（qwen 版）

> 本文是进入 M5 实施前的执行计划，归档于 `docs/archive/proposals/`，用于指导实施 AI 完成 M5。
> 实施阶段的系统契约以 [`../../architecture/fluid-solver.md`](../../architecture/fluid-solver.md) 为准，阶段总表以 [`../../roadmap/product-roadmap.md`](../../roadmap/product-roadmap.md) 为准，测试路由以 [`../../maintenance/routing.md`](../../maintenance/routing.md) 为准。
> 同目录 `m5-gemini-plan.md` 是另一份独立提案；本文与其并存，实施时以本文为准。两份提案的主要差异在 §4.4（孔流边的来源）与 §4.7（持久化/协议决策）。

---

## 0. 实施前必读

按以下顺序阅读，不要凭旧对话记忆推测文件位置：

1. [`../../README.md`](../../README.md)（仓库根 README：命令表、构建、安全边界）
2. [`../../maintenance/routing.md`](../../maintenance/routing.md)（代码职责 + 最小验证路由）
3. [`../../architecture/fluid-solver.md`](../../architecture/fluid-solver.md)（当前 CPU 流体契约）
4. [`../../roadmap/product-roadmap.md`](../../roadmap/product-roadmap.md) 的 M5 节与“接下来三项”
5. [`../../roadmap/gases-and-granular.md`](../../roadmap/gases-and-granular.md)（M5 主题规格与验收条目）
6. [`../../roadmap/cross-cutting-contracts.md`](../../roadmap/cross-cutting-contracts.md)（状态分层、feature flag、协议边界）
7. [`../../roadmap/acceptance-and-release.md`](../../roadmap/acceptance-and-release.md)（里程碑如何签收）
8. [`../../progress/m4-surface-tension.md`](../../progress/m4-surface-tension.md)（M4/M4.1 已签收边界与已量化限制）

基线：主 Mod `0.10.0`，分支 `deepseek-handoff`，Minecraft 26.2 / Fabric Loader 0.19.3 / Fabric API 0.157.0+26.2，JDK `D:\ProgramFiles\BienCreng\jdk-26.0.2`（目标字节码 Java 25）。构建：

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat clean assemble compileTestJava --offline --no-daemon
```

活动核心是 `src/main/java/com/example/fluid/FlipFluidDomain.java`。`FluidDomain.java` 是未接入游戏的旧 VOF 对照，禁止切回。

---

## 1. M5 目标与范围

### 1.1 交付目标

1. **密闭空腔识别**：在区域内识别被固体与液体封闭的空气连通分量，形成气体网络节点（node）；与域开放边界连通的空气归为环境大气（ambient，表压恒 0）。
2. **有限气体库存**：每个密闭节点保存气体库存（物理真值），使用固定参考温度的等温状态方程由库存与可用体积计算表压 `p_gas`。
3. **有限开口边**：节点之间/节点与大气之间通过孔流边（edge）以压差驱动的有限速率输运气体；打孔不瞬时删除高压库存。
4. **气液双向压力耦合**：
   - 液 → 气：液面占据体积改变节点可用体积，压缩使 `p_gas` 单调上升；
   - 气 → 液：`p_gas` 进入自由面 ghost-fluid 边界 `p_interface = p_gas + σκ`，复用 M4 已有两处消费点，不引入第二套表面力离散。
5. **拓扑事件守恒**：开孔、封孔、分裂、合并保持气体总库存（有限库存 + 明确报告的排放量 vent），不产生 NaN，不重复/删除库存。
6. **开发命令与可观测**：`/realwater airdemo` 瞬态场景；状态输出报告节点数、库存、扫描访问量、停止原因与分类。
7. **关闭 M5，版本推进 0.11.0**，达到路线图定义的“有界工业系统 1.0”气体部分。

### 1.2 明确不做（M5 范围外）

- 不逐格求解开放大气；不扫描天然洞穴（未来天然空腔搜索默认 512 BV 预算，M5 只消费该约束、不实现天然搜索）。
- 不做喷流细网格、火焰、玩家附近局部速度场展开。
- 不做气体温度/内能（M9）；不做气体组分化学反应（M7）；不做气体溶解、气泡穿过液体。
- 不改 wire 协议、不改 region-state 字节（决策与理由见 §4.7）。
- 不改 GPU probe；M5 是纯 CPU 改动。
- 不实现液液界面锐化/逐顶点法线平滑（属后续渲染里程碑评估）。

### 1.3 架构红线（违反即返工）

1. **物理真值分层**：液体真值仍是 marker `particleVolume`/`particleMass`；气体真值是节点库存。两套账本独立结算，互不换算；气体压力不得反写 marker 体积。
2. **单一表面力离散**：`p_gas` 必须叠加进 M4 既有的 ghost-fluid `p_interface` 消费路径（压力 RHS 边界散度 + 投影 `pressureDifference`），禁止在 MAC 面上另加外力项。液液 CSF（balanced-force）路径不改动。
3. **逐位退化**：气门关闭或域内全部为环境大气时，活动核心必须与 M4 行为逐位一致（bit-for-bit）。实现上用显式 flag 走旧路径，而不是依赖 `+0.0` 的数值巧合。
4. **守恒**：孔流输运成对 ±；分裂按子体积分配且末项取余数保证精确和；合并直接求和。任何拓扑事件后总库存（有限 + 已报告 vent）不变。
5. **Fail-closed 与预算**：扫描只在区域已知边界内进行；超预算/触达未加载即停止并分类报告，不静默蔓延。
6. **门控独立**：气体使用独立服务器配置门 `gasEnabled`，与材料门互不捆绑；关闭时不创建任何气体状态。
7. 不得用渲染补丁、墙角偏置、强制找平或直接推拉 marker 伪造气压效果。

---

## 2. 当前代码事实（实施依据）

以下行号基于基线提交 `c88d3d6`，实施时如有漂移以符号名定位：

- `FlipFluidDomain.step()`（约 :600）：`rebuildGridFromParticles` → `applyGravity` → `applyLiquidPairSurfaceTensionForce` → `enforceSolidFaceVelocities` → `solvePressure` → `projectVelocity` → 外推/G2P/平流/碰撞 → reseeding。
- `rebuildGridFromParticles()`（约 :641）：重建 `markerCount`、`exactCellMarkerVolume` 等，末尾调用 `rebuildSurfaceTensionPressure()`（:682）。气体拓扑重建与 `p_gas` 更新挂在这之后（§4.6）。
- `isAir(index)`（约 :1525）：`!solid[index] && !isFluid(index)`；`isFluid = markerCount > 0`。M5 空腔扫描复用该定义。
- **界面压力消费点一（压力 RHS）**：`solvePressure`（约 :793）中 `correctedDivergence = div + surfaceTensionBoundaryDivergence(index, dt) - volumeDriftDivergence(...)`（:810）。`surfaceTensionBoundaryDivergence`（约 :1097）在 `!surfaceTensionActive` 时直接返回 0；逐面项 `surfaceTensionBoundaryFace`（约 :1115）对每个 `isAir(neighbor)` 的开放面贡献 `-openArea * scale * densityScale * surfaceTensionPressure[fluid] / theta * inverseCell`。
- **界面压力消费点二（投影）**：`projectVelocity`（约 :1278）经 `pressureDifference`（约 :1309）：液-气面返回 `(surfaceTensionPressure[fluid] - pressure[fluid]) / theta`。`σ=0` 时 `surfaceTensionPressure` 全零，即当前“零表压 Dirichlet”行为。
- `findPressureAnchor()`（约 :1258）：任何流体格有空气邻居即返回 -1（自由面 Dirichlet 定压级）。密闭腔内的自由面同样满足该条件，M5 无需改动锚点逻辑。
- `surfaceTensionMeanCurvature`（约 :1071）/`surfaceTensionNeighborNormal`（约 :1083）/`surfaceTensionNormalComponent`（约 :993）：每格每分量都重算完整三分量中心差分梯度，约 3 倍冗余。**M5 复用该路径前必须先完成 §5 的法向缓存重构**（M4 审阅遗留约束）。
- 开发命令模式：`FluidSimulationService.createTensionDemo`（约 :254）是瞬态演示域的现成模板（先 `restoreTakeover`、拒绝覆盖控制器区域、创建后暂停、`sendSnapshot`）。域尺寸常量 `BLOCK_SIZE_X/Y/Z = 12/8/12`、`CELLS_PER_BLOCK = 2`（约 :42-45）。
- 配置门模式：`FluidServerConfig`（`formatVersion` 1/2、`materialsEnabled` fail-closed、启动读取一次）。
- 测试门模式：`build.gradle` 中 `tasks.register("runXxxValidation", JavaExec)`（约 :85-98 为 M4 两门），测试是 `src/test/java` 下带 `main()` 的独立类，要求 `--offline`。
- 版本：`gradle.properties` 中 `version=0.10.0`。

---

## 3. 状态分层归属（每项 M5 新状态必须先定位）

| M5 新状态 | 层次 | 说明 |
|---|---|---|
| 节点气体库存 `V0`（标准状态等效体积，§4.2） | 物理真值 | 开孔/封孔/分裂/合并守恒结算对象 |
| 节点可用体积 `V_avail` | 数值离散 | 由 marker 占据与 cut-cell 几何派生，每步重建 |
| 节点表压 `p_gas` | 数值离散 | 由 EoS 从库存与体积派生，不独立存储真值 |
| 空腔节点划分 `nodeId[]` | 数值离散 | flood fill 派生；拓扑事件时按 §4.5 规则迁移库存 |
| 孔流边与瞬时流量 | 数值离散 | 输运量成对作用于库存真值 |
| `airdemo` 场景、debug 文本输出 | 渲染/观测 | 只读消费，不回写 |
| `gasEnabled` 配置门 | 调度/事务边界 | fail-closed，启动读取一次 |

---

## 4. 总体设计

### 4.1 组件总览

新增包 `com.example.fluid.air`：

| 类 | 职责 |
|---|---|
| `AirNode` | 单节点状态：id、是否环境、`availableVolume`、`standardVolume V0`（库存）、派生 `gaugePressure` |
| `AirEdge` | 孔流边：两端节点 id、流通面积 `area`、流量系数 `dischargeCoefficient` |
| `AirNetwork` | 节点/边容器；EoS 求压；`stepEdges(dt)` 孔流输运；分裂/合并/开孔/封孔的库存迁移；vent 账本与统计报告 |
| `AirCavityScanner` | 输入 `solid`/`markerCount`/`cutCells`，flood fill 输出 `cellNodeId[]`、节点 `V_avail`、几何边候选、扫描统计（访问量/停止原因/分类） |

`FlipFluidDomain` 持有可选 `AirNetwork`（气门关闭或纯环境域为 `null`，零开销且逐位退化）。`FluidSimulationService` 按 `gasEnabled` 为区域装配网络。

### 4.2 状态方程与单位

求解器压力是表压（`FluidSolverConfig` 注释：大气为零），单位与 `ρ g h` 一致（ρ=1000 kg/m³、g=9.81、cellSize=0.5 时即 Pa 量纲）。

- 参考大气压 `P0 = 101325`（与求解器压力同单位）。
- 库存用**标准状态等效体积** `V0`（该团气体在 `P0` 下的体积，m³）表示，避免显式 R/T/M 常数；等温（固定参考温度）下 `P_abs · V_avail = P0 · V0`，即：

  ```
  p_gas = P0 * (V0 / V_avail_safe - 1)        （表压）
  ```

- 正则化：`V_avail_safe = max(V_avail, EPSILON_V * V_node_total)`，`EPSILON_V = 1e-3`；再 clamp 到 `[-0.99 * P0, P_MAX_SAFETY]`。`P_MAX_SAFETY` 默认 `50 * P0`（可配），防止满充奇异与 PCG 病态；触底/触顶事件计入统计并可在 debug 输出。
- 负压下限 `-0.99*P0` 只是数值护栏；物理上密闭气体不会到负压，触底说明液体试图拉穿气体，按 §9 风险处理并报告。
- M9 引入内能后用物质的量与能量账本替换 `V0`；`AirNode` 字段命名与迁移规则保持该接缝（库存是守恒量，EoS 是派生）。
- 新节点初始库存：`V0 = V_avail`（即初始表压 0，与环境平衡）。

### 4.3 空腔识别（flood fill）

- 可通行格：`!solid && markerCount == 0 && openCellVolume > 0`（与 `isAir` 一致，另加开放体积检查）。6 邻域连通。
- **环境判定**：分量触达域边界上**未被固体封闭**的面（即该边界格的外侧面开放），整分量归 ambient（节点 0，`p_gas ≡ 0`，无库存）。demo/capture 域顶部开放，因此其空气必须是 ambient——这是回归检查项。
- **密闭判定**：完全被固体与液体面包围的分量成为独立密闭节点。
- **部分充液格的体积贡献**：`V_avail(node) = Σ_{member cell} openCellVolume(cell) + Σ_{interface cell} airResidual`。interface cell 指 `markerCount > 0` 且与 node 成员面相邻的格，`airResidual = openCellVolume - exactCellMarkerVolume`（clamp ≥ 0）。归属规则必须确定性：一个界面格只归**一个** node，取其空气侧邻居中 node id 最小者；若无空气邻居（被液体全包围的孤立面）则不计入。该细化使液面升降时 `V_avail` 平滑变化而不是整格跳变；实现上先做“仅成员格 openVolume”的简单版并通过单调性门，再加界面残差项并复跑同一门确认单调性不变差。
- **扫描预算与停止**：扫描只在区域自身格内进行（控制器域边界已知）。设单次扫描访问上限 `scanBudget = region cellCount`（区域本身受 manager 预算约束）；触达上限立即停止，未分类部分按 ambient 处理并记录停止原因。`AirCavityScanner.Result` 必须携带：访问量、停止原因（`COMPLETED` / `BUDGET_EXHAUSTED`）、每分量分类（`AMBIENT` / `SEALED`）。天然空腔 512 BV 预算是未来搜索入口的参数，M5 把 `scanBudget` 做成构造参数即满足接缝。
- **脏标记与重建时机**：首版使用全局脏标记（而非逐节点增量标脏）：`setSolid`、世界碰撞刷新、以及 `rebuildGridFromParticles` 检测到“空气-液体”0↔>0 格数变化（用上一帧 `markerCount==0` 掩码的计数或逐格比较，成本 O(cells)，与既有重建同阶）时置脏；脏时在下一个物理步重建一次。理由：区域有界、拓扑变化是块事件驱动而非每帧，全量重扫成本可接受；增量标脏留作后续优化，但 `AirCavityScanner` 的输入/输出接口按可增量设计（输出完整 `cellNodeId[]`，不做原地假设）。

### 4.4 孔流边（与 gemini 提案的主要差异点）

边的来源有两类：

1. **几何边（自动）**：flood fill 的连通阈值设为“完整开放面”。两个**不同节点**的空气格之间，开放面面积 `0 < area < 1.0`（cut-cell 部分开口，如门缝、活板门、半砖侧缝）不视为连通，而是累加为一条几何边：`area = Σ 面面积`，`Cd` 默认 0.65。同一对节点的多处缝隙合并为一条边。完整开放面（area = 1.0，即整格打通）视为连通，走节点合并路径（§4.5）——这是“大开口”行为：库存精确守恒，压力按合并后体积即时重算。
2. **显式边（场景 API）**：`AirNetwork` 提供按格对声明孔流的入口（面积 + Cd），供独立验证与 `airdemo` 构造“小孔有限均压”场景，不依赖 Minecraft 几何。该 API 仅服务端内部使用，不进协议。

孔流定律（CONTAM 幂律，参考 `gases-and-granular.md` 链接）：

```
Q = Cd * A * sign(pA - pB) * sqrt(2 * |pA - pB| / RHO_AIR)     （m³/s，按源节点实际密度）
RHO_AIR = 1.2 kg/m³（常数，M9 再细化）
```

库存输运（守恒形式，按质量等价的标准体积）：

```
ΔV0 = Q * dt * (P0 + p_source) / P0
V0_source -= ΔV0;  V0_target += ΔV0
```

稳定性护栏：`ΔV0` clamp 到 `[0, MAX_TRANSFER_FRACTION * V0_source]`（默认 0.5），且不得使源库存为负；clamp 触发计入统计。若大压差下出现振荡，允许在 `stepEdges` 内对边做子步（subcycle），但子步数必须有硬上限并记录。

验收要求：双腔均压全程 `Σ V0` 逐位不变（成对 ± 保证），压差单调衰减、无 NaN；封孔（移除边）前后总库存不变。

### 4.5 拓扑事件与库存迁移

每次重扫后，将旧节点映射到新分量（按旧节点成员格在新分量中的多数归属，平局取 id 小者；映射规则必须确定性并写入 javadoc）：

- **合并**（多旧 → 一新）：`V0_new = Σ V0_old`；若新分量是 ambient，有限库存转为 vent 排放（`ventedVolume += Σ V0_old`），物理含义是排入大气。vent 是账本报告项，不是删除：`有限库存 + vented ≡ 初始总库存` 是守恒门的断言对象。
- **分裂**（一旧 → 多新）：按新子体积分配 `V0_i = V0_old * V_avail_i / Σ V_avail`；最后一项用减法取余数，保证和逐位等于 `V0_old`。
- **新孤立空穴**（液体排出/方块移除产生、且不与任何旧节点连通的新分量）：初始化为环境平衡 `V0 = V_avail`，记为 `ambientSource` 事件并报告。这是明确的建模选择（密闭域内无气体源时的工程近似），必须在进度文档中量化声明；验收守恒门不覆盖该事件，但要覆盖开孔/封孔/分裂/合并四类。
- **节点消失**（成员格全部被液体占据且无界面残差）：不销毁库存，节点保留为“零体积节点”，`V_avail` 取 ε 下限、压力取 `P_MAX_SAFETY` 上限，等待重新连通；统计计入 `compressedFloor` 事件。禁止静默丢弃 `V0 > 0` 的节点。
- 所有事件写入 `AirNetwork` 的最近事件环形记录（供 debug/状态输出），不进入存档。

### 4.6 气液压力耦合（复用 M4 ghost-fluid，两处消费点）

核心改动是把“每格 `surfaceTensionPressure`”升级为“每面解析的界面压力”，且 `p_gas` 在消费点按该面空气邻居所属节点解析：

```
p_interface(fluidCell, airCell) = airNetwork.gaugePressure(nodeId[airCell]) + sigmaKappa[fluidCell]
```

1. **压力 RHS**：`surfaceTensionBoundaryDivergence` 泛化为 `interfaceBoundaryDivergence`，激活条件从 `surfaceTensionActive` 改为 `surfaceTensionActive || gasPressureActive`；`surfaceTensionBoundaryFace` 中把 `surfaceTensionPressure[fluid]` 替换为 `p_interface(fluid, neighbor)`（neighbor 已确认为空气格，查 `nodeId[neighbor]`）。
2. **投影**：`pressureDifference` 的液-气分支同样改为 `p_interface`。
3. `σκ` 部分仍由 `rebuildSurfaceTensionPressure()` 产出（重构后由法向缓存驱动，§5）；`p_gas` 不加进该数组，保持其“仅表面张力”的语义，避免 σ=0 路径被污染。
4. **步内时序**（`step()` 每个 substep）：`rebuildGridFromParticles`（含 σκ 重建）→ 气体拓扑重建（若脏）→ `V_avail` 重算 → `stepEdges(dt)` → EoS 求 `p_gas` → `solvePressure`/`projectVelocity` 消费界面压力。液体运动对本步气体体积的影响在下一步生效（显式耦合），这是数值离散选择，写入架构文档。
5. **锚点**：`findPressureAnchor` 不改。密闭腔自由面仍有空气邻居 → 返回 -1 → 压级由 `p_interface` Dirichlet 固定；全充满密闭域无自由面时沿用既有 firstFluid 锚点，气体节点此时与液体无界面、互不干扰。
6. **液液 CSF 不动**：`applyLiquidPairSurfaceTensionForce` 路径不引入气压；气体只作用于液-气自由面。

### 4.7 门控、持久化与协议决策

- **配置门**：`FluidServerConfig` 增加 `gasEnabled`（默认 false），`formatVersion` 升到 3；v1/v2 reader 保留，v1/v2 文件读出时 `gasEnabled=false`（fail-closed）；未知版本拒绝。启动读取一次，语义与材料门一致但互相独立（气门开不要求材料门开，反之亦然）。
- **持久化**：M5 气体库存**不进 region-state**。理由：与 M4 “字段等格式迁移评审通过后再进存档”的先例一致；气体库存进存档需要 region-state v3 与迁移评审，属 M6 事务门范围。后果（必须写入进度文档的已量化限制）：重启后控制器区域的气体按环境平衡重建（密闭腔 `p_gas=0`）。若实施中发现该限制破坏 1.0 发布语义，停下来升级为 region-state v3 子任务并先做格式评审，不得私自扩格。
- **协议**：客户端不消费气体状态（气体不可见，水面形状已隐含气压效果），M5 **不改 wire**。若后续需要客户端 debug 显示气压，按 cross-cutting 契约走新 capability，不在 M5 预建。
- **takeover/世界事务**：M5 不扩大 takeover、不改 journal；`airdemo` 是瞬态域，不写 region-state、不触碰原版水。

---

## 5. 前置重构：表面张力法向缓存（Phase 0，M5 的硬前置）

M4 审阅遗留约束：`surfaceTensionMeanCurvature` 逐分量重算梯度（约 3 倍冗余），**必须在 M5 复用 ghost-fluid 路径之前重构**。

现状：`surfaceTensionMeanCurvature`（FlipFluidDomain 约 :1071）对 6 个邻居各调一次 `surfaceTensionNeighborNormal`，后者又调 `surfaceTensionNormalComponent`，每次都重算完整三分量中心差分（`surfaceTensionCentralDifference` ×3）+ Young 调整。

目标实现：

1. 新增 `double[] normalFieldX/Y/Z`（cellCount）。
2. `rebuildSurfaceTensionPressure()` 中：一次全格遍历，对每个非固体格计算三分量梯度、归一化、Young 调整（逻辑从 `surfaceTensionNormalComponent` 迁移，语义完全保持：长度阈值 `1e-6/cellSize`、无固体邻居时的退化方向、切向退化分支），写入缓存。
3. 曲率改为对缓存法向做中心散度：`κ = -((nx[i+1]-nx[i-1]) + (ny[j+1]-ny[j-1]) + (nz[k+1]-nz[k-1])) / (2Δx)`；固体邻居复用本格法向、域外邻居 clamp 的语义与 `surfaceTensionNeighborNormal` 现状逐条一致。
4. 液液 CSF 的 `surfaceTensionPairCurvature`（约 :895）同样改为消费缓存构建路径——注意它使用**相分数颜色场**而非液气体积分数场，因此需要“颜色场 → 平滑 → 法向缓存 → 曲率”的通用化：把法向/曲率计算抽成接受平滑后颜色场输入的私有方法，液气与液液各调一次；不得为液液另写一套差分。

验收：

- `runSurfaceTensionCurvatureValidation` 与 `runM4SurfaceTensionValidation` 全部门通过，容差指标不回退（静滴 Laplace 误差、寄生流阈值、守恒逐位、接触角铺展、液液 Laplace 误差 20.5% 量级不恶化）。
- σ=0 逐位退化门保持通过（法向缓存只在 `surfaceTensionActive || liquidPairTensionActive` 时构建）。
- 该重构单独成一次提交，不与 M5 新功能混提。

---

## 6. 分阶段执行计划

每个 Phase 独立可验收、独立提交；每个提交只跑 §7 路由表中对应的一项定向门，除非触发升级条件。

### Phase 0 — 法向缓存重构

内容见 §5。定向门：`runM4SurfaceTensionValidation`（该门已含曲率消费的全部活动核心路径）。升级条件：无（不改 wire/存档）。

### Phase 1 — 独立气体网络引擎（无网格、无 Minecraft 依赖）

新建：

- `src/main/java/com/example/fluid/air/AirNode.java`、`AirEdge.java`、`AirNetwork.java`（按 §4.1/4.2/4.4/4.5 实现；`AirNetwork` 提供手工设置节点/边/库存的入口供测试与 airdemo 使用）。
- `src/test/java/com/example/fluid/air/AirNetworkValidation.java`（`main()` 门）：
  1. EoS：`V0` 固定时 `p_gas(V)` 与解析解逐点比对（含 ε 下限与 clamp 分支）；
  2. 双腔小孔均压：压差单调衰减至容差内、无 NaN、无振荡符号翻转；1000 步内达到平衡；
  3. 库存守恒：均压全程 `Σ V0` 逐位不变；开孔（加边）/封孔（删边）前后不变；
  4. 分裂/合并迁移：手工触发 §4.5 规则，断言总库存与 vent 账本闭合；分裂末项余数规则生效；
  5. 负库存/超压护栏：clamp 触发且统计计数正确。

Gradle 门：`runAirNetworkValidation`。

### Phase 2 — 空腔扫描器（网格拓扑，无动力学）

新建：

- `src/main/java/com/example/fluid/air/AirCavityScanner.java`（§4.3；输出 `Result`：`cellNodeId[]`、节点 `V_avail` 列表、几何边候选列表、扫描统计）。
- `src/test/java/com/example/fluid/air/AirCavityScannerValidation.java`（`main()` 门），用例：
  1. 开放水池（顶部无盖）：全部空气归 ambient；
  2. 密封箱（固体全包围）：内部一个 SEALED 节点，`V_avail` 等于几何解析值；
  3. 开口 U 型管两端通大气：空气全部 ambient；
  4. 多个互不连通的密封腔：节点数与 id 分配正确且确定；
  5. 部分开口面（用 cut-cell 部分开放面积构造）：不连通，生成几何边候选且面积正确；
  6. 界面残差归属：部分充液格的 `airResidual` 计入正确节点，确定性平局规则生效；
  7. 预算：人为设小 `scanBudget`，验证停止原因 `BUDGET_EXHAUSTED` 与 ambient 降级分类。

Gradle 门：`runAirCavityScannerValidation`。

### Phase 3 — 活动核心气液耦合

修改 `FlipFluidDomain`：

- 持有可选 `AirNetwork` 与 `AirCavityScanner` 状态（`null` = 气门关闭，逐位退化由显式 flag `gasPressureActive` 保证）；
- 按 §4.6 改造两处界面压力消费点与步内时序；
- 空气-液体格数变化的脏检测；
- 暴露只读查询（节点压力、库存、扫描统计）供服务层状态输出与测试断言。

新建 `src/test/java/com/example/fluid/M5GasLiquidCouplingValidation.java`（`main()` 门），用例与硬指标：

1. **密闭压缩（注射器）**：密封腔半充水，注入定量水后静平衡；断言 (a) `p_gas` 随压缩单调上升，(b) 平衡时液柱静压 `ρ g Δh` 与 `p_gas` 相对误差 ≤ 3%，(c) EoS 解析值吻合 ≤ 2%；
2. **U 型管差压液柱**：两密闭节点初始 `V0` 不同，重液柱高度差满足 `Δh = Δp/(ρ g)`，相对误差 ≤ 3%；
3. **小孔有限均压（活动核心）**：高压节点经显式边向大气泄漏，压差有限时间衰减，全程 `Σ V0 + vented` 不变；
4. **无持续寄生流与守恒**：平衡态最大 marker 速度 < 1e-4；20 步体积/质量账本逐位闭合；
5. **逐位退化**：同场景 `gasPressureActive=false` 与 M4 基线路径的压力场 `floatToRawIntBits` 逐位一致；
6. **拓扑事件**：在活动核心中封孔/开孔（改固体）一次，断言无 NaN、库存迁移符合 §4.5。

Gradle 门：`runM5GasLiquidCouplingValidation`。升级条件：该阶段同时改了 M4 共享路径，提交时额外跑 `runM4SurfaceTensionValidation`（属“改动跨越共享契约”的正当升级，仅此一次）。

### Phase 4 — 配置门、服务装配与开发命令

- `FluidServerConfig`：`formatVersion=3`、`gasEnabled`，fail-closed 读取；v1/v2 兼容读。定向门按路由“材料门配置或 live 协商”类比跑 `runFluidNetworkProtocolValidation` 确认协商未受影响；若仅改配置读写也可只跑 Phase 3 门，实施时以 routing 表当时条目为准。
- `FluidSimulationService`：`gasEnabled` 时为控制器区域与 `airdemo` 装配 `AirNetwork`；`/realwater status`（或 debug 输出）追加气体段：节点数、密闭库存合计、最近扫描访问量/停止原因/分类、最近拓扑事件。
- `/realwater airdemo [piston|utube|bell]`（注册于 `RealWaterCommands`，模式照 `createTensionDemo`：先 `restoreTakeover`、拒绝覆盖控制器区域、瞬态、创建后暂停）：
  - `piston`：密封箱 + 定量注水，演示压缩回稳；
  - `utube`：U 型管双密闭顶，预置不等 `V0`，演示液柱差；
  - `bell`（钟罩）：倒扣杯体困住空气压入水中，演示气袋托住液面。
  - 三个场景均纯水 σ=0，**不要求材料门**，只要求气门；不得使用 σ=100 之类视觉放大（该放大是 tensiondemo 专属的 M4 视觉验证设计，勿复用、勿当 bug 修）。
- 定向门：`runM5GasLiquidCouplingValidation`（命令装配属服务层薄封装，自动门覆盖物理；命令注册不触及 entrypoint/依赖/资源打包，不跑专用服务器启动门）。

### Phase 5 — 里程碑关闭与签收

1. 汇总回归（里程碑关闭正当升级）：`runM5GasLiquidCouplingValidation`、`runM4SurfaceTensionValidation`、`runM3ConnectedVesselValidation`、`runFlipFreeSurfaceValidation`。不跑 GPU、不跑 5000 步长期门（M5 未触及长期 marker 分布；若实施中引入了每步重扫的性能疑虑，按 §9 补一次性能观测并记录）。
2. 实机 smoke（仅回答自动门无法观察的问题）：部署后 `/realwater airdemo piston` 与 `airdemo utube` 各一次，观察液柱停滞/高度差；记录命令、预期、实际与保留日志。由用户执行，实施 AI 提供验证说明。
3. 文档与版本：
   - `docs/architecture/fluid-solver.md`：新增 M5 生效契约（节点真值、EoS、界面压力、退化、runtime-only 限制）；
   - `docs/maintenance/routing.md`：新增 M5 路由行（见 §7）与高频定位条目（“密闭液面不停/压力异常 → AirNetwork EoS 与界面压力消费点”“扫描超时/分类错误 → AirCavityScanner 统计”）；
   - `docs/progress/m5-air-network.md` + `docs/progress/README.md` 增行；
   - `docs/roadmap/product-roadmap.md`：M5 移入已完成引用，更新“接下来三项”；
   - 根 `README.md`：完成状态与 `airdemo` 命令；
   - `gradle.properties`：`version=0.11.0`。
4. 提交粒度建议：Phase 0 一次；Phase 1 引擎+门一次；Phase 2 扫描器+门一次；Phase 3 核心耦合+门一次（含 M4 门复跑证据）；Phase 4 配置/命令一次；Phase 5 文档+版本一次。提交信息沿用仓库现有风格（祈使句、无正文）。

---

## 7. 验证路由（写入 routing.md 的新行）

| 改动 | 默认一项 | 升级条件 |
|---|---|---|
| 气体 EoS/孔流/库存迁移（无网格） | `runAirNetworkValidation` | 接入活动核心后改跑 M5 耦合门 |
| 空腔扫描/环境判定/几何边候选 | `runAirCavityScannerValidation` | 改 cut-cell 共享面定义才加 cut-cell 门 |
| M5 活动核心气液耦合/界面压力 | `runM5GasLiquidCouplingValidation` | 该门已含退化与守恒；只有改 wire/存档才加对应门 |
| 气体配置门/服务装配/airdemo | `runM5GasLiquidCouplingValidation` | 改 entrypoint/payload 注册才跑 EULA=true server 并正常 `stop` |

日常规则不变：一个小增量只跑最贴近的一项；里程碑关闭才汇总。

---

## 8. 验收总表（对应 gases-and-granular.md 的七条）

| 规格条目 | M5 覆盖与证据 |
|---|---|
| 1. 开放水面使用环境表压 | Phase 2 用例 1/3 + Phase 3 逐位退化门 |
| 2. 密闭液面压缩空气压力单调 | Phase 3 用例 1 |
| 3. 高压腔小孔有限时间均压 | Phase 1 用例 2 + Phase 3 用例 3 |
| 4. 开孔/封孔/分裂/合并库存守恒 | Phase 1 用例 3/4 + Phase 3 用例 6 |
| 5. 气压差维持重液柱高度差 | Phase 3 用例 2 + airdemo utube smoke |
| 6. 气体混合复用 species 库存 | M5 以 `V0` 单组分库存实现接缝；多组分 species 属 M7，不在 M5 验收 |
| 7. 超大空腔边缘更新不超单 tick 搜索预算 | Phase 2 用例 7（预算停止）+ Phase 4 状态报告访问量/停止原因 |
| 连续开孔/封孔无 NaN、不重复/删除库存 | Phase 1 用例 2/3 + Phase 3 用例 6 |

---

## 9. 风险、开放问题与升级触发器

1. **压力尺度与 PCG 收敛**：`P0=101325` 与域内静水压（约 ρg·8m ≈ 7.8e4）同量级，RHS 范数增大；若 `lastPressureResidual`/迭代数显著恶化，优先调整 `pressureTolerance` 的相对化方式而不是降低物理常数。
2. **孔流刚性**：大压差 + 大 Cd·A 时显式积分可能振荡；已有 0.5 源库存 clamp，必要时加有界子步。禁止用隐式求解器引入新依赖。
3. **扫描成本**：每步 O(cells) 的空气掩码比较与重建同阶；若实测 tick 时间显著上升，先把比较改为“仅块事件置脏”，再考虑增量标脏，不要降低扫描正确性。
4. **demo/capture 域误判密封**：开放顶必须判 ambient；Phase 2 用例 1 是硬回归。
5. **新孤立空穴的 ambientSource 建模**：属明确近似；若实机出现可见的“凭空充气”，在进度文档量化并评估改为真空初始化，不在实施中临时改规则。
6. **持久化缺口**：重启后气体按环境平衡重建（§4.7）。若 1.0 发布评审认为不可接受，升级为 region-state v3 子任务并先做格式评审。
7. **无黏性**：当前求解器无黏性（M4 已量化限制），气压驱动的动态过程不会自然平静；验收以静平衡指标与寄生流阈值为准，不以“完全静止”为门。
8. 与 gemini 提案冲突时：以本文 §4.4（几何边 + 显式边双来源）与 §4.7（runtime-only 持久化）为准。

---

## 10. 文件清单

| 文件 | 变更 | 说明 |
|---|---|---|
| `src/main/java/com/example/fluid/air/AirNode.java` | 新增 | 节点：环境标志、`V_avail`、库存 `V0`、派生表压 |
| `src/main/java/com/example/fluid/air/AirEdge.java` | 新增 | 孔流边：面积、流量系数 |
| `src/main/java/com/example/fluid/air/AirNetwork.java` | 新增 | EoS、孔流输运、拓扑事件迁移、vent 账本、统计 |
| `src/main/java/com/example/fluid/air/AirCavityScanner.java` | 新增 | flood fill、环境/密闭分类、几何边候选、预算统计 |
| `src/main/java/com/example/fluid/FlipFluidDomain.java` | 修改 | 法向缓存重构（Phase 0）；可选气体网络装配、两处界面压力消费点、步内时序、脏检测 |
| `src/main/java/com/example/simulation/FluidServerConfig.java` | 修改 | `formatVersion=3`、`gasEnabled` fail-closed |
| `src/main/java/com/example/simulation/FluidSimulationService.java` | 修改 | 按气门装配网络；`airdemo` 三场景；状态输出气体段 |
| `src/main/java/com/example/RealWaterCommands.java` | 修改 | 注册 `/realwater airdemo` |
| `src/test/java/com/example/fluid/air/AirNetworkValidation.java` | 新增 | Phase 1 独立门 |
| `src/test/java/com/example/fluid/air/AirCavityScannerValidation.java` | 新增 | Phase 2 独立门 |
| `src/test/java/com/example/fluid/M5GasLiquidCouplingValidation.java` | 新增 | Phase 3 活动核心门 |
| `build.gradle` | 修改 | 注册 3 个新 JavaExec 门 |
| `docs/maintenance/routing.md` | 修改 | M5 路由行与高频定位 |
| `docs/architecture/fluid-solver.md` | 修改 | M5 生效契约 |
| `docs/progress/m5-air-network.md`、`docs/progress/README.md` | 新增/修改 | 验收记录 |
| `docs/roadmap/product-roadmap.md`、根 `README.md` | 修改 | 完成状态与命令 |
| `gradle.properties` | 修改 | `version=0.11.0` |

不改动：`region-state` 编解码、`FluidProtocol`/snapshot payload、客户端渲染、takeover journal、GPU probe。
