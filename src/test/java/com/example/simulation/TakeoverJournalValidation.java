package com.example.simulation;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone M0 codec, file, transaction, and fault-injection regression suite. */
public final class TakeoverJournalValidation {
	private static final String DIMENSION = "minecraft:overworld";
	private static int passed;

	private TakeoverJournalValidation() { }

	public static void main(String[] args) throws Exception {
		run("takeover-journal current codec layout", TakeoverJournalValidation::testCodecLayout);
		run("normal create and delete", TakeoverJournalValidation::testNormalCreateAndDelete);
		run("failure before journal write", TakeoverJournalValidation::testFailureBeforeWrite);
		run("failure after atomic journal publish", TakeoverJournalValidation::testFailureAfterPublish);
		run("failure after removing block N", TakeoverJournalValidation::testFailureAfterRemoval);
		run("failure after restoring block N", TakeoverJournalValidation::testFailureAfterRestoration);
		run("failure before completion delete", TakeoverJournalValidation::testFailureBeforeDelete);
		run("corrupt journal remains locked", TakeoverJournalValidation::testCorruptJournalLock);
		run("unknown journal version remains locked", TakeoverJournalValidation::testUnknownVersionLock);
		run("unloaded dimension remains locked", TakeoverJournalValidation::testUnloadedDimension);
		run("stale temporary files are harmless", TakeoverJournalValidation::testTemporaryFiles);
		run("atomic move unavailable fails closed", TakeoverJournalValidation::testAtomicMoveUnavailable);
		run("warning publication failure rolls forward to recovery", TakeoverJournalValidation::testWarningPublicationFailure);
		run("delete failure preserves journal lock", TakeoverJournalValidation::testDeleteFailure);
		run("world restoration failure preserves journal lock", TakeoverJournalValidation::testWorldRestoreFailure);
		run("occupied player block is never overwritten", TakeoverJournalValidation::testOccupiedBlockSkip);
		System.out.println("TakeoverJournalValidation passed " + passed + " scenarios");
	}

	private static void testCodecLayout() throws Exception {
		TakeoverJournalCodec codec = new TakeoverJournalCodec();
		TakeoverJournal expected = journal(3);
		CompoundTag encoded = codec.encode(expected);
		check(encoded.getIntOr("Version", -1) == 1, "codec changed the v1 version");
		check(encoded.contains("Dimension") && encoded.contains("Blocks"), "codec changed v1 field names");
		assertJournalEquals(expected, codec.decode(encoded));

		CompoundTag currentRoot = new CompoundTag();
		currentRoot.putInt("Version", 1);
		currentRoot.putString("Dimension", DIMENSION);
		ListTag blocks = new ListTag();
		CompoundTag block = new CompoundTag();
		block.putLong("Pos", 42L);
		block.put("State", state("sample-water"));
		blocks.add(block);
		currentRoot.put("Blocks", blocks);
		TakeoverJournal decoded = codec.decode(currentRoot);
		check(decoded.blocks().size() == 1, "current entry was not decoded");
		check("sample-water".equals(stateName(decoded.blocks().getFirst())), "current state changed");

		CompoundTag missingState = currentRoot.copy();
		missingState.getListOrEmpty("Blocks").getCompoundOrEmpty(0).remove("State");
		expectIOException(() -> codec.decode(missingState));
	}

	private static void testNormalCreateAndDelete() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(3);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			TakeoverTransaction.BeginResult begin = TakeoverTransaction.begin(
					files,
					journal,
					TakeoverBackupStore.warningText(root, journal),
					world,
					TakeoverTransaction.FailureInjector.NONE
			);
			check(begin.removedBlocks() == 3, "not every candidate was removed");
			check(files.hasJournal() && files.hasWarning(), "published recovery files are missing");
			String warning = Files.readString(files.warningPath());
			check(warning.contains(DIMENSION) && warning.contains("3") && warning.contains("/realwater remove"),
					"warning omits required recovery information");
			check(world.airCount() == 3, "world was not taken over");

			TakeoverTransaction.RecoveryResult recovery = recover(files, world, TakeoverTransaction.FailureInjector.NONE);
			check(recovery.restoredBlocks() == 3 && recovery.skippedBlocks() == 0, "normal restore counts are wrong");
			check(world.allOriginal(), "normal restore did not restore every block");
			check(!files.isLocked(), "normal restore did not delete recovery files");
		});
	}

	private static void testFailureBeforeWrite() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(3);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			expectIOException(() -> begin(files, journal, world, failAt(TakeoverTransaction.FailurePoint.BEFORE_JOURNAL_WRITE, 0)));
			check(!files.isLocked(), "failure before write published recovery state");
			check(world.allOriginal(), "failure before write modified the world");
		});
	}

	private static void testFailureAfterPublish() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(3);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			expectIOException(() -> begin(files, journal, world, failAt(TakeoverTransaction.FailurePoint.AFTER_JOURNAL_PUBLISH, 0)));
			check(files.hasJournal() && !files.hasWarning(), "post-publish interruption state is wrong");
			check(world.allOriginal(), "post-publish interruption modified the world");
			byte[] originalJournal = Files.readAllBytes(files.journalPath());
			expectIOException(() -> begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE));
			check(java.util.Arrays.equals(originalJournal, Files.readAllBytes(files.journalPath())), "new takeover overwrote the old journal");
			TakeoverTransaction.RecoveryResult recovery = recover(files, world, TakeoverTransaction.FailureInjector.NONE);
			check(recovery.alreadyRestoredBlocks() == 3, "idempotent untouched recovery was not recognized");
			check(!files.isLocked(), "post-publish recovery did not finish");
		});
	}

	private static void testFailureAfterRemoval() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(4);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			expectIOException(() -> begin(files, journal, world, failAt(TakeoverTransaction.FailurePoint.AFTER_REMOVE_BLOCK, 2)));
			check(world.airCount() == 2, "removal interruption did not occur at block N");
			check(files.hasJournal() && files.hasWarning(), "removal interruption lost recovery files");
			expectIOException(() -> begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE));
			TakeoverTransaction.RecoveryResult recovery = recover(files, world, TakeoverTransaction.FailureInjector.NONE);
			check(recovery.restoredBlocks() == 2 && recovery.alreadyRestoredBlocks() == 2, "partial removal was not idempotently restored");
			check(world.allOriginal() && !files.isLocked(), "partial removal recovery did not complete");
		});
	}

	private static void testFailureAfterRestoration() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(4);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE);
			TakeoverTransaction.RecoveryException failure = expectRecoveryException(
					() -> recover(files, world, failAt(TakeoverTransaction.FailurePoint.AFTER_RESTORE_BLOCK, 2))
			);
			check(!failure.worldPassCompleted(), "mid-restore failure was marked complete");
			check(world.airCount() == 2 && files.isLocked(), "restore did not stop at block N");
			TakeoverTransaction.RecoveryResult retry = recover(files, world, TakeoverTransaction.FailureInjector.NONE);
			check(retry.restoredBlocks() == 2 && retry.alreadyRestoredBlocks() == 2, "restore retry was not idempotent");
			check(world.allOriginal() && !files.isLocked(), "restore retry did not complete");
		});
	}

	private static void testFailureBeforeDelete() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(3);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE);
			TakeoverTransaction.RecoveryException failure = expectRecoveryException(
					() -> recover(files, world, failAt(TakeoverTransaction.FailurePoint.BEFORE_COMPLETION_DELETE, 0))
			);
			check(failure.worldPassCompleted(), "pre-delete failure lost completed-world marker");
			check(world.allOriginal() && files.hasJournal() && files.hasWarning(), "pre-delete interruption state is unsafe");
			expectIOException(() -> begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE));
			TakeoverTransaction.RecoveryResult retry = recover(files, world, TakeoverTransaction.FailureInjector.NONE);
			check(retry.alreadyRestoredBlocks() == 3 && !files.isLocked(), "pre-delete retry did not finish idempotently");
		});
	}

	private static void testCorruptJournalLock() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(2);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			Files.write(files.journalPath(), new byte[] {1, 2, 3, 4, 5});
			byte[] corrupt = Files.readAllBytes(files.journalPath());
			expectIOException(() -> recover(files, world, TakeoverTransaction.FailureInjector.NONE));
			expectIOException(() -> begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE));
			check(java.util.Arrays.equals(corrupt, Files.readAllBytes(files.journalPath())), "corrupt journal was overwritten");
			check(world.allOriginal() && files.hasJournal(), "corrupt journal did not lock takeover");
		});
	}

	private static void testUnknownVersionLock() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(2);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			CompoundTag future = new TakeoverJournalCodec().encode(journal);
			future.putInt("Version", 99);
			NbtIo.writeCompressed(future, files.journalPath());
			expectIOException(() -> recover(files, world, TakeoverTransaction.FailureInjector.NONE));
			expectIOException(() -> begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE));
			check(files.hasJournal() && world.allOriginal(), "unknown version did not remain locked");
		});
	}

	private static void testUnloadedDimension() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(3);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE);
			world.dimensionLoaded = false;
			expectIOException(() -> recover(files, world, TakeoverTransaction.FailureInjector.NONE));
			check(world.airCount() == 3 && files.isLocked(), "unloaded dimension lost its recovery lock");
			expectIOException(() -> begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE));
			world.dimensionLoaded = true;
			recover(files, world, TakeoverTransaction.FailureInjector.NONE);
			check(world.allOriginal() && !files.isLocked(), "loaded dimension did not recover");
		});
	}

	private static void testTemporaryFiles() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(2);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			Files.writeString(files.journalTemporaryPath(), "stale journal temp");
			Files.writeString(files.warningTemporaryPath(), "stale warning temp");
			begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE);
			check(!Files.exists(files.journalTemporaryPath()) && !Files.exists(files.warningTemporaryPath()),
					"stale temporary files survived publication");
			recover(files, world, TakeoverTransaction.FailureInjector.NONE);
			Files.writeString(files.journalTemporaryPath(), "crash before publish");
			recover(files, world, TakeoverTransaction.FailureInjector.NONE);
			check(!Files.exists(files.journalTemporaryPath()) && !files.isLocked(), "orphan temporary file was treated as published state");
		});
	}

	private static void testAtomicMoveUnavailable() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(2);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(
					root,
					new TakeoverJournalCodec(),
					(source, target) -> { throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected"); },
					Files::deleteIfExists
			);
			expectIOException(() -> begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE));
			check(world.allOriginal(), "non-atomic filesystem modified the world");
			check(!files.hasJournal() && !Files.exists(files.journalTemporaryPath()), "non-atomic filesystem published or leaked journal data");
		});
	}

	private static void testWarningPublicationFailure() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(2);
			FakeWorld world = new FakeWorld(journal);
			AtomicInteger moves = new AtomicInteger();
			TakeoverJournalFiles files = new TakeoverJournalFiles(
					root,
					new TakeoverJournalCodec(),
					(source, target) -> {
						if (moves.incrementAndGet() == 2) {
							throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected warning move");
						}
						Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
					},
					Files::deleteIfExists
			);
			expectIOException(() -> begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE));
			check(files.hasJournal() && !files.hasWarning(), "warning failure did not preserve the authoritative journal");
			check(world.allOriginal(), "warning failure modified the world");
			recover(new TakeoverJournalFiles(root), world, TakeoverTransaction.FailureInjector.NONE);
			check(!new TakeoverJournalFiles(root).isLocked(), "journal-only recovery did not complete");
		});
	}

	private static void testDeleteFailure() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(3);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles normalFiles = new TakeoverJournalFiles(root);
			begin(normalFiles, journal, world, TakeoverTransaction.FailureInjector.NONE);
			AtomicBoolean failDelete = new AtomicBoolean(true);
			TakeoverJournalFiles failingFiles = new TakeoverJournalFiles(
					root,
					new TakeoverJournalCodec(),
					(source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE),
					path -> {
						if (failDelete.get() && path.getFileName().toString().equals(TakeoverJournalFiles.JOURNAL_FILE_NAME)) {
							throw new IOException("injected journal delete failure");
						}
						return Files.deleteIfExists(path);
					}
			);
			TakeoverTransaction.RecoveryException failure = expectRecoveryException(
					() -> recover(failingFiles, world, TakeoverTransaction.FailureInjector.NONE)
			);
			check(failure.worldPassCompleted(), "delete failure was not distinguished from restore failure");
			check(world.allOriginal() && failingFiles.hasJournal(), "delete failure discarded the recovery journal");
			expectIOException(() -> begin(normalFiles, journal, world, TakeoverTransaction.FailureInjector.NONE));
			failDelete.set(false);
			TakeoverTransaction.RecoveryResult retry = recover(failingFiles, world, TakeoverTransaction.FailureInjector.NONE);
			check(retry.alreadyRestoredBlocks() == 3 && !failingFiles.isLocked(), "delete retry did not finish idempotently");
		});
	}

	private static void testWorldRestoreFailure() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(3);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE);
			world.failRestoreCall = 2;
			TakeoverTransaction.RecoveryException failure = expectRecoveryException(
					() -> recover(files, world, TakeoverTransaction.FailureInjector.NONE)
			);
			check(!failure.worldPassCompleted() && files.isLocked(), "world restore failure removed the lock");
			expectIOException(() -> begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE));
			world.failRestoreCall = 0;
			world.restoreCalls = 0;
			recover(files, world, TakeoverTransaction.FailureInjector.NONE);
			check(world.allOriginal() && !files.isLocked(), "world restore retry did not complete");
		});
	}

	private static void testOccupiedBlockSkip() throws Exception {
		withTemporaryWorld(root -> {
			TakeoverJournal journal = journal(3);
			FakeWorld world = new FakeWorld(journal);
			TakeoverJournalFiles files = new TakeoverJournalFiles(root);
			begin(files, journal, world, TakeoverTransaction.FailureInjector.NONE);
			long occupiedPosition = journal.blocks().get(1).packedPosition();
			world.current.put(occupiedPosition, "PLAYER_SOLID");
			TakeoverTransaction.RecoveryResult recovery = recover(files, world, TakeoverTransaction.FailureInjector.NONE);
			check(recovery.skippedBlocks() == 1, "occupied block was not reported as skipped");
			check("PLAYER_SOLID".equals(world.current.get(occupiedPosition)), "occupied player block was overwritten");
			check(!files.isLocked(), "completed occupied-block recovery retained its journal");
		});
	}

	private static TakeoverTransaction.BeginResult begin(
			TakeoverJournalFiles files,
			TakeoverJournal journal,
			FakeWorld world,
			TakeoverTransaction.FailureInjector failures
	) throws IOException {
		return TakeoverTransaction.begin(files, journal, "test warning", world, failures);
	}

	private static TakeoverTransaction.RecoveryResult recover(
			TakeoverJournalFiles files,
			FakeWorld world,
			TakeoverTransaction.FailureInjector failures
	) throws IOException {
		return TakeoverTransaction.recover(files, world, failures);
	}

	private static TakeoverTransaction.FailureInjector failAt(
			TakeoverTransaction.FailurePoint expectedPoint,
			int expectedIndex
	) {
		return (point, index) -> {
			if (point == expectedPoint && index == expectedIndex) {
				throw new IOException("Injected failure at " + point + (index == 0 ? "" : " #" + index));
			}
		};
	}

	private static TakeoverJournal journal(int blockCount) {
		List<TakeoverJournal.BlockEntry> blocks = new ArrayList<>();
		for (int i = 0; i < blockCount; i++) {
			blocks.add(new TakeoverJournal.BlockEntry(100L + i, state("water-" + i)));
		}
		return new TakeoverJournal(DIMENSION, blocks);
	}

	private static CompoundTag state(String testState) {
		CompoundTag state = new CompoundTag();
		state.putString("Name", "minecraft:water");
		state.putString("TestState", testState);
		return state;
	}

	private static String stateName(TakeoverJournal.BlockEntry block) {
		return block.state().getStringOr("TestState", "");
	}

	private static void assertJournalEquals(TakeoverJournal expected, TakeoverJournal actual) {
		check(expected.dimensionId().equals(actual.dimensionId()), "dimension did not round-trip");
		check(expected.blocks().size() == actual.blocks().size(), "block count did not round-trip");
		for (int i = 0; i < expected.blocks().size(); i++) {
			TakeoverJournal.BlockEntry expectedBlock = expected.blocks().get(i);
			TakeoverJournal.BlockEntry actualBlock = actual.blocks().get(i);
			check(expectedBlock.packedPosition() == actualBlock.packedPosition(), "position did not round-trip");
			check(expectedBlock.state().equals(actualBlock.state()), "state did not round-trip");
		}
	}

	private static TakeoverTransaction.RecoveryException expectRecoveryException(CheckedRunnable action) throws Exception {
		try {
			action.run();
		} catch (TakeoverTransaction.RecoveryException expected) {
			return expected;
		}
		throw new AssertionError("Expected TakeoverTransaction.RecoveryException");
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
		Path root = Files.createTempDirectory("realwater-takeover-test-").toAbsolutePath().normalize();
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

	private static final class FakeWorld implements TakeoverTransaction.WorldAccess {
		private final Map<Long, String> original = new HashMap<>();
		private final Map<Long, String> current = new HashMap<>();
		private boolean dimensionLoaded = true;
		private int restoreCalls;
		private int failRestoreCall;

		private FakeWorld(TakeoverJournal journal) {
			for (TakeoverJournal.BlockEntry block : journal.blocks()) {
				String state = stateName(block);
				original.put(block.packedPosition(), state);
				current.put(block.packedPosition(), state);
			}
		}

		@Override
		public boolean isDimensionLoaded(String dimensionId) {
			return dimensionLoaded && DIMENSION.equals(dimensionId);
		}

		@Override
		public boolean remove(String dimensionId, TakeoverJournal.BlockEntry block) throws IOException {
			checkDimension(dimensionId);
			long position = block.packedPosition();
			if (!original.get(position).equals(current.get(position))) return false;
			current.put(position, "AIR");
			return true;
		}

		@Override
		public TakeoverTransaction.RestoreOutcome restore(
				String dimensionId,
				TakeoverJournal.BlockEntry block
		) throws IOException {
			checkDimension(dimensionId);
			restoreCalls++;
			if (failRestoreCall > 0 && restoreCalls == failRestoreCall) {
				throw new IOException("injected world restore failure");
			}
			long position = block.packedPosition();
			String originalState = original.get(position);
			String currentState = current.get(position);
			if (originalState.equals(currentState)) return TakeoverTransaction.RestoreOutcome.ALREADY_RESTORED;
			if (!"AIR".equals(currentState) && !currentState.startsWith("water-")) {
				return TakeoverTransaction.RestoreOutcome.SKIPPED_OCCUPIED;
			}
			current.put(position, originalState);
			return TakeoverTransaction.RestoreOutcome.RESTORED;
		}

		private void checkDimension(String dimensionId) throws IOException {
			if (!dimensionLoaded || !DIMENSION.equals(dimensionId)) {
				throw new IOException("dimension is not loaded");
			}
		}

		private int airCount() {
			return (int) current.values().stream().filter("AIR"::equals).count();
		}

		private boolean allOriginal() {
			return original.equals(current);
		}
	}
}
