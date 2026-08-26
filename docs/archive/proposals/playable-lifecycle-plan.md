# 可玩生命周期与世界配置实施计划

## 目标

让玩家在新世界中直接制作或从创造栏取得组件，建立控制器区域、使用已实现的材料/化学/颗粒/热学/相变能力，并在退出重进后继续运行。管理命令只保留诊断、fixture 和恢复职责。

本计划优先修复的产品问题是：功能门由 `mmm/server.properties` 冻结，但命令可以为下一次启动修改它；已有区域仍绑定原载荷布局。玩家启用 thermal 后创建区域，再把配置改回或进入另一套启动设置，会得到合法但不可用的 layout mismatch LOCKED。严格拒绝错误解释是正确的，允许普通交互制造这种状态则不是合格产品行为。

## 设计决策

### 世界能力与运行策略分离

新增 world capability profile，作为该世界所有区域载荷能力的唯一解释来源。profile 和它引用的 registry snapshots 必须作为一个 bundle 在首次服务启动时原子发布，之后不可由普通命令修改。不能先发布 profile 再分别创建 registry，也不能先让独立 registry 文件成为权威后再补 profile。

profile 至少冻结：

- materials、chemistry、granular、precipitation、thermal、phasechange 能力集合；
- material、chemical、granular、gas、thermal 的逐 registry fingerprint，以及按 registry kind 规范排序计算的 aggregate fingerprint；
- 能决定 checkpoint 必需 section/extension 的语义布局；
- profile 自身的 canonical fingerprint。

`server.properties` 只保留不会改变载荷解释的策略：controller 是否允许新建、world bridge 是否允许接管、region/cell/marker/parcel/substep 等预算、调度与管理员策略。改变这些策略可在重启后生效，但不得改变已提交 inventory 的字段集合。

不要从所有现有 region 猜测 profile，也不要为缺失 profile 补字段。项目不要求存档迁移，因此实现完成后只接受直接创建出的完整 profile；遇到 region 数据但没有 profile 时原样保留并 LOCKED。

### 默认能力

新世界默认 profile 应启用已经达到产品基线且具有完整 checkpoint 的能力。候选默认集合是 materials、chemistry、granular、precipitation、thermal、phasechange 全部启用。落地前逐项核对：

- 有权威 registry snapshot；
- region writer/reader 与 network negotiation 支持完整组合；
- 空库存不会产生额外 source/sink；
- 默认数据包的物性有效域覆盖水、环境气体和常见初始温度；
- 空闲成本可以休眠，未使用能力不会显著增加常态 tick 成本。

这次格式可以直接替换且不承担已有存档迁移，因此产品实现不保留“受限 profile”作为逃生口：上述六项能力必须同时成为新世界默认值。若任一项缺少完整 registry/checkpoint/休眠路径，就先修复该缺口并阻止 P0/P1 关闭，不能用另一套默认 layout 绕过。profile 一旦发布就不可更改。

### 命令语义

`/mmm materials`、`chemistry`、`granular`、`precipitation`、`thermal` 和 `phasechange` 的 boolean 参数直接移除；无参数形式只打印已提交 profile。不要留下“仅在 profile 尚未发布时可写”的普通命令，因为首次 service 启动会立即发布默认 bundle，不存在玩家可安全介入的窗口。测试若需要其他布局，直接构造纯 Java fixture，不在真实世界制造第二种解释。

`controllers` 与 `worldbridge` 可以保留为运行策略，但语义必须收窄：

- `controllers=false` 只拒绝创建新 region，不阻止 profile/registry/region discovery、WAL recovery、ownership reconcile 或已有区域显式恢复；
- `worldbridge=false` 只拒绝新的原版水接管，不撤销或忽略已 committed ownership，也不阻止未决 ownership WAL 恢复；
- player/dispenser/creative/public typed transfer不是world takeover，必须从`worldbridge`条件中拆出；它们各自由region availability、target和actor/WAL门控制。当前`FluidTransferService`入口的world-bridge短路需要移除，否则策略关闭会让正式容器不可用；
- 预算变化只影响新 admission 和当前 residency。已提交 manifest 超过新策略预算时保留 metadata 并 PAUSED，不变更 layout、fingerprint 或库存；
- `server.properties` 读失败时保留原文件，仍先严格读取 profile 和 region；使用安全策略拒绝新建/新接管，但不把默认布尔组合带入存档解释。

## 工作包

### 能力 profile 真值

在 Fabric 持久层增加独立 codec/store，复用 `DurableFileOperations` 与 `StrictNbtFields`：

1. 定义 loader-neutral `WorldCapabilityProfile` 值对象，只使用语义 capability、fingerprint 和确定排序。
2. 定义唯一 writer shape；字段缺失、额外字段、未知 capability、重复 capability 或不合法依赖链全部拒绝。
3. 首次发布使用临时文件、force、回读、atomic rename、目录同步。
4. 启动顺序改为 profile → 所需 registry snapshot → region discovery。
5. `FluidSimulationService` 不再从易变 boolean 组合计算 region layout，而从 profile 得到唯一 layout expectation。
6. network offer、Query capability 和创建 controller 均读取同一 profile，禁止分别计算。

profile 不拥有 material/species 的定义内容；定义由同一个已提交 bundle 中的 registry snapshot 拥有。profile 只绑定需要哪些 registry、逐项 fingerprint、aggregate fingerprint 和唯一 layout。材料定义也必须有 world-local snapshot；不能继续只依赖当前 JAR 的 `BuiltinFluidMaterials` 来解释新建库存。

### A01：profile 与 registry bundle 的磁盘形状

使用一个新的 world-local 根，不复用目前分别独立提交的 registry 文件：

```text
<world>/mmm/world-capabilities/
  HEAD.nbt
  slot-a/
    manifest.nbt
    profile.nbt
    material-registry.nbt
    chemical-registry.nbt
    granular-registry.nbt
    gas-registry.nbt
    thermal-registry.nbt
  slot-b/                         预留给显式格式替换；正常产品不更新冻结 profile
```

`HEAD.nbt` 是 bundle 的唯一 commit point，至少保存固定 schema、bundle generation、slot、profile fingerprint、registry aggregate fingerprint 和 slot manifest checksum。`profile.nbt` 采用唯一严格 shape：

- schema 与 unit identity；
- 按 stable capability ID 排序且无重复的 capability 列；
- 唯一 `regionLayoutId`，完整默认能力只能解析为 `thermal` layout，并要求 chemistry/granular extensions 同时存在；
- `material/chemical/granular/gas/thermal` 五个具名 fingerprint；
- registry aggregate fingerprint；
- profile fingerprint。

依赖图固定为：`chemistry -> materials`、`granular -> materials`、`precipitation -> chemistry + granular`、`thermal -> materials + gas registry`、`phasechange -> thermal`。gas registry是完整thermal layout的required interpretation，即使不把“gas”暴露成独立玩家toggle也必须进入bundle。完整默认profile包含六个公开capability和五份registry，不能把空inventory当成省略required registry的理由。

aggregate fingerprint 对按 registry kind 排序的 `(kind, fingerprint)` canonical UTF-8 编码做 SHA-256；profile fingerprint 再覆盖 schema、unit、capability、layout 和 aggregate fingerprint。不要把路径、slot 名、时间戳或 Java enum ordinal 纳入 fingerprint。

每个 registry 文件继续使用领域 codec 的严格字段集合与 canonical fingerprint，但 store 改为 bundle slot 内的只读 codec。新增 `FluidMaterialRegistry` 的 world snapshot，至少冻结当前 palette、miscibility/phase、surface/contact、液液 pair 和物理计划 C01 引入的动态黏度定义。runtime handle 从 bundle 恢复，不能从当前代码顺序重建后再声称 fingerprint 相同。

首次发布顺序固定为：

1. 只读扫描 `mmm/` 下 profile、旧 registry、region-state、ownership、container-transfer 与未知条目；任何已存在权威/未知数据但无合法 bundle HEAD 都保留并形成全局 LOCKED，不猜测它们属于默认能力。
2. 从当前数据包与内置 gas/material 定义构造五个 registry，完成所有跨 registry 引用、物性有效域和预算验证。
3. 构造 profile，验证六项能力依赖和 `thermal + chemistry + granular` 唯一 layout。
4. 在 `<slot>.tmp` 写全部 registry、`profile.nbt` 和内部 manifest；逐文件 force。
5. 从临时目录严格回读每个文件，重新计算逐 registry、aggregate、profile 与 manifest checksum；不得复用内存对象的结果。
6. 原子 rename 临时目录为 inactive slot并同步父目录；此时仍没有权威 profile。
7. 写、force、回读 `HEAD.nbt.tmp`，最后原子替换 `HEAD.nbt` 并同步目录 entry。
8. 按新 HEAD 再读整个 bundle并构造 runtime registries；成功后 service 才进入 `READY`。

首次失败只允许留下无 HEAD 的已知 staging 或完整 bundle。下一次启动可以删除/覆盖 writer 自己产生、名称和内部 transaction identity 完全匹配的 staging；出现额外文件、错类型或无法证明来源的残留则保留并 LOCKED。不能在缺 profile 时把已写出的某个 registry 当作已提交真值。

### A02：启动状态机与 admission

服务启动明确区分：

| 状态 | 条件 | 允许行为 |
|---|---|---|
| `UNINITIALIZED` | 无 HEAD、无任何 MMM 权威/未知数据 | 尝试一次默认 bundle 首次发布；失败后保留未初始化状态 |
| `READY` | HEAD、slot、profile、全部 registry 与当前 writer 支持精确匹配 | discovery、recovery、controller admission、Query/协议 |
| `LOCKED_PROFILE` | HEAD 或 bundle 不可唯一解释，或有 region/WAL/旧 registry 但无 profile | 只读诊断和显式维护；禁止新区、transfer、world bridge 和 checkpoint 覆盖 |
| `POLICY_RESTRICTED` | bundle 可解释，但 `server.properties` 无效或策略禁止新建 | 已有状态仍按 profile recovery；拒绝新 admission/新 takeover |

启动调用顺序必须是 bundle discovery/read-or-first-publish → runtime registry restore → container/ownership store scan → region discovery/admission → protocol ready。当前 `FluidSimulationService` 中按 boolean 条件分别 `loadOrCreate` registry 的路径应被删除，不能在 profile 读取前产生文件。

已有READY bundle启动时直接从slot内snapshot构造runtime registry；当前数据包只可在后台做canonical fingerprint比较并报告definition mismatch。数据包缺失、增加或损坏不能让有效world snapshot失效，也不能覆盖bundle；反过来，bundle自身无效时也不能用当前数据包重建。

region manifest admission 精确检查 profile fingerprint、aggregate fingerprint、layout、逐 required registry fingerprint 和 extension presence。network offer 携带同一 profile fingerprint/layout/capability；客户端不支持时只拒绝该连接的 MMM payload，不改变服务端 profile。Query 和 controller summary 从同一个 service-owned profile view 读取能力，不能再询问 `FluidServerConfig` boolean。

### A03：策略配置收缩

`FluidServerConfig` 只保存下列类别：

- 新 controller/new takeover 是否允许；
- per-region/server cell、marker、parcel、deposit、device、substep、phase-operation 与 world-scan 预算；
- pacing、自动暂停和管理员诊断策略；
- 不改变库存的可选兼容/观察策略。

移除六个 capability boolean、对应 `with...Enabled` 和 service 的 `set...ForRestart`。读取器仍严格要求当前完整策略字段，但策略文件不是 inventory identity；字段损坏造成 `POLICY_RESTRICTED`，不能以另一 capability 组合构造 `FluidRegionPersistence`。`RegionStateLayout` 的选择集中在一个 `WorldCapabilityLayoutResolver`，任何其他 `thermal ? ... : ...` 链都应由 `rg` 找出并替换。

### A04：代码入口与最近验证

| 修改 | 首要入口 | 复用/新增类型 | 最近 selector |
|---|---|---|---|
| profile 值、依赖、fingerprint | `core/.../worldprofile/`（新包） | `WorldCapabilityProfile`、`RegistryFingerprintSet` | `region-state` |
| bundle codec/store/故障点 | `fabric/.../simulation/persistence/` | `DurableFileOperations`、`StrictNbtFields`、`RegionStateIntegrity` 的 canonical 思路 | `region-state` |
| material world snapshot | `FluidMaterialRegistry`、新 material codec | 不保存 runtime handle | `materials,region-state` |
| 启动顺序与 policy | `FluidSimulationService`、`FluidServerConfig` | service-owned immutable profile view | `lifecycle,region-state` |
| layout/admission | `RegionStateLayout`、`FluidRegionPersistence` | `WorldCapabilityLayoutResolver` | `region-state` |
| offer/snapshot | `FluidProtocolOfferPayload`、`FluidProtocolServer`、`FluidSnapshotPublisher` | 同一 profile fingerprint | `protocol` |
| 命令只读化 | `MmmCommands` | 删除 capability writer | `audit,lifecycle` |

不要新建 `profile` selector；严格 codec/commit failure 属于 `region-state`，启动/admission 属于 `lifecycle`。只有现有验证类无法承载 profile bundle 的跨文件故障注入时，才在这两个 selector 下增加验证类。

### 控制器创建和恢复

检查 `FluidControllerBlock`、controller footprint、`FluidRegionOperations` 与 `FluidRegionPersistence`：

- server service 首次启动若确认世界中没有任何 MMM 权威或未知遗留数据，立即发布默认 bundle；controller 右击从不承担初始化事务，只消费已提交 profile。bundle 未发布或被锁时，右击只返回失败，不修改世界或创建 region 目录。
- 创建成功后立即运行；checkpoint 首次 HEAD 发布成功前不向客户端宣称区域已创建。
- 玩家暂停意图继续随 region 保存；availability pause 不覆盖该意图。
- 边界破损、chunk 未加载、registry/profile issue 和预算不足使用不同本地化错误，不把 LOCKED 简化成“无法运行”。
- 重进世界时 profile/layout 一致的区域自动发现；若玩家意图为运行且边界可用，则自动加载和继续调度。
- 不申请永久 chunk ticket；缺 chunk 保持 PAUSED/UNLOADED，chunk 恢复后重新 admission。

### A05：controller 创建事务

controller 普通空手交互保持一个入口，不增加“先执行初始化命令”的隐藏前置。创建路径按以下顺序：

1. 要求 service 为 `READY`，策略允许新建，container/ownership/profile store 没有全局不可归属 issue。
2. 只读扫描 controller footprint、required chunk、重叠 owner、预算和 world preimage；扫描结果绑定 profile fingerprint。
3. 创建完整 domain candidate，所有默认 capability 的空 side column、ambient/sealed gas、initial `U` 和 ledger 必须同时存在。
4. 若接管纯原版水，走已有 ownership prepare；否则仍先 stage 初始 region generation。
5. 回读 stage，验证 profile/layout/registry/identity/预算/账本。
6. 需要 ownership 时按 WAL→世界修改→HEAD→committed 收敛；空 region 直接以 HEAD 为创建 commit point。
7. HEAD 回读成功后才登记 owner、发送 snapshot 和成功消息；失败不留下 live region 或客户端幽灵区域。

初始 operator intent 固定为 running。`availability` 是由当前边界/chunk/预算/LOCK 状态派生的正交字段，不能把初始 admission 暂停写成玩家暂停意图。已有 controller 普通右击只选择；潜行空手右击只切换 operator intent，并在下一完整 HEAD 保存。

### A06：重进、chunk 与边界状态机

server 启动从 profile bundle 和 region HEAD 恢复 metadata。对每个 region：

- profile、identity、layout、fingerprint 或 payload 不明时保持 `LOCKED`，不得访问另一 slot或创建空 solver；
- 预算暂时不足时保留 manifest reservation/metadata，状态为 `PAUSED_BUDGET`；提高策略预算后下一次 admission 可恢复；
- required chunk 未加载时为 `UNLOADED_CHUNKS`，不申请 ticket；相关 chunk load 事件只触发有界重新检查；
- 壳破损或 controller 被替换时为 `PAUSED_BOUNDARY`；修复后用持久 footprint 和实际 shape 重新验证，再按 operator intent 决定运行；
- operator intent 为 paused 时，即使 availability 恢复也不自动 step；为 running 时 availability 恢复后自动调度；
- 任何 availability 变化都不 capture 新库存来“更新状态”，只有合法完整 logical state 才 checkpoint。

把失败原因改为封闭 reason code，再由 `zh_cn`/`en_us` 映射。至少区分 `PROFILE_LOCKED`、`REGISTRY_MISMATCH`、`HEAD_INVALID`、`BOUNDARY_OPEN`、`CHUNK_MISSING`、`OVERLAP`、`BUDGET`、`PENDING_WAL`、`PENDING_PHASE_WORK`、`NUMERICAL_PAUSE` 和 `POLICY_DISABLED`。日志可附详细诊断，网络/UI 不发送磁盘路径或异常堆栈。

### 创造模式体验入口

现有 boundary/controller 和容器继续复用。补齐以下产品入口：

- 按 stable material ID 选择的创造材料容器；化学、颗粒和热内容只能由合法 registry 定义构造。
- 控制器或专用检查工具显示区域 UUID、运行意图、availability、尺寸、预算占用、主要库存和温度范围。
- 删除/释放操作区分“只删除空区域”“释放 ownership preimage”“清空凝聚相并记 sink”；危险动作必须有明确确认，不把 `empty` 当普通 UI 按钮。
- 所有正式 item/block 有模型、贴图、创造标签、取得方式、`zh_cn`/`en_us`、tooltip 和失败反馈。
- 玩家无需知道 region UUID；位置和控制器是正常路由，UUID 只用于诊断和恢复。

不要为创造模式另写直接修改 solver 数组的捷径。创造放出继续走 existing transfer primitive 并记 external source；创造装取仍删除真实库存。

### A07：创造内容目录与可信 source

建立一个 MMM 自有创造标签页，按“建造→观察/控制→液体→化学→颗粒→气体→热设备”固定排序；基础 block 仍可保留在原版分类。P1 的正式目录至少包含：

| 条目 | 形状 | 作用 |
|---|---|---|
| boundary、controller | block item | 建立闭壳与正常 lifecycle |
| controller console / field probe | tool item | 打开 controller summary；对壳内侧执行只读 cell/gas/solid probe |
| 空 material bucket | typed container | 从区域装取任意可表示 liquid truth；也可回放实际内容 |
| water、oil、mercury、water/oil sample | creative source descriptor | 在服务端解析为实际 nanos/kg/`U` 后走 transfer；descriptor 本身不是库存 |
| HCl、NaOH、NaCl、NaNO3、KCl、KNO3、water reagent sample | creative source descriptor | 从已提交 chemical registry 构造 exact species/residual/mass/`U` |
| indicator、eraser | tool item | 复用现有 parcel provenance 交互 |
| 空 granular container | typed container | 装取 active/deposit 可表示真值 |
| fine sand、light beads sample | creative source descriptor | 构造实际 SVQ/kg/组成后走 granular insertion |
| gas source/vent probe | creative-only port/tool | P1 可至少注入/抽取已声明 gas component 的 exact 标准体积；为 C05 的 CO2 场景预留，不伪装成液体桶 |
| adjustable fixed reservoir | block + tuning interaction | 无限热浴，设定值是世界 boundary truth |
| finite heat exchanger | 独立 block/item | region-owned kg/`U` body，不能与固定温度源共用真值 |

创造标签页在客户端构建，不能可信地嵌入当前世界 registry fingerprint 和物质库存。因此 filled sample 使用 `CreativeSourceDescriptor(templateStableId, requestedTypedAmount, optionalTemperatureK)`，只表达玩家意图：

1. 仅创造模式玩家可执行；服务端重新验证权限、目标和 descriptor 白名单。
2. 从已提交 world profile/registry 解析 stable IDs，并用状态函数构造完整 `FluidMixture`/`ChemicalContents`/`GranularContents`/gas settlement 与可选 `ThermalContents`。
3. 解析失败或温度越出共同物性域时在修改 solver 前拒绝；不从客户端提供的 kg/species/`U` 接受真值。
4. 解析后的 actual settlement 进入现有 transfer planner、region candidate、WAL/HEAD 和 external source ledger；descriptor 不进入 checkpoint。
5. descriptor 在创造放出后保持不变。创造装取必须改用空 typed container，生成真实 after stack并删除区域实际库存；不能把 probe/source descriptor 当无限 sink。

正式容器 item 仍只携带完整 typed truth；没有 profile fingerprint、质量/species/residual/`U` 的“半成品桶”严格拒绝。当前 `/mmm materialbucket`、`granularcontainer`、`reagent` 只保留 fixture 价值；P1 验收不能调用它们，后续可删除重复命令。

每个目录条目同时提交 item/component codec、model/texture、tooltip、`zh_cn`/`en_us`、创造排序和目标失败消息。tooltip 明确区分“创造 source 模板”和“实际已装填容器”，避免玩家把模板当成持久库存。

### A08：无需 Jade 的观察闭环

Jade 保持可选；P1 不能依赖玩家另装 Jade。新增一个只读 field probe，并复用而不复制 Query/controller summary：

- 对 controller 使用：打开 server-authored controller console；
- 对 boundary/reservoir/exchanger 使用：解析命中面的内侧 world position，调用 Query inventory view并合并一个独立的 gas/solid observation；
- 对未命中已加载 owner 的空气格不从客户端 mesh 反推目标；需要直接自由面选择时由服务端对 region bounds 做有界 ray traversal并最终落到 world position Query；
- probe 不加载 chunk、不唤醒、不 checkpoint、不改变选择 region；非 OK 显示结构化原因，不回退到方块外观。

单格结果至少显示：材料 nanos/kg、density、Top-N species/quanta、active/deposit/coherent SVQ/kg、可选 K、gas ambient/sealed、component standard nanos/kg、可选 Pa/K。展示有固定条目上限并标记隐藏数量；完整库存仍以 region truth 为准。

固定温度 reservoir 通过 probe/专用 tuning screen 显示并修改 setpoint。输入使用 K，服务端验证有限值、thermal registry boundary 允许区间与权限，保存 BlockEntity 后触发精确 thermal wake。setpoint 只影响未来边界通量，不直接改 region `U`；不要用 `/mmm thermalsource` 作为正式路径。

finite exchanger 的温度只由 region body `U` 反演。BlockEntity 不保存温度副本；locator 未 enrollment 时显示明确状态，不能从 UI setpoint 创建能量。

### A09：controller console

console 通过 controller 位置解析 region，不要求玩家输入 UUID。server 每次打开或操作后重新生成有界 `ControllerSummary`，字段包括：

- region/dimension/profile fingerprint 短摘要、footprint 与 checkpoint/network generation；
- operator intent、motion state、thermal state、availability reason；
- cells/markers/chemical parcels/active parcels/deposits/coherent groups/gas nodes/finite devices 的使用量与硬预算；
- 按载体分类的总体积/质量/species/SVQ/gas standard volume/总 `U` 摘要与温度范围；
- ownership、container WAL、phase-work、world bridge 和 checkpoint 是否允许；
- 最近一次安全暂停 reason code 与有界数值诊断。

console 允许的普通动作只有 select、pause/resume、refresh availability 和安全 lifecycle 操作。管理员 recovery、fault injection 和完整 transcript 仍留在命令。客户端请求携带 region UUID、controller position、summary generation 和 action；服务端重新解析 owner并拒绝 stale action，不能信任客户端显示的按钮状态。

summary优先读取region已维护的ledger、manifest metadata和调度诊断；不为每个打开的screen每tick扫描全marker或重建thermal grid。server只在打开、明确刷新、commit或低频状态变化时发送新summary；关闭screen立即停止订阅。观察频率不唤醒motion/thermal，也不改变snapshot发送策略。

### A10：清空、释放与删除 UX

把三个不同语义拆开：

| 动作 | 前置 | 提交结果 |
|---|---|---|
| `EMPTY_CONDENSED` | 无任何 WAL、无 pending phase-work、账本闭合 | 联合清 liquid/chemical/active/deposit/coherent，逐真值记 escaped sink；gas 保留 |
| `RELEASE_OWNERSHIP` | 凝聚相为空、无 WAL、preimage 可解释 | 走 ownership release，恢复可恢复 preimage；玩家占位不覆盖 |
| `REMOVE_EMPTY_REGION` | 凝聚相为空、ownership 已释放/无记录、无 finite device truth、无 WAL | quarantine region root、发送 tombstone、注销 owner |

console 先返回 `LifecycleOperationPreview`：绑定 region generation，列出实际将移除的 nanos/kg/species/SVQ/`U`、gas 是否保留、ownership block 数和所有阻止原因。危险动作必须再次提交同一个 preview token；generation 或任一 WAL/availability 改变就过期并重新预览。token 只在内存短时存在，不是 commit point；真正提交仍由现有 candidate/WAL/HEAD 路径完成。

创造模式直接破坏已绑定 controller/boundary/device 时不能绕过 coordinator。拦截破坏并给出“先通过 controller console 释放/删除”的反馈；未绑定普通方块仍按原版破坏。不要在 block removal callback 中猜测 region 已空。

### A11：资源、反馈与可发现性检查表

每个正式对象完成时逐项检查：

- 注册、item component、创造标签和必要 recipe/loot；
- blockstate、block/item model、贴图与粒子纹理；
- `zh_cn`/`en_us` 名称、tooltip、所有结构化失败 reason；
- 使用声音来自实际 committed action，失败不播放成功声；
- 客户端协议不认识新 payload/component 时明确拒绝，不显示空白 UI；
- dedicated server 不加载 client/Jade 类；Jade 未安装时主 Mod 完整启动；
- creative source、actual container、fixed reservoir、finite device 的名称和 tooltip 不混淆真值。

资源存在性在 `assemble`/resource load 时检查；行为不变量落入最近 selector。不要为贴图存在性新增长期 CPU selector。

### A12：P1 玩家验收脚本

自动门全部通过后只请求一次真实游戏验收，场景固定为总计划 CX-01 至 CX-12 的一座闭壳实验室。执行者记录：

1. 全新世界，无 `mmm/` 目录；进入后从 MMM 创造标签取得全部条目。
2. 建一个有 headspace、一个 fixed reservoir face、一个 finite exchanger 的闭壳，空手激活。
3. 分别注入 water/oil/mercury、HCl/NaOH、fine sand/light beads；用 probe 记录提交后库存。
4. 设置冷热边界，观察导热、沉淀和至少一种相变；进入液体观察排水/浮力。
5. 潜行空手暂停/继续；切换维度或移动到足以卸载required chunk的位置，再返回确认availability恢复且operator intent不变。第三方边界破坏由自动fixture覆盖。
6. 退出到标题并重新进入；确认 profile fingerprint、region generation、operator intent、device `U` 和主要库存仍一致并继续运行。
7. 通过 console 预览并执行 empty→release→remove；确认 gas 保留语义在 empty 预览中明确，最终 tombstone 后客户端不残留 mesh。

唯一日志证据是本次 world 的 profile bundle publication/restore、region HEAD generation、WAL recovery（若触发）和结构化 pause reason；不要求执行任何 fixture 命令。若自动化无法覆盖跨重启和真实 UI，才使用本脚本，不为每个物理现象分别请求人工测试。

### 状态反馈

定义一个 loader-neutral、只读的 controller summary，再由 Fabric 转换成文本或界面。summary 只包含已提交状态：

- identity、dimension、footprint、network/checkpoint generation；
- operator intent、motion/thermal state、availability 或 LOCKED 原因分类；
- cell/marker/parcel/device 预算使用量；
- inventory/energy 的有界摘要；
- 未决 ownership/container/phase-work 标志。

不要把完整错误堆栈、磁盘路径、pressure array 或 live domain 暴露给客户端。Jade 继续走 Query，不读取这个管理 summary 推断流体库存。

## 失败语义

- profile/registry/checkpoint identity 不明：LOCKED，保留证据。
- 边界破损、chunk 不全、预算暂时不可用：PAUSED，不改变 operator intent。
- 玩家尝试改变已冻结 capability：拒绝命令，不修改任何文件，不锁已有 region。
- profile 首次发布失败且仅有 writer 可证明的 staging：世界保持 `UNINITIALIZED`，允许修复外部条件后重试；存在无法归属的文件则 `LOCKED_PROFILE`。
- 默认物性无法解释初始 ambient/温度：阻止 profile 发布，不发布半套 registries。

## 验证计划

优先扩展或替换 `region-state`、`lifecycle`、`region-manager`、`containers` 和 `protocol` 的同类覆盖：

| 覆盖 | 归属 selector | 必须断言 |
|---|---|---|
| bundle strict round-trip | `region-state` | capability/order/layout、逐 fingerprint、aggregate/profile checksum、五份 registry 全量相等 |
| bundle fault boundary | `region-state` | 每文件写/force、slot rename、HEAD 写/rename 前后只读到无 HEAD 或完整 bundle |
| bundle malformed matrix | `region-state` | 缺/多字段、错类型、未知/重复 capability、错依赖、错 fingerprint、额外文件均保留并锁定 |
| legacy/partial world | `region-state` | 有 region、旧 registry、ownership 或 WAL 而无 bundle HEAD 时不发布默认 profile、不改任何字节 |
| unique layout derivation | `region-state` | 完整 capability 只得到 thermal + chemical/granular extensions；找不到第二条 boolean derivation |
| boot/admission | `lifecycle` | READY/LOCKED/POLICY_RESTRICTED 路径，自动恢复，operator intent 与 availability 正交 |
| budget restart | `region-manager,lifecycle` | 降预算只暂停已提交 region；恢复预算后相同 HEAD 可 admission |
| command semantics | `audit,lifecycle` | capability 命令只读且不写 config/profile；controllers/worldbridge 只改变未来 policy |
| creative descriptor trust | `containers` | 客户端伪造 mass/species/`U` 不被接纳；服务端 registry 解析后的 actual settlement 进入 source ledger |
| creative extraction | `containers` | 仍删除实际库存，after stack 是完整 typed truth，WAL 使用 actual amount |
| summary/probe | `protocol,lifecycle` | 不加载、不唤醒、非 OK 无部分 truth；stale console action 拒绝 |
| lifecycle preview | `lifecycle,ownership,containers` | generation/WAL 改变使 token 过期；empty/release/remove 不互相代替 |

跨重启 world transaction 和真实 UI 的最终证据只使用 A12；视觉部分并入渲染计划 RV-01，不重复请求人工场景。

## 推荐提交顺序

1. A01：profile/fingerprint 值对象、material registry snapshot 与 bundle codec，不接 service。
2. A01/A02：bundle store、故障注入和 HEAD-last 首次发布。
3. A02/A03：启动读取、唯一 layout derivation、策略配置收缩和命令只读化。
4. A05/A06：controller 创建、自动恢复、availability reason code。
5. A07：creative source descriptor、正式目录、container route 和资源。
6. A08/A09：probe、controller summary/console、fixed reservoir tuning。
7. A10：empty/release/remove preview 与破坏拦截。
8. A11/A12：资源检查、自动门、单次游戏验收和 architecture/routing/roadmap 收口。

每个提交都只跑 routing 对应最近门。最后需要部署时再执行 clean assemble。
