package com.example.thermal;

import com.example.chemistry.ChemicalLimits;

import java.util.Map;

/** Pure M9 state function: total internal energy is authoritative and temperature is an inversion result. */
public final class ThermalStateFunction {
	public enum PhaseState { LOWER, PLATEAU, UPPER }
	public record State(double temperatureK, double heatCapacityEuPerK) { }
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
		double energy = baseEnergy(materialHandle, massMu, speciesQuanta)
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

	public EquilibriumState equilibriumState(
			int transitionHandle, double massMu, Map<String, Long> speciesQuanta, double internalEnergy
	) {
		requireMassEnergy(massMu, internalEnergy);
		ThermalPhaseTransition transition = registry.transition(transitionHandle);
		int lowerHandle = registry.materialHandle(transition.lowerEnergyMaterialId());
		int upperHandle = registry.materialHandle(transition.upperEnergyMaterialId());
		double lowerBoundary = internalEnergyAtTemperature(lowerHandle, massMu, speciesQuanta,
				transition.equilibriumTemperatureK());
		double upperBoundary = internalEnergyAtTemperature(upperHandle, massMu, speciesQuanta,
				transition.equilibriumTemperatureK());
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
		return new EquilibriumState(transition.equilibriumTemperatureK(), PhaseState.PLATEAU,
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
			double contribution = offset.referenceInternalEnergyEuPerAu()
					.multiply(quanta).divide(ChemicalLimits.QUANTA_PER_AU).toDouble();
			total += contribution;
		}
		if (!Double.isFinite(total)) throw new IllegalArgumentException("Thermal base energy is not finite");
		return total;
	}

	private static void requireMassEnergy(double massMu, double internalEnergy) {
		if (!(Double.isFinite(massMu) && massMu > 0.0)) {
			throw new IllegalArgumentException("Thermal carrier mass must be finite and positive");
		}
		if (!Double.isFinite(internalEnergy)) throw new IllegalArgumentException("Thermal internal energy must be finite");
	}
}
