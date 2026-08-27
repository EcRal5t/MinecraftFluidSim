package cc.ecisr.mmm.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.Set;

/** Strict JSON codec for one loader-neutral moving-body definition. */
public final class MovingBodyDefinitionCodec {
	public static final String PATH = "mmm/moving_bodies";

	private MovingBodyDefinitionCodec() { }

	public static MovingBodyDefinition decode(String stableId, Reader input) {
		JsonElement parsed;
		try { parsed = JsonParser.parseReader(input); }
		catch (RuntimeException invalid) { throw new IllegalArgumentException("Malformed moving-body JSON", invalid); }
		if (!parsed.isJsonObject()) throw new IllegalArgumentException("Moving-body definition root must be an object");
		JsonObject root = parsed.getAsJsonObject();
		requireOnly(root, "selector_kind", "selector", "coupling_mode", "mass_kg", "geometry_provider");
		MovingBodyDefinition.SelectorKind selectorKind = switch (string(root, "selector_kind")) {
			case "entity_type" -> MovingBodyDefinition.SelectorKind.ENTITY_TYPE;
			case "entity_tag" -> MovingBodyDefinition.SelectorKind.ENTITY_TAG;
			default -> throw new IllegalArgumentException("Unknown moving-body selector_kind");
		};
		MovingBodySurfaceSnapshot.CouplingMode couplingMode = switch (string(root, "coupling_mode")) {
			case "dynamic" -> MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC;
			case "kinematic_external" -> MovingBodySurfaceSnapshot.CouplingMode.KINEMATIC_EXTERNAL;
			default -> throw new IllegalArgumentException("Unknown moving-body coupling_mode");
		};
		MovingBodyDefinition.GeometryProvider provider = switch (string(root, "geometry_provider")) {
			case "axis_aligned_box" -> MovingBodyDefinition.GeometryProvider.AXIS_ALIGNED_BOX;
			default -> throw new IllegalArgumentException("Unknown moving-body geometry_provider");
		};
		JsonElement massElement = required(root, "mass_kg");
		if (!massElement.isJsonPrimitive() || !massElement.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException("mass_kg must be a JSON number");
		}
		return new MovingBodyDefinition(stableId, selectorKind, string(root, "selector"), couplingMode,
				massElement.getAsDouble(), provider);
	}

	private static String string(JsonObject root, String field) {
		JsonElement value = required(root, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(field + " must be a string");
		}
		return value.getAsString();
	}

	private static JsonElement required(JsonObject root, String field) {
		JsonElement value = root.get(field);
		if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing field " + field);
		return value;
	}

	private static void requireOnly(JsonObject root, String... fields) {
		Set<String> allowed = Set.of(fields);
		for (String field : root.keySet()) if (!allowed.contains(field)) {
			throw new IllegalArgumentException("Unknown moving-body definition field " + field);
		}
		for (String field : fields) required(root, field);
	}
}
