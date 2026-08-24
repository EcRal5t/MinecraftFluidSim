package com.example.granular;

import com.example.chemistry.ExactRational;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Strict JSON codecs for data/&lt;namespace&gt;/realwater/{particulate_materials,granular_carriers}. */
public final class GranularDefinitionCodec {
	public static final String MATERIALS_PATH = "realwater/particulate_materials";
	public static final String CARRIERS_PATH = "realwater/granular_carriers";
	public static final String REACTIONS_PATH = "realwater/solid_liquid_reactions";

	private GranularDefinitionCodec() { }

	public static ParticulateMaterial decodeMaterial(String stableId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "reference_density", "primary_grain_diameter_meters", "rest_porosity_q",
				"repose_angle_degrees", "static_friction", "dynamic_friction", "restitution",
				"critical_shields", "erosion_rate_svq_per_second", "maximum_active_parcel_svq",
				"base_argb", "display_texture", "reactive");
		return new ParticulateMaterial(stableId, finiteDouble(root, "reference_density"),
				finiteDouble(root, "primary_grain_diameter_meters"), exactInt(root, "rest_porosity_q"),
				Math.toRadians(finiteDouble(root, "repose_angle_degrees")), finiteDouble(root, "static_friction"),
				finiteDouble(root, "dynamic_friction"), finiteDouble(root, "restitution"),
				finiteDouble(root, "critical_shields"), exactLong(root, "erosion_rate_svq_per_second"),
				exactLong(root, "maximum_active_parcel_svq"), color(root, "base_argb"),
				string(root, "display_texture"), bool(root, "reactive"));
	}

	public static GranularCarrier decodeCarrier(String liquidMaterialId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "dynamic_viscosity", "maximum_reynolds");
		return new GranularCarrier(liquidMaterialId, finiteDouble(root, "dynamic_viscosity"),
				finiteDouble(root, "maximum_reynolds"));
	}

	public static SolidLiquidReaction decodeReaction(String stableId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "liquid_phase", "aqueous_stoichiometry", "solid_material", "solid_composition",
				"saturation_capacity_extent_per_bv", "solid_extent_per_batch", "solid_volume_svq_per_batch",
				"accessible_surface_svq_per_cell_step", "reversible");
		return new SolidLiquidReaction(stableId, string(root, "liquid_phase"), integerMap(root, "aqueous_stoichiometry"),
				string(root, "solid_material"), integerMap(root, "solid_composition"),
				ExactRational.parse(string(root, "saturation_capacity_extent_per_bv")),
				exactLong(root, "solid_extent_per_batch"), exactLong(root, "solid_volume_svq_per_batch"),
				exactLong(root, "accessible_surface_svq_per_cell_step"), bool(root, "reversible"));
	}

	private static JsonObject object(Reader input) {
		JsonElement root;
		try { root = JsonParser.parseReader(input); }
		catch (RuntimeException invalid) { throw new IllegalArgumentException("Malformed granular JSON", invalid); }
		if (!root.isJsonObject()) throw new IllegalArgumentException("Granular definition root must be an object");
		return root.getAsJsonObject();
	}

	private static double finiteDouble(JsonObject root, String name) {
		String text = string(root, name);
		double value;
		try { value = Double.parseDouble(text); }
		catch (NumberFormatException invalid) { throw new IllegalArgumentException(name + " must be a decimal string", invalid); }
		if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
		return value;
	}

	private static int exactInt(JsonObject root, String name) {
		long value = exactLong(root, name);
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(name + " is outside the integer range");
		}
		return (int) value;
	}

	private static Map<String, Integer> integerMap(JsonObject root, String name) {
		JsonElement element = required(root, name);
		if (!element.isJsonObject()) throw new IllegalArgumentException(name + " must be an object");
		TreeMap<String, Integer> result = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
				throw new IllegalArgumentException(name + "." + entry.getKey() + " must be an integer string");
			}
			JsonObject wrapper = new JsonObject(); wrapper.add("value", entry.getValue());
			result.put(entry.getKey(), exactInt(wrapper, "value"));
		}
		return Map.copyOf(result);
	}

	private static long exactLong(JsonObject root, String name) {
		String text = string(root, name);
		if (!text.matches("-?(0|[1-9][0-9]*)")) throw new IllegalArgumentException(name + " must be an integer string");
		try { return Long.parseLong(text); }
		catch (NumberFormatException invalid) { throw new IllegalArgumentException(name + " is outside the long range", invalid); }
	}

	private static int color(JsonObject root, String name) {
		String value = string(root, name);
		if (!value.matches("#[0-9a-fA-F]{8}")) throw new IllegalArgumentException(name + " must be #AARRGGBB");
		return (int) Long.parseUnsignedLong(value.substring(1), 16);
	}

	private static String string(JsonObject root, String name) {
		JsonElement value = required(root, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(name + " must be a string");
		}
		return value.getAsString();
	}

	private static boolean bool(JsonObject root, String name) {
		JsonElement value = required(root, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException(name + " must be a JSON boolean");
		}
		return value.getAsBoolean();
	}

	private static JsonElement required(JsonObject root, String name) {
		JsonElement value = root.get(name);
		if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing field " + name);
		return value;
	}

	private static void requireOnly(JsonObject root, String... fields) {
		Set<String> allowed = Set.of(fields);
		for (String field : root.keySet()) if (!allowed.contains(field)) {
			throw new IllegalArgumentException("Unknown granular definition field " + field);
		}
		for (String field : fields) required(root, field);
	}
}
