package mmm.thermal;

import mmm.units.SignedDyadic;
import mmm.units.ProportionalDoubleSplit;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ExactRational;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded cell-local continuous solid inventory. Temperature and phase fraction are never stored. */
public final class CoherentPhaseStore {
	/** A valid inventory edit exceeds finite storage or integer composition capacity. */
	public static final class InventoryUnavailableException extends IllegalArgumentException {
		private InventoryUnavailableException(String message) { super(message); }
	}

	public record Component(String speciesId, long amountQuanta) {
		public Component {
			ThermalIds.requireId(speciesId, "coherent species id");
			if (amountQuanta <= 0L) throw new IllegalArgumentException("Coherent species amount must be positive");
		}
	}

	/** Null on a group means chemically inert; an instantiated composition is always non-empty. */
	public record Composition(List<Component> components, long massResidualUnits) {
		public Composition {
			components = List.copyOf(components);
			if (components.isEmpty() || components.size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL) {
				throw new IllegalArgumentException("Invalid coherent composition");
			}
			String previous = null;
			long total = 0L;
			for (Component component : components) {
				if (previous != null && previous.compareTo(component.speciesId()) >= 0) {
					throw new IllegalArgumentException("Coherent composition is not canonical");
				}
				total = Math.addExact(total, component.amountQuanta());
				previous = component.speciesId();
			}
			if (total > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
				throw new IllegalArgumentException("Coherent composition is over budget");
			}
		}

		public double massResidual() { return mmm.units.Mass.kilograms(massResidualUnits); }

		public Map<String, Long> stableAmounts() {
			TreeMap<String, Long> result = new TreeMap<>();
			for (Component component : components) result.put(component.speciesId(), component.amountQuanta());
			return Map.copyOf(result);
		}
	}

	public record Group(
			int cell, String phaseId, long occupiedVolumeSvq, long massUnits,
			Composition composition, double internalEnergy
	) {
		public double mass() { return mmm.units.Mass.kilograms(massUnits); }
		public void requireCompositionMass(ChemicalRegistry chemistry) {
			if (composition == null) return;
			if (chemistry == null) throw new IllegalArgumentException("Chemical coherent inventory requires its registry");
			long composed = ChemicalMixtureRecord.fromStableAmounts(chemistry, composition.stableAmounts(),
					composition.massResidualUnits()).massUnits(chemistry);
			if (composed != massUnits) throw new IllegalArgumentException("Coherent composition mass counts do not close");
		}

		public Group {
			if (cell < 0) throw new IllegalArgumentException("Coherent phase cell is invalid");
			ThermalIds.requireId(phaseId, "coherent phase id");
			if (occupiedVolumeSvq <= 0L || massUnits <= 0
					|| !Double.isFinite(internalEnergy)) {
				throw new IllegalArgumentException("Coherent phase inventory is invalid");
			}
		}
	}

	private static final Comparator<Group> ORDER = Comparator.comparingInt(Group::cell).thenComparing(Group::phaseId);
	private final int cellCount;
	private final int maximumGroups;
	private ArrayList<Group> groups = new ArrayList<>();

	public CoherentPhaseStore(int cellCount, int maximumGroups) {
		if (cellCount <= 0 || maximumGroups <= 0
				|| maximumGroups > ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION) {
			throw new IllegalArgumentException("Coherent phase store dimensions/budget are invalid");
		}
		this.cellCount = cellCount;
		this.maximumGroups = maximumGroups;
	}

	public int groupCount() { return groups.size(); }
	public Group group(int index) { return groups.get(index); }
	public List<Group> snapshot() { return List.copyOf(groups); }

	public int find(int cell, String phaseId) {
		requireCell(cell); ThermalIds.requireId(phaseId, "coherent phase id");
		int low = 0, high = groups.size() - 1;
		while (low <= high) {
			int middle = (low + high) >>> 1;
			Group candidate = groups.get(middle);
			int order = Integer.compare(candidate.cell(), cell);
			if (order == 0) order = candidate.phaseId().compareTo(phaseId);
			if (order < 0) low = middle + 1;
			else if (order > 0) high = middle - 1;
			else return middle;
		}
		return -low - 1;
	}

	/** Adds or canonically merges one cell+phase group. No source/sink semantics are inferred here. */
	int add(Group addition) {
		Group candidate = previewAdd(addition);
		int found = find(addition.cell(), addition.phaseId());
		commitPreparedAdd(found, candidate);
		return found >= 0 ? found : -found - 1;
	}

	/** The thermal owner has already checked the complete candidate and its source revision. */
	void commitPreparedAdd(int found, Group candidate) {
		if (found >= 0) {
			groups.set(found, candidate);
			return;
		}
		int insertion = -found - 1;
		groups.add(insertion, candidate);
	}

	/** Validates an addition and returns its canonical post-merge value without changing inventory. */
	public Group previewAdd(Group addition) {
		validateGroup(addition);
		if (addition.massUnits() > Long.MAX_VALUE - totalMassUnits())
			throw new InventoryUnavailableException("Coherent mass capacity exceeded");
		int found = find(addition.cell(), addition.phaseId());
		if (found < 0) {
			if (groups.size() == maximumGroups) throw new InventoryUnavailableException("Coherent phase group budget exceeded");
			return addition;
		}
		Group current = groups.get(found);
		if (addition.occupiedVolumeSvq() > Long.MAX_VALUE - current.occupiedVolumeSvq())
			throw new InventoryUnavailableException("Coherent occupied volume exceeds the SVQ range");
		return new Group(current.cell(), current.phaseId(),
				Math.addExact(current.occupiedVolumeSvq(), addition.occupiedVolumeSvq()),
				Math.addExact(current.massUnits(), addition.massUnits()),
				merge(current.composition(), addition.composition()),
				finiteAdd(current.internalEnergy(), addition.internalEnergy(), "coherent energy"));
	}

	/** Proportional exact-SVQ split. The caller may subsequently re-equilibrate energy at cell LTE. */
	Group take(int cell, String phaseId, long requestedSvq, ChemicalRegistry chemistry) {
		TakeSplit split = previewSplit(cell, phaseId, requestedSvq, chemistry);
		int index = find(cell, phaseId);
		if (split.remaining() == null) groups.remove(index);
		else groups.set(index, split.remaining());
		return split.removed();
	}

	/** The removed and retained columns of one proportional take, prepared together. */
	public record TakeSplit(Group removed, Group remaining) { }
	public TakeSplit previewSplit(int cell, String phaseId, long requestedSvq, ChemicalRegistry chemistry) {
		int index = find(cell, phaseId);
		if (index < 0) throw new IllegalArgumentException("Unknown coherent phase group");
		Group current = groups.get(index);
		current.requireCompositionMass(chemistry);
		if (requestedSvq <= 0L || requestedSvq > current.occupiedVolumeSvq()) {
			throw new IllegalArgumentException("Coherent phase take volume is invalid");
		}
		if (requestedSvq == current.occupiedVolumeSvq()) return new TakeSplit(current, null);
		long remainingSvq = current.occupiedVolumeSvq() - requestedSvq;

		long removedMass = mmm.units.Mass.portion(current.massUnits(), requestedSvq, current.occupiedVolumeSvq());
		long remainingMass = Math.subtractExact(current.massUnits(), removedMass);
		ProportionalDoubleSplit energies = ProportionalDoubleSplit.of(current.internalEnergy(), removedMass, current.massUnits());
		double removedEnergy = energies.removed(), remainingEnergy = energies.remaining();
		if (!(removedMass > 0L && remainingMass > 0L)
				|| !Double.isFinite(removedEnergy) || !Double.isFinite(remainingEnergy)) {
			throw new InventoryUnavailableException("Coherent phase split has no positive mass or finite energy shares");
		}
		Composition[] split = split(current.composition(), remainingSvq, requestedSvq, remainingMass, removedMass, chemistry);
		return new TakeSplit(new Group(cell, phaseId, requestedSvq, removedMass, split[1], removedEnergy),
				new Group(cell, phaseId, remainingSvq, remainingMass, split[0], remainingEnergy));
	}

	void setInternalEnergy(int index, double internalEnergy) {
		if (!Double.isFinite(internalEnergy)) throw new IllegalArgumentException("Coherent internal energy is not finite");
		Group group = groups.get(index);
		groups.set(index, new Group(group.cell(), group.phaseId(), group.occupiedVolumeSvq(), group.massUnits(),
				group.composition(), internalEnergy));
	}

	public long cellOccupiedSvq(int cell) {
		requireCell(cell);
		long total = 0L;
		for (Group group : groups) {
			if (group.cell() < cell) continue;
			if (group.cell() > cell) break;
			total = Math.addExact(total, group.occupiedVolumeSvq());
		}
		return total;
	}

	public long totalOccupiedSvq() {
		long total = 0L;
		for (Group group : groups) total = Math.addExact(total, group.occupiedVolumeSvq());
		return total;
	}

	public long totalMassUnits() {
		long total = 0;
		for (Group group : groups) total = Math.addExact(total, group.massUnits());
		return total;
	}
	public double totalMass() { return mmm.units.Mass.kilograms(totalMassUnits()); }

	public SignedDyadic exactEnergy() {
		SignedDyadic total = SignedDyadic.ZERO;
		for (Group group : groups) total = total.add(SignedDyadic.of(group.internalEnergy()));
		return total;
	}

	/** Clears a previously validated whole-store removal without inventing source/sink semantics. */
	void clear() { groups.clear(); }

	/**
	 * Wholesale restore/canonicalization seam: the only entry that fills an empty store from a
	 * snapshot (thermal restore) or validates one (persistence handoff records build a private
	 * scratch store). Incremental mutation stays package-private to the state owners.
	 */
	void restore(List<Group> snapshot) {
		if (!groups.isEmpty()) throw new IllegalStateException("Coherent phase restore target is not empty");
		if (snapshot == null || snapshot.size() > maximumGroups) {
			throw new IllegalArgumentException("Coherent phase snapshot is missing or over budget");
		}
		long mass = 0L;
		for (Group group : snapshot) mass = Math.addExact(mass, group.massUnits());
		Group previous = null;
		for (Group group : snapshot) {
			validateGroup(group);
			if (previous != null && ORDER.compare(previous, group) >= 0) {
				throw new IllegalArgumentException("Coherent phase snapshot is not canonical and unique");
			}
			groups.add(group); previous = group;
		}
	}

	CoherentPhaseStore prepareReplacement(List<Group> future) {
		var candidate=new CoherentPhaseStore(cellCount,maximumGroups);candidate.restore(future);return candidate;
	}
	/** Both lists were validated before the enclosing inventory transaction publishes. */
	void commitPreparedReplacement(CoherentPhaseStore candidate) { groups=candidate.groups; }

	/** Validates a handoff snapshot without exposing a mutation seam on a live store. */
	public static SignedDyadic exactEnergyOfCanonicalSnapshot(
			int cellCount, int maximumGroups, List<Group> snapshot
	) {
		CoherentPhaseStore canonical = new CoherentPhaseStore(cellCount, maximumGroups);
		canonical.restore(snapshot);
		return canonical.exactEnergy();
	}

	private void validateGroup(Group group) {
		if (group == null || group.cell() >= cellCount) throw new IllegalArgumentException("Coherent phase group is outside the store");
	}

	private static Composition merge(Composition first, Composition second) {
		if (first == null || second == null) {
			if (first != second) throw new IllegalArgumentException("Cannot merge inert and chemical coherent groups");
			return null;
		}
		TreeMap<String, Long> amounts = new TreeMap<>();
		for (Component component : first.components()) amounts.put(component.speciesId(), component.amountQuanta());
		for (Component component : second.components()) amounts.merge(component.speciesId(), component.amountQuanta(), Math::addExact);
		if (amounts.size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL)
			throw new InventoryUnavailableException("Coherent merge exceeds the species slot capacity");
		long total = 0;
		for (long amount : amounts.values()) total += amount;
		if (total > ChemicalLimits.MAX_REGION_TOTAL_QUANTA)
			throw new InventoryUnavailableException("Coherent merge exceeds the chemical quantum capacity");
		ArrayList<Component> components = new ArrayList<>(amounts.size());
		for (Map.Entry<String, Long> entry : amounts.entrySet()) components.add(new Component(entry.getKey(), entry.getValue()));
		long residual = Math.addExact(first.massResidualUnits(), second.massResidualUnits());
		return new Composition(components, residual);
	}

	private static Composition[] split(Composition composition, long remainingSvq, long removedSvq,
	                                   long remainingMass, long removedMass, ChemicalRegistry chemistry) {
		if (composition == null) return new Composition[]{null, null};
		if (chemistry == null) throw new IllegalArgumentException("Coherent chemical split requires its registry");
		ArrayList<Component> remaining = new ArrayList<>(), removed = new ArrayList<>();
		for (Component component : composition.components()) {
			long[] amounts = allocate(component.amountQuanta(), remainingSvq, removedSvq);
			if (amounts[0] > 0L) remaining.add(new Component(component.speciesId(), amounts[0]));
			if (amounts[1] > 0L) removed.add(new Component(component.speciesId(), amounts[1]));
		}
		if (remaining.isEmpty() || removed.isEmpty()) {
			throw new InventoryUnavailableException("Coherent split is below chemical quantum resolution");
		}
		boolean removedIsSmaller = removedSvq <= remainingSvq;
		TreeMap<String, Long> smallerAmounts = new TreeMap<>();
		for (Component component : removedIsSmaller ? removed : remaining) smallerAmounts.put(component.speciesId(), component.amountQuanta());
		long smallerMass = removedIsSmaller ? removedMass : remainingMass;
		long smallerResidual = Math.subtractExact(smallerMass,
				ChemicalMixtureRecord.fromStableAmounts(chemistry, smallerAmounts, 0L).speciesMassUnits(chemistry));
		long largerResidual = Math.subtractExact(composition.massResidualUnits(), smallerResidual);
		return new Composition[]{
				new Composition(remaining, (removedIsSmaller ? largerResidual : smallerResidual)),
				new Composition(removed, (removedIsSmaller ? smallerResidual : largerResidual))};
	}

	private static long[] allocate(long total, long firstWeight, long secondWeight) {
		BigInteger denominator = BigInteger.valueOf(Math.addExact(firstWeight, secondWeight));
		BigInteger[] division = BigInteger.valueOf(total).multiply(BigInteger.valueOf(firstWeight))
				.divideAndRemainder(denominator);
		long first = division[0].longValueExact();
		long second = total - first;
		// Largest remainder with stable first-before-second tie break.
		if (division[1].shiftLeft(1).compareTo(denominator) >= 0) { first++; second--; }
		return new long[]{first, second};
	}

	private static double finiteAdd(double first, double second, String label) {
		double result = first + second;
		if (!Double.isFinite(result)) throw new InventoryUnavailableException(label + " overflow");
		return result;
	}

	private void requireCell(int cell) {
		if (cell < 0 || cell >= cellCount) throw new IllegalArgumentException("Unknown coherent phase cell " + cell);
	}
}
