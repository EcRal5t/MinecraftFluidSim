# Real Water Prototype

Minecraft Java 26.2 / Fabric 的有界三维 PIC/FLIP 流体原型。当前完成 M0–M2.5：主 Mod 由 CPU 权威求解，支持最多四个独立区域、控制器闭合边界、跨 chunk 完整加载、原子持久化，以及分数自由面和 cut-cell 碰撞。下一阶段是 M3 多液体与可变密度。

这还不是原版水的全局替代。普通模拟不自动修改世界；控制器功能默认关闭。

## 安全边界

- takeover 只临时移除纯 `Blocks.WATER`，并在修改世界前原子发布 journal v1 与 WARNING；恢复不会覆盖玩家放置的实体方块。
- takeover 恢复文件仍存在时不要移除或降级 Mod；先执行 `/realwater remove` 或让启动恢复完成。
- 控制器区域必须具备完整可见边界和全部已加载 chunk；Mod 不持有永久 chunk ticket。
- `region-state v1` 使用原子检查点。未知、损坏或未解决状态会锁住新控制器创建，不会被静默删除。
- 可选 GPU probe 只读、不修改世界或存档；主 Mod 始终以 CPU 为物理规范。

## 常用命令

| 用途 | 命令 |
|---|---|
| 创建开发域 | `/realwater demo`、`capture`、`takeover` |
| 操作水量与碰撞 | `/realwater pour [1-16]`、`drain [1-16]`、`refresh` |
| 运行控制 | `/realwater run`、`pause`、`step [1-20]`、`status` |
| 区域与控制器 | `/realwater controllers [true|false]`、`regions list`、`regions select <uuid>`、`regions checkpoint` |
| 调试与实体 | `/realwater debug [true|false]`、`entities [true|false]`、`logpath` |
| 安全移除 | `/realwater remove` |

控制器玩法：用 `realwater:fluid_boundary` 建造完整的一格厚封闭外壳，以一个 `realwater:fluid_controller` 替换外壳方块，开启 `/realwater controllers true`，再空手右击控制器。首次激活只读采样内部原版水并创建暂停区域，不执行 takeover。

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
