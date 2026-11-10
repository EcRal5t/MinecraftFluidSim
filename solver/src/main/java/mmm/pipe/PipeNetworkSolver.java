package mmm.pipe;

import java.util.ArrayList;
import java.util.List;
import mmm.fluid.FluidDefinitions;
import mmm.fluid.air.GasRegistry;
import mmm.inventory.LiquidCellInventory;
import mmm.thermal.GasThermalModel;
import mmm.thermal.MixtureVolumeModel;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;

/** A bounded finite-volume pipe step; input state stays unchanged until a result is adopted. */
public final class PipeNetworkSolver {
    private PipeNetworkSolver() { }
    public record Result(PipeNetworkState state,SignedDyadic energyRoundoff,SignedDyadic externalWork) { }
    private record Flux(int edge,int from,int to,double volume,double pressureWork) { }
    private record PhaseKey(double elevation,double capacity,mmm.fluid.air.GasInventory gas,long gasEnergy,
                            List<mmm.granular.GranularTransferLedger.Portion> deposits,List<String> materials,List<LiquidCellInventory.Stock> row) { }
    private record PhaseResult(PipeNetworkState state,SignedDyadic roundoff) { }

    public static Result step(PipeNetworkState state,double durationSeconds,FluidDefinitions definitions,double gravity) {
        return step(state,durationSeconds,definitions,gravity,java.util.Set.of());
    }
    public static Result step(PipeNetworkState state,double durationSeconds,FluidDefinitions definitions,double gravity,
                              java.util.Set<String> frozenSegments) {
        java.util.Objects.requireNonNull(frozenSegments);
        if (!(Double.isFinite(durationSeconds) && durationSeconds > 0 && Double.isFinite(gravity) && gravity >= 0))
            throw new IllegalArgumentException("Invalid pipe step duration or gravity");
        var gasThermal=new GasThermalModel(definitions.thermal(),definitions.gas());
        int count=state.segments().size();boolean[] active=new boolean[count];
        for(int i=0;i<count;i++)active[i]=!frozenSegments.contains(state.segments().get(i).id());
        var mixture=new MixtureVolumeModel.State[count];double[] viscosity=new double[count],outgoing=new double[count];
        var caloric=new ThermalStateFunction(definitions.thermal());
        for(int i=0;i<count;i++) {
            if(!active[i])continue;
            mixture[i]=PipeHydraulics.mixture(state.segments().get(i),state.liquids().rows().get(i),gasThermal);
            double weight=0,mu=0;
            for(var stock:state.liquids().rows().get(i))if(stock.massUnits()>0) {
                String id=state.materialIds().get(stock.key().material());
                int thermal=definitions.thermal().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,id);
                var composition=stock.mixture()==null?java.util.Map.<String,Long>of():stock.mixture().stableAmounts(definitions.chemistry());
                double temperature=caloric.stateForInternalEnergy(thermal,stock.massKilograms(),composition,stock.internalEnergy()).temperatureK();
                double volume=stock.volume().toDouble();weight+=volume;
                mu+=volume*definitions.material().entry(id).dynamicViscosityExtendingAt(temperature);
            }
            viscosity[i]=weight>0?mu/weight:1.8e-5;
        }
        int[] degree=new int[count];
        for(var edge:state.connections())if(edge.opening()>0 && active[edge.first()] && active[edge.second()]) {degree[edge.first()]++;degree[edge.second()]++;}
        var fluxes=new ArrayList<Flux>();
        for(int e=0;e<state.connections().size();e++) {
            var edge=state.connections().get(e);if(edge.opening()==0 || !active[edge.first()] || !active[edge.second()])continue;
            int a=edge.first(),b=edge.second();var ma=mixture[a];var mb=mixture[b];
            double density=Math.max(ma.liquidDensityKgPerM3(),mb.liquidDensityKgPerM3());
            if(density==0)density=Math.max(gasDensity(state,a,ma),gasDensity(state,b,mb));
            double head=ma.absolutePressurePa()-mb.absolutePressurePa()-density*gravity*edge.riseM();
            if(head==0)continue;
            int from=head>0?a:b,to=head>0?b:a;
            double resistance=PipeHydraulics.resistance(edge,state.segments().get(a),state.segments().get(b),
                    .5*(viscosity[a]+viscosity[b]),definitions.granular());
            double proposed=Math.abs(head)*durationSeconds/resistance;
            double ca=ma.pressureStorage(),cb=mb.pressureStorage();
            if(ca>0 && cb>0)proposed=Math.min(proposed,.45*Math.abs(head)/(1/ca+1/cb));
            double occupied=mixture[from].liquidVolumeM3()+mixture[from].gasVolumeM3();
            proposed=Math.min(proposed,.45*occupied) / Math.max(degree[a],degree[b]);
            if(proposed>0 && Double.isFinite(proposed)) {fluxes.add(new Flux(e,from,to,proposed,Math.abs(head)*proposed));outgoing[from]+=proposed;}
        }
        var liquidMoves=new ArrayList<PipeLiquidTransport.VolumeMove>();var gasMoves=new ArrayList<PipeGasTransport.Move>();
        var bedMoves=new ArrayList<PipeBedTransport.Move>();
        double[] gasVolumes=new double[count];for(int i=0;i<count;i++)if(active[i])gasVolumes[i]=mixture[i].gasVolumeM3();
        for(var flux:fluxes) {
            int from=flux.from(),to=flux.to();var m=mixture[from];
            double occupied=m.liquidVolumeM3()+m.gasVolumeM3();
            double scale=outgoing[from]>.45*occupied?.45*occupied/outgoing[from]:1;
            double amount=flux.volume()*scale;
            double liquid=occupied>0?amount*m.liquidVolumeM3()/occupied:0;
            if(liquid>0 && m.liquidScale()>0) {
                double reference=liquid/m.liquidScale();
                if(reference>0) {
                    liquidMoves.add(new PipeLiquidTransport.VolumeMove(flux.edge(),from,to,SignedDyadic.of(reference)));
                    bedMoves.add(new PipeBedTransport.Move(flux.edge(),from,to,liquid,durationSeconds,m.liquidDensityKgPerM3(),viscosity[from]));
                }
            }
            var gas=state.segments().get(from).gas();
            if(!gas.isEmpty() && m.gasVolumeM3()>0) {
                double gasMoved=amount*m.gasVolumeM3()/occupied;
                long nanos=(long)Math.floor(gas.standardVolumeNanos()*(gasMoved/m.gasVolumeM3()));
                if(nanos>0) {
                    gasMoves.add(new PipeGasTransport.Move(flux.edge(),from,to,nanos));
                    double actual=m.gasVolumeM3()*(nanos/(double)gas.standardVolumeNanos());
                    gasVolumes[from]-=actual;gasVolumes[to]+=actual;
                }
            }
        }
        if(liquidMoves.isEmpty() && gasMoves.isEmpty())return finishPhases(new Result(state,SignedDyadic.ZERO,SignedDyadic.ZERO),definitions,gravity,active);
        var liquid=PipeLiquidTransport.routeVolumes(state,liquidMoves,definitions.chemistry(),PipeThermalBindings.liquid(state,definitions));
        var gas=PipeGasTransport.route(liquid.state(),gasMoves,definitions.gas(),definitions.chemistry(),gasThermal);
        var work=PipeGasWork.apply(gas.state(),gasVolumes,gasThermal,active);
        var bed=PipeBedTransport.route(state,work.state(),bedMoves,definitions,gravity);
        return finishPhases(closeEnergy(state,bed,definitions,gravity,gasThermal,fluxes),definitions,gravity,active);
    }
    private static Result finishPhases(Result input,FluidDefinitions definitions,double gravity,boolean[] active) {
        var original=input.state();var segments=new ArrayList<>(original.segments());
        var rows=new ArrayList<>(original.liquids().rows());var materials=new ArrayList<>(original.materialIds());
        var roundoff=input.energyRoundoff();boolean changed=false;
        var candidates=new java.util.HashMap<PhaseKey,PhaseResult>();
        for(int i=0;i<segments.size();i++)if(active[i]) {
            var row=rows.get(i);var localRow=new ArrayList<LiquidCellInventory.Stock>(row.size());
            for(var stock:row)localRow.add(rekey(stock,0));
            var originalSegment=segments.get(i);
            var key=new PhaseKey(originalSegment.elevationM(),originalSegment.capacityM3(),originalSegment.gas(),
                    Double.doubleToRawLongBits(originalSegment.gasInternalEnergy()),originalSegment.deposits(),List.copyOf(materials),List.copyOf(localRow));
            var cached=candidates.get(key);
            if(cached!=null) {
                roundoff=roundoff.add(cached.roundoff());
                if(cached.state()!=null) {
                    changed=true;var value=cached.state().segments().getFirst();
                    segments.set(i,new PipeSegmentState(originalSegment.id(),originalSegment.elevationM(),originalSegment.capacityM3(),
                            value.gas(),value.gasInternalEnergy(),value.deposits()));
                    materials=new ArrayList<>(cached.state().materialIds());var updated=new ArrayList<LiquidCellInventory.Stock>();
                    for(var stock:cached.state().liquids().rows().getFirst())updated.add(rekey(stock,i));
                    rows.set(i,List.copyOf(updated));
                }
                continue;
            }
            var local=new PipeNetworkState(List.of(originalSegment),List.of(),materials,
                    new LiquidCellInventory.Snapshot(List.of(localRow)));
            var start=local;var localRoundoff=SignedDyadic.ZERO;
            for(var transition:definitions.thermal().transitions())
                if(transition.interfaceRequirement()==mmm.thermal.ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS) {
                    var result=PipeCondensation.condense(local,0,transition,definitions,gravity);
                    local=result.state();localRoundoff=localRoundoff.add(result.energyRoundoff());
                    var vapor=PipeVaporization.boil(local,0,transition,definitions,gravity);
                    local=vapor.state();localRoundoff=localRoundoff.add(vapor.energyRoundoff());
                }
            roundoff=roundoff.add(localRoundoff);
            candidates.put(key,new PhaseResult(local==start?null:local,localRoundoff));
            if(local!=start) {
                changed=true;segments.set(i,local.segments().getFirst());
                materials=new ArrayList<>(local.materialIds());var updated=new ArrayList<LiquidCellInventory.Stock>();
                for(var stock:local.liquids().rows().getFirst())updated.add(rekey(stock,i));
                rows.set(i,List.copyOf(updated));
            }
        }
        var state=changed?new PipeNetworkState(segments,original.connections(),materials,new LiquidCellInventory.Snapshot(rows)):original;
        return new Result(state,roundoff,input.externalWork());
    }
    private static LiquidCellInventory.Stock rekey(LiquidCellInventory.Stock stock,int cell) {
        return new LiquidCellInventory.Stock(new LiquidCellInventory.Key(cell,stock.key().material(),255),stock.volume(),
                stock.massUnits(),stock.mixture(),stock.internalEnergy());
    }
    private static double gasDensity(PipeNetworkState state,int index,MixtureVolumeModel.State mixture) {
        return mixture.gasVolumeM3()>0?state.segments().get(index).gas().totalMass()/mixture.gasVolumeM3():0;
    }
    /** Exact sum of stored U, gravity and liquid elastic energy in the current numerical state. */
    public static SignedDyadic energy(PipeNetworkState state,FluidDefinitions definitions,double gravity) {
        if (!(Double.isFinite(gravity) && gravity >= 0)) throw new IllegalArgumentException("Invalid gravity");
        return energy(state,definitions,gravity,new GasThermalModel(definitions.thermal(),definitions.gas()));
    }
    static SignedDyadic energy(PipeNetworkState state,FluidDefinitions definitions,double gravity,GasThermalModel gasThermal) {
        SignedDyadic total=SignedDyadic.ZERO;
        for(int i=0;i<state.segments().size();i++) {
            var segment=state.segments().get(i);double mass=segment.gas().totalMass();
            total=total.add(SignedDyadic.of(segment.gasInternalEnergy()));
            for(var d:segment.deposits()) {mass+=d.inventory().mass();total=total.add(SignedDyadic.of(d.internalEnergy()));}
            for(var stock:state.liquids().rows().get(i)) {mass+=stock.massKilograms();total=total.add(SignedDyadic.of(stock.internalEnergy()));}
            total=total.add(SignedDyadic.of(mass*gravity*segment.elevationM()));
            total=total.add(SignedDyadic.of(PipeHydraulics.mixture(segment,state.liquids().rows().get(i),gasThermal).liquidElasticEnergyJ()));
        }
        return total;
    }
    private static Result closeEnergy(PipeNetworkState before,PipeNetworkState after,FluidDefinitions definitions,double gravity,
                                      GasThermalModel thermal,List<Flux> fluxes) {
        int count=after.segments().size();int[] parent=new int[count];
        for(int i=0;i<count;i++)parent[i]=i;
        for(var flux:fluxes) {int a=root(parent,flux.from()),b=root(parent,flux.to());parent[b]=a;}
        var correction=new SignedDyadic[count];boolean[] active=new boolean[count];
        java.util.Arrays.fill(correction,SignedDyadic.ZERO);
        for(var flux:fluxes) {active[flux.from()]=true;active[flux.to()]=true;}
        for(int i=0;i<count;i++)if(active[i]) {
            int component=root(parent,i);
            correction[component]=correction[component].add(cellEnergy(before,i,gravity,thermal).subtract(cellEnergy(after,i,gravity,thermal)));
        }
        var segments=new ArrayList<>(after.segments());
        var liquidHeat=new java.util.HashMap<Integer,SignedDyadic>();
        double[] weights=new double[count];
        for(var flux:fluxes) {weights[flux.from()]+=.5*flux.pressureWork();weights[flux.to()]+=.5*flux.pressureWork();}
        double[] componentWeight=new double[count];int[] remainder=new int[count];
        java.util.Arrays.fill(remainder,-1);
        for(int i=0;i<count;i++)if(active[i] && (!after.liquids().rows().get(i).isEmpty() || !segments.get(i).gas().isEmpty())) {
            int c=root(parent,i);componentWeight[c]+=weights[i];
            if(remainder[c]<0 || weights[i]>weights[remainder[c]])remainder[c]=i;
        }
        var remaining=correction.clone();
        for(int i=0;i<count;i++)if(active[i]) {
            int c=root(parent,i);
            if(correction[c].signum()!=0 && i!=remainder[c] && weights[i]>0
                    && (!after.liquids().rows().get(i).isEmpty() || !segments.get(i).gas().isEmpty())) {
                var share=SignedDyadic.of(correction[c].toDouble()*weights[i]/componentWeight[c]);
                remaining[c]=remaining[c].subtract(share);assignHeat(after,segments,liquidHeat,i,share,thermal);
            }
        }
        for(int c=0;c<count;c++)if(correction[c].signum()!=0) {
            if(remainder[c]<0)throw new IllegalStateException("Pipe work has no thermal carrier");
            assignHeat(after,segments,liquidHeat,remainder[c],remaining[c],thermal);
        }
        var inventory=new LiquidCellInventory(after.liquids(),definitions.chemistry());
        var plan=inventory.planEdits(List.of()).withMechanicalHeat(liquidHeat);
        var next=new PipeNetworkState(segments,after.connections(),after.materialIds(),plan.future().snapshot());
        var function=new ThermalStateFunction(definitions.thermal());
        for(int i:liquidHeat.keySet())for(var stock:next.liquids().rows().get(i))if(stock.massUnits()>0) {
            int material=definitions.thermal().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,
                    next.materialIds().get(stock.key().material()));
            var species=stock.mixture()==null?java.util.Map.<String,Long>of():stock.mixture().stableAmounts(definitions.chemistry());
            function.stateForInternalEnergy(material,stock.massKilograms(),species,stock.internalEnergy());
        }
        return new Result(next,energy(next,definitions,gravity,thermal).subtract(energy(before,definitions,gravity,thermal)),SignedDyadic.ZERO);
    }
    private static void assignHeat(PipeNetworkState state,ArrayList<PipeSegmentState> segments,
                                   java.util.Map<Integer,SignedDyadic> liquidHeat,int index,SignedDyadic heat,
                                   GasThermalModel thermal) {
        if(!state.liquids().rows().get(index).isEmpty()) {liquidHeat.put(index,heat);return;}
        var segment=segments.get(index);double u=SignedDyadic.of(segment.gasInternalEnergy()).add(heat).toDouble();
        thermal.requireInventoryEnergy(segment.gas(),u);
        segments.set(index,new PipeSegmentState(segment.id(),segment.elevationM(),segment.capacityM3(),segment.gas(),u,segment.deposits()));
    }
    private static int root(int[] parent,int index) {
        while(parent[index]!=index) {parent[index]=parent[parent[index]];index=parent[index];}return index;
    }
    private static SignedDyadic cellEnergy(PipeNetworkState state,int index,double gravity,GasThermalModel thermal) {
        var segment=state.segments().get(index);double mass=segment.gas().totalMass();
        SignedDyadic result=SignedDyadic.of(segment.gasInternalEnergy());
        for(var d:segment.deposits()) {mass+=d.inventory().mass();result=result.add(SignedDyadic.of(d.internalEnergy()));}
        for(var stock:state.liquids().rows().get(index)) {mass+=stock.massKilograms();result=result.add(SignedDyadic.of(stock.internalEnergy()));}
        return result.add(SignedDyadic.of(mass*gravity*segment.elevationM())).add(SignedDyadic.of(
                PipeHydraulics.mixture(segment,state.liquids().rows().get(index),thermal).liquidElasticEnergyJ()));
    }
}
