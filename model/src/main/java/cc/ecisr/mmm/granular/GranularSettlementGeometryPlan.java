package cc.ecisr.mmm.granular;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/** Bounded closure of active spheres intersecting a candidate bed at one common legal target. */
final class GranularSettlementGeometryPlan {
	private final GranularInventory inventory;
	private final GranularBedAdmission admission;
	private final int[] members;
	private final boolean[] included;
	private int count;

	GranularSettlementGeometryPlan(GranularInventory inventory, GranularBedAdmission admission, int capacity) {
		this.inventory = inventory; this.admission = admission;
		members = new int[capacity]; included = new boolean[capacity];
	}

	GranularInventory.SettlementPlan find(int seed, int cell, long open, IntUnaryOperator legalTarget) {
		count = 1; members[0] = seed; included[seed] = true;
		try {
			while (!admission.budgetBlocked()) {
				var plan = inventory.planSettlement(Arrays.copyOf(members, count), cell, open, admission::spend);
				if (plan == null) return null;
				int before = count;
				boolean fits = admission.allows(cell, plan.bulkVolumeSvq(), index -> included[index], index -> {
					if (legalTarget.applyAsInt(index) != cell) return false;
					included[index] = true; members[count++] = index;
					return true;
				});
				if (!fits) return null;
				if (count == before) return plan;
			}
			return null;
		} finally {
			for (int index = 0; index < count; index++) included[members[index]] = false;
		}
	}
}
