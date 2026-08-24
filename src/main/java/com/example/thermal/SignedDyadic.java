package com.example.thermal;

import java.math.BigInteger;
import java.util.Objects;

/** Exact finite IEEE-754 sum used at thermal commit and persistence boundaries, never in the face hot loop. */
public record SignedDyadic(BigInteger significand, int exponent) implements Comparable<SignedDyadic> {
	public static final SignedDyadic ZERO = new SignedDyadic(BigInteger.ZERO, 0);

	public SignedDyadic {
		Objects.requireNonNull(significand, "significand");
		if (significand.signum() == 0) exponent = 0;
		else {
			int trailing = significand.abs().getLowestSetBit();
			if (trailing > 0) {
				significand = significand.shiftRight(trailing);
				exponent = Math.addExact(exponent, trailing);
			}
			if (significand.bitLength() > ThermalLimits.MAX_DYADIC_SIGNIFICAND_BITS) {
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
		BigInteger significand;
		int exponent;
		if (rawExponent == 0) {
			significand = BigInteger.valueOf(fraction);
			exponent = -1074;
		} else {
			significand = BigInteger.valueOf(fraction | 0x0010_0000_0000_0000L);
			exponent = rawExponent - 1075;
		}
		if ((bits & Long.MIN_VALUE) != 0L) significand = significand.negate();
		return new SignedDyadic(significand, exponent);
	}

	public static SignedDyadic sum(double[] values) {
		SignedDyadic result = ZERO;
		for (double value : values) result = result.add(of(value));
		return result;
	}

	public SignedDyadic add(SignedDyadic other) {
		Objects.requireNonNull(other, "other");
		if (significand.signum() == 0) return other;
		if (other.significand.signum() == 0) return this;
		int common = Math.min(exponent, other.exponent);
		BigInteger left = significand.shiftLeft(Math.subtractExact(exponent, common));
		BigInteger right = other.significand.shiftLeft(Math.subtractExact(other.exponent, common));
		return new SignedDyadic(left.add(right), common);
	}

	public SignedDyadic subtract(SignedDyadic other) {
		return add(new SignedDyadic(other.significand.negate(), other.exponent));
	}

	public SignedDyadic negate() { return new SignedDyadic(significand.negate(), exponent); }

	public double toDouble() { return Math.scalb(significand.doubleValue(), exponent); }

	@Override
	public int compareTo(SignedDyadic other) {
		if (significand.signum() != other.significand.signum()) {
			return Integer.compare(significand.signum(), other.significand.signum());
		}
		int common = Math.min(exponent, other.exponent);
		return significand.shiftLeft(exponent - common)
				.compareTo(other.significand.shiftLeft(other.exponent - common));
	}
}
