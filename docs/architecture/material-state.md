# 材料状态契约

修改材料注册、质量、混合物或液液界面时读本文。单位与整数编码统一见[设计书](system-overview.md#units)。

## 修改入口

| 职责 | 代码入口 | 测试选择器 |
|---|---|---|
| 材料定义与单位 | [FluidMaterialRegistry](../../model/src/main/java/mmm/fluid/FluidMaterialRegistry.java)、[WorldMaterialDataPackLoader](../../fabric/src/main/java/mmm/simulation/WorldMaterialDataPackLoader.java)、[FluidDefinitions](../../solver/src/main/java/mmm/fluid/FluidDefinitions.java)、[SolverUnitScale](../../model/src/main/java/mmm/units/SolverUnitScale.java) | `materials` |
| 注入、抽取与容器表示 | [MaterialTransferPlanner](../../solver/src/main/java/mmm/fluid/MaterialTransferPlanner.java)、[FluidMixture](../../model/src/main/java/mmm/transfer/FluidMixture.java) | `materials,containers` |
| 材料界面与表面张力 | [MaterialSurfaceGeometry](../../fabric/src/client/java/mmm/client/MaterialSurfaceGeometry.java)、[SurfaceTensionFields](../../solver/src/main/java/mmm/fluid/SurfaceTensionFields.java) | `materials,surface-tension` |
| 定义持久化 | [MaterialRegistryCodec](../../fabric/src/main/java/mmm/fluid/MaterialRegistryCodec.java) | `region-state` |

执行方式见[验证指南](../maintenance/validation.md#host)。

## 材料定义

默认数据包在 `data/mmm/mmm/fluid_materials` 提供 water、oil 与 mercury，`fluid_interfaces` 声明两个互溶组之间的液液张力。每份材料定义包含：

- stable ID、参考密度（kg/m³）、互溶组、液气表面张力、接触角、显示底色；
- 分段动态黏度 `mu(T)`（Pa·s）与有效温区，是液体黏度的唯一定义；颗粒载体只声明阻力标定的 Reynolds 上限；
- 可选体膨胀定义：载体 ID、参考温度、体膨胀系数，热学能力要求支持的液体提供该定义。

全部字段进入材料指纹与世界能力包，材料单位身份为 `mmm:material_si`。定义按声明顺序建立稠密 material handle，互溶组映射为 phase handle：同组材料不生成内部液液界面，不同组材料保持相分区。handle 只在进程内用作数组索引；checkpoint 保存规范材料 palette，容器保存 stable ID，恢复时先核对 palette 与 registry 身份再建立 handle。

`FluidDomainState` 携带完整不可变世界定义，布局保存其指纹；宿主状态函数与 GPU 材料表从同一份数据生成。新建纯水、原版桶转换、容器回执与恢复统一读取世界定义中的水密度。

### 黏度求值

分段 `mu(T)` 在连接点连续，域内线性插值：

| 方式 | 声明温区外 | 用途 |
|---|---|---|
| `dynamicViscosityAt` | 抛出异常 | 注册表校验、测试断言 |
| `dynamicViscosityExtendingAt` | 取最近端点值 | 载体耦合，覆盖热学可达的全部温度 |

## 库存与密度

`LiquidCellInventory.Stock` 按单元、材料与固定开放分量保存参考体积（`SignedDyadic`）、整数质量、组成与内能，区域与逐材料账本由单元库存精确求和。实际质量与体积参与结算，参考密度用于创建、校验与状态函数绑定。格内密度：

```text
mass_kg = massUnits / Mass.UNITS_PER_KILOGRAM
rho_liquid = sum(mass_kg) / actual_liquid_volume_m3
```

溶质改变实际质量与体积后，密度由同一库存求值；盐水黏度与水—乙醇组成物性采用声明范围内的混合近似，见[化学状态](chemistry-state.md)。热浮力适用范围见[热浮力](thermal-state.md#热浮力)。

## 求解约定

- 同一互溶组的材料共享物相与速度场，各材料保留独立单元库存，实际体积决定格内比例。
- 不可互溶材料共享 MAC 压力/速度框架，保留独立相身份与液液界面。
- 共享通量联合搬运体积、整数质量、组成与内能，实际质量通量同时推进 MAC 动量。
- 单一纯水域走同一机械路径，不引入额外材料源汇。
- 平滑相分数、PLIC、曲率与客户端 mesh 都从实际单元库存派生。

## 注入与抽取

注液计划从逐格开放容量的精确剩余中扣除，多材料共同抵扣同一份剩余空间，跨格注入总量不超过请求；接口处的浮点观察量向下舍入，回执只舍入一次。混合物按体积份额分配整数质量并保留守恒余数；显式化学注入同时分配整数物种，质量余量逐组闭合。拆分与转移要求两侧具有正体积、正质量与可表示组成，低于编码分辨率时保留来源库存。

`setVolumes(float[])` 在一次事务中替换整域目标体积（按 `SpatialGrid` 紧凑索引排列），旧库存记外部汇，新库存按目标材料密度、默认组成与注入温度记外部源；MAC 按实际质量变化移走对应动量，混合动能损失记为局部热。原版水采集与各演示场景都经过这一入口。

## 容器表示

`FluidMixture` 保存容器内至多 64 个按 stable ID 排序的组分，每项包含 `volumeNanos` 与 `massUnits` 整数，总量不超过一桶。单材料物品可用紧凑表示，进入 WAL 时规范化为完整 mixture。抽取先预检物品能否表示实际体积、逐材料质量、组成与内能，WAL 记录实际转移量。tooltip 与耐久条只读。

## 表面与展示

外表面使用总液体占据，液液界面使用逐相分数，两者分别裁剪。每对物相只生成一份共享 mesh，互溶材料不生成内部面。材料底色随数据包与能力包保存，最终颜色由客户端选择的组分、pH 或温度模式决定。

## 功能配置

材料功能在启动时确定，持久区域的载荷形状必须与当前功能配置一致，不一致时保留 checkpoint 并锁定。
