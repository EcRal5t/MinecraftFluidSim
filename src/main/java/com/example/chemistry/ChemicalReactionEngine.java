package com.example.chemistry;

import java.util.Arrays;
import java.util.Map;

/** Deterministic complete-dissociation and instantaneous-reaction evaluator. */
public final class ChemicalReactionEngine {
	private final ChemicalRegistry registry;

	public ChemicalReactionEngine(ChemicalRegistry registry) {
		this.registry = java.util.Objects.requireNonNull(registry, "registry");
	}

	public Result react(int phaseHandle, ChemicalMixtureRecord input) {
		long[] inventory = new long[registry.species().size()];
		for (int entry = 0; entry < input.entryCount(); entry++) {
			inventory[input.speciesHandle(entry)] = input.amountAt(entry);
		}
		InventoryResult result = reactInventory(phaseHandle, inventory);
		return new Result(canonical(result.inventory, input.massResidualBits()), result.evaluations);
	}

	/** Reacts one complete cell/phase inventory without applying the per-parcel 16-entry budget. */
	public InventoryResult reactInventory(int phaseHandle, long[] input) {
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
		int evaluations = 0;
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
			for (Map.Entry<String, Integer> reactant : reaction.reactants().entrySet()) {
				int handle = registry.speciesHandle(reactant.getKey());
				inventory[handle] = Math.subtractExact(inventory[handle],
						Math.multiplyExact(extent, reactant.getValue()));
			}
			for (Map.Entry<String, Integer> product : reaction.products().entrySet()) {
				int handle = registry.speciesHandle(product.getKey());
				inventory[handle] = Math.addExact(inventory[handle],
						Math.multiplyExact(extent, product.getValue()));
			}
		}
		long outputTotal = 0L;
		for (long amount : inventory) outputTotal = Math.addExact(outputTotal, amount);
		if (outputTotal > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
			throw new BudgetExceededException("Reaction result exceeds region inventory budget");
		}
		return new InventoryResult(inventory, evaluations);
	}

	public ChemicalMixtureRecord addAndReact(
			int phaseHandle, ChemicalMixtureRecord base, Map<String, Long> additions
	) {
		long[] inventory = new long[registry.species().size()];
		for (int entry = 0; entry < base.entryCount(); entry++) inventory[base.speciesHandle(entry)] = base.amountAt(entry);
		for (Map.Entry<String, Long> addition : additions.entrySet()) {
			long amount = addition.getValue() == null ? 0L : addition.getValue();
			if (amount <= 0L) throw new IllegalArgumentException("Chemical addition must be positive");
			int handle = registry.speciesHandle(addition.getKey());
			inventory[handle] = Math.addExact(inventory[handle], amount);
		}
		return react(phaseHandle, canonical(inventory, base.massResidualBits())).mixture();
	}

	public double ph(ChemicalMixtureRecord mixture) {
		return ph(mixture, 1.0);
	}

	public double ph(ChemicalMixtureRecord mixture, double blockVolume) {
		if (!(Double.isFinite(blockVolume) && blockVolume > 0.0)) {
			throw new IllegalArgumentException("pH block volume must be positive and finite");
		}
		long acid = mixture.amount(registry.speciesHandle(BuiltinChemicalDefinitions.H_PLUS));
		long base = mixture.amount(registry.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS));
		long difference = acid >= base ? acid - base : base - acid;
		if (difference == 0L) return 7.0;
		double strength = Math.min(7.0, Math.log10(1.0 + difference / (1_000.0 * blockVolume)));
		return acid > base ? 7.0 - strength : 7.0 + strength;
	}

	private ChemicalMixtureRecord canonical(long[] inventory, long residualBits) {
		int count = 0;
		for (long amount : inventory) {
			if (amount < 0L) throw new IllegalArgumentException("Chemical inventory became negative");
			if (amount > 0L) count++;
		}
		if (count == 0) throw new IllegalArgumentException("Chemical inventory became empty");
		if (count > ChemicalLimits.MAX_SPECIES_PER_PARCEL) throw new BudgetExceededException("Parcel species budget exceeded");
		int[] species = new int[count];
		long[] amounts = new long[count];
		int entry = 0;
		for (int handle = 0; handle < inventory.length; handle++) if (inventory[handle] > 0L) {
			species[entry] = handle;
			amounts[entry++] = inventory[handle];
		}
		return new ChemicalMixtureRecord(species, amounts, residualBits);
	}

	public record Result(ChemicalMixtureRecord mixture, int evaluations) { }
	public record InventoryResult(long[] inventory, int evaluations) {
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
