package mmm.fluid;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.Arrays;

/** Selects a preconditioner space from canonical divergence polynomials; all physical rows remain. */
final class CellPressurePolynomialSpace {
	private static final int MAX_BITS = Math.min(4_096, ChemicalLimits.MAX_RATIONAL_BITS / 2 - 1);
	private CellPressurePolynomialSpace() { }

	static int[] independentMonomials(CellAffineVelocity basis, int block) {
		int rank = basis.rank(block);
		if (rank == 0) return new int[0];
		if (rank == 4 && cartesianTermsIndependent(basis, block)) return cartesianMonomials(basis, block);
		try {
			var matrix = new ExactRational[9][3 * rank];
			for (var row : matrix) Arrays.fill(row, ExactRational.ZERO);
			var hull = basis.sourceDirectionsExact(block);
			if (rank == 4) for (int j = 0; j < 3; j++) for (int axis = 0; axis < 3; axis++)
				hull[j][axis] = SignedDyadic.of(j == axis ? 1 : 0);
			var offset = new ExactRational[3];
			for (int axis = 0; axis < 3; axis++) offset[axis] = bounded(basis.normalizedCenterOffsetExact(block, axis));
			for (int axis = 0; axis < 3; axis++) if (basis.axisRank(block, axis) != 0) {
				double[] shape = basis.normalShape(block, axis);
				ExactRational q1 = exact(SignedDyadic.of(shape[1])), q2 = exact(SignedDyadic.of(shape[2]));
				matrix[axis][axis * rank] = multiply(q2, ExactRational.of(2));
				for (int j = 0; j < hull.length; j++) {
					int column = axis * rank + j + 1;
					var direction = new ExactRational[3]; ExactRational shifted = ExactRational.ZERO;
					for (int k = 0; k < 3; k++) {
						direction[k] = exact(hull[j][k]);
						shifted = add(shifted, multiply(direction[k], offset[k]));
					}
					for (int k = 0; k < 3; k++) {
						matrix[k][column] = add(matrix[k][column], multiply(q1, direction[k]));
						int quadratic = quadratic(axis, k) - 1;
						matrix[quadratic][column] = add(matrix[quadratic][column], multiply(ExactRational.of(2), multiply(q2, direction[k])));
					}
					matrix[axis][column] = add(matrix[axis][column], add(multiply(q1, direction[axis]), multiply(ExactRational.of(2), multiply(q2, shifted))));
					int diagonal = quadratic(axis, axis) - 1;
					matrix[diagonal][column] = add(matrix[diagonal][column], multiply(q2, direction[axis]));
				}
			}
			return independentRows(matrix);
		} catch (MagnitudeUnavailable unavailable) {
			return new int[0];
		}
	}

	private static boolean cartesianTermsIndependent(CellAffineVelocity basis, int block) {
		for (int axis = 0; axis < 3; axis++) if (basis.axisRank(block, axis) != 0) {
			double[] shape = basis.normalShape(block, axis);
			// An asymmetric quadratic shares tangent-gradient amplitudes between linear and cross terms.
			if (shape[1] != 0 && shape[2] != 0) return false;
		}
		return true;
	}
	private static int[] cartesianMonomials(CellAffineVelocity basis, int block) {
		boolean[] present = new boolean[10];
		for (int axis = 0; axis < 3; axis++) if (basis.axisRank(block, axis) != 0) {
			double[] shape = basis.normalShape(block, axis);
			if (shape[1] != 0) for (int k = 1; k <= 3; k++) present[k] = true;
			if (shape[2] != 0) {
				present[axis + 1] = true;
				for (int k = 0; k < 3; k++) present[quadratic(axis, k)] = true;
			}
		}
		int[] selected = new int[9]; int count = 0;
		for (int monomial = 1; monomial < 10; monomial++) if (present[monomial]) selected[count++] = monomial;
		return Arrays.copyOf(selected, count);
	}

	private static int[] independentRows(ExactRational[][] matrix) {
		var echelon = new ExactRational[9][];
		int[] pivots = new int[9], selected = new int[9]; int count = 0;
		for (int row = 0; row < matrix.length; row++) {
			var candidate = matrix[row];
			for (int prior = 0; prior < count; prior++) {
				ExactRational factor = candidate[pivots[prior]];
				if (factor.isZero()) continue;
				for (int col = pivots[prior]; col < candidate.length; col++)
					candidate[col] = bounded(candidate[col].subtract(multiply(factor, echelon[prior][col])));
			}
			int pivot = 0; while (pivot < candidate.length && candidate[pivot].isZero()) pivot++;
			if (pivot == candidate.length) continue;
			ExactRational divisor = candidate[pivot];
			for (int col = pivot; col < candidate.length; col++) candidate[col] = divide(candidate[col], divisor);
			pivots[count] = pivot; selected[count] = row + 1; echelon[count++] = candidate;
		}
		return Arrays.copyOf(selected, count);
	}
	private static int quadratic(int axis, int direction) {
		return axis == 0 ? direction == 0 ? 4 : direction == 1 ? 5 : 6
				: axis == 1 ? direction == 0 ? 5 : direction == 1 ? 7 : 8 : direction == 0 ? 6 : direction == 1 ? 8 : 9;
	}
	private static ExactRational exact(SignedDyadic value) {
		BigInteger numerator = value.significand(), denominator = BigInteger.ONE;
		if (value.exponent() >= 0) numerator = numerator.shiftLeft(value.exponent());
		else denominator = denominator.shiftLeft(-value.exponent());
		return bounded(new ExactRational(numerator, denominator));
	}
	private static ExactRational add(ExactRational a, ExactRational b) { return bounded(a.add(b)); }
	private static ExactRational multiply(ExactRational a, ExactRational b) { return bounded(a.multiply(b)); }
	private static ExactRational divide(ExactRational a, ExactRational b) {
		return bounded(new ExactRational(a.numerator().multiply(b.denominator()), a.denominator().multiply(b.numerator())));
	}
	private static ExactRational bounded(ExactRational value) {
		if (value.numerator().bitLength() > MAX_BITS || value.denominator().bitLength() > MAX_BITS) throw new MagnitudeUnavailable();
		return value;
	}
	private static final class MagnitudeUnavailable extends RuntimeException { }
}
