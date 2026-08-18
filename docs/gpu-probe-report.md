# M1 Windows/NVIDIA Vulkan GPU probe report

Date: 2026-08-19. Main Mod: Real Water 0.8.2. Optional probe Mod: `realwater_gpu_probe` 0.1.6. Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.157.0+26.2, Java 25 bytecode, LWJGL 3.4.1.

This is an M1-E correctness, data-movement and pressure-algorithm probe, not the production GPU backend. It has no takeover code, does not read world blocks or collision state, does not write a save, and owns no chunk tickets. The main `realwater` Mod remains the CPU-authoritative implementation whether the optional probe jar is installed or not. The initial two-level multigrid gate now passes without internal solids; solid-aware coarsening and a fuller hierarchy are still required before backend integration.

## Layer boundary

- Physical truth: every GPU marker stores its own `particleVolume`; the validation ledger uses particle count times `1/8` solver-cell volume. Smoothed density never estimates total water.
- Numerical discretization: 3D P2G density/marker/MAC velocity transfer, saved pre-projection velocities, gravity, selectable fixed-work pressure solvers including Jacobi and MG2, MAC projection, 90% FLIP / 10% PIC G2P, and axis-separated particle advection/collision are Vulkan compute stages.
- Rendering reconstruction: a compact density + marker + solid snapshot is generated on the GPU and sent through the existing client marching-tetrahedra payload. Marker occupancy remains the shallow-water seed.
- Scheduling: compute SSBOs require device-local memory. Initialization uploads through a host-coherent staging buffer; a normal physical step copies only a 64-byte workgroup-reduced diagnostic block to the host, and the compact surface snapshot is staged only on the client snapshot cadence. Full-state readback is an explicit benchmark/validation operation. The visual probe is a separate 20 Hz server-tick service. Three consecutive steps above 200 ms pause only the probe. It does not replace the main service's 40 ms adaptive CPU scheduler.
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

The same dam-break input is also run through device-local and persistently mapped host-visible SSBOs on each GPU. After six steps, both devices produced zero marker-mask mismatches, density-byte mean absolute error 0.0000, center-of-mass differences no greater than 0.000001 cell, and maximum-pressure differences within the validation tolerance. This is a numerical equivalence gate for the memory migration; it does not require CPU/GPU or cross-driver bit equality.

## Pressure solver comparison

Probe 0.1.4 adds two experimental solvers without changing the Poisson matrix, fluid-cell classification or projection: red-black Gauss-Seidel with SOR `omega=1.65`, and a conjugate-gradient path expressed as PCG with the identity preconditioner. Probe 0.1.5 adds a diagonal/Jacobi-preconditioned PCG comparison. PCG reuses post-grid-prep face-weight buffers for direction and matrix-vector scratch; the diagonal variant temporarily reuses the smooth-density buffer for `z=M^-1r`, after density has finished its physical role and before post-advection surface density is rebuilt. Both perform workgroup reductions before bounded global atomic accumulation and never read an iteration scalar back to the CPU. Jacobi remains the game-demo default.

Probe 0.1.6 adds a two-level geometric multigrid solver. Each V-cycle applies two unit-weight red-black Gauss-Seidel fine-grid pre-smoothing sweeps, restricts the fine residual to a half-resolution rediscretized grid, runs 24 coarse Jacobi iterations, trilinearly prolongs the correction, and applies three fine-grid post-smoothing sweeps. Restriction and prolongation use four additional device-local coarse scratch buffers. They never alter marker particles, `particleVolume`, fine marker classification, surface density or snapshot masks. The current probe has no internal solid geometry, so this result does not yet validate coarse obstacle topology.

The fixed current-domain first step gives this maximum-divergence convergence curve on the RTX 5070 Ti. Each point starts from the identical particle state; time is the complete physical step including the 64-byte diagnostic copy.

| Solver | Iterations | Post-projection max divergence | Step time |
|---|---:|---:|---:|
| Jacobi | 32 / 64 / 96 | 0.223824 / 0.145660 / 0.103966 | 0.408 / 0.660 / 0.787 ms |
| RBGS/SOR | 16 / 32 / 48 sweeps | 0.378236 / 0.105281 / 0.028933 | 0.424 / 0.654 / 0.875 ms |
| PCG, identity preconditioner | 16 / 24 / 32 | 0.209116 / 0.017929 / 0.001239 | 1.214 / 1.388 / 1.676 ms |
| PCG, diagonal preconditioner | 16 / 24 / 32 | 0.940274 / 0.031761 / 0.005039 | 0.856 / 1.035 / 1.351 ms |
| MG2 V-cycle | 1 / 2 / 3 cycles | 0.228483 / 0.065028 / 0.026593 | 0.415 / 0.676 / 0.820 ms |

Across six evolving steps, Jacobi 96, RBGS/SOR 48, identity-PCG 32, diagonal-PCG 32 and MG2 with two V-cycles produced step-six maximum divergences of approximately 0.23785, 0.05666, 0.01192, 0.01768 and 0.11593. Their representative RTX 5070 Ti averages were 0.889, 1.077, 1.396, 1.559 and 0.872 ms. All retained exactly 2,592.000 solver-cell volumes. Thus MG2 is the best small-domain low-cost candidate, while identity-PCG buys another order of magnitude in residual at higher dispatch/reduction cost. The diagonal preconditioner does not improve this matrix.

That ordering does not scale unchanged. Across the final repeated 64³ grid partial-dam runs on the RTX 5070 Ti, Jacobi 64 averaged 1.822–1.856 ms and reduced the identical first-step divergence from 0.98100 to 0.16684; MG2 with two cycles averaged 1.659–1.861 ms and reached 0.17802. By step four, MG2 consistently left 0.44299 maximum divergence versus Jacobi's 0.46867. The identical first-step MG2 curve was 0.259019 / 0.178021 / 0.139896 for one / two / three cycles at 1.387–1.431 / 1.668–1.693 / 1.883–1.914 ms minimum observed time. Identity-PCG needed 22.697–23.426 ms for a similar first-step residual. MG2 therefore establishes a real cost/quality Pareto point instead of merely spending an order of magnitude more time for a lower residual. Jacobi remains the default because the current result excludes internal solid geometry; the next pressure work is solid-aware coarsening and a fuller hierarchy, not more diagonal-PCG tuning.

## Throughput and readback

The step time includes queue completion and the regular 64-byte diagnostic staging copy. “Snapshot” is the packed density/marker/solid surface field requested separately; “full state” is a diagnostic-only staged scan of all 19 SSBOs, including CPU checksum cost. It is deliberately absent from a normal step.

| Device / memory / case | Markers | Pressure iterations | Step time | Snapshot readback | Full-state readback |
|---|---:|---:|---:|---:|---:|
| RTX 5070 Ti, device-local, current 12×8×12-block dam | 20,736 | 96 | 0.895 ms average (0.711–2.232) | 36,864 B / 0.659 ms | 2,983,744 B / 57.150 ms |
| RTX 5070 Ti, host-visible baseline, current dam | 20,736 | 96 | 7.121 ms average (6.252–8.705) | 36,864 B / 0.523 ms | 2,983,744 B / 45.546 ms |
| RTX 4060 Ti, device-local, current 12×8×12-block dam | 20,736 | 96 | 2.304 ms average (2.032–6.324) | 36,864 B / 0.721 ms | 2,983,744 B / 65.777 ms |
| RTX 4060 Ti, host-visible baseline, current dam | 20,736 | 96 | 8.720 ms average (7.056–11.941) | 36,864 B / 0.527 ms | 2,983,744 B / 46.296 ms |
| RTX 5070 Ti, device-local, full 32³ blocks / 64³ grid | 2,097,152 | 64 | 4.985 ms average (4.490–5.957) | 1,048,576 B / 15.770 ms | 84,557,888 B / 1,363.802 ms |
| RTX 4060 Ti, device-local, full 32³ blocks / 64³ grid | 2,097,152 | 64 | 7.112 ms average (6.682–7.964) | 1,048,576 B / 18.227 ms | 84,557,888 B / 1,477.106 ms |

The 32³ benchmark really allocates and advances all 2,097,152 full-water markers. Gravity is zero in that throughput case because a completely full closed box has a pressure nullspace and no free atmospheric surface. Its exact marker ledger is 262,144 solver-cell volumes, equal to 32,768 Minecraft block-volumes.

Moving the compute state off host-visible memory improves the current-domain step by about 8.0× on the RTX 5070 Ti and 3.6× on the RTX 4060 Ti. It also reverses the misleading M1-A large-domain result: the same RTX 5070 Ti 32³ case falls from roughly 655 ms to 5.52 ms once the 84 MB working set stays device-local. The roughly 17 ms 1 MiB snapshot and 1.35–1.40 s full diagnostic scan show why snapshot cadence, bounded queries and future ring staging still matter even though the physical step is fast.

The 0.1.2 integrated-server smoke on the RTX 5070 Ti measured the current domain at 0.886 ms average and the full 32³ case at 26.045 ms average while Minecraft was also rendering on that GPU. The difference from the 5.520 ms headless result is live GPU contention, not a change in particle volume or numerical stages. Both remain below the 40 ms soft budget. The requested full-state benchmark readback took 1,356.478 ms and is still intentionally excluded from normal stepping. Status reported the exact 2,592.000-cell GPU marker-volume reduction, and `remove` released the visualization without touching world state. This smoke also exposed that paused `/realwatergpu step 6` executed only one step. Probe 0.1.3 then completed all six requested paused steps (`RESULT: 6`), ran 217 total steps at 1.061 ms last-step time, retained exactly 2,592.000 cells of GPU marker volume, reduced divergence from 2.08417 to 0.00114, and removed cleanly. The command-count fix and the three-step hard-overrun guard are both now verified.

Probe 0.1.4 then passed all three integrated-server pressure benchmark commands. On the live RTX 5070 Ti, Jacobi 96 averaged 0.876 ms and ended at divergence 5.93165→0.11767, RBGS/SOR 48 averaged 1.479 ms and ended at 9.95173→0.01688, and identity-PCG 32 averaged 1.649 ms and ended at 10.12072→0.02222. These ending divergence pairs are per-solver stability evidence, not a same-input convergence comparison: each command advanced its own dam for 23 steps and therefore finished from a different particle state. The automated fresh-state curves above remain the fair convergence evidence.

Probe 0.1.5 also passed the integrated-server diagonal-PCG command. On the live RTX 5070 Ti, diagonal-PCG 32 averaged 1.783 ms over 20 steps, retained all 20,736 particles, and ended at divergence 10.12744→0.01579; regular diagnostics remained 64 B per step. This validates the command/parser and packaged shader path, while the same-input automated curves remain the evidence that diagonal preconditioning should not replace the default.

Probe 0.1.6 passed the integrated-server MG2 command. On the live RTX 5070 Ti, two V-cycles averaged 0.798 ms over 20 steps, retained all 20,736 particles, and ended at divergence 10.81817→0.29078; regular diagnostics remained 64 B per step and the full diagnostic readback reflected the four new coarse buffers. This validates the packaged command and V-cycle path. As with the earlier solver commands, the evolved ending pair is not substituted for the automated same-input convergence curves.

## Commands

The optional `realwater-gpu-probe` jar adds:

- `/realwatergpu probe [device]`: run the windowless Vulkan/Shaderc/VMA capability check.
- `/realwatergpu demo [device]`: create a paused visual dam-break about nine blocks ahead. GPU initialization failure creates an isolated CPU fallback demo; it never changes the main Mod backend.
- `/realwatergpu step [1-20]`, `run`, `pause`, `status`: control and inspect the independent demo.
- `/realwatergpu benchmark [12|32] [device] [jacobi|rbgs|pcg|pcgdiag|mg2]`: run the current-domain or real 32³-block benchmark with an optional experimental pressure solver. `mg2` uses two V-cycles. Omission keeps Jacobi. The 32 case intentionally stalls the server thread for several seconds and never accesses world data.
- `/realwatergpu remove`: free probe resources and clear the debug snapshot.

The main `/realwater` domain and the probe share the existing debug snapshot channel, so the probe refuses creation while a main domain exists and auto-pauses if one appears later.

All handled probe commands and their chat/detail output are appended as UTF-8 blocks to `logs/realwater-commands.log`; `/realwater logpath` reports the exact absolute path. This diagnostic transcript is the only new file written by the probe integration and is not world/save state.

## Remaining M1 work

- Add a dedicated-server classpath smoke. Integrated-server device-local visualization, paused multi-step control, continuous run/pause, status, both benchmark sizes, command transcript output and cleanup have passed; forced CPU fallback passed on 0.1.1.
- Replace the single reusable staging buffer and CPU wall-clock timing with bounded ring staging and Vulkan timestamp queries before calling the layout production-ready. Full-state readback remains diagnostic-only.
- Extend the successful two-level multigrid experiment to solid-aware coarse topology and a fuller hierarchy. MG2 passed the no-internal-solid two-scale cost/quality gate, while identity-PCG, diagonal-PCG and RBGS/SOR did not. Jacobi remains the correctness/default baseline until the missing geometry gate passes.
- Complete the CPU variable-density face-coefficient experiment before M3 and measure CPU/GPU break-even across more occupancies.
- Add a CPU-readable checkpoint and recovery path for runtime device loss. The main Mod already remains CPU-only; the optional visual probe currently pauses on a step failure rather than pretending incomplete buffers are valid.

Run the automated suite with the Minecraft native directory:

```powershell
./gradlew.bat :gpu-probe:runGpuFlipValidation --offline --no-daemon `
  '-PgpuProbeNativesDir=Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\26.2-Fabric 0.19.3-natives\lwjgl\3.4.1-snapshot\x64' `
  -PgpuProbeDevice=0
```
