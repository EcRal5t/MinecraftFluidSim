package com.example.thermal;

import com.example.chemistry.ChemicalLimits;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded cell-local continuous solid inventory. Temperature and phase fraction are never stored. */
public final class CoherentPhaseStore {
	public record Component(String speciesId, long amountQuanta) {
		public Component {
			ThermalIds.requireId(speciesId, "coherent species id");
			if (amountQuanta <= 0L) throw new IllegalArgumentException("Coherent species amount must be positive");
		}
	}

	/** Null on a group means chemically inert; an instantiated composition is always non-empty. */
	public record Composition(List<Component> components, long massResidualBits) {
		public Composition {
			components = List.copyOf(components);
			if (components.isEmpty() || components.size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL
					|| !Double.isFinite(massResidual())) {
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

		public double massResidual() { return Double.longBitsToDouble(massResidualBits); }

		public Map<String, Long> stableAmounts() {
			TreeMap<String, Long> result = new TreeMap<>();
			for (Component component : components) result.put(component.speciesId(), component.amountQuanta());
			return Map.copyOf(result);
		}
	}

	public record Group(
			int cell, String phaseId, long occupiedVolumeSvq, double mass,
			Composition composition, double internalEnergy
	) {
		public Group {
			if (cell < 0) throw new IllegalArgumentException("Coherent phase cell is invalid");
			ThermalIds.requireId(phaseId, "coherent phase id");
			if (occupiedVolumeSvq <= 0L || !(Double.isFinite(mass) && mass > 0.0)
					|| !Double.isFinite(internalEnergy)) {
				throw new IllegalArgumentException("Coherent phase inventory is invalid");
			}
		}
	}

	private static final Comparator<Group> ORDER = Comparator.comparingInt(Group::cell).thenComparing(Group::phaseId);
	private final int cellCount;
	private final int maximumGroups;
	private final ArrayList<Group> groups = new ArrayList<>();

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
	public int add(Group addition) {
		Group candidate = previewAdd(addition);
		int found = find(addition.cell(), addition.phaseId());
		if (found >= 0) {
			groups.set(found, candidate);
			return found;
		}
		int insertion = -found - 1;
		groups.add(insertion, candidate);
		return insertion;
	}

	/** Validates an addition and returns its canonical post-merge value without changing inventory. */
	public Group previewAdd(Group addition) {
		validateGroup(addition);
		int found = find(addition.cell(), addition.phaseId());
		if (found < 0) {
			if (groups.size() == maximumGroups) throw new IllegalStateException("Coherent phase group budget exceeded");
			return addition;
		}
		Group current = groups.get(found);
		return new Group(current.cell(), current.phaseId(),
				Math.addExact(current.occupiedVolumeSvq(), addition.occupiedVolumeSvq()),
				finiteAdd(current.mass(), addition.mass(), "coherent mass"),
				merge(current.composition(), addition.composition()),
				finiteAdd(current.internalEnergy(), addition.internalEnergy(), "coherent energy"));
	}

	/** Proportional exact-SVQ split. The caller may subsequently re-equilibrate energy at cell LTE. */
	public Group take(int cell, String phaseId, long requestedSvq) {
		Group removed = previewTake(cell, phaseId, requestedSvq);
		int index = find(cell, phaseId);
		Group current = groups.get(index);
		if (requestedSvq == current.occupiedVolumeSvq()) {
			groups.remove(index);
			return removed;
		}
		long remainingSvq = current.occupiedVolumeSvq() - requestedSvq;
		Composition[] composition = split(current.composition(), remainingSvq, requestedSvq);
		Group remaining = new Group(cell, phaseId, remainingSvq,
				current.mass() - removed.mass(),
				composition[0],
				current.internalEnergy() - removed.internalEnergy());
		groups.set(index, remaining);
		return removed;
	}

	/** Validates and computes a proportional take without changing authoritative inventory. */
	public Group previewTake(int cell, String phaseId, long requestedSvq) {
		int index = find(cell, phaseId);
		if (index < 0) throw new IllegalArgumentException("Unknown coherent phase group");
		Group current = groups.get(index);
		if (requestedSvq <= 0L || requestedSvq > current.occupiedVolumeSvq()) {
			throw new IllegalArgumentException("Coherent phase take volume is invalid");
		}
		if (requestedSvq == current.occupiedVolumeSvq()) return current;
		long remainingSvq = current.occupiedVolumeSvq() - requestedSvq;
		double removedMass = current.mass() * ((double) requestedSvq / current.occupiedVolumeSvq());
		double remainingMass = current.mass() - removedMass;
		double removedEnergy = current.internalEnergy() * ((double) requestedSvq / current.occupiedVolumeSvq());
		double remainingEnergy = current.internalEnergy() - removedEnergy;
		if (!(removedMass > 0.0 && remainingMass > 0.0)
				|| !Double.isFinite(removedEnergy) || !Double.isFinite(remainingEnergy)) {
			throw new IllegalArgumentException("Coherent phase split is not representable");
		}
		Composition[] split = split(current.composition(), remainingSvq, requestedSvq);
		return new Group(cell, phaseId, requestedSvq, removedMass, split[1], removedEnergy);
	}

	public void setInternalEnergy(int index, double internalEnergy) {
		if (!Double.isFinite(internalEnergy)) throw new IllegalArgumentException("Coherent internal energy is not finite");
		Group group = groups.get(index);
		groups.set(index, new Group(group.cell(), group.phaseId(), group.occupiedVolumeSvq(), group.mass(),
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

	public double totalMass() {
		double total = 0.0;
		for (Group group : groups) total += group.mass();
		return total;
	}

	public SignedDyadic exactEnergy() {
		SignedDyadic total = SignedDyadic.ZERO;
		for (Group group : groups) total = total.add(SignedDyadic.of(group.internalEnergy()));
		return total;
	}

	/** Clears a previously validated whole-store removal without inventing source/sink semantics. */
	public void clear() { groups.clear(); }

	public void restore(List<Group> snapshot) {
		if (!groups.isEmpty()) throw new IllegalStateException("Coherent phase restore target is not empty");
		if (snapshot == null || snapshot.size() > maximumGroups) {
			throw new IllegalArgumentException("Coherent phase snapshot is missing or over budget");
		}
		Group previous = null;
		for (Group group : snapshot) {
			validateGroup(group);
			if (previous != null && ORDER.compare(previous, group) >= 0) {
				throw new IllegalArgumentException("Coherent phase snapshot is not canonical and unique");
			}
			groups.add(group); previous = group;
		}
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
		ArrayList<Component> components = new ArrayList<>(amounts.size());
		for (Map.Entry<String, Long> entry : amounts.entrySet()) components.add(new Component(entry.getKey(), entry.getValue()));
		double residual = finiteAdd(first.massResidual(), second.massResidual(), "coherent mass residual");
		return new Composition(components, Double.doubleToRawLongBits(residual));
	}

	private static Composition[] split(Composition composition, long remainingSvq, long removedSvq) {
		if (composition == null) return new Composition[]{null, null};
		ArrayList<Component> remaining = new ArrayList<>(), removed = new ArrayList<>();
		for (Component component : composition.components()) {
			long[] amounts = allocate(component.amountQuanta(), remainingSvq, removedSvq);
			if (amounts[0] > 0L) remaining.add(new Component(component.speciesId(), amounts[0]));
			if (amounts[1] > 0L) removed.add(new Component(component.speciesId(), amounts[1]));
		}
		if (remaining.isEmpty() || removed.isEmpty()) {
			throw new IllegalArgumentException("Coherent split is below chemical quantum resolution");
		}
		double removedResidual = composition.massResidual() * ((double) removedSvq / (remainingSvq + removedSvq));
		double remainingResidual = composition.massResidual() - removedResidual;
		return new Composition[]{
				new Composition(remaining, Double.doubleToRawLongBits(remainingResidual)),
				new Composition(removed, Double.doubleToRawLongBits(removedResidual))};
	}

	private static long[] allocate(long total, long firstWeight, long secondWeight) {
		BigInteger denominator = BigInteger.valueOf(Math.addExact(firstWeight, secondWeight));
		BigInteger[] division = BigInteger.valueOf(total).multiply(BigInteger.valueOf(firstWeight))
				.divideAndRemainder(denominator);
		long first = division[0].longValueExact();
		long second = total - first;
		// Largest remainder with stable first-before-second tie break.
		if (division[1].shiftLeft(1).compareTo(denominator) > 0) { first++; second--; }
		return new long[]{first, second};
	}

	private static double finiteAdd(double first, double second, String label) {
		double result = first + second;
		if (!Double.isFinite(result)) throw new ArithmeticException(label + " overflow");
		return result;
	}

	private void requireCell(int cell) {
		if (cell < 0 || cell >= cellCount) throw new IllegalArgumentException("Unknown coherent phase cell " + cell);
	}
}
