package mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/** Balanced correction on independent internal constraints supported by disjoint velocity blocks. */
final class ConstraintBlockProjection {
	private record Factor(int[] rows, double[] inverseRoot, double[][] lower, double[] workspace) {
		void solve(double[] rhs, double[] result) {
			for (int i = 0; i < rows.length; i++) {
				double value = rhs[rows[i]] * inverseRoot[i];
				for (int j = 0; j < i; j++) value = Math.fma(-lower[i][j], workspace[j], value);
				workspace[i] = value / lower[i][i];
			}
			for (int i = rows.length - 1; i >= 0; i--) {
				double value = workspace[i];
				for (int j = i + 1; j < rows.length; j++) value = Math.fma(-lower[j][i], workspace[j], value);
				workspace[i] = value / lower[i][i]; result[rows[i]] = workspace[i] * inverseRoot[i];
			}
		}
	}
	private final BiConsumer<double[], double[]> gram, transposeGram;
	private final Factor[] factors;
	private final double[] coarse, image, fineRhs, correction;

	ConstraintBlockProjection(ConstraintGramPreconditioner preconditioner, List<int[]> groups, int markerCount,
	                          double[] inverseMass, int[] offsets, int[] indices, double[] coefficients,
	                          double[] coefficientLow, double[] diagonal) {
		var built = new ArrayList<Factor>();
		for (int[] rows : groups) {
			var factor = factor(rows, markerCount, inverseMass, offsets, indices, coefficients, coefficientLow, diagonal);
			if (factor != null) built.add(factor);
		}
		factors = built.toArray(Factor[]::new);
		int count = 0; for (var factor : factors) count = Math.addExact(count, factor.rows.length);
		int[] selected = new int[count]; int offset = 0;
		for (var factor : factors) {
			System.arraycopy(factor.rows, 0, selected, offset, factor.rows.length); offset += factor.rows.length;
		}
		var columns = preconditioner.captureColumns(selected);
		gram = columns::apply; transposeGram = columns::transpose;
		coarse = new double[diagonal.length]; image = new double[diagonal.length];
		fineRhs = new double[diagonal.length]; correction = new double[diagonal.length];
	}
	boolean active() { return factors.length > 0; }

	/** Q + (I-QH) B (I-HQ), with the symmetric cached Gram H and the local positive inverse Q. */
	void apply(double[] rhs, double[] result, BiConsumer<double[], double[]> base) {
		solve(rhs, coarse); gram.accept(coarse, image);
		for (int row = 0; row < rhs.length; row++) fineRhs[row] = rhs[row] - image[row];
		base.accept(fineRhs, result); transposeGram.accept(result, image); solve(image, correction);
		for (int row = 0; row < rhs.length; row++) result[row] += coarse[row] - correction[row];
	}
	private void solve(double[] rhs, double[] result) {
		Arrays.fill(result, 0); for (var factor : factors) factor.solve(rhs, result);
	}

	private static Factor factor(int[] selected, int markerCount, double[] inverseMass, int[] offsets,
	                             int[] indices, double[] coefficients, double[] coefficientLow, double[] diagonal) {
		int count = 0;
		for (int row : selected) if (diagonal[row] > 0) count++;
		if (count == 0) return null;
		int[] rows = new int[count]; double[] roots = new double[count]; int ordinal = 0, entries = 0;
		for (int row : selected) if (diagonal[row] > 0) {
			rows[ordinal] = row; roots[ordinal++] = 1 / Math.sqrt(diagonal[row]);
			entries = Math.addExact(entries, offsets[row + 1] - offsets[row]);
		}
		int[] columns = new int[entries]; int end = 0;
		for (int row : rows) for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) columns[end++] = indices[entry];
		Arrays.sort(columns); int size = 0;
		for (int index : columns) if (size == 0 || columns[size - 1] != index) columns[size++] = index;
		double[][] q = new double[count][size], lower = new double[count][count];
		for (int i = 0; i < count; i++) {
			int row = rows[i];
			for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) {
				int index = indices[entry], column = Arrays.binarySearch(columns, 0, size, index);
				double rootMass = Math.sqrt(inverseMass[index % markerCount]);
				q[i][column] = MarkerPressureOperator.rescaledResponse(coefficients[entry], rootMass, roots[i])
						+ MarkerPressureOperator.rescaledResponse(coefficientLow[entry], rootMass, roots[i]);
				if (!Double.isFinite(q[i][column])) return null;
			}
			for (int pass = 0; pass < 2; pass++) for (int j = 0; j < i; j++) {
				double projection = dot(q[i], q[j]); lower[i][j] += projection;
				for (int column = 0; column < size; column++) q[i][column] = Math.fma(-projection, q[j][column], q[i][column]);
			}
			double norm = 0; for (double value : q[i]) norm = Math.hypot(norm, value);
			if (!(norm > 0 && Double.isFinite(norm))) return null;
			lower[i][i] = norm; for (int column = 0; column < size; column++) q[i][column] /= norm;
		}
		return new Factor(rows, roots, lower, new double[count]);
	}
	private static double dot(double[] first, double[] second) {
		double sum = 0, error = 0;
		for (int i = 0; i < first.length; i++) {
			double adjusted = first[i] * second[i] - error, next = sum + adjusted; error = (next - sum) - adjusted; sum = next;
		}
		return sum;
	}
}
