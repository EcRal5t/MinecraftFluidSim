package mmm.simulation;

import java.util.ArrayList;
import java.util.List;
import mmm.block.FluidPortBlockEntity;
import mmm.block.MmmBlocks;
import mmm.block.ScreenBlock;
import mmm.block.ValveBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/** Particle screening through a horizontal screen deck, and cooled-floor crystallization with a fine screen and outlet valve. */
final class ScreeningLayout {
    private ScreeningLayout() { }

    static IndustrialLayout screening(BlockPos min) {
        var blocks=IndustrialLayout.shell(min,7,7,7);var screens=new ArrayList<IndustrialLayout.Screen>();var ports=new ArrayList<IndustrialLayout.Port>();
        for(int x=1;x<6;x++)for(int z=1;z<6;z++) {
            var position=min.offset(x,3,z);
            blocks.put(position,MmmBlocks.SCREEN.defaultBlockState().setValue(ScreenBlock.FACING,Direction.UP));
            screens.add(new IndustrialLayout.Screen(position,.00025,.6));
        }
        IndustrialLayout.addPort(blocks,ports,min.offset(0,4,1),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        IndustrialLayout.addPort(blocks,ports,min.offset(6,1,1),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        IndustrialLayout.addPort(blocks,ports,min.offset(1,4,0),Direction.NORTH,FluidPortBlockEntity.Mode.INPUT,FluidPortBlockEntity.Phase.SOLID);
        IndustrialLayout.addPort(blocks,ports,min.offset(1,3,0),Direction.NORTH,FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.SOLID);
        var walls=new ArrayList<IndustrialLayout.Wall>();
        IndustrialLayout.addWall(blocks,walls,min.offset(3,4,1),Blocks.IRON_BLOCK.defaultBlockState(),280);
        var controller=min.offset(2,4,0);blocks.put(controller,Blocks.IRON_BLOCK.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,screens,List.of(),walls,List.of());
    }

    static IndustrialLayout crystallization(BlockPos min) {
        var blocks=IndustrialLayout.shell(min,7,7,3);var ports=new ArrayList<IndustrialLayout.Port>();var walls=new ArrayList<IndustrialLayout.Wall>();
        // The first two floor blocks are the cooled crystallizer surface; the rest of the floor is ordinary iron.
        for(int x=1;x<=5;x++) {
            var floor=min.offset(x,1,1);
            if(x<=2)IndustrialLayout.addWall(blocks,walls,floor,Blocks.IRON_BLOCK.defaultBlockState(),280);
            else blocks.put(floor,Blocks.IRON_BLOCK.defaultBlockState());
        }
        var screen=min.offset(2,2,1);
        blocks.put(screen,MmmBlocks.SCREEN.defaultBlockState().setValue(ScreenBlock.FACING,Direction.EAST));
        blocks.put(min.offset(3,2,1),MmmBlocks.VALVE.defaultBlockState().setValue(ValveBlock.FACING,Direction.EAST).setValue(ValveBlock.OPENING,0));
        IndustrialLayout.addPort(blocks,ports,min.offset(1,2,2),Direction.SOUTH,FluidPortBlockEntity.Mode.INPUT);
        IndustrialLayout.addPort(blocks,ports,min.offset(6,2,1),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        IndustrialLayout.addPort(blocks,ports,min.offset(1,2,0),Direction.NORTH,FluidPortBlockEntity.Mode.INPUT,FluidPortBlockEntity.Phase.SOLID);
        var controller=min.offset(2,4,0);blocks.put(controller,Blocks.IRON_BLOCK.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,List.of(new IndustrialLayout.Screen(screen,.000005,.6)),List.of(),walls,List.of());
    }
}
