package mmm.thermal;

import mmm.units.SignedDyadic;

/** Caloric carrier state and energy integrals. */
public final class ThermalContactExchange {
	/** A caloric curve evaluated against the same initial inventory throughout one contact solve. */
	public sealed interface Body permits Carrier,Reservoir {
		double temperatureK();
		double heatCapacityJPerK();
		double energyChangeToTemperature(double temperatureK);
		ThermalStateFunction.State stateAfterEnergyChange(double energyChangeJ);
		default boolean finite() { return true; }
	}

	/** One real carrier, including its unchanged material and species reference energy. */
	public static final class Carrier implements Body {
		private final ThermalMaterialDefinition material;
		private final double massKilograms,baseEnergyJ,internalEnergyJ;
		private final ThermalStateFunction.State initial;
		public Carrier(ThermalMaterialDefinition material,double massKilograms,double baseEnergyJ,double internalEnergyJ) {
			if (material == null || !(Double.isFinite(massKilograms) && massKilograms > 0.0)
					|| !Double.isFinite(baseEnergyJ) || !Double.isFinite(internalEnergyJ))
				throw new IllegalArgumentException("Thermal contact carrier inventory is invalid");
			this.material=material;this.massKilograms=massKilograms;this.baseEnergyJ=baseEnergyJ;this.internalEnergyJ=internalEnergyJ;
			initial=ThermalCaloricInversion.owner(material,massKilograms,internalEnergyJ,baseEnergyJ);
		}
		public ThermalMaterialDefinition material(){return material;}
		public double massKilograms(){return massKilograms;}
		public double baseEnergyJ(){return baseEnergyJ;}
		public double internalEnergyJ(){return internalEnergyJ;}
		@Override public double temperatureK() { return initial.temperatureK(); }
		@Override public double heatCapacityJPerK() { return initial.heatCapacityJPerK(); }
		@Override public double energyChangeToTemperature(double target) {
			return energyChangeBetween(material, massKilograms, temperatureK(), target);
		}
		@Override public ThermalStateFunction.State stateAfterEnergyChange(double change) {
			return ThermalCaloricInversion.owner(material, massKilograms, internalEnergyJ + change, baseEnergyJ);
		}
	}

	/** External fixed-temperature surface; the caller accounts for its exchanged energy. */
	public record Reservoir(double temperatureK) implements Body {
		public Reservoir {
			if (!(Double.isFinite(temperatureK) && temperatureK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K))
				throw new IllegalArgumentException("Thermal contact reservoir temperature is invalid");
		}
		@Override public double heatCapacityJPerK() { return Double.POSITIVE_INFINITY; }
		@Override public double energyChangeToTemperature(double target) { throw new UnsupportedOperationException(); }
		@Override public ThermalStateFunction.State stateAfterEnergyChange(double change) {
			return new ThermalStateFunction.State(temperatureK, Double.POSITIVE_INFINITY);
		}
		@Override public boolean finite() { return false; }
	}

	private ThermalContactExchange() { }

	/** Signed caloric integral; infinite endpoints remain usable as inversion/contact bounds. */
	static double energyChangeBetween(ThermalMaterialDefinition material, double mass, double from, double target) {
		material.specificHeatAt(from); material.specificHeatAt(target);
		double low = Math.min(from, target), high = Math.max(from, target);
		double specific = 0.0, scaled = 0.0; boolean rescale = false;
		var segments = material.caloricSegments();
		for (int index = 0; index < segments.size(); index++) {
			var segment = segments.get(index);
			double a = index == 0 ? low : Math.max(low, segment.minimumTemperatureK());
			double b = index + 1 == segments.size() ? high : Math.min(high, segment.maximumTemperatureK());
			if (b <= a) continue;
			double term = (b - a) * segment.specificHeatJPerKgK();
			rescale |= !Double.isFinite(term) || term < Double.MIN_NORMAL;
			specific += term; scaled += product(b - a, segment.specificHeatJPerKgK(), mass);
		}
		double amount = !rescale && Double.isFinite(specific) ? mass * specific : scaled;
		return target < from ? -amount : amount;
	}

	/** Exact original caloric integral, including the material's continuous endpoint extensions. */
	static SignedDyadic exactEnergyChangeBetween(ThermalMaterialDefinition material, double mass, double from, double target) {
		return exactEnergyChangeBetween(material, SignedDyadic.of(mass), from, target);
	}
	static SignedDyadic exactEnergyChangeBetween(ThermalMaterialDefinition material, SignedDyadic mass, double from, double target) {
		material.specificHeatAt(from); material.specificHeatAt(target);
		double low = Math.min(from, target), high = Math.max(from, target);
		SignedDyadic total = SignedDyadic.ZERO;
		var segments = material.caloricSegments();
		for (int index = 0; index < segments.size(); index++) {
			var segment = segments.get(index);
			double a = index == 0 ? low : Math.max(low, segment.minimumTemperatureK());
			double b = index + 1 == segments.size() ? high : Math.min(high, segment.maximumTemperatureK());
			if (b > a) total = total.add(SignedDyadic.of(b).subtract(SignedDyadic.of(a))
					.multiply(SignedDyadic.of(segment.specificHeatJPerKgK())).multiply(mass));
		}
		return target < from ? SignedDyadic.ZERO.subtract(total) : total;
	}

	/** Shared contact arithmetic; sign is retained while intermediate overflow and subnormal rounding are avoided. */
	static double product(double a, double b, double c) {
		if (a == 0.0 || b == 0.0 || c == 0.0) return 0.0;
		double first = a * b, result = first * c;
		if (Double.isFinite(first) && Math.abs(first) >= Double.MIN_NORMAL
				&& Double.isFinite(result) && result != 0.0) return result;
		int ae = exponent(a), be = exponent(b), ce = exponent(c);
		return Math.scalb(Math.scalb(a, -ae) * Math.scalb(b, -be) * Math.scalb(c, -ce), ae + be + ce);
	}

	static int exponent(double value) {
		int exponent = Math.getExponent(value);
		return exponent == Double.MIN_EXPONENT - 1 ? Math.getExponent(Math.scalb(value, 54)) - 54 : exponent;
	}

}
