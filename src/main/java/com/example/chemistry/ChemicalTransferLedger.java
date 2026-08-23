package com.example.chemistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Complete external/displacement chemical ledger grouped by stable material and indicator provenance. */
public final class ChemicalTransferLedger {
	public static final ChemicalTransferLedger EMPTY = new ChemicalTransferLedger(List.of());

	private final List<Group> groups;

	public ChemicalTransferLedger(List<Group> groups) {
		if (groups == null || groups.size() > ChemicalLimits.MAX_BUCKET_GROUPS) {
			throw new IllegalArgumentException("Chemical transfer groups are over budget");
		}
		TreeMap<Key, MutableGroup> canonical = new TreeMap<>();
		for (Group group : groups) canonical.computeIfAbsent(
				new Key(group.materialId(), group.indicator()), ignored -> new MutableGroup(group.materialId(), group.indicator()))
				.add(group);
		ArrayList<Group> result = new ArrayList<>(canonical.size());
		int entries = 0;
		for (MutableGroup group : canonical.values()) {
			Group frozen = group.freeze();
			entries = Math.addExact(entries, frozen.speciesQuanta().size());
			result.add(frozen);
		}
		if (entries > ChemicalLimits.MAX_BUCKET_SPECIES_ENTRIES) {
			throw new IllegalArgumentException("Chemical transfer species entries are over budget");
		}
		this.groups = List.copyOf(result);
	}

	public List<Group> groups() { return groups; }
	public boolean isEmpty() { return groups.isEmpty(); }
	public double totalVolume() {
		double result = 0.0;
		for (Group group : groups) result += group.volume();
		return result;
	}
	public double totalMass() {
		double result = 0.0;
		for (Group group : groups) result += group.mass();
		return result;
	}
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
			double volume,
			double mass,
			long massResidualBits,
			Map<String, Long> speciesQuanta
	) {
		public Group {
			ChemicalIds.requireId(materialId, "chemical ledger material id");
			if (!(Double.isFinite(volume) && volume > 0.0) || !(Double.isFinite(mass) && mass > 0.0)) {
				throw new IllegalArgumentException("Chemical transfer volume and mass must be finite and positive");
			}
			if (!Double.isFinite(Double.longBitsToDouble(massResidualBits))) {
				throw new IllegalArgumentException("Chemical transfer residual must be finite");
			}
			TreeMap<String, Long> canonical = new TreeMap<>();
			if (speciesQuanta == null || speciesQuanta.isEmpty()
					|| speciesQuanta.size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL) {
				throw new IllegalArgumentException("Chemical transfer species are empty or over budget");
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
		public double massResidual() { return Double.longBitsToDouble(massResidualBits); }
	}

	public static final class Builder {
		private final ChemicalRegistry registry;
		private final TreeMap<Key, MutableGroup> groups = new TreeMap<>();

		private Builder(ChemicalRegistry registry) { this.registry = java.util.Objects.requireNonNull(registry, "registry"); }

		public Builder add(
				String materialId, boolean indicator, double volume, double mass, ChemicalMixtureRecord mixture
		) {
			if (mixture == null) throw new IllegalArgumentException("Chemical transfer mixture is missing");
			return add(new Group(materialId, indicator, volume, mass, mixture.massResidualBits(), mixture.stableAmounts(registry)));
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
		private double volume;
		private double mass;
		private double residual;
		private final TreeMap<String, Long> species = new TreeMap<>();

		private MutableGroup(String materialId, boolean indicator) {
			this.materialId = materialId;
			this.indicator = indicator;
		}
		private void add(Group group) {
			volume += group.volume();
			mass += group.mass();
			residual += group.massResidual();
			if (!(Double.isFinite(volume) && Double.isFinite(mass) && Double.isFinite(residual))) {
				throw new IllegalArgumentException("Chemical transfer ledger overflow");
			}
			group.speciesQuanta().forEach((id, amount) -> species.merge(id, amount, Math::addExact));
		}
		private Group freeze() {
			return new Group(materialId, indicator, volume, mass, Double.doubleToRawLongBits(residual), species);
		}
	}
}
