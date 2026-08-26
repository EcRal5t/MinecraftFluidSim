# 渲染、性能与生产 GPU 实施计划

## 目标

把当前客户端重建和 CPU 规范求解从“可验证原型”推进到稳定游玩的实现，并在量化 profile 后交付生产 GPU 数值后端。渲染、网络、调度和 backend 都只能消费已提交状态；任何优化都不能改变库存、事务或失败语义。

## 渲染质量

### 问题分层

看到洞面、黑三角、薄水消失或液液界面闪烁时，先分类：

1. 服务端 marker/体积/相身份错误；
2. snapshot 量化、裁剪、generation/sequence 错误；
3. mesher 拓扑、共享界面归属或 winding 错误；
4. GPU vertex/index buffer 生命周期错误；
5. 法线/材质/深度混合的纯展示问题。

不允许用增加 density、降低 iso threshold、强制封洞或双面渲染掩盖前两类错误。

当前 `FluidMaterialSurfaceMesher` 中的 marker interior floor、phase-tail threshold、interface inset 等展示启发式必须进入审计。它们若只是为了让薄体“看起来存在”或避免两层面重合，就不能作为最终P1/P3几何；先证明snapshot携带了足够的服务端界面信息，再删除这些补丁。颜色/法线fallback可以保留为纯shading措施，但不得增删triangle或移动占据边界。

### D01：surface snapshot 契约

byte volume与marker mask不足以唯一恢复薄自由面和服务端PLIC。扩展协议为有界、按interface cell和shared face稀疏编码的展示几何输入：

- region/dimension/network generation/sequence与profile/layout fingerprint；
- cell坐标、cut-cell raw/aperture摘要、总liquid fraction；
- 服务端当前PLIC plane的规范化normal与offset，明确固定点量化范围/rounding；它定义本cell内的piecewise-linear half-space，不承诺与邻cell plane共面；
- 每个不可互溶phase pair的canonical IDs、pair plane/fraction、label side与单一owner；同cell全部label partition必须无重叠且覆盖其声明liquid volume；
- 每个shared grid/cut face的canonical identity及两侧cell identity；face topology不由客户端按收到顺序临时配对；
- material palette及只读chemical/granular/thermal attributes；
- payload总cell/byte、每cell label/plane、每shared face输入segment、planar-arrangement vertex/polygon、triple junction以及最终mesh vertex/triangle硬上限和checksum。

snapshot由已提交state重建并带同generation/sequence，不保存pressure或让客户端反算inventory。非interface满液体格可以继续紧凑编码；任何非零尾量若因协议量化无法表示，必须通过显式`subquantum-present`/cell record保留拓扑存在性，而不是在mesher把scalar强行抬高。量化误差只影响展示位置，server actual nanos不变。协议量化后的plane、cut polyhedron与shared-face identity就是mesher唯一输入；validator用同一固定点值重放，不能一处用double原值、一处用量化值。

协议协商只接受完整默认thermal layout及其extensions；未知required surface字段拒绝该frame。迟到base/extension不能跨sequence拼接，chemical/granular/thermal attribute必须与base cell table同一identity。

### D02：确定拓扑 mesher

展示几何定义为所有cell内“cut polyhedron再按outer/phase PLIC half-space裁剪”所得有标签闭多面体的并集边界。mesher拆为纯数据阶段：decode/validate → build labeled clipped polyhedra → build one canonical planar arrangement per shared face → cancel equal-label coverage / emit differing-label patches → emit in-cell PLIC patches → topology-key index → triangulate → normals/attributes。关键规则：

1. outer plane只由总liquid PLIC生成，phase plane不改变外表面位置。
2. phase pair按stable phase ID排序，低ID为first，法向固定first→second；共享界面只生成一次。
3. cell/face/edge遍历使用lexicographic顺序。每个shared face只构建一次二维planar arrangement：把两侧label polyhedron在该face上的全部固定点线段叠加、求交并切成原子polygon；原子polygon两侧label相同则抵消，不同则按canonical pair只发一个有向patch。PLIC不连续产生的是这个face-owned transition patch，不是裂缝，也不移动任一cell内plane。
4. cell内PLIC patch和shared-face patch通过来源拓扑键共享顶点：`(face/edge identity, sorted generating planes, exact fixed-point/rational coordinate)`；index dedup只合并同一拓扑事件，禁止用距离epsilon或事后global weld修补不同事件。
5. 三相接触由同一个face arrangement交点生成canonical junction key；所有incident outer/pair patch复用该key。无法得到无重叠label partition、同一原子polygon出现三个side label或junction incidence不闭合时整frame失败。
6. 边界polygon只在domain/cut boundary或实际free/interface面结束。零面积、NaN、越bounds、同向重复triangle使fixture失败，不能静默删除到“看起来没黑面”。如果量化产生退化，修正snapshot量化或裁剪规则。
7. outer/interface不能靠`INTERFACE_INSET`错开；它们在真实三相接触线上共享顶点/边。opaque/cut、outer translucent和internal pair分开携带render category；默认renderer使用有界per-pixel A-buffer：先做fragment count/prefix-sum pass，容量通过后才fill，逐pixel按depth和canonical triangle ID排序并front-to-back合成。fragment容量来自协议/render预算；count越界时本帧不绘制MMM fluid surface，只显示结构化capacity overlay并保留完整mesh供后续相机帧重试，不能写partial fragment、回退无序alpha或靠depth bias隐藏重合。不支持所需fragment storage/atomic能力的客户端明确禁用MMM surface renderer并报告协议能力，不切回会增厚的旧路径。
8. normal先从几何face得到；面积/角度加权只在同一smooth group内累积，不跨phase/cut-wall sharp edge。坏normal回退到face normal，不删面。

所有裁剪和planar arrangement先做checked count/reservation；任何交点分母为零、fixed-point乘加overflow、成员超协议上限或输出超mesh预算都拒绝整个新surface frame并保留旧完整mesh/结构化诊断，不截断某个face或phase pair。BigInteger可以用于纯Java topology oracle/fixture；生产实现若改用checked 64-bit rational，必须逐fixture与oracle得到相同topology key/digest后才切换。

`Mesh`输出indexed vertex/triangle buffers、triangle category/phase ownership和canonical digest。客户端render adapter只消费这份mesh，不再同时维护一个按cell画水平水面的第二正常渲染路径；debug box/volume overlay保留独立开关。

### D03：mesh validator 与 frame ownership

validator对每类surface分别计算：label partition覆盖/重叠、shared-face原子polygon两侧label、index bounds、finite、area、unoriented duplicate、directed edge incidence、boundary loops、connected components、Euler characteristic摘要、winding、outer/interface overlap和bounds。封闭fixture要求每条edge incidence=2；开放fixture的boundary edge必须落在声明的domain/cut boundary；三相接触线按junction incidence规则允许并单独统计。canonical digest对排序后的exact topology key、量化vertex/triangle/category计算，重复构建必须一致。另一个client harness在固定相机轨迹下验证transparency fragment顺序、capacity overflow不产生partial draw且显示诊断、以及不同相机方向不出现category depth fighting。

客户端frame key固定为`(dimension, region UUID, network generation, sequence)`。每个extension先进入有界staging，只有完整同identity frame才替换current。规则：

- generation增大丢弃旧generation全部staging/buffer；同generation仅接受sequence严格增加；
- tombstone记录generation cursor并释放current/staging；旧包不能复活；clear-all按连接epoch清空；
- CPU mesh build可以worker化，但result入队时再次核对cursor；render-thread独占GPU buffer create/swap/close；
- 新buffer成功创建后原子替换引用，再释放旧buffer；失败保留旧完整frame并记录有界诊断，不显示半mesh；
- dimension切换/断线释放所有native buffer，validator计数不随重连增长。

最近门优先扩展`protocol`中snapshot identity/tombstone覆盖和`materials`中mesherfixture；不要为“render buffer没泄漏”塞进CPU solver测试，必要时用client harness/一次真实场景。

### 最小 fixture

建立可自动导出 snapshot/mesh 的确定 fixture：

- 开放自由面单池；
- 密闭顶部自由面；
- 稀薄单 cell/单 marker 尾量；
- cut-cell 斜面和墙角；
- 两种不可互溶液体的单共享界面；
- 相邻cell的PLIC plane在共享face给出不同trace，必须生成单一face-owned transition patch且仍watertight；
- 三种label在cell/shared face形成一个canonical triple junction；
- region remove、generation 替换和迟到 packet；
- chemical/granular/thermal extension 开关组合。

每个 fixture 检查：索引范围、零面积三角、非流形边、boundary loop、winding、一对 phase 共享面只生成一次、顶点/法线有限、mesh bounds 不越 region。黑三角的开放/密闭 fixture 必须分别存在，不能只靠截图。

### 实现切片

1. 给 `FluidMaterialSurfaceMesher` 增加纯 Java mesh validator 和确定摘要 hash。
2. 将labeled polyhedron clipping、shared-face planar arrangement、topology indexing、material/phase ownership和normal smoothing分成独立步骤和数据类型。
3. 外表面与液液界面分别分类，但都来自同一label-union boundary；phase pair canonical排序和法向只由稳定规则决定。
4. 法线以面积/角度加权邻接平滑，但不跨 sharp material boundary；坏法线回退只能影响 shading，不能删 triangle。
5. 客户端 frame 按 dimension + region UUID + network generation + sequence 管理；tombstone 后旧包不能复活资源。
6. buffer 替换在 render thread 有明确 ownership；释放旧 frame 不影响新 generation。
7. 颜色、indicator、temperature 是独立 read-only vertex/material attributes，不改变几何 occupancy。

### 视觉验收

自动 validator 通过后，只请求一个真实场景：同一闭壳内含自由面、斜 cut-cell 和不可互溶界面，玩家绕行观察。记录唯一命令/建造步骤、预期和截图；不要把视觉检查扩成常态全量门。

## 性能 profile

### 测量边界

先在 CPU 路径分别测量：

- P2G、pressure/PCG、G2P/advection/reseed；
- thermal/reaction/phase/granular/gas；
- collision capture、entity snapshot、world interaction；
- snapshot build、编码、发送、客户端 mesh；
- checkpoint capture、压缩、force、回读；
- JVM allocation/GC 和 native/render buffer。

每条记录 region 尺寸、cell/marker/carrier、active fraction、substep、PCG iteration/residual、snapshot bytes、wall time。设备 timestamp 只用于 GPU stage 对比，调度安全仍看 wall time。

避免在常态代码中保留高开销 profiler。可使用已有命令 transcript 或单独开发 runner；若创建可复用 Python 分析脚本，放 `pytool` 并登记 [`../../maintenance/pytool.md`](../../maintenance/pytool.md)。原始 profile 文件不应作为 architecture 规范。

### D04：可重放 profile harness

profile不是在真实世界随手看一次`/tick`。建立一个Java runner，消费冻结的`FluidDomainState`、collision/entity snapshot和确定step count；Minecraft adapter另有只读计时器测world capture/network/checkpoint。每条样本记录：commit、JDK/JVM flags、CPU/GPU/driver、profile fingerprint、seed、grid/cell size、各carrier count、active fraction、dt/substep、PCG iteration/residual、allocation/GC、stage wall time和candidate outcome。

固定规模：

| 名称 | interior | cells | 用途 |
|---|---:|---:|---|
| S | `6×4×6 BV` | `12×8×12 = 1,152` | P1单实验室，要求稳定20 Hz |
| M | `12×8×12 BV` | `24×16×24 = 9,216` | 当前默认单region预算与pacing判断 |
| L | `32×32×32 BV` | `64³ = 262,144` | GPU/范围评审，非P1承诺 |
| S4-mixed | 四个独立S region | `4×1,152` | P1 scheduler：一个moving、一个thermal/phase、两个idle；检查aggregate tick与饥饿 |
| S4-active | 四个独立S region | `4×1,152` | P3并发容量报告，不预先承诺全部20 Hz |

每个单region规模至少有idle、free-surface dam break、three-material、chemistry+granular、thermal+phase+sealed gas、moving-body六种state。S/M使用相同物理定义和cell size，不通过减少carrier字段伪装快。fixture state及期望inventory digest可重放；runner不写world/HEAD。Minecraft adapter另跑无observer、一个observer和两个observer观看同一region；snapshot应每generation只构建一次再按连接编码，报告每client bytes、总bytes、主线程capture/encode/send和client mesh/A-buffer memory。

采样协议不能写“warmup至稳定”后由执行者自行判断。每个state使用三个全新JVM fork、相同flags和seed：先warmup至少1,000 logical steps；随后以250-step不重叠window比较最近三个window的stage invocation count和总wall-time median，三者count相同且median相对合并median均在3%内才算稳定；最多warmup 5,000 step，仍不稳定则报告`INVALID_UNSTABLE_WARMUP`而不是挑快区间。稳定后固定采5,000 step；包含sleep transition的state另从同一冻结输入重放完整transition，不混入active percentile。保留每fork和合并的p50/p95/p99/max、allocation bytes/step、GC pause和peak resident/device memory。

D04第一次正式报告必须提交一个不可含糊的`P1 reference host` identity：CPU型号/物理核心与SMT、内存、OS、JDK/JVM完整版本与flags、电源模式、GPU/driver以及后台负载约束。本项目基准命令使用指定`D:\ProgramFiles\BienCreng\jdk-26.0.2`并保持Java 25 target。P1关闭引用该记录，不能每次换一台更快机器仍称“目标机器”；reference host变更需要在roadmap显式评审并同时保留旧/新报告。

### D05：P1 CPU关闭门

- S的全能力active场景在P1 reference host上p95 logical-step wall time小于现有40 ms pacing门，无200 ms step，且candidate/audit全通过；
- P1 reference host的client必须通过A-buffer feature/capacity harness和RV-01；不支持该能力的其他client得到明确renderer unavailable，不得因此让server更改物理profile；
- M可以进入REDUCED，但代表性场景不能连续三步越过200 ms自动暂停；报告各stage占比和pacing分布；
- S4-mixed在同一server scheduler中总main-thread capture/commit与已完成worker candidate的p95不越40 ms；四个region都在冻结pacing策略内获得step机会，两个idle region保持sleep且无starvation。S4-active只在P3报告明确容量/降频/暂停结果；
- idle S/M在无observer和有只读probe两种情况下得到相同motion/thermal sleep，probe不唤醒；目标至少80%体积/section按现有定义休眠；
- 零/一/两个observer时logical dt与inventory digest相同；无observer不构造payload/mesh，两个observer不重复构建server snapshot。每client与aggregate snapshot bytes不得超过`FluidProtocolLimits`；mesh build超capacity保留旧完整mesh，A-buffer count超capacity跳过本帧fluid draw并显示诊断，两者都不得提交partial资源；
- checkpoint和world capture单列计时，不能算进solver后再用GPU结论掩盖I/O瓶颈；
- 连续创建/删除frame、region load/unload后Java/native buffer计数回到基线，无generation泄漏。

这些是产品预算，不是物理算法常数。硬件信息与报告一起保存；若S不达标，P1不以GPU作为临时补丁，先按D06优化CPU规范路径。

### D06：基于证据的优化顺序

对每次优化记录before/after stage、allocation和inventory digest。允许：primitive scratch复用、dirty sparse list、canonical work queue、只在observer存在时构建snapshot、按正交activity跳过被证明零通量的模块。禁止：按材料ID跳过、截断small species/marker、减少ledger字段、降低PLIC/pressure精度或在budget后提交半queue。

dirty优化需要证明边界：cell在geometry、carrier、neighbor flux、gas lineage、reaction/phase、device contact或moving body变化时入队；从dirty转sleep前重新验证零通量/零驱动力。全域reference step与dirty step在相同input上比较库存digest和数值误差门。若hot loop临时分配占比显著，先改SoA/scratch ownership；若PCG占比主导，再评审preconditioner/worker/GPU。

### 优化顺序

1. 消除 hot loop 临时分配和重复全域重建。
2. 只处理 dirty/active cell、interface 和 carrier；保持确定边界。
3. 让静态域进入守恒 motion/thermal sleep，目标至少 80% 体积可休眠。
4. snapshot 只在观察者存在且序列需要时构造；网络与 mesh 可降频，物理 dt 不随之改变。
5. 再评估 worker-thread CPU candidate；世界 capture/commit 留在主线程。
6. profile 证明 pressure/P2G 等数值 hot path 达到停止门后才投入 GPU。

不因耗时高而跳过 advection、截断 species、减少 ledger 或降低 checkpoint 完整性。超过 40/200 ms 门时保留当前 pacing/暂停语义，直到代表性数据支持调整。

## 后端边界准备

### 完整 state 审计

在 GPU 实现前逐字段审计 `FluidDomainState`：

- 基础 marker SoA、material/phase、chemical、granular、coherent、gas topology/inventory、thermal U/audit、pending phase-work；
- cut-cell raw mask、配置、registry identity 和硬预算；
- moving-body request 与 candidate generation；
- 所有 source/sink/work/roundoff ledger。

CPU capture → restore → one step 必须与直接 CPU one step 在当前容差内一致。若 state 缺字段，先补纯 Java handoff 和 checkpoint 语义，再写 shader。

### D07：backend state 完整性表

审计不只列类名。为`FluidDomainState`建立字段表，每行记录owner、单位、cardinality key、capture source、restore target、candidate mutation、inventory/diagnostic分类、checkpoint presence和GPU representation。至少逐项覆盖：

- region/profile/registry identity、base generation、solver config和硬预算；
- raw cut micro-mask与固定/动态occupancy输入；
- marker position/velocity/volume/material mass/phase；
- chemical handle展开后的stable species/quanta/residual/indicator；
- active/deposit/coherent SVQ/kg/composition/porosity和各自`U`；
- gas topology membership/minimum key、component standard nanos/kg/species/residual和node `U`；
- liquid/solid/gas/device thermal audits、source/sink/work/roundoff、pending phase volume/work；
- finite device inventory/contact input；
- moving-body request、definition fingerprint与candidate per-body settlement；
- canonical operation queues/dirty state中任何会改变下一步结果的部分。

若某个dirty/cache可从权威state唯一重建，表中写明重建算法并在restore时丢弃旧cache；若不能，就必须进入state。pressure等仅本步派生场不捕获，但C03 pressure-dependent phase要求先projection的规则必须在restore测试中体现。

增加canonical `FluidDomainStateDigest`，按字段语义排序并覆盖raw integer/float bits，用于capture→restore相等、CPU direct vs backend handoff和故障报告；digest只用于测试/诊断，不作为inventory或checkpoint checksum替代品。

### D08：candidate 调度协议

`FluidStepRequest`固定包含region/profile identity、base generation、world geometry revision、entity snapshot revision、dt与deadline policy。backend返回完整`FluidStepCandidate`或结构化failure，不返回“pressure完成、thermal未完成”的partial。

主线程接受candidate时按顺序核对：service/profile仍READY → region/HEAD没有container/world事务 → base generation/geometry/chunk/controller仍匹配 → moving body stale policy满足 →所有inventory/audit/digest/预算有效 → 同generation `CommittedObservationIndex`完整且checksum通过 → wall-time策略允许。任一失败丢弃整个candidate并保留base；backend scratch可复用但不能被下一request误认成已提交state。CPU backend也从candidate生成同shape observation index，避免Query因后端不同走第二语义。

首个worker CPU实现一次region最多一个in-flight request。同region需要container、boundary或checkpoint mutation时先cancel/等待有界完成或拒绝外部操作，不能在两个candidate间merge。不同region可并行，但world capture和最终commit仍在主线程按canonical region UUID顺序处理，避免调度改变跨region可见顺序。

### 并发协议

主线程冻结 world/collision/entity 和完整 base state，创建绑定 generation 的 request。backend 在私有内存计算 candidate。tick boundary 只接受：

- region identity 仍相同；
- base generation 尚未被 container/world transaction 改变；
- controller availability 和 required chunks 仍有效；
- candidate 是紧邻 generation、完整账本通过、wall-time 未超策略；
- moving body stale policy 已满足。

拒绝 candidate 时 base state 完全不变。不能合并半个 GPU pressure 结果与新的世界碰撞。

## 生产 GPU 后端

### 实现范围

生产 backend 必须实现完整 logical step，不是把纯水 pressure kernel 注册成可选后端。可以分阶段开发 kernel，但在覆盖完整 state 前只能作为 probe/validation 使用。

推荐内部顺序：

1. SoA 上传、generation header、bounds/canary 与确定 readback。
2. P2G/MAC、cut-face、variable density pressure/PCG。
3. G2P/advection/CCD/reseed。
4. material/chemical side columns 和 surface forces。
5. granular/coherent/gas topology 与交换。
6. thermal/reaction/phase/pending work。
7. moving solid boundary。
8. candidate audit、compact snapshot 与完整 checkpoint readback。

这不是发布顺序；只有全部覆盖并通过完整候选门后，才允许生产选择 GPU。

### D09：GPU 数据与kernel合同

先从D07字段表生成/维护单一layout specification；Java binding、shader struct、alignment/stride、descriptor和readback header必须引用同一常量来源或由验证测试逐字段比对。每个buffer header包含schema、profile fingerprint短校验、region/base generation、element count、capacity和canary。上传前验证count/capacity，shader内所有append用checked reservation；overflow设置candidate failure flag且不写越界。

kernel之间使用明确stage barrier和不可变input/mutablecandidate分区。integer nanos/SVQ/quanta与stable ordering操作优先使用prefix-sum/segmented reduction得到canonical destination；不能依赖atomic到达顺序决定最后余数接收者。浮点reduction采用固定tree/compensated策略并把roundoff送入与CPU相同审计分类。

production eligibility先冻结并记录device feature contract：storage/workgroup/dispatch上限、subgroup语义、32/64-bit integer arithmetic与atomic、所需浮点精度、timestamp可用性和driver identity。exact `long`库存不能经float/double round-trip；设备无原生64-bit算术时只允许经过CPU逐操作对照的checked two-word 32-bit limb实现，carry、比较、乘除、prefix-sum与overflow均有独立fixture。若某个exact operation、确定reduction或所需容量不能在该device上证明，GPU backend不注册production并回到CPU；不能按材料或carrier选择性丢字段。

每个内部kernel增量仍只注册为probe：

1. 上传/readback先用D07 digest round-trip；
2. mechanics阶段输出pressure residual/divergence和marker momentum对照；
3. 每增加carrier，补它的exact inventory/audit对照；
4. logical step全部覆盖前，`FluidSolverBackend` production registry不出现GPU选项；
5. 完成后另一个提交才增加backend selection policy，默认仍CPU直到D10故障门通过。

GPU shader不读取world/profile文件或构造registry；主线程把冻结的稠密definition tables作为只读buffer上传，stable ID只用于header/digest与CPU边界。

### D10：readback、checkpoint与设备故障

常态resident state使用一个固定CPU合同，不留给实现者二选一：每个GPU candidate在主线程可接受前，必须完成同generation的紧凑`CommittedObservationIndex`与audit header readback。index覆盖现有同步Query的逐cell typed quantity/species/residual/solid/gas/thermal字段、controller ledger摘要、gas node stable key和所有结构化availability所需信息；它不是solver state，不能反向构造marker或库存。缺片、checksum或generation不一致则整个candidate不提交，因此Query始终同步读取当前已提交generation、不唤醒也不触发GPU命令。

需要修改当前state的container/public transfer、world action、finite-device/lifecycle操作，以及checkpoint、unload和backend switch，必须先阻止新GPU step并分片readback当前完整generation；完整D07 digest/audit通过后切成CPU-owned state，再按正常WAL/candidate/HEAD提交，完成后才可重新上传。不能让写事务对仅有observation index的状态规划，也不能把readback延迟隐藏成部分提交。只读probe/console使用observation index，不做完整readback。

每个durable checkpoint成功后保留同generation CPU-readable decoded cache；它只是已提交HEAD payload的内存副本，不是第二commit point。额外周期readback若没有随后发布HEAD，只能缩短同一进程内backend切换成本，device lost后仍不得把它冒充durable state。不能在device lost后声称从已经丢失的resident buffer读取：region回到最近完整CPU-readable HEAD并丢弃其后的纯GPU runtime generations，向玩家报告`PAUSED_GPU_STATE_LOST`。所有跨域source/sink/work在提交前都强制CPU化并发布HEAD，因此不能落在被丢弃区间。服务端为该region发布严格更大的network generation和tombstone，再发送恢复state；绝不能发送较小sequence期待客户端回滚，也不能从客户端surface snapshot恢复。

readback fragment header固定包含region/profile/base/candidate generation、ordinal range、payload kind、byte count和checksum。重叠、缺片、乱序可以暂存但不能构造state；超预算/timeout丢弃整组。完整state重建后先跑D07 digest与inventory audit，再交checkpoint或CPU backend。

故障注入覆盖pipeline创建、descriptor mismatch、shader failure flag、timeout、audit mismatch、readback每片、device lost、backend switch前后和server stop。每一点只能观察到旧完整state或新完整state；source/sink/work不能重复。GPU错误报告保存有界stage/header/residual，不dump全部玩家库存或无限buffer。

### 设备常驻与 readback

- marker、grid、side columns 和临时 PCG 向量常驻设备；常规 step 不全量回 JVM。
- inventory ledger使用设备reduction，并回读紧凑audit/candidate header与同generation `CommittedObservationIndex`；不能只信shader success。
- 客户端 snapshot 可在设备侧生成紧凑展示载荷，但服务端 generation/协议验证仍在 CPU。
- 完整state在checkpoint、unload、backend switch、需要完整truth的诊断以及任何container/public/world/device/lifecycle写事务前分片回读；Query/probe只读observation index。
- readback 每片绑定 region/generation/ordinal range/checksum；缺片不构造 partial state。

GPU 不取得磁盘、world、actor 或 HEAD 权限。即使 renderer 与 compute 使用同一物理设备，也不能让客户端 mesh buffer 成为服务端库存。

### 数值对照

GPU 无需逐 bit 等于 CPU，但每个 fixture 必须同时满足：

- 独立库存账本使用同一 exact/容差规则；
- pressure residual、divergence、volume drift、momentum 与 U 误差在预先冻结上限内；
- 相变 exact batch、integer nanos/SVQ/quanta 和 stable ordering 不因并行 nondeterminism 改变；
- 同一输入重复运行的差异不越过产品误差门；
- CPU fallback 从完整可读 state 继续时不重复 source/sink/work。

对 reduction、atomic 和 scan 明确确定性策略。不能用放宽守恒容差掩盖 race。

### 故障与回退

- shader compile/pipeline 创建失败：区域保持 CPU 或暂停，不修改后端选择的持久真值。
- step timeout：丢弃整个 candidate，保留 base state；达到策略门后切 CPU 或 PAUSED。
- device lost：停止提交，释放不可用资源；从最近完整CPU-readable state/checkpoint恢复并提升network generation、先发tombstone再发恢复snapshot。没有完整状态时LOCKED/PAUSED，而不是从客户端snapshot重建；客户端已经看见的较新纯运行时frame不得继续存活。
- GPU audit 失败：保存有界诊断，禁止 HEAD/checkpoint；可在相同 base 上运行 CPU 对照定位。
- backend switch：只发生在完整 generation 边界，并验证 capture/restore ledger。

## 交付门

1. 代表性 profile 明确 CPU/GPU break-even 和主要耗时占比。
2. `64^3` cell 目标有容量、显存、readback 和 20 Hz/pacing 报告；不保证所有物理组合都达 20 Hz，但必须有产品预算。
3. CPU/GPU 对照覆盖 pure/material/chemical/granular/thermal/gas/phase 与 moving body。
4. GPU超时、错误candidate、device lost、observation/full readback、checkpoint和写事务CPU化中断均只得到完整旧态或完整新态；回退后客户端接受更高network generation且不保留未来mesh。
5. 无 GPU、驱动不支持或 probe 失败时 CPU Mod 仍可正常游玩。
6. 文档明确 CPU 继续是规范/恢复实现，生产 GPU 是计算后端而非第二套世界系统。

### 工作包、入口与最近门

| 工作包 | 首要入口 | 最近 selector/验证 |
|---|---|---|
| D01 | `FluidSnapshotPublisher`、`network/*SnapshotPayload`、`FluidProtocol*` | `protocol` |
| D02 | `FluidMaterialSurfaceMesher`、新的surface snapshot值对象 | `materials`，改变phase输运才加`surface-tension` |
| D03 | client ledger/renderer/buffer owner、mesh validator | `protocol,materials`；native生命周期用client harness |
| D04/D05 | 新profile runner、`FluidStepCandidate` stage stats、Minecraft adapter timers | 各场景最近solver selector；报告本身不进常态门 |
| D06 | 实际hot path类 | 只跑被优化领域selector和同input reference对照 |
| D07/D08 | `FluidDomainState/StepRequest/StepCandidate/CpuFluidSolverBackend`、scheduler | `thermal`作为完整carrier handoff门，再按缺失字段追加直接领域 |
| D09/D10 | `gpu-probe` binding/shader/readback、新production backend adapter | GPU probe harness + CPU对应selector；注册改动加`protocol/lifecycle` |

D01–D04、D05的S/M/S4-mixed门属于P1；D05不达门时，直接针对失败hot path的D06切片也成为P1前置。P3复用同一D04 harness增加S4-active/M/L与GPU报告，不重新定义采样协议；D07–D10不阻塞P1。若CPU已满足产品门，仍可完成GPU作为既定长期项，但不能以“未来GPU”推迟修复P1的mesh或主线程I/O问题。

## 推荐提交顺序

1. D01 surface snapshot与协议identity；先不切默认renderer。
2. D02 indexed mesher与fixture，D03 validator/digest；对旧renderer做同snapshot只读对照。
3. 切换默认renderer，删除marker增厚/interface inset路径；再提交frame/buffer ownership。
4. D04 profile harness、reference host identity与S/M/S4-mixed baseline报告，不在同一提交优化。
5. D06按最大已证实stage逐项优化，每项保留before/after与reference digest。
6. D07 state字段表/digest并补齐handoff，D08 worker/candidate协议。
7. D09逐kernel扩展probe；每个提交都有CPU对照但不注册production。
8. 完整logical step后独立注册GPU backend，再提交selection policy、readback/checkpoint与D10故障恢复。
9. 最后运行RV-01：同一闭壳含薄自由面、斜cut-cell、不可互溶界面、chemical/granular/thermal attributes，绕行并切dimension/重连；预期无洞面/黑三角/旧generation复活，日志保存mesh validator摘要和frame key。

生产backend注册、选择策略、设备丢失恢复和文档必须是独立的最后几个提交，不与shader初次实现混在一起。
