# 游戏内命令速查

`/mmm` 用于开发场景、管理员操作和故障诊断。正常创建区域的步骤见 [项目 README](../../README.md#在游戏里使用)。命令树定义在 [MmmCommands](../../fabric/src/main/java/cc/ecisr/mmm/MmmCommands.java)，处理器入口见 [命令路由](routing.md#gameplay)。

下表省略每项前面的 `/mmm`。

| 用途 | 命令或操作 |
|---|---|
| 创建开发区域 | `demo`、`capture`、`takeover` |
| 创建控制器测试场景 | `controllerdemo` |
| 列出、选择和保存区域 | `regions list`、`regions select <uuid>`、`regions checkpoint` |
| 恢复上一完整存档 | `regions recoverprevious <expectedCheckpoint>`；成功后退出并重新进入世界 |
| 增减库存或刷新碰撞 | `pour [1-16]`、`drain [1-16]`、`refresh` |
| 清空开发区域 | `empty`；控制器区域使用下文的界面流程 |
| 运行与诊断 | `run`、`pause`、`step [1-20]`、`status` |
| 控制器接入配置 | `controllers [true/false]`；关闭后现有控制器区域暂停 |
| 世界交互配置 | `worldbridge`；重启后生效 |
| 材料和界面场景 | `materialdemo`、`materialbucket ...`、`tensiondemo [angle]` |
| 化学场景与状态 | `chemistrydemo`、`reagent ...`、`chemstatus` |
| 颗粒物品与状态 | `granularcontainer ...`、`granstatus` |
| 气压场景 | `airdemo [pressure/vacuum]` |
| 世界方块归属检查 | `ownership` |
| 调试显示、实体与日志 | `debug`、`entities`、`logpath` |
| 容器事务故障注入 | `transferwal [fault <point>]`；在事务恢复测试场景中使用 |

`run` 与手动 `step` 可以重试性能暂停。其他不可用状态先按 `status` 的具体原因排查，入口见 [故障定位](routing.md#diagnosis)。

## 清理控制器区域

管理员手持物质场探针右击控制器，依次预览并确认：

1. `EMPTY_CONDENSED`：清空液体与固体物质。
2. `VENT_SEALED_GAS`：排放密闭气体，形成真空。
3. `RELEASE_OWNERSHIP`：释放世界方块归属。
4. `REMOVE_EMPTY_REGION`：移除已经清空的区域。

界面列出实际移除量和当前阻止原因。每步完成后再进行下一步。`/mmm remove` 用于非控制器开发区域。实现与恢复规则见 [区域生命周期](../architecture/world-ownership-and-containers.md#区域生命周期)。

## 调节恒温源

用 `/give @s mmm:thermal_reservoir` 取得方块。放置后，手持物质场探针右击它，在服务器提供的界面中设置温度，单位为 K。调节要求创造模式和相应写权限，允许温区由当前世界保存的热学定义决定。
