package com.example.chemistry;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/** Canonical exact rational used by definition codecs and conservation validation. */
public record ExactRational(BigInteger numerator, BigInteger denominator) implements Comparable<ExactRational> {
	public static final ExactRational ZERO = new ExactRational(BigInteger.ZERO, BigInteger.ONE);
	public static final ExactRational ONE = new ExactRational(BigInteger.ONE, BigInteger.ONE);

	public ExactRational {
		Objects.requireNonNull(numerator, "numerator");
		Objects.requireNonNull(denominator, "denominator");
		if (denominator.signum() == 0) throw new IllegalArgumentException("Rational denominator is zero");
		if (denominator.signum() < 0) {
			numerator = numerator.negate();
			denominator = denominator.negate();
		}
		BigInteger divisor = numerator.gcd(denominator);
		numerator = numerator.divide(divisor);
		denominator = denominator.divide(divisor);
	}

	public static ExactRational of(long value) {
		return new ExactRational(BigInteger.valueOf(value), BigInteger.ONE);
	}

	/** Exact rational represented by a finite IEEE-754 float. */
	public static ExactRational ofFloat(float value) {
		if (!Float.isFinite(value)) throw new IllegalArgumentException("Float rational must be finite");
		int bits = Float.floatToRawIntBits(value);
		boolean negative = (bits & 0x8000_0000) != 0;
		int exponent = bits >>> 23 & 0xff;
		int significand = bits & 0x7f_ffff;
		if (exponent != 0) significand |= 1 << 23;
		if (negative) significand = -significand;
		int power = exponent == 0 ? -149 : exponent - 150;
		return power >= 0
				? new ExactRational(BigInteger.valueOf(significand).shiftLeft(power), BigInteger.ONE)
				: new ExactRational(BigInteger.valueOf(significand), BigInteger.ONE.shiftLeft(-power));
	}

	/** Accepts an integer, a finite decimal, or a numerator/denominator pair. */
	public static ExactRational parse(String value) {
		if (value == null || value.isBlank() || !value.equals(value.trim())) {
			throw new IllegalArgumentException("Rational text must be non-blank and trimmed");
		}
		int slash = value.indexOf('/');
		try {
			if (slash >= 0) {
				if (slash != value.lastIndexOf('/') || slash == 0 || slash + 1 == value.length()) {
					throw new IllegalArgumentException("Malformed rational " + value);
				}
				return new ExactRational(new BigInteger(value.substring(0, slash)),
						new BigInteger(value.substring(slash + 1)));
			}
			BigDecimal decimal = new BigDecimal(value);
			BigInteger denominator = decimal.scale() <= 0
					? BigInteger.ONE : BigInteger.TEN.pow(decimal.scale());
			BigInteger numerator = decimal.unscaledValue();
			if (decimal.scale() < 0) numerator = numerator.multiply(BigInteger.TEN.pow(-decimal.scale()));
			return new ExactRational(numerator, denominator);
		} catch (NumberFormatException invalid) {
			throw new IllegalArgumentException("Malformed rational " + value, invalid);
		}
	}

	public ExactRational add(ExactRational other) {
		return new ExactRational(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
				denominator.multiply(other.denominator));
	}

	public ExactRational subtract(ExactRational other) {
		return add(new ExactRational(other.numerator.negate(), other.denominator));
	}

	public ExactRational multiply(long value) {
		return new ExactRational(numerator.multiply(BigInteger.valueOf(value)), denominator);
	}

	public ExactRational multiply(ExactRational other) {
		return new ExactRational(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
	}

	public ExactRational divide(long value) {
		if (value == 0L) throw new IllegalArgumentException("Division by zero");
		return new ExactRational(numerator, denominator.multiply(BigInteger.valueOf(value)));
	}

	public int signum() { return numerator.signum(); }
	public boolean isZero() { return numerator.signum() == 0; }

	public double toDouble() {
		return numerator.doubleValue() / denominator.doubleValue();
	}

	public String canonical() {
		return denominator.equals(BigInteger.ONE) ? numerator.toString() : numerator + "/" + denominator;
	}

	@Override
	public int compareTo(ExactRational other) {
		return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
	}
}
