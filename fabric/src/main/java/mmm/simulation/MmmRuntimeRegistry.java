package mmm.simulation;

import mmm.api.event.MmmEventApi;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Sole MinecraftServer-keyed owner for MMM runtime lifetime and pre-start event subscriptions. */
public final class MmmRuntimeRegistry {
	private static final Map<MinecraftServer, Slot> RUNTIMES = new WeakHashMap<>();

	private MmmRuntimeRegistry() { }

	public static synchronized MmmRuntime start(MinecraftServer server) {
		Objects.requireNonNull(server, "Minecraft server");
		Slot slot = RUNTIMES.computeIfAbsent(server, ignored -> new Slot());
		if (slot.runtime != null) return slot.runtime;
		try {
			slot.runtime = MmmRuntimeFactory.create(server, slot.events);
			return slot.runtime;
		} catch (RuntimeException | Error startupFailure) {
			RUNTIMES.remove(server);
			slot.events.clear();
			throw startupFailure;
		}
	}

	public static synchronized MmmRuntime require(MinecraftServer server) {
		Objects.requireNonNull(server, "Minecraft server");
		Slot slot = RUNTIMES.get(server);
		if (slot == null || slot.runtime == null) {
			throw new IllegalStateException("Matter, Motion & Mechanics runtime has not started");
		}
		return slot.runtime;
	}

	/** Read-only discovery never starts a runtime or performs world I/O. */
	public static synchronized MmmRuntime find(MinecraftServer server) {
		Slot slot = server == null ? null : RUNTIMES.get(server);
		return slot == null ? null : slot.runtime;
	}

	/** Listener registration remains valid before SERVER_STARTING without constructing world state. */
	public static synchronized MmmEventApi events(MinecraftServer server) {
		Objects.requireNonNull(server, "Minecraft server");
		return RUNTIMES.computeIfAbsent(server, ignored -> new Slot()).events;
	}

	public static synchronized void stop(MinecraftServer server) {
		Slot slot = RUNTIMES.remove(server);
		if (slot == null) return;
		if (slot.runtime != null) slot.runtime.close(server);
		else slot.events.clear();
	}

	private static final class Slot {
		private final CommittedEventHub events = new CommittedEventHub();
		private MmmRuntime runtime;
	}
}
