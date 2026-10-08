package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.units.SignedDyadic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

	/** Combines complete inventories, rounding each independent mass column once. */
	public static GasInventory sum(List<GasInventory> inventories) {
		if (inventories == null) throw new IllegalArgumentException("Gas inventory sum is missing");
		var components = new TreeMap<String, ComponentSum>();
		for (var inventory : inventories) {
			if (inventory == null) throw new IllegalArgumentException("Gas inventory sum contains null");
			for (var component : inventory.components) {
				components.computeIfAbsent(component.componentId(), ignored -> new ComponentSum()).add(component);
				if (components.size() > GasLimits.MAX_COMPONENTS_PER_NODE) throw new IllegalArgumentException("Gas inventory sum exceeds component capacity");
			}
		}
		var result = new ArrayList<GasComponentAmount>(components.size());
		for (var entry : components.entrySet()) result.add(entry.getValue().finish(entry.getKey()));
		return result.isEmpty() ? EMPTY : new GasInventory(result);
	}

	private static final class ComponentSum {
		private long nanos;
		private GasComponentAmount first;
		private int contributions;
		private SignedDyadic mass = SignedDyadic.ZERO, residual = SignedDyadic.ZERO;
		private final TreeMap<String, Long> species = new TreeMap<>();
		void add(GasComponentAmount component) {
			if (contributions++ == 0) first = component;
			nanos = Math.addExact(nanos, component.standardVolumeNanos());
			mass = mass.add(SignedDyadic.of(component.mass())); residual = residual.add(SignedDyadic.of(component.massResidual()));
			for (var amount : component.species()) {
				species.merge(amount.speciesId(), amount.amountQuanta(), Math::addExact);
				if (species.size() > GasLimits.MAX_SPECIES_PER_COMPONENT) throw new IllegalArgumentException("Gas inventory sum exceeds species capacity");
			}
		}
		GasComponentAmount finish(String id) {
			if (contributions == 1) return first;
			return new GasComponentAmount(id, nanos, mass.toDouble(), species.entrySet().stream()
					.map(entry -> new GasComponentAmount.SpeciesAmount(entry.getKey(), entry.getValue())).toList(), residual.toDouble());
		}
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
		return partition(Arrays.stream(weights).mapToObj(BigInteger::valueOf).toList());
	}

	/** Exact geometric weights retain arbitrarily small positive chamber overlaps. */
	public List<GasInventory> partition(List<BigInteger> weights) {
		largestRemainder(0, weights);
		ArrayList<ArrayList<GasComponentAmount>> children = new ArrayList<>(weights.size());
		for (int child = 0; child < weights.size(); child++) {
			children.add(new ArrayList<>());
		}
		for (GasComponentAmount component : components) {
			long[] shares = largestRemainder(component.standardVolumeNanos(), weights);
			GasComponentAmount[] parts = splitComponent(component, shares);
			for (int child = 0; child < parts.length; child++) if (parts[child] != null) children.get(child).add(parts[child]);
		}
		ArrayList<GasInventory> result = new ArrayList<>(weights.size());
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
		double assignedMass = 0.0, assignedResidual = 0.0;
		int lastPositive = -1;
		for (int child = nanos.length - 1; child >= 0; child--) if (nanos[child] > 0L) { lastPositive = child; break; }
		for (int child = 0; child < nanos.length; child++) {
			if (nanos[child] == 0L) continue;
			double mass, residual;
			if (child == lastPositive) {
				mass = source.mass() - assignedMass;
				residual = source.massResidual() - assignedResidual;
			} else {
				double fraction = (double) nanos[child] / source.standardVolumeNanos();
				mass = source.mass() * fraction;
				residual = source.massResidual() * fraction;
				assignedMass += mass;
				assignedResidual += residual;
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
