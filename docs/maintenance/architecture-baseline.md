# 架构重整基线

本文记录 [`../archive/proposals/code-architecture-restructuring-plan.md`](../archive/proposals/code-architecture-restructuring-plan.md) 执行起点（提交 `1cf9e20`）的机器可复核指标。执行各切片时更新对应条目；数字由 `pytool/arch_baseline.py` 生成，不是跨机器性能真值。

## 超大类（1000 行硬门 allowlist）

| 模块 | 类 | 基线行数 | 删除 allowlist 的切片 |
|---|---|---:|---|
| core | `FlipFluidDomain` | 7975 | S12 |
| fabric | `FluidSimulationService` | 3329 | S21 |
| fabric | `RegionState` | 1480 | S17 |
| core | `FlipThermalState` | 1461 | S10 |
| gpu-probe | `VulkanFlipPipeline` | 1237 | S20 |
| fabric | `FluidTransferService` | 1121 | S16 |
| fabric | `RegionStateFiles` | 1053 | S18 |

超过 600 行但未超限的类只产生 warning，不进 allowlist（当前：`ClientFluidVolumeRenderer` 833、`AirNetwork` 807、`ContainerTransferStore` 768、`FluidRegionPersistence` 760、`ChemicalParcelStore` 681、`WorldCapabilityBundleStore` 677、`MmmCommands` 621、`GpuFlipProbeValidation` 617）。

## 模块规模

| 模块 | 文件 | 行数 |
|---|---:|---:|
| `api/src/main` | 11 | 263 |
| `core/src/main` | 135 | 27401 |
| `fabric/src/main` | 119 | 24475 |
| `fabric/src/client` | 8 | 1383 |
| `gpu-probe/src/main` | 12 | 3867 |

## 当前被 allowlist 的方向性违规

| 规则 | 现状 | 关闭切片 |
|---|---|---|
| `simulation.persistence` import live region 类型 | `RegionManifest`、`RegionState`、`RegionStateCodec`、`RegionStateIntegrity` | S17 |
| 静态 `Map<MinecraftServer, …>` locator | `FluidSimulationService`（SERVICES/COMMITTED_EVENTS）、`FluidProtocolServer`（会话表）、`GpuProbeService` | S14 / S19 / S20 |
| gpu-probe kernel 触及集成类型 | `GpuProbeService`、`MmmGpuProbeMod`、`WorldCollisionSnapshot` | S20 |
| core 包级环 | `cc.ecisr.mmm.fluid.air <-> cc.ecisr.mmm.thermal` | S10 |

`network`/`item`/`block`/client 对 `FlipFluidDomain`/`FlipThermalState` 的 import 当前为 0，规则立即生效，无 allowlist。

## 行为 characterization

- capture → restore → capture、phase-off/phase-on candidate → restore → capture 的 raw-bit parity 已固定在 `FluidDomainStateParityValidation`，由 `lifecycle` selector 执行。
- 其余 characterization（exact transfer、lifecycle empty ledger、persistence fault matrix、启动结果）由既有 selector 覆盖：`containers`、`region-state`、`thermal`、`production-replay`；不重复建门。

## 性能基线

`production-live-budget`（默认 8×8×8 controller，20 个 live step ≤ 40 ms）维持现有门，未在本切片重新计时；第一次移动生产代码的切片（S01）落地时记录本机对照数字。
