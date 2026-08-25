# CPU 流体求解器契约

`FlipFluidDomain` 是唯一活动物理核心，CPU 结果是规范真值。Minecraft 编排、世界方块、网络、持久化和 GPU probe 不进入数值核心。

## 状态分层

- **物理真值**：marker 体积、材料质量、化学 parcel、固体 SVQ/质量/组成、气体库存和各载体内能。
- **数值离散**：MAC 速度/压力、marker count 压力相、平滑 volume、PLIC、cut-cell、曲率和目标散度。
- **渲染重建**：客户端 density、界面、颜色、颗粒 sample 和温度量化。
- **调度状态**：ACTIVE、REDUCED、休眠、PAUSED、UNLOADED。

后三层不能创建、删除、缩放或修复第一层库存。

## 网格与 marker

- 默认 cell size 为 0.5 m；每个 Minecraft block 对应 2×2×2 cells。
- `particleVolume` 的双精度和是液体体积真值。`markerCount > 0` 决定 pressure fluid phase。
- `volume[]` 是为自由面、PLIC 和渲染构造的平滑场，不参与库存结算。
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

相变产生的 pending volume/work 由下一次 projection 消费，因此 pressure step 是唯一机械提交点。

## 自由面与压力

- 开放液面压力使用环境或 gas-node pressure；密闭气体只通过同一界面边界进入 projection。
- marker 分布 drift correction 只生成目标散度，不改 marker 体积，不强制找平。
- 表面张力使用 PLIC/相分数构造曲率和 balanced-force 压力项；`sigma = 0` 不产生额外力。
- 液液相共享机械 pressure/velocity，但保持材料身份、界面张力和密度分层。

pressure 位于 cell center，速度位于 face。离散目标是：

```text
div((1 / rho) grad(p)) = (div(u*) - target_divergence) / dt
```

- 流体格由 `markerCount > 0` 决定；平滑 `volume` 只提供界面几何。
- cut-face aperture、界面距离和 `1/rho` 面系数同时进入矩阵、预条件器和最终投影，避免三个路径使用不同几何。
- 变密度 face coefficient 按两侧界面距离守恒加权；同密度材料必须退化为纯水矩阵。
- PCG 只对活动流体未知量求解；环境表压为 gauge zero，密闭 gas node 给自由面提供非零边界压力。
- drift correction、相变 pending volume 和 moving-solid occupancy 只通过 `target_divergence` 进入 projection，不直接缩放 marker。

### PLIC、表面张力与润湿

自由面由 cell liquid fraction 与局部法向构造 PLIC。ghost-fluid 距离决定 fluid-air pressure face；液液界面则从逐相体积分数字段构造 pairwise 界面。

液气压力跳跃满足 `delta p = sigma * kappa`。液液张力使用 phase 无序对定义，以 balanced-force CSF 接入同一 pressure 路径，减少压力梯度与曲率力离散不一致造成的寄生流。近壁法向只由 Young 接触角旋转；没有“浅水吸墙”、墙角偏置或按材料 ID 写死润湿。

当前不可互溶相共享一个机械速度场：系统承诺静水压力、密度分层和界面张力，不声称解析界面切向滑移、乳化或湍流液滴破碎。

## 固体与碰撞

- Minecraft `VoxelShape` 被保守采样成 cut-cell face aperture；pressure、预条件器、投影和 marker CCD 使用同一几何。
- CCD 只截断命中法向，切向保持自由。
- 世界碰撞变化挤出的液体必须从 marker 账本得到实际量；不能用格密度推断 escaped volume。
- granular、deposit 和 coherent solid occupancy 与 liquid 共享容量和压力排水路径。

`FlipCutCellGeometry` 从保守 `VoxelShape` sample 得到 cell solid volume 和三轴 face aperture。`FlipParticleCollision` 对 marker 轨迹做连续碰撞检测，避免只在步末投影位置造成穿薄墙；碰撞只去除命中法向速度，切向分量保持。

实体交互目前是服务器权威的单向模型：按采样浸没比例给实体施加浮力/阻力，但不把等量反作用动量写回流体，也不把实体作为移动 cut-cell 进入 projection。未来若实现双向耦合，必须同时处理压力面积分、移动固体边界、反作用量和排开体积，不能在当前单向力上叠加经验水流推力。

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
