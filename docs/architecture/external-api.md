# 外部 API 契约

## 当前稳定范围

当前只冻结同步只读的 Query API v1。loader-neutral 契约位于 `api` 模块的 `cc.ecisr.mmm.api.query`；Fabric 调用方通过 `cc.ecisr.mmm.api.fabric.MmmFabricApi.queryV1(MinecraftServer)` 发现实现。受控 transfer、commit event、Jade/WAILA/HWYLA 展示适配和其他 loader 的发现桥仍属于产品路线，不能从 Query v1 推断其写入语义。

`MmmQueryApiV1.VERSION = "1"`，单位系统标识为 `SI+mmm-quanta-v1`。接口只依赖 JDK 类型，不返回 Minecraft 对象、runtime handle、solver array 或 live region/domain 引用。

## 请求

`FluidQueryRequest` 包含：

- `dimensionId`：最长 128 字符的稳定 dimension ID；
- 可选 `regionId`：指定 owner UUID；为空时只解析该位置唯一、已登记的 owner；
- `worldXMetres/worldYMetres/worldZMetres`：有限的世界坐标；Minecraft 一方块按 `1 m` 解释。

调用必须发生在对应 `MinecraftServer` 的服务器主线程。查询只检查已登记、已加载且可用的区域，不加载 chunk、不恢复 checkpoint、不创建 solver、不唤醒 motion/thermal、不推进 logical step、不触发 snapshot 或持久化。

## 结果和失败语义

结果状态是封闭枚举：

| 状态 | 含义 |
|---|---|
| `OK` | 返回一个完整 observation |
| `EMPTY` | 位置有效，但该 solver cell 没有液体 marker |
| `INVALID_REQUEST` | ID、坐标或请求形状无效 |
| `WRONG_THREAD` | 不在服务器主线程 |
| `NO_REGION` | 没有登记 owner，或显式 UUID 不属于该 dimension/位置 |
| `REGION_NOT_LOADED` | owner metadata 存在，但其 solver state 未驻留 |
| `REGION_UNAVAILABLE` | owner 被锁定、可用性检查失败，或服务器服务尚未初始化 |
| `OUTSIDE_INTERIOR` | 点位于 controller footprint 但不在可求解 interior |
| `STATE_NOT_READY` | 已提交库存存在，但派生 observation 无法完整、安全构造 |

只有 `OK` 携带 observation；其他状态不返回部分库存。调用方不得把 Minecraft 水方块外观、错误文本或上一次成功结果解释成当前库存。

## Observation

`FluidCellObservation` 返回 dimension ID、region UUID、region generation、cell 坐标、cell edge、总液体体积 `m³`、质量 `kg`、密度 `kg/m³`、可选温度 `K`、按 stable ID 排序的材料体积/质量以及 species integer quanta。

数据直接扫描权威 marker、chemical parcel 和 thermal side column：不依赖平滑 volume、pressure、客户端 mesh 或 grid rebuild。`regionGeneration` 是 ownership/checkpoint 身份代次，不是每 tick state revision；调用方需要新鲜值时重新查询，不能用它判断两个 observation 之间没有发生物理步。

v1 不承诺 pressure、速度、相分数、gas/颗粒/coherent solid 摘要或内部调度字段。新增字段或其他库存类型必须保持旧调用方可判定的单位与失败语义，不能悄悄复用 `detail` 文本扩展协议。
