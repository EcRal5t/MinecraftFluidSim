# 世界集成、ownership 与容器规格

本文只描述 M6 之后继续扩大世界接入的未来设计门。M6 已完成的 controller ownership、精确容器与跨持久域 WAL 以[当前架构契约](../architecture/world-ownership-and-containers.md)为准，关闭证据见 [M6 进度记录](../progress/m6-world-bridge-and-containers.md)；当前区域存档见 [region-state 契约](../architecture/region-state-v1.md)。

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

当前 region-state 已在一个区域原子代内部按 chunk section 分片；开放世界若要让 section 独立休眠、加载或迁移，仍需新的 manifest/提交格式。每片至少需要：

- 格式版本、区域 UUID、维度、section 坐标和提交代次；
- 各材料/混合包的精确体积、质量与组分库存；
- 活动态 marker、速度、相标签，或休眠态宏观状态；
- 气体节点与跨 section 连接；
- 沉积物 palette 与数量；
- 校验和。

跨 section 的清单、分片与 world ownership 必须能证明属于同一完整提交。GPU 缓冲布局不是存档格式，读盘后必须能构建 CPU 或可选后端。这是未来独立生命周期格式，不能无迁移地改写当前 `region-state v1/v2`。

## M6 已完成边界

M6 已于 2026-08-23 关闭，不在本 roadmap 重复当前实现。后续扩展必须继续保持：

- region-state HEAD 是区域物理状态的唯一提交点；世界 ownership 与 item actor 各自通过独立 WAL 收敛，未知/损坏状态 fail-closed；
- marker 体积、逐材料质量和容器纳体积是独立真值，渲染或密度参数不得参与库存结算；
- 持久接管限于显式 controller 闭壳；开发域与 M0 takeover 保持瞬态；
- 数据被剥离或未协商 API 的外部 Mod 容器不得按外观猜测材料、体积或质量；
- M7 增加 species、M9 增加能量时，必须给 mixture component 与 container WAL 制定显式版本迁移，不能把新字段塞进现有 v2 后假装兼容。

## 世界接入顺序

1. 继续以显式控制器区域为持久边界；开发命令只做瞬态 fixture。
2. M7/M9 新增组分或能量库存时先扩展容器、WAL 与存档版本，不复用旧字段猜测。
3. 评审 section 独立生命周期、休眠和 ownership 共同提交格式。
4. M10 逐项适配灭火、湿润、冲刷、岩浆成石等原版行为，并提供显式其他 Mod API 映射。
5. 只有事务、休眠率与性能门通过后，才评审附近原版河流/水池的可恢复接管。
6. 海洋与世界生成水最后开放，并以前置休眠率、压力拆分和存档基准为条件。

## 游戏资源完成条件

任何正式 gameplay 入口都必须同步提供方块状态、模型、贴图、配方或取得方式、创造标签、`zh_cn`/`en_us` 本地化、tooltip、失败反馈和必要声音/粒子。缺失这些资源意味着入口尚未完成。

## M10 原版行为与其他 Mod API

- 灭火、湿润、岩浆反应、红石、生物和寻路逐项适配，不以“看起来像水”推断完成。
- 提供稳定的材料注册、容器传输、区域端口、查询和事件 API。
- 外部兼容必须显式声明单位换算与材料映射。
- API 不得直接修改平滑 density、客户端 mesh 或未提交的后端中间态。
