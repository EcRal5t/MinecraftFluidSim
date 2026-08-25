package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.granular.SolidVolume;

import java.util.ArrayList;
import java.util.List;

/** Stable, exactly batched equilibrium transition between two thermal material states. */
public record ThermalPhaseTransition(
		String id,
		String lowerEnergyMaterialId,
		String upperEnergyMaterialId,
		long lowerVolumeSvqPerBatch,
		long upperVolumeSvqPerBatch,
		ExactRational massMuPerBatch,
		InterfaceRequirement interfaceRequirement,
		List<EquilibriumPoint> equilibriumCurve
) {
	public enum InterfaceRequirement { NONE, LIQUID_GAS }
	public record EquilibriumPoint(double pressurePa, double temperatureK) {
		public EquilibriumPoint {
			if (!(Double.isFinite(pressurePa) && pressurePa >= 0.0)
					|| !(Double.isFinite(temperatureK)
					&& temperatureK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)) {
				throw new IllegalArgumentException("Thermal equilibrium curve point is invalid");
			}
		}
	}

	public ThermalPhaseTransition(
			String id, String lowerEnergyMaterialId, String upperEnergyMaterialId,
			double equilibriumTemperatureK, long lowerVolumeSvqPerBatch, long upperVolumeSvqPerBatch,
			ExactRational massMuPerBatch, InterfaceRequirement interfaceRequirement,
			double minimumPressurePa, double maximumPressurePa
	) {
		this(id, lowerEnergyMaterialId, upperEnergyMaterialId,
				lowerVolumeSvqPerBatch, upperVolumeSvqPerBatch, massMuPerBatch, interfaceRequirement, List.of(
						new EquilibriumPoint(minimumPressurePa, equilibriumTemperatureK),
						new EquilibriumPoint(maximumPressurePa, equilibriumTemperatureK)));
	}

	public ThermalPhaseTransition {
		ThermalIds.requireId(id, "thermal phase transition id");
		ThermalIds.requireId(lowerEnergyMaterialId, "lower thermal material id");
		ThermalIds.requireId(upperEnergyMaterialId, "upper thermal material id");
		if (lowerEnergyMaterialId.equals(upperEnergyMaterialId)) {
			throw new IllegalArgumentException("Thermal transition requires two different materials");
		}
		if (lowerVolumeSvqPerBatch <= 0L || lowerVolumeSvqPerBatch > SolidVolume.SVQ_PER_BV
				|| upperVolumeSvqPerBatch <= 0L || upperVolumeSvqPerBatch > SolidVolume.SVQ_PER_BV) {
			throw new IllegalArgumentException("Thermal transition volume batch is invalid");
		}
		if (massMuPerBatch == null || massMuPerBatch.signum() <= 0) {
			throw new IllegalArgumentException("Thermal transition mass batch must be positive");
		}
		if (interfaceRequirement == null) throw new IllegalArgumentException("Thermal transition interface is missing");
		if (equilibriumCurve == null || equilibriumCurve.size() < 2
				|| equilibriumCurve.size() > ThermalLimits.MAX_EQUILIBRIUM_CURVE_POINTS) {
			throw new IllegalArgumentException("Thermal equilibrium curve size is invalid");
		}
		ArrayList<EquilibriumPoint> canonical = new ArrayList<>(equilibriumCurve);
		double previousPressure = -1.0;
		for (EquilibriumPoint point : canonical) {
			if (point == null || point.pressurePa() <= previousPressure) {
				throw new IllegalArgumentException("Thermal equilibrium curve pressure is not strictly increasing");
			}
			previousPressure = point.pressurePa();
		}
		equilibriumCurve = List.copyOf(canonical);
	}

	public double minimumPressurePa() { return equilibriumCurve.getFirst().pressurePa(); }

	public double maximumPressurePa() { return equilibriumCurve.getLast().pressurePa(); }

	/** Strictly bounded linear interpolation; callers must pause rather than extrapolate. */
	public double equilibriumTemperatureK(double absolutePressurePa) {
		if (!(Double.isFinite(absolutePressurePa)
				&& absolutePressurePa >= minimumPressurePa()
				&& absolutePressurePa <= maximumPressurePa())) {
			throw new IllegalArgumentException("Pressure is outside the thermal equilibrium curve");
		}
		for (int index = 1; index < equilibriumCurve.size(); index++) {
			EquilibriumPoint upper = equilibriumCurve.get(index);
			if (absolutePressurePa > upper.pressurePa()) continue;
			EquilibriumPoint lower = equilibriumCurve.get(index - 1);
			double fraction = (absolutePressurePa - lower.pressurePa())
					/ (upper.pressurePa() - lower.pressurePa());
			return lower.temperatureK() + fraction * (upper.temperatureK() - lower.temperatureK());
		}
		return equilibriumCurve.getLast().temperatureK();
	}
}
