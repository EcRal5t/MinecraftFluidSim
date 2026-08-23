package com.example.granular;

/** Atomic inventory-only parcel/deposit transitions. Mechanics and scheduling are layered above this class. */
public final class GranularInventory {
	private final GranularParcelStore parcels;
	private final GranularDepositStore deposits;

	public GranularInventory(GranularParcelStore parcels, GranularDepositStore deposits) {
		if (parcels == null || deposits == null) throw new IllegalArgumentException("Granular stores are missing");
		this.parcels = parcels;
		this.deposits = deposits;
	}

	public boolean depositParcel(int parcelIndex, int cell, long geometricOpenSvq) {
		GranularParcelStore.Parcel parcel = parcels.parcel(parcelIndex);
		GranularDepositStore.SolidPortion portion = new GranularDepositStore.SolidPortion(
				parcel.materialHandle(), parcel.solidCompositionHandle(), parcel.solidVolumeSvq(), parcel.mass());
		if (!deposits.tryAdd(cell, portion, geometricOpenSvq)) return false;
		parcels.removeSwap(parcelIndex);
		return true;
	}

	public boolean wake(
			int cell, int materialHandle, int compositionHandle, long requestedSvq,
			float x, float y, float z, float velocityX, float velocityY, float velocityZ
	) {
		GranularDepositStore.SolidPortion planned = deposits.peekTake(
				cell, materialHandle, compositionHandle, requestedSvq);
		GranularParcelStore.Parcel parcel = new GranularParcelStore.Parcel(x, y, z,
				velocityX, velocityY, velocityZ, planned.solidVolumeSvq(), planned.mass(),
				planned.materialHandle(), planned.solidCompositionHandle(), 0, (byte) 0);
		if (!parcels.canAdd(parcel)) return false;
		GranularDepositStore.SolidPortion committed = deposits.take(
				cell, materialHandle, compositionHandle, requestedSvq);
		parcels.add(parcel);
		if (!committed.equals(planned)) throw new IllegalStateException("Granular wake plan changed before commit");
		return true;
	}

	public long totalSolidVolumeSvq() {
		return Math.addExact(parcels.totalSolidVolumeSvq(), deposits.totalSolidVolumeSvq());
	}

	public double totalMass() { return parcels.totalMass() + deposits.totalMass(); }
}
