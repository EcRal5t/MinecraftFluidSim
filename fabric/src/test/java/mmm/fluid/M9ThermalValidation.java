package mmm.fluid;

import mmm.thermal.ThermalOwnerStoreValidation;
import mmm.thermal.ThermalContactValidation;

/** Stable thermal selector for definition, state-function, transport and phase invariants. */
public final class M9ThermalValidation {
	private M9ThermalValidation() { }

	public static void main(String[] args) throws Exception {
		if (args.length == 1 && args[0].equals("--assignment")) {
			ThermalOwnerStoreValidation.validate();
			ThermalContactValidation.validate();
			ThermalTransportValidationScenarios.crowdedCellEnergyAssignmentPreservesEquilibrium();
			System.out.println("M9 actual carrier energy assignment validation passed");
			return;
		}
		if (args.length == 1 && args[0].equals("--convection")) {
			validateConvectionAndExchange();
			System.out.println("M9 convection and exchange validation passed");
			return;
		}
		if (args.length == 1 && args[0].equals("--flow")) {
			validateFlow();
			System.out.println("M9 thermal flow, convection and exchange validation passed");
			return;
		}
		if (args.length == 1 && args[0].equals("--coherent")) {
			ThermalPhaseValidationScenarios.coherentPhaseInventoryAndExactBatchPlanning();
			ThermalPhaseValidationScenarios.compositionAndGranularPhaseChangeConservesJointTruth();
			ThermalPhaseValidationScenarios.phaseVolumeWorkWaitsForMechanicalProjection();
			ThermalPhaseValidationScenarios.operatorEmptyClearsAuditedCondensedCarriers();
			System.out.println("M9 coherent inventory, capacity and thermal handoff validation passed");
			return;
		}
		if (args.length == 1 && args[0].equals("--phase-species")) {
			ThermalGasValidationScenarios.compositionBearingBoilingMovesOnlyGasAdmittedSpecies();
			ThermalGasValidationScenarios.thermalSleepIsOrthogonalToMotion();
			ThermochemistryValidationScenarios.reactionHeatIsPathIndependentStateEnergy();
			ThermochemistryValidationScenarios.reactionCommitValidatesEnergyBeforeMutation();
			ThermochemistryValidationScenarios.granularReactionHeatUsesOneConservedCellEnergy();
			ThermalPhaseValidationScenarios.phasePlateauUsesTheStateFunctionGap();
			System.out.println("M9 phase species and thermal reaction handoff validation passed");
			return;
		}
		if (args.length == 1 && args[0].equals("--gas-topology")) {
			ThermalGasValidationScenarios.persistenceBarrierSynchronizesGasTopology();
			ThermalGasValidationScenarios.gasRuntimeTransfersEnergyAndWork();
			ThermalGasValidationScenarios.operatorVentClearsGasAndEnergyTogether();
			ThermalGasValidationScenarios.sealedInterfaceBoilingCommitsJointInventory();
			System.out.println("M9 gas topology and thermal handoff validation passed");
			return;
		}
		if (args.length == 1 && args[0].equals("--phase-volume")) {
			ThermalPhaseValidationScenarios.phaseVolumeWorkWaitsForMechanicalProjection();
			ThermalPhaseValidationScenarios.operatorEmptyClearsAuditedCondensedCarriers();
			ThermalGasValidationScenarios.sealedInterfaceBoilingCommitsJointInventory();
			System.out.println("M9 pending phase-volume validation passed");
			return;
		}
		if (args.length == 1 && args[0].equals("--granular-energy")) {
			ThermalTransportValidationScenarios.granularEnergyColumnTransactions();
			System.out.println("M9 granular thermal carrier validation passed");
			return;
		}
		if (args.length != 0) throw new IllegalArgumentException("Unknown thermal validation arguments");
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
		validateFlow();
		System.out.println("M9 thermal validation passed");
	}

	private static void validateFlow() throws Exception {
		LiquidThermalAdvectionValidation.main(new String[0]);
		LiquidStratificationValidation.validityAndCommittedDefinitions();
		LiquidStratificationValidation.stableLayers();
		LiquidStratificationValidation.unstableLayersRespond();
		validateConvectionAndExchange();
	}

	private static void validateConvectionAndExchange() throws Exception {
		LiquidConvectionExperiment.main(new String[] {"--verify-growth"});
		ThermalTransportValidationScenarios.partialCutFaceUsesPlicThermalContact();
		ThermalTransportValidationScenarios.finiteThermalBodyExchangesWithoutMixing();
		ThermalGasValidationScenarios.worldBoundaryTemperatureRangeExcludesFiniteDevices();
		ThermalGasValidationScenarios.thermalGridCachesObservedTemperatureRange();
		ThermalTransportValidationScenarios.productionFixedBoundaryFieldAuditsExchange();
		ThermalTransportValidationScenarios.fixedBoundaryAuditsCaloricContinuation();
		ThermalTransportValidationScenarios.crowdedCellEnergyAssignmentPreservesEquilibrium();
	}
}
