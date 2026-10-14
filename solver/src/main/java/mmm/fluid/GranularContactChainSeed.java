package mmm.fluid;

import java.util.Arrays;
import mmm.units.SignedDyadic;

/** Exact isotonic impulse seed for a directed axis chain, optionally anchored at one end. */
final class GranularContactChainSeed {
	private GranularContactChainSeed() { }

	static boolean apply(double[] mass, double[][] input, GranularContactCompression.Row[] rows,
	                     double[] lambda, Runnable spend) {
		int count = mass.length;
		if (count == 0 || rows.length < count - 1 || rows.length > count) return false;
		int axis = -1;
		for (var row : rows) {
			spend.run(); int active = -1;
			for (int a = 0; a < 3; a++) if (row.normal()[a] != 0) {
				if (active >= 0 || Math.abs(row.normal()[a]) != 1) return false;
				active = a;
			}
			if (active < 0 || axis >= 0 && active != axis) return false;
			axis = active;
		}
		if (axis < 0) return false;
		int[] next = new int[count], previous = new int[count], edge = new int[count], order = new int[count];
		for (int i = 0; i < count; i++) { spend.run(); next[i] = previous[i] = edge[i] = -1; }
		int fixed = -1, pairs = 0;
		for (int k = 0; k < rows.length; k++) {
			spend.run(); var row = rows[k];
			if (row.second() < 0) { if (fixed >= 0) return false; fixed = k; continue; }
			int low = row.normal()[axis] > 0 ? row.first() : row.second();
			int high = row.normal()[axis] > 0 ? row.second() : row.first();
			if (next[low] >= 0 || previous[high] >= 0) return false;
			next[low] = high; previous[high] = low; edge[low] = k; pairs++;
		}
		if (pairs != count - 1) return false;
		int start = -1;
		for (int i = 0; i < count; i++) {
			spend.run(); if (previous[i] >= 0) continue;
			if (start >= 0) return false; start = i;
		}
		if (start < 0) return false;
		int vertex = start;
		for (int i = 0; i < count; i++) {
			spend.run(); if (vertex < 0) return false;
			order[i] = vertex; vertex = next[vertex];
		}
		if (vertex >= 0) return false;
		boolean lower = fixed >= 0 && rows[fixed].normal()[axis] > 0;
		if (fixed >= 0 && rows[fixed].first() != order[lower ? 0 : count - 1]) return false;
		SignedDyadic[] weights = new SignedDyadic[count], momentum = new SignedDyadic[count];
		SignedDyadic[] poolMass = new SignedDyadic[count], poolMomentum = new SignedDyadic[count];
		int[] end = new int[count]; int pools = 0;
		// Pool comparisons use exact momentum and mass, without observing a rounded mean.
		for (int i = 0; i < count; i++) {
			spend.run(); weights[i] = SignedDyadic.of(mass[order[i]]);
			momentum[i] = weights[i].multiply(SignedDyadic.of(input[order[i]][axis]));
			poolMass[pools] = weights[i]; poolMomentum[pools] = momentum[i]; end[pools++] = i;
			while (pools > 1) {
				spend.run(); int left = pools - 2, right = pools - 1;
				if (poolMomentum[left].multiply(poolMass[right]).compareTo(poolMomentum[right].multiply(poolMass[left])) <= 0) break;
				poolMass[left] = poolMass[left].add(poolMass[right]);
				poolMomentum[left] = poolMomentum[left].add(poolMomentum[right]); end[left] = end[right]; pools--;
			}
		}
		int clamp = lower ? -1 : count; var clampedMomentum = SignedDyadic.ZERO;
		if (fixed >= 0) for (int p = 0; p < pools; p++) {
			spend.run(); int sign = poolMomentum[p].signum();
			if (lower ? sign < 0 : sign > 0) {
				clampedMomentum = clampedMomentum.add(poolMomentum[p]);
				if (lower) clamp = end[p]; else clamp = Math.min(clamp, p == 0 ? 0 : end[p - 1] + 1);
			}
		}
		double[] candidate = new double[rows.length];
		if (fixed >= 0) {
			spend.run(); var impulse = lower ? clampedMomentum.negate() : clampedMomentum;
			candidate[fixed] = GranularContactCompression.ratio(impulse, SignedDyadic.of(1)).toDouble();
			if (!representable(impulse, candidate[fixed])) return false;
		}
		var clampPrefix = SignedDyadic.ZERO;
		// Recover impulses from exact pool prefixes; only the final impulse is rounded.
		for (int p = 0; p < pools; p++) {
			int begin = p == 0 ? 0 : end[p - 1] + 1;
			boolean clamped = fixed >= 0 && (lower ? end[p] <= clamp : begin >= clamp);
			var prefixMass = SignedDyadic.ZERO; var prefixMomentum = SignedDyadic.ZERO;
			for (int i = begin; i <= end[p]; i++) {
				spend.run(); prefixMass = prefixMass.add(weights[i]); prefixMomentum = prefixMomentum.add(momentum[i]);
				if (clamped) clampPrefix = clampPrefix.add(momentum[i]);
				if (edge[order[i]] < 0) continue;
				SignedDyadic numerator, denominator;
				if (clamped) {
					numerator = lower ? clampPrefix.subtract(clampedMomentum) : clampPrefix; denominator = SignedDyadic.of(1);
				} else {
					numerator = prefixMomentum.multiply(poolMass[p]).subtract(prefixMass.multiply(poolMomentum[p])); denominator = poolMass[p];
				}
				spend.run(); double impulse = GranularContactCompression.ratio(numerator, denominator).toDouble();
				if (!representable(numerator, impulse)) return false;
				candidate[edge[order[i]]] = impulse;
			}
		}
		if (Arrays.equals(candidate, lambda)) return false;
		System.arraycopy(candidate, 0, lambda, 0, lambda.length); return true;
	}

	private static boolean representable(SignedDyadic exact, double value) {
		return exact.signum() >= 0 && Double.isFinite(value) && (exact.signum() == 0 || value > 0);
	}
}
