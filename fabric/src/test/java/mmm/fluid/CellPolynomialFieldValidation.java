package mmm.fluid;

/** Direct interpolation checks for translated tensor rules and rank-deficient fallback. */
public final class CellPolynomialFieldValidation {
	private CellPolynomialFieldValidation() { }

	public static void main(String[] args) {
		int[] order = {5, 2, 7, 1, 0, 6, 3, 4};
		checkTensor(new double[] {-0.25, -0.25, -0.25}, new double[] {0.25, 0.25, 0.25}, order, true);
		checkTensor(new double[] {-0.12, -0.34, -0.18}, new double[] {0.38, 0.16, 0.32}, order, false);
		checkTensor(new double[] {-0.37, -0.12, -0.29}, new double[] {0.11, 0.43, 0.07}, order, false);
		checkFallback(points(new double[] {-0.25, 0, -0.25}, new double[] {0.25, 0, 0.25}, order));
		double[][] incomplete = points(new double[] {-0.25, -0.25, -0.25}, new double[] {0.25, 0.25, 0.25}, order);
		incomplete[0][0] = 0; // Three x coordinates cannot enter the complete tensor path.
		checkFallback(incomplete);
		collapsedTensorRetainsRankTruncation();
		System.out.println("Cell polynomial field validation passed");
	}

	private static void collapsedTensorRetainsRankTruncation() {
		double[][] positions = new double[3][8]; double[] weights = new double[8]; java.util.Arrays.fill(weights, 1);
		double[][] values = {{0.013, -0.047, 0.021, 0.031, -0.012, 0.049, 0.009, -0.034}};
		double mean = 0;
		for (int p = 0; p < 8; p++) {
			mean += values[0][p] / 8;
			for (int axis = 0; axis < 3; axis++) positions[axis][p] = -0.25 + (p >>> axis & 1) * Math.ulp(-0.25F);
		}
		double[] field = CellPolynomialField.fit(positions, weights, values, 8)[0];
		close(field[0], mean, "Unresolved tensor rule lost its weighted constant projection");
		for (int c = 1; c < field.length; c++) check(field[c] == 0, "Unresolved tensor rule amplified unresolvable variation");
	}

	private static void checkTensor(double[] lower, double[] upper, int[] order, boolean standard) {
		double[][] positions = points(lower, upper, order), samples = samples(positions);
		double[] equal = new double[8], unequal = {1e-30, 7, 1e20, 0.125, 1e-15, 3, 1e30, 0.5};
		java.util.Arrays.fill(equal, 1);
		double[][] fit = CellPolynomialField.fit(positions, equal, samples, 8);
		double[][] massFit = CellPolynomialField.fit(positions, unequal, samples, 8);
		for (int field = 0; field < fit.length; field++) {
			check(java.util.Arrays.equals(fit[field], massFit[field]), "Complete tensor interpolation depended on its positive masses");
			if (standard) {
				double[][] exact = {{2, 0.75, 0, -1.25, 0, 0.4375, 0, 0},
						{3, 0.5, -0.75, 1, 0.3125, -0.375, 0.4375, 0.125}, {19, 0, 0, 0, 0, 0, 0, 0}};
				check(java.util.Arrays.equals(fit[field], exact[field]), "The standard tensor rule changed its numerical coefficients");
			}
			for (int p = 0; p < 8; p++) close(CellPolynomialField.evaluate(fit[field], positions[0][p], positions[1][p], positions[2][p]),
					samples[field][p], "Tensor interpolation missed an input node");
			for (double t : new double[] {0, 0.2, 0.7, 1}) {
				double x = lower[0] + t * (upper[0] - lower[0]), y = lower[1] + (1 - t) * (upper[1] - lower[1]);
				double z = lower[2] + 0.3 * (upper[2] - lower[2]);
				close(CellPolynomialField.evaluate(fit[field], x, y, z), analytic(field, x, y, z),
						"Translated or unequal-axis tensor coordinates changed the analytic field");
			}
		}
	}

	private static void checkFallback(double[][] positions) {
		double[] weights = new double[8]; java.util.Arrays.fill(weights, 1);
		double[][] input = samples(positions), fit = CellPolynomialField.fit(positions, weights, input, 8);
		for (int field = 0; field < fit.length; field++) {
			for (double coefficient : fit[field]) check(Double.isFinite(coefficient), "Degenerate nodes produced a nonfinite coefficient");
			for (int p = 0; p < 8; p++) close(CellPolynomialField.evaluate(fit[field], positions[0][p], positions[1][p], positions[2][p]),
					input[field][p], "Rank-deficient or incomplete nodes failed the existing least-squares fallback");
		}
	}

	private static double[][] points(double[] lower, double[] upper, int[] order) {
		double[][] positions = new double[3][8];
		for (int p = 0; p < 8; p++) for (int axis = 0; axis < 3; axis++)
			positions[axis][p] = (order[p] & 1 << axis) == 0 ? lower[axis] : upper[axis];
		return positions;
	}

	private static double[][] samples(double[][] positions) {
		double[][] values = new double[3][8];
		for (int field = 0; field < values.length; field++) for (int p = 0; p < 8; p++)
			values[field][p] = analytic(field, positions[0][p], positions[1][p], positions[2][p]);
		return values;
	}

	private static double analytic(int field, double x, double y, double z) {
		return switch (field) {
			case 0 -> 2 + 3 * x - 5 * z + 7 * x * z;
			case 1 -> 3 + 2 * x - 3 * y + 4 * z + 5 * x * y - 6 * x * z + 7 * y * z + 8 * x * y * z;
			case 2 -> 19;
			default -> throw new AssertionError("Unknown analytic field");
		};
	}

	private static void close(double actual, double expected, String message) {
		check(Math.abs(actual - expected) <= 1e-11 * Math.max(1, Math.abs(expected)), message + ": " + actual + " vs " + expected);
	}
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
