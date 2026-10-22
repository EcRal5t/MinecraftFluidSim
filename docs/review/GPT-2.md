# 核心整合第二次独立审阅

审阅日期：2026-10-23。源码基线：`0f388ca150c03486b0bf612e02c3591f19ca09db`。

本轮重新沿库存、几何、压力、动量、气体、热学、相变、材料编辑、提交和验证入口检查现有实现。按游戏尺度评价，允许已声明的一阶耗散、集总近似和微小数值误差。没有读取 `docs/review` 中其他报告，也没有按旧问题清单逐项验收。

**当前仍不足以认定“大幅降低维护和排障成本”已经完成。** 本轮发现的核心问题包括：正常多腔孔流因微小数值差异中断；只读设备查询污染相变工作区；有限设备与普通导热采用不一致的能量分配规则；已经退出生产调用链的热接触算法继续保留独立入口和测试。追加深挖还发现相变排液缺口、完全汽化后的存档规则冲突，以及碰撞计划遗漏动量状态和待同步状态设计冗余。这些问题会继续让开发者跨多个模块追查同一个现象。

追加审查均沿用同一源码基线。本次继续深挖新增第 12—14 项，保留此前第 1—11 项；第 12、14 项分别以临时程序复现。新增问题涉及气体组分表示对导热的干扰、没有生产调用的气液传质配置，以及生产反应链与独立测试引擎的行为分叉。

以下只记录缺陷和不足。P1 表示会中断正常计算、应优先处理；P2 表示功能、模型一致性或维护成本问题。证据分为“运行复现”“代码直接推导”“验证覆盖不足”，不将覆盖不足等同于已经证明物理算法错误。

## 1. [P1，运行复现] 四腔串联的普通孔流就会因求根失败中断

位置：[GasOrificeFlow.java:76](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/fluid/air/GasOrificeFlow.java:76)、[AirNetwork.java:395](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/fluid/air/AirNetwork.java:395)、[MechanicalStepPipeline.java:66](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/MechanicalStepPipeline.java:66)。

**触发条件非常普通：** 四个 0.125 m³ 气腔串联，全部 300 K，首腔约 1.1 个标准大气压，其余约 0.9 个标准大气压；相邻腔之间开孔面积 0.0625 m²，流量系数 0.65，时间步 0.05 s。直接使用当前 `AirNetwork.stepEdges(dt, thermodynamics, thermalPlanner)` 和 `GasEnergyStore`，第一步即抛出：

```text
Gas port inventory/enthalpy root did not converge
```

本轮同一临时程序的结果如下。步号从 0 开始；没有修改生产源码或现有构建产物。

| 气腔数量 | 孔面积 m² | 结果 |
|---:|---:|---|
| 2 | 0.015625 | 完成 10 步 |
| 2 | 0.0625 | 完成 10 步 |
| 4 | 0.015625 | 第 1 步失败 |
| 4 | 0.0625 | 第 0 步失败 |
| 8 | 0.015625 / 0.0625 | 第 0 步失败 |
| 16 | 0.015625 / 0.0625 | 第 0 步失败 |

`solveThermal` 对每条边反复调用实际库存分配和热力学终态，再用割线法求连续流量的根。终态库存包含整数质量、标准体积和物种分配，因此压力响应包含离散台阶。当前终止条件采用 `1e-10 + mid*1e-10` 的残差或 `1e-10` 的区间宽度，每条边最多 32 次；这个预算与实际离散精度没有统一关系。

为了定位，另在临时目录编译了只增加异常数值的类副本。四腔、0.0625 m² 的失败发生在边索引 2、扫描索引 8：

```text
lo  = 0.0034330179296177053
hi  = 0.003433018054955606
rlo = -1.2036872232452922E-9
rhi =  1.940644742950623E-8
```

这个流量括区间已经只有约 `1.25e-10 m³` 标准体积，远小于有意义的游戏尺度变化，仍导致整步拒绝。另一个四腔案例在最后一次迭代更新后区间已经小于宽度阈值，循环结束却没有重新判断，仍抛出同样异常。增加一两次迭代只能遮住个别样例，不能统一离散库存和求根精度。

**影响：** 有限压力梯度的均压流程不能保证持续运行。机械管线将异常转为 `GasThermalBlockedException`；后端的时间细化只捕获 `MechanicalTimeRefinement`，这条路径直接拒绝候选。此处需要容忍的数值误差被转成了玩家可见的仿真停止。

**维护成本还在增加：** 每条边、每次割线试算都执行全网络 `flowBatch → flowPressures → flowEnergy → donorFlowEnergy/绝热状态函数`。工作量包含“边数 × 外层扫描 × 内层求根 × 全网络库存重建”，并反复生成分配对象。这里没有用正式预算复测来宣称具体性能超标；问题是从调用链即可确认的嵌套规模成本和多处精度耦合。以后改气体量化、焓模型或材料比热，都可能重新触发求根失败。

**修改方向：** 孔流数值未知量、可执行库存分辨率、终止条件和能量终态应形成一个一致的求解规则；将全网络库存实例化从逐边内层试算中移出。复用现有气体验证入口，加入这类带热学的串联网络及端口重排。当前端口顺序、初始平衡端口的测试主要使用等温入口；热学两腔放空测试不能覆盖本问题。

## 2. [P1，运行复现] 只读设备接触查询污染相变缓存，随后出现数组越界

位置：[FiniteDeviceExchangeCoordinator.java:88](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/FiniteDeviceExchangeCoordinator.java:88)、[ThermalCellOperations.java:221](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/ThermalCellOperations.java:221)、[ThermalCellOperations.java:306](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/ThermalCellOperations.java:306)。

`finiteDeviceContacts` 用 `exchangeCarrierAccess` 重建 `domain.grid()`，把气腔接触样本加入这个共享网格。相变扫描需要的是凝聚相网格；其缓存仅检查载体对象和液体、颗粒代次。只读查询没有改变这些代次，于是后续 `preparePhaseScan` 直接返回，保留了带气体样本的网格。

相变候选枚举仍按“液体、连续固相、活动颗粒、沉积”的序号布局解释网格载体。末尾的气体样本被当作沉积，访问不存在的沉积条目。

本轮用一个 0.5 m 格、半格液体、半格有限气体、没有任何固相的状态执行：

```java
state.preparePhaseScan(context);
var before = state.thermalRevision();
state.finiteDeviceContacts(List.of(new CellContactRequest(0, 0, 1)), context);
// before.equals(state.thermalRevision()) == true
state.preparePhaseScan(context);
state.preparePhaseCandidateCell(0, context);
```

结果：

```text
readOnlyContactRevisionUnchanged=true
java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
  at ThermalCellOperations.preparePhaseCandidateCells(ThermalCellOperations.java:306)
  at ThermalDomainState.preparePhaseCandidateCells(ThermalDomainState.java:686)
```

**影响：** 库存完全相同，仅仅增加一次查询，就改变后续相变行为。即使尚未走到越界，读取到的也可能是含气体热容的共同温度，改变凝聚相的温度筛选和属性计算。

当前有限换热器方块尚未接入世界，故本项证明的是现有设备 API 与相变 API 的组合故障，不能据此声称普通玩家每次放置热浴都会触发。API 本身已有区域调用和提交实现，继续接入设备时会直接继承这个缺陷。

**修改方向：** 凝聚相相变工作区与包含气体的设备接触视图应具有明确的数据归属。缓存有效性必须覆盖工作区当前内容，不能只覆盖库存代次。优先消除共享可变网格的两种载体布局，避免向各调用方分散补充“调用后记得失效”的规则。

## 3. [P2，代码直接推导] 有限设备输入微量热量，会让同格载体瞬间大量交换内能

位置：[FiniteDeviceExchangeCoordinator.java:117](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/FiniteDeviceExchangeCoordinator.java:117)、[FiniteDeviceExchangeCoordinator.java:159](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/FiniteDeviceExchangeCoordinator.java:159)、[ThermalCellGrid.java:287](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/ThermalCellGrid.java:287)、[ThermalCellEnergyAssignment.java:44](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/ThermalCellEnergyAssignment.java:44)。

普通导热按各载体当前内能和有限热导成对交换。设备路径则先把同格所有载体计算成共同温度，得到单元热量增量，再调用 `setCellInternalEnergy`，按照共同温度重新分配该格所有载体的内能。此时液体、固体和气体样本全部参加。

一个可以直接由公式算出的例子：同格两个不同载体，各 1 kg、比热 1000 J/(kg·K)，初温分别 300 K 和 500 K，二者热导很小甚至为零。设备向这个格加入 0.001 J 后，两个载体都会被写成约 400.0000005 K；内部发生约 100000 J 的重新分配。把设备热量从 0.001 J 再减小，也不会相应减小这次内部交换。只有恰好等于零时，`editedCells` 才跳过写入。

**影响：** 总内能账可以完全闭合，但有限导热速率、绝热材料的隔离和局部温度历史被破坏。热冲击、气体压力与相变触发都可能因此出现突变。误差不会随设备输入趋近零而趋近零，不能按普通一阶时间离散误差解释。

[thermal-state.md:230](D:/Proj/260817-MinecraftMod/fabric-example-mod/docs/architecture/thermal-state.md:230) 说明同格温差按有限速率消散；设备路径没有遵循同一规则。这里记录的是已有有限设备计算接口的问题，世界方块接入仍属于后续产品工作。

**修改方向：** 设备应连接实际热载体，通过同一组有限接触推进并分别写回内能。设备接触位置、导热和载体分配要在准备阶段一并确定，避免把“单元观察温度”当作强制重分配条件。

## 4. [P2，代码直接推导] 有限设备不能跨越普通比热分段点，减小时间步也无法解决端点降温

位置：[FiniteThermalExchange.java:159](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/FiniteThermalExchange.java:159)、[FiniteThermalExchange.java:179](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/FiniteThermalExchange.java:179)、[ThermalCellGrid.java:407](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/ThermalCellGrid.java:407)。

设备接触只保存初始温度、热容和当前比热线性区间，求解期间没有重新反演接触侧状态函数。温度跨出初始区间就抛出 `Finite thermal contact left its linearization interval`。设备本体可以使用分段状态函数，接触侧却被固定在初始段内。

例如，合法流体材料有 `[1, 300]`、`[300, 1000]` 两个比热段，初温恰好 300 K。网格的 `< segment.maximumTemperatureK()` 选择后一个段，接触最低温度为 300 K。只要设备稍冷、热导为正且时间步为正，就会算出低于 300 K 的接触温度并拒绝。只要降温量仍可由浮点表示，继续减小时间步仍会越过这个初始端点。

此外，这条设备路径在显式子步超过 8 时直接拒绝；普通载体导热已经在相同情况下改用隐式接触扫描。相同材料面对恒温面、另一种载体或有限设备，能否继续换热取决于入口，而不是统一的状态函数和接触模型。

**影响：** 数据驱动的分段比热定义一旦用于设备接触，普通升降温可能需要用户或调用方绕开分段温度。未来调物性会增加与材料本质无关的排障工作。

**修改方向：** 让有限设备进入已有载体状态函数及接触求解过程，或提供能跨段更新的同等统一实现。不要要求外部调用者识别段点并分拆交换，也不要为端点额外加入任意温度偏移。

## 5. [P2，代码直接推导] 旧热接触几何算法和测试仍形成第二条实现路径

位置：[ThermalTransport.java:73](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/ThermalTransport.java:73)、[ThermalDomainState.java:899](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/ThermalDomainState.java:899)、[CarrierThermalConduction.java:12](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/CarrierThermalConduction.java:12)、[ThermalContactValidation.java:66](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/test/java/mmm/thermal/ThermalContactValidation.java:66)。

当前生产热化学管线从 `ThermochemistryPipeline.stepTransport` 进入 `CarrierThermalConduction.contacts` 和 `ThermalCarrierTransport.solve`。与此同时仍保留：

- `ThermalTransport.step/stepPrepared` 的整套同格、相邻格、恒温面接触构造。
- `ThermalDomainState.step(markers, faceArea, boundaryFace, dt)` 的公开执行入口、快照回滚和独立热账提交。
- 每个 `ThermalDomainState` 构造旧 `ThermalTransport` 对象，生产路径主要从中取得子步预算。
- 多个测试直接调用旧接触构造，包括 `condensedCellTransportHasFiniteRates`、`isolatedContactRangesRetainTheirOwners` 和恒温面相关检查。

两套接触图的几何权重不同。旧路径按单元体积乘积及回调面积分配接触；当前路径按固定微面支撑、覆盖份额和气腔样本建立接触。它们虽然最后复用同一个数值接触求解器，空间离散和载体范围仍然各自存在。

**影响：** 修改热导几何时要判断两套公式、两套入口和两批断言是否都应修改；旧测试通过不等于当前生产接触图正确。类名、注释和公开入口仍使旧路径看起来可用于正常计算。这是直接违背“调用迁移后删除旧路径”的维护负担。

**修改方向：** 保留真正独立的热状态函数和接触求解器测试；将空间接触验证迁移到生产图构造入口，再删除旧的空间接触实现和未使用公开流程。`Stats`、预算常量等共享数据可以独立保留，不必为它们保留一个旧求解服务。第 3、4 项也应随设备职责链合并处理，避免继续形成第三套接触规则。

## 6. [P2，代码直接推导的能力缺口] 蒸气完全失去液相后，冷却不能重新生成液体

位置：[GasLiquidPhaseChangeStage.java:123](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/GasLiquidPhaseChangeStage.java:123)、[GasLiquidPhaseChangeStage.java:126](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/GasLiquidPhaseChangeStage.java:126)、[GasLiquidPhaseChangeStage.java:139](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/GasLiquidPhaseChangeStage.java:139)。

相变从现有液体库存出发枚举候选。区域没有对应液体时，`hasMaterial(liquid)` 直接跳过；有少量液体时，也只能向满足同一支撑气腔条件的现有液体库存凝结。代码没有从气体、冷壁或空闲支撑创建第一份凝结液体的路径。

因此，完整汽化后再冷却，或者把蒸气送入一个起初干燥的冷腔，均无法靠现有相变流程恢复液相。人工补入一小份对应液体会改变是否存在相变候选，前后的宏观行为发生离散变化。

这里应准确区分：当前专题文档确实将凝结定义在已有气液接触上，本项是工业仿真能力与泛用性的缺口，不能声称代码违反了该局部公式。实施计划却将冰／水／蒸气相变列为可用，完整汽化也有明确处理，尚未说明这种不可自行返回液相的循环限制。

**影响：** 蒸发—输送—冷却—回流这一常见工业流程缺少凝结液体的初始生成环节；延长重放或增加相变操作预算不会解决。

**修改方向：** 明确产品所需的冷壁凝结或简化成核规则，并通过现有库存、热量和容量计划生成液相。可以采用游戏尺度近似，无需引入科研级成核模型。若本阶段确实排除干腔凝结，应把这一限制放进场景能力说明，不能用已有液面凝结测试覆盖整个闭环能力。

## 7. [P2，验证覆盖不足] 漩涡验收时间远小于自身转动时间，不能支持长期保持的结论

位置：[FlipFluidFreeSurfaceValidation.java:114](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/test/java/mmm/fluid/FlipFluidFreeSurfaceValidation.java:114)、[LiquidMacroscopicMotionValidation.java:88](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/test/java/mmm/fluid/LiquidMacroscopicMotionValidation.java:88)。

平滑漩涡场的长度为 4 m、速度幅值约 0.02 m/s，完整测试只有 160 × 0.025 = 4 s。无量纲输运距离 `U*t/L` 约为 0.02，最大流速对应的路程只有 0.08 m，小于两档网格的格长。由该流函数在中心附近的线性化，角速度约为 `0.02*pi/4 = 0.0157 rad/s`，4 s 仅转约 3.6°。

另一个三轴旋转测试，角速度为 0.1 rad/s，运行 4 × 0.025 = 0.1 s，仅约 0.57°，却允许角动量衰减 20%。测试覆盖了初始投影和非常短的后继，未覆盖旋转物质跨越大量网格后是否还能保持可见漩涡。

**影响：** 现有断言允许的衰减，相对于实际完成的运动过于宽松。程序可以在远未转完一圈时失去大部分旋转，仍满足这些短时检查。这里没有运行长漩涡来断言生产算法一定如此；缺陷是当前验收不能支撑“具有合理持续时间的漩涡”及相应长期误差结论。

**修改方向：** 重写已有漩涡用例，以转过的角度或 `U*t/L` 定义验证时长，覆盖至少有意义的一段转动和跨格输运；沿已有两档网格观察环量／角动量与动能衰减，允许粗网格的一阶耗散。无需增加另一批仅换名称的微扰测试，也无需追求任意尺度的理想无耗散漩涡。

## 8. [P2，代码直接推导] 结冰膨胀只允许占用本格空余体积，连着可排液空间的满水格也无法结冰

位置：[CondensedPhaseChangeStage.java:187](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/CondensedPhaseChangeStage.java:187)、[CondensedPhaseChangeStage.java:25](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/CondensedPhaseChangeStage.java:25)、[ice_water.json:1](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/main/resources/data/mmm/mmm/phase_transitions/ice_water.json:1)。

**触发条件：** 一个完全充满水的格子冷却到冻结温度以下，旁边或上方连着仍有容积的格子，可以排开水。这里讨论可排液的开放容器；不要求模组模拟完全封闭容器内的冰压破坏。

内置冰水定义每批占容由水的 134 SVQ 增至冰的 146 SVQ。`propose` 对正体积增量先计算：

```text
room = 本格固定开放体积 − 本格颗粒、液体、连续固相占容
high = min(high, floor(room / 每批膨胀体积))
```

满水格的 `room` 为零，所以在进行热量判定之前就得到 `high == 0`，直接跳过。邻格有多少空余空间、液体是否能沿实际连通面流出，都没有进入计算。后续 `planCondensedGeometry` 仅准备固体占容和气腔后继；`pendingPhaseVolume` 也是相变成功后才写入，不能使被跳过的格子得到排液机会。

**影响：** 在满格液体保持静止的情况下，继续降温不会推动冻结。自由面上的未满格可能先冻结，内部满格仍会卡住，结果取决于网格填充率。这是普通结冰流程的缺口，量级远大于允许的微小守恒误差。

**维护方面的根因：** [GranularLiquidDisplacement.java:61](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/GranularLiquidDisplacement.java:61) 已经使用共同面网络准备固体占容变化引起的排液、动量输运和气腔后继。凝固却另行使用本格容量拦截。今后“固体增容时能否挤走水”的规则仍要分别修复。

现有 [ThermalPhaseValidationScenarios.java:307](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/test/java/mmm/fluid/ThermalPhaseValidationScenarios.java:307) 使用真实 146/134 体积比的近冻结点夹具只有半格水；它检查相变量化振荡，不能覆盖满格向外排液。这里依据明确的零容量分支作出判断，没有重复运行确定会返回零的测试。

**修改方向：** 固液相变在热量允许的范围内准备完整的固体增容、连通排液、动量和气腔候选，以真正可达的容量限制相变量；将这条链与已有固体排液职责合并。保留无处排液时的合理拒绝，不再用“本格已满”代替“整个可达空间不足”。

## 9. [P1，代码直接推导] 完全汽化后的合法待同步状态，会被存档校验拒绝

位置：[RegionCheckpointValidator.java:108](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointValidator.java:108)、[RegionCheckpointValidator.java:138](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointValidator.java:138)、[PendingPhaseVolumeWork.java:37](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/PendingPhaseVolumeWork.java:37)。

**触发条件：** 一格中的最后一份液体完全汽化，原格没有其他液体、连续固相或颗粒；有限气体已接收相变产物，在下一次机械推进之前保存这份状态。

这类终态已经进入生产实现：[GasLiquidPhaseChangeStage.java:285](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/GasLiquidPhaseChangeStage.java:285) 专门处理完全汽化的空凝聚态热库存，并在 309 行记录负的待同步液体体积。运行时恢复规则也显式支持它：

- [ThermalCellOperations.java:199](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/ThermalCellOperations.java:199) 对负体积允许不存在凝聚态热载体。
- `PendingPhaseVolumeWork.restore` 随后检查该格的有限气体后继库存，存在气体即可恢复。

然而 `RegionCheckpointValidator.validatePendingPhaseVolumeCells` 只把液体、连续固相、活动颗粒和沉积标记为 `carrier[cell]`，完全没有读取气体。最后对**所有正负待同步条目**统一要求 `carrier[cell] == true`。上述完全汽化格必然得到：

```text
Pending phase volume names a cell without a thermal carrier
```

这直接影响正常保存。[RegionCheckpointAssembler.java:103](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointAssembler.java:103) 会保存该待同步条目，[RegionGenerationStore.java:106](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/main/java/mmm/simulation/persistence/RegionGenerationStore.java:106) 在准备存档代次时先执行 `state.validate()`，因此拒绝发生在发布新存档之前。旧存档仍可能存在；本项指新状态不能保存，并非已经证明旧文件损坏。读取含同类条目的存档也会经过同一个校验器。

**测试缺口与排障成本：** [ThermalGasValidationScenarios.java:2670](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/test/java/mmm/fluid/ThermalGasValidationScenarios.java:2670) 的完全沸腾用例使用 `FluidDomain.captureAuthoritativeState/restoreAuthoritativeState` 检查内存快照往返。它没有经过存档校验器。持久化用例又自行构造待同步条目，未覆盖原格只剩气体的相变终态。因此“恢复已通过”可能只对应其中一条恢复链。

**修改方向：** 将待同步状态所需的载体有效性规则集中，并让内存恢复和存档恢复复用。用已有完全汽化场景的真实终态覆盖正式存档路径，替换一个仅证明字段能往返的夹具。无需添加更多互相独立的认证规则。

## 10. [P2，运行复现] 碰撞计划绑定了库存和热状态，却没有绑定会被它整体覆盖的 MAC 动量

位置：[DomainCollision.java:133](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/DomainCollision.java:133)、[DomainCollision.java:139](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/DomainCollision.java:139)、[CellMacStage.java:288](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/CellMacStage.java:288)、[FluidDomain.java:208](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/FluidDomain.java:208)。

`planCollisionEdits` 准备了基于当前速度的完整动量后继。`beginCollisionEdits` 检查计划所属对象、是否使用过、液体数量／体积／质量、库存代次、受影响格的旧碰撞、热代次以及气体计划，最后整体恢复计划中的 MAC 动量。

但 MAC 动量没有进入计划的有效性条件。公开的 `restoreMacMomentum` 可以更新动量，只设置 `velocityGridDirty`，不改变上述任何代次。它也没有类似 `restoreLiquidCellState` 的“仅允许在未初始化气体时恢复”限制。

**本轮最小复现：** 4×4×4 域，格长 0.5 m、密度 1000 kg/m³、零重力；在 `(1,1,1)` 与 `(2,1,1)` 放入各一满格水。为远处干格 `(3,3,3)` 准备变成实心的碰撞计划；随后通过公开接口把两水格间 X 面的动量恢复为 10 kg·m/s；再提交原计划。

```text
提交前：该面动量 10.0 kg·m/s，液体动能 0.39999998211860677 J
提交后：该面动量  0.0 kg·m/s，液体动能 0.0 J
```

计划没有过期异常，远处干格的几何更新静默覆盖了新增运动。该热量也未由旧计划准备，因为旧计划看到的是零动量。源库存和受编辑格的几何均未在两次调用之间改变。

**适用范围：** 这是公开操作接口与计划有效性协议的实际漏洞。本轮没有证明当前 Minecraft 主线程会自然交错这两个调用，不把它写成每次放方块都会丢动量。问题在于新的权威动量状态尚未纳入统一提交协议，调用者必须自行知道这项隐藏限制。

**修改方向：** 为会被覆盖的动量纳入共同状态代次，或在有效库存和几何计划提交时基于当前动量计算后继；若动量恢复确实只服务初始化，应像其他恢复入口一样限制生命周期。避免再增加一套总动量或总动能相等检查：不同空间分布可能有相同总量，仍会被旧计划错误覆盖。

## 11. [P2，代码直接推导] 待同步相变被保存成精确体积事务，数值却没有进入后续物理计算

位置：[PendingPhaseVolumeWork.java:19](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/PendingPhaseVolumeWork.java:19)、[MechanicalStepPipeline.java:141](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/MechanicalStepPipeline.java:141)、[PendingPhaseVolumeCodec.java:67](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/main/java/mmm/simulation/persistence/PendingPhaseVolumeCodec.java:67)。

追踪完整读写链后，目前精确 `SignedDyadic` 数值的用途是：累加与相消、判断是否为零、导出／恢复，以及恢复时按符号判断所需载体。实际推进只读取：

1. `GasLiquidPhaseChangeStage` 用 `isPending(cell)` 跳过本格继续气液相变。
2. `MechanicalStepPipeline` 用全域 `hasWork()` 记下本步是否有待同步状态，在真实压力投影后调用 `clear()`。
3. 主机转移、生命周期和调度据 `hasWork()` 决定是否先执行机械步、唤醒或暂缓操作。

**精确体积的大小没有作为源项传入压力方程，没有交给排液计划，也没有用于计算机械功。** 相变已提交的实际库存与几何承担这些物理输入。因此这份状态的实际职责是记录“哪些格的相变之后尚未完成机械推进”，而当前表示及命名让它看起来还承担一笔必须逐量结算的体积／功事务。

维护成本已经具体落在多个位置：独立稀疏快照、任意精度整数及指数的二进制编码、Manifest 扩展装配、存档恢复、运行时恢复、载体有效性校验，以及 [validation.md:46](D:/Proj/260817-MinecraftMod/fabric-example-mod/docs/maintenance/validation.md:46) 要求的逐格精确相消与快照检查。第 9 项就是运行时与持久化分别维护这些约束后出现的实际冲突。

这里不建议未经分析就删除所有机械推进屏障。**需要保留的调度语义，应与已经提交的物理量分开表达。** 如果仍要求在转移前推进一次力学，可用待推进格集合或清晰的几何／机械代次表达，并让内存和持久化共享该定义；测试守护相变后继续推进、转移、保存恢复的实际结果。若精确体积确实还应代表未执行的物理工作，则必须指出它进入了哪条离散方程；现有调用链没有这个入口。

## 12. [P2，运行复现] 同一种气体拆成等物性的组分后，同格导热面积被重复放大

位置：[CarrierThermalConduction.java:27](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/CarrierThermalConduction.java:27)、[ThermalGasCarriers.java:32](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/ThermalGasCarriers.java:32)、[ThermochemistryPipeline.java:30](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/ThermochemistryPipeline.java:30)。

`ThermalGasCarriers` 按气腔组分生成独立热载体，空间体积乘组分标准体积分数。同格接触随后对每一对载体独立计算 `min(fraction[p], fraction[q])^(2/3)`，再将各对导热量相加。这把同一混合气体的组分体积当成了多个分别拥有接触面的空间物体。

在液体占比不小于气体占比时，将气体均分成 n 个密度、比热、导热率完全相同的组分，液体与气体的同格总热导就从 G 变成 `n^(1/3) × G`。气体组成标签因此改变了物理接触面积。相邻格使用另一套体积分数乘积公式，在本例中保持同一热导，更突出了同格规则的不一致。

**运行复现：** 2×2×2 封闭区域，格宽 0.5 m；格 0 有半格水，其余空间为空气腔。液体初温 400 K，气体 300 K；液体与所有气体的比热都为 1000 J/(kg·K)、导热率都为 1 W/(m·K)，气体标准密度均为 1 kg/m³。分别定义 1、2、8 个等比例、等物性的气体组分，总气体填充量保持一致。直接读取生产接触构造结果，再执行 `stepThermalOnly(0.0001)`：

| 气体组分数 | 液体与本格气体的总热导 W/K | 液体与邻格气体的总热导 W/K | 单步液体放热 J |
|---:|---:|---:|---:|
| 1 | 0.629960525 | 0.75 | 0.013799605 |
| 2 | 0.793700526 | 0.75 | 0.015437005 |
| 8 | 1.259921050 | 0.75 | 0.020099211 |

8 组分时同格热导翻倍，包含邻格接触后的总放热量增加约 45.7%。时间步已取很小，差异来自接触系数本身，继续缩短时间步不能消除其相对偏差。这个复现检验的是传热速率和表示一致性；没有据此声称总能量凭空增加，也没有实测长期发散。

**影响：** 数据包增添等效组分就能改变冷却、加热及到达相变条件的时间；使用者会追查比热、气腔容量、组分配比和时间步，实际原因却在同格几何公式。它超出了允许粗略估算混合长度的范围，因为所需几何面积取决于物种如何命名和拆分。

**处理方向：** 同一空间支持内的混合气体应共享几何接触面积，按混合物热性质确定热导，再把交换能量分配给组分。利用现有生产导热验证增加一个等物性组分拆分对照即可，避免再建立一套脱离实际调用的热接触测试引擎。

## 13. [P2，代码直接推导] Henry 气液传质保留完整配置、存盘和测试链，却没有仿真调用

位置：[GasLiquidSpeciesPair.java:112](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/GasLiquidSpeciesPair.java:112)、[ThermalRegistry.java:378](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/thermal/ThermalRegistry.java:378)、[ThermalRegistryCodec.java:150](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/main/java/mmm/thermal/ThermalRegistryCodec.java:150)、[ThermalGasValidationScenarios.java:548](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/test/java/mmm/fluid/ThermalGasValidationScenarios.java:548)。

内置 [co2_water.json](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/main/resources/data/mmm/mmm/gas_liquid_pairs/co2_water.json) 定义了 Henry 温度表、适用压力、最大浓度、扩散率、液膜厚度与气侧阻力。[thermal-state.md:108](D:/Proj/260817-MinecraftMod/fabric-example-mod/docs/architecture/thermal-state.md:108) 也解释了这些参数如何形成传质量。然而，当前生产代码里读取 `gasLiquidPairs()` 的外部调用只负责注册表校验和序列化；单项查询也没有求解器调用。`henryConstantMolPerCubicMeterPaAt()` 与 `totalResistanceSecondsPerMeter()` 的调用者只有测试。

实际 `ThermochemistryPipeline` 按导热、液相反应、颗粒反应、固液相变、气液相变推进。气液相变采用 `ThermalPhaseTransition`，没有消费这套 Henry 传质定义。因此，修改 CO₂ 的 Henry 常数、扩散率或液膜厚度不会改变求解器的溶解／逸出速率；现有配对数据没有执行对应库存转移的路径。

**维护成本已经实际发生：** 定义类检查参数范围和连续性，数据包加载器解析它，注册表计算指纹，存档编解码保存它，运行时及存档加载分别检查跨注册表引用。测试还维护具体表值、越界拒绝、相同公式重算与序列化相等。这些成本保护的是配置形状，无法证明任何生产传质流程。排查“调参数为什么没效果”必须跨越这一整条文件链才会发现运行阶段缺失。

这里记录的是当前配置、文档与运行能力脱节，以及整合后仍保留的维护负担；不推断该功能过去曾经实现，也不要求本轮强行扩展产品范围。

**处理方向：** 明确当前产品是否需要溶解／逸出。若保留，应交付从接触面积、分压、浓度到守恒转移的完整调用链，并让现有测试覆盖正常推进；若暂不支持，将这些未参与运行的配置、持久化负担和对应测试一起撤出当前实现，文档同步说明实际能力。

## 14. [P2，运行复现] 反应中间态被库存条目限制拦截，合法终态永久无法到达；独立测试引擎却通过

位置：[ChemicalReactionStage.java:140](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/ChemicalReactionStage.java:140)、[ChemicalReactionStage.java:181](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/ChemicalReactionStage.java:181)、[ChemicalReactionStage.java:241](D:/Proj/260817-MinecraftMod/fabric-example-mod/solver/src/main/java/mmm/fluid/ChemicalReactionStage.java:241)、[ChemicalReactionEngine.java:24](D:/Proj/260817-MinecraftMod/fabric-example-mod/model/src/main/java/mmm/chemistry/ChemicalReactionEngine.java:24)、[M7ChemistryValidation.java:366](D:/Proj/260817-MinecraftMod/fabric-example-mod/fabric/src/test/java/mmm/chemistry/M7ChemistryValidation.java:366)。

生产阶段每执行一条反应，就调用 `distribute()`，立即将中间物种向量转成 `ChemicalMixtureRecord`，并检查每份库存最多 16 个物种。即使下一条瞬时反应马上消耗中间产物，程序也到不了那里。异常被 `step()` 捕获，整格不提交、保留 dirty 标记；后续每步从相同初态重算，再次遇到相同限制。

**最小反例：** 一个库存含 A 与 15 种不参与反应的物种，共 16 项；注册表声明完全解离 `A → B + C`，随后瞬时反应 `B + C → D`。A、D 的组成为 H₂、摩尔质量为 0.002 kg/mol；B、C 为 H、0.001 kg/mol。两步严格守恒、无环、无竞争消耗者、零体积变化，注册表正常接受。初态与最终 D 加 15 种惰性物种都只有 16 项，只有计算过程中暂时出现 17 项。

临时程序使用一个半满液体格，初始每种物种 1000 量子，调用实际 `ChemicalReactionStage.step()` 两次；同时将同一初态传给测试采用的 `ChemicalReactionEngine.react()`：

```text
model engine: entries=16, D=1000
pass=1, stats=ReactionStats[visitedCells=1, evaluations=2, changedStocks=0], A=1000, D=0, dirty=[0]
pass=2, stats=ReactionStats[visitedCells=1, evaluations=2, changedStocks=0], A=1000, D=0, dirty=[0]
```

复现关闭热学以隔离组成容量问题。实际失败发生在热量约束计算前；增加热学绑定也不会撤销这里的 16 项检查。固定库存下后续重试完全相同，必须等外部输运改变组成才有机会脱离挂起状态。该案例涉及扩展数据包，未声称内置配方已经触发它。

**根因同时包含算法和维护问题。** 单次逻辑反应过程的工作向量被迫服从持久库存的紧凑表示限制，造成永远无法自行消除的待处理状态。另一边，`ChemicalReactionEngine` 用完整向量执行整条反应链，只在最终结果规范化时检查条目数，因而成功。当前生产推进没有调用它的求解方法，却仍依赖它定义的异常类型；`M7ChemistryValidation` 的解离、酸碱反应和幂等性案例则直接测试这套独立算法。开发者修好其中一套或看到对应测试通过，无法判断游戏实际行为。

**处理方向：** 工作向量、热量可行性计算与最终库存分配应在同一条生产反应链内组织，避免为未提交的瞬时中间态套用最终库存条目上限。把现有反应正确性案例迁到真实生产入口，删除或合并不再承担生产求解的独立引擎；仅依赖其嵌套异常也应随职责整理。增加“16 项初态—17 项中间态—16 项终态”的案例即可覆盖这次新失败模式。

## 对守恒、误差和排障目标的判定

本轮能够确认的主要风险是：**算不下去、不同入口采用不同物理规则、缓存依赖调用顺序、验证时间尺度不足，以及合法运行终态不能通过存档链。** 第 1 项在宏观上几乎可忽略的差异处拒绝推进；第 3 项则在总能量账闭合时发生宏观温度突变；第 8 项把局部容量限制扩大成了普通冻结流程的功能限制。可接受误差的判定应连接实际分辨率和物理效果，当前这些方向仍没有统一。

第 3 项的错误热分配表现为局部温度突变，总内能账仍可闭合。本报告没有实测到新的质量／液体体积持续泄漏，也没有以此作出“所有守恒和长期稳定性已通过”的验收结论。质量、体积与能量账正确，仍不足以排除上述相变、压力推进和局部热行为问题。

第 10 项还证明了公开接口交错下的动量和动能丢失：计划检查通过，实际覆盖的权威状态却已经改变。它的触发范围已单独限定，不据此推断正常时间推进必然能量发散。

第 12 项进一步证明，总能量闭合也能伴随显著的传热速率偏差：该偏差跟随组分表示变化，减小时间步无法消除。第 14 项则是确定性的推进停滞，增加重试次数不会得到终态。两者都需要整理模型表示与执行流程，继续调小数值参数无助于解决原因。

对最重要的维护目标，当前仍有七项具体阻碍：

1. **状态归属不完整。** 热库存有代次，承载两种载体布局的共享网格却没有同等清晰的归属和有效性约束。一次查询造成的错误会在相变模块暴露，异常位置远离原因。
2. **相同物理规则存在多个入口实现。** 普通导热、旧热接触图、有限设备交换分别维护几何、温度推进和提交逻辑；修复一条路径不会自然修复其他路径。
3. **数值方法与库存细节耦合过深。** 气体逐边求根直接穿透全网络库存、物种分配和焓模型，稍微改变网络或量化结果就可能失败。仅继续调整局部迭代次数，会延续反复排障。
4. **跨格容量与提交协议没有覆盖完整职责链。** 颗粒排液已有共同网络，凝固仍按本格空余量拦截；碰撞计划仍会覆盖它没有绑定的动量状态。共同提交方法存在，仍不足以保证所有参与状态遵循同一规则。
5. **运行时与持久化各自解释相变状态。** 完全汽化可以成为运行时终态，却被存档校验拒绝；一份只用于调度的待推进状态又带着精确体积、格式和相消测试。修复必须跨热学、相变、运行时恢复、存档和测试，仍符合用户希望消除的排障困境。
6. **热学和反应都把存储拆分方式带入物理规则。** 同一气腔按组分拆出的载体生成了重复接触面积；瞬时反应工作向量过早受限于库存格式。问题会以温度或反应现象出现，排查却必须理解底层表示。
7. **配置与验证存在脱离生产能力的分支。** Henry 参数从加载到存盘都在维护，求解器却不用；另一套反应引擎的测试能通过，生产链仍然停滞。文件数量和测试数量因此不能代表整合后的维护成本下降。

优先处理发现 1、2、9 的正常流程中断；按完整热交换职责链处理发现 3、4、5。发现 8 应纳入共同容量与排液职责，发现 9、11 应一起整理相变后的推进和持久化语义，发现 10 则补齐统一提交所依赖的动量状态。发现 6 需要产品能力决策，发现 7 需要改写现有验收输入。以上应作为几组完整职责链交付，避免将每个异常分别修成独立补丁。

本次新增的发现 12 应并入热交换几何与载体表示的整理，发现 14 应合并生产反应和测试入口，发现 13 则应先明确能力取舍，再删去无运行职责的维护链。这三项不能靠增加局部异常捕获或更多孤立单元测试完成。

## 复现说明

本轮复用了 `fabric/build/cpu-validation-work/classpath.txt` 指向的已有类文件，JVM 为本机 JDK 26.0.2，启动参数为 `-ea -Xmx512m`。没有重复运行完整领域测试或正式性能预算。运行复现只针对需要判明的新失败模式；其他已经能从代码直接确定的问题没有另写重复测试。

### 四腔孔流的最小程序

以下程序可放在独立临时目录，使用现有 classpath 编译执行。不要替换仓库内的正式类文件。

```java
package mmm.thermal;
import java.util.List;
import mmm.fluid.air.*;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

public class NetworkProbe {
    public static void main(String[] args) {
        var gas = new GasRegistry(
            List.of(new GasComponentDefinition("test:gas", 1, 1, List.of(), List.of())),
            List.of(new GasRegistry.AmbientFraction("test:gas", ExactRational.ONE)));
        var thermal = new ThermalRegistry(List.of(new ThermalMaterialDefinition(
            "test:thermal/gas", ThermalMaterialDefinition.CarrierKind.GAS,
            "test:gas", 300, 0, 0,
            List.of(new ThermalMaterialDefinition.CaloricSegment(1, 2000, 718)))));
        var network = new AirNetwork(gas);
        for (int i = 0; i < 4; i++) {
            network.addNode(.125,
                .125 * (i == 0 ? 1.1 : .9) * GasRegistry.STANDARD_TEMPERATURE_K / 300);
        }
        for (int i = 1; i < 4; i++) network.addOrMergeEdge(i, i + 1, .0625, .65);
        var audit = new ThermalAuditState(ThermalAuditLedger.initialized(SignedDyadic.ZERO));
        var energy = new GasEnergyStore(new GasThermalModel(thermal, gas), 300, audit, network);
        network.stepEdges(.05, energy.thermodynamics(network),
            batch -> energy.planFlow(batch, network));
    }
}
```

### 热缓存案例的状态参数

临时程序使用 `ThermalDomainState` 的正常构造函数：`sizeX=sizeY=sizeZ=1`、`cellSize=0.5`、初温 300 K。载体上下文含一行液体：格索引 0、归一化质量 500、归一化体积 0.5；无化学、无颗粒。液体比热 4184 J/(kg·K)。气体使用上例的单组分定义，比热 718 J/(kg·K)，节点由 `addNode(.0625, .0625)` 创建，提供一个 `GasSupport(0, 1, 255, .5)`。液体和气体热材料均以 300 K 为零参考能。

上下文的液体内能 setter 正常增加 `liquidInventoryRevision`；只读设备查询没有调用 setter。按第 2 项的顺序调用，即在相变候选准备时发生上述越界。这个案例不依赖修改内部字段，也不依赖违反库存代次协议。

### 碰撞计划覆盖较新动量的最小程序

同样使用上述已有 classpath，单独编译下列程序。全部操作来自公开接口；没有修改内部字段或正式类文件。

```java
import mmm.fluid.*;
import java.util.List;

public class MomentumPlanProbe {
    public static void main(String[] args) {
        var domain = new FluidDomain(4, 4, 4,
            new FluidSolverConfig(.5f, 1000, 0, .05f, 200, 1e-5f, .002f));
        domain.setVolume(1, 1, 1, 1);
        domain.setVolume(2, 1, 1, 1);
        var plan = domain.planCollisionEdits(
            List.of(new FluidDomain.CollisionEdit(3, 3, 3, 255)));
        var original = domain.exportMacMomentum();
        int face = (1 * 4 + 1) * 5 + 2;
        var x = original.copyX();
        x[face] = 10;
        domain.restoreMacMomentum(new MacMomentumState(4, 4, 4,
            x, original.copyY(), original.copyZ()));
        System.out.println("before=" + domain.exportMacMomentum().copyX()[face]
            + ", K=" + domain.liquidKineticEnergyJoules());
        domain.beginCollisionEdits(plan);
        System.out.println("after=" + domain.exportMacMomentum().copyX()[face]
            + ", K=" + domain.liquidKineticEnergyJoules());
    }
}
```

### 等物性气体组分拆分的最小程序

下面直接复用生产接触构造和 `stepThermalOnly`。初始化液体温度时调用库存能量写入接口；输出比较接触热导及液体放热量，未将初始化后的全域审计账用作守恒结论。

```java
package mmm.fluid;
import java.util.*;
import mmm.fluid.air.*;
import mmm.thermal.*;
import mmm.chemistry.ExactRational;
public class GasContactProbe {
 static ThermalMaterialDefinition material(String id,ThermalMaterialDefinition.CarrierKind kind,String carrier) {
  return new ThermalMaterialDefinition(id,kind,carrier,300,0,1,List.of(new ThermalMaterialDefinition.CaloricSegment(1,1000,1000)));
 }
 public static void main(String[] args) {for(int n:new int[]{1,2,8})run(n);}
 static void run(int n) {
  var gasDefs=new ArrayList<GasComponentDefinition>(); var ambient=new ArrayList<GasRegistry.AmbientFraction>();
  var thermalDefs=new ArrayList<ThermalMaterialDefinition>();
  thermalDefs.add(material("test:thermal/water",ThermalMaterialDefinition.CarrierKind.LIQUID,BuiltinFluidMaterials.WATER.id()));
  for(int i=0;i<n;i++) {String id="test:gas_"+i;gasDefs.add(new GasComponentDefinition(id,1,1,List.of(),List.of()));ambient.add(new GasRegistry.AmbientFraction(id,ExactRational.parse("1/"+n)));thermalDefs.add(material("test:thermal/gas_"+i,ThermalMaterialDefinition.CarrierKind.GAS,id));}
  var gas=new GasRegistry(gasDefs,ambient);
  var d=new FluidDomain(2,2,2,new FluidSolverConfig(.5f,1000,0,.05f,100,1e-5f,.002f));d.sealDomainBoundary();d.setVolume(0,0,0,.5f);
  d.bindGasRegistry(gas);d.bootstrapGasState("");d.bindThermal(new ThermalRegistry(thermalDefs),new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)),300);
  var stock=d.liquidCells.physicalStockAt(0);d.liquidCells.planPhysicalEnergyUpdate(0,stock.massKilograms()*1000*100).commit();
  var batch=ThermalCarrierTransport.prepare(d.thermalState.exchangeCarrierAccess(d.domainCapabilities.thermalMarkerContext()));
  var contacts=CarrierThermalConduction.contacts(d,batch);double local=0,neighbor=0;
  for(var c:contacts)if(c.first()==0||c.second()==0){int other=c.first()==0?c.second():c.first();if(other<0)continue;if(batch.cell(other)==0)local+=c.conductance();else neighbor+=c.conductance();}
  double before=d.liquidCells.physicalStockAt(0).internalEnergy();d.stepThermalOnly(.0001);double heat=before-d.liquidCells.physicalStockAt(0).internalEnergy();
  System.out.println("components="+n+", localG="+local+", neighborG="+neighbor+", heat="+heat);
 }
}
```

### 合法反应链中间态超出库存条目限制的最小程序

下面使用生产阶段类，因此置于 `mmm.fluid` 包。仅创建独立临时源文件与输出目录，无需改动正式实现。

```java
package mmm.fluid;
import java.util.*;
import mmm.chemistry.*;
public class ReactionProbe {
 public static void main(String[] args) {
  var species=new ArrayList<ChemicalSpecies>();var names=new ArrayList<String>();var input=new TreeMap<String,Long>();
  for(String id:List.of("a","b","c","d")){int h=id.equals("a")||id.equals("d")?2:1;String name="test:"+id;names.add(name);species.add(new ChemicalSpecies(name,Map.of("H",h),0,ExactRational.parse(h+"/1000"),0));}
  input.put("test:a",1000L);
  for(int i=0;i<15;i++){String name="test:i"+i;names.add(name);species.add(new ChemicalSpecies(name,Map.of("H",1),0,ExactRational.parse("1/1000"),0));input.put(name,1000L);}
  String phase="test:phase";
  var registry=new ChemicalRegistry(species,List.of(new ChemicalPhaseDefinition(phase,BuiltinFluidMaterials.WATER.id(),names,Map.of("test:a",ExactRational.ONE))),List.of(
   new ChemicalReaction("test:split",ChemicalReaction.Kind.COMPLETE_DISSOCIATION,phase,Map.of("test:a",1),Map.of("test:b",1,"test:c",1),ExactRational.ZERO),
   new ChemicalReaction("test:combine",ChemicalReaction.Kind.INSTANTANEOUS,phase,Map.of("test:b",1,"test:c",1),Map.of("test:d",1),ExactRational.ZERO)));
  var domain=new FluidDomain(2,2,2,new FluidSolverConfig(.5f,1000,0,.05f,100,1e-5f,.002f));domain.sealDomainBoundary();domain.setVolume(0,0,0,.5f);
  domain.bindMaterialRegistry(new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)));domain.bindChemistry(registry);domain.setMarkerChemical(0,input);
  var old=new ChemicalReactionEngine(registry).react(registry.phaseHandle(phase),domain.liquidCells.physicalStockAt(0).mixture());
  System.out.println("model engine: entries="+old.mixture().entryCount()+", D="+old.mixture().amount(registry.speciesHandle("test:d")));
  var stage=new ChemicalReactionStage(domain);
  for(int tick=1;tick<=2;tick++){var stats=stage.step();var mix=domain.liquidCells.physicalStockAt(0).mixture();System.out.println("pass="+tick+", stats="+stats+", A="+mix.amount(registry.speciesHandle("test:a"))+", D="+mix.amount(registry.speciesHandle("test:d"))+", dirty="+Arrays.toString(stage.captureDirtyCells()));}
 }
}
```

本轮临时源文件、诊断类副本和编译输出在审阅结束时清理；交付仅为本报告。
