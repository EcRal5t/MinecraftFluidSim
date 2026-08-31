package cc.ecisr.mmm.fluid;

/**
 * Public bounded-domain aggregate. Implementation ownership is partitioned by the superclass chain.
 */
public final class FluidDomain extends FluidDomainGasAndGranular {
	public FluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config) {
		this(sizeX, sizeY, sizeZ, config, DEFAULT_MAX_PARTICLES);
	}

	public FluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		super(sizeX, sizeY, sizeZ, config, maximumParticleCount);
	}
}
