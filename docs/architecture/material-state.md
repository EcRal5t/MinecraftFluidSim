# 材料状态契约

修改材料注册、质量、混合物或液液界面时读本文。

单位与整数编码统一见[设计书](system-overview.md#units)。

## 修改入口

- **材料定义与单位**
  - 代码入口：
    - [FluidMaterialRegistry](../../model/src/main/java/mmm/fluid/FluidMaterialRegistry.java)
    - [BuiltinFluidMaterials](../../solver/src/main/java/mmm/fluid/BuiltinFluidMaterials.java)
    - [SolverUnitScale](../../model/src/main/java/mmm/units/SolverUnitScale.java)
  - 测试选择器：`materials`

- **插入、提取和容器表示**
  - 代码入口：
    - [MaterialTransferPlanner](../../solver/src/main/java/mmm/fluid/MaterialTransferPlanner.java)
    - [FluidMixture](../../model/src/main/java/mmm/transfer/FluidMixture.java)
  - 测试选择器：`materials,containers`

- **材料界面和表面张力**
  - 代码入口：
    - [MaterialSurfaceGeometry](../../fabric/src/client/java/mmm/client/MaterialSurfaceGeometry.java)
    - [SurfaceTensionFields](../../solver/src/main/java/mmm/fluid/SurfaceTensionFields.java)
  - 测试选择器：`materials,surface-tension`

- **定义的持久化**
  - 代码入口：[MaterialRegistryCodec](../../fabric/src/main/java/mmm/fluid/MaterialRegistryCodec.java)
  - 测试选择器：`region-state`

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [权威数据](#权威数据)
- [Registry 与运行时 handle](#registry-与运行时-handle)
- [求解器边界](#求解器边界)
- [表面与展示](#表面与展示)
- [跨层表示](#跨层表示)
- [功能配置](#功能配置)

## 权威数据

- `LiquidCellInventory.Stock` 按单元、材料和固定开放分量保存体积、整数质量、组成、内能与指示剂。
- 区域及逐材料体积、质量账本由单元库存精确求和闭合。
- 液体体积使用 `SignedDyadic`，质量使用统一整数编码；handoff 直接共享不可变库存行。
- 注液计划从逐格原始体积的精确和扣除开放容量，计划内各材料共同抵扣这份精确剩余空间。请求剩余量也按实际片段精确扣减，保证跨格注入量不超过请求。转成 binary64 观察量及 binary32 注入片段时向下舍入；操作回执将实际接受量在 binary32 接口处舍入一次。化学量子不足以表示的片段留在请求剩余量中。
- `FluidMaterialRegistry` 是运行时材料定义入口；持久化和容器使用稳定 ID，不保存运行时 handle。
- 当前机械定义包含 stable ID、参考密度、miscibility group、液气表面张力和接触角；液液张力由 registry 的 phase 无序对表给出。
- 显示颜色来自独立只读 palette。
- 液体动态黏度只有一份权威定义：world material registry 按 `Pa·s` 保存分段 `mu(T)` 与有效温区，写进世界能力包。`FluidMaterial` 不保存黏度，granular carrier 也不再自带 `dynamic_viscosity`，它只声明颗粒阻力标定用的 Reynolds 上限。
- 同一材料条目保存可选体膨胀定义：载体 ID、参考温度与体积膨胀系数。三项都进入材料指纹和世界能力包；浮力计算读取已绑定条目。生产热学能力要求支持的液体具有该定义。
- 实际单元记录的质量和体积参与库存结算，参考密度用于创建、校验和状态函数绑定。

## Registry 与运行时 handle

定义按声明顺序建立稠密 material handle；stable ID 必须是合法 Minecraft identifier 且唯一。miscibility group 再映射为 phase handle：同组材料不生成内部液液界面，不同组材料保持 phase 分区。
材料单位身份为 `mmm:material_si`；能力包和区域 palette 的参考密度字段为 `ReferenceDensityKgPerM3Bits`，按 kg/m³ 保存 binary64 原始位。

handle 用于数组索引。checkpoint 保存 canonical material palette，容器保存 stable ID；恢复时先验证完整 palette/registry identity，再建立当前进程 handle。长期状态仅保存 stable ID，不持久化瞬态整数 handle。

内置基线目前提供 water、oil 和 mercury。内置 palette 作为生产默认定义集使用；已提交区域由 checkpoint 中的 palette 与 registry identity 权威解释。

### 黏度求值

分段 `mu(T)` 在连接点上连续，两段共享同一端点值。两条求值方式共享同一次分段查找，只在越域处理上不同：

| 方式 | 声明温区外 | 用途 |
|---|---|---|
| 严格 `dynamicViscosityAt` | 抛 `IllegalArgumentException` | registry 校验、测试断言 |
| 延拓 `dynamicViscosityExtendingAt` | 取最近端点的声明值（常值延拓，不外推斜率） | 载体耦合，必须覆盖热学可达的全部温度 |

域内沿声明段线性插值，域外保持最近端点的正黏度。两种求值都拒绝非有限温度和低于热学严格正温数值域的输入。`minimumViscosityTemperatureK` / `maximumViscosityTemperatureK` 供诊断报告声明温区。

材料定义随后端交接一起走：`FluidDomainState` 保存不可变 `MaterialRegistry` 引用，`Layout` 保存其 fingerprint。`bindMaterialDefinitions` 独立于颗粒能力；生产材料域和各附加能力域均绑定世界定义，冷恢复从已提交能力包取得。颗粒交接必须有定义，独立无黏度数值测试域可以不绑定。

## 求解器边界

- 不可互溶材料共享 MAC 压力/速度框架，但保留独立相身份和液液界面。
- pressure density、平滑相分数、PLIC、曲率和客户端 mesh 都是数值或展示字段，读取实际单元库存。
- 共享通量联合交接体积、整数质量、组成和内能；实际整数质量通量同时推进 MAC 动量。
- 单一纯水域必须退化为同一机械求解路径，不额外引入材料修正源汇。

库存观察的格内组成密度从实际单元记录推导：

```text
rho_cell = sum(stock_mass_kg) / (h^3 * sum(stock_normalized_volume))

mass_kg   = massUnits / Mass.UNITS_PER_KILOGRAM
solver_mass = mass_kg / cellSize^3
volume_m3 = stock_normalized_volume * cellSize^3
```

热区域的压力与颗粒载体物性使用连续组成密度 `rho_i = sum(w_pi*m_p)/sum(w_pi*V_p)`。权重与热浮力共用 cell-center 三线性支撑，限制在完全开放、连通且属于同一 pressure phase 的有液体单元；
外壳附近归一化，partial cut-cell 和含多相单元保留所属单元采样。该字段按载体位置与热状态代次重建，保持所有质量和体积库存原样。
无热状态的压力密度沿用所属单元的组成密度。

化学单元记录的整数闭合式为 `massUnits = speciesMassUnits + massResidualUnits`；
溶质增加实际质量而体积不变时，机械密度自然上升，因此盐水下沉来自同一权威质量账本，不需要盐材料特判。

pressure face 的 `1/rho` 系数按界面距离加权。面密度比、迁移率及压力矩阵系数使用 binary64，投影结果最终写入 binary32 MAC 速度。热膨胀浮力项从相同支撑上的逐载体温度和实际质量推导热密度变化，并沿相同的两段界面距离积分；库存密度、压力惯性和热密度修正各自保存明确的派生含义。

同温盐水分层由溶解的 Na⁺/Cl⁻ 的实际质量得到。`LiquidStratificationValidation` 检查盐水下层静水、反置翻转、同组成温差分层及热盐竞争，并逐步核对体积、材料质量、species 和内能；热修正有效范围见[热浮力](thermal-state.md#热浮力)。

同一互溶组的材料共享物相与速度场，各材料保留独立单元库存。
实际体积决定格内比例，不可互溶材料保留相分区并共享统一力学主干。

## 表面与展示

- 外表面使用总 liquid occupancy；液液界面使用逐 phase fraction，两者必须分开裁剪。
- phase pair 只生成一份共享 mesh，归属和法向确定；互溶材料不生成内部面。
- material palette、ARGB、平滑 fraction 和 marker seed 都是展示数据。
- 当前内置材料的 surface/contact 参数可以退化为零张力/中性接触角；测试或数据定义只有在进入 runtime registry 后才影响 mechanics。

## 跨层表示

- 区域 checkpoint 保存稳定材料 palette、单元记录的材料索引、实际体积与质量、组成和内能。
- `FluidMixture` 保存容器内各稳定材料的纳体积和独立 kg 质量；tooltip 与耐久条只读。
- 玩家和发射器只向实际目标区域转移，单次物品计数必须为 1。
- 容器与区域之间的物质变化通过同一 WAL 事务提交；任何不能被物品表示的结果在修改 solver 前拒绝。

`FluidMixture` 最多保存 64 个按 stable ID 排序的 component；每项包含 `volumeNanos` 和 `massUnits` 整数。
构造时拒绝重复 ID、非正体积/质量和超过一桶的总量。物品中单材料可以使用紧凑 component 表示，多材料使用 canonical mixture 文本；
进入 WAL 时两者都规范化为完整 mixture truth，reader 不现场合成缺失 component。

抽取先预检目标库存与物品可表示数量，再从实际单元记录生成结算；物品须能表示实际体积、逐材料质量、组成与携带内能。请求量与实际可表示量不一致时，WAL 记录实际量，不用请求量伪造闭合。

`setVolumes(float[])` 在一次事务中替换整域目标体积；数组按 x、y、z 递增的单元索引排列。
单格 `setVolume` 共用同一准备过程，体积按固定碰撞开放量减去实际固体占容裁剪；默认材料为 handle 0，可选材料数组为初始化提供逐格材料身份。
旧库存的物种、质量与内能记外部 sink，新库存按目标材料密度、默认组成和注入温度记 external source。
MAC 按实际质量变化移走对应动量或混合静止注入质量，明确的混合动能损失记局部热；库存、热账和气腔全部预检后一次发布。
原版水采集、普通水池、气腔水池、分层与球冠演示先收集目标体积，再调用同一批入口；准备工作按格数与现有库存记录数线性遍历。
材料 palette 通过 `bindMaterialRegistry` 直接绑定，保留已有 Stock 与 MAC 状态。

材料混合物注入先规划目标单元记录，按体积份额分配整数质量并保留守恒余数。
返回的逐材料质量必须与实际写入库存精确相等；体积差额按库存记录中的精确数量结算。
显式化学注入同时分配整数物种，片段质量余量由实际质量整数减去物种质量整数求得，逐组精确闭合。
拆分或转移需要两侧具有正体积、正质量与可表示的化学组成；低于编码分辨率时保留来源库存。
抽取回执从实际库存差额累计整数质量，体积仍按实际片段精确求和后输出。

无材料参数的体积抽取选择默认载体（handle 0），混合抽取使用独立的 `tryExtractExactMixture`。

## 功能配置

材料功能在启动时冻结。持久区域的载荷形状必须与当前功能配置完全一致；不一致时保留 checkpoint 并锁定，不自动增删材料状态。
