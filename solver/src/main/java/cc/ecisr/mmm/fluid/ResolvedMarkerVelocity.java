package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Mass-orthogonal projection onto the defined MAC interpolation space. */
final class ResolvedMarkerVelocity {
	private final int[] size;
	private final float h;
	private final FlipCutCellGeometry cutCells;
	private int[] indices = new int[0];
	private double[] weights = new double[0];
	private double[] diagonal = new double[0], x = new double[0], r = new double[0], p = new double[0], ap = new double[0];

	ResolvedMarkerVelocity(int nx, int ny, int nz, float h, FlipCutCellGeometry cutCells) {
		size = new int[] {nx, ny, nz}; this.h = h; this.cutCells = cutCells;
	}

	void project(LiquidParticleStore particles, int axis, boolean[] valid, float[] velocity) {
		int count = particles.count(), faces = valid.length;
		if (indices.length < count * 8) { indices = new int[count * 8]; weights = new double[count * 8]; }
		if (x.length < faces) {
			diagonal = new double[faces]; x = new double[faces]; r = new double[faces]; p = new double[faces]; ap = new double[faces];
		}
		Arrays.fill(diagonal, 0); Arrays.fill(x, 0); Arrays.fill(r, 0);
		int nx = size[0] + (axis == 0 ? 1 : 0), ny = size[1] + (axis == 1 ? 1 : 0), nz = size[2] + (axis == 2 ? 1 : 0);
		for (int marker = 0; marker < count; marker++) {
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
		for (int marker = 0; marker < count; marker++) velocity[marker] = (float) sample(x, marker);
	}

	private double sample(double[] field, int marker) {
		double result = 0;
		for (int j = 0; j < 8; j++) { int entry = marker * 8 + j; result += weights[entry] * field[indices[entry]]; }
		return result;
	}
}
