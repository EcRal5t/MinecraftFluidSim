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
	private final int[] members;
	private final int[] targets;
	private final long[] open;
	private final boolean[] included;
	private final TreeMap<Integer, Target> packing = new TreeMap<>();
	private int count;
	private record Target(int cell, long open, GranularDepositStore.AdditionPacking packing) { }

	GranularSettlementGeometryPlan(GranularInventory inventory, GranularParcelStore parcels, GranularDepositStore deposits,
	                              GranularBedAdmission admission, int capacity) {
		this.inventory = inventory; this.parcels = parcels; this.deposits = deposits; this.admission = admission;
		members = new int[capacity]; included = new boolean[capacity];
		targets = new int[capacity]; open = new long[capacity];
	}

	GranularInventory.SettlementPlan find(int seed, int cell, IntUnaryOperator legalTarget, IntToLongFunction geometricOpen) {
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
