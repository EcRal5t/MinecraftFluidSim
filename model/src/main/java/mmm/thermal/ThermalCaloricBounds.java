package mmm.thermal;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

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
		return boundedTemperature(portions, SignedDyadic.of(targetEnergy), upper, initialBracket);
	}

	/** Keeps the cell/gas handoff exact until it is compared against an outward-facing curve. */
	public double temperatureBound(List<PortionRange> portions, SignedDyadic targetEnergy, boolean upper,
	                               double initialBracket) {
		return boundedTemperature(portions, Objects.requireNonNull(targetEnergy), upper, initialBracket);
	}

	/** Bounds the LTE temperature after the assignment's single rounded pivot remainder. */
	public double assignedTemperatureBound(List<PortionRange> portions, SignedDyadic requestedLow,
	                                       SignedDyadic requestedHigh, boolean upper, double initialBracket) {
		if (requestedLow.compareTo(requestedHigh) > 0) throw new IllegalArgumentException("Reversed thermal energy interval");
		try {
			double lowerTemperature = temperatureBound(portions, requestedLow, false, initialBracket);
			double upperTemperature = temperatureBound(portions, requestedHigh, true, initialBracket);
			if (!Double.isFinite(lowerTemperature) || !Double.isFinite(upperTemperature)) return unboundedTemperature(upper);
			SignedDyadic[] lows = new SignedDyadic[portions.size()], highs = new SignedDyadic[portions.size()];
			SignedDyadic totalLow = SignedDyadic.ZERO, totalHigh = SignedDyadic.ZERO;
			for (int index = 0; index < portions.size(); index++) {
				// Keep separate owners: each has its own rounded reference and modeled forward energy.
				List<PortionRange> owner = List.of(portions.get(index));
				double low = energyAtTemperature(owner, lowerTemperature, false);
				double high = energyAtTemperature(owner, upperTemperature, true);
				if (!Double.isFinite(low) || !Double.isFinite(high)) return unboundedTemperature(upper);
				lows[index] = SignedDyadic.of(low); highs[index] = SignedDyadic.of(high);
				totalLow = totalLow.add(lows[index]); totalHigh = totalHigh.add(highs[index]);
			}
			double error = 0;
			for (int pivot = 0; pivot < portions.size(); pivot++) {
				SignedDyadic low = SignedDyadic.sum(requestedLow, totalHigh.negate(), highs[pivot]);
				SignedDyadic high = SignedDyadic.sum(requestedHigh, totalLow.negate(), lows[pivot]);
				double magnitude = Math.nextUp(Math.max(Math.abs(low.toDouble()), Math.abs(high.toDouble())));
				if (!Double.isFinite(magnitude)) return unboundedTemperature(upper);
				// RN error is at most half an ULP; a whole outward ULP also covers exponent seams/subnormals.
				error = Math.max(error, Math.ulp(magnitude));
			}
			SignedDyadic target = upper ? requestedHigh.add(SignedDyadic.of(error))
					: requestedLow.subtract(SignedDyadic.of(error));
			return temperatureBound(portions, target, upper, initialBracket);
		} catch (IllegalArgumentException | ArithmeticException unresolved) { return unboundedTemperature(upper); }
	}

	private double boundedTemperature(List<PortionRange> portions, SignedDyadic targetEnergy, boolean upper,
	                                  double initialBracket) {
		if (portions.isEmpty() || !Double.isFinite(initialBracket)) return unboundedTemperature(upper);
		try {
			double minimum = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
			double cursor = Math.max(minimum, initialBracket);
			SignedDyadic energy = exactCurveEnergy(portions, cursor, !upper);
			if (energy == null) return unboundedTemperature(upper);
			int direction = targetEnergy.compareTo(energy);
			if (direction == 0) {
				if (boundCapacity(portions, cursor, !upper).signum() == 0
						|| cursor > minimum && boundCapacity(portions, down(cursor), !upper).signum() == 0)
					return unboundedTemperature(upper);
				return upper ? up(up(cursor)) : Math.max(minimum, down(down(cursor)));
			}
			while (true) {
				if (direction < 0 && cursor == minimum) return minimum;
				double next = direction > 0 ? Double.POSITIVE_INFINITY : minimum;
				for (PortionRange portion : portions) {
					var material = thermal.material(portion.materialHandle());
					next = nearerKnot(cursor, next, material.referenceTemperatureK(), direction);
					for (var segment : material.caloricSegments()) {
						next = nearerKnot(cursor, next, segment.minimumTemperatureK(), direction);
						next = nearerKnot(cursor, next, segment.maximumTemperatureK(), direction);
					}
				}
				// Directional endpoint probes select the correct side even at adjacent binary64 knots.
				SignedDyadic capacity = boundCapacity(portions, direction > 0 ? cursor : down(cursor), !upper);
				if (capacity.signum() == 0) return unboundedTemperature(upper);
				SignedDyadic nextEnergy = Double.isFinite(next) ? energy.add(
						SignedDyadic.of(next).subtract(SignedDyadic.of(cursor)).multiply(capacity)) : null;
				if (nextEnergy == null || (direction > 0 ? targetEnergy.compareTo(nextEnergy) <= 0
						: targetEnergy.compareTo(nextEnergy) >= 0)) {
					double root = divide(SignedDyadic.of(cursor).multiply(capacity).add(targetEnergy.subtract(energy)), capacity);
					if (!Double.isFinite(root)) return unboundedTemperature(upper);
					// Exact affine inversion has one final rounding; retain the actual inverter's two-neighbour allowance.
					return upper ? Math.max(minimum, up(up(root))) : Math.max(minimum, down(down(root)));
				}
				cursor = next; energy = nextEnergy;
			}
		} catch (IllegalArgumentException | ArithmeticException unresolved) { return unboundedTemperature(upper); }
	}

	private SignedDyadic boundCapacity(List<PortionRange> portions, double probe, boolean upper) {
		SignedDyadic total = SignedDyadic.ZERO;
		for (PortionRange portion : portions) {
			var material = thermal.material(portion.materialHandle());
			double mass = (probe >= material.referenceTemperatureK()) == upper ? portion.massHigh() : portion.massLow();
			total = total.add(SignedDyadic.of(mass).multiply(SignedDyadic.of(material.specificHeatAt(probe))));
		}
		return total;
	}
	private static double nearerKnot(double cursor, double next, double knot, int direction) {
		return direction > 0 ? knot > cursor && knot < next ? knot : next : knot < cursor && knot > next ? knot : next;
	}
	private static double divide(SignedDyadic numerator, SignedDyadic denominator) {
		int shift = Math.subtractExact(numerator.exponent(), denominator.exponent());
		if (Math.abs((long) shift) > ChemicalLimits.MAX_RATIONAL_BITS)
			throw new IllegalArgumentException("Caloric bound ratio is over budget");
		return new ExactRational(shift >= 0 ? numerator.significand().shiftLeft(shift) : numerator.significand(),
				shift >= 0 ? denominator.significand() : denominator.significand().shiftLeft(-shift)).toDouble();
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
