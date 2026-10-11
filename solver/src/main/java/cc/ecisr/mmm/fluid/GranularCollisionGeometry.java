package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularDepositStore;
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
			double squared = 0;
			for (int a = 0; a < 3; a++) { double distance = p[a] - Math.max(minimum[a], Math.min(maximum[a], p[a])); squared += distance * distance; }
			if (squared <= radius * (double) radius) touches[0] = true;
		});
		return touches[0];
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
