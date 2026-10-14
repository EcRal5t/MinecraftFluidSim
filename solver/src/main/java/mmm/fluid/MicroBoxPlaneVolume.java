package mmm.fluid;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Exact one-plane microbox volumes in whole-cell units, using the box CDF finite differences. */
final class MicroBoxPlaneVolume {
	private static final SignedDyadic HALF = SignedDyadic.of(.5);
	private static final ExactRational MICRO_VOLUME = ExactRational.ONE.divide(8);
	private MicroBoxPlaneVolume() { }

	/** One normal's selected-box CDF: every leaf has the same exact denominator. */
	static final class SelectedVolume {
		private final SignedDyadic[] minimum, shifts;
		private final SignedDyadic extentSum, denominator, fullNumerator;
		private final int axes;
		private final ExactRational maximum;

		SelectedVolume(int supportMask, SignedDyadic[] normal) {
			if (supportMask <= 0 || supportMask > 255 || normal == null || normal.length != 3)
				throw new IllegalArgumentException("Invalid selected microbox CDF input");
			// The eight selected microboxes tile the unit cube.
			boolean whole = supportMask == 255;
			SignedDyadic[] extents = new SignedDyadic[3];
			int count = 0; SignedDyadic sum = SignedDyadic.ZERO, divisor = SignedDyadic.of(whole ? 1 : 8);
			for (var n : normal) {
				requireBudget(n);
				if (n.signum() != 0) {
					SignedDyadic extent = whole ? n : n.multiply(HALF); if (extent.signum() < 0) extent = extent.negate();
					extents[count++] = extent; sum = sum.add(extent); divisor = divisor.multiply(extent);
				}
			}
			if (count == 0) throw new IllegalArgumentException("Selected microbox CDF needs a nonconstant normal");
			axes = count; extentSum = sum;
			denominator = divisor.multiply(SignedDyadic.of(axes == 3 ? 6 : axes));
			fullNumerator = whole ? denominator : denominator.multiply(SignedDyadic.of(1, -3));
			minimum = new SignedDyadic[whole ? 1 : Integer.bitCount(supportMask)]; int index = 0;
			for (int micro = 0; micro < (whole ? 1 : 8); micro++) if (whole || (supportMask & 1 << micro) != 0) {
				SignedDyadic low = SignedDyadic.ZERO;
				for (int axis = 0; axis < 3; axis++) low = low.add(normal[axis]
						.multiply(SignedDyadic.of((micro >>> axis & 1) + (normal[axis].signum() < 0 ? 1 : 0), whole ? 0 : -1)));
				minimum[index++] = low;
			}
			shifts = new SignedDyadic[1 << axes];
			for (int subset = 0; subset < shifts.length; subset++) {
				SignedDyadic shift = SignedDyadic.ZERO;
				for (int axis = 0; axis < axes; axis++) if ((subset >>> axis & 1) != 0) shift = shift.add(extents[axis]);
				shifts[subset] = shift;
			}
			maximum = whole ? ExactRational.ONE : ExactRational.of(minimum.length).divide(8);
		}

		ExactRational volume(double offset) {
			SignedDyadic threshold = SignedDyadic.of(offset); requireBudget(threshold);
			SignedDyadic numerator = SignedDyadic.ZERO;
			for (var low : minimum) {
				SignedDyadic distance = threshold.subtract(low);
				if (distance.signum() <= 0) continue;
				if (distance.compareTo(extentSum) >= 0) { numerator = numerator.add(fullNumerator); continue; }
				numerator = numerator.add(partialNumerator(distance, axes, null, shifts));
			}
			ExactRational result = quotient(numerator, denominator);
			if (result.signum() < 0 || result.compareTo(maximum) > 0) throw new IllegalStateException("Selected microbox CDF does not close");
			return result;
		}
	}

	/** Measure of nx*x+ny*y+nz*z <= threshold inside the selected open microbox. */
	static ExactRational wetVolume(int micro, GasGeometryOverlap.HalfSpace plane) {
		if (micro < 0 || micro >= 8 || plane == null) throw new IllegalArgumentException("Invalid microbox volume input");
		SignedDyadic[] normal = {plane.nx(), plane.ny(), plane.nz()};
		for (SignedDyadic coefficient : normal) requireBudget(coefficient);
		requireBudget(plane.threshold());
		try {
			SignedDyadic minimum = SignedDyadic.ZERO, extentSum = SignedDyadic.ZERO;
			SignedDyadic[] extents = new SignedDyadic[3];
			int axes = 0;
			for (int axis = 0; axis < 3; axis++) {
				SignedDyadic n = normal[axis];
				SignedDyadic extent = n.multiply(HALF);
				if ((micro >>> axis & 1) != 0) minimum = minimum.add(extent);
				if (n.signum() < 0) { minimum = minimum.add(extent); extent = extent.negate(); }
				if (n.signum() != 0) { extents[axes++] = extent; extentSum = extentSum.add(extent); }
			}
			SignedDyadic distance = plane.threshold().subtract(minimum);
			if (axes == 0) return distance.signum() >= 0 ? MICRO_VOLUME : ExactRational.ZERO;
			if (distance.signum() <= 0) return ExactRational.ZERO;
			if (distance.compareTo(extentSum) >= 0) return MICRO_VOLUME;
			SignedDyadic numerator = partialNumerator(distance, axes, extents, null);
			int factorial = axes == 3 ? 6 : axes;
			SignedDyadic denominator = SignedDyadic.of(factorial * 8);
			for (int axis = 0; axis < axes; axis++) denominator = denominator.multiply(extents[axis]);
			ExactRational result = quotient(numerator, denominator);
			if (result.signum() < 0 || result.compareTo(MICRO_VOLUME) > 0) throw new IllegalStateException("Exact microbox CDF does not close");
			return result;
		} catch (IllegalArgumentException magnitude) {
			throw new GasTopologyBlockedException("Exact microbox plane volume exceeds its representation budget: " + magnitude.getMessage());
		}
	}

	/** Measure of nx*x+ny*y+nz*z >= threshold; nonconstant plane surfaces have zero volume. */
	static ExactRational dryVolume(int micro, GasGeometryOverlap.HalfSpace plane) {
		if (plane == null) throw new IllegalArgumentException("Missing microbox volume plane");
		return wetVolume(micro, new GasGeometryOverlap.HalfSpace(plane.nx().negate(), plane.ny().negate(), plane.nz().negate(), plane.threshold().negate()));
	}

	private static SignedDyadic partialNumerator(SignedDyadic distance, int axes, SignedDyadic[] extents, SignedDyadic[] shifts) {
		SignedDyadic numerator = SignedDyadic.ZERO;
		for (int subset = 0; subset < 1 << axes; subset++) {
			SignedDyadic span = shifts == null ? distance : distance.subtract(shifts[subset]);
			if (shifts == null) for (int axis = 0; axis < axes; axis++)
				if ((subset >>> axis & 1) != 0) span = span.subtract(extents[axis]);
			if (span.signum() <= 0) continue;
			SignedDyadic term = span;
			for (int power = 1; power < axes; power++) term = term.multiply(span);
			numerator = Integer.bitCount(subset) % 2 == 0 ? numerator.add(term) : numerator.subtract(term);
		}
		return numerator;
	}

	private static void requireBudget(SignedDyadic value) {
		long exponent = value.exponent();
		if (value.significand().abs().bitLength() + Math.max(exponent, 0) > ChemicalLimits.MAX_RATIONAL_BITS
				|| Math.max(-exponent, 0) + 1 > ChemicalLimits.MAX_RATIONAL_BITS) {
			throw new GasTopologyBlockedException("Microbox half-space exceeds the rational representation budget");
		}
	}
	private static ExactRational quotient(SignedDyadic numerator, SignedDyadic denominator) {
		if (numerator.signum() == 0) return ExactRational.ZERO;
		long shift = (long) numerator.exponent() - denominator.exponent();
		var n = numerator.significand(); var d = denominator.significand();
		if (n.abs().bitLength() + Math.max(shift, 0) > ChemicalLimits.MAX_RATIONAL_BITS
				|| d.bitLength() + Math.max(-shift, 0) > ChemicalLimits.MAX_RATIONAL_BITS) {
			throw new GasTopologyBlockedException("Microbox volume quotient exceeds the rational representation budget");
		}
		return shift >= 0 ? new ExactRational(n.shiftLeft((int) shift), d) : new ExactRational(n, d.shiftLeft((int) -shift));
	}
}
