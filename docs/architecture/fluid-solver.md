# CPU 流体求解器契约

修改压力、自由面、粒子运动、碰撞或后端状态交接时读本文。

## 修改入口

| 要改什么 | 代码入口 | 测试选择器 |
|---|---|---|
| 计算顺序与状态捕获 | [FluidDomainStepCoordinator](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainStepCoordinator.java)、[MechanicalStepPipeline](../../solver/src/main/java/cc/ecisr/mmm/fluid/MechanicalStepPipeline.java)、[FluidDomainStateMapper](../../solver/src/main/java/cc/ecisr/mmm/fluid/FluidDomainStateMapper.java) | `lifecycle` |
| 压力和自由面 | [PressureProjectionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureProjectionStage.java)、[PressureSystem](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureSystem.java)、[PressureFaceCoupling](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureFaceCoupling.java) | `free-surface,gas-liquid` |
| 速度传递与平流 | [GridToParticleStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/GridToParticleStage.java)、[ResolvedMarkerVelocity](../../solver/src/main/java/cc/ecisr/mmm/fluid/ResolvedMarkerVelocity.java)、[DivergenceFreeVelocityExtension](../../solver/src/main/java/cc/ecisr/mmm/fluid/DivergenceFreeVelocityExtension.java)、[ParticleAdvectionStage](../../solver/src/main/java/cc/ecisr/mmm/fluid/ParticleAdvectionStage.java) | `free-surface,thermal,reseeding` |
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

几何控制体的候选交接使用 `LiquidGeometryState`：归一化根宽对应 SI 长度，顶点和支撑平面保存精确整数坐标，控制体保存 SI 平均速度。多个凸片段可共用一份材料、组成和内能库存；片段的几何与速度各自保留。捕获与恢复要求库存组体积精确相等，并校验空间一阶矩和质量加权动量；marker 位置与速度的 binary32 舍入误差单独计入观察误差。该字段随完整后端候选交接，生产自由面路径的接入完成条件见产品路线中的统一空间离散。

每个凸片段另保存所属力学控制体的规范首片段索引。同一控制体的片段具有相同库存组和平均速度；输运扇形及跨叶格裁剪保持其控制体关联。初始空间细化的独立控制体各自保留。注入、提取、凸片段合并和存档共同维护这一关联，辅助几何切割与力学解析度分别保存。

`LiquidMechanicalControlCells` 从这些关联组装实际力学外表面，`LiquidExteriorGeometry` 精确抵消辅助片段的内部公共面。体积和一阶矩继续读取实际片段积分之和。实际质心严格位于各有向外表面的内侧时，控制体具有星形重心扇形支撑，外表面可包含凹形；缺少这一支撑的集合保留已有正体积分区。显式空间细化将相应控制体按新叶格分开，重新建立共享面。力学网格以完整外表面建立面对应关系，气腔保存的标签与实际剩余空间继续联合校验。

几何状态由 `FlipParticleState` 随库存列一起携带，材料与化学交接复用该基础状态。库存组的 marker 位置表示质心观察；固体碰撞约束由各液体片段的实际占据空间验证。恢复先验证完整几何、库存和碰撞占据，再写入载体列；不一致的输入保留原状态。

`LiquidGeometryMotionPlan` 保存由共享速度场输运后的私有几何候选。发布前校验输入身份、每组精确体积、剩余空间一阶矩、平均动量和实际碰撞占据，再同步写入质心与速度观察以及完整片段几何。片段各自的速度随候选保留，材料数量、组成和内能继续使用原库存组。

携带 `LiquidGeometryState` 的域由 `MechanicalStepPipeline` 调用 `ResolvedLiquidMechanicalStage`，在完整液气控制体上执行黏性、重力与热浮力预测、联合压力求解、共享连续速度重建和时空面扫掠输运。每个正体积液体控制体参加同一组离散方程，库存组保留原材料、组成和内能。后继液体几何、气腔库存与压缩内能共同预检和发布，步末沿用几何片段合并。

有限气腔的压力未知量读取实际库存、容量及状态方程顺应性；真空使用给定的零绝对压力。界面运动延拓缺少独立气相压缩支撑时，求解器细分实际气相区域，重新建立共享面并重算私有力学候选。面塌缩或输运四面体的雅可比变为非正时，由 CPU 后端缩短时间步，从原完整输入重算。空间工作受 65,536 个区域和 4,194,304 次配对的表示上限约束；产品解析尺度仍须由完整步性能评审确定。

当前阶段的气腔对应关系由 `ResolvedGasGeometry.Match` 绑定已保存气体几何与同一份液气输入。气体孔流在该输入通过校验后推进一次，界面标签沿用这份对应关系；空间细化重算保持同一子步的气体库存推进次数。

恢复和每个子步的初态装配由 `LiquidGeometryPhaseInput` 将已保存的液体、气体片段共同映射到开放叶格。逐叶格的包含关系、互不重叠及精确体积和空间一阶矩证明完整覆盖；实际共享面同时校验气腔标签和环境归属。该证明直接复用保存的分区。初次气体建立及界面运动后的气体重分区从液体实际几何求补集，气腔库存沿输运后的材料界面保持对应关系。

`LiquidConformingVelocity` 在共享多边形的重心扇形上建立连续分片线性速度。共享顶点从实际入射的液体控制体及其液体公共面邻居读取平均速度，并作空间线性拟合。气相延拓沿用当前集总气体模型。面重心的法向自由度满足压力求解的精确面通量，控制体重心自由度满足精确平均动量。相邻控制体在整张共享三角面上具有同一速度，外壳的顶点和面重心共同满足已配置的自由滑移或无滑移条件。

`LiquidAffineFit` 在实际控制体质心形成的独立仿射坐标系中求体积加权线性拟合。基底按到已有线性空间的最大距离逐列选择，距离比较与空间秩使用精确整数运算，细薄方向保持实际几何支撑。同一局部控制体集合复用拟合与精确仿射式，复用范围限于本次相同气液几何的机械子步。`LiquidBoundedMatrix` 以输入、运算和逆矩阵残差的误差界约束数值系数，缺少有限证书时拒绝候选。

独立基底的形函数记为 `b_j`，其余观察使用数值拟合系数 `lambda_i`；基底观察的系数由 `lambda_j(x) = b_j(x) - sum_i lambda_i(x) * b_j(c_i)` 精确恢复。实际受支持的节点保持 `sum(lambda)=1` 与 `sum(lambda*c)=x`，力—速度伴随关系继续精确成立。基底的空间秩与节点支撑由整数行列式判定，空间支撑不足的控制体进入现有局部细化。各系数与原精确最小二乘拟合的差异按同一基底传播误差界；齐次顶点求值保留精确权重，数值误差观察直接读取未约分的整数比。

表面力通过该速度重建的导数形成压力算子，导数与输入速度值无关。装配参考向量取零；压力后的实际速度用更新的平均动量与面通量重建，并复用同一几何的插值系数与精确节点权重。四面体体积权重及平均速度对面通量的导数按需缓存。相归属与外壳约束须保持一致，速度误差界按新的均值重新传播。

`LiquidConformingRateOperator` 提供压力相容节点速度及其伴随的带误差界数值求值，两者共享全部几何系数。空间权重、固壁正交投影和体积导数来自相同的精确几何；结果包含系数观察误差与运算误差。表面矩阵作用按节点速度、节点表面梯度和伴随面载荷顺序执行，正表面度量保留节点 Gram 分解，湿壁使用原有符号面积梯度。压力面 Gram 行提供预条件对角观察，湿壁交叉行提供包含系数误差的负曲率行和上界。系数观察、平方根、通量输入及矩阵作用的误差逐项传入压力残差与动量闭合校验。

`StepStats.geometry` 汇总成功解析子步的速度拟合误差与速度观察位移误差上界。速度项在相同单元均值、面体积率和外壳约束下，与原精确拟合的连续节点速度比较；位移项记录速度舍入和固壁投影产生的误差。界面时空扫掠和库存体积仍使用精确几何校验。

`LiquidConformingTransport` 求每张共享面在有限时间内扫过的有向体积。面重心与共享顶点共同满足 `sweptVolume = dt * volumeRate`，控制体后继几何因而满足 `V_next = V_initial + sum(outward sweptVolume)`。时间侧面的共享边使用相同对角线三角化，两个控制体中的贡献精确抵消。不可压液体保持实际体积，具有给定散度的控制体保持对应的有限体积变化。同一控制体的四面体具有完全相同仿射变换时，以完整控制体的精确仿射像交接几何。

节点运动以 binary64 速度观察乘精确时间增量，并保存速度舍入引起的位移误差上界。静止节点与常速度平移保持原几何；固体平面上的正交投影精确保持给定运动约束，投影后的舍入误差平方小于等于原舍入误差平方。共享面随后满足上述精确扫掠体积。后继扇形的内部顶点沿原控制体平均速度推进，扇形体积和空间矩由闭合外表面确定；材料平均动量继续由初态连续速度的精确积分携带。

气体重分区由 `LiquidGasPartition` 沿液体实际外表面建立二叉空间分区。相邻液体片段的公共面先精确抵消，液体内部的速度及材料分割各自保留。分割平面优先减少外表面切割次数，同次数下平衡两侧的表面数量。每个终端凸区域的内部没有液体外表面，内部点的物质归属因此决定整片区域的气液归属；最终分区仍校验开放空间的精确体积和一阶矩。空间细化及气体分区的中间裁剪依靠严格内外顶点判定正体积，最终保留区域按散度定理积分精确体积与一阶矩。面面积、重心及支撑平面在不可变几何上复用，裁剪保留原表面的支撑平面。

多边形积分按精确共线判定消去边上的辅助分点，面积和重心读取同一积分结果。共享面关联与速度重建继续保留完整顶点循环，积分视图精确保持原面积与一阶矩。

解析几何入口将材料的液气、液液及湿壁表面能接入同一隐式压力步，空间支撑不足时局部细化，时间惯性不足时缩短完整计算步。毛细力与压力共同作用，分阶段动能账记录联合投影的动能变化。当前入口要求颗粒和连续固相为空，并且没有待同步相变体积；热化学计算尚需解析几何接触支持。缺少上述离散算子的候选明确拒绝。默认来源的几何初始化、长期稳定性及完整步预算继续由统一空间离散工作项维护。

`LiquidAreaEnergy` 对实际界面多边形计算 `sum(sigma * area)` 及逐顶点负面积梯度。逐面力按精确相邻顶点差累加，保持平移与旋转虚功。`LiquidSurfaceEnergy` 复用这份面积梯度，以共面多边形的重心扇形面积建立正的顶点积分权重；面法向通量在实际入射法向张成的空间内插值，该插值的伴随把面积梯度转换为压力跃变。法向空间之外的切向力独立保留。

连续速度重建的伴随 `LiquidConformingVelocity.load` 将节点力转换为控制体力、面通量载荷与给定运动的功，节点虚功和这些自由度的虚功精确相等。`LiquidConformingCapillarity` 对同一重心扇形三角面读取材料表面系数，将完整面积梯度按这份伴随分配到控制体加速度与面压力载荷。加速度使用实际控制体质量；压力的局部通量惯性对常加速度产生相同的虚功。闭合液面的受力校验保持总动量，实际输运的面积功误差随时间细化下降。长期稳定性和空间收敛继续由统一空间离散工作项维护。RT0 对照输运保留共享法向通量，切向跳变可产生不随时间步缩小的额外表面能；该对照入口是 `LiquidSurfaceTransportExperiment`。

压力相容的虚速度由实际面一阶矩给出控制体均值，再经过同一连续重建；`rateLoad` 是这份 `rateVelocity` 的精确伴随。`LiquidSurfaceMetric` 在实际界面三角面上建立参考表面的 Dirichlet 二次能量，初始位置的梯度与面积梯度相容，刚体平移具有零能量，形变二次能量非负。该参考度量给出后继三角面积的上界。力与速度的伴随耦合依据见 [Schroeder、Zheng 与 Fedkiw 的隐式表面张力方法](https://physbam.stanford.edu/papers/stanford2011-01.pdf)。

`LiquidPressureFaceEnergy` 将表面度量分解为稀疏 Gram 行，经上述伴随转换到压力的体积率自由度。联合压力步的惯性为 `M + dt² Sᵀ K S`，线性表面力来自实际面积梯度；`S` 为压力相容的界面速度重建，`K` 为参考表面度量。相邻液气面的方向转换同时作用于线性力和 Gram 行。隐式面载荷参加压力结果的功与冲量积分，液体保持精确连续性和实际几何体积。耦合核验证封闭液面的能量下降；完整 CPU 后端的材料润湿步验证表面能与动能下降、几何恢复及液气库存保持。长期状态与生产预算继续按统一空间离散完成条件验证。

`LiquidGeometricSurfaceEnergy` 在共享速度的实际三角面上组装液气、液液和湿壁面积能，每张材料界面计入一次。材料界面的正表面能使用参考 Dirichlet 度量；静止平面上的 Young 壁能由 `LiquidWetWallEnergy` 保存为有符号的面积二次式。其初始梯度与实际湿壁面积梯度相等，切向位移后的面积二次变化完整保留。`LiquidSurfaceRateMap` 在本次组装中复用带误差界的节点速度导数，正度量的 Gram 行与壁能的对称交叉行使用同一压力相容速度映射。

有符号壁面 Hessian 的行和给出负曲率上界，局部压力能量逆矩阵给出惯性的正下界，两者均包含系数舍入界。只有 `dt² * negativeCurvatureBound < minimumInertia` 时，联合压力步使用该有符号度量；不足时请求更短的时间步。空间支撑须精确重现活动界面节点的仿射坐标矩，缺少支撑的实际液体控制体请求局部细化。这两项分别检查时间稳定性与空间线性一致性，使用同一套受力和输运方程。

压力结果的 `momentumStationarityBound` 将循环通量的迭代容差与舍入误差经刚体平移的面面积系数累加，给出动量与已积分冲量之差的求解误差界。该观察与精确通量连续性分别校验。

`ConservativeFluxCoordinates` 的压力循环基底使用最小惯性生成森林，惯性包含实际液体、气体储能和隐式表面度量。给定压力的根节点各自保留，细小面的高惯性自由度参加原有完整压力方程。气相速度延拓按同一构造选择最小阻力森林，精确体积率闭合与转置虚功保持相同约束。

压力与气相速度延拓由 `ConservativeConjugateDirections` 在对角合同缩放后的空间内求解。搜索方向先规范化，再经过两轮能量内积正交化；内积使用补偿求和与乘积舍入补偿。完整方向空间用尽后，从重新计算的实际残差开始下一轮修正。收敛依据保持实际算子残差、系数与运算误差界以及物理容差。气相延拓的通量偏移由精确总通量减去精确参考通量后观察，最终通量独立接受精确连续性与驻点误差校验。

控制体压力算子的内部共享面接受 `p_first - p_second` 给定跃变，液气面继续接受液体侧压力。内部跃变的线性功与冲量分别由 `-dt * jump * volumeRate` 和 `-dt * jump * areaVector` 积分；结果与固定外壳、环境和气腔作用分列。

局部压力能量逆矩阵由 `LiquidPressureEnergyInverse` 使用相同的块分解装配，`LiquidBoundedArithmetic` 对几何系数观察和每次数值运算保存向外舍入的绝对误差界。三维逆矩阵以 `||I-AQ||_inf < 1` 的残差证明可逆，并用 Neumann 余项界约束全部逆系数。`ControlVolumeFluxMetric` 将系数误差传入全局压力作用与收敛判定。正惯性证明使用几何稳定化权重 `W` 作对角合同变换，在 `sqrt(W) M sqrt(W) / rho` 中扣除同样变换后的系数误差，再按最大权重恢复物理惯性下界；极小辅助面面积保持对应的速度率尺度。数值范围或正惯性无法证明时，完整私有候选报告相应系数与几何条件并拒绝提交。压力体积率继续使用原精确守恒坐标。

解析几何的机械能由 `LiquidGeometryMechanicalEnergy` 积分：库存组质量按实际片段体积分布，动能读取各片段平均速度，重力势能读取实际空间一阶矩。动量和动能积分先合计同库存组、同速度片段的精确体积，再乘速度及速度平方，辅助切割的互补体积先相消。相反方向的片段运动即使具有零组平均速度，也保留其有限动能；库存质心与平均速度的观察舍入单独接受交接校验。

运动候选通过 `LiquidGeometryCompaction` 合并同一细化叶格内、同库存组、同力学控制体且片段速度逐位相同的凸并集。合并证明候选包含原片段，并精确保持体积和空间一阶矩。气隙、凹形、不同库存组、不同控制体、不同速度和不同叶格均保留。候选配对工作有数量上限，达到上限时保留尚未合并的片段，已解析几何和速度场保持原样。

凸并集证明直接检查已有表面：外表面须支撑另一片段，移除的内表面须被另一片段完整覆盖。互补内表面精确抵消，合并复用已证明的体积和空间矩。`LiquidPhasePartition` 在精确相交前复用唯一顶点和支撑平面，并用向外舍入的包围盒排除可证明分离的片段。气体空间扣除液体时，已证明分离的气体区域完整保留；这些检查减少辅助切割，实际相交和体积校验仍使用精确几何。

解析几何的体积、材料混合物和化学来源通过 `LiquidGeometryInsertionPlan` 整批规划。容量查询按实际片段与基础格求交，得到剩余空间；可表示的转移数量向下舍入。新液体在同一开放空间的剩余气相中分配精确体积，几何片段独立保留，库存组质心作为 binary32 观察写入载体列。几何、marker 预算、组成与内能预检完成后，整批注入同步发布几何和各库存列。

容器提取、单格删除和按目标格集合移除液体共用 `LiquidGeometryExtractionPlan`，求液体片段与目标空间的实际交集，并限定该库存组在目标空间内的可提取体积。规划以载体列的精确体积差生成移除几何；剩余片段保持各自速度，库存列更新剩余质心与体积加权平均速度。候选在修改材料、species 与内能前完成几何及库存校验，再按相同的索引交换顺序提交全部载体列。普通移除按实际移除量记录 species 流出账本与内能收支，保留剩余的正体积尾量。清空液体同时发布空几何。

液体黏性应力和颗粒阻力共同读取按格温度派生的 `mu(T)`。`bindMaterialDefinitions` 独立于颗粒能力绑定 world `MaterialRegistry`；`bindGranular` 复用同一校验与绑定。`FluidDomainState` 携带不可变定义，`Layout` 包含其 fingerprint；纯材料、化学、颗粒和热学区域冷恢复均从已提交世界能力包绑定。缺黏度段、基础物性冲突、重绑定不同定义均在修改域之前拒绝。未绑定定义的独立数值测试域维持无黏度模型。

交给下一次计算的输入还包含三类临时数据：化学与颗粒调度进度、世界热交换面、最近一次真实压力投影。它们随计算状态交接。区域 checkpoint 保存的字段另有定义，见 [区域存档](region-state.md)。丢失热交换面会漏掉实际热通量；反复重置颗粒调度进度会使沉积永远无法休眠。

静水热区域从存档恢复后，先唤醒一次力学计算，重建压力投影，再允许单独热计算使用它判断相平衡。

`FluidStepRequest` 还可携带有数量上限的实体运动输入。后端在私有状态上施加粒子冲量，再执行完整计算步，结果报告实际作用于液体的体积和冲量。用于同步相变几何的请求要求实体输入为空、相变和机械诊断审计关闭，由同一后端选择仅含力学的流程。

力学结果使用 `FluidStepCandidate`，静水热计算使用 `FluidThermalStepCandidate`。两者都返回原代次的紧邻后继，并接受后端标识、代次和完整布局检查。`FluidDomainState.Layout` 固定网格、配置、预算、外壳、载体种类、注册表、功能身份、热学预算与材料定义指纹。

`StepStats.carrierViscosity` 累计本步各次属性构建中超出材料声明温区的格数，以及按构建顺序、cell 顺序选取的首个样例（材料 ID、实际温度、声明温区）。它按「格 × 属性构建批次」计数，同一批次被多个 parcel、Darcy 面或床面重复读取只算一次；失败或细化重算的候选不发布统计。状态报告读取区域保存的最后一个成功步结果，不触发物性或温度重建。

CPU 在私有对象上按阶段执行计算。`CpuWorkBatch` 的并行度取可用逻辑处理器数量的一半，上限为八，每批交接一段索引范围；小范围直接在调用线程执行。三轴 P2G 各自保持粒子顺序，G2P 采样、单元重建和预检后的载体列写入各自的预分配区间。接收格按供给格和份额的原顺序汇总，独立格之间可并行；守恒审计中的求和顺序保持固定。工作任务全部结束后才返回结果或原始异常，任一阶段失败就丢弃整份候选，保留当前区域状态。成功后可直接交接这个对象一次。需要存档或诊断快照时，提交前调用 `state()` 生成不可变状态；提交后该对象已成为活动区域，历史候选的 `state()` 会拒绝调用。完整提交过程见 [运行时与区域操作](runtime-and-lifecycle.md#candidate)。

单次私有后端请求通过 `LiquidGeometryStateMapper.ReadScope` 复用不可变控制体的已校验几何，以及同一完整片段集合的互不重叠证明。打包与冷恢复共同检查面的平面、方向和凸性，以及完整外表面的凸性与成对有向边；自交面和未使用顶点拒绝交接。打包后的缓存按保存格式统一面顺序与顶点循环，并复用源几何已经积分的精确体积和空间矩；冷恢复从保存的面重新积分。集合键保存不可变副本，新增或替换片段后重新校验；库存、动量和碰撞占据按当前输入检查。控制体缓存及集合证明缓存各有 65,536 个片段引用的上限，请求结束后释放，完整候选继续接受全部提交校验。

初始子步数由液体、颗粒速度与重力预测的 Courant 数决定。压力加速后的液体 MAC 速度与颗粒速度还要在碰撞预览前复检。Courant 超限或颗粒非线性阻力迭代未收敛时，`MechanicalTimeRefinement` 携带所需子步数，CPU 后端丢弃候选，从原请求重新恢复并细化整个力学步；最多细化十六次，仍无法满足条件则拒绝候选。重算包含实体输入、气体状态与待同步相变几何，成功候选只提交一次。直接调用可变 `FluidDomain.step()` 的诊断代码会收到该细化异常；生产调用统一经过后端。

成功结果携带本步输运、化学、颗粒反应和相变诊断。力学 `StepStats` 另含颗粒沉积与唤醒诊断。调度器据本步结果判断活动状态。

`FluidStepRequest.mechanicalAuditEnabled` 控制可选机械诊断。启用后，`StepStats` 记录速度传递、颗粒与多孔耦合、重力、浮力、张力、固定约束、压力、碰撞、库存转移和粒子拆并各阶段的动能变化，并报告初末重力势能、热学机械功与各项收支的合计误差。该开关增加诊断记录，物理计算和存档库存保持相同规则。

## 网格与 marker

- 默认 cell size 为 0.5 m；每个 Minecraft block 对应 2×2×2 cells。
- 每个 marker 的 raw float `particleVolume` 是液体体积原始数据，物理总量是这些二进制有理数的精确和；逐格 marker sum 只由该原始数据正向汇总。顺序相关的 float/double reduction 只能作带解析舍入界的诊断，不能替代 exact ledger。
- `volume[]` 是面向观察和表面几何的 binary32 单元体积分数，默认由同格 marker 精确体积和一次舍入生成；pressure、PLIC 与气腔容量共用精确单元库存。粒子平流对照路径以固定核生成平滑体积分数与守恒压力支撑。
- P2G/G2P 使用共享的 valid face；均匀密度时 face 分子/分母按 marker 体积散射，变密度时按 marker 质量散射，并在归一化前保留同一份 P2G face weight 供动能度量。MAC 空气外推从开放压力面播种，G2P 不采未定义空气零值。三轴 face splat 直接写入固定 SoA，不为每个 marker/axis 创建 callback；上一 logical step 末已完整发布且 `gridDirty == false && velocityGridDirty == false` 的派生网格可供下一步首个 substep 直接使用，particle advection 与任何 carrier edit 都会重新置 dirty，后续 substep 不能跳过重建。
- 满液体压力空间要求每个非固体单元均属于 liquid pressure phase。该空间的 G2P 先以 marker 质量内积将已有速度投影到 MAC 插值空间，丢弃该采样空间无法解析的分量，再写成 `v(alpha) = v_pic + alpha * (v_resolved - v_p2g)`。能量预算为 `E_resolved + min(E_predicted_grid, E_projected_grid) - E_p2g_grid`，保留可解析流动的 P2G 方差，使光滑流动的衰减由 Newtonian 黏性应力控制。含自由面的离散沿用原 marker FLIP 增量，并采用预测与投影网格动能的较小值作为预算；其额外数值耗散单列诊断。两者以 PIC 动能作为可行下限，解析选择 `[0,1]` 内满足预算的最大 `alpha`。质量正交投影使用矩阵无关 PCG 求解 `B^T M B q = B^T M v`，返回 `Bq`；残差精度对应 binary32 插值精度。
- reseed 以标准 `2×2×2` marker quadrature 的单 marker 体积决定逐格目标数；split 保持体积、质量、动量和质心。merge 按相同 material、phase、密度、温度、指示剂和逐 species 浓度分组，先选择分散的代表点，再构造保持质心与完整空间协方差的正权重积分规则。体积之和须可精确表示；raw float 体积的双向减法同时检查细小尾数。可行方案耗尽时保留原积分点并报告拒绝原因。GPU 的相同判定须使用精确求值。
- 生成使用标准四分之一格积分点，reseed 的对称拆分保持质心与可放置留距。运动中的 marker 按积分点处理，外壳与整格固体的接触余量取域尺度的一个 float 位置 ULP；切割微单元执行扫掠点碰撞。法向接触保留切向速度。

空气外推以实际 pressure phase 面与真实 carrier 面的并集播种，保留已经投影的液体面。满液体压力空间的闭合固体面是已知零法向速度，参与 G2P 插值的完整权重；自由面沿用开放 valid face 的归一化插值，该传递近似与网格能量预算共同验收。P2G 与 G2P 在域外壳附近使用相同的截断坐标。

`DivergenceFreeVelocityExtension` 对连接到 pressure phase、仍有 marker 的空气分类单元延拓不可压运动。它锁定液体面和固体面，只修改完全开放的自由空气面，以最小二乘通量修正消除完整开放支撑中的离散散度。此工作区保持物理压力原样，也保持所有库存原样；其用途是让自由面积分点继续沿连续体速度运动。没有可调整空气出口的封闭气体分量按实际液体面通量保留平均压缩率；未解析的孤立液滴与薄层按 marker 速度运动。partial cut-face 继续使用既有切割插值。延拓后的速度再次接受 CFL 校验，超限由完整私有候选细化时间步。

完整面支撑的 `H(div)` 重建读取 cut-face 开口。完全闭合的固体面保持整张面上的零法向速度，面平均值与两个切向斜率均为零；相邻开放面的斜率继续由共享 stencil 推导。内部二次修正保持点散度与 MAC 离散散度一致。该规则覆盖整格固体的角落和台阶；partial cut-face 使用已有切割插值与 CCD。

### 空间积分误差与分布诊断

总库存守恒、压力体积估计和粒子空间分布分别校验。压力体积由移动积分点和固定紧支撑核汇总；标准四分之一格积分点给出满格初态，经过剪切和旋转变形后，该固定积分规则会产生局部过量与不足。散射限制在积分点所在的开放连通分量内，闭合的切割面阻断跨分量体积贡献。

[ClosedLiquidVolumeConstraint](../../solver/src/main/java/cc/ecisr/mmm/fluid/ClosedLiquidVolumeConstraint.java) 从 cut-cell 开放面建立封闭连通分量，几何掩码变化时刷新索引。若分量内液体的精确体积等于可用容量，不可压液体充满该分量，逐格压力体积直接采用实际开放容量；颗粒占据从容量中扣除。相等判定对 raw float 按指数累加整数尾数，保留小于 double 求和分辨率的真实缺口。该派生约束保持载体库存与位置原样。含真实空气体积的分量继续使用积分点的压力核。`pressureFluid` 按重建 PLIC 是否包含格中心分类；重建体积分数取容量归一值并限制在 `[0,1]`。自由面局部过量在分类中饱和，局部不足仍可能形成采样误差。

[MomentPreservingMergePlan](../../solver/src/main/java/cc/ecisr/mmm/fluid/MomentPreservingMergePlan.java) 在提交前预检整组替换。[QuadratureMomentFit](../../solver/src/main/java/cc/ecisr/mmm/fluid/QuadratureMomentFit.java) 以对称协方差映射恢复体积加权质心和六项二阶矩，并按原速度的最小二乘线性场更新新积分位置上的速度。空间秩为 `r` 的组至少保留 `r+1` 个正权重点；秩损失、越格、固体碰撞或超出速度舍入范围的动能增长均拒绝整组替换。逐材料质量、species 与内能沿原库存合并规则提交，均匀密度组同时保持质量加权矩。不可解析速度残差的耗散计入既有 reseed 机械诊断，位置与速度的误差受 binary32 写入精度限制。同温、同密度、同浓度分组保留已有局部物性场，必要时保留多于目标数的积分点。

对称拆分用载体体积决定积分尺度。令 `V` 为父载体的 cell-normalized 体积、`h` 为格长，各向同性特征长度为 `h*cbrt(V)`；沿所选轴将体积二等分，对应的子积分点距离父点为四分之一特征长度。位移还受既有可放置区间限制：

```text
d <= h*cbrt(V)/4
Delta spatial_second_moment = V*d^2 <= h^2*V^(5/3)/16
sum_binary_split_tree Delta moment <= h^2*V_initial^(5/3) / [16*(1-2^(-2/3))]
```

这些式子描述正权重积分点的空间二阶矩变化，位置写入另有 float 舍入误差。尺度采用各向同性近似；持续剪切后的载体形状还需要额外的空间解析度。完整开放核支撑中的单次拆分满足 `sum_cells |Delta pressureVolume| <= 24*V*(d/h)^2`，由现行压力核的一维二阶导数上界推导。切割几何的截断与归一化另行测量。逐格数量维护、合并矩约束及拆分误差界分别守护对应不变量；连续变形和自由面压力核的积分精度需要独立校验。

[MarkerDistributionExperiment](../../fabric/src/test/java/cc/ecisr/mmm/fluid/MarkerDistributionExperiment.java) 分别测量固壁法向轮廓、固定速度场的正反向平流、解析保面积映射、单次重播种的二阶矩和溃坝长场景。报告 raw cell volume、容量限制前的 pressure footprint、局部过量积分、低于中心分类条件的格数和体积加权位置矩，并对照封闭满液体约束后的压力体积。自由面解析对照从逆映射独立积分真实覆盖率，用两级参考积分报告基准离散差异；分别细化移动积分点与网格，校验归一化 L1 误差递减。该对照保持封闭满液体约束不触发，帮助区分时间积分误差、压力核的界面平滑与粒子采样误差。默认有限体积输运的空间收敛与逐格容量校验见[单元有限体积输运](#单元有限体积输运)。

### 内存布局

`ParticleToGridStage` 分别重建单元库存几何和 MAC 速度。`gridDirty` 跟踪位置、容量和材料库存，`velocityGridDirty` 跟踪待执行的速度传递。库存几何重建后标记速度待更新；热学、气体归属、存档捕获和单元观察调用 `ensureInventoryGrid`，需要速度的力学阶段与速度查询调用 `ensureGrid`。完整速度重建仍执行三轴 P2G、权重归一、固壁约束与空气速度延拓。两项工作区都从当前载体原始数据推导，恢复后按需重建。

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

`CellLiquidVolumeTransport` 根据投影后的 MAC 法向速度积分共享面的 PLIC 扫掠体积。`ConservativeLiquidFlux` 使用低阶供给、几何通量修正和接收格容量共同限制通量，并在 `2^-26 BV` 格点上保存精确公共面通量。单元更新满足 `V_new = V_old + sum(F_in) - sum(F_out)`，各格正体积和开放容量在提交前逐格检查。颗粒位移造成的容量变化沿已经选中的物理通量分配；供给限制保持每格本子步的实际库存。

有限体积步长使用正库存供给格的总出流 Courant 数 `sum(|u_face|*dt/h*A_open/O_cell) <= 1`，partial micro face 的扫掠宽度还须小于等于半格。初始估计包含重力预测，压力后与 G2P 后再次检查。连通薄层的分类复用 `DivergenceFreeVelocityExtension` 的开放面搜索，每次 P2G 重建后重新推导；独立液滴继续使用位移 CFL 与 CCD。

`CellCarrierRemapPlan` 按单元、材料、指示剂和有界 species 并集分组，同一体积通量搬运材料质量、动量、组成和内能。species 使用整数最大余数分配；质量与内能的可表示余数分配到有容量的份额，提交前检查逐材料质量的 binary64 舍入界、精确 species、精确逐格体积、内能和动量收支。热量重建复用实际质量与组成的 caloric 数据，在相邻输入温度范围内保持正温，并保留完整能量审计。

提交后的 marker 是单元空间积分点：满格采用八个四分之一格点，保留完整三维协方差与可解析仿射速度场；较少 chemical quanta 使用完整的 1、2 或 4 点规则，partial cut-cell 使用开放 micro box。binary32 表示需要的正体积尾数保留为额外积分点。速度场以单元张量多项式重建，偏差动能受输入动能约束。所有基础列、chemistry 与 thermal side column 在完整预检后由 `CarrierMutationCoordinator` 一次发布；本步随后复用已发布积分点。

`CellRemapInventoryAudit` 在发布前分别校验精确体积、逐材料质量、液体内能、动量和动能。发布逐值写入已检查的内能列，并登记这次预检的精确液体能量舍入差额；气体、颗粒与固体内能在该操作中保持原值。完整标准八点规则用张量逆变换批量求值，切割与细小库存尾数沿各自实际积分位置求值。

`MechanicalStepAudit.volumeTransportTransferJoules` 单列重建造成的机械能变化，CCD 接触功、P2G/G2P 变化和黏性耗散各自记录。该空间离散的误差由解析不可压旋流的三级网格检验：`N=12/24/48` 的体积归一化 L1 误差约 `0.04199/0.01743/0.00772`，逐格容量和总精确体积同时闭合。持续旋转至 `t=4 s` 后，`N=48/96`（格长 `0.125/0.0625 m`）的误差约 `0.03751/0.01670`；`N=24` 的细丝尚未充分解析，误差约 `0.28157`。两级独立参考积分的归一化差异分别约 `0.000171/0.000080`，空间误差的递减须超过该参考离散差异。切割几何、颗粒占据、分层和自然对流使用各自回归场景约束适用范围。

完整 `free-surface` 对有限体积路径运行 `12×8×6` 网格的 5,000 步溃坝（`h=0.5 m`、`dt=0.025 s`）。每步检查原始积分点体积精确和未超过开放容量、压力体积与同格库存相等，以及满格八个积分点保留独立的四分之一格位置。全程总体积保持 216 个单元体积，最终质量差为零，累计 binary64 质量舍入上界约 `2.46×10^-7` 个求解器质量单位。

### 当前自由面的空间解析限制

默认输运按 `DivergenceFreeVelocityExtension.advectionCells` 选择路径：压力液体格及经开放面连通的含液格进入共享面通量；其余粒子由 `BallisticLiquidRemap` 按当前粒子速度推进并执行 CCD，目标格预留对应库存容量。压力分类要求重建体积分数大于半格，并且 PLIC 覆盖格中心；`LiquidViscosityStage` 的参与单元还须属于这个压力分类。

面通量重建保存单元库存与物性场，使用固定单元积分位置。该表示未保存局部液体的空间一阶矩，无法保持任意格内液滴的实际质心。单个 PLIC 平面也无法表示完全位于格内、四周被气体包围的闭合液滴。现有独立粒子路径保留这部分位置数据，但缺少对应的连续压力和黏性控制体。分离、连接和格内平移时，两类表示的连续性仍需完成统一空间离散；任务与完成条件见[产品路线](../roadmap/product-roadmap.md#自由面统一空间离散)。

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

液液张力使用 phase 无序对定义，以 balanced-force CSF 接入同一 pressure 路径。液气曲率法向始终只来自液气几何；润湿由 Young 表面能 `sigma_sl - sigma_sg = -sigma cos(theta)` 的切向离散梯度产生，固壁面积取自同一 `2×2×2` cut-cell microgeometry（含域边界），不旋转曲率法向，也不使用浅水吸墙、墙角偏置或材料 ID 特判。

当前 `gpu-probe-kernel` 只覆盖单一常密度不可压 carrier 的 pressure 几何、P2G/G2P、散度一致平流、碰撞和守恒 reseed。它尚未承载材料质量列、变密度、液气/液液表面张力与 Young 壁能、gas boundary、热化学或完整 immutable candidate，因此这些 CPU 能力不能被记作 GPU 已实现；probe 继续不具备生产资格。

不可互溶相共享单一宏观速度场，求解静水压力、密度分层与界面张力。切向滑移、乳化与液滴破碎不在当前求解范围。

## 固体与碰撞

- Fabric 侧 `MinecraftCollisionWorldPort` 将 Minecraft `VoxelShape` 保守采样成不可变 `CollisionField`；core 再把 micro mask 转成 cut-cell face aperture。pressure、预条件器、投影和 marker CCD 使用同一几何。
- CCD 只截断命中法向，切向保持自由。
- 世界碰撞变化挤出的液体必须从 marker 账本得到实际量；不能用格密度推断 escaped volume。
- granular、deposit 和 coherent solid occupancy 与 liquid 共享容量和压力排水路径。

`FlipCutCellGeometry` 从每格 `2×2×2` unsigned micro mask 得到 cell solid volume 和三轴 face aperture。checkpoint 与后端 handoff 保存 raw mask，不能退化为“整格固体/整格空气”；恢复后必须得到相同 aperture。`FlipParticleCollision` 对 marker 轨迹做连续碰撞检测，避免只在步末投影位置造成穿薄墙；碰撞只去除命中法向速度，切向分量保持。

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
