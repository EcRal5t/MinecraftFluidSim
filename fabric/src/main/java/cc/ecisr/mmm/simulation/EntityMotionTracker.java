package cc.ecisr.mmm.simulation;

import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Carries the previous server-tick position of every observed entity so a moving body can be handed to
 * core as an actual displacement instead of a Minecraft position snapshot.
 *
 * <p>`Entity#oldPosition` cannot carry this: it is rewritten by `snapTo`/`absSnapTo` and by
 * `ServerLevel#tickNonPassenger` before `Entity#tick`, and client movement packets are applied by
 * `MinecraftServer#processPacketsAndTick` before any level entity ticks. Reading it at the end of a
 * server tick therefore reports only the residual in-tick motion and silently loses the dominant,
 * client-authoritative displacement of a walking player.
 */
final class EntityMotionTracker {
	private final Map<UUID, Vec3> committed = new HashMap<>();
	private final Map<UUID, Vec3> observed = new HashMap<>();

	/** Returns null until the entity has been observed on the immediately preceding server tick. */
	Vec3 displacementSincePreviousTick(UUID id, Vec3 currentPosition) {
		Vec3 previous = committed.get(id);
		return previous == null ? null : currentPosition.subtract(previous);
	}

	void observe(UUID id, Vec3 currentPosition) {
		observed.put(id, currentPosition);
	}

	/** Publishes this tick's observations; an entity missed for one tick restarts with no displacement. */
	void rotate() {
		committed.clear();
		committed.putAll(observed);
		observed.clear();
	}
}
