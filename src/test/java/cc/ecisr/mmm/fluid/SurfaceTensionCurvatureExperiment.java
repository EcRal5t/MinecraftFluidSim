package cc.ecisr.mmm.fluid;

/**
 * Test-only M4 surface-tension experiment. It builds a colour function on a uniform cell grid, smooths it once to
 * make the interface gradient well-defined, then estimates the interface normal and mean curvature and validates
 * the Laplace pressure jump in isolation. It deliberately does not run inside the active Minecraft solver yet.
 */
final class SurfaceTensionCurvatureExperiment {
	private static final int SUB_SAMPLES = 4;

	private SurfaceTensionCurvatureExperiment() { }

	/** Smooths the colour function once with a 3x3x3 box kernel so the interface gradient is non-degenerate. */
	static double[] smooth(double[] colour, int size) {
		double[] result = new double[colour.length];
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			double sum = 0.0;
			int count = 0;
			for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				int sx = x + dx, sy = y + dy, sz = z + dz;
				if (sx < 0 || sx >= size || sy < 0 || sy >= size || sz < 0 || sz >= size) continue;
				sum += colour[index(size, sx, sy, sz)];
				count++;
			}
			result[index(size, x, y, z)] = sum / count;
		}
		return result;
	}

	/** Unit interface normal at a cell centre, pointing from gas into liquid. */
	static double[] interfaceNormal(double[] colour, int size, int x, int y, int z, double cellSize) {
		double gradientX = centralDifference(colour, size, x, y, z, 1, 0, 0);
		double gradientY = centralDifference(colour, size, x, y, z, 0, 1, 0);
		double gradientZ = centralDifference(colour, size, x, y, z, 0, 0, 1);
		double length = Math.sqrt(gradientX * gradientX + gradientY * gradientY + gradientZ * gradientZ);
		double epsilon = 1.0E-6 / cellSize;
		if (length <= epsilon) return new double[] {0.0, 0.0, 1.0};
		return new double[] {gradientX / length, gradientY / length, gradientZ / length};
	}

	/**
	 * Mean curvature {@code kappa = -div(n)} at a cell centre. Laplace convention: a liquid sphere surrounded by
	 * gas has positive curvature ({@code 2/R}).
	 */
	static double meanCurvature(double[] colour, int size, int x, int y, int z, double cellSize) {
		double inverseTwoCell = 0.5 / cellSize;
		double nxPlus = interfaceNormal(colour, size, Math.min(size - 1, x + 1), y, z, cellSize)[0];
		double nxMinus = interfaceNormal(colour, size, Math.max(0, x - 1), y, z, cellSize)[0];
		double nyPlus = interfaceNormal(colour, size, x, Math.min(size - 1, y + 1), z, cellSize)[1];
		double nyMinus = interfaceNormal(colour, size, x, Math.max(0, y - 1), z, cellSize)[1];
		double nzPlus = interfaceNormal(colour, size, x, y, Math.min(size - 1, z + 1), cellSize)[2];
		double nzMinus = interfaceNormal(colour, size, x, y, Math.max(0, z - 1), cellSize)[2];
		return -((nxPlus - nxMinus) + (nyPlus - nyMinus) + (nzPlus - nzMinus)) * inverseTwoCell;
	}

	/** Colour function for a liquid sphere of the given radius centred in the grid; 1 inside liquid, 0 in gas. */
	static double[] sphereColour(int size, double radius, double cellSize) {
		double[] colour = new double[size * size * size];
		double center = 0.5 * (size - 1) * cellSize;
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			colour[index(size, x, y, z)] = fraction(x, y, z, cellSize, (sx, sy, sz) -> {
				double dx = sx - center, dy = sy - center, dz = sz - center;
				return dx * dx + dy * dy + dz * dz <= radius * radius;
			});
		}
		return colour;
	}

	/** Colour function for a liquid slab filling z below the interface; 1 inside liquid, 0 in gas. */
	static double[] slabColour(int size, double cellSize, double interfaceZ) {
		double[] colour = new double[size * size * size];
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			colour[index(size, x, y, z)] = fraction(x, y, z, cellSize, (sx, sy, sz) -> sz < interfaceZ);
		}
		return colour;
	}

	/** Curvature averaged over the interface band where the smoothed colour is between 0.25 and 0.75. */
	static double interfaceMeanCurvature(double[] colour, int size, double cellSize) {
		double[] smoothed = smooth(colour, size);
		double sum = 0.0;
		int count = 0;
		for (int z = 1; z < size - 1; z++) for (int y = 1; y < size - 1; y++) for (int x = 1; x < size - 1; x++) {
			double value = smoothed[index(size, x, y, z)];
			if (value <= 0.25 || value >= 0.75) continue;
			sum += meanCurvature(smoothed, size, x, y, z, cellSize);
			count++;
		}
		return count == 0 ? 0.0 : sum / count;
	}

	private static double fraction(int x, int y, int z, double cellSize, InsidePredicate predicate) {
		double px = x * cellSize, py = y * cellSize, pz = z * cellSize;
		double hits = 0.0;
		for (int subZ = 0; subZ < SUB_SAMPLES; subZ++) for (int subY = 0; subY < SUB_SAMPLES; subY++) {
			for (int subX = 0; subX < SUB_SAMPLES; subX++) {
				double sx = px + (subX + 0.5) * cellSize / SUB_SAMPLES;
				double sy = py + (subY + 0.5) * cellSize / SUB_SAMPLES;
				double sz = pz + (subZ + 0.5) * cellSize / SUB_SAMPLES;
				if (predicate.inside(sx, sy, sz)) hits += 1.0;
			}
		}
		return hits / (SUB_SAMPLES * SUB_SAMPLES * SUB_SAMPLES);
	}

	private static double centralDifference(double[] colour, int size, int x, int y, int z, int dx, int dy, int dz) {
		int xPlus = Math.min(size - 1, x + dx), xMinus = Math.max(0, x - dx);
		int yPlus = Math.min(size - 1, y + dy), yMinus = Math.max(0, y - dy);
		int zPlus = Math.min(size - 1, z + dz), zMinus = Math.max(0, z - dz);
		return 0.5 * (colour[index(size, xPlus, yPlus, zPlus)] - colour[index(size, xMinus, yMinus, zMinus)]);
	}

	private static int index(int size, int x, int y, int z) {
		return (z * size + y) * size + x;
	}

	@FunctionalInterface private interface InsidePredicate {
		boolean inside(double x, double y, double z);
	}
}
