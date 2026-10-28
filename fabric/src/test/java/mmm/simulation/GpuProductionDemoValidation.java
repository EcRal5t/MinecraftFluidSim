package mmm.simulation;

import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import mmm.application.RegionApplicationService;
import mmm.application.RegionRuntime;
import mmm.application.RegionTickUseCase;
import mmm.application.command.MotionStepCommand;
import mmm.fluid.FluidDomain;
import mmm.simulation.persistence.WorldCapabilityBundleStore;
import mmm.worldprofile.WorldCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/** Offline production services with an empty, fully loaded world input. */
public final class GpuProductionDemoValidation {
	private GpuProductionDemoValidation() { }
	private static PhysicalObservation lastOutcome;
	private static MassBaseline massBaseline;
	private static double bulkWaterTemperatureK;
	private static long reactionWaterQuanta;
	private static List<mmm.inventory.LiquidCellInventory.Stock> firstTensionStocks;

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && args[0].equals("tension")) {
			firstTensionStocks = null;
			String[] variants = args.clone();
			variants[0] = "tension30";
			run(variants);
			var shallow = lastOutcome;
			variants[0] = "tension120";
			run(variants);
			var steep = lastOutcome;
			String stop = args.length < 3 ? "complete" : args[2];
			if (stop.equals("complete")) {
				require(shallow != null && steep != null && shallow.wetArea() > steep.wetArea()
						&& shallow.waterY() < steep.waterY(),
						"GPU wetting evolution did not produce a wider, lower 30-degree droplet");
				System.out.printf("stage=wetting-gpu-outcome degrees30_area_m2=%.6g degrees120_area_m2=%.6g degrees30_height_cells=%.6g degrees120_height_cells=%.6g%n",
						shallow.wetArea(), steep.wetArea(), shallow.waterY(), steep.waterY());
			}
			return;
		}
		run(args);
	}

	private static void run(String[] args) throws Exception {
		lastOutcome = null;
		String scenario = args.length == 0 ? "demo" : args[0];
		int steps = args.length < 2 ? 1 : Integer.parseInt(args[1]);
		String stop = args.length < 3 ? "complete" : args[2];
		if (!Set.of("demo", "material", "chemistry", "tension30", "tension120", "air", "vacuum", "controller", "capture", "takeover").contains(scenario)
				|| !Set.of("initial", "motion", "scheduled", "complete").contains(stop) || steps < 1 || args.length > 3)
			throw new IllegalArgumentException("Expected demo/material/chemistry/tension/air/vacuum, positive step count, and initial/motion/scheduled/complete");
		if (scenario.equals("demo") && stop.equals("complete") && steps < 13)
			throw new IllegalArgumentException("Complete demo requires at least 13 scheduled steps for player entry, movement and exit");
		System.out.println("stage=startup scenario=" + scenario + " scheduled_steps=" + steps);
		var definitions = ProductionRegistries.load();
		var startup = WorldStartupCoordinator.start(Files.createTempDirectory("gpu-demo-world-"), () ->
				new WorldCapabilityBundleStore.Definitions(EnumSet.allOf(WorldCapability.class), definitions.material(),
						definitions.chemical(), definitions.granular(), definitions.gas(), definitions.thermal()));
		require(!startup.creationLocked(), "World startup failed: " + startup.blockingSummary());
		var world = new CommittedWorldRegistries(startup);
		var manager = new FluidRegionManager(startup.config().limits());
		if (scenario.equals("controller")) {
			runController(startup, world, manager, steps, stop);
			return;
		}
		if (scenario.equals("capture") || scenario.equals("takeover")) {
			runCapture(startup, world, manager, steps, stop, scenario.equals("takeover"));
			return;
		}
		var domain = scenario.startsWith("tension") ? FluidRegionFactory.createTensionDomain()
				: Set.of("air", "vacuum").contains(scenario) ? FluidRegionFactory.createDebugDomain()
				: FluidRegionFactory.createDomain(MinecraftDebugFixtures.BLOCK_SIZE_X,
						MinecraftDebugFixtures.BLOCK_SIZE_Y, MinecraftDebugFixtures.BLOCK_SIZE_Z);
		var region = new FluidRegion(UUID.randomUUID(), ResourceKey.create(Registries.DIMENSION,
				Identifier.fromNamespaceAndPath("mmm", "gpu_demo_validation")), BlockPos.ZERO, domain);
		require(manager.addAndSelect(region).accepted(), "Demo region admission failed");
		System.out.println("stage=collision-input scenario=" + scenario);
		captureCollisions(region, scenario);
		domain = region.domain();
		if (scenario.startsWith("tension")) {
			int angle = scenario.equals("tension30") ? 30 : 120;
			var material = DebugScenarioInitialization.tensionDefinitions(world.materialRegistry(), angle);
			DebugScenarioInitialization.bindTensionPalette(domain, material);
			DebugScenarioInitialization.fillTensionCap(domain);
			world.bindConfiguredState(domain, startup.config().maximumThermalSubstepsPerStep(), material);
		} else {
			FluidRegionFactory.bindDefaultMaterialState(domain);
			if (scenario.equals("air") || scenario.equals("vacuum")) DebugScenarioInitialization.fillAirWater(domain);
			else DebugScenarioInitialization.fillDam(domain, scenario.equals("material"));
			world.bindConfiguredState(domain, startup.config().maximumThermalSubstepsPerStep());
			if (scenario.equals("chemistry")) DebugScenarioInitialization.assignChemistry(domain);
		}
		require(domain.totalVolumeExact() > 0, "Scenario contains no liquid");
		if (Set.of("demo", "material", "chemistry").contains(scenario))
			require(domain.totalVolumeExact() == 2592.0, "Dam-break inventory differs from empty-world command");
		long initialMass = domain.totalMassUnits();
		var registries = startup.registries();
		var operations = new FluidRegionOperations(startup.worldRoot(), manager, startup.creationLocked(),
				startup.profileProven(), startup.profile(), registries.chemical(), registries.granular(),
				registries.thermal(), registries.gas(), startup.config().maximumThermalSubstepsPerStep(), registries.material());
		var snapshots = new FluidSnapshotPublisher(world.profile(), world.chemicalRegistry(),
				world.granularRegistry(), world.thermalRegistry());
		try (var backend = MmmRuntimeFactory.createSolverBackend()) {
			MmmRuntimeFactory.prepareSolverBackend(backend, startup, world);
			require(backend.id().equals("mmm:vulkan-resident"), "Production Vulkan backend was not selected: " + backend.id());
			System.out.println("stage=backend " + backend.executionDescription());
			var application = new RegionApplicationService(backend, new FluidRegionRepository(manager), operations);
			var motion = new MinecraftMotionExecutor(application, world, new MinecraftRuntimePolicy(startup, manager));
			if (scenario.equals("air") || scenario.equals("vacuum")) {
				System.out.println("stage=air-prime-motion");
				long coldStart = System.nanoTime();
				prepareAvailability(region);
				require(motion.step(region, List.of()).accepted(), "Air topology initialization rejected");
				System.out.printf("stage=air-prime-motion cold_import_scope_ms=%.3f%n", (System.nanoTime() - coldStart) / 1e6);
				var editable = region.domain();
				var gas = editable.gasBoundary();
				require(gas.nodeCount() > 0, "Air scaffold did not create a sealed gas chamber");
				int node = DebugScenarioInitialization.presetAirPressure(editable, scenario.equals("vacuum"));
				require(node > 0, "Air scaffold does not have a sealed left chamber");
				double pressure = gas.nodeGaugePressurePa(node);
				require(scenario.equals("vacuum") ? pressure < 0 : pressure > 0, "Gas preset has wrong pressure direction");
				initialMass = editable.totalMassUnits();
				System.out.println("stage=air-preset pressure_pa=" + pressure);
			}
			System.out.println("stage=initial-snapshot");
			snapshots.createDispatch(region);
			checkScenario(region, scenario, true);
			var initialState = region.domain().captureAuthoritativeState();
			if (scenario.startsWith("tension")) {
				require(region.domain().maximumParticleSpeed() == 0, "Wetting scenario initial liquid must be stationary");
				var stocks = initialState.liquid().physicalStocks();
				if (firstTensionStocks == null) firstTensionStocks = stocks;
				else require(firstTensionStocks.equals(stocks), "Wetting angles must start from identical liquid inventories");
			}
			massBaseline = baselineMass(initialState);
			var baseline = measure(initialState, scenario);
			if (stop.equals("initial")) return;
			if (stop.equals("motion")) {
				System.out.println("stage=command-motion");
				long coldStart = System.nanoTime();
				prepareAvailability(region);
				require(motion.step(region, List.of()).accepted(), "Command motion rejected");
				System.out.printf("stage=command-motion import_scope_ms=%.3f%n", (System.nanoTime() - coldStart) / 1e6);
				checkResident(region, snapshots, initialMass, scenario, "command-motion");
				region.domain();
				return;
			}
			var ticks = new RegionTickUseCase(application, RegionTickUseCase.Policy.production());
			verifyPauseRun(region, ticks);
			scheduledAdvance(startup, region, snapshots, ticks, steps, initialMass, scenario);
			if (stop.equals("scheduled")) { region.domain(); return; }
			System.out.println("stage=export-restore-edit");
			long residentMass = region.inventorySummary().liquidMassUnits();
			var checkpoint = new MinecraftRegionCheckpointSource(region);
			require(checkpoint.hasMaterialState() && checkpoint.hasChemicalState() && checkpoint.hasGranularState()
					&& checkpoint.hasThermalState() && checkpoint.gasState(world.gasRegistry().fingerprint()) != null
					&& checkpoint.thermalState() != null && region.deviceResident(),
					"Checkpoint source lost a capability or displaced the device state");
			var restored = FluidDomain.restoreAuthoritativeState(checkpoint.authoritativeState());
			require(restored.totalMassUnits() == residentMass, "Export/restore changed liquid mass");
			System.out.println("stage=physical-outcome scenario=" + scenario);
			lastOutcome = measure(checkpoint.authoritativeState(), scenario);
			checkOutcome(scenario, baseline, lastOutcome);
			verifyManualSteps(region, ticks, motion, snapshots, initialMass, scenario);
			residentMass = region.inventorySummary().liquidMassUnits();
			var editable = region.domain();
			require(editable.totalMassUnits() == residentMass, "Materialization changed liquid mass");
			if (editable.hasPendingPhaseMotionWork()) {
				System.out.println("stage=edit-settle-pending-phase-work");
				motion.settlePendingPhaseWork(region);
				editable = region.domain();
				residentMass = editable.totalMassUnits();
			}
			var inserted = insertWaterAtAvailableCell(editable, world);
			require(inserted.transferred() && editable.totalMassUnits() == Math.addExact(residentMass, inserted.massUnits()),
					"Exact water edit did not publish its complete mass");
			long editedMass = editable.totalMassUnits();
			massBaseline = baselineMass(editable.captureAuthoritativeState());
			System.out.println("stage=post-edit-motion");
			require(motion.step(region, List.of()).accepted(), "Post-edit motion rejected");
			checkResident(region, snapshots, editedMass, scenario, "post-edit-motion");
			region.domain(); // Release the retained device generation before closing its backend.
		}
		System.out.println("stage=complete scenario=" + scenario);
	}

	private static FluidDomain.ExactVolumeTransfer insertWaterAtAvailableCell(FluidDomain domain, CommittedWorldRegistries world) {
		float volume = 0.125f;
		int water = domain.materialRegistry().handle("mmm:water");
		long mass = domain.unitScale().massUnitsForNormalized(volume * domain.materialRegistry().material(water).referenceDensity());
		long nanos = Math.round(domain.unitScale().cubicMeters(volume) * 1_000_000_000.0);
		var mixture = new mmm.transfer.FluidMixture(List.of(new mmm.transfer.FluidMixture.Component("mmm:water", nanos, mass)));
		double energy = mmm.transfer.LiquidContainerThermodynamics.materialEnergy(world.thermalRegistry(),
				world.hasCapability(WorldCapability.CHEMISTRY) ? world.chemicalRegistry() : null,
				mixture, mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K);
		int candidates = 0;
		for (int y = 0; y < domain.sizeY(); y++) for (int z = 0; z < domain.sizeZ(); z++) for (int x = 0; x < domain.sizeX(); x++) {
			int cell = (z * domain.sizeY() + y) * domain.sizeX() + x;
			if (domain.isSolid(x, y, z) || !domain.groupAvailable(cell) || domain.volume(x, y, z) > 0.75f) continue;
			candidates++;
			var result = domain.tryInsertExactVolume(x, y, z, volume, 0, water, true, energy);
			if (result.transferred()) {
				System.out.printf("stage=edit-insert cell=%d,%d,%d volume=%.6g mass=%d%n", x, y, z, result.volume(), result.massUnits());
				return result;
			}
		}
		throw new IllegalStateException("No available cell accepted the exact edit: candidates=" + candidates
				+ ", pendingPhase=" + domain.hasPendingPhaseMotionWork() + ", liquid=" + domain.totalVolumeExact()
				+ ", open=" + domain.totalOpenVolumeExact());
	}

	private static void runController(WorldStartupCoordinator.Result startup, CommittedWorldRegistries world,
			FluidRegionManager manager, int steps, String stop) throws Exception {
		var registries = startup.registries();
		var operations = new FluidRegionOperations(startup.worldRoot(), manager, startup.creationLocked(),
				startup.profileProven(), startup.profile(), registries.chemical(), registries.granular(),
				registries.thermal(), registries.gas(), startup.config().maximumThermalSubstepsPerStep(), registries.material());
		System.out.println("stage=controller-scan-activate-checkpoint");
		var fixture = GpuControllerFixture.create(startup, world, manager, operations);
		var region = fixture.region;
		require(region.checkpointGeneration() > 0 && region.liquidVolume() == 0,
				"Controller initial durable state is not empty");
		System.out.println("stage=controller-pour-wal");
		fixture.pour();
		long mass = region.domain().totalMassUnits();
		require(mass > 0, "Controller pour contains no liquid mass");
		massBaseline = baselineMass(region.domain().captureAuthoritativeState());
		var snapshots = new FluidSnapshotPublisher(world.profile(), world.chemicalRegistry(), world.granularRegistry(), world.thermalRegistry());
		snapshots.createDispatch(region);
		if (stop.equals("initial")) return;
		try (var backend = MmmRuntimeFactory.createSolverBackend()) {
			MmmRuntimeFactory.prepareSolverBackend(backend, startup, world);
			require(backend.id().equals("mmm:vulkan-resident"), "Controller did not select GPU backend");
			var application = new RegionApplicationService(backend, new FluidRegionRepository(manager), operations);
			var ticks = new RegionTickUseCase(application, RegionTickUseCase.Policy.production());
			var motion = new MinecraftMotionExecutor(application, world, new MinecraftRuntimePolicy(startup, manager));
			verifyPauseRun(region, ticks);
			scheduledAdvance(startup, region, snapshots, ticks, stop.equals("motion") ? 1 : steps, mass, "controller");
			if (!stop.equals("motion") && !stop.equals("scheduled")) {
				verifyManualSteps(region, ticks, motion, snapshots, mass, "controller");
				motion.settlePendingPhaseWork(region);
				long beforeScoop = region.domain().totalMassUnits();
				System.out.println("stage=controller-scoop-wal");
				fixture.scoop();
				long afterScoop = region.domain().totalMassUnits();
				require(afterScoop < beforeScoop, "Controller scoop did not transfer liquid");
				massBaseline = baselineMass(region.domain().captureAuthoritativeState());
				prepareAvailability(region);
				require(motion.step(region, List.of()).accepted(), "Controller post-scoop motion rejected");
				checkResident(region, snapshots, afterScoop, "controller", "controller-post-scoop-motion");
				operations.checkpoint(region);
			}
			region.domain();
		}
		System.out.println("stage=complete scenario=controller");
	}

	private static void verifyPauseRun(FluidRegion region, RegionTickUseCase ticks) {
		prepareAvailability(region);
		require(MinecraftRegionOperatorActions.setPaused(region, true, () -> true), "Pause rejected");
		var paused = new RegionRuntime(1, 1, 0, 0, region.canStep(), region.canStepThermal());
		require(region.paused() && ticks.plan(paused).axis() == RegionTickUseCase.Axis.NONE,
				"Paused region scheduled work");
		require(MinecraftRegionOperatorActions.setPaused(region, false, () -> { prepareAvailability(region); return true; }),
				"Run rejected");
		require(!region.paused() && region.canStep(), "Run did not restore motion eligibility");
		System.out.println("stage=pause-run paused_plan=NONE run_eligible=true");
	}

	private static void scheduledAdvance(WorldStartupCoordinator.Result startup, FluidRegion region,
			FluidSnapshotPublisher snapshots, RegionTickUseCase ticks, int steps, long mass, String scenario) {
		var pacingState = new RegionRuntime(0, 1, 0, 0, region.canStep(), region.canStepThermal());
		int completed = 0;
		long tick = 0, entryTick = 0;
		var playerId = UUID.fromString("82df17c5-590e-4a92-ae41-1a126f090041");
		while (completed < steps) {
			tick++;
			boolean coldImport = !region.deviceResident();
			prepareAvailability(region);
			long tickStart = System.nanoTime();
			prepareAvailability(region);
			var runtime = new RegionRuntime(tick, pacingState.stepIntervalTicks(), pacingState.hardOverrunStreak(),
					pacingState.recoveryStreak(), region.canStep(), region.canStepThermal());
			var plan = ticks.plan(runtime);
			if (plan.axis() == RegionTickUseCase.Axis.NONE) {
				require(region.canStep() || region.canStepThermal(), "Scheduled region has no eligible work");
				continue;
			}
			require(plan.axis() == RegionTickUseCase.Axis.MOTION, "Unexpected scheduled axis " + plan.axis());
			int step = completed + 1;
			boolean playerPresent = scenario.equals("demo") && step >= 5 && step <= 12;
			List<mmm.world.MovingBodyFluidCoupling.Request> bodies = List.of();
			if (playerPresent) {
				if (step == 5) entryTick = tick;
				double shift = (tick - entryTick) * .002;
				var bounds = new mmm.world.MovingBodyFluidCoupling.Bounds(2.1 + shift, 1, 3.1, 2.7 + shift, 2.8, 3.7);
				var displacement = step == 5 ? null : new net.minecraft.world.phys.Vec3(.002, 0, 0);
				bodies = MinecraftRegionInputs.movingBodyRequests(List.of(
						new MinecraftRegionInputs.BodyMotion(playerId, bounds, bounds.volume(), displacement)));
				require(bodies.size() == 1 && (step == 5 ? bodies.getFirst().velocityXMetresPerSecond() == 0
						: bodies.getFirst().velocityXMetresPerSecond() > 0), "Player observation has incorrect motion");
			}
			var result = ticks.executeMotion(plan, new MotionStepCommand(region.id(), region.solverGeneration(),
					bodies, true, startup.config().maximumPhaseOperationsPerStep(), false));
			require(result.result().accepted(), "Scheduled motion rejected: " + result.result().status());
			double elapsed = (System.nanoTime() - tickStart) / 1e6;
			if (playerPresent) {
				var coupling = result.result().movingBodyCoupling();
				require(coupling.coupledVolumeCubicMetres() > 0 && coupling.bodyImpulses().stream()
						.anyMatch(impulse -> impulse.bodyId().equals(playerId)), "Player-size body lost wet coupling");
				System.out.printf("stage=player-body step=%d x=%.4f velocity=%.4f wet_m3=%.6g%n",
						step, bodies.getFirst().bounds().minimumX(), bodies.getFirst().velocityXMetresPerSecond(),
						coupling.coupledVolumeCubicMetres());
			} else if (scenario.equals("demo") && step == 13) {
				require(result.result().movingBodyCoupling().bodyImpulses().stream()
						.noneMatch(impulse -> impulse.bodyId().equals(playerId)), "Player impulse remained after exit");
				try (var capture = region.captureSolverState()) {
					require(capture.exportState().entityBodyState().bodies().stream()
							.noneMatch(body -> body.bodyId().equals(playerId)), "Player occupancy remained after exit");
				}
				System.out.println("stage=player-body-exit occupancy_released=true");
			}
			var pacing = ticks.paceAfterAcceptedStep(runtime, elapsed);
			pacingState = pacing.runtime();
			completed++;
			System.out.printf("stage=pacing scenario=%s step=%d tick=%d cold_import=%s offline_scope_ms=%.3f interval=%d hard_streak=%d recovery=%d auto_pause=%s%n",
					scenario, completed, tick, coldImport, elapsed, pacingState.stepIntervalTicks(),
					pacingState.hardOverrunStreak(), pacingState.recoveryStreak(), pacing.autoPause());
			checkResident(region, snapshots, mass, scenario, "scheduled-motion");
			if (completed == 1 && scenario.startsWith("tension"))
				require(region.maximumSpeed() > 0, "Wetting force did not produce motion from the stationary initial cap");
			if (pacing.autoPause()) throw new IllegalStateException("Production pacing paused after "
					+ pacingState.hardOverrunStreak() + " consecutive slow steps");
		}
	}

	private static void verifyManualSteps(FluidRegion region, RegionTickUseCase ticks, MinecraftMotionExecutor motion,
			FluidSnapshotPublisher snapshots, long mass, String scenario) {
		require(MinecraftRegionOperatorActions.setPaused(region, true, () -> true), "Manual-step pause rejected");
		prepareAvailability(region);
		long before = region.solverGeneration();
		MinecraftRegionOperatorActions.stepRegion(region, 2, motion);
		require(region.solverGeneration() == before + 2 && region.paused(), "Manual steps did not preserve pause intent");
		var paused = new RegionRuntime(1, 1, 0, 0, region.canStep(), region.canStepThermal());
		require(ticks.plan(paused).axis() == RegionTickUseCase.Axis.NONE, "Manual steps resumed scheduled ticking");
		checkResident(region, snapshots, mass, scenario, "manual-two-steps");
		require(MinecraftRegionOperatorActions.setPaused(region, false, () -> { prepareAvailability(region); return true; }),
				"Run after manual steps rejected");
		require(!region.paused() && region.canStep(), "Run after manual steps did not restore motion eligibility");
		System.out.println("stage=manual-step count=2 paused_intent_preserved=true run_eligible=true");
	}

	private static void runCapture(WorldStartupCoordinator.Result startup, CommittedWorldRegistries world,
			FluidRegionManager manager, int steps, String stop, boolean takeover) throws Exception {
		String scenario = takeover ? "takeover" : "capture";
		var registries = startup.registries();
		var operations = new FluidRegionOperations(startup.worldRoot(), manager, startup.creationLocked(),
				startup.profileProven(), startup.profile(), registries.chemical(), registries.granular(),
				registries.thermal(), registries.gas(), startup.config().maximumThermalSubstepsPerStep(), registries.material());
		System.out.println("stage=capture-world scenario=" + scenario);
		var fixture = GpuCaptureFixture.create(startup, world, manager, operations, takeover);
		var region = fixture.region();
		try {
		captureCollisions(region, scenario);
		var initial = region.domain().captureAuthoritativeState();
		massBaseline = baselineMass(initial);
		long mass = region.domain().totalMassUnits();
		var snapshots = new FluidSnapshotPublisher(world.profile(), world.chemicalRegistry(), world.granularRegistry(), world.thermalRegistry());
		snapshots.createDispatch(region);
		fixture.verifyCaptured();
		System.out.printf("stage=capture-initial scenario=%s liquid_cell_volume=%.6f mass=%d%n", scenario, fixture.sampledVolume(), mass);
			if (stop.equals("initial")) return;
			try (var backend = MmmRuntimeFactory.createSolverBackend()) {
				MmmRuntimeFactory.prepareSolverBackend(backend, startup, world);
				require(backend.id().equals("mmm:vulkan-resident"), "Capture did not select GPU backend");
				var application = new RegionApplicationService(backend, new FluidRegionRepository(manager), operations);
				var motion = new MinecraftMotionExecutor(application, world, new MinecraftRuntimePolicy(startup, manager));
				var ticks = new RegionTickUseCase(application, RegionTickUseCase.Policy.production());
				verifyPauseRun(region, ticks);
				scheduledAdvance(startup, region, snapshots, ticks, stop.equals("motion") ? 1 : steps, mass, scenario);
				fixture.verifyCaptured();
				if (stop.equals("complete")) {
					var state = new MinecraftRegionCheckpointSource(region).authoritativeState();
					require(!initial.liquid().physicalStocks().equals(state.liquid().physicalStocks()) && region.maximumSpeed() > 0,
							"Captured liquid did not advance on GPU");
					verifyManualSteps(region, ticks, motion, snapshots, mass, scenario);
					fixture.verifyCaptured();
				}
				region.domain();
			}
		} finally {
			fixture.restore();
			System.out.println("stage=capture-restored-released scenario=" + scenario);
		}
		System.out.println("stage=complete scenario=" + scenario);
	}

	private static void checkResident(FluidRegion region, FluidSnapshotPublisher snapshots, long expectedMass, String scenario, String stage) {
		long validationStarted = System.nanoTime();
		require(region.deviceResident(), stage + ": adopted state is not resident on the GPU");
		var summary = region.inventorySummary();
		long massDelta = checkChangedMass(region, summary, stage);
		require(region.thermalAuditClosed(), stage + ": thermal audit did not close");
		checkScenario(region, scenario, false);
		snapshots.createDispatch(region);
		String status = RegionDiagnosticsReport.residentStatus(region);
		require(!status.isBlank() && region.deviceResident(), stage + ": status lost the device state");
		System.out.printf("stage=%s generation=%d liquid_mass=%d total_mass_delta=%d water_temperature_k=%.6f max_speed=%.6g resident=%s validation_scope_ms=%.3f%n",
				stage, region.solverGeneration(), summary.liquidMassUnits(), massDelta, bulkWaterTemperatureK, region.maximumSpeed(),
				region.deviceResident(), (System.nanoTime() - validationStarted) / 1e6);
	}

	private record MassBaseline(long liquid, long gas, long granular, long coherent,
			mmm.fluid.air.GasStateSnapshot gasState, mmm.chemistry.ChemicalTransferLedger chemicalSource,
			mmm.chemistry.ChemicalTransferLedger chemicalSink, mmm.granular.GranularAuditLedger granularSource,
			mmm.granular.GranularAuditLedger granularSink, java.util.Map<String, Long> species) { }

	private static MassBaseline baselineMass(mmm.fluid.FluidDomainState state) {
		long liquid = 0, gas = 0, granular = 0, coherent = 0;
		for (var stock : state.liquid().physicalStocks()) liquid = Math.addExact(liquid, stock.massUnits());
		var gasState = state.gas();
		if (gasState != null) for (var node : gasState.nodes()) gas = Math.addExact(gas, node.inventory().totalMassUnits());
		if (state.granular() != null) {
			for (var parcel : state.granular().activeParcels()) granular = Math.addExact(granular, parcel.massUnits());
			for (var group : state.granular().depositGroups()) granular = Math.addExact(granular, group.massUnits());
		}
		if (state.thermalOwner() != null) for (var group : state.thermalOwner().coherentGroups())
			coherent = Math.addExact(coherent, group.massUnits());
		var solid = state.granular();
		return new MassBaseline(liquid, gas, granular, coherent, gasState, state.chemicalSource(), state.chemicalSink(),
				solid == null ? mmm.granular.GranularAuditLedger.EMPTY : solid.externalSource(),
				solid == null ? mmm.granular.GranularAuditLedger.EMPTY : solid.escapedSink(), speciesTotals(state));
	}

	private static java.util.Map<String, Long> liquidSpeciesTotals(mmm.fluid.FluidDomainState state) {
		var totals = new java.util.TreeMap<String, Long>();
		var registry = state.chemicalRegistry();
		if (registry != null) for (var stock : state.liquid().physicalStocks()) if (stock.mixture() != null)
			stock.mixture().stableAmounts(registry).forEach((id, amount) -> totals.merge(id, amount, Math::addExact));
		return java.util.Map.copyOf(totals);
	}

	private static java.util.Map<String, Long> condensedSpeciesTotals(mmm.fluid.FluidDomainState state) {
		var totals = new java.util.TreeMap<String, Long>(liquidSpeciesTotals(state));
		var granular = state.granular();
		if (granular != null) {
			for (var parcel : granular.activeParcels())
				require(parcel.solidCompositionIndex() != 0, "Cannot audit elements of an uncomposed granular parcel");
			for (var group : granular.depositGroups())
				require(group.solidCompositionIndex() != 0, "Cannot audit elements of an uncomposed granular deposit");
			granular.solidSpeciesTotals().forEach((id, amount) -> totals.merge(id, amount, Math::addExact));
		}
		if (state.thermalOwner() != null) for (var group : state.thermalOwner().coherentGroups()) {
			require(group.composition() != null, "Cannot audit elements of an uncomposed continuous solid");
			group.composition().stableAmounts().forEach((id, amount) -> totals.merge(id, amount, Math::addExact));
		}
		return java.util.Map.copyOf(totals);
	}

	private static java.util.Map<String, Long> speciesTotals(mmm.fluid.FluidDomainState state) {
		var totals = new java.util.TreeMap<String, Long>(condensedSpeciesTotals(state));
		var gas = state.gas();
		if (gas != null) for (var node : gas.nodes()) for (var component : node.inventory().components())
			for (var species : component.species()) totals.merge(species.speciesId(), species.amountQuanta(), Math::addExact);
		return java.util.Map.copyOf(totals);
	}

	private static long checkChangedMass(FluidRegion region, mmm.fluid.ResidentFluidState.InventorySummary current, String stage) {
		var before = massBaseline;
		long liquidDelta = current.liquidMassUnits() - before.liquid();
		long gasDelta = current.gasMassUnits() - before.gas();
		long granularDelta = current.granularMassUnits() - before.granular();
		long coherentDelta = current.coherentMassUnits() - before.coherent();
		mmm.fluid.FluidDomainState state;
		try (var capture = region.captureSolverState()) { state = capture.exportState(); }
		catch (RuntimeException failure) {
			System.out.printf("stage=mass-audit-export-failed where=%s liquid_delta=%d gas_delta=%d granular_delta=%d coherent_delta=%d%n",
					stage, liquidDelta, gasDelta, granularDelta, coherentDelta);
			throw failure;
		}
		checkBulkWaterTemperature(state, stage);
		var afterGas = state.gas();
		var beforeGas = before.gasState();
		long totalDelta = Math.addExact(Math.addExact(liquidDelta, gasDelta), Math.addExact(granularDelta, coherentDelta));
		long chemicalExchange = Math.subtractExact(state.chemicalSource().totalMassUnits(), before.chemicalSource().totalMassUnits())
				- Math.subtractExact(state.chemicalSink().totalMassUnits(), before.chemicalSink().totalMassUnits());
		var afterSpecies = speciesTotals(state);
		var changes = new java.util.TreeMap<String, Long>();
		long gasExternalExchange = 0;
		if (beforeGas != null) {
			gasExternalExchange += gasExchange(beforeGas.externalSource(), -1, changes);
			gasExternalExchange += gasExchange(beforeGas.escapedSink(), 1, changes);
		}
		if (afterGas != null) {
			gasExternalExchange += gasExchange(afterGas.externalSource(), 1, changes);
			gasExternalExchange += gasExchange(afterGas.escapedSink(), -1, changes);
		}
		var chemicalChanges = new java.util.TreeMap<String, Long>();
		chemicalExchange(before.chemicalSource(), -1, chemicalChanges);
		chemicalExchange(before.chemicalSink(), 1, chemicalChanges);
		chemicalExchange(state.chemicalSource(), 1, chemicalChanges);
		chemicalExchange(state.chemicalSink(), -1, chemicalChanges);
		chemicalChanges.forEach((id, amount) -> changes.merge(id, amount, Math::addExact));
		var solid = state.granular();
		var solidSource = solid == null ? mmm.granular.GranularAuditLedger.EMPTY : solid.externalSource();
		var solidSink = solid == null ? mmm.granular.GranularAuditLedger.EMPTY : solid.escapedSink();
		long granularExchange = Math.addExact(Math.addExact(granularExchange(before.granularSource(), -1, changes),
				granularExchange(before.granularSink(), 1, changes)), Math.addExact(granularExchange(solidSource, 1, changes),
				granularExchange(solidSink, -1, changes)));
		var expectedSpecies = new java.util.TreeMap<>(before.species());
		changes.forEach((id, amount) -> expectedSpecies.merge(id, amount, Math::addExact));
		expectedSpecies.values().removeIf(amount -> amount == 0);
		reactionWaterQuanta = Math.subtractExact(afterSpecies.getOrDefault("mmm:h2o", 0L), expectedSpecies.getOrDefault("mmm:h2o", 0L));
		boolean speciesReconciled = expectedSpecies.equals(afterSpecies) || elementTotals(state.chemicalRegistry(), expectedSpecies)
						.equals(elementTotals(state.chemicalRegistry(), afterSpecies));
		long expectedMass = Math.addExact(Math.addExact(chemicalExchange, gasExternalExchange), granularExchange);
		if (totalDelta != expectedMass || !speciesReconciled) {
			System.out.printf("stage=mass-audit-failed where=%s liquid_delta=%d gas_delta=%d granular_delta=%d coherent_delta=%d total_delta=%d chemical_external_delta=%d gas_external_exchange=%d granular_external_exchange=%d species_reconciled=%s%n",
					stage, liquidDelta, gasDelta, granularDelta, coherentDelta, totalDelta, chemicalExchange, gasExternalExchange, granularExchange, speciesReconciled);
			System.out.println("stage=mass-audit-ledger gas_source_before=" + (beforeGas == null ? List.of() : beforeGas.externalSource().entries())
					+ " gas_source_after=" + (afterGas == null ? List.of() : afterGas.externalSource().entries())
					+ " gas_sink_before=" + (beforeGas == null ? List.of() : beforeGas.escapedSink().entries())
					+ " gas_sink_after=" + (afterGas == null ? List.of() : afterGas.escapedSink().entries())
					+ " chemical_source_before=" + before.chemicalSource().groups() + " chemical_source_after=" + state.chemicalSource().groups()
					+ " chemical_sink_before=" + before.chemicalSink().groups() + " chemical_sink_after=" + state.chemicalSink().groups()
					+ " granular_source_before=" + before.granularSource().entries() + " granular_source_after=" + solidSource.entries()
					+ " granular_sink_before=" + before.granularSink().entries() + " granular_sink_after=" + solidSink.entries());
			System.out.println("stage=mass-audit-species before=" + before.species() + " expected=" + expectedSpecies + " after=" + afterSpecies);
			throw new AssertionError(stage + ": cannot reconcile cross-phase mass and external exchanges from the available gas ledger");
		}
		return totalDelta;
	}

	/** These room-temperature demos have no imposed heater or refrigerator. */
	private static void checkBulkWaterTemperature(mmm.fluid.FluidDomainState state, String stage) {
		var function = new mmm.thermal.ThermalStateFunction(state.thermalRegistry());
		int thermal = state.thermalRegistry().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID, "mmm:water");
		double waterMass = 0, temperatureMoment = 0;
		for (var stock : state.liquid().physicalStocks()) {
			if (stock.massUnits() == 0 || !state.materialPalette().get(stock.key().material()).id().equals("mmm:water")) continue;
			double mass = stock.massKilograms();
			double temperature = function.stateForInternalEnergy(thermal, mass,
					stock.mixture() == null ? java.util.Map.of() : stock.mixture().stableAmounts(state.chemicalRegistry()),
					stock.internalEnergy()).temperatureK();
			waterMass += mass;
			temperatureMoment += mass * temperature;
		}
		bulkWaterTemperatureK = waterMass == 0 ? 0 : temperatureMoment / waterMass;
		// Trace residue after emptying a container does not define a bulk temperature.
		if (waterMass >= 1.0) require(bulkWaterTemperatureK >= 270 && bulkWaterTemperatureK <= 350,
				stage + ": room-temperature water underwent unforced bulk heating/cooling: " + bulkWaterTemperatureK + " K");
	}

	private static java.util.Map<String, Long> elementTotals(mmm.chemistry.ChemicalRegistry registry,
			java.util.Map<String, Long> species) {
		if (registry == null) throw new IllegalStateException("Element audit requires the chemical registry");
		var totals = new java.util.TreeMap<String, Long>();
		species.forEach((id, amount) -> registry.species(registry.speciesHandle(id)).composition().forEach((element, count) ->
				totals.merge(element, Math.multiplyExact(amount, (long) count), Math::addExact)));
		totals.values().removeIf(amount -> amount == 0);
		return totals;
	}

	/** The gas ledger's environmental rows carry every component crossing the atmosphere. */
	private static long gasExchange(mmm.fluid.air.GasAuditLedger ledger, int sign, java.util.Map<String, Long> species) {
		long mass = 0;
		for (var entry : ledger.entries()) {
			mass = Math.addExact(mass, entry.massUnits());
			for (var amount : entry.species()) species.merge(amount.speciesId(),
					Math.multiplyExact((long) sign, amount.amountQuanta()), Math::addExact);
		}
		return Math.multiplyExact((long) sign, mass);
	}

	private static void chemicalExchange(mmm.chemistry.ChemicalTransferLedger ledger, int sign,
			java.util.Map<String, Long> species) {
		for (var group : ledger.groups()) group.speciesQuanta().forEach((id, amount) ->
				species.merge(id, Math.multiplyExact((long) sign, amount), Math::addExact));
	}

	private static long granularExchange(mmm.granular.GranularAuditLedger ledger, int sign,
			java.util.Map<String, Long> species) {
		long mass = 0;
		for (var entry : ledger.entries()) {
			require(entry.reactive(), "Cannot audit elements of an uncomposed external granular transfer");
			mass = Math.addExact(mass, entry.massUnits());
			for (var amount : entry.composition()) species.merge(amount.speciesId(),
					Math.multiplyExact((long) sign, amount.amountQuanta()), Math::addExact);
		}
		return Math.multiplyExact((long) sign, mass);
	}

	/** Capture the exact full-glass block layout used by the world scaffold in an otherwise empty world. */
	private static void captureCollisions(FluidRegion region, String scenario) {
		int sx = region.sizeX(), sy = region.sizeY(), sz = region.sizeZ();
		int cellsPerBlock = FluidRegionFactory.CELLS_PER_BLOCK;
		byte[] masks = new byte[sx * sy * sz];
		int solid = 0;
		for (int z = 0; z < sz; z++) for (int y = 0; y < sy; y++) for (int x = 0; x < sx; x++) {
			boolean glass = switch (scenario) {
				case "air", "vacuum" -> MinecraftDebugScaffolding.isScaffoldBlock(x / cellsPerBlock, y / cellsPerBlock, z / cellsPerBlock);
				case "tension", "tension30", "tension120" -> y / cellsPerBlock == 0;
				default -> false;
			};
			if (glass) { masks[(z * sy + y) * sx + x] = (byte) 0xff; solid++; }
		}
		var capture = new mmm.world.CollisionField(sx, sy, sz, masks);
		var applied = FluidRegionWorldInteraction.applyCapturedCollisions(region, capture, cellsPerBlock);
		require(applied.changedCells() == solid, "Scaffold collision capture was not applied");
		System.out.println("stage=collision-applied cells=" + solid);
	}

	private static void prepareAvailability(FluidRegion region) {
		boolean wasResident = region.deviceResident();
		boolean[] available = MinecraftGroupAvailability.sampleWorldCells(region.origin(), region.sizeX(),
				region.sizeY(), region.sizeZ(), region.solverConfig().cellSize(), (x, z) -> true);
		require(region.prepareGroupAvailability(available), "Loaded world did not admit any activity group");
		MinecraftRegionAvailability.activateThermalActivity(region);
		require(!wasResident || region.deviceResident(), "Availability observation displaced the device state");
	}

	private static void checkScenario(FluidRegion region, String scenario, boolean initial) {
		int x = scenario.startsWith("tension") ? 11 : scenario.equals("air") || scenario.equals("vacuum") ? 4 : 1;
		int y = scenario.startsWith("tension") || scenario.equals("air") || scenario.equals("vacuum") ? 2 : 1;
		int z = scenario.startsWith("tension") ? 11 : scenario.equals("air") || scenario.equals("vacuum") ? 4 : 1;
		var first = region.observeCellInventory(x, y, z);
		if (initial) require(first != null && first.liquidVolumeCubicMetres() > 0, "Scenario liquid observation is empty");
		if (initial && scenario.equals("material")) {
			var upper = region.observeCellInventory(1, 10, 1);
			require(hasMaterial(first, "mmm:water") && hasMaterial(upper, "mmm:oil"),
					"Layered demo lost its lower water and upper oil");
		} else if (initial && scenario.equals("chemistry")) {
			var base = region.observeCellInventory(7, 1, 1);
			require((hasSpecies(first, "mmm:hcl") || hasSpecies(first, "mmm:h_plus"))
					&& (hasSpecies(base, "mmm:naoh") || hasSpecies(base, "mmm:oh_minus")),
					"Acid/base demo lost its reagents or dissociation products");
		} else if (initial && scenario.startsWith("tension")) {
			require(hasMaterial(first, "mmm:water") && region.solidMicroMask(x, 1, z) == 255,
					"Wetting demo lost its water or glass contact floor");
		} else if (scenario.equals("air") || scenario.equals("vacuum")) {
			var nodes = region.display().thermal().gasNodes();
			require(!nodes.isEmpty(), "Air demo lost the sealed chamber display");
			if (initial) require(nodes.stream().anyMatch(node -> scenario.equals("vacuum")
					? node.gaugePressureDecaPa() < 0 : node.gaugePressureDecaPa() > 0),
					"Air demo lost the preset pressure direction");
		}
		if (initial) System.out.println("stage=scenario-observation initial=true scenario=" + scenario);
	}

	private static boolean hasMaterial(mmm.fluid.FluidCellInventoryView cell, String id) {
		return cell != null && cell.materials().stream().anyMatch(material -> material.materialId().equals(id)
				&& material.volumeCubicMetres() > 0);
	}

	private static boolean hasSpecies(mmm.fluid.FluidCellInventoryView cell, String id) {
		return cell != null && cell.species().stream().anyMatch(species -> species.speciesId().equals(id)
				&& species.amountQuanta() > 0);
	}

	private record PhysicalObservation(double waterX, double oilX, double waterY, double oilY,
			double leftHeight, double rightHeight, long acid, long base, long waterQuanta, double wetArea) { }

	/** Reads the authoritative stocks once at each end of the physical observation interval. */
	private static PhysicalObservation measure(mmm.fluid.FluidDomainState state, String scenario) {
		int sx = state.sizeX(), sy = state.sizeY(), sz = state.sizeZ();
		double[] volume = new double[2], xmoment = new double[2], ymoment = new double[2], side = new double[2], columns = new double[2];
		if (scenario.equals("air") || scenario.equals("vacuum"))
			for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++)
				if (state.fixedGeometry().mask((z * sy + 2) * sx + x) == 0) columns[x < sx / 2 ? 0 : 1]++;
		long acid = 0, base = 0, waterQuanta = 0;
		double wetArea = 0;
		var chemistry = state.chemicalRegistry();
		for (var stock : state.liquid().physicalStocks()) {
			int cell = stock.key().cell(), x = cell % sx, y = cell / sx % sy;
			int material = stock.key().material();
			double amount = stock.volumeFraction();
			if (material < 2) { volume[material] += amount; xmoment[material] += x * amount; ymoment[material] += y * amount; }
			int index = x < sx / 2 ? 0 : 1;
			side[index] += amount;
			if (chemistry != null && stock.mixture() != null) {
				var mix = stock.mixture();
				acid = Math.addExact(acid, Math.addExact(mix.amount(chemistry.speciesHandle("mmm:hcl")), mix.amount(chemistry.speciesHandle("mmm:h_plus"))));
				base = Math.addExact(base, Math.addExact(mix.amount(chemistry.speciesHandle("mmm:naoh")), mix.amount(chemistry.speciesHandle("mmm:oh_minus"))));
				waterQuanta = Math.addExact(waterQuanta, mix.amount(chemistry.speciesHandle("mmm:h2o")));
			}
		}
		if (scenario.startsWith("tension")) {
			var domain = FluidDomain.restoreAuthoritativeState(state);
			double h = domain.config().cellSize();
			var floor = new FluidDomain.WaterContactFace(1, 2 * h, 0, sz * h, 0, sx * h, 1);
			wetArea = domain.waterContacts(List.of(floor)).stream().mapToDouble(FluidDomain.WaterContact::areaSquareMetres).sum();
		}
		return new PhysicalObservation(volume[0] == 0 ? 0 : xmoment[0] / volume[0],
				volume[1] == 0 ? 0 : xmoment[1] / volume[1], volume[0] == 0 ? 0 : ymoment[0] / volume[0],
				volume[1] == 0 ? 0 : ymoment[1] / volume[1], columns[0] == 0 ? 0 : side[0] / columns[0],
				columns[1] == 0 ? 0 : side[1] / columns[1], acid, base, waterQuanta, wetArea);
	}

	private static void checkOutcome(String scenario, PhysicalObservation initial, PhysicalObservation end) {
		System.out.println("stage=physical-observation initial=" + initial + " final=" + end);
		switch (scenario) {
			case "demo" -> require(end.waterX() > initial.waterX(), "Dam-break did not transport water outwards");
			case "material" -> require(end.waterX() > initial.waterX() && end.oilX() > initial.oilX()
					&& end.oilY() > end.waterY(), "Both layers must move while lighter oil remains above water");
			case "chemistry" -> {
				long acidConsumed = initial.acid() - end.acid(), baseConsumed = initial.base() - end.base();
				System.out.printf("stage=neutralization acid_quanta=%d base_quanta=%d reaction_water_quanta=%d liquid_water_change=%d%n",
						acidConsumed, baseConsumed, reactionWaterQuanta, end.waterQuanta() - initial.waterQuanta());
				require(end.waterX() > initial.waterX() && acidConsumed > 0 && acidConsumed == baseConsumed && reactionWaterQuanta == acidConsumed,
						"Mixed acid and base did not undergo balanced neutralization");
			}
			case "tension30", "tension120" -> require(end.wetArea() > 0, "Water no longer contacts the wetting floor");
			case "air", "vacuum" -> {
				double change = (end.rightHeight() - end.leftHeight()) - (initial.rightHeight() - initial.leftHeight());
				require(scenario.equals("vacuum") ? change < 0 : change > 0,
						"Gas pressure did not move the liquid levels in the expected direction");
			}
			default -> throw new IllegalArgumentException("Unknown scenario " + scenario);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
