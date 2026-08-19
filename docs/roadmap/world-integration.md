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

## M6.1 容器真值

- 一桶容量为 1 BV。
- 满且纯的原版水可退化为普通水桶。
- 部分纯水必须保存真实体积；Minecraft damage 只能显示比例，不能成为水量真值。
- 两个部分桶合并时原子填满一个，溢出通过 recipe remainder 留在指定输入。
- 混合液、酸碱和其他材料使用 Real Water 容器，保存体积、混合物 handle，以及加入后才存在的温度/指示剂字段。
- 装取、放出、合成、发射器、漏斗、掉落、死亡、断线和创造源汇都必须显式记账。
- 若 vanilla `WATER_BUCKET` 无法在所有必需路径可靠保留组件，就使用专用 Partial Water Container，不能为外观接受复制或丢失。
- 未协商 API 的其他 Mod 转移应拒绝并反馈，不猜测体积或材料语义。

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

