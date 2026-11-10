package mmm.pipe;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.fluid.FluidDefinitions;
import mmm.inventory.LiquidCellInventory;
import mmm.thermal.GasLiquidPhaseModel;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalPhaseTransition;
import mmm.thermal.ThermalStateFunction;
import mmm.units.Mass;
import mmm.units.SignedDyadic;

/** Finite liquid vaporization candidates with exact material and species accounting. */
public final class PipeVaporization {
    private PipeVaporization() { }
    public record Result(PipeNetworkState state,SignedDyadic energyRoundoff,long batches) { }
    public static Result boil(PipeNetworkState state,int cell,ThermalPhaseTransition transition,FluidDefinitions definitions,double gravity) {
        var lower=definitions.thermal().material(definitions.thermal().materialHandle(transition.lowerEnergyMaterialId()));
        var upper=definitions.thermal().material(definitions.thermal().materialHandle(transition.upperEnergyMaterialId()));
        if(transition.interfaceRequirement()!=ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS
                || lower.carrierKind()!=ThermalMaterialDefinition.CarrierKind.LIQUID
                || upper.carrierKind()!=ThermalMaterialDefinition.CarrierKind.GAS)
            throw new IllegalArgumentException("Invalid pipe vaporization transition");
        int material=state.materialIds().indexOf(lower.carrierId());
        if(material<0)return new Result(state,SignedDyadic.ZERO,0);
        var key=new LiquidCellInventory.Key(cell,material,255);
        var inventory=new LiquidCellInventory(state.liquids(),definitions.chemistry());var stock=inventory.stock(key);
        if(stock==null || stock.massUnits()<=0)return new Result(state,SignedDyadic.ZERO,0);
        var phase=definitions.chemistry().phase(definitions.chemistry().phaseHandleForMaterial(lower.carrierId()));
        boolean solvent=phase.defaultConcentrationMolPerCubicMeter().containsKey(transition.speciesId());
        if(!solvent && (stock.mixture()==null || stock.mixture().amount(definitions.chemistry().speciesHandle(transition.speciesId()))==0))
            return new Result(state,SignedDyadic.ZERO,0);
        var function=new ThermalStateFunction(definitions.thermal());
        var species=stock.mixture()==null?java.util.Map.<String,Long>of():stock.mixture().stableAmounts(definitions.chemistry());
        int handle=definitions.thermal().materialHandle(lower.id());
        double temperature=function.stateForInternalEnergy(handle,stock.massKilograms(),species,stock.internalEnergy()).temperatureK();
        var model=new GasLiquidPhaseModel(definitions.thermal(),definitions.gas());var thermal=new GasThermalModel(definitions.thermal(),definitions.gas());
        var segment=state.segments().get(cell);double pressure=PipeHydraulics.mixture(segment,state.liquids().rows().get(cell),thermal).absolutePressurePa();
        if(model.saturation(transition,temperature,segment.gas(),pressure).direction()!=GasLiquidPhaseModel.Direction.BOIL)
            return new Result(state,SignedDyadic.ZERO,0);
        var unit=model.gasBatch(transition,1).components().getFirst();
        long volumeBatches=(long)Math.floor(stock.volume().toDouble()*mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER/transition.lowerVolumeSvqPerBatch());
        long max=Math.min(volumeBatches,(stock.massUnits()-1)/unit.massUnits());
        max=Math.min(max,Math.min(Long.MAX_VALUE/transition.lowerVolumeSvqPerBatch(),Long.MAX_VALUE/unit.standardVolumeNanos()));
        Result best=new Result(state,SignedDyadic.ZERO,0);long low=1,high=max;
        while(low<=high) {
            long batches=low+(high-low)/2;
            var trial=trial(state,cell,transition,definitions,gravity,model,thermal,stock,unit,batches,temperature,handle,solvent);
            boolean accepted=false;
            if(trial!=null) {
                var next=trial.state();var nextStock=new LiquidCellInventory(next.liquids(),definitions.chemistry()).stock(key);
                var nextSpecies=nextStock.mixture()==null?java.util.Map.<String,Long>of():nextStock.mixture().stableAmounts(definitions.chemistry());
                double nextTemperature=function.stateForInternalEnergy(handle,nextStock.massKilograms(),nextSpecies,nextStock.internalEnergy()).temperatureK();
                var nextGas=next.segments().get(cell).gas();
                double nextPressure=PipeHydraulics.mixture(next.segments().get(cell),next.liquids().rows().get(cell),thermal).absolutePressurePa();
                accepted=model.saturation(transition,nextTemperature,nextGas,nextPressure).direction()!=GasLiquidPhaseModel.Direction.CONDENSE;
            }
            if(accepted) {best=trial;if(batches==high)break;low=batches+1;}else high=batches-1;
        }
        return best;
    }
    private static Result trial(PipeNetworkState state,int cell,ThermalPhaseTransition transition,FluidDefinitions definitions,double gravity,
                                GasLiquidPhaseModel model,GasThermalModel thermal,LiquidCellInventory.Stock stock,
                                mmm.fluid.air.GasComponentAmount unit,long batches,double temperature,int handle,boolean solvent) {
        try {
            long mass=Math.multiplyExact(unit.massUnits(),batches),remainingMass=stock.massUnits()-mass;
            var volume=SignedDyadic.of(Math.multiplyExact(transition.lowerVolumeSvqPerBatch(),batches),-27);
            var remainingVolume=stock.volume().subtract(volume);
            if(remainingVolume.signum()<=0 || remainingMass<=0)return null;
            var selectedSpecies=new TreeMap<String,Long>();var retainedSpecies=new TreeMap<String,Long>();
            if(stock.mixture()!=null)for(var entry:stock.mixture().stableAmounts(definitions.chemistry()).entrySet()) {
                long amount=entry.getKey().equals(transition.speciesId())?Mass.portion(entry.getValue(),mass,stock.massUnits()):0;
                if(amount>0)selectedSpecies.put(entry.getKey(),amount);
                if(amount<entry.getValue())retainedSpecies.put(entry.getKey(),entry.getValue()-amount);
            }
            var selected=composition(selectedSpecies,mass,definitions);var retained=composition(retainedSpecies,remainingMass,definitions);
            double[] energies=LiquidCellInventory.partitionEnergy(stock,new long[]{remainingMass,mass},new ChemicalMixtureRecord[]{retained,selected},PipeThermalBindings.liquid(state,definitions));
            long residual=selected==null?mass:selected.massResidualUnits();
            if(!solvent && residual!=0)return null;
            var vapor=model.gasBatch(transition,batches,selectedSpecies,residual);
            double vaporU=model.internalEnergyAtTemperature(transition,vapor,temperature);
            double liquidU=SignedDyadic.of(energies[0]).add(SignedDyadic.of(energies[1])).subtract(SignedDyadic.of(vaporU)).toDouble();
            var after=new LiquidCellInventory.Stock(stock.key(),remainingVolume,remainingMass,retained,liquidU);
            var function=new ThermalStateFunction(definitions.thermal());
            function.stateForInternalEnergy(handle,after.massKilograms(),retainedSpecies,liquidU);
            var inventory=new LiquidCellInventory(state.liquids(),definitions.chemistry());
            var plan=inventory.planEdits(List.of(new LiquidCellInventory.Edit(stock.key(),after)));
            var segments=new ArrayList<>(state.segments());var old=segments.get(cell);
            var gas=old.gas().merge(vapor);double gasU=SignedDyadic.of(old.gasInternalEnergy()).add(SignedDyadic.of(vaporU)).toDouble();
            thermal.requireInventoryEnergy(gas,gasU);
            segments.set(cell,new PipeSegmentState(old.id(),old.elevationM(),old.capacityM3(),gas,gasU,old.deposits()));
            var candidate=new PipeNetworkState(segments,state.connections(),state.materialIds(),plan.future().snapshot());
            var correction=PipeNetworkSolver.energy(state,definitions,gravity,thermal).subtract(PipeNetworkSolver.energy(candidate,definitions,gravity,thermal));
            var adjusted=new LiquidCellInventory(candidate.liquids(),definitions.chemistry()).planEdits(List.of()).withMechanicalHeat(java.util.Map.of(cell,correction));
            var result=new PipeNetworkState(segments,state.connections(),state.materialIds(),adjusted.future().snapshot());
            var finalStock=new LiquidCellInventory(result.liquids(),definitions.chemistry()).stock(stock.key());
            function.stateForInternalEnergy(handle,finalStock.massKilograms(),retainedSpecies,finalStock.internalEnergy());
            return new Result(result,PipeNetworkSolver.energy(result,definitions,gravity,thermal).subtract(PipeNetworkSolver.energy(state,definitions,gravity,thermal)),batches);
        } catch (ThermalStateFunction.EnergyOutsideValidityException | ThermalStateFunction.EnergyRepresentationException outside) { return null; }
    }
    private static ChemicalMixtureRecord composition(java.util.Map<String,Long> species,long mass,FluidDefinitions definitions) {
        if(species.isEmpty())return null;
        var value=ChemicalMixtureRecord.fromStableAmounts(definitions.chemistry(),species,0);
        return value.withMassResidualUnits(Math.subtractExact(mass,value.speciesMassUnits(definitions.chemistry())));
    }
}
