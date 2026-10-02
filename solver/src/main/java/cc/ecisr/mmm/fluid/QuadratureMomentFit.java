package cc.ecisr.mmm.fluid;

/** Fits a positive-weight replacement quadrature to its original centroid and covariance. */
final class QuadratureMomentFit {
	private QuadratureMomentFit() { }

	static boolean fit(double[][] original, double[] originalWeights, double[][] replacement, double[] weights, int count) {
		double[] before = moments(original, originalWeights, count), after = moments(replacement, weights, count);
		if (!(before[0] > 0) || before[0] != after[0]) return false;
		double[][] target = covariance(before), current = covariance(after);
		// The symmetric covariance transport minimizes weighted displacement among affine fits.
		double[][] currentRoot = root(current, false), inverseRoot = root(current, true);
		double[][] map = multiply(multiply(inverseRoot,
				root(multiply(multiply(currentRoot, target), currentRoot), false)), inverseRoot);
		double[][] represented = multiply(multiply(map, current), transpose(map));
		double scale = Math.max(target[0][0], Math.max(target[1][1], target[2][2]));
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
			if (Math.abs(represented[i][j] - target[i][j]) > Math.max(1e-24, scale * 1e-10)) return false;
		for (int p = 0; p < count; p++) if (weights[p] > 0) {
			double[] offset = new double[3];
			for (int i = 0; i < 3; i++) offset[i] = replacement[i][p] - after[i + 1] / after[0];
			for (int i = 0; i < 3; i++) {
				double coordinate = before[i + 1] / before[0];
				for (int j = 0; j < 3; j++) coordinate += map[i][j] * offset[j];
				replacement[i][p] = coordinate;
			}
		}
		return true;
	}

	private static double[] moments(double[][] positions, double[] weights, int count) {
		double[] result = new double[10];
		for (int p = 0; p < count; p++) {
			double w = weights[p]; result[0] += w;
			for (int i = 0; i < 3; i++) result[i + 1] += w * positions[i][p];
		}
		if (!(result[0] > 0)) return result;
		for (int p = 0; p < count; p++) {
			int index = 4;
			for (int i = 0; i < 3; i++) for (int j = i; j < 3; j++)
				result[index++] += weights[p] * (positions[i][p] - result[i + 1] / result[0])
						* (positions[j][p] - result[j + 1] / result[0]);
		}
		return result;
	}

	/** Retains the affine velocity field; its unresolved least-squares residual is resampling dissipation. */
	static void fitVelocity(double[][] original, double[] originalWeights, float[][] originalVelocity,
			double[][] replacement, double[] weights, int count, float[][] velocity) {
		double[] source = moments(original, originalWeights, count);
		double[][] inverseRoot = root(covariance(source), true);
		double[][] inverse = multiply(inverseRoot, inverseRoot);
		for (int component = 0; component < 3; component++) {
			double mean = 0;
			for (int p = 0; p < count; p++) mean += originalWeights[p] * originalVelocity[component][p];
			mean /= source[0];
			double[] cross = new double[3], gradient = new double[3];
			for (int p = 0; p < count; p++) for (int a = 0; a < 3; a++)
				cross[a] += originalWeights[p] * (originalVelocity[component][p] - mean)
						* (original[a][p] - source[a + 1] / source[0]) / source[0];
			for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) gradient[a] += cross[b] * inverse[b][a];
			for (int p = 0; p < count; p++) if (weights[p] > 0) {
				double value = mean;
				for (int a = 0; a < 3; a++) value += gradient[a] * (replacement[a][p] - source[a + 1] / source[0]);
				velocity[component][p] = (float) value;
			}
		}
	}

	private static double[][] covariance(double[] moments) {
		double[][] result = new double[3][3]; int index = 4;
		for (int i = 0; i < 3; i++) for (int j = i; j < 3; j++)
			result[i][j] = result[j][i] = moments[index++] / moments[0];
		return result;
	}

	/** Symmetric spectral square root (or its pseudoinverse), including planar and linear rules. */
	private static double[][] root(double[][] source, boolean inverse) {
		double[][] a = new double[3][3], q = new double[3][3];
		for (int i = 0; i < 3; i++) { a[i] = source[i].clone(); q[i][i] = 1; }
		for (int sweep = 0; sweep < 32; sweep++) {
			int p = 0, r = 1;
			for (int i = 0; i < 3; i++) for (int j = i + 1; j < 3; j++)
				if (Math.abs(a[i][j]) > Math.abs(a[p][r])) { p = i; r = j; }
			if (Math.abs(a[p][r]) <= 1e-15 * Math.max(1e-30, Math.abs(a[0][0]) + Math.abs(a[1][1]) + Math.abs(a[2][2]))) break;
			double angle = 0.5 * Math.atan2(2 * a[p][r], a[r][r] - a[p][p]);
			double c = Math.cos(angle), s = Math.sin(angle);
			for (int i = 0; i < 3; i++) {
				double ap = a[i][p], ar = a[i][r]; a[i][p] = c * ap - s * ar; a[i][r] = s * ap + c * ar;
				double qp = q[i][p], qr = q[i][r]; q[i][p] = c * qp - s * qr; q[i][r] = s * qp + c * qr;
			}
			for (int j = 0; j < 3; j++) {
				double ap = a[p][j], ar = a[r][j]; a[p][j] = c * ap - s * ar; a[r][j] = s * ap + c * ar;
			}
		}
		double maximum = Math.max(a[0][0], Math.max(a[1][1], a[2][2]));
		double[][] result = new double[3][3];
		for (int k = 0; k < 3; k++) {
			double value = a[k][k] > maximum * 1e-12 ? Math.sqrt(a[k][k]) : 0;
			if (inverse && value > 0) value = 1 / value;
			for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) result[i][j] += q[i][k] * value * q[j][k];
		}
		return result;
	}

	private static double[][] multiply(double[][] a, double[][] b) {
		double[][] result = new double[3][3];
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) result[i][j] += a[i][k] * b[k][j];
		return result;
	}

	private static double[][] transpose(double[][] a) {
		double[][] result = new double[3][3];
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) result[i][j] = a[j][i];
		return result;
	}
}
