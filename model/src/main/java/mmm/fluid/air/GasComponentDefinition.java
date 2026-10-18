package mmm.fluid.air;

import mmm.chemistry.ExactRational;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stable standard-state creation relation for one gas component; mass and residual densities use kg/m³. */
public record GasComponentDefinition(
		String id,
		double standardDensityKgPerCubicMeter,
		double defaultResidualDensityKgPerCubicMeter,
		List<String> allowedSpecies,
		List<DefaultSpecies> defaultSpeciesConcentrations
) {
	public record DefaultSpecies(String speciesId, ExactRational concentrationMolPerStandardCubicMeter) {
		public DefaultSpecies {
			GasIds.require(speciesId, "gas species id");
			if (concentrationMolPerStandardCubicMeter == null || concentrationMolPerStandardCubicMeter.signum() <= 0) {
				throw new IllegalArgumentException("Gas default concentration in mol per standard m³ must be positive");
			}
		}
	}

	public GasComponentDefinition {
		GasIds.require(id, "gas component id");
		if (!(Double.isFinite(standardDensityKgPerCubicMeter) && standardDensityKgPerCubicMeter > 0.0)) {
			throw new IllegalArgumentException("Gas standard density in kg/m³ must be finite and positive");
		}
		if (!Double.isFinite(defaultResidualDensityKgPerCubicMeter)) {
			throw new IllegalArgumentException("Gas default mass residual density must be finite");
		}
		if (allowedSpecies == null || allowedSpecies.size() > GasLimits.MAX_SPECIES_PER_COMPONENT) {
			throw new IllegalArgumentException("Gas allowed species list is missing or over budget");
		}
		ArrayList<String> canonicalAllowed = new ArrayList<>(allowedSpecies);
		for (String speciesId : canonicalAllowed) GasIds.require(speciesId, "allowed gas species id");
		canonicalAllowed.sort(String::compareTo);
		for (int index = 1; index < canonicalAllowed.size(); index++) {
			if (canonicalAllowed.get(index - 1).equals(canonicalAllowed.get(index))) {
				throw new IllegalArgumentException("Duplicate allowed gas species " + canonicalAllowed.get(index));
			}
		}
		allowedSpecies = List.copyOf(canonicalAllowed);
		if (defaultSpeciesConcentrations == null
				|| defaultSpeciesConcentrations.size() > GasLimits.MAX_SPECIES_PER_COMPONENT) {
			throw new IllegalArgumentException("Gas default species list is missing or over budget");
		}
		ArrayList<DefaultSpecies> canonical = new ArrayList<>(defaultSpeciesConcentrations);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Gas default species list contains null");
		}
		canonical.sort(Comparator.comparing(DefaultSpecies::speciesId));
		for (int index = 1; index < canonical.size(); index++) {
			if (canonical.get(index - 1).speciesId().equals(canonical.get(index).speciesId())) {
				throw new IllegalArgumentException("Duplicate gas default species " + canonical.get(index).speciesId());
			}
		}
		for (DefaultSpecies species : canonical) if (!allowedSpecies.contains(species.speciesId())) {
			throw new IllegalArgumentException("Default gas species is not allowed by component " + species.speciesId());
		}
		defaultSpeciesConcentrations = List.copyOf(canonical);
	}

	public boolean allowsSpecies(String speciesId) {
		return java.util.Collections.binarySearch(allowedSpecies, speciesId) >= 0;
	}
}
