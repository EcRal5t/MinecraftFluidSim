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
		return refreshCollisions(level,region,cellsPerBlock,updates,null);
	}

	static FluidRegion.CollisionStats refreshCollisions(ServerLevel level, FluidRegion region, int cellsPerBlock,
			MinecraftWorldUpdates updates, ExternalMovingStructures external) {
		return refreshCollisions(level,region,cellsPerBlock,updates,external,null);
	}

	static FluidRegion.CollisionStats refreshCollisions(ServerLevel level, FluidRegion region, int cellsPerBlock,
			MinecraftWorldUpdates updates, ExternalMovingStructures external, WorldThermalEnvironment environment) {
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
        var field=new MinecraftCollisionWorldPort(level,region,updates).capture(request);
        var plan=external==null?null:external.prepareHandoffs(region,field,
                block->MinecraftGroupAvailability.loaded(level,new BlockPos(block.x(),block.y(),block.z())),
                block->net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                        level.getBlockState(new BlockPos(block.x(),block.y(),block.z())).getBlock()).toString());
        var pipeBodies=PipeCollisionBodies.capture(level,region,members);
        return applyCapturedCollisions(region,field,cellsPerBlock,updates,screens,external,plan,environment,pipeBodies,block->{
            var state=level.getBlockState(new BlockPos(block.x(),block.y(),block.z()));
            return state.getBlock() instanceof mmm.block.ScreenBlock?state.getValue(mmm.block.ScreenBlock.FACING).getAxis().name():null;
        });
    }

	/** Applies one captured field on a private successor; failures leave the live solver untouched. */
	static FluidRegion.CollisionStats applyCapturedCollisions(
			FluidRegion region, CollisionField collisionField, int cellsPerBlock
	) { return applyCapturedCollisions(region,collisionField,cellsPerBlock,null,null,null,null,null,null,block->null); }

	static FluidRegion.CollisionStats applyCapturedCollisions(FluidRegion region, CollisionField field, int cellsPerBlock,
			ExternalMovingStructures external,
			java.util.function.Predicate<mmm.api.motion.MovingStructureApi.FixedBlock> available,
			java.util.function.Function<mmm.api.motion.MovingStructureApi.FixedBlock,String> blockIdAt) {
		var plan=external.prepareHandoffs(region,field,available,blockIdAt);
		return applyCapturedCollisions(region,field,cellsPerBlock,null,null,external,plan,null,null,block->null);
	}

    private static FluidRegion.CollisionStats applyCapturedCollisions(FluidRegion region, CollisionField collisionField,
                                                                       int cellsPerBlock, MinecraftWorldUpdates updates, List<mmm.fluid.ScreenFace> screens,
                                                                       ExternalMovingStructures external, ExternalMovingStructures.HandoffPlan plan,
                                                                       WorldThermalEnvironment environment,
                                                                       List<mmm.world.MovingBodyFluidCoupling.Request> pipeBodies,
                                                                       java.util.function.Function<mmm.api.motion.MovingStructureApi.FixedBlock,String> axisAt) {
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

        return applyPlans(region, allEdits, updates,screens,external,plan,environment,pipeBodies,axisAt);
    }

    private static FluidRegion.CollisionStats applyPlans(FluidRegion region, List<FluidDomainContracts.CollisionEdit> edits,
                                                         MinecraftWorldUpdates updates,List<mmm.fluid.ScreenFace> screens,
                                                         ExternalMovingStructures external, ExternalMovingStructures.HandoffPlan plan,
                                                         WorldThermalEnvironment environment,
                                                         List<mmm.world.MovingBodyFluidCoupling.Request> pipeBodies,
                                                         java.util.function.Function<mmm.api.motion.MovingStructureApi.FixedBlock,String> axisAt) {
        var previous = region.entityBodyState();
        var adjustedBodies=new ArrayList<>(previous.bodies().stream().filter(body->!PipeCollisionBodies.isPipeBody(body.bodyId())).toList());
        if(pipeBodies==null)adjustedBodies.addAll(previous.bodies().stream().filter(body->PipeCollisionBodies.isPipeBody(body.bodyId())).toList());
        else adjustedBodies.addAll(pipeBodies);
        PipeCollisionBodies.checkBudget(adjustedBodies);
        var adjusted=new mmm.fluid.EntityBodyState(adjustedBodies);
        var source = updates == null ? previous : new mmm.fluid.EntityBodyState(updates.sourceBodies(region));
        if(pipeBodies!=null) {
            var combined=new ArrayList<>(source.bodies().stream().filter(body->!PipeCollisionBodies.isPipeBody(body.bodyId())).toList());
            combined.addAll(pipeBodies);PipeCollisionBodies.checkBudget(combined);source=new mmm.fluid.EntityBodyState(combined);
        }
        if(plan!=null) {
            var combined=new ArrayList<>(source.bodies().stream().filter(body->!ExternalMovingStructures.external(body.bodyId())).toList());
            combined.addAll(plan.bodies().bodies().stream().filter(body->ExternalMovingStructures.external(body.bodyId())).toList());
            if(combined.size()>mmm.world.MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP)
                throw new ExternalMovingStructures.InputRejectedException("Structure handoff exceeds the complete body budget");
            source=new mmm.fluid.EntityBodyState(combined);
        }
        boolean pipeChange=!adjusted.equals(previous);
        boolean handoff = !source.equals(adjusted);
        var oldScreens=region.fixedGeometry().screens();
        boolean screenChange=screens!=null&&!oldScreens.equals(screens);
        long baseGeneration = region.solverGeneration();
        try (var thermal=environment==null || plan==null || plan.completed().isEmpty()?null:
                environment.prepareStructureHandoff(region,plan.completed(),axisAt)) {
            if (edits.isEmpty() && !handoff && !pipeChange && !screenChange && thermal==null) {
                if(external!=null)external.handoffsAccepted(region,plan);
                if(environment!=null && plan!=null && !plan.completed().isEmpty())environment.markDirty(region.dimension().identifier().toString());
                return FluidRegion.CollisionStats.EMPTY;
            }
            HostFluidState domain = region.beginHostEdit();
            if(pipeChange)domain.replaceCollisionBodies(List.of(),adjusted);
            var remaining = new ArrayList<>(edits);
            var handoffEdits = new ArrayList<FluidDomainContracts.CollisionEdit>();
            var nextBodies=source;
            remaining.removeIf(edit -> {
                boolean selected = touchesWorldBody(region, previous, edit) || touchesWorldBody(region, nextBodies, edit)
                        || plan!=null && plan.edits().contains(edit);
                if(!selected && (touchesExternalBody(region,previous,edit) || touchesExternalBody(region,nextBodies,edit)))
                    throw new ExternalMovingStructures.InputRejectedException("World edit overlaps an external structure without a matching handoff");
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
            if(thermal!=null)region.adoptStructureCandidate(baseGeneration,domain,thermal);
            else region.adoptCollisionCandidate(baseGeneration, domain);
            if(thermal!=null)environment.structureHandoffAccepted(region.dimension().identifier().toString());
            if(external!=null)external.handoffsAccepted(region,plan);
            if(environment!=null && plan!=null && !plan.completed().isEmpty())environment.markDirty(region.dimension().identifier().toString());
            return new FluidRegion.CollisionStats(edits.size()+(screenChange?1:0)+(pipeChange?1:0), displaced, 0.0F, false);
        } catch (GasTopologyBlockedException blocked) {
            if(plan!=null && !plan.completed().isEmpty())throw new ExternalMovingStructures.InputRejectedException(
                    "Structure handoff awaits a valid gas cavity",blocked);
            throw new FluidDomainContracts.CollisionEditBlockedException(
                    "World collision leaves closed gas without a valid cavity", blocked);
        } catch (FluidDomainContracts.CollisionEditBlockedException blocked) {
            if(plan!=null && !plan.completed().isEmpty())throw new ExternalMovingStructures.InputRejectedException(
                    "Structure handoff candidate rejected: "+blocked.getMessage(),blocked);
            throw blocked;
        }
    }

    private static boolean touchesExternalBody(FluidRegion region, mmm.fluid.EntityBodyState bodies,
                                               FluidDomainContracts.CollisionEdit edit) {
        return bodies.bodies().stream().anyMatch(body->ExternalMovingStructures.external(body.bodyId())
                && ExternalStructureGeometry.touches(region,body.bounds(),edit));
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
            if(state.getBlock() instanceof mmm.block.ScreenBlock && level.getBlockEntity(position) instanceof mmm.block.ScreenBlockEntity screen)
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
