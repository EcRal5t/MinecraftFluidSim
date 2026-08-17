# 3D fluid prototype

The first implementation milestone is intentionally independent from Minecraft and rendering. It answers three questions before chunk storage, networking, or surface meshing add more variables:

1. Does a three-dimensional pressure projection converge for a small active domain?
2. Does a resting column produce a hydrostatic pressure gradient?
3. Can a moving free surface conserve water volume?

## Model

- Positive Y is up, matching Minecraft coordinates.
- Water is incompressible and has a constant density of 1000 simulation units.
- Atmospheric pressure is zero gauge pressure.
- Volume fraction lives at cell centers and is bounded to `[0, 1]`.
- Velocity lives on the three sets of cell faces (a staggered MAC grid).
- Pressure lives at cell centers and is solved with diagonally preconditioned conjugate gradients.
- Static solid cells impose a zero normal face velocity.
- Velocity uses semi-Lagrangian advection for stability.
- Volume uses conservative, capacity-limited upwind transport.

The prototype does not yet contain FLIP marker particles. This is deliberate: a dense Eulerian volume field establishes pressure, conservation, and Minecraft collision semantics with much less state. FLIP/APIC markers can be introduced later only in active surface cells if semi-Lagrangian velocity dissipation is visibly excessive.

## Manual validation

The validation programs have no external test dependency. With a Java 25+ JDK:

```powershell
$out = 'build\prototype-test'
New-Item -ItemType Directory -Force -Path $out | Out-Null
javac --release 25 -Xlint:all -d $out `
  'src\main\java\com\example\fluid\FluidSolverConfig.java' `
  'src\main\java\com\example\fluid\FluidDomain.java' `
  'src\test\java\com\example\fluid\FluidDomainValidation.java' `
  'src\test\java\com\example\fluid\FluidDomainBenchmark.java'
java -ea -cp $out com.example.fluid.FluidDomainValidation
java -cp $out com.example.fluid.FluidDomainBenchmark 64 5
```

## Next integration boundary

The first Minecraft-facing adapter should create one bounded `FluidDomain` for a test chamber and copy full cube collisions into its solid mask. It should not replace arbitrary oceans yet. The server owns the domain and steps it at 20 Hz; the client receives coarse volume/velocity snapshots and interpolates them for rendering.

Before expanding to chunk-scale persistence, add:

- fractional-cell free-surface pressure weights;
- active-cell/sparse pressure iteration;
- entity buoyancy sampling;
- domain split/merge rules;
- a chunk-section codec for volume and face velocity;
- a surface extractor that does not expose the simulation grid as thousands of Minecraft particles.

## Current Minecraft harness

The Fabric harness now owns one temporary `24×16×24` cell domain representing a `12×8×12` block region at two cells per block. It starts paused, remains entirely in memory, and has a 75 ms automatic-pause guard.

Use `/realwater demo` for a block-independent dam-break field or `/realwater capture` to sample nearby vanilla water and full-cube collision shapes. The server sends an unsigned-byte volume snapshot every four running ticks. The client visualizes a bounded random sample as blue dust particles; these are debug points, not simulated entities.

No command other than the explicitly requested capture reads world fluid state, and none of the commands currently changes world blocks. `/realwater remove` discards the active domain.
