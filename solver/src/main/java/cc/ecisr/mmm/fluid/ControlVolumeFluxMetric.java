package cc.ecisr.mmm.fluid;

/** Certified finite pressure-energy factors and complete-dot actions in the global variational solve. */
final class ControlVolumeFluxMetric {
	final ControlVolumeGradient geometry;
	final double[][] matrix;
	private final LiquidExactPressureMetric.Action exactAction;
	private final double[][] coefficientError, representationError;
	private final double normalizedEnergyTraceUpper;
	private final double normalizedPerturbationUpper;
	private final double[] weightUpper;
	final double mobility;
	private final double density;
	private double minimumEigenvalue = Double.NaN;
	ControlVolumeFluxMetric(ControlVolumeGradient geometry, double density) {
		this.geometry = geometry; this.density = density; mobility = 1 / density;
		var inverse = LiquidPressureEnergyInverse.build(geometry, density);
		exactAction = inverse.action(); matrix = inverse.matrix(); coefficientError = inverse.error();
		normalizedEnergyTraceUpper = inverse.normalizedEnergyTraceUpper(); weightUpper = inverse.weightUpper();
		representationError = inverse.representationError(); normalizedPerturbationUpper = inverse.normalizedPerturbationUpper();
		minimumEigenvalue();
	}
	/** Certified local inertia lower bound from the geometric pressure energy. */
	double minimumEigenvalue() {
		if (!Double.isNaN(minimumEigenvalue)) return minimumEigenvalue;
		// N = sqrt(W) M sqrt(W) / rho. Its inverse is W^-1/2 A W^-1/2.
		// Certifying N avoids treating tiny face areas as an absolute perturbation of every rate mode.
		double maximumWeight = 0; for (double weight : weightUpper) maximumWeight = Math.max(maximumWeight, weight);
		double geometricBound = Math.nextDown(Math.nextDown(1 / normalizedEnergyTraceUpper) - normalizedPerturbationUpper);
		// The finite coefficient representation retains a certified positive inertia for every rate mode.
		minimumEigenvalue = Math.nextDown(Math.nextDown(geometricBound * density) / maximumWeight);
		if (!(minimumEigenvalue > 0)) throw new IllegalStateException("Control-volume inertia has no representable positive energy bound: normalizedGeometric="
				+ geometricBound + ", maximumWeight=" + maximumWeight + ", faces=" + matrix.length);
		return minimumEigenvalue;
	}
	double[] multiply(double[] value) {
		if (value.length != matrix.length) throw new IllegalArgumentException("Invalid local volume rates");
		return exactAction.multiply(value);
	}
	/** Upper bound on l' M_represented^-1 l, including the certified coefficient perturbation. */
	double dualEnergyUpper(LiquidBoundedArithmetic.Value[] value) {
		if (value.length != geometry.indices.length) throw new IllegalArgumentException("Incomplete dual pressure support");
		var gradient = new LiquidBoundedArithmetic.Value[]{LiquidBoundedArithmetic.ZERO, LiquidBoundedArithmetic.ZERO, LiquidBoundedArithmetic.ZERO};
		for (int i = 0; i < value.length; i++) for (int a = 0; a < 3; a++)
			gradient[a] = gradient[a].add(LiquidBoundedArithmetic.Value.of(geometry.exactBasis[i][a]).multiply(value[i]));
		var energy = LiquidBoundedArithmetic.ZERO;
		for (int a = 0; a < 3; a++) energy = energy.add(LiquidBoundedArithmetic.Value.of(geometry.exactVolume).multiply(gradient[a].multiply(gradient[a])));
		for (int i = 0; i < value.length; i++) {
			var residual = value[i];
			for (int a = 0; a < 3; a++) residual = residual.subtract(LiquidBoundedArithmetic.Value.of(geometry.exactDisplacement[i][a]).multiply(gradient[a]));
			energy = energy.add(LiquidBoundedArithmetic.Value.of(geometry.exactWeight[i]).multiply(residual.multiply(residual)));
		}
		double lower = Math.nextDown(1 / normalizedEnergyTraceUpper);
		double relative = Math.nextUp(normalizedPerturbationUpper / lower);
		double retained = Math.nextDown(1 - relative);
		// M_represented >= (1-relative) M_original, so the inverse energy grows by at most its reciprocal.
		return energy.divide(new LiquidBoundedArithmetic.Value(density, 0)).divide(new LiquidBoundedArithmetic.Value(retained, 0)).magnitude();
	}
	/** Propagates coefficient representation, input uncertainty and the final complete-dot observation error. */
	double[] evaluationError(double[] value, double[] action, double[] inputError) {
		int n = matrix.length; if (value.length != n || action.length != n || inputError.length != n) throw new IllegalArgumentException("Incomplete pressure action error support");
		double[] result = new double[n];
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				var coefficient = Math.nextUp(Math.abs(matrix[i][j]) + coefficientError[i][j]);
				result[i] = Math.nextUp(result[i] + Math.nextUp(coefficient * inputError[j]));
				result[i] = Math.nextUp(result[i] + Math.nextUp(representationError[i][j] * Math.abs(value[j])));
			}
			result[i] = Math.nextUp(result[i] + Math.ulp(action[i]));
		}
		return result;
	}
}
