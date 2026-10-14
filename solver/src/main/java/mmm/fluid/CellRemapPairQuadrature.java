package mmm.fluid;

import java.util.ArrayList;
import mmm.units.SignedDyadic;

/** Positive unequal-volume sigma pairs inside a component's spatial box. */
final class CellRemapPairQuadrature {
	private static final SignedDyadic ONE = SignedDyadic.of(1);
	private CellRemapPairQuadrature() { }
	record Rule(ArrayList<Float> volumes, int[] arms, int rank) { }

	static Rule create(SignedDyadic volume, double[] mean, double[][] loads, int rank,
			double[] lower, double[] upper) {
		if (volume == null || volume.signum() <= 0 || rank < 0 || rank > 6
				|| mean.length != 3 || lower.length != 3 || upper.length != 3 || loads.length < 3)
			throw new IllegalArgumentException("Invalid liquid paired quadrature input");
		for (int a = 0; a < 3; a++) {
			if (loads[a].length < rank || !Double.isFinite(mean[a]) || !Double.isFinite(lower[a])
					|| !Double.isFinite(upper[a]) || lower[a] > upper[a])
				throw new IllegalArgumentException("Invalid liquid paired quadrature box");
			if (mean[a] < lower[a] || mean[a] > upper[a]) return null;
		}
		var negative = new double[rank]; var positive = new double[rank]; var minimum = new double[rank];
		SignedDyadic minimumSum = SignedDyadic.ZERO;
		for (int k = 0; k < rank; k++) {
			negative[k] = positive[k] = Double.POSITIVE_INFINITY;
			for (int a = 0; a < 3; a++) {
				double load = loads[a][k];
				if (!Double.isFinite(load)) throw new IllegalArgumentException("Non-finite liquid paired load");
				if (load == 0) continue;
				var lowGap = SignedDyadic.of(mean[a]).subtract(SignedDyadic.of(lower[a]));
				var highGap = SignedDyadic.of(upper[a]).subtract(SignedDyadic.of(mean[a]));
				var magnitude = SignedDyadic.of(Math.abs(load));
				negative[k] = Math.min(negative[k], ratio(load > 0 ? lowGap : highGap, magnitude, false));
				positive[k] = Math.min(positive[k], ratio(load > 0 ? highGap : lowGap, magnitude, false));
			}
			if (!(negative[k] > 0 && positive[k] > 0)) return null;
			if (Double.isFinite(negative[k])) {
				var product = SignedDyadic.of(negative[k]).multiply(SignedDyadic.of(positive[k]));
				minimum[k] = ratio(ONE, product, true);
				if (!Double.isFinite(minimum[k])) return null;
				minimumSum = minimumSum.add(SignedDyadic.of(minimum[k]));
			}
		}
		if (minimumSum.compareTo(ONE) >= 0) return null;
		var extra = ONE.subtract(minimumSum); double increment = ratio(extra, SignedDyadic.of(rank + 1), false);
		var weights = new SignedDyadic[2 * rank + 1]; SignedDyadic pairSum = SignedDyadic.ZERO;
		for (int k = 0; k < rank; k++) {
			var exactProbability = SignedDyadic.of(minimum[k]).add(SignedDyadic.of(increment));
			double observed = exactProbability.toDouble();
			if (SignedDyadic.of(observed).compareTo(exactProbability) > 0) observed = Math.nextDown(observed);
			if (!(observed > 0)) return null;
			var probability = SignedDyadic.of(observed); var half = probability.multiply(SignedDyadic.of(.5));
			SignedDyadic minus = half, plus = half;
			boolean negativeLimited = Double.isFinite(negative[k])
					&& probability.multiply(square(negative[k])).compareTo(ONE) < 0;
			boolean positiveLimited = Double.isFinite(positive[k])
					&& probability.multiply(square(positive[k])).compareTo(ONE) < 0;
			if (negativeLimited || positiveLimited) {
				double limited = negativeLimited ? negative[k] : positive[k], opposite = negativeLimited ? positive[k] : negative[k];
				// An interior arm leaves room for the final binary32 volume allocation.
				double interior = Math.sqrt(limited * ratio(ONE, probability.multiply(SignedDyadic.of(opposite)), true));
				var scaledSquare = probability.multiply(square(interior));
				var small = SignedDyadic.of(ratio(probability.multiply(scaledSquare), ONE.add(scaledSquare), false));
				if (negativeLimited) { plus = small; minus = probability.subtract(small); }
				else { minus = small; plus = probability.subtract(small); }
			}
			// These exact inequalities certify both latent arms before storage allocation.
			if (minus.signum() <= 0 || plus.signum() <= 0
					|| Double.isFinite(negative[k]) && plus.compareTo(minus.multiply(probability).multiply(square(negative[k]))) > 0
					|| Double.isFinite(positive[k]) && minus.compareTo(plus.multiply(probability).multiply(square(positive[k]))) > 0)
				return null;
			weights[2 * k] = minus; weights[2 * k + 1] = plus; pairSum = pairSum.add(probability);
		}
		weights[2 * rank] = ONE.subtract(pairSum);
		if (weights[2 * rank].signum() <= 0) return null;
		var allocated = ExactVolumeAllocation.allocate(volume, weights);
		var volumes = new ArrayList<Float>(); var labels = new ArrayList<Integer>(); var center = volume;
		for (int arm = 0; arm < 2 * rank; arm++) {
			float value = (float) allocated[arm].toDouble();
			if (SignedDyadic.of(value).compareTo(allocated[arm]) > 0) value = Math.nextDown(value);
			if (!(value > 0 && Float.isFinite(value))) return null;
			volumes.add(value); labels.add(arm); center = center.subtract(SignedDyadic.of(value));
		}
		try { for (float value : CellRemapChemicalSupport.expansion(center)) { volumes.add(value); labels.add(-1); } }
		catch (IllegalStateException unrepresentable) { return null; }
		var arms = new int[labels.size()]; for (int p = 0; p < arms.length; p++) arms[p] = labels.get(p);
		return new Rule(volumes, arms, rank);
	}

	private static SignedDyadic square(double value) { var exact = SignedDyadic.of(value); return exact.multiply(exact); }
	private static double ratio(SignedDyadic numerator, SignedDyadic denominator, boolean upward) {
		double value = PlicComponentGeometry.ratio(numerator, denominator).toDouble();
		if (!Double.isFinite(value)) return upward ? value : Double.MAX_VALUE;
		int comparison = SignedDyadic.of(value).multiply(denominator).compareTo(numerator);
		return upward && comparison < 0 ? Math.nextUp(value) : !upward && comparison > 0 ? Math.nextDown(value) : value;
	}
}
