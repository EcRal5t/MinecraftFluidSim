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

While either recovery file exists, keep a full world backup and do not remove or downgrade Real Water. First run `/realwater remove`, or let startup recovery finish, and confirm both files have disappeared. An administrator who manually deletes the recovery files or removes the mod before recovery can defeat safeguards that code can no longer enforce.

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

See [docs/fluid-prototype.md](docs/fluid-prototype.md) for the current numerical model, standalone validation, and benchmark. For maintenance and handoff, use [docs/maintenance-routing.md](docs/maintenance-routing.md). The confirmed path from this bounded prototype to multi-material, gas, chemistry, granular deposits, persistence, and optional GPU execution is documented in [docs/long-term-roadmap.md](docs/long-term-roadmap.md).

## License

CC0-1.0
