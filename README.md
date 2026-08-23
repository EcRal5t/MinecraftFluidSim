# Real Water Prototype

Minecraft Java 26.2 / Fabric 的有界三维 PIC/FLIP 流体原型。当前完成 M0–M7，主 Mod 0.13.0 由 CPU 权威求解，支持最多四个独立区域、控制器闭合边界、跨 chunk 完整加载、原子持久化、分数自由面和 cut-cell 碰撞；在默认关闭的材料门后提供多液体、可变密度、液液界面、液气表面张力和 Young 接触角润湿，并对密闭气腔提供等温气压、有限速率孔流与气液压力耦合。M6 加入可恢复的真实世界 ownership、精确容器和跨 region/item WAL；M7 再加入随 marker 守恒输运的液相强电解质、同格反应、pH 指示剂、化学桶以及 v3 存档/协议/WAL。

这还不是原版水的全局替代。普通模拟不自动修改世界；控制器功能默认关闭。

## 安全边界

- takeover 只临时移除纯 `Blocks.WATER`，并在修改世界前原子发布 journal v1 与 WARNING；恢复不会覆盖玩家放置的实体方块。
- takeover 恢复文件仍存在时不要移除或降级 Mod；先执行 `/realwater remove` 或让启动恢复完成。
- 控制器区域必须具备完整可见边界和全部已加载 chunk；Mod 不持有永久 chunk ticket。
- `region-state v1` 使用原子检查点。未知、损坏或未解决状态会锁住新控制器创建，不会被静默删除。
- 材料功能门默认关闭，且只在服务器启动时读取一次。开启后控制器区域改用 `region-state v2`，连接必须协商 protocol v2；关闭门时磁盘上的 v2 区域会被保留并锁住，不会静默降级或删除。
- 化学功能门同样默认关闭，要求材料门已配置为开启并在重启后生效。开启后区域使用 `region-state v3`、protocol v3 与 container-transfer WAL v3；关闭门发现 v3、registry snapshot 未知/损坏或 fingerprint 不符时只保留并 `LOCKED`，绝不降级猜测。
- 世界桥功能门（`/realwater worldbridge`）默认关闭且重启生效。开启后新激活的控制器区域会通过可恢复的 ownership 事务一次性移除内部纯原版水（WAL 与 WARNING 先行，region HEAD 最后提交）；区域与容器间的转移另有独立 WAL。移除含持久 ownership 的区域前必须先排空其水体。
- 可选 GPU probe 只读、不修改世界或存档；主 Mod 始终以 CPU 为物理规范。

## 常用命令

| 用途 | 命令 |
|---|---|
| 创建开发域 | `/realwater demo`、`capture`、`takeover` |
| 创建多个持久区域 | `/realwater controllerdemo` 安全创建 4×4×4 内部测试壳；或手建封闭壳后空手右击控制器；`regions list` 查看 |
| 多材料开发域（需材料门） | `/realwater materialdemo` |
| 材料桶调试取得 | `/realwater materialbucket <empty|water|oil|mercury|water_oil_mix>` |
| 表面张力液滴开发域（需材料门） | `/realwater tensiondemo [角度1-179]` |
| 气压 U 型管开发域 | `/realwater airdemo [pressure\|vacuum]` |
| 操作水量与碰撞 | `/realwater pour [1-16]`、`drain [1-16]`、`refresh` |
| 运行控制 | `/realwater run`、`pause`、`step [1-20]`、`status` |
| 区域与控制器 | `/realwater controllers [true|false]`、`controllerdemo`、`regions list`、`regions select <uuid>`、`regions checkpoint` |
| 材料功能门（下次重启生效） | `/realwater materials [true|false]` |
| 化学功能门（下次重启生效） | `/realwater chemistry [true|false]` |
| 化学开发域与试剂（需化学门） | `/realwater chemistrydemo`、`reagent <hcl|naoh|nacl|nano3|kcl|kno3|water|indicator|eraser>` |
| 管理员完整化学库存 | `/realwater chemstatus` |
| 世界桥功能门（下次重启生效） | `/realwater worldbridge [true|false]` |
| Ownership 对账报告 | `/realwater ownership` |
| 调试与实体 | `/realwater debug [true|false]`、`entities [true|false]`、`logpath` |
| 安全移除 | `/realwater remove` |

控制器玩法：快速验证可开启 `/realwater controllers true` 后执行管理员命令 `/realwater controllerdemo`，它只在玩家面前完整的空气体积内搭建并自动激活一个内部 4×4×4 的空壳，放置或激活被拒绝时回滚壳块。手工玩法则先用 `/give @s realwater:fluid_boundary` 和 `/give @s realwater:fluid_controller` 取得方块；用边界块建造完整的一格厚封闭外壳，以一个控制器替换壳块，再空手右击控制器。普通边界是透光金属框，但仍保持完整方块碰撞。每个互不重叠的控制器会新增并选中一个持久区域，默认总预算最多 4 个；用 `/realwater regions list` 和 `/realwater regions select <uuid>` 管理。`demo`/`empty` 是会替换当前开发域的单域兼容命令，不用于建立多个区域。首次激活只读采样内部原版水并创建暂停区域；若同时开启 `/realwater worldbridge true` 并重启，激活会一次性移除内部纯原版水并建立可恢复 ownership（`/realwater ownership` 查看对账）。玩家可对壳面中心直接使用容器；外部发射器须正对壳面中心，转移目标严格解析为其正后方的唯一内部格，壳边和壳角不会猜测路由。

多材料玩法：`/realwater materials true` 后重启服务器（单人模式退回主菜单再进入世界）。`/realwater materialdemo` 会创建上油下水的瞬态暂停溃坝域，不写 `region-state`，也不读取或接管原版水。正式材料容器入口需要材料门和世界桥均已生效：空材料桶可从准星所指控制器区域装取，材料桶可向目标区域放出；多成分 tooltip 逐项显示稳定材料 ID、体积占比/绝对体积和独立质量。纯水满桶规范化为原版水桶，部分纯水保留在专用部分桶；材料/混合物保留在材料桶。调试取得命令只用于显式测试来源。

化学玩法：先依次设置 `/realwater materials true`、`/realwater chemistry true` 与容器入口所需的 `/realwater worldbridge true`，再重启。`chemistrydemo` 建立左右 HCl/NaOH 的暂停开发域；`reagent` 提供明确记作外部 source 的试剂桶、通用指示剂与可重复使用抹除器。指示剂只标记当前通过开放 cut-face 连通且同 chemical phase 的 marker，最多 100 BV，后来混入的未标水不会自动染色。空材料桶可完整保存 species、质量 residual 与 indicator；原版空桶只接受未标记纯 H2O。完整逐 species 真值只由管理员 `chemstatus` 按需查看并写入 transcript。

气压玩法：`/realwater airdemo pressure`（或 `vacuum`）在准星方向自动搭一座玻璃 U 型管（左腔密闭、右腔通大气）并下半注水；`/realwater run` 后两侧液面形成并保持由气压支撑的稳定高差（正压约 1.4 m、负压约 2.1 m），`/realwater status` 的 gas 段显示密闭腔压力与库存。玻璃只替换空气方块，`/realwater remove`、创建其他开发域或正常关服时自动恢复原状；异常崩溃后残留的玻璃可自行拆掉。`/realwater debug true` 时密闭气腔以高度透明灰色显示。该域瞬态，不写 `region-state`。

## 构建

目标字节码为 Java 25；本机使用 JDK 26：

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat assemble --offline --no-daemon
```

代码职责、最小测试选择和专项 Gradle 命令不在这里重复，统一从 [文档索引](docs/README.md) 进入：

- [当前架构契约](docs/architecture/fluid-solver.md)、[M6 世界 ownership/容器契约](docs/architecture/world-ownership-and-containers.md)与[M7 化学状态契约](docs/architecture/chemistry-state.md)
- [完成进度与验收证据](docs/progress/README.md)
- [未来里程碑与技术规格](docs/roadmap/product-roadmap.md)
- [代码与测试维护路由](docs/maintenance/routing.md)
