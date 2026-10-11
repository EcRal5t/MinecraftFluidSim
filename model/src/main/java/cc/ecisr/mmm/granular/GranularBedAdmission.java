package cc.ecisr.mmm.granular;

import java.util.Arrays;
import java.util.function.IntPredicate;

/** Per-step center-cell index; scratch state follows only settlement swaps and wake appends. */
final class GranularBedAdmission {
	private final GranularParcelStore parcels;
	private final GranularDepositStore deposits;
	private final int sizeX, sizeY, sizeZ;
	private final double h;
	private final int[] head, next, previous, cells;
	private int count, visits, limit;
	private boolean budgetBlocked;

	GranularBedAdmission(GranularParcelStore parcels, GranularDepositStore deposits,
	                     int x, int y, int z, float h) {
		this.parcels = parcels; this.deposits = deposits;
		this.sizeX = x; this.sizeY = y; this.sizeZ = z; this.h = h;
		head = new int[deposits.cellCount()]; next = new int[parcels.capacity()];
		previous = new int[next.length]; cells = new int[next.length];
	}

	void rebuild() {
		Arrays.fill(head, -1); count = 0; visits = 0;
		limit = GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP; budgetBlocked = false;
		for (int index = 0; index < parcels.count(); index++) append(index);
	}

	int visits() { return visits; }
	boolean budgetBlocked() { return budgetBlocked; }
	void reserveLogicalOperations(int operations) { limit = GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP - operations; }
	boolean spend(int work) {
		if (work > limit - visits) { budgetBlocked = true; return false; }
		visits += work; return true;
	}

	boolean allows(int cell, long bulk, int excluded) {
		return allows(cell, bulk, index -> index == excluded, index -> false);
	}

	boolean allows(int cell, long bulk, IntPredicate excluded, IntPredicate acceptBlocking) {
		if (bulk <= deposits.cellBulkVolumeSvq(cell)) return true;
		int x = cell % sizeX, yz = cell / sizeX, y = yz % sizeY, z = yz / sizeY;
		double minX = x * h, minY = y * h, minZ = z * h;
		double maxX = (x + 1) * h, maxY = (y + bulk / (double) SolidVolume.SVQ_PER_CELL) * h, maxZ = (z + 1) * h;
		// representativeRadius is capped at .2h. Include closed tangencies at query limits.
		double radius = (float) h * .20F;
		int x0 = coordinate(Math.nextDown(minX - radius), sizeX), x1 = coordinate(maxX + radius, sizeX);
		int y0 = coordinate(Math.nextDown(minY - radius), sizeY), y1 = coordinate(maxY + radius, sizeY);
		int z0 = coordinate(Math.nextDown(minZ - radius), sizeZ), z1 = coordinate(maxZ + radius, sizeZ);
		for (int iz = z0; iz <= z1; iz++) for (int iy = y0; iy <= y1; iy++) for (int ix = x0; ix <= x1; ix++) {
			for (int index = head[(iz * sizeY + iy) * sizeX + ix]; index >= 0; index = next[index]) {
				if (!spend(1)) return false;
				if (excluded.test(index)) continue;
				var p = parcels.parcel(index);
				if (GranularSphereGeometry.touchesBox(p.x(), p.y(), p.z(),
						GranularMechanics.representativeRadius(p.solidVolumeSvq(), (float) h),
						minX, minY, minZ, maxX, maxY, maxZ) && !acceptBlocking.test(index)) return false;
			}
		}
		return true;
	}

	void removedSwap(int index) {
		int last = --count;
		unlink(index);
		if (index != last) {
			cells[index] = cells[last]; next[index] = next[last]; previous[index] = previous[last];
			if (previous[index] < 0) head[cells[index]] = index; else next[previous[index]] = index;
			if (next[index] >= 0) previous[next[index]] = index;
		}
	}

	void append(int index) {
		if (index != count) throw new IllegalStateException("Bed admission index append is out of order");
		var p = parcels.parcel(index);
		int cell = (coordinate(p.z(), sizeZ) * sizeY + coordinate(p.y(), sizeY)) * sizeX + coordinate(p.x(), sizeX);
		cells[index] = cell; previous[index] = -1; next[index] = head[cell];
		if (head[cell] >= 0) previous[head[cell]] = index;
		head[cell] = index; count++;
	}

	private void unlink(int index) {
		if (previous[index] < 0) head[cells[index]] = next[index]; else next[previous[index]] = next[index];
		if (next[index] >= 0) previous[next[index]] = previous[index];
	}
	private int coordinate(double p, int size) { return Math.max(0, Math.min(size - 1, (int) Math.floor(p / h))); }
}
