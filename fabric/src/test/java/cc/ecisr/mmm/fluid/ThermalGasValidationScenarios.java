package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalDomainState;

import cc.ecisr.mmm.thermal.GasEnergyStore;

import cc.ecisr.mmm.thermal.ThermalCarrierContext;

import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.thermal.GasLiquidSpeciesPair;
import cc.ecisr.mmm.thermal.GasThermalModel;
import cc.ecisr.mmm.thermal.GasLiquidPhaseModel;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.SolverUnitScale;
import cc.ecisr.mmm.thermal.FiniteThermalDeviceDefinition;
import cc.ecisr.mmm.thermal.ThermalCellGrid;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalDefinitionCodec;
import cc.ecisr.mmm.thermal.ThermalDisplaySnapshot;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalRegistryCodec;
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

abstract class ThermalGasValidationScenarios extends ThermalValidationSupport {
	static void worldBoundaryTemperatureRangeExcludesFiniteDevices() {
		ThermalMaterialDefinition liquid = new ThermalMaterialDefinition("test:thermal/range_liquid",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "test:range_liquid", 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 600.0, 1.0)));
		ThermalMaterialDefinition gas = new ThermalMaterialDefinition("test:thermal/range_gas",
				ThermalMaterialDefinition.CarrierKind.GAS, "test:range_gas", 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 500.0, 1.0)));
		ThermalMaterialDefinition device = new ThermalMaterialDefinition("test:thermal/range_device",
				ThermalMaterialDefinition.CarrierKind.DEVICE, "test:range_device", 320.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(300.0, 350.0, 1.0)));
		ThermalRegistry.TemperatureRange range = new ThermalRegistry(List.of(liquid, gas, device))
				.worldBoundaryTemperatureRange();
		require(range.minimumK() == 250.0 && range.maximumK() == 500.0
				&& range.contains(250.0) && range.contains(500.0) && !range.contains(500.1),
				"fixed-boundary tuning range did not use the common world-carrier validity interval");
	}

	static void thermalGridCachesObservedTemperatureRange() {
		ThermalMaterialDefinition material = new ThermalMaterialDefinition("test:thermal/range_cache",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "test:range_cache", 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 600.0, 2.0)));
		double[] energy = {-100.0, 200.0};
		ThermalCellGrid grid = new ThermalCellGrid(2);
		require(grid.observedTemperatureRange() == null,
				"unbuilt thermal grid invented an observed temperature range");
		grid.rebuild(new ThermalCellGrid.CarrierAccess() {
			@Override public int carrierCount() { return 2; }
			@Override public int cell(int carrier) { return carrier; }
			@Override public ThermalMaterialDefinition material(int carrier) { return material; }
			@Override public double mass(int carrier) { return 1.0; }
			@Override public double volume(int carrier) { return 1.0; }
			@Override public double baseEnergy(int carrier) { return 0.0; }
			@Override public double internalEnergy(int carrier) { return energy[carrier]; }
			@Override public void setInternalEnergy(int carrier, double value) { energy[carrier] = value; }
		});
		ThermalCellGrid.TemperatureRange initial = grid.observedTemperatureRange();
		require(initial != null && initial.minimumK() == 250.0 && initial.maximumK() == 400.0,
				"thermal rebuild did not cache the occupied-carrier temperature range");
		grid.setCellInternalEnergies(new double[] {0.0, 100.0});
		ThermalCellGrid.TemperatureRange committed = grid.observedTemperatureRange();
		require(committed != null && committed.minimumK() == 300.0 && committed.maximumK() == 350.0,
				"thermal energy commit did not refresh the observed temperature range");
	}

	static void thermalSleepIsOrthogonalToMotion() {
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

	static void gasStateCompressionAndCarrierEnergy() {
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		ThermalMaterialDefinition air = new ThermalMaterialDefinition("mmm:thermal/air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
				GasRegistry.STANDARD_TEMPERATURE_K, 10.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 718.0)));
		GasThermalModel model = new GasThermalModel(new ThermalRegistry(List.of(air)), gas);
		require(model.internalEnergyAtTemperature(GasInventory.EMPTY, GasRegistry.STANDARD_TEMPERATURE_K) == 0.0
				&& model.absolutePressure(GasInventory.EMPTY, 1.0, 0.0) == 0.0
				&& model.density(GasInventory.EMPTY, 1.0) == 0.0,
				"thermal vacuum carried inventory, energy, pressure or density");
		GasThermalModel.Compression vacuum = model.compressAdiabatically(
				GasInventory.EMPTY, 0.0, 1.0, 0.5);
		require(vacuum.finalInternalEnergy() == 0.0 && vacuum.workIntoGas() == 0.0,
				"vacuum volume change created thermal energy or work");
		GasInventory inventory = gas.createAmbient(1_000_000_000L);
		double initial = model.internalEnergyAtTemperature(inventory, GasRegistry.STANDARD_TEMPERATURE_K);
		require(close(model.state(inventory, initial).temperatureK(), GasRegistry.STANDARD_TEMPERATURE_K),
				"gas temperature was not derived from authoritative U");
		GasThermalModel.HydrodynamicState standard = model.hydrodynamicState(inventory, 1.0, initial);
		double expectedGasConstant = GasRegistry.STANDARD_PRESSURE_PA / GasRegistry.STANDARD_TEMPERATURE_K;
		double expectedExponent = 1.0 + expectedGasConstant / (inventory.totalMass() * 718.0);
		require(close(standard.absolutePressurePa(), GasRegistry.STANDARD_PRESSURE_PA)
				&& close(standard.densityKgPerCubicMeter(), AirNetwork.AIR_DENSITY)
				&& close(standard.gasConstantEuPerK(), expectedGasConstant)
				&& close(standard.isentropicExponent(), expectedExponent)
				&& close(standard.adiabaticBulkModulusPa(),
					expectedExponent * GasRegistry.STANDARD_PRESSURE_PA)
				&& close(standard.soundSpeedMetersPerSecond() * standard.soundSpeedMetersPerSecond(),
					standard.adiabaticBulkModulusPa() / standard.densityKgPerCubicMeter())
				&& close(standard.pressureCompliancePerPa(), 1.0 / standard.adiabaticBulkModulusPa()),
				"gas pressure/density/acoustic closure did not derive from the same inventory and U");
		expectFailure(() -> model.hydrodynamicState(GasInventory.EMPTY, 1.0, 0.0),
				"vacuum continuum closure");
		GasThermalModel.Compression compressed = model.compressAdiabatically(inventory, initial, 1.0, 0.5);
		double expectedTemperature = GasRegistry.STANDARD_TEMPERATURE_K * Math.pow(2.0, expectedExponent - 1.0);
		require(Math.abs(compressed.finalTemperatureK() - expectedTemperature) < 1.0E-10
				&& compressed.workIntoGas() > 0.0
				&& model.absolutePressure(inventory, 0.5, compressed.finalInternalEnergy())
				> 2.0 * GasRegistry.STANDARD_PRESSURE_PA,
				"analytic gas compression did not close state/work/EoS");
		GasThermalModel.Compression highPressure = model.compressAdiabatically(inventory, initial, 1.0, 0.1);
		double highPressurePa = model.hydrodynamicState(
				inventory, 0.1, highPressure.finalInternalEnergy()).absolutePressurePa();
		require(close(highPressurePa / GasRegistry.STANDARD_PRESSURE_PA, Math.pow(10.0, expectedExponent)),
				"adiabatic EoS did not preserve p*V^gamma beyond the removed numerical pressure ceiling");
		GasThermalModel.Compression expanded = model.compressAdiabatically(
				inventory, compressed.finalInternalEnergy(), 0.5, 1.0);
		require(Math.abs(expanded.finalInternalEnergy() - initial) < 1.0E-9,
				"reversible analytic gas expansion did not return initial U");
		ThermalMaterialDefinition piecewiseAir = new ThermalMaterialDefinition("mmm:thermal/piecewise_air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
				GasRegistry.STANDARD_TEMPERATURE_K, 10.0, 0.025, List.of(
						new ThermalMaterialDefinition.CaloricSegment(150.0, 400.0, 718.0),
						new ThermalMaterialDefinition.CaloricSegment(400.0, 1_500.0, 900.0)));
		GasThermalModel piecewise = new GasThermalModel(new ThermalRegistry(List.of(piecewiseAir)), gas);
		double piecewiseInitial = piecewise.internalEnergyAtTemperature(
				inventory, GasRegistry.STANDARD_TEMPERATURE_K);
		GasThermalModel.Compression acrossSegment = piecewise.compressAdiabatically(
				inventory, piecewiseInitial, 1.0, 0.1);
		double entropyToBoundary = inventory.totalMass() * 718.0
				* Math.log(400.0 / GasRegistry.STANDARD_TEMPERATURE_K);
		double expectedAcrossSegment = 400.0 * Math.exp((expectedGasConstant * Math.log(10.0)
				- entropyToBoundary) / (inventory.totalMass() * 900.0));
		GasThermalModel.Compression acrossSegmentReversed = piecewise.compressAdiabatically(
				inventory, acrossSegment.finalInternalEnergy(), 0.1, 1.0);
		require(Math.abs(acrossSegment.finalTemperatureK() - expectedAcrossSegment) < 1.0E-9
				&& Math.abs(acrossSegmentReversed.finalInternalEnergy() - piecewiseInitial) < 1.0E-8,
				"piecewise-caloric gas isentrope did not integrate and reverse across its segment boundary");
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

	static void builtinDryAirWaterAndVaporPhysicsIsCalibrated() {
		try (InputStreamReader airReader = resourceReader(
				"/data/mmm/mmm/thermal_materials/air.json");
		     InputStreamReader waterReader = resourceReader(
				"/data/mmm/mmm/thermal_materials/water.json");
		     InputStreamReader vaporReader = resourceReader(
				     "/data/mmm/mmm/thermal_materials/water_vapor.json");
		     InputStreamReader meltingReader = resourceReader(
				     "/data/mmm/mmm/phase_transitions/ice_water.json");
		     InputStreamReader transitionReader = resourceReader(
				     "/data/mmm/mmm/phase_transitions/water_vapor.json")) {
			ThermalMaterialDefinition air = ThermalDefinitionCodec.decodeMaterial("mmm:air", airReader);
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
			double[] expectedAirCv = {
					715.5184374797511, 718.0965575743058, 730.345643090267,
					764.3848905212396, 822.1840487741881, 885.9605202540622, 928.3976344371151
			};
			double[] airSamples = { 200.0, 300.0, 400.0, 600.0, 850.0, 1_200.0, 1_550.0 };
			for (int segment = 0; segment < expectedAirCv.length; segment++) {
				require(air.specificHeatAt(airSamples[segment]) == expectedAirCv[segment],
						"built-in dry-air caloric segment drifted at index " + segment);
			}
			require(relativeError(AirNetwork.AIR_DENSITY, 1.2038886846941566) < 1.0E-14
					&& water.specificHeatAt(293.15) == 4_184.0
					&& water.conductivityEuPerSecondMeterK() == 0.598
					&& vapor.specificHeatAt(373.15) == 1_410.0
					&& vapor.specificHeatAt(600.0) == 1_554.43
					&& vapor.specificHeatAt(850.0) == 1_723.57
					&& vapor.specificHeatAt(1_200.0) == 1_967.03
					&& vapor.specificHeatAt(1_550.0) == 2_180.66
					&& vapor.conductivityEuPerSecondMeterK() == 0.025,
					"built-in dry-air/water/vapor caloric or conductivity values drifted");
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

	static InputStreamReader resourceReader(String path) {
		var stream = M9ThermalValidation.class.getResourceAsStream(path);
		if (stream == null) throw new AssertionError("Missing validation resource " + path);
		return new InputStreamReader(stream, StandardCharsets.UTF_8);
	}

	static String resourceText(String path) throws IOException {
		try (var stream = M9ThermalValidation.class.getResourceAsStream(path)) {
			if (stream == null) throw new AssertionError("Missing validation resource " + path);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	static void builtinCo2GasLiquidPairDataIsFrozen() {
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
			require(registry.fingerprint().equals("6095e97de32ac72dea39cad4025210a70bcd987f6f3edc73e679f943cd92bf23"),
					"built-in CO2 pair registry fingerprint drifted: " + registry.fingerprint());
			root = java.nio.file.Files.createTempDirectory("mmm-co2-pair-registry-");
			java.nio.file.Path snapshot = root.resolve("thermal-registry.nbt");
			ThermalRegistryCodec.writeSnapshot(snapshot, registry);
			ThermalRegistry decoded = ThermalRegistryCodec.readSnapshot(
					snapshot, chemistry.fingerprint(), "", gas.fingerprint());
			require(decoded.gasLiquidPairs().equals(List.of(pair))
							&& decoded.fingerprint().equals(registry.fingerprint()),
					"CO2 pair did not round-trip through the thermal registry codec");

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

	static void builtinFiniteThermalDeviceDataIsFrozen() {
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
			java.nio.file.Path snapshot = root.resolve("thermal-registry.nbt");
			ThermalRegistryCodec.writeSnapshot(snapshot, registry);
			ThermalRegistry decoded = ThermalRegistryCodec.readSnapshot(snapshot, "", "", "");
			require(decoded.finiteDevices().equals(List.of(device))
							&& decoded.fingerprint().equals(registry.fingerprint()),
					"finite exchanger definition did not round-trip through the thermal registry codec");
			var registryTag = net.minecraft.nbt.NbtIo.readCompressed(snapshot,
					net.minecraft.nbt.NbtAccounter.create(8L * 1024L * 1024L));
			var encodedDevices = registryTag.getListOrEmpty("FiniteDevices");
			((net.minecraft.nbt.CompoundTag) encodedDevices.getFirst()).putString("UnknownPortPolicy", "reject");
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, snapshot);
			try {
				ThermalRegistryCodec.readSnapshot(snapshot, "", "", "");
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

	static void gasRuntimeTransfersEnergyAndWork() {
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
		ThermalCarrierContext empty = emptyMarkers();

		AirNetwork compressedNetwork = new AirNetwork(gasRegistry);
		compressedNetwork.addNode(1.0, 1.0);
		ThermalDomainState compressed = FluidThermalBinding.create(registry, liquids,
				GasRegistry.STANDARD_TEMPERATURE_K, 1, 2, 2, 2, 0.5F, empty, compressedNetwork);
		double initialEnergy = compressed.gasNodeEnergy(1, compressedNetwork);
		GasEnergyStore.GasRemapPlan compression = compressed.planGasRemap(compressedNetwork,
				List.of(compressedNetwork.inventory(1)), new double[] {0.8}, new double[] {initialEnergy},
				new double[] {1.0}, 0.0, 0.0);
		compressedNetwork.remapNodes(new double[] {0.8}, List.of(compressedNetwork.inventory(1)),
				GasInventory.EMPTY, GasInventory.EMPTY);
		compressed.commitGasRemap(compression);
		require(compressed.gasTemperature(1, compressedNetwork) > GasRegistry.STANDARD_TEMPERATURE_K
				&& compressed.auditLedger().mechanicalWork().compareTo(SignedDyadic.ZERO) > 0
				&& compressed.exactEnergy(0).equals(compressed.auditLedger().expectedInventory()),
				"gas topology compression did not jointly commit U and mechanical work");

		AirNetwork flowingNetwork = new AirNetwork(gasRegistry);
		int high = flowingNetwork.addNode(1.0, 1.1), low = flowingNetwork.addNode(1.0, 0.9);
		flowingNetwork.addOrMergeEdge(high, low, 0.1, 0.65);
		ThermalDomainState flowing = FluidThermalBinding.create(registry, liquids,
				GasRegistry.STANDARD_TEMPERATURE_K, 1, 2, 2, 2, 0.5F, empty, flowingNetwork);
		flowingNetwork.stepEdges(0.01, thermodynamics(flowing, flowingNetwork),
				(source, target, moved, sourceAfter, targetAfter) -> flowing.planGasTransfer(
						source, target, moved, sourceAfter, targetAfter, flowingNetwork));
		require(flowing.gasTemperature(high, flowingNetwork) < GasRegistry.STANDARD_TEMPERATURE_K
				&& flowing.gasTemperature(low, flowingNetwork) > GasRegistry.STANDARD_TEMPERATURE_K
				&& flowing.exactEnergy(0).equals(flowing.auditLedger().expectedInventory()),
				"internal gas orifice did not carry source-state enthalpy with exact audit closure");

		flowingNetwork.addOrMergeEdge(high, AirNetwork.AMBIENT_NODE, 0.1, 0.65);
		flowingNetwork.stepEdges(0.01, thermodynamics(flowing, flowingNetwork),
				(source, target, moved, sourceAfter, targetAfter) -> flowing.planGasTransfer(
						source, target, moved, sourceAfter, targetAfter, flowingNetwork));
		require(flowing.auditLedger().externalSink().compareTo(SignedDyadic.ZERO) > 0
				&& flowing.exactEnergy(0).equals(flowing.auditLedger().expectedInventory()),
				"ambient gas outflow did not book carried enthalpy as an external sink");
	}

	static void persistenceBarrierSynchronizesGasTopology() {
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

		FluidDomain domain = new FluidDomain(4, 4, 4, config, 1_024);
		domain.sealDomainBoundary();
		domain.setVolume(0, 0, 0, 0.5F);
		domain.setMarkerMaterial(0, liquids, 0);
		domain.bindGasRegistry(gas);
		domain.bootstrapGasState("");
		domain.bindThermal(thermal, liquids, 300.0);
		require(domain.gasBoundary().nodeCount() == 1, "persistence topology fixture did not start as one cavity");

		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) domain.setVolume(2, y, z, 1.0F);
		FlipMaterialParticleState carriers = domain.exportMaterialParticleState();
		GasInventory beforeGas = domain.gasBoundary().inventory(1);
		var stale = domain.exportGasState("");
		FluidDomain rejected = new FluidDomain(4, 4, 4, config, 1_024);
		rejected.sealDomainBoundary();
		rejected.restoreMaterialParticleState(carriers);
		expectFailure(() -> rejected.restoreGasState(stale, gas),
				"stale gas topology unexpectedly matched the post-carrier checkpoint geometry");

		domain.synchronizeGasTopologyForPersistence();
		var synchronizedGas = domain.exportGasState("");
		FluidDomain restored = new FluidDomain(4, 4, 4, config, 1_024);
		restored.sealDomainBoundary();
		restored.restoreMaterialParticleState(carriers);
		restored.restoreGasState(synchronizedGas, gas);
		require(restored.gasBoundary().nodeCount() == 2
				&& restored.gasBoundary().inventory(1).merge(restored.gasBoundary().inventory(2)).equals(beforeGas)
				&& restored.gasBoundary().auditClosed(),
				"checkpoint gas-topology barrier did not preserve and restore the split inventory");

		FluidDomain movingInterface = new FluidDomain(3, 2, 2, config, 128);
		movingInterface.sealDomainBoundary();
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++) {
			if (!(y == 0 && z == 0 && (x == 0 || x == 1))) movingInterface.setSolid(x, y, z, true);
		}
		movingInterface.setVolume(1, 0, 0, 0.5F);
		movingInterface.bindGasRegistry(gas);
		movingInterface.bootstrapGasState("");
		var sourceBeforeMove = movingInterface.gasBoundary().externalSource();
		var sinkBeforeMove = movingInterface.gasBoundary().escapedSink();
		movingInterface.setVolume(1, 0, 0, 0.0F);
		movingInterface.setVolume(0, 0, 0, 0.5F);
		movingInterface.synchronizeGasTopologyForPersistence();
		require(movingInterface.gasBoundary().externalSource().equals(sourceBeforeMove)
				&& movingInterface.gasBoundary().escapedSink().equals(sinkBeforeMove)
				&& movingInterface.gasBoundary().auditClosed(),
				"moving a partial interface created false sealed-gas source/sink turnover");

		FluidDomain ambientPinch = new FluidDomain(3, 3, 3, config, 128);
		ambientPinch.bindGasRegistry(gas);
		ambientPinch.bootstrapGasState("");
		ambientPinch.bindThermal(thermal, liquids, GasRegistry.STANDARD_TEMPERATURE_K);
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
			if (Math.abs(x - 1) + Math.abs(y - 1) + Math.abs(z - 1) == 1) {
				ambientPinch.setSolid(x, y, z, true);
			}
		}
		ambientPinch.synchronizeGasTopologyForPersistence();
		long centerCellNanos = AirNetwork.nanos(0.125);
		require(ambientPinch.gasBoundary().nodeCount() == 1
				&& ambientPinch.gasBoundary().standardVolumeNanos(1) == centerCellNanos
				&& ambientPinch.gasBoundary().drawnFromAmbientNanos() == centerCellNanos
				&& ambientPinch.gasBoundary().auditClosed()
				&& ambientPinch.exactInternalEnergy().equals(
						ambientPinch.thermalAudit().expectedInventory()),
				"first ambient-to-sealed pinch did not trap its actual source volume and U");

		FluidDomain vacuumBirth = new FluidDomain(3, 3, 3, config, 128);
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
			vacuumBirth.setSolid(x, y, z, true);
		}
		vacuumBirth.bindGasRegistry(gas);
		vacuumBirth.bootstrapGasState("");
		vacuumBirth.bindThermal(thermal, liquids, GasRegistry.STANDARD_TEMPERATURE_K);
		vacuumBirth.setSolidMicroMask(1, 1, 1, 0xAA);
		vacuumBirth.synchronizeGasTopologyForPersistence();
		require(vacuumBirth.gasBoundary().nodeCount() == 1
				&& vacuumBirth.gasBoundary().inventory(1).isEmpty()
				&& vacuumBirth.gasBoundary().drawnFromAmbientNanos() == 0L
				&& vacuumBirth.exactInternalEnergy().equals(
						vacuumBirth.thermalAudit().expectedInventory()),
				"first unowned sealed cavity was not preserved as explicit vacuum");

		FluidDomain vacuumMerge = new FluidDomain(2, 2, 2, config, 128);
		vacuumMerge.sealDomainBoundary();
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
			vacuumMerge.setSolid(x, y, z, true);
		}
		vacuumMerge.setSolidMicroMask(0, 0, 0, 0xAA);
		vacuumMerge.bindGasRegistry(gas);
		vacuumMerge.bootstrapGasState("");
		vacuumMerge.bindThermal(thermal, liquids, GasRegistry.STANDARD_TEMPERATURE_K);
		SignedDyadic energyBeforeVacuum = vacuumMerge.exactInternalEnergy();
		double temperatureBeforeVacuum = vacuumMerge.thermalState.gasTemperature(
				1, vacuumMerge.gasState.network);
		vacuumMerge.setSolidMicroMask(1, 0, 0, 0x55);
		vacuumMerge.synchronizeGasTopologyForPersistence();
		require(vacuumMerge.gasBoundary().nodeCount() == 2
				&& vacuumMerge.gasBoundary().inventory(2).isEmpty(),
				"new closed cut-cell cavity did not remain an explicit vacuum node");
		vacuumMerge.setSolidMicroMask(0, 0, 0, 0x55);
		vacuumMerge.setSolidMicroMask(1, 0, 0, 0xAA);
		vacuumMerge.synchronizeGasTopologyForPersistence();
		require(vacuumMerge.gasBoundary().nodeCount() == 1
				&& vacuumMerge.exactInternalEnergy().equals(energyBeforeVacuum)
				&& close(vacuumMerge.thermalState.gasTemperature(1, vacuumMerge.gasState.network),
					temperatureBeforeVacuum),
				"gas/vacuum topology merge created reversible boundary work or changed U");
	}

	static void thermalGasPressureRowUsesIsentropicCompliance() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		ThermalRegistry thermal = new ThermalRegistry(List.of(
				new ThermalMaterialDefinition("test:thermal/compliance_water",
						ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
						300.0, 0.0, 0.598,
						List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 4_184.0))),
				new ThermalMaterialDefinition("test:thermal/compliance_air",
						ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
						GasRegistry.STANDARD_TEMPERATURE_K, 0.0, 0.026,
						List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 718.0)))));
		FluidDomain domain = new FluidDomain(4, 4, 4, config, 128);
		domain.sealDomainBoundary();
		domain.setVolume(1, 1, 1, 1.0F);
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			domain.setMarkerMaterial(marker, liquids, 0);
		}
		domain.bindGasRegistry(gas);
		domain.bootstrapGasState("");
		domain.bindThermal(thermal, liquids, 300.0);
		GasInventory inventory = domain.gasBoundary().inventory(1);
		double available = domain.gasBoundary().availableVolume(1);
		double internalEnergy = domain.thermalState.gasNodeEnergy(1, domain.gasState.network);
		GasThermalModel.HydrodynamicState state = new GasThermalModel(thermal, gas)
				.hydrodynamicState(inventory, available, internalEnergy);
		double expectedStorage = config.density() * available * state.pressureCompliancePerPa()
				/ (config.timeStep() * (double) config.timeStep()
				* config.cellSize() * config.cellSize() * config.cellSize());
		domain.step();
		require(domain.projection.gasPressureUnknown[1]
				&& Math.abs(domain.projection.gasStorageCoefficient[1] - expectedStorage)
				<= Math.ulp((float) expectedStorage),
				"thermal gas pressure row did not use V/(gamma*p) from the authoritative inventory/U closure");
		double isothermalStorage = config.density() * available / state.absolutePressurePa()
				/ (config.timeStep() * (double) config.timeStep()
				* config.cellSize() * config.cellSize() * config.cellSize());
		require(domain.projection.gasStorageCoefficient[1] < isothermalStorage,
				"thermal gas pressure row silently used isothermal compliance");
	}

	static void operatorVentClearsGasAndEnergyTogether() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		ThermalRegistry thermal = new ThermalRegistry(List.of(
				new ThermalMaterialDefinition("test:thermal/vent_water",
						ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
						300.0, 0.0, 0.598,
						List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 4_184.0))),
				new ThermalMaterialDefinition("test:thermal/vent_air",
						ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
						GasRegistry.STANDARD_TEMPERATURE_K, 0.0, 0.026,
						List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 718.0)))));
		FluidDomain domain = new FluidDomain(3, 3, 3, config, 256);
		domain.sealDomainBoundary();
		domain.setVolume(1, 1, 1, 0.5F);
		domain.setMarkerMaterial(0, liquids, 0);
		domain.bindGasRegistry(gas);
		domain.bootstrapGasState("");
		domain.bindThermal(thermal, liquids, 300.0);
		GasInventory gasBefore = domain.gasBoundary().sealedInventory();
		SignedDyadic energyBefore = domain.exactGasInternalEnergy();
		int nodesBefore = domain.gasBoundary().nodeCount();

		FluidDomain.OperatorGasVent vented = domain.commitOperatorVentSealedGas(
				domain.planOperatorVentSealedGas());
		require(nodesBefore > 0 && vented.sealedNodes() == nodesBefore
				&& vented.removed().equals(gasBefore) && vented.internalEnergy().equals(energyBefore)
				&& domain.gasBoundary().nodeCount() == 0 && domain.gasBoundary().sealedInventory().isEmpty()
				&& domain.gasBoundary().escapedSink().equals(domain.gasBoundary().externalSource())
				&& domain.exactGasInternalEnergy().equals(SignedDyadic.ZERO)
				&& domain.gasBoundary().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"operator vent did not jointly settle sealed gas component and energy truth");
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.gasBoundary().nodeCount() == 0
				&& restored.operatorVentedGasTopology()
				&& restored.exactGasInternalEnergy().equals(SignedDyadic.ZERO)
				&& restored.gasBoundary().auditClosed()
				&& restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory()),
				"operator-vent state did not survive an authoritative handoff");
	}

	static void gasLiquidSaturationAndExactBatch() {
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
				&& model.saturation(transition, 373.15, GasInventory.EMPTY, 0.0).direction()
						== GasLiquidPhaseModel.Direction.BOIL
				&& model.saturation(transition, 373.15, batch,
						transition.maximumPressurePa() * 2.0).direction()
						== GasLiquidPhaseModel.Direction.CONDENSE
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

	static AirNetwork.NodeThermodynamics thermodynamics(
			ThermalDomainState thermal, AirNetwork network
	) {
		return new AirNetwork.NodeThermodynamics() {
			@Override public double gaugePressure(int nodeId) {
				return nodeId == AirNetwork.AMBIENT_NODE ? 0.0 : thermal.gasGaugePressure(nodeId, network);
			}
			@Override public double density(int nodeId) {
				return nodeId == AirNetwork.AMBIENT_NODE ? network.density(nodeId)
						: thermal.gasDensity(nodeId, network);
			}
		};
	}

	static ThermalCarrierContext emptyMarkers() {
		return new ThermalCarrierContext() {
			@Override public int markerCount() { return 0; }
			@Override public int cell(int marker) { throw new IllegalArgumentException(); }
			@Override public int fluidMaterialHandle(int marker) { throw new IllegalArgumentException(); }
			@Override public double mass(int marker) { throw new IllegalArgumentException(); }
			@Override public double volume(int marker) { throw new IllegalArgumentException(); }
			@Override public ChemicalMixtureRecord mixture(int marker) { throw new IllegalArgumentException(); }
			@Override public ChemicalRegistry chemicalRegistry() { return null; }
		};
	}

	static void sealedInterfaceBoilingCommitsJointInventory() {
		FluidMaterial waterCarrier = FluidMaterial.withSurfaceTension(
				"test:boiling_water", 1_000.0, "test:miscible", 0.0, 90.0);
		FluidMaterial oilCarrier = FluidMaterial.withSurfaceTension(
				"test:boiling_oil", 850.0, "test:miscible", 0.0, 30.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(waterCarrier, oilCarrier));
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
		ThermalMaterialDefinition oil = new ThermalMaterialDefinition("test:thermal/boiling_oil",
				ThermalMaterialDefinition.CarrierKind.LIQUID, oilCarrier.id(), 373.15, 0.0, 0.14,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 800.0, 2_000.0)));
		long liquidSvq = SolidVolume.SVQ_PER_CELL / 20_000L;
		long vaporSvq = liquidSvq * 1_700L;
		ExactRational batchMass = ExactRational.parse("52375/8388608");
		ThermalPhaseTransition boiling = new ThermalPhaseTransition("test:phase/boiling_water",
				water.id(), vapor.id(), 373.15, liquidSvq, vaporSvq, batchMass,
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 10_000.0, 3_000_000.0);
		ThermalRegistry thermal = new ThermalRegistry(
				List.of(water, oil, air, vapor), List.of(), List.of(boiling), "", "");
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(4, 4, 4, config, 512);
		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
			if (x == 0 || x == 3 || y == 0 || y == 3 || z == 0 || z == 3) domain.setSolid(x, y, z, true);
		}
		domain.setVolume(1, 1, 1, 1.0F);
		domain.setMarkerMaterial(0, liquids, 1);
		domain.bindThermal(thermal, liquids, 500.0);
		require(domain.gasBoundary().nodeCount() == 1, "boiling fixture did not form one sealed receiver");
		ThermalDisplaySnapshot display = domain.copyThermalDisplaySnapshot();
		int liquidCell = (1 * 4 + 1) * 4 + 1;
		require(display.cellTemperatureQ().length == 64
				&& Short.toUnsignedInt(display.cellTemperatureQ()[liquidCell]) == 500 * 16
				&& (display.debugFlags()[liquidCell] & ThermalDisplaySnapshot.FLAG_CONDENSED) != 0
				&& Byte.toUnsignedInt(display.coherentFraction()[liquidCell]) == 0
				&& display.gasNodes().size() == 1 && display.gasNodes().getFirst().nodeId() == 1,
				"thermal display did not derive quantized condensed/gas state in one rebuild");
		double liquidMassBefore = domain.totalMass();
		double oilVolumeBefore = domain.exactVolumeNear(1, 1, 1, 0, 1);
		double boiledSolverMass = SolverUnitScale.forCellSize(config.cellSize())
				.solverMass(batchMass.toDouble());
		long gasNanosBefore = domain.gasBoundary().standardVolumeNanos(1);
		SignedDyadic energyBefore = domain.exactInternalEnergy();
		ThermalAuditLedger auditBefore = domain.thermalAudit();
		domain.step(true, 128);
		FluidDomain.GasThermalPhaseStats stats = domain.lastGasThermalPhaseStats();
		GasComponentAmount vaporAmount = domain.gasBoundary().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR);
		require(stats.operations() > 1 && stats.operations() <= 128
				&& stats.boiledCells() == 1 && stats.condensedCells() == 0
				&& close(domain.totalMass(), liquidMassBefore - boiledSolverMass * stats.operations())
				&& vaporAmount != null && close(vaporAmount.mass(), batchMass.toDouble() * stats.operations())
				&& domain.gasBoundary().standardVolumeNanos(1) > gasNanosBefore
				&& domain.gasBoundary().auditClosed()
				&& domain.thermalAudit().mechanicalWork().compareTo(auditBefore.mechanicalWork()) < 0
				&& domain.hasPendingPhaseVolumeWork()
				&& close(domain.exactVolumeNear(1, 1, 1, 0, 1), oilVolumeBefore)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"sealed boiling did not jointly conserve liquid/gas mass, volume and energy truth: stats=" + stats
						+ " liquidMass=" + domain.totalMass() + "/" + liquidMassBefore
						+ " vapor=" + vaporAmount + " nanos=" + domain.gasBoundary().standardVolumeNanos(1)
						+ "/" + gasNanosBefore + " deltaU="
						+ domain.exactInternalEnergy().subtract(energyBefore));
		ThermalAuditLedger beforeBoilingProjection = domain.thermalAudit();
		domain.step(false, 0);
		require(!domain.hasPendingPhaseVolumeWork()
				&& domain.thermalAudit().mechanicalWork().compareTo(
						beforeBoilingProjection.mechanicalWork()) > 0
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"boiling phase volume was not consumed by the next real pressure projection");
		domain.setThermalTemperatureInCell(1, 1, 1, 300.0);
		double liquidMassAfterBoiling = domain.totalMass();
		SignedDyadic beforeCondensation = domain.exactInternalEnergy();
		ThermalAuditLedger auditBeforeCondensation = domain.thermalAudit();
		FlipThermalParticleState beforeCondensationState = domain.exportThermalParticleState();
		FluidDomain.GasThermalPhaseStats condensed = domain.stepGasPhaseChangeOnly(stats.operations());
		GasComponentAmount vaporAfterCondensation = domain.gasBoundary().inventory(1)
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
				&& domain.gasBoundary().standardVolumeNanos(1)
						== gasNanosBefore + vaporAfterCondensation.standardVolumeNanos()
				&& domain.gasBoundary().auditClosed()
				&& domain.thermalAudit().mechanicalWork().compareTo(
						auditBeforeCondensation.mechanicalWork()) > 0
				&& domain.hasPendingPhaseVolumeWork()
				&& close(domain.exactVolumeNear(1, 1, 1, 0, 1), oilVolumeBefore)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"sealed condensation did not reverse the exact gas batch with conserved energy: stats=" + condensed
						+ " mass=" + domain.totalMass() + " vapor=" + vaporAfterCondensation
						+ " condensedKg=" + condensedPhysicalMass + "/" + removedVaporMass
						+ " expectedVapor=" + (batchMass.toDouble() * remainingBatches)
						+ " nanos=" + domain.gasBoundary().standardVolumeNanos(1) + "/" + gasNanosBefore
						+ " workCmp=" + domain.thermalAudit().mechanicalWork().compareTo(
						auditBeforeCondensation.mechanicalWork())
						+ " audit=" + domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
						+ " deltaU=" + domain.exactInternalEnergy().subtract(beforeCondensation)
						+ " gas=" + java.util.Arrays.toString(beforeCondensationState.copyGasNodeEnergy()) + "->"
						+ java.util.Arrays.toString(domain.exportThermalParticleState().copyGasNodeEnergy())
						+ " liquid=" + SignedDyadic.sum(beforeCondensationState.copyInternalEnergy()) + "->"
						+ SignedDyadic.sum(domain.exportThermalParticleState().copyInternalEnergy()));
		ThermalAuditLedger beforeCondensationProjection = domain.thermalAudit();
		domain.step(false, 0);
		require(!domain.hasPendingPhaseVolumeWork()
				&& domain.thermalAudit().mechanicalWork().compareTo(
						beforeCondensationProjection.mechanicalWork()) < 0
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"condensation phase volume was not consumed by the next real pressure projection");
	}

	static void compositionBearingBoilingMovesOnlyGasAdmittedSpecies() {
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
		FluidDomain domain = new FluidDomain(4, 4, 4, config, 512);
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
		GasComponentAmount gas = domain.gasBoundary().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR);
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

		domain.step(false, 0);
		require(!domain.hasPendingPhaseVolumeWork(),
				"species-bearing boiling did not consume pending volume before condensation");
		setAllCondensedCellTemperatures(domain, 260.0);
		FluidDomain.GasThermalPhaseStats firstCondensed = domain.stepGasPhaseChangeOnly(1);
		GasComponentAmount vaporRemainder = domain.gasBoundary().inventory(1)
				.component(BuiltinGasDefinitions.WATER_VAPOR);
		require(firstCondensed.operations() == 1 && firstCondensed.condensedCells() == 1
				&& firstCondensed.boiledCells() == 0 && vaporRemainder != null
				&& domain.hasPendingPhaseVolumeWork()
				&& domain.gasBoundary().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"species-bearing partial condensation did not retain its exact gas remainder: stats="
						+ firstCondensed + " vapor=" + vaporRemainder + " gasAudit="
						+ domain.gasBoundary().auditClosed() + " thermalAudit="
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
				&& domain.gasBoundary().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"species-bearing partial condensation did not conserve liquid/gas truth: first="
						+ firstCondensed + " boiledGas=" + gas + " remainder=" + vaporRemainder
						+ " h2o=" + partiallyRestored.copySpeciesTotals()[chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)]
						+ "+" + remainingGasH2o + "/" + initialH2o + " residual="
						+ partiallyRestored.residualLedger() + "+" + vaporRemainder.massResidual()
						+ "/" + initial.residualLedger() + " audit="
						+ domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()));
	}

	static double gasSpeciesMassKilograms(ChemicalRegistry registry, GasComponentAmount component) {
		ExactRational massKilograms = ExactRational.ZERO;
		for (GasComponentAmount.SpeciesAmount amount : component.species()) {
			massKilograms = massKilograms.add(registry.species(registry.speciesHandle(amount.speciesId())).massKilogramsPerAu()
					.multiply(amount.amountQuanta()).divide(ChemicalLimits.QUANTA_PER_AU));
		}
		return massKilograms.toDouble();
	}

	static void setAllCondensedCellTemperatures(FluidDomain domain, double temperatureK) {
		FlipMaterialParticleState state = domain.exportMaterialParticleState();
		byte[] flags = domain.copyThermalDisplaySnapshot().debugFlags();
		for (int cell = 0; cell < flags.length; cell++) {
			if ((flags[cell] & ThermalDisplaySnapshot.FLAG_CONDENSED) == 0) continue;
			int x = cell % state.sizeX();
			int yz = cell / state.sizeX();
			domain.setThermalTemperatureInCell(x, yz % state.sizeY(), yz / state.sizeY(), temperatureK);
		}
	}

}
