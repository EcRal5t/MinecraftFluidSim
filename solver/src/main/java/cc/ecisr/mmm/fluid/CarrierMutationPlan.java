package cc.ecisr.mmm.fluid;

/**
 * Revision-bound intent for one carrier structural mutation. Plans are created from the live
 * mutation revision and expected marker count, then verified by the coordinator immediately
 * before the first column write; every possibly-failing registry, capacity and ledger check
 * happens in the plan phase, so the commit itself must not throw a foreseeable rejection.
 */
record CarrierMutationPlan(Kind kind, long baseRevision, int expectedCount, int targetOrdinal) {
	enum Kind { APPEND, SWAP_REMOVE, WHOLESALE_COUNT }

	static CarrierMutationPlan append(long baseRevision, int expectedCount) {
		return new CarrierMutationPlan(Kind.APPEND, baseRevision, expectedCount, expectedCount);
	}

	static CarrierMutationPlan swapRemove(long baseRevision, int expectedCount, int targetOrdinal) {
		return new CarrierMutationPlan(Kind.SWAP_REMOVE, baseRevision, expectedCount, targetOrdinal);
	}

	static CarrierMutationPlan wholesaleCount(long baseRevision, int expectedCount) {
		return new CarrierMutationPlan(Kind.WHOLESALE_COUNT, baseRevision, expectedCount, -1);
	}
}
