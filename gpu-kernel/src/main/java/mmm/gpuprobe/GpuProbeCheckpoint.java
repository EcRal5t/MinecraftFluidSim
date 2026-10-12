package mmm.gpuprobe;

import mmm.fluid.FluidDomain;
import mmm.fluid.FlipParticleState;
import mmm.fluid.FluidSolverConfig;

/** Immutable, in-memory CPU recovery state for one fully completed GPU generation. */
final class GpuProbeCheckpoint {
	private final long generation;
	private final FlipParticleState particles;

	GpuProbeCheckpoint(long generation, int nx, int ny, int nz, float cellSize, int particleCount,
	                   float volumeLedger, float[] positionVolume, float[] particleVelocities, byte[] solidMask) {
		if (generation < 0L) throw new IllegalArgumentException("Checkpoint generation must not be negative");
		byte[] collisionMicroMasks = new byte[solidMask.length];
		for (int cell = 0; cell < solidMask.length; cell++) {
			int binarySolid = Byte.toUnsignedInt(solidMask[cell]);
			if (binarySolid != 0 && binarySolid != 1) {
				throw new IllegalArgumentException("GPU checkpoint solid mask is not binary");
			}
			collisionMicroMasks[cell] = binarySolid == 0 ? 0 : (byte) 0xff;
		}
		this.generation = generation;
		this.particles = new FlipParticleState(nx, ny, nz, cellSize, particleCount,
				volumeLedger, positionVolume, particleVelocities, collisionMicroMasks);
	}

	FluidDomain restore(FluidSolverConfig config) {
		FluidDomain domain = new FluidDomain(particles.sizeX(), particles.sizeY(), particles.sizeZ(), config);
		domain.restoreParticleState(particles);
		if (domain.particleCount() != particles.particleCount()
				|| Math.abs(domain.totalVolume() - particles.totalVolume()) > 1.0E-4F) {
			throw new IllegalStateException("CPU recovery did not preserve checkpoint particle truth");
		}
		return domain;
	}

	long generation() { return generation; }
	int particleCount() { return particles.particleCount(); }
	float volumeCells() { return particles.totalVolume(); }
	float centerOfMassX() { return particles.centerOfMassX(); }
	int solidCellCount() { return particles.solidCellCount(); }
	long cpuBytes() { return particles.cpuBytes(); }
	long gpuReadbackBytes() {
		long cells = Math.multiplyExact(Math.multiplyExact((long) particles.sizeX(), particles.sizeY()), particles.sizeZ());
		return (long) particles.particleCount() * 8L * Float.BYTES + cells * Integer.BYTES;
	}
}
