package mmm.chemistry;

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

/** Strict datapack loader whose resource namespace/path determines each stable definition id. */
public final class ChemicalDataPackLoader {
	private ChemicalDataPackLoader() { }

	public static ChemicalRegistry load(ResourceManager resources) throws IOException {
		if (resources == null) throw new IOException("Chemical datapack resource manager is missing");
		List<ChemicalSpecies> species = loadCategory(resources, ChemicalDefinitionCodec.SPECIES_PATH,
				ChemicalDefinitionCodec::decodeSpecies);
		List<ChemicalPhaseDefinition> phases = loadCategory(resources, ChemicalDefinitionCodec.PHASES_PATH,
				ChemicalDefinitionCodec::decodePhase);
		List<ChemicalReaction> reactions = loadCategory(resources, ChemicalDefinitionCodec.REACTIONS_PATH,
				ChemicalDefinitionCodec::decodeReaction);
		try {
			return new ChemicalRegistry(species, phases, reactions);
		} catch (RuntimeException invalid) {
			throw new IOException("Chemical datapack registry is invalid: " + invalid.getMessage(), invalid);
		}
	}

	private static <T> List<T> loadCategory(
			ResourceManager manager, String prefix, Decoder<T> decoder
	) throws IOException {
		Map<Identifier, Resource> found = manager.listResources(prefix,
				id -> id.getPath().endsWith(".json"));
		if (found.isEmpty()) throw new IOException("Chemical datapack category is empty: " + prefix);
		ArrayList<Map.Entry<Identifier, Resource>> ordered = new ArrayList<>(found.entrySet());
		ordered.sort(Map.Entry.comparingByKey());
		ArrayList<T> definitions = new ArrayList<>(ordered.size());
		Set<String> stableIds = new HashSet<>();
		for (Map.Entry<Identifier, Resource> entry : ordered) {
			Identifier resourceId = entry.getKey();
			String path = resourceId.getPath();
			String requiredPrefix = prefix + "/";
			if (!path.startsWith(requiredPrefix) || !path.endsWith(".json")) {
				throw new IOException("Chemical definition is outside its category: " + resourceId);
			}
			String stablePath = path.substring(requiredPrefix.length(), path.length() - ".json".length());
			Identifier stable = Identifier.tryBuild(resourceId.getNamespace(), stablePath);
			if (stable == null || stablePath.isEmpty() || !stableIds.add(stable.toString())) {
				throw new IOException("Invalid or duplicate chemical stable id derived from " + resourceId);
			}
			try (Reader reader = entry.getValue().openAsReader()) {
				definitions.add(decoder.decode(stable.toString(), reader));
			} catch (RuntimeException invalid) {
				throw new IOException("Invalid chemical definition " + resourceId + " from pack "
						+ entry.getValue().sourcePackId() + ": " + invalid.getMessage(), invalid);
			}
		}
		definitions.sort(Comparator.comparing(definition -> switch (definition) {
			case ChemicalSpecies value -> value.id();
			case ChemicalPhaseDefinition value -> value.id();
			case ChemicalReaction value -> value.id();
			default -> throw new IllegalStateException("Unknown chemical definition type");
		}));
		return List.copyOf(definitions);
	}

	@FunctionalInterface
	private interface Decoder<T> {
		T decode(String stableId, Reader reader);
	}
}
