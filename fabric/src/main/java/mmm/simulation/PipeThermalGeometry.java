package mmm.simulation;

import mmm.block.PipeBlock;
import mmm.block.PipeGeometry;
import mmm.block.PipeScreenBlock;
import mmm.block.PipeValveBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Geometric inventories shared by the world air graph and the separate pipe cavity. */
final class PipeThermalGeometry {
    record Surface(int x,int y,int z,int direction,int mask,double area) { }
    private record SurfaceKey(int x,int y,int z,int direction) { }
    private static final class SurfaceCount { int mask,count; }
    private static final java.util.Map<BlockState,java.util.List<Surface>> SURFACES=new java.util.HashMap<>();
    private PipeThermalGeometry() { }
    static boolean isPipe(BlockState state) {
        return state.getBlock() instanceof PipeBlock || state.getBlock() instanceof PipeValveBlock
                || state.getBlock() instanceof PipeScreenBlock;
    }
    static double outsideVolume(BlockState state) { return 1.0 - volume(PipeGeometry.envelope(state)); }
    static double cavityOverlap(BlockState state,long mask) {
        var boxes=PipeGeometry.bore(state).toAabbs();double result=0;
        for(int bit=0;bit<64;bit++)if((mask&(1L<<bit))!=0) {
            double minX=(bit%4)*.25,minY=(bit/4%4)*.25,minZ=(bit/16)*.25;
            for(var box:boxes) {
                double dx=Math.max(0,Math.min(minX+.25,box.maxX)-Math.max(minX,box.minX));
                double dy=Math.max(0,Math.min(minY+.25,box.maxY)-Math.max(minY,box.minY));
                double dz=Math.max(0,Math.min(minZ+.25,box.maxZ)-Math.max(minZ,box.minZ));
                result+=dx*dy*dz;
            }
        }
        return result;
    }
    static java.util.List<Surface> outsideSurfaces(BlockState state) {
        return SURFACES.computeIfAbsent(state,PipeThermalGeometry::sampleOutsideSurfaces);
    }
    private static java.util.List<Surface> sampleOutsideSurfaces(BlockState state) {
        var wall=PipeGeometry.shell(state).toAabbs();var envelope=PipeGeometry.envelope(state).toAabbs();
        boolean[] solid=new boolean[4096],owned=new boolean[4096];
        for(int z=0;z<16;z++)for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
            int i=(z*16+y)*16+x;double a=(x+.5)/16,b=(y+.5)/16,c=(z+.5)/16;
            for(var box:wall)if(box.contains(a,b,c)){solid[i]=true;break;}
            for(var box:envelope)if(box.contains(a,b,c)){owned[i]=true;break;}
        }
        var groups=new java.util.HashMap<SurfaceKey,SurfaceCount>();
        for(int z=0;z<16;z++)for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(solid[(z*16+y)*16+x])
            for(var direction:Direction.values()) {
                int xx=x+direction.getStepX(),yy=y+direction.getStepY(),zz=z+direction.getStepZ();
                if(xx>=0&&yy>=0&&zz>=0&&xx<16&&yy<16&&zz<16&&owned[(zz*16+yy)*16+xx])continue;
                int cx=Math.floorDiv(xx,8),cy=Math.floorDiv(yy,8),cz=Math.floorDiv(zz,8);
                int bit=Math.floorMod(xx,8)/4+2*(Math.floorMod(yy,8)/4)+4*(Math.floorMod(zz,8)/4);
                int axis=direction.getAxis()==Direction.Axis.X?0:direction.getAxis()==Direction.Axis.Y?1:2;
                int side=direction.getAxisDirection()==Direction.AxisDirection.POSITIVE?0:1;
                var key=new SurfaceKey(cx,cy,cz,2*axis+side);var group=groups.computeIfAbsent(key,ignored->new SurfaceCount());
                group.mask|=1<<bit;group.count++;
            }
        return groups.entrySet().stream().map(e->new Surface(e.getKey().x(),e.getKey().y(),e.getKey().z(),
                e.getKey().direction(),e.getValue().mask,e.getValue().count/256.0))
                .sorted(java.util.Comparator.comparingInt(Surface::z).thenComparingInt(Surface::y).thenComparingInt(Surface::x).thenComparingInt(Surface::direction)).toList();
    }
    static boolean outsideCell(VoxelShape envelope, int x, int y, int z) {
        return outsideVolume(envelope,x,y,z,.25)>0;
    }
    static double[] halfCellVolumes(BlockState state) {
        var envelope=PipeGeometry.envelope(state); var result=new double[8];
        for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++)
            result[x+2*y+4*z]=outsideVolume(envelope,x,y,z,.5);
        return result;
    }
    static int[] boundaryCoordinate(Direction direction,int u,int v,int size) {
        int axis=direction.getAxis()==Direction.Axis.X?0:direction.getAxis()==Direction.Axis.Y?1:2;
        int[] result=new int[3];result[axis]=direction.getAxisDirection()==Direction.AxisDirection.POSITIVE?size-1:0;
        result[(axis+1)%3]=u;result[(axis+2)%3]=v;return result;
    }
    static boolean boundarySolid(java.util.List<net.minecraft.world.phys.AABB> boxes,Direction direction,int u,int v) {
        int[] p=boundaryCoordinate(direction,u,v,16);
        double x=(p[0]+.5)/16,y=(p[1]+.5)/16,z=(p[2]+.5)/16;
        for(var box:boxes)if(box.contains(x,y,z))return true;
        return false;
    }
    /** Fraction of a quarter-block boundary square belonging to external air. */
    static double outsideFaceFraction(BlockState state,int axis,int side,int u,int v) {
        double normal=side==0?1e-9:1-1e-9,covered=0;
        for(var box:PipeGeometry.envelope(state).toAabbs()) {
            double[] low={box.minX,box.minY,box.minZ},high={box.maxX,box.maxY,box.maxZ};
            if(normal<=low[axis] || normal>=high[axis])continue;
            int a=(axis+1)%3,b=(axis+2)%3;
            double da=Math.max(0,Math.min((u+1)*.25,high[a])-Math.max(u*.25,low[a]));
            double db=Math.max(0,Math.min((v+1)*.25,high[b])-Math.max(v*.25,low[b]));
            covered+=da*db;
        }
        return Math.max(0,Math.min(1,1-covered*16));
    }
    private static double outsideVolume(VoxelShape envelope,int x,int y,int z,double size) {
        double minX=x*size, minY=y*size, minZ=z*size, inside=0;
        for (var box : envelope.toAabbs()) {
            double dx=Math.max(0,Math.min(minX+size,box.maxX)-Math.max(minX,box.minX));
            double dy=Math.max(0,Math.min(minY+size,box.maxY)-Math.max(minY,box.minY));
            double dz=Math.max(0,Math.min(minZ+size,box.maxZ)-Math.max(minZ,box.minZ));
            inside+=dx*dy*dz;
        }
        return Math.max(0,size*size*size-inside);
    }
    private static double volume(VoxelShape shape) {
        double result=0;
        for (var box : shape.toAabbs()) result+=(box.maxX-box.minX)*(box.maxY-box.minY)*(box.maxZ-box.minZ);
        return result;
    }
}
