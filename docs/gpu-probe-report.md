# M1 Windows/NVIDIA Vulkan GPU probe report

Date: 2026-08-18. Main Mod: Real Water 0.8.1. Optional probe Mod: `realwater_gpu_probe` 0.1.0. Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.157.0+26.2, Java 25 bytecode, LWJGL 3.4.1.

This is an M1-A correctness and data-movement probe, not the production GPU backend and not the completion of all M1 pressure-algorithm experiments. It has no takeover code, does not read world blocks or collision state, does not write a save, and owns no chunk tickets. The main `realwater` Mod remains the CPU-authoritative implementation whether the optional probe jar is installed or not.

## Layer boundary

- Physical truth: every GPU marker stores its own `particleVolume`; the validation ledger uses particle count times `1/8` solver-cell volume. Smoothed density never estimates total water.
- Numerical discretization: 3D P2G density/marker/MAC velocity transfer, saved pre-projection velocities, gravity, fixed-iteration Jacobi pressure, MAC projection, 90% FLIP / 10% PIC G2P, and axis-separated particle advection/collision are Vulkan compute stages.
- Rendering reconstruction: a compact density + marker + solid snapshot is generated on the GPU and sent through the existing client marching-tetrahedra payload. Marker occupancy remains the shallow-water seed.
- Scheduling: the visual probe is a separate 20 Hz server-tick service. Three consecutive steps above 200 ms pause only the probe. It does not replace the main service's 40 ms adaptive CPU scheduler.
- World transaction: none. `/realwatergpu remove` releases memory and clears its debug snapshot only.

## Runtime capability

The windowless capability path creates no GLFW window and no Vulkan surface. It creates an instance, selects a compute queue, creates a logical device, initializes and destroys a VMA allocator, compiles a compute shader with Shaderc, dispatches an SSBO checksum kernel, and verifies its readback.

| Device index | Device | Vulkan / driver | Compute dispatch | Float atomic extension | Local device memory |
|---:|---|---|---:|---|---:|
| 0 | NVIDIA GeForce RTX 5070 Ti | 1.4.329 / 595.79 | 1.466 ms | `VK_EXT_shader_atomic_float` | 15.62 GiB |
| 1 | NVIDIA GeForce RTX 4060 Ti | 1.4.329 / 595.79 | 1.305 ms | `VK_EXT_shader_atomic_float` | 15.73 GiB |

Both devices used compute queue family 2, reported a maximum compute workgroup size of 1024, and passed Shaderc, VMA and checksum validation. The Minecraft runtime manifest contains `lwjgl-shaderc` and `lwjgl-vma` 3.4.1; its extracted Windows x64 native directory contains `shaderc.dll` and `lwjgl_vma.dll`, so the probe does not bundle a second LWJGL version.

## Correctness results

The fixed dam-break input is a `24×16×24` solver grid with `h=0.5`, `dt=0.05`, density 1000, gravity 9.81 and 20,736 markers. Six CPU and GPU steps retain exactly 2,592.000 solver-cell volumes (324.000 Minecraft block-volumes). Cross-backend results are accepted by conservation and tolerance, not bit equality.

| Check | RTX 5070 Ti result | RTX 4060 Ti result |
|---|---:|---:|
| CPU/GPU X center-of-mass difference after 6 steps | 0.0762 cell | 0.0762 cell |
| Pre-projection maximum divergence | 1.515274 | 1.515274 |
| Post-projection maximum divergence | 0.237851 | 0.237851 |
| Post-advection occupied marker cells after 6 steps | 2,568 | 2,568 |

The static-column case retains 375.000 solver-cell volumes. After eight steps on the RTX 5070 Ti, maximum pressure is 14,316.000 Pa, mean bottom-layer pressure is 9,391.479 Pa, mean top-layer pressure is 1.468 Pa, and maximum divergence falls from 2.880272 before projection to 0.000005 after projection. This verifies pressure direction and projected incompressibility for this bounded case.

## Throughput and readback

These figures use persistently mapped, host-visible coherent SSBOs. They deliberately measure a simple correctness layout before selecting a production device-local/staging layout.

| Device / case | Markers | Pressure iterations | Step time | Compact readback | Full-state readback |
|---|---:|---:|---:|---:|---:|
| RTX 5070 Ti, current 12×8×12-block dam | 20,736 | 96 | 7.214 ms average (6.152–8.984) | 36,864 B / 0.580 ms | 2,965,312 B / 52.873 ms |
| RTX 4060 Ti, current 12×8×12-block dam | 20,736 | 96 | 2.613 ms average (2.166–3.278) | 36,864 B / 0.586 ms | 2,965,312 B / 58.160 ms |
| RTX 5070 Ti, full 32³ blocks / 64³ grid | 2,097,152 | 64 | 655.271 ms average (651.785–657.227) | 1,048,576 B / 17.097 ms | 84,033,600 B / 1,333.124 ms |

The 32³ benchmark really allocates and advances all 2,097,152 full-water markers. Gravity is zero in that throughput case because a completely full closed box has a pressure nullspace and no free atmospheric surface. Its exact marker ledger is 262,144 solver-cell volumes, equal to 32,768 Minecraft block-volumes.

The smaller 4060 Ti outperforming the 5070 Ti on this layout is evidence that host-visible memory topology and synchronization dominate this probe. The full-state readback cost also confirms that production steps must keep particles and grid state device-local and read back only compact snapshots and bounded diagnostics.

## Commands

The optional `realwater-gpu-probe` jar adds:

- `/realwatergpu probe [device]`: run the windowless Vulkan/Shaderc/VMA capability check.
- `/realwatergpu demo [device]`: create a paused visual dam-break about nine blocks ahead. GPU initialization failure creates an isolated CPU fallback demo; it never changes the main Mod backend.
- `/realwatergpu step [1-20]`, `run`, `pause`, `status`: control and inspect the independent demo.
- `/realwatergpu benchmark [12|32] [device]`: run the current-domain or real 32³-block benchmark. The 32 case intentionally stalls the server thread for several seconds and never accesses world data.
- `/realwatergpu remove`: free probe resources and clear the debug snapshot.

The main `/realwater` domain and the probe share the existing debug snapshot channel, so the probe refuses creation while a main domain exists and auto-pauses if one appears later.

## Remaining M1 work

- Load the optional jar in the actual integrated-server and dedicated-server classpaths; the standalone headless path and both local GPUs are verified, but the in-game visual smoke is still pending.
- Move production candidates to device-local memory with staging/ring buffers and timestamp queries; keep full-state readback diagnostic-only.
- Compare pressure PCG/global reductions, red-black GS/SOR, and a multigrid or preconditioned route, including residual curves and dispatch/barrier cost. Current Jacobi is only the correctness baseline.
- Complete the CPU variable-density face-coefficient experiment before M3 and measure CPU/GPU break-even across more occupancies.
- Add a CPU-readable checkpoint and recovery path for runtime device loss. The main Mod already remains CPU-only; the optional visual probe currently pauses on a step failure rather than pretending incomplete buffers are valid.

Run the automated suite with the Minecraft native directory:

```powershell
./gradlew.bat :gpu-probe:runGpuFlipValidation --offline --no-daemon `
  '-PgpuProbeNativesDir=Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\26.2-Fabric 0.19.3-natives\lwjgl\3.4.1-snapshot\x64' `
  -PgpuProbeDevice=0
```
