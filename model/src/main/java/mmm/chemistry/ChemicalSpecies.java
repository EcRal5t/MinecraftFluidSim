package mmm.chemistry;

import java.util.Map;
import java.util.TreeMap;

/** Immutable chemical species definition; runtime handles are assigned by ChemicalRegistry. */
public record ChemicalSpecies(
		String id,
		Map<String, Integer> composition,
		int charge,
		ExactRational molarMassKgPerMol,
		int baseArgb,
		Double diffusivityM2PerS,
		Double effectiveVolumeM3PerMol
) {
	public ChemicalSpecies(String id, Map<String,Integer> composition, int charge, ExactRational molarMassKgPerMol, int baseArgb) {
		this(id,composition,charge,molarMassKgPerMol,baseArgb,null,null);
	}
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
		if (molarMassKgPerMol == null || molarMassKgPerMol.signum() <= 0) {
			throw new IllegalArgumentException("Species mass must be positive: " + id);
		}
		mmm.units.Mass.exactUnits(molarMassKgPerMol.divide(ChemicalLimits.QUANTA_PER_MOL).numerator(), molarMassKgPerMol.divide(ChemicalLimits.QUANTA_PER_MOL).denominator());
		if (diffusivityM2PerS != null && !(Double.isFinite(diffusivityM2PerS) && diffusivityM2PerS > 0))
			throw new IllegalArgumentException("Species diffusivity is invalid: " + id);
		if (effectiveVolumeM3PerMol != null && !(Double.isFinite(effectiveVolumeM3PerMol) && effectiveVolumeM3PerMol > 0))
			throw new IllegalArgumentException("Species effective volume is invalid: " + id);
		composition = Map.copyOf(canonical);
	}
}
