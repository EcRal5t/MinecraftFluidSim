package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Covers the Fabric-side moving-body wiring which the core-side gates cannot reach: the per-tick
 * displacement observation and its conversion into a loader-neutral request. Existing gates synthesise
 * requests directly, so a wiring regression that silently hands core a zero displacement passes them.
 */
public final class EntityFluidWiringValidation {
	private static final UUID BODY = new UUID(11L, 29L);

	private EntityFluidWiringValidation() { }

	public static void main(String[] args) {
		displacementObservationSpansConsecutiveTicks();
		movingBodyRequestsCarryObservedDisplacement();
		movingBodyRequestsSkipUnobservedBodies();
		movingBodyRequestsHonourTheDocumentedBudget();
		System.out.println("Entity fluid wiring passed");
	}

	private static void displacementObservationSpansConsecutiveTicks() {
		EntityMotionTracker tracker = new EntityMotionTracker();
		require(tracker.displacementSincePreviousTick(BODY, new Vec3(1.0, 2.0, 3.0)) == null,
				"the first observation of a body reported a displacement");
		tracker.observe(BODY, new Vec3(1.0, 2.0, 3.0));
		tracker.rotate();
		Vec3 walked = new Vec3(1.2, 2.0, 3.0);
		Vec3 displacement = tracker.displacementSincePreviousTick(BODY, walked);
		require(displacement != null && Math.abs(displacement.x - 0.2) <= 1.0E-12
						&& displacement.y == 0.0 && displacement.z == 0.0,
				"consecutive observations did not report the walked displacement: " + displacement);
		tracker.observe(BODY, walked);
		tracker.rotate();
		Vec3 resting = tracker.displacementSincePreviousTick(BODY, walked);
		require(resting != null && resting.lengthSqr() == 0.0,
				"a resting body reported a non-zero displacement: " + resting);
		tracker.rotate();
		require(tracker.displacementSincePreviousTick(BODY, walked) == null,
				"a body missed for one tick still reported a stale displacement");
	}

	private static void movingBodyRequestsCarryObservedDisplacement() {
		List<MinecraftEntityFluidInteraction.BodyMotion> motions = List.of(
				new MinecraftEntityFluidInteraction.BodyMotion(
						1.5F, 0.75F, 2.25F, 0.4, new Vec3(0.2, -0.05, 0.0)));
		List<MovingBodyFluidCoupling.Request> requests =
				MinecraftEntityFluidInteraction.movingBodyRequests(motions);
		require(requests.size() == 1, "one observed body did not produce exactly one request");
		MovingBodyFluidCoupling.Request request = requests.get(0);
		require(request.localXMetres() == 1.5F && request.localYMetres() == 0.75F
						&& request.localZMetres() == 2.25F
						&& request.displacedVolumeCubicMetres() == 0.4,
				"moving-body request did not carry the frozen contact geometry: " + request);
		require(request.velocityXMetresPerSecond() == 4.0 && request.velocityYMetresPerSecond() == -1.0
						&& request.velocityZMetresPerSecond() == 0.0,
				"moving-body request did not convert blocks/tick into metres/second: " + request);
	}

	private static void movingBodyRequestsSkipUnobservedBodies() {
		List<MinecraftEntityFluidInteraction.BodyMotion> motions = List.of(
				new MinecraftEntityFluidInteraction.BodyMotion(1.0F, 1.0F, 1.0F, 0.4, null),
				new MinecraftEntityFluidInteraction.BodyMotion(2.0F, 1.0F, 1.0F, 0.4, Vec3.ZERO));
		List<MovingBodyFluidCoupling.Request> requests =
				MinecraftEntityFluidInteraction.movingBodyRequests(motions);
		require(requests.size() == 1 && requests.get(0).localXMetres() == 2.0F,
				"an unobserved body was coupled instead of skipped: " + requests);
		require(requests.get(0).velocityXMetresPerSecond() == 0.0
						&& requests.get(0).velocityYMetresPerSecond() == 0.0
						&& requests.get(0).velocityZMetresPerSecond() == 0.0,
				"a resting body did not report a zero boundary velocity: " + requests.get(0));
	}

	private static void movingBodyRequestsHonourTheDocumentedBudget() {
		List<MinecraftEntityFluidInteraction.BodyMotion> motions = new ArrayList<>();
		for (int body = 0; body <= MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP; body++) {
			motions.add(new MinecraftEntityFluidInteraction.BodyMotion(
					1.0F + body, 1.0F, 1.0F, body, new Vec3(0.1, 0.0, 0.0)));
		}
		List<MovingBodyFluidCoupling.Request> requests =
				MinecraftEntityFluidInteraction.movingBodyRequests(motions);
		require(requests.size() == MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP,
				"moving-body request budget was not applied: " + requests.size());
		double maximum = 0.0;
		for (MovingBodyFluidCoupling.Request request : requests) {
			maximum = Math.max(maximum, request.displacedVolumeCubicMetres());
		}
		require(maximum == MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP,
				"moving-body budget did not keep the largest immersed volumes: " + maximum);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
