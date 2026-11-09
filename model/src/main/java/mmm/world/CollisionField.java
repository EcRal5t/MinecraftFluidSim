package mmm.world;

import mmm.spatial.SpatialGrid;

/** Immutable unsigned 2x2x2 occupancy masks captured from one world generation. */
public final class CollisionField {
	private final SpatialGrid grid;
	private final byte[] microMasks;

	public CollisionField(int sizeX, int sizeY, int sizeZ, byte[] microMasks) {
		this(SpatialGrid.rectangular(sizeX,sizeY,sizeZ),microMasks);
	}
	public CollisionField(SpatialGrid grid, byte[] microMasks) {
		if (grid == null || microMasks == null || microMasks.length != grid.cellCount()) {
			throw new IllegalArgumentException("Collision field dimensions are invalid");
		}
		this.grid = grid;
		this.microMasks = microMasks.clone();
	}

	public SpatialGrid grid() { return grid; }
	public int sizeX() { return grid.sizeX(); }
	public int sizeY() { return grid.sizeY(); }
	public int sizeZ() { return grid.sizeZ(); }
	public int microMask(int x, int y, int z) {
		int cell = grid.cell(x,y,z);
		if (cell < 0) {
			throw new IndexOutOfBoundsException("Collision field cell is outside its capture");
		}
		return Byte.toUnsignedInt(microMasks[cell]);
	}
	public byte[] copyMicroMasks() { return microMasks.clone(); }
}
