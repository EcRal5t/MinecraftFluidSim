# 正常操作 fuzzer（runNormalPlayFuzz）

本页介绍 `:fabric:runNormalPlayFuzz` 的结构、模式、动作、场景、不变量与覆盖边界，供外部评审判断还有哪些层级没有覆盖。代码入口与提交历史见文末。

## 一、目的与范围

目标是自动化排查「正常操作被强制中断」：在合法操作的随机序列（含极端状态、分数体积、盘上往返、暂停与冷重启）下，仿真是否抛出 blocker 异常、暂停或状态不一致。它驱动生产类本体，不修改生产代码，也不替代其它确定性校验（`CpuValidationSuite` 等）。

## 二、代码分层

| 文件 | 行数 | 职责 |
|---|---|---|
| `simulation/ControllerInventoryDurabilityValidation.java` | ~1515 | 入口 `main`、模式与调度、`NormalPlayState`（生产对象装配与操作原语）、动作表、场景与 prefill |
| `simulation/FuzzConfig.java` | 28 | 运行开关、温度表、分数体积助手等常量 |
| `simulation/FuzzMetrics.java` | 72 | 覆盖/耗时/子步计数与汇总打印（只观测，不接触仿真状态） |
| `simulation/FuzzMinimizer.java` | 199 | RNG token 记录、脚本回放、ddmin 最小化 |

依赖方向：入口 → 三个助手；助手不反向依赖入口的业务逻辑（`FuzzMinimizer` 通过 `ControllerInventoryDurabilityValidation` 的包内入口调用动作表与状态）。动作表与 `NormalPlayState` 目前仍同处入口文件，是下一步可继续拆分的部分。

## 三、运行模式

任务：`./gradlew.bat :fabric:runNormalPlayFuzz "-PfuzzArgs=<逗号分隔>" --offline --no-daemon`

| 模式 | 参数 | 说明 |
|---|---|---|
| 场景扫描 | `<每场景种子>,<步数>[,禁颗粒][,线程][,sceneFrom][,sceneTo][,seedOffset]` | 内存态；线程默认 `availableProcessors()` |
| 盘上 / 分数 / 叠加 | `disk\|partial\|partialdisk` + 扫描参数 | `disk` 真 publish/unload/restore；`partial` 用分数体积；`partialdisk` 两者叠加 |
| 单序列复现 | `replay[,disk\|partial\|partialdisk],<scene>,<seed>[,步数][,picks\|debug]` | 失败时打印完整动作 trace |
| 最小化 | `minimize[,disk\|partial\|partialdisk],<scene>,<seed>[,步数]` | 记录 RNG token，ddmin 求严格子序列 |
| 指定种子 | `reported` 或 `a:b:c` | 走扩展动作表 `applyExpandedRandomPlay`，在基线场景逐种子运行；当前失败仅打印、不使进程失败 |
| 回归矩阵 | 无参数时的校验入口 | `runNormalPlayStateMatrix`：固定种子 4/54/103 走旧动作集 `applyRandomAction` + 命名场景，由其它校验入口调用，不经本 `main` |

诊断 token（追加在末尾）：`picks` 逐动作打印耗时与机械子步数；`debug` 打印每动作后的完整气体节点状态。

`disk` 模式跳过未密封场景（场景 2）；每次往返按 `FUZZ_DISK_ROUND_TRIP_INTERVAL = 4` 触发一次 `checkpointAndUnload` + `restore`。

## 四、动作矩阵与权重

| pick | 动作 | 次数/参数 | 物理含义 |
|---|---|---|---|
| 0–4 | `insertWater` | 1 | 单材料液体注入 |
| 5–9 | `insertMercury` | 1 | 高密度液体 |
| 10–14 | `insertOil` | 1 | 低密度液体 |
| 15–21 | `insertAcid` | 1–2 | 化学桶注入（HCl + 水） |
| 22–28 | `insertBase` | 1–2 | 化学桶注入（NaOH + 水） |
| 29–33 | `insertSand` | 1 | 颗粒注入 |
| 34–38 | `burst` | 2–9 | 同一材料的连续注入（水/汞/酸/碱/沙） |
| 39–41 | `insertMixture` | 1 | 多材料混合桶（水+汞+油） |
| 42–46 | `step(motion)` | 1 | 纯力学步 |
| 47–51 | `step(phase)` | 1 | 带相变的力学步 |
| 52–56 | `steps(phase)` | 2–5 | 连续相变步 |
| 57–59 | `steps(motion)` | 2–6 | 连续力学步 |
| 60–63 | `temperature` | 1–3000 K | 全液体格绝对温度 |
| 64–66 | `rampTemperature` | ±5/±50/±300 K | 相对温度斜坡 |
| 67–69 | `thermalStep(motion)` | 1 | 仅热学步 |
| 70–72 | `thermalStep(phase)` | 1 | 仅热学步（带相变） |
| 73–76 | `step(movingBody)` | 1–4 刚体 | 同时作用多个移动刚体 |
| 77–78 | `pause` | — | 盘上：设置操作者暂停 |
| 79–80 | `resume` | — | 盘上：清除暂停 |
| 81–82 | `saveWhilePaused` | — | 盘上：暂停后 `persistence.checkpoint` |
| 83–85 | `containerTransfer` | — | 盘上：私域 candidate → stage → commit HEAD → adopt |
| 86–87 | `coldRestart` | — | 盘上：`checkpointAndUnload` → `loadMetadata` → `restore`；随机改步长与运行时意图 |
| 88–90 | `extractWater` | 分数/8 | 抽取水 |
| 91–93 | `extractChemical` | 分数/8 | 抽取化学液体 |
| 94–95 | `extractMixture` | 分数/4 | 抽取混合液体 |
| 96 | `indicator` | — | 泛洪填充指示剂（用 `reservedCellCapacity` 作预算） |
| 97 | `clear` | — | 操作者清空 |
| 98 | `vent` | — | 操作者排空密封气体 |
| 99 | `settle` | — | 结算未决相位功 |

暂停期间 `step`/`steps`/`thermalStep`/`movingBody`/`settle` 返回 `skip(paused)`；`insert`/`extract`/`temperature` 不受暂停限制（对应生产中的 inventory bypass）。

## 五、场景

`FuzzScene` 只描述尺寸、容量、是否密封、注入点与几何 lambda；prefill 在构造末尾注入初始料。`seedBase` 与扫描的 `seedOffset` 相加得到实际种子。

| # | 名称 | seedBase | 尺寸 | 特点 |
|---|---|---|---|---|
| 0 | sealed-empty-8 | 100 | 8³ | 基线密封空腔 |
| 1 | sealed-empty-4 | 200 | 4³ | 最小密封域，最易暴露容量/拓扑边界 |
| 2 | open-empty-8 | 300 | 8³ | 开放边界（disk 模式跳过） |
| 3 | sealed-tall-6x10x6 | 400 | 6×10×6 | 高域 |
| 4 | sealed-floor-8 | 500 | 8³ | 底部实心 |
| 5 | sealed-corridor-8 | 600 | 8³ | 双墙走廊 |
| 6 | sealed-halfcells-8 | 700 | 8³ | cut-cell 半格 |
| 7 | sealed-obstacle-10x8x8 | 800 | 10×8×8 | 地板 + 半格障碍 |
| 8 | sealed-diagonal-8 | 900 | 8³ | 阶梯斜面 |
| 9 | sealed-throat-8 | 1000 | 8³ | 单格喉道，多腔 |
| 10 | sealed-multicavity-8 | 1100 | 8³ | 多腔连通 |
| 11 | sealed-ledge-8 | 1200 | 8³ | 上下台阶 |
| 12 | sealed-pillar-8 | 1300 | 8³ | 中央柱 |
| 13 | prefill-water-8 | 1400 | 8³ | 预填水 |
| 14 | prefill-sand-8 | 1500 | 8³ | 预填沙 |
| 15 | prefill-chem-8 | 1600 | 8³ | 预填化学 |
| 16 | prefill-mixed-8 | 1700 | 8³ | 预填混合 |
| 17 | prefill-corridor-8 | 1800 | 8³ | 走廊 + 预填 |

## 六、断言的不变量

`NormalPlayState.roundTrip()` 在每个动作收尾执行：

- 密封域：`gasBoundary().auditClosed()` 与 `exactInternalEnergy().equals(thermalAudit().expectedInventory())`。
- 盘上：每次 `checkpointAndUnload` + `restore` 后断言 `operatorPaused`、`lifecycle().state()`、`stepIntervalTicks()` 不变。
- `containerTransfer`：stage 前 live region 不得改变；commit 后 `checkpointGeneration` 推进到已发布 HEAD；adopt 后体积与 staged candidate 精确相等。
- `coldRestart`：`loadMetadata` 能重新发现区域；暂停意图、生命周期状态、步长、`entityPhysicsEnabled`、`debugOverlays` 全部保持。
- 步进/结算/热学步被包装为带 generation、液量、marker 数、固相 SVQ 的 `IllegalStateException`，便于定位。

## 七、确定性模型

- 每条序列由一个 `java.util.Random(seed)` 驱动；动作表按固定权重抽取，RNG 消耗顺序即序列定义。
- `minimize` 用 `RecordingRandom` 为每个动作切出 RNG token 片段，再用 `ScriptedRandom` 严格回放；ddmin 只在 root cause 不变的严格子序列上收敛。
- 副作用：任何修改动作表权重或分支内 RNG 次数的改动都会使旧种子序列失效。

## 八、观测指标

每轮结束打印 `action coverage`、`action hotspots`（按动作类型汇总总耗时/次数/单次均耗时/机械子步）与 `slowest jobs`（最慢单个 job 及其子步数）。`picks` token 提供逐动作耗时与子步数，用于区分「子步多」与「单步贵」。

## 九、覆盖矩阵（供评审找缺口）

已覆盖的生产层：

- 流体求解：压力投影、自由面、FLIP marker 传递、cut-cell 几何、多材料界面（`FluidDomain` 的 step / stepMechanicsOnly / 热学轴）。
- 颗粒：力学、沉积、压实回滚、孔隙水、固液/相变转移（注入、抽取、相变步）。
- 化学：反应引擎、量子分辨率、指示剂泛洪。
- 热学：导热、相变、气相热学、温度与斜坡（未含有限热设备对象）。
- 气体：腔体扫描、拓扑 lineage、孔流、压力、排空、清空、持久化同步。
- 持久化：`RegionRepositoryAdapter` 的 checkpoint / unload / restore / staged lifecycle checkpoint / committed target adoption / `loadMetadata` 冷启动。
- 区域生命周期：pause/resume、`stepIntervalTicks`（ACTIVE/REDUCED）、`operatorPaused` 跨重启。
- 容器转移（loader-neutral 链）：`stageCandidateCheckpoint` → `commitStagedLifecycleCheckpoint` → `adoptCommittedContainerCheckpoint`。
- 实体耦合：1–4 个移动刚体同时作用。

尚未覆盖的层（潜在缺口）：

- 容器转移的 item 层与 WAL：`ContainerTransferUseCase`、`ContainerTransferCoordinator` 的真实 `RegionPort`、`FaultPoint` 注入、`MinecraftContainerTransactionAdapter`、`ContainerItemMapper` / `ItemStack` / `MmmComponents`。
- 容器 recovery：`ContainerRecoveryCoordinator`、`CrossDomainRecovery` 与真实文件/世界状态的组合（目前只有 `ContainerApplicationValidation` 与 `ContainerTransferStoreValidation` 的确定性场景）。
- `MinecraftRegionTick` 的调度与降级：`ensureAvailable`、`unloadForFailure`、`PERFORMANCE_PAUSE` / `NUMERICAL_PAUSE` 的真实触发与恢复、硬超时（`hardOverrunStreak` / `recoveryStreak`）。
- ownership：`OwnershipTransactionCoordinator`、ownership 锁/committed 状态、removal 与 quarantine。
- 世界侧：`ServerLevel`、区块加载、`FluidBoundaryScanner` 的真实边界、方块接管/释放。
- 多区域：`FluidRegionManager` 预算、多区域 gas/network generation 交互（当前每次只驱动单区域）。
- 生命周期：`HYDROSTATIC_SLEEP`（需要真正的静默步进序列）；`REDUCED` 仅通过 coldRestart 改步长触及。
- 客户端/网络：快照发送、渲染、Jade 适配（超出本工具范围）。

此外，现有覆盖还存在「判断能力」缺口：合法操作被静默拒绝时无法判定、拒绝后状态未核对、账本闭合与结果正确之间缺少独立基准比对。这些缺口的成因与补法见 §十二。

## 十二、补覆盖路线图（计划，尚未实现）

优先级来自一次静态评审：**先补现有覆盖的判断能力**，让工具能区分「合法操作被静默拒绝」「应当恢复运行却停住」，比继续堆随机种子更有效。每条给出目标、改动点（file:line）与验收方式。

### W1 拒绝判定与拒绝后状态核对（最高优先）

- 现状：`tryInsertLiquidAt` / `tryInsertChemicalAt` / `tryInsertSandAt` / `tryExtractWaterAt` / `tryExtractChemicalAt` / `tryInsertMixtureAt` / `tryExtractMixtureAt` 返回 `false` 时统一 `skip(...)`（`ControllerInventoryDurabilityValidation.java:641-701`，抽取筛选在 `:1322-1347`）；`indicator` 与 `containerTransfer` 同样只报告失败原因。
- 缺口：若回归让所有注入恒被拒绝，随机分支仍会通过；且拒绝路径不校验状态未变。
- 做法：
  1. 增加「前置条件明确、必须成功」的动作：先按只读访问选定已知容量的目标格，再请求一个确定量并要求 `transferred()`/`inserted()`，失败即抛（现有断言式原语 `:609-640` 是模板）。
  2. 所有 `try*` 的拒绝路径统一核对操作前后状态未变：`totalVolumeExact`、逐材料体积、`particleCount`、`totalGranularSolidVolumeSvq`、`gasBoundary().auditClosed()`。
  3. `skip(...)` 保留具体失败原因（如 `skip(noCanonicalWater)`）并纳入统计，便于识别「某类拒绝比例异常升高」。
- 验收：用临时 mutation 让某类注入必然失败，现有序列必须报 FAIL；撤销后全绿。

### W2 尾量抽取与临界容量

- 现状：三种扩展抽取都先要求附近液量 `>= 8.0`（一桶）才调用分数抽取（`:1322-1327`、`:1330-1334`、`:1339-1343`）；`partialVolume` 仅 32 个按坐标的固定值（`FuzzConfig.java:25`）；酸碱与沙注入仍用整份库存。
- 缺口：亚桶余量、抽到归零、临界容量从未触发。
- 做法：
  1. 抽取改为「读 `exactVolumeNear` → 请求 `min(可用量, 期望量)`」，期望量取自确定集合：`{最小正量, 1 ulp 级 nanos, 1.0, 4.0, 8.0, available, available − ε}`。
  2. 新增闭环动作：抽干 → 倒回等量 → 断言逐材料质量回到基准（复用 W3 的快照比对）。
  3. `partialVolume` 扩充为覆盖整数 nanos/SVQ 最小余量的集合；酸碱与沙也走分数注入路径。
- 验收：闭环后逐材料质量与基准一致；抽取 `available` 时目标格归零且不抛。

### W3 独立状态基准比对

- 现状：`roundTrip()` 只查 gas 闭合与内能闭合（`:853-859`）；容器「live 未改变」只比 `checkpointGeneration`（`:704-706`）；adopt 后只比总液量。
- 缺口：库存与账本同时错误变化仍可能内部自洽。
- 做法：
  1. 增加 `StateSnapshot`：逐材料体积、species 向量、颗粒组 SVQ/质量、载体内能、气体归属（节点 → 体积/标准量/温度/压力/物质）。
  2. `containerTransfer`：stage 前后比对**旧 live 完整快照**；adopt 后比对**恢复态与已提交候选的完整快照**。允许契约规定的派生数据按规则重建（marker ordinal、缓存）。
  3. `roundTrip()` 盘上分支：比对 restore 前后完整快照（当前只比 paused/state/interval）。
- 依赖：优先复用 `captureAuthoritativeState()` 与 `exactVolumeNear` / `ChemicalTransferLedger` / `GranularTransferLedger` / `thermalAudit()` / `gasBoundary()` 等只读访问；不足时补只读快照方法（不改仿真语义）。
- 验收：人为同时篡改库存与对应账本，快照比对必须报 FAIL。

### W4 application 用例与恢复可操作性（新增层）

- 现状：`step` / `thermalStep` 直接构造 `CpuFluidSolverBackend` 并 `adopt`（`:798-826`）；`pause`/`resume` 直接 `region.setPaused`（`:474-483`）；`clear`/`vent` 直接 `domain.commitOperator*`（`:768-777`）。
- 缺口：不经 `RegionApplicationService`、`RegionLifecycleUseCase`、控制台预览确认、真实相变几何处理器；覆盖不到过世代次拒绝、一次性令牌、阻塞条件解除后重试、数值/性能暂停后的清理。
- 做法：新增「应用通道」模式，步进与生命周期操作改走生产用例入口；补**力学休眠与热休眠分别唤醒**各一条：构造入睡条件 → 改变触发条件（注入热源/移动刚体/注入质量）→ 断言用例唤醒并实际推进。
- 验收：临时去掉唤醒分支时必须 FAIL。

### W5 跨 section 持久化

- 现状：所有盘上场景用 `BlockPos.ZERO`、尺寸 ≤ 10 求解单元（`:461`），库存落在单个 Minecraft section。
- 缺口：多 section 的 marker ordinal、side column、气体 lineage 拆分与重组未被触及。
- 做法：把小域平移到 section 交界或负坐标（如 `new BlockPos(-8, 64, 8)`），复用现有场景；roundTrip 快照比对自然覆盖拆分/重组。另补 full profile 之外的合法布局与完整世界能力包启动。
- 验收：跨 section 场景在 `disk` 模式全绿。

### W6 动态世界输入

- 现状：几何只在 `scene.geometry()` 初始化时设置（`:441`）；`temperature` / `rampTemperature` 只改有液格（`:587-607`）。
- 缺口：运行中改变内部障碍、开闭喉道、改恒温源与接触面，再跨保存恢复，均未覆盖；现有生产恒温源的热通量输入也未覆盖（不只有限热设备）。
- 做法：新增运行期几何动作（set/unset 内部实心格、开/闭喉道、改恒温源与接触面），与保存恢复交错。
- 验收：几何改变后按契约重新划分 lineage 且 `auditClosed` 成立；快照比对通过。

### W7 事件级过程验证

- 现状：`FuzzMetrics` 只统计动作命中（`FuzzMetrics.java:25`）；`step(phase)` 成功不等于发生相变，注沙成功不等于经历沉积—唤醒—再抽取；无颗粒抽取动作（`:827` 级刚体每次重随机位置与速度）。
- 做法：从 `candidate.stats()` 采集实际事件计数（相变、颗粒沉积/唤醒、气腔拆/合、化学反应/量子），在热点报告中与「动作次数」并列，区分「动作执行」与「目标过程发生」；补颗粒抽取动作与移动刚体的连续轨迹（进入 → 停留 → 离开）。
- 验收：热点场景报告实际事件计数；对「应当发生却为 0」的场景报 WARN。

### W8 工具自身缺陷（小修）

- `runExplicitSeeds`（`:962-971`）：失败只打印不使进程失败 → 收集后抛出；文档已更正为「走扩展动作表」。
- `runSceneFuzz`（`:1018`）：`future.get()` 无超时 → 改为带超时的 `get(timeout)`，超时记 FAIL 并 `cancel(true)`；超时上限需可配置且足够宽松，避免把已知的长时 job 误判为卡死。
- `FuzzMinimizer`（`FuzzMinimizer.java:109`）：创建的 `NormalPlayState` 未 `close()` → try-with-resources / finally，避免盘上最小化遗留临时目录。

### 建议实施顺序

W1 → W2 → W3 → W4 → W5/W6，其中 W8 可随时独立进行。

## 十、已知限制与性能

- 工具本身的额外开销相对求解可忽略；瓶颈始终是求解。热点排序与实测数据见 `docs/archive/review/fuzz-interrupt-handoff.md`（本地工作区文件，`archive/review` 未入库）。
- 单步耗时失控的实例：`replay,14,41504,150,picks`（单机械子步 1–2.7 s、20–50 子步/动作、单动作可达 52 s）。
- 建议用小规模定向运行（单/相邻场景、4–8 种子、40–60 动作），先看 hotspots 再决定是否扩大；小域（场景 1）`partialdisk` 400 job × 150 动作约 5 分钟。

## 十一、扩展指南

- 新增动作：在 `applyExpandedRandomAction` 增加分支并同步 `FuzzMinimizer.branchName` 的阈值；会改变 RNG 序列，旧种子失效。
- 新增场景：在 `FUZZ_SCENES` 增加 `FuzzScene`，必要时补 prefill；`disk` 场景必须密封。
- 新增不变量：优先放进 `NormalPlayState.roundTrip()` 或对应操作原语，失败信息带上 generation 与账本量。
- 建议的下一步拆分：把场景数据与 `NormalPlayState`（含操作原语）移出入口类，动作表独立成文件，使入口只剩模式与调度。
