package mmm.chemistry;

import mmm.simulation.persistence.StrictNbtFields;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical snapshot codec for the chemical registry. The world capability bundle is the only place a
 * registry snapshot is stored, so this codec has no world directory of its own.
 */
public final class ChemicalRegistryCodec {
	private ChemicalRegistryCodec() { }

	private static final String SCHEMA = "chemical-registry";
	private static final long MAX_BYTES = 4L * 1024L * 1024L;

	/** Canonical bytes of the strict snapshot; the bundle stores exactly these so checksums agree. */
	public static byte[] snapshotBytes(ChemicalRegistry registry) throws IOException {
		if (registry == null) throw new IllegalArgumentException("Chemical registry snapshot is missing");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		NbtIo.writeCompressed(encode(registry), output);
		return output.toByteArray();
	}

	public static void writeSnapshot(Path path, ChemicalRegistry registry) throws IOException {
		if (path == null || registry == null) throw new IllegalArgumentException("Chemical registry snapshot write is incomplete");
		Files.write(path, snapshotBytes(registry));
	}

	public static ChemicalRegistry readSnapshot(Path path) throws IOException {
		if (path == null) throw new IllegalArgumentException("Chemical registry snapshot path is missing");
		CompoundTag root;
		try { root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_BYTES)); }
		catch (RuntimeException invalid) { throw new IOException("Unreadable chemical registry snapshot " + path, invalid); }
		requireAll(root, Map.of("Schema", Tag.TAG_STRING, "QuantaPerMol", Tag.TAG_LONG,
				"Fingerprint", Tag.TAG_STRING, "Canonical", Tag.TAG_BYTE_ARRAY,
				"Species", Tag.TAG_LIST, "Phases", Tag.TAG_LIST, "Reactions", Tag.TAG_LIST,
				"GasLiquidMassTransfers", Tag.TAG_LIST));
		if (!root.getStringOr("Schema", "").equals(SCHEMA)) throw new IOException("Unsupported chemical registry schema");
		if (root.getLongOr("QuantaPerMol", -1L) != ChemicalLimits.QUANTA_PER_MOL) throw new IOException("Chemical quanta/mol scale mismatch");
		try {
			List<ChemicalSpecies> species = decodeSpecies(root.getListOrEmpty("Species"));
			List<ChemicalPhaseDefinition> phases = decodePhases(root.getListOrEmpty("Phases"));
			List<ChemicalReaction> reactions = decodeReactions(root.getListOrEmpty("Reactions"));
			ChemicalRegistry registry = new ChemicalRegistry(species, phases, reactions,
					decodeMassTransfers(root.getListOrEmpty("GasLiquidMassTransfers")));
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
		root.putString("Schema", SCHEMA);
		root.putLong("QuantaPerMol", ChemicalLimits.QUANTA_PER_MOL);
		root.putString("Fingerprint", registry.fingerprint());
		root.putByteArray("Canonical", registry.canonicalBytes());
		ListTag species = new ListTag();
		for (ChemicalSpecies definition : registry.species()) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Id", definition.id()); tag.putInt("Charge", definition.charge());
			tag.putString("MolarMassKgPerMol", definition.molarMassKgPerMol().canonical()); tag.putInt("Color", definition.baseArgb());
			tag.putLong("Diffusivity", Double.doubleToRawLongBits(definition.diffusivityM2PerS() == null ? Double.NaN : definition.diffusivityM2PerS()));
			tag.putLong("EffectiveVolume", Double.doubleToRawLongBits(definition.effectiveVolumeM3PerMol() == null ? Double.NaN : definition.effectiveVolumeM3PerMol()));
			ListTag liquidProperties = new ListTag();
			if (definition.liquidProperties() != null) {
				var properties = definition.liquidProperties();
				CompoundTag value = new CompoundTag();
				value.putLong("Density", Double.doubleToRawLongBits(properties.referenceDensityKgPerM3()));
				value.putLong("HeatCapacity", Double.doubleToRawLongBits(properties.heatCapacityJPerKgK()));
				value.putLong("Conductivity", Double.doubleToRawLongBits(properties.conductivityWPerMK()));
				value.putLong("Viscosity", Double.doubleToRawLongBits(properties.viscosityPaS()));
				liquidProperties.add(value);
			}
			tag.put("LiquidProperties", liquidProperties);
			tag.put("Composition", encodeIntegerMap(definition.composition())); species.add(tag);
		}
		root.put("Species", species);
		ListTag phases = new ListTag();
		for (ChemicalPhaseDefinition definition : registry.phases()) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Id", definition.id()); tag.putString("Material", definition.materialId());
			ListTag allowed = new ListTag();
			for (String id : definition.allowedSpecies()) allowed.add(StringTag.valueOf(id));
			tag.put("Allowed", allowed); tag.put("DefaultConcentrationMolPerM3", encodeRationalMap(definition.defaultConcentrationMolPerCubicMeter()));
            ListTag correction = new ListTag();
            if (definition.viscosityCorrection() != null) {
                var value = definition.viscosityCorrection();
                CompoundTag encoded = new CompoundTag();
                encoded.putLong("A", Double.doubleToRawLongBits(value.a()));
                encoded.putLong("B", Double.doubleToRawLongBits(value.b()));
                encoded.putLong("MaximumMolality", Double.doubleToRawLongBits(value.maximumMolality()));
                encoded.putLong("ReferenceTemperature", Double.doubleToRawLongBits(value.referenceTemperatureK()));
                encoded.putLong("TemperatureTolerance", Double.doubleToRawLongBits(value.temperatureToleranceK()));
                correction.add(encoded);
            }
            tag.put("ViscosityCorrection", correction);
			phases.add(tag);
		}
		root.put("Phases", phases);
		ListTag reactions = new ListTag();
		for (ChemicalReaction definition : registry.reactions()) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Id", definition.id()); tag.putString("Kind", definition.kind().name());
			tag.putString("Phase", definition.phaseId()); tag.put("Reactants", encodeIntegerMap(definition.reactants()));
			tag.put("Products", encodeIntegerMap(definition.products()));
			tag.putString("VolumeDeltaM3PerExtent", definition.volumeDeltaCubicMetersPerExtent().canonical());
			var k = definition.kinetics();
			tag.putLongArray("Kinetics", new long[]{Double.doubleToRawLongBits(k.forwardRate()), Double.doubleToRawLongBits(k.reverseRate()),
					Double.doubleToRawLongBits(k.forwardActivationJMol()), Double.doubleToRawLongBits(k.reverseActivationJMol()),
					Double.doubleToRawLongBits(k.referenceTemperatureK())});
			reactions.add(tag);
		}
		root.put("Reactions", reactions);
		ListTag transfers = new ListTag();
		for (var definition : registry.gasLiquidMassTransfers()) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Id", definition.id()); tag.putString("Species", definition.speciesId());
			tag.putString("LiquidPhase", definition.liquidPhaseId()); tag.putString("GasComponent", definition.gasComponentId());
			tag.putLong("ReferenceTemperatureKBits", Double.doubleToRawLongBits(definition.referenceTemperatureK()));
			tag.putLong("HenrySolubilityMolPerM3PaBits", Double.doubleToRawLongBits(definition.henrySolubilityMolPerM3Pa()));
			tag.putLong("TemperatureSlopeKBits", Double.doubleToRawLongBits(definition.temperatureSlopeK()));
			tag.putLong("LiquidFilmVelocityMPerSBits", Double.doubleToRawLongBits(definition.liquidFilmVelocityMPerS()));
			tag.putLong("GasFilmVelocityMPerSBits", Double.doubleToRawLongBits(definition.gasFilmVelocityMPerS()));
			tag.putLong("MinimumTemperatureKBits",Double.doubleToRawLongBits(definition.minimumTemperatureK()));
			tag.putLong("MaximumTemperatureKBits",Double.doubleToRawLongBits(definition.maximumTemperatureK()));
			transfers.add(tag);
		}
		root.put("GasLiquidMassTransfers", transfers);
		return root;
	}

	private static List<GasLiquidMassTransferDefinition> decodeMassTransfers(ListTag list) throws IOException {
		if (list.size() > ChemicalLimits.MAX_GAS_LIQUID_MASS_TRANSFERS) throw new IOException("Gas/liquid mass transfer definitions exceed snapshot budget");
		var result = new ArrayList<GasLiquidMassTransferDefinition>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = compound(list, i, "gas/liquid mass transfer");
			requireAll(tag, Map.ofEntries(Map.entry("Id",Tag.TAG_STRING),Map.entry("Species",Tag.TAG_STRING),
					Map.entry("LiquidPhase",Tag.TAG_STRING),Map.entry("GasComponent",Tag.TAG_STRING),
					Map.entry("ReferenceTemperatureKBits",Tag.TAG_LONG),Map.entry("HenrySolubilityMolPerM3PaBits",Tag.TAG_LONG),
					Map.entry("TemperatureSlopeKBits",Tag.TAG_LONG),Map.entry("LiquidFilmVelocityMPerSBits",Tag.TAG_LONG),
					Map.entry("GasFilmVelocityMPerSBits",Tag.TAG_LONG),Map.entry("MinimumTemperatureKBits",Tag.TAG_LONG),
					Map.entry("MaximumTemperatureKBits",Tag.TAG_LONG)));
			result.add(new GasLiquidMassTransferDefinition(tag.getStringOr("Id", ""), tag.getStringOr("Species", ""),
					tag.getStringOr("LiquidPhase", ""), tag.getStringOr("GasComponent", ""),
					Double.longBitsToDouble(tag.getLongOr("ReferenceTemperatureKBits", 0)),
					Double.longBitsToDouble(tag.getLongOr("HenrySolubilityMolPerM3PaBits", 0)),
					Double.longBitsToDouble(tag.getLongOr("TemperatureSlopeKBits", 0)),
					Double.longBitsToDouble(tag.getLongOr("LiquidFilmVelocityMPerSBits", 0)),
					Double.longBitsToDouble(tag.getLongOr("GasFilmVelocityMPerSBits", 0)),
					Double.longBitsToDouble(tag.getLongOr("MinimumTemperatureKBits",0)),
					Double.longBitsToDouble(tag.getLongOr("MaximumTemperatureKBits",0))));
		}
		return List.copyOf(result);
	}

	private static List<ChemicalSpecies> decodeSpecies(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > ChemicalLimits.MAX_SPECIES) throw new IOException("Invalid snapshot species count");
		List<ChemicalSpecies> result = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = compound(list, i, "species");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "Charge", Tag.TAG_INT, "MolarMassKgPerMol", Tag.TAG_STRING,
					"Color", Tag.TAG_INT, "Composition", Tag.TAG_LIST, "Diffusivity", Tag.TAG_LONG, "EffectiveVolume", Tag.TAG_LONG,
					"LiquidProperties", Tag.TAG_LIST));
			ListTag encodedProperties = tag.getListOrEmpty("LiquidProperties");
			if (encodedProperties.size() > 1) throw new IOException("Multiple liquid properties for one species");
			ChemicalSpecies.LiquidProperties properties = null;
			if (!encodedProperties.isEmpty()) {
				CompoundTag value = compound(encodedProperties, 0, "liquid properties");
				requireAll(value, Map.of("Density", Tag.TAG_LONG, "HeatCapacity", Tag.TAG_LONG,
						"Conductivity", Tag.TAG_LONG, "Viscosity", Tag.TAG_LONG));
				properties = new ChemicalSpecies.LiquidProperties(
						Double.longBitsToDouble(value.getLongOr("Density", 0)),
						Double.longBitsToDouble(value.getLongOr("HeatCapacity", 0)),
						Double.longBitsToDouble(value.getLongOr("Conductivity", 0)),
						Double.longBitsToDouble(value.getLongOr("Viscosity", 0)));
			}
			result.add(new ChemicalSpecies(tag.getStringOr("Id", ""), decodeIntegerMap(tag.getListOrEmpty("Composition")),
					tag.getIntOr("Charge", Integer.MIN_VALUE), ExactRational.parse(tag.getStringOr("MolarMassKgPerMol", "")),
					tag.getIntOr("Color", 0), nullableDouble(tag.getLongOr("Diffusivity", 0)), nullableDouble(tag.getLongOr("EffectiveVolume", 0)), properties));
		}
		return result;
	}

	private static List<ChemicalPhaseDefinition> decodePhases(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > ChemicalLimits.MAX_PHASES) throw new IOException("Invalid snapshot phase count");
		List<ChemicalPhaseDefinition> result = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = compound(list, i, "phase");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "Material", Tag.TAG_STRING,
					"Allowed", Tag.TAG_LIST, "DefaultConcentrationMolPerM3", Tag.TAG_LIST, "ViscosityCorrection", Tag.TAG_LIST));
			List<String> allowed = new ArrayList<>();
			ListTag encodedAllowed = tag.getListOrEmpty("Allowed");
			for (int entry = 0; entry < encodedAllowed.size(); entry++) {
				Tag value = encodedAllowed.get(entry);
				if (!(value instanceof StringTag text)) throw new IOException("Invalid allowed species entry");
				allowed.add(text.asString().orElseThrow());
			}
			ListTag encodedCorrection = tag.getListOrEmpty("ViscosityCorrection");
            if (encodedCorrection.size() > 1) throw new IOException("Invalid viscosity correction count");
            ChemicalPhaseDefinition.ViscosityCorrection correction = null;
            if (!encodedCorrection.isEmpty()) {
                CompoundTag value = compound(encodedCorrection, 0, "viscosity correction");
                requireAll(value, Map.of("A", Tag.TAG_LONG, "B", Tag.TAG_LONG, "MaximumMolality", Tag.TAG_LONG,
                        "ReferenceTemperature", Tag.TAG_LONG, "TemperatureTolerance", Tag.TAG_LONG));
                correction = new ChemicalPhaseDefinition.ViscosityCorrection(
                        Double.longBitsToDouble(value.getLongOr("A", 0)), Double.longBitsToDouble(value.getLongOr("B", 0)),
                        Double.longBitsToDouble(value.getLongOr("MaximumMolality", 0)),
                        Double.longBitsToDouble(value.getLongOr("ReferenceTemperature", 0)),
                        Double.longBitsToDouble(value.getLongOr("TemperatureTolerance", 0)));
            }
            result.add(new ChemicalPhaseDefinition(tag.getStringOr("Id", ""), tag.getStringOr("Material", ""),
                    allowed, decodeRationalMap(tag.getListOrEmpty("DefaultConcentrationMolPerM3")), correction));
		}
		return result;
	}

	private static List<ChemicalReaction> decodeReactions(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > ChemicalLimits.MAX_REACTIONS) throw new IOException("Invalid snapshot reaction count");
		List<ChemicalReaction> result = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = compound(list, i, "reaction");
			requireAll(tag, Map.of("Id", Tag.TAG_STRING, "Kind", Tag.TAG_STRING, "Phase", Tag.TAG_STRING,
					"Reactants", Tag.TAG_LIST, "Products", Tag.TAG_LIST, "VolumeDeltaM3PerExtent", Tag.TAG_STRING, "Kinetics", Tag.TAG_LONG_ARRAY));
			result.add(new ChemicalReaction(tag.getStringOr("Id", ""),
					ChemicalReaction.Kind.valueOf(tag.getStringOr("Kind", "")), tag.getStringOr("Phase", ""),
					decodeIntegerMap(tag.getListOrEmpty("Reactants")), decodeIntegerMap(tag.getListOrEmpty("Products")),
					ExactRational.parse(tag.getStringOr("VolumeDeltaM3PerExtent", "")), decodeKinetics(tag)));
		}
		return result;
	}

	private static ChemicalReaction.Kinetics decodeKinetics(CompoundTag tag) throws IOException {
		long[] k = tag.getLongArray("Kinetics").orElseThrow();
		if (k.length != 5) throw new IOException("Reaction kinetic constants are incomplete");
		return new ChemicalReaction.Kinetics(Double.longBitsToDouble(k[0]), Double.longBitsToDouble(k[1]),
				Double.longBitsToDouble(k[2]), Double.longBitsToDouble(k[3]), Double.longBitsToDouble(k[4]));
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

	private static CompoundTag compound(ListTag list, int index, String context) throws IOException {
		Tag tag = list.get(index);
		if (!(tag instanceof CompoundTag compound)) throw new IOException("Invalid " + context + " entry");
		return compound;
	}
	private static Double nullableDouble(long bits) { double value=Double.longBitsToDouble(bits);return Double.isNaN(value)?null:value; }

	private static void requireAll(CompoundTag tag, Map<String, Byte> fields) throws IOException {
		StrictNbtFields.requireExact(tag, fields, "chemical registry");
	}
}
