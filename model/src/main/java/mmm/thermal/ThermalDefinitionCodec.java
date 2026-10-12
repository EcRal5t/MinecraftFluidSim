package mmm.thermal;

import mmm.chemistry.ExactRational;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Set;

/** Strict JSON codecs for world-snapshotted thermal definitions. All floating values are JSON strings. */
public final class ThermalDefinitionCodec {
	public static final String MATERIALS_PATH = "mmm/thermal_materials";
	public static final String SPECIES_PATH = "mmm/thermal_species";
	public static final String TRANSITIONS_PATH = "mmm/phase_transitions";
	public static final String BOUNDARIES_PATH = "mmm/thermal_boundaries";
	public static final String PAIRS_PATH = "mmm/gas_liquid_pairs";
	public static final String DEVICES_PATH = "mmm/finite_thermal_devices";

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
		requireOnly(root, "lower_energy_material", "upper_energy_material",
				"lower_volume_svq_per_batch", "upper_volume_svq_per_batch", "mass_mu_per_batch",
				"interface_requirement", "equilibrium_curve");
		ThermalPhaseTransition.InterfaceRequirement requirement;
		try { requirement = ThermalPhaseTransition.InterfaceRequirement.valueOf(string(root, "interface_requirement")); }
		catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unknown interface_requirement", invalid); }
		ArrayList<ThermalPhaseTransition.EquilibriumPoint> curve = new ArrayList<>();
		JsonElement curveElement = required(root, "equilibrium_curve");
		if (!curveElement.isJsonArray()) throw new IllegalArgumentException("equilibrium_curve must be an array");
		JsonArray points = curveElement.getAsJsonArray();
		if (points.size() < 2 || points.size() > ThermalLimits.MAX_EQUILIBRIUM_CURVE_POINTS) {
			throw new IllegalArgumentException("equilibrium_curve size is invalid");
		}
		for (JsonElement element : points) {
			if (!element.isJsonObject()) throw new IllegalArgumentException("equilibrium_curve point must be an object");
			JsonObject point = element.getAsJsonObject();
			requireOnly(point, "pressure_pa", "temperature_k");
			curve.add(new ThermalPhaseTransition.EquilibriumPoint(
					finiteDouble(point, "pressure_pa"), finiteDouble(point, "temperature_k")));
		}
		return new ThermalPhaseTransition(stableId, string(root, "lower_energy_material"),
				string(root, "upper_energy_material"),
				exactLong(root, "lower_volume_svq_per_batch"), exactLong(root, "upper_volume_svq_per_batch"),
				ExactRational.parse(string(root, "mass_mu_per_batch")), requirement, curve);
	}

	public static ThermalBoundaryDefinition decodeBoundary(String stableId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "target_kind", "target_id", "kind", "surface_temperature_k");
		ThermalBoundaryDefinition.TargetKind targetKind;
		ThermalBoundaryDefinition.Kind kind;
		try {
			targetKind = ThermalBoundaryDefinition.TargetKind.valueOf(string(root, "target_kind"));
			kind = ThermalBoundaryDefinition.Kind.valueOf(string(root, "kind"));
		} catch (IllegalArgumentException invalid) {
			throw new IllegalArgumentException("Unknown thermal boundary kind", invalid);
		}
		return new ThermalBoundaryDefinition(stableId, targetKind, string(root, "target_id"), kind,
				finiteDouble(root, "surface_temperature_k"));
	}

	public static GasLiquidSpeciesPair decodePair(String stableId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "gas_component", "liquid_phase", "species",
				"gas_standard_volume_nanos_per_batch", "gas_kilograms_per_batch",
				"liquid_quanta_per_batch", "liquid_kilograms_per_batch", "liquid_residual_kilograms_per_batch",
				"moles_per_batch", "henry_segments", "minimum_pressure_pa", "maximum_pressure_pa",
				"maximum_liquid_concentration_mol_per_m3", "liquid_diffusion_m2_per_s",
				"liquid_film_thickness_m", "gas_resistance_s_per_m");
		JsonElement segmentElement = required(root, "henry_segments");
		if (!segmentElement.isJsonArray()) throw new IllegalArgumentException("henry_segments must be an array");
		JsonArray array = segmentElement.getAsJsonArray();
		if (array.isEmpty() || array.size() > ThermalLimits.MAX_HENRY_SEGMENTS_PER_PAIR) {
			throw new IllegalArgumentException("henry_segments are empty or over budget");
		}
		ArrayList<GasLiquidSpeciesPair.HenrySegment> segments = new ArrayList<>(array.size());
		for (JsonElement element : array) {
			if (!element.isJsonObject()) throw new IllegalArgumentException("Henry segment must be an object");
			JsonObject segment = element.getAsJsonObject();
			requireOnly(segment, "minimum_temperature_k", "maximum_temperature_k",
					"henry_constant_mol_per_m3_pa_at_minimum", "henry_constant_mol_per_m3_pa_at_maximum");
			segments.add(new GasLiquidSpeciesPair.HenrySegment(
					finiteDouble(segment, "minimum_temperature_k"), finiteDouble(segment, "maximum_temperature_k"),
					finiteDouble(segment, "henry_constant_mol_per_m3_pa_at_minimum"),
					finiteDouble(segment, "henry_constant_mol_per_m3_pa_at_maximum")));
		}
		return new GasLiquidSpeciesPair(stableId, string(root, "gas_component"), string(root, "liquid_phase"),
				string(root, "species"), exactLong(root, "gas_standard_volume_nanos_per_batch"),
				finiteDouble(root, "gas_kilograms_per_batch"), exactLong(root, "liquid_quanta_per_batch"),
				finiteDouble(root, "liquid_kilograms_per_batch"),
				finiteDouble(root, "liquid_residual_kilograms_per_batch"),
				ExactRational.parse(string(root, "moles_per_batch")), segments,
				finiteDouble(root, "minimum_pressure_pa"), finiteDouble(root, "maximum_pressure_pa"),
				finiteDouble(root, "maximum_liquid_concentration_mol_per_m3"),
				finiteDouble(root, "liquid_diffusion_m2_per_s"), finiteDouble(root, "liquid_film_thickness_m"),
				finiteDouble(root, "gas_resistance_s_per_m"));
	}

	public static FiniteThermalDeviceDefinition decodeFiniteDevice(String stableId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "thermal_material", "body_mass_kg", "initial_temperature_k", "ports");
		JsonElement portElement = required(root, "ports");
		if (!portElement.isJsonArray()) throw new IllegalArgumentException("ports must be an array");
		JsonArray array = portElement.getAsJsonArray();
		if (array.isEmpty() || array.size() > ThermalLimits.MAX_FINITE_DEVICE_CONTACTS) {
			throw new IllegalArgumentException("finite thermal device ports are empty or over budget");
		}
		ArrayList<FiniteThermalDeviceDefinition.Port> ports = new ArrayList<>(array.size());
		for (JsonElement element : array) {
			if (!element.isJsonObject()) throw new IllegalArgumentException("finite thermal port must be an object");
			JsonObject port = element.getAsJsonObject();
			requireOnly(port, "id", "contact_group", "local_face", "wall_thickness_m", "fluid_film_thickness_m");
			FiniteThermalDeviceDefinition.LocalFace face;
			try { face = FiniteThermalDeviceDefinition.LocalFace.valueOf(string(port, "local_face")); }
			catch (IllegalArgumentException invalid) {
				throw new IllegalArgumentException("Unknown finite thermal port local_face", invalid);
			}
			ports.add(new FiniteThermalDeviceDefinition.Port(string(port, "id"), string(port, "contact_group"), face,
					finiteDouble(port, "wall_thickness_m"), finiteDouble(port, "fluid_film_thickness_m")));
		}
		return new FiniteThermalDeviceDefinition(stableId, string(root, "thermal_material"),
				finiteDouble(root, "body_mass_kg"), finiteDouble(root, "initial_temperature_k"), ports);
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
