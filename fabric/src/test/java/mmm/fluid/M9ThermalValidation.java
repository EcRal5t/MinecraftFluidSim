package mmm.fluid;

import mmm.thermal.ThermalOwnerStoreValidation;
import mmm.thermal.ThermalContactValidation;

/** Stable thermal selector for definition, state-function, transport and phase invariants. */
public final class M9ThermalValidation {
	private M9ThermalValidation() { }

	public static void main(String[] args) {
		ThermochemistryValidationScenarios.definitionAndCanonicalRegistry();
		ThermochemistryValidationScenarios.worldRegistrySnapshotIsAuthoritative();
		ThermochemistryValidationScenarios.piecewiseStateFunctionRoundTrips();
		ThermalGasValidationScenarios.gasStateCompressionAndCarrierEnergy();
		ThermalGasValidationScenarios.builtinDryAirWaterAndVaporPhysicsIsCalibrated();
		ThermalGasValidationScenarios.builtinCo2GasLiquidPairDataIsFrozen();
		ThermalGasValidationScenarios.builtinFiniteThermalDeviceDataIsFrozen();
		ThermalGasValidationScenarios.gasLiquidSaturationAndExactBatch();
		ThermalGasValidationScenarios.gasRuntimeTransfersEnergyAndWork();
		ThermalGasValidationScenarios.thermalGasPressureRowUsesIsentropicCompliance();
		ThermalGasValidationScenarios.persistenceBarrierSynchronizesGasTopology();
		ThermalGasValidationScenarios.operatorVentClearsGasAndEnergyTogether();
		ThermalGasValidationScenarios.sealedInterfaceBoilingCommitsJointInventory();
		ThermalGasValidationScenarios.ambientTrappedAirBoilsIntoMixedNode();
		ThermalGasValidationScenarios.compositionBearingBoilingMovesOnlyGasAdmittedSpecies();
		ThermalGasValidationScenarios.thermalSleepIsOrthogonalToMotion();
		ThermochemistryValidationScenarios.reactionHeatIsPathIndependentStateEnergy();
		ThermochemistryValidationScenarios.reactionCommitValidatesEnergyBeforeMutation();
		ThermochemistryValidationScenarios.granularReactionHeatUsesOneConservedCellEnergy();
		ThermalPhaseValidationScenarios.phasePlateauUsesTheStateFunctionGap();
		ThermalOwnerStoreValidation.validate();
		ThermalContactValidation.validate();
		ThermalPhaseValidationScenarios.coherentPhaseInventoryAndExactBatchPlanning();
		ThermalPhaseValidationScenarios.compositionAndGranularPhaseChangeConservesJointTruth();
		ThermalPhaseValidationScenarios.phaseVolumeWorkWaitsForMechanicalProjection();
		ThermalPhaseValidationScenarios.operatorEmptyClearsAuditedCondensedCarriers();
		ThermalTransportValidationScenarios.granularEnergyColumnTransactions();
		ThermalPhaseValidationScenarios.exactDyadicAuditDoesNotLoseCancellationRemainders();
		ThermalTransportValidationScenarios.containerTransferCarriesAuthoritativeEnergy();
		ThermalTransportValidationScenarios.liquidTransportAndHandoffCloseEnergy();
		ThermalTransportValidationScenarios.localizedHeatingDrivesBoussinesqCirculation();
		LiquidThermalAdvectionValidation.main(new String[0]);
		LiquidStratificationValidation.validityAndCommittedDefinitions();
		LiquidStratificationValidation.stableLayers();
		LiquidStratificationValidation.unstableLayersRespond();
		LiquidConvectionExperiment.main(new String[] {"--verify-growth"});
		ThermalTransportValidationScenarios.partialCutFaceUsesPlicThermalContact();
		ThermalTransportValidationScenarios.finiteThermalBodyExchangesWithoutMixing();
		ThermalGasValidationScenarios.worldBoundaryTemperatureRangeExcludesFiniteDevices();
		ThermalGasValidationScenarios.thermalGridCachesObservedTemperatureRange();
		ThermalTransportValidationScenarios.productionFixedBoundaryFieldAuditsExchange();
		ThermalTransportValidationScenarios.fixedBoundaryAuditsCaloricContinuation();
		ThermalTransportValidationScenarios.crowdedCellEnergyAssignmentPreservesEquilibrium();
		ThermalTransportValidationScenarios.reseedingMovesEnergyWithMarkerTruth();
		System.out.println("M9 thermal validation passed");
	}
}
