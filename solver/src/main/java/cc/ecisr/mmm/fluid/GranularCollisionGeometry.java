package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularDepositStore;
import cc.ecisr.mmm.granular.GranularSphereGeometry;
import cc.ecisr.mmm.granular.SolidVolume;
import java.util.function.BiConsumer;

/** Local geometric queries for fixed micro boxes and cell-local bulk beds. */
final class GranularCollisionGeometry {
	private final int[] sizes;
	private final double h;
	private final FlipCutCellGeometry cutCells;
	private final GranularDepositStore deposits;

	GranularCollisionGeometry(int x, int y, int z, float h, FlipCutCellGeometry cutCells, GranularDepositStore deposits) {
		this.sizes = new int[]{x, y, z}; this.h = h; this.cutCells = cutCells; this.deposits = deposits;
	}

	void forEachBox(double[] minimum, double[] maximum, BiConsumer<double[], double[]> action) {
		double micro = h / 2; int[] lo = lower(minimum, micro), hi = upper(maximum, micro, 2);
		for (int z = lo[2]; z <= hi[2]; z++) for (int y = lo[1]; y <= hi[1]; y++) for (int x = lo[0]; x <= hi[0]; x++) {
			int mask = cutCells.mask(((z / 2) * sizes[1] + y / 2) * sizes[0] + x / 2);
			if ((mask & 1 << ((x & 1) + 2 * (y & 1) + 4 * (z & 1))) != 0)
				action.accept(new double[]{x * micro, y * micro, z * micro}, new double[]{(x + 1) * micro, (y + 1) * micro, (z + 1) * micro});
		}
		if (deposits == null) return;
		lo = lower(minimum, h); hi = upper(maximum, h, 1);
		for (int z = lo[2]; z <= hi[2]; z++) for (int y = lo[1]; y <= hi[1]; y++) for (int x = lo[0]; x <= hi[0]; x++) {
			long bulk = deposits.cellBulkVolumeSvq((z * sizes[1] + y) * sizes[0] + x);
			if (bulk > 0) action.accept(new double[]{x * h, y * h, z * h},
					new double[]{(x + 1) * h, (y + bulk / (double) SolidVolume.SVQ_PER_CELL) * h, (z + 1) * h});
		}
	}

	boolean sphereTouchesSolid(float x, float y, float z, float radius) {
		double[] p = {x, y, z}, lo = new double[3], hi = new double[3]; boolean[] touches = {false};
		for (int a = 0; a < 3; a++) { lo[a] = p[a] - radius; hi[a] = p[a] + radius; }
		forEachBox(lo, hi, (minimum, maximum) -> {
			if (GranularSphereGeometry.touchesBox(x, y, z, radius,
					minimum[0], minimum[1], minimum[2], maximum[0], maximum[1], maximum[2])) touches[0] = true;
		});
		return touches[0];
	}

	/** Number of candidate bed cells visited by the same vertical broad phase. */
	int verticalSpherePathWork(float x, float fromY, float z, float toY, float radius) {
		if (deposits == null) return 0;
		int[] lo = lower(new double[]{x - (double) radius, Math.min(fromY, toY) - (double) radius, z - (double) radius}, h);
		int[] hi = upper(new double[]{x + (double) radius, Math.max(fromY, toY) + (double) radius, z + (double) radius}, h, 1);
		int work = 1;
		for (int a = 0; a < 3; a++) work = Math.multiplyExact(work, Math.max(0, hi[a] - lo[a] + 1));
		return work;
	}

	/** Vertical swept-sphere interior overlap; endpoint support tangency remains admissible. */
	boolean verticalSpherePathTouchesSolid(float x, float fromY, float z, float toY, float radius) {
		double lowY = Math.min(fromY, toY), highY = Math.max(fromY, toY);
		double[] lo = {x - (double) radius, lowY - radius, z - (double) radius};
		double[] hi = {x + (double) radius, highY + radius, z + (double) radius};
		boolean[] crosses = {false};
		forEachBox(lo, hi, (minimum, maximum) -> {
			double dx = x - Math.max(minimum[0], Math.min(maximum[0], x));
			double dy = Math.max(0, Math.max(minimum[1] - highY, lowY - maximum[1]));
			double dz = z - Math.max(minimum[2], Math.min(maximum[2], z));
			if (dx * dx + dy * dy + dz * dz < radius * (double) radius) crosses[0] = true;
		});
		return crosses[0];
	}

	private static int[] lower(double[] point, double length) {
		int[] result = new int[3];
		for (int a = 0; a < 3; a++) result[a] = Math.max(0, (int) Math.floor(Math.nextDown(point[a]) / length));
		return result;
	}
	private int[] upper(double[] point, double length, int divisions) {
		int[] result = new int[3];
		for (int a = 0; a < 3; a++) result[a] = Math.min(divisions * sizes[a] - 1, (int) Math.floor(point[a] / length));
		return result;
	}
}
