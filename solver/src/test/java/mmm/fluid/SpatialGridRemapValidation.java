package mmm.fluid;

import java.util.List;
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
        var gas = map.gas(before.spatialGas(), java.util.Collections.nCopies(target.cellCount(), mmm.fluid.air.GasInventory.EMPTY),
                new double[target.cellCount()]);
        var enlarged = FluidDomainResize.prepare(before, target, 2, 1, 1, new byte[target.cellCount()],
                List.of(), gas, null, null);
        var after = HostFluidState.restoreAuthoritativeState(enlarged);
        if (after.totalVolumeExact() != host.totalVolumeExact() || after.volume(3, 2, 2) != .5f)
            throw new AssertionError("resized state liquid");

        int padding = source.cell(0, 0, 0);
        boolean[] retained = new boolean[source.cellCount()]; java.util.Arrays.fill(retained, true); retained[padding] = false;
        var sparseMap = new SpatialGridRemap(source, target, 2, 1, 1, retained);
        byte[] previousMasks = new byte[source.cellCount()]; previousMasks[padding] = (byte) 255;
        var geometry = sparseMap.geometry(FixedGeometrySnapshot.fromMasks(source, previousMasks), new byte[target.cellCount()], List.of());
        if (geometry.mask(sparseMap.cell(padding)) != 0) throw new AssertionError("former padding must use sampled geometry");
    }
}
