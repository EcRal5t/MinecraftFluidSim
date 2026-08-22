# Archived proposal: M6 世界 ownership、世界桥与容器执行计划（deepseek 版）

> 本文是进入 M6 实施前的执行计划，归档于 `docs/archive/proposals/`。实施阶段的系统契约以 [`../../architecture/fluid-solver.md`](../../architecture/fluid-solver.md)、[`../../architecture/region-state-v1.md`](../../architecture/region-state-v1.md) 与 [`../../roadmap/world-integration.md`](../../roadmap/world-integration.md) 为准，测试路由以 [`../../maintenance/routing.md`](../../maintenance/routing.md) 为准。
>
> 本文所有行号均为基线指示，实施时以符号名和当前代码为准；禁止凭旧对话或 `docs/archive/` 推测文件位置。

---

## 0. 实施前必读

按以下顺序阅读：

1. [`../../README.md`](../../README.md)（命令表、构建、安全边界）
2. [`../../maintenance/routing.md`](../../maintenance/routing.md)（当前代码职责 + 最小验证路由）
3. [`../../architecture/fluid-solver.md`](../../architecture/fluid-solver.md)（当前 CPU 流体契约与不可混用状态）
4. [`../../architecture/region-state-v1.md`](../../architecture/region-state-v1.md)（持久化双槽/HEAD 原子事务，M6 任何存档改动前必读）
5. [`../../roadmap/product-roadmap.md`](../../roadmap/product-roadmap.md) 的 M6 节与“接下来三项”
6. [`../../roadmap/world-integration.md`](../../roadmap/world-integration.md)（M6 主题规格）
7. [`../../roadmap/cross-cutting-contracts.md`](../../roadmap/cross-cutting-contracts.md)（状态分层、feature flag、协议边界）
8. [`../../roadmap/acceptance-and-release.md`](../../roadmap/acceptance-and-release.md)（里程碑验收与发布门）
9. [`../../progress/m0-takeover-smoke.md`](../../progress/m0-takeover-smoke.md)（现有 takeover 人工门与失败判定）
10. [`../../progress/README.md`](../../progress/README.md) 与 [`../../progress/m5-air-network.md`](../../progress/m5-air-network.md)（当前已完成边界与量化限制）

基线：主 Mod `0.11.0`；Minecraft 26.2 / Fabric Loader 0.19.3 / Fabric API 0.157.0+26.2；JDK `D:\ProgramFiles\BienCreng\jdk-26.0.2`，目标 Java 25。

构建：

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat clean assemble compileTestJava --offline --no-daemon
```

活动核心是 `src/main/java/com/example/fluid/FlipFluidDomain.java`。`FluidDomain.java` 是未接入游戏的旧 VOF 对照，禁止切回。

---

## 1. M6 目标与范围

### 1.1 M6.0 ownership 设计门

M6.0 只做设计评审，不做正式 gameplay 入口。目标是：

1. 可丢弃原型 A：**代理占位方块**。
2. 可丢弃原型 B：**空气 + 独立 registry**。
3. 用同一组世界机制兼容性、恢复能力、存档体积与运行成本标准比较两条路线。
4. 冻结 ownership、region-state、原版方块替换和解除接管的**共同代次/恢复状态机**。
5. 输出决策记录：选择一条路线，记录拒绝另一条的证据、格式风险与迁移方案。

**M6.0 通过条件**：选择一条路线并记录拒绝另一条的证据、格式风险和迁移方案。未通过不得进入 M6.1。

### 1.2 M6.1 世界桥与容器

M6.1 实现被选路线并交付：

1. **ownership 层**：让原版 fluid tick、方块放置、邻居更新和常见 Mod 扫描能判断该位置由 Real Water 管理；不能长期依赖“原版水位置持续写空气”。
2. **按 chunk 持久化 ownership 水**：重启后 ownership 与方块状态一致、可恢复；未解决事务锁住新 takeover。
3. **容器真值**：纯水满桶可退化为原版水桶；部分纯水桶保存精确体积；混合物容器保存 mixture handle 与逐材料精确体积；合并配方原子结算并溢出到 recipe remainder。
4. **行为矩阵**：玩家装取/放置、发射器、合成、漏斗、掉落/死亡、创造、断线与未协商 API 的其他 Mod 路径必须显式记账。
5. **有界真实流动**：在 ownership 覆盖的显式配置范围内让纯水先真实流动，再逐项适配灭火等原版行为；不扩大到自然河流/海洋。
6. **关闭 M6.1**：版本推进，progress/architecture/routing 更新，人工 smoke 证据归档。

### 1.3 明确不做（M6 范围外）

- 不扫描天然洞穴；不接入天然河流、海洋、世界生成水。
- 不做 M7 化学、M8 颗粒、M9 热力学。
- 不做灭火、湿润、岩浆成石、红石/生物/寻路逐项适配（M10）。
- 不做开放世界无界求解器；M6.1 仍是显式配置的有界 ownership 区域。
- 不把 `region-state v1/v2` 直接改写为 section 格式；完整 section 持久化是未来格式，若 M6.1 必须触碰，先做独立 v3 提案与迁移门。
- 不改 GPU probe；M6 是纯 CPU/世界事务改动。

### 1.4 版本与门控

- M6.0 关闭时不升版本，只提交决策记录与原型证据。
- M6.1 关闭时版本推进到 `0.12.0`（若 M6.1 被拆分为多个签收子阶段，则在最终子阶段推进）。
- 新增独立服务器配置门 `worldBridgeEnabled`，默认 `false`；格式 `formatVersion` 保持当前值，读取用可选键 + 缺省 `false`，非法值 fail-closed。功能关闭时：
  - 拒绝新 ownership/容器 gameplay 入口；
  - 已存在 ownership 状态保留并只读恢复/导出；
  - 不创建新世界状态，不静默删除已有物质。
- 扩大 takeover 范围必须显式配置、可恢复，且仍受 manager 预算约束。

---

## 2. 架构红线（违反即返工）

1. **物理真值分层**：
   - 液体真值仍是 marker `particleVolume` 精确和；容器中的水体积用 `long` 纳体积量子记账，不能以 damage bar、渲染液面或平滑 density 结算。
   - 混合物容器真值是逐材料精确体积/质量；显示比例只是重建。
2. **marker 与压力相**：`markerCount > 0` 仍决定压力流体格；碰撞只约束墙面法向，切向自由。
3. **客户端渲染**：客户端由 density + marker + solid 重建水面；容器/ownership 的客户端显示只能读快照/物品组件，不得反写物理。
4. **takeover 事务**：世界方块修改前必须先发布可恢复 journal 与 WARNING；未解决状态锁住新 takeover；恢复不得覆盖玩家实体方块。现有 `takeover-journal v1` 与 WARNING 原子发布语义不得弱化。
5. **region-state v1 语义不得弱化**：双不可变槽、完整 generation 校验、最后原子发布 HEAD 的事务语义保持；未知、损坏、混合 generation 或超预算状态必须保留并锁住新控制器创建。
6. **共同代次**：ownership、region-state 和原版方块替换必须共享区域 UUID 与提交代次；不一致时暂停并进入恢复流程，绝不覆盖玩家新实体方块。
7. **瞬态与持久化边界**：只有控制器区域持久化；dev demo、capture、takeover 区域保持瞬态，避免与原版水恢复重复。metadata-only `UNLOADED` 区域仍占 manager 预算。
8. **GPU probe 不受影响**：主 Mod 始终 CPU 权威。
9. **不掩盖结构问题**：不得用渲染补丁、经验规则、跨材料合并或强制找平掩盖物理/事务缺陷。
10. **KISS**：一个增量只跑 `routing.md` 中最贴近改动的一项定向门；不为同一不变量另建重叠测试套件。

---

## 3. 当前代码事实与起点

实施前用符号名重新定位，以下是 M6 相关锚点：

| 职责 | 首要入口 | M6 注意点 |
|---|---|---|
| 服务编排与 takeover | `src/main/java/com/example/simulation/FluidSimulationService.java` | 所有 M6 世界桥、容器、命令入口先过这里；takeover 事务不下沉到 solver |
| 区域登记/预算/选择 | `src/main/java/com/example/simulation/FluidRegionManager.java` | ownership 区域与普通区域共用预算；metadata-only UNLOADED 仍占预算 |
| controller 持久化协调 | `src/main/java/com/example/simulation/FluidRegionPersistence.java` | 只持久化控制器区域；v1/v2 分发、manifest admission、quarantine 不弱化 |
| takeover journal 事务 | `src/main/java/com/example/simulation/TakeoverTransaction.java`、`TakeoverBackupStore.java` | journal/WARNING 发布后才能改世界；恢复不覆盖玩家实体方块 |
| takeover codec/files | `src/main/java/com/example/simulation/TakeoverJournalCodec.java`、`TakeoverJournalFiles.java` | v1 支持任意 BlockPos 列表与 65536 上限；扩大范围先确认是否够用，不要静默改 v1 |
| 区域构造与只读原版水采样 | `src/main/java/com/example/simulation/FluidRegionFactory.java` | marker 上限等于 manager 预留；不从 density 结算 |
| 世界碰撞/实体采样 | `src/main/java/com/example/simulation/FluidRegionWorldInteraction.java` | ownership 层不应绕过碰撞重采样 |
| 控制器方块/边界块 | `src/main/java/com/example/block/FluidControllerBlock.java`、`RealWaterBlocks.java` | M6 代理方块/容器物品注册参考这里；feature flag 默认关闭 |
| 命令 | `src/main/java/com/example/RealWaterCommands.java` | 新增命令沿用 audit transcript 包装 |
| 配置 | `src/main/java/com/example/simulation/FluidServerConfig.java` | 新增 `worldBridgeEnabled` 可选键，fail-closed |
| 活动求解器 | `src/main/java/com/example/fluid/FlipFluidDomain.java` | M6 不得改动压力/散度/marker 账本；只允许新增导出/重建接口时保持纯水路径不变 |
| 网络快照 | `src/main/java/com/example/network/FluidSnapshotPayload.java` | ownership 状态若不进入快照，必须保持协议 v1/v2 独立 |
| 客户端 | `src/client/java/com/example/client/ClientFluidVolumeRenderer.java` | ownership/容器渲染只读服务端状态，不反写 |

### 3.1 现有 takeover 行为摘要

- `/realwater takeover` 只移除纯 `Blocks.WATER`，先写 `realwater-takeover-backup.nbt` 再写 WARNING，然后才改世界。
- journal v1 记录任意 `BlockPos` 列表与 NBT `State`，上限 65,536 方块；解码严格拒绝未知版本/重复/非法 id。
- `/realwater remove` 恢复时跳过当前不是原版水的玩家实体方块；未解决 journal/WARNING 锁住新 takeover。
- 当前 takeover 区域是瞬态：重启后不保留 ownership，只做 journal 恢复。

M6.1 要在这个基础上增加**持久化 ownership**，但不得把 transient takeover 与持久 ownership 混成一套不可恢复路径。

---

## 4. M6.0 ownership 设计门执行方案

### 4.1 原型 A：代理占位方块

目标：验证“每个被 Real Water 管理的水位置写入专用方块”是否成立。

原型要求：

- 新建一个默认关闭的 `realwater:fluid_proxy` 方块（可注册，不进入创造标签）。
- 方块不实现流体逻辑，只作为可见/可查询的占位；至少测试：
  - 放置/破坏：玩家、爆炸、结构命令、活塞、霜冰型替换；
  - 光照遮挡、寻路通过性、掉落伤害/呼吸/游泳查询；
  - 原版 fluid tick、邻居更新、红石、火把/甘蔗/铁轨等更新敏感方块；
  - chunk 保存/加载后的方块状态；
  - 常见 Mod 扫描：以 `minecraft:water` 或 `#minecraft:water` 扫描时是否能正确识别或至少可显式映射。
- 原型不接入求解器；只回答机制兼容性。
- 记录：哪个机制不可行、需要多少 block state/block entity 才能补齐、存档体积估算。

### 4.2 原型 B：空气 + 独立 registry

目标：验证“世界继续写空气，由 sidecar registry 记录 ownership”是否成立。

原型要求：

- 新建一个默认关闭的 `OwnershipStore` 原型，至少：
  - 以 region UUID/维度/chunk section 为键记录被管理位置、方块原状态、generation；
  - 原子发布：临时文件 force 后 rename 到目标；读不到完整状态时 fail-closed；
  - 支持损坏检测（checksum/CRC）与锁定；
  - 在 chunk 加载/卸载顺序变化时能恢复 ownership 视图；
  - 能检测“registry 说管理、世界位置已被玩家改为实体方块”的失配。
- 原型不接入求解器；只回答事务与恢复问题。
- 记录：外部方块编辑如何处理、chunk 卸载时 registry 与世界的时序、崩溃后如何重建、存档体积与扫描成本。

### 4.3 共同评估矩阵

两个原型必须填写同一矩阵，结论写入决策记录：

| 维度 | 代理方块 | 空气 + registry | 备注/证据 |
|---|---|---|---|
| 玩家放置/破坏语义 | | | |
| 活塞/结构命令/爆炸 | | | |
| 光照/寻路/呼吸/游泳 | | | |
| 原版 fluid tick | | | |
| 邻居更新（火把/甘蔗/铁轨等） | | | |
| 常见 Mod 扫描 `#minecraft:water` | | | |
| chunk 保存/加载 | | | |
| 外部方块编辑失配 | | | |
| 崩溃恢复 | | | |
| 校验修复/解除接管 | | | |
| 存档体积 | | | |
| 运行成本 | | | |
| 与 `region-state v1/v2` 共同提交难度 | | | |
| 总评 | | | |

### 4.4 冻结：共同代次/恢复状态机

无论选择哪条路线，必须在 M6.0 冻结以下状态机，M6.1 照此实现：

```text
OwnershipCommit
  regionId: UUID
  dimension: ResourceKey<Level>
  generation: long          // 与 region-state HEAD generation 一致
  state: ACTIVE | RESTORING | LOCKED
  journal: journal descriptor  // 选择路线后确定
  lastPublished: timestamp / slot / HEAD
```

规则：

1. 任何世界方块修改（移除原版水、放置代理方块、写空气）前，必须先持久化 `OwnershipCommit`，并像 takeover journal 一样同步发布可读 WARNING；发布完成后才允许改世界。
2. 恢复流程：
   - 启动/加载区域时读取 `OwnershipCommit` 与当前世界方块；
   - 若当前位置已被玩家/实体方块占据（不是本 ownership 应写的状态），跳过并计入 skipped；
   - 若 commit 不完整、不可读、校验失败，区域暂停并锁住新 ownership/takeover；
   - 恢复成功后原子删除 journal/WARNING，commit 状态变为 ACTIVE 或移入归档。
3. `generation` 只有一个来源：控制器区域使用 `FluidRegion.generation()`；非控制器 ownership 区域若 M6.1 允许，必须由同一个 ownership 管理器分配并持久化，不得自造第二个 generation。
4. 关闭/降级 feature flag 不得删除 ownership 状态；只能暂停、原子落盘、恢复原版状态，或要求管理员执行有说明的迁移。

### 4.5 风险与迁移方案模板

决策记录必须包含：

- 格式风险：是否新增 journal/registry 格式、是否新增 block/item、是否影响旧世界升级；
- 迁移方案：旧 takeover 瞬态语义如何升级到持久 ownership；已有 `region-state v1/v2` 如何保持原样；未知/损坏数据如何处理；
- 拒绝另一条路线的证据摘要（来自评估矩阵与原型测试）；
- 回退方案：如果 M6.1 实现中发现所选路线失败，回到 M6.0 重做另一原型。

### 4.6 M6.0 验收与关闭

- 两个原型代码可丢弃，但必须在提交历史中保留（单独 commit 或归档 commit）。
- 决策记录写入 `docs/progress/m6-ownership-decision.md`（新建）。
- 不升版本；`routing.md` 暂不新增 M6.1 门，但可以新增 M6.0 原型观察记录。
- 关闭 M6.0 后才允许进入 M6.1。

---

## 5. M6.1 世界桥与容器执行方案

### 5.1 总体顺序

按以下顺序推进，每步保持可编译、可运行：

1. 子阶段 1：ownership 层 + 共同代次/恢复状态机 + feature flag。
2. 子阶段 2：chunk 持久化 ownership 水与恢复。
3. 子阶段 3：容器真值与数据组件。
4. 子阶段 4：行为矩阵与 recipes。
5. 子阶段 5：有界真实流动与扩大范围配置。
6. 子阶段 6：客户端渲染/资源与文档/版本推进。

### 5.2 子阶段 1：ownership 层与共同状态机

- 实现 M6.0 冻结的 `OwnershipCommit` 与状态机。
- 若选择代理方块路线：注册 `realwater:fluid_proxy`，方块只读激活，默认不进入创造标签；实现方块与 ownership 状态的映射。
- 若选择 registry 路线：实现 `OwnershipStore` 与原子发布。
- 世界方块修改前先发 journal/WARNING，复用 `TakeoverJournalFiles` 的原子移动模式；如果新 journal 需要更多字段，新增 `ownership-journal v1` 而不是修改 `takeover-journal v1`。
- 恢复不得覆盖玩家实体方块；跳过计数进入日志与 `/realwater status`。
- 门禁：新增 standalone `runM6OwnershipStateMachineValidation`，故障注入点至少覆盖：journal 发布前/后、方块修改中、恢复中、损坏 journal、失配 generation、玩家方块跳过。该门必须证明“要么旧世界、要么新 ownership，绝不出现无 journal 的方块修改”。

### 5.3 子阶段 2：chunk 持久化 ownership 水

- 若代理方块路线：ownership 随 chunk 保存；但 journal/commit 仍以 region 粒度落盘。测试 chunk 保存/加载、跨 chunk 区域缺 chunk 时暂停整域、重载后 ownership 恢复。
- 若 registry 路线：ownership 按 region UUID/chunk section 落盘；提供 CRC、原子发布、损坏锁定；chunk 卸载不删除 registry，加载时对账。
- 保持“只有控制器区域持久化”的旧边界：新增的持久 ownership 仅用于显式配置的 world bridge 区域；dev demo/capture/takeover 仍瞬态。
- 门禁：沿用 `runM6OwnershipStateMachineValidation`；真实 chunk 对账用 GameTest 或人工 smoke。

### 5.4 子阶段 3：容器真值与数据组件

实现文件建议：

- `src/main/java/com/example/item/RealWaterItems.java`（注册）
- `src/main/java/com/example/item/PartialWaterBucketItem.java`
- `src/main/java/com/example/item/RealWaterContainerItem.java`
- `src/main/java/com/example/item/ContainerFluidLedger.java`（纯账本，无 Minecraft 依赖，便于 standalone 测试）
- `src/main/java/com/example/item/ContainerFluidCodec.java`（数据组件往返，fail-closed）

账本规则：

- 1 BV = 1 m³ = `1_000_000_000L` 纳体积量子；所有容量/转移用 `long` 结算。
- 满且纯的原版水桶：直接退化为原版 `WATER_BUCKET`，不携带额外组件。
- 部分纯水：`realwater:partial_water_bucket`，组件 `realwater:water_volume`（long nanos）与可选 `realwater:mixture_handle`；堆叠上限 1；damage bar 只显示比例。
- 混合物容器：组件 `realwater:mixture_handle` + 逐材料体积表（palette index 或材料 id + long nanos）。M6.1 在 `materialsEnabled` 为 true 时才允许创建非纯水容器；否则只创建纯水容器。
- 数据组件未知/缺失/超范围/负体积必须拒绝，不能猜成满桶或 0 体积。
- 创造模式是显式非守恒外部源汇；拾取复制不得污染生存账本，debug 统计来源。

### 5.5 子阶段 4：行为矩阵与 recipes

- 玩家装取/放置：在 ownership 区域内装取按账本流转；放置部分水只放出实际体积，不生成整格水。
- 发射器：走服务端账本，不生成整格水；目标不兼容时原样保留并反馈。
- 合成/合并：动态配方，两个部分容器原子填满一个，溢出以 recipe remainder 保留在指定输入；使用 `long` 纳体积结算。
- 漏斗、掉落、死亡、断线：数据组件往返不变，不自动合并；掉落实体保持同一组件。
- 创造模式：显式记账为外部源汇；拾取副本不污染生存账本。
- 未协商 API 的其他 Mod 转移：拒绝并反馈，不猜测体积或材料语义。
- 门禁：新增 standalone `runM6ContainerValidation`，覆盖：
  - 1 BV 满桶退化；部分桶精确体积往返；
  - 合并 `a + b -> 满 + remainder` 的整数守恒；
  - 混合物 ledger 逐材料守恒；
  - codec 对未知/缺失/负体积/超范围拒绝；
  - 创造/生存源汇记账区分。

### 5.6 子阶段 5：有界真实流动与扩大范围配置

- 在 ownership 覆盖的显式有界区域内，让纯水通过现有 `FlipFluidDomain` 真实流动；不在本阶段适配灭火等原版行为。
- 扩大 takeover 范围必须显式配置（例如 `takeoverExpansionBlocks` 或 `worldBridgeArea`），仍受 manager 预算约束，仍只移除纯 `Blocks.WATER`。
- 扩大范围前确认 `takeover-journal v1` 的 65,536 方块上限是否满足；不满足则新增 journal v2 并保留 v1 reader，禁止静默改 v1。
- 真实流动的渲染与方块状态只读消费服务端快照/ownership 状态，不反写物理。
- 门禁：活动核心相关变化只跑 `runFlipFreeSurfaceValidation`；世界事务变化跑 `runM6OwnershipStateMachineValidation`；不跑 5,000 步长期门。

### 5.7 子阶段 6：客户端渲染/资源与文档/版本推进

- 若 ownership 层需要客户端可见状态：注册对应方块/item 模型、贴图、`zh_cn`/`en_us` 本地化、tooltip、失败反馈、创造标签（仅当功能开启时）。
- 若功能关闭，方块/item 可注册但拒绝新激活，不创建新世界状态。
- 更新：
  - `docs/architecture/world-ownership-v1.md`（新建，若 M6.1 落地 ownership 契约）
  - `docs/architecture/container-ledger-v1.md`（新建，容器账本与数据组件契约）
  - `docs/maintenance/routing.md`（新增 M6 门禁路由）
  - `docs/roadmap/product-roadmap.md`（M6 状态改为已完成或保留后续约束）
  - `docs/progress/m6-world-bridge.md`（验收证据与 smoke）
  - `docs/progress/version-history.md`、`gradle.properties`（`0.12.0`）
- 关闭 M6.1 前，按 `acceptance-and-release.md` 逐层回答，不涉及层明确写“不涉及”。

---

## 6. 测试与验证计划

### 6.1 新增 Gradle 门

在 `build.gradle` 注册：

- `runM6OwnershipStateMachineValidation`
  - 主类 `com.example.simulation.M6OwnershipStateMachineValidation`
  - 覆盖：共同代次/恢复状态机、journal 前后故障注入、失配 generation、玩家方块跳过、锁住新操作。
- `runM6ContainerValidation`
  - 主类 `com.example.item.M6ContainerValidation`
  - 覆盖：满桶退化、部分桶体积、合并余量、混合物账本、数据组件 fail-closed。

### 6.2 默认门禁路由

| 改动 | 默认跑 | 升级条件 |
|---|---|---|
| ownership 状态机/journal | `runM6OwnershipStateMachineValidation` | 改 region-state 才加 `runFluidRegionPersistenceValidation` |
| 容器账本/数据组件/配方 | `runM6ContainerValidation` | 改正式世界入口/协议才加 `runFluidNetworkProtocolValidation` 或 EULA=true server 并正常 `stop` |
| ownership 方块/世界放置/邻居更新 | 自动门无法观察，使用 GameTest 或人工 smoke | 改 entrypoint/资源打包才加 EULA=true server 并正常 `stop` |
| 活动核心（流动耦合） | `runFlipFreeSurfaceValidation` | 改长期 marker 分布才加 reseeding 门 |
| 文档 | 无 | 无代码/资源变化不构建 |

### 6.3 人工 smoke 清单（自动门无法观察时）

进入游戏前明确场景、命令、预期结果和要保留的日志。M6.1 至少完成：

1. `/realwater takeover` 后重启服务器：ownership 恢复，`/realwater status` 显示 generation 一致、无 lock；玩家实体方块未被覆盖。
2. 部分水桶：放置只放出实际体积；合并两桶不丢体积；damage bar 只显示比例；创造拾取不改变生存账本。
3. 发射器/漏斗/掉落/死亡各路径一例，确认数据组件不丢、不自动合并。
4. 代理方块或 registry 路线：原版 fluid tick、活塞、结构命令、火把/甘蔗邻居更新、常见 `#minecraft:water` 扫描各验证一例。
5. 功能门关闭后：新入口拒绝，已存在 ownership 可恢复/导出，世界状态不丢。

人工 smoke 按 `m0-takeover-smoke.md` 的失败判定保留世界副本、日志和截图。

### 6.4 回归策略

- M6.0 设计门：不跑旧门；只跑两个原型的定向 GameTest/manual smoke 和状态机门。
- M6.1 子阶段：每阶段只跑最贴近的一项定向门。
- 关闭 M6.1 前做一次汇总回归：`runM6OwnershipStateMachineValidation`、`runM6ContainerValidation`、`runFluidRegionPersistenceValidation`、`runFluidNetworkProtocolValidation`、`runFlipFreeSurfaceValidation`、`runM5GasLiquidCouplingValidation`、`runM4SurfaceTensionValidation`、`runM3ConnectedVesselValidation`。
- 不跑 5,000 步长期门、benchmark、clean build；只有部署/发布前才 clean build 一次。

---

## 7. 文件与职责变更草案

| 文件 | 变更 | 职责 |
|---|---|---|
| `src/main/java/com/example/simulation/ownership/OwnershipCommit.java` | `[NEW]` | 共同代次/状态机数据 |
| `src/main/java/com/example/simulation/ownership/OwnershipJournal.java` | `[NEW]` | ownership journal codec/atomic publish；不修改 takeover journal v1 |
| `src/main/java/com/example/simulation/ownership/OwnershipStore.java` | `[NEW]` | registry 路线或代理方块路线下的 ownership 持久化与对账 |
| `src/main/java/com/example/simulation/WorldFluidBridge.java` | `[NEW]` | 世界方块/容器/求解器之间的显式换算与事务编排 |
| `src/main/java/com/example/block/FluidProxyBlock.java` | `[NEW]` | 若选择代理方块路线 |
| `src/main/java/com/example/block/RealWaterBlocks.java` | `[MODIFY]` | 注册 proxy block |
| `src/main/java/com/example/item/RealWaterItems.java` | `[NEW]` | 注册部分水桶/混合物容器 |
| `src/main/java/com/example/item/PartialWaterBucketItem.java` | `[NEW]` | 部分纯水桶 |
| `src/main/java/com/example/item/RealWaterContainerItem.java` | `[NEW]` | 混合物容器 |
| `src/main/java/com/example/item/ContainerFluidLedger.java` | `[NEW]` | 容器纳体积账本，无 Minecraft 依赖 |
| `src/main/java/com/example/item/ContainerFluidCodec.java` | `[NEW]` | 数据组件往返与 fail-closed |
| `src/main/java/com/example/simulation/FluidServerConfig.java` | `[MODIFY]` | 新增 `worldBridgeEnabled` 可选键，默认 false |
| `src/main/java/com/example/simulation/FluidSimulationService.java` | `[MODIFY]` | 世界桥编排、恢复、状态输出 |
| `src/main/java/com/example/RealWaterCommands.java` | `[MODIFY]` | 容器/ownership 开发命令（管理员） |
| `src/main/java/com/example/network/FluidSnapshotPayload.java` | `[MODIFY]` | 仅在确需客户端观察 ownership 时增加可选段；不得破坏 v1/v2 独立版本 |
| `src/client/java/com/example/client/ClientFluidVolumeRenderer.java` | `[MODIFY]` | 仅当 ownership/容器需要客户端显示时只读渲染 |
| `src/test/java/com/example/simulation/M6OwnershipStateMachineValidation.java` | `[NEW]` | 状态机/恢复/故障注入门 |
| `src/test/java/com/example/item/M6ContainerValidation.java` | `[NEW]` | 容器账本/合并/codec 门 |
| `build.gradle` | `[MODIFY]` | 注册两个 M6 JavaExec 门 |
| `docs/architecture/world-ownership-v1.md` | `[NEW]` | 冻结后的 ownership 契约 |
| `docs/architecture/container-ledger-v1.md` | `[NEW]` | 容器账本与数据组件契约 |
| `docs/maintenance/routing.md` | `[MODIFY]` | 新增 M6 代码职责与最小门 |
| `docs/roadmap/product-roadmap.md` | `[MODIFY]` | M6 完成状态与后续约束 |
| `docs/progress/m6-world-bridge.md` | `[NEW]` | 验收证据 |
| `docs/progress/README.md`、`version-history.md`、`gradle.properties` | `[MODIFY]` | 关闭 M6.1，版本 0.12.0 |

---

## 8. 风险与回退

- **所选路线在 M6.1 中失败**：立即停止扩大范围，恢复 M6.0 另一原型，重新过设计门；不得用渲染补丁硬撑。
- **journal/ownership 与 region-state generation 不一致**：暂停整域，锁住新操作，保留世界副本与日志；绝不自动选择“看起来较新”的一边。
- **容器账本溢出/负数**：所有计算用 `long` 并显式 `Math.addExact`；失败即拒绝操作，不写物品组件。
- **跨 chunk 区域缺 chunk**：整域暂停，先 checkpoint/恢复，不得半加载步进。
- **旧世界升级**：M6 新增内容默认关闭；旧 `region-state v1/v2`、旧 takeover journal 与协议必须原样可读。若 ownership 需要新存档，只新增版本并保留旧 reader。
- **功能门关闭**：拒绝新激活；保留已有状态只读/导出；恢复原版状态必须走恢复事务。

---

## 9. 关闭 M6 的提交要求

- M6.0：提交原型、评估矩阵与决策记录，不升版本；commit message 形如 `Close M6.0 ownership design gate`。
- M6.1：完成代码、门禁、文档与人工 smoke 后，单次 commit 关闭：`Close M6.1 world bridge and containers`；包含 `gradle.properties` 0.12.0、routing/architecture/progress 更新。
- 提交前 `git diff --check` 通过；工作区干净；默认门禁已跑并记录在 progress 文档中。
- 不提交运行日志、世界副本、测试世界或临时文件。
