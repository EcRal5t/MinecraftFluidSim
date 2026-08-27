package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.world.MovingBodyStepResult;
import cc.ecisr.mmm.world.MovingBodyDefinition;
import cc.ecisr.mmm.world.MovingBodyDefinitionRegistry;
import cc.ecisr.mmm.world.MovingBodySurfaceSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Main-thread entity freeze, exact revalidation and joint candidate settlement. */
final class MinecraftEntityFluidInteraction {
	private static final double TICKS_PER_SECOND = 20.0;

	private MinecraftEntityFluidInteraction() { }

	static FrozenStep freeze(ServerLevel level, FluidRegion region, MovingBodyDefinitionRegistry definitions) {
		if (definitions == null) throw new BlockedException("Moving-body definition registry is unavailable");
		BlockPos origin = region.origin();
		double cellSize = region.domain().config().cellSize();
		double timeStepSeconds = region.domain().config().timeStep();
		AABB bounds = new AABB(origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + region.sizeX() * cellSize,
				origin.getY() + region.sizeY() * cellSize,
				origin.getZ() + region.sizeZ() * cellSize);
		ArrayList<Entity> entities = new ArrayList<>(level.getEntities(
				(Entity) null, bounds, MinecraftEntityFluidInteraction::canCouple));
		entities.sort(Comparator.comparing(Entity::getUUID));
		ArrayList<FrozenEntity> frozen = new ArrayList<>(Math.min(
				entities.size(), MovingBodySurfaceSnapshot.MAXIMUM_BODIES));
		ArrayList<MovingBodySurfaceSnapshot.Body> bodies = new ArrayList<>(frozen.size());
		for (Entity entity : entities) {
			if (frozen.size() == MovingBodySurfaceSnapshot.MAXIMUM_BODIES) {
				throw new BlockedException("Moving-body entity budget exhausted");
			}
			AABB afterWorld = entity.getBoundingBox();
			if (!strictlyInside(afterWorld, bounds)) continue;
			AABB beforeWorld = afterWorld.move(entity.xOld - entity.getX(), entity.yOld - entity.getY(),
					entity.zOld - entity.getZ());
			if (!strictlyInside(beforeWorld, bounds)) {
				throw new BlockedException("Moving entity entered the region without an in-region pre-pose");
			}
			Vec3 preVelocityPerTick = entity.getDeltaMovement();
			double velocityX = (afterWorld.minX - beforeWorld.minX) / timeStepSeconds;
			double velocityY = (afterWorld.minY - beforeWorld.minY) / timeStepSeconds;
			double velocityZ = (afterWorld.minZ - beforeWorld.minZ) / timeStepSeconds;
			long transientId = frozen.size();
			Definition definition = definition(entity, definitions);
			MovingBodySurfaceSnapshot.BoxPose before = localPose(beforeWorld, origin);
			MovingBodySurfaceSnapshot.BoxPose after = localPose(afterWorld, origin);
			MovingBodySurfaceSnapshot.Body body = new MovingBodySurfaceSnapshot.Body(
					transientId, before, after,
					velocityX, velocityY, velocityZ,
					definition.mode() == MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC
							? preVelocityPerTick.x * TICKS_PER_SECOND : velocityX,
					definition.mode() == MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC
							? preVelocityPerTick.y * TICKS_PER_SECOND : velocityY,
					definition.mode() == MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC
							? preVelocityPerTick.z * TICKS_PER_SECOND : velocityZ,
					definition.mode(), definition.massKilograms());
			frozen.add(new FrozenEntity(transientId, entity.getUUID(), entity, beforeWorld, afterWorld,
					preVelocityPerTick, definition));
			bodies.add(body);
		}
		return new FrozenStep(new MovingBodySurfaceSnapshot(definitions.fingerprint(), bodies), List.copyOf(frozen));
	}

	static FrozenStep empty() { return new FrozenStep(MovingBodySurfaceSnapshot.EMPTY, List.of()); }

	private static boolean canCouple(Entity entity) {
		if (!entity.isAlive() || entity.isRemoved() || entity.noPhysics || entity.isPassenger()) return false;
		return !(entity instanceof Player player) || (!player.isSpectator() && !player.getAbilities().flying);
	}

	private static Definition definition(Entity entity, MovingBodyDefinitionRegistry registry) {
		String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		for (MovingBodyDefinition definition : registry.definitions()) {
			boolean matches = switch (definition.selectorKind()) {
				case ENTITY_TYPE -> definition.selectorId().equals(typeId);
				case ENTITY_TAG -> BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(entity.getType()).is(
						TagKey.create(Registries.ENTITY_TYPE, Identifier.parse(definition.selectorId())));
			};
			if (matches) return new Definition(definition.couplingMode(), definition.massKilograms());
		}
		return new Definition(MovingBodySurfaceSnapshot.CouplingMode.KINEMATIC_EXTERNAL, 0.0);
	}

	private static MovingBodySurfaceSnapshot.BoxPose localPose(AABB world, BlockPos origin) {
		return new MovingBodySurfaceSnapshot.BoxPose(
				world.minX - origin.getX(), world.minY - origin.getY(), world.minZ - origin.getZ(),
				world.maxX - origin.getX(), world.maxY - origin.getY(), world.maxZ - origin.getZ());
	}

	private static boolean strictlyInside(AABB box, AABB bounds) {
		return box.minX > bounds.minX && box.minY > bounds.minY && box.minZ > bounds.minZ
				&& box.maxX < bounds.maxX && box.maxY < bounds.maxY && box.maxZ < bounds.maxZ;
	}

	record FrozenStep(MovingBodySurfaceSnapshot snapshot, List<FrozenEntity> entities) {
		FrozenStep {
			if (snapshot == null || entities == null || snapshot.bodies().size() != entities.size()) {
				throw new IllegalArgumentException("Frozen moving-body step is inconsistent");
			}
			entities = List.copyOf(entities);
		}

		int affectedEntities() { return entities.size(); }

		FluidRegion.EntityCouplingStats commit(ServerLevel level, FluidRegion region,
				FluidRegion.PreparedStepCandidate prepared) {
			FluidStepCandidate candidate = prepared.candidate();
			List<MovingBodyStepResult> results = candidate.movingBodyResults();
			if (results.size() != entities.size()) {
				throw new BlockedException("Backend moving-body result count changed");
			}
			ArrayList<Entity> live = new ArrayList<>(entities.size());
			for (int index = 0; index < entities.size(); index++) {
				FrozenEntity frozen = entities.get(index);
				MovingBodyStepResult result = results.get(index);
				if (result.transientId() != frozen.transientId()
						|| result.couplingMode() != frozen.definition().mode()) {
					throw new BlockedException("Backend moving-body identity changed");
				}
				Entity entity = level.getEntity(frozen.uuid());
				if (entity == null || entity != frozen.identity() || !entity.isAlive() || entity.isRemoved()
						|| !sameAabbRaw(entity.getBoundingBox(), frozen.afterPose())
						|| !sameVectorRaw(entity.getDeltaMovement(), frozen.preVelocityPerTick())) {
					throw new BlockedException("Moving entity changed or disappeared before joint commit");
				}
				live.add(entity);
			}

			Vec3[] previousVelocity = new Vec3[live.size()];
			for (int index = 0; index < live.size(); index++) previousVelocity[index] = live.get(index).getDeltaMovement();
			boolean fluidCommitted = false;
			try {
				region.commitPreparedStepCandidate(prepared);
				fluidCommitted = true;
				double impulseX = 0.0, impulseY = 0.0, impulseZ = 0.0;
				for (int index = 0; index < live.size(); index++) {
					MovingBodyStepResult result = results.get(index);
					if (result.couplingMode() == MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC) {
						Entity entity = live.get(index);
						entity.setDeltaMovement(result.finalVelocityXMetresPerSecond() / TICKS_PER_SECOND,
								result.finalVelocityYMetresPerSecond() / TICKS_PER_SECOND,
								result.finalVelocityZMetresPerSecond() / TICKS_PER_SECOND);
						entity.hurtMarked = true;
						if (result.pressureImpulseYKilogramMetresPerSecond() != 0.0
								|| result.viscousImpulseYKilogramMetresPerSecond() != 0.0) entity.resetFallDistance();
					}
					impulseX += result.pressureImpulseXKilogramMetresPerSecond()
							+ result.viscousImpulseXKilogramMetresPerSecond();
					impulseY += result.pressureImpulseYKilogramMetresPerSecond()
							+ result.viscousImpulseYKilogramMetresPerSecond();
					impulseZ += result.pressureImpulseZKilogramMetresPerSecond()
							+ result.viscousImpulseZKilogramMetresPerSecond();
				}
				return new FluidRegion.EntityCouplingStats(live.size(), impulseX, impulseY, impulseZ);
			} catch (RuntimeException failure) {
				for (int index = 0; index < live.size(); index++) {
					live.get(index).setDeltaMovement(previousVelocity[index]);
					live.get(index).hurtMarked = true;
				}
				if (fluidCommitted) region.rollbackPreparedStepCandidate(prepared);
				throw failure;
			}
		}

		boolean rejectContinuousDisplacement(ServerLevel level) {
			boolean moved = false;
			ArrayList<Entity> live = new ArrayList<>(entities.size());
			for (FrozenEntity frozen : entities) {
				Entity entity = level.getEntity(frozen.uuid());
				if (entity == null || entity != frozen.identity() || !entity.isAlive() || entity.isRemoved()
						|| !sameAabbRaw(entity.getBoundingBox(), frozen.afterPose())
						|| !sameVectorRaw(entity.getDeltaMovement(), frozen.preVelocityPerTick())) return false;
				moved |= !sameAabbRaw(frozen.beforePose(), frozen.afterPose());
				live.add(entity);
			}
			if (!moved) return false;
			for (int index = 0; index < live.size(); index++) {
				Entity entity = live.get(index);
				FrozenEntity frozen = entities.get(index);
				AABB before = frozen.beforePose(), after = frozen.afterPose();
				entity.setPos(entity.getX() + before.minX - after.minX,
						entity.getY() + before.minY - after.minY,
						entity.getZ() + before.minZ - after.minZ);
				entity.setDeltaMovement(Vec3.ZERO);
				entity.hurtMarked = true;
			}
			return true;
		}
	}

	private static boolean sameAabbRaw(AABB first, AABB second) {
		return raw(first.minX, second.minX) && raw(first.minY, second.minY) && raw(first.minZ, second.minZ)
				&& raw(first.maxX, second.maxX) && raw(first.maxY, second.maxY) && raw(first.maxZ, second.maxZ);
	}

	private static boolean sameVectorRaw(Vec3 first, Vec3 second) {
		return raw(first.x, second.x) && raw(first.y, second.y) && raw(first.z, second.z);
	}

	private static boolean raw(double first, double second) {
		return Double.doubleToRawLongBits(first) == Double.doubleToRawLongBits(second);
	}

	private record FrozenEntity(long transientId, UUID uuid, Entity identity, AABB beforePose, AABB afterPose,
	                            Vec3 preVelocityPerTick, Definition definition) { }
	private record Definition(MovingBodySurfaceSnapshot.CouplingMode mode, double massKilograms) { }

	static final class BlockedException extends IllegalStateException {
		BlockedException(String message) { super(message); }
		BlockedException(String message, Throwable cause) { super(message, cause); }
	}
}
