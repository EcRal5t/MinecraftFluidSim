package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.application.RegionRuntime;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Freezes region boundary/entity observations and applies the paired per-tick entity response. */
final class MinecraftRegionInputs {
	static final double NEUTRAL_SUBMERGENCE = 0.72;
	private static final double FULL_SUBMERGENCE_DRAG = 0.20;
	private static final double TICKS_PER_SECOND = 20.0;
	private static final int COLLISION_REFRESH_INTERVAL_TICKS = 5;

	private final int cellsPerBlock;
	private final MotionHistory motion = new MotionHistory();
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
		EntityInputs entities = region.entityPhysicsEnabled() ? captureEntities(level, region) : EntityInputs.EMPTY;
		int affectedEntities = entities.affectedEntities();
		region.setLastEntityCoupling(new FluidRegion.EntityCouplingStats(affectedEntities, 0.0, 0.0, 0.0));
		if (affectedEntities > 0) {
			region.wakeForActivity();
			region.wakeThermal(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
		}
		RegionRuntime runtime = new RegionRuntime(tick, region.stepIntervalTicks(),
				region.hardOverrunStreak(), region.recoveryStreak(), region.canStep(),
				thermalEnabled && region.canStepThermal());
		return new TickInputs(runtime, entities.movingBodies(), affectedEntities);
	}

	void finishServerTick() { motion.rotate(); }

	private EntityInputs captureEntities(ServerLevel level, FluidRegion region) {
		List<Interaction> interactions = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, regionBounds(region),
				MinecraftRegionInputs::canAffect)) {
			motion.observe(entity.getUUID(), entity.position());
			Contact contact = sampleContact(region, entity);
			if (contact.submergedFraction() <= 0.0) continue;
			interactions.add(new Interaction(entity, contact,
					motion.displacementSincePreviousTick(entity.getUUID(), entity.position())));
		}
		if (interactions.isEmpty()) return EntityInputs.EMPTY;
		List<BodyMotion> motions = new ArrayList<>(interactions.size());
		for (Interaction interaction : interactions) {
			Contact contact = interaction.contact();
			applyForce(interaction.entity(), contact);
			motions.add(new BodyMotion(contact.localX(), contact.localY(), contact.localZ(),
					contact.displacedVolumeCubicMetres(), interaction.displacementBlocksThisTick()));
		}
		return new EntityInputs(movingBodyRequests(motions), interactions.size());
	}

	/**
	 * Converts frozen body observations into the loader-neutral requests a backend consumes.
	 * Bodies without a previous observation carry no displacement and are not coupled; the documented
	 * per-step request budget keeps the largest immersed volumes when a region holds more bodies.
	 */
	static List<MovingBodyFluidCoupling.Request> movingBodyRequests(List<BodyMotion> motions) {
		if (motions.isEmpty()) return List.of();
		List<BodyMotion> ordered = new ArrayList<>(motions.size());
		for (BodyMotion motion : motions) {
			if (motion.displacementBlocksThisTick() != null) ordered.add(motion);
		}
		if (ordered.isEmpty()) return List.of();
		if (ordered.size() > MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP) {
			ordered.sort(Comparator.comparingDouble(BodyMotion::displacedVolumeCubicMetres).reversed());
			ordered = new ArrayList<>(ordered.subList(0, MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP));
		}
		List<MovingBodyFluidCoupling.Request> requests = new ArrayList<>(ordered.size());
		for (BodyMotion motion : ordered) {
			Vec3 displacement = motion.displacementBlocksThisTick();
			requests.add(new MovingBodyFluidCoupling.Request(
					motion.localXMetres(), motion.localYMetres(), motion.localZMetres(),
					displacement.x * TICKS_PER_SECOND, displacement.y * TICKS_PER_SECOND,
					displacement.z * TICKS_PER_SECOND, motion.displacedVolumeCubicMetres()));
		}
		return List.copyOf(requests);
	}

	private static AABB regionBounds(FluidRegion region) {
		BlockPos origin = region.origin();
		float cellSize = region.domain().config().cellSize();
		return new AABB(
				origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + region.domain().sizeX() * cellSize,
				origin.getY() + region.domain().sizeY() * cellSize,
				origin.getZ() + region.domain().sizeZ() * cellSize
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
		var sample = region.domain().sampleAxisAlignedBody(
				box.minX - origin.getX(), box.minY - origin.getY(), box.minZ - origin.getZ(),
				box.maxX - origin.getX(), box.maxY - origin.getY(), box.maxZ - origin.getZ());
		double displacedVolume = sample.displacedVolumeCubicMetres();
		if (!(displacedVolume > 0.0)) return Contact.DRY;
		double bodyVolume = box.getXsize() * box.getYsize() * box.getZsize();
		double submergedFraction = displacedVolume / bodyVolume;
		if (!(Double.isFinite(submergedFraction) && submergedFraction > 0.0 && submergedFraction <= 1.0 + 1.0E-9)) {
			throw new IllegalStateException("Fluid/body overlap exceeded finite AABB volume");
		}
		submergedFraction = Math.min(1.0, submergedFraction);
		return new Contact(submergedFraction,
				new Vec3(sample.velocityXMetresPerSecond(), sample.velocityYMetresPerSecond(),
						sample.velocityZMetresPerSecond()),
				(float) sample.centerXMetres(), (float) sample.centerYMetres(),
				(float) sample.centerZMetres(), displacedVolume);
	}

	private static void applyForce(Entity entity, Contact contact) {
		entity.setDeltaMovement(responseVelocity(entity.getDeltaMovement(), contact.fluidVelocity(),
				contact.submergedFraction(), entity.getGravity(), entity.isNoGravity()));
		entity.hurtMarked = true;
		if (contact.submergedFraction() > 0.15) entity.resetFallDistance();
	}

	static Vec3 responseVelocity(
			Vec3 currentBlocksPerTick, Vec3 fluidMetresPerSecond,
			double submergedFraction, double gravityBlocksPerTickSquared, boolean noGravity
	) {
		if (currentBlocksPerTick == null || fluidMetresPerSecond == null
				|| !(Double.isFinite(currentBlocksPerTick.x) && Double.isFinite(currentBlocksPerTick.y)
				&& Double.isFinite(currentBlocksPerTick.z) && Double.isFinite(fluidMetresPerSecond.x)
				&& Double.isFinite(fluidMetresPerSecond.y) && Double.isFinite(fluidMetresPerSecond.z)
				&& Double.isFinite(submergedFraction) && submergedFraction > 0.0 && submergedFraction <= 1.0
				&& Double.isFinite(gravityBlocksPerTickSquared) && gravityBlocksPerTickSquared >= 0.0)) {
			throw new IllegalArgumentException("Entity fluid response input is invalid");
		}
		double drag = FULL_SUBMERGENCE_DRAG * submergedFraction;
		Vec3 targetVelocity = fluidMetresPerSecond.scale(1.0 / TICKS_PER_SECOND);
		Vec3 next = currentBlocksPerTick.add(targetVelocity.subtract(currentBlocksPerTick).scale(drag));
		if (!noGravity) {
			double buoyancy = Math.min(0.20,
					gravityBlocksPerTickSquared * submergedFraction / NEUTRAL_SUBMERGENCE);
			next = next.add(0.0, buoyancy, 0.0);
		}
		return next;
	}

	/** One frozen liquid observation; carries no Minecraft entity reference into core. */
	record BodyMotion(
			float localXMetres,
			float localYMetres,
			float localZMetres,
			double displacedVolumeCubicMetres,
			Vec3 displacementBlocksThisTick
	) { }

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
			double submergedFraction, Vec3 fluidVelocity, float localX, float localY, float localZ,
			double displacedVolumeCubicMetres
	) {
		private static final Contact DRY = new Contact(0.0, Vec3.ZERO, 0.0F, 0.0F, 0.0F, 0.0);
	}

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
