package cc.ecisr.mmm.fluid;

/** Local pressure-energy factorization with certified coefficient errors in the global variational solve. */
final class ControlVolumeFluxMetric {
	final ControlVolumeGradient geometry;
	final double[][] matrix;
	private final double[][] coefficientError;
	private final double pressureEnergyTraceUpper;
	final double mobility;
	private final double density;
	private double minimumEigenvalue = Double.NaN;
	ControlVolumeFluxMetric(ControlVolumeGradient geometry, double density) {
		this.geometry = geometry; this.density = density; mobility = 1 / density;
		var inverse = LiquidPressureEnergyInverse.build(geometry, density);
		matrix = inverse.matrix(); coefficientError = inverse.error();
		pressureEnergyTraceUpper = inverse.pressureEnergyTraceUpper();
		minimumEigenvalue();
	}
	/** Certified local inertia lower bound from the geometric pressure energy and all coefficient errors. */
	double minimumEigenvalue() {
		if (!Double.isNaN(minimumEigenvalue)) return minimumEigenvalue;
		// A = V B'B + (I-DB)'W(I-DB) is positive definite. lambda_max(A) <= trace(A).
		double rounding = 0;
		for (var row : coefficientError) { double sum = 0; for (double value : row) sum = Math.nextUp(sum + value); rounding = Math.max(rounding, sum); }
		double geometricBound = Math.nextDown(density / pressureEnergyTraceUpper);
		minimumEigenvalue = Math.nextDown(geometricBound - rounding);
		if (!(minimumEigenvalue > 0)) throw new IllegalStateException("Control-volume inertia has no representable positive energy bound: geometric="
				+ geometricBound + ", coefficientError=" + rounding + ", faces=" + matrix.length);
		return minimumEigenvalue;
	}
	double[] multiply(double[] value) {
		if (value.length != matrix.length) throw new IllegalArgumentException("Invalid local volume rates");
		double[] result = new double[value.length];
		for (int i = 0; i < result.length; i++) for (int j = 0; j < result.length; j++) result[i] += matrix[i][j] * value[j];
		return result;
	}
	double[] evaluationError(double[] value, double[] inputError) {
		int n = matrix.length; double epsilon = Math.scalb(2.0 * n, -53), gamma = Math.nextUp(epsilon / Math.nextDown(1 - epsilon)); double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			double magnitude = 0;
			for (int j = 0; j < n; j++) {
				result[i] = Math.nextUp(result[i] + Math.nextUp(Math.abs(matrix[i][j]) * inputError[j]));
				result[i] = Math.nextUp(result[i] + Math.nextUp(coefficientError[i][j] * Math.nextUp(Math.abs(value[j]) + inputError[j])));
				magnitude = Math.nextUp(magnitude + Math.abs(matrix[i][j] * value[j]));
			}
			result[i] = Math.nextUp(result[i] + Math.nextUp(gamma * magnitude));
		}
		return result;
	}
}
