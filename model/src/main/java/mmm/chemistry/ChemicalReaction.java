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
		ExactRational volumeDeltaBvPerExtent
) {
	public enum Kind { COMPLETE_DISSOCIATION, INSTANTANEOUS }

	public ChemicalReaction {
		ChemicalIds.requireId(id, "reaction id");
		if (kind == null) throw new IllegalArgumentException("Reaction kind is missing: " + id);
		ChemicalIds.requireId(phaseId, "reaction phase id");
		reactants = canonicalSide(reactants, id, "reactant");
		products = canonicalSide(products, id, "product");
		if (volumeDeltaBvPerExtent == null || !volumeDeltaBvPerExtent.isZero()) {
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
