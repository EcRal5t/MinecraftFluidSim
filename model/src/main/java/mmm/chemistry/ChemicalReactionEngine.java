package mmm.chemistry;

import java.util.Map;

/** Deterministic complete-dissociation and instantaneous-reaction evaluator. */
public final class ChemicalReactionEngine {
	private final ChemicalRegistry registry;

	public ChemicalReactionEngine(ChemicalRegistry registry) {
		this.registry = java.util.Objects.requireNonNull(registry, "registry");
	}

	@FunctionalInterface
	public interface ExtentConstraint {
		long limit(ChemicalReaction reaction, long[] before, long[] complete, long maximum);
	}

	/** Reacts one complete cell/phase inventory with a full transient species vector and caller-supplied physical extent constraints. */
	public InventoryResult reactInventory(int phaseHandle, long[] input, ExtentConstraint constraint) {
		if (input == null || input.length != registry.species().size()) {
			throw new IllegalArgumentException("Reaction inventory layout does not match the registry");
		}
		long[] inventory = input.clone();
		long inputTotal = 0L;
		for (long amount : inventory) {
			if (amount < 0L) throw new IllegalArgumentException("Chemical inventory is negative");
			inputTotal = Math.addExact(inputTotal, amount);
		}
		if (inputTotal <= 0L || inputTotal > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
			throw new BudgetExceededException("Reaction inventory is empty or over budget");
		}
		int evaluations = 0; boolean pending = false;
		for (ChemicalReaction reaction : registry.reactionOrder()) {
			if (registry.phaseHandle(reaction.phaseId()) != phaseHandle) continue;
			if (++evaluations > ChemicalLimits.MAX_REACTION_EVALUATIONS_PER_STEP) {
				throw new BudgetExceededException("Reaction evaluation budget exceeded");
			}
			long extent = Long.MAX_VALUE;
			for (Map.Entry<String, Integer> reactant : reaction.reactants().entrySet()) {
				extent = Math.min(extent, inventory[registry.speciesHandle(reactant.getKey())] / reactant.getValue());
			}
			if (extent <= 0L || extent == Long.MAX_VALUE) continue;
			long[] complete = advance(inventory, reaction, extent);
			long accepted = constraint.limit(reaction, inventory, complete, extent);
			if (accepted < 0 || accepted > extent) throw new IllegalArgumentException("Invalid constrained reaction extent");
			pending |= accepted < extent;
			inventory = accepted == extent ? complete : advance(inventory, reaction, accepted);
		}
		long outputTotal = 0L;
		for (long amount : inventory) outputTotal = Math.addExact(outputTotal, amount);
		if (outputTotal > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
			throw new BudgetExceededException("Reaction result exceeds region inventory budget");
		}
		return new InventoryResult(inventory, evaluations, pending);
	}

	/** Full work vector: transient species need no persistent mixture object. */
	public long[] advance(long[] before, ChemicalReaction reaction, long extent) {
		long[] after = before.clone();
		for (var term : reaction.reactants().entrySet()) {
			int handle = registry.speciesHandle(term.getKey());
			after[handle] = Math.subtractExact(after[handle], Math.multiplyExact(extent, term.getValue()));
		}
		for (var term : reaction.products().entrySet()) {
			int handle = registry.speciesHandle(term.getKey());
			after[handle] = Math.addExact(after[handle], Math.multiplyExact(extent, term.getValue()));
		}
		return after;
	}

	public double ph(ChemicalMixtureRecord mixture) {
		return ph(mixture, 1.0);
	}

	public double ph(ChemicalMixtureRecord mixture, double volumeCubicMeters) {
		if (!registry.hasSpecies(BuiltinChemicalDefinitions.H_PLUS)
				|| !registry.hasSpecies(BuiltinChemicalDefinitions.OH_MINUS)) return Double.NaN;
		return ph(mixture.amount(registry.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)),
				mixture.amount(registry.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)), volumeCubicMeters);
	}

	/** Strong-acid/base display approximation shared by queries and snapshots. */
	public static double ph(long acid, long base, double volumeCubicMeters) {
		if (!(Double.isFinite(volumeCubicMeters) && volumeCubicMeters > 0.0)) {
			throw new IllegalArgumentException("pH volume in m³ must be positive and finite");
		}
		long difference = acid >= base ? acid - base : base - acid;
		if (difference == 0L) return 7.0;
		double strength = Math.min(7.0, Math.log10(1.0 + (difference / (double) ChemicalLimits.QUANTA_PER_MOL) / (1e-3 * volumeCubicMeters)));
		return acid > base ? 7.0 - strength : 7.0 + strength;
	}

	/** The current pH model requires a water solvent and both acid/base species definitions. */
	public static boolean supportsPh(ChemicalPhaseDefinition phase) {
		return phase.allowedSpecies().contains(BuiltinChemicalDefinitions.H2O)
				&& phase.allowedSpecies().contains(BuiltinChemicalDefinitions.H_PLUS)
				&& phase.allowedSpecies().contains(BuiltinChemicalDefinitions.OH_MINUS)
				&& phase.defaultConcentrationMolPerCubicMeter().containsKey(BuiltinChemicalDefinitions.H2O);
	}

	public record InventoryResult(long[] inventory, int evaluations, boolean pending) {
		public InventoryResult {
			if (inventory == null) throw new IllegalArgumentException("Reaction result inventory is missing");
			inventory = inventory.clone();
		}
		@Override public long[] inventory() { return inventory.clone(); }
	}

	public static final class BudgetExceededException extends IllegalStateException {
		public BudgetExceededException(String message) { super(message); }
	}
}
