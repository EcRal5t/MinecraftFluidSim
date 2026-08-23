package com.example.simulation;

import com.example.chemistry.ChemicalTransferLedger;
import com.example.fluid.FlipFluidDomain;
import com.example.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/** Minecraft collision sampling and one-way entity coupling for an explicitly supplied region. */
final class FluidRegionWorldInteraction {
	private static final int ENTITY_SAMPLES_XZ = 3;
	private static final int ENTITY_SAMPLES_Y = 5;
	private static final double NEUTRAL_SUBMERGENCE = 0.72;
	private static final double FULL_SUBMERGENCE_DRAG = 0.20;
	private static final double SIMULATION_TICKS_PER_SECOND = 20.0;

	private FluidRegionWorldInteraction() { }

	static int applyEntityForces(ServerLevel level, FluidRegion region) {
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
		for (Entity entity : level.getEntities((Entity) null, bounds, FluidRegionWorldInteraction::canAffectEntity)) {
			EntityFluidContact contact = sampleEntityContact(region, entity);
			if (contact.submergedFraction() <= 0.0) continue;
			applyFluidForce(entity, contact);
			affected++;
		}
		return affected;
	}

	private static boolean canAffectEntity(Entity entity) {
		if (!entity.isAlive() || entity.isRemoved() || entity.noPhysics || entity.isPassenger() || entity.isInWater()) return false;
		return !(entity instanceof Player player) || (!player.isSpectator() && !player.getAbilities().flying);
	}

	private static EntityFluidContact sampleEntityContact(FluidRegion region, Entity entity) {
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		AABB box = entity.getBoundingBox();
		int totalSamples = ENTITY_SAMPLES_XZ * ENTITY_SAMPLES_Y * ENTITY_SAMPLES_XZ;
		int submergedSamples = 0;
		double velocityX = 0.0, velocityY = 0.0, velocityZ = 0.0;

		for (int sampleZ = 0; sampleZ < ENTITY_SAMPLES_XZ; sampleZ++) {
			for (int sampleY = 0; sampleY < ENTITY_SAMPLES_Y; sampleY++) {
				for (int sampleX = 0; sampleX < ENTITY_SAMPLES_XZ; sampleX++) {
					double worldX = sampleCoordinate(box.minX, box.maxX, sampleX, ENTITY_SAMPLES_XZ);
					double worldY = sampleCoordinate(box.minY, box.maxY, sampleY, ENTITY_SAMPLES_Y);
					double worldZ = sampleCoordinate(box.minZ, box.maxZ, sampleZ, ENTITY_SAMPLES_XZ);
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

		if (submergedSamples == 0) return EntityFluidContact.DRY;
		double inverseWetSamples = 1.0 / submergedSamples;
		return new EntityFluidContact(
				(double) submergedSamples / totalSamples,
				new Vec3(velocityX * inverseWetSamples, velocityY * inverseWetSamples, velocityZ * inverseWetSamples)
		);
	}

	private static double sampleCoordinate(double minimum, double maximum, int sample, int count) {
		return minimum + (maximum - minimum) * (sample + 0.5) / count;
	}

	private static void applyFluidForce(Entity entity, EntityFluidContact contact) {
		double submerged = contact.submergedFraction();
		double drag = FULL_SUBMERGENCE_DRAG * submerged;
		Vec3 targetVelocity = contact.fluidVelocity().scale(1.0 / SIMULATION_TICKS_PER_SECOND);
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

	static FluidRegion.CollisionStats refreshCollisions(ServerLevel level, FluidRegion region, int cellsPerBlock) {
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		FluidRegionFootprint footprint = region.footprint();
		int blockSizeX = domain.sizeX() / cellsPerBlock;
		int blockSizeY = domain.sizeY() / cellsPerBlock;
		int blockSizeZ = domain.sizeZ() / cellsPerBlock;
		int changedCells = 0;
		float displaced = 0.0F;
		float escaped = 0.0F;
		boolean markerBudgetLimited = false;
		double inset = 1.0E-5;
		List<Displacement> restorations = new ArrayList<>();

		for (int blockZ = 0; blockZ < blockSizeZ; blockZ++) {
			for (int blockY = 0; blockY < blockSizeY; blockY++) {
				for (int blockX = 0; blockX < blockSizeX; blockX++) {
					BlockPos pos = origin.offset(blockX, blockY, blockZ);
					boolean outsideScannedInterior = footprint != null && !footprint.containsInterior(pos);
					BlockState state = outsideScannedInterior ? Blocks.BEDROCK.defaultBlockState() : level.getBlockState(pos);
					boolean fullBlock = outsideScannedInterior || state.isCollisionShapeFullBlock(level, pos);
					VoxelShape shape = fullBlock ? Shapes.block() : state.getCollisionShape(level, pos);
					float displacedInBlock = 0.0F;
					ChemicalTransferLedger displacedChemistry = ChemicalTransferLedger.EMPTY;

					for (int localZ = 0; localZ < cellsPerBlock; localZ++) {
						for (int localY = 0; localY < cellsPerBlock; localY++) {
							for (int localX = 0; localX < cellsPerBlock; localX++) {
								int cellX = blockX * cellsPerBlock + localX;
								int cellY = blockY * cellsPerBlock + localY;
								int cellZ = blockZ * cellsPerBlock + localZ;
								int desired = fullBlock ? 0xFF : sampleCutCellMask(
										shape, localX, localY, localZ, cellsPerBlock, inset
								);
								if (domain.solidMicroMask(cellX, cellY, cellZ) == desired) continue;
								if (domain.hasChemistryState()) {
									FlipFluidDomain.ChemicalDisplacement chemical = domain.setSolidMicroMaskChemical(
											cellX, cellY, cellZ, desired);
									displacedInBlock += chemical.volume();
									displacedChemistry = displacedChemistry.plus(chemical.ledger());
								} else {
									displacedInBlock += domain.setSolidMicroMask(cellX, cellY, cellZ, desired);
								}
								changedCells++;
							}
						}
					}

					if (displacedInBlock > 0.0F) {
						displaced += displacedInBlock;
						int centerX = blockX * cellsPerBlock + cellsPerBlock / 2;
						int centerY = blockY * cellsPerBlock + cellsPerBlock / 2;
						int centerZ = blockZ * cellsPerBlock + cellsPerBlock / 2;
						restorations.add(new Displacement(
								centerX, centerY, centerZ, displacedInBlock, displacedChemistry));
					}
				}
			}
		}
		for (Displacement request : restorations) {
			if (domain.hasChemistryState()) {
				FlipFluidDomain.ExactChemicalTransfer restoration = domain.tryInsertExactChemical(
						request.centerX(), request.centerY(), request.centerZ(), request.chemistry(), 8);
				if (!restoration.transferred()) {
					escaped += request.volume();
					domain.chemicalParcels().recordEscapedSink(request.chemistry());
					markerBudgetLimited |= domain.particleCount() >= domain.maximumParticleCount();
				}
			} else {
				FlipFluidDomain.VolumeAddition restoration = domain.addVolumeNearBudgeted(
						request.centerX(), request.centerY(), request.centerZ(), request.volume(), 8
				);
				escaped += request.volume() - restoration.addedVolume();
				markerBudgetLimited |= restoration.markerBudgetLimited();
			}
		}

		return new FluidRegion.CollisionStats(changedCells, displaced, escaped, markerBudgetLimited);
	}

	static int sampleCutCellMask(
			VoxelShape shape,
			int localX,
			int localY,
			int localZ,
			int cellsPerBlock,
			double inset
	) {
		if (shape.isEmpty()) return 0;
		int subdivisions = cellsPerBlock * 2;
		double size = 1.0 / subdivisions;
		int mask = 0;
		for (int microZ = 0; microZ < 2; microZ++) for (int microY = 0; microY < 2; microY++) for (int microX = 0; microX < 2; microX++) {
			int sampleX = localX * 2 + microX;
			int sampleY = localY * 2 + microY;
			int sampleZ = localZ * 2 + microZ;
			double minX = sampleX * size + inset, minY = sampleY * size + inset, minZ = sampleZ * size + inset;
			double maxX = (sampleX + 1) * size - inset;
			double maxY = (sampleY + 1) * size - inset;
			double maxZ = (sampleZ + 1) * size - inset;
			if (Shapes.joinIsNotEmpty(shape, Shapes.box(minX, minY, minZ, maxX, maxY, maxZ), BooleanOp.AND)) {
				mask |= 1 << (microX + 2 * (microY + 2 * microZ));
			}
		}
		return mask;
	}

	private record EntityFluidContact(double submergedFraction, Vec3 fluidVelocity) {
		private static final EntityFluidContact DRY = new EntityFluidContact(0.0, Vec3.ZERO);
	}

	private record Displacement(
			int centerX, int centerY, int centerZ, float volume, ChemicalTransferLedger chemistry
	) { }
}
