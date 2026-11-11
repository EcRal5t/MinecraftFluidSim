package mmm.simulation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mmm.block.FluidPortBlock;
import mmm.block.FluidPortBlockEntity;
import mmm.block.MmmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block-level layouts shared by the game builder and controller process fixtures.
 * Each apparatus builds its blocks in its own class; this record holds the result and the shared shell, port and wall placement.
 */
public record IndustrialLayout(BlockPos minimum, BlockPos controller, Map<BlockPos, BlockState> blocks,
                               List<Port> ports, List<Screen> screens, List<Sensor> sensors,
                               List<Wall> walls, List<Coating> coatings) {
    public enum Scenario { TWO_TANKS, SCREENING, SCREENING_CRYSTALLIZATION, COUNTERFLOW, COUNTERFLOW_STONE, DISTILLATION_SINGLE, DISTILLATION_WARM, DISTILLATION, ABSORPTION, CATALYSIS, CATALYSIS_PARTIAL }
    public record Port(BlockPos position, Direction outward, FluidPortBlockEntity.Mode mode,
                       FluidPortBlockEntity.Phase phase, long rate, boolean redstone) {
        public BlockPos box() { return position.relative(outward); }
        Port withRate(long value) { return new Port(position,outward,mode,phase,value,redstone); }
        Port withPhase(FluidPortBlockEntity.Phase value) { return new Port(position,outward,mode,value,rate,redstone); }
        Port withRedstone(boolean value) { return new Port(position,outward,mode,phase,rate,value); }
    }
    public record Screen(BlockPos position, double poreDiameter, double openFraction) { }
    public record Sensor(BlockPos position, mmm.api.sensor.ProcessSensorSnapshot.Measure measure, double low, double high,
                         boolean below, BlockPos first, BlockPos second) { }
    public record Wall(BlockPos position, double temperatureK) { }
    public record Coating(BlockPos position, Direction face, String materialId, double loadingKg) { }
    public IndustrialLayout {
        blocks=Map.copyOf(blocks);ports=List.copyOf(ports);screens=List.copyOf(screens);sensors=List.copyOf(sensors);
        walls=List.copyOf(walls);coatings=List.copyOf(coatings);
    }
    public static IndustrialLayout at(BlockPos minimum, Scenario scenario) {
        return switch(scenario) {
            case TWO_TANKS -> ConnectedTanksLayout.build(minimum);
            case SCREENING -> ScreeningLayout.screening(minimum);
            case SCREENING_CRYSTALLIZATION -> ScreeningLayout.crystallization(minimum);
            case COUNTERFLOW, COUNTERFLOW_STONE -> CounterflowLayout.build(minimum,scenario==Scenario.COUNTERFLOW_STONE);
            case DISTILLATION_SINGLE, DISTILLATION_WARM, DISTILLATION ->
                    DistillationLayout.build(minimum,scenario==Scenario.DISTILLATION_WARM);
            case ABSORPTION, CATALYSIS, CATALYSIS_PARTIAL ->
                    SurfaceChannelLayout.build(minimum,scenario==Scenario.ABSORPTION,scenario==Scenario.CATALYSIS_PARTIAL);
        };
    }

    static LinkedHashMap<BlockPos,BlockState> shell(BlockPos min,int sx,int sy,int sz) {
        return shell(min,sx,sy,sz,Blocks.GLASS.defaultBlockState());
    }
    static LinkedHashMap<BlockPos,BlockState> shell(BlockPos min,int sx,int sy,int sz,BlockState wall) {
        var blocks=new LinkedHashMap<BlockPos,BlockState>();
        for(int x=0;x<sx;x++)for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)
            if(x==0||x==sx-1||y==0||y==sy-1||z==0||z==sz-1)
                blocks.put(min.offset(x,y,z),wall);
        return blocks;
    }
    static Port addPort(Map<BlockPos,BlockState> blocks,List<Port> ports,BlockPos position,Direction outward,FluidPortBlockEntity.Mode mode) {
        return addPort(blocks,ports,position,outward,mode,FluidPortBlockEntity.Phase.LIQUID);
    }
    /** Places the port in the shell and its material box outside, at the default 50 L/s quota. */
    static Port addPort(Map<BlockPos,BlockState> blocks,List<Port> ports,BlockPos position,Direction outward,FluidPortBlockEntity.Mode mode,FluidPortBlockEntity.Phase phase) {
        blocks.put(position,MmmBlocks.FLUID_PORT.defaultBlockState().setValue(FluidPortBlock.FACING,outward));
        blocks.put(position.relative(outward),MmmBlocks.MATERIAL_BOX.defaultBlockState());
        var port=new Port(position,outward,mode,phase,50_000_000L,false);
        ports.add(port);
        return port;
    }
    /** Places a finite thermal wall block and records its initial temperature. */
    static void addWall(Map<BlockPos,BlockState> blocks,List<Wall> walls,BlockPos position,BlockState state,double temperatureK) {
        blocks.put(position,state);
        walls.add(new Wall(position,temperatureK));
    }
}
