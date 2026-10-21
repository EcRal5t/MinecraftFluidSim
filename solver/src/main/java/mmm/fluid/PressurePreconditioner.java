package mmm.fluid;

import java.util.Arrays;

/** IC(0) on the free principal submatrix of the assembled no-drag pressure approximation. */
final class PressurePreconditioner {
	private PressureSparseMatrix matrix;
	private double[] factor = new double[0];
	private double[] pivot = new double[0];
	private double[] rowFactor = new double[0];
	private int[] factorOwner = new int[0];
	private boolean[] free = new boolean[0];

	void prepare(PressureSparseMatrix matrix, int[] unknowns, int count) {
		this.matrix = matrix;
		if (free.length < matrix.size()) {
			free = new boolean[matrix.size()];
			pivot = new double[free.length];
			rowFactor = new double[free.length];
			factorOwner = new int[free.length];
		}
		if (factor.length < matrix.nonzeroCount()) factor = new double[matrix.nonzeroCount()];
		Arrays.fill(free, false);
		Arrays.fill(factorOwner, -1);
		for (int ordinal = 0; ordinal < count; ordinal++) free[unknowns[ordinal]] = true;
		// Graph preparation emits liquid cells, then gas nodes, in increasing flat
		// row order. The active-set subset preserves that triangular ordering.
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = unknowns[ordinal];
			double diagonal = matrix.diagonal(row);
			double remaining = diagonal;
			for (int edge = matrix.start[row]; edge < matrix.start[row + 1] && matrix.column[edge] < row; edge++) {
				int lower = matrix.column[edge];
				factor[edge] = 0;
				if (!free[lower]) continue;
				double value = matrix.value[edge];
				for (int other = matrix.start[lower]; other < matrix.start[lower + 1] && matrix.column[other] < lower; other++) {
					int common = matrix.column[other];
					if (factorOwner[common] == row) value -= rowFactor[common] * factor[other];
				}
				factor[edge] = value / pivot[lower];
				rowFactor[lower] = factor[edge];
				factorOwner[lower] = row;
				remaining -= factor[edge] * factor[edge];
			}
			// Dropped fill can spoil an incomplete pivot even when A is positive.
			// Diagonal compensation keeps the preconditioner positive; A is unchanged.
			pivot[row] = Math.sqrt(Math.max(remaining, .01 * diagonal));
		}
	}

	void apply(int[] unknowns, int count, double[] residual, double[] result) {
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = unknowns[ordinal];
			double value = residual[row];
			for (int edge = matrix.start[row]; edge < matrix.start[row + 1] && matrix.column[edge] < row; edge++) {
				if (free[matrix.column[edge]]) value -= factor[edge] * result[matrix.column[edge]];
			}
			result[row] = value / pivot[row];
		}
		for (int ordinal = count - 1; ordinal >= 0; ordinal--) {
			int row = unknowns[ordinal];
			result[row] /= pivot[row];
			for (int edge = matrix.start[row]; edge < matrix.start[row + 1] && matrix.column[edge] < row; edge++) {
				if (free[matrix.column[edge]]) result[matrix.column[edge]] -= factor[edge] * result[row];
			}
		}
	}
}
