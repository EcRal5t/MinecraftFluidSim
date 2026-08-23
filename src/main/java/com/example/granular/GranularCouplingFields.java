package com.example.granular;

import java.util.Arrays;

/** Exact-SVQ occupancy and derived pressure coupling fields for one bounded solver domain. */
public final class GranularCouplingFields {
	private final int sizeX, sizeY, sizeZ, cellCount;
	private final double cellSize;
	private final long[] solidSvq;
	private final int[] candidateCell = new int[8];
	private final double[] candidateWeight = new double[8];
	private final long[] candidateShare = new long[8];
	private final double[] candidateRemainder = new double[8];

	public GranularCouplingFields(int sizeX, int sizeY, int sizeZ, double cellSize) {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || !(Double.isFinite(cellSize) && cellSize > 0.0)) {
			throw new IllegalArgumentException("Granular coupling dimensions are invalid");
		}
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.cellSize = cellSize;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.solidSvq = new long[cellCount];
	}

	public void rebuild(GranularParcelStore parcels, GranularDepositStore deposits) {
		rebuild(parcels, deposits, (index, parcel) -> new Position(parcel.x(), parcel.y(), parcel.z()));
	}

	public void rebuild(GranularParcelStore parcels, GranularDepositStore deposits, PositionSource positions) {
		if (parcels == null || deposits == null || deposits.cellCount() != cellCount) {
			throw new IllegalArgumentException("Granular coupling inventory dimensions differ");
		}
		if (positions == null) throw new IllegalArgumentException("Granular position source is missing");
		Arrays.fill(solidSvq, 0L);
		deposits.forEachCell((cell, volume, porosity) -> solidSvq[cell] = Math.addExact(solidSvq[cell], volume));
		for (int parcel = 0; parcel < parcels.count(); parcel++) {
			GranularParcelStore.Parcel inventory = parcels.parcel(parcel);
			Position position = positions.position(parcel, inventory);
			if (position == null) throw new IllegalStateException("Granular position source returned null");
			distribute(position.x(), position.y(), position.z(), inventory.solidVolumeSvq());
		}
		long expected = Math.addExact(parcels.totalSolidVolumeSvq(), deposits.totalSolidVolumeSvq());
		long actual = totalSolidSvq();
		if (actual != expected) throw new IllegalStateException("Granular occupancy does not re-sum to exact inventory");
	}

	public long solidSvq(int cell) { requireCell(cell); return solidSvq[cell]; }
	public long[] copySolidSvq() { return solidSvq.clone(); }
	public long totalSolidSvq() {
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

	public float movingSolidTargetDivergence(int cell, long nextSolidSvq, float dt) {
		requireCell(cell);
		if (!(Float.isFinite(dt) && dt > 0.0F) || nextSolidSvq < 0L) {
			throw new IllegalArgumentException("Moving-solid target input is invalid");
		}
		return (float) (-(nextSolidSvq - solidSvq[cell]) / (double) SolidVolume.SVQ_PER_CELL / dt);
	}

	private float voidFraction(int cell, float geometricOpen) {
		if (geometricOpen <= 0.0F) return 0.0F;
		return Math.max(0.0F, Math.min(1.0F, effectiveOpenCellVolume(cell, geometricOpen) / geometricOpen));
	}

	private void distribute(float positionX, float positionY, float positionZ, long parcelSvq) {
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
		for (int candidate = 0; candidate < candidates; candidate++) {
			solidSvq[candidateCell[candidate]] = Math.addExact(
					solidSvq[candidateCell[candidate]], candidateShare[candidate]);
		}
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
