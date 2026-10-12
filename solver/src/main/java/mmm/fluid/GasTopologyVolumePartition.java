package mmm.fluid;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.List;

/** Partitions one physical control volume with exact dyadic geometric weights. */
final class GasTopologyVolumePartition {
	private GasTopologyVolumePartition() { }

	/** Uses actual polyhedron overlaps without quantizing their weights to dyadic observations. */
	static double[] partitionRational(double total, List<ExactRational> weights) {
		if (!Double.isFinite(total) || total < 0 || weights == null || weights.isEmpty())
			throw new IllegalArgumentException("Invalid gas topology volume partition");
		weights = List.copyOf(weights);
		ExactRational sum = ExactRational.ZERO; int pivot = 0;
		for (int i = 0; i < weights.size(); i++) {
			var weight = weights.get(i);
			if (weight.signum() < 0) throw new IllegalArgumentException("Invalid gas topology volume weight");
			sum = sum.add(weight);
			if (weight.compareTo(weights.get(pivot)) > 0) pivot = i;
		}
		if (sum.isZero()) throw new IllegalArgumentException("Gas topology volume weights have zero sum");
		double[] result = new double[weights.size()];
		if (total == 0) return result;
		SignedDyadic source = SignedDyadic.of(total), assigned = SignedDyadic.ZERO;
		for (int i = 0; i < result.length; i++) if (i != pivot && !weights.get(i).isZero()) {
			var weight = weights.get(i);
			result[i] = target(source, weight.numerator().multiply(sum.denominator()),
					weight.denominator().multiply(sum.numerator())).toDouble();
			requirePositive(result[i]); assigned = assigned.add(SignedDyadic.of(result[i]));
		}
		SignedDyadic remainder = source.subtract(assigned);
		if (remainder.signum() <= 0) throw new GasTopologyBlockedException("Gas topology volume rounding exhausted its largest share");
		result[pivot] = remainder.toDouble(); requirePositive(result[pivot]);
		return result;
	}

	static double[] partition(double total, List<SignedDyadic> weights) {
		if (!Double.isFinite(total) || total < 0 || weights == null || weights.isEmpty())
			throw new IllegalArgumentException("Invalid gas topology volume partition");
		int commonExponent = Integer.MAX_VALUE; boolean positive = false;
		for (var weight : weights) {
			if (weight == null || weight.signum() < 0) throw new IllegalArgumentException("Invalid gas topology volume weight");
			if (weight.signum() > 0) { positive = true; commonExponent = Math.min(commonExponent, weight.exponent()); }
		}
		if (!positive) throw new IllegalArgumentException("Gas topology volume weights have zero sum");
		weights = List.copyOf(weights);
		double[] result = new double[weights.size()];
		if (total == 0) return result;
		try {
			BigInteger[] units = new BigInteger[weights.size()];
			BigInteger sum = BigInteger.ZERO; int pivot = 0;
			for (int i = 0; i < weights.size(); i++) {
				var weight = weights.get(i);
				long shift = (long) weight.exponent() - commonExponent;
				if (weight.signum() == 0) units[i] = BigInteger.ZERO;
				else {
					requireBits((long) weight.significand().bitLength() + shift);
					units[i] = weight.significand().shiftLeft((int) shift);
				}
				sum = sum.add(units[i]); requireBits(sum.bitLength());
				if (units[i].compareTo(units[pivot]) > 0) pivot = i;
			}
			SignedDyadic source = SignedDyadic.of(total), assigned = SignedDyadic.ZERO;
			for (int i = 0; i < result.length; i++) if (i != pivot && units[i].signum() > 0) {
				result[i] = target(source, units[i], sum).toDouble();
				requirePositive(result[i]); assigned = assigned.add(SignedDyadic.of(result[i]));
			}
			SignedDyadic remainder = source.subtract(assigned);
			if (remainder.signum() <= 0) throw new GasTopologyBlockedException("Gas topology volume rounding exhausted its largest share");
			result[pivot] = remainder.toDouble(); requirePositive(result[pivot]);
			SignedDyadic error = SignedDyadic.of(result[pivot]).subtract(remainder);
			if (error.signum() < 0) error = error.negate();
			SignedDyadic halfUlp = SignedDyadic.of(Math.ulp(result[pivot])).multiply(SignedDyadic.of(1, -1));
			if (error.compareTo(halfUlp) > 0) throw new GasTopologyBlockedException("Gas topology volume remainder exceeds its rounding bound");
			return result;
		} catch (IllegalArgumentException | ArithmeticException magnitude) {
			throw new GasTopologyBlockedException("Gas topology volume partition exceeds its representation budget: " + magnitude.getMessage());
		}
	}

	private static ExactRational target(SignedDyadic source, BigInteger weight, BigInteger sum) {
		BigInteger numerator = source.significand().multiply(weight), denominator = sum;
		BigInteger divisor = numerator.gcd(denominator);
		numerator = numerator.divide(divisor); denominator = denominator.divide(divisor);
		int exponent = source.exponent();
		if (exponent > 0) {
			int cancelled = Math.min(exponent, denominator.getLowestSetBit());
			denominator = denominator.shiftRight(cancelled); exponent -= cancelled;
		} else if (exponent < 0) {
			int cancelled = Math.min(-exponent, numerator.getLowestSetBit());
			numerator = numerator.shiftRight(cancelled); exponent += cancelled;
		}
		requireBits((long) numerator.bitLength() + Math.max(exponent, 0));
		requireBits((long) denominator.bitLength() + Math.max(-exponent, 0));
		return exponent >= 0 ? new ExactRational(numerator.shiftLeft(exponent), denominator)
				: new ExactRational(numerator, denominator.shiftLeft(-exponent));
	}
	private static void requireBits(long bits) {
		if (bits > ChemicalLimits.MAX_RATIONAL_BITS) throw new GasTopologyBlockedException("Gas topology volume weights exceed the rational representation budget");
	}
	private static void requirePositive(double value) {
		if (!(Double.isFinite(value) && value > 0)) throw new GasTopologyBlockedException("Positive inherited gas volume has no finite positive representation");
	}
}
