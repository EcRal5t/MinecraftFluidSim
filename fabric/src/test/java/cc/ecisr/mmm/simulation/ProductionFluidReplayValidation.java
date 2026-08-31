package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.FluidDomain;

/** Stable production-replay selector; scenario ownership is split by controller and dynamic-body concerns. */
public final class ProductionFluidReplayValidation {
	private ProductionFluidReplayValidation() { }

	public static void main(String[] args) throws Exception {
		ControllerReplayValidationScenarios.controllerPourSequenceWithoutGasSettles();
		ControllerReplayValidationScenarios.controllerPourSequencePreservesGasInventoryAcrossBackend();
		ControllerReplayValidationScenarios.phaseEnabledControllerPourRemainsDefinedAcrossBackend();
		controllerEmptyThenBoundaryPourPreservesGasAcrossBackend();
		DynamicReplayValidationScenarios.floorDamBreakGasThermalPathRemainsDefined();
		DynamicReplayValidationScenarios.fullyBoundHalfFillRoundTripsCurrentGasGeometry();
		DynamicReplayValidationScenarios.fullyBoundPhaseOnStepRemainsBounded();
		DynamicReplayValidationScenarios.planarHalfFillWithoutEntitiesRemainsStableAcrossBackend();
		DynamicReplayValidationScenarios.compressedPlanarHalfFillRemainsStableAcrossBackend();
		DynamicReplayValidationScenarios.stationaryPlayerSizedBodyDoesNotExcitePlanarSurface();
		System.out.println("Production fluid replay passed");
	}

	public static void controllerEmptyThenBoundaryPourPreservesGasAcrossBackend() throws Exception {
		ControllerReplayValidationScenarios.controllerEmptyThenBoundaryPourPreservesGasAcrossBackend();
	}

	public static void controllerPoursKeepSingleSealedGasOwner() {
		ControllerReplayValidationScenarios.controllerPoursKeepSingleSealedGasOwner();
	}

	public static void dynamicPlayerFloatRemainsBoundedAcrossBackend() throws Exception {
		DynamicReplayValidationScenarios.dynamicPlayerFloatRemainsBoundedAcrossBackend();
	}

	public static void walkingBodyDisturbsThenSettlesAcrossBackend() throws Exception {
		DynamicReplayValidationScenarios.walkingBodyDisturbsThenSettlesAcrossBackend();
	}

	public static void defaultLiveStepsRemainBounded() throws Exception {
		DynamicReplayValidationScenarios.defaultLiveStepsRemainBounded();
	}

	static FluidDomain createFullyBoundHalfFilledDomain(FullCapabilityTestFixture.Registries registries) {
		return ProductionReplayValidationSupport.createFullyBoundHalfFilledDomain(registries);
	}

	static FluidDomain createEmptyFullyBoundController(FullCapabilityTestFixture.Registries registries) {
		return ProductionReplayValidationSupport.createEmptyFullyBoundController(registries);
	}

	static FluidDomain createFullyBoundPlanarHalfFill(FullCapabilityTestFixture.Registries registries) {
		return ProductionReplayValidationSupport.createFullyBoundPlanarHalfFill(registries);
	}

	static FullCapabilityTestFixture.Registries loadProductionRegistries() throws Exception {
		return ProductionReplayValidationSupport.loadProductionRegistries();
	}

}
