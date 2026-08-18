# 3D fluid prototype

The current implementation keeps its numerical core independent from Minecraft and rendering. Its validation answers three questions before chunk storage and persistence add more variables:

1. Does a three-dimensional pressure projection converge for a small active domain?
2. Does a resting column produce a hydrostatic pressure gradient?
3. Can a moving free surface conserve water volume?

## Model

- Positive Y is up, matching Minecraft coordinates.
- Water is incompressible and has a constant density of 1000 simulation units.
- Atmospheric pressure is zero gauge pressure.
- Marker particles carry liquid volume and velocity.
- Eight particles initialize one full solver cell.
- Velocity lives on the three sets of cell faces (a staggered MAC grid).
- Pressure lives at cell centers and is solved with diagonally preconditioned conjugate gradients.
- Static solid cells impose a zero normal face velocity.
- Particle-to-grid and grid-to-particle transfers use trilinear interpolation, the 3D counterpart of the Godot shaders' bilinear interpolation.
- Grid-to-particle transfer blends 90% FLIP with 10% PIC.
- Particles are advected through the projected velocity field and collide against the solid-cell mask axis by axis.
- Cell density is reconstructed from particle volume; valid kernel weights are normalized at solid/domain boundaries so reconstruction does not visually lose mass beside walls.

This is a direct three-dimensional adaptation of the reference project's PIC/FLIP cycle: clear grid, P2G, forces, divergence/pressure, projection, G2P, and particle advection. It does not add wall-wetting, corner attraction, or forced surface-leveling rules. The earlier VOF solver remains only as a standalone comparison and is no longer selected by the Minecraft service.

## Variable-density coefficient experiment

The active Minecraft solver remains constant-density. A separate 12-cell CPU finite-volume column now compares three future M3 face coefficients for `div((1/rho) grad(p))`: arithmetic averaging of `1/rho`, inversion after averaging `rho`, and a conservative coefficient weighted by the reconstructed interface distance. The gate covers 1:1, water/oil 1.25:1, and mercury/water 13.6:1 ratios at interface fractions 0.25, 0.50, and 0.75. All candidates keep the matrix symmetric positive definite and converge in 12 diagonal-PCG iterations; only the distance-weighted coefficient reproduces the discrete hydrostatic pressure and eliminates interface parasitic velocity for every tested fraction. It is the selected integration candidate, not an active solver change: M2.5 must still provide a trustworthy fractional interface distance, and M3 must validate the result in 3D connected columns before adoption.

## Manual validation

The validation programs have no external test dependency. With a Java 25+ JDK:

```powershell
$out = 'build\prototype-test'
New-Item -ItemType Directory -Force -Path $out | Out-Null
javac --release 25 -Xlint:all -d $out `
  'src\main\java\com\example\fluid\FluidSolverConfig.java' `
  'src\main\java\com\example\fluid\FlipFluidDomain.java' `
  'src\test\java\com\example\fluid\FlipFluidDomainValidation.java'
java -ea -cp $out com.example.fluid.FlipFluidDomainValidation
```

Run the isolated variable-density selection gate through Gradle:

```powershell
./gradlew.bat runVariableDensityFaceCoefficientValidation --offline --no-daemon
```

## Next integration boundary

The Minecraft adapter creates one bounded `FlipFluidDomain` for a test chamber and samples block collision shapes into its solid mask. It does not replace arbitrary oceans yet. The server owns the domain and targets 20 Hz; the client receives coarse density snapshots and interpolates them for rendering.

Before expanding to chunk-scale persistence, add:

- fractional-cell free-surface pressure weights;
- active-cell/sparse pressure iteration;
- entity buoyancy sampling;
- domain split/merge rules;
- a chunk-section codec for volume and face velocity;
- particle reseeding to keep marker distribution uniform over very long runs;
- two-way momentum coupling from entities back into the fluid.

## Current Minecraft harness

The Fabric harness now owns one temporary `24×16×24` cell domain representing a `12×8×12` block region at two cells per block. It starts paused and remains entirely in memory. A soft 40 ms budget adaptively reduces simulation frequency; three consecutive steps above 200 ms trigger the final safety pause.

Use `/realwater demo` for a block-independent dam-break field or `/realwater capture` to sample nearby vanilla water and collision shapes. The server sends unsigned-byte reconstructed-density and solid-mask snapshots every four running ticks. The client extracts a continuous blue isosurface; translucent orange is captured collision and yellow edges are the domain boundary when debug overlays are enabled. The mesh contains only current state, so it has no drifting or historical particle trail.

Version 0.3 adds bounded interaction and live collision updates. `/realwater pour` and `/realwater drain` edit water in block-volume units along the player's view ray. Collision shapes are sampled into the 2×2×2 subcells of each Minecraft block every five server ticks, including partial shapes rather than only full cubes. New solids displace overlapping water into nearby free capacity before any remainder is allowed to leave the bounded domain.

Version 0.4 addresses long-run free-surface dilution. A conservative anti-diffusive face flux moves volume from the less-filled side of an interface toward its more-filled side, using the same donor and receiver capacity limiters as advection. The Minecraft-sized diagnostic records total volume, active/visible/dense cell counts, and volume by height; total mass remains unchanged while the stable surface occupies far fewer low-fill cells.

Version 0.5 adds bounded server-authoritative entity coupling. A 3×5×3 quadrature over each entity bounding box estimates submerged fraction and mean local staggered-grid velocity. Buoyancy is scaled to reach neutral force near 72% immersion, and linear drag relaxes entity velocity toward the water velocity. The coupling is deliberately one-way for this milestone and can be disabled with `/realwater entities false`.

Version 0.6 replaces the active VOF transport with the 3D PIC/FLIP cycle derived from the Godot reference. Particle-carried volume eliminates the VOF haze without wall-contact or forced-leveling patches. Boundary-normalized density reconstruction and marching tetrahedra provide a continuous client surface. Snapshots interpolate across 200 ms, collision/bounds overlays are optional, and expensive domains reduce their simulation frequency before the hard pause guard is considered.

Version 0.6.1 fixes two 2D-to-3D translation errors. Pressure phase classification uses the cell containing each marker rather than all eight cells touched by its trilinear P2G kernel. Solid contact follows the reference's axis-separated CCD: only normal motion is constrained, so contact with one or two walls cannot freeze tangential motion. A compact `[1,2,1]^3` density reconstruction kernel is evaluated client-side before isosurface extraction; it changes only visualization and closes subcell sampling cavities without moving particle volume.

Version 0.7 introduces `/realwater takeover` as the first reversible bridge from the bounded harness to real world water. It captures the same region as `/realwater capture`, temporarily removes standalone vanilla water blocks without neighbor updates, and keeps suppressing those original positions while the domain exists. `/realwater remove`, a replacement domain, or normal server shutdown restores the exact original states where no solid player edit would be overwritten.

Version 0.7.1 separates two more quantities that cannot safely be inferred from smoothed density. A solid-mask change asks the domain for the exact particle volume it removed and restores that amount nearby, rather than using a P2G density sample as a proxy. Client snapshots carry marker occupancy alongside density; occupied cells seed the inside of the implicit surface while the convolved density still shapes it. This preserves thin-puddle visibility without changing particle positions or manufacturing fluid volume.

Version 0.8 adds an on-disk takeover journal. It serializes exact block states with Minecraft's NBT codec and atomically publishes the compressed file before world mutation begins. Normal restore deletes the journal only after all safe positions have been processed. A journal left by a crash is replayed on the next server start; occupied player edits are still skipped. This transaction boundary makes the temporary takeover safe enough for broader integration tests.

Version 0.8.1 keeps takeover-journal v1 byte-structure compatibility and hardens its transaction boundary. Existing or unreadable recovery state locks new takeover, journal and human-readable warning publication are both atomic prerequisites, non-atomic filesystems fail closed, and restore/delete failures remain retryable. The standalone fault suite exercises every named interruption boundary without loading or modifying a real world.

Version 0.8.2 routes handled `/realwater` and `/realwatergpu` commands and their complete responses to the append-only UTF-8 `logs/realwater-commands.log` transcript. This is observability only: it does not participate in particle volume, numerical fields, rendering state, scheduling or world recovery.

The optional M1-H `gpu-probe` subproject provides a windowless Vulkan version of the complete 3D P2G → gravity → pressure projection → 90/10 FLIP/PIC G2P → axis-separated advection cycle. It keeps marker volume as truth and produces density/marker/solid snapshots on the GPU for the existing renderer. Compute state resides in device-local memory; initialization and explicit bulk reads use serialized staging, while ordinary steps use three independent command/fence/query slots with one 64-byte diagnostic staging block each. Jacobi, RBGS/SOR, identity-PCG, diagonal-PCG, two-level MG2 and packed full-hierarchy MGH share the same fine-grid Poisson discretization and have automated convergence curves plus current/64³-grid timing comparisons. The simple PCG/RBGS alternatives fail the large-grid replacement gate; MG2 is the low-cost baseline and MGH improves the two-cycle 64³ residual by recursively traversing 32³→16³→8³→4³ coarse levels. Coarse diagonal and per-axis face weights are aggregated from fluid/air/solid connections at every restriction, and prolongation near mixed-solid aggregates uses topology-safe injection so a one-cell wall is not erased. In addition to the generated thin-wall/shelf gate, 0.1.8 expands per-block 2×2×2 collision bits into the solver mask and verifies identical GPU/CPU-fallback solids, exact particle volume and zero GPU particles inside solids on both devices. `/realwatergpu worlddemo` captures the same full/partial collision-shape semantics once through non-loading access to already-loaded chunks, then freezes the mask; it never writes the world or save. The generated demo remains the Jacobi baseline, `worlddemo` explicitly exercises MG2, and the packaged 0.1.9 `benchmark ... mgh` path has passed while MGH remains an explicit candidate. Probe 0.1.10 adds a command-tree regression for both `/realwatergpu probe` forms. Probe 0.1.11 adds per-step Vulkan timestamps, and 0.1.12 extracts the byte-identical GLSL resource while splitting small/64³ validation. Probe 0.1.13 removes the physical-step `vkQueueWaitIdle` lifecycle with a bounded three-slot staging ring; GPU timestamp, completed-batch throughput wall and server scheduling/backpressure wall are reported separately. Scheduling/backpressure wall remains the hard-overrun truth. Same-input memory-mode comparison, dam-break CPU/GPU tolerance, MG2/MGH hydrostatic pressure, four-level topology, exact particle-volume and 2,097,152-marker throughput checks are recorded in [`gpu-probe-report.md`](gpu-probe-report.md).

`/realwater capture` only reads world fluid state. The explicitly requested `/realwater takeover` temporarily removes standalone vanilla water after publishing its crash-recovery journal; `/realwater remove`, normal shutdown, or next-start recovery restores safe positions. Other simulation commands do not modify world fluid blocks.
