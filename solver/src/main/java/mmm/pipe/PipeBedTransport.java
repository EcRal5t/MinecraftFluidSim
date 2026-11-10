package mmm.pipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mmm.fluid.FluidDefinitions;
import mmm.granular.GranularTransferLedger;
import mmm.granular.SolidCompositionRecord;
import mmm.granular.SolidVolume;
import mmm.units.Mass;

/** Bed load follows accepted liquid flow after the material's Shields threshold is exceeded. */
public final class PipeBedTransport {
    private PipeBedTransport() { }
    public record Move(int edge,int source,int target,double liquidVolumeM3,double durationSeconds,
                       double liquidDensity,double viscosity) {
        public Move {
            if(edge<0 || source<0 || target<0 || source==target || !(Double.isFinite(liquidVolumeM3) && liquidVolumeM3>0)
                    || !(Double.isFinite(durationSeconds) && durationSeconds>0)
                    || !(Double.isFinite(liquidDensity) && liquidDensity>0) || !(Double.isFinite(viscosity) && viscosity>0))
                throw new IllegalArgumentException("Invalid pipe bed transfer");
        }
    }
    public static PipeNetworkState route(PipeNetworkState before,PipeNetworkState state,List<Move> moves,FluidDefinitions definitions,double gravity) {
        if(moves.isEmpty() || state.segments().stream().allMatch(segment -> segment.deposits().isEmpty()))return state;
        var segments=new ArrayList<>(state.segments());
        var sourceRows=new ArrayList<List<mmm.granular.GranularTransferLedger.Portion>>();
        for(var segment:segments)sourceRows.add(new ArrayList<>(segment.deposits()));
        var incoming=new ArrayList<List<mmm.granular.GranularTransferLedger.Portion>>();
        for(int i=0;i<segments.size();i++)incoming.add(new ArrayList<>());
        var bySource=new ArrayList<List<Move>>();for(int i=0;i<segments.size();i++)bySource.add(new ArrayList<>());
        for(var move:moves) {
            if(move.source()>=segments.size() || move.target()>=segments.size() || move.edge()>=state.connections().size())
                throw new IllegalArgumentException("Pipe bed transfer outside network");
            var edge=state.connections().get(move.edge());
            if(edge.opening()==0 || !(edge.first()==move.source() && edge.second()==move.target()
                    || edge.second()==move.source() && edge.first()==move.target()))
                throw new IllegalArgumentException("Pipe bed transfer lacks an open connection");
            bySource.get(move.source()).add(move);
        }
        for(int source=0;source<segments.size();source++) {
            var outgoing=bySource.get(source);
            if(outgoing.isEmpty())continue;
            var row=sourceRows.get(source);
            for(int item=0;item<row.size();item++) {
                var initial=row.get(item);if(initial==null)continue;
                var group=initial.inventory();var material=definitions.granular().material(definitions.granular().materialHandle(group.materialId()));
                double availableLiquid=0;for(var stock:before.liquids().rows().get(source))availableLiquid+=stock.volume().toDouble();
                if(availableLiquid<=0)continue;
                double[] desired=new double[outgoing.size()];double total=0,maximumRate=0;
                for(int j=0;j<outgoing.size();j++) {
                    var move=outgoing.get(j);var edge=state.connections().get(move.edge());
                    if(edge.sieveOpeningM()>0 && material.primaryGrainDiameterMeters()>edge.sieveOpeningM())continue;
                    double area=edge.areaM2()*edge.opening();if(!(area>0 && move.durationSeconds()>0))continue;
                    double speed=move.liquidVolumeM3()/(move.durationSeconds()*area),diameter=Math.sqrt(4*area/Math.PI);
                    double reynolds=move.liquidDensity()*speed*diameter/move.viscosity();
                    double friction=reynolds>0?(reynolds<2300?64/reynolds:.3164/Math.pow(reynolds,.25)):0;
                    double shear=friction*move.liquidDensity()*speed*speed/8;
                    double grainWeight=Math.max(0,material.referenceDensity()-move.liquidDensity())*gravity*material.primaryGrainDiameterMeters();
                    double shields=grainWeight>0?shear/grainWeight:Double.POSITIVE_INFINITY;
                    if(shields<=material.criticalShields())continue;
                    double excess=material.criticalShields()>0?shields/material.criticalShields()-1:1;
                    double rate=material.erosionRateSvqPerSecond()*move.durationSeconds()*excess;
                    maximumRate=Math.max(maximumRate,rate);
                    desired[j]=Math.min(rate,group.solidVolumeSvq()*Math.min(1,move.liquidVolumeM3()/availableLiquid));
                    total+=desired[j];
                }
                long totalSvq=(long)Math.floor(Math.min(group.solidVolumeSvq(),Math.min(total,maximumRate)));
                if(totalSvq<=0)continue;
                var weights=new mmm.units.SignedDyadic[desired.length];for(int j=0;j<weights.length;j++)weights[j]=mmm.units.SignedDyadic.of(desired[j]);
                long[] portions=mmm.inventory.LiquidCellInventory.allocateQuantities(totalSvq,weights);
                var retained=initial;
                for(int j=0;j<outgoing.size();j++)if(portions[j]>0 && retained!=null) {
                    int target=outgoing.get(j).target();double porosity=material.restPorosityQ()/1_000_000.0;
                    double free=segments.get(target).capacityM3()-bulk(sourceRows.get(target),definitions)-bulk(incoming.get(target),definitions);
                    long capacity=(long)Math.floor(Math.max(0,free)*(1-porosity)*SolidVolume.SVQ_PER_CUBIC_METER);
                    long amount=Math.min(portions[j],Math.min(capacity,retained.inventory().solidVolumeSvq()));
                    var parts=split(retained,amount,definitions);if(parts==null)continue;
                    incoming.get(target).add(parts[0]);retained=parts[1];
                }
                row.set(item,retained);
            }
        }
        boolean changed=false;
        for(int i=0;i<segments.size();i++) {
            var values=new TreeMap<String,mmm.granular.GranularTransferLedger.Portion>();
            for(var deposit:sourceRows.get(i))if(deposit!=null)values.merge(deposit.inventory().materialId(),deposit,(a,b)->merge(a,b,definitions));
            for(var deposit:incoming.get(i))values.merge(deposit.inventory().materialId(),deposit,(a,b)->merge(a,b,definitions));
            var old=segments.get(i);var deposits=List.copyOf(values.values());
            if(!deposits.equals(old.deposits())) {changed=true;segments.set(i,new PipeSegmentState(old.id(),old.elevationM(),old.capacityM3(),old.gas(),old.gasInternalEnergy(),deposits));}
        }
        return changed?new PipeNetworkState(segments,state.connections(),state.materialIds(),state.liquids()):state;
    }
    private static double bulk(List<mmm.granular.GranularTransferLedger.Portion> deposits,FluidDefinitions definitions) {
        double total=0;for(var d:deposits)if(d!=null) {
            var g=d.inventory();var m=definitions.granular().material(definitions.granular().materialHandle(g.materialId()));
            total+=g.solidVolumeSvq()/(double)SolidVolume.SVQ_PER_CUBIC_METER/(1-m.restPorosityQ()/1_000_000.0);
        }return total;
    }
    private static mmm.granular.GranularTransferLedger.Portion[] split(mmm.granular.GranularTransferLedger.Portion deposit,long amount,FluidDefinitions definitions) {
        var group=deposit.inventory();if(amount<=0)return null;
        if(amount==group.solidVolumeSvq())return new mmm.granular.GranularTransferLedger.Portion[]{deposit,null};
        long mass=Mass.portion(group.massUnits(),amount,group.solidVolumeSvq());
        if(mass<=0 || mass>=group.massUnits())return null;
        SolidCompositionRecord selected=null,remaining=null;
        if(group.composition()!=null) {
            var chosen=new TreeMap<String,Long>();var rest=new TreeMap<String,Long>();
            for(var entry:group.composition().stableAmounts(definitions.chemistry()).entrySet()) {
                long n=Mass.portion(entry.getValue(),mass,group.massUnits());
                if(n>0)chosen.put(entry.getKey(),n);if(n<entry.getValue())rest.put(entry.getKey(),entry.getValue()-n);
            }
            if(chosen.isEmpty() || rest.isEmpty())return null;
            selected=composition(chosen,mass,definitions);remaining=composition(rest,group.massUnits()-mass,definitions);
        }
        var thermal=new mmm.thermal.ThermalStateFunction(definitions.thermal());
        int handle=definitions.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.GRANULAR,group.materialId());
        var originalSpecies=group.composition()==null?Map.<String,Long>of():group.composition().stableAmounts(definitions.chemistry());
        var selectedSpecies=selected==null?Map.<String,Long>of():selected.stableAmounts(definitions.chemistry());
        double originalBase=thermal.baseEnergy(handle,group.mass(),originalSpecies);
        double selectedEnergy=thermal.baseEnergy(handle,Mass.kilograms(mass),selectedSpecies)
                +(deposit.internalEnergy()-originalBase)*mass/(double)group.massUnits();
        double remainingEnergy=mmm.units.SignedDyadic.of(deposit.internalEnergy()).subtract(mmm.units.SignedDyadic.of(selectedEnergy)).toDouble();
        return new mmm.granular.GranularTransferLedger.Portion[]{new mmm.granular.GranularTransferLedger.Portion(new GranularTransferLedger.Group(group.materialId(),amount,mass,selected),selectedEnergy),
                new mmm.granular.GranularTransferLedger.Portion(new GranularTransferLedger.Group(group.materialId(),group.solidVolumeSvq()-amount,group.massUnits()-mass,remaining),remainingEnergy)};
    }
    private static SolidCompositionRecord composition(Map<String,Long> species,long mass,FluidDefinitions definitions) {
        var value=SolidCompositionRecord.fromStableAmounts(definitions.chemistry(),species,0);
        return SolidCompositionRecord.fromStableAmounts(definitions.chemistry(),species,Math.subtractExact(mass,value.speciesMassUnits(definitions.chemistry())));
    }
    private static mmm.granular.GranularTransferLedger.Portion merge(mmm.granular.GranularTransferLedger.Portion a,mmm.granular.GranularTransferLedger.Portion b,FluidDefinitions definitions) {
        var x=a.inventory();var y=b.inventory();long mass=Math.addExact(x.massUnits(),y.massUnits());SolidCompositionRecord composition=null;
        if(x.composition()!=null || y.composition()!=null) {
            if(x.composition()==null || y.composition()==null)throw new IllegalArgumentException("Granular composition kinds differ");
            var species=new TreeMap<>(x.composition().stableAmounts(definitions.chemistry()));
            y.composition().stableAmounts(definitions.chemistry()).forEach((id,n)->species.merge(id,n,Math::addExact));
            composition=composition(species,mass,definitions);
        }
        return new mmm.granular.GranularTransferLedger.Portion(new GranularTransferLedger.Group(x.materialId(),Math.addExact(x.solidVolumeSvq(),y.solidVolumeSvq()),mass,composition),
                mmm.units.SignedDyadic.of(a.internalEnergy()).add(mmm.units.SignedDyadic.of(b.internalEnergy())).toDouble());
    }
}
