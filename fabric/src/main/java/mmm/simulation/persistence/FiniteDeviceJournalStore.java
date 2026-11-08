package mmm.simulation.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mmm.recovery.CrossDomainRecovery;
import mmm.thermal.FiniteThermalDeviceItemCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Device payloads for the common HEAD recovery decision; body ownership changes only through HEAD. */
public final class FiniteDeviceJournalStore {
	public record Journal(UUID operationId, UUID regionId, String dimensionId, long position,
			long baseGeneration, long targetGeneration, long targetChecksum,
			CrossDomainRecovery.JournalPhase phase, boolean placing, boolean drop,
			UUID playerId, boolean offHand, UUID dropId, String sourceContents, String bodyContents, String profile) {
		public Journal {
			if (operationId == null || regionId == null || playerId == null || dropId == null
					|| dimensionId == null || phase == null || targetGeneration != baseGeneration + 1
					|| baseGeneration < 0 || sourceContents == null || bodyContents == null
					|| profile == null || !profile.matches("[0-9a-f]{64}") || drop && placing
					|| phase == CrossDomainRecovery.JournalPhase.REGION_COMMITTED && targetChecksum < 0)
				throw new IllegalArgumentException("Invalid finite device transition");
			FiniteThermalDeviceItemCodec.decode(bodyContents);
			if (!sourceContents.isEmpty()) FiniteThermalDeviceItemCodec.decode(sourceContents);
		}
		public Journal committed(long checksum) {
			return new Journal(operationId, regionId, dimensionId, position, baseGeneration, targetGeneration,
					checksum, CrossDomainRecovery.JournalPhase.REGION_COMMITTED, placing, drop, playerId,
					offHand, dropId, sourceContents, bodyContents, profile);
		}
	}
	private final Path directory;
	public FiniteDeviceJournalStore(Path worldRoot) { directory = worldRoot.resolve("mmm").resolve("device-transfer"); }
	public List<Journal> discover() throws IOException {
		if (!Files.exists(directory)) return List.of();
		List<Journal> result = new ArrayList<>();
		try (var paths = Files.list(directory)) {
			for (var path : paths.sorted().toList()) {
				String name = path.getFileName().toString();
				// Only the atomically published WAL can precede a HEAD commit.
				if (Files.isRegularFile(path) && name.endsWith(".nbt.tmp")) continue;
				if (!Files.isRegularFile(path) || !name.endsWith(".nbt"))
					throw new IOException("Unrecognized finite-device recovery evidence: " + path);
				Journal journal = read(path);
				if (!name.equals(journal.operationId() + ".nbt")) throw new IOException("Finite device WAL identity differs from filename");
				result.add(journal);
			}
		}
		return List.copyOf(result);
	}
	public void write(Journal journal) throws IOException {
		boolean created = !Files.exists(directory);
		Files.createDirectories(directory);
		if (created) DurableFileOperations.forceParent(directory);
		Path target = directory.resolve(journal.operationId() + ".nbt"), temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Journal previous = Files.exists(target) ? read(target) : null;
		if (previous != null && !previous.equals(journal)
				&& !(previous.phase() == CrossDomainRecovery.JournalPhase.PREPARED
				&& journal.phase() == CrossDomainRecovery.JournalPhase.REGION_COMMITTED
				&& previous.committed(journal.targetChecksum()).equals(journal)))
			throw new IOException("Finite device WAL replacement differs from its prepared identity");
		NbtIo.writeCompressed(encode(journal), temporary);
		try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
		if (!read(temporary).equals(journal)) throw new IOException("Finite device WAL readback differs from prepared state");
		if (previous != null && !read(target).equals(previous)) throw new IOException("Finite device WAL changed before replacement");
		DurableFileOperations.atomicMoveReplacing(temporary, target);
		DurableFileOperations.forceDirectory(directory);
		if (!read(target).equals(journal)) throw new IOException("Finite device published WAL readback differs");
	}
	public void delete(UUID id) throws IOException {
		Files.deleteIfExists(directory.resolve(id + ".nbt"));
		DurableFileOperations.forceDirectory(directory);
	}
	private Journal read(Path file) throws IOException {
		try {
			var tag = NbtIo.readCompressed(file, NbtAccounter.create(1 << 20));
			return new Journal(UUID.fromString(tag.getStringOr("OperationId", "")), UUID.fromString(tag.getStringOr("RegionId", "")),
					tag.getStringOr("Dimension", ""), tag.getLongOr("Position", 0), tag.getLongOr("Base", -1),
					tag.getLongOr("Target", -1), tag.getLongOr("Checksum", -1),
					CrossDomainRecovery.JournalPhase.valueOf(tag.getStringOr("Phase", "")),
					tag.getBooleanOr("Placing", false), tag.getBooleanOr("Drop", false),
					UUID.fromString(tag.getStringOr("Player", "")), tag.getBooleanOr("OffHand", false),
					UUID.fromString(tag.getStringOr("DropId", "")), tag.getStringOr("Source", ""),
					tag.getStringOr("Body", ""), tag.getStringOr("Profile", ""));
		} catch (IllegalArgumentException invalid) { throw new IOException("Invalid finite device WAL", invalid); }
	}
	private CompoundTag encode(Journal journal) {
		var tag = new CompoundTag();
		tag.putString("OperationId", journal.operationId().toString()); tag.putString("RegionId", journal.regionId().toString());
		tag.putString("Dimension", journal.dimensionId()); tag.putLong("Position", journal.position());
		tag.putLong("Base", journal.baseGeneration()); tag.putLong("Target", journal.targetGeneration());
		tag.putLong("Checksum", journal.targetChecksum()); tag.putString("Phase", journal.phase().name());
		tag.putBoolean("Placing", journal.placing()); tag.putBoolean("Drop", journal.drop());
		tag.putString("Player", journal.playerId().toString()); tag.putBoolean("OffHand", journal.offHand());
		tag.putString("DropId", journal.dropId().toString()); tag.putString("Source", journal.sourceContents());
		tag.putString("Body", journal.bodyContents()); tag.putString("Profile", journal.profile());
		return tag;
	}
}
