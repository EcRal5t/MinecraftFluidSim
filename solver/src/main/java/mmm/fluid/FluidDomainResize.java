package mmm.fluid;

import mmm.fluid.air.SpatialGasState;
import mmm.spatial.SpatialGrid;
import mmm.thermal.ThermalAuditLedger;

/** Builds a private enlarged state using caller-prepared collision and gas inventories. */
public final class FluidDomainResize {
    private FluidDomainResize() { }

    public static FluidDomainState prepare(FluidDomainState source, SpatialGrid target,
            int offsetX, int offsetY, int offsetZ, byte[] sampledMasks,
            java.util.List<ScreenFace> sampledScreens, SpatialGasState gas,
            ThermalAuditLedger thermalAudit, double[] sampledBoundaryTemperatures) {
        return prepare(source, target, offsetX, offsetY, offsetZ, null, sampledMasks, sampledScreens, gas,
                thermalAudit, sampledBoundaryTemperatures);
    }

    public static FluidDomainState prepare(FluidDomainState source, SpatialGrid target,
            int offsetX, int offsetY, int offsetZ, boolean[] retainedCells, byte[] sampledMasks,
            java.util.List<ScreenFace> sampledScreens, SpatialGasState gas,
            ThermalAuditLedger thermalAudit, double[] sampledBoundaryTemperatures) {
        var map = new SpatialGridRemap(source.grid(), target, offsetX, offsetY, offsetZ, retainedCells);
        if (source.hasGas()) {
            if (source.spatialGas() == null || gas == null || !target.equals(gas.grid()))
                throw new IllegalArgumentException("Resize requires spatial gas for both grids");
            var previous = source.spatialGas();
            for (int cell = 0; cell < source.grid().cellCount(); cell++) {
                if (retainedCells != null && !retainedCells[cell]) continue;
                int next = map.cell(cell);
                if (!previous.cells().get(cell).equals(gas.cells().get(next))
                        || Double.doubleToLongBits(previous.energyAt(cell)) != Double.doubleToLongBits(gas.energyAt(next)))
                    throw new IllegalArgumentException("Resize changed existing gas inventory");
            }
        } else if (gas != null) throw new IllegalArgumentException("Unexpected spatial gas");
        var granular = map.granular(source.granular(), source.config().cellSize());
        var thermal = map.thermal(source.thermalOwner(), granular,
                gas == null && source.thermalOwner() != null ? source.thermalOwner().copyGasNodeEnergy()
                        : new double[0], thermalAudit);
        double[] previousBoundary = source.copyThermalBoundaryTemperaturesK();
        double[] boundary = previousBoundary == null ? sampledBoundaryTemperatures
                : map.cells(previousBoundary, sampledBoundaryTemperatures, 6);
        int[] dirty = source.copyChemicalDirtyCells();
        if (dirty != null) {
            dirty = java.util.Arrays.stream(dirty).filter(cell -> retainedCells == null || retainedCells[cell])
                    .map(map::cell).toArray();
            java.util.Arrays.sort(dirty);
        }
        return new FluidDomainState(target, source.config(), source.definitions(), source.sealedDomainBoundary(),
                map.liquid(source.liquid()), map.occupancy(source.phaseOccupancy()), map.momentum(source.momentum()),
                map.geometry(source.fixedGeometry(), sampledMasks, sampledScreens), map.bodies(source.entityBodyState(), source.config().cellSize()),
                source.materialPalette(), source.chemicalRegistry(), source.chemicalSource(), source.chemicalSink(), dirty,
                source.chemicalRegistry() == null || retainedCells != null && !retainedCells[source.chemicalReactionCursor()]
                        ? 0 : map.cell(source.chemicalReactionCursor()),
                map.chemicalPending(source.chemicalMassTransferPending()), source.granularRegistry(),
                granular == null ? null : granular.state(), map.granularDynamics(source.granularDynamics()), source.precipitationEnabled(),
                source.gasRegistry(), FluidDomainState.GasContinuation.spatial(gas), source.thermalRegistry(), thermal,
                source.maximumThermalTransportSubsteps(), map.pending(source.pendingPhaseMotion()), boundary,
                map.pressure(source.exportCondensedPhasePressure()), source.materialDefinitions());
    }
}
