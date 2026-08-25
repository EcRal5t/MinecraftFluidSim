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

## 固体与碰撞

- Minecraft `VoxelShape` 被保守采样成 cut-cell face aperture；pressure、预条件器、投影和 marker CCD 使用同一几何。
- CCD 只截断命中法向，切向保持自由。
- 世界碰撞变化挤出的液体必须从 marker 账本得到实际量；不能用格密度推断 escaped volume。
- granular、deposit 和 coherent solid occupancy 与 liquid 共享容量和压力排水路径。

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
