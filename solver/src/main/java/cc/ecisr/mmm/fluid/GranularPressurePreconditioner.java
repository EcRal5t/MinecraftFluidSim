package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Symmetric Gauss-Seidel preconditioner retaining the local liquid/gas pressure connections. */
final class GranularPressurePreconditioner {
	private final FluidDomain domain;
	private final int[] rank, first, next, column;
	private final double[] coefficient;
	private int entries;

	GranularPressurePreconditioner(FluidDomain domain) {
		this.domain = domain;
		rank = new int[2 * domain.cellCount + 1]; first = new int[rank.length];
		next = new int[6 * domain.cellCount]; column = new int[next.length];
		coefficient = new double[next.length];
	}

	void prepare(int[] unknowns, int count) {
		Arrays.fill(rank, -1); Arrays.fill(first, -1);
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
	}

	private void add(int cell, int neighbor, float faceCoefficient) {
		if (!(faceCoefficient > 0)) return;
		boolean liquid = domain.isFluid(neighbor);
		int node = liquid ? -1 : domain.gasNodeOfAirCell(neighbor);
		if (!liquid && node <= 0) return;
		int row = liquid ? neighbor : domain.cellCount + node;
		if (row < 0 || row >= rank.length || rank[row] < 0 || liquid && rank[row] >= rank[cell]) return;
		int high = rank[row] > rank[cell] ? row : cell, low = high == row ? cell : row;
		column[entries] = low;
		double h = domain.config.cellSize();
		coefficient[entries] = -faceCoefficient / (h * h);
		next[entries] = first[high]; first[high] = entries++;
	}

	/** Applies (D+L)^-T D (D+L)^-1; the two triangular sweeps preserve symmetry. */
	void apply(int[] unknowns, int count, double[] diagonal, double[] residual, double[] result) {
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = unknowns[ordinal];
			double value = residual[row];
			for (int edge = first[row]; edge >= 0; edge = next[edge]) value -= coefficient[edge] * result[column[edge]];
			result[row] = diagonal[row] > 0 ? value / diagonal[row] : 0;
		}
		for (int ordinal = count - 1; ordinal >= 0; ordinal--) {
			int row = unknowns[ordinal];
			for (int edge = first[row]; edge >= 0; edge = next[edge]) {
				int lower = column[edge];
				if (diagonal[lower] > 0) result[lower] -= coefficient[edge] * result[row] / diagonal[lower];
			}
		}
	}
}
