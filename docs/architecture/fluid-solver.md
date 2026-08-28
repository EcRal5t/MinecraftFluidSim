# CPU 流体求解器契约

`FlipFluidDomain` 是唯一活动物理核心，CPU 结果是规范真值。Minecraft 编排、世界方块、网络、持久化和 GPU probe 不进入数值核心。

## 状态分层

- **物理真值**：marker 体积、材料质量、化学 parcel、固体 SVQ/质量/组成、气体库存和各载体内能。
- **数值离散**：MAC 速度/压力、守恒 marker footprint 压力相、平滑 volume、PLIC、cut-cell、曲率和目标散度。
- **渲染重建**：客户端 density、界面、颜色、颗粒 sample 和温度量化。
- **调度状态**：ACTIVE、REDUCED、休眠、PAUSED、UNLOADED。

后三层不能创建、删除、缩放或修复第一层库存。

### 后端候选边界

`FluidDomainState` 捕获恰好一种 liquid carrier 布局（pure/material/chemical）及其 registry、颗粒、气体、热学、相变 pending work、求解配置和硬预算。pressure、平滑 density、mesh、调度与 Minecraft 对象不在其中。`FluidStepRequest` 另外携带有硬上限的 moving-body 输入；后端先在私有 state 上提交这些 marker 动量，再执行同一 logical step。`FluidSolverBackend.evaluate(FluidStepRequest)` 必须返回 base generation 的紧邻完整后继，并随 candidate 回报该批输入实际写入液体的合计体积和冲量；调用者在 tick boundary 验证后才可发布。当前 `CpuFluidSolverBackend` 是规范实现，GPU probe 尚不满足这份完整状态契约，不能作为生产后端注册。

`FluidStepRequest.mechanicalAuditEnabled` 是只读诊断选择器。启用时，CPU candidate 的 `StepStats` 记录 P2G、颗粒/多孔耦合、重力、热浮力、表面张力、固定边界、pressure projection、G2P、碰撞、载体库存转移和 reseed 各阶段的动能变化，并同时报告初末重力势能、热学库存机械功以及逐阶段 telescoping closure。该审计不进入 `FluidDomainState`、HEAD 或任何库存，也不改变正常未启用请求的物理结果。

## 网格与 marker

- 默认 cell size 为 0.5 m；每个 Minecraft block 对应 2×2×2 cells。
- `particleVolume` 的双精度和是液体体积真值；逐格 exact marker sum 只由该真值正向汇总。
- `volume[]` 是 PIC 平滑、展示和既有表面几何的派生场，不参与库存或 pressure phase 结算；pressure 使用独立的守恒 marker footprint。
- P2G/G2P 使用共享的 valid face；MAC 空气外推从开放压力面播种，G2P 不采未定义空气零值。
- reseed split/merge 保持体积、质量、动量和质心，并受 marker 硬预算限制；不跨 material 或 phase。

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
2. P2G，施加重力、热浮力、气压、颗粒和实体的派生力。
3. 构造目标散度并求解 pressure projection。
4. G2P、FLIP/PIC 混合、marker advection 与 CCD。
5. 热传导、反应、相变和颗粒状态转移。
6. reseed、局部唤醒、账本和诊断。

相变产生的 pending volume/work 由下一次真实 projection 按该次绝对压力 exactly-once 消费，因此 pressure step 是唯一机械功提交点。相变提交已经改变 marker 几何，pending volume 不再重复写入目标散度。

自由面平衡夹具必须区分两种初态：液体几何先建立、随后按剩余空间初始化环境气体，表示无外部做功的半满平衡态；气体先按空腔初始化、随后向密闭域注液，则是压缩气体的 external-source 操作，必须保留相应气体内能和压力功，不能拿前一种夹具的环境态覆盖或归零。

## 自由面与压力

- 开放液面压力使用环境或 gas-node pressure；密闭气体只通过同一界面边界进入 projection。
- marker 分布 drift correction 只生成目标散度，不改 marker 体积，不强制找平。
- 表面张力使用 PLIC/相分数构造曲率和 balanced-force 压力项；`sigma = 0` 不产生额外力。
- 液液相共享机械 pressure/velocity，但保持材料身份、界面张力和密度分层。

pressure 位于 cell center，速度位于 face。离散目标是：

```text
div((1 / rho) grad(p)) = (div(u*) - target_divergence) / dt
```

- 每个 marker 按由标准 `2×2×2` 四分之一格点诱导的 C1 紧支撑核，把自身真实体积守恒分配到开放邻格；该 footprint 决定 pressure fraction/component，不改 marker 库存。若本格 exact marker fraction 已包含 cell center，fluid-air ghost distance 使用 exact marker PLIC；只有 footprint 支撑出的 pressure cell 才使用重建 PLIC，并消费相邻 pressure-air fringe。这样 marker 在 containing cell 内的微小位移不会反向改变一个原本静止的 exact 平面水头。
- 含 cell center（fraction `>= 0.5`）的 bulk component 只把中心内格作为 pressure unknown，低 fraction fringe 保持自由面；完全低于中心但接触固定边界的连通薄层整体进入 pressure，远离边界的孤立小滴保持 ballistic carrier。
- 重力、热浮力和速度载体 face 仍由实际 marker occupancy 激活，不能因 pressure footprint 变宽而给孤立液滴稀释重力。
- cut-face aperture、界面距离和 `1/rho` 面系数同时进入矩阵、预条件器和最终投影，避免三个路径使用不同几何。
- 变密度 face coefficient 按两侧界面距离守恒加权；同密度材料必须退化为纯水矩阵。
- PCG 只对活动流体未知量求解；`pressureTolerance` 是绝对 RMS 代数残差，不随 RHS/静水载荷放大。迭代预算耗尽、breakdown 或非有限残差会拒绝完整候选并把生产区域标记 `NUMERICAL_PAUSE`，不会以未收敛压力继续 advection。
- 环境表压为 gauge zero。gas topology 的 runtime lineage 还会沿连通 pressure-air fringe 扩展到实际 ghost face，但不会穿过 pressure-fluid bulk。只有密闭气体边界时，求解器先减去一个统一 gas gauge reference，PCG 只承受相对压力；对外压力观察、相平衡与 pending `-p ΔV` 再加回该 reference。多个 gas lineage 之间的压差不被消除。
- drift correction 和 moving-solid occupancy 只通过 `target_divergence` 进入 projection，不直接缩放 marker；相变 pending 只结算已经发生的 marker 几何变化对应的压力功，不能再次制造一份散度源。

### PLIC、表面张力与润湿

自由面由 cell liquid fraction 与局部法向构造 PLIC。ghost-fluid 距离决定 fluid-air pressure face；液液界面则从逐相体积分数字段构造 pairwise 界面。

液气压力跳跃满足 `delta p = sigma * kappa`。液液张力使用 phase 无序对定义，以 balanced-force CSF 接入同一 pressure 路径，减少压力梯度与曲率力离散不一致造成的寄生流。近壁法向只由 Young 接触角旋转；没有“浅水吸墙”、墙角偏置或按材料 ID 写死润湿。

当前不可互溶相共享一个机械速度场：系统承诺静水压力、密度分层和界面张力，不声称解析界面切向滑移、乳化或湍流液滴破碎。

## 固体与碰撞

- Fabric 侧 `MinecraftCollisionWorldPort` 将 Minecraft `VoxelShape` 保守采样成不可变 `CollisionField`；core 再把 micro mask 转成 cut-cell face aperture。pressure、预条件器、投影和 marker CCD 使用同一几何。
- CCD 只截断命中法向，切向保持自由。
- 世界碰撞变化挤出的液体必须从 marker 账本得到实际量；不能用格密度推断 escaped volume。
- granular、deposit 和 coherent solid occupancy 与 liquid 共享容量和压力排水路径。

`FlipCutCellGeometry` 从每格 `2×2×2` unsigned micro mask 得到 cell solid volume 和三轴 face aperture。checkpoint 与后端 handoff 保存 raw mask，不能退化为“整格固体/整格空气”；恢复后必须得到相同 aperture。`FlipParticleCollision` 对 marker 轨迹做连续碰撞检测，避免只在步末投影位置造成穿薄墙；碰撞只去除命中法向速度，切向分量保持。

实体交互由 Fabric 每个 Minecraft tick 冻结有界 AABB 湿样本、实体速度和流体观察。实体侧响应仍按游戏 tick 应用；moving-body 输入只能在 region 可运动且 pacing 到期的真实 logical step 消费一次，PAUSED、thermal-only 和被 pacing 跳过的 tick 不得提前或重复改写 marker。core 使用紧支撑二次 B-spline 核，在湿体积上把实体运动约束写入附近 marker 速度；返回值由实际 float velocity 写入重新计算 `kg·m/s`，体积、质量、组成和 `U` 不变。该 marker 动量会经过正常 P2G、pressure projection、G2P 和 advection，因此走动、划水和入水速度可以扰动液体，而不是只唤醒调度。

当前 AABB 湿体积是有界 kinematic immersed-boundary 近似：Minecraft 实体运动被视为外部 moving body，不声称已有刚体质量真值或严格等量的实体侧反冲 ledger；实体占位也尚未进入 cut-cell 容量。推进到压力面积分和严格双向闭合前，必须验证静水浮力的采样误差、给出黏性/形状阻力有效域、把实体表面速度接入 pressure boundary，并让实体占位通过统一容量路径排开液体。不得用额外速度钳制或材料特判掩盖这些停止门。

## Reseed 与传输不变量

- split 把一个 marker 的体积、质量、动量和所有 side truth 按同一权重分给两个子 marker；merge 只在 material、phase 和化学可合并性一致时进行。
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
