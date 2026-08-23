package com.example.granular;

import com.example.chemistry.ChemicalLimits;
import com.example.chemistry.ExactRational;

import java.util.Map;
import java.util.TreeMap;

/** Frozen, one-target-per-step solid/liquid saturation rule. */
public record SolidLiquidReaction(
		String id,
		String liquidPhaseId,
		Map<String, Integer> aqueousStoichiometry,
		String solidMaterialId,
		Map<String, Integer> solidComposition,
		ExactRational saturationCapacityExtentPerBv,
		long solidVolumeSvqPerExtent,
		long accessibleSurfaceSvqPerCellStep,
		boolean reversible
) {
	public SolidLiquidReaction {
		GranularIds.requireId(id, "solid-liquid reaction id");
		GranularIds.requireId(liquidPhaseId, "solid-liquid phase id");
		GranularIds.requireId(solidMaterialId, "solid-liquid material id");
		aqueousStoichiometry = canonicalSide(aqueousStoichiometry, "aqueous", id);
		solidComposition = canonicalSide(solidComposition, "solid", id);
		if (saturationCapacityExtentPerBv == null || saturationCapacityExtentPerBv.signum() < 0) {
			throw new IllegalArgumentException("Solid-liquid saturation capacity must be non-negative: " + id);
		}
		if (solidVolumeSvqPerExtent <= 0L || solidVolumeSvqPerExtent > SolidVolume.SVQ_PER_CELL) {
			throw new IllegalArgumentException("Solid-liquid extent volume is invalid: " + id);
		}
		if (accessibleSurfaceSvqPerCellStep <= 0L || accessibleSurfaceSvqPerCellStep > SolidVolume.SVQ_PER_CELL) {
			throw new IllegalArgumentException("Solid-liquid surface cap is invalid: " + id);
		}
	}

	private static Map<String, Integer> canonicalSide(Map<String, Integer> source, String side, String rule) {
		if (source == null || source.isEmpty() || source.size() > GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES) {
			throw new IllegalArgumentException("Solid-liquid " + side + " side is empty or over budget: " + rule);
		}
		TreeMap<String, Integer> result = new TreeMap<>();
		for (Map.Entry<String, Integer> entry : source.entrySet()) {
			GranularIds.requireId(entry.getKey(), side + " species id");
			Integer coefficient = entry.getValue();
			if (coefficient == null || coefficient <= 0
					|| coefficient > ChemicalLimits.MAX_STOICHIOMETRIC_COEFFICIENT) {
				throw new IllegalArgumentException("Invalid solid-liquid " + side + " coefficient: " + rule);
			}
			if (result.put(entry.getKey(), coefficient) != null) {
				throw new IllegalArgumentException("Duplicate solid-liquid " + side + " species: " + rule);
			}
		}
		return Map.copyOf(result);
	}
}
