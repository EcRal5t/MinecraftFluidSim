package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.api.event.MmmEventApi;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;

/** One server's composed runtime; it owns lifecycle coordination, not service convenience methods. */
public final class MmmRuntime {
	private final FluidSimulationService simulation;
	private final CommittedEventHub committedEvents;
	private final WorldStartupCoordinator.Result startup;
	private boolean worldRecoveryCompleted;
	private boolean closed;

	MmmRuntime(
			FluidSimulationService simulation,
			CommittedEventHub committedEvents,
			WorldStartupCoordinator.Result startup
	) {
		this.simulation = Objects.requireNonNull(simulation, "Simulation service");
		this.committedEvents = Objects.requireNonNull(committedEvents, "Committed event hub");
		this.startup = Objects.requireNonNull(startup, "World startup result");
	}

	public FluidSimulationService simulation() { return simulation; }
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
			committedEvents.clear();
		}
	}
}
