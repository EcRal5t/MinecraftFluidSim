package mmm.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Owner-thread, non-persistent per-viewer tokens used only to reject stale or replayed console actions. */
public final class ControllerConsoleSessions {
	private final Map<Viewer, Long> issued = new HashMap<>();
	private long nextGeneration = 1L;

	public long issue(UUID playerId, UUID regionId) {
		Viewer viewer = new Viewer(require(playerId, "player"), require(regionId, "region"));
		if (nextGeneration == Long.MAX_VALUE) {
			issued.clear();
			nextGeneration = 1L;
		}
		long generation = nextGeneration++;
		issued.put(viewer, generation);
		return generation;
	}

	/** Consumes exactly the currently issued token; stale values leave the current token intact. */
	public boolean consume(UUID playerId, UUID regionId, long generation) {
		if (generation <= 0L) return false;
		Viewer viewer = new Viewer(require(playerId, "player"), require(regionId, "region"));
		Long current = issued.get(viewer);
		if (current == null || current != generation) return false;
		issued.remove(viewer);
		return true;
	}

	public void forget(UUID playerId) {
		require(playerId, "player");
		issued.keySet().removeIf(viewer -> viewer.playerId.equals(playerId));
	}

	private static UUID require(UUID value, String label) {
		if (value == null) throw new IllegalArgumentException("Controller console " + label + " identity is missing");
		return value;
	}

	private record Viewer(UUID playerId, UUID regionId) { }
}
