package com.example.simulation.ownership;

import com.example.simulation.ownership.OwnershipProtoTransaction.FailureInjector;
import com.example.simulation.ownership.OwnershipProtoTransaction.FailurePoint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone M6.0 ownership-prototype codec, file, transaction and fault-injection gate. The two
 * decisive scenarios are the ones around the committed publish point: a crash before it must roll
 * the world back to the vanilla preimage, a crash after it must complete the committed ownership
 * and never restore the preimage again.
 */
public final class M6OwnershipPrototypeValidation {
	private static final String DIMENSION = "minecraft:overworld";
	private static int passed;

	private M6OwnershipPrototypeValidation() { }

	public static void main(String[] args) throws Exception {
		run("journal codec round trip and strictness", M6OwnershipPrototypeValidation::testJournalCodec);
		run("committed codec round trip and strictness", M6OwnershipPrototypeValidation::testCommittedCodec);
		run("normal acquire commits generation and owns water", M6OwnershipPrototypeValidation::testNormalAcquire);
		run("acquire skips player-occupied candidates", M6OwnershipPrototypeValidation::testAcquireSkipsOccupied);
		run("second acquire advances generation and unions owned", M6OwnershipPrototypeValidation::testGenerationAdvance);
		run("failure before journal write publishes nothing", M6OwnershipPrototypeValidation::testFailureBeforeWrite);
		run("failure after journal publish rolls forward to recovery", M6OwnershipPrototypeValidation::testFailureAfterPublish);
		run("mid-removal crash rolls back every removed block", M6OwnershipPrototypeValidation::testFailureMidRemoval);
		run("recovery interruption is retryable", M6OwnershipPrototypeValidation::testRecoveryInterruption);
		run("crash after mutation before committed publish rolls back", M6OwnershipPrototypeValidation::testCrashBeforeCommit);
		run("crash after committed publish completes ownership", M6OwnershipPrototypeValidation::testCrashAfterCommit);
		run("crash before completion delete keeps committed state", M6OwnershipPrototypeValidation::testCrashBeforeCleanup);
		run("release restores water and empties the registry", M6OwnershipPrototypeValidation::testNormalRelease);
		run("release crash before committed publish undoes restoration", M6OwnershipPrototypeValidation::testReleaseCrash);
		run("corrupt journal locks transitions", M6OwnershipPrototypeValidation::testCorruptJournalLock);
		run("committed generation mismatch locks transitions", M6OwnershipPrototypeValidation::testGenerationMismatch);
		run("warning without journal requires manual check", M6OwnershipPrototypeValidation::testOrphanWarning);
		run("journal and committed dimension mismatch locks", M6OwnershipPrototypeValidation::testDimensionMismatch);
		run("reconcile reports air, player and water conflicts", M6OwnershipPrototypeValidation::testReconcile);
		run("stale temporary files are harmless", M6OwnershipPrototypeValidation::testTemporaryFiles);
		run("atomic move unavailable fails closed", M6OwnershipPrototypeValidation::testAtomicMoveUnavailable);
		System.out.println("M6OwnershipPrototypeValidation passed " + passed + " scenarios");
	}

	private static void testJournalCodec() throws Exception {
		OwnershipJournalProtoCodec codec = new OwnershipJournalProtoCodec();
		OwnershipJournalProto expected = acquireJournal(3);
		CompoundTag encoded = codec.encode(expected);
		check(encoded.getIntOr("Version", -1) == 1, "codec changed the journal version");
		check(encoded.contains("Dimension") && encoded.contains("Blocks"), "codec changed journal field names");
		assertJournalEquals(expected, codec.decode(encoded));

		CompoundTag future = codec.encode(expected);
		future.putInt("Version", 99);
		expectIOException(() -> codec.decode(future));

		CompoundTag missing = codec.encode(expected);
		missing.remove("BaseGeneration");
		expectIOException(() -> codec.decode(missing));

		CompoundTag badTarget = codec.encode(expected);
		badTarget.putLong("TargetGeneration", expected.baseGeneration() + 2L);
		expectIOException(() -> codec.decode(badTarget));

		CompoundTag badOperation = codec.encode(expected);
		badOperation.putString("Operation", "SNAPSHOT");
		expectIOException(() -> codec.decode(badOperation));

		CompoundTag duplicated = codec.encode(expected);
		duplicated.getListOrEmpty("Blocks").getCompoundOrEmpty(0)
				.putLong("Pos", duplicated.getListOrEmpty("Blocks").getCompoundOrEmpty(1).getLongOr("Pos", 0L));
		expectIOException(() -> codec.decode(duplicated));

		try {
			new OwnershipJournalProto(DIMENSION, 3L, 7L, OwnershipJournalProto.Operation.ACQUIRE, expected.blocks());
			throw new AssertionError("Expected IllegalArgumentException for non-adjacent generations");
		} catch (IllegalArgumentException expectedFailure) {
			// expected
		}
	}

	private static void testCommittedCodec() throws Exception {
		OwnershipCommittedProtoCodec codec = new OwnershipCommittedProtoCodec();
		OwnershipCommittedProto state = new OwnershipCommittedProto(
				OwnershipCommittedProto.PROTO_REGION_ID, DIMENSION, 4L, acquireJournal(2).blocks());
		OwnershipCommittedProto decoded = codec.decode(codec.encode(state));
		check(decoded.generation() == 4L && decoded.ownedBlocks().size() == 2, "committed state did not round-trip");
		check(decoded.dimensionId().equals(DIMENSION), "committed dimension did not round-trip");

		CompoundTag wrongRegion = codec.encode(state);
		wrongRegion.putString("RegionId", "other-region");
		expectIOException(() -> codec.decode(wrongRegion));

		CompoundTag negative = codec.encode(state);
		negative.putLong("Generation", -1L);
		expectIOException(() -> codec.decode(negative));
	}

	private static void testNormalAcquire() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(3));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			OwnershipProtoTransaction.BeginResult result = acquire(files, world, FailureInjector.NONE);
			check(result.generation() == 1L && result.transitionBlocks() == 3 && result.totalOwnedBlocks() == 3,
					"normal acquire counts are wrong");
			check(!files.isLocked(), "completed acquire kept transition files");
			check(!Files.exists(files.committedTemporaryPath()), "committed temp file leaked");
			OwnershipCommittedProto committed = files.readCommitted();
			check(committed.generation() == 1L && committed.ownedBlocks().size() == 3, "committed registry is wrong");
			check(world.airCount() == 3, "owned water was not removed from the world");
		});
	}

	private static void testAcquireSkipsOccupied() throws Exception {
		withTemporaryWorld(root -> {
			List<OwnershipJournalProto.BlockEntry> candidates = entries(3);
			FakeWorld world = new FakeWorld(candidates);
			world.current.put(candidates.get(1).packedPosition(), "PLAYER_SOLID");
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			OwnershipProtoTransaction.BeginResult result = acquire(files, world, FailureInjector.NONE);
			check(result.transitionBlocks() == 2 && result.totalOwnedBlocks() == 2,
					"player-occupied candidate was acquired");
			check(files.readCommitted().ownedBlocks().stream()
							.noneMatch(entry -> entry.packedPosition() == candidates.get(1).packedPosition()),
					"occupied position entered the committed registry");
			check("PLAYER_SOLID".equals(world.current.get(candidates.get(1).packedPosition())),
					"player block was disturbed");
		});
	}

	private static void testGenerationAdvance() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(2));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			acquire(files, world, FailureInjector.NONE);
			world.addWater(900L);
			List<OwnershipJournalProto.BlockEntry> second = List.of(new OwnershipJournalProto.BlockEntry(900L, waterState()));
			OwnershipProtoTransaction.BeginResult result = OwnershipProtoTransaction.beginAcquire(
					files, DIMENSION, second, "test warning", world, FailureInjector.NONE);
			check(result.generation() == 2L && result.totalOwnedBlocks() == 3,
					"second acquire did not advance the generation and union the owned set");
			check(files.readCommitted().generation() == 2L, "committed generation did not advance");
		});
	}

	private static void testFailureBeforeWrite() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(3));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			expectIOException(() -> acquire(files, world, failAt(FailurePoint.BEFORE_JOURNAL_WRITE, 0)));
			check(!files.hasJournal() && !files.hasCommitted(), "failure before write published state");
			check(world.allOriginal(), "failure before write modified the world");
		});
	}

	private static void testFailureAfterPublish() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(3));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			expectIOException(() -> acquire(files, world, failAt(FailurePoint.AFTER_JOURNAL_PUBLISH, 0)));
			check(files.hasJournal() && !files.hasWarning(), "post-publish interruption state is wrong");
			check(world.allOriginal() && !files.hasCommitted(), "post-publish interruption changed committed state");
			expectIOException(() -> acquire(files, world, FailureInjector.NONE));
			OwnershipProtoTransaction.RecoveryResult recovery = recover(files, world, FailureInjector.NONE);
			check(recovery.kind() == OwnershipProtoTransaction.RecoveryResult.Kind.ACQUIRE_ROLLED_BACK
					&& recovery.alreadyRestored() == 3, "journal-only recovery was not recognized as untouched");
			check(!files.isLocked(), "journal-only recovery did not finish");
		});
	}

	private static void testFailureMidRemoval() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(4));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			expectIOException(() -> acquire(files, world, failAt(FailurePoint.AFTER_REMOVE_BLOCK, 2)));
			check(world.airCount() == 2 && files.hasJournal() && files.hasWarning(),
					"mid-removal interruption state is wrong");
			OwnershipProtoTransaction.RecoveryResult recovery = recover(files, world, FailureInjector.NONE);
			check(recovery.kind() == OwnershipProtoTransaction.RecoveryResult.Kind.ACQUIRE_ROLLED_BACK
					&& recovery.restored() == 2 && recovery.alreadyRestored() == 2,
					"partial removal was not rolled back completely");
			check(world.allOriginal() && !files.isLocked() && !files.hasCommitted(),
					"mid-removal recovery left inconsistent state");
		});
	}

	private static void testRecoveryInterruption() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(3));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			expectIOException(() -> acquire(files, world, failAt(FailurePoint.AFTER_REMOVE_BLOCK, 3)));
			expectIOException(() -> recover(files, world, failAt(FailurePoint.AFTER_RESTORE_BLOCK, 2)));
			check(world.airCount() == 1 && files.isLocked(), "interrupted recovery lost its lock");
			OwnershipProtoTransaction.RecoveryResult retry = recover(files, world, FailureInjector.NONE);
			check(retry.restored() == 1 && retry.alreadyRestored() == 2 && world.allOriginal() && !files.isLocked(),
					"recovery retry was not idempotent");
		});
	}

	/** Decisive pre-commit scenario: the world is fully mutated but HEAD still names the base generation. */
	private static void testCrashBeforeCommit() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(4));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			expectIOException(() -> acquire(files, world, failAt(FailurePoint.BEFORE_COMMITTED_PUBLISH, 0)));
			check(world.airCount() == 4 && !files.hasCommitted(),
					"pre-commit crash state is wrong");
			OwnershipProtoTransaction.RecoveryResult recovery = recover(files, world, FailureInjector.NONE);
			check(recovery.kind() == OwnershipProtoTransaction.RecoveryResult.Kind.ACQUIRE_ROLLED_BACK
					&& recovery.restored() == 4, "pre-commit crash did not roll the world fully back");
			check(world.allOriginal() && !files.isLocked(),
					"pre-commit recovery duplicated or dropped water");
		});
	}

	/** Decisive post-commit scenario: ownership is authoritative, so the preimage is never restored again. */
	private static void testCrashAfterCommit() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(4));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			expectIOException(() -> acquire(files, world, failAt(FailurePoint.AFTER_COMMITTED_PUBLISH, 0)));
			check(files.readCommitted().generation() == 1L, "committed generation did not publish");
			OwnershipProtoTransaction.RecoveryResult recovery = recover(files, world, FailureInjector.NONE);
			check(recovery.kind() == OwnershipProtoTransaction.RecoveryResult.Kind.COMPLETED
					&& recovery.restored() == 4, "post-commit recovery did not complete ownership");
			check(world.airCount() == 4, "post-commit recovery restored the preimage and duplicated water");
			check(!files.isLocked(), "post-commit recovery kept transition files");
		});
	}

	private static void testCrashBeforeCleanup() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(2));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			expectIOException(() -> acquire(files, world, failAt(FailurePoint.BEFORE_COMPLETION_DELETE, 0)));
			OwnershipProtoTransaction.RecoveryResult recovery = recover(files, world, FailureInjector.NONE);
			check(recovery.kind() == OwnershipProtoTransaction.RecoveryResult.Kind.COMPLETED
					&& recovery.restored() == 2 && world.airCount() == 2 && !files.isLocked(),
					"pre-cleanup recovery did not complete idempotently");
		});
	}

	private static void testNormalRelease() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(3));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			acquire(files, world, FailureInjector.NONE);
			OwnershipProtoTransaction.BeginResult result = OwnershipProtoTransaction.beginRelease(
					files, "test warning", world, FailureInjector.NONE);
			check(result.generation() == 2L && result.totalOwnedBlocks() == 0, "release counts are wrong");
			check(world.allOriginal(), "release did not restore the preimage");
			check(files.readCommitted().ownedBlocks().isEmpty() && !files.isLocked(),
					"release did not empty the registry");
		});
	}

	/** Release crash before the committed publish must undo the partial restoration back to owned air. */
	private static void testReleaseCrash() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(3));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			acquire(files, world, FailureInjector.NONE);
			expectIOException(() -> OwnershipProtoTransaction.beginRelease(
					files, "test warning", world, failAt(FailurePoint.AFTER_RESTORE_BLOCK, 2)));
			check(world.waterCount() == 2 && world.airCount() == 1, "release interruption state is wrong");
			OwnershipProtoTransaction.RecoveryResult recovery = recover(files, world, FailureInjector.NONE);
			check(recovery.kind() == OwnershipProtoTransaction.RecoveryResult.Kind.RELEASE_ROLLED_BACK
					&& recovery.restored() == 2 && recovery.alreadyRestored() == 1,
					"release rollback counts are wrong");
			check(world.airCount() == 3 && !world.allOriginal(), "release rollback did not undo the restoration");
			check(files.readCommitted().generation() == 1L && files.readCommitted().ownedBlocks().size() == 3,
					"release rollback changed the committed registry");
			check(!files.isLocked(), "release rollback kept transition files");
		});
	}

	private static void testCorruptJournalLock() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(2));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			expectIOException(() -> acquire(files, world, failAt(FailurePoint.AFTER_JOURNAL_PUBLISH, 0)));
			byte[] corrupt = {1, 2, 3, 4, 5};
			Files.write(files.journalPath(), corrupt);
			expectIOException(() -> recover(files, world, FailureInjector.NONE));
			expectIOException(() -> acquire(files, world, FailureInjector.NONE));
			check(java.util.Arrays.equals(corrupt, Files.readAllBytes(files.journalPath())),
					"corrupt journal was overwritten");
			check(world.allOriginal() && files.isLocked(), "corrupt journal did not lock transitions");
		});
	}

	private static void testGenerationMismatch() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(2));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			acquire(files, world, FailureInjector.NONE);
			world.addWater(500L);
			expectIOException(() -> OwnershipProtoTransaction.beginAcquire(
					files, DIMENSION,
					List.of(new OwnershipJournalProto.BlockEntry(500L, waterState())),
					"test warning", world, failAt(FailurePoint.AFTER_COMMITTED_PUBLISH, 0)));
			// Hand-corrupt the committed generation so it matches neither base nor target.
			OwnershipCommittedProto committed = files.readCommitted();
			OwnershipCommittedProto mismatch = new OwnershipCommittedProto(
					OwnershipCommittedProto.PROTO_REGION_ID, DIMENSION,
					committed.generation() + 2L, committed.ownedBlocks());
			Files.deleteIfExists(files.committedPath());
			files.publishCommitted(mismatch);
			expectIOException(() -> recover(files, world, FailureInjector.NONE));
			expectIOException(() -> acquire(files, world, FailureInjector.NONE));
			check(files.isLocked(), "generation mismatch did not stay locked");
		});
	}

	private static void testOrphanWarning() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(2));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			Files.createDirectories(files.directoryPath());
			Files.writeString(files.warningPath(), "orphaned warning");
			expectIOException(() -> recover(files, world, FailureInjector.NONE));
			check(files.isLocked(), "orphan warning was silently removed");
		});
	}

	private static void testDimensionMismatch() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(2));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			acquire(files, world, FailureInjector.NONE);
			world.addWater(700L);
			expectIOException(() -> OwnershipProtoTransaction.beginAcquire(
					files, DIMENSION,
					List.of(new OwnershipJournalProto.BlockEntry(700L, waterState())),
					"test warning", world, failAt(FailurePoint.AFTER_COMMITTED_PUBLISH, 0)));
			OwnershipJournalProto journal = files.readJournal();
			// A committed registry that claims a different dimension at the target generation must lock.
			OwnershipCommittedProto foreign = new OwnershipCommittedProto(
					OwnershipCommittedProto.PROTO_REGION_ID, "minecraft:the_nether",
					journal.targetGeneration(), journal.blocks());
			Files.deleteIfExists(files.committedPath());
			files.publishCommitted(foreign);
			expectIOException(() -> recover(files, world, FailureInjector.NONE));
			check(files.isLocked(), "dimension mismatch did not stay locked");
		});
	}

	private static void testReconcile() throws Exception {
		withTemporaryWorld(root -> {
			List<OwnershipJournalProto.BlockEntry> candidates = entries(3);
			FakeWorld world = new FakeWorld(candidates);
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			acquire(files, world, FailureInjector.NONE);
			world.current.put(candidates.get(0).packedPosition(), "PLAYER_SOLID");
			world.current.put(candidates.get(1).packedPosition(), "water-external");
			OwnershipProtoTransaction.ReconcileResult result = OwnershipProtoTransaction.reconcile(files, world);
			check(result.generation() == 1L && result.ownedBlocks() == 3, "reconcile registry counts are wrong");
			check(result.ownedAir() == 1 && result.playerConflicts() == 1 && result.waterConflicts() == 1,
					"reconcile did not classify air, player and water conflicts");
			OwnershipProtoTransaction.ReconcileResult empty = OwnershipProtoTransaction.reconcile(
					new OwnershipProtoFiles(root.resolve("missing-world")), world);
			check(empty.ownedBlocks() == 0 && !empty.transitionLocked(), "missing registry was not reported as empty");
		});
	}

	private static void testTemporaryFiles() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(2));
			OwnershipProtoFiles files = new OwnershipProtoFiles(root);
			Files.createDirectories(files.directoryPath());
			Files.writeString(files.journalTemporaryPath(), "stale journal temp");
			Files.writeString(files.warningTemporaryPath(), "stale warning temp");
			Files.writeString(files.committedTemporaryPath(), "stale committed temp");
			acquire(files, world, FailureInjector.NONE);
			check(!Files.exists(files.journalTemporaryPath()) && !Files.exists(files.committedTemporaryPath()),
					"stale temporary files survived publication");
			Files.writeString(files.journalTemporaryPath(), "crash before publish");
			OwnershipProtoTransaction.RecoveryResult recovery = recover(files, world, FailureInjector.NONE);
			check(recovery.kind() == OwnershipProtoTransaction.RecoveryResult.Kind.NONE
					&& !Files.exists(files.journalTemporaryPath()) && !files.isLocked(),
					"orphan temporary file was treated as published state");
		});
	}

	private static void testAtomicMoveUnavailable() throws Exception {
		withTemporaryWorld(root -> {
			FakeWorld world = new FakeWorld(entries(2));
			OwnershipProtoFiles files = new OwnershipProtoFiles(
					root.resolve("realwater").resolve(OwnershipProtoFiles.DIRECTORY_NAME),
					new OwnershipJournalProtoCodec(),
					new OwnershipCommittedProtoCodec(),
					(source, target) -> {
						throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
					},
					(source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
					Files::deleteIfExists
			);
			expectIOException(() -> acquire(files, world, FailureInjector.NONE));
			check(world.allOriginal(), "non-atomic filesystem modified the world");
			check(!files.hasJournal() && !Files.exists(files.journalTemporaryPath()),
					"non-atomic filesystem published or leaked journal data");
		});
	}

	private static OwnershipProtoTransaction.BeginResult acquire(
			OwnershipProtoFiles files,
			FakeWorld world,
			OwnershipProtoTransaction.FailureInjector failures
	) throws IOException {
		return OwnershipProtoTransaction.beginAcquire(
				files, DIMENSION, world.originalEntries(), "test warning", world, failures);
	}

	private static OwnershipProtoTransaction.RecoveryResult recover(
			OwnershipProtoFiles files,
			FakeWorld world,
			OwnershipProtoTransaction.FailureInjector failures
	) throws IOException {
		return OwnershipProtoTransaction.recover(files, world, failures);
	}

	private static OwnershipJournalProto acquireJournal(int blockCount) {
		return new OwnershipJournalProto(DIMENSION, 5L, 6L, OwnershipJournalProto.Operation.ACQUIRE, entries(blockCount));
	}

	private static List<OwnershipJournalProto.BlockEntry> entries(int blockCount) {
		List<OwnershipJournalProto.BlockEntry> blocks = new ArrayList<>(blockCount);
		for (int i = 0; i < blockCount; i++) {
			blocks.add(new OwnershipJournalProto.BlockEntry(100L + i, waterState()));
		}
		return blocks;
	}

	private static CompoundTag waterState() {
		CompoundTag state = new CompoundTag();
		state.putString("Name", "minecraft:water");
		return state;
	}

	private static void assertJournalEquals(OwnershipJournalProto expected, OwnershipJournalProto actual) {
		check(expected.dimensionId().equals(actual.dimensionId()), "dimension did not round-trip");
		check(expected.baseGeneration() == actual.baseGeneration()
				&& expected.targetGeneration() == actual.targetGeneration(), "generations did not round-trip");
		check(expected.operation() == actual.operation(), "operation did not round-trip");
		check(expected.blocks().size() == actual.blocks().size(), "block count did not round-trip");
		for (int i = 0; i < expected.blocks().size(); i++) {
			check(expected.blocks().get(i).packedPosition() == actual.blocks().get(i).packedPosition(),
					"position did not round-trip");
			check(expected.blocks().get(i).state().equals(actual.blocks().get(i).state()), "state did not round-trip");
		}
	}

	private static OwnershipProtoTransaction.FailureInjector failAt(
			OwnershipProtoTransaction.FailurePoint expectedPoint,
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
		Path root = Files.createTempDirectory("realwater-ownership-proto-test-").toAbsolutePath().normalize();
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

	/** String-state fake world: positions hold "water", "AIR", "PLAYER_SOLID" or "water-external". */
	private static final class FakeWorld implements OwnershipProtoTransaction.WorldAccess {
		private final Map<Long, String> original = new HashMap<>();
		private final Map<Long, String> current = new HashMap<>();
		private boolean dimensionLoaded = true;

		private FakeWorld(List<OwnershipJournalProto.BlockEntry> candidates) {
			for (OwnershipJournalProto.BlockEntry entry : candidates) {
				original.put(entry.packedPosition(), "water");
				current.put(entry.packedPosition(), "water");
			}
		}

		List<OwnershipJournalProto.BlockEntry> originalEntries() {
			List<OwnershipJournalProto.BlockEntry> entries = new ArrayList<>(original.size());
			for (Map.Entry<Long, String> entry : original.entrySet()) {
				entries.add(new OwnershipJournalProto.BlockEntry(entry.getKey(), waterState()));
			}
			entries.sort(Comparator.comparingLong(OwnershipJournalProto.BlockEntry::packedPosition));
			return entries;
		}

		void addWater(long position) {
			current.put(position, "water");
		}

		@Override
		public boolean isDimensionLoaded(String dimensionId) {
			return dimensionLoaded && DIMENSION.equals(dimensionId);
		}

		@Override
		public OwnershipProtoTransaction.RemoveOutcome removeWater(
				String dimensionId,
				OwnershipJournalProto.BlockEntry block
		) throws IOException {
			checkDimension(dimensionId);
			String state = current.get(block.packedPosition());
			if ("AIR".equals(state)) return OwnershipProtoTransaction.RemoveOutcome.ALREADY_CLEAR;
			if (!"water".equals(state)) return OwnershipProtoTransaction.RemoveOutcome.SKIPPED_OCCUPIED;
			current.put(block.packedPosition(), "AIR");
			return OwnershipProtoTransaction.RemoveOutcome.REMOVED;
		}

		@Override
		public OwnershipProtoTransaction.RestoreOutcome restore(
				String dimensionId,
				OwnershipJournalProto.BlockEntry block
		) throws IOException {
			checkDimension(dimensionId);
			long position = block.packedPosition();
			String state = current.get(position);
			if ("water".equals(state)) return OwnershipProtoTransaction.RestoreOutcome.ALREADY_RESTORED;
			if (!"AIR".equals(state)) return OwnershipProtoTransaction.RestoreOutcome.SKIPPED_OCCUPIED;
			current.put(position, "water");
			return OwnershipProtoTransaction.RestoreOutcome.RESTORED;
		}

		@Override
		public OwnershipProtoTransaction.InspectOutcome inspect(
				String dimensionId,
				OwnershipJournalProto.BlockEntry block
		) throws IOException {
			checkDimension(dimensionId);
			String state = current.get(block.packedPosition());
			if ("AIR".equals(state)) return OwnershipProtoTransaction.InspectOutcome.OWNED_AIR;
			if ("water".equals(state) || "water-external".equals(state)) {
				return OwnershipProtoTransaction.InspectOutcome.PREIMAGE_PRESENT;
			}
			return OwnershipProtoTransaction.InspectOutcome.OCCUPIED;
		}

		private void checkDimension(String dimensionId) throws IOException {
			if (!dimensionLoaded || !DIMENSION.equals(dimensionId)) {
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
