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
}
