package mmm.chemistry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Strict JSON codecs for data/mmm/{species,chemical_phases,reactions}. */
public final class ChemicalDefinitionCodec {
	public static final String SPECIES_PATH = "mmm/species";
	public static final String PHASES_PATH = "mmm/chemical_phases";
	public static final String REACTIONS_PATH = "mmm/reactions";

	private ChemicalDefinitionCodec() { }

	public static ChemicalSpecies decodeSpecies(String stableId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "composition", "charge", "mass_per_au", "base_argb");
		return new ChemicalSpecies(stableId, integerMap(root, "composition"),
				exactInteger(root, "charge"), exact(root, "mass_per_au"), color(root, "base_argb"));
	}

	public static ChemicalPhaseDefinition decodePhase(String stableId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "material", "allowed_species", "default_inventory_per_bv", "accepts_indicator");
		JsonArray source = array(root, "allowed_species");
		List<String> allowed = new ArrayList<>(source.size());
		for (JsonElement element : source) allowed.add(string(element, "allowed_species entry"));
		TreeMap<String, ExactRational> defaults = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : object(root, "default_inventory_per_bv").entrySet()) {
			defaults.put(entry.getKey(), exact(entry.getValue(), "default_inventory_per_bv." + entry.getKey()));
		}
		JsonElement indicator = required(root, "accepts_indicator");
		if (!indicator.isJsonPrimitive() || !indicator.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException("accepts_indicator must be a JSON boolean");
		}
		return new ChemicalPhaseDefinition(stableId, string(required(root, "material"), "material"),
				allowed, defaults, indicator.getAsBoolean());
	}

	public static ChemicalReaction decodeReaction(String stableId, Reader input) {
		JsonObject root = object(input);
		requireOnly(root, "kind", "phase", "reactants", "products", "volume_delta_bv_per_extent");
		ChemicalReaction.Kind kind;
		try {
			kind = ChemicalReaction.Kind.valueOf(string(required(root, "kind"), "kind").toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException invalid) {
			throw new IllegalArgumentException("Unknown reaction kind", invalid);
		}
		return new ChemicalReaction(stableId, kind, string(required(root, "phase"), "phase"),
				integerMap(root, "reactants"), integerMap(root, "products"), exact(root, "volume_delta_bv_per_extent"));
	}

	private static JsonObject object(Reader input) {
		JsonElement root;
		try { root = JsonParser.parseReader(input); }
		catch (RuntimeException invalid) { throw new IllegalArgumentException("Malformed chemical JSON", invalid); }
		if (!root.isJsonObject()) throw new IllegalArgumentException("Chemical definition root must be an object");
		return root.getAsJsonObject();
	}

	private static JsonObject object(JsonObject root, String name) {
		JsonElement value = required(root, name);
		if (!value.isJsonObject()) throw new IllegalArgumentException(name + " must be an object");
		return value.getAsJsonObject();
	}

	private static JsonArray array(JsonObject root, String name) {
		JsonElement value = required(root, name);
		if (!value.isJsonArray()) throw new IllegalArgumentException(name + " must be an array");
		return value.getAsJsonArray();
	}

	private static Map<String, Integer> integerMap(JsonObject root, String name) {
		TreeMap<String, Integer> result = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : object(root, name).entrySet()) {
			ExactRational value = exact(entry.getValue(), name + "." + entry.getKey());
			if (!value.denominator().equals(java.math.BigInteger.ONE)) {
				throw new IllegalArgumentException(name + " coefficients must be exact integers");
			}
			result.put(entry.getKey(), value.numerator().intValueExact());
		}
		return result;
	}

	private static int exactInteger(JsonObject root, String name) {
		ExactRational value = exact(root, name);
		if (!value.denominator().equals(java.math.BigInteger.ONE)) throw new IllegalArgumentException(name + " must be an integer");
		return value.numerator().intValueExact();
	}

	private static ExactRational exact(JsonObject root, String name) { return exact(required(root, name), name); }
	private static ExactRational exact(JsonElement value, String name) {
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(name + " must be an exact rational JSON string");
		}
		return ExactRational.parse(value.getAsString());
	}

	private static int color(JsonObject root, String name) {
		String value = string(required(root, name), name);
		if (!value.matches("#[0-9a-fA-F]{8}")) throw new IllegalArgumentException(name + " must be #AARRGGBB");
		return (int) Long.parseUnsignedLong(value.substring(1), 16);
	}

	private static String string(JsonElement value, String name) {
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
		java.util.Set<String> allowed = java.util.Set.of(fields);
		for (String field : root.keySet()) if (!allowed.contains(field)) {
			throw new IllegalArgumentException("Unknown chemical definition field " + field);
		}
		for (String field : fields) required(root, field);
	}
}
