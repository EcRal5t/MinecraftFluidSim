package com.example.chemistry;

import java.math.BigInteger;
import java.util.Arrays;

/** Largest-remainder allocation using the exact dyadic values represented by positive float volumes. */
public final class BinaryVolumeAllocator {
	private BinaryVolumeAllocator() { }

	public static long[] allocate(long total, float[] volumes, int[] markerOrdinals) {
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

	private static Dyadic dyadic(float value) {
		if (!(Float.isFinite(value) && value > 0.0F)) throw new IllegalArgumentException("Weight must be finite and positive");
		int bits = Float.floatToRawIntBits(value);
		int exponentBits = bits >>> 23 & 0xff;
		int fraction = bits & 0x7fffff;
		if (exponentBits == 0) return new Dyadic(fraction, -149);
		return new Dyadic((1 << 23) | fraction, exponentBits - 150);
	}

	private record Dyadic(int significand, int exponent) { }
}
