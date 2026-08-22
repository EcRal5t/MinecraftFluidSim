# 世界集成、ownership 与容器规格

本文描述 M6 以后把有界求解器接入真实世界的设计门。当前 controller 与 `region-state v1` 行为见 [区域存档契约](../architecture/region-state-v1.md)；M0 takeover 证据见 [进度记录](../progress/m0-takeover-smoke.md)。

## 目标组件边界

```text
数据包材料/组分/反应定义
        -> MaterialRegistry / ReactionRegistry
命令、容器、控制器、世界事件
        -> FluidRegionManager
            -> CPU 规范后端 / 可选加速后端
            -> GasZoneGraph / ChemistryStep / GranularStep
            -> FluidPersistenceStore
            -> WorldFluidBridge
            -> SnapshotBuilder（只读）
```

现有 `FlipFluidDomain` 继续作为 CPU 参考核心演进。只有职责和回归门明确后才抽取 backend 所有权，不为未来 GPU 预先重写核心。

## 区域、连接与 chunk ticket

- 控制器区域继续以可见边界表达 ownership；未闭合、跨未加载 chunk 或超预算时拒绝激活。
- 多区域共享 cell、marker、组分记录、网络和后端内存总预算。
- 区域之间默认不直接连通；未来只通过显式端口或经评审的相邻 section 合并协议传输质量与动量。
- 跨 chunk 区域是原子生命周期单元，缺任一必要 section 时不能把缺失部分当墙继续半区域步进。
- 默认不申请永久 chunk ticket。未来若允许管理员常载工业域，必须同时限制总量、尺寸和持续时间，并在关闭、失败或超时后释放。
- 检查点失败时区域保持暂停并给出可读错误；实现可以使用有界短期 ticket 完成本次保存，但不能借此形成隐式常载。

## 长期 section 持久化

开放世界状态最终按 chunk section 分片，而不是按当前矩形对象保存。每片至少需要：

- 格式版本、区域 UUID、维度、section 坐标和提交代次；
- 各材料/混合包的精确体积、质量与组分库存；
- 活动态 marker、速度、相标签，或休眠态宏观状态；
- 气体节点与跨 section 连接；
- 沉积物 palette 与数量；
- 校验和。

跨 section 的清单、分片与 world ownership 必须能证明属于同一完整提交。GPU 缓冲布局不是存档格式，读盘后必须能构建 CPU 或可选后端。这是未来格式，不能无迁移地改写当前 `region-state v1`。

## M6.0 ownership 设计门

扩大 takeover 前必须做可丢弃原型并比较：

1. **代理占位方块**：验证放置/破坏、活塞、光照、寻路、原版 fluid tick、邻居更新、爆炸、结构命令、chunk 保存和常见 Mod 扫描。
2. **空气 + 独立 registry**：验证外部方块编辑、chunk 加载顺序、registry/世界失配、崩溃恢复、校验修复和解除接管事务。

按世界机制兼容性、恢复能力、存档体积和运行成本选择，不预定胜者。ownership、region-state 和方块替换必须共享区域 UUID 与提交代次；不一致时暂停并进入恢复流程，绝不覆盖玩家新实体方块。

最终世界桥不能长期依赖在原版水位置持续写空气；选定的 ownership 层必须让原版 fluid tick、方块放置与邻居更新能判断该位置由 Real Water 管理。

**M6.0 已于 2026-08-22 关闭**：选定空气 + 独立 registry；证据与冻结的状态机/提交顺序/恢复判定见 [`../progress/m6.0-ownership-review.md`](../progress/m6.0-ownership-review.md)。

### M6.1a 实现注记（由 M6.0 原型与分段世代增量冻结）

- 布局：`<world>/realwater/ownership/<region-uuid>/{wal.nbt, WARNING.txt, committed.nbt}`。WAL v1 携带 region UUID、dimension、base/target generation、operation（ACQUIRE/RELEASE）与 preimage 条目；committed v1 携带 region UUID、dimension、generation 与 owned 条目。
- **region-state HEAD 是唯一提交点**。acquire 顺序：`RegionStateFiles.prepare` 暂存并验证 G+1 → 发布 ownership WAL → WARNING → 世界修改 → `RegionStateFiles.commit` 发布 HEAD → 写 committed.nbt → 清理 WAL/WARNING。committed.nbt 写在 HEAD 之后：POST-COMMIT 恢复在 registry 缺失或落后于 target（替换前崩溃）时从 WAL 重建（owned = ACQUIRE 条目 / RELEASE 为空）；registry 不可读、领先于 WAL target、或在 target 代次上与 WAL 不一致则 LOCKED，绝不静默重写。
- controller acquire 的候选在扫描与移除之间是同一服务器 tick，玩家不可能插入编辑，因此 acquire 期 owned 集合恒等于候选集合；任何非 REMOVED 结果按异常走回滚。玩家方块优先只在恢复/对账路径出现（跨启动的 PRE-COMMIT 回滚跳过已占用位置）。
- 恢复判定以 region HEAD generation 对比 WAL base/target（base=事务前 region HEAD，target=被提交的 checkpoint generation），语义与 M6.0 决策记录的判定表一致。
- release 首版保守：区域 marker 体积为 0 才允许；否则拒绝并提示先排水（M6.1b 容器提供真实转移路径）。
- 已知限制：committed.nbt 被外部删除与 region-state 目录被删除同属"存档被手动破坏"类，不设防；损坏（可读出但不合法）才 LOCKED。
- 增量状态（2026-08-22 关闭）：M6.1a 全部落地并过实机 Smoke A，证据与已知边界见 [`../progress/m6.1a-world-ownership.md`](../progress/m6.1a-world-ownership.md)；M6.0 的 `ownproto` 原型命令面已删除。

## M6.1 容器真值

- 一桶容量为 1 BV。
- 满且纯的原版水可退化为普通水桶。
- 部分纯水必须保存真实体积；Minecraft damage 只能显示比例，不能成为水量真值。
- 两个部分桶合并时原子填满一个，溢出通过 recipe remainder 留在指定输入。
- 混合液、酸碱和其他材料使用 Real Water 容器，保存体积、混合物 handle，以及加入后才存在的温度/指示剂字段。
- 装取、放出、合成、发射器、漏斗、掉落、死亡、断线和创造源汇都必须显式记账。
- 若 vanilla `WATER_BUCKET` 无法在所有必需路径可靠保留组件，就使用专用 Partial Water Container，不能为外观接受复制或丢失。
- 未协商 API 的其他 Mod 转移应拒绝并反馈，不猜测体积或材料语义。

### 必测行为矩阵

| 路径 | 满纯原版水 | 部分纯水 | 混合物/其他液体 |
|---|---|---|---|
| 玩家装取/放置 | 可退化为原版水桶；在模拟区按 1 BV 账本流转 | 保持组件体积、堆叠上限 1；只放出实际体积 | 使用 Real Water 容器和完整 mixture handle |
| 发射器 | 原版行为必须与 ownership 协调 | 走同一服务端账本，不生成整格水 | 走同一账本；目标不兼容时原样保留并反馈 |
| 合成/合并 | 不额外复制 | 动态配方原子填满，recipe remainder 保存溢出 | 仅材料规则允许时合并，组分逐项守恒 |
| 漏斗、掉落、死亡、断线 | 保持物品语义 | 数据组件往返不变，不自动合并 | 数据组件/handle 序列化往返不变 |
| 创造模式 | 明确记为非守恒外部源汇 | 拾取副本不得污染生存账本 | 同左，并在 debug 统计来源 |
| 其他 Mod/数据剥离 | 原版兼容 | 未协商 API 时拒绝转移，不猜测体积 | 未协商 API 时拒绝转移，不展开为原版水 |

## 世界接入顺序

1. 继续以显式控制器/命令区域为边界。
2. 在区域内完成纯水真实流动与容器账本。
3. 接入 section 持久化、休眠和 ownership 共同提交。
4. 才评审附近原版河流/水池的可恢复接管。
5. 灭火、湿润、冲刷、岩浆成石等逐项适配。
6. 其他 Mod 流体只通过数据注册和显式 API 映射。
7. 海洋与世界生成水最后开放，并以前置休眠率、压力拆分和存档基准为条件。

## 游戏资源完成条件

任何正式 gameplay 入口都必须同步提供方块状态、模型、贴图、配方或取得方式、创造标签、`zh_cn`/`en_us` 本地化、tooltip、失败反馈和必要声音/粒子。缺失这些资源意味着入口尚未完成。

## M10 原版行为与其他 Mod API

- 灭火、湿润、岩浆反应、红石、生物和寻路逐项适配，不以“看起来像水”推断完成。
- 提供稳定的材料注册、容器传输、区域端口、查询和事件 API。
- 外部兼容必须显式声明单位换算与材料映射。
- API 不得直接修改平滑 density、客户端 mesh 或未提交的后端中间态。

