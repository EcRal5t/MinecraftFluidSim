package mmm.fluid;

import java.util.Arrays;

/**
 * Staggered finite-volume stress operator K = sum(w b b^T), M du/dt = -K u.
 * Each row is one normal strain, edge shear strain, or prescribed wall velocity difference.
 * Conductances include area/distance; b is dimensionless. Fixed degrees of freedom have zero mass.
 */
final class ViscousStressOperator {
	interface HeatSink { void add(int cell, double joules); }
	private int[] heatCells = new int[64];
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

	void reserveRows(int capacity) {
		if(capacity<=weights.length)return;
		weights=Arrays.copyOf(weights,capacity);
		indices=Arrays.copyOf(indices,4*capacity);
		coefficients=Arrays.copyOf(coefficients,4*capacity);
		heatCells=Arrays.copyOf(heatCells,4*capacity);
	}

	void active(int id, int axis, double capacity, double value) {
		if (!(capacity > 0) || !Double.isFinite(capacity) || !Double.isFinite(value)) {
			throw new IllegalStateException("Invalid viscous momentum capacity/velocity at face " + id);
		}
		mass[id] = capacity; velocity[id] = value; component[id] = axis;
	}

	void add(double weight, int a, double ca, int b, double cb, int c, double cc, int d, double cd) {
		addOwned(weight,a,ca,b,cb,c,cc,d,cd,-1,-1,-1,-1);
	}
	void addOwned(double weight, int a, double ca, int b, double cb, int c, double cc, int d, double cd,
	              int ownerA, int ownerB, int ownerC, int ownerD) {
		if (!(weight >= 0) || !Double.isFinite(weight)) throw new IllegalStateException("Invalid viscous conductance");
		if (weight == 0) return;
		if (rows == weights.length) {
			reserveRows(rows*2);
		}
		int offset = rows * 4;
		heatCells[offset]=ownerA;heatCells[offset+1]=ownerB;heatCells[offset+2]=ownerC;heatCells[offset+3]=ownerD;
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
				spectralBound = Math.max(spectralBound, rowBound[id]);
			}
		}
	}

	/** Diagnostic Euler limit from the infinity-norm bound of M^-1 K. */
	double stableTimeStep() {
		return spectralBound == 0 ? Double.POSITIVE_INFINITY : 2 / spectralBound;
	}

	/** Symmetric splitting: each strain row gets its exact local decay for half a step. */
	FluidDomain.ViscousStepStats advance(double dt) { return advance(dt,null); }
	FluidDomain.ViscousStepStats advance(double dt, HeatSink heatSink) {
		if (!(dt > 0) || !Double.isFinite(dt)) throw new IllegalArgumentException("Invalid viscous duration");
		Arrays.fill(force, 0);
		double halfDt = .5 * dt;
		double dissipated = 0, ix = 0, iy = 0, iz = 0;
		for (int pass = 0; pass < 2; pass++) {
			for (int step = 0; step < rows; step++) {
				int row = pass == 0 ? step : rows - 1 - step;
				int offset = row * 4;
				double strain = 0, inverseMass = 0;
				for (int k = offset; k < offset + 4; k++) {
					int id = indices[k];
					if (id < 0 || mass[id] == 0) continue;
					double b = coefficients[k];
					strain += b * velocity[id];
					inverseMass += b * b / mass[id];
				}
				if (inverseMass == 0 || strain == 0) continue;
				double impulse = -Math.expm1(-halfDt * weights[row] * inverseMass) * strain / inverseMass;
				double heat=Math.max(0, impulse * (strain - .5 * impulse * inverseMass));
				dissipated += heat;
				if(heatSink!=null&&heat>0) {
					int count=0;for(int k=offset;k<offset+4;k++)if(heatCells[k]>=0)count++;
					double remaining=heat,share=count==0?0:heat/count;
					for(int k=offset;k<offset+4;k++)if(heatCells[k]>=0) {
						double part=--count==0?remaining:share;
						heatSink.add(heatCells[k],part);remaining-=part;
					}
				}

				// Internal row coefficients cancel per axis. The remaining impulse is
				// the fixed-wall reaction on the fluid; force stores its time average.
				double bx = 0, by = 0, bz = 0;
				for (int k = offset; k < offset + 4; k++) {
					int id = indices[k];
					if (id < 0 || mass[id] == 0) continue;
					double b = coefficients[k], delta = -impulse * b;
					velocity[id] += delta / mass[id];
					force[id] += delta;
					switch (component[id]) {
						case 0 -> bx += b;
						case 1 -> by += b;
						case 2 -> bz += b;
						default -> throw new IllegalStateException("Invalid velocity component");
					}
				}
				ix -= impulse * bx;
				iy -= impulse * by;
				iz -= impulse * bz;
			}
		}
		for (int id = 0; id < force.length; id++) force[id] /= dt;
		return new FluidDomain.ViscousStepStats(rows, spectralBound, ix, iy, iz, dissipated, 0, 0, 0);
	}
}
