package mmm.gpuprobe;

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
            for(String mode:(args.length>2?List.of(args[2].split(",")):List.of("freeze","melt","boil","condense")))run(backend,mode,thermal,gas);
        }
    }
    private static void run(VulkanFluidSolverBackend backend,String mode,ThermalRegistry thermal,GasRegistry gas) {
        var domain=new FluidDomain(4,4,4,new FluidSolverConfig(.5f,1000,0,.05f,200,1e-5f,.002f));
        var fluids=new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(BuiltinFluidMaterials.createRegistry());domain.sealDomainBoundary();
        domain.setVolume(1,1,1,mode.equals("melt")?.001f:.25f);
        domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);domain.bindGasRegistry(gas);
        double temperature=switch(mode){case "freeze"->250;case "melt"->350;case "boil"->420;default->280;};
        domain.bindThermal(thermal,fluids,temperature);
        if(mode.equals("melt")){
            long batches=1000,mass=mmm.units.Mass.exactUnits(java.math.BigInteger.ONE,java.math.BigInteger.ONE);
            double energy=new ThermalStateFunction(thermal).internalEnergyAtTemperature(thermal.materialHandle("mmm:ice"),1,Map.of(),temperature);
            domain.addCoherentPhaseExternal(1,1,1,"mmm:ice",146*batches,mass,null,energy);
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
            int operations=0;
            for(int step=1;step<=2;step++){
                try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(step,current,true,ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));var prepared=candidate.claimPreparedDeviceState()){
                    var stats=candidate.stats();
                    operations+=switch(mode){case "freeze"->stats.condensedPhase().frozenCells();case "melt"->stats.condensedPhase().meltedCells();case "boil"->stats.gasPhase().boiledCells();default->stats.gasPhase().condensedCells();};
                    var next=prepared.commit();current.close();current=next;
                }
                check(current,expectedMass);
            }
            if(operations==0)throw new AssertionError(mode+" did not convert any inventory");
            try(var candidate=backend.evaluate(new FluidStepRequest(3,current,List.of(),false,0,false,true));var prepared=candidate.claimPreparedDeviceState()){
                var next=prepared.commit();current.close();current=next;
            }
            check(current,expectedMass);
            var checkpoint=current.exportState();var restored=FluidDomain.restoreAuthoritativeState(checkpoint);
            if(!restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory()))throw new AssertionError(mode+" checkpoint audit failed");
            ((ResidentFluidState)current).display();
            System.out.println("GPU thermal phase "+mode+": active_cells="+operations+" exact_mass=true audit_closed=true mechanical_continuation=true checkpoint=true");
        }finally{current.close();}
    }
    private static void check(FluidStateAccess state,long mass){var resident=(ResidentFluidState)state;if(mass(resident.inventorySummary())!=mass||!resident.observation().thermalAuditClosed())throw new AssertionError("Thermal phase mass/energy accounting failed");}
    private static long mass(ResidentFluidState.InventorySummary s){return Math.addExact(Math.addExact(s.liquidMassUnits(),s.coherentMassUnits()),Math.addExact(s.granularMassUnits(),s.gasMassUnits()));}
    @FunctionalInterface private interface Decoder<T>{T decode(String id,java.io.Reader reader);}
    private static <T>List<T> read(Path root,String directory,Decoder<T> decoder)throws Exception{
        var result=new ArrayList<T>();try(var files=Files.list(root.resolve(directory))){for(var file:files.filter(p->p.toString().endsWith(".json")).sorted().toList())try(var reader=Files.newBufferedReader(file)){result.add(decoder.decode("mmm:"+file.getFileName().toString().replace(".json",""),reader));}}return result;
    }
}
