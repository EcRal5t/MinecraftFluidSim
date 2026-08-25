# 气体状态契约

## 模型边界

- 开放空间连接无限环境节点；密闭空间保存有限气体库存。
- controller 的已验证闭壳 footprint 可声明 sealed domain boundary。命令临时域保持环境开放。
- `AirCavityScanner` 只在当前网格预算内 flood fill，不读取未加载世界；部分充液格的剩余空间参与 runtime available volume。
- 节点 pressure、density、temperature、孔流速度和边是派生运行态，不是独立库存。

### Cavity scan

air cell 必须同时满足：非 solid、`markerCount == 0`，且扣除 cut-cell、granular/coherent occupancy 后仍有正 open volume。六邻域 flood fill 只跨越接近全开的 face；部分开放 face 不合并 component，而是形成有限速率 geometric edge。

- controller 已验证闭壳时，solver 外边界不自动判作 ambient；命令临时域和真实缺口仍连接 ambient node 0。
- scan 按固定邻居顺序执行，节点以 canonical minimum-cell key 排序，保证同一几何得到确定 topology。
- 超过 scan budget 时，尚未完成的空间 fail-open 为 ambient，不能凭不完整 flood fill 创建有限密闭库存。
- available volume 是每 cell 的几何 open volume 减液体 fraction 与统一固体 occupancy，再乘 `cellSize³`；部分充液格的 residual gas volume 归属相邻最小 node，ambient 优先。

## 权威真值

- 每个密闭节点以 canonical minimum-cell key 和完整 cell membership 标识。
- 每个气体组分保存稳定 component ID、标准体积 nanos、独立质量、可选 species/残差。
- source、sink、质量 roundoff 和 residual roundoff 是独立审计列。
- ambient 节点不持有有限库存；新密闭腔从环境建立时明确记 external source。

`GasRegistry` 规范化 component 定义和 ambient fraction；ambient fraction 必须按 exact rational 精确和为 1。标准态固定为 `101325 Pa`、`293.15 K`。每个 component 分别定义标准态 kg/m³、允许承载的 species、默认 species quanta/m³ 和质量 residual/m³；默认 species 必须属于该 component 的允许集合。创建 ambient inventory 时用 largest-remainder 对整数 nanos 确定分配。

component 与 species 的隶属关系是 world-local registry 真值，不由运行时库存临时推断。相变 source、拓扑 remap、恢复和 source/sink audit 都必须在发布前验证该关系；一个已知 chemical species 也不能挂到未声明它的 gas component。允许集合非空只声明能力，当前功能布局中实际出现 species 时仍必须同时存在匹配的 chemical registry 和 host gas thermal species offset。

表示硬上限为：registry 64 个 component、单 node 32 个 component、单 component 16 个 species、单 region 65536 个 sealed node、stable ID 128 字符、单 extension 16 MiB。到达上限时拒绝候选或 fail-open 到 ambient；不能丢掉最小 component 继续运行。

node ID 只是当前 topology 的稠密 ordinal；持久身份由 cell membership 和 minimum-cell key 决定。component stable ID、standard-volume nanos、kg 和 species/residual 独立保存，不能从理想气体状态方程反算库存。

## 拓扑变化

拓扑重扫以重扫前后节点的实际 cell lineage 规划 merge、split、vent 和新腔初始化：

- merge 汇总完整库存和能量；
- split 按几何份额分配，精确余数只落入一个确定节点；
- 打开或消失的腔体把实际库存记入环境 sink；
- 新腔体按环境组成和当前可用体积记 source；
- 相变产生或消耗的 water vapor 通过同一组件库存与 source/sink 审计提交。

部分充液界面的短时拓扑抖动可使用 runtime lineage 减少虚假空气周转，但不能改变持久 membership 或物质账本。

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

无热状态保留早期等温网络的数值正则：available volume 最低取 `1e-9 m³`，gauge pressure 限制在 `-0.99 P0 .. 20 P0`，并分别累计 floor/ceiling diagnostic；这只限制传给机械求解器的派生压力，不改气体库存。热状态不使用该钳制修复候选，而把相同压力窗作为有效域，越界安全暂停。无传质绝热体积变化按 constant-`c_v` ideal-gas isentrope 计算新温度与 `delta U`；跨 caloric segment 或有效域时整步暂停。

### 孔流

部分开放 face 聚合为面积 `A` 的 edge，流量采用 CONTAM 型孔流：

```text
Q = Cd * A * sqrt(2 * abs(delta_p) / rho_source)
```

一个 step 的 transfer 会被共同平衡压力所需的最大份额限制，不能越过均压点。标准体积 nanos 用整数结算；热状态同时搬运源侧焓 `h = u + p*v`。ambient 流入记 source，流出记 sink；节点间转移不记外部源汇。

## 持久化

气体只作为完整热区域 generation 的 required payload 发布。恢复时从已提交 carrier geometry 重新扫描拓扑，并要求节点数、minimum-cell key、逐格 membership、库存和能量 ordinal 全部一致；任何差异都锁定，不猜测 remap。

持久 payload 还包含 registry fingerprint、unit identity、component palette、节点总账、逐 section membership 和 source/sink/roundoff 审计。gas registry fingerprint 同时绑定每个 component 的允许 species 集合；不匹配时原样保留并锁定。edge、pressure、temperature、runtime lineage 和 flow velocity 可从完整库存与几何重建，不进入 checkpoint。
