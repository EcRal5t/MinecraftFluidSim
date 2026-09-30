# CPU 流体求解器契约

修改压力、自由面、粒子运动、碰撞或后端状态交接时读本文。

## 修改入口

| 要改什么 | 代码入口 | 测试选择器 |
|---|---|---|
| 计算顺序与状态捕获 | [FluidDomainStepCoordinator](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainStepCoordinator.java)、[MechanicalStepPipeline](../../solver/src/main/java/cc/ecisr/mmm/fluid/MechanicalStepPipeline.java)、[FluidDomainStateMapper](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainStateMapper.java) | `lifecycle` |
| 压力和自由面 | [PressureProjectionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureProjectionStage.java)、[PressureSystem](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureSystem.java)、[PressureFaceCoupling](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureFaceCoupling.java) | `free-surface,gas-liquid` |
| 速度传递与平流 | [FlipTransferScheme](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipTransferScheme.java)、[GridToParticleStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GridToParticleStage.java)、[ParticleAdvectionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/ParticleAdvectionStage.java) | `free-surface` |
| 局部形状碰撞 | [FlipCutCellGeometry](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipCutCellGeometry.java)、[FlipParticleCollision](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipParticleCollision.java) | `cut-cell` |
| 粒子拆分和合并 | [FlipParticleReseeder](../../solver/src/main/java/cc/ecisr/mmm/fluid/FlipParticleReseeder.java)、[CarrierMutationCoordinator](../../solver/src/main/java/cc/ecisr/mmm/fluid/CarrierMutationCoordinator.java) | `reseeding` |
| 后端结果提交 | [FluidSolverBackend](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidSolverBackend.java)、[RegionApplicationService](../../application/src/main/java/cc/ecisr/mmm/application/RegionApplicationService.java)、[FluidRegionRepository](../../fabric/src/main/java/cc/ecisr/mmm/simulation/FluidRegionRepository.java) | `lifecycle` |

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [状态分层](#状态分层)
- [网格与 marker](#网格与-marker)
- [Logical step](#logical-step)
- [自由面与压力](#自由面与压力)
- [固体与碰撞](#固体与碰撞)
- [Reseed 与传输不变量](#reseed-与传输不变量)
- [客户端重建边界](#客户端重建边界)
- [调度](#调度)
- [数值原则](#数值原则)

`FluidDomain` 保存运行中的物理状态，CPU 结果作为计算基准。世界读写、网络与存档由外层模块负责。

## 状态分层

| 数据 | 内容 | 用途 |
|---|---|---|
| 物理原始数据 | 粒子体积与材料质量、化学组分、固体数量与组成、气体库存和各载体内能 | 计算库存与收支 |
| 数值工作区 | MAC 速度与压力、压力相、PLIC、局部碰撞几何、曲率和目标散度 | 完成本次物理计算 |
| 展示数据 | 客户端密度、界面、颜色、颗粒样本和量化温度 | 生成画面 |
| 调度状态 | 运行、降频、休眠、暂停和卸载 | 决定计算时间与内存驻留 |

库存变化由物理原始数据决定，数值工作区和展示数据按需重建。

### 实现职责

`FluidDomain` 的实现按职责分布在包内父类中，共用同一对象和数组：

- `FluidDomainStorage`：存储。
- `FluidDomainContracts`：状态检查。
- `FluidDomainMechanicalFields`：力学工作区。
- `FluidDomainStepCoordinator`：计算顺序。
- `FluidDomainInventoryTransactions`：库存事务。
- `FluidDomainCollisionState`：碰撞状态。
- `FluidDomainCapabilities`：功能配置。
- `FluidDomainGasAndGranular`：气体与颗粒接入。

新增行为放入负责该数据的实现类或计算阶段。`FluidDomainStateMapper` 统一装配捕获和恢复过程。

`MechanicalStepPipeline` 组织力学阶段。构造时向各阶段注入所需数组和工作区，生命周期中会重新绑定的数据经该阶段的 `Access` 接口取得。跨阶段的压力参考值和结果通过明确的写回接口传递。压力分类、流通面与颗粒占据率导数使用子步初态的同一份几何；联合压力求解同时更新液体和颗粒速度，随后预览颗粒碰撞与容量。

`ThermochemistryPipeline` 按传导、液相反应、颗粒反应、固液相变和气液相变的顺序执行。`ThermochemistryStageAssembly` 负责注入依赖，后续沉积和粒子拆并分别由 `GranularTransitionStage`、`ParticleReseedStage` 执行。

固液与气液相变共用 `PhaseChangeStageSupport` 的组成、载体移除、待结算体积和粒子修改操作。`GasPhaseInterfaceResolver` 查找液体单元相邻的密闭气腔，并按饱和分压失衡排序；`GasLiquidPhaseChangeStage` 决定实际反应量并提交。

热学状态由 `ThermalDomainState` 保存。`FluidThermalBinding` 负责流体与热学之间的快照、恢复、粒子拆并和气体压缩响应类型映射。

### 后端候选接口

`FluidDomainState` 包含一种液体载体布局（pure/material/chemical），以及对应注册表、颗粒、气体、热学、待同步相变几何、求解配置和预算。

液体黏性应力和颗粒阻力共同读取按格温度派生的 `mu(T)`。`bindMaterialDefinitions` 独立于颗粒能力绑定 world `MaterialRegistry`；`bindGranular` 复用同一校验与绑定。`FluidDomainState` 携带不可变定义，`Layout` 包含其 fingerprint；纯材料、化学、颗粒和热学区域冷恢复均从已提交世界能力包绑定。缺黏度段、基础物性冲突、重绑定不同定义均在修改域之前拒绝。未绑定定义的独立数值测试域维持无黏度模型。

交给下一次计算的输入还包含三类临时数据：化学与颗粒调度进度、世界热交换面、最近一次真实压力投影。它们随计算状态交接。区域 checkpoint 保存的字段另有定义，见 [区域存档](region-state.md)。丢失热交换面会漏掉实际热通量；反复重置颗粒调度进度会使沉积永远无法休眠。

静水热区域从存档恢复后，先唤醒一次力学计算，重建压力投影，再允许单独热计算使用它判断相平衡。

`FluidStepRequest` 还可携带有数量上限的实体运动输入。后端在私有状态上施加粒子冲量，再执行完整计算步，结果报告实际作用于液体的体积和冲量。用于同步相变几何的请求要求实体输入为空、相变和机械诊断审计关闭，由同一后端选择仅含力学的流程。

力学结果使用 `FluidStepCandidate`，静水热计算使用 `FluidThermalStepCandidate`。两者都返回原代次的紧邻后继，并接受后端标识、代次和完整布局检查。`FluidDomainState.Layout` 固定网格、配置、预算、外壳、载体种类、注册表、功能身份、热学预算与材料定义指纹。

`StepStats.carrierViscosity` 累计本步各次属性构建中超出材料声明温区的格数，以及按构建顺序、cell 顺序选取的首个样例（材料 ID、实际温度、声明温区）。它按「格 × 属性构建批次」计数，同一批次被多个 parcel、Darcy 面或床面重复读取只算一次；失败或细化重算的候选不发布统计。状态报告读取区域保存的最后一个成功步结果，不触发物性或温度重建。

CPU 在私有对象上顺序执行计算，任一阶段失败就丢弃整份候选，保留当前区域状态。成功后可直接交接这个对象一次。需要存档或诊断快照时，提交前调用 `state()` 生成不可变状态；提交后该对象已成为活动区域，历史候选的 `state()` 会拒绝调用。完整提交过程见 [运行时与区域操作](runtime-and-lifecycle.md#candidate)。

初始子步数由液体、颗粒速度与重力预测的 Courant 数决定。压力加速后的液体 MAC 速度与颗粒速度还要在碰撞预览前复检。Courant 超限或颗粒非线性阻力迭代未收敛时，`MechanicalTimeRefinement` 携带所需子步数，CPU 后端丢弃候选，从原请求重新恢复并细化整个力学步；最多细化十六次，仍无法满足条件则拒绝候选。重算包含实体输入、气体状态与待同步相变几何，成功候选只提交一次。直接调用可变 `FluidDomain.step()` 的诊断代码会收到该细化异常；生产调用统一经过后端。

成功结果携带本步输运、化学、颗粒反应和相变诊断。力学 `StepStats` 另含颗粒沉积与唤醒诊断。调度器据本步结果判断活动状态。

`FluidStepRequest.mechanicalAuditEnabled` 控制可选机械诊断。启用后，`StepStats` 记录速度传递、颗粒与多孔耦合、重力、浮力、张力、固定约束、压力、碰撞、库存转移和粒子拆并各阶段的动能变化，并报告初末重力势能、热学机械功与各项收支的合计误差。该开关增加诊断记录，物理计算和存档库存保持相同规则。

## 网格与 marker

- 默认 cell size 为 0.5 m；每个 Minecraft block 对应 2×2×2 cells。
- 每个 marker 的 raw float `particleVolume` 是液体体积原始数据，物理总量是这些二进制有理数的精确和；逐格 marker sum 只由该原始数据正向汇总。顺序相关的 float/double reduction 只能作带解析舍入界的诊断，不能替代 exact ledger。
- `volume[]` 是 PIC 平滑、展示和既有表面几何的派生场，不参与库存或 pressure phase 结算；pressure 使用独立的守恒 marker footprint。
- P2G/G2P 使用共享的 valid face；均匀密度时 face 分子/分母按 marker 体积散射，变密度时按 marker 质量散射，并在归一化前保留同一份 P2G face weight 供动能度量。MAC 空气外推从开放压力面播种，G2P 不采未定义空气零值。三轴 face splat 直接写入固定 SoA，不为每个 marker/axis 创建 callback；上一 logical step 末已完整发布且 `gridDirty == false` 的派生网格可供下一步首个 substep 直接使用，particle advection 与任何 carrier edit 都会重新置 dirty，后续 substep 不能跳过重建。
- G2P 不使用固定 FLIP/PIC 比例。对全体 marker 写成 `v(alpha) = v_pic + alpha * (v_old - v_p2g)`，解析选择 `[0,1]` 内不超过 `max(E_pic, E_projected_grid)` 的最大 `alpha`；纯 FLIP 能量可接受时严格取 `1`，否则才退到能量可行边界。这是传递算子的全局能量约束，不是逐粒子速度钳制，也不冒充黏性。连续液体的 Newtonian 黏性应力由独立的 `LiquidViscosityStage` 推进。
- reseed 以标准 `2×2×2` marker quadrature 的单 marker 体积决定逐格目标数；split 保持体积、质量、动量和质心，merge 还要求两个 raw float 体积之和可由一个 float 精确表示，否则保留 residual marker 并报告 conservation refusal；不跨 material 或 phase。GPU 的相同判定必须以禁止代数重排的精确求值执行。

### 内存布局

活动核心使用固定容量的 structure-of-arrays：

- cell-centered：`pressure`、`divergence`、`markerCount`、平滑 `volume`、精确 cell marker volume/mass、材料密度和表面张力工作数组；
- face-centered：三轴 MAC velocity、P2G 权重、valid mask 与 cut-face aperture；
- marker：位置、速度、raw float 体积、double 质量、材料 handle 和 phase label；
- 正交 side column：chemical handle/indicator、granular carrier、thermal U 和 gas-node lineage。

运行时 handle 只在冻结 registry 内有效。跨 checkpoint、物品或网络边界必须转换为 stable ID、canonical palette index 或完整 ordinal 表。

marker 数和所有 side column 使用同一个 swap-remove/append 顺序。新增、拆分、合并、相变或删除 marker 时，调用方必须在一次提交中更新全部已绑定 side column；只移动基础 marker 而留下化学或能量 ordinal 属于结构损坏。

### 液体黏性应力

`LiquidViscosityStage` 在外力和固定面法向速度处理之后、压力投影之前执行。`ViscousMacDiscretization` 沿用压力液体 owner 与开放面；参与单元同时须持有液体 marker。单元液体控制体为 marker 体积与当前可用体积的较小者，密度取真实质量/体积；每个开放 MAC 面由相邻参与单元各贡献半份 `rho*V`，换算到 kg。薄于压力分类分辨率的独立液滴继续由粒子输运，尚无连续黏性控制体。

离散使用单元法向应变和网格边上的剪切应变。法向行保留 `2*mu`，剪切行同时含 `du_a/dx_b + du_b/dx_a`，相邻四格黏度使用两次调和平均；开放剪切面积取四个支撑压力面的共同最小开口。每行形成非负的 `w*b*b^T`，同轴内部成对冲量用相反系数写入。

```text
M du/dt = -K u
M_ii = rho_i V_i
K = sum_rows w b b^T
L = max_i [ sum_rows w |b_i| sum_active_j |b_j| / M_ii ]
dt <= 2/L
```

`M^-1 K` 与对称半正定矩阵相似，`L` 是由已装配应力行计算的谱上界。Forward Euler 在负实轴上的稳定区为 `[-2,0]`；均匀标量扩散退化为 `dt <= C/sum(G)`。小切割体积直接进入容量，零开口没有通量。非正容量、无效物性、非有限 conductance 或谱界立即拒绝候选；有效但过大的步长通过现有私有候选重试机制细化，超过累计 4096 个子步预算即暂停。每子步重建，包含平流、占据率和温度变化。

接触气体的剪切支撑采用零 traction，sealed gas 与 ambient gas 相同。固定壁面保持法向不可穿透；`FluidSolverConfig.viscousWallCondition` 显式选择 `FREE_SLIP`（默认）或 `NO_SLIP`。后者按 cut-cell 所属固壁面积向两个切向速度面分配静止壁面阻力，使用随液体体积缩小的等效半格距离。这是当前 micro-mask 几何下的集总壁面近似；尚未重建斜壁上的速度梯度或引入 moving-body 表面速度。颗粒阻力与 Darcy 项继续由其专职阶段计算。

`StepStats.viscosity` 保存应力行数、最大谱界、固定壁面对液体的三轴冲量、控制体动能耗散以及 MAC float 写回的三轴舍入冲量。应力算子内部总动量变化由壁面冲量闭合，float 写回误差单列；P2G/G2P 和压力阶段使用各自既有动量与动能度量。`MechanicalStepAudit.viscousWorkJoules` 单列本阶段在 P2G 能量度量下的变化。黏性耗散目前作为机械诊断记录，尚未回填载体内能；不得将该值称为已完成的黏性发热。

`/mmm status` 发布最近成功步的谱界、耗散与壁面冲量。`cut-cell` 包含一维解析剪切层、二维交叉应力解析衰减、刚体旋转、调和界面、壁面动量、均匀/小体积/零开口谱界，以及后端细化和预算拒绝用例。

### 时间步与 CFL

配置中的 `timeStep` 是 logical step 时间；mechanical core 根据液体 marker、颗粒速度和重力预测、cell size 与 `maxCourantNumber` 选择子步，并复检压力后的液体与颗粒位移。子步细化保持本 logical step 的总时间、外部 source/sink 和相变预算。wall-time pacing 只改变 logical step 被调度的 tick 间隔。

## Logical step

1. 读取当前 geometry、cut-cell、carrier occupancy 和边界条件。
2. 体积/质量加权 P2G，施加重力、热浮力、气压、颗粒和实体的派生力。
3. 施加完整 Newtonian 黏性应力，以实际离散矩阵检查显式步长。然后构造物理目标散度并求解 pressure projection；该解是本步对外 pressure/机械功原始数据。
4. 以投影后网格动能约束 G2P 的最大 FLIP 增量；marker 位置沿与 MAC 离散散度一致的二阶 `H(div)` 场以 RK4 推进，再进入 CCD；partial cut-face 暂沿用 cut-cell 插值与 CCD。
5. 在传导前冻结颗粒反应 dirty cell，随后依次执行有限体积传导、液相反应、颗粒析出/溶解、凝聚相变和气液相变；两类相变共享同一操作预算。
6. 执行 active/deposit compaction、沉积与局部唤醒。
7. 经 `CarrierMutationCoordinator` 执行 reseed，同时搬运 chemistry/thermal side column。
8. 只有整个私有 candidate 成功后才替换诊断并发布完整后继。

相变产生的 pending volume/work 由下一次真实 projection 按该次绝对压力 结算一次，因此 pressure step 是唯一机械功提交点。相变提交已经改变 marker 几何，pending volume 不再重复写入目标散度。

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
- PCG 对活动 liquid cell 与接触液体的有限 sealed gas node 联合求解；`pressureTolerance` 是整个 block system 的目标绝对 RMS 代数残差。先按此目标迭代，再核验实际 `b-Ap`；极大压力下允许 binary64 矩阵求值的后向误差界，按未知量数量、机器精度及 `|b|+2 diag(A) max|p|` 的 RMS 推导，不使用固定相对容差。同一次 projection 的 fluid unknown 集、face mobility、diagonal 与 gas storage 在迭代前装配一次，PCG 只在该固定 block operator 上更新向量；迭代中不得重复重建物理系数，也不得改变相分类。迭代预算耗尽、breakdown、非有限残差或无法闭合有限库存/容量 owner 的 gas topology 会以各自 typed failure 拒绝完整候选并把生产区域标记 `NUMERICAL_PAUSE`，不会以未收敛压力或部分 gas remap 继续 advection。其他 `IllegalStateException` 不在该边界被宽泛吞掉。
- CPU 压力向量、右端项、储存项和对角量使用 binary64。液体矩阵按每个面的压力差累加，保持常数压力的零梯度；速度修正使用尚未舍入的解。递推残差达到容差后重新计算 `b-Ap`，只有实际残差通过才接受。
- 环境表压为 gauge zero。gas topology 的 runtime lineage 还会沿连通 pressure-air fringe 扩展到实际 ghost face，但不会穿过 pressure-fluid bulk。只有密闭气体边界时，求解器先减去按气腔 compliance 加权的统一 gas gauge reference，PCG 只承受相对压力；对外压力观察与相平衡再加回该 reference。参考选择同时覆盖液体和颗粒接触的给定压力项；有环境或未归属 fringe 时保持零参考，有真空时优先使用真空压力。多个 gas lineage 之间的压差保留。
- 活动颗粒通过占据率空间导数、有限质量和隐式阻力响应参与压力矩阵；阻力按子步末滑移迭代，液体与颗粒速度一起满足体积连续性；具体离散式见 [颗粒排水与压力](granular-state.md#统一排水与压力)。相变 pending 记录尚待力学同步的 marker 几何变化。

### 液体与可压缩气体的能力边界

当前空间动量网格仍只承载凝聚相；`AirNetwork` 保存密闭气体的组分、质量与 `U`。开放环境和真空仍是液面 Dirichlet pressure，接触液体的有限 sealed node 则以一项集总 pressure unknown 与液体联合求解：无热状态使用 `V/p_abs`，热状态使用 `V*beta_s = V/(gamma*p_abs)` 作为体积 compliance。

该 unknown 只是当次 projection scratch；下一次 topology refresh 仍从唯一 inventory/volume/`U` 计算非线性 EoS 与绝热功，不保存或反算第二份气体库存。

它消除了把有限气腔整步冻结成 pressure boundary 的显式滞后，但仍不解析腔体内部逐 cell/face 动量、声波或混合层。

液气 face 的有向跳跃约定为 `J = p_liquid(interface) - p_gas(interface) = sigma*kappa`。若两侧 center 到界面的距离（以 cell width 为单位）为 `d_l,d_g`，同一 face mobility 与速度修正为：

```text
m_f = rho_ref / (rho_l*d_l + rho_g*d_g)
u[n+1] = u* - dt/(rho_ref*h) * m_f * (p_g - p_l + J)
```

当前 node 没有空间 gas inertia segment，因此严格取 `d_g = 0`，恰好退化为既有 one-sided ghost-fluid 消元；未来两侧都有 pressure unknown 时使用同一 series mass resistance，而不是按密度比切算法。

一个 face 向 liquid/gas 两行加入相同对角与相反 off-diagonal，`J` 以等量反号进入 RHS，所以矩阵保持对称，静态 `p_l-p_g=J` 不产生冲量。集总 node 行另加 `rho_ref*V*beta_s/(dt^2*h^3)` 储存对角；真空没有 continuum unknown。

统一气液空间算法复用同一守恒 P2G/G2P、`H(div)` 重建、平流和碰撞算子；相别差异只进入本构闭合。对冻结组分的局部绝热响应，令 `beta_s = 1 / (rho*c²)`；压力隐式步满足：

```text
beta_s * (p[n+1] - p[adv]) / dt + div(u[n+1]) = 0
u[n+1] = u* - dt * (1/rho) * grad(p[n+1])

-div((1/rho) grad(p[n+1])) + beta_s/dt² * p[n+1]
    = -div(u*)/dt + beta_s/dt² * p[adv]
```

这里 `p[adv]` 是随质量/组分/能量一致平流到控制体的热力学压力，不是凭空保留的显示场。`beta_s → 0` 时 Helmholtz 储存项严格退化为当前不可压 Poisson projection；气体使用状态方程给出的非零 `beta_s`，不需要按材料 ID 选择另一套压力算法。

空间实现还必须以守恒通量更新质量、组分、动量和**总能量**，并把 pressure work 纳入同一候选；只把该储存项加到当前液体矩阵、却不建立这些权威列，不能记为已实现空间气体。低密度、高密度比、混合与相变必须由这些方程自然产生，不能用固定 FLIP 比例、速度钳制或渲染修补代替可压缩闭合。

### PLIC、表面张力与润湿

自由面由 cell liquid fraction 与局部法向构造 PLIC。ghost-fluid 距离决定 fluid-air pressure face；液液界面则从逐相体积分数字段构造 pairwise 界面。

液气压力跳跃满足 `delta p = sigma * kappa`：有 pressure unknown 的已解析界面走 sharp ghost-fluid jump；两侧都没有 pressure unknown、但 marker carrier 跨面的亚格界面走互补 balanced-force CSF，二者拓扑互斥。

液液张力使用 phase 无序对定义，以 balanced-force CSF 接入同一 pressure 路径。液气曲率法向始终只来自液气几何；润湿由 Young 表面能 `sigma_sl - sigma_sg = -sigma cos(theta)` 的切向离散梯度产生，固壁面积取自同一 `2×2×2` cut-cell microgeometry（含域边界），不旋转曲率法向，也不使用浅水吸墙、墙角偏置或材料 ID 特判。

当前 `gpu-probe-kernel` 只覆盖单一常密度不可压 carrier 的 pressure 几何、P2G/G2P、散度一致平流、碰撞和守恒 reseed。它尚未承载材料质量列、变密度、液气/液液表面张力与 Young 壁能、gas boundary、热化学或完整 immutable candidate，因此这些 CPU 能力不能被记作 GPU 已实现；probe 继续不具备生产资格。

当前不可互溶相共享一个机械速度场：系统承诺静水压力、密度分层和界面张力，不声称解析界面切向滑移、乳化或湍流液滴破碎。

## 固体与碰撞

- Fabric 侧 `MinecraftCollisionWorldPort` 将 Minecraft `VoxelShape` 保守采样成不可变 `CollisionField`；core 再把 micro mask 转成 cut-cell face aperture。pressure、预条件器、投影和 marker CCD 使用同一几何。
- CCD 只截断命中法向，切向保持自由。
- 世界碰撞变化挤出的液体必须从 marker 账本得到实际量；不能用格密度推断 escaped volume。
- granular、deposit 和 coherent solid occupancy 与 liquid 共享容量和压力排水路径。

`FlipCutCellGeometry` 从每格 `2×2×2` unsigned micro mask 得到 cell solid volume 和三轴 face aperture。checkpoint 与后端 handoff 保存 raw mask，不能退化为“整格固体/整格空气”；恢复后必须得到相同 aperture。`FlipParticleCollision` 对 marker 轨迹做连续碰撞检测，避免只在步末投影位置造成穿薄墙；碰撞只去除命中法向速度，切向分量保持。

实体交互由 Fabric 每个 Minecraft tick 冻结有界 AABB、原版更新后的实体响应速度、本 tick 实际位姿差和流体观察。core 将 AABB 与当前派生的水平液层逐格求交；每格液层按开放 `2×2×2` micro layer 自下而上分配，所以浸没体积和湿区质心随子格位移连续变化，并排除 `VoxelShape` 已占据的 micro volume。

这个观察不改 marker 库存，也不是压力面积分。实体侧响应仍按游戏 tick 应用；liquid moving boundary 使用 `position - oldPosition` 的实际当 tick 位移速度，不能把原版已施加重力/阻力、供下一 tick 使用的 `deltaMovement` 反向当成本 tick 表面运动。

moving-body 输入只能在 region 可运动且 pacing 到期的真实 logical step 使用一次，PAUSED、thermal-only 和被 pacing 跳过的 tick 不得提前或重复改写 marker。

core 使用紧支撑二次 B-spline 核，在湿体积上把实体运动约束写入附近 marker 速度；返回值由实际 float velocity 写入重新计算 `kg·m/s`，体积、质量、组成和 `U` 不变。该 marker 动量会经过正常 P2G、pressure projection、G2P 和 advection，因此走动、划水和入水速度可以扰动液体，而不是只唤醒调度。

当前 AABB 湿体积是有界 kinematic immersed-boundary 近似：Minecraft 实体运动被视为外部 moving body，不声称已有刚体质量原始数据或严格等量的实体侧反冲 ledger；实体占位也尚未进入 cut-cell 容量。推进到压力面积分和严格双向闭合前，必须验证静水浮力的采样误差、给出黏性/形状阻力有效域、把实体表面速度接入 pressure boundary，并让实体占位通过统一容量路径排开液体。不得用额外速度钳制或材料特判掩盖这些适用条件。

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
- checkpoint/UNLOADED 必须保存或恢复完整物理原始数据。

阈值只在代表性 profile 已分离 solver、世界交互和 readback 成本后评审。

## 数值原则

- 优先使用明确的守恒离散、有限体积通量、PLIC、PCG、balanced-force、Boussinesq 等通用算法。
- 不以墙角偏置、强制找平、渲染增厚或按 tick 百分比源汇掩盖异常。
- 新近似必须声明适用尺度、适用条件和可观测误差；先选择简单有效的模型，再考虑更重框架。
