# 区域 checkpoint 契约

## 提交模型

每个持久 controller 区域使用两个完整 generation slot 和一个很小的 HEAD：

1. 从活动 solver 捕获一个逻辑时刻的完整状态。
2. 写入 inactive slot 的 manifest 与所有 section。
3. 回读、校验 CRC、身份、预算和全部账本。
4. 原子替换 HEAD，使新 generation 成为唯一提交点。

HEAD 发布前，当前已提交 generation 仍是唯一权威状态。失败的 staging 和被拒绝 slot 不得被当作已提交状态，也不得覆盖原 HEAD。

## 完整代次

一个 generation 至少包含：

- region UUID、dimension、network/checkpoint generation、origin、网格和 solver config；
- controller footprint、运行态和 marker 预算；
- Minecraft 对齐的 section 描述、marker ordinal、位置/体积、速度和 solid mask；
- 当前功能门要求的材料、化学、颗粒、气体、热能和 pending phase-work 载荷；
- manifest、section 与各独立库存的总账和 CRC。

所有 required payload 必须属于同一 region、dimension 和 generation。runtime handle、平滑 density、pressure、mesh 和 UI 读数不持久化。

## 功能布局

当前 runtime 根据启动时冻结的功能门选择一种区域载荷：纯水、材料、化学、颗粒或完整热学。读取时必须与同一次启动的门、registry snapshot 和 capability 完全一致。

不执行跨布局改写。布局、fingerprint、身份、总账或 required extension 不一致时，文件原样保留并进入 LOCKED。

未知的 length-delimited optional extension 可随读取保留，但不能被解释为 required truth；下一次从活动 solver 捕获时只写当前 runtime 明确定义的载荷。

## 恢复

- 正常启动只读取 HEAD 指向的 generation，不自动猜测另一槽。
- 只有当前 HEAD 已出现可复核 restore failure、区域仍为 metadata-only 且暂停、ownership 与容器 WAL 无歧义时，管理员才能执行 `regions recoverprevious <expectedCheckpoint>`。
- 恢复命令完整重建并审计紧邻上一槽，再原子移动 HEAD；成功后必须退出并重新进入世界。
- 该命令不是任意撤销工具，不跨功能布局，也不修改被拒绝的 slot。

## 发布前同步

热区域 capture 前必须先把 current marker/occupancy 与 gas topology、gas inventory 和 gas energy ordinal 同步到同一个逻辑时刻。同步失败时 checkpoint 失败，活动 solver 保留，原 HEAD 不变。
