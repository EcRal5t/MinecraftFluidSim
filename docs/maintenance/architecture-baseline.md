# 架构重整基线

本文记录 [`../archive/proposals/code-architecture-restructuring-plan.md`](../archive/proposals/code-architecture-restructuring-plan.md) 执行起点（提交 `1cf9e20`）的机器可复核指标。执行各切片时更新对应条目；数字由 `pytool/arch_baseline.py` 生成，不是跨机器性能真值。

## 超大类（1000 行硬门 allowlist）

| 模块 | 类 | 基线行数 | 删除 allowlist 的切片 |
|---|---|---:|---|
| core | `FlipFluidDomain` | 7975 → 4067（S01–S11）→ S12 删除 | 已关闭 |
| core | `FluidDomain` | S12 切换时 4031；S13 为 cheap layout identity 增至 4036 | S21 |
| fabric | `FluidSimulationService` | 3329 → 3249（S13）→ 3050（S14）→ 2412（S15）→ 2099（S16）→ 1516（S19a）→ 1509（S19b）→ 984（S19c–S19e）→ 894（S19f）→ 661（S19h） | 已关闭；剩余职责在 S21 删除 |
| fabric | `RegionState` | 1480 → S17 拆分 | 已关闭 |
| core | `FlipThermalState` | 1461 → S10 降为窄适配器 | 已关闭 |
| gpu-probe-kernel | `VulkanFlipPipeline` | 1237 → 807（device/resources/bindings/scheduler/readback 拆分） | 已关闭 |
| fabric | `FluidTransferService` | 1121 → S16 删除 | 已关闭 |
| fabric | `RegionStateFiles` | 1053 → S18 拆分 | 已关闭 |

超过 600 行但未超限的类只产生 warning，不进 allowlist（当前：`AirNetwork` 830、`VulkanFlipPipeline` 807、`ChemicalParcelStore` 695、`WorldCapabilityBundleStore` 677、`FluidSimulationService` 661、`GpuFlipProbeValidation` 611、`FluidRegion` 603）。`MmmCommands` 已由 618 行拆为 159 行 Brigadier 树与 475 行 handler；`ClientFluidVolumeRenderer` 已由 833 行降为 69 行编排，`MinecraftDebugFixtures` 为 578 行。

## 模块规模

当前值由 `pytool/arch_baseline.py` 生成：

| 模块 | 文件 | 行数 |
|---|---:|---:|
| `api/src/main` | 11 | 263 |
| `model/src/main` | 128 | 17173 |
| `solver/src/main` | 71 | 15010 |
| `application/src/main` | 24 | 1848 |
| `core/src/main` | 0 | 0 |
| `fabric/src/main` | 152 | 25704 |
| `fabric/src/client` | 14 | 1590 |
| `gpu-probe-kernel/src/main` | 17 | 3700 |
| `gpu-probe/src/main` | 5 | 734 |

## 当前被 allowlist 的方向性违规

| 规则 | 现状 | 关闭切片 |
|---|---|---|
| 静态 `Map<MinecraftServer, …>` locator | `MmmRuntimeRegistry` 是唯一主运行时 composition root；可选 GPU lifecycle 由 Mod initializer 实例字段持有 | composition root 保留 |
| 外部 adapter import god service | 仅根包 `MmmCommandHandlers` 的未拆 operator 写动作 | S21 |

`simulation.persistence` 对 live region 类型的 import 与 core 包级环均已归零，对应 allowlist 已删除。`network`/`item`/`block`/client 对 `FluidDomain`/`FlipThermalState` 的 import 当前为 0。外部 adapter 对 `FluidSimulationService` 的 import 门已生效；indicator、controller、container actor、network、API、GPU 及 Brigadier 树零例外。handler 的场景、world truth、region administration 与诊断已迁出，剩余 operator 写动作的唯一例外明示绑定 S21。

`application` 模块禁止 import `simulation`、`network`、`block`、`item` 以及 loader 类型；自检 fixture 固定这条规则。当前 application ports 只接触 immutable model/solver handoff 与 read model，没有 live domain getter。`gpu-probe-kernel` 对 Minecraft/Fabric/simulation/network/logging import 为零；adapter 只有 `MmmGpuProbeHostIntegration` 可接触主 runtime/network implementation。

`FluidSimulationService` 已无 static locator，也不再拥有 world registry、控制台、激活、indicator、container actor、场景、region administration 或诊断的消费入口；它仍构造部分 child owner，并保留 tick、剩余 operator 写操作与 shutdown/recovery 编排。S21 负责把组合上移到 `MmmRuntimeFactory` 后删除 service，不能用第二个 facade 隐藏这项债务。启动 I/O 与发现顺序已集中在 factory，状态判断集中在无 logger 输入的 `WorldStartupCoordinator`。

## 行为 characterization

- capture → restore → capture、phase-off/phase-on motion candidate 与 thermal-only candidate → restore → capture 的 raw-bit parity 已固定在 `FluidDomainStateParityValidation`，由 `lifecycle` selector 执行。
- 其余 characterization（exact transfer、lifecycle empty ledger、persistence fault matrix、启动结果）由既有 selector 覆盖：`containers`、`region-state`、`thermal`、`production-replay`；不重复建门。

## 性能基线

`production-live-budget`（默认 8×8×8 controller，20 个 live step ≤ 40 ms）维持现有门，未在本切片重新计时；第一次移动生产代码的切片（S01）落地时记录本机对照数字。
