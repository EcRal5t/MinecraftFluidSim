package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Conservative caloric envelopes for prospective raw owner columns, independently of transfer order. */
public final class ThermalCaloricBounds {
	public record QuantaRange(long low, long high) {
		public QuantaRange {
			if (low < 0 || high < low) throw new IllegalArgumentException("Invalid thermal species range");
		}
	}

	/** Zero lower mass permits an absent owner; species ranges and canonical order are immutable. */
	public record PortionRange(int materialHandle, double massLow, double massHigh,
	                           Map<String, QuantaRange> species) {
		public PortionRange {
			if (materialHandle < 0 || !Double.isFinite(massLow) || massLow < 0 || !(massHigh >= massLow)
					|| species == null) throw new IllegalArgumentException("Invalid thermal portion range");
			TreeMap<String, QuantaRange> canonical = new TreeMap<>();
			for (var entry : species.entrySet()) canonical.put(Objects.requireNonNull(entry.getKey()),
					Objects.requireNonNull(entry.getValue()));
			species = Collections.unmodifiableMap(canonical);
		}
	}

	private final ThermalRegistry thermal;

	public ThermalCaloricBounds(ThermalRegistry thermal) {
		this.thermal = Objects.requireNonNull(thermal);
	}

	/** Bounds the rounded forward-energy API, including its exact-integral overflow fallback. */
	public double energyAtTemperature(List<PortionRange> portions, double temperature, boolean upper) {
		try { return orderedForwardEnergy(portions, temperature, upper); }
		catch (IllegalArgumentException | ArithmeticException unresolved) { return unboundedEnergy(upper); }
	}

	private double orderedForwardEnergy(List<PortionRange> portions, double temperature, boolean upper) {
		double result = 0.0;
		for (PortionRange portion : portions) {
			ThermalMaterialDefinition material = thermal.material(portion.materialHandle());
			double reference = material.referenceSpecificEnergyEuPerMu();
			double base = productRange(portion.massLow(), portion.massHigh(), reference, upper);
			for (var entry : portion.species().entrySet()) {
				ThermalSpeciesOffset offset = thermal.speciesOffset(portion.materialHandle(), entry.getKey());
				double first = thermal.referenceEnergyCache().energy(offset, entry.getValue().low());
				double last = thermal.referenceEnergyCache().energy(offset, entry.getValue().high());
				base = outward(base + (upper ? Math.max(first, last) : Math.min(first, last)), upper);
			}
			result = outward(result + forwardBound(portion, material, temperature, base, upper), upper);
		}
		return Double.isFinite(result) ? result : unboundedEnergy(upper);
	}

	/** Bounds the inversion curve anchored at each original reference, rather than the rounded forward API. */
	public double curveEnergyAtTemperature(List<PortionRange> portions, double temperature, boolean upper) {
		try {
			SignedDyadic energy = exactCurveEnergy(portions, temperature, upper);
			return energy == null ? unboundedEnergy(upper) : outward(energy.toDouble(), upper);
		}
		catch (IllegalArgumentException | ArithmeticException unresolved) { return unboundedEnergy(upper); }
	}

	private SignedDyadic exactCurveEnergy(List<PortionRange> portions, double temperature, boolean upper) {
		SignedDyadic result = SignedDyadic.ZERO;
		for (PortionRange portion : portions) {
			double base = referenceEnergyBound(portion, upper);
			if (!Double.isFinite(base)) return null;
			ThermalMaterialDefinition material = thermal.material(portion.materialHandle());
			SignedDyadic specific = ThermalContactExchange.exactEnergyChangeBetween(material, 1.0,
					material.referenceTemperatureK(), temperature);
			double mass = (specific.signum() >= 0) == upper ? portion.massHigh() : portion.massLow();
			if (!Double.isFinite(mass)) return null;
			result = result.add(SignedDyadic.of(base)).add(specific.multiply(SignedDyadic.of(mass)));
		}
		return result;
	}

	/** Rounded multiplication and ordered addition are monotone, including signed reference offsets. */
	public double referenceEnergyBound(PortionRange portion, boolean upper) {
		double reference = thermal.material(portion.materialHandle()).referenceSpecificEnergyEuPerMu();
		double result = ((reference >= 0) == upper ? portion.massHigh() : portion.massLow()) * reference;
		for (var entry : portion.species().entrySet()) {
			ThermalSpeciesOffset offset = thermal.speciesOffset(portion.materialHandle(), entry.getKey());
			double first = thermal.referenceEnergyCache().energy(offset, entry.getValue().low());
			double last = thermal.referenceEnergyCache().energy(offset, entry.getValue().high());
			result += upper ? Math.max(first, last) : Math.min(first, last);
		}
		return Double.isFinite(result) ? result : unboundedEnergy(upper);
	}

	public double temperatureBound(List<PortionRange> portions, double targetEnergy, boolean upper,
	                               double initialBracket) {
		if (!Double.isFinite(targetEnergy)) return unboundedTemperature(upper);
		return boundedTemperature(portions, SignedDyadic.of(targetEnergy), upper, initialBracket, false);
	}

	/** Keeps the cell/gas handoff exact until it is compared against an outward-facing curve. */
	public double temperatureBound(List<PortionRange> portions, SignedDyadic targetEnergy, boolean upper,
	                               double initialBracket) {
		return boundedTemperature(portions, Objects.requireNonNull(targetEnergy), upper, initialBracket, true);
	}

	private double boundedTemperature(List<PortionRange> portions, SignedDyadic targetEnergy, boolean upper,
	                                  double initialBracket, boolean exactComparison) {
		if (portions.isEmpty() || !Double.isFinite(initialBracket)) return unboundedTemperature(upper);
		try {
			double low = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
			double high = Math.max(1.0, initialBracket);
			if (compareCurve(portions, low, !upper, targetEnergy, exactComparison) >= 0) return low;
			while (compareCurve(portions, high, !upper, targetEnergy, exactComparison) < 0) {
				if (high >= Double.MAX_VALUE / 2.0) return upper ? Double.POSITIVE_INFINITY : down(down(high));
				high *= 2.0;
			}
			for (int iteration = 0; iteration < 64 && Math.nextUp(low) < high; iteration++) {
				double middle = low + (high - low) / 2.0;
				if (compareCurve(portions, middle, !upper, targetEnergy, exactComparison) < 0) low = middle;
				else high = middle;
			}
			// A one-ULP fast-path temperature error spans two neighbours across an exponent seam.
			return upper ? up(up(high)) : Math.max(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, down(down(low)));
		} catch (IllegalArgumentException | ArithmeticException unresolved) {
			return unboundedTemperature(upper);
		}
	}

	/** Merges columns belonging to one actual owner; independent owners retain separate rounded reference terms. */
	public PortionRange merge(PortionRange first, PortionRange second) {
		if (first.materialHandle() != second.materialHandle()) throw new IllegalArgumentException("Thermal merge materials differ");
		TreeMap<String, QuantaRange> species = new TreeMap<>(first.species());
		second.species().forEach((id, amounts) -> species.merge(id, amounts, (a, b) ->
				new QuantaRange(Math.addExact(a.low(), b.low()), Math.addExact(a.high(), b.high()))));
		return new PortionRange(first.materialHandle(), Math.max(0.0, down(first.massLow() + second.massLow())),
				up(first.massHigh() + second.massHigh()), species);
	}

	private int compareCurve(List<PortionRange> portions, double temperature, boolean upper,
	                         SignedDyadic target, boolean exactComparison) {
		if (exactComparison) {
			SignedDyadic curve = exactCurveEnergy(portions, temperature, upper);
			return curve == null ? upper ? 1 : -1 : curve.compareTo(target);
		}
		double value = curveEnergyAtTemperature(portions, temperature, upper);
		if (!Double.isFinite(value)) return upper ? 1 : -1;
		return SignedDyadic.of(value).compareTo(target);
	}

	private double forwardBound(PortionRange portion, ThermalMaterialDefinition material,
	                            double temperature, double base, boolean upper) {
		double normal = unboundedEnergy(upper);
		boolean normalPossible = false;
		try {
			double sensible = material.sensibleSpecificEnergyAt(temperature);
			normal = outward(base + productRange(portion.massLow(), portion.massHigh(), sensible, upper), upper);
			// Every actual owner then follows the same finite forward branch, so its operation order suffices.
			double termLow = portion.massLow() * sensible, termHigh = portion.massHigh() * sensible;
			normalPossible = !(Double.isInfinite(termLow) && termLow == termHigh);
			if (Double.isFinite(termLow) && Double.isFinite(termHigh)
					&& Double.isFinite(referenceEnergyBound(portion, false) + Math.min(termLow, termHigh))
					&& Double.isFinite(referenceEnergyBound(portion, true) + Math.max(termLow, termHigh))) return normal;
		} catch (IllegalArgumentException integralOverflow) { /* The exact branch remains available. */ }
		if (!Double.isFinite(base)) return unboundedEnergy(upper);
		SignedDyadic specific = ThermalContactExchange.exactEnergyChangeBetween(material, 1.0,
				material.referenceTemperatureK(), temperature);
		double mass = (specific.signum() >= 0) == upper ? portion.massHigh() : portion.massLow();
		if (!Double.isFinite(mass)) return unboundedEnergy(upper);
		double exact = outward(SignedDyadic.of(base).add(specific.multiply(SignedDyadic.of(mass))).toDouble(), upper);
		if (!normalPossible) return Double.isNaN(exact) ? unboundedEnergy(upper) : exact;
		double result = upper ? Math.max(normal, exact) : Math.min(normal, exact);
		return Double.isNaN(result) ? unboundedEnergy(upper) : result;
	}

	private static double productRange(double low, double high, double factor, boolean upper) {
		return outward((factor >= 0.0 == upper ? high : low) * factor, upper);
	}
	private static double unboundedEnergy(boolean upper) { return upper ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY; }
	private static double unboundedTemperature(boolean upper) { return upper ? Double.POSITIVE_INFINITY : ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K; }
	private static double outward(double value, boolean upper) { return upper ? up(value) : down(value); }
	private static double up(double value) { return Math.nextUp(value); }
	private static double down(double value) { return Math.nextDown(value); }
}
