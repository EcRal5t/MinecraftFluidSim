package mmm.chemistry;

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
		if (numerator.bitLength() > ChemicalLimits.MAX_RATIONAL_BITS
				|| denominator.bitLength() > ChemicalLimits.MAX_RATIONAL_BITS) {
			throw new IllegalArgumentException("Rational magnitude is over budget");
		}
		if (denominator.signum() == 0) throw new IllegalArgumentException("Rational denominator is zero");
		if (denominator.signum() < 0) {
			numerator = numerator.negate();
			denominator = denominator.negate();
		}
		if (numerator.signum() == 0) {
			denominator = BigInteger.ONE;
		} else if (!denominator.equals(BigInteger.ONE)) {
			BigInteger divisor;
			if (numerator.abs().bitLength() < 64 && denominator.bitLength() < 64) {
				long a = Math.abs(numerator.longValueExact()), b = denominator.longValueExact();
				while (b != 0) { long remainder = a % b; a = b; b = remainder; }
				divisor = BigInteger.valueOf(a);
			} else divisor = numerator.gcd(denominator);
			if (!divisor.equals(BigInteger.ONE)) {
				numerator = numerator.divide(divisor);
				denominator = denominator.divide(divisor);
			}
		}
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
		if (value.length() > ChemicalLimits.MAX_RATIONAL_TEXT_LENGTH) {
			throw new IllegalArgumentException("Rational text is over budget");
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
			if (Math.abs((long) decimal.scale()) > ChemicalLimits.MAX_RATIONAL_SCALE) {
				throw new IllegalArgumentException("Rational decimal scale is over budget");
			}
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
		if (isZero()) return other;
		if (other.isZero()) return this;
		BigInteger common = denominator.gcd(other.denominator);
		BigInteger first = denominator.divide(common), second = other.denominator.divide(common);
		BigInteger sum = numerator.multiply(second).add(other.numerator.multiply(first));
		if (sum.signum() == 0) return ZERO;
		// Canonical inputs leave possible numerator cancellation only in the shared denominator factor.
		BigInteger divisor = sum.gcd(common);
		return new ExactRational(sum.divide(divisor), first.multiply(other.denominator.divide(divisor)));
	}

	public ExactRational subtract(ExactRational other) {
		return add(new ExactRational(other.numerator.negate(), other.denominator));
	}

	public ExactRational multiply(long value) {
		if (value == 0 || isZero()) return ZERO;
		if (value == 1) return this;
		return new ExactRational(numerator.multiply(BigInteger.valueOf(value)), denominator);
	}

	public ExactRational multiply(ExactRational other) {
		if (isZero() || other.isZero()) return ZERO;
		if (equals(ONE)) return other;
		if (other.equals(ONE)) return this;
		return new ExactRational(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
	}

	public ExactRational divide(long value) {
		if (value == 0L) throw new IllegalArgumentException("Division by zero");
		if (value == 1 || isZero()) return this;
		return new ExactRational(numerator, denominator.multiply(BigInteger.valueOf(value)));
	}

	public int signum() { return numerator.signum(); }
	public boolean isZero() { return numerator.signum() == 0; }

	/** Rounds the exact quotient once to the final binary64 unit, with ties to even. */
	public double toDouble() {
		int sign = numerator.signum();
		if (sign == 0) return 0.0;
		BigInteger magnitude = numerator.abs();
		if (magnitude.bitLength() <= 53 && denominator.bitLength() <= 53) {
			return numerator.doubleValue() / denominator.doubleValue();
		}
		int highestExponent = magnitude.bitLength() - denominator.bitLength();
		int order = highestExponent >= 0
				? magnitude.compareTo(denominator.shiftLeft(highestExponent))
				: magnitude.shiftLeft(-highestExponent).compareTo(denominator);
		if (order < 0) highestExponent--;
		if (highestExponent > 1023) return sign < 0 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
		if (highestExponent < -1075) return sign < 0 ? -0.0 : 0.0;
		int unitExponent = Math.max(-1074, highestExponent - 52);
		BigInteger scaledNumerator = unitExponent < 0 ? magnitude.shiftLeft(-unitExponent) : magnitude;
		BigInteger scaledDenominator = unitExponent > 0 ? denominator.shiftLeft(unitExponent) : denominator;
		BigInteger[] quotient = scaledNumerator.divideAndRemainder(scaledDenominator);
		int halfway = quotient[1].shiftLeft(1).compareTo(scaledDenominator);
		BigInteger rounded = halfway > 0 || halfway == 0 && quotient[0].testBit(0)
				? quotient[0].add(BigInteger.ONE) : quotient[0];
		// At most 53 bits remain, or the exact power 2^53 after a rounding carry.
		double value = Math.scalb(rounded.doubleValue(), unitExponent);
		return sign < 0 ? -value : value;
	}

	public String canonical() {
		return denominator.equals(BigInteger.ONE) ? numerator.toString() : numerator + "/" + denominator;
	}

	@Override
	public int compareTo(ExactRational other) {
		return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
	}
}
