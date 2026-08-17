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

No command other than the explicitly requested capture reads world fluid state, and none of the commands currently changes world blocks. `/realwater remove` discards the active domain.
