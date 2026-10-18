package mmm.fluid;

import java.util.List;
import mmm.units.SignedDyadic;

/**
 * Immutable multi-material CPU handoff. Stable material definitions and exact marker mass are physical state;
 * runtime handles are reconstructed from palette order and this class is not itself a wire format.
 */
public final class FlipMaterialParticleState {
	private final FlipParticleState particles;
	private final List<FluidMaterial> materials;
	private final double totalVolume;
	private final long totalMassUnits;
	private final int[] materialPaletteIndex;


	public FlipMaterialParticleState(
			int sizeX, int sizeY, int sizeZ, float cellSize, int particleCount,
			double volumeLedger, long massLedger, List<FluidMaterial> materials,
			float[] positionVolume, float[] particleVelocities, byte[] solidMask,
			long[] particleMassUnits, int[] materialPaletteIndex
	) {
		if (!(Double.isFinite(volumeLedger) && volumeLedger >= 0.0)
				|| massLedger < 0) {
			throw new IllegalArgumentException("Material particle ledgers must be finite and non-negative");
		}
		this.materials = List.copyOf(materials);
		FluidMaterialRegistry registry = new FluidMaterialRegistry(this.materials);
		if (particleCount < 0 || positionVolume == null || positionVolume.length != Math.multiplyExact(particleCount, 4)
				|| particleMassUnits == null || materialPaletteIndex == null
				|| particleMassUnits.length != particleCount || materialPaletteIndex.length != particleCount) {
			throw new IllegalArgumentException("Material marker arrays disagree with particle count");
		}
		float[] volumes = new float[particleCount];
		for (int particle = 0; particle < particleCount; particle++) {
			float amount = positionVolume[particle * 4 + 3];
			long markerMassUnits = particleMassUnits[particle];
			if (markerMassUnits <= 0) {
				throw new IllegalArgumentException("Material marker mass must be finite and positive");
			}
			registry.material(materialPaletteIndex[particle]);
			volumes[particle] = amount;
		}
		long mass = mmm.units.Mass.sum(particleMassUnits);
		double volume = SignedDyadic.sum(volumes, particleCount).toDouble();
		if (Double.doubleToRawLongBits(volume) != Double.doubleToRawLongBits(volumeLedger)) {
			throw new IllegalArgumentException("Material marker volume ledger mismatch");
		}
		if (mass != massLedger) {
			throw new IllegalArgumentException("Material marker mass ledger mismatch");
		}
		particles = new FlipParticleState(
				sizeX, sizeY, sizeZ, cellSize, particleCount, (float) volume,
				positionVolume, particleVelocities, particleMassUnits, solidMask
		);
		totalVolume = volume;
		totalMassUnits = mass;
		this.materialPaletteIndex = materialPaletteIndex.clone();
	}

	public int sizeX() { return particles.sizeX(); }
	public int sizeY() { return particles.sizeY(); }
	public int sizeZ() { return particles.sizeZ(); }
	public float cellSize() { return particles.cellSize(); }
	public int particleCount() { return particles.particleCount(); }
	public double totalVolume() { return totalVolume; }
	public long totalMassUnits() { return totalMassUnits; }
	public double totalMass() { return mmm.units.SolverUnitScale.forCellSize(cellSize()).normalizedMass(totalMassUnits); }
	public List<FluidMaterial> materials() { return materials; }
	public float[] copyPositionVolume() { return particles.copyPositionVolume(); }
	public float[] copyParticleVelocities() { return particles.copyParticleVelocities(); }
	public byte[] copySolidMask() { return particles.copySolidMask(); }
	public long[] copyParticleMassUnits() { return particles.copyMassUnits(); }
	public int[] copyMaterialPaletteIndex() { return materialPaletteIndex.clone(); }
	public long cpuBytes() {
		return particles.cpuBytes() + (long) materialPaletteIndex.length * Integer.BYTES;
	}

	public void requireCompatible(int expectedX, int expectedY, int expectedZ, float expectedCellSize) {
		particles.requireCompatible(expectedX, expectedY, expectedZ, expectedCellSize);
	}

	FlipParticleState particles() { return particles; }
	long[] particleMassUnits() { return particles.massUnits(); }
	int[] materialPaletteIndex() { return materialPaletteIndex; }
}
