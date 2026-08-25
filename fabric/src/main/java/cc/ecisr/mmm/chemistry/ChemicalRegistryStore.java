package cc.ecisr.mmm.chemistry;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** World-authoritative immutable registry snapshot with atomic first publication. */
public final class ChemicalRegistryStore {
	private static final int FORMAT_VERSION = 1;
	private static final long MAX_BYTES = 4L * 1024L * 1024L;
	private static final Set<String> KNOWN_FILES = Set.of("registry.nbt", "registry.nbt.tmp");
	private final Path directory;

	public ChemicalRegistryStore(Path worldRoot) {
		this.directory = worldRoot.toAbsolutePath().normalize().resolve("mmm").resolve("chemistry");
	}

	public LoadResult loadOrCreate(ChemicalRegistry definitions) throws IOException {
		validateLayout();
		Path target = registryPath();
		if (Files.exists(target)) {
			ChemicalRegistry authoritative = read(target);
			return new LoadResult(authoritative, !authoritative.fingerprint().equals(definitions.fingerprint()), false);
		}
		Path temporary = temporaryPath();
		Files.createDirectories(directory);
		Files.deleteIfExists(temporary);
		try {
			NbtIo.writeCompressed(encode(definitions), temporary);
			force(temporary);
			ChemicalRegistry reread = read(temporary);
			if (!reread.fingerprint().equals(definitions.fingerprint())) {
				throw new IOException("Staged chemical registry did not read back identically");
			}
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			return new LoadResult(read(target), false, true);
		} catch (IOException failure) {
			try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
			throw failure;
		}
	}

	public ChemicalRegistry read() throws IOException {
		validateLayout();
		if (!Files.exists(registryPath())) throw new IOException("Chemical registry snapshot is missing at " + registryPath());
		return read(registryPath());
	}

	public boolean exists() { return Files.exists(registryPath()); }
	public Path registryPath() { return directory.resolve("registry.nbt"); }
	private Path temporaryPath() { return directory.resolve("registry.nbt.tmp"); }

	private ChemicalRegistry read(Path path) throws IOException {
		CompoundTag root;
		try { root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_BYTES)); }
		catch (RuntimeException invalid) { throw new IOException("Unreadable chemical registry snapshot " + path, invalid); }
		require(root, "Version", Tag.TAG_INT);
		require(root, "AuScale", Tag.TAG_LONG);
		require(root, "Fingerprint", Tag.TAG_STRING);
		require(root, "Canonical", Tag.TAG_BYTE_ARRAY);
		require(root, "Species", Tag.TAG_LIST);
		require(root, "Phases", Tag.TAG_LIST);
		require(root, "Reactions", Tag.TAG_LIST);
		if (root.getIntOr("Version", -1) != FORMAT_VERSION) throw new IOException("Unsupported chemical registry version");
		if (root.getLongOr("AuScale", -1L) != ChemicalLimits.QUANTA_PER_AU) throw new IOException("Chemical AU scale mismatch");
		try {
			List<ChemicalSpecies> species = decodeSpecies(root.getListOrEmpty("Species"));
			List<ChemicalPhaseDefinition> phases = decodePhases(root.getListOrEmpty("Phases"));
			List<ChemicalReaction> reactions = decodeReactions(root.getListOrEmpty("Reactions"));
			ChemicalRegistry registry = new ChemicalRegistry(species, phases, reactions);
			String fingerprint = root.getStringOr("Fingerprint", "");
			byte[] canonical = root.getByteArray("Canonical").orElseThrow();
			if (!registry.fingerprint().equals(fingerprint)
					|| !java.util.Arrays.equals(registry.canonicalBytes(), canonical)) {
				throw new IOException("Chemical registry fingerprint/canonical bytes mismatch");
			}
			return registry;
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid chemical registry snapshot: " + invalid.getMessage(), invalid);
		}
	}

	private static CompoundTag encode(ChemicalRegistry registry) {
		CompoundTag root = new CompoundTag();
		root.putInt("Version", FORMAT_VERSION);
		root.putLong("AuScale", ChemicalLimits.QUANTA_PER_AU);
		root.putString("Fingerprint", registry.fingerprint());
		root.putByteArray("Canonical", registry.canonicalBytes());
		ListTag species = new ListTag();
		for (ChemicalSpecies definition : registry.species()) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Id", definition.id()); tag.putInt("Charge", definition.charge());
			tag.putString("Mass", definition.massPerAu().canonical()); tag.putInt("Color", definition.baseArgb());
			tag.put("Composition", encodeIntegerMap(definition.composition())); species.add(tag);
		}
		root.put("Species", species);
		ListTag phases = new ListTag();
		for (ChemicalPhaseDefinition definition : registry.phases()) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Id", definition.id()); tag.putString("Material", definition.materialId());
			tag.putBoolean("Indicator", definition.acceptsIndicator());
			ListTag allowed = new ListTag();
			for (String id : definition.allowedSpecies()) allowed.add(StringTag.valueOf(id));
			tag.put("Allowed", allowed); tag.put("Defaults", encodeRationalMap(definition.defaultInventoryPerBv()));
			phases.add(tag);
		}
		root.put("Phases", phases);
		ListTag reactions = new ListTag();
		for (ChemicalReaction definition : registry.reactions()) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Id", definition.id()); tag.putString("Kind", definition.kind().name());
			tag.putString("Phase", definition.phaseId()); tag.put("Reactants", encodeIntegerMap(definition.reactants()));
			tag.put("Products", encodeIntegerMap(definition.products()));
			tag.putString("VolumeDelta", definition.volumeDeltaBvPerExtent().canonical()); reactions.add(tag);
		}
		root.put("Reactions", reactions);
		return root;
	}

	private static List<ChemicalSpecies> decodeSpecies(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > ChemicalLimits.MAX_SPECIES) throw new IOException("Invalid snapshot species count");
		List<ChemicalSpecies> result = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = compound(list, i, "species");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "Charge", Tag.TAG_INT, "Mass", Tag.TAG_STRING,
					"Color", Tag.TAG_INT, "Composition", Tag.TAG_LIST));
			result.add(new ChemicalSpecies(tag.getStringOr("Id", ""), decodeIntegerMap(tag.getListOrEmpty("Composition")),
					tag.getIntOr("Charge", Integer.MIN_VALUE), ExactRational.parse(tag.getStringOr("Mass", "")),
					tag.getIntOr("Color", 0)));
		}
		return result;
	}

	private static List<ChemicalPhaseDefinition> decodePhases(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > ChemicalLimits.MAX_PHASES) throw new IOException("Invalid snapshot phase count");
		List<ChemicalPhaseDefinition> result = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = compound(list, i, "phase");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "Material", Tag.TAG_STRING, "Indicator", Tag.TAG_BYTE,
					"Allowed", Tag.TAG_LIST, "Defaults", Tag.TAG_LIST));
			List<String> allowed = new ArrayList<>();
			ListTag encodedAllowed = tag.getListOrEmpty("Allowed");
			for (int entry = 0; entry < encodedAllowed.size(); entry++) {
				Tag value = encodedAllowed.get(entry);
				if (!(value instanceof StringTag text)) throw new IOException("Invalid allowed species entry");
				allowed.add(text.asString().orElseThrow());
			}
			result.add(new ChemicalPhaseDefinition(tag.getStringOr("Id", ""), tag.getStringOr("Material", ""),
					allowed, decodeRationalMap(tag.getListOrEmpty("Defaults")), tag.getBooleanOr("Indicator", false)));
		}
		return result;
	}

	private static List<ChemicalReaction> decodeReactions(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > ChemicalLimits.MAX_REACTIONS) throw new IOException("Invalid snapshot reaction count");
		List<ChemicalReaction> result = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = compound(list, i, "reaction");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "Kind", Tag.TAG_STRING, "Phase", Tag.TAG_STRING,
					"Reactants", Tag.TAG_LIST, "Products", Tag.TAG_LIST, "VolumeDelta", Tag.TAG_STRING));
			result.add(new ChemicalReaction(tag.getStringOr("Id", ""),
					ChemicalReaction.Kind.valueOf(tag.getStringOr("Kind", "")), tag.getStringOr("Phase", ""),
					decodeIntegerMap(tag.getListOrEmpty("Reactants")), decodeIntegerMap(tag.getListOrEmpty("Products")),
					ExactRational.parse(tag.getStringOr("VolumeDelta", ""))));
		}
		return result;
	}

	private static ListTag encodeIntegerMap(Map<String, Integer> map) {
		ListTag list = new ListTag();
		new TreeMap<>(map).forEach((key, value) -> { CompoundTag tag = new CompoundTag();
			tag.putString("Key", key); tag.putInt("Value", value); list.add(tag); });
		return list;
	}
	private static Map<String, Integer> decodeIntegerMap(ListTag list) throws IOException {
		TreeMap<String, Integer> result = new TreeMap<>();
		for (int i = 0; i < list.size(); i++) { CompoundTag tag = compound(list, i, "integer map");
			requireAll(tag, Map.of("Key", Tag.TAG_STRING, "Value", Tag.TAG_INT));
			if (result.put(tag.getStringOr("Key", ""), tag.getIntOr("Value", 0)) != null) throw new IOException("Duplicate map key"); }
		return result;
	}
	private static ListTag encodeRationalMap(Map<String, ExactRational> map) {
		ListTag list = new ListTag();
		new TreeMap<>(map).forEach((key, value) -> { CompoundTag tag = new CompoundTag();
			tag.putString("Key", key); tag.putString("Value", value.canonical()); list.add(tag); });
		return list;
	}
	private static Map<String, ExactRational> decodeRationalMap(ListTag list) throws IOException {
		TreeMap<String, ExactRational> result = new TreeMap<>();
		for (int i = 0; i < list.size(); i++) { CompoundTag tag = compound(list, i, "rational map");
			requireAll(tag, Map.of("Key", Tag.TAG_STRING, "Value", Tag.TAG_STRING));
			if (result.put(tag.getStringOr("Key", ""), ExactRational.parse(tag.getStringOr("Value", ""))) != null) throw new IOException("Duplicate map key"); }
		return result;
	}

	private void validateLayout() throws IOException {
		if (!Files.exists(directory)) return;
		if (!Files.isDirectory(directory)) throw new IOException("Chemical registry path is not a directory: " + directory);
		try (var entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) if (!KNOWN_FILES.contains(entry.getFileName().toString()) || Files.isDirectory(entry)) {
				throw new IOException("Unknown chemical registry content is preserved at " + entry);
			}
		}
	}

	private static CompoundTag compound(ListTag list, int index, String context) throws IOException {
		Tag tag = list.get(index);
		if (!(tag instanceof CompoundTag compound)) throw new IOException("Invalid " + context + " entry");
		return compound;
	}
	private static void requireAll(CompoundTag tag, Map<String, Byte> fields) throws IOException {
		for (Map.Entry<String, Byte> field : fields.entrySet()) require(tag, field.getKey(), field.getValue());
	}
	private static void require(CompoundTag tag, String key, byte type) throws IOException {
		Tag value = tag.get(key);
		if (value == null || value.getId() != type) throw new IOException("Missing or invalid registry field " + key);
	}
	private static void force(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
	}

	public record LoadResult(ChemicalRegistry registry, boolean definitionMismatch, boolean created) { }
}
