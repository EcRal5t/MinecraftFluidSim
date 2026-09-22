package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;

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
	private volatile ThermalReferenceEnergyCache referenceEnergies;

	public ThermalStateFunction(ThermalRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Thermal registry is missing");
		this.registry = registry;
	}

	public double internalEnergyAtTemperature(
			int materialHandle, double massMu, Map<String, Long> speciesQuanta, double temperatureK
	) {
		ThermalMaterialDefinition material = registry.material(materialHandle);
		double energy = baseEnergy(materialHandle, massMu, speciesQuanta)
				+ massMu * material.sensibleSpecificEnergyAt(temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal internal energy is not finite");
		return energy;
	}

	public double internalEnergyAtTemperature(
			int materialHandle, double massMu, ChemicalMixtureRecord mixture,
			ChemicalRegistry chemicalRegistry, double temperatureK
	) {
		ThermalMaterialDefinition material = registry.material(materialHandle);
		double energy = baseEnergy(materialHandle, massMu, mixture, chemicalRegistry)
				+ massMu * material.sensibleSpecificEnergyAt(temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal internal energy is not finite");
		return energy;
	}

	public State stateForInternalEnergy(
			int materialHandle, double massMu, Map<String, Long> speciesQuanta, double internalEnergy
	) {
		requireMassEnergy(massMu, internalEnergy);
		ThermalMaterialDefinition material = registry.material(materialHandle);
		double sensibleSpecific = (internalEnergy - baseEnergy(materialHandle, massMu, speciesQuanta)) / massMu;
		double temperature = material.temperatureForSensibleSpecificEnergy(sensibleSpecific);
		double heatCapacity = massMu * material.specificHeatAt(temperature);
		if (!(Double.isFinite(heatCapacity) && heatCapacity > 0.0)) {
			throw new IllegalArgumentException("Thermal heat capacity is invalid");
		}
		return new State(temperature, heatCapacity);
	}

	/** Closed-form common-temperature inversion for a heterogeneous LTE inventory. */
	public State stateForInventory(List<InventoryPortion> portions, double internalEnergy) {
		if (portions == null || portions.isEmpty()
				|| portions.size() > ThermalLimits.MAX_THERMAL_CARRIERS_PER_CELL
				|| !Double.isFinite(internalEnergy)) {
			throw new IllegalArgumentException("Thermal LTE inventory is missing, over budget or non-finite");
		}
		double minimum = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
		double minimumEnergy = inventoryEnergy(portions, minimum);
		double tolerance = energyTolerance(internalEnergy, minimumEnergy);
		if (internalEnergy < minimumEnergy - tolerance) {
			throw new EnergyOutsideValidityException();
		}
		if (internalEnergy <= minimumEnergy) return new State(minimum, inventoryHeatCapacity(portions, minimum));

		double cursor = minimum;
		double cursorEnergy = minimumEnergy;
		while (true) {
			double next = Double.POSITIVE_INFINITY;
			for (InventoryPortion portion : portions) {
				for (ThermalMaterialDefinition.CaloricSegment segment
						: registry.material(portion.materialHandle()).caloricSegments()) {
					if (segment.maximumTemperatureK() > cursor && segment.maximumTemperatureK() < next) {
						next = segment.maximumTemperatureK();
					}
				}
			}
			if (next == Double.POSITIVE_INFINITY) {
				double capacity = inventoryHeatCapacity(portions, cursor);
				double temperature = cursor + (internalEnergy - cursorEnergy) / capacity;
				if (!(Double.isFinite(temperature) && temperature >= cursor)) {
					throw new IllegalArgumentException("Thermal LTE state inversion overflowed");
				}
				return new State(temperature, inventoryHeatCapacity(portions, temperature));
			}
			double capacity = inventoryHeatCapacity(portions, cursor + (next - cursor) * 0.5);
			double intervalEnergy = capacity * (next - cursor);
			double nextEnergy = cursorEnergy + intervalEnergy;
			tolerance = energyTolerance(internalEnergy, cursorEnergy, nextEnergy);
			if (internalEnergy <= nextEnergy + tolerance) {
				double temperature = cursor + (internalEnergy - cursorEnergy) / capacity;
				temperature = Math.max(cursor, Math.min(next, temperature));
				return new State(temperature, inventoryHeatCapacity(portions, temperature));
			}
			cursorEnergy = nextEnergy;
			cursor = next;
		}
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
		if (speciesQuanta != null) for (Map.Entry<String, Long> entry : speciesQuanta.entrySet()) {
			String species = entry.getKey();
			Long quanta = entry.getValue();
			if (species == null || quanta == null || quanta < 0L) {
				throw new IllegalArgumentException("Thermal species inventory is invalid");
			}
			if (quanta == 0L) continue;
			ThermalSpeciesOffset offset = registry.speciesOffset(materialHandle, species);
			double contribution = referenceEnergy(offset, quanta);
			total += contribution;
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
			for (int entry = 0; entry < mixture.entryCount(); entry++) {
				long quanta = mixture.amountAt(entry);
				String speciesId = chemicalRegistry.species(mixture.speciesHandle(entry)).id();
				ThermalSpeciesOffset offset = registry.speciesOffset(materialHandle, speciesId);
				total += referenceEnergy(offset, quanta);
			}
		}
		if (!Double.isFinite(total)) throw new IllegalArgumentException("Thermal base energy is not finite");
		return total;
	}

	private double referenceEnergy(ThermalSpeciesOffset offset, long quanta) {
		ThermalReferenceEnergyCache cache = referenceEnergies;
		if (cache == null) referenceEnergies = cache = new ThermalReferenceEnergyCache();
		return cache.energy(offset, quanta);
	}

	private static void requireMassEnergy(double massMu, double internalEnergy) {
		if (!(Double.isFinite(massMu) && massMu > 0.0)) {
			throw new IllegalArgumentException("Thermal carrier mass must be finite and positive");
		}
		if (!Double.isFinite(internalEnergy)) throw new IllegalArgumentException("Thermal internal energy must be finite");
	}

	private double inventoryEnergy(List<InventoryPortion> portions, double temperatureK) {
		double total = 0.0;
		for (InventoryPortion portion : portions) total += internalEnergyAtTemperature(
				portion.materialHandle(), portion.massMu(), portion.speciesQuanta(), temperatureK);
		if (!Double.isFinite(total)) throw new IllegalArgumentException("Thermal LTE energy overflow");
		return total;
	}

	private double inventoryHeatCapacity(List<InventoryPortion> portions, double temperatureK) {
		double total = 0.0;
		for (InventoryPortion portion : portions) total += portion.massMu()
				* registry.material(portion.materialHandle()).specificHeatAt(temperatureK);
		if (!(Double.isFinite(total) && total > 0.0)) {
			throw new IllegalArgumentException("Thermal LTE heat capacity is invalid");
		}
		return total;
	}

	private static double energyTolerance(double... values) {
		double tolerance = 0.0;
		for (double value : values) tolerance = Math.max(tolerance, Math.ulp(value) * 16.0);
		return tolerance;
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
