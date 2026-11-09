package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import mmm.fluid.HostFluidState;
import mmm.fluid.GasTopologyBlockedException;
import mmm.simulation.controller.FluidRegionFootprint;
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
        var members = new java.util.HashSet<CollisionRegion.Block>();
        if (region.footprint() != null) {
            for (long packed : region.footprint().interiorBlocks()) {
                var position = BlockPos.of(packed);
                if (region.footprint().containsInterior(position)) members.add(new CollisionRegion.Block(
                        position.getX()-origin.getX(),position.getY()-origin.getY(),position.getZ()-origin.getZ()));
            }
        } else {
            var grid = region.grid();
            for (int cell=0;cell<grid.cellCount();cell++) members.add(new CollisionRegion.Block(
                    grid.x(cell)/cellsPerBlock,grid.y(cell)/cellsPerBlock,grid.z(cell)/cellsPerBlock));
        }
        var request = new CollisionRegion(region.dimension().identifier().toString(),origin.getX(),origin.getY(),origin.getZ(),
                cellsPerBlock,region.grid(),members);
        return applyCapturedCollisions(region,new MinecraftCollisionWorldPort(level,region).capture(request),cellsPerBlock);
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
		if (!collisionField.grid().equals(region.grid())) throw new IllegalArgumentException("Collision capture topology differs");
        var grid = region.grid();
        var editsByBlock = new java.util.LinkedHashMap<CollisionRegion.Block,List<FluidDomainContracts.CollisionEdit>>();
        for (int cell=0;cell<grid.cellCount();cell++) {
            int x=grid.x(cell),y=grid.y(cell),z=grid.z(cell),desired=collisionField.microMask(x,y,z);
            if (region.solidMicroMask(x,y,z)==desired) continue;
            var block = new CollisionRegion.Block(x/cellsPerBlock,y/cellsPerBlock,z/cellsPerBlock);
            editsByBlock.computeIfAbsent(block,ignored->new ArrayList<>()).add(new FluidDomainContracts.CollisionEdit(x,y,z,desired));
        }
        var allEdits = new ArrayList<FluidDomainContracts.CollisionEdit>();
        for (var edits : editsByBlock.values()) allEdits.addAll(edits);

        return applyPlans(region, allEdits);
    }

    private static FluidRegion.CollisionStats applyPlans(FluidRegion region, List<FluidDomainContracts.CollisionEdit> allEdits) {
		if (allEdits.isEmpty()) return FluidRegion.CollisionStats.EMPTY;

		long baseGeneration = region.solverGeneration();
		try {
			HostFluidState domain = region.beginHostEdit();
			FluidRegion.CollisionStats stats = applyEdits(domain, allEdits);
			region.adoptCollisionCandidate(baseGeneration, domain);
			return stats;
		} catch (GasTopologyBlockedException blocked) {
			throw new FluidDomainContracts.CollisionEditBlockedException(
					"World collision leaves closed gas without a valid cavity", blocked);
		}
	}

	private static FluidRegion.CollisionStats applyEdits(HostFluidState domain,
            List<FluidDomainContracts.CollisionEdit> edits) {
        float displaced = domain.applyCollisionEdits(edits);
        return new FluidRegion.CollisionStats(edits.size(), displaced, 0.0F, false);
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

}
