package mmm.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Associates loaded world inputs with the solver's physical activity groups. */
final class MinecraftGroupAvailability {
    private MinecraftGroupAvailability() { }

    static boolean prepare(ServerLevel level, FluidRegion region) {
        int sx=region.sizeX(),sy=region.sizeY(),sz=region.sizeZ();
        boolean[] available=sampleWorldCells(region.origin(),region.grid(),region.solverConfig().cellSize(),
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

    static boolean[] sampleWorldCells(BlockPos origin, mmm.spatial.SpatialGrid grid, double h,
            java.util.function.BiPredicate<Integer,Integer> chunkLoaded) {
        boolean[] available = new boolean[grid.cellCount()];
        for (int cell = 0; cell < available.length; cell++) {
            BlockPos block = origin.offset((int)Math.floor(grid.x(cell)*h), (int)Math.floor(grid.y(cell)*h), (int)Math.floor(grid.z(cell)*h));
            boolean loaded = chunkLoaded.test(block.getX() >> 4, block.getZ() >> 4);
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = block.relative(direction);
                loaded &= chunkLoaded.test(neighbor.getX() >> 4, neighbor.getZ() >> 4);
            }
            available[cell] = loaded;
        }
        return available;
    }

    static boolean[] sampleWorldCells(BlockPos origin,int sx,int sy,int sz,double h,
            java.util.function.BiPredicate<Integer,Integer> chunkLoaded) {
        return sampleWorldCells(origin, mmm.spatial.SpatialGrid.rectangular(sx,sy,sz), h, chunkLoaded);
    }

    static boolean loaded(ServerLevel level,BlockPos position) {
        return level.getChunkSource().getChunkNow(position.getX()>>4,position.getZ()>>4)!=null;
    }

    static boolean boxAvailable(FluidRegion region, net.minecraft.world.phys.AABB box) {
        double h=region.solverConfig().cellSize(); BlockPos origin=region.origin();
        int minX=Math.max(0,(int)Math.floor((box.minX-origin.getX())/h));
        int minY=Math.max(0,(int)Math.floor((box.minY-origin.getY())/h));
        int minZ=Math.max(0,(int)Math.floor((box.minZ-origin.getZ())/h));
        int maxX=Math.min(region.sizeX()-1,(int)Math.ceil((box.maxX-origin.getX())/h)-1);
        int maxY=Math.min(region.sizeY()-1,(int)Math.ceil((box.maxY-origin.getY())/h)-1);
        int maxZ=Math.min(region.sizeZ()-1,(int)Math.ceil((box.maxZ-origin.getZ())/h)-1);
        for(int z=minZ;z<=maxZ;z++) for(int y=minY;y<=maxY;y++) for(int x=minX;x<=maxX;x++)
            { int cell = region.grid().cell(x,y,z); if (cell >= 0 && !region.groupAvailable(cell)) return false; }
        return true;
    }
}
