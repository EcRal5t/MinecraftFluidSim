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
