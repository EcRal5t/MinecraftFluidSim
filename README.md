# Real Water Prototype

A Minecraft Java 26.2 Fabric prototype for bounded, pressure-based 3D water simulation.

This version is a safe test harness, not yet a global replacement for vanilla water. It owns one temporary server-side 3D PIC/FLIP domain, sends reconstructed density and collision snapshots to clients, and rebuilds a translucent isosurface every frame. It does not persist simulation data or modify world blocks automatically.

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

While either recovery file exists, keep a full world backup and do not remove or downgrade Real Water. First run `/realwater remove`, or let startup recovery finish, and confirm both files have disappeared. An administrator who manually deletes the recovery files or removes the mod before recovery can defeat safeguards that code can no longer enforce.

## Optional windowless GPU probe

The separate `realwater-gpu-probe` jar is an M1 development probe, not a replacement backend for the main Mod. It initializes Vulkan lazily without a GLFW window or surface, never reads or changes world blocks, and never writes simulation data to a save. Its compute SSBOs now use device-local memory: initialization uploads through staging, every physical step reads back only a 64-byte reduced diagnostic block, and the compact surface snapshot is staged only when the client update is due. A host-visible mode remains in the standalone suite as a same-input baseline. `/realwatergpu demo` exposes its Jacobi GPU dam-break through the existing client surface renderer; `step`, `run`, `pause`, `status`, `benchmark [12|32] [device] [jacobi|rbgs|pcg|pcgdiag|mg2]`, and `remove` are independent from the main `/realwater` lifecycle. RBGS/SOR and both PCG variants remain negative benchmark experiments. The two-level `mg2` V-cycle passes both the 64³ cost/quality gate and a synthetic thin-wall/shelf topology gate, but Jacobi remains the demo default until a fuller hierarchy and real-world collision ingestion are validated. If Vulkan initialization fails, only this visual demo falls back to an isolated CPU domain. The main Mod remains CPU-authoritative with or without the probe jar. Its command transcript is diagnostic output in the normal game logs directory, not simulation persistence.

See [docs/gpu-probe-report.md](docs/gpu-probe-report.md) for the two-GPU capability results, CPU/GPU correctness tolerances, static-column validation, the real 2,097,152-marker benchmark, readback measurements, commands, and remaining M1 work.

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
