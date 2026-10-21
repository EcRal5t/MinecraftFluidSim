package mmm.simulation;

import mmm.fluid.FluidDomain;

/** Stable production-replay selector; scenario ownership is split by controller and dynamic-body concerns. */
public final class ProductionFluidReplayValidation {
	private ProductionFluidReplayValidation() { }

	public static void main(String[] args) throws Exception {
		mmm.validation.ValidationRunInfo.print("ProductionFluidReplayValidation", args);
        if(args.length==1&&args[0].equals("--controller-gas")){
            ControllerReplayValidationScenarios.controllerPourSequencePreservesGasInventoryAcrossBackend();return;
        }
		if (args.length == 1 && args[0].equals("--compressed-soak")) {
			DynamicReplayValidationScenarios.compressedPlanarHalfFillRemainsStableAcrossBackend(5_000);
			return;
		}
		if (args.length == 1 && args[0].equals("--demo-budget")) {
			DynamicReplayValidationScenarios.damBreakProductionStepsRemainBelowPauseThreshold();
			return;
		}
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

	public static void controllerPoursRetainMacroscopicHeadspace() {
		ControllerReplayValidationScenarios.controllerPoursRetainMacroscopicHeadspace();
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
