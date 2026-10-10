package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularCouplingFields;

import java.util.Arrays;

/**
 * Reconstructs one deterministic planar liquid interface per marker-occupied cell.
 *
 * <p>The exact marker-volume sum in a cell supplies only the plane's volume fraction;
 * it never replaces the marker ledger. A centered fraction gradient supplies the
 * orientation. Pressure classification is reconstructed separately by
 * {@link FlipPressurePhaseGeometry}. Runtime cut-cell open volume normalizes the
 * local liquid fraction; shared solid-face aperture is applied separately by the
 * pressure operator.</p>
 */
final class FlipFreeSurfaceGeometry {
	private static final float FRACTION_EPSILON = 1.0E-6F;
	private static final float NORMAL_EPSILON_SQUARED = 1.0E-10F;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final float[] exactCellVolume;
	private final float[] liquidFraction;
	private final float[] normalX;
	private final float[] normalY;
	private final float[] normalZ;
	private final float[] planeOffset;

	FlipFreeSurfaceGeometry(int sizeX, int sizeY, int sizeZ) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		int cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.exactCellVolume = new float[cellCount];
		this.liquidFraction = new float[cellCount];
		this.normalX = new float[cellCount];
		this.normalY = new float[cellCount];
		this.normalZ = new float[cellCount];
		this.planeOffset = new float[cellCount];
	}

	void clearExactVolumes() {
		Arrays.fill(exactCellVolume, 0.0F);
	}

	void addExactVolume(int cellIndex, float particleVolume) {
		exactCellVolume[cellIndex] += particleVolume;
	}

	void rebuild(boolean[] solid, int[] markerCount, FlipCutCellGeometry cutCells) {
		rebuild(solid, markerCount, cutCells, null);
	}

	void rebuild(boolean[] solid, int[] markerCount, FlipCutCellGeometry cutCells,
	             GranularCouplingFields granular) {
		for (int index = 0; index < exactCellVolume.length; index++) {
			float geometricOpen = cutCells.openCellVolume(index);
			float openVolume = granular == null ? geometricOpen
					: granular.effectiveOpenCellVolume(index, geometricOpen);
			liquidFraction[index] = solid[index] || markerCount[index] == 0
					? 0.0F : clamp(exactCellVolume[index] / Math.max(openVolume, FRACTION_EPSILON), 0.0F, 1.0F);
			normalX[index] = 0.0F;
			normalY[index] = 0.0F;
			normalZ[index] = 0.0F;
			planeOffset[index] = 0.0F;
		}

		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (solid[index] || markerCount[index] == 0 || !hasAirNeighbor(x, y, z, solid, markerCount)) continue;

			float centerFraction = liquidFraction[index];
			float nx = -(sampleFraction(x + 1, y, z, centerFraction, solid)
					- sampleFraction(x - 1, y, z, centerFraction, solid));
			float ny = -(sampleFraction(x, y + 1, z, centerFraction, solid)
					- sampleFraction(x, y - 1, z, centerFraction, solid));
			float nz = -(sampleFraction(x, y, z + 1, centerFraction, solid)
					- sampleFraction(x, y, z - 1, centerFraction, solid));
			float lengthSquared = nx * nx + ny * ny + nz * nz;
			if (lengthSquared <= NORMAL_EPSILON_SQUARED) {
				nx = airDirection(x + 1, y, z, solid, markerCount) - airDirection(x - 1, y, z, solid, markerCount);
				ny = airDirection(x, y + 1, z, solid, markerCount) - airDirection(x, y - 1, z, solid, markerCount);
				nz = airDirection(x, y, z + 1, solid, markerCount) - airDirection(x, y, z - 1, solid, markerCount);
				lengthSquared = nx * nx + ny * ny + nz * nz;
			}
			if (lengthSquared <= NORMAL_EPSILON_SQUARED) {
				nx = 0.0F;
				ny = 1.0F;
				nz = 0.0F;
			} else {
				float inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
				nx *= inverseLength;
				ny *= inverseLength;
				nz *= inverseLength;
			}

			normalX[index] = nx;
			normalY[index] = ny;
			normalZ[index] = nz;
			planeOffset[index] = solvePlaneOffset(nx, ny, nz, centerFraction);
		}
	}

	float liquidFraction(int index) {
		return liquidFraction[index];
	}

	/** PLIC liquid coverage of one cell face; directions are -x,+x,-y,+y,-z,+z. */
	float liquidFaceFraction(int index, int direction) {
		if (direction < 0 || direction >= 6) throw new IllegalArgumentException("Unknown PLIC face direction");
		float fraction = liquidFraction[index];
		if (fraction <= FRACTION_EPSILON) return 0.0F;
		if (fraction >= 1.0F - FRACTION_EPSILON) return 1.0F;
		float nx = normalX[index], ny = normalY[index], nz = normalZ[index];
		if (nx * nx + ny * ny + nz * nz <= NORMAL_EPSILON_SQUARED) return fraction;
		float offset = planeOffset[index];
		return switch (direction) {
			case 0 -> unitCubeLiquidFraction(0.0F, ny, nz, offset);
			case 1 -> unitCubeLiquidFraction(0.0F, ny, nz, offset - nx);
			case 2 -> unitCubeLiquidFraction(nx, 0.0F, nz, offset);
			case 3 -> unitCubeLiquidFraction(nx, 0.0F, nz, offset - ny);
			case 4 -> unitCubeLiquidFraction(nx, ny, 0.0F, offset);
			case 5 -> unitCubeLiquidFraction(nx, ny, 0.0F, offset - nz);
			default -> throw new IllegalStateException("Unreachable PLIC face direction");
		};
	}

	float exactCellVolume(int index) {
		return exactCellVolume[index];
	}

	private float sampleFraction(int x, int y, int z, float fallback, boolean[] solid) {
		if (!inside(x, y, z)) return fallback;
		int index = cellIndex(x, y, z);
		return solid[index] ? fallback : liquidFraction[index];
	}

	private int airDirection(int x, int y, int z, boolean[] solid, int[] markerCount) {
		if (!inside(x, y, z)) return 0;
		int index = cellIndex(x, y, z);
		return !solid[index] && markerCount[index] == 0 ? 1 : 0;
	}

	private boolean hasAirNeighbor(int x, int y, int z, boolean[] solid, int[] markerCount) {
		return isAir(x - 1, y, z, solid, markerCount) || isAir(x + 1, y, z, solid, markerCount)
				|| isAir(x, y - 1, z, solid, markerCount) || isAir(x, y + 1, z, solid, markerCount)
				|| isAir(x, y, z - 1, solid, markerCount) || isAir(x, y, z + 1, solid, markerCount);
	}

	private boolean isAir(int x, int y, int z, boolean[] solid, int[] markerCount) {
		if (!inside(x, y, z)) return false;
		int index = cellIndex(x, y, z);
		return !solid[index] && markerCount[index] == 0;
	}

	static float solvePlaneOffset(float nx, float ny, float nz, float fraction) {
		return (float) solvePlaneOffset((double) nx, ny, nz, fraction);
	}

	static double solvePlaneOffset(double normalX, double normalY, double normalZ, double fraction) {
		double clampedFraction = clamp(fraction, 0, 1);
		double minimum = Math.min(0, normalX) + Math.min(0, normalY) + Math.min(0, normalZ);
		double maximum = Math.max(0, normalX) + Math.max(0, normalY) + Math.max(0, normalZ);
		if (clampedFraction <= 0) return minimum;
		if (clampedFraction >= 1) return maximum;
		double a = Math.abs(normalX), b = Math.abs(normalY), c = Math.abs(normalZ);
		double tail = Math.min(clampedFraction, 1 - clampedFraction), root, firstInterval;
		if (a > 0 && b > 0 && c > 0) {
			root = Math.cbrt(6 * a * b * c * tail); firstInterval = Math.min(a, Math.min(b, c));
		} else if (a > 0 && b > 0) {
			root = rectanglePlaneOffset(a, b, tail); firstInterval = Double.POSITIVE_INFINITY;
		} else if (a > 0 && c > 0) {
			root = rectanglePlaneOffset(a, c, tail); firstInterval = Double.POSITIVE_INFINITY;
		} else if (b > 0 && c > 0) {
			root = rectanglePlaneOffset(b, c, tail); firstInterval = Double.POSITIVE_INFINITY;
		} else {
			root = tail * Math.max(a, Math.max(b, c)); firstInterval = Double.POSITIVE_INFINITY;
		}
		if (root <= firstInterval) return minimum + (clampedFraction <= 0.5 ? root : a + b + c - root);
		double lower = minimum, upper = maximum;
		for (int iteration = 0; iteration < 64; iteration++) {
			double middle = 0.5 * (lower + upper);
			if (middle == lower || middle == upper) break;
			if (unitCubeLiquidFraction(normalX, normalY, normalZ, middle) < clampedFraction) lower = middle;
			else upper = middle;
		}
		return 0.5 * (lower + upper);
	}

	private static double rectanglePlaneOffset(double a, double b, double fraction) {
		double small = Math.min(a, b), large = Math.max(a, b);
		return fraction <= small / (2 * large) ? Math.sqrt(2 * a * b * fraction) : large * fraction + 0.5 * small;
	}

	static float unitCubeLiquidFraction(float nx, float ny, float nz, float offset) {
		return (float) unitCubeLiquidFraction((double) nx, ny, nz, offset);
	}

	/** Scale-invariant CDF, evaluated as an integrated rectangle to avoid cancelling nearly equal powers. */
	static double unitCubeLiquidFraction(double nx, double ny, double nz, double offset) {
		double a = Math.abs(nx), b = Math.abs(ny), c = Math.abs(nz);
		double t = offset + (nx < 0 ? a : 0) + (ny < 0 ? b : 0) + (nz < 0 ? c : 0);
		double sum = a + b + c;
		if (sum == 0) return t >= 0 ? 1 : 0;
		if (t <= 0) return 0;
		if (t >= sum) return 1;
		boolean complement = t > 0.5 * sum;
		if (complement) t = sum - t;
		if (a > b) { double swap = a; a = b; b = swap; }
		if (b > c) { double swap = b; b = c; c = swap; }
		if (a > b) { double swap = a; a = b; b = swap; }
		double result;
		if (a > 0) result = (rectangleIntegral(a, b, t) - rectangleIntegral(a, b, t - c)) / c;
		else if (b > 0) result = t <= b ? t * t / (2 * b * c) : (t - 0.5 * b) / c;
		else result = t / c;
		return clamp(complement ? 1 - result : result, 0, 1);
	}

	/** Integral of the CDF of a*x+b*y, with 0<a<=b and x,y in [0,1]. */
	static double rectangleIntegral(double a, double b, double t) {
		if (t <= 0) return 0;
		if (t <= a) return t * t * t / (6 * a * b);
		if (t <= b) return (t * (t - a) / 2 + a * a / 6) / b;
		if (t < a + b) { double remaining = a + b - t; return t - 0.5 * (a + b) + remaining * remaining * remaining / (6 * a * b); }
		return t - 0.5 * (a + b);
	}

	private boolean inside(int x, int y, int z) {
		return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
	}

	private int cellIndex(int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static float clamp(float value, float minimum, float maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
