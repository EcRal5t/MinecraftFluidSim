package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.Map;
import java.util.List;

/** Pure thermal state function: total internal energy is authoritative and temperature is an inversion result. */
public final class ThermalStateFunction {
	public enum PhaseState { LOWER, PLATEAU, UPPER }
	public record State(double temperatureK, double heatCapacityEuPerK) { }
	/** A prospective inventory is finite and well-formed but its energy implies no positive-temperature state. */
	public static final class EnergyOutsideValidityException extends IllegalArgumentException {
		public EnergyOutsideValidityException() {
			super("Thermal LTE energy is outside the positive-temperature domain");
		}
	}
	public record InventoryPortion(int materialHandle, double massMu, Map<String, Long> speciesQuanta) {
		public InventoryPortion {
			if (!(Double.isFinite(massMu) && massMu > 0.0) || speciesQuanta == null) {
				throw new IllegalArgumentException("Thermal inventory portion is invalid");
			}
			speciesQuanta = Map.copyOf(speciesQuanta);
		}
	}
	public record EquilibriumState(double temperatureK, PhaseState phaseState, double upperPhaseFraction,
	                               double lowerBoundaryEnergy, double upperBoundaryEnergy) { }

	private final ThermalRegistry registry;

	public ThermalStateFunction(ThermalRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Thermal registry is missing");
		this.registry = registry;
	}

	public double internalEnergyAtTemperature(
			int materialHandle, double massMu, Map<String, Long> speciesQuanta, double temperatureK
	) {
		ThermalMaterialDefinition material = registry.material(materialHandle);
		double energy = ThermalCaloricInversion.energyAt(material, massMu, baseEnergy(materialHandle, massMu, speciesQuanta), temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal internal energy is not finite");
		return energy;
	}

	public double internalEnergyAtTemperature(
			int materialHandle, double massMu, ChemicalMixtureRecord mixture,
			ChemicalRegistry chemicalRegistry, double temperatureK
	) {
		ThermalMaterialDefinition material = registry.material(materialHandle);
		double energy = ThermalCaloricInversion.energyAt(material, massMu, baseEnergy(materialHandle, massMu, mixture, chemicalRegistry), temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal internal energy is not finite");
		return energy;
	}

	public State stateForInternalEnergy(
			int materialHandle, double massMu, Map<String, Long> speciesQuanta, double internalEnergy
	) {
		requireMassEnergy(massMu, internalEnergy);
		return ThermalCaloricInversion.owner(registry.material(materialHandle), massMu, internalEnergy,
				baseEnergy(materialHandle, massMu, speciesQuanta));
	}

	/** Closed-form common-temperature inversion for a heterogeneous LTE inventory. */
	public State stateForInventory(List<InventoryPortion> portions, double internalEnergy) {
		if (!Double.isFinite(internalEnergy)) throw new IllegalArgumentException("Thermal LTE energy is non-finite");
		return stateForInventory(portions, SignedDyadic.of(internalEnergy));
	}

	/** Retains an aggregate's exact energy until its fixed reference terms have been removed. */
	public State stateForInventory(List<InventoryPortion> portions, SignedDyadic totalEnergy) {
		if (portions == null || portions.isEmpty() || totalEnergy == null)
			throw new IllegalArgumentException("Thermal LTE inventory is missing or non-finite");
		SignedDyadic reference = SignedDyadic.ZERO;
		for (InventoryPortion portion : portions) reference = reference.add(SignedDyadic.of(
				baseEnergy(portion.materialHandle(), portion.massMu(), portion.speciesQuanta())));
		return ThermalCaloricInversion.solve(new ThermalCaloricInversion.Inventory() {
			public int first() { return 0; } public int next(int item) { return item + 1 < portions.size() ? item + 1 : -1; }
			public ThermalMaterialDefinition material(int item) { return registry.material(portions.get(item).materialHandle()); }
			public double mass(int item) { return portions.get(item).massMu(); }
		}, totalEnergy.subtract(reference));
	}

	public EquilibriumState equilibriumState(
			int transitionHandle, double massMu, Map<String, Long> speciesQuanta,
			double internalEnergy, double absolutePressurePa
	) {
		requireMassEnergy(massMu, internalEnergy);
		ThermalPhaseTransition transition = registry.transition(transitionHandle);
		int lowerHandle = registry.materialHandle(transition.lowerEnergyMaterialId());
		int upperHandle = registry.materialHandle(transition.upperEnergyMaterialId());
		double equilibrium = transition.equilibriumTemperatureK(absolutePressurePa);
		double lowerBoundary = internalEnergyAtTemperature(lowerHandle, massMu, speciesQuanta,
				equilibrium);
		double upperBoundary = internalEnergyAtTemperature(upperHandle, massMu, speciesQuanta,
				equilibrium);
		if (!(upperBoundary > lowerBoundary)) throw new IllegalStateException("Thermal transition energy gap is not positive");
		if (internalEnergy < lowerBoundary) {
			State state = stateForInternalEnergy(lowerHandle, massMu, speciesQuanta, internalEnergy);
			return new EquilibriumState(state.temperatureK(), PhaseState.LOWER, 0.0, lowerBoundary, upperBoundary);
		}
		if (internalEnergy > upperBoundary) {
			State state = stateForInternalEnergy(upperHandle, massMu, speciesQuanta, internalEnergy);
			return new EquilibriumState(state.temperatureK(), PhaseState.UPPER, 1.0, lowerBoundary, upperBoundary);
		}
		double fraction = (internalEnergy - lowerBoundary) / (upperBoundary - lowerBoundary);
		if (!Double.isFinite(fraction)) throw new IllegalArgumentException("Thermal phase fraction is not finite");
		return new EquilibriumState(equilibrium, PhaseState.PLATEAU,
				Math.max(0.0, Math.min(1.0, fraction)), lowerBoundary, upperBoundary);
	}

	public double baseEnergy(int materialHandle, double massMu, Map<String, Long> speciesQuanta) {
		if (!(Double.isFinite(massMu) && massMu > 0.0)) {
			throw new IllegalArgumentException("Thermal carrier mass must be finite and positive");
		}
		ThermalMaterialDefinition material = registry.material(materialHandle);
		double total = massMu * material.referenceSpecificEnergyEuPerMu();
		if (speciesQuanta != null && !speciesQuanta.isEmpty()) {
			for (Map.Entry<String, Long> entry : speciesQuanta.entrySet()) {
				if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0L) {
					throw new IllegalArgumentException("Thermal species inventory is invalid");
				}
			}
			for (Map.Entry<String, Long> entry : new java.util.TreeMap<>(speciesQuanta).entrySet()) {
				long quanta = entry.getValue();
				if (quanta == 0L) continue;
				ThermalSpeciesOffset offset = registry.speciesOffset(materialHandle, entry.getKey());
				total += referenceEnergy(offset, quanta);
			}
		}
		if (!Double.isFinite(total)) throw new IllegalArgumentException("Thermal base energy is not finite");
		return total;
	}

	public double baseEnergy(
			int materialHandle, double massMu, ChemicalMixtureRecord mixture, ChemicalRegistry chemicalRegistry
	) {
		if (!(Double.isFinite(massMu) && massMu > 0.0)) {
			throw new IllegalArgumentException("Thermal carrier mass must be finite and positive");
		}
		ThermalMaterialDefinition material = registry.material(materialHandle);
		double total = massMu * material.referenceSpecificEnergyEuPerMu();
		if (mixture != null) {
			if (chemicalRegistry == null) throw new IllegalArgumentException("Thermal chemical registry is missing");
			ThermalSpeciesOffset[] offsets = offsets(chemicalRegistry)[materialHandle];
			for (int entry = 0; entry < mixture.entryCount(); entry++) {
				long quanta = mixture.amountAt(entry);
				ThermalSpeciesOffset offset = requireOffset(offsets, mixture.speciesHandle(entry));
				total += referenceEnergy(offset, quanta);
			}
		}
		if (!Double.isFinite(total)) throw new IllegalArgumentException("Thermal base energy is not finite");
		return total;
	}

	private double referenceEnergy(ThermalSpeciesOffset offset, long quanta) {
		if (offset.referenceInternalEnergyEuPerAu().isZero()) return 0;
		return referenceCache().energy(offset, quanta);
	}

	/** Absolute binary64 error from reference-term conversion and ordered additions. */
	public double baseEnergyRoundoff(int materialHandle, double massMu, ChemicalMixtureRecord mixture, ChemicalRegistry chemistry) {
		double total = massMu * registry.material(materialHandle).referenceSpecificEnergyEuPerMu();
		double error = Math.ulp(total);
		ThermalSpeciesOffset[] offsets = mixture == null ? null : offsets(chemistry)[materialHandle];
		if (mixture != null) for (int entry = 0; entry < mixture.entryCount(); entry++) {
			var offset = requireOffset(offsets, mixture.speciesHandle(entry));
			double term = referenceEnergy(offset, mixture.amountAt(entry));
			total += term;
			// Rational numerator, denominator and quotient each have one final conversion.
			error += 3 * Math.ulp(term) + Math.ulp(total);
		}
		return error;
	}

	private ThermalSpeciesOffset[][] offsets(ChemicalRegistry chemistry) {
		return registry.speciesOffsetTable(chemistry);
	}
	private static ThermalSpeciesOffset requireOffset(ThermalSpeciesOffset[] offsets, int species) {
		if (species < 0 || species >= offsets.length || offsets[species] == null)
			throw new IllegalArgumentException("Missing thermal species offset for chemical handle " + species);
		return offsets[species];
	}

	private ThermalReferenceEnergyCache referenceCache() {
		return registry.referenceEnergyCache();
	}

	private static void requireMassEnergy(double massMu, double internalEnergy) {
		if (!(Double.isFinite(massMu) && massMu > 0.0)) {
			throw new IllegalArgumentException("Thermal carrier mass must be finite and positive");
		}
		if (!Double.isFinite(internalEnergy)) throw new IllegalArgumentException("Thermal internal energy must be finite");
	}

	/** Normalizes a chemical mixture into the stable species-amount map used by inventory portions. */
	public static java.util.Map<String, Long> stableAmounts(
			ChemicalMixtureRecord mixture, ChemicalRegistry chemicalRegistry
	) {
		if (mixture == null) return Map.of();
		if (chemicalRegistry == null) throw new IllegalArgumentException("Thermal chemical registry is missing");
		java.util.TreeMap<String, Long> result = new java.util.TreeMap<>();
		for (int entry = 0; entry < mixture.entryCount(); entry++) result.put(
				chemicalRegistry.species(mixture.speciesHandle(entry)).id(), mixture.amountAt(entry));
		return Map.copyOf(result);
	}
}
