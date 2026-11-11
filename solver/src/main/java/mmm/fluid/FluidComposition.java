package mmm.fluid;

import java.util.List;

/** Committed component quantities projected from authoritative inventory columns. */
public record FluidComposition(List<Row> rows) {
    public FluidComposition { rows = List.copyOf(rows); }
    public enum Phase { LIQUID, GAS, GRANULAR, COHERENT }
    public record Row(String id, Phase phase, double massKilograms, double volumeCubicMetres,
                      double minimumTemperatureK, double maximumTemperatureK, boolean residualMass,
                      double amountMoles, boolean ion, double medianTemperatureK) { }
}
