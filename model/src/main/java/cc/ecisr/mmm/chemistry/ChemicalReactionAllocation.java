package cc.ecisr.mmm.chemistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/** Plans one cell/phase's product allocation against its existing carrier masses and residuals. */
final class ChemicalReactionAllocation {
	private ChemicalReactionAllocation() { }

	record Plan(int[] markers, ChemicalMixtureRecord[] mixtures, double[] masses, long quantaDelta, int changed) { }

	static Plan plan(ChemicalParcelStore store, List<Integer> markers, long[] inventory, double[] markerMass) {
		ChemicalRegistry registry = store.registry();
		double[] weights = new double[markers.size()];
		int[] ordinals = new int[markers.size()];
		for (int i = 0; i < markers.size(); i++) {
			ordinals[i] = markers.get(i);
			weights[i] = store.record(ordinals[i]).exactSpeciesMassKilograms(registry).toDouble();
		}
		@SuppressWarnings("unchecked")
		TreeMap<Integer, Long>[] distributed = new TreeMap[markers.size()];
		for (int i = 0; i < distributed.length; i++) distributed[i] = new TreeMap<>();
		for (int species = 0; species < inventory.length; species++) if (inventory[species] > 0L) {
			long[] allocation = BinaryVolumeAllocator.allocate(inventory[species], weights, ordinals);
			for (int i = 0; i < allocation.length; i++) if (allocation[i] > 0L) distributed[i].put(species, allocation[i]);
		}
		repairEmptyParcels(distributed, ordinals);
		ChemicalMixtureRecord[] replacements = new ChemicalMixtureRecord[markers.size()];
		double[] masses = new double[markers.size()];
		double previousMass = 0.0, candidateMass = 0.0;
		long delta = 0L;
		int changed = 0;
		for (int i = 0; i < markers.size(); i++) {
			int marker = ordinals[i];
			if (distributed[i].size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL) {
				throw new ChemicalReactionEngine.BudgetExceededException("Reaction allocation exceeds parcel species capacity");
			}
			replacements[i] = new ChemicalMixtureRecord(distributed[i].keySet().stream().mapToInt(Integer::intValue).toArray(),
					distributed[i].values().stream().mapToLong(Long::longValue).toArray(), store.record(marker).massResidualKilogramsBits());
			double kilograms = replacements[i].massKilograms(registry);
			if (!(Double.isFinite(kilograms) && kilograms > 0.0)) {
				throw new ChemicalReactionEngine.BudgetExceededException("Reaction allocation requires positive carrier mass");
			}
			ExactRational speciesDelta = replacements[i].exactSpeciesMassKilograms(registry)
					.subtract(store.record(marker).exactSpeciesMassKilograms(registry));
			try {
				masses[i] = ChemicalMixtureRecord.solverMassAfterSpeciesChange(markerMass[marker], speciesDelta, store.unitScale());
			} catch (IllegalArgumentException | ArithmeticException unavailable) {
				throw new ChemicalReactionEngine.BudgetExceededException("Reaction mass update exceeds representation precision");
			}
			if (!(Double.isFinite(masses[i]) && masses[i] > 0.0)) {
				throw new ChemicalReactionEngine.BudgetExceededException("Reaction allocation requires positive solver mass");
			}
			previousMass += markerMass[marker];
			candidateMass += masses[i];
			delta = Math.addExact(delta, replacements[i].totalQuanta() - store.record(marker).totalQuanta());
			if (!replacements[i].equals(store.record(marker))) changed++;
		}
		double tolerance = Math.max(1.0E-9, Math.max(Math.ulp(previousMass), Math.ulp(candidateMass)) * 8.0);
		if (!Double.isFinite(previousMass) || !Double.isFinite(candidateMass) || Math.abs(previousMass - candidateMass) > tolerance) {
			throw new ChemicalReactionEngine.BudgetExceededException("Reaction marker-mass allocation exceeds representation precision");
		}
		return new Plan(ordinals, replacements, masses, delta, changed);
	}

	private static void repairEmptyParcels(TreeMap<Integer, Long>[] distributed, int[] ordinals) {
		long[] totals = new long[distributed.length];
		ArrayList<Integer> empty = new ArrayList<>();
		PriorityQueue<Integer> donors = new PriorityQueue<>((left, right) -> {
			int total = Long.compare(totals[right], totals[left]);
			return total != 0 ? total : Integer.compare(ordinals[left], ordinals[right]);
		});
		for (int parcel = 0; parcel < distributed.length; parcel++) {
			for (long amount : distributed[parcel].values()) totals[parcel] = Math.addExact(totals[parcel], amount);
			if (totals[parcel] == 0L) empty.add(parcel);
			else if (totals[parcel] > 1L) donors.add(parcel);
		}
		empty.sort((left, right) -> Integer.compare(ordinals[left], ordinals[right]));
		for (int target : empty) {
			Integer donor = donors.poll();
			if (donor == null) throw new ChemicalReactionEngine.BudgetExceededException("Reaction products require fewer live carriers");
			int species = -1;
			long largest = -1L;
			for (Map.Entry<Integer, Long> entry : distributed[donor].entrySet()) if (entry.getValue() > largest) {
				species = entry.getKey(); largest = entry.getValue();
			}
			if (largest == 1L) distributed[donor].remove(species);
			else distributed[donor].put(species, largest - 1L);
			distributed[target].put(species, 1L);
			totals[donor]--; totals[target] = 1L;
			if (totals[donor] > 1L) donors.add(donor);
		}
	}
}
