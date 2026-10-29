package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import mmm.chemistry.ChemicalTransferLedger;
import mmm.fluid.HostFluidState;
import mmm.fluid.GasTopologyBlockedException;
import mmm.simulation.controller.FluidRegionFootprint;
import mmm.units.SignedDyadic;
import mmm.world.CollisionField;
import mmm.world.CollisionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/** Minecraft collision capture and transactional liquid displacement for an explicitly supplied region. */
final class FluidRegionWorldInteraction {
	private FluidRegionWorldInteraction() { }

	static FluidRegion.CollisionStats refreshCollisions(ServerLevel level, FluidRegion region, int cellsPerBlock) {
		BlockPos origin = region.origin();
		FluidRegionFootprint footprint = region.footprint();
		int blockSizeX = region.sizeX() / cellsPerBlock;
		int blockSizeY = region.sizeY() / cellsPerBlock;
		int blockSizeZ = region.sizeZ() / cellsPerBlock;
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
		CollisionField collisionField = new MinecraftCollisionWorldPort(level, region).capture(captureRegion);
		return applyCapturedCollisions(region, collisionField, cellsPerBlock);
	}

	/** Applies one captured field on a private successor; failures leave the live solver untouched. */
	static FluidRegion.CollisionStats applyCapturedCollisions(
			FluidRegion region, CollisionField collisionField, int cellsPerBlock
	) {
		if (cellsPerBlock <= 0 || region.sizeX() % cellsPerBlock != 0
				|| region.sizeY() % cellsPerBlock != 0 || region.sizeZ() % cellsPerBlock != 0
				|| collisionField.sizeX() != region.sizeX() || collisionField.sizeY() != region.sizeY()
				|| collisionField.sizeZ() != region.sizeZ()) {
			throw new IllegalArgumentException("Collision capture does not match the region layout");
		}
		int blockSizeX = region.sizeX() / cellsPerBlock;
		int blockSizeY = region.sizeY() / cellsPerBlock;
		int blockSizeZ = region.sizeZ() / cellsPerBlock;
		List<PlannedBlock> plannedBlocks = new ArrayList<>();
		List<FluidDomainContracts.CollisionEdit> allEdits = new ArrayList<>();

		for (int blockZ = 0; blockZ < blockSizeZ; blockZ++) {
			for (int blockY = 0; blockY < blockSizeY; blockY++) {
				for (int blockX = 0; blockX < blockSizeX; blockX++) {
					List<FluidDomainContracts.CollisionEdit> blockEdits = new ArrayList<>();

					for (int localZ = 0; localZ < cellsPerBlock; localZ++) {
						for (int localY = 0; localY < cellsPerBlock; localY++) {
							for (int localX = 0; localX < cellsPerBlock; localX++) {
								int cellX = blockX * cellsPerBlock + localX;
								int cellY = blockY * cellsPerBlock + localY;
								int cellZ = blockZ * cellsPerBlock + localZ;
								int desired = collisionField.microMask(cellX, cellY, cellZ);
								if (region.solidMicroMask(cellX, cellY, cellZ) == desired) continue;
								FluidDomainContracts.CollisionEdit edit = new FluidDomainContracts.CollisionEdit(
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

		long baseGeneration = region.solverGeneration();
		try {
			HostFluidState domain = region.beginHostEdit();
			FluidRegion.CollisionStats stats = applyEdits(domain, allEdits, plannedBlocks);
			region.adoptCollisionCandidate(baseGeneration, domain);
			return stats;
		} catch (GasTopologyBlockedException blocked) {
			throw new FluidDomainContracts.CollisionEditBlockedException(
					"World collision leaves closed gas without a valid cavity", blocked);
		}
	}

	private static FluidRegion.CollisionStats applyEdits(
			HostFluidState domain, List<FluidDomainContracts.CollisionEdit> allEdits, List<PlannedBlock> plannedBlocks
	) {
		FluidDomainContracts.CollisionEditPlan plan = domain.planCollisionEdits(allEdits);
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
			long[] displacedMaterialMass = displacedMaterialVolume == null ? null
					: new long[displacedMaterialVolume.length];
			SignedDyadic displacedEnergy = SignedDyadic.ZERO;
			for (FluidDomainContracts.CollisionEdit edit : block.edits()) {
				if (domain.hasChemistryState()) {
					FluidDomainContracts.ChemicalDisplacement chemical = domain.setSolidMicroMaskChemical(
							edit.x(), edit.y(), edit.z(), edit.mask());
					displacedInBlock += chemical.volume();
					displacedChemistry = displacedChemistry.plus(chemical.ledger());
					displacedEnergy = displacedEnergy.add(SignedDyadic.of(chemical.internalEnergy()));
				} else if (displacedMaterialVolume != null) {
					FluidDomainContracts.MaterialDisplacement material = domain.setSolidMicroMaskMaterial(
							edit.x(), edit.y(), edit.z(), edit.mask());
					displacedInBlock += material.volume();
					for (FluidDomainContracts.MaterialPortion portion : material.portions()) {
						displacedMaterialVolume[portion.materialHandle()] += portion.volume();
						displacedMaterialMass[portion.materialHandle()] = Math.addExact(displacedMaterialMass[portion.materialHandle()], portion.massUnits());
					}
					displacedEnergy = displacedEnergy.add(SignedDyadic.of(material.internalEnergy()));
				} else {
					displacedInBlock += domain.setSolidMicroMask(edit.x(), edit.y(), edit.z(), edit.mask());
				}
				changedCells++;
			}
			if (displacedInBlock <= 0.0F) continue;
			displaced += displacedInBlock;
			List<FluidDomainContracts.MaterialPortion> displacedMaterials = new ArrayList<>();
			if (displacedMaterialVolume != null) for (int material = 0;
					material < displacedMaterialVolume.length; material++) {
				if (displacedMaterialVolume[material] > 0.0) {
					displacedMaterials.add(new FluidDomainContracts.MaterialPortion(material,
							displacedMaterialVolume[material], displacedMaterialMass[material]));
				}
			}
			restorations.add(new Displacement(block.centerX(), block.centerY(), block.centerZ(), displacedInBlock,
					displacedChemistry, List.copyOf(displacedMaterials), displacedEnergy.toDouble()));
		}
		for (Displacement request : restorations) {
			if (domain.hasChemistryState()) {
				FluidDomainContracts.ExactChemicalTransfer restoration = domain.hasThermalState()
						? domain.tryInsertExactChemical(request.centerX(), request.centerY(), request.centerZ(),
								request.chemistry(), 8, false, request.internalEnergy())
						: domain.tryInsertExactChemical(request.centerX(), request.centerY(), request.centerZ(),
								request.chemistry(), 8);
				if (!restoration.transferred()) {
					escaped += request.volume();
					domain.chemistry().recordEscapedSink(request.chemistry());
				}
			} else if (domain.hasMaterialState()) {
				FluidDomainContracts.ExactMixtureTransfer restoration = domain.hasThermalState()
						? domain.tryInsertExactMixture(request.centerX(), request.centerY(), request.centerZ(),
								request.materials(), 8, false, request.internalEnergy())
						: domain.tryInsertExactMixture(request.centerX(), request.centerY(), request.centerZ(),
								request.materials(), 8);
				if (!restoration.transferred()) {
					escaped += request.volume();
				}
			} else {
				FluidDomainContracts.VolumeAddition restoration = domain.addVolumeNearBudgeted(
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
			List<FluidDomainContracts.MaterialPortion> materials, double internalEnergy
	) { }

	private record PlannedBlock(int centerX, int centerY, int centerZ,
	                            List<FluidDomainContracts.CollisionEdit> edits) { }
}
