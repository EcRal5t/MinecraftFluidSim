package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/** Exact region summaries and closure checks, independent of the finite columns of one node. */
final class GasRegionAudit {
	private GasRegionAudit() { }
	static GasAuditLedger aggregate(Iterable<GasInventory> inventories) {
		TreeMap<String, Accumulator> totals = new TreeMap<>();
		for (GasInventory inventory : inventories) for (var component : inventory.components()) {
			Accumulator total = totals.get(component.componentId());
			if (total == null) {
				if (totals.size() == GasLimits.MAX_COMPONENTS) throw new IllegalArgumentException("Gas region components are over budget");
				totals.put(component.componentId(), total = new Accumulator());
			}
			total.add(component);
		}
		ArrayList<GasAuditLedger.Entry> entries = new ArrayList<>(totals.size());
		totals.forEach((id, total) -> entries.add(total.entry(id)));
		GasAuditLedger result = new GasAuditLedger(entries);
		result.standardVolumeNanos();
		return result;
	}
	private static final class Accumulator {
		long nanos;
		SignedDyadic mass = SignedDyadic.ZERO, residual = SignedDyadic.ZERO;
		final TreeMap<String, Long> species = new TreeMap<>();
		void add(GasComponentAmount amount) {
			nanos = Math.addExact(nanos, amount.standardVolumeNanos());
			mass = mass.add(SignedDyadic.of(amount.mass())); residual = residual.add(SignedDyadic.of(amount.massResidual()));
			for (var entry : amount.species()) species.merge(entry.speciesId(), entry.amountQuanta(), Math::addExact);
		}
		GasAuditLedger.Entry entry(String id) {
			return new GasAuditLedger.Entry(id, nanos, mass, species.entrySet().stream().map(entry ->
					new GasComponentAmount.SpeciesAmount(entry.getKey(), entry.getValue())).toList(), residual);
		}
	}
	static void requireClosure(GasAuditLedger before, GasAuditLedger after, GasAuditLedger source, GasAuditLedger sink) {
		TreeMap<String, Columns> left = columns(before), right = columns(after);
		merge(left, columns(source)); merge(right, columns(sink));
		if (!left.equals(right)) throw new IllegalArgumentException("Gas topology remap does not close component nanos/species");
	}
	static boolean closed(GasAuditLedger live, GasAuditLedger source, GasAuditLedger sink,
	                      SignedDyadic massRoundoff, SignedDyadic residualRoundoff) {
		source.standardVolumeNanos(); sink.standardVolumeNanos();
		TreeMap<String, Columns> left = columns(live), right = columns(source);
		merge(left, columns(sink));
		if (!left.equals(right)) return false;
		return mass(live, false).add(mass(sink, false)).equals(mass(source, false).add(massRoundoff))
				&& mass(live, true).add(mass(sink, true)).equals(mass(source, true).add(residualRoundoff));
	}
	static SignedDyadic mass(GasAuditLedger ledger, boolean residual) {
		SignedDyadic result = SignedDyadic.ZERO;
		for (var entry : ledger.entries()) result = result.add(residual ? entry.massResidual() : entry.mass());
		return result;
	}
	private static TreeMap<String, Columns> columns(GasAuditLedger ledger) {
		TreeMap<String, Columns> result = new TreeMap<>();
		for (var entry : ledger.entries()) {
			TreeMap<String, Long> species = new TreeMap<>();
			for (var amount : entry.species()) species.put(amount.speciesId(), amount.amountQuanta());
			result.put(entry.componentId(), new Columns(entry.standardVolumeNanos(), species));
		}
		return result;
	}
	private static void merge(TreeMap<String, Columns> target, TreeMap<String, Columns> values) {
		values.forEach((id, value) -> target.merge(id, value, Columns::add));
	}
	private record Columns(long nanos, Map<String, Long> species) {
		private Columns { species = Map.copyOf(species); }
		static Columns add(Columns first, Columns second) {
			TreeMap<String, Long> species = new TreeMap<>(first.species);
			second.species.forEach((id, value) -> species.merge(id, value, Math::addExact));
			return new Columns(Math.addExact(first.nanos, second.nanos), species);
		}
	}
}
