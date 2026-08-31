package cc.ecisr.mmm.fluid;

/**
 * Reconstructs a second-order H(div) velocity inside one MAC cell. Each normal
 * face profile is shared by its two cells and has the stored face value as its
 * area average. A quadratic interior bubble cancels the tangential-profile
 * divergence, so the pointwise divergence equals the cell's discrete MAC
 * divergence everywhere in the cell.
 */
final class DivergenceConformingVelocityField {
	static final class Sample {
		float x;
		float y;
		float z;
	}

	private DivergenceConformingVelocityField() { }

	static void sample(
			MacVelocityGrid grid, int sizeX, int sizeY, int sizeZ, float cellSize,
			float physicalX, float physicalY, float physicalZ, Sample result
	) {
		float gridX = physicalX / cellSize;
		float gridY = physicalY / cellSize;
		float gridZ = physicalZ / cellSize;
		int cellX = Math.clamp((int) Math.floor(gridX), 0, sizeX - 1);
		int cellY = Math.clamp((int) Math.floor(gridY), 0, sizeY - 1);
		int cellZ = Math.clamp((int) Math.floor(gridZ), 0, sizeZ - 1);
		float localX = Math.clamp(gridX - cellX, 0.0F, 1.0F);
		float localY = Math.clamp(gridY - cellY, 0.0F, 1.0F);
		float localZ = Math.clamp(gridZ - cellZ, 0.0F, 1.0F);
		float centeredX = localX - 0.5F;
		float centeredY = localY - 0.5F;
		float centeredZ = localZ - 0.5F;

		float leftU = u(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ);
		float rightU = u(grid, sizeX, sizeY, sizeZ, cellX + 1, cellY, cellZ);
		float leftUy = uSlopeY(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ);
		float rightUy = uSlopeY(grid, sizeX, sizeY, sizeZ, cellX + 1, cellY, cellZ);
		float leftUz = uSlopeZ(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ);
		float rightUz = uSlopeZ(grid, sizeX, sizeY, sizeZ, cellX + 1, cellY, cellZ);

		float bottomV = v(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ);
		float topV = v(grid, sizeX, sizeY, sizeZ, cellX, cellY + 1, cellZ);
		float bottomVx = vSlopeX(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ);
		float topVx = vSlopeX(grid, sizeX, sizeY, sizeZ, cellX, cellY + 1, cellZ);
		float bottomVz = vSlopeZ(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ);
		float topVz = vSlopeZ(grid, sizeX, sizeY, sizeZ, cellX, cellY + 1, cellZ);

		float backW = w(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ);
		float frontW = w(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ + 1);
		float backWx = wSlopeX(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ);
		float frontWx = wSlopeX(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ + 1);
		float backWy = wSlopeY(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ);
		float frontWy = wSlopeY(grid, sizeX, sizeY, sizeZ, cellX, cellY, cellZ + 1);

		float leftProfile = leftU + leftUy * centeredY + leftUz * centeredZ;
		float rightProfile = rightU + rightUy * centeredY + rightUz * centeredZ;
		float bottomProfile = bottomV + bottomVx * centeredX + bottomVz * centeredZ;
		float topProfile = topV + topVx * centeredX + topVz * centeredZ;
		float backProfile = backW + backWx * centeredX + backWy * centeredY;
		float frontProfile = frontW + frontWx * centeredX + frontWy * centeredY;

		float xDivergenceSlope = topVx - bottomVx + frontWx - backWx;
		float yDivergenceSlope = rightUy - leftUy + frontWy - backWy;
		float zDivergenceSlope = rightUz - leftUz + topVz - bottomVz;
		result.x = leftProfile + localX * (rightProfile - leftProfile)
				- 0.5F * xDivergenceSlope * (centeredX * centeredX - 0.25F);
		result.y = bottomProfile + localY * (topProfile - bottomProfile)
				- 0.5F * yDivergenceSlope * (centeredY * centeredY - 0.25F);
		result.z = backProfile + localZ * (frontProfile - backProfile)
				- 0.5F * zDivergenceSlope * (centeredZ * centeredZ - 0.25F);
	}

	private static float uSlopeY(MacVelocityGrid grid, int nx, int ny, int nz, int x, int y, int z) {
		return 0.5F * (u(grid, nx, ny, nz, x, y + 1, z) - u(grid, nx, ny, nz, x, y - 1, z));
	}

	private static float uSlopeZ(MacVelocityGrid grid, int nx, int ny, int nz, int x, int y, int z) {
		return 0.5F * (u(grid, nx, ny, nz, x, y, z + 1) - u(grid, nx, ny, nz, x, y, z - 1));
	}

	private static float vSlopeX(MacVelocityGrid grid, int nx, int ny, int nz, int x, int y, int z) {
		return 0.5F * (v(grid, nx, ny, nz, x + 1, y, z) - v(grid, nx, ny, nz, x - 1, y, z));
	}

	private static float vSlopeZ(MacVelocityGrid grid, int nx, int ny, int nz, int x, int y, int z) {
		return 0.5F * (v(grid, nx, ny, nz, x, y, z + 1) - v(grid, nx, ny, nz, x, y, z - 1));
	}

	private static float wSlopeX(MacVelocityGrid grid, int nx, int ny, int nz, int x, int y, int z) {
		return 0.5F * (w(grid, nx, ny, nz, x + 1, y, z) - w(grid, nx, ny, nz, x - 1, y, z));
	}

	private static float wSlopeY(MacVelocityGrid grid, int nx, int ny, int nz, int x, int y, int z) {
		return 0.5F * (w(grid, nx, ny, nz, x, y + 1, z) - w(grid, nx, ny, nz, x, y - 1, z));
	}

	private static float u(MacVelocityGrid grid, int nx, int ny, int nz, int x, int y, int z) {
		y = Math.clamp(y, 0, ny - 1);
		z = Math.clamp(z, 0, nz - 1);
		if (x < 0 || x > nx) return 0.0F;
		return grid.velocityX[x + (nx + 1) * (y + ny * z)];
	}

	private static float v(MacVelocityGrid grid, int nx, int ny, int nz, int x, int y, int z) {
		x = Math.clamp(x, 0, nx - 1);
		z = Math.clamp(z, 0, nz - 1);
		if (y < 0 || y > ny) return 0.0F;
		return grid.velocityY[x + nx * (y + (ny + 1) * z)];
	}

	private static float w(MacVelocityGrid grid, int nx, int ny, int nz, int x, int y, int z) {
		x = Math.clamp(x, 0, nx - 1);
		y = Math.clamp(y, 0, ny - 1);
		if (z < 0 || z > nz) return 0.0F;
		return grid.velocityZ[x + nx * (y + ny * z)];
	}
}
