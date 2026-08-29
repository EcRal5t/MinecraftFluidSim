# 代码架构重整实施提案

## 文档定位

本文是一次**保持物理、库存、存档和协议语义不变**的结构性改造规格。它面向后续执行改造的 AI；假定执行者能够完成局部 Java 重构，但不能依赖执行者自行推断跨域不变量、提交顺序或测试范围。因此本文使用明确的模块方向、文件级工作包、禁止事项、停止条件和验收门。

调研基线为提交 `f3843b1`。行数均指该提交生产 Java 源文件的物理行数，不是方法数或复杂度结论。代码在执行期间继续演进时，先重新采集基线，但不得因为数字变化而跳过本提案中的状态所有权和提交边界。

本文中的“必须”“不得”是架构约束；“建议”才允许在给出等价证据后调整。执行者应严格按工作包顺序推进，每个工作包单独提交，禁止把多个尚未闭合的工作包堆在一个提交中。

## 一页结论

当前最大的维护问题不是 `FlipFluidDomain` 恰好有 7975 行，而是以下四类边界重叠：

1. `FlipFluidDomain` 同时拥有权威 marker/材料/化学/颗粒/气体/内能状态、派生网格和 PCG 工作区、logical step 编排、外部转移、生命周期清空/排放、碰撞编辑、查询和显示快照。
2. `FluidSimulationService` 同时是 service locator、世界启动器、composition root、调度器、控制器用例、容器恢复器、生命周期事务协调器、fixture/命令后端和查询入口。
3. `RegionState`、`RegionStateFiles`、`FluidRegionPersistence` 分别把 live runtime 捕获、逻辑 checkpoint、严格 codec、文件原子提交、ownership 和恢复编排交叉在一起；`simulation.persistence` 反向依赖 live `FluidRegion`。
4. 当前 `core` 虽不引用 Minecraft，但包级依赖仍互相穿透：`fluid -> thermal`、`thermal -> fluid/air`、`fluid.air -> thermal`；编译器无法替当前单一 Gradle 模块阻止错误方向。

目标不是一次性重写求解器，而是保留 `FlipFluidDomain` 作为临时兼容外壳，逐步把内部真值搬到有唯一所有者的组件，把 step 搬到显式流水线，把 Fabric 行为搬到应用用例和 adapter。待依赖方向稳定后，再把逻辑边界提升为 Gradle 模块。最终 `FlipFluidDomain` 被一个小型 `FluidDomain` 聚合根取代，`FluidSimulationService` 被 `MmmRuntime` composition root 和若干用例取代；区域 HEAD 仍是唯一持久提交点。

## 范围与非目标

### 本次必须完成

- 拆解 `FlipFluidDomain`、`FlipThermalState`、`FluidSimulationService`、`RegionState`、`RegionStateFiles`、`FluidTransferService` 和 `VulkanFlipPipeline` 的混合职责。
- 建立 `model -> solver -> application -> fabric adapters` 的单向依赖，并用自动架构门约束。
- 保留 CPU `FluidDomainState -> FluidStepRequest -> FluidStepCandidate` 完整候选边界，把它作为迁移期间的对照和回滚边界。
- 统一 marker 及其材料、化学、内能侧列的增删、交换、拆分、合并和外部转移，禁止各侧列独立漂移。
- 将世界启动、region 调度、controller 用例、容器事务、持久化和网络端点从一个服务中拆出。
- 把 checkpoint 的逻辑装配、严格 NBT codec 和双槽/HEAD 文件事务分层，同时维持当前磁盘字节语义和故障恢复语义。
- 拆分过大的验证类，但保留所有现有 selector 名称和场景语义。
- 完成架构文档、维护路由和目标文件索引的同步。

### 本次明确不做

- 不调整 FLIP/PIC 比例、压力公式、PCG 容差、相变批量、热物性、气体 EoS、颗粒机制或任何数值常量。
- 不改变 logical step 的顺序，不并行化 server-thread 提交，不引入异步 checkpoint。
- 不改变当前 world capability、region layout、NBT 字段、checksum、HEAD/slot 形状、网络 payload 或公开 API ABI。
- 上述 ABI 指 `api` 模块公开契约；`core` 内部公开类在迁移时也先保留 compatibility facade，直到仓库内消费者全部迁走，不能让一次结构提交同时制造无关调用方破坏。
- 不兼容或迁移旧实现存档；但本次重构前后必须兼容**当前基线格式**，不能把结构重构伪装成格式替换。
- 不引入 Spring、Guice、反射扫描、运行时插件容器或通用事件总线。
- 不把每个小算法都做成 Gradle 模块；物理模块拆分只在包级方向已经稳定后进行。
- 不顺便实现新产品功能、GPU 生产后端、开放世界水体、多 region 原子提交或跨 region 热设备。

## 当前结构的量化证据

### 模块与规模

| 生产源 | Java 文件 | 物理行数 | 观察 |
|---|---:|---:|---|
| `api/src/main` | 11 | 263 | 边界小且方向正确，应保持稳定 |
| `core/src/main` | 135 | 27401 | loader-neutral，但多个领域处于一个编译单元，包级环无法被阻止 |
| `fabric/src/main` | 119 | 24475 | server adapter、应用编排和持久化混在一起 |
| `fabric/src/client` | 48 | 17260 | 客户端与 server source set 已分开，继续保持 |
| `gpu-probe/src/main` | 12 | 3867 | 数值 pipeline 与 Fabric probe service 混在同一模块 |

当前 Gradle 图是 `api <- core <- fabric`，`gpu-probe` 依赖 `core` 并以 `compileOnly` 访问 `fabric`。顶层方向尚可，但粒度不足以约束 core 内部和 Fabric 内部。

### 最大生产类

| 文件 | 行数 | 当前混合职责 |
|---|---:|---|
| `core/.../fluid/FlipFluidDomain.java` | 7975 | 聚合真值、机械/热化学流水线、工作区、转移、查询、生命周期 |
| `fabric/.../simulation/FluidSimulationService.java` | 3329 | composition root、启动、调度、用例、恢复、命令/fixture、查询 |
| `fabric/.../simulation/persistence/RegionState.java` | 1480 | live capture、逻辑 DTO、重建、跨 section 账本验证 |
| `core/.../fluid/FlipThermalState.java` | 1461 | 各载体 U、气体能量、反应/相变、设备换热、审计和 marker 侧列 |
| `gpu-probe/.../VulkanFlipPipeline.java` | 1237 | Vulkan 生命周期、资源、调度、kernel binding、readback、诊断 |
| `fabric/.../simulation/FluidTransferService.java` | 1121 | 玩家/发射器 UI adapter、目标解析、物品映射、核心转移规划 |
| `fabric/.../simulation/persistence/RegionStateFiles.java` | 1053 | ACTIVE/REMOVED、双槽、HEAD、恢复、quarantine、NBT IO |
| `fabric/.../simulation/ContainerTransferStore.java` | 768 | WAL codec、文件存储、扫描/恢复辅助 |
| `fabric/.../simulation/FluidRegionPersistence.java` | 760 | checkpoint、restore、ownership、metadata discovery、layout 分支 |

行数不是唯一指标，但这些类同时呈现高字段数、高入口数和跨领域 import，说明它们不是单一算法天然较长，而是职责聚集。

### 包级热点

| 包 | 文件/行数 | 问题 |
|---|---:|---|
| `cc.ecisr.mmm.fluid` | 32 / 14001 | 一个包同时容纳聚合根、mechanics、状态快照、backend SPI、查询 DTO |
| `cc.ecisr.mmm.simulation` | 31 / 10451 | runtime、use case、Minecraft adapter 和 durable transaction 相互可见 |
| `cc.ecisr.mmm.simulation.persistence` | 24 / 6150 | codec/store 反向引用 `FluidRegion` 与 lifecycle 类型 |
| `cc.ecisr.mmm.thermal` | 31 / 4289 | 领域模型较完整，但由 `FlipThermalState` 反向桥接 fluid/air |
| `cc.ecisr.mmm.granular` | 24 / 3806 | 领域类已拆出，仍由 god domain 直接协调其内部 |
| `cc.ecisr.mmm.network` | 20 / 2279 | server 端点直接取得 `FluidSimulationService` |

已存在的 `FlipFreeSurfaceGeometry`、`FlipPressurePhaseGeometry`、`FlipCutCellGeometry`、`FlipParticleReseeder`、`AirCavityScanner`、`GranularInventory`、`ThermalCellGrid` 等不是失败代码。新结构必须复用这些经过验证的实现，不得另写一套“更干净”的算法与原实现竞争。

### 验证代码热点

当前 `fabric/src/test` 有 40 个 Java 文件、15877 行；纯 core 数值验证也全部借用 Fabric test classpath。最大验证类是 `M9ThermalValidation` 2176 行、`FluidRegionPersistenceValidation` 1110 行、`M8GranularValidation` 1060 行、`ProductionFluidReplayValidation` 957 行。它们保留了珍贵的跨域场景，但 scenario fixture、断言 helper 和 selector registration 混在长类中。

目标不是改成大量细碎 mock。跨域 production replay 和 durable fault matrix 继续作为集成门；纯 model/solver/application 的计划、state owner、codec-independent ledger 和 stage 测试应放到相应模块，在没有 Minecraft runtime 的 classpath 上运行。Fabric 的 `CpuValidationSuite` 继续聚合现有 selector；S21 只拆 scenario 文件和复用 fixture，不改变 selector 名、full/fast 语义或场景步数。

## 根因分析

### 权威状态与派生状态没有物理隔离

`FlipFluidDomain` 的字段中同时存在：

- 权威 marker SoA：位置、速度、体积、质量、material handle、phase label；
- 与 marker ordinal 严格对齐的 chemistry 和 thermal 侧列；
- granular active/deposit/coherent、gas inventory/topology、source/sink、pending phase work；
- 可重建的 cell marker index、volume、density、pressure footprint、gas cell ownership；
- 可丢弃的 MAC velocity、P2G weight、PCG vector、表面张力 scratch；
- transient collision edit 和 diagnostics。

结果是任何方法都能越过正确的所有者直接改数组。`removeParticle` 必须知道 thermal swap-remove、chemical swap-remove、cell dirty 和主数组搬移；外部 insert/extract、phase change、reseed 又各有类似路径。这不是靠“给方法换文件”能解决的问题。

### 编排与算法没有隔离

`FlipFluidDomain.step` 直接执行 gas topology、granular mechanics、P2G、body force、surface tension、pressure projection、G2P、advection、collision、thermal/reaction/phase、deposit dynamics 和 reseed。阶段之间靠共享字段的隐式前置状态通讯，例如 `gridDirty`、`pressureGranularCoupling`、`pendingPhaseCellVolume` 和 thermal grid 是否已经准备。执行者很难知道一个阶段可否单独调用、失败后哪些字段已经改变。

### 包级互相依赖掩盖领域方向

`fluid` 使用大量 `thermal` 类型，`thermal` 又引用 `fluid.air`，`fluid.air` 再引用 `thermal` 的能量类型。它们在同一 Gradle 模块内可以编译，因此错误方向没有即时反馈。真正需要的共同概念——stable carrier identity、exact ledger、总内能、gas state snapshot——应位于更低层 model，而不是让算法包彼此引用运行时实现。

### Fabric 服务是“所有入口的共同朋友”

`FluidSimulationService` 有静态 `WeakHashMap` locator，并暴露大量世界、controller、query、lifecycle、transfer、fixture 和 status 方法。网络、命令、物品和 GPU probe 只要拿到它就能绕过用例边界。构造器还会读取策略、发布/恢复 capability bundle、加载 registries、扫描 region/WAL/ownership 并登记问题，使得启动失败路径和普通用例无法独立测试。

### 持久化分层倒置

`RegionState.capture*` 直接接收 live `FluidRegion`，`RegionManifest`/`RegionStateCodec`/`RegionStateIntegrity` 也引用 simulation/lifecycle/footprint 类型。低层持久 DTO 因此知道 live runtime。另一方面 `FluidRegionPersistence` 同时做 checkpoint、restore、metadata owner、ownership transaction 和 Minecraft block preimage。任何改动都容易同时碰到 codec、文件事务和业务状态机。

### 测试能保护结果，但不能保护依赖方向

现有 CPU selector 对数值和恢复语义覆盖很强，这是重构可行的关键；但它们主要验证行为，不能阻止新代码再次从 network 直接调用 solver 或从 persistence 反向持有 live region。需要补充架构静态门，而不是用更多长集成测试代替分层。

## 不可破坏的现有不变量

任何工作包开始前都应重读对应 architecture 文档。以下摘要不替代那些文档，只把重构时最容易误伤的边界集中列出。

### 权威库存

- 液体真值是 marker 的位置、速度、体积、逐材料质量和相身份；cell volume、density、pressure、PLIC、mesh 都是派生状态。
- 化学真值是 species quanta、composition、charge、mass residual、indicator provenance 和 source/sink。
- 颗粒真值是 active/deposit/coherent 的 SVQ、质量、材料、组成及审计。
- 气体真值是 component nanos、kg、species/residual、node `U` 和 source/sink；温度、压力和密度派生。
- 热真值是每个载体总 `U` 以及 source/sink/work/exchange/roundoff；温度派生。
- side column 与 carrier 的 ordinal 必须同生、同 swap、同 split/merge、同提交，禁止“先改机械、再补化学/热”。

### 精确量与单位

- `1 BV = 1_000_000_000 nanos`，`1 BV = 2^27 SVQ`，`1 AU = 1_000_000_000 quanta`。
- marker solver mass 与 kg 只经 `SolverUnitScale` 转换。
- raw double、`SignedDyadic`、整数库存和 residual 都是合法真值；不得通过显示值重建。
- 所有换算明确 exact/floor/ceil/at-most，不得静默吃掉差额。

### 数值候选

- `FluidDomainState` 是完整不可变后端状态。
- `FluidStepRequest` 绑定 base generation 和冻结输入。
- `FluidStepCandidate` 只能是 `base + 1`，且 backend id、布局、预算、generation 全部校验后才能替换 live domain。
- 后端失败不得修改 live domain。迁移期间若新组件不能维持这一点，立即停止，不得以“随后 restore”掩盖预修改。

### logical step 顺序

固定顺序为 mechanics 输入/P2G → forces → pressure → G2P/advection/collision → conduction → chemistry/reaction → solid/liquid 和 gas/liquid phase → granular transition → reseed → 全账本验证。pending phase mechanical work 必须由下一次真实 pressure projection exactly-once 消费。

### 持久提交

- region HEAD 是区域物理状态唯一提交点。
- ACTIVE 写 inactive slot、逐文件 force、完整回读验证、发布 slot，最后原子替换 HEAD；HEAD rename 是唯一 commit point。
- REMOVED HEAD 发布后旧 ACTIVE payload 绝不能恢复 solver，只能收敛到 quarantine；UUID 永不复用。
- ownership/container/quarantine 的跨域恢复仍使用 `CrossDomainRecovery` 的 `BEFORE/AFTER/THIRD/UNAVAILABLE` 代数。
- 当前严格字段、类型、checksum、schema、layout 和 registry fingerprint 在本次结构改造中保持不变。

### 线程、生命周期和只读边界

- 世界读取/修改、玩家/发射器 actor 修改、lifecycle 和 HEAD 提交只在服务器主线程。
- solver 只消费冻结输入，不拥有世界、ItemStack、NBT、actor 或 HEAD。
- Query、renderer、status、Jade 和 GPU probe 都是只读消费者，不唤醒、不加载、不 checkpoint。
- operator intent 与 availability reason 正交；重构不得用一个 boolean 合并。
- `ACTIVE/REDUCED/PAUSED/UNLOADED` 和 motion/thermal activity 的语义保持不变。

## 目标架构

### 最终依赖图

```text
api                       稳定外部 ABI，JDK-only
 ^
 |
model                     stable ID、单位、ledger、registry、immutable state、recovery algebra
 ^
 |
solver                    CPU mechanics/thermal/gas/granular pipeline 与 backend SPI
 ^
 |
application               region/use-case、port、typed command/result、generation 协调
 ^
 |
fabric                    Minecraft/NBT/network/client/command/item/world adapters

gpu-probe-kernel  ------> model + solver
gpu-probe-fabric  ------> api/application integration SPI + Fabric API
```

箭头表示“上层依赖下层”。`api` 是否由 `model` 依赖只限于已有 public quantity/event/query 类型；不得让 public API 反向引用内部 model。实际 Gradle 配置可让 `model` 同时依赖 `api`，图中不允许形成循环。

为了降低风险，先在当前 `core`/`fabric` 内建立这些包级边界，再创建 Gradle 模块。过早物理拆分会迫使执行者为了通过编译公开大量内部数组，反而固化错误 API。

### 各层职责

| 层 | 可以拥有 | 不得拥有 |
|---|---|---|
| `api` | public stable records/interfaces、typed quantity、query/event 契约 | runtime handle、solver array、Minecraft/NBT 类型、内部 use case |
| `model` | immutable checkpoint/backend state、registry definitions、exact ledgers、unit、stable identity、reason codes、recovery algebra | step 算法、可变 scratch、Minecraft/NBT、网络 payload |
| `solver` | 可变 domain aggregate、机械/热化学 stages、workspace、candidate backend | 世界、HEAD、WAL、actor、packet、ItemStack、NBT |
| `application` | command/result、region runtime metadata、scheduler、use case、repository/world/actor ports | Minecraft/NBT 具体类、直接文件 IO、客户端文案、GPU buffer |
| `fabric` | composition root、Minecraft adapters、NBT codecs/stores、network/command/item/client | 复制 solver 真值、绕过 use case 直接改 domain |
| `gpu-probe-kernel` | Vulkan pipeline、buffer/layout/kernel/readback、CPU 对照 | Fabric world/service/network |
| `gpu-probe-fabric` | 可选 Mod lifecycle、命令、只读 world capture、probe snapshot adapter | 主区域提交权、region HEAD、ownership |

### 先逻辑拆分、后物理拆分

阶段一保留现有 Gradle 模块，但建立包：

```text
core/.../model/...
core/.../solver/domain/...
core/.../solver/mechanics/...
core/.../solver/thermochemistry/...
core/.../solver/backend/...
core/.../application/...        # 只有完成 Fabric 解耦后才迁入

fabric/.../bootstrap/...
fabric/.../adapter/minecraft/...
fabric/.../adapter/persistence/...
fabric/.../adapter/network/...
fabric/.../adapter/item/...
```

阶段二由编译通过的 import 方向机械迁移到 `model`、`solver`、`application` 新模块。旧 `core` 暂时作为聚合发布模块：`api project(':model')`、`api project(':solver')`、`api project(':application')`，自身不再放生产代码。所有消费者迁移后再决定是否删除聚合模块；不得在同一提交中既拆模块又改算法。

## solver 内部设计

### 聚合根只表达能力，不暴露数组

最终使用 `FluidDomain` 作为小型聚合根。迁移期间 `FlipFluidDomain` 保持现有 public surface 并逐项委托；调用者全部迁走后删除旧类或将其缩为 package-private compatibility facade。

建议目标形状：

```java
public final class FluidDomain {
    private final DomainIdentity identity;
    private final DomainGeometry geometry;
    private final LiquidCarrierState liquid;
    private final GasDomainState gas;
    private final GranularDomainState granular;
    private final ThermalDomainState thermal;
    private final DomainWorkspaces workspaces;
    private final FluidStepPipeline stepPipeline;

    public FluidDomainState captureState();
    public DomainObservation observe(DomainQuery query);
    public DomainMutationResult apply(DomainMutation mutation);
    public FluidStepOutcome step(FluidStepInput input);
}
```

这只是职责示意，不要求用一个通用 `Object`/visitor 分派所有命令。热路径可以保留具体方法。硬约束是：聚合根不返回可变数组、不让 adapter 取得内部 state owner、不持有世界或持久化对象。

### 权威状态所有者

| 组件 | 唯一拥有 | 关键操作 |
|---|---|---|
| `DomainGeometry` | size、cell size、solid mask、cut-cell micro geometry、maximum marker budget | shape 校验、collision edit candidate、几何只读 view |
| `LiquidParticleStore` | marker position/velocity/volume/mass/material/phase SoA 与 count/capacity | append、swapRemove、split、merge、reorder、capture/restore |
| `ChemicalMarkerColumn` | 与 marker ordinal 对齐的 composition/indicator 和化学 ledger | append/swapRemove/split/merge；不能自行改变 marker count |
| `ThermalCarrierState` | liquid marker U、granular/coherent/gas/device U 与 thermal audit | 由 carrier mutation 接口同步增删；温度只查询 |
| `GasDomainState` | `AirNetwork`、topology lineage、vented flag、gas audit | bootstrap/restore、topology candidate、transfer/vent plan+commit |
| `GranularDomainState` | active/deposit stores、composition、inventory/audit、dynamics state | exact insert/extract、transition、capture/restore |
| `PendingMechanicalWork` | per-cell pending phase volume/work | plan、exactly-once consume、capture/restore |
| `DomainDiagnostics` | 上一完整 accepted step 的 stats/audits | replace-on-success；不是权威库存 |

`ChemicalMarkerColumn` 可以继续内部包装现有 `ChemicalParcelStore`，`ThermalCarrierState` 可以逐步包装 `FlipThermalState`。不要为了名字一致重写已验证的数据结构。

### marker 侧列的唯一变更入口

新增 package-private `CarrierMutationCoordinator`，所有改变 marker ordinal 或 carrier 数量的路径必须经它：

```text
plan append/remove/split/merge/reorder
  -> 验证 geometry、capacity、material、chemistry、thermal、ledger
  -> 生成不可变 CarrierMutationPlan（绑定 mutationRevision）
  -> 一次 commit：liquid + chemistry + thermal
  -> 递增 mutationRevision，标记 derived grid dirty
```

计划对象只保存必要的 ordinal/数量/ledger 和校验摘要，不复制完整 domain。commit 前重验 revision 和相关 raw bits。commit 内不允许抛出可预见的业务拒答；所有 registry、容量、能量、exact representability 检查必须在 plan 阶段完成。若内部违反不变量仍抛异常，该候选由外层完整 domain candidate 丢弃，不能继续使用半改 live state。

下列现有路径最终必须只调用该 coordinator：

- `setVolume`/fixture 注入和外部 liquid/material/chemical insert/extract；
- gas condensation/boiling 对 liquid carrier 的创建与移除；
- solid/liquid phase change；
- granular precipitation/dissolution；
- reseed split/merge；
- lifecycle empty；
- collision displacement 和 world interaction 排水。

禁止为每个 side column 暴露 `double[] energies()`、`int[] mixtureIndices()` 等数组，再让 pipeline 自己保持 ordinal 对齐。

### 派生状态与工作区

将可丢弃、可重建的字段分成显式 workspace：

| workspace | 内容 | 生命周期 |
|---|---|---|
| `MarkerGridIndex` | cell head/tail/next/previous、marker count、exact cell volume/mass、phase entries | carrier/geometry mutation 后 dirty；mechanics 前重建 |
| `MacVelocityGrid` | face velocity、P2G velocity/weight、valid flags | 每个 mechanics substep |
| `PressureProjectionWorkspace` | pressure fluid mask、air node、pressure/divergence/rhs/residual/preconditioned/direction/matrixDirection | 每个 projection，可跨 step 复用内存但不持久化 |
| `SurfaceTensionWorkspace` | color/smoothed/curvature/normal/pressure | surface tension stage 内 |
| `GasTopologyWorkspace` | cavity scan arrays、lineage mapping、queue | topology candidate 内 |
| `ThermalGridWorkspace` | LTE cell grid、buoyancy sums、phase scan scratch | thermal axis 内 |
| `GranularCouplingWorkspace` | current/next/pressure coupling fields、carrier occupancy | granular/mechanics stage 内 |

`DomainWorkspaces` 只聚合这些可丢弃对象，不能逐渐变成第二个 god context；任何 authoritative ledger、registry、generation 或 operator intent 放进去都视为违规。

每个 workspace 提供 `invalidate(reason)` 和 `requireBuiltFor(revision)` 或等价 revision 检查。不要继续使用一个全局 `gridDirty` boolean 表示所有派生状态。

### 显式 step 流水线

建议拆为以下固定阶段；名称可调整，顺序不得调整：

```text
FluidStepPipeline
  1. StepAuditBaseline.capture
  2. MechanicalStepPipeline
       MarkerGridBuilder
       GasTopologyStage
       GranularPredictionStage
       ParticleToGridStage
       BodyForceStage (gravity, buoyancy, surface tension, porous boundary)
       PressureProjectionStage
       GridToParticleStage
       ParticleAdvectionStage
       CollisionCommitStage
  3. ThermochemistryPipeline
       ThermalTransportStage
       ChemicalReactionStage
       GranularReactionStage
       CondensedPhaseChangeStage
       GasLiquidPhaseChangeStage
  4. GranularTransitionStage
  5. ParticleReseedStage
  6. StepAuditClosure.validate
  7. DomainDiagnostics.replace
```

`FluidStepPipeline` 是唯一决定阶段顺序的类；stage 不得回调整个 pipeline，也不得自行发布 candidate/checkpoint/snapshot。每个 stage 接受窄接口，例如 `LiquidReadView`、`MacGridAccess`、`ThermalMutationPort`，而不是整个 `FluidDomain` 或一个包含所有组件的 `StepContext`。

热路径可使用 package-private mutable access 以避免分配，但必须满足：access 类型只在所属 solver package 可见；不能被 Fabric/application 导入；不能同时暴露不相关领域。

### pressure、surface 和 moving body

- 将 `solvePressure`、matrix application、preconditioner、project velocity 和收敛诊断整体搬入 `PressureProjectionStage`；不要分散到多个通用数学 utils。
- 保留 `FlipPressurePhaseGeometry`、`FlipFreeSurfaceGeometry`、`FlipCutCellGeometry` 和 `FlipVelocityExtrapolator` 的现有算法；只改变它们从 workspace/view 取得数据的方式。
- `MovingBodyFluidCoupling` 的纯 Java输入继续由 Fabric 冻结。`BodyCouplingStage` 只返回实际 liquid impulse；实体响应仍在 adapter/application 边界。
- mechanical audit 是 stage 输出的组合，不得通过读取 adapter 日志重新计算。

### thermal、gas、chemistry 和 granular 的方向

共同值类型下沉到 `model`：carrier kind/stable ID、registry snapshot、exact ledger、`SignedDyadic`、immutable gas/thermal/granular state。算法方向固定为：

```text
solver mechanics ----uses----> model carrier/energy views
solver thermochemistry ------> model registries/ledgers
solver gas -------------------> model gas inventory/state
solver granular --------------> model granular inventory/state
```

领域算法之间只通过明确的 plan/result 交接。例如 condensation 返回 `CarrierMutationPlan + GasTransferPlan + EnergySettlement`，由 thermochemistry coordinator 在完整 candidate 内联合提交；`GasLiquidPhaseModel` 不直接取得 `LiquidParticleStore`。

`FlipThermalState` 的拆分顺序是先提取职责而非先重命名：

1. liquid marker energy column；
2. gas node energy store；
3. granular/coherent energy adapter；
4. finite device exchange coordinator；
5. thermal audit accumulator；
6. thermal transport/phase planning services。

直到所有计划/提交测试闭合前，保留一个 package-private `FlipThermalState` facade 委托这些组件，避免同时改 `FlipFluidDomain` 和所有 thermal tests。

### typed failure

solver/application 使用封闭 reason/exception 类型表达容量、预算、pressure non-convergence、thermal validity、gas topology、stale generation 和 ledger closure。显示文本由 Fabric adapter 本地化。不得让 `message.contains(...)` 或中文/英文错误文本驱动 lifecycle/LOCKED/PAUSED 状态机。

## application 与 Fabric 设计

### composition root

新增 `MmmRuntime`，它只保存已构造组件并负责 shutdown；新增 `MmmRuntimeFactory.start(server)` 执行启动顺序。若 Fabric lifecycle 需要按 server 查 runtime，只允许 `MmmRuntimeRegistry` 持有一个 `WeakHashMap<MinecraftServer, MmmRuntime>`。任何其他类不得再建立自己的 service locator。

启动顺序必须显式分阶段并返回 typed result：

```text
load strict server policy
  -> inspect/publish/read world capability bundle
  -> build immutable registries/profile
  -> construct repositories and scan durable issues
  -> recover removed quarantine / ownership / container WAL
  -> discover committed region metadata
  -> runtime admission
  -> register ready application services
```

启动失败保留现有 fail-closed 语义。factory 不得在部分失败后返回一个看似 READY 但缺 repository/registry 的 runtime。

### 应用用例

至少拆出以下类；它们接收纯 command 并返回 typed result，不发送 packet、不拼聊天文案：

| 用例 | 负责 | 不负责 |
|---|---|---|
| `RegionCreationUseCase` | footprint/admission、初始 candidate、ownership/checkpoint 协调 | 扫描 Minecraft shape 的具体实现、UI |
| `RegionTickUseCase` | due 判断、冻结输入、backend candidate、adopt、activity/pacing | 读取实体/碰撞具体 API、发 snapshot |
| `RegionLifecycleUseCase` | pause/resume、empty/vent/release/remove preview+confirm | player permission 文案、screen |
| `RegionCheckpointUseCase` | capture、repository publish、adopt committed generation | NBT、路径、atomic move |
| `ContainerTransferUseCase` | request/plan、WAL、candidate HEAD、actor convergence | ItemStack component、音效 |
| `ControllerQueryUseCase` | bounded summary/observation、token session | packet/chat |
| `ThermalReservoirUseCase` | token、setpoint validation、未来 boundary input | BlockEntity 具体访问 |
| `RegionRecoveryUseCase` | metadata discovery/admission、restore previous、durable issue classification | 自动猜测损坏格式 |
| `DebugFixtureUseCase` | demo/capture fixture 的明确管理入口 | 正常玩家流程 |

用例之间若需要协作，依赖更小的 coordinator/port，不互相取得整个 `MmmRuntime`。例如 network endpoint 只能依赖 `ControllerQueryUseCase`，不能依赖 runtime 再自己找 region/backend。

### region runtime、catalog 与 admission

`FluidRegion` 当前同时保存 identity、domain、solver/checkpoint/network generation、footprint、runtime lifecycle、thermal activity、availability、operator intent 和有限设备。这些字段虽然相关，但不是都应由一个可任意修改的对象拥有。目标拆分为：

- `RegionIdentity`：UUID、dimension stable ID、origin、controller footprint 和 immutable grid/config identity；
- `RegionRuntimeState`：当前 accepted `FluidDomain`、solver generation、motion/thermal activity、pacing diagnostics；只在 server main thread；
- `RegionDurableMetadata`：checkpoint/network generation、profile/layout/fingerprint、persisted operator intent；来自 committed HEAD；
- `RegionAvailability`：封闭 reason 集合及本次派生证据；不覆盖 operator intent；
- `FiniteDeviceInventory`：仍随同一个 region candidate/HEAD，由 runtime state 引用唯一 owner。

`RegionRuntime` 可以聚合上述对象，但 mutation 方法按 use case 收窄，不能再次暴露 `setEverything`。`RegionCatalog`（由当前 `FluidRegionManager` 演进）只拥有 region identity/metadata 注册、cell/marker reservation、overlap index 和当前 residency；它不 step、不 checkpoint、不恢复 solver。`RegionAdmissionService` 接收 committed metadata、当前 policy/chunk/boundary facts，返回 accepted runtime plan 或 typed availability；已提交 metadata 即使 admission 失败也先登记并保留预算语义。

当前 `FluidRegionOperations` 中的 create/load/checkpoint/recover 职责分别进入 creation、checkpoint、recovery use case。当前 `FluidRegionLifecycle` 保留为纯状态转移器或并入 `RegionRuntimeState`；它不能访问 repository/world。

### ports 与 adapters

`application` 定义最小 port，Fabric 实现：

- `RegionRepository`：read metadata/state、prepare/commit/abort generation、REMOVED；
- `OwnershipRepository` 与 `OwnershipWorldPort`；
- `ContainerJournalRepository` 与 `ContainerActorPort`；
- `CollisionWorldPort`、`EntityMotionPort`、`ThermalBoundaryPort`；
- `SnapshotSink`、`CommittedEventSink`；
- `ServerClock` 或传入 tick/time，不直接调用 wall clock 作为物理真值。

port 方法返回 loader-neutral record。不要建立 `WorldPort` 这种覆盖所有 Minecraft 操作的大接口。每个 adapter 只实现自己能 durable readback 的事实，尤其 actor recovery 必须继续分类 exact `BEFORE/AFTER/THIRD/UNAVAILABLE`。

### network、命令、物品与客户端

- payload codec 只做 strict decode/encode 和预算检查；handler 把 payload 转成 command，调用一个 use case，再把 result 转成 payload。
- `/mmm` 命令只做权限、参数解析、use case 调用、审计和显示；fixture 通过 `DebugFixtureUseCase`，不能直接构造/改 domain。
- `FluidTransferService` 拆成 `PlayerContainerTransferAdapter`、`DispenserContainerTransferAdapter`、`ContainerItemCodec/Mapper` 和 application `ContainerTransferUseCase`。声音、hand、hit result、ItemStack replacement 留在 adapter。
- `FluidSnapshotPublisher` 只消费 committed/read-only snapshot view；不能捕获 live mutable array。
- 客户端继续只按 region network generation + snapshot sequence 接收；重构不改变 payload。

### 客户端快照与渲染

`ClientFluidVolumeRenderer` 当前同时接收五类 payload、维护 ledger/最新 frame、做 snapshot interpolation、构造 fluid/material/chemical/granular/gas geometry、管理 staged GPU buffer 并提交 render pass。它尚未超过 1000 行，但已经跨越状态归并、纯几何和图形资源三个边界。目标拆分为：

- `ClientRegionSnapshotStore`：独占各 payload ledger、generation/sequence/tombstone 判定和 latest immutable frames；
- `ClientFrameInterpolator`：只对兼容的连续 fluid snapshots 插值，使用注入的 monotonic time；
- `FluidSurfaceGeometryBuilder`：纯输入 frame → vertices/mesh，不读取 Minecraft client 或 GPU buffer；
- `MaterialSurfaceGeometryBuilder`、`GranularGeometryBuilder`、`DebugCellGeometryBuilder`：按显示职责分开；
- `FluidRenderBackend`：独占 `StagedVertexBuffer`、pipeline、upload/execute/close；
- `ClientFluidVolumeRenderer`：只在 extraction/render callback 取 immutable frame、调用 builder/backend，目标小于 400 行。

客户端 snapshot store 的 clear/remove/late-packet 行为必须保持 current ledger 决策；mesh 和插值永远不能反写 store。screen 类把 server-authored summary映射为控件，token/result仍由 network adapter持有，不在 screen复制权限或lifecycle规则。

## checkpoint 与 durable transaction 设计

### 四层边界

```text
RegionCheckpointAssembler      live read view <-> loader-neutral complete checkpoint
RegionStateCodec               checkpoint <-> strict NBT compounds
RegionGenerationStore          ACTIVE/REMOVED slot + HEAD atomic file protocol
RegionRepositoryAdapter        application port；组合上述三者和当前 registries/profile
```

`RegionCheckpointAssembler` 不读取文件或 Minecraft；它接收 `RegionCheckpointSource`，按明确顺序装配 base/material/chemical/granular/gas/thermal/device/pending-work。不要使用反射或 `ServiceLoader` 自动发现 extension。layout 决定唯一 contributor 清单，顺序在代码中可见。

`RegionStateCodec` 不引用 `FluidRegion`、`FluidRegionLifecycle`、`FluidRegionFootprint`。将这些持久字段变为 loader-neutral `RegionIdentity`、`RegionGeometry`、`RegionRuntimeIntent`、`ControllerFootprintState` 值对象；Fabric adapter 负责与 BlockPos/ResourceKey 映射。

`RegionGenerationStore` 是当前 `RegionStateFiles` 的拆分目标：

- `RegionHeadCodec/Store`：严格 HEAD；
- `ActiveGenerationStore`：section/manifest 临时目录、回读和 slot；
- `RemovedGenerationStore`：removed tombstone；
- `RemovedQuarantineStore`：收敛和 admission；
- `RegionDiscovery`：只读目录分类；
- `DurableFileOperations`：force、atomic move、directory sync、受控 tree stats/delete。

只有 `RegionGenerationStore` coordinator 能决定 HEAD 发布顺序。拆文件不是让业务层手动调用 `headStore.write`；低层 store 应 package-private。

### 本次的格式策略

在所有 checkpoint 工作包中，基线 writer 与新 writer 对同一个 frozen fixture 必须得到：

- 相同语义字段集合、NBT tag 类型、列表排序和 canonical checksum；
- 相同 manifest/section 布局、generation 和 fingerprint；
- 相同故障点下的 HEAD/slot/quarantine 分类；
- 相同 restore 后 `FluidDomainState` raw-bit/equality 结果。

如果基线 codec 的字节输出受 NBT map 顺序影响，只要求当前 `RegionStateIntegrity` 定义的 canonical checksum 和 strict decode 语义相同；不得趁机更换 checksum。任何真正格式修订必须另立提案、新 schema/root 和迁移政策。

## 自动架构门

新增独立 `verifyArchitecture` Gradle task，不塞进物理数值 selector。可以用一段 JDK-only source import scanner，并由 Gradle 传入 repository root；不引入大型分析框架。至少检查：

1. `api`、`model`、`solver`、`application` 不 import `net.minecraft`、`net.fabricmc` 或 NBT。
2. `model` 不 import `solver/application/fabric`；`solver` 不 import `application/fabric`；`application` 不 import Fabric adapter 包。
3. persistence codec/model 不 import live `FluidRegion` 或 solver mutable domain。
4. network/command/item adapter 不 import `FlipFluidDomain`；它们只接触 application command/result 或 read view。
5. `gpu-probe-kernel` 不 import `simulation`、`network`、Minecraft/Fabric。
6. 除 `MmmRuntimeRegistry` 外没有 `Map<MinecraftServer, ...Service>` 静态 locator。
7. source package 依赖图无环；临时 allowlist 必须带工作包编号和删除期限，最终 allowlist 为空。
8. 生产类超过 1000 行使 task 失败；迁移期只 allowlist 当前大类，并要求每个相关工作包单调下降。新类超过 600 行只警告并要求设计说明。行数是防回归信号，不代替职责评审。

Gradle 编译边界建立后仍保留 import 门，防止 Fabric 源集内部重新穿透 application/use case。

## 验证策略

### 重构前先增加 characterization

不要以“现有 milestone 已通过”代替以下对照：

- `FluidDomainState` capture → restore → capture 的完整字段和 raw double bits 对照；
- 同一 `FluidStepRequest` 经旧 facade 与新 pipeline 的 candidate state、stats、moving-body impulse 对照；
- marker append/remove/swap/split/merge 后 position/material/chemistry/thermal ordinal 对照；
- exact transfer 的 volume/mass/species/SVQ/U/source/sink 对照；
- lifecycle empty/vent 的 private candidate 前后 ledger 对照；
- RegionState capture/reconstruct 与 codec round trip 对照；
- 每个 persistence fault point 的文件树/HEAD decision 对照；
- server startup READY/LOCKED/POLICY_RESTRICTED 结果对照。

数值输出如果当前就是 deterministic raw-bit，应坚持 raw-bit。只有现有算法本就允许容差的 derived diagnostic 才用已有容差；不得为了重构扩大容差。

### 现有 selector 的使用

| 改动区域 | 最近门 |
|---|---|
| marker store、mechanics、pressure、workspace | `materials,free-surface,reseeding,cut-cell,production-replay`，只按实际跨越逐项追加 |
| gas topology/pressure | `air-network,air-cavity,gas-liquid,controller-gas-lineage` |
| chemistry/granular/thermal pipeline | `chemistry,granular,thermal` |
| backend candidate、tick/adopt | `lifecycle,production-replay,production-live-budget` |
| checkpoint/codec/store/startup | `region-state` |
| container use case/WAL/actor | `containers`；跨协议才加 `protocol` |
| ownership/lifecycle removal | `ownership,lifecycle,region-state` |
| network/query/snapshot | `protocol` 加直接 carrier 最近门 |
| GPU kernel | GPU headless correctness/benchmark 门；不改变主 CPU selector |

日常命令继续使用：

```powershell
./gradlew.bat :fabric:runCpuValidation '-PcpuValidation=<selectors>' --offline --no-daemon
./gradlew.bat :fabric:runCpuValidation -PcpuValidation=milestone --offline --no-daemon
./gradlew.bat clean assemble compileTestJava verifyArchitecture --offline --no-daemon
```

每个工作包先跑最近门；每个阶段末跑 milestone；模块切分完成后跑 clean assemble + milestone。长期 soak 只在阶段关闭或实际改到长期稳定场景时运行，不能用它替代局部单元/characterization。

### 性能与分配

结构重构不得让默认 controller 的 `production-live-budget` 超过当前 40 ms 门。对 mechanics 热路径额外记录：

- logical step 和 substep 分配次数；
- capture/restore bytes 和耗时；
- workspace rebuild 次数；
- pressure iteration/residual；
- marker count/capacity。

新增 view/interface 不应在 marker/cell/face 循环中产生每元素对象。优先传 primitive array-backed package-private access 或 indexed methods；不要为了“面向对象”把每个 marker 包成 record。

## 执行协议

后续 AI 每次只执行一个编号切片。开始切片前必须：

1. 阅读本提案对应切片、`docs/architecture/system-overview.md`、直接涉及的主题 architecture 和 `docs/maintenance/routing.md`。
2. 运行 `git status --short`，记录用户已有改动；不得覆盖、reset 或顺手格式化无关文件。
3. 运行该切片的最小基线门。若基线失败，先报告失败证据，不在红色基线上重构。
4. 列出本切片允许修改/新增的文件。发现必须跨越未列出的权威边界时停止并回到提案更新，不暗中扩大范围。
5. 只做结构变化；数值常量、算法顺序、schema、payload、错误分类和 source/sink 语义保持逐项相同。
6. 完成后运行 characterization、最近 selector、`verifyArchitecture`、`git diff --check`。
7. 审查 `git diff --stat` 和关键 diff，确认没有数组漏搬、字段默认值变化、异常吞掉或测试门降级。
8. 单独提交。提交信息使用下面给出的建议前缀；提交正文记录运行的 selector 和性能结果。

每个切片都必须可由 `git revert <commit>` 独立回退。不要依赖“下一个切片会修好”来提交编译失败、门失败、双路径行为不同或临时跳过测试的状态。

### 通用停止条件

出现任一情况立即停止当前切片并报告，不继续猜：

- 无法判断某字段是 authoritative、derived 还是 transient；
- 需要改变 current NBT/payload/API shape 才能完成搬移；
- plan 前已经修改 live state，且失败不能丢弃完整 private candidate；
- raw-bit state parity、ledger、generation 或 source/sink 不一致；
- 最近 selector 失败，失败不能证明与本切片无关；
- `production-live-budget` 新增稳定超时或热路径出现 O(marker×cell) 退化；
- package cycle 只能通过把内部数组/实现类改成广泛 public 来解决；
- HEAD/WAL/actor/ownership 的 commit point 变成两个或顺序不清；
- 为通过测试需要扩大容差、删除断言、关闭物理或减少场景步数。

## 工作包总览

| 切片 | 核心产物 | 首要大类下降目标 | 主要门 |
|---|---|---:|---|
| S00 | characterization + `verifyArchitecture` | 不要求 | architecture + milestone baseline |
| S01 | solver read views/observation services | `FlipFluidDomain` -300 至 -600 | `protocol` 加 carrier 最近门 |
| S02 | `LiquidParticleStore` | 字段所有权迁移 | `materials,reseeding,production-replay` |
| S03 | `CarrierMutationCoordinator` | 删除分散 ordinal 变更 | `chemistry,thermal,reseeding` |
| S04 | geometry/grid/workspaces | `FlipFluidDomain` -600 至 -1000 | `free-surface,cut-cell,materials` |
| S05 | liquid/material/chemical exact transfer | `FlipFluidDomain` -800 至 -1400 | `materials,chemistry,containers` |
| S06 | granular transfer/lifecycle inventory planner | `FlipFluidDomain` -500 至 -900 | `granular,thermal,containers` |
| S07 | gas domain/topology coordinator | `FlipFluidDomain` -500 至 -900 | `air-network,air-cavity,gas-liquid` |
| S08 | pressure projection stage | `FlipFluidDomain` -500 至 -800 | `free-surface,gas-liquid,production-replay` |
| S09 | complete mechanics pipeline | `FlipFluidDomain` 降到约 2500 以下 | mechanics + live budget |
| S10 | thermal state owners | `FlipThermalState` 降到 700 以下 | `thermal,granular,air-network` |
| S11 | thermochemistry pipeline | `FlipFluidDomain` 降到约 1000 以下 | `chemistry,granular,thermal,gas-liquid` |
| S12 | `FluidDomain` aggregate/backend migration | 旧 facade 降到 600 以下 | `lifecycle,production-replay,milestone` |
| S13 | application commands/ports | 服务入口开始迁移 | application unit + lifecycle |
| S14 | `MmmRuntime` startup/composition | `FluidSimulationService` -500 至 -800 | `region-state,lifecycle` |
| S15 | tick/query/lifecycle use cases | `FluidSimulationService` -1000 至 -1500 | `lifecycle,protocol,production-replay` |
| S16 | container transfer use case/adapters | `FluidTransferService` <400 | `containers,protocol` |
| S17 | checkpoint model/assembler | `RegionState` <600 | `region-state` |
| S18 | durable store/repository/ownership split | `RegionStateFiles`/`FluidRegionPersistence` <600 | `region-state,ownership,containers` |
| S19 | network/command/item thin adapters | 删除 service locator 消费者 | `protocol,audit,lifecycle` |
| S20 | Gradle 模块 + GPU kernel split | 所有依赖由编译器约束 | clean assemble + milestone + GPU gates |
| S21 | 删除兼容 facade、拆测试、同步文档 | 无生产类 >1000 | milestone + soak + release build |

表中的行数是阶段性方向，不允许通过压缩格式、合并多语句、删注释或把代码搬进匿名类达成。真正关闭条件是职责和依赖方向。

## S00：冻结行为与建立架构门

### 修改范围

- `fabric/src/test/java/cc/ecisr/mmm/validation/ArchitectureDependencyValidation.java`（新增）
- `fabric/src/test/java/cc/ecisr/mmm/validation/FluidDomainStateAssertions.java`（新增或按现有 test package 放置）
- `fabric/src/test/java/cc/ecisr/mmm/fluid/FluidDomainArchitectureValidation.java`（新增）
- `fabric/build.gradle`、根 `build.gradle`
- 可新增 `docs/maintenance/architecture-baseline.md` 记录机器可复核指标

### 操作

1. 为 test JavaExec 增加 repository root system property。`ArchitectureDependencyValidation` 扫描所有生产 `.java` 的 package/import 和物理行数；不执行编译器或外部命令，不依赖当前工作目录。
2. 创建 `verifyArchitecture` 根任务，依赖 architecture validation。给现有超大类建立显式 allowlist，每项写切片删除期限；新超大类不在 allowlist。
3. `FluidDomainStateAssertions` 递归比较 arrays、lists、records 和 raw float/double bits。禁止使用 record 默认 `equals` 比较 primitive array，因为那只比较数组引用。
4. 在现有 full-profile fixture 上固定 capture→restore→capture、phase-off candidate→restore→capture、moving-body candidate 和一次 exact transfer 的 parity。
5. 固定当前 `FlipFluidDomain`/`FluidSimulationService` public/package entrypoint 清单作为迁移清单，而不是 ABI 承诺。清单每迁走一项即删除，不允许新增未分类入口。
6. 采集 milestone、`production-live-budget` 和 build 基线，记录 JVM/JDK、marker/grid、步数和耗时；性能数字不写死为跨机器真值，只保留现有 40 ms 产品门和本机相对对照。

### 禁止

- S00 不改生产行为或移动生产方法。
- 不创建新的 `architecture` CPU selector；架构扫描用独立 Gradle task，行为 characterization 挂入它实际覆盖的现有 selector。
- 不用字符串搜索方法名替代 state parity。

### 完成条件

- `verifyArchitecture` 在当前 allowlist 下通过，并能由一个故意违规的本地实验证明会失败，实验代码随后撤回。
- milestone 和 live budget 基线通过。
- 建议提交：`test: freeze architecture refactor baseline`。

## S01：先拆只读观察与诊断

### 目标文件

在 `core/src/main/java/cc/ecisr/mmm/solver/observation/` 新建：

- `DomainReadView`
- `CellInventoryReader`
- `CarrierInventoryReader`
- `DomainSnapshotBuilder`
- `DomainMetrics`

若暂不迁 package，可先置于 `cc.ecisr.mmm.fluid` 的 package-private 类，S12 再机械移动。不要同时移动 package 和改实现。

### 操作

1. 分类 `FlipFluidDomain` 中所有无副作用入口：cell inventory、carrier observation、temperature/gas pressure、mask/density/marker/material/thermal display snapshot、volume/mass/energy totals、body sample 和 diagnostics。
2. 定义只读 indexed view；读取 primitive 值，不返回内部数组。已有 `copy*` 方法仍复制。
3. 每次移动一组读取逻辑，旧 public 方法保留签名并委托新 reader/builder。嵌套 public record 暂时由 facade 映射，避免在同一切片造成调用方类型迁移。
4. 验证 reader 不调用 `ensureGrid` 以外的状态变更；如果某个“query”会重建派生网格，明确标注为 solver-internal observation，外部 Query 仍只能使用 committed/read-only view。
5. 把 `StepStats`/audit 的格式化移出 domain；domain 只保存 typed diagnostics。

### 完成条件

- 对同一 frozen domain，新旧每个 observation raw 值或 byte array 相同。
- observation 类不 import Fabric/application/persistence。
- `FlipFluidDomain` 的只读实现显著下降，旧 facade 只有委托。
- 运行 `protocol` 以及实际读取的 `air-network/granular/thermal` 最近门。
- 建议提交：`refactor: extract solver observation services`。

## S02：建立 `LiquidParticleStore` 唯一所有权

### 目标文件

- `core/.../solver/domain/LiquidParticleStore.java`
- `core/.../solver/domain/LiquidParticleReadAccess.java`
- `core/.../solver/domain/LiquidParticleStateCodec.java` 或 capture mapper；此处“codec”是内存 state mapper，不是 NBT
- 对应 validation

### 操作

1. 原样搬入 marker count/capacity 和 position、velocity、volume、mass、material handle、phase label 数组。数组初始容量、增长公式、maximum budget 和默认值逐字保持。
2. 先只让 `FlipFluidDomain` 通过 store 的 primitive indexed access 读写，不移动算法。一次替换一列并编译；全部替换后删除旧字段。
3. 把 `ensureParticleCapacity`、主列 append、swap-remove、capture、restore 搬入 store。side column 暂不由 store 自己修改；它通过 S03 coordinator 回调。
4. `particleCellNext/Previous` 属于派生 `MarkerGridIndex`，不放入 authoritative store；在 S04 搬走前可暂留 facade，并加 TODO 引用 S04。
5. 为 reseeder/P2G 热路径提供无分配 indexed access。若既有算法必须接收数组，可提供 solver-internal、不可逃逸的 access object；架构门禁止 Fabric/application 导入，聚合根不得返回它。
6. capture/restore 对每列逐 raw-bit 对照，特别检查 capacity 不属于持久真值、count 之后的尾部也不参与状态。

### 禁止

- 不把 chemistry/thermal 数组也塞进 `LiquidParticleStore`；它只拥有机械 carrier 主列。
- 不把每个 marker 包装成对象。
- 不改变 swap-remove 顺序或 capacity doubling。

### 完成条件

- `FlipFluidDomain` 不再声明主 marker 数组和 `particleCount`。
- store 是这些字段唯一 owner；全仓库没有另一份可变 alias。
- `materials,reseeding,production-replay` 通过且性能无稳定回退。
- 建议提交：`refactor: centralize liquid particle storage`。

## S03：统一 carrier 与 side-column mutation

### 目标文件

- `CarrierMutationCoordinator.java`
- `CarrierMutationPlan.java`
- `ChemicalMarkerColumn.java`（包装现有 `ChemicalParcelStore`）
- `LiquidThermalColumn.java`（先包装 `FlipThermalState` 的 marker 部分）
- `CarrierMutationValidation.java`

### 操作

1. 在 domain 中增加 `mutationRevision`，只在成功的 carrier 结构变更后递增。
2. 列出所有 marker 数量/ordinal 改变点，用架构测试固定清单。至少包含 restore、set/add/remove、external transfer、phase、reseed、collision displacement。
3. 定义 append、swap-remove、split、merge、permutation 五种 primitive mutation。每种 plan 绑定 base revision、目标 ordinal、material/chemical/energy settlement 和预期 count。
4. 先把当前 `removeParticle`/`removeParticleInternalPhase` 合并到 coordinator；再迁 append；最后迁 split/merge/reseed。
5. plan 完成全部可能失败的 registry/capacity/ledger/energy 检查。commit 顺序固定并在验证中故障注入；若无法做到 commit 不抛出，则调用只允许发生在可丢弃的完整 candidate domain 上。
6. 成功后统一 invalidates marker grid/thermal grid；失败后 revision、所有列、ledger、dirty state 原样。

### 完成条件

- 全仓库只有 coordinator 能调用 `LiquidParticleStore` 的结构 mutation。
- chemistry/thermal side column 不暴露独立改变 count 的方法。
- 每种 mutation 覆盖空、last ordinal、middle swap、capacity edge、registry rejection 和 raw-bit ledger。
- `chemistry,thermal,reseeding,production-replay` 通过。
- 建议提交：`refactor: make carrier mutations atomic across side columns`。

## S04：分离几何、派生网格与 reusable workspace

### 目标文件

- `DomainGeometry.java`
- `MarkerGridIndex.java`
- `MarkerGridBuilder.java`
- `MacVelocityGrid.java`
- `PressureProjectionWorkspace.java`
- `SurfaceTensionWorkspace.java`
- `DomainWorkspaces.java`

### 操作

1. `DomainGeometry` 接管 dimensions、cell count、config/unit scale、solid mask、cut cells 和 marker budget。collision edit 的 plan/commit 仍在专门 coordinator，不让任意 stage 写 solid。
2. `MarkerGridIndex` 接管 cell marker links/count、exact volume/mass、density、phase entries 和 build revision。
3. 将 `rebuildGridFromParticles` 拆为 `MarkerGridBuilder.build`、P2G 初始化和其他 stage-specific rebuild。先保持调用顺序与循环顺序，禁止“顺便优化”。
4. MAC arrays 和 valid/weight 放入 `MacVelocityGrid`；PCG arrays 放入 `PressureProjectionWorkspace`；surface arrays 放入 `SurfaceTensionWorkspace`。
5. 用 carrier revision + geometry revision 替换一个模糊 `gridDirty`。每个 workspace 只接受匹配 revision；debug/test build 中 stale access 立即失败。
6. 构造器只组装组件，不再手工分配几十个数组。比较数组长度公式和初始 fill 值。

### 完成条件

- authoritative state 与 workspace 字段在不同 owner 中。
- checkpoint/capture 不读取 workspace 作为库存来源。
- `free-surface,cut-cell,materials,production-replay` 通过；P2G/pressure stats 相同。
- 建议提交：`refactor: separate solver geometry and workspaces`。

## S05：抽取 liquid/material/chemical exact transfer

### 目标文件

- `solver/transfer/LiquidTransferPlanner.java`
- `solver/transfer/MaterialTransferPlanner.java`
- `solver/transfer/ChemicalTransferPlanner.java`
- `solver/transfer/CarrierTransferSettlement.java`
- `solver/lifecycle/CondensedInventoryClearPlanner.java`

### 操作

1. 按 pure → material → chemical 顺序迁移现有 insertion/extraction/displacement。每次只迁一种 layout，并让旧 `FlipFluidDomain` 方法委托。
2. planner 接收 stable/handle 已验证的 loader-neutral request、cell target 和 read access，返回绑定 mutation revision 的 plan。
3. settlement 必须同时列出 volume、mass、material portions、chemical ledger、internal energy 和 source/sink 分类；没有该能力时使用明确 absent，而不是零值猜测。
4. commit 只经 S03 coordinator，并在 commit 后核对 actual settlement 与计划 raw bits。
5. lifecycle empty 复用相同 inventory planner，但 ledger 记入明确 operator sink；不得通过循环调用 public bucket extraction 模拟清空。
6. 删除 `FlipFluidDomain` 中重复的 nearby-cell、portion validation 和 removal bookkeeping；几何选择共用一个有界 locator。

### 完成条件

- 对 pure/material/chemical 的 exact、at-most、容量不足、pending phase work、空目标逐项对照。
- 现有 container transaction 的 actual settlement、HEAD generation、actor after 不变。
- `materials,chemistry,thermal,containers,production-replay` 按实际 layout 通过。
- 建议提交：`refactor: extract exact carrier transfer planners`。

## S06：抽取 granular transfer 与生命周期库存计划

### 目标文件

- `solver/granular/GranularDomainState.java`
- `solver/transfer/GranularTransferPlanner.java`
- `solver/lifecycle/DomainInventoryLifecyclePlanner.java`

### 操作

1. 把 registry、parcel/deposit stores、composition pool、inventory、audit 和 external source/sink 聚合到 `GranularDomainState`；现有 granular 算法类继续复用。
2. 迁移 exact dry-container insert/extract、nearby cell selection、loose packing/capacity 计划和 thermal energy settlement。
3. active/deposit/coherent 的清空必须在一个 lifecycle plan 中联合列出 SVQ、kg、species 和 `U`；commit 前重验 mutation/granular/thermal revision。
4. precipitation、deposit dynamics 和 mechanics 暂不迁移，只改为通过 state owner 访问。
5. 拒答必须在任何 granular/thermal audit 改动前完成。

### 完成条件

- `FlipFluidDomain` 不再直接拥有 granular stores/audit。
- granular transfer 前后 ledger、composition identity、energy raw bits 和 capacity 结果相同。
- `granular,thermal,containers` 通过。
- 建议提交：`refactor: isolate granular domain inventory and transfers`。

## S07：抽取 gas state 与 topology coordinator

### 目标文件

- `solver/gas/GasDomainState.java`
- `solver/gas/GasTopologyCoordinator.java`
- `solver/gas/GasPressureBoundary.java`
- `solver/gas/GasLifecyclePlanner.java`

### 操作

1. `GasDomainState` 接管 `AirNetwork`、registry binding、sealed boundary、operator vented flag、previous sealed node count 和 lineage state。
2. `GasTopologyWorkspace` 接管 cell node arrays/queue/scan scratch；cell membership snapshot仍由 state capture 明确导出。
3. 把 bootstrap、restore、export、refresh/remap、ambient exchange 和 topology validation 搬到 coordinator。保留 stable overlap/lineage 规则和异常分类。
4. pressure stage 只通过 `GasPressureBoundary` 读取 node pressure/owner；不能取得 mutable `AirNetwork`。
5. vent 通过 `GasLifecyclePlanner` 联合 gas inventory + gas energy + audit；先 plan 后 commit。thermal gas energy 由 S10 owner 参与，不在 gas state 复制一份。
6. persistence 同步入口返回完整 `GasStateSnapshot`，不让 persistence 调 topology 内部方法。

### 完成条件

- `FlipFluidDomain.airNetwork()` 不再向 Fabric 暴露 mutable network；迁移调用者到 read view。兼容 facade 若暂留此方法，架构 allowlist 标明 S12 删除。
- `air-network,air-cavity,gas-liquid,controller-gas-lineage,production-replay` 通过。
- 建议提交：`refactor: isolate gas state and topology coordination`。

## S08：抽取 pressure projection

### 目标文件

- `solver/mechanics/PressureProjectionStage.java`
- `solver/mechanics/PressureSystem.java`
- `solver/mechanics/PressureBoundaryView.java`
- `solver/mechanics/PressureProjectionResult.java`

### 操作

1. 整体迁移 pressure fluid classification、target divergence、matrix application、preconditioner、PCG loop、reference anchor、projection 和 convergence result。
2. 接口只接收 geometry、marker grid、MAC grid、gas pressure boundary、granular coupling、pending phase work read view 和 workspace。
3. 维持循环顺序、float/double 类型、absolute RMS tolerance、iteration count 和 exception 文本对应的 typed reason。
4. pending phase mechanical work 的 plan 在 pressure result 后生成，只有 projection candidate 接受时消费；不得在 solve 前清零。
5. 旧 `solvePressure/projectVelocity` 方法删除，`FlipFluidDomain.step` 暂时调用 stage。

### 完成条件

- 静水、free surface、gas pressure、granular occupancy、cut-cell 和 non-convergence candidate 对照相同。
- `free-surface,gas-liquid,cut-cell,production-replay,production-live-budget` 通过。
- 建议提交：`refactor: extract pressure projection stage`。

## S09：完成 mechanical pipeline

### 目标文件

- `MechanicalStepPipeline.java`
- `ParticleToGridStage.java`
- `BodyForceStage.java`
- `GridToParticleStage.java`
- `ParticleAdvectionStage.java`
- `GranularMechanicalStage.java`
- `MechanicalAudit.java`/`MechanicalAuditAccumulator.java`

### 操作

1. 按现有 `FlipFluidDomain.step` 的 substep 顺序逐 stage 搬移；每搬一个 stage，就用同一 fixture 比较 stage 前后关键 workspace/state。
2. granular preview/capacity/pressure coupling、porous damping、gravity、thermal buoyancy、surface tension、solid velocity、pressure、extrapolation、G2P、advection/collision 的先后保持逐项一致。
3. `MechanicalStepPipeline` 只返回 typed stats、actual moving-body impulse 和 pending work consumption；不运行 thermal/chemistry/reseed。
4. mechanical audit accumulator 独立接收每 stage energy delta，finish 时维持现有 ULP closure。
5. 清除 stage 间通过 facade private field 交流的路径；每一项进入明确 state/workspace/result。
6. 对 hot loop 运行 allocation/profile 诊断。若 indexed interface 未被 JIT 内联导致回退，改用 solver-internal primitive access，不公开给上层。

### 完成条件

- `FlipFluidDomain.step` 的 mechanics 部分只剩一次 pipeline 调用。
- phase disabled 时旧/new backend candidate state raw-bit 相同，或所有原本只允许容差的诊断满足原容差。
- `materials,surface-tension,free-surface,reseeding,cut-cell,air-cavity,gas-liquid,granular,entity-fluid,production-replay,production-live-budget` 通过。
- 阶段出口跑 milestone。
- 建议提交：`refactor: compose mechanics from explicit solver stages`。

## S10：拆分 `FlipThermalState`

### 目标文件

- `solver/thermal/LiquidEnergyColumn.java`
- `solver/thermal/GasEnergyStore.java`
- `solver/thermal/GranularEnergyState.java`
- `solver/thermal/FiniteDeviceExchangeCoordinator.java`
- `solver/thermal/ThermalAuditState.java`
- `solver/thermal/ThermalDomainState.java`

### 操作

1. 先搬字段所有权和 capture/restore，不改 thermal algorithm。每搬一个 carrier 的 U，就做 total exact energy 与 audit raw-bit 对照。
2. liquid ordinal 变化只允许 S03 coordinator 调用 `LiquidEnergyColumn`。
3. gas remap/vent 的能量 plan 与 gas inventory plan 使用同一 topology revision；granular/coherent energy 使用 stable inventory key，不按显示温度重建。
4. finite device state 与 exchange audit继续属于 region HEAD；provider/object 只是 locator。coordinator 输出联合 body/cell plan，不能把 device U 放回 BlockEntity。
5. 将 transport、phase candidate calculation、reaction plan 等无状态算法移到 service；state owner 只保存真值和审计。
6. 保留 package-private `FlipThermalState` facade，逐方法委托；当降到纯委托后在 S12 删除。

### 完成条件

- 各 carrier exact U、source/sink/work/exchange/roundoff 对照闭合。
- `FlipThermalState` 不再同时拥有所有 carrier 和全部算法，目标 <700 行。
- `thermal,granular,air-network,gas-liquid,production-replay` 通过。
- 建议提交：`refactor: split thermal carrier state and coordinators`。

## S11：建立 thermochemistry pipeline

### 目标文件

- `ThermochemistryPipeline.java`
- `ThermalTransportStage.java`
- `ChemicalReactionStage.java`
- `GranularReactionStage.java`
- `CondensedPhaseChangeStage.java`
- `GasLiquidPhaseChangeStage.java`
- `GranularTransitionStage.java`
- `ParticleReseedStage.java`

### 操作

1. 以现有 `runThermalReactionPhaseAxis` 为唯一顺序源，逐 stage 搬移；不按领域偏好重新排序。
2. 每个 phase/reaction stage 输出完整 plan：所有 carrier inventory、composition、U、audit、pending volume 和 operation budget settlement。
3. exact batch search、pressure/saturation validity、phase direction consistency 和 operation limit 原样保留。
4. granular compaction/deposit dynamics 与 reseed 分列；reseed 仍经 S03 carrier mutation 并同时搬运 side columns。
5. pipeline 最后统一进行 volume/mass/species/SVQ/U/source/sink closure；只有成功才替换 diagnostics。
6. 旧 standalone thermal/phase test entrypoint 可暂委托单 stage，但生产只从 pipeline 进入。

### 完成条件

- `FlipFluidDomain.step` 只组合 mechanical pipeline、thermochemistry pipeline、granular transition、reseed 和 audit closure。
- phase-on full-profile candidate 与基线 inventory/energy/gas lineage/pending work 相同。
- `chemistry,granular,thermal,gas-liquid,controller-gas-lineage,production-replay,production-live-budget` 通过。
- 建议提交：`refactor: compose thermochemistry from explicit stages`。

## S12：切换到新 `FluidDomain` 聚合根

### 目标文件

- `solver/domain/FluidDomain.java`
- `solver/backend/CpuFluidSolverBackend.java`
- `model/state/FluidDomainState.java` 仅机械迁 package；字段不变
- `FlipFluidDomain.java` compatibility facade

### 操作

1. `FluidDomain` 组合 geometry、state owners、workspaces 和 pipelines；构造器只验证 identity/config/budget 并组装。
2. 将 authoritative capture/restore 实现移到 aggregate/state mapper。`CpuFluidSolverBackend` 只恢复私有 `FluidDomain`、应用 frozen moving bodies、step、捕获完整 candidate。
3. 逐一迁移 Fabric、test、gpu-probe 的直接 `FlipFluidDomain` 调用到 aggregate 或 backend/read view。fixture construction 使用专门 test fixture builder，不把 `setVolume` 等调试入口留在生产 aggregate public API。
4. compatibility facade 保持尚未迁走的签名并委托；入口清单归零后删除。若 public core consumers 暂需过渡，保留一版 deprecated facade，但它不得拥有状态或算法。
5. `FluidStepCandidate` 的 generation/backend/layout 校验继续在 adopt 边界，不因 aggregate 更名下沉到 pipeline。
6. 更新 architecture allowlist，禁止新代码 import `FlipFluidDomain`。

### 完成条件

- `FlipFluidDomain` <600 行且只有兼容委托，或已删除。
- solver 无 Fabric/persistence/world mutable imports。
- phase-off/phase-on candidate parity、lifecycle adopt、production replay、milestone 全过。
- 建议提交：`refactor: adopt FluidDomain solver aggregate`。

## S13：定义 application command/result 与 ports

### 目标文件

在 `core/src/main/java/cc/ecisr/mmm/application/` 暂建：

- `port/RegionRepository.java`
- `port/OwnershipPort.java`
- `port/ContainerActorPort.java`
- `port/ContainerJournalRepository.java`
- `port/CollisionWorldPort.java`
- `port/EntityMotionPort.java`
- `port/SnapshotSink.java`
- 对应 `command/`、`result/`、`RegionApplicationService` 小型接口

### 操作

1. 从实际用例倒推 port，不预建万能 repository。先覆盖 checkpoint/tick/query 的最小方法。
2. command 携带 stable identity、expected generation、typed amount、权限判定后的 principal facts；不携带 `ServerPlayer`、`ServerLevel`、`ItemStack`、`Component`。
3. result 使用封闭 reason code + bounded typed data；Fabric adapter 负责本地化。
4. 在 Fabric 原类旁写 adapter 实现，先让 `FluidSimulationService` 调 application service；行为保持不变。
5. 架构门禁止 application import `net.*`、NBT、network payload 和 Fabric package。

### 完成条件

- 至少 checkpoint、step/adopt、controller summary 有 loader-neutral command/result 测试。
- port 没有 `getEverything()`、`MmmRuntime` 或 live domain getter。
- `lifecycle,protocol,region-state` 最近门通过。
- 建议提交：`refactor: introduce loader-neutral application ports`。

## S14：拆出 world startup 与 composition root

### 目标文件

- `fabric/.../bootstrap/MmmRuntime.java`
- `MmmRuntimeFactory.java`
- `MmmRuntimeRegistry.java`
- `WorldStartupCoordinator.java`
- `RegistryBundleLoader.java`

### 操作

1. 原样搬移 `FluidSimulationService` 构造器的策略读取、capability startup、definition validation、registry 获取、repository 创建、recovery/discovery 顺序。
2. 每个阶段返回 typed startup state 和 issues；logger 是 adapter side effect，不是状态机输入。
3. `MmmRuntime` 字段按职责持有 use cases/repositories/sinks，不再次复制原 service 的全部 convenience 方法。
4. 所有 Fabric lifecycle callback 先从 `MmmRuntimeRegistry` 获取 runtime。删除 `FluidSimulationService.SERVICES` 和 `COMMITTED_EVENTS`，event hub 由 runtime 拥有。
5. 对 READY、unreadable policy、LOCKED_PROFILE、definition drift、unknown durable entry、quarantine recovery 建启动对照。

### 完成条件

- `FluidSimulationService` 构造器不再做 IO/recovery，或类已由 runtime 替换。
- 全仓库只有 `MmmRuntimeRegistry` 按 `MinecraftServer` 保存 runtime。
- `region-state,lifecycle,containers,ownership` 启动相关门通过。
- 建议提交：`refactor: isolate server runtime startup`。

## S15：拆 tick、query 和 lifecycle 用例

### 目标文件

- `application/RegionTickUseCase.java`
- `application/RegionLifecycleUseCase.java`
- `application/ControllerQueryUseCase.java`
- `application/ThermalReservoirUseCase.java`
- `application/RegionRuntime.java`
- `application/RegionCatalog.java`
- `application/RegionAdmissionService.java`
- `fabric/.../adapter/minecraft/MinecraftRegionInputs.java`

### 操作

1. 先迁 `stepMotionOnce`/tick due/pacing/adopt，Fabric adapter 在调用前冻结 collision、entity motion、thermal boundary；application 不回读世界。
2. 迁 controller summary/query/token session，summary 从 bounded read view 构建。UNLOADED 不虚构 live inventory。
3. 迁 pause/resume 和 empty/vent/release/remove preview/confirm。nonce、player/region/generation/action 绑定保持；permission facts由 adapter 提供，确认时仍重验全部 durable/live identity。
4. snapshot sink 只在 accepted runtime step或 durable lifecycle commit后调用现有 publisher。
5. fixture/demo/takeover 移到 `DebugFixtureUseCase` 或专门 maintenance adapter，不和正常 tick 混在一起。
6. 将 `FluidRegionManager` 的注册/reservation/overlap 机械迁到 `RegionCatalog`，将 runtime policy/chunk/boundary 判定迁到 `RegionAdmissionService`；catalog 不调用 solver/repository。
7. 将 `FluidRegion` 字段按 identity/durable metadata/runtime state/availability 分类。先用聚合 facade 委托，待调用者迁完再收窄 setter；不得同时改变 lifecycle transition。

### 完成条件

- `FluidSimulationService.tick/controllerSummary/lifecycle` 只剩委托或被删除。
- PAUSED/pacing/due motion input exactly-once，candidate adopt 和 removal tombstone 时序不变。
- `lifecycle,entity-fluid,production-replay,protocol,region-state,ownership` 按跨层通过。
- 建议提交：`refactor: split region runtime use cases`。

## S16：重构 container transfer

### 目标文件

- `application/ContainerTransferUseCase.java`
- `application/ContainerTransferCoordinator.java`
- `fabric/.../adapter/item/PlayerContainerTransferAdapter.java`
- `DispenserContainerTransferAdapter.java`
- `MinecraftContainerActorAdapter.java`
- `ContainerItemMapper.java`
- `adapter/persistence/ContainerJournalCodec.java`
- `ContainerJournalFileStore.java`
- `application/ContainerRecoveryCoordinator.java`

### 操作

1. 从 `FluidTransferService` 提取 hit/hand/dispenser/ItemStack/声音/文案到两个 adapter。
2. pure/material/chemical/granular/thermal item truth 先由 `ContainerItemMapper` 解成 core `ContainerTransferActor`/contents；actual 非空 profile binding继续严格验证。
3. application use case 复用 S05/S06 planner 和现有 `ControllerItemTransferCoordinator` 的 WAL→staged HEAD→actor exact after→WAL delete 顺序。
4. recovery index、UNAVAILABLE event retry、THIRD lock、runtime dirty set 迁入专门 `ContainerRecoveryCoordinator`；normal tick 不扫描目录。
5. 成功 result 返回 actual settlement 和 committed generation；adapter 最后替换 ItemStack/播放声音/发送消息。
6. 旧 `FluidTransferService` 保留两个 static Fabric hook 委托，调用点迁完后删除。
7. 将 `ContainerTransferStore` 拆成严格 NBT codec 与文件 store；file store只做write/read/delete/discover和durability，recovery coordinator解释journal状态。WAL删除仍只能发生在actor durable exact-after回读和committed event边界都完成之后。

### 完成条件

- application/core 不 import ItemStack/Player/BlockSource/Component。
- player 与 dispenser 同用一个 plan/coordinator；差异只在 actor adapter。
- fault matrix、success response loss、actor unload/reload、profile mismatch 和 exact settlement通过。
- `containers,protocol` 加实际 carrier 门通过。
- 建议提交：`refactor: separate container transfer use case and adapters`。

## S17：拆 checkpoint 逻辑模型与 assembler

### 目标文件

- `model/region/RegionCheckpoint.java`
- `RegionIdentity.java`、`RegionGeometry.java`、`RegionRuntimeIntent.java`
- `application/checkpoint/RegionCheckpointAssembler.java`
- `RegionCheckpointSource.java`
- `RegionCheckpointRestorer.java`
- 保留 `RegionState` 作为临时 adapter 名称

### 操作

1. 先从 `RegionState` 提取 loader-neutral records；所有 BlockPos/ResourceKey/lifecycle class 映射为 stable primitive/value。
2. assembler 按 layout 显式选择 pure/material/chemical/granular/thermal contributors。每个 contributor只负责自己的完整 payload和 ledger验证，manifest总账由 assembler联合验证。
3. capture 接受 `RegionCheckpointSource`，不接受 `FluidRegion`。source由 application aggregate/read view实现。
4. reconstruct 返回 `RestoredRegionState`/`FluidDomainState` + metadata，不直接创建 live `FluidRegion`。application 在 admission 后构造 runtime。
5. 逐 layout 比较 manifest、section、extensions、ledger、checksum和 reconstructed domain。
6. `RegionState` 全部调用迁移后删除或变成 NBT adapter DTO，不再有 live capture/reconstruct。

### 完成条件

- `simulation.persistence` 对 `FluidRegion`、`FluidRegionLifecycle` 的 import 归零。
- assembler/model 不 import NBT/Minecraft。
- `RegionState` <600 行，或职责被新类型完全取代。
- `region-state` 全门与 current writer parity 通过。
- 建议提交：`refactor: separate region checkpoint assembly from runtime`。

## S18：拆 durable generation store 与 repository

### 目标文件

- `adapter/persistence/RegionHeadStore.java`
- `ActiveGenerationStore.java`
- `RemovedGenerationStore.java`
- `RemovedQuarantineStore.java`
- `RegionDiscovery.java`
- `RegionGenerationStore.java`
- `RegionRepositoryAdapter.java`
- `OwnershipTransactionCoordinator.java`

### 操作

1. 先提取无状态严格 HEAD/removed codec，再提取 ACTIVE slot write/read，再提取 quarantine。每次让 `RegionStateFiles` coordinator 委托，故障点不变。
2. `RegionGenerationStore` 保留唯一 prepare/commit/abort/read/discover public surface；子 store package-private，业务层不能单独发布 HEAD。
3. 将 `FluidRegionPersistence` 的 checkpoint/restore/metadata 与 ownership 分开。repository adapter只组合 checkpoint assembler + generation store；ownership coordinator实现 application port。
4. `GenerationSequence`、base checksum、inactive slot、force/readback/atomic rename/directory sync顺序逐项保持。
5. `deleteTree` 只能用于 writer 能证明的明确 temp/inactive target；路径先 canonical/absolute 验证在 region root 内。未知证据仍保留并 LOCKED。
6. 对 ACTIVE/REMOVED 每个 fault point 和 CrossDomainRecovery 全矩阵比较文件树决策。

### 完成条件

- HEAD commit point 仍只有一个 coordinator；业务层无法直接调用低层 HEAD write。
- `RegionStateFiles`、`FluidRegionPersistence` 删除或均 <600 行单职责 facade。
- `region-state,ownership,containers` 相关恢复门通过，真实文件 fixture无语义差异。
- 建议提交：`refactor: split region repository and durable stores`。

## S19：收窄 network、command、item 和 snapshot adapter

### 目标文件

- `adapter/network/*Endpoint.java`
- `adapter/command/MmmCommandEndpoints.java`
- `adapter/item/*Adapter.java`
- `adapter/snapshot/FabricSnapshotSink.java`
- `client/ClientRegionSnapshotStore.java`
- `client/ClientFrameInterpolator.java`
- `client/*GeometryBuilder.java`
- `client/FluidRenderBackend.java`

### 操作

1. 对每个 payload handler建立固定形状：strict decode → command mapping → one use case → result mapping。handler 不访问 repository/domain/region manager。
2. 对 `/mmm` 每个子命令映射到正常或 maintenance use case；命令审计包裹最终 result，不参与业务分支。
3. 将 `FluidProtocolServer -> FluidSimulationService` 直接依赖删除。material/thermal snapshot mapper只读取model snapshot，不读取 mutable registry/domain。
4. 将 `MmmCommands` 中 fixture数学和世界操作搬到 fixture use case/adapter，命令树只保留参数和权限。
5. 所有用户文本保留当前语义，但 typed reason 与本地化映射集中，不让 application返回 `Component`。
6. 拆 `ClientFluidVolumeRenderer`：先搬 payload ledger/store，再搬纯 geometry builder，最后搬 GPU resource/backend。每一步固定 late snapshot、tombstone、layout reject、interpolation 和 generated vertex fixture；不要在拆分时改变视觉阈值、颜色、iso level 或 render pipeline。

### 完成条件

- network/command/item package无 `FlipFluidDomain`、repository具体类或 `FluidSimulationService` import。
- `ClientFluidVolumeRenderer` 只编排 immutable frame、geometry builder 和 render backend，目标 <400 行；ledger/store 与 GPU resource 没有双重 owner。
- `FluidSimulationService` 无消费者后删除；若为过渡保留，architecture allowlist要求S21删除。
- `protocol,audit,lifecycle,containers`及实际领域门通过。
- 建议提交：`refactor: make Fabric endpoints thin application adapters`。

## S20：物理 Gradle 模块与 GPU 拆分

### 目标模块

```text
api
model
solver
application
core                 # 临时聚合模块，无生产源码
fabric
gpu-probe-kernel
gpu-probe            # Fabric adapter/mod
```

### 操作

1. 先创建空模块和依赖，再按已经稳定的 package 机械移动源码；一次移动一个包，禁止同时重命名类型或改算法。
2. `model` 依赖 JDK、现有必要的 Gson 和 `api`；`solver` 依赖 model/api；`application` 依赖 solver/model/api；`fabric` implementation/include相应模块。
3. 旧 `core` 改为 `java-library` 聚合依赖，暂保 artifact name；确认没有源码和反向依赖后再决定下个发布是否删除。
4. 把 `VulkanFlipPipeline`、checkpoint、shader binding、headless validation搬到 `gpu-probe-kernel`；把 `GpuProbeService`、world capture、命令、snapshot发送留在 `gpu-probe`。
5. `VulkanFlipPipeline` 进一步拆为 `VulkanDeviceContext`、`FlipGpuResources`、`FlipKernelBindings`、`GpuStepScheduler`、`GpuReadback`；每个资源实现明确 close ownership。不得在结构拆分中改变 shader/buffer layout。
6. 可选 probe若仍需主Mod冲突检测/快照通道，通过窄 integration SPI；允许 Fabric adapter对 host integration API的compile-only依赖，但kernel不能依赖`:fabric`。
7. 更新root assemble、publication、sourcesJar和Loom include。检查最终JAR没有重复class或漏资源。
8. 为 model/solver/application 建各自 test task，至少运行 state owner、plan/commit、pipeline stage 和 use case unit tests；Fabric test保留跨域selector聚合。若需要共享fixture，使用明确的test-fixture/source-set依赖，不把test helper放入生产JAR。

### 完成条件

- Gradle图无 cycle；model/solver/application可在没有Minecraft classpath时编译和测试。
- `gpu-probe-kernel`无Minecraft/Fabric/simulation/network imports；`gpu-probe`不取得HEAD/ownership写权。
- clean assemble、compileTestJava、verifyArchitecture、milestone和GPU headless correctness通过。
- 比较Fabric最终JAR包含的API/model/solver/application类和资源符合预期。
- 建议提交：`refactor: enforce architecture with Gradle modules`。

## S21：删除过渡层、拆验证类并关闭文档

### 操作

1. 删除无消费者的 `FlipFluidDomain`/`FlipThermalState`/`FluidSimulationService`/`RegionState`/`RegionStateFiles`/`FluidTransferService` compatibility facade。若某个名字保留，必须是单一职责且不在allowlist。
2. 拆 `M9ThermalValidation`、region persistence validation、production replay等长测试类，按scenario分文件；`CpuValidationSuite`保留现有selector和执行顺序，避免改变门语义。
3. test fixture builder集中构造full-profile domain/region；不得复制生产registry或ledger规则。
4. architecture allowlist清空。生产类>1000行失败；检查最大类、包依赖图和static locator。
5. 更新：
   - `docs/architecture/system-overview.md` 的模块和数据流；
   - `docs/architecture/region-state.md` 的职责类名，但不改commit契约；
   - fluid/gas/thermal/container主题文档的owner/pipeline；
   - `docs/maintenance/routing.md` 的新入口；
   - roadmap只在产品能力实际变化时更新，本次通常不改能力描述；
   - version history记录架构重整完成，不把本提案提升为当前规范。
6. 将本提案保留在archive；真正生效的约束全部落到architecture/maintenance，不能让执行者以后继续查proposal才能理解系统。

### 完成条件

- milestone、soak、clean assemble、`verifyArchitecture`、必要GPU门全部通过。
- 当前格式的world capability、ACTIVE/REMOVED region、ownership/container fault fixtures全部可读且行为相同。
- `production-live-budget`仍过40 ms门；没有显著内存/分配回退。
- 建议提交：`docs: close architecture restructuring migration`。

## 旧文件到目标组件的迁移表

| 旧文件/区域 | 目标 |
|---|---|
| `FlipFluidDomain` marker fields | `LiquidParticleStore` |
| `FlipFluidDomain` grid/MAC/PCG/surface fields | `MarkerGridIndex` + workspaces |
| `FlipFluidDomain.step` | `MechanicalStepPipeline` + `ThermochemistryPipeline` + reseed/audit closure |
| pressure methods | `PressureProjectionStage` |
| exact liquid/material/chemical methods | `solver.transfer.*Planner` + `CarrierMutationCoordinator` |
| granular state/transfer methods | `GranularDomainState` + `GranularTransferPlanner` |
| gas bootstrap/restore/remap/vent | `GasDomainState` + `GasTopologyCoordinator` + lifecycle planner |
| `FlipThermalState` | carrier energy stores + thermal services/coordinators |
| `FluidSimulationService` constructor/static maps | `MmmRuntimeFactory` + `MmmRuntimeRegistry` |
| `FluidSimulationService.tick/step` | `RegionTickUseCase` + Minecraft frozen-input adapter |
| controller summary/lifecycle | query/lifecycle use cases + network/UI adapters |
| container WAL/recovery fields | `ContainerRecoveryCoordinator` |
| `ContainerTransferStore` | strict journal codec + durable file store；recovery解释留在coordinator |
| `FluidRegion` | identity/durable metadata/runtime state/availability 的受控 `RegionRuntime` 聚合 |
| `FluidRegionManager` | `RegionCatalog` + `RegionAdmissionService` |
| `FluidRegionOperations` | creation/checkpoint/recovery use cases |
| `FluidTransferService` | application transfer use case + player/dispenser/item adapters |
| `RegionState` | loader-neutral checkpoint + assembler/restorer |
| `RegionStateCodec/Integrity` live imports | Fabric strict NBT codec 消费 loader-neutral checkpoint；不含 NBT 的 canonical integrity helper 才可下沉 model |
| `RegionStateFiles` | generation coordinator + head/active/removed/quarantine stores |
| `FluidRegionPersistence` | repository adapter + ownership transaction coordinator |
| `ClientFluidVolumeRenderer` | snapshot store/interpolator + pure geometry builders + render backend |
| `VulkanFlipPipeline` | device/resources/bindings/scheduler/readback in GPU kernel |

## 最终架构验收清单

### 状态所有权

- [ ] 每项 authoritative truth 只有一个可变 owner。
- [ ] marker主列与chemical/thermal侧列只经原子mutation coordinator改变ordinal。
- [ ] derived grid/workspace可完整丢弃重建，不进入checkpoint真值。
- [ ] aggregate不返回live数组、mutable `AirNetwork`或内部state owner。
- [ ] 所有 external mutation 都有 typed plan/result、expected generation/revision 和明确 source/sink。

### 编排

- [ ] logical step顺序只在一个pipeline可见。
- [ ] stage输入窄且不接收god context/runtime。
- [ ] candidate失败不会修改live domain。
- [ ] pending phase work exactly-once消费。
- [ ] diagnostics只在完整accepted step后替换。

### application/Fabric

- [ ] application无Minecraft/Fabric/NBT类型。
- [ ] network/command/item只调用用例，不直接访问domain/repository。
- [ ] 只有 `MmmRuntimeRegistry` 拥有 server→runtime map。
- [ ] 启动、tick、query、lifecycle、transfer、fixture 为独立职责。
- [ ] 用户文案不驱动 reason state machine。
- [ ] `RegionCatalog` 只登记 identity/metadata/reservation/overlap，不 step 或 checkpoint。

### 持久化

- [ ] checkpoint assembler不接受live `FluidRegion`。
- [ ] codec不引用runtime/solver mutable type。
- [ ] HEAD rename仍是唯一commit point。
- [ ] ACTIVE/REMOVED、quarantine、ownership/container recovery矩阵不变。
- [ ] current baseline格式和checksum语义不变。

### 构建与质量

- [ ] model/solver/application在无Minecraft classpath下编译。
- [ ] package/module依赖无环，architecture allowlist为空。
- [ ] 无生产类超过 1000 行；新类职责可一句话说明。
- [ ] 现有 CPU selector 名称和场景保留。
- [ ] milestone、soak、clean assemble、live budget 和必要 GPU 门通过。
- [ ] architecture 与 maintenance 文档已替代 proposal 成为当前真值。
- [ ] 客户端 snapshot truth、纯 geometry 和 GPU resource 分属唯一 owner，迟到包/tombstone 不复活 region。

## 风险、回退与优先级

### 最高风险：ordinal side-column 漂移

症状通常不是立即崩溃，而是材料、species或U落到错误marker。S02/S03必须在其他算法搬移前完成；任何相关提交都要求逐marker raw parity和swap-remove边界测试。若失败，完整revert该切片，不做“修正测试期望”。

### 次高风险：候选内的部分提交

把方法拆成多个service后容易出现第一个service已commit、第二个plan失败。所有跨carrier操作必须先联合plan，或只在可丢弃private domain candidate内执行。不得给live aggregate增加补偿事务来掩盖错误顺序。

### 次高风险：持久层抽象改变commit point

把`RegionStateFiles`拆成小类时最容易让上层分别写slot和HEAD。子store必须package-private，由一个generation coordinator维护状态机和fault points。任何无法逐项映射当前故障点的设计都不接受。

### 性能风险：细粒度对象和虚调用

solver热循环不创建marker/cell对象。先用indexed primitive access和可复用workspace；若profile证明虚调用没有内联，再在solver-internal范围提供array access。不要以性能为由把数组公开到application/Fabric。

### 计划风险：同时移动package、改名字、改算法

每个提交只选择一种主要变化：字段owner、方法搬移、调用者迁移、package/Gradle移动或算法变化。本提案不包含算法变化。出现难以review的大diff时应拆小，而不是要求测试替代review。

### 回退原则

- 单切片失败：revert该切片，前序已通过切片保留。
- 阶段出口milestone失败：二分最近阶段提交，回退首次破坏行为的切片。
- 性能回退：保留新owner/边界，优先优化internal access；若无法恢复门，revert导致回退的切片，不降低预算门。
- durable parity失败：立即revert整个S17/S18相关切片，保留原writer；不得发布双writer或自动迁移。

## 建议完成指标

这些指标用于证明架构变化已经实际发生，不是单纯把大类切成更多文件：

- `FlipFluidDomain`、`FlipThermalState`、`FluidSimulationService`、`FluidTransferService`旧god class无状态/算法，最终删除或小于600行纯facade。
- 所有生产Java类少于1000行；绝大多数编排/adapter少于500至600行。
- `simulation.persistence -> simulation.FluidRegion` import为0。
- network/command/item -> solver mutable domain import为0。
- model/solver/application -> Minecraft/Fabric/NBT import为0。
- `ClientFluidVolumeRenderer` 小于400行编排代码，payload ledger、mesh build和GPU buffer ownership不再混合。
- core package-level mutual dependency被目标模块编译边界消除。
- CPU candidate、checkpoint和transfer characterization保持基线raw truth。
- 默认controller live step继续满足40ms硬门。

达到数字但仍存在god `Context`、全局service locator、数组公开或双commit point，不算完成。反之，少数复杂算法类接近行数上限但单一职责、依赖单向且有完整门，可在架构评审中给出明确例外；例外不能是旧god class换名。
