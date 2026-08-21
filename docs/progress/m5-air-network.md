# M5 空气区域网络与气液压力

完成日期：2026-08-22。版本 0.11.0。活动核心仍是 `FlipFluidDomain`，CPU 权威；本阶段为纯 CPU 改动，未触及 wire、`region-state`、takeover journal 与 GPU probe。

## 交付内容

- `com.example.fluid.air` 独立气体网络引擎：`AirNode`（状态）、`AirEdge`（孔流边）、`AirNetwork`（等温 EoS、CONTAM 幂律孔流、拓扑事件库存迁移、vent/drawn 账本）。
- `AirCavityScanner`（`com.example.fluid`）：预算内 flood fill 空腔拓扑；触达边界层或预算耗尽分类 ambient；完整开放面连通、部分开放面成为几何孔流边；`V_avail` 从平滑液体体积场连续推导。
- `FlipFluidDomain` 接入：每子步重扫 + 库存多数归属迁移 + `V_avail` 刷新 + 孔流步进；自由面 ghost-fluid 跳跃升级为 `p_interface = σκ + p_gas(空气格所属腔)`，压力 RHS 与投影两处消费点一致；ambient 贡献恰好为零，无密闭腔时与 M4 逐位一致。
- `/realwater airdemo [pressure|vacuum]` 瞬态开发命令与 `/realwater status` 气体段（节点压力、库存、vented/drawn）。
- Phase 0 前置重构：表面张力法向每平滑趟缓存一次，曲率只读缓存（M4 审阅遗留的 3 倍冗余消除），M4 门数值与重构前完全一致。

## 验收证据（自动门）

| 门 | 结果 |
|---|---|
| `runAirNetworkValidation` | 等温 EoS 分支精确；双腔均压 7 步收敛到 1.1e-11 Pa；开孔/封孔冻结逐位；分裂/合并/vent 账本恒等；环境排/吸气 ≤20 步收敛 |
| `runAirCavityScannerValidation` | 开放水池/开口 U 管 ambient、密封箱体积精确（含 h=0.5 单位换算）、多腔确定性 id、1/4 开口几何边面积、界面残差归属、预算停止 fail-open |
| `runM5GasLiquidCouplingValidation` | 准静态压缩单调升压、平衡 `p_gas` 与 `ρgΔh` 误差 **0.91%**；负压悬液 **6.73%**（≤10% 门）；孔链 797 步均压到 1%、5 步后仍有 80% 压差（非瞬时）；全过程气体账本 `sealed+vented==drawn` 精确、marker 体积/质量逐位守恒、开放域无密闭腔（退化路径） |
| `runM4SurfaceTensionValidation`（共享契约升级复跑） | 静滴误差 0.998%、液液 20.5%、σ=0 逐位退化——与 M5 改动前数值完全一致 |
| `runM3ConnectedVesselValidation`、`runFlipFreeSurfaceValidation`（里程碑汇总） | 通过；M3 不等截面连通器均面 4.000→0.188 |

## 与提案的偏差（均为实施时决策）

1. **纳体积量子账本**：气体库存用 `long`（1e-9 m³）而不是 double，使 `sealed + vented == drawn` 在 long 算术下严格成立（两份提案的 double 成对 ± 无法保证逐位守恒）。所有气体来源记入 drawn，新腔环境平衡初始化即 ambientSource 近似的账本化。
2. **无 `gasEnabled` 配置门**：气体不改变持久化与协议，开放水域与 M4 逐位一致，故不引入配置门与 formatVersion 升级（qwen 提案的方案）。控制器区域密闭腔自动获得真实气压。
3. **`V_avail` 用平滑体积场**：精确 marker 残差（1/8 格量化）在 reseed 重组下产生 ~0.4 m³/步的体积噪声，经气体弹簧共振放大为持续 0.05 m/s 搅动；改用平滑场后归属连续，噪声消除。`V_avail` 属数值离散，不动 marker 真值。
4. **准静态验收协议**：猛灌水会卷入气泡使体积折算液面失真（诊断确认压力剖面本身完全水静力学一致）；验收场景改为缓慢补水，同时覆盖规格"压力随可用体积单调增加"条目。
5. **孔流均衡钳制**：输运量钳制到均压分配而不是 0.5 源库存比例，压差单调衰减、无子步循环。
6. **M3 门场景修正**：其连通器是从实体雕出的封闭容器，柱顶空气在 M5 后成为真实气垫抵抗均面（正确物理）；给柱顶开边界层气口恢复该门"纯水力学均面"的原假设。

## 已量化限制

- 重启后密闭腔按环境平衡重建（`p=0`）；气体状态进存档需 region-state v3 评审，属后续格式迁移。
- 新发现密闭腔按环境平衡初始化（记入 drawn），不从周围液相携压；液面完全覆盖的腔 vent 库存，亚步级闪烁会造成等价于环境重置的瞬时压力跳变。
- 无黏性求解器中气液耦合柱振荡以有界幅度持续（观测 ≤0.16 m/s，不增长、不发散）；平衡以时间平均判定。
- 气体库存重启丢失之外，`exportParticleState` 对 reseed 后含部分体积 marker 的纯水域会因 mass-volume 1e-12 严格校验抛出（M5 调试中发现，属既有行为，测试改用平滑场测量）。
- 512 BV 天然空腔搜索预算参数已就位（`scanBudgetCells`），天然空腔搜索入口本身属未来阶段。

## 实机 smoke

待部署后执行：`/realwater airdemo pressure` → `/realwater run`，观察左腔液面被气压压下、右腔抬高并保持稳定差（约 1.8 m）；`/realwater airdemo vacuum` 观察左腔被吸升约 1.3 m；`/realwater status` 应显示 `gas node 1 p=…Pa V0=…m³`。
