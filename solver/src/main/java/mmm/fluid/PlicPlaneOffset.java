package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Inverts the exact selected-microbox volume CDF on the binary64 offset lattice. */
final class PlicPlaneOffset {
	record Result(double offset, double lower, double upper, ExactRational lowerVolume, ExactRational upperVolume,
	              ExactRational targetVolume) { }
	private PlicPlaneOffset() { }

	static Result solve(int supportMask, float nx, float ny, float nz, ExactRational target) {
		if (supportMask <= 0 || supportMask > 255 || !Float.isFinite(nx) || !Float.isFinite(ny) || !Float.isFinite(nz)
				|| nx == 0 && ny == 0 && nz == 0 || target == null || target.signum() < 0
				|| target.compareTo(ExactRational.of(Integer.bitCount(supportMask)).divide(8)) > 0)
			throw new IllegalArgumentException("Invalid PLIC offset inversion input");
		try {
			SignedDyadic[] normal = {SignedDyadic.of(nx), SignedDyadic.of(ny), SignedDyadic.of(nz)};
			SignedDyadic minimum = null, maximum = null;
			for (int micro = 0; micro < 8; micro++) if ((supportMask & 1 << micro) != 0) {
				SignedDyadic low = SignedDyadic.ZERO, high = SignedDyadic.ZERO;
				for (int axis = 0; axis < 3; axis++) {
					int bit = micro >>> axis & 1, negative = normal[axis].signum() < 0 ? 1 : 0;
					low = low.add(normal[axis].multiply(SignedDyadic.of(bit + negative, -1)));
					high = high.add(normal[axis].multiply(SignedDyadic.of(bit + 1 - negative, -1)));
				}
				if (minimum == null || low.compareTo(minimum) < 0) minimum = low;
				if (maximum == null || high.compareTo(maximum) > 0) maximum = high;
			}
			double lower = outward(minimum, false), upper = outward(maximum, true);
			ExactRational lowVolume = volume(supportMask, normal, lower), highVolume = volume(supportMask, normal, upper);
			if (lowVolume.equals(target)) return exact(lower, lowVolume);
			if (highVolume.equals(target)) return exact(upper, highVolume);
			if (lowVolume.compareTo(target) > 0 || highVolume.compareTo(target) < 0)
				throw new IllegalStateException("PLIC offset extrema do not enclose the target volume");
			long lowOrdinal = ordinal(lower), highOrdinal = ordinal(upper);
			int iterations = 0;
			while (lowOrdinal + 1 < highOrdinal) {
				if (++iterations > Long.SIZE) throw new GasTopologyBlockedException("PLIC offset inversion exceeds its search budget");
				// Signed floor average avoids overflow even when the interval spans both signs.
				long middleOrdinal = (lowOrdinal & highOrdinal) + ((lowOrdinal ^ highOrdinal) >> 1);
				double middle = value(middleOrdinal);
				ExactRational middleVolume = volume(supportMask, normal, middle);
				int order = middleVolume.compareTo(target);
				if (order == 0) return exact(middle, middleVolume);
				if (order < 0) { lowOrdinal = middleOrdinal; lower = middle; lowVolume = middleVolume; }
				else { highOrdinal = middleOrdinal; upper = middle; highVolume = middleVolume; }
			}
			double selected = target.multiply(2).compareTo(lowVolume.add(highVolume)) <= 0 ? lower : upper;
			return new Result(selected, lower, upper, lowVolume, highVolume,target);
		} catch (IllegalArgumentException magnitude) {
			throw new GasTopologyBlockedException("Exact PLIC offset inversion exceeds its representation budget: " + magnitude.getMessage());
		}
	}

	private static ExactRational volume(int supportMask, SignedDyadic[] normal, double offset) {
		var plane = new GasGeometryOverlap.HalfSpace(normal[0], normal[1], normal[2], SignedDyadic.of(offset));
		ExactRational sum = ExactRational.ZERO;
		for (int micro = 0; micro < 8; micro++) if ((supportMask & 1 << micro) != 0)
			sum = sum.add(MicroBoxPlaneVolume.wetVolume(micro, plane));
		return sum;
	}

	private static Result exact(double offset, ExactRational volume) {
		return new Result(offset, offset, offset, volume, volume,volume);
	}
	private static double outward(SignedDyadic exact, boolean upper) {
		double rounded = exact.toDouble();
		int order = SignedDyadic.of(rounded).compareTo(exact);
		if (upper && order < 0) rounded = Math.nextUp(rounded);
		if (!upper && order > 0) rounded = Math.nextDown(rounded);
		return rounded == 0 ? 0 : rounded;
	}
	private static long ordinal(double value) {
		long bits = Double.doubleToRawLongBits(value);
		return bits < 0 ? Long.MIN_VALUE - bits : bits;
	}
	private static double value(long ordinal) {
		return Double.longBitsToDouble(ordinal < 0 ? Long.MIN_VALUE - ordinal : ordinal);
	}
}
