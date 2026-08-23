# M8 conservative granular state

本文冻结 M8 颗粒沉淀与低成本沉积的物理真值、数值耦合、休眠、相转移、预算和跨层版本边界。活动
CPU 求解器仍以 [`fluid-solver.md`](fluid-solver.md) 为规范；液体 marker 体积、逐材料质量、M7 化学库存、
气体库存与 M8 固体库存分别结算。

## 范围与功能门

- M8 使用“活动 granular parcel + 守恒 deposit cell”低成本模型。活动 parcel 负责浮沉和输运，稳定库存
  聚合成稀疏沉积格；不实现 DEM、支撑链、通用刚体、弹性体、黏性土或生产 MPM。
- `granularEnabled` 是独立启动门，但要求 `materialsEnabled=true`；`precipitationEnabled` 又要求
  `granularEnabled && chemistryEnabled`。旧 config reader 强制两个新门关闭。
- 门关闭时不分配颗粒数组、沉积池或耦合场，活动 M0--M7 路径保持逐位退化。发现含颗粒的新版状态时保留并
  `LOCKED`，不降级或删除。
- 颗粒只属于显式 controller 区域，不接管原版沙/砾石方块，不扩张 ownership 扫描，也不接管河床或开放世界。
- M8 不持久化 M5 runtime-only 气体，不改 40/200 ms wall-time pacing，不修液面黑三角、液液锐化或法线平滑。

## 术语和物理真值

- **grain** 是材料定义中的真实代表粒径，只参与阻力、渗透率和侵蚀门。
- **granular parcel** 是可代表许多 grain 的活动数值包，不是一颗真实沙粒。
- **solid skeleton volume** 是实际排开液体和气体的体积，也是固体体积真值。
- **bulk deposit volume** 是 `skeleton / (1-porosity)` 的派生形态体积，包含孔隙，不能再次全部排水。
- **pore water** 仍由普通液体 marker 拥有并进入现有压力投影；deposit 不保存第二套水库存。

M8 新增并分别闭合：

1. 活动 parcel 与 deposit 的逐材料 skeleton volume；
2. 活动 parcel 与 deposit 的逐材料独立 raw `double` mass；
3. reactive solid 的 species、组成基元与电荷；
4. particulate external-source 与 escaped-sink audit。

碰撞半径、平滑 occupancy、face void fraction、surface height、bulk volume、渗透率、颜色和客户端 mesh 都是
派生状态。`ParticulateMaterial.referenceDensity` 只用于创建和校验；任何已存质量不得由体积乘当前密度重建。

## 单位

当前生产分辨率固定 `cellSize=0.5 m`。M8 固体体积使用 checked `long` 二进制量子：

```text
SVQ_PER_BV   = 2^27 = 134,217,728
SVQ_PER_CELL = 2^24 = 16,777,216
1 SVQ        = 2^-24 solver-cell volume
```

该单位使 parcel/deposit 迁移、占据分配和固液相转移严格回和，并能精确表示为当前 `float particleVolume` 的
二进制增量。世界流体容器继续使用 `1 BV = 1,000,000,000` 纳体积量子；两种单位不共用字段。未来改变 solver
分辨率必须先评审存档单位迁移。

deposit 每格保存 `0 < porosityQ < 1,000,000`。孔隙率参与 bulk 形态与渗透率，但 skeleton SVQ 独自决定液体
容量：

```text
geometric open volume O_i
solid skeleton volume S_i
liquid/gas void capacity C_i = O_i - S_i
0 <= S_i <= O_i
```

## 数据定义和权威 registry

数据路径固定为：

```text
data/<namespace>/realwater/particulate_materials/*.json
data/<namespace>/realwater/granular_carriers/*.json
data/<namespace>/realwater/solid_liquid_reactions/*.json
```

particulate material 至少定义 stable ID、参考密度、grain diameter、rest porosity、安息角、静/动摩擦、
restitution、critical Shields、侵蚀速率上限、最大活动 parcel SVQ 和显示属性。carrier 按稳定液体 material ID
定义动力黏度与 `schiller_naumann_v1`；实际浮力密度仍从当地 marker mass/volume 得出，缺 carrier 时不猜水黏度。

solid-liquid rule 定义 liquid chemical phase、aqueous stoichiometry、solid material/composition、饱和容量、
每 extent 的 solid SVQ、接触上限和可逆性。loader 复用 M7 的有界 exact-rational 语义并在发布前拒绝未知引用、
竞争消费者、循环、负数、算术溢出以及质量/组成/电荷/声明体积不守恒。

首次开启颗粒门时原子发布 `<world>/realwater/granular/registry.nbt`。canonical snapshot/fingerprint 是已有固体
库存的唯一解释，并冻结单位 scale、材料、carrier、规则及其依赖的 M7 registry fingerprint。数据包变化只报告
mismatch，不重解释存量；unknown/corrupt/missing/identity mismatch 全部保留并全局 `LOCKED`。

## 活动 parcel 与 deposit 库存

活动状态使用 primitive SoA：位置、速度、`long solidVolumeSvq`、raw `double mass`、material handle、solid
composition handle、contact age 和 flags。runtime handle 不上链。drag 使用 physical grain diameter；parcel 数值
聚合体积不能把许多细粒错误地变成一颗大球。

- split 按最大 parcel SVQ 确定性切分，末项取体积、质量和组成余数；merge 仅限同格、同材料、同组成且速度
  相近的 parcel，并保持质量、动量和质心。
- hard capacity 不足时 creation/phase transfer/container insert 在提交前整体拒绝，不能覆盖、删小项或强塞 deposit。
- inert solid composition handle 为 0；reactive solid 使用独立 bounded `SolidCompositionRecord`，不复用 M7
  `ChemicalMixtureRecord`，也不携带 indicator。

deposit 使用 cell→entry-head 的 primitive 稀疏池。每格保存 porosityQ 和按 material/composition canonical 排序的
组；单格最多 8 组。相同组必须合并，第 9 组使整个 plan 拒绝。没有液体 marker 但有 deposit 的格和 section 仍是
有效库存。

parcel↔deposit、wake、坡面移动和相转移均使用 plan/commit。成功时逐材料 SVQ、mass、composition 一次移动；
失败时两端逐位不变。

## 浮力、阻力、碰撞和双向动量

含液 marker 的格从最近实际 marker（ordinal 破同距）选择 liquid material/chemical phase；共享 MAC 提供流速，
当地 phase marker 的实际 mass/volume 提供密度，exact marker/PLIC 几何给出浸没比例。无 marker 格按空气处理，
不从平滑 density 尾巴产生浮力。

首版阻力为 Schiller--Naumann：

```text
Re = rho_f d |u-v| / mu
C_D = 24/Re (1 + 0.15 Re^0.687), Re < 1000
C_D = 0.44,                         Re >= 1000
F_drag = 0.5 C_D rho_f A_aggregate |u-v| (u-v)
F_buoyancy = -rho_f V_submerged g
```

`Re -> 0` 使用 Stokes 极限。阻力用解析或半隐式 relaxation，不能让 slip 翻号；等量反向冲量按同一确定性
归一 stencil 散回有质量的 MAC faces。无墙、无重力 fixture 中 fluid+parcel 总动量必须闭合。世界/deposit 碰撞
使用独立半径的 sweep/CCD，只截断法向并按材料摩擦/restitution 更新切向；边界冲量单独报告。

液体和 granular 最大速度共同决定现有 CFL substep 数，颗粒不能使用会穿墙的更粗时间步。

## 精确排水和统一压力几何

每个 parcel 的 exact SVQ 以定点权重 + largest remainder 分配到最多 8 格，deposit SVQ 直接归格。该 occupancy
field 每次严格回和到物理库存，但自身只是可重建数值离散。

每个 substep 预测 collision-resolved `S_next`，不先提交位置。moving-solid 容量源为：

```text
targetDivergence_i += -(S_next_i - S_old_i) / dt
```

内部运动时全域 `sum(S_next-S_old)=0`。`GranularCouplingFields` 是 pressure RHS、matrix、preconditioner、projection、
PLIC/free-surface、volume drift、插入容量和 air available volume 的唯一固体容量输入。face void 由相邻 cell void
fraction 对称派生，以上路径共享同一 face coefficient。禁止只改 drift、渲染或插入容量来伪造排水。

外部 solid insert 先检查目标和全域容量。sealed incompressible 满域拒绝且零残留；有自由空间时压力源移动等量水，
不修改液体 marker 总账。固液 precipitation 同时减少等量可表示液体 volume、增加 exact solid SVQ，所以总占据
体积不变。runtime gas `V_avail` 扣 skeleton volume，但 M8 不持久化 gas node/inventory。

deposit skeleton 扣容量，bulk porosity 只形成 Darcy 型 face damping：

```text
K = d^2 porosity^3 / (180 (1-porosity)^2)
u* = u / (1 + dt mu / (rho K))
```

混合 permeability 用有界对称 harmonic closure。孔隙 face 不归零，`markerCount > 0` 仍决定压力流体格。

## 沉积、安息角、休眠与局部唤醒

parcel 只有在接触 support、法向速度/动能连续低于材料门、局部 capacity/group/queue 预算可满足且坡面 plan 合法时
才原子转为 deposit。失败时保持活动 parcel。

安息角只在局部 dirty surface 上做守恒松弛。邻域包含四个水平 face，以及通过两段开放 face path 校验的四个对角；
对角距离为 `sqrt(2)h`，不得穿 closed corner。超过 `distance*tan(reposeAngle)` 的 bulk 高差向下换算成可移动
solid SVQ，受 exposed inventory、receiver capacity、材料 relaxation rate 和 logical-step operation budget 限制。
多材料按 exact solid-volume largest remainder 移动；未完成超坡格保留 dirty，不用方向 bias、吸附、支撑链或找平。

deposit 是零动量宏观休眠表示。连续 20 个 logical step 无坡面转移、parcel 接触、超阈值剪切、support change 或
solid-liquid reaction 后退出 dirty queue。区域 hydrostatic sleep 还要求无活动 parcel、无 dirty deposit、无 pending
granular chemistry；不能清零活动速度冒充休眠。

只检查 exposed surface 的局部 wake：

```text
tau_b = mu |du_t/dn|
Shields = tau_b / ((rho_s-rho_f) g d)
```

超过材料 critical Shields 时按 erosion cap 精确取出 exposed SVQ 并聚合为有限 parcel。block/cut-cell 变化、parcel
撞击、容器和 reaction 只标实际格及 1-cell support/slope 邻域。每 logical step 消费固定 dirty/operation 预算；余项
留队，客户端观察绝不唤醒。

## M7 与 M8 固液相转移

M7 dissolved reaction 每 external step 仍只运行一次；随后 M8 针对 `(cell, chemical phase, rule)` 计算一次确定性
饱和目标，再做 solid-liquid plan/commit。新溶出 species 只标 M7 dirty，下一 external step 才参与 aqueous reaction。

precipitation plan 同时：扣 aqueous species 与 exact mass、保留 marker residual、扣可表示 liquid marker volume、增加
solid SVQ/mass/composition。dissolution 严格反向且只消费同格 active parcel 或 exposed deposit；深层组、邻格、墙后、
不同 chemical phase 不参与。indicator 不进入 solid，溶出 species 继承承载液体 marker 自身 provenance。

液体 marker 的绝对 `float volume` 仍是 M0–M7 的任意正 IEEE-754 dyadic 真值，不因启用 M8 而量化成整数 SVQ。
饱和容量从同格 marker 的精确 dyadic 和计算；plan 只向各 marker 加减整数 SVQ，并要求该 delta 在原 float 上精确可表示，
因此原有亚 SVQ 尾数不变。不能表示或会使 marker 非正时整份 plan 拒绝并安全暂停，禁止把绝对 liquid volume 向上/向下
取整后重写 marker。

每次提交前后验证：

```text
aqueous + solid species/composition/charge  exact long 相等
liquid marker mass + active/deposit mass    容差内相等
liquid volume delta in SVQ + solid delta    exact long 为 0
```

任一 marker positivity、pool、parcel、group、capacity、算术或 operation 预算失败时双方不变并进入可读的
granular/precipitation-blocked PAUSED；内部相转移不记 external source/sink。

## 每步顺序与完整提交

每个 CFL substep：液体 P2G/phase mass → 当前 granular coupling field → runtime air → granular force及反冲量 →
collision preview/next occupancy → 液体力与 porous damping → 共享几何 pressure → G2P/液体 advection → 提交同一
granular preview。pressure 或 postcondition 失败不得暴露液体新代与颗粒旧代的混合状态。

所有 substep 后：M7 reaction 一次 → M8 phase transfer 一次 → parcel/deposit/relax/wake → 液体 reseeding → 最终
ledger rebuild。`FlipFluidDomain` 只编排；registry、库存、mechanics、deposit、chemistry bridge 和 codec 保持独立职责。

## 跨层格式

- server config 使用 v5，新增两个门和 granular parcel/deposit 总预算；v1--v4 reader 强制新门关闭。
- `region-state v4` 继续保留给用户已冻结的“气体 runtime-only 状态持久化前独立评审”。M8 使用独立完整
  `region-state v5`；reader 按精确集合 `{1,2,3,5}` 分派，v4 在未评审/未实现时保持 unknown/LOCKED。
- v5 是 liquid/material/optional chemistry/granular 的单一完整 generation；不得使用 v3 HEAD 加颗粒 sidecar。
  双槽、section descriptor、回读验证、HEAD 最后发布和 quarantine 语义保持。v1/v2/v3→v5 只在 inactive slot
  产生完整空颗粒新代，旧 canonical bytes 不变。
- protocol v4 严格协商，只发送有界 display palette、deposit bulk fill/porosity display、Top material 和稳定采样的
  active parcel；不发送 exact SVQ、raw mass、species 或权威 porosity。
- 干颗粒容器 `GranularContents v1` 保存 stable material group、exact SVQ、raw mass、可选 solid composition 和
  registry fingerprint；不携孔隙水、液体、indicator 或 gas，且和 FluidMixture/ChemicalContents 表示互斥。
- container-transfer WAL v4 使用独立 solid SVQ/resource kind 字段，保持 PREPARED → solver → stage/verify v5 →
  REGION_COMMITTED(after/checksum) → HEAD → actor after → durable save → delete。v1/v2/v3 reader/recovery 不变。

任何 gate、registry、identity、checksum、generation 或 actor durable-save 歧义都保留状态/WAL 并 LOCKED；不能按
当前数据包、reference density、tooltip 或显示字段修复。

## 硬预算

| 项目 | 上限 |
|---|---:|
| particulate materials / carriers / rules | 256 / 512 / 512 |
| active parcels / region / default server | 16,384 / 65,536 |
| max parcel solid volume | 262,144 SVQ（1/64 cell） |
| deposit cells | region cell budget |
| groups / deposit cell / region | 8 / 65,536 |
| solid composition entries / record | 16 |
| dirty cells | region cell budget |
| granular / phase-transfer operations per logical step | 131,072 / 131,072 |
| protocol active display samples / region | 4,096 |
| default-region protocol snapshot | 512 KiB |

所有 count、length、乘法、pool、scan、BFS、reaction 和 allocation 预算在部分提交和大数组分配前验证。

## 低成本模型证据与 MPM 停止门

2026-08-24 的可删除 Java 隔离原型（固定 0.5 m cell）得到：

- Schiller--Naumann 终速：500/1000/2600 kg/m³ 在水中分别 `+0.033391/0/-0.076737 m/s`；半隐式积分与 root
  oracle 在打印精度内一致；
- 双向 drag fixture 的总动量前后均为 0；5,592,422 SVQ 跨两格分配及 moving source 精确回和；
- 40% porosity 时 4,194,304 skeleton SVQ 派生 6,990,506.667 bulk SVQ，但液体容量只扣 skeleton；
- 10,000 次 parcel↔deposit 往返后 1 BV 仍为 134,217,728 SVQ；
- 32° 坡面门的一维最大坡角为 32.000°；二维四邻域轴/对角误差 11.027°，加入合法对角后降至 3.930°，因此
  冻结八邻域但要求 closed-corner face-path 校验；
- 16,384 parcel、9,216 cell、65,536 group 的候选 primitive arrays 估算约 3.34 MiB/region，不含持久/显示暂存。

该原型只证明表示和候选 closure 可进入生产实现。生产 solver 的 terminal velocity、两向动量、统一 pressure、
孔隙静水、安息角朝向、no-creep、局部 wake、80% sleep 与 gate-on/empty wall-time 闭合证据记录在
[`../progress/m8-granular-deposition.md`](../progress/m8-granular-deposition.md)。

守恒失败属于实现/事务错误，不是升级 MPM 的理由。只有统一低成本模型完成一次记录过的参数/邻域校准后，仍无法
同时满足安息角朝向、动态坍塌、孔隙压力和局部再悬浮中的至少两项，且缩小材料 validity range/活动范围也不合理时，
才允许做隔离小域 MPM/Drucker--Prager 对照；不得预建生产 backend 框架。
