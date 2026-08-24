package com.example.thermal;

import com.example.chemistry.ExactRational;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Set;

/** Strict JSON codecs for world-snapshotted M9 thermal definitions. All floating values are JSON strings. */
public final class ThermalDefinitionCodec {
	public static final String MATERIALS_PATH = "realwater/thermal_materials";
	public static final String SPECIES_PATH = "realwater/thermal_species";
	public static final String TRANSITIONS_PATH = "realwater/phase_transitions";

	private ThermalDefinitionCodec() { }

	public static ThermalMaterialDefinition decodeMaterial(String stableId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "carrier_kind", "carrier_id", "reference_temperature_k",
				"reference_specific_energy_eu_per_mu", "conductivity_eu_per_second_meter_k", "caloric_segments");
		JsonElement segmentElement = required(root, "caloric_segments");
		if (!segmentElement.isJsonArray()) throw new IllegalArgumentException("caloric_segments must be an array");
		JsonArray array = segmentElement.getAsJsonArray();
		if (array.isEmpty() || array.size() > ThermalLimits.MAX_SEGMENTS_PER_MATERIAL) {
			throw new IllegalArgumentException("caloric_segments are empty or over budget");
		}
		ArrayList<ThermalMaterialDefinition.CaloricSegment> segments = new ArrayList<>(array.size());
		for (JsonElement element : array) {
			if (!element.isJsonObject()) throw new IllegalArgumentException("caloric segment must be an object");
			JsonObject segment = element.getAsJsonObject();
			requireOnly(segment, "minimum_temperature_k", "maximum_temperature_k", "specific_heat_eu_per_mu_k");
			segments.add(new ThermalMaterialDefinition.CaloricSegment(
					finiteDouble(segment, "minimum_temperature_k"), finiteDouble(segment, "maximum_temperature_k"),
					finiteDouble(segment, "specific_heat_eu_per_mu_k")));
		}
		ThermalMaterialDefinition.CarrierKind kind;
		try { kind = ThermalMaterialDefinition.CarrierKind.valueOf(string(root, "carrier_kind")); }
		catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unknown thermal carrier_kind", invalid); }
		return new ThermalMaterialDefinition(stableId, kind, string(root, "carrier_id"),
				finiteDouble(root, "reference_temperature_k"),
				finiteDouble(root, "reference_specific_energy_eu_per_mu"),
				finiteDouble(root, "conductivity_eu_per_second_meter_k"), segments);
	}

	public static ThermalSpeciesOffset decodeSpecies(String speciesStableId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "species_id", "host_thermal_material", "reference_internal_energy_eu_per_au");
		return new ThermalSpeciesOffset(speciesStableId, string(root, "species_id"), string(root, "host_thermal_material"),
				ExactRational.parse(string(root, "reference_internal_energy_eu_per_au")));
	}

	public static ThermalPhaseTransition decodeTransition(String stableId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "lower_energy_material", "upper_energy_material", "equilibrium_temperature_k",
				"lower_volume_svq_per_batch", "upper_volume_svq_per_batch", "mass_mu_per_batch",
				"interface_requirement", "minimum_pressure_pa", "maximum_pressure_pa");
		ThermalPhaseTransition.InterfaceRequirement requirement;
		try { requirement = ThermalPhaseTransition.InterfaceRequirement.valueOf(string(root, "interface_requirement")); }
		catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unknown interface_requirement", invalid); }
		return new ThermalPhaseTransition(stableId, string(root, "lower_energy_material"),
				string(root, "upper_energy_material"), finiteDouble(root, "equilibrium_temperature_k"),
				exactLong(root, "lower_volume_svq_per_batch"), exactLong(root, "upper_volume_svq_per_batch"),
				ExactRational.parse(string(root, "mass_mu_per_batch")), requirement,
				finiteDouble(root, "minimum_pressure_pa"), finiteDouble(root, "maximum_pressure_pa"));
	}

	private static JsonObject object(Reader input) {
		JsonElement root;
		try { root = JsonParser.parseReader(input); }
		catch (RuntimeException invalid) { throw new IllegalArgumentException("Malformed thermal JSON", invalid); }
		if (!root.isJsonObject()) throw new IllegalArgumentException("Thermal definition root must be an object");
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

	private static long exactLong(JsonObject root, String name) {
		String text = string(root, name);
		if (!text.matches("-?(0|[1-9][0-9]*)")) throw new IllegalArgumentException(name + " must be an integer string");
		try { return Long.parseLong(text); }
		catch (NumberFormatException invalid) { throw new IllegalArgumentException(name + " is outside the long range", invalid); }
	}

	private static String string(JsonObject root, String name) {
		JsonElement value = required(root, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(name + " must be a string");
		}
		return value.getAsString();
	}

	private static JsonElement required(JsonObject root, String name) {
		JsonElement value = root.get(name);
		if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing field " + name);
		return value;
	}

	private static void requireOnly(JsonObject root, String... fields) {
		Set<String> allowed = Set.of(fields);
		for (String field : root.keySet()) if (!allowed.contains(field)) {
			throw new IllegalArgumentException("Unknown thermal definition field " + field);
		}
		for (String field : fields) required(root, field);
	}
}
