package mmm.fluid;

import mmm.chemistry.ChemicalRegistry;
import mmm.units.Mass;
import java.util.TreeMap;

/** Single-pass read of committed stocks and carrier stores. */
final class FluidCompositionProjector {
    static FluidComposition observe(HostFluidState domain) {
        return observe(domain, cell -> true);
    }
    static FluidComposition observe(HostFluidState domain, java.util.function.IntPredicate cellFilter) {
        var chemistry = domain.definitions.chemistry();
        var projector = new FluidCompositionProjector(chemistry);
        if (domain.thermalState != null) domain.thermalState.prepareSpatialObservation(domain.domainCapabilities.thermalMarkerContext());
        for (var stock : domain.liquidCells.physicalStocks()) {
            if (!cellFilter.test(stock.key().cell())) continue;
            String material = domain.materialRegistry == null ? BuiltinFluidMaterials.WATER : domain.materialRegistry.material(stock.key().material()).id();
            double temperature = domain.thermalState == null ? Double.NaN
                    : domain.thermalState.spatialTemperatureInCell(stock.key().cell());
            var species = new java.util.ArrayList<FluidCompositionProjector.Species>();
            if (stock.mixture() != null) for (int i = 0; i < stock.mixture().entryCount(); i++)
                species.add(new FluidCompositionProjector.Species(chemistry.species(stock.mixture().speciesHandle(i)).id(), stock.mixture().amountAt(i)));
            projector.portion(material, FluidComposition.Phase.LIQUID, stock.massUnits(),
                    stock.volumeFraction() * domain.unitScale.cubicMetersPerNormalizedVolume(), species, temperature);
        }
        if (domain.granularState != null) {
            for (int i = 0; i < domain.granularState.parcels.count(); i++) {
                var parcel = domain.granularState.parcels.parcel(i);
                int cell = domain.domainInventoryGeometry.granularParcelCell(parcel);
                if (!cellFilter.test(cell)) continue;
                projector.portion(domain.granularState.registry.material(parcel.materialHandle()).id(), FluidComposition.Phase.GRANULAR,
                        parcel.massUnits(), Math.scalb((double) parcel.solidVolumeSvq(), -27), solidSpecies(domain, parcel.solidCompositionHandle(), chemistry),
                        domain.thermalState == null ? Double.NaN : domain.thermalState.spatialTemperatureInCell(cell));
            }
            domain.granularState.deposits.forEachGroup((cell, porosity, material, composition, volume, mass) ->
                    { if (cellFilter.test(cell)) projector.portion(domain.granularState.registry.material(material).id(), FluidComposition.Phase.GRANULAR,
                            mass, Math.scalb((double) volume, -27), solidSpecies(domain, composition, chemistry),
                            domain.thermalState == null ? Double.NaN : domain.thermalState.spatialTemperatureInCell(cell)); });
        }
        if (domain.thermalState != null) for (var group : domain.thermalState.coherentPhases().snapshot()) {
            if (!cellFilter.test(group.cell())) continue;
            var species = group.composition() == null ? java.util.List.<FluidCompositionProjector.Species>of()
                    : group.composition().components().stream().map(value ->
                            new FluidCompositionProjector.Species(value.speciesId(), value.amountQuanta())).toList();
            projector.portion(group.phaseId(), FluidComposition.Phase.COHERENT, group.massUnits(),
                    Math.scalb((double) group.occupiedVolumeSvq(), -27), species, domain.thermalState.spatialTemperatureInCell(group.cell()));
        }
        for (int node : domain.gasBoundary.nodeIds()) {
            if (!cellFilter.test(node - 1)) continue;
            var gas = domain.gasBoundary.inventory(node);
            double temperature = domain.hasThermalState() && !gas.isEmpty() ? domain.gasNodeTemperatureK(node) : Double.NaN;
            for (var component : gas.components()) {
                var species = component.species().stream().map(value ->
                        new FluidCompositionProjector.Species(value.speciesId(), value.amountQuanta())).toList();
                projector.portion(component.componentId(), FluidComposition.Phase.GAS, component.massUnits(),
                        component.standardVolumeNanos() * 1e-9, species, temperature);
            }
        }
        return projector.result();
    }

    private static java.util.List<FluidCompositionProjector.Species> solidSpecies(HostFluidState domain, int handle, mmm.chemistry.ChemicalRegistry chemistry) {
        if (handle == 0) return java.util.List.of();
        var composition = domain.granularState.compositions.record(handle);
        var result = new java.util.ArrayList<FluidCompositionProjector.Species>(composition.entryCount());
        for (int i = 0; i < composition.entryCount(); i++) result.add(new FluidCompositionProjector.Species(
                chemistry.species(composition.speciesHandle(i)).id(), composition.amountAt(i)));
        return result;
    }

    private final TreeMap<String, Accumulator> rows = new TreeMap<>();
    private final ChemicalRegistry chemistry;
    FluidCompositionProjector(ChemicalRegistry chemistry) { this.chemistry = chemistry; }

    void portion(String carrier, FluidComposition.Phase phase, long massUnits, double volume,
                 java.util.List<Species> species, double temperature) {
        if (species.isEmpty() || chemistry == null) {
            add(carrier, phase, Mass.kilograms(massUnits), volume, temperature);
            return;
        }
        long attributed = 0;
        long[] masses = new long[species.size()];
        for (int i = 0; i < species.size(); i++) {
            var value = species.get(i);
            masses[i] = chemistry.speciesMassUnits(chemistry.speciesHandle(value.id()), value.quanta());
            attributed = Math.addExact(attributed, masses[i]);
        }
        long residual = Math.subtractExact(massUnits, attributed);
        if (residual < 0) {
            var weights = new mmm.units.SignedDyadic[masses.length];
            for (int i = 0; i < masses.length; i++) weights[i] = mmm.units.SignedDyadic.of(masses[i], 0);
            masses = mmm.inventory.LiquidCellInventory.allocateQuantities(massUnits, weights);
        }
        double speciesVolume = species.size() == 1 && residual >= -1 && residual <= 1 ? volume : Double.NaN;
        for (int i = 0; i < species.size(); i++)
            add(species.get(i).id(), phase, Mass.kilograms(masses[i]), speciesVolume, temperature, false,
                    species.get(i).quanta() / (double) mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL,
                    chemistry.species(chemistry.speciesHandle(species.get(i).id())).charge() != 0);
        if (residual > 0) add(carrier, phase, Mass.kilograms(residual), Double.NaN, temperature, true);
    }
    record Species(String id, long quanta) { }
    void add(String id, FluidComposition.Phase phase, double mass, double volume, double temperature) {
        add(id, phase, mass, volume, temperature, false, Double.NaN, false);
    }
    private void add(String id, FluidComposition.Phase phase, double mass, double volume, double temperature, boolean residualMass) {
        add(id, phase, mass, volume, temperature, residualMass, Double.NaN, false);
    }
    private void add(String id, FluidComposition.Phase phase, double mass, double volume, double temperature, boolean residualMass, double amountMoles, boolean ion) {
        rows.computeIfAbsent(phase.name() + "\0" + id + "\0" + residualMass,
                key -> new Accumulator(id, phase, residualMass, ion)).add(mass, volume, temperature, amountMoles);
    }
    FluidComposition result() { return new FluidComposition(rows.values().stream().map(Accumulator::row).toList()); }
    /** Temperature samples are weighted by the represented component mass. */
    private static final class Accumulator {
        final String id; final FluidComposition.Phase phase; final boolean residualMass, ion;
        double mass, volume, amount, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        final java.util.List<double[]> temperatures = new java.util.ArrayList<>();
        Accumulator(String id, FluidComposition.Phase phase, boolean residualMass, boolean ion) {
            this.id = id; this.phase = phase; this.residualMass = residualMass; this.ion = ion;
        }
        void add(double m, double v, double t, double mol) {
            mass += m; volume += ion ? Double.NaN : v; amount += mol;
            if (Double.isFinite(t) && m > 0) {
                min = Math.min(min, t); max = Math.max(max, t); temperatures.add(new double[]{t, m});
            }
        }
        double median() {
            if (temperatures.isEmpty()) return Double.NaN;
            temperatures.sort(java.util.Comparator.comparingDouble(a -> a[0]));
            double half = temperatures.stream().mapToDouble(a -> a[1]).sum() / 2, cumulative = 0;
            for (int i = 0; i < temperatures.size(); i++) {
                cumulative += temperatures.get(i)[1];
                if (cumulative > half) return temperatures.get(i)[0];
                if (cumulative == half && i + 1 < temperatures.size())
                    return (temperatures.get(i)[0] + temperatures.get(i + 1)[0]) / 2;
            }
            return temperatures.get(temperatures.size() - 1)[0];
        }
        FluidComposition.Row row() { return new FluidComposition.Row(id, phase, mass, volume,
                min == Double.POSITIVE_INFINITY ? Double.NaN : min,
                max == Double.NEGATIVE_INFINITY ? Double.NaN : max, residualMass, amount, ion, median()); }
    }
}
