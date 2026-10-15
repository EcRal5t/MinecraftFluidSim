package mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import mmm.units.SignedDyadic;

/** Conditional positive spatial rule; each branch shares the remaining spatial covariance. */
final class CellRemapBoxQuadrature {
	private CellRemapBoxQuadrature() { }
	record Rule(ArrayList<Float> volumes, double[][] positions) { }
	private record Node(double weight, double[] mean) { }

	static Rule create(SignedDyadic volume, double[] mean, double[][] loads, int spatialRank,
	                   int minimumCount, double[] lower, double[] upper) {
		if (volume == null || volume.signum() <= 0 || minimumCount < 1 || spatialRank < 0 || spatialRank > 3)
			throw new IllegalArgumentException("Invalid positive spatial quadrature");
		var factor = new double[3][spatialRank];
		for (int a = 0; a < 3; a++) {
			if (!(Double.isFinite(mean[a]) && lower[a] <= mean[a] && mean[a] <= upper[a])) return null;
			System.arraycopy(loads[a], 0, factor[a], 0, spatialRank);
		}
		var nodes = partition(1, mean, factor, 0, lower, upper);
		if (nodes == null) return null;
		var weights = new SignedDyadic[nodes.size()]; int pivot = 0;
		for (int p = 0; p < weights.length; p++) {
			weights[p] = SignedDyadic.of(nodes.get(p).weight);
			if (nodes.get(p).weight > nodes.get(pivot).weight) pivot = p;
		}
		var allocated = ExactVolumeAllocation.allocate(volume, weights);
		var volumes = new ArrayList<Float>(); var positions = new ArrayList<double[]>(); var remaining = volume;
		for (int p = 0; p < allocated.length; p++) if (p != pivot) {
			float value = (float) allocated[p].toDouble();
			if (SignedDyadic.of(value).compareTo(allocated[p]) > 0) value = Math.nextDown(value);
			if (!(value > 0 && Float.isFinite(value))) return null;
			volumes.add(value); positions.add(nodes.get(p).mean); remaining = remaining.subtract(SignedDyadic.of(value));
		}
		for (float value : CellRemapChemicalSupport.expansion(remaining)) {
			volumes.add(value); positions.add(nodes.get(pivot).mean);
		}
		while (volumes.size() < minimumCount) {
			int largest = 0;
			for (int p = 1; p < volumes.size(); p++) if (volumes.get(p) > volumes.get(largest)) largest = p;
			float old = volumes.get(largest), first = old * .5F, second = old - first;
			if (!(first > 0 && second > 0) || !SignedDyadic.of(first).add(SignedDyadic.of(second)).equals(SignedDyadic.of(old))) return null;
			volumes.set(largest, first); volumes.add(second); positions.add(positions.get(largest));
		}
		return new Rule(volumes, positions.toArray(double[][]::new));
	}

	private static ArrayList<Node> partition(double weight, double[] mean, double[][] factor, int resolved,
	                                         double[] lower, double[] upper) {
		var variance = new double[3]; int remainingAxes = 0;
		for (int a = 0; a < 3; a++) if ((resolved & 1 << a) == 0) {
			variance[a] = dot(factor[a], factor[a]);
			if (variance[a] > 0) remainingAxes |= 1 << a;
		}
		if (remainingAxes == 0) {
			var leaf = new ArrayList<Node>(); leaf.add(new Node(weight, mean)); return leaf;
		}
		var order = new Integer[]{0, 1, 2};
		Arrays.sort(order, (a, b) -> Double.compare(constraint(mean, variance, lower, upper, b), constraint(mean, variance, lower, upper, a)));
		for (int axis : order) if ((remainingAxes & 1 << axis) != 0) {
			double sigma = Math.sqrt(variance[axis]); var direction = factor[axis].clone();
			for (int k = 0; k < direction.length; k++) direction[k] /= sigma;
			var response = new double[3]; var residual = new double[3][direction.length];
			for (int a = 0; a < 3; a++) {
				response[a] = dot(factor[a], direction);
				for (int k = 0; k < direction.length; k++) residual[a][k] = Math.fma(-response[a], direction[k], factor[a][k]);
			}
			response[axis] = sigma; Arrays.fill(residual[axis], 0);
			double negative = Double.POSITIVE_INFINITY, positive = Double.POSITIVE_INFINITY;
			for (int a = 0; a < 3; a++) if (response[a] != 0) {
				double low = mean[a] - lower[a], high = upper[a] - mean[a], magnitude = Math.abs(response[a]);
				negative = Math.min(negative, (response[a] > 0 ? low : high) / magnitude);
				positive = Math.min(positive, (response[a] > 0 ? high : low) / magnitude);
			}
			if (!(negative > 0 && positive > 0 && negative * positive >= 1)) continue;
			// Both arms use the same fraction of their available distance, leaving interior storage room.
			double minus = Math.sqrt(negative / positive), plus = 1 / minus;
			double probability = plus / (minus + plus), otherProbability = minus / (minus + plus);
			var lowMean = new double[3]; var highMean = new double[3];
			boolean inside = true;
			for (int a = 0; a < 3; a++) {
				lowMean[a] = Math.fma(-minus, response[a], mean[a]); highMean[a] = Math.fma(plus, response[a], mean[a]);
				inside &= lowMean[a] >= lower[a] && lowMean[a] <= upper[a] && highMean[a] >= lower[a] && highMean[a] <= upper[a];
			}
			if (!inside) continue;
			double lowCapacity = capacity(lowMean, residual, lower, upper), highCapacity = capacity(highMean, residual, lower, upper);
			double lowScale = 1, highScale = 1;
			if (Double.isFinite(lowCapacity)) {
				// Equal capacity utilisation keeps both children interior, sharing the same remaining PSD factor.
				double maximum = Math.max(lowCapacity, highCapacity);
				if (!(maximum > 0)) continue;
				double lowRatio = lowCapacity / maximum, highRatio = highCapacity / maximum;
				double totalCapacity = Math.fma(probability, lowRatio, otherProbability * highRatio);
				lowScale = lowRatio / totalCapacity; highScale = highRatio / totalCapacity;
			}
			if (!(lowScale >= 0 && highScale >= 0 && lowScale <= lowCapacity && highScale <= highCapacity)) continue;
			var low = partition(weight * probability, lowMean, scaled(residual, Math.sqrt(lowScale)), resolved | 1 << axis, lower, upper);
			if (low == null) continue;
			var high = partition(weight * otherProbability, highMean, scaled(residual, Math.sqrt(highScale)), resolved | 1 << axis, lower, upper);
			if (high != null) { low.addAll(high); return low; }
		}
		return null;
	}
	private static double constraint(double[] mean, double[] variance, double[] lower, double[] upper, int axis) {
		double available = (mean[axis] - lower[axis]) * (upper[axis] - mean[axis]);
		return variance[axis] == 0 ? 0 : variance[axis] / available;
	}
	private static double capacity(double[] mean, double[][] factor, double[] lower, double[] upper) {
		double result = Double.POSITIVE_INFINITY;
		for (int a = 0; a < 3; a++) {
			double variance = dot(factor[a], factor[a]);
			if (variance > 0) result = Math.min(result, (mean[a] - lower[a]) * (upper[a] - mean[a]) / variance);
		}
		return result;
	}
	private static double[][] scaled(double[][] factor, double scale) {
		var result = new double[3][factor[0].length];
		for (int a = 0; a < 3; a++) for (int k = 0; k < factor[a].length; k++) result[a][k] = scale * factor[a][k];
		return result;
	}
	private static double dot(double[] first, double[] second) {
		double value = 0; for (int k = 0; k < first.length; k++) value = Math.fma(first[k], second[k], value); return value;
	}
}
