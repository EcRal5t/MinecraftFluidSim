# M8 颗粒沉淀与低成本沉积设计蓝图

## 0. 文档定位

本文用于指导另一个 AI 把主 Mod `0.13.0`、M0–M7 已关闭的基线推进到 M8。它是详细实施提案，不是已经生效的架构规范；每个增量开始前仍须以当前代码、`docs/architecture/` 和 `docs/maintenance/routing.md` 为准，不得为了贴合本提案回退后来已经冻结并验证的契约。

本文基于 2026-08-24 的提交 `befef4e`。基线事实如下：

- Minecraft Java 26.2 / Fabric，目标 Java 25；本机 JDK 为 `D:\ProgramFiles\BienCreng\jdk-26.0.2`，Gradle 默认 `--offline`。
- `FlipFluidDomain` 是唯一生产 CPU 求解核心；GPU probe 0.1.16 只读，不是生产后端。
- 液体 marker 体积、逐材料质量、M7 species/组成/电荷/mass residual、气体库存和容器库存都是互相独立的真值。
- controller ownership 使用空气 + 独立 registry；`region-state` HEAD 是物理状态唯一提交点。
- M7 只包含液相强电解质与瞬时计量反应，明确不包含沉淀。M7 的 region/protocol/container WAL v3 都已冻结，M8 不得向任何 v3 字节塞入颗粒字段。
- 正式开发域是 controller 闭壳区域，默认最多 4 个；`demo`/`empty` 只用于开发入口。

开始每轮前至少重读：

1. `docs/maintenance/routing.md`
2. `docs/architecture/fluid-solver.md`
3. `docs/architecture/chemistry-state.md`
4. `docs/architecture/world-ownership-and-containers.md`
5. `docs/architecture/region-state-v1.md`
6. `docs/roadmap/product-roadmap.md` 的 M8
7. `docs/roadmap/gases-and-granular.md`
8. `docs/roadmap/materials-and-chemistry.md`
9. `docs/roadmap/cross-cutting-contracts.md`
10. 涉及里程碑关闭时再读 `docs/roadmap/acceptance-and-release.md`

本文特意比日常任务清单更详细，因为它要冻结跨 solver、化学、存档、容器和协议的新真值。实施时仍应保持每个提交小而直接，只从 routing 选择最近验证门。

---

# 1. M8 的完成定义

M8 关闭时，CPU 权威域应形成下面这条完整闭环：

```text
数据驱动 particulate material / carrier / solid-liquid reaction registry
        ↓
活动 granular parcel：材料、精确骨架体积、独立质量、速度、可选固相组成
        ↓
液体双向浮力/阻力 + 精确排水 + 原压力格中的孔隙水
        ↓
稳定 parcel 守恒聚合为 deposit cell
        ↓
局部安息角松弛、沉积休眠、剪切/支撑/反应局部唤醒
        ↓
M7 dissolved species ↔ M8 solid composition 原子相转移
        ↓
region-state v4 / granular container / container WAL v4
        ↓
protocol v4 的有界只读颗粒与沉积显示
```

只有以下结果同时成立，M8 才算完成：

- 活动颗粒与休眠沉积物使用同一套精确固体库存；parcel↔deposit 任意往返不改变一枚固体体积量子、材料库存或固相 species quantum。
- 颗粒排开的水量只来自颗粒/沉积物的真实骨架体积，绝不来自平滑液体 density、渲染 fill、碰撞半径或 bulk deposit 体积。
- 重颗粒下沉、轻颗粒上浮、近中性颗粒近似悬浮；阻力与反作用冲量在无墙、无重力 fixture 中闭合总动量。
- 孔隙率控制沉积物 bulk 形态和渗透阻力，但孔隙水仍进入同一压力投影；沉积物不能被当成不透水的普通 solid mask。
- 堆积能形成数据定义的安息角并真正进入休眠；普通支撑变化、局部剪切或局部反应只触及必要邻域，不能把整座沉积物全量粒子化。
- 反应沉淀和普通细沙共用机械运动路径，但保留不同 stable material ID、颜色、质量、颗粒尺度、孔隙/摩擦参数和反应规则。
- 沉淀/再溶解前后，液体与固体合计 species、组成、电荷、质量和声明的相体积转换分别闭合；任何预算失败都在提交前全量拒绝。
- v1/v2/v3 region、protocol、WAL reader 与 canonical bytes 保持；功能门关闭或身份未知时保留新状态并 LOCKED，不降级、不猜测。
- 客户端只得到有界显示数据；观察、粒子特效、沉积 mesh 或颜色都不能唤醒、结算或修改服务器库存。
- 低成本模型满足守恒与形态门；只有形态门在一个完整、统一的低成本模型上仍失败，才允许做隔离的 MPM/Drucker–Prager 小型验证。

M8 不是“给水里画一些下落沙点”，也不是“把沉积变成若干沙方块”。只完成视觉、沉降或配方中的一部分，都不能关闭里程碑。

---

# 2. 范围冻结

## 2.1 纳入 M8

- 数据驱动颗粒材料、载液黏度/阻力定义、固液相转移规则和世界级权威 registry snapshot。
- 活动颗粒 parcel 的精确固体体积、独立质量、速度、材料和可选固相组成。
- 重力、浮力、有限相对速度阻力、两向动量交换、自由液面浸没比例和颗粒 CFL。
- 活动颗粒、沉积物骨架体积对液体/气体可用体积的精确扣除。
- 沉积物的稳定材料组、精确固体量、独立质量、孔隙率、必要混合组成和局部表面状态。
- 多孔沉积中的水压力与 Darcy 型阻力；孔隙水仍是普通液体 marker，不成为 deposit 的第二套水库存。
- 守恒安息角松弛、沉积休眠、局部唤醒/侵蚀、支撑变化和碰撞挤出。
- M7 dissolved species 与 M8 solid composition 之间的有限溶解度、沉淀和再溶解。
- 独立 config gate、region-state v4、protocol v4、granular container、container-transfer WAL v4。
- 开发 fixture、管理员状态观测、客户端活动颗粒与沉积物只读显示。

## 2.2 明确不纳入 M8

- 通用刚体、弹性体、破坏、岩土工程级压密、泥浆流变、黏性土、团聚或胶结。
- 全粒度 DEM、逐真实沙粒、完整多尺度粒径分布、布朗运动、湍流调制或颗粒旋转自由度。
- 通用弱酸/缓冲/活度、完整 Ksp 热力学、反应动力学、温度、相变热或反应热。
- 原版 falling block、海滩/河床全世界接管、漏斗/管道/第三方流体 API；这些仍属于 M10 或更晚的开放世界评审。
- 气体库存持久化。M8 只让 runtime 气体的可用几何体积看见固体骨架；不保存气体 node、库存或压力。
- 生产 GPU 后端、wall-time 阈值修改、`REDUCED`/`PAUSED` 语义修改、solver/world/readback profile 债务。
- 黑色三角面、液液锐化、逐顶点法线或其他渲染专项；M8 显示不能顺手修补既有水面问题。
- 为可能采用 MPM 预建 backend 抽象、GPU buffer、constitutive model 层或完整框架。

## 2.3 术语必须统一

- **grain**：材料定义中的物理代表粒径，用于阻力、临界剪切和渗透率；不是一个 Java 对象。
- **granular parcel**：运行中的数值聚合包，可代表许多同材质、同组成的 grain；它拥有精确库存和一个速度。
- **solid skeleton volume**：真正排开液体/气体的固体体积，是 M8 体积真值。
- **bulk deposit volume**：由 skeleton volume 与 porosity 派生的堆积外形体积；它包含孔隙，不能再次全部排水。
- **deposit cell**：稳定沉积物的宏观存储格，不是 Minecraft block，不进入 world ownership registry，也不改 vanilla 方块。
- **pore water**：位于 bulk deposit 孔隙中的普通液体 marker；压力、材料、化学仍由现有液体状态拥有。
- **wake**：从休眠沉积库存中精确取出一部分，创建活动 parcel 或将局部形态重新加入 dirty queue；不是重建全域。

代码、命令、日志和文档都避免把液体 marker 叫作 granular particle，也避免把 numerical parcel 宣称为一颗真实沙粒。

---

# 3. M8.0 必须先冻结的设计门

实现生产状态前必须把本节提炼到新的正式架构文档（建议 `docs/architecture/granular-state.md`）并经一次独立评审。M8.0 可以有可删除 prototype，但不能先把未决表示写进 v4 存档。

## 3.1 三种低成本候选

| 候选 | 优点 | 致命问题 | 结论 |
|---|---|---|---|
| 全活动球形 parcel + DEM 接触 | 运动直观，局部碰撞自然 | 静态堆仍按库存体积线性耗时；parcel 聚合尺度会污染形态；难达 80% 休眠门 | 不作为生产首选，仅可作小场景 oracle |
| 纯 Eulerian 沙体积分数/格子沙 | 状态少、沉积便宜 | 水中单粒沉降/浮力、parcel 输运和局部反应接触不自然；易重新发明第二种流体 | 拒绝 |
| 活动 parcel + 守恒 deposit cell | 活动成本随扰动增长；沉降和静态形态各用合适表示；能保存精确库存 | 需要严谨的 parcel↔deposit、孔隙和局部坡面事务 | 选定 |

选定模型中，活动 parcel 不做昂贵的全对全 grain 接触。它与 world cut-cell、沉积表面和必要的局部 parcel occupancy 碰撞；稳定堆的材料内摩擦由守恒 deposit surface relaxation 表达。禁止再加入 Godot 旧原型的半格支撑链、经验 adhesion、吸附、强制找平或“如果下方有沙就静止”特例。

## 3.2 格式版本门冲突必须先解决

现有 roadmap 把“未来气体持久化”口头称为 `region-state v4`，但 M8 又是 v3 后第一个需要持久化的新物理库存。一个 committed region 不能由 v3 HEAD 外挂无提交点颗粒 sidecar，也不能跳过原子联合 checkpoint。

推荐并冻结如下：

1. M8 占用 `region-state v4`，因为它是 v3 后第一个实际发布的新 region schema。
2. M8.0 同步把 roadmap 的气体表述改成“下一未占用 region-state 版本（预计 v5）独立评审”；这只重命名未来版本，不实现或携带气体持久化。
3. protocol v4 与 container-transfer WAL v4 同样只属于 M8；三种 v4 是独立格式，不能因为数字相同而共享 codec 或兼容推断。
4. server config 独立升为 v5；config 版本与 region/protocol/WAL 数字无关联。

如果维护者拒绝让 M8 占用 region-state v4，应立即暂停编码并先形成替代版本分配 ADR。不得私自用 region-state v3 extension、无 HEAD sidecar 或“先写 v5、以后补 v4”绕开。

## 3.3 M8.0 prototype 必须给出的证据

至少完成以下隔离实验，结果写入 decision record；源码在生产路径覆盖后删除：

- 一个 parcel 在静水中的 Schiller–Naumann 沉降曲线与终端速度。
- 同体积轻/中性/重 parcel 的浮、悬、沉方向和动量交换。
- 固体体积在两个压力格间移动时，液体容量变化源能挤开等量水而不改液体 marker 总账。
- skeleton volume 与 bulk volume 分离的单格孔隙压力 fixture。
- 4 邻域与带合法对角路径的 8 邻域坡面松弛比较；选定方案必须限制网格朝向误差。
- 同一堆积经历 10,000 次 parcel↔deposit 和局部 wake，固体体积量子逐项零漂移。
- 活动 parcel 数上限、deposit group 上限、dirty queue 上限和默认 9,216-cell 区域内存估计。
- gate on/empty、典型 1 BV 沉降、80% 休眠后三组 wall-time；只量化，不改现有 40/200 ms pacing。

M8.0 通过后再冻结所有常量。本文第 10 节给出默认候选；若实测修改，必须连同理由、替代值和预算推导写入架构文档，不能散落为 magic number。

---

# 4. 物理真值与单位

## 4.1 新增真值

M8 至少新增以下互相独立的账本：

1. 活动 parcel 的逐材料 solid skeleton volume。
2. 活动 parcel 的逐材料质量。
3. 活动 parcel 的固相 species/组成/电荷（仅 reactive parcel）。
4. deposit cell 的逐材料 solid skeleton volume。
5. deposit cell 的逐材料质量。
6. deposit cell 的固相 species/组成/电荷。
7. deposit cell 的 porosity 状态。
8. particulate external-source 与 escaped-sink 审计。

以下不是新真值：

- 数值碰撞半径、平滑固相分数、pressure face void fraction、沉积 surface height、渗透率、颜色、网络量化值和客户端 mesh。
- bulk deposit volume 是 `solidVolume / (1 - porosity)` 的派生值。
- parcel 代表的真实 grain 数量是 `solidVolume / primaryGrainVolume` 的派生值，可为非整数数值权重；不另设“粒子个数库存”。

## 4.2 固体体积量子

不要用十进制纳体积直接驱动 solver。当前生产分辨率每 BV 有 `2×2×2 = 8` 个 solver cell，而 `float particleVolume` 以 solver-cell volume 为单位。M8 使用独立二进制固体量子：

```text
SVQ_PER_BV   = 2^27 = 134,217,728
SVQ_PER_CELL = 2^24 = 16,777,216  （cellSize = 0.5 时）
1 SVQ        = 2^-24 solver-cell volume
```

理由：

- `long` 加减、分配和 parcel↔deposit 转换严格闭合。
- 一个 SVQ 对应的 solver-cell delta 是精确二进制值，M8 相转移可以修改 `float particleVolume` 后逐项验证真实 delta，而不引入新的液体 residual 账本。
- 世界/液体容器继续使用既有 `1 BV = 1_000_000_000 nanos`；两种单位不能混称或复用字段。

生产 granular gate 首版只允许 Minecraft 当前固定 `cellSize = 0.5`。如果以后允许其他分辨率，必须另评物理 BV 与 solver-cell SVQ 的整数映射；不得让同一 v4 存档随当前配置改变体积含义。

所有 SVQ 运算使用 checked `long`。外部显示可换算 BV；pressure 输入可换算为 `double/float` 数值场，但任何数值场都不能反向重建 SVQ 库存。

## 4.3 质量和密度

- 每个活动 parcel 和 deposit material group 保存独立 positive finite raw `double mass`；迁移、分裂和合并保存 raw bits 或在明确的稳定顺序下做末项取余。
- `ParticulateMaterial.referenceDensity` 只用于创建新的无历史 inert material、定义验证和诊断；不得从已存 solid volume 重算已存 mass。
- 浮力使用 parcel 实际 `mass / solidVolume` 与当地载液实际 marker mass/volume；不能只看 material display density。
- phase transfer 的固体质量来自被转移 species 的 exact-rational mass。液体 marker 的既有 M7 `massResidualBits` 保持；沉淀时只减去被移走 species mass，再溶解时只加回同一质量。
- 无墙无重力的 drag fixture 中，液体网格与 parcel 的总动量按容差闭合；沉积碰撞和摩擦产生的冲量明确归属 world/controller boundary，不能伪称内部守恒。

## 4.4 孔隙率

每个 deposit cell 保存一个固定点 porosity：

```text
POROSITY_SCALE = 1_000_000
0 < porosityQ < POROSITY_SCALE
```

`solidVolumeSvq` 是排水真值；`bulkVolume = solid / (1-porosity)` 只用于表面高度、容纳容量和渗透率。混合材料进入同一 cell 时，porosity 按加入前后 solid volume 做确定性加权并只舍入一次；已存值不得在 reload 时按当前数据包重算。

deposit cell 必须满足：

```text
0 <= solid skeleton volume <= geometric open volume
0 <= derived bulk volume <= local supported bulk capacity
liquid capacity = geometric open volume - solid skeleton volume
```

孔隙里已有或后来进入的水仍由液体 marker 拥有；deposit state 不保存“孔隙水体积”。

## 4.5 固相组成

reactive solid 使用独立 `SolidCompositionRecord`，不得把它塞进 `ChemicalMixtureRecord`：

- stable chemical species 或 runtime handle 的稀疏 `(species, amountQuanta)`，每 record 最多 16 项；
- 可选 raw mass residual，只用于显式定义需要的固相非 species 质量；首批反应沉淀 residual 必须为 0；
- 固相 material handle 与 composition handle 分开；inert sand 的 composition handle 为 0；
- exact species totals、composition bases、charge 和 mass 可从 world chemical registry 校验，但 liquid chemical phase/indicator 不进入固相 record。

M7 registry snapshot 仍是 species 的唯一解释；M8 不改它的 canonical bytes 或 fingerprint。

---

# 5. 数据定义与世界 registry

## 5.1 数据路径

建议新增：

```text
data/<namespace>/realwater/particulate_materials/*.json
data/<namespace>/realwater/granular_carriers/*.json
data/<namespace>/realwater/solid_liquid_reactions/*.json
```

stable ID 由资源 namespace/path 得到。定义 codec 继续采用 M7 已验证的 bounded exact rational parser；禁止为 M8 再写一套无上限十进制解析。

## 5.2 ParticulateMaterialDefinition

首版字段至少包括：

```text
stable id
reference density                 # 只供创建/校验
primary grain diameter (m)
rest porosity (exact rational)
repose angle (degrees)
static/dynamic friction
restitution
critical Shields parameter
erosion rate cap (SVQ/s per exposed cell)
maximum active parcel volume (SVQ)
base ARGB / display texture key
reactive or inert declaration
```

`primary grain diameter` 用于 drag、Shields 和 permeability；`maximum active parcel volume` 只限制数值聚合。两者不得混成同一“半径”。

## 5.3 GranularCarrierDefinition

当前 `FluidMaterial` 没有黏度字段，而且 v2 material snapshot/canonical bytes 已冻结。M8 不应给 `FluidMaterial` 强塞一个默认黏度并改变旧构造语义。新 granular registry 按 liquid stable material ID 保存：

```text
liquid material id
dynamic viscosity
supported drag law = schiller_naumann_v1
optional maximum Reynolds validity/reporting range
```

密度仍取运行时当地液体 marker mass/volume。granular gate 开启时，活动 region palette 中每种可能接触颗粒的液体都必须存在唯一 carrier 定义；缺失即拒绝激活或恢复，不能按水黏度猜测第三方材料。

## 5.4 SolidLiquidReactionDefinition

首批用一个受限 schema 表达有限溶解度、沉淀和再溶解：

```text
stable rule id
liquid chemical phase id
aqueous stoichiometric species coefficients
solid particulate material id
solid composition coefficients
saturation capacity: extent quanta / BV of liquid
solid volume SVQ / extent
optional accessible surface SVQ cap / cell / step
reversible flag
```

定义加载必须证明：

- aqueous+solid 两侧 exact mass、声明组成和电荷守恒；
- 每个 precipitation extent 的 `solidVolumeSvq > 0`，并对应相反的 liquid marker volume delta；M8 首版要求总占据体积零变化；
- coefficients、extent、乘积和 volume conversion 全部 checked；
- 同一 chemical phase 中不存在两个规则竞争消费同一限制 species；
- 不存在 precipitation/dissolution 回馈环、隐藏 priority 或一次逻辑步内非终止循环；
- 所有 species、phase、particulate material 和 liquid material 引用已知且唯一。

这里的 saturation capacity 是游戏化、确定性的有限溶解度模型，不宣称完整 Ksp、活度或温度依赖。一个 cell/rule 的目标固相 extent 为“该格可用 formula extent 减去按液体体积计算的溶解容量”，并在同一步只向目标移动一次；不得先析出再溶解造成振荡。

## 5.5 世界级权威 snapshot

首次启用 granular gate 时原子发布：

```text
<world>/realwater/granular/registry.nbt
```

snapshot canonical bytes/fingerprint 覆盖 particulate materials、carrier properties、solid-liquid rules、单位 scale 和所有稳定引用。规则引用 M7 species 时，snapshot 同时冻结所依赖的 chemical registry fingerprint，但不能复制或重写 M7 registry。

恢复语义与 M7 相同：

- 已有合法 granular snapshot 是现有固体库存唯一解释。
- 当前数据包变化只报告 mismatch，不重解释存量。
- 已有合法 snapshot 时，当前数据包损坏只能报告“无法比较”，不能使权威 snapshot 失效。
- v4 存在而 snapshot 缺失、unknown/corrupt snapshot、fingerprint/identity 不符、需要 chemistry 却缺 M7 snapshot，一律保留并 LOCKED。
- `/reload` 不替换活动世界的权威定义。

---

# 6. 活动 granular parcel 状态

## 6.1 SoA 表示

建议 `GranularParcelStore` 使用 primitive SoA：

```text
float positionX/Y/Z
float velocityX/Y/Z
long  solidVolumeSvq
double mass
int materialHandle
int solidCompositionHandle
int contactAge
byte flags
```

不允许每 parcel `Map`、NBT、Java list、registry string 或对象链。stable ID 只在 registry/persistence/container 边界出现。

parcel 的数值碰撞 envelope 从 parcel bulk volume 派生并受 `maximumActiveParcelVolume` 限制；drag 始终使用 material 的 physical grain diameter。一个 parcel 合并更多 grain 不能因此获得错误的单大球终端速度。

## 6.2 parcel split/merge

- 新 parcel 超过 material 上限时，按固定最大 SVQ 切分，最后一项取余；质量和 composition 用 solid volume 权重 largest-remainder/末项 remainder。
- 同 cell、同 material、同 composition、速度接近的 parcel 可合并到上限；合并保存质量、动量和质心。
- 不跨 material 或 composition 合并；reactive 与 inert 不因颜色相同合并。
- hard capacity 不足时，creation/phase transfer/container insert 必须在 commit 前拒绝；不能覆盖旧 parcel、删最小 parcel 或直接把悬浮物塞进 deposit。
- deposit wake 可按最大 parcel volume 聚合创建，不要求一 grain 一个 parcel。

## 6.3 浸没与 carrier 选择

颗粒所在格有液体 marker 时：

1. 在 containing cell 内按距离选最近 marker，ordinal 破同距，用其 material/chemical phase 选择 carrier；
2. 液体速度仍采样现有共享 MAC 场；
3. carrier density 从该 phase 的实际 marker mass/volume 聚合；
4. 浸没比例从现有 exact marker volume/PLIC 几何派生并钳制 `[0,1]`；
5. 无液体 marker 时按空气中的弹道 parcel 处理，不使用平滑 volume 的微量尾巴制造浮力。

为此需要给现有 per-cell phase sparse aggregation补 phase mass；不能用 `FluidMaterial.referenceDensity` 替代实际 marker mass。

## 6.4 重力、浮力和阻力

一个 parcel 的外力：

```text
F_gravity = mass * g
F_buoyancy = -rho_fluid * submergedSolidVolume * g
F_drag = 0.5 * C_D * rho_fluid * aggregateArea * |u-v| * (u-v)
```

其中：

```text
Re = rho_fluid * grainDiameter * |u-v| / dynamicViscosity
C_D = 24/Re * (1 + 0.15 * Re^0.687), Re < 1000
C_D = 0.44,                         Re >= 1000
aggregateArea = representedGrainCount * PI * d^2 / 4
```

`Re→0` 使用 Stokes 极限，不能除零。drag 采用解析/半隐式 relaxation，避免轻 parcel 或高黏度时把 slip 一步翻号；drag 与 buoyancy 的相反冲量按同一插值 stencil 散回 MAC face mass，再进入压力投影。所有 stencil 权重确定性归一，空液体 stencil 不施加反作用。

这是一套候选相关式，M8.0 必须用 settling oracle 校准适用范围；超出定义 validity range 只报告诊断或拒绝该材料，不悄悄套用无界经验系数。

## 6.5 CFL 与碰撞

- `calculateSubsteps` 同时考虑最大液体 face speed 和最大 granular parcel speed；两者共用现有 `maxCourantNumber`，不另开能穿墙的粗步。
- parcel 对 world 使用 sweep/CCD，复用 `FlipCutCellGeometry` 的微体素几何语义，但独立实现 granular radius；不要改液体 marker 的 point collision 行为。
- parcel 对 deposit surface 使用派生 bulk surface 做 sweep；不把 deposit 写进 `solid[]` 或 `FlipCutCellGeometry`。
- 只截断接触法向并按 material friction/restitution 更新切向；冲量记为 boundary impulse diagnostic。
- parcel-parcel 不做全对全球碰撞。高局部固相率通过 occupancy cap、合并和 deposit transition 处理。

---

# 7. 精确排水与液体压力耦合

## 7.1 每格容量

对每个 solver cell 定义：

```text
O_i = world cut-cell geometric open volume
S_i = active parcel apportioned skeleton volume + deposit skeleton volume
C_i = O_i - S_i                         # liquid/gas void capacity
L_i = exact liquid marker volume sum
```

`S_i` 从每个 parcel 的 exact SVQ 和 deposit group exact SVQ 构建。活动 parcel 可用 trilinear support 分配到至多 8 个 cell，但必须用 fixed-point 权重 + largest remainder，使所有 cell SVQ 之和严格等于 parcel SVQ。这个 occupancy field 是数值离散，不是第二套固体库存。

硬条件：`0 <= S_i <= O_i`。source、phase transfer、wake、deposit move 或 world edit 的计划若会违反它，在修改任何状态前拒绝或缩小到完整可表示量；不能事后删除水/沙修正。

## 7.2 moving-solid 容量源

每个 substep 先预测 parcel 的 collision-resolved next position，并构建 `S_old`/`S_next`。压力目标增加：

```text
target divergence from moving solid = -(S_next - S_old) / dt
```

全域 `sum(S_next-S_old)=0` 时，该源全域严格回和为零。它让进入某格的固体排出等体积液体，让离开的格释放容量，而不改液体 marker 总量。

压力 RHS、矩阵、preconditioner、projection、PLIC fraction、volume drift 都必须消费同一 `GranularCouplingFields`：

- cell liquid capacity 使用 `C_i`；
- face void fraction 从相邻 cell void fraction 以同一对称公式得到；
- pressure operator 的 face coefficient、RHS flux 和 projection 共享该 face fraction；
- moving-solid target 与现有 volume drift、surface-tension/gas boundary 各自独立相加。

禁止只改 `volumeDriftDivergence` 或只在渲染/插入时扣 volume；那会让 pressure、projection 与 capacity 使用三套几何。

## 7.3 deposit 中的孔隙水

deposit 的 skeleton volume 从 capacity 扣除，但 bulk porosity 对 face 施加 Darcy 型阻力：

```text
K = d^2 * porosity^3 / (180 * (1-porosity)^2)
u* = u / (1 + dt * dynamicViscosity / (rho * K))
```

实际实现使用 bounded denominator、material validity range 和 face bulk fraction；相邻 deposit 的混合 K 采用对称 harmonic 形式。压力格仍由 `markerCount > 0` 决定，孔隙中的 marker 仍被投影，face 不得因为有 deposit 就归零。

Kozeny–Carman 只是首版低成本 permeability closure；M8.0 必须用 hydrostatic 与单向渗流 fixture 检查。若只需正确静水而动态渗流误差较大，应先收紧材料范围/区域尺度，不以不透水 solid mask 取代孔隙。

## 7.4 runtime 气体

M5 气体仍 runtime-only，但 cavity 的 `V_avail` 必须扣除 granular skeleton volume；否则密闭容器总体积会重复。空沉积孔隙可继续属于气体 topology，首版不承诺颗粒床气体压降精度。

这项修改只扩展 `AirCavityScanner` 的只读几何输入，不保存 granular 内的气体、不改变 gas ledger 格式，也不顺手实现 gas region-state。

## 7.5 外部插入和 fully sealed 容量

- 插入固体前检查全域及目标局部完整容量；有自由空间时固体通过 pressure 在后续步挤水，液体库存不被直接删除。
- 无任何空余的 sealed incompressible region 必须拒绝外部 solid insert；不能把水记 escaped sink 来腾位置。
- phase precipitation 同时把液体 marker volume 减少等量 SVQ、增加 solid SVQ，因此 combined occupied volume 不变。
- world block 放置仍以玩家方块优先；被占据的 liquid 与 granular 都返回完整 displacement ledger，先局部恢复，确实无法恢复的部分分别进入显式 escaped sink。

---

# 8. deposit state 与安息角

## 8.1 DepositStore

每个有沉积的 solver cell 保存：

```text
cell index
porosityQ
sorted material groups[]:
    material handle
    solidVolumeSvq
    mass bits
    solidCompositionHandle
sleep/dirty scheduling bits（不参与库存）
```

每格 material group 最多 8 项。相同 material+composition 必须 canonical 合并；超过上限的 plan 整体拒绝，不能丢小成分或只保留 dominant material。

没有 deposit 的 cell 不分配 group 对象。建议采用 cell→entry-head primitive sparse pool，而不是 `Map<Integer,List<...>>`。deposit cell 即便没有任何 liquid marker 也必须进入 v4 section capture。

## 8.2 parcel → deposit

parcel 只有同时满足以下条件才可沉积：

- 与 world/deposit support 接触；
- 法向速度和总 kinetic energy 连续若干 substep 低于材料门；
- 当地 slope relaxation 能找到合法 supported capacity；
- 转换后的 bulk volume 不超过 cell/cut geometry 容量；
- material group、composition pool 和 dirty queue 预算均可预先满足。

转换是单次 plan/commit：从 active store 删除的 exact SVQ/mass/composition 必须逐项进入 deposit。速度归零所产生的接触冲量/能量耗散归 world boundary diagnostic。失败时 parcel 原样保留。

## 8.3 安息角松弛

不使用支撑链。对局部 dirty surface cell 建立重力方向上的 surface elevation，邻接使用水平 4 面和经合法两段 face path 验证的对角，共 8 个候选；对角距离为 `sqrt(2)h`，不能穿 closed corner。

若 donor/receiver 高差超过：

```text
allowedHeightDifference = horizontalDistance * tan(reposeAngle)
```

计算超额 bulk volume，再按 donor porosity 转成可移动 solid SVQ，向下取整并受以下量共同限制：

- donor exposed solid inventory；
- receiver supported bulk capacity；
- material 每步 relaxation rate；
- logical-step operation budget。

多材料 donor 以 exact solid-volume 权重做 deterministic largest remainder；末项取得所有 SVQ 余数，质量末项取得 floating remainder。使用 red-black/固定方向轮换的有界迭代，遍历顺序不依赖 hash。未完成的超坡 cell 保留 dirty，不能为了预算直接标为稳定。

坡面松弛在 deposit 表示内守恒移动库存，普通稳定变化不需要先创建 parcel。只有失去所有支持、被流体侵蚀或玩法明确挖取的 exposed 量才变成活动 parcel。

## 8.4 混合沉积物

- cell 保存所有必要 material/composition group，不做层状地质历史。
- porosity、repose angle 和 permeability 的派生混合规则必须 canonical。建议 porosity 按 solid volume 加权；repose angle 取 material-weighted 后再钳制到各组范围；permeability 用 harmonic closure。
- 任何混合 closure 都只影响形态/运动，不得改变逐组库存。
- 若首批 morphology fixture 证明简单混合角产生不可接受的分层误差，先限制可混 material 组合或保存一个 bounded surface group index；不要立刻引入无限 strata。

---

# 9. 沉积休眠与局部唤醒

## 9.1 休眠语义

deposit 本身就是零动量宏观休眠表示。一个 deposit neighborhood 在连续默认 20 个 logical step 中满足以下条件后退出 dirty queue：

- 无 slope transfer；
- 无 active parcel 接触；
- 无超过临界值的 fluid shear；
- 无 block/cut-cell/support change；
- 无 solid-liquid reaction 或 pending phase transfer。

sleep age 是调度状态，不是物质真值。checkpoint 可保存 stable/dirty bit以便诊断，但 restore 必须在 operator-paused 下按当前 collision 重建局部 dirty frontier，不能相信旧 support。

region 级 hydrostatic sleep 还必须要求：active granular parcel 数为 0、deposit dirty queue 为空、无 pending granular chemistry。现有 `commitHydrostaticSleep()` 不能把活动 granular velocity 清零后冒充稳定。

## 9.2 流体剪切唤醒

只检查 exposed deposit surface。用当地切向速度梯度估计 bed shear：

```text
tau_b = dynamicViscosity * |du_t/dn|
Shields = tau_b / ((rho_s-rho_f) * g * grainDiameter)
```

当 `rho_s <= rho_f` 或 `Shields > criticalShields` 时，按超过门槛的比例和 material erosion cap 计划唤醒 exposed SVQ。计划从 deposit 精确扣除并聚合成少量 active parcel；预算不足全量拒绝并保持 dirty，而不是删库存。

临界值和 erosion cap 是 material data，不允许为单一 fixture 加“水往右时多挖一层”等方向偏置。

## 9.3 其他唤醒源

| 事件 | 允许触及范围 |
|---|---|
| block/cut-cell 改变 | 改变格及 1-cell support/slope 邻域 |
| active parcel 撞击 | 接触格及直接 slope 邻域 |
| 玩家/发射器 granular 容器 | 实际目标 region/cell 的有界 BFS |
| precipitation/dissolution | 发生相转移的 cell、暴露 surface 和直接 support 邻域 |
| fluid shear | 超阈值的 exposed surface cell |
| 客户端观察/快照/mesh | 绝不唤醒 |

dirty queue 上限为 region cell budget；每 logical step 只处理固定 operation budget。剩余工作留队。不能每 tick 扫描所有 deposit cell，也不能因一个底部 block 删除而全域 `deposit → parcel`。

## 9.4 反应接触

再溶解直接从同 cell 的 exposed deposit/active parcel 扣除将被溶解的量，不必先把该量粒子化。反应导致 support/slope 改变时只把邻域标 dirty。深层 group 在上层未移除前不可被同格液体隔空消费。

---

# 10. 硬预算候选

M8.0 实测后把最终值写入 `GranularLimits` 和架构文档。首轮候选：

| 项目 | 候选上限 |
|---|---:|
| particulate materials / carrier definitions / solid-liquid rules | 256 / 512 / 512 |
| active granular parcels / region | 16,384 |
| active granular parcels / server（默认 4 region） | 65,536 |
| max parcel solid volume | `SVQ_PER_CELL / 64 = 262,144 SVQ` |
| deposit cells / region | region cell budget |
| material groups / deposit cell | 8 |
| deposit groups / region | 65,536 |
| solid composition entries / record | 16 |
| live solid composition handles | active parcel cap + deposit group cap + 1 |
| granular dirty cells | region cell budget |
| granular operations / logical step | 131,072 |
| phase-transfer evaluations / logical step | 131,072 |
| registry stable ID / rational text / rational bits | 128 chars / 4,096 chars / 16,384 bits |
| granular container groups / composition entries | 16 / 256 total |
| protocol active parcel display samples / region | 4,096 |
| v4 snapshot bytes / default region | 512 KiB |

这里的 max parcel volume 是 `1/64 solver cell`，线性尺度明显小于 cell；1 BV 最多需要 512 个满上限 active parcel。大静态库存应迅速进入 deposit，不靠提高 active cap 承载整座沙堆。

所有 pool、decode、scan、BFS、reaction、relaxation 和 allocation 预算都必须先于部分 commit 检查。预算失败使 granular stage 进入带可读原因的 PAUSED/blocked 状态；它不是 wall-time pacing，不能触发阈值修改。

---

# 11. 每步求解顺序

## 11.1 substep

建议把 `FlipFluidDomain.step()` 保持为高层编排，不把全部 granular 算法继续堆进该文件。每个 CFL substep 顺序冻结为：

1. 从液体 marker 重建现有 MAC、volume、markerCount、phase volume/mass。
2. 从 active parcel + deposit 构建当前 exact granular occupancy、liquid capacity、porosity/permeability field。
3. runtime air cavity 重扫时扣除 solid skeleton volume。
4. 保存 P2G velocity 供 FLIP delta。
5. 对 active parcel 施加 gravity；按 carrier/浸没计算 semi-implicit buoyancy+drag，并把相反冲量散回有质量的 MAC faces。
6. 用更新后的 parcel velocity做 collision-resolved next-position preview；构建 next occupancy 与 moving-solid capacity source，不提交位置。
7. 施加液体 gravity、液液 surface tension 和 porous face damping。
8. 使用 world cut-cell × granular void face 的统一几何求 pressure；RHS 同时含 volume drift、surface/gas boundary 和 moving-solid source。
9. 用同一 face coefficient projection，重新 enforce world solid faces并外推空气速度。
10. G2P 更新液体 marker；advect liquid marker。
11. 提交已经用于 pressure preview 的 granular next position/velocity；碰撞结果与 pressure source 必须是同一份计划。

任何 preview 失败都不得留下半步 parcel 位置。若 pressure 或 postcondition 失败，沿用现有区域 PAUSED 故障语义并保留最后完整可 checkpoint 状态；实现前必须决定是保存 granular preview 还是对整个 substep 增加小型 rollback。不得暴露“液体已投影、颗粒仍在旧格”的混合状态。

## 11.2 每个 external step 只执行一次的逻辑阶段

所有 substep 完成后：

1. 运行现有 M7 dissolved chemistry 一次，保持其“每 external step 一次”的冻结契约。
2. 运行 M8 solid-liquid phase-transfer plan/commit 一次。
3. 新溶出的 species 只标记 M7 dirty，下一 external step 再参加 aqueous reaction；不能为 M8 偷跑第二次 M7 reaction。
4. 处理 parcel→deposit、局部 slope relaxation、erosion/wake 和 dirty/sleep age。
5. 运行现有 liquid reseeding；相转移已修改的 liquid volume/mass/species 必须一起进入 reseed。
6. 重建最终 grid/diagnostics，核对所有 ledger。

phase transfer 与 deposit transition 都必须是 two-phase plan/commit；不能循环中逐 parcel 修改后才发现 pool、capacity 或 marker positivity 不足。

## 11.3 gate-off 退化

`granularEnabled=false` 时：

- 不分配 granular arrays、occupancy、deposit pool 或 registry runtime handles。
- `FlipFluidDomain.step()` 走原有 M7 路径，关键输出应保持逐 bit。
- air scanner、pressure、free surface、world collision、region capture 和 protocol 都调用旧签名/旧版本。
- v4 region 被发现时保留并 LOCKED；绝不写回 v3。

---

# 12. M7 ↔ M8 相转移事务

## 12.1 饱和目标

对 `(cell, liquid chemical phase, rule)` 聚合 M7 species 与同格 exposed solid inventory。用 exact stoichiometry 求：

```text
availableFormulaExtent = min(speciesAmount_i / coefficient_i)
dissolvedCapacity = floor(liquidVolumeBV * capacityExtentPerBV)
totalAccessibleFormulaExtent = availableFormulaExtent + existingAccessibleSolidExtent
targetAccessibleSolidExtent = max(0, totalAccessibleFormulaExtent - dissolvedCapacity)
```

`existingAccessibleSolidExtent` 只含同格 active parcel 与 exposed deposit surface，深层库存不参与本次平衡。一次 logical step 只从当前 accessible solid extent 向 target 移动，受 accessible surface、operation、parcel/deposit group 和 marker representability 预算限制。相同输入、marker ordinal、deposit cell/group 顺序必须给出相同结果。

## 12.2 precipitation plan

计划同时包含：

- 从指定 liquid marker parcel 扣除 exact species quanta；
- 按 exact species mass 扣除 marker mass，保留原 `massResidualBits`；
- 从 liquid marker `particleVolume` 扣除 `extent * solidVolumeSvq` 对应的精确二进制 cell volume；
- 创建/增长同 cell active granular parcel 的 exact solid SVQ、mass 和 SolidCompositionRecord；
- 更新 M7 dirty cells、granular dirty cells和内部 phase-transfer diagnostic。

每个被修改 marker 必须保持正 volume、正 finite mass 和非空 chemical parcel。若 requested extent 小于 float/SVQ 可表示量，保持 supersaturated dissolved inventory，等待以后聚合；不能删除不足一量子的 species。

## 12.3 dissolution plan

严格反向：

- 只读取同格可接触的 active parcel 或 exposed deposit group；
- 从 solid exact inventory 扣除 extent；
- 把同一 species quanta、mass 和 liquid volume 加回 compatible liquid markers；
- 若 cell 没有 compatible liquid marker、marker/pool budget不足或会违反 material/chemical phase，整项不提交；
- solid group 归零才删除 handle；部分溶解保留剩余 raw mass/composition。

indicator provenance 属于承载液体 marker。新溶解 species 分配到既有 marker 后继承这些 marker 自身 indicator bit；solid 不保存或传播 indicator。

## 12.4 联合守恒式

每次 phase transfer 前后必须验证：

```text
aqueous species + solid species             exact long 相等
aqueous composition + solid composition     exact long 相等
aqueous charge + solid charge               exact long 相等
liquid marker mass + active/deposit mass    容差内相等
liquid marker volume delta in SVQ
    + solid skeleton volume delta            exact long 为 0
```

M7 external-source/escaped-sink 与 M8 particulate external-source/escaped-sink 仍各自保存；相转移是内部 ledger，不得同时记成 source/sink 来掩盖不平。

---

# 13. world ownership、方块变化与生命周期

## 13.1 ownership

- granular inventory 只属于 controller-managed region，不建立 vanilla sand block ownership。
- ownership sidecar 的方块 preimage/committed 语义不变；M8 不扩张 acquire 扫描范围，不接管原版沙或 gravel。
- HEAD 仍是 liquid+chemistry+granular 的唯一物理提交点。granular registry snapshot 是定义身份，不是替代 HEAD 的库存提交点。

## 13.2 world collision refresh

`FluidRegionWorldInteraction` 处理 block/cut-cell 改变时必须一次规划两类 displacement：

1. 现有 liquid/material/chemical marker displacement。
2. active parcel 与 deposit exact granular displacement。

玩家实体方块优先。局部 restore 采用各自完整 ledger；剩余 liquid 进入既有 escaped sink，剩余 granular 进入新的 escaped sink。不能按 material density 从体积重建 mass/chemistry，也不能让 liquid restore 成功而丢失 granular ledger 后继续运行。

## 13.3 lifecycle

- ACTIVE/REDUCED pacing 仍由现有 wall time 决定；M8 只增加诊断分项。
- operator PAUSED、granular-blocked PAUSED、chemistry-blocked PAUSED 的原因分别报告；解除条件不可互相覆盖。
- UNLOADED 必须释放 active parcel、deposit pool、composition pool 和 coupling arrays，但保留 manifest admission 预算。
- restore 先读完整 v4、校验 registry/ledger/budget，再分配 solver；随后从当前世界重建 cut-cell 与 deposit support，保持 operator-paused。
- normal checkpoint、停服、empty/remove、ownership release 都必须处理 solid inventory。区域只有 liquid、active solid、deposit solid、chemical inventory 全部精确为空时才允许 release。

---

# 14. region-state v4

## 14.1 格式原则

- v1/v2/v3 codec、CRC、golden bytes和reader保持原样。
- parser 按精确版本集合分派，不把 `version <= MAX` 当作兼容证明。
- 建议新增 `RegionStateV4Codec` 和 typed v4 payload，避免继续扩大已经承担 v1–v3 的 `RegionStateCodec` 条件分支。
- 两槽、完整 generation、section descriptors、回读验证、HEAD 最后发布、quarantine deletion 都保持。
- v4 是一个完整联合代次，不允许 v3 liquid HEAD + 外部 granular sidecar 各自提交。

## 14.2 capability flags

v4 manifest 保存严格 capability bitset：

```text
MATERIALS       required
GRANULAR        required
CHEMISTRY       optional, 但存在 chemical parcel/solid composition 时 required
PRECIPITATION   optional, 表示规则身份和相转移状态可恢复
```

未知 bit 锁定。不能仅通过“某个 list 非空”猜功能状态。granular on / chemistry off 的 inert sand v4 合法；含 solid composition 的 v4 在 chemistry gate或registry不可用时必须 LOCKED。

## 14.3 manifest 内容

除 v2/v3 相应 liquid/chemical 信息外，v4 manifest 至少保存并校验：

- granular registry fingerprint、SVQ/BV scale、porosity scale、capabilities；
- stable particulate material palette 与 solid-composition canonical table；
- active parcel count、总/逐材料 active solid SVQ 和 raw mass ledger；
- deposit cell/group count、总/逐材料 deposit solid SVQ 和 raw mass ledger；
- combined aqueous+solid species/composition/charge totals；
- particulate external-source/escaped-sink bounded audit；
- granular budgets与每 section descriptor 的 active/deposit count、SVQ、mass、CRC；
- 是否存在 pending dirty work只作 scheduling metadata，不参与物质回和。

## 14.4 section 内容

每 section 保存：

```text
active parcel:
    global ordinal
    local position / velocity
    material palette index
    solidVolumeSvq
    raw mass
    solid composition table index

deposit cell:
    local cell coordinate
    porosityQ
    canonical material groups
    per-group SVQ / raw mass / composition index

section ledgers:
    active/deposit SVQ and mass
    solid species totals
```

没有 liquid marker 但有 deposit 的 section 仍必须存在。decode 在分配 arrays 前检查 count、length、乘法、坐标、duplicate cell/group、palette index、SVQ、mass、porosity、composition和预算。

## 14.5 迁移

- 第一次启用 granular 时，v1/v2/v3 → v4 只在 inactive slot 构建完整新 generation；原有 liquid/material/chemical raw truth不变，granular inventory为空。
- v3 chemistry region迁移时沿用已有 M7 authoritative registry与mixture table，不按当前数据包重建。
- fault injection 后只能看到旧完整 v1/v2/v3 或新完整 v4。
- granular gate关闭发现v4：保留、锁定、禁止普通checkpoint/empty/release；不写downgrade。
- v4 restore后如当前world support改变，只允许通过正式 displacement/wake逻辑处理，不能在decode阶段静默重排沉积物。

---

# 15. granular container 与 WAL v4

## 15.1 容器边界

M8 首版使用独立干颗粒容器，不扩展 bucket/slurry envelope：

- 新 `GranularContents v1` 保存 stable material groups、solid SVQ、raw mass、可选 solid composition 和 granular registry fingerprint。
- item capacity 以 exact solid SVQ 定义；bulk/porosity只用于 tooltip，不能决定结算。
- `FluidMixture v1`、`ChemicalContents v1` 与 `GranularContents v1` 三种表示互斥。
- 普通/化学桶只抽液体，悬浮 parcel 留在 region；这是首版明确的过滤式语义。
- granular container只装/放固体，不携带孔隙水、液体 marker、indicator或气体。
- count必须为1；damage bar、tooltip、颜色只读。

这避免在M8首版发明“液体+化学+颗粒”复合桶。未来其他Mod suspension API必须另立格式。

## 15.2 exact transfer primitive

建议 domain/service 提供：

```text
tryInsertExactGranular(cell, GranularTransferLedger, maxRadius)
tryExtractExactGranular(cell, requestedSvq, maxRadius, predicate)
```

两者先完整 plan capacity、parcel/deposit group、composition和region budget，再一次commit。返回实际逐material SVQ、mass和composition；item只按实际返回更新。不能按reference density补mass，也不能只返回总SVQ丢混合组成。

extract优先 exposed deposit，再取目标邻域active parcel；选择规则stable且写入架构。不能隔墙或从被上层覆盖的深层group抽取。

## 15.3 WAL v4

沿用跨region/item持久域顺序，但新增独立v4 record字段：

```text
PREPARED(before item + requested solid SVQ upper bound)
    → solver exact granular modification
    → stage/verify complete region-state v4
REGION_COMMITTED(after item + actual granular ledger + target checksum)
    → publish HEAD
    → actor after item
    → durable actor save
    → delete WAL
```

- 不得把solid SVQ塞进旧`volumeNanos`字段；v4明确保存resource kind与完整`GranularContents` fingerprint。
- v1/v2/v3 reader/recovery保持；v1 material缺mass仍锁定人工处置。
- HEAD=base只丢弃未提交v4 WAL；HEAD=target且REGION_COMMITTED才收敛actor after；其他代次/identity/checksum/gate/registry歧义保留并锁定。
- actor未加载或durable save未成功时保留WAL；同region禁止新transfer。
- 玩家/发射器仍只按实际目标region路由。创造放出记particulate external source；创造装取仍产生真实item/region转移，不制造hidden sink。

---

# 16. protocol v4 与客户端显示

## 16.1 negotiation

新增：

```text
GRANULAR_SNAPSHOT_VERSION = 4
CAP_GRANULAR_SNAPSHOT
```

granular gate开时server严格只offer v4，并要求material+granular capabilities；chemistry同时开启时也要求chemical capability。旧client不能用v3连接并“看不见颗粒”，因为看不见会导致错误交互判断；应可读拒绝。

granular gate关时仍严格选择v3/v2/v1，旧payload golden bytes不变。

## 16.2 v4 display payload

v4只发：

- v2 material/free-surface几何字段；
- chemistry启用时的v3 bounded chemical display segment；
- particulate display palette（stable ID、base color/texture key）；
- deposit每格quantized bulk fill、porosity display byte和Top-2/dominant material fractions；
- bounded active parcel visual samples：量化位置、速度、显示半径和material index；
- generation/sequence/tombstone/dimension/region identity。

活动parcel超过显示上限时，用stable ordinal/hash规则采样并另发per-cell display density；不能因为客户端cap删除服务器parcel。deposit surface mesh只从quantized bulk fields重建。

不得发送：

- exact SVQ、raw mass、solid species quanta、composition/charge ledger；
- runtime handles、reaction extent、wake queue或authoritative porosityQ；
-可被客户端反写的collision/pressure state。

## 16.3 renderer

- deposit surface与液体surface分开mesh；孔隙水液面仍来自原液体renderer。
- active parcel可用instanced sprite/mesh；采样变化只能影响视觉。
- 客户端接受新v4 snapshot时重建；旧sequence、旧generation、tombstone、dimension switch、disconnect/reconfigure清理语义复制现有ledger模式。
- M8 renderer不修改已有水面normal、液液界面或黑三角债。

---

# 17. 功能门、管理员入口和状态观测

## 17.1 config v5

建议新增：

```text
granularEnabled=false
precipitationEnabled=false
maximumGranularParcelsPerRegion=16384
maximumTotalGranularParcels=65536
maximumDepositGroupsPerRegion=65536
```

依赖：

```text
granularEnabled     requires materialsEnabled
precipitationEnabled requires granularEnabled && chemistryEnabled
```

v1–v4 config reader把新门强制为false并使用安全预算默认；v5缺字段、非法依赖或预算非正拒绝。所有门和预算在server startup绑定，不能reload中途改变已有region含义。

关闭`precipitationEnabled`但granular+chemistry仍开时，可机械加载reactive solid但不做新相转移；关闭chemistry而v4含solid composition时必须LOCKED。

## 17.2 管理员入口

自动门和实机需要的最小入口：

```text
/realwater granulardemo settling_heavy|settling_light|repose|pore|sealed|precipitate
/realwater granularstatus [region]
/realwater granular give <material> [solidBV]
```

`granulardemo`只构建可重复fixture，不替代controller生产域。`give`只能生成通过world registry校验且在item预算内的canonical container，不允许写任意NBT。

`granularstatus`至少报告：

```text
region UUID/generation/state/gates
granular registry fingerprint (+ chemical fingerprint when linked)
active parcel count/cap
deposit cell/group count/cap
active/deposit total and per-material SVQ/mass
solid species totals and combined aqueous+solid audit
dirty/sleep/wake counts
drag/buoyancy/contact impulses
solid-volume pressure source maximum and residual
phase-transfer evaluations/extents/deferred reason
external source / escaped sink
last granular block/overflow reason
granular stage wall-time breakdown
```

完整库存只向有权限管理员按需输出并进入transcript；普通snapshot/status不广播exact ledger。

---

# 18. 建议代码落点与职责

## 18.1 新包

建议建立`src/main/java/com/example/granular/`：

```text
GranularLimits
ParticulateMaterialDefinition
GranularCarrierDefinition
SolidLiquidReactionDefinition
GranularDefinitionCodec / DataPackLoader
GranularRegistry / GranularRegistryStore
SolidCompositionRecord / SolidCompositionPool
GranularParcelStore
DepositStore
GranularTransferLedger
GranularCouplingFields
GranularStep
DepositRelaxer
GranularChemistryBridge
GranularDiagnostics
GranularDisplaySnapshot
```

类数可在职责仍单一时合并，但必须保持以下依赖方向：

```text
definition/registry
      ↓
inventory + pure mechanics
      ↓
solver orchestration adapters
      ↓
persistence / item / network display adapters
```

纯granular core不得依赖Minecraft `ItemStack`、NBT、ResourceManager或客户端类。

## 18.2 fluid 接点

建议新增：

```text
com.example.fluid.FlipGranularState          # 内存handoff，不是wire/save codec
com.example.fluid.FlipGranularCollision      # world/deposit CCD adapter
```

`FlipFluidDomain`只负责：bind/restore/export granular state、step阶段编排、exact transfer入口和现有grid数组接线。drag、deposit、chemistry bridge、codec都不应成为其第二套内嵌实现。

`FlipCutCellGeometry`继续只描述world geometry；不要写deposit mask。`FlipFreeSurfaceGeometry`和pressure路径通过typed `GranularCouplingFields`读取capacity/face void。

## 18.3 persistence

建议新增：

```text
RegionStateV4Codec
GranularRegionState / GranularSectionState
GranularRegionStateIntegrity
```

`RegionStateFiles`只做version dispatch与两槽发布。不要复制一套HEAD/slot文件事务；复用文件层，隔离v4 payload codec和integrity。

## 18.4 必然修改

```text
FlipFluidDomain / AirCavityScanner / pressure/free-surface shared geometry
FluidRegion / FluidRegionFactory / Lifecycle / Operations / Persistence
FluidRegionWorldInteraction / FluidSimulationService
FluidServerConfig
RegionStateFiles version dispatcher
FluidTransferService / ContainerTransferJournal / Store / Recovery
RealWaterComponents / Items / Mod / Commands
FluidProtocol / ProtocolServer / new v4 payload+ledger
RealWaterModClient / ClientFluidVolumeRenderer or dedicated granular renderer
CpuValidationSuite（只增加最近 granular failure domain）
resources: datapack definitions, item/model/texture/lang/recipe
docs/maintenance/routing.md
```

发现文件真正承担第二职责时拆分并同步routing；不要只为“每轮一个类”机械拆文件，也不要继续扩大已经复杂的`RegionState`/`FlipFluidDomain`条件分支。

---

# 19. 分轮实施

每轮在前一轮提交和最近门通过后开始。不要把全部M8做成一个巨型commit。

## Round 0 — M8.0 设计与低成本模型门

产物：

- `granular-state.md`正式契约草案；
- SVQ、porosity、parcel/deposit、drag、pressure source、phase transfer和v4版本分配decision record；
- 第3.3节isolated prototype报告；
- 受影响文件与预算清单；
- 明确低成本模型通过或停止。

只改文档/prototype，不注册item、不写world存档。生产实现前删除无入口prototype源码或移到不可发布实验位置。

推荐提交：

```text
M8: freeze conservative granular state design
```

## Round 1 — M8.1 registry 与纯库存 core

实现：

- definitions/codec/canonical fingerprint/world snapshot store；
- SVQ conversion、material/carrier/rule validation；
- SolidCompositionPool、GranularParcelStore、DepositStore、transfer ledger；
- parcel split/merge与parcel↔deposit纯事务；
- 无Minecraft依赖的`granular` selector基础场景。

此轮不接`FlipFluidDomain`、不改region-state/protocol/item。

推荐提交：

```text
M8: add bounded granular registry and inventory core
```

## Round 2 — M8.2 active parcel 与流体双向耦合

实现：

- domain bind/export handoff；
- carrier phase mass、浸没、gravity/buoyancy/drag；
- granular CFL与world CCD；
- exact occupancy、moving-solid pressure source、face void/capacity共享；
- air available volume扣solid skeleton；
- gate-off逐bit退化与诊断。

最近门：`granular`；只有实际改坏共享cut-cell/pressure/gas路径时才追加`cut-cell`、`free-surface`或`gas-liquid`，不累计运行全部。

推荐提交：

```text
M8: couple active granular parcels to the CPU fluid solver
```

## Round 3 — M8.3 deposit、孔隙与局部唤醒

实现：

- supported deposit transition；
- porosity/bulk surface/Darcy damping；
- conservative repose relaxation；
- sleep age/dirty queue；
- shear、support、collision local wake；
- 80% sleep与active-cost报告。

仍只扩展同一个`granular` selector；长期无creep场景放`soak`，不要新建JavaExec task。

推荐提交：

```text
M8: add conservative porous deposits and local wake
```

## Round 4 — M8.4 config 与 region-state v4

实现：

- config v5/gates/budgets；
- v4 codec/integrity/manifest/sections；
- v1/v2/v3→v4 migration；
- full generation discovery、unknown/corrupt/overbudget lock；
- loaded/unloaded/checkpoint/empty/release；
- granular registry authority。

最近门：`region-state`；granular handoff变化才合跑`granular`。保持v1/v2/v3golden fixtures。

推荐提交：

```text
M8: persist granular region state v4
```

## Round 5 — M8.5 precipitation 与再溶解

实现：

- saturation target与surface accessibility；
- M7 reaction后的一次phase-transfer plan/commit；
- liquid volume/mass/species ↔ solid SVQ/mass/composition联合账本；
- precipitate active parcel、deposit contact dissolution与local dirty；
- registry chemical fingerprint linkage；
- chemistry/precipitation gate matrix。

最近门：`granular,chemistry`。若只改rule codec而未触碰solver，无需region/container/protocol。

推荐提交：

```text
M8: bridge dissolved species and reactive precipitates
```

## Round 6 — M8.6 granular container 与 WAL v4

实现：

- `GranularContents v1` component/item/tooltip/canonicalization；
- exact insert/extract与目标region路由；
- player/dispenser/creative；
- WAL v4、old readers、actor durable recovery；
- item model/texture/creative tab/recipe/`en_us`/`zh_cn`。

最近门：`containers`；仅exact granular primitive本身改动可跑`granular`。改注册后做一次EULA=true startup并正常`stop`。

推荐提交：

```text
M8: transact granular containers through WAL v4
```

## Round 7 — M8.7 protocol、renderer 与 gameplay fixture

实现：

- strict v4 negotiation/payload/ledger/tombstone；
- bounded parcel sampling与deposit display fields；
- client deposit mesh/active parcel rendering；
- `granulardemo`、`granularstatus`和管理员give入口；
- 反馈、资源与transcript。

最近门：`protocol`；物理字段生成改动才追加`granular`。渲染只在自动门不可观察处请求最小实机smoke。

推荐提交：

```text
M8: expose bounded granular gameplay and rendering
```

## Round 8 — M8.8 关闭里程碑

按当时`acceptance-and-release.md`汇总受影响层：

- 运行短`milestone`，不自动把历史full/soak/GPU累加；
- 对`granular`、`region-state`、`containers`、`protocol`和`chemistry`只跑真正受影响的完整selector；
- 一个deposit sleep/no-creep + precipitation/redissolution soak；
- 两个最小实机smoke；
- clean offline assemble/compile、dedicated server startup、版本和JAR SHA-256；
- 更新architecture/routing/progress/roadmap/README。

推荐提交：

```text
Close M8 conservative granular milestone
```

---

# 20. 自动验收矩阵

测试数量必须受控：新增一个`granular` failure domain，接到现有`CpuValidationSuite`；不要为`granular-core`、`deposit`、`precipitation`各建JavaExec。短场景加入fast/milestone，长安息角/休眠循环只进入soak，并优先替换重复不变量的旧场景。

## 20.1 definition/registry

- JSON顺序无关、canonical bytes/fingerprint相同；stable ID排序确定。
- duplicate/unknown/NaN/negative/out-of-range/overflow/rational bomb拒绝。
- material porosity、repose、diameter、density、friction、viscosity边界。
- carrier覆盖当前liquid palette；缺定义不猜默认。
- solid-liquid rule mass/composition/charge/volume不守恒、竞争消费者、cycle拒绝。
- world snapshot首次发布、合法snapshot优先、datapack mismatch、missing/corrupt/foreign fingerprint锁定。

## 20.2 inventory

- SVQ/BV/cell-volume exact conversion边界。
- 10,000次parcel split/merge、parcel↔deposit、wake无一SVQ漂移。
- mass、momentum和SolidComposition largest-remainder闭合。
- mixed deposit 8组正好成功，第9组全拒绝。
- pool/capacity/checked arithmetic失败无部分commit。
- inert与reactive、不同material/composition不误merge。

## 20.3 active mechanics

- 静水中heavy terminal velocity相对oracle误差候选≤5%。
- light上浮、heavy下沉、neutral在容差内不持续漂移。
- 无gravity/wall时drag前后fluid+parcel总动量相对误差≤`max(1e-6, 16 ulp)`。
- high-viscosity/high-slip不翻号、无NaN、CFL增加substep。
- 自由面出入时buoyancy/drag连续；无marker格不受density尾巴浮力。
- cut-cell CCD不穿墙；contact只改法向/摩擦允许切向。

## 20.4 exact displacement/pressure

- parcel在两格间移动：`sum ΔS = 0`且液体marker总量不变。
- pressure RHS/matrix/projection共享face void；对称正定和residual门保持。
- sealed满域solid insert全拒绝；有free volume时不产生liquid sink。
- solid占据使runtime gas available volume等量减少，gas ledger恒等式不变。
- gate-off reference fixture关键数组逐bit相同。
- 不能用smooth liquid density改变任何SVQ settlement。

## 20.5 deposit/porosity/repose

- skeleton volume排水，bulk volume不重复排水。
- pore-water hydrostatic pressure相对无deposit理论值候选≤2%；静水无持续漏失。
- Darcy流量随porosity/diameter单调，pressure仍作用于pore marker。
- 至少三种repose angle的最终坡角误差候选≤5°。
- pile旋转0°/45°的角度差≤5°；closed corner不走对角捷径。
- 稳定后1,000步surface creep小于一个active parcel直径且库存零漂移。
- 代表性静态fixture≥80% granular volume进入deposit sleep；客户端snapshot不唤醒。

## 20.6 local wake

- shear低于critical不wake；超过门按cap只唤醒exposed量。
- block support删除只触及bounded neighborhood；剩余pile不全量particleize。
- reaction只消费exposedgroup；深层组不隔空反应。
- dirty queue预算耗尽保留pending，不把不稳定cell标sleep。
- wake后active+deposit逐material SVQ/mass/composition闭合。

## 20.7 precipitation/dissolution

- 低于capacity不析出；超过capacity只析出exact excess extent。
- 同rule同cell不在一步内析出/再溶解振荡。
- 相邻格、墙后、不同chemical phase不发生固液反应。
- precipitation后liquid volume delta SVQ + solid delta SVQ = 0。
- combined aqueous+solid species/composition/charge exact；mass在容差内。
- indicator不进入solid；dissolved species继承承载marker自己的indicator provenance。
- active precipitate沉降/沉积后material/composition不变。
- parcel/pool/capacity不足时dissolved/solid双方均不变并给出可读blocked原因。
- 10,000 precipitation/dissolution循环无long drift。

## 20.8 persistence

- v1/v2/v3 golden bytes、CRC和reader不变。
- v1/v2/v3→v4迁移保持所有旧marker raw volume/mass/species/residual/indicator。
- v4 active/deposit/composition/porosity canonical round-trip。
- section/manifest逐material SVQ/mass、solid species与combined species回和。
- deposit-only section不被遗漏。
- bad index/duplicate cell/group/invalid porosity/unknown material/wrong scale/fingerprint拒绝。
- count/length预算在allocate前拒绝。
- 每个fault point只恢复旧完整generation或新完整v4。
- gate off读取v4只锁定/保留，不downgrade。

## 20.9 container/WAL

- inert单材、多材、reactive precipitate、partial contents canonical round-trip。
- item↔region逐group SVQ/mass/composition闭合。
- `FluidMixture`/`ChemicalContents`/`GranularContents`双表示拒绝。
- hidden/deep deposit、隔墙、错误region、count!=1拒绝且状态不变。
- player/dispenser/creative与actor unload/durable save。
- v1 pure/v2 material/v3 chemical WAL recovery不回归。
- v4所有fault boundary收敛；unknown/gate/fingerprint/generation歧义保留WAL并锁定。

## 20.10 network/client

- v1/v2/v3/v4 negotiation matrix与缺capability可读拒绝。
- v1/v2/v3 payload golden bytes不变。
- v4长度、palette、index、fraction、sample count攻击拒绝。
- packet不含exact SVQ/mass/species/porosityQ。
- 超过4,096 active parcel时stable采样且per-cell display回和。
- generation/sequence/tombstone/reconnect/dimension switch不复活旧deposit。
- renderer rebuild不触发server wake或physics mutation。

---

# 21. 低成本模型的放行与 MPM 升级门

## 21.1 低成本模型通过条件

低成本模型必须同时满足：

- 所有exact inventory与phase-transfer守恒门；
- terminal velocity、light/heavy、pore pressure和sealed capacity门；
- repose angle、orientation sensitivity、no-creep与local wake门；
- representative fixture 80% granular volume休眠；
- typical active/deposit工作量在现有区域预算内，不迫使修改40/200 ms pacing。

守恒失败首先视为实现/事务错误，不是采用MPM的理由。MPM不会自动修好codec、WAL或账本。

## 21.2 允许触发 MPM 对照的唯一情形

只有在以下流程完成后仍失败，才进入MPM评审：

1. 使用一套统一的parcel+deposit模型，而非每fixture不同特例。
2. 完成一次有记录的参数/邻域/relaxation-rate校准。
3. 证明角度、动态坍塌、孔隙水或再悬浮中至少两项无法同时满足；改善一项必然破坏另一项。
4. 失败不能通过缩小material validity range、active区域或更新率合理解决。
5. 没有加入新的support/adhesion/bias经验规则来“追测试”。

## 21.3 MPM 对照范围

若触发，只做独立小域CPU验证：

- 2D或很小3D sand continuum；
- Drucker–Prager plasticity、最小porous water coupling；
- 与同一repose/collapse/pore-pressure fixture比较守恒、形态、时间和内存；
- 不接Minecraft world、region-state、item、protocol、GPU或生产`FlipFluidDomain`；
- 产出ADR后决定采用、继续低成本或缩小M8范围。

不得提前创建`GranularBackend`/`MpmBackend`接口。Pradhana的多相MPM工作可作为模型证据，但其双网格、半隐式动量交换和塑性return mapping说明MPM是显著的新求解器，而非一次局部重构：<https://escholarship.org/uc/item/52b8b82q>。

低成本侵蚀门可使用Shields类无量纲阈值作为数据定义依据，而不是方向性support偏置；相关阈值本身具有实验不确定性，所以M8只承诺在冻结material range内的游戏化一致性，不宣称河工预测精度：<https://www.usgs.gov/publications/criteria-incipient-motion-spherical-sediment-particles>、<https://www.sciencedirect.com/science/article/pii/S1001627923000756>。

---

# 22. 性能与调度报告

M8不解决生产GPU backend，也不改wall-time阈值。每个milestone benchmark记录：

```text
solver total step ms
granular occupancy/pressure-coupling ms
drag/buoyancy ms
collision preview ms
deposit relaxation/wake ms
phase-transfer ms
active parcels / cap
deposit cells/groups / cap
dirty/sleeping cells and sleeping SVQ ratio
granular heap estimate / allocations / GC
v4 snapshot bytes
v4 region-state bytes/checkpoint ms
WAL bytes/fsync ms
REDUCED/PAUSED transitions and exact cause
```

至少比较：

1. granular gate off；
2. gate on但无任何granular inventory；
3. 1 BV active settling；
4. 大部分已sleep的代表性pile；
5. precipitation+local dissolution；
6. hard-cap前的局部强扰动。

候选性能门：gate-on/empty相对baseline median overhead≤5%；典型1 BV granular stage p95≤10 ms；sleep状态成本随dirty surface/active parcel增长，不随全deposit SVQ线性增长。最终阈值由M8.0同机实测冻结。若失败，先profile和缩小active范围/更新率/宏观状态，不能放宽通用`REDUCED >40 ms`或`3×>200 ms → PAUSED`语义。

---

# 23. 实机 smoke

自动门全部通过后只做自动化难观察的最小场景。

## Smoke A — 沉降、孔隙与安息角

1. 在小controller region中建立浅水和干平台。
2. 倒入同体积轻颗粒和重颗粒；观察浮/沉方向与活动parcel显示。
3. 让重颗粒形成pile，记录`granularstatus`的active/deposit/SVQ。
4. 等待sleep，确认客户端靠近、转视角和重新连接不唤醒。
5. 施加局部水流或删除一个support block，只观察局部wake。
6. unload/reload并重启，核对pile形态、porosity和ledger。

通过：没有穿墙、全pile爆醒、库存跳变或水面靠渲染假抬升；坡角与自动门一致。

## Smoke B — precipitation、再溶解与 WAL

1. 在chemistry+granular+precipitation门开启的region中倒入生成首批沉淀的两种溶液。
2. 用`chemstatus`和`granularstatus`记录combined species与solid SVQ。
3. 观察precipitate随fluid移动、沉降并进入deposit。
4. 加入使其再溶解的compatible solution，只让接触表面局部变化。
5. 玩家与dispenser各完成一次granular装取/放出。
6. unload/restart后核对registry fingerprint、HEAD generation、item contents和WAL目录收敛为空。

只有自动fault injection无法代表Minecraft actor durable-save边界时才做一次备份世界强制崩溃，不重演M6/M7所有实机场景。

---

# 24. 立即停止并回到设计的信号

出现任一项，不继续叠补丁：

- granular inventory被塞进liquid marker material/phase字段、M7 `ChemicalMixtureRecord`、region/protocol/WAL v3或tooltip。
- 用smooth liquid density、render fill、collision radius或bulk deposit volume结算排水。
- 从volume×referenceDensity重建已存mass。
- deposit被写入world `solid[]`，导致pore water退出pressure。
- parcel与deposit各有一套无法逐项对账的材料/组成真值。
- 普通support变化把整座deposit全量parcelize。
- 每tick扫描全部deposit cell或按总solid SVQ线性工作。
- 为通过安息角fixture加入方向bias、support chain、吸附、强制找平或材料特例。
- phase transfer先改M7 species，随后因parcel/group/capacity失败留下半个反应。
- precipitation修改solid volume却没有等量、可验证的liquid volume delta。
- indicator进入solid或沉淀后自动扩散给未标marker。
- v4缺registry时按当前datapack猜定义，或gate off时写回v3。
- granular container复用`volumeNanos`假装SVQ，或和chemical/material component双表示。
- actor durable save前删除v4 WAL。
- protocol/renderer发送或反写exact inventory。
- 调整40/200 ms阈值掩盖granular开销。
- 顺手持久化gas、实现GPU backend、修黑三角或液液锐化。
- 在低成本模型尚未完成统一形态门前预建MPM框架。

---

# 25. 文档、routing 与关闭产物

M8.0通过后，把冻结事实从本archive proposal提炼到正式architecture；长期真值不能只留在提案。M8关闭至少更新：

```text
docs/architecture/granular-state.md（新）
docs/architecture/fluid-solver.md
docs/architecture/chemistry-state.md
docs/architecture/world-ownership-and-containers.md
docs/architecture/region-state-v1.md（版本路由说明）
docs/maintenance/routing.md
docs/maintenance/next-session-initial-prompt.md
docs/progress/README.md
docs/progress/m8-granular-deposition.md（新）
docs/roadmap/product-roadmap.md
docs/roadmap/gases-and-granular.md
docs/roadmap/materials-and-chemistry.md
docs/roadmap/cross-cutting-contracts.md
docs/roadmap/world-integration.md
README.md
```

其中必须明确把未来gas persistence改称下一未占用region-state版本，避免与M8 v4同名。`routing.md`新增granular职责与一个最近selector，并说明何时追加region-state/containers/protocol/chemistry；不要把M8全部门累计到日常修改。

---

# 26. 最终放行清单

- [ ] M8.0 SVQ、parcel/deposit、porosity、drag、pressure source、phase transfer和v4版本ADR已通过。
- [ ] 低成本模型选择有prototype数据，未预建MPM生产框架。
- [ ] granular registry snapshot权威、bounded、fingerprinted，datapack变化不重解释库存。
- [ ] active parcel exact SVQ/mass/momentum/composition经split/merge/collision闭合。
- [ ] moving-solid capacity source与pressure/RHS/matrix/projection共享几何。
- [ ] heavy/light/neutral、terminal velocity与two-way momentum门通过。
- [ ] deposit skeleton/bulk/porosity分层正确，pore water仍参与pressure。
- [ ] repose、orientation、no-creep、80% sleep和local wake门通过。
- [ ] precipitation/dissolution与M7联合species/composition/charge/mass/volume账本闭合。
- [ ] config v5默认关闭并满足gate依赖。
- [ ] region-state v4与v1/v2/v3→v4 crash-safe migration通过；旧golden bytes不变。
- [ ] `GranularContents v1`与WAL v4通过actor durable-save所有边界；旧reader保持。
- [ ] strict protocol v4与bounded display通过；客户端不含exact truth、不唤醒server。
- [ ] granular gate off的M0–M7活动路径逐bit退化。
- [ ] 典型active与sleep fixture没有迫使修改wall-time pacing。
- [ ] 需要的item/model/texture/recipe/feedback/`en_us`/`zh_cn`齐全。
- [ ] 两个最小实机smoke通过，或有证据说明无需人工观察。
- [ ] architecture/routing/progress/roadmap/README同步。
- [ ] 按风险选择的完整门、clean offline build、server startup、版本、部署JAR与SHA-256已记录。

最终成功语义是：

> M8 在CPU权威的有界controller域内加入了可持久、可转移、可反应的颗粒固体真值。活动颗粒以受控parcel完成浮沉与双向阻力，稳定库存守恒收敛到保存孔隙和混合组成的deposit cell；真实骨架体积精确排水，孔隙水继续参与同一压力；沉淀/再溶解与M7化学联合闭合；静态成本随局部扰动而非总库存增长。任何旧格式、功能门关闭路径或客户端显示都不能吞掉、猜测或改写这些真值。
