package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Minecraft entity discovery and response over loader-neutral fluid observations. */
final class MinecraftEntityFluidInteraction {
	private static final double NEUTRAL_SUBMERGENCE = 0.72;
	private static final double FULL_SUBMERGENCE_DRAG = 0.20;
	private static final double TICKS_PER_SECOND = 20.0;

	private MinecraftEntityFluidInteraction() { }

	static Frame capture(ServerLevel level, FluidRegion region) {
		BlockPos origin = region.origin();
		FlipFluidDomain domain = region.domain();
		float cellSize = domain.config().cellSize();
		AABB bounds = new AABB(
				origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + domain.sizeX() * cellSize,
				origin.getY() + domain.sizeY() * cellSize,
				origin.getZ() + domain.sizeZ() * cellSize
		);
		List<Interaction> interactions = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, bounds, MinecraftEntityFluidInteraction::canAffect)) {
			Contact contact = sampleContact(region, entity);
			if (contact.submergedFraction() <= 0.0) continue;
			interactions.add(new Interaction(entity, contact, entity.getDeltaMovement()));
		}
		return interactions.isEmpty() ? Frame.EMPTY : new Frame(List.copyOf(interactions));
	}

	static void applyEntityForces(Frame frame) {
		for (Interaction interaction : frame.interactions()) applyForce(interaction.entity(), interaction.contact());
	}

	static FluidRegion.EntityCouplingStats applyToLiquid(Frame frame, FlipFluidDomain domain) {
		double impulseX = 0.0, impulseY = 0.0, impulseZ = 0.0;
		for (Interaction interaction : frame.interactions()) {
			Vec3 bodyVelocity = interaction.velocityBlocksPerTick().scale(TICKS_PER_SECOND);
			Contact contact = interaction.contact();
			MovingBodyFluidCoupling.Result coupling = domain.applyMovingBodyCoupling(
					new MovingBodyFluidCoupling.Request(contact.localX(), contact.localY(), contact.localZ(),
							bodyVelocity.x, bodyVelocity.y, bodyVelocity.z, contact.displacedVolumeCubicMetres()));
			impulseX += coupling.impulseXKilogramMetresPerSecond();
			impulseY += coupling.impulseYKilogramMetresPerSecond();
			impulseZ += coupling.impulseZKilogramMetresPerSecond();
		}
		return new FluidRegion.EntityCouplingStats(frame.interactions().size(), impulseX, impulseY, impulseZ);
	}

	private static boolean canAffect(Entity entity) {
		if (!entity.isAlive() || entity.isRemoved() || entity.noPhysics || entity.isPassenger() || entity.isInWater()) {
			return false;
		}
		return !(entity instanceof Player player) || (!player.isSpectator() && !player.getAbilities().flying);
	}

	private static Contact sampleContact(FluidRegion region, Entity entity) {
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		AABB box = entity.getBoundingBox();
		FlipFluidDomain.BodyFluidSample sample = domain.sampleAxisAlignedBody(
				box.minX - origin.getX(), box.minY - origin.getY(), box.minZ - origin.getZ(),
				box.maxX - origin.getX(), box.maxY - origin.getY(), box.maxZ - origin.getZ());
		double displacedVolume = sample.displacedVolumeCubicMetres();
		if (!(displacedVolume > 0.0)) return Contact.DRY;
		double bodyVolume = box.getXsize() * box.getYsize() * box.getZsize();
		double submergedFraction = displacedVolume / bodyVolume;
		if (!(Double.isFinite(submergedFraction) && submergedFraction > 0.0 && submergedFraction <= 1.0 + 1.0E-9)) {
			throw new IllegalStateException("Fluid/body overlap exceeded finite AABB volume");
		}
		return new Contact(submergedFraction,
				new Vec3(sample.velocityXMetresPerSecond(), sample.velocityYMetresPerSecond(),
						sample.velocityZMetresPerSecond()),
				(float) sample.centerXMetres(), (float) sample.centerYMetres(),
				(float) sample.centerZMetres(), displacedVolume);
	}

	private static void applyForce(Entity entity, Contact contact) {
		double submerged = contact.submergedFraction();
		double drag = FULL_SUBMERGENCE_DRAG * submerged;
		Vec3 targetVelocity = contact.fluidVelocity().scale(1.0 / TICKS_PER_SECOND);
		Vec3 current = entity.getDeltaMovement();
		Vec3 next = current.add(targetVelocity.subtract(current).scale(drag));
		if (!entity.isNoGravity()) {
			double buoyancy = Math.min(0.20, entity.getGravity() * submerged / NEUTRAL_SUBMERGENCE);
			next = next.add(0.0, buoyancy, 0.0);
		}
		entity.setDeltaMovement(next);
		entity.hurtMarked = true;
		if (submerged > 0.15) entity.resetFallDistance();
	}

	private record Interaction(Entity entity, Contact contact, Vec3 velocityBlocksPerTick) { }

	record Frame(List<Interaction> interactions) {
		static final Frame EMPTY = new Frame(List.of());

		Frame {
			interactions = List.copyOf(interactions);
		}

		int affectedEntities() { return interactions.size(); }
	}

	private record Contact(
			double submergedFraction, Vec3 fluidVelocity, float localX, float localY, float localZ,
			double displacedVolumeCubicMetres
	) {
		private static final Contact DRY = new Contact(0.0, Vec3.ZERO, 0.0F, 0.0F, 0.0F, 0.0);
	}
}
