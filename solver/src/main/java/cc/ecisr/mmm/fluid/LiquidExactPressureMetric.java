package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Exact block inverse of the affine-consistent pressure energy; actions round after the complete dot product. */
final class LiquidExactPressureMetric {
	private LiquidExactPressureMetric() { }
	static Factors build(ControlVolumeGradient geometry, double density) {
		int n = geometry.indices.length; var gram = new Fraction[3][3]; var remainder = new Fraction[3][3]; var dual = new Fraction[n][3];
		for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) dual[i][a] = geometry.exactBasis[i][a].divide(geometry.exactWeight[i]);
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			gram[a][b] = Fraction.ZERO; var affine = Fraction.ZERO;
			for (int i = 0; i < n; i++) {
				gram[a][b] = gram[a][b].add(geometry.exactBasis[i][a].multiply(dual[i][b]));
				affine = affine.add(geometry.exactBasis[i][a].multiply(geometry.exactDisplacement[i][b]));
			}
			remainder[a][b] = Fraction.of(a == b ? 1 : 0).subtract(affine);
		}
		var inverse = inverse(gram); var coupling = new Fraction[n][3];
		for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) {
			coupling[i][a] = Fraction.ZERO;
			for (int b = 0; b < 3; b++) coupling[i][a] = coupling[i][a].add(dual[i][b].multiply(inverse[b][a]));
		}
		var lifting = new Fraction[n][3]; var spatial = new Fraction[3][3];
		for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) {
			lifting[i][a] = geometry.exactDisplacement[i][a];
			for (int b = 0; b < 3; b++) lifting[i][a] = lifting[i][a].add(coupling[i][b].multiply(remainder[b][a]));
		}
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			spatial[a][b] = a == b ? geometry.exactVolume : Fraction.ZERO;
			for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++)
				spatial[a][b] = spatial[a][b].add(remainder[j][a].multiply(inverse[j][k]).multiply(remainder[k][b]));
		}
		var spatialInverse = inverse(spatial); var spatialDual = new Fraction[n][3];
		for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) {
			spatialDual[i][a] = Fraction.ZERO;
			for (int b = 0; b < 3; b++) spatialDual[i][a] = spatialDual[i][a].add(lifting[i][b].multiply(spatialInverse[b][a]));
		}
		var diagonal = new Fraction[n]; for (int i = 0; i < n; i++) diagonal[i] = Fraction.of(1).divide(geometry.exactWeight[i]);
		return new Factors(diagonal, dual, coupling, lifting, spatialDual, Fraction.of(density));
	}
	record Factors(Fraction[] diagonal, Fraction[][] dual, Fraction[][] coupling, Fraction[][] lifting, Fraction[][] spatialDual, Fraction density) {
		Fraction coefficient(int i, int j) {
			var value = i == j ? diagonal[i] : Fraction.ZERO;
			for (int a = 0; a < 3; a++) value = value.subtract(coupling[i][a].multiply(dual[j][a])).add(spatialDual[i][a].multiply(lifting[j][a]));
			return value.multiply(density);
		}
	}
	private static Fraction[][] inverse(Fraction[][] matrix) {
		var cofactors = new Fraction[3][3];
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
			int a = (i + 1) % 3, b = (i + 2) % 3, c = (j + 1) % 3, d = (j + 2) % 3;
			cofactors[i][j] = matrix[a][c].multiply(matrix[b][d]).subtract(matrix[a][d].multiply(matrix[b][c]));
		}
		var determinant = Fraction.ZERO; for (int j = 0; j < 3; j++) determinant = determinant.add(matrix[0][j].multiply(cofactors[0][j]));
		if (determinant.signum() <= 0) throw new IllegalArgumentException("Pressure metric has no positive three-dimensional geometric support");
		var result = new Fraction[3][3];
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) result[i][j] = cofactors[j][i].divide(determinant);
		return result;
	}
	/** Common integer coordinates are compiled once; each action observes its final rows without intermediate reductions. */
	static final class Action {
		private final LinearForm[] reduction, output;
		private final java.math.BigInteger[] denominator;
		Action(Factors factors) {
			int n = factors.diagonal.length; reduction = new LinearForm[6]; output = new LinearForm[n];
			for (int a = 0; a < 6; a++) {
				var row = new Fraction[n];
				for (int i = 0; i < n; i++) row[i] = a < 3 ? factors.dual[i][a] : factors.lifting[i][a - 3];
				reduction[a] = new LinearForm(row);
			}
			var divisor = java.math.BigInteger.ONE; for (var row : reduction) divisor = common(divisor, row.denominator);
			for (int a = 0; a < reduction.length; a++) {
				var scale = divisor.divide(reduction[a].denominator); var coefficients = reduction[a].numerator.clone();
				for (int i = 0; i < n; i++) coefficients[i] = coefficients[i].multiply(scale);
				reduction[a] = new LinearForm(coefficients, divisor);
			}
			denominator = new java.math.BigInteger[n];
			var scale = new Fraction(divisor, java.math.BigInteger.ONE);
			for (int i = 0; i < n; i++) {
				var row = new Fraction[7]; row[0] = factors.diagonal[i].multiply(factors.density).multiply(scale);
				for (int a = 0; a < 3; a++) {
					row[1 + a] = factors.coupling[i][a].negate().multiply(factors.density);
					row[4 + a] = factors.spatialDual[i][a].multiply(factors.density);
				}
				output[i] = new LinearForm(row);
				denominator[i] = output[i].denominator.multiply(divisor);
			}
		}
		double[] multiply(double[] value) {
			if (value.length != output.length) throw new IllegalArgumentException("Incomplete exact pressure coordinates");
			var input = new cc.ecisr.mmm.units.SignedDyadic[value.length]; int exponent = 0;
			for (int i = 0; i < input.length; i++) {
				input[i] = cc.ecisr.mmm.units.SignedDyadic.of(value[i]); if (input[i].signum() != 0) exponent = Math.min(exponent, input[i].exponent());
			}
			var scaled = new java.math.BigInteger[input.length];
			for (int i = 0; i < input.length; i++) scaled[i] = input[i].significand().shiftLeft(input[i].exponent() - exponent);
			var row = new java.math.BigInteger[7]; for (int a = 0; a < 6; a++) row[a + 1] = reduction[a].sum(scaled);
			var result = new double[value.length];
			for (int i = 0; i < result.length; i++) {
				row[0] = scaled[i];
				result[i] = LiquidIntegerDotObservation.rounded(output[i].numerator, row, denominator[i].shiftLeft(-exponent));
				if (!Double.isFinite(result[i])) throw new IllegalStateException("Exact pressure action exceeds its numerical observation range");
			}
			return result;
		}
	}
	private static final class LinearForm {
		final java.math.BigInteger[] numerator; final java.math.BigInteger denominator;
		LinearForm(java.math.BigInteger[] numerator, java.math.BigInteger denominator) { this.numerator = numerator; this.denominator = denominator; }
		LinearForm(Fraction[] coefficients) {
			var divisor = java.math.BigInteger.ONE; for (var value : coefficients) divisor = common(divisor, value.denominator());
			denominator = divisor; numerator = new java.math.BigInteger[coefficients.length];
			for (int i = 0; i < coefficients.length; i++) numerator[i] = coefficients[i].numerator().multiply(divisor.divide(coefficients[i].denominator()));
		}
		java.math.BigInteger sum(java.math.BigInteger[] value) {
			var result = java.math.BigInteger.ZERO;
			for (int i = 0; i < numerator.length; i++) result = result.add(numerator[i].multiply(value[i])); return result;
		}
	}
	private static java.math.BigInteger common(java.math.BigInteger a, java.math.BigInteger b) { return a.divide(LiquidIntegerArithmetic.gcd(a, b)).multiply(b); }
}
