package cc.ecisr.mmm.simulation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Testable takeover transaction ordering; Minecraft world access stays behind a narrow callback. */
final class TakeoverTransaction {
	private TakeoverTransaction() { }

	static BeginResult begin(
			TakeoverJournalFiles files,
			TakeoverJournal journal,
			String warning,
			WorldAccess world,
			FailureInjector failures
	) throws IOException {
		files.assertUnlockedForTakeover();
		failures.hit(FailurePoint.BEFORE_JOURNAL_WRITE, 0);
		files.publishJournal(journal);
		failures.hit(FailurePoint.AFTER_JOURNAL_PUBLISH, 0);
		files.publishWarning(warning);

		List<Long> removedPositions = new ArrayList<>();
		for (TakeoverJournal.BlockEntry block : journal.blocks()) {
			if (!world.remove(journal.dimensionId(), block)) continue;
			removedPositions.add(block.packedPosition());
			failures.hit(FailurePoint.AFTER_REMOVE_BLOCK, removedPositions.size());
		}
		return new BeginResult(List.copyOf(removedPositions));
	}

	static RecoveryResult recover(
			TakeoverJournalFiles files,
			WorldAccess world,
			FailureInjector failures
	) throws IOException {
		if (!files.hasJournal()) {
			if (files.hasWarning()) {
				throw new IOException("Takeover WARNING exists without its NBT journal; recovery must be checked manually");
			}
			files.deleteStaleTemporaryFiles();
			return RecoveryResult.NONE;
		}

		TakeoverJournal journal = files.readJournal();
		if (!world.isDimensionLoaded(journal.dimensionId())) {
			throw new IOException("Takeover journal dimension is not loaded: " + journal.dimensionId());
		}

		int restored = 0;
		int alreadyRestored = 0;
		int skipped = 0;
		int processed = 0;
		try {
			for (TakeoverJournal.BlockEntry block : journal.blocks()) {
				RestoreOutcome outcome = world.restore(journal.dimensionId(), block);
				switch (outcome) {
					case RESTORED -> restored++;
					case ALREADY_RESTORED -> alreadyRestored++;
					case SKIPPED_OCCUPIED -> skipped++;
				}
				processed++;
				failures.hit(FailurePoint.AFTER_RESTORE_BLOCK, processed);
			}
		} catch (IOException | RuntimeException exception) {
			throw new RecoveryException(false, restored, alreadyRestored, skipped, exception);
		}

		try {
			failures.hit(FailurePoint.BEFORE_COMPLETION_DELETE, 0);
			files.deleteCompletedTransaction();
		} catch (IOException exception) {
			throw new RecoveryException(true, restored, alreadyRestored, skipped, exception);
		}
		return new RecoveryResult(true, restored, alreadyRestored, skipped);
	}

	enum FailurePoint {
		BEFORE_JOURNAL_WRITE,
		AFTER_JOURNAL_PUBLISH,
		AFTER_REMOVE_BLOCK,
		AFTER_RESTORE_BLOCK,
		BEFORE_COMPLETION_DELETE
	}

	@FunctionalInterface
	interface FailureInjector {
		FailureInjector NONE = (point, index) -> { };

		void hit(FailurePoint point, int oneBasedBlockIndex) throws IOException;
	}

	interface WorldAccess {
		boolean isDimensionLoaded(String dimensionId) throws IOException;

		boolean remove(String dimensionId, TakeoverJournal.BlockEntry block) throws IOException;

		RestoreOutcome restore(String dimensionId, TakeoverJournal.BlockEntry block) throws IOException;
	}

	enum RestoreOutcome {
		RESTORED,
		ALREADY_RESTORED,
		SKIPPED_OCCUPIED
	}

	record BeginResult(List<Long> removedPositions) {
		int removedBlocks() {
			return removedPositions.size();
		}
	}

	record RecoveryResult(boolean found, int restoredBlocks, int alreadyRestoredBlocks, int skippedBlocks) {
		private static final RecoveryResult NONE = new RecoveryResult(false, 0, 0, 0);
	}

	static final class RecoveryException extends IOException {
		private final boolean worldPassCompleted;
		private final int restoredBlocks;
		private final int alreadyRestoredBlocks;
		private final int skippedBlocks;

		private RecoveryException(
				boolean worldPassCompleted,
				int restoredBlocks,
				int alreadyRestoredBlocks,
				int skippedBlocks,
				Throwable cause
		) {
			super(cause.getMessage(), cause);
			this.worldPassCompleted = worldPassCompleted;
			this.restoredBlocks = restoredBlocks;
			this.alreadyRestoredBlocks = alreadyRestoredBlocks;
			this.skippedBlocks = skippedBlocks;
		}

		boolean worldPassCompleted() {
			return worldPassCompleted;
		}

		int restoredBlocks() {
			return restoredBlocks;
		}

		int alreadyRestoredBlocks() {
			return alreadyRestoredBlocks;
		}

		int skippedBlocks() {
			return skippedBlocks;
		}
	}
}
