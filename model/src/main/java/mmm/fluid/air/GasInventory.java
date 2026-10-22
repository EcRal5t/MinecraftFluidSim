package mmm.fluid.air;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.function.LongFunction;

/** Canonical immutable component inventory used by gas nodes and persistence. */
public final class GasInventory {
	public static final GasInventory EMPTY = new GasInventory(List.of());

	/** A valid transfer cannot fit the finite gas quantity columns. */
	public static final class RepresentationException extends IllegalArgumentException {
		RepresentationException(String message) { super(message); }
	}

	static long addColumn(long first, long second, String column) {
		if (first < 0 || second < 0) throw new IllegalArgumentException("Negative gas " + column);
		if (first > Long.MAX_VALUE - second) throw new RepresentationException("Gas " + column + " exceeds its integer representation");
		return first + second;
	}

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
		long total = 0L, mass = 0L;
		for (GasComponentAmount component : this.components) {
			total = Math.addExact(total, component.standardVolumeNanos());
			mass = Math.addExact(mass, component.massUnits());
		}
		this.standardVolumeNanos = total;
	}

	public List<GasComponentAmount> components() { return components; }
	public long standardVolumeNanos() { return standardVolumeNanos; }
	public boolean isEmpty() { return components.isEmpty(); }

	public long totalMassUnits() {
		long result = 0;
		for (GasComponentAmount component : components) result = Math.addExact(result, component.massUnits());
		return result;
	}
	public double totalMass() { return mmm.units.Mass.kilograms(totalMassUnits()); }

	/** Physical kg/m³: exact component sum divided by volume, rounded once in the final unit. */
	public double density(double availableVolume) {
		if (!(Double.isFinite(availableVolume) && availableVolume >= 0.0)
				|| availableVolume == 0.0 && !isEmpty()) {
			throw new IllegalArgumentException("Gas density needs a valid available volume");
		}
		if (isEmpty()) return 0.0;
		double result = totalMass() / availableVolume;
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
		addColumn(standardVolumeNanos, other.standardVolumeNanos, "inventory standard volume");
		TreeMap<String, GasComponentAmount> merged = new TreeMap<>();
		for (GasComponentAmount component : components) merged.put(component.componentId(), component);
		for (GasComponentAmount right : other.components) {
			GasComponentAmount left = merged.get(right.componentId());
			merged.put(right.componentId(), left == null ? right : merge(left, right));
		}
		if (merged.size() > GasLimits.MAX_COMPONENTS_PER_NODE)
			throw new RepresentationException("Gas inventory exceeds its component slots");
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

	/** Shared systematic rounding keeps all integer columns conservative and trace gas mobile. */
	public List<GasInventory> partitionTransport(long[] weights) { return partitionTransport(weights, 0); }
	public List<GasInventory> partitionTransport(long[] weights, long sourcePosition) {
		if (weights == null || weights.length < 2 || weights[0] <= 0)
			throw new IllegalArgumentException("Gas transport needs a positive retained donor share");
		var columns = new ArrayList<ArrayList<GasComponentAmount>>();
		for (int i = 0; i < weights.length; i++) columns.add(new ArrayList<>());
		long key = mixPosition(standardVolumeNanos ^ Long.rotateLeft(sourcePosition, 17));
		for (var component : components) {
			long[] nanos = systematicShares(component.standardVolumeNanos(), weights, key);
			// A finite donor keeps its last representable component quantum.
			if (nanos[0] == 0) { int pivot = 1; for (int i = 2; i < nanos.length; i++) if (nanos[i] > nanos[pivot]) pivot = i; nanos[0]++; nanos[pivot]--; }
			for (int i = 1; i < nanos.length; i++) if (nanos[i] > 0
					&& mmm.units.Mass.portion(component.massUnits(), nanos[i], component.standardVolumeNanos()) == 0) { nanos[0] += nanos[i]; nanos[i] = 0; }
			long[] mass = systematicShares(component.massUnits(), nanos, key);
			for (int i = 0; i < mass.length; i++) if (nanos[i] > 0 && mass[i] == 0) {
				int pivot = 0; for (int j = 1; j < mass.length; j++) if (mass[j] > mass[pivot]) pivot = j;
				mass[pivot]--; mass[i]++;
			}
			long[] residual = systematicShares(component.massResidualUnits(), nanos, key);
			long[][] species = new long[component.species().size()][];
			for (int i = 0; i < species.length; i++) species[i] = systematicShares(component.species().get(i).amountQuanta(), nanos, key);
			for (int child = 0; child < nanos.length; child++) if (nanos[child] > 0) {
				var amounts = new ArrayList<GasComponentAmount.SpeciesAmount>();
				for (int i = 0; i < species.length; i++) if (species[i][child] > 0)
					amounts.add(new GasComponentAmount.SpeciesAmount(component.species().get(i).speciesId(), species[i][child]));
				columns.get(child).add(new GasComponentAmount(component.componentId(), nanos[child], mass[child], amounts, residual[child]));
			}
		}
		return columns.stream().map(GasInventory::new).toList();
	}
	/** One shared offset gives unbiased integer rounding; the final cumulative endpoint is exact. */
	private static long[] systematicShares(long total, long[] weights, long phase) {
		BigInteger sum = BigInteger.ZERO; for (long weight : weights) {
			if (weight < 0) throw new IllegalArgumentException("Negative gas share"); sum = sum.add(BigInteger.valueOf(weight));
		}
		if (sum.signum() <= 0) throw new IllegalArgumentException("Empty gas partition weights");
		BigInteger magnitude = BigInteger.valueOf(total).abs(), cumulative = BigInteger.ZERO;
		BigInteger offset = sum.multiply(BigInteger.valueOf(phase >>> 11)).shiftRight(53);
		long[] result = new long[weights.length]; BigInteger previous = BigInteger.ZERO;
		for (int i = 0; i < weights.length; i++) {
			cumulative = cumulative.add(BigInteger.valueOf(weights[i]));
			var endpoint = magnitude.multiply(cumulative).add(offset).divide(sum);
			var value = endpoint.subtract(previous); result[i] = (total < 0 ? value.negate() : value).longValueExact(); previous = endpoint;
		}
		return result;
	}
	private static long mixPosition(long position) {
		position = (position ^ (position >>> 30)) * 0xbf58476d1ce4e5b9L;
		position = (position ^ (position >>> 27)) * 0x94d049bb133111ebL;
		return position ^ (position >>> 31);
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
			String componentId, long selectedNanos, long selectedMass, long selectedMassResidual
	) {
		GasComponentAmount selected = component(componentId);
		if (selected == null || selectedNanos <= 0L || selectedMass <= 0) {
			throw new IllegalArgumentException("Gas component phase columns are invalid");
		}
		Split proportional = splitComponent(componentId, selectedNanos);
		GasComponentAmount first = proportional.first().components().getFirst();
		if (selectedNanos == selected.standardVolumeNanos()) {
			if (selectedMass != selected.massUnits()
					|| selectedMassResidual != selected.massResidualUnits()) {
				throw new RepresentationException("A complete gas component take must preserve all columns");
			}
			return proportional;
		}
		long remainingMass = Math.subtractExact(selected.massUnits(), selectedMass);
		long remainingResidual = Math.subtractExact(selected.massResidualUnits(), selectedMassResidual);
		if (remainingMass <= 0) {
			throw new RepresentationException("Gas component phase remainder cannot store the requested mass/residual");
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

	/** Topology geometry supplies proportions; integer allocation preserves the complete inventory. */
	public List<GasInventory> partition(List<ExactRational> weights) {
		return partition(GasPartitionWeights.ofRationals(weights));
	}

	/** The same immutable geometric measures serve integer stock and physical control volume. */
	public List<GasInventory> partition(GasPartitionWeights weights) {
		if (weights == null) throw new IllegalArgumentException("Gas partition weights are missing");
		return partition(weights.childCount(), weights::allocateNanos);
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
			species.merge(amount.speciesId(), amount.amountQuanta(), (left, right) -> addColumn(left, right, "species quantity"));
		}
		if (species.size() > GasLimits.MAX_SPECIES_PER_COMPONENT)
			throw new RepresentationException("Gas component exceeds its species slots");
		long mass = Math.addExact(first.massUnits(), second.massUnits()), residual = Math.addExact(first.massResidualUnits(), second.massResidualUnits());
		return new GasComponentAmount(first.componentId(),
				addColumn(first.standardVolumeNanos(), second.standardVolumeNanos(), "component standard volume"), mass,
				species.entrySet().stream().map(entry -> new GasComponentAmount.SpeciesAmount(entry.getKey(), entry.getValue())).toList(),
				residual);
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
		long assignedMass = 0, assignedResidual = 0;
		int pivot = 0;
		for (int child = 1; child < nanos.length; child++) if (nanos[child] > nanos[pivot]) pivot = child;
		// The largest share retains all integer division remainders.
		for (int ordinal = 0; ordinal < nanos.length; ordinal++) {
			int child = ordinal == nanos.length - 1 ? pivot : ordinal < pivot ? ordinal : ordinal + 1;
			if (nanos[child] == 0L) continue;
			long mass, residual;
			if (child == pivot) {
				mass = Math.subtractExact(source.massUnits(), assignedMass);
				residual = Math.subtractExact(source.massResidualUnits(), assignedResidual);
			} else {
				mass = mmm.units.Mass.portion(source.massUnits(), nanos[child], source.standardVolumeNanos());
				residual = mmm.units.Mass.portion(source.massResidualUnits(), nanos[child], source.standardVolumeNanos());
				assignedMass = Math.addExact(assignedMass, mass);
				assignedResidual = Math.addExact(assignedResidual, residual);
			}
			if (mass <= 0)
				throw new RepresentationException("Positive gas share cannot store its mass/residual columns");
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

	@Override public boolean equals(Object other) {
		return other instanceof GasInventory inventory && components.equals(inventory.components);
	}
	@Override public int hashCode() { return components.hashCode(); }
	@Override public String toString() { return components.toString(); }
}
