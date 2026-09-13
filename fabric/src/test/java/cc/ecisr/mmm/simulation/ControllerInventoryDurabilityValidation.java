package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FluidDomain;
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
		var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				region.solverGeneration(), region.domain().captureAuthoritativeState(), java.util.List.of(),
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
		double bucketVolume = 1.0 / SolverUnitScale.forCellSize(
				productionConfig().cellSize()).cubicMetersPerNormalizedVolume();
		return new ChemicalTransferLedger(java.util.List.of(new ChemicalTransferLedger.Group(
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

	private static final class NormalPlayState {
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
		private FluidDomain domain;
		private long generation;

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
			water = registries.liquids().handle(BuiltinFluidMaterials.WATER.id());
			mercury = registries.liquids().handle(BuiltinFluidMaterials.MERCURY.id());
			oil = registries.liquids().handle(BuiltinFluidMaterials.OIL.id());
			acid = reagentBucket(registries, BuiltinChemicalDefinitions.HCL);
			acidEnergy = reagentEnergy(registries, acid);
			base = reagentBucket(registries, BuiltinChemicalDefinitions.NAOH);
			baseEnergy = reagentEnergy(registries, base);
			int sandHandle = registries.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND);
			double mass = registries.granular().material(sandHandle).referenceDensity();
			sand = new GranularTransferLedger(java.util.List.of(new GranularTransferLedger.Group(
					BuiltinGranularDefinitions.FINE_SAND, SolidVolume.SVQ_PER_BV, mass, null)));
			if (FUZZ_DISK) {
				diskRoot = Files.createTempDirectory("mmm-fuzz-disk");
				persistence = new RegionRepositoryAdapter(diskRoot, TestProfiles.full(), registries.chemical(),
						registries.granular(), registries.thermal(), registries.gas());
				region = new FluidRegion(java.util.UUID.randomUUID(), DIMENSION, BlockPos.ZERO, domain,
						scene.capacity(), footprint());
				persistence.checkpoint(region);
			}
			roundTrip();
			java.util.function.Consumer<NormalPlayState> prefill = PREFILL.get(scene.name());
			if (prefill != null) prefill.accept(this);
		}

		long solverGeneration() { return region != null ? region.solverGeneration() : generation; }

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
			for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) {
				for (int x = 0; x < domain.sizeX(); x++) {
					if (domain.exactVolumeNear(x, y, z, 0, -1) > 0.0) {
						domain.setThermalTemperatureInCell(x, y, z, kelvin);
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
			float volume = FUZZ_PARTIAL ? partialVolume(x, y, z) : 8.0F;
			if (!domain.tryInsertExactVolume(x, y, z, volume, 6, material, true).transferred()) return false;
			roundTrip();
			return true;
		}
		boolean tryInsertChemicalAt(int x, int y, int z, ChemicalTransferLedger ledger, double energy) {
			settle();
			if (!domain.tryInsertExactChemical(x, y, z, ledger, 6, true, energy).transferred()) return false;
			roundTrip();
			return true;
		}
		boolean tryInsertSandAt(int x, int y, int z) {
			settle();
			if (!domain.tryInsertExactGranular(x, y, z, sand, 6, true).inserted()) return false;
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
			float volume = FUZZ_PARTIAL ? partialVolume(target[0], target[1], target[2]) : 8.0F;
			if (!domain.tryExtractExactVolume(target[0], target[1], target[2], volume, 6, water).transferred()) return false;
			roundTrip();
			return true;
		}
		boolean tryExtractChemicalAt(int[] target) {
			if (!domain.tryExtractExactChemical(target[0], target[1], target[2], 8.0F, 6).transferred()) return false;
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
						solverGeneration(), domain.captureAuthoritativeState(), java.util.List.of(), phase,
						phase ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0));
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
			try {
				var candidate = new CpuFluidSolverBackend().evaluateThermal(new FluidThermalStepRequest(
						solverGeneration(), domain.captureAuthoritativeState(), phase,
						phase ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0));
				adoptThermal(candidate);
			} catch (RuntimeException failure) {
				throw new IllegalStateException("normal-play thermal-only step failed at generation " + generation
						+ ", liquid=" + domain.totalVolumeExact() + ", markers=" + domain.particleCount()
						+ ", solidSvq=" + domain.totalGranularSolidVolumeSvq(), failure);
			}
			generation++;
			roundTrip();
		}
		void stepWithMovingBody(java.util.Random random) {
			double half = domain.sizeX() * 0.5;
			double displaced = 0.001 + random.nextDouble() * 0.05;
			var body = new MovingBodyFluidCoupling.Request(
					(float) (0.25 + random.nextDouble() * (half - 0.5)),
					(float) (0.25 + random.nextDouble() * (half - 0.5)),
					(float) (0.25 + random.nextDouble() * (half - 0.5)),
					(random.nextDouble() - 0.5) * 20.0, (random.nextDouble() - 0.5) * 20.0,
					(random.nextDouble() - 0.5) * 20.0, displaced);
			try {
				var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
						solverGeneration(), domain.captureAuthoritativeState(), java.util.List.of(body), true,
						ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
				adopt(candidate);
			} catch (RuntimeException failure) {
				throw new IllegalStateException("normal-play moving-body step failed at generation " + generation
						+ ", liquid=" + domain.totalVolumeExact() + ", markers=" + domain.particleCount()
						+ ", solidSvq=" + domain.totalGranularSolidVolumeSvq(), failure);
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
					try {
						persistence.checkpointAndUnload(region);
						persistence.restore(region);
					} catch (java.io.IOException failure) {
						throw new IllegalStateException("normal-play disk checkpoint failed at generation "
								+ generation, failure);
					}
					domain = region.domain();
				}
			} else {
				domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			}
			if (scene.sealed()) {
				check(domain.gasBoundary().auditClosed(),
						"normal-play restore generation " + generation + " left the gas audit open");
				check(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
						"normal-play restore generation " + generation + " left the thermal audit open");
			}
		}
	}

	/** Seeded normal-play fuzzing entry point used to surface forced simulation interruptions. */
	public static void main(String[] args) throws Exception {
		int[] explicitSeeds = args.length > 0 && args[0].equals("reported")
				? new int[]{97, 119, 135, 152, 267, 268, 282, 323, 327, 441, 479, 505, 506, 691,
						702, 807, 876, 880, 1259, 1393, 1433, 1505, 1807, 1864, 1870, 2056, 2075, 3034}
				: args.length > 0 && args[0].contains(":")
				? java.util.Arrays.stream(args[0].split(":")).mapToInt(Integer::parseInt).toArray() : null;
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		if (args.length > 0 && args[0].equals("replay")) {
			FUZZ_PROGRESS = true;
			int scene = Integer.parseInt(args[1]);
			int seed = Integer.parseInt(args[2]);
			Failure failure = runExpandedRandomPlay(registries, FUZZ_SCENES.get(scene), seed,
					args.length > 3 ? Integer.parseInt(args[3]) : 80);
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
			int sceneIndex = args.length > 1 ? Integer.parseInt(args[1]) : 1;
			int seed = args.length > 2 ? Integer.parseInt(args[2]) : 212;
			int actions = args.length > 3 ? Integer.parseInt(args[3]) : 80;
			minimize(registries, FUZZ_SCENES.get(sceneIndex), seed, actions);
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

	private static void runExplicitSeeds(
			FullCapabilityTestFixture.Registries registries, int[] seeds, int actions
	) throws Exception {
		FUZZ_PROGRESS = true;
		for (int seed : seeds) {
			Failure failure = runExpandedRandomPlay(registries, seed, actions);
			System.out.println("seed=" + seed + (failure == null ? " PASS" : " FAIL"));
			if (failure != null) failure.cause().printStackTrace(System.out);
		}
	}

	private record RngToken(char kind, int bound, int intValue, double doubleValue, boolean boolValue) { }

	private static final class RecordingRandom extends java.util.Random {
		private final java.util.List<RngToken> tokens = new java.util.ArrayList<>();

		RecordingRandom(long seed) { super(seed); }

		@Override public int nextInt(int bound) {
			int value = super.nextInt(bound);
			tokens.add(new RngToken('i', bound, value, 0.0, false));
			return value;
		}

		@Override public boolean nextBoolean() {
			boolean value = super.nextBoolean();
			tokens.add(new RngToken('b', 0, 0, 0.0, value));
			return value;
		}

		@Override public double nextDouble() {
			double value = super.nextDouble();
			tokens.add(new RngToken('d', 0, 0, value, false));
			return value;
		}
	}

	private static final class ScriptedRandom extends java.util.Random {
		private final java.util.List<RngToken> tokens;
		private int cursor;

		ScriptedRandom(java.util.List<RngToken> tokens) { this.tokens = tokens; }

		private RngToken take(char kind) {
			if (cursor >= tokens.size()) throw new IllegalStateException("script exhausted");
			RngToken token = tokens.get(cursor++);
			if (token.kind() != kind) throw new IllegalStateException("script kind mismatch");
			return token;
		}

		@Override public int nextInt(int bound) {
			RngToken token = take('i');
			if (token.bound() != bound) throw new IllegalStateException("script bound mismatch");
			return token.intValue();
		}

		@Override public boolean nextBoolean() { return take('b').boolValue(); }

		@Override public double nextDouble() { return take('d').doubleValue(); }
	}

	private record ScriptedFailure(
			FuzzScene scene, int seed, String root,
			java.util.List<java.util.List<RngToken>> slices, java.util.List<String> labels
	) { }

	private static String failedLabel(java.util.List<RngToken> slice) {
		if (slice.isEmpty()) return "<failed>";
		RngToken first = slice.get(0);
		if (first.kind() != 'i' || first.bound() != 100) return "<failed>";
		return branchName(first.intValue());
	}

	private static String branchName(int pick) {
		if (pick < 5) return "insertWater";
		if (pick < 10) return "insertMercury";
		if (pick < 15) return "insertOil";
		if (pick < 22) return "insertAcid";
		if (pick < 29) return "insertBase";
		if (pick < 35) return "insertSand";
		if (pick < 42) return "burst";
		if (pick < 48) return "step(motion)";
		if (pick < 54) return "step(phase)";
		if (pick < 60) return "steps(phase)";
		if (pick < 63) return "steps(motion)";
		if (pick < 68) return "temperature";
		if (pick < 71) return "thermalStep(motion)";
		if (pick < 74) return "thermalStep(phase)";
		if (pick < 78) return "step(movingBody)";
		if (pick < 82) return "extractWater";
		if (pick < 88) return "extractChemical";
		if (pick < 93) return "indicator";
		if (pick < 96) return "clear";
		if (pick < 98) return "vent";
		return "settle";
	}

	private static ScriptedFailure recordScriptedFailure(
			FullCapabilityTestFixture.Registries registries, FuzzScene scene, int seed, int maxActions
	) throws Exception {
		NormalPlayState state = new NormalPlayState(registries, scene);
		RecordingRandom random = new RecordingRandom(seed);
		java.util.List<java.util.List<RngToken>> slices = new java.util.ArrayList<>();
		java.util.List<String> labels = new java.util.ArrayList<>();
		for (int index = 0; index < maxActions; index++) {
			int before = random.tokens.size();
			try {
				labels.add(applyExpandedRandomAction(state, random));
			} catch (Throwable failure) {
				java.util.List<RngToken> slice =
						java.util.List.copyOf(random.tokens.subList(before, random.tokens.size()));
				slices.add(slice);
				labels.add(failedLabel(slice));
				return new ScriptedFailure(scene, seed, rootCause(failure), slices, labels);
			}
			slices.add(java.util.List.copyOf(random.tokens.subList(before, random.tokens.size())));
		}
		return null;
	}

	private static String replayScript(
			FullCapabilityTestFixture.Registries registries, ScriptedFailure script, int[] keep
	) throws Exception {
		NormalPlayState state = new NormalPlayState(registries, script.scene());
		for (int index : keep) {
			applyExpandedRandomAction(state, new ScriptedRandom(script.slices().get(index)));
		}
		return null;
	}

	private static boolean reproduces(
			FullCapabilityTestFixture.Registries registries, ScriptedFailure script, int[] keep
	) {
		try {
			return script.root().equals(replayScript(registries, script, keep));
		} catch (Throwable failure) {
			return script.root().equals(rootCause(failure));
		}
	}

	private static void minimize(
			FullCapabilityTestFixture.Registries registries, FuzzScene scene, int seed, int actions
	) throws Exception {
		ScriptedFailure script = recordScriptedFailure(registries, scene, seed, actions);
		if (script == null) {
			System.out.println("minimize[" + scene.name() + " seed=" + seed + "]: did not fail");
			return;
		}
		int size = script.slices().size();
		int[] keep = new int[size];
		for (int index = 0; index < size; index++) keep[index] = index;
		System.out.println("minimize[" + scene.name() + " seed=" + seed + "] start actions=" + size
				+ " root=" + script.root());
		int granularity = 2;
		while (keep.length >= 2) {
			int chunk = (keep.length + granularity - 1) / granularity;
			int[] reduced = null;
			for (int start = 0; start < keep.length; start += chunk) {
				int end = Math.min(keep.length, start + chunk);
				int[] candidate = new int[keep.length - (end - start)];
				System.arraycopy(keep, 0, candidate, 0, start);
				System.arraycopy(keep, end, candidate, start, keep.length - end);
				if (reproduces(registries, script, candidate)) {
					reduced = candidate;
					break;
				}
			}
			if (reduced != null) {
				keep = reduced;
				granularity = Math.max(2, granularity - 1);
			} else if (granularity >= keep.length) {
				break;
			} else {
				granularity = Math.min(keep.length, granularity * 2);
			}
		}
		StringBuilder trace = new StringBuilder("[");
		for (int index = 0; index < keep.length; index++) {
			if (index > 0) trace.append(", ");
			trace.append(keep[index] + 1).append(':').append(script.labels().get(keep[index]));
		}
		trace.append("]");
		System.out.println("minimize[" + scene.name() + " seed=" + seed + "] final actions=" + keep.length);
		System.out.println("minimize[" + scene.name() + " seed=" + seed + "] trace=" + trace);
	}

	private record SceneJob(FuzzScene scene, int seed) { }

	private record SceneFailure(SceneJob job, Failure failure) { }

	private static void runSceneFuzz(
			FullCapabilityTestFixture.Registries registries, int seedsPerScene, int actions, int threads,
			int sceneFrom, int sceneTo, int seedOffset
	) throws Exception {
		FUZZ_PROGRESS = false;
		int lastScene = Math.min(sceneTo, FUZZ_SCENES.size());
		java.util.List<SceneJob> jobs = new java.util.ArrayList<>();
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
		java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
		for (int index = 0; index < jobs.size(); index++) {
			int slot = index;
			SceneJob job = jobs.get(index);
			futures.add(pool.submit(() -> {
				Failure failure;
				try {
					failure = runExpandedRandomPlay(registries, job.scene(), job.seed(), actions);
				} catch (Throwable unexpected) {
					failure = new Failure(0, unexpected, java.util.List.of("scene setup aborted"));
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
		for (java.util.concurrent.Future<?> future : futures) future.get();
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
		check(failures == 0, "scenario fuzz found " + failures + " interrupted jobs");
	}

	private static String rootCause(Throwable failure) {
		Throwable cause = failure;
		while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
		return cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}

	private record Failure(int actionIndex, Throwable cause, java.util.List<String> trace) { }

	private static Failure runRandomizedNormalPlay(
			FullCapabilityTestFixture.Registries registries, int seed, int actionCount
	) throws Exception {
		NormalPlayState state = new NormalPlayState(registries);
		java.util.Random random = new java.util.Random(seed);
		java.util.List<String> trace = new java.util.ArrayList<>();
		for (int index = 0; index < actionCount; index++) {
			String action;
			try {
				action = applyRandomAction(state, random);
			} catch (Throwable failure) {
				return new Failure(index + 1, failure, java.util.List.copyOf(trace));
			}
			trace.add((index + 1) + ":" + action);
			if (Boolean.getBoolean("mmm.fuzzDebug")) {
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
		try {
			java.util.Random random = new java.util.Random(seed);
			java.util.List<String> trace = new java.util.ArrayList<>();
			for (int index = 0; index < actionCount; index++) {
				String action;
				try {
					action = applyExpandedRandomAction(state, random);
				} catch (Throwable failure) {
					return new Failure(index + 1, failure, java.util.List.copyOf(trace));
				}
				trace.add((index + 1) + ":" + action);
				if (Boolean.getBoolean("mmm.fuzzDebug")) {
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

	private static String applyExpandedRandomAction(NormalPlayState state, java.util.Random random) {
		int pick = random.nextInt(100);
		if (pick < 5) {
			int[] at = state.randomCorner(random);
			return state.tryInsertLiquidAt(at[0], at[1], at[2], state.water) ? "insertWater" : "skip(insertWater)";
		}
		if (pick < 10) {
			int[] at = state.randomCorner(random);
			return state.tryInsertLiquidAt(at[0], at[1], at[2], state.mercury) ? "insertMercury" : "skip(insertMercury)";
		}
		if (pick < 15) {
			int[] at = state.randomCorner(random);
			return state.tryInsertLiquidAt(at[0], at[1], at[2], state.oil) ? "insertOil" : "skip(insertOil)";
		}
		if (pick < 22) {
			int[] at = state.randomCorner(random);
			int x = at[0], y = at[1], z = at[2];
			for (int bucket = 0, count = 1 + random.nextInt(2); bucket < count; bucket++) {
				if (!state.tryInsertChemicalAt(x, y, z, state.acid, state.acidEnergy)) return "skip(insertAcid)";
			}
			return "insertAcid";
		}
		if (pick < 29) {
			int[] at = state.randomCorner(random);
			int x = at[0], y = at[1], z = at[2];
			for (int bucket = 0, count = 1 + random.nextInt(2); bucket < count; bucket++) {
				if (!state.tryInsertChemicalAt(x, y, z, state.base, state.baseEnergy)) return "skip(insertBase)";
			}
			return "insertBase";
		}
		if (pick < 35) {
			if (!FUZZ_DISABLE_GRANULAR) {
				int[] at = state.randomCorner(random);
				if (state.tryInsertSandAt(at[0], at[1], at[2])) {
					return "insertSand";
				}
				return "skip(insertSand)";
			}
			state.step(false);
			return "step(motion)";
		}
		if (pick < 42) return burst(state, random);
		if (pick < 48) { state.step(false); return "step(motion)"; }
		if (pick < 54) { state.step(true); return "step(phase)"; }
		if (pick < 60) { int count = 2 + random.nextInt(4); state.steps(true, count); return "steps(phase," + count + ")"; }
		if (pick < 63) { int count = 2 + random.nextInt(5); state.steps(false, count); return "steps(motion," + count + ")"; }
		if (pick < 68) {
			double kelvin = RANDOM_TEMPERATURES[random.nextInt(RANDOM_TEMPERATURES.length)];
			state.temperature(kelvin);
			state.roundTrip();
			return "temperature(" + kelvin + ")";
		}
		if (pick < 71) {
			if (state.domain.totalVolumeExact() > 0.0 || state.domain.totalGranularSolidVolumeSvq() > 0L
					|| state.domain.hasAmbientGasCells()) {
				state.thermalStep(false);
				return "thermalStep(motion)";
			}
			return "skip(thermalStep)";
		}
		if (pick < 74) {
			if (state.domain.totalVolumeExact() > 0.0 || state.domain.totalGranularSolidVolumeSvq() > 0L) {
				state.thermalStep(true);
				return "thermalStep(phase)";
			}
			return "skip(thermalStep)";
		}
		if (pick < 78) { state.stepWithMovingBody(random); return "step(movingBody)"; }
		if (pick < 82) {
			state.settle();
			int[] target = state.maximumNearbyLiquidCell(6, state.water);
			if (state.domain.exactVolumeNear(target[0], target[1], target[2], 6, state.water) >= 8.0
					&& state.tryExtractWaterAt(target)) {
				return "extractWater";
			}
			return "skip(noCanonicalWater)";
		}
		if (pick < 88) {
			state.settle();
			int[] target = state.maximumNearbyLiquidCell(6);
			if (state.domain.exactVolumeNear(target[0], target[1], target[2], 6, -1) >= 8.0
					&& state.tryExtractChemicalAt(target)) {
				return "extractChemical";
			}
			return "skip(noCanonicalLiquid)";
		}
		if (pick < 93) {
			var edit = state.domain.editIndicatorFloodFill(state.injectX, state.injectY, state.injectZ,
					random.nextBoolean(), state.cellBudget(), 100.0);
			if (edit.accepted()) {
				state.roundTrip();
				return "indicator";
			}
			return "skip(indicator:" + edit.failure() + ")";
		}
		if (pick < 96) { state.clear(); return "clear"; }
		if (pick < 98) { state.vent(); return "vent"; }
		state.settle();
		return "settle";
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

	private static boolean FUZZ_PROGRESS;
	private static boolean FUZZ_DISABLE_GRANULAR = false;
	private static boolean FUZZ_DISK = false;
	private static boolean FUZZ_PARTIAL = false;
	private static final int FUZZ_DISK_ROUND_TRIP_INTERVAL = 4;

	private static float partialVolume(int x, int y, int z) {
		return 0.25F + ((x * 31 + y * 17 + z * 7) % 32) * 0.24F;
	}

	private static final double[] RANDOM_TEMPERATURES = {
			1.0, 50.0, 150.0, 273.15, 293.15, 320.0, 373.15, 600.0, 1_500.0, 3_000.0
	};

	private static final int SCENE_HALF_BOTTOM = 0x33;

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

	private record FuzzScene(String name, int seedBase, int sizeX, int sizeY, int sizeZ, int capacity,
			boolean sealed, int injectX, int injectY, int injectZ,
			java.util.function.Consumer<FluidDomain> geometry) {

		static FuzzScene flat(String name, int seedBase, int sizeX, int sizeY, int sizeZ, boolean sealed,
				int injectX, int injectY, int injectZ) {
			return new FuzzScene(name, seedBase, sizeX, sizeY, sizeZ, sizeX * sizeY * sizeZ * 8, sealed,
					injectX, injectY, injectZ, domain -> { });
		}
	}

	private static final FuzzScene BASELINE_SCENE = FuzzScene.flat(
			"sealed-empty-8", 100, 8, 8, 8, true, 7, 7, 7);

	private static final java.util.List<FuzzScene> FUZZ_SCENES = java.util.List.of(
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
