package cc.ecisr.mmm.granular;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import java.util.function.IntToLongFunction;

/** Bounded closure of active spheres intersecting final candidate beds at their own legal targets. */
final class GranularSettlementGeometryPlan {
	private final GranularInventory inventory;
	private final GranularBedAdmission admission;
	private final int[] members;
	private final int[] targets;
	private final long[] open;
	private final boolean[] included;
	private int count;

	GranularSettlementGeometryPlan(GranularInventory inventory, GranularBedAdmission admission, int capacity) {
		this.inventory = inventory; this.admission = admission;
		members = new int[capacity]; included = new boolean[capacity];
		targets = new int[capacity]; open = new long[capacity];
	}

	GranularInventory.SettlementPlan find(int seed, int cell, IntUnaryOperator legalTarget, IntToLongFunction geometricOpen) {
		count = 1; members[0] = seed; targets[0] = cell; open[0] = geometricOpen.applyAsLong(cell); included[seed] = true;
		try {
			while (!admission.budgetBlocked()) {
				var plan = inventory.planSettlement(Arrays.copyOf(members, count), Arrays.copyOf(targets, count),
						Arrays.copyOf(open, count), admission::spend);
				if (plan == null) return null;
				int before = count;
				for (var target : plan.targets()) {
					if (!admission.spend(1) || !admission.allows(target.cell(), target.bulkVolumeSvq(), index -> included[index], index -> {
						int destination = legalTarget.applyAsInt(index);
						if (destination < 0) return false;
						included[index] = true; members[count] = index; targets[count] = destination;
						open[count++] = geometricOpen.applyAsLong(destination);
						return true;
					})) return null;
				}
				if (count == before) return plan;
			}
			return null;
		} finally {
			for (int index = 0; index < count; index++) included[members[index]] = false;
		}
	}
}
