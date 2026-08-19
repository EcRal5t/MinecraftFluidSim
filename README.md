# Real Water Prototype

A Minecraft Java 26.2 Fabric prototype for bounded, pressure-based 3D water simulation.

This version is a safe bounded-region harness, not yet a global replacement for vanilla water. Its `FluidRegionManager` can tick and render up to four non-overlapping server-side 3D PIC/FLIP regions independently under configurable per-region and total budgets. One region remains selected for commands, while every region in the player's dimension has an independently ordered client surface. Controller regions persist through `region-state v1`: a missing required chunk, broken shell or disabled feature flag checkpoints the whole region before releasing its solver into metadata-only `UNLOADED`. Ordinary simulation still does not modify world blocks automatically.

## In-game commands

- `/realwater demo` creates a paused mathematical dam-break domain about nine blocks in front of the player. It does not change blocks.
- `/realwater capture` samples vanilla water and collision shapes in a 12×8×12-block region centered near the player. It does not remove the vanilla water.
- `/realwater takeover` performs the same capture, then temporarily replaces standalone vanilla water blocks with air so only the simulated water remains. `/realwater remove`, starting another domain, or a normal server shutdown restores the captured block states.
- `/realwater pour [1-16]` injects that many block-volumes of simulated water where the crosshair enters the domain. The default is one bucket/block.
- `/realwater drain [1-16]` removes simulated water along the crosshair ray. The default is one bucket/block.
- `/realwater refresh` immediately re-samples collision geometry; this also happens automatically every five server ticks.
- `/realwater entities [true|false]` reports or toggles bounded entity buoyancy and drag.
- `/realwater controllers [true|false]` reports or atomically persists the world-local controller feature gate. It defaults to off; disabling it checkpoints and unloads existing controller regions without deleting their water.
- `/realwater regions list` lists selected, loaded and persisted/unloaded regions plus locked persistence issues.
- `/realwater regions select <uuid-or-prefix>` changes the command target without changing its pause state.
- `/realwater regions checkpoint` pauses and atomically checkpoints the selected controller region.
- `/realwater debug [true|false]` shows or hides the orange collision cells and yellow domain bounds. It is hidden by default.
- `/realwater step [1-20]` advances a paused domain by a bounded number of steps.
- `/realwater run` advances at 20 Hz when affordable and automatically lowers the simulation rate under sustained load.
- `/realwater pause` pauses automatic stepping.
- `/realwater status` reports the selected id, lifecycle, total region budget, grid size, total water, maximum pressure, solve iterations, residual, time, the latest marker reseeding diagnostics, and the most recent nonzero collision change so an automatic refresh cannot hide earlier escaped water.
- `/realwater logpath` prints the absolute path of the dedicated UTF-8 command transcript.
- `/realwater remove` restores any active takeover, atomically quarantines committed controller state, then removes all registered regions and client debug views. Unknown/corrupt state is never silently deleted.

The blue mesh is a marching-tetrahedra isosurface reconstructed from the current water particles. With `/realwater debug true`, translucent orange faces show collision subcells and yellow edges show the bounded domain. A 40 ms soft budget lowers stepping to 10, 6.7, or 5 Hz instead of stopping; only three consecutive steps above 200 ms auto-pause the domain.

## Opt-in M2 controller region

Controller gameplay is deliberately administrator-enabled. Obtain `realwater:fluid_boundary` and `realwater:fluid_controller` with `/give`, build a complete one-block-thick shell from those visible blocks, replace one shell block with the controller, run `/realwater controllers true`, then empty-hand right-click the controller. The six-neighbour scan accepts exactly one enclosed interior. It refuses an open shell, a controller touching multiple separate chambers, an unloaded associated chunk, a scan beyond the configured per-region budget, the total budget, overlap with an existing region, or unresolved persistent state. Defaults remain 1,152 interior blocks, 9,216 cells / 73,728 markers per region, four regions and 36,864 / 294,912 total reservations; administrators can edit `<world>/realwater/server.properties` while the server is stopped.

First activation appends and selects a paused region, read-only samples vanilla water inside the shell into exact marker `particleVolume`, and publishes its initial checkpoint without removing any water block or creating a takeover journal. Right-clicking its controller again only selects the existing region. All interior and boundary chunks must already be fully loaded; the Mod acquires no permanent chunk ticket. If any required chunk later unloads or the visible shell breaks, collision sampling, entity coupling, pour/drain and stepping stop before partial access. A complete two-slot checkpoint is validated and HEAD is atomically published before the live solver arrays are released. Returning chunks or a repaired shell restore the exact marker order/volume/velocity, re-sample current collision, and remain paused until explicit `/realwater run`. Other ready regions continue ticking throughout.

M2 was accepted on 2026-08-19 with a four-chunk controller region: chunk unload/return, boundary break/repair and a normal world restart retained the same UUID, 3,100 markers and 377.77777 exact cell-volume. See [the M2 acceptance record](docs/progress/m2-region-state-smoke.md).

Collision sampling runs at the same two-cells-per-block resolution as the fluid. Slabs, stairs, fences, and other non-full collision shapes therefore occupy only the subcells they intersect. If a newly placed block overlaps simulated water, the service conservatively moves that water into nearby capacity when possible and reports any volume that could not fit in the bounded domain.

The active solver follows the same structure as the Godot reference: particle-to-grid transfer, gravity, MAC-grid pressure projection, a 90% FLIP / 10% PIC grid-to-particle transfer, and particle advection with solid collision. A full solver cell starts with eight markers; at two cells per Minecraft axis this is about 64 particles per full water block, rather than 4096. Particle volume is conserved directly, so water cannot turn into a domain-wide low-density VOF haze. M2.5 reconstructs a PLIC plane from each occupied cell's exact marker-volume sum and uses one shared ghost-fluid distance in the pressure matrix, preconditioner and projection. Pressure-phase MAC velocities are extrapolated two air layers before G2P so undefined air faces cannot act as artificial zero-velocity drag on small droplets. A bounded pressure-only rest-volume target prevents long-run marker compression without editing the exact marker ledger or forcing a level surface. A deterministic end-of-step reseeder conservatively merges cells above 12 markers and splits under-sampled cells without exceeding their region budget.

The M2.5 offline gates cover fractional hydrostatic surfaces, a regularized 0.25-cell thin layer, a 0.125-cell discrete-gravity droplet, a fixed stair slope, wall/corner tangential motion, a 5,000-step static pool, and two 5,000-step marker-distribution runs. Runtime collision now samples each solver cell into a 2x2x2 cut mask (4x4x4 per Minecraft block), derives shared open-face areas and open-cell volume, uses them consistently in divergence and pressure projection, and sweeps markers without cancelling tangential motion. Exact marker volume remains unchanged. `region-state v1` stays compatible by persisting only fully solid cells and requiring current-world collision resampling before a restored region can step. Fixed-coordinate stair displacement/restoration, pane classification, small-droplet motion and a two-minute settling-surface observation have passed in Minecraft. M2.5 is complete; see [the numerical record](docs/progress/m2.5-fluid-foundation.md) and [accepted smoke record](docs/progress/m2.5-smoke.md).

Scheduling enters hydrostatic sleep only after 200 consecutive completed steps with low marker speed, low pressure residual, no collision change and no affected entity. The transition removes residual marker momentum but does not move, create, delete or resize markers; marker count and exact `particleVolume` are checked unchanged. Collision updates, entity contact, pour/drain, manual step or run wake the region. This is a scheduling approximation, separate from the pressure discretization and from client surface reconstruction.

Before play, client and server negotiate Real Water protocol v1 and the required region-identity, generation/sequence, optional-section and multi-region capabilities. Missing or incompatible client support is rejected during configuration with a readable reason rather than attempting a misaligned decode. Each snapshot carries its dimension, region UUID, state generation, monotonic sequence, pure-water palette version and length-delimited optional sections. The client ignores duplicate/older sequences, keeps removal tombstones against delayed packets, filters surfaces by the current dimension and renders all current regions together. These density/marker/solid snapshots remain reconstruction-only and never become physical water truth.

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

See [the M1 GPU probe report](docs/progress/m1-gpu-probe-report.md) for the completed two-GPU capability, correctness, pressure, 2,097,152-marker, readback, recovery, occupancy and 30-minute integrated stability results.

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

Start with the [documentation index](docs/README.md). The current numerical contract is in [architecture/fluid-solver.md](docs/architecture/fluid-solver.md), code and minimal-test routing is in [maintenance/routing.md](docs/maintenance/routing.md), completed evidence is in [progress/](docs/progress/README.md), and future work is in [roadmap/product-roadmap.md](docs/roadmap/product-roadmap.md).

## License

CC0-1.0
