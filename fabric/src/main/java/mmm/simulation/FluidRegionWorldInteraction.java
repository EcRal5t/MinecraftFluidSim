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
        return refreshCollisions(level,region,cellsPerBlock,null);
    }

    static FluidRegion.CollisionStats refreshCollisions(ServerLevel level, FluidRegion region, int cellsPerBlock, MinecraftWorldUpdates updates) {
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
        var screens=captureScreens(level,region,members,cellsPerBlock);
        return applyCapturedCollisions(region,new MinecraftCollisionWorldPort(level,region,updates).capture(request),cellsPerBlock,updates,screens);
    }

	/** Applies one captured field on a private successor; failures leave the live solver untouched. */
	static FluidRegion.CollisionStats applyCapturedCollisions(
			FluidRegion region, CollisionField collisionField, int cellsPerBlock
	) { return applyCapturedCollisions(region,collisionField,cellsPerBlock,null,null); }

    private static FluidRegion.CollisionStats applyCapturedCollisions(FluidRegion region, CollisionField collisionField,
                                                                       int cellsPerBlock, MinecraftWorldUpdates updates, List<mmm.fluid.ScreenFace> screens) {
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

        return applyPlans(region, allEdits, updates,screens);
    }

    private static FluidRegion.CollisionStats applyPlans(FluidRegion region, List<FluidDomainContracts.CollisionEdit> edits,
                                                         MinecraftWorldUpdates updates,List<mmm.fluid.ScreenFace> screens) {
        var previous = region.entityBodyState();
        var source = updates == null ? previous : new mmm.fluid.EntityBodyState(updates.sourceBodies(region));
        boolean handoff = !source.equals(previous);
        var oldScreens=region.fixedGeometry().screens();
        boolean screenChange=screens!=null&&!oldScreens.equals(screens);
        if (edits.isEmpty() && !handoff && !screenChange) return FluidRegion.CollisionStats.EMPTY;
        long baseGeneration = region.solverGeneration();
        try {
            HostFluidState domain = region.beginHostEdit();
            var remaining = new ArrayList<>(edits);
            var handoffEdits = new ArrayList<FluidDomainContracts.CollisionEdit>();
            remaining.removeIf(edit -> {
                boolean selected = touchesWorldBody(region, previous, edit) || touchesWorldBody(region, source, edit);
                if (selected) handoffEdits.add(edit);
                return selected;
            });
            if (handoff || !handoffEdits.isEmpty()) {
                domain.handoffCollisionBodies(handoffEdits, source);
            }
            float displaced = remaining.isEmpty() ? 0.0F : domain.applyCollisionEdits(remaining);
            if(screenChange) {
                for(var screen:oldScreens)domain.removeScreenFace(screen.face());
                for(var screen:screens)domain.setScreenFace(screen.face(),screen.poreDiameter(),screen.openFraction());
            }
            region.adoptCollisionCandidate(baseGeneration, domain);
            return new FluidRegion.CollisionStats(edits.size()+(screenChange?1:0), displaced, 0.0F, false);
        } catch (GasTopologyBlockedException blocked) {
            throw new FluidDomainContracts.CollisionEditBlockedException(
                    "World collision leaves closed gas without a valid cavity", blocked);
        }
    }

    private static List<mmm.fluid.ScreenFace> captureScreens(ServerLevel level,FluidRegion region,
            java.util.Set<CollisionRegion.Block> members,int scale) {
        var grid=region.grid();var origin=region.origin();var screens=new java.util.TreeMap<Integer,mmm.fluid.ScreenFace>();
        for(var screen:region.fixedGeometry().screens())screens.put(screen.face(),screen);
        for(var block:members) {
            var position=origin.offset(block.x(),block.y(),block.z());
            if(!MinecraftGroupAvailability.loaded(level,position))continue;
            boolean available=true;
            for(int z=0;z<scale;z++)for(int y=0;y<scale;y++)for(int x=0;x<scale;x++) {
                int cell=grid.cell(block.x()*scale+x,block.y()*scale+y,block.z()*scale+z);
                if(cell>=0&&!region.groupAvailable(cell))available=false;
            }
            if(!available)continue;
            screens.entrySet().removeIf(entry->screenBlock(grid,entry.getKey(),scale).equals(block));
            var state=level.getBlockState(position);
            if(state.is(mmm.block.MmmBlocks.SCREEN) && level.getBlockEntity(position) instanceof mmm.block.ScreenBlockEntity screen)
                for(int face:IndustrialOperations.screenFaces(region,position,state))
                    screens.put(face,new mmm.fluid.ScreenFace(face,screen.poreDiameter(),screen.openFraction()));
        }
        return List.copyOf(screens.values());
    }
    private static CollisionRegion.Block screenBlock(mmm.spatial.SpatialGrid grid,int face,int scale) {
        int axis=grid.faceAxis(face),x=grid.faceCoordinate(face,0),y=grid.faceCoordinate(face,1),z=grid.faceCoordinate(face,2);
        if(axis==0)x-=scale/2;else if(axis==1)y-=scale/2;else z-=scale/2;
        return new CollisionRegion.Block(Math.floorDiv(x,scale),Math.floorDiv(y,scale),Math.floorDiv(z,scale));
    }

    private static boolean touchesWorldBody(FluidRegion region, mmm.fluid.EntityBodyState bodies,
                                            FluidDomainContracts.CollisionEdit edit) {
        double h = region.solverConfig().cellSize(), x = edit.x()*h, y = edit.y()*h, z = edit.z()*h;
        for (var body : bodies.bodies()) {
            if (!MinecraftWorldUpdates.worldBody(body.bodyId())) continue;
            var b = body.bounds();
            if (b.maximumX()>x && b.minimumX()<x+h && b.maximumY()>y && b.minimumY()<y+h
                    && b.maximumZ()>z && b.minimumZ()<z+h) return true;
        }
        return false;
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
