package mmm.fluid;

/** Complete tensor rules retain every coordinate used by the reconstructed fields. */
final class CellRemapQuadrature {
	private static final int[] COEFFICIENT_AXES = {0, 1, 2, 4, 3, 5, 6, 7};
	private static final int[] PREFERRED_AXES = {1, 4, 2};
	private static final double[][][] OFFSETS = new double[8][][];
	static {
		for (int axes = 0; axes < OFFSETS.length; axes++) {
			int count = minimumCount(axes); OFFSETS[axes] = new double[count][3];
			for (int slot = 0; slot < count; slot++) {
				int bit = 0;
				for (int axis = 0; axis < 3; axis++) if ((axes & 1 << axis) != 0)
					OFFSETS[axes][slot][axis] = (slot >>> bit++ & 1) == 0 ? -.25 : .25;
			}
		}
	}
	private CellRemapQuadrature() { }

	static int requiredAxes(double[][] fields) {
		int axes = 0;
		for (double[] field : fields) for (int coefficient = 1; coefficient < field.length; coefficient++)
			if (field[coefficient] != 0) axes |= COEFFICIENT_AXES[coefficient];
		return axes;
	}

	static int minimumCount(int axes) { return 1 << Integer.bitCount(axes); }

	static int samplingAxes(int count, int requiredAxes) {
		int dimensions = Integer.numberOfTrailingZeros(count);
		if (Integer.bitCount(requiredAxes) > dimensions)
			throw new IllegalArgumentException("Tensor quadrature cannot resolve its required coordinates");
		int axes = requiredAxes;
		// Constant two/four-point fields keep their existing x / x-z rules.
		for (int preferred : PREFERRED_AXES)
			if (Integer.bitCount(axes) < dimensions) axes |= preferred;
		return axes;
	}

	static double[] offset(int slot, int axes) {
		return OFFSETS[axes][slot];
	}
}
