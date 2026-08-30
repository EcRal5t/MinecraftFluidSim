package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.api.event.MmmEventApi;
import cc.ecisr.mmm.api.query.MmmQueryApi;
import cc.ecisr.mmm.application.ContainerRecoveryCoordinator;
import cc.ecisr.mmm.network.ProtocolSessions;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;

/** One server's composed runtime; it owns lifecycle coordination, not service convenience methods. */
public final class MmmRuntime {
	private final FluidSimulationService simulation;
	private final MinecraftControllerConsole controllerConsole;
	private final MinecraftRegionActivation regionActivation;
	private final MinecraftIndicatorEditing indicatorEditing;
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
			FluidSimulationService simulation,
			MinecraftControllerConsole controllerConsole,
			MinecraftRegionActivation regionActivation,
			MinecraftIndicatorEditing indicatorEditing,
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
		this.simulation = Objects.requireNonNull(simulation, "Simulation service");
		this.controllerConsole = Objects.requireNonNull(controllerConsole, "Controller console");
		this.regionActivation = Objects.requireNonNull(regionActivation, "Region activation");
		this.indicatorEditing = Objects.requireNonNull(indicatorEditing, "Indicator editing");
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

	public FluidSimulationService simulation() { return simulation; }
	public MinecraftControllerConsole controllerConsole() { return controllerConsole; }
	public MinecraftRegionActivation regionActivation() { return regionActivation; }
	public MinecraftIndicatorEditing indicatorEditing() { return indicatorEditing; }
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

	/** World-dependent recovery waits until Fabric reports all server levels started. */
	public void recoverWorld(MinecraftServer server) {
		Objects.requireNonNull(server, "Minecraft server");
		if (closed) throw new IllegalStateException("Matter, Motion & Mechanics runtime is closed");
		if (worldRecoveryCompleted) return;
		simulation.recoverTakeoverBackup(server);
		simulation.recoverWorldOwnership(server);
		simulation.recoverContainerTransfers(server);
		worldRecoveryCompleted = true;
	}

	void close(MinecraftServer server) {
		if (closed) return;
		closed = true;
		try {
			simulation.checkpointForShutdown();
			simulation.restoreTakeover(server);
		} finally {
			protocolSessions.clear();
			committedEvents.clear();
		}
	}
}
