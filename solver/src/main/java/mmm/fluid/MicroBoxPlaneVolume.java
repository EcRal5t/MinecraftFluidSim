package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Floating-point half-space volumes in whole-cell units. */
final class MicroBoxPlaneVolume {
	private MicroBoxPlaneVolume() { }
	static final class SelectedVolume {
		private final int mask;
		private final double[] normal;
		SelectedVolume(int supportMask, SignedDyadic[] normal) {
			mask = supportMask;
			this.normal = new double[]{normal[0].toDouble(),normal[1].toDouble(),normal[2].toDouble()};
		}
		double measure(double offset) {
			if (mask == 255) return box(normal, offset, 0, 1);
			double sum = 0;
			for (int micro = 0; micro < 8; micro++) if ((mask & 1 << micro) != 0) sum += box(normal, offset, micro, .5);
			return sum;
		}
		ExactRational volume(double offset) { return rational(measure(offset)); }
	}
	static double box(double[] n, double offset, int micro, double side) {
		double scale = Math.max(Math.abs(n[0]),Math.max(Math.abs(n[1]),Math.abs(n[2])));
		if (scale == 0) return offset >= 0 ? side*side*side : 0;
		double t = offset / scale;
		double[] width = new double[3]; int dimensions = 0;
		for (int axis = 0; axis < 3; axis++) {
			double c = n[axis] / scale;
			t -= c * ((micro >>> axis & 1) * side + (c < 0 ? side : 0));
			if (c != 0) width[dimensions++] = Math.abs(c) * side;
		}
		java.util.Arrays.sort(width,0,dimensions);
		double total = 0; for (int a = 0; a < dimensions; a++) total += width[a];
		if (t <= 0) return 0;
		if (t >= total) return side*side*side;
		boolean complement = t > total*.5;
		if (complement) t = total-t;
		double a = width[0], f;
		if (dimensions == 1) f = t/a;
		else {
			double b = width[1];
			if (dimensions == 2) f = t <= a ? (t/a)*(t/b)*.5 : (t-.5*a)/b;
			else {
				double c = width[2];
				if (t <= a) f = (t/a)*(t/b)*(t/c)/6;
				else if (t <= b) f = (3*t*(t-a)+a*a)/(6*b*c);
				else if (t >= a+b) f = (t-.5*(a+b))/c;
				else {
					double u = t-b, v = Math.max(0,t-c);
					f = (3*t*(t-a)+a*a-(u*u*u+v*v*v)/a)/(6*b*c);
				}
			}
		}
		return Math.clamp(complement ? 1-f : f,0,1)*side*side*side;
	}
	static ExactRational wetVolume(int micro, GasGeometryOverlap.HalfSpace plane) {
		return rational(box(new double[]{plane.nx().toDouble(),plane.ny().toDouble(),plane.nz().toDouble()},plane.threshold().toDouble(),micro,.5));
	}
	static ExactRational dryVolume(int micro, GasGeometryOverlap.HalfSpace plane) {
		return rational(box(new double[]{-plane.nx().toDouble(),-plane.ny().toDouble(),-plane.nz().toDouble()},-plane.threshold().toDouble(),micro,.5));
	}
	static ExactRational[] dryMoments(int micro, GasGeometryOverlap.HalfSpace plane, int order) {
		return MicroBoxPlaneMoments.integrate(micro,java.util.List.of(plane),order);
	}
	/** Converts the computed geometric weight at the exact inventory allocation interface. */
	static ExactRational rational(double value) {
		return quotient(SignedDyadic.of(value),SignedDyadic.of(1));
	}
	static ExactRational quotient(SignedDyadic numerator, SignedDyadic denominator) {
		int shift = numerator.exponent()-denominator.exponent();
		return shift >= 0 ? new ExactRational(numerator.significand().shiftLeft(shift),denominator.significand())
				: new ExactRational(numerator.significand(),denominator.significand().shiftLeft(-shift));
	}
}
