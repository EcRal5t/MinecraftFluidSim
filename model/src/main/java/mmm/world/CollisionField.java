package mmm.world;

/** Immutable unsigned 2x2x2 occupancy masks captured from one world generation. */
public final class CollisionField {
	private final int sizeX, sizeY, sizeZ;
	private final byte[] microMasks;

	public CollisionField(int sizeX, int sizeY, int sizeZ, byte[] microMasks) {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || microMasks == null
				|| microMasks.length != Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ)) {
			throw new IllegalArgumentException("Collision field dimensions are invalid");
		}
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.microMasks = microMasks.clone();
	}

	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public int microMask(int x, int y, int z) {
		if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
			throw new IndexOutOfBoundsException("Collision field cell is outside its capture");
		}
		return Byte.toUnsignedInt(microMasks[(z * sizeY + y) * sizeX + x]);
	}
	public byte[] copyMicroMasks() { return microMasks.clone(); }
}
