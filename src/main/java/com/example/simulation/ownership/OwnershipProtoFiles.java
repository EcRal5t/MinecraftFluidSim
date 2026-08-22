package com.example.simulation.ownership;

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

/**
 * Narrow, injectable file boundary for the disposable M6.0 ownership prototype. The committed
 * registry file is the sole commit point and is published last, with a read-back verification
 * before it replaces the previous generation.
 */
final class OwnershipProtoFiles {
	static final String DIRECTORY_NAME = "ownership-proto";
	static final String JOURNAL_FILE_NAME = "journal.nbt";
	static final String WARNING_FILE_NAME = "WARNING.txt";
	static final String COMMITTED_FILE_NAME = "committed.nbt";
	private static final long MAX_COMPRESSED_NBT_BYTES = 16L * 1024L * 1024L;

	private final Path directory;
	private final OwnershipJournalProtoCodec journalCodec;
	private final OwnershipCommittedProtoCodec committedCodec;
	private final AtomicMover atomicMover;
	private final AtomicReplacer atomicReplacer;
	private final DeleteOperation deleteOperation;

	OwnershipProtoFiles(Path worldRoot) {
		this(worldRoot.resolve("realwater").resolve(DIRECTORY_NAME),
				new OwnershipJournalProtoCodec(), new OwnershipCommittedProtoCodec(),
				OwnershipProtoFiles::atomicMove, OwnershipProtoFiles::atomicMoveReplacing, Files::deleteIfExists);
	}

	OwnershipProtoFiles(
			Path directory,
			OwnershipJournalProtoCodec journalCodec,
			OwnershipCommittedProtoCodec committedCodec,
			AtomicMover atomicMover,
			AtomicReplacer atomicReplacer,
			DeleteOperation deleteOperation
	) {
		this.directory = directory.toAbsolutePath().normalize();
		this.journalCodec = journalCodec;
		this.committedCodec = committedCodec;
		this.atomicMover = atomicMover;
		this.atomicReplacer = atomicReplacer;
		this.deleteOperation = deleteOperation;
	}

	/** Any unresolved transition, orphaned warning, or unreadable committed state locks new transitions. */
	void assertUnlockedForTransition() throws IOException {
		if (hasJournal()) {
			throw new IOException("An unresolved ownership transition journal already exists at " + journalPath());
		}
		if (hasWarning()) {
			throw new IOException("An orphaned ownership warning exists at " + warningPath() + "; recovery must complete first");
		}
		if (hasCommitted()) {
			readCommitted();
		}
		deleteStaleTemporaryFiles();
	}

	void publishJournal(OwnershipJournalProto journal) throws IOException {
		if (hasJournal() || hasWarning()) {
			throw new IOException("Refusing to overwrite an unresolved ownership transition");
		}
		CompoundTag encoded = journalCodec.encode(journal);
		writeAtomically(journalTemporaryPath(), journalPath(), temporary -> NbtIo.writeCompressed(encoded, temporary));
	}

	OwnershipJournalProto readJournal() throws IOException {
		if (!hasJournal()) throw new IOException("Ownership journal does not exist at " + journalPath());
		CompoundTag root = NbtIo.readCompressed(journalPath(), NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES));
		return journalCodec.decode(root);
	}

	void publishWarning(String warning) throws IOException {
		if (!hasJournal()) throw new IOException("Cannot publish the ownership warning before the journal");
		if (hasWarning()) throw new IOException("Refusing to overwrite ownership warning " + warningPath());
		byte[] bytes = warning.getBytes(StandardCharsets.UTF_8);
		writeAtomically(warningTemporaryPath(), warningPath(), temporary -> Files.write(temporary, bytes));
	}

	/** Publishes the committed generation; this replacement is the transition's sole commit point. */
	void publishCommitted(OwnershipCommittedProto state) throws IOException {
		CompoundTag encoded = committedCodec.encode(state);
		Files.createDirectories(directory);
		Path temporary = committedTemporaryPath();
		deleteOperation.deleteIfExists(temporary);
		try {
			NbtIo.writeCompressed(encoded, temporary);
			forceFile(temporary);
			// Verify the staged generation decodes identically before it may become authoritative.
			OwnershipCommittedProto reread = committedCodec.decode(
					NbtIo.readCompressed(temporary, NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES)));
			if (!reread.equals(state)) {
				throw new IOException("Staged committed ownership did not read back identically");
			}
			atomicReplacer.moveReplacing(temporary, committedPath());
			if (!Files.exists(committedPath())) {
				throw new IOException("Atomic publisher did not create " + committedPath());
			}
		} catch (IOException exception) {
			try {
				deleteOperation.deleteIfExists(temporary);
			} catch (IOException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
			}
			throw exception;
		}
	}

	OwnershipCommittedProto readCommitted() throws IOException {
		if (!hasCommitted()) throw new IOException("Committed ownership does not exist at " + committedPath());
		CompoundTag root = NbtIo.readCompressed(committedPath(), NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES));
		return committedCodec.decode(root);
	}

	void deleteTransaction() throws IOException {
		deleteOperation.deleteIfExists(warningPath());
		deleteOperation.deleteIfExists(journalPath());
		deleteStaleTemporaryFiles();
	}

	void deleteStaleTemporaryFiles() throws IOException {
		deleteOperation.deleteIfExists(journalTemporaryPath());
		deleteOperation.deleteIfExists(warningTemporaryPath());
		deleteOperation.deleteIfExists(committedTemporaryPath());
	}

	boolean hasJournal() {
		return Files.exists(journalPath());
	}

	boolean hasWarning() {
		return Files.exists(warningPath());
	}

	boolean hasCommitted() {
		return Files.exists(committedPath());
	}

	boolean isLocked() {
		return hasJournal() || hasWarning();
	}

	String lockDescription() {
		if (hasJournal() && hasWarning()) return "未解决 ownership 原型 journal 与 WARNING";
		if (hasJournal()) return "未解决或不可读 ownership 原型 journal";
		if (hasWarning()) return "孤立 ownership 原型 WARNING（需人工核对恢复）";
		return "无";
	}

	Path directoryPath() {
		return directory;
	}

	Path journalPath() {
		return directory.resolve(JOURNAL_FILE_NAME);
	}

	Path warningPath() {
		return directory.resolve(WARNING_FILE_NAME);
	}

	Path committedPath() {
		return directory.resolve(COMMITTED_FILE_NAME);
	}

	Path journalTemporaryPath() {
		return directory.resolve(JOURNAL_FILE_NAME + ".tmp");
	}

	Path warningTemporaryPath() {
		return directory.resolve(WARNING_FILE_NAME + ".tmp");
	}

	Path committedTemporaryPath() {
		return directory.resolve(COMMITTED_FILE_NAME + ".tmp");
	}

	private void writeAtomically(Path temporary, Path target, TemporaryWriter writer) throws IOException {
		Files.createDirectories(directory);
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

	private static void atomicMoveReplacing(Path source, Path target) throws IOException {
		Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	@FunctionalInterface
	interface AtomicMover {
		void move(Path source, Path target) throws IOException;
	}

	@FunctionalInterface
	interface AtomicReplacer {
		void moveReplacing(Path source, Path target) throws IOException;
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
