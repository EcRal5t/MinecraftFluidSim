package cc.ecisr.mmm.simulation;

import static cc.ecisr.mmm.simulation.FuzzConfig.FUZZ_DEBUG;
import static cc.ecisr.mmm.simulation.FuzzConfig.FUZZ_DISABLE_GRANULAR;
import static cc.ecisr.mmm.simulation.FuzzConfig.FUZZ_DISK;
import static cc.ecisr.mmm.simulation.FuzzConfig.FUZZ_DISK_ROUND_TRIP_INTERVAL;
import static cc.ecisr.mmm.simulation.FuzzConfig.FUZZ_PARTIAL;
import static cc.ecisr.mmm.simulation.FuzzConfig.FUZZ_PICKS;
import static cc.ecisr.mmm.simulation.FuzzConfig.FUZZ_PROGRESS;
import static cc.ecisr.mmm.simulation.FuzzConfig.JOB_TIMEOUT_MILLIS;
import static cc.ecisr.mmm.simulation.FuzzConfig.MINIMUM_VOLUMES;
import static cc.ecisr.mmm.simulation.FuzzConfig.RAMP_DELTAS;
import static cc.ecisr.mmm.simulation.FuzzConfig.RANDOM_TEMPERATURES;
import static cc.ecisr.mmm.simulation.FuzzConfig.SCENE_HALF_BOTTOM;
import static cc.ecisr.mmm.simulation.FuzzConfig.actionName;
import static cc.ecisr.mmm.simulation.FuzzConfig.partialVolume;
import static cc.ecisr.mmm.simulation.FuzzMetrics.ACTION_COUNTS;
import static cc.ecisr.mmm.simulation.FuzzMetrics.ACTION_COVERAGE;
import static cc.ecisr.mmm.simulation.FuzzMetrics.ACTION_NANOS;
import static cc.ecisr.mmm.simulation.FuzzMetrics.ACTION_SUBSTEPS;
import static cc.ecisr.mmm.simulation.FuzzMetrics.JOB_MILLIS;
import static cc.ecisr.mmm.simulation.FuzzMetrics.JOB_SUBSTEPS;
import static cc.ecisr.mmm.simulation.FuzzMetrics.cover;
import static cc.ecisr.mmm.simulation.FuzzMetrics.coverageLabel;

import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.application.command.MotionStepCommand;
import cc.ecisr.mmm.application.result.MotionStepResult;
import cc.ecisr.mmm.application.result.RegionStepStatus;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidDomainState;
import cc.ecisr.mmm.fluid.FluidDomainStateAssertions;
import cc.ecisr.mmm.fluid.FluidCellInventoryView;
import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import cc.ecisr.mmm.fluid.FluidThermalStepRequest;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.fluid.FluidThermalStepCandidate;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.units.SolverUnitScale;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
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
import java.util.List;

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
					List.of(
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
			GranularTransferLedger sandBucket = new GranularTransferLedger(List.of(
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
		var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				region.solverGeneration(), region.domain().captureAuthoritativeState(), List.of(),
				phaseChange, phaseChange ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0));
		region.adoptStepCandidate(candidate, CpuFluidSolverBackend.ID);
		checkClosed(region.domain(), "step before generation " + (region.checkpointGeneration() + 1L));
		persistence.checkpoint(region);
		restart(region, persistence, "published step");
	}

	private static void settlePendingVolumeWork(
			FluidRegion region, RegionRepositoryAdapter persistence
	) throws Exception {
		if (!region.domain().hasPendingPhaseVolumeWork()) return;
		var candidate = new CpuFluidSolverBackend().evaluate(FluidStepRequest.pendingPhaseSettlement(
				region.solverGeneration(), region.domain().captureAuthoritativeState()));
		region.adoptStepCandidate(candidate, CpuFluidSolverBackend.ID);
		persistence.checkpoint(region);
		restart(region, persistence, "pending phase-work settlement");
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
		return footprint(8, 8, 8);
	}

	private static FluidRegionFootprint footprint(int sizeX, int sizeY, int sizeZ) {
		return footprint(sizeX, sizeY, sizeZ, BlockPos.ZERO);
	}

	private static FluidRegionFootprint footprint(int sizeX, int sizeY, int sizeZ, BlockPos origin) {
		int blocksX = Math.max(1, sizeX / 2);
		int blocksY = Math.max(1, sizeY / 2);
		int blocksZ = Math.max(1, sizeZ / 2);
		Set<Long> interior = new HashSet<>();
		for (int z = 0; z < blocksZ; z++) for (int y = 0; y < blocksY; y++) for (int x = 0; x < blocksX; x++) {
			interior.add(origin.offset(x, y, z).asLong());
		}
		BlockPos controller = origin.offset(-1, 1, 1);
		BlockPos maximum = origin.offset(blocksX - 1, blocksY - 1, blocksZ - 1);
		Set<Long> requiredChunks = new HashSet<>();
		for (int chunkZ = origin.getZ() >> 4; chunkZ <= maximum.getZ() >> 4; chunkZ++) {
			for (int chunkX = origin.getX() >> 4; chunkX <= maximum.getX() >> 4; chunkX++) {
				requiredChunks.add(ChunkPos.pack(chunkX, chunkZ));
			}
		}
		return new FluidRegionFootprint(controller, origin, maximum, interior,
				Set.of(controller.asLong()), requiredChunks);
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
		double bucketVolume = 1.0 / SolverUnitScale.forCellSize(
				productionConfig().cellSize()).cubicMetersPerNormalizedVolume();
		return new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group(
				BuiltinFluidMaterials.WATER.id(), false, bucketVolume, mass,
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
		for (int seed : new int[]{4, 54, 103}) {
			Failure failure = runRandomizedNormalPlay(registries, seed, 60);
			if (failure != null) throw new AssertionError("granular pressure replay seed=" + seed
					+ ", action=" + failure.actionIndex() + ", trace=" + failure.trace(), failure.cause());
		}
		runScenario("sand and precipitate long settlement", registries, state -> {
			state.insertSand(); state.insertAcid(5); state.insertBase(5); state.steps(true, 120);
		});
		runScenario("reported acid-then-base precipitation", registries, state -> {
			for (int bucket = 0; bucket < 7; bucket++) { state.insertAcid(); state.steps(true, 2); }
			for (int bucket = 0; bucket < 7; bucket++) { state.insertBase(); state.steps(true, 2); }
			state.steps(true, 20);
		});
		runScenario("base-then-acid precipitation", registries, state -> {
			state.insertBase(7); state.insertAcid(7); state.steps(true, 20);
		});
		runScenario("cold acid-base precipitation", registries, state -> {
			state.insertAcid(); state.insertBase(); state.temperature(1.0); state.steps(true, 40);
		});
		runScenario("alternating acid-base precipitation", registries, state -> {
			for (int bucket = 0; bucket < 6; bucket++) {
				state.insertAcid(); state.insertBase(); state.step(true);
			}
		});
		runScenario("indicator acid-base precipitation", registries, state -> {
			for (int bucket = 0; bucket < 15; bucket++) { state.insertAcid(); state.steps(true, 2); }
			state.indicator(true);
			for (int bucket = 0; bucket < 15; bucket++) { state.insertBase(); state.steps(true, 2); }
			state.steps(true, 180);
		});
		runScenario("water-diluted acid-base precipitation", registries, state -> {
			state.insertAcid(4); state.insertWater(); state.insertBase(4); state.steps(true, 20);
		});
		runScenario("mercury and sand with acid-base precipitation", registries, state -> {
			state.insertAcid(4); state.insertMercury(); state.insertSand(); state.insertBase(4); state.steps(true, 60);
		});
		runScenario("cold sand-water coupling", registries, state -> {
			state.insertWater(); state.insertSand(); state.temperature(260.0); state.steps(true, 60);
		});
		runScenario("acid-base cold-restore before phase", registries, state -> {
			state.insertAcid(); state.insertBase(); state.temperature(1500.0); state.roundTrip(); state.steps(true, 60);
		});
		runScenario("acid-base precipitation then vent", registries, state -> {
			state.insertAcid(4); state.insertBase(4); state.steps(true, 12);
			state.vent(); state.roundTrip(); state.step(true);
		});
		runScenario("acid-base clear and reinsert", registries, state -> {
			state.insertAcid(4); state.insertBase(4); state.steps(true, 12); state.clear();
			state.vent(); state.insertAcid(2); state.insertBase(2); state.steps(true, 8);
		});
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
		runScenario("settled precipitate reacidification", registries, state -> {
			state.insertAcid(6); state.insertBase(6); state.steps(true, 80);
			state.insertAcid(4); state.steps(true, 80);
		});
		runScenario("settled precipitate additional base", registries, state -> {
			state.insertAcid(6); state.insertBase(6); state.steps(true, 80);
			state.insertBase(4); state.steps(true, 80);
		});
		runScenario("indicator toggled during precipitation", registries, state -> {
			state.insertAcid(6); state.indicator(true); state.insertBase(3); state.steps(true, 10);
			state.indicator(false); state.insertBase(3); state.steps(true, 80);
		});
		runScenario("precipitate vent and reacidify", registries, state -> {
			state.insertAcid(6); state.insertBase(6); state.steps(true, 40); state.settle();
			state.vent(); state.roundTrip(); state.insertAcid(2); state.steps(true, 40);
		});
		runScenario("precipitate clear vacuum rebuild", registries, state -> {
			state.insertAcid(6); state.insertBase(6); state.steps(true, 60); state.clear(); state.vent();
			state.insertAcid(4); state.indicator(true); state.insertBase(4); state.steps(true, 60);
		});
		runScenario("mercury and precipitate long settlement", registries, state -> {
			state.insertMercury(); state.insertAcid(5); state.insertBase(5); state.steps(true, 120);
		});
		runScenario("reactive liquid extraction after precipitation", registries, state -> {
			state.insertAcid(6); state.insertBase(6); state.steps(true, 40);
			state.extractChemical(); state.steps(true, 80);
		});
		runScenario("reactive bed repeated cold handoff", registries, state -> {
			state.insertAcid(6); state.insertBase(6);
			for (int cycle = 0; cycle < 40; cycle++) { state.step(true); state.roundTrip(); }
			state.settle(); state.vent(); state.roundTrip(); state.step(true);
		});
		runScenario("long alternating acid base pulses", registries, state -> {
			for (int pulse = 0; pulse < 10; pulse++) {
				state.insertAcid(); state.insertBase(); state.steps(true, 5);
			}
			state.steps(true, 100);
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

	static final class NormalPlayState {
		private final FuzzScene scene;
		private final FullCapabilityTestFixture.Registries registries;
		private final int injectX;
		private final int injectY;
		private final int injectZ;
		private final int water;
		private final int mercury;
		private final int oil;
		private final ChemicalTransferLedger acid;
		private final double acidEnergy;
		private final ChemicalTransferLedger base;
		private final double baseEnergy;
		private final GranularTransferLedger sand;
		private RegionRepositoryAdapter persistence;
		private FluidRegion region;
		private Path diskRoot;
		private int diskRoundTrips;
		private FluidRegionManager regionManager;
		private cc.ecisr.mmm.application.RegionApplicationService application;
		private FluidDomain domain;
		private long generation;
		long substepsThisAction;

		private void recordSubsteps(FluidStepCandidate candidate) {
			substepsThisAction = Math.addExact(substepsThisAction, candidate.stats().substeps());
			FuzzMetrics.recordEvents(scene.name(), candidate.stats());
		}

		NormalPlayState(FullCapabilityTestFixture.Registries registries) throws Exception {
			this(registries, BASELINE_SCENE);
		}

		NormalPlayState(FullCapabilityTestFixture.Registries registries, FuzzScene scene) throws Exception {
			this.scene = scene;
			this.registries = registries;
			this.injectX = scene.injectX();
			this.injectY = scene.injectY();
			this.injectZ = scene.injectZ();
			this.domain = new FluidDomain(scene.sizeX(), scene.sizeY(), scene.sizeZ(),
					productionConfig(), scene.capacity());
			scene.geometry().accept(domain);
			if (scene.sealed()) domain.sealDomainBoundary();
			FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
			FullCapabilityTestFixture.bindFull(domain, registries, 293.15,
					cc.ecisr.mmm.thermal.ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
			double[] worldBoundary = new double[scene.sizeX() * scene.sizeY() * scene.sizeZ() * 6];
			java.util.Arrays.fill(worldBoundary, Double.NaN);
			domain.replaceThermalBoundaryTemperatures(worldBoundary);
			water = registries.liquids().handle(BuiltinFluidMaterials.WATER.id());
			mercury = registries.liquids().handle(BuiltinFluidMaterials.MERCURY.id());
			oil = registries.liquids().handle(BuiltinFluidMaterials.OIL.id());
			acid = reagentBucket(registries, BuiltinChemicalDefinitions.HCL);
			acidEnergy = reagentEnergy(registries, acid);
			base = reagentBucket(registries, BuiltinChemicalDefinitions.NAOH);
			baseEnergy = reagentEnergy(registries, base);
			int sandHandle = registries.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND);
			double mass = registries.granular().material(sandHandle).referenceDensity();
			sand = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
					BuiltinGranularDefinitions.FINE_SAND, SolidVolume.SVQ_PER_BV, mass, null)));
			if (FUZZ_DISK) {
				diskRoot = Files.createTempDirectory("mmm-fuzz-disk");
				persistence = new RegionRepositoryAdapter(diskRoot, TestProfiles.full(), registries.chemical(),
						registries.granular(), registries.thermal(), registries.gas());
				region = new FluidRegion(java.util.UUID.randomUUID(), DIMENSION, scene.origin(), domain,
						scene.capacity(), footprint(scene.sizeX(), scene.sizeY(), scene.sizeZ(), scene.origin()));
				persistence.checkpoint(region);
				wireApplicationChannel();
			}
			roundTrip();
			java.util.function.Consumer<NormalPlayState> prefill = PREFILL.get(scene.name());
			if (prefill != null) prefill.accept(this);
			verifySleepLadder();
		}

		/**
		 * Exercises the hydrostatic and thermal sleep latches on the freshly built, at-rest region,
		 * before the random sequence starts. Latching sleep mid-sequence would suppress motion that is
		 * legitimately accumulating, so the ladder runs once here and always wakes both axes.
		 */
		private void verifySleepLadder() {
			if (region == null) return;
			boolean wasPaused = region.paused();
			region.setPaused(false);
			try {
				region.enableThermalActivity();
				if (!domainIsAtRest()) return;
				boolean entered = false;
				for (int step = 0; step < 260 && !entered; step++) {
					entered = region.lifecycle().observeCompletedStep(true, 200);
				}
				check(entered, "a region at rest did not enter hydrostatic sleep after 200 quiet steps"
						+ " (state=" + region.lifecycle().state() + ", availability="
						+ region.availabilityDescription() + ")");
				region.domain().commitHydrostaticSleep();
				check(region.lifecycle().state() == FluidRegionLifecycle.State.HYDROSTATIC_SLEEP,
						"hydrostatic sleep did not latch the lifecycle state");
				check(!region.canStep(), "hydrostatically sleeping region still reports steppable");
				region.wakeForActivity();
				check(region.lifecycle().state() != FluidRegionLifecycle.State.HYDROSTATIC_SLEEP
								&& region.canStep(), "activity wake did not restore the region to steppable");
				FuzzMetrics.cover("sleepWake(ladder)");
				ThermalActivity.Observation observation = restingThermalObservation();
				if (observation == null) return;
				boolean thermalEntered = false;
				for (int step = 0; step < 260 && !thermalEntered; step++) {
					thermalEntered = region.observeThermal(observation, 200);
				}
				check(thermalEntered, "an at-rest thermal axis did not enter sleep after 200 quiet observations");
				check(region.thermalActivity().state() == ThermalActivity.State.SLEEP,
						"thermal idle observation did not latch thermal sleep");
				region.wakeThermal(ThermalActivity.WakeReason.FINITE_DEVICE_EXCHANGE);
				check(region.thermalActivity().state() != ThermalActivity.State.SLEEP,
						"thermal wake reason did not wake the thermal axis");
			} finally {
				region.setPaused(wasPaused);
			}
		}

		long solverGeneration() { return region != null ? region.solverGeneration() : generation; }

		boolean pausedByOperator() { return region != null && region.paused(); }

		void pause() {
			region.setPaused(true);
			check(region.paused(), "operator pause did not stick");
			check(!region.canStep(), "paused region still reports a steppable solver");
		}

		void resume() {
			region.setPaused(false);
			check(!region.paused(), "operator resume did not stick");
		}

		void saveWhilePaused() {
			region.setPaused(true);
			try {
				persistence.checkpoint(region);
			} catch (java.io.IOException failure) {
				throw new IllegalStateException("save-while-paused checkpoint failed", failure);
			}
			check(region.paused(), "checkpoint cleared the operator pause intent");
		}

		void coldRestart(java.util.Random random) {
			boolean wasPaused = region.paused();
			boolean wasPhysics = region.entityPhysicsEnabled();
			boolean wasDebug = region.debugOverlays();
			long wasGeneration = region.checkpointGeneration();
			FluidDomainState beforeRestart = domain.captureAuthoritativeState();
			int nextInterval = 1 + random.nextInt(3);
			region.setEntityPhysicsEnabled(!wasPhysics);
			region.setDebugOverlays(!wasDebug);
			region.setStepIntervalTicks(nextInterval);
			FluidRegionLifecycle.State expectedState = expectedRunningState(nextInterval);
			java.util.UUID id = region.id();
			try {
				persistence.checkpointAndUnload(region);
			} catch (java.io.IOException failure) {
				throw new IllegalStateException("cold-restart checkpoint/unload failed", failure);
			}
			FluidRegionManager manager = new FluidRegionManager(
					new cc.ecisr.mmm.application.RegionCatalog.Limits(
							8, 1_000_000L, 1_000_000L, 10_000_000L, 10_000_000L));
			List<String> issues = persistence.loadMetadata(manager);
			FluidRegion fresh = manager.region(id);
			if (fresh == null) {
				throw new IllegalStateException("cold restart did not rediscover region " + id + "; issues=" + issues);
			}
			try {
				persistence.restore(fresh);
			} catch (java.io.IOException failure) {
				throw new IllegalStateException("cold-restart restore failed", failure);
			}
			check(fresh.solverLoaded(), "cold restart left the region unloaded");
			check(fresh.paused() == wasPaused,
					"cold restart changed operator pause intent " + wasPaused + " -> " + fresh.paused());
			check(fresh.lifecycle().state() == expectedState,
					"cold restart changed lifecycle state " + expectedState + " -> " + fresh.lifecycle().state());
			check(fresh.stepIntervalTicks() == nextInterval,
					"cold restart changed the step interval " + nextInterval + " -> " + fresh.stepIntervalTicks());
			check(fresh.entityPhysicsEnabled() == !wasPhysics, "cold restart changed entity physics intent");
			check(fresh.debugOverlays() == !wasDebug, "cold restart changed the debug-overlay intent");
			check(fresh.checkpointGeneration() > wasGeneration,
					"cold restart did not advance the committed checkpoint generation");
			region = fresh;
			domain = fresh.domain();
			wireApplicationChannel();
			FluidDomainStateAssertions.assertSameDurableState(beforeRestart, domain.captureAuthoritativeState(),
					"cold restart changed durable authoritative state");
			roundTrip();
		}

		private static FluidRegionLifecycle.State expectedRunningState(int interval) {
			return interval == 1 ? FluidRegionLifecycle.State.ACTIVE : FluidRegionLifecycle.State.REDUCED;
		}

		void adopt(FluidStepCandidate candidate) {
			if (region != null) {
				region.adoptStepCandidate(candidate, CpuFluidSolverBackend.ID);
				domain = region.domain();
			} else {
				domain = candidate.claimPreparedCpuDomain();
			}
		}

		void adoptThermal(FluidThermalStepCandidate candidate) {
			if (region != null) {
				region.adoptThermalStepCandidate(candidate, CpuFluidSolverBackend.ID);
				domain = region.domain();
			} else {
				domain = candidate.claimPreparedCpuDomain();
			}
		}

		void close() {
			if (diskRoot == null) return;
			try (var paths = Files.walk(diskRoot)) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			} catch (java.io.IOException ignored) {
				// Temporary fuzz checkpoints are best-effort cleaned up.
			}
		}

		int cellBudget() { return domain.sizeX() * domain.sizeY() * domain.sizeZ(); }

		int[] randomCorner(java.util.Random random) {
			return new int[]{
					upperCorner(random, domain.sizeX()),
					upperCorner(random, domain.sizeY()),
					upperCorner(random, domain.sizeZ())};
		}

		private static int upperCorner(java.util.Random random, int size) {
			int span = Math.min(3, size);
			return size - span + random.nextInt(span);
		}

		void temperature(double kelvin) {
			FuzzMetrics.note(scene.name(), "thermal");
			for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) {
				for (int x = 0; x < domain.sizeX(); x++) {
					if (domain.exactVolumeNear(x, y, z, 0, -1) > 0.0) {
						domain.setThermalTemperatureInCell(x, y, z, kelvin);
					}
				}
			}
		}

		void rampTemperature(double delta) {
			FuzzMetrics.note(scene.name(), "thermal");
			for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) {
				for (int x = 0; x < domain.sizeX(); x++) {
					if (domain.exactVolumeNear(x, y, z, 0, -1) > 0.0) {
						double current = domain.thermalTemperatureInCell(x, y, z);
						domain.setThermalTemperatureInCell(x, y, z,
								Math.min(20_000.0, Math.max(0.5, current + delta)));
					}
				}
			}
		}

		void insertWater() { insertLiquid(water); }
		void insertMercury() { insertLiquid(mercury); }
		void insertLiquid(int material) {
			settle();
			check(domain.tryInsertExactVolume(injectX, injectY, injectZ, 8.0F, 6, material, true).transferred(),
					"normal-play liquid insertion was rejected");
			roundTrip();
		}
		void insertAcid() {
			insertAcid(1);
		}
		void insertAcid(int buckets) {
			for (int bucket = 0; bucket < buckets; bucket++) insertChemical(acid, acidEnergy, "acid");
		}
		void insertBase() { insertBase(1); }
		void insertBase(int buckets) {
			for (int bucket = 0; bucket < buckets; bucket++) insertChemical(base, baseEnergy, "base");
		}
		void insertChemical(ChemicalTransferLedger ledger, double energy, String name) {
			settle();
			FuzzMetrics.note(scene.name(), "chemistry");
			check(domain.tryInsertExactChemical(injectX, injectY, injectZ, ledger, 6, true, energy).transferred(),
					"normal-play " + name + " insertion was rejected at generation " + generation
							+ ", liquid=" + domain.totalVolumeExact() + ", markers=" + domain.particleCount()
							+ ", solidSvq=" + domain.totalGranularSolidVolumeSvq());
			roundTrip();
		}
		void insertSand() {
			settle();
			check(domain.tryInsertExactGranular(injectX, injectY, injectZ, sand, 6, true).inserted(),
					"normal-play granular insertion was rejected");
			roundTrip();
		}
		boolean tryInsertLiquidAt(int x, int y, int z, int material) {
			settle();
			FluidDomainState before = snapshot();
			float volume = FUZZ_PARTIAL ? partialVolume(x, y, z) : 8.0F;
			if (!domain.tryInsertExactVolume(x, y, z, volume, 6, material, true).transferred()) {
				assertUnchanged(before, "liquid insertion at " + x + "," + y + "," + z);
				return false;
			}
			roundTrip();
			return true;
		}
		boolean tryInsertChemicalAt(int x, int y, int z, ChemicalTransferLedger ledger, double energy) {
			settle();
			FuzzMetrics.note(scene.name(), "chemistry");
			FluidDomainState before = snapshot();
			if (!domain.tryInsertExactChemical(x, y, z, ledger, 6, true, energy).transferred()) {
				assertUnchanged(before, "chemical insertion at " + x + "," + y + "," + z);
				return false;
			}
			roundTrip();
			return true;
		}
		boolean tryInsertSandAt(int x, int y, int z) {
			settle();
			FuzzMetrics.note(scene.name(), "granular");
			FluidDomainState before = snapshot();
			if (!domain.tryInsertExactGranular(x, y, z, sand, 6, true).inserted()) {
				assertUnchanged(before, "granular insertion at " + x + "," + y + "," + z);
				return false;
			}
			roundTrip();
			return true;
		}
		void indicator(boolean value) {
			var edit = domain.editIndicatorFloodFill(injectX, injectY, injectZ, value, cellBudget(), 100.0);
			check(edit.accepted(), "normal-play indicator edit was rejected: " + edit.failure()
					+ ", liquid=" + domain.totalVolumeExact() + ", markers=" + domain.particleCount()
					+ ", solidSvq=" + domain.totalGranularSolidVolumeSvq());
			roundTrip();
		}
		void extractWater() {
			settle();
			check(domain.tryExtractExactVolume(injectX, injectY, injectZ, 8.0F, 6).transferred(),
					"normal-play water extraction was rejected");
			roundTrip();
		}
		boolean tryExtractWaterAt(int[] target) {
			FluidDomainState before = snapshot();
			float volume = FUZZ_PARTIAL ? partialVolume(target[0], target[1], target[2]) : 8.0F;
			if (!domain.tryExtractExactVolume(target[0], target[1], target[2], volume, 6, water).transferred()) {
				assertUnchanged(before, "water extraction");
				return false;
			}
			roundTrip();
			return true;
		}
		boolean tryExtractChemicalAt(int[] target) {
			FluidDomainState before = snapshot();
			float volume = FUZZ_PARTIAL ? partialVolume(target[0], target[1], target[2]) : 8.0F;
			if (!domain.tryExtractExactChemical(target[0], target[1], target[2], volume, 6).transferred()) {
				assertUnchanged(before, "chemical extraction");
				return false;
			}
			roundTrip();
			return true;
		}
		boolean tryInsertMixtureAt(int x, int y, int z) {
			settle();
			FluidDomainState before = snapshot();
			double portion = FUZZ_PARTIAL ? partialVolume(x, y, z) : 2.0;
			List<FluidDomain.MaterialPortion> portions = List.of(
					new FluidDomain.MaterialPortion(water, portion, 2_000.0),
					new FluidDomain.MaterialPortion(mercury, 1.0, 13_600.0),
					new FluidDomain.MaterialPortion(oil, 1.0, 900.0));
			if (!domain.tryInsertExactMixture(x, y, z, portions, 6, true).transferred()) {
				assertUnchanged(before, "mixture insertion at " + x + "," + y + "," + z);
				return false;
			}
			roundTrip();
			return true;
		}
		boolean tryExtractMixtureAt(int[] target) {
			FluidDomainState before = snapshot();
			float volume = FUZZ_PARTIAL ? partialVolume(target[0], target[1], target[2]) : 4.0F;
			if (!domain.tryExtractExactMixture(target[0], target[1], target[2], volume, 7).transferred()) {
				assertUnchanged(before, "mixture extraction");
				return false;
			}
			roundTrip();
			return true;
		}

		FluidDomainState snapshot() { return domain.captureAuthoritativeState(); }

		/** Every rejection path must leave the authoritative inventory exactly as it found it. */
		void assertUnchanged(FluidDomainState before, String what) {
			FluidDomainStateAssertions.assertSameState(before, domain.captureAuthoritativeState(),
					"rejected " + what + " mutated authoritative state at generation " + generation);
		}

		/** Durable per-material inventory, independent of marker order or grid layout. */
		record AggregateInventory(long particles, double liquidVolume, double liquidMass, long solidSvq,
				double solidMass, double internalEnergy, long speciesQuanta,
				java.util.TreeMap<String, double[]> perMaterial) { }

		AggregateInventory aggregateInventory() {
			java.util.TreeMap<String, double[]> perMaterial = new java.util.TreeMap<>();
			for (int z = 0; z < domain.sizeZ(); z++) {
				for (int y = 0; y < domain.sizeY(); y++) {
					for (int x = 0; x < domain.sizeX(); x++) {
						FluidCellInventoryView view = domain.observeCellInventory(x, y, z);
						if (view == null) continue;
						for (FluidCellInventoryView.MaterialAmount amount : view.materials()) {
							double[] totals = perMaterial.computeIfAbsent(amount.materialId(), key -> new double[2]);
							totals[0] += amount.volumeCubicMetres();
							totals[1] += amount.massKilograms();
						}
					}
				}
			}
			return new AggregateInventory(domain.particleCount(), domain.totalVolumeExact(), domain.totalMass(),
					domain.totalGranularSolidVolumeSvq(), domain.totalGranularMass(),
					domain.totalInternalEnergy(), domain.totalChemicalSpeciesQuanta(), perMaterial);
		}

		private static boolean inventoryClose(double baseline, double actual) {
			return Math.abs(baseline - actual)
					<= 1.0E-9 * Math.max(1.0, Math.max(Math.abs(baseline), Math.abs(actual)));
		}

		/**
		 * Compares durable per-material inventory within solver roundoff; marker identity and ordering
		 * are free. Callers must ensure the compared operation is composition-symmetric: a round trip
		 * through a solute-bearing mixture removes carrier water together with its solutes and returns
		 * pure water, so mass need not return to baseline there.
		 */
		void checkInventoryMatches(AggregateInventory baseline, String context) {
			AggregateInventory actual = aggregateInventory();
			check(baseline.solidSvq() == actual.solidSvq(),
					context + ": solid inventory changed " + baseline.solidSvq() + " -> " + actual.solidSvq());
			check(inventoryClose(baseline.liquidVolume(), actual.liquidVolume())
							&& inventoryClose(baseline.liquidMass(), actual.liquidMass()),
					context + ": liquid ledger changed " + baseline.liquidVolume() + "/" + baseline.liquidMass()
							+ " -> " + actual.liquidVolume() + "/" + actual.liquidMass());
			check(baseline.perMaterial().keySet().equals(actual.perMaterial().keySet()),
					context + ": material set changed " + baseline.perMaterial().keySet()
							+ " -> " + actual.perMaterial().keySet());
			for (var entry : baseline.perMaterial().entrySet()) {
				double[] before = entry.getValue();
				double[] after = actual.perMaterial().get(entry.getKey());
				check(inventoryClose(before[0], after[0]) && inventoryClose(before[1], after[1]),
						context + ": " + entry.getKey() + " changed " + before[0] + "/" + before[1]
								+ " -> " + after[0] + "/" + after[1]);
			}
		}

		/**
		 * Closed-loop comparison: inventory plus the two conserved carriers that a symmetric
		 * extract-and-return must restore. Species quanta are integer counts, so the bound is tight.
		 */
		void checkClosedLoopMatches(AggregateInventory baseline, String context) {
			checkInventoryMatches(baseline, context);
			AggregateInventory actual = aggregateInventory();
			check(inventoryClose(baseline.internalEnergy(), actual.internalEnergy()),
					context + ": internal energy changed " + baseline.internalEnergy()
							+ " -> " + actual.internalEnergy());
			long allowed = Math.max(1L, (long) (Math.abs(baseline.speciesQuanta()) * 1.0E-9));
			check(Math.abs(baseline.speciesQuanta() - actual.speciesQuanta()) <= allowed,
					context + ": species quanta changed " + baseline.speciesQuanta() + " -> "
							+ actual.speciesQuanta() + " (allowed " + allowed + ")");
		}

		/** Requested material scope that matches the availability probe: -1 means every material. */
		private static final int ALL_MATERIALS = -1;

		/** Largest float strictly below {@code available}, so the request can never exceed availability. */
		private static float representableBelow(double available) {
			float requested = (float) available;
			while ((double) requested >= available) requested = Math.nextDown(requested);
			return requested;
		}

		/**
		 * Drains toward the tail at the fullest cell. {@code exactVolumeNear} is only an upper bound:
		 * it ignores the per-marker chemical/thermal split resolution that the planner enforces, so the
		 * observed amount cannot be requested directly. Descend until the planner accepts a rung, and
		 * fail only if every rung down to the resolution floor is refused while liquid is observable.
		 */
		String extractTail() {
			settle();
			int[] target = maximumNearbyLiquidCell(6);
			double observed = domain.exactVolumeNear(target[0], target[1], target[2], 6, ALL_MATERIALS);
			if (observed <= 0.0) return "skip(noLiquid)";
			float floor = MINIMUM_VOLUMES[0];
			for (float request = representableBelow(observed); request >= floor; request *= 0.5F) {
				FluidDomainState before = snapshot();
				var transfer = domain.tryExtractExactVolume(target[0], target[1], target[2], request, 6,
						ALL_MATERIALS);
				if (!transfer.transferred()) {
					assertUnchanged(before, "tail extraction");
					continue;
				}
				check(transfer.volume() > 0.0
								&& transfer.volume() <= (double) request + Math.ulp((double) request),
						"tail extraction reported volume " + transfer.volume()
								+ " above its request of " + request);
				roundTrip();
				return "extractTail(" + request + ")";
			}
			throw new AssertionError("every representable tail request down to " + floor + " m3 was refused with "
					+ observed + " m3 observable at generation " + generation);
		}

		/**
		 * Requests more than the whole domain holds, which no reachable settlement can cover. Acceptance
		 * would be a real accounting failure; rejection must also leave the inventory untouched.
		 */
		String extractOverCapacity() {
			settle();
			double total = domain.totalVolumeExact();
			float requested = (float) (total + Math.max(1.0, total * 0.01));
			if (!Float.isFinite(requested)) return "skip(overflow)";
			int[] target = maximumNearbyLiquidCell(6);
			FluidDomainState before = snapshot();
			if (domain.tryExtractExactVolume(target[0], target[1], target[2], requested, 6, ALL_MATERIALS)
					.transferred()) {
				throw new AssertionError("extraction of " + requested + " m3 succeeded with only " + total
						+ " m3 in the whole domain at generation " + generation);
			}
			assertUnchanged(before, "over-capacity extraction");
			return "extractOverCapacity";
		}

		/**
		 * Probes a sub-bucket or minimal-remainder request. Below the reachable split resolution a
		 * refusal is correct, so acceptance is not required; both outcomes are still asserted: a
		 * refusal must not mutate state and a settlement must not exceed its request.
		 */
		String extractSubBucket(int variant) {
			settle();
			int[] target = maximumNearbyLiquidCell(6);
			double observed = domain.exactVolumeNear(target[0], target[1], target[2], 6, ALL_MATERIALS);
			if (observed <= 0.0) return "skip(noLiquid)";
			float requested = MINIMUM_VOLUMES[variant % MINIMUM_VOLUMES.length];
			FluidDomainState before = snapshot();
			var transfer = domain.tryExtractExactVolume(target[0], target[1], target[2], requested, 6,
					ALL_MATERIALS);
			if (!transfer.transferred()) {
				assertUnchanged(before, "sub-bucket extraction");
				return "skip(subBucketRejected)";
			}
			check(transfer.volume() > 0.0
							&& transfer.volume() <= (double) requested + Math.ulp((double) requested),
					"sub-bucket extraction reported volume " + transfer.volume()
							+ " above its request of " + requested);
			roundTrip();
			return "extractSubBucket(" + requested + ")";
		}

		/**
		 * Extracts locally then pours the returned amount straight back; per-material inventory, species
		 * quanta and internal energy must all return to baseline. Both the purity precondition and the
		 * extraction are limited to the same small radius, so the loop runs on a local aqueous pocket
		 * rather than requiring the whole domain to be water.
		 */
		String extractThenRestore() {
			settle();
			int radius = 3;
			int[] target = maximumNearbyLiquidCell(radius, water);
			double observed = domain.exactVolumeNear(target[0], target[1], target[2], radius, water);
			if (observed <= 0.0) return "skip(noCanonicalWaterLoop)";
			double neighbourhood = domain.exactVolumeNear(target[0], target[1], target[2], radius, ALL_MATERIALS);
			if (Math.abs(neighbourhood - observed) > 1.0E-9 * Math.max(1.0, neighbourhood)) {
				return "skip(notPureWaterNeighbourhood)";
			}
			float request = representableBelow(Math.min(2.0, observed));
			AggregateInventory baseline = aggregateInventory();
			FluidDomainState beforeExtract = snapshot();
			var extracted = domain.tryExtractExactVolume(target[0], target[1], target[2], request, radius, water);
			if (!extracted.transferred()) {
				assertUnchanged(beforeExtract, "closed-loop extraction");
				return "skip(loopExtractRejected)";
			}
			check(extracted.volume() > 0.0, "closed-loop extraction reported no volume");
			float returned = (float) extracted.volume();
			FluidDomainState beforeInsert = snapshot();
			if (!domain.tryInsertExactVolume(target[0], target[1], target[2], returned, radius, water, true)
					.transferred()) {
				assertUnchanged(beforeInsert, "closed-loop re-insertion");
				return "skip(loopInsertRejected)";
			}
			roundTrip();
			checkClosedLoopMatches(baseline, "closed-loop extract/pour at generation " + generation);
			return "extractThenRestore";
		}

		/** Minimal-volume insertion. An empty inject cell is a clear precondition, so rejection fails. */
		String insertMinimalVolume(int variant) {
			settle();
			float volume = MINIMUM_VOLUMES[variant % MINIMUM_VOLUMES.length];
			boolean preconditionEmpty = domain.exactVolumeNear(injectX, injectY, injectZ, 0, -1) == 0.0
					&& domain.granularSolidVolumeSvq(injectX, injectY, injectZ) == 0L;
			FluidDomainState before = snapshot();
			if (!domain.tryInsertExactVolume(injectX, injectY, injectZ, volume, 6, water, true).transferred()) {
				if (preconditionEmpty) {
					throw new AssertionError("empty inject cell rejected a " + volume
							+ " m3 insertion at generation " + generation);
				}
				assertUnchanged(before, "minimal-volume insertion");
				return "skip(minimalRejected)";
			}
			roundTrip();
			return "insertMinimalVolume(" + volume + ")";
		}

		/** Toggles one interior collision cell at runtime, the way a placed or broken block would. */
		String toggleSolid(java.util.Random random) {
			settle();
			int x = random.nextInt(domain.sizeX());
			int y = random.nextInt(domain.sizeY());
			int z = random.nextInt(domain.sizeZ());
			if (x == injectX && y == injectY && z == injectZ) return "skip(solidInjectCell)";
			boolean next = !domain.isSolid(x, y, z);
			float displaced = domain.setSolid(x, y, z, next);
			roundTrip();
			return (next ? "setSolid(" : "clearSolid(") + displaced + ")";
		}

		/** Drives one face of the world-derived constant-temperature boundary field up or down. */
		String editBoundarySource(java.util.Random random) {
			double[] field = domain.captureAuthoritativeState().copyThermalBoundaryTemperaturesK();
			int cells = domain.sizeX() * domain.sizeY() * domain.sizeZ();
			if (field == null || field.length != cells * 6) return "skip(noThermalBoundary)";
			double[] edited = field.clone();
			int face = random.nextInt(edited.length);
			edited[face] = random.nextBoolean()
					? RANDOM_TEMPERATURES[random.nextInt(RANDOM_TEMPERATURES.length)]
					: Double.NaN;
			if (!domain.replaceThermalBoundaryTemperatures(edited)) return "skip(boundaryUnchanged)";
			roundTrip();
			return "editBoundarySource(" + edited[face] + ")";
		}

		/** Rebuilds the loader-neutral application channel around the current live region. */
		private void wireApplicationChannel() {
			if (region == null) return;
			regionManager = new FluidRegionManager(new cc.ecisr.mmm.application.RegionCatalog.Limits(
					8, 1_000_000L, 1_000_000L, 10_000_000L, 10_000_000L));
			regionManager.addAndSelect(region);
			region.enableThermalActivity();
			application = new cc.ecisr.mmm.application.RegionApplicationService(
					new CpuFluidSolverBackend(), new FluidRegionRepository(regionManager),
					(regionId, expectedGeneration) -> {
						throw new UnsupportedOperationException(
								"fuzz application channel does not use the checkpoint port");
					});
		}

		/** Steps through the real application service and proves the generation token is single-use. */
		String applicationStep(java.util.Random random) {
			if (application == null) return "skip(noApplicationChannel)";
			if (!region.canStep()) return "skip(notSteppable)";
			boolean phase = random.nextBoolean();
			long baseGeneration = region.solverGeneration();
			MotionStepResult accepted = application.stepMotion(new MotionStepCommand(region.id(), baseGeneration,
					List.of(), phase, phase ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0, false));
			if (!accepted.accepted()) {
				throw new AssertionError("application rejected a fresh motion step: " + accepted.status());
			}
			FuzzMetrics.recordEvents(scene.name(), accepted.stats());
			MotionStepResult stale = application.stepMotion(new MotionStepCommand(region.id(), baseGeneration,
					List.of(), phase, phase ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0, false));
			if (stale.status() != RegionStepStatus.STALE_GENERATION) {
				throw new AssertionError("application did not reject a spent generation: " + stale.status());
			}
			domain = region.domain();
			generation++;
			roundTrip();
			return "applicationStep(phase=" + phase + ")";
		}

		/**
		 * Drives hydrostatic and thermal sleep to their latched states, then wakes each axis. Runs only
		 * when the domain is empirically at rest, so latching sleep cannot suppress a moving instability
		 * and {@code commitHydrostaticSleep} merely confirms momentum that is already zero.
		 */
		String sleepWake(java.util.Random random) {
			if (region == null) return "skip(noLifecycleChannel)";
			if (region.paused()) return "skip(paused)";
			if (!domainIsAtRest()) return "skip(domainNotAtRest)";
			region.enableThermalActivity();
			boolean enteredHydrostatic = false;
			for (int step = 0; step < 260 && !enteredHydrostatic; step++) {
				if (!domainIsAtRest()) return "skip(domainStartedMoving)";
				enteredHydrostatic = region.lifecycle().observeCompletedStep(true, 200);
			}
			if (!enteredHydrostatic) return "skip(noHydrostaticSleep)";
			check(domain.maximumParticleSpeed() <= QUIET_MARKER_SPEED,
					"hydrostatic sleep was latched while markers were still moving");
			region.domain().commitHydrostaticSleep();
			check(region.lifecycle().state() == FluidRegionLifecycle.State.HYDROSTATIC_SLEEP,
					"hydrostatic sleep did not latch the lifecycle state");
			check(!region.canStep(), "hydrostatically sleeping region still reports steppable");
			region.wakeForActivity();
			check(region.lifecycle().state() != FluidRegionLifecycle.State.HYDROSTATIC_SLEEP,
					"activity wake left the region hydrostatically asleep");
			if (region.thermalActivity() != null) {
				check(region.thermalActivity().state() != ThermalActivity.State.SLEEP,
						"mechanical wake did not wake the thermal axis");
			}
			boolean enteredThermal = false;
			for (int step = 0; step < 260 && !enteredThermal; step++) {
				ThermalActivity.Observation observation = restingThermalObservation();
				if (observation == null) return "skip(thermalNotAtRest)";
				enteredThermal = region.observeThermal(observation, 200);
			}
			if (!enteredThermal) return "skip(noThermalSleep)";
			check(region.thermalActivity().state() == ThermalActivity.State.SLEEP,
					"thermal idle observation did not latch thermal sleep");
			region.wakeThermal(ThermalActivity.WakeReason.FINITE_DEVICE_EXCHANGE);
			check(region.thermalActivity().state() != ThermalActivity.State.SLEEP,
					"thermal wake reason did not wake the thermal axis");
			roundTrip();
			return "sleepWake(hydrostatic+thermal)";
		}

		/** Empirical rest: no meaningful marker motion, no pending phase lien, sealed gas audit intact. */
		private boolean domainIsAtRest() {
			return domain.maximumParticleSpeed() <= QUIET_MARKER_SPEED
					&& !domain.hasPendingPhaseVolumeWork()
					&& (!scene.sealed() || domain.gasBoundary().auditClosed());
		}

		/**
		 * The thermal axis observation actually implied by the current domain. Returns null when the
		 * domain is not measurably quiet, so sleep is never latched over real activity.
		 */
		private ThermalActivity.Observation restingThermalObservation() {
			var transport = domain.lastThermalTransportStats();
			if (transport == null) return null;
			if (transport.maximumAbsoluteTransferEu() != 0.0
					|| !transport.reservoirExchange().equals(cc.ecisr.mmm.units.SignedDyadic.ZERO)) return null;
			if (domain.chemicalParcels() != null && domain.chemicalParcels().hasDirtyCells()) return null;
			if (domain.hasPendingPhaseVolumeWork() || !domainIsAtRest()) return null;
			return new ThermalActivity.Observation(transport, false, false, false, false, false, false, true);
		}

		private static final double QUIET_MARKER_SPEED = 1.0E-6;

		/**
		 * Injects and then clears an availability block. Runs only when the region was steppable
		 * beforehand, so clearing the injected block must restore a steppable state and an accepted
		 * step; a region that was already paused or blocked is left untouched.
		 */
		String blockAndRetry(java.util.Random random) {
			if (region == null) return "skip(noLifecycleChannel)";
			if (!region.canStep()) return "skip(notSteppableBefore)";
			RegionAvailabilityReason reason = random.nextBoolean()
					? RegionAvailabilityReason.NUMERICAL_PAUSE : RegionAvailabilityReason.PERFORMANCE_PAUSE;
			region.setAvailabilityFailure(reason, "fuzz injected availability block");
			check(!region.canStep(), "region with an injected block still reports steppable");
			check(region.availabilityReason() == reason,
					"region lost the injected availability reason " + reason);
			check(region.availabilityDescription().startsWith(reason.name()),
					"region availability description ignored the injected block");
			if (reason == RegionAvailabilityReason.PERFORMANCE_PAUSE) region.clearPerformanceBlock();
			else region.setAvailabilityFailure(null);
			check(region.availabilityFailure() == null, "region kept a cleared availability block");
			check(region.canStep(), "clearing the injected " + reason + " block did not restore steppability");
			if (application == null) return "blockAndRetry(" + reason + ",clearedNoChannel)";
			long baseGeneration = region.solverGeneration();
			MotionStepResult retried = application.stepMotion(new MotionStepCommand(
					region.id(), baseGeneration, List.of(), false, 0, false));
			if (!retried.accepted()) {
				throw new AssertionError("region did not resume stepping after an injected "
						+ reason + " block: " + retried.status());
			}
			FuzzMetrics.recordEvents(scene.name(), retried.stats());
			domain = region.domain();
			generation++;
			roundTrip();
			return "blockAndRetry(" + reason + ")";
		}

		/** Drives the loader-neutral container-transfer chain: private candidate, staged HEAD, adoption. */
		boolean containerTransfer(java.util.Random random) {
			settle();
			long baseGeneration = region.checkpointGeneration();
			FluidDomainState liveBefore = region.domain().captureAuthoritativeState();
			FluidDomain candidateDomain = FluidDomain.restoreAuthoritativeState(liveBefore);
			FluidDomainState candidateBefore = candidateDomain.captureAuthoritativeState();
			int x = random.nextInt(candidateDomain.sizeX());
			int y = random.nextInt(candidateDomain.sizeY());
			int z = random.nextInt(candidateDomain.sizeZ());
			float volume = FUZZ_PARTIAL ? partialVolume(x, y, z) : 8.0F;
			boolean transferred = random.nextBoolean()
					? candidateDomain.tryInsertExactVolume(x, y, z, volume, 6, water, true).transferred()
					: candidateDomain.tryExtractExactVolume(x, y, z, volume, 6, water).transferred();
			if (!transferred) {
				FluidDomainStateAssertions.assertSameState(candidateBefore, candidateDomain.captureAuthoritativeState(),
						"rejected container transfer mutated the private candidate");
				return false;
			}
			FluidDomainState candidateAfter = candidateDomain.captureAuthoritativeState();
			double targetVolume = candidateDomain.totalVolumeExact();
			long targetGeneration = baseGeneration + 1L;
			long published;
			try {
				RegionRepositoryAdapter.StagedCheckpoint staged = persistence.stageCandidateCheckpoint(
						region, candidateDomain, targetGeneration, region.paused());
				published = persistence.commitStagedLifecycleCheckpoint(staged);
			} catch (java.io.IOException failure) {
				throw new IllegalStateException("container transfer staging failed", failure);
			}
			check(published == targetGeneration, "container transfer published generation " + published
					+ " instead of " + targetGeneration);
			check(region.checkpointGeneration() == baseGeneration,
					"container transfer mutated the live region before adoption");
			FluidDomainStateAssertions.assertSameState(liveBefore, region.domain().captureAuthoritativeState(),
					"container transfer mutated the live region before adoption");
			try {
				persistence.adoptCommittedContainerCheckpoint(region, baseGeneration, published);
			} catch (java.io.IOException failure) {
				throw new IllegalStateException("container transfer adoption failed", failure);
			}
			domain = region.domain();
			check(region.checkpointGeneration() == published, "container transfer did not adopt the published HEAD");
			FluidDomainStateAssertions.assertSameDurableState(candidateAfter, domain.captureAuthoritativeState(),
					"container transfer adoption diverged from the committed candidate");
			check(domain.totalVolumeExact() == targetVolume,
					"container transfer adopted volume " + domain.totalVolumeExact() + " instead of " + targetVolume);
			roundTrip();
			return true;
		}
		void extractChemical() {
			settle();
			int[] target = maximumNearbyLiquidCell(6);
			double available = domain.exactVolumeNear(target[0], target[1], target[2], 6, -1);
			check(domain.tryExtractExactChemical(target[0], target[1], target[2], 8.0F, 6).transferred(),
					"normal-play chemical extraction was rejected at generation " + generation
							+ ", liquid=" + domain.totalVolumeExact() + ", markers=" + domain.particleCount()
							+ ", pending=" + domain.hasPendingPhaseVolumeWork() + ", target="
							+ java.util.Arrays.toString(target) + ", nearby=" + available);
			roundTrip();
		}
		int[] maximumNearbyLiquidCell(int radius) { return maximumNearbyLiquidCell(radius, -1); }
		int[] maximumNearbyLiquidCell(int radius, int materialHandle) {
			int[] selected = new int[3];
			double maximum = -1.0;
			for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) {
				for (int x = 0; x < domain.sizeX(); x++) {
					double available = domain.exactVolumeNear(x, y, z, radius, materialHandle);
					if (available > maximum) {
						maximum = available; selected[0] = x; selected[1] = y; selected[2] = z;
					}
				}
			}
			return selected;
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
		void settle() {
			if (!domain.hasPendingPhaseVolumeWork()) return;
			try {
				var candidate = new CpuFluidSolverBackend().evaluate(
						FluidStepRequest.pendingPhaseSettlement(solverGeneration(), domain.captureAuthoritativeState()));
				check(candidate.stats().thermalAxis().granularPhase().transfers() == 0
							&& candidate.stats().chemistryEvaluations() == 0,
						"pending phase-work settlement advanced thermochemistry");
				recordSubsteps(candidate);
				adopt(candidate);
				check(!domain.hasPendingPhaseVolumeWork(),
						"pending phase-work settlement retained the prior lien");
			} catch (RuntimeException failure) {
				throw new IllegalStateException("normal-play pending phase-work settlement failed at generation "
						+ generation, failure);
			}
			generation++;
			roundTrip();
		}
		void steps(boolean phase, int count) { for (int step = 0; step < count; step++) step(phase); }
		void step(boolean phase) {
			try {
				var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
						solverGeneration(), domain.captureAuthoritativeState(), List.of(), phase,
						phase ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0));
				recordSubsteps(candidate);
				adopt(candidate);
			} catch (RuntimeException failure) {
				throw new IllegalStateException("normal-play logical step failed at generation " + generation
						+ ", liquid=" + domain.totalVolumeExact() + ", markers=" + domain.particleCount()
						+ ", solidSvq=" + domain.totalGranularSolidVolumeSvq(), failure);
			}
			generation++;
			roundTrip();
		}
		void thermalStep(boolean phase) {
			FuzzMetrics.note(scene.name(), "thermal");
			try {
				var candidate = new CpuFluidSolverBackend().evaluateThermal(new FluidThermalStepRequest(
						solverGeneration(), domain.captureAuthoritativeState(), phase,
						phase ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0));
				FuzzMetrics.recordEvents(scene.name(), candidate.stats());
				adoptThermal(candidate);
			} catch (RuntimeException failure) {
				throw new IllegalStateException("normal-play thermal-only step failed at generation " + generation
						+ ", liquid=" + domain.totalVolumeExact() + ", markers=" + domain.particleCount()
						+ ", solidSvq=" + domain.totalGranularSolidVolumeSvq(), failure);
			}
			generation++;
			roundTrip();
		}
		void stepWithMovingBodies(java.util.Random random, int count) {
			double half = domain.sizeX() * 0.5;
			List<MovingBodyFluidCoupling.Request> bodies = new java.util.ArrayList<>(count);
			for (int index = 0; index < count; index++) {
				double displaced = 0.001 + random.nextDouble() * 0.05;
				bodies.add(new MovingBodyFluidCoupling.Request(
						(float) (0.25 + random.nextDouble() * (half - 0.5)),
						(float) (0.25 + random.nextDouble() * (half - 0.5)),
						(float) (0.25 + random.nextDouble() * (half - 0.5)),
						(random.nextDouble() - 0.5) * 20.0, (random.nextDouble() - 0.5) * 20.0,
						(random.nextDouble() - 0.5) * 20.0, displaced));
			}
			try {
				var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
						solverGeneration(), domain.captureAuthoritativeState(), bodies, true,
						ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
				recordSubsteps(candidate);
				adopt(candidate);
			} catch (RuntimeException failure) {
				throw new IllegalStateException("normal-play moving-body step failed at generation " + generation
						+ ", liquid=" + domain.totalVolumeExact() + ", markers=" + domain.particleCount()
						+ ", solidSvq=" + domain.totalGranularSolidVolumeSvq() + ", bodies=" + count, failure);
			}
			generation++;
			roundTrip();
		}
		void roundTrip() {
			if (scene.sealed()) {
				check(domain.gasBoundary().auditClosed(),
						"normal-play handoff generation " + generation + " left the gas audit open");
				check(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
						"normal-play handoff generation " + generation + " left the thermal audit open");
			}
			if (region != null) {
				if (++diskRoundTrips % FUZZ_DISK_ROUND_TRIP_INTERVAL == 0) {
					boolean wasPaused = region.paused();
					FluidRegionLifecycle.State wasState = region.lifecycle().state();
					int wasInterval = region.stepIntervalTicks();
					FluidDomainState beforeHandoff = domain.captureAuthoritativeState();
					try {
						persistence.checkpointAndUnload(region);
						persistence.restore(region);
					} catch (java.io.IOException failure) {
						throw new IllegalStateException("normal-play disk checkpoint failed at generation "
								+ generation, failure);
					}
					check(region.paused() == wasPaused,
							"disk checkpoint changed the operator pause intent " + wasPaused + " -> " + region.paused());
					check(region.lifecycle().state() == wasState,
							"disk checkpoint changed lifecycle state " + wasState + " -> " + region.lifecycle().state());
					check(region.stepIntervalTicks() == wasInterval,
							"disk checkpoint changed the step interval " + wasInterval + " -> " + region.stepIntervalTicks());
					domain = region.domain();
					FluidDomainStateAssertions.assertSameDurableState(beforeHandoff, domain.captureAuthoritativeState(),
							"disk checkpoint/restore changed durable authoritative state at generation " + generation);
				}
			} else {
				FluidDomainState beforeRoundTrip = domain.captureAuthoritativeState();
				domain = FluidDomain.restoreAuthoritativeState(beforeRoundTrip);
				FluidDomainStateAssertions.assertSameState(beforeRoundTrip, domain.captureAuthoritativeState(),
						"in-memory capture/restore changed authoritative state at generation " + generation);
			}
			if (scene.sealed()) {
				check(domain.gasBoundary().auditClosed(),
						"normal-play restore generation " + generation + " left the gas audit open");
				check(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
						"normal-play restore generation " + generation + " left the thermal audit open");
			}
		}
	}

	/**
	 * Mutation-style validation of the harness itself: each judgement is fed a state that must trip it,
	 * so a green run means the checks can fire rather than merely that no sample threw.
	 */
	static void runHarnessSelfCheck(FullCapabilityTestFixture.Registries registries) throws Exception {
		NormalPlayState state = new NormalPlayState(registries);
		try {
			FluidDomainState before = state.snapshot();
			if (!state.domain.tryInsertExactVolume(state.injectX, state.injectY, state.injectZ,
					1.0F, 6, state.water, true).transferred()) {
				throw new IllegalStateException("selfcheck could not stage a mutation");
			}
			boolean guardFired = false;
			try {
				state.assertUnchanged(before, "selfcheck");
			} catch (AssertionError expected) {
				guardFired = true;
			}
			check(guardFired, "selfcheck: rejection guard did not detect a mutated state");

			NormalPlayState.AggregateInventory baseline = state.aggregateInventory();
			if (!state.domain.tryInsertExactVolume(state.injectX, state.injectY, state.injectZ,
					1.0F, 6, state.water, true).transferred()) {
				throw new IllegalStateException("selfcheck could not stage a second mutation");
			}
			boolean loopFired = false;
			try {
				state.checkClosedLoopMatches(baseline, "selfcheck");
			} catch (AssertionError expected) {
				loopFired = true;
			}
			check(loopFired, "selfcheck: closed-loop comparison missed an inventory change");

			FuzzMetrics.note("selfcheck", "thermal");
			check(FuzzMetrics.familyGapExists("selfcheck", "thermal"),
					"selfcheck: coverage gap detection missed an exercised family with no events");
			System.out.println("harness selfcheck PASS (rejection guard, closed-loop comparison, coverage gap)");
		} finally {
			state.close();
		}
	}

	/** Seeded normal-play fuzzing entry point used to surface forced simulation interruptions. */
	public static void main(String[] args) throws Exception {
		for (String arg : args) if (arg.equals("debug")) FUZZ_DEBUG = true;
		for (String arg : args) if (arg.equals("picks")) FUZZ_PICKS = true;
		int[] explicitSeeds = args.length > 0 && args[0].equals("reported")
				? new int[]{97, 119, 135, 152, 267, 268, 282, 323, 327, 441, 479, 505, 506, 691,
						702, 807, 876, 880, 1259, 1393, 1433, 1505, 1807, 1864, 1870, 2056, 2075, 3034}
				: args.length > 0 && args[0].contains(":")
				? java.util.Arrays.stream(args[0].split(":")).mapToInt(Integer::parseInt).toArray() : null;
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		if (args.length > 0 && args[0].equals("selfcheck")) {
			runHarnessSelfCheck(registries);
			return;
		}
		if (args.length > 0 && args[0].equals("replay")) {
			FUZZ_PROGRESS = true;
			int base = parseFuzzMode(args, 1);
			int scene = Integer.parseInt(args[base]);
			int seed = Integer.parseInt(args[base + 1]);
			Failure failure = runExpandedRandomPlay(registries, FUZZ_SCENES.get(scene), seed,
					args.length > base + 2 ? Integer.parseInt(args[base + 2]) : 80);
			if (failure != null) throw new AssertionError("scene=" + scene + ", seed=" + seed
					+ ", trace=" + failure.trace(), failure.cause());
			System.out.println("replay scene=" + scene + " seed=" + seed + " PASS");
			return;
		}
		if (explicitSeeds != null) {
			runExplicitSeeds(registries, explicitSeeds, args.length > 1 ? Integer.parseInt(args[1]) : 80);
			return;
		}
		if (args.length > 0 && args[0].equals("minimize")) {
			FUZZ_PROGRESS = false;
			int base = parseFuzzMode(args, 1);
			int sceneIndex = args.length > base ? Integer.parseInt(args[base]) : 1;
			int seed = args.length > base + 1 ? Integer.parseInt(args[base + 1]) : 212;
			int actions = args.length > base + 2 ? Integer.parseInt(args[base + 2]) : 80;
			FuzzMinimizer.minimize(registries, FUZZ_SCENES.get(sceneIndex), seed, actions);
			return;
		}
		if (args.length > 0 && (args[0].equals("disk") || args[0].equals("partial")
				|| args[0].equals("partialdisk"))) {
			FUZZ_DISK = args[0].contains("disk");
			FUZZ_PARTIAL = args[0].contains("partial");
			int seedsPerScene = args.length > 1 ? Integer.parseInt(args[1]) : 4;
			int actions = args.length > 2 ? Integer.parseInt(args[2]) : 80;
			int threads = args.length > 3 ? Math.max(1, Integer.parseInt(args[3]))
					: Math.max(1, Runtime.getRuntime().availableProcessors());
			int sceneFrom = args.length > 4 ? Integer.parseInt(args[4]) : 0;
			int sceneTo = args.length > 5 ? Integer.parseInt(args[5]) : FUZZ_SCENES.size();
			int seedOffset = args.length > 6 ? Integer.parseInt(args[6]) : 0;
			runSceneFuzz(registries, seedsPerScene, actions, threads, sceneFrom, sceneTo, seedOffset);
			return;
		}
		int seedsPerScene = args.length > 0 ? Integer.parseInt(args[0]) : 4;
		int actions = args.length > 1 ? Integer.parseInt(args[1]) : 80;
		FUZZ_DISABLE_GRANULAR = args.length > 2 && Boolean.parseBoolean(args[2]);
		int threads = args.length > 3 ? Math.max(1, Integer.parseInt(args[3]))
				: Math.max(1, Runtime.getRuntime().availableProcessors());
		int sceneFrom = args.length > 4 ? Integer.parseInt(args[4]) : 0;
		int sceneTo = args.length > 5 ? Integer.parseInt(args[5]) : FUZZ_SCENES.size();
		int seedOffset = args.length > 6 ? Integer.parseInt(args[6]) : 0;
		runSceneFuzz(registries, seedsPerScene, actions, threads, sceneFrom, sceneTo, seedOffset);
	}

	private static int parseFuzzMode(String[] args, int index) {
		if (index < args.length && (args[index].equals("disk") || args[index].equals("partial")
				|| args[index].equals("partialdisk"))) {
			FUZZ_DISK = args[index].contains("disk");
			FUZZ_PARTIAL = args[index].contains("partial");
			return index + 1;
		}
		return index;
	}

	private static void runExplicitSeeds(
			FullCapabilityTestFixture.Registries registries, int[] seeds, int actions
	) throws Exception {
		FUZZ_PROGRESS = true;
		java.util.List<Integer> failedSeeds = new java.util.ArrayList<>();
		for (int seed : seeds) {
			Failure failure = runExpandedRandomPlay(registries, seed, actions);
			System.out.println("seed=" + seed + (failure == null ? " PASS" : " FAIL"));
			if (failure != null) {
				failedSeeds.add(seed);
				failure.cause().printStackTrace(System.out);
			}
		}
		check(failedSeeds.isEmpty(), "explicit-seed fuzz found " + failedSeeds.size() + "/" + seeds.length
				+ " failing seeds: " + failedSeeds);
	}


	private record SceneJob(FuzzScene scene, int seed) { }

	private record SceneFailure(SceneJob job, Failure failure) { }

	private static void runSceneFuzz(
			FullCapabilityTestFixture.Registries registries, int seedsPerScene, int actions, int threads,
			int sceneFrom, int sceneTo, int seedOffset
	) throws Exception {
		FUZZ_PROGRESS = false;
		int lastScene = Math.min(sceneTo, FUZZ_SCENES.size());
		List<SceneJob> jobs = new java.util.ArrayList<>();
		for (int sceneIndex = Math.max(0, sceneFrom); sceneIndex < lastScene; sceneIndex++) {
			FuzzScene scene = FUZZ_SCENES.get(sceneIndex);
			if (FUZZ_DISK && !scene.sealed()) continue;
			for (int ordinal = 0; ordinal < seedsPerScene; ordinal++) {
				jobs.add(new SceneJob(scene, scene.seedBase() + seedOffset + ordinal));
			}
		}
		System.out.println("running " + jobs.size() + " jobs (scenes [" + sceneFrom + "," + lastScene + ") x "
				+ seedsPerScene + " seeds, " + actions + " actions) on " + threads + " threads");
		SceneFailure[] results = new SceneFailure[jobs.size()];
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
		java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger();
		List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
		for (int index = 0; index < jobs.size(); index++) {
			int slot = index;
			SceneJob job = jobs.get(index);
			futures.add(pool.submit(() -> {
				Failure failure;
				try {
					failure = runExpandedRandomPlay(registries, job.scene(), job.seed(), actions);
				} catch (Throwable unexpected) {
					failure = new Failure(0, unexpected, List.of("scene setup aborted"));
				}
				if (failure != null) {
					results[slot] = new SceneFailure(job, failure);
					failureCount.incrementAndGet();
				}
				int done = completed.incrementAndGet();
				if (done % 16 == 0) System.out.println("... fuzz progress jobs=" + done + "/" + jobs.size()
						+ ", failures=" + failureCount.get());
			}));
		}
		SceneJob timedOut = null;
		for (int index = 0; index < futures.size(); index++) {
			try {
				futures.get(index).get(JOB_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
			} catch (java.util.concurrent.TimeoutException timeout) {
				futures.get(index).cancel(true);
				if (timedOut == null) timedOut = jobs.get(index);
				if (results[index] == null) {
					results[index] = new SceneFailure(jobs.get(index), new Failure(0, new AssertionError(
							"fuzz job exceeded " + JOB_TIMEOUT_MILLIS + " ms without completing", timeout),
							List.of("job timed out")));
					failureCount.incrementAndGet();
				}
			} catch (java.util.concurrent.ExecutionException execution) {
				if (results[index] == null) {
					Throwable cause = execution.getCause() == null ? execution : execution.getCause();
					results[index] = new SceneFailure(jobs.get(index),
							new Failure(0, cause, List.of("job aborted without a report")));
					failureCount.incrementAndGet();
				}
			}
		}
		if (timedOut != null) {
			pool.shutdownNow();
			String mode = FUZZ_DISK ? (FUZZ_PARTIAL ? "partialdisk" : "disk") : null;
			System.err.println("A fuzz job exceeded " + JOB_TIMEOUT_MILLIS + " ms. A hang that ignores"
					+ " interruption cannot be reclaimed in-process, so exiting with code 3."
					+ " Replay: " + (mode == null ? "" : mode + ",")
					+ FUZZ_SCENES.indexOf(timedOut.scene()) + "," + timedOut.seed() + "," + actions);
			System.err.flush();
			System.exit(3);
		}
		pool.shutdown();
		java.util.LinkedHashMap<String, Integer> classes = new java.util.LinkedHashMap<>();
		java.util.LinkedHashMap<String, String> firstWhere = new java.util.LinkedHashMap<>();
		java.util.LinkedHashMap<String, String> firstTrace = new java.util.LinkedHashMap<>();
		int failures = 0;
		for (SceneFailure result : results) {
			if (result == null) continue;
			failures++;
			String root = rootCause(result.failure().cause());
			boolean firstOfClass = !classes.containsKey(root);
			System.out.println("FUZZ FAIL scene=" + result.job().scene().name() + " seed=" + result.job().seed()
					+ " action=" + result.failure().actionIndex() + " :: " + root);
			classes.merge(root, 1, Integer::sum);
			firstWhere.putIfAbsent(root, "scene=" + result.job().scene().name() + " seed=" + result.job().seed()
					+ " action=" + result.failure().actionIndex());
			firstTrace.putIfAbsent(root, result.failure().trace().toString());
			if (firstOfClass) result.failure().cause().printStackTrace(System.out);
		}
		System.out.println("scenario fuzz finished; jobs=" + jobs.size() + " failures=" + failures);
		for (var entry : classes.entrySet()) {
			System.out.println("FUZZ CLASS x" + entry.getValue() + " @ " + firstWhere.get(entry.getKey())
					+ " :: " + entry.getKey());
			System.out.println("  first trace=" + firstTrace.get(entry.getKey()));
		}
		FuzzMetrics.printCoverage();
		FuzzMetrics.printEvents();
		FuzzMetrics.printHotspots();
		check(failures == 0, "scenario fuzz found " + failures + " interrupted jobs");
	}

	static String rootCause(Throwable failure) {
		Throwable cause = failure;
		while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
		return cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}

	private record Failure(int actionIndex, Throwable cause, List<String> trace) { }

	private static Failure runRandomizedNormalPlay(
			FullCapabilityTestFixture.Registries registries, int seed, int actionCount
	) throws Exception {
		NormalPlayState state = new NormalPlayState(registries);
		java.util.Random random = new java.util.Random(seed);
		List<String> trace = new java.util.ArrayList<>();
		for (int index = 0; index < actionCount; index++) {
			String action;
			try {
				action = applyRandomAction(state, random);
			} catch (Throwable failure) {
				return new Failure(index + 1, failure, List.copyOf(trace));
			}
			trace.add((index + 1) + ":" + action);
			if ((FUZZ_DEBUG || Boolean.getBoolean("mmm.fuzzDebug"))) {
				System.out.println("action=" + (index + 1) + ":" + action + " liquid=" + state.domain.totalVolumeExact());
				for (int node : state.domain.gasBoundary().nodeIds()) System.out.println("  gas=" + node
						+ " volume=" + state.domain.gasBoundary().availableVolume(node)
						+ " std=" + state.domain.gasBoundary().standardVolume(node)
						+ " T=" + state.domain.gasNodeTemperatureK(node)
						+ " p=" + state.domain.gasNodeGaugePressurePa(node));
			}
		}
		return null;
	}

	private static Failure runExpandedRandomPlay(
			FullCapabilityTestFixture.Registries registries, int seed, int actionCount
	) throws Exception {
		return runExpandedRandomPlay(registries, BASELINE_SCENE, seed, actionCount);
	}

	private static Failure runExpandedRandomPlay(
			FullCapabilityTestFixture.Registries registries, FuzzScene scene, int seed, int actionCount
	) throws Exception {
		NormalPlayState state = new NormalPlayState(registries, scene);
		long jobStartNanos = System.nanoTime();
		long jobSubsteps = 0L;
		try {
			java.util.Random random = new java.util.Random(seed);
			List<String> trace = new java.util.ArrayList<>();
			for (int index = 0; index < actionCount; index++) {
				String action;
				state.substepsThisAction = 0L;
				long actionStartNanos = System.nanoTime();
				try {
					action = applyExpandedRandomAction(state, random);
				} catch (Throwable failure) {
					return new Failure(index + 1, failure, List.copyOf(trace));
				}
				long elapsedNanos = System.nanoTime() - actionStartNanos;
				String label = coverageLabel(action);
				if (FUZZ_PICKS) {
					System.out.println("done action=" + (index + 1) + " " + action + " "
							+ (elapsedNanos / 1_000_000L) + "ms substeps=" + state.substepsThisAction);
					System.out.flush();
				}
				ACTION_NANOS.computeIfAbsent(label, key -> new java.util.concurrent.atomic.LongAdder())
						.add(elapsedNanos);
				ACTION_COUNTS.computeIfAbsent(label, key -> new java.util.concurrent.atomic.LongAdder())
						.increment();
				ACTION_SUBSTEPS.computeIfAbsent(label, key -> new java.util.concurrent.atomic.LongAdder())
						.add(state.substepsThisAction);
				jobSubsteps += state.substepsThisAction;
				trace.add((index + 1) + ":" + action);
				cover(action);
				if ((FUZZ_DEBUG || Boolean.getBoolean("mmm.fuzzDebug"))) {
					System.out.println("action=" + (index + 1) + ":" + action + " liquid="
							+ state.domain.totalVolumeExact());
					for (int node : state.domain.gasBoundary().nodeIds()) System.out.println("  gas=" + node
							+ " volume=" + state.domain.gasBoundary().availableVolume(node)
							+ " std=" + state.domain.gasBoundary().standardVolume(node)
							+ " T=" + state.domain.gasNodeTemperatureK(node)
							+ " p=" + state.domain.gasNodeGaugePressurePa(node));
				}
				if (FUZZ_PROGRESS && (index + 1) % 8 == 0) System.out.println(
						"seed=" + seed + " actions=" + (index + 1) + "/" + actionCount + " generation="
								+ state.generation);
			}
			return null;
		} finally {
			String job = scene.name() + "/" + seed;
			JOB_MILLIS.put(job, (System.nanoTime() - jobStartNanos) / 1_000_000L);
			JOB_SUBSTEPS.put(job, jobSubsteps);
			state.close();
		}
	}

	private static String applyRandomAction(NormalPlayState state, java.util.Random random) {
		int pick = random.nextInt(100);
		if (pick < 6) { state.insertWater(); return "insertWater"; }
		if (pick < 11) { state.insertMercury(); return "insertMercury"; }
		if (pick < 18) { state.insertAcid(1 + random.nextInt(2)); return "insertAcid"; }
		if (pick < 25) { state.insertBase(1 + random.nextInt(2)); return "insertBase"; }
		if (pick < 30) {
			if (!FUZZ_DISABLE_GRANULAR) { state.insertSand(); return "insertSand"; }
			state.step(false);
			return "step(motion)";
		}
		if (pick < 40) { state.step(false); return "step(motion)"; }
		if (pick < 52) { state.step(true); return "step(phase)"; }
		if (pick < 60) { int count = 2 + random.nextInt(4); state.steps(true, count); return "steps(phase," + count + ")"; }
		if (pick < 64) { int count = 2 + random.nextInt(5); state.steps(false, count); return "steps(motion," + count + ")"; }
		if (pick < 70) {
			double kelvin = RANDOM_TEMPERATURES[random.nextInt(RANDOM_TEMPERATURES.length)];
			state.temperature(kelvin);
			state.roundTrip();
			return "temperature(" + kelvin + ")";
		}
		if (pick < 76) {
			state.settle();
			int[] target = state.maximumNearbyLiquidCell(6, state.water);
			if (state.domain.exactVolumeNear(target[0], target[1], target[2], 6, state.water) >= 8.0
					&& state.tryExtractWaterAt(target)) {
				return "extractWater";
			}
			return "skip(noCanonicalWater)";
		}
		if (pick < 82) {
			state.settle();
			int[] target = state.maximumNearbyLiquidCell(6);
			if (state.domain.exactVolumeNear(target[0], target[1], target[2], 6, -1) >= 8.0
					&& state.tryExtractChemicalAt(target)) {
				return "extractChemical";
			}
			return "skip(noCanonicalLiquid)";
		}
		if (pick < 87) {
			var edit = state.domain.editIndicatorFloodFill(7, 7, 7, random.nextBoolean(), 512, 100.0);
			if (edit.accepted()) {
				state.roundTrip();
				return "indicator";
			}
			return "skip(indicator:" + edit.failure() + ")";
		}
		if (pick < 91) { state.clear(); return "clear"; }
		if (pick < 96) { state.vent(); return "vent"; }
		state.settle();
		return "settle";
	}

	static String applyExpandedRandomAction(NormalPlayState state, java.util.Random random) {
		int pick = random.nextInt(100);
		String branch = actionName(pick);
		if (FUZZ_PICKS) { System.out.println("pick=" + pick + " " + branch); System.out.flush(); }
		boolean frozen = state.pausedByOperator();
		switch (branch) {
			case "insertWater": {
				int[] at = state.randomCorner(random);
				return state.tryInsertLiquidAt(at[0], at[1], at[2], state.water)
						? "insertWater" : "skip(insertWater)";
			}
			case "insertMercury": {
				int[] at = state.randomCorner(random);
				return state.tryInsertLiquidAt(at[0], at[1], at[2], state.mercury)
						? "insertMercury" : "skip(insertMercury)";
			}
			case "insertOil": {
				int[] at = state.randomCorner(random);
				return state.tryInsertLiquidAt(at[0], at[1], at[2], state.oil)
						? "insertOil" : "skip(insertOil)";
			}
			case "insertAcid": {
				int[] at = state.randomCorner(random);
				for (int bucket = 0, count = 1 + random.nextInt(2); bucket < count; bucket++) {
					if (!state.tryInsertChemicalAt(at[0], at[1], at[2], state.acid, state.acidEnergy)) {
						return "skip(insertAcid)";
					}
				}
				return "insertAcid";
			}
			case "insertBase": {
				int[] at = state.randomCorner(random);
				for (int bucket = 0, count = 1 + random.nextInt(2); bucket < count; bucket++) {
					if (!state.tryInsertChemicalAt(at[0], at[1], at[2], state.base, state.baseEnergy)) {
						return "skip(insertBase)";
					}
				}
				return "insertBase";
			}
			case "insertSand": {
				if (!FUZZ_DISABLE_GRANULAR) {
					int[] at = state.randomCorner(random);
					return state.tryInsertSandAt(at[0], at[1], at[2]) ? "insertSand" : "skip(insertSand)";
				}
				if (frozen) return "skip(paused)";
				state.step(false);
				return "step(motion)";
			}
			case "burst":
				return burst(state, random);
			case "insertMixture": {
				int[] at = state.randomCorner(random);
				return state.tryInsertMixtureAt(at[0], at[1], at[2]) ? "insertMixture" : "skip(insertMixture)";
			}
			case "step(motion)":
				if (frozen) return "skip(paused)";
				state.step(false);
				return "step(motion)";
			case "step(phase)":
				if (frozen) return "skip(paused)";
				state.step(true);
				return "step(phase)";
			case "steps(phase)": {
				if (frozen) return "skip(paused)";
				int count = 2 + random.nextInt(4);
				state.steps(true, count);
				return "steps(phase," + count + ")";
			}
			case "steps(motion)": {
				if (frozen) return "skip(paused)";
				int count = 2 + random.nextInt(5);
				state.steps(false, count);
				return "steps(motion," + count + ")";
			}
			case "temperature": {
				double kelvin = RANDOM_TEMPERATURES[random.nextInt(RANDOM_TEMPERATURES.length)];
				state.temperature(kelvin);
				state.roundTrip();
				return "temperature(" + kelvin + ")";
			}
			case "rampTemperature": {
				double delta = RAMP_DELTAS[random.nextInt(RAMP_DELTAS.length)];
				state.rampTemperature(delta);
				state.roundTrip();
				return "rampTemperature(" + delta + ")";
			}
			case "thermalStep(motion)":
				if (frozen) return "skip(paused)";
				if (state.domain.totalVolumeExact() > 0.0 || state.domain.totalGranularSolidVolumeSvq() > 0L
						|| state.domain.hasAmbientGasCells()) {
					state.thermalStep(false);
					return "thermalStep(motion)";
				}
				return "skip(thermalStep)";
			case "thermalStep(phase)":
				if (frozen) return "skip(paused)";
				if (state.domain.totalVolumeExact() > 0.0 || state.domain.totalGranularSolidVolumeSvq() > 0L) {
					state.thermalStep(true);
					return "thermalStep(phase)";
				}
				return "skip(thermalStep)";
			case "step(movingBody)": {
				if (frozen) return "skip(paused)";
				int bodies = 1 + random.nextInt(4);
				state.stepWithMovingBodies(random, bodies);
				return "step(movingBody," + bodies + ")";
			}
			case "pause":
				if (state.region == null) return "skip(pause)";
				if (state.region.paused()) return "skip(alreadyPaused)";
				state.pause();
				return "pause";
			case "resume":
				if (state.region == null) return "skip(resume)";
				if (!state.region.paused()) return "skip(alreadyRunning)";
				state.resume();
				return "resume";
			case "saveWhilePaused":
				if (state.region == null) return "skip(save)";
				state.saveWhilePaused();
				return "saveWhilePaused";
			case "containerTransfer":
				if (state.region == null) return "skip(containerTransfer)";
				return state.containerTransfer(random) ? "containerTransfer" : "skip(containerTransfer)";
			case "coldRestart":
				if (state.region == null) return "skip(coldRestart)";
				state.coldRestart(random);
				return "coldRestart";
			case "extractWater": {
				state.settle();
				int[] target = state.maximumNearbyLiquidCell(6, state.water);
				if (state.domain.exactVolumeNear(target[0], target[1], target[2], 6, state.water) >= 8.0
						&& state.tryExtractWaterAt(target)) {
					return "extractWater";
				}
				return "skip(noCanonicalWater)";
			}
			case "extractChemical": {
				state.settle();
				int[] target = state.maximumNearbyLiquidCell(6);
				if (state.domain.exactVolumeNear(target[0], target[1], target[2], 6, -1) >= 8.0
						&& state.tryExtractChemicalAt(target)) {
					return "extractChemical";
				}
				return "skip(noCanonicalLiquid)";
			}
			case "extractMixture": {
				state.settle();
				int[] target = state.maximumNearbyLiquidCell(6);
				if (state.domain.exactVolumeNear(target[0], target[1], target[2], 6, -1) >= 8.0
						&& state.tryExtractMixtureAt(target)) {
					return "extractMixture";
				}
				return "skip(noCanonicalMixture)";
			}
			case "extractTail":
				return state.extractTail();
			case "extractOverCapacity":
				return state.extractOverCapacity();
			case "extractSubBucket":
				return state.extractSubBucket(random.nextInt(MINIMUM_VOLUMES.length));
			case "extractThenRestore":
				return state.extractThenRestore();
			case "insertMinimalVolume":
				return state.insertMinimalVolume(random.nextInt(MINIMUM_VOLUMES.length));
			case "toggleSolid":
				return state.toggleSolid(random);
			case "editBoundarySource":
				return state.editBoundarySource(random);
			case "applicationStep":
				return state.applicationStep(random);
			case "sleepWake":
				return state.sleepWake(random);
			case "blockAndRetry":
				return state.blockAndRetry(random);
			case "indicator": {
				var edit = state.domain.editIndicatorFloodFill(state.injectX, state.injectY, state.injectZ,
						random.nextBoolean(), state.cellBudget(), 100.0);
				if (edit.accepted()) {
					state.roundTrip();
					return "indicator";
				}
				return "skip(indicator:" + edit.failure() + ")";
			}
			case "clear":
				state.clear();
				return "clear";
			case "vent":
				state.vent();
				return "vent";
			default:
				if (frozen) return "skip(paused)";
				state.settle();
				return "settle";
		}
	}

	private static String burst(NormalPlayState state, java.util.Random random) {
		int mode = random.nextInt(5);
		int count = 2 + random.nextInt(mode == 4 ? 4 : 8);
		for (int bucket = 0; bucket < count; bucket++) {
			int[] at = state.randomCorner(random);
			int x = at[0], y = at[1], z = at[2];
			boolean accepted;
			String label;
			switch (mode) {
				case 0 -> { accepted = state.tryInsertLiquidAt(x, y, z, state.water); label = "burstWater"; }
				case 1 -> { accepted = state.tryInsertLiquidAt(x, y, z, state.mercury); label = "burstMercury"; }
				case 2 -> { accepted = state.tryInsertChemicalAt(x, y, z, state.acid, state.acidEnergy); label = "burstAcid"; }
				case 3 -> { accepted = state.tryInsertChemicalAt(x, y, z, state.base, state.baseEnergy); label = "burstBase"; }
				default -> { accepted = !FUZZ_DISABLE_GRANULAR && state.tryInsertSandAt(x, y, z); label = "burstSand"; }
			}
			if (!accepted) return "skip(" + label + "@" + bucket + ")";
		}
		return "burst(" + count + ")";
	}

	private static final java.util.Map<String, java.util.function.Consumer<NormalPlayState>> PREFILL =
			new java.util.HashMap<>();
	static {
		PREFILL.put("prefill-water-8", state -> {
			for (int i = 0; i < 8; i++) {
				state.tryInsertLiquidAt(state.injectX, state.injectY, state.injectZ, state.water);
			}
			state.steps(false, 3);
		});
		PREFILL.put("prefill-sand-8", state -> {
			for (int i = 0; i < 4; i++) state.tryInsertSandAt(state.injectX, state.injectY, state.injectZ);
			state.steps(true, 3);
		});
		PREFILL.put("prefill-chem-8", state -> {
			for (int i = 0; i < 3; i++) {
				state.tryInsertChemicalAt(state.injectX, state.injectY, state.injectZ, state.acid, state.acidEnergy);
				state.tryInsertChemicalAt(state.injectX, state.injectY, state.injectZ, state.base, state.baseEnergy);
			}
			state.steps(true, 4);
		});
		PREFILL.put("prefill-mixed-8", state -> {
			state.tryInsertLiquidAt(state.injectX, state.injectY, state.injectZ, state.water);
			state.tryInsertLiquidAt(state.injectX, state.injectY, state.injectZ, state.mercury);
			state.tryInsertLiquidAt(state.injectX, state.injectY, state.injectZ, state.oil);
			state.tryInsertChemicalAt(state.injectX, state.injectY, state.injectZ, state.acid, state.acidEnergy);
			state.tryInsertChemicalAt(state.injectX, state.injectY, state.injectZ, state.base, state.baseEnergy);
			state.tryInsertSandAt(state.injectX, state.injectY, state.injectZ);
			state.steps(true, 4);
		});
		PREFILL.put("prefill-corridor-8", state -> {
			for (int i = 0; i < 6; i++) {
				state.tryInsertLiquidAt(state.injectX, state.injectY, state.injectZ, state.water);
				state.tryInsertSandAt(state.injectX, state.injectY, state.injectZ);
			}
			state.steps(true, 3);
		});
	}

	record FuzzScene(String name, int seedBase, int sizeX, int sizeY, int sizeZ, int capacity,
			boolean sealed, int injectX, int injectY, int injectZ, BlockPos origin,
			java.util.function.Consumer<FluidDomain> geometry) {

		FuzzScene {
			if (origin == null) origin = BlockPos.ZERO;
		}

		FuzzScene(String name, int seedBase, int sizeX, int sizeY, int sizeZ, int capacity,
				boolean sealed, int injectX, int injectY, int injectZ,
				java.util.function.Consumer<FluidDomain> geometry) {
			this(name, seedBase, sizeX, sizeY, sizeZ, capacity, sealed, injectX, injectY, injectZ,
					BlockPos.ZERO, geometry);
		}

		/** Places the same scene across a different world origin, for cross-section persistence coverage. */
		FuzzScene at(BlockPos newOrigin) {
			return new FuzzScene(name, seedBase, sizeX, sizeY, sizeZ, capacity, sealed, injectX, injectY,
					injectZ, newOrigin, geometry);
		}

		static FuzzScene flat(String name, int seedBase, int sizeX, int sizeY, int sizeZ, boolean sealed,
				int injectX, int injectY, int injectZ) {
			return new FuzzScene(name, seedBase, sizeX, sizeY, sizeZ, sizeX * sizeY * sizeZ * 8, sealed,
					injectX, injectY, injectZ, domain -> { });
		}
	}

	private static final FuzzScene BASELINE_SCENE = FuzzScene.flat(
			"sealed-empty-8", 100, 8, 8, 8, true, 7, 7, 7);

	private static final List<FuzzScene> FUZZ_SCENES = List.of(
			BASELINE_SCENE,
			FuzzScene.flat("sealed-empty-4", 200, 4, 4, 4, true, 3, 3, 3),
			FuzzScene.flat("open-empty-8", 300, 8, 8, 8, false, 7, 7, 7),
			FuzzScene.flat("sealed-tall-6x10x6", 400, 6, 10, 6, true, 3, 8, 3),
			new FuzzScene("sealed-floor-8", 500, 8, 8, 8, 8 * 8 * 8 * 8, true, 4, 6, 4, domain -> {
				for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) domain.setSolid(x, 0, z, true);
			}),
			new FuzzScene("sealed-corridor-8", 600, 8, 8, 8, 8 * 8 * 8 * 8, true, 4, 6, 4, domain -> {
				for (int z = 0; z < 8; z++) for (int y = 1; y < 7; y++) {
					domain.setSolid(3, y, z, true);
					domain.setSolid(5, y, z, true);
				}
			}),
			new FuzzScene("sealed-halfcells-8", 700, 8, 8, 8, 8 * 8 * 8 * 8, true, 4, 6, 4, domain -> {
				for (int z = 2; z < 6; z++) for (int y = 2; y < 6; y++) {
					domain.setSolidMicroMask(4, y, z, SCENE_HALF_BOTTOM);
				}
			}),
			new FuzzScene("sealed-obstacle-10x8x8", 800, 10, 8, 8, 10 * 8 * 8 * 8, true, 5, 6, 4, domain -> {
				for (int z = 0; z < 8; z++) for (int x = 0; x < 10; x++) domain.setSolid(x, 0, z, true);
				for (int z = 2; z <= 5; z++) for (int y = 1; y <= 3; y++) {
					domain.setSolidMicroMask(5, y, z, SCENE_HALF_BOTTOM);
				}
			}),
			new FuzzScene("sealed-diagonal-8", 900, 8, 8, 8, 8 * 8 * 8 * 8, true, 1, 6, 4, domain -> {
				for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
					for (int y = 0; y <= x; y++) domain.setSolid(x, y, z, true);
					if (x + 1 < 8) domain.setSolidMicroMask(x, x + 1, z, SCENE_HALF_BOTTOM);
				}
			}),
			new FuzzScene("sealed-throat-8", 1000, 8, 8, 8, 8 * 8 * 8 * 8, true, 2, 6, 4, domain -> {
				for (int z = 0; z < 8; z++) for (int y = 0; y < 8; y++) {
					if (y == 4 && z == 4) continue;
					domain.setSolid(4, y, z, true);
				}
			}),
			new FuzzScene("sealed-multicavity-8", 1100, 8, 8, 8, 8 * 8 * 8 * 8, true, 2, 6, 4, domain -> {
				for (int z = 0; z < 8; z++) for (int y = 0; y < 8; y++) domain.setSolid(4, y, z, true);
				for (int z = 3; z <= 4; z++) for (int y = 1; y <= 2; y++) domain.setSolid(4, y, z, false);
			}),
			new FuzzScene("sealed-ledge-8", 1200, 8, 8, 8, 8 * 8 * 8 * 8, true, 4, 4, 4, domain -> {
				for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
					domain.setSolid(x, 0, z, true);
					domain.setSolid(x, 7, z, true);
				}
			}),
			new FuzzScene("sealed-pillar-8", 1300, 8, 8, 8, 8 * 8 * 8 * 8, true, 4, 6, 4, domain -> {
				for (int z = 2; z <= 5; z++) for (int y = 0; y <= 5; y++) for (int x = 2; x <= 5; x++) {
					if (x == 2 || x == 5 || z == 2 || z == 5) domain.setSolid(x, y, z, true);
				}
			}),
			FuzzScene.flat("prefill-water-8", 1400, 8, 8, 8, true, 7, 7, 7),
			FuzzScene.flat("prefill-sand-8", 1500, 8, 8, 8, true, 7, 7, 7),
			FuzzScene.flat("prefill-chem-8", 1600, 8, 8, 8, true, 7, 7, 7),
			FuzzScene.flat("prefill-mixed-8", 1700, 8, 8, 8, true, 7, 7, 7),
			new FuzzScene("prefill-corridor-8", 1800, 8, 8, 8, 8 * 8 * 8 * 8, true, 4, 6, 4, domain -> {
				for (int z = 0; z < 8; z++) for (int y = 1; y < 7; y++) {
					domain.setSolid(3, y, z, true);
					domain.setSolid(5, y, z, true);
				}
			}),
			FuzzScene.flat("sealed-netsplit-8", 1900, 8, 8, 8, true, 7, 7, 7)
					.at(new BlockPos(-2, 64, -2)),
			new FuzzScene("sealed-netsplit-corridor-8", 2000, 8, 8, 8, 8 * 8 * 8 * 8, true, 4, 6, 4, domain -> {
				for (int z = 0; z < 8; z++) for (int y = 1; y < 7; y++) {
					domain.setSolid(3, y, z, true);
					domain.setSolid(5, y, z, true);
				}
			}).at(new BlockPos(-2, 64, -2)),
			new FuzzScene("sealed-bottleneck-8", 2100, 8, 8, 8, 8 * 8 * 8 * 8, true, 6, 6, 4, domain -> {
				for (int z = 0; z < 8; z++) for (int y = 0; y < 8; y++) {
					if (y == 1 && z == 4) continue;
					domain.setSolid(4, y, z, true);
				}
			}),
			new FuzzScene("sealed-microthroat-8", 2200, 8, 8, 8, 8 * 8 * 8 * 8, true, 2, 6, 4, domain -> {
				for (int z = 0; z < 8; z++) for (int y = 0; y < 8; y++) {
					if (y == 4 && z == 4) {
						domain.setSolidMicroMask(4, y, z, SCENE_HALF_BOTTOM);
					} else {
						domain.setSolid(4, y, z, true);
					}
				}
			})
	);

	@FunctionalInterface private interface Scenario { void run(NormalPlayState state) throws Exception; }

	private static FluidSolverConfig productionConfig() {
		return new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				240, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	@FunctionalInterface
	private interface CandidateMutation {
		void apply(FluidDomain candidate) throws Exception;
	}
}
