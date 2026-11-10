package mmm.fluid;

import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;
import mmm.spatial.SpatialGrid;
import mmm.units.SignedDyadic;

/** Checks translation of inventories and staggered faces into an enlarged grid. */
public final class SpatialGridRemapValidation {
    public static void main(String[] args) {
        var source = SpatialGrid.rectangular(2, 2, 2);
        var target = SpatialGrid.rectangular(6, 4, 4);
        var map = new SpatialGridRemap(source, target, 2, 1, 1);
        int oldCell = source.cell(1, 1, 1), newCell = target.cell(3, 2, 2);
        if (map.cell(oldCell) != newCell) throw new AssertionError("cell translation");
        var rows = new java.util.ArrayList<List<LiquidCellInventory.Stock>>();
        for (int i = 0; i < source.cellCount(); i++) rows.add(List.of());
        rows.set(oldCell, List.of(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(oldCell, 0, 255),
                SignedDyadic.of(.5), 123, null, 20.0)));
        var moved = map.liquid(new LiquidCellInventory.Snapshot(rows));
        var stock = moved.rows().get(newCell).getFirst();
        if (stock.massUnits() != 123 || stock.internalEnergy() != 20.0 || !stock.volume().equals(SignedDyadic.of(.5)))
            throw new AssertionError("liquid inventory");
        double[] oldMicro = new double[source.cellCount() * 8]; oldMicro[oldCell * 8 + 3] = .0625;
        var occupancy = map.occupancy(new PhaseOccupancyState(oldMicro));
        if (occupancy.liquidVolume(newCell * 8 + 3) != .0625) throw new AssertionError("occupancy");
        double[] x = new double[source.faceCount(0)], y = new double[source.faceCount(1)], z = new double[source.faceCount(2)];
        int oldFace = source.face(oldCell, 1); x[oldFace - source.faceOffset(0)] = 9;
        var momentum = map.momentum(new MacMomentumState(source, x, y, z));
        if (momentum.copyX()[map.face(oldFace) - target.faceOffset(0)] != 9) throw new AssertionError("face momentum");

        var host = new HostFluidState(source, FluidSolverConfig.prototypeDefaults(), TestFluidDefinitions.load());
        host.bindGasRegistry(TestFluidDefinitions.load().gas());
        host.cellGasVolume(0, 0, 0);
        host.setVolume(1, 1, 1, .5f);
        var before = host.captureAuthoritativeState();
        var mergedGas = FluidDomainMerge.gas(List.of(
                new FluidDomainMerge.Source(before, 0, 0, 0, null),
                new FluidDomainMerge.Source(before, 2, 0, 0, null)), target,
                java.util.Collections.nCopies(target.cellCount(), mmm.fluid.air.GasInventory.EMPTY),
                new double[target.cellCount()]);
        for (int cell = 0; cell < source.cellCount(); cell++) {
            int x0 = source.x(cell), y0 = source.y(cell), z0 = source.z(cell);
            for (int offset : new int[] {0, 2}) {
                int mapped = target.cell(x0 + offset, y0, z0);
                if (!mergedGas.cells().get(mapped).equals(before.spatialGas().cells().get(cell))
                        || mergedGas.energyAt(mapped) != before.spatialGas().energyAt(cell))
                    throw new AssertionError("merged spatial gas");
            }
        }
        var merged = FluidDomainMerge.prepare(List.of(new FluidDomainMerge.Source(before, 0, 0, 0, null),
                new FluidDomainMerge.Source(before, 2, 0, 0, null)), target, FixedGeometrySnapshot.fromMasks(target, new byte[target.cellCount()]), mergedGas, null, null);
        var mergedHost = HostFluidState.restoreAuthoritativeState(merged);
        if (mergedHost.totalVolumeExact() != 2 * host.totalVolumeExact()
                || mergedHost.volume(1, 1, 1) != .5f || mergedHost.volume(3, 1, 1) != .5f)
            throw new AssertionError("merged liquid inventory");
        expectRejected(() -> FluidDomainMerge.prepare(List.of(new FluidDomainMerge.Source(before, 0, 0, 0, null),
                        new FluidDomainMerge.Source(before, 0, 0, 0, null)), target, FixedGeometrySnapshot.fromMasks(target, new byte[target.cellCount()]), mergedGas, null, null), "overlapping merged cells");
        expectRejected(() -> FluidDomainMerge.prepare(List.of(new FluidDomainMerge.Source(before, 5, 0, 0, null)),
                target, FixedGeometrySnapshot.fromMasks(target, new byte[target.cellCount()]), mergedGas, null, null), "truncated target");
        var leftBodies = HostFluidState.restoreAuthoritativeState(before);
        var rightBodies = HostFluidState.restoreAuthoritativeState(before);
        var bounds = new mmm.world.MovingBodyFluidCoupling.Bounds(0, 0, 0, .1, .1, .1);
        var requests = new java.util.ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        int halfBudget = mmm.world.MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP / 2 + 1;
        for (int i = 0; i < 2 * halfBudget; i++) requests.add(new mmm.world.MovingBodyFluidCoupling.Request(
                new java.util.UUID(0, i + 1), bounds, 0, 0, 0));
        leftBodies.restoreEntityBodyState(new EntityBodyState(requests.subList(0, halfBudget)));
        rightBodies.restoreEntityBodyState(new EntityBodyState(requests.subList(halfBudget, 2 * halfBudget)));
        var leftState = leftBodies.captureAuthoritativeState(); var rightState = rightBodies.captureAuthoritativeState();
        expectRejected(() -> FluidDomainMerge.prepare(List.of(new FluidDomainMerge.Source(leftState, 0, 0, 0, null),
                        new FluidDomainMerge.Source(rightState, 2, 0, 0, null)), target, FixedGeometrySnapshot.fromMasks(target, new byte[target.cellCount()]), mergedGas, null, null), "merged body budget");
        var sharedId = UUID.randomUUID();
        leftBodies.restoreEntityBodyState(new EntityBodyState(List.of(new mmm.world.MovingBodyFluidCoupling.Request(sharedId,
                new mmm.world.MovingBodyFluidCoupling.Bounds(1, 0, 0, 1.5, .5, .5), 0, 0, 0))));
        rightBodies.restoreEntityBodyState(new EntityBodyState(List.of(new mmm.world.MovingBodyFluidCoupling.Request(sharedId,
                new mmm.world.MovingBodyFluidCoupling.Bounds(-1, 0, 0, -.5, .5, .5), 0, 0, 0))));
        var shared = FluidDomainMerge.prepare(List.of(new FluidDomainMerge.Source(leftBodies.captureAuthoritativeState(), 0, 0, 0, null),
                new FluidDomainMerge.Source(rightBodies.captureAuthoritativeState(), 2, 0, 0, null)), target,
                FixedGeometrySnapshot.fromMasks(target, new byte[target.cellCount()]), mergedGas, null, null);
        if (shared.entityBodyState().bodies().size() != 1) throw new AssertionError("shared body duplicated");
        try {
            FluidDomainMerge.gas(List.of(new FluidDomainMerge.Source(before, 0, 0, 0, null),
                    new FluidDomainMerge.Source(before, 0, 0, 0, null)), target,
                    java.util.Collections.nCopies(target.cellCount(), mmm.fluid.air.GasInventory.EMPTY),
                    new double[target.cellCount()]);
            throw new AssertionError("overlapping source cells accepted");
        } catch (IllegalArgumentException expected) { }
        var gas = map.gas(before.spatialGas(), java.util.Collections.nCopies(target.cellCount(), mmm.fluid.air.GasInventory.EMPTY),
                new double[target.cellCount()]);
        var enlarged = FluidDomainResize.prepare(before, target, 2, 1, 1, FixedGeometrySnapshot.fromMasks(target, new byte[target.cellCount()]), gas, null, null);
        var after = HostFluidState.restoreAuthoritativeState(enlarged);
        if (after.totalVolumeExact() != host.totalVolumeExact() || after.volume(3, 2, 2) != .5f)
            throw new AssertionError("resized state liquid");

        int padding = source.cell(0, 0, 0);
        boolean[] retained = new boolean[source.cellCount()]; java.util.Arrays.fill(retained, true); retained[padding] = false;
        var sparseMap = new SpatialGridRemap(source, target, 2, 1, 1, retained);
        byte[] previousMasks = new byte[source.cellCount()]; previousMasks[padding] = (byte) 255;
        int[] exterior = new int[target.cellCount()];
        exterior[target.cell(0, 0, 0)] = 15;
        var geometry = sparseMap.geometry(FixedGeometrySnapshot.fromMasks(source, previousMasks),
                FixedGeometrySnapshot.fromMasks(target, new byte[target.cellCount()], exterior, List.of()));
        if (geometry.mask(sparseMap.cell(padding)) != 0) throw new AssertionError("former padding must use sampled geometry");
        if (!Arrays.equals(geometry.copyExteriorMasks(), exterior)) throw new AssertionError("resized exterior wall geometry differs");
        fullInventoryMerge(source);
        sparsePadding();
    }

    private static void sparsePadding() {
        var source = SpatialGrid.rectangular(16, 8, 8);
        var target = new SpatialGrid(16, 8, 8, List.of(new SpatialGrid.Block(1, 0, 0)));
        var retained = new boolean[source.cellCount()];
        for (int cell = 0; cell < retained.length; cell++) retained[cell] = source.x(cell) >= 8;
        var map = new SpatialGridRemap(source, target, 0, 0, 0, retained);
        if (map.cell(source.cell(0, 0, 0)) != -1 || map.cell(source.cell(8, 0, 0)) != target.cell(8, 0, 0))
            throw new AssertionError("sparse padding mapping");
        var rows = new java.util.ArrayList<List<LiquidCellInventory.Stock>>();
        for (int cell = 0; cell < source.cellCount(); cell++) rows.add(List.of());
        rows.set(source.cell(0, 0, 0), List.of(new LiquidCellInventory.Stock(
                new LiquidCellInventory.Key(source.cell(0, 0, 0), 0, 255), SignedDyadic.of(.1), 10, null, 0)));
        expectRejected(() -> map.liquid(new LiquidCellInventory.Snapshot(rows)), "occupied omitted padding");
    }

    private static void fullInventoryMerge(SpatialGrid source) {
        var definitions = TestFluidDefinitions.load();
        var defaults = FluidSolverConfig.prototypeDefaults();
        var config = new FluidSolverConfig(.5f, defaults.gravity(), defaults.timeStep(), defaults.maxPressureIterations(),
                defaults.pressureTolerance(), defaults.fluidEpsilon());
        var host = new HostFluidState(source, config, definitions);
        var materials = new java.util.ArrayList<>(definitions.material().entries().stream().map(e -> e.material()).toList());
        materials.sort(java.util.Comparator.comparingInt(m -> m.id().equals("mmm:water") ? 0 : 1));
        var liquids = new FluidMaterialRegistry(materials);
        host.bindMaterialRegistry(liquids);
        host.bindChemistry(definitions.chemistry());
        var registry = definitions.granular();
        int sand = registry.materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND);
        long volume = mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ;
        long mass = mmm.units.Mass.fromKilograms(1.0);
        var grains = new mmm.granular.GranularStateSnapshot(2, 2, 2, registry.fingerprint(),
                registry.materials().stream().map(mmm.granular.ParticulateMaterial::id).toList(),
                List.of(new mmm.granular.GranularStateSnapshot.ActiveParcel(.25f, .25f, .25f, 0, 0, 0,
                        volume, mass, sand, 0, 0, (byte) 0, 0)),
                List.of(new mmm.granular.GranularStateSnapshot.DepositGroup(source.cell(1, 0, 0),
                        registry.material(sand).restPorosityQ(), sand, 0, volume, mass)), 2 * volume, 2 * mass);
        host.restoreGranularState(grains, registry, liquids, definitions.material());
        host.bindGasRegistry(definitions.gas());
        host.bindThermal(definitions.thermal(), liquids, 300);
        host.setVolume(1, 1, 1, .25f);
        var state = host.captureAuthoritativeState();
        var target = SpatialGrid.rectangular(4, 2, 2);
        var inputs = List.of(new FluidDomainMerge.Source(state, 0, 0, 0, null),
                new FluidDomainMerge.Source(state, 2, 0, 0, null));
        var gas = FluidDomainMerge.gas(inputs, target,
                java.util.Collections.nCopies(target.cellCount(), mmm.fluid.air.GasInventory.EMPTY), new double[target.cellCount()]);
        var combined = FluidDomainMerge.prepare(inputs, target, FixedGeometrySnapshot.fromMasks(target, new byte[target.cellCount()]), gas,
                FluidDomainMerge.thermalAudit(inputs), null);
        var restored = HostFluidState.restoreAuthoritativeState(combined);
        if (restored.totalVolumeExact() != 2 * host.totalVolumeExact()
                || combined.granular().totalMassUnits() != 2 * state.granular().totalMassUnits()
                || combined.granular().activeParcels().size() != 2 || combined.granular().depositGroups().size() != 2
                || combined.thermalOwner().copyActiveParcelEnergy().length != 2
                || combined.thermalOwner().copyDepositEnergy().length != 2)
            throw new AssertionError("full inventory merge");
    }

    private static void expectRejected(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label + " accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
