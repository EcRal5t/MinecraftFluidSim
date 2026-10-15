package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.Arrays;

/** Selects preconditioner rows from D A; every physical divergence row remains in the operator. */
final class CellPressurePolynomialSpace {
	private CellPressurePolynomialSpace() { }

	static int[] independentMonomials(CellAffineVelocity basis, int block) {
		int modes = basis.modeCount(block);
		if (modes == 0) return new int[0];
		var exact = new SignedDyadic[9][modes];
		for (var row : exact) Arrays.fill(row, SignedDyadic.ZERO);
		var polynomial = new double[10];
		for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++) {
			basis.divergencePolynomial(block, axis, term, polynomial);
			for (int mode = 0; mode < modes; mode++) {
				SignedDyadic amplitude = basis.modeCoefficientExact(block, mode, axis, term);
				if (amplitude.signum() == 0) continue;
				for (int monomial = 1; monomial < 10; monomial++) if (polynomial[monomial] != 0)
					exact[monomial - 1][mode] = exact[monomial - 1][mode]
							.add(amplitude.multiply(SignedDyadic.of(polynomial[monomial])));
			}
		}
		var rows = new double[9][modes];
		for (int row = 0; row < rows.length; row++) {
			int scale = Integer.MIN_VALUE;
			for (var value : exact[row]) if (value.signum() != 0)
				scale = Math.max(scale, Math.addExact(value.exponent(), value.significand().abs().bitLength() - 1));
			if (scale == Integer.MIN_VALUE) continue;
			double norm = 0;
			for (int mode = 0; mode < modes; mode++) {
				var value = exact[row][mode];
				rows[row][mode] = value.signum() == 0 ? 0
						: new SignedDyadic(value.significand(), Math.subtractExact(value.exponent(), scale)).toDouble();
				norm = Math.hypot(norm, rows[row][mode]);
			}
			for (int mode = 0; mode < modes; mode++) rows[row][mode] /= norm;
		}
		return independentRows(rows);
	}

	/** Pivoted, twice-orthogonalized QR on at most nine normalized polynomial rows. */
	private static int[] independentRows(double[][] rows) {
		int modes = rows[0].length, count = 0;
		var orthogonal = new double[Math.min(rows.length, modes)][];
		var used = new boolean[rows.length]; int[] selected = new int[orthogonal.length];
		double tolerance = Math.max(rows.length, modes) * Math.ulp(1.0);
		while (count < orthogonal.length) {
			int pivot = -1; double largest = tolerance; double[] best = null;
			for (int row = 0; row < rows.length; row++) if (!used[row]) {
				double[] candidate = rows[row].clone();
				for (int pass = 0; pass < 2; pass++) for (int prior = 0; prior < count; prior++) {
					double projection = 0;
					for (int mode = 0; mode < modes; mode++) projection += candidate[mode] * orthogonal[prior][mode];
					for (int mode = 0; mode < modes; mode++) candidate[mode] -= projection * orthogonal[prior][mode];
				}
				double norm = 0; for (double value : candidate) norm = Math.hypot(norm, value);
				if (norm > largest) { largest = norm; pivot = row; best = candidate; }
			}
			if (pivot < 0) break;
			for (int mode = 0; mode < modes; mode++) best[mode] /= largest;
			used[pivot] = true; selected[count] = pivot + 1; orthogonal[count++] = best;
		}
		int[] result = Arrays.copyOf(selected, count); Arrays.sort(result); return result;
	}
}
