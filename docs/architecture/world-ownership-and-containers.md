# 世界 ownership、容器与跨持久域事务

本文描述 M6 已生效的世界接入契约。求解器真值与材料状态见 [`fluid-solver.md`](fluid-solver.md)，区域检查点格式见 [`region-state-v1.md`](region-state-v1.md)。M0 的瞬态 takeover journal、M6 ownership sidecar、region-state 与容器转移 WAL 是四个独立格式，不得互相猜测或合并。

## 范围与功能门

- 持久 ownership 只属于 controller-managed 区域；`demo`、`empty`、`capture`、`takeover` 和各类开发域仍是瞬态区域。
- `worldBridgeEnabled` 默认关闭并在服务器启动时绑定。关闭时不建立新 ownership，也不拦截区域桶转移；已有 sidecar 保留并对账。
- `materialsEnabled` 默认关闭并在服务器启动时绑定。非纯水或混合物容器只在材料门开启时进入活动求解器。
- 控制器扫描要求一格厚可见闭壳、完整加载所需 chunk、互不重叠并通过全局预算；默认最多 4 个区域。激活走 `FluidRegionManager.addAndSelect`，开发命令走 `replaceSelected`。

## Ownership 提交与恢复

正式布局为 `<world>/realwater/ownership/<region-uuid>/{wal.nbt,WARNING.txt,committed.nbt}`。`region-state` HEAD 是区域物理状态的唯一提交点。

Acquire 顺序固定为：

1. 校验当前 HEAD，暂存并回读验证目标检查点；
2. 原子发布 ownership WAL，再发布 WARNING；
3. 在同一服务器 tick 内逐项移除已扫描的纯原版水；
4. 最后发布目标 region-state HEAD；
5. 从已提交事务重建/发布 `committed.nbt`，再清理 WAL 与 WARNING。

恢复只比较 HEAD 与 WAL 的 `baseGeneration`/`targetGeneration`：HEAD=base 回滚 preimage（玩家实体方块优先）；HEAD=target 完成 committed ownership，绝不恢复原水；其他代次、未知格式、损坏、身份不符或同代内容不一致全部 LOCKED 并保留证据。首次发布失败与 abort 必须清理自己创建的 staging；未知目录内容不得被当作 staging 删除。Release 仅在区域已精确排空时允许，仍走同类可恢复事务。已加载区域也必须逐区域检查 sidecar；未解决 WAL/WARNING、损坏内容或超前于活动 checkpoint 的 committed generation 会同时阻止步进、排空和所有普通 checkpoint（包括停服 checkpoint），不得只在 UNLOADED restore 时检查。

## 容器真值

- 1 BV = 1 m³ = `1_000_000_000` 纳体积量子。`long` 体积是容器权威值；耐久条、tooltip、渲染密度都只读派生。
- 部分纯水桶使用 `realwater:water_volume`，严格位于 0 与 1 BV 之间。恰好 1 BV 纯水规范化为原版水桶，空值规范化为原版空桶。
- 材料桶使用总 `fluid_volume`、总 `fluid_mass_bits`，并在单材料时使用稳定 `material_id`，在多材料时使用 canonical `mixture_components`。
- `FluidMixture` 按稳定材料 ID 排序；每项独立保存正纳体积和 raw IEEE-754 `double` 质量。组件总质量本身也必须为正有限值，总质量字段必须与各项 raw 求和一致。最多 64 项、编码最多 8192 字符、总体积不超过 1 BV。
- 损坏、被数据包剥离或互相矛盾的组件不得推测修复；物品保持不变，转移拒绝。
- 部分桶与材料桶堆叠上限为 1；所有区域桶入口也显式拒绝 `count != 1`，不得吞掉整叠空桶或复制流体。

## 精确求解器转移

`FlipFluidDomain` 的 `tryInsertExactVolume`/`tryExtractExactVolume`、`tryInsertExactMaterial` 与 `tryInsertExactMixture`/`tryExtractExactMixture` 都是 all-or-nothing：

- insert 先规划容量和 marker，再一次提交；拒绝时不得留下 marker、体积或质量残留；
- extract 先以双精度检查可用量，再移除整 marker 并对尾 marker 做守恒拆分；
- 多材料提取返回逐材料体积与独立质量，不按密度从体积重新估算；
- 服务层先把容器请求向下量化成“不超过余额/容量的最大正 `float` cell volume”，再以原语返回的真实 `double` marker 变动结算容器纳体积。不可由 solver 表示的余量留在原物品中，不得按请求量扣账或记账；多材料插入按组件分别保留体积与独立质量余量，多材料提取从实际逐材料返回值重建物品账本。

桶转移按玩家准星或发射器朝向命中的区域路由，和命令当前选中区域无关。生存模式从区域装取/向区域放出是守恒转移；创造模式放出明确记为外部 source，装取用副本替换手中空容器而不制造隐藏 sink。成功转移会唤醒休眠区域，但保留转移前的 operator pause/running 意图。区域外或门关闭时让原版物品行为继续处理。

## 容器转移 WAL

布局为 `<world>/realwater/container-transfer/<transaction-uuid>.nbt`，当前格式 v2。WAL 记录区域与目标代次、INSERT/EXTRACT、玩家手或发射器槽 actor、操作前后完整物品 fingerprint（含 mixture 与独立质量）以及 PREPARED/REGION_COMMITTED phase。

顺序固定为：原子发布带 before item 与请求上限的 PREPARED WAL → 求解器精确修改 → 以实际 marker 变动量及完整 after item 原子替换为 REGION_COMMITTED WAL → 发布目标 region-state HEAD → 当前调用把 actor 改为 after item → 随后 tick 幂等重放 after item、强制保存 actor 所属存档 → 删除 WAL。PREPARED 和 REGION_COMMITTED 的 `volumeNanos` 因而允许分别表示请求上限和实际结算量；后者必须与 before/after fingerprint 的差一致。`REGION_COMMITTED` 表示 WAL 已具备 target 提交所需的权威 after item；region-state HEAD 仍是物理提交点。恢复判定为：

| 启动状态 | 动作 |
|---|---|
| HEAD=base，live solver 未脏 | 丢弃未提交 WAL，旧物品/旧区域仍权威 |
| HEAD=target 且 WAL 为 REGION_COMMITTED | 将 actor 收敛到 after item，保存成功后删除 WAL |
| target 但缺 after phase、live solver 脏或 generation 失配 | 保留 WAL 并锁住转移，等待人工处置 |

玩家离线、发射器所在维度或区块未加载时保持 pending，不删除记录；同一区域新转移保持锁定。store 的陌生文件、目录、临时文件、损坏 NBT 或文件名/payload UUID 不同会触发全局 fail-closed 锁，因为无法安全判断其所属区域。v1 纯水 WAL仍可读；v1 材料 WAL没有独立质量账本，必须锁定并人工恢复，不能按当前密度补算。

## 已知边界

- M6 只接管显式控制器闭壳内的有界区域，不接管天然河流、洞穴或海洋。
- 原版水从破损边界流回 owned air 只由 ownership 对账报告，不静默吸收；先修复边界再由管理员处置。
- 部分纯水桶合并有动态配方；材料混合物目前通过精确装取/放出和显式调试取得形成，不提供任意材料合成配方。
- 漏斗、掉落、死亡、断线和重启依赖 Minecraft DataComponent 原样往返；外部 Mod 的语义化流体 API 留到 M10。
- 当前求解器仍由 CPU 权威执行；可选 GPU probe 不读取或提交上述世界事务。
