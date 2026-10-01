package cc.ecisr.mmm.thermal;

/** Conservative backward-Euler heat transfer through one finite conductance, in EU/(s K). */
public final class ThermalContactExchange {
	/** A caloric curve evaluated against the same initial inventory throughout one contact solve. */
	public interface Body {
		double temperatureK();
		double heatCapacityEuPerK();
		double energyChangeToTemperature(double temperatureK);
		ThermalStateFunction.State stateAfterEnergyChange(double energyChangeEu);
		default boolean finite() { return true; }
	}

	/** One real carrier, including its unchanged material and species reference energy. */
	public record Carrier(ThermalMaterialDefinition material, double massKilograms,
	                      double baseEnergyEu, double internalEnergyEu) implements Body {
		public Carrier {
			if (material == null || !(Double.isFinite(massKilograms) && massKilograms > 0.0)
					|| !Double.isFinite(baseEnergyEu) || !Double.isFinite(internalEnergyEu))
				throw new IllegalArgumentException("Thermal contact carrier inventory is invalid");
			double t = material.temperatureForSensibleSpecificEnergy((internalEnergyEu - baseEnergyEu) / massKilograms);
			requireCapacity(massKilograms * material.specificHeatAt(t));
		}
		@Override public double temperatureK() {
			return material.temperatureForSensibleSpecificEnergy((internalEnergyEu - baseEnergyEu) / massKilograms);
		}
		@Override public double heatCapacityEuPerK() { return massKilograms * material.specificHeatAt(temperatureK()); }
		@Override public double energyChangeToTemperature(double target) {
			return massKilograms * material.sensibleSpecificEnergyBetween(temperatureK(), target);
		}
		@Override public ThermalStateFunction.State stateAfterEnergyChange(double change) {
			double temperature = material.temperatureForSensibleSpecificEnergy(
					(internalEnergyEu + change - baseEnergyEu) / massKilograms);
			double capacity = massKilograms * material.specificHeatAt(temperature);
			requireCapacity(capacity);
			return new ThermalStateFunction.State(temperature, capacity);
		}
	}

	/** External fixed-temperature surface; the caller accounts for its exchanged energy. */
	public record Reservoir(double temperatureK) implements Body {
		public Reservoir {
			if (!(Double.isFinite(temperatureK) && temperatureK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K))
				throw new IllegalArgumentException("Thermal contact reservoir temperature is invalid");
		}
		@Override public double heatCapacityEuPerK() { return Double.POSITIVE_INFINITY; }
		@Override public double energyChangeToTemperature(double target) { throw new UnsupportedOperationException(); }
		@Override public ThermalStateFunction.State stateAfterEnergyChange(double change) {
			return new ThermalStateFunction.State(temperatureK, Double.POSITIVE_INFINITY);
		}
		@Override public boolean finite() { return false; }
	}

	private ThermalContactExchange() { }

	/** Returns signed energy entering first; finite second receives the opposite amount. No inventory is edited. */
	public static double transfer(Body first, Body second, double conductanceEuPerSecondK, double dtSeconds) {
		if (first == null || second == null || !first.finite()
				|| !(Double.isFinite(conductanceEuPerSecondK) && conductanceEuPerSecondK >= 0.0)
				|| !(Double.isFinite(dtSeconds) && dtSeconds > 0.0))
			throw new IllegalArgumentException("Thermal contact input is invalid");
		double firstT = first.temperatureK(), secondT = second.temperatureK();
		requireCapacity(first.heatCapacityEuPerK());
		if (second.finite()) requireCapacity(second.heatCapacityEuPerK());
		if (conductanceEuPerSecondK == 0.0 || firstT == secondT) return 0.0;
		double sign = secondT > firstT ? 1.0 : -1.0;
		double high = Math.abs(first.energyChangeToTemperature(secondT));
		if (second.finite()) high = Math.min(high, Math.abs(second.energyChangeToTemperature(firstT)));
		if (!(Double.isFinite(high) && high > 0.0))
			throw new IllegalArgumentException("Thermal contact energy interval is invalid");
		double inverseConductanceDt = (1.0 / conductanceEuPerSecondK) / dtSeconds;
		// Change division order when a reciprocal overflow hides a representable conductance-times-dt.
		if (Double.isInfinite(inverseConductanceDt)) inverseConductanceDt = (1.0 / dtSeconds) / conductanceEuPerSecondK;
		double slope = 1.0 / first.heatCapacityEuPerK() + 1.0 / second.heatCapacityEuPerK();
		double low = 0.0;
		double amount = Math.min(high, Math.abs(secondT - firstT) / (inverseConductanceDt + slope));
		if (amount == 0.0) return 0.0;
		while (true) {
			var firstState = first.stateAfterEnergyChange(sign * amount);
			var secondState = second.stateAfterEnergyChange(-sign * amount);
			double residual = amount * inverseConductanceDt
					- sign * (secondState.temperatureK() - firstState.temperatureK());
			double precision = 16.0 * Math.max(Math.ulp(secondState.temperatureK()), Math.ulp(firstState.temperatureK()));
			if (Math.abs(residual) <= precision || Math.nextUp(low) >= high) break;
			if (residual > 0.0) high = amount; else low = amount;
			slope = 1.0 / firstState.heatCapacityEuPerK() + 1.0 / secondState.heatCapacityEuPerK();
			double next = amount - residual / (inverseConductanceDt + slope);
			if (!(next > low && next < high)) next = low + (high - low) * 0.5;
			if (next == amount) break;
			amount = next;
		}
		return sign * amount;
	}

	private static void requireCapacity(double capacity) {
		if (!(Double.isFinite(capacity) && capacity > 0.0))
			throw new IllegalArgumentException("Thermal contact heat capacity is invalid");
	}
}
