package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.simulation.persistence.DurableFileOperations;
import cc.ecisr.mmm.simulation.persistence.StrictNbtFields;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** World-authoritative immutable material registry snapshot with atomic first publication. */
public final class MaterialRegistryStore {
	private static final String SCHEMA = "material-registry";
	private static final long MAX_BYTES = 4L * 1_048_576L;
	private static final Set<String> KNOWN_FILES = Set.of("registry.nbt", "registry.nbt.tmp");
	private final Path directory;

	public MaterialRegistryStore(Path worldRoot) {
		if (worldRoot == null) throw new IllegalArgumentException("Material registry world root is missing");
		directory = worldRoot.toAbsolutePath().normalize().resolve("mmm").resolve("material");
	}

	public LoadResult loadOrCreate(MaterialRegistry definitions) throws IOException {
		if (definitions == null) throw new IllegalArgumentException("Material registry definitions are missing");
		validateLayout();
		Path target = registryPath();
		if (Files.exists(target)) {
			MaterialRegistry authoritative = readSnapshot(target);
			return new LoadResult(authoritative, !authoritative.fingerprint().equals(definitions.fingerprint()), false);
		}
		Path temporary = temporaryPath();
		DurableFileOperations.createDirectories(directory);
		if (Files.deleteIfExists(temporary)) DurableFileOperations.forceDirectory(directory);
		try {
			writeSnapshot(temporary, definitions);
			force(temporary);
			MaterialRegistry reread = readSnapshot(temporary);
			if (!reread.fingerprint().equals(definitions.fingerprint())) {
				throw new IOException("Staged material registry did not read back identically");
			}
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			DurableFileOperations.forceDirectory(directory);
			return new LoadResult(readSnapshot(target), false, true);
		} catch (IOException failure) {
			try {
				if (Files.deleteIfExists(temporary)) DurableFileOperations.forceDirectory(directory);
			} catch (IOException cleanup) {
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
	}

	public MaterialRegistry read() throws IOException {
		validateLayout();
		if (!exists()) throw new IOException("Material registry snapshot is missing at " + registryPath());
		return readSnapshot(registryPath());
	}

	public boolean exists() { return Files.exists(registryPath()); }

	public Path registryPath() { return directory.resolve("registry.nbt"); }

	private Path temporaryPath() { return directory.resolve("registry.nbt.tmp"); }

	public static void writeSnapshot(Path path, MaterialRegistry registry) throws IOException {
		if (path == null || registry == null) throw new IllegalArgumentException("Material registry snapshot write is incomplete");
		Files.write(path, snapshotBytes(registry));
	}

	public static byte[] snapshotBytes(MaterialRegistry registry) throws IOException {
		if (registry == null) throw new IllegalArgumentException("Material registry snapshot is missing");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		NbtIo.writeCompressed(encode(registry), output);
		return output.toByteArray();
	}

	public static MaterialRegistry readSnapshot(Path path) throws IOException {
		if (path == null) throw new IllegalArgumentException("Material registry snapshot path is missing");
		CompoundTag root;
		try {
			root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_BYTES));
		} catch (RuntimeException invalid) {
			throw new IOException("Unreadable material registry snapshot " + path, invalid);
		}
		requireAll(root, Map.of("Schema", Tag.TAG_STRING, "UnitSystem", Tag.TAG_STRING,
				"Fingerprint", Tag.TAG_STRING, "Canonical", Tag.TAG_BYTE_ARRAY,
				"DefaultMaterial", Tag.TAG_STRING, "Materials", Tag.TAG_LIST, "PairTensions", Tag.TAG_LIST));
		if (!root.getStringOr("Schema", "").equals(SCHEMA)
				|| !MaterialRegistry.UNIT_SYSTEM_ID.equals(root.getStringOr("UnitSystem", ""))) {
			throw new IOException("Unsupported material registry schema or unit system");
		}
		try {
			MaterialRegistry registry = new MaterialRegistry(root.getStringOr("DefaultMaterial", ""),
					decodeMaterials(root.getListOrEmpty("Materials")),
					decodePairs(root.getListOrEmpty("PairTensions")));
			if (!registry.fingerprint().equals(root.getStringOr("Fingerprint", ""))
					|| !java.util.Arrays.equals(registry.canonicalBytes(), root.getByteArray("Canonical").orElseThrow())) {
				throw new IOException("Material registry fingerprint/canonical bytes mismatch");
			}
			return registry;
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid material registry snapshot: " + invalid.getMessage(), invalid);
		}
	}

	private static CompoundTag encode(MaterialRegistry registry) {
		CompoundTag root = new CompoundTag();
		root.putString("Schema", SCHEMA);
		root.putString("UnitSystem", MaterialRegistry.UNIT_SYSTEM_ID);
		root.putString("Fingerprint", registry.fingerprint());
		root.putByteArray("Canonical", registry.canonicalBytes());
		root.putString("DefaultMaterial", registry.defaultMaterialId());
		ListTag materials = new ListTag();
		for (MaterialRegistry.MaterialEntry entry : registry.entries()) {
			CompoundTag tag = new CompoundTag();
			FluidMaterial material = entry.material();
			tag.putString("Id", material.id());
			tag.putLong("DensityBits", Double.doubleToRawLongBits(material.referenceDensity()));
			tag.putString("MiscibilityGroup", material.miscibilityGroup());
			tag.putLong("SurfaceTensionBits", Double.doubleToRawLongBits(material.surfaceTension()));
			tag.putLong("ContactAngleBits", Double.doubleToRawLongBits(material.contactAngleRadians()));
			ListTag viscosity = new ListTag();
			for (MaterialRegistry.ViscositySegment segment : entry.viscosity()) {
				CompoundTag value = new CompoundTag();
				value.putLong("MinimumBits", Double.doubleToRawLongBits(segment.minimumTemperatureK()));
				value.putLong("MaximumBits", Double.doubleToRawLongBits(segment.maximumTemperatureK()));
				value.putLong("MinimumViscosityBits", Double.doubleToRawLongBits(segment.dynamicViscosityPaPerSAtMinimum()));
				value.putLong("MaximumViscosityBits", Double.doubleToRawLongBits(segment.dynamicViscosityPaPerSAtMaximum()));
				viscosity.add(value);
			}
			tag.put("Viscosity", viscosity);
			materials.add(tag);
		}
		root.put("Materials", materials);
		ListTag pairs = new ListTag();
		for (MaterialRegistry.LiquidPairTension pair : registry.pairTensions()) {
			CompoundTag tag = new CompoundTag();
			tag.putString("FirstGroup", pair.firstMiscibilityGroup());
			tag.putString("SecondGroup", pair.secondMiscibilityGroup());
			tag.putLong("TensionBits", Double.doubleToRawLongBits(pair.tension()));
			pairs.add(tag);
		}
		root.put("PairTensions", pairs);
		return root;
	}

	private static List<MaterialRegistry.MaterialEntry> decodeMaterials(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > MaterialRegistry.MAX_MATERIALS) throw new IOException("Invalid material count");
		ArrayList<MaterialRegistry.MaterialEntry> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "material");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "DensityBits", Tag.TAG_LONG,
					"MiscibilityGroup", Tag.TAG_STRING, "SurfaceTensionBits", Tag.TAG_LONG,
					"ContactAngleBits", Tag.TAG_LONG, "Viscosity", Tag.TAG_LIST));
			ListTag encodedSegments = tag.getListOrEmpty("Viscosity");
			ArrayList<MaterialRegistry.ViscositySegment> segments = new ArrayList<>(encodedSegments.size());
			for (int segmentIndex = 0; segmentIndex < encodedSegments.size(); segmentIndex++) {
				CompoundTag value = compound(encodedSegments, segmentIndex, "viscosity segment");
				requireAll(value, Map.of("MinimumBits", Tag.TAG_LONG, "MaximumBits", Tag.TAG_LONG,
						"MinimumViscosityBits", Tag.TAG_LONG, "MaximumViscosityBits", Tag.TAG_LONG));
				segments.add(new MaterialRegistry.ViscositySegment(
						Double.longBitsToDouble(value.getLongOr("MinimumBits", 0L)),
						Double.longBitsToDouble(value.getLongOr("MaximumBits", 0L)),
						Double.longBitsToDouble(value.getLongOr("MinimumViscosityBits", 0L)),
						Double.longBitsToDouble(value.getLongOr("MaximumViscosityBits", 0L))));
			}
			result.add(new MaterialRegistry.MaterialEntry(new FluidMaterial(
					tag.getStringOr("Id", ""),
					Double.longBitsToDouble(tag.getLongOr("DensityBits", 0L)),
					tag.getStringOr("MiscibilityGroup", ""),
					Double.longBitsToDouble(tag.getLongOr("SurfaceTensionBits", 0L)),
					Double.longBitsToDouble(tag.getLongOr("ContactAngleBits", 0L))), segments));
		}
		return List.copyOf(result);
	}

	private static List<MaterialRegistry.LiquidPairTension> decodePairs(ListTag list) throws IOException {
		if (list.size() > MaterialRegistry.MAX_PAIR_TENSIONS) throw new IOException("Liquid pair tensions over budget");
		ArrayList<MaterialRegistry.LiquidPairTension> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "pair tension");
			requireAll(tag, Map.of("FirstGroup", Tag.TAG_STRING, "SecondGroup", Tag.TAG_STRING,
					"TensionBits", Tag.TAG_LONG));
			result.add(new MaterialRegistry.LiquidPairTension(tag.getStringOr("FirstGroup", ""),
					tag.getStringOr("SecondGroup", ""),
					Double.longBitsToDouble(tag.getLongOr("TensionBits", 0L))));
		}
		return List.copyOf(result);
	}

	private void validateLayout() throws IOException {
		if (!Files.exists(directory)) return;
		if (!Files.isDirectory(directory)) throw new IOException("Material registry path is not a directory: " + directory);
		try (var entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) {
				if (!KNOWN_FILES.contains(entry.getFileName().toString()) || Files.isDirectory(entry)) {
					throw new IOException("Unknown material registry content is preserved at " + entry);
				}
			}
		}
	}

	private static CompoundTag compound(ListTag list, int index, String context) throws IOException {
		Tag tag = list.get(index);
		if (!(tag instanceof CompoundTag parsed)) throw new IOException("Invalid material " + context + " entry");
		return parsed;
	}

	private static void requireAll(CompoundTag tag, Map<String, Byte> fields) throws IOException {
		StrictNbtFields.requireExact(tag, fields, "material registry");
	}

	private static void force(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
	}

	public record LoadResult(MaterialRegistry registry, boolean definitionMismatch, boolean created) { }
}
