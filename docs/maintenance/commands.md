# 游戏内命令速查

`/mmm` 用于开发场景、管理员操作和故障诊断。正常创建区域的步骤见 [项目 README](../../README.md#在游戏里使用)。
命令树定义在 [MmmCommands](../../fabric/src/main/java/mmm/MmmCommands.java)，处理器入口见 [命令索引](../architecture/system-overview.md#gameplay)。

下表省略每项前面的 `/mmm`。

| 用途 | 命令或操作 |
|---|---|
| 打开演示面板 | `demo`、`demos`；快速入门手册中的“演示” |
| 创建空演示水箱 | `controllerdemo` |
| 大型溃坝与世界采样诊断 | `damdemo`、`capture`、`takeover` |
| 列出、选择和保存区域 | `regions list`、`regions select <uuid>`、`regions checkpoint` |
| 恢复上一完整存档 | `regions recoverprevious <expectedCheckpoint>`；成功后退出并重新进入世界 |
| 增减库存或刷新碰撞 | `pour [1-16]`、`drain [1-16]`、`refresh` |
| 清空开发区域 | `empty`；控制器区域使用下文的界面流程 |
| 运行与诊断 | `run`、`pause`、`step [1-20]`、`status` |
| 控制器接入配置 | `controllers [true/false]`；关闭后现有控制器区域暂停 |
| 世界交互配置 | `worldbridge`；重启后生效 |
| 油水配方与材料物品 | `materialdemo`、`materialbucket ...` |
| 酸碱配方与化学状态 | `chemistrydemo`、`reagent ...`、`chemstatus` |
| 放大毛细效应的进阶实验 | `tensiondemo [angle]` |
| 颗粒物品与状态 | `granularcontainer ...`、`granstatus` |
| 气压场景 | `airdemo [ambient/pressure/vacuum]` |
| 世界方块归属检查 | `ownership` |
| 调试显示、实体与日志 | `debug`、`entities`、`logpath` |
| 容器事务故障注入 | `transferwal [fault <point>]`；在事务恢复测试场景中使用 |

`run` 与手动 `step` 可以重试性能暂停。其他不可用状态先按 `status` 的具体原因排查，入口见 [故障定位](../architecture/system-overview.md#diagnosis)。

## 演示面板

管理员在空旷、区块已加载的位置打开面板，选择水箱、水流或气压。水箱的清水、油水和酸碱共用同一个真实控制器区域；水流池带池壁、障碍和入池台阶；气压管在同一几何中切换常压、加压和低压。开始后返回游戏画面，重置后保持暂停。

“开始”创建并运行所选场景，“暂停”和“单步”操作当前场景，“重置”在原位置恢复所选初态并暂停，“移除”清理演示区域及仍保持原样的演示外壳。材料、化学、控制器和气压快捷命令均打开这个面板，并准备相应的暂停场景。创建水箱时发放缺少的水桶、空桶、材料桶和探针；水箱通过实际接口交接库存。重置会清空原配方，恢复室温常压后投料。

每位玩家在每个维度维护一个演示；水箱身份随世界保存，重新进入可继续重置。临时水流／气压场景共用一个名额，创建前需移除其他玩家或命令留下的临时场景。操作需在演示附近进行。演示仍使用世界能力、Vulkan 求解和正常超时暂停规则。

## 清理控制器区域

管理员手持物质场探针右击控制器，依次预览并确认：

1. `EMPTY_CONDENSED`：清空液体与固体物质。
2. `VENT_SEALED_GAS`：排放密闭气体，形成真空。
3. `RELEASE_OWNERSHIP`：释放世界方块归属。
4. `REMOVE_EMPTY_REGION`：移除已经清空的区域。

界面列出实际移除量和当前阻止原因。每步完成后再进行下一步。`/mmm remove` 用于非控制器开发区域。实现与恢复规则见 [区域生命周期](../architecture/world-ownership-and-containers.md#区域生命周期)。

## 调节恒温源

用 `/give @s mmm:thermal_reservoir` 取得方块。放置后，手持物质场探针右击它，在服务器提供的界面中设置温度，单位为 K。调节要求创造模式和相应写权限，允许温区由当前世界保存的热学定义决定。

## 切换流体显色

手持物质场探针右击控制器，打开“流体显色设置”。可选择组分、pH 或温度，并调整温度色带下限与上限（K）。选择保存在当前客户端，作用于全部可见流体区域，暂停时也立即换色。
组分使用材料和化学组分颜色；pH 色带为 0–14；温度使用单元凝聚相展示值。灰色代表不适用或无数据。
