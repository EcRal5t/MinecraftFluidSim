package mmm.chemistry;


import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Immutable micromole species inventory and integer 100 ng mass residual. */
public final class ChemicalMixtureRecord {
	private final int[] speciesHandles;
	private final long[] amounts;
	private final long massResidualUnits;
	private final int hash;
	private final long totalQuanta;
	// A single immutable memo keeps registry identity and value atomically paired across readers.
	private volatile SpeciesMass speciesMass;
	private record SpeciesMass(ChemicalRegistry registry, ExactRational kilograms) { }

	public ChemicalMixtureRecord(int[] speciesHandles, long[] amounts, long massResidualUnits) {
		if (speciesHandles == null || amounts == null
				|| speciesHandles.length != amounts.length
				|| speciesHandles.length > ChemicalLimits.MAX_SPECIES_PER_PARCEL) {
			throw new IllegalArgumentException("Chemical parcel entries are over budget");
		}
		long total = 0L;
		for (int i = 0; i < speciesHandles.length; i++) {
			if (speciesHandles[i] < 0 || (i > 0 && speciesHandles[i - 1] >= speciesHandles[i]) || amounts[i] <= 0L) {
				throw new IllegalArgumentException("Chemical parcel entries are not canonical");
			}
			total = Math.addExact(total, amounts[i]);
		}
		if (total > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
			throw new IllegalArgumentException("Chemical parcel amount is over budget");
		}

		this.speciesHandles = speciesHandles.clone();
		this.amounts = amounts.clone();
		this.massResidualUnits = massResidualUnits;
		this.totalQuanta = total;
		this.hash = 31 * (31 * Arrays.hashCode(this.speciesHandles) + Arrays.hashCode(this.amounts))
				+ Long.hashCode(massResidualUnits);
	}

	private ChemicalMixtureRecord(ChemicalMixtureRecord source, long residualUnits) {

		speciesHandles = source.speciesHandles;
		amounts = source.amounts;
		totalQuanta = source.totalQuanta;
		massResidualUnits = residualUnits;
		hash = source.hash - Long.hashCode(source.massResidualUnits) + Long.hashCode(residualUnits);
		speciesMass = source.speciesMass;
	}

	public static ChemicalMixtureRecord fromStableAmounts(
			ChemicalRegistry registry, Map<String, Long> stableAmounts, long massResidualUnits
	) {
		TreeMap<Integer, Long> handles = new TreeMap<>();
		for (Map.Entry<String, Long> entry : stableAmounts.entrySet()) {
			long amount = entry.getValue() == null ? 0L : entry.getValue();
			if (amount <= 0L) throw new IllegalArgumentException("Chemical amount must be positive");
			handles.merge(registry.speciesHandle(entry.getKey()), amount, Math::addExact);
		}
		int[] species = new int[handles.size()];
		long[] amounts = new long[handles.size()];
		int index = 0;
		for (Map.Entry<Integer, Long> entry : handles.entrySet()) {
			species[index] = entry.getKey();
			amounts[index++] = entry.getValue();
		}
		return new ChemicalMixtureRecord(species, amounts, massResidualUnits);
	}

	public int entryCount() { return speciesHandles.length; }
	public int speciesHandle(int entry) { return speciesHandles[entry]; }
	public long amountAt(int entry) { return amounts[entry]; }
	public long massResidualUnits() { return massResidualUnits; }
	public double massResidualKilograms() { return mmm.units.Mass.kilograms(massResidualUnits); }

	/** Preserves the immutable species inventory and its registry-specific exact-mass memo. */
	public ChemicalMixtureRecord withMassResidualUnits(long residual) {
		return residual == massResidualUnits ? this : new ChemicalMixtureRecord(this, residual);
	}
	public int[] copySpeciesHandles() { return speciesHandles.clone(); }
	public long[] copyAmounts() { return amounts.clone(); }

	public long amount(int speciesHandle) {
		int index = Arrays.binarySearch(speciesHandles, speciesHandle);
		return index < 0 ? 0L : amounts[index];
	}

	public long totalQuanta() {
		return totalQuanta;
	}

	public ExactRational exactSpeciesMassKilograms(ChemicalRegistry registry) {
		SpeciesMass cached = speciesMass;
		if (cached != null && cached.registry() == registry) return cached.kilograms();
		ExactRational result = ExactRational.ZERO;
		for (int i = 0; i < speciesHandles.length; i++) {
			result = result.add(registry.speciesMass(speciesHandles[i], amounts[i]));
		}
		speciesMass = new SpeciesMass(registry, result);
		return result;
	}

	public long speciesMassUnits(ChemicalRegistry registry) { return mmm.units.Mass.exactUnits(exactSpeciesMassKilograms(registry).numerator(), exactSpeciesMassKilograms(registry).denominator()); }
	public long massUnits(ChemicalRegistry registry) {
		return Math.addExact(speciesMassUnits(registry), massResidualUnits);
	}
	public double massKilograms(ChemicalRegistry registry) { return mmm.units.Mass.kilograms(massUnits(registry)); }

	public static long massUnitsAfterSpeciesChange(long previous, ExactRational speciesDeltaKilograms) {
		return Math.addExact(previous, mmm.units.Mass.exactUnits(speciesDeltaKilograms.numerator(), speciesDeltaKilograms.denominator()));
	}

	public long chargeQuanta(ChemicalRegistry registry) {
		long result = 0L;
		for (int i = 0; i < speciesHandles.length; i++) {
			result = Math.addExact(result, Math.multiplyExact(amounts[i], registry.species(speciesHandles[i]).charge()));
		}
		return result;
	}

	public Map<String, Long> stableAmounts(ChemicalRegistry registry) {
		TreeMap<String, Long> result = new TreeMap<>();
		for (int i = 0; i < speciesHandles.length; i++) result.put(registry.species(speciesHandles[i]).id(), amounts[i]);
		return Map.copyOf(result);
	}

	@Override public boolean equals(Object other) {
		return this == other || other instanceof ChemicalMixtureRecord record
				&& massResidualUnits == record.massResidualUnits
				&& Arrays.equals(speciesHandles, record.speciesHandles)
				&& Arrays.equals(amounts, record.amounts);
	}
	@Override public int hashCode() { return hash; }
	@Override public String toString() { return "ChemicalMixtureRecord[entries=" + entryCount() + ",quanta=" + totalQuanta() + "]"; }
}
