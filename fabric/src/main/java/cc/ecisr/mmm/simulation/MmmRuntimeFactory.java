package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.application.ContainerRecoveryCoordinator;
import cc.ecisr.mmm.application.ControllerQueryUseCase;
import cc.ecisr.mmm.application.RegionApplicationService;
import cc.ecisr.mmm.application.ThermalReservoirUseCase;
import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.network.ProtocolSessions;
import cc.ecisr.mmm.simulation.persistence.ContainerJournalFileStore;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;
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

		FluidServerConfig config = startup.config();
		RegistryBundleLoader.RegistryBundle registries = startup.registries();
		CommittedWorldRegistries worldRegistries = new CommittedWorldRegistries(startup);
		FluidRegionManager regionManager = new FluidRegionManager(config.limits());
		FluidRegionOperations regionOperations = new FluidRegionOperations(worldRoot, regionManager,
				startup.creationLocked(), startup.profileProven(), startup.profile(),
				registries == null ? null : registries.chemical(),
				registries == null ? null : registries.granular(),
				registries == null ? null : registries.thermal(),
				registries == null ? null : registries.gas(), config.maximumThermalSubstepsPerStep());
		regionOperations.recoverRemovedQuarantines();
		for (WorldStartupCoordinator.Issue issue : startup.issues()) if (issue.blocksCreation()) {
			regionOperations.recordIssue("startup " + issue.stage() + "/" + issue.code() + ": " + issue.detail());
		}
		List<String> ownershipIssues = regionOperations.scanOwnershipIssues();
		for (String issue : ownershipIssues) {
			MmmMod.LOGGER.error("Matter, Motion & Mechanics world ownership issue: {}", issue);
		}

		ContainerJournalFileStore containerTransfers = new ContainerJournalFileStore(worldRoot);
		ContainerTransferIndex containerIndex = discoverContainerTransfers(containerTransfers);
		FluidSnapshotPublisher snapshots = new FluidSnapshotPublisher(startup.profile(),
				registries == null ? null : registries.chemical(),
				registries == null ? null : registries.granular(),
				registries == null ? null : registries.thermal());
		ContainerRecoveryCoordinator containerRecovery = new ContainerRecoveryCoordinator(containerTransfers,
				new MinecraftContainerRecoveryAdapter(server, startup, registries, regionManager, regionOperations,
						committedEvents), containerIndex.journals(), containerIndex.issues());
		MinecraftOwnershipIssues ownership = new MinecraftOwnershipIssues(ownershipIssues);
		MinecraftRuntimePolicy policy = new MinecraftRuntimePolicy(startup, regionManager);
		FluidWorldTargetResolver targets = new FluidWorldTargetResolver(regionManager);
		RegionApplicationService regionApplication = new RegionApplicationService(new CpuFluidSolverBackend(),
				new FluidRegionRepository(regionManager), regionOperations);
		MinecraftMotionExecutor motion = new MinecraftMotionExecutor(regionApplication, worldRegistries, policy);
		MinecraftTakeoverAdapter takeover = new MinecraftTakeoverAdapter();
		MinecraftDebugScaffolding scaffolding = new MinecraftDebugScaffolding();
		MinecraftRegionInputs regionInputs = new MinecraftRegionInputs(
				worldRegistries.thermalOrNull(), FluidRegionFactory.CELLS_PER_BLOCK);
		MinecraftRegionTick regionTick = new MinecraftRegionTick(regionApplication, regionInputs, snapshots,
				worldRegistries.profile(), policy.maximumPhaseOperationsPerStep());
		MinecraftRegionAvailability availability = new MinecraftRegionAvailability(
				containerRecovery, regionOperations, policy::controllerEnabled,
				regionTick, snapshots, worldRegistries);
		ControllerQueryUseCase controllerQueries = new ControllerQueryUseCase(new ControllerSummaryAdapter(
				regionManager, regionOperations, worldRegistries.profile(), worldRegistries.thermalOrNull(),
				containerRecovery::hasPending, startup::authoritativeWritesReady,
				policy.liveWorldBridgeEnabled()));
		MinecraftRegionLifecycle regionLifecycle = new MinecraftRegionLifecycle(
				startup, regionManager, regionOperations, snapshots, targets, controllerQueries,
				worldRegistries.thermalOrNull(), containerRecovery::hasPending);
		MinecraftControllerConsole controllerConsole = new MinecraftControllerConsole(
				targets, controllerQueries, regionLifecycle, new ThermalReservoirUseCase(), regionTick,
				worldRegistries.thermalOrNull(), startup, regionManager, startup::authoritativeWritesReady,
				availability::ensure);
		MinecraftRegionActivation regionActivation = new MinecraftRegionActivation(
				startup, worldRegistries, regionManager, regionOperations, snapshots, regionTick, takeover,
				scaffolding, containerRecovery, policy::controllerEnabled, policy::defaultEntityPhysicsEnabled,
				policy::defaultDebugOverlays, ownership::current, availability,
				policy.liveWorldBridgeEnabled(), policy.maximumThermalSubstepsPerStep());
		MinecraftDebugFixtures debugFixtures = new MinecraftDebugFixtures(
				regionManager, snapshots, takeover, scaffolding, new MinecraftDebugFixtures.Runtime() {
					@Override public FluidRegion.CollisionStats refreshCollisions(net.minecraft.server.level.ServerLevel level) {
						FluidRegion region = regionManager.selected();
						if (region == null) return FluidRegion.CollisionStats.EMPTY;
						if (!availability.ensure(level, region)) return region.lastCollisionStats();
						try {
							return regionTick.refreshRegionInputs(level, region);
						} catch (FluidDomain.CollisionEditBlockedException blocked) {
							regionTick.blockCollisionEdit(level, region, blocked);
							throw blocked;
						}
					}
					@Override public void stepMotion(FluidRegion region) { motion.step(region, List.of()); }
					@Override public void bindCapabilityState(FluidDomain domain) {
						worldRegistries.bindConfiguredState(domain, policy.maximumThermalSubstepsPerStep());
					}
					@Override public String activateController(
							net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos position
					) {
						MinecraftRegionActivation.Result result = regionActivation.activate(player, position, false);
						return result.succeeded() ? null : result.failure();
					}
				}, worldRegistries.profile(), policy::defaultEntityPhysicsEnabled, policy::defaultDebugOverlays);
		MinecraftIndicatorEditing indicatorEditing = new MinecraftIndicatorEditing(
				worldRegistries, targets, regionOperations, snapshots, availability);
		MinecraftRegionAdministration regionAdministration = new MinecraftRegionAdministration(
				regionManager, regionOperations, regionApplication, containerRecovery);
		RegionDiagnosticsReport regionDiagnostics = new RegionDiagnosticsReport(
				regionManager, regionOperations, takeover, debugFixtures, worldRegistries,
				policy::controllerEnabled, policy::liveWorldBridgeEnabled,
				policy::configuredWorldBridgeEnabled, ownership::current, policy::maximumPhaseOperationsPerStep);
		MinecraftContainerTransactionAdapter containerTransactions = new MinecraftContainerTransactionAdapter(
				regionManager, regionOperations, containerRecovery, worldRegistries.profile().profileFingerprint(),
				worldRegistries.thermalOrNull() == null ? null : worldRegistries.thermalOrNull().fingerprint());
		MinecraftPendingPhaseWorkBarrier phaseWorkBarrier =
				new MinecraftPendingPhaseWorkBarrier(motion, regionTick);
		PlayerContainerTransferAdapter playerContainerTransfers = new PlayerContainerTransferAdapter(
				startup, worldRegistries, targets, availability, phaseWorkBarrier, containerTransactions, snapshots);
		DispenserContainerTransferAdapter dispenserContainerTransfers = new DispenserContainerTransferAdapter(
				startup, worldRegistries, targets, availability, phaseWorkBarrier, containerTransactions, snapshots);
		MinecraftRegionOperatorActions operatorActions = new MinecraftRegionOperatorActions(
				startup, regionManager, targets, regionOperations, snapshots, takeover, debugFixtures,
				availability, regionTick, motion, phaseWorkBarrier, policy, containerRecovery);
		MinecraftSimulationLoop simulationLoop = new MinecraftSimulationLoop(
				regionManager, regionOperations, snapshots, containerRecovery, takeover,
				availability, regionTick, ownership::replace);
		return new MmmRuntime(simulationLoop, operatorActions, containerTransactions, controllerConsole,
				regionActivation, indicatorEditing, debugFixtures, regionAdministration, regionDiagnostics,
				playerContainerTransfers, dispenserContainerTransfers,
				new MinecraftRegionObservation(regionManager, snapshots), containerRecovery,
				new FabricFluidQueryApi(server, regionManager, startup.profileProven()), new ProtocolSessions(),
				worldRegistries, committedEvents, startup);
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
