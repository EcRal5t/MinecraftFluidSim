package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Exact rational reference coefficients for the production numerical error certificates. */
final class LiquidPressureEnergyInverseOracle {
	private LiquidPressureEnergyInverseOracle() { }
	static double[][] build(ControlVolumeGradient geometry, double density) {
		int n = geometry.indices.length; var zero = Fraction.of(0); var one = Fraction.of(1);
		var gram = new Fraction[3][3]; var remainder = new Fraction[3][3];
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			gram[a][b] = zero; remainder[a][b] = a == b ? one : zero;
			for (int i = 0; i < n; i++) {
				gram[a][b] = gram[a][b].add(geometry.exactBasis[i][a].multiply(geometry.exactBasis[i][b]).divide(geometry.exactWeight[i]));
				remainder[a][b] = remainder[a][b].subtract(geometry.exactBasis[i][a].multiply(geometry.exactDisplacement[i][b]));
			}
		}
		var gramInverse = inverse(gram); var correction = multiply(gramInverse, remainder);
		var coupling = new Fraction[n][3]; var lifting = new Fraction[n][3];
		for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) {
			coupling[i][a] = zero; lifting[i][a] = geometry.exactDisplacement[i][a];
			for (int b = 0; b < 3; b++) {
				coupling[i][a] = coupling[i][a].add(geometry.exactBasis[i][b].multiply(gramInverse[b][a]).divide(geometry.exactWeight[i]));
				lifting[i][a] = lifting[i][a].add(geometry.exactBasis[i][b].multiply(correction[b][a]).divide(geometry.exactWeight[i]));
			}
		}
		var spatial = new Fraction[3][3];
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			spatial[a][b] = a == b ? geometry.exactVolume : zero;
			for (int k = 0; k < 3; k++) spatial[a][b] = spatial[a][b].add(remainder[k][a].multiply(correction[k][b]));
		}
		var dualLifting = multiply(lifting, inverse(spatial)); var inertia = Fraction.of(density); var result = new double[n][n];
		for (int i = 0; i < n; i++) for (int j = 0; j <= i; j++) {
			var value = i == j ? one.divide(geometry.exactWeight[i]) : zero;
			for (int a = 0; a < 3; a++) value = value.subtract(coupling[i][a].multiply(geometry.exactBasis[j][a]).divide(geometry.exactWeight[j]))
					.add(dualLifting[i][a].multiply(lifting[j][a]));
			double observed = value.multiply(inertia).rounded();
			if (!Double.isFinite(observed) || i == j && !(observed > 0)) throw new IllegalStateException("Control-volume inverse flux metric exceeds numerical range");
			result[i][j] = result[j][i] = observed;
		}
		return result;
	}
	private static Fraction[][] multiply(Fraction[][] first, Fraction[][] second) {
		var result = new Fraction[first.length][second[0].length];
		for (int i = 0; i < result.length; i++) for (int j = 0; j < result[i].length; j++) {
			var value = Fraction.of(0); for (int k = 0; k < second.length; k++) value = value.add(first[i][k].multiply(second[k][j])); result[i][j] = value;
		}
		return result;
	}
	private static Fraction[][] inverse(Fraction[][] original) {
		int n = original.length; var matrix = new Fraction[n][2 * n];
		for (int i = 0; i < n; i++) for (int j = 0; j < 2 * n; j++) matrix[i][j] = j < n ? original[i][j] : Fraction.of(j - n == i ? 1 : 0);
		for (int column = 0; column < n; column++) {
			int pivot = column; while (pivot < n && matrix[pivot][column].numerator().signum() == 0) pivot++;
			if (pivot == n) throw new IllegalStateException("Control-volume pressure energy is singular");
			var row = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = row; var divisor = matrix[column][column];
			for (int j = column; j < 2 * n; j++) matrix[column][j] = matrix[column][j].divide(divisor);
			for (int i = 0; i < n; i++) if (i != column) {
				var factor = matrix[i][column]; for (int j = column; j < 2 * n; j++) matrix[i][j] = matrix[i][j].subtract(factor.multiply(matrix[column][j]));
			}
		}
		var result = new Fraction[n][n]; for (int i = 0; i < n; i++) System.arraycopy(matrix[i], n, result[i], 0, n); return result;
	}
}
