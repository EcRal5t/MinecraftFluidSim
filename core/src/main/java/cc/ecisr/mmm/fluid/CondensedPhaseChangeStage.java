package cc.ecisr.mmm.fluid;

/** Owns the exact-batch coherent-solid/liquid phase pass in the thermal axis. */
final class CondensedPhaseChangeStage {
	interface Access {
		FlipFluidDomain.ThermalPhaseStats step(int maximumOperations, boolean phaseGridPrepared);
	}

	private final Access access;

	CondensedPhaseChangeStage(Access access) {
		this.access = access;
	}

	FlipFluidDomain.ThermalPhaseStats step(int maximumOperations, boolean phaseGridPrepared) {
		return access.step(maximumOperations, phaseGridPrepared);
	}
}
