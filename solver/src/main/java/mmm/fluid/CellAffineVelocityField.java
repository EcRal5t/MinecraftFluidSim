package mmm.fluid;

/** Immutable point samples of the same actual-mass cell field used by wet-face transfer. */
final class CellAffineVelocityField {
	private final CellAffineVelocity basis;
	private final double[][] coefficients;

	CellAffineVelocityField(CellAffineVelocity basis, double[][] coefficients) {
		if (basis == null || coefficients == null || coefficients.length != 3)
			throw new IllegalArgumentException("Missing cell velocity field");
		this.basis = basis;
		this.coefficients = new double[3][];
		for (int axis = 0; axis < 3; axis++) {
			if (coefficients[axis] == null || coefficients[axis].length != 4 * basis.cellCount())
				throw new IllegalArgumentException("Cell velocity field shape mismatch");
			this.coefficients[axis] = coefficients[axis].clone();
			for (double value : this.coefficients[axis]) if (!Double.isFinite(value))
				throw new IllegalArgumentException("Nonfinite cell velocity coefficient");
		}
	}

	boolean hasCell(int cell) { return basis.cellMarkerCount(cell) > 0; }
	/** Normal trace in this donor's [0,1] coordinates: intercept, first tangent slope, second tangent slope. */
	void faceTrace(int cell, int axis, boolean positive, int sizeX, int sizeY, double[] result) {
		if (result == null || result.length < 3 || axis < 0 || axis > 2 || !hasCell(cell))
			throw new IllegalArgumentException("Missing affine normal trace");
		int[] coordinate = {cell % sizeX, cell / sizeX % sizeY, cell / (sizeX * sizeY)};
		double intercept = coefficients[axis][4 * cell];
		for (int a = 0; a < 3; a++) {
			double local = coordinate[a] + (a == axis && positive ? 1 : 0) - basis.center(cell, a) / basis.cellSize();
			intercept = Math.fma(coefficients[axis][4 * cell + a + 1], local, intercept);
		}
		result[0] = intercept;
		result[1] = coefficients[axis][4 * cell + (axis + 1) % 3 + 1];
		result[2] = coefficients[axis][4 * cell + (axis + 2) % 3 + 1];
	}
	double value(int axis, int cell, double x, double y, double z) {
		return basis.value(coefficients, axis, cell, x, y, z);
	}
	FluidDomain.VelocitySample sample(int cell, double x, double y, double z) {
		double vx = value(0, cell, x, y, z), vy = value(1, cell, x, y, z), vz = value(2, cell, x, y, z);
		if (!Float.isFinite((float) vx) || !Float.isFinite((float) vy) || !Float.isFinite((float) vz))
			throw new IllegalStateException("Unrepresentable resolved liquid velocity sample");
		return new FluidDomain.VelocitySample((float) vx, (float) vy, (float) vz);
	}
}
