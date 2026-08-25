package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.thermal.SignedDyadic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exact cumulative gas source/sink ledger; raw doubles are accumulated as exact dyadics. */
public final class GasAuditLedger {
	public static final GasAuditLedger EMPTY = new GasAuditLedger(List.of());

	public record Entry(
			String componentId, long standardVolumeNanos, SignedDyadic mass,
			List<GasComponentAmount.SpeciesAmount> species, SignedDyadic massResidual
	) {
		public Entry {
			GasIds.require(componentId, "gas audit component id");
			if (standardVolumeNanos <= 0L || mass == null || mass.compareTo(SignedDyadic.ZERO) <= 0
					|| species == null || massResidual == null) {
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
				amount.componentId(), amount.standardVolumeNanos(), SignedDyadic.of(amount.mass()), amount.species(),
				SignedDyadic.of(amount.massResidual())));
		return result.isEmpty() ? EMPTY : new GasAuditLedger(result);
	}

	public List<Entry> entries() { return entries; }
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
		private SignedDyadic mass = SignedDyadic.ZERO;
		private SignedDyadic residual = SignedDyadic.ZERO;
		private final TreeMap<String, Long> species = new TreeMap<>();
		void add(Entry entry) {
			nanos = Math.addExact(nanos, entry.standardVolumeNanos()); mass = mass.add(entry.mass());
			residual = residual.add(entry.massResidual());
			for (GasComponentAmount.SpeciesAmount amount : entry.species()) {
				species.merge(amount.speciesId(), amount.amountQuanta(), Math::addExact);
			}
		}
		Entry freeze(String id) {
			return new Entry(id, nanos, mass, species.entrySet().stream()
					.map(entry -> new GasComponentAmount.SpeciesAmount(entry.getKey(), entry.getValue())).toList(), residual);
		}
	}

	@Override public boolean equals(Object other) {
		return other instanceof GasAuditLedger ledger && entries.equals(ledger.entries);
	}
	@Override public int hashCode() { return entries.hashCode(); }
}
