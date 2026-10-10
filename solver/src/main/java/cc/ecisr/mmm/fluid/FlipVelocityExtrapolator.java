package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Extends pressure-phase MAC velocities into the air stencil used by marker G2P sampling. */
final class FlipVelocityExtrapolator {
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final FlipCutCellGeometry cutCells;

	FlipVelocityExtrapolator(int sizeX, int sizeY, int sizeZ, FlipCutCellGeometry cutCells) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cutCells = cutCells;
	}

	void rebuildAndExtrapolate(boolean[] solid, int[] markerCount, boolean[] pressureFluid, boolean completePressureSpace,
			float[] velocityX, float[] velocityY, float[] velocityZ, boolean[] validX, boolean[] validY, boolean[] validZ,
			float[] scratchX, float[] scratchY, float[] scratchZ, MarkerTransferBudget dependencies) {
		seedPressureFaces(solid, markerCount, pressureFluid, validX, validY, validZ);
		extrapolateFaceField(velocityX, validX, scratchX, 0, sizeX + 1, sizeY, sizeZ, dependencies);
		extrapolateFaceField(velocityY, validY, scratchY, 1, sizeX, sizeY + 1, sizeZ, dependencies);
		extrapolateFaceField(velocityZ, validZ, scratchZ, 2, sizeX, sizeY, sizeZ + 1, dependencies);
		if (!completePressureSpace) return;
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x <= sizeX; x++)
			if (cutCells.xFaceOpen(x, y, z) == 0) validX[xFaceIndex(x, y, z)] = true;
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y <= sizeY; y++) for (int x = 0; x < sizeX; x++)
			if (cutCells.yFaceOpen(x, y, z) == 0) validY[yFaceIndex(x, y, z)] = true;
		for (int z = 0; z <= sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++)
			if (cutCells.zFaceOpen(x, y, z) == 0) validZ[zFaceIndex(x, y, z)] = true;
	}

	private void seedPressureFaces(
			boolean[] solid, int[] markerCount, boolean[] pressureFluid, boolean[] validX, boolean[] validY, boolean[] validZ
	) {
		Arrays.fill(validX, false);
		Arrays.fill(validY, false);
		Arrays.fill(validZ, false);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			int left = cellIndex(x - 1, y, z), right = cellIndex(x, y, z);
			validX[xFaceIndex(x, y, z)] = faceActive(
					solid, markerCount, pressureFluid, left, right, cutCells.xFaceOpen(x, y, z)
			);
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int below = cellIndex(x, y - 1, z), above = cellIndex(x, y, z);
			validY[yFaceIndex(x, y, z)] = faceActive(
					solid, markerCount, pressureFluid, below, above, cutCells.yFaceOpen(x, y, z)
			);
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int back = cellIndex(x, y, z - 1), front = cellIndex(x, y, z);
			validZ[zFaceIndex(x, y, z)] = faceActive(
					solid, markerCount, pressureFluid, back, front, cutCells.zFaceOpen(x, y, z)
			);
		}
	}

	private void extrapolateFaceField(
			float[] field, boolean[] valid, float[] addedThisLayer,
			int axis, int nx, int ny, int nz, MarkerTransferBudget dependencies
	) {
		int zStride = nx * ny;
		for (int layer = 0; layer < FlipTransferScheme.VELOCITY_EXTRAPOLATION_LAYERS; layer++) {
			Arrays.fill(addedThisLayer, 0.0F);
			int added = 0;
			for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
				int index = (z * ny + y) * nx + x;
				if (valid[index] || !faceOpen(axis, x, y, z)) continue;
				float sum = 0.0F;
				int count = 0;
				if (x > 0 && valid[index - 1]) { sum += field[index - 1]; count++; }
				if (x + 1 < nx && valid[index + 1]) { sum += field[index + 1]; count++; }
				if (y > 0 && valid[index - nx]) { sum += field[index - nx]; count++; }
				if (y + 1 < ny && valid[index + nx]) { sum += field[index + nx]; count++; }
				if (z > 0 && valid[index - zStride]) { sum += field[index - zStride]; count++; }
				if (z + 1 < nz && valid[index + zStride]) { sum += field[index + zStride]; count++; }
				if (count == 0) continue;
				if (dependencies != null) {
					if (x > 0 && valid[index - 1]) dependencies.recordExtrapolationInput(axis, index, index - 1);
					if (x + 1 < nx && valid[index + 1]) dependencies.recordExtrapolationInput(axis, index, index + 1);
					if (y > 0 && valid[index - nx]) dependencies.recordExtrapolationInput(axis, index, index - nx);
					if (y + 1 < ny && valid[index + nx]) dependencies.recordExtrapolationInput(axis, index, index + nx);
					if (z > 0 && valid[index - zStride]) dependencies.recordExtrapolationInput(axis, index, index - zStride);
					if (z + 1 < nz && valid[index + zStride]) dependencies.recordExtrapolationInput(axis, index, index + zStride);
				}
				field[index] = sum / count;
				addedThisLayer[index] = 1.0F;
				added++;
			}
			if (added == 0) break;
			for (int face = 0; face < valid.length; face++) {
				if (addedThisLayer[face] != 0.0F) valid[face] = true;
			}
		}
	}

	private boolean faceOpen(int axis, int x, int y, int z) {
		return switch (axis) {
			case 0 -> x > 0 && x < sizeX && cutCells.xFaceOpen(x, y, z) > 0.0F;
			case 1 -> y > 0 && y < sizeY && cutCells.yFaceOpen(x, y, z) > 0.0F;
			case 2 -> z > 0 && z < sizeZ && cutCells.zFaceOpen(x, y, z) > 0.0F;
			default -> throw new IllegalArgumentException("Unknown face axis: " + axis);
		};
	}

	private static boolean faceActive(
			boolean[] solid, int[] markerCount, boolean[] pressureFluid, int first, int second, float openFraction
	) {
		return openFraction > 0.0F
				&& ((!solid[first] && (markerCount[first] > 0 || pressureFluid[first])) || (!solid[second] && (markerCount[second] > 0 || pressureFluid[second])));
	}

	private int cellIndex(int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}

	private int xFaceIndex(int x, int y, int z) {
		return (z * sizeY + y) * (sizeX + 1) + x;
	}

	private int yFaceIndex(int x, int y, int z) {
		return (z * (sizeY + 1) + y) * sizeX + x;
	}

	private int zFaceIndex(int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}
}
