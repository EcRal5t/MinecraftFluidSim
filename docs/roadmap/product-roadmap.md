# Real Water 产品路线

本文是尚未完成里程碑的总表。当前基线已经完成 M0、M1、M2、M2.5、M3、M4、M5、M6、M7、M8 和 M9；直接下一产品阶段为 M10。完成证据见 [`../progress/README.md`](../progress/README.md)，当前系统契约见 [`../architecture/fluid-solver.md`](../architecture/fluid-solver.md)、[`../architecture/region-state-v1.md`](../architecture/region-state-v1.md)、[`../architecture/m3-material-state-migration.md`](../architecture/m3-material-state-migration.md)、[`../architecture/world-ownership-and-containers.md`](../architecture/world-ownership-and-containers.md)、[`../architecture/chemistry-state.md`](../architecture/chemistry-state.md)、[`../architecture/granular-state.md`](../architecture/granular-state.md) 与 [`../architecture/thermal-state.md`](../architecture/thermal-state.md)。

基线：Minecraft Java 26.2、Fabric Loader 0.19.3、Fabric API 0.157.0+26.2、已验收主 Mod 0.15.0、Java 25。活动求解器仍是 `FlipFluidDomain`，CPU 是规范后端。

## 技术规格路由

本文件只管理阶段顺序、范围和完成条件。实现某一阶段时再读对应主题，不把全部长期设计塞回 README：

| 主题 | 覆盖阶段 | 详细规格 |
|---|---|---|
| 材料、混合物、化学与热力学 | M3、M7、M9 | [`materials-and-chemistry.md`](materials-and-chemistry.md) |
| 多液体、自由界面、润湿与实体耦合 | M3、M4 | [`fluids-and-interfaces.md`](fluids-and-interfaces.md) |
| 气体网络、颗粒与沉积 | M5、M8 | [`gases-and-granular.md`](gases-and-granular.md) |
| section 持久化、ownership、容器与 Mod API | M6、M10 | [`world-integration.md`](world-integration.md) |
| 扩容、调度、并发与计算后端 | 跨阶段 | [`performance-and-backends.md`](performance-and-backends.md) |
| 协议、渲染、事务与 feature flag | 跨阶段 | [`cross-cutting-contracts.md`](cross-cutting-contracts.md) |
| 测试阶梯、里程碑签收与发布门 | 跨阶段 | [`acceptance-and-release.md`](acceptance-and-release.md) |

整理前混合了历史与未来内容的完整原文仍保存在 [`../archive/legacy-long-term-roadmap.md`](../archive/legacy-long-term-roadmap.md)，只用于核对迁移与追溯。

## 发布切割线

- **1.0 能力线**：M3 多液体/可变密度、M4 表面张力/润湿与 M5 密闭气体网络及气液压力均已完成；范围仍是控制器或开发命令圈定的有界区域。
- **2.0 集成线**：M6 ownership 评审、真实世界流体桥和容器已完成；后续仍不得跳过显式边界、事务恢复门或开放世界规模评审。
- M7 化学、M8 颗粒与 M9 热力学已在 2.0 集成线上完成；当前进入 M10 原版/其他 Mod API。
- 版本号按实际验收门决定，不随开发次数自然表示成熟度。

## 长期产品目标

1. 一个方块空间可包含任意体积、组分和相态，不受原版“一格一种流体”限制。
2. 可互溶液体连续混合；不可互溶液体共享机械压力但保留界面并按密度分层。
3. 开放空间使用环境大气；密闭空间保存有限气体库存和压力，小孔以有限速率连通。
4. 材料、相和反应数据驱动；所有源汇之外守恒质量、组成、电荷、体积和后续能量账本。
5. 颗粒阶段只覆盖沉淀、细沙等低成本沉降/沉积，不承诺通用刚体或弹性固体。
6. 先在有界工业区域达到可靠、可恢复和可量化，再评审河流、海洋和开放世界接管。

近期不做：逐格求解所有开放空气、逐分子/离子粒子、首版弱酸/缓冲液/活度/动力学/完整化学平衡、通用刚体/弹性体、无界天然洞穴扫描、海洋规模 20 Hz 承诺。M3 不提前实现有限溶解度或表面张力，但必须保留后续数据边界。

## 必须长期保持的分层

- **物理真值**：marker 体积、逐材料质量、化学组分/电荷、固体 SVQ/质量/组成、气体组分量/质量/组成与各载体内能分别结算。
- **数值离散**：压力、PLIC、cut-cell、组分输运和曲率；不能反过来结算物理库存。
- **渲染重建**：客户端从只读快照派生外表面、液液界面和颜色。
- **调度策略**：活动/降频/休眠/卸载/CPU-GPU 后端不得改变守恒结果。
- **世界事务**：任何 ownership 或原版方块替换都必须先有可恢复的原子发布状态。
- **协议/存档**：protocol、takeover journal 与 region-state 独立版本；未知格式必须明确拒绝。

## 目标数据边界

- material registry 定义密度、黏度、颜色、相态、互溶组和未来热/化学属性。
- marker 携带相/材料身份与守恒质量；单一纯水应严格退化为当前 `particleVolume` 解。
- 稀疏 mixture handle 只在多组分格存在，不为每个 marker 复制完整组分 map。
- 不可互溶相共享压力/速度框架；模型不能声称已解析界面滑移或乳化。
- 客户端 palette/界面字段是展示数据，不成为材料库存。
- 世界、bucket、region、checkpoint 和网络单位必须显式换算，不能用渲染 density 充当库存。

## M3 起点约束（后续阶段继续保持）

M3 已关闭，结果与证据见 [`../progress/m3-multi-fluid.md`](../progress/m3-multi-fluid.md)。后续阶段必须继续保持：

- marker 体积与逐材料质量是两套独立真值账本；单一纯水必须严格退化为当前 `particleVolume` 解。
- 稀疏 mixture handle 只在多组分格存在，不为每个 marker 复制完整组分 map。
- 不可互溶相共享压力/速度框架；模型不能声称已解析界面滑移或乳化。
- 客户端 palette/界面字段是展示数据，不成为材料库存。
- 正式世界入口中的 ownership 与桶矩阵已由 M6 完成；未来新增材料/容器路径仍必须保留体积与质量独立真值、稳定材料 ID 和跨持久域事务。
- reseeding 不跨 material/phase 合并；不得用跨材料合并或渲染补丁掩盖亚格滞留。

## M4：表面张力与润湿（已完成）

- 液气界面 `σ κ` 压力跳跃与 Young 接触角已进入活动核心；`σ=0` 逐位退化回 M3。实机视觉 smoke 已确认不同 Young 角的球冠形状差异。证据见 [`../progress/m4-surface-tension.md`](../progress/m4-surface-tension.md)。
- M4.1 液液 `σ12` 已进入活动核心：`FluidMaterialRegistry` 持有运行时-only phase 无序对张力，活动核心以相体积分数场 balanced-force CSF 接入压力投影；同密度满域液滴通过 Laplace 跳跃与无持续寄生流门。
- 仍约束后续阶段：
  - M3 遗留的液液界面亚格混相/锯齿仍保留：曲率驱动的物理锐化与逐顶点法线平滑尚未实现，且不得用渲染补丁替代物理。
  - 不得以墙角偏置、受支撑强制找平或渲染收缩替代表面张力。

## M5：空气区域网络与气液压力（已完成）

- 密闭气腔识别、纳体积量子精确气体账本、等温 EoS、有限速率孔流、拓扑事件库存迁移与 `p_gas` 自由面边界均已进入活动核心；开放水域与 M4 逐位一致，气体状态 runtime-only。证据见 [`../progress/m5-air-network.md`](../progress/m5-air-network.md)。
- 仍约束后续阶段：
  - M9 已先独立冻结并实现 gas `region-state v4` 基态，再由完整 v6 组合 gas + thermal；v4 不携 granular/thermal，不能从版本号猜联合内容。
  - 天然空腔搜索入口与 512 BV 默认预算留待未来阶段；控制器域只消费已知边界。
  - 气体温度/内能（M9）、气相反应（M7）与喷流细网格不在 M5 范围。

## M6：世界 ownership 与流体桥（已完成）

- M6.0 选定**空气 + 独立 ownership registry**，M6.1 完成 region-state HEAD 键控的 crash-safe ownership、精确部分/混合材料容器、玩家/发射器转移与跨持久域 WAL；主 Mod 0.12.0 的自动与实机证据见 [`../progress/m6-world-bridge-and-containers.md`](../progress/m6-world-bridge-and-containers.md)，现行契约见 [`../architecture/world-ownership-and-containers.md`](../architecture/world-ownership-and-containers.md)。
- 仍约束后续阶段：原版水流入破损 owned 边界不得静默吸收；持久 ownership 只走 controller 区域；未知/损坏 sidecar 必须 fail-closed；灭火、湿润、岩浆和外部 Mod API 留到 M10。

## M7：守恒化学（已完成）

M7 已以主 Mod 0.13.0 闭合：marker parcel、strong-electrolyte reaction、v3 region/protocol/container WAL、化学桶和指示剂
均进入生产路径。现行契约见 [`../architecture/chemistry-state.md`](../architecture/chemistry-state.md)，自动与实机验收记录见
[`../progress/m7-conservative-chemistry.md`](../progress/m7-conservative-chemistry.md)。后续阶段不得把气相化学、沉淀或热状态
回填进 M7 v3。

## M8：颗粒沉淀与低成本沉积（已完成）

M8 已以主 Mod 0.14.0 闭合：守恒 active parcel/deposit、统一排水与压力、浮力/阻力、孔隙水、安息角、休眠与
局部唤醒、固液相转移、v5 region-state、protocol v4 和 container-transfer WAL v4 均进入生产路径；低成本模型
通过自动守恒/形态门与合并实机场景，无需启动 MPM 评审。现行契约见
[`../architecture/granular-state.md`](../architecture/granular-state.md)，验收记录见
[`../progress/m8-granular-deposition.md`](../progress/m8-granular-deposition.md)。后续不得把 M9 热状态或 runtime-only
气体状态回填进 v5。2026-08-24 双 AI 关闭审阅进一步修复 precipitation 联合 occupancy、exact rule volume batch、
v5 audit-only identity、remove/empty 与 runtime collision 边界，并完成最近门复验；world granular registry store v3
由此取代无法安全重解释的 v2，旧 snapshot 保留并 LOCKED。

## M9：温度、相变和反应热（已完成）

- 权威内能、分段热容量状态函数与派生温度已经进入液体、颗粒、连续固相和密闭气体库存；温度、热容场与 LTE grid 始终只读派生。
- 有限体积导热、PLIC/cut-face 接触面积、世界固定温度储层、反应热、固液/气液相变、非等温气体压缩/孔流及能量 source/sink/work 审计已经进入生产 logical step。
- motion/thermal 两轴休眠、gas v4 前置、完整 region-state v6、protocol v5、`ThermalContents v1`、container WAL v5、旧 v1/v2/v3/v5 显式初温升级和 fail-closed 恢复均已通过自动门。
- 0.15.0 已通过 21 领域短里程碑门、受影响领域完整门、clean 构建与 thermal-on 专服启动；真实 controller 的旧 v2 跨重启、显式 v6 初始化及客户端立即恢复显示通过，production fixed-boundary/WAL fixture 与世界副本 v6 跨重启审计闭合。当前契约见 [`../architecture/thermal-state.md`](../architecture/thermal-state.md)，完成证据见 [`../progress/m9-thermal-phase-reaction.md`](../progress/m9-thermal-phase-reaction.md)。

## M10：原版行为与其他 Mod API

- 灭火、湿润、岩浆反应、红石、生物和寻路逐项适配。
- 提供材料注册、容器传输、区域端口、查询和事件 API。
- 外部 API 必须声明单位/映射，不得直接修改平滑 density 或客户端 mesh。

## 性能与停止条件

- 活动成本应随扰动表面积/活动 section 数增长，而不是随整个库存线性增长。
- 平静区域要能进入守恒休眠；扩大范围前代表性静态测试至少 80% 体积可休眠。
- 世界读取/修改留在服务器主线程；数值后端只消费不可变输入并在 tick 边界提交完整代次。
- GPU 常规步只回读紧凑诊断/快照；完整状态仅在 checkpoint、卸载、迁移或诊断时分片回读。
- 超时保留上一完整状态，不展示半步；CPU 始终是规范和回退。
- 32³ 方块 GPU 目标必须有真实报告，否则据实降低默认上限。

若阶段失败，依次缩小活动区、降低更新率、采用宏观节点/沉积状态，最后才降低空间分辨率；不得先牺牲守恒或用渲染隐藏失败。

## 验证策略

验证按风险升级，不是累计清单。日常小增量只跑 [`../maintenance/routing.md`](../maintenance/routing.md) 指定的一项最近门；里程碑关闭时再按 [`acceptance-and-release.md`](acceptance-and-release.md) 汇总实际受影响的层次。

## 接下来三项

1. 开始 M10 原版行为/其他 Mod API 的范围冻结，先分别定义灭火、湿润、岩浆和外部 API 的单位、事务与失败语义。
2. 先做代表性 CPU wall-time profile，分别量化 solver、世界交互与 readback；占比未知前不改 40/200 ms 阈值，再评审生产 GPU backend。
3. M10 不得借热学入口顺带扩大开放世界 ownership；灭火、湿润、岩浆和外部 Mod API 分别冻结单位、事务与失败语义后再实现。

开放/密闭域的液面黑三角继续作为独立视觉债建立最小 fixture；液液锐化和逐顶点法线平滑分别评估，不得用渲染补丁掩盖 marker 或相输运问题。
