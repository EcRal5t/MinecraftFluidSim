# 工业展示素材

传感器、物料箱和手持物品采用统一的深色金属、青色仪表与黄铜细节。传感器正面为读数盘，背面朝向默认测点，侧面箭头指向背面；容器接口的侧面标记区分停止、进料和出料，进料指向内腔，出料指向外侧物料箱。

## 素材来源和加工

使用内置图像生成，提示词如下：

> Create a Minecraft mod pixel-art asset source sheet. Layout exactly 2 columns by 3 rows, each panel a separate square with generous margins and no text: top left industrial sensor face with cyan gauge and dark steel frame, top right metallic storage crate front with amber latch; middle left handheld cyan-tipped field probe, middle right metal bucket containing blue liquid; bottom left metal bucket with tan grains, bottom right compact gas canister with cyan pressure gauge. Uniform 16-bit pixel art, sharp square pixels, strong silhouettes, cohesive muted steel/cyan/brass palette, straight orthographic view, not 3D, no text, no shadows outside objects. Each panel separated by flat vivid magenta background.

原图保留在本地 `build/imagegen/source.png`。各面和图标裁出后移除洋红背景，以最近邻缩放至 32×32 像素；传感器背面、侧面、箱体顶底和接口标记按同一配色整理成像素贴图。物品图标保留透明底，方块表面使用不透明贴图。
