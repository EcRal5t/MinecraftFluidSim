package cc.ecisr.mmm.chemistry;

import java.math.BigInteger;
import java.util.Arrays;

/** Largest-remainder allocation using exact dyadic values of positive volume or mass weights. */
public final class BinaryVolumeAllocator {
	private BinaryVolumeAllocator() { }

	public static long[] allocate(long total, float[] volumes, int[] markerOrdinals) {
		if (volumes == null) throw new IllegalArgumentException("Allocation weights are missing");
		double[] weights = new double[volumes.length];
		for (int i = 0; i < weights.length; i++) weights[i] = volumes[i];
		return allocate(total, weights, markerOrdinals);
	}

	/** Preserves the full binary64 range of physical mass weights. */
	public static long[] allocate(long total, double[] volumes, int[] markerOrdinals) {
		if (total < 0L || volumes == null || markerOrdinals == null || volumes.length == 0
				|| volumes.length != markerOrdinals.length) {
			throw new IllegalArgumentException("Invalid binary-volume allocation");
		}
		Dyadic[] dyadics = new Dyadic[volumes.length];
		int minimumExponent = Integer.MAX_VALUE;
		for (int i = 0; i < volumes.length; i++) {
			dyadics[i] = dyadic(volumes[i]);
			minimumExponent = Math.min(minimumExponent, dyadics[i].exponent());
		}
		BigInteger[] weights = new BigInteger[volumes.length];
		BigInteger denominator = BigInteger.ZERO;
		for (int i = 0; i < weights.length; i++) {
			weights[i] = BigInteger.valueOf(dyadics[i].significand()).shiftLeft(dyadics[i].exponent() - minimumExponent);
			denominator = denominator.add(weights[i]);
		}
		long[] result = new long[volumes.length];
		BigInteger[] remainders = new BigInteger[volumes.length];
		long assigned = 0L;
		for (int i = 0; i < result.length; i++) {
			BigInteger[] quotient = weights[i].multiply(BigInteger.valueOf(total)).divideAndRemainder(denominator);
			result[i] = quotient[0].longValueExact();
			remainders[i] = quotient[1];
			assigned = Math.addExact(assigned, result[i]);
		}
		int remaining = Math.toIntExact(total - assigned);
		Integer[] order = new Integer[volumes.length];
		for (int i = 0; i < order.length; i++) order[i] = i;
		Arrays.sort(order, (left, right) -> {
			int remainder = remainders[right].compareTo(remainders[left]);
			return remainder != 0 ? remainder : Integer.compare(markerOrdinals[left], markerOrdinals[right]);
		});
		for (int i = 0; i < remaining; i++) result[order[i]]++;
		return result;
	}

	private static Dyadic dyadic(double value) {
		if (!(Double.isFinite(value) && value > 0.0)) throw new IllegalArgumentException("Weight must be finite and positive");
		long bits = Double.doubleToRawLongBits(value);
		int exponentBits = (int) (bits >>> 52 & 0x7ff);
		long fraction = bits & 0xfffffffffffffL;
		if (exponentBits == 0) return new Dyadic(fraction, -1074);
		return new Dyadic((1L << 52) | fraction, exponentBits - 1075);
	}

	private record Dyadic(long significand, int exponent) { }
}
