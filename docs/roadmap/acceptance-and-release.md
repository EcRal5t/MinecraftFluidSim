# 验收与发布门

本文用于发布或产品阶段关闭，不是日常改动的累计清单。日常门只从 [维护路由](../maintenance/routing.md) 选择。

## 风险矩阵

| 层次 | 必须回答 |
|---|---|
| 物理真值 | 体积、质量、组成、电荷、固体量、气体量、动量和能量是否只通过明确 source/sink/work 改变？ |
| 数值离散 | CFL、pressure residual、界面、cut-cell、密度比和 reseed 是否稳定且无 NaN？ |
| 渲染 | 稀薄 marker、多材料界面、颗粒和温度是否可见且不反写状态？ |
| 调度 | active、pacing、休眠、唤醒和暂停是否得到同一守恒结果？ |
| 持久化 | 写入/恢复中断、未知布局、chunk 卸载和玩家编辑是否 fail-closed？ |
| 网络 | capability、sequence、reconnect、unknown optional payload 和拒绝路径是否正确？ |
| 游戏交互 | controller、容器、实体、方块更新和多人观察是否一致？ |
| 资源 | block/item/model/recipe、反馈、本地化和功能门默认值是否完整？ |

不受改动影响的层明确写“不涉及”，不为此重跑其他领域门。

## 验证阶梯

1. 独立 CPU selector 覆盖数值和账本。
2. 接入 Minecraft 边界后才使用专服或 GameTest 覆盖 block/item/save/recovery。
3. 改后端或规模时做 CPU/GPU 容差对照与性能基准。
4. 只有自动门无法观察渲染、真实 `VoxelShape` 或真实世界事务时，才执行单一人工场景。
5. 只有长期稳定性属于风险时才运行 soak，并记录最大残差、账本误差、marker/carrier、内存、GC 和 tick time。

CPU 验证由单一 `runCpuValidation` runner 按 selector 执行；`check` 不隐式运行全部领域。新增场景优先替换重复不变量的测试，长期场景进入 `soak`。

## 可复现性

- CPU 参考场景固定种子、遍历顺序和输入；可以稳定逐 bit 时保留该保证。
- 并行 CPU 和 GPU 按库存账本、pressure residual 和场值容差验收。
- 性能报告记录 commit、后端、设备/驱动、种子、网格、时间步、容差和可重放输入。

## 扩大范围前

- 代表性静态域至少 80% 体积进入守恒休眠，且观察不唤醒。
- 活动成本随实际扰动 section 与边界表面积增长。
- 常规 GPU step 只回读紧凑快照或有界查询。
- 单 section checkpoint、恢复、唤醒和网络快照都有硬上限。
- 跨 chunk 区域不半加载步进；checkpoint 失败不暴露混合 generation。
- ownership 和容器在每个异常提交边界都可确定恢复或锁定。

## 发布构建

发布前按实际风险选择完整 selector，再运行：

```powershell
./gradlew.bat clean assemble compileTestJava --offline --no-daemon
```

检查 mods 目录中同一 Mod id 只有一个启用 JAR，并记录产物 SHA-256。
