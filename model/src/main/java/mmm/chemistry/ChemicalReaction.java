package mmm.chemistry;

import java.util.Map;
import java.util.TreeMap;

/** Exact, zero-volume reaction definition. */
public record ChemicalReaction(
		String id,
		Kind kind,
		String phaseId,
		Map<String, Integer> reactants,
		Map<String, Integer> products,
		ExactRational volumeDeltaCubicMetersPerExtent,
		Kinetics kinetics
) {
	public enum Kind { COMPLETE_DISSOCIATION, INSTANTANEOUS, FINITE_RATE }

	/** Mass-action constants use mol/m³; activation energies use J/mol. */
	public record Kinetics(double forwardRate, double reverseRate, double forwardActivationJMol,
			double reverseActivationJMol, double referenceTemperatureK) {
		public static final Kinetics FAST = new Kinetics(0, 0, 0, 0, 298.15);
		public Kinetics {
			for (double value : new double[]{forwardRate, reverseRate, forwardActivationJMol, reverseActivationJMol})
				if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid reaction kinetics");
			if (!Double.isFinite(referenceTemperatureK) || referenceTemperatureK <= 0)
				throw new IllegalArgumentException("Invalid kinetic reference temperature");
		}
	}

	public ChemicalReaction(String id, Kind kind, String phaseId, Map<String, Integer> reactants,
			Map<String, Integer> products, ExactRational volumeDeltaCubicMetersPerExtent) {
		this(id, kind, phaseId, reactants, products, volumeDeltaCubicMetersPerExtent, Kinetics.FAST);
	}

	public ChemicalReaction {
		ChemicalIds.requireId(id, "reaction id");
		if (kinetics == null || (kind == Kind.FINITE_RATE && kinetics.forwardRate() == 0 && kinetics.reverseRate() == 0))
			throw new IllegalArgumentException("Finite reaction requires a rate: " + id);
		if (kind == null) throw new IllegalArgumentException("Reaction kind is missing: " + id);
		ChemicalIds.requireId(phaseId, "reaction phase id");
		reactants = canonicalSide(reactants, id, "reactant");
		products = canonicalSide(products, id, "product");
		if (volumeDeltaCubicMetersPerExtent == null || !volumeDeltaCubicMetersPerExtent.isZero()) {
			throw new IllegalArgumentException("Reaction volume delta must be exactly zero: " + id);
		}
		if (kind == Kind.COMPLETE_DISSOCIATION && reactants.size() != 1) {
			throw new IllegalArgumentException("Complete dissociation requires exactly one reactant: " + id);
		}
	}

	private static Map<String, Integer> canonicalSide(Map<String, Integer> source, String reaction, String label) {
		if (source == null || source.isEmpty()) throw new IllegalArgumentException("Reaction has no " + label + "s: " + reaction);
		TreeMap<String, Integer> result = new TreeMap<>();
		for (Map.Entry<String, Integer> entry : source.entrySet()) {
			ChemicalIds.requireId(entry.getKey(), label + " species id");
			Integer coefficient = entry.getValue();
			if (coefficient == null || coefficient <= 0
					|| coefficient > ChemicalLimits.MAX_STOICHIOMETRIC_COEFFICIENT) {
				throw new IllegalArgumentException("Invalid " + label + " coefficient in " + reaction);
			}
			if (result.put(entry.getKey(), coefficient) != null) throw new IllegalArgumentException("Duplicate " + label);
		}
		return Map.copyOf(result);
	}
}
