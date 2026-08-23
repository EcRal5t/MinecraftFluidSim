# M7 守恒化学

2026-08-23 完整关闭，主 Mod 升至 0.13.0。M7.0 冻结的 absolute parcel record、硬预算
和 v3 边界已完整进入 CPU 权威生产路径，现行契约见 [`../architecture/chemistry-state.md`](../architecture/chemistry-state.md)。

## 已落地边界

- 15 个内建 species、3 个 liquid chemical phase、6 个 complete dissociation 与一个瞬时中和反应由数据包 exact codec 加载；
  stable-ID canonical bytes 的 SHA-256 fingerprint 为
  `f6dcf366a816b403bf412b37f5791a49bf0463d367bba236704e37bd9a1d4b7a`。首次启用原子发布世界 registry snapshot，之后存档
  snapshot 权威；数据包变化只报告 mismatch。
- chemistry-off 不分配 parcel 数组；chemistry-on 要求 materials-on。marker handle、indicator bit、packed species entries 与
  raw-double mass residual 经 advection、split/merge、compaction、collision displacement、checkpoint/restore 和容器转移保持。
  drain/escape 与创造插入分别累计 escaped-sink/external-source 审计账本。
- reaction 每外部 step 只在最终 advection 后、reseeding 前运行一次，并按 `(cell, chemical phase)` 聚合；相邻格、墙后与
  不可互溶 phase 不反应。species largest-remainder 按 marker 二进制体积和 ordinal 重分配，indicator provenance 不参与分组。
- region-state v3 保存 registry identity、canonical mixture table、per-marker table index/indicator、region/section species 与
  residual ledger以及 source/sink extension。v1/v2 到 v3 只写非活动槽并最后发布 HEAD；门关闭、未知/损坏、缺 registry、
  fingerprint/代次/账本失配均保留并锁定。
- `ChemicalContents v1` 与 legacy `FluidMixture v1` 互斥；玩家与发射器沿实际目标 region 执行 count=1 转移。controller
  container-transfer WAL v3 的 actor-after 只有在 v3 HEAD 发布并 durable save 后才允许清除；v1/v2 恢复语义保持。
- protocol v3 继续携带 v2 material/phase 几何，只新增有界 species palette、每格 Top-4、展示 residual、pH 与 indicator
  fraction。客户端颜色只读，不反写 solver。通用指示剂按 cut-face/phase flood fill，添加最多 100 BV；抹除器可重复使用。

## 自动验收

- `chemistry` 完整门：exact codec/fingerprint、非法定义矩阵、两组等价盐、等量/双向过量酸碱、10,000 次无 long 漂移；
  同格/相邻格/墙后/不可互溶隔离、advection 后 reaction、split/merge/compaction、collision、handoff 与化学桶 round-trip。
- 冻结性能门实测：73,728-marker/9,216-cell 额外 primitive 常驻模型 13.02 MiB（上限 64 MiB）；代表性静态域
  chemistry-on/no-dirty 配对中位 step 增量 1.84%（上限 5%）；64-marker 酸碱 fixture p95 0.642 ms（上限 10 ms）。
  同一 JVM 交替测量 30-step warmup、15 组各 20 step，并在全新 JVM 重复通过；阈值未改动现有 40/200 ms pacing。
- `region-state`：v1/v2 golden bytes、v3 canonical/CRC/ledger、v1/v2→v3 与全部发布故障点、UNLOADED、未知/损坏/
  foreign fingerprint 锁定。
- `containers`：pure/material/chemical ledger、partial settlement、双表示/foreign registry 拒绝、玩家/发射器 actor codec、
  v1/v2/v3 WAL 与 recovery/fault boundary。
- `protocol,chemistry`：v1/v2/v3 negotiation、长度攻击、palette、sequence/generation/tombstone/reconnect、Top-4 量化闭合；
  默认 9,216-cell v3 payload 小于 512 KiB。

关闭运行中，短 `milestone` 的 19 个领域以 8.182 s runner 时间通过；完整 `chemistry` 在两个全新 JVM 连续通过；离线
`clean assemble compileTestJava` 成功。EULA=true 根项目专用服务器加载 `realwater 0.13.0` 到 `Done`，收到 `stop` 后正常保存
并以 Gradle success 退出，日志无 `LOCKED`/`ERROR`。第一次实机签收暴露了壳面入口缺陷：玩家把不可替换壳块的外侧空气
当成目标，外置发射器则把壳块本身当成目标，两者都未到达内部 cell。现已统一为严格的“壳面块 → 唯一面相邻内部格”解析；
发射器还必须沿朝向继续进入该格，边/角和跨域歧义均拒绝。同期加入只占空气且失败回滚的 `/realwater controllerdemo`
（4×4×4 内部）以及不遮光的空心边界框架。首次命令实测又发现零 marker 化学域被 handoff/codec 的非空 mixture-table
校验拒绝；现已冻结并覆盖合法空状态：零 marker 必须空 table，非空域仍必须具备逐 marker 有效引用。

第二次实机签收暴露了三个可复现的 M7 问题。第一，逐 species 独立 largest-remainder 在极小 marker 上可能让每种 species
都舍入为零；全量事务正确地拒绝该计划并把 region 置为 `chemistry-blocked PAUSED`，所以后续桶操作静默拒绝，重启则从
最后完整 checkpoint 恢复。现按 marker ordinal 为零总量 parcel 做确定性修复：从总量最大的 donor 已分配条目搬运一个
quantum，逐 species 总量不变；若总 quanta 确实少于 live marker 数量仍整体拒绝。暂停原因和不可用区域原因现直接反馈给
玩家。第二，浅水重建格可能有正密度但没有 marker；指示剂入口现在只沿正 cut-face 在 Manhattan 距离 2 内吸附到真实
marker cell，墙后仍拒绝。第三，化学桶 tooltip 在每个 material/indicator group 下显示逐 species stable ID、exact AU 与
quanta，不再只有组总量。

上述修复分别通过极小 parcel 反应守恒/indicator provenance、浅水吸附/不可穿墙、完整 `chemistry`、`containers` 与
`lifecycle` 最近门，并重新通过离线 `clean assemble compileTestJava`。发布 JAR 已部署为 mods 目录唯一启用的主 Mod；
SHA-256 为 `37CB7D3D15648707D2366F7304BCD4C6DCBC8937E34A5B9EA5698E2B96416377`，0.12.0 保持 `.disabled`。

同次实机截图中的黑色三角面登记为 M7 外独立渲染债：其三角拓扑外观更符合客户端表面 mesh/透明深度伪影，现有证据
不足以归因于密闭域或 marker 输运。按冻结范围，本轮不改逐顶点法线、液面锐化或液液物理，也不以渲染补丁掩盖潜在
输运问题；后续应在开放/密闭域分别建立最小视觉 fixture 后单独评审。

最终合并实机场景已由用户签收。修复版启动从 checkpoint 77 恢复 1268 个 marker；浅水指示剂、逐 species tooltip、玩家
抽取/倒入及发射器“溶液 + 指示剂”桶均符合预期。发射器事务后区域继续以 `ACTIVE` 运行，`chemstatus` 在同一 registry
fingerprint 下持续报告 H2O、H+/OH- 与旁观离子，过量侧和中和结果符合计量；未出现 `chemistry-blocked`、`LOCKED` 或 Mod
异常。全部 container-transfer WAL 收敛到 durable actor-after 且目录最终为空，正常关服最后发布 checkpoint 203。至此自动门、
真实物品事务、视觉派生和重启持久性共同闭合 M7。
