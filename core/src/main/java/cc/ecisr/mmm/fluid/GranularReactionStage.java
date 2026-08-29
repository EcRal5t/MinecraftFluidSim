package cc.ecisr.mmm.fluid;

/** Owns the granular precipitation/dissolution position in the thermochemistry order. */
final class GranularReactionStage {
	interface Access {
		boolean enabled();
		FlipFluidDomain.PhaseTransferStats transfer(int[] dirtyCells);
	}

	private final Access access;

	GranularReactionStage(Access access) {
		this.access = access;
	}

	FlipFluidDomain.PhaseTransferStats step(int[] dirtyCells) {
		return access.enabled() ? access.transfer(dirtyCells)
				: new FlipFluidDomain.PhaseTransferStats(0, 0, 0, 0L);
	}
}
