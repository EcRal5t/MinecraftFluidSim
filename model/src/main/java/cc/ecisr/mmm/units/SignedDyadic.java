package cc.ecisr.mmm.units;

import java.math.BigInteger;
import java.util.Objects;

/** Bounded exact binary rational for inventory transfers, energy accounting and persistence. */
public record SignedDyadic(BigInteger significand, int exponent) implements Comparable<SignedDyadic> {
	public static final SignedDyadic ZERO = new SignedDyadic(BigInteger.ZERO, 0);
	private static final int MIN_DOUBLE_EXPONENT = -1074;
	private static final int MAX_DOUBLE_EXPONENT = 971;
	private static final int DOUBLE_EXPONENT_BINS = MAX_DOUBLE_EXPONENT - MIN_DOUBLE_EXPONENT + 1;

	/** Significand bit budget that keeps exact sums inside a bounded storage and codec envelope. */
	public static final int MAX_SIGNIFICAND_BITS = 32_768;

	public SignedDyadic {
		Objects.requireNonNull(significand, "significand");
		if (significand.signum() == 0) exponent = 0;
		else {
			int trailing = significand.abs().getLowestSetBit();
			if (trailing > 0) {
				significand = significand.shiftRight(trailing);
				exponent = Math.addExact(exponent, trailing);
			}
			if (significand.bitLength() > MAX_SIGNIFICAND_BITS) {
				throw new IllegalArgumentException("Thermal dyadic significand is over budget");
			}
		}
	}

	public static SignedDyadic of(double value) {
		if (!Double.isFinite(value)) throw new IllegalArgumentException("Thermal audit value must be finite");
		long bits = Double.doubleToRawLongBits(value);
		long fraction = bits & 0x000f_ffff_ffff_ffffL;
		int rawExponent = (int) (bits >>> 52 & 0x7ffL);
		if (rawExponent == 0 && fraction == 0L) return ZERO;
		long rawSignificand;
		int exponent;
		if (rawExponent == 0) {
			rawSignificand = fraction;
			exponent = -1074;
		} else {
			rawSignificand = fraction | 0x0010_0000_0000_0000L;
			exponent = rawExponent - 1075;
		}
		int trailing = Long.numberOfTrailingZeros(rawSignificand);
		rawSignificand >>>= trailing;
		if ((bits & Long.MIN_VALUE) != 0L) rawSignificand = -rawSignificand;
		return new SignedDyadic(BigInteger.valueOf(rawSignificand), exponent + trailing);
	}

	public static SignedDyadic sum(double[] values) {
		return sum(values, values == null ? 0 : values.length);
	}

	/** Exact binary32 inventory sum using bounded exponent bins. */
	public static SignedDyadic sum(float[] values, int length) {
		if (values == null || length < 0 || length > values.length) throw new IllegalArgumentException("Invalid float inventory range");
		long[] bins = new long[254];
		int first = bins.length, last = -1;
		for (int i = 0; i < length; i++) {
			float value = values[i];
			if (!Float.isFinite(value)) throw new IllegalArgumentException("Float inventory is not finite");
			int bits = Float.floatToRawIntBits(value), fraction = bits & 0x7fffff, rawExponent = bits >>> 23 & 0xff;
			if (rawExponent == 0 && fraction == 0) continue;
			int bin = rawExponent == 0 ? 0 : rawExponent - 1;
			long significand = rawExponent == 0 ? fraction : fraction | 0x800000;
			bins[bin] += bits < 0 ? -significand : significand;
			first = Math.min(first, bin); last = Math.max(last, bin);
		}
		if (last < 0) return ZERO;
		BigInteger total = BigInteger.ZERO;
		for (int bin = first; bin <= last; bin++) if (bins[bin] != 0)
			total = total.add(BigInteger.valueOf(bins[bin]).shiftLeft(bin - first));
		return new SignedDyadic(total, -149 + first);
	}

	/**
	 * Exact finite sum without constructing and normalizing one {@link BigInteger} per value.
	 * IEEE-754 binary64 has only 2,046 possible dyadic exponents. Each exponent bin is accumulated
	 * in signed two's-complement 128-bit words (enough for every value in a Java array), then the
	 * occupied bins are combined once. The result is bit-for-bit identical to repeated exact add.
	 */
	public static SignedDyadic sum(double[] values, int length) {
		if (values == null || length < 0 || length > values.length) {
			throw new IllegalArgumentException("Thermal audit sum range is invalid");
		}
		long[] low = new long[DOUBLE_EXPONENT_BINS];
		long[] high = new long[DOUBLE_EXPONENT_BINS];
		int minimumBin = DOUBLE_EXPONENT_BINS;
		int maximumBin = -1;
		for (int index = 0; index < length; index++) {
			double value = values[index];
			if (!Double.isFinite(value)) throw new IllegalArgumentException("Thermal audit value must be finite");
			long bits = Double.doubleToRawLongBits(value);
			long fraction = bits & 0x000f_ffff_ffff_ffffL;
			int rawExponent = (int) (bits >>> 52 & 0x7ffL);
			if (rawExponent == 0 && fraction == 0L) continue;
			long significand = rawExponent == 0 ? fraction : fraction | 0x0010_0000_0000_0000L;
			int exponent = rawExponent == 0 ? MIN_DOUBLE_EXPONENT : rawExponent - 1075;
			if ((bits & Long.MIN_VALUE) != 0L) significand = -significand;
			int bin = exponent - MIN_DOUBLE_EXPONENT;
			long previousLow = low[bin];
			long nextLow = previousLow + significand;
			low[bin] = nextLow;
			high[bin] += (significand < 0L ? -1L : 0L)
					+ (Long.compareUnsigned(nextLow, previousLow) < 0 ? 1L : 0L);
			minimumBin = Math.min(minimumBin, bin);
			maximumBin = Math.max(maximumBin, bin);
		}
		if (maximumBin < 0) return ZERO;
		BigInteger significand = BigInteger.ZERO;
		for (int bin = minimumBin; bin <= maximumBin; bin++) {
			if (high[bin] == 0L && low[bin] == 0L) continue;
			BigInteger lower = BigInteger.valueOf(low[bin] & Long.MAX_VALUE);
			if (low[bin] < 0L) lower = lower.setBit(Long.SIZE - 1);
			BigInteger binValue = BigInteger.valueOf(high[bin]).shiftLeft(Long.SIZE).add(lower);
			significand = significand.add(binValue.shiftLeft(bin - minimumBin));
		}
		return new SignedDyadic(significand, MIN_DOUBLE_EXPONENT + minimumBin);
	}

	public SignedDyadic add(SignedDyadic other) { return combine(other, false); }
	public SignedDyadic subtract(SignedDyadic other) { return combine(other, true); }

	private SignedDyadic combine(SignedDyadic other, boolean subtract) {
		Objects.requireNonNull(other, "other");
		if (other.significand.signum() == 0) return this;
		if (significand.signum() == 0) return subtract ? other.negate() : other;
		int common = Math.min(exponent, other.exponent);
		long leftShift = (long) exponent - common, rightShift = (long) other.exponent - common;
		if (significand.abs().bitLength() + leftShift < Long.SIZE
				&& other.significand.abs().bitLength() + rightShift < Long.SIZE) {
			long left = significand.longValue() << (int) leftShift, right = other.significand.longValue() << (int) rightShift;
			try {
				long sum = subtract ? Math.subtractExact(left, right) : Math.addExact(left, right);
				if (sum == 0) return ZERO;
				int trailing = Long.numberOfTrailingZeros(sum);
				return new SignedDyadic(BigInteger.valueOf(sum >> trailing), Math.addExact(common, trailing));
			} catch (ArithmeticException overflow) {
				// A wide exact result keeps the same bounded BigInteger representation.
			}
		}
		BigInteger left = significand.shiftLeft(Math.subtractExact(exponent, common));
		BigInteger right = other.significand.shiftLeft(Math.subtractExact(other.exponent, common));
		return new SignedDyadic(subtract ? left.subtract(right) : left.add(right), common);
	}

	public SignedDyadic negate() { return significand.signum() == 0 ? ZERO : new SignedDyadic(significand.negate(), exponent); }

	public double toDouble() { return Math.scalb(significand.doubleValue(), exponent); }

	@Override
	public int compareTo(SignedDyadic other) {
		int sign = significand.signum(), otherSign = other.significand.signum();
		if (sign != otherSign) return Integer.compare(sign, otherSign);
		if (exponent == other.exponent) return significand.compareTo(other.significand);
		long magnitude = (long) exponent + significand.abs().bitLength();
		long otherMagnitude = (long) other.exponent + other.significand.abs().bitLength();
		if (magnitude != otherMagnitude) return sign * Long.compare(magnitude, otherMagnitude);
		int common = Math.min(exponent, other.exponent);
		return significand.shiftLeft(exponent - common)
				.compareTo(other.significand.shiftLeft(other.exponent - common));
	}
}
