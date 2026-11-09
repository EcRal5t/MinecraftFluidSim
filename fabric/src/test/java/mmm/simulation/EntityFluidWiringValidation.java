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
	private static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> DIMENSION = dimension("first");
	private static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> OTHER_DIMENSION = dimension("second");

	private EntityFluidWiringValidation() { }

	public static void main(String[] args) {
		displacementObservationSpansConsecutiveTicks();
		displacementObservationStartsOverAcrossDimensions();
		movingBodyRequestsCarryObservedDisplacement();
		movingBodyRequestsInitializeUnobservedBodies();
		movingBodyRequestsHonourTheDocumentedBudget();
		bodySweepIntoUnavailableGroupRetainsPreviousPose();
		pausedObservationsDoNotExhaustBodyBudget();
		manualStepRetainsObservedBodiesAndDrive();
		pistonShapeAndOccupationHandoff();
		vanillaPistonFrameUsesMovedBlock();
		System.out.println("Entity fluid wiring passed");
	}

	private static void vanillaPistonFrameUsesMovedBlock() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		var pos=new net.minecraft.core.BlockPos(2,1,1);
		var piston=new net.minecraft.world.level.block.piston.PistonMovingBlockEntity(pos,
				net.minecraft.world.level.block.Blocks.MOVING_PISTON.defaultBlockState(),
				net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),net.minecraft.core.Direction.EAST,true,false);
		var frame=MinecraftWorldUpdates.capturePiston(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,pos,net.minecraft.core.BlockPos.ZERO,piston);
		require(frame.source().size()==1 && frame.target().size()==1, "Vanilla moved block lost its shape");
		var bounds=frame.source().getFirst().bounds();
		require(bounds.minimumX()==1 && bounds.maximumX()==2 && bounds.minimumY()==1 && bounds.maximumY()==2,
				"Vanilla piston source pose differs from its moved stone block");
		require(frame.source().getFirst().bodyId().equals(frame.target().getFirst().bodyId()), "Piston identity changed during movement");
		var reverse=new net.minecraft.world.level.block.piston.PistonMovingBlockEntity(new net.minecraft.core.BlockPos(1,1,1),
				net.minecraft.world.level.block.Blocks.MOVING_PISTON.defaultBlockState(),
				net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),net.minecraft.core.Direction.EAST,false,false);
		var retracted=MinecraftWorldUpdates.capturePiston(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
				new net.minecraft.core.BlockPos(1,1,1),net.minecraft.core.BlockPos.ZERO,reverse);
		require(frame.source().getFirst().bodyId().equals(retracted.source().getFirst().bodyId()),
				"Pushed block identity changed when the piston reversed");
	}

	private static void pistonShapeAndOccupationHandoff() {
		var shape = net.minecraft.world.phys.shapes.Shapes.or(
				net.minecraft.world.phys.shapes.Shapes.box(.75,0,0,1,1,1),
				net.minecraft.world.phys.shapes.Shapes.box(0,.375,.375,.75,.625,.625));
		var boxes = MinecraftCollisionWorldPort.movingBoxes(shape,2);
		byte[] masks = MinecraftCollisionWorldPort.sampleBlockMasks(shape,2);
		for (int z=0;z<4;z++) for (int y=0;y<4;y++) for (int x=0;x<4;x++) {
			int cell=((z/2)*2+y/2)*2+x/2, bit=(x&1)+2*((y&1)+2*(z&1));
			double px=(x+.5)/4, py=(y+.5)/4, pz=(z+.5)/4;
			boolean covered=false;
			for(var box:boxes) covered |= px>box.minX && px<box.maxX && py>box.minY && py<box.maxY && pz>box.minZ && pz<box.maxZ;
			require(covered == ((masks[cell] & (1<<bit)) != 0), "Moving piston changed its sampled shape");
		}
		var bounds=new MovingBodyFluidCoupling.Bounds(.5,.5,.5,1,1,1);
		var body=new MovingBodyFluidCoupling.Request(BODY,bounds,1,0,0,.75,.75,.75,0,0,2,0,1.0/100);
		require(Math.abs(body.surfaceVelocity(1,1.25,.75,.75)-1.0)<1e-12, "Angular surface velocity differs");
		var definitions=mmm.fluid.TestFluidDefinitions.load();
		var domain=new mmm.fluid.HostFluidState(4,4,4,new mmm.fluid.FluidSolverConfig(.5f,9.81f,.05f,200,1e-5f,.002f),definitions);
		var fluids=new mmm.fluid.FluidMaterialRegistry(List.of(definitions.material().entry("mmm:water").material()));
		domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(definitions.material());
		domain.setSolid(1,1,1,true);domain.setVolume(2,1,1,.4f);domain.sealDomainBoundary();
		domain.bindGasRegistry(definitions.gas());domain.bindThermal(definitions.thermal(),fluids,300);
		long mass=domain.totalMassUnits();double volume=domain.totalVolumeExact(), energy=domain.totalInternalEnergy();
		domain.handoffCollisionBodies(List.of(new mmm.fluid.FluidDomainContracts.CollisionEdit(1,1,1,0)),new mmm.fluid.EntityBodyState(List.of(body)));
		require(domain.solidMicroMask(1,1,1)==0 && domain.entityBodyState().bodies().size()==1, "Piston did not leave fixed geometry");
		domain.handoffCollisionBodies(List.of(new mmm.fluid.FluidDomainContracts.CollisionEdit(1,1,1,255)),mmm.fluid.EntityBodyState.EMPTY);
		require(domain.solidMicroMask(1,1,1)==255 && domain.totalMassUnits()==mass && domain.totalVolumeExact()==volume && domain.totalInternalEnergy()==energy, "Piston handoff changed inventory or energy");
	}

	private static void displacementObservationSpansConsecutiveTicks() {
		MinecraftRegionInputs.MotionHistory tracker = new MinecraftRegionInputs.MotionHistory();
		require(tracker.displacementSincePreviousTick(DIMENSION, BODY, new Vec3(1.0, 2.0, 3.0)) == null,
				"the first observation of a body reported a displacement");
		tracker.observe(DIMENSION, BODY, new Vec3(1.0, 2.0, 3.0));
		tracker.rotate();
		Vec3 walked = new Vec3(1.2, 2.0, 3.0);
		Vec3 displacement = tracker.displacementSincePreviousTick(DIMENSION, BODY, walked);
		require(displacement != null && Math.abs(displacement.x - 0.2) <= 1.0E-12
						&& displacement.y == 0.0 && displacement.z == 0.0,
				"consecutive observations did not report the walked displacement: " + displacement);
		tracker.observe(DIMENSION, BODY, walked);
		tracker.rotate();
		Vec3 resting = tracker.displacementSincePreviousTick(DIMENSION, BODY, walked);
		require(resting != null && resting.lengthSqr() == 0.0,
				"a resting body reported a non-zero displacement: " + resting);
		tracker.rotate();
		require(tracker.displacementSincePreviousTick(DIMENSION, BODY, walked) == null,
				"a body missed for one tick still reported a stale displacement");
	}

	private static void displacementObservationStartsOverAcrossDimensions() {
		var tracker = new MinecraftRegionInputs.MotionHistory();
		tracker.observe(DIMENSION, BODY, new Vec3(800, 64, 400));
		tracker.rotate();
		var arrival = new Vec3(100, 70, 50);
		require(tracker.displacementSincePreviousTick(OTHER_DIMENSION, BODY, arrival) == null,
				"Dimension transfer subtracted positions in different worlds");
		tracker.observe(OTHER_DIMENSION, BODY, arrival);
		tracker.rotate();
		var displacement = tracker.displacementSincePreviousTick(OTHER_DIMENSION, BODY, arrival.add(.1, 0, 0));
		require(displacement != null && Math.abs(displacement.x - .1) < 1e-12,
				"Destination observations failed to resume normal motion");
		require(tracker.displacementSincePreviousTick(DIMENSION, BODY, new Vec3(800, 64, 400)) == null,
				"Destination observation retained a stale source-world pose");
	}

	private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension(String name) {
		var registry = net.minecraft.resources.ResourceKey.<net.minecraft.world.level.Level>createRegistryKey(
				net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm", "entity_validation_dimensions"));
		return net.minecraft.resources.ResourceKey.create(registry,
				net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm", name));
	}

	private static void manualStepRetainsObservedBodiesAndDrive() {
		UUID drive = new UUID(7, 9);
		var pose = bounds(1, 1, 1);
		var drivePose = bounds(2, 1, 1);
		var observed = List.of(new MinecraftRegionInputs.BodyMotion(BODY, pose, 0, new Vec3(.2, 0, 0)));
		var prescribed = List.of(new MovingBodyFluidCoupling.Request(drive, drivePose, 0, 0, 0));
		var requests = MinecraftRegionInputs.manualBodies(observed, prescribed);
		require(requests.size() == 2 && requests.get(0).bodyId().equals(BODY)
				&& requests.get(0).bounds().equals(pose) && requests.get(0).velocityXMetresPerSecond() == 0
				&& requests.get(1).bodyId().equals(drive) && requests.get(1).bounds().equals(drivePose)
				&& requests.get(1).velocityXMetresPerSecond() == 0,
				"manual step failed to retain stationary entity and drive occupancy");
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
