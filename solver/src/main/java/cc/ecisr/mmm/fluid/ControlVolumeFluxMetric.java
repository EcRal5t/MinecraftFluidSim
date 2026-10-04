package cc.ecisr.mmm.fluid;

/** Inverse of the hybrid pressure energy, used as the local variational flux metric. */
final class ControlVolumeFluxMetric {
	final ControlVolumeGradient geometry;
	final double[][] matrix;
	final double mobility;
	ControlVolumeFluxMetric(ControlVolumeGradient geometry, double density) {
		this.geometry = geometry; mobility = 1 / density;
		int n = geometry.indices.length; double[][] factor = new double[n][n];
		for (int i = 0; i < n; i++) for (int j = 0; j <= i; j++) {
			double energy = geometry.volume * geometry.basis[i].dot(geometry.basis[j]);
			for (int k = 0; k < n; k++) {
				double a = (k == i ? 1 : 0) - geometry.displacement[k].dot(geometry.basis[i]);
				double b = (k == j ? 1 : 0) - geometry.displacement[k].dot(geometry.basis[j]);
				energy += geometry.weight[k] * a * b;
			}
			double value = mobility * energy; for (int k = 0; k < j; k++) value -= factor[i][k] * factor[j][k];
			if (i == j) {
				if (!(value > 0) || !Double.isFinite(value)) throw new IllegalStateException("Control-volume flux metric exceeds numerical resolution");
				factor[i][j] = Math.sqrt(value);
			} else factor[i][j] = value / factor[j][j];
		}
		double[][] inverse = new double[n][n];
		for (int column = 0; column < n; column++) for (int row = 0; row < n; row++) {
			double value = row == column ? 1 : 0; for (int k = 0; k < row; k++) value -= factor[row][k] * inverse[k][column];
			inverse[row][column] = value / factor[row][row];
		}
		matrix = new double[n][n];
		for (int i = 0; i < n; i++) for (int j = 0; j <= i; j++) {
			double value = 0; for (int k = Math.max(i, j); k < n; k++) value += inverse[k][i] * inverse[k][j];
			if (!Double.isFinite(value)) throw new IllegalStateException("Control-volume inverse flux metric exceeds numerical range");
			matrix[i][j] = matrix[j][i] = value;
		}
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
