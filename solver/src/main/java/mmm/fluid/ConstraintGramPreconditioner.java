package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.Arrays;

/** Symmetric Gauss-Seidel on the actual pressure Gram, normalized by its positive diagonal. */
final class ConstraintGramPreconditioner {
	private final double[] inverseRoot, lower, workspace;
	private final int[] offsets, columns;

	ConstraintGramPreconditioner(int markerCount, double[] inverseMass, int[] rowOffsets, int[] indices,
	                            double[] coefficients, double[] coefficientLow, double[] diagonal) {
		int rows = diagonal.length;
		boolean expanded = false; for (double value : coefficientLow) expanded |= value != 0;
		SignedDyadic[] exact = expanded ? new SignedDyadic[coefficients.length] : null;
		if (expanded) for (int entry = 0; entry < exact.length; entry++)
			exact[entry] = SignedDyadic.of(coefficients[entry]).add(SignedDyadic.of(coefficientLow[entry]));
		inverseRoot = new double[rows]; workspace = new double[rows]; offsets = new int[rows + 1];
		for (int row = 0; row < rows; row++) {
			if (!(diagonal[row] >= 0 && Double.isFinite(diagonal[row]))) throw blocked("Invalid actual pressure diagonal at row " + row);
			inverseRoot[row] = diagonal[row] == 0 ? 0 : 1 / Math.sqrt(diagonal[row]);
		}
		int[] heads = new int[Math.multiplyExact(3, markerCount)], next = new int[indices.length], entryRows = new int[indices.length];
		Arrays.fill(heads, -1);
		for (int row = 0; row < rows; row++) for (int entry = rowOffsets[row]; entry < rowOffsets[row + 1]; entry++) {
			entryRows[entry] = row; next[entry] = heads[indices[entry]]; heads[indices[entry]] = entry;
		}
		int[] marks = new int[rows], touched = new int[rows]; Arrays.fill(marks, -1);
		double[] sums = new double[rows]; SignedDyadic[] exactSums = expanded ? new SignedDyadic[rows] : null; int count = 0;
		int[] builtColumns = new int[Math.max(1, Math.min(indices.length, 256))]; double[] builtLower = new double[builtColumns.length];
		for (int row = 0; row < rows; row++) {
			int touchedCount = 0;
			for (int entry = rowOffsets[row]; entry < rowOffsets[row + 1]; entry++) {
				int index = indices[entry]; double massInverse = inverseMass[index % markerCount];
				for (int other = heads[index]; other >= 0; other = next[other]) {
					int column = entryRows[other]; if (column >= row) continue;
					if (marks[column] != row) { marks[column] = row; sums[column] = 0; if (expanded) exactSums[column] = SignedDyadic.ZERO; touched[touchedCount++] = column; }
					if (expanded) exactSums[column] = exactSums[column].add(exact[entry].multiply(SignedDyadic.of(massInverse)).multiply(exact[other]));
					else sums[column] += MarkerPressureOperator.rescaledResponse(coefficients[entry], massInverse, coefficients[other]);
				}
			}
			Arrays.sort(touched, 0, touchedCount);
			for (int slot = 0; slot < touchedCount; slot++) {
				int column = touched[slot]; if (expanded ? exactSums[column].signum() == 0 : sums[column] == 0) continue;
				double normalized = expanded
						? exactSums[column].multiply(SignedDyadic.of(inverseRoot[row])).multiply(SignedDyadic.of(inverseRoot[column])).toDouble()
						: (sums[column] * inverseRoot[row]) * inverseRoot[column];
				if (!Double.isFinite(normalized)) throw blocked("Unrepresentable pressure preconditioner at row " + row);
				if (normalized == 0) continue;
				if (count == builtColumns.length) {
					int capacity = Math.addExact(count, Math.max(1, count));
					builtColumns = Arrays.copyOf(builtColumns, capacity); builtLower = Arrays.copyOf(builtLower, capacity);
				}
				builtColumns[count] = column; builtLower[count++] = normalized;
			}
			offsets[row + 1] = count;
		}
		columns = Arrays.copyOf(builtColumns, count); lower = Arrays.copyOf(builtLower, count);
	}

	/** D^-1/2 B^-T B^-1 D^-1/2 r, with B=I+the normalized strict lower Gram. */
	void apply(double[] rhs, double[] result) {
		if (rhs.length != inverseRoot.length || result.length != rhs.length || rhs == result)
			throw new IllegalArgumentException("Pressure preconditioner vector shape or alias mismatch");
		for (int row = 0; row < rhs.length; row++) {
			double value = rhs[row] * inverseRoot[row];
			for (int entry = offsets[row]; entry < offsets[row + 1]; entry++) value = Math.fma(-lower[entry], workspace[columns[entry]], value);
			workspace[row] = value;
		}
		for (int row = rhs.length - 1; row >= 0; row--) {
			double value = workspace[row]; result[row] = value * inverseRoot[row];
			for (int entry = offsets[row]; entry < offsets[row + 1]; entry++)
				workspace[columns[entry]] = Math.fma(-lower[entry], value, workspace[columns[entry]]);
		}
	}
	private static FluidDomain.PressureSolveBlockedException blocked(String message) { return new FluidDomain.PressureSolveBlockedException(message); }
}
