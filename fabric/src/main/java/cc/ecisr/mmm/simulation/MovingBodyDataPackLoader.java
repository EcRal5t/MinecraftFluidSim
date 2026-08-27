package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.world.MovingBodyDefinition;
import cc.ecisr.mmm.world.MovingBodyDefinitionCodec;
import cc.ecisr.mmm.world.MovingBodyDefinitionRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Map;

/** Strict current-datapack loader; definitions affect only subsequently frozen entity steps. */
final class MovingBodyDataPackLoader {
	private MovingBodyDataPackLoader() { }

	static MovingBodyDefinitionRegistry load(ResourceManager resources) throws IOException {
		if (resources == null) throw new IOException("Moving-body datapack resource manager is missing");
		Map<Identifier, Resource> found = resources.listResources(MovingBodyDefinitionCodec.PATH,
				id -> id.getPath().endsWith(".json"));
		if (found.isEmpty()) throw new IOException("Moving-body definition category is empty");
		ArrayList<Map.Entry<Identifier, Resource>> ordered = new ArrayList<>(found.entrySet());
		ordered.sort(Map.Entry.comparingByKey());
		ArrayList<MovingBodyDefinition> definitions = new ArrayList<>(ordered.size());
		for (Map.Entry<Identifier, Resource> entry : ordered) {
			Identifier resourceId = entry.getKey();
			String requiredPrefix = MovingBodyDefinitionCodec.PATH + "/";
			String path = resourceId.getPath();
			if (!path.startsWith(requiredPrefix) || !path.endsWith(".json")) {
				throw new IOException("Moving-body definition is outside its category: " + resourceId);
			}
			String stablePath = path.substring(requiredPrefix.length(), path.length() - ".json".length());
			Identifier stableId = Identifier.tryBuild(resourceId.getNamespace(), stablePath);
			if (stableId == null || stablePath.isEmpty()) {
				throw new IOException("Moving-body definition has an invalid stable id: " + resourceId);
			}
			try (Reader reader = entry.getValue().openAsReader()) {
				definitions.add(MovingBodyDefinitionCodec.decode(stableId.toString(), reader));
			} catch (RuntimeException invalid) {
				throw new IOException("Invalid moving-body definition " + resourceId + " from pack "
						+ entry.getValue().sourcePackId() + ": " + invalid.getMessage(), invalid);
			}
		}
		try { return new MovingBodyDefinitionRegistry(definitions); }
		catch (RuntimeException invalid) {
			throw new IOException("Moving-body datapack registry is invalid: " + invalid.getMessage(), invalid);
		}
	}
}
