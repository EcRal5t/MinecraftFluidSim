package mmm.simulation;

import java.util.ArrayList;
import java.util.List;
import mmm.block.FluidPortBlockEntity;
import mmm.block.MmmBlocks;
import mmm.block.ProcessSensorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/** Compact batch still: heated pot, short vapour passage and an iron condenser inside an insulated shell. */
final class DistillationLayout {
    private DistillationLayout() { }

    static IndustrialLayout build(BlockPos min,boolean warm) {
        // An insulated shell keeps vapour from condensing anywhere except the cooled condenser walls.
        var blocks=IndustrialLayout.shell(min,6,5,4,MmmBlocks.INSULATING_GLASS.defaultBlockState());
        var ports=new ArrayList<IndustrialLayout.Port>();var thermal=new ArrayList<IndustrialLayout.Wall>();
        var feed=IndustrialLayout.addPort(blocks,ports,min.offset(0,1,2),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        ports.set(ports.size()-1,feed.withRate(10_000_000L));
        var product=IndustrialLayout.addPort(blocks,ports,min.offset(3,2,1),Direction.NORTH,FluidPortBlockEntity.Mode.OUTPUT);
        ports.set(ports.size()-1,product.withRate(500_000L));
        var vent=IndustrialLayout.addPort(blocks,ports,min.offset(3,3,2),Direction.UP,FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.GAS);
        ports.set(ports.size()-1,vent.withRate(1_000_000_000L).withRedstone(true));

        // The pot, vapour passage and condenser stay as open air; every other interior block is insulation.
        var open=List.of(min.offset(1,1,2),min.offset(1,2,2),min.offset(2,2,2),min.offset(3,2,2));
        for(int y=1;y<4;y++)for(int x=1;x<5;x++)for(int z=1;z<3;z++) {
            var position=min.offset(x,y,z);
            if(!open.contains(position) && !position.equals(product.position()) && !position.equals(vent.position()))
                blocks.put(position,MmmBlocks.INSULATION.defaultBlockState());
        }

        for(var wall:List.of(min.offset(4,2,2),min.offset(3,1,2)))
            IndustrialLayout.addWall(blocks,thermal,wall,Blocks.IRON_BLOCK.defaultBlockState(),warm?400:285);
        for(var heater:List.of(min.offset(1,0,2),min.offset(1,1,1),min.offset(2,1,2)))
            IndustrialLayout.addWall(blocks,thermal,heater,MmmBlocks.THERMAL_RESERVOIR.defaultBlockState(),600);

        var pressureSensor=min.offset(4,3,2);
        blocks.put(pressureSensor,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.WEST));
        var condenser=min.offset(3,2,2);
        var sensors=List.of(new IndustrialLayout.Sensor(pressureSensor,mmm.api.sensor.ProcessSensorSnapshot.Measure.PRESSURE,
                100_000,110_000,false,condenser,condenser));
        var controller=min.offset(1,2,1);
        blocks.put(controller,MmmBlocks.INSULATION.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,List.of(),sensors,thermal,List.of());
    }
}
