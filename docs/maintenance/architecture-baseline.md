# 架构重整基线

本文记录 [`../archive/proposals/code-architecture-restructuring-plan.md`](../archive/proposals/code-architecture-restructuring-plan.md) 执行起点（提交 `1cf9e20`）的机器可复核指标。执行各切片时更新对应条目；数字由 `pytool/arch_baseline.py` 生成，不是跨机器性能真值。

## 超大类（1000 行硬门 allowlist）

| 模块 | 类 | 基线行数 | 删除 allowlist 的切片 |
|---|---|---:|---|
| core | `FlipFluidDomain` | 7975 → 4067（S01–S11）→ S12 删除 | 已关闭 |
| core | `FluidDomain` | S12 切换时 4031；S13 为 cheap layout identity 增至 4036 | S21 |
| fabric | `FluidSimulationService` | 3329 → 3249（S13）→ 3050（S14）→ 2412（S15）→ 2099（S16）→ 1516（S19a）→ 1509（S19b） | S21 |
| fabric | `RegionState` | 1480 | S17 |
| core | `FlipThermalState` | 1461 | S10 |
| gpu-probe | `VulkanFlipPipeline` | 1237 | S20 |
| fabric | `FluidTransferService` | 1121 | S16 |
| fabric | `RegionStateFiles` | 1053 | S18 |

超过 600 行但未超限的类只产生 warning，不进 allowlist（S19g 后：`AirNetwork` 830、`ChemicalParcelStore` 695、`WorldCapabilityBundleStore` 677、`GpuFlipProbeValidation` 617、`MmmCommands` 614、`FluidRegion` 603）。`ClientFluidVolumeRenderer` 已由 833 行降为 69 行编排，`MinecraftDebugFixtures` 为 578 行。

## 模块规模

S19g 后的当前值，由 `pytool/arch_baseline.py` 生成：

| 模块 | 文件 | 行数 |
|---|---:|---:|
| `api/src/main` | 11 | 263 |
| `core/src/main` | 214 | 33393 |
| `fabric/src/main` | 150 | 25830 |
| `fabric/src/client` | 14 | 1590 |
| `gpu-probe/src/main` | 12 | 3868 |

## 当前被 allowlist 的方向性违规

| 规则 | 现状 | 关闭切片 |
|---|---|---|
| `simulation.persistence` import live region 类型 | `RegionManifest`、`RegionState`、`RegionStateCodec`、`RegionStateIntegrity` | S17 |
| 静态 `Map<MinecraftServer, …>` locator | 主运行时已只剩 `MmmRuntimeRegistry` composition root；`FluidProtocolServer`（会话表）、`GpuProbeService` 仍待收口 | 主运行时已关闭 / S19 / S20 |
| gpu-probe kernel 触及集成类型 | `GpuProbeService`、`MmmGpuProbeMod`、`WorldCollisionSnapshot` | S20 |
| core 包级环 | `cc.ecisr.mmm.fluid.air <-> cc.ecisr.mmm.thermal` | S10 |

`network`/`item`/`block`/client 对 `FluidDomain`/`FlipThermalState` 的 import 当前为 0，规则立即生效，无方向 allowlist。

`core/.../application` 禁止 import `simulation`、`network`、`block`、`item` 以及 loader 类型；S13 自检 fixture 固定这条规则。当前 application ports 只接触 immutable core handoff/read model，没有 live domain getter。

`FluidSimulationService` 已无 static locator，构造器只接收 startup result、repository/manager、snapshot/container adapter 和 runtime-owned event hub，不执行 policy/capability/metadata/WAL I/O。启动 I/O 与发现顺序集中在 `MmmRuntimeFactory`，状态判断集中在无 logger 输入的 `WorldStartupCoordinator`。

## 行为 characterization

- capture → restore → capture、phase-off/phase-on motion candidate 与 thermal-only candidate → restore → capture 的 raw-bit parity 已固定在 `FluidDomainStateParityValidation`，由 `lifecycle` selector 执行。
- 其余 characterization（exact transfer、lifecycle empty ledger、persistence fault matrix、启动结果）由既有 selector 覆盖：`containers`、`region-state`、`thermal`、`production-replay`；不重复建门。

## 性能基线

`production-live-budget`（默认 8×8×8 controller，20 个 live step ≤ 40 ms）维持现有门，未在本切片重新计时；第一次移动生产代码的切片（S01）落地时记录本机对照数字。
