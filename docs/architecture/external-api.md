# 外部 API 契约

## 当前稳定范围

当前冻结同步只读 Query API，以及只发布 durable container completion 的 committed-event API。loader-neutral 契约位于 `api` 模块的 `cc.ecisr.mmm.api.query`、`cc.ecisr.mmm.api.event` 与共享 typed quantity 所在的 `cc.ecisr.mmm.api.transfer`；Fabric 调用方分别通过 `MmmFabricApi.query(MinecraftServer)` 和 `MmmFabricApi.events(MinecraftServer)` 发现实现。Fabric 已提供可选 Jade 观察适配，但它只是 Query API 的展示消费者，不扩展稳定契约。受控 transfer、其他 commit event、WAILA/HWYLA 展示适配和其他 loader 的发现桥仍属于产品路线，不能从只读查询或事件通知推断写入权限。

`MmmQueryApi.UNIT_SYSTEM = "SI+mmm-quanta"`。接口只依赖 JDK 类型，不返回 Minecraft 对象、runtime handle、solver array 或 live region/domain 引用。产品功能和 Java 类型使用语义名称；格式变化直接替换当前契约，不保留按数字区分的公开入口。

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

只有 `OK` 携带 observation；其他状态不返回部分库存。`REGION_UNAVAILABLE` 还必须携带一个封闭的 `RegionAvailabilityReason`，用于稳定地区分 profile、registry/HEAD、边界/chunk、overlap、预算、WAL、pending work、权限、数值暂停与政策停用；其他状态不得携带该字段。`detail` 只作诊断，调用方不得解析错误文本，也不得把 Minecraft 水方块外观或上一次成功结果解释成当前库存。

## Observation

`FluidCellObservation` 返回 dimension ID、region UUID、region network generation、cell 坐标、cell edge、总液体体积 `m³`、质量 `kg`、密度 `kg/m³`、可选温度 `K`、按 stable ID 排序的材料体积/质量以及 species integer quanta。

数据只使用目标 cell 的权威 marker、chemical parcel 和 thermal carrier side column，并直接由该 cell 的总 `U` 与载体清单反演 LTE 温度：不依赖平滑 volume、pressure、客户端 mesh，也不重建 fluid/thermal grid。材料和 species 都按 stable ID 排序。`regionNetworkGeneration` 标识网络中的区域 incarnation，不是 checkpoint generation 或逐 tick state revision；调用方需要新鲜值时重新查询，不能用它判断两个 observation 之间没有发生物理步。

Query API 不承诺 pressure、速度、相分数、gas/颗粒/coherent solid 摘要或内部调度字段。新增字段或其他库存类型必须保持调用方可判定的单位与失败语义，不能悄悄复用 `detail` 文本扩展协议。

## 内置探针与控制界面

主 Mod 的 field probe 是内部、服务端权威的产品入口，不扩展上述稳定 Query ABI。普通使用先通过 `ShellFluidQuery` 对命中壳面两侧分别调用公开 Query，只有唯一 owner 时才继续；潜行使用以有界 DDA 向内寻找第一格凝聚相。随后它从同一 live region、同一 network generation 和同一 cell 读取 loader-neutral `CellCarrierInventoryView`，显示 active/deposit/coherent solid 与 ambient/sealed gas 的有界 Top-N 摘要。液体 observation 与 carrier observation 身份不一致时整次拒绝，不拼接两个时刻的部分真值；路径不加载 chunk、不恢复 solver、不唤醒、不 checkpoint，也不重建 gas topology 或 thermal grid。这个额外 carrier view 是内部诊断模型，外部 Mod 不能由它推断公开 Query 已承诺 gas/solid 字段。

对 controller 使用探针会打开 server-authored bounded summary：identity、完整 footprint、当前/保留预算、operator intent、motion/thermal state、封闭 availability reason、live inventory（若 solver 已加载）、已观测载体温度范围、事务门和数值诊断。Minecraft adapter 先核对线程、距离、方块和 owner，再把 region UUID 与一次性 summary generation 交给 `RegionApplicationService`；`ControllerSummaryPort` 只返回 use-case-specific bounded read model，不返回 region/domain。metadata-only UNLOADED 只报告 inventory unavailable，不为展示恢复派生状态。界面只提供 select、pause、resume 和显式 availability refresh；每个动作都携带一次性、按 player+region 隔离的 opaque generation，服务端再次核对距离、方块、owner、权限和 token，客户端 summary 没有提交权。打开或读取 summary 本身不加载、不唤醒、不 checkpoint。

对 fixed thermal reservoir 使用探针会打开 setpoint 界面。summary 绑定一次性 player+dimension+block token，并携带已提交 world thermal registry 的公共载体温度交集；只有创造玩家能提交有限且处于该区间的 K 值。服务端重验距离、方块、世界可写状态和 token，持久化 BlockEntity 后只刷新相邻已加载区域的 boundary flux 并唤醒 thermal scheduling，不直接改写 region `U`。

## Jade 观察适配

Jade 是 Fabric 模块的可选 compile-only 依赖；未安装 Jade 时不会解析任何 Jade 类，也不影响主 Mod 启动。服务端 provider 只对 `fluid_boundary`、`fluid_controller` 和 `thermal_reservoir` 请求数据，并通过上述 Query API 观察命中面近侧或跨过一格壳体的反侧位置。非 `OK` 结果不产生 tooltip，不从方块外观猜测流体。

发送给客户端的是有界展示摘要：体积、质量、密度、可选温度，以及按量排序的至多四项材料和 species；若项目更多会明确显示隐藏项数。该摘要不是库存协议，不能用于转移或回写。当前适配依赖玩家命中闭壳方块，不声称直接 ray trace 空气格内的自由面；扩展自由面目标前必须继续保持不加载、不唤醒和服务端权威查询。

## Committed event

`MmmEventApi` 可在不初始化 world service 的情况下注册监听器。回调只在服务器主线程执行；单个监听器异常会被隔离，不能改变提交结果或阻断其他监听器。服务器关闭会清空该服务器的订阅。

`ContainerTransferCommittedEvent` 只在 region HEAD 已指向 target、player/dispenser after-item 已完成强制保存且 WAL 已删除后发布。它携带 transaction UUID、dimension、region UUID、checkpoint generation、相对区域的 insert/extract 方向，以及封闭的 `TransferQuantity`：液体记录 `10^-9 m³` nanovolume 和可选 raw `U`，颗粒记录 `2^-40 BV` 的 SVQ，调用方不需要用枚举与无类型 long 自行配对。事件不声称包含材料/species 明细；监听者需要当前库存时重新查询。

`core/.../transfer/` 已保存与 loader 无关的容器体积、逐材料质量、species/residual、颗粒组成、raw `U`、actor identity 和 journal 数据；Fabric 只负责 NBT 持久编码、Minecraft actor 收敛，以及由 `ContainerItemStacks` 映射 `ItemStack` component。它们是内部事务与未来 transfer adapter 的共享模型，不等同于已经开放写权限；公开 request/plan/commit 仍须先完成可跨重启解析并强制保存的外部 actor 协议。

事件是易失通知，不是库存日志：服务器可能在删除 WAL 后、发布事件前退出，监听者也可能晚注册。漏失不能重放或反向驱动物理，消费者必须能从 Query API 恢复当前观察。checkpoint、ownership、phase transition 与安全暂停事件尚未冻结，不能用 container event 猜测这些提交。
