package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularCouplingFields;

/** Pressure-only PLIC reconstructed from conservative marker-footprint volume. */
final class FlipPressurePhaseGeometry {
	private static final float FRACTION_EPSILON = 1.0E-6F;
	private static final float NORMAL_EPSILON_SQUARED = 1.0E-10F;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final float[] fraction;
	private final float[] normalX;
	private final float[] normalY;
	private final float[] normalZ;
	private final float[] planeOffset;

	FlipPressurePhaseGeometry(int sizeX, int sizeY, int sizeZ) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) {
			throw new IllegalArgumentException("Pressure phase grid is too small");
		}
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		int cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.fraction = new float[cellCount];
		this.normalX = new float[cellCount];
		this.normalY = new float[cellCount];
		this.normalZ = new float[cellCount];
		this.planeOffset = new float[cellCount];
	}

	void rebuild(
			boolean[] solid, double[] reconstructedVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, boolean[] pressureFluid
	) {
		if (solid.length != fraction.length || reconstructedVolume.length != fraction.length
				|| pressureFluid.length != fraction.length) {
			throw new IllegalArgumentException("Pressure phase arrays disagree with the cell count");
		}
		for (int cell = 0; cell < fraction.length; cell++) {
			float geometricOpen = cutCells.openCellVolume(cell);
			float openVolume = granular == null ? geometricOpen
					: granular.effectiveOpenCellVolume(cell, geometricOpen);
			fraction[cell] = solid[cell] || openVolume <= 0.0F ? 0.0F
					: (float) clamp(reconstructedVolume[cell] / openVolume, 0.0, 1.0);
			pressureFluid[cell] = false;
			normalX[cell] = normalY[cell] = normalZ[cell] = planeOffset[cell] = 0.0F;
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = cellIndex(x, y, z);
			if (solid[cell] || !(fraction[cell] > 0.0F) || fraction[cell] >= 1.0F - FRACTION_EPSILON) continue;
			float center = fraction[cell];
			float nx = -(sampleFraction(x + 1, y, z, center, solid)
					- sampleFraction(x - 1, y, z, center, solid));
			float ny = -(sampleFraction(x, y + 1, z, center, solid)
					- sampleFraction(x, y - 1, z, center, solid));
			float nz = -(sampleFraction(x, y, z + 1, center, solid)
					- sampleFraction(x, y, z - 1, center, solid));
			float lengthSquared = nx * nx + ny * ny + nz * nz;
			if (lengthSquared <= NORMAL_EPSILON_SQUARED) {
				nx = airDirection(x + 1, y, z, solid) - airDirection(x - 1, y, z, solid);
				ny = airDirection(x, y + 1, z, solid) - airDirection(x, y - 1, z, solid);
				nz = airDirection(x, y, z + 1, solid) - airDirection(x, y, z - 1, solid);
				lengthSquared = nx * nx + ny * ny + nz * nz;
			}
			if (lengthSquared <= NORMAL_EPSILON_SQUARED) ny = 1.0F;
			else {
				float inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
				nx *= inverseLength;
				ny *= inverseLength;
				nz *= inverseLength;
			}
			normalX[cell] = nx;
			normalY[cell] = ny;
			normalZ[cell] = nz;
			planeOffset[cell] = FlipFreeSurfaceGeometry.solvePlaneOffset(nx, ny, nz, center);
		}
		for (int cell = 0; cell < fraction.length; cell++) {
			if (solid[cell] || !(fraction[cell] > 0.5F)) continue;
			if (fraction[cell] >= 1.0F - FRACTION_EPSILON) pressureFluid[cell] = true;
			else pressureFluid[cell] = planeOffset[cell]
					> 0.5F * (normalX[cell] + normalY[cell] + normalZ[cell]);
		}
	}

	float liquidFraction(int cell) { return fraction[cell]; }

	float interfaceTheta(int fluid, int directionX, int directionY, int directionZ) {
		float distance;
		if (fraction[fluid] >= 1.0F - FRACTION_EPSILON) distance = 0.5F;
		else {
			float outward = normalX[fluid] * directionX + normalY[fluid] * directionY
					+ normalZ[fluid] * directionZ;
			if (!(outward > 0.0F)) return 1.0F;
			float centerProjection = 0.5F * (normalX[fluid] + normalY[fluid] + normalZ[fluid]);
			distance = (planeOffset[fluid] - centerProjection) / outward;
		}
		int x = fluid % sizeX;
		int yz = fluid / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		int nx = x + directionX, ny = y + directionY, nz = z + directionZ;
		if (inside(nx, ny, nz)) distance += fraction[cellIndex(nx, ny, nz)];
		if (!(distance > 0.0F)) {
			throw new IllegalStateException("Pressure-cell centre does not lie inside its reconstructed liquid phase");
		}
		return Math.min(distance, 1.0F);
	}

	private float sampleFraction(int x, int y, int z, float fallback, boolean[] solid) {
		if (!inside(x, y, z)) return fallback;
		int cell = cellIndex(x, y, z);
		return solid[cell] ? fallback : fraction[cell];
	}

	private int airDirection(int x, int y, int z, boolean[] solid) {
		if (!inside(x, y, z)) return 0;
		int cell = cellIndex(x, y, z);
		return !solid[cell] && fraction[cell] <= 0.5F ? 1 : 0;
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
