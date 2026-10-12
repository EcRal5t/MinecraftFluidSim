package mmm.gpuprobe;

/** Expands adapter-captured block collision bits into solver-cell storage. */
final class CollisionMaskExpansion {
	private static final int CELLS_PER_BLOCK = 2;

	private CollisionMaskExpansion() { }

	static byte[] expand(int blocksX, int blocksY, int blocksZ, byte[] blockMasks) {
		if (blocksX <= 0 || blocksY <= 0 || blocksZ <= 0) {
			throw new IllegalArgumentException("invalid block collision mask dimensions");
		}
		int expectedBlocks = Math.multiplyExact(Math.multiplyExact(blocksX, blocksY), blocksZ);
		if (blockMasks.length != expectedBlocks) {
			throw new IllegalArgumentException("invalid block collision mask dimensions");
		}
		int nx = blocksX * CELLS_PER_BLOCK;
		int ny = blocksY * CELLS_PER_BLOCK;
		int nz = blocksZ * CELLS_PER_BLOCK;
		byte[] result = new byte[Math.multiplyExact(Math.multiplyExact(nx, ny), nz)];
		for (int blockZ = 0; blockZ < blocksZ; blockZ++) {
			for (int blockY = 0; blockY < blocksY; blockY++) {
				for (int blockX = 0; blockX < blocksX; blockX++) {
					int blockIndex = blockX + blocksX * (blockY + blocksY * blockZ);
					int mask = Byte.toUnsignedInt(blockMasks[blockIndex]);
					for (int localZ = 0; localZ < CELLS_PER_BLOCK; localZ++) {
						for (int localY = 0; localY < CELLS_PER_BLOCK; localY++) {
							for (int localX = 0; localX < CELLS_PER_BLOCK; localX++) {
								int bit = localX + CELLS_PER_BLOCK * (localY + CELLS_PER_BLOCK * localZ);
								if ((mask & (1 << bit)) == 0) continue;
								int x = blockX * CELLS_PER_BLOCK + localX;
								int y = blockY * CELLS_PER_BLOCK + localY;
								int z = blockZ * CELLS_PER_BLOCK + localZ;
								result[x + nx * (y + ny * z)] = 1;
							}
						}
					}
				}
			}
		}
		return result;
	}

	static int countOccupied(byte[] cells) {
		int count = 0;
		for (byte cell : cells) if (cell != 0) count++;
		return count;
	}
}
