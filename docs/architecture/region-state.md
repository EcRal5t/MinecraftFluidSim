# 区域 checkpoint 契约

修改区域存档、世界能力包、启动恢复或区域删除时读本文。单位与整数编码统一见[设计书](system-overview.md#units)。

## 修改入口

| 职责 | 代码入口 | 测试选择器 |
|---|---|---|
| 世界启动与定义加载 | [WorldStartupCoordinator](../../fabric/src/main/java/mmm/simulation/WorldStartupCoordinator.java)、[RegistryBundleLoader](../../fabric/src/main/java/mmm/simulation/RegistryBundleLoader.java) | `region-state` |
| 世界能力包 | [WorldCapabilityBundleStore](../../fabric/src/main/java/mmm/simulation/persistence/WorldCapabilityBundleStore.java)、[WorldCapabilityStartup](../../fabric/src/main/java/mmm/simulation/persistence/WorldCapabilityStartup.java) | `region-state` |
| 捕获、校验与恢复 | [MinecraftRegionCheckpointSource](../../fabric/src/main/java/mmm/simulation/MinecraftRegionCheckpointSource.java)、[RegionCheckpointAssembler](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointAssembler.java)、[RegionCheckpointValidator](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointValidator.java)、[RegionCheckpointRestorer](../../fabric/src/main/java/mmm/simulation/persistence/RegionCheckpointRestorer.java) | `region-state` |
| 相占据与固定微面编码 | [PhaseOccupancyCodec](../../fabric/src/main/java/mmm/simulation/persistence/PhaseOccupancyCodec.java)、[FixedFaceGeometryCodec](../../fabric/src/main/java/mmm/simulation/persistence/FixedFaceGeometryCodec.java) | `region-state` |
| 文件编码与双槽提交 | [RegionStateCodec](../../fabric/src/main/java/mmm/simulation/persistence/RegionStateCodec.java)、[RegionGenerationStore](../../fabric/src/main/java/mmm/simulation/persistence/RegionGenerationStore.java) | `region-state` |
| 删除与隔离 | [RemovedGenerationStore](../../fabric/src/main/java/mmm/simulation/persistence/RemovedGenerationStore.java)、[RemovedQuarantineStore](../../fabric/src/main/java/mmm/simulation/persistence/RemovedQuarantineStore.java) | `region-state` |
| 登记与加载区域 | [RegionRepositoryAdapter](../../fabric/src/main/java/mmm/simulation/RegionRepositoryAdapter.java)、[FluidRegionOperations](../../fabric/src/main/java/mmm/simulation/FluidRegionOperations.java) | `region-state,region-manager` |

执行方式见[验证指南](../maintenance/validation.md#host)。

## 世界能力包

`mmm/world-capabilities/` 保存世界唯一的能力包：化学、气体、颗粒、材料、热学五份 registry snapshot 加一份 `profile.nbt`。区域载荷中的 stable ID 全部按这个包解析，区域文件不复制 registry。

能力包发布一次后保持不变。datapack 定义与已提交包不一致时只报告漂移，已有库存继续按包内定义解释。发布采用与区域相同的 staging → slot → HEAD 顺序，HEAD rename 是提交点；重开时按目录内容补完中断的发布或把无法识别的 staging 移入 quarantine。

启动时能力包决定世界状态：

| 状态 | 含义 |
|---|---|
| `UNINITIALIZED` | 尚无能力包 |
| `READY` | 能力包可证明且允许接入 |
| `LOCKED_PROFILE` | 存在能力包或权威数据但无法证明；文件原样保留 |
| `POLICY_RESTRICTED` | 能力包可证明，世界政策暂不接入 |

`mmm/` 下的权威目录（`container-transfer`、`ownership`、`region-state`）要求存在已提交能力包；`quarantine` 保存隔离证据。其他未知条目使世界进入 LOCKED 并原样保留。存档直接使用当前格式，读取时不做迁移或字段补全。

## 目录与提交模型

`mmm/region-state/<region UUID>/` 是区域物理状态的唯一持久根：

```text
HEAD.nbt
slot-a/  slot-b/       # 各自是完整 ACTIVE generation（manifest + section-*），或只含 removed.nbt 的删除标记
```

逻辑数据是 loader 无关的 `RegionCheckpoint`。`MinecraftRegionCheckpointSource` 从活动区域导出同一逻辑时刻的稳定数据，Assembler 组装 section 与总账，Validator 审计跨 section 身份与账本，Restorer 重建 solver 状态。`RegionGenerationStore` 独占 slot 与 HEAD 的写入，业务层通过 `RegionRepositoryAdapter` 访问。

一次提交写入非活动 slot，回读并校验 CRC、身份、预算和全部账本，再原子替换 HEAD。**HEAD rename 是唯一提交点**：此前旧 generation 保持权威，任何失败都保留原 HEAD。平台不支持原子移动时提交失败。显式 checkpoint 在写入前核对 region 身份、预期 generation、所有权锁和挂起的容器 WAL，过期请求保留原 HEAD 并返回封闭的拒绝原因。

### 区域删除

删除空区域写入 `removed.nbt` 并把 HEAD 原子替换为 `REMOVED`。删除标记保存区域与 registry 身份、前后 generation、旧 manifest 校验和、隔离事务 ID 和零库存摘要。提交后旧 ACTIVE slot 移入 `mmm/quarantine/removed-region/<uuid>/`，删除标记永久保留，该 UUID 不再复用。

启动时对 HEAD 与隔离目录统一分类：未提交的删除 staging 恢复到原 ACTIVE；已提交 REMOVED 幂等推进到隔离完成；内容不匹配或无法归类的组合进入 LOCKED。

## Generation 内容

**Manifest** 是无需分配 solver 即可审计与预算准入的索引：

- `RegionIdentity`：region UUID、维度、checkpoint/network generation；
- `RegionGeometry`：origin、网格、完整 `FluidSolverConfig`（含 `ViscousWallCondition`）、controller footprint 与配额；
- `RegionRuntimeIntent`：resume state、operator intent、实体/调试开关、step interval 与 tick；
- 材料与物种 palette、registry identity、各库存总账与 section descriptor；
- 颗粒、气体、热学、有限热设备等 manifest 扩展，以及覆盖规范编码的 manifest checksum。

**Section** 按 Minecraft section 对齐，声明 `StateEncoding=cell-mac`：

```text
液体记录  key = globalCell, materialPaletteIndex, connectedSupportMask
          volume = canonicalSignedDyadic(significandBytes, exponent)
          massUnits, internalEnergyBits
          mixture = speciesHandles[], amountQuanta[], massResidualUnits   # 化学启用时
面动量    FaceMomentum(axis, globalFaceIndex, momentumBits)               # kg·m/s，每面一次
```

- 面归属正侧单元所在 section，区域最外高侧面归属紧邻内部单元。
- 每格保存原始碰撞掩码。
- `mmm:phase_occupancy` 扩展按单元保存八个微盒液相体积（单位为整格体积，每项位于 `[0, 1/8]`）。每个开放连通分量的占据之和与状态方程换算的液体体积一致。
- 气体扩展按格保存非空组分库存和内能。
- 颗粒扩展保存活动颗粒、沉积、组成与占容。
- `mmm:solver_runtime` 保存空间块成员、封闭模式、化学待处理格、颗粒床层调度、热接触、待机械推进格和最近真实压力场。

所有记录、palette、沉积组和账本按规范顺序编码，相同状态重复捕获得到相同校验和。平滑密度、PLIC、曲率、mesh、液体点样本和 UI 读数都从恢复后的库存派生，不写入存档。

## 功能布局

区域载荷布局由已提交 profile 唯一决定：`pure`、`material`、`chemical`、`granular` 或 `thermal`。各能力决定哪些扩展必须存在：

- **materials**：液体记录的材料 palette；所有记录保存整数质量。
- **chemistry**：mixture、整数物种、质量余量与化学指纹。
- **granular**：活动/沉积库存、固相组成、SVQ、质量、孔隙率与颗粒总账。
- **thermal**：非液体内能、气体热状态、外部注入和环境气体的配置温度（Kelvin）、能量审计、浮力身份和待推进格集合。非空有限热设备集合作为 manifest 扩展保存，质量与内能仍由区域 HEAD 持有。

能力存在时其 manifest 与 section 扩展成套出现，缺失时也不携带。布局、指纹、身份、总账或必需扩展不一致时文件原样保留并进入 LOCKED；未知可选扩展可以跳过，下次捕获只写当前定义的载荷。

## 启动、准入与恢复

启动先加载能力包与 world registries，再扫描 `region-state`。发现阶段只读 HEAD 与 manifest，登记库存汇总和配额；无法识别的条目若能确定 region 只锁该区域，否则禁止新建区域。

已证明的 manifest 先登记到 `RegionCatalog`（UUID、维度、AABB 与预留配额），再按当前 runtime 政策做预算与放置准入。准入失败分两类：

- **availability block**（`BUDGET`、`OVERLAP`、`CHUNK_MISSING`、`BOUNDARY_OPEN`、`POLICY_DISABLED`）：元数据、配额、checkpoint 身份和 operator intent 全部保留，条件满足后从原 HEAD 恢复。
- **LOCKED**：profile、布局、指纹或 HEAD 本身无法证明。

section 由运行恢复逐批读取，读取视图绑定区域、HEAD slot、generation 和 manifest 校验和。全部到齐后：

1. 按 registry stable ID 重建 runtime handle，校验指纹与能力。
2. 重建单元液体库存、相占据、MAC 动量、颗粒、气体和非液体热状态，执行跨域库存与能量审计。
3. 全部成功才激活 solver，随后刷新碰撞并发布客户端快照。

恢复失败的区域保持 UNLOADED/PAUSED 或 LOCKED，命令、世界桥接和容器转移都经过同一准入。operator intent 与 availability 相互独立：UNLOADED 区域也可以设定下一次运行意图，控制台在此状态下只展示 manifest 汇总。wall-time 超时计数属于进程内调度指标，重启后不保留。

## 世界热场与同步

启用世界热场时，世界 HEAD 指向同一保存时点的环境库存、全部区域快照与区域删除标记。先写不可变载荷，再原子发布世界记录；区域双槽作为该记录的读取镜像，启动时追平落后的镜像。环境节点保存结构携带归属与固定筛网轴，`CARRIED` 节点保留内能和表面库存。

checkpoint 捕获已提交的同代单元库存、固体占容、气体库存和内能，捕获期间拒绝未完成的碰撞事务。设备候选与 checkpoint 保持同代液体库存、几何与动量。气体存档细节见[气体状态](gas-state.md)。

## 单位身份

- 化学 registry 与区域 manifest 的 `QuantaPerMol` 为 1000000，化学指纹覆盖 kg/mol 与 mol/m³ 定义。
- 热注册表使用 `mmm:si_mol`，固体尺度字段为 `SvqPerCubicMeter`，气体默认组成保存 `ConcentrationMolPerStandardM3`。
- 材料单位身份为 `mmm:material_si`，参考密度字段为 `ReferenceDensityKgPerM3Bits`。

能力包、区域 HEAD 与容器 WAL 绑定完整注册表指纹。
