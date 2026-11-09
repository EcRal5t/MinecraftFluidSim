package mmm.fluid.air;

import mmm.chemistry.ChemicalRegistry;
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

	/** Selects up to the requested volume; indivisible trace components remain in the source. */
	public Split splitTotal(long firstNanos, GasRegistry registry, ChemicalRegistry chemistry) {
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
					new long[] { selected[index], component.standardVolumeNanos() - selected[index] }, registry, chemistry, 1);
			if (parts[0] != null) first.add(parts[0]);
			if (parts[1] != null) second.add(parts[1]);
		}
		return new Split(new GasInventory(first), new GasInventory(second));
	}

	/** Selects a bounded portion of one component, retaining an indivisible remainder in the source. */
	public Split splitComponent(String componentId, long selectedNanos, GasRegistry registry, ChemicalRegistry chemistry) {
		GasComponentAmount selected = component(componentId);
		if (selected == null || selectedNanos < 0L || selectedNanos > selected.standardVolumeNanos()) {
			throw new IllegalArgumentException("Gas component split is outside the inventory");
		}
		if (selectedNanos == 0L) return new Split(EMPTY, this);
		GasComponentAmount[] portions = splitComponent(selected,
				new long[] {selectedNanos, selected.standardVolumeNanos() - selectedNanos}, registry, chemistry, 1);
		ArrayList<GasComponentAmount> remainder = new ArrayList<>(components.size());
		for (GasComponentAmount component : components) {
			if (!component.componentId().equals(componentId)) remainder.add(component);
			else if (portions[1] != null) remainder.add(portions[1]);
		}
		return new Split(portions[0] == null ? EMPTY : new GasInventory(List.of(portions[0])), new GasInventory(remainder));
	}

	/** The same immutable geometric measures serve integer stock and physical control volume. */
	public List<GasInventory> partition(GasPartitionWeights weights, GasRegistry registry, ChemicalRegistry chemistry) {
		if (weights == null) throw new IllegalArgumentException("Gas partition weights are missing");
		return partition(weights.childCount(), weights::allocateNanos, registry, chemistry);
	}

	private List<GasInventory> partition(int childCount, LongFunction<long[]> allocation, GasRegistry registry, ChemicalRegistry chemistry) {
		ArrayList<ArrayList<GasComponentAmount>> children = new ArrayList<>(childCount);
		for (int child = 0; child < childCount; child++) children.add(new ArrayList<>());
		for (GasComponentAmount component : components) {
			long[] shares = allocation.apply(component.standardVolumeNanos());
			GasComponentAmount[] parts = splitComponent(component, shares, registry, chemistry, -1);
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

	private static GasComponentAmount[] splitComponent(GasComponentAmount source, long[] requested, GasRegistry registry, ChemicalRegistry chemistry, int retainedChild) {
		long[] nanos=requested.clone();long sum=0;
		for(long value:nanos){if(value<0)throw new IllegalArgumentException("Gas component split volume cannot be negative");sum=Math.addExact(sum,value);}
		if(sum!=source.standardVolumeNanos())throw new IllegalArgumentException("Gas component split does not close");
		for(;;){
			int pivot=0;for(int child=1;child<nanos.length;child++)if(nanos[child]>nanos[pivot])pivot=child;
			List<BigInteger> weights=Arrays.stream(nanos).mapToObj(BigInteger::valueOf).toList();
			long[][] speciesShares=new long[source.species().size()][];
			for(int species=0;species<source.species().size();species++)
				speciesShares[species]=largestRemainder(source.species().get(species).amountQuanta(),weights);
			long[] residual=new long[nanos.length],mass=new long[nanos.length];long assigned=0;
			for(int child=0;child<nanos.length;child++)if(child!=pivot&&nanos[child]>0){
				residual[child]=mmm.units.Mass.portion(source.massResidualUnits(),nanos[child],source.standardVolumeNanos());
				assigned=Math.addExact(assigned,residual[child]);
			}
			residual[pivot]=Math.subtractExact(source.massResidualUnits(),assigned);
			for(int child=0;child<nanos.length;child++)if(nanos[child]>0){
				mass[child]=residual[child];
				for(int species=0;species<speciesShares.length;species++)if(speciesShares[species][child]>0)
					mass[child]=Math.addExact(mass[child],Math.multiplyExact(speciesShares[species][child],
							massPerQuantum(registry,chemistry,source.componentId(),source.species().get(species).speciesId())));
			}
			if(retainedChild>=0)for(int child=0;child<nanos.length;child++)if(nanos[child]>0&&mass[child]<=0){
				var result=new GasComponentAmount[nanos.length];result[retainedChild]=source;return result;
			}
			boolean retry=false;
			for(int child=0;child<nanos.length;child++)if(child!=pivot&&nanos[child]>0&&mass[child]<=0){
				nanos[pivot]=Math.addExact(nanos[pivot],nanos[child]);nanos[child]=0;retry=true;
			}
			if(retry)continue;
			if(mass[pivot]<=0){
				int target=-1;for(int child=0;child<nanos.length;child++)if(child!=pivot&&mass[child]>0&&(target<0||mass[child]>mass[target]))target=child;
				if(target<0)throw new RepresentationException("Gas component cannot store its mass/residual columns");
				nanos[target]=Math.addExact(nanos[target],nanos[pivot]);nanos[pivot]=0;continue;
			}
			GasComponentAmount[] result=new GasComponentAmount[nanos.length];
			for(int child=0;child<nanos.length;child++)if(nanos[child]>0){
				var species=new ArrayList<GasComponentAmount.SpeciesAmount>();
				for(int entry=0;entry<speciesShares.length;entry++)if(speciesShares[entry][child]>0)
					species.add(new GasComponentAmount.SpeciesAmount(source.species().get(entry).speciesId(),speciesShares[entry][child]));
				result[child]=new GasComponentAmount(source.componentId(),nanos[child],mass[child],species,residual[child]);
			}
			return result;
		}
	}

	private static long massPerQuantum(GasRegistry registry, ChemicalRegistry chemistry, String component, String species) {
		if (chemistry != null) return chemistry.speciesMassUnits(chemistry.speciesHandle(species), 1);
		for (var definition : registry.component(component).defaultSpeciesConcentrations())
			if (definition.speciesId().equals(species)) return definition.massUnitsPerQuantum();
		throw new IllegalArgumentException("Gas species mass needs its chemical registry: " + species);
	}

	public record Split(GasInventory first, GasInventory second) { }
	private record Remainder(int index, BigInteger value) { }

	@Override public boolean equals(Object other) {
		return other instanceof GasInventory inventory && components.equals(inventory.components);
	}
	@Override public int hashCode() { return components.hashCode(); }
	@Override public String toString() { return components.toString(); }
}
