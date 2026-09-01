package cc.ecisr.mmm.granular;

import cc.ecisr.mmm.chemistry.BinaryVolumeAllocator;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.units.SolverUnitScale;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/** Pure same-cell saturation planner. Store ownership and commits remain in the domain adapter. */
public final class GranularPhaseTransferEngine {
	private final ChemicalRegistry chemistry;
	private final SolverUnitScale unitScale;

	public GranularPhaseTransferEngine(ChemicalRegistry chemistry, SolverUnitScale unitScale) {
		this.chemistry = java.util.Objects.requireNonNull(chemistry, "chemistry");
		this.unitScale = java.util.Objects.requireNonNull(unitScale, "unitScale");
	}

	public Plan plan(
			SolidLiquidReaction rule, List<LiquidParcel> liquid, long existingAccessibleExtent,
			long maximumTransferExtent
	) {
		if (rule == null || liquid == null || liquid.isEmpty() || existingAccessibleExtent < 0L
				|| maximumTransferExtent <= 0L) throw new IllegalArgumentException("Invalid phase-transfer planning input");
		long[] inventory = new long[chemistry.species().size()];
		int[] ordinals = new int[liquid.size()]; float[] massWeights = new float[liquid.size()];
		for (int index = 0; index < liquid.size(); index++) {
			LiquidParcel parcel = liquid.get(index); ordinals[index] = parcel.marker();
			if (index > 0 && ordinals[index - 1] >= ordinals[index]) {
				throw new IllegalArgumentException("Phase-transfer liquid markers are not sorted and unique");
			}
			for (int entry = 0; entry < parcel.mixture().entryCount(); entry++) inventory[parcel.mixture().speciesHandle(entry)] =
					Math.addExact(inventory[parcel.mixture().speciesHandle(entry)], parcel.mixture().amountAt(entry));
			double exactMass = parcel.mixture().exactSpeciesMassKilograms(chemistry).toDouble(); massWeights[index] = (float) exactMass;
			if (!(Float.isFinite(massWeights[index]) && massWeights[index] > 0.0F)) {
				throw new BlockedException("Chemical marker exact mass is not a usable phase-transfer weight");
			}
		}
		long available = Long.MAX_VALUE;
		for (Map.Entry<String, Integer> term : rule.aqueousStoichiometry().entrySet()) {
			available = Math.min(available, inventory[chemistry.speciesHandle(term.getKey())] / term.getValue());
		}
		if (available == Long.MAX_VALUE) available = 0L;
		long capacity = floorCapacity(rule.saturationCapacityExtentPerBv(), liquid);
		long target = Math.max(0L, Math.subtractExact(Math.addExact(available, existingAccessibleExtent), capacity));
		long signedExtent = target - existingAccessibleExtent;
		if (signedExtent == 0L) return Plan.none();
		long magnitude = Math.min(Math.abs(signedExtent), maximumTransferExtent);
		magnitude = magnitude / rule.solidExtentPerBatch() * rule.solidExtentPerBatch();
		if (magnitude <= 0L) return Plan.none();
		boolean precipitation = signedExtent > 0L;
		for (; magnitude > 0L; magnitude -= rule.solidExtentPerBatch()) {
			Plan candidate = planRepresentableMagnitude(rule, liquid, inventory, magnitude, precipitation, ordinals,
					massWeights);
			if (candidate != null) return candidate;
		}
		return Plan.none();
	}

	/** Returns null when this exact reaction batch cannot be represented by the current marker lattice. */
	private Plan planRepresentableMagnitude(
			SolidLiquidReaction rule, List<LiquidParcel> liquid, long[] inventory, long magnitude,
			boolean precipitation, int[] ordinals, float[] massWeights
	) {
		long solidSvq = Math.multiplyExact(magnitude / rule.solidExtentPerBatch(), rule.solidVolumeSvqPerBatch());

		TreeMap<Integer, Long> transferred = new TreeMap<>();
		for (Map.Entry<String, Integer> term : rule.aqueousStoichiometry().entrySet()) transferred.put(
				chemistry.speciesHandle(term.getKey()), Math.multiplyExact(magnitude, term.getValue()));
		long[] resultInventory = inventory.clone();
		for (Map.Entry<Integer, Long> term : transferred.entrySet()) resultInventory[term.getKey()] = precipitation
				? Math.subtractExact(resultInventory[term.getKey()], term.getValue())
				: Math.addExact(resultInventory[term.getKey()], term.getValue());
		for (long amount : resultInventory) if (amount < 0L) throw new BlockedException("Phase transfer lacks aqueous species");

		@SuppressWarnings("unchecked") TreeMap<Integer, Long>[] distributed = new TreeMap[liquid.size()];
		for (int index = 0; index < distributed.length; index++) distributed[index] = new TreeMap<>();
		for (int species = 0; species < resultInventory.length; species++) if (resultInventory[species] > 0L) {
			long[] allocation = BinaryVolumeAllocator.allocate(resultInventory[species], massWeights, ordinals);
			for (int index = 0; index < allocation.length; index++) if (allocation[index] > 0L) {
				distributed[index].put(species, allocation[index]);
			}
		}
		if (!repairEmpty(distributed, ordinals)) return null;
		for (TreeMap<Integer, Long> marker : distributed) {
			if (marker.size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL) return null;
		}
		long[] volumeDeltas = representableVolumeAllocation(solidSvq, liquid, ordinals, precipitation);
		if (volumeDeltas == null) return null;
		MarkerUpdate[] updates = new MarkerUpdate[liquid.size()];
		double oldMass = 0.0, newMass = 0.0;
		for (int index = 0; index < liquid.size(); index++) {
			LiquidParcel source = liquid.get(index);
			ChemicalMixtureRecord replacement = record(
					distributed[index], source.mixture().massResidualKilogramsBits());
			double massKilograms = replacement.massKilograms(chemistry);
			if (!(Double.isFinite(massKilograms) && massKilograms > 0.0)) {
				throw new BlockedException("Phase transfer produced non-positive marker mass");
			}
			double solverMass = unitScale.solverMass(massKilograms);
			long signedVolumeDelta = precipitation ? -volumeDeltas[index] : volumeDeltas[index];
			float cellVolume;
			try { cellVolume = SolidVolume.addCellVolumeSvqExact(source.cellVolume(), signedVolumeDelta); }
			catch (IllegalArgumentException unrepresentable) {
				throw new BlockedException("Liquid marker cannot represent the exact SVQ phase-transfer delta");
			}
			updates[index] = new MarkerUpdate(source.marker(), replacement, cellVolume, solverMass);
			oldMass += unitScale.kilograms(source.mass()); newMass += massKilograms;
		}
		SolidCompositionRecord solid = recordSolid(transferred);
		double solidMass = solid.totalMass(chemistry);
		double expectedNew = precipitation ? oldMass - solidMass : oldMass + solidMass;
		if (!massClose(newMass, expectedNew)) throw new BlockedException("Liquid/solid phase-transfer mass did not close");
		return new Plan(precipitation ? magnitude : -magnitude, precipitation ? solidSvq : -solidSvq,
				precipitation ? solidMass : -solidMass, solid, List.of(updates));
	}

	/**
	 * Allocates the exact solid SVQ total on the binary32 lattice already owned by each liquid
	 * marker. A proportional largest-remainder allocation is the preference, not a commit
	 * invariant: any leftover is deterministically reassigned before the caller tries a smaller
	 * whole reaction batch.
	 */
	private static long[] representableVolumeAllocation(
			long total, List<LiquidParcel> liquid, int[] ordinals, boolean precipitation
	) {
		float[] weights = new float[liquid.size()];
		for (int index = 0; index < weights.length; index++) weights[index] = liquid.get(index).cellVolume();
		long[] preferred = BinaryVolumeAllocator.allocate(total, weights, ordinals);
		long[] result = new long[liquid.size()];
		long assigned = 0L;
		for (int index = 0; index < result.length; index++) {
			long limit = precipitation ? maximumRemovableSvq(liquid.get(index).cellVolume()) : total;
			result[index] = representableDeltaAtMost(
					liquid.get(index).cellVolume(), Math.min(preferred[index], limit), precipitation);
			assigned = Math.addExact(assigned, result[index]);
		}
		long remaining = total - assigned;
		for (int index = 0; index < result.length && remaining > 0L; index++) {
			long limit = precipitation ? maximumRemovableSvq(liquid.get(index).cellVolume()) : total;
			long candidate = representableDeltaAtMost(liquid.get(index).cellVolume(),
					Math.min(limit, Math.addExact(result[index], remaining)), precipitation);
			if (candidate <= result[index]) continue;
			remaining -= candidate - result[index];
			result[index] = candidate;
		}
		return remaining == 0L ? result : null;
	}

	private static long maximumRemovableSvq(float volume) {
		long maximum = SolidVolume.cellVolumeSvqAtMost(volume);
		double scaled = Math.scalb((double) volume, 24);
		return scaled == Math.rint(scaled) ? Math.max(0L, maximum - 1L) : maximum;
	}

	/** Largest non-negative integer SVQ delta no greater than requested that lands on a float. */
	private static long representableDeltaAtMost(float volume, long requested, boolean precipitation) {
		if (requested <= 0L) return 0L;
		double exact = (double) volume + Math.scalb(precipitation ? -requested : requested, -24);
		if (!(Double.isFinite(exact) && exact > 0.0 && exact <= Float.MAX_VALUE)) return 0L;
		float candidate = (float) exact;
		if (precipitation && candidate < exact) candidate = Math.nextUp(candidate);
		if (!precipitation && candidate > exact) candidate = Math.nextDown(candidate);
		while (candidate != volume) {
			double scaled = Math.scalb(precipitation
					? (double) volume - candidate : (double) candidate - volume, 24);
			if (scaled >= 0.0 && scaled <= requested && scaled == Math.rint(scaled)) return (long) scaled;
			candidate = precipitation ? Math.nextUp(candidate) : Math.nextDown(candidate);
		}
		return 0L;
	}

	private ChemicalMixtureRecord record(Map<Integer, Long> values, long residualBits) {
		if (values.isEmpty() || values.size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL) {
			throw new BlockedException("Phase transfer exceeds the chemical marker entry budget");
		}
		int[] species = new int[values.size()]; long[] amounts = new long[values.size()]; int index = 0;
		for (Map.Entry<Integer, Long> entry : values.entrySet()) {
			species[index] = entry.getKey(); amounts[index++] = entry.getValue();
		}
		return new ChemicalMixtureRecord(species, amounts, residualBits);
	}

	private SolidCompositionRecord recordSolid(Map<Integer, Long> values) {
		int[] species = new int[values.size()]; long[] amounts = new long[values.size()]; int index = 0;
		for (Map.Entry<Integer, Long> entry : values.entrySet()) {
			species[index] = entry.getKey(); amounts[index++] = entry.getValue();
		}
		return new SolidCompositionRecord(species, amounts, Double.doubleToRawLongBits(0.0));
	}

	private static long floorCapacity(ExactRational capacityPerBv, List<LiquidParcel> liquid) {
		int minimumExponent = Integer.MAX_VALUE;
		Dyadic[] volumes = new Dyadic[liquid.size()];
		for (int index = 0; index < volumes.length; index++) {
			volumes[index] = dyadic(liquid.get(index).cellVolume());
			minimumExponent = Math.min(minimumExponent, volumes[index].exponent());
		}
		BigInteger exactCellVolume = BigInteger.ZERO;
		for (Dyadic volume : volumes) exactCellVolume = exactCellVolume.add(
				BigInteger.valueOf(volume.significand()).shiftLeft(volume.exponent() - minimumExponent));
		BigInteger numerator = capacityPerBv.numerator().multiply(exactCellVolume);
		BigInteger denominator = capacityPerBv.denominator().multiply(BigInteger.valueOf(
				SolidVolume.SVQ_PER_BV / SolidVolume.SVQ_PER_CELL));
		if (minimumExponent >= 0) numerator = numerator.shiftLeft(minimumExponent);
		else denominator = denominator.shiftLeft(-minimumExponent);
		try { return numerator.divide(denominator).longValueExact(); }
		catch (ArithmeticException overBudget) { throw new BlockedException("Liquid saturation capacity is over budget"); }
	}

	private static Dyadic dyadic(float value) {
		int bits = Float.floatToRawIntBits(value);
		int exponentBits = bits >>> 23 & 0xff;
		int fraction = bits & 0x7f_ffff;
		return exponentBits == 0
				? new Dyadic(fraction, -149)
				: new Dyadic((1 << 23) | fraction, exponentBits - 150);
	}

	private static boolean repairEmpty(TreeMap<Integer, Long>[] distributed, int[] ordinals) {
		long[] totals = new long[distributed.length]; ArrayList<Integer> empty = new ArrayList<>();
		PriorityQueue<Integer> donors = new PriorityQueue<>((left, right) -> {
			int total = Long.compare(totals[right], totals[left]);
			return total != 0 ? total : Integer.compare(ordinals[left], ordinals[right]);
		});
		for (int index = 0; index < distributed.length; index++) {
			for (long amount : distributed[index].values()) totals[index] = Math.addExact(totals[index], amount);
			if (totals[index] == 0L) empty.add(index); else if (totals[index] > 1L) donors.add(index);
		}
		for (int target : empty) {
			Integer donor = donors.poll();
			if (donor == null) return false;
			Map.Entry<Integer, Long> largest = distributed[donor].entrySet().stream()
					.max(ComparatorByAmount.INSTANCE).orElseThrow();
			if (largest.getValue() == 1L) distributed[donor].remove(largest.getKey());
			else distributed[donor].put(largest.getKey(), largest.getValue() - 1L);
			distributed[target].put(largest.getKey(), 1L); totals[donor]--; totals[target] = 1L;
			if (totals[donor] > 1L) donors.add(donor);
		}
		return true;
	}

	private static boolean massClose(double left, double right) {
		return Math.abs(left - right) <= Math.max(1.0E-9, Math.max(Math.ulp(left), Math.ulp(right)) * 8.0);
	}

	public record LiquidParcel(int marker, float cellVolume, double mass, ChemicalMixtureRecord mixture) {
		public LiquidParcel {
			if (marker < 0 || !(Float.isFinite(cellVolume) && cellVolume > 0.0F)
					|| !(Double.isFinite(mass) && mass > 0.0) || mixture == null) {
				throw new IllegalArgumentException("Invalid phase-transfer liquid parcel");
			}
		}
	}
	public record MarkerUpdate(int marker, ChemicalMixtureRecord mixture, float cellVolume, double mass) { }
	public record Plan(long signedExtent, long signedSolidSvq, double signedSolidMass,
	                   SolidCompositionRecord transferredComposition, List<MarkerUpdate> markerUpdates) {
		static Plan none() { return new Plan(0L, 0L, 0.0, null, List.of()); }
		public boolean changed() { return signedExtent != 0L; }
		public boolean precipitation() { return signedExtent > 0L; }
	}

	private enum ComparatorByAmount implements java.util.Comparator<Map.Entry<Integer, Long>> {
		INSTANCE;
		@Override public int compare(Map.Entry<Integer, Long> left, Map.Entry<Integer, Long> right) {
			int amount = Long.compare(left.getValue(), right.getValue());
			return amount != 0 ? amount : Integer.compare(right.getKey(), left.getKey());
		}
	}

	private record Dyadic(int significand, int exponent) { }

	public static final class BlockedException extends IllegalStateException {
		public BlockedException(String message) { super(message); }
	}
}
