# 架构重整基线

本文记录 [`../archive/proposals/code-architecture-restructuring-plan.md`](../archive/proposals/code-architecture-restructuring-plan.md) 从提交 `1cf9e20` 到 S21 收口后的机器可复核指标。数字由 `uv run pytool/arch_baseline.py` 生成，不是跨机器性能真值。

## 生产类 1000 行硬门

迁移 allowlist 已清空；任何生产 Java 类超过 1000 行都会直接失败。

| 原超大类 | 收口结果 |
|---|---|
| `FlipFluidDomain` 7975 行 | S12 删除；唯一 public aggregate 为 17 行 `FluidDomain`，实现按同一 live 对象拆到 8 个 package-private responsibility partitions，最大 750 行 |
| `FluidSimulationService` 3329 行 | S21 删除；组合、tick/recovery/shutdown、policy、同步 motion 与 operator 写动作分别归 `MmmRuntimeFactory`、`MinecraftSimulationLoop`、`MinecraftRuntimePolicy`、`MinecraftMotionExecutor`、`MinecraftRegionOperatorActions` |
| `RegionState` 1480 行 | capture/validation/restore/store 已拆分；保留 27 行 durable DTO adapter，因为它仍是 active store 输入而非兼容 facade |
| `FlipThermalState` 1461 行 | S21 删除；`ThermalDomainState` 是运行时 owner，`FluidThermalBinding` 只做无状态 snapshot/restore/reseed 类型映射 |
| `FluidTransferService` 1121 行 | S16 删除 |
| `RegionStateFiles` 1053 行 | S18 删除 |
| `VulkanFlipPipeline` 1237 行 | device/resources/bindings/scheduler/readback 与 compact diagnostics 拆出后为 942 行 |

当前超过 600 行但未超限的类为：`VulkanFlipPipeline` 942、`AirNetwork` 842、`FluidDomainMechanicalFields` 750、`FluidDomainCapabilities` 749、`FluidDomainStepCoordinator` 714、`ChemicalParcelStore` 695、`WorldCapabilityBundleStore` 677、`GpuFlipProbeValidation` 653、`FluidRegion` 622、`FluidDomainCollisionState` 615、`GasLiquidPhaseChangeStage` 601。它们只产生 warning，不进入例外表；液气多 node 邻接解析仍单独归入 `GasPhaseInterfaceResolver`，stage 只拥有 extent/commit。

## 模块规模

| 模块 | 文件 | 行数 |
|---|---:|---:|
| `api/src/main/java` | 11 | 264 |
| `model/src/main/java` | 128 | 17506 |
| `solver/src/main/java` | 83 | 15865 |
| `application/src/main/java` | 24 | 1848 |
| `core/src/main/java` | 0 | 0 |
| `fabric/src/main/java` | 158 | 25804 |
| `fabric/src/client/java` | 14 | 1590 |
| `gpu-probe-kernel/src/main/java` | 18 | 4129 |
| `gpu-probe/src/main/java` | 5 | 734 |

共 441 个生产 Java 源；硬门超限类为 0。

## 依赖与所有权门

当前没有方向性迁移 allowlist。`verifyArchitecture` 直接检查：

- `api/model/solver/application` 的 loader-neutral 依赖方向和包环；
- `simulation.persistence` 不 import live region 类型；network/item/block/client 不 import solver mutable domain；
- 已删除的 `FluidSimulationService` 不得从任何生产源重新引入；
- `gpu-probe-kernel` 不接触 Minecraft/Fabric/runtime/network，GPU adapter 只有 `MmmGpuProbeHostIntegration` 可接触 host runtime/network；
- `MmmRuntimeRegistry` 必须是唯一 `Map<MinecraftServer, …>` locator；
- 生产类 1000 行硬上限与 carrier structural mutation 的唯一 coordinator。

`MmmRuntimeFactory` 是唯一 world-runtime composition root。它显式接线各语义 owner；`MmmRuntime` 只暴露按行为命名的入口，不暴露 manager/domain，也没有替代 god-service facade。`application` 只接触 immutable model/solver handoff、command/result 与窄 ports；GPU probe 仍没有 region HEAD、ownership、actor 或世界写权。

## 行为 characterization

- capture → restore → capture、phase-off/phase-on motion candidate 与 thermal-only candidate → restore → capture 的 raw-bit parity 固定在 `FluidDomainStateParityValidation`，由 `lifecycle` selector 执行。
- current world capability、ACTIVE/REMOVED region、strict codec、双槽故障和 startup 由 `region-state` 覆盖；`RegionState` 的保留不增加旧格式 reader。
- ownership 与 container fault matrix 分别由 `ownership`、`containers` 覆盖；未知/歧义证据保持 LOCKED。
- 长测试实现按 scenario 文件拆分，但 `thermal`、`region-state`、`production-replay` selector 名、顺序、fast/full 语义和场景步数保持不变。full-profile 测试构造统一经 `FullCapabilityTestFixture`，生产 registry/ledger 规则没有复制。

## 性能基线

S21 开放域气体复审最终干净运行（JDK 26.0.2、Java 25 target）的 `production-live-budget` 数据：默认 8×8×8 controller、1024 初始 markers，20 个 live logical steps median/max 为 11.302/16.863 ms，低于 40 ms 产品门；空场景最大装量 `/mmm demo` 为 24×16×24、2,592 liquid cells、20,736 初始 markers，20 步最大 174.813 ms，连续超过 200 ms 为 0。最大装量轨迹覆盖 1→3 substeps、74→267 aggregate PCG 和一次真实相变。JVM 预热单列，不计入 frame budget；性能数字只作本机无并行 Minecraft 进程时的相对证据。
