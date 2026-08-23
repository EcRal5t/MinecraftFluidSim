# M7 守恒化学

2026-08-23 自动关闭，主 Mod 升至 0.13.0；一次合并实机场景仍待用户签收。M7.0 冻结的 absolute parcel record、硬预算
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
（4×4×4 内部）以及不遮光的空心边界框架，等待同一实机场景复测。

修复后的发布 JAR 已重新通过 `boundary,containers`、离线 `clean assemble compileTestJava` 和根项目专用服务器正常
`Done`/`stop`，并部署为 mods 目录唯一启用的主 Mod；SHA-256 为
`1DE3142B87D4EC07394B56E06119AFC1B41692993E57894A181A190E98DE8B91`，0.12.0 保持 `.disabled`。

自动门无法观察的混合酸碱颜色、真实玩家/发射器物品事务和跨重启视觉/账本一致性，合并为一次用户实机场景；该场景签收后
才把 M7 从“自动关闭”改为“完整关闭”。
