package mmm.simulation;

import java.util.ArrayList;
import java.util.List;
import mmm.block.FluidPortBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/** Two parallel baffled channels whose iron baffles carry adsorbent or catalyst coatings on both faces. */
final class SurfaceChannelLayout {
    private SurfaceChannelLayout() { }

    static IndustrialLayout build(BlockPos min,boolean adsorption,boolean partial) {
        var blocks=IndustrialLayout.shell(min,10,5,8);var ports=new ArrayList<IndustrialLayout.Port>();
        IndustrialLayout.addPort(blocks,ports,min.offset(0,1,1),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        IndustrialLayout.addPort(blocks,ports,min.offset(9,1,1),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        IndustrialLayout.addPort(blocks,ports,min.offset(0,1,5),Direction.WEST,FluidPortBlockEntity.Mode.INPUT);
        IndustrialLayout.addPort(blocks,ports,min.offset(9,1,5),Direction.EAST,FluidPortBlockEntity.Mode.OUTPUT);
        if(adsorption)ports.replaceAll(p->p.withPhase(FluidPortBlockEntity.Phase.GAS));
        for(int x=1;x<9;x++)for(int y=1;y<=3;y++)for(int z=3;z<=4;z++)blocks.put(min.offset(x,y,z),Blocks.IRON_BLOCK.defaultBlockState());
        for(int x=1;x<9;x++)for(int z:new int[]{1,2,5,6})blocks.put(min.offset(x,3,z),Blocks.IRON_BLOCK.defaultBlockState());
        for(int x:new int[]{3,6})for(int y=1;y<=2;y++)for(int z=1;z<=6;z++) {
            if(z==3 || z==4 || x==3&&(z==2||z==6) || x==6&&(z==1||z==5))continue;
            blocks.put(min.offset(x,y,z),Blocks.IRON_BLOCK.defaultBlockState());
        }
        var coatings=new ArrayList<IndustrialLayout.Coating>();String material=adsorption?"mmm:co2_adsorbent":"mmm:hydration_catalyst";
        for(int x:new int[]{3,6})for(int y=1;y<=2;y++)for(int z=1;z<=2;z++)
            if((!partial || x==3) && blocks.containsKey(min.offset(x,y,z)))for(var face:List.of(Direction.WEST,Direction.EAST))
                coatings.add(new IndustrialLayout.Coating(min.offset(x,y,z),face,material,adsorption?.05:1));
        var controller=min.offset(2,1,0);blocks.put(controller,Blocks.IRON_BLOCK.defaultBlockState());
        return new IndustrialLayout(min,controller,blocks,ports,List.of(),List.of(),List.of(),coatings);
    }
}
