package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.FlipFluidDomain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Minecraft entity discovery and response over loader-neutral fluid observations. */
final class MinecraftEntityFluidInteraction {
	private static final int SAMPLES_XZ = 3;
	private static final int SAMPLES_Y = 5;
	private static final double NEUTRAL_SUBMERGENCE = 0.72;
	private static final double FULL_SUBMERGENCE_DRAG = 0.20;
	private static final double TICKS_PER_SECOND = 20.0;

	private MinecraftEntityFluidInteraction() { }

	static int applyForces(ServerLevel level, FluidRegion region) {
		BlockPos origin = region.origin();
		FlipFluidDomain domain = region.domain();
		float cellSize = domain.config().cellSize();
		AABB bounds = new AABB(
				origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + domain.sizeX() * cellSize,
				origin.getY() + domain.sizeY() * cellSize,
				origin.getZ() + domain.sizeZ() * cellSize
		);
		int affected = 0;
		for (Entity entity : level.getEntities((Entity) null, bounds, MinecraftEntityFluidInteraction::canAffect)) {
			Contact contact = sampleContact(region, entity);
			if (contact.submergedFraction() <= 0.0) continue;
			applyForce(entity, contact);
			affected++;
		}
		return affected;
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
		int totalSamples = SAMPLES_XZ * SAMPLES_Y * SAMPLES_XZ;
		int submergedSamples = 0;
		double velocityX = 0.0, velocityY = 0.0, velocityZ = 0.0;
		for (int sampleZ = 0; sampleZ < SAMPLES_XZ; sampleZ++) {
			for (int sampleY = 0; sampleY < SAMPLES_Y; sampleY++) {
				for (int sampleX = 0; sampleX < SAMPLES_XZ; sampleX++) {
					double worldX = sampleCoordinate(box.minX, box.maxX, sampleX, SAMPLES_XZ);
					double worldY = sampleCoordinate(box.minY, box.maxY, sampleY, SAMPLES_Y);
					double worldZ = sampleCoordinate(box.minZ, box.maxZ, sampleZ, SAMPLES_XZ);
					float localX = (float) (worldX - origin.getX());
					float localY = (float) (worldY - origin.getY());
					float localZ = (float) (worldZ - origin.getZ());
					if (!domain.containsFluidAt(localX, localY, localZ)) continue;
					FlipFluidDomain.VelocitySample velocity = domain.sampleVelocity(localX, localY, localZ);
					velocityX += velocity.x();
					velocityY += velocity.y();
					velocityZ += velocity.z();
					submergedSamples++;
				}
			}
		}
		if (submergedSamples == 0) return Contact.DRY;
		double inverseWetSamples = 1.0 / submergedSamples;
		return new Contact((double) submergedSamples / totalSamples,
				new Vec3(velocityX * inverseWetSamples, velocityY * inverseWetSamples,
						velocityZ * inverseWetSamples));
	}

	private static double sampleCoordinate(double minimum, double maximum, int sample, int count) {
		return minimum + (maximum - minimum) * (sample + 0.5) / count;
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

	private record Contact(double submergedFraction, Vec3 fluidVelocity) {
		private static final Contact DRY = new Contact(0.0, Vec3.ZERO);
	}
}
