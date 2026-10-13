package mmm.fluid;

/** Positive joint covariance square root, compressed by orthogonal column rotations. */
final class JointMomentFactor {
	private final double[][] values = new double[6][6];
	private final double[][] joined = new double[6][13];

	void merge(JointMomentFactor other, double retained, double ratio, double[] delta) {
		double oldScale = Math.sqrt(retained), addedScale = Math.sqrt(ratio), separation = oldScale * addedScale;
		for (int a = 0; a < 6; a++) {
			for (int k = 0; k < 6; k++) {
				joined[a][k] = finite(oldScale * values[a][k]);
				joined[a][k + 6] = other == null ? 0 : finite(addedScale * other.values[a][k]);
			}
			joined[a][12] = finite(separation * delta[a]);
		}
		// Rotate each row's tail into its pivot. No covariance subtraction or rank truncation.
		for (int a = 0; a < 6; a++) for (int k = 12; k > a; k--) {
			double x = joined[a][a], y = joined[a][k];
			if (y == 0) continue;
			double norm = finite(Math.hypot(x, y)), c = x / norm, s = y / norm;
			for (int b = a + 1; b < 6; b++) {
				double pivot = joined[b][a], tail = joined[b][k];
				joined[b][a] = finite(c * pivot + s * tail);
				joined[b][k] = finite(-s * pivot + c * tail);
			}
			joined[a][a] = norm; joined[a][k] = 0;
		}
		for (int a = 0; a < 6; a++) System.arraycopy(joined[a], 0, values[a], 0, 6);
	}

	void advancePositions(double alpha) {
		for (int a = 0; a < 3; a++) for (int k = 0; k < 6; k++)
			values[a][k] = finite(values[a][k] + alpha * values[a + 3][k]);
	}

	void projectPositionAxis(int axis) { java.util.Arrays.fill(values[axis], 0); }

	/** Rank-revealing row QR gives spatial modes first, then orthogonal velocity modes. */
	Decomposition decompose(double[][] covariance) {
		double[][] remaining = new double[6][], loads = new double[6][6];
		double[] rowScale = new double[6];
		for (int a = 0; a < 6; a++) { remaining[a] = values[a].clone(); rowScale[a] = length(values[a]); }
		int rank = 0, spatialRank = 0;
		for (int block = 0; block < 2; block++) {
			boolean[] used = new boolean[3];
			for (int step = 0; step < 3; step++) {
				int pivot = -1; double norm = 0, best = 0;
				for (int axis = 0; axis < 3; axis++) if (!used[axis]) {
					int row = 3 * block + axis;
					double candidate = length(remaining[row]);
					if (rowScale[row] == 0 || finite(candidate * candidate) <= 128 * Math.ulp(Math.abs(covariance[row][row]))) continue;
					double relative = candidate / rowScale[row];
					if (relative > best) { pivot = axis; norm = candidate; best = relative; }
				}
				if (pivot < 0) break;
				used[pivot] = true;
				double[] direction = remaining[3 * block + pivot].clone();
				for (int k = 0; k < 6; k++) direction[k] /= norm;
				// Reorthogonalize every remaining row, matching the existing weighted row QR.
				for (int a = 0; a < 6; a++) for (int pass = 0; pass < 2; pass++) {
					double projection = dot(remaining[a], direction);
					loads[a][rank] = finite(loads[a][rank] + projection);
					for (int k = 0; k < 6; k++) remaining[a][k] = finite(remaining[a][k] - projection * direction[k]);
				}
				rank++;
			}
			if (block == 0) spatialRank = rank;
		}
		return new Decomposition(loads, spatialRank, rank);
	}

	private static double length(double[] row) {
		double norm = 0; for (double value : row) norm = Math.hypot(norm, value); return finite(norm);
	}
	private static double dot(double[] a, double[] b) {
		double value = 0; for (int k = 0; k < a.length; k++) value += a[k] * b[k]; return finite(value);
	}
	private static double finite(double value) {
		if (!Double.isFinite(value)) throw new CellRemapSpatialMoments.FitFailure(
				CellRemapSpatialMoments.Failure.UNREPRESENTABLE, "Liquid joint square root is unrepresentable in binary64");
		return value;
	}
	record Decomposition(double[][] loads, int spatialRank, int rank) { }
}
