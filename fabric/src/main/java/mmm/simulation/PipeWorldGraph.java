package mmm.simulation;

import java.util.*;
import mmm.block.*;
import mmm.pipe.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Minecraft geometry and port semantics at the independent pipe solver boundary. */
final class PipeWorldGraph {
    private PipeWorldGraph() { }

    static PipeInstallation installation(BlockState state) {
        if(!PipeThermalGeometry.isPipe(state))throw new IllegalArgumentException("Not a pipe");
        var properties=new TreeMap<String,String>();
        for(var property:state.getProperties())properties.put(property.getName(),value(state,property));
        return new PipeInstallation(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),properties);
    }
    private static <T extends Comparable<T>> String value(BlockState state,Property<T> property) {
        return property.getName(state.getValue(property));
    }
    static BlockState state(PipeInstallation installation) {
        var id=Identifier.parse(installation.blockId());
        if(!BuiltInRegistries.BLOCK.containsKey(id))throw new IllegalArgumentException("Unknown pipe block "+id);
        var state=BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        if(!PipeThermalGeometry.isPipe(state))throw new IllegalArgumentException("Stored block is not a pipe");
        for(var entry:installation.properties().entrySet()) {
            var property=state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if(property==null)throw new IllegalArgumentException("Unknown pipe property "+entry.getKey());
            state=property(state,property,entry.getValue());
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState property(BlockState state,Property<T> property,String value) {
        return state.setValue(property,property.getValue(value).orElseThrow(()->new IllegalArgumentException("Invalid pipe property "+value)));
    }
    static boolean opens(BlockState state,Direction direction) {
        if(state.getBlock() instanceof PipeBlock)return state.getValue(PipeBlock.port(direction)).open();
        return state.getValue(ScreenBlock.FACING).getAxis()==direction.getAxis();
    }
    static double opening(BlockState state) {
        return state.getBlock() instanceof PipeValveBlock?state.getValue(PipeValveBlock.OPENING)*.5:1;
    }
    static double capacity(BlockState state) { return volume(PipeGeometry.bore(state)); }
    static double volume(VoxelShape shape) {
        double result=0;for(var box:shape.toAabbs())result+=(box.maxX-box.minX)*(box.maxY-box.minY)*(box.maxZ-box.minZ);
        return result;
    }
    static Map<BlockPos,VoxelShape> bores(PipeDimensionState state) {
        var result=new HashMap<BlockPos,VoxelShape>();
        for(var entry:state.installations().entrySet())result.put(BlockPos.of(Long.parseLong(entry.getKey())),PipeGeometry.bore(state(entry.getValue())));
        return Map.copyOf(result);
    }
    static List<PipeConnection> connections(List<PipeSegmentState> segments,Map<String,PipeInstallation> installations,
                                           java.util.function.Predicate<BlockPos> loaded,
                                           java.util.function.ToDoubleFunction<BlockPos> sieve,
                                           java.util.function.ToDoubleFunction<BlockPos> openFraction) {
        var indices=new HashMap<Long,Integer>();
        for(int i=0;i<segments.size();i++)indices.put(Long.parseLong(segments.get(i).id()),i);
        var edges=new ArrayList<PipeConnection>();
        for(int i=0;i<segments.size();i++) {
            var segment=segments.get(i);var pos=BlockPos.of(Long.parseLong(segment.id()));
            if(!loaded.test(pos))continue;
            var first=state(installations.get(segment.id()));
            for(var face:Direction.values()) {
                var neighbor=pos.relative(face);Integer j=indices.get(neighbor.asLong());
                if(j==null || j<=i || !loaded.test(neighbor))continue;
                var second=state(installations.get(segments.get(j).id()));
                if(!opens(first,face)||!opens(second,face.getOpposite()))continue;
                double a=sieve.applyAsDouble(pos),b=sieve.applyAsDouble(neighbor);
                double aperture=Math.min(opening(first)*openFraction.applyAsDouble(pos),opening(second)*openFraction.applyAsDouble(neighbor));
                edges.add(new PipeConnection(i,j,1,.25,face.getStepY(),aperture,
                        a==0?b:b==0?a:Math.min(a,b)));
            }
        }
        return List.copyOf(edges);
    }
}
