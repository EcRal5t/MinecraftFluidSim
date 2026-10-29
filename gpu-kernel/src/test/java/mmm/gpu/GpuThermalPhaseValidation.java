package mmm.gpu;

import java.nio.file.*;
import java.util.*;
import mmm.chemistry.*;
import mmm.fluid.*;
import mmm.fluid.air.*;
import mmm.granular.*;
import mmm.thermal.*;

/** Normal thermal conversion through the production resident backend and checkpoint. */
public final class GpuThermalPhaseValidation {
    public static void main(String[] args) throws Exception {
        int device=Integer.parseInt(args[0]);Path definitions=Path.of(args[1]);
        var chemistry=BuiltinChemicalDefinitions.REGISTRY;var granular=BuiltinGranularDefinitions.createRegistry();var gas=BuiltinGasDefinitions.createRegistry();
        var thermal=new ThermalRegistry(read(definitions,"thermal_materials",ThermalDefinitionCodec::decodeMaterial),
            read(definitions,"thermal_species",ThermalDefinitionCodec::decodeSpecies),read(definitions,"phase_transitions",ThermalDefinitionCodec::decodeTransition),
            List.of(),List.of(),chemistry.fingerprint(),granular.fingerprint(),gas.fingerprint());
        try(var backend=new VulkanFluidSolverBackend(device)) {
            for(String mode:(args.length>2?List.of(args[2].split(",")):List.of("freeze","melt","boil","condense","dissolve","outgas","ambient-outgas")))run(backend,mode,thermal,gas);
        }
    }
    private static void run(VulkanFluidSolverBackend backend,String mode,ThermalRegistry thermal,GasRegistry gas) {
        var domain=new HostFluidState(4,4,4,new FluidSolverConfig(.5f,1000,0,.05f,200,1e-5f,.002f));
        boolean ambient=mode.equals("ambient-outgas");
        var fluids=new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(BuiltinFluidMaterials.createRegistry());if(!ambient)domain.sealDomainBoundary();
        domain.setVolume(1,1,1,mode.equals("melt")?.001f:.25f);
        domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);domain.bindGasRegistry(gas);
        boolean film=mode.equals("dissolve")||mode.equals("outgas")||ambient;
        if(film)domain.setMarkerChemical(0,!mode.equals("dissolve")?Map.of(BuiltinChemicalDefinitions.H2O,1_000_000_000L,BuiltinChemicalDefinitions.CO2,100_000_000L):Map.of(BuiltinChemicalDefinitions.H2O,1_000_000_000L));
        double temperature=film?298.15:switch(mode){case "freeze"->250;case "melt"->350;case "boil"->420;default->280;};
        domain.bindThermal(thermal,fluids,temperature);
        if(mode.equals("melt")){
            long batches=1000,mass=mmm.units.Mass.exactUnits(java.math.BigInteger.ONE,java.math.BigInteger.ONE);
            double energy=new ThermalStateFunction(thermal).internalEnergyAtTemperature(thermal.materialHandle("mmm:ice"),1,Map.of(),temperature);
            domain.addCoherentPhaseExternal(1,1,1,"mmm:ice",146*batches,mass,null,energy);
        }
        if(film){
            int node=domain.gasNodeAt(.75,.95,.75);if(ambient?node!=-1:node<1)throw new AssertionError("Unexpected film contact gas node: "+node);
            if(mode.equals("dissolve"))domain.editGas(node,"mmm:co2",1_000_000_000L,temperature,false);
        }
        if(mode.equals("condense")){
            int node=domain.gasNodeAt(.75,.95,.75);if(node<1)throw new AssertionError("No finite chamber for condensation");
            domain.editGas(node,"mmm:water_vapor",1_000_000_000L,temperature,false);
        }
        FluidStateAccess current=domain.captureAuthoritativeState();
        try {
            try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(0,current,false,0));var prepared=candidate.claimPreparedDeviceState()){
                var next=prepared.commit();current.close();current=next;
            }
            long expectedMass=mass(((ResidentFluidState)current).inventorySummary());
            var initialCheckpoint=film?current.exportState():null;
            long initialLiquidCo2=film?liquidSpecies(initialCheckpoint,BuiltinChemicalDefinitions.CO2):0;
            if(ambient)expectedMass=accountedMass(current);
            var initialSpecies=film?accountedSpecies(initialCheckpoint,ambient):Map.<String,Long>of();
            int operations=0;
            for(int step=1;step<=2;step++){
                try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(step,current,!film,film?0:ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));var prepared=candidate.claimPreparedDeviceState()){
                    var stats=candidate.stats();
                    if(film){if(stats.massTransfer().active()&&stats.massTransfer().changedStocks())operations++;}
                    else operations+=switch(mode){case "freeze"->stats.condensedPhase().frozenCells();case "melt"->stats.condensedPhase().meltedCells();case "boil"->stats.gasPhase().boiledCells();default->stats.gasPhase().condensedCells();};
                    var next=prepared.commit();current.close();current=next;
                }
                check(current,expectedMass,ambient);
            }
            if(film){
                var after=current.exportState();long delta=liquidSpecies(after,BuiltinChemicalDefinitions.CO2)-initialLiquidCo2;
                if(mode.equals("dissolve")?delta<=0:delta>=0)throw new AssertionError(mode+" did not transfer CO2 in the expected direction");
                if(!accountedSpecies(after,ambient).equals(initialSpecies)||!HostFluidState.restoreAuthoritativeState(after).gasBoundary().auditClosed())throw new AssertionError(mode+" species/gas audit failed");
            }
            if(operations==0)throw new AssertionError(mode+" did not convert any inventory");
            try(var candidate=backend.evaluate(new FluidStepRequest(3,current,List.of(),false,0,false,true));var prepared=candidate.claimPreparedDeviceState()){
                var next=prepared.commit();current.close();current=next;
            }
            check(current,expectedMass,ambient);
            var checkpoint=current.exportState();var restored=HostFluidState.restoreAuthoritativeState(checkpoint);
            if((mode.equals("boil")||mode.equals("outgas"))&&checkpoint.gas().phaseSource().isEmpty())
                throw new AssertionError(mode+" did not publish its finite gas phase source");
            if((mode.equals("condense")||mode.equals("dissolve"))&&checkpoint.gas().phaseSink().isEmpty())
                throw new AssertionError(mode+" did not publish its finite gas phase sink");
            if(film&&(!accountedSpecies(checkpoint,ambient).equals(initialSpecies)||!restored.gasBoundary().auditClosed()))throw new AssertionError(mode+" continuation species/gas audit failed: initial="+initialSpecies+", actual="+accountedSpecies(checkpoint,ambient)+", gasClosed="+restored.gasBoundary().auditClosed()+", gas="+checkpoint.gas().nodes());
            if(!restored.thermalAudit().acceptsInventory(restored.exactInternalEnergy()))throw new AssertionError(mode+" checkpoint audit failed");
            if(ambient){
                if(checkpoint.chemicalSink().isEmpty())throw new AssertionError("Ambient outgas did not post an external chemical sink");
                var resumed=restored.captureAuthoritativeState();
                try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(4,resumed,false,0));var prepared=candidate.claimPreparedDeviceState()){
                    var next=prepared.commit();current.close();current=next;
                }finally{resumed.close();}
                check(current,expectedMass,true);
                var continued=current.exportState();
                if(!accountedSpecies(continued,true).equals(initialSpecies))throw new AssertionError("Restored ambient transfer repeated or lost chemical postings");
                var continuedDomain=HostFluidState.restoreAuthoritativeState(continued);
                if(!continuedDomain.gasBoundary().auditClosed()||!continuedDomain.thermalAudit().acceptsInventory(continuedDomain.exactInternalEnergy()))throw new AssertionError("Restored ambient transfer audit failed");
            }
            ((ResidentFluidState)current).display();
            System.out.println("GPU thermal phase "+mode+": active_cells="+operations+" exact_mass=true audit_closed=true mechanical_continuation=true checkpoint=true");
        }finally{current.close();}
    }
    private static long liquidSpecies(FluidDomainState state,String id){
        long total=0;for(var stock:state.liquid().stocks())if(stock.mixture()!=null)total=Math.addExact(total,stock.mixture().stableAmounts(state.chemicalRegistry()).getOrDefault(id,0L));return total;
    }
    private static Map<String,Long> species(FluidDomainState state){
        var totals=new TreeMap<String,Long>();
        for(var stock:state.liquid().stocks())if(stock.mixture()!=null)stock.mixture().stableAmounts(state.chemicalRegistry()).forEach((id,q)->{if(q!=0)totals.merge(id,q,Math::addExact);});
        for(var node:state.gas().nodes())for(var component:node.inventory().components())for(var amount:component.species())if(amount.amountQuanta()!=0)totals.merge(amount.speciesId(),amount.amountQuanta(),Math::addExact);
        return totals;
    }
    private static Map<String,Long> accountedSpecies(FluidDomainState state,boolean ambient){
        var result=new TreeMap<>(species(state));
        if(ambient){
            for(var group:state.chemicalSink().groups())group.speciesQuanta().forEach((id,q)->result.merge(id,q,Math::addExact));
            for(var group:state.chemicalSource().groups())group.speciesQuanta().forEach((id,q)->result.merge(id,-q,Math::addExact));
            result.values().removeIf(q->q==0);
        }
        return result;
    }
    private static long accountedMass(FluidStateAccess state){
        var checkpoint=state.exportState();
        return Math.subtractExact(Math.addExact(mass(((ResidentFluidState)state).inventorySummary()),checkpoint.chemicalSink().totalMassUnits()),checkpoint.chemicalSource().totalMassUnits());
    }
    private static void check(FluidStateAccess state,long mass,boolean ambient){var resident=(ResidentFluidState)state;if((ambient?accountedMass(state):mass(resident.inventorySummary()))!=mass||!resident.observation().thermalAuditClosed())throw new AssertionError("Thermal phase mass/energy accounting failed");}
    private static long mass(ResidentFluidState.InventorySummary s){return Math.addExact(Math.addExact(s.liquidMassUnits(),s.coherentMassUnits()),Math.addExact(s.granularMassUnits(),s.gasMassUnits()));}
    @FunctionalInterface interface Decoder<T>{T decode(String id,java.io.Reader reader);}
    static <T>List<T> read(Path root,String directory,Decoder<T> decoder)throws Exception{
        var result=new ArrayList<T>();try(var files=Files.list(root.resolve(directory))){for(var file:files.filter(p->p.toString().endsWith(".json")).sorted().toList())try(var reader=Files.newBufferedReader(file)){result.add(decoder.decode("mmm:"+file.getFileName().toString().replace(".json",""),reader));}}return result;
    }
}
