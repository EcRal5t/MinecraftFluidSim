package mmm.pipe;

import java.util.List;
import mmm.fluid.air.GasInventory;
import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;

/** Pipe junction transport preserves each original donor and its carried energy. */
public final class PipeLiquidTransportValidation {
    public static void main(String[] args) {
        var key = new LiquidCellInventory.Key(0,0,255);
        var source = new LiquidCellInventory.Stock(key,SignedDyadic.of(.75),1200,null,2400);
        var segments = List.of(segment("a"),segment("b"),segment("c"));
        var edges = List.of(new PipeConnection(0,1,1,.1,0,1,0),new PipeConnection(0,2,1,.1,0,1,0));
        var liquids = new LiquidCellInventory.Snapshot(List.of(List.of(source),List.of(),List.of()));
        var before = new PipeNetworkState(segments,edges,List.of("mmm:water"),liquids);
        var after = PipeLiquidTransport.route(before,List.of(
                new PipeLiquidTransport.Move(0,key,1,SignedDyadic.of(.25)),
                new PipeLiquidTransport.Move(1,key,2,SignedDyadic.of(.125))),null,null).state();
        var inventory = new LiquidCellInventory(after.liquids(),null);
        require(inventory.exactVolume().equals(SignedDyadic.of(.75)),"volume conservation");
        require(inventory.massUnits()==1200,"mass conservation");
        require(inventory.exactInternalEnergy().equals(SignedDyadic.of(2400)),"energy conservation");
        require(inventory.exactCellVolume(0).equals(SignedDyadic.of(.375)),"donor remainder");
        require(inventory.exactCellVolume(1).equals(SignedDyadic.of(.25)),"first branch");
        require(inventory.exactCellVolume(2).equals(SignedDyadic.of(.125)),"second branch");
        require(before.liquids().rows().get(0).getFirst()==source && before.liquids().rows().get(1).isEmpty(),"input changed");
        try {
            var closed = new PipeNetworkState(segments,List.of(new PipeConnection(0,1,1,.1,0,0,0)),before.materialIds(),liquids);
            PipeLiquidTransport.route(closed,List.of(new PipeLiquidTransport.Move(0,key,1,SignedDyadic.of(.25))),null,null);
            throw new AssertionError("closed pipe accepted transport");
        } catch (IllegalArgumentException expected) { }
        bedStep();
        bedTransport();
        frozenPhase();
        chemicalCondensation();
        vaporization();
        condensation();
        branchStep();
        liquidGasStep();
        gasStep();
        hydraulics();
        mixedJunction();
        gasJunction();
        System.out.println("Pipe liquid and gas junction inventory and energy passed");
    }
    private static void bedStep() {
        var defs=mmm.fluid.TestFluidDefinitions.load();var gasThermal=new mmm.thermal.GasThermalModel(defs.thermal(),defs.gas());
        var thermal=new mmm.thermal.ThermalStateFunction(defs.thermal());
        String sand=mmm.granular.BuiltinGranularDefinitions.FINE_SAND;
        int gh=defs.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.GRANULAR,sand);
        int lh=defs.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water");
        var bed=new mmm.granular.GranularTransferLedger.Portion(new mmm.granular.GranularTransferLedger.Group(sand,1000,mmm.units.Mass.fromKilograms(.01),null),
                thermal.internalEnergyAtTemperature(gh,.01,java.util.Map.of(),300));
        var gasA=gasThermal.inventoryAtPressure(.5,180000,300,defs.gas().ambient());
        var gasB=gasThermal.inventoryAtPressure(.5,100000,300,defs.gas().ambient());
        var segments=List.of(new PipeSegmentState("upstream",0,1,gasA,gasThermal.internalEnergyAtTemperature(gasA,300),List.of(bed)),
                new PipeSegmentState("downstream",0,1,gasB,gasThermal.internalEnergyAtTemperature(gasB,300),List.of()));
        double u=thermal.internalEnergyAtTemperature(lh,500,java.util.Map.of(),300);
        var row0=List.of(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,0,255),SignedDyadic.of(.5),mmm.units.Mass.fromKilograms(500),null,u));
        var row1=List.of(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(1,0,255),SignedDyadic.of(.5),mmm.units.Mass.fromKilograms(500),null,u));
        var state=new PipeNetworkState(segments,List.of(new PipeConnection(0,1,1,.1,0,1,0)),List.of("mmm:water"),new LiquidCellInventory.Snapshot(List.of(row0,row1)));
        var result=PipeNetworkSolver.step(state,.05,defs,9.81);
        require(!result.state().segments().get(1).deposits().isEmpty(),"pressure-driven flow transports bed");
        long volume=0,mass=0;for(var segment:result.state().segments())for(var item:segment.deposits()) {
            volume+=item.inventory().solidVolumeSvq();mass+=item.inventory().massUnits();
        }
        require(volume==1000 && mass==bed.inventory().massUnits(),"step bed conservation");
        require(PipeNetworkSolver.energy(result.state(),defs,9.81).subtract(PipeNetworkSolver.energy(state,defs,9.81)).equals(result.energyRoundoff()),"step bed energy account");
    }
    private static void bedTransport() {
        var defs=mmm.fluid.TestFluidDefinitions.load();var function=new mmm.thermal.ThermalStateFunction(defs.thermal());
        String sand=mmm.granular.BuiltinGranularDefinitions.FINE_SAND;
        int thermal=defs.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.GRANULAR,sand);
        long solid=1_000_000,mass=mmm.units.Mass.fromKilograms(5);
        var deposit=new mmm.granular.GranularTransferLedger.Portion(new mmm.granular.GranularTransferLedger.Group(sand,solid,mass,null),
                function.internalEnergyAtTemperature(thermal,5,java.util.Map.of(),300));
        var source=new PipeSegmentState("source",0,1,GasInventory.EMPTY,0,List.of(deposit));
        var target=segment("target");
        var liquid=new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,0,255),SignedDyadic.of(.5),mmm.units.Mass.fromKilograms(500),null,0);
        var rows=new LiquidCellInventory.Snapshot(List.of(List.of(liquid),List.of()));
        var edge=new PipeConnection(0,1,1,.1,0,1,0);
        var state=new PipeNetworkState(List.of(source,target),List.of(edge),List.of("mmm:water"),rows);
        var move=new PipeBedTransport.Move(0,0,1,.05,.05,1000,.001);
        var result=PipeBedTransport.route(state,state,List.of(move),defs,9.81);
        require(!result.segments().get(1).deposits().isEmpty(),"flow mobilizes bed");
        long volume=0,totalMass=0;var energy=SignedDyadic.ZERO;
        for(var segment:result.segments())for(var item:segment.deposits()) {
            volume+=item.inventory().solidVolumeSvq();totalMass+=item.inventory().massUnits();energy=energy.add(SignedDyadic.of(item.internalEnergy()));
        }
        require(volume==solid && totalMass==mass,"bed volume and mass conserved");
        require(Math.abs(energy.subtract(SignedDyadic.of(deposit.internalEnergy())).toDouble())<1e-6,"bed carried energy");
        var screen=new PipeNetworkState(state.segments(),List.of(new PipeConnection(0,1,1,.1,0,1,.0001)),state.materialIds(),rows);
        require(PipeBedTransport.route(screen,screen,List.of(move),defs,9.81)==screen,"fine sieve retains upstream bed");
        var slow=new PipeBedTransport.Move(0,0,1,1e-12,.05,1000,.001);
        require(PipeBedTransport.route(state,state,List.of(slow),defs,9.81)==state,"subcritical shear leaves bed");
        var species=java.util.Map.of("mmm:na_plus",64000L,"mmm:cl_minus",64000L);
        var composition=mmm.granular.SolidCompositionRecord.fromStableAmounts(defs.chemistry(),species,0);
        composition=mmm.granular.SolidCompositionRecord.fromStableAmounts(defs.chemistry(),species,mass-composition.speciesMassUnits(defs.chemistry()));
        String reactive=mmm.granular.BuiltinGranularDefinitions.PRECIPITATE;
        int reactiveThermal=defs.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.GRANULAR,reactive);
        var chemical=new mmm.granular.GranularTransferLedger.Portion(new mmm.granular.GranularTransferLedger.Group(reactive,solid,mass,composition),
                function.internalEnergyAtTemperature(reactiveThermal,5,species,300));
        var chemicalSource=new PipeSegmentState("source",0,1,GasInventory.EMPTY,0,List.of(chemical));
        var chemicalState=new PipeNetworkState(List.of(chemicalSource,target),List.of(edge),state.materialIds(),rows);
        var chemicalResult=PipeBedTransport.route(chemicalState,chemicalState,List.of(move),defs,9.81);
        var totals=new java.util.TreeMap<String,Long>();long chemicalMass=0,chemicalVolume=0;
        for(var segment:chemicalResult.segments())for(var item:segment.deposits()) {
            chemicalMass+=item.inventory().massUnits();chemicalVolume+=item.inventory().solidVolumeSvq();
            item.inventory().composition().stableAmounts(defs.chemistry()).forEach((id,n)->totals.merge(id,n,Math::addExact));
        }
        require(chemicalResult.segments().get(1).deposits().size()==1,"reactive bed travels");
        require(chemicalMass==mass && chemicalVolume==solid && totals.equals(species),"reactive bed species conservation");
    }
    private static void frozenPhase() {
        var defs=mmm.fluid.TestFluidDefinitions.load();var transition=defs.thermal().transitions().stream()
                .filter(t -> t.id().equals("mmm:water_vapor")).findFirst().orElseThrow();
        var phase=new mmm.thermal.GasLiquidPhaseModel(defs.thermal(),defs.gas());
        var thermal=new mmm.thermal.GasThermalModel(defs.thermal(),defs.gas());
        var gas=phase.gasBatch(transition,1000).merge(thermal.inventoryAtPressure(.2,101325,300,defs.gas().ambient()));
        double u=thermal.internalEnergyAtTemperature(gas,300);
        var segments=List.of(new PipeSegmentState("frozen",0,1,gas,u,List.of()),new PipeSegmentState("loaded",0,1,gas,u,List.of()));
        var state=new PipeNetworkState(segments,List.of(new PipeConnection(0,1,1,.1,0,1,0)),List.of(),
                new LiquidCellInventory.Snapshot(List.of(List.of(),List.of())));
        var next=PipeNetworkSolver.step(state,.05,defs,9.81,java.util.Set.of("frozen")).state();
        require(next.segments().getFirst().equals(segments.getFirst()),"frozen gas and U remain unchanged");
        require(next.liquids().rows().getFirst().isEmpty(),"frozen vapor does not condense");
        require(!next.liquids().rows().get(1).isEmpty(),"loaded vapor condenses");
    }
    private static void chemicalCondensation() {
        var defs=mmm.fluid.TestFluidDefinitions.load();var transition=defs.thermal().transitions().stream()
                .filter(t -> t.id().equals("mmm:water_vapor")).findFirst().orElseThrow();
        var phase=new mmm.thermal.GasLiquidPhaseModel(defs.thermal(),defs.gas());
        var thermal=new mmm.thermal.GasThermalModel(defs.thermal(),defs.gas());
        var vapor=phase.gasBatch(transition,1000,java.util.Map.of("mmm:h2o",50_000_000L),1_000_000_000L);
        var gas=vapor.merge(thermal.inventoryAtPressure(.2,101325,300,defs.gas().ambient()));
        var state=new PipeNetworkState(List.of(new PipeSegmentState("chemical",0,1,gas,thermal.internalEnergyAtTemperature(gas,300),List.of())),
                List.of(),List.of(),new LiquidCellInventory.Snapshot(List.of(List.of())));
        var result=PipeCondensation.condense(state,0,transition,defs,9.81);
        require(result.batches()>0,"chemical vapor condenses");
        long original=species(gas,"mmm:h2o"),remaining=species(result.state().segments().getFirst().gas(),"mmm:h2o"),liquid=0;
        for(var stock:result.state().liquids().rows().getFirst())if(stock.mixture()!=null)
            liquid+=stock.mixture().amount(defs.chemistry().speciesHandle("mmm:h2o"));
        require(original==remaining+liquid,"condensation conserves molecular species");
        require(PipeNetworkSolver.energy(result.state(),defs,9.81).subtract(PipeNetworkSolver.energy(state,defs,9.81)).equals(result.energyRoundoff()),"chemical condensation energy account");
    }
    private static long species(GasInventory gas,String id) {
        long total=0;for(var component:gas.components())for(var amount:component.species())if(amount.speciesId().equals(id))total+=amount.amountQuanta();return total;
    }
    private static void vaporization() {
        var defs=mmm.fluid.TestFluidDefinitions.load();var thermal=new mmm.thermal.GasThermalModel(defs.thermal(),defs.gas());
        var function=new mmm.thermal.ThermalStateFunction(defs.thermal());
        var transition=defs.thermal().transitions().stream().filter(t -> t.id().equals("mmm:water_vapor")).findFirst().orElseThrow();
        var gas=thermal.inventoryAtPressure(.5,101325,400,defs.gas().ambient());
        int material=defs.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water");
        var stock=new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,0,255),SignedDyadic.of(.5),mmm.units.Mass.fromKilograms(500),null,
                function.internalEnergyAtTemperature(material,500,java.util.Map.of(),400));
        var state=new PipeNetworkState(List.of(new PipeSegmentState("hot",0,1,gas,thermal.internalEnergyAtTemperature(gas,400),List.of())),
                List.of(),List.of("mmm:water"),new LiquidCellInventory.Snapshot(List.of(List.of(stock))));
        var result=PipeVaporization.boil(state,0,transition,defs,9.81);
        require(result.batches()>0,"hot liquid vaporizes");
        long massBefore=gas.totalMassUnits()+stock.massUnits();
        require(result.state().segments().getFirst().gas().totalMassUnits()+new LiquidCellInventory(result.state().liquids(),defs.chemistry()).massUnits()==massBefore,"vaporization mass conservation");
        require(PipeNetworkSolver.energy(result.state(),defs,9.81).subtract(PipeNetworkSolver.energy(state,defs,9.81)).equals(result.energyRoundoff()),"vaporization energy account");
    }
    private static void condensation() {
        var defs=mmm.fluid.TestFluidDefinitions.load();
        var transition=defs.thermal().transitions().stream().filter(t -> t.id().equals("mmm:water_vapor")).findFirst().orElseThrow();
        var phase=new mmm.thermal.GasLiquidPhaseModel(defs.thermal(),defs.gas());
        var thermal=new mmm.thermal.GasThermalModel(defs.thermal(),defs.gas());
        var gas=phase.gasBatch(transition,1000).merge(thermal.inventoryAtPressure(.2,101325,300,defs.gas().ambient()));
        var state=new PipeNetworkState(List.of(new PipeSegmentState("cold",0,1,gas,thermal.internalEnergyAtTemperature(gas,300),List.of())),
                List.of(),List.of(),new LiquidCellInventory.Snapshot(List.of(List.of())));
        var before=PipeNetworkSolver.energy(state,defs,9.81);
        var result=PipeCondensation.condense(state,0,transition,defs,9.81);
        require(result.batches()>0,"supersaturated vapor condenses");
        var step=PipeNetworkSolver.step(state,.05,defs,9.81);
        require(!step.state().liquids().rows().getFirst().isEmpty(),"pipe step condenses vapor at rest");
        require(PipeNetworkSolver.energy(step.state(),defs,9.81).subtract(before).equals(step.energyRoundoff()),"step condensation energy account");
        require(!result.state().liquids().rows().getFirst().isEmpty(),"condensation creates liquid inventory");
        long gasBefore=gas.totalMassUnits(),gasAfter=result.state().segments().getFirst().gas().totalMassUnits();
        long liquidMass=new LiquidCellInventory(result.state().liquids(),defs.chemistry()).massUnits();
        require(gasBefore==gasAfter+liquidMass,"condensation mass conservation");
        require(PipeNetworkSolver.energy(result.state(),defs,9.81).subtract(before).equals(result.energyRoundoff()),"condensation energy account");
    }
    private static void branchStep() {
        var defs=mmm.fluid.TestFluidDefinitions.load();var thermal=new mmm.thermal.GasThermalModel(defs.thermal(),defs.gas());
        var segments=new java.util.ArrayList<PipeSegmentState>();var edges=new java.util.ArrayList<PipeConnection>();
        var rows=new java.util.ArrayList<List<LiquidCellInventory.Stock>>();
        for(int i=0;i<6;i++) {
            var gas=thermal.inventoryAtPressure(1,i==0?120000:100000,300,defs.gas().ambient());
            segments.add(new PipeSegmentState("segment"+i,0,1,gas,thermal.internalEnergyAtTemperature(gas,300),List.of()));
            rows.add(List.of());if(i>0)edges.add(new PipeConnection(0,i,1,.1,0,1,0));
        }
        var state=new PipeNetworkState(segments,edges,List.of(),new LiquidCellInventory.Snapshot(rows));
        for(int step=0;step<5;step++) {
            state=PipeNetworkSolver.step(state,100,defs,9.81).state();
            double source=PipeHydraulics.mixture(state.segments().getFirst(),List.of(),thermal).absolutePressurePa();
            for(int i=1;i<6;i++) {
                double target=PipeHydraulics.mixture(state.segments().get(i),List.of(),thermal).absolutePressurePa();
                require(source>=target && target>=99999,"large-step junction stays within pressure range");
            }
        }
    }
    private static void liquidGasStep() {
        var defs=mmm.fluid.TestFluidDefinitions.load();var gasThermal=new mmm.thermal.GasThermalModel(defs.thermal(),defs.gas());
        var thermal=new mmm.thermal.ThermalStateFunction(defs.thermal());
        int handle=defs.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water");
        var a=gasThermal.inventoryAtPressure(.5,120000,300,defs.gas().ambient());
        var b=gasThermal.inventoryAtPressure(.5,100000,300,defs.gas().ambient());
        double liquidU=thermal.internalEnergyAtTemperature(handle,500,java.util.Map.of(),300);
        var segments=List.of(new PipeSegmentState("a",0,1,a,gasThermal.internalEnergyAtTemperature(a,300),List.of()),
                new PipeSegmentState("b",0,1,b,gasThermal.internalEnergyAtTemperature(b,300),List.of()));
        var stock0=new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,0,255),SignedDyadic.of(.5),mmm.units.Mass.fromKilograms(500),null,liquidU);
        var stock1=new LiquidCellInventory.Stock(new LiquidCellInventory.Key(1,0,255),SignedDyadic.of(.5),mmm.units.Mass.fromKilograms(500),null,liquidU);
        var state=new PipeNetworkState(segments,List.of(new PipeConnection(0,1,1,.1,0,1,0)),List.of("mmm:water"),
                new LiquidCellInventory.Snapshot(List.of(List.of(stock0),List.of(stock1))));
        double previous=PipeHydraulics.mixture(segments.get(0),state.liquids().rows().get(0),gasThermal).absolutePressurePa()
                -PipeHydraulics.mixture(segments.get(1),state.liquids().rows().get(1),gasThermal).absolutePressurePa();
        double initial=previous;
        long mass=totalMass(state);
        for(int i=0;i<8;i++) {
            var beforeEnergy=networkEnergy(state,gasThermal,9.81);
            var result=PipeNetworkSolver.step(state,.05,defs,9.81);state=result.state();
            require(networkEnergy(state,gasThermal,9.81).subtract(beforeEnergy).equals(result.energyRoundoff()),"step energy audit");
            double next=PipeHydraulics.mixture(state.segments().get(0),state.liquids().rows().get(0),gasThermal).absolutePressurePa()
                    -PipeHydraulics.mixture(state.segments().get(1),state.liquids().rows().get(1),gasThermal).absolutePressurePa();
            require(next>=0 && next<initial,"mixed phase pressure remains within initial range");
            require(totalMass(state)==mass,"step mass conservation");
            previous=next;
        }
        require(previous<initial*.5,"mixed phase pressure difference relaxes");
    }
    private static long totalMass(PipeNetworkState state) {
        long total=new LiquidCellInventory(state.liquids(),null).massUnits();
        for(var segment:state.segments())total=Math.addExact(total,segment.gas().totalMassUnits());
        return total;
    }
    private static SignedDyadic networkEnergy(PipeNetworkState state,mmm.thermal.GasThermalModel thermal,double gravity) {
        var energy=SignedDyadic.ZERO;
        for(int i=0;i<state.segments().size();i++) {
            var segment=state.segments().get(i);double mass=segment.gas().totalMass();
            energy=energy.add(SignedDyadic.of(segment.gasInternalEnergy()));
            for(var d:segment.deposits()) {mass+=d.inventory().mass();energy=energy.add(SignedDyadic.of(d.internalEnergy()));}
            for(var stock:state.liquids().rows().get(i)) {mass+=stock.massKilograms();energy=energy.add(SignedDyadic.of(stock.internalEnergy()));}
            energy=energy.add(SignedDyadic.of(mass*gravity*segment.elevationM()));
            energy=energy.add(SignedDyadic.of(PipeHydraulics.mixture(segment,state.liquids().rows().get(i),thermal).liquidElasticEnergyJ()));
        }
        return energy;
    }
    private static void gasStep() {
        var defs=mmm.fluid.TestFluidDefinitions.load();var thermal=new mmm.thermal.GasThermalModel(defs.thermal(),defs.gas());
        var high=thermal.inventoryAtPressure(1,120000,300,defs.gas().ambient());
        var low=thermal.inventoryAtPressure(1,100000,300,defs.gas().ambient());
        double u0=thermal.internalEnergyAtTemperature(high,300),u1=thermal.internalEnergyAtTemperature(low,300);
        var segments=List.of(new PipeSegmentState("high",0,1,high,u0,List.of()),new PipeSegmentState("low",0,1,low,u1,List.of()));
        var state=new PipeNetworkState(segments,List.of(new PipeConnection(0,1,1,.1,0,1,0)),List.of(),new LiquidCellInventory.Snapshot(List.of(List.of(),List.of())));
        var result=PipeNetworkSolver.step(state,.05,defs,9.81);
        var next=result.state();
        require(next.segments().get(0).gas().standardVolumeNanos()<high.standardVolumeNanos(),"gas follows pressure");
        require(next.segments().get(1).gas().standardVolumeNanos()>low.standardVolumeNanos(),"gas enters low pressure segment");
        var merged=next.segments().get(0).gas().merge(next.segments().get(1).gas());
        require(merged.components().equals(high.merge(low).components()),"step gas component conservation");
        var delta=SignedDyadic.of(next.segments().get(0).gasInternalEnergy()).add(SignedDyadic.of(next.segments().get(1).gasInternalEnergy()))
                .subtract(SignedDyadic.of(u0)).subtract(SignedDyadic.of(u1));
        require(delta.equals(result.energyRoundoff()),"step gas energy accounting");
    }
    private static void hydraulics() {
        var registry=mmm.fluid.TestFluidDefinitions.load().granular();
        var a=segment("a");var b=segment("b");
        var edge=new PipeConnection(0,1,1,.1,0,1,0);
        double resistance=PipeHydraulics.resistance(edge,a,b,.001,registry);
        require(Math.abs(resistance-8*Math.PI*.001/.01)<1e-12,"Poiseuille resistance");
        require(PipeHydraulics.resistance(new PipeConnection(0,1,1,.1,0,0,0),a,b,.001,registry)==Double.POSITIVE_INFINITY,"closed valve resistance");
        var high=new mmm.thermal.MixtureVolumeModel.State(110000,1,0,1,1000,0,0);
        var low=new mmm.thermal.MixtureVolumeModel.State(100000,1,0,1,1000,0,0);
        require(PipeHydraulics.liquidFlow(edge,a,b,high,low,.001,9.81,registry)>0,"pressure flow direction");
        var hill=new PipeConnection(0,1,1,.1,2,1,0);
        require(PipeHydraulics.liquidFlow(hill,a,b,high,low,.001,9.81,registry)<0,"hydrostatic head direction");
        var deposit=new mmm.granular.GranularTransferLedger.Portion(new mmm.granular.GranularTransferLedger.Group(
                mmm.granular.BuiltinGranularDefinitions.FINE_SILT,30000000,1000,null),0);
        var bed=new PipeSegmentState("bed",0,1,GasInventory.EMPTY,0,List.of(deposit));
        require(PipeHydraulics.resistance(edge,bed,bed,.001,registry)>resistance,"bed increases resistance");
        require(PipeHydraulics.resistance(hill,bed,bed,.001,registry)>PipeHydraulics.resistance(edge,bed,bed,.001,registry),"vertical bed serial resistance");
    }
    private static void mixedJunction() {
        var first=new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,0,255),SignedDyadic.of(.5),800,null,1600);
        var second=new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,1,255),SignedDyadic.of(.25),400,null,400);
        var network=new PipeNetworkState(List.of(segment("a"),segment("b")),
                List.of(new PipeConnection(0,1,1,.1,0,1,0)),List.of("mmm:water","mmm:oil"),
                new LiquidCellInventory.Snapshot(List.of(List.of(first,second),List.of())));
        var result=PipeLiquidTransport.routeVolumes(network,List.of(new PipeLiquidTransport.VolumeMove(0,0,1,SignedDyadic.of(.375))),null,null);
        var inventory=new LiquidCellInventory(result.state().liquids(),null);
        require(inventory.massUnits()==1200 && inventory.exactVolume().equals(SignedDyadic.of(.75)),"mixed inventory conservation");
        require(inventory.exactInternalEnergy().subtract(SignedDyadic.of(2000)).equals(result.energyRoundoff()),"mixed energy account");
        require(inventory.stocksInCell(1).size()==2,"both materials travel across junction");
    }
    private static void gasJunction() {
        var defs=mmm.fluid.TestFluidDefinitions.load();
        var thermal=new mmm.thermal.GasThermalModel(defs.thermal(),defs.gas());
        var gas=thermal.inventoryAtPressure(1,120000,300,defs.gas().ambient());
        double energy=thermal.internalEnergyAtTemperature(gas,300);
        var segments=List.of(new PipeSegmentState("a",0,1,gas,energy,List.of()),segment("b"),segment("c"));
        var edges=List.of(new PipeConnection(0,1,1,.1,0,1,0),new PipeConnection(0,2,1,.1,0,1,0));
        var state=new PipeNetworkState(segments,edges,List.of(),new LiquidCellInventory.Snapshot(List.of(List.of(),List.of(),List.of())));
        long quarter=gas.standardVolumeNanos()/4;
        var result=PipeGasTransport.route(state,List.of(new PipeGasTransport.Move(0,0,1,quarter),
                new PipeGasTransport.Move(1,0,2,quarter)),defs.gas(),defs.chemistry(),thermal);
        GasInventory total=GasInventory.EMPTY;
        SignedDyadic totalEnergy=SignedDyadic.ZERO;
        for(var segment:result.state().segments()) {
            total=total.merge(segment.gas()); totalEnergy=totalEnergy.add(SignedDyadic.of(segment.gasInternalEnergy()));
        }
        require(total.components().equals(gas.components()),"gas components and mass conservation");
        require(totalEnergy.subtract(SignedDyadic.of(energy)).equals(result.energyRoundoff()),"gas energy rounding account");
        require(state.segments().getFirst().gas().equals(gas),"gas donor changed");
        var work=PipeGasWork.apply(state,new double[]{.5,0,0},thermal);
        require(work.gasWork().signum()<0,"expanding gas performs work");
        require(SignedDyadic.of(work.state().segments().getFirst().gasInternalEnergy()).subtract(SignedDyadic.of(energy))
                .equals(work.gasWork()),"gas work accounting");
    }
    private static PipeSegmentState segment(String id) { return new PipeSegmentState(id,0,1,GasInventory.EMPTY,0,List.of()); }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
