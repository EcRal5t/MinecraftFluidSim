# CPU 流体求解器契约

修改压力、自由面、粒子运动、碰撞或后端状态交接时读本文。

## 修改入口

| 要改什么 | 代码入口 | 测试选择器 |
|---|---|---|
| 计算顺序与状态捕获 | [FluidDomainStepCoordinator](../../solver/src/main/java/mmm/fluid/FluidDomainStepCoordinator.java)、[MechanicalStepPipeline](../../solver/src/main/java/mmm/fluid/MechanicalStepPipeline.java)、[FluidDomainStateMapper](../../solver/src/main/java/mmm/fluid/FluidDomainStateMapper.java) | `lifecycle` |
| 压力和自由面 | [PressureProjectionStage](../../solver/src/main/java/mmm/fluid/PressureProjectionStage.java)、[PressureSystem](../../solver/src/main/java/mmm/fluid/PressureSystem.java)、[PressureFaceCoupling](../../solver/src/main/java/mmm/fluid/PressureFaceCoupling.java) | `free-surface,gas-liquid` |
| 速度传递与平流 | [GridToParticleStage](../../solver/src/main/java/mmm/fluid/GridToParticleStage.java)、[MarkerTransferBudget](../../solver/src/main/java/mmm/fluid/MarkerTransferBudget.java)、[ResolvedMarkerVelocity](../../solver/src/main/java/mmm/fluid/ResolvedMarkerVelocity.java)、[DivergenceFreeVelocityExtension](../../solver/src/main/java/mmm/fluid/DivergenceFreeVelocityExtension.java)、[ParticleAdvectionStage](../../solver/src/main/java/mmm/fluid/ParticleAdvectionStage.java) | `free-surface,thermal,reseeding` |
| 局部形状碰撞 | [FlipCutCellGeometry](../../solver/src/main/java/mmm/fluid/FlipCutCellGeometry.java)、[FlipParticleCollision](../../solver/src/main/java/mmm/fluid/FlipParticleCollision.java) | `cut-cell` |
| 粒子拆分和合并 | [FlipParticleReseeder](../../solver/src/main/java/mmm/fluid/FlipParticleReseeder.java)、[CarrierMutationCoordinator](../../solver/src/main/java/mmm/fluid/CarrierMutationCoordinator.java) | `reseeding` |
| 后端结果提交 | [FluidSolverBackend](../../solver/src/main/java/mmm/fluid/FluidSolverBackend.java)、[RegionApplicationService](../../app/src/main/java/mmm/application/RegionApplicationService.java)、[FluidRegionRepository](../../fabric/src/main/java/mmm/simulation/FluidRegionRepository.java) | `lifecycle` |

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

CPU 在私有对象上按阶段执行计算。`CpuWorkBatch` 的并行度取可用逻辑处理器数量的一半，上限为八，每批交接一段索引范围；小范围直接在调用线程执行。三轴 P2G 各自保持粒子顺序，G2P 采样、单元重建和预检后的载体列写入各自的预分配区间。接收格按供给格和份额的原顺序汇总，独立格之间可并行；守恒审计中的求和顺序保持固定。工作任务全部结束后才返回结果或原始异常，任一阶段失败就丢弃整份候选，保留当前区域状态。成功后可直接交接这个对象一次。需要存档或诊断快照时，提交前调用 `state()` 生成不可变状态；提交后该对象已成为活动区域，历史候选的 `state()` 会拒绝调用。完整提交过程见 [运行时与区域操作](runtime-and-lifecycle.md#candidate)。

初始子步数由液体、颗粒速度与重力预测的 Courant 数决定。压力加速后的液体 MAC 速度与颗粒速度还要在碰撞预览前复检。Courant 超限或颗粒非线性阻力迭代未收敛时，`MechanicalTimeRefinement` 携带所需子步数，CPU 后端丢弃候选，从原请求重新恢复并细化整个力学步；最多细化十六次，仍无法满足条件则拒绝候选。重算包含实体输入、气体状态与待同步相变几何，成功候选只提交一次。直接调用可变 `FluidDomain.step()` 的诊断代码会收到该细化异常；生产调用统一经过后端。

CPU 候选的 `mechanicalResolution()` 保存实际时间细化次数、累计预留子步和最近一次细化原因。累计预留在每次候选开始前计入，包含随后拒绝的尝试，上限为 4096；完成子步数由 `StepStats.substeps` 报告。其他后端的默认零值表示未报告。该诊断随计算结果返回。

每次细化以本次实际选定的子步数为基准，将下次最低数量设为其两倍与异常要求数量的较大值。初始 CFL 已选较多子步时，下次尝试也保持严格减小步长；聚焦诊断复用后端的这条选择规则。

成功结果携带本步输运、化学、颗粒反应和相变诊断。力学 `StepStats` 另含颗粒沉积与唤醒诊断。调度器据本步结果判断活动状态。

`FluidStepRequest.mechanicalAuditEnabled` 控制可选机械诊断。启用后，`StepStats` 按液体与活动颗粒的实际 kg 记录速度传递、多孔阻力、重力、浮力、张力、固定约束、联合压力与颗粒阻力、碰撞、库存转移和粒子拆并各阶段的动能变化。液体在网格上的阶段采用既有 P2G 动能度量，活动颗粒沿用原始速度；P2G/G2P 阶段中颗粒动能保持原值。颗粒重力计入 `gravityWorkJoules`，压力与隐式颗粒阻力的一次联合速度发布计入 `pressureProjectionWorkJoules`，颗粒碰撞及容量暂停计入 `particleCollisionWorkJoules`。初末重力势能包含液体、活动颗粒的实际位置及各沉积组的均匀床层质心。连续凝聚固相与集总气腔的机械能尚未纳入这组诊断；热学机械功沿用热账本的完整载体范围。`closureResidualJoules` 校验各阶段动能变化的合计，势能、未结算耗散与总机械能另行评价。该开关增加诊断记录，物理计算和存档库存保持相同规则。

## 网格与 marker

有限体积出流的 Courant 数按当前 `advectionCells` 中的供给格计算，与实际参与通量计划的库存一致。独立粒子使用自己的速度与位移约束，初始步长和压力后复检均保留这项检查。守恒积分点的逻辑步末段调用 `ensureInventoryGrid` 更新库存几何；输运后的速度网格保持 dirty，由下一子步或公开速度查询的 `ensureGrid` 重建 P2G 质量、有效面及延拓数据。

- 默认 cell size 为 0.5 m；每个 Minecraft block 对应 2×2×2 cells。
- 每个 marker 的 raw float `particleVolume` 是液体体积原始数据，物理总量是这些二进制有理数的精确和；逐格 marker sum 只由该原始数据正向汇总。顺序相关的 float/double reduction 只能作带解析舍入界的诊断，不能替代 exact ledger。
- `volume[]` 是面向观察和表面几何的 binary32 单元体积分数，默认由同格 marker 精确体积和一次舍入生成；pressure、PLIC 与气腔容量共用精确单元库存。粒子平流对照路径以固定核生成平滑体积分数与守恒压力支撑。
- P2G/G2P 使用共享的 valid face；均匀密度时 face 分子/分母按 marker 体积散射，变密度时用实际 double 质量累加面质量和双精度加权均值，最后一次写入 float 面速度。加权均值先归一化质量比例，避免质量乘速度的中间溢出。保留的 P2G face weight 为 double，按原质量或体积单位供冲量、阻力和动能计算；float `velocityWeight` 保存归一化与空气外推的工作数据。MAC 空气外推从开放压力面播种，G2P 不采未定义空气零值。三轴 face splat 直接写入固定 SoA，不为每个 marker/axis 创建 callback；上一 logical step 末已完整发布且 `gridDirty == false && velocityGridDirty == false` 的派生网格可供下一步首个 substep 直接使用，particle advection 与任何 carrier edit 都会重新置 dirty，后续 substep 不能跳过重建。
- G2P 按实际数值依赖分量分别计算预算：正权 P2G、有效 G2P、压力行、有限气体未知量、颗粒压力和阻力因子，以及空气延拓的联立变量共同确定支撑；空气外推从实际使用的面沿有向来源追溯。封闭几何分量的液体精确库存与实际可用容量相等，且数值分量的全部载体属于该满液压力支撑时，使用质量正交投影。容量包含颗粒占据量，真实微小缺口保持自由面规则。
- 满液支撑的 G2P 先以 marker 质量内积将已有速度投影到 MAC 插值空间，丢弃该采样空间无法解析的分量，再写成 `v(alpha) = v_pic + alpha * (v_resolved - v_p2g)`。能量预算为 `E_resolved + min(E_predicted_grid, E_projected_grid) - E_p2g_grid`，保留可解析流动的 P2G 方差。质量正交投影使用矩阵无关 PCG 求解 `B^T M B q = B^T M v`，返回 `Bq`；各独立分量分别归约内积与判定收敛，残差精度对应 binary32 插值精度。含自由面的支撑沿用原 marker FLIP 增量，并采用预测与投影网格动能的较小值作为预算；其额外数值耗散单列诊断。两者以 PIC 动能作为可行下限，解析选择 `[0,1]` 内满足预算的最大 `alpha`。只有一个含液分量时保留直接求根；临时数组规模由当前面、格、载体和颗粒数量决定，每个面最多进入一次追溯队列。远处独立自由面或满液池保持目标分量的速度响应。
- reseed 以标准 `2×2×2` marker quadrature 的单 marker 体积决定逐格目标数；split 保持体积、质量、动量和质心。merge 按相同 material、phase、密度、温度、指示剂和逐 species 浓度分组，先选择分散的代表点，再构造保持质心与完整空间协方差的正权重积分规则。体积之和须可精确表示；raw float 体积的双向减法同时检查细小尾数。可行方案耗尽时保留原积分点并报告拒绝原因。GPU 的相同判定须使用精确求值。
- 生成使用标准四分之一格积分点，reseed 的对称拆分保持质心与可放置留距。运动中的 marker 按积分点处理，碰撞按实际直线轨迹依次遍历切割微单元，并在接触后推进剩余切向位移。接触位置取属于原开放微单元的最近 float 表示，外壳、整格固体与切割固体沿用同一规则；同刻跨越切向网格线保持切向速度。多个微盒在角点相接时，从可行法向组合中选择位移改变量最小的组合；等值选择合并约束，合并后触及实心邻盒时保留原开放微盒。角点响应对轴置换与空间反射保持一致。

G2P 的预测、投影、PIC 与输入载体动能，以及 FLIP 混合的线性、二次系数，均先按实际 kg 计算并以 J 保存。独立数值分量采用同一单位；生产网格动能审计逐面先换算质量再计算能量。

空气外推以开放的实际 pressure phase 面与开放的正 P2G 权重面的并集播种，保留这些面已经获得的物理速度。张量插值可在两侧均无 marker 中心的面上产生正权重，该开放面同样保持有效。满液体压力空间的闭合固体面是已知零法向速度，参与 G2P 插值的完整权重；自由面沿用开放 valid face 的归一化插值，该传递近似与网格能量预算共同验收。P2G 与 G2P 在域外壳附近使用相同的截断坐标。

压力面惯性与实际 P2G 面质量存在差异。自由面场景中，压力算子上的内部总冲量可以闭合，写回粒子后的实际总冲量仍有偏差。压力后的 G2P 与再次 P2G 也会改变已投影的散度；满液体的质量正交速度投影保留可解析采样空间，未保证该空间中的压力连续性。实际粒子质量、速度传递和压力约束需要共同组成一致的离散，完成条件见[物理缺口的依赖安排](../roadmap/product-roadmap.md#物理缺口的依赖安排)。[PressureTransferExperiment](../../fabric/src/test/java/mmm/fluid/PressureTransferExperiment.java) 分别报告压力面、P2G 面质量和实际粒子的冲量与功，以及速度回映射后的散度；自由面实验同时分解 PIC 压力响应、FLIP 系数变化和支撑面外推变化。

[MarkerVelocityTransfer](../../solver/src/main/java/mmm/fluid/MarkerVelocityTransfer.java) 提供实际 kg 加权的固定稀疏 `J/Jᵀ`，封闭面统一排除，MAC 发布采用双精度归约及一次 float 写入。[MarkerPressureOperator](../../solver/src/main/java/mmm/fluid/MarkerPressureOperator.java) 将共享面面积和相邻压力行组成 `C`，提供体积流率 `Cv`、压力力 `Cᵀp` 和 `CM⁻¹Cᵀ + S_g`；有限气体储存为 `S_g = Vβ_s/Δt²`。压力力先计算共享面压差，封闭常压力保持严格零力；相同压力行的面贡献先合并，再计算对角项。独立离散验证覆盖实际粒子质量下的连续性、压力冲量与功、封闭常压力零空间和气体压缩符号。

纯液体主流程通过 [MarkerPressureProjection](../../solver/src/main/java/mmm/fluid/MarkerPressureProjection.java) 使用实际质量的壁面适配速度拟合与微盒湿面约束，直接发布粒子速度和同一拟合场的 MAC 法向迹。每个正湿体积块参与散度体积积分；部分充液块与有限气腔共同满足恒压载荷的 Gauss 关系，改变数值压力参考保持实际载体力。

速度分量采用 `u_axis = q_axis(t_axis) [a + g·(x-c)/h]`，`t` 为距物理格中心的归一化坐标。无湿墙时 `q=1`；一张湿墙取一次零点形状，两张平行湿墙取两端为零的二次形状，三张同轴湿墙保留零法向场。梯度 `g` 位于实际来源位置差所张成的空间，单点至三维来源依实际空间秩保留相应梯度。实际质量加权拟合与转置共用同一系数；受限轴的墙面响应采用质量正交投影。部分来源落在形状零点、导致所选空间缺少惯性时明确拒绝，全源零形状保留零场。

压力对壁面适配场积分完整的二次散度多项式，并约束各非恒定系数；封闭格内三轴旋涡保持零散度和零墙面法向迹。完整湿微盒的体积行由实际六面通量组装，同块内部湿微面按几何归属直接抵消；部分湿微盒采用体积矩积分。湿体积矩与完整六面在几何捕获时绑定，二阶矩按需精确积分并缓存。一般 PLIC 自由面的法向迹通过体积散度与六面通量的 Gauss 关系求得，重合微面的自由面采用对应平面上的法向迹。MAC 与输运面上的 `q` 为常数，均值、切向矩和载荷转置共用该因子。平面湿面矩的法向质心直接取积分面的常量坐标，法向中心二阶矩严格为零；切向中心矩采用两遍中心化三角积分，保留薄层的正空间方差。同一物理面的四个完整、互不重叠湿微面汇总时，以物理面坐标、切向单元、格长和四位覆盖证书确认完整矩形，再直接计算面积、质心及中心矩；混合中心矩严格为零。部分多边形与重复微面沿用一般合并规则。

每个持有实际载体的固定开放分量形成一个速度块，独立保存来源质量、空间秩、拟合和壁面形状。同一法向平面同时存在湿墙和开放湿面时，来源逐微盒覆盖与正湿微盒完全一致的分量进一步拆成微盒速度块，并约束内部共享面的法向连续性。首块沿用物理格编号，额外块按格、固定分量与微盒顺序追加；微盒和位置查询复用同一稀疏索引。体积行按块湿体积组装，共享微面按两侧速度块合并，自由面按原微盒分别绑定气腔。输运按各来源块的 support 单独积分。单格压力观察由各速度块在格中心的压力估计按湿体积加权，压力未知量继续分别求解。

线性求解以气腔储存加权的共同常压模式起算，再由实际算子计算完整残差；零对角行保持零初值。CG 步长分母计算正二次型 `Σ |Cᵀd|²/m + Σ S_g d²`，纯液体路径按精确因式响应归约，颗粒混合路径沿共同压力力响应计算。

仿射压力采用 `p = p_ref + δp`：有限气腔行的固定参考为本次初压，其他行沿用准备的共同压力，零对角行取零。增量右端项为 `S_g(p_initial-p_ref) - Cv_star/dt - D H Dᵀ p_ref`；`Cv_star` 从实际预测速度精确归约，统一乘 binary64 时间倒数，保持约束行关系。共同时间倒数具有一次相对舍入误差，连续性观察同时包含该时间缩放误差。参考项、完整算子和残差先精确相减，再舍入观察值。增量保存为高、低两个 binary64 列，LU 修正和 PCG 更新先精确合计再拆分；保留原迭代上限和残差目标。转置力先合计参考与完整增量的精确二进制值，逐载体最后一次舍入；气体目标体积率使用同一完整压力差。公开压力与网格压力按完整压力各自一次舍入，原物理右端项继续用于来源认证及气体诊断。

纯液体压力算子保留精确的几何约束 `D` 与共同速度映射 `L`。每个有效速度块和轴缓存实际惯性的局部矩阵 `H = L M⁻¹ Lᵀ`，实际作用为 `D H Dᵀ + S_g`；体积流率使用 `D L v`，转置力使用 `Lᵀ Dᵀp`，正功使用同次 `Dᵀp` 与 `H Dᵀp`。中间量保持完整二进制系数，最终观察各舍入一次。完整 `C = D L` 继续用于稀疏 Gram 缓存与独立对照，近似双字 CSR 提供局部质量 QR 的行表示。多项式行之间的精确线性关系保持到最后一次数值观察，压力力位于共同速度空间的转置像中。

初始压力通过实际残差时直接接受。其余纯液体来源优先尝试精确行基底上的稀疏 LU。`PressureRowBasis` 按实际局部惯性确定有效约束：满秩块使用 `D`，低秩块使用 `D H` 的精确独立列，正气腔储存行附加专有列。候选独立行按实际质量 Gram 对角量从大到小选取。模素数满秩提供严格独立性证书，欠秩时用精确有理数消元认证全部依赖及右端项的来源舍入相容性。依赖关系须保持液体体积及气腔压力观察；封闭域实际按共同参考发布压力时，允许全部观察系数精确相同的整体平移。非均匀观察变化继续拒绝该候选。

`SparsePressureFactor` 用原始 `D H Dᵀ + S_g` 的精确主矩阵生成一次舍入的归一化矩阵，调用随 Mod 打包的 EJML 稀疏 LU。同一因子复用完整算子残差做迭代修正，每次保留严格改善的有限候选；修正停止后，最佳候选可作为重新计算残差的 PCG 初值。LU 求解次数与 PCG 共用原迭代额度，全部原物理行的实际残差达到原容差后才发布。精确运算额度、可用内存、数值因子、观察或来源认证不满足条件时，保持输入压力继续 PCG。体积流率、转置力、压力观察和载体发布保持同一算子。

行关系与右端项认证共用一次确定性算术额度，按实际行数、局部惯性列数、非零输入及初始有理数的 32 位字数确定。大整数乘除和最大公约数在执行前按操作数字数乘积计费，消元项逐项计费。该额度管理候选计算成本，原有理数位数上限、物理行、迭代上限和残差要求保持各自规则。

PCG 预条件以实际质量 Gram 的 SGS 为基础。每个速度块从规范散度多项式中选取独立的非恒定系数，实际质量加权行通过 QR 形成局部正逆 `Q`；全部物理约束行继续参与求解。局部 balanced 修正为 `Q+(I−QH)B(I−HQ)`，`H` 复用 SGS 的对称稀疏 Gram 缓存，选中列的前乘与转置共用稀疏邻接和归一化尺度。三次残差修正形成 `3B−3BHB+BHBHB`，在对称 `H` 和正 `B` 下保持正定。存在有限气腔储存时，再采用共同常压模式的单维 balanced 修正，缓存实际 `Aχ`；`χᵀAχ` 与载体惯性、气体储存的正能量按算子舍入界独立校核，固定气压的真实牵引共同参与。规范选行和局部逆用于预条件，实际压力算子、迭代上限及新算残差验收保持原规则。

压力几何来自当前气腔 frame 的 PLIC，具体来源校核见[气体压力耦合](gas-state.md#压力与热耦合)。含颗粒或连续固相库存时，主流程使用联合面压力和既有 G2P 写回。稀疏支撑、断开分量及各相共同惯性的完成条件见[求解器整合计划](../roadmap/solver-consolidation-plan.md)。

[LiquidSpatialInput](../../solver/src/main/java/mmm/fluid/LiquidSpatialInput.java) 按需捕获当前 frame 的 PLIC 与原始载体矩。微盒使用 `8*cell+micro` 编号，按固定连通分量分别汇总；空分量、干微盒及其中的未解析载体保留各自身份。源权重按积分点位置分组，PLIC 的体积、一阶矩与六项二阶矩共用一次精确半空间裁剪积分；分量孔隙率将覆盖积分换算为孔隙内液体测度。全干格的分量孔隙率按规范整数 SVQ 分配后的容量计算，与气体块共用容量解释。

原始体积和质量保留求解器单位，来源第一矩使用物理位置，二阶矩使用距格中心的物理位置。几何矩保存在格中心坐标中，归一化积分按捕获的体积尺度、格长及格长平方分别换成 `m³`、`m⁴`、`m⁵`；世界坐标第一矩在观察时加回格中心项。体积、质量假设及位置矩的来源差额先精确相减，再舍入为观察值。几何协方差 `M₂/V − (M₁/V)(M₁/V)ᵀ` 同样在有理数中计算，保留极薄液层的正空间方差；标量孔隙率保持该协方差。实际粒子惯性与连续几何惯性的差异分别可查。

该视图保存不可变的 frame 引用、源矩和几何矩，捕获时复用 frame 的完整载体几何检查；质量从原始列读取。普通压力流程沿用现有几何捕获，细分空间比较在显式请求时执行。压力候选发布继续校核完整原始列。均匀密度对照共同检查空间矩、实际惯性积分与压力功，相应入口见[验证指南](../maintenance/validation.md)。

归一化质量权重上的面力作用于实际粒子的加权质心，该力臂一般偏离面中心，刚体旋转可产生额外体积流率与内部压力力矩。令 `Rω = ω×(x−center)`，保持内部角动量要求 `CR = 0`；当前质量映射还须补齐面速度的切向一阶矩。[PressureTransferExperiment](../../fabric/src/test/java/mmm/fluid/PressureTransferExperiment.java) 的 `--rotation` 对照独立面通量、紧支撑压力的实际总力矩与旋转虚功。

面速度的局部线性保持要求切向矩 `Σ J_fi t_i = t_face` 与常量保持 `Σ J_fi = 1` 同时成立。目标切向位置须位于支撑积分点的仿射包内；采用非负系数时，须进一步位于凸包内。单点、共线及稀疏自由面可缺少所需支撑。速度表示的验收同时覆盖刚体旋转、可解析的体积流率模式与压力源的相容性；这些空间能力由实际质量及共同几何决定。

`DivergenceFreeVelocityExtension` 对连接到 pressure phase、仍有 marker 的空气分类单元延拓不可压运动。压力液体面、正 P2G 权重面和固体面均为已知速度；辅助求解调整完全开放的无质量空气面，以最小二乘通量修正消除可解析的离散散度。这保持载体面上的动量和动能，以及物理压力和所有库存。没有可调整空气出口的封闭气体分量按已知面通量保留平均压缩率；未解析的孤立液滴与薄层按 marker 速度运动。partial cut-face 继续使用既有切割插值。延拓后的速度再次接受 CFL 校验，超限由完整私有候选细化时间步。

完整面支撑的 `H(div)` 重建读取 cut-face 开口。完全闭合的固体面保持整张面上的零法向速度，面平均值与两个切向斜率均为零；相邻开放面的斜率继续由共享 stencil 推导。内部二次修正保持点散度与 MAC 离散散度一致。该规则覆盖整格固体的角落和台阶；partial cut-face 使用已有切割插值与 CCD。

### 空间积分误差与分布诊断

总库存守恒、压力体积估计和粒子空间分布分别校验。压力体积由移动积分点和固定紧支撑核汇总；标准四分之一格积分点给出满格初态，经过剪切和旋转变形后，该固定积分规则会产生局部过量与不足。散射限制在积分点所在的开放连通分量内，闭合的切割面阻断跨分量体积贡献。

[ClosedLiquidVolumeConstraint](../../solver/src/main/java/mmm/fluid/ClosedLiquidVolumeConstraint.java) 从 cut-cell 开放面建立封闭连通分量，几何掩码变化时刷新索引。若分量内液体的精确体积等于可用容量，不可压液体充满该分量，逐格压力体积直接采用实际开放容量；颗粒占据从容量中扣除。相等判定对 raw float 按指数累加整数尾数，保留小于 double 求和分辨率的真实缺口。该派生约束保持载体库存与位置原样。含真实空气体积的分量继续使用积分点的压力核。`pressureFluid` 按重建 PLIC 是否包含格中心分类；重建体积分数取容量归一值并限制在 `[0,1]`。自由面局部过量在分类中饱和，局部不足仍可能形成采样误差。

[MomentPreservingMergePlan](../../solver/src/main/java/mmm/fluid/MomentPreservingMergePlan.java) 在提交前预检整组替换。[QuadratureMomentFit](../../solver/src/main/java/mmm/fluid/QuadratureMomentFit.java) 以对称协方差映射恢复体积加权质心和六项二阶矩，并按原速度的最小二乘线性场更新新积分位置上的速度。空间秩为 `r` 的组至少保留 `r+1` 个正权重点；秩损失、越格、固体碰撞或超出速度舍入范围的动能增长均拒绝整组替换。逐材料质量、species 与内能沿原库存合并规则提交，均匀密度组同时保持质量加权矩。不可解析速度残差的耗散计入既有 reseed 机械诊断，位置与速度的误差受 binary32 写入精度限制。同温、同密度、同浓度分组保留已有局部物性场，必要时保留多于目标数的积分点。

对称拆分用载体体积决定积分尺度。体积和质量都须可精确二等分为正的储存值，来源选择跳过无法满足这项条件的载体；全部来源均受限时计入 `conservationSkipped`，保留原始列。令 `V` 为父载体的 cell-normalized 体积、`h` 为格长，各向同性特征长度为 `h*cbrt(V)`；沿所选轴将体积二等分，对应的子积分点距离父点为四分之一特征长度。位移还受既有可放置区间限制：

```text
d <= h*cbrt(V)/4
Delta spatial_second_moment = V*d^2 <= h^2*V^(5/3)/16
sum_binary_split_tree Delta moment <= h^2*V_initial^(5/3) / [16*(1-2^(-2/3))]
```

这些式子描述正权重积分点的空间二阶矩变化，位置写入另有 float 舍入误差。尺度采用各向同性近似；持续剪切后的载体形状还需要额外的空间解析度。完整开放核支撑中的单次拆分满足 `sum_cells |Delta pressureVolume| <= 24*V*(d/h)^2`，由现行压力核的一维二阶导数上界推导。切割几何的截断与归一化另行测量。逐格数量维护、合并矩约束及拆分误差界分别守护对应不变量；连续变形和自由面压力核的积分精度需要独立校验。

[MarkerDistributionExperiment](../../fabric/src/test/java/mmm/fluid/MarkerDistributionExperiment.java) 分别测量固壁法向轮廓、固定速度场的正反向平流、解析保面积映射、单次重播种的二阶矩和溃坝长场景。报告 raw cell volume、容量限制前的 pressure footprint、局部过量积分、低于中心分类条件的格数和体积加权位置矩，并对照封闭满液体约束后的压力体积。自由面解析对照从逆映射独立积分真实覆盖率，用两级参考积分报告基准离散差异；分别细化移动积分点与网格，校验归一化 L1 误差递减。该对照保持封闭满液体约束不触发，帮助区分时间积分误差、压力核的界面平滑与粒子采样误差。默认有限体积输运的空间收敛与逐格容量校验见[单元有限体积输运](#单元有限体积输运)。

### 内存布局

`ParticleToGridStage` 分别重建单元库存几何和 MAC 速度。`gridDirty` 跟踪位置、容量和材料库存，`velocityGridDirty` 跟踪待执行的速度传递。库存几何重建后标记速度待更新；热学、气体归属、存档捕获和单元观察调用 `ensureInventoryGrid`，需要速度的力学阶段与速度查询调用 `ensureGrid`。完整速度重建仍执行三轴 P2G、权重归一、固壁约束与空气速度延拓。两项工作区都从当前载体原始数据推导，恢复后按需重建。

`PressureSystem` 的仿射采样场绑定库存网格和压力几何的代次。原始列待重建或任一代次变化时，旧场失效；压力发布和壁面投影分别安装当前场。有限体积输运的面速度、扫掠和本次 Courant 检查复用同一个捕获结果。出流 Courant 数按各实际速度块累计，开孔只计该分量的共享微面；纯液体路径使用该固定分量的开放体积作为容量，同格其他分量分别校验。

活动核心使用固定容量的 structure-of-arrays：

- cell-centered：`pressure`、`divergence`、`markerCount`、观察用 `volume`、精确 cell marker volume/mass、材料密度和表面张力工作数组；
- face-centered：三轴 MAC velocity、P2G 权重、valid mask 与 cut-face aperture；
- marker：位置、速度、raw float 体积、double 质量、材料 handle 和 phase label；
- 正交 side column：chemical handle/indicator、granular carrier、thermal U 和 gas-node lineage。

运行时 handle 只在冻结 registry 内有效。跨 checkpoint、物品或网络边界必须转换为 stable ID、canonical palette index 或完整 ordinal 表。

marker 数和所有 side column 使用同一个 swap-remove/append 顺序。新增、拆分、合并、相变或删除 marker 时，调用方必须在一次提交中更新全部已绑定 side column；只移动基础 marker 而留下化学或能量 ordinal 属于结构损坏。

### 液体黏性应力

`LiquidViscosityStage` 在颗粒和多孔阻力处理、固定面法向速度校验之后，重力、热浮力与张力施力之前推进已有流动。随后压力投影建立力平衡；静水初态的临时重力预测速度由压力消除，黏性阶段保持零应变与零耗散。`ViscousMacDiscretization` 沿用压力液体 owner 与开放面；参与单元同时须持有液体 marker。单元液体控制体为 marker 体积与当前可用体积的较小者，密度取真实质量/体积；每个开放 MAC 面由相邻参与单元各贡献半份 `rho*V`，换算到 kg。薄于压力分类分辨率的独立液滴继续由粒子输运，尚无连续黏性控制体。

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

### 单元有限体积输运

默认构建使用单元有限体积液体输运。各格实际 marker 体积精确求和，压力、PLIC、气腔剩余容量和输运使用同一单元库存。独立数值对照可用 JVM 参数 `-Dmmm.cellVolumeTransport=false` 选择粒子积分点平流。空间精度与长期分布的验收如下；完整生产步的耗时要求见[生产资格](../roadmap/performance-and-backends.md#有限体积输运的生产资格)。

`setVolume` 通过 `CellVolumeReplacementPlan` 预检单格替换。请求达到当前可用容量时，各固定连通分量先取得共享 SVQ 分配后的精确孔隙容量，再由其开放微格的四分之一格积分点分配液体体积；`LiquidCellQuadrature` 在共同 binary32 格点上均匀分配并保留分量精确总体积，份额的储存差最多为该格点的一个单位。无切割格的部分填充采用请求水平湿层内的八点规则，层厚为请求体积除以当前孔隙容量；切格部分填充按开放微格顺序逐分量抵扣剩余容量。生成点使用最终 binary32 坐标校核实际所在格与微格，无法表示所需采样位置时立即拒绝。

旧载体索引、新载体质量、完整化学组成与区域数量额度、实际储存内能的正温反演，以及完整 source/sink 审计候选均在删除前完成校验和储存预留。气体已初始化时，以未来完整载体矩列表预检气腔拓扑及热学后继；预览缓存与替换计划绑定实际库存网格的重建版本，原位抽取同样使旧计划失效。提交前复核载体、化学、气体、颗粒、热学版本与碰撞几何。提交按原索引降序删除并同步附属列，随后发布新载体与两份完整审计；气体按后续正常几何同步更新。热源精确等于全部新载体实际储存内能之和，热汇精确等于移除载体的内能之和。

满容量初始化沿用颗粒与凝聚固体的单元均匀占容近似，液体扣量均匀分布于开放微格。该空间近似的一阶矩、外壳附近实际颗粒质心及各相共同正空间表示仍须在统一压力与库存离散中进一步闭合。

`CellLiquidVolumeTransport` 根据投影后的 MAC 法向速度积分共享面的 PLIC 扫掠体积。`ConservativeLiquidFlux` 使用低阶供给、几何通量修正和接收格容量共同限制通量，先在 `2^-26` 单元体积格点上保存初始精确公共面通量。单元更新满足 `V_new = V_old + sum(F_in) - sum(F_out)`，各格正体积和开放容量在提交前逐格检查。每面供给限制后的体积与向下量化结果之差按 exact dyadic 保存，供给格记正差、接收格记负差；全域误差和为零。该差与精确带符号的颗粒核表示误差合并，核误差非零时对合计正差一次向上取整到 SVQ；核误差为零时保留面通量正差的精确额度。由此限定逐格保守几何重分配量，取整增加的包络严格小于一个 SVQ。化学表示数量限制取消完整面和禁用的零格点面的贡献从舍入预算移除，物理面速度额度与本子步原始供给约束同时保留。

有限体积步长使用正库存供给格的总出流 Courant 数 `sum(|u_face|*dt/h*A_open/O_cell) <= 1`，partial micro face 的扫掠宽度还须小于等于半格。初始估计包含重力预测，压力后与 G2P 后再次检查。连通薄层的分类复用 `DivergenceFreeVelocityExtension` 的搜索，要求相邻 PLIC 在同一开放面上具有正面积交集，每次 P2G 重建后重新推导；独立液滴继续使用位移 CFL 与 CCD。压力与输运共用当前库存生成的 cut-cell PLIC；部分格的法向采用格内体积质心估计，零偏心时沿体积分数梯度生成。

仿射输运在同次 advection 内保留逐方向、逐速度分量的微盒扫掠体积与第一矩，供初始通量、受限出流和微盒来源权重复用。支撑限制筛选原微盒、出流面微盒与接收面微盒，保持分量内精确求和及分量间原双精度归约顺序；湿体积按需计算，二阶矩与独立相平面继续各自求值。保留结果逐位绑定法向迹、步长、PLIC、固定掩码及双方孔隙，按可用内存限制保存；提交或拒绝后释放工作区，新子步重新准备。

PLIC 的体积质心从实际 binary32 体积和位置精确汇总体积与位置第一矩，先在物理坐标中精确减去格中心矩，再除以 `h*V` 并舍入一次。固定微盒的各个面连通分量分别保存这些派生矩，[PlicComponentGeometry](../../solver/src/main/java/mmm/fluid/PlicComponentGeometry.java) 按各分量的库存重建独立平面。微面、共享湿面积、暴露湿面及扫掠体积读取所属分量的平面，逐分量保持体积。单 PLIC 的位置矩近似和同格速度自由度的分量拆分仍按共同空间表示的完成条件完善。

基于粒子库存的平面阈值由 [PlicPlaneOffset](../../solver/src/main/java/mmm/fluid/PlicPlaneOffset.java) 按精确库存与孔隙容量的比值求逆。精确盒积分在 binary64 阈值序列上单调查找，返回精确根或相邻阈值及其精确体积，并选取体积误差较小的阈值。相邻阈值间的体积差明确给出当前平面表示的分辨率。

[PhaseLiquidGeometryEdit](../../solver/src/main/java/mmm/fluid/PhaseLiquidGeometryEdit.java) 以固定 mask 和全部开放分量的不可变库存矩列表作为未来几何输入，包含清空后的零分量；整格数量、体积和位置矩由该列表精确汇总。PLIC 候选替换被编辑格的全部分量，再计算全网格体积分数梯度。候选与已发布载体重建使用同一聚合与平面算法。分量孔隙容量复用 [CellRemapMicroTopology](../../solver/src/main/java/mmm/fluid/CellRemapMicroTopology.java) 的规范 SVQ 分配，与微面输运保持相同的固体份额和剩余容量。

`CellCarrierRemapPlan` 按单元、开放微格的面连通分量、材料、指示剂和有界 species 并集分组，同一体积通量搬运材料质量、动量、组成和内能。species 使用整数最大余数分配；质量与内能的可表示余数分配到有容量的份额，提交前检查逐材料质量的 binary64 舍入界、精确 species、精确逐格体积、内能和动量收支。热量重建复用实际质量与组成的 caloric 数据，在相邻输入温度范围内保持正温，并保留完整能量审计。

存在断开的开放微格时，`CellRemapPorts` 以实际相邻开放 micro face 配对共享面通量。每个供给分量使用本子步原始库存，其保留份额与出流份额共同满足化学表示数量限制；独立到达按最终 binary32 物理位置归属分量并预留容量。接收分量超容时沿已有入流路径反向抵扣，各中间分量的库存保持不变；最终逐面通量、逐格库存和第一矩同时使用抵扣后的计划。采样和第一矩平移保留分量归属，普通输运中无实际通量的分量保持原积分点，几何重映射逐分量重新核验。固定微盒的分量体积由开放微格数精确给出；颗粒与凝聚固体仍采用当前单元标量占容近似，将既有 SVQ 固体占容按开放体积划分，随后抵扣独立到达的精确 raw dyadic 体积。液体通量与容量沿原始二进制体积格点分配。

化学份额先检查精确体积的正 binary32 展开。单元体积位于输入 binary32 格点且不超过一格，正体积最多用七个 float 展开；每个微格分量最多有二十四个出流配对和一个保留份额。低 quanta 分量预检这些份额，单组保留体积可通过向上取到 float 格点、等量抵扣一个出流配对来恢复可表示性；其余不满足表示条件的候选依次取消最小出流配对并重新检查容量。实际通量与第一矩同步使用最终计划。

物理份额合并并补全到达形状后，最终接收组的联合矩秩、温度坐标要求和实际正 float 可拆性共同决定最低积分点数。`CellRemapSpeciesAllocation` 先按共同体积权重分配 species，再沿来源允许的正体积去向寻找增广路径，重新分配已有整数 quanta，满足各接收组的最终节点需求。每份来源的各 species 总量保持精确相等；中间组净数量保持原值。分组预检使用来源的完整可能物种集合，正式组成确定后才分配来源热量并反演接收温度。通用正积分规则保留可用的五至七个节点和精确体积尾数。量子不足或原始 float 格点缺少足够正节点时，明确拒绝完整候选；时间细分继续处理真实时间离散与位置支撑问题。

微格计划的线性访问额度由实际单元数、原始载体数、到达载体数和面数推导。每面最多四个开放配对、五轮有进展的容量分配；化学检查仅对实际出流分量收费。容量路径与低 quanta 表示候选共用额外搜索额度，包含一个完整图遍历及两次路径遍历的输入规模额度和既有力学搜索额度，按访问节点、边、分组与候选数组逐次预检收费。超额拒绝完整私有候选，原始库存保持原值。完整格和单连通格继续采用共享面的快速分配，化学表示预检需要抵扣时进入同一微面计划。

均匀密度组在原始质量及各份额的 `rho*V` 均可精确表示为 binary64 时，按同一密度写入质量并保持精确总和；其他组继续按质量储存的舍入界校验。密度判定核对每个原始载体，材料相同仍须通过该判定。

共享面精确通量大于零的供给格与接收格重建 marker，进出通量抵消时仍属于重建格。其余有限体积格保留原积分点的位置、速度与全部库存列，由共同计划与独立粒子结果一起预检提交。固体表示改变的几何重映射仍按新开放空间重建。无液体载体时跳过排水阶段。`GranularLiquidDisplacement` 每次重映射独立使用 `MAX_OPERATIONS_PER_LOGICAL_STEP` 搜索额度，全域库存复制、清空和建图预先计费，随后按 BFS 节点、邻接边及两遍路径遍历收费。超额抛出 `MechanicalWorkBudgetExceededException`，CPU 后端拒绝完整私有候选，已提交源状态保持原值；该额度与颗粒压缩、沉积阶段的额度分别计算。

仿射输运的低阶通量按实际开放微面上的正法向流率计算。捕获的面法向迹沿源法向柱使用同一函数，低阶柱内混合按源微盒的孔隙容量归一化；液体份额精确积分 `PLIC 湿区 ∩ 正向流区` 中的法向迹，保留微盒内部速度与相分布的相关性。干区份额取开放量减去液体量，两者精确和保持开放量。该法向混合的空间近似通过低阶通量与实际高阶扫掠的差量观察。高阶、湿体积与低阶共用湿源半空间，零法向平面按原始库存认证全湿或全干。`ConservativeLiquidFlux` 使用同份低阶通量限制高阶修正，逐格低阶出流超过初始库存时请求时间细化；原计算额度保持不变。实际气体库存去向由气腔后继计划确定。

仿射步长按开放法向柱内湿源的法向迹峰值估计。线性峰值由湿半空间与源微盒交集的盒角和盒边交点精确求出，最后向上舍入；当前出口全干时，内侧湿源仍参与位移估计。标量路径沿用整面 Courant 检查，仿射路径使用湿源 Courant，并保留切格半微盒的位移条件。容量修复的液体追加上界由湿源峰值与开放面积推导，开放扫掠量另行保存供气团迁移认证。原面速度继续描述开放面流率。峰值复用绑定载体与压力几何修订号，保存扫掠同时检查这两项身份及原几何元组。

质量、动量和动能由同一组原始载体正权重分配。`CellRemapMicroPreferences` 从本次扫掠与湿体积推导供给比例：几何份额优先使用具有原始库存的实际扫掠微盒，同一法向柱的扫掠区缺少积分点时沿用柱级表示；低阶份额按该微盒低阶出流与湿体积之比作用于原始库存，积分点缺少对应支撑时采用同柱表示。两份提议按通量计划中的同一高低阶比例组合，微盒内载体共用份额。固体表示改变的零时长几何排液按本组原始微盒库存生成供给比例，实际端口与最终容量继续由共同计划检查。`CellRemapTransportMoments` 在原始体积供给限制内分配全部出流，保留份额逐点取原权重减去全部出流权重。每份质量、动量、动能、体积第一矩和体积速度矩使用相同权重；同微盒的原始精确矩先汇总，再乘共同份额，逐载体权重继续供空间矩与其他库存列使用。最终观察与舍入差额保持逐载体精确计算的结果。整数 species 与内能继续使用各自的库存分配和完整预检。

共享面第一矩在面上结算；切向位置和空间矩由同一来源份额推导。格内质量与体积第一矩采用输入、输出动量的梯形位移近似。`CellRemapSpatialMoments` 汇总质量加权的位置、速度及其完整联合协方差。流入包采用子步内冻结载体速度和均匀进入时间的重建近似：切向跟随完整轨迹，法向在末段半步之外增加 `(dt/h)^2 E[v_n^2]/12` 的独立空间方差，保持包的第一矩、位置速度交叉矩及完整速度矩。同速、未截断平移时对应扫掠液层的厚度；一般仿射流的载体速度与面法向迹差异、PLIC 截断另有空间误差。接收组以平行轴公式合并来源，正权重重建优先保持目标质心、空间协方差、位置与速度交叉矩及速度协方差。未发生运动方向的空间方差参与节点数选择。

存在真实流入且合并来源的空间秩不足三时，接收组采用有界的亚格形状近似。`LiquidAffineSweepGeometry.sweptExactSecondMoments` 从同一次实际 PLIC 扫掠求切向几何协方差 `G`，`Π` 投影到该组原空间支撑的正交补。每份归一化流入体积 `V_s` 按既有对称拆分尺度取 `κ_s=min(1,V_s^(2/3)/(16 trace(G_s)))`，零几何协方差保持零贡献。按实际质量份额累计 `A=Σ_s (M_s/M) κ_s Π G_s Πᵀ`，各份使用同一个原空间支撑。联合矩仅增加位置块 `A`，保留已有方向的空间矩、位置速度交叉矩及完整速度矩；实际采样的节点需求继续由联合秩决定。

该近似的质量二阶矩增量满足 `trace(ΔM₂) ≤ Σ_s M_s h² V_s^(2/3)/16`，以 `LiquidTransportStats.massSecondMomentApproximation` 的三项对角量报告，单位为 `kg·m²`。均匀密度下体积矩与质量矩具有相同比例；变密度继续独立核对实际体积矩。零通量、零时长固体排液及已有三维空间支撑保持原规则。正积分点仍接受开放分量、float 位置、整数份额和库存预检，后续压力继续检查实际发布秩与惯性；一般 PLIC 几何误差和完整气腔交接按集成用例验收。

联合矩分解使用 [JointMomentFactor](../../solver/src/main/java/mmm/fluid/JointMomentFactor.java) 保存六行正平方根。正权合并的来源因子和质心差列经 Givens 列旋转压缩，位置推进与面法向投影直接变换对应行。位置优先的两遍 QR 按各原始行方差的浮点分辨率选择模式；独立小速度模式及其关联位置系数共同参与重建。所有携带位置系数的模式按优先几何选择采样方向，包含由速度行解析出的薄层方向。均值更新以较大质量来源为锚点。原始平行轴协方差单独维护，输出仍逐项回验一阶矩与完整二阶矩，节点数和储存可表示性分别检查。

空间秩为 `r`、未解析速度残差秩为 `s` 时，正权重规则至少需要 `1+r+s` 个节点。三维空间优先采用八点规则，节点不足时按联合矩所需数量检查；温度变化仍要求其涉及坐标的完整张量支撑。各材料组保留独立质心，共同体积在接收格及微格分量中精确核验。完整矩规则无法放入接收空间时，实际出流涉及的坐标沿用有界质心与最大可行仿射伸缩的空间近似；未参与输运的坐标保留原方差。均匀密度组的填充余量分别计算，变密度组按实际开放位置限制质量质心。速度继续保留来源动量与动能。化学量子、精确体积尾数或 marker 额度无法承载所需规则时，整份候选在发布前拒绝，诊断保留实际接收格、分量 support 与节点缺口。

位置预检按最终 binary32 值核验单元与开放微格归属。位置写入的舍入误差、体积第一矩误差和零共享通量格中未推进的内部动量由 `StepStats.liquidTransport` 累计；完全流出的源组保留原有第一矩残差诊断。质量矩使用 kg·m，体积矩使用原始体积分数乘米；带符号残差相对于梯形动量位移计算，乘重力得到重建的势能误差。几何矩使用 double 近似，运动方向的空间协方差变化仍须由解析输运对照量化；共享面与载体惯性全部联合矩的闭合按共同空间表示的完成条件推进。体积与相关库存按精确收支校验。所有基础列、chemistry 与 thermal side column 经完整预检后由 `CarrierMutationCoordinator` 一次发布。

温度标量多项式按实际 MAC 速度在供给格固定坐标中回溯，跨格合并前将系数变换至接收格坐标；混合项产生的低阶系数同时变换。速度由共同质量、位置与速度联合矩重建，温度拟合及采样只处理热学标量。输出温度均值由实际内能与 caloric 库存反演，空间偏差受相邻输入温度范围限制。面热量通量仍读取回溯前的供给场，与输出空间重建分别求值。

`CellRemapInventoryAudit` 在发布前分别校验精确体积、逐材料质量、液体内能、动量和动能。发布逐值写入已检查的内能列，并登记这次预检的精确液体能量舍入差额；气体、颗粒与固体内能在该操作中保持原值。温度沿最终拟合的实际积分位置求值，切割与细小库存尾数采用同一规则。

`MechanicalStepAudit.volumeTransportTransferJoules` 单列重建造成的动能变化，包含输出载体重新投影到壁面适配速度空间的表示转换功；`particleCollisionWorkJoules` 记录独立液体载体及颗粒的实际 CCD 接触功。P2G/G2P 变化和黏性耗散各自记录。`liquidTransport` 的带符号质量矩残差观察几何重建对势能的影响；动能审计闭合与机械总能守恒须分别评价。该空间离散的误差由解析不可压旋流的三级网格检验：`N=12/24/48` 的体积归一化 L1 误差约 `0.04199/0.01743/0.00772`，逐格容量和总精确体积同时闭合。持续旋转至 `t=4 s` 后，`N=48/96`（格长 `0.125/0.0625 m`）的误差约 `0.03751/0.01670`；`N=24` 的细丝尚未充分解析，误差约 `0.28157`。两级独立参考积分的归一化差异分别约 `0.000171/0.000080`，空间误差的递减须超过该参考离散差异。切割几何、颗粒占据、分层和自然对流使用各自回归场景约束适用范围。

完整 `free-surface` 对有限体积路径运行 `12×8×6` 网格的 5,000 步溃坝（`h=0.5 m`、`dt=0.025 s`）。每步检查原始积分点体积精确和未超过开放容量、压力体积与同格库存相等，以及满格八个积分点保留独立的四分之一格位置。全程总体积保持 216 个单元体积，最终质量差为零，累计 binary64 质量舍入上界约 `2.48×10^-7` 个求解器质量单位。

### 当前自由面的空间解析限制

默认输运按 `DivergenceFreeVelocityExtension.advectionCells` 选择路径：压力液体格及经公共湿面积连通的含液格进入共享面通量；其余粒子由 `BallisticLiquidRemap` 按当前粒子速度推进并执行 CCD，目标格预留对应库存容量。压力分类要求重建体积分数大于半格，并且 PLIC 覆盖格中心；`LiquidViscosityStage` 的参与单元还须属于这个压力分类。

面通量重建保存单元库存与物性场，按开放容量和正权重积分规则近似空间第一矩。固定 PLIC 通量、单元容量与原始 marker 第一矩可能互不相容，无法保持任意格内液滴的实际质心；超出表示范围的部分计入矩投影误差。单个 PLIC 平面也无法表示完全位于格内、四周被气体包围的闭合液滴。现有独立粒子路径保留这部分位置数据，但缺少对应的连续压力和黏性控制体。零共享通量保留已有位置；有通量重建在受限空间支撑内拟合质量第一矩，体积矩误差另行记录。现有验证分别量化连接、分离和格内平移的误差；亚格压力与黏性作用继续按实际解析度限制支持范围。[展示几何阶段](../roadmap/product-roadmap.md#阶段-3展示几何契约与重建)需要同时保留已解析单元和未解析载体的位置输入。

连续输运的微小正库存可形成远离主要液体的数值尾部。误差评价同时读取库存加权位置、尾部质量和占据格/载体数量：微小尾部的极远坐标与主要液体质心分开观察，其新增支撑进入计算成本。固定网格上的时间细化会增加重建次数，空间投影误差需与时间截断误差分别测量。`LiquidHandoffContinuityValidation` 覆盖规定三轴匀速输运、切割几何开闭，以及八秒立方体平移的库存加权外距离、远处尾部库存和载体规模。名义八点积分规则可追加精确 binary32 展开尾数；数量检查采用实际载体容量。空间细化的误差比较针对固定运行时长，持续记录各时刻的尾部变化。

同一验证还覆盖两个一立方米水体以相反的 `0.5 m/s` 速度闭合两米间距。场景声明表面张力 `0.0728 N/m`，使用 `293.15 K` 下的水黏度，运行等温力学；网格 `h=0.5/0.25 m`、步长 `dt=0.025/0.0125 s` 分别比较。实际连接依据公共液体湿面积构建连通分量，以固定中截面左右两侧各自最大的库存分量是否相同判定；连接检查读取每个完整逻辑步的末态。暂时桥接与再次分离逐次记录；主要水体须从名义接触时刻 `2 s` 持续连接至 `2.5 s`，同时校验库存、容量和载体预算，报告这段时间内主分量的最小体积、孤立尾部的最大体积，以及动量、动能和质心差异。这个连接场景尚未证明机械误差全面收敛。

`CapillaryNeckValidation` 在固定有限围墙域验证持续液颈分离：水柱平均半径 `R=1/64 m`、长度 `9R`、半径余弦扰动幅度 `0.25R`，横向宽度 `2.5R`；实际水黏度 `0.001002 Pa·s`、表面张力 `0.0728 N/m`，无重力、无热绑定，围墙采用自由滑移与 90° 接触角。初始化独立积分体积和三轴第一矩。网格 `R/h=4` 报告未解析结果，`R/h=8` 的 `dt=1/256、1/512 s` 均须在固定约八个毛细时间尺度的搜索区间内发生主要端部分离，并持续 `0.125 s`。湿面图中接触两端的最大分量须分别成为全域最大的两个分量；中央卫星滴和全部正库存尾部继续参与计算。每个逻辑步使用正式 CPU 后端，从完整快照细化并接管候选，检查精确体积、质量存储舍入界、容量、有限值及实际载体预算；报告分离时刻、共同物理时刻的动量/动能/质心、尾部、外侧湿壁和完整步成本。该回归限定于声明的有限域，断裂时间的精确收敛、无墙增长率及精细网格的实时成本仍需分别评价。

## Logical step

1. 读取当前 geometry、cut-cell、carrier occupancy 和边界条件。
2. 体积/质量加权 P2G，推进颗粒与多孔阻力，校验固定面法向速度，再按完整 Newtonian 黏性应力扩散已有流动；以实际离散矩阵检查显式步长。
3. 施加重力、热浮力、张力和气压等派生作用，再构造物理目标散度并求解 pressure projection；该解是本步对外 pressure/机械功原始数据。
4. 延拓自由面附近的不可压速度，按压力空间对应的能量预算约束 G2P 的最大 FLIP 增量；连通液体按共享面的有限体积通量搬运库存并重建单元积分点，其余液体按当前粒子速度推进并执行 CCD。独立粒子平流对照路径在完整面支撑中使用二阶 `H(div)` 场与 RK4；切割几何共同约束开放通量与碰撞。
5. 在传导前冻结颗粒反应 dirty cell，随后依次执行有限体积传导、液相反应、颗粒析出/溶解、凝聚相变和气液相变；两类相变共享同一操作预算。
6. 执行 active/deposit compaction、沉积与局部唤醒。
7. 经 `CarrierMutationCoordinator` 执行 reseed，同时搬运 chemistry/thermal side column。
8. 只有整个私有 candidate 成功后才替换诊断并发布完整后继。

相变在固定区域体积下联合守恒两相内能，并更新 marker 几何。pending volume 记录由下一次真实 projection 完成力学同步后清除；液体与颗粒运动造成的气腔容量变化由气体等熵模型结算机械功。能量规则见 [热学相变](thermal-state.md#相变)。

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

- 默认有限体积输运按逐格精确库存生成 pressure fraction/PLIC。粒子积分点平流对照模式使用由标准 `2×2×2` 四分之一格点诱导的 C1 紧支撑核，把真实体积守恒分配到开放邻格；该 footprint 决定对照模式的压力几何。`MarkerDistributionExperiment` 也计算这个平滑足迹作为采样误差诊断，测量后恢复默认压力数组。
- 仿射液体压力的体积未知量覆盖全部正湿体积格，由该格实际湿体积与载体惯性组成。联合面压力路径按 PLIC 是否覆盖 cell center 选择开放格，满格直接进入，部分格以 plane/center 几何判定；中心以下薄层沿用载体平流和补充界面力。两条路径各按对应离散的空间支撑生成约束与界面距离。
- 重力覆盖开放的正 P2G 质量面与实际 marker occupancy 激活的输运面；正质量面即使相邻格中没有 marker 中心，也接受相同重力加速度。热浮力按载体物性计算，速度支撑沿用实际 P2G 权重。
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

当前 node 没有空间 gas inertia segment，因此严格取 `d_g = 0`，退化为既有 one-sided ghost-fluid 消元；两侧均有 pressure unknown 时统一使用 series mass resistance 计算。

一个 face 向 liquid/gas 两行加入相同对角与相反 off-diagonal，`J` 以等量反号进入 RHS，所以矩阵保持对称，静态 `p_l-p_g=J` 不产生冲量。集总 node 行另加 `rho_ref*V*beta_s/(dt^2*h^3)` 储存对角；真空没有 continuum unknown。

统一气液空间算法复用同一守恒 P2G/G2P、`H(div)` 重建、平流和碰撞算子；相别差异只进入本构闭合。对冻结组分的局部绝热响应，令 `beta_s = 1 / (rho*c²)`；压力隐式步满足：

```text
beta_s * (p[n+1] - p[adv]) / dt + div(u[n+1]) = 0
u[n+1] = u* - dt * (1/rho) * grad(p[n+1])

-div((1/rho) grad(p[n+1])) + beta_s/dt² * p[n+1]
    = -div(u*)/dt + beta_s/dt² * p[adv]
```

这里 `p[adv]` 为随质量/组分/能量一致平流到控制体的热力学压力。`beta_s → 0` 时 Helmholtz 储存项退化为不可压 Poisson projection；气体依状态方程给出非零 `beta_s`，统一求解格式。

空间实现还必须以守恒通量更新质量、组分、动量和**总能量**，并把 pressure work 纳入同一候选；只把该储存项加到当前液体矩阵、却不建立这些权威列，不能记为已实现空间气体。低密度、高密度比、混合与相变必须由这些方程自然产生，不能用固定 FLIP 比例、速度钳制或渲染修补代替可压缩闭合。

### PLIC、表面张力与润湿

自由面由 cell liquid fraction 与局部法向构造 PLIC。ghost-fluid 距离决定 fluid-air pressure face；液液界面则从逐相体积分数字段构造 pairwise 界面。

液气压力跳跃满足 `delta p = sigma * kappa`：有 pressure unknown 的已解析界面走 sharp ghost-fluid jump；两侧都没有 pressure unknown、但 marker carrier 跨面的亚格界面走互补 balanced-force CSF，二者拓扑互斥。

液气曲率优先由原始液体体积分数的九列高度函数计算。各列须位于完整开放单元中，沿同一方向单调积分至空单元；液端采用满格，或两侧 PLIC 共同覆盖的完整湿截面。部分端格按实际库存纳入高度积分，其 PLIC 对应的压力体积须与当前体积分数相等，端格法向须朝向该列气侧。主轴按体积分数梯度选择。外壳的实际接触角为 90°、目标和采样单元完全开放时，体积分数作偶反射，散度中的墙法向分量作奇反射。切割、颗粒占据、其他接触角、未解析或多重界面沿用平滑法向的散度近似。`HeightFunctionCurvature` 与准确体积分数初始化的平面、部分格液层、球面、接触外壳的圆柱及完整压力步对照；闭合自由面的合力、力矩和寄生速度仍须随解析度观察。液液 CSF 保留与其平滑相分数梯度配套的曲率。

液液张力使用 phase 无序对定义，以 balanced-force CSF 接入同一 pressure 路径。每对使用两相平滑分数的 `alpha_A*grad(alpha_B) - alpha_B*grad(alpha_A)` 构造法向、曲率与面力；二相占满液体时退化为 `grad(alpha_B)`，第三相中的 pair 份额减小时作用连续减小，任一相缺失时作用归零。液气曲率法向始终只来自液气几何；润湿由 Young 表面能 `sigma_sl - sigma_sg = -sigma cos(theta)` 的切向离散梯度产生，固壁面积取自同一 `2×2×2` cut-cell microgeometry（含域边界），不旋转曲率法向，也不使用浅水吸墙、墙角偏置或材料 ID 特判。

当前 `gpu-kernel` 覆盖单一常密度不可压 carrier 的 pressure 几何、P2G/G2P、散度一致平流、碰撞和总库存守恒 reseed。粒子中心平流尚未通过共享面库存输运与逐格容量约束交接，局部库存可能超过可用格体积。CPU 可读 checkpoint 忠实保存粒子位置、数量和碰撞标记；恢复出的状态仍须满足 CPU 容量与完整步校验，当前 probe 尚不能保证任意计算代次可继续 CPU 计算。它尚未承载材料质量列、变密度、液气/液液表面张力与 Young 壁能、gas boundary、热化学或完整 immutable candidate；probe 继续不具备生产资格。

不可互溶相共享单一宏观速度场，求解静水压力、密度分层与界面张力。切向滑移、乳化与液滴破碎不在当前求解范围。

## 固体与碰撞

- Fabric 侧 `MinecraftCollisionWorldPort` 将 Minecraft `VoxelShape` 保守采样成不可变 `CollisionField`；core 再把 micro mask 转成 cut-cell face aperture。pressure、预条件器、投影和 marker CCD 使用同一几何。
- CCD 只截断命中法向，切向保持自由。
- 世界碰撞变化挤出的液体必须从 marker 账本得到实际量；不能用格密度推断 escaped volume。
- granular、deposit 和 coherent solid occupancy 与 liquid 共享容量和压力排水路径。

`FlipCutCellGeometry` 从每格 `2×2×2` unsigned micro mask 得到 cell solid volume 和三轴 face aperture。checkpoint 与后端 handoff 保存 raw mask，不能退化为“整格固体/整格空气”；恢复后必须得到相同 aperture。`FlipParticleCollision` 按微单元平面的相交时刻遍历 marker 直线轨迹，取首个固体接触，随后沿墙面推进剩余切向位移。角落的同时接触按实际阻挡微单元确定法向；碰撞去除指向固体的法向速度，切向分量保持。

粒子 handoff 的格长必须有限且与接收域逐位一致，保持 solver 体积、质量与 SI 单位换算相同。恢复在首次状态修改前，按实际 binary32 位置与固定微盒的面连通分量精确汇总体积；所有材料共用分量的开放容量。超量候选保留原载体、碰撞几何和修订号。该预检守护体积容量，空间第一矩的可表示性仍按共同空间表示的完成条件推进。

实体交互由 Fabric 每个 Minecraft tick 冻结有界 AABB、原版更新后的实体响应速度、本 tick 实际位姿差和流体观察。core 将 AABB 与当前派生的水平液层逐格求交；每格液层按开放 `2×2×2` micro layer 自下而上分配，所以浸没体积和湿区质心随子格位移连续变化，并排除 `VoxelShape` 已占据的 micro volume。

该项观察保持 marker 库存不变。实体侧响应按游戏 tick 应用；liquid moving boundary 取 `position - oldPosition` 的实际当 tick 位移速度，避免误用计入下 tick 预先外力的 `deltaMovement`。

moving-body 输入只能在 region 可运动且 pacing 到期的真实 logical step 使用一次，PAUSED、thermal-only 和被 pacing 跳过的 tick 不得提前或重复改写 marker。

core 使用紧支撑二次 B-spline 核，在湿体积上把实体运动约束写入附近 marker 速度；返回值由实际 float velocity 写入重新计算 `kg·m/s`，体积、质量、组成和 `U` 不变。该 marker 动量经过正常 P2G、pressure projection、G2P 和 advection，使走动、划水和入水速度直接在物理场中扰动液体。

当前实体浸没基于运动学 AABB 浸润近似与位移扰动：实体按外部运动刚体处理并施加流体扰动动量。后续双向闭合将把实体表面速度接入压力边界，并将实体占位纳入统一容量排开液体。

## Reseed 与传输不变量

- split 把一个 marker 的体积、质量、动量和所有 side truth 按同一权重分给两个子 marker；merge 只在 material、phase 和化学可合并性一致时进行。
- merge 只有在目标 raw float volume 能精确表示两 marker 的数学和时才提交；否则 marker 数可暂时高于 nominal target，但 exact 体积/质量优先，诊断必须区分 geometry、budget 与 conservation refusal。
- marker 预算不足时先拒绝整个候选，不允许创建基础 marker 后再丢弃 side column。
- insert/extract 先完成容量、可表示尾数、物品形状和事务预检，再修改数组。
- 数值清理严格以库存归零或显式转入 sink 为准，禁止以体积微小为由丢弃有效库存。

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
