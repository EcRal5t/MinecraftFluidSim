# M8 颗粒沉淀与低成本沉积 —— 独立代码审阅（opencode）

- **审阅范围**：`b611e02c`（不含）→ `HEAD`（`4566b95` Close M8），27 个提交，86 文件 +9625/−219
- **基线**：主 Mod 0.14.0；Minecraft 26.2 / Fabric；Java 25
- **审阅日期**：2026-08-24
- **结论**：**有条件通过（PASS with findings）**。冻结边界（守恒真值、版本门控、事务顺序、只读展示）在代码中全部可验证；发现 1 个中等潜在缺陷（reactive 审计 + 空组成表的 v5 capability 校验不对称）和 3 个非阻塞观察项。测试按用户说明未复跑。

---

## 1. 审阅方法

1. 以 `docs/architecture/granular-state.md`（287 行冻结契约）与 `docs/progress/m8-granular-deposition.md`（M8.0–M8.15）为基准清单。
2. 通读 `com.example.granular` 全部 24 个新类、`FlipGranularCollision`、`FlipFluidDomain` 的 M8 段落（绑定/插入/舀取/相转移/耦合/排水/清空）、`GranularRegionStateCodec`、`RegionState` v5 捕获/校验/重建、WAL v4、`GranularContents`、protocol v4 与客户端渲染 diff。
3. 对关键数值做了独立推导：压力源符号、半隐式阻力、largest-remainder 回和、compaction 的 swap-remove 索引安全性、SVQ↔纳体积换算方向。
4. 逐项核对 M6–M8 冻结边界（见 §4）。

## 2. 冻结契约核对结果

| 契约项 | 实现位置 | 判定 |
|---|---|---|
| SVQ 独立真值：`2^27/BV`、`2^24/cell`，checked long | `SolidVolume`；所有迁移路径 `Math.addExact/multiplyExact` | ✅ |
| marker 亚 SVQ dyadic 尾数不被量化 | `SolidVolume.addCellVolumeSvqExact`（回读 delta 精确比对，否则拒绝）；`GranularPhaseTransferEngine.floorCapacity` 用 BigInteger 精确 dyadic 和 | ✅（M8.11 崩溃修正正确） |
| 逐材料质量独立于体积×密度 | `Parcel.mass`/`SolidPortion.mass` 全程随 SVQ 同步迁移；split 按 SVQ 比例、merge 直接相加；`referenceDensity` 只出现在创建（命令）与浮力/Shields 派生 | ✅ |
| skeleton 决定容量，bulk 只是形态 | `GranularDepositStore.tryAdd` 同时检 `newVolume` 与 `bulkVolumeCeil` ≤ geometric open；液体容量路径只扣 skeleton（`effectiveOpenCellVolume`） | ✅ |
| 孔隙水仍由液体 marker 拥有 | deposit 不保存第二套水库存；`recomputePorosity` 只按 skeleton 加权 rest porosity | ✅ |
| moving-solid source 与统一压力几何 | `granularMovingSolidDivergence = -(S_next−S_old)/cellVol/dt`；符号经独立推导正确（与 drift 项同约定：最终 `div = drift + granular − tension`，固体增格 → 负散度 → 液体外排）；RHS/matrix/preconditioner/projection/face void 全部走同一 `pressureGranularCoupling` | ✅ |
| 每 substep 顺序：P2G→coupling→力/反冲量→preview→压力→G2P→advect→commit | `FlipFluidDomain.step()` 2133–2168 行逐行符合；压力失败不 commit（`granularCollision.commit` 在 projectVelocity 之后） | ✅ |
| 容量拒绝逐 parcel 局部化 | `FlipGranularCollision.resolveCapacity`：remove/try-add 逐 parcel，交替方向有界重试（`min(dim, 131072/count)` pass），失败只 hold 该 parcel；无全域清零 | ✅（M8.13 修正到位） |
| settlement/compaction/relaxation/wake 提交前联合 occupancy plan | `granularDepositEnvironment(plannedSolid)` 的 plan/cancel 对 + 提交后 `matchesSolidSvq` 复核（2179–2213 行）；compaction 同样先 plan 后 rebuild 比对 | ✅（M8.14 修正到位） |
| 首个 bulk-capable 层才可 deposit；禁止越层悬空 | `GranularDepositDynamics.step` 自 y=0 向上扫描，`previewCellBulkVolumeAfterAdd` 通过后仍是唯一合法目标；被 active 暂占时 `rollParcelDownhill` 保持活动 | ✅ |
| 八邻域安息角 + closed-corner face-path | `additionSlopeAllowed`/`tryRelax` 用 `horizontalPathOpen`；对角经 `FluidSimulationService`/domain 的两段开放 face 检查（`xThenZ || zThenX`）；方向起点按 `column*φ + logicalStep` 确定性轮转 | ✅ |
| 休眠：20 步无变化退队；区域静水休眠要求零 active/零 dirty/无 pending 化学 | `SLEEP_LOGICAL_STEPS=20`、`stableAge` 只在无变化时递增；`granularReadyForHydrostaticSleep` 三条件（274–278 行）并接入服务层 hydrostatic 判定 | ✅ |
| 局部 wake：Shields 阈值 + erosion cap + 只取 exposed | `tryWake` 用 `dominantGroup`（exposed），erosion ≤ `min(rate*dt, group, maxParcel)`；亚临界不移动；reactive partial group 无 composition-aware split 时经 `IllegalArgumentException` 捕获保持原状 | ✅ |
| 浮力/阻力：Schiller–Naumann + Stokes 极限，半隐式不翻号 | `GranularMechanics`：`slip*exp(−rate*dt)` 解析弛豫；`Re≤1e-12` 走 Stokes 线性项；密度取当地 marker `cellDensity`，黏度取显式 carrier（无 carrier 不猜水） | ✅ |
| 等量反向冲量散回有质量 MAC face | `addFaceImpulse` 按 trilinear 权重、仅 `faceMass>1e-8` 的面、validWeight 归一 | ✅（见 §3 观察 3） |
| 相转移：M7 每步一次 → M8 一次；同格同 phase；dyadic 精确 | `step()` 先 `reactDirtyCells` 后 `transferGranularPhases(dirtyCells)`；planner 纯函数；`BlockedException` → `region.blockGranular` PAUSED，已提交 cell 保留 | ✅ |
| 新沉淀从被扣体积 carrier 邻域出生、继承速度、优先守恒 merge | `precipitationCarrierMarker` + `precipitationMerge`（比例相容 + 速度门 + 碰撞合法 + 位置有效） | ✅（M8.12 修正到位） |
| 溶解严格反向，只消费同格 active 或 exposed deposit | `accessibleSolid` 只扫同格 parcel + `dominantGroup`；`matchingSolidExtent` 要求 exact extent（整除 + SVQ 精确匹配） | ✅ |
| registry snapshot 权威；未知/失配保留并 LOCKED | `GranularRegistryStore`：atomic 首发 + 读回校验 + 未知文件拒绝；datapack 变化只 warn；`FluidSimulationService` 启动时缺 snapshot 而有 v5 → LOCKED；WAL/区域恢复各自验 fingerprint | ✅ |
| region-state v5 完整代次；`{1,2,3,5}` 精确分派；v4 保留 | `RegionStateCodec.decodeIdentity` 精确集合；`GranularRegionStateCodec` manifest/section typed extension；section/material/global 三层账本逐位（`doubleToRawLongBits`）比对；v1/v2/v3→v5 经 inactive-slot 迁移 + HEAD 后发布 | ✅ |
| runtime composition handle 不上链；v5 映射 stable table | `GranularStateSnapshot.capture` 规范化唯一表 + 孤儿 handle 检查；`restoreInto` 逐格 porosity 与注册表派生比对 | ✅ |
| protocol v4 严格协商；exact 真值不上链 | `FluidGranularSnapshotPayload` 只含量化字段（16-bit 位置、8-bit fill/porosity/diameter、≤4096 样本）；客户端 ledger 校验 version/capability/palette/fingerprint/乱序 | ✅ |
| `GranularContents v1` 与液体组件互斥；≤1 BV/16 组/64 项/64 KiB；canonical round-trip | `GranularContents` 构造器 + `decode` 末尾 `encode().equals(encoded)` 强制 | ✅ |
| WAL v4：`FLUID_NANOS`/`GRANULAR_SVQ` 互斥；事务顺序不变 | `ContainerTransferStore` v4 读写双向拒绝混载；`validateGranularTransferShape` 核对 before/after delta；恢复路径先验 granular registry 身份 | ✅ |
| `/realwater empty`：先拒未决 WAL → 清空 → escaped sink 记账 → 同一 v5 HEAD 后才成功 | `emptySelectedRegion` + `removeAllGranularExact`（capture 先验 ownership、audit 与 cleared 逐位比对、组成恰好释放一次） | ✅（但见 §3 发现 1） |
| 硬预算表 | `GranularLimits` 与文档逐项一致；codec 各字段 `bounded()`；phase transfer/relaxation/dirty queue 均有 operation budget | ✅ |
| pacing/PAUSED 阈值不改、气体不持久化、黑三角不修 | 未见相关改动；`updateStepPacing` 未触碰；`AirCavityScanner` 只是 runtime 扣 skeleton | ✅ |

## 3. 发现

### 发现 1（中等，潜在缺陷）：reactive 审计 + 空组成表时 v5 capability 校验不对称

- **写入侧** `RegionState.granularManifest`（RegionState.java:879–880）：只要 `solidCompositions` 非空 **或** `externalSource/escapedSink.requiresChemistry()` 就置 `CAP_PRECIPITATION`。
- **校验侧** `RegionState.validateGranularLedgers`（RegionState.java:548）：要求 `capabilityPrecipitation == !solidCompositions.isEmpty()`。
- **后果**：区域曾产生 reactive 固体并被 `/realwater empty`（或 escaped-sink 舀空）后，`escapedSink` 含 reactive 条目而活动组成表为空 → 下一次 `captureGranular` 的 `publish→validate()` 抛 "capability set does not match its complete payload" → checkpoint 失败、区域暂停；重启后按旧 HEAD 恢复（数据安全，但 empty 操作被静默回滚，且该区域每次 checkpoint 都会失败，直到再次出现非空组成表）。
- **为何门没拦住**：M8.15 的清空回归（两桶细沙）与 `FluidRegionPersistenceValidation` 的 reactive fixture 都在组成表非空时捕获 v5；"reactive 审计存在 + 组成表为空 + 捕获 v5" 组合没有覆盖。
- **建议**：统一两侧语义。最小修法是校验侧改为 `capabilityPrecipitation != (!compositions.isEmpty() || externalSource.requiresChemistry() || escapedSink.requiresChemistry())`；并在 `granular` selector 增补一个"reactive 清空后捕获并读回 v5"的断言（可复用现有清空 fixture，不需新 Gradle task）。

### 观察 2（低，非阻塞）：Reynolds 越界是 throw 而非 PAUSED

`GranularMechanics.stepVelocities` 在 `Re > carrier.maximumReynolds()` 时抛 `IllegalStateException`（GranularMechanics.java:51–53），而服务层 step 只捕获化学 `BudgetExceededException` 与颗粒 `BlockedException`。按当前材料参数需要 >200 m/s（水载细沙）的 slip 才可达，实际几乎不可能；但语义上与"预算/有效性失败 → 可读 PAUSED"的冻结模式不一致。若未来加入大颗粒/低黏载体，建议把该失败并入 `blockGranular` 路径。

### 观察 3（低，非阻塞）：反冲量在无质量 stencil 时静默丢弃

`addFaceImpulse` 在 8 面 stencil 全部 `faceMass ≤ 1e-8` 时直接丢弃冲量（颗粒贴着无液体的墙/空气边界）。此时总动量不闭合，但属自由表面边界情形；文档的动量闭合门（无墙 fixture）不受影响。记录备查即可。

### 观察 4（低，非阻塞）：`/realwater granularcontainer` 与 `materialbucket` 无权限要求

两者都生成显式 creative/debug 外部源（命令反馈已明示），与既有 `materialbucket` 惯例一致；`reagent`/`chemstatus`/`granstatus` 均有 admin 权限。若服务器对普通玩家开放命令，建议后续统一评估 debug 发放类命令的权限策略（非 M8 回归）。

### 其他已核查且无问题的疑点

- `compactActive` 的逆序提交 + `removeSwap`：因 pair 按 `mergeSecond` 升序生成、逆序提交，swap 移动的被移元素必然不被任何未提交 pair 引用（`mergeFirst < mergeSecond < 当前 tail`），且提交前重算 `activeMerge` 与 `plannedMerge` 比对——安全。
- `tryWake` 的 `max(1, floor(rate*dt))`：SVQ 为 2⁻²⁴ cell，1 SVQ 下限造成的超速可忽略。
- `commitDissolution` 部分溶解时 residual 全留剩余相：由 `massClose` 门守护，不闭合即 `BlockedException`，保守且无静默错误。
- `GranularStateSnapshot.validateActive` 硬编码 0.5 m 边界：与 `bindGranular` 的 0.5 m 强制和 `SolidVolume` 的冻结声明一致，属有意耦合。
- `discoverCommittedStructure` 只用于首发 registry 决策，常规 admission 仍走完整 `discover()`——未削弱校验。

## 4. 冻结边界（M6–M8）回退检查

- 气体仍 runtime-only，未进 wire/存档；`region-state v4` 保持预留且被精确拒绝。✅
- controller ownership、marker 体积、容器纳体积、WAL 事务顺序、`demo`/`empty` 语义均未被 M8 改动破坏；`empty` 的 WAL 前置拒绝已加。✅
- chemistry 默认关闭与依赖链（precipitation→granular+chemistry）在 `FluidServerConfig` 构造器、重启命令、启动装配三处一致；旧 v1–v4 config reader 强制新门关闭。✅
- 展示层（tooltip/durability/protocol/客户端 mesh）全部只读，未发现任何显示值反写结算的路径。✅
- 未发现经验补丁式算法：阻力、Darcy/Kozeny–Carman 阻尼、Shields、安息角松弛均来自文档声明的标准形式；M8.9 的"先阻尼后重力"顺序修正是物理正确性修正而非调参。✅

## 5. 结论

M8 实现与 `granular-state.md` 冻结契约一致，事务、版本门控与守恒账本的结构正确性可验证。**建议在进入 M9 前修复发现 1**（单侧一行语义对齐 + 一个复用现有 fixture 的断言），观察 2–4 可列入技术债跟踪，不阻塞里程碑关闭。

## 6. GPT 联合核实与最终处置（2026-08-24）

本报告原文保留为独立审阅证据。随后与 `m8-gpt-review.md` 逐项映射，结论如下：

- 发现 1 确认有效，且 identity 门也必须覆盖 audit-only payload；现由统一的 `requiresChemistry()` 条件控制 v5
  capability、capture/readback、reconstruct 与 restore，reactive 清空后空组成表的 v5 fixture 已通过。
- 观察 2 确认有效并修复：Reynolds validity 在任何 parcel 速度提交前整体预检，服务层转为可读
  granular-validity PAUSED，失败 fixture 验证 parcel 未改变。
- 观察 3 确认是自由面/边界动量，而不是流体内部闭合；现不再静默丢弃，无质量 face stencil 的分量进入只读
  boundary-impulse 诊断并由管理员 `granstatus` 报告。
- 观察 4 确认有效并收紧：`materialbucket`、`granularcontainer` 现均要求 `COMMANDS_ADMIN`。

联合复核还确认并修复了原报告未列出的 precipitation 联合 occupancy、固液规则声明体积守恒、`remove` 固体排空门、
Minecraft collision refresh、`empty` 联合 preflight 与过期架构说明。实现提交为 `92e9428`、`a312140`、`cb0bc4f`；
最后一项同时冻结全部 carrier sample，并把惰性 audit 纳入 readback registry 校验。完整根因、修复映射、
格式兼容边界与门结果见 `m8-gpt-review.md` 的“联合复核与关闭”。本报告的“有条件通过”据此升级为 **PASS / M8 正式关闭**。
