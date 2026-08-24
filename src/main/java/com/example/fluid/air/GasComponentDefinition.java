package com.example.fluid.air;

import com.example.chemistry.ExactRational;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stable standard-state creation relation for one gas component. */
public record GasComponentDefinition(
		String id,
		double standardMassPerCubicMeter,
		double defaultMassResidualPerCubicMeter,
		List<DefaultSpecies> defaultSpeciesPerCubicMeter
) {
	public record DefaultSpecies(String speciesId, ExactRational quantaPerCubicMeter) {
		public DefaultSpecies {
			GasIds.require(speciesId, "gas species id");
			if (quantaPerCubicMeter == null || quantaPerCubicMeter.signum() <= 0) {
				throw new IllegalArgumentException("Gas default species amount must be positive");
			}
		}
	}

	public GasComponentDefinition {
		GasIds.require(id, "gas component id");
		if (!(Double.isFinite(standardMassPerCubicMeter) && standardMassPerCubicMeter > 0.0)) {
			throw new IllegalArgumentException("Gas standard mass density must be finite and positive");
		}
		if (!Double.isFinite(defaultMassResidualPerCubicMeter)) {
			throw new IllegalArgumentException("Gas default mass residual density must be finite");
		}
		if (defaultSpeciesPerCubicMeter == null
				|| defaultSpeciesPerCubicMeter.size() > GasLimits.MAX_SPECIES_PER_COMPONENT) {
			throw new IllegalArgumentException("Gas default species list is missing or over budget");
		}
		ArrayList<DefaultSpecies> canonical = new ArrayList<>(defaultSpeciesPerCubicMeter);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Gas default species list contains null");
		}
		canonical.sort(Comparator.comparing(DefaultSpecies::speciesId));
		for (int index = 1; index < canonical.size(); index++) {
			if (canonical.get(index - 1).speciesId().equals(canonical.get(index).speciesId())) {
				throw new IllegalArgumentException("Duplicate gas default species " + canonical.get(index).speciesId());
			}
		}
		defaultSpeciesPerCubicMeter = List.copyOf(canonical);
	}
}
