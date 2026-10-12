package mmm.fluid;

import mmm.simulation.persistence.StrictNbtFields;
import mmm.thermal.LiquidThermalExpansion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Canonical snapshot codec for the material registry. The world capability bundle is the only place a
 * registry snapshot is stored, so this codec has no world directory of its own.
 */
public final class MaterialRegistryCodec {
	private MaterialRegistryCodec() { }

	private static final String SCHEMA = "material-registry";
	private static final long MAX_BYTES = 4L * 1_048_576L;

	/** Canonical bytes of the strict snapshot; the bundle stores exactly these so checksums agree. */
	public static byte[] snapshotBytes(MaterialRegistry registry) throws IOException {
		if (registry == null) throw new IllegalArgumentException("Material registry snapshot is missing");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		NbtIo.writeCompressed(encode(registry), output);
		return output.toByteArray();
	}

	public static void writeSnapshot(Path path, MaterialRegistry registry) throws IOException {
		if (path == null || registry == null) throw new IllegalArgumentException("Material registry snapshot write is incomplete");
		Files.write(path, snapshotBytes(registry));
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
				"Materials", Tag.TAG_LIST, "PairTensions", Tag.TAG_LIST));
		if (!root.getStringOr("Schema", "").equals(SCHEMA)
				|| !MaterialRegistry.UNIT_SYSTEM_ID.equals(root.getStringOr("UnitSystem", ""))) {
			throw new IOException("Unsupported material registry schema or unit system");
		}
		try {
			MaterialRegistry registry = new MaterialRegistry(decodeMaterials(root.getListOrEmpty("Materials")),
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
			ListTag expansion = new ListTag();
			if (entry.thermalExpansion() != null) {
				var definition = entry.thermalExpansion();
				CompoundTag value = new CompoundTag();
				value.putLong("ReferenceBits", Double.doubleToRawLongBits(definition.referenceTemperatureK()));
				value.putLong("CoefficientBits", Double.doubleToRawLongBits(definition.volumetricCoefficientPerK()));
				value.putLong("MaximumRelativeBits", Double.doubleToRawLongBits(definition.maximumRelativeDensityChange()));
				expansion.add(value);
			}
			tag.put("ThermalExpansion", expansion);
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
					"ContactAngleBits", Tag.TAG_LONG, "Viscosity", Tag.TAG_LIST, "ThermalExpansion", Tag.TAG_LIST));
			ListTag encodedExpansion = tag.getListOrEmpty("ThermalExpansion");
			if (encodedExpansion.size() > 1) throw new IOException("Multiple thermal expansion definitions for one material");
			LiquidThermalExpansion.Definition expansion = null;
			if (!encodedExpansion.isEmpty()) {
				CompoundTag value = compound(encodedExpansion, 0, "thermal expansion");
				requireAll(value, Map.of("ReferenceBits", Tag.TAG_LONG, "CoefficientBits", Tag.TAG_LONG,
						"MaximumRelativeBits", Tag.TAG_LONG));
				expansion = new LiquidThermalExpansion.Definition(tag.getStringOr("Id", ""),
						Double.longBitsToDouble(value.getLongOr("ReferenceBits", 0L)),
						Double.longBitsToDouble(value.getLongOr("CoefficientBits", 0L)),
						Double.longBitsToDouble(value.getLongOr("MaximumRelativeBits", 0L)));
			}
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
					Double.longBitsToDouble(tag.getLongOr("ContactAngleBits", 0L))), segments, expansion));
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

	private static CompoundTag compound(ListTag list, int index, String context) throws IOException {
		Tag tag = list.get(index);
		if (!(tag instanceof CompoundTag parsed)) throw new IOException("Invalid material " + context + " entry");
		return parsed;
	}

	private static void requireAll(CompoundTag tag, Map<String, Byte> fields) throws IOException {
		StrictNbtFields.requireExact(tag, fields, "material registry");
	}

}
