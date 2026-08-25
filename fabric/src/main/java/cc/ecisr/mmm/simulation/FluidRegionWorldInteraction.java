package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.thermal.SignedDyadic;
import cc.ecisr.mmm.world.CollisionField;
import cc.ecisr.mmm.world.CollisionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
		boolean[] interiorBlocks = new boolean[blockSizeX * blockSizeY * blockSizeZ];
		for (int blockZ = 0; blockZ < blockSizeZ; blockZ++) {
			for (int blockY = 0; blockY < blockSizeY; blockY++) {
				for (int blockX = 0; blockX < blockSizeX; blockX++) {
					BlockPos position = origin.offset(blockX, blockY, blockZ);
					interiorBlocks[(blockZ * blockSizeY + blockY) * blockSizeX + blockX] =
							footprint == null || footprint.containsInterior(position);
				}
			}
		}
		CollisionRegion captureRegion = new CollisionRegion(region.dimension().identifier().toString(),
				origin.getX(), origin.getY(), origin.getZ(), blockSizeX, blockSizeY, blockSizeZ,
				cellsPerBlock, interiorBlocks);
		CollisionField collisionField = new MinecraftCollisionWorldPort(level).capture(captureRegion);
		List<PlannedBlock> plannedBlocks = new ArrayList<>();
		List<FlipFluidDomain.CollisionEdit> allEdits = new ArrayList<>();

		for (int blockZ = 0; blockZ < blockSizeZ; blockZ++) {
			for (int blockY = 0; blockY < blockSizeY; blockY++) {
				for (int blockX = 0; blockX < blockSizeX; blockX++) {
					List<FlipFluidDomain.CollisionEdit> blockEdits = new ArrayList<>();

					for (int localZ = 0; localZ < cellsPerBlock; localZ++) {
						for (int localY = 0; localY < cellsPerBlock; localY++) {
							for (int localX = 0; localX < cellsPerBlock; localX++) {
								int cellX = blockX * cellsPerBlock + localX;
								int cellY = blockY * cellsPerBlock + localY;
								int cellZ = blockZ * cellsPerBlock + localZ;
								int desired = collisionField.microMask(cellX, cellY, cellZ);
								if (domain.solidMicroMask(cellX, cellY, cellZ) == desired) continue;
								FlipFluidDomain.CollisionEdit edit = new FlipFluidDomain.CollisionEdit(
										cellX, cellY, cellZ, desired);
								blockEdits.add(edit);
								allEdits.add(edit);
							}
						}
					}
					if (!blockEdits.isEmpty()) plannedBlocks.add(new PlannedBlock(
							blockX * cellsPerBlock + cellsPerBlock / 2,
							blockY * cellsPerBlock + cellsPerBlock / 2,
							blockZ * cellsPerBlock + cellsPerBlock / 2,
							List.copyOf(blockEdits)));
				}
			}
		}
		if (allEdits.isEmpty()) return FluidRegion.CollisionStats.EMPTY;

		FlipFluidDomain.CollisionEditPlan plan = domain.planCollisionEdits(allEdits);
		domain.beginCollisionEdits(plan);
		int changedCells = 0;
		float displaced = 0.0F;
		float escaped = 0.0F;
		boolean markerBudgetLimited = false;
		List<Displacement> restorations = new ArrayList<>();
		for (PlannedBlock block : plannedBlocks) {
			float displacedInBlock = 0.0F;
			ChemicalTransferLedger displacedChemistry = ChemicalTransferLedger.EMPTY;
			double[] displacedMaterialVolume = domain.hasMaterialState() && !domain.hasChemistryState()
					? new double[domain.materialRegistry().size()] : null;
			double[] displacedMaterialMass = displacedMaterialVolume == null ? null
					: new double[displacedMaterialVolume.length];
			SignedDyadic displacedEnergy = SignedDyadic.ZERO;
			for (FlipFluidDomain.CollisionEdit edit : block.edits()) {
				if (domain.hasChemistryState()) {
					FlipFluidDomain.ChemicalDisplacement chemical = domain.setSolidMicroMaskChemical(
							edit.x(), edit.y(), edit.z(), edit.mask());
					displacedInBlock += chemical.volume();
					displacedChemistry = displacedChemistry.plus(chemical.ledger());
					displacedEnergy = displacedEnergy.add(SignedDyadic.of(chemical.internalEnergy()));
				} else if (displacedMaterialVolume != null) {
					FlipFluidDomain.MaterialDisplacement material = domain.setSolidMicroMaskMaterial(
							edit.x(), edit.y(), edit.z(), edit.mask());
					displacedInBlock += material.volume();
					for (FlipFluidDomain.MaterialPortion portion : material.portions()) {
						displacedMaterialVolume[portion.materialHandle()] += portion.volume();
						displacedMaterialMass[portion.materialHandle()] += portion.mass();
					}
					displacedEnergy = displacedEnergy.add(SignedDyadic.of(material.internalEnergy()));
				} else {
					displacedInBlock += domain.setSolidMicroMask(edit.x(), edit.y(), edit.z(), edit.mask());
				}
				changedCells++;
			}
			if (displacedInBlock <= 0.0F) continue;
			displaced += displacedInBlock;
			List<FlipFluidDomain.MaterialPortion> displacedMaterials = new ArrayList<>();
			if (displacedMaterialVolume != null) for (int material = 0;
					material < displacedMaterialVolume.length; material++) {
				if (displacedMaterialVolume[material] > 0.0 && displacedMaterialMass[material] > 0.0) {
					displacedMaterials.add(new FlipFluidDomain.MaterialPortion(material,
							displacedMaterialVolume[material], displacedMaterialMass[material]));
				}
			}
			restorations.add(new Displacement(block.centerX(), block.centerY(), block.centerZ(), displacedInBlock,
					displacedChemistry, List.copyOf(displacedMaterials), displacedEnergy.toDouble()));
		}
		for (Displacement request : restorations) {
			if (domain.hasChemistryState()) {
				FlipFluidDomain.ExactChemicalTransfer restoration = domain.hasThermalState()
						? domain.tryInsertExactChemical(request.centerX(), request.centerY(), request.centerZ(),
								request.chemistry(), 8, false, request.internalEnergy())
						: domain.tryInsertExactChemical(request.centerX(), request.centerY(), request.centerZ(),
								request.chemistry(), 8);
				if (!restoration.transferred()) {
					escaped += request.volume();
					domain.chemicalParcels().recordEscapedSink(request.chemistry());
					markerBudgetLimited |= domain.particleCount() >= domain.maximumParticleCount();
				}
			} else if (domain.hasMaterialState()) {
				FlipFluidDomain.ExactMixtureTransfer restoration = domain.hasThermalState()
						? domain.tryInsertExactMixture(request.centerX(), request.centerY(), request.centerZ(),
								request.materials(), 8, false, request.internalEnergy())
						: domain.tryInsertExactMixture(request.centerX(), request.centerY(), request.centerZ(),
								request.materials(), 8);
				if (!restoration.transferred()) {
					escaped += request.volume();
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
		return MinecraftCollisionWorldPort.sampleCutCellMask(
				shape, localX, localY, localZ, cellsPerBlock, inset);
	}

	private record EntityFluidContact(double submergedFraction, Vec3 fluidVelocity) {
		private static final EntityFluidContact DRY = new EntityFluidContact(0.0, Vec3.ZERO);
	}

	private record Displacement(
			int centerX, int centerY, int centerZ, float volume, ChemicalTransferLedger chemistry,
			List<FlipFluidDomain.MaterialPortion> materials, double internalEnergy
	) { }

	private record PlannedBlock(int centerX, int centerY, int centerZ,
	                            List<FlipFluidDomain.CollisionEdit> edits) { }
}
