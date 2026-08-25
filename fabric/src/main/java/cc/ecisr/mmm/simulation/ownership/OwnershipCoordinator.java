package cc.ecisr.mmm.simulation.ownership;

import cc.ecisr.mmm.simulation.persistence.RegionState;
import cc.ecisr.mmm.simulation.persistence.RegionStateFiles;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Joint world-ownership transaction across the two stores. Ordering contract: the region state is
 * staged and verified first, then the ownership WAL and WARNING are published. Acquire mutates
 * the world before publishing HEAD; release publishes HEAD before restoring the world so a
 * pre-commit crash never has an indistinguishable water preimage to undo. HEAD remains the sole
 * commit decision point; the committed
 * ownership registry is written after it and is rebuilt from the WAL by post-commit recovery.
 * Recovery compares the region HEAD generation with the WAL base/target, so a crash always yields
 * the complete previous state or the complete new state and never restores the preimage of
 * committed ownership.
 */
public final class OwnershipCoordinator {
	private OwnershipCoordinator() { }

	public static AcquireResult acquire(
			RegionStateFiles regionFiles,
			OwnershipFiles ownershipFiles,
			RegionState target,
			List<OwnershipJournal.BlockEntry> candidates,
			String warning,
			WorldAccess world,
			FailureInjector failures
	) throws IOException {
		Objects.requireNonNull(target, "target");
		if (candidates.isEmpty()) throw new IOException("No pure water positions to acquire");
		ownershipFiles.assertUnlockedForTransition();
		UUID regionId = target.manifest().regionId();
		String dimensionId = target.manifest().dimensionId();

		RegionStateFiles.PreparedGeneration prepared = regionFiles.prepare(target, (point, path) -> { });
		try {
			long baseGeneration = prepared.baseGeneration() == null ? 0L : prepared.baseGeneration();
			if (target.manifest().checkpointGeneration() != baseGeneration + 1L) {
				throw new IOException("Acquire target generation must be one past the committed region HEAD "
						+ baseGeneration + ", got " + target.manifest().checkpointGeneration());
			}
			failures.hit(FailurePoint.AFTER_REGION_PREPARE, 0);
			OwnershipJournal wal = new OwnershipJournal(regionId, dimensionId, baseGeneration,
					target.manifest().checkpointGeneration(), OwnershipJournal.Operation.ACQUIRE, candidates);
			ownershipFiles.publishWAL(wal);
			failures.hit(FailurePoint.AFTER_WAL_PUBLISH, 0);
			ownershipFiles.publishWarning(warning);
			failures.hit(FailurePoint.AFTER_WARNING_PUBLISH, 0);

			// Candidates are scanned and removed in the same server tick, so every position must still
			// match; anything else aborts into the recovery path instead of being silently skipped.
			int removed = 0;
			for (OwnershipJournal.BlockEntry entry : candidates) {
				if (world.removeWater(dimensionId, entry) != RemoveOutcome.REMOVED) {
					throw new IOException("Owned water position " + entry.packedPosition()
							+ " changed between scan and removal");
				}
				removed++;
				failures.hit(FailurePoint.AFTER_REMOVE_BLOCK, removed);
			}

			failures.hit(FailurePoint.BEFORE_HEAD_PUBLISH, 0);
			long published = regionFiles.commit(prepared, (point, path) -> { });
			failures.hit(FailurePoint.AFTER_HEAD_PUBLISH, 0);
			ownershipFiles.publishCommitted(new OwnershipCommitted(regionId, dimensionId, published, candidates));
			failures.hit(FailurePoint.AFTER_COMMITTED_WRITE, 0);
			failures.hit(FailurePoint.BEFORE_CLEANUP, 0);
			ownershipFiles.deleteTransaction();
			return new AcquireResult(published, candidates.size());
		} catch (IOException | RuntimeException failure) {
			if (!ownershipFiles.hasWAL()) {
				try {
					regionFiles.abort(prepared);
				} catch (IOException abortFailure) {
					failure.addSuppressed(abortFailure);
				}
			}
			throw failure;
		}
	}

	/** Caller must first refuse the release while the region still holds marker volume. */
	public static ReleaseResult release(
			RegionStateFiles regionFiles,
			OwnershipFiles ownershipFiles,
			RegionState target,
			String warning,
			WorldAccess world,
			FailureInjector failures
	) throws IOException {
		Objects.requireNonNull(target, "target");
		ownershipFiles.assertUnlockedForTransition();
		if (!ownershipFiles.hasCommitted()) throw new IOException("No committed ownership to release");
		OwnershipCommitted committed = ownershipFiles.readCommitted();
		UUID regionId = committed.regionId();
		if (!regionId.equals(target.manifest().regionId())) {
			throw new IOException("Ownership registry belongs to region " + regionId
					+ ", not " + target.manifest().regionId());
		}
		if (committed.ownedBlocks().isEmpty()) throw new IOException("Ownership registry holds no owned positions");

		RegionStateFiles.PreparedGeneration prepared = regionFiles.prepare(target, (point, path) -> { });
		try {
			long baseGeneration = prepared.baseGeneration() == null ? 0L : prepared.baseGeneration();
			if (target.manifest().checkpointGeneration() != baseGeneration + 1L) {
				throw new IOException("Release target generation must be one past the committed region HEAD "
						+ baseGeneration + ", got " + target.manifest().checkpointGeneration());
			}
			failures.hit(FailurePoint.AFTER_REGION_PREPARE, 0);
			OwnershipJournal wal = new OwnershipJournal(regionId, committed.dimensionId(), baseGeneration,
					target.manifest().checkpointGeneration(), OwnershipJournal.Operation.RELEASE, committed.ownedBlocks());
			ownershipFiles.publishWAL(wal);
			failures.hit(FailurePoint.AFTER_WAL_PUBLISH, 0);
			ownershipFiles.publishWarning(warning);
			failures.hit(FailurePoint.AFTER_WARNING_PUBLISH, 0);

			failures.hit(FailurePoint.BEFORE_HEAD_PUBLISH, 0);
			long published = regionFiles.commit(prepared, (point, path) -> { });
			failures.hit(FailurePoint.AFTER_HEAD_PUBLISH, 0);

			int restored = 0;
			int alreadyRestored = 0;
			int skipped = 0;
			int processed = 0;
			for (OwnershipJournal.BlockEntry entry : wal.blocks()) {
				switch (world.restore(committed.dimensionId(), entry)) {
					case RESTORED -> restored++;
					case ALREADY_RESTORED -> alreadyRestored++;
					case SKIPPED_OCCUPIED -> skipped++;
				}
				processed++;
				failures.hit(FailurePoint.AFTER_RESTORE_BLOCK, processed);
			}

			ownershipFiles.publishCommitted(new OwnershipCommitted(regionId, committed.dimensionId(), published, List.of()));
			failures.hit(FailurePoint.AFTER_COMMITTED_WRITE, 0);
			failures.hit(FailurePoint.BEFORE_CLEANUP, 0);
			ownershipFiles.deleteTransaction();
			return new ReleaseResult(published, restored, alreadyRestored, skipped);
		} catch (IOException | RuntimeException failure) {
			if (!ownershipFiles.hasWAL()) {
				try {
					regionFiles.abort(prepared);
				} catch (IOException abortFailure) {
					failure.addSuppressed(abortFailure);
				}
			}
			throw failure;
		}
	}

	public static RecoveryResult recover(
			RegionStateFiles regionFiles,
			OwnershipFiles ownershipFiles,
			WorldAccess world,
			FailureInjector failures
	) throws IOException {
		if (!ownershipFiles.hasWAL()) {
			if (ownershipFiles.hasWarning()) {
				throw new IOException("Ownership WARNING exists without its WAL; recovery must be checked manually");
			}
			ownershipFiles.deleteStaleTemporaryFiles();
			return RecoveryResult.NONE;
		}
		OwnershipJournal wal = ownershipFiles.readWAL();
		if (!world.isDimensionLoaded(wal.dimensionId())) {
			throw new IOException("Ownership WAL dimension is not loaded: " + wal.dimensionId());
		}
		long head = regionFiles.committedGeneration(wal.regionId());

		if (head == wal.baseGeneration()) return rollbackToBase(regionFiles, ownershipFiles, world, failures, wal);
		if (head == wal.targetGeneration()) return completeCommitted(ownershipFiles, world, failures, wal);
		throw new IOException("Ownership is LOCKED: region HEAD generation " + head + " matches neither WAL base "
				+ wal.baseGeneration() + " nor target " + wal.targetGeneration() + " for region " + wal.regionId());
	}

	public static ReconcileResult reconcile(OwnershipFiles ownershipFiles, WorldAccess world) throws IOException {
		if (!ownershipFiles.hasCommitted()) {
			return new ReconcileResult(0L, 0, 0, 0, 0, ownershipFiles.isLocked());
		}
		OwnershipCommitted committed = ownershipFiles.readCommitted();
		if (!world.isDimensionLoaded(committed.dimensionId())) {
			throw new IOException("Committed ownership dimension is not loaded: " + committed.dimensionId());
		}
		int ownedAir = 0;
		int playerConflicts = 0;
		int waterConflicts = 0;
		for (OwnershipJournal.BlockEntry entry : committed.ownedBlocks()) {
			switch (world.inspect(committed.dimensionId(), entry)) {
				case OWNED_AIR -> ownedAir++;
				case OCCUPIED -> playerConflicts++;
				case PREIMAGE_PRESENT -> waterConflicts++;
			}
		}
		return new ReconcileResult(committed.generation(), committed.ownedBlocks().size(),
				ownedAir, playerConflicts, waterConflicts, ownershipFiles.isLocked());
	}

	/** Pre-commit crash: the world returns to the pre-transition state; player blocks always win. */
	private static RecoveryResult rollbackToBase(
			RegionStateFiles regionFiles,
			OwnershipFiles ownershipFiles,
			WorldAccess world,
			FailureInjector failures,
			OwnershipJournal wal
	) throws IOException {
		int applied = 0;
		int alreadyApplied = 0;
		int skipped = 0;
		int processed = 0;
		for (OwnershipJournal.BlockEntry entry : wal.blocks()) {
			if (wal.operation() == OwnershipJournal.Operation.ACQUIRE) {
				switch (world.restore(wal.dimensionId(), entry)) {
					case RESTORED -> applied++;
					case ALREADY_RESTORED -> alreadyApplied++;
					case SKIPPED_OCCUPIED -> skipped++;
				}
			} else {
				// Release publishes HEAD before its first world write. At the base generation
				// there is therefore nothing to undo, and touching matching water would risk
				// deleting a pre-existing player/world block.
				alreadyApplied++;
			}
			processed++;
			failures.hit(FailurePoint.AFTER_RESTORE_BLOCK, processed);
		}
		failures.hit(FailurePoint.BEFORE_CLEANUP, 0);
		regionFiles.discardUncommittedAfterRollback(wal.regionId(), wal.baseGeneration());
		ownershipFiles.deleteTransaction();
		return wal.operation() == OwnershipJournal.Operation.ACQUIRE
				? RecoveryResult.acquireRolledBack(applied, alreadyApplied, skipped)
				: RecoveryResult.releaseRolledBack(applied, alreadyApplied, skipped);
	}

	/**
	 * Post-commit crash: ownership is authoritative and the preimage is never restored again. A
	 * missing or stale (pre-replacement) committed registry is rebuilt from the WAL; a registry
	 * that is unreadable, ahead of the WAL target, or disagreeing at the target generation locks.
	 */
	private static RecoveryResult completeCommitted(
			OwnershipFiles ownershipFiles,
			WorldAccess world,
			FailureInjector failures,
			OwnershipJournal wal
	) throws IOException {
		List<OwnershipJournal.BlockEntry> expected =
				wal.operation() == OwnershipJournal.Operation.ACQUIRE ? wal.blocks() : List.of();
		OwnershipCommitted current = ownershipFiles.hasCommitted() ? ownershipFiles.readCommitted() : null;
		if (current != null && !current.regionId().equals(wal.regionId())) {
			throw new IOException("Ownership is LOCKED: committed registry region " + current.regionId()
					+ " disagrees with the WAL region " + wal.regionId());
		}
		if (current != null && current.generation() == wal.targetGeneration()) {
			if (!current.ownedBlocks().equals(expected)) {
				throw new IOException("Ownership is LOCKED: committed registry at generation "
						+ current.generation() + " disagrees with the WAL for region " + wal.regionId());
			}
		} else if (current != null && current.generation() > wal.targetGeneration()) {
			throw new IOException("Ownership is LOCKED: committed registry generation "
					+ current.generation() + " is ahead of the WAL target " + wal.targetGeneration());
		}
		int releaseRestored = 0;
		int releaseAlreadyRestored = 0;
		int releaseSkipped = 0;
		if (wal.operation() == OwnershipJournal.Operation.RELEASE) {
			int processed = 0;
			for (OwnershipJournal.BlockEntry entry : wal.blocks()) {
				switch (world.restore(wal.dimensionId(), entry)) {
					case RESTORED -> releaseRestored++;
					case ALREADY_RESTORED -> releaseAlreadyRestored++;
					case SKIPPED_OCCUPIED -> releaseSkipped++;
				}
				failures.hit(FailurePoint.AFTER_RESTORE_BLOCK, ++processed);
			}
		}
		if (current == null || current.generation() != wal.targetGeneration()) {
			ownershipFiles.publishCommitted(new OwnershipCommitted(
					wal.regionId(), wal.dimensionId(), wal.targetGeneration(), expected));
		}
		failures.hit(FailurePoint.AFTER_COMMITTED_WRITE, 0);

		int ownedAir = 0;
		int playerConflicts = 0;
		int waterConflicts = 0;
		int processed = 0;
		for (OwnershipJournal.BlockEntry entry : expected) {
			switch (world.inspect(wal.dimensionId(), entry)) {
				case OWNED_AIR -> ownedAir++;
				case OCCUPIED -> playerConflicts++;
				case PREIMAGE_PRESENT -> waterConflicts++;
			}
			processed++;
			failures.hit(FailurePoint.AFTER_RESTORE_BLOCK, processed);
		}
		failures.hit(FailurePoint.BEFORE_CLEANUP, 0);
		ownershipFiles.deleteTransaction();
		if (wal.operation() == OwnershipJournal.Operation.RELEASE) {
			return RecoveryResult.completed(releaseRestored, releaseAlreadyRestored, releaseSkipped);
		}
		return RecoveryResult.completed(ownedAir, playerConflicts, waterConflicts);
	}

	enum FailurePoint {
		AFTER_REGION_PREPARE,
		AFTER_WAL_PUBLISH,
		AFTER_WARNING_PUBLISH,
		AFTER_REMOVE_BLOCK,
		AFTER_RESTORE_BLOCK,
		BEFORE_HEAD_PUBLISH,
		AFTER_HEAD_PUBLISH,
		AFTER_COMMITTED_WRITE,
		BEFORE_CLEANUP
	}

	@FunctionalInterface
	public interface FailureInjector {
		FailureInjector NONE = (point, index) -> { };

		void hit(FailurePoint point, int oneBasedBlockIndex) throws IOException;
	}

	public interface WorldAccess {
		boolean isDimensionLoaded(String dimensionId) throws IOException;

		RemoveOutcome removeWater(String dimensionId, OwnershipJournal.BlockEntry block) throws IOException;

		RestoreOutcome restore(String dimensionId, OwnershipJournal.BlockEntry block) throws IOException;

		InspectOutcome inspect(String dimensionId, OwnershipJournal.BlockEntry block) throws IOException;
	}

	public enum RemoveOutcome {
		REMOVED,
		ALREADY_CLEAR,
		SKIPPED_OCCUPIED
	}

	public enum RestoreOutcome {
		RESTORED,
		ALREADY_RESTORED,
		SKIPPED_OCCUPIED
	}

	public enum InspectOutcome {
		OWNED_AIR,
		PREIMAGE_PRESENT,
		OCCUPIED
	}

	public record AcquireResult(long generation, int ownedBlocks) { }

	public record ReleaseResult(long generation, int restoredBlocks, int alreadyRestoredBlocks, int skippedBlocks) { }

	/**
	 * Field meanings depend on {@link Kind}: for {@code ACQUIRE_ROLLED_BACK} they count the water
	 * preimage pass, for {@code RELEASE_ROLLED_BACK} the undo-to-air pass, and for {@code COMPLETED}
	 * the owned-position inspection (air / player conflict / preimage water).
	 */
	public record RecoveryResult(Kind kind, int restored, int alreadyRestored, int skipped) {
		public enum Kind {
			NONE,
			ACQUIRE_ROLLED_BACK,
			RELEASE_ROLLED_BACK,
			COMPLETED
		}

		public static final RecoveryResult NONE = new RecoveryResult(Kind.NONE, 0, 0, 0);

		static RecoveryResult acquireRolledBack(int restored, int alreadyRestored, int skipped) {
			return new RecoveryResult(Kind.ACQUIRE_ROLLED_BACK, restored, alreadyRestored, skipped);
		}

		static RecoveryResult releaseRolledBack(int undone, int alreadyClear, int skipped) {
			return new RecoveryResult(Kind.RELEASE_ROLLED_BACK, undone, alreadyClear, skipped);
		}

		static RecoveryResult completed(int ownedAir, int playerConflicts, int waterConflicts) {
			return new RecoveryResult(Kind.COMPLETED, ownedAir, playerConflicts, waterConflicts);
		}
	}

	public record ReconcileResult(
			long generation,
			int ownedBlocks,
			int ownedAir,
			int playerConflicts,
			int waterConflicts,
			boolean transitionLocked
	) { }
}
