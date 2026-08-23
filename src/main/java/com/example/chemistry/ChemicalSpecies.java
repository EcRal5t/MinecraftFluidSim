package com.example.chemistry;

import java.util.Map;
import java.util.TreeMap;

/** Immutable chemical species definition; runtime handles are assigned by ChemicalRegistry. */
public record ChemicalSpecies(
		String id,
		Map<String, Integer> composition,
		int charge,
		ExactRational massPerAu,
		int baseArgb
) {
	public ChemicalSpecies {
		ChemicalIds.requireId(id, "species id");
		if (composition == null || composition.isEmpty() || composition.size() > ChemicalLimits.MAX_COMPOSITION_BASES) {
			throw new IllegalArgumentException("Species composition is empty or over budget: " + id);
		}
		TreeMap<String, Integer> canonical = new TreeMap<>();
		for (Map.Entry<String, Integer> entry : composition.entrySet()) {
			String basis = ChemicalIds.requireBasis(entry.getKey());
			Integer coefficient = entry.getValue();
			if (coefficient == null || coefficient <= 0
					|| coefficient > ChemicalLimits.MAX_COMPOSITION_COEFFICIENT) {
				throw new IllegalArgumentException("Invalid composition coefficient for " + id + ": " + coefficient);
			}
			if (canonical.put(basis, coefficient) != null) throw new IllegalArgumentException("Duplicate composition basis " + basis);
		}
		if (Math.abs(charge) > ChemicalLimits.MAX_ABSOLUTE_CHARGE) {
			throw new IllegalArgumentException("Species charge is over budget: " + id);
		}
		if (massPerAu == null || massPerAu.signum() <= 0) throw new IllegalArgumentException("Species mass must be positive: " + id);
		composition = Map.copyOf(canonical);
	}
}
