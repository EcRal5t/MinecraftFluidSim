# Real Water Prototype

Minecraft Java 26.2 / Fabric 的有界三维 PIC/FLIP 流体原型。当前完成 M0–M5：主 Mod 由 CPU 权威求解，支持最多四个独立区域、控制器闭合边界、跨 chunk 完整加载、原子持久化、分数自由面和 cut-cell 碰撞，在默认关闭的材料门后提供多液体、可变密度、液液界面、液气表面张力和 Young 接触角润湿，并对密闭气腔提供等温气压、有限速率孔流与气液压力耦合。下一阶段是 M6 世界 ownership 与流体桥。

这还不是原版水的全局替代。普通模拟不自动修改世界；控制器功能默认关闭。

## 安全边界

- takeover 只临时移除纯 `Blocks.WATER`，并在修改世界前原子发布 journal v1 与 WARNING；恢复不会覆盖玩家放置的实体方块。
- takeover 恢复文件仍存在时不要移除或降级 Mod；先执行 `/realwater remove` 或让启动恢复完成。
- 控制器区域必须具备完整可见边界和全部已加载 chunk；Mod 不持有永久 chunk ticket。
- `region-state v1` 使用原子检查点。未知、损坏或未解决状态会锁住新控制器创建，不会被静默删除。
- 材料功能门默认关闭，且只在服务器启动时读取一次。开启后控制器区域改用 `region-state v2`，连接必须协商 protocol v2；关闭门时磁盘上的 v2 区域会被保留并锁住，不会静默降级或删除。
- 可选 GPU probe 只读、不修改世界或存档；主 Mod 始终以 CPU 为物理规范。

## 常用命令

| 用途 | 命令 |
|---|---|
| 创建开发域 | `/realwater demo`、`capture`、`takeover` |
| 多材料开发域（需材料门） | `/realwater materialdemo` |
| 表面张力液滴开发域（需材料门） | `/realwater tensiondemo [角度1-179]` |
| 气压 U 型管开发域 | `/realwater airdemo [pressure\|vacuum]` |
| 操作水量与碰撞 | `/realwater pour [1-16]`、`drain [1-16]`、`refresh` |
| 运行控制 | `/realwater run`、`pause`、`step [1-20]`、`status` |
| 区域与控制器 | `/realwater controllers [true|false]`、`regions list`、`regions select <uuid>`、`regions checkpoint` |
| 材料功能门（下次重启生效） | `/realwater materials [true|false]` |
| 调试与实体 | `/realwater debug [true|false]`、`entities [true|false]`、`logpath` |
| 安全移除 | `/realwater remove` |

控制器玩法：用 `realwater:fluid_boundary` 建造完整的一格厚封闭外壳，以一个 `realwater:fluid_controller` 替换外壳方块，开启 `/realwater controllers true`，再空手右击控制器。首次激活只读采样内部原版水并创建暂停区域，不执行 takeover。

多材料玩法：`/realwater materials true` 后重启服务器（单人模式退回主菜单再进入世界），再用 `/realwater materialdemo` 创建上油下水的暂停溃坝域。该域是瞬态的，不写 `region-state`，也不读取或接管原版水；桶与方块入口仍只支持纯水。

气压玩法：`/realwater airdemo pressure`（或 `vacuum`）创建左腔密闭、右腔通大气的 U 型管域；`/realwater run` 后两侧液面形成并保持由气压支撑的稳定高差（正压约 1.8 m、负压约 1.3 m），`/realwater status` 的 gas 段显示密闭腔压力与库存。该域同样瞬态，不写 `region-state`。

## 构建

目标字节码为 Java 25；本机使用 JDK 26：

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat assemble --offline --no-daemon
```

代码职责、最小测试选择和专项 Gradle 命令不在这里重复，统一从 [文档索引](docs/README.md) 进入：

- [当前架构契约](docs/architecture/fluid-solver.md)
- [完成进度与验收证据](docs/progress/README.md)
- [未来里程碑与技术规格](docs/roadmap/product-roadmap.md)
- [代码与测试维护路由](docs/maintenance/routing.md)

## License

CC0-1.0
