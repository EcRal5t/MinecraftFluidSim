# 流体展示参考

修改入口为 `FluidSnapshotPublisher`、`ClientRegionSnapshotStore`、`MaterialSurfaceGeometry` 和 `FluidRenderBackend`。
当前完整职责链与代码索引见[设计书](system-overview.md#client)，待实施要求集中在[实施计划](../roadmap/solver-consolidation-plan.md#客户端展示与测量)。
本文维护已落地的数据归属、当前渲染行为和适配所依据的外部接口。

## 输入与显色归属

服务端按已提交状态发布有界展示快照，客户端按区域身份、网络代次和序号接收。移除游标使旧消息失效。
液体统一使用材料快照，携带实际块成员；几何、化学、温度和颗粒显色共用该帧的紧凑格索引，客户端通过空间拓扑查找相邻格和世界坐标。
库存、物种、质量及内能由物理模块保存，展示量化和客户端颜色设置由展示链处理。
`CarrierInventoryReader.copyMaterialRenderSnapshot` 从单元库存、同代 PLIC 与 `FixedGeometrySnapshot` 生成 `FluidSurfaceInput`。每片隐含一个开放微盒，附带 PLIC 和材料高度裁剪平面；同单元材料按密度顺序分配实际库存份额，采用格内分层近似。共享面由客户端凸片求交确定，接触界面只保留一次，法向指向第二侧。正液量沿实际 PLIC 显示，开放微盒约束形状；极小相采用紧凑角部形状。仅接触裁剪面而无体积的片不参加网格、邻面遮挡或相机介质判定。

坐标以单元局部 double 平面传输，整数世界原点独立保存；每片最多三条裁剪平面，总片数上限 262144。材料几何与显色列合计上限 16 MiB，组合快照上限 32 MiB。完整快照按最多 512 KiB 分片发送，连接内连续组装，全部接齐后交给区域代次与序号规则；断线丢弃未完成组装。材料分区高度求解到约 6×10⁻⁸ 单元，显色份额采用 byte；库存数量保持原始表示。

几何与材料份额参加 `sameSurface`；相同几何复用网格，温度、pH 和本地模式变化只刷新颜色。更新帧采用已提交几何直接切换，表面及相机介质始终对应同一帧。
`MaterialFrame` 将已接受的材料几何、逐相 pH 和凝聚相温度关联，显色模式及温度范围由 `FluidDisplaySettings` 保存。
组分、pH、温度的数值定义见[化学与显色](chemistry-state.md#ph-与显色)，单位见设计书。

## 当前几何与绘制

`ClientFluidVolumeRenderer` 协调快照、几何生成与 GPU 提交；`MaterialSurfaceGeometry` 和 `GranularSurfaceGeometry` 提供液体与颗粒几何。
材料表面从已接受的 `FluidSurfaceInput` 重建，网格保留两侧材料、来源单元与朝向；相机位置决定当前可见一侧的材质。
生产表面使用 `BLOCK` 顶点格式，提交位置、颜色、方块图集 UV、实际世界光照和法线。水采用 `water_still`，汞采用 `iron_block`，其他材料采用 `white_concrete` 乘材料颜色。pH 与温度模式共用几何，通过位置／颜色管线保留色带，执行正常深度测试和深度写入。
材料表面为两侧分别生成朝向相反的顶点，保留对应材料与颜色，并启用背面剔除。相机穿过界面时由绘制管线选择可见一侧。材料的基础透明度读取 JSON `display_argb` 的 alpha 通道，光影包继续计算水的光学效果。材料与颗粒共用透明批次，所有四边形按相机距离排序；三角形通过重复末顶点编码。
绘制注册于 `BEFORE_TRANSLUCENT_TERRAIN`，在不透明深度已准备的透明目标中提交液体，使近侧玻璃随后混合到液面上。与原版透明区块的跨批次排序仍采用现有近似。
`FluidRenderBatch` 分别持有主画面和阴影的顶点、索引与排序中心。几何、显色、材质、相关方块光照、图集或光影状态改变时重新上传顶点；相机移动时只重排索引。顶点与 Iris 方块中心共同使用区段对齐的相机锚点，跨锚点时重建，矩阵与排序读取同一坐标约定。区域移除、断线和资源重载使缓存失效，客户端关闭释放 GPU 与本地缓冲区。
颗粒保留沉积层外露面与活动代表粒子的方盒形状，沿材质管线提交图集纹理、实际世界光照与逐面法线；Iris 读取沙方块的分类。固定方向明暗已由正常照明替换。调试外壳沿 `renderDebug` 使用 `DEBUG_FILLED_BOX`。

## Iris 26.2 接口依据

当前展示管线以 Iris 1.11.2 和 Sodium 0.9.1（对应 Minecraft 26.2）为基准实现。
Iris 公开 API `IrisApi.assignPipeline(RenderPipeline, IrisProgram)` 将 `IrisProgram.TRANSLUCENT` 经 `ProgramId.fromAPI` 映射到 `gbuffers_water`，匹配时需核对管线顶点格式；诊断管线通过 `assignPipeline(..., BASIC)` 注册。

Iris 属于可选运行时依赖，主展示管线不直接引用其编译期类。阴影阶段集成与材质上下文注入依赖 Iris 的未公开内部接口，模组将这些接口访问与版本适配逻辑集中封装在两处：
1. **反射桥接（`IrisFluidCompatibility`）**：负责动态探测与调用 Iris 内部接口。未安装 Iris 时平稳回退至原生透明渲染；若内部接口不匹配，抛出包含具体调用位置与异常原因的诊断信息。
   - 管线程序映射：调用 `IrisPipelines.copyPipeline(TRANSLUCENT_TERRAIN, materialPipeline)`，复制主画面与阴影程序映射，使水与其他材料沿 `gbuffers_water`／`shadow_water` 材质分支执行；
   - 材质映射查询：从 `WorldRenderingSettings.getBlockStateIds()` 动态读取当前光影包的方块状态映射。水使用原版水状态，汞使用铁块状态，油及其他材料使用白色染色玻璃状态；表面颜色与纹理仍由各自材料决定。光影切换后下一次绘制重新读取；
   - 顶点材质上下文：通过 `BlockSensitiveBufferBuilder` 顶点缓冲扩展接口，在每个表面提交前调用 `beginBlock` 注入材质 ID 与方块中心属性，供 Iris 自动扩展材质、纹理中心、切线与法线数据。
2. **阴影阶段切面注入（`IrisFluidShadowMixin`）**：通过 `@Pseudo` Mixin 切入 Iris 内部阴影渲染器（`ShadowRenderer.renderShadows`），在透明区块组绘制完毕后，使用相同的流体材料网格与主相机坐标提交顶点。Iris 的 `MixinGlCommandEncoder` 在阴影阶段维持阴影视口和帧缓冲区，实际目标由阴影程序接管，后端通过标准 Blaze3D 渲染通道完成提交。

材料与颗粒顶点、方块中心坐标共同减去相机的区段锚点，模型矩阵补偿剩余相机偏移；主画面、阴影和透明排序共用这一坐标约定。
`CommonUniforms.isEyeInWater` 从相机 `getFluidInCamera()` 读取浸没状态；动态液面绘制与相机介质查询共同接入。
`FluidCameraMixin` 通过已接受几何的 `materialAt` 查询，将水内相机设为 `FogType.WATER`，同时供原版雾效与 Iris 使用。原版熔岩、细雪及水的判定优先；油、汞当前采用空气介质信号和各自表面外观。

## 光影包的几何与材质假设

主要接入对象为 Photon v1.4 与 Complementary Unbound r5.9.3。静态检查发现两包均使用水材质分类、纹理与光照坐标、法线及切线信息，并提供额外水面顶点位移。
保留模拟液面位置时，Photon 在 Water → Water Waves 中关闭 `WATER_DISPLACEMENT`，保留 `WATER_WAVES` 提供法线波纹；Complementary 在 Water Settings 或 Waving Settings 中关闭 `WAVING_WATER_VERTEX`（Waving Water Height），法线波纹沿包的 Water Wave 设置保留。
Complementary 的附加高度位移按 `fract(worldPos.y + 0.005)` 判断顶点，任意 PLIC 斜面和格内液滴均可能触发。上述设置使主画面与阴影使用同一模拟几何，也使水内相机判定与可见液面对应。

水材质可重新计算顶点颜色、透明度和吸收；pH／温度改走基础颜色程序，色带经包的后处理和曝光输出。汞以铁材质分类、油以染色玻璃分类使用包现有能力，具体金属感和透射效果依包设置决定。
两包的相关透明流程用屏幕前后深度估计厚度，多层独立液体的逐段传播信息超出该深度表示。
阶段 3 的光学近似范围见[产品路线](../roadmap/product-roadmap.md#阶段-3展示几何材质与光影接入)，实际设置和观察结果见验证指南。

本地源码定位如下；编号用于解释当前源码，运行适配读取 Iris 映射。

| 对象 | 相对其 `shaders/` 的入口 |
|---|---|
| Photon v1.4 | `block.properties` 的水分类；`include/vertex/utility.glsl` 的材料与切线；`include/vertex/displacement.glsl` 的顶点波动 |
| Photon v1.4 | `program/gbuffers_all_translucent.fsh` 的材质、法线与深度；`program/c1_blend_layers.fsh` 的透明合成及水下效果 |
| Complementary Unbound r5.9.3 | `block.properties` 的水分类；`program/gbuffers_water.glsl` 的属性与材质；`lib/materials/materialMethods/wavingBlocks.glsl` 的附加位移 |

## 验证入口

协议与几何沿 `protocol,materials`，离线展示成本沿 `presentation`；画面、光影生命周期和实际兼容组合见[展示验证](../maintenance/validation.md#fluid-presentation)。
真实光影渲染效果与性能开销见[展示验证](../maintenance/validation.md#fluid-presentation)。
运行时每十秒记录主画面与阴影绘制次数、顶点重建与索引排序次数、构建／排序／上传／提交累计 CPU 耗时和上传字节数。`FluidRenderTiming` 使用设备时间戳异步读取主画面与阴影的 GPU 绘制均值、最大值和样本数；未完成的查询保留到后续帧，满环时跳过采样。
