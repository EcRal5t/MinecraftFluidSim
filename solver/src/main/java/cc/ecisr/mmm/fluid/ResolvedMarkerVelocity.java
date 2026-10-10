package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Mass-orthogonal projection onto the defined MAC interpolation space. */
final class ResolvedMarkerVelocity {
	private final int[] size;
	private final float h;
	private final FlipCutCellGeometry cutCells;
	private int[] indices = new int[0];
	private double[] weights = new double[0];
	private double[][] componentScalars = new double[0][];
	private int[] faceComponents = new int[0];
	private double[] diagonal = new double[0], x = new double[0], r = new double[0], p = new double[0], ap = new double[0];

	ResolvedMarkerVelocity(int nx, int ny, int nz, float h, FlipCutCellGeometry cutCells) {
		size = new int[] {nx, ny, nz}; this.h = h; this.cutCells = cutCells;
	}

	void project(LiquidParticleStore particles, int axis, boolean[] valid, float[] velocity) {
		project(particles, axis, valid, velocity, null);
	}

	void project(LiquidParticleStore particles, int axis, boolean[] valid, float[] velocity, MarkerTransferBudget support) {
		int count = particles.count(), faces = valid.length;
		boolean zero = true;
		for (int marker = 0; marker < count; marker++)
			if ((support == null || support.resolved(marker)) && velocity[marker] != 0) { zero = false; break; }
		if (zero) {
			// A homogeneous linear projection maps zero to positive zero, with no Gram assembly.
			for (int marker = 0; marker < count; marker++)
				if (support == null || support.resolved(marker)) velocity[marker] = 0;
			return;
		}
		if (indices.length < count * 8) { indices = new int[count * 8]; weights = new double[count * 8]; }
		if (x.length < faces) {
			diagonal = new double[faces]; x = new double[faces]; r = new double[faces]; p = new double[faces]; ap = new double[faces];
		}
		Arrays.fill(diagonal, 0); Arrays.fill(x, 0); Arrays.fill(r, 0);
		int nx = size[0] + (axis == 0 ? 1 : 0), ny = size[1] + (axis == 1 ? 1 : 0), nz = size[2] + (axis == 2 ? 1 : 0);
		for (int marker = 0; marker < count; marker++) {
			if (support != null && !support.resolved(marker)) continue;
			float gx = Math.clamp(particles.positionX(marker) / h - (axis == 0 ? 0 : 0.5F), 0, nx - 1);
			float gy = Math.clamp(particles.positionY(marker) / h - (axis == 1 ? 0 : 0.5F), 0, ny - 1);
			float gz = Math.clamp(particles.positionZ(marker) / h - (axis == 2 ? 0 : 0.5F), 0, nz - 1);
			int ix = (int) gx, iy = (int) gy, iz = (int) gz;
			float fx = gx - ix, fy = gy - iy, fz = gz - iz;
			double sum = 0;
			for (int j = 0; j < 8; j++) {
				int sx = Math.min(ix + (j & 1), nx - 1), sy = Math.min(iy + (j >> 1 & 1), ny - 1), sz = Math.min(iz + (j >> 2), nz - 1);
				int face = (sz * ny + sy) * nx + sx, entry = marker * 8 + j;
				float w = ((j & 1) == 0 ? 1 - fx : fx) * ((j & 2) == 0 ? 1 - fy : fy) * ((j & 4) == 0 ? 1 - fz : fz);
				indices[entry] = face; weights[entry] = valid[face] ? w : 0; sum += weights[entry];
				// Known zero normal wall values retain their interpolation weight in the
				// denominator, while their coefficients are absent from the unknown space.
				float aperture = switch (axis) {
					case 0 -> cutCells.xFaceOpen(sx, sy, sz);
					case 1 -> cutCells.yFaceOpen(sx, sy, sz);
					default -> cutCells.zFaceOpen(sx, sy, sz);
				};
				if (aperture == 0) weights[entry] = 0;
			}
			for (int j = 0; j < 8; j++) {
				int entry = marker * 8 + j, face = indices[entry];
				double w = sum > 1e-8 ? weights[entry] / sum : 0; weights[entry] = w;
				double mass = particles.mass(marker);
				r[face] += mass * w * velocity[marker]; diagonal[face] += mass * w * w;
			}
		}
		if (support != null && !support.singleComponent()) {
			projectComponents(particles, axis, velocity, support, faces); return;
		}
		double initial = 0, rr = 0;
		for (int face = 0; face < faces; face++) {
			p[face] = diagonal[face] > 0 ? r[face] / diagonal[face] : 0;
			rr += r[face] * p[face]; initial += r[face] * r[face];
		}
		double tolerance = initial * Math.pow(8 * Math.ulp(1.0F), 2);
		int iteration = 0;
		while (rr > 0) {
			Arrays.fill(ap, 0);
			for (int marker = 0; marker < count; marker++) {
				if (support != null && !support.resolved(marker)) continue;
				double sample = sample(p, marker) * particles.mass(marker);
				for (int j = 0; j < 8; j++) { int entry = marker * 8 + j; ap[indices[entry]] += weights[entry] * sample; }
			}
			double denominator = 0;
			for (int face = 0; face < faces; face++) denominator += p[face] * ap[face];
			if (!(denominator > 0 && Double.isFinite(denominator))) throw new IllegalStateException("Invalid resolved-velocity Gram matrix");
			double alpha = rr / denominator, next = 0, residualSquared = 0;
			for (int face = 0; face < faces; face++) {
				x[face] += alpha * p[face]; r[face] -= alpha * ap[face];
				residualSquared += r[face] * r[face];
				if (diagonal[face] > 0) next += r[face] * r[face] / diagonal[face];
			}
			if (residualSquared <= tolerance) break;
			if (++iteration >= 2 * faces) throw new IllegalStateException("Resolved-velocity projection did not converge");
			for (int face = 0; face < faces; face++) p[face] = (diagonal[face] > 0 ? r[face] / diagonal[face] : 0) + next / rr * p[face];
			rr = next;
		}
		for (int marker = 0; marker < count; marker++)
			if (support == null || support.resolved(marker)) velocity[marker] = (float) sample(x, marker);
	}

	/** Block-diagonal Gram solve; each existing support has its own reductions and stopping test. */
	private void projectComponents(LiquidParticleStore particles, int axis, float[] velocity, MarkerTransferBudget support, int faces) {
		int capacity = support.componentCapacity();
		if (componentScalars.length == 0 || componentScalars[0].length < capacity) componentScalars = new double[5][capacity];
		else for (double[] field : componentScalars) Arrays.fill(field, 0);
		if (faceComponents.length < faces) faceComponents = new int[faces];
		double[] rr = componentScalars[0], tolerance = componentScalars[1], denominator = componentScalars[2];
		double[] next = componentScalars[3], residual = componentScalars[4];
		for (int f = 0; f < faces; f++) {
			int c = faceComponents[f] = support.component(axis, f);
			p[f] = diagonal[f] > 0 ? r[f] / diagonal[f] : 0;
			rr[c] += r[f] * p[f]; tolerance[c] += r[f] * r[f];
		}
		for (int c = 0; c < capacity; c++) tolerance[c] *= Math.pow(8 * Math.ulp(1.0F), 2);
		for (int iteration = 0; ; iteration++) {
			Arrays.fill(ap, 0); Arrays.fill(denominator, 0); Arrays.fill(next, 0); Arrays.fill(residual, 0);
			for (int marker = 0; marker < particles.count(); marker++) if (support.resolved(marker)) {
				double sample = sample(p, marker) * particles.mass(marker);
				for (int j = 0; j < 8; j++) { int entry = marker * 8 + j; ap[indices[entry]] += weights[entry] * sample; }
			}
			for (int f = 0; f < faces; f++) denominator[faceComponents[f]] += p[f] * ap[f];
			for (int c = 0; c < capacity; c++) if (rr[c] > 0 && !(denominator[c] > 0 && Double.isFinite(denominator[c])))
				throw new IllegalStateException("Invalid resolved-velocity Gram matrix");
			for (int f = 0; f < faces; f++) {
				int c = faceComponents[f]; if (!(rr[c] > 0)) continue;
				double alpha = rr[c] / denominator[c]; x[f] += alpha * p[f]; r[f] -= alpha * ap[f];
				residual[c] += r[f] * r[f];
				if (diagonal[f] > 0) next[c] += r[f] * r[f] / diagonal[f];
			}
			boolean active = false;
			for (int c = 0; c < capacity; c++) {
				if (residual[c] <= tolerance[c]) next[c] = 0;
				active |= next[c] > 0;
			}
			if (!active) break;
			if (iteration + 1 >= 2 * faces) throw new IllegalStateException("Resolved-velocity projection did not converge");
			for (int f = 0; f < faces; f++) {
				int c = faceComponents[f];
				p[f] = next[c] > 0 ? (diagonal[f] > 0 ? r[f] / diagonal[f] : 0) + next[c] / rr[c] * p[f] : 0;
			}
			System.arraycopy(next, 0, rr, 0, capacity);
		}
		for (int marker = 0; marker < particles.count(); marker++)
			if (support.resolved(marker)) velocity[marker] = (float) sample(x, marker);
	}

	private double sample(double[] field, int marker) {
		double result = 0;
		for (int j = 0; j < 8; j++) { int entry = marker * 8 + j; result += weights[entry] * field[indices[entry]]; }
		return result;
	}
}
