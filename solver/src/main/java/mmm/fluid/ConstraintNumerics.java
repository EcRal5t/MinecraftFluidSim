package mmm.fluid;


/** Products used by mass-weighted constraint responses. */
final class ConstraintNumerics {
	private ConstraintNumerics() { }

	/** Renormalized high/low sum; neither input needs to dominate the other. */
	static void accumulate(double[] high, double[] low, int index, double value) {
		double sum = high[index] + value, virtual = sum - high[index];
		double error = (high[index] - (sum - virtual)) + (value - virtual);
		double tail = low[index] + error, result = sum + tail;
		virtual = result - sum;
		low[index] = (sum - (result - virtual)) + (tail - virtual);
		high[index] = result;
	}

	static void accumulateProduct(double[] high, double[] low, int index, double first, double second) {
		double product = first * second;
		accumulate(high, low, index, product);
		accumulate(high, low, index, Math.fma(first, second, -product));
	}

}
