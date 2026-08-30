package cc.ecisr.mmm.simulation.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Strict REMOVED tombstone codec and slot access; it never publishes HEAD. */
final class RemovedGenerationStore {
	static final String REMOVED = "removed.nbt";
	private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;

	RemovedRegionManifest create(
			UUID id,
			String capabilityProfileFingerprint,
			String registryAggregateFingerprint,
			long baseGeneration,
			long targetGeneration,
			long oldManifestChecksum,
			long networkTombstoneGeneration,
			UUID quarantineTransactionId
	) {
		RemovedRegionManifest unsigned = new RemovedRegionManifest(id, capabilityProfileFingerprint,
				registryAggregateFingerprint, baseGeneration, targetGeneration, oldManifestChecksum,
				networkTombstoneGeneration, quarantineTransactionId,
				RemovedRegionManifest.ZERO_INVENTORY_DIGEST, 0L);
		return withChecksum(unsigned);
	}

	void writeSlot(Path slot, RemovedRegionManifest removed) throws IOException {
		Path removedPath = slot.resolve(REMOVED);
		NbtIo.writeCompressed(encode(removed), removedPath);
		DurableFileOperations.forceFile(removedPath);
		DurableFileOperations.forceDirectory(slot);
	}

	RemovedRegionManifest readGeneration(Path region, RegionHeadStore.Head head) throws IOException {
		if (head.kind() != RegionHeadStore.HeadKind.REMOVED) {
			throw new IOException("Region HEAD is not REMOVED");
		}
		RemovedRegionManifest removed = readSlot(region.resolve(head.slot()), head.id());
		if (removed.targetGeneration() != head.generation() || removed.checksum() != head.manifestChecksum()) {
			throw new IOException("REMOVED HEAD does not bind its tombstone manifest");
		}
		return removed;
	}

	RemovedRegionManifest readSlot(Path slot, UUID expectedId) throws IOException {
		if (!Files.isDirectory(slot)) throw new IOException("Missing REMOVED tombstone slot");
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(slot)) {
			int count = 0;
			for (Path entry : entries) {
				count++;
				if (!Files.isRegularFile(entry) || !entry.getFileName().toString().equals(REMOVED)) {
					throw new IOException("Unexpected REMOVED tombstone slot content");
				}
			}
			if (count != 1) throw new IOException("Incomplete REMOVED tombstone slot");
		}
		return decode(readNbt(slot.resolve(REMOVED)), expectedId);
	}

	CompoundTag encode(RemovedRegionManifest value) {
		CompoundTag tag = new CompoundTag();
		tag.putString("Schema", "removed-region");
		tag.putLong("UuidMost", value.regionId().getMostSignificantBits());
		tag.putLong("UuidLeast", value.regionId().getLeastSignificantBits());
		tag.putString("ProfileFingerprint", value.capabilityProfileFingerprint());
		tag.putString("RegistryAggregateFingerprint", value.registryAggregateFingerprint());
		tag.putLong("BaseGeneration", value.baseGeneration());
		tag.putLong("TargetGeneration", value.targetGeneration());
		tag.putLong("OldManifestChecksum", value.oldManifestChecksum());
		tag.putLong("NetworkTombstoneGeneration", value.networkTombstoneGeneration());
		tag.putLong("QuarantineMost", value.quarantineTransactionId().getMostSignificantBits());
		tag.putLong("QuarantineLeast", value.quarantineTransactionId().getLeastSignificantBits());
		tag.putLong("ZeroInventoryDigest", value.zeroInventoryDigest());
		tag.putLong("Checksum", value.checksum());
		return tag;
	}

	RemovedRegionManifest decode(CompoundTag tag, UUID expectedId) throws IOException {
		try {
			StrictNbtFields.requireExact(tag, Map.ofEntries(
					Map.entry("Schema", Tag.TAG_STRING),
					Map.entry("UuidMost", Tag.TAG_LONG),
					Map.entry("UuidLeast", Tag.TAG_LONG),
					Map.entry("ProfileFingerprint", Tag.TAG_STRING),
					Map.entry("RegistryAggregateFingerprint", Tag.TAG_STRING),
					Map.entry("BaseGeneration", Tag.TAG_LONG),
					Map.entry("TargetGeneration", Tag.TAG_LONG),
					Map.entry("OldManifestChecksum", Tag.TAG_LONG),
					Map.entry("NetworkTombstoneGeneration", Tag.TAG_LONG),
					Map.entry("QuarantineMost", Tag.TAG_LONG),
					Map.entry("QuarantineLeast", Tag.TAG_LONG),
					Map.entry("ZeroInventoryDigest", Tag.TAG_LONG),
					Map.entry("Checksum", Tag.TAG_LONG)), "REMOVED region manifest");
			if (!tag.getStringOr("Schema", "").equals("removed-region")) {
				throw new IOException("Unknown REMOVED manifest schema");
			}
			RemovedRegionManifest value = new RemovedRegionManifest(
					new UUID(tag.getLongOr("UuidMost", 0L), tag.getLongOr("UuidLeast", 0L)),
					tag.getStringOr("ProfileFingerprint", ""),
					tag.getStringOr("RegistryAggregateFingerprint", ""),
					tag.getLongOr("BaseGeneration", -1L),
					tag.getLongOr("TargetGeneration", -1L),
					tag.getLongOr("OldManifestChecksum", -1L),
					tag.getLongOr("NetworkTombstoneGeneration", -1L),
					new UUID(tag.getLongOr("QuarantineMost", 0L), tag.getLongOr("QuarantineLeast", 0L)),
					tag.getLongOr("ZeroInventoryDigest", -1L),
					tag.getLongOr("Checksum", -1L));
			if (!value.regionId().equals(expectedId) || value.checksum() != checksum(value)) {
				throw new IOException("REMOVED manifest checksum or identity mismatch");
			}
			return value;
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid REMOVED manifest", invalid);
		}
	}

	private static RemovedRegionManifest withChecksum(RemovedRegionManifest value) {
		return new RemovedRegionManifest(value.regionId(), value.capabilityProfileFingerprint(),
				value.registryAggregateFingerprint(), value.baseGeneration(), value.targetGeneration(),
				value.oldManifestChecksum(), value.networkTombstoneGeneration(), value.quarantineTransactionId(),
				value.zeroInventoryDigest(), checksum(value));
	}

	private static long checksum(RemovedRegionManifest value) {
		CRC32C crc = new CRC32C();
		updateLong(crc, value.regionId().getMostSignificantBits());
		updateLong(crc, value.regionId().getLeastSignificantBits());
		crc.update(value.capabilityProfileFingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		crc.update(value.registryAggregateFingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		updateLong(crc, value.baseGeneration());
		updateLong(crc, value.targetGeneration());
		updateLong(crc, value.oldManifestChecksum());
		updateLong(crc, value.networkTombstoneGeneration());
		updateLong(crc, value.quarantineTransactionId().getMostSignificantBits());
		updateLong(crc, value.quarantineTransactionId().getLeastSignificantBits());
		updateLong(crc, value.zeroInventoryDigest());
		return crc.getValue();
	}

	private static void updateLong(CRC32C crc, long value) {
		for (int shift = 56; shift >= 0; shift -= 8) crc.update((int) (value >>> shift) & 0xff);
	}

	private static CompoundTag readNbt(Path path) throws IOException {
		if (!Files.isRegularFile(path)) throw new IOException("Missing region-state file " + path);
		return NbtIo.readCompressed(path, NbtAccounter.create(MAX_NBT_BYTES));
	}

}
