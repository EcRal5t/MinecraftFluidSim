# 工业展示素材

传感器、物料箱和手持物品采用统一的深色金属、青色仪表与黄铜细节。传感器正面为读数盘，背面朝向默认测点，侧面箭头指向背面；容器接口的侧面标记区分停止、进料和出料，进料指向内腔，出料指向外侧物料箱。

## 素材来源和加工

绝热块使用浅色陶瓷板、深钢边框和青色密封条，保留板材分缝。绝热玻璃使用两像素宽的深钢／青色框及黄铜角标，中心 28×28 像素完全透明。有限换热器的南北换热面使用铜管散热纹理，其他面复用现有金属盖板；机械驱动器使用齿轮轴芯，恒温源使用陶瓷芯与双色温度标识。这些纹理均为 32×32 RGBA，方块与物品共用模型引用。

原版金属、木材、石材等继续使用原版外观，新增导热数据无需替换它们的材质。阀门与筛网已有表达状态的独立几何，本轮保持现有金属表面；新增设备确定形状与交互方向后再补专用纹理。

使用内置图像生成，提示词如下：

> Create a Minecraft mod pixel-art asset source sheet. Layout exactly 2 columns by 3 rows, each panel a separate square with generous margins and no text: top left industrial sensor face with cyan gauge and dark steel frame, top right metallic storage crate front with amber latch; middle left handheld cyan-tipped field probe, middle right metal bucket containing blue liquid; bottom left metal bucket with tan grains, bottom right compact gas canister with cyan pressure gauge. Uniform 16-bit pixel art, sharp square pixels, strong silhouettes, cohesive muted steel/cyan/brass palette, straight orthographic view, not 3D, no text, no shadows outside objects. Each panel separated by flat vivid magenta background.

原图保留在本地 `build/imagegen/source.png`。各面和图标裁出后移除洋红背景，以最近邻缩放至 32×32 像素；传感器背面、侧面、箱体顶底和接口标记按同一配色整理成像素贴图。物品图标保留透明底，方块表面使用不透明贴图。

## 绝热玻璃连接纹理

MMM 在客户端通过 Fabric `ModelLoadingPlugin` 和 `QuadEmitter` 实现连接纹理，无需安装 Continuity，无新增依赖或 mixin。方块使用原版 `TransparentBlock`，保留完整碰撞立方体和热场形状，允许天光通过并消隐相邻同类玻璃的内部面。模型按每个面的邻块选择四个象限，支持横向、纵向、整片窗口及缺失对角块的凹角。

采用五张紧凑 CTM 图，路径为 `fabric/src/main/resources/assets/mmm/textures/block/insulating_glass_ctm_<n>.png`：

| n | 图案 | 用途 |
|---|---|---|
| 0 | 四边框与角标 | 独立玻璃、物品与破坏粒子 |
| 1 | 全透明 | 两邻边与对角均连接 |
| 2 | 左右框 | 上下连接 |
| 3 | 上下框 | 左右连接 |
| 4 | 四个内角 | 两邻边连接、对角缺失 |

边框宽度统一为 2 像素，各象限沿原 UV 取样。只连接同种绝热玻璃，位于邻块前方的绝热玻璃会保留转折轮廓；与原版玻璃相接仍保留材料边界。图集随资源重载更新。绝热板和设备外壳的接缝有实体含义，保留各自面板边框。

参考本地 Continuity `origin/26.2/dev`（`d15d4421ace78f3b5ba65f46ce2764ebfeda5c9e`）的模型接入和紧凑连接纹理思路，MMM 仅实现自身玻璃需要的模型。Continuity 的资源包规则仍由 Continuity 管理。

## 新增材质生成提示词

使用内置 imagegen 分别生成五张正交像素原图，原图在本地 `build/imagegen/industrial/*-source.png`；交付贴图在 `fabric/src/main/resources/assets/mmm/textures/block/`。按最近邻采样到 32×32，绝热材料保留两像素边框；玻璃的 alpha 保留生成结果。连接变体由同一张玻璃的边条和角区提取，保证配色、线宽一致，中心保持完全透明。

### insulation

> Use case: stylized-concept. Asset type: Minecraft industrial mod square block texture. Generate ONE flat orthographic, edge-to-edge seamless insulation board surface, no scene or perspective. Pixel art on an exact 32 by 32 conceptual pixel grid, enlarged with nearest-neighbor hard square pixels. Pale cool ceramic insulation, muted dark blue-gray steel edging, restrained cyan seal stripe and tiny brass fasteners matching industrial instruments. A broad calm ceramic center with sparse square flecks, no wool fibers. Thin straight frame exactly at outer edges, symmetrical, no text, no gradients, no shadows outside, no watermark. Opaque entire square canvas. Should read as engineered thermal insulation when tiled.

### insulating_glass

> Use case: stylized-concept. Asset type: Minecraft industrial insulating glass texture. ONE flat orthographic square glass face. Exact conceptual 32 by 32 pixel grid enlarged with hard nearest-neighbor pixels. Ultra-thin one-pixel dark blue-gray steel outer rim and one-pixel pale cyan inner gasket, tiny muted brass corner fasteners. The entire large central opening must be fully transparent with zero haze, zero frosted fill, zero diagonal glare, zero checkerboard painted into image. Transparent background is actual alpha. Frame reaches exact four image edges, symmetrical, no margin, no text, no gradients, no 3D, no scene, no watermark. Match dark steel/cyan/brass Minecraft industrial instruments. At least 75 percent of image area fully transparent, suitable for seeing clearly through a large window.

### finite_heat_exchanger

> Use case: stylized-concept. Asset type: ONE Minecraft industrial mod block face texture. a finite heat exchanger face: large recessed copper serpentine tube with cyan cold channel and muted orange warm channel, symmetrical dark steel casing, clearly readable open horizontal cooling fins, tiny brass fasteners. Flat orthographic square face fills entire canvas edge-to-edge, no margins, no perspective, no external shadow. Strict pixel art designed as a 32x32 pixel sprite enlarged with nearest-neighbor hard square pixels; very simple bold shapes readable at 32 pixels. Cohesive muted dark blue-gray steel, cyan instruments and aged brass palette. Opaque full square background. No text, no numbers, no logos, no smooth gradients, no extra panels or scenes.

### mechanical_driver

> Use case: stylized-concept. Asset type: ONE Minecraft industrial mod block face texture. a mechanical driver face: centered steel shaft hub with four spokes inside a brass toothed gear, cyan thin ring accents, dark steel square casing, functional mechanical industrial motor rather than a Minecraft piston. Flat orthographic square face fills entire canvas edge-to-edge, no margins, no perspective, no external shadow. Strict pixel art designed as a 32x32 pixel sprite enlarged with nearest-neighbor hard square pixels; very simple bold shapes readable at 32 pixels. Cohesive muted dark blue-gray steel, cyan instruments and aged brass palette. Opaque full square background. No text, no numbers, no logos, no smooth gradients, no extra panels or scenes.

### thermal_reservoir

> Use case: stylized-concept. Asset type: ONE Minecraft industrial mod block face texture. a thermal reservoir face: broad sealed pale ceramic square core surrounded by dark steel casing, centered small split cyan and muted orange temperature indicator, copper heat transfer coils at two sides, tiny brass fasteners, no target bullseye. Flat orthographic square face fills entire canvas edge-to-edge, no margins, no perspective, no external shadow. Strict pixel art designed as a 32x32 pixel sprite enlarged with nearest-neighbor hard square pixels; very simple bold shapes readable at 32 pixels. Cohesive muted dark blue-gray steel, cyan instruments and aged brass palette. Opaque full square background. No text, no numbers, no logos, no smooth gradients, no extra panels or scenes.

验证入口：`:fabric:runHostValidation -PhostValidation=protocol` 包含玻璃邻域、六面象限方向、同类面消隐、实体碰撞、天光与视觉形状检查；`:fabric:assemble verifyArchitecture` 检查客户端构建、资源打包和架构边界。

## 物理管件

金属管、绝热管、玻璃管、绝热玻璃管、管阀和管筛沿用现有工业配色。外宽为 0.625 m，内径为 0.5 m，壁厚为 0.0625 m，模型与碰撞均按 1/16 方块网格生成。四种普通管使用主体、六面支管、端盖和收回面组合，729 种端口几何对应共享形状；阀门以独立闸板表现关闭、半开、全开，筛膜限于中央管口。两种玻璃管保留透明视野，客户端使用接受后的内腔形状裁切实际液层和床体。四材质模型、碰撞、配方、掉落与解锁一致性纳入 `shape` 验证。
