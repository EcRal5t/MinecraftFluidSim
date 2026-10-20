package mmm.granular;

import java.util.Arrays;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import java.util.function.IntToLongFunction;

/** Bounded closure of active spheres intersecting final candidate beds at their own legal targets. */
final class GranularSettlementGeometryPlan {
	private final GranularInventory inventory;
	private final GranularParcelStore parcels;
	private final GranularDepositStore deposits;
	private final GranularBedAdmission admission;
	private int[] members = new int[0], targets = new int[0];
	private long[] open = new long[0];
	private boolean[] included = new boolean[0];
	private final TreeMap<Integer, Target> packing = new TreeMap<>();
	private int count;
	private record Target(int cell, long open, GranularDepositStore.AdditionPacking packing) { }

	GranularSettlementGeometryPlan(GranularInventory inventory, GranularParcelStore parcels, GranularDepositStore deposits,
	                              GranularBedAdmission admission) {
		this.inventory = inventory; this.parcels = parcels; this.deposits = deposits; this.admission = admission;
	}

	GranularInventory.SettlementPlan find(int seed, int cell, IntUnaryOperator legalTarget, IntToLongFunction geometricOpen) {
		ensureCapacity(parcels.count());
		long parcelRevision = parcels.mutationRevision(); count = 0;
		try {
			if (!include(seed, cell, geometricOpen)) return null;
			while (!admission.budgetBlocked()) {
				int before = count;
				// New receiving cells join the next pass; each current bed keeps the canonical cell order.
				for (var target : packing.values().toArray(Target[]::new)) {
					long bulk = target.packing.bulkVolumeSvq();
					if (bulk > target.open || !admission.spend(1) || !admission.allows(target.cell, bulk, index -> included[index], index -> {
						int destination = legalTarget.applyAsInt(index);
						return destination >= 0 && include(index, destination, geometricOpen);
					})) return null;
				}
				if (parcels.mutationRevision() != parcelRevision)
					throw new IllegalStateException("Active parcels changed during settlement closure");
				if (count == before) {
					// Composition ownership, final mass storage and thermal audit are planned once for the complete cohort.
					return inventory.planSettlement(Arrays.copyOf(members, count), Arrays.copyOf(targets, count),
							Arrays.copyOf(open, count), admission::spend);
				}
			}
			return null;
		} finally {
			for (int index = 0; index < count; index++) included[members[index]] = false;
			packing.clear();
		}
	}
	private void ensureCapacity(int required){
		if(required<=members.length)return;
		int grown=Math.min(parcels.capacity(),Math.max(required,members.length+Math.max(16,members.length/2)));
		members=new int[grown];targets=new int[grown];open=new long[grown];included=new boolean[grown];
	}
	long bulkVolumeAfterAdd(int cell, GranularDepositStore.SolidPortion portion) {
		var target = packing.get(cell);
		return target == null ? deposits.previewCellBulkVolumeAfterAdd(cell, portion)
				: target.packing.bulkVolumeSvqAfterAdd(portion);
	}
	private boolean include(int index, int cell, IntToLongFunction geometricOpen) {
		if (!admission.spend(1)) return false;
		long capacity = geometricOpen.applyAsLong(cell);
		if (capacity <= 0) return false;
		var target = packing.computeIfAbsent(cell, ignored -> new Target(cell, capacity, deposits.additionPacking(cell)));
		if (target.open != capacity) throw new IllegalStateException("Settlement target capacity changed during closure");
		var parcel = parcels.parcel(index);
		target.packing.add(new GranularDepositStore.SolidPortion(parcel.materialHandle(), parcel.solidCompositionHandle(),
				parcel.solidVolumeSvq(), parcel.massUnits()));
		included[index] = true; members[count] = index; targets[count] = cell; open[count++] = capacity;
		return target.packing.bulkVolumeSvq() <= capacity;
	}
}
