package mmm.fluid;

import mmm.units.SignedDyadic;

/** Products used by mass-weighted constraint responses. */
final class ConstraintNumerics {
	private ConstraintNumerics() { }

	static double rescaledResponse(double coefficient, double inverseMass, double force) {
		double result = (coefficient * inverseMass) * force;
		if ((!Double.isFinite(result) || result == 0) && coefficient != 0 && inverseMass != 0 && force != 0
				&& Double.isFinite(coefficient) && Double.isFinite(inverseMass) && Double.isFinite(force)) {
			return SignedDyadic.of(coefficient).multiply(SignedDyadic.of(inverseMass)).multiply(SignedDyadic.of(force)).toDouble();
		}
		return result;
	}
}
