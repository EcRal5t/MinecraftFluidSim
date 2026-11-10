package mmm.pipe;

import java.util.*;
import mmm.fluid.TestFluidDefinitions;
import mmm.fluid.air.GasInventory;
import mmm.inventory.LiquidCellInventory;
import mmm.thermal.*;
import mmm.units.*;

/** Finite wall energy, material ordering and monotone long-step equilibration. */
public final class PipeWallHeatValidation {
    public static void main(String[] args) {
        var definitions=TestFluidDefinitions.load();var function=new ThermalStateFunction(definitions.thermal());
        int water=definitions.thermal().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water");
        double u=function.internalEnergyAtTemperature(water,250,Map.of(),360);
        var stock=new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,0,255),SignedDyadic.of(.25),Mass.fromKilograms(250),null,u);
        var state=new PipeNetworkState(List.of(new PipeSegmentState("pipe",0,.25,GasInventory.EMPTY,0,List.of())),
                List.of(),List.of("mmm:water"),new LiquidCellInventory.Snapshot(List.of(List.of(stock))));
        double metalLoss=0,insulatedLoss=0;
        for(String material:List.of("mmm:ordinary_metal_wall","mmm:ordinary_insulation_wall","mmm:ordinary_glass_wall")) {
            int handle=definitions.thermal().materialHandle(material);long mass=Mass.fromKilograms(100);
            double wallEnergy=function.internalEnergyAtTemperature(handle,100,Map.of(),290);
            var wall=new PipeWallHeat.Wall(0,material,mass,wallEnergy,2,.03125);
            var result=PipeWallHeat.exchange(state,List.of(wall),.5,definitions);
            double next=result.state().liquids().rows().getFirst().getFirst().internalEnergy();
            double delta=result.wallEnergyDeltas().get(0);
            if(definitions.thermal().material(handle).conductivityWPerMeterK()==0)
                require(next==u && delta==0,"insulating wall exchanged heat");
            else require(next<u && delta>0,"hot liquid heats finite wall");
            require(SignedDyadic.of(next).subtract(SignedDyadic.of(u)).add(SignedDyadic.of(delta)).equals(result.roundoff()),"heat exchange exact roundoff ledger");
            require(state.liquids().rows().getFirst().getFirst()==stock,"source inventory immutable");
            var longStep=PipeWallHeat.exchange(state,List.of(wall),1e9,definitions);
            double fluidT=function.stateForInternalEnergy(water,250,Map.of(),longStep.state().liquids().rows().getFirst().getFirst().internalEnergy()).temperatureK();
            double wallT=function.stateForInternalEnergy(handle,100,Map.of(),wallEnergy+longStep.wallEnergyDeltas().get(0)).temperatureK();
            require(fluidT>=wallT-1e-9 && fluidT<=360 && wallT>=290,"finite owners stay within initial temperatures");
            if(material.contains("metal"))metalLoss=u-next;
            if(material.contains("insulation"))insulatedLoss=u-next;
        }
        require(metalLoss>insulatedLoss*5,"insulation reduces cavity heat loss");
        mixedElasticEnergy(definitions,function,water);
        System.out.println("Pipe finite wall heat, material ordering and long-step conservation passed");
    }
    private static void mixedElasticEnergy(mmm.fluid.FluidDefinitions definitions,ThermalStateFunction function,int water) {
        var gas=definitions.gas().createAmbient(250_000_000L);var gasModel=new GasThermalModel(definitions.thermal(),definitions.gas());
        double gasEnergy=gasModel.internalEnergyAtTemperature(gas,360);
        double liquidEnergy=function.internalEnergyAtTemperature(water,240,Map.of(),360);
        var stock=new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,0,255),SignedDyadic.of(.24),Mass.fromKilograms(240),null,liquidEnergy);
        var segment=new PipeSegmentState("mixed",0,.25,gas,gasEnergy,List.of());
        var state=new PipeNetworkState(List.of(segment),List.of(),List.of("mmm:water"),new LiquidCellInventory.Snapshot(List.of(List.of(stock))));
        int material=definitions.thermal().materialHandle("mmm:ordinary_metal_wall");long mass=Mass.fromKilograms(100);
        double wallEnergy=function.internalEnergyAtTemperature(material,100,Map.of(),290);
        var result=PipeWallHeat.exchange(state,List.of(new PipeWallHeat.Wall(0,"mmm:ordinary_metal_wall",mass,wallEnergy,2,.03125)),10,definitions);
        double before=PipeHydraulics.mixture(segment,state.liquids().rows().getFirst(),gasModel).liquidElasticEnergyJ();
        double after=PipeHydraulics.mixture(result.state().segments().getFirst(),result.state().liquids().rows().getFirst(),gasModel).liquidElasticEnergyJ();
        require(Math.abs(after-before)>1e-8,"mixed fixture must change elastic energy");
        var total=PipeNetworkSolver.energy(result.state(),definitions,0).subtract(PipeNetworkSolver.energy(state,definitions,0))
                .add(SignedDyadic.of(result.wallEnergyDeltas().get(0)));
        require(total.equals(result.roundoff()) && Math.abs(total.toDouble())<1e-5,"wall, gas/liquid U and elastic energy did not close");
        var heating=PipeWallHeat.addSegmentHeat(state,0,SignedDyadic.of(100),definitions);
        var heatBalance=PipeNetworkSolver.energy(heating.state(),definitions,0).subtract(PipeNetworkSolver.energy(state,definitions,0))
                .subtract(SignedDyadic.of(100));
        require(heatBalance.equals(heating.roundoff()) && Math.abs(heatBalance.toDouble())<1e-5,
                "mechanical heat and elastic energy did not close");
    }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
