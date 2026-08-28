# 性能、调度与计算后端

## 当前规模

默认 0.5 m cell 下，`24×16×24` 网格为 9,216 cells；`32³` Minecraft 方块对应 `64³ = 262,144` cells。区域 cell、marker、parcel、deposit、网络 payload 和设备内存必须分别有 per-region 与 server-wide 硬预算。

## 调度不变量

- `ACTIVE`、`REDUCED`、热/机械休眠和 `UNLOADED` 只改变执行频率或内存驻留，不改变物理库存。
- 当前 CPU wall-time 策略在单步超过 40 ms 后采用 1–4 tick pacing，连续 3 步超过 200 ms 自动暂停。
- 方块更新、实体接触、容器、反应、温度梯度、气压或边界变化精确唤醒受影响区域；客户端观察不唤醒。
- 不得只跳过 advection 却继续从过期 marker 位置 P2G。
- 扩大默认范围前，代表性静态测试至少 80% 体积可进入守恒休眠。

## 近期 profile

在调整阈值或选择生产后端前，量化：

1. P2G/pressure/G2P/advection；
2. thermal、reaction、phase 和 granular；
3. Minecraft collision/entity/world interaction；
4. snapshot 编码、网络发送和 readback；
5. checkpoint capture、压缩、回读和恢复。

报告记录区域尺寸、活动 marker/carrier、substep、PCG iteration/residual、GC、wall time 和设备 timestamp。设备 timestamp 只做性能观测，调度仍以 wall time 为真值。

当前默认 `8×8×8` controller 以独立 `production-live-budget` 门约束注入 16 BV 后的 20 个 live logical step；JVM 预热耗时独立报告，门内任何一步都必须小于 40 ms。P2G face splat 不产生 marker-local callback 对象，logical step 末已同步的 clean grid 在下一步首个 substep 复用；这些只是等价地移除分配与重复 rebuild，不改变 marker、CFL substep、PCG 或 residual。额外 warmup 和 pacing 不能替代该门。

## 并发边界

Minecraft 世界读取、方块修改和实体快照留在服务器主线程：

1. 主线程以 `FluidDomainState` 冻结本步完整权威输入，并用 `FluidStepRequest` 绑定 base generation；
2. CPU worker 或 GPU 只在私有状态上计算一个 `FluidStepCandidate`；
3. tick 边界原子提交完整结果；
4. 超时保留上一完整状态，不展示半步；
5. 世界和物品修改仍由主线程事务执行。

## 生产 GPU 评审门

- CPU 始终是规范、恢复和回退后端。
- 粒子、网格和临时向量常驻设备内存；常规步不能全量回读 JVM heap。
- 快照在设备侧压缩，只回读表面、相、温度和有界诊断。
- 完整状态只在 checkpoint、卸载、后端切换或诊断时按 generation 分片回读。
- 设备丢失时暂停区域，从最近 CPU 可读 checkpoint 恢复。
- GPU 不要求逐 bit 等于 CPU，但必须满足相同库存账本和数值误差上限。
- GPU 实现必须覆盖完整 `FluidDomainState`；当前只读纯水 probe 不实现 `FluidSolverBackend`，也不能作为生产后端选择。

评审必须回答 CPU/GPU break-even、pressure 质量、常规/完整 readback 成本、`64³` 容量、20 Hz 可行性和 Minecraft 同时渲染稳定性。只读 probe 的能力结果不能替代生产 profile。

## 停止与降级顺序

未满足预算时依次缩小活动区、降低更新率、采用稀疏宏观节点/沉积状态，最后才降低空间分辨率。不能先牺牲守恒，也不能用客户端 mesh 隐藏数值失败。
