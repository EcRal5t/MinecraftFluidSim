package cc.ecisr.mmm.simulation;

/** Outcome of a durable world restoration: takeover journal recovery or region removal. */
public record RestorationStats(
		boolean journalFound,
		int restoredBlocks,
		int alreadyRestoredBlocks,
		int skippedBlocks,
		int ownershipRestoredBlocks,
		int ownershipSkippedBlocks,
		boolean completed,
		String failure
) {
	public RestorationStats(
			boolean journalFound,
			int restoredBlocks,
			int alreadyRestoredBlocks,
			int skippedBlocks,
			boolean completed,
			String failure
	) {
		this(journalFound, restoredBlocks, alreadyRestoredBlocks, skippedBlocks, 0, 0, completed, failure);
	}

	static RestorationStats failed(
			boolean journalFound,
			int restoredBlocks,
			int alreadyRestoredBlocks,
			int skippedBlocks,
			String failure
	) {
		return new RestorationStats(journalFound, restoredBlocks, alreadyRestoredBlocks, skippedBlocks, 0, 0, false, failure);
	}
}
