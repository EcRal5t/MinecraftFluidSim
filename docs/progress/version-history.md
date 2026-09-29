# 主 Mod 版本历史

本文记录已经发布或成为基线的主 Mod 增量。它是历史档案，可以保留当时使用的里程碑名、格式代号、兼容策略和已知限制，但不定义当前行为。现行契约从 [`../architecture/`](../README.md) 读取。

## 0.3–0.5：有界世界交互

- **0.3**：加入 `/realwater pour|drain` 与定时碰撞刷新。
- **0.4**：加入自由面反扩散和 surface diagnostic；这属于早期 VOF 阶段，活动核心后来由 PIC/FLIP 替代。
- **0.5**：加入服务器权威的实体浮力/阻力采样；保持单向耦合。

## 0.6–0.6.1：活动 PIC/FLIP

- **0.6**：以 Godot 参考的 3D PIC/FLIP 循环替代活动 VOF；marker 携带精确体积，客户端使用 marching tetrahedra 重建。
- **0.6.1**：修正压力相 containing-cell 分类和 3D normal-only 碰撞；客户端密度卷积只影响重建。

## 0.7–0.8.2：世界事务与观测

- **0.7**：首次加入可逆 `/realwater takeover`，只移除纯原版水并在 remove/关服时恢复。
- **0.7.1**：碰撞挤水改按 marker 精确体积结算；快照增加 marker occupancy 以保持浅水可见。
- **0.8**：加入磁盘 takeover journal，世界修改前原子发布。
- **0.8.1**：强化 takeover journal 的 fail-closed、WARNING 和可重试恢复边界。
- **0.8.2**：加入 `/realwater` 与 `/realwatergpu` 的 UTF-8 命令 transcript；只做观测。

## M2 与 M2.5：区域与自由面基础

- **M2**：加入多区域 manager、控制器可见边界、完整 chunk 门、只读快照协议、独立生命周期和区域 checkpoint。
- **M2.5**：加入 PLIC fluid-air 压力距离、runtime cut-cell、有效 MAC 面外推、守恒 marker reseeding 和 pressure-only rest-volume 约束。

## 0.9–0.9.1：M3 多液体与可变密度

- **0.9.0**：marker 增加材料/相/质量账本，压力投影使用变密度 `1/rho` 面系数；加入互溶组聚合、不可互溶分层、material 区域布局与快照 capability、客户端 partitioned mesh，以及材料 fixture 和启动时冻结的材料功能门。
- **0.9.1**：修复外表面主导材料在自由面外侧空格被误判为 palette 0，以及液液界面裁剪到等值面导致与自由面共面闪烁；并对噪声级相分数设置下限。

## 0.10.0：M4 表面张力与润湿

- **0.10.0**：液气界面 `p_interface = sigma * kappa` ghost-fluid 压力跳跃进入活动 pressure RHS 与投影；近壁曲率法向施加 Young 接触角，不写死吸墙或墙角偏置。液液 `sigma12` 以材料 phase 无序对定义并按 balanced-force CSF 接入活动核心。`sigma=0` 逐位退化回无表面张力路径，wire 与 region-state 不携带新的库存字段。自动门覆盖静滴 Laplace 跳跃、液液压力跳跃、无持续寄生流、合并/断开守恒和不同接触角铺展；另提供瞬态表面张力 fixture。

可选 GPU probe 使用独立版本线，0.1.0–0.1.16 始终是只读观测路径，没有成为生产后端。

## 0.11.0：M5 空气区域网络与气液压力

- **0.11.0**：密闭气腔 flood fill 识别（预算 fail-open）、`long` 纳体积量子精确气体账本（`sealed + vented == drawnFromAmbient`）、等温状态方程、CONTAM 型有限速率孔流与拓扑事件库存迁移进入活动核心；自由面 ghost-fluid 跳跃升级为 `surface tension + p_gas`，环境空气贡献为零，开放水域与 M4 逐位一致。气体状态当时为 runtime-only；新增瞬态 `/realwater airdemo [pressure|vacuum]` 与 status 气体段。

## 0.12.0：M6 世界 ownership、流体桥与容器

- **0.12.0**：controller 通过 region-state HEAD 键控的 WAL/committed sidecar crash-safe 接管内部原版水；加入 `long` 纳体积部分纯水桶、保存逐材料体积与独立质量的 canonical 混合材料桶、solver all-or-nothing 精确转移、玩家/发射器目标路由、创造 external source/scoop 语义、部分桶守恒合并配方，以及 region/item 跨持久域 container-transfer WAL。未知、损坏和代次失配一律保留并锁定；堆叠与组件损坏安全拒绝。CPU 回归、专用服务器启动和合并实机场景通过。

## 0.13.0：M7 守恒化学

- **0.13.0**：加入 exact-rational species/phase/reaction registry、每 marker packed parcel 与 indicator provenance；强电解质完整解离和同格同 chemical-phase 的 `H+ + OH- -> H2O` 在最终 advection 后守恒执行。区域、快照和容器事务增加 chemical 布局；化学桶保存 fingerprint、逐 species quanta、质量 residual 与 indicator group，external source/escaped sink 可审计并持久化。客户端只读使用 Top-4、pH 与 indicator 派生颜色；加入通用指示剂、抹除器、化学 fixture 和管理员库存命令。

## 0.14.0：M8 颗粒沉淀与低成本沉积

- **0.14.0**：加入数据驱动颗粒 registry、守恒 active parcel/deposit inventory、浮力/阻力、moving-solid pressure source、精确排水、孔隙水、安息角沉积、休眠与局部唤醒；化学相转移保存固相 species composition。区域、只读快照和容器事务增加 granular 布局；`/mmm empty` 可把流体、active parcel 和 deposit 作为可审计 sink 一次清空。自动发布门、专用服务器启动和客户端/controller/发射器/沉淀/重启合并实机场景通过。

## 0.15.0–0.15.4：M9 温度、相变和反应热

- **0.15.0**：每个液体、颗粒、连续固相和密闭气体载体保存独立权威内能，温度与热容量只由 world-authoritative 分段状态函数派生；有限体积导热、固定温度世界储层、反应热、固液/气液 exact-batch 相变、非等温气体传质/压缩功及 source/sink/work 审计进入生产 logical step。区域、只读快照、物品内容和容器事务增加完整 thermal 布局。自动发布门、专服启动、真实世界副本跨 checkpoint/restart，以及真实 controller 初始化和客户端立即恢复显示通过。
- **0.15.1**：修正 condensed mass 的 `cellSize^3` SI 换算、controller sealed gas 边界、Boussinesq 自然对流和气液多批相变；runtime 统一使用完整 thermal 区域布局。
- **0.15.2**：收紧 sealed gas 新拓扑 bootstrap 的周转闭合条件；活库存或任何不闭合状态均保留并锁定。
- **0.15.3**：将气液相变的 standard-volume nanos 固定为 canonical 单批整数倍，并在凝结时联合拆分独立 nanos/kg/species/solver-residual 列；既有 grouped-rounding 蒸气尾数可守恒继续运行，不再误触发 exact transition mass 停止门。
- **0.15.4**：checkpoint capture 强制 current marker/occupancy 与 gas topology/U remap 同代；部分充液界面使用 runtime cavity lineage 避免假 dry-air turnover。加入只在明确 restore failure 后可用的紧邻上一完整代全量验证/原子 HEAD 恢复；校准新 world water-vapor `c_v` 与潜热，status 分离 air/vapor gross turnover 并标记 phase budget hit。checkpoint 22→21→新代跨两次重启的原世界实机验收通过，M9 正式关闭。

## 0.16.0：当前格式收敛与文档重整

- **0.16.0**：删除区域、配置、容器 WAL 和颗粒 payload 的历史 reader、自动迁移与缺字段补全；删除显式热初始化入口。所有持久载荷必须与本次启动冻结的功能布局和 registry identity 完全一致，否则原样保留并锁定。
- 保留双槽 checkpoint 的同布局上一完整代恢复；它属于当前事务故障恢复，不是跨版本迁移。
- 文档重新分为当前架构、未来路线、维护路由和版本历史；后续修订恢复了当前技术架构的组件、公式、数据布局和事务细节，使删除重复进度报告不再损失维护所需知识。

## 0.17.0：架构重整完成

- 将生产代码物理拆为 `api`、`model`、`solver`、`application`、Fabric adapter、GPU kernel 与 GPU adapter；编译和静态门直接约束依赖方向。
- 删除 `FluidSimulationService`、`FlipThermalState`、`FlipFluidDomain`、`FluidTransferService` 与 `RegionStateFiles` 等过渡层；运行时组合、tick、policy、operator、事务、thermal 与 GPU native 资源均有唯一 owner。
- `FluidDomain` 保持唯一 public live aggregate，但实现按同一对象的 mechanics、step、inventory、collision、capability 与 gas/granular 责任分区；生产类 1000 行硬门不再含迁移 allowlist。
- validation 按 scenario 文件拆分且 selector 语义不变；当前 world capability、ACTIVE/REMOVED region、ownership/container fault、CPU milestone/soak 与 GPU probe 门继续使用同一现行格式和不变量。

## 2026-09-30：统一液体黏度来源

C01 的 1a 将颗粒阻力、Darcy 孔隙阻力和床面剪切统一到世界材料定义的 `mu(T)`，移除 granular carrier 的黏度字段。后端交接携带材料定义，冷恢复从已提交世界能力包取得定义。审查补齐平流后的属性失效、无颗粒湿格、逐步越域诊断、自定义物性恢复及存档前身份校验。

验证通过 `materials,granular,region-state,lifecycle,thermal,thermal-extremes` 与 `verifyArchitecture`。审查后 `granular-active` 单进程预热 20 步、采样 80 步，平均 57.31 ms、p95 100.60 ms、9.03 MiB/步；无受控的改前对照，不作为性能提升证据。
