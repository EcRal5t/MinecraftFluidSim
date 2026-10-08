package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Scalar cell/face diffusion energy with affine consistency on resolved control volumes. */
final class ControlVolumeDiffusion {
	private final ControlVolumeGradient geometry;
	private final double[] error;
	private double stabilizationEnergy;

	ControlVolumeDiffusion(ControlVolumeGradient geometry) {
		this.geometry = java.util.Objects.requireNonNull(geometry);
		error = new double[geometry.indices.length];
	}

	/**
	 * Differences are face values minus the cell value. The returned face flux is the
	 * derivative of half the diffusion energy; its negative sum is the cell flux.
	 * Interleaved vector components use stride 3; scalar fields use stride 1.
	 */
	double apply(double[] differences, double[] flux, int stride, int component, double[] gradient) {
		if (stride < 1 || component < 0 || component >= stride
				|| differences.length != stride * geometry.indices.length || flux.length != differences.length
				|| gradient.length != 3 * stride || differences == flux)
			throw new IllegalArgumentException("Incomplete control-volume diffusion field");
		int start = 3 * component;
		Arrays.fill(gradient, start, start + 3, 0);
		for (int i = 0; i < geometry.indices.length; i++) {
			double delta = differences[stride * i + component];
			if (!Double.isFinite(delta)) throw new IllegalArgumentException("Non-finite diffusion difference");
			for (int axis = 0; axis < 3; axis++) gradient[start + axis] += delta * geometry.basis[i].coordinate(axis);
		}
		double gx = gradient[start], gy = gradient[start + 1], gz = gradient[start + 2];
		double cx = geometry.volume * gx, cy = geometry.volume * gy, cz = geometry.volume * gz;
		double energy = geometry.volume * (gx * gx + gy * gy + gz * gz);
		stabilizationEnergy = 0;
		for (int i = 0; i < geometry.indices.length; i++) {
			var displacement = geometry.displacement[i];
			error[i] = differences[stride * i + component] - (gx * displacement.x() + gy * displacement.y() + gz * displacement.z());
			double residual = geometry.weight[i] * error[i];
			stabilizationEnergy += residual * error[i];
			cx -= residual * displacement.x(); cy -= residual * displacement.y(); cz -= residual * displacement.z();
		}
		for (int i = 0; i < geometry.indices.length; i++) {
			var basis = geometry.basis[i];
			flux[stride * i + component] = geometry.weight[i] * error[i] + basis.x() * cx + basis.y() * cy + basis.z() * cz;
			if (!Double.isFinite(flux[stride * i + component])) throw new IllegalStateException("Control-volume diffusion flux overflow");
		}
		energy += stabilizationEnergy;
		if (!Double.isFinite(energy)) throw new IllegalStateException("Control-volume diffusion energy overflow");
		return energy;
	}

	double stabilizationEnergy() { return stabilizationEnergy; }

	/** Propagates face-minus-cell observation error through every term of the diffusion action. */
	double[] observationError(double differenceError) {
		if (!(Double.isFinite(differenceError) && differenceError > 0)) throw new IllegalArgumentException("Invalid diffusion observation precision");
		var gradientError = new double[3];
		for (var basis : geometry.basis) for (int a = 0; a < 3; a++)
			gradientError[a] = upperAdd(gradientError[a], upperMultiply(Math.abs(basis.coordinate(a)), differenceError));
		var errorBound = new double[geometry.indices.length]; var correctionError = new double[3];
		for (int a = 0; a < 3; a++) correctionError[a] = upperMultiply(geometry.volume, gradientError[a]);
		for (int i = 0; i < errorBound.length; i++) {
			errorBound[i] = differenceError;
			for (int a = 0; a < 3; a++) errorBound[i] = upperAdd(errorBound[i],
					upperMultiply(Math.abs(geometry.displacement[i].coordinate(a)), gradientError[a]));
			for (int a = 0; a < 3; a++) correctionError[a] = upperAdd(correctionError[a],
					upperMultiply(upperMultiply(geometry.weight[i], errorBound[i]), Math.abs(geometry.displacement[i].coordinate(a))));
		}
		for (int i = 0; i < errorBound.length; i++) {
			errorBound[i] = upperMultiply(geometry.weight[i], errorBound[i]);
			for (int a = 0; a < 3; a++) errorBound[i] = upperAdd(errorBound[i],
					upperMultiply(Math.abs(geometry.basis[i].coordinate(a)), correctionError[a]));
		}
		return errorBound;
	}
	private static double upperAdd(double first, double second) { return Math.nextUp(first + second); }
	private static double upperMultiply(double first, double second) { return Math.nextUp(first * second); }
}
