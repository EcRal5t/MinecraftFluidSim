# 渲染、性能与生产 GPU 实施计划

## 目标

把当前客户端重建和 CPU 规范求解从“可验证原型”推进到稳定游玩的实现，并在量化 profile 后交付生产 GPU 数值后端。渲染、网络、调度和 backend 都只能消费已提交状态；任何优化都不能改变库存、事务或失败语义。

## 渲染质量

### 问题分层

看到洞面、黑三角、薄水消失或液液界面闪烁时，先分类：

1. 服务端 marker/体积/相身份错误；
2. snapshot 量化、裁剪、generation/sequence 错误；
3. mesher 拓扑、共享界面归属或 winding 错误；
4. GPU vertex/index buffer 生命周期错误；
5. 法线/材质/深度混合的纯展示问题。

不允许用增加 density、降低 iso threshold、强制封洞或双面渲染掩盖前两类错误。

### 最小 fixture

建立可自动导出 snapshot/mesh 的确定 fixture：

- 开放自由面单池；
- 密闭顶部自由面；
- 稀薄单 cell/单 marker 尾量；
- cut-cell 斜面和墙角；
- 两种不可互溶液体的单共享界面；
- region remove、generation 替换和迟到 packet；
- chemical/granular/thermal extension 开关组合。

每个 fixture 检查：索引范围、零面积三角、非流形边、boundary loop、winding、一对 phase 共享面只生成一次、顶点/法线有限、mesh bounds 不越 region。黑三角的开放/密闭 fixture 必须分别存在，不能只靠截图。

### 实现切片

1. 给 `FluidMaterialSurfaceMesher` 增加纯 Java mesh validator 和确定摘要 hash。
2. 将 topology 构建、material/phase ownership、normal smoothing 分成独立步骤和数据类型。
3. 外表面与液液界面分别生成；phase pair canonical 排序和法向只由稳定规则决定。
4. 法线以面积/角度加权邻接平滑，但不跨 sharp material boundary；坏法线回退只能影响 shading，不能删 triangle。
5. 客户端 frame 按 dimension + region UUID + network generation + sequence 管理；tombstone 后旧包不能复活资源。
6. buffer 替换在 render thread 有明确 ownership；释放旧 frame 不影响新 generation。
7. 颜色、indicator、temperature 是独立 read-only vertex/material attributes，不改变几何 occupancy。

### 视觉验收

自动 validator 通过后，只请求一个真实场景：同一闭壳内含自由面、斜 cut-cell 和不可互溶界面，玩家绕行观察。记录唯一命令/建造步骤、预期和截图；不要把视觉检查扩成常态全量门。

## 性能 profile

### 测量边界

先在 CPU 路径分别测量：

- P2G、pressure/PCG、G2P/advection/reseed；
- thermal/reaction/phase/granular/gas；
- collision capture、entity snapshot、world interaction；
- snapshot build、编码、发送、客户端 mesh；
- checkpoint capture、压缩、force、回读；
- JVM allocation/GC 和 native/render buffer。

每条记录 region 尺寸、cell/marker/carrier、active fraction、substep、PCG iteration/residual、snapshot bytes、wall time。设备 timestamp 只用于 GPU stage 对比，调度安全仍看 wall time。

避免在常态代码中保留高开销 profiler。可使用已有命令 transcript 或单独开发 runner；若创建可复用 Python 分析脚本，放 `pytool` 并登记 [`../../maintenance/pytool.md`](../../maintenance/pytool.md)。原始 profile 文件不应作为 architecture 规范。

### 优化顺序

1. 消除 hot loop 临时分配和重复全域重建。
2. 只处理 dirty/active cell、interface 和 carrier；保持确定边界。
3. 让静态域进入守恒 motion/thermal sleep，目标至少 80% 体积可休眠。
4. snapshot 只在观察者存在且序列需要时构造；网络与 mesh 可降频，物理 dt 不随之改变。
5. 再评估 worker-thread CPU candidate；世界 capture/commit 留在主线程。
6. profile 证明 pressure/P2G 等数值 hot path 达到停止门后才投入 GPU。

不因耗时高而跳过 advection、截断 species、减少 ledger 或降低 checkpoint 完整性。超过 40/200 ms 门时保留当前 pacing/暂停语义，直到代表性数据支持调整。

## 后端边界准备

### 完整 state 审计

在 GPU 实现前逐字段审计 `FluidDomainState`：

- 基础 marker SoA、material/phase、chemical、granular、coherent、gas topology/inventory、thermal U/audit、pending phase-work；
- cut-cell raw mask、配置、registry identity 和硬预算；
- moving-body request 与 candidate generation；
- 所有 source/sink/work/roundoff ledger。

CPU capture → restore → one step 必须与直接 CPU one step 在当前容差内一致。若 state 缺字段，先补纯 Java handoff 和 checkpoint 语义，再写 shader。

### 并发协议

主线程冻结 world/collision/entity 和完整 base state，创建绑定 generation 的 request。backend 在私有内存计算 candidate。tick boundary 只接受：

- region identity 仍相同；
- base generation 尚未被 container/world transaction 改变；
- controller availability 和 required chunks 仍有效；
- candidate 是紧邻 generation、完整账本通过、wall-time 未超策略；
- moving body stale policy 已满足。

拒绝 candidate 时 base state 完全不变。不能合并半个 GPU pressure 结果与新的世界碰撞。

## 生产 GPU 后端

### 实现范围

生产 backend 必须实现完整 logical step，不是把纯水 pressure kernel 注册成可选后端。可以分阶段开发 kernel，但在覆盖完整 state 前只能作为 probe/validation 使用。

推荐内部顺序：

1. SoA 上传、generation header、bounds/canary 与确定 readback。
2. P2G/MAC、cut-face、variable density pressure/PCG。
3. G2P/advection/CCD/reseed。
4. material/chemical side columns 和 surface forces。
5. granular/coherent/gas topology 与交换。
6. thermal/reaction/phase/pending work。
7. moving solid boundary。
8. candidate audit、compact snapshot 与完整 checkpoint readback。

这不是发布顺序；只有全部覆盖并通过完整候选门后，才允许生产选择 GPU。

### 设备常驻与 readback

- marker、grid、side columns 和临时 PCG 向量常驻设备；常规 step 不全量回 JVM。
- inventory ledger 使用设备 reduction，并回读紧凑 audit/candidate header；不能只信 shader success。
- 客户端 snapshot 可在设备侧生成紧凑展示载荷，但服务端 generation/协议验证仍在 CPU。
- 完整 state 只在 checkpoint、unload、backend switch、诊断或设备故障恢复需要时分片回读。
- readback 每片绑定 region/generation/ordinal range/checksum；缺片不构造 partial state。

GPU 不取得磁盘、world、actor 或 HEAD 权限。即使 renderer 与 compute 使用同一物理设备，也不能让客户端 mesh buffer 成为服务端库存。

### 数值对照

GPU 无需逐 bit 等于 CPU，但每个 fixture 必须同时满足：

- 独立库存账本使用同一 exact/容差规则；
- pressure residual、divergence、volume drift、momentum 与 U 误差在预先冻结上限内；
- 相变 exact batch、integer nanos/SVQ/quanta 和 stable ordering 不因并行 nondeterminism 改变；
- 同一输入重复运行的差异不越过产品误差门；
- CPU fallback 从完整可读 state 继续时不重复 source/sink/work。

对 reduction、atomic 和 scan 明确确定性策略。不能用放宽守恒容差掩盖 race。

### 故障与回退

- shader compile/pipeline 创建失败：区域保持 CPU 或暂停，不修改后端选择的持久真值。
- step timeout：丢弃整个 candidate，保留 base state；达到策略门后切 CPU 或 PAUSED。
- device lost：停止提交，释放不可用资源；从最近完整 CPU-readable state/checkpoint 恢复。没有完整状态时 LOCKED/PAUSED，而不是从客户端 snapshot 重建。
- GPU audit 失败：保存有界诊断，禁止 HEAD/checkpoint；可在相同 base 上运行 CPU 对照定位。
- backend switch：只发生在完整 generation 边界，并验证 capture/restore ledger。

## 交付门

1. 代表性 profile 明确 CPU/GPU break-even 和主要耗时占比。
2. `64^3` cell 目标有容量、显存、readback 和 20 Hz/pacing 报告；不保证所有物理组合都达 20 Hz，但必须有产品预算。
3. CPU/GPU 对照覆盖 pure/material/chemical/granular/thermal/gas/phase 与 moving body。
4. GPU 超时、错误 candidate、device lost、checkpoint/readback 中断均只得到完整旧态或完整新态。
5. 无 GPU、驱动不支持或 probe 失败时 CPU Mod 仍可正常游玩。
6. 文档明确 CPU 继续是规范/恢复实现，生产 GPU 是计算后端而非第二套世界系统。

## 推荐提交顺序

先提交 mesh validator 和 fixture，再提交 profile instrumentation 与报告，然后审计/补全 backend state，最后逐 kernel 开发 probe。生产 backend 注册、选择策略、设备丢失恢复和文档必须作为独立的最后几个提交，不与 shader 初次实现混在一起。
