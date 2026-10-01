package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalOwnerStoreValidation;

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
		ThermalPhaseValidationScenarios.coherentPhaseInventoryAndExactBatchPlanning();
		ThermalPhaseValidationScenarios.compositionAndGranularPhaseChangeConservesJointTruth();
		ThermalPhaseValidationScenarios.phaseVolumeWorkWaitsForMechanicalProjection();
		ThermalPhaseValidationScenarios.operatorEmptyClearsAuditedCondensedCarriers();
		ThermalTransportValidationScenarios.granularEnergyColumnTransactions();
		ThermalPhaseValidationScenarios.exactDyadicAuditDoesNotLoseCancellationRemainders();
		ThermalTransportValidationScenarios.containerTransferCarriesAuthoritativeEnergy();
		ThermalTransportValidationScenarios.liquidTransportAndHandoffCloseEnergy();
		ThermalTransportValidationScenarios.localizedHeatingDrivesBoussinesqCirculation();
		LiquidStratificationValidation.validityAndCommittedDefinitions();
		LiquidStratificationValidation.stableLayers();
		LiquidStratificationValidation.unstableLayersRespond();
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
