package mmm.fluid;

import java.util.Arrays;

/**
 * Staggered finite-volume stress operator K = sum(w b b^T), M du/dt = -K u.
 * Each row is one normal strain, edge shear strain, or prescribed wall velocity difference.
 * Conductances include area/distance; b is dimensionless. Fixed degrees of freedom have zero mass.
 */
final class ViscousStressOperator {
	final double[] mass, velocity, force;
	private final double[] rowBound, diagonal, sqrtMass, initial, residual, direction, applied, preconditioned;
	private final int[] component;
	private int[] indices = new int[64];
	private double[] coefficients = new double[64], weights = new double[16];
	private int rows;
	private double spectralBound;

	ViscousStressOperator(int degreesOfFreedom) {
		mass = new double[degreesOfFreedom]; velocity = new double[degreesOfFreedom];
		force = new double[degreesOfFreedom]; rowBound = new double[degreesOfFreedom];
		component = new int[degreesOfFreedom];
		diagonal = new double[degreesOfFreedom]; sqrtMass = new double[degreesOfFreedom];
		initial = new double[degreesOfFreedom]; residual = new double[degreesOfFreedom];
		direction = new double[degreesOfFreedom]; applied = new double[degreesOfFreedom];
		preconditioned = new double[degreesOfFreedom];
	}

	void clear() {
		Arrays.fill(mass, 0); Arrays.fill(velocity, 0); Arrays.fill(rowBound, 0);
		rows = 0; spectralBound = 0;
	}

	void active(int id, int axis, double capacity, double value) {
		if (!(capacity > 0) || !Double.isFinite(capacity) || !Double.isFinite(value)) {
			throw new IllegalStateException("Invalid viscous momentum capacity/velocity at face " + id);
		}
		mass[id] = capacity; velocity[id] = value; component[id] = axis;
	}

	void add(double weight, int a, double ca, int b, double cb, int c, double cc, int d, double cd) {
		if (!(weight >= 0) || !Double.isFinite(weight)) throw new IllegalStateException("Invalid viscous conductance");
		if (weight == 0) return;
		if (rows == weights.length) {
			weights = Arrays.copyOf(weights, rows * 2);
			indices = Arrays.copyOf(indices, rows * 8);
			coefficients = Arrays.copyOf(coefficients, rows * 8);
		}
		int offset = rows * 4;
		indices[offset] = a; indices[offset + 1] = b; indices[offset + 2] = c; indices[offset + 3] = d;
		coefficients[offset] = ca; coefficients[offset + 1] = cb;
		coefficients[offset + 2] = cc; coefficients[offset + 3] = cd;
		weights[rows++] = weight;
		double sum = 0;
		for (int k = offset; k < offset + 4; k++) if (indices[k] >= 0 && mass[indices[k]] > 0) sum += Math.abs(coefficients[k]);
		for (int k = offset; k < offset + 4; k++) {
			int id = indices[k];
			if (id >= 0 && mass[id] > 0 && coefficients[k] != 0) {
				rowBound[id] += weight * Math.abs(coefficients[k]) * sum / mass[id];
			}
		}
	}

	/** M^-1 K is similar to a symmetric positive semidefinite matrix; its infinity norm bounds lambda_max. */
	double stableTimeStep() {
		spectralBound = 0;
		for (double bound : rowBound) {
			if (!(bound >= 0) || !Double.isFinite(bound)) throw new IllegalStateException("Unprovable viscous spectral bound");
			spectralBound = Math.max(spectralBound, bound);
		}
		// Forward Euler's negative-real stability interval is [-2, 0]. In the scalar uniform
		// limit this is C / sum(G). Round down so division cannot enlarge that interval.
		return spectralBound == 0 ? Double.POSITIVE_INFINITY : Math.nextDown(2 / spectralBound);
	}

	/** Backward Euler on the symmetric mass-scaled stress matrix. */
	FluidDomain.ViscousStepStats advance(double dt) {
		if (!(dt > 0) || !Double.isFinite(dt)) throw new IllegalArgumentException("Invalid viscous duration");
		stableTimeStep();
		double rhsNorm = 0;
		for (int id = 0; id < mass.length; id++) {
			sqrtMass[id] = Math.sqrt(mass[id]);
			initial[id] = velocity[id];
			velocity[id] *= sqrtMass[id];
			diagonal[id] = 1;
			rhsNorm += velocity[id] * velocity[id];
		}
		for (int row = 0; row < rows; row++) for (int k = row * 4; k < row * 4 + 4; k++) {
			int id = indices[k];
			if (id >= 0 && mass[id] > 0) diagonal[id] += dt * weights[row] * coefficients[k] * coefficients[k] / mass[id];
		}
		apply(velocity, applied, dt);
		double residualNorm = 0, rz = 0;
		for (int id = 0; id < mass.length; id++) {
			residual[id] = velocity[id] - applied[id];
			direction[id] = preconditioned[id] = residual[id] / diagonal[id];
			residualNorm += residual[id] * residual[id]; rz += residual[id] * preconditioned[id];
		}
		double toleranceSquared = Math.max(1e-24, rhsNorm * 1e-20);
		int iteration = 0, limit = Math.min(256, mass.length);
		while (residualNorm > toleranceSquared && iteration++ < limit) {
			apply(direction, applied, dt);
			double denominator = 0;
			for (int id = 0; id < mass.length; id++) denominator += direction[id] * applied[id];
			if (!(denominator > 0) || !Double.isFinite(denominator))
				throw new IllegalStateException("Invalid implicit viscous pressure-free stress system");
			double alpha = rz / denominator;
			residualNorm = 0;
			for (int id = 0; id < mass.length; id++) {
				velocity[id] += alpha * direction[id]; residual[id] -= alpha * applied[id];
				residualNorm += residual[id] * residual[id];
				preconditioned[id] = residual[id] / diagonal[id];
			}
			double next = 0;
			for (int id = 0; id < mass.length; id++) next += residual[id] * preconditioned[id];
			double beta = next / rz;
			for (int id = 0; id < mass.length; id++) direction[id] = preconditioned[id] + beta * direction[id];
			rz = next;
		}
		if (!(residualNorm <= toleranceSquared))
			throw new IllegalStateException("Implicit viscous stress did not converge: residual=" + Math.sqrt(residualNorm));
		double dissipated = 0;
		for (int id = 0; id < mass.length; id++) if (mass[id] > 0) {
			velocity[id] /= sqrtMass[id];
			dissipated += .5 * mass[id] * (initial[id] - velocity[id]) * (initial[id] + velocity[id]);
		}
		Arrays.fill(force, 0);
		double ix = 0, iy = 0, iz = 0;
		for (int row = 0; row < rows; row++) {
			int offset = row * 4;
			double strain = 0, bx = 0, by = 0, bz = 0;
			for (int k = offset; k < offset + 4; k++) {
				int id = indices[k];
				if (id >= 0 && mass[id] > 0) strain += coefficients[k] * velocity[id];
			}
			double stress = -weights[row] * strain;
			for (int k = offset; k < offset + 4; k++) {
				int id = indices[k];
				if (id < 0 || mass[id] == 0) continue;
				force[id] += coefficients[k] * stress;
				switch (component[id]) {
					case 0 -> bx += coefficients[k];
					case 1 -> by += coefficients[k];
					case 2 -> bz += coefficients[k];
					default -> throw new IllegalStateException("Invalid velocity component");
				}
			}
			ix += dt * stress * bx; iy += dt * stress * by; iz += dt * stress * bz;
		}
		return new FluidDomain.ViscousStepStats(rows, spectralBound, ix, iy, iz, dissipated, 0, 0, 0);
	}

	private void apply(double[] source, double[] result, double dt) {
		System.arraycopy(source, 0, result, 0, source.length);
		for (int row = 0; row < rows; row++) {
			int offset = row * 4;
			double strain = 0;
			for (int k = offset; k < offset + 4; k++) {
				int id = indices[k];
				if (id >= 0 && mass[id] > 0) strain += coefficients[k] * source[id] / sqrtMass[id];
			}
			double stress = dt * weights[row] * strain;
			for (int k = offset; k < offset + 4; k++) {
				int id = indices[k];
				if (id >= 0 && mass[id] > 0) result[id] += coefficients[k] * stress / sqrtMass[id];
			}
		}
	}
}
