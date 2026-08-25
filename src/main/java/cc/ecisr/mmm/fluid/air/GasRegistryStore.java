package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.chemistry.ExactRational;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

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

/** World-authoritative immutable gas component/ambient registry v1 with atomic first publication. */
public final class GasRegistryStore {
	private static final int FORMAT_VERSION = 1;
	private static final long MAX_BYTES = 4L * 1_048_576L;
	private static final Set<String> KNOWN_FILES = Set.of("registry.nbt", "registry.nbt.tmp");
	private final Path directory;

	public GasRegistryStore(Path worldRoot) {
		if (worldRoot == null) throw new IllegalArgumentException("Gas registry world root is missing");
		directory = worldRoot.toAbsolutePath().normalize().resolve("mmm").resolve("gas");
	}

	public LoadResult loadOrCreate(GasRegistry definitions) throws IOException {
		if (definitions == null) throw new IllegalArgumentException("Gas registry definitions are missing");
		validateLayout(); Path target = registryPath();
		if (Files.exists(target)) {
			GasRegistry authoritative = read(target);
			return new LoadResult(authoritative, !authoritative.fingerprint().equals(definitions.fingerprint()), false);
		}
		Path temporary = temporaryPath(); Files.createDirectories(directory); Files.deleteIfExists(temporary);
		try {
			NbtIo.writeCompressed(encode(definitions), temporary); force(temporary);
			GasRegistry reread = read(temporary);
			if (!reread.fingerprint().equals(definitions.fingerprint())) {
				throw new IOException("Staged gas registry did not read back identically");
			}
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			return new LoadResult(read(target), false, true);
		} catch (IOException failure) {
			try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
			throw failure;
		}
	}

	public GasRegistry read() throws IOException {
		validateLayout();
		if (!exists()) throw new IOException("Gas registry snapshot is missing at " + registryPath());
		return read(registryPath());
	}

	public boolean exists() { return Files.exists(registryPath()); }
	public Path registryPath() { return directory.resolve("registry.nbt"); }
	private Path temporaryPath() { return directory.resolve("registry.nbt.tmp"); }

	private GasRegistry read(Path path) throws IOException {
		CompoundTag root;
		try { root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_BYTES)); }
		catch (RuntimeException invalid) { throw new IOException("Unreadable gas registry snapshot " + path, invalid); }
		requireAll(root, Map.of("Version", Tag.TAG_INT, "UnitSystem", Tag.TAG_STRING,
				"StandardPressureBits", Tag.TAG_LONG, "StandardTemperatureBits", Tag.TAG_LONG,
				"Fingerprint", Tag.TAG_STRING, "Canonical", Tag.TAG_BYTE_ARRAY,
				"Components", Tag.TAG_LIST, "Ambient", Tag.TAG_LIST));
		if (root.getIntOr("Version", -1) != FORMAT_VERSION
				|| !GasRegistry.UNIT_SYSTEM_ID.equals(root.getStringOr("UnitSystem", ""))
				|| root.getLongOr("StandardPressureBits", 0L) != Double.doubleToRawLongBits(GasRegistry.STANDARD_PRESSURE_PA)
				|| root.getLongOr("StandardTemperatureBits", 0L) != Double.doubleToRawLongBits(GasRegistry.STANDARD_TEMPERATURE_K)) {
			throw new IOException("Unsupported gas registry version or standard state");
		}
		try {
			GasRegistry registry = new GasRegistry(decodeComponents(root.getListOrEmpty("Components")),
					decodeAmbient(root.getListOrEmpty("Ambient")));
			if (!registry.fingerprint().equals(root.getStringOr("Fingerprint", ""))
					|| !java.util.Arrays.equals(registry.canonicalBytes(), root.getByteArray("Canonical").orElseThrow())) {
				throw new IOException("Gas registry fingerprint/canonical bytes mismatch");
			}
			return registry;
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid gas registry snapshot: " + invalid.getMessage(), invalid);
		}
	}

	private static CompoundTag encode(GasRegistry registry) {
		CompoundTag root = new CompoundTag(); root.putInt("Version", FORMAT_VERSION);
		root.putString("UnitSystem", GasRegistry.UNIT_SYSTEM_ID);
		root.putLong("StandardPressureBits", Double.doubleToRawLongBits(GasRegistry.STANDARD_PRESSURE_PA));
		root.putLong("StandardTemperatureBits", Double.doubleToRawLongBits(GasRegistry.STANDARD_TEMPERATURE_K));
		root.putString("Fingerprint", registry.fingerprint()); root.putByteArray("Canonical", registry.canonicalBytes());
		ListTag components = new ListTag();
		for (GasComponentDefinition component : registry.components()) {
			CompoundTag tag = new CompoundTag(); tag.putString("Id", component.id());
			tag.putLong("MassDensityBits", Double.doubleToRawLongBits(component.standardMassPerCubicMeter()));
			tag.putLong("ResidualDensityBits", Double.doubleToRawLongBits(component.defaultMassResidualPerCubicMeter()));
			ListTag species = new ListTag();
			for (GasComponentDefinition.DefaultSpecies entry : component.defaultSpeciesPerCubicMeter()) {
				CompoundTag value = new CompoundTag(); value.putString("Species", entry.speciesId());
				value.putString("QuantaPerCubicMeter", entry.quantaPerCubicMeter().canonical()); species.add(value);
			}
			tag.put("Species", species); components.add(tag);
		}
		root.put("Components", components);
		ListTag ambient = new ListTag();
		for (GasRegistry.AmbientFraction fraction : registry.ambient()) {
			CompoundTag tag = new CompoundTag(); tag.putString("Component", fraction.componentId());
			tag.putString("Fraction", fraction.fraction().canonical()); ambient.add(tag);
		}
		root.put("Ambient", ambient); return root;
	}

	private static List<GasComponentDefinition> decodeComponents(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > GasLimits.MAX_COMPONENTS) throw new IOException("Invalid gas component count");
		ArrayList<GasComponentDefinition> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "component");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "MassDensityBits", Tag.TAG_LONG,
					"ResidualDensityBits", Tag.TAG_LONG, "Species", Tag.TAG_LIST));
			ListTag encodedSpecies = tag.getListOrEmpty("Species");
			if (encodedSpecies.size() > GasLimits.MAX_SPECIES_PER_COMPONENT) throw new IOException("Gas component species over budget");
			ArrayList<GasComponentDefinition.DefaultSpecies> species = new ArrayList<>(encodedSpecies.size());
			for (int entry = 0; entry < encodedSpecies.size(); entry++) {
				CompoundTag value = compound(encodedSpecies, entry, "component species");
				requireAll(value, Map.of("Species", Tag.TAG_STRING, "QuantaPerCubicMeter", Tag.TAG_STRING));
				species.add(new GasComponentDefinition.DefaultSpecies(value.getStringOr("Species", ""),
						ExactRational.parse(value.getStringOr("QuantaPerCubicMeter", ""))));
			}
			result.add(new GasComponentDefinition(tag.getStringOr("Id", ""),
					Double.longBitsToDouble(tag.getLongOr("MassDensityBits", 0L)),
					Double.longBitsToDouble(tag.getLongOr("ResidualDensityBits", 0L)), species));
		}
		return List.copyOf(result);
	}

	private static List<GasRegistry.AmbientFraction> decodeAmbient(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > GasLimits.MAX_COMPONENTS_PER_NODE) throw new IOException("Invalid ambient gas count");
		ArrayList<GasRegistry.AmbientFraction> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "ambient");
			requireAll(tag, Map.of("Component", Tag.TAG_STRING, "Fraction", Tag.TAG_STRING));
			result.add(new GasRegistry.AmbientFraction(tag.getStringOr("Component", ""),
					ExactRational.parse(tag.getStringOr("Fraction", ""))));
		}
		return List.copyOf(result);
	}

	private void validateLayout() throws IOException {
		if (!Files.exists(directory)) return;
		if (!Files.isDirectory(directory)) throw new IOException("Gas registry path is not a directory: " + directory);
		try (var entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) if (!KNOWN_FILES.contains(entry.getFileName().toString()) || Files.isDirectory(entry)) {
				throw new IOException("Unknown gas registry content is preserved at " + entry);
			}
		}
	}

	private static CompoundTag compound(ListTag list, int index, String context) throws IOException {
		Tag tag = list.get(index);
		if (!(tag instanceof CompoundTag compound)) throw new IOException("Invalid gas " + context + " entry");
		return compound;
	}

	private static void requireAll(CompoundTag tag, Map<String, Byte> fields) throws IOException {
		for (Map.Entry<String, Byte> field : fields.entrySet()) {
			Tag value = tag.get(field.getKey());
			if (value == null || value.getId() != field.getValue()) throw new IOException("Missing or invalid gas registry field " + field.getKey());
		}
	}

	private static void force(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
	}

	public record LoadResult(GasRegistry registry, boolean definitionMismatch, boolean created) { }
}
