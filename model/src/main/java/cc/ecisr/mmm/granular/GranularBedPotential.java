package cc.ecisr.mmm.granular;

import cc.ecisr.mmm.units.SignedDyadic;

/** Exact gravitational first-moment admission for uniform-bed shape transfers. */
public final class GranularBedPotential {
	private GranularBedPotential() { }

	/** Compares exact mass first moments; stored mass rounding has its separate plan bound. */
	public static boolean allowsSettlement(GranularInventory.SettlementPlan plan, float cellSize,
	                             int sizeX, int sizeY, double gravity) {
		if (plan == null || !(Float.isFinite(cellSize) && cellSize > 0)
				|| sizeX <= 0 || sizeY <= 0 || !(Double.isFinite(gravity) && gravity >= 0)) {
			throw new IllegalArgumentException("Settlement energy geometry or gravity is invalid");
		}
		if (gravity == 0) return true;
		SignedDyadic h = SignedDyadic.of(cellSize);
		SignedDyadic before = SignedDyadic.ZERO, after = SignedDyadic.ZERO;
		for (var target : plan.targets()) {
			SignedDyadic origin = h.multiply(SignedDyadic.of(target.cell() / sizeX % sizeY, 0));
			SignedDyadic halfHeight = h.multiply(SignedDyadic.of(target.bulkVolumeSvq(), -25));
			after = after.add(target.oldBedMass().multiply(h).multiply(
					SignedDyadic.of(target.bulkVolumeSvq() - target.oldBedBulkVolumeSvq(), -25)))
					.add(target.addedMass().multiply(origin.add(halfHeight)));
		}
		for (var parcel : plan.parcels()) {
			SignedDyadic mass = SignedDyadic.of(parcel.mass());
			before = before.add(mass.multiply(SignedDyadic.of(parcel.y())));
		}
		return after.compareTo(before) <= 0;
	}

	/** Two uniform beds share the moved conceptual mass before either store mutation. */
	public static boolean allowsRelaxation(GranularDepositStore store, int from, int to,
	                                      GranularDepositStore.SolidPortion moved, float cellSize,
	                                      int sizeX, int sizeY, double gravity) {
		if (store == null || moved == null || from == to || !(Float.isFinite(cellSize) && cellSize > 0)
				|| sizeX <= 0 || sizeY <= 0 || !(Double.isFinite(gravity) && gravity >= 0)) {
			throw new IllegalArgumentException("Bed relaxation energy input is invalid");
		}
		if (gravity == 0) return true;
		if (!moved.equals(store.peekTake(from, moved.materialHandle(), moved.solidCompositionHandle(), moved.solidVolumeSvq()))) {
			throw new IllegalArgumentException("Bed relaxation portion does not match its source");
		}
		long sourceBulk = store.previewCellBulkVolumeAfterTake(from, moved.materialHandle(), moved.solidCompositionHandle(), moved.solidVolumeSvq());
		long targetBulk = store.previewCellBulkVolumeAfterAdd(to, moved);
		SignedDyadic[] masses = {SignedDyadic.ZERO, SignedDyadic.ZERO};
		store.forEachGroupInCell(from, (cell, porosity, material, composition, volume, mass) -> masses[0] = masses[0].add(SignedDyadic.of(mass)));
		store.forEachGroupInCell(to, (cell, porosity, material, composition, volume, mass) -> masses[1] = masses[1].add(SignedDyadic.of(mass)));
		SignedDyadic h = SignedDyadic.of(cellSize), mass = SignedDyadic.of(moved.mass());
		SignedDyadic sourceCenter = h.multiply(SignedDyadic.of(from / sizeX % sizeY, 0).add(SignedDyadic.of(sourceBulk, -25)));
		SignedDyadic targetCenter = h.multiply(SignedDyadic.of(to / sizeX % sizeY, 0).add(SignedDyadic.of(targetBulk, -25)));
		SignedDyadic change = masses[0].multiply(h).multiply(SignedDyadic.of(sourceBulk - store.cellBulkVolumeSvq(from), -25))
				.add(masses[1].multiply(h).multiply(SignedDyadic.of(targetBulk - store.cellBulkVolumeSvq(to), -25)))
				.add(mass.multiply(targetCenter.subtract(sourceCenter)));
		return change.signum() <= 0;
	}

	/** Release to an active carrier needs modeled work whenever its combined first moment rises. */
	public static boolean allowsWake(GranularDepositStore store, int cell, GranularDepositStore.SolidPortion moved,
	                                 float targetY, float cellSize, int sizeX, int sizeY, double gravity) {
		if (store == null || moved == null || !(Float.isFinite(targetY) && targetY >= 0)
				|| !(Float.isFinite(cellSize) && cellSize > 0) || sizeX <= 0 || sizeY <= 0
				|| !(Double.isFinite(gravity) && gravity >= 0)) {
			throw new IllegalArgumentException("Bed wake energy input is invalid");
		}
		if (gravity == 0) return true;
		if (!moved.equals(store.peekTake(cell, moved.materialHandle(), moved.solidCompositionHandle(), moved.solidVolumeSvq()))) {
			throw new IllegalArgumentException("Bed wake portion does not match its source");
		}
		long remainingBulk = store.previewCellBulkVolumeAfterTake(cell, moved.materialHandle(), moved.solidCompositionHandle(), moved.solidVolumeSvq());
		SignedDyadic[] originalMass = {SignedDyadic.ZERO};
		store.forEachGroupInCell(cell, (ignored, porosity, material, composition, volume, mass) -> originalMass[0] = originalMass[0].add(SignedDyadic.of(mass)));
		SignedDyadic h = SignedDyadic.of(cellSize);
		SignedDyadic remainingCenter = h.multiply(SignedDyadic.of(cell / sizeX % sizeY, 0).add(SignedDyadic.of(remainingBulk, -25)));
		SignedDyadic change = originalMass[0].multiply(h).multiply(SignedDyadic.of(remainingBulk - store.cellBulkVolumeSvq(cell), -25))
				.add(SignedDyadic.of(moved.mass()).multiply(SignedDyadic.of(targetY).subtract(remainingCenter)));
		return change.signum() <= 0;
	}
}
