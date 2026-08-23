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
