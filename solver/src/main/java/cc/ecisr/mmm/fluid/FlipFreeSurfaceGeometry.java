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

	static float solvePlaneOffset(float normalX, float normalY, float normalZ, float fraction) {
		float clampedFraction = clamp(fraction, 0.0F, 1.0F);
		float minimum = Math.min(0.0F, normalX) + Math.min(0.0F, normalY) + Math.min(0.0F, normalZ);
		float maximum = Math.max(0.0F, normalX) + Math.max(0.0F, normalY) + Math.max(0.0F, normalZ);
		if (clampedFraction <= 0.0F) return minimum;
		if (clampedFraction >= 1.0F) return maximum;
		double a = Math.abs(normalX), b = Math.abs(normalY), c = Math.abs(normalZ);
		double tail = Math.min(clampedFraction, 1.0 - clampedFraction), root, firstInterval;
		if (a > 1.0E-10 && b > 1.0E-10 && c > 1.0E-10) {
			root = Math.cbrt(6 * a * b * c * tail); firstInterval = Math.min(a, Math.min(b, c));
		} else if (a > 1.0E-10 && b > 1.0E-10) {
			root = rectanglePlaneOffset(a, b, tail); firstInterval = Double.POSITIVE_INFINITY;
		} else if (a > 1.0E-10 && c > 1.0E-10) {
			root = rectanglePlaneOffset(a, c, tail); firstInterval = Double.POSITIVE_INFINITY;
		} else if (b > 1.0E-10 && c > 1.0E-10) {
			root = rectanglePlaneOffset(b, c, tail); firstInterval = Double.POSITIVE_INFINITY;
		} else {
			root = tail * Math.max(a, Math.max(b, c)); firstInterval = Double.POSITIVE_INFINITY;
		}
		// The first vertex interval is a simplex. Its reflected complement has the
		// same measure, so both near-empty and near-full planes have a direct inverse.
		if (root <= firstInterval) return (float) (minimum + (clampedFraction <= 0.5 ? root : a + b + c - root));
		float lower = minimum;
		float upper = maximum;
		for (int iteration = 0; iteration < 32; iteration++) {
			float middle = 0.5F * (lower + upper);
			if (middle == lower || middle == upper) break;
			if (unitCubeLiquidFraction(normalX, normalY, normalZ, middle) < clampedFraction) lower = middle;
			else upper = middle;
		}
		return 0.5F * (lower + upper);
	}

	/** The lower half of a rectangle is a triangle followed by a constant-width strip. */
	private static double rectanglePlaneOffset(double a, double b, double fraction) {
		double small = Math.min(a, b), large = Math.max(a, b);
		return fraction <= small / (2 * large) ? Math.sqrt(2 * a * b * fraction) : large * fraction + 0.5 * small;
	}

	static float unitCubeLiquidFraction(float normalX, float normalY, float normalZ, float offset) {
		double a = Math.abs(normalX), b = Math.abs(normalY), c = Math.abs(normalZ);
		double transformedOffset = offset;
		if (normalX < 0.0F) transformedOffset += a;
		if (normalY < 0.0F) transformedOffset += b;
		if (normalZ < 0.0F) transformedOffset += c;
		if (a + b + c == 0) return transformedOffset >= 0 ? 1 : 0;
		if (transformedOffset <= 0) return 0;
		if (transformedOffset >= a + b + c) return 1;
		boolean complement = transformedOffset > 0.5 * (a + b + c);
		if (complement) transformedOffset = a + b + c - transformedOffset;
		double result;
		if (a > 1.0E-10 && b > 1.0E-10 && c > 1.0E-10) {
			result = (cube(transformedOffset)
					- cube(transformedOffset - a) - cube(transformedOffset - b) - cube(transformedOffset - c)
					+ cube(transformedOffset - a - b) + cube(transformedOffset - a - c) + cube(transformedOffset - b - c)
					- cube(transformedOffset - a - b - c)) / (6.0 * a * b * c);
		} else if (a > 1.0E-10 && b > 1.0E-10) {
			result = (square(transformedOffset) - square(transformedOffset - a) - square(transformedOffset - b)
					+ square(transformedOffset - a - b)) / (2.0 * a * b);
		} else if (a > 1.0E-10 && c > 1.0E-10) {
			result = (square(transformedOffset) - square(transformedOffset - a) - square(transformedOffset - c)
					+ square(transformedOffset - a - c)) / (2.0 * a * c);
		} else if (b > 1.0E-10 && c > 1.0E-10) {
			result = (square(transformedOffset) - square(transformedOffset - b) - square(transformedOffset - c)
					+ square(transformedOffset - b - c)) / (2.0 * b * c);
		} else {
			double length = Math.max(a, Math.max(b, c));
			result = length <= 1.0E-10 ? (transformedOffset >= 0.0 ? 1.0 : 0.0) : transformedOffset / length;
		}
		return (float) clamp(complement ? 1 - result : result, 0.0, 1.0);
	}

	private static double square(double value) {
		double positive = Math.max(0.0, value);
		return positive * positive;
	}

	private static double cube(double value) {
		double positive = Math.max(0.0, value);
		return positive * positive * positive;
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
