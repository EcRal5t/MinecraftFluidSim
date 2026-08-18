# Real Water Prototype

A Minecraft Java 26.2 Fabric prototype for bounded, pressure-based 3D water simulation.

This version is a safe test harness, not yet a global replacement for vanilla water. Its in-memory `FluidRegionManager` currently exposes only one selected temporary server-side 3D PIC/FLIP region, sends reconstructed density and collision snapshots to clients, and rebuilds a translucent isosurface every frame. Each region owns independent pause/pacing state and a hard marker budget; replacement is budget-checked before the old region is removed. Multi-region ticking, controller discovery, network region ids and persistence are not enabled yet. It does not persist simulation data or modify world blocks automatically.

## In-game commands

- `/realwater demo` creates a paused mathematical dam-break domain about nine blocks in front of the player. It does not change blocks.
- `/realwater capture` samples vanilla water and collision shapes in a 12×8×12-block region centered near the player. It does not remove the vanilla water.
- `/realwater takeover` performs the same capture, then temporarily replaces standalone vanilla water blocks with air so only the simulated water remains. `/realwater remove`, starting another domain, or a normal server shutdown restores the captured block states.
- `/realwater pour [1-16]` injects that many block-volumes of simulated water where the crosshair enters the domain. The default is one bucket/block.
- `/realwater drain [1-16]` removes simulated water along the crosshair ray. The default is one bucket/block.
- `/realwater refresh` immediately re-samples collision geometry; this also happens automatically every five server ticks.
- `/realwater entities [true|false]` reports or toggles bounded entity buoyancy and drag.
- `/realwater debug [true|false]` shows or hides the orange collision cells and yellow domain bounds. It is hidden by default.
- `/realwater step [1-20]` advances a paused domain by a bounded number of steps.
- `/realwater run` advances at 20 Hz when affordable and automatically lowers the simulation rate under sustained load.
- `/realwater pause` pauses automatic stepping.
- `/realwater status` reports grid size, total water, maximum pressure, solve iterations, residual, and time.
- `/realwater logpath` prints the absolute path of the dedicated UTF-8 command transcript.
- `/realwater remove` removes the temporary domain and client debug view.

The blue mesh is a marching-tetrahedra isosurface reconstructed from the current water particles. With `/realwater debug true`, translucent orange faces show collision subcells and yellow edges show the bounded domain. A 40 ms soft budget lowers stepping to 10, 6.7, or 5 Hz instead of stopping; only three consecutive steps above 200 ms auto-pause the domain.

Collision sampling runs at the same two-cells-per-block resolution as the fluid. Slabs, stairs, fences, and other non-full collision shapes therefore occupy only the subcells they intersect. If a newly placed block overlaps simulated water, the service conservatively moves that water into nearby capacity when possible and reports any volume that could not fit in the bounded domain.

The active solver follows the same structure as the Godot reference: particle-to-grid transfer, gravity, MAC-grid pressure projection, a 90% FLIP / 10% PIC grid-to-particle transfer, and particle advection with solid collision. A full solver cell starts with eight markers; at two cells per Minecraft axis this is about 64 particles per full water block, rather than 4096. Particle volume is conserved directly, so water cannot turn into a domain-wide low-density VOF haze.

Entities inside the active domain are sampled at 45 points across their bounding box. Their submerged fraction produces buoyancy, while drag moves their velocity toward the local MAC-grid water velocity. Flying/spectating players, passengers, no-physics entities, and entities already handled by vanilla water are skipped. This is currently one-way coupling: water moves entities, but entities do not yet push momentum back into the water field.

There are no wall-contact, corner-filling, or forced-leveling rules in the active physics. Solid behavior comes from zero-normal MAC velocities plus particle collision, as in the Godot algorithm. Boundary-aware density-kernel normalization preserves particle mass when reconstructing cells next to solids. Client snapshots are blended over their 200 ms interval, and the client extracts one continuous isosurface instead of drawing a separate cuboid for every half-block cell.

Version 0.6.1 separates pressure-cell classification from the smooth P2G density field: a pressure cell must actually contain a marker particle instead of merely receiving a tiny interpolation weight. Particle collision now constrains only the wall-normal coordinate, matching the Godot CCD pass and preserving tangential motion along walls and corners. The client convolves reconstructed density before isosurface extraction so sparse sampling does not create internal triangular cavities.

Version 0.7 adds an explicit bounded takeover session. Only pure `water` blocks are removed; waterlogged blocks are left untouched. Original block states are restored only where the position is still air or water, so a solid block placed during the test is never overwritten.

Version 0.7.1 makes live collision displacement use the exact particle volume removed by a new solid instead of an interpolated grid-density estimate, eliminating the small mass loss seen after block placement. Snapshots now include the actual marker-cell mask in addition to smooth density. Marker cells seed the implicit surface, so a shallow puddle remains visible even when no density cell reaches the 0.5 isosurface threshold. Status reports both particle count and occupied marker-cell count.

Version 0.8 makes takeover restoration crash-safe. Before any vanilla water is removed, the exact dimension, block positions, and block states are written to `realwater-takeover-backup.nbt` in the world directory through a temporary file and atomic replacement. A leftover journal is recovered automatically when that world next starts. The journal is deleted only after restoration completes; if it cannot be written, takeover is refused without modifying the world.

Version 0.8.1 completes the automated M0 transaction hardening without changing takeover-journal v1. An existing, corrupt, unknown-version, unloaded-dimension, or otherwise unresolved journal locks every new takeover and is never overwritten. The journal and `realwater-takeover-WARNING.txt` must both be atomically published before world mutation; filesystems without atomic move support fail closed. Restoration failures and cleanup failures preserve the journal for an idempotent retry. The warning names the world, dimension, backed-up block count, and recovery steps.

Version 0.8.2 adds an append-only UTF-8 transcript at `logs/realwater-commands.log`. Every `/realwater` and `/realwatergpu` command that reaches its handler records the exact command, source, all success/failure/detail output, and Brigadier result code as one timestamped block. `/realwater logpath` prints its absolute location. Syntax errors rejected before a handler runs, such as a misspelled subcommand, remain in Minecraft's `latest.log` because Fabric exposes no command-execution callback at that parse boundary.

The M0 takeover transaction milestone is complete. The normal real-world smoke restored 48 standalone water blocks; a separate forced-process-interruption smoke restored all 60 captured water blocks on the next world start, deleted both recovery files, cleared the takeover lock and left the bounded adjacent-water, sand/gravel, redstone and neighbor-sensitive setup stable. These tests do not expand takeover beyond pure `water` blocks.

While either recovery file exists, keep a full world backup and do not remove or downgrade Real Water. First run `/realwater remove`, or let startup recovery finish, and confirm both files have disappeared. An administrator who manually deletes the recovery files or removes the mod before recovery can defeat safeguards that code can no longer enforce.

## Optional windowless GPU probe

The separate `realwater-gpu-probe` jar is an M1 development probe, not a replacement backend for the main Mod. It initializes Vulkan lazily without a GLFW window or surface, never changes world blocks, and never writes simulation data to a save. Its compute SSBOs use device-local memory: initialization uses serialized bulk staging, while ordinary physical steps submit through a bounded three-slot command/fence/query ring and copy only one 64-byte reduced diagnostic block into each slot. Compact snapshots and diagnostic full-state reads first settle the ring and then use the explicit bulk path. A host-visible mode remains in the standalone suite as a same-input baseline. `/realwatergpu demo` exposes its world-independent Jacobi dam-break; `/realwatergpu worlddemo` explicitly captures one frozen 2×2×2-subcell collision snapshot from already-loaded nearby chunks and runs it with solid-aware MG2. The latter refuses missing chunks, owns no chunk tickets, does not sample world water, and ignores subsequent block edits. Probe 0.1.9 also exposes the adaptive packed full-hierarchy `mgh` pressure benchmark; a 64³ solver grid uses 32³→16³→8³→4³ coarse levels, while the current small domain stops after its useful first coarse level. Probe 0.1.10 fixes and structurally tests the optional device argument under `/realwatergpu probe [device]`. Probe 0.1.11 adds compute-queue Vulkan timestamp queries. Probe 0.1.12 moves the main GLSL program into a packaged shader resource and separates expensive 64³ validation from the small-domain suite without changing any stage or equation. Probe 0.1.13 removes per-step `vkQueueWaitIdle`: GPU timestamp remains device observation, batch throughput wall is reported separately, and scheduling/backpressure wall remains the server scheduler and continuous-overrun truth. Probe 0.1.14 creates a validated CPU-readable marker/velocity/solid checkpoint at generation 0 and every 20 completed steps; a runtime GPU synchronization failure rolls the visual probe back to the last complete generation, switches it to the isolated CPU fallback, and leaves it paused. Probe 0.1.15 adds the separate headless occupancy matrix. Probe 0.1.16 makes each 50 ms GPU physical step four 12.5 ms numerical substeps, aligns domain/solid normal contact with the CPU `0.45h` marker radius, and adds a 1,000-step CPU/GPU marker-layer and edge-density gate for the prior rapid surface-collapse regression. On the RTX 5070 Ti the remeasured 20,736-marker reference dam is 5.16× faster by completed-batch throughput; the single-marker crossover is near 1,024 pressure-fluid cells. This does not select a production backend. `step`, `run`, `pause`, `status`, `benchmark [12|32] [device] [jacobi|rbgs|pcg|pcgdiag|mg2|mgh]`, and `remove` remain independent from the main `/realwater` lifecycle. MGH is an experimental quality/cost candidate rather than the default, and RBGS/SOR and both PCG variants remain negative benchmark experiments. If Vulkan initialization fails, only the visual probe falls back to an isolated CPU domain with the same solid mask. The main Mod remains CPU-authoritative with or without the probe jar. Its command transcript is diagnostic output in the normal game logs directory, not simulation persistence.

See [docs/gpu-probe-report.md](docs/gpu-probe-report.md) for the completed M1 two-GPU capability, correctness, pressure, 2,097,152-marker, readback, recovery, occupancy and 30-minute integrated stability results.

The standalone CPU M1 coefficient gate has selected an interface-distance-weighted conservative `1/rho` face coefficient for future variable-density work. This is an experimental M3 input, not an integration into the current constant-density `FlipFluidDomain`; fractional interface reconstruction and 3D connected-column validation remain prerequisites.

## Build

Minecraft 26.2 targets Java 25. On this machine the project is built with the installed JDK 26 while compiling to Java 25 bytecode:

```powershell
$env:JAVA_HOME = 'D:\ProgramFiles\BienCreng\jdk-26.0.2'
./gradlew.bat assemble --offline --no-daemon
```

The standalone M0 journal/transaction suite uses only the already-resolved offline compile classpath:

```powershell
./gradlew.bat runTakeoverJournalValidation --offline --no-daemon
```

The optional GPU module has a windowless correctness/benchmark task. It requires the LWJGL native directory extracted by this Minecraft installation:

```powershell
./gradlew.bat :gpu-probe:runGpuFlipValidation --offline --no-daemon `
  '-PgpuProbeNativesDir=Z:\Games\Minecraft\Pure\.minecraft\versions\26.2-Fabric 0.19.3\26.2-Fabric 0.19.3-natives\lwjgl\3.4.1-snapshot\x64' `
  -PgpuProbeDevice=0
```

See [docs/fluid-prototype.md](docs/fluid-prototype.md) for the current numerical model, standalone validation, and benchmark. For maintenance and handoff, use [docs/maintenance-routing.md](docs/maintenance-routing.md). The confirmed path from this bounded prototype to multi-material, gas, chemistry, granular deposits, persistence, and optional GPU execution is documented in [docs/long-term-roadmap.md](docs/long-term-roadmap.md).

## License

CC0-1.0
