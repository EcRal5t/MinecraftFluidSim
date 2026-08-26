package cc.ecisr.mmm.thermal;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict datapack loader whose resource namespace/path supplies each stable thermal definition id. */
public final class ThermalDataPackLoader {
	private ThermalDataPackLoader() { }

	public static ThermalRegistry load(
			ResourceManager resources, String chemicalRegistryFingerprint, String granularRegistryFingerprint,
			String gasRegistryFingerprint
	) throws IOException {
		if (resources == null) throw new IOException("Thermal datapack resource manager is missing");
		List<ThermalMaterialDefinition> materials = loadCategory(resources, ThermalDefinitionCodec.MATERIALS_PATH,
				ThermalDefinitionCodec::decodeMaterial, true);
		List<ThermalSpeciesOffset> offsets = chemicalRegistryFingerprint.isEmpty() ? List.of()
				: loadCategory(resources, ThermalDefinitionCodec.SPECIES_PATH,
						ThermalDefinitionCodec::decodeSpecies, false);
		List<ThermalPhaseTransition> transitions = loadCategory(resources, ThermalDefinitionCodec.TRANSITIONS_PATH,
				ThermalDefinitionCodec::decodeTransition, false);
		List<ThermalBoundaryDefinition> boundaries = loadCategory(resources, ThermalDefinitionCodec.BOUNDARIES_PATH,
				ThermalDefinitionCodec::decodeBoundary, false);
		List<GasLiquidSpeciesPair> pairs = gasRegistryFingerprint.isEmpty() ? List.of()
				: loadCategory(resources, ThermalDefinitionCodec.PAIRS_PATH,
						ThermalDefinitionCodec::decodePair, false);
		try { return new ThermalRegistry(materials, offsets, transitions, boundaries, pairs,
				chemicalRegistryFingerprint, granularRegistryFingerprint, gasRegistryFingerprint); }
		catch (RuntimeException invalid) {
			throw new IOException("Thermal datapack registry is invalid: " + invalid.getMessage(), invalid);
		}
	}

	private static <T> List<T> loadCategory(
			ResourceManager manager, String prefix, Decoder<T> decoder, boolean required
	) throws IOException {
		Map<Identifier, Resource> found = manager.listResources(prefix, id -> id.getPath().endsWith(".json"));
		if (found.isEmpty()) {
			if (required) throw new IOException("Thermal datapack category is empty: " + prefix);
			return List.of();
		}
		ArrayList<Map.Entry<Identifier, Resource>> ordered = new ArrayList<>(found.entrySet());
		ordered.sort(Map.Entry.comparingByKey());
		ArrayList<T> definitions = new ArrayList<>(ordered.size());
		Set<String> stableIds = new HashSet<>();
		for (Map.Entry<Identifier, Resource> entry : ordered) {
			Identifier resourceId = entry.getKey();
			String path = resourceId.getPath(), requiredPrefix = prefix + "/";
			if (!path.startsWith(requiredPrefix) || !path.endsWith(".json")) {
				throw new IOException("Thermal definition is outside its category: " + resourceId);
			}
			String stablePath = path.substring(requiredPrefix.length(), path.length() - ".json".length());
			Identifier stable = Identifier.tryBuild(resourceId.getNamespace(), stablePath);
			if (stable == null || stablePath.isEmpty() || !stableIds.add(stable.toString())) {
				throw new IOException("Invalid or duplicate thermal stable id derived from " + resourceId);
			}
			try (Reader reader = entry.getValue().openAsReader()) {
				definitions.add(decoder.decode(stable.toString(), reader));
			} catch (RuntimeException invalid) {
				throw new IOException("Invalid thermal definition " + resourceId + " from pack "
						+ entry.getValue().sourcePackId() + ": " + invalid.getMessage(), invalid);
			}
		}
		definitions.sort(Comparator.comparing(definition -> switch (definition) {
			case ThermalMaterialDefinition value -> value.id();
			case ThermalSpeciesOffset value -> value.id();
			case ThermalPhaseTransition value -> value.id();
			case ThermalBoundaryDefinition value -> value.id();
			case GasLiquidSpeciesPair value -> value.id();
			default -> throw new IllegalStateException("Unknown thermal definition type");
		}));
		return List.copyOf(definitions);
	}

	@FunctionalInterface
	private interface Decoder<T> { T decode(String stableId, Reader reader); }
}
