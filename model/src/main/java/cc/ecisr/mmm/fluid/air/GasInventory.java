package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.units.SignedDyadic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongFunction;

/** Canonical immutable component inventory used by gas nodes and persistence. */
public final class GasInventory {
	public static final GasInventory EMPTY = new GasInventory(List.of());

	private final List<GasComponentAmount> components;
	private final long standardVolumeNanos;

	public GasInventory(List<GasComponentAmount> components) {
		if (components == null || components.size() > GasLimits.MAX_COMPONENTS_PER_NODE) {
			throw new IllegalArgumentException("Gas inventory is missing or over budget");
		}
		TreeMap<String, GasComponentAmount> canonical = new TreeMap<>();
		for (GasComponentAmount component : components) {
			if (component == null || canonical.put(component.componentId(), component) != null) {
				throw new IllegalArgumentException("Gas inventory contains null or duplicate components");
			}
		}
		this.components = List.copyOf(canonical.values());
		long total = 0L;
		for (GasComponentAmount component : this.components) total = Math.addExact(total, component.standardVolumeNanos());
		this.standardVolumeNanos = total;
	}

	public List<GasComponentAmount> components() { return components; }
	public long standardVolumeNanos() { return standardVolumeNanos; }
	public boolean isEmpty() { return components.isEmpty(); }

	/** Derived physical mass in kg, summed in canonical component order. */
	public double totalMass() {
		double result = 0.0;
		for (GasComponentAmount component : components) result += component.mass();
		if (!(Double.isFinite(result) && result >= 0.0)) {
			throw new IllegalStateException("Gas inventory mass is not finite");
		}
		return result;
	}

	/** Physical kg/m³: exact component sum divided by volume, rounded once in the final unit. */
	public double density(double availableVolume) {
		if (!(Double.isFinite(availableVolume) && availableVolume >= 0.0)
				|| availableVolume == 0.0 && !isEmpty()) {
			throw new IllegalArgumentException("Gas density needs a valid available volume");
		}
		if (isEmpty()) return 0.0;
		SignedDyadic mass = SignedDyadic.ZERO;
		for (GasComponentAmount component : components) mass = mass.add(SignedDyadic.of(component.mass()));
		SignedDyadic volume = SignedDyadic.of(availableVolume);
		int power = mass.exponent() - volume.exponent();
		BigInteger numerator = mass.significand(), denominator = volume.significand();
		if (power >= 0) numerator = numerator.shiftLeft(power);
		else denominator = denominator.shiftLeft(-power);
		double result = new ExactRational(numerator, denominator).toDouble();
		if (!(Double.isFinite(result) && result >= 0.0)) {
			throw new IllegalStateException("Gas inventory density is not finite");
		}
		return result;
	}

	public GasComponentAmount component(String componentId) {
		for (GasComponentAmount component : components) if (component.componentId().equals(componentId)) return component;
		return null;
	}

	public GasInventory merge(GasInventory other) {
		if (other.isEmpty()) return this;
		if (isEmpty()) return other;
		TreeMap<String, GasComponentAmount> merged = new TreeMap<>();
		for (GasComponentAmount component : components) merged.put(component.componentId(), component);
		for (GasComponentAmount right : other.components) {
			GasComponentAmount left = merged.get(right.componentId());
			merged.put(right.componentId(), left == null ? right : merge(left, right));
		}
		return new GasInventory(List.copyOf(merged.values()));
	}

	/** Splits an exact total-nanos portion from this mixture without creating or deleting any integer column. */
	public Split splitTotal(long firstNanos) {
		if (firstNanos < 0L || firstNanos > standardVolumeNanos) {
			throw new IllegalArgumentException("Gas split volume is outside the inventory");
		}
		if (firstNanos == 0L) return new Split(EMPTY, this);
		if (firstNanos == standardVolumeNanos) return new Split(this, EMPTY);
		long[] selected = largestRemainder(firstNanos,
				components.stream().map(c -> BigInteger.valueOf(c.standardVolumeNanos())).toList());
		ArrayList<GasComponentAmount> first = new ArrayList<>(), second = new ArrayList<>();
		for (int index = 0; index < components.size(); index++) {
			GasComponentAmount component = components.get(index);
			GasComponentAmount[] parts = splitComponent(component,
					new long[] { selected[index], component.standardVolumeNanos() - selected[index] });
			if (parts[0] != null) first.add(parts[0]);
			if (parts[1] != null) second.add(parts[1]);
		}
		return new Split(new GasInventory(first), new GasInventory(second));
	}

	/** Selects an exact nanos portion of one stable component, leaving every other component untouched. */
	public Split splitComponent(String componentId, long selectedNanos) {
		GasComponentAmount selected = component(componentId);
		if (selected == null || selectedNanos < 0L || selectedNanos > selected.standardVolumeNanos()) {
			throw new IllegalArgumentException("Gas component split is outside the inventory");
		}
		if (selectedNanos == 0L) return new Split(EMPTY, this);
		GasComponentAmount[] portions = splitComponent(selected,
				new long[] {selectedNanos, selected.standardVolumeNanos() - selectedNanos});
		ArrayList<GasComponentAmount> remainder = new ArrayList<>(components.size());
		for (GasComponentAmount component : components) {
			if (!component.componentId().equals(componentId)) remainder.add(component);
			else if (portions[1] != null) remainder.add(portions[1]);
		}
		return new Split(new GasInventory(List.of(portions[0])), new GasInventory(remainder));
	}

	/**
	 * Selects one component with explicitly planned mass and residual columns. Nanos and species
	 * remain integer-conservative; any floating subtraction remainder stays in the gas remainder.
	 * This is the phase-boundary form used when a historical grouped nanos rounding is not exactly
	 * proportional to the transition's mass quantum.
	 */
	public Split splitComponent(
			String componentId, long selectedNanos, double selectedMass, double selectedMassResidual
	) {
		GasComponentAmount selected = component(componentId);
		if (selected == null || selectedNanos <= 0L || !Double.isFinite(selectedMass) || selectedMass <= 0.0
				|| !Double.isFinite(selectedMassResidual)) {
			throw new IllegalArgumentException("Gas component phase columns are invalid");
		}
		Split proportional = splitComponent(componentId, selectedNanos);
		GasComponentAmount first = proportional.first().components().getFirst();
		if (selectedNanos == selected.standardVolumeNanos()) {
			if (!closeColumn(selectedMass, selected.mass())
					|| !closeColumn(selectedMassResidual, selected.massResidual())) {
				throw new IllegalArgumentException("A complete gas component take must preserve all columns");
			}
			return proportional;
		}
		double remainingMass = selected.mass() - selectedMass;
		double remainingResidual = selected.massResidual() - selectedMassResidual;
		if (!(remainingMass > 0.0) || !Double.isFinite(remainingResidual)) {
			throw new IllegalArgumentException("Gas component phase remainder is invalid");
		}
		GasComponentAmount exactFirst = new GasComponentAmount(componentId, first.standardVolumeNanos(),
				selectedMass, first.species(), selectedMassResidual);
		GasComponentAmount proportionalRemainder = proportional.second().component(componentId);
		GasComponentAmount exactRemainder = new GasComponentAmount(componentId,
				proportionalRemainder.standardVolumeNanos(), remainingMass,
				proportionalRemainder.species(), remainingResidual);
		ArrayList<GasComponentAmount> remainder = new ArrayList<>(proportional.second().components().size());
		for (GasComponentAmount component : proportional.second().components()) {
			remainder.add(component.componentId().equals(componentId) ? exactRemainder : component);
		}
		return new Split(new GasInventory(List.of(exactFirst)), new GasInventory(remainder));
	}

	/** Partitions every component by non-negative topology weights. */
	public List<GasInventory> partition(long[] weights) {
		if (weights == null || weights.length == 0) throw new IllegalArgumentException("Gas partition needs children");
		for (long weight : weights) {
			if (weight < 0L) throw new IllegalArgumentException("Gas partition weight cannot be negative");
		}
		List<BigInteger> exactWeights = Arrays.stream(weights).mapToObj(BigInteger::valueOf).toList();
		return partition(weights.length, total -> largestRemainder(total, exactWeights));
	}

	/** Exact topology weights retain positive sub-nano proportions without inventing integer shares. */
	public List<GasInventory> partition(List<ExactRational> weights) {
		if (weights == null || weights.isEmpty()) throw new IllegalArgumentException("Gas partition needs children");
		ExactRational sum = ExactRational.ZERO;
		for (ExactRational weight : weights) {
			if (weight == null || weight.signum() < 0) throw new IllegalArgumentException("Gas partition weight cannot be negative");
			// Reduce each sum before applying the rational representation budget.
			BigInteger common = sum.denominator().gcd(weight.denominator());
			BigInteger factor = weight.denominator().divide(common);
			BigInteger numerator = sum.numerator().multiply(factor)
					.add(weight.numerator().multiply(sum.denominator().divide(common)));
			BigInteger denominator = sum.denominator().multiply(factor);
			BigInteger divisor = numerator.gcd(denominator);
			sum = new ExactRational(numerator.divide(divisor), denominator.divide(divisor));
		}
		if (sum.signum() == 0) throw new IllegalArgumentException("Exact partition weights sum to zero");
		ArrayList<ExactRational> normalized = new ArrayList<>(weights.size());
		for (ExactRational weight : weights) {
			BigInteger first = weight.numerator().gcd(sum.numerator());
			BigInteger second = sum.denominator().gcd(weight.denominator());
			normalized.add(new ExactRational(weight.numerator().divide(first).multiply(sum.denominator().divide(second)),
					weight.denominator().divide(second).multiply(sum.numerator().divide(first))));
		}
		return partition(weights.size(), total -> rationalRemainder(total, normalized));
	}

	private List<GasInventory> partition(int childCount, LongFunction<long[]> allocation) {
		ArrayList<ArrayList<GasComponentAmount>> children = new ArrayList<>(childCount);
		for (int child = 0; child < childCount; child++) children.add(new ArrayList<>());
		for (GasComponentAmount component : components) {
			long[] shares = allocation.apply(component.standardVolumeNanos());
			GasComponentAmount[] parts = splitComponent(component, shares);
			for (int child = 0; child < parts.length; child++) if (parts[child] != null) children.get(child).add(parts[child]);
		}
		ArrayList<GasInventory> result = new ArrayList<>(childCount);
		for (List<GasComponentAmount> child : children) result.add(new GasInventory(child));
		return List.copyOf(result);
	}

	private static long[] rationalRemainder(long total, List<ExactRational> normalized) {
		long[] shares = new long[normalized.size()];
		ArrayList<RationalRemainder> remainders = new ArrayList<>(normalized.size());
		long assigned = 0;
		for (int index = 0; index < normalized.size(); index++) {
			ExactRational weight = normalized.get(index);
			BigInteger[] division = weight.numerator().multiply(BigInteger.valueOf(total)).divideAndRemainder(weight.denominator());
			shares[index] = division[0].longValueExact();
			assigned = Math.addExact(assigned, shares[index]);
			remainders.add(new RationalRemainder(index, new ExactRational(division[1], weight.denominator())));
		}
		remainders.sort(Comparator.comparing(RationalRemainder::value).reversed().thenComparingInt(RationalRemainder::index));
		for (long index = 0, left = total - assigned; index < left; index++) shares[remainders.get((int) index).index()]++;
		return shares;
	}

	static long[] largestRemainder(long total, List<BigInteger> weights) {
		if (total < 0L || weights == null || weights.isEmpty()) throw new IllegalArgumentException("Invalid exact partition");
		BigInteger sum = BigInteger.ZERO;
		for (BigInteger weight : weights) {
			if (weight == null || weight.signum() < 0) throw new IllegalArgumentException("Negative exact partition weight");
			sum = sum.add(weight);
		}
		if (sum.signum() <= 0) throw new IllegalArgumentException("Exact partition weights sum to zero");
		long[] shares = new long[weights.size()];
		ArrayList<Remainder> remainders = new ArrayList<>(weights.size());
		long assigned = 0L;
		for (int index = 0; index < weights.size(); index++) {
			BigInteger[] division = weights.get(index).multiply(BigInteger.valueOf(total)).divideAndRemainder(sum);
			shares[index] = division[0].longValueExact();
			assigned = Math.addExact(assigned, shares[index]);
			remainders.add(new Remainder(index, division[1]));
		}
		remainders.sort(Comparator.comparing(Remainder::value).reversed().thenComparingInt(Remainder::index));
		for (long left = total - assigned, index = 0; index < left; index++) shares[remainders.get((int) index).index()]++;
		return shares;
	}

	private static GasComponentAmount merge(GasComponentAmount first, GasComponentAmount second) {
		TreeMap<String, Long> species = new TreeMap<>();
		for (GasComponentAmount.SpeciesAmount amount : first.species()) species.put(amount.speciesId(), amount.amountQuanta());
		for (GasComponentAmount.SpeciesAmount amount : second.species()) {
			species.merge(amount.speciesId(), amount.amountQuanta(), Math::addExact);
		}
		return new GasComponentAmount(first.componentId(),
				Math.addExact(first.standardVolumeNanos(), second.standardVolumeNanos()), first.mass() + second.mass(),
				species.entrySet().stream().map(entry -> new GasComponentAmount.SpeciesAmount(entry.getKey(), entry.getValue())).toList(),
				first.massResidual() + second.massResidual());
	}

	private static GasComponentAmount[] splitComponent(GasComponentAmount source, long[] nanos) {
		long sum = 0L;
		for (long value : nanos) {
			if (value < 0L) throw new IllegalArgumentException("Gas component split volume cannot be negative");
			sum = Math.addExact(sum, value);
		}
		if (sum != source.standardVolumeNanos()) throw new IllegalArgumentException("Gas component split does not close");
		long[][] speciesShares = new long[source.species().size()][];
		List<BigInteger> weights = Arrays.stream(nanos).mapToObj(BigInteger::valueOf).toList();
		for (int species = 0; species < source.species().size(); species++) {
			speciesShares[species] = largestRemainder(source.species().get(species).amountQuanta(), weights);
		}
		GasComponentAmount[] result = new GasComponentAmount[nanos.length];
		SignedDyadic assignedMass = SignedDyadic.ZERO, assignedResidual = SignedDyadic.ZERO;
		int pivot = 0;
		for (int child = 1; child < nanos.length; child++) if (nanos[child] > nanos[pivot]) pivot = child;
		// The largest share absorbs the source reduction error; tiny children retain their own scale.
		for (int ordinal = 0; ordinal < nanos.length; ordinal++) {
			int child = ordinal == nanos.length - 1 ? pivot : ordinal < pivot ? ordinal : ordinal + 1;
			if (nanos[child] == 0L) continue;
			double mass, residual;
			if (child == pivot) {
				mass = SignedDyadic.of(source.mass()).subtract(assignedMass).toDouble();
				residual = SignedDyadic.of(source.massResidual()).subtract(assignedResidual).toDouble();
			} else {
				double fraction = (double) nanos[child] / source.standardVolumeNanos();
				mass = source.mass() * fraction;
				residual = source.massResidual() * fraction;
				assignedMass = assignedMass.add(SignedDyadic.of(mass));
				assignedResidual = assignedResidual.add(SignedDyadic.of(residual));
			}
			ArrayList<GasComponentAmount.SpeciesAmount> species = new ArrayList<>();
			for (int entry = 0; entry < source.species().size(); entry++) if (speciesShares[entry][child] > 0L) {
				species.add(new GasComponentAmount.SpeciesAmount(source.species().get(entry).speciesId(), speciesShares[entry][child]));
			}
			result[child] = new GasComponentAmount(source.componentId(), nanos[child], mass, species, residual);
		}
		return result;
	}

	public record Split(GasInventory first, GasInventory second) { }
	private record Remainder(int index, BigInteger value) { }
	private record RationalRemainder(int index, ExactRational value) { }
	private static boolean closeColumn(double first, double second) {
		return Math.abs(first - second) <= Math.max(1.0E-12,
				Math.max(Math.ulp(first), Math.ulp(second)) * 16.0);
	}

	@Override public boolean equals(Object other) {
		return other instanceof GasInventory inventory && components.equals(inventory.components);
	}
	@Override public int hashCode() { return components.hashCode(); }
	@Override public String toString() { return components.toString(); }
}
