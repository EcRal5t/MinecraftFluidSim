# Real Water

Minecraft Java 26.2 / Fabric 的有界三维物理流体 Mod。主 Mod 使用 CPU 权威 PIC/FLIP 求解器，支持最多四个独立区域、控制器闭壳、跨 chunk 完整加载、原子 checkpoint、分数自由面、cut-cell 碰撞、多液体、守恒化学、低成本颗粒沉积以及热学与相变。

这不是原版水的全局替代。普通模拟不会自动接管世界，控制器与所有扩展物理功能默认关闭。

## 持久化与安全边界

- 控制器区域必须有完整边界，所需 chunk 必须全部加载；Mod 不持有永久 chunk ticket。
- 世界接管只处理纯原版水。ownership journal 和 WARNING 先发布，原版方块随后修改，region HEAD 最后提交。
- 区域 checkpoint 使用双槽完整代次与 HEAD 最后发布。`regions recoverprevious` 只在当前 HEAD 恢复失败后，显式验证并激活紧邻的上一完整代。
- 区域载荷必须与本次启动冻结的功能门完全一致。任何格式、功能布局、registry fingerprint、身份或账本不一致都会保留原文件并锁定，不改写载荷或猜测修复。
- 容器事务跨区域、物品和玩家/发射器持久域。actor 的 after-item 完成 durable save 前不得删除 WAL；未知、损坏或布局不一致的 WAL 保留并锁定。
- `empty` 联合清空液体、连续凝聚相、active parcel 和 deposit，并把实际移除的物质与能量记入 sink；存在未决容器 WAL 或相变功时拒绝执行。
- 可选 GPU probe 只读且不修改世界或存档；主 Mod 始终以 CPU 结果为规范。

功能门只在服务器启动时读取。请在创建持久控制器区域前一次配置好需要的 `materials`、`chemistry`、`granular`、`precipitation`、`thermal` 和 `phasechange` 组合；之后改变组合不会改写现有区域。

## 常用命令

| 用途 | 命令 |
|---|---|
| 创建开发域 | `/realwater demo`、`capture`、`takeover` |
| 创建持久闭壳 | `/realwater controllers true`，重启后执行 `/realwater controllerdemo`；或手建边界并空手右击控制器 |
| 选择与保存区域 | `/realwater regions list`、`regions select <uuid>`、`regions checkpoint` |
| 恢复上一完整 checkpoint | `/realwater regions recoverprevious <expectedCheckpoint>`；成功后退出并重新进入世界 |
| 操作库存与碰撞 | `/realwater pour [1-16]`、`drain [1-16]`、`empty`、`refresh` |
| 运行控制 | `/realwater run`、`pause`、`step [1-20]`、`status` |
| 功能门 | `/realwater materials`、`chemistry`、`granular`、`precipitation`、`thermal`、`phasechange`、`worldbridge`；均在重启后生效 |
| 材料与界面调试 | `/realwater materialdemo`、`materialbucket ...`、`tensiondemo [angle]` |
| 化学调试 | `/realwater chemistrydemo`、`reagent ...`、`chemstatus` |
| 颗粒调试 | `/realwater granularcontainer ...`、`granstatus` |
| 气压调试 | `/realwater airdemo [pressure|vacuum]` |
| 可调恒温边界 | `/give @s realwater:thermal_reservoir`；准星对准后 `/realwater thermalsource [temperatureK]` |
| ownership 对账 | `/realwater ownership` |
| 调试与实体 | `/realwater debug`、`entities`、`logpath` |
| 安全移除 | `/realwater remove` |

## 当前物理边界

- marker 体积、逐材料质量、化学 species/组成/电荷、固体 SVQ/质量/组成、气体组分量/质量以及各载体总内能都是独立真值。
- pressure density、PLIC、平滑体积分数、客户端 mesh、tooltip、耐久条、pH、颜色和温度读数都只是数值或展示派生，不能参与库存结算。
- 强电解质只做完整解离和同格同 chemical phase 的瞬时计量反应。指示剂随 parcel 输运，但不隔离反应，也不会自动扩散到后来混入的 marker。
- 颗粒采用 active parcel + deposit cell 模型，包含统一排水/压力、浮力/阻力、安息角、孔隙水、休眠与局部唤醒。没有通用 MPM 框架。
- 温度由权威内能和 world thermal registry 的状态函数派生。热量只经有限体积边界通量、反应热、相变和机械功进入审计账本。
- 固液和气液相变受能量、库存、容量、饱和压力、绝对压力有效域与每步预算约束。气体 pressure/temperature/volume 由密闭节点库存派生。
- `thermal_reservoir` 是无限热浴，但实际交换仍只通过相邻 cut-face 通量；越出材料有效温区会安全暂停。

局部温度查询和 Jade/WAILA/HWYLA 适配属于下一阶段的外部观察 API；主 Mod 不提供准星温度 HUD。

## 构建

目标字节码为 Java 25，本机使用 JDK 26：

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat assemble --offline --no-daemon
```

文档入口：

- [当前架构契约](docs/README.md)
- [代码职责与最小验证路由](docs/maintenance/routing.md)
- [未来产品路线](docs/roadmap/product-roadmap.md)
- [发布验收策略](docs/roadmap/acceptance-and-release.md)
