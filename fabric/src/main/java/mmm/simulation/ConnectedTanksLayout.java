package mmm.simulation;

import java.util.ArrayList;
import java.util.List;
import mmm.block.FluidPortBlockEntity;
import mmm.block.MmmBlocks;
import mmm.block.ProcessSensorBlock;
import mmm.block.ValveBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/** Two tanks joined through an iron partition by a valve bank and an upper gas passage, with a level sensor on the feed. */
final class ConnectedTanksLayout {
    private ConnectedTanksLayout() { }

    static IndustrialLayout build(BlockPos min) {
        int sx=14,sy=6,sz=5;var blocks=IndustrialLayout.shell(min,sx,sy,sz);
        // The liquid passage and upper gas passage cross the partition independently.
        for(int x=4;x<=9;x++)for(int y=1;y<5;y++)for(int z=1;z<4;z++)
            if(!(y<=2 && z<=2) && !(y==4 && z==3))blocks.put(min.offset(x,y,z),Blocks.IRON_BLOCK.defaultBlockState());
        for(int y=1;y<=2;y++)for(int z=1;z<=2;z++)
            blocks.put(min.offset(6,y,z),MmmBlocks.VALVE.defaultBlockState().setValue(ValveBlock.FACING,Direction.EAST));
        var ports=new ArrayList<IndustrialLayout.Port>();
        IndustrialLayout.addPort(blocks,ports,min.offset(0,3,1),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        IndustrialLayout.addPort(blocks,ports,min.offset(13,1,1),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        ports.set(0,ports.getFirst().withRedstone(true));
        var sensorPos=min.offset(0,3,0);
        blocks.put(sensorPos,MmmBlocks.PROCESS_SENSOR.defaultBlockState().setValue(ProcessSensorBlock.FACING,Direction.SOUTH));
        var sensor=new IndustrialLayout.Sensor(sensorPos,mmm.api.sensor.ProcessSensorSnapshot.Measure.LEVEL,.25,.75,true,min.offset(10,1,1),min.offset(12,3,3));
        var controller=min.offset(1,2,0);blocks.put(controller,Blocks.IRON_BLOCK.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,List.of(),List.of(sensor),List.of(),List.of());
    }
}
