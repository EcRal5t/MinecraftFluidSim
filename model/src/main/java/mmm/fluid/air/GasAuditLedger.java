package mmm.fluid.air;

import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exact cumulative gas source/sink ledger; raw doubles are accumulated as exact dyadics. */
public final class GasAuditLedger {
	public static final GasAuditLedger EMPTY = new GasAuditLedger(List.of());

	public record Entry(
			String componentId, long standardVolumeNanos, long massUnits,
			List<GasComponentAmount.SpeciesAmount> species, long massResidualUnits
	) {
		public Entry {
			GasIds.require(componentId, "gas audit component id");
			if (standardVolumeNanos <= 0L || massUnits <= 0
					|| species == null) {
				throw new IllegalArgumentException("Invalid gas audit entry");
			}
			ArrayList<GasComponentAmount.SpeciesAmount> canonical = new ArrayList<>(species);
			if (canonical.stream().anyMatch(java.util.Objects::isNull)
					|| canonical.size() > GasLimits.MAX_SPECIES_PER_COMPONENT) {
				throw new IllegalArgumentException("Gas audit species are invalid or over budget");
			}
			canonical.sort(java.util.Comparator.comparing(GasComponentAmount.SpeciesAmount::speciesId));
			for (int index = 1; index < canonical.size(); index++) {
				if (canonical.get(index - 1).speciesId().equals(canonical.get(index).speciesId())) {
					throw new IllegalArgumentException("Duplicate gas audit species");
				}
			}
			species = List.copyOf(canonical);
		}
	}

	private final List<Entry> entries;

	public GasAuditLedger(List<Entry> entries) {
		if (entries == null || entries.size() > GasLimits.MAX_COMPONENTS) {
			throw new IllegalArgumentException("Gas audit is missing or over budget");
		}
		TreeMap<String, Entry> canonical = new TreeMap<>();
		for (Entry entry : entries) if (entry == null || canonical.put(entry.componentId(), entry) != null) {
			throw new IllegalArgumentException("Gas audit contains null or duplicate components");
		}
		this.entries = List.copyOf(canonical.values());
	}

	public static GasAuditLedger of(GasInventory inventory) {
		ArrayList<Entry> result = new ArrayList<>();
		for (GasComponentAmount amount : inventory.components()) result.add(new Entry(
				amount.componentId(), amount.standardVolumeNanos(), amount.massUnits(), amount.species(),
				amount.massResidualUnits()));
		return result.isEmpty() ? EMPTY : new GasAuditLedger(result);
	}

	public List<Entry> entries() { return entries; }
	public boolean isEmpty() { return entries.isEmpty(); }
	public Entry component(String id) {
		for (Entry entry : entries) if (entry.componentId().equals(id)) return entry;
		return null;
	}
	public long standardVolumeNanos() {
		long total = 0L;
		for (Entry entry : entries) total = Math.addExact(total, entry.standardVolumeNanos());
		return total;
	}

	public GasAuditLedger add(GasInventory inventory) { return add(of(inventory)); }

	public GasAuditLedger add(GasAuditLedger other) {
		if (other.entries.isEmpty()) return this;
		if (entries.isEmpty()) return other;
		TreeMap<String, Mutable> totals = mutable(entries);
		for (Entry entry : other.entries) totals.computeIfAbsent(entry.componentId(), ignored -> new Mutable()).add(entry);
		if (totals.size() > GasLimits.MAX_COMPONENTS)
			throw new GasInventory.RepresentationException("Gas audit exceeds its component slots");
		ArrayList<Entry> result = new ArrayList<>(totals.size());
		for (Map.Entry<String, Mutable> entry : totals.entrySet()) result.add(entry.getValue().freeze(entry.getKey()));
		return new GasAuditLedger(result);
	}

	private static TreeMap<String, Mutable> mutable(List<Entry> entries) {
		TreeMap<String, Mutable> result = new TreeMap<>();
		for (Entry entry : entries) result.computeIfAbsent(entry.componentId(), ignored -> new Mutable()).add(entry);
		return result;
	}

	private static final class Mutable {
		private long nanos;
		private long massUnits;
		private long residualUnits;
		private final TreeMap<String, Long> species = new TreeMap<>();
		void add(Entry entry) {
			nanos = GasInventory.addColumn(nanos, entry.standardVolumeNanos(), "audit standard volume"); massUnits = Math.addExact(massUnits, entry.massUnits());
			residualUnits = Math.addExact(residualUnits, entry.massResidualUnits());
			for (GasComponentAmount.SpeciesAmount amount : entry.species()) {
				species.merge(amount.speciesId(), amount.amountQuanta(), (left, right) -> GasInventory.addColumn(left, right, "audit species quantity"));
			}
		}
		Entry freeze(String id) {
			if (species.size() > GasLimits.MAX_SPECIES_PER_COMPONENT)
				throw new GasInventory.RepresentationException("Gas audit exceeds its species slots");
			return new Entry(id, nanos, massUnits, species.entrySet().stream()
					.map(entry -> new GasComponentAmount.SpeciesAmount(entry.getKey(), entry.getValue())).toList(), residualUnits);
		}
	}

	@Override public boolean equals(Object other) {
		return other instanceof GasAuditLedger ledger && entries.equals(ledger.entries);
	}
	@Override public int hashCode() { return entries.hashCode(); }
}
