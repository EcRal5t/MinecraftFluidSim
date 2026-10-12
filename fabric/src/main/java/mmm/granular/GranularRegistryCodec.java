package mmm.granular;

import mmm.chemistry.ChemicalRegistry;
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
import java.util.TreeMap;

/**
 * Canonical snapshot codec for the granular registry. The world capability bundle is the only place a
 * registry snapshot is stored, so this codec has no world directory of its own.
 */
public final class GranularRegistryCodec {
	private GranularRegistryCodec() { }

	private static final String SCHEMA = "granular-registry";
	private static final long MAX_BYTES = 4L * 1024L * 1024L;

	/** Canonical bytes of the strict snapshot; the bundle stores exactly these so checksums agree. */
	public static byte[] snapshotBytes(GranularRegistry registry) throws IOException {
		if (registry == null) throw new IllegalArgumentException("Granular registry snapshot is missing");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		NbtIo.writeCompressed(encode(registry), output);
		return output.toByteArray();
	}

	public static void writeSnapshot(Path path, GranularRegistry registry) throws IOException {
		if (path == null || registry == null) throw new IllegalArgumentException("Granular registry snapshot write is incomplete");
		Files.write(path, snapshotBytes(registry));
	}

	public static GranularRegistry readSnapshot(Path path, ChemicalRegistry chemicalRegistry) throws IOException {
		if (path == null) throw new IllegalArgumentException("Granular registry snapshot path is missing");
		if (chemicalRegistry == null) throw new IllegalArgumentException("Granular registry snapshot is missing its chemical registry");
		CompoundTag root;
		try { root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_BYTES)); }
		catch (RuntimeException invalid) { throw new IOException("Unreadable granular registry snapshot " + path, invalid); }
		requireAll(root, Map.of("Schema", Tag.TAG_STRING, "SvqPerBv", Tag.TAG_LONG, "PorosityScale", Tag.TAG_INT,
				"Fingerprint", Tag.TAG_STRING, "Canonical", Tag.TAG_BYTE_ARRAY,
				"ChemicalFingerprint", Tag.TAG_STRING, "Materials", Tag.TAG_LIST,
				"Carriers", Tag.TAG_LIST, "Reactions", Tag.TAG_LIST));
		if (!root.getStringOr("Schema", "").equals(SCHEMA) || root.getLongOr("SvqPerBv", -1L) != SolidVolume.SVQ_PER_BV
				|| root.getIntOr("PorosityScale", -1) != GranularLimits.POROSITY_SCALE) {
			throw new IOException("Unsupported granular registry schema or unit scale");
		}
		try {
			String chemicalFingerprint = root.getStringOr("ChemicalFingerprint", "");
			List<SolidLiquidReaction> reactions = decodeReactions(root.getListOrEmpty("Reactions"));
			if (!reactions.isEmpty() && !chemicalRegistry.fingerprint().equals(chemicalFingerprint)) {
				throw new IOException("Granular registry chemical dependency fingerprint mismatch");
			}
			GranularRegistry registry = new GranularRegistry(decodeMaterials(root.getListOrEmpty("Materials")),
					decodeCarriers(root.getListOrEmpty("Carriers")), reactions, reactions.isEmpty() ? null : chemicalRegistry);
			if (!registry.chemicalRegistryFingerprint().equals(chemicalFingerprint)) {
				throw new IOException("Granular registry chemical dependency is non-canonical");
			}
			if (!registry.fingerprint().equals(root.getStringOr("Fingerprint", ""))
					|| !java.util.Arrays.equals(registry.canonicalBytes(), root.getByteArray("Canonical").orElseThrow())) {
				throw new IOException("Granular registry fingerprint/canonical bytes mismatch");
			}
			return registry;
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid granular registry snapshot: " + invalid.getMessage(), invalid);
		}
	}

	private static CompoundTag encode(GranularRegistry registry) {
		CompoundTag root = new CompoundTag();
		root.putString("Schema", SCHEMA); root.putLong("SvqPerBv", SolidVolume.SVQ_PER_BV);
		root.putInt("PorosityScale", GranularLimits.POROSITY_SCALE);
		root.putString("Fingerprint", registry.fingerprint()); root.putByteArray("Canonical", registry.canonicalBytes());
		root.putString("ChemicalFingerprint", registry.chemicalRegistryFingerprint());
		ListTag materials = new ListTag();
		for (ParticulateMaterial material : registry.materials()) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Id", material.id()); tag.putLong("DensityBits", bits(material.referenceDensity()));
			tag.putLong("GrainBits", bits(material.primaryGrainDiameterMeters())); tag.putInt("PorosityQ", material.restPorosityQ());
			tag.putLong("ReposeBits", bits(material.reposeAngleRadians())); tag.putLong("StaticFrictionBits", bits(material.staticFriction()));
			tag.putLong("DynamicFrictionBits", bits(material.dynamicFriction())); tag.putLong("RestitutionBits", bits(material.restitution()));
			tag.putLong("CriticalShieldsBits", bits(material.criticalShields()));
			tag.putLong("ErosionRateSvq", material.erosionRateSvqPerSecond());
			tag.putLong("MaximumParcelSvq", material.maximumActiveParcelSvq()); tag.putInt("Color", material.baseArgb());
			tag.putString("Texture", material.displayTextureId()); tag.putBoolean("Reactive", material.reactive()); materials.add(tag);
		}
		root.put("Materials", materials);
		ListTag carriers = new ListTag();
		for (GranularCarrier carrier : registry.carriers()) {
			CompoundTag tag = new CompoundTag(); tag.putString("Material", carrier.liquidMaterialId());
			tag.putLong("MaximumReynoldsBits", bits(carrier.maximumReynolds())); carriers.add(tag);
		}
		root.put("Carriers", carriers);
		ListTag reactions = new ListTag();
		for (SolidLiquidReaction reaction : registry.reactions()) {
			CompoundTag tag = new CompoundTag(); tag.putString("Id", reaction.id());
			tag.putString("Phase", reaction.liquidPhaseId()); tag.put("Aqueous", encodeSide(reaction.aqueousStoichiometry()));
			tag.putString("SolidMaterial", reaction.solidMaterialId()); tag.put("Solid", encodeSide(reaction.solidComposition()));
			tag.putString("Capacity", reaction.saturationCapacityExtentPerBv().canonical());
			tag.putLong("ExtentBatch", reaction.solidExtentPerBatch());
			tag.putLong("SolidSvqBatch", reaction.solidVolumeSvqPerBatch());
			tag.putLong("SurfaceSvq", reaction.accessibleSurfaceSvqPerCellStep());
			tag.putBoolean("Reversible", reaction.reversible()); reactions.add(tag);
		}
		root.put("Reactions", reactions);
		return root;
	}

	private static ListTag encodeSide(Map<String, Integer> side) {
		ListTag list = new ListTag();
		for (Map.Entry<String, Integer> entry : new TreeMap<>(side).entrySet()) {
			CompoundTag term = new CompoundTag(); term.putString("Species", entry.getKey());
			term.putInt("Coefficient", entry.getValue()); list.add(term);
		}
		return list;
	}

	private static List<ParticulateMaterial> decodeMaterials(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > GranularLimits.MAX_MATERIALS) throw new IOException("Invalid granular material count");
		List<ParticulateMaterial> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "particulate material");
			requireAll(tag, Map.ofEntries(
					Map.entry("Id", Tag.TAG_STRING), Map.entry("DensityBits", Tag.TAG_LONG), Map.entry("GrainBits", Tag.TAG_LONG),
					Map.entry("PorosityQ", Tag.TAG_INT), Map.entry("ReposeBits", Tag.TAG_LONG),
					Map.entry("StaticFrictionBits", Tag.TAG_LONG), Map.entry("DynamicFrictionBits", Tag.TAG_LONG),
					Map.entry("RestitutionBits", Tag.TAG_LONG), Map.entry("CriticalShieldsBits", Tag.TAG_LONG),
					Map.entry("ErosionRateSvq", Tag.TAG_LONG), Map.entry("MaximumParcelSvq", Tag.TAG_LONG),
					Map.entry("Color", Tag.TAG_INT), Map.entry("Texture", Tag.TAG_STRING), Map.entry("Reactive", Tag.TAG_BYTE)));
			result.add(new ParticulateMaterial(tag.getStringOr("Id", ""), value(tag, "DensityBits"), value(tag, "GrainBits"),
					tag.getIntOr("PorosityQ", -1), value(tag, "ReposeBits"), value(tag, "StaticFrictionBits"),
					value(tag, "DynamicFrictionBits"), value(tag, "RestitutionBits"), value(tag, "CriticalShieldsBits"),
					tag.getLongOr("ErosionRateSvq", -1L), tag.getLongOr("MaximumParcelSvq", -1L), tag.getIntOr("Color", 0),
					tag.getStringOr("Texture", ""), tag.getBooleanOr("Reactive", false)));
		}
		return List.copyOf(result);
	}

	private static List<GranularCarrier> decodeCarriers(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > GranularLimits.MAX_CARRIERS) throw new IOException("Invalid granular carrier count");
		List<GranularCarrier> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "granular carrier");
			requireAll(tag, Map.of("Material", Tag.TAG_STRING, "MaximumReynoldsBits", Tag.TAG_LONG));
			result.add(new GranularCarrier(tag.getStringOr("Material", ""), value(tag, "MaximumReynoldsBits")));
		}
		return List.copyOf(result);
	}

	private static List<SolidLiquidReaction> decodeReactions(ListTag list) throws IOException {
		if (list.size() > GranularLimits.MAX_REACTIONS) throw new IOException("Invalid solid-liquid reaction count");
		List<SolidLiquidReaction> result = new ArrayList<>(list.size());
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = compound(list, index, "solid-liquid reaction");
			requireAll(tag, Map.ofEntries(Map.entry("Id", Tag.TAG_STRING), Map.entry("Phase", Tag.TAG_STRING),
					Map.entry("Aqueous", Tag.TAG_LIST), Map.entry("SolidMaterial", Tag.TAG_STRING),
					Map.entry("Solid", Tag.TAG_LIST), Map.entry("Capacity", Tag.TAG_STRING),
					Map.entry("ExtentBatch", Tag.TAG_LONG), Map.entry("SolidSvqBatch", Tag.TAG_LONG),
					Map.entry("SurfaceSvq", Tag.TAG_LONG),
					Map.entry("Reversible", Tag.TAG_BYTE)));
			result.add(new SolidLiquidReaction(tag.getStringOr("Id", ""), tag.getStringOr("Phase", ""),
					decodeSide(tag.getListOrEmpty("Aqueous")), tag.getStringOr("SolidMaterial", ""),
					decodeSide(tag.getListOrEmpty("Solid")), ExactRational.parse(tag.getStringOr("Capacity", "")),
					tag.getLongOr("ExtentBatch", -1L), tag.getLongOr("SolidSvqBatch", -1L),
					tag.getLongOr("SurfaceSvq", -1L),
					tag.getBooleanOr("Reversible", false)));
		}
		return List.copyOf(result);
	}

	private static Map<String, Integer> decodeSide(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES) {
			throw new IOException("Invalid solid-liquid side entry count");
		}
		TreeMap<String, Integer> result = new TreeMap<>();
		for (int index = 0; index < list.size(); index++) {
			CompoundTag term = compound(list, index, "solid-liquid term");
			requireAll(term, Map.of("Species", Tag.TAG_STRING, "Coefficient", Tag.TAG_INT));
			String species = term.getStringOr("Species", "");
			if (result.put(species, term.getIntOr("Coefficient", -1)) != null) {
				throw new IOException("Duplicate solid-liquid species " + species);
			}
		}
		return Map.copyOf(result);
	}

	private static CompoundTag compound(ListTag list, int index, String context) throws IOException {
		Tag tag = list.get(index);
		if (!(tag instanceof CompoundTag compound)) throw new IOException("Invalid " + context + " entry");
		return compound;
	}
	private static void requireAll(CompoundTag tag, Map<String, Byte> fields) throws IOException {
		StrictNbtFields.requireExact(tag, fields, "granular registry");
	}
	private static long bits(double value) { return Double.doubleToRawLongBits(value); }
	private static double value(CompoundTag tag, String field) { return Double.longBitsToDouble(tag.getLongOr(field, 0L)); }
}
