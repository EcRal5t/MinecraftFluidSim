package cc.ecisr.mmm.fluid;

/** Exact local pressure-energy factorization, rounded once for the global variational solve. */
final class ControlVolumeFluxMetric {
	final ControlVolumeGradient geometry;
	final double[][] matrix;
	final double mobility;
	ControlVolumeFluxMetric(ControlVolumeGradient geometry, double density) {
		this.geometry = geometry; mobility = 1 / density;
		matrix = LiquidPressureEnergyInverse.build(geometry, density);
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