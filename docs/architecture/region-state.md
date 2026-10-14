# 区域 checkpoint 契约

修改区域存档、世界能力包、启动恢复或删除标记时读本文。

## 修改入口

| 要改什么 | 代码入口 | 测试选择器 |
|---|---|---|
| 世界启动与定义加载 | [WorldStartupCoordinator](../../fabric/src/main/java/mmm/simulation/WorldStartupCoordinator.java)、[RegistryBundleLoader](../../fabric/src/main/java/mmm/simulation/RegistryBundleLoader.java) | `region-state` |
| 世界能力包 | [WorldCapabilityBundleStore](../../fabric/src/main/java/mmm/simulation/persistence/WorldCapabilityBundleStore.java)、[WorldCapabilityStartup](../../fabric/src/main/java/mmm/simulation/persistence/WorldCapabilityStartup.java) | `region-state` |
| 捕获和恢复完整状态 | [MinecraftRegionCheckpointSource](../../fabric/src/main/java/mmm/simulation/MinecraftRegionCheckpointSource.java)、[RegionCheckpointAssembler](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointAssembler.java)、[RegionCheckpointValidator](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointValidator.java)、[RegionCheckpointRestorer](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointRestorer.java) | `region-state` |
| 文件编码与双槽提交 | [RegionStateCodec](../../fabric/src/main/java/mmm/simulation/persistence/RegionStateCodec.java)、[RegionGenerationStore](../../fabric/src/main/java/mmm/simulation/persistence/RegionGenerationStore.java) | `region-state` |
| 删除区域与隔离旧文件 | [RemovedGenerationStore](../../fabric/src/main/java/mmm/simulation/persistence/RemovedGenerationStore.java)、[RemovedQuarantineStore](../../fabric/src/main/java/mmm/simulation/persistence/RemovedQuarantineStore.java) | `region-state` |
| 登记和加载区域 | [RegionRepositoryAdapter](../../fabric/src/main/java/mmm/simulation/RegionRepositoryAdapter.java)、[FluidRegionOperations](../../fabric/src/main/java/mmm/simulation/FluidRegionOperations.java) | `region-state,region-manager` |

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [世界能力包](#世界能力包)
- [启动判定](#启动判定)
- [文件与职责边界](#文件与职责边界)
- [提交模型](#提交模型)
- [完整代次](#完整代次)
- [功能布局](#功能布局)
- [读取与 admission](#读取与-admission)
- [恢复](#恢复)
- [发布前同步](#发布前同步)
- [故障、清理与删除](#故障清理与删除)

## 世界能力包

`mmm/world-capabilities/` 保存世界唯一的世界能力包：五个 world registry snapshot 加一份 `profile.nbt`。它是已有库存的唯一解释——区域载荷里的 stable ID 只能按这个包的 registry 解析，区域 section 不复制 registry。

包是不可变的，发布一次，generation 恒为 1；定义漂移只报告 mismatch，不覆盖已提交内容。成员集合固定为 `chemical-registry.nbt`、`gas-registry.nbt`、`granular-registry.nbt`、`material-registry.nbt`、`profile.nbt`、`thermal-registry.nbt`，每个成员的字节就是对应 registry codec 的严格规范编码（`*RegistryCodec.snapshotBytes`），所以包内 checksum 与 codec 自身编出的字节逐字节一致。

**包是 registry snapshot 的唯一根**：五个 codec 没有各自独立的世界目录；若存在 `mmm/chemistry`、`mmm/gas` 等旧式 per-kind 目录，均按未知条目处理。

manifest 校验和覆盖 manifest 自身，HEAD 额外绑定 manifest 校验和、profile fingerprint 与 aggregate fingerprint。

发布序列与区域 checkpoint 同构：先在 `mmm/quarantine/world-capability-staging/<transaction>.preparing` 写 manifest，原子移为 `slot-a.tmp`；再按规范顺序写成员、回读校验；原子移为 `slot-a`；最后写 `HEAD.nbt.tmp`、回读校验、原子移为 `HEAD.nbt`。**只有 HEAD rename 是 commit point**，slot 在 HEAD 绑定它之前只是 staging。

重开时按目录内容判定唯一状态：`{HEAD, slot-a}` 已提交；`{slot-a, HEAD.nbt.tmp}` 补完 HEAD 移动；`{slot-a}` 补发 HEAD；`{slot-a.tmp}` 把中断的 staging 原样移入 quarantine 后重新发布；空目录首次发布；其他组合一律拒绝并原样保留。

staging 目录不作主动删除；无 publication identity 的 staging 判定为非法。quarantine 具有显式 admission 预算，超限时直接拒绝操作。

## 启动判定

`WorldCapabilityBundleStore#inspect` 只看目录名与全局权威存在性，不解码任何载荷，返回 `EMPTY` / `STAGING` / `COMMIT_CANDIDATE` / `COMMITTED`。`WorldCapabilityStartup#start` 把它翻译成唯一启动状态：

| 状态 | 含义 |
|---|---|
| `UNINITIALIZED` | 没有任何包，也没有完成发布 |
| `READY` | 包可证明且政策允许接入 |
| `LOCKED_PROFILE` | 存在包或权威但无法证明可接入；原样保留，不重新解释 |
| `POLICY_RESTRICTED` | 包可证明，但世界政策暂不接入；bundle 仍然可用 |

**恢复不查当前定义源**：`COMMIT_CANDIDATE` 只凭已写出的 slot 就补完 HEAD，不要求 datapack 仍能解码。已提交包即使与 datapack 不一致也保持权威，只报告 drift，不修复、不重发。

`mmm/` 下的权威域（`container-transfer`、`ownership`、`region-state`）只在存在已提交包时才允许存在；`quarantine` 是已知的持久证据归档根，但不拥有物理原始数据。没有包却出现权威，或出现任何其他未知条目，一律 LOCKED 并原样保留。这里没有迁移、没有版本嗅探、没有字段补全——旧实现产出的存档不被解释。

此规则覆盖 per-kind registry 目录：registry snapshot 仅允许保存在能力包内。发现遗留或未知目录时，世界置为 LOCKED，目录原样保留，严禁静默采纳或自动合并。同理，任何 `mmm/` 下无法归属的文件或目录均触发 LOCKED。

## 文件与职责边界

world save 下的 `mmm/region-state/<region UUID>/` 是区域物理状态的唯一持久根。目录内只有：

```text
HEAD.nbt
slot-a/manifest.nbt + section-*.nbt    # ACTIVE generation
slot-b/manifest.nbt + section-*.nbt    # ACTIVE generation
slot-a/removed.nbt                     # 或 REMOVED 删除标记
slot-b/removed.nbt                     # 或 REMOVED 删除标记
```

同一个 slot 只能是完整 ACTIVE generation 或只含一个 `removed.nbt` 的 REMOVED 删除标记，不能混合。逻辑原始数据是 loader-neutral `RegionCheckpoint`：`RegionIdentity`、`RegionGeometry` 与 `RegionRuntimeIntent` 只保存 stable value，manifest/section/extension 不引用 `BlockPos`、`ResourceKey`、live region 或 lifecycle class。

`MinecraftRegionCheckpointSource` 是唯一从 loaded `FluidRegion` 映射这些值并导出 solver snapshot 的 adapter；`RegionCheckpointAssembler` 按 pure/material/chemical/granular/thermal layout 组装完整 section、extension 和总账，`RegionCheckpointValidator` 审计跨 section identity/ownership/ledger，`RegionCheckpointRestorer` 只重建既有 immutable solver state。

`RegionState` 只是 durable store 的 27 行 DTO adapter，不 capture 或 reconstruct live runtime。`RegionStateCodec` 只编解码 ACTIVE NBT；`RemovedRegionManifest` 是不可重建 solver 的严格零库存终止原始数据；`RegionStateIntegrity` 生成与 NBT 字段顺序无关的规范 CRC32C。

`RegionGenerationStore` 独占 prepare/commit/abort/read/discover 状态机和 HEAD 发布顺序。它组合 package-private `RegionHeadStore`、`ActiveGenerationStore`、`RemovedGenerationStore`、`RemovedQuarantineStore` 与 `RegionDiscovery`：子 store 分别拥有严格 HEAD、ACTIVE、REMOVED、quarantine 和启动分类职责，但不能单独形成第二个提交点。

`RegionRepositoryAdapter` 组合 Minecraft checkpoint source、generation store、当前 registries/profile 与 live restore；`OwnershipTransactionCoordinator` 才执行 Minecraft 方块 ownership 事务。

任何业务层都不能直接写 slot、调用低层 HEAD writer 或把目录存在当成已提交。

HEAD 使用固定 `region-head` schema 并显式区分 `ACTIVE` / `REMOVED` kind。ACTIVE manifest 与每个 section 使用语义 `Layout`：`pure`、`material`、`chemical`、`granular` 或 `thermal`。

其中 `granular`、`thermal` 的附加 capability 精确声明是否同时携带 chemistry/granular 状态。启动时由已提交 world capability profile 得到唯一预期 layout 和 registry fingerprint，存档必须精确匹配；未知 kind/layout、未知必需 extension、缺字段或额外能力都不能按数字顺序猜测兼容性，也没有自动迁移或默认补全。

## 提交模型

每个 ACTIVE controller 区域使用两个完整 generation slot 和一个很小的 HEAD：

1. Minecraft source 在 server thread 从活动 solver 导出同一逻辑时刻的稳定 metadata 与完整载荷，assembler 形成 `RegionCheckpoint`。
2. 写入 inactive slot 的 manifest 与所有 section。
3. 回读、校验 CRC、身份、预算和全部账本。
4. 原子替换 HEAD，使新 generation 成为唯一提交点。

HEAD 发布前，当前已提交 generation 仍是唯一权威状态。失败的 staging 和被拒绝 slot 不得被当作已提交状态，也不得覆盖原 HEAD。

显式 controller checkpoint 先由 Minecraft adapter 冻结 region UUID、expected checkpoint generation 与 container-WAL presence，再经 loader-neutral `CheckpointRegionCommand` 进入 `RegionApplicationService`。

pending WAL 在触达持久端口前拒绝；`RegionCheckpointPort` 在写入前重新查找同一 region 并核对 generation、controller/loaded 身份与 ownership lock。

结果只使用封闭状态和 bounded diagnostic，Fabric 负责本地化；任何 stale、ownership 或 persistence 拒答都保留原 HEAD。

具体发布序列为：

1. 通过 `RegionCheckpointValidator` 验证内存 checkpoint 的形状与账本；durable store 只接收它的 `RegionState` adapter。
2. 读取并完整验证当前 HEAD；要求新 checkpoint generation 严格递增，且 base generation/checksum 未改变。
3. 按当前 HEAD、严格 slot 布局和 staging identity 证明 inactive/temp 内容确由 writer 产生；无法证明的文件原样保留并 LOCKED。只有已证明的旧内容可删除，随后在 `<slot>.tmp` 逐个写 section，每个文件 `force`。
4. 最后写 manifest 并 `force`，从临时目录回读全部文件，执行 CRC、预算、账本和可重建性验证。
5. 原子把 `<slot>.tmp` 移为 inactive slot。此时它只是完整 staging，仍不权威。
6. 写、`force`、回读 `HEAD.nbt.tmp`，再以 replace-existing 原子移动为 `HEAD.nbt`。
7. 按新 HEAD 再读一次完整 generation；成功才向调用者返回 generation。

目录 rename 保护 slot 完整性，HEAD rename 是唯一 commit point。若平台不支持所需原子移动则发布失败，不能退化为复制后删除。

### REMOVED 终止代次

`REMOVE_EMPTY_REGION` 先重新读取并验证当前 ACTIVE HEAD、完整 active generation、旧 manifest checksum 与 quarantine admission，再证明 inactive/temp staging 可安全替换，随后写入并回读唯一的 `removed.nbt`。

删除标记 保存 region/profile/registry identity、base/target generation、旧 manifest checksum、更高的 network 删除标记 generation、quarantine transaction UUID、固定零库存 digest 与自身 checksum。

只有 `REMOVED` HEAD 的原子替换是删除 commit point；此前 exact staging 可丢弃，ACTIVE generation 仍是唯一原始数据。容量预算只在该提交前准入；HEAD 已是 REMOVED 后必须继续幂等收敛，不能用变化后的预算拒绝已提交事务。

`REMOVED` HEAD 发布后，旧 ACTIVE slot 不再是库存原始数据，也绝不能用于恢复 solver。服务层先发送 network removal 删除标记 并注销 runtime owner，持久层再把旧 slot 原子移动到 `mmm/quarantine/removed-region/<uuid>/<target>/payload`，写入匹配的 `quarantine.nbt` 并回读 exact AFTER 状态。

删除标记 永久留在原 region UUID 路径，因此该 UUID 不能重新创建。

启动恢复用 `CrossDomainRecovery` 对 HEAD 与 quarantine 外部成员统一分类。ACTIVE HEAD 只允许清理实现自己产生且可完整验证的未提交 删除标记 staging；若 staging 已伴随任何 quarantine 变化则状态不再是 exact BEFORE，必须 LOCKED。

REMOVED HEAD 接受 old payload 尚在原 slot、payload 已移动但 quarantine manifest 未发布等可证明的 BEFORE，并幂等收敛到 AFTER；source 与 destination 同时存在、内容不匹配或任意第三状态一律保留并 LOCKED。

## 完整代次

一个 generation 至少包含：

- region UUID、dimension、network/checkpoint generation、origin、网格和 solver config；
- controller footprint、运行态和 marker 预算；
- Minecraft 对齐的 section 描述、marker ordinal、位置/体积、速度和 solid mask；
- 已提交 profile 要求的材料、化学、颗粒、气体、热能和 pending phase-work 载荷；
- manifest、section 与各独立库存的总账和 CRC。

所有 required payload 必须属于同一 region、dimension 和 generation。runtime handle、平滑 density、pressure、mesh 和 UI 读数不持久化。

### Manifest

求解配置包含 `ViscousWallCondition`（`FREE_SLIP` / `NO_SLIP`），编码与 manifest checksum 同时覆盖它。恢复材料域时由已提交能力包重新绑定黏度定义，黏性矩阵与物性派生数组在候选内重建。

manifest 是无需分配 solver 或 Minecraft runtime 对象即可审计和预算 admission 的索引，保存：

- `RegionIdentity` 中的 region UUID、dimension stable ID 与 checkpoint/network generation；
- `RegionGeometry` 中的 stable block origin、grid、完整 `FluidSolverConfig`、预留 marker capacity 与 controller footprint；
- `RegionRuntimeIntent` 中的 resume state、operator intent、entity/debug 开关、step interval 与 tick；
- controller position、内部/边界 block 集和 required chunk footprint；
- marker/volume/mass 总账、material/species palette 与 registry identity；
- 严格排序的 section descriptor：坐标、marker 数、体积、质量和 section checksum；
- granular、gas、thermal、pending phase-work 等 manifest extension 与各自总账；
- 覆盖上述规范编码的 manifest checksum。

metadata discovery 只能相信已经通过 HEAD 绑定、manifest checksum 和结构预算的 manifest。它可以据此保留 UNLOADED 区域的 cell/marker 配额，但不能据此虚构 solver inventory。

### Section

section 按 Minecraft section 对齐，是可独立校验的完整切片。基础数组采用 marker ordinal 作为 side-column 连接键：

```text
ordinal[]
positionVolume[] = x,y,z,volume
velocity[]       = vx,vy,vz
material[] + mass[]
solidMask[]
```

启用的 chemistry/granular/thermal extension 继续绑定相同 ordinal，并保存 cell-local deposit/coherent 数据。ordinal 在全 region 唯一且 cardinality 必须与所有 required side column 相等；section identity、local bounds、体积/质量/species/energy 汇总必须重新合到 manifest。

granular section 保存每份活动颗粒的 `ceilMask` 原始字节。完整重建按该颗粒的位置、SVQ 和域尺寸校验核支撑、floor/ceil 配额及份额精确总量，再恢复同一选择。

共同液体形状使用 `mmm:liquid_shapes` section extension，按全域 cell 递增保存精确二进制盒范围及实际发布载体的三轴体积位置矩；纯液体、材料与化学布局共用该载荷。每格最多八个互不重叠正盒，extension 上限为 16 MiB。读取校核 cell 归属、范围、碰撞掩码、原始体积与位置矩，以及几何位置矩的坐标存储误差界。恢复先安装载体与形状，再重建气体归属和压力几何。

运行时 handle 由 world registry snapshot 重新解析，数组位置不作持久化 stable ID。

section、palette、mixture、deposit group、gas node 和 ledger 都必须采用规范排序。这样 checksum、故障重放和相同状态的重复 capture 不依赖 hash-map 遍历顺序。

## 功能布局

当前 runtime 只按已提交 world capability profile 选择一种区域载荷：纯水、材料、化学、颗粒或完整热学。读取时必须与该 profile、bundle 内 registry snapshot 和 capability 完全一致。

不执行跨布局改写。布局、fingerprint、身份、总账或 required extension 不一致时，文件原样保留并进入 LOCKED。

未知的 length-delimited optional extension 可随读取保留，但不能被解释为 required truth；下一次从活动 solver 捕获时只写当前 runtime 明确定义的载荷。

布局选择严格依据已提交的世界能力包决定各扩展字段是否必须存在：

- materials 决定 marker 是否必须有 material ordinal 和独立 mass；
- chemistry 决定 mixture/indicator/species/residual 列及 registry fingerprint 是否 required；
- granular 决定 active/deposit、solid composition、SVQ/mass/porosity 与 granular ledger 是否 required；
- thermal 决定所有 solver carrier `U`、coherent store、gas topology/inventory/energy、外部注入与环境气体的配置温度、audit、buoyancy identity 和 pending phase-work 是否 required；配置温度以 Kelvin 原始 double 保存在热学 manifest，恢复时与库存一起交接。非空有限热设备集合是 manifest-only extension，其 provider/object 只作 locator，kg 与 `U` 仍由同一 region HEAD 持有。

一个 capability 存在时，它的 manifest 和每个相关 section extension 必须成套出现；不存在时也不得偷偷携带该 truth。unknown optional extension 只有在不影响当前 required truth 时才允许跳过。

## 读取与 admission

启动顺序是先加载并冻结 world registries 与 world capability bundle，再扫描 region-state root。扫描遇到非 UUID 条目、非目录、损坏 HEAD、未知必需布局或 checksum/identity 失败时保留原路径为 issue。若无法从路径安全识别 region，禁止创建所有新区；若能识别，则只锁对应 region。

完整恢复按以下顺序：

1. 由 HEAD kind 选择唯一 ACTIVE slot 或 REMOVED 删除标记，并验证 HEAD 中 UUID、generation 和 manifest checksum 的绑定；REMOVED 只做 quarantine 收敛，不进入 solver 重建。
2. 按 manifest descriptor 读取且只读取列出的 section；拒绝缺失、重复和额外未知 required 文件。
3. 检查长度、硬预算、canonical order、CRC 和所有分层 ledger。
4. 用 world registry stable ID 重建 runtime handle，验证 fingerprint/capability。
5. 重建 marker、chemistry、granular、gas 和 thermal side columns，执行跨层 cardinality 与 audit。
6. 只有全部成功才分配/激活 solver；随后刷新 collision，再发布 client snapshot。

restore 失败的区域保持 metadata-only UNLOADED/PAUSED 或 LOCKED。命令、world bridge 和容器转移不得绕过 admission 修改它。

可证明的 committed manifest 必须先作为 metadata owner 登记到 `RegionCatalog`，再单独执行当前 runtime policy 的 budget/placement admission。

catalog descriptor 只有 UUID、dimension stable ID、AABB 与 reserved cells/markers；它不持有 solver/repository，也不调用 restore。

`FluidRegionOperations` 在同一个 server-thread 工作流中按需检查内在数值失败、托管/ownership/policy、catalog admission 与边界；只有上一阶段通过才读取下一阶段事实。

数值失败与 ownership lock 就地保留已加载 solver；policy、catalog 或边界拒绝只有在合法完整 checkpoint 成功后才释放 solver。restore、checkpoint、collision refresh 与 snapshot publication 都留在这条 adapter 工作流。

`BUDGET`、`OVERLAP`、`CHUNK_MISSING`、`BOUNDARY_OPEN` 或 `POLICY_DISABLED` 只形成 availability block：metadata、reservation、checkpoint identity 和 operator intent 都保留。

profile/layout/fingerprint/HEAD 本身不可证明才形成持久 issue/LOCKED，不能把暂时预算不足混写成载荷损坏。

availability 使用封闭 reason code；详细诊断供日志和本地展示查看，状态机仅根据 reason code 转移。availability 变化不捕获库存；释放已加载 solver 时方可发布完整 checkpoint。恢复条件满足后重新从原 HEAD restore，并按持久 operator intent 决定是否 step。

operator intent 与 availability 在 metadata-only 状态也保持正交：操作者可以在不恢复 solver 的情况下把 UNLOADED region 的下一次运行意图设为 PAUSED 或 RUNNING；resume intent 不得为了立即运行而加载、唤醒或改写 checkpoint。

controller console 的 summary 只读现有 metadata 和已维护的 bounded ledger；live carrier totals、温度范围和需要 solver 的 usage 在 UNLOADED 时明确 unavailable。

显式 refresh availability 才可尝试当前 runtime admission/restore，成功后仍按保存的 operator intent 决定是否 step。

`RegionRuntime` 仅为单次 tick 的不可变调度快照，不进入 checkpoint。checkpoint 保存 resume state、operator intent、entity/debug 开关、step interval 与 tick；wall-time hard-overrun/recovery streak 是进程内短期调度指标，重启时不解释为持久 PAUSED 或数值失败。

## 恢复

- 正常启动只读取 HEAD 指向的 generation，不自动猜测另一槽。
- 只有当前 HEAD 已出现可复核 restore failure、区域仍为 metadata-only 且暂停、ownership 与容器 WAL 无歧义时，管理员才能执行 `regions recoverprevious <expectedCheckpoint>`。
- 恢复命令完整重建并审计紧邻上一槽，再原子移动 HEAD；成功后必须退出并重新进入世界。
- 该命令仅用于回滚到紧邻上一代同布局的有效存档，不修改被拒绝的当前槽位。

恢复上一完整代的原子边界仍然只有 HEAD：命令要求操作者给出当前 expected generation，先重新读取并完全重建 inactive slot，确认其恰为 `current - 1`；发布前再次核对当前 HEAD generation/checksum 未移动，再只替换 HEAD。被拒绝的当前 slot 保持字节不变，下一次正常 checkpoint 才可能覆盖它。

这条路径不自动触发，是因为“另一个 slot 可读”不能证明它比当前失败状态更符合操作者意图，也不能解决外部 ownership/item WAL 的歧义。

## 发布前同步

热区域 capture 前必须先把 current marker/occupancy 与 gas topology、gas inventory 和 gas energy ordinal 同步到同一个逻辑时刻。同步失败时 checkpoint 失败，活动 solver 保留，原 HEAD 不变。

同步为 capture 的前置必要环节：先根据待发布 marker/solid/coherent geometry 派生 cavity topology，再以稳定 overlap 规则规划 gas merge/split/ambient exchange，联合验证 gas inventory、node `U` 和 topology key。只有同步候选闭合后，section marker 和 gas extension 才能一起编码。

## 故障、清理与删除

发布路径在 slot 写前、每个 section 后、manifest 后、临时回读后、slot rename 后以及 HEAD rename 前后都有故障注入点。相关持久化测试必须证明任一点异常退出后，启动只会得到完整旧代或完整新代，不会把 staging 当成 HEAD。

只允许自动清理实现自己产生、名称集合与内容都完全可验证的首次发布或 ACTIVE 删除标记 staging；目录出现未知文件就原样保留并锁定。区域删除发布永久 `REMOVED` HEAD 后，旧 ACTIVE payload 移入持久隔离区，并保留可核对删除标记的 manifest 记录。

任何中断都只能恢复到 ACTIVE exact BEFORE、REMOVED 可收敛中间态或 LOCKED third state，不能让旧 payload 重新被 discovery 接纳为 solver。

普通 checkpoint 不删除上一完整 slot，因为它既是原子覆盖的另一半，也是显式 recoverprevious 的唯一候选。清理逻辑绝不能触碰 HEAD 当前指向的 slot。

气体区域载荷保存完整 `8N` 微盒归属主列 `pieceNodeOrdinal`，按全域 minimum-piece key 规范节点排序并随 section 拆分、组装。section 保留全域节点编号并校核 `sealedPieceCount`；恢复从完整载体几何重新生成微盒归属与容量，核对库存和热能的同序绑定。具体格式见 [气体状态](gas-state.md)。
