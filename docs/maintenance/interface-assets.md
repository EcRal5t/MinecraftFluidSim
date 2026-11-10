# 工业界面与诊断显示

## 视觉资源

界面采用深钢底色、低饱和青色读数、铜色边条；琥珀色用于提醒，红色用于故障。正文、数字、单位与曲线由游戏实时绘制。装饰不嵌入说明文字，重要操作保留可朗读标签。

三张源图通过内置 imagegen 生成，实际 alpha 保留，按最近邻缩小为游戏资源。图标先沿透明间隔逐个裁出，再按最长边 26 像素居中放入 32 像素单格，四周至少保留 3 像素透明边距：

| 资源 | 尺寸 | 用途 |
|---|---|---|
| `assets/mmm/icon.png` | 128×128 | 模组列表图标，64 像素母版整数放大 |
| `textures/gui/brand.png` | 128×128 | 星夜、轨道与工业结构的独立徽标 |
| `textures/gui/wordmark.png` | 192×64 | 徽标与 MMM 字标 |
| `textures/gui/industrial_atlas.png` | 128×128 | 4×4 图集，单格 32 像素 |

路径相对 `fabric/src/main/resources/assets/mmm/`，模组图标一行除外。本地生成原图保存在 `build/imagegen/interface/`；运行只依赖已提交资源。图集顺序与 `IndustrialUi.Icon` 一致：

| | | | |
|---|---|---|---|
| 温度 | 压力 | 流量 | 液体 |
| 气体 | 固体 | 取样 | 阀门 |
| 运行 | 暂停 | 告警 | 故障 |
| 曲线 | 设置 | 能量 | 日志 |

面板、按钮状态、页签和滚动条使用整数像素绘制，以适应不同 GUI 缩放。共享绘制入口为 `IndustrialUi`。控制器的游玩概览、技术详情、样品对照、显示设置和工艺曲线共用这套样式；各屏幕保持原有请求代次及玩家本地状态。样品相态和成分条直接使用服务端有类型的数据；相应请求及响应采用 v2 通道，由通道支持检查决定是否发送。

## 调试 HUD

调试面板是独立的屏幕空间 HUD。默认常驻右上角，简洁／展开两种状态都不接管鼠标，不打开 Screen，也不暂停游戏。

| 默认按键 | 操作 |
|---|---|
| F8 | 显示／隐藏 |
| F9 | 简洁／展开 |
| Page Up／Page Down | 展开时查看较早／较新的诊断输出 |

按键可在原版控制设置中修改。显隐与展开状态保存在 `config/mmm-debug-display.properties`。菜单打开时不覆盖菜单，随原版隐藏 HUD 行为消隐。

客户端 FPS、帧耗时和堆内存每 10 tick 更新。服务器选中区域的求解读数每 10 tick 定向发送，作为同一条快照替换；超过两秒未更新的读数标记过期。专服数值诊断仅发送给同维度、范围内的管理员；单人世界主人在未开启作弊时也可接收。调试信息不作为游玩仪表盘的数据来源。

MMM 命令输出进入事件记录；过长输出分块，连续重复记录合并，事件历史有界。跨维度、断线及连接配置阶段清理客户端记录，数据包携带维度以拒收旧维度内容。完整命令记录仍写入 `logs/mmm-commands.log`，内部错误和堆栈继续由原文件日志保存。

HUD 的文本换行和分页布局缓存半秒，窗口尺寸、展开状态或翻页变化立即更新。无逐帧遍历服务器求解状态，无全局日志拦截。

## 验证

`:fabric:runHostValidation -PhostValidation=protocol,audit,lifecycle` 覆盖协议往返与分块、受众规则、诊断缓存上限／覆盖／重复合并／过期、窄视口分页，以及既有样品、曲线、显示和生命周期请求。`:fabric:assemble :fabric:compileGametestJava :fabric:runArchitectureValidation` 检查客户端／专服编译边界、资源打包和模块结构。

## 生成提示词

### 独立徽标

> Use case: logo-brand. Asset type: final transparent pixel-art emblem for Minecraft industrial science mod 'Matter, Motion & Mechanics' (MMM), mod-list icon and game GUI. Primary request: romantic starry exploration meets humble industrial machinery. ONE compact centered emblem: a broad copper orbital ring around a deep midnight-blue miniature planet, a small warm ivory four-point guiding star above, a thin cyan light path through the center. Subtle dark-steel mechanical brackets make the orbit feel engineered, but poetic, quiet, hopeful, not military or corporate. Strong simple silhouette readable at 32px, designed on a precise 64x64 pixel grid enlarged with nearest-neighbor square pixels. Restrained palette of midnight steel #141f29, slate #344955, oxidized copper #bd8c5b, muted cyan #6fc8cc, ivory #e1ddc7. Flat orthographic sprite, 2-3 shade ramps only, no smooth gradients, no blur, no tiny noise, no 3D render. Transparent actual alpha around the emblem; fully isolated, balanced square composition, generous 6% transparent padding. No lettering, no words, no border around canvas, no mockup, no watermark. This will be paired with a separate precise MMM pixel wordmark by the application.

### 工业图标

> Use case: stylized-concept. Asset type: production pixel-art industrial GUI icon sprite sheet for Minecraft mod. Exactly 4 columns and 4 rows of separate icons, 16 total, evenly spaced, each centered within one equal square cell, all on ACTUAL TRANSPARENT ALPHA. No grid lines or labels. Canvas square. Keep every glyph within inner 65% of its cell with generous clear padding. Designed at conceptual 128x128 pixels (each icon tile 32x32), enlarged nearest-neighbor: simple bold 1-2 pixel strokes, clean hard square pixels. Row 1 from left: thermometer with amber bulb; circular cyan pressure dial; cyan rightward flow arrow with three stream lines; blue liquid droplet. Row 2: pale cyan gas puff of three squares; ochre pile of solid grains; glass sample vial with cyan contents; copper pipe handwheel valve. Row 3: cyan play triangle; copper pause two bars; amber warning triangle with exclamation; red octagonal fault stop with exclamation. Row 4: cyan rising line chart with axes; steel settings cog with copper hub; ivory lightning bolt; cyan three-line log/document icon. Cohesive palette deep steel outline #1c2c37, subdued cyan #6fc8cc, copper #bd8c5b, ivory #e1ddc7, amber #d6a34e, red #d66c61. Flat orthographic small game sprites, clear distinct silhouettes, no words, no typography besides the two exclamation marks, no decorative backgrounds, no panels, no perspective, no blur, no smooth gradients, no outer shadows. Every icon should be readable at 16 to 24 screen pixels. Uniform size and shared industrial visual language.

### MMM 字标

以独立徽标原图为身份参考。

> Use case: logo-brand. Create a transparent horizontal pixel-art logo lockup using the attached orbital planet/star emblem as the exact identity reference. Left 35%: preserve the compact copper orbit, cyan trajectory, midnight planet, steel mechanism and ivory north star, simplify only enough to align cleanly to square pixels. Right 60%: large highly legible custom pixel letters exactly 'MMM' (three capital M letters, M M M), ivory faces with dark-steel one-pixel outline and restrained copper lower edges. Leave a calm gap between emblem and letters, centered vertically, generous transparent padding. Wide composition roughly 3:1 aspect ratio. Strict game pixel art, like a 192x64 pixel design enlarged nearest-neighbor; flat orthographic, hard square pixels, simple readable silhouette. Actual alpha transparency. No additional text, no subtitle, no landscape, no vignette, no blur, no gradients, no mockup or watermark. Romantic scientific exploration, matching the attached mark.
