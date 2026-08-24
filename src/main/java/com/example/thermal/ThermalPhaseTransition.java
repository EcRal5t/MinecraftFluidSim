package com.example.thermal;

import com.example.chemistry.ExactRational;
import com.example.granular.SolidVolume;

/** Stable, exactly batched equilibrium transition between two thermal material states. */
public record ThermalPhaseTransition(
		String id,
		String lowerEnergyMaterialId,
		String upperEnergyMaterialId,
		double equilibriumTemperatureK,
		long lowerVolumeSvqPerBatch,
		long upperVolumeSvqPerBatch,
		ExactRational massMuPerBatch,
		InterfaceRequirement interfaceRequirement,
		double minimumPressurePa,
		double maximumPressurePa
) {
	public enum InterfaceRequirement { NONE, LIQUID_GAS }

	public ThermalPhaseTransition {
		ThermalIds.requireId(id, "thermal phase transition id");
		ThermalIds.requireId(lowerEnergyMaterialId, "lower thermal material id");
		ThermalIds.requireId(upperEnergyMaterialId, "upper thermal material id");
		if (lowerEnergyMaterialId.equals(upperEnergyMaterialId)) {
			throw new IllegalArgumentException("Thermal transition requires two different materials");
		}
		if (!(Double.isFinite(equilibriumTemperatureK)
				&& equilibriumTemperatureK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)) {
			throw new IllegalArgumentException("Thermal transition temperature is invalid");
		}
		if (lowerVolumeSvqPerBatch <= 0L || lowerVolumeSvqPerBatch > SolidVolume.SVQ_PER_BV
				|| upperVolumeSvqPerBatch <= 0L || upperVolumeSvqPerBatch > SolidVolume.SVQ_PER_BV) {
			throw new IllegalArgumentException("Thermal transition volume batch is invalid");
		}
		if (massMuPerBatch == null || massMuPerBatch.signum() <= 0) {
			throw new IllegalArgumentException("Thermal transition mass batch must be positive");
		}
		if (interfaceRequirement == null) throw new IllegalArgumentException("Thermal transition interface is missing");
		if (!(Double.isFinite(minimumPressurePa) && Double.isFinite(maximumPressurePa)
				&& minimumPressurePa >= 0.0 && maximumPressurePa > minimumPressurePa)) {
			throw new IllegalArgumentException("Thermal transition pressure range is invalid");
		}
	}
}
