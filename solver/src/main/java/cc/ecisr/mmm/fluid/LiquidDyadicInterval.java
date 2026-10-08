package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.math.BigInteger;

/** Outward dyadic intervals; every operation retains a bounded relative significand. */
record LiquidDyadicInterval(Fraction low, Fraction high, int precision) {
	LiquidDyadicInterval {
		if (precision < 2 || low == null || high == null || low.compareTo(high) > 0)
			throw new IllegalArgumentException("Invalid dyadic arithmetic interval");
	}
	static final class ResolutionRequired extends ArithmeticException {
		ResolutionRequired() { super("Pressure coefficient interval has unresolved denominator sign"); }
	}
	static LiquidDyadicInterval of(Fraction value, int precision) { return rounded(value, value, precision); }
	private static LiquidDyadicInterval rounded(Fraction low, Fraction high, int precision) {
		return new LiquidDyadicInterval(endpoint(low, precision, false), endpoint(high, precision, true), precision);
	}
	private static Fraction endpoint(Fraction value, int precision, boolean upper) {
		if (value.signum() == 0) return Fraction.ZERO;
		int exponent = value.numerator().abs().bitLength() - value.denominator().bitLength() - precision;
		var numerator = value.numerator(); var denominator = value.denominator();
		if (exponent < 0) numerator = numerator.shiftLeft(-exponent); else denominator = denominator.shiftLeft(exponent);
		var quotient = numerator.divideAndRemainder(denominator);
		if (quotient[1].signum() != 0 && (upper ? numerator.signum() > 0 : numerator.signum() < 0))
			quotient[0] = quotient[0].add(BigInteger.valueOf(upper ? 1 : -1));
		return exponent < 0 ? new Fraction(quotient[0], BigInteger.ONE.shiftLeft(-exponent))
				: new Fraction(quotient[0].shiftLeft(exponent), BigInteger.ONE);
	}
	LiquidDyadicInterval add(LiquidDyadicInterval other) {
		return rounded(low.add(other.low), high.add(other.high), precision);
	}
	LiquidDyadicInterval negate() { return new LiquidDyadicInterval(high.negate(), low.negate(), precision); }
	LiquidDyadicInterval subtract(LiquidDyadicInterval other) { return add(other.negate()); }
	LiquidDyadicInterval multiply(LiquidDyadicInterval other) {
		return enclosing(new Fraction[]{low.multiply(other.low), low.multiply(other.high), high.multiply(other.low), high.multiply(other.high)});
	}
	LiquidDyadicInterval divide(LiquidDyadicInterval other) {
		if (other.low.signum() <= 0 && other.high.signum() >= 0) throw new ResolutionRequired();
		return enclosing(new Fraction[]{low.divide(other.low), low.divide(other.high), high.divide(other.low), high.divide(other.high)});
	}
	private LiquidDyadicInterval enclosing(Fraction[] values) {
		var lower = values[0]; var upper = values[0];
		for (var value : values) { if (value.compareTo(lower) < 0) lower = value; if (value.compareTo(upper) > 0) upper = value; }
		return rounded(lower, upper, precision);
	}
	Fraction midpoint() { return low.add(high).divide(Fraction.of(2)); }
	/** Distance from an exact represented coefficient to every value in this interval. */
	double error(Fraction represented) {
		var first = low.subtract(represented).absolute(); var second = high.subtract(represented).absolute();
		var error = first.compareTo(second) >= 0 ? first : second;
		return error.signum() == 0 ? 0 : Math.nextUp(error.rounded());
	}
}
