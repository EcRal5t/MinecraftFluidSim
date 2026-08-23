package com.example.granular;

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

/** Strict datapack loader whose resource namespace/path determines each stable granular id. */
public final class GranularDataPackLoader {
	private GranularDataPackLoader() { }

	public static GranularRegistry load(ResourceManager resources) throws IOException {
		if (resources == null) throw new IOException("Granular datapack resource manager is missing");
		List<ParticulateMaterial> materials = loadCategory(resources, GranularDefinitionCodec.MATERIALS_PATH,
				GranularDefinitionCodec::decodeMaterial);
		List<GranularCarrier> carriers = loadCategory(resources, GranularDefinitionCodec.CARRIERS_PATH,
				GranularDefinitionCodec::decodeCarrier);
		try { return new GranularRegistry(materials, carriers); }
		catch (RuntimeException invalid) {
			throw new IOException("Granular datapack registry is invalid: " + invalid.getMessage(), invalid);
		}
	}

	private static <T> List<T> loadCategory(ResourceManager manager, String prefix, Decoder<T> decoder) throws IOException {
		Map<Identifier, Resource> found = manager.listResources(prefix, id -> id.getPath().endsWith(".json"));
		if (found.isEmpty()) throw new IOException("Granular datapack category is empty: " + prefix);
		ArrayList<Map.Entry<Identifier, Resource>> ordered = new ArrayList<>(found.entrySet());
		ordered.sort(Map.Entry.comparingByKey());
		ArrayList<T> definitions = new ArrayList<>(ordered.size());
		Set<String> stableIds = new HashSet<>();
		for (Map.Entry<Identifier, Resource> entry : ordered) {
			Identifier resourceId = entry.getKey();
			String path = resourceId.getPath(), requiredPrefix = prefix + "/";
			if (!path.startsWith(requiredPrefix) || !path.endsWith(".json")) {
				throw new IOException("Granular definition is outside its category: " + resourceId);
			}
			String stablePath = path.substring(requiredPrefix.length(), path.length() - ".json".length());
			Identifier stable = Identifier.tryBuild(resourceId.getNamespace(), stablePath);
			if (stable == null || stablePath.isEmpty() || !stableIds.add(stable.toString())) {
				throw new IOException("Invalid or duplicate granular stable id derived from " + resourceId);
			}
			try (Reader reader = entry.getValue().openAsReader()) {
				definitions.add(decoder.decode(stable.toString(), reader));
			} catch (RuntimeException invalid) {
				throw new IOException("Invalid granular definition " + resourceId + " from pack "
						+ entry.getValue().sourcePackId() + ": " + invalid.getMessage(), invalid);
			}
		}
		definitions.sort(Comparator.comparing(definition -> switch (definition) {
			case ParticulateMaterial value -> value.id();
			case GranularCarrier value -> value.liquidMaterialId();
			default -> throw new IllegalStateException("Unknown granular definition type");
		}));
		return List.copyOf(definitions);
	}

	@FunctionalInterface
	private interface Decoder<T> { T decode(String stableId, Reader reader); }
}
