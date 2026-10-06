package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.Value;
import static cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.*;

/** The same block inverse of V B'B + (I-DB)'W(I-DB), with certified numerical coefficients. */
final class LiquidPressureEnergyInverse {
	record Result(double[][] matrix, double[][] error, double normalizedEnergyTraceUpper, double[] weightUpper) { }
	private LiquidPressureEnergyInverse() { }
	static Result build(ControlVolumeGradient geometry, double density) {
		int n = geometry.indices.length;
		var basis = new Value[n][3]; var displacement = new Value[n][3]; var weight = new Value[n];
		for (int i = 0; i < n; i++) {
			weight[i] = Value.of(geometry.exactWeight[i]);
			for (int a = 0; a < 3; a++) { basis[i][a] = Value.of(geometry.exactBasis[i][a]); displacement[i][a] = Value.of(geometry.exactDisplacement[i][a]); }
		}
		var gram = new Value[3][3]; var remainder = new Value[3][3];
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			gram[a][b] = ZERO; remainder[a][b] = a == b ? ONE : ZERO;
			for (int i = 0; i < n; i++) {
				gram[a][b] = gram[a][b].add(basis[i][a].multiply(basis[i][b]).divide(weight[i]));
				remainder[a][b] = remainder[a][b].subtract(basis[i][a].multiply(displacement[i][b]));
			}
		}
		var gramInverse = inverse(gram); var correction = multiply(gramInverse, remainder);
		var coupling = new Value[n][3]; var lifting = new Value[n][3];
		for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) {
			coupling[i][a] = ZERO; lifting[i][a] = displacement[i][a];
			for (int b = 0; b < 3; b++) {
				var dual = basis[i][b].divide(weight[i]);
				coupling[i][a] = coupling[i][a].add(dual.multiply(gramInverse[b][a]));
				lifting[i][a] = lifting[i][a].add(dual.multiply(correction[b][a]));
			}
		}
		var volume = Value.of(geometry.exactVolume); var spatial = new Value[3][3];
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			spatial[a][b] = a == b ? volume : ZERO;
			for (int k = 0; k < 3; k++) spatial[a][b] = spatial[a][b].add(remainder[k][a].multiply(correction[k][b]));
		}
		var dualLifting = multiply(lifting, inverse(spatial)); var inertia = new Value(density, 0);
		var result = new double[n][n]; var error = new double[n][n];
		for (int i = 0; i < n; i++) for (int j = 0; j <= i; j++) {
			var value = i == j ? ONE.divide(weight[i]) : ZERO;
			for (int a = 0; a < 3; a++) value = value.subtract(coupling[i][a].multiply(basis[j][a].divide(weight[j])))
					.add(dualLifting[i][a].multiply(lifting[j][a]));
			value = value.multiply(inertia);
			if (i == j && !(value.value() > value.error())) throw new IllegalStateException("Control-volume inverse flux metric has unresolved positive inertia");
			result[i][j] = result[j][i] = value.value(); error[i][j] = error[j][i] = value.error();
		}
		var trace = ZERO;
		for (int i = 0; i < n; i++) {
			for (int a = 0; a < 3; a++) trace = trace.add(volume.multiply(basis[i][a].multiply(basis[i][a])).divide(weight[i]));
			for (int j = 0; j < n; j++) {
				var residual = i == j ? ONE : ZERO;
				for (int a = 0; a < 3; a++) residual = residual.subtract(displacement[i][a].multiply(basis[j][a]));
				trace = trace.add(weight[i].multiply(residual.multiply(residual)).divide(weight[j]));
			}
		}
		var weightUpper = new double[n]; for (int i = 0; i < n; i++) weightUpper[i] = weight[i].magnitude();
		return new Result(result, error, trace.magnitude(), weightUpper);
	}
	private static Value[][] multiply(Value[][] first, Value[][] second) {
		var result = new Value[first.length][second[0].length];
		for (int i = 0; i < result.length; i++) for (int j = 0; j < result[i].length; j++) {
			var value = ZERO; for (int k = 0; k < second.length; k++) value = value.add(first[i][k].multiply(second[k][j])); result[i][j] = value;
		}
		return result;
	}
	/** If ||I-AQ||_inf < 1, A^-1 = Q(I-(I-AQ))^-1; the Neumann remainder bounds every entry. */
	private static Value[][] inverse(Value[][] original) {
		int n = original.length; var matrix = new double[n][2 * n];
		for (int i = 0; i < n; i++) for (int j = 0; j < 2 * n; j++) matrix[i][j] = j < n ? original[i][j].value() : j - n == i ? 1 : 0;
		for (int column = 0; column < n; column++) {
			int pivot = column; for (int i = column + 1; i < n; i++) if (Math.abs(matrix[i][column]) > Math.abs(matrix[pivot][column])) pivot = i;
			var row = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = row; double divisor = matrix[column][column];
			if (divisor == 0 || !Double.isFinite(divisor)) throw new IllegalStateException("Control-volume pressure energy is numerically singular");
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
		if (!(norm < 1)) throw new IllegalStateException("Control-volume inverse has no representable residual certificate");
		var remainder = multiply(approximate, residual); var result = new Value[n][n]; double denominator = Math.nextDown(1 - norm);
		for (int i = 0; i < n; i++) {
			double row = 0; for (int j = 0; j < n; j++) row = up(row + remainder[i][j].magnitude());
			double bound = up(row / denominator);
			for (int j = 0; j < n; j++) result[i][j] = new Value(approximate[i][j].value(), bound);
		}
		return result;
	}
}
