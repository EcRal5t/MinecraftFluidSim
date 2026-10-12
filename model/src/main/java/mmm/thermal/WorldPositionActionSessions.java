package mmm.thermal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Owner-thread, non-persistent stale-action tokens keyed by player and loader-neutral world position. */
public final class WorldPositionActionSessions {
	private final Map<Viewer, Long> issued = new HashMap<>();
	private long nextGeneration = 1L;

	public long issue(UUID playerId, String dimensionId, int x, int y, int z) {
		Viewer viewer = viewer(playerId, dimensionId, x, y, z);
		if (nextGeneration == Long.MAX_VALUE) {
			issued.clear();
			nextGeneration = 1L;
		}
		long generation = nextGeneration++;
		issued.put(viewer, generation);
		return generation;
	}

	public boolean consume(UUID playerId, String dimensionId, int x, int y, int z, long generation) {
		if (generation <= 0L) return false;
		Viewer viewer = viewer(playerId, dimensionId, x, y, z);
		Long current = issued.get(viewer);
		if (current == null || current != generation) return false;
		issued.remove(viewer);
		return true;
	}

	public void forget(UUID playerId) {
		if (playerId == null) throw new IllegalArgumentException("World-position session player is missing");
		issued.keySet().removeIf(viewer -> viewer.playerId.equals(playerId));
	}

	private static Viewer viewer(UUID playerId, String dimensionId, int x, int y, int z) {
		if (playerId == null || dimensionId == null || dimensionId.isBlank()) {
			throw new IllegalArgumentException("World-position session identity is missing");
		}
		return new Viewer(playerId, dimensionId, x, y, z);
	}

	private record Viewer(UUID playerId, String dimensionId, int x, int y, int z) { }
}
