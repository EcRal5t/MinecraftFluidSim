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

面板、按钮状态、页签和滚动条使用整数像素绘制，以适应不同 GUI 缩放。共享绘制入口为 `IndustrialUi`。控制器的游玩概览、技术详情、样品对照、显示设置和工艺曲线共用这套样式；各屏幕保持原有请求代次及玩家本地状态。样品分析直接使用服务端有类型的数据，请求及响应采用 v4 通道，由通道支持检查决定是否发送。

## 表格与曲线

开发客户端默认窗口为 1600×900；界面验收使用 GUI 缩放 2，逻辑视口为 800×450。正文保持原版字号，只有调试 HUD 与温度上下偏差缩小。字段通过行列对齐、交替行底色和分组表达关系，不使用中点分隔内容。

控制器以成分和相态为行。相态用图标表示，离子不显示相态图标或自身的体积。材料别名与对应化学式使用统一名称，残余质量仅增加标记。固液纯组分使用实际库存体积，混合组分缺少独立体积时显示 `—`；气体显示已记录的物质的量（mol）。内能以区域总量单独列出。

温度中心值为成分质量加权中位数，上下标分别显示最高温度减中位数、中位数减最低温度。凝聚相使用所在单元的空间热观测，气体使用所在节点温度。等分权重恰好落在两个温度之间时取二者均值。读数来自已提交状态，客户端渲染不读取求解器。

样品分析使用有类型的总量、材料库存和物种数据，客户端统一格式化。当前样品与基准按同一行对齐，气体总量和载体行显示 mol，固液保留体积；不同量纲不计算差值。物种行显示物质的量、质量分数、摩尔分数及凝聚相浓度。缺少物种记录或热状态的字段显示 `—`，基准随断线或跨维度清除。

工艺曲线保留全体液位、液体温度、气体温度、绝对压力与液体流量，并提供测量范围内成分的质量、物质的量、温度中位数及可归属的固液体积。选项由实际可用数据生成，离子和气体不提供体积曲线。先选指标，再勾选一个或多个成分；同一指标的曲线使用同一张图及共同物理量程，左侧颜色图例显示相态图标和成分名，悬停显示各曲线读数。

支持时间窗口、前后平移、冻结视图及输入模拟时间起止值。冻结保留客户端快照，服务器继续记录。每条曲线保留最近 256 个样本，每 4 游戏刻采样；横轴使用区域模拟时间。每个观察会话最多选择 16 条曲线，选择按会话独立维护。传感器配置指标持续记录，成分按订阅采集；未选择成分时每 20 刻更新一次可用目录。关闭界面释放本会话，断线后的租约在 40 刻内到期。请求和响应采用 v3 通道。

## 调试 HUD 与文件日志

调试面板是独立的屏幕空间 HUD，默认常驻右上角，不接管鼠标，不暂停游戏。F8 显隐，F9 切换简洁／展开，按键可在原版控制设置中修改。偏好保存在 `config/mmm-debug-display.properties`，菜单打开时不覆盖菜单。

HUD 使用 0.8 倍字号。系统读数与区域求解读数分组，标签左对齐、数值右对齐，每半秒更新缓存。服务器发送有类型的快照；超过两秒未更新标记过期。命令结果只显示短暂完成提示或精简错误原因，完整命令输出不占用 HUD 或聊天栏。模拟故障显示简短提示，完整异常保留在文件。

专服诊断限定同维度、范围内的管理员；单人世界主人也可接收。跨维度、断线及连接配置阶段清理客户端状态，数据包携带维度以拒收旧维度内容。调试数据与游玩仪表盘分别维护。

文件日志保留两类信息：`logs/mmm-commands.log` 按时间、来源、命令、结果记录用户操作；`logs/latest.log` 保留运行环境、区域故障和异常堆栈。区域状态报告按主题分行。半秒刷新的 HUD 快照不写文件，避免重复记录连续读数；周期性渲染统计降为 DEBUG 级别。

## 验证

`:fabric:runHostValidation -PhostValidation=protocol,audit,lifecycle,sensor-display` 覆盖成分投影、样品数值与协议、多指标历史及区间、显示状态隔离、诊断快照和日志。`:fabric:assemble :fabric:runArchitectureValidation` 检查资源打包、客户端／专服编译边界和模块结构。

实机使用隔离目录 `build/validation/ui-live/run`；截图与本轮结果保存在 `build/validation/ui-density/`。验收固定 1600×900、GUI 缩放 2，检查控制器成分表、双样品对照、多指标曲线／时间区间及 F8／F9。

### 成分与同图曲线验证（2026-11-11）

源码及离线逻辑验证通过：质量加权中位数、成分 mol、离子体积排除、统一名称、共同坐标量程、数据包编解码，以及真实库存的双成分采样和空间范围筛选。会话测试覆盖两名玩家使用相同会话编号时的隔离、空选择、关闭、到期和重新选择。完整构建、游戏测试源码编译与架构检查通过，记录位于 `build/validation/component-trends/`。本轮未启动游戏、查看图片或截图。

### 前轮实机记录（2026-11-11）

协议、日志、生命周期、传感器验证以及完整构建通过。最新客户端布局使用独立输出目录编译，客户端状态验证及架构检查通过，记录位于 `build/validation/ui-density/`。

实机观察到控制器成分／残余质量行、样品对照表、F8／F9 显隐和展开、三项连续曲线、冻结查看与 90–100 秒自定义区间。`final/controller.png` 为前轮控制器截图；`sample-observed.png`、`trend-range-observed.png` 与 `hud-expanded.png` 保存已观察的界面。最后的样品紧凑排版、调试面板栏位平衡及曲线数字／区间标签整理已编译，补拍在用户按 Esc 中止实机操作后停止。客户端保留原状态。

运行日志保存在 `runtime-observed.log`。旧测试区域 `03567bd8` 的已有 `status=32` 数值故障受到保护性暂停，区域 `43544a7c` 用于本轮连续读数验证；该数值故障不在界面变更范围内。

## 生成提示词

### 独立徽标

> Use case: logo-brand. Asset type: final transparent pixel-art emblem for Minecraft industrial science mod 'Matter, Motion & Mechanics' (MMM), mod-list icon and game GUI. Primary request: romantic starry exploration meets humble industrial machinery. ONE compact centered emblem: a broad copper orbital ring around a deep midnight-blue miniature planet, a small warm ivory four-point guiding star above, a thin cyan light path through the center. Subtle dark-steel mechanical brackets make the orbit feel engineered, but poetic, quiet, hopeful, not military or corporate. Strong simple silhouette readable at 32px, designed on a precise 64x64 pixel grid enlarged with nearest-neighbor square pixels. Restrained palette of midnight steel #141f29, slate #344955, oxidized copper #bd8c5b, muted cyan #6fc8cc, ivory #e1ddc7. Flat orthographic sprite, 2-3 shade ramps only, no smooth gradients, no blur, no tiny noise, no 3D render. Transparent actual alpha around the emblem; fully isolated, balanced square composition, generous 6% transparent padding. No lettering, no words, no border around canvas, no mockup, no watermark. This will be paired with a separate precise MMM pixel wordmark by the application.

### 工业图标

> Use case: stylized-concept. Asset type: production pixel-art industrial GUI icon sprite sheet for Minecraft mod. Exactly 4 columns and 4 rows of separate icons, 16 total, evenly spaced, each centered within one equal square cell, all on ACTUAL TRANSPARENT ALPHA. No grid lines or labels. Canvas square. Keep every glyph within inner 65% of its cell with generous clear padding. Designed at conceptual 128x128 pixels (each icon tile 32x32), enlarged nearest-neighbor: simple bold 1-2 pixel strokes, clean hard square pixels. Row 1 from left: thermometer with amber bulb; circular cyan pressure dial; cyan rightward flow arrow with three stream lines; blue liquid droplet. Row 2: pale cyan gas puff of three squares; ochre pile of solid grains; glass sample vial with cyan contents; copper pipe handwheel valve. Row 3: cyan play triangle; copper pause two bars; amber warning triangle with exclamation; red octagonal fault stop with exclamation. Row 4: cyan rising line chart with axes; steel settings cog with copper hub; ivory lightning bolt; cyan three-line log/document icon. Cohesive palette deep steel outline #1c2c37, subdued cyan #6fc8cc, copper #bd8c5b, ivory #e1ddc7, amber #d6a34e, red #d66c61. Flat orthographic small game sprites, clear distinct silhouettes, no words, no typography besides the two exclamation marks, no decorative backgrounds, no panels, no perspective, no blur, no smooth gradients, no outer shadows. Every icon should be readable at 16 to 24 screen pixels. Uniform size and shared industrial visual language.

### MMM 字标

以独立徽标原图为身份参考。

> Use case: logo-brand. Create a transparent horizontal pixel-art logo lockup using the attached orbital planet/star emblem as the exact identity reference. Left 35%: preserve the compact copper orbit, cyan trajectory, midnight planet, steel mechanism and ivory north star, simplify only enough to align cleanly to square pixels. Right 60%: large highly legible custom pixel letters exactly 'MMM' (three capital M letters, M M M), ivory faces with dark-steel one-pixel outline and restrained copper lower edges. Leave a calm gap between emblem and letters, centered vertically, generous transparent padding. Wide composition roughly 3:1 aspect ratio. Strict game pixel art, like a 192x64 pixel design enlarged nearest-neighbor; flat orthographic, hard square pixels, simple readable silhouette. Actual alpha transparency. No additional text, no subtitle, no landscape, no vignette, no blur, no gradients, no mockup or watermark. Romantic scientific exploration, matching the attached mark.
