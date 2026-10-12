package mmm.simulation;

import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.fluid.*;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularTransferLedger;
import mmm.granular.SolidVolume;
import mmm.thermal.ThermalLimits;
import mmm.world.MovingBodyFluidCoupling;
import jdk.jfr.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Offline evolving workloads and fixed-input replays; timings are not CI thresholds. */
public final class CompositeStepBenchmark {
    @Name("mmm.BenchmarkPhase") @StackTrace(false)
    public static class Phase extends Event { public String phase; public int step; }

    private static final CpuFluidSolverBackend CPU = new CpuFluidSolverBackend();
    private static final int PHASE_BUDGET = ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP;
    private static final com.sun.management.ThreadMXBean ALLOC =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long THREAD = Thread.currentThread().threadId();
    private static volatile Object observed;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalArgumentException(
                "scene warmup samples output-prefix [jfr]; scenes: granular, granular-active, granular-large, thermochem, mixed, presentation");
        String scene = args[0];
        int warmup = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
        if (warmup < 0 || samples <= 0) throw new IllegalArgumentException("Invalid sample counts");
        Path prefix = Path.of(args[3]).toAbsolutePath();
        Files.createDirectories(prefix.getParent());
        require(ALLOC.isThreadAllocatedMemorySupported(), "Thread allocation measurement is unavailable");
        ALLOC.setThreadAllocatedMemoryEnabled(true);
        var registries = FullCapabilityTestFixture.load();
        FluidRegion[] regions = switch (scene) {
            case "granular", "granular-active" -> new FluidRegion[]{region(granular(registries, scene.endsWith("active")))};
            case "granular-large" -> new FluidRegion[]{region(largeGranular(registries))};
            case "thermochem" -> new FluidRegion[]{region(ProductionReplayValidationSupport.createFullyBoundPlanarHalfFill(registries))};
            case "mixed" -> new FluidRegion[]{
                    region(ProductionReplayValidationSupport.createFullyBoundPlanarHalfFill(registries)),
                    region(ProductionReplayValidationSupport.createFullyBoundCompressedPlanarHalfFill(registries)),
                    region(ProductionReplayValidationSupport.createFullyBoundPlanarHalfFill(registries))};
            case "presentation" -> new FluidRegion[]{region(ProductionReplayValidationSupport.createFullyBoundDamBreakDemo(registries))};
            default -> throw new IllegalArgumentException("Unknown scene " + scene);
        };
        // Rebuild a real pressure projection before using the isolated thermal axis.
        if (scene.equals("mixed")) motion(regions[1], List.of());
        // Fixed-input replay isolates the active coupling kernel from later deposition.
        FluidDomainState replay = scene.equals("granular-active") || scene.equals("granular-large")
                ? regions[0].domain().captureAuthoritativeState() : null;
        boolean profile = args.length > 4 && Boolean.parseBoolean(args[4]);
        try (var recording = profile ? new Recording(Configuration.getConfiguration("profile")) : null;
             var out = new PrintWriter(prefix + ".csv")) {
            if (recording != null) { recording.enable(Phase.class); recording.setDestination(Path.of(prefix + ".jfr")); }
            out.println("step,measured,total_ms,allocated_bytes,operation_ms,motion_calls,thermal_calls,idle_regions,markers,active_parcels,deposit_groups,substeps,pcg,chemistry_changed,precipitations,settled,woken,coupled_markers,impulse_norm,solid_svq,energy_dyadic,capture_ms,capture_bytes,restore_ms,restore_bytes,snapshot_ms,snapshot_bytes,mesh_ms,mesh_bytes");
            double[] times = new double[samples]; long bytes = 0;
            long changed = 0, precipitated = 0, settled = 0, woken = 0, coupled = 0;
            long measuredChanged = 0, measuredPrecipitated = 0;
            boolean disturb = false;
            for (int step = 0; step < warmup + samples; step++) {
                if (step == warmup && recording != null) recording.start();
                Phase event = new Phase(); event.phase = scene; event.step = step;
                long[] stageNanos = new long[4], stageBytes = new long[4];
                event.begin();
                long beforeBytes = allocated(), start = System.nanoTime();
                Counters count = new Counters();
                long operationStart = System.nanoTime();
                if (scene.equals("thermochem")) feed(regions[0], registries, step, count);
                double operations = (System.nanoTime() - operationStart) / 1e6;
                if (scene.equals("presentation")) {
                    var domain = regions[0].domain();
                    long stageAllocation = allocated(), stageStart = System.nanoTime();
                    var state = domain.captureAuthoritativeState();
                    stageNanos[0] = System.nanoTime() - stageStart; stageBytes[0] = allocated() - stageAllocation;
                    stageAllocation = allocated(); stageStart = System.nanoTime();
                    observed = FluidDomain.restoreAuthoritativeState(state);
                    stageNanos[1] = System.nanoTime() - stageStart; stageBytes[1] = allocated() - stageAllocation;
                    stageAllocation = allocated(); stageStart = System.nanoTime();
                    var material = domain.copyMaterialRenderSnapshot();
                    observed = domain.copyChemicalDisplaySnapshot();
                    observed = domain.copyGranularDisplaySnapshot();
                    observed = domain.copyThermalDisplaySnapshot();
                    stageNanos[2] = System.nanoTime() - stageStart; stageBytes[2] = allocated() - stageAllocation;
                    stageAllocation = allocated(); stageStart = System.nanoTime();
                    observed = FluidMaterialSurfaceMesher.build(material);
                    stageNanos[3] = System.nanoTime() - stageStart; stageBytes[3] = allocated() - stageAllocation;
                } else if (scene.equals("mixed")) {
                    // Explicit offline workload schedule, independent of Minecraft activity classification.
                    if (step % 2 == 0) count.add(motion(regions[0], body(regions[0].domain(), 0.25)));
                    else count.idle++;
                    var thermal = CPU.evaluateThermal(new FluidThermalStepRequest(regions[1].solverGeneration(),
                            regions[1].domain().captureAuthoritativeState(), true, PHASE_BUDGET));
                    regions[1].adoptThermalStepCandidate(thermal, CPU.id()); count.thermal++;
                    count.changed += thermal.stats().chemistry().changedMarkers();
                    count.precipitated += thermal.stats().granularPhase().precipitations();
                    count.idle++; // Third region is explicitly held unchanged.
                } else {
                    var requests = scene.startsWith("granular") && disturb ? body(regions[0].domain(), 1.0) : List.<MovingBodyFluidCoupling.Request>of();
                    if (replay == null) count.add(motion(regions[0], requests));
                    else {
                        var candidate = CPU.evaluate(new FluidStepRequest(regions[0].solverGeneration(), replay, List.of(), true, PHASE_BUDGET));
                        regions[0].adoptStepCandidate(candidate, CPU.id()); count.add(candidate);
                    }
                }
                long elapsed = System.nanoTime() - start, allocation = allocated() - beforeBytes;
                event.end(); if (recording != null && step >= warmup) event.commit();
                // Observation, audits and CSV formatting are outside BOTH measured intervals.
                FluidDomain domain = regions[0].domain();
                var solid = domain.exportGranularState();
                changed += count.changed; precipitated += count.precipitated;
                settled += count.settled; woken += count.woken; coupled += count.coupled;
                if (scene.startsWith("granular") && settled > 0) disturb = true;
                for (var region : regions) require(region.domain().gasBoundary().auditClosed()
                        && region.domain().exactInternalEnergy().equals(region.domain().thermalAudit().expectedInventory()), "Inventory audit failed");
                out.printf(Locale.ROOT, "%d,%b,%.6f,%d,%.6f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.9g,%d,%s",
                        step, step >= warmup, elapsed / 1e6, allocation, operations, count.motion, count.thermal, count.idle,
                        domain.particleCount(), solid.activeParcels().size(), solid.depositGroups().size(), count.substeps, count.pcg,
                        count.changed, count.precipitated, count.settled, count.woken, count.coupled, count.impulse,
                        domain.totalGranularSolidVolumeSvq(), domain.exactInternalEnergy().toString().replace(",", ";"));
                for (int stage = 0; stage < 4; stage++) out.printf(Locale.ROOT, ",%.6f,%d", stageNanos[stage] / 1e6, stageBytes[stage]);
                out.println();
                if (step >= warmup) {
                    times[step - warmup] = elapsed / 1e6; bytes += allocation;
                    measuredChanged += count.changed; measuredPrecipitated += count.precipitated;
                }
            }
            if (recording != null) recording.stop();
            Arrays.sort(times);
            System.out.printf(Locale.ROOT, "%s mean=%.6f p95=%.6f callerMiB=%.6f changed=%d precipitated=%d settled=%d woken=%d coupled=%d%n",
                    scene, Arrays.stream(times).average().orElseThrow(), times[(int) Math.ceil(samples * .95) - 1],
                    bytes / (double) samples / 1048576, changed, precipitated, settled, woken, coupled);
            if (scene.equals("thermochem")) require(measuredChanged > 0 && measuredPrecipitated > 0, "Measured thermochemistry process coverage missing");
            if (replay != null) require(regions[0].domain().exportGranularState().activeParcels().size() > 0, "Active-grain workload exhausted");
            else if (scene.startsWith("granular")) require(settled > 0 && coupled > 0, "Settling/body coverage missing; inspect CSV and extend the trajectory");
        }
    }

    private static FluidDomain granular(FullCapabilityTestFixture.Registries r, boolean elevated) {
        FluidDomain d = new FluidDomain(8, 8, 8, ProductionReplayValidationSupport.productionConfig(), 4096);
        d.sealDomainBoundary();
        FullCapabilityTestFixture.bindEmptyMaterialState(d, r.liquids());
        int material = r.liquids().handle("mmm:water");
        for (int y = 0; y < 4; y++) for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++)
            require(d.tryInsertExactVolume(x, y, z, 1, 0, material).transferred(), "Liquid initialization rejected");
        FullCapabilityTestFixture.bindFull(d, r, 293.15, mmm.thermal.ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
        long svq = SolidVolume.SVQ_PER_CELL / 4;
        double density = r.granular().material(r.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND)).referenceDensity();
        var ledger = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
                BuiltinGranularDefinitions.FINE_SAND, svq, density * (svq / (double) SolidVolume.SVQ_PER_BV), null)));
        for (int z = 2; z < 6; z++) for (int x = 2; x < 6; x++) {
            require(d.tryInsertExactGranular(x, elevated ? 3 : 0, z, ledger, 0, true).inserted(), "Sand injection rejected");
        }
        return d;
    }

    private static FluidDomain largeGranular(FullCapabilityTestFixture.Registries r) {
        var domain = ProductionReplayValidationSupport.createFullyBoundDamBreakDemo(r);
        long svq = SolidVolume.SVQ_PER_CELL / 4;
        double density = r.granular().material(r.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND)).referenceDensity();
        var ledger = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(BuiltinGranularDefinitions.FINE_SAND,
                svq, density * (svq / (double) SolidVolume.SVQ_PER_BV), null)));
        for (int i = 0; i < 96; i++) require(domain.tryInsertExactGranular(2 + i % 6, 8 + i / 36 % 2,
                3 + i / 6 % 6, ledger, 1, true).inserted(), "Large sand injection rejected");
        return domain;
    }

    private static void feed(FluidRegion region, FullCapabilityTestFixture.Registries r, int step, Counters count) {
        if (region.domain().hasPendingPhaseVolumeWork()) {
            var settled = CPU.evaluate(FluidStepRequest.pendingPhaseSettlement(region.solverGeneration(), region.domain().captureAuthoritativeState()));
            region.adoptStepCandidate(settled, CPU.id());
            count.add(settled);
            count.motion--; // Pending-volume settlement does not advance a motion step.
        }
        var amounts = Map.of(BuiltinChemicalDefinitions.H_PLUS, 1_000_000L,
                BuiltinChemicalDefinitions.OH_MINUS, 1_000_000L,
                BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L, BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L);
        double volume = .125, mass = volume * .125 * 1000;
        double speciesMass = ChemicalMixtureRecord.fromStableAmounts(r.chemical(), amounts, 0).exactSpeciesMassKilograms(r.chemical()).toDouble();
        var ledger = new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water", false,
                volume, mass, Double.doubleToRawLongBits(mass - speciesMass), amounts)));
        require(region.domain().tryInsertExactChemical(2 + step % 4, 4, 2 + step / 4 % 4, ledger, 2, true).transferred(), "Reactant feed rejected");
    }

    private static List<MovingBodyFluidCoupling.Request> body(FluidDomain domain, double speed) {
        // A bed-adjacent marker guarantees that the requested compact kernel touches actual liquid.
        var positions = domain.exportMaterialParticleState().copyPositionVolume();
        double targetX = 2, targetY = .75, targetZ = 2;
        var deposits = domain.exportGranularState().depositGroups();
        if (!deposits.isEmpty()) {
            int cell = deposits.stream().max(Comparator.comparingLong(mmm.granular.GranularStateSnapshot.DepositGroup::solidVolumeSvq)).orElseThrow().cell();
            targetX = (cell % domain.sizeX() + .5) * domain.config().cellSize();
            targetY = (cell / domain.sizeX() % domain.sizeY() + 1.5) * domain.config().cellSize();
            targetZ = (cell / domain.sizeX() / domain.sizeY() + .5) * domain.config().cellSize();
        }
        int nearest = 0; double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < positions.length; i += 4) {
            double score = Math.pow(positions[i] - targetX, 2) + Math.pow(positions[i + 1] - targetY, 2) + Math.pow(positions[i + 2] - targetZ, 2);
            if (score < best) { best = score; nearest = i; }
        }
        require(positions.length > 0, "No liquid for disturbance");
        return List.of(new MovingBodyFluidCoupling.Request(positions[nearest], positions[nearest + 1], positions[nearest + 2], speed, 0, 0, .75));
    }

    private static FluidStepCandidate motion(FluidRegion region, List<MovingBodyFluidCoupling.Request> requests) {
        var candidate = CPU.evaluate(new FluidStepRequest(region.solverGeneration(), region.domain().captureAuthoritativeState(), requests, true, PHASE_BUDGET));
        region.adoptStepCandidate(candidate, CPU.id());
        return candidate;
    }

    private static FluidRegion region(FluidDomain d) {
        return new FluidRegion(UUID.randomUUID(), ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("mmm", "benchmark")), BlockPos.ZERO, d, d.maximumParticleCount());
    }
    private static long allocated() { return ALLOC.getThreadAllocatedBytes(THREAD); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static final class Counters {
        int motion, thermal, idle, substeps, pcg, changed, precipitated, settled, woken, coupled;
        double impulse;
        void add(FluidStepCandidate candidate) {
            motion++;
            var stats = candidate.stats(); substeps += stats.substeps(); pcg += stats.pressureIterations();
            changed += stats.chemistryChangedMarkers(); precipitated += stats.thermalAxis().granularPhase().precipitations();
            settled += stats.granularTransition().settledParcels(); woken += stats.granularTransition().wokenParcels();
            var body = candidate.movingBodyCoupling(); coupled += body.coupledMarkers();
            impulse += Math.sqrt(Math.pow(body.impulseXKilogramMetresPerSecond(), 2)
                    + Math.pow(body.impulseYKilogramMetresPerSecond(), 2) + Math.pow(body.impulseZKilogramMetresPerSecond(), 2));
        }
    }
}
