package cc.ecisr.mmm.fluid;

/** Runtime marker advection and normal-only contact against binary and cut-cell solids. */
final class FlipParticleCollision {
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final float cellSize;
	private final boolean[] fullySolid;
	private final FlipCutCellGeometry cutCells;

	FlipParticleCollision(
			int sizeX,
			int sizeY,
			int sizeZ,
			float cellSize,
			boolean[] fullySolid,
			FlipCutCellGeometry cutCells
	) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellSize = cellSize;
		this.fullySolid = fullySolid;
		this.cutCells = cutCells;
	}

	void advect(
			float[] particleX,
			float[] particleY,
			float[] particleZ,
			float[] motionX,
			float[] motionY,
			float[] motionZ,
			float[] velocityX,
			float[] velocityY,
			float[] velocityZ,
			int particleCount,
			float dt
	) {
		float radius = Math.ulp(Math.max(sizeX, Math.max(sizeY, sizeZ)) * cellSize);
		float maximumX = sizeX * cellSize - radius;
		float maximumY = sizeY * cellSize - radius;
		float maximumZ = sizeZ * cellSize - radius;
		for (int particle = 0; particle < particleCount; particle++) {
			float proposedX = particleX[particle] + motionX[particle] * dt;
			float targetX = clamp(proposedX, radius, maximumX);
			if (proposedX < radius) velocityX[particle] = Math.max(0.0F, velocityX[particle]);
			else if (proposedX > maximumX) velocityX[particle] = Math.min(0.0F, velocityX[particle]);
			particleX[particle] = sweepAxis(
					particleX[particle], targetX, particleY[particle], particleZ[particle],
					0, particle, velocityX, velocityY, velocityZ
			);
			float proposedY = particleY[particle] + motionY[particle] * dt;
			float targetY = clamp(proposedY, radius, maximumY);
			if (proposedY < radius) velocityY[particle] = Math.max(0.0F, velocityY[particle]);
			else if (proposedY > maximumY) velocityY[particle] = Math.min(0.0F, velocityY[particle]);
			particleY[particle] = sweepAxis(
					particleY[particle], targetY, particleX[particle], particleZ[particle],
					1, particle, velocityX, velocityY, velocityZ
			);
			float proposedZ = particleZ[particle] + motionZ[particle] * dt;
			float targetZ = clamp(proposedZ, radius, maximumZ);
			if (proposedZ < radius) velocityZ[particle] = Math.max(0.0F, velocityZ[particle]);
			else if (proposedZ > maximumZ) velocityZ[particle] = Math.min(0.0F, velocityZ[particle]);
			particleZ[particle] = sweepAxis(
					particleZ[particle], targetZ, particleX[particle], particleY[particle],
					2, particle, velocityX, velocityY, velocityZ
			);
			constrainToFullSolidFaces(
					particle, radius, particleX, particleY, particleZ, velocityX, velocityY, velocityZ
			);
		}
	}

	boolean centerInSolid(float x, float y, float z) {
		return cutCells.pointInsideSolid(x, y, z, cellSize);
	}

	/** Axis-separated swept point collision at one quarter of a cut microcell. */
	private float sweepAxis(
			float start,
			float target,
			float otherFirst,
			float otherSecond,
			int axis,
			int particle,
			float[] velocityX,
			float[] velocityY,
			float[] velocityZ
	) {
		float distance = target - start;
		if (distance == 0.0F) return start;
		float maximumIncrement = cellSize / (FlipCutCellGeometry.MICRO_CELLS_PER_AXIS * 4.0F);
		int increments = Math.max(1, (int) Math.ceil(Math.abs(distance) / maximumIncrement));
		float accepted = start;
		for (int increment = 1; increment <= increments; increment++) {
			float candidate = start + distance * increment / increments;
			float x = axis == 0 ? candidate : otherFirst;
			float y = axis == 1 ? candidate : axis == 0 ? otherFirst : otherSecond;
			float z = axis == 2 ? candidate : otherSecond;
			if (centerInSolid(x, y, z)) {
				if (axis == 0) velocityX[particle] = 0.0F;
				else if (axis == 1) velocityY[particle] = 0.0F;
				else velocityZ[particle] = 0.0F;
				break;
			}
			accepted = candidate;
		}
		return accepted;
	}

	/** Quadrature points meet the physical wall and preserve tangential motion. */
	private void constrainToFullSolidFaces(
			int particle,
			float radius,
			float[] particleX,
			float[] particleY,
			float[] particleZ,
			float[] velocityX,
			float[] velocityY,
			float[] velocityZ
	) {
		int x = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(particleX[particle] / cellSize)));
		int y = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(particleY[particle] / cellSize)));
		int z = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(particleZ[particle] / cellSize)));
		float localX = particleX[particle] - x * cellSize;
		float localY = particleY[particle] - y * cellSize;
		float localZ = particleZ[particle] - z * cellSize;
		float contactMinimum = radius;
		float contactMaximum = cellSize - contactMinimum;

		if (x > 0 && localX < radius && fullySolid[cellIndex(x - 1, y, z)]) {
			particleX[particle] = x * cellSize + contactMinimum;
			velocityX[particle] = Math.max(0.0F, velocityX[particle]);
		}
		if (x + 1 < sizeX && localX > cellSize - radius && fullySolid[cellIndex(x + 1, y, z)]) {
			particleX[particle] = x * cellSize + contactMaximum;
			velocityX[particle] = Math.min(0.0F, velocityX[particle]);
		}
		if (y > 0 && localY < radius && fullySolid[cellIndex(x, y - 1, z)]) {
			particleY[particle] = y * cellSize + contactMinimum;
			velocityY[particle] = Math.max(0.0F, velocityY[particle]);
		}
		if (y + 1 < sizeY && localY > cellSize - radius && fullySolid[cellIndex(x, y + 1, z)]) {
			particleY[particle] = y * cellSize + contactMaximum;
			velocityY[particle] = Math.min(0.0F, velocityY[particle]);
		}
		if (z > 0 && localZ < radius && fullySolid[cellIndex(x, y, z - 1)]) {
			particleZ[particle] = z * cellSize + contactMinimum;
			velocityZ[particle] = Math.max(0.0F, velocityZ[particle]);
		}
		if (z + 1 < sizeZ && localZ > cellSize - radius && fullySolid[cellIndex(x, y, z + 1)]) {
			particleZ[particle] = z * cellSize + contactMaximum;
			velocityZ[particle] = Math.min(0.0F, velocityZ[particle]);
		}
	}

	private int cellIndex(int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static float clamp(float value, float minimum, float maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
