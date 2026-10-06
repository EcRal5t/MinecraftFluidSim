package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.Value;
import static cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.*;

/** Numerical matrix operations with certified input, arithmetic and inverse-coefficient errors. */
final class LiquidBoundedMatrix {
	private LiquidBoundedMatrix() { }
	static Value[][] multiply(Value[][] first, Value[][] second) {
		var result = new Value[first.length][second[0].length];
		for (int i = 0; i < result.length; i++) for (int j = 0; j < result[i].length; j++) {
			var value = ZERO; for (int k = 0; k < second.length; k++) value = value.add(first[i][k].multiply(second[k][j])); result[i][j] = value;
		}
		return result;
	}
	/** If ||I-AQ||_inf < 1, A^-1 = Q(I-(I-AQ))^-1; the Neumann remainder bounds every entry. */
	static Value[][] inverse(Value[][] original) {
		int n = original.length; var matrix = new double[n][2 * n];
		for (int i = 0; i < n; i++) for (int j = 0; j < 2 * n; j++) matrix[i][j] = j < n ? original[i][j].value() : j - n == i ? 1 : 0;
		for (int column = 0; column < n; column++) {
			int pivot = column; for (int i = column + 1; i < n; i++) if (Math.abs(matrix[i][column]) > Math.abs(matrix[pivot][column])) pivot = i;
			var row = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = row; double divisor = matrix[column][column];
			if (divisor == 0 || !Double.isFinite(divisor)) throw new IllegalStateException("Spatial matrix is numerically singular");
			for (int j = column; j < 2 * n; j++) matrix[column][j] /= divisor;
			for (int i = 0; i < n; i++) if (i != column) {
				double factor = matrix[i][column]; for (int j = column; j < 2 * n; j++) matrix[i][j] -= factor * matrix[column][j];
			}
		}
		var approximate = new Value[n][n];
		for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) approximate[i][j] = new Value(matrix[i][n + j], 0);
		var product = multiply(original, approximate); var residual = new Value[n][n]; double norm = 0;
		for (int i = 0; i < n; i++) {
			double row = 0;
			for (int j = 0; j < n; j++) { residual[i][j] = (i == j ? ONE : ZERO).subtract(product[i][j]); row = up(row + residual[i][j].magnitude()); }
			norm = Math.max(norm, row);
		}
		if (!(norm < 1)) throw new IllegalStateException("Spatial inverse has no representable residual certificate");
		var remainder = multiply(approximate, residual); var result = new Value[n][n]; double denominator = Math.nextDown(1 - norm);
		for (int i = 0; i < n; i++) {
			double row = 0; for (int j = 0; j < n; j++) row = up(row + remainder[i][j].magnitude());
			double bound = up(row / denominator);
			for (int j = 0; j < n; j++) result[i][j] = new Value(approximate[i][j].value(), bound);
		}
		return result;
	}
}
