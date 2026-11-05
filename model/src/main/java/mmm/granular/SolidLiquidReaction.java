package mmm.granular;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ExactRational;

import java.util.Map;
import java.util.TreeMap;

/** Frozen, one-target-per-step solid/liquid saturation rule. */
public record SolidLiquidReaction(
		String id,
		String liquidPhaseId,
		Map<String, Integer> aqueousStoichiometry,
	String solidMaterialId,
	Map<String, Integer> solidComposition,
	ExactRational saturationConcentrationMolPerCubicMeter,
	long solidExtentPerBatch,
	long solidVolumeSvqPerBatch,
	long accessibleSurfaceSvqPerCellStep,
		boolean reversible,
		Solubility solubility
) {
	/** Linear solvent-mole ratio over its declared temperature interval. */
	public record Solubility(String solventSpeciesId,double referenceTemperatureK,double ratio,double slopePerK,double minimumK,double maximumK,double diffusivityM2PerS) {
		public Solubility {
			GranularIds.requireId(solventSpeciesId,"solvent species id");
			if(!(Double.isFinite(referenceTemperatureK)&&Double.isFinite(ratio)&&Double.isFinite(slopePerK)&&Double.isFinite(minimumK)&&Double.isFinite(maximumK)&&Double.isFinite(diffusivityM2PerS)
					&& minimumK>0 && minimumK<=referenceTemperatureK && referenceTemperatureK<=maximumK && ratio>0 && diffusivityM2PerS>0))throw new IllegalArgumentException("Invalid solubility curve");
		}
	}
	public SolidLiquidReaction(String id,String liquidPhaseId,Map<String,Integer> aqueousStoichiometry,String solidMaterialId,
			Map<String,Integer> solidComposition,ExactRational saturation,long extent,long volume,long surface,boolean reversible){
		this(id,liquidPhaseId,aqueousStoichiometry,solidMaterialId,solidComposition,saturation,extent,volume,surface,reversible,null);
	}
	public SolidLiquidReaction {
		GranularIds.requireId(id, "solid-liquid reaction id");
		GranularIds.requireId(liquidPhaseId, "solid-liquid phase id");
		GranularIds.requireId(solidMaterialId, "solid-liquid material id");
		aqueousStoichiometry = canonicalSide(aqueousStoichiometry, "aqueous", id);
		solidComposition = canonicalSide(solidComposition, "solid", id);
		if (saturationConcentrationMolPerCubicMeter == null || saturationConcentrationMolPerCubicMeter.signum() < 0) {
			throw new IllegalArgumentException("Solid-liquid saturation capacity must be non-negative: " + id);
		}
		if (solidExtentPerBatch <= 0L || solidExtentPerBatch > GranularLimits.MAX_REACTION_EXTENTS_PER_BATCH
				|| solidVolumeSvqPerBatch <= 0L || solidVolumeSvqPerBatch > SolidVolume.SVQ_PER_CELL) {
			throw new IllegalArgumentException("Solid-liquid extent/volume batch is invalid: " + id);
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
