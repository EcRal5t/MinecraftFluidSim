package mmm.simulation;

import java.util.*;
import mmm.block.PipeGeometry;
import mmm.pipe.PipeDimensionState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Projects the actual quarter-square-metre mouth onto adjacent region control volumes. */
final class PipeEndpointResolver {
    record Endpoint(int pipeSegment,FluidRegion region,int cell,double areaM2,double lengthM,
                    double riseM,double opening,Direction direction) { }
    private PipeEndpointResolver() { }
    static List<Endpoint> resolve(ServerLevel level,PipeDimensionState pipes,FluidWorldTargetResolver targets,
                                  java.util.function.Predicate<FluidRegion> available) {
        var result=new ArrayList<Endpoint>();
        for(int index=0;index<pipes.network().segments().size();index++) {
            var segment=pipes.network().segments().get(index);var pos=BlockPos.of(Long.parseLong(segment.id()));
            if(!level.hasChunkAt(pos))continue;
            var installation=pipes.installations().get(segment.id());if(installation==null)continue;
            var state=PipeWorldGraph.state(installation);
            if(level.getBlockState(pos)!=state)continue;
            for(var face:Direction.values()) {
                if(!PipeWorldGraph.opens(state,face))continue;
                var next=pos.relative(face);if(!level.hasChunkAt(next)||PipeThermalGeometry.isPipe(level.getBlockState(next)))continue;
                var target=targets.transferTargetAtBlock(level,next,face);
                if(target==null || !available.test(target.region()))continue;
                var region=target.region();double h=region.solverConfig().cellSize();var grid=region.grid();
                var areas=faceAreas(region,target.interiorBlock(),face);
                for(var area:areas.entrySet()) {
                    double elevation=region.origin().getY()+(grid.y(area.getKey())+.5)*h;
                    double length=.5+.5*h+Math.abs(target.interiorBlock().getX()-next.getX())
                            +Math.abs(target.interiorBlock().getY()-next.getY())+Math.abs(target.interiorBlock().getZ()-next.getZ());
                    result.add(new Endpoint(index,region,area.getKey(),area.getValue(),length,elevation-segment.elevationM(),PipeWorldGraph.opening(state),face));
                }
            }
        }
        return List.copyOf(result);
    }
    /** Projects the actual open mouth onto available fluid microfaces. */
    static Map<Integer,Double> faceAreas(FluidRegion region,BlockPos interiorBlock,Direction face) {
                double h=region.solverConfig().cellSize();var grid=region.grid();
                int axis=face.getAxis()==Direction.Axis.X?0:face.getAxis()==Direction.Axis.Y?1:2;
                var areas=new TreeMap<Integer,Double>();
                // The model's mouth is exactly the 8 by 8 central pixels of this face.
                for(int a=4;a<12;a++)for(int b=4;b<12;b++) {
                    double[] coordinate={interiorBlock.getX(),interiorBlock.getY(),interiorBlock.getZ()};
                    coordinate[axis]+=face.getAxisDirection()==Direction.AxisDirection.POSITIVE?1e-8:1-1e-8;
                    coordinate[(axis+1)%3]+=(a+.5)/16.;coordinate[(axis+2)%3]+=(b+.5)/16.;
                    int x=(int)Math.floor((coordinate[0]-region.origin().getX())/h);
                    int y=(int)Math.floor((coordinate[1]-region.origin().getY())/h);
                    int z=(int)Math.floor((coordinate[2]-region.origin().getZ())/h);int cell=grid.cell(x,y,z);
                    if(cell<0 || !region.groupAvailable(cell))continue;
                    int mx=(int)Math.floor((coordinate[0]-region.origin().getX())/h*2)&1;
                    int my=(int)Math.floor((coordinate[1]-region.origin().getY())/h*2)&1;
                    int mz=(int)Math.floor((coordinate[2]-region.origin().getZ())/h*2)&1;
                    if((region.fixedGeometry().mask(cell)&(1<<(mx+2*my+4*mz)))!=0)continue;
                    areas.merge(cell,1/256.,Double::sum);
                }
                return Collections.unmodifiableMap(areas);
    }
}
