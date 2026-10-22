# 流体展示参考

修改入口为 `FluidSnapshotPublisher`、`ClientRegionSnapshotStore`、`MaterialSurfaceGeometry` 和 `FluidRenderBackend`。
当前完整职责链与代码索引见[设计书](system-overview.md#client)，待实施要求集中在[实施计划](../roadmap/solver-consolidation-plan.md#下一轮交付d01-展示几何输入)。
本文维护已落地的数据归属、当前渲染行为和适配所依据的外部接口；D01–D03 实现后随代码更新相应契约。

## 输入与显色归属

服务端按已提交状态发布有界展示快照，客户端按区域身份、网络代次和序号接收。移除游标使旧消息失效。
库存、物种、质量及内能由物理模块保存，展示量化和客户端颜色设置由展示链处理。
`MaterialFrame` 将已接受的材料几何、逐相 pH 和凝聚相温度关联，显色模式及温度范围由 `FluidDisplaySettings` 保存。
组分、pH、温度的数值定义见[化学与显色](chemistry-state.md#ph-与显色)，单位见设计书。

## 当前几何与绘制

`ClientFluidVolumeRenderer` 协调快照、几何生成与 GPU 提交；`FluidSurfaceGeometry`、`MaterialSurfaceGeometry` 和 `GranularSurfaceGeometry` 提供各类几何。
当前生产表面使用 `FluidRenderBackend` 的 `DEBUG_FILLED_BOX` 管线，顶点提交位置和颜色；材料表面按朝向调整明暗，使用固定透明度。
绘制注册于 `AFTER_TRANSLUCENT_TERRAIN`，后端创建渲染 pass 并提交到主画面目标。
现有输出和旧几何启发式的替换由 D01–D03 组织，完成后在本节登记实际网格、材质、透明与资源生命周期契约。

## Iris 26.2 接口依据

本地 `Iris` 仓库的 `origin/26.2` 提供 `IrisApi.assignPipeline(RenderPipeline, IrisProgram)`。
`IrisProgram.TRANSLUCENT` 经 `ProgramId.fromAPI` 映射到 `gbuffers_water`；匹配还涉及管线顶点格式。
当前分支的公开 API 未提供阴影注册和阴影回调方法，阴影绘制需沿实际内部渲染流程适配。
版本专用调用应集中到客户端兼容实现，具体类和注入位置随实现登记。

`WorldRenderingSettings.getBlockStateIds()` 提供当前包的方块状态分类映射，`BlockSensitiveBufferBuilder` 提供顶点材料上下文。
这些属于内部接口。不同包使用不同水编号，适配读取当前加载映射，并在光影切换后更新对应资源和分类。
`CommonUniforms.isEyeInWater` 从相机 `getFluidInCamera()` 读取浸没状态；动态液面绘制与相机介质查询需要共同接入。
水下分类是全局介质信号，油、汞的外观及浸没处理须沿各自材料能力明确表达。

## 光影包的几何与材质假设

主要接入对象为 Photon v1.4 与 Complementary Unbound r5.9.3。静态检查发现两包均使用水材质分类、纹理与光照坐标、法线及切线信息，并提供额外水面顶点位移。
Complementary 的部分波动判断依赖方块内高度。模拟表面位置与光影附加位移的处理要求集中在 D02。

水材质可能重新计算顶点颜色、透明度和吸收，pH／温度的色带可读性需要专门着色策略。
两包的相关透明流程用屏幕前后深度估计厚度，多层独立液体的逐段传播信息超出该深度表示。
阶段 3 的光学近似范围见[产品路线](../roadmap/product-roadmap.md#阶段-3展示几何契约与重建)，实际设置和观察结果见验证指南。

本地源码定位如下；编号用于解释当前源码，运行适配读取 Iris 映射。

| 对象 | 相对其 `shaders/` 的入口 |
|---|---|
| Photon v1.4 | `block.properties` 的水分类；`include/vertex/utility.glsl` 的材料与切线；`include/vertex/displacement.glsl` 的顶点波动 |
| Photon v1.4 | `program/gbuffers_all_translucent.fsh` 的材质、法线与深度；`program/c1_blend_layers.fsh` 的透明合成及水下效果 |
| Complementary Unbound r5.9.3 | `block.properties` 的水分类；`program/gbuffers_water.glsl` 的属性与材质；`lib/materials/materialMethods/wavingBlocks.glsl` 的附加位移 |

## 验证入口

协议与几何沿 `protocol,materials`，离线展示成本沿 `presentation`；画面、光影生命周期和实际兼容组合见[展示验证](../maintenance/validation.md#fluid-presentation)。
源码接口检查只能说明接入依据，游戏加载、渲染效果及成本分别记录实际证据。
