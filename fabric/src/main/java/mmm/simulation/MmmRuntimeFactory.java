package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import mmm.MmmMod;
import mmm.application.ContainerRecoveryCoordinator;
import mmm.application.ControllerQueryUseCase;
import mmm.application.RegionApplicationService;
import mmm.application.ThermalReservoirUseCase;
import mmm.fluid.FluidSolverBackend;
import mmm.fluid.HostFluidState;
import mmm.network.ProtocolSessions;
import mmm.simulation.persistence.ContainerJournalFileStore;
import mmm.transfer.ContainerTransferJournal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.UUID;

/** The only world-runtime composition root; all construction-time I/O is sequenced here. */
public final class MmmRuntimeFactory {
	private MmmRuntimeFactory() { }

	static MmmRuntime create(MinecraftServer server, CommittedEventHub committedEvents) {
		Objects.requireNonNull(server, "Minecraft server");
		Objects.requireNonNull(committedEvents, "Committed event hub");
		Path worldRoot = server.getWorldPath(LevelResource.ROOT);
		WorldStartupCoordinator.Result startup = WorldStartupCoordinator.start(worldRoot,
				() -> RegistryBundleLoader.loadDefinitions(server));
		logStartup(startup);
		if (!startup.profileProven()) throw new IllegalStateException(startup.blockingSummary());

		FluidServerConfig config = startup.config();
		RegistryBundleLoader.RegistryBundle registries = startup.registries();
		CommittedWorldRegistries worldRegistries = new CommittedWorldRegistries(startup);
		FluidRegionManager regionManager = new FluidRegionManager(config.limits());
		var comparatorSignals = new ControllerComparatorSignals(regionManager, (dimension, position) -> {
			var level = server.getLevel(dimension);
			if (level != null && level.hasChunkAt(position))
				level.updateNeighbourForOutputSignal(position, level.getBlockState(position).getBlock());
		});
		FluidRegionOperations regionOperations = new FluidRegionOperations(worldRoot, regionManager, startup);
		regionOperations.recoverRemovedQuarantines();
		for (WorldStartupCoordinator.Issue issue : startup.issues()) if (issue.blocksCreation()) {
			regionOperations.recordIssue("startup " + issue.stage() + "/" + issue.code() + ": " + issue.detail());
		}
		List<String> ownershipIssues = regionOperations.scanOwnershipIssues();
		for (String issue : ownershipIssues) {
			MmmMod.LOGGER.error("Matter, Motion & Mechanics world ownership issue: {}", issue);
		}

		ContainerJournalFileStore containerTransfers = new ContainerJournalFileStore(worldRoot);
		var transferReceipts = new mmm.simulation.persistence.PublicTransferReceiptStore(worldRoot);
		ContainerTransferIndex containerIndex = discoverContainerTransfers(containerTransfers);
		FluidSnapshotPublisher snapshots = new FluidSnapshotPublisher(startup.profile(),
				registries == null ? null : registries.chemical(),
				registries == null ? null : registries.granular(),
				registries == null ? null : registries.thermal());
		ContainerRecoveryCoordinator containerRecovery = new ContainerRecoveryCoordinator(containerTransfers,
				new MinecraftContainerRecoveryAdapter(server, startup, registries, regionManager, regionOperations,
						committedEvents,transferReceipts), containerIndex.journals(), containerIndex.issues());
		MinecraftOwnershipIssues ownership = new MinecraftOwnershipIssues(ownershipIssues);
		MinecraftRuntimePolicy policy = new MinecraftRuntimePolicy(startup, regionManager);
		FluidWorldTargetResolver targets = new FluidWorldTargetResolver(regionManager);
		FluidSolverBackend solverBackend = initializeSolverBackend(MmmRuntimeFactory::openSolverBackend,
				backend -> prepareSolverBackend(backend, startup, worldRegistries));
		regionManager.solverBackend(solverBackend);
		try {
		MmmMod.LOGGER.info("Matter, Motion & Mechanics numerical execution: {}", solverBackend.executionDescription());
		RegionApplicationService regionApplication = new RegionApplicationService(solverBackend,
				new FluidRegionRepository(regionManager), regionOperations);
		MinecraftMotionExecutor motion = new MinecraftMotionExecutor(regionApplication, worldRegistries, policy);
		MinecraftTakeoverAdapter takeover = new MinecraftTakeoverAdapter();
		MinecraftDebugScaffolding scaffolding = new MinecraftDebugScaffolding();
		MinecraftWorldUpdates worldUpdates=new MinecraftWorldUpdates(regionManager);
		ExternalMovingStructures externalStructures=new ExternalMovingStructures(server);
		MinecraftRegionInputs regionInputs = new MinecraftRegionInputs(
				worldRegistries.thermalOrNull(), FluidRegionFactory.CELLS_PER_BLOCK,
				worldRegistries.resolveMaterial("mmm:water").referenceDensity());
		regionInputs.worldUpdates(worldUpdates);
		regionInputs.externalStructures(externalStructures);
		MinecraftRegionTick regionTick = new MinecraftRegionTick(regionApplication, regionInputs, snapshots,
				worldRegistries.profile(), policy.maximumPhaseOperationsPerStep());
		MinecraftPendingPhaseWorkBarrier phaseWorkBarrier =
				new MinecraftPendingPhaseWorkBarrier(motion, regionTick);
		MinecraftRegionAvailability availability = new MinecraftRegionAvailability(
				containerRecovery, regionOperations, policy::controllerEnabled,
				regionTick, snapshots, worldRegistries);
		FiniteHeatExchangerOperations finiteDevices = new FiniteHeatExchangerOperations(server, worldRoot,
				regionManager, regionOperations, targets, availability, phaseWorkBarrier, worldRegistries, snapshots);
		FarmlandMoistureOperations farmland = new FarmlandMoistureOperations(server, worldRoot,
				regionManager, regionOperations, availability, phaseWorkBarrier, worldRegistries, snapshots);
		availability.additionalPending(id -> finiteDevices.hasPending(id) || farmland.hasPending(id));
				regionTick.activityConnections(finiteDevices::refreshActivityConnections);
		regionTick.farmlandHydration(farmland::tickAvailable);
		ControllerQueryUseCase controllerQueries = new ControllerQueryUseCase(new ControllerSummaryAdapter(
				regionManager, regionOperations, worldRegistries.profile(), worldRegistries.thermalOrNull(),
				availability::hasPending, startup::authoritativeWritesReady,
				policy.liveWorldBridgeEnabled()));
		MinecraftRegionLifecycle regionLifecycle = new MinecraftRegionLifecycle(
				startup, regionManager, regionOperations, availability, phaseWorkBarrier,
				snapshots, targets, controllerQueries,
				worldRegistries.thermalOrNull(), availability::hasPending);
		MinecraftControllerConsole controllerConsole = new MinecraftControllerConsole(
				targets, controllerQueries, regionLifecycle, new ThermalReservoirUseCase(), regionTick,
				worldRegistries.thermalOrNull(), startup, regionManager, startup::authoritativeWritesReady,
				availability::ensure);
		MinecraftRegionActivation regionActivation = new MinecraftRegionActivation(
				startup, worldRegistries, regionManager, regionOperations, snapshots, regionTick, takeover,
				containerRecovery, policy::controllerEnabled, policy::defaultEntityPhysicsEnabled,
				policy::defaultDebugOverlays, ownership::current, availability,
				policy.liveWorldBridgeEnabled(), policy.maximumThermalSubstepsPerStep());
		MinecraftPoolOperations pools = new MinecraftPoolOperations(regionManager, regionOperations, regionActivation,
				availability, phaseWorkBarrier, regionLifecycle, snapshots, startup);
		MinecraftDebugFixtures debugFixtures = new MinecraftDebugFixtures(
				regionManager, snapshots, takeover, scaffolding, new MinecraftDebugFixtures.Runtime() {
					@Override public FluidRegion.CollisionStats refreshCollisions(net.minecraft.server.level.ServerLevel level) {
						FluidRegion region = regionManager.selected();
						if (region == null) return FluidRegion.CollisionStats.EMPTY;
						if (!availability.ensure(level, region)) {
							throw new IllegalStateException("演示区域无法读取世界碰撞：" + region.availabilityFailure());
						}
						try {
							return regionTick.refreshRegionInputs(level, region);
						} catch (FluidDomainContracts.CollisionEditBlockedException blocked) {
							regionTick.blockCollisionEdit(level, region, blocked);
							throw blocked;
						}
					}
					@Override public void stepMotion(FluidRegion region) { motion.step(region, List.of()); }
					@Override public CommittedWorldRegistries worldRegistries() { return worldRegistries; }
					@Override public void bindCapabilityState(HostFluidState domain) {
						worldRegistries.bindConfiguredState(domain, policy.maximumThermalSubstepsPerStep());
					}
					@Override public void bindTensionPalette(HostFluidState domain, int angleDegrees) {
						DebugScenarioInitialization.bindTensionPalette(domain,
								DebugScenarioInitialization.tensionDefinitions(worldRegistries.materialRegistry(), angleDegrees));
					}
					@Override public void bindTensionCapabilityState(HostFluidState domain, int angleDegrees) {
						worldRegistries.bindConfiguredState(domain, policy.maximumThermalSubstepsPerStep(),
								DebugScenarioInitialization.tensionDefinitions(worldRegistries.materialRegistry(), angleDegrees));
					}
					@Override public MinecraftDebugFixtures.ControllerActivation activateController(
							net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos position
					) {
						MinecraftRegionActivation.Result result = regionActivation.activate(player, position, false);
						return new MinecraftDebugFixtures.ControllerActivation(
								result.succeeded() ? null : result.failure(), result.retainShell());
					}
				}, worldRegistries.profile(), policy::defaultEntityPhysicsEnabled, policy::defaultDebugOverlays);
		MinecraftRegionAdministration regionAdministration = new MinecraftRegionAdministration(
				regionManager, regionOperations, regionApplication, availability::hasPending);
		RegionDiagnosticsReport regionDiagnostics = new RegionDiagnosticsReport(
				regionManager, regionOperations, takeover, debugFixtures, worldRegistries,
				policy::controllerEnabled, policy::liveWorldBridgeEnabled,
				policy::configuredWorldBridgeEnabled, ownership::current, policy::maximumPhaseOperationsPerStep, solverBackend::executionDescription);
		MinecraftContainerTransactionAdapter containerTransactions = new MinecraftContainerTransactionAdapter(
				regionManager, regionOperations, containerRecovery, worldRegistries.profile().profileFingerprint(),
				worldRegistries.thermalOrNull() == null ? null : worldRegistries.thermalOrNull().fingerprint());
		var transferApi = new FabricTransferApi(server,regionManager,startup,worldRegistries,availability,phaseWorkBarrier,
				containerTransactions,containerRecovery,transferReceipts,snapshots);
		PlayerContainerTransferAdapter playerContainerTransfers = new PlayerContainerTransferAdapter(
				startup, worldRegistries, targets, availability, phaseWorkBarrier, containerTransactions, snapshots);
		DispenserContainerTransferAdapter dispenserContainerTransfers = new DispenserContainerTransferAdapter(
				startup, worldRegistries, targets, availability, phaseWorkBarrier, containerTransactions, snapshots);
		MinecraftProcessOperations processes = new MinecraftProcessOperations(worldRoot, regionManager, targets, startup);
		regionInputs.prescribedBodies(processes::bodies);
		regionInputs.manualPrescribedBodies(region -> processes.manualBodies(region.id(), region.entityBodyState().bodies()));
		regionManager.observeRemoval(region -> { processes.regionRemoved(region); externalStructures.regionRemoved(region); });
		MinecraftRegionOperatorActions operatorActions = new MinecraftRegionOperatorActions(
				startup, regionManager, targets, regionOperations, snapshots, takeover, debugFixtures,
				availability, regionTick, motion, phaseWorkBarrier, policy, containerRecovery);
		MinecraftSimulationLoop simulationLoop = new MinecraftSimulationLoop(
				regionManager, regionOperations, snapshots, containerRecovery, takeover,
				availability, regionTick, ownership::replace);
		DemoWorkbench demos = new DemoWorkbench(regionManager, debugFixtures, operatorActions,
				new DemoTankOperations(startup, worldRegistries, regionManager, regionOperations,
						regionLifecycle, availability, phaseWorkBarrier), startup, regionOperations);
		return new MmmRuntime(simulationLoop, finiteDevices, farmland, operatorActions, containerTransactions, controllerConsole,
				regionActivation, pools, debugFixtures, demos, regionAdministration, regionDiagnostics,
				playerContainerTransfers, dispenserContainerTransfers,
				new MinecraftRegionObservation(regionManager, snapshots, comparatorSignals), containerRecovery,
				new FabricFluidQueryApi(server, regionManager, startup.profileProven()), new ProtocolSessions(),
				worldRegistries, committedEvents, startup,transferApi, solverBackend, processes, new IndustrialOperations(startup,targets,worldRegistries,containerTransactions,containerRecovery,availability,phaseWorkBarrier,snapshots),worldUpdates,externalStructures);
		} catch (RuntimeException | Error failure) {
			try { solverBackend.close(); }
			catch (RuntimeException | Error closeFailure) { failure.addSuppressed(closeFailure); }
			throw failure;
		}
	}

	static void prepareSolverBackend(FluidSolverBackend backend, WorldStartupCoordinator.Result startup,
			CommittedWorldRegistries worldRegistries) {
		if (startup.creationLocked() || !startup.profileProven() || backend.unavailableReason() != null) return;
		long started = System.nanoTime();
		HostFluidState domain = FluidRegionFactory.createDomain(1, 1, 1, worldRegistries);
		worldRegistries.bindConfiguredState(domain, startup.config().maximumThermalSubstepsPerStep());
		backend.prepare(domain.captureAuthoritativeState());
		MmmMod.LOGGER.info("Matter, Motion & Mechanics solver preparation: {} ms",
				(System.nanoTime() - started) / 1_000_000);
	}

	static FluidSolverBackend createSolverBackend() {
		return initializeSolverBackend(MmmRuntimeFactory::openSolverBackend, backend -> { });
	}

	/** Device creation and first submission share the same world-startup failure policy. */
	static FluidSolverBackend initializeSolverBackend(java.util.function.Supplier<FluidSolverBackend> create,
			java.util.function.Consumer<FluidSolverBackend> prepare) {
		FluidSolverBackend backend = null;
		try {
			backend = Objects.requireNonNull(create.get());
			if (backend.unavailableReason() == null) prepare.accept(backend);
			return backend;
		} catch (RuntimeException | LinkageError failure) {
			if (backend != null) try { backend.close(); }
			catch (RuntimeException | LinkageError closeFailure) { failure.addSuppressed(closeFailure); }
			MmmMod.LOGGER.error("Vulkan initialization or preparation failed; simulation regions remain paused", failure);
			return new mmm.fluid.UnavailableFluidRuntime(message(failure));
		}
	}

	private static FluidSolverBackend openSolverBackend() {
		int device = Integer.parseInt(System.getProperty("mmm.gpuDevice", "0"));
		if (device < 0) throw new IllegalArgumentException("mmm.gpuDevice must be nonnegative");
		for (var provider : ServiceLoader.load(FluidSolverBackend.Provider.class))
			if (provider.id().equals("vulkan")) return Objects.requireNonNull(provider.create(device));
		return new mmm.fluid.UnavailableFluidRuntime("Vulkan provider is unavailable");
	}

	private static ContainerTransferIndex discoverContainerTransfers(ContainerJournalFileStore store) {
		Map<UUID, ContainerTransferJournal> journals = new HashMap<>();
		Set<String> issues = new HashSet<>();
		try {
			ContainerJournalFileStore.Scan scan = store.discover();
			issues.addAll(scan.issues());
			for (UUID transactionId : scan.transactions()) try {
				journals.put(transactionId, store.read(transactionId));
			} catch (IOException | RuntimeException unreadable) {
				issues.add("unreadable indexed container-transfer WAL is preserved: "
						+ transactionId + " (" + message(unreadable) + ")");
			}
		} catch (IOException | RuntimeException scanFailure) {
			issues.add("container-transfer boot scan failed: " + message(scanFailure));
		}
		return new ContainerTransferIndex(Map.copyOf(journals), Set.copyOf(issues));
	}

	private static void logStartup(WorldStartupCoordinator.Result startup) {
		for (WorldStartupCoordinator.Issue issue : startup.issues()) {
			String text = "Matter, Motion & Mechanics startup " + issue.stage() + "/" + issue.code()
					+ ": " + issue.detail();
			if (issue.severity() == WorldStartupCoordinator.Severity.ERROR) MmmMod.LOGGER.error(text);
			else MmmMod.LOGGER.warn(text);
		}
	}

	private static String message(Throwable failure) {
		String detail = failure.getMessage();
		return detail == null || detail.isBlank() ? failure.getClass().getSimpleName() : detail;
	}

	private record ContainerTransferIndex(
			Map<UUID, ContainerTransferJournal> journals,
			Set<String> issues
	) { }
}
