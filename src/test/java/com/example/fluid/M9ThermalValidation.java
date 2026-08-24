package com.example.fluid;

import com.example.chemistry.ChemicalLimits;
import com.example.chemistry.ChemicalMixtureRecord;
import com.example.chemistry.ChemicalParcelStore;
import com.example.chemistry.ChemicalPhaseDefinition;
import com.example.chemistry.ChemicalReaction;
import com.example.chemistry.ChemicalRegistry;
import com.example.chemistry.ChemicalSpecies;
import com.example.chemistry.ExactRational;
import com.example.granular.SolidVolume;
import com.example.thermal.SignedDyadic;
import com.example.thermal.GranularEnergyColumn;
import com.example.thermal.CoherentPhaseStore;
import com.example.thermal.ThermalBoundaryDefinition;
import com.example.thermal.ThermalAuditLedger;
import com.example.thermal.ThermalDefinitionCodec;
import com.example.thermal.ThermalMaterialDefinition;
import com.example.thermal.ThermalPhaseTransition;
import com.example.thermal.ThermalPhaseChangeEngine;
import com.example.thermal.ThermalRegistry;
import com.example.thermal.ThermalRegistryStore;
import com.example.thermal.ThermalSpeciesOffset;
import com.example.thermal.ThermalStateFunction;
import com.example.thermal.ThermalTransport;

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
		reactionHeatIsPathIndependentStateEnergy();
		reactionCommitValidatesEnergyBeforeMutation();
		phasePlateauUsesTheStateFunctionGap();
		coherentPhaseInventoryAndExactBatchPlanning();
		granularEnergyColumnTransactions();
		exactDyadicAuditDoesNotLoseCancellationRemainders();
		liquidTransportAndHandoffCloseEnergy();
		fixedBoundaryAuditsAndFailedStepRollsBack();
		reseedingMovesEnergyWithMarkerTruth();
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
