package mmm.simulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mmm.block.FluidPortBlock;
import mmm.block.FluidPortBlockEntity;
import mmm.block.MmmBlocks;
import mmm.block.ProcessSensorBlock;
import mmm.block.ProcessSensorBlockEntity;
import mmm.block.ScreenBlock;
import mmm.block.ValveBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Block-level layouts shared by the game builder and controller process fixtures. */
public record IndustrialLayout(BlockPos minimum, BlockPos controller, Map<BlockPos, BlockState> blocks,
                               List<Port> ports, List<Screen> screens, List<Sensor> sensors,
                               List<Wall> walls, List<Coating> coatings) {
    public enum Scenario { TWO_TANKS, SCREENING, SCREENING_CRYSTALLIZATION, COUNTERFLOW, COUNTERFLOW_STONE, DISTILLATION_SINGLE, DISTILLATION_WARM, DISTILLATION, ABSORPTION, CATALYSIS, CATALYSIS_PARTIAL }
    public record Port(BlockPos position, Direction outward, FluidPortBlockEntity.Mode mode,
                       FluidPortBlockEntity.Phase phase, long rate, boolean redstone) {
        public BlockPos box() { return position.relative(outward); }
    }
    public record Screen(BlockPos position, double poreDiameter, double openFraction) { }
    public record Sensor(BlockPos position, ProcessSensorBlockEntity.Measure measure, double low, double high,
                         boolean below, BlockPos first, BlockPos second) { }
    public record Wall(BlockPos position, double temperatureK) { }
    public record Coating(BlockPos position, Direction face, String materialId, double loadingKg) { }
    public IndustrialLayout {
        blocks=Map.copyOf(blocks);ports=List.copyOf(ports);screens=List.copyOf(screens);sensors=List.copyOf(sensors);
        walls=List.copyOf(walls);coatings=List.copyOf(coatings);
    }
    public static IndustrialLayout at(BlockPos minimum, Scenario scenario) {
        return switch(scenario) {
            case TWO_TANKS -> connectedTanks(minimum);
            case SCREENING, SCREENING_CRYSTALLIZATION -> screening(minimum,scenario==Scenario.SCREENING_CRYSTALLIZATION);
            case COUNTERFLOW, COUNTERFLOW_STONE -> counterflow(minimum,scenario==Scenario.COUNTERFLOW_STONE);
            case DISTILLATION_SINGLE, DISTILLATION_WARM, DISTILLATION ->
                    distillation(minimum,scenario==Scenario.DISTILLATION_WARM);
            case ABSORPTION, CATALYSIS, CATALYSIS_PARTIAL -> surfaceChannel(minimum,scenario==Scenario.ABSORPTION,scenario==Scenario.CATALYSIS_PARTIAL);
        };
    }
    private static IndustrialLayout screening(BlockPos min,boolean crystals) {
        if(crystals)return crystallization(min);
        var blocks=shell(min,7,7,7);var screens=new ArrayList<Screen>();var ports=new ArrayList<Port>();
        for(int x=1;x<6;x++)for(int z=1;z<6;z++) {
            var position=min.offset(x,3,z);
            blocks.put(position,MmmBlocks.SCREEN.defaultBlockState().setValue(ScreenBlock.FACING,Direction.UP));
            screens.add(new Screen(position,.00025,.6));
        }
        addPort(blocks,ports,min.offset(0,4,1),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(6,1,1),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        addPort(blocks,ports,min.offset(1,4,0),Direction.NORTH,FluidPortBlockEntity.Mode.INPUT,FluidPortBlockEntity.Phase.SOLID);
        addPort(blocks,ports,min.offset(1,3,0),Direction.NORTH,FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.SOLID);
        var cold=min.offset(3,4,1);blocks.put(cold,Blocks.IRON_BLOCK.defaultBlockState());
        var controller=min.offset(2,4,0);blocks.put(controller,MmmBlocks.FLUID_CONTROLLER.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,screens,List.of(),List.of(new Wall(cold,280)),List.of());
    }
    private static IndustrialLayout crystallization(BlockPos min) {
        var blocks=shell(min,7,7,3);var ports=new ArrayList<Port>();var walls=new ArrayList<Wall>();
        for(int x=1;x<=5;x++) {
            var floor=min.offset(x,1,1);blocks.put(floor,Blocks.IRON_BLOCK.defaultBlockState());
            if(x<=2)walls.add(new Wall(floor,280));
        }
        var screen=min.offset(2,2,1);
        blocks.put(screen,MmmBlocks.SCREEN.defaultBlockState().setValue(ScreenBlock.FACING,Direction.EAST));
        blocks.put(min.offset(3,2,1),MmmBlocks.VALVE.defaultBlockState().setValue(ValveBlock.FACING,Direction.EAST).setValue(ValveBlock.OPENING,0));
        addPort(blocks,ports,min.offset(1,2,2),Direction.SOUTH,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(6,2,1),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        addPort(blocks,ports,min.offset(1,2,0),Direction.NORTH,FluidPortBlockEntity.Mode.INPUT,FluidPortBlockEntity.Phase.SOLID);
        var controller=min.offset(2,4,0);blocks.put(controller,MmmBlocks.FLUID_CONTROLLER.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,List.of(new Screen(screen,.000005,.6)),List.of(),walls,List.of());
    }
    private static IndustrialLayout connectedTanks(BlockPos min) {
        int sx=14,sy=6,sz=5;var blocks=shell(min,sx,sy,sz);
        // The liquid passage and upper gas passage cross the partition independently.
        for(int x=4;x<=9;x++)for(int y=1;y<5;y++)for(int z=1;z<4;z++)
            if(!(y<=2 && z<=2) && !(y==4 && z==3))blocks.put(min.offset(x,y,z),Blocks.IRON_BLOCK.defaultBlockState());
        var screens=new ArrayList<Screen>();
        for(int y=1;y<=2;y++)for(int z=1;z<=2;z++) {
            var p=min.offset(6,y,z);
            blocks.put(p,MmmBlocks.VALVE.defaultBlockState().setValue(ValveBlock.FACING,Direction.EAST));
        }
        var ports=new ArrayList<Port>();
        addPort(blocks,ports,min.offset(0,3,1),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(13,1,1),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        var sensorPos=min.offset(0,3,0);
        blocks.put(sensorPos,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.SOUTH));
        {var p=ports.getFirst();ports.set(0,new Port(p.position(),p.outward(),p.mode(),p.phase(),p.rate(),true));}
        var sensor=new Sensor(sensorPos,ProcessSensorBlockEntity.Measure.LEVEL,.25,.75,true,min.offset(10,1,1),min.offset(12,3,3));
        var controller=min.offset(1,2,0);blocks.put(controller,MmmBlocks.FLUID_CONTROLLER.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,screens,List.of(sensor),
                List.of(),List.of());
    }
    private static IndustrialLayout counterflow(BlockPos min,boolean stone) {
        var blocks=shell(min,4,5,9);
        for(int x=1;x<=2;x++)for(int y=1;y<4;y++) {
            blocks.put(min.offset(x,y,4),Blocks.WOOL.white().defaultBlockState());
            for(int z:new int[]{1,2,6,7})blocks.put(min.offset(x,y,z),Blocks.WOOL.white().defaultBlockState());
        }
        var walls=new ArrayList<Wall>();
        for(int x=1;x<=2;x++) {
            var wall=min.offset(x,1,4);
            blocks.put(wall,(stone?Blocks.STONE:MmmBlocks.FINITE_HEAT_EXCHANGER).defaultBlockState());
            walls.add(new Wall(wall,320));
            for(int y=1;y<4;y++) {
                blocks.put(min.offset(x,y,3),MmmBlocks.VALVE.defaultBlockState().setValue(ValveBlock.FACING,Direction.DOWN).setValue(ValveBlock.OPENING,1));
                blocks.put(min.offset(x,y,5),MmmBlocks.VALVE.defaultBlockState().setValue(ValveBlock.FACING,Direction.UP).setValue(ValveBlock.OPENING,1));
            }
        }
        var ports=new ArrayList<Port>();
        addPort(blocks,ports,min.offset(0,1,3),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(3,1,3),Direction.EAST,FluidPortBlockEntity.Mode.STOPPED);
        addPort(blocks,ports,min.offset(3,1,5),Direction.EAST,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(0,1,5),Direction.WEST,FluidPortBlockEntity.Mode.STOPPED);
        addPort(blocks,ports,min.offset(1,0,3),Direction.DOWN,FluidPortBlockEntity.Mode.STOPPED);
        for(int z:new int[]{3,5})addPort(blocks,ports,min.offset(1,4,z),Direction.UP,FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.GAS);
        ports.replaceAll(p->new Port(p.position(),p.outward(),p.mode(),p.phase(),1_000_000_000L,p.redstone()));
        var controller=min.offset(1,2,0);blocks.put(controller,MmmBlocks.FLUID_CONTROLLER.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,List.of(),List.of(),walls,List.of());
    }
    long counterflowChargeNanos(Direction side) {
        double volume=0;
        for(var wall:walls) {
            var state=blocks.get(wall.position().relative(side));
            var blocked=ValveBlock.shutter(state).bounds();
            volume+=1-blocked.getXsize()*blocked.getYsize()*blocked.getZsize();
        }
        return Math.round(volume*mmm.transfer.ContainerFluidLedger.NANOS_PER_BLOCK);
    }
    private static IndustrialLayout distillation(BlockPos min,boolean warm) {
        var blocks=shell(min,6,5,4);var ports=new ArrayList<Port>();var thermal=new ArrayList<Wall>();
        addPort(blocks,ports,min.offset(0,1,2),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        var feed=ports.removeLast();
        ports.add(new Port(feed.position(),feed.outward(),feed.mode(),feed.phase(),10_000_000L,false));
        addPort(blocks,ports,min.offset(3,2,1),Direction.NORTH,FluidPortBlockEntity.Mode.OUTPUT);
        var product=ports.removeLast();
        ports.add(new Port(product.position(),product.outward(),product.mode(),product.phase(),500_000L,false));
        addPort(blocks,ports,min.offset(3,3,2),Direction.UP,FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.GAS);
        var vent=ports.removeLast();
        ports.add(new Port(vent.position(),vent.outward(),vent.mode(),vent.phase(),1_000_000_000L,true));

        var open=List.of(min.offset(1,1,2),min.offset(1,2,2),min.offset(2,2,2),min.offset(3,2,2));
        for(int y=1;y<4;y++)for(int x=1;x<5;x++)for(int z=1;z<3;z++) {
            var position=min.offset(x,y,z);
            if(!open.contains(position) && !position.equals(product.position()) && !position.equals(vent.position()))
                blocks.put(position,MmmBlocks.FLUID_BOUNDARY.defaultBlockState());
        }
        for(var passage:open)blocks.put(passage,MmmBlocks.VALVE.defaultBlockState()
                .setValue(ValveBlock.FACING,Direction.UP).setValue(ValveBlock.OPENING,1));

        for(var wall:List.of(min.offset(4,2,2),min.offset(3,1,2))) {
            blocks.put(wall,Blocks.IRON_BLOCK.defaultBlockState());
            thermal.add(new Wall(wall,warm?400:285));
        }
        for(var heater:List.of(min.offset(1,0,2),min.offset(1,1,1),min.offset(2,1,2))) {
            blocks.put(heater,MmmBlocks.THERMAL_RESERVOIR.defaultBlockState());
            thermal.add(new Wall(heater,600));
        }

        var pressureSensor=min.offset(4,3,2);
        blocks.put(pressureSensor,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.WEST));
        var condenser=min.offset(3,2,2);
        var sensors=List.of(
                new Sensor(pressureSensor,ProcessSensorBlockEntity.Measure.PRESSURE,100_000,110_000,false,condenser,condenser));
        var controller=min.offset(1,2,1);
        blocks.put(controller,MmmBlocks.FLUID_CONTROLLER.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,List.of(),sensors,thermal,List.of());
    }
    private static IndustrialLayout surfaceChannel(BlockPos min,boolean adsorption,boolean partial) {
        var blocks=shell(min,10,5,8);var ports=new ArrayList<Port>();
        addPort(blocks,ports,min.offset(0,1,1),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(9,1,1),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        addPort(blocks,ports,min.offset(0,1,5),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(9,1,5),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        if(adsorption)ports.replaceAll(p->new Port(p.position(),p.outward(),p.mode(),FluidPortBlockEntity.Phase.GAS,p.rate(),p.redstone()));
        for(int x=1;x<9;x++)for(int y=1;y<=3;y++)for(int z=3;z<=4;z++)blocks.put(min.offset(x,y,z),Blocks.IRON_BLOCK.defaultBlockState());
        for(int x=1;x<9;x++)for(int z:new int[]{1,2,5,6})blocks.put(min.offset(x,3,z),Blocks.IRON_BLOCK.defaultBlockState());
        for(int x:new int[]{3,6})for(int y=1;y<=2;y++)for(int z=1;z<=6;z++) {
            if(z==3 || z==4 || x==3&&(z==2||z==6) || x==6&&(z==1||z==5))continue;
            blocks.put(min.offset(x,y,z),Blocks.IRON_BLOCK.defaultBlockState());
        }
        var coatings=new ArrayList<Coating>();String material=adsorption?"mmm:co2_adsorbent":"mmm:hydration_catalyst";
        for(int x:new int[]{3,6})for(int y=1;y<=2;y++)for(int z=1;z<=2;z++)
            if((!partial || x==3) && blocks.containsKey(min.offset(x,y,z)))for(var face:List.of(Direction.WEST,Direction.EAST))
                coatings.add(new Coating(min.offset(x,y,z),face,material,adsorption?.05:1));
        var controller=min.offset(2,1,0);blocks.put(controller,MmmBlocks.FLUID_CONTROLLER.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,List.of(),List.of(),List.of(),coatings);
    }
    private static LinkedHashMap<BlockPos,BlockState> shell(BlockPos min,int sx,int sy,int sz) {
        var blocks=new LinkedHashMap<BlockPos,BlockState>();
        for(int x=0;x<sx;x++)for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)
            if(x==0||x==sx-1||y==0||y==sy-1||z==0||z==sz-1)
                blocks.put(min.offset(x,y,z),MmmBlocks.FLUID_BOUNDARY.defaultBlockState());
        return blocks;
    }
    private static void addPort(Map<BlockPos,BlockState> blocks,List<Port> ports,BlockPos position,Direction outward,FluidPortBlockEntity.Mode mode) {
        addPort(blocks,ports,position,outward,mode,FluidPortBlockEntity.Phase.LIQUID);
    }
    private static void addPort(Map<BlockPos,BlockState> blocks,List<Port> ports,BlockPos position,Direction outward,FluidPortBlockEntity.Mode mode,FluidPortBlockEntity.Phase phase) {
        blocks.put(position,MmmBlocks.FLUID_PORT.defaultBlockState().setValue(FluidPortBlock.FACING,outward));
        blocks.put(position.relative(outward),MmmBlocks.MATERIAL_BOX.defaultBlockState());
        ports.add(new Port(position,outward,mode,phase,50_000_000L,false));
    }
}
