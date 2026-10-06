package cc.ecisr.mmm.fluid;

/** Local pressure-energy factorization with certified coefficient errors in the global variational solve. */
final class ControlVolumeFluxMetric {
	final ControlVolumeGradient geometry;
	final double[][] matrix;
	private final double[][] coefficientError;
	private final double normalizedEnergyTraceUpper;
	private final double[] weightUpper;
	final double mobility;
	private final double density;
	private double minimumEigenvalue = Double.NaN;
	ControlVolumeFluxMetric(ControlVolumeGradient geometry, double density) {
		this.geometry = geometry; this.density = density; mobility = 1 / density;
		var inverse = LiquidPressureEnergyInverse.build(geometry, density);
		matrix = inverse.matrix(); coefficientError = inverse.error();
		normalizedEnergyTraceUpper = inverse.normalizedEnergyTraceUpper(); weightUpper = inverse.weightUpper();
		minimumEigenvalue();
	}
	/** Certified local inertia lower bound from the geometric pressure energy and all coefficient errors. */
	double minimumEigenvalue() {
		if (!Double.isNaN(minimumEigenvalue)) return minimumEigenvalue;
		// N = sqrt(W) M sqrt(W) / rho. Its inverse is W^-1/2 A W^-1/2.
		// Certifying N avoids treating tiny face areas as an absolute perturbation of every rate mode.
		double rounding = 0;
		var scale = new double[weightUpper.length]; double maximumWeight = 0;
		for (int i = 0; i < scale.length; i++) { scale[i] = Math.nextUp(Math.sqrt(weightUpper[i])); maximumWeight = Math.max(maximumWeight, weightUpper[i]); }
		for (int i = 0; i < coefficientError.length; i++) {
			double sum = 0;
			for (int j = 0; j < coefficientError.length; j++) sum = Math.nextUp(sum + Math.nextUp(Math.nextUp(Math.nextUp(scale[i] * coefficientError[i][j]) * scale[j]) / density));
			rounding = Math.max(rounding, sum);
		}
		double geometricBound = Math.nextDown(1 / normalizedEnergyTraceUpper);
		minimumEigenvalue = Math.nextDown(Math.nextDown(Math.nextDown(geometricBound - rounding) * density) / maximumWeight);
		if (!(minimumEigenvalue > 0)) throw new IllegalStateException("Control-volume inertia has no representable positive energy bound: normalizedGeometric="
				+ geometricBound + ", normalizedCoefficientError=" + rounding + ", faces=" + matrix.length);
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
