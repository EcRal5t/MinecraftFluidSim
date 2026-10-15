package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Exact positive affine rate integrated over a half-cell tangential rectangle. */
final class MicroFaceAffineIntegral {
	private static final SignedDyadic HALF = SignedDyadic.of(.5);
	private static final SignedDyadic EIGHTH = SignedDyadic.of(.125);
	private final SignedDyadic intercept, tangentStep, otherStep, tangentExtent, otherExtent, denominator;
	private final int axes;

	MicroFaceAffineIntegral(double intercept, double tangentSlope, double otherSlope, boolean positive) {
		var sign = SignedDyadic.of(positive ? 1 : -1);
		this.intercept = SignedDyadic.of(intercept).multiply(sign);
		tangentStep = SignedDyadic.of(tangentSlope).multiply(sign).multiply(HALF);
		otherStep = SignedDyadic.of(otherSlope).multiply(sign).multiply(HALF);
		tangentExtent = tangentStep.signum() < 0 ? tangentStep.negate() : tangentStep;
		otherExtent = otherStep.signum() < 0 ? otherStep.negate() : otherStep;
		axes = (tangentStep.signum() == 0 ? 0 : 1) + (otherStep.signum() == 0 ? 0 : 1);
		var divisor = SignedDyadic.of(axes == 2 ? 24 : axes == 1 ? 8 : 4);
		if (tangentExtent.signum() != 0) divisor = divisor.multiply(tangentExtent);
		if (otherExtent.signum() != 0) divisor = divisor.multiply(otherExtent);
		denominator = divisor;
	}

	ExactRational integrate(int tangentBit, int otherBit) {
		var minimum = intercept;
		if (tangentBit != 0) minimum = minimum.add(tangentStep);
		if (otherBit != 0) minimum = minimum.add(otherStep);
		if (tangentStep.signum() < 0) minimum = minimum.add(tangentStep);
		if (otherStep.signum() < 0) minimum = minimum.add(otherStep);
		var maximum = minimum.add(tangentExtent).add(otherExtent);
		if (maximum.signum() <= 0) return ExactRational.ZERO;
		if (minimum.signum() >= 0)
			return MicroBoxPlaneVolume.quotient(minimum.add(maximum).multiply(EIGHTH), SignedDyadic.of(1));
		// Integrating the box CDF once gives the positive-part integral: a (d+1)-power finite difference.
		var numerator = power(maximum);
		if (tangentExtent.signum() != 0) numerator = numerator.subtract(power(maximum.subtract(tangentExtent)));
		if (otherExtent.signum() != 0) numerator = numerator.subtract(power(maximum.subtract(otherExtent)));
		if (axes == 2) numerator = numerator.add(power(minimum));
		return MicroBoxPlaneVolume.quotient(numerator, denominator);
	}

	private SignedDyadic power(SignedDyadic value) {
		if (value.signum() <= 0) return SignedDyadic.ZERO;
		var result = value.multiply(value);
		return axes == 2 ? result.multiply(value) : result;
	}
}
