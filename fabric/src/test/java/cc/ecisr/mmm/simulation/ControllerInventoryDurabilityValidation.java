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
		region.domain().step(phaseChange, phaseChange ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0);
		checkClosed(region.domain(), "step before generation " + (region.checkpointGeneration() + 1L));
		persistence.checkpoint(region);
		restart(region, persistence, "published step");
	}

	private static void settlePendingVolumeWork(
			FluidRegion region, RegionRepositoryAdapter persistence
	) throws Exception {
		if (!region.domain().hasPendingPhaseVolumeWork()) return;
		region.domain().stepMechanicsOnly();
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
		private final FullCapabilityTestFixture.Registries registries;
		private final int water;
		private final int mercury;
		private final int oil;
		private final ChemicalTransferLedger acid;
		private final double acidEnergy;
		private final ChemicalTransferLedger base;
		private final double baseEnergy;
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
			oil = registries.liquids().handle(BuiltinFluidMaterials.OIL.id());
			acid = reagentBucket(registries, BuiltinChemicalDefinitions.HCL);
			acidEnergy = reagentEnergy(registries, acid);
			base = reagentBucket(registries, BuiltinChemicalDefinitions.NAOH);
			baseEnergy = reagentEnergy(registries, base);
			int sandHandle = registries.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND);
			double mass = registries.granular().material(sandHandle).referenceDensity();
			sand = new GranularTransferLedger(java.util.List.of(new GranularTransferLedger.Group(
					BuiltinGranularDefinitions.FINE_SAND, SolidVolume.SVQ_PER_BV, mass, null)));
			roundTrip();
		}

		void temperature(double kelvin) {
			for (int z = 0; z < 8; z++) for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
				if (domain.exactVolumeNear(x, y, z, 0, -1) > 0.0) domain.setThermalTemperatureInCell(x, y, z, kelvin);
			}
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
			check(domain.tryInsertExactChemical(7, 7, 7, ledger, 6, true, energy).transferred(),
					"normal-play " + name + " insertion was rejected at generation " + generation
							+ ", liquid=" + domain.totalVolumeExact() + ", markers=" + domain.particleCount()
							+ ", solidSvq=" + domain.totalGranularSolidVolumeSvq());
			roundTrip();
		}
		void insertSand() {
			settle();
			check(domain.tryInsertExactGranular(7, 7, 7, sand, 6, true).inserted(),
					"normal-play granular insertion was rejected");
			roundTrip();
		}
		boolean tryInsertLiquidAt(int x, int y, int z, int material) {
			settle();
			if (!domain.tryInsertExactVolume(x, y, z, 8.0F, 6, material, true).transferred()) return false;
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
			var edit = domain.editIndicatorFloodFill(7, 7, 7, value, 512, 100.0);
			check(edit.accepted(), "normal-play indicator edit was rejected: " + edit.failure()
					+ ", liquid=" + domain.totalVolumeExact() + ", markers=" + domain.particleCount()
					+ ", solidSvq=" + domain.totalGranularSolidVolumeSvq());
			roundTrip();
		}
		void extractWater() {
			settle();
			check(domain.tryExtractExactVolume(7, 7, 7, 8.0F, 6).transferred(),
					"normal-play water extraction was rejected");
			roundTrip();
		}
		boolean tryExtractWaterAt(int[] target) {
			if (!domain.tryExtractExactVolume(target[0], target[1], target[2], 8.0F, 6, water).transferred()) return false;
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
						FluidStepRequest.pendingPhaseSettlement(generation, domain.captureAuthoritativeState()));
				check(candidate.stats().thermalAxis().granularPhase().transfers() == 0
							&& candidate.stats().chemistryEvaluations() == 0,
						"pending phase-work settlement advanced thermochemistry");
				domain = candidate.claimPreparedCpuDomain();
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
						generation, domain.captureAuthoritativeState(), java.util.List.of(), phase,
						phase ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0));
				domain = candidate.claimPreparedCpuDomain();
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
						generation, domain.captureAuthoritativeState(), phase,
						phase ? ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP : 0));
				domain = candidate.claimPreparedCpuDomain();
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
						generation, domain.captureAuthoritativeState(), java.util.List.of(body), true,
						ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
				domain = candidate.claimPreparedCpuDomain();
			} catch (RuntimeException failure) {
				throw new IllegalStateException("normal-play moving-body step failed at generation " + generation
						+ ", liquid=" + domain.totalVolumeExact() + ", markers=" + domain.particleCount()
						+ ", solidSvq=" + domain.totalGranularSolidVolumeSvq(), failure);
			}
			generation++;
			roundTrip();
		}
		void roundTrip() {
			checkClosed(domain, "normal-play handoff generation " + generation);
			domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			checkClosed(domain, "normal-play restore generation " + generation);
		}
	}

	/** Seeded normal-play fuzzing entry point used to surface forced simulation interruptions. */
	public static void main(String[] args) throws Exception {
		int[] selectedSeeds = args.length > 0 && args[0].equals("reported")
				? new int[]{97, 119, 135, 152, 267, 268, 282, 323, 327, 441, 479, 505, 506, 691,
						702, 807, 876, 880, 1259, 1393, 1433, 1505, 1807, 1864, 1870, 2056, 2075, 3034}
				: args.length > 0 && args[0].contains(":")
				? java.util.Arrays.stream(args[0].split(":")).mapToInt(Integer::parseInt).toArray() : null;
		int firstSeed = selectedSeeds != null ? 0 : args.length > 0 ? Integer.parseInt(args[0]) : 0;
		int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 32;
		int actions = args.length > 2 ? Integer.parseInt(args[2]) : 80;
		FUZZ_DISABLE_GRANULAR = args.length > 3 && Boolean.parseBoolean(args[3]);
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		java.util.LinkedHashMap<String, Integer> classes = new java.util.LinkedHashMap<>();
		java.util.LinkedHashMap<String, String> firstWhere = new java.util.LinkedHashMap<>();
		java.util.LinkedHashMap<String, String> firstTrace = new java.util.LinkedHashMap<>();
		int failures = 0;
		FUZZ_PROGRESS = selectedSeeds != null;
		if (selectedSeeds != null) seeds = selectedSeeds.length;
		for (int ordinal = 0; ordinal < seeds; ordinal++) {
			int seed = selectedSeeds == null ? firstSeed + ordinal : selectedSeeds[ordinal];
			Failure failure = runExpandedRandomPlay(registries, seed, actions);
			if (selectedSeeds != null) System.out.println("seed=" + seed + (failure == null ? " PASS" : " FAIL"));
			if ((ordinal + 1) % 32 == 0) System.out.println("... fuzz progress at seed " + seed
					+ ", failures=" + (failures + (failure == null ? 0 : 1)));
			if (failure == null) continue;
			if (selectedSeeds != null) failure.cause().printStackTrace(System.out);
			failures++;
			String root = rootCause(failure.cause());
			classes.merge(root, 1, Integer::sum);
			firstWhere.putIfAbsent(root, "seed=" + seed + " action=" + failure.actionIndex());
			firstTrace.putIfAbsent(root, failure.trace().toString());
		}
		System.out.println("randomized normal play finished; seeds=" + seeds + " failures=" + failures);
		for (var entry : classes.entrySet()) {
			System.out.println("FUZZ CLASS x" + entry.getValue() + " @ " + firstWhere.get(entry.getKey())
					+ " :: " + entry.getKey());
			System.out.println("  first trace=" + firstTrace.get(entry.getKey()));
		}
		check(failures == 0, "randomized normal play found " + failures + " interrupted seeds");
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
		}
		return null;
	}

	private static Failure runExpandedRandomPlay(
			FullCapabilityTestFixture.Registries registries, int seed, int actionCount
	) throws Exception {
		NormalPlayState state = new NormalPlayState(registries);
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
			if (FUZZ_PROGRESS && (index + 1) % 8 == 0) System.out.println(
					"seed=" + seed + " actions=" + (index + 1) + "/" + actionCount + " generation=" + state.generation);
		}
		return null;
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
			if (state.tryInsertLiquidAt(randomCorner(random), randomCorner(random), randomCorner(random), state.water)) {
				return "insertWater";
			}
			return "skip(insertWater)";
		}
		if (pick < 10) {
			if (state.tryInsertLiquidAt(randomCorner(random), randomCorner(random), randomCorner(random), state.mercury)) {
				return "insertMercury";
			}
			return "skip(insertMercury)";
		}
		if (pick < 15) {
			if (state.tryInsertLiquidAt(randomCorner(random), randomCorner(random), randomCorner(random), state.oil)) {
				return "insertOil";
			}
			return "skip(insertOil)";
		}
		if (pick < 22) {
			int x = randomCorner(random), y = randomCorner(random), z = randomCorner(random);
			for (int bucket = 0, count = 1 + random.nextInt(2); bucket < count; bucket++) {
				if (!state.tryInsertChemicalAt(x, y, z, state.acid, state.acidEnergy)) return "skip(insertAcid)";
			}
			return "insertAcid";
		}
		if (pick < 29) {
			int x = randomCorner(random), y = randomCorner(random), z = randomCorner(random);
			for (int bucket = 0, count = 1 + random.nextInt(2); bucket < count; bucket++) {
				if (!state.tryInsertChemicalAt(x, y, z, state.base, state.baseEnergy)) return "skip(insertBase)";
			}
			return "insertBase";
		}
		if (pick < 35) {
			if (!FUZZ_DISABLE_GRANULAR) {
				if (state.tryInsertSandAt(randomCorner(random), randomCorner(random), randomCorner(random))) {
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
			var edit = state.domain.editIndicatorFloodFill(7, 7, 7, random.nextBoolean(), 512, 100.0);
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

	private static int randomCorner(java.util.Random random) { return 5 + random.nextInt(3); }

	private static String burst(NormalPlayState state, java.util.Random random) {
		int mode = random.nextInt(5);
		int count = 2 + random.nextInt(mode == 4 ? 4 : 8);
		for (int bucket = 0; bucket < count; bucket++) {
			int x = randomCorner(random), y = randomCorner(random), z = randomCorner(random);
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

	private static final double[] RANDOM_TEMPERATURES = {
			1.0, 50.0, 150.0, 273.15, 293.15, 320.0, 373.15, 600.0, 1_500.0, 3_000.0
	};

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
