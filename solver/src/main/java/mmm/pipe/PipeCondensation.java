package mmm.pipe;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.fluid.FluidDefinitions;
import mmm.granular.SolidVolume;
import mmm.inventory.LiquidCellInventory;
import mmm.thermal.GasLiquidPhaseModel;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalPhaseTransition;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;

/** Finite vapor condensation candidates in one pipe segment. */
public final class PipeCondensation {
    private PipeCondensation() { }
    public record Result(PipeNetworkState state,SignedDyadic energyRoundoff,long batches) { }
    public static Result condense(PipeNetworkState state,int cell,ThermalPhaseTransition transition,
                                  FluidDefinitions definitions,double gravity) {
        if(cell<0 || cell>=state.segments().size() || transition.interfaceRequirement()!=ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS)
            throw new IllegalArgumentException("Invalid pipe condensation target");
        var upper=definitions.thermal().material(definitions.thermal().materialHandle(transition.upperEnergyMaterialId()));
        var lower=definitions.thermal().material(definitions.thermal().materialHandle(transition.lowerEnergyMaterialId()));
        if(upper.carrierKind()!=ThermalMaterialDefinition.CarrierKind.GAS || lower.carrierKind()!=ThermalMaterialDefinition.CarrierKind.LIQUID)
            throw new IllegalArgumentException("Pipe condensation requires liquid and gas carriers");
        var segment=state.segments().get(cell);var gas=segment.gas();var component=gas.component(upper.carrierId());
        if(component==null)return new Result(state,SignedDyadic.ZERO,0);
        var model=new GasLiquidPhaseModel(definitions.thermal(),definitions.gas());
        var thermal=new GasThermalModel(definitions.thermal(),definitions.gas());
        double temperature=thermal.state(gas,segment.gasInternalEnergy()).temperatureK();
        double pressure=PipeHydraulics.mixture(segment,state.liquids().rows().get(cell),thermal).absolutePressurePa();
        if(model.saturation(transition,temperature,gas,pressure).direction()!=GasLiquidPhaseModel.Direction.CONDENSE)
            return new Result(state,SignedDyadic.ZERO,0);
        var unit=model.gasBatch(transition,1).components().getFirst();
        long max=Math.min(mmm.thermal.ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP,component.standardVolumeNanos()/unit.standardVolumeNanos());
        Result best=new Result(state,SignedDyadic.ZERO,0);long low=1,high=max;
        while(low<=high) {
            long batches=low+(high-low)/2;
            Result trial=trial(state,cell,transition,definitions,gravity,thermal,lower,unit,batches,temperature);
            boolean accepted=false;
            if(trial!=null) {
                var result=trial.state().segments().get(cell);var remaining=result.gas();
                if(!remaining.isEmpty()) {
                    double t=thermal.state(remaining,result.gasInternalEnergy()).temperatureK();
                    double p=PipeHydraulics.mixture(result,trial.state().liquids().rows().get(cell),thermal).absolutePressurePa();
                    accepted=model.saturation(transition,t,remaining,p).direction()!=GasLiquidPhaseModel.Direction.BOIL;
                }
            }
            if(accepted) {best=trial;low=batches+1;} else high=batches-1;
        }
        return best;
    }
    private static Result trial(PipeNetworkState state,int cell,ThermalPhaseTransition transition,FluidDefinitions definitions,
                                double gravity,GasThermalModel thermal,ThermalMaterialDefinition lower,
                                mmm.fluid.air.GasComponentAmount unit,long batches,double temperature) {
        try {
            var old=state.segments().get(cell);long nanos=Math.multiplyExact(unit.standardVolumeNanos(),batches);
            var split=old.gas().splitComponent(unit.componentId(),nanos,definitions.gas(),definitions.chemistry());
            var selected=split.first().component(unit.componentId());
            if(selected==null || split.second().isEmpty())return null;
            var amounts=new TreeMap<String,Long>();for(var species:selected.species())amounts.put(species.speciesId(),species.amountQuanta());
            var phase=definitions.chemistry().phase(definitions.chemistry().phaseHandleForMaterial(lower.carrierId()));
            if(amounts.isEmpty() && !phase.defaultConcentrationMolPerCubicMeter().containsKey(transition.speciesId()))return null;
            ChemicalMixtureRecord mixture=amounts.isEmpty()?null:ChemicalMixtureRecord.fromStableAmounts(definitions.chemistry(),amounts,selected.massResidualUnits());
            var materials=new ArrayList<>(state.materialIds());int material=materials.indexOf(lower.carrierId());
            if(material<0) {material=materials.size();materials.add(lower.carrierId());}
            var function=new ThermalStateFunction(definitions.thermal());
            int handle=definitions.thermal().materialHandle(lower.id());
            double u=function.internalEnergyAtTemperature(handle,selected.mass(),mixture,definitions.chemistry(),temperature);
            var stock=new LiquidCellInventory.Stock(new LiquidCellInventory.Key(cell,material,255),
                    SignedDyadic.of(transition.lowerVolumeSvqPerBatch()/(double)SolidVolume.SVQ_PER_CUBIC_METER
                            *selected.massUnits()/unit.massUnits()),
                    selected.massUnits(),mixture,u);
            var liquid=new LiquidCellInventory(state.liquids(),definitions.chemistry());
            var plan=liquid.planAdditions(List.of(stock));
            double gasU=SignedDyadic.of(old.gasInternalEnergy()).subtract(SignedDyadic.of(u)).toDouble();
            thermal.requireInventoryEnergy(split.second(),gasU);
            var segments=new ArrayList<>(state.segments());
            segments.set(cell,new PipeSegmentState(old.id(),old.elevationM(),old.capacityM3(),split.second(),gasU,old.deposits()));
            var candidate=new PipeNetworkState(segments,state.connections(),materials,plan.future().snapshot());
            var correction=PipeNetworkSolver.energy(state,definitions,gravity,thermal).subtract(PipeNetworkSolver.energy(candidate,definitions,gravity,thermal));
            var inventory=new LiquidCellInventory(candidate.liquids(),definitions.chemistry());
            var heat=inventory.planEdits(List.of()).withMechanicalHeat(java.util.Map.of(cell,correction));
            var result=new PipeNetworkState(segments,state.connections(),materials,heat.future().snapshot());
            for(var value:result.liquids().rows().get(cell))if(value.massUnits()>0) {
                int h=definitions.thermal().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,materials.get(value.key().material()));
                var species=value.mixture()==null?java.util.Map.<String,Long>of():value.mixture().stableAmounts(definitions.chemistry());
                function.stateForInternalEnergy(h,value.massKilograms(),species,value.internalEnergy());
            }
            return new Result(result,PipeNetworkSolver.energy(result,definitions,gravity,thermal).subtract(PipeNetworkSolver.energy(state,definitions,gravity,thermal)),batches);
        } catch (ThermalStateFunction.EnergyOutsideValidityException | ThermalStateFunction.EnergyRepresentationException outside) { return null; }
    }
}
