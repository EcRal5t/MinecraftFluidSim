package cc.ecisr.mmm.fluid;

/** Weighted cell-local tensor field on the eight nodes of the volume quadrature. */
final class CellPolynomialField {
	private CellPolynomialField() { }
	static final class Workspace {
		double[][] positions = new double[3][0], values = new double[4][0]; double[] weights = new double[0];
		void ensureCapacity(int count) {
			if (weights.length >= count) return;
			positions = new double[3][count]; values = new double[4][count]; weights = new double[count];
		}
	}

	static double[][] fit(double[][] positions, double[] weights, double[][] values, int count) {
		boolean[] constant = new boolean[values.length];
		boolean allConstant = true;
		for (int field = 0; field < values.length; field++) {
			constant[field] = true;
			for (int p = 1; p < count; p++) if (values[field][p] != values[field][0]) constant[field] = false;
			allConstant &= constant[field];
		}
		if (allConstant) {
			double[][] result = new double[values.length][8];
			for (int field = 0; field < values.length; field++) result[field][0] = values[field][0];
			return result;
		}
		if (count == 8) {
			int[] slots = new int[8]; java.util.Arrays.fill(slots, -1); boolean standard = true;
			for (int p = 0; p < count; p++) {
				int slot = 0;
				for (int a = 0; a < 3; a++) {
					if (Math.abs(positions[a][p]) != 0.25) standard = false;
					if (positions[a][p] > 0) slot |= 1 << a;
				}
				if (slots[slot] >= 0) standard = false; slots[slot] = p;
			}
			if (standard) {
				// Eight independent samples determine the tensor field exactly, whatever
				// their positive masses. The orthogonal transform avoids normal equations.
				double[][] result = new double[values.length][8];
				int[] masks = {0, 1, 2, 4, 3, 5, 6, 7};
				for (int field = 0; field < values.length; field++) {
					if (constant[field]) { result[field][0] = values[field][0]; continue; }
					double[] transform = new double[8];
					for (int slot = 0; slot < 8; slot++) transform[slot] = values[field][slots[slot]];
					for (int width = 1; width < 8; width *= 2) for (int block = 0; block < 8; block += 2 * width)
						for (int i = block; i < block + width; i++) {
							double a = transform[i], b = transform[i + width];
							transform[i] = a + b; transform[i + width] = b - a;
						}
					for (int coefficient = 0; coefficient < 8; coefficient++) result[field][coefficient] = transform[masks[coefficient]] / 8;
				}
				return result;
			}
		}
		double[][] matrix = new double[8][8], right = new double[values.length][8]; double total = 0;
		for (int p = 0; p < count; p++) total += weights[p];
		for (int p = 0; p < count; p++) {
			double[] basis = basis(4 * positions[0][p], 4 * positions[1][p], 4 * positions[2][p]);
			double w = weights[p] / total;
			for (int a = 0; a < 8; a++) {
				for (int b = 0; b < 8; b++) matrix[a][b] += w * basis[a] * basis[b];
				for (int field = 0; field < values.length; field++) if (!constant[field]) right[field][a] += w * basis[a] * values[field][p];
			}
		}
		double scale = 0; for (int a = 0; a < 8; a++) scale = Math.max(scale, matrix[a][a]);
		int[] pivotColumns = new int[8]; int rank = 0;
		for (int column = 0; column < 8; column++) {
			int pivot = rank; for (int row = rank; row < 8; row++) if (Math.abs(matrix[row][column]) > Math.abs(matrix[pivot][column])) pivot = row;
			if (Math.abs(matrix[pivot][column]) <= 64 * Math.ulp(scale)) continue;
			double[] swap = matrix[rank]; matrix[rank] = matrix[pivot]; matrix[pivot] = swap;
			for (double[] rhs : right) { double value = rhs[rank]; rhs[rank] = rhs[pivot]; rhs[pivot] = value; }
			double diagonal = matrix[rank][column];
			for (int c = 0; c < 8; c++) matrix[rank][c] /= diagonal;
			for (double[] rhs : right) rhs[rank] /= diagonal;
			for (int row = 0; row < 8; row++) if (row != rank) {
				double factor = matrix[row][column];
				for (int c = 0; c < 8; c++) matrix[row][c] -= factor * matrix[rank][c];
				for (double[] rhs : right) rhs[row] -= factor * rhs[rank];
			}
			pivotColumns[rank++] = column;
		}
		double[][] result = new double[values.length][8];
		for (int field = 0; field < values.length; field++) for (int row = 0; row < rank; row++) result[field][pivotColumns[row]] = right[field][row];
		for (int field = 0; field < values.length; field++) if (constant[field]) result[field][0] = values[field][0];
		return result;
	}

	static double evaluate(double[] coefficients, double x, double y, double z) {
		x *= 4; y *= 4; z *= 4;
		return coefficients[0] + coefficients[1] * x + coefficients[2] * y + coefficients[3] * z
				+ coefficients[4] * (x * y) + coefficients[5] * (x * z) + coefficients[6] * (y * z) + coefficients[7] * (x * y * z);
	}

	/** Inverse orthogonal transform on the complete standard eight-point tensor rule. */
	static double[] tensorValues(double[] coefficients) {
		double[] result = {coefficients[0], coefficients[1], coefficients[2], coefficients[4],
				coefficients[3], coefficients[5], coefficients[6], coefficients[7]};
		for (int width = 1; width < 8; width *= 2) for (int block = 0; block < 8; block += 2 * width)
			for (int i = block; i < block + width; i++) {
				double a = result[i], b = result[i + width]; result[i] = a - b; result[i + width] = a + b;
			}
		return result;
	}

	/** Evolves intrinsic temperature variation under cell-relative deformation. */
	static double[] deform(double[] coefficients, FluidDomain domain, int cell, float dt) {
		boolean constant = true;
		for (int a = 1; a < coefficients.length; a++) if (coefficients[a] != 0) constant = false;
		if (constant || dt == 0) return coefficients;
		int x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY, z = cell / (domain.sizeX * domain.sizeY);
		var sample = new DivergenceConformingVelocityField.Sample(); double[][] velocities = new double[3][8]; double[] mean = new double[3];
		float h = domain.config.cellSize();
		for (int slot = 0; slot < 8; slot++) {
			DivergenceConformingVelocityField.sample(domain.mac, domain.geometry.cutCells, domain.sizeX, domain.sizeY, domain.sizeZ, h,
					(x + 0.25F + 0.5F * (slot & 1)) * h, (y + 0.25F + 0.5F * (slot >>> 1 & 1)) * h,
					(z + 0.25F + 0.5F * (slot >>> 2 & 1)) * h, sample);
			velocities[0][slot] = sample.x; velocities[1][slot] = sample.y; velocities[2][slot] = sample.z;
			for (int a = 0; a < 3; a++) mean[a] += velocities[a][slot] / 8;
		}
		double[][] positions = new double[3][8], values = new double[1][8]; double[] weights = new double[8];
		for (int slot = 0; slot < 8; slot++) {
			double[] moved = new double[3];
			for (int a = 0; a < 3; a++) {
				positions[a][slot] = ((slot >>> a & 1) == 0 ? -0.25 : 0.25);
				moved[a] = positions[a][slot] - dt / h * (velocities[a][slot] - mean[a]);
			}
			values[0][slot] = evaluate(coefficients, moved[0], moved[1], moved[2]); weights[slot] = 1;
		}
		return fit(positions, weights, values, 8)[0];
	}

	private static double[] basis(double x, double y, double z) { return new double[] {1, x, y, z, x * y, x * z, y * z, x * y * z}; }
}
