package mmm.simulation;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import mmm.fluid.FluidDefinitions;
import mmm.fluid.air.GasInventory;
import mmm.inventory.LiquidCellInventory;
import mmm.pipe.*;
import mmm.thermal.GasThermalModel;
import mmm.units.SignedDyadic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Pure topology candidate. Air handoff and the world edit are committed by its caller. */
final class PipeTopologyEdit {
    record Air(GasInventory inventory,double energy) {
        Air { Objects.requireNonNull(inventory);if(!Double.isFinite(energy))throw new IllegalArgumentException("Invalid cavity air"); }
    }
    record Contents(Air air,List<String> materialIds,List<LiquidCellInventory.Stock> liquids,List<mmm.granular.GranularTransferLedger.Portion> deposits) {
        Contents { Objects.requireNonNull(air);materialIds=List.copyOf(materialIds);liquids=List.copyOf(liquids);deposits=List.copyOf(deposits); }
        static Contents air(Air value) { return new Contents(value,List.of(),List.of(),List.of()); }
    }
    record Result(PipeDimensionState state,Map<BlockPos,Air> returnedAir) { }
    private PipeTopologyEdit() { }

    static Result plan(PipeDimensionState previous,Map<BlockPos,BlockState> accepted,
                       Map<BlockPos,Contents> acquired,Predicate<BlockPos> loaded,ToDoubleFunction<BlockPos> sieve,ToDoubleFunction<BlockPos> openFraction,
                       FluidDefinitions definitions) {
        var oldIndices=new HashMap<String,Integer>();
        if(previous!=null)for(int i=0;i<previous.network().segments().size();i++)
            oldIndices.put(previous.network().segments().get(i).id(),i);
        var returned=new HashMap<BlockPos,Air>();
        if(previous!=null)for(var segment:previous.network().segments()) {
            var pos=BlockPos.of(Long.parseLong(segment.id()));if(accepted.containsKey(pos))continue;
            int index=oldIndices.get(segment.id());
            if(!segment.deposits().isEmpty() || !previous.network().liquids().rows().get(index).isEmpty())
                throw new IllegalArgumentException("请先排空管内液体并清理沉积，再拆除管道");
            returned.put(pos,new Air(segment.gas(),segment.gasInternalEnergy()));
        }
        if(accepted.isEmpty())return new Result(null,Map.copyOf(returned));
        var ordered=new TreeMap<Long,BlockState>();accepted.forEach((pos,state)->ordered.put(pos.asLong(),state));
        var segments=new ArrayList<PipeSegmentState>();var rows=new ArrayList<List<LiquidCellInventory.Stock>>();
        var palette=new ArrayList<String>(previous==null?List.of():previous.network().materialIds());
        for(var contents:acquired.values())for(var id:contents.materialIds())if(!palette.contains(id))palette.add(id);
        var installations=new TreeMap<String,PipeInstallation>();var oldGasVolumes=new ArrayList<Double>();var changedCapacity=new ArrayList<Boolean>();
        var gasThermal=new GasThermalModel(definitions.thermal(),definitions.gas());
        SignedDyadic supplied=SignedDyadic.ZERO,removed=SignedDyadic.ZERO;
        for(var entry:ordered.entrySet()) {
            String id=Long.toString(entry.getKey());var pos=BlockPos.of(entry.getKey());var state=entry.getValue();
            double capacity=PipeWorldGraph.capacity(state);int index=segments.size();
            installations.put(id,PipeWorldGraph.installation(state));Integer old=oldIndices.get(id);
            if(old==null) {
                var contents=Objects.requireNonNull(acquired.get(pos),"New pipe needs an acquired inventory");var air=contents.air();
                segments.add(new PipeSegmentState(id,pos.getY()+.5,capacity,air.inventory(),air.energy(),contents.deposits()));
                var stocks=new ArrayList<LiquidCellInventory.Stock>();double mass=air.inventory().totalMass();
                supplied=supplied.add(SignedDyadic.of(air.energy()));
                for(var stock:contents.liquids()) {
                    int material=palette.indexOf(contents.materialIds().get(stock.key().material()));
                    stocks.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(index,material,255),stock.volume(),stock.massUnits(),stock.mixture(),stock.internalEnergy()));
                    mass+=stock.massKilograms();supplied=supplied.add(SignedDyadic.of(stock.internalEnergy()));
                }
                for(var deposit:contents.deposits()){mass+=deposit.inventory().mass();supplied=supplied.add(SignedDyadic.of(deposit.internalEnergy()));}
                rows.add(stocks);oldGasVolumes.add(capacity);changedCapacity.add(false);
                supplied=supplied.add(SignedDyadic.of(mass*9.81*(pos.getY()+.5)));
            } else {
                var source=previous.network().segments().get(old);var sourceRows=previous.network().liquids().rows().get(old);
                double condensed=sourceRows.stream().mapToDouble(stock->stock.volume().toDouble()).sum();
                for(var deposit:source.deposits())condensed+=deposit.inventory().solidVolumeSvq()/(double)mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER;
                if(condensed>capacity)throw new IllegalArgumentException("管内库存超过新管腔容量，请先排空再调整管口");
                var copied=new ArrayList<LiquidCellInventory.Stock>();
                for(var stock:sourceRows)copied.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(index,stock.key().material(),255),
                        stock.volume(),stock.massUnits(),stock.mixture(),stock.internalEnergy()));
                rows.add(copied);oldGasVolumes.add(PipeHydraulics.mixture(source,sourceRows,gasThermal).gasVolumeM3());
                changedCapacity.add(Double.compare(source.capacityM3(),capacity)!=0);
                segments.add(new PipeSegmentState(id,pos.getY()+.5,capacity,source.gas(),source.gasInternalEnergy(),source.deposits()));
            }
        }
        for(var entry:returned.entrySet())removed=removed.add(SignedDyadic.of(entry.getValue().energy()))
                .add(SignedDyadic.of(entry.getValue().inventory().totalMass()*9.81*(entry.getKey().getY()+.5)));
        var edges=PipeWorldGraph.connections(segments,installations,loaded,sieve,openFraction);
        var network=new PipeNetworkState(segments,edges,palette,new LiquidCellInventory.Snapshot(rows));
        var active=new boolean[changedCapacity.size()];for(int i=0;i<active.length;i++)active[i]=changedCapacity.get(i);
        var compression=PipeGasWork.apply(network,oldGasVolumes.stream().mapToDouble(Double::doubleValue).toArray(),gasThermal,active);
        var before=previous==null?SignedDyadic.ZERO:PipeNetworkSolver.energy(previous.network(),definitions,9.81);
        var work=PipeNetworkSolver.energy(compression.state(),definitions,9.81).subtract(before).subtract(supplied).add(removed);
        var oldAudit=previous==null?PipeAudit.EMPTY:previous.audit();
        var audit=new PipeAudit(oldAudit.initialEnergy(),oldAudit.suppliedEnergy().add(supplied),oldAudit.removedEnergy().add(removed),
                oldAudit.externalWork().add(work),oldAudit.environmentHeat(),oldAudit.roundoff());
        return new Result(new PipeDimensionState(previous==null?1:Math.incrementExact(previous.generation()),compression.state(),audit,installations),Map.copyOf(returned));
    }
}
