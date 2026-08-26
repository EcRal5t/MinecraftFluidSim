package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalPhaseDefinition;
import cc.ecisr.mmm.chemistry.ChemicalReaction;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ChemicalSpecies;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.thermal.GasLiquidSpeciesPair;
import cc.ecisr.mmm.thermal.GasThermalModel;
import cc.ecisr.mmm.thermal.GasLiquidPhaseModel;
import cc.ecisr.mmm.thermal.SignedDyadic;
import cc.ecisr.mmm.thermal.GranularEnergyColumn;
import cc.ecisr.mmm.thermal.LiquidThermalExpansion;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.units.SolverUnitScale;
import cc.ecisr.mmm.thermal.FiniteThermalDeviceDefinition;
import cc.ecisr.mmm.thermal.FiniteThermalBodyState;
import cc.ecisr.mmm.thermal.FiniteThermalExchange;
import cc.ecisr.mmm.thermal.ThermalBoundaryDefinition;
import cc.ecisr.mmm.thermal.ThermalCellGrid;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalDefinitionCodec;
import cc.ecisr.mmm.thermal.ThermalDisplaySnapshot;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;
import cc.ecisr.mmm.thermal.ThermalPhaseChangeEngine;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalRegistryStore;
import cc.ecisr.mmm.thermal.ThermalSpeciesOffset;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.simulation.ThermalActivity;
import cc.ecisr.mmm.simulation.FluidRegionLifecycle;

import java.io.StringReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
		builtinWaterAndVaporPhysicsIsCalibrated();
		builtinCo2GasLiquidPairDataIsFrozen();
		builtinFiniteThermalDeviceDataIsFrozen();
		gasLiquidSaturationAndExactBatch();
		gasRuntimeTransfersEnergyAndWork();
		persistenceBarrierSynchronizesGasTopology();
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
		localizedHeatingDrivesBoussinesqCirculation();
		partialCutFaceUsesPlicThermalContact();
		finiteThermalBodyExchangesWithoutMixing();
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
		ThermalMaterialDefinition air = new ThermalMaterialDefinition("mmm:thermal/air",
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

	private static void builtinWaterAndVaporPhysicsIsCalibrated() {
		try (InputStreamReader waterReader = resourceReader(
				"/data/mmm/mmm/thermal_materials/water.json");
		     InputStreamReader vaporReader = resourceReader(
				     "/data/mmm/mmm/thermal_materials/water_vapor.json");
		     InputStreamReader meltingReader = resourceReader(
				     "/data/mmm/mmm/phase_transitions/ice_water.json");
		     InputStreamReader transitionReader = resourceReader(
				     "/data/mmm/mmm/phase_transitions/water_vapor.json")) {
			ThermalMaterialDefinition water = ThermalDefinitionCodec.decodeMaterial("mmm:water", waterReader);
			ThermalMaterialDefinition vapor = ThermalDefinitionCodec.decodeMaterial("mmm:water_vapor", vaporReader);
			ThermalPhaseTransition transition = ThermalDefinitionCodec.decodeTransition(
					"mmm:water_vapor", transitionReader);
			ThermalPhaseTransition melting = ThermalDefinitionCodec.decodeTransition(
					"mmm:ice_water", meltingReader);
			double batchMassKg = transition.massMuPerBatch().toDouble();
			double liquidVolume = (double) transition.lowerVolumeSvqPerBatch() / SolidVolume.SVQ_PER_BV;
			double vaporVolume = (double) transition.upperVolumeSvqPerBatch() / SolidVolume.SVQ_PER_BV;
			double standardVaporVolume = vaporVolume * GasRegistry.STANDARD_TEMPERATURE_K
					/ transition.equilibriumTemperatureK(GasRegistry.STANDARD_PRESSURE_PA);
			double liquidDensity = batchMassKg / liquidVolume;
			double vaporDensityAtBoiling = batchMassKg / vaporVolume;
			double gasConstant = GasRegistry.STANDARD_PRESSURE_PA * standardVaporVolume
					/ (batchMassKg * GasRegistry.STANDARD_TEMPERATURE_K);
			ThermalRegistry registry = new ThermalRegistry(List.of(water, vapor), List.of(),
					List.of(transition), "", "");
			ThermalStateFunction state = new ThermalStateFunction(registry);
			double liquidU = state.internalEnergyAtTemperature(
					registry.materialHandle(water.id()), batchMassKg, Map.of(),
					transition.equilibriumTemperatureK(GasRegistry.STANDARD_PRESSURE_PA));
			double vaporU = state.internalEnergyAtTemperature(
					registry.materialHandle(vapor.id()), batchMassKg, Map.of(),
					transition.equilibriumTemperatureK(GasRegistry.STANDARD_PRESSURE_PA));
			double latentEnthalpy = (vaporU - liquidU + GasRegistry.STANDARD_PRESSURE_PA
					* (vaporVolume - liquidVolume)) / batchMassKg;
			double fourCellReservoirConductance = 4.0 * 2.0 * water.conductivityEuPerSecondMeterK()
					* 0.25 / 0.5;
			double extremeReservoirPower = fourCellReservoirConductance * (10_000_000.0 - 293.15);
			double maximumPhaseMassPerSimulatedSecond = ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP
					* batchMassKg / 0.05;

			require(relativeError(liquidDensity, 1_000.0) < 0.002
					&& relativeError(vaporDensityAtBoiling, 0.588) < 0.001
					&& relativeError(gasConstant, 461.52) < 1.0E-5,
					"built-in phase volumes do not reproduce water/vapor SI density and gas constant");
			require(water.specificHeatAt(293.15) == 4_184.0
					&& water.conductivityEuPerSecondMeterK() == 0.598
					&& vapor.specificHeatAt(373.15) == 1_410.0
					&& vapor.conductivityEuPerSecondMeterK() == 0.025,
					"built-in water/vapor caloric or conductivity values drifted");
			require(Math.abs(latentEnthalpy - 2_256_700.0) < 0.01,
					"water/vapor state-function gap does not reproduce the 1-atm latent enthalpy: " + latentEnthalpy);
			require(close(melting.equilibriumTemperatureK(101_325.0), 273.15251907976949)
					&& close(melting.equilibriumTemperatureK(2_000_000.0), 273.01088655470164)
					&& melting.equilibriumTemperatureK(1_000_000.0)
					< melting.equilibriumTemperatureK(101_325.0),
					"IAPWS ice-Ih pressure curve did not lower the melting point");
			expectFailure(() -> melting.equilibriumTemperatureK(2_000_001.0),
					"solid/liquid equilibrium curve extrapolated outside its declared pressure range");
			require(Math.abs(fourCellReservoirConductance - 2.392) < 1.0E-12
					&& Math.abs(extremeReservoirPower - 23_919_298.7852) < 1.0E-6
					&& Math.abs(maximumPhaseMassPerSimulatedSecond - 81.92) < 1.0E-12,
					"production boundary flux or exact phase throughput scale drifted");
		} catch (IOException failure) {
			throw new AssertionError("Could not read built-in thermal definitions", failure);
		}
	}

	private static InputStreamReader resourceReader(String path) {
		var stream = M9ThermalValidation.class.getResourceAsStream(path);
		if (stream == null) throw new AssertionError("Missing validation resource " + path);
		return new InputStreamReader(stream, StandardCharsets.UTF_8);
	}

	private static String resourceText(String path) throws IOException {
		try (var stream = M9ThermalValidation.class.getResourceAsStream(path)) {
			if (stream == null) throw new AssertionError("Missing validation resource " + path);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void builtinCo2GasLiquidPairDataIsFrozen() {
		java.nio.file.Path root = null;
		String pairPath = "/data/mmm/mmm/gas_liquid_pairs/co2_water.json";
		try (InputStreamReader pairReader = resourceReader(pairPath);
		     InputStreamReader waterReader = resourceReader("/data/mmm/mmm/thermal_materials/water.json");
		     InputStreamReader co2Reader = resourceReader("/data/mmm/mmm/thermal_materials/co2.json");
		     InputStreamReader gasOffsetReader = resourceReader("/data/mmm/mmm/thermal_species/co2.json");
		     InputStreamReader liquidOffsetReader = resourceReader(
				     "/data/mmm/mmm/thermal_species/water_co2.json")) {
			GasLiquidSpeciesPair pair = ThermalDefinitionCodec.decodePair("mmm:co2_water", pairReader);
			ThermalMaterialDefinition water = ThermalDefinitionCodec.decodeMaterial("mmm:water", waterReader);
			ThermalMaterialDefinition co2 = ThermalDefinitionCodec.decodeMaterial("mmm:co2", co2Reader);
			ThermalSpeciesOffset gasOffset = ThermalDefinitionCodec.decodeSpecies("mmm:co2", gasOffsetReader);
			ThermalSpeciesOffset liquidOffset = ThermalDefinitionCodec.decodeSpecies(
					"mmm:water_co2", liquidOffsetReader);
			GasRegistry gas = BuiltinGasDefinitions.createRegistry();
			ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
			var gasComponent = gas.component(pair.gasComponentId());
			var liquidPhase = chemistry.phase(chemistry.phaseHandle(pair.liquidPhaseId()));
			double gasKilograms = gasComponent.standardMassPerCubicMeter()
					* AirNetwork.cubicMeters(pair.gasStandardVolumeNanosPerBatch());
			double liquidKilograms = chemistry.species(chemistry.speciesHandle(pair.speciesId()))
					.massKilogramsPerAu().multiply(pair.liquidQuantaPerBatch())
					.divide(ChemicalLimits.QUANTA_PER_AU).toDouble();

			require(pair.gasComponentId().equals(BuiltinGasDefinitions.CO2)
					&& pair.speciesId().equals(BuiltinChemicalDefinitions.CO2)
					&& gasComponent.allowsSpecies(pair.speciesId())
					&& liquidPhase.allowedSpecies().contains(pair.speciesId()),
					"built-in CO2 pair is not admitted by both frozen phase registries");
			require(Double.doubleToRawLongBits(pair.gasKilogramsPerBatch())
						== Double.doubleToRawLongBits(gasKilograms)
					&& Double.doubleToRawLongBits(pair.liquidKilogramsPerBatch())
						== Double.doubleToRawLongBits(liquidKilograms)
					&& Double.doubleToRawLongBits(pair.liquidResidualKilogramsPerBatch())
						== Double.doubleToRawLongBits(gasKilograms - liquidKilograms),
					"built-in CO2 exact batch does not close nanos/quanta/kg/residual bitwise");
			require(gasOffset.speciesId().equals(pair.speciesId())
					&& gasOffset.hostThermalMaterialId().equals(co2.id())
					&& liquidOffset.speciesId().equals(pair.speciesId())
					&& liquidOffset.hostThermalMaterialId().equals(water.id())
					&& liquidOffset.referenceInternalEnergyEuPerAu().equals(ExactRational.of(-19_400_000L)),
					"built-in CO2 thermal hosts or dissolved state-function offset drifted");
			require(close(pair.henryConstantMolPerCubicMeterPaAt(293.15), 3.9E-4)
					&& close(pair.henryConstantMolPerCubicMeterPaAt(288.15), 4.7E-4),
					"built-in CO2 Henry table did not preserve knots or linear interpolation");
			expectFailure(() -> pair.henryConstantMolPerCubicMeterPaAt(273.149),
					"CO2 Henry model extrapolated below its declared temperature domain");
			expectFailure(() -> pair.henryConstantMolPerCubicMeterPaAt(363.151),
					"CO2 Henry model extrapolated above its declared temperature domain");
			require(Double.doubleToRawLongBits(pair.totalResistanceSecondsPerMeter())
					== Double.doubleToRawLongBits(pair.liquidFilmThicknessMeters()
					/ pair.liquidDiffusionSquareMetersPerSecond() + pair.gasResistanceSecondsPerMeter()),
					"CO2 pair did not use the declared series film resistance");

			ThermalRegistry registry = new ThermalRegistry(List.of(water, co2),
					List.of(gasOffset, liquidOffset), List.of(), List.of(), List.of(pair),
					chemistry.fingerprint(), "", gas.fingerprint());
			require(registry.fingerprint().equals("3ad148b10e255ec1415773bccf7eda9c07caf0a5fcd1e4be54de46153576dae4"),
					"built-in CO2 pair registry fingerprint drifted: " + registry.fingerprint());
			root = java.nio.file.Files.createTempDirectory("mmm-co2-pair-registry-");
			ThermalRegistryStore store = new ThermalRegistryStore(
					root, chemistry.fingerprint(), "", gas.fingerprint());
			ThermalRegistryStore.LoadResult published = store.loadOrCreate(registry);
			require(published.created() && published.registry().gasLiquidPairs().equals(List.of(pair))
					&& published.registry().fingerprint().equals(registry.fingerprint()),
					"CO2 pair did not round-trip through the authoritative thermal snapshot");

			String json = resourceText(pairPath);
			expectFailure(() -> ThermalDefinitionCodec.decodePair("mmm:co2_water",
					new StringReader("{\"unknown\":\"rejected\"," + json.substring(1))),
					"unknown CO2 pair JSON field was ignored");
			expectFailure(() -> ThermalDefinitionCodec.decodePair("mmm:co2_water", new StringReader(
					json.replace("\"gas_standard_volume_nanos_per_batch\":\"24055\"",
							"\"gas_standard_volume_nanos_per_batch\":24055"))),
					"numeric CO2 pair amount bypassed the exact string codec");
			expectFailure(() -> ThermalDefinitionCodec.decodePair("mmm:co2_water", new StringReader(
					json.replace("\"liquid_residual_kilograms_per_batch\":\"-2.1376376542351153E-10\"",
							"\"liquid_residual_kilograms_per_batch\":\"0.0\""))),
					"non-closing CO2 pair residual was accepted");
		} catch (IOException failure) {
			throw new AssertionError("Could not validate built-in CO2 gas/liquid pair data", failure);
		} finally {
			if (root != null) try (var paths = java.nio.file.Files.walk(root)) {
				for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					java.nio.file.Files.deleteIfExists(path);
				}
			} catch (IOException cleanup) {
				throw new AssertionError("CO2 pair registry store cleanup failed", cleanup);
			}
		}
	}

	private static void builtinFiniteThermalDeviceDataIsFrozen() {
		java.nio.file.Path root = null;
		String devicePath = "/data/mmm/mmm/finite_thermal_devices/finite_heat_exchanger.json";
		try (InputStreamReader deviceReader = resourceReader(devicePath);
		     InputStreamReader materialReader = resourceReader(
				     "/data/mmm/mmm/thermal_materials/copper_heat_exchanger.json")) {
			FiniteThermalDeviceDefinition device = ThermalDefinitionCodec.decodeFiniteDevice(
					"mmm:finite_heat_exchanger", deviceReader);
			ThermalMaterialDefinition material = ThermalDefinitionCodec.decodeMaterial(
					"mmm:copper_heat_exchanger", materialReader);
			require(material.carrierKind() == ThermalMaterialDefinition.CarrierKind.DEVICE
					&& material.carrierId().equals(device.id())
					&& material.conductivityEuPerSecondMeterK() == 394.0
					&& material.specificHeatAt(293.15) == 385.0
					&& device.bodyMassKilograms() == 16.0
					&& device.initialTemperatureK() == 293.15,
					"built-in finite exchanger copper body definition drifted");
			require(device.ports().size() == 2
					&& device.ports().get(0).contactGroupId().equals("mmm:circuit_a")
					&& device.ports().get(0).localFace() == FiniteThermalDeviceDefinition.LocalFace.NORTH
					&& device.ports().get(1).contactGroupId().equals("mmm:circuit_b")
					&& device.ports().get(1).localFace() == FiniteThermalDeviceDefinition.LocalFace.SOUTH
					&& device.ports().stream().allMatch(port -> port.wallThicknessMeters() == 0.01
							&& port.fluidFilmThicknessMeters() == 0.01),
					"built-in finite exchanger port groups or SI geometry drifted");
			ThermalRegistry registry = new ThermalRegistry(List.of(material), List.of(), List.of(), List.of(),
					List.of(), List.of(device), "", "", "");
			require(registry.fingerprint().equals("3551946f35dc822ac7f6472fa792eaad4b245fac78897782eb25524cedf9973b"),
					"built-in finite exchanger registry fingerprint drifted: " + registry.fingerprint());
			ThermalStateFunction state = new ThermalStateFunction(registry);
			double initialEnergy = state.internalEnergyAtTemperature(registry.materialHandle(material.id()),
					device.bodyMassKilograms(), Map.of(), device.initialTemperatureK());
			require(initialEnergy == 0.0,
					"built-in finite exchanger default body energy is not derived from the material state function");

			root = java.nio.file.Files.createTempDirectory("mmm-finite-device-registry-");
			ThermalRegistryStore store = new ThermalRegistryStore(root, "", "", "");
			ThermalRegistryStore.LoadResult published = store.loadOrCreate(registry);
			require(published.created() && published.registry().finiteDevices().equals(List.of(device))
					&& published.registry().fingerprint().equals(registry.fingerprint()),
					"finite exchanger definition did not round-trip through the thermal snapshot");
			var registryTag = net.minecraft.nbt.NbtIo.readCompressed(store.registryPath(),
					net.minecraft.nbt.NbtAccounter.create(8L * 1024L * 1024L));
			var encodedDevices = registryTag.getListOrEmpty("FiniteDevices");
			((net.minecraft.nbt.CompoundTag) encodedDevices.getFirst()).putString("UnknownPortPolicy", "reject");
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, store.registryPath());
			try {
				store.read();
				throw new AssertionError("unknown finite thermal device snapshot field was ignored");
			} catch (IOException expected) { }

			String json = resourceText(devicePath);
			expectFailure(() -> ThermalDefinitionCodec.decodeFiniteDevice("mmm:finite_heat_exchanger",
					new StringReader("{\"unknown\":\"rejected\"," + json.substring(1))),
					"unknown finite thermal device JSON field was ignored");
			expectFailure(() -> ThermalDefinitionCodec.decodeFiniteDevice("mmm:finite_heat_exchanger",
					new StringReader(json.replace("\"body_mass_kg\":\"16.0\"", "\"body_mass_kg\":16.0"))),
					"numeric finite thermal body mass bypassed the strict string codec");
			expectFailure(() -> new FiniteThermalDeviceDefinition("mmm:duplicate_face", material.id(), 1.0, 293.15,
					List.of(device.ports().getFirst(), new FiniteThermalDeviceDefinition.Port("mmm:other",
							"mmm:other_group", FiniteThermalDeviceDefinition.LocalFace.NORTH, 0.01, 0.01))),
					"multiple finite thermal ports claimed the same local face");
		} catch (IOException failure) {
			throw new AssertionError("Could not validate built-in finite thermal device data", failure);
		} finally {
			if (root != null) try (var paths = java.nio.file.Files.walk(root)) {
				for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					java.nio.file.Files.deleteIfExists(path);
				}
			} catch (IOException cleanup) {
				throw new AssertionError("finite thermal device registry cleanup failed", cleanup);
			}
		}
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
				new ThermalMaterialDefinition("mmm:thermal/air",
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

	private static void persistenceBarrierSynchronizesGasTopology() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		ThermalRegistry thermal = new ThermalRegistry(List.of(
				new ThermalMaterialDefinition("test:thermal/persistence_water",
						ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
						300.0, 0.0, 0.598,
						List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 4_184.0))),
				new ThermalMaterialDefinition("test:thermal/persistence_air",
						ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
						GasRegistry.STANDARD_TEMPERATURE_K, 0.0, 0.026,
						List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 718.0)))));

		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, config, 1_024);
		domain.sealDomainBoundary();
		domain.setVolume(0, 0, 0, 0.5F);
		domain.setMarkerMaterial(0, liquids, 0);
		domain.bindGasRegistry(gas);
		domain.bootstrapGasState("");
		domain.bindThermal(thermal, liquids, 300.0);
		require(domain.airNetwork().nodeCount() == 1, "persistence topology fixture did not start as one cavity");

		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) domain.setVolume(2, y, z, 1.0F);
		FlipMaterialParticleState carriers = domain.exportMaterialParticleState();
		GasInventory beforeGas = domain.airNetwork().inventory(1);
		var stale = domain.exportGasState("");
		FlipFluidDomain rejected = new FlipFluidDomain(4, 4, 4, config, 1_024);
		rejected.sealDomainBoundary();
		rejected.restoreMaterialParticleState(carriers);
		expectFailure(() -> rejected.restoreGasState(stale, gas),
				"stale gas topology unexpectedly matched the post-carrier checkpoint geometry");

		domain.synchronizeGasTopologyForPersistence();
		var synchronizedGas = domain.exportGasState("");
		FlipFluidDomain restored = new FlipFluidDomain(4, 4, 4, config, 1_024);
		restored.sealDomainBoundary();
		restored.restoreMaterialParticleState(carriers);
		restored.restoreGasState(synchronizedGas, gas);
		require(restored.airNetwork().nodeCount() == 2
				&& restored.airNetwork().inventory(1).merge(restored.airNetwork().inventory(2)).equals(beforeGas)
				&& restored.airNetwork().auditClosed(),
				"checkpoint gas-topology barrier did not preserve and restore the split inventory");

		FlipFluidDomain movingInterface = new FlipFluidDomain(3, 2, 2, config, 128);
		movingInterface.sealDomainBoundary();
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++) {
			if (!(y == 0 && z == 0 && (x == 0 || x == 1))) movingInterface.setSolid(x, y, z, true);
		}
		movingInterface.setVolume(1, 0, 0, 0.5F);
		movingInterface.bindGasRegistry(gas);
		movingInterface.bootstrapGasState("");
		var sourceBeforeMove = movingInterface.airNetwork().externalSource();
		var sinkBeforeMove = movingInterface.airNetwork().escapedSink();
		movingInterface.setVolume(1, 0, 0, 0.0F);
		movingInterface.setVolume(0, 0, 0, 0.5F);
		movingInterface.synchronizeGasTopologyForPersistence();
		require(movingInterface.airNetwork().externalSource().equals(sourceBeforeMove)
				&& movingInterface.airNetwork().escapedSink().equals(sinkBeforeMove)
				&& movingInterface.airNetwork().auditClosed(),
				"moving a partial interface created false sealed-gas source/sink turnover");
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
		ThermalSpeciesOffset vaporSpecies = new ThermalSpeciesOffset("test:offset/vapor_h2o",
				BuiltinChemicalDefinitions.H2O, vapor.id(), ExactRational.parse("-250"));
		ThermalRegistry thermal = new ThermalRegistry(List.of(liquid, vapor), List.of(vaporSpecies),
				List.of(transition), BuiltinChemicalDefinitions.REGISTRY.fingerprint(), "");
		GasLiquidPhaseModel model = new GasLiquidPhaseModel(thermal, gas);
		GasInventory batch = model.gasBatch(transition, 1L);
		require(model.gasBatch(transition, 3L).standardVolumeNanos()
				== Math.multiplyExact(3L, batch.standardVolumeNanos()),
				"multi-batch gas nanos did not compose from the canonical minimum quantum");
		GasInventory speciesBatch = model.gasBatch(transition, 1L,
				Map.of(BuiltinChemicalDefinitions.H2O, ChemicalLimits.QUANTA_PER_AU), 0.0005);
		double expectedSpeciesEnergy = new ThermalStateFunction(thermal).internalEnergyAtTemperature(
				thermal.materialHandle(vapor.id()), 0.001,
				Map.of(BuiltinChemicalDefinitions.H2O, ChemicalLimits.QUANTA_PER_AU), 373.15);
		require(close(model.internalEnergyAtTemperature(transition, speciesBatch, 373.15), expectedSpeciesEnergy),
				"gas phase energy omitted component-local species state energy");
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
				"test:boiling_water", 1_000.0, "test:miscible", 0.0, 90.0);
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
		long liquidSvq = SolidVolume.SVQ_PER_CELL / 20_000L;
		long vaporSvq = liquidSvq * 1_700L;
		ExactRational batchMass = ExactRational.parse("52375/8388608");
		ThermalPhaseTransition boiling = new ThermalPhaseTransition("test:phase/boiling_water",
				water.id(), vapor.id(), 373.15, liquidSvq, vaporSvq, batchMass,
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 10_000.0, 3_000_000.0);
		ThermalRegistry thermal = new ThermalRegistry(
				List.of(water, air, vapor), List.of(), List.of(boiling), "", "");
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
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
				"thermal display did not derive quantized condensed/gas state in one rebuild");
		double liquidMassBefore = domain.totalMass();
		double boiledSolverMass = SolverUnitScale.forCellSize(config.cellSize())
				.solverMass(batchMass.toDouble());
		long gasNanosBefore = domain.airNetwork().standardVolumeNanos(1);
		SignedDyadic energyBefore = domain.exactInternalEnergy();
		ThermalAuditLedger auditBefore = domain.thermalAudit();
		domain.step(true, 128);
		FlipFluidDomain.GasThermalPhaseStats stats = domain.lastGasThermalPhaseStats();
		GasComponentAmount vaporAmount = domain.airNetwork().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR);
		require(stats.operations() > 1 && stats.operations() <= 128
				&& stats.boiledCells() == 1 && stats.condensedCells() == 0
				&& close(domain.totalMass(), liquidMassBefore - boiledSolverMass * stats.operations())
				&& vaporAmount != null && close(vaporAmount.mass(), batchMass.toDouble() * stats.operations())
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
		double liquidMassAfterBoiling = domain.totalMass();
		SignedDyadic beforeCondensation = domain.exactInternalEnergy();
		ThermalAuditLedger auditBeforeCondensation = domain.thermalAudit();
		FlipThermalParticleState beforeCondensationState = domain.exportThermalParticleState();
		FlipFluidDomain.GasThermalPhaseStats condensed = domain.stepGasPhaseChangeOnly(stats.operations());
		GasComponentAmount vaporAfterCondensation = domain.airNetwork().inventory(1)
				.component(BuiltinGasDefinitions.WATER_VAPOR);
		long remainingBatches = stats.operations() - condensed.operations();
		double condensedPhysicalMass = (domain.totalMass() - liquidMassAfterBoiling)
				* SolverUnitScale.forCellSize(config.cellSize()).cubicMetersPerNormalizedVolume();
		double removedVaporMass = vaporAmount.mass()
				- (vaporAfterCondensation == null ? 0.0 : vaporAfterCondensation.mass());
		require(condensed.operations() > 1 && condensed.operations() <= stats.operations()
				&& condensed.boiledCells() == 0 && condensed.condensedCells() == 1
				&& Math.abs(condensedPhysicalMass - removedVaporMass) <= 1.0E-9
				&& vaporAfterCondensation != null
				&& Math.abs(vaporAfterCondensation.mass()
						- batchMass.toDouble() * remainingBatches) <= 1.0E-9
				&& domain.airNetwork().standardVolumeNanos(1)
						== gasNanosBefore + vaporAfterCondensation.standardVolumeNanos()
				&& domain.airNetwork().auditClosed()
				&& domain.thermalAudit().mechanicalWork().compareTo(
						auditBeforeCondensation.mechanicalWork()) > 0
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"sealed condensation did not reverse the exact gas batch with conserved energy: stats=" + condensed
						+ " mass=" + domain.totalMass() + " vapor=" + vaporAfterCondensation
						+ " condensedKg=" + condensedPhysicalMass + "/" + removedVaporMass
						+ " expectedVapor=" + (batchMass.toDouble() * remainingBatches)
						+ " nanos=" + domain.airNetwork().standardVolumeNanos(1) + "/" + gasNanosBefore
						+ " workCmp=" + domain.thermalAudit().mechanicalWork().compareTo(
						auditBeforeCondensation.mechanicalWork())
						+ " audit=" + domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
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
				water.id(), vapor.id(), 373.15, liquidSvq, liquidSvq * 500L,
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
		double sandMass = sandSvq / (double) SolidVolume.SVQ_PER_BV
				* granular.material(sand).referenceDensity();
		require(domain.tryInsertExactGranular(1, 1, 1, sand, 0, sandSvq, sandMass).inserted(),
				"gas phase fixture could not insert its granular LTE carrier");
		domain.bindThermal(thermal, liquids, 500.0);
		long initialGranularSvq = domain.totalGranularSolidVolumeSvq();
		FlipChemicalParticleState initial = domain.exportChemicalParticleState();
		long initialH2o = initial.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)];
		long initialSodium = initial.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)];
		float indicatorVolume = initial.materials().copyPositionVolume()[3];
		double indicatorMass = initial.materials().copyParticleMass()[0];

		domain.step(true, 2);
		GasComponentAmount gas = domain.airNetwork().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR);
		FlipChemicalParticleState boiled = domain.exportChemicalParticleState();
		SolverUnitScale massScale = SolverUnitScale.forCellSize(config.cellSize());
		long gasH2o = gas.species().stream().filter(amount -> amount.speciesId().equals(
				BuiltinChemicalDefinitions.H2O)).mapToLong(GasComponentAmount.SpeciesAmount::amountQuanta).sum();
		long gasSodium = gas.species().stream().filter(amount -> amount.speciesId().equals(
				BuiltinChemicalDefinitions.NA_PLUS)).mapToLong(GasComponentAmount.SpeciesAmount::amountQuanta).sum();
		require(domain.lastGasThermalPhaseStats().boiledCells() == 1 && gasH2o > 0L && gasSodium == 0L
				&& boiled.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)] + gasH2o == initialH2o
				&& boiled.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)] == initialSodium
				&& close(boiled.residualLedger() + gas.massResidual(),
						initial.residualLedger())
				&& close(gasSpeciesMassKilograms(chemistry, gas) + gas.massResidual(), gas.mass())
				&& Float.floatToRawIntBits(boiled.materials().copyPositionVolume()[3])
				== Float.floatToRawIntBits(indicatorVolume)
				&& Double.doubleToRawLongBits(boiled.materials().copyParticleMass()[0])
				== Double.doubleToRawLongBits(indicatorMass)
				&& domain.totalGranularSolidVolumeSvq() == initialGranularSvq
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"species-bearing boiling did not retain solute/indicator and move exact vapor truth");

		setAllCondensedCellTemperatures(domain, 300.0);
		FlipFluidDomain.GasThermalPhaseStats firstCondensed = domain.stepGasPhaseChangeOnly(1);
		GasComponentAmount vaporRemainder = domain.airNetwork().inventory(1)
				.component(BuiltinGasDefinitions.WATER_VAPOR);
		require(firstCondensed.operations() == 1 && firstCondensed.condensedCells() == 1
				&& firstCondensed.boiledCells() == 0 && vaporRemainder != null
				&& domain.airNetwork().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"species-bearing partial condensation did not retain its exact gas remainder: stats="
						+ firstCondensed + " vapor=" + vaporRemainder + " gasAudit="
						+ domain.airNetwork().auditClosed() + " thermalAudit="
						+ domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()));
		FlipChemicalParticleState partiallyRestored = domain.exportChemicalParticleState();
		long remainingGasH2o = vaporRemainder.species().stream().filter(amount -> amount.speciesId().equals(
				BuiltinChemicalDefinitions.H2O)).mapToLong(GasComponentAmount.SpeciesAmount::amountQuanta).sum();
		double remainingSolverMass = massScale.solverMass(vaporRemainder.mass());
		require(partiallyRestored.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)]
						+ remainingGasH2o == initialH2o
				&& partiallyRestored.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)] == initialSodium
				&& close(partiallyRestored.residualLedger()
						+ vaporRemainder.massResidual(), initial.residualLedger())
				&& close(gasSpeciesMassKilograms(chemistry, vaporRemainder)
						+ vaporRemainder.massResidual(), vaporRemainder.mass())
				&& close(partiallyRestored.materials().totalMass() + remainingSolverMass,
						initial.materials().totalMass())
				&& Float.floatToRawIntBits(partiallyRestored.materials().copyPositionVolume()[3])
				== Float.floatToRawIntBits(indicatorVolume)
				&& Double.doubleToRawLongBits(partiallyRestored.materials().copyParticleMass()[0])
				== Double.doubleToRawLongBits(indicatorMass)
				&& domain.totalGranularSolidVolumeSvq() == initialGranularSvq
				&& domain.airNetwork().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"species-bearing partial condensation did not conserve liquid/gas truth: first="
						+ firstCondensed + " boiledGas=" + gas + " remainder=" + vaporRemainder
						+ " h2o=" + partiallyRestored.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)]
						+ "+" + remainingGasH2o + "/" + initialH2o + " residual="
						+ partiallyRestored.residualLedger() + "+" + vaporRemainder.massResidual()
						+ "/" + initial.residualLedger() + " audit="
						+ domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()));
	}

	private static double gasSpeciesMassKilograms(ChemicalRegistry registry, GasComponentAmount component) {
		ExactRational massKilograms = ExactRational.ZERO;
		for (GasComponentAmount.SpeciesAmount amount : component.species()) {
			massKilograms = massKilograms.add(registry.species(registry.speciesHandle(amount.speciesId())).massKilogramsPerAu()
					.multiply(amount.amountQuanta()).divide(ChemicalLimits.QUANTA_PER_AU));
		}
		return massKilograms.toDouble();
	}

	private static void setAllCondensedCellTemperatures(FlipFluidDomain domain, double temperatureK) {
		FlipMaterialParticleState state = domain.exportMaterialParticleState();
		byte[] flags = domain.copyThermalDisplaySnapshot().debugFlags();
		for (int cell = 0; cell < flags.length; cell++) {
			if ((flags[cell] & ThermalDisplaySnapshot.FLAG_CONDENSED) == 0) continue;
			int x = cell % state.sizeX();
			int yz = cell / state.sizeX();
			domain.setThermalTemperatureInCell(x, yz % state.sizeY(), yz / state.sizeY(), temperatureK);
		}
	}

	private static void definitionAndCanonicalRegistry() {
		ThermalMaterialDefinition first = material("mmm:thermal/test", "mmm:test", 100.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 300.0, 2.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 4.0)));
		ThermalMaterialDefinition second = material("mmm:thermal/other", "mmm:other", 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 3.0)));
		ThermalRegistry forward = new ThermalRegistry(List.of(first, second));
		ThermalRegistry reverse = new ThermalRegistry(List.of(second, first));
		require(forward.fingerprint().equals(reverse.fingerprint()), "thermal registry depends on definition order");
		require(java.util.Arrays.equals(forward.canonicalBytes(), reverse.canonicalBytes()),
				"thermal canonical bytes depend on definition order");

		String json = """
				{
				  "carrier_kind":"LIQUID",
				  "carrier_id":"mmm:test",
				  "reference_temperature_k":"300.0",
				  "reference_specific_energy_eu_per_mu":"100.0",
				  "conductivity_eu_per_second_meter_k":"0.5",
				  "caloric_segments":[
				    {"minimum_temperature_k":"200.0","maximum_temperature_k":"300.0","specific_heat_eu_per_mu_k":"2.0"},
				    {"minimum_temperature_k":"300.0","maximum_temperature_k":"500.0","specific_heat_eu_per_mu_k":"4.0"}
				  ]
				}
				""";
		ThermalMaterialDefinition decoded = ThermalDefinitionCodec.decodeMaterial("mmm:thermal/test",
				new StringReader(json));
		require(decoded.equals(first), "thermal material JSON did not round-trip the definition contract");
		ThermalBoundaryDefinition boundary = ThermalDefinitionCodec.decodeBoundary("mmm:boundary/test",
				new StringReader("{\"target_kind\":\"BLOCK\",\"target_id\":\"minecraft:magma_block\","
						+ "\"kind\":\"FIXED_SURFACE_TEMPERATURE\",\"surface_temperature_k\":\"500.0\"}"));
		ThermalRegistry withBoundary = new ThermalRegistry(List.of(first, second), List.of(), List.of(),
				List.of(boundary), "", "");
		require(withBoundary.boundaries().equals(List.of(boundary))
				&& !withBoundary.fingerprint().equals(forward.fingerprint()),
				"thermal boundary definition was absent from canonical registry identity");
		expectFailure(() -> new ThermalMaterialDefinition("mmm:thermal/broken",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "mmm:broken", 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 299.0, 1.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 1.0))), "non-contiguous segments accepted");
	}

	private static void worldRegistrySnapshotIsAuthoritative() {
		java.nio.file.Path root = null;
		try {
			root = java.nio.file.Files.createTempDirectory("mmm-thermal-registry-");
			ThermalMaterialDefinition original = material("test:thermal/store", "test:store", 0.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0)));
			ThermalRegistry authoritative = new ThermalRegistry(List.of(original));
			ThermalRegistryStore store = new ThermalRegistryStore(root, "", "", "");
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
			var registryTag = net.minecraft.nbt.NbtIo.readCompressed(store.registryPath(),
					net.minecraft.nbt.NbtAccounter.create(8L * 1024L * 1024L));
			registryTag.putString("UnknownField", "must-lock");
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, store.registryPath());
			try {
				store.read();
				throw new AssertionError("unknown thermal registry field was ignored");
			} catch (java.io.IOException expected) { }
			registryTag.remove("UnknownField");
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, store.registryPath());
			java.nio.file.Files.writeString(root.resolve("mmm").resolve("thermal").resolve("foreign.bin"), "foreign");
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
		ThermalMaterialDefinition definition = material("mmm:thermal/test", "mmm:test", 100.0,
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
		ThermalMaterialDefinition solution = material("mmm:thermal/solution", "mmm:water", 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 500.0, 1_000.0)));
		List<ThermalSpeciesOffset> offsets = List.of(
				new ThermalSpeciesOffset("mmm:thermal_offset/h", "mmm:h_plus", solution.id(), ExactRational.of(10)),
				new ThermalSpeciesOffset("mmm:thermal_offset/oh", "mmm:oh_minus", solution.id(), ExactRational.of(20)),
				new ThermalSpeciesOffset("mmm:thermal_offset/water", "mmm:h2o", solution.id(), ExactRational.of(-50)));
		ThermalRegistry registry = new ThermalRegistry(List.of(solution), offsets, List.of(), CHEMICAL_FINGERPRINT, "");
		ThermalStateFunction state = new ThermalStateFunction(registry);
		int handle = registry.materialHandle(solution.id());
		Map<String, Long> reactants = Map.of("mmm:h_plus", ChemicalLimits.QUANTA_PER_AU,
				"mmm:oh_minus", ChemicalLimits.QUANTA_PER_AU);
		Map<String, Long> productsA = Map.of("mmm:h2o", ChemicalLimits.QUANTA_PER_AU);
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
		ChemicalParcelStore parcels = new ChemicalParcelStore(
				chemistry, SolverUnitScale.forCellSize(1.0F), 1, 8);
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
		require(thermal.exactEnergy(1).equals(before) && close(thermal.temperature(0, markers), 380.0),
				"reaction heat was not derived from conserved marker internal energy");
	}

	private static void granularReactionHeatUsesOneConservedCellEnergy() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 1.0F / 20.0F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FlipFluidDomain domain = new FlipFluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		domain.setVolume(1, 1, 1, 0.1F);
		domain.bindGranular(granular, liquids);
		domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		domain.setMarkerChemical(0, Map.of(
				BuiltinChemicalDefinitions.NA_PLUS, 1_100_000L,
				BuiltinChemicalDefinitions.CL_MINUS, 1_100_000L), true);
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
		long solidBeforeDissolution = domain.totalGranularSolidVolumeSvq();
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
						+ temperatureBeforeDissolution + " afterT=" + dissolutionTemperature
						+ " dissolved=" + dissolved + " solidSvq=" + solidBeforeDissolution + "->"
						+ domain.totalGranularSolidVolumeSvq()
						+ " audit=" + domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
						+ " deltaU=" + domain.exactInternalEnergy().subtract(beforeDissolution));
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
		ThermalMaterialDefinition ice = new ThermalMaterialDefinition("mmm:thermal/ice",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "mmm:ice", 273.15, 0.0, 2.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 273.15, 2_100.0)));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "mmm:water", 273.15, 334_000.0, 0.6,
				List.of(new ThermalMaterialDefinition.CaloricSegment(273.15, 500.0, 4_180.0)));
		ThermalPhaseTransition melt = new ThermalPhaseTransition("mmm:phase/ice_water", ice.id(), water.id(),
				273.15, SolidVolume.SVQ_PER_BV, SolidVolume.SVQ_PER_BV * 917L / 1_000L, ExactRational.of(917),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 80_000.0, 120_000.0);
		ThermalRegistry registry = new ThermalRegistry(List.of(water, ice), List.of(), List.of(melt), "", "");
		ThermalStateFunction state = new ThermalStateFunction(registry);
		ThermalStateFunction.EquilibriumState lower = state.equilibriumState(registry.transitionHandle(melt.id()),
				1.0, Map.of(), 0.0, GasRegistry.STANDARD_PRESSURE_PA);
		double quarterEnergy = lower.lowerBoundaryEnergy()
				+ 0.25 * (lower.upperBoundaryEnergy() - lower.lowerBoundaryEnergy());
		ThermalStateFunction.EquilibriumState plateau = state.equilibriumState(registry.transitionHandle(melt.id()),
				1.0, Map.of(), quarterEnergy, GasRegistry.STANDARD_PRESSURE_PA);
		require(plateau.phaseState() == ThermalStateFunction.PhaseState.PLATEAU
				&& close(plateau.temperatureK(), 273.15) && close(plateau.upperPhaseFraction(), 0.25),
				"phase plateau did not derive its fraction from the state-function energy gap");

		ThermalPhaseTransition highPressure = new ThermalPhaseTransition(
				"mmm:phase/high_pressure_ice_water", ice.id(), water.id(),
				SolidVolume.SVQ_PER_BV, SolidVolume.SVQ_PER_BV * 917L / 1_000L,
				ExactRational.of(917), ThermalPhaseTransition.InterfaceRequirement.NONE,
				List.of(new ThermalPhaseTransition.EquilibriumPoint(3_000_000.0, 273.15),
						new ThermalPhaseTransition.EquilibriumPoint(4_000_000.0, 273.15)));
		ThermalRegistry highPressureRegistry = new ThermalRegistry(
				List.of(water, ice), List.of(), List.of(highPressure), "", "");
		require(highPressureRegistry.transition(0).minimumPressurePa() == 3_000_000.0
				&& close(highPressure.equilibriumTemperatureK(3_500_000.0), 273.15),
				"solid/liquid curve was incorrectly required to cover standard pressure");
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain unsupportedPressure = new FlipFluidDomain(2, 2, 2, config, 128);
		unsupportedPressure.setVolume(0, 0, 0, 0.5F);
		unsupportedPressure.bindThermal(highPressureRegistry,
				new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)), 273.15);
		expectFailure(() -> unsupportedPressure.stepInertPhaseChangeOnly(16),
				"solid/liquid phase silently ignored pressure outside its declared curve");
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
		ThermalPhaseChangeEngine.Plan freeze = engine.planInert(
				transition, 0.0, 1.0, 50.0, 16L, GasRegistry.STANDARD_PRESSURE_PA);
		ThermalPhaseChangeEngine.Plan melt = engine.planInert(
				transition, 1.0, 0.0, 50.0, 16L, GasRegistry.STANDARD_PRESSURE_PA);
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
		boolean collisionBlocked = false;
		try {
			domain.planCollisionEdits(List.of(
					new FlipFluidDomain.CollisionEdit(1, 1, 1, 0xff),
					new FlipFluidDomain.CollisionEdit(0, 0, 0, 0xff)));
		} catch (IllegalStateException expected) {
			collisionBlocked = true;
		}
		require(collisionBlocked && domain.solidMicroMask(1, 1, 1) == 0
				&& domain.solidMicroMask(0, 0, 0) == 0
				&& domain.totalCoherentSolidVolumeSvq() == SolidVolume.SVQ_PER_CELL / 2L,
				"collision batch mutated an earlier cell before rejecting coherent inventory");
		domain.setVolume(0, 0, 0, 1.0F);
		require(close(domain.totalVolumeExact(), 0.5)
				&& domain.totalCoherentSolidVolumeSvq() == SolidVolume.SVQ_PER_CELL / 2L,
				"coherent phase did not share the exact pressure/free-surface occupancy field");

		long batchSvq = SolidVolume.SVQ_PER_CELL / 8L;
		ThermalPhaseTransition cellTransition = new ThermalPhaseTransition("test:phase/cell_lower_upper",
				lower.id(), upper.id(), 300.0, batchSvq, batchSvq, ExactRational.parse("0.015625"),
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
				&& close(SolverUnitScale.forCellSize(config.cellSize()).kilograms(phaseDomain.totalMass())
						+ phaseDomain.totalCoherentMass(), 0.125)
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
				&& close(SolverUnitScale.forCellSize(config.cellSize()).kilograms(phaseDomain.totalMass())
						+ phaseDomain.totalCoherentMass(), 0.125)
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
		FlipFluidDomain.ThermalPhaseStats quantizedEquilibrium = roundoffDomain.stepInertPhaseChangeOnly(16);
		FlipFluidDomain.ThermalPhaseStats repeatedEquilibrium = roundoffDomain.stepInertPhaseChangeOnly(16);
		require(quantizedEquilibrium.operations() == 0 && repeatedEquilibrium.operations() == 0
				&& close(roundoffDomain.thermalTemperatureInCell(0, 0, 0), 273.1498)
				&& roundoffDomain.exactInternalEnergy().equals(roundoffEnergy)
				&& roundoffDomain.exactInternalEnergy().equals(roundoffDomain.thermalAudit().expectedInventory()),
				"exact phase quantum farther from equilibrium was allowed to create a freeze/melt cycle: stats="
						+ quantizedEquilibrium + "/" + repeatedEquilibrium
						+ " energy=" + roundoffDomain.exactInternalEnergy() + "/" + roundoffEnergy
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
		double sandMass = sandSvq / (double) SolidVolume.SVQ_PER_BV
				* granular.material(sand).referenceDensity();
		require(domain.tryInsertExactGranular(1, 1, 1, sand, 0, sandSvq, sandMass).inserted(),
				"joint phase fixture could not insert its granular heat bath");
		domain.bindThermal(compositionPhaseThermalRegistry(liquids, granular, chemistry), liquids, 295.0);

		CondensedLedger initial = condensedLedger(domain, chemistry);
		long initialGranularSvq = domain.totalGranularSolidVolumeSvq();
		double initialGranularMass = domain.totalGranularMass();
		SolverUnitScale massScale = SolverUnitScale.forCellSize(config.cellSize());
		double initialMass = massScale.kilograms(domain.totalMass()) + domain.totalCoherentMass();
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
				&& close(massScale.kilograms(domain.totalMass()) + domain.totalCoherentMass(), initialMass)
				&& Math.abs(domain.exactInternalEnergy().subtract(coldEnergy).toDouble()) <= 1.0E-8
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"composition/granular freeze did not conserve joint species, indicator, solid and energy truth: stats="
						+ freeze + " initial=" + initial + " frozen=" + frozen
						+ " coherent=" + domain.totalCoherentSolidVolumeSvq()
						+ " granularSvq=" + domain.totalGranularSolidVolumeSvq() + "/" + initialGranularSvq
						+ " granularMass=" + domain.totalGranularMass() + "/" + initialGranularMass
						+ " massKg=" + (massScale.kilograms(domain.totalMass()) + domain.totalCoherentMass()) + "/" + initialMass
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
				&& close(massScale.kilograms(domain.totalMass()) + domain.totalCoherentMass(), initialMass)
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
				batchSvq, batchSvq, ExactRational.parse("15.625"),
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
				ExactRational.parse("0.015625"), ThermalPhaseTransition.InterfaceRequirement.NONE,
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
		FlipFluidDomain lienDomain = new FlipFluidDomain(2, 2, 2, config, 128);
		lienDomain.setVolume(0, 0, 0, 0.125F);
		lienDomain.bindThermal(registry, liquids, 295.0);
		PendingPhaseVolumeState restoredLien = new PendingPhaseVolumeState(8,
				List.of(new PendingPhaseVolumeState.Entry(0, SignedDyadic.of(0.125))));
		lienDomain.restorePendingPhaseVolumeState(restoredLien);
		double volumeBeforeBlockedExtraction = lienDomain.totalVolumeExact();
		FlipFluidDomain.ExactVolumeTransfer blockedExtraction = lienDomain.tryExtractExactVolume(
				0, 0, 0, 0.125F, 0, 0);
		require(!blockedExtraction.transferred()
				&& lienDomain.totalVolumeExact() == volumeBeforeBlockedExtraction
				&& lienDomain.exportPendingPhaseVolumeState().entries().equals(restoredLien.entries()),
				"external extraction stranded pending phase mechanical work: transferred="
						+ blockedExtraction.transferred() + " volume=" + lienDomain.totalVolumeExact()
						+ "/" + volumeBeforeBlockedExtraction + " pending="
						+ lienDomain.exportPendingPhaseVolumeState().entries() + "/" + restoredLien.entries());

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
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/container_water",
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

		double importedEnergy = SolverUnitScale.forCellSize(config.cellSize()).kilograms(1_000.0)
				* 4_000.0 * 20.0;
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
		FluidMaterialRegistry productionLiquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.8F);
		FlipFluidDomain domain = new FlipFluidDomain(8, 8, 8, config, 8 * 8 * 8 * 8);
		domain.bindGranular(granular, productionLiquids);
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long parcelSvq = SolidVolume.SVQ_PER_BV;
		double parcelMass = parcelSvq / (double) SolidVolume.SVQ_PER_BV
				* granular.material(sand).referenceDensity();
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
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/water",
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
		require(hot < 350.0 && hot >= 300.0 && cold > 250.0 && cold <= 300.0 && hot >= cold,
				"pairwise thermal transport was not monotone: hot=" + hot + " cold=" + cold);
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

		FluidDomainState authoritative = domain.captureAuthoritativeState();
		FlipFluidDomain fullRestore = FlipFluidDomain.restoreAuthoritativeState(authoritative);
		var expectedGas = domain.exportGasState("");
		var restoredGas = fullRestore.exportGasState("");
		boolean energyEqual = fullRestore.exactInternalEnergy().equals(domain.exactInternalEnergy());
		boolean gasEqual = restoredGas.nodes().equals(expectedGas.nodes())
				&& Arrays.equals(restoredGas.cellNodeOrdinal(), expectedGas.cellNodeOrdinal());
		PendingPhaseVolumeState expectedPending = domain.exportPendingPhaseVolumeState();
		PendingPhaseVolumeState restoredPending = fullRestore.exportPendingPhaseVolumeState();
		boolean pendingEqual = restoredPending.cellCount() == expectedPending.cellCount()
				&& restoredPending.entries().equals(expectedPending.entries());
		require(energyEqual && gasEqual && pendingEqual,
				"complete loader-neutral handoff changed truth: energy=" + energyEqual
						+ " gas=" + gasEqual + " pending=" + pendingEqual);
		SignedDyadic committedEnergy = domain.exactInternalEnergy();
		FluidStepCandidate candidate = new CpuFluidSolverBackend().evaluate(
				new FluidStepRequest(7L, authoritative, List.of(), false, 0));
		require(candidate.baseGeneration() == 7L && candidate.candidateGeneration() == 8L
				&& candidate.backendId().equals(CpuFluidSolverBackend.ID)
				&& domain.exactInternalEnergy().equals(committedEnergy),
				"CPU candidate boundary changed generation identity or mutated the owner");
	}

	private static void localizedHeatingDrivesBoussinesqCirculation() {
		require(LiquidThermalExpansion.require(BuiltinFluidMaterials.WATER.id())
				.relativeBuoyancy(313.15) > 0.0, "water expansion definition does not produce hot buoyancy");
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/buoyancy_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				293.15, 0.0, 0.598,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 4_184.0)));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 0.05F,
				100, 1.0E-5F, 0.40F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(6, 6, 6, config, 1_728);
		for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
			if (x == 0 || x == 5 || y == 0 || y == 5 || z == 0 || z == 5) domain.setSolid(x, y, z, true);
			else domain.setVolume(x, y, z, 1.0F);
		}
		domain.bindThermal(new ThermalRegistry(List.of(water)), liquids, 293.15);
		domain.setThermalTemperatureInCell(2, 1, 2, 333.15);
		for (int step = 0; step < 4; step++) domain.step();
		FlipFluidDomain.VelocitySample plume = domain.sampleVelocity(1.25F, 0.75F, 1.25F);
		FlipFluidDomain.VelocitySample far = domain.sampleVelocity(1.75F, 0.75F, 1.75F);
		require(plume.y() > far.y() + 1.0E-5F && domain.maximumParticleSpeed() > 1.0E-4F,
				"localized heat did not create an upward Boussinesq plume: plume=" + plume.y() + " far=" + far.y());
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"Boussinesq momentum coupling changed authoritative thermal energy");
	}

	private static void partialCutFaceUsesPlicThermalContact() {
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/partial_contact_water",
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

	private static void finiteThermalBodyExchangesWithoutMixing() {
		ThermalMaterialDefinition wall = new ThermalMaterialDefinition("test:thermal/exchanger_wall",
				ThermalMaterialDefinition.CarrierKind.DEVICE, "test:exchanger_wall",
				300.0, 0.0, 20.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 500.0, 500.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(wall));
		ThermalStateFunction stateFunction = new ThermalStateFunction(registry);
		double initialEnergy = stateFunction.internalEnergyAtTemperature(
				registry.materialHandle(wall.id()), 10.0, Map.of(), 400.0);
		FiniteThermalBodyState body = new FiniteThermalBodyState(
				7L, registry.fingerprint(), wall.id(), 10.0, initialEnergy);
		FiniteThermalExchange.Plan plan = FiniteThermalExchange.plan(registry, body, List.of(
				new FiniteThermalExchange.Contact(0, 300.0, 2_000.0, 100.0, 250.0, 500.0),
				new FiniteThermalExchange.Contact(1, 350.0, 1_000.0, 50.0, 250.0, 500.0)
		), 1.0, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		FiniteThermalExchange.ContactDelta cold = plan.contacts().get(0);
		FiniteThermalExchange.ContactDelta warm = plan.contacts().get(1);
		SignedDyadic jointChange = SignedDyadic.of(plan.after().internalEnergyEu())
				.subtract(SignedDyadic.of(plan.before().internalEnergyEu()))
				.add(SignedDyadic.of(cold.internalEnergyDeltaEu()))
				.add(SignedDyadic.of(warm.internalEnergyDeltaEu()));
		require(plan.changed() && plan.before().generation() == 7L && plan.after().generation() == 8L
				&& cold.ordinal() == 0 && warm.ordinal() == 1
				&& close(cold.internalEnergyDeltaEu(), 10_000.0)
				&& close(warm.internalEnergyDeltaEu(), 2_500.0)
				&& close(cold.finalTemperatureK(), 305.0)
				&& close(warm.finalTemperatureK(), 352.5)
				&& close(stateFunction.stateForInternalEnergy(registry.materialHandle(wall.id()), 10.0,
						Map.of(), plan.after().internalEnergyEu()).temperatureK(), 397.5)
				&& jointChange.equals(plan.roundoffResidual()),
				"finite thermal body did not produce a closed three-owner energy plan");

		double equilibriumEnergy = stateFunction.internalEnergyAtTemperature(
				registry.materialHandle(wall.id()), 10.0, Map.of(), 350.0);
		FiniteThermalBodyState equilibrium = new FiniteThermalBodyState(
				9L, registry.fingerprint(), wall.id(), 10.0, equilibriumEnergy);
		FiniteThermalExchange.Plan noOp = FiniteThermalExchange.plan(registry, equilibrium, List.of(
				new FiniteThermalExchange.Contact(0, 350.0, 2_000.0, 100.0, 250.0, 500.0),
				new FiniteThermalExchange.Contact(1, 350.0, 1_000.0, 50.0, 250.0, 500.0)
		), 1.0, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		require(!noOp.changed() && noOp.after().equals(equilibrium)
				&& noOp.contacts().stream().allMatch(contact -> contact.internalEnergyDeltaEu() == 0.0),
				"equilibrated finite thermal body produced a false durable mutation");

		ThermalMaterialDefinition segmented = new ThermalMaterialDefinition("test:thermal/segmented_device",
				ThermalMaterialDefinition.CarrierKind.DEVICE, "test:segmented_device",
				350.0, 0.0, 20.0, List.of(
						new ThermalMaterialDefinition.CaloricSegment(250.0, 300.0, 1.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 1_000.0)));
		ThermalRegistry segmentedRegistry = new ThermalRegistry(List.of(segmented));
		ThermalStateFunction segmentedState = new ThermalStateFunction(segmentedRegistry);
		FiniteThermalBodyState segmentedBody = new FiniteThermalBodyState(1L,
				segmentedRegistry.fingerprint(), segmented.id(), 1.0,
				segmentedState.internalEnergyAtTemperature(0, 1.0, Map.of(), 400.0));
		double[] segmentedCellEnergy = {segmentedBody.internalEnergyEu()};
		ThermalCellGrid segmentedCell = new ThermalCellGrid(1);
		segmentedCell.rebuild(new ThermalCellGrid.CarrierAccess() {
			@Override public int carrierCount() { return 1; }
			@Override public int cell(int carrier) { return 0; }
			@Override public ThermalMaterialDefinition material(int carrier) { return segmented; }
			@Override public double mass(int carrier) { return 1.0; }
			@Override public double volume(int carrier) { return 1.0; }
			@Override public double baseEnergy(int carrier) { return 0.0; }
			@Override public double internalEnergy(int carrier) { return segmentedCellEnergy[carrier]; }
			@Override public void setInternalEnergy(int carrier, double energy) { segmentedCellEnergy[carrier] = energy; }
		});
		require(segmentedCell.minimumTemperature(0) == 300.0
				&& segmentedCell.maximumTemperature(0) == 500.0,
				"finite thermal contact exposed a non-linear caloric validity interval");
		expectFailure(() -> FiniteThermalExchange.plan(segmentedRegistry, segmentedBody, List.of(
				new FiniteThermalExchange.Contact(0, 250.0, 1_000.0, 100.0, 250.0, 500.0)
		), 1.0, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS),
				"finite thermal planner ignored a lower-capacity reachable caloric segment");
	}

	private static void productionFixedBoundaryFieldAuditsExchange() {
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/production_boundary_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				293.15, 0.0, 0.598,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 4_184.0)));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(4, 3, 3, config, 512);
		domain.setVolume(0, 1, 1, 1.0F);
		domain.bindThermal(new ThermalRegistry(List.of(water)), liquids, 293.15);
		double[] boundaries = new double[4 * 3 * 3 * 6];
		java.util.Arrays.fill(boundaries, Double.NaN);
		boundaries[((1 * 3 + 1) * 4) * 6] = 10_000_000.0; // -x domain wall of cell (0,1,1)
		require(domain.replaceThermalBoundaryTemperatures(boundaries)
				&& !domain.replaceThermalBoundaryTemperatures(boundaries),
				"production thermal boundary field did not detect an actual world edit exactly once");
		ThermalTransport.Stats transport = domain.stepThermalOnly(1.0);
		double expectedOneCellFlux = 0.598 * (10_000_000.0 - 293.15);
		require(transport.reservoirExchange().compareTo(SignedDyadic.ZERO) > 0
				&& Math.abs(transport.reservoirExchange().toDouble() - expectedOneCellFlux) < 1.0E-6
				&& domain.thermalTemperatureInCell(0, 1, 1) > 293.15
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"production fixed boundary did not reproduce and audit its finite-volume SI flux");
	}

	private static void fixedBoundaryAuditsAndFailedStepRollsBack() {
		FluidMaterial liquid = FluidMaterial.withSurfaceTension("test:boundary_liquid", 1.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(liquid));
		ThermalMaterialDefinition definition = new ThermalMaterialDefinition("test:thermal/boundary_liquid",
				ThermalMaterialDefinition.CarrierKind.LIQUID, liquid.id(), 300.0, 0.0, 0.125,
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
				&& heated.reservoirExchange().equals(SignedDyadic.of(12.5))
				&& thermal.exactEnergy(1).equals(thermal.audit().expectedInventory()),
				"fixed-temperature boundary did not close source/audit energy");
		SignedDyadic beforeFailure = thermal.exactEnergy(1);
		ThermalAuditLedger auditBeforeFailure = thermal.audit();
		expectFailure(() -> thermal.step(markers, (x, y, z, axis) -> 0.0,
				(x, y, z, direction) -> direction == 0
						? new ThermalTransport.FixedBoundary(0.25, 10_000.0) : null, 10.0),
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

	private static double relativeError(double actual, double expected) {
		return Math.abs(actual - expected) / Math.abs(expected);
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
