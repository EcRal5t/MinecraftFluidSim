package mmm.chemistry;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Liquid chemical overlay bound to one stable mechanical material id. */
public record ChemicalPhaseDefinition(
		String id,
		String materialId,
		List<String> allowedSpecies,
		Map<String, ExactRational> defaultConcentrationMolPerCubicMeter
) {
	public ChemicalPhaseDefinition {
		ChemicalIds.requireId(id, "chemical phase id");
		ChemicalIds.requireId(materialId, "mechanical material id");
		if (allowedSpecies == null || allowedSpecies.size() > ChemicalLimits.MAX_SPECIES) {
			throw new IllegalArgumentException("Chemical phase species list is missing or over budget: " + id);
		}
		TreeSet<String> species = new TreeSet<>();
		for (String speciesId : allowedSpecies) {
			ChemicalIds.requireId(speciesId, "allowed species id");
			if (!species.add(speciesId)) throw new IllegalArgumentException("Duplicate allowed species " + speciesId);
		}
		TreeMap<String, ExactRational> defaults = new TreeMap<>();
		if (defaultConcentrationMolPerCubicMeter == null || (defaultConcentrationMolPerCubicMeter.isEmpty() != species.isEmpty())) {
			throw new IllegalArgumentException("Chemical phase default concentration must match its species list: " + id);
		}
		for (Map.Entry<String, ExactRational> entry : defaultConcentrationMolPerCubicMeter.entrySet()) {
			if (!species.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().signum() <= 0) {
				throw new IllegalArgumentException("Invalid default inventory for " + id + ": " + entry.getKey());
			}
			defaults.put(entry.getKey(), entry.getValue());
		}
		allowedSpecies = List.copyOf(species);
		defaultConcentrationMolPerCubicMeter = Map.copyOf(defaults);
	}
}
