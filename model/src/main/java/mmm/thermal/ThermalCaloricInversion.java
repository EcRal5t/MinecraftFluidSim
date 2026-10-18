package mmm.thermal;

import mmm.chemistry.ExactRational;
import mmm.chemistry.ChemicalLimits;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.TreeMap;

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
	static double heatCapacity(Inventory inventory, double temperature) { return state(inventory, temperature).heatCapacityJPerK(); }

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
					+ Math.ulp(mass) * Math.abs(material.referenceSpecificEnergyJPerKg() + sensible)
					+ mass * material.specificHeatAt(temperature) * Math.ulp(temperature);
			if (Double.isFinite(bound)) return bound;
		} catch (IllegalArgumentException overflow) { /* The exact forward path has no intermediate integral rounding. */ }
		SignedDyadic exactMass = SignedDyadic.of(mass), sensible = ThermalContactExchange.exactEnergyChangeBetween(material, mass,
				material.referenceTemperatureK(), temperature);
		SignedDyadic materialEnergy = exactMass.multiply(SignedDyadic.of(material.referenceSpecificEnergyJPerKg())).add(sensible);
		if (materialEnergy.signum() < 0) materialEnergy = SignedDyadic.ZERO.subtract(materialEnergy);
		return Math.ulp(energyAt(material, mass, base, temperature))
				+ divide(materialEnergy.multiply(SignedDyadic.of(Math.ulp(mass))), exactMass)
				+ ThermalContactExchange.product(mass, material.specificHeatAt(temperature), Math.ulp(temperature));
	}

	static ThermalStateFunction.State solve(Inventory inventory, SignedDyadic target) {
		return new Curve(inventory).solve(target);
	}

	/** One common piecewise-affine caloric curve; equal materials share their exact mass. */
	static final class Curve {
		private final ThermalMaterialDefinition[] materials;
		private final SignedDyadic[] masses;
		private final double[] knots;
		private final SignedDyadic[] energies, capacities;
		private final SignedDyadic coldCapacity, minimumCapacity;

		Curve(Inventory inventory) {
			var positions = new IdentityHashMap<ThermalMaterialDefinition, Integer>();
			var groupedMaterials = new ArrayList<ThermalMaterialDefinition>();
			var groupedMass = new ArrayList<SignedDyadic>();
			for (int item = inventory.first(); item >= 0; item = inventory.next(item)) {
				var material = inventory.material(item);
				SignedDyadic mass = inventory.exactMass(item);
				if (material == null || mass == null || mass.signum() <= 0
						|| !(Double.isFinite(inventory.mass(item)) && inventory.mass(item) > 0))
					throw new IllegalArgumentException("Thermal caloric inventory is invalid");
				Integer index = positions.get(material);
				if (index == null) {
					positions.put(material, groupedMaterials.size());
					groupedMaterials.add(material); groupedMass.add(mass);
				} else groupedMass.set(index, groupedMass.get(index).add(mass));
			}
			if (groupedMaterials.isEmpty()) throw new IllegalArgumentException("Thermal caloric inventory is empty");
			materials = groupedMaterials.toArray(ThermalMaterialDefinition[]::new);
			masses = groupedMass.toArray(SignedDyadic[]::new);
			var changes = new TreeMap<Double, SignedDyadic>();
			SignedDyadic capacity = SignedDyadic.ZERO;
			for (int group = 0; group < materials.length; group++) {
				var material = materials[group]; var segments = material.caloricSegments();
				capacity = capacity.add(masses[group].multiply(SignedDyadic.of(segments.getFirst().specificHeatJPerKgK())));
				for (int segment = 1; segment < segments.size(); segment++) {
					SignedDyadic change = SignedDyadic.of(segments.get(segment).specificHeatJPerKgK())
							.subtract(SignedDyadic.of(segments.get(segment - 1).specificHeatJPerKgK())).multiply(masses[group]);
					changes.merge(segments.get(segment).minimumTemperatureK(), change, SignedDyadic::add);
				}
			}
			changes.values().removeIf(value -> value.signum() == 0);
			// A real reference anchors the common curve, keeping ordinary constant-cv
			// arithmetic compact even though endpoint extensions reach MIN_NORMAL.
			changes.putIfAbsent(materials[0].referenceTemperatureK(), SignedDyadic.ZERO);
			knots = new double[changes.size()]; energies = new SignedDyadic[knots.length]; capacities = new SignedDyadic[knots.length];
			coldCapacity = capacity;
			SignedDyadic minimum = capacity, energy = SignedDyadic.ZERO;
			double firstKnot = changes.firstKey();
			for (int group = 0; group < materials.length; group++) energy = energy.add(
					ThermalContactExchange.exactEnergyChangeBetween(materials[group], masses[group],
							materials[group].referenceTemperatureK(), firstKnot));
			int index = 0;
			for (var change : changes.entrySet()) {
				knots[index] = change.getKey();
				if (index > 0) energy = energy.add(SignedDyadic.of(knots[index]).subtract(SignedDyadic.of(knots[index - 1])).multiply(capacity));
				capacity = capacity.add(change.getValue());
				energies[index] = energy; capacities[index] = capacity;
				if (capacity.compareTo(minimum) < 0) minimum = capacity;
				index++;
			}
			minimumCapacity = minimum;
		}

		ThermalStateFunction.State solve(SignedDyadic target) {
			int low = 0, high = knots.length;
			while (low + 1 < high) {
				int middle = (low + high) >>> 1;
				if (target.compareTo(energies[middle]) >= 0) low = middle; else high = middle;
			}
			SignedDyadic capacity = target.compareTo(energies[0]) < 0 ? coldCapacity : capacities[low];
			SignedDyadic numerator = SignedDyadic.of(knots[low]).multiply(capacity).add(target.subtract(energies[low]));
			double temperature = divide(numerator, capacity);
			if (temperature < ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K) {
				SignedDyadic minimumEnergy = energies[0].add(SignedDyadic.of(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)
						.subtract(SignedDyadic.of(knots[0])).multiply(coldCapacity));
				double minimum = minimumEnergy.toDouble();
				if (!Double.isFinite(minimum) || target.compareTo(minimumEnergy.subtract(SignedDyadic.of(16 * Math.ulp(minimum)))) < 0)
					throw new ThermalStateFunction.EnergyOutsideValidityException();
				return stateAt(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
			}
			if (high < knots.length) temperature = Math.min(knots[high], temperature);
			return stateAt(temperature);
		}

		ThermalStateFunction.State stateAt(double temperature) {
			if (!Double.isFinite(temperature)) throw new ThermalStateFunction.EnergyRepresentationException();
			if (temperature < ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)
				throw new IllegalArgumentException("Thermal state inversion left the positive-temperature domain");
			int index = java.util.Arrays.binarySearch(knots, temperature);
			if (index < 0) index = -index - 2;
			double capacity = (index < 0 ? coldCapacity : capacities[index]).toDouble();
			if (!Double.isFinite(capacity)) throw new ThermalStateFunction.EnergyRepresentationException();
			if (!(capacity > 0)) throw new IllegalArgumentException("Thermal heat capacity is invalid");
			return new ThermalStateFunction.State(temperature, capacity);
		}

		/** Bounds the final conservation-pivot storage error without preparing owner energies. */
		double assignmentTemperatureTolerance(SignedDyadic requested, SignedDyadic absoluteReference, int owners, double temperature) {
			SignedDyadic magnitude = absoluteReference;
			for (int group = 0; group < materials.length; group++) magnitude = magnitude.add(abs(
					ThermalContactExchange.exactEnergyChangeBetween(materials[group], masses[group],
							materials[group].referenceTemperatureK(), temperature)));
			// Each rounded non-pivot energy is bounded by twice its exact magnitude plus
			// one subnormal quantum. The pivot is requested minus their exact stored sum.
			SignedDyadic pivotBound = abs(requested).add(magnitude.multiply(SignedDyadic.of(2L, 0)))
					.add(SignedDyadic.of((long) owners, -1074));
			int exponent = pivotBound.signum() == 0 ? -1074
					: Math.max(-1074, pivotBound.exponent() + pivotBound.significand().abs().bitLength() - 53);
			double tolerance = divide(SignedDyadic.of(1L, exponent), minimumCapacity);
			return Math.nextUp(tolerance + 2 * Math.ulp(temperature));
		}
	}

	private static SignedDyadic abs(SignedDyadic value) {
		return value.signum() < 0 ? SignedDyadic.ZERO.subtract(value) : value;
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
		var rawNumerator = shift >= 0 ? numerator.significand().shiftLeft(shift) : numerator.significand();
		var rawDenominator = shift >= 0 ? denominator.significand() : denominator.significand().shiftLeft(-shift);
		if (rawNumerator.bitLength() > ChemicalLimits.MAX_RATIONAL_BITS || rawDenominator.bitLength() > ChemicalLimits.MAX_RATIONAL_BITS)
			throw new IllegalArgumentException("Rational magnitude is over budget");
		return ExactRational.roundQuotientToDouble(rawNumerator, rawDenominator);
	}
}
