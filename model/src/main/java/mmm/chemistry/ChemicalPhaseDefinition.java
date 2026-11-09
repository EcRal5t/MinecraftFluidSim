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
		Map<String, ExactRational> defaultConcentrationMolPerCubicMeter,
		ViscosityCorrection viscosityCorrection
) {
	public record ViscosityCorrection(double a,double b,double maximumMolality,double referenceTemperatureK,double temperatureToleranceK) {
		public ViscosityCorrection {
			if (!(Double.isFinite(a) && a>=0 && Double.isFinite(b) && b>=0 && Double.isFinite(maximumMolality) && maximumMolality>0
					&& Double.isFinite(referenceTemperatureK) && referenceTemperatureK>0 && Double.isFinite(temperatureToleranceK) && temperatureToleranceK>=0))
				throw new IllegalArgumentException("Liquid viscosity correction is invalid");
		}
	}
	public ChemicalPhaseDefinition(String id,String materialId,List<String> allowedSpecies,Map<String,ExactRational> defaults) {
		this(id,materialId,allowedSpecies,defaults,null);
	}
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
