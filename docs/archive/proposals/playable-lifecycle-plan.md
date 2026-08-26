# 可玩生命周期与世界配置实施计划

## 目标

让玩家在新世界中直接制作或从创造栏取得组件，建立控制器区域、使用已实现的材料/化学/颗粒/热学/相变能力，并在退出重进后继续运行。管理命令只保留诊断、fixture 和恢复职责。

本计划优先修复的产品问题是：功能门由 `mmm/server.properties` 冻结，但命令可以为下一次启动修改它；已有区域仍绑定原载荷布局。玩家启用 thermal 后创建区域，再把配置改回或进入另一套启动设置，会得到合法但不可用的 layout mismatch LOCKED。严格拒绝错误解释是正确的，允许普通交互制造这种状态则不是合格产品行为。

## 设计决策

### 世界能力与运行策略分离

新增 world capability profile，作为该世界所有区域载荷能力的唯一解释来源。profile 在首次创建 world-local registry 或首个 controller region 前原子发布，之后不可由普通命令修改。

profile 至少冻结：

- materials、chemistry、granular、precipitation、thermal、phasechange 能力集合；
- 对应 world-local registry fingerprint；
- 能决定 checkpoint 必需 section/extension 的语义布局；
- profile 自身的 canonical fingerprint。

`server.properties` 只保留不会改变载荷解释的策略：controller 是否允许新建、world bridge 是否允许接管、region/cell/marker/parcel/substep 等预算、调度与管理员策略。改变这些策略可在重启后生效，但不得改变已提交 inventory 的字段集合。

不要从所有现有 region 猜测 profile，也不要为缺失 profile 补字段。项目不要求存档迁移，因此实现完成后只接受直接创建出的完整 profile；遇到 region 数据但没有 profile 时原样保留并 LOCKED。

### 默认能力

新世界默认 profile 应启用已经达到产品基线且具有完整 checkpoint 的能力。候选默认集合是 materials、chemistry、granular、precipitation、thermal、phasechange 全部启用。落地前逐项核对：

- 有权威 registry snapshot；
- region writer/reader 与 network negotiation 支持完整组合；
- 空库存不会产生额外 source/sink；
- 默认数据包的物性有效域覆盖水、环境气体和常见初始温度；
- 空闲成本可以休眠，未使用能力不会显著增加常态 tick 成本。

若某能力不满足这些条件，不应以运行期 toggle 规避；应把缺口列为阻止完整默认 profile 的明确 blocker，并保留一个由首次世界创建流程选择的受限 profile。profile 一旦发布就不可更改。

### 命令语义

`/mmm materials` 等改变载荷布局的 toggle 应直接移除或改成只读状态输出。若保留管理员设置入口，只允许在以下条件同时满足时写入尚未发布的 profile：

- 没有 world-local registry snapshot；
- 没有 region-state、ownership、container WAL 或未知 store issue；
- 没有 live region；
- 操作结果立即回读验证，并明确提示“世界能力已冻结”。

已经冻结后返回结构化拒绝，不能写下一个会在重启后锁区的配置。

## 工作包

### 能力 profile 真值

建议在 Fabric 持久层增加独立 codec/store，复用 `DurableFileOperations` 与 `StrictNbtFields`：

1. 定义 loader-neutral `WorldCapabilityProfile` 值对象，只使用语义 capability、fingerprint 和确定排序。
2. 定义唯一 writer shape；字段缺失、额外字段、未知 capability、重复 capability 或不合法依赖链全部拒绝。
3. 首次发布使用临时文件、force、回读、atomic rename、目录同步。
4. 启动顺序改为 profile → 所需 registry snapshot → region discovery。
5. `FluidSimulationService` 不再从易变 boolean 组合计算 region layout，而从 profile 得到唯一 layout expectation。
6. network offer、Query capability 和创建 controller 均读取同一 profile，禁止分别计算。

profile 不拥有 material/species 的定义内容；定义仍由 world registry snapshot 拥有。profile 只绑定需要哪些 registry 及其 fingerprint。

### 控制器创建和恢复

检查 `FluidControllerBlock`、controller footprint、`FluidRegionOperations` 与 `FluidRegionPersistence`：

- 首次右击若 profile 尚未发布，先完成 profile/registry 发布，再扫描 footprint；任一步失败都不修改世界或创建 region 目录。
- 创建成功后立即运行；checkpoint 首次 HEAD 发布成功前不向客户端宣称区域已创建。
- 玩家暂停意图继续随 region 保存；availability pause 不覆盖该意图。
- 边界破损、chunk 未加载、registry/profile issue 和预算不足使用不同本地化错误，不把 LOCKED 简化成“无法运行”。
- 重进世界时 profile/layout 一致的区域自动发现；若玩家意图为运行且边界可用，则自动加载和继续调度。
- 不申请永久 chunk ticket；缺 chunk 保持 PAUSED/UNLOADED，chunk 恢复后重新 admission。

### 创造模式体验入口

现有 boundary/controller 和容器继续复用。补齐以下产品入口：

- 按 stable material ID 选择的创造材料容器；化学、颗粒和热内容只能由合法 registry 定义构造。
- 控制器或专用检查工具显示区域 UUID、运行意图、availability、尺寸、预算占用、主要库存和温度范围。
- 删除/释放操作区分“只删除空区域”“释放 ownership preimage”“清空凝聚相并记 sink”；危险动作必须有明确确认，不把 `empty` 当普通 UI 按钮。
- 所有正式 item/block 有模型、贴图、创造标签、取得方式、`zh_cn`/`en_us`、tooltip 和失败反馈。
- 玩家无需知道 region UUID；位置和控制器是正常路由，UUID 只用于诊断和恢复。

不要为创造模式另写直接修改 solver 数组的捷径。创造放出继续走 existing transfer primitive 并记 external source；创造装取仍删除真实库存。

### 状态反馈

定义一个 loader-neutral、只读的 controller summary，再由 Fabric 转换成文本或界面。summary 只包含已提交状态：

- identity、dimension、footprint、network/checkpoint generation；
- operator intent、motion/thermal state、availability 或 LOCKED 原因分类；
- cell/marker/parcel/device 预算使用量；
- inventory/energy 的有界摘要；
- 未决 ownership/container/phase-work 标志。

不要把完整错误堆栈、磁盘路径、pressure array 或 live domain 暴露给客户端。Jade 继续走 Query，不读取这个管理 summary 推断流体库存。

## 失败语义

- profile/registry/checkpoint identity 不明：LOCKED，保留证据。
- 边界破损、chunk 不全、预算暂时不可用：PAUSED，不改变 operator intent。
- 玩家尝试改变已冻结 capability：拒绝命令，不修改任何文件，不锁已有 region。
- profile 首次发布失败：世界保持“尚未初始化能力”，允许修复外部条件后重试。
- 默认物性无法解释初始 ambient/温度：阻止 profile 发布，不发布半套 registries。

## 验证计划

优先扩展或替换 `region-state`、`lifecycle`、`region-manager` 的同类覆盖：

1. 新世界首次创建 profile，所有必需 registry fingerprint 闭合。
2. 创建 controller、checkpoint、停止服务、重新启动并自动继续运行。
3. 已有区域后尝试改变 capability，命令被拒绝且配置/HEAD 字节不变。
4. 改预算后重启，region layout 仍可解释；超预算区域只暂停，不被改写。
5. profile 发布各故障点只能留下无 profile 或完整 profile。
6. profile 缺字段、额外字段、错误 fingerprint 和未知 capability 均保留并锁定。
7. 暂时卸载 required chunk 后恢复，operator intent 不改变。

真实游戏只需要一个最终场景：新建世界，创造栏取得组件，建闭壳并注入热液体，退出到标题再进入，确认区域自动继续且温度/液面可观察。

## 推荐提交顺序

1. `WorldCapabilityProfile` 与严格 codec/store，不接运行时。
2. 启动读取和唯一 layout derivation，替换 boolean 拼装。
3. 移除危险 toggle 写入，增加冻结状态反馈。
4. 默认 profile 与首次 controller 初始化事务。
5. 控制器 summary、创造入口和本地化。
6. architecture/routing/roadmap 收口。

每个提交都只跑 routing 对应最近门。最后需要部署时再执行 clean assemble。
