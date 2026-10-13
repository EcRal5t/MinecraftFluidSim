package mmm.fluid;

import mmm.thermal.ThermalDomainState;

import mmm.thermal.GasEnergyStore;

import mmm.thermal.ThermalCarrierContext;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ExactRational;
import mmm.granular.SolidVolume;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularRegistry;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.fluid.air.GasComponentAmount;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.thermal.GasLiquidSpeciesPair;
import mmm.thermal.GasThermalModel;
import mmm.thermal.GasLiquidPhaseModel;
import mmm.units.SignedDyadic;
import mmm.units.SolverUnitScale;
import mmm.thermal.FiniteThermalDeviceDefinition;
import mmm.thermal.ThermalCellGrid;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalDefinitionCodec;
import mmm.thermal.ThermalDisplaySnapshot;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalPhaseTransition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalRegistryCodec;
import mmm.thermal.ThermalSpeciesOffset;
import mmm.thermal.ThermalStateFunction;
import mmm.thermal.ThermalTransport;
import mmm.simulation.ThermalActivity;
import mmm.simulation.FluidRegionLifecycle;

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
		gasDensityUsesTheFinalPhysicalQuotient();
		gasTopologyRemapsRetainCommonTemperature();
		gasRemapPlansRequireTheirPreparingOwner();
		gasInitializationAndRestoreValidateActualInventory();
		gasTransferBoundsScaleBeforeOverflow();
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		ThermalMaterialDefinition air = new ThermalMaterialDefinition("mmm:thermal/air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
				GasRegistry.STANDARD_TEMPERATURE_K, 10.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 718.0)));
		GasThermalModel model = new GasThermalModel(new ThermalRegistry(List.of(air)), gas);
		require(model.internalEnergyAtTemperature(GasInventory.EMPTY, GasRegistry.STANDARD_TEMPERATURE_K) == 0.0
				&& model.absolutePressure(GasInventory.EMPTY, 1.0, 0.0) == 0.0
				&& model.density(GasInventory.EMPTY, 1.0) == 0.0
				&& model.absolutePressure(GasInventory.EMPTY, 0.0, 0.0) == 0.0
				&& model.density(GasInventory.EMPTY, 0.0) == 0.0,
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
		double arbitraryEnergy = initial + 123.456789;
		GasThermalModel.Compression unchanged = model.compressAdiabatically(inventory, arbitraryEnergy, 1.0, 1.0);
		require(Double.doubleToRawLongBits(unchanged.finalInternalEnergy()) == Double.doubleToRawLongBits(arbitraryEnergy)
				&& unchanged.workIntoGas() == 0.0,
				"unchanged gas volume manufactured caloric inversion work");
		ThermalMaterialDefinition highCapacityAir = new ThermalMaterialDefinition("test:thermal/high_capacity_air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
				GasRegistry.STANDARD_TEMPERATURE_K, 10.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 30_000.0)));
		GasThermalModel highCapacityModel = new GasThermalModel(new ThermalRegistry(List.of(highCapacityAir)), gas);
		double highCapacityEnergy = highCapacityModel.internalEnergyAtTemperature(inventory, GasRegistry.STANDARD_TEMPERATURE_K);
		GasThermalModel.Compression farExpansion = highCapacityModel.compressAdiabatically(inventory,
				highCapacityEnergy, 1.0E-200, 1.0E200);
		double expectedFarTemperature = GasRegistry.STANDARD_TEMPERATURE_K * Math.exp(expectedGasConstant
				* (Math.log(1.0E-200) - Math.log(1.0E200)) / (inventory.totalMass() * 30_000.0));
		require(Math.abs(farExpansion.finalTemperatureK() / expectedFarTemperature - 1.0) < 1.0E-12,
				"representable isentrope failed when the raw volume ratio underflowed");
		ThermalMaterialDefinition coldReference = new ThermalMaterialDefinition("test:thermal/cold_reference_air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
				1.0E-300, 0.0, 0.025, List.of(new ThermalMaterialDefinition.CaloricSegment(
						ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, 1_500.0, 150.0)));
		GasThermalModel coldReferenceModel = new GasThermalModel(new ThermalRegistry(List.of(coldReference)), gas);
		double coldEnergy = coldReferenceModel.internalEnergyAtTemperature(inventory, ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
		double exactMinimumEnergy = new java.math.BigDecimal(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)
				.subtract(new java.math.BigDecimal(coldReference.referenceTemperatureK()))
				.multiply(new java.math.BigDecimal(inventory.totalMass())).multiply(java.math.BigDecimal.valueOf(150)).doubleValue();
		require(coldEnergy == exactMinimumEnergy, "cold gas forward energy differs from its exact caloric integral");
		double actualColdTemperature = new java.math.BigDecimal(coldEnergy)
				.divide(new java.math.BigDecimal(inventory.totalMass()).multiply(java.math.BigDecimal.valueOf(150)),
						new java.math.MathContext(110))
				.add(new java.math.BigDecimal(coldReference.referenceTemperatureK())).doubleValue();
		// Final storage rounding at MIN may lie below the numeric domain by at most half an energy ULP.
		actualColdTemperature = Math.max(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, actualColdTemperature);
		require(Double.doubleToRawLongBits(coldReferenceModel.state(inventory, coldEnergy).temperatureK())
				== Double.doubleToRawLongBits(actualColdTemperature),
				"cold gas inversion lost the temperature carried by its actual raw energy");
		double heatedVolume = Math.exp(-inventory.totalMass() * 150.0 / expectedGasConstant
				* (Math.log(100.0) - Math.log(actualColdTemperature)));
		GasThermalModel.Compression coldHeating = coldReferenceModel.compressAdiabatically(
				inventory, coldEnergy, 1.0, heatedVolume);
		require(Math.abs(coldHeating.finalTemperatureK() / 100.0 - 1.0) < 1.0E-12,
				"scaled isentropic exponential overflowed despite a representable final temperature");
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
		GasThermalModel.Compression belowDeclaredRange = piecewise.compressAdiabatically(
				inventory, piecewiseInitial, 1.0, 100.0);
		GasThermalModel.Compression belowDeclaredRangeReversed = piecewise.compressAdiabatically(
				inventory, belowDeclaredRange.finalInternalEnergy(), 100.0, 1.0);
		require(belowDeclaredRange.finalTemperatureK() < piecewiseAir.minimumTemperatureK()
				&& belowDeclaredRange.finalTemperatureK() >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K
				&& Math.abs(belowDeclaredRangeReversed.finalInternalEnergy() - piecewiseInitial) < 1.0E-8,
				"gas isentrope did not continuously extend and reverse below the declared caloric range");
		GasThermalModel.Compression effectivelyZero = piecewise.compressAdiabatically(
				inventory, piecewiseInitial, 1.0, 1.0E15);
		GasThermalModel.Compression effectivelyZeroReversed = piecewise.compressAdiabatically(
				inventory, effectivelyZero.finalInternalEnergy(), 1.0E15, 1.0);
		require(effectivelyZero.finalTemperatureK() > 0.0 && effectivelyZero.finalTemperatureK() < 1.0
				&& Double.isFinite(effectivelyZero.finalInternalEnergy())
				&& effectivelyZeroReversed.finalTemperatureK() > 250.0,
				"finite deep expansion still encountered an arbitrary one-kelvin stop wall");
		GasThermalModel.Compression beyondRangeCompression = model.compressAdiabatically(
				inventory, model.internalEnergyAtTemperature(inventory, 2_000.0), 1.0, Math.nextDown(1.0));
		require(beyondRangeCompression.finalTemperatureK() > 1_999.0
				&& beyondRangeCompression.finalTemperatureK() < 2_001.0
				&& Double.isFinite(beyondRangeCompression.finalInternalEnergy())
				&& beyondRangeCompression.workIntoGas() >= 0.0,
				"below-resolution isentropic compression above the caloric range was rejected");
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
		mixedGasEnergyPartitionsRetainCommonTemperature();
	}

	private static void mixedGasEnergyPartitionsRetainCommonTemperature() {
		List<String> ids = List.of("test:partition_a", "test:partition_b", "test:partition_c");
		GasRegistry gas = new GasRegistry(ids.stream().map(id -> new mmm.fluid.air.GasComponentDefinition(
				id, 8, 8, List.of(), List.of())).toList(),
				List.of(new GasRegistry.AmbientFraction(ids.getFirst(), ExactRational.ONE)));
		for (double[] references : new double[][] {{1e18, 200, -1e18}, {1e18, -1e18, 200}, {100, 200, -100}}) {
			java.util.ArrayList<ThermalMaterialDefinition> materials = new java.util.ArrayList<>();
			java.util.ArrayList<GasComponentAmount> components = new java.util.ArrayList<>();
			for (int component = 0; component < ids.size(); component++) {
				materials.add(new ThermalMaterialDefinition("test:thermal/partition_" + component,
						ThermalMaterialDefinition.CarrierKind.GAS, ids.get(component), 300, references[component], 0,
						List.of(new ThermalMaterialDefinition.CaloricSegment(1, 900, 1))));
				components.add(new GasComponentAmount(ids.get(component), 125_000_000L, 1, List.of(), 1));
			}
			GasThermalModel model = new GasThermalModel(new ThermalRegistry(materials), gas);
			GasInventory source = new GasInventory(components);
			for (double temperature : new double[] {300, 300.5}) {
				double sourceEnergy = model.internalEnergyAtTemperature(source, temperature);
				double expected = 200 + 3 * (temperature - 300);
				List<GasInventory> children = source.partition(new long[] {1, 1});
				double[] energies = model.partitionEnergy(source, sourceEnergy, children);
				require(sourceEnergy == expected && model.state(source, sourceEnergy).temperatureK() == temperature
						&& Arrays.equals(energies, new double[] {expected / 2, expected / 2})
						&& model.state(children.get(0), energies[0]).temperatureK() == temperature
						&& model.state(children.get(1), energies[1]).temperatureK() == temperature
						&& SignedDyadic.sum(energies).equals(SignedDyadic.of(sourceEnergy)),
						"gas component reduction changed a common-temperature exact split");
				require(model.internalEnergyAtTemperature(source, temperature) == sourceEnergy,
						"gas partition changed its source inventory or energy");
			}
		}
		for (double sign : new double[] {1, -1}) {
			ThermalRegistry thermal = new ThermalRegistry(List.of(
					new ThermalMaterialDefinition("test:thermal/extreme_a", ThermalMaterialDefinition.CarrierKind.GAS,
							ids.get(0), 1, sign * Double.MAX_VALUE, 0,
							List.of(new ThermalMaterialDefinition.CaloricSegment(1, 2, 5e307))),
					new ThermalMaterialDefinition("test:thermal/extreme_b", ThermalMaterialDefinition.CarrierKind.GAS,
							ids.get(1), 1, -sign * Double.MAX_VALUE, 0,
							List.of(new ThermalMaterialDefinition.CaloricSegment(1, 2, 5e307)))));
			GasThermalModel model = new GasThermalModel(thermal, gas);
			GasInventory source = new GasInventory(List.of(new GasComponentAmount(ids.get(0), 125_000_000L, 1, List.of(), 1),
					new GasComponentAmount(ids.get(1), 125_000_000L, 1, List.of(), 1)));
			double temperature = Math.nextUp(1.0);
			double expected = new java.math.BigDecimal(5e307).multiply(java.math.BigDecimal.valueOf(2))
					.multiply(new java.math.BigDecimal(temperature).subtract(java.math.BigDecimal.ONE)).doubleValue();
			double energy = model.internalEnergyAtTemperature(source, temperature);
			require(energy == expected && model.state(source, energy).temperatureK() == temperature
					&& model.partitionEnergy(source, energy, List.of(source))[0] == energy,
					"finite mixed gas energy depends on overflowing virtual component energies");
		}
	}

	static void gasInitializationAndRestoreValidateActualInventory() {
		var gas = new GasRegistry(List.of(new mmm.fluid.air.GasComponentDefinition(
				"test:restore_gas", 1, 1, List.of(), List.of())),
				List.of(new GasRegistry.AmbientFraction("test:restore_gas", ExactRational.ONE)));
		var liquids = new FluidMaterialRegistry(List.of(new FluidMaterial("mmm:water", 1000, "mmm:water", 0, Math.PI / 2)));
		var water = new ThermalMaterialDefinition("test:thermal/restore_water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"mmm:water", 500, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 600, 4180)));
		var material = new ThermalMaterialDefinition("test:thermal/restore_gas", ThermalMaterialDefinition.CarrierKind.GAS,
				"test:restore_gas", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 400, 750),
				new ThermalMaterialDefinition.CaloricSegment(400, 600, 1e308)));
		var registry = new ThermalRegistry(List.of(water, material));
		var config = new FluidSolverConfig(.5F, 1000, 0, .001F, 100, 1e-5F, .45F, .002F, .8F);
		var source = new FluidDomain(2, 2, 2, config, 256);
		source.sealDomainBoundary(); source.bindGasRegistry(gas); source.bindThermal(registry, liquids, 300);
		var snapshot = source.captureAuthoritativeState(); var inventory = snapshot.gas().nodes().getFirst().inventory();
		var model = new GasThermalModel(registry, gas);
		var state = model.hydrodynamicState(inventory, 1, snapshot.thermal().copyGasNodeEnergy()[0]);
		require(inventory.standardVolumeNanos() == 1_000_000_000L && inventory.totalMass() == 1
				&& state.temperatureK() == 300 && state.heatCapacityEuPerK() == 750 && state.isentropicExponent() > 1
				&& snapshot.thermal().copyGasNodeEnergy()[0] == 0 && snapshot.thermal().audit().expectedInventory().equals(SignedDyadic.ZERO),
				"gas restore fixture does not have a valid captured caloric and hydrodynamic state");
		boolean disposableOverflow = false;
		try { model.internalEnergyAtTemperature(inventory, water.referenceTemperatureK()); }
		catch (IllegalArgumentException expected) { disposableOverflow = true; }
		require(disposableOverflow, "gas restore fixture does not expose disposable default-temperature energy overflow");
		var restored = FluidDomain.restoreAuthoritativeState(snapshot);
		var recaptured = restored.captureAuthoritativeState();
		require(restored.gasNodeTemperatureK(1) == 300 && restored.gasNodeGaugePressurePa(1) == source.gasNodeGaugePressurePa(1)
				&& recaptured.gas().nodes().getFirst().inventory().equals(inventory)
				&& Arrays.equals(recaptured.thermal().copyGasNodeEnergy(), snapshot.thermal().copyGasNodeEnergy())
				&& recaptured.thermal().audit().equals(snapshot.thermal().audit()),
				"gas restore modeled disposable default-temperature energy or changed authoritative gas truth");
		var rounded = new ThermalMaterialDefinition("test:thermal/rounded_initial_gas", ThermalMaterialDefinition.CarrierKind.GAS,
				"test:restore_gas", 350, 1e18, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 600, 1)));
		var roundedRegistry = new ThermalRegistry(List.of(water, rounded)); var roundedModel = new GasThermalModel(roundedRegistry, gas);
		double invalidEnergy = roundedModel.internalEnergyAtTemperature(inventory, 1);
		require(invalidEnergy - 1e18 == -384, "gas initialization fixture does not expose a rounded negative-temperature state");
		var network = new AirNetwork(gas); network.addNode(1, 0); network.commitPhaseSource(1, inventory);
		var audit = new mmm.thermal.ThermalAuditState(ThermalAuditLedger.initialized(SignedDyadic.ZERO));
		var before = audit.ledger(); boolean rejected = false;
		try { new GasEnergyStore(roundedModel, 1, audit, network); }
		catch (ThermalStateFunction.EnergyOutsideValidityException expected) { rejected = true; }
		require(rejected && audit.ledger().equals(before) && network.inventory(1).equals(inventory),
				"gas constructor admitted an invalid rounded U or changed its source before rejecting it");
		var invalid = new FluidDomain(2, 2, 2, config, 256); invalid.sealDomainBoundary(); invalid.bindGasRegistry(gas); rejected = false;
		try { invalid.bindThermal(roundedRegistry, liquids, 1); }
		catch (ThermalStateFunction.EnergyOutsideValidityException expected) { rejected = true; }
		require(rejected && invalid.thermalState == null, "public gas binding published an invalid caloric owner");
	}

	static void gasTransferBoundsScaleBeforeOverflow() {
		// A large first segment followed by individually sub-ULP segments retains their exact caloric energy.
		var segments = new java.util.ArrayList<ThermalMaterialDefinition.CaloricSegment>();
		segments.add(new ThermalMaterialDefinition.CaloricSegment(1, 2, 1e18));
		for (int end = 2; end < 17; end++) segments.add(new ThermalMaterialDefinition.CaloricSegment(end, end + 1, 128));
		var piecewiseGas = new GasRegistry(List.of(new mmm.fluid.air.GasComponentDefinition(
				"test:piecewise_gas", 1e-9, 1e-9, List.of(), List.of())),
				List.of(new GasRegistry.AmbientFraction("test:piecewise_gas", ExactRational.ONE)));
		var piecewiseThermal = new ThermalRegistry(List.of(new ThermalMaterialDefinition("test:thermal/piecewise_gas",
				ThermalMaterialDefinition.CarrierKind.GAS, "test:piecewise_gas", 17, 2e18, 0, segments)));
		var piecewiseModel = new GasThermalModel(piecewiseThermal, piecewiseGas);
		var piecewiseInventory = new GasInventory(List.of(new GasComponentAmount("test:piecewise_gas",
				1_000_000_000_000_000_000L, 1, List.of(), 1)));
		piecewiseGas.requireCompatible(piecewiseInventory);
		var piecewiseNetwork = new AirNetwork(piecewiseGas);
		piecewiseNetwork.addNode(1, 0); piecewiseNetwork.addNode(.2, 0);
		piecewiseNetwork.commitPhaseSource(1, piecewiseInventory); piecewiseNetwork.commitPhaseSource(2, piecewiseInventory);
		var piecewiseState = new ThermalDomainState(piecewiseThermal, new int[0], new int[0], 17,
				0, 1, 1, 1, 1, emptyMarkers(), piecewiseNetwork, 1);
		piecewiseState.restoreGasEnergies(piecewiseNetwork, new double[]{-1024, -1792});
		piecewiseState.restoreAuditLedger(ThermalAuditLedger.initialized(SignedDyadic.of(-2816)));
		double sourceT = new java.math.BigDecimal(896).divide(new java.math.BigDecimal(1e18),
				java.math.MathContext.DECIMAL128).doubleValue();
		double targetT = new java.math.BigDecimal(128).divide(new java.math.BigDecimal(1e18),
				java.math.MathContext.DECIMAL128).doubleValue();
		require(piecewiseState.gasTemperature(1, piecewiseNetwork) == sourceT
				&& piecewiseState.gasTemperature(2, piecewiseNetwork) == targetT,
				"piecewise gas inventory lost the original-reference caloric integral");
		java.util.function.LongPredicate piecewisePermits = amount -> {
			try {
				var split = piecewiseInventory.splitTotal(amount); double carried = piecewiseModel.enthalpy(split.first(), sourceT);
				return piecewiseModel.absolutePressure(split.second(), 1, -1024 - carried)
						>= piecewiseModel.absolutePressure(piecewiseInventory.merge(split.first()), .2, -1792 + carried);
			} catch (IllegalArgumentException | ArithmeticException invalid) { return false; }
		};
		long oracle = 0;
		for (int amount = 1; amount <= 512; amount++) if (piecewisePermits.test(amount)) oracle = amount;
		var piecewiseRevision = piecewiseState.thermalRevision(); var piecewiseAudit = piecewiseState.auditLedger();
		long found = piecewiseState.gasThermodynamics(piecewiseNetwork).limitTransferNanos(1, 2, 512);
		require(oracle == 333 && found == oracle && piecewisePermits.test(found) && !piecewisePermits.test(found + 1)
				&& piecewiseState.thermalRevision().equals(piecewiseRevision) && piecewiseState.auditLedger().equals(piecewiseAudit)
				&& piecewiseState.gasNodeEnergy(1, piecewiseNetwork) == -1024 && piecewiseState.gasNodeEnergy(2, piecewiseNetwork) == -1792
				&& piecewiseNetwork.inventory(1).equals(piecewiseInventory) && piecewiseNetwork.inventory(2).equals(piecewiseInventory),
				"gas search pruned valid portions using rounded minimum-temperature prefixes or changed a preview");
		for (int scenario = 0; scenario < 5; scenario++) {
			double density = scenario == 0 ? 1 : 1e-300, cv = scenario == 0 ? 10_000 : 1e307;
			double reference = scenario == 3 ? 1e307 : scenario == 4 ? -1e307 : 0;
			double temperature = scenario == 2 ? 600 : scenario == 3 ? 299 : scenario == 4 ? 301 : 300;
			var definition = new mmm.fluid.air.GasComponentDefinition("test:scaled_gas",
					density, density, List.of(), List.of());
			var gas = new GasRegistry(List.of(definition), List.of(new GasRegistry.AmbientFraction(definition.id(), ExactRational.ONE)));
			var material = new ThermalMaterialDefinition("test:thermal/scaled_gas", ThermalMaterialDefinition.CarrierKind.GAS,
					definition.id(), 300, reference, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1_000, cv)));
			var model = new GasThermalModel(new ThermalRegistry(List.of(material)), gas);
			var network = new AirNetwork(gas); network.addNode(.125, 0); network.addNode(.125, 0);
			var source = gas.createAmbient(1_000_000_000L); var target = gas.createAmbient(500_000_000L);
			gas.requireCompatible(source); gas.requireCompatible(target);
			network.commitPhaseSource(1, source); network.commitPhaseSource(2, target);
			var audit = new mmm.thermal.ThermalAuditState(ThermalAuditLedger.initialized(SignedDyadic.ZERO));
			var store = new GasEnergyStore(model, temperature, audit, network);
			double sourceEnergy = store.nodeEnergy(1, network), targetEnergy = store.nodeEnergy(2, network);
			double actualTemperature = store.temperature(1, network);
			model.hydrodynamicState(source, .125, sourceEnergy); model.hydrodynamicState(target, .125, targetEnergy);
			java.util.function.LongPredicate permits = amount -> {
				try {
					var split = source.splitTotal(amount); double carried = model.enthalpy(split.first(), actualTemperature);
					return model.absolutePressure(split.second(), .125, sourceEnergy - carried)
							>= model.absolutePressure(target.merge(split.first()), .125, targetEnergy + carried);
				} catch (IllegalArgumentException | ArithmeticException invalid) { return false; }
			};
			// With one constant-Cv component and equal initial temperatures, the pressure difference decreases with the portion.
			long low = 0, high = source.standardVolumeNanos();
			while (low + 1 < high) {
				long middle = low + (high - low) / 2;
				if (permits.test(middle)) low = middle; else high = middle;
			}
			var before = network.inventory(1); var beforeTarget = network.inventory(2);
			long revision = store.revision();
			long actual = store.thermodynamics(network).limitTransferNanos(1, 2, source.standardVolumeNanos());
			require(actual == low && permits.test(actual) && !permits.test(actual + 1)
					&& network.inventory(1).equals(before) && network.inventory(2).equals(beforeTarget)
					&& store.revision() == revision && store.nodeEnergy(1, network) == sourceEnergy
					&& store.nodeEnergy(2, network) == targetEnergy,
					"finite gas transfer lost its caloric bounds through specific integral overflow or mutated a preview");
			if (scenario == 0) require(actual == 241_647_638L, "ordinary gas transfer bounds changed");
			if (scenario == 1) require(actual == 249_991_359L, "scaled gas transfer no longer matches its monotone pressure oracle");
		}
		for (boolean scaled : new boolean[]{false, true}) {
			double cv = scaled ? 1e308 : 10_000, density = scaled ? 1e-300 : 1;
			double initialVolume = scaled ? 1e-300 : 1, finalVolume = scaled ? 1e20 : 2;
			var definition = new mmm.fluid.air.GasComponentDefinition("test:compression_gas",
					density, density, List.of(), List.of());
			var gas = new GasRegistry(List.of(definition), List.of(new GasRegistry.AmbientFraction(definition.id(), ExactRational.ONE)));
			var material = new ThermalMaterialDefinition("test:thermal/compression_gas", ThermalMaterialDefinition.CarrierKind.GAS,
					definition.id(), 600, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 2_000, cv)));
			var model = new GasThermalModel(new ThermalRegistry(List.of(material)), gas);
			var inventory = gas.createAmbient(1_000_000_000L);
			double initialEnergy = model.internalEnergyAtTemperature(inventory, 1_000);
			var initial = model.hydrodynamicState(inventory, initialVolume, initialEnergy);
			double exponent = initial.gasConstantEuPerK() / initial.heatCapacityEuPerK()
					* (Math.log(initialVolume) - Math.log(finalVolume));
			double expectedTemperature = initial.temperatureK() + initial.temperatureK() * Math.expm1(exponent);
			SignedDyadic capacity = SignedDyadic.of(inventory.totalMass()).multiply(SignedDyadic.of(cv));
			SignedDyadic expectedDelta = capacity.multiply(SignedDyadic.of(expectedTemperature)
					.subtract(SignedDyadic.of(initial.temperatureK())));
			double expectedEnergy = SignedDyadic.of(initialEnergy).add(expectedDelta).toDouble();
			var compression = model.compressAdiabatically(inventory, initialEnergy, initialVolume, finalVolume);
			var finalState = model.hydrodynamicState(inventory, finalVolume, compression.finalInternalEnergy());
			double temperatureBound = 4 * Math.ulp(expectedTemperature);
			double energyBound = capacity.toDouble() * temperatureBound + 4 * Math.ulp(expectedEnergy);
			require(Math.abs(compression.finalTemperatureK() - expectedTemperature) <= temperatureBound
					&& Math.abs(finalState.temperatureK() - expectedTemperature) <= temperatureBound
					&& Math.abs(compression.finalInternalEnergy() - expectedEnergy) <= energyBound
					&& Math.abs(compression.workIntoGas() - expectedDelta.toDouble()) <= energyBound
					&& compression.workIntoGas() == compression.finalInternalEnergy() - initialEnergy,
					"finite scaled isentropic gas expansion overflowed before mass scaling or lost its raw energy change");
		}
	}

	private static void tinyGasEnergyPartitions(GasThermalModel model, GasRegistry gas) {
		for (long nanos : new long[] {1_000_000_000L, 2_000_000_000_000_000L, 1L << 54, Long.MAX_VALUE}) {
			GasInventory source = gas.createAmbient(nanos);
			List<GasInventory> forward = source.partition(new long[] {nanos - 3, 0, 1, 2});
			List<GasInventory> reverse = source.partition(new long[] {2, 1, 0, nanos - 3});
			for (double temperature : new double[] {293.15, 100.0, 1.0, .001, 1e-5, 1e-6, 1e-9}) {
				double energy = model.internalEnergyAtTemperature(source, temperature);
				var state = model.state(source, energy);
				double[] energies = model.partitionEnergy(source, energy, forward);
				double[] reversed = model.partitionEnergy(source, energy, reverse);
				for (int child = 0; child < forward.size(); child++) {
					require(Double.doubleToRawLongBits(energies[child]) == Double.doubleToRawLongBits(reversed[3 - child]),
							"unique heat-capacity pivot depends on tiny child order");
					if (forward.get(child).isEmpty()) { require(energies[child] == 0.0, "vacuum received gas energy"); continue; }
					double modeled = model.internalEnergyAtTemperature(forward.get(child), state.temperatureK());
					model.requireInventoryEnergy(forward.get(child), modeled);
					var childState = model.state(forward.get(child), energies[child]);
					double inversionError = 32 * (Math.ulp(modeled) / childState.heatCapacityEuPerK()
							+ Math.ulp(energy) / state.heatCapacityEuPerK()) + 64 * Math.ulp(state.temperatureK());
					require(childState.temperatureK() > 0.0
							&& Math.abs(childState.temperatureK() - state.temperatureK()) <= inversionError,
							"tiny gas child amplified the topology energy remainder");
				}
				SignedDyadic error = SignedDyadic.sum(energies).subtract(SignedDyadic.of(energy));
				if (error.signum() < 0) error = error.negate();
				require(error.compareTo(SignedDyadic.of(Math.ulp(energies[0]) * .5)) <= 0,
						"gas energy partition exceeds single pivot rounding");
			}
		}
	}

	static void builtinDryAirWaterAndVaporPhysicsIsCalibrated() {
		try (InputStreamReader airReader = resourceReader(
				"/data/mmm/mmm/thermal_materials/air.json");
		     InputStreamReader waterReader = resourceReader(
				"/data/mmm/mmm/thermal_materials/water.json");
		     InputStreamReader iceReader = resourceReader(
				"/data/mmm/mmm/thermal_materials/ice.json");
		     InputStreamReader vaporReader = resourceReader(
				     "/data/mmm/mmm/thermal_materials/water_vapor.json");
		     InputStreamReader meltingReader = resourceReader(
				     "/data/mmm/mmm/phase_transitions/ice_water.json");
		     InputStreamReader transitionReader = resourceReader(
				     "/data/mmm/mmm/phase_transitions/water_vapor.json")) {
			ThermalMaterialDefinition air = ThermalDefinitionCodec.decodeMaterial("mmm:air", airReader);
			ThermalMaterialDefinition water = ThermalDefinitionCodec.decodeMaterial("mmm:water", waterReader);
			ThermalMaterialDefinition ice = ThermalDefinitionCodec.decodeMaterial("mmm:ice", iceReader);
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
			ThermalRegistry registry = new ThermalRegistry(List.of(air, water, vapor, ice), List.of(),
					List.of(transition, melting), "", "");
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
			long airNanos = 140_000_000L;
			long vaporNanos = 2_967_839L;
			GasInventory reportedExpansion = new GasInventory(List.of(
					new GasComponentAmount(BuiltinGasDefinitions.AIR, airNanos,
							AirNetwork.AIR_DENSITY * AirNetwork.cubicMeters(airNanos),
							List.of(), 0.0),
					new GasComponentAmount(BuiltinGasDefinitions.WATER_VAPOR, vaporNanos,
							BuiltinGasDefinitions.WATER_VAPOR_STANDARD_DENSITY
									* AirNetwork.cubicMeters(vaporNanos),
							List.of(), 0.0)));
			GasThermalModel reportedModel = new GasThermalModel(
					registry, BuiltinGasDefinitions.createRegistry());
			tinyGasEnergyPartitions(reportedModel, BuiltinGasDefinitions.createRegistry());
			GasInventory airChild = BuiltinGasDefinitions.createRegistry().createAmbient(2_000_000_000L);
			GasInventory vaporChild = new GasInventory(List.of(new GasComponentAmount(
					BuiltinGasDefinitions.WATER_VAPOR, 2_000_000_000L,
					2 * BuiltinGasDefinitions.WATER_VAPOR_STANDARD_DENSITY, List.of(), 0.0)));
			GasInventory mixed = airChild.merge(vaporChild);
			double mixedEnergy = Math.nextUp(reportedModel.internalEnergyAtTemperature(mixed, 321.0));
			double mixedTemperature = reportedModel.state(mixed, mixedEnergy).temperatureK();
			double[] mixedParts = reportedModel.partitionEnergy(mixed, mixedEnergy, List.of(vaporChild, airChild));
			double[] mixedReverse = reportedModel.partitionEnergy(mixed, mixedEnergy, List.of(airChild, vaporChild));
			require(airChild.totalMass() > vaporChild.totalMass()
					&& airChild.totalMass() * air.specificHeatAt(mixedTemperature)
						< vaporChild.totalMass() * vapor.specificHeatAt(mixedTemperature)
					&& mixedParts[1] == reportedModel.internalEnergyAtTemperature(airChild, mixedTemperature)
					&& mixedParts[0] == mixedReverse[1] && mixedParts[1] == mixedReverse[0],
					"gas energy remainder followed mass or child order instead of heat capacity");
			double reportedInitial = reportedModel.internalEnergyAtTemperature(reportedExpansion, 306.0691472872192);
			GasThermalModel.Compression reported = reportedModel.compressAdiabatically(
					reportedExpansion, reportedInitial, 0.13749912215963653, 0.34265727368591875);
			GasThermalModel.Compression reportedReverse = reportedModel.compressAdiabatically(
					reportedExpansion, reported.finalInternalEnergy(), 0.34265727368591875, 0.13749912215963653);
			require(reported.finalTemperatureK() < vapor.minimumTemperatureK()
					&& reported.finalTemperatureK() >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K
					&& reported.workIntoGas() < 0.0
					&& Math.abs(reportedReverse.finalInternalEnergy() - reportedInitial) < 1.0E-8,
					"reported water-vapor bubble expansion did not remain a reversible thermal candidate");
			require(close(melting.minimumPressurePa(), 611.657)
					&& close(melting.equilibriumTemperatureK(611.657), 273.16)
					&& close(melting.equilibriumTemperatureK(2_815.2265625), 273.15983635215986)
					&& close(melting.equilibriumTemperatureK(101_325.0), 273.15251907976949)
					&& close(melting.equilibriumTemperatureK(2_000_000.0), 273.01088655470164)
					&& melting.equilibriumTemperatureK(1_000_000.0)
					< melting.equilibriumTemperatureK(101_325.0),
					"IAPWS ice-Ih pressure curve did not lower the melting point");
			require(close(melting.equilibriumTemperatureK(208_566_000.0), 251.165)
					&& close(melting.equilibriumTemperatureK(2_216_000_000.0), 355.0)
					&& close(melting.equilibriumTemperatureK(melting.maximumPressurePa()), 715.0),
					"high-pressure melting curve lost its IAPWS branch junctions");
			var last = melting.equilibriumCurve().getLast();
			var previous = melting.equilibriumCurve().get(melting.equilibriumCurve().size() - 2);
			double extendedPressure = last.pressurePa() + 3.0 * (last.pressurePa() - previous.pressurePa());
			require(close(melting.equilibriumTemperatureK(extendedPressure),
					last.temperatureK() + 3.0 * (last.temperatureK() - previous.temperatureK()))
					&& melting.equilibriumTemperatureK(0.0) > 273.16
					&& melting.equilibriumTemperatureK(1.0E14) > 715.0,
					"condensed phase endpoint continuation changed slope or rejected finite high pressure");
			expectFailure(() -> melting.equilibriumTemperatureK(Double.NaN), "nonfinite phase pressure accepted");
			expectFailure(() -> melting.equilibriumTemperatureK(-1.0), "negative absolute phase pressure accepted");
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

	private static void gasDensityUsesTheFinalPhysicalQuotient() {
		for (boolean extreme : new boolean[] {false, true}) {
			double mass = extreme ? 1e308 : 1, capacity = extreme ? 1e-308 : 1;
			var gas = new GasRegistry(List.of(
					new mmm.fluid.air.GasComponentDefinition("test:a", 1, 1, List.of(), List.of()),
					new mmm.fluid.air.GasComponentDefinition("test:b", 1, 1, List.of(), List.of())),
					List.of(new GasRegistry.AmbientFraction("test:a", ExactRational.ONE)));
			var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
			var thermal = new ThermalRegistry(List.of(topologyMaterial("test:w", ThermalMaterialDefinition.CarrierKind.LIQUID,
					BuiltinFluidMaterials.WATER.id(), 0, 1), topologyMaterial("test:ta", ThermalMaterialDefinition.CarrierKind.GAS,
					"test:a", 0, capacity), topologyMaterial("test:tb", ThermalMaterialDefinition.CarrierKind.GAS, "test:b", 0, capacity)));
			var inventory = new GasInventory(List.of(new GasComponentAmount("test:a", 1_000_000L, mass, List.of(), mass),
					new GasComponentAmount("test:b", 1_000_000L, mass, List.of(), mass)));
			var domain = new FluidDomain(8, 4, 4,
					new FluidSolverConfig(.5F, 1000, 0, .001F, 100, 1e-5F, .45F, .002F, 0), 128);
			domain.sealDomainBoundary(); domain.setVolume(0, 0, 0, .125F);
			domain.bindGasRegistry(gas);
			var geometryTwin = new FluidDomain(8, 4, 4, domain.config, 128);
			geometryTwin.sealDomainBoundary();
			geometryTwin.restoreParticleState(domain.exportParticleState());
			geometryTwin.bindGasRegistry(gas);
			var topology = geometryTwin.bootstrapGasState("");
			require(topology.nodes().size() == 1, "Gas-density fixture must have one actual gas component");
			domain.restoreGasState(new mmm.fluid.air.GasStateSnapshot(8, 4, 4, gas.fingerprint(), "",
					List.of(new mmm.fluid.air.GasStateSnapshot.Node(topology.nodes().getFirst().topologyKey(), inventory)),
					topology.pieceNodeOrdinal(),
					mmm.fluid.air.GasAuditLedger.of(inventory), mmm.fluid.air.GasAuditLedger.EMPTY,
					SignedDyadic.ZERO, SignedDyadic.ZERO), gas);
			domain.bindThermal(thermal, liquids, 300);
			domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			var before = domain.captureAuthoritativeState();
			var network = domain.gasState.network; var stored = network.inventory(1);
			var source = network.externalSource(); var sink = network.escapedSink();
			var massRoundoff = network.massRoundoff(); var residualRoundoff = network.residualRoundoff();
			long gasRevision = network.revision();
			var thermalRevision = domain.thermalState.thermalRevision(); var audit = domain.thermalAudit();
			double[] marker = {domain.particles.positionX(0), domain.particles.positionY(0), domain.particles.positionZ(0),
					domain.particles.velocityX(0), domain.particles.velocityY(0), domain.particles.velocityZ(0),
					domain.particles.volume(0), domain.particles.mass(0)};
			double volume = network.availableVolume(1);
			double expected = new java.math.BigDecimal(mass).multiply(java.math.BigDecimal.valueOf(2))
					.divide(new java.math.BigDecimal(volume), new java.math.MathContext(110)).doubleValue();
			var hydro = domain.thermalState.gasHydrodynamicState(1, network);
			require(expected > 0 && Double.isFinite(expected) && network.density(1) == expected
					&& domain.thermalState.gasDensity(1, network) == expected && hydro.densityKgPerCubicMeter() == expected,
					"gas density rounded the component mass aggregate before division by its actual volume");
			require(hydro.temperatureK() == 300 && hydro.absolutePressurePa() > 0 && Double.isFinite(hydro.absolutePressurePa())
					&& hydro.soundSpeedMetersPerSecond() > 0 && Double.isFinite(hydro.soundSpeedMetersPerSecond()),
					"finite gas temperature, pressure or sound speed was lost to an intermediate mass overflow");
			require(network.inventory(1) == stored && network.externalSource() == source && network.escapedSink() == sink
					&& network.massRoundoff() == massRoundoff && network.residualRoundoff() == residualRoundoff
					&& network.revision() == gasRevision && domain.thermalState.matchesThermalRevision(thermalRevision)
					&& domain.thermalAudit() == audit && network.auditClosed(), "gas density queries changed authoritative state");
			require(Arrays.equals(marker, new double[] {domain.particles.positionX(0), domain.particles.positionY(0),
					domain.particles.positionZ(0), domain.particles.velocityX(0), domain.particles.velocityY(0),
					domain.particles.velocityZ(0), domain.particles.volume(0), domain.particles.mass(0)})
					&& Arrays.equals(before.thermal().copyInternalEnergy(), domain.captureAuthoritativeState().thermal().copyInternalEnergy())
					&& Arrays.equals(before.thermal().copyGasNodeEnergy(), domain.captureAuthoritativeState().thermal().copyGasNodeEnergy()),
					"gas state derivation modified liquid stock or raw internal energy");
		}
		var tiny = new GasInventory(List.of(new GasComponentAmount("test:a", 1, Double.MIN_VALUE, List.of(), Double.MIN_VALUE),
				new GasComponentAmount("test:b", 1, Double.MIN_VALUE, List.of(), Double.MIN_VALUE)));
		double expectedTiny = new java.math.BigDecimal(Double.MIN_VALUE).multiply(java.math.BigDecimal.valueOf(2))
				.divide(java.math.BigDecimal.valueOf(3), new java.math.MathContext(110)).doubleValue();
		require(Double.MIN_VALUE / 3 == 0 && expectedTiny == Double.MIN_VALUE && tiny.density(3) == expectedTiny,
				"component-wise density division lost a collectively representable positive mass");
		require(GasInventory.EMPTY.density(0) == 0 && GasInventory.EMPTY.density(1) == 0, "vacuum density is not zero");
	}

	private static ThermalMaterialDefinition topologyMaterial(String id, ThermalMaterialDefinition.CarrierKind kind,
			String carrier, double reference, double capacity) {
		return new ThermalMaterialDefinition(id, kind, carrier, 300, reference, 0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150, 1000, capacity)));
	}

	private static void phaseGeometryRemapIsPreparedBeforeCarrierCommit() {
		for (int scenario = 0; scenario < 6; scenario++) {
			int components = scenario == 0 ? 33 : 32;
			boolean energyOverflow = scenario == 2;
			boolean saturationOvershoot = scenario == 4;
			double energyReference = scenario == 2 ? 1e308 : scenario == 3 ? 1e307 : 0;
			FluidDomain domain = phaseTopologyDomain(components, energyReference,
					scenario == 4 ? 2 : scenario == 5 ? 1 : 0,
					scenario == 4 ? 1_000_000_000L : scenario == 5 ? 100_000_000L : 0L);
			var before = domain.captureAuthoritativeState();
			var beforeMarkers = domain.exportMaterialParticleState();
			int bridgeCell = domain.cellIndex(phaseTopologyScene(false).bridges()[0], 1, 1);
			var beforeThermal = domain.exportThermalParticleState();
			var beforeGas = domain.gasState.network.sealedInventory();
			var beforeNodes = List.of(domain.gasState.network.inventory(1), domain.gasState.network.inventory(2));
			double[] beforeGasVolume = {domain.gasState.network.availableVolume(1), domain.gasState.network.availableVolume(2)};
			var beforeSource = domain.gasState.network.externalSource();
			var beforeSink = domain.gasState.network.escapedSink();
			var beforePending = domain.pendingPhaseCellVolume.clone();
			var thermalRevision = domain.thermalState.thermalRevision();
			var transition = domain.thermalState.registry().transitions().getFirst();
			var phase = domain.thermalState.gasLiquidPhase(domain.gasState.network);
			if (scenario == 5) for (int node = 1; node <= 2; node++) require(phase.saturation(transition, 400,
					domain.gasState.network.inventory(node), domain.thermalState.requireGasNodeState(node,
							domain.gasState.network.inventory(node), domain.thermalState.gasNodeEnergy(node, domain.gasState.network),
							domain.gasState.network)).direction() == GasLiquidPhaseModel.Direction.BOIL,
					"future-volume boiling control must start with boiling affinity at both interfaces");
			var beforeEnergy = domain.exactInternalEnergy();
			var beforeMass = actualPhaseMass(domain);
			var beforeAudit = domain.thermalAudit();
			long markerRevision = domain.carrierMutationRevision;
			var stats = domain.stepGasPhaseChangeOnly(1);
			require(domain.exactInternalEnergy().equals(beforeEnergy) && actualPhaseMass(domain).equals(beforeMass)
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
					&& domain.gasState.network.auditClosed(), "phase topology successor lost physical mass or energy");
			if (components == 33 || energyOverflow || saturationOvershoot) {
				var afterThermal = domain.exportThermalParticleState();
				require(stats.operations() == 0 && domain.particleCount() == beforeMarkers.particleCount()
						&& domain.gasState.network.nodeCount() == 2 && domain.carrierMutationRevision == markerRevision
						&& domain.thermalState.thermalRevision().liquid() == thermalRevision.liquid()
						&& domain.thermalState.thermalRevision().granular() == thermalRevision.granular()
						&& domain.gasState.network.sealedInventory().equals(beforeGas)
						&& domain.gasState.network.inventory(1).equals(beforeNodes.getFirst())
						&& domain.gasState.network.inventory(2).equals(beforeNodes.get(1))
						&& domain.gasState.network.availableVolume(1) == beforeGasVolume[0]
						&& domain.gasState.network.availableVolume(2) == beforeGasVolume[1]
						&& domain.gasState.network.externalSource().equals(beforeSource)
						&& domain.gasState.network.escapedSink().equals(beforeSink)
						&& domain.thermalAudit().equals(beforeAudit) && domain.pendingPhaseCellVolume[bridgeCell].equals(SignedDyadic.ZERO)
						&& Arrays.equals(beforePending, domain.pendingPhaseCellVolume)
						&& Arrays.equals(beforeMarkers.copyPositionVolume(), domain.exportMaterialParticleState().copyPositionVolume())
						&& Arrays.equals(beforeMarkers.copyParticleMass(), domain.exportMaterialParticleState().copyParticleMass())
						&& Arrays.equals(beforeThermal.copyInternalEnergy(), afterThermal.copyInternalEnergy())
						&& Arrays.equals(beforeThermal.copyActiveParcelEnergy(), afterThermal.copyActiveParcelEnergy())
						&& Arrays.equals(beforeThermal.copyGasNodeEnergy(), afterThermal.copyGasNodeEnergy()),
						"unrepresentable or supersaturated future cavity was rejected after publishing its liquid removal: " + scenario);
			} else {
				require(stats.operations() == 1 && stats.boiledCells() == 1 && domain.particleCount() == 0
						&& domain.gasState.network.nodeCount() == 1
						&& domain.pendingPhaseCellVolume[bridgeCell].equals(SignedDyadic.of(-1, 0)),
						"a complete 32-component phase successor was not committed");
				require(phase.saturation(transition, domain.gasNodeTemperatureK(1),
						domain.gasState.network.inventory(1), domain.thermalState.requireGasNodeState(1,
								domain.gasState.network.inventory(1), domain.thermalState.gasNodeEnergy(1, domain.gasState.network),
								domain.gasState.network)).direction() != GasLiquidPhaseModel.Direction.CONDENSE,
						"committed boiling successor reversed saturation after its complete topology remap");
			}
			var after = domain.captureAuthoritativeState();
			FluidDomain restored = FluidDomain.restoreAuthoritativeState(after);
			var restoredThermal = restored.exportThermalParticleState();
			require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
					&& actualPhaseMass(restored).equals(beforeMass)
					&& restored.thermalAudit().equals(after.thermal().audit())
					&& restored.gasState.network.sealedInventory().equals(domain.gasState.network.sealedInventory())
					&& Arrays.equals(restoredThermal.copyInternalEnergy(), after.thermal().copyInternalEnergy())
					&& Arrays.equals(restoredThermal.copyActiveParcelEnergy(), after.thermal().copyActiveParcelEnergy())
					&& Arrays.equals(restoredThermal.copyGasNodeEnergy(), after.thermal().copyGasNodeEnergy())
					&& FluidDomain.restoreAuthoritativeState(before).particleCount() == beforeMarkers.particleCount(),
					"prospective phase topology did not retain a restorable state");
		}
		phaseDirectionsFollowCurrentCavity();
	}

	private static void phaseDirectionsFollowCurrentCavity() {
		FluidDomain domain = phaseTopologyDomain(1, 0, 0, 0L, true);
		int[] samples = phaseTopologyScene(true).cavitySamples();
		var initial = domain.captureAuthoritativeState();
		var energy = domain.exactInternalEnergy();
		var mass = actualPhaseMass(domain);
		var phase = domain.thermalState.gasLiquidPhase(domain.gasState.network);
		var transition = domain.thermalState.registry().transitions().getFirst();
		require(domain.gasState.network.nodeCount() == 3 && domain.totalVolumeExact() == 2
				&& domain.gasState.frame.commonCellNode(domain.cellIndex(samples[0], 1, 1)) == 1
				&& domain.gasState.frame.commonCellNode(domain.cellIndex(samples[1], 1, 1)) == 2
				&& domain.gasState.frame.commonCellNode(domain.cellIndex(samples[2], 1, 1)) == 3,
				"independent interfaces did not begin in three distinct sealed cavities");
		for (int node = 1; node <= 3; node++) require(phase.saturation(transition, 400,
				domain.gasState.network.inventory(node), domain.thermalState.requireGasNodeState(node,
						domain.gasState.network.inventory(node), domain.thermalState.gasNodeEnergy(node, domain.gasState.network),
						domain.gasState.network)).direction() == (node == 2
				? GasLiquidPhaseModel.Direction.CONDENSE : GasLiquidPhaseModel.Direction.BOIL),
				"independent cavity phase affinities differ from their prescribed initial states");
		FluidDomain separate = FluidDomain.restoreAuthoritativeState(initial);
		var joinedStats = domain.stepGasPhaseChangeOnly(2);
		var firstStats = separate.stepGasPhaseChangeOnly(1);
		require(firstStats.operations() == 1 && separate.gasState.network.nodeCount() == 2
				&& separate.gasState.frame.commonCellNode(separate.cellIndex(samples[2], 1, 1)) == 2,
				"the first bridge did not merge its cavities and renumber the independent cavity");
		separate = FluidDomain.restoreAuthoritativeState(separate.captureAuthoritativeState());
		var secondStats = separate.stepGasPhaseChangeOnly(1);
		require(joinedStats.operations() == 2 && joinedStats.boiledCells() == 2
				&& secondStats.operations() == 1 && domain.particleCount() == 0 && separate.particleCount() == 0,
				"a reused cavity ordinal suppressed a legitimate independent same-temperature phase change");
		var joinedThermal = domain.exportThermalParticleState();
		var separateThermal = separate.exportThermalParticleState();
		require(domain.exactInternalEnergy().equals(energy) && separate.exactInternalEnergy().equals(energy)
				&& actualPhaseMass(domain).equals(mass) && actualPhaseMass(separate).equals(mass)
				&& domain.thermalAudit().equals(separate.thermalAudit())
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& domain.gasState.network.auditClosed() && separate.gasState.network.auditClosed()
				&& domain.gasState.network.sealedInventory().equals(separate.gasState.network.sealedInventory())
				&& domain.gasState.network.externalSource().equals(separate.gasState.network.externalSource())
				&& domain.gasState.network.escapedSink().equals(separate.gasState.network.escapedSink())
				&& Arrays.equals(domain.pendingPhaseCellVolume, separate.pendingPhaseCellVolume)
				&& Arrays.equals(joinedThermal.copyInternalEnergy(), separateThermal.copyInternalEnergy())
				&& Arrays.equals(joinedThermal.copyActiveParcelEnergy(), separateThermal.copyActiveParcelEnergy())
				&& Arrays.equals(joinedThermal.copyGasNodeEnergy(), separateThermal.copyGasNodeEnergy()),
				"combined and separate phase budgets produced different conserved inventory or raw thermal state");
		for (int node = 1; node <= 2; node++) require(domain.gasState.network.inventory(node).equals(separate.gasState.network.inventory(node))
				&& domain.gasState.network.availableVolume(node) == separate.gasState.network.availableVolume(node),
				"combined and separate phase budgets assigned different final gas cavities");
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.particleCount() == 0 && restored.gasState.network.nodeCount() == 2
				&& restored.exactInternalEnergy().equals(energy) && actualPhaseMass(restored).equals(mass)
				&& restored.thermalAudit().equals(domain.thermalAudit())
				&& restored.gasState.network.sealedInventory().equals(domain.gasState.network.sealedInventory()),
				"independent completed phase transfers did not retain a restorable state");
	}

	private static FluidDomain phaseTopologyDomain(
			int componentCount, double energyReference, int extraVaporNode, long extraVaporNanos
	) {
		return phaseTopologyDomain(componentCount, energyReference, extraVaporNode, extraVaporNanos, false);
	}

	private static FluidDomain phaseTopologyDomain(
			int componentCount, double energyReference, int extraVaporNode, long extraVaporNanos,
			boolean independentInterface
	) {
		var gasDefinitions = new java.util.ArrayList<mmm.fluid.air.GasComponentDefinition>();
		var materials = new java.util.ArrayList<ThermalMaterialDefinition>();
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		var grains = BuiltinGranularDefinitions.createRegistry();
		var water = topologyMaterial("test:topology_water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.WATER.id(), 0, 2);
		materials.add(water);
		for (int k = 0; k < 33; k++) {
			gasDefinitions.add(new mmm.fluid.air.GasComponentDefinition("test:g" + k, 1, 1, List.of(), List.of()));
			materials.add(topologyMaterial("test:tg" + k, ThermalMaterialDefinition.CarrierKind.GAS,
					"test:g" + k, k == 0 ? 100 : energyReference != 0 && (k == 1 || k == 17) ? energyReference : 0, 1));
		}
		for (int k = 0; k < grains.materials().size(); k++) materials.add(topologyMaterial("test:ts" + k,
				ThermalMaterialDefinition.CarrierKind.GRANULAR, grains.material(k).id(), 0, 1));
		var gasRegistry = new GasRegistry(gasDefinitions,
				List.of(new GasRegistry.AmbientFraction("test:g0", ExactRational.ONE)));
		var transition = new ThermalPhaseTransition("test:topology_phase", water.id(), "test:tg0", 300,
				SolidVolume.SVQ_PER_CELL, SolidVolume.SVQ_PER_BV, ExactRational.of(125),
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 0, 1e8);
		var registry = new ThermalRegistry(materials, List.of(), List.of(transition), "", grains.fingerprint());
		var scene = phaseTopologyScene(independentInterface);
		int width = scene.width();
		int[] bridges = scene.bridges();
		FluidDomain domain = new FluidDomain(width, 3, 3,
				new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1e-5F, .45F, .002F, 0), 64);
		domain.sealDomainBoundary();
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < width; x++)
			if (y != 1 || z != 1 || x == 0 || x == width - 1 || x == scene.separatingWall())
				domain.setSolid(x, y, z, true);
		for (int bridge : bridges) domain.setVolume(bridge, 1, 1, 1);
		domain.bindGranular(grains, liquids, BuiltinFluidMaterials.createRegistry());
		domain.bindGasRegistry(gasRegistry); domain.bootstrapGasState("");
		domain.commitOperatorVentSealedGas(domain.planOperatorVentSealedGas());
		for (int node = 1; node <= (independentInterface ? 3 : 2); node++) {
			var amounts = new java.util.ArrayList<GasComponentAmount>();
			int start = independentInterface || node == 1 ? 0 : 17;
			int end = independentInterface ? 1 : node == 1 ? 17 : componentCount;
			for (int k = start; k < end; k++) {
				double gasMass = energyReference != 0 && (k == 1 || k == 17) ? 1 : 1.0 / 1024;
				long nanos = independentInterface && node == 2 ? 300_000_000L : 1_000_000L;
				amounts.add(new GasComponentAmount("test:g" + k, nanos, gasMass, List.of(), gasMass));
			}
			domain.gasState.network.commitPhaseSource(node, new GasInventory(amounts));
		}
		if (extraVaporNanos > 0) domain.gasState.network.commitPhaseSource(extraVaporNode,
				new GasInventory(List.of(new GasComponentAmount("test:g0", extraVaporNanos,
						1.0 / 1024, List.of(), 1.0 / 1024))));
		domain.bindThermal(registry, liquids, energyReference != 0 ? 300 : 400); domain.stepGasPhaseChangeOnly(0);
		return FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
	}

	private record PhaseTopologyScene(int width, int[] bridges, int separatingWall, int[] cavitySamples) { }

	private static PhaseTopologyScene phaseTopologyScene(boolean independentInterface) {
		// Five cells per side admit the full vapor batch; the independent scene keeps a one-cell supersaturated middle cavity.
		return independentInterface ? new PhaseTopologyScene(26, new int[]{11,24}, 13, new int[]{1,12,14})
				: new PhaseTopologyScene(13, new int[]{6}, -1, new int[]{1,7});
	}

	private static void gasRemapPlansRequireTheirPreparingOwner() {
		var gases = BuiltinGasDefinitions.createRegistry();
		var liquid = new FluidMaterial("test:remap_host", 1000, "test:remap_host", 0, Math.PI / 2);
		var liquids = new FluidMaterialRegistry(List.of(liquid));
		var registry = new ThermalRegistry(List.of(topologyMaterial("test:remap_liquid",
				ThermalMaterialDefinition.CarrierKind.LIQUID, liquid.id(), 0, 1),
				topologyMaterial("test:remap_air", ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR, 0, 1)));
		var network = new AirNetwork(gases); network.addNode(1, 1);
		var first = FluidThermalBinding.create(registry, liquids, 300, 1, 2, 2, 2, .5F, emptyMarkers(), network);
		var foreign = FluidThermalBinding.create(registry, liquids, 300, 1, 2, 2, 2, .5F, emptyMarkers(), network);
		var plan = first.planGasRemap(network, List.of(network.inventory(1)), new double[]{1},
				new double[]{first.gasNodeEnergy(1, network)}, new double[]{1}, SignedDyadic.ZERO, SignedDyadic.ZERO);
		var audit = foreign.auditLedger(); double energy = foreign.gasNodeEnergy(1, network);
		expectFailure(() -> foreign.commitGasRemap(plan), "foreign gas thermal remap was accepted");
		require(foreign.auditLedger() == audit && foreign.gasNodeEnergy(1, network) == energy,
				"foreign gas remap rejection changed its target owner");
		first.commitGasRemap(plan);
		expectFailure(() -> first.commitGasRemap(plan), "stale gas thermal remap was accepted twice");
	}

	private static void gasTopologyRemapsRetainCommonTemperature() {
		gasTopologyExchangePreservesExactRegionalEnergy();
		var gasDefinitions = List.of(new mmm.fluid.air.GasComponentDefinition("test:a", 8, 8, List.of(), List.of()),
				new mmm.fluid.air.GasComponentDefinition("test:b", 8, 8, List.of(), List.of()),
				new mmm.fluid.air.GasComponentDefinition("test:c", 8, 8, List.of(), List.of()));
		var gas = new GasRegistry(gasDefinitions, List.of(new GasRegistry.AmbientFraction("test:a", ExactRational.ONE)));
		var liquids = new FluidMaterialRegistry(List.of(new FluidMaterial("mmm:water", 1000, "mmm:water", 0, Math.PI / 2)));
		var registry = new ThermalRegistry(List.of(topologyMaterial("test:w", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"mmm:water", 0, 1000), topologyMaterial("test:ta", ThermalMaterialDefinition.CarrierKind.GAS, "test:a", 1e18, 1),
				topologyMaterial("test:tb", ThermalMaterialDefinition.CarrierKind.GAS, "test:b", 200, 1),
				topologyMaterial("test:tc", ThermalMaterialDefinition.CarrierKind.GAS, "test:c", -1e18, 1)));
		for (var order : List.of(List.of("test:a", "test:b", "test:c"), List.of("test:a", "test:c", "test:b"))) {
			FluidDomain domain = new FluidDomain(5, 2, 2,
					new FluidSolverConfig(.5F, 1000, 0, .001F, 100, 1e-5F, .45F, .002F, .8F), 128);
			domain.sealDomainBoundary();
			for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 5; x++)
				if (!(y == 0 && z == 0 && x % 2 == 0)) domain.setSolid(x, y, z, true);
			int[] membership = new int[20 * 8]; Arrays.fill(membership, -1);
			var nodes = new java.util.ArrayList<mmm.fluid.air.GasStateSnapshot.Node>();
			var source = mmm.fluid.air.GasAuditLedger.EMPTY;
			for (int i = 0; i < 3; i++) {
				int cell = domain.cellIndex(i * 2, 0, 0); Arrays.fill(membership, cell * 8, cell * 8 + 8, i + 1);
				var inventory = new GasInventory(List.of(new GasComponentAmount(order.get(i), 125_000_000, 1, List.of(), 1)));
				nodes.add(new mmm.fluid.air.GasStateSnapshot.Node(cell * 8, inventory)); source = source.add(inventory);
			}
			domain.restoreGasState(new mmm.fluid.air.GasStateSnapshot(5, 2, 2, gas.fingerprint(), "", nodes,
					membership, source, mmm.fluid.air.GasAuditLedger.EMPTY, SignedDyadic.ZERO, SignedDyadic.ZERO), gas);
			domain.bindThermal(registry, liquids, 300);
			domain.setSolid(1, 0, 0, false); domain.setSolid(3, 0, 0, false); domain.synchronizeGasTopologyForPersistence();
			require(domain.gasNodeTemperatureK(1) == 300 && domain.exactInternalEnergy().equals(SignedDyadic.of(200))
					&& domain.thermalAudit().mechanicalWork().equals(SignedDyadic.ZERO)
					&& domain.thermalAudit().roundoffResidual().equals(SignedDyadic.ZERO)
					&& FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState()).gasNodeTemperatureK(1) == 300,
					"gas topology aggregation depends on the old node order or changes a common temperature");
		}
	}

	private static void gasTopologyExchangePreservesExactRegionalEnergy() {
		SignedDyadic total = SignedDyadic.of(1e308).add(SignedDyadic.of(1e308));
		for (boolean source : new boolean[]{false, true}) {
			FluidDomain domain = gasRegionalEnergyDomain(source);
			var before = domain.captureAuthoritativeState();
			require(domain.exactInternalEnergy().equals(source ? SignedDyadic.ZERO : total)
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"regional gas energy control has an invalid initial exact ledger");
			domain.setSolid(0, 1, 1, source); domain.setSolid(4, 1, 1, source);
			domain.synchronizeGasTopologyForPersistence();
			require(domain.gasBoundary().nodeCount() == (source ? 2 : 0)
					&& domain.exactInternalEnergy().equals(source ? total : SignedDyadic.ZERO)
					&& (source ? domain.thermalAudit().externalSource() : domain.thermalAudit().externalSink()).equals(total)
					&& domain.thermalAudit().mechanicalWork().equals(SignedDyadic.ZERO)
					&& domain.thermalAudit().roundoffResidual().equals(SignedDyadic.ZERO)
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
					&& domain.gasBoundary().auditClosed()
					&& FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState()).exactInternalEnergy()
							.equals(domain.exactInternalEnergy()),
					"topology gas source or sink forced its exact regional energy through a double total");
			if (!source) {
				FluidDomain explicit = FluidDomain.restoreAuthoritativeState(before);
				explicit.commitOperatorVentSealedGas(explicit.planOperatorVentSealedGas());
				require(explicit.exactInternalEnergy().equals(SignedDyadic.ZERO)
						&& explicit.thermalAudit().externalSink().equals(total)
						&& explicit.exactInternalEnergy().equals(explicit.thermalAudit().expectedInventory()),
						"explicit vent and topology escape disagree on the same exact exported energy");
			}
		}
	}

	private static FluidDomain gasRegionalEnergyDomain(boolean initiallyAmbient) {
		var gas = new GasRegistry(List.of(
				new mmm.fluid.air.GasComponentDefinition("test:a", 8, 8, List.of(), List.of()),
				new mmm.fluid.air.GasComponentDefinition("test:b", 8, 8, List.of(), List.of())),
				List.of(new GasRegistry.AmbientFraction("test:a", ExactRational.ONE)));
		var liquids = new FluidMaterialRegistry(List.of(new FluidMaterial("mmm:water", 1000, "mmm:water", 0, Math.PI / 2)));
		var thermal = new ThermalRegistry(List.of(topologyMaterial("test:w", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"mmm:water", 0, 1), topologyMaterial("test:ta", ThermalMaterialDefinition.CarrierKind.GAS, "test:a", 1e308, 1),
				topologyMaterial("test:tb", ThermalMaterialDefinition.CarrierKind.GAS, "test:b", 1e308, 1)));
		FluidDomain domain = new FluidDomain(5, 3, 3,
				new FluidSolverConfig(.5F, 1000, 0, .001F, 100, 1e-5F, .45F, .002F, .8F), 128);
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 5; x++)
			if (!(y == 1 && z == 1 && (x == 1 || x == 3 || initiallyAmbient && (x == 0 || x == 4))))
				domain.setSolid(x, y, z, true);
		if (initiallyAmbient) { domain.bindGasRegistry(gas); domain.bootstrapGasState(""); }
		else {
			int[] membership = new int[45 * 8]; Arrays.fill(membership, -1);
			var nodes = new java.util.ArrayList<mmm.fluid.air.GasStateSnapshot.Node>();
			var source = mmm.fluid.air.GasAuditLedger.EMPTY;
			for (int i = 0; i < 2; i++) {
				int cell = domain.cellIndex(1 + 2 * i, 1, 1); Arrays.fill(membership, cell * 8, cell * 8 + 8, i + 1);
				var inventory = new GasInventory(List.of(new GasComponentAmount(i == 0 ? "test:a" : "test:b",
						125_000_000, 1, List.of(), 1)));
				nodes.add(new mmm.fluid.air.GasStateSnapshot.Node(cell * 8, inventory)); source = source.add(inventory);
			}
			domain.restoreGasState(new mmm.fluid.air.GasStateSnapshot(5, 3, 3, gas.fingerprint(), "", nodes,
					membership, source, mmm.fluid.air.GasAuditLedger.EMPTY, SignedDyadic.ZERO, SignedDyadic.ZERO), gas);
		}
		domain.bindThermal(thermal, liquids, 300);
		return FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
	}

	static void gasRuntimeTransfersEnergyAndWork() {
		mmm.thermal.GasTransferValidation.validate();
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
				new double[] {1.0}, SignedDyadic.ZERO, SignedDyadic.ZERO);
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
		flowingNetwork.stepEdges(0.0001, thermodynamics(flowing, flowingNetwork),
				(source, target, moved, sourceAfter, targetAfter) -> flowing.planGasTransfer(
						source, target, moved, sourceAfter, targetAfter, flowingNetwork));
		require(flowing.gasTemperature(high, flowingNetwork) < GasRegistry.STANDARD_TEMPERATURE_K
				&& flowing.gasTemperature(low, flowingNetwork) > GasRegistry.STANDARD_TEMPERATURE_K
				&& flowing.exactEnergy(0).equals(flowing.auditLedger().expectedInventory()),
				"internal gas orifice did not carry source-state enthalpy with exact audit closure");

		flowingNetwork.addOrMergeEdge(high, AirNetwork.AMBIENT_NODE, 0.1, 0.65);
		flowingNetwork.stepEdges(0.0001, thermodynamics(flowing, flowingNetwork),
				(source, target, moved, sourceAfter, targetAfter) -> flowing.planGasTransfer(
						source, target, moved, sourceAfter, targetAfter, flowingNetwork));
		require(flowing.auditLedger().externalSink().compareTo(SignedDyadic.ZERO) > 0
				&& flowing.exactEnergy(0).equals(flowing.auditLedger().expectedInventory()),
				"ambient gas outflow did not book carried enthalpy as an external sink");

		AirNetwork equalizingNetwork = new AirNetwork(gasRegistry);
		int donor = equalizingNetwork.addNode(1.0, 1.1), receiver = equalizingNetwork.addNode(1.0, 0.9);
		equalizingNetwork.addOrMergeEdge(donor, receiver, 0.1, 0.65);
		ThermalDomainState equalizing = FluidThermalBinding.create(registry, liquids,
				GasRegistry.STANDARD_TEMPERATURE_K, 1, 2, 2, 2, 0.5F, empty, equalizingNetwork);
		double gamma = 1.0 + GasRegistry.STANDARD_PRESSURE_PA / GasRegistry.STANDARD_TEMPERATURE_K
				/ (gasRegistry.createAmbient(1_000_000_000L).totalMass() * 718.0);
		for (int step = 0; step < 4; step++) {
			equalizingNetwork.stepEdges(0.05, thermodynamics(equalizing, equalizingNetwork),
					(source, target, moved, sourceAfter, targetAfter) -> equalizing.planGasTransfer(
							source, target, moved, sourceAfter, targetAfter, equalizingNetwork));
			require(equalizing.gasGaugePressure(donor, equalizingNetwork)
					>= equalizing.gasGaugePressure(receiver, equalizingNetwork)
					&& Math.abs(equalizingNetwork.standardVolume(donor) - (1.1 - 0.1 / gamma)) < 2.0E-9
					&& equalizing.exactEnergy(0).equals(equalizing.auditLedger().expectedInventory()),
					"finite-energy orifice overshot the analytic pressure equilibrium");
		}
		require(!equalizingNetwork.hasPressureDrivenFlow(thermodynamics(equalizing, equalizingNetwork)),
				"unrepresentable enthalpy flow kept an equilibrated gas network active");

		AirNetwork hotNetwork = new AirNetwork(gasRegistry);
		int hotSource = hotNetwork.addNode(1.0, 1.1), hotTarget = hotNetwork.addNode(1.0, 0.9);
		hotNetwork.addOrMergeEdge(hotSource, hotTarget, 0.001, 0.65);
		ThermalDomainState hot = FluidThermalBinding.create(registry, liquids,
				600.0, 1, 2, 2, 2, 0.5F, empty, hotNetwork);
		double expectedStandardTransfer = 0.65 * 0.001 * Math.sqrt(2.0
				* (hot.gasGaugePressure(hotSource, hotNetwork) - hot.gasGaugePressure(hotTarget, hotNetwork))
				/ hot.gasDensity(hotSource, hotNetwork)) * 1.0E-4 * 1.1;
		hotNetwork.stepEdges(1.0E-4, thermodynamics(hot, hotNetwork),
				(source, target, moved, sourceAfter, targetAfter) -> hot.planGasTransfer(
						source, target, moved, sourceAfter, targetAfter, hotNetwork));
		require(Math.abs((1.1 - hotNetwork.standardVolume(hotSource)) - expectedStandardTransfer) <= 0.5E-9
				&& hot.exactEnergy(0).equals(hot.auditLedger().expectedInventory()),
				"non-isothermal orifice rate omitted the source temperature in standard-volume conversion");

		AirNetwork drawingNetwork = new AirNetwork(gasRegistry);
		int vacuumTarget = drawingNetwork.addNode(1.0, 0.0);
		drawingNetwork.addOrMergeEdge(AirNetwork.AMBIENT_NODE, vacuumTarget, 0.1, 0.65);
		ThermalDomainState drawing = FluidThermalBinding.create(registry, liquids,
				600.0, 1, 2, 2, 2, 0.5F, empty, drawingNetwork);
		drawingNetwork.stepEdges(1.0, thermodynamics(drawing, drawingNetwork),
				(source, target, moved, sourceAfter, targetAfter) -> drawing.planGasTransfer(
						source, target, moved, sourceAfter, targetAfter, drawingNetwork));
		require(Math.abs(drawingNetwork.standardVolume(vacuumTarget)
				- GasRegistry.STANDARD_TEMPERATURE_K / (gamma * 600.0)) < 2.0E-9
				&& drawing.gasGaugePressure(vacuumTarget, drawingNetwork) <= 0.0
				&& drawing.exactEnergy(0).equals(drawing.auditLedger().expectedInventory()),
				"ambient filling ignored source enthalpy and finite receiver heat capacity");
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
		for (float volume : new float[] {0.25F, 0.75F, 0.5F}) {
			double oldV = domain.gasBoundary().availableVolume(1), oldT = domain.gasNodeTemperatureK(1);
			GasInventory stock = domain.gasBoundary().inventory(1);
			double specificR = GasRegistry.STANDARD_PRESSURE_PA * AirNetwork.cubicMeters(stock.standardVolumeNanos())
					/ (stock.totalMass() * GasRegistry.STANDARD_TEMPERATURE_K);
			domain.setVolume(0, 0, 0, volume); domain.synchronizeGasTopologyForPersistence();
			double expectedT = oldT * Math.pow(oldV / domain.gasBoundary().availableVolume(1), specificR / 718.0);
			require(Math.abs(domain.gasNodeTemperatureK(1) - expectedT) < 2e-9
					&& domain.gasBoundary().inventory(1).equals(stock)
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"actual cavity compression/expansion differs from the independent ideal-gas adiabat");
		}

		GasInventory beforeGas = domain.gasBoundary().inventory(1);
		var stale = domain.exportGasState("");
		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) domain.setVolume(2, y, z, 1.0F);
		FlipMaterialParticleState carriers = domain.exportMaterialParticleState();
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

		// A fixed-mask swap removes every old gas support; inventory has no certified swept route.
		// Reject that unsupported edit without publishing a false successor or thermal work.
		FluidDomain unsupportedSwap = new FluidDomain(2, 2, 2, config, 128);
		unsupportedSwap.sealDomainBoundary();
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++)
			unsupportedSwap.setSolid(x, y, z, true);
		unsupportedSwap.setSolidMicroMask(0, 0, 0, 0xAA);
		unsupportedSwap.bindGasRegistry(gas); unsupportedSwap.bootstrapGasState("");
		unsupportedSwap.bindThermal(thermal, liquids, GasRegistry.STANDARD_TEMPERATURE_K);
		var gasBeforeSwap = unsupportedSwap.exportGasState("");
		var thermalBeforeSwap = unsupportedSwap.thermalAudit();
		unsupportedSwap.setSolidMicroMask(0, 0, 0, 0x55);
		expectFailure(unsupportedSwap::synchronizeGasTopologyForPersistence,
				"gas stock without positive spatial overlap acquired an invented successor");
		var gasAfterSwap = unsupportedSwap.exportGasState("");
		require(Arrays.equals(gasBeforeSwap.pieceNodeOrdinal(), gasAfterSwap.pieceNodeOrdinal())
				&& gasBeforeSwap.nodes().equals(gasAfterSwap.nodes())
				&& gasBeforeSwap.externalSource().equals(gasAfterSwap.externalSource())
				&& gasBeforeSwap.escapedSink().equals(gasAfterSwap.escapedSink())
				&& thermalBeforeSwap == unsupportedSwap.thermalAudit(),
				"rejected unsupported gas remap partially changed inventory or energy");
		gasExpansionRetainsTheOldControlVolume();

	}

	private static void gasExpansionRetainsTheOldControlVolume() {
		var config = new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1e-5F, .45F, .002F, 0);
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		var gas = BuiltinGasDefinitions.createRegistry();
		var thermal = new ThermalRegistry(List.of(
			new ThermalMaterialDefinition("test:expansion_water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.WATER.id(), 300, 0, .598, List.of(new ThermalMaterialDefinition.CaloricSegment(150,1500,4184))),
			new ThermalMaterialDefinition("test:expansion_air", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.AIR, GasRegistry.STANDARD_TEMPERATURE_K, 0, .026,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150,1500,718)))));
		for (float initial : new float[] {1, .5F}) {
			var domain = new FluidDomain(2, 2, 2, config, 128);
			byte[] masks = new byte[8]; Arrays.fill(masks, (byte)255); masks[0] = masks[1] = 0;
			domain.restoreParticleState(new FlipParticleState(2, 2, 2, .5F, 0, 0, new float[0], new float[0], masks));
			domain.sealDomainBoundary(); domain.setVolume(1, 0, 0, initial);
			domain.setMarkerMaterial(0, liquids, 0); domain.bindGasRegistry(gas); domain.bootstrapGasState("");
			domain.bindThermal(thermal, liquids, 600);
			double oldV = domain.gasBoundary().availableVolume(1); GasInventory stock = domain.gasBoundary().inventory(1);
			double specificR = GasRegistry.STANDARD_PRESSURE_PA * AirNetwork.cubicMeters(stock.standardVolumeNanos())
					/ (stock.totalMass() * GasRegistry.STANDARD_TEMPERATURE_K);
			domain.removeVolumeNear(1, 0, 0, .5F, 0); domain.synchronizeGasTopologyForPersistence();
			double expectedT = 600 * Math.pow(oldV / domain.gasBoundary().availableVolume(1), specificR / 718);
			require(Math.abs(domain.gasNodeTemperatureK(1) - expectedT) < 2e-9
					&& domain.gasBoundary().inventory(1).equals(stock) && domain.gasBoundary().auditClosed()
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"gas expansion suppressed work when a previously full liquid cell exposed new dry pieces");
		}
		var merged = new FluidDomain(3, 2, 2, config, 64);
		merged.sealDomainBoundary();
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++)
			if (y != 0 || z != 0) merged.setSolid(x, y, z, true);
		merged.setVolume(1, 0, 0, 1);
		merged.bindGasRegistry(gas); merged.bootstrapGasState(""); merged.setGasNodeStandardVolume(2, 0);
		merged.bindThermal(thermal, liquids, 300);
		require(merged.gasBoundary().nodeCount() == 2 && merged.gasBoundary().inventory(2).isEmpty(),
				"liquid barrier did not separate actual gas and vacuum control volumes");
		GasInventory stock = merged.gasBoundary().inventory(1);
		double inheritedVolume = merged.gasBoundary().availableVolume(1) + merged.gasBoundary().availableVolume(2);
		double oldEnergy = merged.thermalState.gasNodeEnergy(1, merged.gasState.network);
		var source = merged.gasBoundary().externalSource(); var sink = merged.gasBoundary().escapedSink();
		var work = merged.thermalAudit().mechanicalWork();
		double specificR = GasRegistry.STANDARD_PRESSURE_PA * AirNetwork.cubicMeters(stock.standardVolumeNanos())
				/ (stock.totalMass() * GasRegistry.STANDARD_TEMPERATURE_K);
		require(merged.removeVolumeNear(1, 0, 0, 1, 0) == 1, "full liquid barrier was not removed");
		merged.synchronizeGasTopologyForPersistence();
		double expected = 300 * Math.pow(inheritedVolume / merged.gasBoundary().availableVolume(1), specificR / 718);
		require(merged.particleCount() == 0 && merged.gasBoundary().nodeCount() == 1
				&& merged.gasBoundary().inventory(1).equals(stock) && Math.abs(merged.gasNodeTemperatureK(1) - expected) < 2e-9
				&& merged.gasBoundary().externalSource().equals(source) && merged.gasBoundary().escapedSink().equals(sink)
				&& merged.thermalAudit().mechanicalWork().subtract(work).equals(SignedDyadic.of(
						merged.thermalState.gasNodeEnergy(1, merged.gasState.network)).subtract(SignedDyadic.of(oldEnergy)))
				&& merged.exactInternalEnergy().equals(merged.thermalAudit().expectedInventory()),
				"gas/vacuum merge lost inherited volume, stock or its actual expansion work");
		for (int cell = 0; cell < 3; cell++) for (int micro = 0; micro < 8; micro++)
			require(merged.gasBoundary().nodeOfPiece(8 * cell + micro) == 1,
					"the merged cavity did not retain complete actual piece ownership");
		for (int extraction = 0; extraction < 5; extraction++) {
			for (int liquidCells = 1; liquidCells <= 2; liquidCells++) {
				var delayed = isolatedVacuumBeforeWallOpening(config, liquids, gas, thermal,
						extraction, liquidCells, false, false);
				var synchronizedState = isolatedVacuumBeforeWallOpening(config, liquids, gas, thermal,
						extraction, liquidCells, true, false);
				require(delayed.equals(synchronizedState), "an observation changed gas work or energy across vacuum birth");
			}
			isolatedVacuumBeforeWallOpening(config, liquids, gas, thermal, extraction, 1, false, true);
		}
		coldGasExpansionRejectsExtractionBeforeWrite();
		gasInsertionPublishesCapacityWork(config, liquids, gas, thermal);
	}

	private static void gasInsertionPublishesCapacityWork(FluidSolverConfig config, FluidMaterialRegistry liquids,
	                                                     GasRegistry gas, ThermalRegistry thermal) {
		for (int insertion = 0; insertion < 4; insertion++) {
			VacuumOpeningState reference = null;
			for (boolean intermediateSync : new boolean[] {false, true}) {
				var domain = insertionDomain(config, liquids, gas, thermal, insertion == 3);
				var stock = domain.gasBoundary().inventory(1);
				double oldEnergy = domain.thermalState.gasNodeEnergy(1, domain.gasState.network);
				require(insertGasFixtureLiquid(domain, insertion, .5F), "public liquid insertion was refused");
				double specificR = GasRegistry.STANDARD_PRESSURE_PA * AirNetwork.cubicMeters(stock.standardVolumeNanos())
						/ (stock.totalMass() * GasRegistry.STANDARD_TEMPERATURE_K);
				double expected = 300 * Math.pow(2, specificR / 718);
				require(domain.gasBoundary().availableVolume(1) == .0625
						&& Math.abs(domain.gasNodeTemperatureK(1) - expected) < 2e-9,
						"insertion did not publish gas compression with its liquid successor");
				if (intermediateSync) domain.synchronizeGasTopologyForPersistence();
				domain.setSolid(1, 0, 0, false); domain.synchronizeGasTopologyForPersistence();
				double energy = domain.thermalState.gasNodeEnergy(1, domain.gasState.network);
				var result = new VacuumOpeningState(domain.gasBoundary().inventory(1), energy,
						domain.gasNodeTemperatureK(1), domain.thermalAudit().mechanicalWork());
				require(result.stock().equals(stock) && Math.abs(result.temperature() - expected) < 2e-9
						&& domain.gasBoundary().availableVolume(1) == .3125
						&& result.work().equals(SignedDyadic.of(energy).subtract(SignedDyadic.of(oldEnergy)))
						&& domain.gasBoundary().auditClosed()
						&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
						"fixed wall opening changed already committed liquid compression work");
				if (reference == null) reference = result;
				else require(reference.equals(result), "read synchronization changed insertion history");
			}
			var refused = insertionDomain(config, liquids, gas, thermal, insertion == 3);
			var state = refused.captureAuthoritativeState();
			// Closing the only stock-bearing cell leaves its inventory without a successor.
			var frame = refused.gasState.frame; long gasRevision = refused.gasState.network.revision();
			var thermalRevision = refused.thermalState.thermalRevision();
			long carrierRevision = refused.carrierMutationRevision();
			var audit = refused.thermalAudit();
			long chemicalRevision = refused.chemicalParcels == null ? 0 : refused.chemicalParcels.mutationRevision();
			require(!insertGasFixtureLiquid(refused, insertion, 1), "finite gas cavity disappearance accepted insertion");
			require(refused.particleCount() == 0 && refused.gasState.frame == frame
					&& refused.gasState.network.revision() == gasRevision
					&& refused.carrierMutationRevision() == carrierRevision
					&& refused.thermalState.thermalRevision().equals(thermalRevision) && refused.thermalAudit() == audit
					&& (refused.chemicalParcels == null ? 0 : refused.chemicalParcels.mutationRevision()) == chemicalRevision
					&& refused.exportGasState("").nodes().equals(state.gas().nodes()),
					"rejected insertion changed a carrier, gas, thermal or chemical owner");
		}
	}

	private static FluidDomain insertionDomain(FluidSolverConfig config, FluidMaterialRegistry liquids,
	                                         GasRegistry gas, ThermalRegistry thermal, boolean chemical) {
		var domain = new FluidDomain(3, 2, 2, config, 128); domain.sealDomainBoundary();
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++)
			if (y != 0 || z != 0 || x == 1) domain.setSolid(x, y, z, true);
		if (chemical) {
			domain.bindMaterialRegistry(liquids); domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
			thermal = new ThermalRegistry(thermal.materials(), List.of(new ThermalSpeciesOffset(
					"test:insertion_water", BuiltinChemicalDefinitions.H2O, thermal.materials().stream()
					.filter(material -> material.carrierKind() == ThermalMaterialDefinition.CarrierKind.LIQUID)
					.findFirst().orElseThrow().id(), ExactRational.ZERO)), List.of(),
					BuiltinChemicalDefinitions.REGISTRY.fingerprint(), "");
		}
		domain.bindGasRegistry(gas); domain.bootstrapGasState(""); domain.setGasNodeStandardVolume(2, 0);
		domain.bindThermal(thermal, liquids, 300); return domain;
	}

	private static boolean insertGasFixtureLiquid(FluidDomain domain, int insertion, float volume) {
		if (insertion == 0) return domain.tryInsertExactVolume(0, 0, 0, volume, 0, 0, true).transferred();
		if (insertion == 1) return domain.tryInsertExactMixture(0, 0, 0,
				List.of(new FluidDomain.MaterialPortion(0, volume, volume * 1000)), 0, true, 1.001).transferred();
		if (insertion == 2) return domain.addVolumeNearBudgeted(0, 0, 0, volume, 0).addedVolume() == volume;
		var record = domain.chemicalParcels.defaultRecord(BuiltinFluidMaterials.WATER.id(), volume, .5F, volume * 1000, false);
		var ledger = mmm.chemistry.ChemicalTransferLedger.builder(domain.chemicalParcels.registry())
				.add(BuiltinFluidMaterials.WATER.id(), false, volume, volume * 125, record).build();
		return domain.tryInsertExactChemical(0, 0, 0, ledger, 0, true, 1.001).transferred();
	}

	private static void coldGasExpansionRejectsExtractionBeforeWrite() {
		var config = new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1e-5F, .45F, .002F, 0);
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		var thermal = new ThermalRegistry(List.of(
				new ThermalMaterialDefinition("test:cold_water", ThermalMaterialDefinition.CarrierKind.LIQUID,
						BuiltinFluidMaterials.WATER.id(), Double.MIN_NORMAL, 0, 0,
						List.of(new ThermalMaterialDefinition.CaloricSegment(Double.MIN_NORMAL, 1, 1))),
				new ThermalMaterialDefinition("test:cold_air", ThermalMaterialDefinition.CarrierKind.GAS,
						BuiltinGasDefinitions.AIR, Double.MIN_NORMAL, 0, 0,
						List.of(new ThermalMaterialDefinition.CaloricSegment(Double.MIN_NORMAL, 1, 1)))));
		var domain = new FluidDomain(2, 2, 2, config, 64);
		domain.sealDomainBoundary();
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++)
			if (x != 0 || y != 0 || z != 0) domain.setSolid(x, y, z, true);
		domain.setVolume(0, 0, 0, .5F); domain.bindGasRegistry(BuiltinGasDefinitions.createRegistry());
		domain.bootstrapGasState(""); domain.bindThermal(thermal, liquids, Double.MIN_NORMAL);
		var before = domain.exportMaterialParticleState(); var gas = domain.exportGasState("");
		var frame = domain.gasState.frame; var network = domain.gasState.network;
		long revision = network.revision(), carrierRevision = domain.carrierMutationRevision();
		var thermalRevision = domain.thermalState.thermalRevision(); var audit = domain.thermalAudit();
		require(!domain.tryExtractExactVolume(0, 0, 0, .5F, 0).transferred(),
				"unrepresentable gas expansion did not refuse the complete container extraction");
		var after = domain.exportMaterialParticleState(); var gasAfter = domain.exportGasState("");
		require(Arrays.equals(before.copyPositionVolume(), after.copyPositionVolume())
				&& Arrays.equals(before.copyParticleMass(), after.copyParticleMass())
				&& Arrays.equals(before.copyParticleVelocities(), after.copyParticleVelocities())
				&& Arrays.equals(gasAfter.pieceNodeOrdinal(), gas.pieceNodeOrdinal()) && gasAfter.nodes().equals(gas.nodes())
				&& gasAfter.externalSource().equals(gas.externalSource()) && gasAfter.escapedSink().equals(gas.escapedSink())
				&& gasAfter.massRoundoff().equals(gas.massRoundoff()) && gasAfter.residualRoundoff().equals(gas.residualRoundoff())
				&& domain.gasState.frame == frame
				&& domain.gasState.network == network && network.revision() == revision
				&& domain.carrierMutationRevision() == carrierRevision && domain.thermalAudit() == audit
				&& domain.thermalState.thermalRevision().equals(thermalRevision)
				&& domain.exactInternalEnergy().equals(audit.expectedInventory()),
				"rejected extraction changed liquid, gas or thermal owners");
	}

	private record VacuumOpeningState(GasInventory stock, double energy, double temperature, SignedDyadic work) { }

	private static VacuumOpeningState isolatedVacuumBeforeWallOpening(FluidSolverConfig config,
			FluidMaterialRegistry liquids, GasRegistry gas, ThermalRegistry thermal, int extraction,
			int liquidCells, boolean intermediateSync, boolean openFirst) {
		int nx = 2 + liquidCells;
		var domain = new FluidDomain(nx, 2, 2, config, 128);
		domain.sealDomainBoundary();
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < nx; x++)
			if (y != 0 || z != 0 || x == 1) domain.setSolid(x, y, z, true);
		for (int x = 2; x < nx; x++) domain.setVolume(x, 0, 0, 1);
		if (extraction == 2) {
			domain.bindMaterialRegistry(liquids); domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
			thermal = new ThermalRegistry(thermal.materials(), List.of(new ThermalSpeciesOffset(
					"test:vacuum_water", BuiltinChemicalDefinitions.H2O, thermal.materials().stream()
					.filter(material -> material.carrierKind() == ThermalMaterialDefinition.CarrierKind.LIQUID)
					.findFirst().orElseThrow().id(), ExactRational.ZERO)), List.of(),
					BuiltinChemicalDefinitions.REGISTRY.fingerprint(), "");
		}
		domain.bindGasRegistry(gas); domain.bootstrapGasState(""); domain.bindThermal(thermal, liquids, 300);
		var stock = domain.gasBoundary().inventory(1);
		var source = domain.gasBoundary().externalSource(); var sink = domain.gasBoundary().escapedSink();
		double oldEnergy = domain.thermalState.gasNodeEnergy(1, domain.gasState.network);
		if (openFirst) domain.setSolid(1, 0, 0, false);
		if (extraction == 0) require(domain.removeVolumeNear(2, 0, 0, liquidCells, liquidCells - 1) == liquidCells,
				"operator extraction did not remove both planned cells");
		else if (extraction == 1) require(domain.tryExtractExactMixture(2, 0, 0, liquidCells, liquidCells - 1).transferred(),
				"material container extraction failed");
		else if (extraction == 2) require(domain.tryExtractExactChemical(2, 0, 0, liquidCells, liquidCells - 1).transferred(),
				"chemical container extraction failed");
		else if (extraction == 3) domain.removeAllWaterExact();
		else domain.commitRemoveAllCondensedExact(domain.planRemoveAllCondensedExact());
		if (!openFirst) require(domain.gasBoundary().nodeCount() == 2 && domain.gasBoundary().inventory(2).isEmpty(),
				"liquid extraction did not publish the isolated vacuum successor");
		if (intermediateSync) domain.synchronizeGasTopologyForPersistence();
		if (!openFirst) domain.setSolid(1, 0, 0, false);
		domain.synchronizeGasTopologyForPersistence();
		double temperature = domain.gasNodeTemperatureK(1);
		double energy = domain.thermalState.gasNodeEnergy(1, domain.gasState.network);
		var work = domain.thermalAudit().mechanicalWork();
		double specificR = GasRegistry.STANDARD_PRESSURE_PA * AirNetwork.cubicMeters(stock.standardVolumeNanos())
				/ (stock.totalMass() * GasRegistry.STANDARD_TEMPERATURE_K);
		double expected = openFirst ? 300 * Math.pow(2.0 / nx, specificR / 718) : 300;
		require(Math.abs(temperature - expected) < 2e-9 && domain.gasBoundary().inventory(1).equals(stock)
				&& domain.gasBoundary().nodeCount() == 1 && domain.gasBoundary().availableVolume(1) == nx * .125
				&& domain.gasBoundary().externalSource().equals(source) && domain.gasBoundary().escapedSink().equals(sink)
				&& work.equals(SignedDyadic.of(energy).subtract(SignedDyadic.of(oldEnergy)))
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"fixed wall opening confused isolated vacuum with pressure-driven liquid retreat");
		if (!openFirst) require(energy == oldEnergy && work.signum() == 0,
				"free expansion into an isolated newborn vacuum changed gas internal energy");
		return new VacuumOpeningState(stock, energy, temperature, work);
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

		ThermalRegistry deepColdThermal = new ThermalRegistry(List.of(
				new ThermalMaterialDefinition("test:thermal/deep_cold_water",
						ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
						ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, 0.0, 0.598, List.of(new ThermalMaterialDefinition.CaloricSegment(
						ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, 1_500.0, 4_184.0))),
				new ThermalMaterialDefinition("test:thermal/deep_cold_air",
						ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
						ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, 0.0, 0.026, List.of(new ThermalMaterialDefinition.CaloricSegment(
						ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, 1_500.0, 718.0)))));
		FluidDomain deepCold = new FluidDomain(4, 4, 4, config, 128);
		deepCold.sealDomainBoundary();
		deepCold.setVolume(1, 1, 1, 1.0F);
		for (int marker = 0; marker < deepCold.particleCount(); marker++) {
			deepCold.setMarkerMaterial(marker, liquids, 0);
		}
		deepCold.bindGasRegistry(gas);
		deepCold.bootstrapGasState("");
		// U=0 at the cold reference preserves the intended actual temperature in binary64.
		deepCold.bindThermal(deepColdThermal, liquids, ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
		double coldStorage = config.density() * deepCold.pressureBoundaryView.gasNodeVolumeCompliance(1)
				/ (config.timeStep() * (double) config.timeStep()
				* config.cellSize() * config.cellSize() * config.cellSize());
		require(Double.isInfinite(coldStorage), "deep-cold fixture did not exceed pressure storage precision");
		FluidStepCandidate deepColdCandidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0L, deepCold.captureAuthoritativeState(), List.of(), false, 0));
		deepCold = deepColdCandidate.claimPreparedCpuDomain();
		double deepColdTemperature = deepCold.thermalState.gasTemperature(1, deepCold.gasState.network);
		require(!deepCold.projection.gasPressureUnknown[1]
				&& deepColdTemperature > 0.0 && deepColdTemperature < 1.0
				&& deepCold.exactInternalEnergy().equals(deepCold.thermalAudit().expectedInventory()),
				"deep-cold nonempty gas still paused instead of using its actual near-vacuum pressure boundary");
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
		var gasBefore = domain.gasBoundary().sealedInventory();
		SignedDyadic energyBefore = domain.exactGasInternalEnergy();
		int nodesBefore = domain.gasBoundary().nodeCount();

		FluidDomain.OperatorGasVentPlan stale = domain.planOperatorVentSealedGas();
		domain.gasState.network.updateAvailableVolume(1,
				Math.nextDown(domain.gasBoundary().availableVolume(1)));
		boolean staleRejected = false;
		try {
			domain.commitOperatorVentSealedGas(stale);
		} catch (IllegalStateException expected) {
			staleRejected = true;
		}
		require(staleRejected && domain.gasBoundary().sealedInventory().equals(gasBefore)
				&& domain.exactGasInternalEnergy().equals(energyBefore),
				"stale gas vent partially committed its thermal owner");
		FluidDomain.OperatorGasVentPlan thermallyStale = domain.planOperatorVentSealedGas();
		domain.setThermalTemperatureInCell(1, 1, 1, 301.0);
		var gasAfterHeating = domain.gasBoundary().sealedInventory();
		SignedDyadic gasEnergyAfterHeating = domain.exactGasInternalEnergy();
		var auditAfterHeating = domain.thermalAudit();
		staleRejected = false;
		try {
			domain.commitOperatorVentSealedGas(thermallyStale);
		} catch (IllegalStateException expected) {
			staleRejected = true;
		}
		require(staleRejected && domain.gasBoundary().sealedInventory().equals(gasAfterHeating)
				&& domain.exactGasInternalEnergy().equals(gasEnergyAfterHeating)
				&& domain.thermalAudit().equals(auditAfterHeating),
				"thermally stale gas vent overwrote a newer thermal audit generation");
		FluidDomain.OperatorGasVent vented = domain.commitOperatorVentSealedGas(
				domain.planOperatorVentSealedGas());
		require(nodesBefore > 0 && vented.sealedNodes() == nodesBefore
				&& vented.removed().equals(gasBefore) && vented.internalEnergy().equals(energyBefore)
				&& domain.gasBoundary().nodeCount() == nodesBefore && domain.gasBoundary().sealedInventory().isEmpty()
				&& domain.gasBoundary().escapedSink().equals(domain.gasBoundary().externalSource())
				&& domain.exactGasInternalEnergy().equals(SignedDyadic.ZERO)
				&& domain.gasBoundary().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"operator vent did not jointly settle sealed gas component and energy truth");
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.gasBoundary().nodeCount() == nodesBefore
				&& restored.gasBoundary().sealedInventory().isEmpty()
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
		GasRegistry humidAmbient = new GasRegistry(gas.components(), List.of(
				new GasRegistry.AmbientFraction(BuiltinGasDefinitions.AIR, ExactRational.parse("3/4")),
				new GasRegistry.AmbientFraction(BuiltinGasDefinitions.WATER_VAPOR, ExactRational.parse("1/4"))));
		var ambientSaturation = new GasLiquidPhaseModel(thermal, humidAmbient).ambientSaturation(transition, 373.15);
		require(ambientSaturation.componentPartialPressurePa() == GasRegistry.STANDARD_PRESSURE_PA / 4
				&& ambientSaturation.direction() == GasLiquidPhaseModel.Direction.BOIL
				&& model.ambientSaturation(transition, 373.15).componentPartialPressurePa() == 0,
				"an opened phase interface did not use the ambient reservoir's vapor fraction");
		double referenceGasVolume = (double) gasSvq / SolidVolume.SVQ_PER_BV;
		double latentEnthalpy = 2_256_000.0 + GasRegistry.STANDARD_PRESSURE_PA
				* (referenceGasVolume - (double) transition.lowerVolumeSvqPerBatch() / SolidVolume.SVQ_PER_BV) / 0.001;
		double gasConstant = GasRegistry.STANDARD_PRESSURE_PA * referenceGasVolume / (0.001 * 373.15);
		double expectedHotSaturation = GasRegistry.STANDARD_PRESSURE_PA
				* Math.exp(-latentEnthalpy / gasConstant * (1.0 / 383.15 - 1.0 / 373.15));
		require(close(model.saturation(transition, 383.15, batch, GasRegistry.STANDARD_PRESSURE_PA)
				.saturationPressurePa(), expectedHotSaturation),
				"saturation latent enthalpy used standard volume in reference-state pressure work");
		require(model.saturation(transition, ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, batch,
				GasRegistry.STANDARD_PRESSURE_PA).direction() == GasLiquidPhaseModel.Direction.CONDENSE,
				"near-zero liquid temperature did not retain the condensation path");
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
		return thermal.gasThermodynamics(network);
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
		phaseGasCapacityIsAtomicAndRetainsSmallerBatch();
		massOnlyCondensationUsesExistingReceiver();
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
		FluidDomain isolated = new FluidDomain(4, 4, 4, config, 512);
		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
			isolated.setSolid(x, y, z, true);
		isolated.setSolidMicroMask(1, 1, 1, 0xAA);
		isolated.setSolid(2, 1, 1, false);
		isolated.setVolume(1, 1, 1, 0.5F);
		isolated.setMarkerMaterial(0, liquids, 0);
		isolated.bindThermal(thermal, liquids, 500.0);
		SignedDyadic isolatedEnergy = isolated.exactInternalEnergy();
		require(isolated.stepGasPhaseChangeOnly(128).operations() == 0
				&& isolated.gasBoundary().sealedInventory().component(BuiltinGasDefinitions.WATER_VAPOR) == null
				&& isolated.exactInternalEnergy().equals(isolatedEnergy),
				"gas/liquid phase exchange crossed a closed cut-cell face");
		FluidDomain partialCavity = new FluidDomain(4, 4, 4, config, 512);
		partialCavity.sealDomainBoundary();
		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
			partialCavity.setSolid(x, y, z, true);
		partialCavity.setSolidMicroMask(1, 1, 1, 0xAA);
		partialCavity.bindThermal(thermal, liquids, 500.0);
		partialCavity.setVolume(1, 1, 1, 0.25F);
		var partialPhase = partialCavity.stepGasPhaseChangeOnly(128);
		require(partialCavity.gasBoundary().nodeCount() == 1
				&& partialPhase.boiledCells() == 1
				&& partialCavity.gasBoundary().sealedInventory().component(BuiltinGasDefinitions.WATER_VAPOR) != null
				&& partialCavity.exactInternalEnergy().equals(partialCavity.thermalAudit().expectedInventory()),
				"partial-cell gas owner lost its same-cell liquid phase interface: " + partialPhase
						+ ", nodes=" + partialCavity.gasBoundary().nodeCount()
						+ ", gas=" + partialCavity.gasBoundary().sealedInventory());
		FluidDomain domain = new FluidDomain(4, 4, 4, config, 512);
		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
			if (x == 0 || x == 3 || y == 0 || y == 3 || z == 0 || z == 3) domain.setSolid(x, y, z, true);
		}
		domain.setVolume(1, 1, 1, 1.0F);
		domain.setMarkerMaterial(0, liquids, 1);
		domain.bindThermal(thermal, liquids, 500.0);
		domain.thermalState.preparePhaseScan(domain.thermalMarkerContext());
		var emptyCandidate = domain.thermalState.preparePhaseCandidateCell(0, domain.thermalMarkerContext());
		var emptyCandidateBeforeEnergy = domain.exactInternalEnergy();
		var emptyCandidateBeforeAudit = domain.thermalAudit();
		var emptyCandidateBeforeRevision = domain.thermalState.thermalRevision();
		try {
			domain.thermalState.requirePhaseCandidateState(emptyCandidate, List.of(), 1.0E20, Math.nextDown(1.0E20));
			throw new AssertionError("empty candidate discarded exact energy after rounded gas transfer");
		} catch (ThermalDomainState.EmptyPhaseCandidateEnergyException expected) { }
		require(Double.isNaN(domain.thermalState.requirePhaseCandidateState(emptyCandidate, List.of(), 1.0, 1.0)),
				"exactly empty candidate acquired a fictitious temperature");
		require(domain.exactInternalEnergy().equals(emptyCandidateBeforeEnergy)
				&& domain.thermalAudit() == emptyCandidateBeforeAudit
				&& domain.thermalState.thermalRevision().equals(emptyCandidateBeforeRevision),
				"empty-inventory energy validation changed a thermal owner or its audit");
		require(domain.gasBoundary().nodeCount() == 1, "boiling fixture did not form one sealed receiver");
		ThermalDisplaySnapshot display = domain.copyThermalDisplaySnapshot();
		int liquidCell = (1 * 4 + 1) * 4 + 1;
		require(display.cellTemperatureQ().length == 64
				&& Short.toUnsignedInt(display.cellTemperatureQ()[liquidCell]) == 500 * 16
				&& (display.debugFlags()[liquidCell] & ThermalDisplaySnapshot.FLAG_CONDENSED) != 0
				&& Byte.toUnsignedInt(display.coherentFraction()[liquidCell]) == 0
				&& display.gasNodes().size() == 1 && display.gasNodes().getFirst().nodeId() == 1,
				"thermal display did not derive quantized condensed/gas state in one rebuild");
		domain.setThermalTemperatureInCell(1, 1, 1, 5_000.0);
		require(Short.toUnsignedInt(domain.copyThermalDisplaySnapshot().cellTemperatureQ()[liquidCell])
				== ThermalDisplaySnapshot.MAX_TEMPERATURE_Q,
				"bounded display encoding rejected an otherwise valid high-temperature state");
		domain.setThermalTemperatureInCell(1, 1, 1, 500.0);
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
				&& domain.thermalAudit().mechanicalWork().equals(auditBefore.mechanicalWork())
				&& domain.hasPendingPhaseVolumeWork()
				&& close(domain.exactVolumeNear(1, 1, 1, 0, 1), oilVolumeBefore)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"sealed boiling did not jointly conserve liquid/gas mass, volume and energy truth: stats=" + stats
					+ " liquidMass=" + domain.totalMass() + "/" + liquidMassBefore
					+ " expectedLiquidMass=" + (liquidMassBefore - boiledSolverMass * stats.operations())
					+ " oilVolume=" + domain.exactVolumeNear(1, 1, 1, 0, 1) + "/" + oilVolumeBefore
					+ " vapor=" + vaporAmount + " expectedVaporMass=" + (batchMass.toDouble() * stats.operations())
					+ " nanos=" + domain.gasBoundary().standardVolumeNanos(1)
					+ "/" + gasNanosBefore + " deltaU="
					+ domain.exactInternalEnergy().subtract(energyBefore)
					+ " gasAudit=" + domain.gasBoundary().auditClosed()
					+ " workCompare=" + domain.thermalAudit().mechanicalWork().compareTo(auditBefore.mechanicalWork())
					+ " pending=" + domain.hasPendingPhaseVolumeWork()
					+ " thermalAudit=" + domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()));
		FluidDomain referenceBoilingProjection = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		java.util.Arrays.fill(referenceBoilingProjection.pendingPhaseCellVolume, SignedDyadic.ZERO);
		referenceBoilingProjection.step(false, 0);
		domain.step(false, 0);
		require(!domain.hasPendingPhaseVolumeWork()
				&& domain.exactInternalEnergy().equals(referenceBoilingProjection.exactInternalEnergy())
				&& domain.thermalAudit().mechanicalWork().equals(referenceBoilingProjection.thermalAudit().mechanicalWork())
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
				&& domain.thermalAudit().mechanicalWork().equals(
						auditBeforeCondensation.mechanicalWork())
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
		FluidDomain referenceCondensationProjection = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		java.util.Arrays.fill(referenceCondensationProjection.pendingPhaseCellVolume, SignedDyadic.ZERO);
		referenceCondensationProjection.step(false, 0);
		domain.step(false, 0);
		require(!domain.hasPendingPhaseVolumeWork()
				&& domain.exactInternalEnergy().equals(referenceCondensationProjection.exactInternalEnergy())
				&& domain.thermalAudit().mechanicalWork().equals(referenceCondensationProjection.thermalAudit().mechanicalWork())
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"condensation phase volume was not consumed by the next real pressure projection");
		FluidDomain thin = new FluidDomain(3, 3, 3, config, 256);
		thin.sealDomainBoundary();
		thin.setVolume(1, 1, 1, 0.00016F);
		thin.setMarkerMaterial(0, liquids, 0);
		thin.bindGasRegistry(BuiltinGasDefinitions.createRegistry()); thin.bootstrapGasState("");
		thin.bindThermal(thermal, liquids, GasRegistry.STANDARD_TEMPERATURE_K);
		thin.commitOperatorVentSealedGas(thin.planOperatorVentSealedGas());
		double thinVolume = thin.totalVolumeExact(), thinMass = thin.totalMass();
		var thinEnergy = thin.exactInternalEnergy();
		var thinPhase = thin.stepGasPhaseChangeOnly(1);
		require(thinPhase.operations() == 0 && thin.totalVolumeExact() == thinVolume && thin.totalMass() == thinMass
				&& thin.exactInternalEnergy().equals(thinEnergy) && thin.gasBoundary().sealedInventory().isEmpty()
				&& thin.thermalState.temperature(0, thin.thermalMarkerContext()) == GasRegistry.STANDARD_TEMPERATURE_K,
				"sub-quantum evaporation crossed saturation and spent latent heat on a thin liquid carrier");
		FluidDomain finiteVapor = new FluidDomain(3, 3, 3, config, 256);
		finiteVapor.sealDomainBoundary();
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++)
			if (y != 1 || z != 1 || x == 0) finiteVapor.setSolid(x, y, z, true);
		restorePhaseTestCarriers(finiteVapor, liquids, new float[]{.008F}, new int[1]);
		var gasRegistry = BuiltinGasDefinitions.createRegistry();
		finiteVapor.bindGasRegistry(gasRegistry); finiteVapor.bootstrapGasState("");
		finiteVapor.commitOperatorVentSealedGas(finiteVapor.planOperatorVentSealedGas());
		var phaseModel = new GasLiquidPhaseModel(thermal, gasRegistry);
		finiteVapor.gasState.network.commitPhaseSource(1, phaseModel.gasBatch(boiling, 2));
		finiteVapor.bindThermal(thermal, finiteVapor.materialRegistry, GasRegistry.STANDARD_TEMPERATURE_K);
		float[] condensationVelocity = {2.0F, -1.0F, 0.5F};
		finiteVapor.particles.setVelocityX(0, condensationVelocity[0]);
		finiteVapor.particles.setVelocityY(0, condensationVelocity[1]);
		finiteVapor.particles.setVelocityZ(0, condensationVelocity[2]);
		double condensationMassBefore = finiteVapor.unitScale.kilograms(finiteVapor.particles.mass(0));
		var pairEnergy = finiteVapor.exactInternalEnergy();
		var limitedCondensation = finiteVapor.stepGasPhaseChangeOnly(2);
		require(limitedCondensation.operations() == 1 && limitedCondensation.condensedCells() == 1
				&& finiteVapor.gasBoundary().standardVolumeNanos(1) == phaseModel.gasBatch(boiling, 1).standardVolumeNanos()
				&& finiteVapor.exactInternalEnergy().equals(pairEnergy) && finiteVapor.gasBoundary().auditClosed(),
				"integer condensation crossed saturation instead of retaining the final vapor quantum");
		require(finiteVapor.particleCount() == 1, "condensation replaced its existing liquid receiver");
		double condensationMassAfter = finiteVapor.unitScale.kilograms(finiteVapor.particles.mass(0));
		float[] receiverVelocity = {finiteVapor.particles.velocityX(0), finiteVapor.particles.velocityY(0),
				finiteVapor.particles.velocityZ(0)};
		for (int axis = 0; axis < 3; axis++) {
			double before = condensationMassBefore * condensationVelocity[axis];
			double after = condensationMassAfter * receiverVelocity[axis];
			double rounding = 0.5 * condensationMassAfter * Math.ulp(receiverVelocity[axis]) + 4.0 * Math.ulp(before);
			require(Math.abs(after - before) <= rounding,
					"zero-momentum condensation created receiver momentum on axis " + axis + ": " + before + " -> " + after);
		}
	}

	/**
	 * Ambient-lineage trapping combined with sealed-interface boiling: an open pocket that the
	 * shell closes after bootstrap must trap its real ambient air inventory through the topology
	 * remap, and boiling from the adjacent liquid must add water vapor to that same node without
	 * disturbing the trapped air columns. This is the deterministic owner of the air/vapor mixture
	 * observable; the production floor dam-break replay can no longer exercise it because the
	 * binary64 pressure overhaul changed the collapsing front so it stops folding ambient air.
	 */
	static void ambientTrappedAirBoilsIntoMixedNode() {
		FluidMaterial waterCarrier = FluidMaterial.withSurfaceTension(
				"test:mix_water", 1_000.0, "test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(waterCarrier));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("test:thermal/mix_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, waterCarrier.id(), 373.15, 0.0, 0.6,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 800.0, 4_180.0)));
		ThermalMaterialDefinition air = new ThermalMaterialDefinition("test:thermal/mix_air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR, 373.15, 0.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 800.0, 718.0)));
		ThermalMaterialDefinition vapor = new ThermalMaterialDefinition("test:thermal/mix_vapor",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.WATER_VAPOR,
				373.15, 2_256_000.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 800.0, 1_410.0)));
		long liquidSvq = SolidVolume.SVQ_PER_CELL / 20_000L;
		long vaporSvq = liquidSvq * 1_700L;
		ExactRational batchMass = ExactRational.parse("52375/8388608");
		ThermalPhaseTransition boiling = new ThermalPhaseTransition("test:phase/mix_boiling",
				water.id(), vapor.id(), 373.15, liquidSvq, vaporSvq, batchMass,
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 10_000.0, 3_000_000.0);
		ThermalRegistry thermal = new ThermalRegistry(
				List.of(water, air, vapor), List.of(), List.of(boiling), "", "");
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(4, 4, 4, config, 512);
		domain.setVolume(2, 1, 1, 1.0F);
		domain.setMarkerMaterial(0, liquids, 0);
		domain.bindGasRegistry(BuiltinGasDefinitions.createRegistry());
		domain.bootstrapGasState("");
		domain.bindThermal(thermal, liquids, 500.0);
		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
			if ((x == 1 && y == 1 && z == 1) || (x == 2 && y == 1 && z == 1)) continue;
			domain.setSolid(x, y, z, true);
		}
		domain.synchronizeGasTopologyForPersistence();
		long pocketNanos = AirNetwork.nanos(0.125);
		require(domain.gasBoundary().nodeCount() == 1
				&& domain.gasBoundary().inventory(1).component(BuiltinGasDefinitions.AIR) != null
				&& domain.gasBoundary().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR) == null
				&& domain.gasBoundary().standardVolumeNanos(1) == pocketNanos
				&& domain.gasBoundary().drawnFromAmbientNanos() == pocketNanos
				&& domain.gasBoundary().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"enclosed ambient pocket did not trap its air inventory through the remap: nodes="
						+ domain.gasBoundary().nodeCount() + " inventory=" + domain.gasBoundary().inventory(1)
						+ " drawn=" + domain.gasBoundary().drawnFromAmbientNanos() + "/" + pocketNanos);
		domain.step(true, 128);
		FluidDomain.GasThermalPhaseStats stats = domain.lastGasThermalPhaseStats();
		GasComponentAmount trappedAir = domain.gasBoundary().inventory(1)
				.component(BuiltinGasDefinitions.AIR);
		GasComponentAmount boiledVapor = domain.gasBoundary().inventory(1)
				.component(BuiltinGasDefinitions.WATER_VAPOR);
		require(stats.operations() > 0
				&& trappedAir != null && trappedAir.standardVolumeNanos() == pocketNanos
				&& boiledVapor != null && boiledVapor.mass() > 0.0
				&& domain.gasBoundary().escapedSink().standardVolumeNanos() == 0L
				&& domain.gasBoundary().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"boiling into trapped ambient air did not form a conserved air/vapor mixture: stats="
						+ stats + " air=" + trappedAir + " vapor=" + boiledVapor
						+ " escaped=" + domain.gasBoundary().escapedSink());
	}

	static void compositionBearingBoilingMovesOnlyGasAdmittedSpecies() {
		compositionBearingBoilingSearchesCompleteBatches();
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
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
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
		// This artificial transition has a coarse vapor-volume quantum. Strong cooling
		// leaves sufficient saturation drive for one complete condensation batch.
		setAllCondensedCellTemperatures(domain, 200.0);
		condensationCapacityRetry(domain);
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

	static void phaseGasCapacityIsAtomicAndRetainsSmallerBatch() {
		phaseGeometryRemapIsPreparedBeforeCarrierCommit();
		phaseMassTransfersUseActualStoredKilograms();
		gasPhaseTransfersCheckActualGasKilograms();
		phaseRawCarrierAssignmentIsAtomic();
		phaseGasEnergyRepresentationRetainsSmallerBatch();
		FluidDomain history = phaseGasCapacityDomain(true);
		var before = history.captureAuthoritativeState();
		var thermalBefore = history.exportThermalParticleState();
		var chemicalBefore = history.exportChemicalParticleState();
		var materialBefore = history.exportMaterialParticleState();
		var gasBefore = history.gasBoundary().inventory(1);
		var sourceBefore = history.gasState.network.externalSource();
		var sinkBefore = history.gasState.network.escapedSink();
		var energyBefore = history.exactInternalEnergy();
		var auditBefore = history.thermalAudit();
		var deferred = history.stepGasPhaseChangeOnly(1);
		var thermalAfter = history.exportThermalParticleState();
		var chemicalAfter = history.exportChemicalParticleState();
		var materialAfter = history.exportMaterialParticleState();
		require(deferred.operations() == 0 && deferred.boiledCells() == 0 && deferred.condensedCells() == 0
				&& Arrays.equals(materialAfter.copyPositionVolume(), materialBefore.copyPositionVolume())
				&& Arrays.equals(materialAfter.copyParticleVelocities(), materialBefore.copyParticleVelocities())
				&& Arrays.equals(materialAfter.copyParticleMass(), materialBefore.copyParticleMass())
				&& Arrays.equals(chemicalAfter.copySpeciesTotals(), chemicalBefore.copySpeciesTotals())
				&& Double.doubleToRawLongBits(chemicalAfter.residualLedger())
						== Double.doubleToRawLongBits(chemicalBefore.residualLedger())
				&& Arrays.equals(thermalAfter.copyInternalEnergy(), thermalBefore.copyInternalEnergy())
				&& Arrays.equals(thermalAfter.copyGasNodeEnergy(), thermalBefore.copyGasNodeEnergy())
				&& history.gasBoundary().inventory(1).equals(gasBefore)
				&& history.gasState.network.externalSource().equals(sourceBefore)
				&& history.gasState.network.escapedSink().equals(sinkBefore)
				&& history.exactInternalEnergy().equals(energyBefore) && history.thermalAudit().equals(auditBefore)
				&& history.gasBoundary().auditClosed(),
				"gas cumulative-source capacity changed liquid or gas state before deferring its phase batch");
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(history.captureAuthoritativeState());
		FluidDomain restoredBefore = FluidDomain.restoreAuthoritativeState(before);
		require(restored.exactInternalEnergy().equals(restoredBefore.exactInternalEnergy())
				&& restored.thermalAudit().equals(restoredBefore.thermalAudit())
				&& restored.gasState.network.externalSource().equals(sourceBefore)
				&& restored.gasState.network.escapedSink().equals(sinkBefore)
				&& restored.gasBoundary().inventory(1).equals(gasBefore),
				"deferred gas phase source no longer captured and restored the original closed inventories");
		FluidDomain limited = phaseGasCapacityDomain(false);
		var limitedBefore = limited.exactInternalEnergy();
		var limitedAudit = limited.thermalAudit();
		var accepted = limited.stepGasPhaseChangeOnly(2);
		require(accepted.operations() == 1 && accepted.boiledCells() == 1 && accepted.condensedCells() == 0
				&& limited.gasBoundary().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR)
						.species().getFirst().amountQuanta() == Long.MAX_VALUE
				&& limited.exportChemicalParticleState().copySpeciesTotals()[
						BuiltinChemicalDefinitions.REGISTRY.speciesHandle(BuiltinChemicalDefinitions.H2O)] == 1
				&& limited.exactInternalEnergy().subtract(limitedBefore).equals(
						limited.thermalAudit().roundoffResidual().subtract(limitedAudit.roundoffResidual()))
				&& limited.exactInternalEnergy().equals(limited.thermalAudit().expectedInventory())
				&& limited.gasBoundary().auditClosed(),
				"an overflowing largest gas phase batch prevented its smaller complete batch");
	}

	private static void gasPhaseTransfersCheckActualGasKilograms() {
		for (long batchMass : new long[]{1, 200, 256}) for (boolean boiling : new boolean[]{true, false}) {
			FluidDomain domain = phaseHugeGasMassDomain(batchMass, boiling);
			SignedDyadic beforeMass = actualPhaseMass(domain), beforeEnergy = domain.exactInternalEnergy();
			var beforeGas = domain.gasState.network.inventory(1).components();
			var beforeSource = domain.gasState.network.externalSource();
			var beforeSink = domain.gasState.network.escapedSink();
			var beforeRoundoff = domain.gasState.network.massRoundoff();
			var beforeMaterials = domain.exportMaterialParticleState();
			var beforeThermal = domain.exportThermalParticleState();
			long beforeRevision = domain.thermalState.thermalRevision().liquid();
			var stats = domain.stepGasPhaseChangeOnly(1);
			require(stats.operations() == (batchMass == 256 ? 1 : 0),
					"gas phase must require the stored component's full transferred-scale mass change");
			require(actualPhaseMass(domain).equals(beforeMass)
					&& domain.gasState.network.massRoundoff().equals(beforeRoundoff)
					&& domain.exactInternalEnergy().equals(beforeEnergy)
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"gas phase changed actual kg or U by absorbing the transfer into gas roundoff");
			if (batchMass != 256) {
				require(domain.thermalState.thermalRevision().liquid() == beforeRevision
						&& domain.gasState.network.inventory(1).components().equals(beforeGas)
						&& domain.gasState.network.externalSource() == beforeSource
						&& domain.gasState.network.escapedSink() == beforeSink
						&& java.util.Arrays.equals(domain.exportMaterialParticleState().copyParticleMass(), beforeMaterials.copyParticleMass())
						&& java.util.Arrays.equals(domain.exportMaterialParticleState().copyPositionVolume(), beforeMaterials.copyPositionVolume())
						&& java.util.Arrays.equals(domain.exportThermalParticleState().copyInternalEnergy(), beforeThermal.copyInternalEnergy())
						&& domain.pendingPhaseCellVolume[13].equals(SignedDyadic.ZERO),
						"unrepresentable gas phase candidate wrote a carrier, ledger or pending volume");
			}
			require(FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState()).particleCount() == 1,
					"gas phase mass representation must remain capturable and restorable");
		}
	}

	private static FluidDomain phaseHugeGasMassDomain(long batchMass, boolean boiling) {
		var gases = new GasRegistry(List.of(new mmm.fluid.air.GasComponentDefinition("test:mass_vapor", 1, 1, List.of(), List.of())),
				List.of(new GasRegistry.AmbientFraction("test:mass_vapor", ExactRational.ONE)));
		var liquids = new FluidMaterialRegistry(List.of(FluidMaterial.withSurfaceTension(
				"test:mass_water", 100_000, "test:miscible", 0, 90)));
		var water = new ThermalMaterialDefinition("test:mass_water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:mass_water", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(200, 400, 1)));
		var vapor = new ThermalMaterialDefinition("test:mass_vapor", ThermalMaterialDefinition.CarrierKind.GAS,
				"test:mass_vapor", 299, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(200, 400, 1)));
		var transition = new ThermalPhaseTransition("test:mass_phase", water.id(), vapor.id(), 300,
				SolidVolume.SVQ_PER_CELL / 64, SolidVolume.SVQ_PER_BV / 8, ExactRational.of(batchMass),
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 0, 1e8);
		var registry = new ThermalRegistry(List.of(water, vapor), List.of(), List.of(transition), "", "");
		var domain = new FluidDomain(3, 3, 3, new FluidSolverConfig(.5F, 100_000, 0, .05F, 100, 1e-5F, .45F, .002F, 0), 64);
		domain.sealDomainBoundary();
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++)
			if (y != 1 || z != 1 || x == 0) domain.setSolid(x, y, z, true);
		restorePhaseTestCarriers(domain, liquids, new float[]{.125F}, new int[1]);
		domain.bindGasRegistry(gases); domain.bootstrapGasState("");
		domain.commitOperatorVentSealedGas(domain.planOperatorVentSealedGas());
		long nanos = boiling ? 1 : new GasLiquidPhaseModel(registry, gases).gasBatch(transition, 4).standardVolumeNanos();
		domain.gasState.network.commitPhaseSource(1, new GasInventory(List.of(new GasComponentAmount(
				"test:mass_vapor", nanos, 1e18, List.of(), 1e18))));
		domain.bindThermal(registry, domain.materialRegistry, 300); domain.stepGasPhaseChangeOnly(0);
		return FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
	}

	private static void phaseMassTransfersUseActualStoredKilograms() {
		for (long batchMass : new long[]{1, 200}) {
			FluidDomain blocked = phaseMassResolutionDomain(batchMass, true, false);
			var beforeMaterial = blocked.exportMaterialParticleState();
			var beforeThermal = blocked.exportThermalParticleState();
			var beforeRevision = blocked.thermalState.thermalRevision();
			var beforeGas = blocked.gasBoundary().inventory(1);
			var beforeSource = blocked.gasState.network.externalSource();
			var beforeSink = blocked.gasState.network.escapedSink();
			var beforeAudit = blocked.thermalAudit();
			var beforeMass = actualPhaseMass(blocked);
			var result = blocked.stepGasPhaseChangeOnly(1);
			var afterMaterial = blocked.exportMaterialParticleState();
			var afterThermal = blocked.exportThermalParticleState();
			require(result.operations() == 0 && blocked.particleCount() == 1
					&& Arrays.equals(beforeMaterial.copyPositionVolume(), afterMaterial.copyPositionVolume())
					&& Arrays.equals(beforeMaterial.copyParticleMass(), afterMaterial.copyParticleMass())
					&& Arrays.equals(beforeMaterial.copyParticleVelocities(), afterMaterial.copyParticleVelocities())
					&& Arrays.equals(beforeThermal.copyInternalEnergy(), afterThermal.copyInternalEnergy())
					&& Arrays.equals(beforeThermal.copyGasNodeEnergy(), afterThermal.copyGasNodeEnergy())
					&& blocked.thermalState.thermalRevision().liquid() == beforeRevision.liquid()
					&& blocked.thermalState.thermalRevision().granular() == beforeRevision.granular()
					&& blocked.gasBoundary().inventory(1).equals(beforeGas)
					&& blocked.gasState.network.externalSource() == beforeSource
					&& blocked.gasState.network.escapedSink() == beforeSink && blocked.thermalAudit().equals(beforeAudit)
					&& actualPhaseMass(blocked).equals(beforeMass),
					"boiling admitted an unresolved or incorrectly rounded actual kilogram transfer: " + batchMass);
			blocked.captureAuthoritativeState();
			FluidDomain appended = phaseMassResolutionDomain(batchMass, false, false);
			var initialMass = actualPhaseMass(appended);
			double largeMass = appended.particles.mass(0);
			var initialRoundoff = appended.gasState.network.massRoundoff();
			require(appended.stepGasPhaseChangeOnly(1).condensedCells() == 1 && appended.particleCount() == 2
					&& appended.particles.mass(0) == largeMass && appended.particles.volume(0) == .125F
					&& appended.unitScale.kilograms(appended.particles.mass(1)) == batchMass
					&& actualPhaseMass(appended).equals(initialMass)
					&& appended.gasState.network.massRoundoff().equals(initialRoundoff)
					&& appended.exactInternalEnergy().equals(appended.thermalAudit().expectedInventory()),
					"condensation failed to append a representable carrier after its first receiver lost mass: " + batchMass);
			appended.captureAuthoritativeState();
		}
		FluidDomain nextReceiver = phaseMassResolutionDomain(1, false, true);
		var beforeMass = actualPhaseMass(nextReceiver);
		double unchangedMass = nextReceiver.particles.mass(0);
		require(nextReceiver.stepGasPhaseChangeOnly(1).condensedCells() == 1 && nextReceiver.particleCount() == 2
				&& nextReceiver.particles.mass(0) == unchangedMass && nextReceiver.particles.volume(0) == .125F
				&& nextReceiver.unitScale.kilograms(nextReceiver.particles.mass(1)) == 3.0
				&& actualPhaseMass(nextReceiver).equals(beforeMass),
				"condensation did not try its next representable receiver after a failed actual mass delta");
		nextReceiver.captureAuthoritativeState();
		FluidDomain represented = phaseMassResolutionDomain(256, true, false);
		var representedMass = actualPhaseMass(represented);
		require(represented.stepGasPhaseChangeOnly(1).boiledCells() == 1 && actualPhaseMass(represented).equals(representedMass),
				"an exactly represented mass increment was rejected at a large marker mass");
		represented.captureAuthoritativeState();
	}

	private static SignedDyadic actualPhaseMass(FluidDomain domain) {
		SignedDyadic mass = SignedDyadic.ZERO;
		for (int marker = 0; marker < domain.particleCount(); marker++)
			mass = mass.add(SignedDyadic.of(domain.unitScale.kilograms(domain.particles.mass(marker))));
		for (var entry : domain.gasState.network.sealedInventory().entries()) mass = mass.add(entry.mass());
		for (var group : domain.thermalState.coherentPhases().snapshot()) mass = mass.add(SignedDyadic.of(group.mass()));
		return mass;
	}

	private static FluidDomain phaseMassResolutionDomain(long batchMass, boolean boiling, boolean secondReceiver) {
		var liquids = new FluidMaterialRegistry(List.of(FluidMaterial.withSurfaceTension(
				"test:mass_water", (double) 1e20F, "test:miscible", 0, 90)));
		var water = new ThermalMaterialDefinition("test:mass_water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:mass_water", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(200, 400, 1)));
		var air = new ThermalMaterialDefinition("test:mass_air", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.AIR, 299, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(200, 400, 1)));
		var vapor = new ThermalMaterialDefinition("test:mass_vapor", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.WATER_VAPOR, 299, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(200, 400, 1)));
		var transition = new ThermalPhaseTransition("test:mass_phase", water.id(), vapor.id(), 300,
				SolidVolume.SVQ_PER_CELL / 64, SolidVolume.SVQ_PER_BV / 8, ExactRational.of(batchMass),
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 0, 1e8);
		var registry = new ThermalRegistry(List.of(water, air, vapor), List.of(), List.of(transition), "", "");
		var gases = BuiltinGasDefinitions.createRegistry();
		var domain = new FluidDomain(3, 3, 3, new FluidSolverConfig(.5F, 1e20F, 0, .05F, 100, 1e-5F, .45F, .002F, 0), 64);
		domain.sealDomainBoundary();
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++)
			if (y != 1 || z != 1 || x == 0) domain.setSolid(x, y, z, true);
		restorePhaseTestCarriers(domain, liquids, new float[]{.125F}, new int[1]);
		if (secondReceiver) require(domain.tryInsertExactMaterial(1, 1, 1, .125F, 16, 0, 0).transferred(),
				"mass-resolution receiver setup failed");
		domain.bindGasRegistry(gases); domain.bootstrapGasState("");
		domain.commitOperatorVentSealedGas(domain.planOperatorVentSealedGas());
		if (!boiling) domain.gasState.network.commitPhaseSource(1, new GasLiquidPhaseModel(registry, gases).gasBatch(transition, 4));
		domain.bindThermal(registry, domain.materialRegistry, 300); domain.stepGasPhaseChangeOnly(0); domain.captureAuthoritativeState();
		return domain;
	}

	private static void phaseRawCarrierAssignmentIsAtomic() {
		FluidDomain blocked = phaseRawAssignmentDomain(257.0);
		var beforeMaterial = blocked.exportMaterialParticleState();
		var beforeThermal = blocked.exportThermalParticleState();
		var beforeEnergy = blocked.exactInternalEnergy();
		var beforeAudit = blocked.thermalAudit();
		var beforeGas = blocked.gasBoundary().inventory(1);
		var beforeSource = blocked.gasState.network.externalSource();
		var beforeSink = blocked.gasState.network.escapedSink();
		var candidate = blocked.stepGasPhaseChangeOnly(1);
		var afterMaterial = blocked.exportMaterialParticleState();
		var afterThermal = blocked.exportThermalParticleState();
		require(candidate.operations() == 0 && blocked.particleCount() == 3
				&& Arrays.equals(beforeMaterial.copyPositionVolume(), afterMaterial.copyPositionVolume())
				&& Arrays.equals(beforeMaterial.copyParticleVelocities(), afterMaterial.copyParticleVelocities())
				&& Arrays.equals(beforeMaterial.copyParticleMass(), afterMaterial.copyParticleMass())
				&& Arrays.equals(beforeThermal.copyInternalEnergy(), afterThermal.copyInternalEnergy())
				&& Arrays.equals(beforeThermal.copyGasNodeEnergy(), afterThermal.copyGasNodeEnergy())
				&& blocked.gasBoundary().inventory(1).equals(beforeGas)
				&& blocked.gasState.network.externalSource().equals(beforeSource)
				&& blocked.gasState.network.escapedSink().equals(beforeSink)
				&& blocked.exactInternalEnergy().equals(beforeEnergy) && blocked.thermalAudit().equals(beforeAudit),
				"continuous phase LTE admission published inventories before rejecting its raw carrier assignment");
		var restored = FluidDomain.restoreAuthoritativeState(blocked.captureAuthoritativeState());
		require(restored.exactInternalEnergy().equals(beforeEnergy) && restored.thermalAudit().equals(beforeAudit),
				"deferred raw phase assignment damaged capture/restore closure");
		FluidDomain accepted = phaseRawAssignmentDomain(384.0);
		var initialEnergy = accepted.exactInternalEnergy();
		require(accepted.stepGasPhaseChangeOnly(1).operations() == 1 && accepted.particleCount() == 2
				&& accepted.totalVolumeExact() == .25 && accepted.unitScale.kilograms(accepted.totalMass()) == 2.0
				&& accepted.gasBoundary().inventory(1).totalMass() == 1.0
				&& accepted.exactInternalEnergy().equals(initialEnergy)
				&& accepted.exactInternalEnergy().equals(accepted.thermalAudit().expectedInventory())
				&& accepted.thermalState.phaseScanTemperatureInCell(13) == 172.0
				&& accepted.gasBoundary().auditClosed(),
				"a representable opposite-reference phase assignment failed its prepared inventory and energy commit");
		require(FluidDomain.restoreAuthoritativeState(accepted.captureAuthoritativeState()).exactInternalEnergy().equals(initialEnergy),
				"prepared raw phase assignment did not retain its closed actual energies through restore");
	}

	private static FluidDomain phaseRawAssignmentDomain(double gasReferenceEnergy) {
		var liquids = new FluidMaterialRegistry(List.of(
				FluidMaterial.withSurfaceTension("test:raw_water", 64, "test:miscible", 0, 90),
				FluidMaterial.withSurfaceTension("test:raw_plus", 64, "test:miscible", 0, 90),
				FluidMaterial.withSurfaceTension("test:raw_minus", 64, "test:miscible", 0, 90)));
		var water = phaseRawAssignmentMaterial("test:raw_water", ThermalMaterialDefinition.CarrierKind.LIQUID, "test:raw_water", 128, 1);
		var plus = phaseRawAssignmentMaterial("test:raw_plus", ThermalMaterialDefinition.CarrierKind.LIQUID, "test:raw_plus", 1e18, 1);
		var minus = phaseRawAssignmentMaterial("test:raw_minus", ThermalMaterialDefinition.CarrierKind.LIQUID, "test:raw_minus", -1e18, 1);
		var air = phaseRawAssignmentMaterial("test:raw_air", ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR, 0, 718);
		var vapor = phaseRawAssignmentMaterial("test:raw_vapor", ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.WATER_VAPOR, gasReferenceEnergy, 1);
		var transition = new ThermalPhaseTransition("test:raw_boil", water.id(), vapor.id(), 300,
				SolidVolume.SVQ_PER_CELL / 8, SolidVolume.SVQ_PER_BV / 32, ExactRational.ONE,
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 0, 1e8);
		var registry = new ThermalRegistry(List.of(water, plus, minus, air, vapor), List.of(), List.of(transition), "", "");
		var domain = new FluidDomain(3, 3, 3, new FluidSolverConfig(.5F, 64, 0, .05F, 100, 1e-5F, .45F, .002F, 0), 64);
		domain.sealDomainBoundary();
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++)
			if (y != 1 || z != 1 || x == 0) domain.setSolid(x, y, z, true);
		restorePhaseTestCarriers(domain, liquids, new float[]{.125F, .125F, .125F}, new int[]{0, 1, 2});
		domain.bindGasRegistry(BuiltinGasDefinitions.createRegistry()); domain.bootstrapGasState("");
		domain.commitOperatorVentSealedGas(domain.planOperatorVentSealedGas());
		domain.bindThermal(registry, domain.materialRegistry, 300); domain.captureAuthoritativeState();
		return domain;
	}

	private static ThermalMaterialDefinition phaseRawAssignmentMaterial(String id,
			ThermalMaterialDefinition.CarrierKind kind, String carrier, double reference, double cv) {
		return new ThermalMaterialDefinition(id, kind, carrier, 300, reference, 0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(100, 400, cv)));
	}

	private static FluidDomain phaseGasCapacityDomain(boolean history) {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		var gases = BuiltinGasDefinitions.createRegistry();
		var water = new ThermalMaterialDefinition("test:phase_capacity/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.WATER.id(), 373.15, 0, .6,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250, 1_000, 4_180)));
		var air = new ThermalMaterialDefinition("test:phase_capacity/air", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.AIR, 373.15, 0, .025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250, 1_000, 718)));
		var vapor = new ThermalMaterialDefinition("test:phase_capacity/vapor", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.WATER_VAPOR, 373.15, 2_256_000, .025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250, 1_000, 1_410)));
		long batchSvq = SolidVolume.SVQ_PER_CELL / 20_000;
		var transition = new ThermalPhaseTransition("test:phase_capacity/boiling", water.id(), vapor.id(), 373.15,
				batchSvq, batchSvq * 1_700, ExactRational.parse("52375/8388608"),
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 10_000, 3_000_000);
		var thermal = new ThermalRegistry(List.of(water, air, vapor), List.of(
				new ThermalSpeciesOffset("test:phase_capacity/water_h2o", BuiltinChemicalDefinitions.H2O, water.id(), ExactRational.ZERO),
				new ThermalSpeciesOffset("test:phase_capacity/vapor_h2o", BuiltinChemicalDefinitions.H2O, vapor.id(), ExactRational.ZERO)),
				List.of(transition), chemistry.fingerprint(), "");
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 1_000, 0, .05F, 100, 1E-5F, .45F, .002F, 0), 64);
		domain.sealDomainBoundary();
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++)
			if (y != 1 || z != 1 || x == 0) domain.setSolid(x, y, z, true);
		restorePhaseTestCarriers(domain, liquids, new float[]{SolidVolume.toCellVolume(2 * batchSvq)}, new int[1]);
		domain.bindChemistry(chemistry); domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 2L), false);
		domain.bindGasRegistry(gases); domain.bootstrapGasState(chemistry.fingerprint());
		domain.commitOperatorVentSealedGas(domain.planOperatorVentSealedGas());
		long quanta = history ? Long.MAX_VALUE : Long.MAX_VALUE - 1;
		double mass = chemistry.species(chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)).massKilogramsPerAu()
				.multiply(quanta).divide(ChemicalLimits.QUANTA_PER_AU).toDouble();
		// Keep the species-capacity control within the transferred mass storage resolution.
		double storedMass = history ? mass : 1e8;
		var previousSource = new GasInventory(List.of(new GasComponentAmount(BuiltinGasDefinitions.WATER_VAPOR,
				1_000_000, storedMass, List.of(new GasComponentAmount.SpeciesAmount(BuiltinChemicalDefinitions.H2O, quanta)), storedMass - mass)));
		domain.gasState.network.commitPhaseSource(1, previousSource);
		if (history) domain.gasState.network.commitPhaseSink(1, previousSource);
		domain.bindThermal(thermal, domain.materialRegistry, 900);
		require(domain.gasBoundary().auditClosed() && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"gas phase capacity fixture did not start from an admitted, closed inventory");
		return domain;
	}

	private static void phaseGasEnergyRepresentationRetainsSmallerBatch() {
		var liquids = new FluidMaterialRegistry(List.of(
				FluidMaterial.withSurfaceTension("test:phase_energy/water", 64, "test:miscible", 0, 90),
				FluidMaterial.withSurfaceTension("test:phase_energy/bath", 64, "test:miscible", 0, 90)));
		var water = new ThermalMaterialDefinition("test:phase_energy/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				liquids.material(0).id(), 300, 1E308, 0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250, 350, 1E307)));
		var bath = new ThermalMaterialDefinition("test:phase_energy/bath", ThermalMaterialDefinition.CarrierKind.LIQUID,
				liquids.material(1).id(), 300, -1E308, 0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250, 350, 1)));
		var air = new ThermalMaterialDefinition("test:phase_energy/air", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.AIR, 300, 0, 0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250, 350, 718)));
		var vapor = new ThermalMaterialDefinition("test:phase_energy/vapor", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.WATER_VAPOR, 300, Math.nextUp(1E308), 0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250, 350, 1)));
		var transition = new ThermalPhaseTransition("test:phase_energy/boiling", water.id(), vapor.id(), 300,
				SolidVolume.SVQ_PER_CELL / 8, SolidVolume.SVQ_PER_BV / 8, ExactRational.ONE,
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 0, 1E8);
		var thermal = new ThermalRegistry(List.of(water, bath, air, vapor), List.of(), List.of(transition), "", "");
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 64, 0, .05F, 100, 1E-5F, .45F, .002F, 0), 64);
		domain.sealDomainBoundary();
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++)
			if (y != 1 || z != 1 || x == 0) domain.setSolid(x, y, z, true);
		restorePhaseTestCarriers(domain, liquids, new float[]{.125F, .125F, .125F, .0625F}, new int[]{0, 0, 1, 0});
		require(domain.particleCount() == 4, "phase energy fixture needs three water owners and one heat bath");
		domain.bindGasRegistry(BuiltinGasDefinitions.createRegistry()); domain.bootstrapGasState("");
		domain.commitOperatorVentSealedGas(domain.planOperatorVentSealedGas()); domain.bindThermal(thermal, domain.materialRegistry, 300);
		domain.captureAuthoritativeState();
		var energyBefore = domain.exactInternalEnergy(); var auditBefore = domain.thermalAudit();
		double volumeBefore = domain.totalVolumeExact(), massBefore = domain.unitScale.kilograms(domain.totalMass());
		require(energyBefore.toDouble() == 1.5E308
				&& domain.thermalState.temperature(0, domain.thermalMarkerContext()) == 300,
				"large-reference phase fixture did not begin with finite raw energies and a valid temperature");
		var accepted = domain.stepGasPhaseChangeOnly(2);
		var gas = domain.gasBoundary().inventory(1);
		require(accepted.operations() == 1 && accepted.boiledCells() == 1 && accepted.condensedCells() == 0
				&& gas.components().size() == 1 && gas.totalMass() == 1 && gas.components().getFirst().species().isEmpty()
				&& domain.unitScale.kilograms(domain.totalMass()) + gas.totalMass() == massBefore
				&& domain.totalVolumeExact() == volumeBefore - SolidVolume.toCellVolume(transition.lowerVolumeSvqPerBatch())
				&& domain.exactInternalEnergy().subtract(energyBefore).equals(
						domain.thermalAudit().roundoffResidual().subtract(auditBefore.roundoffResidual()))
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& domain.gasBoundary().auditClosed(),
				"a non-representable largest gas energy batch prevented its smaller complete batch");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
				&& restored.thermalAudit().equals(domain.thermalAudit()) && restored.gasBoundary().inventory(1).equals(gas),
				"representable phase energy candidate did not preserve its actual owner state through restore");
	}

	static void massOnlyCondensationUsesExistingReceiver() {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		var gasRegistry = BuiltinGasDefinitions.createRegistry();
		var water = phaseSearchMaterial("test:receiver/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.WATER.id(), 0, 4_180);
		var air = phaseSearchMaterial("test:receiver/air", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.AIR, 0, 718);
		var vapor = phaseSearchMaterial("test:receiver/vapor", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.WATER_VAPOR, 2_256_000, 1_410);
		long batchSvq = SolidVolume.SVQ_PER_CELL / 20_000L;
		var transition = new ThermalPhaseTransition("test:receiver/condensation", water.id(), vapor.id(), 373.15,
				batchSvq, batchSvq * 1_700, ExactRational.parse("52375/8388608"),
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 10_000, 3_000_000);
		var thermal = new ThermalRegistry(List.of(water, air, vapor), List.of(
				new ThermalSpeciesOffset("test:receiver/water_h2o", BuiltinChemicalDefinitions.H2O, water.id(), ExactRational.ZERO),
				new ThermalSpeciesOffset("test:receiver/vapor_h2o", BuiltinChemicalDefinitions.H2O, vapor.id(), ExactRational.ZERO)),
				List.of(transition), chemistry.fingerprint(), "");
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 1_000, 0, .05F, 100, 1E-5F, .45F, .002F, 0), 64);
		domain.sealDomainBoundary();
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++)
			if (y != 1 || z != 1 || x == 0) domain.setSolid(x, y, z, true);
		restorePhaseTestCarriers(domain, liquids, new float[]{.1F}, new int[1]);
		domain.bindChemistry(chemistry);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 1L), false);
		domain.bindGasRegistry(gasRegistry); domain.bootstrapGasState(chemistry.fingerprint());
		domain.commitOperatorVentSealedGas(domain.planOperatorVentSealedGas());
		var phase = new GasLiquidPhaseModel(thermal, gasRegistry);
		double mass = transition.massMuPerBatch().multiply(3).toDouble();
		double speciesMass = chemistry.species(chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O))
				.massKilogramsPerAu().divide(ChemicalLimits.QUANTA_PER_AU).toDouble();
		var gas = phase.gasBatch(transition, 3, Map.of(BuiltinChemicalDefinitions.H2O, 1L), mass - speciesMass);
		domain.gasState.network.commitPhaseSource(1, gas);
		domain.bindThermal(thermal, domain.materialRegistry, 250);
		long nanos = phase.gasBatch(transition, 1).standardVolumeNanos();
		var proportional = domain.gasState.network.previewComponentTake(1, BuiltinGasDefinitions.WATER_VAPOR, nanos);
		require(proportional.first().components().getFirst().species().isEmpty(),
				"one of three gas batches must round its single H2O quantum to the remaining gas");
		double batchMass = transition.massMuPerBatch().toDouble();
		var take = domain.gasState.network.previewComponentTake(
				1, BuiltinGasDefinitions.WATER_VAPOR, nanos, batchMass, batchMass);
		var chemicalBefore = domain.exportChemicalParticleState();
		var energyBefore = domain.exactInternalEnergy(); var auditBefore = domain.thermalAudit();
		var massBefore = SignedDyadic.of(domain.unitScale.kilograms(domain.totalMass())).add(SignedDyadic.of(gas.totalMass()));
		var residualBefore = SignedDyadic.of(chemicalBefore.residualLedger()).add(SignedDyadic.of(gas.components().getFirst().massResidual()));
		double volumeBefore = domain.totalVolumeExact();
		var stats = domain.stepGasPhaseChangeOnly(1);
		var remaining = domain.gasBoundary().inventory(1);
		var chemicalAfter = domain.exportChemicalParticleState();
		require(stats.operations() == 1 && stats.condensedCells() == 1 && stats.boiledCells() == 0
				&& domain.particleCount() == 1 && remaining.equals(take.second())
				&& Arrays.equals(chemicalAfter.copySpeciesTotals(), chemicalBefore.copySpeciesTotals())
				&& remaining.component(BuiltinGasDefinitions.WATER_VAPOR).species().equals(gas.components().getFirst().species())
				&& domain.totalVolumeExact() - volumeBefore == SolidVolume.toCellVolume(batchSvq),
				"mass-only gas batch must merge into its positive-species liquid receiver");
		var massAfter = SignedDyadic.of(domain.unitScale.kilograms(domain.totalMass())).add(SignedDyadic.of(remaining.totalMass()));
		var residualAfter = SignedDyadic.of(chemicalAfter.residualLedger()).add(SignedDyadic.of(remaining.components().getFirst().massResidual()));
		require(Math.abs(massAfter.subtract(massBefore).toDouble()) <= .5 * Math.ulp(domain.unitScale.kilograms(domain.totalMass()))
				&& Math.abs(residualAfter.subtract(residualBefore).toDouble()) <= Math.ulp(chemicalAfter.residualLedger()),
				"mass-only condensation preserves kg and residual within the receiver's storage rounding");
		require(domain.exactInternalEnergy().subtract(energyBefore).equals(
				domain.thermalAudit().roundoffResidual().subtract(auditBefore.roundoffResidual()))
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& domain.gasBoundary().auditClosed(), "mass-only condensation closes actual energy and gas audits");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
				&& restored.thermalAudit().equals(domain.thermalAudit())
				&& restored.gasBoundary().inventory(1).equals(remaining)
				&& restored.totalMass() == domain.totalMass() && restored.totalVolumeExact() == domain.totalVolumeExact()
				&& Arrays.equals(restored.exportChemicalParticleState().copySpeciesTotals(), chemicalAfter.copySpeciesTotals())
				&& restored.exportChemicalParticleState().residualLedger() == chemicalAfter.residualLedger(),
				"mass-only condensation capture/restore retains its coupled inventories");
	}

	static void compositionBearingBoilingSearchesCompleteBatches() {
		for (int maximum : new int[]{2, 4}) boilingIntegerCandidateSearch(maximum);
	}

	private static void boilingIntegerCandidateSearch(int maximum) {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER, BuiltinFluidMaterials.OIL));
		var water = phaseSearchMaterial("test:search/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.WATER.id(), 0, 4_180);
		var oil = phaseSearchMaterial("test:search/oil", ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.OIL.id(), 0, 2_000);
		var air = phaseSearchMaterial("test:search/air", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.AIR, 0, 718);
		var vapor = phaseSearchMaterial("test:search/vapor", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.WATER_VAPOR, 2_256_000, 1_410);
		long batchSvq = SolidVolume.SVQ_PER_CELL / 20_000L;
		var transition = new ThermalPhaseTransition("test:search/boiling", water.id(), vapor.id(), 373.15,
				batchSvq, batchSvq * 1_700, ExactRational.parse("52375/8388608"),
				ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS, 10_000, 3_000_000);
		var thermal = new ThermalRegistry(List.of(water, oil, air, vapor), List.of(
				new ThermalSpeciesOffset("test:search/water_h2o", BuiltinChemicalDefinitions.H2O, water.id(), ExactRational.ZERO),
				new ThermalSpeciesOffset("test:search/oil_carrier", BuiltinChemicalDefinitions.OIL_CARRIER, oil.id(), ExactRational.ZERO),
				new ThermalSpeciesOffset("test:search/vapor_h2o", BuiltinChemicalDefinitions.H2O, vapor.id(), ExactRational.ZERO)),
				List.of(transition), chemistry.fingerprint(), "");
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 1_000, 0, .05F, 100, 1E-5F, .45F, .002F, 0), 64);
		domain.sealDomainBoundary();
		restorePhaseTestCarriers(domain, liquids, new float[]{SolidVolume.toCellVolume(2 * batchSvq)}, new int[1]);
		if (maximum == 4) require(domain.tryInsertExactVolume(1, 1, 1,
				SolidVolume.toCellVolume(2 * batchSvq), 0, 0).transferred(), "integer-search second water insertion");
		require(domain.tryInsertExactVolume(1, 1, 1, .125F, 0, 1).transferred(), "integer-search oil heat bath insertion");
		domain.bindChemistry(chemistry);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 1L), false);
		if (maximum == 4) domain.setMarkerChemical(1, Map.of(BuiltinChemicalDefinitions.H2O, 3L), false);
		domain.bindGasRegistry(BuiltinGasDefinitions.createRegistry());
		domain.bootstrapGasState("");
		domain.commitOperatorVentSealedGas(domain.planOperatorVentSealedGas());
		domain.bindThermal(thermal, domain.materialRegistry, 500);
		var before = domain.exportChemicalParticleState();
		var energy = domain.exactInternalEnergy();
		var audit = domain.thermalAudit();
		double mass = domain.unitScale.kilograms(domain.totalMass());
		double volume = domain.totalVolumeExact();
		// One H2O quantum permits a complete two-batch take but no partial one-batch take.
		// With a second 3-quantum carrier, batches 1/2/4 are valid and 3 is unrepresentable.
		var stats = domain.stepGasPhaseChangeOnly(maximum);
		require(stats.operations() == maximum && stats.boiledCells() == 1 && stats.condensedCells() == 0,
				"integer feasibility holes must retain the largest complete boiling candidate: max=" + maximum + " actual=" + stats);
		var gas = domain.gasBoundary().inventory(1).component(BuiltinGasDefinitions.WATER_VAPOR);
		var after = domain.exportChemicalParticleState();
		long[] species = after.copySpeciesTotals();
		for (var amount : gas.species()) species[chemistry.speciesHandle(amount.speciesId())] =
				Math.addExact(species[chemistry.speciesHandle(amount.speciesId())], amount.amountQuanta());
		require(Arrays.equals(species, before.copySpeciesTotals())
				&& domain.unitScale.kilograms(domain.totalMass()) + gas.mass() == mass
				&& Math.abs(after.residualLedger() + gas.massResidual() - before.residualLedger())
				<= 16 * Math.ulp(before.residualLedger())
				&& volume - domain.totalVolumeExact() == SolidVolume.toCellVolume(maximum * batchSvq),
				"complete boiling preserves actual kg, integer species, residual and liquid SVQ");
		require(domain.exactInternalEnergy().subtract(energy).equals(
				domain.thermalAudit().roundoffResidual().subtract(audit.roundoffResidual()))
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& domain.gasBoundary().auditClosed(), "complete boiling books only actual energy roundoff and closes gas/thermal audits");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
				&& restored.thermalAudit().equals(domain.thermalAudit())
				&& restored.gasBoundary().inventory(1).equals(domain.gasBoundary().inventory(1))
				&& Arrays.equals(restored.exportChemicalParticleState().copySpeciesTotals(), after.copySpeciesTotals())
				&& restored.totalVolumeExact() == domain.totalVolumeExact()
				&& restored.totalMass() == domain.totalMass(), "complete boiling capture/restore retains the coupled inventories");
	}

	private static ThermalMaterialDefinition phaseSearchMaterial(String id, ThermalMaterialDefinition.CarrierKind kind,
			String carrier, double reference, double capacity) {
		return new ThermalMaterialDefinition(id, kind, carrier, 373.15, reference, .1,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250, 800, capacity)));
	}

	private static void condensationCapacityRetry(FluidDomain domain) {
		var original = domain.chemicalParcels.record(1).stableAmounts(domain.chemicalParcels.registry());
		long fill = ChemicalLimits.MAX_REGION_TOTAL_QUANTA - domain.chemicalParcels.totalQuanta()
				+ domain.chemicalParcels.record(1).totalQuanta();
		domain.setMarkerChemical(1, Map.of(BuiltinChemicalDefinitions.H2O, fill), false);
		SignedDyadic energy = domain.exactInternalEnergy();
		long gasNanos = domain.gasBoundary().standardVolumeNanos(1);
		require(domain.stepGasPhaseChangeOnly(1).operations() == 0
				&& domain.gasBoundary().standardVolumeNanos(1) == gasNanos
				&& domain.exactInternalEnergy().equals(energy),
				"condensation reserves chemical capacity before releasing vapor");
		domain.setMarkerChemical(1, original, false);
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
