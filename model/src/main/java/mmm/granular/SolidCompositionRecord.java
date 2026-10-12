package mmm.granular;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ExactRational;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.math.BigInteger;

/** Immutable absolute solid species inventory; indicator and liquid phase never enter this record. */
/** Immutable solid species inventory whose exact and residual masses are kilograms. */
public final class SolidCompositionRecord {
	private final int[] speciesHandles;
	private final long[] amounts;
	private final long massResidualBits;
	private final int hash;

	public SolidCompositionRecord(int[] speciesHandles, long[] amounts, long massResidualBits) {
		if (speciesHandles == null || amounts == null || speciesHandles.length == 0
				|| speciesHandles.length != amounts.length
				|| speciesHandles.length > GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES) {
			throw new IllegalArgumentException("Solid composition entries are empty or over budget");
		}
		long total = 0L;
		for (int index = 0; index < speciesHandles.length; index++) {
			if (speciesHandles[index] < 0 || index > 0 && speciesHandles[index - 1] >= speciesHandles[index]
					|| amounts[index] <= 0L) {
				throw new IllegalArgumentException("Solid composition entries are not canonical");
			}
			total = Math.addExact(total, amounts[index]);
		}
		if (total > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
			throw new IllegalArgumentException("Solid composition amount is over budget");
		}
		double residual = Double.longBitsToDouble(massResidualBits);
		if (!Double.isFinite(residual)) throw new IllegalArgumentException("Solid mass residual is not finite");
		this.speciesHandles = speciesHandles.clone(); this.amounts = amounts.clone();
		this.massResidualBits = massResidualBits;
		this.hash = 31 * (31 * Arrays.hashCode(this.speciesHandles) + Arrays.hashCode(this.amounts))
				+ Long.hashCode(massResidualBits);
	}

	public static SolidCompositionRecord fromStableAmounts(
			ChemicalRegistry registry, Map<String, Long> stableAmounts, double massResidual
	) {
		TreeMap<Integer, Long> handles = new TreeMap<>();
		for (Map.Entry<String, Long> entry : stableAmounts.entrySet()) {
			long amount = entry.getValue() == null ? 0L : entry.getValue();
			if (amount <= 0L) throw new IllegalArgumentException("Solid species amount must be positive");
			handles.merge(registry.speciesHandle(entry.getKey()), amount, Math::addExact);
		}
		int[] species = new int[handles.size()]; long[] amounts = new long[handles.size()]; int index = 0;
		for (Map.Entry<Integer, Long> entry : handles.entrySet()) {
			species[index] = entry.getKey(); amounts[index++] = entry.getValue();
		}
		return new SolidCompositionRecord(species, amounts, Double.doubleToRawLongBits(massResidual));
	}

	public int entryCount() { return speciesHandles.length; }
	public int speciesHandle(int entry) { return speciesHandles[entry]; }
	public long amountAt(int entry) { return amounts[entry]; }
	public long amount(int speciesHandle) {
		int index = Arrays.binarySearch(speciesHandles, speciesHandle);
		return index < 0 ? 0L : amounts[index];
	}
	public long totalQuanta() {
		long result = 0L; for (long amount : amounts) result = Math.addExact(result, amount); return result;
	}
	public long massResidualBits() { return massResidualBits; }
	public double massResidual() { return Double.longBitsToDouble(massResidualBits); }
	public int[] copySpeciesHandles() { return speciesHandles.clone(); }
	public long[] copyAmounts() { return amounts.clone(); }

	public ExactRational exactSpeciesMass(ChemicalRegistry registry) {
		ExactRational result = ExactRational.ZERO;
		for (int index = 0; index < speciesHandles.length; index++) {
			result = result.add(registry.species(speciesHandles[index]).massKilogramsPerAu().multiply(amounts[index])
					.divide(ChemicalLimits.QUANTA_PER_AU));
		}
		return result;
	}

	public double totalMass(ChemicalRegistry registry) { return exactSpeciesMass(registry).toDouble() + massResidual(); }
	public long chargeQuanta(ChemicalRegistry registry) {
		long result = 0L;
		for (int index = 0; index < speciesHandles.length; index++) result = Math.addExact(result,
				Math.multiplyExact(amounts[index], registry.species(speciesHandles[index]).charge()));
		return result;
	}
	public Map<String, Long> stableAmounts(ChemicalRegistry registry) {
		TreeMap<String, Long> result = new TreeMap<>();
		for (int index = 0; index < speciesHandles.length; index++) {
			result.put(registry.species(speciesHandles[index]).id(), amounts[index]);
		}
		return Map.copyOf(result);
	}

	/** True when both absolute inventories describe the same species ratios per exact solid SVQ. */
	public boolean proportionalTo(SolidCompositionRecord other, long solidVolumeSvq, long otherSolidVolumeSvq) {
		if (other == null || solidVolumeSvq <= 0L || otherSolidVolumeSvq <= 0L
				|| !Arrays.equals(speciesHandles, other.speciesHandles)) return false;
		BigInteger leftVolume = BigInteger.valueOf(solidVolumeSvq);
		BigInteger rightVolume = BigInteger.valueOf(otherSolidVolumeSvq);
		for (int entry = 0; entry < amounts.length; entry++) {
			if (!BigInteger.valueOf(amounts[entry]).multiply(rightVolume).equals(
					BigInteger.valueOf(other.amounts[entry]).multiply(leftVolume))) return false;
		}
		return true;
	}

	/** Adds two absolute inventories after their grouping compatibility has been established. */
	public SolidCompositionRecord plus(SolidCompositionRecord other) {
		if (other == null || !Arrays.equals(speciesHandles, other.speciesHandles)) {
			throw new IllegalArgumentException("Solid compositions have different species palettes");
		}
		long[] combined = amounts.clone();
		for (int entry = 0; entry < combined.length; entry++) {
			combined[entry] = Math.addExact(combined[entry], other.amounts[entry]);
		}
		double residual = massResidual() + other.massResidual();
		if (!Double.isFinite(residual)) throw new ArithmeticException("Solid composition residual overflow");
		return new SolidCompositionRecord(speciesHandles, combined, Double.doubleToRawLongBits(residual));
	}

	@Override public boolean equals(Object other) {
		return this == other || other instanceof SolidCompositionRecord record
				&& massResidualBits == record.massResidualBits
				&& Arrays.equals(speciesHandles, record.speciesHandles) && Arrays.equals(amounts, record.amounts);
	}
	@Override public int hashCode() { return hash; }
}
