# 区域 checkpoint 契约

## 文件与职责边界

world save 下的 `mmm/region-state/<region UUID>/` 是区域物理状态的唯一持久根。目录内只有：

```text
HEAD.nbt
slot-a/manifest.nbt + section-*.nbt
slot-b/manifest.nbt + section-*.nbt
```

`RegionState` 在 server thread 上捕获/重建完整逻辑状态；`RegionStateCodec` 只编解码 NBT；`RegionStateIntegrity` 生成与 NBT 字段顺序无关的规范 CRC32C；`RegionStateFiles` 只管理双槽、回读验证与原子 HEAD。任何业务层都不能直接写 slot 或把目录存在当成已提交。

HEAD 使用固定 `region-head` schema；manifest 与每个 section 使用语义 `Layout`：`pure`、`material`、`chemical`、`granular` 或 `thermal`。其中 `granular`、`thermal` 的附加 capability 精确声明是否同时携带 chemistry/granular 状态。唯一预期 layout 由 committed world capability profile 推导；manifest 必须精确绑定 profile fingerprint、registry aggregate fingerprint 和 material registry fingerprint。未知 layout、未知必需 extension、缺字段或额外能力都不能按数字顺序猜测兼容性，也没有自动迁移或默认补全。

## 提交模型

每个持久 controller 区域使用两个完整 generation slot 和一个很小的 HEAD：

1. 从活动 solver 捕获一个逻辑时刻的完整状态。
2. 写入 inactive slot 的 manifest 与所有 section。
3. 回读、校验 CRC、身份、预算和全部账本。
4. 原子替换 HEAD，使新 generation 成为唯一提交点。

HEAD 发布前，当前已提交 generation 仍是唯一权威状态。失败的 staging 和被拒绝 slot 不得被当作已提交状态，也不得覆盖原 HEAD。

具体发布序列为：

1. 验证内存 `RegionState` 的形状与账本。
2. 读取并完整验证当前 HEAD；要求新 checkpoint generation 严格递增，且 base generation/checksum 未改变。
3. 删除 inactive slot 的已知旧内容，在 `<slot>.tmp` 逐个写 section，每个文件 `force`。
4. 最后写 manifest 并 `force`，从临时目录回读全部文件，执行 CRC、预算、账本和可重建性验证。
5. 原子把 `<slot>.tmp` 移为 inactive slot。此时它只是完整 staging，仍不权威。
6. 写、`force`、回读 `HEAD.nbt.tmp`，再以 replace-existing 原子移动为 `HEAD.nbt`。
7. 按新 HEAD 再读一次完整 generation；成功才向调用者返回 generation。

目录 rename 保护 slot 完整性，HEAD rename 是唯一 commit point。若平台不支持所需原子移动则发布失败，不能退化为复制后删除。

## 完整代次

一个 generation 至少包含：

- region UUID、dimension、network/checkpoint generation、origin、网格和 solver config；
- controller footprint、运行态和 marker 预算；
- Minecraft 对齐的 section 描述、marker ordinal、位置/体积、速度和 solid mask；
- committed profile 要求的材料、化学、颗粒、气体、热能和 pending phase-work 载荷；
- manifest、section 与各独立库存的总账和 CRC。

所有 required payload 必须属于同一 region、dimension 和 generation。runtime handle、平滑 density、pressure、mesh 和 UI 读数不持久化。

### Manifest

manifest 是无需分配 solver 即可审计和预算 admission 的索引，保存：

- region/dimension/checkpoint/network identity；
- origin、grid、完整 `FluidSolverConfig`、预留 marker capacity 与持久 runtime 状态；
- controller position、内部/边界 block 集和 required chunk footprint；
- world capability profile/aggregate/material fingerprint，marker/volume/mass 总账、material/species palette 与 registry identity；
- 严格排序的 section descriptor：坐标、marker 数、体积、质量和 section checksum；
- granular、gas、thermal、pending phase-work 等 manifest extension 与各自总账；
- 覆盖上述规范编码的 manifest checksum。

metadata discovery 只能相信已经通过 HEAD 绑定、manifest checksum 和结构预算的 manifest。它必须据此保留全部 UNLOADED 区域身份及 cell/marker reservation；当前运行预算不足只产生 `BUDGET` availability，不得丢弃已提交 metadata。提高预算并重启后重新 admission。metadata 不能据此虚构 solver inventory。

### Section

section 按 Minecraft section 对齐，是可独立校验的完整切片。基础数组采用 marker ordinal 作为 side-column 连接键：

```text
ordinal[]
positionVolume[] = x,y,z,volume
velocity[]       = vx,vy,vz
material[] + mass[]
solidMask[]
```

启用的 chemistry/granular/thermal extension 继续绑定相同 ordinal，并保存 cell-local deposit/coherent 数据。ordinal 在全 region 唯一且 cardinality 必须与所有 required side column 相等；section identity、local bounds、体积/质量/species/energy 汇总必须重新合到 manifest。数组位置不是长期 stable ID，运行时 handle 由 world registry snapshot 重新解析。

section、palette、mixture、deposit group、gas node 和 ledger 都必须采用规范排序。这样 checksum、故障重放和相同状态的重复 capture 不依赖 hash-map 遍历顺序。

## 功能布局

当前 runtime 只通过 `WorldCapabilityLayoutResolver` 从 committed profile 选择一种区域载荷：纯水、材料、化学、颗粒或完整热学。读取时必须与同一个 bundle slot 的 profile、registry snapshot 和 capability 完全一致。

不执行跨布局改写。布局、fingerprint、身份、总账或 required extension 不一致时，文件原样保留并进入 LOCKED。

未知的 length-delimited optional extension 可随读取保留，但不能被解释为 required truth；下一次从活动 solver 捕获时只写当前 runtime 明确定义的载荷。

布局选择不是“缺什么就补什么”：

- materials 决定 marker 是否必须有 material ordinal 和独立 mass；
- chemistry 决定 mixture/indicator/species/residual 列及 registry fingerprint 是否 required；
- granular 决定 active/deposit、solid composition、SVQ/mass/porosity 与 granular ledger 是否 required；
- thermal 决定所有 solver carrier `U`、coherent store、gas topology/inventory/energy、audit、buoyancy identity 和 pending phase-work 是否 required；非空有限热设备集合是 manifest-only extension，其 provider/object 只作 locator，kg 与 `U` 仍由同一 region HEAD 持有。

一个 capability 存在时，它的 manifest 和每个相关 section extension 必须成套出现；不存在时也不得偷偷携带该 truth。unknown optional extension 只有在不影响当前 required truth 时才允许跳过。

## 读取与 admission

启动先由 world capability HEAD 唯一选择 committed slot，严格回读 profile 和五 registry snapshot，再扫描 region-state root。已有 HEAD 不调用任何单 registry `loadOrCreate`；当前数据包只做 fingerprint 比较。扫描遇到非 UUID 条目、非目录、损坏 HEAD、profile/layout/fingerprint 不匹配或 checksum/identity 失败时保留原路径为 issue。若无法从路径安全识别 region，禁止创建所有新区；若能识别，则只锁对应 region。

完整恢复按以下顺序：

1. 由 HEAD 选唯一 slot，并验证 HEAD 中 UUID、generation 和 manifest checksum 的绑定。
2. 按 manifest descriptor 读取且只读取列出的 section；拒绝缺失、重复和额外未知 required 文件。
3. 检查长度、硬预算、canonical order、CRC 和所有分层 ledger。
4. 用 world registry stable ID 重建 runtime handle，验证 fingerprint/capability。
5. 重建 marker、chemistry、granular、gas 和 thermal side columns，执行跨层 cardinality 与 audit。
6. 只有全部成功才分配/激活 solver；随后刷新 collision，再发布 client snapshot。

restore 失败的区域保持 metadata-only UNLOADED/PAUSED 或 LOCKED。命令、world bridge 和容器转移不得绕过 admission 修改它。

## 恢复

- 正常启动只读取 HEAD 指向的 generation，不自动猜测另一槽。
- 只有当前 HEAD 已出现可复核 restore failure、区域仍为 metadata-only 且暂停、ownership 与容器 WAL 无歧义时，管理员才能执行 `regions recoverprevious <expectedCheckpoint>`。
- 恢复命令完整重建并审计紧邻上一槽，再原子移动 HEAD；成功后必须退出并重新进入世界。
- 该命令不是任意撤销工具，不跨功能布局，也不修改被拒绝的 slot。

恢复上一完整代的原子边界仍然只有 HEAD：命令要求操作者给出当前 expected generation，先重新读取并完全重建 inactive slot，确认其恰为 `current - 1`；发布前再次核对当前 HEAD generation/checksum 未移动，再只替换 HEAD。被拒绝的当前 slot 保持字节不变，下一次正常 checkpoint 才可能覆盖它。

这条路径不自动触发，是因为“另一个 slot 可读”不能证明它比当前失败状态更符合操作者意图，也不能解决外部 ownership/item WAL 的歧义。

## 发布前同步

热区域 capture 前必须先把 current marker/occupancy 与 gas topology、gas inventory 和 gas energy ordinal 同步到同一个逻辑时刻。同步失败时 checkpoint 失败，活动 solver 保留，原 HEAD 不变。

同步是 capture 的组成部分而不是事后修补：先根据待发布 marker/solid/coherent geometry 派生 cavity topology，再以稳定 overlap 规则规划 gas merge/split/ambient exchange，联合验证 gas inventory、node `U` 和 topology key。只有同步候选闭合后，section marker 和 gas extension 才能一起编码。

## 故障、清理与删除

发布路径在 slot 写前、每个 section 后、manifest 后、临时回读后、slot rename 后以及 HEAD rename 前后都有故障注入点。最近持久化门必须证明任一点异常退出后，启动只会得到完整旧代或完整新代，不会把 staging 当成 HEAD。

只允许自动清理实现自己产生且名称集合完全已知的首次发布 staging；目录出现未知文件就原样保留并锁定。已提交区域删除时先原子移出可发现命名空间到 quarantine，再 best-effort 物理删除；删除失败也不能让半删目录重新被 discovery 接纳。

普通 checkpoint 不删除上一完整 slot，因为它既是原子覆盖的另一半，也是显式 recoverprevious 的唯一候选。清理逻辑绝不能触碰 HEAD 当前指向的 slot。
