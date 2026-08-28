package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Minecraft entity discovery and response over loader-neutral fluid observations. */
final class MinecraftEntityFluidInteraction {
	static final double NEUTRAL_SUBMERGENCE = 0.72;
	private static final double FULL_SUBMERGENCE_DRAG = 0.20;
	private static final double TICKS_PER_SECOND = 20.0;

	private MinecraftEntityFluidInteraction() { }

	static Frame capture(ServerLevel level, FluidRegion region, EntityMotionTracker motion) {
		List<Interaction> interactions = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, regionBounds(region),
				MinecraftEntityFluidInteraction::canAffect)) {
			motion.observe(entity.getUUID(), entity.position());
			Contact contact = sampleContact(region, entity);
			if (contact.submergedFraction() <= 0.0) continue;
			interactions.add(new Interaction(entity, contact,
					motion.displacementSincePreviousTick(entity.getUUID(), entity.position())));
		}
		return interactions.isEmpty() ? Frame.EMPTY : new Frame(List.copyOf(interactions));
	}

	static void applyEntityForces(Frame frame) {
		for (Interaction interaction : frame.interactions()) applyForce(interaction.entity(), interaction.contact());
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

	private record Interaction(Entity entity, Contact contact, Vec3 displacementBlocksThisTick) { }

	record Frame(List<Interaction> interactions) {
		static final Frame EMPTY = new Frame(List.of());

		Frame {
			interactions = List.copyOf(interactions);
		}

		int affectedEntities() { return interactions.size(); }

		List<BodyMotion> bodyMotions() {
			if (interactions.isEmpty()) return List.of();
			List<BodyMotion> motions = new ArrayList<>(interactions.size());
			for (Interaction interaction : interactions) {
				Contact contact = interaction.contact();
				motions.add(new BodyMotion(contact.localX(), contact.localY(), contact.localZ(),
						contact.displacedVolumeCubicMetres(), interaction.displacementBlocksThisTick()));
			}
			return List.copyOf(motions);
		}
	}

	private record Contact(
			double submergedFraction, Vec3 fluidVelocity, float localX, float localY, float localZ,
			double displacedVolumeCubicMetres
	) {
		private static final Contact DRY = new Contact(0.0, Vec3.ZERO, 0.0F, 0.0F, 0.0F, 0.0);
	}
}
