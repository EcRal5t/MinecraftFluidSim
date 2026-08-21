# M5 气体网络与气液压力代码审阅报告（deepseek）

- **审阅对象**：`b8147b7ae867f`（M5 起点，含 Phase 0 表面张力法向缓存重构）至 `8226745`（HEAD）共 9 个提交；其中 `b8147b7..HEAD` 的 8 个提交为主要 M5 实现，`b8147b7` 本身通过代码审阅与 M4 门复跑验证数值等价
- **审阅基线**：主 Mod `0.11.0`；Minecraft 26.2 / Fabric Loader 0.19.3 / Fabric API 0.157.0+26.2 / JDK 26.0.2 / target Java 25
- **审阅方式**：逐文件阅读 M5 变更、核对 `docs/architecture/fluid-solver.md` 与 `docs/maintenance/routing.md` 中的当前契约，并实际重跑最贴近改动的自动门
- **最终结论**：**有条件合格（Qualified with conditions）**——M5 主路径物理正确、自动门全部通过、M4 开放水域退化数值与签收证据一致，可以继续 M6；但存在 1 个违反“新腔按环境平衡初始化”的中等缺陷，建议在下一阶段签收前修复并补门禁，另有一批低风险工程项应记录或顺手处理。

---

## 1. 验证执行记录

实际运行（离线、无 daemon，JAVA_HOME 指向 `D:\ProgramFiles\BienCreng\jdk-26.0.2`）：

```powershell
./gradlew.bat runAirNetworkValidation runAirCavityScannerValidation runM5GasLiquidCouplingValidation --offline --no-daemon
./gradlew.bat runM4SurfaceTensionValidation runM3ConnectedVesselValidation --offline --no-daemon
./gradlew.bat runFluidNetworkProtocolValidation --offline --no-daemon
```

结果：

| 门 | 结果 | 与 M5 进度文档对照 |
|---|---|---|
| `runAirNetworkValidation` | 通过：双腔 7 步均压到 1.09e-11 Pa；环境排/吸气 ≤20 步；账本恒等 | 一致 |
| `runAirCavityScannerValidation` | 通过：开放/密封分类、体积换算、几何边、残差归属、预算 fail-open | 一致 |
| `runM5GasLiquidCouplingValidation` | 通过：准静态压缩 0.91%（门 ≤8%）；负压悬液 6.73%（门 ≤10%）；孔链 797 步均压到 1%，5 步仍 >80%；marker 体积/质量守恒；账本恒等 | 一致 |
| `runM4SurfaceTensionValidation` | 通过：静滴 0.998%、液液 20.5%，数值与 M5 改动前签收值一致 | 一致 |
| `runM3ConnectedVesselValidation` | 通过：连通器 4.000→0.188 | 一致 |
| `runFluidNetworkProtocolValidation` | 通过：v1 快照扩展 codec 正常，未知扩展可跳过 | 未列入 M5 进度，但按路由属于 gas debug extension 的对应门 |

结论：M5 声称的自动门证据可复现，且 M4/M3 回归未暴露退化路径回归。

---

## 2. 分模块评价

### 2.1 `com.example.fluid.air`（AirNode / AirEdge / AirNetwork）

- **气体真值设计正确**：库存为 `long` 纳体积量子，`sealed + vented == drawnFromAmbient` 在 long 算术下严格成立；`setStandardVolume`、孔流、merge/split/vent/remap 均维护账本。
- **孔流数值离散正确**：CONTAM 幂律 + 均压钳制，压差单调衰减不超调；环境边按 gauge-zero 库存钳制，方向正确。
- **发现的中等缺陷**：新腔环境平衡初始化与 EoS 的 `V_min` 正则化不一致，详见 3.1。
- **低风险**：`remapNodes`/`addNode` 对负库存静默钳 0；`addOrMergeEdge` 合并平行边时用新 `dischargeCoefficient` 覆盖旧值；`mergeNodes` 在参数异常时非原子。当前生产路径未触发，但 API 应更严格。

### 2.2 `AirCavityScanner`

- **拓扑分类正确**：六邻域显式栈 flood fill，避免递归栈溢出；触达域边界或预算耗尽即 ambient，不保存有限库存。
- **体积推导符合 M5 数值离散**：空气格体积为 `(openVolume - clamped smoothed liquid) * cellVolume`，界面格残差归属相邻最小 node id 且 ambient 优先。
- **几何边正确**：部分开口面成为 `GeometricEdge`，面积按 cut-cell face aperture 累计。
- **预算语义**：`FlipFluidDomain` 传入 `scanBudgetCells = cellCount`，因此活动核心实际不会触发预算早停。对当前有界控制器域这是可接受的（已知边界、应全扫），但“fail-open 预算”目前在活动核心是名义上的；未来天然空腔搜索才需要真正的 512 BV 预算。
- **低风险**：`isAirCell` 只查 `markerCount == 0` 与 `openCellVolume > 0`，不查平滑液量。若出现无 marker 但平滑液量接近开度的“湿空气格”，会被扫成可用体积极小的密闭腔，正好触发 3.1 的初始化缺陷。建议把这类格并入界面残差或要求空气体积 > 最小阈值。

### 2.3 `FlipFluidDomain` 活动核心耦合

- **两处消费点一致**：`surfaceTensionBoundaryFace`（RHS）与 `pressureDifference`（投影）使用同一个 `jump = surfaceTensionPressure + gas`，`theta` 与密度缩放仍与 M4 共享。
- **M4 退化逐位成立**：无密闭腔时 `gasPressureActive == false`，`gasPressureOfAirCell` 直接返回 0；有密闭腔但气压恰好为 0 时走原 `surfaceTensionPressure` 分支。M4 门数值复现一致，证明重构与 M5 插入未改变开放水域路径。
- **库存迁移**：多数归属迁移与真分裂余量分配逻辑正确；旧腔触达 ambient 时经 remap 差额记为 vented。
- **低风险**：`overlap` 矩阵为 `[previousSealedNodeCount + 1][sealed + 1]` 的 int 二维数组。默认域 9216 格时最坏约 340 MB，多单格腔拓扑可能造成 GC 压力或 OOM。建议改为稀疏映射（每个旧腔遍历其格并投递到新 id 的 map），M6 前评估。

### 2.4 服务层 `airdemo` 与调试渲染

- `airdemo` 脚手架只替换空气、remove/替换域/关服时恢复，符合“dev demo 瞬态”边界。
- 客户端 gas 调试蒙版只读消费服务端快照，未反写物理。
- **低风险**：`createAirDemo` 在“左侧气腔未密闭”失败路径上不会恢复已放置的玻璃脚手架，也不会清掉已创建的瞬态域；建议失败即回滚。
- **文档矛盾**：`docs/architecture/fluid-solver.md` 写“气体状态 runtime-only，不进 wire、`region-state` 或客户端快照”，但服务端确实通过 `fluid_snapshot_v1` 的 `EXTENSION_GAS_NODES` 发送了调试用 gas node mask。应把架构文档改为“气体库存/压力不入快照；仅发送调试用 gas node mask 扩展”，或直接去掉扩展。

---

## 3. 缺陷与风险清单

### 3.1（中等）新腔在 `V_avail < V_min` 时不是环境平衡，违反架构契约

- **契约**：`docs/architecture/fluid-solver.md` 与 M5 进度均写明“新腔按环境平衡初始化（`p=0`）”。
- **代码路径**：`updateGasNetwork` 对新腔调用 `AirNetwork.nanos(availableVolumes[node - 1])`，随后 `AirNetwork.remapNodes` 以 `new AirNode(available, inventory)` 建节点；`gaugePressure` 计算 `P0 * (V0 / max(V, V_min) - 1)`。
- **问题**：当新腔可用体积小于 `MIN_AVAILABLE_VOLUME = 1.0E-4` 时，`V0 = V_avail < V_min`，但分母被钳到 `V_min`，于是新腔一出生就带负压。
- **复现实测**（jshell，classpath 为编译产物）：

```text
AirNetwork n = new AirNetwork();
n.addNode(5.0E-5, 5.0E-5) -> gaugePressure = -50662.5 Pa
n.addNode(1.0E-5, 1.0E-5) -> gaugePressure = -91192.5 Pa
n.remapNodes(new double[]{5.0E-5}, new long[]{AirNetwork.nanos(5.0E-5)})
                         -> gaugePressure(1) = -50662.5 Pa
```

- **影响**：极小的密闭气泡/湿空气格首次被发现时会表现为强真空，可能拉扯邻近液体；若它同时有通向 ambient 的几何边，还会先吸入空气去填正则化缺口，污染精确 drawn 账本。
- **建议**：在 `addNode`/`remapNodes` 的环境平衡初始化路径上保证 `gaugePressure == 0`，并新增 `V_avail < V_min` 的定向门禁用例；若正则化语义改变，同步更新 `fluid-solver.md`。

### 3.2（低）`overlap` 二维矩阵内存随腔数平方增长

`updateGasNetwork` 为 `[previousSealedNodeCount + 1][sealed + 1]` 分配 int 矩阵。默认 9216 格最坏接近 340 MB，极端多腔拓扑可能 OOM。当前实际场景风险低，建议 M6 前改为稀疏迁移。

### 3.3（低）架构文档与快照扩展矛盾

见 2.4。建议改架构文档，不削弱任何不变量。

### 3.4（低）`createAirDemo` 失败路径不清理

见 2.4。`lastTakeoverFailure = "左侧气腔未密闭..."` 时，玻璃脚手架和瞬态域仍留在世界中。建议失败时恢复脚手架并移除/替换当前瞬态域。

### 3.5（低）`remapNodes` 未校验负库存

`new AirNode(available, standardVolumeNanos)` 内部 `Math.max(0, standardVolumeNanos)` 会把负值静默钳为 0，而 `remapNodes` 的 `newSum` 仍用原始负值数组求和，理论上会破坏账本恒等式。生产路径目前不会产生负值，但这是掩盖错误的入口，应改为显式 `IllegalArgumentException`。

### 3.6（低）gas debug extension 的 64 KiB 限制

`FluidSnapshotPayload.MAX_EXTENSION_BYTES = 1 << 16`，而 `EXTENSION_GAS_NODES` 数据长度等于 `cellCount`。当前默认域 9216 格远小于限制；但若未来 CPU 域超过 65,536 格（例如 64³）且存在密闭腔，`createSnapshot` 会抛异常。建议在域过大时跳过该调试扩展，或随协议版本提高限制。

### 3.7（低）其他小项

- `addOrMergeEdge` 合并平行边时用新 `dischargeCoefficient` 覆盖旧值；当前扫描器总是 0.65，无实际影响。
- `mergeNodes` 在 `sourceNodeIds` 后续位置再次出现 target 时会先合并前面的 source 再抛异常，非原子；当前生产拓扑事件走 `remapNodes`，无实际影响。
- `git diff --check b8147b7ae867f..HEAD` 报 `docs/progress/m5-air-network.md:47: new blank line at EOF`。

---

## 4. 测试质量评价

- **覆盖面**：三个 M5 门分别覆盖无网格 EoS/孔流/账本、扫描器拓扑/体积/几何边/预算、活动核心气液耦合；M4/M3 门覆盖退化路径与旧契约；协议门覆盖扩展 codec。符合“最小充分验证”原则，没有为同一不变量重复建套件。
- **缺陷**：缺少 `V_avail < V_min` 的新腔初始化用例，导致 3.1 被漏掉；也缺少“多腔/大域”和“扩展超 64 KiB”的边界用例。建议在现有 `AirNetworkValidation` 中扩展一个 tiny-cavity equilibrium 用例，而不是新建测试套件。

---

## 5. 最终结论

- **是否合格以继续开发**：**可以继续 M6**。M5 的气体真值账本、扫描器拓扑分类、活动核心气液压力耦合与 M4 退化路径均经代码审阅和自动门复现验证，未发现会破坏液体 marker 真值、协议、持久化或世界事务的结构性缺陷。
- **但不建议当前就视为无条件签收**：3.1 是明确的契约违反，且现有测试未覆盖；建议在 M6.0 设计评审前完成修复并补一个定向用例。其余低风险项可并入 M6.0 或作为快速清理提交处理。
- **不需要**：重新构建部署、进游戏验证或运行 5,000 步长期门；本次结论不改变自动门范围。
