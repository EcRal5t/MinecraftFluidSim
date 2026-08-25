package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.transfer.ContainerTransferJournal;

/** Pure recovery decision at the region HEAD commit boundary. */
final class ContainerTransferRecovery {
	private ContainerTransferRecovery() { }

	static Decision decide(long head, ContainerTransferJournal journal, boolean liveSolverDirty) {
		return decide(head, -1L, journal, liveSolverDirty);
	}

	static Decision decide(long head, long headManifestChecksum, ContainerTransferJournal journal, boolean liveSolverDirty) {
		if (head == journal.baseGeneration()) {
			return liveSolverDirty ? Decision.LOCK_LIVE_SOLVER_DIRTY : Decision.DISCARD_UNCOMMITTED;
		}
		if (head == journal.targetGeneration()) {
			if (journal.targetManifestChecksum() >= 0L
					&& headManifestChecksum != journal.targetManifestChecksum()) return Decision.LOCK_CHECKSUM_MISMATCH;
			return journal.phase() == ContainerTransferJournal.Phase.REGION_COMMITTED
					? Decision.CONVERGE_AFTER_ITEM
					: Decision.LOCK_TARGET_WITHOUT_AFTER_ITEM;
		}
		return Decision.LOCK_GENERATION_MISMATCH;
	}

	enum Decision {
		DISCARD_UNCOMMITTED,
		CONVERGE_AFTER_ITEM,
		LOCK_LIVE_SOLVER_DIRTY,
		LOCK_TARGET_WITHOUT_AFTER_ITEM,
		LOCK_CHECKSUM_MISMATCH,
		LOCK_GENERATION_MISMATCH
	}
}
