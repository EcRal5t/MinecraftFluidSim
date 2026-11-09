package mmm.thermal;

import mmm.fluid.air.GasAuditLedger;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasPartitionWeights;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasStateSnapshot;
import mmm.fluid.air.SpatialGasState;
import java.util.ArrayList;
import java.util.Collections;

/** Converts a prepared chamber initial condition into finite local inventories once. */
public final class SpatialGasInitialization {
    public record Result(SpatialGasState state, double capturedAmbientEnergy) { }

    private SpatialGasInitialization() { }

    public static Result fromChambers(GasStateSnapshot source, double[] sourceEnergy, double[] dryVolumes,
                                      GasRegistry gas, mmm.chemistry.ChemicalRegistry chemistry, GasThermalModel thermal,
                                      double ambientPressurePa, double ambientTemperatureK) {
        int cells = source.grid().cellCount();
        if (sourceEnergy.length != source.nodes().size() || dryVolumes.length != cells
                || !source.registryFingerprint().equals(gas.fingerprint()))
            throw new IllegalArgumentException("Gas initial-condition dimensions or material identity differ");
        var owners = source.cellNodeOrdinal();
        var members = new ArrayList<ArrayList<Integer>>(source.nodes().size());
        for (int node = 0; node < source.nodes().size(); node++) members.add(new ArrayList<>());
        var inventory = new ArrayList<>(Collections.nCopies(cells, GasInventory.EMPTY));
        double[] energy = new double[cells];
        var captured = GasAuditLedger.EMPTY;
        double capturedEnergy = 0;
        int[] pieces = source.pieceNodeOrdinal();
        for (int cell = 0; cell < cells; cell++) {
            double volume = dryVolumes[cell];
            if (!Double.isFinite(volume) || volume < 0)
                throw new IllegalArgumentException("Invalid initial gas volume at cell " + cell);
            if (volume == 0) continue;
            int owner = owners[cell];
            if (owner < 0) {
                for (int slot = 0; slot < 8; slot++) if (pieces[8*cell+slot] >= 0)
                    throw new IllegalArgumentException("Local gas requires one open gas space per cell: " + cell);
                continue;
            }
            if (owner > 0) members.get(owner-1).add(cell);
            else {
                var amount = thermal.inventoryAtPressure(volume, ambientPressurePa, ambientTemperatureK, gas.ambient());
                inventory.set(cell, amount);
                energy[cell] = thermal.internalEnergyAtTemperature(amount, ambientTemperatureK);
                captured = captured.add(amount);
                capturedEnergy += energy[cell];
            }
        }
        for (int node = 0; node < members.size(); node++) {
            var stock = source.nodes().get(node).inventory();
            thermal.requireInventoryEnergy(stock, sourceEnergy[node]);
            var targets = members.get(node);
            if (targets.isEmpty()) {
                if (!stock.isEmpty()) throw new IllegalArgumentException("Initial gas inventory has no dry volume: " + node);
                continue;
            }
            double[] weights = new double[targets.size()];
            for (int i = 0; i < weights.length; i++) weights[i] = dryVolumes[targets.get(i)];
            var portions = stock.partition(GasPartitionWeights.of(weights),gas,chemistry);
            var energies = thermal.partitionEnergy(stock, sourceEnergy[node], portions);
            for (int i = 0; i < targets.size(); i++) {
                int cell = targets.get(i);
                inventory.set(cell, portions.get(i));
                energy[cell] = energies[i];
            }
        }
        return new Result(new SpatialGasState(source.grid(),
                source.registryFingerprint(), source.chemicalRegistryFingerprint(), inventory, energy,
                source.externalSource().add(captured), source.escapedSink(), source.phaseSource(), source.phaseSink()),
                capturedEnergy);
    }
}
