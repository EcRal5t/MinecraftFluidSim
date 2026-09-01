package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Deterministic normal-play operation matrix through private candidates, HEAD and cold restore. */
final class ControllerInventoryDurabilityValidation {
	private static final ResourceKey<Registry<Level>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(
			Identifier.fromNamespaceAndPath("mmm", "controller_inventory_durability_registry"));
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(DIMENSION_REGISTRY,
			Identifier.fromNamespaceAndPath("mmm", "controller_inventory_durability"));

	private ControllerInventoryDurabilityValidation() { }

	static void run() throws Exception {
		Path root = Files.createTempDirectory("mmm-controller-inventory-durability");
		try {
			FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
			FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig(), 4_096);
			domain.sealDomainBoundary();
			FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
			FullCapabilityTestFixture.bindFull(domain, registries, 293.15,
					cc.ecisr.mmm.thermal.ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
			FluidRegion region = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO, domain, 4_096L,
					footprint());
			RegionRepositoryAdapter persistence = new RegionRepositoryAdapter(root, TestProfiles.full(),
					registries.chemical(), registries.granular(), registries.thermal(), registries.gas());
			check(persistence.checkpoint(region) == 1L, "operation matrix did not publish its empty base");

			int water = registries.liquids().handle(BuiltinFluidMaterials.WATER.id());
			int oil = registries.liquids().handle(BuiltinFluidMaterials.OIL.id());
			int mercury = registries.liquids().handle(BuiltinFluidMaterials.MERCURY.id());
			for (int bucket = 0; bucket < 4; bucket++) insertLiquid(region, persistence, water, 7 - bucket, 7, 7);
			stepAndPublish(region, persistence, true);
			for (int bucket = 0; bucket < 4; bucket++) insertLiquid(region, persistence, oil, bucket, 7, 7);
			for (int bucket = 0; bucket < 4; bucket++) insertLiquid(region, persistence, mercury, 7, bucket, 7);
			restart(region, persistence, "three-material rapid pours");

			mutate(region, persistence, candidate -> check(candidate.tryInsertExactMixture(7, 7, 0,
					java.util.List.of(
							new FluidDomain.MaterialPortion(water, 2.0, 2_000.0),
							new FluidDomain.MaterialPortion(oil, 2.0, 1_600.0),
							new FluidDomain.MaterialPortion(mercury, 2.0, 27_200.0)),
					6, true).transferred(), "mixed-material container was rejected"));
			mutate(region, persistence, candidate -> check(
					candidate.tryExtractExactMixture(7, 7, 7, 4.0F, 7).transferred(),
					"mixed-material extraction was rejected"));
			restart(region, persistence, "mixed extraction");

			ChemicalTransferLedger acid = reagentBucket(registries, BuiltinChemicalDefinitions.HCL);
			double acidEnergy = reagentEnergy(registries, acid);
			mutate(region, persistence, candidate -> check(candidate.tryInsertExactChemical(
					7, 7, 7, acid, 6, true, acidEnergy).transferred(), "first acid bucket was rejected"));
			mutate(region, persistence, candidate -> check(candidate.editIndicatorFloodFill(
					7, 7, 7, true, 512, 100.0).accepted(), "acid indicator edit was rejected"));
			mutate(region, persistence, candidate -> check(candidate.tryInsertExactChemical(
					7, 7, 7, acid, 6, true, acidEnergy).transferred(), "post-indicator acid bucket was rejected"));
			for (int step = 0; step < 20; step++) stepAndPublish(region, persistence, true);
			settlePendingVolumeWork(region, persistence);
			restart(region, persistence, "acid/indicator/post-acid phase replay");

			int sand = registries.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND);
			double sandMass = registries.granular().material(sand).referenceDensity();
			GranularTransferLedger sandBucket = new GranularTransferLedger(java.util.List.of(
					new GranularTransferLedger.Group(BuiltinGranularDefinitions.FINE_SAND,
							SolidVolume.SVQ_PER_BV, sandMass, null)));
			for (int bucket = 0; bucket < 4; bucket++) mutate(region, persistence, candidate -> check(
					candidate.tryInsertExactGranular(7, 7, 7, sandBucket, 6, true).inserted(),
					"fine-sand container was rejected"));
			stepAndPublish(region, persistence, false);
			restart(region, persistence, "liquid and granular inventory");

			mutate(region, persistence, candidate -> {
				candidate.commitOperatorClear(candidate.planOperatorClear());
				check(candidate.totalVolumeExact() == 0.0 && candidate.totalGranularSolidVolumeSvq() == 0L,
						"operator clear left condensed inventory");
			});
			mutate(region, persistence, candidate -> candidate.commitOperatorVentSealedGas(
					candidate.planOperatorVentSealedGas()));
			restart(region, persistence, "clear and vent");
			insertLiquid(region, persistence, water, 7, 7, 7);
			restart(region, persistence, "post-vent reinsertion");
			mutate(region, persistence, candidate -> candidate.commitOperatorClear(candidate.planOperatorClear()));
			restart(region, persistence, "post-vent second clear");

			region.lifecycle().setOperatorPaused(false, 1);
			for (int quiet = 0; quiet < 200; quiet++) {
				region.lifecycle().observeCompletedStep(true, 200);
			}
			check(region.lifecycle().state() == FluidRegionLifecycle.State.HYDROSTATIC_SLEEP,
					"thermal restart fixture did not enter hydrostatic sleep");
			restart(region, persistence, "running hydrostatic thermal checkpoint");
			check(region.lifecycle().state() == FluidRegionLifecycle.State.ACTIVE,
					"cold thermal restore resumed without rebuilding its derived pressure projection");
			runNormalPlayStateMatrix(registries);
		} finally {
			try (var paths = Files.walk(root)) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}

	private static void insertLiquid(
			FluidRegion region, RegionRepositoryAdapter persistence, int material, int x, int y, int z
	) throws Exception {
		settlePendingVolumeWork(region, persistence);
		mutate(region, persistence, candidate -> check(
				candidate.tryInsertExactVolume(x, y, z, 8.0F, 6, material, true).transferred(),
				"liquid container was rejected for material " + material));
	}

	private static void mutate(
			FluidRegion region, RegionRepositoryAdapter persistence, CandidateMutation mutation
	) throws Exception {
		long base = region.checkpointGeneration();
		FluidDomain candidate = FluidDomain.restoreAuthoritativeState(region.domain().captureAuthoritativeState());
		mutation.apply(candidate);
		long target = base + 1L;
		RegionRepositoryAdapter.StagedCheckpoint staged = persistence.stageCandidateCheckpoint(
				region, candidate, target, region.paused());
		check(persistence.commitStagedLifecycleCheckpoint(staged) == target,
				"candidate HEAD publication returned another generation");
		persistence.adoptCommittedContainerCheckpoint(region, base, target);
		checkClosed(region.domain(), "candidate generation " + target);
	}

	private static void stepAndPublish(
			FluidRegion region, RegionRepositoryAdapter persistence, boolean phaseChange
	) throws Exception {
		region.domain().step(phaseChange, phaseChange ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0);
		checkClosed(region.domain(), "step before generation " + (region.checkpointGeneration() + 1L));
		persistence.checkpoint(region);
		restart(region, persistence, "published step");
	}

	private static void settlePendingVolumeWork(
			FluidRegion region, RegionRepositoryAdapter persistence
	) throws Exception {
		if (region.domain().hasPendingPhaseVolumeWork()) stepAndPublish(region, persistence, false);
	}

	private static void restart(
			FluidRegion region, RegionRepositoryAdapter persistence, String context
	) throws Exception {
		persistence.checkpointAndUnload(region);
		persistence.restore(region);
		check(region.solverLoaded(), context + " did not restore the solver");
		checkClosed(region.domain(), context);
	}

	private static void checkClosed(FluidDomain domain, String context) {
		check(domain.gasBoundary().auditClosed(), context + " left the gas audit open");
		check(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				context + " left the thermal audit open");
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		check(restored.gasBoundary().auditClosed()
				&& restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory()),
				context + " did not survive authoritative in-memory reconstruction");
	}

	private static FluidRegionFootprint footprint() {
		Set<Long> interior = new HashSet<>();
		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
			interior.add(new BlockPos(x, y, z).asLong());
		}
		BlockPos controller = new BlockPos(-1, 1, 1);
		return new FluidRegionFootprint(controller, BlockPos.ZERO, new BlockPos(3, 3, 3), interior,
				Set.of(controller.asLong()), Set.of(ChunkPos.pack(0, 0), ChunkPos.pack(-1, 0)));
	}

	private static ChemicalTransferLedger reagentBucket(
			FullCapabilityTestFixture.Registries registries, String reagent
	) {
		java.util.Map<String, Long> amounts = java.util.Map.of(
				BuiltinChemicalDefinitions.H2O, 50_000_000_000L,
				reagent, 1_000_000_000L);
		double mass = 1_000.0;
		ChemicalMixtureRecord withoutResidual = ChemicalMixtureRecord.fromStableAmounts(
				registries.chemical(), amounts, 0.0);
		ChemicalMixtureRecord mixture = ChemicalMixtureRecord.fromStableAmounts(registries.chemical(), amounts,
				mass - withoutResidual.exactSpeciesMassKilograms(registries.chemical()).toDouble());
		return new ChemicalTransferLedger(java.util.List.of(new ChemicalTransferLedger.Group(
				BuiltinFluidMaterials.WATER.id(), false, 1.0, mass,
				mixture.massResidualKilogramsBits(), mixture.stableAmounts(registries.chemical()))));
	}

	private static double reagentEnergy(
			FullCapabilityTestFixture.Registries registries, ChemicalTransferLedger reagent
	) {
		ChemicalTransferLedger.Group group = reagent.groups().getFirst();
		int material = registries.thermal().materialHandle(
				ThermalMaterialDefinition.CarrierKind.LIQUID, group.materialId());
		return new ThermalStateFunction(registries.thermal()).internalEnergyAtTemperature(
				material, group.massKilograms(), group.speciesQuanta(), 293.15);
	}

	/** Crosses normal player actions with full backend handoff after every accepted transition. */
	private static void runNormalPlayStateMatrix(FullCapabilityTestFixture.Registries registries) throws Exception {
		runScenario("reported acid-indicator-acid", registries, state -> {
			state.insertAcid(); state.indicator(true); state.insertAcid(); state.steps(true, 20);
		});
		runScenario("reported vent-water-resume", registries, state -> {
			state.vent(); state.insertWater(); state.steps(true, 20);
		});
		runScenario("vent-direct-resume", registries, state -> { state.vent(); state.step(true); });
		runScenario("vent-cold-restore-resume", registries, state -> {
			state.vent(); state.roundTrip(); state.step(true);
		});
		runScenario("vent-then-condensed-clear", registries, state -> {
			state.vent(); state.clear(); state.insertWater(); state.step(true);
		});
		runScenario("repeated-vent", registries, state -> {
			state.vent(); state.vent(); state.step(true);
		});
		runScenario("vacuum-acid-indicator-phase", registries, state -> {
			state.vent(); state.insertAcid(); state.indicator(true); state.step(true);
		});
		runScenario("vacuum-granular-motion", registries, state -> {
			state.vent(); state.insertSand(); state.steps(false, 4);
		});
		runScenario("vacuum-mercury-motion", registries, state -> {
			state.vent(); state.insertMercury(); state.steps(true, 4);
		});
		runScenario("clear-vent-reinsert-clear", registries, state -> {
			state.insertWater(); state.clear(); state.vent(); state.insertWater(); state.step(true); state.clear();
		});
		runScenario("phase-lien-vent-restart", registries, state -> {
			state.insertWater(); state.steps(true, 8); state.settle(); state.vent(); state.roundTrip(); state.step(true);
		});
		runScenario("mixed-clear-vent-extract", registries, state -> {
			state.insertWater(); state.insertMercury(); state.insertSand(); state.steps(false, 3);
			state.clear(); state.vent(); state.insertWater(); state.extractWater(); state.step(true);
		});
	}

	private static void runScenario(
			String name, FullCapabilityTestFixture.Registries registries, Scenario scenario
	) throws Exception {
		try {
			scenario.run(new NormalPlayState(registries));
		} catch (RuntimeException | AssertionError failure) {
			throw new AssertionError("normal-play state-machine scenario failed: " + name, failure);
		}
	}

	private static final class NormalPlayState {
		private final FullCapabilityTestFixture.Registries registries;
		private final int water;
		private final int mercury;
		private final ChemicalTransferLedger acid;
		private final double acidEnergy;
		private final GranularTransferLedger sand;
		private FluidDomain domain;
		private long generation;

		NormalPlayState(FullCapabilityTestFixture.Registries registries) throws Exception {
			this.registries = registries;
			this.domain = new FluidDomain(8, 8, 8, productionConfig(), 4_096);
			domain.sealDomainBoundary();
			FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
			FullCapabilityTestFixture.bindFull(domain, registries, 293.15,
					cc.ecisr.mmm.thermal.ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
			water = registries.liquids().handle(BuiltinFluidMaterials.WATER.id());
			mercury = registries.liquids().handle(BuiltinFluidMaterials.MERCURY.id());
			acid = reagentBucket(registries, BuiltinChemicalDefinitions.HCL);
			acidEnergy = reagentEnergy(registries, acid);
			int sandHandle = registries.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND);
			double mass = registries.granular().material(sandHandle).referenceDensity();
			sand = new GranularTransferLedger(java.util.List.of(new GranularTransferLedger.Group(
					BuiltinGranularDefinitions.FINE_SAND, SolidVolume.SVQ_PER_BV, mass, null)));
			roundTrip();
		}

		void insertWater() { insertLiquid(water); }
		void insertMercury() { insertLiquid(mercury); }
		void insertLiquid(int material) {
			settle();
			check(domain.tryInsertExactVolume(7, 7, 7, 8.0F, 6, material, true).transferred(),
					"normal-play liquid insertion was rejected");
			roundTrip();
		}
		void insertAcid() {
			settle();
			check(domain.tryInsertExactChemical(7, 7, 7, acid, 6, true, acidEnergy).transferred(),
					"normal-play acid insertion was rejected");
			roundTrip();
		}
		void insertSand() {
			settle();
			check(domain.tryInsertExactGranular(7, 7, 7, sand, 6, true).inserted(),
					"normal-play granular insertion was rejected");
			roundTrip();
		}
		void indicator(boolean value) {
			check(domain.editIndicatorFloodFill(7, 7, 7, value, 512, 100.0).accepted(),
					"normal-play indicator edit was rejected");
			roundTrip();
		}
		void extractWater() {
			settle();
			check(domain.tryExtractExactVolume(7, 7, 7, 8.0F, 6).transferred(),
					"normal-play water extraction was rejected");
			roundTrip();
		}
		void clear() {
			settle();
			domain.commitOperatorClear(domain.planOperatorClear());
			roundTrip();
		}
		void vent() {
			settle();
			domain.commitOperatorVentSealedGas(domain.planOperatorVentSealedGas());
			roundTrip();
		}
		void settle() { if (domain.hasPendingPhaseVolumeWork()) step(false); }
		void steps(boolean phase, int count) { for (int step = 0; step < count; step++) step(phase); }
		void step(boolean phase) {
			var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
					generation, domain.captureAuthoritativeState(), java.util.List.of(), phase,
					phase ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0));
			domain = candidate.claimPreparedCpuDomain();
			generation++;
			roundTrip();
		}
		void roundTrip() {
			checkClosed(domain, "normal-play handoff generation " + generation);
			domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			checkClosed(domain, "normal-play restore generation " + generation);
		}
	}

	@FunctionalInterface private interface Scenario { void run(NormalPlayState state) throws Exception; }

	private static FluidSolverConfig productionConfig() {
		return new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	@FunctionalInterface
	private interface CandidateMutation {
		void apply(FluidDomain candidate) throws Exception;
	}
}
