package mmm.fluid;

import mmm.units.SignedDyadic;
import java.math.BigInteger;

/** Products used by mass-weighted constraint responses. */
final class ConstraintNumerics {
	private ConstraintNumerics() { }

	/** Exact row-major F M^-1 F^T; all rows share the same positive source-column inertia. */
	static SignedDyadic[] massGram(SignedDyadic[][] rows, SignedDyadic[] inverseMass) {
		if (rows == null || rows.length == 0 || rows.length > 12 || inverseMass == null || inverseMass.length == 0)
			throw new IllegalArgumentException("Mass Gram requires 1 to 12 rows and positive source inertia");
		int width = rows.length, count = inverseMass.length;
		for (var inverse : inverseMass) if (inverse == null || inverse.signum() <= 0)
			throw new IllegalArgumentException("Mass Gram source inverse mass must be positive");
		var integers = new BigInteger[width][count]; var weighted = new BigInteger[width][count];
		int[] exponents = new int[width], weightedExponents = new int[width];
		for (int row = 0; row < width; row++) {
			if (rows[row] == null || rows[row].length != count)
				throw new IllegalArgumentException("Mass Gram source row dimensions differ");
			int exponent = Integer.MAX_VALUE, weightedExponent = Integer.MAX_VALUE;
			for (int column = 0; column < count; column++) {
				var value = rows[row][column];
				if (value == null) throw new IllegalArgumentException("Mass Gram source coefficient is missing");
				if (value.signum() != 0) {
					exponent = Math.min(exponent, value.exponent());
					weightedExponent = Math.min(weightedExponent, Math.addExact(value.exponent(), inverseMass[column].exponent()));
				}
			}
			exponents[row] = exponent == Integer.MAX_VALUE ? 0 : exponent;
			weightedExponents[row] = weightedExponent == Integer.MAX_VALUE ? 0 : weightedExponent;
			for (int column = 0; column < count; column++) {
				var value = rows[row][column];
				if (value.signum() == 0) { integers[row][column] = weighted[row][column] = BigInteger.ZERO; continue; }
				integers[row][column] = align(value.significand(), value.exponent(), exponents[row]);
				weighted[row][column] = align(value.significand().multiply(inverseMass[column].significand()),
						Math.addExact(value.exponent(), inverseMass[column].exponent()), weightedExponents[row]);
			}
		}
		var gram = new SignedDyadic[width * width];
		for (int first = 0; first < width; first++) for (int second = 0; second <= first; second++) {
			BigInteger sum = BigInteger.ZERO;
			for (int column = 0; column < count; column++)
				if (integers[first][column].signum() != 0 && weighted[second][column].signum() != 0)
					sum = sum.add(integers[first][column].multiply(weighted[second][column]));
			gram[width * first + second] = gram[width * second + first] = sum.signum() == 0 ? SignedDyadic.ZERO
					: new SignedDyadic(sum, Math.addExact(exponents[first], weightedExponents[second]));
		}
		return gram;
	}

	private static BigInteger align(BigInteger significand, int exponent, int common) {
		long shift = (long) exponent - common;
		if (shift > SignedDyadic.MAX_SIGNIFICAND_BITS)
			throw new IllegalArgumentException("Mass Gram binary exponent span exceeds the dyadic representation budget");
		return significand.shiftLeft((int) shift);
	}

	static double rescaledResponse(double coefficient, double inverseMass, double force) {
		double result = (coefficient * inverseMass) * force;
		if ((!Double.isFinite(result) || result == 0) && coefficient != 0 && inverseMass != 0 && force != 0
				&& Double.isFinite(coefficient) && Double.isFinite(inverseMass) && Double.isFinite(force)) {
			return SignedDyadic.of(coefficient).multiply(SignedDyadic.of(inverseMass)).multiply(SignedDyadic.of(force)).toDouble();
		}
		return result;
	}
}
