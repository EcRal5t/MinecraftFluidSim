# M1 Windows/NVIDIA Vulkan GPU probe report

Date: 2026-08-19. Main Mod: Real Water 0.8.2. Optional probe Mod: `realwater_gpu_probe` 0.1.16. Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.157.0+26.2, Java 25 bytecode, LWJGL 3.4.1.

This is the completed M1 correctness, data-movement, collision-ingestion, runtime-recovery and occupancy probe, not the production GPU backend. It has no takeover code, never writes blocks or save data, and owns no chunk tickets. The ordinary generated demo and every headless benchmark remain world-independent. The explicit `worlddemo` command captures collision once from chunks already loaded around the player, then runs only from the frozen in-memory subcell mask. The main `realwater` Mod remains CPU-authoritative whether the optional probe jar is installed or not.

## Layer boundary

- Physical truth: every GPU marker stores its own `particleVolume`; the validation ledger uses particle count times `1/8` solver-cell volume. Smoothed density never estimates total water.
- Numerical discretization: 3D P2G density/marker/MAC velocity transfer, saved pre-projection velocities, gravity, selectable fixed-work pressure solvers including Jacobi, MG2 and packed full-hierarchy MGH, MAC projection, 90% FLIP / 10% PIC G2P, and axis-separated particle advection/collision are Vulkan compute stages.
- Rendering reconstruction: a compact density + marker + solid snapshot is generated on the GPU and sent through the existing client marching-tetrahedra payload. Marker occupancy remains the shallow-water seed.
- Scheduling: compute SSBOs require device-local memory. Initialization and explicit snapshot/full-state reads use a serialized host-coherent bulk staging buffer. Ordinary physical steps use a fixed three-slot ring; every slot owns one command pool/buffer, fence, two-query timestamp pool and 64-byte mapped diagnostic staging block, for 192 bytes of regular readback staging total. Submission never overwrites an uncompleted slot and waits only that slot's fence on reuse; there is no physical-step `vkQueueWaitIdle`. GPU timestamp measures the complete device command stream including the 64-byte copy. Completed-batch throughput wall measures end-to-end throughput. Scheduling/backpressure wall measures time spent by the server thread recording, submitting and, only when the bounded ring is full, waiting for a reusable slot. That scheduling wall remains the hard-overrun truth: three consecutive calls above 200 ms pause only the visual probe. Device timestamp remains observation and never drives the guard. The probe does not replace the main service's 40 ms adaptive CPU scheduler.
- Runtime recovery: generation 0 must be captured before a Vulkan demo is published. Every 20 completed logical steps, the service settles the submission ring and atomically replaces its in-memory CPU checkpoint with exact marker position/volume, marker velocity and solid state. A failure in step submission, fence completion, checkpoint, snapshot or status diagnostics discards untrusted newer GPU state, restores the last complete generation into the isolated CPU fallback and leaves it paused. Checkpoint readback wall time is included in the service hard-overrun clock. This state is never written to a save.
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

The fixed current-domain first step gives this maximum-divergence convergence curve on the RTX 5070 Ti. Each point starts from the identical particle state; time is the minimum Vulkan timestamp over three samples and covers the complete physical device command stream including the 64-byte diagnostic copy.

| Solver | Iterations | Post-projection max divergence | Step time |
|---|---:|---:|---:|
| Jacobi | 32 / 64 / 96 | 0.223824 / 0.145660 / 0.103966 | 0.237 / 0.369 / 0.495 ms |
| RBGS/SOR | 16 / 32 / 48 sweeps | 0.378236 / 0.105281 / 0.028933 | 0.241 / 0.383 / 0.502 ms |
| PCG, identity preconditioner | 16 / 24 / 32 | 0.209245 / 0.016840 / 0.001246 | 0.452 / 0.669 / 0.867 ms |
| PCG, diagonal preconditioner | 16 / 24 / 32 | 0.940272 / 0.031761 / 0.005039 | 0.492 / 0.724 / 0.936 ms |
| MG2 V-cycle | 1 / 2 / 3 cycles | 0.270847 / 0.064497 / 0.026499 | 0.267 / 0.416 / 0.580 ms |
| MGH adaptive V-cycle | 1 / 2 / 3 cycles | 0.270847 / 0.064496 / 0.026498 | 0.246 / 0.434 / 0.589 ms |

Across six evolving steps, Jacobi 96, RBGS/SOR 48, identity-PCG 32, diagonal-PCG 32, MG2 two-cycle and MGH two-cycle runs produced step-six maximum divergences of approximately 0.23785, 0.05667, 0.0122, 0.01768, 0.11600 and 0.11600. The current small domain deliberately gives MGH only one coarse level, so its numerical result matches MG2 within floating-point dispatch ordering. All retained exactly 2,592.000 solver-cell volumes. MG2 remains the simpler small-domain low-cost candidate, while identity-PCG buys another order of magnitude in residual at higher dispatch/reduction cost. The diagonal preconditioner does not improve this matrix.

That ordering does not scale unchanged. In the solid-aware 64³ grid partial-dam run on the RTX 5070 Ti, the identical first-step MG2 curve was 0.266631 / 0.178072 / 0.139916 for one / two / three cycles at Vulkan times of 1.261 / 1.488 / 1.725 ms. The four-level MGH curve was 0.512959 / 0.144481 / 0.099696 at 1.287 / 1.543 / 1.804 ms. Thus two MGH cycles materially improve on two MG2 cycles at similar device cost and approach the residual of three MG2 cycles; three MGH cycles reduce it further for a small additional cost. The RTX 4060 Ti reproduced the same numerical curves; its MGH timestamps were 1.976 / 2.256 / 2.544 ms. In the evolving run on both devices, MGH two-cycle step four left 0.28908 maximum divergence versus MG2's 0.44294. MGH is therefore a useful 64³ quality/cost candidate, but MG2 remains the simpler `worlddemo` backend and Jacobi remains the generated-demo default until packaged MGH and longer-running integration gates pass.

The synthetic obstacle case uses a one-solver-cell vertical wall crossed by a one-cell horizontal shelf: 228 solid cells, 39,648 markers and exactly 4,956.000 solver-cell volumes. After eight steps, Jacobi, MG2 and MGH placed no marker inside a solid. For both multigrid paths, coarse X coupling across every covered thin-wall face was exactly 0.000000 while an adjacent open face remained 1.000. MG2 and the single-level small-domain MGH both reduced maximum divergence from 5.27196 to 0.32735 versus Jacobi's 4.79587 to 0.42489. Device-local and host-visible MG2/MGH runs produced zero marker/solid mismatches and no meaningful density, pressure or center-of-mass difference. A separate 64³ obstacle gate contains 2,176 solid cells and 1,162,240 markers carrying exactly 145,280.000 solver-cell volumes. On both GPUs, all four hierarchy levels kept every covered thin-wall coupling at 0.0 and adjacent open coupling at 1.0, no particle entered a solid, and projection reduced divergence from 0.98100 to 0.15741. The 64³ topology gate additionally reads the packed hierarchy after a physical step and verifies four exact level sizes, nonempty fluid masks, finite positive diagonals/RHS/pressure and bounded face weights; observed fluid counts were 9,984 / 1,344 / 192 / 24.

Probe 0.1.8 adds the block-mask expansion and frozen-world ingestion path. Its deterministic 12×8×12-block fixture contains full blocks, lower-half collision masks and an internal wall, expanding to exactly 1,320 solid solver cells. Both the GPU MG2 path and isolated CPU fallback receive an identical mask and initialize 16,992 markers carrying exactly 2,124.000 solver-cell volumes. On both GPUs, eight GPU steps retained that volume, left zero particles inside solids, and ended at divergence 4.55874→0.10610. This automated gate validates block/subcell ordering, upload, fallback equivalence, conservation and collision; the actual Minecraft `BlockState`/loaded-chunk capture and client visualization remain an integrated-server smoke gate.

## Throughput and readback

Probe 0.1.13 reports three intentionally different clocks. “GPU timestamp” spans one submitted physical command stream and its regular 64-byte diagnostic copy. “Throughput wall” encloses a complete measured batch, including final fence completion and diagnostic decode, divided by its step count. “Scheduling wall” is the per-call server-thread record/submit/backpressure cost used by the continuous-overrun guard. “Snapshot” is the packed density/marker/solid surface field requested separately; “full state” is a diagnostic-only staged scan of all 27 SSBOs, including CPU checksum cost. Both explicit read paths first settle the ring and are deliberately absent from a normal step.

| Device / memory / case | Markers | Pressure iterations | GPU / throughput wall / scheduling wall average | Snapshot readback | Full-state readback |
|---|---:|---:|---:|---:|---:|
| RTX 5070 Ti, device-local, current 12×8×12-block dam | 20,736 | 96 | 0.521 / 0.547 / 0.474 ms | 36,864 B / 0.616 ms | 3,002,176 B / 59.913 ms |
| RTX 5070 Ti, host-visible baseline, current dam | 20,736 | 96 | 7.239 / 7.267 / 6.334 ms | 36,864 B / 0.534 ms | 3,002,176 B / 49.282 ms |
| RTX 4060 Ti, device-local, current 12×8×12-block dam | 20,736 | 96 | 2.208 / 2.236 / 1.769 ms | 36,864 B / 0.598 ms | 3,002,176 B / 72.234 ms |
| RTX 4060 Ti, host-visible baseline, current dam | 20,736 | 96 | 7.572 / 7.609 / 7.288 ms | 36,864 B / 0.694 ms | 3,002,176 B / 51.556 ms |
| RTX 5070 Ti, device-local, full 32³ blocks / 64³ grid | 2,097,152 | 64 | 4.777 / 4.913 / 0.130 ms | 1,048,576 B / 17.078 ms | 85,231,680 B / 1,549.848 ms |
| RTX 4060 Ti, device-local, full 32³ blocks / 64³ grid, 0.1.11 synchronous baseline | 2,097,152 | 64 | 7.363 / 7.648 / n/a ms | 1,048,576 B / 17.832 ms | 85,231,680 B / 1,522.867 ms |

The 32³ benchmark really allocates and advances all 2,097,152 full-water markers. Gravity is zero in that throughput case because a completely full closed box has a pressure nullspace and no free atmospheric surface. Its exact marker ledger remains 262,144 solver-cell volumes, equal to 32,768 Minecraft block-volumes. The 0.1.13 large gate was intentionally repeated on the RTX 5070 Ti because staging layout and capacity changed; the RTX 4060 Ti ran the complete small-domain lifecycle suite, while its already-recorded synchronous 64³ row is retained only as a labeled historical baseline.

Moving the compute state off host-visible memory remains decisively beneficial, though the ratio varies with driver placement and concurrent desktop load. The fixed ring bounds ordinary mapped diagnostic staging to 192 bytes regardless of solver-domain size; the separate bulk buffer is sized once for initialization and explicit reads. The roughly 17 ms 1 MiB snapshot and 1.55 s full diagnostic scan still show why snapshot cadence and diagnostic-only full readback matter even though the physical step is fast.

The 0.1.2 integrated-server smoke on the RTX 5070 Ti measured the current domain at 0.886 ms average and the full 32³ case at 26.045 ms average while Minecraft was also rendering on that GPU. The difference from the 5.520 ms headless result is live GPU contention, not a change in particle volume or numerical stages. Both remain below the 40 ms soft budget. The requested full-state benchmark readback took 1,356.478 ms and is still intentionally excluded from normal stepping. Status reported the exact 2,592.000-cell GPU marker-volume reduction, and `remove` released the visualization without touching world state. This smoke also exposed that paused `/realwatergpu step 6` executed only one step. Probe 0.1.3 then completed all six requested paused steps (`RESULT: 6`), ran 217 total steps at 1.061 ms last-step time, retained exactly 2,592.000 cells of GPU marker volume, reduced divergence from 2.08417 to 0.00114, and removed cleanly. The command-count fix and the three-step hard-overrun guard are both now verified.

Probe 0.1.4 then passed all three integrated-server pressure benchmark commands. On the live RTX 5070 Ti, Jacobi 96 averaged 0.876 ms and ended at divergence 5.93165→0.11767, RBGS/SOR 48 averaged 1.479 ms and ended at 9.95173→0.01688, and identity-PCG 32 averaged 1.649 ms and ended at 10.12072→0.02222. These ending divergence pairs are per-solver stability evidence, not a same-input convergence comparison: each command advanced its own dam for 23 steps and therefore finished from a different particle state. The automated fresh-state curves above remain the fair convergence evidence.

Probe 0.1.5 also passed the integrated-server diagonal-PCG command. On the live RTX 5070 Ti, diagonal-PCG 32 averaged 1.783 ms over 20 steps, retained all 20,736 particles, and ended at divergence 10.12744→0.01579; regular diagnostics remained 64 B per step. This validates the command/parser and packaged shader path, while the same-input automated curves remain the evidence that diagonal preconditioning should not replace the default.

Probe 0.1.6 passed the integrated-server MG2 command. On the live RTX 5070 Ti, two V-cycles averaged 0.798 ms over 20 steps, retained all 20,736 particles, and ended at divergence 10.81817→0.29078; regular diagnostics remained 64 B per step and the full diagnostic readback reflected the four new coarse buffers. This validates the packaged command and V-cycle path. As with the earlier solver commands, the evolved ending pair is not substituted for the automated same-input convergence curves.

Probe 0.1.7 passed the packaged solid-aware MG2 command on the live RTX 5070 Ti. Two V-cycles averaged 0.890 ms over 20 steps, retained all 20,736 particles, and ended at divergence 10.86908→0.10805; regular diagnostics remained 64 B per step and the solid-aware coarse topology increased the requested full-state diagnostic to 3,002,176 B. This closes the integrated-server packaging gate for the generated-solid topology changes. It does not yet validate Minecraft block ingestion: 0.1.7 still uses only generated masks and remains world-independent.

Probe 0.1.8 passed its integrated-server `worlddemo` command/API and numerical smoke on the live RTX 5070 Ti. The non-loading capture covered four loaded chunks and found 2,816 solid solver cells in 356 Minecraft blocks. Excluding those solids from the dam initialized 13,824 markers carrying exactly 1,728.000 solver-cell volumes (216.000 Minecraft blocks). Eight paused steps ran at 0.655 ms last-step time and reduced divergence from 1.22451 to 0.06943. Continuous execution reached 347 steps at 0.749 ms last-step time, retained exactly 1,728.000 cells, and reduced divergence from 2.87115 to 0.05586 before clean removal. Status preserved the `frozen-world/read-only` count throughout. The transcript proves the loaded-chunk API, packaged MG2, volume ledger, scheduling, status and cleanup paths; visual alignment of particular partial shapes and the optional after-capture block-edit freeze check are manual observations rather than log-derived claims.

Probe 0.1.9 passed its complete headless suite on both GPUs and its packaged integrated-server MGH smoke on the RTX 5070 Ti. The 24×16×24 command averaged 0.880 ms over 20 evolved steps, retained all 20,736 particles and ended at divergence 10.86908→0.10806. The 64³ full-water command allocated 2,097,152 particles, averaged 7.146 ms over three steps under live rendering contention, retained zero divergence as expected for the gravity-free closed box, staged its 1,048,576-byte snapshot in 16.244 ms and completed the explicit 85,231,680-byte full-state diagnostic in 1,379.330 ms. Both commands returned success and did not access world state. The smoke also revealed that `/realwatergpu probe` worked only without its documented device argument: the Brigadier `device` node had been attached to the root rather than beneath `probe`. Probe 0.1.10 corrects that nesting and adds an automated tree-structure gate proving that both the default executor and nested device executor exist. Its packaged `/realwatergpu probe 1` smoke then selected the RTX 4060 Ti, passed Vulkan 1.4.329, Shaderc, VMA, float-atomic and checksum checks, and completed the dispatch in 0.973 ms. Both documented probe forms are now closed by automated and integrated evidence.

The 0.1.10 dedicated-server classpath smoke also passes. Loom launched Minecraft 26.2 with Fabric Loader 0.19.3 in `Env=SERVER`, loaded Real Water 0.8.2 and `realwater_gpu_probe` 0.1.10 among 44 mods, set Java compatibility to 25, and both Mod initializers completed without a client-only linkage error. The probe reported that Vulkan remained lazy and the main CPU backend authoritative. The smoke deliberately kept `gpu-probe/run/eula.txt` at `false`, so the process exited before opening a port or creating a world. After the one missing Loom runtime helper was cached, the same gate reproduced with `--offline`.

Probe 0.1.11 adds a two-slot Vulkan timestamp query pool to each pipeline. The selected compute queue must expose timestamp bits; the suite validates finite positive durations and verifies that device time fits inside the enclosing wall interval. Complete suites passed on both GPUs, including both memory modes, all pressure candidates, static and obstacle cases, frozen block-mask ingestion, four packed MGH levels and the real 2,097,152-marker allocation. Its EULA-false dedicated-server gate also loaded `realwater_gpu_probe` 0.1.11 in `Env=SERVER`, initialized both Mods without client-only linkage and exited before opening a port or creating a world. The packaged RTX 5070 Ti smoke then reported `GPU timestamp 尚未步进` before the first step, 0.514 ms GPU versus 0.890 ms wall after six paused steps, and an MGH benchmark average of 0.450 ms GPU versus 0.917 ms wall. All 20,736 particles and 2,592.000 solver-cell volumes were retained, `remove` released the probe, and the transcript confirmed no world/save modification. No physical equation, fluid classification, particle collision, rendering payload or scheduler threshold changed.

Probe 0.1.12 is a maintenance-only packaging refactor. The byte-identical main GLSL source now lives at `assets/realwater_gpu_probe/shaders/flip.comp`, and a dedicated loader fails clearly if the resource is absent. Small-domain correctness remains in `GpuFlipProbeValidation`; 64³ hierarchy, pressure and 2,097,152-marker gates move to `GpuLargeDomainValidation` and are skipped with `-PgpuProbeSkipLarge=true`. No physical, numerical, rendering or scheduling behavior changes.

The 0.1.12 packaged jar contains the extracted shader and both validation classes. Its RTX 5070 Ti `--skip-large` suite passed all small-domain numerical, timestamp, memory-mode and readback gates; the EULA-false dedicated server loaded probe 0.1.12 in `Env=SERVER` and exited without opening a port or creating a world. Per the proportional test policy, this byte-identical shader/refactor change did not repeat the already-closed second-GPU or 64³ gates.

Probe 0.1.13 replaces the pipeline's single command buffer, single universal staging buffer and per-step queue-idle wait with a fixed three-slot submission ring. Each ordinary step owns its command pool/buffer, fence, two timestamp queries and 64-byte diagnostic staging block until completion; a daemon completion worker waits fences and decodes results in queue order. Reuse waits only the selected slot, so memory is bounded while server ticks may submit ahead. Initialization, compact snapshots and explicit full-state diagnostics use one separate serialized bulk staging buffer after the ring is settled. Automated validation asserts the exact three slots, 192 regular staging bytes, three outstanding submissions, finite per-slot timestamps and unchanged marker-volume diagnostics. The RTX 5070 Ti full suite and RTX 4060 Ti `--skip-large` lifecycle suite both passed. The 64³ full-water gate retained all 2,097,152 markers and 262,144.0 solver-cell volumes at 4.777 ms GPU / 4.913 ms throughput wall. No shader, physical equation, pressure solver, marker classification, collision rule, rendering field, world access or save format changed.

The companion CPU variable-density face-coefficient gate is now complete. Its isolated 12-cell column compared `average(1/rho)`, `1/average(rho)` and an interface-distance conservative coefficient at 1:1, water/oil 1.25:1 and mercury/water 13.6:1 density ratios. Every assembled matrix was symmetric positive definite and each diagonal-PCG solve converged in 12 iterations with residual RMS at or below `2.36e-14`. The distance-weighted candidate's aggregate hydrostatic pressure-plus-parasitic-velocity score was `3.48e-15`, versus `1.08` for `1/average(rho)` and `9.12` for `average(1/rho)`. It is selected for later M3 integration, contingent on M2.5 reconstructing the interface fraction; it does not change the constant-density CPU domain or the GPU shader.

Probe 0.1.14 adds the CPU-readable runtime recovery boundary without changing the shader or any SSBO binding. `FlipParticleState` validates and immutably owns the marker-volume ledger, positions, velocities and frozen solid mask; `GpuProbeCheckpoint` attaches one completed generation. The current generated dam checkpoint occupies 589,824 CPU bytes and reads 700,416 GPU bytes, while the configured 73,728-marker service maximum remains bounded to 2,073,600 CPU bytes and 2,396,160 readback bytes. Ordinary steps still read only 64 bytes. The automated lifecycle gate captures and restores both device-local and host-visible states, advances the restored CPU domain without volume loss, verifies a 20-generation replacement cadence, and passed `--skip-large` on both RTX 5070 Ti and RTX 4060 Ti. Close now drains every outstanding completion future before resource destruction even when one slot reports failure. No physical equation, marker classification, collision rule, rendering field, scheduler threshold, world access or save format changed. A deliberate operating-system/driver reset was not triggered; the deterministic gate exercises the same validated handoff and rollback state used when Vulkan reports the runtime failure.

## Occupancy break-even

Probe 0.1.15 adds `GpuOccupancyBreakEvenValidation`, a separate headless publication matrix rather than extra cost in the ordinary fast gate. Every row starts from the same deterministic contiguous occupancy on both backends, evolves six physical steps, resets to that initial state seven times, and reports the median batch. Initialization, shader compilation and validation readback are outside the timed region. CPU time is the active `FlipFluidDomain` PCG wall; GPU comparison time is completed-batch throughput wall for the Jacobi/96 correctness baseline. Timestamp remains device observation and scheduling wall remains the runtime guard truth. This comparison therefore measures the present probe implementations, not equivalent fixed iteration work and not the end-to-end latency of a future production backend.

Representative RTX 5070 Ti results:

| Grid / initial occupancy | CPU wall | GPU timestamp | GPU throughput wall | CPU / GPU throughput |
|---|---:|---:|---:|---:|
| `24×16×24`, 1 fluid cell × 1 marker | 0.240 ms | 1.370 ms | 1.481 ms | 0.16× |
| `24×16×24`, 4 fluid cells × 1 marker | 0.453 ms | 1.493 ms | 1.604 ms | 0.28× |
| `24×16×24`, 16 fluid cells × 1 marker | 0.617 ms | 1.498 ms | 1.626 ms | 0.38× |
| `24×16×24`, 1,024 fluid cells × 1 marker | 2.872 ms | 2.082 ms | 2.202 ms | 1.30× |
| `24×16×24`, 8,192 fluid cells × 1 marker | 23.864 ms | 2.155 ms | 2.254 ms | 10.59× |
| `24×16×24`, current 2,592-cell / 20,736-marker count | 11.646 ms | 2.171 ms | 2.257 ms | 5.16× |
| `4³`, 25% cells × 8 markers | 0.538 ms | 1.269 ms | 1.423 ms | 0.38× |
| `32³`, 25% cells × 8 markers | 50.077 ms | 2.165 ms | 2.282 ms | 21.95× |

Probe 0.1.16 remeasures this matrix after defining one 50 ms physical step as four complete 12.5 ms numerical substeps. The sampled current-grid single-marker crossover is now 1,024 fluid cells/markers, and the 25%-occupancy grid crossover is `16³`. At fixed 1,024 pressure-fluid cells, raising marker count from 1,024 to 8,192 changes CPU wall from 2.778 to 4.981 ms and GPU throughput from 2.228 to 2.213 ms. At fixed 8,192 markers, raising pressure-fluid cells from 1,024 to 8,192 changes CPU wall from 4.988 to 24.164 ms and GPU throughput from 2.217 to 2.231 ms. The four-substep dense-grid GPU floor is roughly 1.4–2.3 ms over the tested current grid, whereas the current CPU PCG cost remains much more sensitive to active pressure topology. All rows preserve their exact marker count and `particleVolume` ledger. The result supersedes the 0.1.15 performance values, closes the M1 break-even measurement for the corrected physical step, and does not switch the formal backend.

## Long-run surface regression and limit

Probe 0.1.16 fixes a GPU-specific rapid-collapse path observed in the rendered generated demo around steps 979–1,304. The old GPU advection integrated the full 50 ms tick once and constrained domain contact at `0.05h`, while the CPU reference used CFL substeps and a `0.45h` marker contact radius. GPU physical steps now record four full P2G → pressure → G2P → advection substeps at 12.5 ms each. Domain and frozen-solid contact use the same `0.45h` radius; only the contacted normal coordinate and outward normal velocity are clipped, so tangential motion remains free. Snapshot reconstruction still runs only after the final substep, and one timestamp spans all four substeps. Checkpoint generation, scheduler overrun accounting and the three-slot ring remain per 50 ms physical step.

`GpuLongRunSurfaceValidation` advances the same generated dam for 1,000 physical steps on GPU and CPU, then compares raw post-advection marker occupancy and quantized density columns rather than screenshots. On the RTX 5070 Ti the corrected GPU retained marker layers `y=0..2` and 1,728 marker cells, matching the CPU layer/count. A representative GPU edge minimum/mean density-column sum was `2.157/2.672` cells versus CPU `2.576/3.069`; GPU interior mean was `2.753` versus CPU `3.148`. The prior one-layer GPU state would fail every marker/edge threshold. Exact marker volume remained 2,592.000 solver cells.

The Minecraft-rendered regression window passed manually on 2026-08-19. At step 1,317 the generated RTX 5070 Ti demo was reported as visually much improved, with the earlier persistent edge/corner depressions absent; small residual pits are accepted only as deferred M2.5 work. Status retained 20,736 particles and exactly 2,592.000 solver cells, reported four 12.5 ms substeps, a 1.808 ms GPU timestamp, checkpoint generation 1,300 with age 17, and zero recoveries. No screenshot is required for this accepted result.

The correction does not claim to finish M2.5. A separate non-gating 36,000-step characterization retained exact particle volume but showed the remaining no-reseeding drift: GPU marker occupancy changed from `y=0..2`, 1,727 cells at step 1,000 to `y=0..1`, 1,152 cells by step 5,000. Edge minimum/mean density declined from `2.169/2.681` at 1,000 to `1.506/2.057` at 36,000; interior mean declined from `2.746` to `2.141`. This is the already-planned long-time marker-distribution problem: it requires conservative reseeding driven by the exact per-marker `particleVolume` ledger and fractional free-surface work. Increasing substeps again, relaxing the gate or leveling the rendered mesh is not an accepted fix.

The corrected 5070 Ti device-local current-domain physical step measures about 2.05–2.17 ms GPU timestamp and 2.09–2.26 ms completed-batch wall. The real 64³ / 2,097,152-marker full-water physical step passed at 11.548 ms GPU timestamp and 11.744 ms throughput wall with 262,144.0 solver-cell volume. The second GPU was not repeated because no device selection, queue, memory type or Vulkan lifecycle changed.

## Commands

The optional `realwater-gpu-probe` jar adds:

- `/realwatergpu probe [device]`: run the windowless Vulkan/Shaderc/VMA capability check.
- `/realwatergpu demo [device]`: create a paused visual dam-break about nine blocks ahead. GPU initialization failure creates an isolated CPU fallback demo; it never changes the main Mod backend.
- `/realwatergpu worlddemo [device]`: create a paused MG2 dam-break against one frozen read-only snapshot of nearby loaded Minecraft collision shapes. It refuses unloaded required chunks, never adds a chunk ticket, does not sample world water, and does not follow later block edits.
- `/realwatergpu step [1-20]`, `run`, `pause`, `status`: control and inspect the independent demo. Vulkan status labels server scheduling/backpressure wall, in-command-buffer GPU timestamp, checkpoint generation/age/bytes and recovery count separately.
- `/realwatergpu benchmark [12|32] [device] [jacobi|rbgs|pcg|pcgdiag|mg2|mgh]`: run the current-domain or real 32³-block benchmark with an optional experimental pressure solver. `mg2` and `mgh` use two V-cycles. Omission keeps Jacobi. Output separates GPU timestamp, completed-batch throughput wall and per-submit scheduling/backpressure wall. The 32 case intentionally stalls the server thread for several seconds and never accesses world data.
- `/realwatergpu remove`: free probe resources and clear the debug snapshot.

The main `/realwater` domain and the probe share the existing debug snapshot channel, so the probe refuses creation while a main domain exists and auto-pauses if one appears later.

All handled probe commands and their chat/detail output are appended as UTF-8 blocks to `logs/realwater-commands.log`; `/realwater logpath` reports the exact absolute path. This diagnostic transcript is the only new file written by the probe integration and is not world/save state.

## M1 completion

Every automated M1 capability, 1,000-step surface regression, pressure-candidate, memory-mode, 64³ capacity, compact/full readback, bounded-ring, runtime-recovery, variable-density coefficient and corrected occupancy break-even gate is complete. The Minecraft-rendered 1,317-step edge/corner regression also passed.

The integrated stability gate completed on the RTX 4060 Ti from 06:33:07 to 07:04:51 on 2026-08-19. The final paused status at step 38,073 retained 20,736 particles and exactly 2,592.000 solver cells, checkpoint generation 38,060 with age 13, and zero recoveries. The server remained responsive; scheduling/backpressure wall was 1.588 ms and the final four-substep GPU timestamp was 3.722 ms. An endpoint operating-system sample showed about 1.69 GiB Java working set, 3.07 GiB private memory and 736 MiB total device usage on the 4060 Ti. `/realwatergpu remove` then released the probe and visual snapshot normally, without world or save writes.

An earlier RTX 5070 Ti attempt was intentionally excluded from the steady-topology gate: powering off the display directly attached to that card changed the Windows display topology and crashed Minecraft's OpenGL render thread in `GpuSurface.present`/`glfwSwapBuffers`, with native frames in `d3d11.dll` and NVIDIA `nvoglv64.dll`. The probe fence thread was blocked, there was no Vulkan device-loss/recovery report and no Windows TDR event. OBS and NVIDIA capture hooks were loaded. This is recorded as an external display-present-path incident rather than a compute-pipeline failure; monitor/device topology must remain stable during the M1 result above.

M1 is complete. The known post-5,000-step marker-distribution drift remains M2.5 work and is not misreported as long-term free-surface acceptance. The probe remains optional and read-only with respect to worlds/saves, and no production backend has been selected.

Run the automated suite with the Minecraft native directory:

```powershell
./gradlew.bat :gpu-probe:runGpuFlipValidation --offline --no-daemon `
  '-PgpuProbeNativesDir=Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\26.2-Fabric 0.19.3-natives\lwjgl\3.4.1-snapshot\x64' `
  -PgpuProbeDevice=0
```

Run the standalone occupancy publication matrix only when re-measuring CPU/GPU performance:

```powershell
./gradlew.bat :gpu-probe:runGpuOccupancyBenchmark --offline --no-daemon `
  '-PgpuProbeNativesDir=Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\26.2-Fabric 0.19.3-natives\lwjgl\3.4.1-snapshot\x64' `
  -PgpuProbeDevice=0
```

Run the targeted 1,000-step GPU/CPU surface gate after changing physical substeps, advection, collision, marker classification or snapshot density:

```powershell
./gradlew.bat :gpu-probe:runGpuLongRunSurfaceValidation --offline --no-daemon `
  '-PgpuProbeNativesDir=Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\26.2-Fabric 0.19.3-natives\lwjgl\3.4.1-snapshot\x64' `
  -PgpuProbeDevice=0
```
