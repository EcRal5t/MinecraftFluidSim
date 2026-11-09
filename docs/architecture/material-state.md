# 材料状态契约

修改材料注册、质量、混合物或液液界面时读本文。

单位与整数编码统一见[设计书](system-overview.md#units)。

## 修改入口

- **材料定义与单位**
  - 代码入口：
    - [FluidMaterialRegistry](../../model/src/main/java/mmm/fluid/FluidMaterialRegistry.java)
    - [WorldMaterialDataPackLoader](../../fabric/src/main/java/mmm/simulation/WorldMaterialDataPackLoader.java)
    - [FluidDefinitions](../../solver/src/main/java/mmm/fluid/FluidDefinitions.java)
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

执行方式见 [验证指南](../maintenance/validation.md#host)。

## 本文内容

- [权威数据](#权威数据)
- [Registry 与运行时 handle](#registry-与运行时-handle)
- [求解器边界](#求解器边界)
- [表面与展示](#表面与展示)
- [跨层表示](#跨层表示)
- [功能配置](#功能配置)

## 权威数据

- `LiquidCellInventory.Stock` 按单元、材料和固定开放分量保存体积、整数质量、组成、内能。
- 区域及逐材料体积、质量账本由单元库存精确求和闭合。
- 液体体积使用 `SignedDyadic`，质量使用统一整数编码；handoff 直接共享不可变库存行。
- 注液计划从逐格原始体积的精确和扣除开放容量，计划内各材料共同抵扣这份精确剩余空间。请求剩余量也按实际片段精确扣减，保证跨格注入量不超过请求。转成 binary64 观察量及 binary32 注入片段时向下舍入；操作回执将实际接受量在 binary32 接口处舍入一次。化学量子不足以表示的片段留在请求剩余量中。
- `FluidMaterialRegistry` 是运行时材料定义入口；持久化和容器使用稳定 ID，不保存运行时 handle。
- 当前机械定义包含 stable ID、参考密度、miscibility group、液气表面张力、接触角和显示底色；液液张力由 registry 的 phase 无序对表给出。
- 材料底色随数据包定义和世界能力包保存，最终颜色由客户端选择的组分、pH 或温度模式决定。
- 液体动态黏度只有一份权威定义：world material registry 按 `Pa·s` 保存分段 `mu(T)` 与有效温区，写进世界能力包。`FluidMaterial` 不保存黏度，granular carrier 也不再自带 `dynamic_viscosity`，它只声明颗粒阻力标定用的 Reynolds 上限。
- 同一材料条目保存可选体膨胀定义：载体 ID、参考温度与体积膨胀系数。三项都进入材料指纹和世界能力包；浮力计算读取已绑定条目。生产热学能力要求支持的液体具有该定义。
- 实际单元记录的质量和体积参与库存结算，参考密度用于创建、校验和状态函数绑定。

## Registry 与运行时 handle

定义按声明顺序建立稠密 material handle；stable ID 必须是合法 Minecraft identifier 且唯一。miscibility group 再映射为 phase handle：同组材料不生成内部液液界面，不同组材料保持 phase 分区。
材料单位身份为 `mmm:material_si`；能力包和区域 palette 的参考密度字段为 `ReferenceDensityKgPerM3Bits`，按 kg/m³ 保存 binary64 原始位。

handle 用于数组索引。checkpoint 保存 canonical material palette，容器保存 stable ID；恢复时先验证完整 palette/registry identity，再建立当前进程 handle。长期状态仅保存 stable ID，不持久化瞬态整数 handle。

默认数据包在 `data/mmm/mmm/fluid_materials` 提供 water、oil 和 mercury。每份 JSON 声明密度、互溶组、表面张力、接触角、显示色、温度黏度点和热浮力参数；`fluid_interfaces` 中的可选定义声明两组互溶组之间的张力。Java 中仅保存稳定物质 ID。已提交区域由世界能力包中的完整定义和 checkpoint 中的材料身份共同解释。

`FluidSolverConfig` 保存格长、重力、步长和数值求解参数。新建纯水库存、原版桶转换、容器回执与持久恢复统一读取世界定义中的水密度。完整定义通过 `FluidDefinitions` 传递，液体库存功能关闭时仍可查询相应物性。

### 黏度求值

分段 `mu(T)` 在连接点上连续，两段共享同一端点值。两条求值方式共享同一次分段查找，只在越域处理上不同：

| 方式 | 声明温区外 | 用途 |
|---|---|---|
| 严格 `dynamicViscosityAt` | 抛 `IllegalArgumentException` | registry 校验、测试断言 |
| 延拓 `dynamicViscosityExtendingAt` | 取最近端点的声明值（常值延拓，不外推斜率） | 载体耦合，必须覆盖热学可达的全部温度 |

域内沿声明段线性插值，域外保持最近端点的正黏度。两种求值都拒绝非有限温度和低于热学严格正温数值域的输入。`minimumViscosityTemperatureK` / `maximumViscosityTemperatureK` 供诊断报告声明温区。

材料定义随后端交接一起走：`FluidDomainState` 保存完整不可变世界定义，`Layout` 保存其 fingerprint。材料与附加能力绑定均核对同一份定义，冷恢复从已提交能力包取得。宿主状态函数和 GPU 材料表从相同数据生成。

## 求解器边界

- 不可互溶材料共享 MAC 压力/速度框架，但保留独立相身份和液液界面。
- pressure density、平滑相分数、PLIC、曲率和客户端 mesh 都是数值或展示字段，读取实际单元库存。
- 共享通量联合交接体积、整数质量、组成和内能；实际整数质量通量同时推进 MAC 动量。
- 单一纯水域必须退化为同一机械求解路径，不额外引入材料修正源汇。

库存观察的格内密度由实际整数质量和共同状态方程给出的实际液体体积求得：

```text
mass_kg = massUnits / Mass.UNITS_PER_KILOGRAM
rho_liquid = sum(mass_kg) / actual_liquid_volume_m3
```

化学单元记录的整数闭合式为 `massUnits = speciesMassUnits + massResidualUnits`；溶质改变实际质量和体积后，密度由同一库存求值。盐水黏度和水—乙醇组成物性采用声明范围内的混合近似，详见[化学状态](chemistry-state.md)；温度相关热容与参考能由各载体的热状态函数求值。

共同压力使用两侧液气质量形成的 MAC 面惯性；重力与温度相关浮力使用同代物质状态。热浮力的适用范围见[热浮力](thermal-state.md#热浮力)。

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

`setVolumes(float[])` 在一次事务中替换整域目标体积；数组按 `SpatialGrid` 的紧凑单元索引排列，通过 `grid.cell(x,y,z)` 映射实际坐标。
单格 `setVolume` 共用同一准备过程，体积按固定碰撞开放量减去实际固体占容裁剪；默认材料为 handle 0，可选材料数组为初始化提供逐格材料身份。
旧库存的物种、质量与内能记外部 sink，新库存按目标材料密度、默认组成和注入温度记 external source。
MAC 按实际质量变化移走对应动量或混合静止注入质量，明确的混合动能损失记局部热；库存、热账和逐格气体后继共同准备后一次发布。
原版水采集、普通水池、分层与球冠演示先收集目标体积，再调用同一批入口；准备工作按格数与现有库存记录数线性遍历。
材料 palette 通过 `bindMaterialRegistry` 直接绑定，保留已有 Stock 与 MAC 状态。

材料混合物注入先规划目标单元记录，按体积份额分配整数质量并保留守恒余数。
返回的逐材料质量必须与实际写入库存精确相等；体积差额按库存记录中的精确数量结算。
显式化学注入同时分配整数物种，片段质量余量由实际质量整数减去物种质量整数求得，逐组精确闭合。
拆分或转移需要两侧具有正体积、正质量与可表示的化学组成；低于编码分辨率时保留来源库存。
抽取回执从实际库存差额累计整数质量，体积仍按实际片段精确求和后输出。

无材料参数的体积抽取选择默认载体（handle 0），混合抽取使用独立的 `tryExtractExactMixture`。

## 功能配置

材料功能在启动时冻结。持久区域的载荷形状必须与当前功能配置完全一致；不一致时保留 checkpoint 并锁定，不自动增删材料状态。
