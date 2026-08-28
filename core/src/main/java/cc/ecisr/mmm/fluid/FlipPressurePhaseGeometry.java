package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularCouplingFields;

import java.util.Arrays;

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
	private final boolean[] classified;
	private final int[] componentQueue;

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
		this.classified = new boolean[cellCount];
		this.componentQueue = new int[cellCount];
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
			pressureFluid[cell] = !solid[cell] && fraction[cell] >= 0.5F;
			normalX[cell] = normalY[cell] = normalZ[cell] = planeOffset[cell] = 0.0F;
		}
		classifySupportedSubcenterComponents(solid, cutCells, granular, pressureFluid);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = cellIndex(x, y, z);
			if (!pressureFluid[cell] || !hasAirNeighbor(x, y, z, pressureFluid, solid)) continue;
			float center = fraction[cell];
			float nx = -(sampleFraction(x + 1, y, z, center, solid)
					- sampleFraction(x - 1, y, z, center, solid));
			float ny = -(sampleFraction(x, y + 1, z, center, solid)
					- sampleFraction(x, y - 1, z, center, solid));
			float nz = -(sampleFraction(x, y, z + 1, center, solid)
					- sampleFraction(x, y, z - 1, center, solid));
			float lengthSquared = nx * nx + ny * ny + nz * nz;
			if (lengthSquared <= NORMAL_EPSILON_SQUARED) {
				nx = airDirection(x + 1, y, z, pressureFluid, solid)
						- airDirection(x - 1, y, z, pressureFluid, solid);
				ny = airDirection(x, y + 1, z, pressureFluid, solid)
						- airDirection(x, y - 1, z, pressureFluid, solid);
				nz = airDirection(x, y, z + 1, pressureFluid, solid)
						- airDirection(x, y, z - 1, pressureFluid, solid);
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
	}

	float liquidFraction(int cell) { return fraction[cell]; }

	private void classifySupportedSubcenterComponents(
			boolean[] solid, FlipCutCellGeometry cutCells, GranularCouplingFields granular,
			boolean[] pressureFluid
	) {
		Arrays.fill(classified, false);
		for (int root = 0; root < fraction.length; root++) {
			if (classified[root] || solid[root] || !(fraction[root] > 0.0F)) continue;
			int head = 0, tail = 1;
			componentQueue[0] = root;
			classified[root] = true;
			boolean containsCellCenter = false, touchesBlocked = false;
			while (head < tail) {
				int cell = componentQueue[head++];
				if (fraction[cell] >= 0.5F) containsCellCenter = true;
				int x = cell % sizeX;
				int yz = cell / sizeX;
				int y = yz % sizeY;
				int z = yz / sizeY;
				if (touchesBlockedFace(cell, x, y, z, cutCells, granular)) touchesBlocked = true;
				tail = visitNeighbor(cell, x - 1, y, z, cutCells.xFaceOpen(x, y, z),
						solid, cutCells, granular, tail);
				tail = visitNeighbor(cell, x + 1, y, z, cutCells.xFaceOpen(x + 1, y, z),
						solid, cutCells, granular, tail);
				tail = visitNeighbor(cell, x, y - 1, z, cutCells.yFaceOpen(x, y, z),
						solid, cutCells, granular, tail);
				tail = visitNeighbor(cell, x, y + 1, z, cutCells.yFaceOpen(x, y + 1, z),
						solid, cutCells, granular, tail);
				tail = visitNeighbor(cell, x, y, z - 1, cutCells.zFaceOpen(x, y, z),
						solid, cutCells, granular, tail);
				tail = visitNeighbor(cell, x, y, z + 1, cutCells.zFaceOpen(x, y, z + 1),
						solid, cutCells, granular, tail);
			}
			if (!containsCellCenter && touchesBlocked) {
				for (int entry = 0; entry < tail; entry++) pressureFluid[componentQueue[entry]] = true;
			}
		}
	}

	private int visitNeighbor(
			int cell, int x, int y, int z, float geometricFaceOpen,
			boolean[] solid, FlipCutCellGeometry cutCells, GranularCouplingFields granular, int tail
	) {
		if (!inside(x, y, z)) return tail;
		int neighbor = cellIndex(x, y, z);
		if (classified[neighbor] || solid[neighbor] || !(fraction[neighbor] > 0.0F)) return tail;
		float effectiveOpen = geometricFaceOpen;
		if (granular != null && geometricFaceOpen > 0.0F) {
			effectiveOpen *= granular.faceVoidScale(
					cell, cutCells.openCellVolume(cell), neighbor, cutCells.openCellVolume(neighbor));
		}
		if (!(effectiveOpen > 0.0F)) return tail;
		classified[neighbor] = true;
		componentQueue[tail] = neighbor;
		return tail + 1;
	}

	private boolean touchesBlockedFace(
			int cell, int x, int y, int z, FlipCutCellGeometry cutCells, GranularCouplingFields granular
	) {
		return blockedFace(cell, x - 1, y, z, cutCells.xFaceOpen(x, y, z), cutCells, granular)
				|| blockedFace(cell, x + 1, y, z, cutCells.xFaceOpen(x + 1, y, z), cutCells, granular)
				|| blockedFace(cell, x, y - 1, z, cutCells.yFaceOpen(x, y, z), cutCells, granular)
				|| blockedFace(cell, x, y + 1, z, cutCells.yFaceOpen(x, y + 1, z), cutCells, granular)
				|| blockedFace(cell, x, y, z - 1, cutCells.zFaceOpen(x, y, z), cutCells, granular)
				|| blockedFace(cell, x, y, z + 1, cutCells.zFaceOpen(x, y, z + 1), cutCells, granular);
	}

	private boolean blockedFace(
			int cell, int x, int y, int z, float geometricFaceOpen,
			FlipCutCellGeometry cutCells, GranularCouplingFields granular
	) {
		if (!inside(x, y, z)) return true;
		int neighbor = cellIndex(x, y, z);
		if (granular == null || geometricFaceOpen <= 0.0F) return geometricFaceOpen < 1.0F;
		return geometricFaceOpen * granular.faceVoidScale(
				cell, cutCells.openCellVolume(cell), neighbor, cutCells.openCellVolume(neighbor)) < 1.0F;
	}

	float interfaceTheta(int fluid, int directionX, int directionY, int directionZ) {
		float distance;
		if (fraction[fluid] >= 1.0F - FRACTION_EPSILON) distance = 0.5F;
		else {
			float outward = normalX[fluid] * directionX + normalY[fluid] * directionY
					+ normalZ[fluid] * directionZ;
			if (!(outward > 1.0E-6F)) return 1.0F;
			float centerProjection = 0.5F * (normalX[fluid] + normalY[fluid] + normalZ[fluid]);
			distance = (planeOffset[fluid] - centerProjection) / outward;
		}
		int x = fluid % sizeX;
		int yz = fluid / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		int nx = x + directionX, ny = y + directionY, nz = z + directionZ;
		if (inside(nx, ny, nz)) distance += fraction[cellIndex(nx, ny, nz)];
		return clamp(distance, FlipFreeSurfaceGeometry.MINIMUM_THETA, 1.0F);
	}

	private float sampleFraction(int x, int y, int z, float fallback, boolean[] solid) {
		if (!inside(x, y, z)) return fallback;
		int cell = cellIndex(x, y, z);
		return solid[cell] ? fallback : fraction[cell];
	}

	private int airDirection(int x, int y, int z, boolean[] pressureFluid, boolean[] solid) {
		if (!inside(x, y, z)) return 0;
		int cell = cellIndex(x, y, z);
		return !solid[cell] && !pressureFluid[cell] ? 1 : 0;
	}

	private boolean hasAirNeighbor(int x, int y, int z, boolean[] pressureFluid, boolean[] solid) {
		return airDirection(x - 1, y, z, pressureFluid, solid) != 0
				|| airDirection(x + 1, y, z, pressureFluid, solid) != 0
				|| airDirection(x, y - 1, z, pressureFluid, solid) != 0
				|| airDirection(x, y + 1, z, pressureFluid, solid) != 0
				|| airDirection(x, y, z - 1, pressureFluid, solid) != 0
				|| airDirection(x, y, z + 1, pressureFluid, solid) != 0;
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
