# Matter, Motion & Mechanics

Minecraft Java 26.2 / Fabric 的有界三维物理流体 Mod。主 Mod 使用 CPU 权威 PIC/FLIP 求解器，支持最多四个独立区域、控制器闭壳、跨 chunk 完整加载、原子 checkpoint、分数自由面、cut-cell 碰撞、多液体、守恒化学、低成本颗粒沉积以及热学与相变。

这不是原版水的全局替代。当前稳定基线以可制作的边界方块和控制器建立显式闭壳：普通空手右击控制器会创建或选择区域并立即运行，空手潜行右击会持久切换暂停意图；正常生命周期不依赖 `/mmm`。原版世界交互仍按产品路线逐项接入。CPU 目前是规范后端，生产 GPU 后端是后续确定目标，但不会取得世界事务提交权。

代码按编译边界拆为 `api`（loader-neutral 外部契约）、`model`（registry、账本与 immutable state）、`solver`（CPU 物理与完整 candidate）、`application`（用例与 port）、`fabric`（Minecraft/Fabric 适配）、`gpu-probe-kernel`（无 loader 的 Vulkan 计算与验证）和 `gpu-probe`（可选 Fabric 适配）；`core` 仅保留为聚合发布 artifact，不再含生产源码。

## 持久化与安全边界

- 控制器区域必须有完整边界，所需 chunk 必须全部加载；Mod 不持有永久 chunk ticket。
- 世界接管只处理纯原版水。ownership journal 和 WARNING 先发布，原版方块随后修改，region HEAD 最后提交。
- 区域 checkpoint 使用双槽完整代次与 HEAD 最后发布。`regions recoverprevious` 只在当前 HEAD 恢复失败后，显式验证并激活紧邻的上一完整代。
- 区域载荷必须与已提交的世界能力包完全一致。任何格式、区域布局、registry fingerprint、身份或账本不一致都会保留原文件并锁定，不改写载荷或猜测修复。
- 容器事务跨区域、物品和玩家/发射器持久域。actor 的 after-item 完成 durable save 并回读 exact after 前不得删除 WAL；未知、损坏或布局不一致的 WAL 保留并锁定。
- `empty` 联合清空非控制器开发域中的液体、连续凝聚相、active parcel 和 deposit，并把实际移除的物质与能量记入 sink；控制器区域必须用控制器界面的 `EMPTY_CONDENSED` 预览/确认事务，不能由命令绕过。存在未决容器 WAL 或相变功时拒绝破坏性 lifecycle；普通桶、发射器及 `pour`/`drain` 会先用权威 mechanical candidate 结算已有相变功再转移。
- 可选 GPU probe 只读且不修改世界或存档；主 Mod 始终以 CPU 结果为规范。

世界能力（材料、化学、颗粒、降水、热学和相变）不是每个世界的开关，而是由 datapack 定义并在服务器首次启动时发布为不可变的世界能力包（`mmm/world-capabilities/`，HEAD 是唯一提交点）。能力包同时冻结该世界的 registry 快照与区域布局，是已有库存的唯一解释。启动后 datapack 若与已提交包不一致，包继续作为权威，只报告 drift，不修复也不重发；无法证明能力包的世界会锁定并关闭控制器。任何来自其他实现或其他 build 的存档目录都原样保留并锁定，没有迁移或字段补全。

## 正常入口

`fluid_boundary` 以玻璃和铁锭制作，`fluid_controller` 以比较器、红石和铁锭制作；两者也分别出现在建筑方块和功能方块创造栏。用边界与一个控制器围成唯一闭合内腔，保持所需 chunk 已加载，然后空手右击控制器激活。再次普通右击只选择该区域；空手潜行右击切换运行/暂停。运行意图随 checkpoint 保存，边界或 chunk 暂时不可用只会安全阻止 stepping，不会覆写玩家意图。

## 管理与诊断命令

`/mmm` 用于 fixture、诊断、恢复和管理员操作，不是正常游玩的启动条件。

| 用途 | 命令 |
|---|---|
| 创建开发域 | `/mmm demo`、`capture`、`takeover` |
| 创建持久闭壳 fixture | `/mmm controllerdemo`；正常游玩请制作并放置边界与控制器 |
| 选择与保存区域 | `/mmm regions list`、`regions select <uuid>`、`regions checkpoint` |
| 恢复上一完整 checkpoint | `/mmm regions recoverprevious <expectedCheckpoint>`；成功后退出并重新进入世界 |
| 操作库存与碰撞 | `/mmm pour [1-16]`、`drain [1-16]`、`empty`（仅非控制器开发域）、`refresh`；控制器清空用控制器界面 `EMPTY_CONDENSED` 的预览/确认 |
| 运行与区域诊断 | `/mmm run`、`pause`、`step [1-20]`、`status`；`run` 可显式重试 wall-time `PERFORMANCE_PAUSE`，不会清除真正的数值失败；状态只读 live/committed runtime，不作为库存真值 |
| 控制器准入配置 | `/mmm controllers [true/false]`；关闭会暂停现有控制器域 |
| 世界桥接 | `/mmm worldbridge`；重启后生效 |
| 材料与界面调试 | `/mmm materialdemo`、`materialbucket ...`、`tensiondemo [angle]` |
| 化学调试 | `/mmm chemistrydemo`、`reagent ...`、`chemstatus` |
| 颗粒调试 | `/mmm granularcontainer ...`、`granstatus` |
| 气压调试 | `/mmm airdemo [pressure/vacuum]` |
| 可调恒温边界 | `/give @s mmm:thermal_reservoir`；放置后用 field probe 右击该方块，在服务端签发的调节界面里设置温度（K） |
| ownership 对账 | `/mmm ownership` |
| 调试与实体 | `/mmm debug`、`entities`、`logpath` |
| 容器 WAL 故障门 | `/mmm transferwal [fault <point>]`；仅用于事务恢复验证 |
| 安全移除 | `/mmm remove` |

## 当前物理边界

- marker 体积、逐材料质量、化学 species/组成/电荷、固体 SVQ/质量/组成、气体组分量/质量以及各载体总内能都是独立真值。
- pressure density、PLIC、平滑体积分数、客户端 mesh、tooltip、耐久条、pH、颜色和温度读数都只是数值或展示派生，不能参与库存结算。
- 强电解质只做完整解离和同格同 chemical phase 的瞬时计量反应。指示剂随 parcel 输运，但不隔离反应，也不会自动扩散到后来混入的 marker。
- 颗粒采用 active parcel + deposit cell 模型，包含统一排水/压力、浮力/阻力、安息角、孔隙水、休眠与局部唤醒。没有通用 MPM 框架。
- 温度由权威内能和 world thermal registry 的状态函数派生。热量只经有限体积边界通量、反应热、相变和机械功进入审计账本。
- 固液和气液相变受能量、库存、容量、饱和/分压条件、材料温度有效域与每步预算约束。气体 pressure/temperature/volume 由密闭节点库存派生；真空及低总压不会被误判为超出液气 transition 曲线。
- `thermal_reservoir` 是无限热浴，但实际交换仍只通过相邻 cut-face 通量；越出材料有效温区会安全暂停。

外部 Mod 可通过 JDK-only Query API 同步读取已加载区域的单格材料、体积、质量、密度、species 和可选温度；Fabric 发现入口为 `MmmFabricApi.query(server)`。查询不加载、不唤醒、不回写。`MmmFabricApi.events(server)` 只在容器 actor 已强制保存、WAL 已删除后发布 committed event，并以封闭 typed quantity 区分液体 nanovolume/raw `U` 与颗粒 SVQ。安装 Jade 时，观察本 Mod 的闭壳方块可显示相邻液体摘要；Jade 仍是可选依赖，主 Mod 不提供独立准星 HUD。受控写入、WAILA/HWYLA 与直接命中自由面的适配仍待实现。

## 构建

目标字节码为 Java 25，本机使用 JDK 26：

```powershell
./gradlew.bat assemble --offline --no-daemon
```

文档入口：

- [系统组件与数据流](docs/architecture/system-overview.md)
- [当前架构契约](docs/README.md)
- [外部 Query、committed event 与 typed quantity](docs/architecture/external-api.md)
- [代码职责与最小验证路由](docs/maintenance/routing.md)
- [未来产品路线](docs/roadmap/product-roadmap.md)
- [发布验收策略](docs/roadmap/acceptance-and-release.md)
- [主 Mod 版本历史](docs/progress/version-history.md)
