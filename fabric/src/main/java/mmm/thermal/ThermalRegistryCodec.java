package mmm.thermal;

import mmm.chemistry.ExactRational;
import mmm.simulation.persistence.DurableFileOperations;
import mmm.simulation.persistence.StrictNbtFields;
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

/**
 * Canonical snapshot codec for the thermal registry. The world capability bundle is the only place a
 * registry snapshot is stored, so this codec has no world directory of its own.
 */
public final class ThermalRegistryCodec {
	private ThermalRegistryCodec() { }

	private static final String SCHEMA = "thermal-registry";
	private static final long MAX_BYTES = 8L * 1024L * 1024L;

	/** Canonical bytes of the strict snapshot; the bundle stores exactly these so checksums agree. */
	public static byte[] snapshotBytes(ThermalRegistry registry) throws IOException {
		if (registry == null) throw new IllegalArgumentException("Thermal registry snapshot is missing");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		NbtIo.writeCompressed(encode(registry), output);
		return output.toByteArray();
	}

	public static void writeSnapshot(Path path, ThermalRegistry registry) throws IOException {
		if (path == null || registry == null) throw new IllegalArgumentException("Thermal registry snapshot write is incomplete");
		Files.write(path, snapshotBytes(registry));
	}

	public static ThermalRegistry readSnapshot(Path path, String chemicalFingerprint, String granularFingerprint, String gasFingerprint) throws IOException {
		if (path == null) throw new IllegalArgumentException("Thermal registry snapshot path is missing");
		chemicalFingerprint = requireFingerprint(chemicalFingerprint, "chemical");
		granularFingerprint = requireFingerprint(granularFingerprint, "granular");
		gasFingerprint = requireFingerprint(gasFingerprint, "gas");
		CompoundTag root;
		try { root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_BYTES)); }
		catch (RuntimeException invalid) { throw new IOException("Unreadable thermal registry snapshot " + path, invalid); }
		requireAll(root, Map.ofEntries(
				Map.entry("Schema", Tag.TAG_STRING), Map.entry("UnitSystem", Tag.TAG_STRING),
				Map.entry("MassEncoding", Tag.TAG_STRING),
				Map.entry("Fingerprint", Tag.TAG_STRING), Map.entry("Canonical", Tag.TAG_BYTE_ARRAY),
				Map.entry("ChemicalFingerprint", Tag.TAG_STRING), Map.entry("GranularFingerprint", Tag.TAG_STRING),
				Map.entry("GasFingerprint", Tag.TAG_STRING),
				Map.entry("Materials", Tag.TAG_LIST), Map.entry("SpeciesOffsets", Tag.TAG_LIST),
				Map.entry("Transitions", Tag.TAG_LIST), Map.entry("Boundaries", Tag.TAG_LIST),
				Map.entry("FiniteDevices", Tag.TAG_LIST)));
		if (!mmm.units.Mass.ENCODING.equals(root.getStringOr("MassEncoding", ""))
				|| !root.getStringOr("Schema", "").equals(SCHEMA)
				|| !ThermalRegistry.UNIT_SYSTEM_ID.equals(root.getStringOr("UnitSystem", ""))) {
			throw new IOException("Unsupported thermal registry schema or unit system");
		}
		try {
			String chemical = root.getStringOr("ChemicalFingerprint", "");
			String granular = root.getStringOr("GranularFingerprint", "");
			String gas = root.getStringOr("GasFingerprint", "");
			if (!chemical.equals(chemicalFingerprint) || !granular.equals(granularFingerprint)
					|| !gas.equals(gasFingerprint)) {
				throw new IOException("Thermal registry dependency fingerprint mismatch");
			}
			ThermalRegistry registry = new ThermalRegistry(
					decodeMaterials(root.getListOrEmpty("Materials")),
					decodeOffsets(root.getListOrEmpty("SpeciesOffsets")),
					decodeTransitions(root.getListOrEmpty("Transitions")),
					decodeBoundaries(root.getListOrEmpty("Boundaries")),
					decodeDevices(root.getListOrEmpty("FiniteDevices")), chemical, granular, gas);
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
		root.putString("Schema", SCHEMA);
		root.putString("MassEncoding", mmm.units.Mass.ENCODING); root.putString("UnitSystem", ThermalRegistry.UNIT_SYSTEM_ID);
		root.putString("Fingerprint", registry.fingerprint()); root.putByteArray("Canonical", registry.canonicalBytes());
		root.putString("ChemicalFingerprint", registry.chemicalRegistryFingerprint());
		root.putString("GranularFingerprint", registry.granularRegistryFingerprint());
		root.putString("GasFingerprint", registry.gasRegistryFingerprint());
		ListTag materials = new ListTag();
		for (ThermalMaterialDefinition material : registry.materials()) {
			CompoundTag tag = new CompoundTag(); tag.putString("Id", material.id());
			tag.putString("CarrierKind", material.carrierKind().name()); tag.putString("CarrierId", material.carrierId());
			tag.putLong("ReferenceTemperatureBits", bits(material.referenceTemperatureK()));
			tag.putLong("ReferenceSpecificEnergyJPerKgBits", bits(material.referenceSpecificEnergyJPerKg()));
			tag.putLong("ConductivityWPerMKBits", bits(material.conductivityWPerMeterK()));
			ListTag segments = new ListTag();
			for (ThermalMaterialDefinition.CaloricSegment segment : material.caloricSegments()) {
				CompoundTag encoded = new CompoundTag(); encoded.putLong("MinimumBits", bits(segment.minimumTemperatureK()));
				encoded.putLong("MaximumBits", bits(segment.maximumTemperatureK()));
				encoded.putLong("SpecificHeatJPerKgKBits", bits(segment.specificHeatJPerKgK())); segments.add(encoded);
			}
			tag.put("Segments", segments); materials.add(tag);
		}
		root.put("Materials", materials);
		ListTag offsets = new ListTag();
		for (ThermalSpeciesOffset offset : registry.speciesOffsets()) {
			CompoundTag tag = new CompoundTag(); tag.putString("Id", offset.id()); tag.putString("Species", offset.speciesId());
			tag.putString("Host", offset.hostThermalMaterialId());
			tag.putString("ReferenceEnergyJPerMol", offset.referenceInternalEnergyJPerMol().canonical()); offsets.add(tag);
		}
		root.put("SpeciesOffsets", offsets);
		ListTag transitions = new ListTag();
		for (ThermalPhaseTransition transition : registry.transitions()) {
			CompoundTag tag = new CompoundTag(); tag.putString("Id", transition.id());
			tag.putString("Lower", transition.lowerEnergyMaterialId()); tag.putString("Upper", transition.upperEnergyMaterialId());
			tag.putLong("LowerSvq", transition.lowerVolumeSvqPerBatch()); tag.putLong("UpperSvq", transition.upperVolumeSvqPerBatch());
			tag.putString("MassKg", transition.massKgPerBatch().canonical());
			tag.putString("Interface", transition.interfaceRequirement().name());
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
		ListTag devices = new ListTag();
		for (FiniteThermalDeviceDefinition device : registry.finiteDevices()) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Id", device.id());
			tag.putString("ThermalMaterial", device.thermalMaterialId());
			tag.putLong("BodyMassUnits", device.bodyMassUnits());
			tag.putLong("InitialTemperatureBits", bits(device.initialTemperatureK()));
			ListTag ports = new ListTag();
			for (FiniteThermalDeviceDefinition.Port port : device.ports()) {
				CompoundTag encoded = new CompoundTag();
				encoded.putString("Id", port.id());
				encoded.putString("ContactGroup", port.contactGroupId());
				encoded.putString("LocalFace", port.localFace().name());
				encoded.putLong("WallThicknessBits", bits(port.wallThicknessMeters()));
				encoded.putLong("FluidFilmThicknessBits", bits(port.fluidFilmThicknessMeters()));
				ports.add(encoded);
			}
			tag.put("Ports", ports);
			devices.add(tag);
		}
		root.put("FiniteDevices", devices);
		return root;
	}

	private static List<ThermalMaterialDefinition> decodeMaterials(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > ThermalLimits.MAX_MATERIALS) throw new IOException("Invalid thermal material count");
		ArrayList<ThermalMaterialDefinition> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "material");
			requireAll(tag, Map.ofEntries(Map.entry("Id", Tag.TAG_STRING), Map.entry("CarrierKind", Tag.TAG_STRING),
					Map.entry("CarrierId", Tag.TAG_STRING), Map.entry("ReferenceTemperatureBits", Tag.TAG_LONG),
					Map.entry("ReferenceSpecificEnergyJPerKgBits", Tag.TAG_LONG), Map.entry("ConductivityWPerMKBits", Tag.TAG_LONG),
					Map.entry("Segments", Tag.TAG_LIST)));
			ListTag encodedSegments = tag.getListOrEmpty("Segments");
			if (encodedSegments.isEmpty() || encodedSegments.size() > ThermalLimits.MAX_SEGMENTS_PER_MATERIAL) {
				throw new IOException("Invalid thermal caloric segment count");
			}
			ArrayList<ThermalMaterialDefinition.CaloricSegment> segments = new ArrayList<>(encodedSegments.size());
			for (int segment = 0; segment < encodedSegments.size(); segment++) {
				CompoundTag value = compound(encodedSegments, segment, "caloric segment");
				requireAll(value, Map.of("MinimumBits", Tag.TAG_LONG, "MaximumBits", Tag.TAG_LONG,
						"SpecificHeatJPerKgKBits", Tag.TAG_LONG));
				segments.add(new ThermalMaterialDefinition.CaloricSegment(value(value, "MinimumBits"),
						value(value, "MaximumBits"), value(value, "SpecificHeatJPerKgKBits")));
			}
			result.add(new ThermalMaterialDefinition(tag.getStringOr("Id", ""),
					ThermalMaterialDefinition.CarrierKind.valueOf(tag.getStringOr("CarrierKind", "")),
					tag.getStringOr("CarrierId", ""), value(tag, "ReferenceTemperatureBits"),
					value(tag, "ReferenceSpecificEnergyJPerKgBits"), value(tag, "ConductivityWPerMKBits"), segments));
		}
		return List.copyOf(result);
	}

	private static List<ThermalSpeciesOffset> decodeOffsets(ListTag list) throws IOException {
		if (list.size() > ThermalLimits.MAX_SPECIES_OFFSETS) throw new IOException("Invalid thermal species offset count");
		ArrayList<ThermalSpeciesOffset> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "species offset");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "Species", Tag.TAG_STRING,
					"Host", Tag.TAG_STRING, "ReferenceEnergyJPerMol", Tag.TAG_STRING));
			result.add(new ThermalSpeciesOffset(tag.getStringOr("Id", ""), tag.getStringOr("Species", ""),
					tag.getStringOr("Host", ""), ExactRational.parse(tag.getStringOr("ReferenceEnergyJPerMol", ""))));
		}
		return List.copyOf(result);
	}

	private static List<ThermalPhaseTransition> decodeTransitions(ListTag list) throws IOException {
		if (list.size() > ThermalLimits.MAX_PHASE_TRANSITIONS) throw new IOException("Invalid thermal transition count");
		ArrayList<ThermalPhaseTransition> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "transition");
			requireAll(tag, Map.ofEntries(Map.entry("Id", Tag.TAG_STRING), Map.entry("Lower", Tag.TAG_STRING),
					Map.entry("Upper", Tag.TAG_STRING),
					Map.entry("LowerSvq", Tag.TAG_LONG), Map.entry("UpperSvq", Tag.TAG_LONG),
					Map.entry("MassKg", Tag.TAG_STRING), Map.entry("Interface", Tag.TAG_STRING),
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
					tag.getStringOr("Upper", ""), tag.getLongOr("LowerSvq", -1L),
					tag.getLongOr("UpperSvq", -1L), ExactRational.parse(tag.getStringOr("MassKg", "")),
					ThermalPhaseTransition.InterfaceRequirement.valueOf(tag.getStringOr("Interface", "")),
					curve));
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


	private static List<FiniteThermalDeviceDefinition> decodeDevices(ListTag list) throws IOException {
		if (list.size() > ThermalLimits.MAX_FINITE_DEVICE_TYPES) {
			throw new IOException("Invalid finite thermal device count");
		}
		ArrayList<FiniteThermalDeviceDefinition> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "finite thermal device");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "ThermalMaterial", Tag.TAG_STRING,
					"BodyMassUnits", Tag.TAG_LONG, "InitialTemperatureBits", Tag.TAG_LONG,
					"Ports", Tag.TAG_LIST));
			ListTag encodedPorts = tag.getListOrEmpty("Ports");
			if (encodedPorts.isEmpty() || encodedPorts.size() > ThermalLimits.MAX_FINITE_DEVICE_CONTACTS) {
				throw new IOException("Invalid finite thermal port count");
			}
			ArrayList<FiniteThermalDeviceDefinition.Port> ports = new ArrayList<>(encodedPorts.size());
			for (int port = 0; port < encodedPorts.size(); port++) {
				CompoundTag value = compound(encodedPorts, port, "finite thermal port");
				requireAll(value, Map.of("Id", Tag.TAG_STRING, "ContactGroup", Tag.TAG_STRING,
						"LocalFace", Tag.TAG_STRING, "WallThicknessBits", Tag.TAG_LONG,
						"FluidFilmThicknessBits", Tag.TAG_LONG));
				ports.add(new FiniteThermalDeviceDefinition.Port(value.getStringOr("Id", ""),
						value.getStringOr("ContactGroup", ""),
						FiniteThermalDeviceDefinition.LocalFace.valueOf(value.getStringOr("LocalFace", "")),
						value(value, "WallThicknessBits"), value(value, "FluidFilmThicknessBits")));
			}
			result.add(new FiniteThermalDeviceDefinition(tag.getStringOr("Id", ""),
					tag.getStringOr("ThermalMaterial", ""), tag.getLongOr("BodyMassUnits", 0L),
					value(tag, "InitialTemperatureBits"), ports));
		}
		return List.copyOf(result);
	}

	private static CompoundTag compound(ListTag list, int index, String context) throws IOException {
		Tag tag = list.get(index);
		if (!(tag instanceof CompoundTag compound)) throw new IOException("Invalid thermal " + context + " entry");
		return compound;
	}

	private static void requireAll(CompoundTag tag, Map<String, Byte> fields) throws IOException {
		StrictNbtFields.requireExact(tag, fields, "thermal registry");
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
}
