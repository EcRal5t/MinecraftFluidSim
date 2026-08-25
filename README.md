# Real Water Prototype

Minecraft Java 26.2 / Fabric 的有界三维 PIC/FLIP 流体原型。M0–M8 已验收；当前主 Mod 0.15.4 是 M9 修订候选，仍由 CPU 权威求解。系统支持最多四个独立区域、控制器闭合边界、跨 chunk 完整加载、原子持久化、分数自由面和 cut-cell 碰撞；默认关闭的材料门后提供多液体、化学和低成本颗粒。M9 加入权威内能、派生温度、有限体积导热、世界固定温度边界、Boussinesq 自然对流、反应热、连续固相、固液/气液相变、非等温密闭气体、正交热休眠，以及 corrected-SI region-state v7、protocol v5 和 container-transfer WAL v5。

这还不是原版水的全局替代。普通模拟不自动修改世界；控制器功能默认关闭。

## 安全边界

- takeover 只临时移除纯 `Blocks.WATER`，并在修改世界前原子发布 journal v1 与 WARNING；恢复不会覆盖玩家放置的实体方块。
- takeover 恢复文件仍存在时不要移除或降级 Mod；先执行 `/realwater remove` 或让启动恢复完成。
- 控制器区域必须具备完整可见边界和全部已加载 chunk；Mod 不持有永久 chunk ticket。
- `region-state v1` 使用原子检查点。未知、损坏或未解决状态会锁住新控制器创建，不会被静默删除。
- 材料功能门默认关闭，且只在服务器启动时读取一次。开启后控制器区域改用 `region-state v2`，连接必须协商 protocol v2；关闭门时磁盘上的 v2 区域会被保留并锁住，不会静默降级或删除。
- 化学功能门同样默认关闭，要求材料门已配置为开启并在重启后生效。开启后区域使用 `region-state v3`、protocol v3 与 container-transfer WAL v3；关闭门发现 v3、registry snapshot 未知/损坏或 fingerprint 不符时只保留并 `LOCKED`，绝不降级猜测。
- 颗粒功能门默认关闭并依赖材料门；沉淀门还依赖化学门。开启后联合区域状态使用 `region-state v5`、protocol v4 与 container-transfer WAL v4；world granular registry store v3 冻结 exact extent/SVQ batch 与 chemical dependency。active/deposit、反应性 audit 或 registry 身份未知时同样只保留并 `LOCKED`；旧 v2 registry 不按当前数据包重解释。
- 热学功能门默认关闭并依赖材料门，相变门又依赖热学门；两者都只在重启后生效。开启后 world gas/thermal registry snapshot 权威，修订后的完整联合区域使用 `region-state v7`、只读展示使用 protocol v5、液体容器事务使用 WAL v5 与 `ThermalContents v1`。温度只是由独立总内能和状态函数派生的读数；unknown/corrupt/fingerprint 失配一律保留并 `LOCKED`。旧 v1/v2/v3/v5 与错误 condensed-mass 尺度的 legacy v6 区域只允许在暂停、ownership 完整且没有未决容器 WAL 时由管理员显式执行 `regions thermalinit <K>`；v6 若仍有活 gas 节点、不闭合账本或 roundoff 也会拒绝，只有无节点且 source=sink 精确闭合的历史周转可保账迁移；v4 气体基态不能猜测升级。
- 世界桥功能门（`/realwater worldbridge`）默认关闭且重启生效。开启后新激活的控制器区域会通过可恢复的 ownership 事务一次性移除内部纯原版水（WAL 与 WARNING 先行，region HEAD 最后提交）；区域与容器间的转移另有独立 WAL。移除区域前必须先恢复完整状态并确认流体 marker 与固体 SVQ 都恰好为空。
- 可选 GPU probe 只读、不修改世界或存档；主 Mod 始终以 CPU 为物理规范。

## 常用命令

| 用途 | 命令 |
|---|---|
| 创建开发域 | `/realwater demo`、`capture`、`takeover` |
| 创建多个持久区域 | `/realwater controllerdemo` 安全创建 4×4×4 内部测试壳；或手建封闭壳后空手右击控制器；`regions list` 查看 |
| 多材料开发域（需材料门） | `/realwater materialdemo` |
| 材料桶调试取得 | `/realwater materialbucket <empty|water|oil|mercury|water_oil_mix>` |
| 表面张力液滴开发域（需材料门） | `/realwater tensiondemo [角度1-179]` |
| 气压 U 型管开发域 | `/realwater airdemo [pressure\|vacuum]` |
| 操作水量与碰撞 | `/realwater pour [1-16]`、`drain [1-16]`、`refresh` |
| 清空所选区域的液体、coherent 相与全部颗粒/沉积 | `/realwater empty` |
| 运行控制 | `/realwater run`、`pause`、`step [1-20]`、`status` |
| 区域与控制器 | `/realwater controllers [true|false]`、`controllerdemo`、`regions list`、`regions select <uuid>`、`regions checkpoint` |
| 材料功能门（下次重启生效） | `/realwater materials [true|false]` |
| 化学功能门（下次重启生效） | `/realwater chemistry [true|false]` |
| 化学开发域与试剂（需化学门） | `/realwater chemistrydemo`、`reagent <hcl|naoh|nacl|nano3|kcl|kno3|water|indicator|eraser>` |
| 管理员完整化学库存 | `/realwater chemstatus` |
| 颗粒/沉淀门（下次重启生效） | `/realwater granular [true|false]`、`precipitation [true|false]` |
| 热学/相变门（下次重启生效） | `/realwater thermal [true|false]`、`phasechange [true|false]` |
| 旧区域显式赋予初温并原子升级 v7 | `/realwater regions thermalinit <temperatureK>` |
| 失败 checkpoint 显式恢复到上一完整代 | `/realwater regions recoverprevious <expectedCheckpoint>`；成功后退出并重新进入世界 |
| 可调恒温边界 | `/give @s realwater:thermal_reservoir`；准星对准后 `/realwater thermalsource [temperatureK]` 查询/设置 |
| 管理员干颗粒容器调试取得 | `/realwater granularcontainer <empty|fine_sand|light_beads>` |
| 管理员完整颗粒库存 | `/realwater granstatus` |
| 世界桥功能门（下次重启生效） | `/realwater worldbridge [true|false]` |
| Ownership 对账报告 | `/realwater ownership` |
| 调试与实体 | `/realwater debug [true|false]`、`entities [true|false]`、`logpath` |
| 安全移除 | `/realwater remove` |

控制器玩法：快速验证可开启 `/realwater controllers true` 后执行管理员命令 `/realwater controllerdemo`，它只在玩家面前完整的空气体积内搭建并自动激活一个内部 4×4×4 的空壳，放置或激活被拒绝时回滚壳块。手工玩法则先用 `/give @s realwater:fluid_boundary` 和 `/give @s realwater:fluid_controller` 取得方块；用边界块建造完整的一格厚封闭外壳，以一个控制器替换壳块，再空手右击控制器。普通边界是透光金属框，但仍保持完整方块碰撞。每个互不重叠的控制器会新增并选中一个持久区域，默认总预算最多 4 个；用 `/realwater regions list` 和 `/realwater regions select <uuid>` 管理。`demo` 是替换当前开发域的单域兼容命令；`empty` 只清空当前所选区域，不删除区域。首次激活只读采样内部原版水并创建暂停区域；若同时开启 `/realwater worldbridge true` 并重启，激活会一次性移除内部纯原版水并建立可恢复 ownership（`/realwater ownership` 查看对账）。玩家可对壳面中心直接使用容器；外部发射器须正对壳面中心，转移目标严格解析为其正后方的唯一内部格，壳边和壳角不会猜测路由。

多材料玩法：`/realwater materials true` 后重启服务器（单人模式退回主菜单再进入世界）。`/realwater materialdemo` 会创建上油下水的瞬态暂停溃坝域，不写 `region-state`，也不读取或接管原版水。正式材料容器入口需要材料门和世界桥均已生效：空材料桶可从准星所指控制器区域装取，材料桶可向目标区域放出；多成分 tooltip 逐项显示稳定材料 ID、体积占比/绝对体积和独立质量。纯水满桶规范化为原版水桶，部分纯水保留在专用部分桶；材料/混合物保留在材料桶。调试取得命令只用于显式测试来源。

化学玩法：先依次设置 `/realwater materials true`、`/realwater chemistry true` 与容器入口所需的 `/realwater worldbridge true`，再重启。`chemistrydemo` 建立左右 HCl/NaOH 的暂停开发域；`reagent` 提供明确记作外部 source 的试剂桶，以及不消耗的通用指示剂与抹除器 operator 工具。指示剂只标记当前通过开放 cut-face 连通且同 chemical phase 的 marker，最多 100 BV；浅液面瞄准可在开放面内向两格内吸附到实际 marker，后来混入的未标水不会自动染色。空材料桶可完整保存 species、质量 residual 与 indicator，tooltip 按 material/indicator group 列出每个 stable species 的精确 AU/quanta；原版空桶只接受未标记纯 H2O。完整逐 species 真值只由管理员 `chemstatus` 按需查看并写入 transcript。

颗粒玩法：先开启 materials、granular 与容器入口所需的 worldbridge，反应沉淀还需 chemistry+precipitation，随后重启。颗粒只进入显式闭壳 controller；满干颗粒容器对壳面中心放出，空容器从实际目标格附近的 active/exposed inventory 有界装取，玩家和发射器使用同一 v4 WAL 事务。管理员 `granularcontainer` 只生成经 world registry 校验的惰性测试来源，不能伪造反应性组成；`granstatus` 在服务端报告 exact SVQ、mass、solid species、source/sink 与 boundary impulse，客户端只显示量化 deposit 表面和 active samples。需要一键重置时，先选中目标区域再执行 `/realwater empty`；它先联合预检 liquid/chemical、M9 coherent phase 与 granular sink candidate，再同时清除液体、coherent 相、active parcel 和 deposit，并把实际移除的物质与内能记入 escaped sink 后发布同一 checkpoint。气体库存不属于这次凝聚相清空。存在未决容器 WAL 时会拒绝清空。

热学玩法：在首次创建 thermal snapshot 前先一次配置好本世界需要的 materials、可选 chemistry/granular，再开启 thermal；需要相变时再开启 phasechange，然后重启。新建区域以 293.15 K 建立权威内能；旧 v1/v2/v3/v5 及 legacy v6 controller 不从环境或当前数据包猜温度，必须保持暂停后执行 `regions thermalinit <K>` 才会经 inactive slot 原子升级 v7。启动时这种旧代次会稳定等待 operator，`status` 和入服消息会明确报告仍保留的 checkpoint/marker/体积；由于 protocol v5 不能伪造内能，初始化前有意不发送该区域的热快照，客户端会看不到水，但这不是库存丢失。若某个 v7 HEAD 自身通过文件校验却因同代 marker/gas topology 不一致而恢复失败，启动不会自动猜上一代；管理员只能在区域仍为 metadata-only、暂停、无 ownership/WAL 歧义时执行 `regions recoverprevious <当前checkpoint>`。命令会完整试恢复紧邻的上一代、再原子切换 HEAD，保留被拒绝 slot，并要求退出后重新进入世界；它不是任意撤销工具。`status` 报告派生的凝聚相温度范围、总 U、外部 source/sink、机械功、连续固相 SVQ、最近相变批数/预算命中，以及每个密闭气体节点的 water-vapor kg/标准体积和逐 air/vapor source/sink 周转、pending work 与 audit；这些读数不参与结算。普通世界方块绝热，world thermal snapshot 明确声明的冰方块表面固定为 273.15 K。`thermal_reservoir` 是同一有限体积边界路径的可调无限热浴，默认 293.15 K；它可直接替换控制器闭壳的一格，准星对准后用 `thermalsource <K>` 改变持久设定值，低于流体时是冷源、高于流体时是热源，实际交换仍只通过 cut-face 通量进入 source/sink 审计。液体显热还会通过 Boussinesq 体力驱动自然对流；气液相变按完整最小量子成批规划，仍受潜热、饱和压力、库存、容量与每步预算约束。设定值不会被钳制；若候选载体状态超出 world thermal registry 的有效域，求解器报告实际候选温度/材料/区间并安全暂停。bundled water-vapor 的 `c_v` 与参考能修正只用于首次建立的新 world registry snapshot；已有世界继续按自己的 fingerprint/definitions 解释旧 `U`，不会被当前资源静默迁移。液体容器在热区域内用 WAL v5 同时搬运物质与 `ThermalContents v1` 总能量；tooltip 温度仍只读。局部流体温度查询/事件 API 与 Jade/WAILA/HWYLA 适配留在 M10，主 Mod 不另加准星 HUD。

气压玩法：`/realwater airdemo pressure`（或 `vacuum`）在准星方向自动搭一座玻璃 U 型管（左腔密闭、右腔通大气）并下半注水；`/realwater run` 后两侧液面形成并保持由气压支撑的稳定高差（正压约 1.4 m、负压约 2.1 m），`/realwater status` 的 gas 段显示密闭腔压力与库存。玻璃只替换空气方块，`/realwater remove`、创建其他开发域或正常关服时自动恢复原状；异常崩溃后残留的玻璃可自行拆掉。`/realwater debug true` 时密闭气腔以高度透明灰色显示。该域瞬态，不写 `region-state`。

## 构建

目标字节码为 Java 25；本机使用 JDK 26：

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat assemble --offline --no-daemon
```

代码职责、最小测试选择和专项 Gradle 命令不在这里重复，统一从 [文档索引](docs/README.md) 进入：

- [当前架构契约](docs/architecture/fluid-solver.md)、[M6 世界 ownership/容器契约](docs/architecture/world-ownership-and-containers.md)、[M7 化学状态契约](docs/architecture/chemistry-state.md)、[M8 颗粒状态契约](docs/architecture/granular-state.md)与[M9 热学设计冻结](docs/architecture/thermal-state.md)
- [完成进度与验收证据](docs/progress/README.md)
- [未来里程碑与技术规格](docs/roadmap/product-roadmap.md)
- [代码与测试维护路由](docs/maintenance/routing.md)
