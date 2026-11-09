package mmm.fluid.air;

import java.util.List;
import mmm.spatial.SpatialGrid;

/** Immutable local gas inventory and internal energy in the spatial grid's compact order. */
public record SpatialGasState(
        SpatialGrid grid,
        String registryFingerprint, String chemicalRegistryFingerprint,
        List<GasInventory> cells, double[] internalEnergy,
        GasAuditLedger externalSource, GasAuditLedger escapedSink,
        GasAuditLedger phaseSource, GasAuditLedger phaseSink
) {
    public SpatialGasState(int sizeX,int sizeY,int sizeZ,String registryFingerprint,String chemicalRegistryFingerprint,
            List<GasInventory> cells,double[] internalEnergy,GasAuditLedger externalSource,GasAuditLedger escapedSink,
            GasAuditLedger phaseSource,GasAuditLedger phaseSink) {
        this(SpatialGrid.rectangular(sizeX,sizeY,sizeZ),registryFingerprint,chemicalRegistryFingerprint,cells,internalEnergy,
                externalSource,escapedSink,phaseSource,phaseSink);
    }
    public SpatialGasState {
        if (grid == null
                || registryFingerprint == null || !registryFingerprint.matches("[0-9a-f]{64}")
                || chemicalRegistryFingerprint == null
                || !(chemicalRegistryFingerprint.isEmpty() || chemicalRegistryFingerprint.matches("[0-9a-f]{64}"))
                || cells == null || internalEnergy == null
                || cells.size() != grid.cellCount()
                || internalEnergy.length != cells.size()
                || externalSource == null || escapedSink == null || phaseSource == null || phaseSink == null)
            throw new IllegalArgumentException("Invalid spatial gas state");
        cells = List.copyOf(cells);
        internalEnergy = internalEnergy.clone();
        for (int cell = 0; cell < cells.size(); cell++) {
            if (!Double.isFinite(internalEnergy[cell]) || cells.get(cell).isEmpty() && internalEnergy[cell] != 0)
                throw new IllegalArgumentException("Invalid local gas energy at cell " + cell);
        }
    }

    @Override public double[] internalEnergy() { return internalEnergy.clone(); }
    public int sizeX() { return grid.sizeX(); }
    public int sizeY() { return grid.sizeY(); }
    public int sizeZ() { return grid.sizeZ(); }
    public double energyAt(int cell) { return internalEnergy[cell]; }
    public int cellCount() { return cells.size(); }
}
