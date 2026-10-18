package mmm.fluid;

import mmm.granular.SolidVolume;
import java.util.Arrays;

/** Bounded material aperture plus the actual cell-CIC gradient used by relative solid pressure coupling. */
final class GranularFaceVolumes {
		private final FluidDomain domain;
	private final int[] offsets = new int[3];
	private final float[] liquid, fixedPore;
	private final mmm.granular.GranularKernel kernel;
	private final double[] activeVolume, fixedVolume;
	private final int[] faces = new int[8];
	private final double[] volumes = new double[8];
	private long revision;

	GranularFaceVolumes(FluidDomain domain) {
		this.domain = domain;
		offsets[1] = domain.mac.velocityX.length;
		offsets[2] = offsets[1] + domain.mac.velocityY.length;
		liquid = new float[offsets[2] + domain.mac.velocityZ.length]; fixedPore = new float[liquid.length];
		kernel = new mmm.granular.GranularKernel(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
		activeVolume = new double[liquid.length]; fixedVolume = new double[domain.cellCount];
	}

	long revision() { return revision; }
	float open(int axis, int local) { return liquid[offsets[axis] + local]; }
	float fixedPoreOpen(int axis, int local) { return fixedPore[offsets[axis] + local]; }

	void rebuild() {
		Arrays.fill(activeVolume, 0); Arrays.fill(fixedVolume, 0);
		int count = domain.granularState == null ? 0 : domain.granularState.parcels.count();

		if (domain.pressureGranularCoupling != null) {
			for (int cell = 0; cell < domain.cellCount; cell++)
				fixedVolume[cell] = SolidVolume.toCellVolume(domain.pressureGranularCoupling.fixedCellSvq(cell));
			if (domain.granularState != null) domain.granularState.deposits.forEachCell(
					(cell, svq, porosity) -> fixedVolume[cell] += SolidVolume.toCellVolume(svq));
		}
		for (int p = 0; p < count; p++) {
			var parcel = domain.granularState.parcels.parcel(p);
			for (int axis = 0; axis < 3; axis++) {
				double h = domain.config.cellSize();
				double px = parcel.x() / h - (axis == 0 ? 0 : .5);
				double py = parcel.y() / h - (axis == 1 ? 0 : .5);
				double pz = parcel.z() / h - (axis == 2 ? 0 : .5);
				int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py), z0 = (int) Math.floor(pz);
				int base = 0, entries = 0;
				double sum = 0;
				for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
					int x = x0 + dx, y = y0 + dy, z = z0 + dz;
					if (x < 0 || x >= domain.sizeX + (axis == 0 ? 1 : 0)
							|| y < 0 || y >= domain.sizeY + (axis == 1 ? 1 : 0)
							|| z < 0 || z >= domain.sizeZ + (axis == 2 ? 1 : 0)) continue;
					if (axis == 0 && (x == 0 || x == domain.sizeX)
							|| axis == 1 && (y == 0 || y == domain.sizeY)
							|| axis == 2 && (z == 0 || z == domain.sizeZ)) continue;
					int local = local(axis, x, y, z);
					double weight = (dx == 0 ? 1 - px + x0 : px - x0)
							* (dy == 0 ? 1 - py + y0 : py - y0) * (dz == 0 ? 1 - pz + z0 : pz - z0)
							* geometric(axis, local);
					if (!(weight > 0)) continue;
					faces[base + entries] = offsets[axis] + local;
					volumes[base + entries++] = weight; sum += weight;
				}
				// A blocked axis transfers its pressure load to the fixed geometry through contact.
				if (sum == 0) continue;
				double fraction = SolidVolume.toCellVolume(parcel.solidVolumeSvq());
				for (int i = base; i < base + entries; i++) {
					volumes[i] *= fraction / sum;
					activeVolume[faces[i]] += volumes[i];
				}
			}
		}
		for (int axis = 0; axis < 3; axis++) for (int local = 0; local < length(axis); local++) {
			int face = offsets[axis] + local;
			double geometric = geometric(axis, local), fixed = fixedFraction(axis, local);
			double available = geometric * (1 - fixed); fixedPore[face] = (float) available;
			liquid[face] = (float) Math.max(0, available - activeVolume[face]);
			if (liquid[face] > available) liquid[face] = Math.nextDown(liquid[face]);

		}
		revision++;
	}

	/** Actual cell-CIC occupancy derivative; MAC face fractions are a separate bounded aperture approximation. */
	int gradient(int parcel, int[] cells, double[] gx, double[] gy, double[] gz) {
		var p = domain.granularState.parcels.parcel(parcel);
		return kernel.volumeGradient(p.x(), p.y(), p.z(), Math.scalb((double)p.solidVolumeSvq(),-24), cells, gx, gy, gz);
	}

	private int length(int axis) { return axis == 0 ? domain.mac.velocityX.length : axis == 1 ? domain.mac.velocityY.length : domain.mac.velocityZ.length; }
	private int local(int axis, int x, int y, int z) { return axis == 0 ? domain.xFaceIndex(x, y, z) : axis == 1 ? domain.yFaceIndex(x, y, z) : domain.zFaceIndex(x, y, z); }
	private int rightCell(int axis, int local) {
		int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0);
		return domain.cellIndex(local % nx, local / nx % ny, local / (nx * ny));
	}
	private double geometric(int axis, int local) {
		int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0);
		int x = local % nx, y = local / nx % ny, z = local / (nx * ny);
		return axis == 0 ? domain.geometry.cutCells.xFaceOpen(x, y, z)
				: axis == 1 ? domain.geometry.cutCells.yFaceOpen(x, y, z) : domain.geometry.cutCells.zFaceOpen(x, y, z);
	}
	private double fixedFraction(int axis, int local) {
		int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0);
		int x = local % nx, y = local / nx % ny, z = local / (nx * ny);
		if (axis == 0 && (x == 0 || x == domain.sizeX) || axis == 1 && (y == 0 || y == domain.sizeY)
				|| axis == 2 && (z == 0 || z == domain.sizeZ)) return 0;
		int right = domain.cellIndex(x, y, z), left = right - (axis == 0 ? 1 : axis == 1 ? domain.sizeX : domain.sizeX * domain.sizeY);
		double a = domain.geometry.cutCells.openCellVolume(left), b = domain.geometry.cutCells.openCellVolume(right);
		return Math.min(1, Math.max(a > 0 ? fixedVolume[left] / a : 1, b > 0 ? fixedVolume[right] / b : 1));
	}
}
