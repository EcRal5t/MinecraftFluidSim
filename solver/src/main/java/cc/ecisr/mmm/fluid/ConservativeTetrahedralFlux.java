package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;

/** Local RT kinetic energy minimized within the exact tetrahedral continuity constraints. */
final class ConservativeTetrahedralFlux {
	private final int[][] faces, sign;
	private final double[][][] mass;
	private final ConservativeFluxCoordinates coordinates;
	private final SignedDyadic[] source;
	private final double[] observedSource;
	ConservativeTetrahedralFlux(int[][] faces, int[][] sign, double[][][] mass, int[] first, int[] second,
			SignedDyadic[] fixed, SignedDyadic[] source) {
		this.faces = faces; this.sign = sign; this.mass = mass; this.source = Arrays.copyOf(source, source.length + 1);
		this.source[source.length] = SignedDyadic.ZERO; observedSource = new double[this.source.length];
		for (int c = 0; c < source.length; c++) observedSource[c] = source[c].toDouble();
		boolean[] constrained = new boolean[this.source.length]; Arrays.fill(constrained, 0, source.length, true);
		coordinates = new ConservativeFluxCoordinates(constrained.length, first, second, constrained, fixed);
	}
	SignedDyadic[] solve(double[][] target) {
		int n = coordinates.size(); var zero = new SignedDyadic[n]; Arrays.fill(zero, SignedDyadic.ZERO);
		coordinates.liftExact(zero, source);
		var particular = coordinates.lift(new double[n], observedSource, true); var gradient = multiply(particular);
		for (int t = 0; t < faces.length; t++) for (int i = 0; i < 4; i++) gradient[faces[t][i]] -= sign[t][i] * target[t][i];
		var rhs = coordinates.transpose(gradient); for (int i = 0; i < rhs.length; i++) rhs[i] = -rhs[i];
		double[][] matrix = new double[n][n];
		for (int column = 0; column < n; column++) {
			double[] basis = new double[n]; basis[column] = 1;
			var values = coordinates.transpose(multiply(coordinates.lift(basis, observedSource, false)));
			for (int row = 0; row < n; row++) matrix[row][column] = values[row];
		}
		var solution = LiquidControlVolumeVelocity.solve(matrix, rhs); var exact = new SignedDyadic[n];
		for (int i = 0; i < n; i++) exact[i] = SignedDyadic.of(solution[i]);
		return coordinates.liftExact(exact, source);
	}
	private double[] multiply(double[] value) {
		double[] result = new double[value.length];
		for (int t = 0; t < faces.length; t++) for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++)
			result[faces[t][i]] += sign[t][i] * mass[t][i][j] * sign[t][j] * value[faces[t][j]];
		return result;
	}
}
