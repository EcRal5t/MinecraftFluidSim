package mmm.chemistry;

import mmm.units.SignedDyadic;
import mmm.units.SolverUnitScale;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Immutable absolute parcel inventory; runtime species handles are sorted and unique. */
/** Immutable species-quanta inventory whose exact and residual mass columns are both kilograms. */
public final class ChemicalMixtureRecord {
	private final int[] speciesHandles;
	private final long[] amounts;
	private final long massResidualKilogramsBits;
	private final int hash;
	private final long totalQuanta;
	// A single immutable memo keeps registry identity and value atomically paired across readers.
	private volatile SpeciesMass speciesMass;
	private record SpeciesMass(ChemicalRegistry registry, ExactRational kilograms) { }

	public ChemicalMixtureRecord(int[] speciesHandles, long[] amounts, long massResidualKilogramsBits) {
		if (speciesHandles == null || amounts == null || speciesHandles.length == 0
				|| speciesHandles.length != amounts.length
				|| speciesHandles.length > ChemicalLimits.MAX_SPECIES_PER_PARCEL) {
			throw new IllegalArgumentException("Chemical parcel entries are empty or over budget");
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
		double residual = Double.longBitsToDouble(massResidualKilogramsBits);
		if (!Double.isFinite(residual)) throw new IllegalArgumentException("Chemical mass residual is not finite");
		this.speciesHandles = speciesHandles.clone();
		this.amounts = amounts.clone();
		this.massResidualKilogramsBits = massResidualKilogramsBits;
		this.totalQuanta = total;
		this.hash = 31 * (31 * Arrays.hashCode(this.speciesHandles) + Arrays.hashCode(this.amounts))
				+ Long.hashCode(massResidualKilogramsBits);
	}

	private ChemicalMixtureRecord(ChemicalMixtureRecord source, long residualBits) {
		if (!Double.isFinite(Double.longBitsToDouble(residualBits))) throw new IllegalArgumentException("Chemical mass residual is not finite");
		speciesHandles = source.speciesHandles;
		amounts = source.amounts;
		totalQuanta = source.totalQuanta;
		massResidualKilogramsBits = residualBits;
		hash = source.hash - Long.hashCode(source.massResidualKilogramsBits) + Long.hashCode(residualBits);
		speciesMass = source.speciesMass;
	}

	public static ChemicalMixtureRecord fromStableAmounts(
			ChemicalRegistry registry, Map<String, Long> stableAmounts, double massResidualKilograms
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
		return new ChemicalMixtureRecord(species, amounts, Double.doubleToRawLongBits(massResidualKilograms));
	}

	public int entryCount() { return speciesHandles.length; }
	public int speciesHandle(int entry) { return speciesHandles[entry]; }
	public long amountAt(int entry) { return amounts[entry]; }
	public long massResidualKilogramsBits() { return massResidualKilogramsBits; }
	public double massResidualKilograms() { return Double.longBitsToDouble(massResidualKilogramsBits); }

	/** Preserves the immutable species inventory and its registry-specific exact-mass memo. */
	public ChemicalMixtureRecord withMassResidualKilograms(double residual) {
		long bits = Double.doubleToRawLongBits(residual);
		return bits == massResidualKilogramsBits ? this : new ChemicalMixtureRecord(this, bits);
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

	public double massKilograms(ChemicalRegistry registry) {
		return exactSpeciesMassKilograms(registry).toDouble() + massResidualKilograms();
	}

	/** Updates the authoritative solver mass by an exact species delta, preserving its residual. */
	public static double solverMassAfterSpeciesChange(
			double previous, ExactRational speciesDeltaKilograms, SolverUnitScale unitScale
	) {
		if (speciesDeltaKilograms.isZero()) return previous;
		ExactRational scale = exactDouble(unitScale.cubicMetersPerNormalizedVolume());
		ExactRational increment = new ExactRational(
				speciesDeltaKilograms.numerator().multiply(scale.denominator()),
				speciesDeltaKilograms.denominator().multiply(scale.numerator()));
		return exactDouble(previous).add(increment).toDouble();
	}

	private static ExactRational exactDouble(double value) {
		SignedDyadic exact = SignedDyadic.of(value);
		return exact.exponent() >= 0 ? new ExactRational(exact.significand().shiftLeft(exact.exponent()), BigInteger.ONE)
				: new ExactRational(exact.significand(), BigInteger.ONE.shiftLeft(-exact.exponent()));
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
				&& massResidualKilogramsBits == record.massResidualKilogramsBits
				&& Arrays.equals(speciesHandles, record.speciesHandles)
				&& Arrays.equals(amounts, record.amounts);
	}
	@Override public int hashCode() { return hash; }
	@Override public String toString() { return "ChemicalMixtureRecord[entries=" + entryCount() + ",quanta=" + totalQuanta() + "]"; }
}
