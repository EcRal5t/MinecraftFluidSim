package mmm.chemistry;

import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Chemical transfer ledger: normalized liquid volume, physical kg mass/residual, integer μmol species. */
public final class ChemicalTransferLedger {
	public static final ChemicalTransferLedger EMPTY = new ChemicalTransferLedger(List.of());

	private final List<Group> groups;

	public ChemicalTransferLedger(List<Group> groups) {
		if (groups == null) throw new IllegalArgumentException("Chemical transfer groups are missing");
		TreeMap<Key, MutableGroup> canonical = new TreeMap<>();
		for (Group group : groups) canonical.computeIfAbsent(
				new Key(group.materialId(), group.indicator()), ignored -> new MutableGroup(group.materialId(), group.indicator()))
				.add(group);
		if (canonical.size() > ChemicalLimits.MAX_TRANSFER_GROUPS) {
			throw new IllegalArgumentException("Chemical transfer groups are over budget");
		}
		ArrayList<Group> result = new ArrayList<>(canonical.size());
		int entries = 0; long mass = 0L;
		for (MutableGroup group : canonical.values()) {
			Group frozen = group.freeze();
			mass = Math.addExact(mass, frozen.massUnits());
			entries = Math.addExact(entries, frozen.speciesQuanta().size());
			result.add(frozen);
		}
		if (entries > ChemicalLimits.MAX_TRANSFER_SPECIES_ENTRIES) {
			throw new IllegalArgumentException("Chemical transfer species entries are over budget");
		}
		this.groups = List.copyOf(result);
	}

	public List<Group> groups() { return groups; }
	public boolean isEmpty() { return groups.isEmpty(); }
	public double totalNormalizedVolume() {
		double result = 0.0;
		for (Group group : groups) result += group.normalizedVolume();
		return result;
	}
	public long totalMassUnits() {
		long result = 0;
		for (Group group : groups) result = Math.addExact(result, group.massUnits());
		return result;
	}
	public double totalMass() { return mmm.units.Mass.kilograms(totalMassUnits()); }
	public long totalQuanta() {
		long result = 0L;
		for (Group group : groups) for (long amount : group.speciesQuanta().values()) result = Math.addExact(result, amount);
		return result;
	}

	public ChemicalTransferLedger plus(ChemicalTransferLedger other) {
		if (isEmpty()) return other;
		if (other.isEmpty()) return this;
		ArrayList<Group> combined = new ArrayList<>(groups.size() + other.groups.size());
		combined.addAll(groups); combined.addAll(other.groups);
		return new ChemicalTransferLedger(combined);
	}

	public static Builder builder(ChemicalRegistry registry) { return new Builder(registry); }

	public record Group(
			String materialId,
			boolean indicator,
			double normalizedVolume,
			long massUnits,
			long massResidualUnits,
			Map<String, Long> speciesQuanta
	) {
		public Group {
			ChemicalIds.requireId(materialId, "chemical ledger material id");
			if (!(Double.isFinite(normalizedVolume) && normalizedVolume > 0.0)
					|| !(massUnits > 0)) {
				throw new IllegalArgumentException("Chemical transfer normalized volume must be finite and positive, and mass counts positive");
			}

			TreeMap<String, Long> canonical = new TreeMap<>();
			if (speciesQuanta == null
					|| speciesQuanta.size() > ChemicalLimits.MAX_TRANSFER_SPECIES_PER_GROUP) {
				throw new IllegalArgumentException("Chemical transfer species are over budget");
			}
			for (Map.Entry<String, Long> entry : speciesQuanta.entrySet()) {
				ChemicalIds.requireId(entry.getKey(), "chemical ledger species id");
				if (entry.getValue() == null || entry.getValue() <= 0L) {
					throw new IllegalArgumentException("Chemical transfer amount must be positive");
				}
				canonical.merge(entry.getKey(), entry.getValue(), Math::addExact);
			}
			speciesQuanta = Map.copyOf(canonical);
		}
		public double massKilograms() { return mmm.units.Mass.kilograms(massUnits); }
		public double massResidualKilograms() {
			return mmm.units.Mass.kilograms(massResidualUnits);
		}
	}

	public static final class Builder {
		private final ChemicalRegistry registry;
		private final TreeMap<Key, MutableGroup> groups = new TreeMap<>();

		private Builder(ChemicalRegistry registry) { this.registry = java.util.Objects.requireNonNull(registry, "registry"); }

		public Builder add(
				String materialId, boolean indicator, double normalizedVolume, long massUnits,
				ChemicalMixtureRecord mixture
		) {
			if (mixture == null) throw new IllegalArgumentException("Chemical transfer mixture is missing");
			return add(new Group(materialId, indicator, normalizedVolume, massUnits,
					mixture.massResidualUnits(), mixture.stableAmounts(registry)));
		}

		public Builder add(Group group) {
			groups.computeIfAbsent(new Key(group.materialId(), group.indicator()),
					ignored -> new MutableGroup(group.materialId(), group.indicator())).add(group);
			return this;
		}

		public Builder add(ChemicalTransferLedger ledger) {
			for (Group group : ledger.groups) add(group);
			return this;
		}

		public ChemicalTransferLedger build() {
			if (groups.isEmpty()) return EMPTY;
			return new ChemicalTransferLedger(groups.values().stream().map(MutableGroup::freeze).toList());
		}
	}

	private record Key(String materialId, boolean indicator) implements Comparable<Key> {
		@Override public int compareTo(Key other) {
			int material = materialId.compareTo(other.materialId);
			return material != 0 ? material : Boolean.compare(indicator, other.indicator);
		}
	}

	private static final class MutableGroup {
		private final String materialId;
		private final boolean indicator;
		// These are independent inventory columns. Aggregate their binary values exactly and round once;
		// ordinary += accumulates enough drift in a full demo region to falsify a closed mass ledger.
		private SignedDyadic normalizedVolume = SignedDyadic.ZERO;
		private long massUnits;
		private long residualUnits;
		private final TreeMap<String, Long> species = new TreeMap<>();

		private MutableGroup(String materialId, boolean indicator) {
			this.materialId = materialId;
			this.indicator = indicator;
		}
		private void add(Group group) {
			normalizedVolume = normalizedVolume.add(SignedDyadic.of(group.normalizedVolume()));
			massUnits = Math.addExact(massUnits, group.massUnits());
			residualUnits = Math.addExact(residualUnits, group.massResidualUnits());
			if (!(Double.isFinite(normalizedVolume.toDouble()))) {
				throw new IllegalArgumentException("Chemical transfer ledger overflow");
			}
			group.speciesQuanta().forEach((id, amount) -> species.merge(id, amount, Math::addExact));
		}
		private Group freeze() {
			return new Group(materialId, indicator, normalizedVolume.toDouble(), massUnits,
					residualUnits, species);
		}
	}
}
