# 气体状态契约

## 模型边界

- 开放空间连接无限环境节点；密闭空间保存有限气体库存。
- controller 的已验证闭壳 footprint 可声明 sealed domain boundary。命令临时域保持环境开放。
- `AirCavityScanner` 只在当前网格预算内 flood fill，不读取未加载世界；部分充液格的剩余空间参与 runtime available volume。
- 节点 pressure、density、temperature、孔流速度和边是派生运行态，不是独立库存。

### Cavity scan

由当前几何直接发现的 air cell 同时满足：非 solid、`markerCount == 0`、扣除 cut-cell、granular/coherent occupancy 后仍有正 open volume。**扫描不区分"气"和"未采样的液体格"**：两者对同一个 flood fill 是同一件事——一段连通的无载体体积。是否成为腔体不靠分类决定，腔体带什么初始库存才需要区分，而那由 lineage 分配处负责：无旧 owner 的新 component 是显式真空节点，库存、质量与 `U` 均为零。marker bulk 的普通空隙不必被猜成气泡，因为它即使成为 component 也拿不到库存。六邻域 flood fill 只跨越接近全开的 face；部分开放 face 不合并 component，而是形成有限速率 geometric edge。已存在的密闭有限库存若暂时只剩部分充液格，则其旧 lineage 中仍有正 residual volume、且不接触任何本轮 full-air/ambient component 的连通部分成为显式 partial owner component；真实连通时仍按正常 merge 处理。

密闭壳内失去全部拓扑去向的库存**不判为丢失**：腔体消失时 `normalizeClosedCapacity` 已经把它的容量重新分给仍然存在的腔体，库存按同一组容量权重随之转移。被载体挤没的气泡、被抬升液面压碎的 headspace 碎片、以及闪断的载体空隙在这里是同一个事件——闭体积里的气，腔体没了；只有守恒决定它去哪。开放域中同样的情形才记 escaped sink。

- controller 已验证闭壳时，solver 外边界不自动判作 ambient；命令临时域和真实缺口仍连接 ambient node 0。
- scan 按固定邻居顺序执行，节点以 canonical minimum-cell key 排序，保证同一几何得到确定 topology。
- 超过 scan budget 时，尚未完成的空间 fail-open 为 ambient，不能凭不完整 flood fill 创建有限密闭库存。
- available volume 是每 cell 的几何 open volume 减逐格 exact marker volume 与统一固体 occupancy，再乘 `cellSize³`；平滑液体 fraction 不结算气体容量或绝热功。单格 residual 只在局部拓扑归属时钳在几何范围内；完整闭域的节点容量总和随后必须严格等于“有效 open geometry 总量减全部权威 liquid carrier 体积”，局部 marker 聚集不能凭逐格钳制创造额外气相体积或绝热功。runtime attribution 可沿 gas-centered（gas residual 至少占 open volume 一半）的连通部分充液格传播，随后只给相邻的一层 liquid-centered interface residual 归属，不能借 marker 空隙穿过整个液体 bulk；多 node 相遇时 ambient/较小 node 优先。

## 权威真值

- 每个密闭节点以 canonical minimum-cell key 和完整 cell membership 标识。
- 每个气体组分保存稳定 component ID、标准体积 nanos、独立质量 kg、可选 species 和 chemical mass residual kg；气体的两个质量列都不使用凝聚相 solver mass weight。
- source、sink、质量 roundoff 和 residual roundoff 是独立审计列。
- ambient 节点不持有有限库存；开放域中新密闭腔从环境建立时明确记 external source。已启动闭域内没有旧 owner 的新 cavity 是显式真空节点，其 component 库存、质量和 `U` 均为零，不得伪造 ambient source。

`GasRegistry` 规范化 component 定义和 ambient fraction；ambient fraction 必须按 exact rational 精确和为 1。标准态固定为 `101325 Pa`、`293.15 K`。每个 component 分别定义标准态 kg/m³、允许承载的 species、默认 species quanta/m³ 和质量 residual/m³；默认 species 必须属于该 component 的允许集合。创建 ambient inventory 时用 largest-remainder 对整数 nanos 确定分配。

component 与 species 的隶属关系是 world-local registry 真值，不由运行时库存临时推断。相变 source、拓扑 remap、恢复和 source/sink audit 都必须在发布前验证该关系；一个已知 chemical species 也不能挂到未声明它的 gas component。气液相变只把同时被 component 允许且具有 host gas thermal offset 的 species 视为可挥发，其他 species 留在凝聚相。允许集合非空只声明能力，当前功能布局中实际出现 species 时仍必须同时存在匹配的 chemical registry 和 host gas thermal species offset。

`AirNetwork` 对节点、库存、可用体积、edge 和物质审计维护同一个单调 revision。topology remap 绑定规划时 revision；提交前 gas inventory 与 gas-energy 两个 owner 必须同时接受各自 generation，之后才发布任一侧。几何仍相同但库存、体积、edge 或 `U` 已改变的旧计划必须整体拒绝，不能覆盖较新的 owner 真值。

表示硬上限为：registry 64 个 component、单 node 32 个 component、单 component 16 个 species、单 region 65536 个 sealed node、stable ID 128 字符、单 extension 16 MiB。到达上限时拒绝候选或 fail-open 到 ambient；不能丢掉最小 component 继续运行。

node ID 只是当前 topology 的稠密 ordinal；持久身份由 cell membership 和 minimum-cell key 决定。component stable ID、standard-volume nanos、kg 和 species/residual 独立保存，不能从理想气体状态方程反算库存。凝聚相与气相搬运 chemical residual 时，只在相边界用该区域冻结的 `cellSize³` 做 signed solver-weight/kg 换算；气相内部输运、分裂、合并和持久化始终保持 kg。

## 拓扑变化

拓扑重扫以重扫前后节点的实际 cell lineage 规划 merge、split、vent 和新腔初始化：

- merge 汇总完整库存和能量；
- split 按几何份额分配，精确余数只落入一个确定节点；
- 开放域中打开或消失的腔体把实际库存记入环境 sink；密闭域中不产生 source/sink，失去拓扑去向的库存按剩余腔体容量权重转移，不记为丢失；
- 开放域中新腔体按环境组成和当前可用体积记 source；
- 相变产生或消耗的 water vapor 通过同一组件库存与 source/sink 审计提交。

部分充液界面的短时拓扑抖动使用 runtime lineage 减少虚假空气周转，但不能改变物质账本。新 full-air child 没有同格 membership overlap 时，只能继承同格或跨开放 face 相邻的旧 runtime lineage。**继承只在旧 node 已经不拥有任何本轮开放 cell 时发生**：仍拥有开放 cell 的旧 node 一律按 membership overlap 匹配，不会把库存分给一个新出现的空腔。因此已启动闭域里无旧 owner 的新 cavity 按契约是显式真空节点，库存、质量与 `U` 均为零，不伪造 ambient source。旧 node 的 full-air membership 完全消失且旧 residual component 仍与新 gas component 相邻时，从原 interface cells 返回该新 lineage。若该 residual component 具有正容量但不接触任何新 gas component，它必须继续拥有原有限库存：该 component 的 partial owner cells 进入本代 topology membership，并按 minimum cell 与其他节点一起重新 canonicalize；若分裂成多个不连通 residual component，则按各自实际容量规划库存 split。这样不会把无法以 full-air cell 表示的真实气泡删除或任意并入另一节点。marker bulk 的普通空隙也不需要专门识别：它即使成为 component 也拿不到库存，消失时又按上一条守恒规则把库存交回余下腔体。

稳定 topology 快路每次仍从当前 exact residual volume 重新分配 interface lineage；相变新打开的微小气隙不能沿用上一轮空 residual 的归属，也不能被当成凭空新 node。闭域完成初次环境库存建立后，任何 topology source/sink、正容量有限库存失去 owner、有限库存被压到零容量或 scan budget 耗尽都以 typed gas-topology failure 拒绝完整候选；生产域进入 `NUMERICAL_PAUSE`，不会让该失败穿透 server tick，也不会提交部分 remap。注液只能改变现有库存的容量、拓扑分配和压缩功。

气液相变若只截断或扩充既有 marker，marker cardinality、cell membership、solid/cut-cell 与 granular occupancy 都没有改变，因此 air-cell 连通性和 interface lineage 也严格不变。该中间候选用实际提交前后 float marker 体积差更新被编辑 cell 的 exact marker sum，再沿现有 node membership 重新汇总连续 available volume；每个 node 仍规划完整绝热体积功，并把 gas `U` 与 node volume 一次提交。只有 marker 新增/删除或其他结构变化才重新 flood fill、overlap 和 remap。logical step 末以及 capture 前仍从完整 carrier geometry 重扫，稳定路径不是持久 topology 的替代真值。

重扫先建立旧 node 与新 node 的实际 cell overlap，再纯规划全部 transfer：

- 一对一 lineage 携带完整 node inventory；
- 多对一 merge 对 component、species、mass/residual 和 U 分列求和；
- 一对多 split 按 overlap/available-volume 整数权重分配，最后一个非空 child 接收 raw remainder；
- 连到 ambient 的份额进入 sink，凭空形成的 sealed volume按 ambient composition进入 source；
- topology 与 thermal U 的 transfer plan 必须一起可提交，否则 network 保持重扫前状态。

## 压力与热耦合

- 无热状态时使用等温状态方程和有限速率孔流。
- 热状态存在时，气体总内能与节点库存共同派生温度、绝对压力和密度；孔流同步搬运组分与能量。
- 气液相变用 water-vapor 分压计算饱和条件，受绝对压力、潜热、库存、容量和批预算共同限制。
- 所有 candidate 温度和压力必须落在 world thermal registry 的有效域；越界安全暂停。
- mechanics 可从所有液气界面选择一个统一 sealed-node gauge reference，在 PCG 内只求相对压力并在绝对观察/热学边界加回；该数值换元不修改 node pressure、inventory、U 或不同 node 间的压差。

当前 `AirNetwork` 是有限体积热力学/孔流网络，不是逐 cell/face 的气体动量求解器；空间 FLIP 网格仍只承载不可压凝聚相。气体的可压缩性已经由库存、available volume、`U` 与状态方程表达，并通过界面 pressure 耦合液体，但尚不承诺解析腔体内部速度场、声学传播或气体混合层。后续若把气体迁入共享空间动量内核，必须保留这里的 exact 组分/质量/能量账，并以状态方程闭合密度和非零散度；不能复用液体零散度 projection 把气体错误地压成不可压，也不能按材料 ID 选择另一套平流/传递算法。

### 状态方程

无热状态的等温 gauge pressure 为：

```text
p_gauge = P0 * (Vstd / Vavailable - 1)
```

热状态使用节点权威 U 反演 `T`，再计算：

```text
p_abs = P0 * (Vstd / Vavailable) * (T / T0)
rho_gas = total_mass_kg / Vavailable
```

零库存 sealed node 表示真空：`p_abs = 0`、`rho = 0`、`U = 0`，几何体积变化不产生绝热功。真空的温度没有热力学定义；只读显示使用区域插入温度作为约定值，不能据此反算库存。孔流可按有限速率把环境或相邻节点的真实库存搬入真空。

无热状态保留早期等温网络的数值正则：available volume 最低取 `1e-9 m³`，gauge pressure 限制在 `-0.99 P0 .. 20 P0`，并分别累计 floor/ceiling diagnostic；这只限制传给机械求解器的派生压力，不改气体库存。热状态不使用该钳制修复候选，而把相同压力窗作为有效域，越界安全暂停。无传质绝热体积变化按 constant-`c_v` ideal-gas isentrope 计算新温度与 `delta U`；跨 caloric segment 或有效域时整步暂停。

### 孔流

部分开放 face 聚合为面积 `A` 的 edge，流量采用 CONTAM 型孔流：

```text
Q = Cd * A * sqrt(2 * abs(delta_p) / rho_source)
```

一个 step 的 transfer 会被共同平衡压力所需的最大份额限制，不能越过均压点。标准体积 nanos 用整数结算；热状态同时搬运源侧焓 `h = u + p*v`。ambient 流入记 source，流出记 sink；节点间转移不记外部源汇。

## 持久化

气体只作为完整热区域 generation 的 required payload 发布。恢复时从已提交 carrier geometry 重新扫描 full-air topology；已提交 membership 中的 partial owner 只在同一格仍有正 residual、连通部分仍不接触任何重扫 gas component 时重新建立。随后要求节点数、minimum-cell key、逐格 membership、库存和能量 ordinal 全部一致；任何差异都锁定，不猜测 remap。

持久 payload 还包含 registry fingerprint、unit identity、component palette、节点总账、逐 section membership（包括必要的 partial owner cells）和 source/sink/roundoff 审计；显式真空 node 以空 component inventory 原样保存。gas registry fingerprint 同时绑定每个 component 的允许 species 集合；不匹配时原样保留并锁定。edge、pressure、temperature、其余 runtime interface lineage 和 flow velocity 可从完整库存与几何重建，不进入 checkpoint。

管理员 `VENT_SEALED_GAS` 是一个联合持久候选：所有 sealed component nanos/kg/species/residual 按实际值进入 gas escaped sink，所有 gas node `U` 按实际值进入 thermal external sink，有限 node 与 energy ordinal 一起清零。原 sealed cell 随后只标记为 ambient-connected geometry；ambient 不是 region inventory，不能用 ambient 默认库存补回。`operatorVentedTopology` 随 gas payload 保存并在恢复时重建该语义，因此后续凝聚相 `empty`、capture 或 topology 同步不能重新生成 sealed inventory；排放后的区域也不能恢复运行。
