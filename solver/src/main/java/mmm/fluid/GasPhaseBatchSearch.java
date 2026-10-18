package mmm.fluid;

import java.util.function.DoubleUnaryOperator;
import java.util.function.LongPredicate;

/** Bounded scalar phase closure; trials use floating caloric state and never publish inventory. */
final class GasPhaseBatchSearch {
	private GasPhaseBatchSearch() { }

	/** The conserved chamber model supplies a nonincreasing pressure residual. */
	static double pressure(double upper, DoubleUnaryOperator residual) {
		if (!(Double.isFinite(upper) && upper > 0) || residual == null)
			throw new IllegalArgumentException("Invalid phase pressure bracket");
		double lower = 0, first = residual.applyAsDouble(0);
		if (!Double.isFinite(first)) throw new IllegalArgumentException("Nonfinite phase pressure residual");
		if (first <= 0) return 0;
		double end = residual.applyAsDouble(upper);
		while (end > 0) {
			upper *= 2;
			if (!Double.isFinite(upper)) throw new IllegalArgumentException("Phase pressure has no finite bracket");
			end = residual.applyAsDouble(upper);
		}
		if (!Double.isFinite(end)) throw new IllegalArgumentException("Nonfinite phase pressure residual");
		for (int iteration = 0; iteration < 32; iteration++) {
			double middle = lower + (upper - lower) * .5;
			double value = residual.applyAsDouble(middle);
			if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite phase pressure residual");
			if (value > 0) lower = middle; else upper = middle;
			if (upper - lower <= 1e-6 * Math.max(1, middle)) break;
		}
		return lower + (upper - lower) * .5;
	}

	/** Integer inventory admission retained for the independent solid/liquid transition. */
	static long upperBound(long high, LongPredicate possible) {
		long low = 0;
		while (low < high) {
			long span = high - low, middle = low + (span >>> 1) + (span & 1);
			if (possible.test(middle)) low = middle; else high = middle - 1;
		}
		return low;
	}
}
