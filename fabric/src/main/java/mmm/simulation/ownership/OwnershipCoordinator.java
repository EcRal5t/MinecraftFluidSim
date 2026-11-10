package mmm.simulation.ownership;

import mmm.simulation.persistence.RegionState;
import mmm.simulation.persistence.RegionGenerationStore;
import mmm.simulation.persistence.GenerationSequence;
import mmm.recovery.CrossDomainRecovery;
import java.util.ArrayList;

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
			RegionGenerationStore regionFiles,
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

		RegionGenerationStore.PreparedGeneration prepared = regionFiles.prepare(target, (point, path) -> { });
		try {
			long baseGeneration = prepared.baseGeneration() == null ? 0L : prepared.baseGeneration();
			if (!GenerationSequence.isNext(baseGeneration, target.manifest().checkpointGeneration())) {
				throw new IOException("Acquire target generation must be one past the committed region HEAD "
						+ baseGeneration + ", got " + target.manifest().checkpointGeneration());
			}
			failures.hit(FailurePoint.AFTER_REGION_PREPARE, 0);
			var retained = ownershipFiles.hasCommitted() ? ownershipFiles.readCommitted() : null;
			if (retained != null && (!retained.regionId().equals(regionId) || !retained.dimensionId().equals(dimensionId)
					|| retained.generation() > baseGeneration)) throw new IOException("Existing ownership identity differs");
			var existing = retained == null ? List.<OwnershipJournal.BlockEntry>of() : retained.ownedBlocks();
			var positions = new java.util.HashSet<Long>();
			for (var entry : existing) positions.add(entry.packedPosition());
			for (var entry : candidates) if (!positions.add(entry.packedPosition()))
				throw new IOException("New ownership overlaps an existing position");
			OwnershipJournal wal = new OwnershipJournal(regionId, dimensionId, baseGeneration,
					target.manifest().checkpointGeneration(), OwnershipJournal.Operation.ACQUIRE, candidates,
					existing, UUID.randomUUID(), prepared.manifestChecksum(), CrossDomainRecovery.JournalPhase.PREPARED);
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

			world.persist(dimensionId);
			ownershipFiles.publishCommitReady(wal);
			failures.hit(FailurePoint.BEFORE_HEAD_PUBLISH, 0);
			long published = regionFiles.commit(prepared, (point, path) -> { });
			failures.hit(FailurePoint.AFTER_HEAD_PUBLISH, 0);
			var owned = new java.util.ArrayList<>(existing);
			owned.addAll(candidates);
			ownershipFiles.publishCommitted(new OwnershipCommitted(regionId, dimensionId, published, owned));
			failures.hit(FailurePoint.AFTER_COMMITTED_WRITE, 0);
			failures.hit(FailurePoint.BEFORE_CLEANUP, 0);
			world.persist(dimensionId);
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
			RegionGenerationStore regionFiles,
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

		RegionGenerationStore.PreparedGeneration prepared = regionFiles.prepare(target, (point, path) -> { });
		try {
			long baseGeneration = prepared.baseGeneration() == null ? 0L : prepared.baseGeneration();
			if (!GenerationSequence.isNext(baseGeneration, target.manifest().checkpointGeneration())) {
				throw new IOException("Release target generation must be one past the committed region HEAD "
						+ baseGeneration + ", got " + target.manifest().checkpointGeneration());
			}
			failures.hit(FailurePoint.AFTER_REGION_PREPARE, 0);
			OwnershipJournal wal = new OwnershipJournal(regionId, committed.dimensionId(), baseGeneration,
					target.manifest().checkpointGeneration(), OwnershipJournal.Operation.RELEASE, committed.ownedBlocks(),
					UUID.randomUUID(), prepared.manifestChecksum(), CrossDomainRecovery.JournalPhase.PREPARED);
			ownershipFiles.publishWAL(wal);
			failures.hit(FailurePoint.AFTER_WAL_PUBLISH, 0);
			ownershipFiles.publishWarning(warning);
			failures.hit(FailurePoint.AFTER_WARNING_PUBLISH, 0);

			ownershipFiles.publishCommitReady(wal);
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
			world.persist(committed.dimensionId());
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

	/** Publishes actual reference-water transfer and the retained ownership in one HEAD transaction. */
	public static ReleaseResult returnWater(
			RegionGenerationStore regionFiles,
			OwnershipFiles ownershipFiles,
			RegionState target,
			List<OwnershipJournal.BlockEntry> returned,
			boolean relinquishAll,
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
		if (returned.isEmpty() && !relinquishAll) throw new IOException("Native water return is empty");
		var positions = new java.util.HashSet<Long>();
		for (var block : returned) {
			if (!positions.add(block.packedPosition()) || committed.ownedBlocks().stream().noneMatch(owned -> owned.packedPosition() == block.packedPosition()))
				throw new IOException("Native water return positions disagree with committed ownership");
			if (world.inspectReturn(committed.dimensionId(), block) != InspectOutcome.OWNED_AIR) throw new IOException("Native water output must begin in owned air");
		}

		RegionGenerationStore.PreparedGeneration prepared = regionFiles.prepare(target, (point, path) -> { });
		try {
			long baseGeneration = prepared.baseGeneration() == null ? 0L : prepared.baseGeneration();
			if (!GenerationSequence.isNext(baseGeneration, target.manifest().checkpointGeneration())) {
				throw new IOException("Release target generation must be one past the committed region HEAD "
						+ baseGeneration + ", got " + target.manifest().checkpointGeneration());
			}
			failures.hit(FailurePoint.AFTER_REGION_PREPARE, 0);
			OwnershipJournal wal = new OwnershipJournal(regionId, committed.dimensionId(), baseGeneration,
					target.manifest().checkpointGeneration(), OwnershipJournal.Operation.RETURN_WATER, returned,
					relinquishAll ? List.of() : committed.ownedBlocks().stream().filter(block -> returned.stream().noneMatch(out -> out.packedPosition() == block.packedPosition())).toList(),
					UUID.randomUUID(), prepared.manifestChecksum(), CrossDomainRecovery.JournalPhase.PREPARED);
			ownershipFiles.publishWAL(wal);
			failures.hit(FailurePoint.AFTER_WAL_PUBLISH, 0);
			ownershipFiles.publishWarning(warning);
			failures.hit(FailurePoint.AFTER_WARNING_PUBLISH, 0);

			ownershipFiles.publishCommitReady(wal);
			failures.hit(FailurePoint.BEFORE_HEAD_PUBLISH, 0);
			long published = regionFiles.commit(prepared, (point, path) -> { });
			failures.hit(FailurePoint.AFTER_HEAD_PUBLISH, 0);

			int restored = 0;
			int alreadyRestored = 0;
			int skipped = 0;
			int processed = 0;
			for (OwnershipJournal.BlockEntry entry : wal.blocks()) {
				if (world.inspectReturn(committed.dimensionId(), entry) == InspectOutcome.OCCUPIED) throw new IOException("Native water output position changed");
				switch (world.returnWater(committed.dimensionId(), entry)) {
					case RESTORED -> restored++;
					case ALREADY_RESTORED -> alreadyRestored++;
					case SKIPPED_OCCUPIED -> throw new IOException("Native water output is occupied");
				}
				processed++;
				failures.hit(FailurePoint.AFTER_RESTORE_BLOCK, processed);
			}

			ownershipFiles.publishCommitted(new OwnershipCommitted(regionId, committed.dimensionId(), published, wal.retainedBlocks()));
			failures.hit(FailurePoint.AFTER_COMMITTED_WRITE, 0);
			failures.hit(FailurePoint.BEFORE_CLEANUP, 0);
			world.persist(committed.dimensionId());
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
			RegionGenerationStore regionFiles,
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
		long head = regionFiles.committedGeneration(wal.regionId());
		long checksum = head == 0 ? 0 : regionFiles.committedManifestChecksum(wal.regionId());
		var members = new ArrayList<CrossDomainRecovery.ExternalTruth>();
		if (!world.isDimensionLoaded(wal.dimensionId())) {
			members.add(CrossDomainRecovery.ExternalTruth.UNAVAILABLE);
		} else for (var block : wal.blocks()) {
			var state = wal.operation() == OwnershipJournal.Operation.RETURN_WATER
					? world.inspectReturn(wal.dimensionId(), block) : world.inspect(wal.dimensionId(), block);
			// Restore preserves an occupied player block; that position already needs no write.
			boolean preserved = state == InspectOutcome.OCCUPIED && wal.operation() != OwnershipJournal.Operation.RETURN_WATER;
			boolean before = wal.operation() == OwnershipJournal.Operation.ACQUIRE
					? state == InspectOutcome.PREIMAGE_PRESENT : state == InspectOutcome.OWNED_AIR;
			boolean after = wal.operation() == OwnershipJournal.Operation.ACQUIRE
					? state == InspectOutcome.OWNED_AIR : state == InspectOutcome.PREIMAGE_PRESENT;
			if (head == wal.baseGeneration() && wal.operation() == OwnershipJournal.Operation.RELEASE) {
				before = true; after = false;
			} else after |= preserved;
			members.add(CrossDomainRecovery.classify(true, before, after, false));
		}
		if (members.isEmpty()) members.add(CrossDomainRecovery.ExternalTruth.BEFORE);
		var boundary = new CrossDomainRecovery.Boundary(head, checksum, wal.baseGeneration(), wal.targetGeneration(),
				wal.targetManifestChecksum() >= 0, wal.targetManifestChecksum(), wal.phase(), false);
		var decision = CrossDomainRecovery.decide(boundary, members,
				wal.operation() == OwnershipJournal.Operation.ACQUIRE
						? CrossDomainRecovery.CommitOrder.EXTERNAL_FIRST : CrossDomainRecovery.CommitOrder.REGION_FIRST);
		return switch (decision.outcome()) {
			case DISCARD_UNCOMMITTED, ROLLBACK_BEFORE -> rollbackToBase(regionFiles, ownershipFiles, world, failures, wal);
			case CONVERGE_AFTER -> completeCommitted(ownershipFiles, world, failures, wal);
			case WAIT_UNAVAILABLE -> throw new IOException("Ownership recovery waits for external world " + wal.dimensionId());
			case LOCKED -> throw new IOException("Ownership is LOCKED: " + decision.reason() + ", operation="
					+ wal.operationId() + ", region=" + wal.regionId() + ", head=" + head);
		};
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
			RegionGenerationStore regionFiles,
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
		world.persist(wal.dimensionId());
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
		List<OwnershipJournal.BlockEntry> expected;
		if (wal.operation() == OwnershipJournal.Operation.ACQUIRE) {
			var combined = new java.util.ArrayList<>(wal.retainedBlocks());
			combined.addAll(wal.blocks());
			expected = List.copyOf(combined);
		} else expected = wal.retainedBlocks();
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
		if (wal.operation() != OwnershipJournal.Operation.ACQUIRE) {
			int processed = 0;
			for (OwnershipJournal.BlockEntry entry : wal.blocks()) {
				switch (wal.operation() == OwnershipJournal.Operation.RETURN_WATER
						? world.returnWater(wal.dimensionId(), entry) : world.restore(wal.dimensionId(), entry)) {
					case RESTORED -> releaseRestored++;
					case ALREADY_RESTORED -> releaseAlreadyRestored++;
					case SKIPPED_OCCUPIED -> {
						if (wal.operation() == OwnershipJournal.Operation.RETURN_WATER) throw new IOException("Native water output is occupied");
						releaseSkipped++;
					}
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
		world.persist(wal.dimensionId());
		ownershipFiles.deleteTransaction();
		if (wal.operation() != OwnershipJournal.Operation.ACQUIRE) {
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

		void persist(String dimensionId) throws IOException;

		RemoveOutcome removeWater(String dimensionId, OwnershipJournal.BlockEntry block) throws IOException;

		RestoreOutcome restore(String dimensionId, OwnershipJournal.BlockEntry block) throws IOException;

		InspectOutcome inspect(String dimensionId, OwnershipJournal.BlockEntry block) throws IOException;

		default InspectOutcome inspectReturn(String dimensionId, OwnershipJournal.BlockEntry block) throws IOException {
			return inspect(dimensionId, block);
		}

		default RestoreOutcome returnWater(String dimensionId, OwnershipJournal.BlockEntry block) throws IOException {
			return restore(dimensionId, block);
		}
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
