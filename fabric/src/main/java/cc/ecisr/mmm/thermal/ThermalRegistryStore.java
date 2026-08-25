package cc.ecisr.mmm.thermal;

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

/** World-authoritative immutable thermal registry v2 with atomic first publication. */
public final class ThermalRegistryStore {
	private static final int FORMAT_VERSION = 2;
	private static final long MAX_BYTES = 8L * 1024L * 1024L;
	private static final Set<String> KNOWN_FILES = Set.of("registry.nbt", "registry.nbt.tmp");

	private final Path directory;
	private final String chemicalFingerprint;
	private final String granularFingerprint;

	public ThermalRegistryStore(Path worldRoot, String chemicalFingerprint, String granularFingerprint) {
		if (worldRoot == null) throw new IllegalArgumentException("Thermal registry world root is missing");
		this.directory = worldRoot.toAbsolutePath().normalize().resolve("mmm").resolve("thermal");
		this.chemicalFingerprint = requireFingerprint(chemicalFingerprint, "chemical");
		this.granularFingerprint = requireFingerprint(granularFingerprint, "granular");
	}

	public LoadResult loadOrCreate(ThermalRegistry definitions) throws IOException {
		if (definitions == null) throw new IllegalArgumentException("Thermal registry definitions are missing");
		requireDependencies(definitions);
		validateLayout();
		Path target = registryPath();
		if (Files.exists(target)) {
			ThermalRegistry authoritative = read(target);
			return new LoadResult(authoritative, !authoritative.fingerprint().equals(definitions.fingerprint()), false);
		}
		Path temporary = temporaryPath();
		Files.createDirectories(directory);
		Files.deleteIfExists(temporary);
		try {
			NbtIo.writeCompressed(encode(definitions), temporary);
			force(temporary);
			ThermalRegistry reread = read(temporary);
			if (!reread.fingerprint().equals(definitions.fingerprint())) {
				throw new IOException("Staged thermal registry did not read back identically");
			}
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			return new LoadResult(read(target), false, true);
		} catch (IOException failure) {
			try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
			throw failure;
		}
	}

	public ThermalRegistry read() throws IOException {
		validateLayout();
		if (!exists()) throw new IOException("Thermal registry snapshot is missing at " + registryPath());
		return read(registryPath());
	}

	public boolean exists() { return Files.exists(registryPath()); }
	public Path registryPath() { return directory.resolve("registry.nbt"); }
	private Path temporaryPath() { return directory.resolve("registry.nbt.tmp"); }

	private ThermalRegistry read(Path path) throws IOException {
		CompoundTag root;
		try { root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_BYTES)); }
		catch (RuntimeException invalid) { throw new IOException("Unreadable thermal registry snapshot " + path, invalid); }
		requireAll(root, Map.ofEntries(
				Map.entry("Version", Tag.TAG_INT), Map.entry("UnitSystem", Tag.TAG_STRING),
				Map.entry("Fingerprint", Tag.TAG_STRING), Map.entry("Canonical", Tag.TAG_BYTE_ARRAY),
				Map.entry("ChemicalFingerprint", Tag.TAG_STRING), Map.entry("GranularFingerprint", Tag.TAG_STRING),
				Map.entry("Materials", Tag.TAG_LIST), Map.entry("SpeciesOffsets", Tag.TAG_LIST),
				Map.entry("Transitions", Tag.TAG_LIST), Map.entry("Boundaries", Tag.TAG_LIST)));
		if (root.getIntOr("Version", -1) != FORMAT_VERSION
				|| !ThermalRegistry.UNIT_SYSTEM_ID.equals(root.getStringOr("UnitSystem", ""))) {
			throw new IOException("Unsupported thermal registry version or unit system");
		}
		try {
			String chemical = root.getStringOr("ChemicalFingerprint", "");
			String granular = root.getStringOr("GranularFingerprint", "");
			if (!chemical.equals(chemicalFingerprint) || !granular.equals(granularFingerprint)) {
				throw new IOException("Thermal registry dependency fingerprint mismatch");
			}
			ThermalRegistry registry = new ThermalRegistry(
					decodeMaterials(root.getListOrEmpty("Materials")),
					decodeOffsets(root.getListOrEmpty("SpeciesOffsets")),
					decodeTransitions(root.getListOrEmpty("Transitions")),
					decodeBoundaries(root.getListOrEmpty("Boundaries")), chemical, granular);
			if (!registry.fingerprint().equals(root.getStringOr("Fingerprint", ""))
					|| !java.util.Arrays.equals(registry.canonicalBytes(), root.getByteArray("Canonical").orElseThrow())) {
				throw new IOException("Thermal registry fingerprint/canonical bytes mismatch");
			}
			return registry;
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid thermal registry snapshot: " + invalid.getMessage(), invalid);
		}
	}

	private static CompoundTag encode(ThermalRegistry registry) {
		CompoundTag root = new CompoundTag();
		root.putInt("Version", FORMAT_VERSION); root.putString("UnitSystem", ThermalRegistry.UNIT_SYSTEM_ID);
		root.putString("Fingerprint", registry.fingerprint()); root.putByteArray("Canonical", registry.canonicalBytes());
		root.putString("ChemicalFingerprint", registry.chemicalRegistryFingerprint());
		root.putString("GranularFingerprint", registry.granularRegistryFingerprint());
		ListTag materials = new ListTag();
		for (ThermalMaterialDefinition material : registry.materials()) {
			CompoundTag tag = new CompoundTag(); tag.putString("Id", material.id());
			tag.putString("CarrierKind", material.carrierKind().name()); tag.putString("CarrierId", material.carrierId());
			tag.putLong("ReferenceTemperatureBits", bits(material.referenceTemperatureK()));
			tag.putLong("ReferenceEnergyBits", bits(material.referenceSpecificEnergyEuPerMu()));
			tag.putLong("ConductivityBits", bits(material.conductivityEuPerSecondMeterK()));
			ListTag segments = new ListTag();
			for (ThermalMaterialDefinition.CaloricSegment segment : material.caloricSegments()) {
				CompoundTag encoded = new CompoundTag(); encoded.putLong("MinimumBits", bits(segment.minimumTemperatureK()));
				encoded.putLong("MaximumBits", bits(segment.maximumTemperatureK()));
				encoded.putLong("SpecificHeatBits", bits(segment.specificHeatEuPerMuK())); segments.add(encoded);
			}
			tag.put("Segments", segments); materials.add(tag);
		}
		root.put("Materials", materials);
		ListTag offsets = new ListTag();
		for (ThermalSpeciesOffset offset : registry.speciesOffsets()) {
			CompoundTag tag = new CompoundTag(); tag.putString("Id", offset.id()); tag.putString("Species", offset.speciesId());
			tag.putString("Host", offset.hostThermalMaterialId());
			tag.putString("ReferenceEnergy", offset.referenceInternalEnergyEuPerAu().canonical()); offsets.add(tag);
		}
		root.put("SpeciesOffsets", offsets);
		ListTag transitions = new ListTag();
		for (ThermalPhaseTransition transition : registry.transitions()) {
			CompoundTag tag = new CompoundTag(); tag.putString("Id", transition.id());
			tag.putString("Lower", transition.lowerEnergyMaterialId()); tag.putString("Upper", transition.upperEnergyMaterialId());
			tag.putLong("TemperatureBits", bits(transition.equilibriumTemperatureK()));
			tag.putLong("LowerSvq", transition.lowerVolumeSvqPerBatch()); tag.putLong("UpperSvq", transition.upperVolumeSvqPerBatch());
			tag.putString("Mass", transition.massMuPerBatch().canonical());
			tag.putString("Interface", transition.interfaceRequirement().name());
			tag.putLong("MinimumPressureBits", bits(transition.minimumPressurePa()));
			tag.putLong("MaximumPressureBits", bits(transition.maximumPressurePa()));
			ListTag curve = new ListTag();
			for (ThermalPhaseTransition.EquilibriumPoint point : transition.equilibriumCurve()) {
				CompoundTag encoded = new CompoundTag();
				encoded.putLong("PressureBits", bits(point.pressurePa()));
				encoded.putLong("TemperatureBits", bits(point.temperatureK()));
				curve.add(encoded);
			}
			tag.put("EquilibriumCurve", curve); transitions.add(tag);
		}
		root.put("Transitions", transitions);
		ListTag boundaries = new ListTag();
		for (ThermalBoundaryDefinition boundary : registry.boundaries()) {
			CompoundTag tag = new CompoundTag(); tag.putString("Id", boundary.id());
			tag.putString("TargetKind", boundary.targetKind().name()); tag.putString("TargetId", boundary.targetId());
			tag.putString("Kind", boundary.kind().name()); tag.putLong("TemperatureBits", bits(boundary.surfaceTemperatureK()));
			boundaries.add(tag);
		}
		root.put("Boundaries", boundaries);
		return root;
	}

	private static List<ThermalMaterialDefinition> decodeMaterials(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > ThermalLimits.MAX_MATERIALS) throw new IOException("Invalid thermal material count");
		ArrayList<ThermalMaterialDefinition> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "material");
			requireAll(tag, Map.ofEntries(Map.entry("Id", Tag.TAG_STRING), Map.entry("CarrierKind", Tag.TAG_STRING),
					Map.entry("CarrierId", Tag.TAG_STRING), Map.entry("ReferenceTemperatureBits", Tag.TAG_LONG),
					Map.entry("ReferenceEnergyBits", Tag.TAG_LONG), Map.entry("ConductivityBits", Tag.TAG_LONG),
					Map.entry("Segments", Tag.TAG_LIST)));
			ListTag encodedSegments = tag.getListOrEmpty("Segments");
			if (encodedSegments.isEmpty() || encodedSegments.size() > ThermalLimits.MAX_SEGMENTS_PER_MATERIAL) {
				throw new IOException("Invalid thermal caloric segment count");
			}
			ArrayList<ThermalMaterialDefinition.CaloricSegment> segments = new ArrayList<>(encodedSegments.size());
			for (int segment = 0; segment < encodedSegments.size(); segment++) {
				CompoundTag value = compound(encodedSegments, segment, "caloric segment");
				requireAll(value, Map.of("MinimumBits", Tag.TAG_LONG, "MaximumBits", Tag.TAG_LONG,
						"SpecificHeatBits", Tag.TAG_LONG));
				segments.add(new ThermalMaterialDefinition.CaloricSegment(value(value, "MinimumBits"),
						value(value, "MaximumBits"), value(value, "SpecificHeatBits")));
			}
			result.add(new ThermalMaterialDefinition(tag.getStringOr("Id", ""),
					ThermalMaterialDefinition.CarrierKind.valueOf(tag.getStringOr("CarrierKind", "")),
					tag.getStringOr("CarrierId", ""), value(tag, "ReferenceTemperatureBits"),
					value(tag, "ReferenceEnergyBits"), value(tag, "ConductivityBits"), segments));
		}
		return List.copyOf(result);
	}

	private static List<ThermalSpeciesOffset> decodeOffsets(ListTag list) throws IOException {
		if (list.size() > ThermalLimits.MAX_SPECIES_OFFSETS) throw new IOException("Invalid thermal species offset count");
		ArrayList<ThermalSpeciesOffset> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "species offset");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "Species", Tag.TAG_STRING,
					"Host", Tag.TAG_STRING, "ReferenceEnergy", Tag.TAG_STRING));
			result.add(new ThermalSpeciesOffset(tag.getStringOr("Id", ""), tag.getStringOr("Species", ""),
					tag.getStringOr("Host", ""), ExactRational.parse(tag.getStringOr("ReferenceEnergy", ""))));
		}
		return List.copyOf(result);
	}

	private static List<ThermalPhaseTransition> decodeTransitions(ListTag list) throws IOException {
		if (list.size() > ThermalLimits.MAX_PHASE_TRANSITIONS) throw new IOException("Invalid thermal transition count");
		ArrayList<ThermalPhaseTransition> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "transition");
			requireAll(tag, Map.ofEntries(Map.entry("Id", Tag.TAG_STRING), Map.entry("Lower", Tag.TAG_STRING),
					Map.entry("Upper", Tag.TAG_STRING), Map.entry("TemperatureBits", Tag.TAG_LONG),
					Map.entry("LowerSvq", Tag.TAG_LONG), Map.entry("UpperSvq", Tag.TAG_LONG),
					Map.entry("Mass", Tag.TAG_STRING), Map.entry("Interface", Tag.TAG_STRING),
					Map.entry("MinimumPressureBits", Tag.TAG_LONG), Map.entry("MaximumPressureBits", Tag.TAG_LONG),
					Map.entry("EquilibriumCurve", Tag.TAG_LIST)));
			ListTag encodedCurve = tag.getListOrEmpty("EquilibriumCurve");
			if (encodedCurve.size() < 2 || encodedCurve.size() > ThermalLimits.MAX_EQUILIBRIUM_CURVE_POINTS) {
				throw new IOException("Invalid thermal equilibrium curve size");
			}
			ArrayList<ThermalPhaseTransition.EquilibriumPoint> curve = new ArrayList<>(encodedCurve.size());
			for (int point = 0; point < encodedCurve.size(); point++) {
				CompoundTag value = compound(encodedCurve, point, "equilibrium curve point");
				requireAll(value, Map.of("PressureBits", Tag.TAG_LONG, "TemperatureBits", Tag.TAG_LONG));
				curve.add(new ThermalPhaseTransition.EquilibriumPoint(
						value(value, "PressureBits"), value(value, "TemperatureBits")));
			}
			result.add(new ThermalPhaseTransition(tag.getStringOr("Id", ""), tag.getStringOr("Lower", ""),
					tag.getStringOr("Upper", ""), value(tag, "TemperatureBits"), tag.getLongOr("LowerSvq", -1L),
					tag.getLongOr("UpperSvq", -1L), ExactRational.parse(tag.getStringOr("Mass", "")),
					ThermalPhaseTransition.InterfaceRequirement.valueOf(tag.getStringOr("Interface", "")),
					value(tag, "MinimumPressureBits"), value(tag, "MaximumPressureBits"), curve));
		}
		return List.copyOf(result);
	}

	private static List<ThermalBoundaryDefinition> decodeBoundaries(ListTag list) throws IOException {
		if (list.size() > ThermalLimits.MAX_BOUNDARIES) throw new IOException("Invalid thermal boundary count");
		ArrayList<ThermalBoundaryDefinition> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "boundary");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "TargetKind", Tag.TAG_STRING, "TargetId", Tag.TAG_STRING,
					"Kind", Tag.TAG_STRING, "TemperatureBits", Tag.TAG_LONG));
			result.add(new ThermalBoundaryDefinition(tag.getStringOr("Id", ""),
					ThermalBoundaryDefinition.TargetKind.valueOf(tag.getStringOr("TargetKind", "")),
					tag.getStringOr("TargetId", ""), ThermalBoundaryDefinition.Kind.valueOf(tag.getStringOr("Kind", "")),
					value(tag, "TemperatureBits")));
		}
		return List.copyOf(result);
	}

	private void requireDependencies(ThermalRegistry registry) {
		if (!chemicalFingerprint.equals(registry.chemicalRegistryFingerprint())
				|| !granularFingerprint.equals(registry.granularRegistryFingerprint())) {
			throw new IllegalArgumentException("Thermal definition dependency fingerprints changed");
		}
	}

	private void validateLayout() throws IOException {
		if (!Files.exists(directory)) return;
		if (!Files.isDirectory(directory)) throw new IOException("Thermal registry path is not a directory: " + directory);
		try (var entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) if (!KNOWN_FILES.contains(entry.getFileName().toString()) || Files.isDirectory(entry)) {
				throw new IOException("Unknown thermal registry content is preserved at " + entry);
			}
		}
	}

	private static CompoundTag compound(ListTag list, int index, String context) throws IOException {
		Tag tag = list.get(index);
		if (!(tag instanceof CompoundTag compound)) throw new IOException("Invalid thermal " + context + " entry");
		return compound;
	}

	private static void requireAll(CompoundTag tag, Map<String, Byte> fields) throws IOException {
		for (Map.Entry<String, Byte> field : fields.entrySet()) {
			Tag value = tag.get(field.getKey());
			if (value == null || value.getId() != field.getValue()) {
				throw new IOException("Missing or invalid thermal registry field " + field.getKey());
			}
		}
	}

	private static String requireFingerprint(String value, String label) {
		if (value == null || !(value.isEmpty() || value.matches("[0-9a-f]{64}"))) {
			throw new IllegalArgumentException("Invalid " + label + " registry fingerprint");
		}
		return value;
	}

	private static long bits(double value) { return Double.doubleToRawLongBits(value); }
	private static double value(CompoundTag tag, String field) {
		return Double.longBitsToDouble(tag.getLongOr(field, 0L));
	}
	private static void force(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
	}

	public record LoadResult(ThermalRegistry registry, boolean definitionMismatch, boolean created) { }
}
