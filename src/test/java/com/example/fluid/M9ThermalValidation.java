package com.example.fluid;

import com.example.chemistry.ChemicalLimits;
import com.example.chemistry.BuiltinChemicalDefinitions;
import com.example.chemistry.ChemicalMixtureRecord;
import com.example.chemistry.ChemicalParcelStore;
import com.example.chemistry.ChemicalPhaseDefinition;
import com.example.chemistry.ChemicalReaction;
import com.example.chemistry.ChemicalRegistry;
import com.example.chemistry.ChemicalSpecies;
import com.example.chemistry.ExactRational;
import com.example.granular.SolidVolume;
import com.example.granular.BuiltinGranularDefinitions;
import com.example.granular.GranularRegistry;
import com.example.granular.GranularTransferLedger;
import com.example.fluid.air.AirNetwork;
import com.example.fluid.air.BuiltinGasDefinitions;
import com.example.fluid.air.GasComponentAmount;
import com.example.fluid.air.GasInventory;
import com.example.fluid.air.GasRegistry;
import com.example.thermal.GasThermalModel;
import com.example.thermal.GasLiquidPhaseModel;
import com.example.thermal.SignedDyadic;
import com.example.thermal.GranularEnergyColumn;
import com.example.thermal.CoherentPhaseStore;
import com.example.thermal.ThermalBoundaryDefinition;
import com.example.thermal.ThermalAuditLedger;
import com.example.thermal.ThermalDefinitionCodec;
import com.example.thermal.ThermalDisplaySnapshot;
import com.example.thermal.ThermalMaterialDefinition;
import com.example.thermal.ThermalPhaseTransition;
import com.example.thermal.ThermalPhaseChangeEngine;
import com.example.thermal.ThermalRegistry;
import com.example.thermal.ThermalRegistryStore;
import com.example.thermal.ThermalSpeciesOffset;
import com.example.thermal.ThermalStateFunction;
import com.example.thermal.ThermalTransport;
import com.example.simulation.ThermalActivity;
import com.example.simulation.FluidRegionLifecycle;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

/** One retained selector for M9 definition, state-function and later transport/phase invariants. */
public final class M9ThermalValidation {
	private static final String CHEMICAL_FINGERPRINT = "1".repeat(64);

	private M9ThermalValidation() { }

	public static void main(String[] args) {
		definitionAndCanonicalRegistry();
		worldRegistrySnapshotIsAuthoritative();
		piecewiseStateFunctionRoundTrips();
		gasStateCompressionAndCarrierEnergy();
		gasLiquidSaturationAndExactBatch();
		gasRuntimeTransfersEnergyAndWork();
		sealedInterfaceBoilingCommitsJointInventory();
		compositionBearingBoilingMovesOnlyGasAdmittedSpecies();
		thermalSleepIsOrthogonalToMotion();
		reactionHeatIsPathIndependentStateEnergy();
		reactionCommitValidatesEnergyBeforeMutation();
		granularReactionHeatUsesOneConservedCellEnergy();
		phasePlateauUsesTheStateFunctionGap();
		coherentPhaseInventoryAndExactBatchPlanning();
		compositionAndGranularPhaseChangeConservesJointTruth();
		phaseVolumeWorkWaitsForMechanicalProjection();
		operatorEmptyClearsAuditedCondensedCarriers();
		granularEnergyColumnTransactions();
		exactDyadicAuditDoesNotLoseCancellationRemainders();
		containerTransferCarriesAuthoritativeEnergy();
		liquidTransportAndHandoffCloseEnergy();
		partialCutFaceUsesPlicThermalContact();
		productionFixedBoundaryFieldAuditsExchange();
		fixedBoundaryAuditsAndFailedStepRollsBack();
		reseedingMovesEnergyWithMarkerTruth();
		System.out.println("M9 thermal validation passed");
	}

	private static void thermalSleepIsOrthogonalToMotion() {
		ThermalActivity activity = new ThermalActivity();
		ThermalTransport.Stats quiet = new ThermalTransport.Stats(
				1, 0, 0.0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO);
		ThermalActivity.Observation observation = new ThermalActivity.Observation(
				quiet, false, false, false, false, false, false, true);
		require(!activity.observe(observation, 3) && activity.state() == ThermalActivity.State.REDUCED,
				"first quiet thermal step did not enter reduced activity");
		require(!activity.observe(observation, 3) && activity.canStep(),
				"thermal activity slept before its quiet-step budget");
		require(activity.observe(observation, 3) && activity.state() == ThermalActivity.State.SLEEP
				&& !activity.canStep(), "thermal activity did not enter conservative sleep");
		activity.wake(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
		require(activity.state() == ThermalActivity.State.ACTIVE && activity.canStep(),
				"thermal boundary event did not wake thermal scheduling");
		ThermalTransport.Stats flux = new ThermalTransport.Stats(
				1, 1, 0.0, Math.ulp(1.0), SignedDyadic.ZERO, SignedDyadic.ZERO);
		activity.observe(new ThermalActivity.Observation(
				flux, false, false, false, false, false, false, true), 3);
		require(activity.state() == ThermalActivity.State.ACTIVE && activity.quietSteps() == 0,
				"representable heat transfer was treated as quiet");

		FluidRegionLifecycle motion = new FluidRegionLifecycle();
		motion.setOperatorPaused(false, 1);
		for (int step = 0; step < 3; step++) motion.observeCompletedStep(true, 3);
		require(motion.state() == FluidRegionLifecycle.State.HYDROSTATIC_SLEEP && !motion.canStep()
				&& activity.canStep(), "motion sleep incorrectly disabled the independent thermal axis");
	}

	private static void gasStateCompressionAndCarrierEnergy() {
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		ThermalMaterialDefinition air = new ThermalMaterialDefinition("realwater:thermal/air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
				GasRegistry.STANDARD_TEMPERATURE_K, 10.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 718.0)));
		GasThermalModel model = new GasThermalModel(new ThermalRegistry(List.of(air)), gas);
		GasInventory inventory = gas.createAmbient(1_000_000_000L);
		double initial = model.internalEnergyAtTemperature(inventory, GasRegistry.STANDARD_TEMPERATURE_K);
		require(close(model.state(inventory, initial).temperatureK(), GasRegistry.STANDARD_TEMPERATURE_K),
				"gas temperature was not derived from authoritative U");
		GasThermalModel.Compression compressed = model.compressAdiabatically(inventory, initial, 1.0, 0.5);
		double exponent = GasRegistry.STANDARD_PRESSURE_PA
				/ GasRegistry.STANDARD_TEMPERATURE_K / (1.2 * 718.0);
		double expectedTemperature = GasRegistry.STANDARD_TEMPERATURE_K * Math.pow(2.0, exponent);
		require(Math.abs(compressed.finalTemperatureK() - expectedTemperature) < 1.0E-10
				&& compressed.workIntoGas() > 0.0
				&& model.absolutePressure(inventory, 0.5, compressed.finalInternalEnergy())
				> 2.0 * GasRegistry.STANDARD_PRESSURE_PA,
				"analytic gas compression did not close state/work/EoS");
		GasThermalModel.Compression expanded = model.compressAdiabatically(
				inventory, compressed.finalInternalEnergy(), 0.5, 1.0);
		require(Math.abs(expanded.finalInternalEnergy() - initial) < 1.0E-9,
				"reversible analytic gas expansion did not return initial U");
		List<GasInventory> children = inventory.partition(new long[] { 1L, 2L, 3L });
		double[] energies = model.partitionEnergy(inventory, initial, children);
		double partitioned = 0.0;
		for (int index = 0; index < children.size(); index++) {
			partitioned += energies[index];
			if (!children.get(index).isEmpty()) require(close(model.state(children.get(index), energies[index]).temperatureK(),
					GasRegistry.STANDARD_TEMPERATURE_K), "gas topology split did not retain parent temperature");
		}
		require(Math.abs(partitioned - initial) <= Math.ulp(initial) * 4.0
				&& model.enthalpy(children.getFirst(), GasRegistry.STANDARD_TEMPERATURE_K) > energies[0],
				"gas topology/orifice carrier energy did not close");
	}

	private static void gasRuntimeTransfersEnergyAndWork() {
		GasRegistry gasRegistry = BuiltinGasDefinitions.createRegistry();
		FluidMaterial liquid = FluidMaterial.withSurfaceTension(
				"test:gas_host", 1_000.0, "test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(liquid));
		ThermalRegistry registry = new ThermalRegistry(List.of(
				new ThermalMaterialDefinition("test:thermal/gas_host",
						ThermalMaterialDefinition.CarrierKind.LIQUID, liquid.id(), 300.0, 0.0, 0.5,
						List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 4_000.0))),
				new ThermalMaterialDefinition("realwater:thermal/air",
						ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
						GasRegistry.STANDARD_TEMPERATURE_K, 10.0, 0.025,
						List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 718.0)))));
		FlipThermalState.MarkerContext empty = emptyMarkers();

		AirNetwork compressedNetwork = new AirNetwork(gasRegistry);
		compressedNetwork.addNode(1.0, 1.0);
		FlipThermalState compressed = new FlipThermalState(registry, liquids,
				GasRegistry.STANDARD_TEMPERATURE_K, 1, 2, 2, 2, 0.5F, empty, compressedNetwork);
		double initialEnergy = compressed.gasNodeEnergy(1, compressedNetwork);
		FlipThermalState.GasRemapPlan compression = compressed.planGasRemap(compressedNetwork,
				List.of(compressedNetwork.inventory(1)), new double[] {0.8}, new double[] {initialEnergy},
				new double[] {1.0}, 0.0, 0.0);
		compressedNetwork.remapNodes(new double[] {0.8}, List.of(compressedNetwork.inventory(1)),
				GasInventory.EMPTY, GasInventory.EMPTY);
		compressed.commitGasRemap(compression);
		require(compressed.gasTemperature(1, compressedNetwork) > GasRegistry.STANDARD_TEMPERATURE_K
				&& compressed.audit().mechanicalWork().compareTo(SignedDyadic.ZERO) > 0
				&& compressed.exactEnergy(0).equals(compressed.audit().expectedInventory()),
				"gas topology compression did not jointly commit U and mechanical work");

		AirNetwork flowingNetwork = new AirNetwork(gasRegistry);
		int high = flowingNetwork.addNode(1.0, 1.1), low = flowingNetwork.addNode(1.0, 0.9);
		flowingNetwork.addOrMergeEdge(high, low, 0.1, 0.65);
		FlipThermalState flowing = new FlipThermalState(registry, liquids,
				GasRegistry.STANDARD_TEMPERATURE_K, 1, 2, 2, 2, 0.5F, empty, flowingNetwork);
		flowingNetwork.stepEdges(0.01, thermodynamics(flowing, flowingNetwork),
				(source, target, moved, sourceAfter, targetAfter) -> flowing.planGasTransfer(
						source, target, moved, sourceAfter, targetAfter, flowingNetwork));
		require(flowing.gasTemperature(high, flowingNetwork) < GasRegistry.STANDARD_TEMPERATURE_K
				&& flowing.gasTemperature(low, flowingNetwork) > GasRegistry.STANDARD_TEMPERATURE_K
				&& flowing.exactEnergy(0).equals(flowing.audit().expectedInventory()),
				"internal gas orifice did not carry source-state enthalpy with exact audit closure");

		flowingNetwork.addOrMergeEdge(high, AirNetwork.AMBIENT_NODE, 0.1, 0.65);
		flowingNetwork.stepEdges(0.01, thermodynamics(flowing, flowingNetwork),
				(source, target, moved, sourceAfter, targetAfter) -> flowing.planGasTransfer(
						source, target, moved, sourceAfter, targetAfter, flowingNetwork));
		require(flowing.audit().externalSink().compareTo(SignedDyadic.ZERO) > 0
				&& flowing.exactEnergy(0).equals(flowing.audit().expectedInventory()),
				"ambient gas outflow did not book carried enthalpy as an external sink");
	}

	private static void gasLiquidSaturationAndExactBatch() {
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		ThermalMaterialDefinition liquid = new ThermalMaterialDefinition("test:thermal/liquid_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "test:water", 373.15, 0.0, 0.6,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 4_180.0)));
		ThermalMaterialDefinition vapor = new ThermalMaterialDefinition("test:thermal/water_vapor",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.WATER_VAPOR,
				373.15, 2_256_000.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 1_410.0)));
		long gasSvq = Math.round((0.001 / BuiltinGasDefinitions.WATER_VAPOR_STANDARD_DENSITY)
				* 373.15 / GasRegistry.STANDARD_TEMPERATURE_K * SolidVolume.SVQ_PER_BV);
		ThermalPhaseTransition transition = new ThermalPhaseTransition("test:boil",
				liquid.id(), vapor.id(), 373.15, 134L, gasSvq, ExactRational.parse("1/1000"),
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 10_000.0, 2_000_000.0);
		GasLiquidPhaseModel model = new GasLiquidPhaseModel(
				new ThermalRegistry(List.of(liquid, vapor), List.of(), List.of(transition), "", ""), gas);
		GasInventory batch = model.gasBatch(transition, 1L);
		GasLiquidPhaseModel.Saturation equilibrium = model.saturation(
				transition, 373.15, batch, GasRegistry.STANDARD_PRESSURE_PA);
		require(close(equilibrium.saturationPressurePa(), GasRegistry.STANDARD_PRESSURE_PA)
				&& equilibrium.direction() == GasLiquidPhaseModel.Direction.EQUILIBRIUM
				&& model.saturation(transition, 383.15, batch,
				GasRegistry.STANDARD_PRESSURE_PA).direction() == GasLiquidPhaseModel.Direction.BOIL
				&& model.saturation(transition, 363.15, batch,
				GasRegistry.STANDARD_PRESSURE_PA).direction() == GasLiquidPhaseModel.Direction.CONDENSE,
				"Clausius-Clapeyron saturation did not select the physical phase direction");
		AirNetwork network = new AirNetwork(gas); int node = network.addNode(1.0, 1.0);
		network.commitPhaseSource(node, batch);
		GasInventory.Split take = network.previewComponentTake(node,
				BuiltinGasDefinitions.WATER_VAPOR, batch.standardVolumeNanos());
		network.commitPhaseSink(node, take.first());
		require(take.first().equals(batch) && network.auditClosed(),
				"gas phase batch did not preserve component/mass/residual ledgers");
	}

	private static AirNetwork.NodeThermodynamics thermodynamics(
			FlipThermalState thermal, AirNetwork network
	) {
		return new AirNetwork.NodeThermodynamics() {
			@Override public double gaugePressure(int nodeId) {
				return nodeId == AirNetwork.AMBIENT_NODE ? 0.0 : thermal.gasGaugePressure(nodeId, network);
			}
			@Override public double density(int nodeId) {
				return nodeId == AirNetwork.AMBIENT_NODE ? AirNetwork.AIR_DENSITY
						: thermal.gasDensity(nodeId, network);
			}
		};
	}

	private static FlipThermalState.MarkerContext emptyMarkers() {
		return new FlipThermalState.MarkerContext() {
			@Override public int markerCount() { return 0; }
			@Override public int cell(int marker) { throw new IllegalArgumentException(); }
			@Override public int fluidMaterialHandle(int marker) { throw new IllegalArgumentException(); }
			@Override public double mass(int marker) { throw new IllegalArgumentException(); }
			@Override public double volume(int marker) { throw new IllegalArgumentException(); }
			@Override public ChemicalMixtureRecord mixture(int marker) { throw new IllegalArgumentException(); }
			@Override public ChemicalRegistry chemicalRegistry() { return null; }
		};
	}

	private static void sealedInterfaceBoilingCommitsJointInventory() {
		FluidMaterial waterCarrier = FluidMaterial.withSurfaceTension(
				"test:boiling_water", 1.0, "test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(waterCarrier));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("test:thermal/boiling_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, waterCarrier.id(), 373.15, 0.0, 0.6,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 800.0, 4_180.0)));
		ThermalMaterialDefinition air = new ThermalMaterialDefinition("test:thermal/boiling_air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR, 373.15, 0.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 800.0, 718.0)));
		ThermalMaterialDefinition vapor = new ThermalMaterialDefinition("test:thermal/boiling_vapor",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.WATER_VAPOR,
				373.15, 2_256_000.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 800.0, 1_410.0)));
		long liquidSvq = SolidVolume.SVQ_PER_CELL / 200L;
		long vaporSvq = liquidSvq * 1_000L;
		ThermalPhaseTransition boiling = new ThermalPhaseTransition("test:phase/boiling_water",
				water.id(), vapor.id(), 373.15, liquidSvq, vaporSvq, ExactRational.parse("0.01"),
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 10_000.0, 3_000_000.0);
		ThermalRegistry thermal = new ThermalRegistry(
				List.of(water, air, vapor), List.of(), List.of(boiling), "", "");
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, config, 512);
		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
			if (x == 0 || x == 3 || y == 0 || y == 3 || z == 0 || z == 3) domain.setSolid(x, y, z, true);
		}
		domain.setVolume(1, 1, 1, 1.0F);
		domain.bindThermal(thermal, liquids, 500.0);
		require(domain.airNetwork().nodeCount() == 1, "boiling fixture did not form one sealed receiver");
		ThermalDisplaySnapshot display = domain.copyThermalDisplaySnapshot();
		int liquidCell = (1 * 4 + 1) * 4 + 1;
		require(display.cellTemperatureQ().length == 64
				&& Short.toUnsignedInt(display.cellTemperatureQ()[liquidCell]) == 500 * 16
				&& (display.debugFlags()[liquidCell] & ThermalDisplaySnapshot.FLAG_CONDENSED) != 0
				&& Byte.toUnsignedInt(display.coherentFraction()[liquidCell]) == 0
				&& display.gasNodes().size() == 1 && display.gasNodes().getFirst().nodeId() == 1,
				"thermal v5 display did not derive quantized condensed/gas state in one rebuild");
		double liquidMassBefore = domain.totalMass();
		long gasNanosBefore = domain.airNetwork().standardVolumeNanos(1);
		SignedDyadic energyBefore = domain.exactInternalEnergy();
		ThermalAuditLedger auditBefore = domain.thermalAudit();
		domain.step(true, 1);
		FlipFluidDomain.GasThermalPhaseStats stats = domain.lastGasThermalPhaseStats();
		GasComponentAmount vaporAmount = domain.airNetwork().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR);
		require(stats.operations() == 1 && stats.boiledCells() == 1 && stats.condensedCells() == 0
				&& close(domain.totalMass(), liquidMassBefore - 0.01)
				&& vaporAmount != null && close(vaporAmount.mass(), 0.01)
				&& domain.airNetwork().standardVolumeNanos(1) > gasNanosBefore
				&& domain.airNetwork().auditClosed()
				&& domain.thermalAudit().mechanicalWork().compareTo(auditBefore.mechanicalWork()) < 0
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"sealed boiling did not jointly conserve liquid/gas mass, volume and energy truth: stats=" + stats
						+ " liquidMass=" + domain.totalMass() + "/" + liquidMassBefore
						+ " vapor=" + vaporAmount + " nanos=" + domain.airNetwork().standardVolumeNanos(1)
						+ "/" + gasNanosBefore + " deltaU="
						+ domain.exactInternalEnergy().subtract(energyBefore));
		domain.setThermalTemperatureInCell(1, 1, 1, 300.0);
		SignedDyadic beforeCondensation = domain.exactInternalEnergy();
		ThermalAuditLedger auditBeforeCondensation = domain.thermalAudit();
		FlipThermalParticleState beforeCondensationState = domain.exportThermalParticleState();
		FlipFluidDomain.GasThermalPhaseStats condensed = domain.stepGasPhaseChangeOnly(1);
		require(condensed.operations() == 1 && condensed.boiledCells() == 0 && condensed.condensedCells() == 1
				&& close(domain.totalMass(), liquidMassBefore)
				&& domain.airNetwork().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR) == null
				&& domain.airNetwork().standardVolumeNanos(1) == gasNanosBefore
				&& domain.airNetwork().auditClosed()
				&& domain.thermalAudit().mechanicalWork().compareTo(
						auditBeforeCondensation.mechanicalWork()) > 0
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"sealed condensation did not reverse the exact gas batch with conserved energy: stats=" + condensed
						+ " mass=" + domain.totalMass() + " vapor="
						+ domain.airNetwork().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR)
						+ " nanos=" + domain.airNetwork().standardVolumeNanos(1) + "/" + gasNanosBefore
						+ " deltaU=" + domain.exactInternalEnergy().subtract(beforeCondensation)
						+ " gas=" + java.util.Arrays.toString(beforeCondensationState.copyGasNodeEnergy()) + "->"
						+ java.util.Arrays.toString(domain.exportThermalParticleState().copyGasNodeEnergy())
						+ " liquid=" + SignedDyadic.sum(beforeCondensationState.copyInternalEnergy()) + "->"
						+ SignedDyadic.sum(domain.exportThermalParticleState().copyInternalEnergy()));
	}

	private static void compositionBearingBoilingMovesOnlyGasAdmittedSpecies() {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("test:thermal/species_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				373.15, 0.0, 0.6,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 800.0, 4_180.0)));
		ThermalMaterialDefinition air = new ThermalMaterialDefinition("test:thermal/species_air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
				373.15, 0.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 800.0, 718.0)));
		ThermalMaterialDefinition vapor = new ThermalMaterialDefinition("test:thermal/species_vapor",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.WATER_VAPOR,
				373.15, 2_256_000.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 800.0, 1_410.0)));
		long liquidSvq = SolidVolume.SVQ_PER_CELL / 200L;
		ThermalPhaseTransition boiling = new ThermalPhaseTransition("test:phase/species_boiling",
				water.id(), vapor.id(), 373.15, liquidSvq, liquidSvq * 1_000L,
				ExactRational.parse("0.01"), ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS,
				10_000.0, 3_000_000.0);
		java.util.ArrayList<ThermalMaterialDefinition> materials = new java.util.ArrayList<>(List.of(water, air, vapor));
		for (int handle = 0; handle < granular.materials().size(); handle++) materials.add(
				new ThermalMaterialDefinition("test:thermal/species_granular_" + handle,
						ThermalMaterialDefinition.CarrierKind.GRANULAR, granular.material(handle).id(),
						373.15, 0.0, 0.2,
						List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 800.0, 900.0))));
		ThermalRegistry thermal = new ThermalRegistry(materials, List.of(
				new ThermalSpeciesOffset("test:offset/species_water_h2o", BuiltinChemicalDefinitions.H2O,
						water.id(), ExactRational.ZERO),
				new ThermalSpeciesOffset("test:offset/species_water_na", BuiltinChemicalDefinitions.NA_PLUS,
						water.id(), ExactRational.ZERO),
				new ThermalSpeciesOffset("test:offset/species_vapor_h2o", BuiltinChemicalDefinitions.H2O,
						vapor.id(), ExactRational.ZERO)), List.of(boiling),
				chemistry.fingerprint(), granular.fingerprint());
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, config, 512);
		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
			if (x == 0 || x == 3 || y == 0 || y == 3 || z == 0 || z == 3) domain.setSolid(x, y, z, true);
		}
		domain.setVolume(1, 1, 1, 0.75F);
		domain.bindGranular(granular, liquids);
		domain.bindChemistry(chemistry);
		for (int marker = 0; marker < domain.particleCount(); marker++) domain.setMarkerChemical(marker, Map.of(
				BuiltinChemicalDefinitions.H2O, 20_000_000L,
				BuiltinChemicalDefinitions.NA_PLUS, 1_000_000L), marker == 0);
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long sandSvq = granular.material(sand).maximumActiveParcelSvq();
		double sandMass = SolidVolume.toCellVolume(sandSvq) * granular.material(sand).referenceDensity();
		require(domain.tryInsertExactGranular(1, 1, 1, sand, 0, sandSvq, sandMass).inserted(),
				"gas phase fixture could not insert its granular LTE carrier");
		domain.bindThermal(thermal, liquids, 500.0);
		long initialGranularSvq = domain.totalGranularSolidVolumeSvq();
		FlipChemicalParticleState initial = domain.exportChemicalParticleState();
		long initialH2o = initial.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)];
		long initialSodium = initial.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)];
		float indicatorVolume = initial.materials().copyPositionVolume()[3];
		double indicatorMass = initial.materials().copyParticleMass()[0];

		domain.step(true, 1);
		GasComponentAmount gas = domain.airNetwork().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR);
		FlipChemicalParticleState boiled = domain.exportChemicalParticleState();
		long gasH2o = gas.species().stream().filter(amount -> amount.speciesId().equals(
				BuiltinChemicalDefinitions.H2O)).mapToLong(GasComponentAmount.SpeciesAmount::amountQuanta).sum();
		long gasSodium = gas.species().stream().filter(amount -> amount.speciesId().equals(
				BuiltinChemicalDefinitions.NA_PLUS)).mapToLong(GasComponentAmount.SpeciesAmount::amountQuanta).sum();
		require(domain.lastGasThermalPhaseStats().boiledCells() == 1 && gasH2o > 0L && gasSodium == 0L
				&& boiled.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)] + gasH2o == initialH2o
				&& boiled.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)] == initialSodium
				&& close(boiled.residualLedger() + gas.massResidual(), initial.residualLedger())
				&& Float.floatToRawIntBits(boiled.materials().copyPositionVolume()[3])
				== Float.floatToRawIntBits(indicatorVolume)
				&& Double.doubleToRawLongBits(boiled.materials().copyParticleMass()[0])
				== Double.doubleToRawLongBits(indicatorMass)
				&& domain.totalGranularSolidVolumeSvq() == initialGranularSvq
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"species-bearing boiling did not retain solute/indicator and move exact vapor truth");

		domain.setThermalTemperatureInCell(1, 1, 1, 300.0);
		FlipFluidDomain.GasThermalPhaseStats condensed = domain.stepGasPhaseChangeOnly(1);
		FlipChemicalParticleState restored = domain.exportChemicalParticleState();
		require(condensed.condensedCells() == 1
				&& domain.airNetwork().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR) == null
				&& restored.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)] == initialH2o
				&& restored.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)] == initialSodium
				&& close(restored.residualLedger(), initial.residualLedger())
				&& Float.floatToRawIntBits(restored.materials().copyPositionVolume()[3])
				== Float.floatToRawIntBits(indicatorVolume)
				&& Double.doubleToRawLongBits(restored.materials().copyParticleMass()[0])
				== Double.doubleToRawLongBits(indicatorMass)
				&& domain.totalGranularSolidVolumeSvq() == initialGranularSvq
				&& domain.airNetwork().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"species-bearing condensation did not restore exact liquid/gas chemical truth");
	}

	private static void definitionAndCanonicalRegistry() {
		ThermalMaterialDefinition first = material("realwater:thermal/test", "realwater:test", 100.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 300.0, 2.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 4.0)));
		ThermalMaterialDefinition second = material("realwater:thermal/other", "realwater:other", 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 3.0)));
		ThermalRegistry forward = new ThermalRegistry(List.of(first, second));
		ThermalRegistry reverse = new ThermalRegistry(List.of(second, first));
		require(forward.fingerprint().equals(reverse.fingerprint()), "thermal registry depends on definition order");
		require(java.util.Arrays.equals(forward.canonicalBytes(), reverse.canonicalBytes()),
				"thermal canonical bytes depend on definition order");

		String json = """
				{
				  "carrier_kind":"LIQUID",
				  "carrier_id":"realwater:test",
				  "reference_temperature_k":"300.0",
				  "reference_specific_energy_eu_per_mu":"100.0",
				  "conductivity_eu_per_second_meter_k":"0.5",
				  "caloric_segments":[
				    {"minimum_temperature_k":"200.0","maximum_temperature_k":"300.0","specific_heat_eu_per_mu_k":"2.0"},
				    {"minimum_temperature_k":"300.0","maximum_temperature_k":"500.0","specific_heat_eu_per_mu_k":"4.0"}
				  ]
				}
				""";
		ThermalMaterialDefinition decoded = ThermalDefinitionCodec.decodeMaterial("realwater:thermal/test",
				new StringReader(json));
		require(decoded.equals(first), "thermal material JSON did not round-trip the definition contract");
		ThermalBoundaryDefinition boundary = ThermalDefinitionCodec.decodeBoundary("realwater:boundary/test",
				new StringReader("{\"target_kind\":\"BLOCK\",\"target_id\":\"minecraft:magma_block\","
						+ "\"kind\":\"FIXED_SURFACE_TEMPERATURE\",\"surface_temperature_k\":\"500.0\"}"));
		ThermalRegistry withBoundary = new ThermalRegistry(List.of(first, second), List.of(), List.of(),
				List.of(boundary), "", "");
		require(withBoundary.boundaries().equals(List.of(boundary))
				&& !withBoundary.fingerprint().equals(forward.fingerprint()),
				"thermal boundary definition was absent from canonical registry identity");
		expectFailure(() -> new ThermalMaterialDefinition("realwater:thermal/broken",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "realwater:broken", 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 299.0, 1.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 1.0))), "non-contiguous segments accepted");
	}

	private static void worldRegistrySnapshotIsAuthoritative() {
		java.nio.file.Path root = null;
		try {
			root = java.nio.file.Files.createTempDirectory("realwater-thermal-registry-");
			ThermalMaterialDefinition original = material("test:thermal/store", "test:store", 0.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0)));
			ThermalRegistry authoritative = new ThermalRegistry(List.of(original));
			ThermalRegistryStore store = new ThermalRegistryStore(root, "", "");
			ThermalRegistryStore.LoadResult first = store.loadOrCreate(authoritative);
			require(first.created() && !first.definitionMismatch()
					&& first.registry().fingerprint().equals(authoritative.fingerprint()),
					"first thermal registry snapshot was not atomically published");
			ThermalMaterialDefinition changed = material("test:thermal/store", "test:store", 1.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0)));
			ThermalRegistryStore.LoadResult mismatch = store.loadOrCreate(new ThermalRegistry(List.of(changed)));
			require(mismatch.definitionMismatch()
					&& mismatch.registry().fingerprint().equals(authoritative.fingerprint()),
					"changed thermal datapack reinterpreted the world snapshot");
			java.nio.file.Files.writeString(root.resolve("realwater").resolve("thermal").resolve("foreign.bin"), "foreign");
			try {
				store.read();
				throw new AssertionError("unknown thermal registry content was ignored");
			} catch (java.io.IOException expected) { }
		} catch (java.io.IOException failure) {
			throw new AssertionError("thermal registry store validation failed", failure);
		} finally {
			if (root != null) try (var paths = java.nio.file.Files.walk(root)) {
				for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					java.nio.file.Files.deleteIfExists(path);
				}
			} catch (java.io.IOException cleanup) {
				throw new AssertionError("thermal registry store cleanup failed", cleanup);
			}
		}
	}

	private static void piecewiseStateFunctionRoundTrips() {
		ThermalMaterialDefinition definition = material("realwater:thermal/test", "realwater:test", 100.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 300.0, 2.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 4.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(definition));
		ThermalStateFunction state = new ThermalStateFunction(registry);
		int handle = registry.materialHandle(definition.id());
		for (double temperature : new double[]{200.0, 250.0, 300.0, 375.0, 500.0}) {
			double energy = state.internalEnergyAtTemperature(handle, 2.5, Map.of(), temperature);
			double restored = state.stateForInternalEnergy(handle, 2.5, Map.of(), energy).temperatureK();
			require(close(restored, temperature), "piecewise thermal state did not round-trip at " + temperature);
		}
		double cold = state.internalEnergyAtTemperature(handle, 1.0, Map.of(), 200.0);
		require(cold < 0.0, "thermal state rejected the valid negative-energy side of its reference");
		expectFailure(() -> state.stateForInternalEnergy(handle, 1.0, Map.of(), Double.MAX_VALUE),
				"out-of-range internal energy was extrapolated");
	}

	private static void reactionHeatIsPathIndependentStateEnergy() {
		ThermalMaterialDefinition solution = material("realwater:thermal/solution", "realwater:water", 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 500.0, 1_000.0)));
		List<ThermalSpeciesOffset> offsets = List.of(
				new ThermalSpeciesOffset("realwater:thermal_offset/h", "realwater:h_plus", solution.id(), ExactRational.of(10)),
				new ThermalSpeciesOffset("realwater:thermal_offset/oh", "realwater:oh_minus", solution.id(), ExactRational.of(20)),
				new ThermalSpeciesOffset("realwater:thermal_offset/water", "realwater:h2o", solution.id(), ExactRational.of(-50)));
		ThermalRegistry registry = new ThermalRegistry(List.of(solution), offsets, List.of(), CHEMICAL_FINGERPRINT, "");
		ThermalStateFunction state = new ThermalStateFunction(registry);
		int handle = registry.materialHandle(solution.id());
		Map<String, Long> reactants = Map.of("realwater:h_plus", ChemicalLimits.QUANTA_PER_AU,
				"realwater:oh_minus", ChemicalLimits.QUANTA_PER_AU);
		Map<String, Long> productsA = Map.of("realwater:h2o", ChemicalLimits.QUANTA_PER_AU);
		Map<String, Long> productsB = new java.util.TreeMap<>(productsA);
		double reactantReference = state.internalEnergyAtTemperature(handle, 1.0, reactants, 300.0);
		double productTemperatureA = state.stateForInternalEnergy(handle, 1.0, productsA, reactantReference).temperatureK();
		double productTemperatureB = state.stateForInternalEnergy(handle, 1.0, productsB, reactantReference).temperatureK();
		require(productTemperatureA > 300.0 && close(productTemperatureA, productTemperatureB),
				"formation energy did not produce path-independent exothermic temperature rise");
	}

	private static void reactionCommitValidatesEnergyBeforeMutation() {
		String a = "test:reactant", b = "test:product", phaseId = "test:phase", materialId = "test:liquid";
		ChemicalSpecies reactant = new ChemicalSpecies(a, Map.of("X", 1), 0, ExactRational.ONE, 0xff000000);
		ChemicalSpecies product = new ChemicalSpecies(b, Map.of("X", 1), 0, ExactRational.ONE, 0xff000000);
		ChemicalPhaseDefinition phase = new ChemicalPhaseDefinition(
				phaseId, materialId, List.of(a, b), Map.of(a, ExactRational.ONE), false);
		ChemicalReaction reaction = new ChemicalReaction("test:reaction", ChemicalReaction.Kind.INSTANTANEOUS,
				phaseId, Map.of(a, 1), Map.of(b, 1), ExactRational.ZERO);
		ChemicalRegistry chemistry = new ChemicalRegistry(List.of(reactant, product), List.of(phase), List.of(reaction));
		ChemicalParcelStore parcels = new ChemicalParcelStore(chemistry, 1, 8);
		ChemicalMixtureRecord initial = ChemicalMixtureRecord.fromStableAmounts(
				chemistry, Map.of(a, ChemicalLimits.QUANTA_PER_AU), 0.0);
		parcels.assign(0, initial, false);
		parcels.markDirtyCell(0);
		double[] mass = {1.0};
		FluidMaterial liquid = FluidMaterial.withSurfaceTension(materialId, 1.0, "test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(liquid));
		FlipThermalState.MarkerContext markers = new FlipThermalState.MarkerContext() {
			@Override public int markerCount() { return 1; }
			@Override public int cell(int marker) { return 0; }
			@Override public int fluidMaterialHandle(int marker) { return 0; }
			@Override public double mass(int marker) { return mass[marker]; }
			@Override public double volume(int marker) { return 1.0; }
			@Override public ChemicalMixtureRecord mixture(int marker) { return parcels.record(marker); }
			@Override public ChemicalRegistry chemicalRegistry() { return chemistry; }
		};
		List<ThermalSpeciesOffset> offsets = List.of(
				new ThermalSpeciesOffset("test:offset/a", a, "test:thermal/liquid", ExactRational.ZERO),
				new ThermalSpeciesOffset("test:offset/b", b, "test:thermal/liquid", ExactRational.of(-100)));
		FlipThermalState narrow = new FlipThermalState(new ThermalRegistry(List.of(new ThermalMaterialDefinition(
				"test:thermal/liquid", ThermalMaterialDefinition.CarrierKind.LIQUID, materialId, 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 305.0, 10.0)))), offsets, List.of(),
				chemistry.fingerprint(), ""), liquids, 300.0, 1, 2, 2, 2, 0.5F, markers);
		expectFailure(() -> parcels.reactDirtyCells(2, 2, 2, 0.5F,
				new float[]{0.1F}, new float[]{0.1F}, new float[]{0.1F}, mass, new int[]{0},
				handle -> materialId, 1,
				(marker, mixture, candidateMass) -> narrow.requireReactionState(
						marker, mixture, candidateMass, markers)),
				"out-of-range reaction heat partially committed");
		require(parcels.record(0).amount(chemistry.speciesHandle(a)) == ChemicalLimits.QUANTA_PER_AU,
				"failed thermal reaction validation changed chemical truth");

		FlipThermalState thermal = new FlipThermalState(new ThermalRegistry(List.of(new ThermalMaterialDefinition(
				"test:thermal/liquid", ThermalMaterialDefinition.CarrierKind.LIQUID, materialId, 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0)))), offsets, List.of(),
				chemistry.fingerprint(), ""), liquids, 300.0, 1, 2, 2, 2, 0.5F, markers);
		SignedDyadic before = thermal.exactEnergy(1);
		parcels.reactDirtyCells(2, 2, 2, 0.5F,
				new float[]{0.1F}, new float[]{0.1F}, new float[]{0.1F}, mass, new int[]{0},
				handle -> materialId, 1,
				(marker, mixture, candidateMass) -> thermal.requireReactionState(
						marker, mixture, candidateMass, markers));
		require(thermal.exactEnergy(1).equals(before) && close(thermal.temperature(0, markers), 310.0),
				"reaction heat was not derived from conserved marker internal energy");
	}

	private static void granularReactionHeatUsesOneConservedCellEnergy() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 1.0F / 20.0F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.M3_PALETTE);
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FlipFluidDomain domain = new FlipFluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		domain.setVolume(1, 1, 1, 0.1F);
		domain.bindGranular(granular, liquids);
		domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		domain.setMarkerChemical(0, Map.of(
				BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L,
				BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L), true);
		domain.bindThermal(reactiveGranularThermalRegistry(liquids, granular), liquids, 300.0);
		domain.setPrecipitationEnabled(true);
		SignedDyadic beforePrecipitation = domain.exactInternalEnergy();
		domain.step();
		double precipitationTemperature = domain.thermalTemperatureInCell(1, 1, 1);
		require(domain.lastPhaseTransferStats().precipitations() == 1
				&& precipitationTemperature > 300.0
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& Math.abs(domain.exactInternalEnergy().subtract(beforePrecipitation).toDouble()) <= 1.0E-8,
				"solid formation energy did not emerge as conserved-cell precipitation heat: T="
						+ precipitationTemperature + " deltaU="
						+ domain.exactInternalEnergy().subtract(beforePrecipitation));

		for (int marker = 0; marker < domain.particleCount(); marker++) domain.setMarkerChemical(
				marker, Map.of(BuiltinChemicalDefinitions.H2O, 1_000_000L), (marker & 1) == 0);
		SignedDyadic beforeDissolution = domain.exactInternalEnergy();
		double temperatureBeforeDissolution = domain.thermalTemperatureInCell(1, 1, 1);
		boolean dissolved = false;
		for (int step = 0; step < 16 && domain.totalGranularSolidVolumeSvq() > 0L; step++) {
			domain.step(); dissolved |= domain.lastPhaseTransferStats().dissolutions() > 0;
		}
		double dissolutionTemperature = domain.thermalTemperatureInCell(1, 1, 1);
		require(dissolved && domain.totalGranularSolidVolumeSvq() == 0L
				&& dissolutionTemperature < temperatureBeforeDissolution
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& Math.abs(domain.exactInternalEnergy().subtract(beforeDissolution).toDouble()) <= 1.0E-8,
				"dissolution did not consume sensible energy through the same state function: beforeT="
						+ temperatureBeforeDissolution + " afterT=" + dissolutionTemperature);
	}

	private static ThermalRegistry reactiveGranularThermalRegistry(
			FluidMaterialRegistry liquids, GranularRegistry granular
	) {
		java.util.ArrayList<ThermalMaterialDefinition> materials = new java.util.ArrayList<>();
		for (int handle = 0; handle < liquids.size(); handle++) materials.add(new ThermalMaterialDefinition(
				"test:thermal/reactive_liquid_" + handle, ThermalMaterialDefinition.CarrierKind.LIQUID,
				liquids.material(handle).id(), 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 100.0))));
		for (int handle = 0; handle < granular.materials().size(); handle++) materials.add(
				new ThermalMaterialDefinition("test:thermal/reactive_granular_" + handle,
						ThermalMaterialDefinition.CarrierKind.GRANULAR, granular.material(handle).id(),
						300.0, 0.0, 1.0,
						List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 100.0))));
		java.util.ArrayList<ThermalSpeciesOffset> offsets = new java.util.ArrayList<>();
		for (int material = 0; material < materials.size(); material++) {
			ThermalMaterialDefinition host = materials.get(material);
			for (int species = 0; species < BuiltinChemicalDefinitions.REGISTRY.species().size(); species++) {
				String speciesId = BuiltinChemicalDefinitions.REGISTRY.species(species).id();
				boolean precipitate = host.carrierKind() == ThermalMaterialDefinition.CarrierKind.GRANULAR
						&& host.carrierId().equals(BuiltinGranularDefinitions.PRECIPITATE)
						&& (speciesId.equals(BuiltinChemicalDefinitions.NA_PLUS)
						|| speciesId.equals(BuiltinChemicalDefinitions.CL_MINUS));
				offsets.add(new ThermalSpeciesOffset("test:thermal_offset/m" + material + "_s" + species,
						speciesId, host.id(), precipitate ? ExactRational.of(-1) : ExactRational.ZERO));
			}
		}
		return new ThermalRegistry(materials, offsets, List.of(), BuiltinChemicalDefinitions.REGISTRY.fingerprint(),
				granular.fingerprint());
	}

	private static void phasePlateauUsesTheStateFunctionGap() {
		ThermalMaterialDefinition ice = new ThermalMaterialDefinition("realwater:thermal/ice",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "realwater:ice", 273.15, 0.0, 2.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 273.15, 2_100.0)));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("realwater:thermal/water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "realwater:water", 273.15, 334_000.0, 0.6,
				List.of(new ThermalMaterialDefinition.CaloricSegment(273.15, 500.0, 4_180.0)));
		ThermalPhaseTransition melt = new ThermalPhaseTransition("realwater:phase/ice_water", ice.id(), water.id(),
				273.15, SolidVolume.SVQ_PER_BV, SolidVolume.SVQ_PER_BV * 917L / 1_000L, ExactRational.of(917),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 80_000.0, 120_000.0);
		ThermalRegistry registry = new ThermalRegistry(List.of(water, ice), List.of(), List.of(melt), "", "");
		ThermalStateFunction state = new ThermalStateFunction(registry);
		ThermalStateFunction.EquilibriumState lower = state.equilibriumState(registry.transitionHandle(melt.id()),
				1.0, Map.of(), 0.0);
		double quarterEnergy = lower.lowerBoundaryEnergy()
				+ 0.25 * (lower.upperBoundaryEnergy() - lower.lowerBoundaryEnergy());
		ThermalStateFunction.EquilibriumState plateau = state.equilibriumState(registry.transitionHandle(melt.id()),
				1.0, Map.of(), quarterEnergy);
		require(plateau.phaseState() == ThermalStateFunction.PhaseState.PLATEAU
				&& close(plateau.temperatureK(), 273.15) && close(plateau.upperPhaseFraction(), 0.25),
				"phase plateau did not derive its fraction from the state-function energy gap");
	}

	private static void coherentPhaseInventoryAndExactBatchPlanning() {
		CoherentPhaseStore store = new CoherentPhaseStore(8, 8);
		CoherentPhaseStore.Composition first = new CoherentPhaseStore.Composition(List.of(
				new CoherentPhaseStore.Component("test:a", 8L),
				new CoherentPhaseStore.Component("test:b", 4L)), Double.doubleToRawLongBits(0.25));
		CoherentPhaseStore.Composition second = new CoherentPhaseStore.Composition(List.of(
				new CoherentPhaseStore.Component("test:a", 8L),
				new CoherentPhaseStore.Component("test:b", 4L)), Double.doubleToRawLongBits(0.25));
		store.add(new CoherentPhaseStore.Group(3, "test:thermal/solid", 8L, 4.0, first, 32.0));
		store.add(new CoherentPhaseStore.Group(3, "test:thermal/solid", 8L, 4.0, second, 32.0));
		CoherentPhaseStore.Group removed = store.take(3, "test:thermal/solid", 8L);
		require(store.groupCount() == 1 && store.group(0).occupiedVolumeSvq() == 8L
				&& removed.occupiedVolumeSvq() == 8L && close(store.totalMass() + removed.mass(), 8.0)
				&& store.exactEnergy().add(SignedDyadic.of(removed.internalEnergy())).equals(SignedDyadic.of(64.0))
				&& removed.composition().stableAmounts().equals(Map.of("test:a", 8L, "test:b", 4L)),
				"coherent phase merge/split did not conserve its independent inventories");

		ThermalMaterialDefinition lower = new ThermalMaterialDefinition("test:thermal/lower",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:solid", 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		ThermalMaterialDefinition upper = new ThermalMaterialDefinition("test:thermal/upper",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "test:liquid", 300.0, 100.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		ThermalPhaseTransition transition = new ThermalPhaseTransition("test:phase/lower_upper",
				lower.id(), upper.id(), 300.0, 1L, 1L, ExactRational.parse("0.1"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 0.0, 200_000.0);
		ThermalRegistry registry = new ThermalRegistry(List.of(lower, upper), List.of(), List.of(transition), "", "");
		ThermalPhaseChangeEngine engine = new ThermalPhaseChangeEngine(registry);
		ThermalPhaseChangeEngine.Plan freeze = engine.planInert(transition, 0.0, 1.0, 50.0, 16L);
		ThermalPhaseChangeEngine.Plan melt = engine.planInert(transition, 1.0, 0.0, 50.0, 16L);
		require(freeze.signedBatches() == 5L && melt.signedBatches() == -5L
				&& close(freeze.temperatureAfterK(), 300.0) && close(melt.temperatureAfterK(), 300.0),
				"exact-batch phase planner did not derive equilibrium extent from conserved energy");

		FluidMaterial liquid = FluidMaterial.withSurfaceTension("test:liquid", 1.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(liquid));
		FlipThermalState.MarkerContext carriers = new FlipThermalState.MarkerContext() {
			@Override public int markerCount() { return 1; }
			@Override public int cell(int marker) { return 0; }
			@Override public int fluidMaterialHandle(int marker) { return 0; }
			@Override public double mass(int marker) { return 0.5; }
			@Override public double volume(int marker) { return 0.5; }
			@Override public ChemicalMixtureRecord mixture(int marker) { return null; }
			@Override public ChemicalRegistry chemicalRegistry() { return null; }
		};
		FlipThermalState coupled = new FlipThermalState(registry, liquids, 300.0,
				1, 2, 2, 2, 0.5F, carriers);
		coupled.addCoherentExternal(new CoherentPhaseStore.Group(
				0, lower.id(), 1L, 0.5, null, 0.0));
		require(close(coupled.temperatureInCell(0, carriers), 300.0),
				"liquid/coherent LTE did not derive a shared temperature");
		coupled.setTemperatureInCell(0, 310.0, carriers);
		require(close(coupled.temperatureInCell(0, carriers), 310.0)
				&& coupled.exactEnergy(1).equals(coupled.audit().expectedInventory()),
				"liquid/coherent heat exchange did not close the shared energy audit");

		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(2, 2, 2, config, 128);
		domain.setVolume(0, 0, 0, 0.5F);
		domain.bindThermal(registry, liquids, 300.0);
		domain.addCoherentPhaseExternal(0, 0, 0, lower.id(),
				SolidVolume.SVQ_PER_CELL / 2L, 0.5, null, 0.0);
		domain.setVolume(0, 0, 0, 1.0F);
		require(close(domain.totalVolumeExact(), 0.5)
				&& domain.totalCoherentSolidVolumeSvq() == SolidVolume.SVQ_PER_CELL / 2L,
				"coherent phase did not share the exact pressure/free-surface occupancy field");

		long batchSvq = SolidVolume.SVQ_PER_CELL / 8L;
		ThermalPhaseTransition cellTransition = new ThermalPhaseTransition("test:phase/cell_lower_upper",
				lower.id(), upper.id(), 300.0, batchSvq, batchSvq, ExactRational.parse("0.125"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 0.0, 200_000.0);
		ThermalRegistry cellRegistry = new ThermalRegistry(
				List.of(lower, upper), List.of(), List.of(cellTransition), "", "");
		FlipFluidDomain phaseDomain = new FlipFluidDomain(2, 2, 2, config, 128);
		phaseDomain.setVolume(0, 0, 0, 1.0F);
		phaseDomain.bindThermal(cellRegistry, liquids, 295.0);
		SignedDyadic coldEnergy = phaseDomain.exactInternalEnergy();
		FlipFluidDomain.ThermalPhaseStats freezeStats = phaseDomain.stepInertPhaseChangeOnly(16);
		require(freezeStats.operations() == 4 && freezeStats.signedBatches() == 4L
				&& close(phaseDomain.totalVolumeExact(), 0.5)
				&& phaseDomain.totalCoherentSolidVolumeSvq() == SolidVolume.SVQ_PER_CELL / 2L
				&& close(phaseDomain.totalMass() + phaseDomain.totalCoherentMass(), 1.0)
				&& close(phaseDomain.thermalTemperatureInCell(0, 0, 0), 300.0)
				&& phaseDomain.exactInternalEnergy().equals(coldEnergy)
				&& phaseDomain.exactInternalEnergy().equals(phaseDomain.thermalAudit().expectedInventory()),
				"domain freeze commit did not conserve exact mass, volume and energy inventories");

		phaseDomain.setThermalTemperatureInCell(0, 0, 0, 305.0);
		SignedDyadic hotEnergy = phaseDomain.exactInternalEnergy();
		FlipFluidDomain.ThermalPhaseStats meltStats = phaseDomain.stepInertPhaseChangeOnly(16);
		require(meltStats.operations() == 4 && meltStats.signedBatches() == -4L
				&& close(phaseDomain.totalVolumeExact(), 1.0)
				&& phaseDomain.totalCoherentSolidVolumeSvq() == 0L
				&& close(phaseDomain.totalMass() + phaseDomain.totalCoherentMass(), 1.0)
				&& close(phaseDomain.thermalTemperatureInCell(0, 0, 0), 300.0)
				&& phaseDomain.exactInternalEnergy().equals(hotEnergy)
				&& phaseDomain.exactInternalEnergy().equals(phaseDomain.thermalAudit().expectedInventory()),
				"domain melt commit did not conserve exact mass, volume and energy inventories");

		FluidMaterial roundoffLiquid = FluidMaterial.withSurfaceTension("test:roundoff_liquid", 1_000.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry roundoffLiquids = new FluidMaterialRegistry(List.of(roundoffLiquid));
		ThermalMaterialDefinition roundoffWater = new ThermalMaterialDefinition("test:thermal/roundoff_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, roundoffLiquid.id(), 293.15, 0.0, 0.598,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 4_184.0)));
		ThermalMaterialDefinition roundoffIce = new ThermalMaterialDefinition("test:thermal/roundoff_ice",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:roundoff_ice", 293.15, -375_070.0, 2.22,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 2_108.0)));
		ThermalPhaseTransition roundoffTransition = new ThermalPhaseTransition("test:phase/roundoff_ice_water",
				roundoffIce.id(), roundoffWater.id(), 273.15, 146L, 134L, ExactRational.parse("0.001"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 10_000.0, 2_000_000.0);
		ThermalRegistry roundoffRegistry = new ThermalRegistry(
				List.of(roundoffIce, roundoffWater), List.of(), List.of(roundoffTransition), "", "");
		FluidSolverConfig roundoffConfig = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain roundoffDomain = new FlipFluidDomain(2, 2, 2, roundoffConfig, 128);
		roundoffDomain.setVolume(0, 0, 0, 0.5F);
		roundoffDomain.bindThermal(roundoffRegistry, roundoffLiquids, 273.1498);
		SignedDyadic roundoffEnergy = roundoffDomain.exactInternalEnergy();
		FlipFluidDomain.ThermalPhaseStats roundoffFreeze = roundoffDomain.stepInertPhaseChangeOnly(16);
		require(roundoffFreeze.operations() == 1
				&& Math.abs(roundoffDomain.exactInternalEnergy().subtract(roundoffEnergy).toDouble())
				<= Math.ulp(roundoffEnergy.toDouble()) * 16.0
				&& roundoffDomain.exactInternalEnergy().equals(roundoffDomain.thermalAudit().expectedInventory()),
				"tiny coherent batch amplified the exact cell-energy remainder into a false LTE failure: stats="
						+ roundoffFreeze + " energy=" + roundoffDomain.exactInternalEnergy() + "/" + roundoffEnergy
						+ " expected=" + roundoffDomain.thermalAudit().expectedInventory());

		FlipFluidDomain quantizedDomain = new FlipFluidDomain(2, 2, 2, roundoffConfig, 128);
		quantizedDomain.bindThermal(roundoffRegistry, roundoffLiquids, 273.15);
		double quantizedIceEnergy = new ThermalStateFunction(roundoffRegistry).internalEnergyAtTemperature(
				roundoffRegistry.materialHandle(roundoffIce.id()), 0.001, Map.of(), 273.16);
		quantizedDomain.addCoherentPhaseExternal(0, 0, 0, roundoffIce.id(), 146L,
				0.001, null, quantizedIceEnergy);
		SignedDyadic quantizedEnergy = quantizedDomain.exactInternalEnergy();
		FlipFluidDomain.ThermalPhaseStats unavailableMelt = quantizedDomain.stepInertPhaseChangeOnly(16);
		require(unavailableMelt.operations() == 0
				&& quantizedDomain.exactInternalEnergy().equals(quantizedEnergy)
				&& quantizedDomain.exactInternalEnergy().equals(quantizedDomain.thermalAudit().expectedInventory()),
				"an unrepresentable exact phase quantum stopped the region instead of leaving inventory unchanged");
		require(FlipFluidDomain.minimumPhaseBatchesForCapacity(1_025L, 1_000L, -12L) == 3L,
				"shrinking phase planning did not clear transient joint-cell overfill before evaluating LTE");
		long markerSvq = SolidVolume.SVQ_PER_CELL / 8L;
		float tailedMarkerVolume = Math.nextUp(SolidVolume.toCellVolume(markerSvq));
		require(SolidVolume.cellVolumeSvqAtMost(tailedMarkerVolume) == markerSvq
				&& SolidVolume.cellVolumeSvqAtLeast(tailedMarkerVolume) == markerSvq + 1L,
				"phase SVQ bounds quantized away an authoritative float marker-volume tail");
	}

	private static void compositionAndGranularPhaseChangeConservesJointTruth() {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(3, 3, 3, config, 256);
		domain.setVolume(1, 1, 1, 0.75F);
		domain.bindGranular(granular, liquids);
		domain.bindChemistry(chemistry);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 1_000_000L), true);
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long sandSvq = granular.material(sand).maximumActiveParcelSvq();
		double sandMass = SolidVolume.toCellVolume(sandSvq)
				* granular.material(sand).referenceDensity();
		require(domain.tryInsertExactGranular(1, 1, 1, sand, 0, sandSvq, sandMass).inserted(),
				"joint phase fixture could not insert its granular heat bath");
		domain.bindThermal(compositionPhaseThermalRegistry(liquids, granular, chemistry), liquids, 295.0);

		CondensedLedger initial = condensedLedger(domain, chemistry);
		long initialGranularSvq = domain.totalGranularSolidVolumeSvq();
		double initialGranularMass = domain.totalGranularMass();
		double initialMass = domain.totalMass() + domain.totalCoherentMass();
		SignedDyadic coldEnergy = domain.exactInternalEnergy();
		FlipFluidDomain.ThermalPhaseStats freeze = domain.stepInertPhaseChangeOnly(16);
		CondensedLedger frozen = condensedLedger(domain, chemistry);
		require(freeze.operations() > 0 && freeze.signedBatches() > 0L
				&& domain.totalCoherentSolidVolumeSvq() > 0L
				&& frozen.species().equals(initial.species())
				&& close(frozen.massResidual(), initial.massResidual())
				&& frozen.indicatorCount() == 1
				&& Float.floatToRawIntBits(frozen.indicatorVolume())
				== Float.floatToRawIntBits(initial.indicatorVolume())
				&& Double.doubleToRawLongBits(frozen.indicatorMass())
				== Double.doubleToRawLongBits(initial.indicatorMass())
				&& domain.totalGranularSolidVolumeSvq() == initialGranularSvq
				&& close(domain.totalGranularMass(), initialGranularMass)
				&& close(domain.totalMass() + domain.totalCoherentMass(), initialMass)
				&& Math.abs(domain.exactInternalEnergy().subtract(coldEnergy).toDouble()) <= 1.0E-8
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"composition/granular freeze did not conserve joint species, indicator, solid and energy truth: stats="
						+ freeze + " initial=" + initial + " frozen=" + frozen
						+ " coherent=" + domain.totalCoherentSolidVolumeSvq()
						+ " granularSvq=" + domain.totalGranularSolidVolumeSvq() + "/" + initialGranularSvq
						+ " granularMass=" + domain.totalGranularMass() + "/" + initialGranularMass
						+ " mass=" + (domain.totalMass() + domain.totalCoherentMass()) + "/" + initialMass
						+ " energy=" + domain.exactInternalEnergy() + "/" + coldEnergy
						+ " expected=" + domain.thermalAudit().expectedInventory());

		domain.setThermalTemperatureInCell(1, 1, 1, 305.0);
		SignedDyadic hotEnergy = domain.exactInternalEnergy();
		FlipFluidDomain.ThermalPhaseStats melt = domain.stepInertPhaseChangeOnly(16);
		CondensedLedger melted = condensedLedger(domain, chemistry);
		require(melt.operations() > 0 && melt.signedBatches() < 0L
				&& domain.totalCoherentSolidVolumeSvq() == 0L
				&& melted.species().equals(initial.species())
				&& close(melted.massResidual(), initial.massResidual())
				&& melted.indicatorCount() == 1
				&& domain.totalGranularSolidVolumeSvq() == initialGranularSvq
				&& close(domain.totalGranularMass(), initialGranularMass)
				&& close(domain.totalMass() + domain.totalCoherentMass(), initialMass)
				&& Math.abs(domain.exactInternalEnergy().subtract(hotEnergy).toDouble()) <= 1.0E-8
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"composition/granular melt did not restore joint species, solid and energy truth");
	}

	private static ThermalRegistry compositionPhaseThermalRegistry(
			FluidMaterialRegistry liquids, GranularRegistry granular, ChemicalRegistry chemistry
	) {
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("test:thermal/composition_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				300.0, 100.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		ThermalMaterialDefinition ice = new ThermalMaterialDefinition("test:thermal/composition_ice",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:composition_ice",
				300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		java.util.ArrayList<ThermalMaterialDefinition> materials = new java.util.ArrayList<>();
		materials.add(water); materials.add(ice);
		materials.add(new ThermalMaterialDefinition("test:thermal/composition_air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
				300.0, 0.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 718.0))));
		for (int handle = 0; handle < granular.materials().size(); handle++) materials.add(
				new ThermalMaterialDefinition("test:thermal/composition_granular_" + handle,
						ThermalMaterialDefinition.CarrierKind.GRANULAR, granular.material(handle).id(),
						300.0, 0.0, 1.0,
						List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0))));
		java.util.ArrayList<ThermalSpeciesOffset> offsets = new java.util.ArrayList<>();
		for (int species = 0; species < chemistry.species().size(); species++) {
			String speciesId = chemistry.species(species).id();
			offsets.add(new ThermalSpeciesOffset("test:composition_offset/water_" + species,
					speciesId, water.id(), ExactRational.ZERO));
			offsets.add(new ThermalSpeciesOffset("test:composition_offset/ice_" + species,
					speciesId, ice.id(), ExactRational.ZERO));
		}
		long batchSvq = SolidVolume.SVQ_PER_CELL / 8L;
		ThermalPhaseTransition transition = new ThermalPhaseTransition(
				"test:phase/composition_ice_water", ice.id(), water.id(), 300.0,
				batchSvq, batchSvq, ExactRational.of(125),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 0.0, 200_000.0);
		return new ThermalRegistry(materials, offsets, List.of(transition),
				chemistry.fingerprint(), granular.fingerprint());
	}

	private static CondensedLedger condensedLedger(FlipFluidDomain domain, ChemicalRegistry chemistry) {
		FlipChemicalParticleState liquid = domain.exportChemicalParticleState();
		java.util.TreeMap<String, Long> species = new java.util.TreeMap<>();
		long[] liquidTotals = liquid.copySpeciesTotals();
		for (int handle = 0; handle < liquidTotals.length; handle++) if (liquidTotals[handle] > 0L) {
			species.put(chemistry.species(handle).id(), liquidTotals[handle]);
		}
		double residual = liquid.residualLedger();
		for (CoherentPhaseStore.Group group : domain.exportThermalParticleState().coherentGroups()) {
			if (group.composition() == null) continue;
			for (Map.Entry<String, Long> entry : group.composition().stableAmounts().entrySet()) {
				species.merge(entry.getKey(), entry.getValue(), Math::addExact);
			}
			residual += group.composition().massResidual();
		}
		byte[] indicators = liquid.copyIndicator();
		float[] positionVolume = liquid.materials().copyPositionVolume();
		double[] mass = liquid.materials().copyParticleMass();
		int indicatorCount = 0; float indicatorVolume = 0.0F; double indicatorMass = 0.0;
		for (int marker = 0; marker < indicators.length; marker++) if (indicators[marker] != 0) {
			indicatorCount++; indicatorVolume += positionVolume[marker * 4 + 3]; indicatorMass += mass[marker];
		}
		return new CondensedLedger(Map.copyOf(species), residual,
				indicatorCount, indicatorVolume, indicatorMass);
	}

	private record CondensedLedger(
			Map<String, Long> species, double massResidual,
			int indicatorCount, float indicatorVolume, double indicatorMass
	) { }

	private static void phaseVolumeWorkWaitsForMechanicalProjection() {
		FluidMaterial liquid = FluidMaterial.withSurfaceTension("test:pending_liquid", 1.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(liquid));
		ThermalMaterialDefinition lower = new ThermalMaterialDefinition("test:thermal/pending_solid",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:pending_solid",
				300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 1.0E9)));
		ThermalMaterialDefinition upper = new ThermalMaterialDefinition("test:thermal/pending_liquid",
				ThermalMaterialDefinition.CarrierKind.LIQUID, liquid.id(),
				300.0, 100.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 1.0E9)));
		long batchSvq = SolidVolume.SVQ_PER_CELL / 8L;
		ThermalPhaseTransition transition = new ThermalPhaseTransition("test:phase/pending_volume",
				lower.id(), upper.id(), 300.0, batchSvq * 2L, batchSvq,
				ExactRational.parse("0.125"), ThermalPhaseTransition.InterfaceRequirement.NONE,
				0.0, 10_000_000.0);
		ThermalRegistry registry = new ThermalRegistry(List.of(lower, upper), List.of(), List.of(transition), "", "");
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(2, 2, 2, config, 128);
		domain.setVolume(0, 0, 0, 0.5F);
		domain.bindThermal(registry, liquids, 295.0);
		ThermalAuditLedger before = domain.thermalAudit();
		FlipFluidDomain.ThermalPhaseStats phase = domain.stepInertPhaseChangeOnly(16);
		PendingPhaseVolumeState pending = domain.exportPendingPhaseVolumeState();
		SignedDyadic signedCellVolume = pending.entries().stream()
				.map(PendingPhaseVolumeState.Entry::signedCellVolume)
				.reduce(SignedDyadic.ZERO, SignedDyadic::add);
		require(phase.operations() == 4 && signedCellVolume.equals(SignedDyadic.of(0.5))
				&& domain.hasPendingPhaseVolumeWork()
				&& domain.thermalAudit().mechanicalWork().equals(before.mechanicalWork()),
				"phase volume was not retained without prematurely booking projection work");

		domain.step();
		ThermalAuditLedger after = domain.thermalAudit();
		require(!domain.hasPendingPhaseVolumeWork()
				&& after.mechanicalWork().compareTo(before.mechanicalWork()) < 0
				&& domain.exactInternalEnergy().equals(after.expectedInventory()),
				"next mechanical projection did not consume expansion exactly once");
		domain.step();
		require(!domain.hasPendingPhaseVolumeWork()
				&& domain.thermalAudit().mechanicalWork().equals(after.mechanicalWork()),
				"cleared phase volume repeated its mechanical work on a later projection");
	}

	private static void exactDyadicAuditDoesNotLoseCancellationRemainders() {
		SignedDyadic exact = SignedDyadic.sum(new double[]{1.0E16, 1.0, -1.0E16});
		require(exact.equals(SignedDyadic.of(1.0)), "exact dyadic audit lost a cancellation remainder");
		for (double value : new double[]{0.0, -0.0, Double.MIN_VALUE, -17.25, Double.MAX_VALUE}) {
			require(Double.doubleToRawLongBits(SignedDyadic.of(value).toDouble())
					== Double.doubleToRawLongBits(value == 0.0 ? 0.0 : value),
					"dyadic conversion did not round-trip " + value);
		}
	}

	private static void operatorEmptyClearsAuditedCondensedCarriers() {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(3, 3, 3, config, 256);
		domain.setVolume(1, 1, 1, 0.75F);
		domain.bindGranular(granular, liquids);
		domain.bindChemistry(chemistry);
		domain.bindThermal(compositionPhaseThermalRegistry(liquids, granular, chemistry), liquids, 295.0);
		CondensedLedger before = condensedLedger(domain, chemistry);
		require(domain.stepInertPhaseChangeOnly(16).operations() > 0
				&& domain.totalCoherentSolidVolumeSvq() > 0L,
				"operator-empty fixture did not create coherent condensed inventory");
		// The operator command rejects this phase event until a real mechanical projection consumes it.
		domain.step(false, 0);
		require(!domain.hasPendingPhaseVolumeWork(),
				"operator-empty fixture did not consume pending phase work");
		long coherentSvq = domain.totalCoherentSolidVolumeSvq();
		FlipFluidDomain.OperatorClear cleared = domain.commitOperatorClear(domain.planOperatorClear());
		long initialQuanta = before.species().values().stream().mapToLong(Long::longValue).sum();
		require(domain.particleCount() == 0 && domain.totalCoherentSolidVolumeSvq() == 0L
				&& cleared.condensed().coherentVolumeSvq() == coherentSvq
				&& cleared.condensed().coherentGroups() > 0
				&& domain.chemicalParcels().escapedSink().totalQuanta() == initialQuanta
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"operator empty did not jointly clear/audit liquid and coherent carriers: cleared=" + cleared
						+ " U=" + domain.exactInternalEnergy()
						+ " expected=" + domain.thermalAudit().expectedInventory());
	}

	private static void containerTransferCarriesAuthoritativeEnergy() {
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("realwater:thermal/container_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 400.0, 4_000.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(water));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(4, 3, 3, config, 512);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.bindThermal(registry, liquids, 300.0);
		domain.removeAllWaterExact();

		double importedEnergy = 1_000.0 * 4_000.0 * 20.0;
		FlipFluidDomain.ExactVolumeTransfer inserted = domain.tryInsertExactVolume(
				1, 1, 1, 1.0F, 1, 0, false, importedEnergy);
		require(inserted.transferred() && close(inserted.internalEnergy(), importedEnergy)
				&& close(domain.thermalTemperatureInCell(1, 1, 1), 320.0)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"explicit container insertion did not assign item U through the state function");

		FlipFluidDomain.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(1, 1, 1, 1.0F, 1, 0);
		require(extracted.transferred() && close(extracted.internalEnergy(), importedEnergy)
				&& domain.particleCount() == 0 && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"container extraction did not return the exact removed marker energy");
		FlipFluidDomain.ExactVolumeTransfer restored = domain.tryInsertExactVolume(
				1, 1, 1, 1.0F, 1, 0, false, extracted.internalEnergy());
		require(restored.transferred() && close(domain.thermalTemperatureInCell(1, 1, 1), 320.0)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"container energy did not survive an extract/reinsert round trip");

		FlipFluidDomain.MaterialDisplacement collision = domain.setSolidMicroMaskMaterial(1, 1, 1, 0xff);
		require(close(collision.internalEnergy(), importedEnergy) && collision.portions().size() == 1
				&& domain.particleCount() == 0,
				"collision displacement did not carry the removed material energy ledger");
		domain.setSolidMicroMaskMaterial(1, 1, 1, 0);
		FlipFluidDomain.ExactMixtureTransfer relocated = domain.tryInsertExactMixture(
				1, 1, 1, collision.portions(), 1, false, collision.internalEnergy());
		require(relocated.transferred() && close(domain.thermalTemperatureInCell(1, 1, 1), 320.0)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"collision relocation did not restore material U without a guessed temperature");
	}

	private static void granularEnergyColumnTransactions() {
		GranularEnergyColumn column = new GranularEnergyColumn(8, 8);
		column.addActive(10.0); column.addActive(20.0); column.addActive(30.0);
		GranularEnergyColumn.DepositKey first = new GranularEnergyColumn.DepositKey(1, 2, 0);
		GranularEnergyColumn.DepositKey second = new GranularEnergyColumn.DepositKey(2, 2, 0);
		column.addDeposit(first, 5.0); column.addDeposit(first, 5.0);
		SignedDyadic initial = column.exactEnergy();
		column.mergeActive(2, 0);
		double removedActive = column.takeActivePortion(0, 1L, 4L);
		double removedDeposit = column.takeDeposit(first, 1L, 4L);
		column.moveDeposit(first, second, 1L, 3L);
		SignedDyadic removed = SignedDyadic.of(removedActive).add(SignedDyadic.of(removedDeposit));
		require(column.activeCount() == 2 && close(column.activeEnergy(0), 30.0)
				&& close(column.activeEnergy(1), 20.0)
				&& close(column.depositEnergy(first), 5.0)
				&& close(column.depositEnergy(second), 2.5)
				&& column.exactEnergy().add(removed).equals(initial),
				"granular thermal split/merge/move did not conserve raw energy");
		GranularEnergyColumn restored = new GranularEnergyColumn(8, 8);
		restored.restore(column.copyActiveEnergy(), column.depositSnapshot());
		require(restored.exactEnergy().equals(column.exactEnergy())
				&& restored.clear().equals(column.exactEnergy()),
				"granular thermal column handoff/clear did not preserve exact energy");

		String granularFingerprint = "2".repeat(64);
		ThermalMaterialDefinition liquid = material("test:thermal/granular_host", "test:liquid", 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		ThermalMaterialDefinition solid = new ThermalMaterialDefinition("test:thermal/granular",
				ThermalMaterialDefinition.CarrierKind.GRANULAR, "test:sand", 300.0, 20.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(liquid, solid), List.of(), List.of(), "", granularFingerprint);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(
				FluidMaterial.withSurfaceTension("test:liquid", 1.0, "test:miscible", 0.0, 90.0)));
		GranularEnergyColumn.DepositKey deposit = new GranularEnergyColumn.DepositKey(0, 0, 0);
		FlipThermalState.MarkerContext carriers = new FlipThermalState.MarkerContext() {
			@Override public int markerCount() { return 0; }
			@Override public int cell(int marker) { throw new IllegalArgumentException(); }
			@Override public int fluidMaterialHandle(int marker) { throw new IllegalArgumentException(); }
			@Override public double mass(int marker) { throw new IllegalArgumentException(); }
			@Override public double volume(int marker) { throw new IllegalArgumentException(); }
			@Override public ChemicalMixtureRecord mixture(int marker) { throw new IllegalArgumentException(); }
			@Override public ChemicalRegistry chemicalRegistry() { return null; }
			@Override public int granularMaterialCount() { return 1; }
			@Override public String granularRegistryFingerprint() { return granularFingerprint; }
			@Override public String granularMaterialId(int materialHandle) { return "test:sand"; }
			@Override public int activeParcelCount() { return 1; }
			@Override public int activeParcelCell(int ordinal) { return 0; }
			@Override public int activeParcelMaterial(int ordinal) { return 0; }
			@Override public int activeParcelComposition(int ordinal) { return 0; }
			@Override public long activeParcelSvq(int ordinal) { return SolidVolume.SVQ_PER_CELL / 2L; }
			@Override public double activeParcelMass(int ordinal) { return 0.5; }
			@Override public List<GranularEnergyColumn.DepositKey> depositKeys() { return List.of(deposit); }
			@Override public long depositSvq(GranularEnergyColumn.DepositKey key) { return SolidVolume.SVQ_PER_CELL / 4L; }
			@Override public double depositMass(GranularEnergyColumn.DepositKey key) { return 0.25; }
		};
		FlipThermalState granularThermal = new FlipThermalState(registry, liquids, 300.0,
				1, 2, 2, 2, 0.5F, carriers);
		require(granularThermal.exactEnergy(0).equals(SignedDyadic.of(15.0))
				&& close(granularThermal.temperatureInCell(0, carriers), 300.0),
				"M8 active/deposit energy did not join the LTE state function");
		granularThermal.setTemperatureInCell(0, 310.0, carriers);
		require(close(granularThermal.temperatureInCell(0, carriers), 310.0)
				&& granularThermal.exactEnergy(0).equals(granularThermal.audit().expectedInventory()),
				"M8 active/deposit cell heat exchange did not close the thermal audit");

		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry productionLiquids = new FluidMaterialRegistry(BuiltinFluidMaterials.M3_PALETTE);
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.8F);
		FlipFluidDomain domain = new FlipFluidDomain(8, 8, 8, config, 8 * 8 * 8 * 8);
		domain.bindGranular(granular, productionLiquids);
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long parcelSvq = SolidVolume.SVQ_PER_BV;
		double parcelMass = SolidVolume.toCellVolume(parcelSvq) * granular.material(sand).referenceDensity();
		GranularTransferLedger bucket = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
				BuiltinGranularDefinitions.FINE_SAND, parcelSvq, parcelMass, null)));
		require(domain.tryInsertExactGranular(4, 6, 4, bucket, 3).inserted(),
				"granular thermal domain fixture could not insert its parcel");
		ThermalRegistry productionThermal = granularThermalRegistry(productionLiquids, granular);
		domain.bindThermal(productionThermal, productionLiquids, 300.0);
		SignedDyadic insertedEnergy = domain.exactInternalEnergy();
		FlipFluidDomain.GranularExtraction extracted = domain.tryExtractExactGranular(
				4, 6, 4, parcelSvq, 3, false);
		require(extracted.transferred() && close(extracted.internalEnergy(), insertedEnergy.toDouble())
				&& domain.exactInternalEnergy().equals(SignedDyadic.ZERO)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"granular extraction did not move its raw energy to the external sink");
		require(domain.tryInsertExactGranular(4, 6, 4, bucket, 3).inserted()
				&& domain.exactInternalEnergy().equals(insertedEnergy),
				"granular insertion did not restore its explicit-temperature source energy");
		for (int step = 0; step < 180 && domain.granularDepositCellCount() == 0; step++) domain.step();
		require(domain.granularDepositCellCount() > 0
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"active-to-deposit settlement failed: deposits=" + domain.granularDepositCellCount()
						+ " energy=" + domain.exactInternalEnergy()
						+ " expected=" + domain.thermalAudit().expectedInventory());
		domain.removeAllGranularExact();
		require(domain.exactInternalEnergy().equals(SignedDyadic.ZERO)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"granular clear did not move all active/deposit energy to the escaped sink");
	}

	private static ThermalRegistry granularThermalRegistry(
			FluidMaterialRegistry liquids, GranularRegistry granular
	) {
		java.util.ArrayList<ThermalMaterialDefinition> definitions = new java.util.ArrayList<>();
		for (int material = 0; material < liquids.size(); material++) definitions.add(new ThermalMaterialDefinition(
				"test:thermal/liquid_" + material, ThermalMaterialDefinition.CarrierKind.LIQUID,
				liquids.material(material).id(), 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0))));
		for (int material = 0; material < granular.materials().size(); material++) definitions.add(
				new ThermalMaterialDefinition("test:thermal/granular_" + material,
						ThermalMaterialDefinition.CarrierKind.GRANULAR, granular.material(material).id(),
						300.0, 20.0, 1.0,
						List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0))));
		return new ThermalRegistry(definitions, List.of(), List.of(), "", granular.fingerprint());
	}

	private static void liquidTransportAndHandoffCloseEnergy() {
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("realwater:thermal/water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				300.0, 0.0, 1_000.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 4_000.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(water));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(4, 3, 3, config, 512);
		domain.setVolume(1, 1, 1, 1.0F);
		domain.setVolume(2, 1, 1, 1.0F);
		domain.bindThermal(registry, liquids, 300.0);
		domain.setThermalTemperatureInCell(1, 1, 1, 350.0);
		domain.setThermalTemperatureInCell(2, 1, 1, 250.0);
		SignedDyadic before = domain.exactInternalEnergy();
		domain.stepThermalOnly(1_000.0);
		double hot = domain.thermalTemperatureInCell(1, 1, 1);
		double cold = domain.thermalTemperatureInCell(2, 1, 1);
		require(hot < 350.0 && hot > 300.0 && cold > 250.0 && cold < 300.0,
				"pairwise thermal transport was not monotone");
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"transient thermal transport did not close its audit ledger");
		require(Math.abs(domain.exactInternalEnergy().subtract(before).toDouble()) <= 1.0E-6,
				"adiabatic thermal transport changed total internal energy beyond representation residual");

		FlipMaterialParticleState materials = domain.exportMaterialParticleState();
		FlipThermalParticleState thermal = domain.exportThermalParticleState();
		FlipFluidDomain restored = new FlipFluidDomain(4, 3, 3, config, 512);
		restored.restoreMaterialParticleState(materials);
		restored.restoreThermalParticleState(thermal, registry);
		require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
				&& close(restored.thermalTemperatureInCell(1, 1, 1), hot)
				&& close(restored.thermalTemperatureInCell(2, 1, 1), cold),
				"orthogonal thermal handoff did not preserve energy/temperature");
	}

	private static void partialCutFaceUsesPlicThermalContact() {
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("realwater:thermal/partial_contact_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 400.0, 4_000.0)));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(4, 3, 3, config, 512);
		// Two microcells obstruct the shared +x face: the authoritative aperture is exactly one half.
		domain.setSolidMicroMask(1, 1, 1, 0x22);
		domain.setVolume(1, 1, 1, 0.5F);
		domain.setVolume(2, 1, 1, 1.0F);
		domain.bindThermal(new ThermalRegistry(List.of(water)), liquids, 300.0);
		domain.setThermalTemperatureInCell(1, 1, 1, 350.0);
		domain.setThermalTemperatureInCell(2, 1, 1, 250.0);
		ThermalTransport.Stats transport = domain.stepThermalOnly(1_000.0);
		require(transport.evaluatedFaces() == 1 && transport.maximumAbsoluteTransferEu() > 0.0
				&& domain.thermalTemperatureInCell(1, 1, 1) < 350.0
				&& domain.thermalTemperatureInCell(2, 1, 1) > 250.0,
				"partial cut/PLIC contact was dropped from production thermal transport");
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"partial cut/PLIC thermal contact did not close its energy audit");
	}

	private static void productionFixedBoundaryFieldAuditsExchange() {
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("realwater:thermal/production_boundary_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 400.0, 4_000.0)));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(4, 3, 3, config, 512);
		domain.setVolume(0, 1, 1, 1.0F);
		domain.bindThermal(new ThermalRegistry(List.of(water)), liquids, 300.0);
		double[] boundaries = new double[4 * 3 * 3 * 6];
		java.util.Arrays.fill(boundaries, Double.NaN);
		boundaries[((1 * 3 + 1) * 4) * 6] = 350.0; // -x domain wall of cell (0,1,1)
		require(domain.replaceThermalBoundaryTemperatures(boundaries)
				&& !domain.replaceThermalBoundaryTemperatures(boundaries),
				"production thermal boundary field did not detect an actual world edit exactly once");
		ThermalTransport.Stats transport = domain.stepThermalOnly(1.0);
		require(transport.reservoirExchange().compareTo(SignedDyadic.ZERO) > 0
				&& domain.thermalTemperatureInCell(0, 1, 1) > 300.0
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"production fixed boundary did not heat and audit the authoritative carrier");
	}

	private static void fixedBoundaryAuditsAndFailedStepRollsBack() {
		FluidMaterial liquid = FluidMaterial.withSurfaceTension("test:boundary_liquid", 1.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(liquid));
		ThermalMaterialDefinition definition = new ThermalMaterialDefinition("test:thermal/boundary_liquid",
				ThermalMaterialDefinition.CarrierKind.LIQUID, liquid.id(), 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0)));
		FlipThermalState.MarkerContext markers = new FlipThermalState.MarkerContext() {
			@Override public int markerCount() { return 1; }
			@Override public int cell(int marker) { return 0; }
			@Override public int fluidMaterialHandle(int marker) { return 0; }
			@Override public double mass(int marker) { return 1.0; }
			@Override public double volume(int marker) { return 1.0; }
			@Override public ChemicalMixtureRecord mixture(int marker) { return null; }
			@Override public ChemicalRegistry chemicalRegistry() { return null; }
		};
		FlipThermalState thermal = new FlipThermalState(new ThermalRegistry(List.of(definition)), liquids,
				300.0, 1, 2, 2, 2, 0.5F, markers);
		ThermalTransport.Stats heated = thermal.step(markers, (x, y, z, axis) -> 0.0,
				(x, y, z, direction) -> direction == 0
						? new ThermalTransport.FixedBoundary(0.25, 400.0) : null, 1.0);
		require(close(thermal.temperature(0, markers), 310.0)
				&& heated.reservoirExchange().equals(SignedDyadic.of(100.0))
				&& thermal.exactEnergy(1).equals(thermal.audit().expectedInventory()),
				"fixed-temperature boundary did not close source/audit energy");
		SignedDyadic beforeFailure = thermal.exactEnergy(1);
		ThermalAuditLedger auditBeforeFailure = thermal.audit();
		expectFailure(() -> thermal.step(markers, (x, y, z, axis) -> 0.0,
				(x, y, z, direction) -> direction == 0
						? new ThermalTransport.FixedBoundary(0.25, 1_000.0) : null, 10.0),
				"out-of-range fixed-boundary step was accepted");
		require(thermal.exactEnergy(1).equals(beforeFailure) && thermal.audit().equals(auditBeforeFailure),
				"failed thermal transport exposed a partial energy/audit commit");
	}

	private static void reseedingMovesEnergyWithMarkerTruth() {
		int capacity = 64, count = 17;
		boolean[] solid = new boolean[4 * 4 * 4];
		float[] x = new float[capacity], y = new float[capacity], z = new float[capacity];
		float[] vx = new float[capacity], vy = new float[capacity], vz = new float[capacity];
		float[] volume = new float[capacity];
		double[] mass = new double[capacity], energy = new double[capacity];
		int[] material = new int[capacity], phase = new int[capacity];
		for (int marker = 0; marker < 16; marker++) {
			x[marker] = 1.1F + (marker & 3) * 0.02F;
			y[marker] = 1.1F + (marker >> 2 & 3) * 0.02F;
			z[marker] = 1.1F;
			volume[marker] = 1.0F / 64.0F;
			mass[marker] = volume[marker] * 1_000.0;
			energy[marker] = mass[marker] * 7.0;
		}
		x[16] = 2.25F; y[16] = 1.25F; z[16] = 1.25F;
		volume[16] = 0.5F; mass[16] = 500.0; energy[16] = 3_500.0;
		SignedDyadic before = SignedDyadic.sum(java.util.Arrays.copyOf(energy, count));
		FlipParticleReseeder.ThermalAdapter adapter = new FlipParticleReseeder.ThermalAdapter() {
			@Override public double totalEnergy(int particleCount) {
				double total = 0.0;
				for (int marker = 0; marker < particleCount; marker++) total += energy[marker];
				return total;
			}
			@Override public void merge(int target, int source) { energy[target] += energy[source]; }
			@Override public FlipParticleReseeder.ThermalSplit beforeSplit(int source) {
				return new FlipParticleReseeder.ThermalSplit(energy[source], energy[source] / mass[source]);
			}
			@Override public void afterSplit(FlipParticleReseeder.ThermalSplit split, int source, int destination) {
				energy[source] = mass[source] * split.temperatureK();
				energy[destination] = split.parentEnergy() - energy[source];
			}
			@Override public void move(int source, int destination) { energy[destination] = energy[source]; }
		};
		FlipParticleReseeder.Result result = new FlipParticleReseeder(4, 4, 4, 1.0F, capacity).reseed(
				solid, x, y, z, vx, vy, vz, volume, mass, material, phase, null, adapter, count, false,
				(candidateX, candidateY, candidateZ) -> true);
		SignedDyadic after = SignedDyadic.sum(java.util.Arrays.copyOf(energy, result.particleCount()));
		require(result.merged() > 0 && result.split() > 0 && before.equals(after),
				"thermal reseeding did not move energy with merged/split marker truth");
	}

	private static ThermalMaterialDefinition material(
			String id, String carrier, double referenceSpecificEnergy,
			List<ThermalMaterialDefinition.CaloricSegment> segments
	) {
		return new ThermalMaterialDefinition(id, ThermalMaterialDefinition.CarrierKind.LIQUID, carrier,
				300.0, referenceSpecificEnergy, 0.5, segments);
	}

	private static boolean close(double first, double second) {
		return Math.abs(first - second) <= Math.max(1.0E-10,
				Math.max(Math.ulp(first), Math.ulp(second)) * 8.0);
	}

	private static void expectFailure(Runnable action, String message) {
		try { action.run(); }
		catch (RuntimeException expected) { return; }
		throw new AssertionError(message);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
