package cc.ecisr.mmm.simulation.ownership;

import cc.ecisr.mmm.simulation.ownership.OwnershipCoordinator.FailureInjector;
import cc.ecisr.mmm.simulation.ownership.OwnershipCoordinator.FailurePoint;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.simulation.FluidRegion;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.simulation.persistence.RegionState;
import cc.ecisr.mmm.simulation.persistence.RegionStateFiles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Standalone joint ownership gate across the region-state store and the ownership WAL. The two
 * decisive scenarios surround the region HEAD publication: a crash before it must roll the world
 * back to the vanilla preimage, a crash after it must complete the ownership and never restore
 * the preimage again.
 */
public final class M6OwnershipValidation {
	private static final String DIMENSION_ID = "mmm:ownership_validation";
	private static final ResourceKey<Registry<Level>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(
			Identifier.fromNamespaceAndPath("mmm", "ownership_validation_registry"));
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(DIMENSION_REGISTRY,
			Identifier.fromNamespaceAndPath("mmm", "ownership_validation"));
	private static final FluidSolverConfig CONFIG = new FluidSolverConfig(
			0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	private static int passed;

	private M6OwnershipValidation() { }

	public static void main(String[] args) throws Exception {
		run("wal and committed codec strictness", M6OwnershipValidation::testCodecs);
		run("normal acquire commits the joint state", M6OwnershipValidation::testNormalAcquire);
		run("acquire rejects a non-adjacent target generation", M6OwnershipValidation::testGenerationGuard);
		run("crash after region prepare aborts private staging", M6OwnershipValidation::testCrashAfterPrepare);
		run("boot removes only fully known first-publication staging", M6OwnershipValidation::testBootCleansFirstPublicationStaging);
		run("crash after WAL publish rolls forward to recovery", M6OwnershipValidation::testCrashAfterWalPublish);
		run("mid-mutation crash rolls back every removed block", M6OwnershipValidation::testCrashMidMutation);
		run("player block wins in pre-commit rollback", M6OwnershipValidation::testPlayerWinsInRollback);
		run("crash before HEAD rolls the world fully back", M6OwnershipValidation::testCrashBeforeHead);
		run("crash after HEAD completes ownership and never restores", M6OwnershipValidation::testCrashAfterHead);
		run("crash before cleanup completes idempotently", M6OwnershipValidation::testCrashBeforeCleanup);
		run("normal release restores water and clears ownership", M6OwnershipValidation::testNormalRelease);
		run("release crash before HEAD never touches matching water", M6OwnershipValidation::testReleaseCrashBeforeHead);
		run("release crash after HEAD completes restoration", M6OwnershipValidation::testReleaseCrash);
		run("corrupt WAL locks and is never overwritten", M6OwnershipValidation::testCorruptWalLock);
		run("WAL generation mismatch locks", M6OwnershipValidation::testWalGenerationMismatch);
		run("committed registry ahead of the WAL target locks", M6OwnershipValidation::testCommittedAheadLocks);
		run("reconcile classifies air, player and water", M6OwnershipValidation::testReconcile);
		run("regions are isolated from each other's locks", M6OwnershipValidation::testRegionIsolation);
		run("warning without WAL requires manual check", M6OwnershipValidation::testOrphanWarning);
		run("unknown ownership content locks and is preserved", M6OwnershipValidation::testUnknownOwnershipLayout);
		run("corrupt committed registry locks during boot recovery", M6OwnershipValidation::testCorruptCommittedLock);
		System.out.println("M6OwnershipValidation passed " + passed + " scenarios");
	}

	private static void testCodecs() throws Exception {
		OwnershipJournalCodec journalCodec = new OwnershipJournalCodec();
		UUID regionId = UUID.randomUUID();
		OwnershipJournal expected = new OwnershipJournal(regionId, DIMENSION_ID, 5L, 6L,
				OwnershipJournal.Operation.ACQUIRE, entries(3));
		CompoundTag encoded = journalCodec.encode(expected);
		check(encoded.getStringOr("Schema", "").equals("ownership-transition"), "codec changed the WAL schema");
		assertJournalEquals(expected, journalCodec.decode(encoded));

		CompoundTag future = journalCodec.encode(expected);
		future.putString("Schema", "unknown");
		expectIOException(() -> journalCodec.decode(future));
		CompoundTag missing = journalCodec.encode(expected);
		missing.remove("RegionUuidMost");
		expectIOException(() -> journalCodec.decode(missing));
		CompoundTag badOperation = journalCodec.encode(expected);
		badOperation.putString("Operation", "SNAPSHOT");
		expectIOException(() -> journalCodec.decode(badOperation));
		CompoundTag badTarget = journalCodec.encode(expected);
		badTarget.putLong("TargetGeneration", 8L);
		expectIOException(() -> journalCodec.decode(badTarget));
		try {
			new OwnershipJournal(regionId, DIMENSION_ID, 3L, 7L, OwnershipJournal.Operation.ACQUIRE, entries(1));
			throw new AssertionError("Expected IllegalArgumentException for non-adjacent generations");
		} catch (IllegalArgumentException expectedFailure) {
			// expected
		}
		try {
			new OwnershipJournal(regionId, DIMENSION_ID, Long.MAX_VALUE, Long.MIN_VALUE,
					OwnershipJournal.Operation.ACQUIRE, entries(1));
			throw new AssertionError("Expected IllegalArgumentException for an overflowed generation pair");
		} catch (IllegalArgumentException expectedFailure) {
			// expected
		}
		CompoundTag overflowed = journalCodec.encode(expected);
		overflowed.putLong("BaseGeneration", Long.MAX_VALUE);
		overflowed.putLong("TargetGeneration", Long.MIN_VALUE);
		expectIOException(() -> journalCodec.decode(overflowed));

		OwnershipCommittedCodec committedCodec = new OwnershipCommittedCodec();
		OwnershipCommitted committed = new OwnershipCommitted(regionId, DIMENSION_ID, 4L, entries(2));
		OwnershipCommitted decoded = committedCodec.decode(committedCodec.encode(committed));
		check(decoded.equals(committed), "committed registry did not round-trip");
		CompoundTag foreignRegion = committedCodec.encode(committed);
		foreignRegion.putLong("RegionUuidMost", 123L);
		check(!committedCodec.decode(foreignRegion).regionId().equals(regionId),
				"foreign region ids must stay visible to the caller instead of failing decode");
		CompoundTag negative = committedCodec.encode(committed);
		negative.putLong("Generation", -1L);
		expectIOException(() -> committedCodec.decode(negative));
		CompoundTag stone = new CompoundTag();
		stone.putString("Name", "minecraft:stone");
		List<OwnershipJournal.BlockEntry> nonWater = List.of(new OwnershipJournal.BlockEntry(7L, stone));
		expectIOException(() -> journalCodec.encode(new OwnershipJournal(regionId, DIMENSION_ID, 1L, 2L,
				OwnershipJournal.Operation.ACQUIRE, nonWater)));
		expectIOException(() -> committedCodec.encode(new OwnershipCommitted(regionId, DIMENSION_ID, 1L, nonWater)));
	}

	private static void testNormalAcquire() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 3);
			OwnershipCoordinator.AcquireResult result = acquire(fixture, FailureInjector.NONE);
			check(result.generation() == 1L && result.ownedBlocks() == 3, "acquire counts are wrong");
			check(fixture.regionFiles().committedGeneration(fixture.regionId()) == 1L, "region HEAD did not commit");
			check(fixture.world().airCount() == 3, "owned water was not removed from the world");
			check(!fixture.ownershipFiles().isLocked(), "acquire kept the WAL or warning");
			OwnershipCommitted committed = fixture.ownershipFiles().readCommitted();
			check(committed.generation() == 1L && committed.ownedBlocks().size() == 3, "committed registry is wrong");
		});
	}

	private static void testGenerationGuard() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 2);
			RegionState wrongTarget = RegionState.capture(populatedRegion(fixture.regionId()), 3L);
			expectIOException(() -> OwnershipCoordinator.acquire(fixture.regionFiles(), fixture.ownershipFiles(),
					wrongTarget, fixture.world().originalEntries(), "test warning", fixture.world(), FailureInjector.NONE));
			check(!fixture.ownershipFiles().hasWAL() && fixture.world().allOriginal(),
					"generation guard published state");
		});
	}

	private static void testCrashAfterPrepare() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 3);
			expectIOException(() -> acquire(fixture, failAt(FailurePoint.AFTER_REGION_PREPARE, 0)));
			check(!fixture.ownershipFiles().hasWAL() && fixture.world().allOriginal(),
					"post-prepare crash published the WAL or modified the world");
			Path regionDirectory = root.resolve("mmm").resolve("region-state")
					.resolve(fixture.regionId().toString());
			check(!Files.exists(regionDirectory), "failed prepare retained a private staged generation");
			// A retry starts from a genuinely clean first publication; unknown content still stays locked.
			OwnershipCoordinator.AcquireResult retry = acquire(fixture, FailureInjector.NONE);
			check(retry.generation() == 1L && fixture.world().airCount() == 3, "staged-slot retry did not recover");
			UUID foreignRegion = UUID.randomUUID();
			Path foreignDirectory = root.resolve("mmm").resolve("region-state").resolve(foreignRegion.toString());
			Files.createDirectories(foreignDirectory);
			Files.writeString(foreignDirectory.resolve("mystery.dat"), "foreign data");
			expectIOException(() -> acquire(new Fixture(root, 2, foreignRegion), FailureInjector.NONE));
		});
	}

	private static void testBootCleansFirstPublicationStaging() throws Exception {
		withTemporaryWorld(root -> {
			UUID regionId = UUID.randomUUID();
			RegionStateFiles files = new RegionStateFiles(root);
			RegionState staged = RegionState.capture(populatedRegion(regionId), 1L);
			files.prepare(staged, (point, path) -> { });
			Path regionDirectory = root.resolve("mmm").resolve("region-state").resolve(regionId.toString());
			check(Files.isDirectory(regionDirectory) && files.committedGeneration(regionId) == 0L,
					"fixture did not create an uncommitted first publication");

			RegionStateFiles reopened = new RegionStateFiles(root);
			reopened.recoverKnownFirstPublicationStaging();
			check(!Files.exists(regionDirectory), "boot retained known headless first-publication staging");
			check(reopened.discover().issues().isEmpty() && reopened.discover().manifests().isEmpty(),
					"cleaned first publication remained globally locked");

			UUID nestedForeignId = UUID.randomUUID();
			RegionStateFiles nestedFiles = new RegionStateFiles(root);
			nestedFiles.prepare(RegionState.capture(populatedRegion(nestedForeignId), 1L), (point, path) -> { });
			Path nestedForeign = root.resolve("mmm").resolve("region-state")
					.resolve(nestedForeignId.toString()).resolve("slot-a").resolve("foreign").resolve("payload.bin");
			Files.createDirectories(nestedForeign.getParent());
			Files.writeString(nestedForeign, "foreign data");
			nestedFiles.recoverKnownFirstPublicationStaging();
			check(Files.exists(nestedForeign), "boot deleted nested foreign staged content");
			check(nestedFiles.discover().issues().containsKey(nestedForeign.getParent().getParent().getParent()),
					"nested foreign staged content did not lock discovery");

			UUID wrongTypeId = UUID.randomUUID();
			Path wrongTypeRegion = root.resolve("mmm").resolve("region-state").resolve(wrongTypeId.toString());
			Files.createDirectories(wrongTypeRegion);
			Files.writeString(wrongTypeRegion.resolve("slot-a"), "not a directory");
			nestedFiles.recoverKnownFirstPublicationStaging();
			check(Files.isRegularFile(wrongTypeRegion.resolve("slot-a")),
					"boot deleted a staged slot with the wrong node type");
		});
	}

	private static void testCrashAfterWalPublish() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 3);
			expectIOException(() -> acquire(fixture, failAt(FailurePoint.AFTER_WAL_PUBLISH, 0)));
			check(fixture.ownershipFiles().hasWAL() && !fixture.ownershipFiles().hasWarning(),
					"WAL-only crash state is wrong");
			check(fixture.world().allOriginal() && fixture.regionFiles().committedGeneration(fixture.regionId()) == 0L,
					"WAL-only crash changed the world or committed a region");
			expectIOException(() -> acquire(fixture, FailureInjector.NONE));
			OwnershipCoordinator.RecoveryResult recovery = recover(fixture, FailureInjector.NONE);
			check(recovery.kind() == OwnershipCoordinator.RecoveryResult.Kind.ACQUIRE_ROLLED_BACK
					&& recovery.alreadyRestored() == 3, "WAL-only recovery was not recognized as untouched");
			check(!fixture.ownershipFiles().isLocked(), "WAL-only recovery kept the lock");
			OwnershipCoordinator.AcquireResult retry = acquire(fixture, FailureInjector.NONE);
			check(retry.generation() == 1L && fixture.world().airCount() == 3, "retry after rollback failed");
		});
	}

	private static void testCrashMidMutation() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 4);
			expectIOException(() -> acquire(fixture, failAt(FailurePoint.AFTER_REMOVE_BLOCK, 2)));
			check(fixture.world().airCount() == 2 && fixture.ownershipFiles().isLocked(),
					"mid-mutation crash state is wrong");
			OwnershipCoordinator.RecoveryResult recovery = recover(fixture, FailureInjector.NONE);
			check(recovery.kind() == OwnershipCoordinator.RecoveryResult.Kind.ACQUIRE_ROLLED_BACK
					&& recovery.restored() == 2 && recovery.alreadyRestored() == 2,
					"partial removal was not rolled back completely");
			check(fixture.world().allOriginal() && !fixture.ownershipFiles().isLocked()
					&& fixture.regionFiles().committedGeneration(fixture.regionId()) == 0L,
					"mid-mutation recovery left inconsistent state");
		});
	}

	private static void testPlayerWinsInRollback() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 3);
			List<OwnershipJournal.BlockEntry> entries = fixture.world().originalEntries();
			expectIOException(() -> acquire(fixture, failAt(FailurePoint.BEFORE_HEAD_PUBLISH, 0)));
			check(fixture.world().airCount() == 3, "pre-HEAD crash did not mutate the world");
			fixture.world().current.put(entries.get(1).packedPosition(), "PLAYER_SOLID");
			OwnershipCoordinator.RecoveryResult recovery = recover(fixture, FailureInjector.NONE);
			check(recovery.kind() == OwnershipCoordinator.RecoveryResult.Kind.ACQUIRE_ROLLED_BACK
					&& recovery.restored() == 2 && recovery.skipped() == 1, "player block was not skipped");
			check("PLAYER_SOLID".equals(fixture.world().current.get(entries.get(1).packedPosition())),
					"player block was overwritten during rollback");
			check(fixture.world().waterCount() == 2, "rollback did not restore the other positions");
		});
	}

	/** Decisive pre-commit scenario: the world is fully mutated but the region HEAD names the base. */
	private static void testCrashBeforeHead() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 4);
			expectIOException(() -> acquire(fixture, failAt(FailurePoint.BEFORE_HEAD_PUBLISH, 0)));
			check(fixture.world().airCount() == 4
					&& fixture.regionFiles().committedGeneration(fixture.regionId()) == 0L,
					"pre-HEAD crash state is wrong");
			// Real startup loads region metadata before ownership recovery. The absent HEAD proves the
			// staged region is uncommitted; discarding it must not discard the ownership WAL needed to
			// restore the already-mutated world.
			new RegionStateFiles(root).recoverKnownFirstPublicationStaging();
			check(fixture.ownershipFiles().hasWAL(), "region staging cleanup deleted the ownership WAL");
			OwnershipCoordinator.RecoveryResult recovery = recover(fixture, FailureInjector.NONE);
			check(recovery.kind() == OwnershipCoordinator.RecoveryResult.Kind.ACQUIRE_ROLLED_BACK
					&& recovery.restored() == 4, "pre-HEAD crash did not roll the world fully back");
			check(fixture.world().allOriginal() && !fixture.ownershipFiles().isLocked()
					&& !fixture.ownershipFiles().hasCommitted(), "pre-HEAD recovery duplicated or dropped water");
		});
	}

	/** Decisive post-commit scenario: ownership is authoritative, so the preimage is never restored. */
	private static void testCrashAfterHead() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 4);
			expectIOException(() -> acquire(fixture, failAt(FailurePoint.AFTER_HEAD_PUBLISH, 0)));
			check(fixture.regionFiles().committedGeneration(fixture.regionId()) == 1L,
					"region HEAD did not publish before the injected crash");
			check(!fixture.ownershipFiles().hasCommitted(), "committed registry published before HEAD");
			OwnershipCoordinator.RecoveryResult recovery = recover(fixture, FailureInjector.NONE);
			check(recovery.kind() == OwnershipCoordinator.RecoveryResult.Kind.COMPLETED
					&& recovery.restored() == 4, "post-HEAD recovery did not complete ownership");
			check(fixture.world().airCount() == 4, "post-HEAD recovery restored the preimage and duplicated water");
			OwnershipCommitted committed = fixture.ownershipFiles().readCommitted();
			check(committed.generation() == 1L && committed.ownedBlocks().size() == 4,
					"committed registry was not rebuilt from the WAL");
			check(!fixture.ownershipFiles().isLocked(), "post-HEAD recovery kept the lock");
		});
	}

	private static void testCrashBeforeCleanup() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 2);
			expectIOException(() -> acquire(fixture, failAt(FailurePoint.BEFORE_CLEANUP, 0)));
			OwnershipCoordinator.RecoveryResult recovery = recover(fixture, FailureInjector.NONE);
			check(recovery.kind() == OwnershipCoordinator.RecoveryResult.Kind.COMPLETED
					&& recovery.restored() == 2 && fixture.world().airCount() == 2
					&& !fixture.ownershipFiles().isLocked(), "pre-cleanup recovery did not complete idempotently");
		});
	}

	private static void testNormalRelease() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 3);
			acquire(fixture, FailureInjector.NONE);
			OwnershipCoordinator.ReleaseResult result = release(fixture, FailureInjector.NONE);
			check(result.generation() == 2L && result.restoredBlocks() == 3
					&& result.skippedBlocks() == 0, "release counts are wrong");
			check(fixture.world().allOriginal(), "release did not restore the preimage");
			check(fixture.ownershipFiles().readCommitted().ownedBlocks().isEmpty(), "release did not clear ownership");
			check(!fixture.ownershipFiles().isLocked(), "release kept the WAL");
			check(fixture.regionFiles().committedGeneration(fixture.regionId()) == 2L, "release HEAD is wrong");
		});
	}

	/** Release crash before HEAD has not touched the world, even if matching water later appears. */
	private static void testReleaseCrashBeforeHead() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 3);
			acquire(fixture, FailureInjector.NONE);
			expectIOException(() -> release(fixture, failAt(FailurePoint.BEFORE_HEAD_PUBLISH, 0)));
			OwnershipJournal.BlockEntry first = fixture.world().originalEntries().getFirst();
			fixture.world().current.put(first.packedPosition(), "water");
			OwnershipCoordinator.RecoveryResult recovery = recover(fixture, FailureInjector.NONE);
			check(recovery.kind() == OwnershipCoordinator.RecoveryResult.Kind.RELEASE_ROLLED_BACK,
					"pre-HEAD release recovery did not retain the base generation");
			check(fixture.world().waterCount() == 1 && fixture.world().airCount() == 2,
					"pre-HEAD release recovery deleted matching pre-existing water");
			check(fixture.regionFiles().committedGeneration(fixture.regionId()) == 1L,
					"pre-HEAD release recovery advanced the region HEAD");
		});
	}

	/** Release crash after HEAD must finish restoration instead of rolling committed state back. */
	private static void testReleaseCrash() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 3);
			acquire(fixture, FailureInjector.NONE);
			expectIOException(() -> release(fixture, failAt(FailurePoint.AFTER_RESTORE_BLOCK, 2)));
			check(fixture.world().waterCount() == 2 && fixture.world().airCount() == 1,
					"release interruption state is wrong");
			OwnershipCoordinator.RecoveryResult recovery = recover(fixture, FailureInjector.NONE);
			check(recovery.kind() == OwnershipCoordinator.RecoveryResult.Kind.COMPLETED
					&& recovery.restored() == 1 && recovery.alreadyRestored() == 2,
					"release completion counts are wrong");
			check(fixture.world().allOriginal(), "release completion did not restore the remaining preimage");
			OwnershipCommitted committed = fixture.ownershipFiles().readCommitted();
			check(committed.generation() == 2L && committed.ownedBlocks().isEmpty(),
					"release completion did not clear the committed registry");
			check(fixture.regionFiles().committedGeneration(fixture.regionId()) == 2L,
					"release completion did not retain the committed region HEAD");
		});
	}

	private static void testCorruptWalLock() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 2);
			expectIOException(() -> acquire(fixture, failAt(FailurePoint.AFTER_WAL_PUBLISH, 0)));
			byte[] corrupt = {1, 2, 3, 4, 5};
			Files.write(fixture.ownershipFiles().walPath(), corrupt);
			expectIOException(() -> recover(fixture, FailureInjector.NONE));
			expectIOException(() -> acquire(fixture, FailureInjector.NONE));
			check(java.util.Arrays.equals(corrupt, Files.readAllBytes(fixture.ownershipFiles().walPath())),
					"corrupt WAL was overwritten");
			check(fixture.world().allOriginal() && fixture.ownershipFiles().isLocked(),
					"corrupt WAL did not lock transitions");
		});
	}

	private static void testWalGenerationMismatch() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 2);
			acquire(fixture, FailureInjector.NONE);
			fixture.ownershipFiles().publishWAL(new OwnershipJournal(fixture.regionId(), DIMENSION_ID, 3L, 4L,
					OwnershipJournal.Operation.ACQUIRE, entries(2)));
			expectIOException(() -> recover(fixture, FailureInjector.NONE));
			expectIOException(() -> acquire(fixture, FailureInjector.NONE));
			check(fixture.ownershipFiles().isLocked(), "generation mismatch did not stay locked");
		});
	}

	private static void testCommittedAheadLocks() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 2);
			expectIOException(() -> acquire(fixture, failAt(FailurePoint.AFTER_HEAD_PUBLISH, 0)));
			fixture.ownershipFiles().publishCommitted(
					new OwnershipCommitted(fixture.regionId(), DIMENSION_ID, 5L, entries(2)));
			expectIOException(() -> recover(fixture, FailureInjector.NONE));
			check(fixture.ownershipFiles().isLocked() && fixture.world().airCount() == 2,
					"ahead-of-target committed registry did not lock safely");
		});
	}

	private static void testReconcile() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 3);
			List<OwnershipJournal.BlockEntry> entries = fixture.world().originalEntries();
			acquire(fixture, FailureInjector.NONE);
			fixture.world().current.put(entries.get(0).packedPosition(), "PLAYER_SOLID");
			fixture.world().current.put(entries.get(1).packedPosition(), "water-external");
			OwnershipCoordinator.ReconcileResult result = OwnershipCoordinator.reconcile(
					fixture.ownershipFiles(), fixture.world());
			check(result.generation() == 1L && result.ownedBlocks() == 3, "reconcile counts are wrong");
			check(result.ownedAir() == 1 && result.playerConflicts() == 1 && result.waterConflicts() == 1,
					"reconcile did not classify air, player and water");
			OwnershipCoordinator.ReconcileResult empty = OwnershipCoordinator.reconcile(
					new OwnershipFiles(root.resolve("missing-world"), UUID.randomUUID()), fixture.world());
			check(empty.ownedBlocks() == 0 && !empty.transitionLocked(), "missing registry was not empty");
		});
	}

	private static void testRegionIsolation() throws Exception {
		withTemporaryWorld(root -> {
			Fixture locked = new Fixture(root, 2);
			expectIOException(() -> acquire(locked, failAt(FailurePoint.AFTER_WAL_PUBLISH, 0)));
			Fixture other = new Fixture(root, 3);
			OwnershipCoordinator.AcquireResult result = acquire(other, FailureInjector.NONE);
			check(result.generation() == 1L && other.world().airCount() == 3,
					"another region's lock blocked an independent acquire");
			OwnershipCoordinator.RecoveryResult recovery = recover(other, FailureInjector.NONE);
			check(recovery.kind() == OwnershipCoordinator.RecoveryResult.Kind.NONE,
					"a clean region reported a recovery");
			check(locked.ownershipFiles().isLocked(), "the locked region lost its lock");
		});
	}

	private static void testOrphanWarning() throws Exception {
		withTemporaryWorld(root -> {
			Fixture fixture = new Fixture(root, 2);
			Files.createDirectories(fixture.ownershipFiles().directoryPath());
			Files.writeString(fixture.ownershipFiles().warningPath(), "orphaned warning");
			expectIOException(() -> recover(fixture, FailureInjector.NONE));
			check(fixture.ownershipFiles().isLocked(), "orphan warning was silently removed");
		});
	}

	private static void testUnknownOwnershipLayout() throws Exception {
		withTemporaryWorld(root -> {
			Path ownershipRoot = root.resolve("mmm").resolve(OwnershipFiles.ROOT_DIRECTORY_NAME);
			Files.createDirectories(ownershipRoot);
			Path rootMystery = ownershipRoot.resolve("mystery.dat");
			Files.writeString(rootMystery, "foreign root data");
			OwnershipStore store = new OwnershipStore(root);
			check(!store.scanIssues().isEmpty(), "unknown ownership-root file was ignored");
			check(Files.exists(rootMystery), "scan deleted unknown ownership-root content");

			UUID regionId = UUID.randomUUID();
			OwnershipFiles files = store.files(regionId);
			Files.createDirectories(files.directoryPath());
			Path regionMystery = files.directoryPath().resolve("future-format.bin");
			Files.writeString(regionMystery, "future data");
			check(files.isLocked(), "unknown per-region ownership content did not lock");
			expectIOException(files::deleteStore);
			check(Files.exists(regionMystery), "deleteStore removed unknown ownership content");
		});
	}

	private static void testCorruptCommittedLock() throws Exception {
		withTemporaryWorld(root -> {
			UUID regionId = UUID.randomUUID();
			OwnershipStore store = new OwnershipStore(root);
			OwnershipFiles files = store.files(regionId);
			Files.createDirectories(files.directoryPath());
			byte[] corrupt = {8, 7, 6, 5};
			Files.write(files.committedPath(), corrupt);
			check(files.isLocked() && !store.scanIssues().isEmpty(),
					"corrupt committed ownership was not reported as locked");
			check(!store.recoverUnfinished(null).isEmpty(),
					"boot recovery silently accepted corrupt committed ownership");
			check(java.util.Arrays.equals(corrupt, Files.readAllBytes(files.committedPath())),
					"boot recovery rewrote corrupt committed ownership");
		});
	}

	private static OwnershipCoordinator.AcquireResult acquire(Fixture fixture, FailureInjector failures)
			throws IOException {
		RegionState staged = RegionState.capture(populatedRegion(fixture.regionId()), 1L);
		return OwnershipCoordinator.acquire(fixture.regionFiles(), fixture.ownershipFiles(), staged,
				fixture.world().originalEntries(), "test warning", fixture.world(), failures);
	}

	private static OwnershipCoordinator.ReleaseResult release(Fixture fixture, FailureInjector failures)
			throws IOException {
		RegionState staged = RegionState.capture(populatedRegion(fixture.regionId()), 2L);
		return OwnershipCoordinator.release(fixture.regionFiles(), fixture.ownershipFiles(), staged,
				"test warning", fixture.world(), failures);
	}

	private static OwnershipCoordinator.RecoveryResult recover(Fixture fixture, FailureInjector failures)
			throws IOException {
		return OwnershipCoordinator.recover(fixture.regionFiles(), fixture.ownershipFiles(),
				fixture.world(), failures);
	}

	private static List<OwnershipJournal.BlockEntry> entries(int blockCount) {
		List<OwnershipJournal.BlockEntry> blocks = new ArrayList<>(blockCount);
		for (int i = 0; i < blockCount; i++) {
			blocks.add(new OwnershipJournal.BlockEntry(100L + i, waterState()));
		}
		return blocks;
	}

	private static CompoundTag waterState() {
		CompoundTag state = new CompoundTag();
		state.putString("Name", "minecraft:water");
		return state;
	}

	private static FluidRegion populatedRegion(UUID id) {
		BlockPos origin = new BlockPos(15, 16, 15);
		FluidDomain domain = new FluidDomain(6, 4, 6, CONFIG, 512);
		domain.setSolid(0, 0, 0, true);
		domain.setVolume(1, 1, 1, 0.25F);
		domain.setVolume(2, 1, 2, 1.0F);
		domain.setVolume(4, 2, 4, 0.625F);
		FlipParticleState state = domain.exportParticleState();
		float[] velocities = state.copyParticleVelocities();
		for (int i = 0; i < velocities.length; i++) velocities[i] = (i % 7 - 3) * 0.03125F;
		domain.restoreParticleState(new FlipParticleState(state.sizeX(), state.sizeY(), state.sizeZ(), state.cellSize(),
				state.particleCount(), state.totalVolume(), state.copyPositionVolume(), velocities, state.copySolidMask()));
		Set<Long> interior = new HashSet<>();
		for (int z = 15; z <= 17; z++) for (int y = 16; y <= 17; y++) for (int x = 15; x <= 17; x++) {
			interior.add(new BlockPos(x, y, z).asLong());
		}
		BlockPos controller = new BlockPos(14, 16, 15);
		Set<Long> chunks = Set.of(ChunkPos.pack(0, 0), ChunkPos.pack(1, 0), ChunkPos.pack(0, 1), ChunkPos.pack(1, 1));
		FluidRegionFootprint footprint = new FluidRegionFootprint(controller, origin, new BlockPos(17, 17, 17),
				interior, Set.of(controller.asLong()), chunks);
		return new FluidRegion(id, DIMENSION, origin, domain, 512L, footprint);
	}

	private static void assertJournalEquals(OwnershipJournal expected, OwnershipJournal actual) {
		check(expected.regionId().equals(actual.regionId()), "region id did not round-trip");
		check(expected.dimensionId().equals(actual.dimensionId()), "dimension did not round-trip");
		check(expected.baseGeneration() == actual.baseGeneration()
				&& expected.targetGeneration() == actual.targetGeneration(), "generations did not round-trip");
		check(expected.operation() == actual.operation(), "operation did not round-trip");
		check(expected.blocks().equals(actual.blocks()), "blocks did not round-trip");
	}

	private static OwnershipCoordinator.FailureInjector failAt(
			OwnershipCoordinator.FailurePoint expectedPoint,
			int expectedIndex
	) {
		return (point, index) -> {
			if (point == expectedPoint && index == expectedIndex) {
				throw new IOException("Injected failure at " + point + (index == 0 ? "" : " #" + index));
			}
		};
	}

	private static void expectIOException(CheckedRunnable action) throws Exception {
		try {
			action.run();
		} catch (IOException expected) {
			return;
		}
		throw new AssertionError("Expected IOException");
	}

	private static void run(String name, CheckedRunnable test) throws Exception {
		test.run();
		passed++;
		System.out.println("PASS " + name);
	}

	private static void withTemporaryWorld(TemporaryWorldTest test) throws Exception {
		Path root = Files.createTempDirectory("mmm-ownership-test-").toAbsolutePath().normalize();
		try {
			test.run(root);
		} finally {
			deleteTestDirectory(root);
		}
	}

	private static void deleteTestDirectory(Path root) throws IOException {
		try (var paths = Files.walk(root)) {
			try {
				paths.sorted(Comparator.reverseOrder()).forEach(path -> {
					if (!path.startsWith(root)) throw new AssertionError("Refusing to delete outside test directory: " + path);
					try {
						Files.deleteIfExists(path);
					} catch (IOException exception) {
						throw new UncheckedIOException(exception);
					}
				});
			} catch (UncheckedIOException exception) {
				throw exception.getCause();
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	@FunctionalInterface
	private interface CheckedRunnable {
		void run() throws Exception;
	}

	@FunctionalInterface
	private interface TemporaryWorldTest {
		void run(Path root) throws Exception;
	}

	private record Fixture(
			RegionStateFiles regionFiles,
			OwnershipFiles ownershipFiles,
			FakeWorld world,
			UUID regionId
	) {
		Fixture(Path root, int blockCount) {
			this(root, blockCount, UUID.randomUUID());
		}

		Fixture(Path root, int blockCount, UUID regionId) {
			this(new RegionStateFiles(root), new OwnershipFiles(root, regionId), new FakeWorld(entries(blockCount)), regionId);
		}
	}

	/** String-state fake world: positions hold "water", "AIR", "PLAYER_SOLID" or "water-external". */
	private static final class FakeWorld implements OwnershipCoordinator.WorldAccess {
		private final Map<Long, String> original = new HashMap<>();
		private final Map<Long, String> current = new HashMap<>();
		private boolean dimensionLoaded = true;

		private FakeWorld(List<OwnershipJournal.BlockEntry> candidates) {
			for (OwnershipJournal.BlockEntry entry : candidates) {
				original.put(entry.packedPosition(), "water");
				current.put(entry.packedPosition(), "water");
			}
		}

		List<OwnershipJournal.BlockEntry> originalEntries() {
			List<OwnershipJournal.BlockEntry> entries = new ArrayList<>(original.size());
			for (Map.Entry<Long, String> entry : original.entrySet()) {
				entries.add(new OwnershipJournal.BlockEntry(entry.getKey(), waterState()));
			}
			entries.sort(Comparator.comparingLong(OwnershipJournal.BlockEntry::packedPosition));
			return entries;
		}

		@Override
		public boolean isDimensionLoaded(String dimensionId) {
			return dimensionLoaded && DIMENSION_ID.equals(dimensionId);
		}

		@Override
		public OwnershipCoordinator.RemoveOutcome removeWater(String dimensionId, OwnershipJournal.BlockEntry block)
				throws IOException {
			checkDimension(dimensionId);
			String state = current.get(block.packedPosition());
			if ("AIR".equals(state)) return OwnershipCoordinator.RemoveOutcome.ALREADY_CLEAR;
			if (!"water".equals(state)) return OwnershipCoordinator.RemoveOutcome.SKIPPED_OCCUPIED;
			current.put(block.packedPosition(), "AIR");
			return OwnershipCoordinator.RemoveOutcome.REMOVED;
		}

		@Override
		public OwnershipCoordinator.RestoreOutcome restore(String dimensionId, OwnershipJournal.BlockEntry block)
				throws IOException {
			checkDimension(dimensionId);
			long position = block.packedPosition();
			String state = current.get(position);
			if ("water".equals(state)) return OwnershipCoordinator.RestoreOutcome.ALREADY_RESTORED;
			if (!"AIR".equals(state)) return OwnershipCoordinator.RestoreOutcome.SKIPPED_OCCUPIED;
			current.put(position, "water");
			return OwnershipCoordinator.RestoreOutcome.RESTORED;
		}

		@Override
		public OwnershipCoordinator.InspectOutcome inspect(String dimensionId, OwnershipJournal.BlockEntry block)
				throws IOException {
			checkDimension(dimensionId);
			String state = current.get(block.packedPosition());
			if ("AIR".equals(state)) return OwnershipCoordinator.InspectOutcome.OWNED_AIR;
			if ("water".equals(state) || "water-external".equals(state)) {
				return OwnershipCoordinator.InspectOutcome.PREIMAGE_PRESENT;
			}
			return OwnershipCoordinator.InspectOutcome.OCCUPIED;
		}

		private void checkDimension(String dimensionId) throws IOException {
			if (!dimensionLoaded || !DIMENSION_ID.equals(dimensionId)) {
				throw new IOException("dimension is not loaded");
			}
		}

		private int airCount() {
			return (int) current.values().stream().filter("AIR"::equals).count();
		}

		private int waterCount() {
			return (int) current.values().stream().filter("water"::equals).count();
		}

		private boolean allOriginal() {
			return original.equals(current);
		}
	}
}
