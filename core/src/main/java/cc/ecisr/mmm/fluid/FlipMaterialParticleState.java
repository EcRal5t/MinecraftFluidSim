package cc.ecisr.mmm.fluid;

import java.util.List;

/**
 * Immutable multi-material CPU handoff. Stable material definitions and exact marker mass are physical state;
 * runtime handles are reconstructed from palette order and this class is not itself a wire format.
 */
public final class FlipMaterialParticleState {
	private final FlipParticleState particles;
	private final List<FluidMaterial> materials;
	private final FluidMaterialRegistry runtimeRegistry;
	private final double totalVolume;
	private final double totalMass;
	private final double[] particleMass;
	private final int[] materialPaletteIndex;

	public FlipMaterialParticleState(
			int sizeX, int sizeY, int sizeZ, float cellSize, int particleCount,
			double volumeLedger, double massLedger, List<FluidMaterial> materials,
			float[] positionVolume, float[] particleVelocities, byte[] solidMask,
			double[] particleMass, int[] materialPaletteIndex
	) {
		this(sizeX, sizeY, sizeZ, cellSize, particleCount, volumeLedger, massLedger,
				new FluidMaterialRegistry(materials), positionVolume, particleVelocities, solidMask,
				particleMass, materialPaletteIndex);
	}

	public FlipMaterialParticleState(
			int sizeX, int sizeY, int sizeZ, float cellSize, int particleCount,
			double volumeLedger, double massLedger, FluidMaterialRegistry runtimeRegistry,
			float[] positionVolume, float[] particleVelocities, byte[] solidMask,
			double[] particleMass, int[] materialPaletteIndex
	) {
		if (runtimeRegistry == null) throw new IllegalArgumentException("Material runtime registry is missing");
		if (!(Double.isFinite(volumeLedger) && volumeLedger >= 0.0)
				|| !(Double.isFinite(massLedger) && massLedger >= 0.0)) {
			throw new IllegalArgumentException("Material particle ledgers must be finite and non-negative");
		}
		this.runtimeRegistry = runtimeRegistry;
		this.materials = runtimeRegistry.materials();
		FluidMaterialRegistry registry = runtimeRegistry;
		if (particleCount < 0 || positionVolume == null || positionVolume.length != Math.multiplyExact(particleCount, 4)
				|| particleMass == null || materialPaletteIndex == null
				|| particleMass.length != particleCount || materialPaletteIndex.length != particleCount) {
			throw new IllegalArgumentException("Material marker arrays disagree with particle count");
		}
		double volume = 0.0, mass = 0.0;
		for (int particle = 0; particle < particleCount; particle++) {
			float amount = positionVolume[particle * 4 + 3];
			double markerMass = particleMass[particle];
			if (!(Double.isFinite(markerMass) && markerMass > 0.0)) {
				throw new IllegalArgumentException("Material marker mass must be finite and positive");
			}
			registry.material(materialPaletteIndex[particle]);
			volume += amount;
			mass += markerMass;
		}
		if (Double.doubleToRawLongBits(volume) != Double.doubleToRawLongBits(volumeLedger)) {
			throw new IllegalArgumentException("Material marker volume ledger mismatch");
		}
		if (Double.doubleToRawLongBits(mass) != Double.doubleToRawLongBits(massLedger)) {
			throw new IllegalArgumentException("Material marker mass ledger mismatch");
		}
		particles = new FlipParticleState(
				sizeX, sizeY, sizeZ, cellSize, particleCount, (float) volume,
				positionVolume, particleVelocities, solidMask
		);
		totalVolume = volume;
		totalMass = mass;
		this.particleMass = particleMass.clone();
		this.materialPaletteIndex = materialPaletteIndex.clone();
	}

	public int sizeX() { return particles.sizeX(); }
	public int sizeY() { return particles.sizeY(); }
	public int sizeZ() { return particles.sizeZ(); }
	public float cellSize() { return particles.cellSize(); }
	public int particleCount() { return particles.particleCount(); }
	public double totalVolume() { return totalVolume; }
	public double totalMass() { return totalMass; }
	public List<FluidMaterial> materials() { return materials; }
	public FluidMaterialRegistry runtimeRegistry() { return runtimeRegistry; }
	public float[] copyPositionVolume() { return particles.copyPositionVolume(); }
	public float[] copyParticleVelocities() { return particles.copyParticleVelocities(); }
	public byte[] copySolidMask() { return particles.copySolidMask(); }
	public double[] copyParticleMass() { return particleMass.clone(); }
	public int[] copyMaterialPaletteIndex() { return materialPaletteIndex.clone(); }
	public long cpuBytes() {
		return particles.cpuBytes() + (long) particleMass.length * Double.BYTES
				+ (long) materialPaletteIndex.length * Integer.BYTES;
	}

	public void requireCompatible(int expectedX, int expectedY, int expectedZ, float expectedCellSize) {
		particles.requireCompatible(expectedX, expectedY, expectedZ, expectedCellSize);
	}

	FlipParticleState particles() { return particles; }
	double[] particleMass() { return particleMass; }
	int[] materialPaletteIndex() { return materialPaletteIndex; }
}
