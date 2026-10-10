package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/**
 * Runtime-only cut-cell solid geometry for the CPU FLIP solver.
 *
 * <p>Each solver cell owns a 2x2x2 occupancy mask. Cell-open volume and the
 * shared 2x2 face apertures are numerical geometry; they never contribute to
 * the exact marker-volume ledger. Domain boundary faces are closed.</p>
 */
final class FlipCutCellGeometry {
	static final int MICRO_CELLS_PER_AXIS = 2;
	static final int FULL_MASK = 0xFF;
	private static final float MICRO_VOLUME = 1.0F / 8.0F;
	private static final float MICRO_FACE_AREA = 1.0F / 4.0F;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final byte[] occupancy;
	private final float[] openCellVolume;
	private final float[] openFaceX;
	private final float[] openFaceY;
	private final float[] openFaceZ;
	private final float[] solidWallAreaX;
	private final float[] solidWallAreaY;
	private final float[] solidWallAreaZ;
	private boolean facesDirty = true;

	FlipCutCellGeometry(int sizeX, int sizeY, int sizeZ) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		int cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		occupancy = new byte[cellCount];
		openCellVolume = new float[cellCount];
		Arrays.fill(openCellVolume, 1.0F);
		openFaceX = new float[(sizeX + 1) * sizeY * sizeZ];
		openFaceY = new float[sizeX * (sizeY + 1) * sizeZ];
		openFaceZ = new float[sizeX * sizeY * (sizeZ + 1)];
		solidWallAreaX = new float[cellCount];
		solidWallAreaY = new float[cellCount];
		solidWallAreaZ = new float[cellCount];
	}

	int mask(int index) {
		return Byte.toUnsignedInt(occupancy[index]);
	}

	boolean fullySolid(int index) {
		return mask(index) == FULL_MASK;
	}

	float openCellVolume(int index) {
		return openCellVolume[index];
	}

	int replaceMask(int index, int requestedMask) {
		if ((requestedMask & ~FULL_MASK) != 0) throw new IllegalArgumentException("Cut-cell mask must fit eight bits");
		int previous = mask(index);
		if (previous == requestedMask) return previous;
		occupancy[index] = (byte) requestedMask;
		openCellVolume[index] = 1.0F - Integer.bitCount(requestedMask) * MICRO_VOLUME;
		facesDirty = true;
		return previous;
	}

	void restoreMicroMasks(byte[] microMasks) {
		if (microMasks.length != occupancy.length) throw new IllegalArgumentException("Collision mask length changed");
		for (int index = 0; index < microMasks.length; index++) {
			int mask = Byte.toUnsignedInt(microMasks[index]);
			occupancy[index] = microMasks[index];
			openCellVolume[index] = 1.0F - Integer.bitCount(mask) * MICRO_VOLUME;
		}
		facesDirty = true;
	}

	byte[] copyMicroMasks() { return occupancy.clone(); }

	byte[] copyBinaryFullMask() {
		byte[] result = new byte[occupancy.length];
		for (int index = 0; index < result.length; index++) result[index] = fullySolid(index) ? (byte) 1 : 0;
		return result;
	}

	float xFaceOpen(int x, int y, int z) {
		ensureFaces();
		return openFaceX[xFaceIndex(x, y, z)];
	}

	float yFaceOpen(int x, int y, int z) {
		ensureFaces();
		return openFaceY[yFaceIndex(x, y, z)];
	}

	float zFaceOpen(int x, int y, int z) {
		ensureFaces();
		return openFaceZ[zFaceIndex(x, y, z)];
	}

	/** Current face apertures for a read-only numerical stage; geometry stays fixed during that stage. */
	float[] openFaceField(int axis) {
		ensureFaces();
		return switch (axis) {
			case 0 -> openFaceX; case 1 -> openFaceY; case 2 -> openFaceZ;
			default -> throw new IllegalArgumentException("Face axis must be 0, 1 or 2");
		};
	}

	/** Dimensionless solid-wall area owned by an open cell, grouped by wall-normal axis. */
	float solidWallArea(int cell, int axis) {
		ensureFaces();
		return switch (axis) {
			case 0 -> solidWallAreaX[cell];
			case 1 -> solidWallAreaY[cell];
			case 2 -> solidWallAreaZ[cell];
			default -> throw new IllegalArgumentException("Wall normal axis must be 0, 1 or 2");
		};
	}

	boolean pointInsideSolid(float x, float y, float z, float cellSize) {
		int microX = microCoordinate(x, cellSize, sizeX);
		int microY = microCoordinate(y, cellSize, sizeY);
		int microZ = microCoordinate(z, cellSize, sizeZ);
		return microSolid(microX, microY, microZ);
	}

	boolean microCellOpen(int cell, int localX, int localY, int localZ) {
		if (cell < 0 || cell >= occupancy.length || localX < 0 || localX >= MICRO_CELLS_PER_AXIS
				|| localY < 0 || localY >= MICRO_CELLS_PER_AXIS
				|| localZ < 0 || localZ >= MICRO_CELLS_PER_AXIS) {
			throw new IllegalArgumentException("Cut-cell micro coordinate is invalid");
		}
		return !occupied(mask(cell), localX, localY, localZ);
	}

	private void ensureFaces() {
		if (!facesDirty) return;
		Arrays.fill(openFaceX, 0.0F);
		Arrays.fill(openFaceY, 0.0F);
		Arrays.fill(openFaceZ, 0.0F);
		Arrays.fill(solidWallAreaX, 0.0F);
		Arrays.fill(solidWallAreaY, 0.0F);
		Arrays.fill(solidWallAreaZ, 0.0F);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			int left = mask(cellIndex(x - 1, y, z));
			int right = mask(cellIndex(x, y, z));
			int open = 0;
			for (int microZ = 0; microZ < 2; microZ++) for (int microY = 0; microY < 2; microY++) {
				if (!occupied(left, 1, microY, microZ) && !occupied(right, 0, microY, microZ)) open++;
			}
			openFaceX[xFaceIndex(x, y, z)] = open * MICRO_FACE_AREA;
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int below = mask(cellIndex(x, y - 1, z));
			int above = mask(cellIndex(x, y, z));
			int open = 0;
			for (int microZ = 0; microZ < 2; microZ++) for (int microX = 0; microX < 2; microX++) {
				if (!occupied(below, microX, 1, microZ) && !occupied(above, microX, 0, microZ)) open++;
			}
			openFaceY[yFaceIndex(x, y, z)] = open * MICRO_FACE_AREA;
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int back = mask(cellIndex(x, y, z - 1));
			int front = mask(cellIndex(x, y, z));
			int open = 0;
			for (int microY = 0; microY < 2; microY++) for (int microX = 0; microX < 2; microX++) {
				if (!occupied(back, microX, microY, 1) && !occupied(front, microX, microY, 0)) open++;
			}
			openFaceZ[zFaceIndex(x, y, z)] = open * MICRO_FACE_AREA;
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = cellIndex(x, y, z);
			for (int localZ = 0; localZ < 2; localZ++) for (int localY = 0; localY < 2; localY++) for (int localX = 0; localX < 2; localX++) {
				if (occupied(mask(cell), localX, localY, localZ)) continue;
				int microX = 2 * x + localX, microY = 2 * y + localY, microZ = 2 * z + localZ;
				if (microNeighborSolid(microX - 1, microY, microZ)) solidWallAreaX[cell] += MICRO_FACE_AREA;
				if (microNeighborSolid(microX + 1, microY, microZ)) solidWallAreaX[cell] += MICRO_FACE_AREA;
				if (microNeighborSolid(microX, microY - 1, microZ)) solidWallAreaY[cell] += MICRO_FACE_AREA;
				if (microNeighborSolid(microX, microY + 1, microZ)) solidWallAreaY[cell] += MICRO_FACE_AREA;
				if (microNeighborSolid(microX, microY, microZ - 1)) solidWallAreaZ[cell] += MICRO_FACE_AREA;
				if (microNeighborSolid(microX, microY, microZ + 1)) solidWallAreaZ[cell] += MICRO_FACE_AREA;
			}
		}
		facesDirty = false;
	}

	private boolean microNeighborSolid(int microX, int microY, int microZ) {
		if (microX < 0 || microX >= 2 * sizeX || microY < 0 || microY >= 2 * sizeY
				|| microZ < 0 || microZ >= 2 * sizeZ) return true;
		return microSolid(microX, microY, microZ);
	}

	private boolean microSolid(int microX, int microY, int microZ) {
		int cellX = microX / MICRO_CELLS_PER_AXIS;
		int cellY = microY / MICRO_CELLS_PER_AXIS;
		int cellZ = microZ / MICRO_CELLS_PER_AXIS;
		int localX = microX & 1;
		int localY = microY & 1;
		int localZ = microZ & 1;
		return occupied(mask(cellIndex(cellX, cellY, cellZ)), localX, localY, localZ);
	}

	private static int microCoordinate(float coordinate, float cellSize, int cellCount) {
		float microSize = cellSize / MICRO_CELLS_PER_AXIS;
		return Math.min(cellCount * MICRO_CELLS_PER_AXIS - 1, Math.max(0, (int) Math.floor(coordinate / microSize)));
	}

	private static boolean occupied(int mask, int x, int y, int z) {
		return (mask & 1 << (x + 2 * (y + 2 * z))) != 0;
	}

	private int cellIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private int xFaceIndex(int x, int y, int z) { return (z * sizeY + y) * (sizeX + 1) + x; }
	private int yFaceIndex(int x, int y, int z) { return (z * (sizeY + 1) + y) * sizeX + x; }
	private int zFaceIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
}
