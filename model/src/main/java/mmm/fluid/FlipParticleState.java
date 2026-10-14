package mmm.fluid;

import java.util.List;

/**
 * Immutable in-memory marker state for CPU handoff. It is neither a save format nor a rendering payload.
 * Marker volume in {@code positionVolume.w} remains the physical liquid-volume truth.
 */
public final class FlipParticleState {
	private static final float VOLUME_TOLERANCE = 1.0E-4F;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final float cellSize;
	private final int particleCount;
	private final float totalVolume;
	private final float centerOfMassX;
	private final int solidCellCount;
	private final float[] positionVolume;
	private final float[] particleVelocities;
	private final byte[] collisionMicroMasks;
	private final List<LiquidCellShape> liquidShapes;

	public FlipParticleState(int sizeX, int sizeY, int sizeZ, float cellSize, int particleCount,
	                         float volumeLedger, float[] positionVolume, float[] particleVelocities,
	                         byte[] collisionMicroMasks) {
		this(sizeX, sizeY, sizeZ, cellSize, particleCount, volumeLedger, positionVolume, particleVelocities, collisionMicroMasks, List.of());
	}
	public FlipParticleState(int sizeX, int sizeY, int sizeZ, float cellSize, int particleCount,
	                         float volumeLedger, float[] positionVolume, float[] particleVelocities,
	                         byte[] collisionMicroMasks, List<LiquidCellShape> liquidShapes) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || !(cellSize > 0.0F) || !Float.isFinite(cellSize)) {
			throw new IllegalArgumentException("Invalid particle-state grid");
		}
		if (particleCount < 0) throw new IllegalArgumentException("Particle-state count must not be negative");
		if (positionVolume == null || particleVelocities == null || collisionMicroMasks == null) {
			throw new IllegalArgumentException("Particle-state arrays must not be null");
		}
		if (positionVolume.length != Math.multiplyExact(particleCount, 4)) {
			throw new IllegalArgumentException("Position/volume size does not match particle count");
		}
		if (particleVelocities.length != Math.multiplyExact(particleCount, 3)) {
			throw new IllegalArgumentException("Velocity size does not match particle count");
		}
		if (collisionMicroMasks.length != Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ)) {
			throw new IllegalArgumentException("Collision mask size does not match particle-state grid");
		}
		if (!Float.isFinite(volumeLedger) || volumeLedger < 0.0F) {
			throw new IllegalArgumentException("Particle-state volume ledger is invalid");
		}

		double volume = 0.0, weightedX = 0.0;
		float maximumX = sizeX * cellSize, maximumY = sizeY * cellSize, maximumZ = sizeZ * cellSize;
		int solids = 0;
		for (byte value : collisionMicroMasks) {
			if (Byte.toUnsignedInt(value) == 0xff) solids++;
		}
		for (int particle = 0; particle < particleCount; particle++) {
			int positionBase = particle * 4, velocityBase = particle * 3;
			float x = positionVolume[positionBase], y = positionVolume[positionBase + 1];
			float z = positionVolume[positionBase + 2], amount = positionVolume[positionBase + 3];
			if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
					|| !Float.isFinite(amount) || !(amount > 0.0F)) {
				throw new IllegalArgumentException("Particle state contains non-finite or empty marker data");
			}
			if (!(x >= 0.0F && x < maximumX && y >= 0.0F && y < maximumY && z >= 0.0F && z < maximumZ)) {
				throw new IllegalArgumentException("Particle state contains a marker outside its grid");
			}
			int cellX = (int) Math.floor(x / cellSize), cellY = (int) Math.floor(y / cellSize);
			int cellZ = (int) Math.floor(z / cellSize);
			int cell = cellX + sizeX * (cellY + sizeY * cellZ);
			int localX = Math.min(1, (int) Math.floor((x / cellSize - cellX) * 2.0F));
			int localY = Math.min(1, (int) Math.floor((y / cellSize - cellY) * 2.0F));
			int localZ = Math.min(1, (int) Math.floor((z / cellSize - cellZ) * 2.0F));
			int microBit = 1 << (localX + 2 * (localY + 2 * localZ));
			if ((Byte.toUnsignedInt(collisionMicroMasks[cell]) & microBit) != 0) {
				throw new IllegalArgumentException("Particle state contains a marker inside collision geometry");
			}
			for (int axis = 0; axis < 3; axis++) if (!Float.isFinite(particleVelocities[velocityBase + axis])) {
				throw new IllegalArgumentException("Particle state contains non-finite marker velocity");
			}
			volume += amount;
			weightedX += x / cellSize * amount;
		}
		float summedVolume = (float) volume;
		float allowedError = Math.max(VOLUME_TOLERANCE, volumeLedger * 1.0E-6F);
		if (Math.abs(summedVolume - volumeLedger) > allowedError) {
			throw new IllegalArgumentException("Marker volume does not match the supplied particle ledger");
		}

		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellSize = cellSize;
		this.particleCount = particleCount;
		this.totalVolume = summedVolume;
		this.centerOfMassX = summedVolume == 0.0F ? Float.NaN : (float) (weightedX / volume);
		this.solidCellCount = solids;
		this.positionVolume = positionVolume.clone();
		this.particleVelocities = particleVelocities.clone();
		this.collisionMicroMasks = collisionMicroMasks.clone();
		this.liquidShapes = List.copyOf(liquidShapes);
		LiquidCellShape.requireMatches(this.liquidShapes, sizeX, sizeY, sizeZ, cellSize, this.positionVolume,
				cell -> Byte.toUnsignedInt(this.collisionMicroMasks[cell]));
	}

	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public float cellSize() { return cellSize; }
	public int particleCount() { return particleCount; }
	public float totalVolume() { return totalVolume; }
	public float centerOfMassX() { return centerOfMassX; }
	public int solidCellCount() { return solidCellCount; }
	public long cpuBytes() {
		long bytes = (long) positionVolume.length * Float.BYTES
				+ (long) particleVelocities.length * Float.BYTES + collisionMicroMasks.length;
		for (var shape : liquidShapes) bytes += shape.cpuBytes();
		return bytes;
	}
	public List<LiquidCellShape> liquidShapes() { return liquidShapes; }

	public void requireCompatible(int expectedX, int expectedY, int expectedZ, float expectedCellSize) {
		if (sizeX != expectedX || sizeY != expectedY || sizeZ != expectedZ
				|| Float.floatToRawIntBits(cellSize) != Float.floatToRawIntBits(expectedCellSize)) {
			throw new IllegalArgumentException("Particle state does not match the target domain");
		}
	}

	public float[] copyPositionVolume() { return positionVolume.clone(); }
	public float[] copyParticleVelocities() { return particleVelocities.clone(); }
	/** Legacy codec name; values are unsigned 2x2x2 cut-cell occupancy masks, not booleans. */
	public byte[] copySolidMask() { return collisionMicroMasks.clone(); }
	public byte[] copyCollisionMicroMasks() { return collisionMicroMasks.clone(); }

	float[] positionVolume() { return positionVolume; }
	float[] particleVelocities() { return particleVelocities; }
	byte[] collisionMicroMasks() { return collisionMicroMasks; }
}
