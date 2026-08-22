# M6 世界 Ownership、世界桥与容器执行计划

## 0. 文档定位

本计划用于指导另一个 AI 完成 Minecraft Java 26.2 Fabric Real Water 的 M6。

它不是新的架构规范。执行过程中始终以以下顺序重新获取当前事实：

1. `docs/README.md`
2. `docs/maintenance/routing.md`
3. `docs/architecture/fluid-solver.md`
4. `docs/roadmap/product-roadmap.md`
5. 仅进入 M6 对应的 `docs/roadmap/world-integration.md`
6. 一旦触及持久化，再读 `docs/architecture/region-state-v1.md`
7. 一旦需要引用已经完成的验收，再从 `docs/progress/README.md` 进入对应记录

禁止从 `docs/archive/`、旧对话或本计划中的历史文件名反推当前代码位置。

---

# 1. M6 的真正目标

M6 不是进一步修改 FLIP 水动力本身。

M6 要解决的是：

> 如何让已经存在的 CPU 权威流体域，对 Minecraft 世界中的一片空间建立持久、可恢复、可验证的 ownership，并在这个 ownership 上安全实现世界水与容器之间的精确物质转移。

路线图规定两个硬阶段：

## M6.0：Ownership 设计评审

必须比较两个可丢弃原型：

* 代理占位方块；
* 空气 + 独立 ownership registry。

比较维度：

* Minecraft 世界机制兼容性；
* 其它 Mod 的可观察行为；
* 玩家编辑；
* chunk 保存/加载；
* 崩溃恢复；
* 存档体积；
* 运行期开销；
* 原版 fluid tick / neighbor update；
* ownership 与 region-state generation 的一致性。

M6.0 必须最终冻结：

* ownership 表示；
* world mutation 规则；
* region-state / ownership / world replacement 的共同 generation；
* acquire 状态机；
* crash recovery 状态机；
* release 状态机；
* 格式迁移；
* downgrade / feature gate 行为。

**M6.0 未通过，禁止实现正式 M6.1 gameplay。**

## M6.1：世界桥与容器

只有 M6.0 的 ownership 决策通过后，才实现：

* controller 范围持久 ownership；
* 纯水真实流动；
* 满纯水桶；
* 部分纯水容器；
* 多材料/混合物容器；
* 玩家交互；
* 发射器；
* 合并配方；
* 漏斗/掉落/死亡/断线；
* 创造模式源汇；
* 崩溃一致性；
* 最终真实世界 smoke。

路线图本身要求 M6.1 覆盖这些容器和世界路径。

---

# 2. 五层状态绝不能混淆

每个 M6 bug、设计和测试都要明确属于哪一层。

## 2.1 物理真值

液体真值：

```text
Σ marker.particleVolume
```

M3 材料门开启后还存在独立的逐材料质量账本。

不得用：

* density；
* `volume[]`；
* PLIC fraction；
* renderer 水面高度；
* Item damage bar；

结算精确库存。

当前 active CPU architecture 已明确 marker `particleVolume` 总和是液体真值，而 `markerCount > 0` 决定压力流体格。

## 2.2 数值离散

包括：

* `markerCount`
* 平滑 `volume[]`
* PLIC
* cut-cell
* 压力
* 曲率
* 表面张力
* 气体压力边界

这些可以影响运动，但不能重新定义“有多少水”。

## 2.3 渲染重建

客户端仍然：

```text
density + marker + solid
```

marker 是浅水可见的必要种子。

M6 不得通过：

* 增厚 renderer；
* 隐藏 proxy；
* 强制液面；
* 人工补水；

掩盖 marker 或 ownership 结构退化。

## 2.4 调度

ACTIVE / REDUCED / sleep / UNLOADED 只影响计算时间。

不得改变：

* marker 总量；
* ownership；
* container contents。

## 2.5 世界事务

M6 新增的核心层。

包括：

```text
region-state
ownership
world blocks
container inventory
transition journal/WAL
```

“当前世界看起来没问题”并不代表事务已经提交。

---

# 3. 当前代码带来的关键事实

## 3.1 `FlipFluidDomain` 不应成为 M6 主战场

活动 CPU 核心继续是：

```text
src/main/java/com/example/fluid/FlipFluidDomain.java
```

旧 `FluidDomain.java` 不接回游戏。

M6 世界 ownership 不应进入 solver。

solver 最多增加：

* 精确 extract primitive；
* 精确 insert primitive；
* material-aware transfer primitive。

不应增加：

* Minecraft `setBlock`；
* journal；
* ItemStack；
* chunk ownership。

---

## 3.2 当前 controller 还不是真 ownership

当前 controller 激活路径：

1. 扫描闭合边界；
2. manager admission；
3. 创建 region；
4. 采样原版水为 marker；
5. 初始 checkpoint；
6. 保持 paused。

现有代码在 `FluidSimulationService.activateController()` 中明确先 capture vanilla water，再 checkpoint。

但是：

**原版水没有因为 controller 激活而被持久移除。**

所以当前 controller 实际状态是：

```text
Minecraft vanilla water
+
Real Water marker representation
```

只是模拟默认暂停，所以尚未成为正式“双重运动水”。

M6 的第一件正式工作就是消除这种双 ownership。

---

# 4. M6 最大事务风险：M0 Restore 不能直接升级成长期 Ownership

当前 takeover 的安全语义非常好：

1. 先发布 journal；
2. 再发布 WARNING；
3. 才修改世界；
4. restore 时不覆盖玩家实体方块。

当前实现确实严格按照这个顺序。

世界 adapter 也只接受 journal 中原本就是纯 `Blocks.WATER` 的状态，恢复时遇到玩家实体块会 `SKIPPED_OCCUPIED`。

但是 M6 有一个根本区别。

假设初始水池：

```text
AAAA
AAAA
```

Real Water 接管后，水 marker 流成：

```text
   AA
 AAA
A
```

此时如果 crash recovery 又恢复最初 `AAAA/AAAA` 的 vanilla 水：

```text
markers 当前水
+
原始 vanilla 水
```

就复制了水。

因此 M6 必须把三种行为彻底分开：

### PRE-COMMIT rollback

ownership 尚未正式提交：

```text
恢复原始 vanilla water
```

合法。

### POST-COMMIT recovery

ownership generation 已经提交：

```text
完成/修复 ownership 表示
```

但：

```text
绝对不能恢复原始 water preimage
```

### Explicit release

玩家明确解除 Real Water ownership。

这是另一套事务，不能叫 crash restore。

---

# 5. M6.0 第一阶段：建立统一实验 Fixture

两个 ownership 原型必须使用完全相同的测试世界。

推荐一个小型 controller：

* 跨 2–4 个 chunk；
* 一部分纯 vanilla source water；
* 一部分空气；
* 一块玩家实体方块；
* 沙；
* 砾石；
* 红石；
* neighbor-sensitive block；
* piston；
* 与区域相邻但边界外的 vanilla water；
* 明确 chunk border。

记录：

```text
region UUID
checkpoint generation
marker count
exact particleVolume
vanilla WATER block count
ownership entries/proxy count
save size
world writes / 200 ticks
```

再分别记录：

* player edit；
* piston；
* explosion；
* `/fill`；
* `/setblock`；
* structure；
* chunk unload/reload；
* crash；
* boundary break。

两个原型不允许使用不同 fixture。

---

# 6. M6.0 原型 A：Proxy Placeholder Block

## 6.1 目的

用一个 Real Water ownership block 明确告诉 Minecraft：

> 这个 block position 不再由 vanilla fluid 管理。

这个 block：

* 不是水；
* 不保存水量；
* 不参与 FLIP 数值；
* 只表示 ownership。

## 6.2 关键实验

必须测：

* placement；
* breaking；
* piston push/pull；
* explosion；
* light；
* pathfinding；
* collision；
* neighbor updates；
* fluid tick；
* falling sand/gravel；
* `/fill`；
* structure；
* chunk save/load；
* common mod block scanning。

## 6.3 硬限制

Proxy 绝不能跟随液面移动。

错误设计：

```text
cell wet -> set proxy
cell dry -> set air
```

因为这样 ownership 变成了 numerical reconstruction。

正确：

```text
controller-defined ownership region
```

稳定时：

```text
world writes per physics step = 0
```

---

# 7. M6.0 原型 B：Air + Independent Registry

## 7.1 目的

World block 保持 air。

Real Water 自己维护：

```text
(dimension, position)
    -> region UUID
    -> generation
```

Minecraft world 与 registry 必须能够检测不一致。

## 7.2 必须调查的事件覆盖

逐项列出实现方式：

```text
player set block
break
bucket
fluid scheduled tick
neighbor update
piston
explosion
command block placement
structure placement
chunk load
chunk unload
external mod setBlock
```

每项明确属于：

```text
Fabric API
vanilla override
mixin
无法可靠捕获
```

如果最终需要大量 mixin 才能阻止其它系统在 owned air 中自由写入，这条路线应被强烈否决。

---

# 8. 两种原型共同硬门

## 8.1 稳态零 ownership world writes

FLIP 水面变化：

```text
不得修改 ownership
```

只有：

* ownership acquire；
* release；
* 玩家 world edit；
* transaction recovery；

才能改 ownership。

## 8.2 玩家方块优先

实际世界有实体块：

```text
保留实体块
```

Real Water：

* 刷新 collision；
* 如必要暂停；
* 记录 conflict。

绝不“恢复 proxy”覆盖玩家。

## 8.3 整域 generation

同一 controller：

```text
region UUID
region generation
ownership generation
world transition target generation
```

必须一致。

缺片：

```text
whole region paused
```

不能：

```text
load 7/8 chunks and treat missing chunk as wall
```

## 8.4 未知格式 fail closed

不能：

```text
unknown ownership -> ignore -> continue solver
```

必须：

```text
LOCKED
```

---

# 9. 强烈建议冻结的 M6 状态机

推荐语义：

```text
LEGACY_CONTROLLER
PREPARED
OWNED_COMMITTED
RELEASING
LOCKED
```

## LEGACY_CONTROLLER

当前 v1/v2 controller。

还未正式接管 Minecraft 世界。

## PREPARED

完整目标 generation 已经写好并验证。

world transition WAL + WARNING 已持久化。

但是：

```text
HEAD 还没有指向新 generation
```

## OWNED_COMMITTED

最后 commit point 已发布。

marker + ownership 是权威。

旧 vanilla water preimage 失去物理权威。

## RELEASING

显式 release transaction。

期间：

* region paused；
* 禁止 container transfer；
* 禁止新 ownership mutation。

## LOCKED

任何：

* unknown；
* corrupted；
* mixed generation；
* over-budget；
* WAL mismatch；
* player conflict 无法自动解决；

进入 LOCKED。

---

# 10. Ownership Acquire 的正确提交顺序

这是整个 M6 最重要的实现顺序。

假设当前 committed generation：

```text
G
```

目标：

```text
G+1
```

步骤：

1. 暂停 region。
2. required chunks 全部 loaded。
3. 验证当前 HEAD=`G`。
4. 采集 world preimage。
5. 候选 world water 仍只允许纯 `Blocks.WATER`。
6. 构造完整 `G+1` region/ownership state。
7. 写 inactive slot/temp。
8. `force()`。
9. 读回。
10. checksum / ledger / identity 全部验证。
11. **此时仍不能发布 HEAD。**
12. 写 ownership transition WAL。
13. force WAL。
14. 写 WARNING。
15. force WARNING。
16. 开始 world mutation。
17. 验证每个 world mutation。
18. 玩家实体方块冲突不覆盖。
19. 世界修改全部成功后，**最后原子发布 HEAD=G+1**。
20. 再读 HEAD/manifest/ownership 进行终验。
21. 清理 WAL/WARNING。

最终 commit decision 仍然是：

```text
atomic HEAD publication
```

这与现有 region-state “HEAD 最后发布”的事务哲学一致。当前 v1 的双槽流程也是先完整写/验证 inactive generation，最后才发布 HEAD。

---

# 11. Crash Recovery 判定表

启动检测到 M6 WAL：

## HEAD = G

说明目标 ownership 从未 commit。

动作：

```text
rollback world preimage
```

仍保持：

```text
player entity block wins
```

然后清理未提交 G+1。

## HEAD = G+1

说明 ownership 已 commit。

动作：

```text
complete ownership representation
```

禁止：

```text
restore old vanilla water
```

## HEAD = 其它 generation

LOCKED。

## HEAD corrupt

LOCKED。

## UUID 不匹配

LOCKED。

## WAL target 与 manifest 不匹配

LOCKED。

不能根据：

* timestamp；
* 文件大小；
* 哪个 slot 比较新；

猜 authoritative state。

---

# 12. `region-state v3` 应作为 M6.0 的重点格式方案

当前：

```text
v1 = pure water
v2 = material-enabled
```

持久 ownership 是新的语义。

如果继续把它伪装成 v1/v2，只额外增加一个 ownership 文件，会产生严重 downgrade 风险：

旧版可能：

1. 正常打开 v1/v2；
2. 完全不知道 ownership；
3. 世界原版水已经被删除或变成 proxy；
4. solver 继续运行。

因此 M6.0 必须认真评审：

```text
region-state v3
```

而不能默认“旁边多放一个文件即可”。

## v3 必须保持

* v1 bytes 不变；
* v1 CRC 不变；
* v2 bytes 不变；
* v2 CRC 不变；
* reader 仍认识 v1/v2；
* v3 migration 是新的完整 generation；
* v3 unknown/corrupt -> LOCKED；
* world bridge disabled 不删除 v3；
* downgrade fail closed。

---

# 13. 当前 section persistence 可以复用，但不是独立 section commit

现有 `RegionState.capture()` 已经按照 Minecraft 世界 16×16×16 section 坐标切 marker/solid。

而 `RegionStateFiles` 仍通过：

```text
slot-a
slot-b
HEAD
```

提交整个 region generation。

因此 M6 roadmap 中：

> 按 chunk 持久化 ownership 水

最合理的解释是：

```text
physical storage segmented by section/chunk
+
logical commit atomic at whole-region generation
```

不能：

```text
section A generation 11
section B generation 12
```

拼成 region generation 12。

---

# 14. Release Ownership 必须单独设计

M6 绝不能提供：

```text
restore original water blocks
```

作为长期 release。

因为水已经运动。

最小、可靠的 M6 release 可以保守一点：

## 推荐首版

允许 release 的前提：

* region paused；
* 没有未完成 WAL；
* liquid state 能被无损转出；
* 或 region 已经排空。

如果 marker 中还存在不能无损映射回 vanilla 的：

* partial water；
* mixture；
* arbitrary free surface；

则：

```text
refuse release
```

提示玩家先使用 Real Water container 转移。

比“近似生成原版水源格”安全得多。

---

# 15. M6.0 结束条件

必须形成一份正式 decision record，记录：

* 选 proxy 还是 registry；
* 为什么；
* 拒绝另一路线的证据；
* save footprint；
* stable world write count；
* Minecraft mechanism compatibility；
* crash behavior；
* ownership generation；
* format；
* migration；
* downgrade；
* release；
* automatic gate；
* manual smoke；
* remaining limitation。

然后才能进入 M6.1。

---

# 16. M6.1 实施总顺序

严格建议：

```text
1. feature gate
2. persistence format
3. staged generation
4. ownership WAL
5. ownership backend
6. startup recovery
7. world edit coordination
8. exact solver transfer primitive
9. container numeric representation
10. Item Data Component
11. player interaction
12. dispenser
13. crafting
14. hopper/drop/death/disconnect
15. cross-store crash recovery
16. milestone smoke
17. docs/version/release
```

不要从 bucket UI 开始。

---

# 17. Feature Gate

新增类似：

```text
worldBridgeEnabled=false
```

默认关闭。

关闭时：

* 不创建新 ownership；
* 不允许新的 region/container gameplay transfer；
* 已有 persistent ownership 不删除；
* 已有新格式不降级；
* 可以读状态并报告 LOCKED/disabled。

当前 server config format v2 已包含 controllers/materials 两个 feature gate。

配置格式与 region-state 格式必须分开评审：

```text
config 兼容
≠
save format 兼容
```

---

# 18. 改造 RegionStateFiles：支持 Prepare / Commit

当前 `publish()`：

```text
write inactive
validate
rename slot
write HEAD
atomic replace HEAD
```

M6 ownership 需要在：

```text
slot verified
```

与：

```text
HEAD commit
```

之间插入 world WAL 和 world mutation。

因此内部可演化为：

```java
PreparedGeneration prepare(RegionState state);
long commit(PreparedGeneration prepared);
void abort(PreparedGeneration prepared);
```

但现有：

```java
publish(state)
```

应继续作为普通 checkpoint 包装。

`PreparedGeneration` 至少绑定：

```text
region UUID
generation
slot
manifest checksum
base HEAD
```

commit 前重新验证 base HEAD 未移动。

默认门：

```text
runFluidRegionPersistenceValidation
```

不要同时跑 solver/GPU。

---

# 19. 新增 Ownership Transition WAL

不要破坏 `takeover-journal v1`。

新增版本独立的 world ownership transaction。

建议字段：

```text
version
transaction UUID
region UUID
dimension
base generation
target generation
operation ACQUIRE / RELEASE
preimage entries
target ownership entries
count
checksum
```

Fault injection：

```text
before WAL publish
after WAL publish
before world mutation
after mutation N
after final mutation
before HEAD
after HEAD
before cleanup
```

每个故障点重启后只能得到：

```text
old complete
```

或：

```text
new complete
```

不能 mixed。

---

# 20. WorldFluidBridge 职责

推荐单独服务层。

```text
WorldFluidBridge
OwnershipCoordinator
OwnershipStore / ProxyAdapter
```

具体类名可调整。

必须保持：

```text
solver != Minecraft world mutation layer
```

禁止把：

```java
level.setBlock(...)
```

放进 `FlipFluidDomain`。

---

# 21. 玩家编辑

## 玩家放实体块

player block wins。

之后：

```text
refresh collision
```

当前 world collision adapter 会根据实际 `VoxelShape` 更新 cut-cell，并将被实体块挤出的水重新加入邻域；无法容纳部分显式计入 escaped。

不要增加额外“支撑水”“贴墙水”等经验规则。

## 玩家拆实体块

空间重新开放。

但：

```text
不生成水
```

只有 marker 自己流进去。

---

# 22. Exact Solver Transfer Primitive

M6 container 最重要的物理 API。

不要直接复用开发命令 `/pour` `/drain` 的“尽量添加/删除”语义。

至少需要：

```text
tryInsertExact(...)
tryExtractExact(...)
tryExtractAvailable(...)
```

## Full bucket insert

1 BV 必须 all-or-nothing。

错误：

```text
marker budget only accepts 0.7 BV
bucket becomes empty
```

正确：

```text
operation fails
bucket unchanged
solver unchanged
```

## Full bucket extract

只有：

```text
exactly extractable 1 BV pure water
```

才返回 vanilla water bucket。

不足：

```text
partial container
```

material != pure water：

```text
Real Water container
```

---

# 23. Marker Selection 与 Quantity Settlement 分开

允许：

```text
marker occupancy
```

用于找“瞄准的地方有没有液体”。

不允许：

```text
smooth volume = 0.63
=> 抽 0.63 BV
```

actual removal 只能通过 marker ledger。

---

# 24. 容器的精确数值表示先做实验

在 Item 注册前，做一个小型纯 Java 数值实验。

回答：

1. marker `float particleVolume` 的 double exact sum 如何持久化；
2. split/merge/extract 后能否无损 round-trip；
3. pure water partial 如何编码；
4. M3 material volume + mass 如何编码；
5. 10,000 次 container↔solver round-trip 是否 drift；
6. 最大合法容量有没有 overflow。

如果一个简单整数单位不能保证 ledger 一致：

```text
不要为了方便使用近似数
```

可以重新设计：

* exact residual；
* canonical components；
* 或其它可验证表示。

---

# 25. Item Data Component

使用 Minecraft 26.2 当前的 Item Data Component 机制保存 Real Water 容器状态。

逻辑模型：

```text
FluidContainerContents
    schemaVersion
    exact amount representation
    stable material IDs
    exact per-material ledger
```

不要持久化：

```text
runtime material handle
palette index as identity
density
mesh
gas node id
gas pressure
future fake temperature
future chemistry
```

---

# 26. Container Canonicalization

## Empty

```text
empty Real Water container
```

## Partial pure water

```text
Real Water partial-water container
```

## Exactly 1 BV pure water

允许 canonicalize：

```text
minecraft:water_bucket
```

## Mixed / other liquid

```text
Real Water container
```

永远不伪装成 vanilla water bucket。

---

# 27. 玩家交互

全部走一个：

```text
FluidTransferService
```

而不是 Item、Dispenser、Recipe 各自操作 solver。

## Vanilla water bucket -> owned region

尝试：

```text
insertExact(1 BV pure water)
```

成功才：

```text
bucket -> empty bucket
```

失败：

```text
item unchanged
```

## Region -> empty bucket

* exact 1 BV pure water -> vanilla bucket；
* partial pure -> partial container；
* mixture -> Real Water container；
* zero -> unchanged/fail。

## Partial container -> 非 Real Water 世界

M6 可以保守：

```text
reject
```

不能为了方便写 approximate source block。

---

# 28. Dispenser

Dispenser 只负责：

* 找 slot；
* 找朝向；
* 找 target。

然后调用：

```text
FluidTransferService
```

规则与玩家相同。

失败：

```text
original ItemStack unchanged
```

partial 不能生成 full source block。

---

# 29. Crafting / Merge

两个 partial containers：

```text
destination += min(capacity-destination, source)
```

但必须使用 exact ledger。

多出的液体：

```text
recipe remainder
```

不能被 crafting 默认消耗吞掉。

M3 mixture：

* material ledger conserved；
* 不允许的组合 recipe 不匹配；
* M6 不做 chemistry。

---

# 30. Hopper / Drop / Death / Disconnect

M6 主要验证：

> ItemStack component 能否完整 round-trip。

## Hopper

只搬 item。

不要自动做液体 API。

## ItemEntity

save/load：

```text
component equal
```

两个非空容器不能因为 item merge 规则而丢 ledger。

## Death

inventory drop：

```text
contents unchanged
```

## Disconnect

player save/load：

```text
contents unchanged
```

## Creative

明确属于：

```text
external non-conservative source/sink
```

debug 统计即可。

不要污染 survival conservation assertion。

---

# 31. M6.1 另一个大风险：Region 和 Item 不同持久域

即使一次玩家操作内严格守恒：

```text
remove 1 BV from region
give 1 bucket
```

也还没有解决 crash。

因为：

```text
region-state
```

和：

```text
player.dat / chunk inventory
```

不是同一个 atomic file transaction。

典型 crash：

## Duplicate

region checkpoint 已经保存“少了 1 BV”。

player inventory 还保存旧满 bucket。

重启：

```text
water duplicated
```

## Loss

player inventory 已经保存 empty bucket。

region checkpoint 还是旧状态。

根据操作方向可能出现丢失。

因此 M6.1 关闭前必须显式解决跨持久域事务。

---

# 32. Container Transfer WAL

建议首版保持 KISS。

只用于：

```text
player/dispenser <-> region
```

不用于普通 item 搬运。

字段至少：

```text
tx UUID
version
region UUID
dimension
base generation
target generation
INSERT / EXTRACT
exact material ledger delta
actor type
player UUID OR block pos
slot/hand
before item fingerprint
after item
checksum
```

---

# 33. Container Transfer 提交流程

容器不是 20 Hz hot path，因此首版可以优先正确性，允许同步 checkpoint。

推荐：

1. region availability check。
2. 构造 exact solver delta。
3. 构造 after ItemStack。
4. 两边都不修改。
5. publish transfer WAL PREPARED。
6. 修改 solver。
7. publish target region checkpoint。
8. WAL 标记 REGION_COMMITTED。
9. 修改 ItemStack。
10. 尽最大能力确认相应 player/container dirty/save。
11. WAL 标记 ITEM_COMMITTED。
12. cleanup WAL。
13. wake region。
14. snapshot。

如果以后证明同步 checkpoint 成本过高，再优化。

不要一开始就引入后台 world write。

---

# 34. Container WAL Recovery

## HEAD = base generation

fluid mutation 未 committed。

恢复：

```text
before item
```

删除 transaction。

## HEAD = target generation

fluid mutation committed。

必须收敛：

```text
after item
```

不能给玩家旧 item。

## Actor item 无法唯一识别

```text
LOCK
```

不要猜。

## Dispenser chunk 未加载

pending。

不能把 unavailable 当 completed。

---

# 35. 不要预建全球 Item UUID 数据库

首版优先：

```text
short-lived transaction WAL
+
ordinary Data Component
```

不要提前做：

```text
global container ownership database
```

只有真实实验证明 Minecraft player/dispenser persistence 无法安全收敛时，再升级设计。

---

# 36. 测试策略

任何时候首先重新看最新 `routing.md`。

当前 routing 明确要求：

> 一个小增量默认只跑最贴近的一项定向门。

## Persistence format / HEAD

默认：

```text
runFluidRegionPersistenceValidation
```

## Ownership world transaction

优先扩展现有 takeover transaction fake-world 测试结构。

如果确实形成第二职责，再新增：

```text
runWorldOwnershipValidation
```

并同步 routing。

## Manager budget/admission

```text
runFluidRegionManagerValidation
```

## Controller boundary

```text
runFluidBoundaryScannerValidation
```

## Marker material transfer

如果改 material/phase/mass：

```text
runM3ConnectedVesselValidation
```

## Container component

若现有测试没有合理职责承载，再新增小而专一：

```text
runFluidContainerValidation
```

## GPU

M6：

```text
不跑
```

除非实际触及 GPU queue/device/memory/Vulkan lifecycle。

---

# 37. M6.0 手工验证

只验证自动测试看不到的 Minecraft 机制：

两个 ownership prototype 使用同一 fixture 测：

* piston；
* explosion；
* neighbor update；
* fluid tick；
* structure；
* player edit；
* chunk reload；
* common block observation。

不需要重跑：

* M3 水动力；
* M4 tension；
* M5 gas；
* 64³ GPU；
* 5000 step。

---

# 38. M6.1 新自动验收

## Ownership

必须覆盖：

* legacy v1 readable；
* v2 readable；
* migration；
* unknown version；
* corrupt CRC；
* missing section；
* extra section；
* mixed generation；
* UUID mismatch；
* budget rejection；
* PREPARED crash；
* halfway world mutation crash；
* crash before HEAD；
* crash after HEAD；
* WAL cleanup crash；
* occupied player block；
* unloaded metadata budget。

## Container

必须覆盖：

* pure 1 BV round-trip；
* partial round-trip；
* exact all-or-nothing；
* marker budget failure unchanged；
* exact ledger no drift；
* component codec；
* stack limit；
* recipe remainder；
* material conservation；
* incompatible mixture reject。

## Cross persistence

必须覆盖每一个：

```text
WAL
solver mutation
region HEAD
item mutation
WAL state
cleanup
```

之间 crash。

---

# 39. M6.1 最终实机 Smoke

不要重演所有旧 smoke。

只增加 M6 自己的。

## Smoke A — Ownership 生命周期

1. controller region。
2. 开 world bridge。
3. acquire ownership。
4. 确认 vanilla water 与 marker 不双份。
5. run。
6. 水实际流动。
7. 放实体方块。
8. collision 正确。
9. 离开 required chunk。
10. UNLOADED。
11. 回来。
12. same UUID。
13. same exact ledger。
14. ownership correct。
15. 玩家实体块未覆盖。

## Smoke B — Ownership Crash

只做一次真实强制中断。

故障点选择 automatic fault suite 无法完全代表的真实 Minecraft world save 边界。

必须使用世界备份。

## Smoke C — Container Matrix

一次覆盖：

* vanilla full water；
* partial water；
* material container；
* player；
* dispenser；
* crafting remainder；
* hopper；
* drop/pickup；
* death；
* disconnect；
* creative；
* restart。

每一步记录：

```text
exact region ledger before/after
exact item ledger before/after
```

截图只做辅助。

---

# 40. 性能停止线

M6 不用“大水世界”证明成功。

只在 M6.0 route decision 与 M6.1 milestone 各量一次：

```text
steady ownership writes/tick
acquire world writes
release world writes
ownership storage bytes
startup reconciliation wall time
bucket transaction checkpoint wall time
```

最重要：

```text
steady-state ownership writes/tick = 0
```

如果 bucket 同步 checkpoint 太慢：

先优化：

* transaction scope；
* checkpoint serialization；
* fsync grouping。

不要先牺牲 crash consistency。

---

# 41. 文档更新

## M6.0 通过

把已经冻结的：

* ownership；
* generation；
* recovery；
* migration；

写入正式 architecture/roadmap。

不要继续只更新 archive proposal。

## M6.1 关闭

更新：

```text
docs/architecture/
docs/maintenance/routing.md
docs/progress/README.md
new M6 progress report
docs/roadmap/product-roadmap.md
README.md
```

职责：

* architecture = 当前行为；
* routing = 文件和最小测试；
* progress = 已完成证据；
* roadmap = 后续约束；
* README = 用户使用和安全边界。

---

# 42. 版本

当前远端主 Mod：

```text
0.11.0
```

M6.0 原型不因为提交很多就升版本。

M6.1 正式关闭时，根据当时 `acceptance-and-release` 再决定版本。

GPU probe：

```text
0.1.16
```

保持不动。

---

# 43. 推荐提交粒度

每个小增量自行 commit。

推荐：

```text
M6: prototype ownership backends
M6: record ownership design decision

M6: add ownership generation format
M6: stage region generations before commit
M6: add ownership transition journal
M6: acquire controller world ownership
M6: reconcile ownership on startup
M6: protect player edits in owned regions

M6: add exact fluid transfer primitive
M6: add persistent partial fluid container
M6: route player bucket transfers
M6: route dispenser transfers
M6: conserve container crafting remainders
M6: preserve container contents through inventory lifecycle
M6: make region-container transfers crash recoverable

M6: close world bridge milestone
```

一个 commit 不要同时做：

```text
format + solver + item + renderer + commands
```

---

# 44. 每次提交前

```powershell
git status --short --branch
```

然后：

1. 确认没有覆盖用户未提交内容。
2. 只跑 routing 当前最贴近的一项门。
3. `git diff --check`
4. 看 diff。
5. commit。

文档-only：

```text
不跑代码门
```

正式发布：

才执行 clean build。

---

# 45. 立即停止扩范围的信号

出现任何一个，都回到设计而不是继续补丁：

* ownership 随 density 每 tick 写 block；
* marker 总量丢了但 renderer 看起来正常；
* v3 被旧代码当 v1/v2 打开；
* crash recovery 必须猜 generation；
* recovery 覆盖玩家实体块；
* release 只能通过重建历史 water blocks；
* full bucket 被部分写入；
* item 已消耗但 solver 只接受部分量；
* material container 保存 runtime handle；
* recipe/hopper 出现复制，只能靠事后扫描；
* missing chunk 被当墙继续计算；
* 为修 M6 引入强制找平；
* 为修 M6 引入 renderer 水量补偿；
* CPU-only world bridge 依赖 GPU 才正确。

---

# 46. 推荐给执行 AI 的具体首轮任务

不要直接实现完整 M6。

第一轮只做：

## Task 1

本地确认：

```text
git status
git log
current version
```

## Task 2

建立同一个小 ownership fixture。

## Task 3

实现 disposable Proxy prototype。

只测 world mechanics。

## Task 4

实现 disposable independent-registry prototype。

只测 world mechanics。

## Task 5

生成对比表：

```text
compatibility
recovery
storage
world writes
hook complexity
mod observability
```

## Task 6

冻结：

```text
ownership representation
transaction state machine
format/migration
release
```

并完成 M6.0 review。

在这之前：

```text
不要实现正式 bucket
不要修改 FlipFluidDomain pressure
不要升级 takeover 范围
不要发布 persistent ownership
```

---

# 47. 第二轮任务

M6.0 通过后：

1. 新格式/migration。
2. staged region generation。
3. ownership WAL。
4. fault injection。
5. acquire。
6. startup reconcile。
7. player world edit。

到此为止先形成：

> 一个没有容器，但已经真正 crash-safe 的持久 Real Water ownership controller。

这应该成为 M6.1 的第一个内部稳定点。

---

# 48. 第三轮任务

然后才做：

1. exact marker transfer；
2. numeric representation experiment；
3. container component；
4. pure water player path；
5. partial；
6. material；
7. dispenser；
8. recipe；
9. inventory lifecycle；
10. transfer WAL。

---

# 49. M6 最终成功定义

M6 成功不是：

> 水桶能倒出一团看起来像水的东西。

而是：

> 一个显式控制器区域能够持久拥有 Minecraft 世界的一片空间；其中液体始终由 marker 精确账本作为物理真值；ownership、region-state 与 world mutation 使用同一 generation 并能在任意中断点恢复为旧完整或新完整状态；玩家方块永远优先；容器在 player、dispenser、crafting 和持久化路径中不复制、不吞液；整个过程中 density、PLIC、renderer 和调度都不成为第二套库存。

这才是进入 M7/M10 之前足够可靠的世界桥。
