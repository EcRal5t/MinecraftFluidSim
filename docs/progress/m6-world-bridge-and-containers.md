# M6 世界 ownership、流体桥与容器（已完成）

2026-08-23 关闭，主 Mod 升至 0.12.0。M6.0 选定的“空气 + 独立 registry”已完成生产化；M6.1a ownership 与 M6.1b 精确容器均通过自动故障注入、专用服务器启动和用户实机 Smoke C。当前行为契约见 [`../architecture/world-ownership-and-containers.md`](../architecture/world-ownership-and-containers.md)。

## 最终实现

- controller 区域以 region-state HEAD 为唯一提交点，通过 per-region WAL/WARNING/committed sidecar crash-safe acquire/release；启动恢复、未知/损坏数据、代次/身份失配和玩家方块优先均 fail-closed。
- 容器以 1 BV=`1e9` 纳体积量子记账；部分纯水桶、单材料桶和 canonical 多材料混合桶分别保留精确体积，材料桶额外保留逐材料独立质量。纯水满/空边界规范化为原版水桶/空桶。
- solver 增加体积、单材料及混合物的 all-or-nothing insert/extract；拒绝零残留，尾 marker 守恒拆分，体积与材料质量各自结算。
- 玩家桶和发射器共享 `FluidTransferService`，目标由准星/发射方向决定而非命令选区；堆叠输入安全拒绝。创造放出记为外部 source，装取替换空容器副本，不制造隐藏 sink。
- region/item 跨持久域使用独立 container-transfer WAL v2；玩家离线或发射器未加载保持 pending，actor after item 持久保存成功后才删除 WAL。严格目录扫描会保留并锁住未知/损坏证据。
- 部分纯水桶动态合并配方守恒 remainder；DataComponent 经漏斗、掉落、死亡、断线和重启原样往返。多材料 tooltip 逐项显示稳定材料 ID、体积占比/绝对体积和质量。

M6 最终审查还修复了：首次 publication staging 残留、prepare 失败未 abort、unknown/corrupt ownership 布局被过度恢复、旧 v1 材料 WAL 缺失独立质量却可能被接受、材料桶只保存总体材料、桶转移错误依赖选区、运行区域转移后被错误暂停、stacked empty bucket 可能丢物，以及 Gradle `check` 对非 JUnit validation main 的错误“无测试”失败。

## 自动与启动门

关闭前运行一次完整 CPU 回归（2026-08-23，约 1 分 15 秒），覆盖 `check`、当前 solver/free-surface/cut-cell/reseeding、M3/M4/M5、manager/lifecycle/controller、protocol、persistence、takeover、ownership 与容器相关 JavaExec 门，全部通过。最终受影响层的定向结果：

| 门 | 结果 |
|---|---|
| `runM6ContainerValidation` | 9 组通过：量化/规范化、组件 codec、混合体积+独立质量、stack/creative/target/dispenser、tooltip 所依赖数据契约 |
| `runContainerTransferValidation` | 10 组通过：WAL v2、v1 兼容边界、HEAD 决策、actor 收敛、严格 store 扫描与故障恢复 |
| `runM6OwnershipValidation` | 21 场景通过：prepare/abort/staging、PRE/POST-COMMIT、release、玩家优先、启动次序、unknown/corrupt/代次失配锁定 |
| `runFluidRegionPersistenceValidation` | 分段检查点、v1/v2 与 config v3 迁移通过 |
| `runM3ConnectedVesselValidation` | 多材料体积/质量与变密度连通器回归通过 |
| `clean assemble compileTestJava check` | Java 25 clean release 构建通过 |
| EULA=false dedicated server | Minecraft 26.2、Fabric Loader 0.19.3、主 Mod 与可选 GPU probe 完成加载/入口启动，然后按原版 EULA 门正常停止 |

## 实机 Smoke C（用户于 2026-08-23 签收）

用户逐项复测并确认“全部符合预期”：

- 单材料与水/油混合材料桶的装取、放出及体积/质量账本正确；运行中的区域在转移后保持运行意图；
- 发射器成功路径和拒绝路径保持物品/区域一致，无原版流体绕过 ledger；
- 创造模式 source/scoop 语义、满/部分/空规范化和堆叠桶拒绝均符合预期，无吞物或隐藏 sink；
- 材料组件经掉落、漏斗、重启等 inventory 生命周期保持；
- 重启后 region/item 收敛，无遗留 `container-transfer` 锁、generation mismatch 或 ownership LOCKED。

Smoke C 是 M6 最后一个人工门；新增逐项 tooltip 只读同一已验收组件账本，不改变转移或持久化语义。

## 发布分层结论

按 [`../roadmap/acceptance-and-release.md`](../roadmap/acceptance-and-release.md) 汇总：

- 物理/守恒：修改精确边界转移，不改 PIC/FLIP 步进；体积与逐材料质量门闭合。
- 几何/世界：仅显式控制器闭壳区域；ownership 世界写入有 WAL，玩家实体方块优先。
- 数值：没有调整 CFL、压力、PLIC、cut-cell 或调度阈值；完整 CPU 回归无退化。
- 生命周期/存档：region-state HEAD、ownership sidecar 与 container WAL 的崩溃顺序均有故障注入；未知状态 fail-closed。
- 协议/渲染：没有修改 protocol 格式；tooltip 是 item component 的只读展示。
- GPU：不涉及；GPU probe 仍可选且只读，CPU 仍为规范后端。

## 保留边界

- `demo`/`empty` 是替换选中开发域的兼容命令；多区域创建走互不重叠的控制器，默认总数 4。
- 自定义 partial/material bucket 的合法 stack size 为 1；区域目标内的 stacked vanilla empty bucket 也整体拒绝。
- M6 不提供其他 Mod 的流体 API，不自动接管开放世界原版水，也不适配灭火/岩浆等全部原版行为；这些属于 M10。
- container-transfer WAL v1 仅对纯水事务兼容；旧材料事务缺独立质量，保留并锁定供人工处置。
- CPU wall-time 降频/硬暂停策略未在 M6 调参；性能后端与调度优化继续按路线图单独量化。
