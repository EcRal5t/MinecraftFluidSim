package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.units.SignedDyadic;

/** Exact piecewise caloric coordinates shared by real owners and derived common-temperature inventories. */
final class ThermalCaloricInversion {
	interface Inventory {
		int first(); int next(int item);
		ThermalMaterialDefinition material(int item);
		double mass(int item);
		default SignedDyadic exactMass(int item) { return SignedDyadic.of(mass(item)); }
	}
	private record Single(ThermalMaterialDefinition material, double mass) implements Inventory {
		public int first() { return 0; } public int next(int item) { return -1; }
		public ThermalMaterialDefinition material(int item) { return material; }
		public double mass(int item) { return mass; }
	}
	private ThermalCaloricInversion() { }

	static ThermalStateFunction.State owner(ThermalMaterialDefinition material, double mass, double energy, double base) {
		if (!(Double.isFinite(mass) && mass > 0 && Double.isFinite(energy) && Double.isFinite(base)))
			throw new IllegalArgumentException("Thermal owner inventory is invalid");
		return single(material, mass, SignedDyadic.of(energy).subtract(SignedDyadic.of(base)));
	}
	static ThermalStateFunction.State single(ThermalMaterialDefinition material, double mass, SignedDyadic sensible) {
		var fast = singleFast(material, mass, sensible);
		if (fast != null) return fast;
		return solve(new Single(material, mass), sensible);
	}
	static ThermalStateFunction.State single(ThermalMaterialDefinition material, SignedDyadic mass, SignedDyadic sensible) {
		return solve(new Inventory() {
			public int first() { return 0; } public int next(int item) { return -1; }
			public ThermalMaterialDefinition material(int item) { return material; }
			public double mass(int item) { return mass.toDouble(); }
			public SignedDyadic exactMass(int item) { return mass; }
		}, sensible);
	}
	static double heatCapacity(Inventory inventory, double temperature) { return state(inventory, temperature).heatCapacityEuPerK(); }

	static double energyAt(ThermalMaterialDefinition material, double mass, double base, double temperature) {
		material.specificHeatAt(temperature);
		if (!(Double.isFinite(mass) && mass > 0 && Double.isFinite(base))) throw new IllegalArgumentException("Thermal owner inventory is invalid");
		try {
			double value = base + mass * material.sensibleSpecificEnergyAt(temperature);
			if (Double.isFinite(value)) return value;
		} catch (IllegalArgumentException overflow) { /* The validated endpoints leave only integral overflow. */ }
		double value = SignedDyadic.of(base).add(ThermalContactExchange.exactEnergyChangeBetween(material, mass,
				material.referenceTemperatureK(), temperature)).toDouble();
		if (!Double.isFinite(value)) throw new ThermalStateFunction.EnergyRepresentationException();
		return value;
	}

	static double modeledRoundoff(ThermalMaterialDefinition material, double mass, double base, double temperature) {
		material.specificHeatAt(temperature);
		try {
			double sensible = material.sensibleSpecificEnergyAt(temperature), term = mass * sensible;
			double bound = material.caloricSegments().size() * mass * Math.ulp(sensible) + Math.ulp(term) + Math.ulp(base + term)
					+ Math.ulp(mass) * Math.abs(material.referenceSpecificEnergyEuPerMu() + sensible)
					+ mass * material.specificHeatAt(temperature) * Math.ulp(temperature);
			if (Double.isFinite(bound)) return bound;
		} catch (IllegalArgumentException overflow) { /* The exact forward path has no intermediate integral rounding. */ }
		SignedDyadic exactMass = SignedDyadic.of(mass), sensible = ThermalContactExchange.exactEnergyChangeBetween(material, mass,
				material.referenceTemperatureK(), temperature);
		SignedDyadic materialEnergy = exactMass.multiply(SignedDyadic.of(material.referenceSpecificEnergyEuPerMu())).add(sensible);
		if (materialEnergy.signum() < 0) materialEnergy = SignedDyadic.ZERO.subtract(materialEnergy);
		return Math.ulp(energyAt(material, mass, base, temperature))
				+ divide(materialEnergy.multiply(SignedDyadic.of(Math.ulp(mass))), exactMass)
				+ ThermalContactExchange.product(mass, material.specificHeatAt(temperature), Math.ulp(temperature));
	}

	static ThermalStateFunction.State solve(Inventory inventory, SignedDyadic target) {
		int first = inventory.first();
		if (inventory.next(first) < 0 && inventory.exactMass(first).equals(SignedDyadic.of(inventory.mass(first)))) {
			var fast = singleFast(inventory.material(first), inventory.mass(first), target);
			if (fast != null) return fast;
		}
		double cursor = inventory.material(first).referenceTemperatureK();
		SignedDyadic cursorEnergy = SignedDyadic.ZERO;
		if (inventory.next(first) >= 0) for (int item = first; item >= 0; item = inventory.next(item)) {
			var material = inventory.material(item);
			cursorEnergy = cursorEnergy.add(ThermalContactExchange.exactEnergyChangeBetween(material, inventory.exactMass(item),
					material.referenceTemperatureK(), cursor));
		}
		int direction = target.compareTo(cursorEnergy);
		if (direction == 0) return state(inventory, cursor);
		while (true) {
			double next = direction > 0 ? Double.POSITIVE_INFINITY : ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
			for (int item = first; item >= 0; item = inventory.next(item))
				for (var segment : inventory.material(item).caloricSegments()) {
					double endpoint = segment.maximumTemperatureK();
					if (direction > 0 ? endpoint > cursor && endpoint < next : endpoint < cursor && endpoint > next)
						next = endpoint;
				}
			double probe = next == Double.POSITIVE_INFINITY ? cursor : Math.min(cursor, next) + Math.abs(next - cursor) * .5;
			SignedDyadic capacity = capacity(inventory, probe);
			if (next == Double.POSITIVE_INFINITY) return state(inventory, divide(SignedDyadic.of(cursor).multiply(capacity)
					.add(target.subtract(cursorEnergy)), capacity));
			// The initial anchor uses each original reference. Exact accumulation then follows
			// the same affine segments without rounding away energy at a neighbouring knot.
			SignedDyadic nextEnergy = cursorEnergy.add(SignedDyadic.of(next).subtract(SignedDyadic.of(cursor)).multiply(capacity));
			int comparison = target.compareTo(nextEnergy);
			if (direction > 0 ? comparison <= 0 : comparison >= 0) {
				double low = Math.min(cursor, next), high = Math.max(cursor, next);
				SignedDyadic lower = target.subtract(direction > 0 ? cursorEnergy : nextEnergy);
				SignedDyadic upper = (direction > 0 ? nextEnergy : cursorEnergy).subtract(target);
				double temperature = lower.compareTo(upper) <= 0 ? divide(SignedDyadic.of(low).multiply(capacity).add(lower), capacity)
						: divide(SignedDyadic.of(high).multiply(capacity).subtract(upper), capacity);
				return state(inventory, Math.max(low, Math.min(high, temperature)));
			}
			if (next == ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K) {
				double minimumEnergy = nextEnergy.toDouble();
				if (Double.isFinite(minimumEnergy) && target.compareTo(nextEnergy.subtract(SignedDyadic.of(16 * Math.ulp(minimumEnergy)))) >= 0)
					return state(inventory, next);
				throw new ThermalStateFunction.EnergyOutsideValidityException();
			}
			cursor = next; cursorEnergy = nextEnergy;
		}
	}

	/** Hardware arithmetic is sufficient only when the exact coefficient/target fit binary64,
	 * and an outward-rounded interval proves the result stays in one segment within one temperature ULP. */
	private static ThermalStateFunction.State singleFast(ThermalMaterialDefinition material, double mass, SignedDyadic target) {
		double energy = target.toDouble();
		if (!Double.isFinite(energy) || !target.equals(SignedDyadic.of(energy))) return null;
		double reference = material.referenceTemperatureK(); int index = 0;
		var segments = material.caloricSegments();
		while (index + 1 < segments.size() && (reference > segments.get(index).maximumTemperatureK()
				|| reference == segments.get(index).maximumTemperatureK() && energy >= 0)) index++;
		double cv = energy == 0 ? material.specificHeatAt(reference) : segments.get(index).specificHeatEuPerMuK();
		double capacity = mass * cv;
		if (!(Double.isFinite(capacity) && capacity >= Double.MIN_NORMAL) || significandBits(mass) + significandBits(cv) > 53) return null;
		if (energy == 0) return new ThermalStateFunction.State(reference, capacity);
		double delta = energy / capacity, temperature = reference + delta;
		if (!Double.isFinite(temperature)) return null;
		double lower = Math.nextDown(reference + Math.nextDown(delta)), upper = Math.nextUp(reference + Math.nextUp(delta));
		double minimum = index == 0 ? ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K : segments.get(index).minimumTemperatureK();
		double maximum = index + 1 == segments.size() ? Double.POSITIVE_INFINITY : segments.get(index).maximumTemperatureK();
		if (lower > minimum && upper < maximum && temperature - lower <= Math.ulp(temperature) && upper - temperature <= Math.ulp(temperature))
			return new ThermalStateFunction.State(temperature, capacity);
		return null;
	}
	private static int significandBits(double value) {
		long bits = Double.doubleToRawLongBits(value), significand = bits & 0x000f_ffff_ffff_ffffL;
		if ((bits & 0x7ff0_0000_0000_0000L) != 0) significand |= 0x0010_0000_0000_0000L;
		return 64 - Long.numberOfLeadingZeros(significand) - Long.numberOfTrailingZeros(significand);
	}

	private static ThermalStateFunction.State state(Inventory inventory, double temperature) {
		if (!Double.isFinite(temperature)) throw new ThermalStateFunction.EnergyRepresentationException();
		if (temperature < ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)
			throw new IllegalArgumentException("Thermal state inversion overflowed or left the positive-temperature domain");
		double capacity = capacity(inventory, temperature).toDouble();
		if (!Double.isFinite(capacity)) throw new ThermalStateFunction.EnergyRepresentationException();
		if (!(capacity > 0)) throw new IllegalArgumentException("Thermal heat capacity is invalid");
		return new ThermalStateFunction.State(temperature, capacity);
	}
	private static SignedDyadic capacity(Inventory inventory, double temperature) {
		SignedDyadic total = SignedDyadic.ZERO;
		for (int item = inventory.first(); item >= 0; item = inventory.next(item)) {
			double mass = inventory.mass(item), cv = inventory.material(item).specificHeatAt(temperature);
			SignedDyadic exactMass = inventory.exactMass(item);
			total = total.add(exactMass.equals(SignedDyadic.of(mass)) ? product(mass, cv) : exactMass.multiply(SignedDyadic.of(cv)));
		}
		return total;
	}
	private static SignedDyadic product(double a, double b) {
		SignedDyadic x = SignedDyadic.of(a), y = SignedDyadic.of(b);
		double value = a * b;
		if (Double.isFinite(value) && x.exponent() + y.exponent() >= -1074
				&& x.significand().abs().bitLength() + y.significand().abs().bitLength() <= 53) return SignedDyadic.of(value);
		return x.multiply(y);
	}
	private static double divide(SignedDyadic numerator, SignedDyadic denominator) {
		double a = numerator.toDouble(), b = denominator.toDouble();
		if (Double.isFinite(a) && Double.isFinite(b) && b > 0 && numerator.equals(SignedDyadic.of(a)) && denominator.equals(SignedDyadic.of(b)))
			return a / b;
		int shift = numerator.exponent() - denominator.exponent();
		return new ExactRational(shift >= 0 ? numerator.significand().shiftLeft(shift) : numerator.significand(),
				shift >= 0 ? denominator.significand() : denominator.significand().shiftLeft(-shift)).toDouble();
	}
}
