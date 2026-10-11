package cc.ecisr.mmm.granular;

import cc.ecisr.mmm.units.SignedDyadic;

/** Necessary energy admission for converting representative parcels to a uniform scalar bed. */
public final class GranularSettlementEnergy {
	private GranularSettlementEnergy() { }

	/** Compares exact mass first moments; stored mass rounding has its separate plan bound. */
	public static boolean allows(GranularInventory.SettlementPlan plan, float cellSize,
	                             int sizeX, int sizeY, double gravity) {
		if (plan == null || !(Float.isFinite(cellSize) && cellSize > 0)
				|| sizeX <= 0 || sizeY <= 0 || !(Double.isFinite(gravity) && gravity >= 0)) {
			throw new IllegalArgumentException("Settlement energy geometry or gravity is invalid");
		}
		if (gravity == 0) return true;
		SignedDyadic h = SignedDyadic.of(cellSize);
		SignedDyadic origin = h.multiply(SignedDyadic.of(plan.cell() / sizeX % sizeY, 0));
		SignedDyadic oldMass = plan.oldBedMass(), finalMass = oldMass;
		SignedDyadic before = oldMass.multiply(h).multiply(SignedDyadic.of(plan.oldBedBulkVolumeSvq(), -25));
		for (var parcel : plan.parcels()) {
			SignedDyadic mass = SignedDyadic.of(parcel.mass());
			finalMass = finalMass.add(mass);
			before = before.add(mass.multiply(SignedDyadic.of(parcel.y()).subtract(origin)));
		}
		SignedDyadic after = finalMass.multiply(h).multiply(SignedDyadic.of(plan.bulkVolumeSvq(), -25));
		return after.compareTo(before) <= 0;
	}
}
