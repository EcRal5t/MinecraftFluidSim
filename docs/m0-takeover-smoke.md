# M0 takeover 真实世界冒烟

只在专用测试世界执行。开始前完整复制世界目录；测试期间如果世界目录仍有 `realwater-takeover-backup.nbt` 或 `realwater-takeover-WARNING.txt`，不得卸载或降级 Mod。

## 测试布置

1. 建一个能完全落入 12×8×12 接管域的 3×3、深 2 格石质水池，填纯原版水源；不要使用 waterlogged 方块作为计数对象。
2. 在池边分别放置：接触池壁的沙柱和砾石柱、与池相邻的小型原版水槽、红石粉 + 中继器 + 红石灯回路，以及一个邻居更新敏感方块（例如火把、铁轨或甘蔗）。这些结构都保持有界，不连接大型红石机器或自然水系。
3. 记录测试前截图、世界备份路径和池内纯水方块数。

## A. 正常创建与删除

1. 进入测试世界，执行 `/realwater takeover`，保持模拟暂停。
2. 确认命令报告的“暂时移除”数量与预期纯水方块数一致；waterlogged 方块不应计入。
3. 在世界根目录确认 NBT journal 与 WARNING 同时存在，打开 WARNING，核对世界目录、维度、方块数和恢复步骤。
4. 执行 `/realwater status`，保存完整输出。
5. 在其中一个已移除水位置放一个实体方块，作为玩家编辑保护样本。
6. 执行 `/realwater remove`。记录消息中的恢复、已恢复、跳过数量；跳过应至少包含刚放置的实体方块。
7. 确认两个恢复文件都已删除，其他安全位置恢复为原版水，玩家实体方块未被覆盖。
8. 检查沙/砾石、相邻水、红石和敏感方块；记录本次恢复写入数（命令中的“恢复”）以及任何邻接变化。变化必须局限于上述小型布置，不能出现持续更新或向外扩散。

## B. 强制中断与启动恢复

1. 从测试前备份复制出新的测试世界，再次执行 `/realwater takeover`。
2. 确认两个恢复文件存在且原版水已移除；不要执行 `/realwater remove`。
3. 仅在这个有备份的测试世界中强制结束游戏，模拟崩溃。
4. 使用同一或更新的兼容 Mod 重启同一世界，等待服务器启动恢复。
5. 保存 `latest.log` 中 `Recovered unfinished Real Water takeover` 一行；其中 restored、already restored、skipped 是恢复更新计数。
6. 确认两个恢复文件均已删除、水已恢复、邻接测试结构没有无界级联。
7. 执行 `/realwater status`，确认 `takeover lock 无`。

## C. 失败判定与回传

出现任一情况立即停止，不再执行新的 takeover，并保留世界副本、journal、WARNING 和日志：恢复文件不可读或未删除；`status` 仍显示 lock；安全位置缺水；玩家实体方块被覆盖；相邻水/沙砾/红石发生无界连锁；启动日志出现恢复异常。

回传以下内容即可继续定位：A/B 两轮的 takeover、remove、status 完整文本；恢复前后截图；`Recovered unfinished` 或异常日志片段；两个恢复文件最终是否存在。

## 已完成结果

- 正常路径已恢复 48 个纯原版水方块，journal 与 WARNING 均删除，玩家实体方块保护正常。
- 2026-08-19 强制中断路径接管 480 个非空子格并暂时移除 60 个纯原版水方块。重启日志为 `restored 60, already restored 0, skipped 0`；两个恢复文件随后消失，`/realwater status` 显示 `takeover lock 无`，相邻水、沙砾、红石及更新敏感方块均正常。

M0 人工门已完成。后续只有修改 takeover 事务、journal、恢复 update flags 或扩大接管范围时才重跑相应场景。
