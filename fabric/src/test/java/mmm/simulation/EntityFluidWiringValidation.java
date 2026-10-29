package mmm.simulation;

import mmm.world.MovingBodyFluidCoupling;
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
		movingBodyRequestsInitializeUnobservedBodies();
		movingBodyRequestsHonourTheDocumentedBudget();
		bodySweepIntoUnavailableGroupRetainsPreviousPose();
		pausedObservationsDoNotExhaustBodyBudget();
		System.out.println("Entity fluid wiring passed");
	}

	private static void displacementObservationSpansConsecutiveTicks() {
		MinecraftRegionInputs.MotionHistory tracker = new MinecraftRegionInputs.MotionHistory();
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
		List<MinecraftRegionInputs.BodyMotion> motions = List.of(
				new MinecraftRegionInputs.BodyMotion(BODY, bounds(1.5, .75, 2.25), 0.4, new Vec3(0.2, -0.05, 0.0)));
		List<MovingBodyFluidCoupling.Request> requests =
				MinecraftRegionInputs.movingBodyRequests(motions);
		require(requests.size() == 1, "one observed body did not produce exactly one request");
		MovingBodyFluidCoupling.Request request = requests.get(0);
		require(request.localXMetres() == 1.5F && request.localYMetres() == 0.75F
						&& request.localZMetres() == 2.25F
						&& request.bounds().equals(bounds(1.5, .75, 2.25)) && request.bodyId().equals(BODY),
				"moving-body request did not carry the frozen contact geometry: " + request);
		require(request.velocityXMetresPerSecond() == 4.0 && request.velocityYMetresPerSecond() == -1.0
						&& request.velocityZMetresPerSecond() == 0.0,
				"moving-body request did not convert blocks/tick into metres/second: " + request);
	}

	private static void movingBodyRequestsInitializeUnobservedBodies() {
		List<MinecraftRegionInputs.BodyMotion> motions = List.of(
				new MinecraftRegionInputs.BodyMotion(BODY, bounds(1, 1, 1), 0.4, null),
				new MinecraftRegionInputs.BodyMotion(new UUID(11, 30), bounds(2, 1, 1), 0.4, Vec3.ZERO));
		List<MovingBodyFluidCoupling.Request> requests =
				MinecraftRegionInputs.movingBodyRequests(motions);
		require(requests.size() == 2 && requests.get(0).localXMetres() == 1.0F,
				"an unobserved body did not initialize its geometry: " + requests);
		require(requests.get(0).velocityXMetresPerSecond() == 0.0
						&& requests.get(0).velocityYMetresPerSecond() == 0.0
						&& requests.get(0).velocityZMetresPerSecond() == 0.0,
				"a resting body did not report a zero boundary velocity: " + requests.get(0));
	}

	private static void movingBodyRequestsHonourTheDocumentedBudget() {
		List<MinecraftRegionInputs.BodyMotion> motions = new ArrayList<>();
		for (int body = 0; body <= MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP; body++) {
			motions.add(new MinecraftRegionInputs.BodyMotion(new UUID(0, body), bounds(1 + body, 1, 1), body, new Vec3(0.1, 0.0, 0.0)));
		}
		try {
			MinecraftRegionInputs.movingBodyRequests(motions);
			throw new AssertionError("Oversized entity target was partially accepted");
		} catch (IllegalArgumentException expected) {
			require(expected.getMessage().contains("budget"), "Unexpected entity target rejection: " + expected);
		}
	}

	private static void bodySweepIntoUnavailableGroupRetainsPreviousPose() {
		var old = new MovingBodyFluidCoupling.Request(BODY,
				new MovingBodyFluidCoupling.Bounds(.1, 0, 0, .4, .4, .4), 2, 0, 0);
		var target = new MovingBodyFluidCoupling.Request(BODY,
				new MovingBodyFluidCoupling.Bounds(1.6, 0, 0, 1.9, .4, .4), 3, 0, 0);
		var geometry = mmm.fluid.FixedGeometrySnapshot.fromMasks(5, 1, 1,
				new byte[]{0, 0, (byte) 255, 0, 0});
		var previous = new mmm.fluid.EntityBodyState(List.of(old));
		var graph = new mmm.fluid.PhysicalActivityGraph(5, 1, 1, geometry,
				new int[]{1, 1, -1, 2, 2}, new int[0], List.of(), previous, .5);
		graph.joinBodyMotion(previous, new mmm.fluid.EntityBodyState(List.of(target)), .5);
		boolean[] available = graph.available(new boolean[]{true, true, false, false, false});
		require(!available[0] && !available[3], "body sweep failed to pause the old and target groups");
		var accepted = MinecraftRegionInputs.retainBlockedBodies(List.of(old), List.of(target),
				bounds -> available[(int) (bounds.minimumX() / .5)]);
		require(accepted.size() == 1 && accepted.get(0).bounds().equals(old.bounds())
				&& accepted.get(0).velocityXMetresPerSecond() == 0,
				"blocked body did not retain its old pose at zero velocity: " + accepted);
	}

	private static void pausedObservationsDoNotExhaustBodyBudget() {
		var old = new MovingBodyFluidCoupling.Request(BODY,
				new MovingBodyFluidCoupling.Bounds(.1, 0, 0, .4, .4, .4), 2, 0, 0);
		var motions = new ArrayList<MinecraftRegionInputs.BodyMotion>();
		for (int i = 0; i <= MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP; i++)
			motions.add(new MinecraftRegionInputs.BodyMotion(new UUID(1, i),
					new MovingBodyFluidCoupling.Bounds(1.6, 0, 0, 1.9, .4, .4), 0, Vec3.ZERO));
		// The last target joins the old active group to the unavailable group.
		motions.add(new MinecraftRegionInputs.BodyMotion(BODY,
				new MovingBodyFluidCoupling.Bounds(1.6, 0, 0, 1.9, .4, .4), 0, Vec3.ZERO));
		motions.add(new MinecraftRegionInputs.BodyMotion(new UUID(2, 0),
				new MovingBodyFluidCoupling.Bounds(3.1, 0, 0, 3.4, .4, .4), 0, Vec3.ZERO));
		var targets = MinecraftRegionInputs.bodyObservations(motions);
		var geometry = mmm.fluid.FixedGeometrySnapshot.fromMasks(7, 1, 1,
				new byte[]{0, 0, (byte) 255, 0, 0, (byte) 255, 0});
		var previous = new mmm.fluid.EntityBodyState(List.of(old));
		var graph = new mmm.fluid.PhysicalActivityGraph(7, 1, 1, geometry,
				new int[]{1, 1, -1, 2, 2, -1, 3}, new int[0], List.of(), previous, .5);
		graph.joinBodyMotion(previous, targets, .5);
		boolean[] available = graph.available(new boolean[]{true, true, false, false, false, false, true});
		require(!available[0] && available[6], "all target sweeps must contribute to activity groups");
		var accepted = MinecraftRegionInputs.retainBlockedBodies(previous.bodies(), targets,
				bounds -> available[(int) (bounds.minimumX() / .5)]);
		MinecraftRegionInputs.checkBodyBudget(accepted);
		require(accepted.size() == 2 && accepted.stream().anyMatch(body -> body.bodyId().equals(BODY)
				&& body.bounds().equals(old.bounds()) && body.velocityXMetresPerSecond() == 0),
				"paused observations exhausted the budget or lost the blocked old pose: " + accepted);
	}

	private static MovingBodyFluidCoupling.Bounds bounds(double x, double y, double z) {
		return new MovingBodyFluidCoupling.Bounds(x - .2, y - .4, z - .2, x + .2, y + .4, z + .2);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
