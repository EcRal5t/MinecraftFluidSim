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

	/** Binds immutable liquid constitutive definitions independently of granular capability. */
	public void bindMaterialDefinitions(MaterialRegistry definitions) { LiquidMaterialBinding.bind(this, definitions); }

	/**
	 * Derived per-cell liquid carrier properties for the current carrier and thermal revision.
	 *
	 * <p>Declared here so every collaborator that holds a {@link FluidDomain} reaches the one entry
	 * point instead of re-deriving density, temperature or viscosity per support point.
	 */
	LiquidCarrierProperties derivedCarrierState() { return super.derivedCarrierState(); }
}
