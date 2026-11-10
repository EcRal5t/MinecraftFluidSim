package mmm.simulation;

import java.nio.file.Files;
import java.util.*;
import mmm.fluid.air.GasInventory;
import mmm.granular.GranularTransferLedger;
import mmm.inventory.LiquidCellInventory;
import mmm.pipe.*;
import mmm.units.SignedDyadic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Display projection reads real inventories and the accepted, supplied geometry. */
public final class PipeSnapshotPublisherValidation {
    private PipeSnapshotPublisherValidation() { }
    public static void run() throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-pipe-display-"),()->
                new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(java.util.EnumSet.allOf(mmm.worldprofile.WorldCapability.class),
                        definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var pos=new BlockPos(4,5,6);
        var deposit=new mmm.granular.GranularTransferLedger.Portion(new GranularTransferLedger.Group("mmm:fine_sand",1000,2000,null),50);
        var segment=new PipeSegmentState(Long.toString(pos.asLong()),5,.25,GasInventory.EMPTY,0,List.of(deposit));
        var stock=new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,0,255),SignedDyadic.of(.125),1000,null,123);
        var network=new PipeNetworkState(List.of(segment),List.of(),List.of("mmm:water"),new LiquidCellInventory.Snapshot(List.of(List.of(stock))));
        int west=mmm.block.PipeGeometry.bit(Direction.WEST),east=mmm.block.PipeGeometry.bit(Direction.EAST);
        var bore=mmm.block.PipeGeometry.bore(west|east,west|east);
        var projected=new PipeSnapshotPublisher(world).project(network,Map.of(pos,bore));
        if(projected.size()!=1 || projected.getFirst().position()!=pos.asLong() || projected.getFirst().layers().size()!=2
                || !projected.getFirst().layers().get(0).material().equals("mmm:granular")
                || !projected.getFirst().layers().get(1).material().equals("mmm:water")
                || projected.getFirst().layers().get(1).volumeM3()!=.125
                || projected.getFirst().layers().get(1).argb()!=world.resolveMaterial("mmm:water").displayArgb())
            throw new AssertionError("Pipe display did not preserve inventory and material colour");
        double volume=projected.getFirst().bore().stream().mapToDouble(box->
                (box.x1()-box.x0())*(box.y1()-box.y0())*(box.z1()-box.z0())/4096.0).sum();
        if(volume!=.25)throw new AssertionError("Pipe display did not use the supplied accepted bore");
    }
}
