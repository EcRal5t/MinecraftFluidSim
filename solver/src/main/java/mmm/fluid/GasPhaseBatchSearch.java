package mmm.fluid;

import java.util.HashMap;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/** Brackets actual saturation samples and selects an admissible, representable integer extent. */
final class GasPhaseBatchSearch {
	private GasPhaseBatchSearch() { }

	/** Positive residual retains the source phase direction; null samples are representation holes. */
	record Sample<P>(double residual, P plan) {
		Sample {
			if (Double.isNaN(residual) || residual >= 0 && plan == null)
				throw new IllegalArgumentException("A phase residual requires an actual admissible plan");
		}
	}

	static <P> P equilibrium(long high, LongFunction<Sample<P>> candidate) {
		if (high <= 0) return null;
		// Each allowed integer extent is evaluated at most once, including representation holes.
		long budget = high;
		var samples = new HashMap<Long, Sample<P>>();
		Sample<P> end = candidate.apply(high);
		samples.put(high, end);
		if (end != null && end.residual() >= 0) return end.plan();
		long low = 0, upper = high;
		P best = null;
		while (low + 1 < upper && samples.size() < budget) {
			long middle = low == 0 ? 1 : low + (upper - low) / 2;
			long found = -1;
			Sample<P> sample = null;
			// A missing chemical/owner quantum has no residual sign. Inspect neighbours of the root query.
			for (long distance = 0; samples.size() < budget; distance++) {
				boolean inRange = false;
				for (int side = 0; side < (distance == 0 ? 1 : 2); side++) {
					long point = side == 0 ? middle + distance : middle - distance;
					if (point <= low || point >= upper) continue;
					inRange = true;
					if (!samples.containsKey(point)) samples.put(point, candidate.apply(point));
					sample = samples.get(point);
					if (sample != null) { found = point; break; }
					if (samples.size() >= budget) break;
				}
				if (found >= 0 || !inRange && middle + distance >= upper && middle - distance <= low) break;
			}
			if (found < 0) break;
			if (sample.residual() >= 0) { low = found; best = sample.plan(); }
			else upper = found;
		}
		return best;
	}

	/** The supplied physical necessary condition must be nonincreasing over the whole extent. */
	static long upperBound(long high, LongPredicate possible) {
		long low = 0;
		while (low < high) {
			long span = high - low, middle = low + (span >>> 1) + (span & 1);
			if (possible.test(middle)) low = middle;
			else high = middle - 1;
		}
		return low;
	}
}
