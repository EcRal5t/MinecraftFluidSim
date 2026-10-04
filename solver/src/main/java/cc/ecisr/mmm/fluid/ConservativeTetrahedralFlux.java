package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.Arrays;

/** Local RT kinetic energy minimized within the exact tetrahedral continuity constraints. */
final class ConservativeTetrahedralFlux {
	private final int[][] faces, sign;
	private final double[][][] mass;
	private final ConservativeFluxCoordinates coordinates;
	private final Fraction[] source;
	private final double[] observedSource;
	ConservativeTetrahedralFlux(int[][] faces, int[][] sign, double[][][] mass, int[] first, int[] second,
			Fraction[] fixed, Fraction[] source) {
		this.faces = faces; this.sign = sign; this.mass = mass; this.source = Arrays.copyOf(source, source.length + 1);
		this.source[source.length] = Fraction.ZERO; observedSource = new double[this.source.length];
		for (int c = 0; c < source.length; c++) observedSource[c] = source[c].toDouble();
		boolean[] constrained = new boolean[this.source.length]; Arrays.fill(constrained, 0, source.length, true);
		coordinates = new ConservativeFluxCoordinates(constrained.length, first, second, constrained, fixed);
	}
	Fraction[] solve(Fraction[][] target) {
		int n = coordinates.size(); var zero = new Fraction[n]; Arrays.fill(zero, Fraction.ZERO);
		coordinates.liftExact(zero, source);
		var reference = new Fraction[n];
		for (int t = 0; t < faces.length; t++) for (int i = 0; i < 4; i++) if (sign[t][i] > 0)
			for (int c = 0; c < n; c++) if (coordinates.face(c) == faces[t][i]) reference[c] = target[t][i];
		var initial = coordinates.liftExact(reference, source); double[] particular = new double[initial.length], gradient = particular.clone(), error = gradient.clone();
		for (int f = 0; f < particular.length; f++) particular[f] = initial[f].toDouble();
		for (int t = 0; t < faces.length; t++) for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
			int f = faces[t][i]; double expected = target[t][j].toDouble(), observed = particular[faces[t][j]], difference = sign[t][j] * observed - expected;
			double term = sign[t][i] * mass[t][i][j] * difference; gradient[f] += term;
			error[f] += Math.abs(mass[t][i][j]) * (Math.ulp(observed) + Math.ulp(expected) + Math.ulp(difference)) + Math.ulp(term) + Math.ulp(gradient[f]);
		}
		var rhs = coordinates.transpose(gradient); for (int i = 0; i < rhs.length; i++) rhs[i] = -rhs[i];
		var bound = coordinates.transposeError(gradient, error); boolean stationary = true;
		for (int i = 0; i < n; i++) stationary &= Math.abs(rhs[i]) <= bound[i];
		double[][] matrix = new double[n][n];
		for (int column = 0; !stationary && column < n; column++) {
			double[] basis = new double[n]; basis[column] = 1;
			var values = coordinates.transpose(multiply(coordinates.lift(basis, observedSource, false)));
			for (int row = 0; row < n; row++) matrix[row][column] = values[row];
		}
		var solution = stationary ? new double[n] : solve(matrix, rhs); var exact = new Fraction[n];
		for (int i = 0; i < n; i++) exact[i] = reference[i].add(Fraction.of(solution[i]));
		return coordinates.liftExact(exact, source);
	}
	private double[] multiply(double[] value) {
		double[] result = new double[value.length];
		for (int t = 0; t < faces.length; t++) for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++)
			result[faces[t][i]] += sign[t][i] * mass[t][i][j] * sign[t][j] * value[faces[t][j]];
		return result;
	}
	private static double[] solve(double[][] matrix, double[] rhs) {
		int count = rhs.length; double[][] factor = new double[count][]; for (int i = 0; i < count; i++) factor[i] = matrix[i].clone();
		double[] value = rhs.clone(); int[] rows = new int[count]; for (int i = 0; i < count; i++) rows[i] = i;
		for (int column = 0; column < count; column++) {
			int pivot = column; for (int row = column + 1; row < count; row++) if (Math.abs(factor[row][column]) > Math.abs(factor[pivot][column])) pivot = row;
			if (!(Math.abs(factor[pivot][column]) > 0)) throw new IllegalStateException("Singular liquid velocity reconstruction");
			var swap = factor[pivot]; factor[pivot] = factor[column]; factor[column] = swap;
			double scalar = value[pivot]; value[pivot] = value[column]; value[column] = scalar;
			int rowIndex = rows[pivot]; rows[pivot] = rows[column]; rows[column] = rowIndex;
			for (int row = column + 1; row < count; row++) {
				double scale = factor[row][column] / factor[column][column];
				for (int j = column + 1; j < count; j++) factor[row][j] -= scale * factor[column][j];
				value[row] -= scale * value[column]; factor[row][column] = scale;
			}
		}
		for (int row = count - 1; row >= 0; row--) {
			for (int j = row + 1; j < count; j++) value[row] -= factor[row][j] * value[j]; value[row] /= factor[row][row];
			if (!Double.isFinite(value[row])) throw new IllegalStateException("Liquid velocity reconstruction overflow");
		}
		double[] upperMagnitude = new double[count], factorMagnitude = new double[count]; double maximumValue = 0;
		for (int row = 0; row < count; row++) {
			maximumValue = Math.max(maximumValue, Math.abs(value[row]));
			for (int j = row; j < count; j++) upperMagnitude[row] += Math.abs(factor[row][j]);
		}
		for (int row = 0; row < count; row++) {
			double bound = upperMagnitude[row]; for (int j = 0; j < row; j++) bound += Math.abs(factor[row][j]) * upperMagnitude[j];
			factorMagnitude[rows[row]] = bound;
		}
		// Partial-pivoted LU has componentwise backward error bounded by gamma_n |L||U|.
		// Include triangular solve and residual evaluation, whose operation counts are each O(n).
		double epsilonOperations = Math.scalb(8.0 * count, -53), gamma = epsilonOperations / (1 - epsilonOperations);
		for (int row = 0; row < count; row++) {
			double actual = 0, magnitude = Math.abs(rhs[row]);
			for (int j = 0; j < count; j++) { double term = matrix[row][j] * value[j]; actual += term; magnitude += Math.abs(term); }
			if (Math.abs(actual - rhs[row]) > gamma * (magnitude + factorMagnitude[row] * maximumValue))
				throw new IllegalStateException("Liquid velocity reconstruction did not converge");
		}
		return value;
	}
}
