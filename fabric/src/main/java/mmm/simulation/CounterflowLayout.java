package mmm.simulation;

import java.util.ArrayList;
import java.util.List;
import mmm.block.FluidPortBlockEntity;
import mmm.block.MmmBlocks;
import mmm.block.ValveBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/** Two narrow insulated channels flowing in opposite directions across one finite copper (or stone) partition. */
final class CounterflowLayout {
    private CounterflowLayout() { }

    static IndustrialLayout build(BlockPos min,boolean stone) {
        var blocks=IndustrialLayout.shell(min,4,5,9);
        for(int x=1;x<=2;x++)for(int y=1;y<4;y++) {
            blocks.put(min.offset(x,y,4),MmmBlocks.INSULATION.defaultBlockState());
            for(int z:new int[]{1,2,6,7})blocks.put(min.offset(x,y,z),MmmBlocks.INSULATION.defaultBlockState());
        }
        var walls=new ArrayList<IndustrialLayout.Wall>();
        for(int x=1;x<=2;x++) {
            IndustrialLayout.addWall(blocks,walls,min.offset(x,1,4),(stone?Blocks.STONE:MmmBlocks.FINITE_HEAT_EXCHANGER).defaultBlockState(),320);
            for(int y=1;y<4;y++) {
                blocks.put(min.offset(x,y,3),MmmBlocks.VALVE.defaultBlockState().setValue(ValveBlock.FACING,Direction.DOWN).setValue(ValveBlock.OPENING,1));
                blocks.put(min.offset(x,y,5),MmmBlocks.VALVE.defaultBlockState().setValue(ValveBlock.FACING,Direction.UP).setValue(ValveBlock.OPENING,1));
            }
        }
        var ports=new ArrayList<IndustrialLayout.Port>();
        IndustrialLayout.addPort(blocks,ports,min.offset(0,1,3),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        IndustrialLayout.addPort(blocks,ports,min.offset(3,1,3),Direction.EAST,FluidPortBlockEntity.Mode.STOPPED);
        IndustrialLayout.addPort(blocks,ports,min.offset(3,1,5),Direction.EAST,FluidPortBlockEntity.Mode.INPUT);
        IndustrialLayout.addPort(blocks,ports,min.offset(0,1,5),Direction.WEST,FluidPortBlockEntity.Mode.STOPPED);
        IndustrialLayout.addPort(blocks,ports,min.offset(1,0,3),Direction.DOWN,FluidPortBlockEntity.Mode.STOPPED);
        for(int z:new int[]{3,5})IndustrialLayout.addPort(blocks,ports,min.offset(1,4,z),Direction.UP,FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.GAS);
        ports.replaceAll(p->p.withRate(1_000_000_000L));
        var controller=min.offset(1,2,0);blocks.put(controller,Blocks.IRON_BLOCK.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,List.of(),List.of(),walls,List.of());
    }

    /** Open volume of the valve row beside the partition on one side, in container nano-units. */
    static long chargeNanos(IndustrialLayout layout,Direction side) {
        double volume=0;
        for(var wall:layout.walls()) {
            var state=layout.blocks().get(wall.position().relative(side));
            var blocked=ValveBlock.shutter(state).bounds();
            volume+=1-blocked.getXsize()*blocked.getYsize()*blocked.getZsize();
        }
        return Math.round(volume*mmm.transfer.ContainerFluidLedger.NANOS_PER_BLOCK);
    }
}
