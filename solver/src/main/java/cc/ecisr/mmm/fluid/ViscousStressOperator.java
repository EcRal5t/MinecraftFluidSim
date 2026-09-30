package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/**
 * Staggered finite-volume stress operator K = sum(w b b^T), M du/dt = -K u.
 * Each row is one normal strain, edge shear strain, or prescribed wall velocity difference.
 * Conductances include area/distance; b is dimensionless. Fixed degrees of freedom have zero mass.
 */
final class ViscousStressOperator {
	final double[] mass, velocity, force;
	private final double[] rowBound;
	private final int[] component;
	private int[] indices = new int[64];
	private double[] coefficients = new double[64], weights = new double[16];
	private int rows;
	private double spectralBound;

	ViscousStressOperator(int degreesOfFreedom) {
		mass = new double[degreesOfFreedom]; velocity = new double[degreesOfFreedom];
		force = new double[degreesOfFreedom]; rowBound = new double[degreesOfFreedom];
		component = new int[degreesOfFreedom];
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
				// Round each positive arithmetic operation outwards; the proof must survive
				// floating-point assembly, including tiny capacities and many incident rows.
				double numerator = Math.nextUp(Math.nextUp(weight * Math.abs(coefficients[k])) * sum);
				rowBound[id] = Math.nextUp(rowBound[id] + Math.nextUp(numerator / mass[id]));
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

	FluidDomain.ViscousStepStats advance(double dt) {
		double stable = stableTimeStep();
		if (!(dt > 0) || !Double.isFinite(dt) || dt > stable) {
			throw new IllegalStateException("Viscous step exceeds assembled spectral bound");
		}
		Arrays.fill(force, 0);
		double ix = 0, iy = 0, iz = 0;
		for (int row = 0; row < rows; row++) {
			int offset = row * 4;
			double strain = 0;
			for (int k = offset; k < offset + 4; k++) {
				int id = indices[k];
				if (id >= 0 && mass[id] > 0) strain += coefficients[k] * velocity[id];
			}
			double stress = -weights[row] * strain;
			double bx = 0, by = 0, bz = 0;
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
			// Interior pairs cancel coefficients exactly before multiplying by stress. The
			// remainder is impulse delivered to liquid by a fixed wall (opposite reaction).
			ix += dt * stress * bx; iy += dt * stress * by; iz += dt * stress * bz;
		}
		double dissipated = 0;
		for (int id = 0; id < mass.length; id++) if (mass[id] > 0) {
			double previous = velocity[id], next = previous + dt * force[id] / mass[id];
			if (!Double.isFinite(next)) throw new IllegalStateException("Viscous velocity overflow");
			dissipated += 0.5 * mass[id] * (previous - next) * (previous + next);
			velocity[id] = next;
		}
		if (!Double.isFinite(dissipated) || !Double.isFinite(ix) || !Double.isFinite(iy) || !Double.isFinite(iz)) {
			throw new IllegalStateException("Viscous audit overflow");
		}
		return new FluidDomain.ViscousStepStats(rows, spectralBound, ix, iy, iz, dissipated, 0, 0, 0);
	}
}
