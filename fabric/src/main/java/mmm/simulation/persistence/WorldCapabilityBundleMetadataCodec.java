package mmm.simulation.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/** Strict NBT codec for bundle manifest and sole-commit-point HEAD metadata. */
public final class WorldCapabilityBundleMetadataCodec {
	public static final long MAX_MANIFEST_BYTES = 256L * 1024L;
	public static final long MAX_HEAD_BYTES = 64L * 1024L;

	private WorldCapabilityBundleMetadataCodec() { }

	public static void writeManifest(Path path, WorldCapabilityBundleManifest manifest) throws IOException {
		if (path == null || manifest == null) throw new IllegalArgumentException("Bundle manifest write is incomplete");
		NbtIo.writeCompressed(encodeManifest(manifest), path);
	}

	public static WorldCapabilityBundleManifest readManifest(Path path) throws IOException {
		return decodeManifest(read(path, MAX_MANIFEST_BYTES, "bundle manifest"));
	}

	public static void writeHead(Path path, WorldCapabilityBundleHead head) throws IOException {
		if (path == null || head == null) throw new IllegalArgumentException("Bundle HEAD write is incomplete");
		NbtIo.writeCompressed(encodeHead(head), path);
	}

	public static WorldCapabilityBundleHead readHead(Path path) throws IOException {
		return decodeHead(read(path, MAX_HEAD_BYTES, "bundle HEAD"));
	}

	public static CompoundTag encodeManifest(WorldCapabilityBundleManifest manifest) {
		if (manifest == null) throw new IllegalArgumentException("Bundle manifest is missing");
		CompoundTag root = new CompoundTag();
		root.putString("Schema", manifest.schema());
		root.putLong("Generation", manifest.generation());
		root.putString("PublicationTransaction", manifest.publicationTransactionId().toString());
		root.putString("ProfileFingerprint", manifest.profileFingerprint());
		root.putString("AggregateFingerprint", manifest.aggregateFingerprint());
		ListTag files = new ListTag();
		for (WorldCapabilityBundleManifest.FileEntry entry : manifest.files()) {
			CompoundTag encoded = new CompoundTag();
			encoded.putString("Name", entry.name());
			encoded.putLong("ByteLength", entry.byteLength());
			encoded.putString("Checksum", entry.checksum());
			files.add(encoded);
		}
		root.put("Files", files);
		return root;
	}

	public static WorldCapabilityBundleManifest decodeManifest(CompoundTag root) throws IOException {
		StrictNbtFields.requireExact(root, Map.of(
				"Schema", Tag.TAG_STRING,
				"Generation", Tag.TAG_LONG,
				"PublicationTransaction", Tag.TAG_STRING,
				"ProfileFingerprint", Tag.TAG_STRING,
				"AggregateFingerprint", Tag.TAG_STRING,
				"Files", Tag.TAG_LIST), "world capability bundle manifest");
		ListTag encodedFiles = root.getListOrEmpty("Files");
		if (encodedFiles.size() != WorldCapabilityBundleManifest.REQUIRED_FILES.size()) {
			throw new IOException("Bundle manifest file count is not exact");
		}
		ArrayList<WorldCapabilityBundleManifest.FileEntry> files = new ArrayList<>(encodedFiles.size());
		String previousName = null;
		try {
			for (int index = 0; index < encodedFiles.size(); index++) {
				Tag value = encodedFiles.get(index);
				if (!(value instanceof CompoundTag entry)) throw new IOException("Invalid bundle manifest file entry");
				StrictNbtFields.requireExact(entry, Map.of("Name", Tag.TAG_STRING,
						"ByteLength", Tag.TAG_LONG, "Checksum", Tag.TAG_STRING), "bundle manifest file entry");
				String name = entry.getStringOr("Name", "");
				if (previousName != null && previousName.compareTo(name) >= 0) {
					throw new IOException("Bundle manifest files are not in canonical order");
				}
				files.add(new WorldCapabilityBundleManifest.FileEntry(name,
						entry.getLongOr("ByteLength", -1L), entry.getStringOr("Checksum", "")));
				previousName = name;
			}
			return new WorldCapabilityBundleManifest(root.getStringOr("Schema", ""),
					root.getLongOr("Generation", -1L), parseUuid(root.getStringOr("PublicationTransaction", "")),
					root.getStringOr("ProfileFingerprint", ""), root.getStringOr("AggregateFingerprint", ""), files);
		} catch (IllegalArgumentException | ArithmeticException invalid) {
			throw new IOException("Invalid bundle manifest: " + invalid.getMessage(), invalid);
		}
	}

	public static CompoundTag encodeHead(WorldCapabilityBundleHead head) {
		if (head == null) throw new IllegalArgumentException("Bundle HEAD is missing");
		CompoundTag root = new CompoundTag();
		root.putString("Schema", head.schema());
		root.putLong("Generation", head.generation());
		root.putString("PublicationTransaction", head.publicationTransactionId().toString());
		root.putString("ProfileFingerprint", head.profileFingerprint());
		root.putString("AggregateFingerprint", head.aggregateFingerprint());
		root.putString("ManifestChecksum", head.manifestChecksum());
		return root;
	}

	public static WorldCapabilityBundleHead decodeHead(CompoundTag root) throws IOException {
		StrictNbtFields.requireExact(root, Map.of(
				"Schema", Tag.TAG_STRING,
				"Generation", Tag.TAG_LONG,
				"PublicationTransaction", Tag.TAG_STRING,
				"ProfileFingerprint", Tag.TAG_STRING,
				"AggregateFingerprint", Tag.TAG_STRING,
				"ManifestChecksum", Tag.TAG_STRING), "world capability bundle HEAD");
		try {
			return new WorldCapabilityBundleHead(root.getStringOr("Schema", ""),
					root.getLongOr("Generation", -1L), parseUuid(root.getStringOr("PublicationTransaction", "")),
					root.getStringOr("ProfileFingerprint", ""), root.getStringOr("AggregateFingerprint", ""),
					root.getStringOr("ManifestChecksum", ""));
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid bundle HEAD: " + invalid.getMessage(), invalid);
		}
	}

	private static CompoundTag read(Path path, long maximumBytes, String context) throws IOException {
		if (path == null) throw new IllegalArgumentException(context + " path is missing");
		try { return NbtIo.readCompressed(path, NbtAccounter.create(maximumBytes)); }
		catch (RuntimeException invalid) { throw new IOException("Unreadable " + context + " " + path, invalid); }
	}

	private static UUID parseUuid(String text) {
		UUID parsed = UUID.fromString(text);
		if (!parsed.toString().equals(text)) throw new IllegalArgumentException("Non-canonical publication transaction");
		return parsed;
	}
}
