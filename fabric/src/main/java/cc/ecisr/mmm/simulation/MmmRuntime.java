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
	private final MinecraftRegionObservation regionObservation;
	private final ContainerRecoveryCoordinator containerRecovery;
	private final MmmQueryApi queryApi;
	private final ProtocolSessions protocolSessions;
	private final CommittedEventHub committedEvents;
	private final WorldStartupCoordinator.Result startup;
	private boolean worldRecoveryCompleted;
	private boolean closed;

	MmmRuntime(
			FluidSimulationService simulation,
			MinecraftControllerConsole controllerConsole,
			MinecraftRegionObservation regionObservation,
			ContainerRecoveryCoordinator containerRecovery,
			MmmQueryApi queryApi,
			ProtocolSessions protocolSessions,
			CommittedEventHub committedEvents,
			WorldStartupCoordinator.Result startup
	) {
		this.simulation = Objects.requireNonNull(simulation, "Simulation service");
		this.controllerConsole = Objects.requireNonNull(controllerConsole, "Controller console");
		this.regionObservation = Objects.requireNonNull(regionObservation, "Region observation");
		this.containerRecovery = Objects.requireNonNull(containerRecovery, "Container recovery coordinator");
		this.queryApi = Objects.requireNonNull(queryApi, "Query API");
		this.protocolSessions = Objects.requireNonNull(protocolSessions, "Protocol sessions");
		this.committedEvents = Objects.requireNonNull(committedEvents, "Committed event hub");
		this.startup = Objects.requireNonNull(startup, "World startup result");
	}

	public FluidSimulationService simulation() { return simulation; }
	public MinecraftControllerConsole controllerConsole() { return controllerConsole; }
	public MinecraftRegionObservation regionObservation() { return regionObservation; }
	public ContainerRecoveryCoordinator containerRecovery() { return containerRecovery; }
	public MmmQueryApi queryApi() { return queryApi; }
	public ProtocolSessions protocolSessions() { return protocolSessions; }
	public WorldCapabilityProfile worldProfile() { return startup.profile(); }
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
