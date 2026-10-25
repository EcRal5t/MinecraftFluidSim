package mmm.simulation;

import mmm.application.RegionRuntime;
import mmm.thermal.ThermalRegistry;
import mmm.world.MovingBodyFluidCoupling;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Captures region geometry/entity observations and applies the accepted paired entity response. */
final class MinecraftRegionInputs {
	static final double NEUTRAL_SUBMERGENCE = 0.72;
	private static final double BODY_REFERENCE_DENSITY_KG_M3 = 1000.0 * NEUTRAL_SUBMERGENCE;
	private static final double TICKS_PER_SECOND = 20.0;
	private static final int COLLISION_REFRESH_INTERVAL_TICKS = 5;

	private final int cellsPerBlock;
	private final MotionHistory motion = new MotionHistory();
	private final Map<UUID, List<Interaction>> pendingResponses = new HashMap<>();
	private final ThermalBoundaryResolver thermalBoundaries;

	MinecraftRegionInputs(ThermalRegistry thermalRegistry, int cellsPerBlock) {
		if (cellsPerBlock <= 0) throw new IllegalArgumentException("cells per block must be positive");
		this.cellsPerBlock = cellsPerBlock;
		this.thermalBoundaries = thermalRegistry == null ? null : new ThermalBoundaryResolver(thermalRegistry);
	}

	boolean boundariesDue(long tick) { return tick % COLLISION_REFRESH_INTERVAL_TICKS == 0L; }

	FluidRegion.CollisionStats refreshCollisions(ServerLevel level, FluidRegion region) {
		FluidRegion.CollisionStats collisions = FluidRegionWorldInteraction.refreshCollisions(
				level, region, cellsPerBlock);
		if (collisions.changedCells() > 0 || collisions.displacedCellVolume() > 0.0F
				|| collisions.escapedCellVolume() > 0.0F || collisions.markerBudgetLimited()) {
			region.setLastCollisionStats(collisions);
		}
		region.markCollisionRefreshed();
		if (collisions.changedCells() > 0) {
			region.wakeForActivity();
			region.wakeThermal(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
		}
		return collisions;
	}

	boolean refreshThermalBoundary(ServerLevel level, FluidRegion region) {
		return thermalBoundaries != null && thermalBoundaries.refresh(level, region, cellsPerBlock);
	}

	TickInputs captureTick(ServerLevel level, FluidRegion region, long tick, boolean thermalEnabled) {
		MinecraftGroupAvailability.prepare(level, region);
		EntityInputs entities = region.entityPhysicsEnabled() ? captureEntities(level, region) : EntityInputs.EMPTY;
		int affectedEntities = entities.affectedEntities();
		region.setLastEntityCoupling(new FluidRegion.EntityCouplingStats(affectedEntities, 0.0, 0.0, 0.0));
		if (affectedEntities > 0 || !new mmm.fluid.EntityBodyState(entities.movingBodies()).equals(region.entityBodyState())) {
			region.wakeForActivity();
			region.wakeThermal(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
		}
		RegionRuntime runtime = new RegionRuntime(tick, region.stepIntervalTicks(),
				region.hardOverrunStreak(), region.recoveryStreak(), region.canStep(),
				thermalEnabled && region.canStepThermal());
		return new TickInputs(runtime, entities.movingBodies(), affectedEntities);
	}

	void finishServerTick() { motion.rotate(); pendingResponses.clear(); }

	void commitEntityResponse(ServerLevel level, FluidRegion region, MovingBodyFluidCoupling.Result result) {
		var responses = pendingResponses.remove(region.id());
		if (responses == null) return;
		for (var interaction : responses) {
			if (matchesObservation(level, region, interaction) && interaction.entity().isOnFire()
					&& interaction.contact().waterContactVolumeCubicMetres() > 0)
				interaction.entity().clearFire();
		}
		var byId = new HashMap<UUID, Interaction>();
		for (var interaction : responses) byId.put(interaction.entity().getUUID(), interaction);
		for (var impulse : result.bodyImpulses()) {
			var interaction = byId.get(impulse.bodyId());
			if (interaction == null || !matchesObservation(level, region, interaction) || !canAffect(interaction.entity())) continue;
			double mass = interaction.contact().massKilograms();
			Vec3 delta = new Vec3(impulse.impulseXKilogramMetresPerSecond(),
					impulse.impulseYKilogramMetresPerSecond(), impulse.impulseZKilogramMetresPerSecond())
					.scale(1.0 / (mass * TICKS_PER_SECOND));
			interaction.entity().setDeltaMovement(interaction.entity().getDeltaMovement().add(delta));
			interaction.entity().hurtMarked = true;
			if (interaction.contact().submergedFraction() > .15) interaction.entity().resetFallDistance();
		}
	}

	private static boolean matchesObservation(ServerLevel level, FluidRegion region, Interaction interaction) {
		Entity entity = interaction.entity();
		if (entity.level() != level || !entity.isAlive() || entity.isRemoved()) return false;
		var box = entity.getBoundingBox();
		var origin = region.origin();
		var bounds = new MovingBodyFluidCoupling.Bounds(box.minX - origin.getX(), box.minY - origin.getY(),
				box.minZ - origin.getZ(), box.maxX - origin.getX(), box.maxY - origin.getY(), box.maxZ - origin.getZ());
		return bounds.equals(interaction.contact().bounds());
	}

	void discardEntityResponse(FluidRegion region) { pendingResponses.remove(region.id()); }

	private EntityInputs captureEntities(ServerLevel level, FluidRegion region) {
		pendingResponses.remove(region.id());
		List<Interaction> interactions = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, regionBounds(region),
				entity -> canAffect(entity) || entity.isAlive() && !entity.isRemoved() && entity.isOnFire())) {
			if (!MinecraftGroupAvailability.boxAvailable(region, entity.getBoundingBox())) continue;
			motion.observe(entity.getUUID(), entity.position());
			Contact contact = sampleContact(region, entity);

			interactions.add(new Interaction(entity, contact,
					motion.displacementSincePreviousTick(entity.getUUID(), entity.position())));
		}
		if (interactions.isEmpty()) return EntityInputs.EMPTY;
		List<BodyMotion> motions = new ArrayList<>(interactions.size());
		for (Interaction interaction : interactions) {
			Contact contact = interaction.contact();
			if (!canAffect(interaction.entity())) continue;
			motions.add(new BodyMotion(interaction.entity().getUUID(), contact.bounds(),
					contact.displacedVolumeCubicMetres(), interaction.displacementBlocksThisTick()));
		}
		var requests = movingBodyRequests(motions);
		pendingResponses.put(region.id(), List.copyOf(interactions));
		int affected = motions.size();
		for (var interaction : interactions)
			if (!canAffect(interaction.entity()) && interaction.entity().isOnFire()
					&& interaction.contact().waterContactVolumeCubicMetres() > 0) affected++;
		return new EntityInputs(requests, affected);
	}

	/**
	 * Converts frozen body observations into the loader-neutral requests a backend consumes.
	 * Bodies without a previous observation start at zero prescribed velocity; the documented
	 * per-step request budget rejects an oversized complete body set.
	 */
	static List<MovingBodyFluidCoupling.Request> movingBodyRequests(List<BodyMotion> motions) {
		if (motions.isEmpty()) return List.of();
		List<BodyMotion> ordered = new ArrayList<>(motions.size());
		for (BodyMotion motion : motions) {
			ordered.add(motion);
		}
		if (ordered.isEmpty()) return List.of();
		if (ordered.size() > MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP)
			throw new IllegalArgumentException("Entity target exceeds the complete region body budget");
		List<MovingBodyFluidCoupling.Request> requests = new ArrayList<>(ordered.size());
		for (BodyMotion motion : ordered) {
			Vec3 displacement = motion.displacementBlocksThisTick() == null ? Vec3.ZERO : motion.displacementBlocksThisTick();
			requests.add(new MovingBodyFluidCoupling.Request(motion.bodyId(), motion.bounds(),
					displacement.x * TICKS_PER_SECOND, displacement.y * TICKS_PER_SECOND,
					displacement.z * TICKS_PER_SECOND));
		}
		return List.copyOf(requests);
	}

	private static AABB regionBounds(FluidRegion region) {
		BlockPos origin = region.origin();
		float cellSize = region.solverConfig().cellSize();
		return new AABB(
				origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + region.sizeX() * cellSize,
				origin.getY() + region.sizeY() * cellSize,
				origin.getZ() + region.sizeZ() * cellSize
		);
	}

	private static boolean canAffect(Entity entity) {
		if (!entity.isAlive() || entity.isRemoved() || entity.noPhysics || entity.isPassenger() || entity.isInWater()) {
			return false;
		}
		return !(entity instanceof Player player) || (!player.isSpectator() && !player.getAbilities().flying);
	}

	private static Contact sampleContact(FluidRegion region, Entity entity) {
		BlockPos origin = region.origin();
		AABB box = entity.getBoundingBox();
		var sample = region.sampleBody(
				box.minX - origin.getX(), box.minY - origin.getY(), box.minZ - origin.getZ(),
				box.maxX - origin.getX(), box.maxY - origin.getY(), box.maxZ - origin.getZ());
		double displacedVolume = sample.displacedVolumeCubicMetres();

		double bodyVolume = box.getXsize() * box.getYsize() * box.getZsize();
		double submergedFraction = displacedVolume / bodyVolume;
		if (!(Double.isFinite(submergedFraction) && submergedFraction >= 0.0 && submergedFraction <= 1.0 + 1.0E-9)) {
			throw new IllegalStateException("Fluid/body overlap exceeded finite AABB volume");
		}
		submergedFraction = Math.min(1.0, submergedFraction);
		return new Contact(submergedFraction, displacedVolume, sample.waterContactVolumeCubicMetres(),
				new MovingBodyFluidCoupling.Bounds(box.minX - origin.getX(), box.minY - origin.getY(),
						box.minZ - origin.getZ(), box.maxX - origin.getX(), box.maxY - origin.getY(), box.maxZ - origin.getZ()),
				effectiveBodyMass(bodyVolume, region.solverConfig().gravity(), entity.getGravity()));
	}

	/** Host gravity calibrates the effective mass of an externally controlled body. */
	static double effectiveBodyMass(double volume, double solverGravity, double hostGravityBlocksPerTickSquared) {
		double hostGravity = hostGravityBlocksPerTickSquared * TICKS_PER_SECOND * TICKS_PER_SECOND;
		return volume * BODY_REFERENCE_DENSITY_KG_M3 * (solverGravity > 0 && hostGravity > 0 ? solverGravity / hostGravity : 1.0);
	}

	/** One frozen liquid observation; carries no Minecraft entity reference into core. */
	record BodyMotion(UUID bodyId, MovingBodyFluidCoupling.Bounds bounds, double displacedVolumeCubicMetres, Vec3 displacementBlocksThisTick) { }

	record TickInputs(
			RegionRuntime runtime,
			List<MovingBodyFluidCoupling.Request> movingBodies,
			int affectedEntities
	) { }

	private record Interaction(Entity entity, Contact contact, Vec3 displacementBlocksThisTick) { }

	private record EntityInputs(List<MovingBodyFluidCoupling.Request> movingBodies, int affectedEntities) {
		private static final EntityInputs EMPTY = new EntityInputs(List.of(), 0);
	}

	private record Contact(
			double submergedFraction, double displacedVolumeCubicMetres, double waterContactVolumeCubicMetres,
			MovingBodyFluidCoupling.Bounds bounds, double massKilograms) { }

	/**
	 * Consecutive-tick position history; an entity missed for one tick restarts unobserved.
	 * Minecraft's old-position fields are rewritten before this adapter runs, so they cannot preserve
	 * the client-authoritative displacement of a walking player across server ticks.
	 */
	static final class MotionHistory {
		private final Map<UUID, Vec3> committed = new HashMap<>();
		private final Map<UUID, Vec3> observed = new HashMap<>();

		/** Returns null until the entity has been observed on the immediately preceding server tick. */
		Vec3 displacementSincePreviousTick(UUID id, Vec3 currentPosition) {
			Vec3 previous = committed.get(id);
			return previous == null ? null : currentPosition.subtract(previous);
		}

		void observe(UUID id, Vec3 currentPosition) { observed.put(id, currentPosition); }

		void rotate() {
			committed.clear();
			committed.putAll(observed);
			observed.clear();
		}
	}
}
