# 自由面统一算法探索：成果与对照索引

本页整理截至 2026-10-10 的探索资产，供现行双路径自由面算法修复时查阅。生产实现与稳定契约见[流体求解器](../architecture/fluid-solver.md)，后续顺序见[产品路线](../roadmap/product-roadmap.md)。

撤回提交为 `948857d`。没有候选完成液滴、薄层、液池、连接、分离、跨格运动及全部库存和生命周期验收。实验支持对当前工程路线的取舍；统一算法的普遍可行性仍需其他证据。失败过的完整求解器保留为反例，全面基准资格尚未取得。

## 保存位置

| 资产 | 位置与用途 |
|---|---|
| 修复前对照 | `b58984e`；分支 `codex/free-surface-before-unification` |
| 撤回前生产快照 | `717e9b0`；分支 `codex/free-surface-unification-rejected-20261010`，保留 161 次提交的实现、测试与失败模式 |
| 较早独立实验 | 主目录 `build/free-surface-audit/`；索引为 `current-method-audit.md`，包含多面体交集、正库存转移与早期机械实验 |
| 后期独立实验 | `C:/Users/Ecisral/.codex/worktrees/free-surface-feasibility/fabric-example-mod/build/free-surface-audit/`；包含 Hermite、GN 支撑、客观有限变形功与最后的诊断驱动 |
| 最终结论 | 主目录 `build/free-surface-audit/final-verdict-20261010.md`；`continued-research-checkpoint.md` 保存后期证据，结论以其最后一节为准 |
| 较早实验归档 | 主目录 `build/free-surface-audit-20261010-evidence.zip` |
| 后期最终归档 | 主目录 `build/free-surface-final-20261010.zip` 及同名 `.sha256`；含独立源码、数值基表、类文件、驱动、日志和失败快照 |
| 原生 VOF 对照 | 主目录 `build/free-surface-audit/reference-vof/`，其中 `reference-native-repro.tar.gz` 保存原生复现资产 |

`build/` 文件受 Git 忽略，迁移机器时须保留归档。较早实验与后期实验分处两个目录；主目录的报告不表示所有后期源码已在主目录。读取旧快照时使用 `git show 717e9b0:<仓库内路径>`，按现行架构重新实现所需改动。旧生产框架保持归档，不整体合并、复制或 cherry-pick。

## 主要路线族及复用判定

| 路线族与检索入口 | 已证实的成果 | 限制与用途 |
|---|---|---|
| 精确持久切割、连续重建；快照中的 `LiquidMomentReconstruction`、`ResolvedLiquidMechanicalStage`、`LiquidInventoryRepartitionPlan` | 局部精确交集、广延库存分配与共同受力的若干局部契约 | 持久切割历史与几何等库存强约束导致片段、控制体和有理分母增长。原 2×2×2 薄层第二完整重建步约 34.48 s，第三步耗尽预算。保留少量精确几何核验及测试场景；整套框架维持归档。 |
| 固定背景 P1、PLIC 与有界凸形；`FixedBackgroundProbe`、`NumericRemapProbe`、`CurrentVofGeometryProbe` | 共用压力、对称应变、表面功与输运；补偿乘加改善近水平交集消去误差 | 单 PLIC 平面不能表达闭合格内液滴、两侧薄层或任意体积加三项第一矩。局部凸化和体积恢复产生位置误差、裂缝或假表面。保留算术谓词、规定几何积分与反例。 |
| 固定 P1 隐式界面与 BFECC；`SharedLevelSetGeometryProbe`、`SharedLevelSetMechanicalProbe` | 表示规模固定，部分早期连续场景能运行 | 长期逆映射失败；重建表面能增长使实际总能量增加。整体重建和机械路线保持归档。 |
| 边交点与子格 Marching Tetrahedra；`EdgeIntervalGeometryProbe` | 临时表示有界，可生成共形界面 | 管道、封闭岛和反复内接重建仍有缺失；平移实验几何体积损失约 8.49%。保存空间表示反例。 |
| 随动 P1 网格、有限步体积与面积功；`RetainedGeometryMechanicalProbe`、`SweptPressureNewtonProbe` | 固定正材料质量消除重新分配惯性造成的质心漂移；仿射单元的有限体积梯度和实际面积功可闭合 | 有效的早期参数和固定拓扑场景不能覆盖真实水薄层的网格退化；移动地址、重网格、墙面和拓扑维护未完成。有限步功可作算子对照。 |
| 当前有向表面积分与正交集；`SignedBoundaryRemapProbe`、`CurrentBoundaryClipProbe`、`AffineInventoryRemapProbe` | 非星形、孔洞、桥接及分离几何的规定平移保持体积/第一矩；正交集搬运 8 个广延列，固定 2^-40 单位库存精确守恒 | 有向链用于几何积分；正库存使用正交集。仿射密度长期重建可能负值，实际全能力库存与动态拓扑未接入。适合一步覆盖率、矩、正份额和容量对照。固定库存单位不能直接替换现行原始库存。 |
| 重网格与正求积；`RegriddedMechanicalRemapProbe`、`PositiveMomentClosureProbe`、`MomentQuadratureLoopProbe` | 正质量、质心支撑和共同重分配比重新按几何体积分配惯性可靠 | 控制体和表面规模增长、负密度或支撑不可行仍会失败。保留正权可行性原则，现行 merge 模块按其已有契约复用。 |
| 共享面 VOF/CSF 与切割 MAC；`BoundedFluxMechanicalProbe`、`StaggeredPhaseMechanicsProbe` | 库存通量、散度和完整应变的独立诊断 | 薄层位置/界面误差与细化成本仍不合格；局部散度通过不等于完整形状和力学通过。作为离散差异与成本反例。 |
| C1 三次 Hermite 界面；`CubicHermiteInterface`、`HermiteGeometryAudit`、`HermiteCutMacroMoments`、`HermiteTranslationWorkAudit` | 16³ 为 39,304 个标量，静态/平移保持与多级求积可测；无持久内部切割历史 | 反复投影产生假液体，旋转薄层解析误差和细化成本仍高。形状积分误差、几何输运误差和物理功须分别测量。作为几何对照。 |
| 固定前沿和全局 P2 力学；`SmoothCharacteristicWork`、`QuadraticMaterialLoop` | 部分自由液滴连续场景保持有限功、库存和质心；界面细化可观测 | 全局速度空间不能表示多个独立刚体运动；固定前沿没有实际连接分离。薄层重建误差持续累积。适合局部有限功对照。 |
| GN 共形力学与正质量支撑；`MassSupportedMacroProjection`、`MassSupportedRigidAudit`、`PhysicalSupportEnrichmentAudit` | 先保留仿射模式，再剔除无质量支撑高阶列；两液滴独立物理支撑将刚体动能损失从 8.33909% 降至约 4.43e-14 | 42 列粗空间与静态分体 fixture 尚无动态支撑图；局部细化的自由度/成本须另计。作为刚体、质量投影与黏性算子对照。 |
| 客观有限变形功；`ObjectiveMaterialWork`、`MacroMaterialWork` | 面积功、体积功、对称中点应力和黏性功的局部恒等式约至 1e-16，有限刚体旋转无虚假黏性热 | GN 试验空间缺少完整中点旋转模式，全局角动量尚未同时通过。局部本构恒等式可复用，完整有限变形机械阶段维持离线。 |
| 仿射参考坐标和保留求积点；`AffineGeometryResolutionAudit`、`AffineMacroMaterialAudit`、`RetainedMacroQuadratureAudit` | 局部解析改善、C3 延拓与源组装优化均有独立比较；局部密度误差随时间步减半近一阶收敛 | 假相、动态拓扑和局部不可压缩误差未同时受控。四子步完整 0.05 s 机械内核约 715/333/308 ms，触发连续成本停止；还未包含产品完整步。保存失败快照与时间收敛证据。 |

### 外部算法资产

| 参考 | 可查位置及有效范围 |
|---|---|
| Basilisk | `reference-vof/common-vof-reference.c` 已包含 `navier-stokes/conserving.h`。已有液滴、薄层、池、连接和分离测试；薄层 AMR 完整成本超限，冲量和实际分离结果仍有缺陷。可作方法行为比较，不能作为现行全部不变量的权威数据。 |
| Algoim | 后期 `HermiteNativeSurfaceMoments` 及原生积分资产，用同一 Hermite 多项式作高成本界面积分比较。必须报告求积阶数差异；原生根参考未与 GN 分片梯度跳跃共形，分片应力资格尚未取得。 |
| SDTF | 后期只读参考与报告中保存上游位置、许可和评审。拓扑维护有参考价值，插值/平滑没有现行精确库存和有限面积功契约；完整框架保持外部参考。 |

## 现行双路径修复可接入的成果

1. **分别核对库存与空间误差。** 保留现行 `SignedDyadic`、`ExactVolumeAllocation`、`CellRemapMassAllocation`、`CellSpeciesInventory`、`CellRemapHeatBalance`。几何使用可观测误差的近似积分；库存按原始载体精确结算。全过程任意精度几何的成本已有反例。
2. **使用共同转移计划。** 体积、逐材料质量、species、U 和动量沿同一来源/目标关系搬运，容量与附属列一起预检。现行 `CellCarrierRemapPlan` 和 `CellRemapInventoryAudit` 已是复用入口；交接审计还应观察空间矩及可解析速度损失。
3. **保留正权和位置支撑。** 复用现行 `MomentPreservingMergePlan` 与 `QuadratureMomentFit` 的质心、协方差和正权契约。物理支撑不足的刚体、仿射模式必须明确测量；总动量为零可能掩盖两个物体之间的错误交换。
4. **单列几何重建与机械功。** 现行 `MechanicalStepAudit` 分开记录 G2P、库存重建、碰撞和黏性变化。规定匀速运动的实际表面功为零；重建面积变化属于几何诊断，额外补偿内力会改变冲量。

库存与第一矩无法唯一确定界面。同体积、质量和质心的薄椭球与等体积球，表面积可相差约 4.369 倍。位置矩改善有助交接，表面力与最小解析尺度仍需各自验证。

## 下一项可靠对照

优先在零力匀速及规定仿射映射下测量两个输运分支交接，检查原始库存、体积/质量第一矩、协方差、线动量和动能。质量第一矩满足 `M1_next = M1_before + dt * momentum`；位置和速度写入误差按实际存储精度计算。三轴格内平移、跨格、接入/离开液池支撑、两液滴相反运动使用同一观测。真实连接与分离须另有连续运动测试。

| 对照对象 | 现有入口 | 基准适用范围 |
|---|---|---|
| 库存份额、微小尾数、容量 | `fabric/src/test/java/cc/ecisr/mmm/fluid/LiquidVolumeTransportValidation.java` | 已有整数分配 oracle、微小正份额与解析旋流多级收敛；适合加强现有测试 |
| 覆盖率、空间矩、压力 footprint | `fabric/src/test/java/cc/ecisr/mmm/fluid/MarkerDistributionExperiment.java` | 逆解析映射独立积分，两级参考差异用于分离基准误差；实际交接尚需增加观测 |
| 一步复杂几何交集 | 较早 `NumericBoxCut`、`CurrentBoundaryClipProbe`；争议交集再核对快照 `LiquidExactPolygon` | 已知输入的交集/体积/矩；精确几何与原始库存分别核对，复杂自交表面超出已验证范围 |
| 黏性解析解与刚体 | `solver/src/test/java/cc/ecisr/mmm/fluid/LiquidViscosityValidation.java`，由 `FlipCutCellValidation` 调用 | 保留已有完整对称应变、旋转和壁面约束对照；独立液滴模式再用后期支撑诊断 |
| 客观有限功与正支撑 | 后期 `ObjectiveMaterialWork`、`MassSupportedRigidAudit`、`PhysicalSupportEnrichmentAudit` | 算子恒等式和静态支撑比较，动态拓扑与产品生命周期须独立验收 |

### 可执行入口

在主仓库根目录运行已有解析覆盖率对照，选择具体实验以控制成本：

```powershell
./gradlew.bat :fabric:runMarkerDistributionExperiment '--args=free-analytic' --offline --no-daemon
```

后期实验独立于 Gradle。在研究 worktree 根目录调用最终驱动，或先把最终归档解压到独立目录；归档中的源码与 `SignedDyadic.java` 按原相对目录保存：

```powershell
& ./build/free-surface-audit/reproduce-final-audits.ps1 `
  -Java 'D:/ProgramFiles/BienCreng/jdk-26.0.2/bin/java.exe' `
  -Javac 'D:/ProgramFiles/BienCreng/jdk-26.0.2/bin/javac.exe' -Compile
```

驱动编译独立诊断，运行客观功、物理支撑和两级时间细化。它允许记录已知成本/密度停止条件，返回成功表示复现流程完成；各候选的验收结果在日志内。

编译后只运行下一项需要的算子：

```powershell
java -cp build/free-surface-audit/final-repro-classes cc.ecisr.mmm.fluid.ObjectiveMaterialWork
java -cp build/free-surface-audit/final-repro-classes cc.ecisr.mmm.fluid.PhysicalSupportEnrichmentAudit build/free-surface-audit/stokes-macro-basis.txt
java '-Dprobe.massSupported=true' '-Dprobe.directMaterialMass=true' '-Dprobe.sigma=0' -cp build/free-surface-audit/final-repro-classes cc.ecisr.mmm.fluid.MassSupportedRigidAudit build/free-surface-audit/stokes-macro-basis.txt
```

以上 `java` 要求 Java 25 或更新版本。成本比较保留同机、同输入、同物性、同物理时长；机械内核、几何更新与完整生产步分别报告。重跑直到偶然通过会破坏成本证据，应保留每次原始结果。
