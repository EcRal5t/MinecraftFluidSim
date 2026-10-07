package cc.ecisr.mmm.chemistry;

import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Complete external/displacement chemical ledger grouped by stable material and indicator provenance. */
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
		int entries = 0;
		for (MutableGroup group : canonical.values()) {
			Group frozen = group.freeze();
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
	public double totalVolume() { return volumeQuantity().toDouble(); }
	public double totalMass() { return massKilogramsQuantity().toDouble(); }
	public SignedDyadic volumeQuantity() {
		var result = SignedDyadic.ZERO;
		for (Group group : groups) result = result.add(group.volumeQuantity());
		return result;
	}
	public SignedDyadic massKilogramsQuantity() {
		var result = SignedDyadic.ZERO;
		for (Group group : groups) result = result.add(group.massKilogramsQuantity());
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
			double massKilograms,
			long massResidualKilogramsBits,
			Map<String, Long> speciesQuanta,
			SignedDyadic volumeQuantity, SignedDyadic massKilogramsQuantity
	) {
		public Group(String materialId, boolean indicator, double volume, double massKilograms,
				long massResidualKilogramsBits, Map<String, Long> speciesQuanta) {
			this(materialId, indicator, volume, massKilograms, massResidualKilogramsBits, speciesQuanta,
					SignedDyadic.of(volume), SignedDyadic.of(massKilograms));
		}
		public Group(String materialId, boolean indicator, SignedDyadic volume, SignedDyadic massKilograms,
				long massResidualKilogramsBits, Map<String, Long> speciesQuanta) {
			this(materialId, indicator, volume.toDouble(), massKilograms.toDouble(), massResidualKilogramsBits, speciesQuanta, volume, massKilograms);
		}
		public Group {
			ChemicalIds.requireId(materialId, "chemical ledger material id");
			if (!(Double.isFinite(volume) && volume > 0.0)
					|| !(Double.isFinite(massKilograms) && massKilograms > 0.0)) {
				throw new IllegalArgumentException("Chemical transfer volume and mass must be finite and positive");
			}
			if (volumeQuantity == null || massKilogramsQuantity == null || volumeQuantity.signum() <= 0 || massKilogramsQuantity.signum() <= 0
					|| Double.doubleToRawLongBits(volume) != Double.doubleToRawLongBits(volumeQuantity.toDouble())
					|| Double.doubleToRawLongBits(massKilograms) != Double.doubleToRawLongBits(massKilogramsQuantity.toDouble()))
				throw new IllegalArgumentException("Chemical transfer observations differ from complete quantities");
			if (!Double.isFinite(Double.longBitsToDouble(massResidualKilogramsBits))) {
				throw new IllegalArgumentException("Chemical transfer residual must be finite");
			}
			TreeMap<String, Long> canonical = new TreeMap<>();
			if (speciesQuanta == null || speciesQuanta.isEmpty()
					|| speciesQuanta.size() > ChemicalLimits.MAX_TRANSFER_SPECIES_PER_GROUP) {
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
		public double massResidualKilograms() {
			return Double.longBitsToDouble(massResidualKilogramsBits);
		}
	}

	public static final class Builder {
		private final ChemicalRegistry registry;
		private final TreeMap<Key, MutableGroup> groups = new TreeMap<>();

		private Builder(ChemicalRegistry registry) { this.registry = java.util.Objects.requireNonNull(registry, "registry"); }

		public Builder add(
				String materialId, boolean indicator, double volume, double massKilograms,
				ChemicalMixtureRecord mixture
		) {
			if (mixture == null) throw new IllegalArgumentException("Chemical transfer mixture is missing");
			return add(new Group(materialId, indicator, volume, massKilograms,
					mixture.massResidualKilogramsBits(), mixture.stableAmounts(registry)));
		}
		public Builder add(String materialId, boolean indicator, SignedDyadic volume, SignedDyadic massKilograms,
				ChemicalMixtureRecord mixture) {
			if (mixture == null) throw new IllegalArgumentException("Chemical transfer mixture is missing");
			return add(new Group(materialId, indicator, volume, massKilograms,
					mixture.massResidualKilogramsBits(), mixture.stableAmounts(registry)));
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
		// These are independent truth columns. Aggregate their binary values exactly and round once;
		// ordinary += accumulates enough drift in a full demo region to falsify a closed mass ledger.
		private SignedDyadic volume = SignedDyadic.ZERO;
		private SignedDyadic massKilograms = SignedDyadic.ZERO;
		private SignedDyadic residual = SignedDyadic.ZERO;
		private final TreeMap<String, Long> species = new TreeMap<>();

		private MutableGroup(String materialId, boolean indicator) {
			this.materialId = materialId;
			this.indicator = indicator;
		}
		private void add(Group group) {
			volume = volume.add(group.volumeQuantity());
			massKilograms = massKilograms.add(group.massKilogramsQuantity());
			residual = residual.add(SignedDyadic.of(group.massResidualKilograms()));
			if (!(Double.isFinite(volume.toDouble()) && Double.isFinite(massKilograms.toDouble())
					&& Double.isFinite(residual.toDouble()))) {
				throw new IllegalArgumentException("Chemical transfer ledger overflow");
			}
			group.speciesQuanta().forEach((id, amount) -> species.merge(id, amount, Math::addExact));
		}
		private Group freeze() {
			return new Group(materialId, indicator, volume, massKilograms,
					Double.doubleToRawLongBits(residual.toDouble()), species);
		}
	}
}
