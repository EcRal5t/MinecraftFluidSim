package cc.ecisr.mmm.units;

import java.math.BigInteger;
import java.util.Objects;

/** Bounded exact binary rational for inventory transfers, energy accounting and persistence. */
public final class SignedDyadic implements Comparable<SignedDyadic> {
	private final int exponent, magnitudeBits, sign;
	private final long compact;
	private volatile BigInteger significand;
	public static final SignedDyadic ZERO = new SignedDyadic(BigInteger.ZERO, 0);
	private static final int MIN_DOUBLE_EXPONENT = -1074;
	private static final int MAX_DOUBLE_EXPONENT = 971;

	/** Significand bit budget that keeps exact sums inside a bounded storage and codec envelope. */
	public static final int MAX_SIGNIFICAND_BITS = 32_768;

	public SignedDyadic(BigInteger significand, int exponent) {
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
		this.exponent = exponent;
		this.sign = significand.signum();
		this.magnitudeBits = significand.abs().bitLength();
		this.compact = magnitudeBits < Long.SIZE ? significand.longValue() : 0;
		this.significand = significand;
	}

	/** Canonical small integers stay in machine words until a codec needs the wide view. */
	private SignedDyadic(long significand, int exponent) {
		int trailing = significand == 0 ? 0 : Long.numberOfTrailingZeros(significand);
		compact = significand >> trailing;
		this.exponent = significand == 0 ? 0 : Math.addExact(exponent, trailing);
		magnitudeBits = Long.SIZE - Long.numberOfLeadingZeros(Math.abs(compact));
		sign = Long.compare(compact, 0);
	}

	public static SignedDyadic of(long significand, int exponent) {
		return significand == 0 ? ZERO : new SignedDyadic(significand, exponent);
	}

	/** Whether the canonical value is an exact signed integer in the specified binary unit. */
	public boolean fitsLongUnits(int unitExponent) {
		long shift = (long) exponent - unitExponent;
		return sign == 0 || shift >= 0 && (magnitudeBits + shift < Long.SIZE || compact == -1 && shift == 63);
	}

	public long longUnitsExact(int unitExponent) {
		if (sign == 0) return 0;
		int shift = Math.subtractExact(exponent, unitExponent);
		if (shift < 0) throw new ArithmeticException("Dyadic is fractional in the requested binary unit");
		return fitsLongUnits(unitExponent) ? compact << shift : significand().shiftLeft(shift).longValueExact();
	}

	public int exponent() { return exponent; }
	public int signum() { return sign; }
	public BigInteger significand() {
		BigInteger value = significand;
		if (value == null) significand = value = BigInteger.valueOf(compact);
		return value;
	}

	@Override public boolean equals(Object value) {
		if (this == value) return true;
		if (!(value instanceof SignedDyadic other) || exponent != other.exponent || magnitudeBits != other.magnitudeBits || sign != other.sign) return false;
		return magnitudeBits < Long.SIZE ? compact == other.compact : significand().equals(other.significand());
	}
	@Override public int hashCode() { return 31 * significand().hashCode() + exponent; }
	@Override public String toString() { return "SignedDyadic[significand=" + significand() + ", exponent=" + exponent + "]"; }

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
		return new SignedDyadic(rawSignificand, exponent + trailing);
	}

	public static SignedDyadic sum(double[] values) {
		return sum(values, values == null ? 0 : values.length);
	}

	/** Exact binary32 inventory sum using bounded exponent bins. */
	public static SignedDyadic sum(float[] values, int length) {
		if (values == null || length < 0 || length > values.length) throw new IllegalArgumentException("Invalid float inventory range");
		if (length == 0) return ZERO;
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
	 * Two passes restrict storage to the occupied span of binary64's 2,046 dyadic exponents. Each bin is accumulated
	 * in signed two's-complement 128-bit words (enough for every value in a Java array), then the
	 * occupied bins are combined once. The result is bit-for-bit identical to repeated exact add.
	 */
	public static SignedDyadic sum(double[] values, int length) {
		if (values == null || length < 0 || length > values.length) {
			throw new IllegalArgumentException("Thermal audit sum range is invalid");
		}
		if (length == 0) return ZERO;
		int minimumExponent = MAX_DOUBLE_EXPONENT + 1, maximumExponent = MIN_DOUBLE_EXPONENT - 1;
		for (int index = 0; index < length; index++) {
			double value = values[index];
			if (!Double.isFinite(value)) throw new IllegalArgumentException("Thermal audit value must be finite");
			long bits = Double.doubleToRawLongBits(value);
			if ((bits & Long.MAX_VALUE) == 0) continue;
			int rawExponent = (int) (bits >>> 52 & 0x7ffL);
			int exponent = rawExponent == 0 ? MIN_DOUBLE_EXPONENT : rawExponent - 1075;
			minimumExponent = Math.min(minimumExponent, exponent);
			maximumExponent = Math.max(maximumExponent, exponent);
		}
		if (maximumExponent < minimumExponent) return ZERO;
		// Bound every aligned partial sum, including cancellation, before using one signed word.
		int countBits = Integer.SIZE - Integer.numberOfLeadingZeros(length - 1);
		if (53 + maximumExponent - minimumExponent + countBits <= 63) {
			long total = 0;
			for (int index = 0; index < length; index++) {
				long bits = Double.doubleToRawLongBits(values[index]);
				long fraction = bits & 0x000f_ffff_ffff_ffffL;
				int rawExponent = (int) (bits >>> 52 & 0x7ffL);
				if (rawExponent == 0 && fraction == 0) continue;
				long significand = rawExponent == 0 ? fraction : fraction | 0x0010_0000_0000_0000L;
				int exponent = rawExponent == 0 ? MIN_DOUBLE_EXPONENT : rawExponent - 1075;
				long aligned = significand << (exponent - minimumExponent);
				total += bits < 0 ? -aligned : aligned;
			}
			return total == 0 ? ZERO : new SignedDyadic(total, minimumExponent);
		}
		long[] low = new long[maximumExponent - minimumExponent + 1];
		long[] high = new long[low.length];
		for (int index = 0; index < length; index++) {
			double value = values[index];
			long bits = Double.doubleToRawLongBits(value);
			long fraction = bits & 0x000f_ffff_ffff_ffffL;
			int rawExponent = (int) (bits >>> 52 & 0x7ffL);
			if (rawExponent == 0 && fraction == 0L) continue;
			long significand = rawExponent == 0 ? fraction : fraction | 0x0010_0000_0000_0000L;
			int exponent = rawExponent == 0 ? MIN_DOUBLE_EXPONENT : rawExponent - 1075;
			if ((bits & Long.MIN_VALUE) != 0L) significand = -significand;
			int bin = exponent - minimumExponent;
			long previousLow = low[bin];
			long nextLow = previousLow + significand;
			low[bin] = nextLow;
			high[bin] += (significand < 0L ? -1L : 0L)
					+ (Long.compareUnsigned(nextLow, previousLow) < 0 ? 1L : 0L);
		}
		BigInteger significand = BigInteger.ZERO;
		for (int bin = 0; bin < low.length; bin++) {
			if (high[bin] == 0L && low[bin] == 0L) continue;
			BigInteger lower = BigInteger.valueOf(low[bin] & Long.MAX_VALUE);
			if (low[bin] < 0L) lower = lower.setBit(Long.SIZE - 1);
			BigInteger binValue = BigInteger.valueOf(high[bin]).shiftLeft(Long.SIZE).add(lower);
			significand = significand.add(binValue.shiftLeft(bin));
		}
		return new SignedDyadic(significand, minimumExponent);
	}

	public SignedDyadic add(SignedDyadic other) { return combine(other, false); }
	public SignedDyadic subtract(SignedDyadic other) { return combine(other, true); }

	/** Exact product in the same bounded canonical representation. */
	public SignedDyadic multiply(SignedDyadic other) {
		Objects.requireNonNull(other, "other");
		if (sign == 0 || other.sign == 0) return ZERO;
		return new SignedDyadic(significand().multiply(other.significand()), Math.addExact(exponent, other.exponent));
	}

	private SignedDyadic combine(SignedDyadic other, boolean subtract) {
		Objects.requireNonNull(other, "other");
		if (other.sign == 0) return this;
		if (sign == 0) return subtract ? other.negate() : other;
		int common = Math.min(exponent, other.exponent);
		long leftShift = (long) exponent - common, rightShift = (long) other.exponent - common;
		if (magnitudeBits + leftShift < Long.SIZE && other.magnitudeBits + rightShift < Long.SIZE) {
			long left = compact << (int) leftShift, right = other.compact << (int) rightShift;
			try {
				long sum = subtract ? Math.subtractExact(left, right) : Math.addExact(left, right);
				return sum == 0 ? ZERO : new SignedDyadic(sum, common);
			} catch (ArithmeticException overflow) {
				// A wide exact result keeps the same bounded BigInteger representation.
			}
		}
		BigInteger left = significand().shiftLeft(Math.subtractExact(exponent, common));
		BigInteger right = other.significand().shiftLeft(Math.subtractExact(other.exponent, common));
		return new SignedDyadic(subtract ? left.subtract(right) : left.add(right), common);
	}

	public SignedDyadic negate() {
		return sign == 0 ? ZERO : magnitudeBits < Long.SIZE ? new SignedDyadic(-compact, exponent) : new SignedDyadic(significand().negate(), exponent);
	}

	/** One ties-to-even rounding in the final binary64 unit, including subnormal values. */
	public double toDouble() {
		if (sign == 0) return 0.0;
		if (magnitudeBits <= 53 && exponent >= -1074) return Math.scalb((double) compact, exponent);
		long highestExponent = (long) exponent + magnitudeBits - 1;
		if (highestExponent > 1023) return sign < 0 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
		if (highestExponent < -1075) return sign < 0 ? -0.0 : 0.0;
		int unitExponent = (int) Math.max(-1074, highestExponent - 52);
		BigInteger magnitude = significand().abs();
		int discardedBits = Math.subtractExact(unitExponent, exponent);
		BigInteger rounded;
		if (discardedBits <= 0) rounded = magnitude.shiftLeft(-discardedBits);
		else {
			rounded = magnitude.shiftRight(discardedBits);
			boolean halfwayBit = magnitude.testBit(discardedBits - 1);
			boolean lowerBits = magnitude.getLowestSetBit() < discardedBits - 1;
			if (halfwayBit && (lowerBits || rounded.testBit(0))) rounded = rounded.add(BigInteger.ONE);
		}
		// Rounded is at most 53 bits, or the exact power 2^53 after a carry.
		double value = Math.scalb(rounded.doubleValue(), unitExponent);
		return sign < 0 ? -value : value;
	}

	@Override
	public int compareTo(SignedDyadic other) {
		if (sign != other.sign) return Integer.compare(sign, other.sign);
		if (exponent == other.exponent) return magnitudeBits < Long.SIZE && other.magnitudeBits < Long.SIZE
				? Long.compare(compact, other.compact) : significand().compareTo(other.significand());
		long magnitude = (long) exponent + magnitudeBits;
		long otherMagnitude = (long) other.exponent + other.magnitudeBits;
		if (magnitude != otherMagnitude) return sign * Long.compare(magnitude, otherMagnitude);
		int common = Math.min(exponent, other.exponent);
		long leftShift = (long) exponent - common, rightShift = (long) other.exponent - common;
		if (magnitudeBits + leftShift < Long.SIZE && other.magnitudeBits + rightShift < Long.SIZE)
			return Long.compare(compact << (int) leftShift, other.compact << (int) rightShift);
		return significand().shiftLeft(Math.subtractExact(exponent, common))
				.compareTo(other.significand().shiftLeft(Math.subtractExact(other.exponent, common)));
	}
}
