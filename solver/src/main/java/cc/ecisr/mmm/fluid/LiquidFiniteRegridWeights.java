package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finite positive weights retain each target capacity and each original inventory/velocity volume exactly. */
final class LiquidFiniteRegridWeights {
	record Result(List<Map<LiquidGeometryVelocityProjection.Key, Fraction>> rows, Fraction movedVolumeBound,
			List<Fraction> kineticErrorBounds, List<Fraction> massErrorBounds) { }
	private LiquidFiniteRegridWeights() { }
	static Result project(List<Map<LiquidGeometryVelocityProjection.Key, Fraction>> reference) {
		var columns = new LinkedHashMap<LiquidGeometryVelocityProjection.Key, Integer>(); var totals = new LinkedHashMap<Integer, Fraction>();
		var rows = new ArrayList<Map<Integer, Fraction>>(); var quantum = new ArrayList<Fraction>(); var massQuantum = new ArrayList<Fraction>();
		var relativeQuantum = Fraction.of(Math.ulp(1.0) / 2);
		for (var row : reference) {
			var local = new LinkedHashMap<Integer, Fraction>(); var volume = Fraction.ZERO; var mass = LiquidDyadicInterval.of(Fraction.ZERO, 64);
			for (var entry : row.entrySet()) {
				int column = columns.computeIfAbsent(entry.getKey(), ignored -> columns.size());
				local.put(column, entry.getValue()); totals.merge(column, entry.getValue(), Fraction::add); volume = volume.add(entry.getValue());
				mass = mass.add(LiquidDyadicInterval.of(entry.getValue().multiply(entry.getKey().density()), 64));
			}
			rows.add(local); quantum.add(volume.multiply(relativeQuantum)); massQuantum.add(mass.low().multiply(relativeQuantum));
		}
		for (int bits = 53;; bits = Math.min(1074, 2 * bits)) {
			var finite = LiquidPhaseVolumeRounding.round(rows, totals, bits, quantum, relativeQuantum);
			var keys = new ArrayList<>(columns.keySet()); var result = new ArrayList<Map<LiquidGeometryVelocityProjection.Key, Fraction>>(); var changed = Fraction.ZERO;
			var kineticBounds = new ArrayList<Fraction>(); var massBounds = new ArrayList<Fraction>();
			for (int r = 0; r < rows.size(); r++) {
				var row = new LinkedHashMap<LiquidGeometryVelocityProjection.Key, Fraction>(); var massChange = Fraction.ZERO;
				var low = new Fraction[3]; var high = new Fraction[3];
				for (var entry : finite.get(r).entrySet()) {
					var key = keys.get(entry.getKey()); row.put(key, entry.getValue());
					var delta = entry.getValue().subtract(rows.get(r).get(entry.getKey())).absolute(); changed = changed.add(LiquidDyadicInterval.of(delta, 64).high());
					// Bound the change of the reference quadratic error about any positive mass-weighted mean.
					var upper = LiquidDyadicInterval.of(delta.multiply(key.density()), 64).high(); massChange = massChange.add(upper);
					for (int a = 0; a < 3; a++) {
						var u = Fraction.of(key.velocity().coordinate(a));
						if (low[a] == null || u.compareTo(low[a]) < 0) low[a] = u;
						if (high[a] == null || u.compareTo(high[a]) > 0) high[a] = u;
					}
				}
				var spanSquared = Fraction.ZERO; for (int a = 0; a < 3; a++) { var span = high[a].subtract(low[a]); spanSquared = spanSquared.add(span.multiply(span)); }
				massBounds.add(massChange); kineticBounds.add(massChange.multiply(spanSquared).divide(Fraction.of(2)));
				result.add(java.util.Collections.unmodifiableMap(row));
			}
			boolean accepted = true; for (int r = 0; r < rows.size(); r++) accepted &= massBounds.get(r).compareTo(massQuantum.get(r)) <= 0;
			if (accepted) return new Result(List.copyOf(result), changed.divide(Fraction.of(2)), List.copyOf(kineticBounds), List.copyOf(massBounds));
			if (bits == 1074) throw new LiquidCoordinateResolutionRequired("Finite inventory weight mass errors exceed their relative coordinate support");
		}
	}
}
