package mmm.fluid;

import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.Arrays;

/** Positive largest-remainder allocation over the exact binary volume lattice. */
final class ExactVolumeAllocation {
	private ExactVolumeAllocation() { }

	static SignedDyadic[] allocate(SignedDyadic amount, SignedDyadic[] weights) {
		if (weights.length == 1) {
			if (amount.signum() < 0 || weights[0].signum() < 0
					|| weights[0].signum() == 0 && amount.signum() != 0)
				throw new IllegalArgumentException("Invalid single-support allocation");
			return new SignedDyadic[] {amount};
		}
		int exponent = amount.exponent();
		for (SignedDyadic weight : weights) if (weight.signum() > 0) exponent = Math.min(exponent, weight.exponent());
		long[] narrowWeights = narrowUnits(weights, exponent);
		if (narrowWeights != null && amount.signum() >= 0 && amount.fitsLongUnits(exponent)) {
			long[] allocated = allocateNarrow(amount.longUnitsExact(exponent), narrowWeights);
			if (allocated != null) {
				SignedDyadic[] result = new SignedDyadic[weights.length];
				for (int i = 0; i < result.length; i++) result[i] = SignedDyadic.of(allocated[i], exponent);
				return result;
			}
		}
		BigInteger[] units = new BigInteger[weights.length];
		for (int i = 0; i < weights.length; i++) units[i] = units(weights[i], exponent);
		BigInteger[] allocated = allocate(units(amount, exponent), units);
		SignedDyadic[] result = new SignedDyadic[weights.length];
		for (int i = 0; i < result.length; i++) result[i] = new SignedDyadic(allocated[i], exponent);
		return result;
	}

	static long[] allocate(long amount, SignedDyadic[] weights) {
		if (amount < 0) throw new IllegalArgumentException("Negative allocation amount");
		int exponent = Integer.MAX_VALUE, support = 0; SignedDyadic first = null; boolean uniform = true;
		for (SignedDyadic weight : weights) {
			if (weight.signum() < 0) throw new IllegalArgumentException("Negative allocation weight");
			if (weight.signum() > 0) {
				exponent = Math.min(exponent, weight.exponent()); support++;
				if (first == null) first = weight; else uniform &= first.equals(weight);
			}
		}
		if (exponent == Integer.MAX_VALUE) throw new IllegalArgumentException("Volume allocation has no positive support");
		if (uniform) {
			long[] result = new long[weights.length]; long quotient = amount / support, remainder = amount % support;
			for (int i = 0; i < result.length; i++) if (weights[i].signum() > 0) {
				result[i] = quotient; if (remainder > 0) { result[i]++; remainder--; }
			}
			return result;
		}
		long[] narrowWeights = narrowUnits(weights, exponent);
		if (narrowWeights != null) {
			long[] result = allocateNarrow(amount, narrowWeights);
			if (result != null) return result;
		}
		BigInteger[] units = new BigInteger[weights.length];
		for (int i = 0; i < weights.length; i++) units[i] = units(weights[i], exponent);
		BigInteger[] allocated = allocate(BigInteger.valueOf(amount), units);
		long[] result = new long[weights.length];
		for (int i = 0; i < result.length; i++) result[i] = allocated[i].longValueExact();
		return result;
	}

	/** Geometric preferences share the existing inventory lattice and respect each donor capacity. */
	static SignedDyadic[] allocateCapped(SignedDyadic amount, SignedDyadic[] capacities, double[] preferences) {
		if (capacities.length == 1) {
			if (amount.compareTo(capacities[0]) > 0) throw new IllegalArgumentException("Geometric allocation exceeds donor volume");
			return new SignedDyadic[] {amount};
		}
		int exponent = amount.exponent(), weightExponent = Integer.MAX_VALUE;
		SignedDyadic[] exactWeights = new SignedDyadic[preferences.length];
		for (int i = 0; i < capacities.length; i++) {
			if (capacities[i].signum() > 0) exponent = Math.min(exponent, capacities[i].exponent());
			exactWeights[i] = SignedDyadic.of(preferences[i]);
			if (exactWeights[i].signum() > 0) weightExponent = Math.min(weightExponent, exactWeights[i].exponent());
		}
		if (weightExponent == Integer.MAX_VALUE) weightExponent = 0;
		BigInteger[] remaining = new BigInteger[capacities.length], weights = new BigInteger[capacities.length], result = new BigInteger[capacities.length];
		for (int i = 0; i < remaining.length; i++) {
			remaining[i] = units(capacities[i], exponent); weights[i] = units(exactWeights[i], weightExponent); result[i] = BigInteger.ZERO;
		}
		BigInteger unassigned = units(amount, exponent), available = BigInteger.ZERO;
		for (BigInteger value : remaining) available = available.add(value);
		if (available.compareTo(unassigned) < 0) throw new IllegalArgumentException("Geometric allocation exceeds donor volume");
		while (unassigned.signum() > 0) {
			BigInteger totalWeight = BigInteger.ZERO;
			for (int i = 0; i < weights.length; i++) {
				if (remaining[i].signum() == 0) weights[i] = BigInteger.ZERO;
				totalWeight = totalWeight.add(weights[i]);
			}
			BigInteger[] allocation = allocate(unassigned, totalWeight.signum() == 0 ? remaining : weights);
			for (int i = 0; i < remaining.length; i++) {
				BigInteger accepted = allocation[i].min(remaining[i]); result[i] = result[i].add(accepted);
				remaining[i] = remaining[i].subtract(accepted); unassigned = unassigned.subtract(accepted);
			}
		}
		SignedDyadic[] allocated = new SignedDyadic[result.length];
		for (int i = 0; i < allocated.length; i++) allocated[i] = new SignedDyadic(result[i], exponent);
		return allocated;
	}

	private static BigInteger[] allocate(BigInteger amount, BigInteger[] weights) {
		if (amount.signum() < 0) throw new IllegalArgumentException("Negative allocation amount");
		BigInteger total = BigInteger.ZERO;
		for (BigInteger weight : weights) {
			if (weight.signum() < 0) throw new IllegalArgumentException("Negative allocation weight");
			total = total.add(weight);
		}
		if (total.signum() == 0) {
			if (amount.signum() != 0) throw new IllegalArgumentException("Empty allocation support");
			BigInteger[] result = new BigInteger[weights.length]; Arrays.fill(result, BigInteger.ZERO); return result;
		}
		BigInteger[] result = new BigInteger[weights.length], remainder = new BigInteger[weights.length];
		BigInteger assigned = BigInteger.ZERO;
		Integer[] order = new Integer[weights.length];
		for (int i = 0; i < result.length; i++) {
			BigInteger[] division = weights[i].multiply(amount).divideAndRemainder(total);
			result[i] = division[0]; remainder[i] = division[1]; assigned = assigned.add(result[i]); order[i] = i;
		}
		Arrays.sort(order, (a, b) -> { int comparison = remainder[b].compareTo(remainder[a]); return comparison != 0 ? comparison : Integer.compare(a, b); });
		int remaining = amount.subtract(assigned).intValueExact();
		for (int i = 0; i < remaining; i++) result[order[i]] = result[order[i]].add(BigInteger.ONE);
		return result;
	}

	/** Checked integer arithmetic gives the identical quotient, remainder and ordinal tie order. */
	private static long[] allocateNarrow(long amount, long[] weights) {
		if (amount < 0) throw new IllegalArgumentException("Negative allocation amount");
		long total = 0;
		for (long weight : weights) {
			if (weight > Long.MAX_VALUE - total) return null;
			total += weight;
		}
		long[] result = new long[weights.length], remainder = new long[weights.length];
		if (total == 0) {
			if (amount != 0) throw new IllegalArgumentException("Empty allocation support");
			return result;
		}
		long assigned = 0;
		for (int i = 0; i < result.length; i++) {
			long weight = weights[i], numerator = amount * weight;
			if (amount == 0 || weight <= Long.MAX_VALUE / amount) {
				result[i] = numerator / total; remainder[i] = numerator % total;
			} else {
				// A floating estimate supplies only the starting quotient. Two-word
				// integer products verify and correct it before any share is accepted.
				long quotient = Math.min(amount, (long) ((double) amount * weight / total));
				long product = quotient * total;
				long low = numerator - product;
				long high = Math.multiplyHigh(amount, weight) - Math.multiplyHigh(quotient, total)
						- (Long.compareUnsigned(numerator, product) < 0 ? 1 : 0);
				int corrections = 0;
				while (high < 0 || high > 0 || Long.compareUnsigned(low, total) >= 0) {
					if (++corrections > 8) return null;
					if (high < 0) {
						quotient--;
						long corrected = low + total;
						if (Long.compareUnsigned(corrected, low) < 0) high++;
						low = corrected;
					} else {
						quotient++;
						if (Long.compareUnsigned(low, total) < 0) high--;
						low -= total;
					}
				}
				result[i] = quotient; remainder[i] = low;
			}
			assigned += result[i];
		}
		for (long remaining = amount - assigned; remaining > 0; remaining--) {
			int largest = 0;
			for (int i = 1; i < result.length; i++) if (remainder[i] > remainder[largest]) largest = i;
			result[largest]++; remainder[largest] = -1;
		}
		return result;
	}

	private static long[] narrowUnits(SignedDyadic[] weights, int exponent) {
		long[] result = new long[weights.length];
		for (int i = 0; i < weights.length; i++) {
			var weight = weights[i];
			if (weight.signum() < 0) throw new IllegalArgumentException("Negative allocation weight");
			if (weight.signum() == 0) continue;
			if (!weight.fitsLongUnits(exponent)) return null;
			result[i] = weight.longUnitsExact(exponent);
		}
		return result;
	}

	private static BigInteger units(SignedDyadic value, int exponent) {
		return value.signum() == 0 ? BigInteger.ZERO : value.significand().shiftLeft(value.exponent() - exponent);
	}
}
