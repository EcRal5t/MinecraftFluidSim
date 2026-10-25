package mmm.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Associates loaded world inputs with the solver's physical activity groups. */
final class MinecraftGroupAvailability {
    private MinecraftGroupAvailability() { }

    static boolean prepare(ServerLevel level, FluidRegion region) {
        int sx=region.sizeX(),sy=region.sizeY(),sz=region.sizeZ();
        boolean[] available=sampleWorldCells(region.origin(),sx,sy,sz,region.solverConfig().cellSize(),
                (x,z)->level.getChunkSource().getChunkNow(x,z)!=null);
        boolean restored=false;
        for(int cell=0;cell<available.length;cell++) restored |= available[cell] && !region.worldCellAvailable(cell);
        boolean active=region.prepareGroupAvailability(available);
        if(restored) {
            region.requireCollisionRefresh();
            region.wakeForActivity();
            region.wakeThermal(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
        }
        return active;
    }

    static boolean[] sampleWorldCells(BlockPos origin,int sx,int sy,int sz,double h,
            java.util.function.BiPredicate<Integer,Integer> chunkLoaded) {
        boolean[] available=new boolean[sx*sy*sz];
        for(int z=0;z<sz;z++) for(int y=0;y<sy;y++) for(int x=0;x<sx;x++) {
            BlockPos block=origin.offset((int)Math.floor(x*h),(int)Math.floor(y*h),(int)Math.floor(z*h));
            boolean loaded=chunkLoaded.test(block.getX()>>4,block.getZ()>>4);
            for(Direction direction:Direction.values()) {
                BlockPos neighbor=block.relative(direction);
                loaded &= chunkLoaded.test(neighbor.getX()>>4,neighbor.getZ()>>4);
            }
            available[(z*sy+y)*sx+x]=loaded;
        }
        return available;
    }

    static String loadedShellFailure(ServerLevel level, FluidRegion region) {
        var footprint=region.footprint();
        for(long packed:footprint.boundaryBlocks()) {
            BlockPos position=BlockPos.of(packed);
            if(!loaded(level,position)) continue;
            var state=level.getBlockState(position);
            boolean solid=footprint.kind()==mmm.model.region.RegionGeometry.RegionKind.POOL
                    ? state.isCollisionShapeFullBlock(level,position) : mmm.block.MmmBlocks.isBoundary(state);
            if(!solid) return "Region wall or floor changed at "+position.toShortString();
        }
        for(long packed:footprint.atmosphereBlocks()) {
            BlockPos position=BlockPos.of(packed);
            if(loaded(level,position) && !level.getBlockState(position).isAir())
                return "Pool atmosphere opening changed at "+position.toShortString();
        }
        return null;
    }

    static boolean loaded(ServerLevel level,BlockPos position) {
        return level.getChunkSource().getChunkNow(position.getX()>>4,position.getZ()>>4)!=null;
    }

    static boolean boxAvailable(FluidRegion region, net.minecraft.world.phys.AABB box) {
        double h=region.solverConfig().cellSize(); BlockPos origin=region.origin();
        int minX=Math.max(0,(int)Math.floor((box.minX-origin.getX())/h));
        int minY=Math.max(0,(int)Math.floor((box.minY-origin.getY())/h));
        int minZ=Math.max(0,(int)Math.floor((box.minZ-origin.getZ())/h));
        int maxX=Math.min(region.sizeX()-1,(int)Math.floor((box.maxX-origin.getX())/h));
        int maxY=Math.min(region.sizeY()-1,(int)Math.floor((box.maxY-origin.getY())/h));
        int maxZ=Math.min(region.sizeZ()-1,(int)Math.floor((box.maxZ-origin.getZ())/h));
        for(int z=minZ;z<=maxZ;z++) for(int y=minY;y<=maxY;y++) for(int x=minX;x<=maxX;x++)
            if(!region.groupAvailable((z*region.sizeY()+y)*region.sizeX()+x)) return false;
        return true;
    }
}
