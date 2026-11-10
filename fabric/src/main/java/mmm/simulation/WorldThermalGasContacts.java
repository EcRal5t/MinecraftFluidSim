package mmm.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mmm.fluid.EnvironmentBinding;
import mmm.thermal.EnvironmentThermalState;
import net.minecraft.core.BlockPos;

/** Matches each open domain face to the finite exterior air components touching its microfaces. */
final class WorldThermalGasContacts {
    private WorldThermalGasContacts() { }

    static List<EnvironmentBinding.GasReference> collect(FluidRegion region,
                                                        Map<EnvironmentThermalState.Key,Long> airMasks) {
        if (region.solverConfig().cellSize() != .5f)
            throw new IllegalArgumentException("World air contacts require half-metre region cells");
        var byBlock=new HashMap<BlockPos,List<Map.Entry<EnvironmentThermalState.Key,Long>>>();
        for(var entry:airMasks.entrySet()) {
            var key=entry.getKey();
            byBlock.computeIfAbsent(new BlockPos(key.x(),key.y(),key.z()),ignored->new ArrayList<>()).add(entry);
        }
        var contacts=new ArrayList<EnvironmentBinding.GasReference>();
        var grid=region.grid(); var origin=region.origin();
        for(int cell=0;cell<grid.cellCount();cell++) for(int side=0;side<6;side++) {
            if(grid.neighbor(cell,side)>=0)continue;
            int axis=side/2;
            int[] start={4*origin.getX()+2*grid.x(cell),4*origin.getY()+2*grid.y(cell),4*origin.getZ()+2*grid.z(cell)};
            start[axis]+=(side&1)==0?-1:2;
            var areas=new HashMap<EnvironmentThermalState.Key,Integer>();
            for(int u=0;u<2;u++)for(int v=0;v<2;v++) {
                int[] p=start.clone(); p[(axis+1)%3]+=u; p[(axis+2)%3]+=v;
                var block=new BlockPos(Math.floorDiv(p[0],4),Math.floorDiv(p[1],4),Math.floorDiv(p[2],4));
                int bit=Math.floorMod(p[0],4)+4*Math.floorMod(p[1],4)+16*Math.floorMod(p[2],4);
                for(var entry:byBlock.getOrDefault(block,List.of())) if((entry.getValue()&(1L<<bit))!=0)
                    areas.merge(entry.getKey(),1,Integer::sum);
            }
            final int faceCell=cell,faceSide=side;
            areas.entrySet().stream().sorted(java.util.Comparator.comparingInt(e->e.getKey().component()))
                    .forEach(e->contacts.add(new EnvironmentBinding.GasReference(faceCell,faceSide,e.getKey(),e.getValue()/4.0)));
        }
        return List.copyOf(contacts);
    }
}
