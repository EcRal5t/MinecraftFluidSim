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
    public record Sensor(BlockPos position, mmm.api.sensor.ProcessSensorSnapshot.Measure measure, double low, double high,
                         boolean below, BlockPos first, BlockPos second) { }
    public record Wall(BlockPos position, double temperatureK) { }
    public record Coating(BlockPos position, Direction face, String materialId, double loadingKg) { }
    public record Tray(BlockPos gasOpening, BlockPos downpipe, BlockPos seal, BlockPos pool, BlockPos inlet, Direction inletOutward) { }
    private static List<Tray> trays(BlockPos min) {
        var result=new ArrayList<Tray>();
        for(int stage=0;stage<3;stage++) {
            int floor=3+3*stage,gasX=stage%2==0?2:5,downX=stage%2==0?5:2;
            var outward=stage==0?Direction.EAST:Direction.WEST;
            var inlet=stage==0?min.offset(7,floor+2,3):min.offset(0,floor+2,stage==1?3:4);
            result.add(new Tray(min.offset(gasX,floor,2),min.offset(downX,floor,5),
                    min.offset(downX,stage==0?1:floor-2,5),min.offset(3,floor+1,4),inlet,outward));
        }
        return List.copyOf(result);
    }
    public List<Tray> distillationTrays() {
        var valve=blocks.get(minimum.offset(3,11,4));
        return valve!=null && valve.is(MmmBlocks.VALVE)?trays(minimum):List.of();
    }
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
        var sensor=new Sensor(sensorPos,mmm.api.sensor.ProcessSensorSnapshot.Measure.LEVEL,.25,.75,true,min.offset(10,1,1),min.offset(12,3,3));
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
        addPort(blocks,ports,min.offset(0,3,3),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(9,2,3),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        addPort(blocks,ports,min.offset(9,3,5),Direction.EAST,FluidPortBlockEntity.Mode.INPUT);
        addPort(blocks,ports,min.offset(0,2,5),Direction.WEST,FluidPortBlockEntity.Mode.OUTPUT);
        addPort(blocks,ports,min.offset(0,1,3),Direction.WEST,FluidPortBlockEntity.Mode.OUTPUT);
        for(int z:new int[]{3,5})
            addPort(blocks,ports,min.offset(4,4,z),Direction.UP,FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.GAS);
        ports.replaceAll(p->new Port(p.position(),p.outward(),p.mode(),p.phase(),1_000_000_000L,p.redstone()));
        var drain=ports.get(4);ports.set(4,new Port(drain.position(),drain.outward(),FluidPortBlockEntity.Mode.STOPPED,drain.phase(),drain.rate(),drain.redstone()));
        var thermal=new ArrayList<Wall>();
        for(int x=1;x<9;x++)for(int y=1;y<=2;y++)thermal.add(new Wall(min.offset(x,y,4),320));
        var controller=min.offset(2,1,0);blocks.put(controller,MmmBlocks.FLUID_CONTROLLER.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,List.of(),List.of(),thermal,List.of());
    }
    private static IndustrialLayout distillation(BlockPos min,boolean trays,boolean warm,boolean bypass) {
        int collectorY=trays?13:8,weirY=collectorY+3,roofY=weirY+1,ventY=roofY+1;
        var blocks=shell(min,8,ventY+1,7);var ports=new ArrayList<Port>();var thermal=new ArrayList<Wall>();
        addPort(blocks,ports,min.offset(0,1,2),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        var feedPort=ports.removeLast();ports.add(new Port(feedPort.position(),feedPort.outward(),feedPort.mode(),feedPort.phase(),trays?1_000_000_000L:feedPort.rate(),true));
        addPort(blocks,ports,min.offset(6,collectorY-1,4),Direction.DOWN,FluidPortBlockEntity.Mode.OUTPUT);
        var productPort=ports.removeLast();ports.add(new Port(productPort.position(),productPort.outward(),productPort.mode(),productPort.phase(),productPort.rate(),true));
        addPort(blocks,ports,min.offset(7,1,2),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        if(trays) {
            var bottomPort=ports.removeLast();ports.add(new Port(bottomPort.position(),bottomPort.outward(),bottomPort.mode(),bottomPort.phase(),bottomPort.rate(),true));
        }
        var open=new java.util.HashSet<BlockPos>();var pipeWalls=new java.util.HashSet<BlockPos>();var sealOutlets=new java.util.HashSet<BlockPos>();
        if(trays) {
            for(int x=1;x<=6;x++)open.add(min.offset(x,1,2));
            open.add(min.offset(2,2,2));
            for(int y=1;y<=2;y++) {
                for(int z=2;z<=5;z++)open.add(min.offset(6,y,z));
                open.add(min.offset(5,y,5));
            }
            for(var tray:trays(min)) {
                int floor=tray.gasOpening().getY()-min.getY(),downX=tray.downpipe().getX()-min.getX(),gasX=tray.gasOpening().getX()-min.getX();
                open.add(tray.gasOpening());open.add(tray.downpipe());
                for(int x=2;x<=5;x++)for(int z=2;z<=5;z++)open.add(min.offset(x,floor+1,z));
                // The downpipe is isolated at pool level and opens over the weir one block above.
                for(int y:new int[]{floor-1,floor+1}) {
                    var pipe=min.offset(downX,y,5);
                    for(var direction:Direction.Plane.HORIZONTAL)pipeWalls.add(pipe.relative(direction));
                }
                for(var direction:Direction.Plane.HORIZONTAL)pipeWalls.add(tray.seal().relative(direction));
                sealOutlets.add(tray.seal().relative(floor==3||downX==2?Direction.EAST:Direction.WEST));
                // The vapor riser opens above the pool into the headspace.
                pipeWalls.add(min.offset(gasX==2?3:4,floor+1,2));
                pipeWalls.add(min.offset(gasX,floor+1,3));
                for(int x=2;x<=5;x++)for(int z=2;z<=5;z++)open.add(min.offset(x,floor+2,z));
                open.add(tray.inlet().relative(tray.inletOutward().getOpposite()));
                addPort(blocks,ports,tray.inlet(),tray.inletOutward(),FluidPortBlockEntity.Mode.INPUT);
                var port=ports.removeLast();ports.add(new Port(port.position(),port.outward(),port.mode(),port.phase(),1_000_000_000L,false));
            }
        } else {
            for(int x=1;x<=6;x++)for(int z=1;z<=5;z++) {
                open.add(min.offset(x,1,z));
                if(x>3 || z>3)open.add(min.offset(x,2,z));
            }
            for(int z=2;z<=5;z++)open.add(min.offset(2,3,z));
            for(int x=3;x<=5;x++)open.add(min.offset(x,3,5));
        }
        addPort(blocks,ports,min.offset(7,collectorY,4),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.GAS);
        var productVent=ports.removeLast();ports.add(new Port(productVent.position(),productVent.outward(),productVent.mode(),productVent.phase(),1_000_000_000L,true));
        if(trays) {
            addPort(blocks,ports,min.offset(3,12,5),Direction.SOUTH,FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.GAS);
            var refluxVent=ports.removeLast();ports.add(new Port(refluxVent.position(),refluxVent.outward(),refluxVent.mode(),refluxVent.phase(),1_000_000_000L,true));
        }
        for(int y=trays?12:4;y<weirY;y++)open.add(min.offset(2,y,2));
        // Vapor crosses the raised passage while carried liquid returns to the riser.
        for(int x:new int[]{2,3,5,6})open.add(min.offset(x,weirY,2));
        for(int x=2;x<=5;x++)open.add(min.offset(x,roofY,2));
        for(int z=3;z<=4;z++)open.add(min.offset(6,weirY,z));
        for(int y=collectorY;y<=weirY;y++)open.add(min.offset(6,y,4));
        if(trays) {
            for(int y=12;y<=weirY;y++)open.add(min.offset(3,y,4));
            for(int x=3;x<=6;x++)open.add(min.offset(x,weirY,4));
        }
        for(int x=2;x<=3;x++)open.add(min.offset(x,roofY,3));
        open.removeAll(pipeWalls);
        var reflux=min.offset(3,11,4);
        for(int y=1;y<ventY;y++)for(int x=1;x<7;x++)for(int z=1;z<6;z++) {
            var position=min.offset(x,y,z);
            if(trays && position.equals(reflux))blocks.put(position,MmmBlocks.VALVE.defaultBlockState()
                    .setValue(ValveBlock.FACING,Direction.UP).setValue(ValveBlock.OPENING,bypass?0:4));
            else if(!open.contains(position))blocks.put(position,Blocks.IRON_BLOCK.defaultBlockState());
        }
        for(var outlet:sealOutlets)blocks.put(outlet,Blocks.STONE_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE,net.minecraft.world.level.block.state.properties.SlabType.TOP));
        for(var port:ports)if(port.phase()==FluidPortBlockEntity.Phase.GAS) {
            blocks.put(port.position(),MmmBlocks.FLUID_PORT.defaultBlockState().setValue(FluidPortBlock.FACING,port.outward()));
            blocks.put(port.box(),MmmBlocks.MATERIAL_BOX.defaultBlockState());
            open.remove(port.position());open.remove(port.box());
        }
        var product=ports.get(1);var box=product.box();
        blocks.put(product.position(),MmmBlocks.FLUID_PORT.defaultBlockState().setValue(FluidPortBlock.FACING,Direction.DOWN));
        blocks.put(box,MmmBlocks.MATERIAL_BOX.defaultBlockState());
        open.remove(product.position());open.remove(box);
        for(var direction:List.of(Direction.WEST,Direction.NORTH,Direction.SOUTH,Direction.DOWN)) {
            var wall=box.relative(direction);blocks.put(wall,MmmBlocks.FLUID_BOUNDARY.defaultBlockState());open.remove(wall);
        }
        blocks.remove(box.relative(Direction.EAST));
        var controller=min.offset(2,1,0);blocks.put(controller,MmmBlocks.FLUID_CONTROLLER.defaultBlockState());
        var levelSensor=min.offset(0,1,1);blocks.put(levelSensor,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.SOUTH));
        var temperatureSensor=min.offset(5,collectorY-1,4);blocks.put(temperatureSensor,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.SOUTH));
        open.remove(temperatureSensor);
        var sensors=new ArrayList<Sensor>();
        var productPressure=min.offset(7,collectorY,3);blocks.put(productPressure,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.SOUTH));
        sensors.add(new Sensor(productPressure,mmm.api.sensor.ProcessSensorSnapshot.Measure.PRESSURE,100_000,110_000,false,min.offset(6,collectorY,4),min.offset(6,collectorY,4)));
        if(trays) {
            var refluxPressure=min.offset(3,13,5);blocks.put(refluxPressure,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.SOUTH));
            open.remove(refluxPressure);
            sensors.add(new Sensor(refluxPressure,mmm.api.sensor.ProcessSensorSnapshot.Measure.PRESSURE,100_000,110_000,false,min.offset(3,12,4),min.offset(3,12,4)));
        }
        sensors.add(new Sensor(levelSensor,mmm.api.sensor.ProcessSensorSnapshot.Measure.LEVEL,.25,.75,true,min.offset(1,1,1),min.offset(6,2,5)));
        sensors.add(new Sensor(temperatureSensor,mmm.api.sensor.ProcessSensorSnapshot.Measure.LIQUID_TEMPERATURE,350,375,true,min.offset(6,collectorY,4),min.offset(6,collectorY,4)));
        if(trays) {
            var bottomSensor=min.offset(7,1,1);blocks.put(bottomSensor,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.WEST));
            var seal=trays(min).getFirst().seal();
            sensors.add(new Sensor(bottomSensor,mmm.api.sensor.ProcessSensorSnapshot.Measure.LEVEL,.65,.85,false,seal,seal));
        }
        for(int x=2;x<=4;x++)for(int z=1;z<=3;z++) {
            var hot=min.offset(x,0,z);blocks.put(hot,MmmBlocks.THERMAL_RESERVOIR.defaultBlockState());thermal.add(new Wall(hot,293.15));
        }
        var condenserWalls=new java.util.LinkedHashSet<BlockPos>();
        var collectors=new java.util.LinkedHashSet<BlockPos>();
        for(int y=collectorY;y<=weirY;y++)collectors.add(min.offset(6,y,4));
        if(trays) {
            for(int y=12;y<=weirY;y++)collectors.add(min.offset(3,y,4));
            for(int x=3;x<=6;x++)collectors.add(min.offset(x,weirY,4));
        }
        for(var collector:collectors) {
            for(var direction:Direction.values()) {
                var adjacent=collector.relative(direction);var state=blocks.get(adjacent);
                if(state!=null && state.is(Blocks.IRON_BLOCK))condenserWalls.add(adjacent);
            }
        }
        var heatedWalls=new java.util.LinkedHashSet<BlockPos>();
        for(var passage:open)for(var direction:Direction.values()) {
            var adjacent=passage.relative(direction);var state=blocks.get(adjacent);
            if(state!=null && state.is(Blocks.IRON_BLOCK))heatedWalls.add(adjacent);
        }
        for(var wall:heatedWalls)thermal.add(new Wall(wall,condenserWalls.contains(wall)?(warm?400:285):380));
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
