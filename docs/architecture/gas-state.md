# 气体状态契约

修改气腔扫描、气体库存、压力响应或孔流时读本文。

## 修改入口

| 要改什么 | 代码入口 | 测试选择器 |
|---|---|---|
| 气腔扫描和归属 | [AirCavityScanner](../../solver/src/main/java/cc/ecisr/mmm/fluid/AirCavityScanner.java) | `air-cavity` |
| 气体库存与孔流 | [AirNetwork](../../model/src/main/java/cc/ecisr/mmm/fluid/air/AirNetwork.java) | `air-network` |
| 温度、压力和声学状态 | [GasThermalModel](../../model/src/main/java/cc/ecisr/mmm/thermal/GasThermalModel.java) | `thermal` |
| 气液压力耦合 | [PressureBoundaryView](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureBoundaryView.java)、[PressureProjectionWorkspace](../../solver/src/main/java/cc/ecisr/mmm/fluid/PressureProjectionWorkspace.java) | `gas-liquid` |
| 定义与存档 | [GasRegistryCodec](../../fabric/src/main/java/cc/ecisr/mmm/fluid/air/GasRegistryCodec.java) | `region-state` |

执行方式见 [验证指南](../maintenance/validation.md#cpu)。

## 本文内容

- [模型边界](#模型边界)
- [权威数据](#权威数据)
- [拓扑变化](#拓扑变化)
- [压力与热耦合](#压力与热耦合)
- [持久化](#持久化)

## 模型边界

- 开放空间连接无限环境节点；密闭空间保存有限气体库存。
- controller 的已验证闭壳 footprint 可声明 sealed domain boundary。命令临时域保持环境开放。
- `AirCavityScanner` 只在当前网格预算内 flood fill，不读取未加载世界；部分充液格的剩余空间参与 runtime available volume。
- 节点 pressure、density、temperature、孔流速度和边是派生运行态，不是独立库存。

### Cavity scan

由当前几何直接发现的 air cell 同时满足：非 solid、`markerCount == 0`、扣除 cut-cell、granular/coherent occupancy 后仍有正 open volume。**扫描不区分"气"和"未采样的液体格"**：两者对同一个 flood fill 是同一件事——一段连通的无载体体积。

是否成为腔体不靠分类决定，腔体带什么初始库存才需要区分，而那由 lineage 分配处负责：无旧 owner 的新 component 是显式真空节点，库存、质量与 `U` 均为零；开放域里上一代明确连接 ambient 的实际体积若本代被夹断为 sealed，则按该体积截留环境库存，即使它同时并入一个既有有限气泡也一样。

marker bulk 的普通空隙不必被猜成气泡，因为它即使成为 component 也拿不到库存。六邻域 flood fill 只跨越接近全开的 face；部分开放 face 不合并 component，而是形成有限速率 geometric edge。

已存在的密闭有限库存若暂时只剩部分充液格，则其旧 lineage 中仍有正 residual volume、且不接触任何本轮 full-air/ambient component 的连通部分成为显式 partial owner component；真实连通时仍按正常 merge 处理。

完整重扫后若仍没有节点，扫描器为具有正残余体积的部分充液连通区建立显式归属，按原规则规范化节点顺序和闭域总容量。这覆盖满液域的分数抽取，以及旧 membership 全部消失、抽取位置没有旧 lineage 的情况。节点通过正常 remap 接受库存：没有可转移库存时保持真空，旧密闭库存仍按既有容量权重转移。持久化与恢复使用相同的扫描规则。

密闭壳内失去全部拓扑去向的库存**不判为丢失**：腔体消失时先沿开放面交给仍能到达的最近腔体，一个都到不了时才按全部剩余腔体的容量权重转移，库存随容量一起走。被载体挤没的气泡、被抬升液面压碎的 headspace 碎片、以及闪断的载体空隙在这里是同一个事件——闭体积里的气，腔体没了；可达性先决定它去哪，守恒只在没有可达腔体时兜底。开放域中同样的情形才记 escaped sink。

- controller 已验证闭壳时，solver 外边界不自动判作 ambient；命令临时域和真实缺口仍连接 ambient node 0。
- scan 按固定邻居顺序执行，节点以 canonical minimum-cell key 排序，保证同一几何得到确定 topology。
- 超过 scan budget 时，尚未完成的空间 fail-open 为 ambient，不能凭不完整 flood fill 创建有限密闭库存。
- available volume 是每 cell 的几何 open volume 减逐格 exact marker volume 与统一固体 occupancy，再乘 `cellSize³`；平滑液体 fraction 不结算气体容量或绝热功。单格 residual 只在局部拓扑归属时钳在几何范围内；完整闭域的每个节点按它实际拥有的 cell 结算带符号补量（有效 open volume 减权威 liquid），局部 marker 聚集只缩小这些 marker 所归属的腔体，不能越过实墙影响另一个腔体，也不能凭逐格钳制创造额外气相体积或绝热功。没有任何腔体拥有的残余体积（与任何气腔都不连通的部分充液格）才进入全域结算，因此节点容量总和仍严格等于“有效 open geometry 总量减全部权威 liquid carrier 体积”。runtime attribution 可沿 gas-centered（gas residual 至少占 open volume 一半）的连通部分充液格传播，随后只给相邻的一层 liquid-centered interface residual 归属，不能借 marker 空隙穿过整个液体 bulk；多 node 相遇时 ambient/较小 node 优先。

## 权威数据

- 每个密闭节点以 canonical minimum-cell key 和完整 cell membership 标识。
- 每个气体组分保存稳定 component ID、标准体积 nanos、独立质量 kg、可选 species 和 chemical mass residual kg；气体的两个质量列都不使用凝聚相 solver mass weight。
- source、sink、质量 roundoff 和 residual roundoff 是独立审计列。
- ambient 节点不持有有限库存；开放域中新密闭腔从环境建立时明确记 external source。已启动闭域内没有旧 owner 的新 cavity 是显式真空节点，其 component 库存、质量和 `U` 均为零，不得伪造 ambient source。

`GasRegistry` 规范化 component 定义和 ambient fraction；ambient fraction 必须按 exact rational 精确和为 1。标准态固定为 `101325 Pa`、`293.15 K`。

每个 component 分别定义标准态 kg/m³、允许承载的 species、默认 species quanta/m³ 和质量 residual/m³；默认 species 必须属于该 component 的允许集合。

创建 ambient inventory 时用 largest-remainder 对整数 nanos 确定分配。

component 与 species 的隶属关系是 world-local registry 原始数据，不由运行时库存临时推断。相变 source、拓扑 remap、恢复和 source/sink audit 都必须在发布前验证该关系；一个已知 chemical species 也不能挂到未声明它的 gas component。

气液相变只把同时被 component 允许且具有 host gas thermal offset 的 species 视为可挥发，其他 species 留在凝聚相。允许集合非空只声明能力，当前功能布局中实际出现 species 时仍必须同时存在匹配的 chemical registry 和 host gas thermal species offset。

`AirNetwork` 对节点、库存、可用体积、edge 和物质审计维护同一个单调 revision。topology remap 绑定规划时 revision；提交前 gas inventory 与 gas-energy 两个 owner 必须同时接受各自 generation，之后才发布任一侧。几何仍相同但库存、体积、edge 或 `U` 已改变的旧计划必须整体拒绝，不能覆盖较新的 owner 原始数据。

表示硬上限为：registry 64 个 component、单 node 32 个 component、单 component 16 个 species、单 region 65536 个 sealed node、stable ID 128 字符、单 extension 16 MiB。到达上限时拒绝候选或 fail-open 到 ambient；不能丢掉最小 component 继续运行。

node ID 只是当前 topology 的稠密 ordinal；持久身份由 cell membership 和 minimum-cell key 决定。component stable ID、standard-volume nanos、kg 和 species/residual 独立保存，不能从理想气体状态方程反算库存。

凝聚相与气相搬运 chemical residual 时，只在相边界用该区域冻结的 `cellSize³` 做 signed solver-weight/kg 换算；气相内部输运、分裂、合并和持久化始终保持 kg。

## 拓扑变化

拓扑重扫以重扫前后节点的实际 cell lineage 规划 merge、split、vent 和新腔初始化：

- merge 汇总完整库存和能量；
- split 按几何份额分配，精确余数只落入一个确定节点；
- 开放域中打开或消失的腔体把实际库存记入环境 sink；密闭域中不产生 source/sink，失去拓扑去向的库存按剩余腔体容量权重转移，不记为丢失；
- 开放域中从上一代 ambient lineage 被夹断的实际体积按环境组成记 source；若同一新节点还继承既有 sealed node，则环境来源体积与旧节点体积、库存和 `U` 分列合并后，才对最终几何差额结算功；同一节点中上一代无 owner 的本代体积仍是零库存真空并进入 inherited volume；
- 相变产生或消耗的 water vapor 通过同一组件库存与 source/sink 审计提交。

部分充液界面的短时拓扑抖动使用 runtime lineage 减少虚假空气周转，但不能改变物质账本。新 full-air child 没有同格 membership overlap 时，只能继承同格或跨开放 face 相邻的旧 runtime lineage。

**继承只在旧 node 已经不拥有任何本轮开放 cell 时发生**：仍拥有开放 cell 的旧 node 一律按 membership overlap 匹配，不会把库存分给一个新出现的空腔。因此已启动闭域里无旧 owner 的新 cavity 按契约是显式真空节点，库存、质量与 `U` 均为零，不伪造 ambient source。

旧 node 的 full-air membership 完全消失且旧 residual component 仍与新 gas component 相邻时，从原 interface cells 返回该新 lineage。若该 residual component 具有正容量但不接触任何新 gas component，它必须继续拥有原有限库存：该 component 的 partial owner cells 进入本代 topology membership，并按 minimum cell 与其他节点一起重新 canonicalize；若分裂成多个不连通 residual component，则按各自实际容量规划库存 split。

这样不会把无法以 full-air cell 表示的真实气泡删除或任意并入另一节点。marker bulk 的普通空隙也不需要专门识别：它即使成为 component 也拿不到库存，消失时又按上一条规则把库存交回可达腔体。

稳定 topology 快路每次仍从当前 exact residual volume 重新分配 interface lineage；相变新打开的微小气隙不能沿用上一轮空 residual 的归属，也不能被当成凭空新 node。

闭域完成初次环境库存建立后，任何 topology source/sink、正容量有限库存失去 owner、有限库存被压到零容量或 scan budget 耗尽都以 typed gas-topology failure 拒绝完整候选；生产域进入 `NUMERICAL_PAUSE`，不会让该失败穿透 server tick，也不会提交部分 remap。

注液只能改变现有库存的容量、拓扑分配和压缩功。

气液相变若只截断或扩充既有 marker，marker cardinality、cell membership、solid/cut-cell 与 granular occupancy 都没有改变，因此 air-cell 连通性和 interface lineage 也严格不变。

该中间候选用实际提交前后 float marker 体积差更新被编辑 cell 的 exact marker sum，再沿现有 node membership 重新汇总连续 available volume；相变期间保留联合守恒的 gas `U`，并与 node volume 一次提交；后续实际运动的容量变化才结算等熵功。

只有 marker 新增/删除或其他结构变化才重新 flood fill、overlap 和 remap。logical step 末以及 capture 前仍从完整 carrier geometry 重扫，稳定路径不是持久 topology 的替代原始数据。

重扫先建立旧 node 与新 node 的实际 cell overlap，再纯规划全部 transfer：

- 一对一 lineage 携带完整 node inventory；
- 多对一 merge 对 component、species、mass/residual 和 U 分列求和；
- 空库存节点若并入有限气体，旧真空控制体积也进入 inherited volume；本代新出现且上一代没有 sealed/ambient owner 的体积同样作为新真空控制体积进入该列。气体向这些体积的自由膨胀保持 `U` 不变，不能误记为可逆边界功；真空被载体完全挤至零体积且没有后继时则没有物质、`U` 或控制体积需要转移；
- 一对多 split 按 overlap/available-volume 整数权重分配，最后一个非空 child 接收 raw remainder；
- 连到 ambient 的旧有限库存份额进入 sink；只有从上一代 ambient lineage 截留的体积按 ambient composition 进入 source，无 owner 的 sealed volume 是显式真空；
- topology 与 thermal U 的 transfer plan 必须一起可提交，否则 network 保持重扫前状态。

## 压力与热耦合

- 无热状态时使用等温状态方程和有限速率孔流。
- 热状态存在时，气体总内能与节点库存共同派生温度、绝对压力、密度和声学闭合；孔流同步搬运组分与能量。
- 气液相变用 water-vapor 分压计算饱和条件，受状态方程、潜热、库存、容量和批预算共同限制。有限非负总压包括真空；它不被误当作固液 `T_eq(p)` 曲线的查询坐标，液气方向只由液温给出的饱和压与实际 vapor partial pressure 决定。
- 一个界面 cell 可以同时含多种合法 liquid carrier。某条气液 transition 只结算其目标 liquid 的体积、质量、组成和相变能量；同格其他 liquid 原样加入该 cell 的 LTE heat-bath inventory，并计入冷凝容量，但不被改材料、不被并入目标组成，也不能仅因存在就触发候选暂停。
- 所有 candidate 必须能由 world thermal registry 的连续状态函数从权威 `U` 唯一反演；声明 caloric 温区之外沿最近端点 `cv` 延拓，不因标定端点暂停。凝聚相的 `T_eq(p)` 在数据内插值、数据外沿端点割线延伸，详见热学专题。
- 一个 liquid cell 可同时邻接多个独立 sealed node。gas topology 继续保留各 node 的库存和 `U`，phase planner 不合并它们；每个 pressure interval 只向绝对分压失衡最大的可执行 node 提交一次 cell 交换，随后由下一 projection 同步该体积变化。
- mechanics 可从所有液气界面选择一个统一 sealed-node gauge reference，在 PCG 内只求相对压力并在绝对观察/热学边界加回；该数值换元不修改 node inventory、U 或不同 node 间的压差。接触液体的有限 node 会得到当次 projection 的集总可压缩 pressure unknown；它是由同一 EoS 线性化的 scratch，不是新的权威 node pressure 列。

当前 `AirNetwork` 是有限体积热力学与孔流网络模型；空间 FLIP 网格承载不可压凝聚相。有限 node 与液体接触时已用 `C_V = V*beta_s` 作为集总储存项进入同一对称 pressure block system，液体侵入/退出气腔的面通量与 node 压力响应因而在本子步隐式闭合；等温 `beta=1/p_abs`，热状态 `beta_s=1/(gamma*p_abs)`。

活动颗粒的占据率支撑涉及有限气体节点时，该节点也进入压力系统。多个支撑单元属于同一节点时先合并体积导数，再与颗粒有限质量组成对称压力项；气体容量变化和颗粒受压使用同一份导数。离散方程见 [颗粒压力耦合](granular-state.md#统一排水与压力)。

气腔节点按集总参数处理，未引入空间速度自由度与腔内混合层。后续迁移到空间动量内核时，需保留精确组分、质量与能量账本，并依据状态方程闭合密度与可压缩散度。

空间化时 node inventory 与逐 cell inventory 不能同时成为原始数据。进入空间内核的连通气体必须由逐控制体的 component nanos/kg/species/residual、动量和总能量唯一持有；`AirNetwork` 只能在未空间解析的腔体继续持有 exact node 总账，或作为逐格总账可证明的 coarse projection。两种表示之间的切换必须是同一候选内的守恒 repartition，不能先复制 node 再让两侧各自演化。

### 状态方程

无热状态的等温 gauge pressure 为：

```text
p_gauge = P0 * (Vstd / Vavailable - 1)
rho_gas = total_mass_kg / Vavailable
```

热状态使用节点权威 U 反演 `T`，再计算：

```text
p_abs = P0 * (Vstd / Vavailable) * (T / T0)
rho_gas = total_mass_kg / Vavailable
R_total = P0 * Vstd / T0
gamma = 1 + R_total / C_v,total
K_s = gamma * p_abs
c = sqrt(K_s / rho_gas)
beta_s = 1 / K_s
```

`GasThermalModel.HydrodynamicState` 是上述局部 ideal-gas mixture 闭合的唯一实现：`C_v,total` 来自同一库存和权威 `U` 所在 caloric segment 或端点延拓，`R_total` 来自同一 exact standard volume；绝热体积模量 `K_s`、声速 `c` 与压力 compliance `beta_s` 都是状态方程导数，不是 CFL 调参或材料特例。

caloric 标定端点不截断内禀温度，相平衡曲线继续拥有自己的压力数据域。若要支持非理想气体，应扩展数据驱动的状态方程及其导数，不能在 solver 中按 component ID 修正压力。

零库存 sealed node 表示真空：`p_abs = 0`、`rho = 0`、`U = 0`，几何体积变化不产生绝热功。真空的温度没有热力学定义；只读显示使用区域插入温度作为约定值，不能据此反算库存。孔流可按有限速率把环境或相邻节点的真实库存搬入真空。

等温与热状态都不钳制派生压力：正 available volume 直接使用状态方程，有限库存落入零 available volume 是奇异候选并显式拒绝。非空库存的绝对压力因而始终为正，可连续逼近真空，也可表达有限的高压状态；算法不再以固定压力窗替代状态方程。

无传质绝热体积变化按分段常 `c_v` 的 exact ideal-gas isentrope 计算新温度与 `delta U`，通过 `∫Cv(T)/T dT = R ln(V_initial/V_final)` 解析穿越所有 caloric segment 及其端点延拓；有限体积比不会得到数学上精确的 `0 K`，但可低于 1 K 并在界面舍入显示为零，只有路径低于 binary64 最小正常正数或不再有限才拒绝。

非空但接近绝对零度的节点仍按库存/容量/`U` 得到真实近真空压力。若其顺应性大到不能表示为 float PCG gas row，本次 projection 将该真实压力作为 Dirichlet 边界，不创建伪造的有限顺应性，也不抬高温度或压力；随后 gas topology remap 仍从实际新旧容量 exactly-once 结算等熵 `delta U`。只有可表示的正常节点进入联合 liquid/gas pressure unknown。

### 孔流

部分开放 face 聚合为面积 `A` 的 edge，流量采用 CONTAM 型孔流：

```text
Q = Cd * A * sqrt(2 * abs(delta_p) / rho_source)
```

一个 step 的 transfer 会被共同平衡压力所需的最大份额限制，不能越过均压点。标准体积 nanos 用整数结算；热状态同时搬运源侧焓 `h = u + p*v`。ambient 流入记 source，流出记 sink；节点间转移不记外部源汇。

休眠观察复用这项整数均压限制，读取当前热力学压力而不修改库存。全部 edge 的均压可转移量为零时，气体允许休眠；存在浮点压差但已达到整数均压状态的节点无需反复执行空步骤。仍有均压余量的细小孔保持活动，判断与孔面积、单步时长无关，避免把低流率误认为平衡。`air-network` 同时验证均压后连续空步骤、环境流入/流出，以及微小孔的非平衡状态。

## 持久化

气体作为完整热区域 generation 的 required payload 发布。快照和逐 section payload 同时保存 `cellNodeOrdinal`（拓扑 membership）与 `cellLineageOrdinal`（包含部分充液格的气体归属）。两列使用同一 minimum-cell 顺序的节点 ordinal；lineage 的 `-1` 表示无归属，`0` 表示 ambient。恢复时从已提交的液体、颗粒和 coherent 库存共同推导几何后重扫。气体节点先于其热能恢复，因此重扫直接读取同一输入热快照中的 coherent 体积，避免使用尚未恢复的热状态，并用完整 lineage 保留仅存在于部分充液格中的气泡。随后要求节点数、minimum-cell key、逐格 membership、库存和能量 ordinal 一致；差异归入状态恢复失败。

持久 payload 还包含 registry fingerprint、unit identity、component palette、节点总账和 source/sink/roundoff 审计；显式真空 node 以空 component inventory 原样保存。membership 的正 ordinal 必须与同格 lineage 一致，缺失或越界的 lineage 拒绝恢复。

gas registry fingerprint 同时绑定每个 component 的允许 species 集合；不匹配时原样保留并锁定。edge、pressure、temperature 和 flow velocity 从完整库存与几何重建。

管理员 `VENT_SEALED_GAS` 是一个联合持久候选：所有 sealed component nanos/kg/species/residual 按实际值进入 gas escaped sink，所有 gas node `U` 按实际值进入 thermal external sink。

操作保留由闭壳几何派生的完整 node membership、available volume 与 edge，但把每个 node 的 component inventory 和对应 `U` 精确置零，因此提交结果是显式密闭真空而不是 ambient-connected 特例。

plan 同时绑定 `AirNetwork` revision 与完整 thermal owner revision；commit 在第一次 mutation 前预检两者，不能先发布 thermal sink 再发现 gas topology 已 stale，也不能用旧 audit successor 覆盖新的传导或边界热账本。

真空节点可以 capture、恢复、清空凝聚相、再次排气并在显式 resume 后继续运行；后续注液、蒸发或拓扑 merge/split 只能从真实 transfer/phase source 建立新库存，不能用环境默认库存补回。
