package mmm.simulation;

import mmm.api.event.MmmEventApi;
import mmm.api.query.MmmQueryApi;
import mmm.application.ContainerRecoveryCoordinator;
import mmm.network.ProtocolSessions;
import mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;

/** One server's composed runtime; it owns lifecycle coordination, not service convenience methods. */
public final class MmmRuntime {
	private final MinecraftSimulationLoop simulationLoop;
	private final MinecraftRegionOperatorActions operatorActions;
	private final MinecraftContainerTransactionAdapter containerTransactions;
	private final MinecraftControllerConsole controllerConsole;
	private final MinecraftRegionActivation regionActivation;
	private final MinecraftIndicatorEditing indicatorEditing;
	private final MinecraftDebugFixtures debugFixtures;
	private final MinecraftRegionAdministration regionAdministration;
	private final RegionDiagnosticsReport regionDiagnostics;
	private final PlayerContainerTransferAdapter playerContainerTransfers;
	private final DispenserContainerTransferAdapter dispenserContainerTransfers;
	private final MinecraftRegionObservation regionObservation;
	private final ContainerRecoveryCoordinator containerRecovery;
	private final MmmQueryApi queryApi;
	private final ProtocolSessions protocolSessions;
	private final CommittedWorldRegistries worldRegistries;
	private final CommittedEventHub committedEvents;
	private final WorldStartupCoordinator.Result startup;
	private boolean worldRecoveryCompleted;
	private boolean closed;

	MmmRuntime(
			MinecraftSimulationLoop simulationLoop,
			MinecraftRegionOperatorActions operatorActions,
			MinecraftContainerTransactionAdapter containerTransactions,
			MinecraftControllerConsole controllerConsole,
			MinecraftRegionActivation regionActivation,
			MinecraftIndicatorEditing indicatorEditing,
			MinecraftDebugFixtures debugFixtures,
			MinecraftRegionAdministration regionAdministration,
			RegionDiagnosticsReport regionDiagnostics,
			PlayerContainerTransferAdapter playerContainerTransfers,
			DispenserContainerTransferAdapter dispenserContainerTransfers,
			MinecraftRegionObservation regionObservation,
			ContainerRecoveryCoordinator containerRecovery,
			MmmQueryApi queryApi,
			ProtocolSessions protocolSessions,
			CommittedWorldRegistries worldRegistries,
			CommittedEventHub committedEvents,
			WorldStartupCoordinator.Result startup
	) {
		this.simulationLoop = Objects.requireNonNull(simulationLoop, "Simulation loop");
		this.operatorActions = Objects.requireNonNull(operatorActions, "Region operator actions");
		this.containerTransactions = Objects.requireNonNull(containerTransactions, "Container transactions");
		this.controllerConsole = Objects.requireNonNull(controllerConsole, "Controller console");
		this.regionActivation = Objects.requireNonNull(regionActivation, "Region activation");
		this.indicatorEditing = Objects.requireNonNull(indicatorEditing, "Indicator editing");
		this.debugFixtures = Objects.requireNonNull(debugFixtures, "Debug fixtures");
		this.regionAdministration = Objects.requireNonNull(regionAdministration, "Region administration");
		this.regionDiagnostics = Objects.requireNonNull(regionDiagnostics, "Region diagnostics");
		this.playerContainerTransfers = Objects.requireNonNull(playerContainerTransfers, "Player container transfers");
		this.dispenserContainerTransfers = Objects.requireNonNull(
				dispenserContainerTransfers, "Dispenser container transfers");
		this.regionObservation = Objects.requireNonNull(regionObservation, "Region observation");
		this.containerRecovery = Objects.requireNonNull(containerRecovery, "Container recovery coordinator");
		this.queryApi = Objects.requireNonNull(queryApi, "Query API");
		this.protocolSessions = Objects.requireNonNull(protocolSessions, "Protocol sessions");
		this.worldRegistries = Objects.requireNonNull(worldRegistries, "Committed world registries");
		this.committedEvents = Objects.requireNonNull(committedEvents, "Committed event hub");
		this.startup = Objects.requireNonNull(startup, "World startup result");
	}

	public MinecraftRegionOperatorActions operatorActions() { return operatorActions; }
	public MinecraftControllerConsole controllerConsole() { return controllerConsole; }
	public MinecraftRegionActivation regionActivation() { return regionActivation; }
	public MinecraftIndicatorEditing indicatorEditing() { return indicatorEditing; }
	public MinecraftDebugFixtures debugFixtures() { return debugFixtures; }
	public MinecraftRegionAdministration regionAdministration() { return regionAdministration; }
	public RegionDiagnosticsReport regionDiagnostics() { return regionDiagnostics; }
	PlayerContainerTransferAdapter playerContainerTransfers() { return playerContainerTransfers; }
	DispenserContainerTransferAdapter dispenserContainerTransfers() { return dispenserContainerTransfers; }
	public MinecraftRegionObservation regionObservation() { return regionObservation; }
	public ContainerRecoveryCoordinator containerRecovery() { return containerRecovery; }
	public MmmQueryApi queryApi() { return queryApi; }
	public ProtocolSessions protocolSessions() { return protocolSessions; }
	public CommittedWorldRegistries worldRegistries() { return worldRegistries; }
	public WorldCapabilityProfile worldProfile() { return worldRegistries.profile(); }
	public MmmEventApi events() { return committedEvents; }
	public WorldStartupCoordinator.Result startup() { return startup; }

	public void tick(MinecraftServer server) { simulationLoop.tick(server); }

	public boolean isLifecycleManagedBlock(
			net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos position
	) {
		return operatorActions.isLifecycleManagedBlock(level, position);
	}

	public void retryUnavailableContainerTransfers(
			net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.ChunkPos chunk
	) {
		containerRecovery.retryDispenserChunk(level.dimension().identifier().toString(), chunk.x(), chunk.z());
	}

	public mmm.application.ContainerTransferCoordinator.FaultPoint containerTransferFault() {
		return containerTransactions.fault();
	}

	public void setContainerTransferFault(
			mmm.application.ContainerTransferCoordinator.FaultPoint point
	) {
		containerTransactions.setFault(point);
	}

	/** World-dependent recovery waits until Fabric reports all server levels started. */
	public void recoverWorld(MinecraftServer server) {
		Objects.requireNonNull(server, "Minecraft server");
		if (closed) throw new IllegalStateException("Matter, Motion & Mechanics runtime is closed");
		if (worldRecoveryCompleted) return;
		simulationLoop.recoverWorld(server);
		worldRecoveryCompleted = true;
	}

	void close(MinecraftServer server) {
		if (closed) return;
		closed = true;
		try {
			simulationLoop.checkpointForShutdown();
			simulationLoop.restoreTakeover(server);
		} finally {
			protocolSessions.clear();
			committedEvents.clear();
		}
	}
}
