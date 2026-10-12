package mmm.granular;

import java.util.Arrays;
import java.util.function.IntToLongFunction;

/** Exact-SVQ occupancy and derived pressure coupling fields for one bounded solver domain. */
public final class GranularCouplingFields {
	private final int sizeX, sizeY, sizeZ, cellCount;
	private final double cellSize;
	private final long[] solidSvq;
	private final long[] fixedCellSvq;
	private final int[] candidateCell = new int[8];
	private final double[] candidateWeight = new double[8];
	private final long[] candidateShare = new long[8];
	private final double[] candidateRemainder = new double[8];
	private long totalSolidSvq;
	private long fixedSolidSvq;
	private boolean fixedOnly = true;

	public GranularCouplingFields(int sizeX, int sizeY, int sizeZ, double cellSize) {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || !(Double.isFinite(cellSize) && cellSize > 0.0)) {
			throw new IllegalArgumentException("Granular coupling dimensions are invalid");
		}
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.cellSize = cellSize;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.solidSvq = new long[cellCount];
		this.fixedCellSvq = new long[cellCount];
	}

	/** Replaces fixed cell-local occupancy (for example coherent solid) without touching granular truth. */
	public void setFixedCellSvq(long[] fixed) {
		if (fixed == null || fixed.length != cellCount) {
			throw new IllegalArgumentException("Fixed solid occupancy dimensions differ");
		}
		long total = 0L;
		for (long value : fixed) {
			if (value < 0L) throw new IllegalArgumentException("Fixed solid occupancy cannot be negative");
			total = Math.addExact(total, value);
		}
		System.arraycopy(fixed, 0, fixedCellSvq, 0, cellCount);
		System.arraycopy(fixed, 0, solidSvq, 0, cellCount);
		totalSolidSvq = total;
		fixedSolidSvq = total;
		fixedOnly = true;
	}

	/** Rebuilds a fixed-only field when no granular stores are bound. */
	public void rebuildFixed() {
		if (fixedOnly) return;
		System.arraycopy(fixedCellSvq, 0, solidSvq, 0, cellCount);
		totalSolidSvq = fixedSolidSvq;
		fixedOnly = true;
	}

	public void rebuild(GranularParcelStore parcels, GranularDepositStore deposits) {
		rebuild(parcels, deposits, (index, parcel) -> new Position(parcel.x(), parcel.y(), parcel.z()));
	}

	public void rebuild(GranularParcelStore parcels, GranularDepositStore deposits, PositionSource positions) {
		if (parcels == null || deposits == null || deposits.cellCount() != cellCount) {
			throw new IllegalArgumentException("Granular coupling inventory dimensions differ");
		}
		if (positions == null) throw new IllegalArgumentException("Granular position source is missing");
		if (parcels.count() == 0 && deposits.groupCount() == 0) { rebuildFixed(); return; }
		fixedOnly = false;
		System.arraycopy(fixedCellSvq, 0, solidSvq, 0, cellCount);
		totalSolidSvq = 0L;
		deposits.forEachCell((cell, volume, porosity) -> solidSvq[cell] = Math.addExact(solidSvq[cell], volume));
		for (int parcel = 0; parcel < parcels.count(); parcel++) {
			GranularParcelStore.Parcel inventory = parcels.parcel(parcel);
			Position position = positions.position(parcel, inventory);
			if (position == null) throw new IllegalStateException("Granular position source returned null");
			distribute(position.x(), position.y(), position.z(), inventory.solidVolumeSvq());
		}
		long expected = Math.addExact(fixedSolidSvq,
				Math.addExact(parcels.totalSolidVolumeSvq(), deposits.totalSolidVolumeSvq()));
		long actual = sumSolidSvq();
		if (actual != expected) throw new IllegalStateException("Granular occupancy does not re-sum to exact inventory");
		totalSolidSvq = actual;
	}

	public long solidSvq(int cell) { requireCell(cell); return solidSvq[cell]; }
	public long[] copySolidSvq() { return solidSvq.clone(); }
	public boolean matchesSolidSvq(long[] expected) {
		return expected != null && expected.length == cellCount && Arrays.equals(solidSvq, expected);
	}
	public long totalSolidSvq() { return totalSolidSvq; }
	public void clear() {
		rebuildFixed();
	}
	private long sumSolidSvq() {
		long total = 0L;
		for (long value : solidSvq) total = Math.addExact(total, value);
		return total;
	}

	public float effectiveOpenCellVolume(int cell, float geometricOpenVolume) {
		requireCell(cell);
		if (!(Float.isFinite(geometricOpenVolume) && geometricOpenVolume >= 0.0F)) {
			throw new IllegalArgumentException("Geometric open volume is invalid");
		}
		return Math.max(0.0F, geometricOpenVolume - SolidVolume.toCellVolume(solidSvq[cell]));
	}

	public float faceVoidScale(int firstCell, float firstOpen, int secondCell, float secondOpen) {
		float first = voidFraction(firstCell, firstOpen), second = voidFraction(secondCell, secondOpen);
		return Math.min(first, second);
	}

	/** Plans one positioned parcel against the same exact 8-cell weights used by the production rebuild. */
	public boolean tryPlanParcel(
			long[] plannedSolidSvq, Position position, long parcelSvq, IntToLongFunction geometricCapacity
	) {
		if (plannedSolidSvq == null || plannedSolidSvq.length != cellCount || position == null
				|| geometricCapacity == null) throw new IllegalArgumentException("Granular placement plan is incomplete");
		int candidates = allocate(position.x(), position.y(), position.z(), parcelSvq);
		for (int candidate = 0; candidate < candidates; candidate++) {
			int cell = candidateCell[candidate];
			long capacity = geometricCapacity.applyAsLong(cell);
			if (capacity < 0L || Math.addExact(plannedSolidSvq[cell], candidateShare[candidate]) > capacity) return false;
		}
		for (int candidate = 0; candidate < candidates; candidate++) {
			int cell = candidateCell[candidate];
			plannedSolidSvq[cell] = Math.addExact(plannedSolidSvq[cell], candidateShare[candidate]);
		}
		return true;
	}

	/** Read-only capacity check over the two parcels' at-most-eight-cell supports. */
	public boolean canReplaceParcel(Position previous, long previousSvq, Position replacement, long replacementSvq,
			IntToLongFunction geometricCapacity) {
		if (replacement == null || geometricCapacity == null || (previous == null) != (previousSvq == 0)) {
			throw new IllegalArgumentException("Granular replacement plan is incomplete");
		}
		int removedCount = previous == null ? 0 : allocate(previous.x(), previous.y(), previous.z(), previousSvq);
		int[] removedCells = Arrays.copyOf(candidateCell, removedCount);
		long[] removedShares = Arrays.copyOf(candidateShare, removedCount);
		for (int index = 0; index < removedCount; index++) if (solidSvq[removedCells[index]] < removedShares[index]) {
			throw new IllegalStateException("Granular replacement plan underflow");
		}
		int addedCount = allocate(replacement.x(), replacement.y(), replacement.z(), replacementSvq);
		for (int index = 0; index < addedCount; index++) {
			int cell = candidateCell[index];
			long occupied = solidSvq[cell];
			for (int removed = 0; removed < removedCount; removed++) {
				if (removedCells[removed] == cell) occupied -= removedShares[removed];
			}
			long capacity = geometricCapacity.applyAsLong(cell);
			if (capacity < 0 || Math.addExact(occupied, candidateShare[index]) > capacity) return false;
		}
		return true;
	}

	/** Removes one parcel using the exact same shares as rebuild/placement planning. */
	public void removePlannedParcel(long[] plannedSolidSvq, Position position, long parcelSvq) {
		if (plannedSolidSvq == null || plannedSolidSvq.length != cellCount || position == null) {
			throw new IllegalArgumentException("Granular removal plan is incomplete");
		}
		int candidates = allocate(position.x(), position.y(), position.z(), parcelSvq);
		for (int candidate = 0; candidate < candidates; candidate++) {
			int cell = candidateCell[candidate];
			if (plannedSolidSvq[cell] < candidateShare[candidate]) {
				throw new IllegalStateException("Granular placement plan underflow");
			}
		}
		for (int candidate = 0; candidate < candidates; candidate++) {
			int cell = candidateCell[candidate];
			plannedSolidSvq[cell] -= candidateShare[candidate];
		}
	}

	/** Returns this parcel's exact largest-remainder share in one target cell. */
	public long parcelShareAtCell(Position position, long parcelSvq, int targetCell) {
		requireCell(targetCell);
		if (position == null) throw new IllegalArgumentException("Granular parcel-share position is missing");
		int candidates = allocate(position.x(), position.y(), position.z(), parcelSvq);
		for (int candidate = 0; candidate < candidates; candidate++) {
			if (candidateCell[candidate] == targetCell) return candidateShare[candidate];
		}
		return 0L;
	}

	/** Plans cell-local deposit skeleton volume against the same exact geometric capacity. */
	public boolean tryPlanCell(
			long[] plannedSolidSvq, int cell, long solidSvq, IntToLongFunction geometricCapacity
	) {
		if (plannedSolidSvq == null || plannedSolidSvq.length != cellCount || geometricCapacity == null) {
			throw new IllegalArgumentException("Granular cell plan is incomplete");
		}
		requireCell(cell);
		if (solidSvq <= 0L) throw new IllegalArgumentException("Granular cell plan volume is invalid");
		long capacity = geometricCapacity.applyAsLong(cell);
		if (capacity < 0L || Math.addExact(plannedSolidSvq[cell], solidSvq) > capacity) return false;
		plannedSolidSvq[cell] += solidSvq;
		return true;
	}

	public void removePlannedCell(long[] plannedSolidSvq, int cell, long solidSvq) {
		if (plannedSolidSvq == null || plannedSolidSvq.length != cellCount) {
			throw new IllegalArgumentException("Granular cell removal plan is incomplete");
		}
		requireCell(cell);
		if (solidSvq <= 0L || plannedSolidSvq[cell] < solidSvq) {
			throw new IllegalStateException("Granular cell removal plan underflow");
		}
		plannedSolidSvq[cell] -= solidSvq;
	}

	private float voidFraction(int cell, float geometricOpen) {
		if (geometricOpen <= 0.0F) return 0.0F;
		return Math.max(0.0F, Math.min(1.0F, effectiveOpenCellVolume(cell, geometricOpen) / geometricOpen));
	}

	private void distribute(float positionX, float positionY, float positionZ, long parcelSvq) {
		int candidates = allocate(positionX, positionY, positionZ, parcelSvq);
		for (int candidate = 0; candidate < candidates; candidate++) {
			solidSvq[candidateCell[candidate]] = Math.addExact(
					solidSvq[candidateCell[candidate]], candidateShare[candidate]);
		}
	}

	private int allocate(float positionX, float positionY, float positionZ, long parcelSvq) {
		if (!(Float.isFinite(positionX) && Float.isFinite(positionY) && Float.isFinite(positionZ)) || parcelSvq <= 0L) {
			throw new IllegalArgumentException("Granular occupancy parcel is invalid");
		}
		double gx = positionX / cellSize - 0.5, gy = positionY / cellSize - 0.5, gz = positionZ / cellSize - 0.5;
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		double fx = gx - x0, fy = gy - y0, fz = gz - z0;
		int candidates = 0;
		double weightSum = 0.0;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) continue;
			double weight = (ox == 0 ? 1.0 - fx : fx) * (oy == 0 ? 1.0 - fy : fy) * (oz == 0 ? 1.0 - fz : fz);
			candidateCell[candidates] = (z * sizeY + y) * sizeX + x;
			candidateWeight[candidates] = weight;
			weightSum += weight;
			candidates++;
		}
		if (!(weightSum > 0.0)) throw new IllegalStateException("Granular parcel has no in-domain occupancy support");
		long assigned = 0L;
		for (int candidate = 0; candidate < candidates; candidate++) {
			double exact = parcelSvq * candidateWeight[candidate] / weightSum;
			long share = (long) Math.floor(exact);
			candidateShare[candidate] = share;
			candidateRemainder[candidate] = exact - share;
			assigned = Math.addExact(assigned, share);
		}
		long remaining = parcelSvq - assigned;
		while (remaining-- > 0L) {
			int best = 0;
			for (int candidate = 1; candidate < candidates; candidate++) {
				if (candidateRemainder[candidate] > candidateRemainder[best]
						|| candidateRemainder[candidate] == candidateRemainder[best]
						&& candidateCell[candidate] < candidateCell[best]) best = candidate;
			}
			candidateShare[best]++;
			candidateRemainder[best] = -1.0;
		}
		return candidates;
	}

	private void requireCell(int cell) {
		if (cell < 0 || cell >= cellCount) throw new IllegalArgumentException("Unknown granular coupling cell " + cell);
	}

	public record Position(float x, float y, float z) {
		public Position {
			if (!(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z))) {
				throw new IllegalArgumentException("Granular coupling position must be finite");
			}
		}
	}

	@FunctionalInterface
	public interface PositionSource {
		Position position(int index, GranularParcelStore.Parcel parcel);
	}
}
