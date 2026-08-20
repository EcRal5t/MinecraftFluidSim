# Active CPU fluid solver

本文只描述当前生效的 CPU 流体契约。实现历史见 [`../progress/README.md`](../progress/README.md)，未来多材料路线见 [`../roadmap/product-roadmap.md`](../roadmap/product-roadmap.md)。Minecraft 活动核心是 `src/main/java/com/example/fluid/FlipFluidDomain.java`；旧 `FluidDomain.java` 只保留为未接入游戏的 VOF 对照。

## 状态边界

- **物理真值**：全部 marker `particleVolume` 的精确和。精确加水、删水、碰撞挤水和恢复只按 marker 账本结算。
- **M3 材料真值**：活动 marker 同时持有材料 handle、相标签和显式 `particleMass`；逐材料质量账本与 marker 体积账本各自独立结算，互不换算。存在变密度时 P2G 按质量/动量加权，同密度域继续逐位退化到原体积权重路径。
- **M4 数据契约**：`FluidMaterial` 冻结 `surfaceTension`（非负，0 为 M3 退化）与 `contactAngleRadians`（开区间 `(0, π)`，`π/2` 为无曲率中性）两个字段。当前 wire 与 `region-state` 字节都不携带它们，decode 侧用 `σ=0`、`π/2` 重建，冻结的内建材料也保持该缺省以保证 v2 无损 round-trip；真实数值要等 solver 消费它们、且格式迁移方案评审通过后再进入存档。
- **M4 活动核心**：`σ>0` 时自由液气面使用 `p_interface = σ κ` 的 ghost-fluid 压力跳跃，曲率从 exact marker 液相分数一次平滑后 `κ=-div(n)` 计算；该压力跳跃同时进入压力 RHS 与投影，`σ=0` 逐位退化回 M3。固体润湿只在曲率法向中施加 Young 边界：近壁法向满足 `n·n_solid = -cosθ`，不使用吸墙或墙角偏置。
- **材料门**：多材料只在 `materialsEnabled` 配置门本次启动为开时绑定。门开时 demo、capture 和控制器区域都绑定内建 palette，纯水区域即 palette index 0 的单材料状态；门关时活动核心继续走纯水路径。
- **交接边界**：`FlipParticleState` 仍只允许纯水；任何非默认 material/phase/mass 状态导出都会显式失败。`FlipMaterialParticleState` 是独立内存 handoff，保存稳定 palette、每 marker material index 与 raw `double mass`；它本身不是 wire 或磁盘格式，`region-state v2` 仍独立校验长度、checksum、generation、palette 与预算。
- **数值离散**：`markerCount > 0` 决定压力流体格；平滑 `volume[]`、PLIC 分数、cut-cell 开口和目标散度都不是第二套水量。
- **渲染重建**：客户端使用 density + marker + solid 重建；marker mask 是浅水可见的必要内部种子，渲染不得反写物理。
- **M3 材料重建**：`FluidMaterialRenderSnapshot` 从 marker 只读派生总体积、逐材料/phase 量化分数与各自 marker 种子；每格分数确定性回和到 255。这些量化字段是 `fluid_snapshot_v2` 的展示数据，不携带精确库存，客户端也不得回写。
- **M3 界面几何**：`FluidMaterialSurfaceMesher` 只从上述快照生成总液相外表面和 partitioned tetrahedra 液液面。phase pair 只拥有一份共享面；相组成由 marker 格外推、再由总液相裁剪，不能用平滑 density 决定材料归属。服务端和客户端共用同一 mesher，客户端只在接受新 v2 快照时重建，不参与每帧物理。
- **M3 着色与不重合**：外表面颜色只由**含 marker 的格**投票，采样点按支持半径加权；落在自由面外侧空格的样本不得回退成 palette 0，无法解析时跳过该三角形而不是涂错色。液液面按固定 inset 裁剪到自由面**严格内侧**，因此两类几何不共面；并对噪声级相分数设下限，避免远离真实材料的伪界面。这两条都是渲染重建阈值，不触碰物理账本。
- **调度策略**：ACTIVE/REDUCED/休眠/UNLOADED 只控制何时持有或步进状态，不得改变 marker 账本。
- **碰撞**：墙面只约束法向，切向保持自由；禁止恢复吸墙、墙角偏置或受支撑强制找平。

## 当前 PIC/FLIP 循环

1. marker 向 MAC 网格做 P2G，重建 velocity、平滑 volume 和 containing-cell `markerCount`；变密度域从 marker 质量/动量求面速度，同密度域保持原体积权重。
2. 压力相开放 MAC 面向空气外推两层，避免未定义空气数组零值阻尼小水滴。
3. 施加重力，以共享 cut-face aperture 构造散度和压力系统。
4. PLIC ghost-fluid `theta` 在压力矩阵、预条件器和投影中保持一致；变密度域还在三处共享 `rho_ref / rho_face`，其中液液面使用相体积分数给出的界面距离加权密度。M4 表面张力在自由液气面施加 `p_interface = σ κ`：曲率法向在近壁处施加 Young 接触角，压力跳跃同时进入压力 RHS 和投影，`σ=0` 保持 M3 逐位退化。
5. 有界 rest-volume 目标只修正数值 marker 压缩，不编辑 `particleVolume`，也不强制液面水平。
6. 投影后的有效 MAC 速度再次外推，G2P 使用 90% FLIP / 10% PIC。
7. marker 逐轴扫掠并与 runtime cut-cell 碰撞，只截断命中轴法向。
8. 每个完整物理步末执行确定性守恒 reseeding：过密格 merge，欠采样格 split，硬预算不足无损跳过；merge 不跨 material/phase，split 同时分割质量并继承标签。

## 固体与自由表面

- solver 分辨率为每 Minecraft 方块每轴 2 cell。
- 每个 solver cell 另持有 runtime `2x2x2` occupancy，因此一个方块以 `4x4x4` 微体素保守采样 `VoxelShape`。
- 开放格体积为 1/8 增量，共享面开口为 1/4 增量；散度、PCG 和投影必须读取同一几何。
- PLIC 分数来自 containing-cell marker 精确体积和除以开放体积；`theta >= 0.05` 是公开的条件数正则化。
- `region-state v1` 只持久化 full-solid fallback。恢复后必须从完整已加载的当前世界重建 partial geometry 才能 step。

## 当前范围

- 正式世界入口（桶、原版方块替换、takeover）仍只创建密度 1000 的纯水，外部空气使用零表压。多材料输入目前只有瞬态 `/realwater materialdemo`；完整桶矩阵留到 M6.1。
- 变系数压力算子已在活动核心生效，并通过 1.25:1 与 13.6:1 竖直界面静水门、不同截面连通器和重轻液翻转分层门。
- M4 液气表面张力与固体接触角已进入活动核心：静滴 Laplace 跳跃、无持续寄生流、合并/断开守恒和不同接触角铺展均已覆盖；液液 `σ12` 仍待后续材料对系数。
- 活动材料几何按格稀疏聚合实际出现的 phase，条目总数不超过域 marker 容量；多相交汇不会截断为两个相。该状态只服务压力离散，内部界面 mesh 另由上述 mesher 重建。
- 服务端 CPU 是物理规范；可选 GPU probe 仍只读、可回退，不写世界或存档。
- 已接通最多四个有界区域、控制器边界、独立调度、`region-state v1` 与门控 `region-state v2`；当前集成说明见根 [`README.md`](../../README.md)。
- 多材料 marker、逐材料质量账本、可变密度共享压力、门控 protocol v2 与客户端液液界面渲染已进入活动路径；跨层兼容边界见 [`m3-material-state-migration.md`](m3-material-state-migration.md)。

代码职责和最小验证任务只在 [`../maintenance/routing.md`](../maintenance/routing.md) 维护。
