package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Incomplete Cholesky preconditioner retaining the local liquid/gas pressure connections. */
final class GranularPressurePreconditioner {
	private final FluidDomain domain;
	private final int[] rank, first, next, column;
	private final double[] coefficient;
	private final double[] factor, pivot;
	private final double[] rowFactor;
	private final int[] factorOwner;
	private int entries;

	GranularPressurePreconditioner(FluidDomain domain) {
		this.domain = domain;
		rank = new int[2 * domain.cellCount + 1]; first = new int[rank.length];
		next = new int[6 * domain.cellCount]; column = new int[next.length];
		coefficient = new double[next.length];
		factor = new double[next.length]; pivot = new double[rank.length];
		rowFactor = new double[rank.length]; factorOwner = new int[rank.length];
	}

	void prepare(int[] unknowns, int count, double[] diagonal) {
		Arrays.fill(rank, -1); Arrays.fill(first, -1);
		Arrays.fill(factorOwner, -1);
		for (int ordinal = 0; ordinal < count; ordinal++) rank[unknowns[ordinal]] = ordinal;
		entries = 0;
		int sy = domain.sizeX, sz = domain.sizeX * domain.sizeY;
		var p = domain.projection;
		for (int ordinal = 0; ordinal < p.fluidUnknownCount; ordinal++) {
			int cell = p.fluidUnknownCells[ordinal];
			add(cell, cell - 1, p.matrixNegativeX[cell]);
			add(cell, cell + 1, p.matrixPositiveX[cell]);
			add(cell, cell - sy, p.matrixNegativeY[cell]);
			add(cell, cell + sy, p.matrixPositiveY[cell]);
			add(cell, cell - sz, p.matrixNegativeZ[cell]);
			add(cell, cell + sz, p.matrixPositiveZ[cell]);
		}
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = unknowns[ordinal];
			double remaining = diagonal[row];
			for (int edge = first[row]; edge >= 0; edge = next[edge]) {
				int lower = column[edge];
				double value = coefficient[edge];
				for (int other = first[lower]; other >= 0; other = next[other]) {
					int common = column[other];
					if (factorOwner[common] == row) value -= rowFactor[common] * factor[other];
				}
				factor[edge] = value / pivot[lower];
				rowFactor[lower] = factor[edge]; factorOwner[lower] = row;
				remaining -= factor[edge] * factor[edge];
			}
			if (!(remaining > 0 && Double.isFinite(remaining))) {
				throw new FluidDomain.PressureSolveBlockedException("Incomplete pressure factor lost a positive pivot");
			}
			pivot[row] = Math.sqrt(remaining);
		}
	}

	private void add(int cell, int neighbor, double faceCoefficient) {
		if (!(faceCoefficient > 0)) return;
		boolean liquid = domain.isFluid(neighbor);
		int node = liquid ? -1 : domain.gasNodeOfAirCell(neighbor);
		if (!liquid && node <= 0) return;
		int row = liquid ? neighbor : domain.cellCount + node;
		if (row < 0 || row >= rank.length || rank[row] < 0 || liquid && rank[row] >= rank[cell]) return;
		int high = rank[row] > rank[cell] ? row : cell, low = high == row ? cell : row;
		double h = domain.config.cellSize();
		double value = -faceCoefficient / (h * h);
		int previous = -1, edge = first[high];
		while (edge >= 0 && rank[column[edge]] < rank[low]) { previous = edge; edge = next[edge]; }
		if (edge >= 0 && column[edge] == low) { coefficient[edge] += value; return; }
		column[entries] = low; coefficient[entries] = value; next[entries] = edge;
		if (previous < 0) first[high] = entries; else next[previous] = entries;
		entries++;
	}

	/** Applies L^-T L^-1 with the same factor in both triangular sweeps. */
	void apply(int[] unknowns, int count, double[] residual, double[] result) {
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = unknowns[ordinal];
			double value = residual[row];
			for (int edge = first[row]; edge >= 0; edge = next[edge]) value -= factor[edge] * result[column[edge]];
			result[row] = value / pivot[row];
		}
		for (int ordinal = count - 1; ordinal >= 0; ordinal--) {
			int row = unknowns[ordinal];
			result[row] /= pivot[row];
			for (int edge = first[row]; edge >= 0; edge = next[edge]) {
				int lower = column[edge];
				result[lower] -= factor[edge] * result[row];
			}
		}
	}
}
