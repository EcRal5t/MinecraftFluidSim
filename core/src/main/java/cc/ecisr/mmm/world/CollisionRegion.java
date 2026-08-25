package cc.ecisr.mmm.world;

/** Loader-neutral request for a rectangular block-aligned collision capture. */
public final class CollisionRegion {
	private final String dimensionId;
	private final int originX, originY, originZ;
	private final int blocksX, blocksY, blocksZ;
	private final int cellsPerBlock;
	private final boolean[] interiorBlocks;

	public CollisionRegion(
			String dimensionId, int originX, int originY, int originZ,
			int blocksX, int blocksY, int blocksZ, int cellsPerBlock, boolean[] interiorBlocks
	) {
		if (dimensionId == null || dimensionId.isBlank() || blocksX <= 0 || blocksY <= 0 || blocksZ <= 0
				|| cellsPerBlock <= 0 || interiorBlocks == null
				|| interiorBlocks.length != Math.multiplyExact(Math.multiplyExact(blocksX, blocksY), blocksZ)) {
			throw new IllegalArgumentException("Collision capture region is invalid");
		}
		this.dimensionId = dimensionId;
		this.originX = originX; this.originY = originY; this.originZ = originZ;
		this.blocksX = blocksX; this.blocksY = blocksY; this.blocksZ = blocksZ;
		this.cellsPerBlock = cellsPerBlock;
		this.interiorBlocks = interiorBlocks.clone();
	}

	public String dimensionId() { return dimensionId; }
	public int originX() { return originX; }
	public int originY() { return originY; }
	public int originZ() { return originZ; }
	public int blocksX() { return blocksX; }
	public int blocksY() { return blocksY; }
	public int blocksZ() { return blocksZ; }
	public int cellsPerBlock() { return cellsPerBlock; }
	public int cellsX() { return Math.multiplyExact(blocksX, cellsPerBlock); }
	public int cellsY() { return Math.multiplyExact(blocksY, cellsPerBlock); }
	public int cellsZ() { return Math.multiplyExact(blocksZ, cellsPerBlock); }
	public boolean isInteriorBlock(int x, int y, int z) {
		if (x < 0 || x >= blocksX || y < 0 || y >= blocksY || z < 0 || z >= blocksZ) return false;
		return interiorBlocks[(z * blocksY + y) * blocksX + x];
	}
}
