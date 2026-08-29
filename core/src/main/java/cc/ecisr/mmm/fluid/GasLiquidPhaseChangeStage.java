package cc.ecisr.mmm.fluid;

/** Owns the sealed-interface liquid/gas phase pass in the thermal axis. */
final class GasLiquidPhaseChangeStage {
	interface Access {
		FlipFluidDomain.GasThermalPhaseStats step(int maximumOperations, boolean phaseGridPrepared);
	}

	private final Access access;

	GasLiquidPhaseChangeStage(Access access) {
		this.access = access;
	}

	FlipFluidDomain.GasThermalPhaseStats step(int maximumOperations, boolean phaseGridPrepared) {
		return access.step(maximumOperations, phaseGridPrepared);
	}
}
