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

权威阈值表在 `FuzzConfig.actionName(int)`：`applyExpandedRandomAction` 按名称 `switch` 分派，`FuzzMinimizer.branchName` 直接复用它，因此两者不会漂移。下表是当前档位与物理含义（`pick = random.nextInt(100)`）。

| pick | 动作 | 次数/参数 | 物理含义 |
|---|---|---|---|
| 0–3 | `insertWater` | 1 | 单材料液体注入 |
| 4–7 | `insertMercury` | 1 | 高密度液体 |
| 8–11 | `insertOil` | 1 | 低密度液体 |
| 12–17 | `insertAcid` | 1–2 | 化学桶注入（HCl + 水） |
| 18–23 | `insertBase` | 1–2 | 化学桶注入（NaOH + 水） |
| 24–27 | `insertSand` | 1 | 颗粒注入 |
| 28–31 | `burst` | 2–9 | 同一材料的连续注入（水/汞/酸/碱/沙） |
| 32–34 | `insertMixture` | 1 | 多材料混合桶（水+汞+油） |
| 35–38 | `step(motion)` | 1 | 纯力学步 |
| 39–42 | `step(phase)` | 1 | 带相变的力学步 |
| 43–47 | `steps(phase)` | 2–5 | 连续相变步 |
| 48–50 | `steps(motion)` | 2–6 | 连续力学步 |
| 51–54 | `temperature` | 1–3000 K | 全液体格绝对温度 |
| 55–57 | `rampTemperature` | ±5/±50/±300 K | 相对温度斜坡 |
| 58–60 | `thermalStep(motion)` | 1 | 仅热学步 |
| 61–63 | `thermalStep(phase)` | 1 | 仅热学步（带相变） |
| 64–67 | `step(movingBody)` | 1–4 刚体 | 同时作用多个移动刚体 |
| 68–69 | `pause` | — | 盘上：设置操作者暂停 |
| 70–71 | `resume` | — | 盘上：清除暂停 |
| 72–73 | `saveWhilePaused` | — | 盘上：暂停后 `persistence.checkpoint` |
| 74–76 | `containerTransfer` | — | 盘上：私域 candidate → stage → commit HEAD → adopt |
| 77–78 | `coldRestart` | — | 盘上：`checkpointAndUnload` → `loadMetadata` → `restore`；随机改步长与运行时意图 |
| 79–81 | `extractWater` | 分数/8 | 抽取水 |
| 82–84 | `extractChemical` | 分数/8 | 抽取化学液体 |
| 85–86 | `extractMixture` | 分数/4 | 抽取混合液体 |
| 87 | `extractTail` | `nextDown(可用量)` | 抽到目标格归零的边界抽取，必须成功 |
| 88 | `extractOverCapacity` | 略超可用量 | 临界容量，拒绝时核对状态未变 |
| 89 | `extractSubBucket` | 1e-9–7.999 m³ | 亚桶/最小余量抽取 |
| 90 | `extractThenRestore` | 8.0 m³ | 抽一桶再倒回，纯水域上逐材料质量回到基准 |
| 91 | `insertMinimalVolume` | 最小体积档 | 极小体积注入；空注入格上拒绝即失败 |
| 92 | `toggleSolid` | — | 运行期翻转内部碰撞格（世界编辑） |
| 93 | `editBoundarySource` | — | 改世界恒温边界场单个面 |
| 94 | `applicationStep` | — | 经 `RegionApplicationService` 步进，并验证世代令牌单次有效 |
| 95 | `sleepWake` | — | 力学休眠与热休眠各自闩锁后分别唤醒 |
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
| 18 | sealed-netsplit-8 | 1900 | 8³ | 基线上移到 `(-2,64,-2)`，库存跨 section 边界 |
| 19 | sealed-netsplit-corridor-8 | 2000 | 8³ | 走廊上移到 `(-2,64,-2)`，跨 section |

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

每轮结束打印 `action coverage`、`process coverage`（真实发生的求解事件：相变、颗粒沉积/唤醒、化学求值、重播种合/分）、`action hotspots`（按动作类型汇总总耗时/次数/单次均耗时/机械子步）与 `slowest jobs`（最慢单个 job 及其子步数）。`picks` token 提供逐动作耗时与子步数，用于区分「子步多」与「单步贵」。

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

## 十、已知限制与性能

- 工具本身的额外开销相对求解可忽略；瓶颈始终是求解。热点排序与实测数据见 `docs/archive/review/fuzz-interrupt-handoff.md`（本地工作区文件，`archive/review` 未入库）。
- 单步耗时失控的实例：`replay,14,41504,150,picks`（单机械子步 1–2.7 s、20–50 子步/动作、单动作可达 52 s）。
- 建议用小规模定向运行（单/相邻场景、4–8 种子、40–60 动作），先看 hotspots 再决定是否扩大；小域（场景 1）`partialdisk` 400 job × 150 动作约 5 分钟。

## 十一、扩展指南

- 新增动作：在 `applyExpandedRandomAction` 增加分支并同步 `FuzzMinimizer.branchName` 的阈值；会改变 RNG 序列，旧种子失效。
- 新增场景：在 `FUZZ_SCENES` 增加 `FuzzScene`，必要时补 prefill；`disk` 场景必须密封。
- 新增不变量：优先放进 `NormalPlayState.roundTrip()` 或对应操作原语，失败信息带上 generation 与账本量。
- 建议的下一步拆分：把场景数据与 `NormalPlayState`（含操作原语）移出入口类，动作表独立成文件，使入口只剩模式与调度。

## 十二、补覆盖路线图（W1–W8 已实现，2026-09-14）

优先级来自一次静态评审：**先补现有覆盖的判断能力**，让工具能区分「合法操作被静默拒绝」「应当恢复运行却停住」。以下为落地结果，权威阈值表已收敛到 `FuzzConfig.actionName(int)`（`applyExpandedRandomAction` 与 `FuzzMinimizer` 共用，不再各自维护）。

- **W1 拒绝判定**：全部 `try*` 拒绝路径用 `FluidDomainStateAssertions.assertSameState` 核对操作前后状态未变；新增前置条件明确、要求成功的动作 `insertMinimalVolume`（注入点为空格子时拒绝即判失败）。验收：`insertMinimalVolume` 在空注入格上的断言即为常驻判据。
- **W2 尾量抽取**：新增 `extractTail`（请求 `nextDown(可用量)`，必须成功）、`extractOverCapacity`（请求略超容量，拒绝则核对状态未变）、`extractSubBucket`（1e-9–7.999 m³ 档）、`extractThenRestore`（抽一桶再倒回，逐材料质量/体积回到基准）。闭环仅在**纯水**域上断言（`holdsOnlyWater`）；混合域上抽水会带走溶质、倒回纯水，组分本就不对称。
- **W3 独立比对**：`roundTrip` 盘上与内存分支、`coldRestart`、`containerTransfer` 的 live/adopt 全部改为 `FluidDomainStateAssertions.assertSameDurableState`（持久层不含调度队列、每步压力草稿与世界边界温度场，这些按契约在载入时重建）。**该断言已实际命中一处契约事实**：化学 dirty-cell 队列不入存档。
- **W4 application 通道**：`applicationStep` 经真实 `RegionApplicationService.stepMotion` 步进，并复用同一世代令牌复交以断言 `STALE_GENERATION`；`sleepWake` 驱动力学休眠与热休眠各自闩锁再分别唤醒（力学走 `observeCompletedStep`，热学走 `ThermalActivity.Observation`），断言两个轴独立。
- **W5 跨 section**：`FuzzScene` 增加 `origin`，新增场景 18/19 位于 `BlockPos(-2, 64, -2)`，库存跨 section 边界。
- **W6 动态世界输入**：新增 `toggleSolid`（运行期改内部碰撞格，位移计入 escaped sink）与 `editBoundarySource`（改世界恒温边界场的一个面）。
- **W7 事件级验证**：从 `StepStats`/`ThermalAxisStats` 采集实际事件（相变、颗粒沉积/唤醒、化学求值、重播种合/分），新增「process coverage」报告，并对未发生的关键过程打 WARN。
- **W8 工具缺陷**：显式种子模式失败改为抛错；扫描等待加可配置超时 `-Dmmm.fuzzJobTimeoutMillis`（默认 30 分钟/job）；`FuzzMinimizer` 回放与记录路径均 `close()` 临时目录。

仍未覆盖：容器 WAL 故障注入与 recovery、ownership/removal、`ServerLevel` 侧接管与区块加载、多区域交互、客户端/网络；`MinecraftRegionTick` 的 pacing/`recordStep`/快照需服务器，`RegionApplicationService` 不经过它。

