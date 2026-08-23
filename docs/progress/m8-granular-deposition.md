# M8 颗粒沉淀与低成本沉积进度

## M8.0 设计门（2026-08-24）

基线为主 Mod 0.13.0、提交前工作树干净。修改前 `runCpuValidation` 的 13 个 fast 领域全部通过，runner 2.958 秒。

正式设计冻结在 [`../architecture/granular-state.md`](../architecture/granular-state.md)：选用活动 parcel + 守恒
deposit cell，不预建 DEM/MPM；固体体积使用 `2^27 SVQ/BV` 的 checked-long 独立真值，质量与固相组成另行结算；
moving-solid capacity source 与 pressure/free-surface/air 共享同一耦合几何；孔隙只影响 bulk 形态和 Darcy 阻力，
孔隙水仍由液体 marker 拥有。

隔离原型未进入发布 source set，结果为：

| 项目 | 结果 |
|---|---|
| 轻/中性/重颗粒终速 | `+0.033391 / 0 / -0.076737 m/s`，半隐式积分匹配 Schiller--Naumann root oracle |
| 两向阻力动量 | fixture 前后总动量均为 0 |
| 跨格 occupancy | 5,592,422 SVQ 分配与 moving source exact re-sum |
| skeleton/bulk | 40% porosity 只按 skeleton 扣液体容量 |
| parcel↔deposit | 10,000 往返后 1 BV 仍为 134,217,728 SVQ |
| 安息角 | 32° 一维为 32.000°；四邻域朝向误差 11.027°，合法八邻域降为 3.930° |
| primitive arrays | 默认单 region 候选约 3.34 MiB |

版本决定遵守已有冻结边界：气体持久化仍保留独立 `region-state v4` 评审，M8 颗粒完整联合存档使用
`region-state v5`；protocol v4、container-transfer WAL v4 与 region 版本互不推断。

下一增量只实现无 Minecraft 依赖的 registry、SVQ 库存与 parcel/deposit 事务，并新增现有 runner 下唯一的
`granular` selector；在该门通过前不修改 region-state、protocol、item 或生产 solver。

## M8.1a definition 与库存 core（2026-08-24）

- 新增有硬预算的 particulate material/carrier 定义、按 stable ID 排序的 canonical registry 与 SHA-256 fingerprint；
- 新增 `2^27 SVQ/BV` 的 exact cell-volume 换算和向下 container nanos 换算；
- 新增固定上限 primitive SoA active parcel、稀疏 canonical deposit groups 与 skeleton/bulk capacity 分离；
- parcel split/merge 与 parcel↔deposit/wake 保持 SVQ 和独立质量，预算/第九组/bulk overflow 拒绝均为零残留；
- `CpuValidationSuite` 只增加一个 `granular` 领域并纳入 fast/milestone，不增加 Gradle task。

最近门 `runCpuValidation -PcpuValidation=granular` 通过：full 场景包含 10,000 次 parcel↔deposit 往返，runner
0.080 秒、granular 本体 0.068 秒。此增量未接 solver、Minecraft、存档、容器或协议，因此未累计其他 selector。

## M8.1b mechanics 与 coupling core（2026-08-24）

- Schiller--Naumann/Stokes 极限的半隐式 velocity relaxation 已进入纯 granular core；浮力使用实际 parcel mass/
  skeleton volume，当地 carrier 密度/黏度来自显式 sample；hydrodynamic 反冲量由环境接口接收。
- `GranularCouplingFields` 将 parcel exact SVQ 以 trilinear weight + deterministic largest remainder 分配到最多
  8 格，并合入 deposit skeleton；每次 rebuild 独立核对 occupancy 与物理库存精确相等。
- moving-solid target、effective cell capacity 与对称保守 face void 已形成 typed coupling API；尚未接入压力路径。

最近门 `runCpuValidation -PcpuValidation=granular` 再次通过：heavy/light 终速匹配 root oracle、neutral horizontal
drag 的 parcel+fluid 动量闭合、parcel 跨格前后 `sum(ΔS)=0`；full granular 本体 0.080 秒。仍未触碰共享 fluid
路径，所以未追加旧 selector。

## M8.2a CPU solver 联合几何（2026-08-24）

- `FlipFluidDomain` 可显式绑定 granular+liquid registry；0.5 m cell/SVQ、carrier palette 和一次绑定身份均严格校验，
  无服务入口时不会自动创建颗粒状态。
- 每个 substep 在 P2G 后执行 buoyancy/drag 与等量 MAC 反冲量，granular 最大速度进入同一 CFL；独立小半径
  `FlipGranularCollision` 先生成 CCD preview，pressure 成功后才提交对应位置。
- current/preview 两代 `GranularCouplingFields` 分别提供 exact occupancy；free-surface 使用 current capacity，pressure
  RHS/matrix/preconditioner/projection 使用 preview 的同一对称 face void，moving-solid target 使用 exact `ΔSVQ/dt`。
- water/material/chemical insert capacity、surface-tension fraction 和 volume drift 统一扣 skeleton；runtime gas scanner 的
  available volume 同样扣 skeleton，但 topology/库存格式不变。
- full sealed region 的外部 solid insert 在修改前拒绝；有空气的 fixture 步进不改变一枚 liquid marker volume、solid
  SVQ 或独立 mass。

最近门先跑 `granular` 通过（80 个集成 solver steps，0.318 秒）；因实际修改共享 pressure/cut-cell/air 路径，随后用
`--rerun-tasks` 跑 `granular,free-surface,cut-cell,gas-liquid`，4 域 runner 47.232 秒全部通过，其中长期 gas-liquid
44.247 秒。gate-off 的既有 free-surface/cut-cell/gas-liquid 结果未回退。

## M8.3 低成本沉积、休眠与局部唤醒（2026-08-24）

- granular collision preview 现同时处理 domain/cut-cell/deposit bulk surface；支撑标志与连续接触年龄仍只在对应
  pressure 成功后提交，连续 3 个低速支撑 substep 后才允许 parcel 原子转为 deposit。
- 新增有界 dirty surface-column scheduler；安息角在合法八邻域中按最大超坡量搬运 exact SVQ，对角只有至少一条
  两段开放 face path 时成立。每 column 每 logical step 至多处理一次，方向同值时按时空确定性轮转，避免固定轴偏置。
- 连续 20 个 logical step 无坡面移动或 wake 的 column 退出 dirty queue；诊断才扫描 sleep 比例，常态 step 不扫描
  sleeping deposit。block/cut-cell 变化只扰动实际 column 与一格邻域，覆盖 granular inventory 的碰撞编辑会在改动前拒绝。
- exposed group 的 Shields 超阈值 wake 受 `erosionRateSvqPerSecond`、材料 parcel 上限和全局 operation/parcel 预算限制；
  亚临界检查不移动库存，未具备 composition-aware split 的 reactive partial group 保持 deposit，不猜测拆分。
- deposit porosity 用各 material skeleton SVQ 重新派生，bulk 只用于形态；液体压力容量仍只扣 skeleton。沉积格 face 使用
  frozen Kozeny--Carman/Darcy 半隐式阻尼，双侧 permeability 采用对称 harmonic closure。

同一 `granular` selector 新增支撑沉积、八邻域 repose、20-step sleep、亚临界 no-creep、超临界局部 wake 及全程
SVQ/mass 守恒断言，没有新增常态 gate。因碰撞和 free-surface/cut-cell 邻接路径被修改，最近门用 `--rerun-tasks`
运行 `granular,cut-cell,free-surface`，3 域 runner 2.878 秒全部通过。

## M8.4 世界 registry、配置 v5 与 `region-state v5`（2026-08-24）

- server config 升为 v5，新增 granular/precipitation 重启门及 server parcel/deposit-group 硬预算；旧 v1--v4
  reader 明确关闭未知新门。依赖链为 granular→materials、precipitation→granular+chemistry，不能通过关掉依赖绕过。
- 首次启用发布 `<world>/realwater/granular/registry.nbt`，之后 world snapshot 的 stable ID、物性、carrier 与 fingerprint
  权威。首次定义来自 strict `data/*/realwater/particulate_materials` 与 `granular_carriers`，物理数值要求十进制字符串；
  当前 data pack 与已存在 snapshot 不同只告警而不重解释库存。snapshot 丢失而 v5/不可读 region 已存在时锁定，禁止拿
  当前 definitions 猜测重建。
- `region-state v4` 继续为气体持久化评审预留并严格拒绝；颗粒使用 v5 的完整 generation，而不是向 v3 附加 sidecar。
  manifest/section required typed extension 保存 active parcel 与 deposit group 的 exact SVQ、raw mass、材料 palette、运动学、
  porosity 和 composition reference，并在 section/material/global 三层复核独立账本。
- v1/v2/v3 在 granular gate 首次恢复时写入 inactive slot 的完整 v5、读回验证后才切 HEAD；gate/registry/身份/能力位/
  unit/账本未知均保留 committed generation 并 LOCKED。v1/v2/v3 canonical bytes 和既有 reader 未改写。
- 本增量暂不允许非零 reactive solid composition 或 precipitation capability 落盘；它们等 M8 chemistry bridge 绑定
  固相组成 registry 后再扩充同一 frozen v5 typed payload，避免用 runtime handle 或当前数据包含义占位。

最近门 `runCpuValidation -PcpuValidation=region-state --rerun-tasks` 通过，覆盖 config v5、非空 active+deposit v5
codec/NBT/disk round-trip、reserved-v4 与缺 extension 拒绝、v1/v2/v3→v5 原子迁移及 granular-off 保留锁定；runner
2.795 秒。该增量没有修改 solver 路径，因此不累计 solver selectors。

data-pack loader 接入后复跑 `granular,region-state --rerun-tasks`，bundled definitions/fingerprint、snapshot first publish、
definition mismatch 保留及 unknown store content 锁定均通过；两域 runner 3.159 秒。

## M8.5 固液规则与化学 registry 依赖（2026-08-24）

- granular registry snapshot v2 现一并冻结 `solid_liquid_reactions`、canonical rule 顺序及依赖的 world chemical
  registry fingerprint；同 phase 竞争消费、未知 phase/species/material、inert target、非 exact-species 守恒、负容量或
  超预算在首次发布前拒绝。
- granular gate 单独开启也会先原子冻结 chemical registry definitions 作为规则身份依赖，但不会分配 chemical runtime
  state 或运行 M7 reaction；以后启用 precipitation 不能拿新的 datapack 化学含义解释旧 granular snapshot。
- bundled 首条规则是 aqueous Na+/Cl- ↔ reactive precipitate 的可逆、单目标饱和规则。本 checkpoint 只冻结定义与
  identity，尚未修改 liquid/solid inventory；相转移要等 `SolidCompositionRecord` 和原子 plan/commit 接入。

最近 `granular --rerun-tasks` 通过，包含 rule codec、bundled fingerprint 与 registry snapshot round-trip；runner
1.412 秒。

- 独立 `SolidCompositionRecord`/primitive pool 已接入：每个 reactive active/deposit inventory 拥有唯一 bounded handle，
  indicator 与 liquid phase 不进入固相；inert handle 固定为 0。v5 把 runtime handle 改写为 canonical stable-species table，
  并保存/复核 chemical fingerprint、solid species totals、charge 可导出关系和 raw mass residual。
- reactive composition 的 active/deposit mass 必须与 exact species mass+residual 闭合；缺 chemical gate/registry、孤儿或
  复用 handle、table/ledger 不规范一律拒绝。关闭 precipitation 后仍可用 chemistry+granular 机械恢复已存在 reactive solid。

复跑 `granular,region-state --rerun-tasks`，新增非空 reactive active parcel 的 v5 codec/磁盘 readback，inert v5 与旧迁移
未回退；两域 runner 3.146 秒。

## M8.6 同格固液相转移（2026-08-24）

- 新增纯 saturation planner：只在同 cell、同 chemical phase 内按冻结规则计算一次 signed extent；aqueous species、
  reactive solid composition、solid SVQ、marker volume 与双方独立 raw mass 同时生成替换 plan，硬预算或不可表示拆分在
  commit 前拒绝。
- precipitation 生成有唯一 composition handle 的 active parcel；reversible dissolution 只消费一个确定的可接触 active
  parcel 或 exposed deposit group，partial solid split 同时拆 species/SVQ/mass，普通 inventory API 仍拒绝无 composition
  plan 的 reactive partial move。
- M7 aqueous reaction 每 logical step 仍只运行一次；固液相转移随后只消费 chemical dirty cells。反应性 parcel 跨格，
  以及 settle/repose/wake 改变固体所在格时，只把旧/新局部格重新标 dirty；sleeping deposit 不做全域轮询。
- `precipitationEnabled` 已贯穿新 controller、v5 restore/migration 与 checkpoint 前绑定；规则/registry/chemistry/granular
  任一依赖缺失时不能开启。预算或 representation block 会暂停该 region，当前 plan 保持未提交，之前已完成的 cell
  transfer 保持为已提交真值，避免错误声称整 logical step 回滚。
- gate fixture 在满液格用 Na+/Cl- 触发 4096 SVQ surface cap 的沉淀，再换成欠饱和载液完整溶解；两次均核对
  aqueous+solid species、独立 mass、liquid+solid volume 守恒，并确认 indicator provenance 不随相转移扩散。

最近门 `runCpuValidation -PcpuValidation=granular,chemistry,region-state --rerun-tasks` 全部通过：granular 1.097 秒、
chemistry 1.200 秒、region-state 2.300 秒，runner 合计 4.602 秒。下一增量才进入 dry granular container 与独立
container-transfer WAL v4；不会把固体塞入现有 liquid/chemical WAL v3。

## M8.7 干式颗粒容器与跨域 WAL v4（2026-08-24）

- `GranularContents v1` 以 stable material group 保存 exact SVQ、raw mass、optional stable-species composition、
  SVQ scale 与 granular registry fingerprint；最多 16 组/64 个组成项/64 KiB，和全部液体组件严格互斥。tooltip 与
  durability bar 只读该账本，空容器没有隐藏零值组件。
- solver 侧新增 stable-ledger 的 all-or-nothing dry insertion 与有界 extraction：注入会按材料 parcel 上限切分并在
  commit 前检查组合池/parcel/全域容量；舀取只取附近 active parcel 与每格 exposed deposit group，不取深层组，且在
  删除库存前由 item adapter 验证可表示性。reactive partial split 不可精确时保留原库存。
- particulate external-source/escaped-sink audit 作为独立 stable-ID 累计真值进入 v5 granular manifest；manifest payload
  内部版本 2 可读旧版本 1，旧存档补空 audit，不从现存库存猜历史。创造放出才增加 source；普通 item↔region 转移不
  制造 source/sink。
- container-transfer WAL 升为 v4：resource kind 明确区分 `FLUID_NANOS` 与 `GRANULAR_SVQ`，item fingerprint 保存独立
  solid SVQ 和完整 granular contents；PREPARED/实际结算/checksum/HEAD/actor durable-save 顺序复用同一事务路径。
  v1/v2/v3 仍按原液体格式读取；未知门、registry、fingerprint、组成或 actor 状态继续保留并 LOCKED。
- 玩家与发射器入口只允许向实际命中的闭壳 controller 区域转移颗粒，单次 count 必须为 1；空干式容器沿准星命中的
  真实颗粒格舀取，装有颗粒的容器沿目标方块端口注入。command/demo 区域不接受颗粒容器。

最近门 `runCpuValidation -PcpuValidation=granular,containers,region-state --rerun-tasks` 全部通过：granular 1.225 秒、
containers 0.437 秒、region-state 1.851 秒，runner 合计 3.516 秒。下一增量进入 protocol v4 的有界只读 display
snapshot、客户端颗粒绘制与管理员诊断；exact SVQ/mass/species 不上链。

## M8.8 protocol v4、只读颗粒展示与管理员诊断（2026-08-24）

- protocol v4 只在 granular gate 启用时严格协商，并要求 material+granular capability；chemistry 同时启用时还要求
  chemical capability 且每帧必须携带对应展示层。v1/v2/v3 payload/type/reader 保持独立，不用旧版本伪装降级。
- server display snapshot 只包含 canonical 颗粒颜色 palette、逐格 8-bit bulk fill/porosity/top-material 与最多 4096 个
  确定性抽样 active parcel；位置为 16-bit、直径为 8-bit。exact SVQ、raw mass、solid species/composition 和 runtime
  handle 均不进入 payload，客户端只能重建表面和抽样小颗粒。
- v4 ledger 对 dimension/region/generation/sequence、granular/chemical registry fingerprint、palette、tombstone 与
  clear-all 独立门控；默认 24×16×24（9216 格）含化学层的完整 v4 packet 仍低于 512 KiB。
- 客户端在现有 material surface 后绘制 porosity 着色的 deposit 外表面及 active samples，不反写 solver。管理员
  `granstatus` 从服务端 exact v5 snapshot 输出总/逐材料 SVQ、mass、固相 species 及 external-source/escaped-sink 审计；
  `granular`/`precipitation` 命令只改下次重启配置并明确显示 live/configured 差异。

最近门 `runCpuValidation -PcpuValidation=protocol,granular --offline --rerun-tasks` 全部通过：protocol 1.147 秒、
granular 0.434 秒，runner 合计 1.584 秒；服务端、客户端与测试 source set 同时编译通过。下一步审计 M8 完成门，
只把自动门无法观察的视觉、真实 controller 转移和重启恢复收敛成一次实机验收。

## M8.9 孔隙静水与空门性能完成门（2026-08-24）

- 新增真实 solver porous-bed fixture：底层每格 1/4 cell skeleton、40% porosity，孔隙水仍由普通 marker 拥有；
  200 步内相邻压力收敛到 `rho*g*h`，最大 marker speed 低于 `3e-4 m/s`，solid SVQ/mass 不变。
- fixture 发现原 porous damping 在 gravity 之后执行会永久削低静水压力。现改为只半隐式阻尼上一完整代速度，再施加
  gravity/surface force 和共享 pressure projection；因此零速床保留完整静水压力，pressure 后仍不暴露散度半步。
- 空 granular inventory 不再重复构建 occupancy、carrier、deposit 或 granular air-volume 路径；coupling total 使用
  checked cached total，库存归零时显式清空。24×16×24、两层静水的同进程交错中位数为 baseline 9.340 ms、
  granular gate-on/empty 9.348 ms（+0.1%），液体体积和最大速度逐位一致；门冻结为 5% 或 0.5 ms 计时噪声包络，
  且绝不越过既有 40 ms pacing。未修改 `REDUCED`/`PAUSED` 阈值。
- 生产低成本模型现已通过 terminal velocity、两向动量、exact occupancy/moving source、统一 pressure、孔隙静水、
  八邻域安息角、no-creep、局部 wake 与至少 80% stable-column sleep；没有同时失败的形态/孔压/再悬浮项，MPM
  评审停止条件未触发。

因本修正触及 pressure 顺序与 runtime gas available-volume 选择，最近门使用 `--rerun-tasks` 运行
`granular,free-surface,cut-cell,gas-liquid`：四域分别 1.950/1.953/0.656/46.270 秒，runner 合计 50.829 秒，全部通过。
剩余 M8 关闭项只有自动门无法证明的客户端颗粒可见性、真实 controller 干容器转移及实机重启恢复。

为把上述人工项收敛到一个真实 controller 场景，新增 `/realwater granularcontainer
<empty|fine_sand|light_beads>`：它只从已冻结的 world registry 生成惰性 canonical `GranularContents v1`，满载 1 BV
skeleton，并在命令反馈中明确标作 creative/debug 外部源；反应性 material 被拒绝，不能借命令伪造 solid composition。
命令层编译和完整 `containers` selector 通过（1.391 秒）。

## M8.10 0.14.0 实机候选（2026-08-24）

- 主 Mod 版本升为 0.14.0。完整相关门以 `--rerun-tasks` 运行 `granular,chemistry,region-state,containers,protocol`：
  五域分别 2.114/1.230/2.367/0.279/0.182 秒，runner 合计 6.172 秒，全部通过；同轮代表性空门 benchmark
  为 baseline 10.238 ms、granular gate-on/empty 10.308 ms（+0.7%）。
- `milestone` 的 20 个短域全部通过，runner 合计 8.840 秒；其独立空门 benchmark 为 baseline 10.367 ms、
  granular gate-on/empty 10.292 ms（-0.7%）。未因候选发布修改 pacing 或自动暂停阈值。
- JDK 26.0.2 下执行 `clean assemble compileTestJava --offline --no-daemon` 成功（20 秒）；精确根任务
  `:runServer --offline` 以 Java 25 target 加载 Minecraft 26.2、Fabric Loader 0.19.3 和 realwater 0.14.0，进入
  `Done` 后接受 `stop` 并正常保存（21 秒）。
- 候选 JAR 已部署为 `realwater-0.14.0.jar`，SHA-256 为
  `3B5ADB61833DC210D7B437A4119CB4B79E0181B7A67FCDA3C2C588E6CDF26EE2`；同一 Mod id 只有这一份启用，
  旧 `realwater-0.13.0.jar` 已可恢复地改名为 `.disabled`。独立只读 GPU probe 保持 0.1.16。

M8 尚不在完成索引中：最后只等待一个真实 controller 合并场景确认客户端颗粒可见性、玩家/发射器干容器事务、
反应沉淀和正常重启后的 v5 恢复。通过后再关闭路线图 M8 并将 0.14.0 从实机候选记为已验收基线。

## M8.11 实机候选沉淀崩溃修正（2026-08-24）

- 首次合并实机场景在细沙与 NaCl 已分别完成 v4 容器事务后，`run` 的第二个左右 logical step 触发
  `SolidVolume.fromCellVolumeExact`：相转移 adapter 错把输运/reseeding 后的任意 `float marker volume` 当成必然对齐
  整数 SVQ，未对齐时 `IllegalArgumentException` 逃出 server tick。暂停时看到的少量悬浮细沙是尚未步进的 active
  parcel，不是本次异常原因。
- 修正保留 M7 marker volume 原始 dyadic 尾数：饱和容量直接从精确 IEEE-754 dyadic 和计算，只按体积权重分配本次
  solid SVQ delta；每个新 marker volume 必须精确等于旧值加减该整数 SVQ。绝对 liquid volume 不再先量化后重写，
  species、mass、solid SVQ 和 liquid-volume delta 的既有原子 plan/commit 不变。
- 原有沉淀→溶解 fixture 改用 `0.1F` 非 SVQ 对齐 marker，直接穿过生产 `FlipFluidDomain.step()`；没有新增 selector
  或 Gradle task。完整 `granular,chemistry --rerun-tasks` 通过，分别 1.910/0.987 秒，runner 合计 2.897 秒。
- 崩溃世界的两个容器 WAL 均已收敛且 `container-transfer` 目录为空；停服路径在 05:19:59 成功发布新的 v5 HEAD，
  因此保留该世界并按正常严格 reader 恢复，不手删或猜测修复任何状态。
- 修正后 `clean assemble compileTestJava --offline --no-daemon` 成功（15 秒），根 `:runServer` 加载 0.14.0 到 `Done`
  并正常 `stop`。崩溃世界的只读副本随后作为独立 dedicated universe 启动：严格 reader 接受原 region UUID、v5 HEAD、
  registry 和空 WAL，没有 `LOCKED`；无玩家时只按既有完整 chunk 门暂停。原世界 HEAD SHA-256 前后均为
  `79DE83CCC696BFA997A0C72E3B11645269AF0894BB094A6826866F336476715C`，烟测未改原件。
- 新候选 JAR 已部署，SHA-256 为 `0B2008122E141FAC4B1236C15DEE83C04170B46C287859AF78B8E66F9FAE9DED`；
  崩溃版候选可恢复地保留为 `realwater-0.14.0-pre-svq-fix.jar.disabled`，同 Mod id 仍只有一个启用 JAR。

## M8.12 实机颗粒形态与沉积 canonical 修正（2026-08-24）

- SVQ 修正版在原世界继续运行时不再 tick crash，玩家与发射器颗粒容器往返及各物质数量符合预期；但两张运行后
  截图与 05:41 的 `granstatus` 显示 1 BV 细沙和沉淀仍全部为 active（4824 parcels、depositGroups 0）。细沙实际被
  切成 512 个最大 parcel，却每 64 个重叠在同一 solver-cell 格心，因而客户端只看见约 8 个黄色大点锁步下落；
  precipitation 同样逐步在格心创建静止 4096 SVQ 白点。
- 05:46 后第二段运行开始形成 reactive deposit；退出时 checkpoint 捕获到同格多个 runtime composition handle 的
  内容规范化为同一 stable composition，因重复 canonical key 拒绝发布。日志明确保留 checkpoint 9 为上一权威代次，
  没有覆盖 HEAD；这不是可以在 codec 层去重掩盖的显示问题。
- reactive parcel→deposit 事务现按单位 SVQ 的 exact species 比例识别相容组，合并 absolute composition/SVQ/mass 并
  释放被并入 handle；checkpoint 因此只会看到一个 canonical group。相同规则也用于 bounded active compaction，旧世界
  已存在的数千小沉淀可在 operation budget 内逐步合并，而不是必须重建或丢弃。
- dry insert 使用 collision-valid 的格内低差异位置；容量 plan 复用生产 occupancy 的 8 格 largest-remainder 权重，
  不因打散位置放宽 skeleton capacity。precipitation 从实际扣体积的 carrier marker 邻域出生并继承局部速度；同格、
  同组成、近速度且未达材料上限时直接守恒 merge。active display glyph 只缩小到可读上限，deposit 提高不透明度；
  exact 体积、质量、碰撞半径、bulk 高度和液体 mesh 均未由显示反写。
- `granular` selector 增补三个已有失败域内断言：dry insert 至少 32 个不同量化位置、连续 precipitation 合并仍闭合
  aqueous+solid truth、active/deposit reactive merge 后 composition handle 唯一且 v5 capture canonical。没有新增
  selector 或 Gradle task。液面黑三角仍按既有独立视觉债处理，本增量不修改 liquid mesher。
- 完整受影响门 `granular,chemistry,region-state,containers,protocol` 分别用时 1.847/0.984/1.940/0.191/0.157 秒，
  runner 合计 5.119 秒；随后 `clean assemble compileTestJava` 成功（16 秒），根 `:runServer` 加载 0.14.0 到 `Done`
  并正常 `stop`。新实机候选 SHA-256 为
  `9B9523413BF48C6251AEB100BA5E2B5DC227434308D99CAF58B0F1264B1B5146`；上一 SVQ 修正版可恢复地保存为
  `realwater-0.14.0-pre-morphology-fix.jar.disabled`，其 hash 仍为
  `0B2008122E141FAC4B1236C15DEE83C04170B46C287859AF78B8E66F9FAE9DED`，mods 目录只有一个主 Mod JAR 启用。

## M8.13 整桶局部拥塞与联合排水修正（2026-08-24）

- 形态候选把两桶细沙打散后，实机仍在下落数帧后把全部散点同时卡住；舀走一部分会暂时恢复几帧。生产代码确认
  任一 `S_next` 格超出 cut-cell 容量时会回退整个 granular preview，并把全域 active parcel 速度清零。旧门只分别
  覆盖了整桶初始位置不重叠和单 parcel solver 推进，没有覆盖整桶跨格时局部拥塞，因而漏检。
- preview 现从当前 exact occupancy 逐 parcel 做 remove/try-add；失败只保留该 parcel 并形成局部向下接触。解析在
  131,072-operation 预算内交替顺序重试，使下层腾出的空间能在同一 substep 向上游传播，不再用全域冻结保护守恒。
- 新整桶 fixture 随后暴露第二层错误：同一步有 217 个 parcel 转为 deposit 时，旧 settlement 只检查 deposit 自身的
  bulk/几何容量，忽略其余 active parcel 的 8 格插值排水，下一步出现单格 `21,996,260 > 16,777,216 SVQ`。
  active compaction、settlement、八邻域 relaxation 与 wake 现全部在提交前更新同一份 active+deposit 联合 exact
  occupancy plan；失败原子恢复原计划，提交后仍重建并验证几何容量。
- `granular` selector 沿用已有任务增加两桶、1024 parcel、40-step 动态回归：必须在同一步实际出现局部 held 和继续
  moving，两者不得退化成全域静止；active 未结算时不得连续六步状态不变，且每步 total SVQ/mass 精确闭合。完整
  granular 门通过，runner 2.209 秒；没有增加 selector 或长期常态测试。
- 截图中的黄色“花团”是同一小区域内几十个原 4.7 cm display cube 相互遮挡后的轮廓，不是单个物理 parcel；只读
  active glyph 上限缩到约 2 cm，使整桶代表 parcel 呈点云。自由落体中同材质 parcel 受同一重力而近似同步仍属低成本
  模型边界；glyph 不添加假位移，也不反写 exact volume、碰撞半径或沉积形态。
