package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Finite dyadic factors of the original pressure inverse, with certified operator perturbation. */
final class LiquidCertifiedPressureMetric {
	record Result(LiquidExactPressureMetric.Factors factors, double[][] error, double normalizedPerturbationUpper, int precision) { }
	private static final int MAXIMUM_PRECISION = 4096;
	private LiquidCertifiedPressureMetric() { }
	static Result build(ControlVolumeGradient geometry, double density, double[] weightUpper, double normalizedLower) {
		for (int precision = 64; precision <= MAXIMUM_PRECISION; precision *= 2) {
			try {
				var result = compute(geometry, density, weightUpper, precision);
				if (result.normalizedPerturbationUpper <= Math.nextDown(normalizedLower * Math.ulp(1.0))) return result;
			} catch (LiquidDyadicInterval.ResolutionRequired unresolved) { }
		}
		throw new IllegalStateException("Pressure coefficients exceed their certified arithmetic budget: maximumPrecision=" + MAXIMUM_PRECISION);
	}
	private static Result compute(ControlVolumeGradient geometry, double density, double[] weightUpper, int precision) {
		int n = geometry.indices.length; var zero = LiquidDyadicInterval.of(Fraction.ZERO, precision);
		var one = LiquidDyadicInterval.of(Fraction.of(1), precision); var rho = LiquidDyadicInterval.of(Fraction.of(density), precision);
		var volume = LiquidDyadicInterval.of(geometry.exactVolume, precision);
		var basis = new LiquidDyadicInterval[n][3]; var displacement = new LiquidDyadicInterval[n][3];
		var diagonal = new LiquidDyadicInterval[n]; var dual = new LiquidDyadicInterval[n][3];
		for (int i = 0; i < n; i++) {
			var weight = LiquidDyadicInterval.of(geometry.exactWeight[i], precision); diagonal[i] = one.divide(weight);
			for (int a = 0; a < 3; a++) {
				basis[i][a] = LiquidDyadicInterval.of(geometry.exactBasis[i][a], precision);
				displacement[i][a] = LiquidDyadicInterval.of(geometry.exactDisplacement[i][a], precision);
				dual[i][a] = basis[i][a].divide(weight);
			}
		}
		var gram = new LiquidDyadicInterval[3][3]; var remainder = new LiquidDyadicInterval[3][3];
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			gram[a][b] = zero; remainder[a][b] = a == b ? one : zero;
			for (int i = 0; i < n; i++) {
				gram[a][b] = gram[a][b].add(basis[i][a].multiply(dual[i][b]));
				remainder[a][b] = remainder[a][b].subtract(basis[i][a].multiply(displacement[i][b]));
			}
		}
		var inverse = inverse(gram); var coupling = new LiquidDyadicInterval[n][3];
		var lifting = new LiquidDyadicInterval[n][3];
		for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) {
			coupling[i][a] = zero;
			for (int b = 0; b < 3; b++) coupling[i][a] = coupling[i][a].add(dual[i][b].multiply(inverse[b][a]));
		}
		for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) {
			lifting[i][a] = displacement[i][a];
			for (int b = 0; b < 3; b++) lifting[i][a] = lifting[i][a].add(coupling[i][b].multiply(remainder[b][a]));
		}
		var spatial = new LiquidDyadicInterval[3][3];
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			spatial[a][b] = a == b ? volume : zero;
			for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++)
				spatial[a][b] = spatial[a][b].add(remainder[j][a].multiply(inverse[j][k]).multiply(remainder[k][b]));
		}
		var spatialInverse = inverse(spatial); var spatialDual = new LiquidDyadicInterval[n][3];
		for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) {
			spatialDual[i][a] = zero;
			for (int b = 0; b < 3; b++) spatialDual[i][a] = spatialDual[i][a].add(lifting[i][b].multiply(spatialInverse[b][a]));
		}
		var q = midpoints(dual); var l = midpoints(lifting);
		var factors = new LiquidExactPressureMetric.Factors(java.util.Arrays.stream(diagonal).map(LiquidDyadicInterval::midpoint).toArray(Fraction[]::new),
				q, product(q, symmetricMidpoints(inverse)), l, product(l, symmetricMidpoints(spatialInverse)), Fraction.of(density));
		var error = new double[n][n];
		for (int i = 0; i < n; i++) for (int j = 0; j <= i; j++) {
			var coefficient = i == j ? diagonal[i] : zero;
			for (int a = 0; a < 3; a++) coefficient = coefficient.subtract(coupling[i][a].multiply(dual[j][a])).add(spatialDual[i][a].multiply(lifting[j][a]));
			error[i][j] = error[j][i] = coefficient.multiply(rho).error(factors.coefficient(i, j));
		}
		double perturbation = 0;
		for (int i = 0; i < n; i++) {
			double row = 0;
			for (int j = 0; j < n; j++) if (error[i][j] > 0) {
				double weights = Math.nextUp(Math.nextUp(Math.sqrt(weightUpper[i])) * Math.nextUp(Math.sqrt(weightUpper[j])));
				row = Math.nextUp(row + Math.nextUp(Math.nextUp(error[i][j] * weights) / density));
			}
			perturbation = Math.max(perturbation, row);
		}
		return new Result(factors, error, perturbation, precision);
	}
	private static LiquidDyadicInterval[][] inverse(LiquidDyadicInterval[][] matrix) {
		var cofactors = new LiquidDyadicInterval[3][3];
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
			int a = (i + 1) % 3, b = (i + 2) % 3, c = (j + 1) % 3, d = (j + 2) % 3;
			cofactors[i][j] = matrix[a][c].multiply(matrix[b][d]).subtract(matrix[a][d].multiply(matrix[b][c]));
		}
		var determinant = LiquidDyadicInterval.of(Fraction.ZERO, matrix[0][0].precision());
		for (int j = 0; j < 3; j++) determinant = determinant.add(matrix[0][j].multiply(cofactors[0][j]));
		if (determinant.low().signum() <= 0) throw new LiquidDyadicInterval.ResolutionRequired();
		var result = new LiquidDyadicInterval[3][3];
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) result[i][j] = cofactors[j][i].divide(determinant);
		return result;
	}
	private static Fraction[][] midpoints(LiquidDyadicInterval[][] values) {
		return java.util.Arrays.stream(values).map(row -> java.util.Arrays.stream(row).map(LiquidDyadicInterval::midpoint).toArray(Fraction[]::new)).toArray(Fraction[][]::new);
	}
	private static Fraction[][] symmetricMidpoints(LiquidDyadicInterval[][] values) {
		var result = midpoints(values);
		for (int i = 0; i < result.length; i++) for (int j = 0; j < i; j++) result[i][j] = result[j][i] = result[i][j].add(result[j][i]).divide(Fraction.of(2));
		return result;
	}
	private static Fraction[][] product(Fraction[][] left, Fraction[][] right) {
		var result = new Fraction[left.length][3];
		for (int i = 0; i < left.length; i++) for (int a = 0; a < 3; a++) {
			result[i][a] = Fraction.ZERO;
			for (int b = 0; b < 3; b++) result[i][a] = result[i][a].add(left[i][b].multiply(right[b][a]));
		}
		return result;
	}
}
