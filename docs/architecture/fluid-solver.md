# Active CPU fluid solver

本文只描述当前生效的 CPU 流体契约。实现历史见 [`../progress/README.md`](../progress/README.md)，未来多材料路线见 [`../roadmap/product-roadmap.md`](../roadmap/product-roadmap.md)。Minecraft 活动核心是 `src/main/java/com/example/fluid/FlipFluidDomain.java`；旧 `FluidDomain.java` 只保留为未接入游戏的 VOF 对照。

## 状态边界

- **物理真值**：全部 marker `particleVolume` 的精确和。精确加水、删水、碰撞挤水和恢复只按 marker 账本结算。
- **M3 材料真值**：活动 marker 同时持有材料 handle、相标签和显式 `particleMass`；逐材料质量账本与 marker 体积账本各自独立结算，互不换算。存在变密度时 P2G 按质量/动量加权，同密度域继续逐位退化到原体积权重路径。
- **M4 数据契约**：`FluidMaterial` 冻结 `surfaceTension`（非负，0 为 M3 退化）与 `contactAngleRadians`（开区间 `(0, π)`，`π/2` 为无曲率中性）两个字段，活动 solver 已消费运行时注册值。当前 wire 与 `region-state` 字节仍不携带它们，decode 侧用 `σ=0`、`π/2` 重建，冻结的内建材料也保持该缺省以保证 v2 无损 round-trip；持久化真实数值仍需格式迁移评审。
- **M4 活动核心**：`σ>0` 时自由液气面使用 `p_interface = σ κ` 的 ghost-fluid 压力跳跃，曲率从 exact marker 液相分数一次平滑后 `κ=-div(n)` 计算；该压力跳跃同时进入压力 RHS 与投影，`σ=0` 逐位退化回 M3。固体润湿只在曲率法向中施加 Young 边界：近壁法向满足 `n·n_solid = -cosθ`，不使用吸墙或墙角偏置。
- **M4.1 液液数据边界**：`FluidMaterialRegistry` 增加运行时-only 的 `LiquidPairTension`（非负，按 phase handle 无序对去重），活动 solver 已以 balanced-force CSF 消费。液液 `σ12` 与 M4 单相字段一样不进入 material snapshot、wire 或 `region-state` 字节；decode 路径重建空 pair 表，保持 v2 无损 round-trip。持久化真实数值仍需格式迁移评审。
- **M5 气体真值**：每个密闭气腔的库存是标准状态等效体积，以 `long` 纳体积量子（1e-9 m³）精确记账；恒等式 `sealed + vented == drawnFromAmbient` 在 long 算术下严格成立，覆盖节点创建、注入、孔流输运、合并、分裂与 vent。液体 marker 账本与气体库存是两套独立真值，互不换算。
- **M5 数值离散**：气腔可用体积 `V_avail` 每子步由扫描器从平滑液体体积场连续推导（含界面格残差，归属相邻最小 node id 的腔，ambient 优先）；表压由等温状态方程 `p = P0·(V0/max(V,V_min) − 1)` 派生并钳制在数值窗口，`V_min = 1e-9 m³` 与纳体积量子同阶，新腔在 `V_avail < V_min` 时也保证环境平衡（`p=0`）。拓扑重扫按格多数归属迁移库存，真分裂按重叠计数比例分配、末项取余；新腔按环境平衡初始化（记为 drawn），开孔/消失经 remap 差额记为 vented。孔流按 CONTAM 幂律输运并钳制到均压分配，压差单调衰减不越过平衡。
- **M5 界面气压**：自由液气面的 ghost-fluid 跳跃升级为 `p_interface = σ κ + p_gas(该面空气格所属密闭腔)`，按面解析同一腔的双面液柱；环境大气贡献恰好为零，因此无密闭腔时活动核心与 M4 逐位一致。空腔扫描触达边界层或预算耗尽即分类 ambient，绝不保存为有限库存；部分开口面成为几何孔流边而非连通。
- **M5 运行时边界**：气体库存/压力 runtime-only，不进 wire、`region-state` 或客户端快照；仅 `fluid_snapshot_v1` 可携带只读调试用 gas node mask 扩展（display data，不携带库存/压力）。重启后密闭腔按环境平衡重建（`p=0`）。无独立配置门——气体不改变持久化格式，开放水域行为与 M4 逐位相同。
- **材料门**：多材料只在 `materialsEnabled` 配置门本次启动为开时绑定。门开时 demo、capture 和控制器区域都绑定内建 palette，纯水区域即 palette index 0 的单材料状态；门关时活动核心继续走纯水路径。
- **交接边界**：`FlipParticleState` 仍只允许纯水；任何非默认 material/phase/mass 状态导出都会显式失败。`FlipMaterialParticleState` 保存稳定 palette、每 marker material index 与 raw `double mass`；`FlipChemicalParticleState` 再组合 authoritative registry fingerprint、canonical mixture table、per-marker table index/indicator 及 source/sink audit。二者都只是内存 handoff，不是 wire 或磁盘格式；`region-state v2/v3` 各自独立校验长度、checksum、generation、palette、registry 与预算。
- **M6 世界/容器边界**：ownership sidecar、region-state 和 container-transfer WAL 分别提交；容器 `long` 纳体积与逐材料 raw `double` 质量在服务边界同 marker 账本核对。精确转移原语只提交完整成功结果，拒绝时不得留下 solver 残量。完整契约见 [`world-ownership-and-containers.md`](world-ownership-and-containers.md)。
- **M8 已完成边界**：显式绑定的 0.5 m CPU 域持有 exact-SVQ active parcel/deposit 库存；current/preview
  skeleton occupancy 通过同一 face void 和 moving-solid target 进入 free-surface、pressure matrix/projection、volume
  drift 与 runtime gas available volume，浮力/阻力向 MAC 散回等量反冲量。颗粒/沉淀 config v5、完整
  `region-state v5`、protocol v4、`GranularContents v1` 与 container WAL v4 已进入生产入口，并分别独立门控；
  M7 v3 未回填，`region-state v4` 仍只为气体持久化评审预留。M9 热状态不得写入这些既有格式。完整冻结契约见
  [`granular-state.md`](granular-state.md)。
- **数值离散**：`markerCount > 0` 决定压力流体格；平滑 `volume[]`、PLIC 分数、cut-cell 开口和目标散度都不是第二套水量。
- **渲染重建**：客户端使用 density + marker + solid 重建；marker mask 是浅水可见的必要内部种子，渲染不得反写物理。
- **M3 材料重建**：`FluidMaterialRenderSnapshot` 从 marker 只读派生总体积、逐材料/phase 量化分数与各自 marker 种子；每格分数确定性回和到 255。这些量化字段是 `fluid_snapshot_v2` 的展示数据，不携带精确库存，客户端也不得回写。
- **M3 界面几何**：`FluidMaterialSurfaceMesher` 只从上述快照生成总液相外表面和 partitioned tetrahedra 液液面。phase pair 只拥有一份共享面；相组成由 marker 格外推、再由总液相裁剪，不能用平滑 density 决定材料归属。服务端和客户端共用同一 mesher，客户端只在接受新 v2 快照时重建，不参与每帧物理。
- **M3 着色与不重合**：外表面颜色只由**含 marker 的格**投票，采样点按支持半径加权；落在自由面外侧空格的样本不得回退成 palette 0，无法解析时跳过该三角形而不是涂错色。液液面按固定 inset 裁剪到自由面**严格内侧**，因此两类几何不共面；并对噪声级相分数设下限，避免远离真实材料的伪界面。这两条都是渲染重建阈值，不触碰物理账本。
- **调度策略**：ACTIVE/REDUCED/休眠/UNLOADED 只控制何时持有或步进状态，不得改变 marker 账本。
- **碰撞**：墙面只约束法向，切向保持自由；禁止恢复吸墙、墙角偏置或受支撑强制找平。

## 当前 PIC/FLIP 循环

1. marker 向 MAC 网格做 P2G，重建 velocity、平滑 volume 和 containing-cell `markerCount`；变密度域从 marker 质量/动量求面速度，同密度域保持原体积权重。
2. 气体网络重扫：空腔拓扑 flood fill、库存迁移、`V_avail` 刷新、几何孔流边重建与有限速率输运，然后求出各密闭腔 `p_gas`（无密闭腔时此步只产生 ambient 标记，后续路径与 M4 逐位一致）。
3. 压力相开放 MAC 面向空气外推两层，避免未定义空气数组零值阻尼小水滴。
4. 施加重力，以共享 cut-face aperture 构造散度和压力系统。
5. PLIC ghost-fluid `theta` 在压力矩阵、预条件器和投影中保持一致；变密度域还在三处共享 `rho_ref / rho_face`，其中液液面使用相体积分数给出的界面距离加权密度。M4 表面张力在自由液气面施加 `p_interface = σ κ`：曲率法向在近壁处施加 Young 接触角，压力跳跃同时进入压力 RHS 和投影，`σ=0` 保持 M3 逐位退化。M4.1 液液 `σ12` 以 balanced-force CSF 动量形式按相体积分数场加入 MAC 速度，再进入同一压力投影。M5 密闭气压叠加进同一 `p_interface`（按面解析空气格所属腔），环境空气贡献为零。
6. 有界 rest-volume 目标只修正数值 marker 压缩，不编辑 `particleVolume`，也不强制液面水平。
7. 投影后的有效 MAC 速度再次外推，G2P 使用 90% FLIP / 10% PIC。
8. marker 逐轴扫掠并与 runtime cut-cell 碰撞，只截断命中轴法向。
9. 每个完整物理步末执行确定性守恒 reseeding：过密格 merge，欠采样格 split，硬预算不足无损跳过；merge 不跨 material/phase，split 同时分割质量并继承标签。

## 固体与自由表面

- solver 分辨率为每 Minecraft 方块每轴 2 cell。
- 每个 solver cell 另持有 runtime `2x2x2` occupancy，因此一个方块以 `4x4x4` 微体素保守采样 `VoxelShape`。
- 开放格体积为 1/8 增量，共享面开口为 1/4 增量；散度、PCG 和投影必须读取同一几何。
- PLIC 分数来自 containing-cell marker 精确体积和除以开放体积；`theta >= 0.05` 是公开的条件数正则化。
- `region-state v1` 只持久化 full-solid fallback。恢复后必须从完整已加载的当前世界重建 partial geometry 才能 step。

## 当前范围

- 正式 controller 世界入口支持纯水、内建材料和 canonical 多材料容器的精确装取/放出；纯水 ownership acquire 仍只移除扫描到的原版纯水，瞬态 takeover 不改变语义。外部空气使用零表压。容器与 ownership 约束见 [`world-ownership-and-containers.md`](world-ownership-and-containers.md)。
- 变系数压力算子已在活动核心生效，并通过 1.25:1 与 13.6:1 竖直界面静水门、不同截面连通器和重轻液翻转分层门。
- M4 液气表面张力与固体接触角已进入活动核心：静滴 Laplace 跳跃、无持续寄生流、合并/断开守恒和不同接触角铺展均已覆盖。
- M4.1 液液 `σ12` 已进入活动核心：同密度两相满域液滴通过 Laplace 跳跃门和 5 步无持续寄生流门；液液 `σ12` 尚未进入 wire/存档。
- M5 密闭气体网络已进入活动核心：等温 EoS、有限速率孔流、拓扑事件库存迁移与 `p_gas` 自由面边界均已生效；开放水域与 M4 逐位一致，气体状态 runtime-only 不进存档。
- 活动材料几何按格稀疏聚合实际出现的 phase，条目总数不超过域 marker 容量；多相交汇不会截断为两个相。该状态只服务压力离散，内部界面 mesh 另由上述 mesher 重建。
- 服务端 CPU 是物理规范；可选 GPU probe 仍只读、可回退，不写世界或存档。
- 已接通最多四个有界区域、控制器边界、独立调度和门控 `region-state v1/v2/v3/v5`（v4 保留并拒绝）；M6 已加入 crash-safe ownership 与容器转移，M7 已加入 marker 化学 parcel 与 v3 WAL，M8 已加入颗粒 v5 状态及 v4 容器 WAL，当前集成说明见根 [`README.md`](../../README.md)。
- 多材料 marker、逐材料质量账本、可变密度共享压力、严格 protocol v1/v2/v3 与客户端液液/化学只读重建已进入活动路径；材料边界见 [`m3-material-state-migration.md`](m3-material-state-migration.md)，化学边界见 [`chemistry-state.md`](chemistry-state.md)。

代码职责和最小验证任务只在 [`../maintenance/routing.md`](../maintenance/routing.md) 维护。
