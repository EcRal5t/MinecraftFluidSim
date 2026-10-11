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
			double from = temperatureK();
			material.specificHeatAt(target); // Validate the endpoint before forming an interval bound.
			double low = Math.min(from, target), high = Math.max(from, target);
			double specific = 0.0, scaled = 0.0;
			boolean rescale = false;
			var segments = material.caloricSegments();
			for (int index = 0; index < segments.size(); index++) {
				var segment = segments.get(index);
				double a = index == 0 ? low : Math.max(low, segment.minimumTemperatureK());
				double b = index + 1 == segments.size() ? high : Math.min(high, segment.maximumTemperatureK());
				if (b <= a) continue;
				double term = (b - a) * segment.specificHeatEuPerMuK();
				rescale |= !Double.isFinite(term) || term < Double.MIN_NORMAL;
				specific += term;
				scaled += product(b - a, segment.specificHeatEuPerMuK(), massKilograms);
			}
			// An infinite bound is usable when the other finite body supplies a smaller interval.
			double amount = !rescale && Double.isFinite(specific) ? massKilograms * specific : scaled;
			return target < from ? -amount : amount;
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
		if (!(high >= 0.0))
			throw new IllegalArgumentException("Thermal contact energy interval is invalid");
		if (high == 0.0) return 0.0;
		high = Math.min(high, Double.MAX_VALUE);
		double inverseConductanceDt = (1.0 / conductanceEuPerSecondK) / dtSeconds;
		// Change division order when a reciprocal overflow hides a representable conductance-times-dt.
		if (Double.isInfinite(inverseConductanceDt)) inverseConductanceDt = (1.0 / dtSeconds) / conductanceEuPerSecondK;
		double low = 0.0;
		double amount = Math.min(high, response(Math.abs(secondT - firstT),
				first.heatCapacityEuPerK(), second.heatCapacityEuPerK(), conductanceEuPerSecondK, dtSeconds));
		if (amount == 0.0) return 0.0;
		while (true) {
			var firstState = first.stateAfterEnergyChange(sign * amount);
			var secondState = second.stateAfterEnergyChange(-sign * amount);
			double drop = amount * inverseConductanceDt;
			if (!Double.isFinite(drop)) drop = Math.scalb(Math.scalb(amount, -exponent(amount))
					/ (Math.scalb(conductanceEuPerSecondK, -exponent(conductanceEuPerSecondK))
					* Math.scalb(dtSeconds, -exponent(dtSeconds))),
					exponent(amount) - exponent(conductanceEuPerSecondK) - exponent(dtSeconds));
			double residual = drop
					- sign * (secondState.temperatureK() - firstState.temperatureK());
			double precision = Math.max(Math.ulp(secondState.temperatureK()), Math.ulp(firstState.temperatureK()));
			if (Math.abs(residual) / 16.0 <= precision || Math.nextUp(low) >= high) break;
			if (amount == Double.MAX_VALUE && residual < 0.0)
				throw new IllegalArgumentException("Thermal contact transfer is not representable");
			if (residual > 0.0) high = amount; else low = amount;
			double next = amount - response(residual, firstState.heatCapacityEuPerK(),
					secondState.heatCapacityEuPerK(), conductanceEuPerSecondK, dtSeconds);
			if (!(next > low && next < high)) next = low + (high - low) * 0.5;
			if (next == amount) break;
			amount = next;
		}
		return sign * amount;
	}

	/** delta / (1/(G dt) + 1/C1 + 1/C2), keeping the shared binary scale outside the sum. */
	private static double response(double delta, double firstCapacity, double secondCapacity, double g, double dt) {
		double inverse = (1.0 / g) / dt;
		if (Double.isInfinite(inverse)) inverse = (1.0 / dt) / g;
		double denominator = inverse + (1.0 / firstCapacity + 1.0 / secondCapacity);
		double result = delta / denominator;
		if (Double.isFinite(denominator) && denominator > 0.0 && Double.isFinite(result) && result != 0.0)
			return result;
		if (delta == 0.0 || !Double.isFinite(delta)) return delta;
		int ge = exponent(g), de = exponent(dt), ce = exponent(firstCapacity);
		int scale = Math.min(ge + de, ce);
		if (Double.isFinite(secondCapacity)) scale = Math.min(scale, exponent(secondCapacity));
		denominator = Math.scalb(1.0 / (Math.scalb(g, -ge) * Math.scalb(dt, -de)), scale - ge - de)
				+ Math.scalb(1.0 / Math.scalb(firstCapacity, -ce), scale - ce);
		if (Double.isFinite(secondCapacity)) {
			ce = exponent(secondCapacity);
			denominator += Math.scalb(1.0 / Math.scalb(secondCapacity, -ce), scale - ce);
		}
		int re = exponent(Math.abs(delta));
		return Math.scalb(Math.scalb(delta, -re) / denominator, re + scale);
	}

	private static double product(double a, double b, double c) {
		double first = a * b, result = first * c;
		if (Double.isFinite(first) && first >= Double.MIN_NORMAL && Double.isFinite(result) && result > 0.0) return result;
		int ae = exponent(a), be = exponent(b), ce = exponent(c);
		return Math.scalb(Math.scalb(a, -ae) * Math.scalb(b, -be) * Math.scalb(c, -ce), ae + be + ce);
	}

	private static int exponent(double value) {
		int exponent = Math.getExponent(value);
		return exponent == Double.MIN_EXPONENT - 1 ? Math.getExponent(Math.scalb(value, 54)) - 54 : exponent;
	}

	private static void requireCapacity(double capacity) {
		if (!(Double.isFinite(capacity) && capacity > 0.0))
			throw new IllegalArgumentException("Thermal contact heat capacity is invalid");
	}
}
