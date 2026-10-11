package cc.ecisr.mmm.fluid;

import java.util.function.LongFunction;

/** Searches the complete integer batch set; only interval proofs can omit candidates. */
final class GasPhaseBatchSearch {
	@FunctionalInterface
	interface Exclusion { boolean excluded(long low, long high); }

	private GasPhaseBatchSearch() { }

	static <P> P maximum(long high, LongFunction<P> candidate, Exclusion exclusion) {
		if (high <= 0L) return null;
		P last = candidate.apply(high);
		return last != null ? last : search(1L, high - 1L, candidate, exclusion);
	}

	private static <P> P search(long low, long high, LongFunction<P> candidate, Exclusion exclusion) {
		if (low > high) return null;
		if (low == high) return candidate.apply(low);
		if (exclusion.excluded(low, high)) return null;
		long middle = low + ((high - low) >>> 1);
		P upper = search(middle + 1L, high, candidate, exclusion);
		return upper != null ? upper : search(low, middle, candidate, exclusion);
	}
}
