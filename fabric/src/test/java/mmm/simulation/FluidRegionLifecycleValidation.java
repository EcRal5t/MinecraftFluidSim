package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import mmm.api.RegionAvailabilityReason;
import mmm.application.ControllerQueryUseCase;
import mmm.application.RegionApplicationService;
import mmm.application.RegionCatalog;
import mmm.application.RegionLifecycleUseCase;
import mmm.application.RegionRuntime;
import mmm.application.RegionTickUseCase;
import mmm.application.command.CheckpointRegionCommand;
import mmm.application.command.ControllerSummaryCommand;
import mmm.application.command.MotionStepCommand;
import mmm.application.command.ThermalStepCommand;
import mmm.application.port.ControllerSummaryPort;
import mmm.application.port.RegionCheckpointPort;
import mmm.application.port.RegionRepository;
import mmm.application.result.CheckpointRegionResult;
import mmm.application.result.MotionStepResult;
import mmm.application.result.RegionStepStatus;
import mmm.application.result.ThermalStepResult;
import mmm.controller.ControllerSummary;
import mmm.controller.LifecycleOperation;
import mmm.controller.LifecycleOperationPreview;
import mmm.controller.LifecycleOperationSessions;
import mmm.fluid.HostFluidState;
import mmm.fluid.FlipParticleState;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.FluidSolverBackend;
import mmm.fluid.FluidStepCandidate;
import mmm.fluid.FluidStepRequest;
import mmm.fluid.FluidThermalStepCandidate;
import mmm.fluid.FluidThermalStepRequest;
import mmm.thermal.FiniteThermalBodyState;
import mmm.thermal.FiniteThermalDeviceInventory;
import mmm.thermal.FiniteThermalDeviceState;
import mmm.thermal.FiniteThermalExchange;
import mmm.thermal.ThermalStateFunction;
import mmm.thermal.ThermalTransport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Deterministic lifecycle transition gate independent of Minecraft chunks and wall-clock timing. */
public final class FluidRegionLifecycleValidation {
	private static final ResourceKey<Registry<Level>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(
			Identifier.fromNamespaceAndPath("mmm", "lifecycle_validation_dimension_registry"));
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
			DIMENSION_REGISTRY, Identifier.fromNamespaceAndPath("mmm", "lifecycle_validation_dimension"));

	private FluidRegionLifecycleValidation() { }

	public static void main(String[] args) throws Exception {
		validateBackendInitializationFailure();
		RegionHostHandoffValidation.validate();
		RegionDeviceLossGuardValidation.validate();
		EntityFluidWiringValidation.main(new String[0]);
		validateDemoWorkbenchStore();
		validateDemoWorldRestart();
		validatePoolWallHandoff();
		validateControllerComparatorObservation();
		validatePacingAndOperatorPause();
		validateRegionTickPlanningAndPacing();
		validateAvailabilityPreservesRunIntent();
		validateUnavailableDevicePreservesHostState();
		validateDeviceLossRecovery();
		validateHydrostaticSleepAndWake();
		validateHydrostaticSleepPreservesMarkerLedger();
		validateInternalConductionWakesMotion();
		validateUnloadRequiresPublishedCheckpointAndReleasedSolver();
		validateMetadataOnlySummaryCannotInventInventory();
		validateControllerSummaryTracksRunAndRecovery();
		validateLifecyclePreviewTokensBindEveryCommitGate();
		System.out.println("FluidRegionLifecycle validation passed");
	}

	private static void validatePoolWallHandoff() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		var thermal = registries.thermal();
		var host = new HostFluidState(2, 2, 2, new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f), mmm.fluid.TestFluidDefinitions.load());
		host.sealDomainBoundary();
		host.bindMaterialRegistry(registries.liquids());
		host.bindGasRegistry(registries.gas());
		host.bindThermal(thermal, registries.liquids(), 300);
		host.commitOperatorVentSealedGas(host.planOperatorVentSealedGas());
		var footprint = new mmm.simulation.controller.FluidRegionFootprint(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO,
				java.util.Set.of(BlockPos.ZERO.asLong()), java.util.Set.of(new BlockPos(0,-1,0).asLong()),
				java.util.Set.of(net.minecraft.world.level.ChunkPos.pack(0,0)),
				mmm.model.region.RegionGeometry.RegionKind.POOL, java.util.Set.of());
		var region = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO, host, footprint);
		var definition = thermal.finiteDevice("mmm:ordinary_stone_wall");
		double energy = new ThermalStateFunction(thermal).internalEnergyAtTemperature(
				thermal.materialHandle(definition.thermalMaterialId()), definition.bodyMassKilograms(), java.util.Map.of(), 350);
		var wall = new FiniteThermalDeviceState(OrdinaryThermalWalls.PROVIDER, "1@minecraft:stone", definition.id(),
				new FiniteThermalBodyState(0, thermal.fingerprint(), definition.thermalMaterialId(), definition.bodyMassUnits(), energy));
		var walls = FiniteThermalDeviceInventory.empty(thermal.fingerprint()).insertExternal(wall);
		region.restoreFiniteThermalDevices(walls, thermal, 0);
		require(OwnershipTransactionCoordinator.canRelinquishPool(region, region.hostSnapshot()),
				"Empty pool with fixed walls did not relinquish ownership");
		var candidate = region.ownershipCandidate(region.beginHostEdit(), footprint.withReturnedBlocks(footprint.interiorBlocks()));
		region.adoptOwnershipCandidate(candidate, 1);
		require(region.finiteThermalDevices(thermal.fingerprint()).equals(walls),
				"Pool return candidate changed wall energy or audit");
		var heaterDefinition = thermal.finiteDevice("mmm:finite_heat_exchanger");
		double heaterEnergy = new ThermalStateFunction(thermal).internalEnergyAtTemperature(
				thermal.materialHandle(heaterDefinition.thermalMaterialId()), heaterDefinition.bodyMassKilograms(), java.util.Map.of(), 300);
		var heater = new FiniteThermalDeviceState("mmm:finite_heat_exchanger", "test:heater", heaterDefinition.id(),
				new FiniteThermalBodyState(0, thermal.fingerprint(), heaterDefinition.thermalMaterialId(), heaterDefinition.bodyMassUnits(), heaterEnergy));
		region.replaceOrdinaryWallInventory(walls.insertExternal(heater), thermal);
		require(region.hasRemovableThermalDevices() && !OwnershipTransactionCoordinator.canRelinquishPool(region, region.hostSnapshot()),
				"Installed heat exchanger did not block pool relinquishment");
	}

	private static void validateBackendInitializationFailure() {
		var closed = new java.util.concurrent.atomic.AtomicBoolean();
		var backend = (FluidSolverBackend) java.lang.reflect.Proxy.newProxyInstance(
				FluidSolverBackend.class.getClassLoader(), new Class<?>[]{FluidSolverBackend.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "unavailableReason" -> null;
					case "close" -> { closed.set(true); throw new IllegalStateException("release after loss"); }
					default -> throw new UnsupportedOperationException(method.getName());
				});
		var failure = new IllegalStateException("device lost during first upload");
		var result = MmmRuntimeFactory.initializeSolverBackend(() -> backend, candidate -> { throw failure; });
		require(closed.get() && result.unavailableReason().contains(failure.getMessage())
				&& failure.getSuppressed().length == 1, "Warmup failure must release device and preserve its reason");
		var host = new HostFluidState(2, 2, 2, FluidSolverConfig.prototypeDefaults(), mmm.fluid.TestFluidDefinitions.load());
		var region = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO, host);
		region.solverBackend(result);
		require(region.pauseForUnavailableDevice() && !region.canStep(), "Failed warmup allowed region advancement");
		var creation = MmmRuntimeFactory.initializeSolverBackend(
				() -> { throw new UnsatisfiedLinkError("missing Vulkan library"); }, candidate -> {
					throw new AssertionError("Unavailable device must not warm up");
				});
		require(creation.unavailableReason().contains("missing Vulkan library"), "Creation failure lost its reason");
	}

	private static void validateUnavailableDevicePreservesHostState() {
		var host = new HostFluidState(2, 2, 2, FluidSolverConfig.prototypeDefaults(), mmm.fluid.TestFluidDefinitions.load());
		host.setVolume(0, 0, 0, .5F);
		var region = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO, host);
		region.setPaused(false);
		long generation = region.solverGeneration();
		region.solverBackend(new mmm.fluid.UnavailableFluidRuntime("test device unavailable"));
		require(region.pauseForUnavailableDevice() && !region.canStep()
				&& region.availabilityReason() == RegionAvailabilityReason.NUMERICAL_PAUSE
				&& region.solverGeneration() == generation && region.hostSnapshot().totalVolumeExact() == .5,
				"Unavailable device did not preserve host inventory and pause advancement");
	}

	private static void validateDeviceLossRecovery() {
		var controller = new BlockPos(-1, 0, 0);
		var footprint = new mmm.simulation.controller.FluidRegionFootprint(controller, BlockPos.ZERO, BlockPos.ZERO,
				java.util.Set.of(BlockPos.ZERO.asLong()), java.util.Set.of(controller.asLong()),
				java.util.Set.of(net.minecraft.world.level.ChunkPos.pack(0, 0)));
		var host = new HostFluidState(2, 2, 2, FluidSolverConfig.prototypeDefaults(), mmm.fluid.TestFluidDefinitions.load());
		host.setVolume(0, 0, 0, .5F);
		var region = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO, host, footprint);
		var backend = new LossBackend();
		region.solverBackend(backend);
		region.ensureDeviceResident();
		var successor = region.beginHostEdit();
		successor.setVolume(0, 0, 0, .75F);
		var published = successor.captureAuthoritativeState();
		backend.failImport = true;
		try {
			region.adoptContainerCandidateDomain(successor, 1, false);
			throw new AssertionError("device loss did not interrupt published candidate adoption");
		} catch (FluidSolverBackend.DeviceLostException expected) { }
		require(!region.solverLoaded() && region.checkpointGeneration() == 1 && !region.paused(),
				"published HEAD was not retained for recovery after failed adoption");
		require(region.pauseForUnavailableDevice() && !region.canStep(), "lost device did not pause region");
		region.restoreAuthoritativeSolver(published, 1);
		require(region.hostSnapshot().totalVolumeExact() == .75 && !region.paused(),
				"recovery did not restore the published inventory and operator intent");

		var queryHost = new HostFluidState(2, 2, 2, FluidSolverConfig.prototypeDefaults(), mmm.fluid.TestFluidDefinitions.load());
		var queryRegion = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO, queryHost, footprint);
		var queryBackend = new LossBackend();
		queryRegion.solverBackend(queryBackend);
		queryRegion.ensureDeviceResident();
		queryBackend.failRead = true;
		try {
			queryRegion.hostSnapshot();
			throw new AssertionError("device loss did not interrupt readback");
		} catch (FluidSolverBackend.DeviceLostException expected) { }
		require(queryRegion.pauseForUnavailableDevice() && !queryRegion.solverLoaded() && !queryRegion.canStep(),
				"failed readback left a lost resident generation accessible");
	}

	private static final class LossBackend implements FluidSolverBackend {
		boolean failImport, failRead;
		String unavailable;
		@Override public String id() { return "test:device"; }
		@Override public String unavailableReason() { return unavailable; }
		@Override public mmm.fluid.PreparedFluidState prepareState(mmm.fluid.FluidStateAccess source,
				mmm.fluid.FluidDomainState successor, FiniteThermalDeviceInventory devices,
				List<mmm.fluid.FluidStateAccess.DevicePort> ports) {
			if (failImport) throw lost();
			var state = new mmm.fluid.FluidStateAccess() {
				@Override public mmm.fluid.FluidDomainState.Layout layout() { return successor.layout(); }
				@Override public mmm.fluid.FluidDomainState exportState() {
					if (failRead) throw lost();
					return successor;
				}
			};
			return new mmm.fluid.PreparedFluidState() {
				@Override public mmm.fluid.FluidDomainState.Layout layout() { return state.layout(); }
				@Override public mmm.fluid.FluidDomainState exportState() { return state.exportState(); }
				@Override public mmm.fluid.FluidStateAccess commit() { return state; }
				@Override public void close() { }
			};
		}
		private DeviceLostException lost() { unavailable = "test device lost"; return new DeviceLostException(unavailable); }
		@Override public FluidStepCandidate evaluate(FluidStepRequest request) { throw new UnsupportedOperationException(); }
		@Override public FluidThermalStepCandidate evaluateThermal(FluidThermalStepRequest request) { throw new UnsupportedOperationException(); }
	}

	private static void validateDemoWorkbenchStore() throws Exception {
		var root = java.nio.file.Files.createTempDirectory("mmm-demo-workbench-");
		var store = new DemoWorkbenchStore(root);
		var player = UUID.randomUUID();
		var water = new DemoWorkbenchStore.Entry(player, "minecraft:overworld", UUID.randomUUID(),
				new BlockPos(-12, 65, 37), net.minecraft.core.Direction.WEST, mmm.network.DemoPreset.WATER, false);
		var other = new DemoWorkbenchStore.Entry(player, "minecraft:the_nether", UUID.randomUUID(),
				new BlockPos(6, 70, -8), net.minecraft.core.Direction.SOUTH, mmm.network.DemoPreset.CHEMISTRY, true);
		require(store.loadAll().isEmpty(), "New demo metadata was not empty");
		store.save(List.of(water, other));
		require(new DemoWorkbenchStore(root).loadAll().equals(List.of(water, other)), "Demo metadata did not survive reload");
		var ready = new DemoWorkbenchStore.Entry(player, water.dimension(), water.region(), water.anchor(), water.facing(),
				mmm.network.DemoPreset.MATERIAL, true);
		store.save(List.of(ready));
		require(new DemoWorkbenchStore(root).loadAll().equals(List.of(ready)), "Demo metadata replacement or removal failed");
		store.save(List.of());
		require(new DemoWorkbenchStore(root).loadAll().isEmpty(), "Demo metadata removal did not survive reload");
	}

	private static void validateDemoWorldRestart() throws Exception {
		var production=ProductionRegistries.load();
		var definitions=new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
				java.util.EnumSet.allOf(mmm.worldprofile.WorldCapability.class),production.material(),production.chemical(),
				production.granular(),production.gas(),production.thermal());
		var root=java.nio.file.Files.createTempDirectory("mmm-demo-world-restart-");
		var initial=WorldStartupCoordinator.start(root,()->definitions);
		require(initial.authoritativeWritesReady(),"New demo world did not admit its capability bundle");
		var entry=new DemoWorkbenchStore.Entry(UUID.randomUUID(),"minecraft:overworld",UUID.randomUUID(),
				new BlockPos(1,64,2),net.minecraft.core.Direction.NORTH,mmm.network.DemoPreset.CHEMISTRY,true);
		new DemoWorkbenchStore(root).save(List.of(entry));
		java.nio.file.Files.copy(root.resolve("mmm/demo-workbench.nbt"),root.resolve("mmm/demo-workbench.nbt.tmp"));
		var address=new mmm.api.transfer.BlockInventoryAddress("minecraft:overworld",2,64,2,0);
		var binding=new mmm.simulation.persistence.PublicTransferReceiptStore.Binding(address.actorRef(),address,UUID.randomUUID());
		new mmm.simulation.persistence.PublicTransferReceiptStore(root).bind(binding);
		java.nio.file.Files.createDirectories(root.resolve("mmm/device-transfer"));
		var driveRegion=UUID.randomUUID();var drive=new MinecraftProcessOperations.Drive(0,200,1,1,1,.5,0,null);
		new MinecraftProcessOperations(root,new FluidRegionManager(initial.config().limits()),null,initial).setDrive(driveRegion,drive);
		java.nio.file.Files.copy(root.resolve("mmm/processes.properties"),root.resolve("mmm/processes.properties.tmp"));
		var reopened=WorldStartupCoordinator.start(root,()->definitions);
		require(reopened.authoritativeWritesReady()&&new DemoWorkbenchStore(root).loadAll().equals(List.of(entry)),
				"Existing demo metadata blocked the committed world on restart: " + reopened);
		var manager=new FluidRegionManager(reopened.config().limits());
		require(new mmm.simulation.persistence.PublicTransferReceiptStore(root).binding(binding.actor()).equals(binding)
				&&new MinecraftProcessOperations(root,manager,null,reopened).drive(driveRegion).equals(drive),
				"Industrial actor binding or drive configuration did not survive world restart");
		var operations=new FluidRegionOperations(root,manager,reopened);
		require(!operations.creationLocked(),"Reopened demo world was unexpectedly locked");
		var lockedRoot=java.nio.file.Files.createTempDirectory("mmm-locked-world-start-");
		java.nio.file.Files.createDirectories(lockedRoot.resolve("mmm").resolve("unknown-durable-root"));
		var locked=WorldStartupCoordinator.start(lockedRoot,()->definitions);
		require(!locked.profileProven()&&locked.creationLocked(),"Unknown authority did not lock world startup");
		var lockedOperations=new FluidRegionOperations(lockedRoot,new FluidRegionManager(locked.config().limits()),locked);
		require(lockedOperations.creationLocked(),"Unproven world did not retain its creation lock");
	}

	private static void validateControllerComparatorObservation() {
		require(ControllerComparatorSignals.strength(0, 8) == 0
				&& ControllerComparatorSignals.strength(.001, 8) == 1
				&& ControllerComparatorSignals.strength(4, 8) == 8
				&& ControllerComparatorSignals.strength(7.99, 8) == 14
				&& ControllerComparatorSignals.strength(8, 8) == 15,
				"Controller comparator did not use the vanilla container ratio");
		var manager = new FluidRegionManager(new RegionCatalog.Limits(4, 4096, 16384));
		int[] notifications = {0};
		var signals = new ControllerComparatorSignals(manager, (dimension, position) -> notifications[0]++);
		var controller = new BlockPos(-1, 0, 0);
		var footprint = new mmm.simulation.controller.FluidRegionFootprint(controller, BlockPos.ZERO, BlockPos.ZERO,
				java.util.Set.of(BlockPos.ZERO.asLong()), java.util.Set.of(controller.asLong()),
				java.util.Set.of(net.minecraft.world.level.ChunkPos.pack(0, 0)));
		var domain = new HostFluidState(2, 2, 2, new FluidSolverConfig(.5F,0,.05F,48,1e-4F,.002F), mmm.fluid.TestFluidDefinitions.load());
		domain.setSolidMicroMask(0,0,0,15);
		var region = new FluidRegion(new UUID(127,131), DIMENSION, BlockPos.ZERO, domain, footprint);
		require(signals.signal(DIMENSION, controller)==0 && manager.add(region).accepted()
				&& notifications[0]==0, "Inactive or empty controller emitted a liquid signal");
		var candidate = HostFluidState.restoreAuthoritativeState(domain.captureAuthoritativeState());
		float[] half = {.25F,.5F,.5F,.5F,.5F,.5F,.5F,.5F};
		candidate.setVolumes(half);
		region.adoptContainerCandidateDomain(candidate, 1, true);
		require(signals.signal(DIMENSION, controller)==8 && region.paused() && notifications[0]==1
				&& candidate.fixedOpenVolumeExact()==7.5,
				"Committed liquid signal lost the fixed pore capacity or paused observation");
		candidate.restoreEntityBodyState(new mmm.fluid.EntityBodyState(java.util.List.of(
				new mmm.world.MovingBodyFluidCoupling.Request(new UUID(137,139),
						new mmm.world.MovingBodyFluidCoupling.Bounds(.1,.1,.1,.8,.8,.8),0,0,0))));
		region.publishCommittedObservation();
		for(int i=0;i<20;i++) require(signals.signal(DIMENSION, controller)==8, "Comparator observation changed state");
		require(notifications[0]==1 && region.editableHost()==candidate && region.paused()
				&& candidate.fixedOpenVolumeExact()==7.5, "Read-only observation woke or rebuilt the region");
		candidate.restoreEntityBodyState(mmm.fluid.EntityBodyState.EMPTY);
		region.setAvailabilityFailure(mmm.api.RegionAvailabilityReason.CHUNK_MISSING,"test missing chunk");
		require(signals.signal(DIMENSION,controller)==0 && notifications[0]==2,"Unavailable controller retained output");
		region.setAvailabilityFailure(null);
		var saved = candidate.captureAuthoritativeState();
		require(region.recordPublishedCheckpoint(2) && region.releaseSolverAfterCheckpoint(2)
				&& signals.signal(DIMENSION,controller)==0,"Unloaded controller retained output");
		int unloadedNotifications = notifications[0];
		for(int i=0;i<20;i++) require(signals.signal(DIMENSION,controller)==0,"Read-only signal restored an unloaded solver");
		require(!region.solverLoaded() && notifications[0]==unloadedNotifications,"Signal query mutated unloaded state");
		region.restoreAuthoritativeSolver(saved,2);
		require(signals.signal(DIMENSION,controller)==8,"Restored controller lost its committed ratio");
		manager.remove(region.id());
		require(signals.signal(DIMENSION,controller)==0,"Removed controller retained output");
	}

	private static void validateLifecyclePreviewTokensBindEveryCommitGate() {
		UUID player = new UUID(1L, 2L), otherPlayer = new UUID(3L, 4L), region = new UUID(5L, 6L);
		RegionLifecycleUseCase lifecycle = new RegionLifecycleUseCase();
		LifecycleOperationSessions.Context context = lifecycleContext(player, region, 7L,
				LifecycleOperation.EMPTY_CONDENSED, false, "READY", 100L);
		LifecycleOperationPreview preview = lifecycle.preview(previewFacts(context, List.of()));
		require(preview.ready() && preview.ownershipBlocks() == 9,
				"server lifecycle preview lost its bounded removal/ownership summary");
		require(lifecycle.authorizeCommit(preview.nonce(), previewFacts(lifecycleContext(otherPlayer, region, 7L,
				LifecycleOperation.EMPTY_CONDENSED, false, "READY", 101L), List.of())).decision()
				== RegionLifecycleUseCase.CommitDecision.TOKEN_INVALID,
				"another player consumed a lifecycle preview token");
		require(lifecycle.authorizeCommit(preview.nonce(), previewFacts(lifecycleContext(player, region, 7L,
				LifecycleOperation.EMPTY_CONDENSED, false, "READY", 101L), List.of())).decision()
				== RegionLifecycleUseCase.CommitDecision.AUTHORIZED
				&& lifecycle.authorizeCommit(preview.nonce(), previewFacts(context, List.of())).decision()
				== RegionLifecycleUseCase.CommitDecision.TOKEN_INVALID,
				"valid lifecycle token did not consume exactly once");

		for (LifecycleOperationSessions.Context changed : List.of(
				lifecycleContext(player, region, 8L, LifecycleOperation.EMPTY_CONDENSED, false, "READY", 101L),
				lifecycleContext(player, region, 7L, LifecycleOperation.VENT_SEALED_GAS, false, "READY", 101L),
				new LifecycleOperationSessions.Context(player, region, 7L,
						LifecycleOperation.EMPTY_CONDENSED,
						LifecycleOperationPreview.PermissionDecision.BUILTIN_ADMIN_DENIED,
						false, "READY", 101L),
				lifecycleContext(player, region, 7L, LifecycleOperation.EMPTY_CONDENSED, true, "READY", 101L),
				lifecycleContext(player, region, 7L, LifecycleOperation.EMPTY_CONDENSED, false, "CHUNK_MISSING", 101L),
				lifecycleContext(player, region, 7L, LifecycleOperation.EMPTY_CONDENSED, false, "READY", 301L))) {
			LifecycleOperationPreview stale = lifecycle.preview(previewFacts(context, List.of()));
			require(lifecycle.authorizeCommit(stale.nonce(), previewFacts(changed, List.of())).decision()
					== RegionLifecycleUseCase.CommitDecision.TOKEN_INVALID,
					"lifecycle preview survived a generation/action/WAL/availability/expiry change");
			require(lifecycle.authorizeCommit(stale.nonce(), previewFacts(context, List.of())).decision()
					== RegionLifecycleUseCase.CommitDecision.TOKEN_INVALID,
					"invalidated lifecycle preview token was reusable");
		}
		LifecycleOperationPreview blocked = lifecycle.preview(previewFacts(context, List.of()));
		var blockedAuthorization = lifecycle.authorizeCommit(blocked.nonce(), previewFacts(context,
				List.of(LifecycleOperationPreview.Blocker.PENDING_WAL)));
		require(blockedAuthorization.decision() == RegionLifecycleUseCase.CommitDecision.BLOCKED
				&& blockedAuthorization.blockers().equals(List.of(LifecycleOperationPreview.Blocker.PENDING_WAL)),
				"current lifecycle blockers did not prevent commit after token consumption");

		var running = lifecycleContext(player, region, 7L, LifecycleOperation.EMPTY_CONDENSED,
				false, "READY|paused=false", 102L);
		var paused = lifecycleContext(player, region, 7L, LifecycleOperation.EMPTY_CONDENSED,
				false, "READY|paused=true", 103L);
		var runningPreview = lifecycle.preview(previewFacts(running, List.of(LifecycleOperationPreview.Blocker.REGION_RUNNING)));
		require(!runningPreview.ready() && runningPreview.blockers().contains(LifecycleOperationPreview.Blocker.REGION_RUNNING),
				"running region did not present the pause requirement");
		var pausedPreview = lifecycle.preview(previewFacts(paused, List.of()));
		require(pausedPreview.ready() && lifecycle.authorizeCommit(pausedPreview.nonce(),
				previewFacts(running, List.of(LifecycleOperationPreview.Blocker.REGION_RUNNING))).decision()
				== RegionLifecycleUseCase.CommitDecision.TOKEN_INVALID,
				"resuming the region did not invalidate the paused preview");
		var fresh = lifecycle.preview(previewFacts(paused, List.of()));
		require(lifecycle.authorizeCommit(fresh.nonce(), previewFacts(paused, List.of())).decision()
				== RegionLifecycleUseCase.CommitDecision.AUTHORIZED,
				"fresh paused preview could not be confirmed");
	}

	/** Real summary projection follows the same run state and retry action used by the console. */
	private static void validateControllerSummaryTracksRunAndRecovery() throws Exception {
		var root = java.nio.file.Files.createTempDirectory("mmm-controller-summary-flow-");
		var manager = new FluidRegionManager(new RegionCatalog.Limits(4, 4096, 16384));
		var controller = new BlockPos(-1, 0, 0);
		var footprint = new mmm.simulation.controller.FluidRegionFootprint(controller, BlockPos.ZERO, BlockPos.ZERO,
				java.util.Set.of(BlockPos.ZERO.asLong()), java.util.Set.of(controller.asLong()),
				java.util.Set.of(net.minecraft.world.level.ChunkPos.pack(0, 0)));
		var domain = new HostFluidState(2, 2, 2, new FluidSolverConfig(.5F, 0, .05F, 48, 1e-5F, 1e-4F), mmm.fluid.TestFluidDefinitions.load());
		domain.setVolume(0, 0, 0, .5F);
		var region = new FluidRegion(new UUID(83L, 89L), DIMENSION, BlockPos.ZERO, domain, footprint);
		require(manager.addAndSelect(region).accepted(), "controller summary fixture was not admitted");
		var profile = TestProfiles.pure();
		var operations = new FluidRegionOperations(root, manager, false, true, profile, mmm.fluid.TestFluidDefinitions.load().chemistry(), mmm.fluid.TestFluidDefinitions.load().granular(),
                mmm.fluid.TestFluidDefinitions.load().thermal(), mmm.fluid.TestFluidDefinitions.load().gas(),
				ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS, TestProfiles.materialRegistry());
		require(operations.checkpoint(region) == 1L, "controller summary fixture did not publish its initial state");
		var queries = new ControllerQueryUseCase(new ControllerSummaryAdapter(manager, operations, profile, null,
				id -> false, () -> true, false));
		var player = new UUID(97L, 101L);
		var first = queries.query(new ControllerSummaryCommand(player, region.id())).summary();
		require(first.operatorIntent() == ControllerSummary.OperatorIntent.PAUSED && first.inventory().live(),
				"new controller did not report paused live inventory");
		require(queries.consumeActionToken(player, region.id(), first.summaryGeneration())
				&& MinecraftRegionOperatorActions.setPaused(region, false, () -> true), "controller run action failed");
		var running = queries.query(new ControllerSummaryCommand(player, region.id())).summary();
		require(running.operatorIntent() == ControllerSummary.OperatorIntent.RUNNING && running.availability().ready()
				&& !queries.consumeActionToken(player, region.id(), first.summaryGeneration()),
				"running summary or stale-token rejection did not follow the accepted action");
		region.blockPerformance("test performance block");
		var blocked = queries.query(new ControllerSummaryCommand(player, region.id())).summary();
		require(blocked.operatorIntent() == ControllerSummary.OperatorIntent.RUNNING
				&& blocked.availability().reason() == RegionAvailabilityReason.PERFORMANCE_PAUSE,
				"performance pause changed the operator intent or was not visible");
		require(queries.consumeActionToken(player, region.id(), blocked.summaryGeneration()), "retry token was rejected");
		MinecraftRegionOperatorActions.clearPerformancePauseForExplicitRetry(region);
		var recovered = queries.query(new ControllerSummaryCommand(player, region.id())).summary();
		require(recovered.operatorIntent() == ControllerSummary.OperatorIntent.RUNNING && recovered.availability().ready()
				&& recovered.inventory().liquidMassKilograms() == first.inventory().liquidMassKilograms(),
				"retry failed to restore the visible run state or changed liquid inventory");
		region.blockNumerical("test numerical block");
		MinecraftRegionOperatorActions.clearPerformancePauseForExplicitRetry(region);
		var numerical = queries.query(new ControllerSummaryCommand(player, region.id())).summary();
		require(numerical.availability().reason() == RegionAvailabilityReason.NUMERICAL_PAUSE,
				"performance retry cleared a distinct numerical failure");
		long beforeUnload = operations.checkpoint(region);
		require(region.releaseSolverAfterCheckpoint(beforeUnload), "controller fixture did not unload");
		region.setAvailabilityFailure(RegionAvailabilityReason.BOUNDARY_OPEN, "shell is open");
		operations.setUnloadedRunIntent(region, true);
		require(!region.solverLoaded() && region.paused()
				&& region.availabilityReason() == RegionAvailabilityReason.BOUNDARY_OPEN,
				"unloaded pause changed availability or retained a solver");
		var reopenedManager = new FluidRegionManager(new RegionCatalog.Limits(4, 4096, 16384));
		var reopened = new FluidRegionOperations(root, reopenedManager, false, true, profile, mmm.fluid.TestFluidDefinitions.load().chemistry(), mmm.fluid.TestFluidDefinitions.load().granular(),
                mmm.fluid.TestFluidDefinitions.load().thermal(), mmm.fluid.TestFluidDefinitions.load().gas(),
				ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS, TestProfiles.materialRegistry());
		var restored = reopenedManager.region(region.id());
		require(restored != null && restored.paused(), "unloaded pause was lost after metadata reload");
		reopened.setUnloadedRunIntent(restored, false);
		var finalManager = new FluidRegionManager(new RegionCatalog.Limits(4, 4096, 16384));
		new FluidRegionOperations(root, finalManager, false, true, profile, mmm.fluid.TestFluidDefinitions.load().chemistry(), mmm.fluid.TestFluidDefinitions.load().granular(),
                mmm.fluid.TestFluidDefinitions.load().thermal(), mmm.fluid.TestFluidDefinitions.load().gas(),
				ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS, TestProfiles.materialRegistry());
		require(!finalManager.region(region.id()).paused(), "unloaded resume was lost after metadata reload");
		try (var paths = java.nio.file.Files.walk(root)) {
			for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
		}
	}

	private static LifecycleOperationSessions.Context lifecycleContext(
			UUID player, UUID region, long generation, LifecycleOperation operation,
			boolean pendingWal, String availability, long tick
	) {
		return new LifecycleOperationSessions.Context(player, region, generation, operation,
				LifecycleOperationPreview.PermissionDecision.BUILTIN_ADMIN_GRANTED,
				pendingWal, availability, tick);
	}

	private static RegionLifecycleUseCase.PreviewFacts previewFacts(
			LifecycleOperationSessions.Context context,
			List<LifecycleOperationPreview.Blocker> blockers
	) {
		return new RegionLifecycleUseCase.PreviewFacts(context, LifecycleOperationPreview.Removal.none(),
				LifecycleOperationPreview.GasState.none(), 9, blockers);
	}

	/** The live solver is only replaced by a complete, adjacent candidate from the expected backend. */






	private static void validateRegionTickPlanningAndPacing() {
		int[] calls = {0, 0};
		RegionTickUseCase.StepExecutor steps = new RegionTickUseCase.StepExecutor() {
			@Override public MotionStepResult stepMotion(MotionStepCommand command) {
				calls[0]++;
				return MotionStepResult.rejected(RegionStepStatus.REGION_NOT_FOUND);
			}
			@Override public ThermalStepResult stepThermal(ThermalStepCommand command) {
				calls[1]++;
				return ThermalStepResult.rejected(RegionStepStatus.REGION_NOT_FOUND);
			}
		};
		RegionTickUseCase ticks = new RegionTickUseCase(
				steps, () -> 0L, RegionTickUseCase.Policy.production());
		require(ticks.plan(new RegionRuntime(4L, 2, 0, 0, false, false)).axis()
				== RegionTickUseCase.Axis.NONE, "paused axes produced a logical step");
		require(ticks.plan(new RegionRuntime(3L, 2, 0, 0, true, true)).axis()
				== RegionTickUseCase.Axis.NONE, "a skipped game tick consumed frozen input");
		require(ticks.plan(new RegionRuntime(4L, 2, 0, 0, false, true)).axis()
				== RegionTickUseCase.Axis.THERMAL, "thermal-only runtime did not schedule its due axis");
		RegionTickUseCase.Plan motion = ticks.plan(new RegionRuntime(4L, 2, 0, 0, true, true));
		require(motion.axis() == RegionTickUseCase.Axis.MOTION,
				"a due motion axis did not take precedence over thermal-only stepping");
		ticks.executeMotion(motion, new MotionStepCommand(new UUID(43L, 47L), 0L,
				List.of(), false, false));
		require(calls[0] == 1 && calls[1] == 0,
				"one due tick consumed more or less than its single frozen motion input");

		RegionRuntime runtime = new RegionRuntime(4L, 1, 0, 0, true, true);
		RegionTickUseCase.Pacing reduced = ticks.paceAfterAcceptedStep(runtime, 50.0);
		require(reduced.runtime().stepIntervalTicks() == 2 && !reduced.autoPause(),
				"step budget did not produce the existing reduced-rate transition");
		RegionTickUseCase.Pacing threshold = ticks.paceAfterAcceptedStep(
				new RegionRuntime(4L, 4, 2, 0, true, true), 200.0);
		require(threshold.runtime().hardOverrunStreak() == 0 && !threshold.autoPause(),
				"the inclusive 200 ms boundary was treated as a hard overrun");
		RegionTickUseCase.Pacing overrun = null;
		runtime = reduced.runtime();
		for (int i = 0; i < 3; i++) {
			overrun = ticks.paceAfterAcceptedStep(runtime, 201.0);
			runtime = overrun.runtime();
			require(overrun.autoPause() == (i == 2),
					"hard overrun pause did not require the complete consecutive streak");
		}
		runtime = new RegionRuntime(16L, 4, 0, 0, true, false);
		for (int i = 0; i < 7; i++) runtime = ticks.paceAfterAcceptedStep(runtime, 40.0).runtime();
		require(runtime.stepIntervalTicks() == 4 && runtime.recoveryStreak() == 7,
				"reduced pacing recovered before the evidence window closed");
		runtime = ticks.paceAfterAcceptedStep(runtime, 40.0).runtime();
		require(runtime.stepIntervalTicks() == 3 && runtime.recoveryStreak() == 0,
				"complete recovery evidence did not restore exactly one interval level");
	}

	private static void validateHydrostaticSleepPreservesMarkerLedger() {
		FluidSolverConfig config = FluidSolverConfig.prototypeDefaults();
		HostFluidState domain = new HostFluidState(6, 5, 4, config, mmm.fluid.TestFluidDefinitions.load());
		domain.restoreParticleState(new FlipParticleState(
				6, 5, 4, config.cellSize(), 2, 0.1875F,
				new float[] {
						0.25F, 0.25F, 0.25F, 0.125F,
						1.25F, 0.75F, 0.75F, 0.0625F
				},
				new float[] {0.5F, -0.25F, 0.125F, -0.125F, 0.25F, -0.5F}, mmm.units.Mass.createLiquid(config.cellSize(), 1000.0, new float[] {
						0.25F, 0.25F, 0.25F, 0.125F,
						1.25F, 0.75F, 0.75F, 0.0625F
				}),
				new byte[6 * 5 * 4]
		));
		double volume = domain.totalVolumeExact();
		double mass = domain.totalMass();
		int markers = domain.particleCount();
		float[] velocities = domain.exportParticleState().copyParticleVelocities();
		FluidRegion region = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO,
				domain);
		region.setPaused(false);
		require(region.lifecycle().observeCompletedStep(true, 1), "sleep fixture did not enter sleep");
		require(java.util.Arrays.equals(velocities, region.editableHost().exportParticleState().copyParticleVelocities()),
				"scheduling sleep changed marker momentum and kinetic energy");
		region.wakeForActivity();
		require(java.util.Arrays.equals(velocities, region.editableHost().exportParticleState().copyParticleVelocities()),
				"waking changed retained marker momentum");
		require(domain.particleCount() == markers && domain.totalVolumeExact() == volume && domain.totalMass() == mass,
				"hydrostatic sleep changed marker ledgers");
	}

	private static void validateInternalConductionWakesMotion() {
		HostFluidState domain = new HostFluidState(2, 2, 2, FluidSolverConfig.prototypeDefaults(), mmm.fluid.TestFluidDefinitions.load());
		FluidRegion region = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO,
				domain);
		region.setPaused(false);
		region.enableThermalActivity();
		require(region.lifecycle().observeCompletedStep(true, 1), "conduction fixture did not sleep");
		var zero = mmm.units.SignedDyadic.ZERO;
		var quiet = new ThermalActivity.Observation(new mmm.thermal.ThermalTransport.Stats(
				1, 1, 0.0, 0.0, zero, zero), false, false, false, false, false, false, false, true);
		require(!MinecraftRegionTick.applyThermalObservation(region, quiet) && !region.canStep(),
				"zero internal heat transfer woke mechanics");
		var slowTransfer = new ThermalActivity.Observation(quiet.transport(), false, false, false,
				true, false, false, false, true);
		for (int i = 0; i < 3; i++) {
			require(!MinecraftRegionTick.applyThermalObservation(region, slowTransfer) && !region.canStep(),
					"subquantum mass transfer unnecessarily woke mechanics");
			require(region.thermalActivity().state() == ThermalActivity.State.ACTIVE,
					"subquantum gas/liquid transfer entered thermal sleep");
		}
		var conducting = new ThermalActivity.Observation(new mmm.thermal.ThermalTransport.Stats(
				1, 1, 1.0e-12, 1.0e-8, zero, zero), false, false, false, false, false, false, false, true);
		require(MinecraftRegionTick.applyThermalObservation(region, conducting) && region.canStep(),
				"internal conduction without external energy exchange failed to wake mechanics");
		region.setPaused(true);
		MinecraftRegionTick.applyThermalObservation(region, conducting);
		require(!region.canStep(), "thermal activity overrode the operator's pause");
	}



	private static void validatePacingAndOperatorPause() {
		RegionLifecycleUseCase useCase = new RegionLifecycleUseCase();
		require(useCase.decideRunIntent(new RegionLifecycleUseCase.RunIntentFacts(
				false, false, true, true))
				== RegionLifecycleUseCase.RunIntentDecision.PERMISSION_DENIED,
				"controller resume ignored the frozen permission fact");
		require(useCase.decideRunIntent(new RegionLifecycleUseCase.RunIntentFacts(
				false, true, false, true))
				== RegionLifecycleUseCase.RunIntentDecision.WRITE_NOT_READY,
				"controller resume ignored the authoritative-write gate");
		require(useCase.decideRunIntent(new RegionLifecycleUseCase.RunIntentFacts(
				true, true, true, true))
				== RegionLifecycleUseCase.RunIntentDecision.UNCHANGED
				&& useCase.decideRunIntent(new RegionLifecycleUseCase.RunIntentFacts(
				false, true, true, true))
				== RegionLifecycleUseCase.RunIntentDecision.ACCEPTED,
				"controller run-intent idempotence/transition decision changed");
		FluidRegionLifecycle lifecycle = new FluidRegionLifecycle();
		require(lifecycle.state() == FluidRegionLifecycle.State.ACTIVE && !lifecycle.canStep(),
				"new lifecycle must start operator-paused in ACTIVE scheduling state");
		lifecycle.setOperatorPaused(false, 1);
		require(lifecycle.canStep(), "run did not enable ACTIVE stepping");
		lifecycle.onStepIntervalChanged(3);
		require(lifecycle.state() == FluidRegionLifecycle.State.REDUCED && lifecycle.canStep(),
				"reduced pacing changed operator intent");
		lifecycle.setOperatorPaused(true, 3);
		require(!lifecycle.canStep() && lifecycle.state() == FluidRegionLifecycle.State.REDUCED,
				"operator pause was confused with scheduling state");

		HostFluidState retryDomain = new HostFluidState(2, 2, 2, FluidSolverConfig.prototypeDefaults(), mmm.fluid.TestFluidDefinitions.load());
		FluidRegion retryRegion = new FluidRegion(new UUID(11L, 13L), DIMENSION, BlockPos.ZERO,
				retryDomain);
		retryRegion.blockPerformance("three accepted steps exceeded the wall-time guard");
		retryRegion.setHardOverrunStreak(3);
		retryRegion.setRecoveryStreak(2);
		require(retryRegion.availabilityReason() == RegionAvailabilityReason.PERFORMANCE_PAUSE
				&& retryRegion.performanceBlockFailure() != null
				&& retryRegion.numericalBlockFailure() == null,
				"accepted slow steps were misclassified as a numerical solver failure");
		require(MinecraftRegionAvailability.inventoryDecreaseMayBypass(retryRegion.availabilityReason())
				&& MinecraftRegionAvailability.inventoryDecreaseMayBypass(RegionAvailabilityReason.NUMERICAL_PAUSE)
				&& !MinecraftRegionAvailability.inventoryDecreaseMayBypass(RegionAvailabilityReason.PENDING_WAL)
				&& !MinecraftRegionAvailability.inventoryDecreaseMayBypass(RegionAvailabilityReason.HEAD_INVALID),
				"inventory-decreasing recovery did not isolate safe pauses from durable-state locks");
		MinecraftRegionOperatorActions.clearPerformancePauseForExplicitRetry(retryRegion);
		require(retryRegion.availabilityReason() == null && retryRegion.performanceBlockFailure() == null
				&& retryRegion.hardOverrunStreak() == 0 && retryRegion.recoveryStreak() == 0,
				"explicit run/step retry did not clear its retryable performance block");
		retryRegion.blockNumerical("pressure solve did not converge");
		MinecraftRegionOperatorActions.clearPerformancePauseForExplicitRetry(retryRegion);
		require(retryRegion.availabilityReason() == RegionAvailabilityReason.NUMERICAL_PAUSE
				&& retryRegion.numericalBlockFailure() != null,
				"explicit run/step retry cleared a numerical failure");
	}

	private static void validateAvailabilityPreservesRunIntent() {
		FluidRegionLifecycle lifecycle = running();
		lifecycle.blockAvailability(RegionAvailabilityReason.CHUNK_MISSING, "required chunk missing");
		require(!lifecycle.canStep() && !lifecycle.operatorPaused()
				&& lifecycle.availabilityReason() == RegionAvailabilityReason.CHUNK_MISSING,
				"availability failure overwrote the running intent");
		lifecycle.clearAvailabilityBlock();
		require(lifecycle.canStep() && lifecycle.availabilityReason() == null,
				"availability recovery did not restore the running intent");
		lifecycle.setOperatorPaused(true, 1);
		lifecycle.blockAvailability(RegionAvailabilityReason.BOUNDARY_OPEN, "required boundary missing");
		lifecycle.clearAvailabilityBlock();
		require(!lifecycle.canStep() && lifecycle.operatorPaused(),
				"availability recovery resumed an operator-paused region");
	}

	private static void validateHydrostaticSleepAndWake() {
		FluidRegionLifecycle lifecycle = running();
		require(!lifecycle.observeCompletedStep(true, 3), "sleep began before the quiet window");
		require(!lifecycle.observeCompletedStep(true, 3), "sleep began before the complete quiet window");
		require(lifecycle.observeCompletedStep(true, 3), "quiet window did not enter hydrostatic sleep");
		require(lifecycle.state() == FluidRegionLifecycle.State.HYDROSTATIC_SLEEP && !lifecycle.canStep(),
				"hydrostatic sleep still permits stepping");
		lifecycle.wakeForActivity(2);
		require(lifecycle.state() == FluidRegionLifecycle.State.REDUCED && lifecycle.canStep()
				&& lifecycle.quietCompletedSteps() == 0, "activity did not wake into current pacing state");
		lifecycle.observeCompletedStep(true, 3);
		lifecycle.observeCompletedStep(false, 3);
		require(lifecycle.quietCompletedSteps() == 0, "non-quiet step did not reset sleep evidence");
	}

	private static void validateUnloadRequiresPublishedCheckpointAndReleasedSolver() {
		FluidRegionLifecycle lifecycle = running();
		require(!lifecycle.requestUnload(false, true), "unpublished state entered UNLOADED");
		require(!lifecycle.requestUnload(true, false), "live solver entered UNLOADED");
		require(lifecycle.state() == FluidRegionLifecycle.State.ACTIVE && lifecycle.canStep(),
				"refused unload changed live scheduling state");
		require(lifecycle.requestUnload(true, true), "complete checkpoint/release transition was rejected");
		require(lifecycle.state() == FluidRegionLifecycle.State.UNLOADED && !lifecycle.canStep()
				&& !lifecycle.operatorPaused(), "UNLOADED lifecycle lost its running intent");
		lifecycle.setOperatorPaused(true, 1);
		lifecycle.setOperatorPaused(false, 1);
		require(!lifecycle.operatorPaused() && lifecycle.state() == FluidRegionLifecycle.State.UNLOADED,
				"operator could not change intent independently while availability kept the solver unloaded");
		lifecycle.restoreFromUnloaded(FluidRegionLifecycle.State.ACTIVE, 1);
		require(lifecycle.canStep(), "restored lifecycle did not resume its persisted running intent");
	}

	private static void validateMetadataOnlySummaryCannotInventInventory() {
		ControllerSummary.Inventory unavailable = ControllerSummary.Inventory.unavailable();
		require(!unavailable.live() && !unavailable.internalEnergyAvailable()
				&& Double.isNaN(unavailable.totalInternalEnergyJ())
				&& !unavailable.temperatureRange().available(),
				"metadata-only controller inventory did not remain explicitly unavailable");
		boolean rejected = false;
		try {
			new ControllerSummary.Inventory(false, 1.0, 0.0, 0L, 0L, 0.0,
					0L, 0.0, 0L, 0.0, false, Double.NaN,
					ControllerSummary.TemperatureRange.unavailable());
		} catch (IllegalArgumentException expected) {
			rejected = true;
		}
		require(rejected, "metadata-only controller inventory accepted invented live volume");

		UUID region = new UUID(37L, 41L);
		ControllerSummary.Capacity unavailableCapacity = ControllerSummary.Capacity.unavailable(64L);
		ControllerSummaryPort.View view = new ControllerSummaryPort.View(region, "mmm:summary_dimension",
				"0".repeat(64), 3L, 5L, new ControllerSummary.Footprint(1, 2, 3, 4, 5, 6, 120L, 4),
				true, ControllerSummary.OperatorIntent.PAUSED, ControllerSummary.MotionState.UNLOADED,
				ControllerSummary.ThermalState.UNBOUND, new ControllerSummary.Availability(null, null),
				new ControllerSummary.Usage(unavailableCapacity,
						unavailableCapacity, unavailableCapacity, unavailableCapacity, unavailableCapacity,
						unavailableCapacity), unavailable,
				new ControllerSummary.Gates(true, false, false, false, false),
				new ControllerSummary.Diagnostics(0.0, 0, 0.0, null));
		UUID viewer = new UUID(43L, 47L);
		ControllerQueryUseCase queries = new ControllerQueryUseCase(
				id -> id.equals(region) ? Optional.of(view) : Optional.empty());
		var result = queries.query(new ControllerSummaryCommand(viewer, region));
		require(result.ok() && result.summary().summaryGeneration() > 0L
				&& !result.summary().inventory().live(),
				"controller query invented inventory or lost the authorized token");
		require(queries.consumeActionToken(viewer, region, result.summary().summaryGeneration())
				&& !queries.consumeActionToken(viewer, region, result.summary().summaryGeneration()),
				"controller query token did not consume exactly once");
	}



	private static RegionApplicationService application(
			FluidSolverBackend backend,
			RegionRepository regions,
			RegionCheckpointPort checkpoints
	) {
		return new RegionApplicationService(backend, regions, checkpoints);
	}

	private static RegionRepository unusedRepository() {
		return new RegionRepository() {
			@Override public Capture captureForStep(UUID regionId) {
				return Capture.unavailable(CaptureStatus.REGION_NOT_FOUND);
			}
			@Override public RegionStepStatus adoptMotion(
					UUID regionId, FluidStepCandidate candidate, String expectedBackendId
			) { return RegionStepStatus.REGION_NOT_FOUND; }
			@Override public RegionStepStatus adoptThermal(
					UUID regionId, FluidThermalStepCandidate candidate, String expectedBackendId
			) { return RegionStepStatus.REGION_NOT_FOUND; }
		};
	}

	private static FluidRegionLifecycle running() {
		FluidRegionLifecycle lifecycle = new FluidRegionLifecycle();
		lifecycle.setOperatorPaused(false, 1);
		return lifecycle;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
