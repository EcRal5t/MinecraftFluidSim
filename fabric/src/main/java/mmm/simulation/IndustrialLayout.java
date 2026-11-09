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
    public enum Scenario { TWO_TANKS, SCREENING, SCREENING_CRYSTALLIZATION, COUNTERFLOW, COUNTERFLOW_STONE, DISTILLATION_SINGLE, DISTILLATION_WARM, DISTILLATION, DISTILLATION_BYPASS, ABSORPTION, CATALYSIS, CATALYSIS_PARTIAL }
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
            case DISTILLATION_SINGLE, DISTILLATION_WARM, DISTILLATION, DISTILLATION_BYPASS -> distillation(minimum,
                    scenario==Scenario.DISTILLATION || scenario==Scenario.DISTILLATION_BYPASS,
                    scenario==Scenario.DISTILLATION_WARM,scenario==Scenario.DISTILLATION_BYPASS);
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
        int sx=14,sy=5,sz=5;var blocks=shell(min,sx,sy,sz);
        // The central passage retains a 2 by 2 metre opening between the two tanks.
        for(int x=4;x<=9;x++)for(int y=1;y<4;y++)for(int z=1;z<4;z++)
            if(y==3||z==3)blocks.put(min.offset(x,y,z),Blocks.IRON_BLOCK.defaultBlockState());
        var screens=new ArrayList<Screen>();
        for(int y=1;y<=2;y++)for(int z=1;z<=2;z++) {
            var p=min.offset(6,y,z);
            blocks.put(p,MmmBlocks.VALVE.defaultBlockState().setValue(ValveBlock.FACING,Direction.EAST));
        }
        var ports=new ArrayList<Port>();
        addPort(blocks,ports,min.offset(0,1,1),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(13,1,1),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        var sensorPos=min.offset(0,1,0);
        blocks.put(sensorPos,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.SOUTH));
        {var p=ports.getFirst();ports.set(0,new Port(p.position(),p.outward(),p.mode(),p.phase(),p.rate(),true));}
        var sensor=new Sensor(sensorPos,ProcessSensorBlockEntity.Measure.LEVEL,.25,.75,true,min.offset(10,1,1),min.offset(12,3,3));
        var controller=min.offset(1,2,0);blocks.put(controller,MmmBlocks.FLUID_CONTROLLER.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,screens,List.of(sensor),
                List.of(),List.of());
    }
    private static IndustrialLayout counterflow(BlockPos min,boolean stone) {
        var blocks=shell(min,10,5,9);
        for(int x=1;x<9;x++)for(int y=1;y<4;y++) {
            blocks.put(min.offset(x,y,4),(stone?Blocks.STONE:Blocks.IRON_BLOCK).defaultBlockState());
            for(int z:new int[]{2,6})blocks.put(min.offset(x,y,z),Blocks.STONE.defaultBlockState());
        }
        var ports=new ArrayList<Port>();
        addPort(blocks,ports,min.offset(0,1,3),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(9,2,3),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        addPort(blocks,ports,min.offset(9,1,5),Direction.EAST,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(0,2,5),Direction.WEST,FluidPortBlockEntity.Mode.OUTPUT);
        for(int z:new int[]{3,5})
            addPort(blocks,ports,min.offset(4,4,z),Direction.UP,FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.GAS);
        ports.replaceAll(p->new Port(p.position(),p.outward(),p.mode(),p.phase(),1_000_000_000L,p.redstone()));
        var thermal=new ArrayList<Wall>();
        for(int x=1;x<9;x++)for(int y=1;y<=2;y++)thermal.add(new Wall(min.offset(x,y,4),320));
        var controller=min.offset(2,1,0);blocks.put(controller,MmmBlocks.FLUID_CONTROLLER.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,List.of(),List.of(),thermal,List.of());
    }
    private static IndustrialLayout distillation(BlockPos min,boolean trays,boolean warm,boolean bypass) {
        var blocks=shell(min,8,14,8);var ports=new ArrayList<Port>();var thermal=new ArrayList<Wall>();
        addPort(blocks,ports,min.offset(0,2,2),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        var feedPort=ports.removeLast();ports.add(new Port(feedPort.position(),feedPort.outward(),feedPort.mode(),feedPort.phase(),1_000_000_000L,true));
        addPort(blocks,ports,min.offset(7,11,5),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        var productPort=ports.removeLast();ports.add(new Port(productPort.position(),productPort.outward(),productPort.mode(),productPort.phase(),productPort.rate(),true));
        addPort(blocks,ports,min.offset(7,1,2),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        for(int x=2;x<=3;x++)for(int z=2;z<=3;z++) {
            var vent=min.offset(x,13,z);
            addPort(blocks,ports,vent,Direction.UP,FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.GAS);
            var port=ports.removeLast();ports.add(new Port(port.position(),port.outward(),port.mode(),port.phase(),1_000_000_000L,false));
        }
        // The boiler and condenser retain only the passages needed by their real ports.
        for(int y=1;y<=2;y++)for(int x=1;x<7;x++)for(int z=1;z<7;z++)
            if(z<2 || z>(trays?5:3))blocks.put(min.offset(x,y,z),Blocks.IRON_BLOCK.defaultBlockState());
        for(int y=11;y<=12;y++)for(int x=1;x<7;x++)for(int z=1;z<7;z++)
            if(x<2 || z<2 || z>5)blocks.put(min.offset(x,y,z),Blocks.IRON_BLOCK.defaultBlockState());
        // The shaft keeps separate gas chimneys and liquid downcomers through each tray.
        for(int y=3;y<=9;y++)for(int x=1;x<7;x++)for(int z=1;z<7;z++)
            if(x<2 || x>(trays?5:3) || z<2 || z>(trays?5:3))
                blocks.put(min.offset(x,y,z),Blocks.IRON_BLOCK.defaultBlockState());
        if(trays) {
            int[] levels={3,6,8};
            for(int layer=0;layer<levels.length;layer++) {
                int y=levels[layer],gasX=layer%2==0?2:5,downX=layer%2==0?5:2;
                int gasInside=gasX==2?3:4,downInside=downX==2?3:4;
                for(int x=2;x<=5;x++)for(int z=2;z<=5;z++)
                    if(!(x==gasX && z==2) && !(x==downX && z==5))
                        blocks.put(min.offset(x,y,z),Blocks.IRON_BLOCK.defaultBlockState());
                blocks.put(min.offset(gasInside,y+1,2),Blocks.IRON_BLOCK.defaultBlockState());
                blocks.put(min.offset(gasX,y+1,3),Blocks.IRON_BLOCK.defaultBlockState());
                // The downcomer opens sideways into the liquid pool below the tray.
                blocks.put(min.offset(downX,y-1,4),Blocks.IRON_BLOCK.defaultBlockState());
                if(y==3 || y==6)blocks.put(min.offset(downInside,y-1,5),Blocks.IRON_BLOCK.defaultBlockState());
                if(y==3)blocks.put(min.offset(downX,y-2,4),Blocks.IRON_BLOCK.defaultBlockState());
            }
            for(int y:new int[]{4,7,9}) {
                blocks.remove(min.offset(1,y,4));
                addPort(blocks,ports,min.offset(0,y,4),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
                var port=ports.removeLast();ports.add(new Port(port.position(),port.outward(),port.mode(),port.phase(),1_000_000_000L,false));
            }
            blocks.put(min.offset(1,10,4),Blocks.IRON_BLOCK.defaultBlockState());
        }
        // The condenser has separate passages for rising gas and controlled reflux.
        for(int x=2;x<7;x++)for(int z=2;z<7;z++)
            if(!(x==2 && z==2) && !(trays && x==4 && z==5))
                blocks.put(min.offset(x,10,z),Blocks.IRON_BLOCK.defaultBlockState());
        if(trays)blocks.put(min.offset(4,10,5),MmmBlocks.VALVE.defaultBlockState()
                .setValue(ValveBlock.FACING,Direction.UP).setValue(ValveBlock.OPENING,bypass?0:4));
        for(int x=2;x<=4;x++)for(int z=1;z<=3;z++) {
            var hot=min.offset(x,0,z);blocks.put(hot,MmmBlocks.THERMAL_RESERVOIR.defaultBlockState());thermal.add(new Wall(hot,293.15));
        }
        var cold=min.offset(6,12,5);blocks.put(cold,Blocks.IRON_BLOCK.defaultBlockState());thermal.add(0,new Wall(cold,warm?400:285));
        var controller=min.offset(2,1,0);blocks.put(controller,MmmBlocks.FLUID_CONTROLLER.defaultBlockState());
        var levelSensor=min.offset(0,2,1);blocks.put(levelSensor,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.SOUTH));
        var temperatureSensor=min.offset(7,11,4);blocks.put(temperatureSensor,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.SOUTH));
        var sensors=List.of(new Sensor(levelSensor,ProcessSensorBlockEntity.Measure.LEVEL,.25,.75,true,min.offset(1,1,1),min.offset(6,2,6)),
                new Sensor(temperatureSensor,ProcessSensorBlockEntity.Measure.TEMPERATURE,300,350,true,min.offset(5,11,4),min.offset(6,12,6)));
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
