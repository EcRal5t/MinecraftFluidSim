package mmm.thermal;

import mmm.chemistry.ExactRational;
import mmm.granular.SolidVolume;

import java.util.ArrayList;
import java.util.List;

/** Stable, exactly batched equilibrium transition between two thermal material states. */
public record ThermalPhaseTransition(
		String id,
		String lowerEnergyMaterialId,
		String upperEnergyMaterialId,
		long lowerVolumeSvqPerBatch,
		long upperVolumeSvqPerBatch,
		ExactRational massKgPerBatch,
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
			ExactRational massKgPerBatch, InterfaceRequirement interfaceRequirement,
			double minimumPressurePa, double maximumPressurePa
	) {
		this(id, lowerEnergyMaterialId, upperEnergyMaterialId,
				lowerVolumeSvqPerBatch, upperVolumeSvqPerBatch, massKgPerBatch, interfaceRequirement, List.of(
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
		if (lowerVolumeSvqPerBatch <= 0L || lowerVolumeSvqPerBatch > SolidVolume.SVQ_PER_CUBIC_METER
				|| upperVolumeSvqPerBatch <= 0L || upperVolumeSvqPerBatch > SolidVolume.SVQ_PER_CUBIC_METER) {
			throw new IllegalArgumentException("Thermal transition volume batch is invalid");
		}
		if (massKgPerBatch == null || massKgPerBatch.signum() <= 0) {
			throw new IllegalArgumentException("Thermal transition mass batch must be positive");
		}
		mmm.units.Mass.exactUnits(massKgPerBatch.numerator(), massKgPerBatch.denominator());
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

	/** Extrema of the admitted pressure curve; condensed endpoint segments continue over P >= 0. */
	public double minimumEquilibriumTemperatureK() { return equilibriumTemperatureBound(true); }
	public double maximumEquilibriumTemperatureK() { return equilibriumTemperatureBound(false); }
	private double equilibriumTemperatureBound(boolean minimum) {
		double bound=minimum?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY;
		for(var point:equilibriumCurve)
			bound=minimum?Math.min(bound,point.temperatureK()):Math.max(bound,point.temperatureK());
		if(interfaceRequirement==InterfaceRequirement.NONE) {
			var first=equilibriumCurve.getFirst();var second=equilibriumCurve.get(1);
			double firstSlope=(second.temperatureK()-first.temperatureK())/(second.pressurePa()-first.pressurePa());
			double atZero=Math.fma(-first.pressurePa(),firstSlope,first.temperatureK());
			bound=minimum?Math.min(bound,atZero):Math.max(bound,atZero);
			var last=equilibriumCurve.getLast();var previous=equilibriumCurve.get(equilibriumCurve.size()-2);
			double lastSlope=(last.temperatureK()-previous.temperatureK())/(last.pressurePa()-previous.pressurePa());
			if(minimum&&lastSlope<0)return Double.NEGATIVE_INFINITY;
			if(!minimum&&lastSlope>0)return Double.POSITIVE_INFINITY;
		}
		return bound;
	}

	/** Piecewise linear phase curve with endpoint continuation for condensed phases. */
	public double equilibriumTemperatureK(double absolutePressurePa) {
		if (!(Double.isFinite(absolutePressurePa) && absolutePressurePa >= 0.0)
				|| interfaceRequirement == InterfaceRequirement.LIQUID_GAS
				&& (absolutePressurePa < minimumPressurePa() || absolutePressurePa > maximumPressurePa())) {
			throw new IllegalArgumentException("Pressure is outside the thermal equilibrium curve");
		}
		for (int index = 1; index < equilibriumCurve.size(); index++) {
			EquilibriumPoint upper = equilibriumCurve.get(index);
			if (absolutePressurePa > upper.pressurePa() && index + 1 < equilibriumCurve.size()) continue;
			EquilibriumPoint lower = equilibriumCurve.get(index - 1);
			double slope = (upper.temperatureK() - lower.temperatureK())
					/ (upper.pressurePa() - lower.pressurePa());
			double temperature = Math.fma(absolutePressurePa - lower.pressurePa(), slope, lower.temperatureK());
			if (!(Double.isFinite(temperature) && temperature >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)) {
				throw new IllegalArgumentException("Continued phase curve has no valid absolute temperature");
			}
			return temperature;
		}
		return equilibriumCurve.getLast().temperatureK();
	}
}
