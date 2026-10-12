package mmm.simulation.persistence;

import mmm.model.region.RegionManifest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Strict HEAD codec and file access. Publication order remains owned by RegionGenerationStore. */
final class RegionHeadStore {
	static final String HEAD = "HEAD.nbt";
	static final String SLOT_A = "slot-a";
	static final String SLOT_B = "slot-b";
	private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;

	private final RegionGenerationStore.AtomicMover atomicMover;

	RegionHeadStore(RegionGenerationStore.AtomicMover atomicMover) {
		this.atomicMover = atomicMover;
	}

	Head read(Path region, UUID expectedId) throws IOException {
		if (!Files.isDirectory(region)) throw new IOException("Missing region-state directory " + region);
		return decode(readNbt(region.resolve(HEAD)), expectedId);
	}

	Head readTemporary(Path path, UUID expectedId) throws IOException {
		return decode(readNbt(path), expectedId);
	}

	Path writeTemporary(Path region, Head head) throws IOException {
		Path temporary = region.resolve(HEAD + ".tmp");
		NbtIo.writeCompressed(encode(head), temporary);
		DurableFileOperations.forceFile(temporary);
		decode(NbtIo.readCompressed(temporary, NbtAccounter.create(MAX_NBT_BYTES)), head.id());
		return temporary;
	}

	void publishTemporary(Path region, Path temporary) throws IOException {
		atomicMover.move(temporary, region.resolve(HEAD), true);
		DurableFileOperations.forceDirectory(region);
	}

	static Head active(UUID id, long generation, String slot, long manifestChecksum) {
		return new Head(id, generation, HeadKind.ACTIVE, slot, manifestChecksum);
	}

	static Head removed(UUID id, long generation, String slot, long manifestChecksum) {
		return new Head(id, generation, HeadKind.REMOVED, slot, manifestChecksum);
	}

	static void validateBinding(Head head, RegionManifest manifest) throws IOException {
		if (!head.id().equals(manifest.regionId())
				|| head.generation() != manifest.checkpointGeneration()
				|| head.manifestChecksum() != manifest.checksum()) {
			throw new IOException("HEAD does not bind the active manifest");
		}
	}

	private static CompoundTag encode(Head head) {
		CompoundTag tag = new CompoundTag();
		tag.putString("Schema", "region-head");
		tag.putLong("UuidMost", head.id().getMostSignificantBits());
		tag.putLong("UuidLeast", head.id().getLeastSignificantBits());
		tag.putLong("Generation", head.generation());
		tag.putString("Kind", head.kind().name());
		tag.putString("Slot", head.slot());
		tag.putLong("ManifestChecksum", head.manifestChecksum());
		tag.putLong("Checksum", checksum(head.id(), head.generation(), head.kind(), head.slot(), head.manifestChecksum()));
		return tag;
	}

	private static Head decode(CompoundTag tag, UUID expectedId) throws IOException {
		StrictNbtFields.requireExact(tag, Map.of(
				"Schema", Tag.TAG_STRING,
				"UuidMost", Tag.TAG_LONG,
				"UuidLeast", Tag.TAG_LONG,
				"Generation", Tag.TAG_LONG,
				"Kind", Tag.TAG_STRING,
				"Slot", Tag.TAG_STRING,
				"ManifestChecksum", Tag.TAG_LONG,
				"Checksum", Tag.TAG_LONG), "region-state HEAD");
		String schema = tag.getStringOr("Schema", "");
		UUID id = new UUID(tag.getLongOr("UuidMost", 0L), tag.getLongOr("UuidLeast", 0L));
		long generation = tag.getLongOr("Generation", -1L);
		long manifestChecksum = tag.getLongOr("ManifestChecksum", -1L);
		String slot = tag.getStringOr("Slot", "");
		HeadKind kind;
		try {
			kind = HeadKind.valueOf(tag.getStringOr("Kind", ""));
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid region-state HEAD kind for " + expectedId);
		}
		if (!schema.equals("region-head") || !id.equals(expectedId) || generation <= 0L
				|| (!slot.equals(SLOT_A) && !slot.equals(SLOT_B))) {
			throw new IOException("Invalid region-state HEAD for " + expectedId);
		}
		if (tag.getLongOr("Checksum", -1L) != checksum(id, generation, kind, slot, manifestChecksum)) {
			throw new IOException("Region-state HEAD checksum mismatch");
		}
		return new Head(id, generation, kind, slot, manifestChecksum);
	}

	private static long checksum(UUID id, long generation, HeadKind kind, String slot, long manifestChecksum) {
		CRC32C crc = new CRC32C();
		updateLong(crc, id.getMostSignificantBits());
		updateLong(crc, id.getLeastSignificantBits());
		updateLong(crc, generation);
		crc.update(kind.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		crc.update(slot.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		updateLong(crc, manifestChecksum);
		return crc.getValue();
	}

	private static void updateLong(CRC32C crc, long value) {
		for (int shift = 56; shift >= 0; shift -= 8) crc.update((int) (value >>> shift) & 0xff);
	}

	private static CompoundTag readNbt(Path path) throws IOException {
		if (!Files.isRegularFile(path)) throw new IOException("Missing region-state file " + path);
		return NbtIo.readCompressed(path, NbtAccounter.create(MAX_NBT_BYTES));
	}

	enum HeadKind { ACTIVE, REMOVED }

	record Head(UUID id, long generation, HeadKind kind, String slot, long manifestChecksum) { }
}
