package mmm.simulation.persistence;

import mmm.application.ContainerRecoveryCoordinator;
import mmm.transfer.ContainerTransferJournal;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Durable file operations for short-lived container journals; interpretation belongs to recovery. */
public final class ContainerJournalFileStore implements ContainerRecoveryCoordinator.JournalStore {
	private static final String ROOT_DIRECTORY_NAME = "container-transfer";
	private static final long MAX_COMPRESSED_NBT_BYTES = 4L * 1024L * 1024L;
	private static final String TEMP_SUFFIX = ".tmp";

	private final Path directory;

	public ContainerJournalFileStore(Path worldRoot) {
		this.directory = worldRoot.toAbsolutePath().normalize().resolve("mmm").resolve(ROOT_DIRECTORY_NAME);
	}

	/** Strict discovery preserves every unknown, staged, corrupt, or mismatched piece of evidence. */
	public Scan discover() throws IOException {
		if (!Files.exists(directory)) return new Scan(List.of(), List.of());
		if (!Files.isDirectory(directory)) {
			return new Scan(List.of(), List.of("container-transfer root is not a directory: " + directory));
		}
		List<UUID> ids = new ArrayList<>();
		List<String> issues = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (!Files.isRegularFile(entry) || !name.endsWith(".nbt")) {
					issues.add("unknown container-transfer content is preserved: " + entry);
					continue;
				}
				UUID id;
				try {
					id = UUID.fromString(name.substring(0, name.length() - 4));
				} catch (IllegalArgumentException invalidName) {
					issues.add("unknown container-transfer WAL name is preserved: " + entry);
					continue;
				}
				try {
					ContainerTransferJournal journal = read(id);
					if (!journal.transactionId().equals(id)) {
						issues.add("container-transfer WAL filename/payload mismatch: " + entry);
						continue;
					}
					ids.add(id);
				} catch (IOException | RuntimeException invalidWal) {
					issues.add("unreadable container-transfer WAL is preserved: " + entry
							+ " (" + invalidWal.getMessage() + ")");
				}
			}
		}
		ids.sort(Comparator.comparing(UUID::toString));
		issues.sort(String::compareTo);
		return new Scan(List.copyOf(ids), List.copyOf(issues));
	}

	public ContainerTransferJournal read(UUID transactionId) throws IOException {
		Path path = path(transactionId);
		if (!Files.exists(path)) throw new IOException("Container transfer WAL does not exist at " + path);
		return ContainerJournalCodec.decode(
				NbtIo.readCompressed(path, NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES)));
	}

	@Override
	public void write(ContainerTransferJournal journal) throws IOException {
		assertHealthyLayout();
		boolean directoryCreated = !Files.exists(directory);
		Files.createDirectories(directory);
		if (directoryCreated) DurableFileOperations.forceParent(directory);
		Path path = path(journal.transactionId());
		Path temporary = path.resolveSibling(path.getFileName() + TEMP_SUFFIX);
		deleteIfExists(temporary);
		try {
			NbtIo.writeCompressed(ContainerJournalCodec.encode(journal), temporary);
			forceFile(temporary);
			ContainerTransferJournal reread = ContainerJournalCodec.decode(
					NbtIo.readCompressed(temporary, NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES)));
			if (!reread.equals(journal)) {
				throw new IOException("Staged container transfer WAL did not read back identically");
			}
			moveWithRetry(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			if (!Files.exists(path)) throw new IOException("Atomic publisher did not create " + path);
			DurableFileOperations.forceDirectory(directory);
		} catch (IOException failure) {
			try {
				deleteIfExists(temporary);
				DurableFileOperations.forceDirectory(directory);
			} catch (IOException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	@Override
	public void delete(UUID transactionId) throws IOException {
		assertHealthyLayout();
		boolean changed = deleteIfExists(path(transactionId));
		Path temporary = path(transactionId).resolveSibling(path(transactionId).getFileName() + TEMP_SUFFIX);
		changed |= deleteIfExists(temporary);
		if (changed) DurableFileOperations.forceDirectory(directory);
	}

	private void assertHealthyLayout() throws IOException {
		Scan scan = discover();
		if (!scan.issues().isEmpty()) {
			throw new IOException("Container-transfer store is locked: " + String.join("; ", scan.issues()));
		}
	}

	public Path directoryPath() { return directory; }

	private Path path(UUID transactionId) { return directory.resolve(transactionId + ".nbt"); }

	private static boolean deleteIfExists(Path path) throws IOException { return Files.deleteIfExists(path); }

	private static void forceFile(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
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

	public record Scan(List<UUID> transactions, List<String> issues) {
		public boolean hasAny() { return !transactions.isEmpty() || !issues.isEmpty(); }
	}
}
