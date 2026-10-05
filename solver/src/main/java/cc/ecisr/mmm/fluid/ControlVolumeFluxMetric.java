package cc.ecisr.mmm.fluid;

/** Exact local pressure-energy factorization, rounded once for the global variational solve. */
final class ControlVolumeFluxMetric {
	final ControlVolumeGradient geometry;
	final double[][] matrix;
	final double mobility;
	private final double density;
	private double minimumEigenvalue = Double.NaN;
	ControlVolumeFluxMetric(ControlVolumeGradient geometry, double density) {
		this.geometry = geometry; this.density = density; mobility = 1 / density;
		matrix = LiquidPressureEnergyInverse.build(geometry, density);
	}
	/** Certified local inertia lower bound from the exact inverse pressure energy and coefficient rounding. */
	double minimumEigenvalue() {
		if (!Double.isNaN(minimumEigenvalue)) return minimumEigenvalue;
		var norm = LiquidExactPolygon.Fraction.ZERO;
		for (var row : geometry.exactBasis) for (var value : row) norm = norm.add(value.multiply(value));
		var upper = geometry.exactVolume.multiply(norm);
		for (int i = 0; i < geometry.indices.length; i++) {
			var displacement = LiquidExactPolygon.Fraction.ZERO;
			for (var value : geometry.exactDisplacement[i]) displacement = displacement.add(value.multiply(value));
			upper = upper.add(geometry.exactWeight[i].multiply(LiquidExactPolygon.Fraction.of(2))
					.multiply(LiquidExactPolygon.Fraction.of(1).add(displacement.multiply(norm))));
		}
		double rounding = 0;
		for (var row : matrix) { double sum = 0; for (double value : row) sum = Math.nextUp(sum + Math.ulp(value)); rounding = Math.max(rounding, sum); }
		minimumEigenvalue = Math.nextDown(Math.nextDown(LiquidExactPolygon.Fraction.of(density).divide(upper).rounded()) - rounding);
		if (!(minimumEigenvalue > 0)) throw new IllegalStateException("Control-volume inertia has no representable positive energy bound");
		return minimumEigenvalue;
	}
	double[] multiply(double[] value) {
		if (value.length != matrix.length) throw new IllegalArgumentException("Invalid local volume rates");
		double[] result = new double[value.length];
		for (int i = 0; i < result.length; i++) for (int j = 0; j < result.length; j++) result[i] += matrix[i][j] * value[j];
		return result;
	}
	double[] evaluationError(double[] value, double[] inputError) {
		int n = matrix.length; double epsilon = Math.scalb(2.0 * n, -53), gamma = epsilon / (1 - epsilon); double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			double magnitude = 0;
			for (int j = 0; j < n; j++) { result[i] += Math.abs(matrix[i][j]) * inputError[j]; magnitude += Math.abs(matrix[i][j] * value[j]); }
			result[i] += gamma * magnitude;
		}
		return result;
	}
}
