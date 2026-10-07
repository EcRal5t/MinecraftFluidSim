package cc.ecisr.mmm.fluid;

/** Exact local pressure-energy action with certified observation errors in the global variational solve. */
final class ControlVolumeFluxMetric {
	final ControlVolumeGradient geometry;
	final double[][] matrix;
	private final LiquidExactPressureMetric.Action exactAction;
	private final double[][] coefficientError;
	private final double normalizedEnergyTraceUpper;
	private final double[] weightUpper;
	final double mobility;
	private final double density;
	private double minimumEigenvalue = Double.NaN;
	ControlVolumeFluxMetric(ControlVolumeGradient geometry, double density) {
		this.geometry = geometry; this.density = density; mobility = 1 / density;
		var inverse = LiquidPressureEnergyInverse.build(geometry, density);
		exactAction = new LiquidExactPressureMetric.Action(inverse.factors()); matrix = inverse.matrix(); coefficientError = inverse.error();
		normalizedEnergyTraceUpper = inverse.normalizedEnergyTraceUpper(); weightUpper = inverse.weightUpper();
		minimumEigenvalue();
	}
	/** Certified local inertia lower bound from the geometric pressure energy. */
	double minimumEigenvalue() {
		if (!Double.isNaN(minimumEigenvalue)) return minimumEigenvalue;
		// N = sqrt(W) M sqrt(W) / rho. Its inverse is W^-1/2 A W^-1/2.
		// Certifying N avoids treating tiny face areas as an absolute perturbation of every rate mode.
		double maximumWeight = 0; for (double weight : weightUpper) maximumWeight = Math.max(maximumWeight, weight);
		double geometricBound = Math.nextDown(1 / normalizedEnergyTraceUpper);
		// The action uses exact coefficients and sums; the rounded dense view is used only for coordinate scaling.
		minimumEigenvalue = Math.nextDown(Math.nextDown(geometricBound * density) / maximumWeight);
		if (!(minimumEigenvalue > 0)) throw new IllegalStateException("Control-volume inertia has no representable positive energy bound: normalizedGeometric="
				+ geometricBound + ", maximumWeight=" + maximumWeight + ", faces=" + matrix.length);
		return minimumEigenvalue;
	}
	double[] multiply(double[] value) {
		if (value.length != matrix.length) throw new IllegalArgumentException("Invalid local volume rates");
		return exactAction.multiply(value);
	}
	/** Propagates input uncertainty and the final observation error of an already evaluated exact action. */
	double[] evaluationError(double[] action, double[] inputError) {
		int n = matrix.length; if (action.length != n || inputError.length != n) throw new IllegalArgumentException("Incomplete pressure action error support");
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				var coefficient = Math.nextUp(Math.abs(matrix[i][j]) + coefficientError[i][j]);
				result[i] = Math.nextUp(result[i] + Math.nextUp(coefficient * inputError[j]));
			}
			result[i] = Math.nextUp(result[i] + Math.ulp(action[i]));
		}
		return result;
	}
}
