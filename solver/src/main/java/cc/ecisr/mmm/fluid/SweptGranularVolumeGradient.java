package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.granular.SolidVolume;
import java.util.Arrays;

/** Integrates the normalized solid-volume gradient across every support knot of a parcel sweep. */
final class SweptGranularVolumeGradient {
	static final int MAX_SUPPORT = 27;
	final int[] cells = new int[MAX_SUPPORT];
	final double[] x = new double[MAX_SUPPORT], y = new double[MAX_SUPPORT], z = new double[MAX_SUPPORT];
	private final double[] knots = new double[5];
	private final int[] localCells = new int[8];
	private final double[] weights = new double[8], dx = new double[8], dy = new double[8], dz = new double[8];
	private final FluidDomain domain;
	private int count;

	SweptGranularVolumeGradient(FluidDomain domain) { this.domain = domain; }

	int build(GranularParcelStore.Parcel parcel, double dt) {
		count = 0; Arrays.fill(x, 0); Arrays.fill(y, 0); Arrays.fill(z, 0);
		double h = domain.config.cellSize();
		double[] position = {parcel.x() / h - .5, parcel.y() / h - .5, parcel.z() / h - .5};
		double[] distance = {dt * parcel.velocityX() / h, dt * parcel.velocityY() / h, dt * parcel.velocityZ() / h};
		int stops = 2; knots[0] = 0; knots[1] = 1;
		for (int axis = 0; axis < 3; axis++) {
			if (Math.abs(distance[axis]) > 1) throw new MechanicalTimeRefinement(2, "Granular swept support Courant");
			if (distance[axis] == 0) continue;
			double knot = distance[axis] > 0 ? Math.floor(position[axis]) + 1 : Math.ceil(position[axis]) - 1;
			double time = (knot - position[axis]) / distance[axis];
			if (time > 0 && time < 1) knots[stops++] = time;
		}
		Arrays.sort(knots, 0, stops);
		double fraction = parcel.solidVolumeSvq() / (double) SolidVolume.SVQ_PER_CELL;
		for (int segment = 1; segment < stops; segment++) {
			double middle = .5 * (knots[segment] + knots[segment - 1]), width = knots[segment] - knots[segment - 1];
			// Two interior Gauss nodes integrate the quadratic trilinear derivative exactly.
			for (int side = -1; side <= 1; side += 2) {
				double time = middle + side * width / Math.sqrt(12);
				accumulate(position[0] + time * distance[0], position[1] + time * distance[1],
						position[2] + time * distance[2], fraction * width / 2, h, distance);
			}
		}
		return count;
	}

	private void accumulate(double px, double py, double pz, double scale, double h, double[] distance) {
		int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py), z0 = (int) Math.floor(pz), entries = 0;
		// At an exactly represented knot, use the derivative on the side entered by this sweep.
		if (px == x0 && distance[0] < 0) x0--;
		if (py == y0 && distance[1] < 0) y0--;
		if (pz == z0 && distance[2] < 0) z0--;
		double fx = px - x0, fy = py - y0, fz = pz - z0, sum = 0, sx = 0, sy = 0, sz = 0;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int cx = x0 + ox, cy = y0 + oy, cz = z0 + oz;
			if (cx < 0 || cy < 0 || cz < 0 || cx >= domain.sizeX || cy >= domain.sizeY || cz >= domain.sizeZ) continue;
			double wx = ox == 0 ? 1 - fx : fx, wy = oy == 0 ? 1 - fy : fy, wz = oz == 0 ? 1 - fz : fz;
			localCells[entries] = domain.cellIndex(cx, cy, cz); weights[entries] = wx * wy * wz;
			dx[entries] = (ox == 0 ? -1 : 1) * wy * wz / h;
			dy[entries] = wx * (oy == 0 ? -1 : 1) * wz / h;
			dz[entries] = wx * wy * (oz == 0 ? -1 : 1) / h;
			sum += weights[entries]; sx += dx[entries]; sy += dy[entries]; sz += dz[entries]; entries++;
		}
		if (!(sum > 0)) throw new IllegalStateException("Granular sweep has no domain support");
		for (int entry = 0; entry < entries; entry++) {
			int slot = 0; while (slot < count && cells[slot] != localCells[entry]) slot++;
			if (slot == count) {
				if (count == MAX_SUPPORT) throw new IllegalStateException("Granular sweep exceeds its 27-cell support");
				cells[count++] = localCells[entry];
			}
			x[slot] += scale * (dx[entry] * sum - weights[entry] * sx) / (sum * sum);
			y[slot] += scale * (dy[entry] * sum - weights[entry] * sy) / (sum * sum);
			z[slot] += scale * (dz[entry] * sum - weights[entry] * sz) / (sum * sum);
		}
	}
}
