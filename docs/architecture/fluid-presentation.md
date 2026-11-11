# 流体展示参考

修改客户端液体与颗粒展示、显色或光影适配时读本文。完整职责链见[设计书](system-overview.md#client)，待实施要求见[实施计划](../roadmap/solver-consolidation-plan.md#客户端展示与测量)。

## 修改入口

| 职责 | 代码入口 | 测试选择器 |
|---|---|---|
| 快照发布与接收 | [FluidSnapshotPublisher](../../fabric/src/main/java/mmm/simulation/FluidSnapshotPublisher.java)、[ClientRegionSnapshotStore](../../fabric/src/client/java/mmm/client/ClientRegionSnapshotStore.java) | `protocol` |
| 表面输入 | [CarrierInventoryReader](../../solver/src/main/java/mmm/fluid/CarrierInventoryReader.java) | `materials` |
| 液体与颗粒几何 | [MaterialSurfaceGeometry](../../fabric/src/client/java/mmm/client/MaterialSurfaceGeometry.java)、[GranularSurfaceGeometry](../../fabric/src/client/java/mmm/client/GranularSurfaceGeometry.java) | `materials` |
| 绘制与批次 | [ClientFluidVolumeRenderer](../../fabric/src/client/java/mmm/client/ClientFluidVolumeRenderer.java)、[FluidRenderBackend](../../fabric/src/client/java/mmm/client/FluidRenderBackend.java)、[FluidRenderBatch](../../fabric/src/client/java/mmm/client/FluidRenderBatch.java) | `presentation` |
| 显色设置 | [FluidDisplaySettings](../../fabric/src/client/java/mmm/client/FluidDisplaySettings.java) | `materials` |
| Iris 适配与相机介质 | [IrisFluidCompatibility](../../fabric/src/client/java/mmm/client/IrisFluidCompatibility.java)、[IrisFluidShadowMixin](../../fabric/src/client/java/mmm/mixin/client/IrisFluidShadowMixin.java)、[FluidCameraMixin](../../fabric/src/client/java/mmm/mixin/client/FluidCameraMixin.java) | 游戏内检查 |
| 绘制耗时 | [FluidRenderTiming](../../fabric/src/client/java/mmm/client/FluidRenderTiming.java) | `presentation` |

画面、光影组合与性能证据见[展示验证](../maintenance/validation.md#fluid-presentation)。

## 快照与显色归属

服务端从已提交状态发布有界展示快照，客户端按区域身份、网络代次与序号接收，过期消息作废。库存、物种、质量与内能留在物理模块，客户端只接收量化后的几何与显色数据。

表面输入由单元库存、同代 PLIC 与固定几何派生：每片对应一个开放微盒，附带 PLIC 与材料分层裁剪平面，同格多材料按密度顺序分层。相邻片的共享面只保留一次。几何、化学、温度和颗粒显色共用同一帧的紧凑格索引。

几何不变时复用网格，温度、pH 与显色模式变化只刷新颜色；表面与相机介质始终对应同一帧。组分、pH、温度的数值定义见[pH 与显色](chemistry-state.md#ph-与显色)。

## 绘制

材料表面使用 `BLOCK` 顶点格式，提交方块图集 UV、世界光照与法线，参与正常深度测试与写入。水取水的静止纹理，汞取铁块纹理，其他材料取白色混凝土纹理乘材料底色；基础透明度来自材料 `display_argb` 的 alpha。界面两侧各生成一份朝向相反的面并启用背面剔除，相机所在一侧决定可见材质。

液体与颗粒在 `BEFORE_TRANSLUCENT_TERRAIN` 阶段进入同一透明批次，按相机距离排序，近侧玻璃随后混合到液面上。与原版透明区块的跨批次排序为近似。颗粒绘制沉积层外露面与活动代表粒子的方盒。

## Iris 适配

基准版本为 Iris 1.11.2 与 Sodium 0.9.1。Iris 是可选依赖，未安装时走原生透明渲染。

- 公开 API `IrisApi.assignPipeline(RenderPipeline, IrisProgram)` 把材料管线登记为 `IrisProgram.TRANSLUCENT`，对应光影包的 `gbuffers_water`。
- 阴影阶段、方块状态映射与顶点材质上下文依赖 Iris 内部接口，全部集中在 `IrisFluidCompatibility` 与 `IrisFluidShadowMixin`；接口不匹配时报告调用位置与原因。
- 光影包按方块状态识别材质：水用原版水，汞用铁块，油与其他材料用白色染色玻璃；颜色与纹理仍由材料决定。
- `FluidCameraMixin` 用已接受几何判断相机是否在水中，设为 `FogType.WATER`，原版雾效与 Iris 的 `isEyeInWater` 共用该结果。油和汞当前按空气介质处理。

## 光影包设置

主要适配 Photon 与 Complementary Unbound。两包都对水面做额外顶点位移，会让可见液面偏离模拟液面，也会打乱阴影与水内相机判定。使用时关闭顶点位移、保留法线波纹：

| 光影包 | 关闭 | 保留 |
|---|---|---|
| Photon | Water → Water Waves 中的 `WATER_DISPLACEMENT` | `WATER_WAVES` |
| Complementary Unbound | Water / Waving Settings 中的 `WAVING_WATER_VERTEX`（Waving Water Height） | 包自身的 Water Wave 设置 |

水材质由光影包重新计算颜色、透明度与吸收，pH 与温度模式经基础颜色程序输出色带。汞与油沿用铁块与染色玻璃在包内的效果。两包用屏幕前后深度估计透明厚度，多层独立液体按单层近似。光学近似范围见[产品路线](../roadmap/product-roadmap.md#阶段-3展示几何材质与光影接入)。
