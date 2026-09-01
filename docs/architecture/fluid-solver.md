# CPU 流体求解器契约

`FluidDomain` 是唯一活动物理聚合根，CPU 结果是规范真值。Minecraft 编排、世界方块、网络、持久化和 GPU probe 不进入数值核心。

## 状态分层

- **物理真值**：marker 体积、材料质量、化学 parcel、固体 SVQ/质量/组成、气体库存和各载体内能。
- **数值离散**：MAC 速度/压力、守恒 marker footprint 压力相、平滑 volume、PLIC、cut-cell、曲率和目标散度。
- **渲染重建**：客户端 density、界面、颜色、颗粒 sample 和温度量化。
- **调度状态**：ACTIVE、REDUCED、休眠、PAUSED、UNLOADED。

后三层不能创建、删除、缩放或修复第一层库存。

`FluidDomain` 仍是唯一 live 对象和唯一 public aggregate surface，但实现不再集中在一个四千行文件。package-private 的 `FluidDomainStorage`、`Contracts`、`MechanicalFields`、`StepCoordinator`、`InventoryTransactions`、`CollisionState`、`Capabilities` 与 `GasAndGranular` 按职责形成同一对象的实现分区；它们不是可独立构造的 owner，也不复制数组、generation 或 commit point。新增行为应落到拥有该不变量的分区或既有 stage，不得把无关 convenience method 重新堆回 public leaf。

力学子步由 `MechanicalStepPipeline` 与各专职 stage 组合。stage 不接收或保存 `FluidDomain`：稳定的 SoA/workspace 引用在构造时显式注入，可能随 lifecycle 重新绑定的状态通过各 stage 自己的窄 `Access` 契约取得。不同 stage 不能借 aggregate 任意读写彼此 scratch；pressure anchor/reference/result 等跨阶段值只能走声明过的写回缝。

非力学轴由 `ThermochemistryPipeline`、`GranularTransitionStage` 与 `ParticleReseedStage` 组合。`ThermochemistryPipeline` 是传导、液相反应、颗粒析出/溶解、凝聚相变和气液相变的唯一生产顺序源；`ThermochemistryStageAssembly` 只接线，各 stage 只持有明确 workspace/capability seam。固液与气液 stage 共享 `PhaseChangeStageSupport` 的 exact composition、carrier removal、pending volume 与受控 marker mutation 操作，但不共享可任意调用的 aggregate 引用。`GasPhaseInterfaceResolver` 只解析一个 liquid cell 邻接的 distinct sealed owners 和它们的饱和分压失衡；extent 搜索及 commit 仍唯一属于 `GasLiquidPhaseChangeStage`。

thermal runtime 直接使用 `ThermalDomainState`。`FluidThermalBinding` 只拥有 fluid carrier snapshot、restore、reseed 与 gas-compliance 的类型映射；它不保存库存，也不提供第二套 thermal mutator。capture/restore 继续由 `FluidDomainStateMapper` 汇总，所以删除旧 thermal facade 不改变 candidate、region HEAD 或账本契约。

### 后端候选边界

完整 backend handoff 除库存真值外还携带仅供紧邻候选继续的冻结输入与有界调度进度：world thermal face field 只属于本次输入，granular dirty/quiet/rotation 只属于局部工作游标；两者都不能反向恢复库存，也不进入 region HEAD。省略它们会分别丢失真实边界通量，或使每 tick 私有重建永远达不到沉积休眠。

`FluidDomainState` 捕获恰好一种 liquid carrier 布局（pure/material/chemical）及其 registry、颗粒、气体、热学、相变 pending work、求解配置和硬预算；还显式携带只服务本次私有候选的 chemistry/granular 调度列、world-derived thermal face field 与最近一次真实 mechanical pressure projection。后三类不是库存：不进入 region checkpoint，也绝不用于反向恢复 carrier、gas 或 `U`。pressure 之外的平滑 density、mesh 与 Minecraft 对象不在其中；capture/restore 的装配顺序由 `FluidDomainStateMapper` 集中维护。运行中的 hydrostatic thermal checkpoint 冷恢复后先唤醒一次 motion，以重建没有持久化的真实 pressure projection，再允许 thermal-only 相平衡消费它。`FluidStepRequest` 另外携带有硬上限的 moving-body 输入；后端先在私有 state 上提交这些 marker 动量，再执行同一 logical step。`FluidSolverBackend.evaluate(FluidStepRequest)` 必须返回 base generation 的紧邻完整后继，并随 candidate 回报该批输入实际写入液体的合计体积和冲量。

hydrostatic sleep 期间的非运动轴使用 `FluidThermalStepRequest` / `FluidThermalStepCandidate` 和同一个 backend id/generation/layout adoption 门。CPU backend 也必须先恢复私有 aggregate，再顺序执行 conduction、reaction 与 phase；任一后序拒答都会丢弃整个私有 candidate，不能在 live domain 留下已提交的前序 stage。成功的 CPU candidate 直接 single-use 转移这份已经求值的私有 aggregate，避免正常帧再做 candidate capture→restore；只有 checkpoint、重播和诊断需要 immutable state 时才在转移前按需物化。转移后 `state()` 必须拒绝，因为 aggregate 已成为可变 live owner，不能生成伪装成该历史 generation 的迟到快照。其他进程或 GPU backend 可以直接提供 eager immutable state，但必须满足同一完整语义。`FluidDomainState.Layout` 固定网格/config/预算、sealed boundary、carrier kind、全部 registry/capability identity 与 thermal budget；后端不得借一次合法 step 替换这些定义。`RegionApplicationService` 在求值后校验，`RegionRepository` 在 atomic adopt 前按 live layout 重验。accepted outcome 明确携带本步实际执行的 transport、chemistry、granular reaction 与 condensed/gas phase 诊断；motion `StepStats` 另携带 granular transition 诊断。调度器不得从 restore 后的默认 diagnostics 读取本步活动。当前 `CpuFluidSolverBackend` 是规范实现，GPU probe 尚不满足这份完整状态契约，不能作为生产后端注册。

`FluidStepRequest.mechanicalAuditEnabled` 是只读诊断选择器。启用时，CPU candidate 的 `StepStats` 记录 P2G、颗粒/多孔耦合、重力、热浮力、表面张力、固定边界、pressure projection、G2P、碰撞、载体库存转移和 reseed 各阶段的动能变化，并同时报告初末重力势能、热学库存机械功以及逐阶段 telescoping closure。该审计不进入 `FluidDomainState`、HEAD 或任何库存，也不改变正常未启用请求的物理结果。

## 网格与 marker

- 默认 cell size 为 0.5 m；每个 Minecraft block 对应 2×2×2 cells。
- 每个 marker 的 raw float `particleVolume` 是液体体积真值，物理总量是这些二进制有理数的精确和；逐格 marker sum 只由该真值正向汇总。顺序相关的 float/double reduction 只能作带解析舍入界的诊断，不能替代 exact ledger。
- `volume[]` 是 PIC 平滑、展示和既有表面几何的派生场，不参与库存或 pressure phase 结算；pressure 使用独立的守恒 marker footprint。
- P2G/G2P 使用共享的 valid face；均匀密度时 face 分子/分母按 marker 体积散射，变密度时按 marker 质量散射，并在归一化前保留同一份 P2G face weight 供动能度量。MAC 空气外推从开放压力面播种，G2P 不采未定义空气零值。三轴 face splat 直接写入固定 SoA，不为每个 marker/axis 创建 callback；上一 logical step 末已完整发布且 `gridDirty == false` 的派生网格可供下一步首个 substep 直接使用，particle advection 与任何 carrier edit 都会重新置 dirty，后续 substep 不能跳过重建。
- G2P 不使用固定 FLIP/PIC 比例。对全体 marker 写成 `v(alpha) = v_pic + alpha * (v_old - v_p2g)`，解析选择 `[0,1]` 内不超过 `max(E_pic, E_projected_grid)` 的最大 `alpha`；纯 FLIP 能量可接受时严格取 `1`，否则才退到能量可行边界。这是传递算子的全局能量约束，不是逐粒子速度钳制，也不冒充黏性。registry 已保存材料黏度，但当前连续液体动量方程还没有黏性扩散 stage。
- reseed 以标准 `2×2×2` marker quadrature 的单 marker 体积决定逐格目标数；split 保持体积、质量、动量和质心，merge 还要求两个 raw float 体积之和可由一个 float 精确表示，否则保留 residual marker 并报告 conservation refusal；不跨 material 或 phase。GPU 的相同判定必须以禁止代数重排的精确求值执行。

### 内存布局

活动核心使用固定容量的 structure-of-arrays：

- cell-centered：`pressure`、`divergence`、`markerCount`、平滑 `volume`、精确 cell marker volume/mass、材料密度和表面张力工作数组；
- face-centered：三轴 MAC velocity、P2G 权重、valid mask 与 cut-face aperture；
- marker：位置、速度、raw float 体积、double 质量、材料 handle 和 phase label；
- 正交 side column：chemical handle/indicator、granular carrier、thermal U 和 gas-node lineage。

运行时 handle 只在冻结 registry 内有效。跨 checkpoint、物品或网络边界必须转换为 stable ID、canonical palette index 或完整 ordinal 表。

marker 数和所有 side column 使用同一个 swap-remove/append 顺序。新增、拆分、合并、相变或删除 marker 时，调用方必须在一次提交中更新全部已绑定 side column；只移动基础 marker 而留下化学或能量 ordinal 属于结构损坏。

### 时间步与 CFL

配置中的 `timeStep` 是 logical step 时间；mechanical core 根据最大 face speed、cell size 与 `maxCourantNumber` 选择子步。子步只提高数值稳定性，不改变本 logical step 的外部 source/sink 或相变预算。wall-time pacing 只改变 logical step 被调度的 tick 间隔，也不扩大物理 `dt`。

## Logical step

1. 读取当前 geometry、cut-cell、carrier occupancy 和边界条件。
2. 体积/质量加权 P2G，施加重力、热浮力、气压、颗粒和实体的派生力。
3. 构造物理目标散度并求解 pressure projection；该解是本步对外 pressure/机械功真值。
4. 以投影后网格动能约束 G2P 的最大 FLIP 增量；marker 位置沿与 MAC 离散散度一致的二阶 `H(div)` 场以 RK4 推进，再进入 CCD；partial cut-face 暂沿用 cut-cell 插值与 CCD。
5. 在传导前冻结颗粒反应 dirty cell，随后依次执行有限体积传导、液相反应、颗粒析出/溶解、凝聚相变和气液相变；两类相变共享同一操作预算。
6. 执行 active/deposit compaction、沉积与局部唤醒。
7. 经 `CarrierMutationCoordinator` 执行 reseed，同时搬运 chemistry/thermal side column。
8. 只有整个私有 candidate 成功后才替换诊断并发布完整后继。

相变产生的 pending volume/work 由下一次真实 projection 按该次绝对压力 exactly-once 消费，因此 pressure step 是唯一机械功提交点。相变提交已经改变 marker 几何，pending volume 不再重复写入目标散度。

自由面平衡夹具必须区分两种初态：液体几何先建立、随后按剩余空间初始化环境气体，表示无外部做功的半满平衡态；气体先按空腔初始化、随后向密闭域注液，则是压缩气体的 external-source 操作，必须保留相应气体内能和压力功，不能拿前一种夹具的环境态覆盖或归零。

## 自由面与压力

- 开放环境和真空液面使用 Dirichlet pressure；接触液体的有限密闭 gas node 以状态方程导出的集总可压缩 pressure unknown 进入同一个 projection。两者都只走唯一液气 face 通量，不另加气体体力或材料分支。
- 物理 pressure projection 的不可压目标散度为零（再叠加表面张力、移动固体等真实边界项），不接收 marker 分布误差或逐格过容量作为机械源项。marker 聚集只由散度一致平流、CCD 与守恒 reseed 处理；不得另造目标散度、位置投影或体积修正来掩盖传递误差。
- 表面张力使用 PLIC/相分数构造曲率和 balanced-force 压力项；`sigma = 0` 不产生额外力。
- 液液相共享机械 pressure/velocity，但保持材料身份、界面张力和密度分层。

pressure 位于 cell center，速度位于 face。离散目标是：

```text
div((1 / rho) grad(p)) = (div(u*) - target_divergence) / dt
```

- 每个 marker 按由标准 `2×2×2` 四分之一格点诱导的 C1 紧支撑核，把自身真实体积守恒分配到开放邻格；该 footprint 决定 pressure fraction/PLIC，不改 marker 库存，也不使用展示 `volume[]`。
- pressure unknown 只包含其重建 PLIC 真正覆盖 cell center 的开放格；满格直接进入，部分格以 plane/center 几何判定。含 marker 但只占据中心以下薄层的格仍是液体 carrier，可参与重力、平流和补充界面力，却不能仅凭 marker 存在强行取得 pressure unknown。这样 pressure 矩阵、ghost-fluid 距离与载体拓扑使用同一界面几何，不再靠极小 `theta` 或 marker override 制造薄压力层。
- 重力、热浮力和速度载体 face 仍由实际 marker occupancy 激活，不能因 pressure footprint 变宽而给孤立液滴稀释重力。
- cut-face aperture、界面距离和 `1/rho` 面系数同时进入矩阵、预条件器和最终投影，避免三个路径使用不同几何。
- 变密度 face coefficient 按两侧界面距离守恒加权；同密度材料必须退化为纯水矩阵。
- PCG 对活动 liquid cell 与接触液体的有限 sealed gas node 联合求解；`pressureTolerance` 是整个 block system 的绝对 RMS 代数残差，不随 RHS/静水载荷放大。同一次 projection 的 fluid unknown 集、face mobility、diagonal 与 gas storage 在迭代前装配一次，PCG 只在该固定 block operator 上更新向量；迭代中不得重复重建物理系数，也不得改变相分类。迭代预算耗尽、breakdown、非有限残差或无法闭合有限库存/容量 owner 的 gas topology 会以各自 typed failure 拒绝完整候选并把生产区域标记 `NUMERICAL_PAUSE`，不会以未收敛压力或部分 gas remap 继续 advection。其他 `IllegalStateException` 不在该边界被宽泛吞掉。
- 环境表压为 gauge zero。gas topology 的 runtime lineage 还会沿连通 pressure-air fringe 扩展到实际 ghost face，但不会穿过 pressure-fluid bulk。只有密闭气体边界时，求解器先减去一个统一 gas gauge reference，PCG 只承受相对压力；对外压力观察、相平衡与 pending `-p ΔV` 再加回该 reference。多个 gas lineage 之间的压差不被消除。
- moving-solid occupancy 只通过物理 `target_divergence` 进入 projection，不直接缩放 marker；相变 pending 只结算已经发生的 marker 几何变化对应的压力功，不能再次制造一份散度源。

### 液体与可压缩气体的能力边界

当前空间动量网格仍只承载凝聚相；`AirNetwork` 保存密闭气体的组分、质量与 `U`。开放环境和真空仍是液面 Dirichlet pressure，接触液体的有限 sealed node 则以一项集总 pressure unknown 与液体联合求解：无热状态使用 `V/p_abs`，热状态使用 `V*beta_s = V/(gamma*p_abs)` 作为体积 compliance。该 unknown 只是当次 projection scratch；下一次 topology refresh 仍从唯一 inventory/volume/`U` 计算非线性 EoS 与绝热功，不保存或反算第二份气体库存。它消除了把有限气腔整步冻结成 pressure boundary 的显式滞后，但仍不解析腔体内部逐 cell/face 动量、声波或混合层。

液气 face 的有向跳跃约定为 `J = p_liquid(interface) - p_gas(interface) = sigma*kappa`。若两侧 center 到界面的距离（以 cell width 为单位）为 `d_l,d_g`，同一 face mobility 与速度修正为：

```text
m_f = rho_ref / (rho_l*d_l + rho_g*d_g)
u[n+1] = u* - dt/(rho_ref*h) * m_f * (p_g - p_l + J)
```

当前 node 没有空间 gas inertia segment，因此严格取 `d_g = 0`，恰好退化为既有 one-sided ghost-fluid 消元；未来两侧都有 pressure unknown 时使用同一 series mass resistance，而不是按密度比切算法。一个 face 向 liquid/gas 两行加入相同对角与相反 off-diagonal，`J` 以等量反号进入 RHS，所以矩阵保持对称，静态 `p_l-p_g=J` 不产生冲量。集总 node 行另加 `rho_ref*V*beta_s/(dt^2*h^3)` 储存对角；真空没有 continuum unknown。

统一气液空间算法复用同一守恒 P2G/G2P、`H(div)` 重建、平流和碰撞算子；相别差异只进入本构闭合。对冻结组分的局部绝热响应，令 `beta_s = 1 / (rho*c²)`；压力隐式步满足：

```text
beta_s * (p[n+1] - p[adv]) / dt + div(u[n+1]) = 0
u[n+1] = u* - dt * (1/rho) * grad(p[n+1])

-div((1/rho) grad(p[n+1])) + beta_s/dt² * p[n+1]
    = -div(u*)/dt + beta_s/dt² * p[adv]
```

这里 `p[adv]` 是随质量/组分/能量一致平流到控制体的热力学压力，不是凭空保留的显示场。`beta_s → 0` 时 Helmholtz 储存项严格退化为当前不可压 Poisson projection；气体使用状态方程给出的非零 `beta_s`，不需要按材料 ID 选择另一套压力算法。空间实现还必须以守恒通量更新质量、组分、动量和**总能量**，并把 pressure work 纳入同一候选；只把该储存项加到当前液体矩阵、却不建立这些权威列，不能记为已实现空间气体。低密度、高密度比、混合与相变必须由这些方程自然产生，不能用固定 FLIP 比例、速度钳制或渲染修补代替可压缩闭合。

### PLIC、表面张力与润湿

自由面由 cell liquid fraction 与局部法向构造 PLIC。ghost-fluid 距离决定 fluid-air pressure face；液液界面则从逐相体积分数字段构造 pairwise 界面。

液气压力跳跃满足 `delta p = sigma * kappa`：有 pressure unknown 的已解析界面走 sharp ghost-fluid jump；两侧都没有 pressure unknown、但 marker carrier 跨面的亚格界面走互补 balanced-force CSF，二者拓扑互斥。液液张力使用 phase 无序对定义，以 balanced-force CSF 接入同一 pressure 路径。液气曲率法向始终只来自液气几何；润湿由 Young 表面能 `sigma_sl - sigma_sg = -sigma cos(theta)` 的切向离散梯度产生，固壁面积取自同一 `2×2×2` cut-cell microgeometry（含域边界），不旋转曲率法向，也不使用浅水吸墙、墙角偏置或材料 ID 特判。

当前 `gpu-probe-kernel` 只覆盖单一常密度不可压 carrier 的 pressure 几何、P2G/G2P、散度一致平流、碰撞和守恒 reseed。它尚未承载材料质量列、变密度、液气/液液表面张力与 Young 壁能、gas boundary、热化学或完整 immutable candidate，因此这些 CPU 能力不能被记作 GPU 已实现；probe 继续不具备生产资格。

当前不可互溶相共享一个机械速度场：系统承诺静水压力、密度分层和界面张力，不声称解析界面切向滑移、乳化或湍流液滴破碎。

## 固体与碰撞

- Fabric 侧 `MinecraftCollisionWorldPort` 将 Minecraft `VoxelShape` 保守采样成不可变 `CollisionField`；core 再把 micro mask 转成 cut-cell face aperture。pressure、预条件器、投影和 marker CCD 使用同一几何。
- CCD 只截断命中法向，切向保持自由。
- 世界碰撞变化挤出的液体必须从 marker 账本得到实际量；不能用格密度推断 escaped volume。
- granular、deposit 和 coherent solid occupancy 与 liquid 共享容量和压力排水路径。

`FlipCutCellGeometry` 从每格 `2×2×2` unsigned micro mask 得到 cell solid volume 和三轴 face aperture。checkpoint 与后端 handoff 保存 raw mask，不能退化为“整格固体/整格空气”；恢复后必须得到相同 aperture。`FlipParticleCollision` 对 marker 轨迹做连续碰撞检测，避免只在步末投影位置造成穿薄墙；碰撞只去除命中法向速度，切向分量保持。

实体交互由 Fabric 每个 Minecraft tick 冻结有界 AABB、原版更新后的实体响应速度、本 tick 实际位姿差和流体观察。core 将 AABB 与当前派生的水平液层逐格求交；每格液层按开放 `2×2×2` micro layer 自下而上分配，所以浸没体积和湿区质心随子格位移连续变化，并排除 `VoxelShape` 已占据的 micro volume。这个观察不改 marker 库存，也不是压力面积分。实体侧响应仍按游戏 tick 应用；liquid moving boundary 使用 `position - oldPosition` 的实际当 tick 位移速度，不能把原版已施加重力/阻力、供下一 tick 使用的 `deltaMovement` 反向当成本 tick 表面运动。moving-body 输入只能在 region 可运动且 pacing 到期的真实 logical step 消费一次，PAUSED、thermal-only 和被 pacing 跳过的 tick 不得提前或重复改写 marker。core 使用紧支撑二次 B-spline 核，在湿体积上把实体运动约束写入附近 marker 速度；返回值由实际 float velocity 写入重新计算 `kg·m/s`，体积、质量、组成和 `U` 不变。该 marker 动量会经过正常 P2G、pressure projection、G2P 和 advection，因此走动、划水和入水速度可以扰动液体，而不是只唤醒调度。

当前 AABB 湿体积是有界 kinematic immersed-boundary 近似：Minecraft 实体运动被视为外部 moving body，不声称已有刚体质量真值或严格等量的实体侧反冲 ledger；实体占位也尚未进入 cut-cell 容量。推进到压力面积分和严格双向闭合前，必须验证静水浮力的采样误差、给出黏性/形状阻力有效域、把实体表面速度接入 pressure boundary，并让实体占位通过统一容量路径排开液体。不得用额外速度钳制或材料特判掩盖这些停止门。

## Reseed 与传输不变量

- split 把一个 marker 的体积、质量、动量和所有 side truth 按同一权重分给两个子 marker；merge 只在 material、phase 和化学可合并性一致时进行。
- merge 只有在目标 raw float volume 能精确表示两 marker 的数学和时才提交；否则 marker 数可暂时高于 nominal target，但 exact 体积/质量优先，诊断必须区分 geometry、budget 与 conservation refusal。
- marker 预算不足时先拒绝整个候选，不允许创建基础 marker 后再丢弃 side column。
- insert/extract 先完成容量、可表示尾数、物品形状和事务预检，再修改数组。
- 数值清理只能删除零或被显式转入 sink 的库存；“太小而看不见”不是删除理由。

## 客户端重建边界

服务端快照只发送量化展示字段和 marker seed。外表面从总液相 occupancy 重建；液液界面由 material partition 单独重建，并保证共享界面单一归属和稳定法向。客户端卷积、iso threshold、法线平滑和三角拓扑不能反馈到 pressure phase 或材料账本。

## 调度

motion 与 thermal activity 分开评估。调度只决定何时执行相同 logical step：

- 单步超过 40 ms 后进入 1–4 tick pacing；
- 连续 3 步超过 200 ms 自动暂停；
- 平静区域可按守恒条件休眠；
- checkpoint/UNLOADED 必须保存或恢复完整物理真值。

阈值只在代表性 profile 已分离 solver、世界交互和 readback 成本后评审。

## 数值原则

- 优先使用明确的守恒离散、有限体积通量、PLIC、PCG、balanced-force、Boussinesq 等通用算法。
- 不以墙角偏置、强制找平、渲染增厚或按 tick 百分比源汇掩盖异常。
- 新近似必须声明适用尺度、停止门和可观测误差；先选择简单有效的模型，再考虑更重框架。
