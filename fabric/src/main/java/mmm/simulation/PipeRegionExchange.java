package mmm.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mmm.fluid.FluidDefinitions;
import mmm.fluid.PipeRegionCells;
import mmm.inventory.LiquidCellInventory;
import mmm.pipe.PipeConnection;
import mmm.pipe.PipeNetworkSolver;
import mmm.pipe.PipeNetworkState;
import mmm.pipe.PipeSegmentState;
import mmm.units.SignedDyadic;
import net.minecraft.core.Direction;

/** One private pipe step coupled to the actual contacted regional cells. */
final class PipeRegionExchange {
    record Port(int pipeSegment, FluidRegion region, int cell, double areaM2, double lengthM,
                double riseM, double opening, Direction direction) { }
    private record Key(FluidRegion region,int cell) { }
    private record Contact(Key key,int segment,PipeRegionCells.Cell before) { }
    private PipeRegionExchange() { }

    static Prepared prepare(PipeNetworkState pipe,List<Port> ports,double seconds,FluidDefinitions definitions,double gravity) {
        return prepare(pipe,ports,seconds,definitions,gravity,java.util.Set.of());
    }
    static Prepared prepare(PipeNetworkState pipe,List<Port> ports,double seconds,FluidDefinitions definitions,double gravity,
                            java.util.Set<String> frozenSegments) {
        var hosts=new LinkedHashMap<FluidRegion,mmm.fluid.HostFluidState>();
        var generations=new HashMap<FluidRegion,Long>();
        var contacts=new LinkedHashMap<Key,Contact>();
        var segments=new ArrayList<>(pipe.segments());var edges=new ArrayList<>(pipe.connections());
        var palette=new ArrayList<>(pipe.materialIds());var rows=new ArrayList<List<LiquidCellInventory.Stock>>(pipe.liquids().rows());
        var granular=new LinkedHashMap<Key,List<mmm.granular.GranularTransferLedger.Portion>>();
        var original=new LinkedHashMap<Key,PipeRegionCells.Cell>();
        for(var port:ports) {
            var region=port.region();
            if(region==null || port.direction()==null || !region.canStep() || region.availabilityFailure()!=null || region.hasPendingPhaseMotionWork())
                throw new IllegalStateException("Pipe region is unavailable");
            if(port.pipeSegment()<0 || port.pipeSegment()>=pipe.segments().size())throw new IllegalArgumentException("Unknown pipe segment");
            var host=hosts.computeIfAbsent(region,r->{generations.put(r,r.solverGeneration());return r.beginHostEdit();});
            var key=new Key(region,port.cell());
            if(!original.containsKey(key))original.put(key,PipeRegionCells.capture(host,port.cell()));
        }
        for(var entry:original.entrySet())granular.put(entry.getKey(),mmm.fluid.PipeRegionGranular.capture(hosts.get(entry.getKey().region()),entry.getKey().cell(),definitions));
        for(var port:ports) {
            var region=port.region();
            if(region==null || port.direction()==null || !region.canStep() || region.availabilityFailure()!=null || region.hasPendingPhaseMotionWork())
                throw new IllegalStateException("Pipe region is unavailable");
            if(port.pipeSegment()<0 || port.pipeSegment()>=pipe.segments().size())throw new IllegalArgumentException("Unknown pipe segment");
            var host=hosts.computeIfAbsent(region,r->{generations.put(r,r.solverGeneration());return r.beginHostEdit();});
            var key=new Key(region,port.cell());var contact=contacts.get(key);
            double elevation=pipe.segments().get(port.pipeSegment()).elevationM()+port.riseM();
            if(contact==null) {
                var cell=original.get(key);int index=segments.size();
                contact=new Contact(key,index,cell);contacts.put(key,contact);
                double capacity=cell.capacityM3();
                for(var deposit:granular.get(key))capacity+=deposit.inventory().solidVolumeSvq()/(double)mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER;
                segments.add(new PipeSegmentState("region:"+region.id()+":"+port.cell(),elevation,capacity,cell.gas(),cell.gasEnergy(),granular.get(key)));
                var row=new ArrayList<LiquidCellInventory.Stock>();double h=host.config().cellSize();var factor=SignedDyadic.of(h*h*h);
                for(var stock:cell.liquid()) {
                    String id=host.materialRegistry().material(stock.key().material()).id();int material=palette.indexOf(id);
                    if(material<0){material=palette.size();palette.add(id);}
                    row.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(index,material,255),stock.volume().multiply(factor),stock.massUnits(),stock.mixture(),stock.internalEnergy()));
                }
                rows.add(List.copyOf(row));
            } else if(Math.abs(segments.get(contact.segment()).elevationM()-elevation)>1e-9) throw new IllegalArgumentException("Pipe contacts disagree on cell elevation");
            edges.add(new PipeConnection(port.pipeSegment(),contact.segment(),port.lengthM(),port.areaM2(),port.riseM(),port.opening(),0));
        }
        var combined=new PipeNetworkState(segments,edges,palette,new LiquidCellInventory.Snapshot(rows));
        var result=PipeNetworkSolver.step(combined,seconds,definitions,gravity,frozenSegments);
        if(result.state()==combined)return new Prepared(pipe,result.energyRoundoff(),result.externalWork(),List.of());
        var byRegion=new LinkedHashMap<FluidRegion,List<PipeRegionCells.Cell>>();
        for(var contact:contacts.values()) {
            var host=hosts.get(contact.key().region());var segment=result.state().segments().get(contact.segment());
            double h=host.config().cellSize();var scale=SignedDyadic.of(1/(h*h*h));var stocks=new ArrayList<LiquidCellInventory.Stock>();
            for(var stock:result.state().liquids().rows().get(contact.segment())) {
                String id=result.state().materialIds().get(stock.key().material());int material=host.materialRegistry().handle(id);
                var key=new LiquidCellInventory.Key(contact.key().cell(),material,contact.before().support());
                stocks.add(new LiquidCellInventory.Stock(key,stock.volume().multiply(scale),stock.massUnits(),stock.mixture(),stock.internalEnergy()));
            }
            byRegion.computeIfAbsent(contact.key().region(),ignored->new ArrayList<>()).add(new PipeRegionCells.Cell(contact.key().cell(),contact.before().capacityM3(),contact.before().support(),stocks,segment.gas(),segment.gasInternalEnergy()));
        }
        var heat=new java.util.TreeMap<Integer,SignedDyadic>();
        for(var entry:byRegion.entrySet()) {
            var transferred=PipeRegionCells.replace(hosts.get(entry.getKey()),entry.getValue());
            for(var item:transferred.removedKineticEnergyByCell().entrySet()) {
                int recipient=-1;
                for(var port:ports)if(port.region()==entry.getKey() && port.cell()==item.getKey()) {
                    int index=port.pipeSegment();
                    if(!result.state().liquids().rows().get(index).isEmpty() || !result.state().segments().get(index).gas().isEmpty()) {recipient=index;break;}
                }
                if(recipient<0)throw new IllegalStateException("Transferred momentum has no connected pipe carrier");
                heat.merge(recipient,item.getValue(),SignedDyadic::add);
            }
        }
        for(var contact:contacts.values()) {
            var deposits=result.state().segments().get(contact.segment()).deposits();
            if(!deposits.equals(granular.get(contact.key()))) {
                var host=hosts.get(contact.key().region());
                mmm.fluid.PipeRegionGranular.extract(host,contact.key().cell(),1,definitions);
                if(!deposits.isEmpty())mmm.fluid.PipeRegionGranular.insert(host,contact.key().cell(),deposits,definitions);
            }
        }
        var pipeSegments=new ArrayList<>(result.state().segments().subList(0,pipe.segments().size()));
        var pipeRows=new ArrayList<>(result.state().liquids().rows().subList(0,pipe.segments().size()));
        var output=new PipeNetworkState(pipeSegments,pipe.connections(),result.state().materialIds(),new LiquidCellInventory.Snapshot(pipeRows));
        var roundoff=result.energyRoundoff();
        for(var entry:heat.entrySet()) {
            var heated=mmm.pipe.PipeWallHeat.addSegmentHeat(output,entry.getKey(),entry.getValue(),definitions);
            output=heated.state();roundoff=roundoff.add(heated.roundoff());
        }
        var edits=new ArrayList<RegionPipeEdit>();
        try {
            for(var entry:hosts.entrySet()) {
                if(entry.getKey().solverGeneration()!=generations.get(entry.getKey())) throw new IllegalStateException("Pipe region changed during preparation");
                edits.add(new RegionPipeEdit(entry.getKey(),entry.getValue()));
            }
            return new Prepared(output,roundoff,result.externalWork(),edits);
        } catch(RuntimeException | Error failure) { for(var edit:edits)edit.close();throw failure; }
    }
    static final class Prepared implements AutoCloseable {
        private final PipeNetworkState state;private final SignedDyadic roundoff,work;private final List<RegionPipeEdit> edits;
        private boolean handled;
        Prepared(PipeNetworkState state,SignedDyadic roundoff,SignedDyadic work,List<RegionPipeEdit> edits){this.state=state;this.roundoff=roundoff;this.work=work;this.edits=List.copyOf(edits);}
        PipeNetworkState state(){return state;}
        SignedDyadic energyRoundoff(){return roundoff;}
        SignedDyadic externalWork(){return work;}
        boolean isCurrent(){return !handled && edits.stream().allMatch(RegionPipeEdit::isCurrent);}
        void commit(){commit(null);}
        void commit(mmm.fluid.PreparedEnvironmentState environment){
            if(!isCurrent())throw new IllegalStateException("Pipe exchange is stale");
            RegionPipeEdit.commit(edits,environment);handled=true;
        }
        void publishObservations(){if(!handled)throw new IllegalStateException("Pipe exchange is not committed");RegionPipeEdit.publishObservations(edits);}
        @Override public void close(){for(var edit:edits)edit.close();handled=true;}
    }
}
