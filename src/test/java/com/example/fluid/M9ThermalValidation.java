package com.example.thermal;

import com.example.chemistry.ChemicalLimits;
import com.example.chemistry.ExactRational;
import com.example.granular.SolidVolume;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

/** One retained selector for M9 definition, state-function and later transport/phase invariants. */
public final class M9ThermalValidation {
	private static final String CHEMICAL_FINGERPRINT = "1".repeat(64);

	private M9ThermalValidation() { }

	public static void main(String[] args) {
		definitionAndCanonicalRegistry();
		piecewiseStateFunctionRoundTrips();
		reactionHeatIsPathIndependentStateEnergy();
		phasePlateauUsesTheStateFunctionGap();
		exactDyadicAuditDoesNotLoseCancellationRemainders();
		System.out.println("M9 thermal validation passed");
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
		expectFailure(() -> new ThermalMaterialDefinition("realwater:thermal/broken",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "realwater:broken", 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 299.0, 1.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 1.0))), "non-contiguous segments accepted");
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

	private static void exactDyadicAuditDoesNotLoseCancellationRemainders() {
		SignedDyadic exact = SignedDyadic.sum(new double[]{1.0E16, 1.0, -1.0E16});
		require(exact.equals(SignedDyadic.of(1.0)), "exact dyadic audit lost a cancellation remainder");
		for (double value : new double[]{0.0, -0.0, Double.MIN_VALUE, -17.25, Double.MAX_VALUE}) {
			require(Double.doubleToRawLongBits(SignedDyadic.of(value).toDouble())
					== Double.doubleToRawLongBits(value == 0.0 ? 0.0 : value),
					"dyadic conversion did not round-trip " + value);
		}
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
