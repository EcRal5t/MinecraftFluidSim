package mmm.fluid;

import mmm.units.SignedDyadic;

/** Weighted row QR for the fixed scalar feature space; the exact fit is the fallback. */
final class CellAffineFit {
	private CellAffineFit() { }
	static double[] fit(double[] mass, double[][] feature, double[][] directions, boolean closeConstant) {
		int n = mass.length, rank = feature.length;
		double maximum = 0; for (double value : mass) maximum = Math.max(maximum, value);
		double[] root = new double[n]; double[][] q = new double[rank][n], lower = new double[rank][rank];
		for (int i = 0; i < n; i++) {
			root[i] = Math.sqrt(mass[i] / maximum);
			if (!(root[i] > 0 && Double.isFinite(root[i]))) return null;
		}
		for (int row = 0; row < rank; row++) {
			for (int i = 0; i < n; i++) q[row][i] = root[i] * feature[row][i];
			for (int pass = 0; pass < 2; pass++) for (int prior = 0; prior < row; prior++) {
				double projection = dot(q[row], q[prior]); lower[row][prior] += projection;
				for (int i = 0; i < n; i++) q[row][i] -= projection * q[prior][i];
			}
			double norm = 0; for (double value : q[row]) norm = Math.hypot(norm, value);
			if (!(norm > 0 && Double.isFinite(norm))) return null;
			lower[row][row] = norm; for (int i = 0; i < n; i++) q[row][i] /= norm;
		}
		double[][] local = new double[rank][n];
		for (int i = 0; i < n; i++) for (int row = rank - 1; row >= 0; row--) {
			double value = q[row][i] * root[i];
			for (int later = row + 1; later < rank; later++) value -= lower[later][row] * local[later][i];
			local[row][i] = value / lower[row][row];
		}
		for (int row = 0; row < rank; row++) {
			for (int pass = 0; pass < 2; pass++) {
				double[] z = new double[rank];
				for (int j = 0; j < rank; j++) {
					double residual = SignedDyadic.of(j == row ? 1 : 0).subtract(exactDot(local[row], feature[j])).toDouble();
					for (int prior = 0; prior < j; prior++) residual -= lower[j][prior] * z[prior];
					z[j] = residual / lower[j][j];
				}
				for (int i = 0; i < n; i++) for (int j = 0; j < rank; j++) local[row][i] += root[i] * q[j][i] * z[j];
			}
			for (double value : local[row]) if (!Double.isFinite(value)) return null;
			for (int j = 0; j < rank; j++) if (!near(exactDot(local[row], feature[j]), j == row ? 1 : 0, n + 32.0)) return null;
		}
		double[] result = new double[4 * n];
		for (int i = 0; i < n; i++) {
			result[4 * i] = local[0][i];
			for (int axis = 0; axis < 3; axis++) {
				SignedDyadic sum = SignedDyadic.ZERO;
				for (int j = 1; j < rank; j++) sum = sum.add(SignedDyadic.of(directions[j - 1][axis]).multiply(SignedDyadic.of(local[j][i])));
				result[4 * i + axis + 1] = sum.toDouble();
			}
		}
		if (closeConstant) for (int term = 0; term < 4; term++) {
			int pivot = 0; for (int i = 1; i < n; i++) if (Math.ulp(result[4 * i + term]) < Math.ulp(result[4 * pivot + term])) pivot = i;
			SignedDyadic others = SignedDyadic.ZERO;
			for (int i = 0; i < n; i++) if (i != pivot) others = others.add(SignedDyadic.of(result[4 * i + term]));
			result[4 * pivot + term] = SignedDyadic.of(term == 0 ? 1 : 0).subtract(others).toDouble();
		}
		for (int term = 0; term < 4; term++) for (int j = 0; j < rank; j++) {
			SignedDyadic actual = SignedDyadic.ZERO;
			for (int i = 0; i < n; i++) actual = actual.add(SignedDyadic.of(result[4 * i + term]).multiply(SignedDyadic.of(feature[j][i])));
			double expected = term == 0 ? (j == 0 ? 1 : 0) : j == 0 ? 0 : directions[j - 1][term - 1];
			if (!near(actual, expected, n + 64.0)) return null;
		}
		return result;
	}
	private static boolean near(SignedDyadic actual, double expected, double ulps) {
		SignedDyadic error = actual.subtract(SignedDyadic.of(expected));
		return (error.signum() < 0 ? error.negate() : error).compareTo(SignedDyadic.of(ulps * Math.ulp(1.0))) <= 0;
	}
	private static double dot(double[] a, double[] b) { double value = 0; for (int i = 0; i < a.length; i++) value += a[i] * b[i]; return value; }
	private static SignedDyadic exactDot(double[] a, double[] b) {
		SignedDyadic value = SignedDyadic.ZERO;
		for (int i = 0; i < a.length; i++) value = value.add(SignedDyadic.of(a[i]).multiply(SignedDyadic.of(b[i])));
		return value;
	}
}
