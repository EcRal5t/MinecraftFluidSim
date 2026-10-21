# 核心整合完整自审

审查对象：`4e6bfb9`，生产源码 `3679a2471128686e6a37f4b7ba72d335d3d9b354`。先执行完整测试入口，再审查库存与热账、共同力学、气腔及恢复三条职责链。本轮没有修改生产代码或测试；详细命令、运行范围及数值证据统一保存于[验证指南](../maintenance/validation.md#当前验证证据)。

结论：共同主干确有简化收益，当前仍有影响物理响应和恢复的结构性缺陷，M10 综合验收待完成。前轮基于分段证据宣布完成的结论已经撤回。当前发现的问题需要沿职责链整改，放宽数值容差无法处理其中的模型与状态交接错误。

## 确认的问题

### P1：静水支撑覆盖真实真空腔的压力接触

[PressureFaceGraph.prepareHydrostaticReference](../../solver/src/main/java/mmm/fluid/PressureFaceGraph.java#L371) 在分量接地、上方有液体接触且 `hasFiniteGas=false` 时，将整个分量加入液体压力支撑。零库存真空同样满足该判断，实际干容量没有参与这次提升。[pressureAtMicro](../../solver/src/main/java/mmm/fluid/PressureFaceGraph.java#L218) 随后优先读取液体压力行，真实真空节点失去应有的压力接触和体积散度。

完整 `gas-liquid` 首个失败的半液格真空容量场景正好满足这些条件。真实库存结算仍能限制最终容量，但机械预测中的真空闭合反力已经缺失。应保留真空节点及其 C/CT 接触，把已知静水参考载荷与自由度分类分开；压力观察与颗粒接触一并使用同一规则。该场景的触发断言应保留。

### P1：颗粒已知浮力没有经过同一步隐式阻力

[MechanicalStepPipeline](../../solver/src/main/java/mmm/fluid/MechanicalStepPipeline.java#L79) 先施加全重力，在第 137 行执行阻力，再执行压力求解。[PressureSystem.solveProjection](../../solver/src/main/java/mmm/fluid/PressureSystem.java#L40) 此时才调用 [GranularPressureCoupling.prepare](../../solver/src/main/java/mmm/fluid/GranularPressureCoupling.java#L53)，直接把包括静水浮力的已知载荷加入速度。

在静水、大液体惯性和小单粒极限下，记 `γ=ΔtD/m`、`b=ρ液/ρ粒`，该顺序产生：

`v新=(v旧−gΔt)/(1+γ)+b gΔt`。

其固定点比把净重一起交给阻力的结果多出向上速度 `b gΔt`。正式细砂密度 2600 kg/m³、步长 0.05 s 时，该偏差尺度约 0.189 m/s，足以改变沉降方向。共同残余压力会改变有限惯性响应系数，已知载荷绕过阻力的次序偏差仍然存在。

完整沙床记录到第 519 步仍有活动颗粒，未完成预定生命周期。应统一准备重力与静水浮力，再交给阻力响应；残余压力保持共同耦合。延长沉降超时无法修正此问题。

### P1：容量分组造成受限孔口两侧整腔来源混合

[ConservativeLiquidFlux.prepareGasGroups](../../solver/src/main/java/mmm/fluid/ConservativeLiquidFlux.java#L337) 把旧气体与流入气体来源并入容量组。[inheritGasReservoirs](../../solver/src/main/java/mmm/fluid/ConservativeLiquidFlux.java#L394) 随后根据旧腔容量比例，将整组所有旧节点的来源铺到每个干分量；[GasTopologyCoordinator](../../solver/src/main/java/mmm/fluid/GasTopologyCoordinator.java#L205) 再按这些来源分配完整库存与内能。

代码可达条件为：相邻两个水平半液格，经固定受限开孔连接，两侧原为独立气腔，共享 MAC 有小正速度。干微面液相流量为零而总流量为正，互补气相流量超过解析量后就能把两侧并组。最终交换比例主要取决于旧腔容量，小流量的幅度被消掉。未来拓扑即使仍把两侧登记为有限孔流节点，库存也已经发生整腔混合。

这是源码数据流确认的缺陷，本轮未新增或执行该组合的动态夹具。全局整数库存和 U 仍可精确闭合，因此现有守恒审计不足以发现这种错误交换。

应将容量可行性分组和来源输运分开：容量组约束接收量，来源沿实际通量交接；真正连成同一共压气腔时才整腔混合，受限孔口继续由 `GasOrificeFlow` 结算。

### P2：冷恢复会初始化原本没有初始化的气体

[FluidDomainStateMapper.capture](../../solver/src/main/java/mmm/fluid/FluidDomainStateMapper.java#L22) 可以保存 `gas=null`，恢复第 86 行也保留这个选择。但第 97 行调用 `ensureGrid()`，经 [CellMacStage.rebuildGeometry](../../solver/src/main/java/mmm/fluid/CellMacStage.java#L102) 触发拓扑刷新，为剩余封闭空间创建环境气体并记入 source。

完整 `region-state` 的 gas presence 失败来自这条路径。库存审计可以因登记了 source 而闭合，恢复语义仍然改变了。恢复应只准备所需派生状态，气体初始化留在明确的物理准备时点。保留状态存在性断言。

### 润湿宏观次序仍有回归

当前源码三组接触角扩展方差为 16.98／23.22／29.56 m²，未满足原定的润湿扩展次序。峰值动能有限，单凭该记录尚不能断定持续泵能；次序反向已经足以阻止验收。此次审查没有确认唯一根因，应沿共同曲率、墙面接触法向与压力载荷整链处理。

## 守恒、液封与墙面动量

| 对象 | 本轮结论及适用范围 |
|---|---|
| 质量与物种 | 主输运按来源分配整数质量和物种，目标统一累加；未发现新的丢失或重复源汇。已初始化气体库存审查也未发现直接丢弃路径。错误整腔混合和恢复初始化说明总账闭合还需结合正确来源与操作语义评价 |
| 液体体积与真实容量 | 数量结算以精确库存和实际容量生成后继；共同库存及原 5,000 步溃坝通过。热膨胀、相变及跨相转移按相应模型改变液体体积，守恒要求随具体操作核对 |
| 载体内能与相变热账 | 未发现本轮审查链中的重复计功或漏交接。来源 U 余数、实际存储舍入和机械热分开入账；完整热学通过。舍入账闭合说明存储账本一致，仍需独立观察物理误差量级 |
| 完整机械总能量 | 全域物理黏性、阻力及实体做功尚未统一形成完整热机械闭合，现状已在热学专题和产品路线登记；当前不能宣称所有场景的 `U+K+势能` 严格守恒 |
| 液封 | 共享干面交集、满液分量与固定墙阻断规则一致，当前 `air-cavity` 通过。已有移动液塞保留较厚满液核心；两相切格与受限孔口组合仍受上述来源缺陷影响 |
| 墙面动量交接 | 固定墙及未解析法向模式退休均清零对应 P，登记墙面冲量，按实际面惯性计算 `P²/(2M)` 并写入本地库存 U。正常调用链未见重复退休、重复计热或凭空补热，`cell-inventory` 通过。静水载荷分类缺陷仍会影响墙旁流动响应 |

墙面与库存主要审查入口：

- [CellMacStage.constrainUnresolvedWallMomentum](../../solver/src/main/java/mmm/fluid/CellMacStage.java#L161)、[stopWallMomentum](../../solver/src/main/java/mmm/fluid/CellMacStage.java#L243)：约束、实际能量与反力。
- [CellMomentumTransport](../../solver/src/main/java/mmm/fluid/CellMomentumTransport.java)、[MacMomentumAdvection](../../solver/src/main/java/mmm/fluid/MacMomentumAdvection.java)：实际质量通量及动量混合损失。
- [LiquidCellInventory](../../model/src/main/java/mmm/inventory/LiquidCellInventory.java#L445)：来源数量分配及最终 U、机械热与舍入结算。
- [ThermalCellEnergyAssignment](../../model/src/main/java/mmm/thermal/ThermalCellEnergyAssignment.java)、[GasLiquidPhaseChangeStage](../../solver/src/main/java/mmm/fluid/GasLiquidPhaseChangeStage.java)：实际载体 U 与跨相完整候选。

## 长期误差与流体表现

当前源码原 5,000 步溃坝的五个千步采样动能从约 563 J 降至末态 291 J，中间最低约 203 J；库存、容量和恢复检查通过。完整自由面、旋转、黏性解析对照、热平流与自然对流也通过，支持这些代表场景具有有界响应。

这些结果不能推广为整个耦合系统长期稳定。颗粒浮力次序偏差会持续积累到错误稳态；来源分组会造成过快混合；润湿方向错误尚未解决。玩家末态动能低于峰值，仅凭超出静止体阈值无法认定发散，持续实体输入功也需要纳入评价。当前应逐条修正已确认的离散与交接问题，再观察对应正常长流程。

气腔控制器的 17 个节点也不能直接等同于丢失气体：库存 64 m³std 精确闭合，最大腔约 33.979 m³，残余气泡合计约 0.0206 m³。当前日志缺少小腔位置和连续几何，宏观头部空间是否错误断开仍待确认。

## 测试与排障成本

共同库存、实际质量通量、CSR 压力矩阵、同代气体容量和联合热学候选减少了重复物理规则。纯液体与含固相区域已经使用同一液体主干，这项架构收益可以确认。

“大幅降低维护排障成本”的目标仍未达成，主要原因如下：

- 静水职责散在 `PressureFaceGraph` 的分类与参考压力、`BodyForceStage` 的已知载荷、`GranularPressureCoupling` 的浮力以及 `PressureSystem` 的阻力次序。本次两项力学缺陷跨越这条链；应集中准备自由度、已知载荷及作用对象，让各阶段读取同一结果。
- `DomainFlowFields.ensureGrid` 带有气体初始化和来源登记等业务副作用，恢复等调用者很难从方法名称判断影响。应区分派生准备与状态初始化，并将明确初始化移到对应操作的提交链。
- 气体容量组被复用为库存来源归属，两种规则混在一起。各自的数据和提交职责应直接分开，避免继续增加例外条件。
- `DomainStep` 同时包含步组织、机械观察和床层适配，仍有机械转发；`DomainFlowFields` 同时处理网格准备和较长的曲率实现。适合沿数据职责移动整段实现，克制继续拆细碎助手类。
- 材料测试把内部 PCG 次数写死为 1–32；当前解析静水平衡允许零次。恢复派生压力逐位相等也过强：本次只有 0.0078125 Pa 舍入差，精确库存和 U 比较应保留。极端热学第二组夹具在初始化前已经超过质量编码范围，应缩小体积保留其热学目标。
- 孔流测试的一秒均压和精确终点要求需按一阶隐式离散与库存量子修正。单节点方程按当前参数推导约剩 13.67 Pa，属于尚需明确接受范围的瞬态误差；终端一个 nanos 约对应 0.000101 Pa。应检查单调响应与合理终态误差，保持精确库存收支。
- Gradle `test` 与实际 main 验证入口分离，`gpu-probe` 又缺少对应任务配置。完整组合在失败领域的首个断言后停止，因此一次运行的“领域已开始”不足以代表内部全部场景通过。M10 应记录实际完成范围。
- 模块验证与 CPU 完整组合重复执行了切格等入口；应由一个聚合入口统一编排、去重，保留单独运行能力。本轮未删测试或改任务，完整基线保持原样。

整改以三条完整职责链推进：共同静水与两相压力响应；气体来源、有限孔流与恢复；润湿与验收契约。具体交付状态由[实施计划](../roadmap/solver-consolidation-plan.md#下一轮完整交付)维护。
