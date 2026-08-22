package com.example.simulation.ownership;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Testable world-ownership transition for the disposable M6.0 prototype. Ordering contract:
 * journal first, then WARNING, then world mutation, and the committed registry is published last
 * as the sole commit point. Recovery decides purely by comparing the committed generation with the
 * journal's base and target generations, so a crash either yields the complete previous state or
 * the complete new state and never restores the preimage of committed ownership.
 */
final class OwnershipProtoTransaction {
	private OwnershipProtoTransaction() { }

	static BeginResult beginAcquire(
			OwnershipProtoFiles files,
			String dimensionId,
			List<OwnershipJournalProto.BlockEntry> candidates,
			String warning,
			WorldAccess world,
			FailureInjector failures
	) throws IOException {
		files.assertUnlockedForTransition();
		if (candidates.isEmpty()) throw new IOException("No pure water positions to acquire");
		OwnershipCommittedProto base = readBase(files);
		if (base != null && !base.dimensionId().equals(dimensionId)) {
			throw new IOException("Committed prototype ownership belongs to dimension " + base.dimensionId()
					+ "; release it before acquiring in " + dimensionId);
		}

		OwnershipJournalProto journal = new OwnershipJournalProto(
				dimensionId,
				base == null ? 0L : base.generation(),
				(base == null ? 0L : base.generation()) + 1L,
				OwnershipJournalProto.Operation.ACQUIRE,
				candidates
		);
		failures.hit(FailurePoint.BEFORE_JOURNAL_WRITE, 0);
		files.publishJournal(journal);
		failures.hit(FailurePoint.AFTER_JOURNAL_PUBLISH, 0);
		files.publishWarning(warning);

		List<OwnershipJournalProto.BlockEntry> newlyOwned = new ArrayList<>();
		for (OwnershipJournalProto.BlockEntry entry : journal.blocks()) {
			if (world.removeWater(dimensionId, entry) != RemoveOutcome.REMOVED) continue;
			newlyOwned.add(entry);
			failures.hit(FailurePoint.AFTER_REMOVE_BLOCK, newlyOwned.size());
		}

		failures.hit(FailurePoint.BEFORE_COMMITTED_PUBLISH, 0);
		Map<Long, OwnershipJournalProto.BlockEntry> owned = new LinkedHashMap<>();
		if (base != null) {
			for (OwnershipJournalProto.BlockEntry entry : base.ownedBlocks()) owned.put(entry.packedPosition(), entry);
		}
		for (OwnershipJournalProto.BlockEntry entry : newlyOwned) owned.put(entry.packedPosition(), entry);
		OwnershipCommittedProto target = new OwnershipCommittedProto(
				OwnershipCommittedProto.PROTO_REGION_ID,
				dimensionId,
				journal.targetGeneration(),
				List.copyOf(owned.values())
		);
		files.publishCommitted(target);
		failures.hit(FailurePoint.AFTER_COMMITTED_PUBLISH, 0);
		failures.hit(FailurePoint.BEFORE_COMPLETION_DELETE, 0);
		files.deleteTransaction();
		return new BeginResult(journal.targetGeneration(), newlyOwned.size(), target.ownedBlocks().size());
	}

	static BeginResult beginRelease(
			OwnershipProtoFiles files,
			String warning,
			WorldAccess world,
			FailureInjector failures
	) throws IOException {
		files.assertUnlockedForTransition();
		OwnershipCommittedProto base = readBase(files);
		if (base == null || base.ownedBlocks().isEmpty()) {
			throw new IOException("No committed prototype ownership to release");
		}

		OwnershipJournalProto journal = new OwnershipJournalProto(
				base.dimensionId(),
				base.generation(),
				base.generation() + 1L,
				OwnershipJournalProto.Operation.RELEASE,
				base.ownedBlocks()
		);
		failures.hit(FailurePoint.BEFORE_JOURNAL_WRITE, 0);
		files.publishJournal(journal);
		failures.hit(FailurePoint.AFTER_JOURNAL_PUBLISH, 0);
		files.publishWarning(warning);

		int processed = 0;
		for (OwnershipJournalProto.BlockEntry entry : journal.blocks()) {
			world.restore(base.dimensionId(), entry);
			processed++;
			failures.hit(FailurePoint.AFTER_RESTORE_BLOCK, processed);
		}

		failures.hit(FailurePoint.BEFORE_COMMITTED_PUBLISH, 0);
		OwnershipCommittedProto target = new OwnershipCommittedProto(
				OwnershipCommittedProto.PROTO_REGION_ID,
				base.dimensionId(),
				journal.targetGeneration(),
				List.of()
		);
		files.publishCommitted(target);
		failures.hit(FailurePoint.AFTER_COMMITTED_PUBLISH, 0);
		failures.hit(FailurePoint.BEFORE_COMPLETION_DELETE, 0);
		files.deleteTransaction();
		return new BeginResult(journal.targetGeneration(), 0, 0);
	}

	static RecoveryResult recover(OwnershipProtoFiles files, WorldAccess world, FailureInjector failures) throws IOException {
		if (!files.hasJournal()) {
			if (files.hasWarning()) {
				throw new IOException("Ownership WARNING exists without its journal; recovery must be checked manually");
			}
			files.deleteStaleTemporaryFiles();
			return RecoveryResult.NONE;
		}

		OwnershipJournalProto journal = files.readJournal();
		if (!world.isDimensionLoaded(journal.dimensionId())) {
			throw new IOException("Ownership journal dimension is not loaded: " + journal.dimensionId());
		}
		OwnershipCommittedProto committed = files.hasCommitted() ? files.readCommitted() : null;
		long committedGeneration = committed == null ? 0L : committed.generation();

		if (committedGeneration == journal.baseGeneration()) {
			return rollbackToBase(files, world, failures, journal, committed);
		}
		if (committedGeneration == journal.targetGeneration()) {
			return completeCommitted(files, world, failures, journal, committed);
		}
		throw new IOException("Ownership prototype is LOCKED: committed generation " + committedGeneration
				+ " matches neither journal base " + journal.baseGeneration()
				+ " nor target " + journal.targetGeneration());
	}

	/** Pre-commit crash: the world returns to the pre-transition state; player blocks always win. */
	private static RecoveryResult rollbackToBase(
			OwnershipProtoFiles files,
			WorldAccess world,
			FailureInjector failures,
			OwnershipJournalProto journal,
			OwnershipCommittedProto committed
	) throws IOException {
		requireSameDimension(journal, committed);
		int applied = 0;
		int alreadyApplied = 0;
		int skipped = 0;
		int processed = 0;
		for (OwnershipJournalProto.BlockEntry entry : journal.blocks()) {
			if (journal.operation() == OwnershipJournalProto.Operation.ACQUIRE) {
				switch (world.restore(journal.dimensionId(), entry)) {
					case RESTORED -> applied++;
					case ALREADY_RESTORED -> alreadyApplied++;
					case SKIPPED_OCCUPIED -> skipped++;
				}
			} else {
				switch (world.removeWater(journal.dimensionId(), entry)) {
					case REMOVED -> applied++;
					case ALREADY_CLEAR -> alreadyApplied++;
					case SKIPPED_OCCUPIED -> skipped++;
				}
			}
			processed++;
			failures.hit(FailurePoint.AFTER_RESTORE_BLOCK, processed);
		}
		failures.hit(FailurePoint.BEFORE_COMPLETION_DELETE, 0);
		files.deleteTransaction();
		return journal.operation() == OwnershipJournalProto.Operation.ACQUIRE
				? RecoveryResult.acquireRolledBack(applied, alreadyApplied, skipped)
				: RecoveryResult.releaseRolledBack(applied, alreadyApplied, skipped);
	}

	/** Post-commit crash: ownership is authoritative; the preimage is never restored again. */
	private static RecoveryResult completeCommitted(
			OwnershipProtoFiles files,
			WorldAccess world,
			FailureInjector failures,
			OwnershipJournalProto journal,
			OwnershipCommittedProto committed
	) throws IOException {
		requireSameDimension(journal, committed);
		int ownedAir = 0;
		int playerConflicts = 0;
		int waterConflicts = 0;
		int processed = 0;
		for (OwnershipJournalProto.BlockEntry entry : committed.ownedBlocks()) {
			switch (world.inspect(journal.dimensionId(), entry)) {
				case OWNED_AIR -> ownedAir++;
				case OCCUPIED -> playerConflicts++;
				case PREIMAGE_PRESENT -> waterConflicts++;
			}
			processed++;
			failures.hit(FailurePoint.AFTER_RESTORE_BLOCK, processed);
		}
		failures.hit(FailurePoint.BEFORE_COMPLETION_DELETE, 0);
		files.deleteTransaction();
		return RecoveryResult.completed(ownedAir, playerConflicts, waterConflicts);
	}

	static ReconcileResult reconcile(OwnershipProtoFiles files, WorldAccess world) throws IOException {
		OwnershipCommittedProto committed = files.hasCommitted() ? files.readCommitted() : null;
		if (committed == null) {
			return new ReconcileResult(0L, 0, 0, 0, 0, files.isLocked());
		}
		if (!world.isDimensionLoaded(committed.dimensionId())) {
			throw new IOException("Committed ownership dimension is not loaded: " + committed.dimensionId());
		}
		int ownedAir = 0;
		int playerConflicts = 0;
		int waterConflicts = 0;
		for (OwnershipJournalProto.BlockEntry entry : committed.ownedBlocks()) {
			switch (world.inspect(committed.dimensionId(), entry)) {
				case OWNED_AIR -> ownedAir++;
				case OCCUPIED -> playerConflicts++;
				case PREIMAGE_PRESENT -> waterConflicts++;
			}
		}
		return new ReconcileResult(committed.generation(), committed.ownedBlocks().size(),
				ownedAir, playerConflicts, waterConflicts, files.isLocked());
	}

	private static OwnershipCommittedProto readBase(OwnershipProtoFiles files) throws IOException {
		return files.hasCommitted() ? files.readCommitted() : null;
	}

	private static void requireSameDimension(OwnershipJournalProto journal, OwnershipCommittedProto committed)
			throws IOException {
		String committedDimension = committed == null ? journal.dimensionId() : committed.dimensionId();
		if (!committedDimension.equals(journal.dimensionId())) {
			throw new IOException("Ownership prototype is LOCKED: journal dimension " + journal.dimensionId()
					+ " disagrees with committed dimension " + committedDimension);
		}
	}

	enum FailurePoint {
		BEFORE_JOURNAL_WRITE,
		AFTER_JOURNAL_PUBLISH,
		AFTER_REMOVE_BLOCK,
		AFTER_RESTORE_BLOCK,
		BEFORE_COMMITTED_PUBLISH,
		AFTER_COMMITTED_PUBLISH,
		BEFORE_COMPLETION_DELETE
	}

	@FunctionalInterface
	interface FailureInjector {
		FailureInjector NONE = (point, index) -> { };

		void hit(FailurePoint point, int oneBasedBlockIndex) throws IOException;
	}

	interface WorldAccess {
		boolean isDimensionLoaded(String dimensionId) throws IOException;

		RemoveOutcome removeWater(String dimensionId, OwnershipJournalProto.BlockEntry block) throws IOException;

		RestoreOutcome restore(String dimensionId, OwnershipJournalProto.BlockEntry block) throws IOException;

		InspectOutcome inspect(String dimensionId, OwnershipJournalProto.BlockEntry block) throws IOException;
	}

	enum RemoveOutcome {
		REMOVED,
		ALREADY_CLEAR,
		SKIPPED_OCCUPIED
	}

	enum RestoreOutcome {
		RESTORED,
		ALREADY_RESTORED,
		SKIPPED_OCCUPIED
	}

	enum InspectOutcome {
		OWNED_AIR,
		PREIMAGE_PRESENT,
		OCCUPIED
	}

	record BeginResult(long generation, int transitionBlocks, int totalOwnedBlocks) { }

	/**
	 * Field meanings depend on {@link Kind}: for {@code ACQUIRE_ROLLED_BACK} they count the water
	 * preimage pass, for {@code RELEASE_ROLLED_BACK} the undo-to-air pass, and for {@code COMPLETED}
	 * the owned-position inspection (air / player conflict / preimage water).
	 */
	record RecoveryResult(Kind kind, int restored, int alreadyRestored, int skipped) {
		enum Kind {
			NONE,
			ACQUIRE_ROLLED_BACK,
			RELEASE_ROLLED_BACK,
			COMPLETED
		}

		static final RecoveryResult NONE = new RecoveryResult(Kind.NONE, 0, 0, 0);

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

	record ReconcileResult(
			long generation,
			int ownedBlocks,
			int ownedAir,
			int playerConflicts,
			int waterConflicts,
			boolean transitionLocked
	) { }
}
