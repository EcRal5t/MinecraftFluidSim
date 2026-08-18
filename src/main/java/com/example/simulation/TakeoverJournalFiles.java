package com.example.simulation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Narrow, injectable file boundary for atomic journal and warning publication. */
final class TakeoverJournalFiles {
	static final String JOURNAL_FILE_NAME = "realwater-takeover-backup.nbt";
	static final String WARNING_FILE_NAME = "realwater-takeover-WARNING.txt";
	private static final long MAX_COMPRESSED_NBT_BYTES = 16L * 1024L * 1024L;

	private final Path worldRoot;
	private final TakeoverJournalCodec codec;
	private final AtomicMover atomicMover;
	private final DeleteOperation deleteOperation;

	TakeoverJournalFiles(Path worldRoot) {
		this(worldRoot, new TakeoverJournalCodec(), TakeoverJournalFiles::atomicMove, Files::deleteIfExists);
	}

	TakeoverJournalFiles(
			Path worldRoot,
			TakeoverJournalCodec codec,
			AtomicMover atomicMover,
			DeleteOperation deleteOperation
	) {
		this.worldRoot = worldRoot.toAbsolutePath().normalize();
		this.codec = codec;
		this.atomicMover = atomicMover;
		this.deleteOperation = deleteOperation;
	}

	void assertUnlockedForTakeover() throws IOException {
		if (hasJournal()) {
			throw new IOException("An unresolved takeover journal already exists at " + journalPath());
		}
		if (hasWarning()) {
			throw new IOException("An orphaned takeover warning exists at " + warningPath() + "; verify recovery before removing it");
		}
		deleteStaleTemporaryFiles();
	}

	void publishJournal(TakeoverJournal journal) throws IOException {
		if (hasJournal() || hasWarning()) {
			throw new IOException("Refusing to overwrite published Real Water takeover recovery files");
		}
		CompoundTag encoded = codec.encode(journal);
		writeAtomically(journalTemporaryPath(), journalPath(), temporary -> NbtIo.writeCompressed(encoded, temporary));
	}

	TakeoverJournal readJournal() throws IOException {
		if (!hasJournal()) throw new IOException("Takeover journal does not exist at " + journalPath());
		CompoundTag root = NbtIo.readCompressed(journalPath(), NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES));
		return codec.decode(root);
	}

	void publishWarning(String warning) throws IOException {
		if (!hasJournal()) throw new IOException("Cannot publish takeover warning before the journal");
		if (hasWarning()) throw new IOException("Refusing to overwrite takeover warning " + warningPath());
		byte[] bytes = warning.getBytes(StandardCharsets.UTF_8);
		writeAtomically(warningTemporaryPath(), warningPath(), temporary -> Files.write(temporary, bytes));
	}

	void deleteCompletedTransaction() throws IOException {
		deleteOperation.deleteIfExists(warningPath());
		deleteOperation.deleteIfExists(journalPath());
		deleteStaleTemporaryFiles();
	}

	void deleteStaleTemporaryFiles() throws IOException {
		deleteOperation.deleteIfExists(journalTemporaryPath());
		deleteOperation.deleteIfExists(warningTemporaryPath());
	}

	boolean hasJournal() {
		return Files.exists(journalPath());
	}

	boolean hasWarning() {
		return Files.exists(warningPath());
	}

	boolean isLocked() {
		return hasJournal() || hasWarning();
	}

	String lockDescription() {
		if (hasJournal() && hasWarning()) return "未解决 takeover journal 与 WARNING";
		if (hasJournal()) return "未解决或不可读 takeover journal";
		if (hasWarning()) return "孤立 takeover WARNING（需人工核对恢复）";
		return "无";
	}

	Path journalPath() {
		return worldRoot.resolve(JOURNAL_FILE_NAME);
	}

	Path warningPath() {
		return worldRoot.resolve(WARNING_FILE_NAME);
	}

	Path journalTemporaryPath() {
		return worldRoot.resolve(JOURNAL_FILE_NAME + ".tmp");
	}

	Path warningTemporaryPath() {
		return worldRoot.resolve(WARNING_FILE_NAME + ".tmp");
	}

	private void writeAtomically(Path temporary, Path target, TemporaryWriter writer) throws IOException {
		Files.createDirectories(worldRoot);
		deleteOperation.deleteIfExists(temporary);
		try {
			writer.write(temporary);
			forceFile(temporary);
			if (Files.exists(target)) throw new IOException("Refusing to replace existing recovery file " + target);
			atomicMover.move(temporary, target);
			if (!Files.exists(target)) throw new IOException("Atomic publisher did not create " + target);
		} catch (IOException exception) {
			try {
				deleteOperation.deleteIfExists(temporary);
			} catch (IOException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
			}
			throw exception;
		}
	}

	private static void forceFile(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
	}

	private static void atomicMove(Path source, Path target) throws IOException {
		Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
	}

	@FunctionalInterface
	interface AtomicMover {
		void move(Path source, Path target) throws IOException;
	}

	@FunctionalInterface
	interface DeleteOperation {
		boolean deleteIfExists(Path path) throws IOException;
	}

	@FunctionalInterface
	private interface TemporaryWriter {
		void write(Path path) throws IOException;
	}
}
