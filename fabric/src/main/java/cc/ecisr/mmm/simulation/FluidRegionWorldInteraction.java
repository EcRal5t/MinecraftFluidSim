package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.world.CollisionField;
import cc.ecisr.mmm.world.CollisionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/** Minecraft collision capture and transactional liquid displacement for an explicitly supplied region. */
final class FluidRegionWorldInteraction {
	private FluidRegionWorldInteraction() { }

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

	private record Displacement(
			int centerX, int centerY, int centerZ, float volume, ChemicalTransferLedger chemistry,
			List<FlipFluidDomain.MaterialPortion> materials, double internalEnergy
	) { }

	private record PlannedBlock(int centerX, int centerY, int centerZ,
	                            List<FlipFluidDomain.CollisionEdit> edits) { }
}
