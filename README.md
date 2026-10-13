# Matter, Motion & Mechanics

Minecraft Java 26.2 / Fabric 流体模组。玩家用外壳方块和控制器围出封闭空间，模拟其中的液体、气体、颗粒、化学反应、传热与相变。当前最多支持四个独立区域，物理计算使用 CPU 有限体积液体输运与 PIC/FLIP 速度传递。

## 接到需求后从这里开始

开发前按 [AGENTS.md](AGENTS.md) 完整读取项目入口、系统总览、维护路由、产品路线与本轮涉及的架构专题，再按下表定位代码。专题开头给出代码入口和相关测试，正文解释修改时需要了解的数据与算法。

| 要做什么 | 从哪里开始 |
|---|---|
| 改物品、配方、创造栏、提示文字或命令 | [游戏交互代码](docs/maintenance/routing.md#gameplay) |
| 改启动、tick、暂停、控制器操作或区域预算 | [运行时与区域操作](docs/architecture/runtime-and-lifecycle.md) |
| 改水流、液面、压力、碰撞或粒子运动 | [流体求解器](docs/architecture/fluid-solver.md) |
| 改材料、密度、混合物或液液界面 | [材料状态](docs/architecture/material-state.md) |
| 改气腔、气压或孔隙间的气体流动 | [气体状态](docs/architecture/gas-state.md) |
| 改化学反应、指示剂或 pH | [化学状态](docs/architecture/chemistry-state.md) |
| 改颗粒、沉积、溶解或析出 | [颗粒状态](docs/architecture/granular-state.md) |
| 改导热、温度、相变或热设备 | [热学状态](docs/architecture/thermal-state.md) |
| 改存档、数据包配置、启动恢复或区域删除 | [区域存档](docs/architecture/region-state.md) |
| 改桶、发射器、世界方块接管或事务恢复 | [世界归属与容器事务](docs/architecture/world-ownership-and-containers.md) |
| 改网络快照、渲染或客户端界面 | [客户端与协议代码](docs/maintenance/routing.md#client) |
| 接入其他 Mod、查询接口、事件或 Jade | [外部 API](docs/architecture/external-api.md) |
| 排查已有故障，但还不知道涉及哪个模块 | [按现象定位](docs/maintenance/routing.md#diagnosis) |
| 优化性能或开发 GPU probe | [性能与 GPU 代码](docs/maintenance/routing.md#performance) |
| 了解模块关系和一次计算的完整过程 | [系统总览](docs/architecture/system-overview.md) |

修改后的测试命令见 [验证指南](docs/maintenance/validation.md)。规划新功能时看 [产品路线](docs/roadmap/product-roadmap.md)，准备发布时看 [验收与发布](docs/roadmap/acceptance-and-release.md)。[文档目录](docs/README.md)另列出了工具和历史资料。

## 在游戏里使用

1. 用玻璃和铁锭制作 `fluid_boundary`，用比较器、红石和铁锭制作 `fluid_controller`。创造模式也可从模组物品栏取得它们和快速入门手册。
2. 用外壳方块和一个控制器围出唯一的封闭内腔，并保持所在区块加载。
3. 空手右击控制器，创建区域并开始运行。再次右击选择区域，空手潜行右击切换暂停状态。玩家选择的运行状态会写入存档。
4. 手持物质场探针右击控制器，查看状态和操作界面。管理员可依次预览并确认清空凝聚相、排气、释放世界方块归属、移除空区域。

外壳破损或所需区块卸载时，区域暂停计算，保留玩家原先的运行意图。当前游玩范围是控制器内的封闭空间；天然河流、海洋和开放洞穴的接入仍在规划中。

`/mmm` 提供开发场景、诊断和恢复操作。常用命令见 [命令速查](docs/maintenance/commands.md)。

## 构建

目标字节码为 Java 25，现有开发环境使用 JDK 26。在仓库根目录执行：

```powershell
./gradlew.bat assemble --offline --no-daemon
```

`--offline` 使用本机 Gradle 缓存；首次构建缺少依赖时，去掉该参数下载依赖。版本配置在 [gradle.properties](gradle.properties)，模块列表在 [settings.gradle](settings.gradle)。

主模组产物位于 `fabric/build/libs/`，可选 GPU probe 产物位于 `gpu-probe/build/libs/`。部署步骤见 [验收与发布](docs/roadmap/acceptance-and-release.md#release)。

构建缓存、实验输出与本地研究归档的保存位置和清理方式见 [构建输出管理](docs/maintenance/build-outputs.md)。
