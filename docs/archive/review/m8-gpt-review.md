# M8 代码与架构静态审阅（GPT）

审阅范围：`b611e02c^..4566b95`，即包含 `b611e02c Freeze M8 conservative granular design` 本身，直到当前
`HEAD` `4566b95 Close M8 granular milestone`。该范围共 29 个提交、87 个变更文件，约 `+9,903/-204` 行，覆盖
颗粒定义与 registry、active parcel/deposit、固液耦合、region-state v5、容器与 WAL v4、protocol v4、operator
清空以及实机后续修正。

审阅基线：根 `README.md`；`docs/architecture/fluid-solver.md`、`world-ownership-and-containers.md`、
`region-state-v1.md`、`chemistry-state.md`、`granular-state.md`；`docs/maintenance/routing.md`；
`docs/roadmap/product-roadmap.md`；并交叉检查 `docs/progress/m8-granular-deposition.md` 和
`docs/roadmap/gases-and-granular.md`。

审阅方式：静态检查完整提交清单和生产调用路径，优先反查独立真值、plan/commit、联合 exact occupancy、
registry identity、v5 capture/readback/restore、ownership release、container WAL 和 operator 路径。按要求没有运行
Gradle、自动门或实机场景；既有通过记录只作为历史证据，不替代本次代码推理。

## 结论

M8 的总体分层方向正确，但当前不能判定为“万无一失”，也不建议以现有 `HEAD` 直接把 M8 当作无遗留边界进入 M9。
本次确认 **5 项高风险、1 项中风险、1 项低风险**：

1. precipitation commit 绕过联合 exact-occupancy 容量计划；
2. 内置 NaCl 沉淀的权威质量/体积给出约 `498.216` 的实际密度，却声明 `2800`，loader 也没有执行冻结的声明体积校验；
3. v5 对“只剩反应性 source/sink audit”的 capability 写入、验证和 registry identity 门不一致；
4. `/realwater remove` 只检查液体，可成功移除仍持有固体库存的 controller 区域；
5. 世界碰撞刷新遇到颗粒占据时抛出未捕获异常，无法真正拒绝已经发生的方块编辑；
6. `/realwater empty` 先清液体、后预检固体 audit，不是一次联合 plan/commit；
7. `fluid-solver.md` 仍把已经完成的 M8 写成“开发中、没有 gameplay/config/persistence 入口”。

前五项涉及冻结的物理真值、持久化/恢复或生产世界行为，建议作为 M8 重新关闭前的阻断项。修复时不需要新增
Gradle task 或常态 selector；按 routing 扩展已有最近门即可。

## 主要发现

### 1. [高] precipitation 在提交液体/化学真值前没有计划相同的 8 格 exact occupancy

位置：

- `docs/architecture/granular-state.md:91-104,131-140,195-215`
- `src/main/java/com/example/fluid/FlipFluidDomain.java:2905-2943`
- `src/main/java/com/example/fluid/FlipFluidDomain.java:2946-2978`
- `src/main/java/com/example/fluid/FlipFluidDomain.java:3122-3148`
- `src/main/java/com/example/fluid/FlipGranularCollision.java:82-120`
- `src/main/java/com/example/granular/GranularCouplingFields.java:87-104`

冻结契约要求 creation/phase transfer 在提交前使用生产 rebuild 完全相同的最多 8 格 fixed-weight + largest-remainder
分配检查 `S_i <= O_i`，capacity 失败时液体和固体双方逐位不变。dry insert、active compaction、settlement、relax 和
wake 已经采用该路径。

`commitPrecipitation` 却只调用 `spawnPosition` 检查球体/cut-cell/deposit 碰撞。新 parcel 分支在 pool/parcel count
可用后直接提交 chemical replacements、marker volume 和 active parcel；merge 分支只以
`granularCollision.positionValid` 检查合并后球体。两条分支都没有：

- 从当前 `granularCoupling` 复制联合 occupancy；
- merge 时先移除旧 parcel 的 8 格 shares；
- 以 `tryPlanParcel(..., this::granularGeometricOpenSvq)` 检查新/合并 parcel 的真实 shares。

这不是只在损坏状态下发生。沉淀从 carrier marker 所在格扣除液体体积，但代表 parcel 的 trilinear stencil 可以把部分
solid SVQ 分到相邻格；相邻格可能已被 active/deposit/cut-cell capacity 占满。此时 origin cell 的液固总体积仍守恒，
但邻格 `S_i` 会超出 `O_i`。本步后面的 `granularOccupancyFitsGeometry` 才发现问题并抛
`IllegalStateException`；`FluidSimulationService.tickRegion` 只捕获 phase-transfer `BlockedException`，因此它不会变成
所承诺的可读 `granular-phase-blocked PAUSED`，而可能直接逃出 server tick。液体 species/mass/volume 与固体已经先行提交。

建议：把 precipitation 的 create/merge 都纳入一个 occupancy candidate。create 直接 try-add；merge 先 exact remove
旧位置再 try-add 合并位置，失败时只丢弃 candidate 并在任何 chemical/marker/pool 修改前抛 `BlockedException`。
checkpoint capture/restore 也应复核 active+deposit 联合 occupancy，避免内部 invariant 错误被写成合法 v5。

### 2. [高] 内置沉淀的实际密度与材料声明相差约 5.62 倍，冻结的 rule volume 校验没有实现

位置：

- `docs/architecture/granular-state.md:33-35,70-76,106-124`
- `src/main/java/com/example/granular/GranularRegistry.java:106-125`
- `src/main/java/com/example/granular/GranularPhaseTransferEngine.java:56-100`
- `src/main/java/com/example/granular/GranularMechanics.java:42-60`
- `src/main/resources/data/realwater/realwater/species/na_plus.json`
- `src/main/resources/data/realwater/realwater/species/cl_minus.json`
- `src/main/resources/data/realwater/realwater/solid_liquid_reactions/sodium_chloride.json`
- `src/main/resources/data/realwater/realwater/particulate_materials/precipitate.json`

正式架构要求 loader 在 world snapshot 首次发布前拒绝质量、组成、电荷或声明体积不守恒的固液规则，并明确说
`referenceDensity` 用于创建和校验。当前 `validateReactions` 只验证 phase/material/species 引用、reactive 标志、两侧
species map 相等和竞争 consumer；没有把 rule 的 exact species mass、`solid_volume_svq_per_extent` 与材料参考密度交叉
校验。

当前内置 rule 每 extent 搬运 64 个 Na+ quanta 和 64 个 Cl- quanta，M7 mass 分别为 184、280 MU/AU，且
`1 AU = 1e9 quanta`：

```text
mass / extent              = 64 * (184 + 280) / 1e9 = 2.9696e-5 raw mass
cell volume / extent       = 1 SVQ = 2^-24 cell volume
production implied density = 2.9696e-5 * 2^24 = 498.216206336
declared reference density = 2800
```

生产 `GranularMechanics` 使用 parcel 的实际 `mass / solidVolume` 计算重力、浮力和阻力，不会用 `referenceDensity`
覆盖历史质量。因此该 NaCl “precipitate”在 1000 密度的水中会按约 498 的轻质颗粒响应，而不是按声明的 2800
沉降；现有 terminal-velocity 证据使用的是按 reference density 人工创建的 parcel，不能证明 reaction-created parcel。

建议：先明确 rule 的允许量化误差，再在 `GranularRegistry` 首次构造时用 exact-rational mass 和 SVQ scale 校验每个
reactive material 的声明密度/体积关系；当前内置系数和 `solid_volume_svq_per_extent` 必须随之修正。不要在运行时把
已生成 mass 按 density 重算，那会破坏独立真值和可逆溶解。

### 3. [高] v5 的反应性 audit-only 状态既无法正常发布，也没有完整 identity fail-closed

位置：

- `docs/architecture/granular-state.md:28-35,226-249`
- `src/main/java/com/example/simulation/persistence/RegionState.java:331-345`
- `src/main/java/com/example/simulation/persistence/RegionState.java:541-551`
- `src/main/java/com/example/simulation/persistence/RegionState.java:877-888`
- `src/main/java/com/example/granular/GranularStateSnapshot.java:155-187`
- `src/main/java/com/example/granular/GranularStateSnapshot.java:252-266`

writer 在以下任一条件成立时写 `CAP_PRECIPITATION`：live `solidCompositions` 非空、external source 含 reactive audit、
escaped sink 含 reactive audit。reader/readback 却要求：

```java
capabilityPrecipitation == !expected.solidCompositions().isEmpty()
```

因此正常生产路径就能制造无法发布的合法 candidate：区域产生过反应性沉淀后执行 `/realwater empty`，live composition
table 归零，完整 composition/species/mass 被移入 escaped sink。capture 设置 precipitation capability，随后的
`RegionStateFiles.stage`/readback validation 以 capability mismatch 拒绝。`emptySelectedRegion` 只能暂停已清空的 live
solver，并明确提示重启可能恢复旧库存。创造 source 后再完整抽取也能留下同类 audit-only 状态。

反向还存在 fail-open 缝隙：若外部 payload 在 audit-only 状态把 capability 置为 false，当前 validation 反而通过；
`reconstructGranular` 和 `restoreInto` 只在 live composition table 非空时比较 stored chemical fingerprint。audit
`validateAgainst` 会按当前 registry 的 stable species 重新解析，却不比较 snapshot 中的 chemical fingerprint。只要 stable
ID/mass 恰好仍可验证，identity 不明的 audit 可能被当前 chemical registry 解释，违反“fingerprint/身份不明必须保留并
LOCKED”。

建议：定义唯一的 `requiresReactiveGranularPayload` 条件，至少覆盖 live composition、external source、escaped sink，
并在 encode、validate、reconstruct、restore 全部复用。该条件为 true 时必须同时要求 chemistry capability、region chemical
state、stored chemical fingerprint 和 granular snapshot 冻结的 chemical dependency 完全一致；任一不符都保留 generation
并 LOCKED。

### 4. [高] `/realwater remove` 可以删除仍持有固体库存的 controller 区域

位置：

- `docs/architecture/world-ownership-and-containers.md:14-24`
- `docs/architecture/granular-state.md:28-35,245-249`
- `src/main/java/com/example/simulation/FluidRegionPersistence.java:251-286`
- `src/main/java/com/example/simulation/FluidRegionOperations.java:188-208`
- `src/main/java/com/example/simulation/FluidSimulationService.java:1567-1590`

ownership release 只在“区域精确排空”时允许。当前 `releaseOwnership` 的唯一库存门是：loaded region 检查
`domain.totalVolume()`，unloaded region 检查 `checkpointVolume()`；两者都没有检查 active/deposit solid SVQ。并且 unloaded
region 的检查发生在 `restore(region)` 之前，所以 metadata 也没有机会读取/核对 granular inventory。

流体为零而固体非零时，release 会提交一个仍含固体的 v5 generation、恢复 vanilla preimage、删除 ownership sidecar；
随后 `deletePersistentRegions` 把 region-state 移入 quarantine，`regionManager.clear()` 清掉活动登记并向客户端发 clear-all。
命令报告成功，但这些固体既没有被拒绝，也没有像 `/empty` 那样进入 escaped sink。quarantine 使字节可能仍可人工恢复，
不改变该命令已经从权威世界语义中静默移除固体库存的事实。

建议：release 前先确保 region 已完整 restore/admit，再同时要求 liquid marker volume 为零、active parcel 为零、deposit
group/SVQ 为零；任一非零都拒绝并提示先执行 `/realwater empty`。不要只给 metadata 增加一个可被误当成第二真值的
display count；若为 unloaded 快速拒绝增加 summary，也必须在真正 release 前以完整 v5 重建复核。

### 5. [高] 方块碰撞刷新对颗粒覆盖的“拒绝”发生得太晚，并以未捕获异常逃出 tick

位置：

- `docs/architecture/granular-state.md:131-140,163-188,217-224`
- `src/main/java/com/example/fluid/FlipFluidDomain.java:1194-1257`
- `src/main/java/com/example/simulation/FluidRegionWorldInteraction.java:110-187`
- `src/main/java/com/example/simulation/FluidSimulationService.java:1347-1393`

玩家在 controller 内放置有碰撞形状的方块后，Minecraft 世界修改已经发生；下一次
`FluidRegionWorldInteraction.refreshCollisions` 才把新 `VoxelShape` 写入 solver。`setSolidMicroMaskInternal` 发现目标格
存在任何 active/deposit occupancy 时抛 `IllegalStateException("Cannot overwrite granular inventory...")`，不会修改该格
solver mask。

这不能实现进度文档所称的“碰撞编辑在改动前拒绝”：世界方块已改变，solver 仍保留旧几何和原颗粒。更严重的是
collision refresh 位于 `tickRegion` 的 `try` 块之前，异常既不会暂停 region，也不会形成可读 granular blocked 状态，
而会逃出 server tick；下一次刷新仍会遇到同一差异。扫描在命中该格以前还可能已提交其他格的 mask/液体 displacement，
所以一次 refresh 也不是全域原子的。

建议在产品语义中二选一并完整实现：

- 真正拒绝编辑：在 Minecraft block placement/break 提交前拦截覆盖 granular truth 的操作；或
- 接受玩家方块优先：先规划受影响 active/deposit 的局部搬移、附近恢复和剩余 escaped sink，再联合提交 collision 与账本。

无论选择哪一种，周期 refresh 都不能用未捕获异常维持世界/solver 分歧；至少应安全锁定 region、保存可读原因并避免
重复 server-tick 崩溃。

### 6. [中] `/realwater empty` 的液体与固体清除不是一次联合 plan/commit

位置：

- `docs/architecture/granular-state.md:245-266`
- `src/main/java/com/example/fluid/FlipFluidDomain.java:301-327`
- `src/main/java/com/example/fluid/FlipFluidDomain.java:1628-1640`
- `src/main/java/com/example/simulation/FluidSimulationService.java:1627-1651`

`emptySelectedRegion` 依次执行 `removeAllWaterExact()`、`removeAllGranularExact()`，之后才进入 checkpoint 的
`try/catch`。固体方法虽正确地在清 inventory 前 capture、计算 `granularEscapedSink.plus(removed)`，但这些预检发生在液体
已经清空并写入 chemical escaped sink 之后。

若 solid composition ownership 校验、audit `long`/species 累加、mass finite 校验或其他固体 preflight 失败，命令会在
checkpoint `try/catch` 之前抛出：液体已从 live solver 删除，固体仍在，region 也没有由该路径安全暂停。累计 audit 的
checked arithmetic 正是冻结硬预算的一部分，不能假定永不失败。该路径因此不满足“所有预算在部分提交前验证”和一次
完整 v5 clear 的语义。

建议增加窄的 region-clear plan：先生成 liquid/chemical sink candidate、granular sink candidate、composition release
清单和完整 v5 可捕获性检查；全部成功后再一次提交两类 inventory clear，最后 stage/verify/publish HEAD。无需把它扩展成
通用事务框架。

### 7. [低] `fluid-solver.md` 的 M8 边界已经过期并与权威格式说明冲突

位置：

- `docs/architecture/fluid-solver.md:19-22`
- `docs/architecture/granular-state.md:226-249`
- `docs/roadmap/product-roadmap.md` 的 M8/M9 状态

`fluid-solver.md` 仍写着“**M8 开发中边界**”“当前尚无 gameplay/config/persistence 入口，v1/v2/v3 格式未改变”。
当前产品已经有 granular/precipitation config v5、region-state v5、protocol v4、container WAL v4、干式容器玩法与 operator
清空。后半句关于“不回填 v1/v2/v3”仍正确，但整段会误导 M9 的跨层设计和日常 routing。

建议改成 M8 已完成边界，明确 region-state v5、protocol v4、container WAL v4 相互独立，v4 region-state 仍只为气体
评审预留；不要顺手把 M9 热状态写进任何现有 M7/M8 格式。

## 已核对且未发现回退的边界

以下结论只表示本次静态审阅没有发现反例，不等同于对所有输入的形式证明：

- CPU 仍是唯一权威求解器；GPU probe 没有进入 M8 提交路径。
- solid SVQ、raw mass、solid composition/species、external source/escaped sink 保持独立类型和账本；display/tooltip
  没有参与结算。
- active parcel + deposit cell 是生产模型，没有预建 MPM/DEM backend；porosity 只决定 bulk/渗透派生量，pressure
  capacity 仍扣 skeleton。
- dry insert、active compaction、settlement、relax、wake 已复用联合 occupancy planning；本报告 finding 1 是
  precipitation 分支遗漏同一 closure，不是要求更换低成本模型。
- region-state 使用完整 v5 generation，region-state v4 仍保留并拒绝；没有向 M7 v3 或气体预留 v4 回填颗粒字段。
- container WAL v4 区分 `FLUID_NANOS`/`GRANULAR_SVQ`，PREPARED、实际 after/checksum、HEAD、actor durable save、
  delete 的主顺序保持；v1/v2/v3 reader/recovery 仍有独立分支。
- 玩家/发射器颗粒入口要求 controller-managed 实际区域和 `item count == 1`；创造放出走 granular external source，
  scoop 不走隐藏 sink。
- protocol v4 只发送有界显示数据，没有发送 exact SVQ、mass、species 或 runtime composition handle。

## 建议的最小关闭顺序

本轮没有执行测试。修复后按 `routing.md` 使用现有 selector 即可，不建议新增长期常态门：

| 顺序 | 修复范围 | 最近门 |
|---|---|---|
| 1 | precipitation occupancy + rule density/volume validation | `granular,chemistry`；若修改 shared cut-cell capacity，再加 `cut-cell` |
| 2 | v5 audit-only capability/identity | `region-state,granular` |
| 3 | remove/release 固体排空门 | `ownership,region-state,granular` |
| 4 | block/cut-cell 与 granular truth 的产品语义 | `granular,shape,cut-cell`；若改 pressure shared face，再加 `free-surface` |
| 5 | empty 联合 clear | `granular,chemistry,region-state,containers` |
| 6 | 文档同步 | 无自动门 |

上述高风险项关闭、最近门通过并按现有 release 文档完成一次必要的真实 controller 方块编辑/重启验证后，才适合重新写
M8 关闭结论；不应以渲染补丁、当前 datapack 重解释或 density 回填历史 mass 掩盖这些问题。
