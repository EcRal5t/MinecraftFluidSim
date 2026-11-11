package mmm.simulation;

import java.util.HashMap;
import java.util.Map;
import mmm.block.MmmBlocks;
import net.minecraft.core.BlockPos;

/**
 * Apparatus-level diagnostics for any {@link IndustrialLayout} process run.
 * Construct at the start of the run to record each finite thermal device's internal energy; {@link #print} then lists
 * devices whose energy changed noticeably and the liquid held in every interior block of the shell.
 */
final class ApparatusDiagnostics {
    private final FluidRegion region;
    private final CommittedWorldRegistries world;
    private final IndustrialLayout layout;
    private final Map<String,Double> initialEnergy=new HashMap<>();
    private final BlockPos interiorMinimum, interiorMaximum;

    ApparatusDiagnostics(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout) {
        this.region=region;this.world=world;this.layout=layout;
        for(var device:region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices())
            initialEnergy.put(device.providerId()+device.objectId(),device.body().internalEnergyJ());
        // Material boxes sit outside the shell; every other layout block lies on or inside it.
        int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(var entry:layout.blocks().entrySet())if(!entry.getValue().is(MmmBlocks.MATERIAL_BOX)) {
            var p=entry.getKey();maxX=Math.max(maxX,p.getX());maxY=Math.max(maxY,p.getY());maxZ=Math.max(maxZ,p.getZ());
        }
        interiorMinimum=layout.minimum().offset(1,1,1);
        interiorMaximum=new BlockPos(maxX-1,maxY-1,maxZ-1);
    }

    /** Prints {@code <prefix>_device} lines for devices with |ΔE| ≥ threshold and {@code <prefix>_block} lines for wetted interior blocks. */
    void print(String prefix,double thresholdJ) {
        printDeviceEnergyChanges(prefix,thresholdJ);
        printBlockLiquid(prefix);
    }

    /** Devices are reported at their block position relative to the layout minimum. */
    void printDeviceEnergyChanges(String prefix,double thresholdJ) {
        for(var device:region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices()) {
            double delta=device.body().internalEnergyJ()-initialEnergy.getOrDefault(device.providerId()+device.objectId(),0.0);
            if(Math.abs(delta)<thresholdJ)continue;
            String id=device.objectId();int at=id.indexOf('@');
            var p=BlockPos.of(Long.parseLong(at<0?id:id.substring(0,at))).subtract(layout.minimum());
            System.out.println(prefix+"_device rel="+p.toShortString()+" id="+id+" dE_J="+delta);
        }
    }

    /** Sums the liquid volume of the cells in each interior block and prints every block holding more than 1 cm³. */
    void printBlockLiquid(String prefix) {
        var host=region.hostSnapshot();int n=FluidRegionFactory.CELLS_PER_BLOCK;
        for(int x=interiorMinimum.getX();x<=interiorMaximum.getX();x++)
            for(int y=interiorMinimum.getY();y<=interiorMaximum.getY();y++)
                for(int z=interiorMinimum.getZ();z<=interiorMaximum.getZ();z++) {
                    double volume=0;
                    for(int dx=0;dx<n;dx++)for(int dy=0;dy<n;dy++)for(int dz=0;dz<n;dz++) {
                        var liquid=host.observeCellInventory(n*(x-region.origin().getX())+dx,n*(y-region.origin().getY())+dy,n*(z-region.origin().getZ())+dz);
                        if(liquid!=null)volume+=liquid.liquidVolumeCubicMetres();
                    }
                    if(volume>1e-6)System.out.println(prefix+"_block rel="+(x-layout.minimum().getX())+","+(y-layout.minimum().getY())+","
                            +(z-layout.minimum().getZ())+" liquid_m3="+volume);
                }
    }
}
