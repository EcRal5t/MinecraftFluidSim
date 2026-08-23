package com.example.granular;

/** Atomic inventory-only parcel/deposit transitions. Mechanics and scheduling are layered above this class. */
public final class GranularInventory {
	private final GranularParcelStore parcels;
	private final GranularDepositStore deposits;
	private SolidCompositionPool compositions;

	public GranularInventory(GranularParcelStore parcels, GranularDepositStore deposits) {
		if (parcels == null || deposits == null) throw new IllegalArgumentException("Granular stores are missing");
		this.parcels = parcels;
		this.deposits = deposits;
	}

	public void bindSolidCompositions(SolidCompositionPool pool) {
		if (pool == null) throw new IllegalArgumentException("Solid composition pool is missing");
		if (compositions != null && compositions != pool) {
			throw new IllegalStateException("Granular inventory cannot change its solid composition pool");
		}
		compositions = pool;
	}

	public boolean depositParcel(int parcelIndex, int cell, long geometricOpenSvq) {
		GranularParcelStore.Parcel parcel = parcels.parcel(parcelIndex);
		if (parcel.solidCompositionHandle() != 0) {
			if (compositions == null) throw new IllegalStateException("Reactive deposit requires a solid composition pool");
			SolidCompositionRecord incoming = compositions.record(parcel.solidCompositionHandle());
			GranularDepositStore.Group compatible = deposits.matchingGroup(cell, parcel.materialHandle(), group ->
					incoming.proportionalTo(compositions.record(group.solidCompositionHandle()),
							parcel.solidVolumeSvq(), group.solidVolumeSvq()));
			if (compatible != null) {
				SolidCompositionRecord combined = compositions.record(compatible.solidCompositionHandle()).plus(incoming);
				GranularDepositStore.SolidPortion merged = new GranularDepositStore.SolidPortion(
						parcel.materialHandle(), compatible.solidCompositionHandle(),
						parcel.solidVolumeSvq(), parcel.mass());
				if (!deposits.tryAdd(cell, merged, geometricOpenSvq)) return false;
				compositions.overwrite(compatible.solidCompositionHandle(), combined);
				compositions.release(parcel.solidCompositionHandle());
				parcels.removeSwap(parcelIndex);
				return true;
			}
		}
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
