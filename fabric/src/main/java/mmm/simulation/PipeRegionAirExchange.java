package mmm.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mmm.fluid.FluidDefinitions;
import mmm.fluid.PipeRegionCells;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasPartitionWeights;
import mmm.thermal.GasThermalModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Transfers cavity gas between private region candidates and newly assembled pipes. */
final class PipeRegionAirExchange {
    private record Key(FluidRegion region,int cell) { }
    private PipeRegionAirExchange() { }
    static Prepared prepare(ServerLevel level,FluidRegionManager regions,Map<BlockPos,Double> newVolumes,
                            Map<BlockPos,PipeTopologyEdit.Air> returned,FluidDefinitions definitions) {
        return prepare(level.dimension(),pos->mmm.block.PipeGeometry.bore(level.getBlockState(pos)),regions,newVolumes,returned,definitions);
    }
    static Prepared prepare(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                            java.util.function.Function<BlockPos,net.minecraft.world.phys.shapes.VoxelShape> bore,
                            FluidRegionManager regions,Map<BlockPos,Double> newVolumes,
                            Map<BlockPos,PipeTopologyEdit.Air> returned,FluidDefinitions definitions) {
        var hosts=new LinkedHashMap<FluidRegion,mmm.fluid.HostFluidState>();
        var generations=new HashMap<FluidRegion,Long>();var cells=new LinkedHashMap<Key,PipeRegionCells.Cell>();
        var acquired=new LinkedHashMap<BlockPos,PipeTopologyEdit.Air>();
        var contents=new LinkedHashMap<BlockPos,PipeTopologyEdit.Contents>();
        var thermal=new GasThermalModel(definitions.thermal(),definitions.gas());
        var granular=new HashMap<BlockPos,List<mmm.granular.GranularTransferLedger.Portion>>();
        var cellOwner=new HashMap<Key,BlockPos>();
        for(var entry:newVolumes.entrySet()) {
            var deposits=new ArrayList<mmm.granular.GranularTransferLedger.Portion>();
            for(var key:contactCells(dimension,regions,entry.getKey(),hosts,generations,cells)) {
                cellOwner.put(key,entry.getKey());
                var host=hosts.get(key.region());var cell=cells.get(key);double h=host.config().cellSize();
                double geometric=Integer.bitCount(cell.support())*.125*h*h*h;
                double fraction=Math.min(1,cavityInCell(bore.apply(entry.getKey()),host,key.cell(),cell.support())/geometric);
                deposits.addAll(mmm.fluid.PipeRegionGranular.extract(host,key.cell(),fraction,definitions));
            }
            granular.put(entry.getKey(),List.copyOf(deposits));
        }
        for(var key:List.copyOf(cells.keySet()))cells.put(key,PipeRegionCells.capture(hosts.get(key.region()),key.cell()));
        for(var entry:newVolumes.entrySet()) {
            var keys=contactCells(dimension,regions,entry.getKey(),hosts,generations,cells);
            var liquidPalette=hosts.get(keys.getFirst().region()).materialRegistry().materials().stream().map(mmm.fluid.FluidMaterial::id).toList();
            var liquid=new mmm.inventory.LiquidCellInventory(1,definitions.chemistry());
            var deposits=granular.get(entry.getKey());
            var collected=GasInventory.EMPTY;double energy=0;
            for(var key:keys) {
                var host=hosts.get(key.region());var cell=cells.get(key);
                double intersection=cavityInCell(bore.apply(entry.getKey()),host,key.cell(),cell.support());
                double h=host.config().cellSize();double geometric=Integer.bitCount(cell.support())*.125*h*h*h;
                double fraction=Math.min(1,intersection/geometric);
                if(!(Double.isFinite(fraction)&&fraction>=0))throw new IllegalStateException("管腔交集无效");
                var split=splitLiquid(cell,host,liquidPalette,fraction,definitions);
                if(!split.moved().isEmpty())liquid.planAdditions(split.moved()).commit();
                var portions=cell.gas().partition(GasPartitionWeights.of(new double[]{fraction,1-fraction}),definitions.gas(),definitions.chemistry());
                var parts=thermal.partitionEnergy(cell.gas(),cell.gasEnergy(),portions);
                collected=collected.merge(portions.get(0));energy+=parts[0];
                cells.put(key,new PipeRegionCells.Cell(cell.index(),cell.capacityM3(),cell.support(),split.remaining(),portions.get(1),parts[1]));
            }
            var air=new PipeTopologyEdit.Air(collected,energy);acquired.put(entry.getKey(),air);
            contents.put(entry.getKey(),new PipeTopologyEdit.Contents(air,liquidPalette,liquid.snapshot().rows().getFirst(),deposits));
        }
        for(var entry:returned.entrySet()) {
            var keys=contactCells(dimension,regions,entry.getKey(),hosts,generations,cells);var weights=new double[keys.size()];
            for(int i=0;i<keys.size();i++)weights[i]=gasVolume(hosts.get(keys.get(i).region()),keys.get(i).cell());
            var portions=entry.getValue().inventory().partition(GasPartitionWeights.of(weights),definitions.gas(),definitions.chemistry());
            var energies=thermal.partitionEnergy(entry.getValue().inventory(),entry.getValue().energy(),portions);
            for(int i=0;i<keys.size();i++) {
                var key=keys.get(i);var cell=cells.get(key);
                cells.put(key,new PipeRegionCells.Cell(cell.index(),cell.capacityM3(),cell.support(),cell.liquid(),cell.gas().merge(portions.get(i)),cell.gasEnergy()+energies[i]));
            }
        }
        var byRegion=new LinkedHashMap<FluidRegion,List<PipeRegionCells.Cell>>();
        cells.forEach((key,cell)->byRegion.computeIfAbsent(key.region(),ignored->new ArrayList<>()).add(cell));
        var kinetic=new HashMap<BlockPos,mmm.units.SignedDyadic>();
        for(var entry:byRegion.entrySet()) {
            var transferred=PipeRegionCells.replace(hosts.get(entry.getKey()),entry.getValue());
            for(var value:transferred.removedKineticEnergyByCell().entrySet()) {
                var owner=cellOwner.get(new Key(entry.getKey(),value.getKey()));
                if(owner==null)throw new IllegalStateException("区域流体动能没有管腔接收方");
                kinetic.merge(owner,value.getValue(),mmm.units.SignedDyadic::add);
            }
        }
        for(var entry:kinetic.entrySet()) {
            var original=contents.get(entry.getKey());var air=original.air();
            var segment=new mmm.pipe.PipeSegmentState("acquired",0,newVolumes.get(entry.getKey()),air.inventory(),air.energy(),original.deposits());
            var network=new mmm.pipe.PipeNetworkState(List.of(segment),List.of(),original.materialIds(),new mmm.inventory.LiquidCellInventory.Snapshot(List.of(original.liquids())));
            var heated=mmm.pipe.PipeWallHeat.addSegmentHeat(network,0,entry.getValue(),definitions).state();
            var next=heated.segments().getFirst();var heatedAir=new PipeTopologyEdit.Air(next.gas(),next.gasInternalEnergy());
            acquired.put(entry.getKey(),heatedAir);
            contents.put(entry.getKey(),new PipeTopologyEdit.Contents(heatedAir,original.materialIds(),heated.liquids().rows().getFirst(),next.deposits()));
        }
        var edits=new ArrayList<RegionPipeEdit>();
        try {
            for(var entry:hosts.entrySet()) {
                if(entry.getKey().solverGeneration()!=generations.get(entry.getKey()))throw new IllegalStateException("管道交接期间区域已变化");
                edits.add(new RegionPipeEdit(entry.getKey(),entry.getValue()));
            }
            return new Prepared(Map.copyOf(acquired),Map.copyOf(contents),edits);
        } catch(RuntimeException | Error failure) { for(var edit:edits)edit.close();throw failure; }
    }
    private record LiquidSplit(List<mmm.inventory.LiquidCellInventory.Stock> remaining,List<mmm.inventory.LiquidCellInventory.Stock> moved) { }
    private static LiquidSplit splitLiquid(PipeRegionCells.Cell cell,mmm.fluid.HostFluidState host,List<String> palette,double fraction,FluidDefinitions definitions) {
        if(fraction==0 || cell.liquid().isEmpty())return new LiquidSplit(cell.liquid(),List.of());
        var source=new ArrayList<mmm.inventory.LiquidCellInventory.Stock>();var total=mmm.units.SignedDyadic.ZERO;
        double h=host.config().cellSize();var scale=mmm.units.SignedDyadic.of(h*h*h);
        for(var stock:cell.liquid()) {
            var key=new mmm.inventory.LiquidCellInventory.Key(0,stock.key().material(),255);
            var volume=stock.volume().multiply(scale);total=total.add(volume);
            source.add(new mmm.inventory.LiquidCellInventory.Stock(key,volume,stock.massUnits(),stock.mixture(),stock.internalEnergy()));
        }
        var segments=List.of(new mmm.pipe.PipeSegmentState("source",0,1,GasInventory.EMPTY,0,List.of()),new mmm.pipe.PipeSegmentState("target",0,1,GasInventory.EMPTY,0,List.of()));
        var edges=List.of(new mmm.pipe.PipeConnection(0,1,1,.25,0,1,0));
        var network=new mmm.pipe.PipeNetworkState(segments,edges,palette,new mmm.inventory.LiquidCellInventory.Snapshot(List.of(source,List.of())));
        var request=total.multiply(mmm.units.SignedDyadic.of(fraction));
        var routed=mmm.pipe.PipeLiquidTransport.routeVolumes(network,List.of(new mmm.pipe.PipeLiquidTransport.VolumeMove(0,0,1,request)),definitions.chemistry(),mmm.pipe.PipeThermalBindings.liquid(network,definitions));
        var remaining=new ArrayList<mmm.inventory.LiquidCellInventory.Stock>();var moved=new ArrayList<mmm.inventory.LiquidCellInventory.Stock>();
        var inverse=mmm.units.SignedDyadic.of(1/(h*h*h));
        for(var stock:routed.state().liquids().rows().get(0))remaining.add(new mmm.inventory.LiquidCellInventory.Stock(new mmm.inventory.LiquidCellInventory.Key(cell.index(),stock.key().material(),cell.support()),stock.volume().multiply(inverse),stock.massUnits(),stock.mixture(),stock.internalEnergy()));
        for(var stock:routed.state().liquids().rows().get(1))moved.add(new mmm.inventory.LiquidCellInventory.Stock(new mmm.inventory.LiquidCellInventory.Key(0,stock.key().material(),255),stock.volume(),stock.massUnits(),stock.mixture(),stock.internalEnergy()));
        return new LiquidSplit(remaining,moved);
    }
    private static double cavityInCell(net.minecraft.world.phys.shapes.VoxelShape bore,mmm.fluid.HostFluidState host,int cell,int support) {
        var grid=host.grid();int x=grid.x(cell)&1,y=grid.y(cell)&1,z=grid.z(cell)&1;double total=0;
        for(var box:bore.toAabbs())for(int micro=0;micro<8;micro++)if((support&(1<<micro))!=0) {
            double loX=x*.5+(micro&1)*.25,loY=y*.5+((micro>>1)&1)*.25,loZ=z*.5+((micro>>2)&1)*.25;
            double dx=Math.max(0,Math.min(box.maxX,loX+.25)-Math.max(box.minX,loX));
            double dy=Math.max(0,Math.min(box.maxY,loY+.25)-Math.max(box.minY,loY));
            double dz=Math.max(0,Math.min(box.maxZ,loZ+.25)-Math.max(box.minZ,loZ));total+=dx*dy*dz;
        }
        return total;
    }
    private static double gasVolume(mmm.fluid.HostFluidState host,int cell) {
        var grid=host.grid();return host.cellGasVolume(grid.x(cell),grid.y(cell),grid.z(cell));
    }
    private static List<Key> contactCells(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,FluidRegionManager regions,BlockPos pos,
            Map<FluidRegion,mmm.fluid.HostFluidState> hosts,Map<FluidRegion,Long> generations,Map<Key,PipeRegionCells.Cell> cells) {
        var region=regions.regions().stream().filter(r->r.dimension().equals(dimension) && r.footprint()!=null && r.footprint().containsInterior(pos)).findFirst()
                .orElseThrow(()->new IllegalStateException("管道位置没有可用区域"));
        if(!region.canStep() || region.availabilityFailure()!=null || region.hasPendingPhaseMotionWork())throw new IllegalStateException("管道区域暂不可用");
        var host=hosts.computeIfAbsent(region,r->{generations.put(r,r.solverGeneration());return r.beginHostEdit();});
        int x=(pos.getX()-region.origin().getX())*2,y=(pos.getY()-region.origin().getY())*2,z=(pos.getZ()-region.origin().getZ())*2;
        var result=new ArrayList<Key>();
        for(int dz=0;dz<2;dz++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++) {
            int index=host.grid().cell(x+dx,y+dy,z+dz);if(index<0)continue;
            var key=new Key(region,index);cells.computeIfAbsent(key,ignored->PipeRegionCells.capture(host,index));result.add(key);
        }
        if(result.isEmpty())throw new IllegalStateException("管道位置没有开放区域格");
        return result;
    }
    static final class Prepared implements AutoCloseable {
        private final Map<BlockPos,PipeTopologyEdit.Air> acquired;private final Map<BlockPos,PipeTopologyEdit.Contents> contents;private final List<RegionPipeEdit> edits;private boolean handled;
        Prepared(Map<BlockPos,PipeTopologyEdit.Air> acquired,Map<BlockPos,PipeTopologyEdit.Contents> contents,List<RegionPipeEdit> edits){this.acquired=acquired;this.contents=contents;this.edits=List.copyOf(edits);}
        Map<BlockPos,PipeTopologyEdit.Air> acquired(){return acquired;}
        Map<BlockPos,PipeTopologyEdit.Contents> inventories(){
            return contents;
        }
        boolean isCurrent(){return !handled && edits.stream().allMatch(RegionPipeEdit::isCurrent);}
        void commit(mmm.fluid.PreparedEnvironmentState environment){if(!isCurrent())throw new IllegalStateException("管道气体交接已过期");RegionPipeEdit.commit(edits,environment);handled=true;}
        void publishObservations(){if(!handled)throw new IllegalStateException("管道气体交接尚未提交");RegionPipeEdit.publishObservations(edits);}
        @Override public void close(){for(var edit:edits)edit.close();handled=true;}
    }
}
