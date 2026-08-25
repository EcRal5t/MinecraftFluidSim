package cc.ecisr.mmm.simulation.ownership;

import cc.ecisr.mmm.simulation.persistence.DurableFileOperations;
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
import java.util.Set;
import java.util.UUID;

/**
 * Narrow, injectable file boundary for one region's ownership store. The WAL and WARNING refuse
 * to overwrite; the committed registry is published with a read-back verification and may replace
 * a previous generation, but it is never the transaction commit point — region-state HEAD is.
 */
public final class OwnershipFiles {
	static final String ROOT_DIRECTORY_NAME = "ownership";
	static final String WAL_FILE_NAME = "wal.nbt";
	static final String WARNING_FILE_NAME = "WARNING.txt";
	static final String COMMITTED_FILE_NAME = "committed.nbt";
	private static final long MAX_COMPRESSED_NBT_BYTES = 16L * 1024L * 1024L;

	private final Path directory;
	private final OwnershipJournalCodec journalCodec;
	private final OwnershipCommittedCodec committedCodec;
	private final AtomicMover atomicMover;
	private final AtomicReplacer atomicReplacer;
	private final DeleteOperation deleteOperation;

	public OwnershipFiles(Path worldRoot, UUID regionId) {
		this(worldRoot.resolve("mmm").resolve(ROOT_DIRECTORY_NAME).resolve(regionId.toString()),
				new OwnershipJournalCodec(), new OwnershipCommittedCodec(),
				OwnershipFiles::atomicMove, OwnershipFiles::atomicMoveReplacing, Files::deleteIfExists);
	}

	OwnershipFiles(
			Path directory,
			OwnershipJournalCodec journalCodec,
			OwnershipCommittedCodec committedCodec,
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
	public void assertUnlockedForTransition() throws IOException {
		validateKnownLayout();
		if (hasWAL()) {
			throw new IOException("An unresolved ownership transition WAL already exists at " + walPath());
		}
		if (hasWarning()) {
			throw new IOException("An orphaned ownership warning exists at " + warningPath() + "; recovery must complete first");
		}
		if (hasCommitted()) {
			readCommitted();
		}
		deleteStaleTemporaryFiles();
	}

	void publishWAL(OwnershipJournal journal) throws IOException {
		if (hasWAL() || hasWarning()) {
			throw new IOException("Refusing to overwrite an unresolved ownership transition");
		}
		CompoundTag encoded = journalCodec.encode(journal);
		writeAtomically(walTemporaryPath(), walPath(), temporary -> NbtIo.writeCompressed(encoded, temporary));
	}

	OwnershipJournal readWAL() throws IOException {
		if (!hasWAL()) throw new IOException("Ownership WAL does not exist at " + walPath());
		CompoundTag root = NbtIo.readCompressed(walPath(), NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES));
		return journalCodec.decode(root);
	}

	void publishWarning(String warning) throws IOException {
		if (!hasWAL()) throw new IOException("Cannot publish the ownership warning before the WAL");
		if (hasWarning()) throw new IOException("Refusing to overwrite ownership warning " + warningPath());
		byte[] bytes = warning.getBytes(StandardCharsets.UTF_8);
		writeAtomically(warningTemporaryPath(), warningPath(), temporary -> Files.write(temporary, bytes));
	}

	/** Publishes the committed registry; written after the region HEAD commit, rebuildable from the WAL. */
	void publishCommitted(OwnershipCommitted state) throws IOException {
		CompoundTag encoded = committedCodec.encode(state);
		Files.createDirectories(directory);
		Path temporary = committedTemporaryPath();
		deleteOperation.deleteIfExists(temporary);
		try {
			NbtIo.writeCompressed(encoded, temporary);
			forceFile(temporary);
			OwnershipCommitted reread = committedCodec.decode(
					NbtIo.readCompressed(temporary, NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES)));
			if (!reread.equals(state)) {
				throw new IOException("Staged committed ownership did not read back identically");
			}
			atomicReplacer.moveReplacing(temporary, committedPath());
			if (!Files.exists(committedPath())) {
				throw new IOException("Atomic publisher did not create " + committedPath());
			}
			DurableFileOperations.forceDirectory(directory);
		} catch (IOException exception) {
			try {
				deleteOperation.deleteIfExists(temporary);
			} catch (IOException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
			}
			throw exception;
		}
	}

	public OwnershipCommitted readCommitted() throws IOException {
		if (!hasCommitted()) throw new IOException("Committed ownership does not exist at " + committedPath());
		CompoundTag root = NbtIo.readCompressed(committedPath(), NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES));
		return committedCodec.decode(root);
	}

	void deleteTransaction() throws IOException {
		deleteOperation.deleteIfExists(warningPath());
		deleteOperation.deleteIfExists(walPath());
		deleteStaleTemporaryFiles();
		DurableFileOperations.forceDirectory(directory);
	}

	void deleteStaleTemporaryFiles() throws IOException {
		deleteOperation.deleteIfExists(walTemporaryPath());
		deleteOperation.deleteIfExists(warningTemporaryPath());
		deleteOperation.deleteIfExists(committedTemporaryPath());
	}

	/** Removes the whole ownership directory after a completed release plus region removal. */
	public void deleteStore() throws IOException {
		if (!Files.exists(directory)) return;
		validateKnownLayout();
		try (var paths = Files.walk(directory)) {
			for (Path entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				deleteOperation.deleteIfExists(entry);
			}
		}
		DurableFileOperations.forceParent(directory);
	}

	public boolean hasWAL() {
		return Files.exists(walPath());
	}

	public boolean hasWarning() {
		return Files.exists(warningPath());
	}

	public boolean hasCommitted() {
		return Files.exists(committedPath());
	}

	public boolean exists() {
		return Files.exists(directory);
	}

	public boolean isLocked() {
		if (hasWAL() || hasWarning()) return true;
		try {
			validateKnownLayout();
			if (hasCommitted()) readCommitted();
			return false;
		} catch (IOException invalid) {
			return true;
		}
	}

	public String lockDescription() {
		if (hasWAL() && hasWarning()) return "未解决 ownership WAL 与 WARNING";
		if (hasWAL()) return "未解决或不可读 ownership WAL";
		if (hasWarning()) return "孤立 ownership WARNING（需人工核对恢复）";
		try {
			validateKnownLayout();
			if (hasCommitted()) readCommitted();
			return "无";
		} catch (IOException invalid) {
			return "未知、损坏或不可读 ownership 内容：" + invalid.getMessage();
		}
	}

	/** Refuses to ignore or delete future/foreign files inside this region's sidecar directory. */
	void validateKnownLayout() throws IOException {
		if (!Files.exists(directory)) return;
		if (!Files.isDirectory(directory)) {
			throw new IOException("Ownership path is not a directory: " + directory);
		}
		Set<String> known = Set.of(
				WAL_FILE_NAME, WARNING_FILE_NAME, COMMITTED_FILE_NAME,
				WAL_FILE_NAME + ".tmp", WARNING_FILE_NAME + ".tmp", COMMITTED_FILE_NAME + ".tmp");
		try (var entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) {
				if (!known.contains(entry.getFileName().toString()) || Files.isDirectory(entry)) {
					throw new IOException("Unknown ownership content is preserved at " + entry);
				}
			}
		}
	}

	Path directoryPath() {
		return directory;
	}

	Path walPath() {
		return directory.resolve(WAL_FILE_NAME);
	}

	Path warningPath() {
		return directory.resolve(WARNING_FILE_NAME);
	}

	Path committedPath() {
		return directory.resolve(COMMITTED_FILE_NAME);
	}

	Path walTemporaryPath() {
		return directory.resolve(WAL_FILE_NAME + ".tmp");
	}

	Path warningTemporaryPath() {
		return directory.resolve(WARNING_FILE_NAME + ".tmp");
	}

	Path committedTemporaryPath() {
		return directory.resolve(COMMITTED_FILE_NAME + ".tmp");
	}

	private void writeAtomically(Path temporary, Path target, TemporaryWriter writer) throws IOException {
		boolean directoryCreated = !Files.exists(directory);
		Files.createDirectories(directory);
		if (directoryCreated) DurableFileOperations.forceParent(directory);
		deleteOperation.deleteIfExists(temporary);
		try {
			writer.write(temporary);
			forceFile(temporary);
			if (Files.exists(target)) throw new IOException("Refusing to replace existing recovery file " + target);
			atomicMover.move(temporary, target);
			if (!Files.exists(target)) throw new IOException("Atomic publisher did not create " + target);
			DurableFileOperations.forceDirectory(directory);
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

	/** Windows antivirus/indexing can briefly hold handles on freshly written files. */
	private static void atomicMove(Path source, Path target) throws IOException {
		moveWithRetry(source, target, StandardCopyOption.ATOMIC_MOVE);
	}

	private static void atomicMoveReplacing(Path source, Path target) throws IOException {
		moveWithRetry(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void moveWithRetry(Path source, Path target, StandardCopyOption... options) throws IOException {
		IOException denied = null;
		for (int attempt = 0; attempt < 5; attempt++) {
			try {
				Files.move(source, target, options);
				return;
			} catch (java.nio.file.AccessDeniedException accessDenied) {
				denied = accessDenied;
				try {
					Thread.sleep(25L << attempt);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw accessDenied;
				}
			}
		}
		throw denied;
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
