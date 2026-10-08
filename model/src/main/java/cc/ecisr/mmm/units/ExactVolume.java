package cc.ecisr.mmm.units;

import java.math.BigInteger;
import java.util.Objects;

/** Bounded canonical volume integral; floating-point columns observe the complete ratio once. */
public record ExactVolume(BigInteger numerator, BigInteger denominator) implements Comparable<ExactVolume> {
	public static final int MAX_COMPONENT_BITS = 32_768;
	public static final ExactVolume ZERO = new ExactVolume(BigInteger.ZERO, BigInteger.ONE);
	public ExactVolume {
		Objects.requireNonNull(numerator); Objects.requireNonNull(denominator);
		if (denominator.signum() == 0) throw new IllegalArgumentException("Volume denominator is zero");
		var divisor = numerator.gcd(denominator); if (denominator.signum() < 0) divisor = divisor.negate();
		numerator = numerator.divide(divisor); denominator = denominator.divide(divisor);
		if (numerator.abs().bitLength() > MAX_COMPONENT_BITS || denominator.bitLength() > MAX_COMPONENT_BITS)
			throw new IllegalArgumentException("Exact volume exceeds its quantity budget");
	}
	public static ExactVolume of(double value) { return of(SignedDyadic.of(value)); }
	public static ExactVolume of(SignedDyadic value) {
		if (value.signum() == 0) return ZERO;
		long numeratorBits = value.significand().abs().bitLength() + Math.max(0L, value.exponent());
		long denominatorBits = 1 + Math.max(0L, -(long) value.exponent());
		if (numeratorBits > MAX_COMPONENT_BITS || denominatorBits > MAX_COMPONENT_BITS)
			throw new IllegalArgumentException("Exact volume exceeds its quantity budget");
		return value.exponent() >= 0 ? new ExactVolume(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
				: new ExactVolume(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent()));
	}
	public int signum() { return numerator.signum(); }
	public ExactVolume add(ExactVolume other) {
		if (signum() == 0) return other; if (other.signum() == 0) return this;
		var common = denominator.gcd(other.denominator); var first = denominator.divide(common); var second = other.denominator.divide(common);
		return new ExactVolume(numerator.multiply(second).add(other.numerator.multiply(first)), first.multiply(other.denominator));
	}
	public ExactVolume add(SignedDyadic other) { return add(of(other)); }
	public ExactVolume subtract(ExactVolume other) { return add(other.negate()); }
	public ExactVolume subtract(SignedDyadic other) { return subtract(of(other)); }
	public ExactVolume negate() { return signum() == 0 ? this : new ExactVolume(numerator.negate(), denominator); }
	public ExactVolume multiply(ExactVolume other) {
		var first = numerator.gcd(other.denominator); var second = other.numerator.gcd(denominator);
		return new ExactVolume(numerator.divide(first).multiply(other.numerator.divide(second)), denominator.divide(second).multiply(other.denominator.divide(first)));
	}
	public ExactVolume multiply(SignedDyadic other) { return multiply(of(other)); }
	public ExactVolume divide(ExactVolume other) {
		if (other.signum() == 0) throw new IllegalArgumentException("Volume division by zero");
		return multiply(new ExactVolume(other.denominator, other.numerator));
	}
	@Override public int compareTo(ExactVolume other) {
		return denominator.equals(other.denominator) ? numerator.compareTo(other.numerator) : numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
	}
	public int compareTo(SignedDyadic other) { return compareTo(of(other)); }
	public double toDouble() { return observation(52, -1074, 1023); }
	public float toFloat() { return (float) observation(23, -149, 127); }
	private double observation(int fractionBits, int minimumUnitExponent, int maximumExponent) {
		if (signum() == 0) return 0;
		var magnitude = numerator.abs(); int exponent = magnitude.bitLength() - denominator.bitLength();
		if ((exponent >= 0 ? magnitude.compareTo(denominator.shiftLeft(exponent)) : magnitude.shiftLeft(-exponent).compareTo(denominator)) < 0) exponent--;
		if (exponent > maximumExponent) return Math.copySign(Double.POSITIVE_INFINITY, signum());
		if (exponent < minimumUnitExponent - 1) return Math.copySign(0.0, signum());
		int shift = Math.min(fractionBits - exponent, -minimumUnitExponent);
		var dividend = shift >= 0 ? magnitude.shiftLeft(shift) : magnitude;
		var divisor = shift >= 0 ? denominator : denominator.shiftLeft(-shift);
		var division = dividend.divideAndRemainder(divisor); int comparison = division[1].shiftLeft(1).compareTo(divisor);
		if (comparison > 0 || comparison == 0 && division[0].testBit(0)) division[0] = division[0].add(BigInteger.ONE);
		return Math.copySign(Math.scalb(division[0].doubleValue(), -shift), signum());
	}
}
