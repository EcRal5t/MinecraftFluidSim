# M1 Windows/NVIDIA Vulkan GPU probe report

Date: 2026-08-19. Main Mod: Real Water 0.8.2. Optional probe Mod: `realwater_gpu_probe` 0.1.10. Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.157.0+26.2, Java 25 bytecode, LWJGL 3.4.1.

This is an M1-G correctness, data-movement and collision-ingestion probe, not the production GPU backend. It has no takeover code, never writes blocks or save data, and owns no chunk tickets. The ordinary generated demo and every headless benchmark remain world-independent. The explicit `worlddemo` command captures collision once from chunks already loaded around the player, then runs only from the frozen in-memory subcell mask. The main `realwater` Mod remains CPU-authoritative whether the optional probe jar is installed or not.

## Layer boundary

- Physical truth: every GPU marker stores its own `particleVolume`; the validation ledger uses particle count times `1/8` solver-cell volume. Smoothed density never estimates total water.
- Numerical discretization: 3D P2G density/marker/MAC velocity transfer, saved pre-projection velocities, gravity, selectable fixed-work pressure solvers including Jacobi, MG2 and packed full-hierarchy MGH, MAC projection, 90% FLIP / 10% PIC G2P, and axis-separated particle advection/collision are Vulkan compute stages.
- Rendering reconstruction: a compact density + marker + solid snapshot is generated on the GPU and sent through the existing client marching-tetrahedra payload. Marker occupancy remains the shallow-water seed.
- Scheduling: compute SSBOs require device-local memory. Initialization uploads through a host-coherent staging buffer; a normal physical step copies only a 64-byte workgroup-reduced diagnostic block to the host, and the compact surface snapshot is staged only on the client snapshot cadence. Full-state readback is an explicit benchmark/validation operation. The visual probe is a separate 20 Hz server-tick service. Three consecutive steps above 200 ms pause only the probe. It does not replace the main service's 40 ms adaptive CPU scheduler.
- World transaction: none. `worlddemo` is a bounded read-only snapshot, not a takeover. It obtains only currently loaded chunks through the non-loading `getChunkNow` path, includes a one-block horizontal halo for contextual shapes, samples the same 2×2×2 collision-shape intersections as the CPU service, and refuses creation if any required chunk is absent. It never refreshes the frozen mask automatically. `/realwatergpu remove` releases memory and clears its debug snapshot only.

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

The four-level MGH column gate uses a 64³ grid and 169,344 markers carrying exactly 21,168.000 solver-cell volumes. On both GPUs, eight steps retained that exact ledger, produced 40,151 Pa maximum pressure with 25,016 Pa at the bottom and 0.705 Pa at the top, and reduced divergence from 2.18506 to 0.09383. This is a deep-hierarchy pressure-direction, divergence and conservation gate; it does not claim that two fixed V-cycles have converged the pressure magnitude to a continuum analytic solution.

The same dam-break input is also run through device-local and persistently mapped host-visible SSBOs on each GPU. After six steps, both devices produced zero marker-mask mismatches, density-byte mean absolute error 0.0000, center-of-mass differences no greater than 0.000001 cell, and maximum-pressure differences within the validation tolerance. This is a numerical equivalence gate for the memory migration; it does not require CPU/GPU or cross-driver bit equality.

## Pressure solver comparison

Probe 0.1.4 adds two experimental solvers without changing the Poisson matrix, fluid-cell classification or projection: red-black Gauss-Seidel with SOR `omega=1.65`, and a conjugate-gradient path expressed as PCG with the identity preconditioner. Probe 0.1.5 adds a diagonal/Jacobi-preconditioned PCG comparison. PCG reuses post-grid-prep face-weight buffers for direction and matrix-vector scratch; the diagonal variant temporarily reuses the smooth-density buffer for `z=M^-1r`, after density has finished its physical role and before post-advection surface density is rebuilt. Both perform workgroup reductions before bounded global atomic accumulation and never read an iteration scalar back to the CPU. Jacobi remains the game-demo default.

Probe 0.1.6 adds a two-level geometric multigrid solver. Each V-cycle applies two unit-weight red-black Gauss-Seidel fine-grid pre-smoothing sweeps, restricts the fine residual to a half-resolution grid, runs 24 coarse Jacobi iterations, prolongs the correction, and applies three fine-grid post-smoothing sweeps. Probe 0.1.7 makes its coarse topology solid-aware: fine-grid connections are aggregated into a coarse diagonal and positive X/Y/Z face weights, fluid-solid faces remain closed, fluid-air faces retain their zero-pressure diagonal contribution, and prolongation near mixed-solid aggregates uses local injection instead of interpolating through a wall. Eight coarse scratch buffers remain numerical state only; they never alter marker particles, `particleVolume`, fine marker classification, surface density or snapshot masks.

Probe 0.1.9 adds `MGH`, an adaptive full V-cycle using the same eight scratch-buffer roles packed across every coarse level. The current 24×16×24 grid stops at its useful 12×8×12 first level and is numerically equivalent to MG2. A 64³ grid traverses 32³→16³→8³→4³. Each recursive restriction carries the residual plus solid-aware diagonal and X/Y/Z face couplings; intermediate levels receive two Jacobi iterations on descent and ascent, the bottom receives four, and deep prolongations use a bounded correction before the existing fine-grid post-smoothing. Per-dispatch level dimensions and packed offsets are push constants, avoiding repeated hierarchy reconstruction in every shader invocation. This is numerical solver state only and does not change particle-volume truth, marker fluid classification, collision response, rendering reconstruction or scheduling.

The fixed current-domain first step gives this maximum-divergence convergence curve on the RTX 5070 Ti. Each point starts from the identical particle state; time is the complete physical step including the 64-byte diagnostic copy.

| Solver | Iterations | Post-projection max divergence | Step time |
|---|---:|---:|---:|
| Jacobi | 32 / 64 / 96 | 0.223824 / 0.145660 / 0.103966 | 0.408 / 0.660 / 0.787 ms |
| RBGS/SOR | 16 / 32 / 48 sweeps | 0.378236 / 0.105281 / 0.028933 | 0.424 / 0.654 / 0.875 ms |
| PCG, identity preconditioner | 16 / 24 / 32 | 0.209116 / 0.017929 / 0.001239 | 1.214 / 1.388 / 1.676 ms |
| PCG, diagonal preconditioner | 16 / 24 / 32 | 0.940274 / 0.031761 / 0.005039 | 0.856 / 1.035 / 1.351 ms |
| MG2 V-cycle | 1 / 2 / 3 cycles | 0.270847 / 0.064497 / 0.026499 | 0.395 / 0.693 / 0.798 ms |
| MGH adaptive V-cycle | 1 / 2 / 3 cycles | 0.270847 / 0.064496 / 0.026498 | 0.486 / 0.635 / 0.776 ms |

Across six evolving steps, Jacobi 96, RBGS/SOR 48, identity-PCG 32, diagonal-PCG 32, MG2 two-cycle and MGH two-cycle runs produced step-six maximum divergences of approximately 0.23785, 0.05667, 0.0122, 0.01768, 0.11600 and 0.11600. The current small domain deliberately gives MGH only one coarse level, so its numerical result matches MG2 within floating-point dispatch ordering. All retained exactly 2,592.000 solver-cell volumes. MG2 remains the simpler small-domain low-cost candidate, while identity-PCG buys another order of magnitude in residual at higher dispatch/reduction cost. The diagonal preconditioner does not improve this matrix.

That ordering does not scale unchanged. In the solid-aware 64³ grid partial-dam run on the RTX 5070 Ti, the identical first-step MG2 curve was 0.266631 / 0.178072 / 0.139916 for one / two / three cycles at minimum observed times of 1.517 / 1.760 / 1.976 ms. The four-level MGH curve was 0.512959 / 0.144481 / 0.099696 at 1.501 / 1.759 / 2.047 ms. Thus two MGH cycles materially improve on two MG2 cycles at the same measured cost and approach the residual of three MG2 cycles; three MGH cycles reduce it further for a small additional cost. On the RTX 4060 Ti the same numerical curves reproduced exactly; MG2 measured 2.018 / 2.780 / 3.198 ms and MGH measured 2.596 / 3.133 / 3.613 ms, so MGH two-cycle again approaches MG2 three-cycle quality and cost rather than dominating it. In the evolving run on both devices, MGH two-cycle step four left 0.28908 maximum divergence versus MG2's 0.44294. MGH is therefore a useful 64³ quality/cost candidate, but MG2 remains the simpler `worlddemo` backend and Jacobi remains the generated-demo default until packaged MGH and longer-running integration gates pass.

The synthetic obstacle case uses a one-solver-cell vertical wall crossed by a one-cell horizontal shelf: 228 solid cells, 39,648 markers and exactly 4,956.000 solver-cell volumes. After eight steps, Jacobi, MG2 and MGH placed no marker inside a solid. For both multigrid paths, coarse X coupling across every covered thin-wall face was exactly 0.000000 while an adjacent open face remained 1.000. MG2 and the single-level small-domain MGH both reduced maximum divergence from 5.27196 to 0.32735 versus Jacobi's 4.79587 to 0.42489. Device-local and host-visible MG2/MGH runs produced zero marker/solid mismatches and no meaningful density, pressure or center-of-mass difference. A separate 64³ obstacle gate contains 2,176 solid cells and 1,162,240 markers carrying exactly 145,280.000 solver-cell volumes. On both GPUs, all four hierarchy levels kept every covered thin-wall coupling at 0.0 and adjacent open coupling at 1.0, no particle entered a solid, and projection reduced divergence from 0.98100 to 0.15741. The 64³ topology gate additionally reads the packed hierarchy after a physical step and verifies four exact level sizes, nonempty fluid masks, finite positive diagonals/RHS/pressure and bounded face weights; observed fluid counts were 9,984 / 1,344 / 192 / 24.

Probe 0.1.8 adds the block-mask expansion and frozen-world ingestion path. Its deterministic 12×8×12-block fixture contains full blocks, lower-half collision masks and an internal wall, expanding to exactly 1,320 solid solver cells. Both the GPU MG2 path and isolated CPU fallback receive an identical mask and initialize 16,992 markers carrying exactly 2,124.000 solver-cell volumes. On both GPUs, eight GPU steps retained that volume, left zero particles inside solids, and ended at divergence 4.55874→0.10610. This automated gate validates block/subcell ordering, upload, fallback equivalence, conservation and collision; the actual Minecraft `BlockState`/loaded-chunk capture and client visualization remain an integrated-server smoke gate.

## Throughput and readback

The step time includes queue completion and the regular 64-byte diagnostic staging copy. “Snapshot” is the packed density/marker/solid surface field requested separately; “full state” is a diagnostic-only staged scan of all 19 SSBOs, including CPU checksum cost. It is deliberately absent from a normal step.

| Device / memory / case | Markers | Pressure iterations | Step time | Snapshot readback | Full-state readback |
|---|---:|---:|---:|---:|---:|
| RTX 5070 Ti, device-local, current 12×8×12-block dam | 20,736 | 96 | 0.840 ms average (0.672–2.221) | 36,864 B / 0.796 ms | 3,002,176 B / 60.639 ms |
| RTX 5070 Ti, host-visible baseline, current dam | 20,736 | 96 | 7.420 ms average (6.354–8.736) | 36,864 B / 0.538 ms | 3,002,176 B / 48.837 ms |
| RTX 4060 Ti, device-local, current 12×8×12-block dam | 20,736 | 96 | 1.893 ms average (1.474–5.288) | 36,864 B / 0.804 ms | 3,002,176 B / 69.429 ms |
| RTX 4060 Ti, host-visible baseline, current dam | 20,736 | 96 | 9.802 ms average (7.123–11.726) | 36,864 B / 0.546 ms | 3,002,176 B / 54.033 ms |
| RTX 5070 Ti, device-local, full 32³ blocks / 64³ grid | 2,097,152 | 64 | 4.511 ms average (4.364–4.592) | 1,048,576 B / 19.601 ms | 85,231,680 B / 1,402.486 ms |
| RTX 4060 Ti, device-local, full 32³ blocks / 64³ grid | 2,097,152 | 64 | 7.736 ms average (7.681–7.784) | 1,048,576 B / 17.318 ms | 85,231,680 B / 1,423.583 ms |

The 32³ benchmark really allocates and advances all 2,097,152 full-water markers. Gravity is zero in that throughput case because a completely full closed box has a pressure nullspace and no free atmospheric surface. Its exact marker ledger is 262,144 solver-cell volumes, equal to 32,768 Minecraft block-volumes.

Moving the compute state off host-visible memory improves the current-domain step by about 8.0× on the RTX 5070 Ti and 3.6× on the RTX 4060 Ti. It also reverses the misleading M1-A large-domain result: the same RTX 5070 Ti 32³ case falls from roughly 655 ms to 5.52 ms once the 84 MB working set stays device-local. The roughly 17 ms 1 MiB snapshot and 1.35–1.40 s full diagnostic scan show why snapshot cadence, bounded queries and future ring staging still matter even though the physical step is fast.

The 0.1.2 integrated-server smoke on the RTX 5070 Ti measured the current domain at 0.886 ms average and the full 32³ case at 26.045 ms average while Minecraft was also rendering on that GPU. The difference from the 5.520 ms headless result is live GPU contention, not a change in particle volume or numerical stages. Both remain below the 40 ms soft budget. The requested full-state benchmark readback took 1,356.478 ms and is still intentionally excluded from normal stepping. Status reported the exact 2,592.000-cell GPU marker-volume reduction, and `remove` released the visualization without touching world state. This smoke also exposed that paused `/realwatergpu step 6` executed only one step. Probe 0.1.3 then completed all six requested paused steps (`RESULT: 6`), ran 217 total steps at 1.061 ms last-step time, retained exactly 2,592.000 cells of GPU marker volume, reduced divergence from 2.08417 to 0.00114, and removed cleanly. The command-count fix and the three-step hard-overrun guard are both now verified.

Probe 0.1.4 then passed all three integrated-server pressure benchmark commands. On the live RTX 5070 Ti, Jacobi 96 averaged 0.876 ms and ended at divergence 5.93165→0.11767, RBGS/SOR 48 averaged 1.479 ms and ended at 9.95173→0.01688, and identity-PCG 32 averaged 1.649 ms and ended at 10.12072→0.02222. These ending divergence pairs are per-solver stability evidence, not a same-input convergence comparison: each command advanced its own dam for 23 steps and therefore finished from a different particle state. The automated fresh-state curves above remain the fair convergence evidence.

Probe 0.1.5 also passed the integrated-server diagonal-PCG command. On the live RTX 5070 Ti, diagonal-PCG 32 averaged 1.783 ms over 20 steps, retained all 20,736 particles, and ended at divergence 10.12744→0.01579; regular diagnostics remained 64 B per step. This validates the command/parser and packaged shader path, while the same-input automated curves remain the evidence that diagonal preconditioning should not replace the default.

Probe 0.1.6 passed the integrated-server MG2 command. On the live RTX 5070 Ti, two V-cycles averaged 0.798 ms over 20 steps, retained all 20,736 particles, and ended at divergence 10.81817→0.29078; regular diagnostics remained 64 B per step and the full diagnostic readback reflected the four new coarse buffers. This validates the packaged command and V-cycle path. As with the earlier solver commands, the evolved ending pair is not substituted for the automated same-input convergence curves.

Probe 0.1.7 passed the packaged solid-aware MG2 command on the live RTX 5070 Ti. Two V-cycles averaged 0.890 ms over 20 steps, retained all 20,736 particles, and ended at divergence 10.86908→0.10805; regular diagnostics remained 64 B per step and the solid-aware coarse topology increased the requested full-state diagnostic to 3,002,176 B. This closes the integrated-server packaging gate for the generated-solid topology changes. It does not yet validate Minecraft block ingestion: 0.1.7 still uses only generated masks and remains world-independent.

Probe 0.1.8 passed its integrated-server `worlddemo` command/API and numerical smoke on the live RTX 5070 Ti. The non-loading capture covered four loaded chunks and found 2,816 solid solver cells in 356 Minecraft blocks. Excluding those solids from the dam initialized 13,824 markers carrying exactly 1,728.000 solver-cell volumes (216.000 Minecraft blocks). Eight paused steps ran at 0.655 ms last-step time and reduced divergence from 1.22451 to 0.06943. Continuous execution reached 347 steps at 0.749 ms last-step time, retained exactly 1,728.000 cells, and reduced divergence from 2.87115 to 0.05586 before clean removal. Status preserved the `frozen-world/read-only` count throughout. The transcript proves the loaded-chunk API, packaged MG2, volume ledger, scheduling, status and cleanup paths; visual alignment of particular partial shapes and the optional after-capture block-edit freeze check are manual observations rather than log-derived claims.

Probe 0.1.9 passed its complete headless suite on both GPUs and its packaged integrated-server MGH smoke on the RTX 5070 Ti. The 24×16×24 command averaged 0.880 ms over 20 evolved steps, retained all 20,736 particles and ended at divergence 10.86908→0.10806. The 64³ full-water command allocated 2,097,152 particles, averaged 7.146 ms over three steps under live rendering contention, retained zero divergence as expected for the gravity-free closed box, staged its 1,048,576-byte snapshot in 16.244 ms and completed the explicit 85,231,680-byte full-state diagnostic in 1,379.330 ms. Both commands returned success and did not access world state. The smoke also revealed that `/realwatergpu probe` worked only without its documented device argument: the Brigadier `device` node had been attached to the root rather than beneath `probe`. Probe 0.1.10 corrects that nesting and adds an automated tree-structure gate proving that both the default executor and nested device executor exist. Its packaged `/realwatergpu probe 1` smoke then selected the RTX 4060 Ti, passed Vulkan 1.4.329, Shaderc, VMA, float-atomic and checksum checks, and completed the dispatch in 0.973 ms. Both documented probe forms are now closed by automated and integrated evidence.

The 0.1.10 dedicated-server classpath smoke also passes. Loom launched Minecraft 26.2 with Fabric Loader 0.19.3 in `Env=SERVER`, loaded Real Water 0.8.2 and `realwater_gpu_probe` 0.1.10 among 44 mods, set Java compatibility to 25, and both Mod initializers completed without a client-only linkage error. The probe reported that Vulkan remained lazy and the main CPU backend authoritative. The smoke deliberately kept `gpu-probe/run/eula.txt` at `false`, so the process exited before opening a port or creating a world. After the one missing Loom runtime helper was cached, the same gate reproduced with `--offline`.

## Commands

The optional `realwater-gpu-probe` jar adds:

- `/realwatergpu probe [device]`: run the windowless Vulkan/Shaderc/VMA capability check.
- `/realwatergpu demo [device]`: create a paused visual dam-break about nine blocks ahead. GPU initialization failure creates an isolated CPU fallback demo; it never changes the main Mod backend.
- `/realwatergpu worlddemo [device]`: create a paused MG2 dam-break against one frozen read-only snapshot of nearby loaded Minecraft collision shapes. It refuses unloaded required chunks, never adds a chunk ticket, does not sample world water, and does not follow later block edits.
- `/realwatergpu step [1-20]`, `run`, `pause`, `status`: control and inspect the independent demo.
- `/realwatergpu benchmark [12|32] [device] [jacobi|rbgs|pcg|pcgdiag|mg2|mgh]`: run the current-domain or real 32³-block benchmark with an optional experimental pressure solver. `mg2` and `mgh` use two V-cycles. Omission keeps Jacobi. The 32 case intentionally stalls the server thread for several seconds and never accesses world data.
- `/realwatergpu remove`: free probe resources and clear the debug snapshot.

The main `/realwater` domain and the probe share the existing debug snapshot channel, so the probe refuses creation while a main domain exists and auto-pauses if one appears later.

All handled probe commands and their chat/detail output are appended as UTF-8 blocks to `logs/realwater-commands.log`; `/realwater logpath` reports the exact absolute path. This diagnostic transcript is the only new file written by the probe integration and is not world/save state.

## Remaining M1 work

- Replace the single reusable staging buffer and CPU wall-clock timing with bounded ring staging and Vulkan timestamp queries before calling the layout production-ready. Full-state readback remains diagnostic-only.
- The 0.1.8 integrated-server `worlddemo` creation, stepping, continuous scheduling, status and cleanup smoke has passed. If a visual mismatch is observed, capture a screenshot of the relevant partial shape; the optional after-capture block-edit freeze observation is still useful but is not required for the already-enforced immutable in-memory mask. Probe 0.1.9 MGH passed both packaged benchmark sizes, and probe 0.1.10 passed the corrected `/realwatergpu probe 1` form. Jacobi remains the generated-demo correctness/default baseline and MG2 remains the read-only `worlddemo` backend.
- Complete the CPU variable-density face-coefficient experiment before M3 and measure CPU/GPU break-even across more occupancies.
- Add a CPU-readable checkpoint and recovery path for runtime device loss. The main Mod already remains CPU-only; the optional visual probe currently pauses on a step failure rather than pretending incomplete buffers are valid.

Run the automated suite with the Minecraft native directory:

```powershell
./gradlew.bat :gpu-probe:runGpuFlipValidation --offline --no-daemon `
  '-PgpuProbeNativesDir=Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\26.2-Fabric 0.19.3-natives\lwjgl\3.4.1-snapshot\x64' `
  -PgpuProbeDevice=0
```
