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

当前 `production-live-budget` 同时约束两种生产路径：默认 `8×8×8` controller 注入 16 BV 后的 20 个 live logical step 每步必须小于 40 ms；空场景最大装量 `/mmm demo` 的 `24×16×24`、2,592 个液体 cell、20,736 初始 marker、完整 chemistry/granular/gas/thermal/phase 布局连续执行 20 步，不得形成三步连续超过 200 ms。JVM 预热耗时独立报告。exact dyadic 分桶归约、CPU prepared-candidate ownership transfer，以及每次 projection 固定 pressure unknown/face matrix 后只遍历活动 unknown，都只移除重复归约、状态往返或物理系数重建；逐步 CFL substep、PCG iteration/residual 和相变事件保持原轨迹，账本不作近似。额外 warmup、pacing、关闭能力或提高阈值不能替代该门。

## 并发边界

Minecraft 世界读取、方块修改和实体快照留在服务器主线程：

1. 主线程以 `FluidDomainState` 冻结本步完整权威输入，并用 `FluidStepRequest` 绑定 base generation；
2. CPU worker 或 GPU 只在私有状态上计算一个语义完整的 `FluidStepCandidate`；
3. tick 边界在 backend/generation/layout 重验后原子提交完整结果；同进程 CPU 可 single-use 转移已求值 aggregate，其他 backend 可交付 eager state；
4. 超时保留上一完整状态，不展示半步；
5. 世界和物品修改仍由主线程事务执行。

## 生产 GPU 评审门

- CPU 始终是规范、恢复和回退后端。
- 粒子、网格和临时向量常驻设备内存；常规步不能全量回读 JVM heap。
- 快照在设备侧压缩，只回读表面、相、温度和有界诊断。
- 完整 host state 只在 checkpoint、卸载、后端切换、重播或诊断时按 generation 物化/分片回读；常规 CPU/GPU 帧不得为了形式上的 candidate 对称性强制一次全量往返。
- 设备丢失时暂停区域，从最近 CPU 可读 checkpoint 恢复。
- GPU 不要求逐 bit 等于 CPU，但必须满足相同库存账本和数值误差上限。
- GPU 实现必须覆盖完整 `FluidDomainState`；当前只读纯水 probe 不实现 `FluidSolverBackend`，也不能作为生产后端选择。

评审必须回答 CPU/GPU break-even、pressure 质量、常规/完整 readback 成本、`64³` 容量、20 Hz 可行性和 Minecraft 同时渲染稳定性。只读 probe 的能力结果不能替代生产 profile。

当前 headless capability 已在独立 kernel 中证明 Vulkan、Shaderc、VMA 与 compute dispatch 可用，checkpoint 也能把 GPU binary solid cell 严格提升为 CPU cut-cell full mask。CPU/GPU 共同能力子集已统一守恒 P2G/G2P、`H(div)` 平流、碰撞与 raw-float 可表示 reseed 规则；1000 步长跑以逐 marker 精确二进制有理数账本闭合，GPU reduction 只作带解析舍入界的在线诊断。probe 仍不满足生产后端评审门，因为它只有单一常密度 carrier，尚未实现完整 `FluidDomainState` 的材料/变密度、表面张力、气体、热学、化学、颗粒和 phase candidate，也没有生产 checkpoint/readback/fallback 接线。不得用 CPU fallback、放宽门限、减少参考步数、速度钳制、渲染增厚或材料特例把能力缺口改写成通过。

## 停止与降级顺序

未满足预算时依次缩小活动区、降低更新率、采用稀疏宏观节点/沉积状态，最后才降低空间分辨率。不能先牺牲守恒，也不能用客户端 mesh 隐藏数值失败。
