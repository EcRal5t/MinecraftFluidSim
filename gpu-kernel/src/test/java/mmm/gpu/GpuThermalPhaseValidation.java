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
        var chemistry=mmm.fluid.TestFluidDefinitions.load().chemistry();var granular=mmm.fluid.TestFluidDefinitions.load().granular();var gas=mmm.fluid.TestFluidDefinitions.load().gas();
        var thermal=new ThermalRegistry(read(definitions,"thermal_materials",ThermalDefinitionCodec::decodeMaterial),
            read(definitions,"thermal_species",ThermalDefinitionCodec::decodeSpecies),read(definitions,"phase_transitions",ThermalDefinitionCodec::decodeTransition),
            List.of(),List.of(),chemistry.fingerprint(),granular.fingerprint(),gas.fingerprint());
        try(var backend=new VulkanFluidSolverBackend(device)) {
            for(String mode:(args.length>2?List.of(args[2].split(",")):List.of("freeze","melt","boil","condense","dissolve","outgas","ambient-outgas","mixed-boil","mixed-condense","multi-contact","vapor-empty"))) {
                if(mode.equals("surface"))runSurface(backend,thermal,gas,definitions);
                else run(backend,mode,mode.equals("vapor-empty")?extendedLiquidRange(thermal):thermal,gas);
            }
        }
    }
    private static ThermalRegistry extendedLiquidRange(ThermalRegistry source){
        var materials=source.materials().stream().map(material->{
            if(!material.id().equals("mmm:water"))return material;
            var segment=material.caloricSegments().getFirst();
            return new ThermalMaterialDefinition(material.id(),material.carrierKind(),material.carrierId(),
                    material.referenceTemperatureK(),material.referenceSpecificEnergyJPerKg(),material.conductivityWPerMeterK(),
                    List.of(new ThermalMaterialDefinition.CaloricSegment(segment.minimumTemperatureK(),1400,segment.specificHeatJPerKgK())));
        }).toList();
        return new ThermalRegistry(materials,source.speciesOffsets(),source.transitions(),source.boundaries(),source.finiteDevices(),
                source.chemicalRegistryFingerprint(),source.granularRegistryFingerprint(),source.gasRegistryFingerprint());
    }
    private static void runSurface(VulkanFluidSolverBackend backend,ThermalRegistry base,GasRegistry gas,Path definitions) throws Exception {
        var thermal=new ThermalRegistry(base.materials(),base.speciesOffsets(),base.transitions(),base.boundaries(),
                read(definitions,"finite_thermal_devices",ThermalDefinitionCodec::decodeFiniteDevice),
                read(definitions,"surface_materials",ThermalDefinitionCodec::decodeSurface),
                base.chemicalRegistryFingerprint(),base.granularRegistryFingerprint(),base.gasRegistryFingerprint());
        var chemistry=mmm.fluid.TestFluidDefinitions.load().chemistry();
        var domain=new HostFluidState(4,4,4,new FluidSolverConfig(.5f,0,.05f,200,1e-5f,.002f),mmm.fluid.TestFluidDefinitions.withThermal(thermal));
        var fluids=new FluidMaterialRegistry(List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material()));
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(mmm.fluid.TestFluidDefinitions.load().material());domain.sealDomainBoundary();
        domain.bindChemistry(chemistry);domain.bindGasRegistry(gas);domain.bindThermal(thermal,fluids,293.15);
        int cell=domain.grid().cell(1,1,1),node=domain.gasNodeAt(.75,.75,.75);
        if(node<1)throw new AssertionError("Surface test gas cell is unavailable");
        domain.editGas(node,BuiltinGasDefinitions.CO2,1_000_000_000L,293.15,false);
        var surface=new FixedSurfaceState(FiniteThermalDeviceDefinition.LocalFace.EAST,"mmm:co2_adsorbent",
                mmm.units.Mass.fromKilograms(1),Map.of(BuiltinChemicalDefinitions.CO2,500_000L));
        var wallDefinition=thermal.finiteDevice("mmm:ordinary_metal_wall");
        var wallMaterial=thermal.material(thermal.materialHandle(wallDefinition.thermalMaterialId()));
        var wallPort=wallDefinition.ports().stream().filter(port->port.localFace()==surface.face()).findFirst().orElseThrow();
        var body=new FiniteThermalBodyState(0,thermal.fingerprint(),wallDefinition.thermalMaterialId(),wallDefinition.bodyMassUnits(),0);
        var device=new FiniteThermalDeviceState("mmm:surface_test","wall",wallDefinition.id(),body,List.of(surface));
        body=new FiniteThermalBodyState(0,thermal.fingerprint(),body.thermalMaterialId(),body.massUnits(),SurfaceThermalState.energyAtTemperature(thermal,device,293.15));
        device=new FiniteThermalDeviceState(device.providerId(),device.objectId(),device.deviceTypeId(),body,device.surfaces());
        var devices=new FiniteThermalDeviceInventory(thermal.fingerprint(),List.of(device));
        var ports=List.of(new FluidStateAccess.DevicePort(device.providerId(),device.objectId(),
                new FluidDomainContracts.ThermalPortRequest(1,1,1,0,wallPort.wallThicknessMeters(),wallMaterial.conductivityWPerMeterK(),wallPort.fluidFilmThicknessMeters())));
        var first=surfaceStep(backend,domain,devices,ports,thermal,gas,cell,"adsorb");
        var low=HostFluidState.restoreAuthoritativeState(first.state());
        var co2=first.state().spatialGas().cells().get(cell).component(BuiltinGasDefinitions.CO2);
        if(co2!=null)low.editGas(low.gasNodeAt(.75,.75,.75),BuiltinGasDefinitions.CO2,co2.standardVolumeNanos(),
                low.gasNodeTemperatureK(low.gasNodeAt(.75,.75,.75)),true);
        surfaceStep(backend,low,first.devices(),ports,thermal,gas,cell,"desorb");
    }
    private record SurfaceResult(FluidDomainState state,FiniteThermalDeviceInventory devices) {}
    private static SurfaceResult surfaceStep(VulkanFluidSolverBackend backend,HostFluidState domain,FiniteThermalDeviceInventory devices,
                                             List<FluidStateAccess.DevicePort> ports,ThermalRegistry thermal,GasRegistry gas,int cell,String label) {
        var state=domain.captureAuthoritativeState();var device=devices.devices().getFirst();var surface=device.surfaces().getFirst();
        var definition=thermal.surfaceMaterial(surface.materialId());var adsorbate=definition.adsorbates().getFirst();
        var inventory=state.spatialGas().cells().get(cell);var component=inventory.component(BuiltinGasDefinitions.CO2);
        double pressure=new GasThermalModel(thermal,gas).absolutePressure(inventory,domain.cellGasVolume(1,1,1),state.spatialGas().energyAt(cell));
        double partial=component==null?0:pressure*component.standardVolumeNanos()/(double)inventory.standardVolumeNanos();
        double temperature=SurfaceThermalState.temperature(thermal,device);
        double affinity=adsorbate.affinityPerPa()*Math.exp(adsorbate.adsorptionHeatJPerMol()/8.31446261815324*(1/temperature-1/adsorbate.referenceTemperatureK()));
        long present=surface.speciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L);
        double capacity=mmm.units.Mass.kilograms(surface.loadingMassUnits())*definition.siteCapacityMolesPerKg()*1e6;
        double demand=definition.exchangeMolesPerSquareMeterSecond()*definition.effectiveAreaFactor()*.25*domain.config().timeStep()*1e6
                *(affinity*partial/(1+affinity*partial)-present/capacity)+surface.residualQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0.0);
        long unit=mmm.fluid.TestFluidDefinitions.load().chemistry().speciesMassUnits(mmm.fluid.TestFluidDefinitions.load().chemistry().speciesHandle(BuiltinChemicalDefinitions.CO2),1);
        long requested=(long)Math.floor(Math.abs(demand));
        if(demand>=0) {
            long donor=component==null?0:component.species().stream().filter(amount->amount.speciesId().equals(BuiltinChemicalDefinitions.CO2))
                    .mapToLong(GasComponentAmount.SpeciesAmount::amountQuanta).sum();
            requested=Math.min(requested,Math.min(donor,Math.min(component==null?0:component.massUnits()/unit,(long)Math.floor(capacity-present))));
        } else requested=Math.min(requested,present);
        int gasHandle=thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS,BuiltinGasDefinitions.CO2);
        var gasMaterial=thermal.material(gasHandle);double minimum=ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
        double gasReference=thermal.speciesOffset(gasHandle,BuiltinChemicalDefinitions.CO2).referenceInternalEnergyJPerMol().toDouble()*1e-6;
        double gasTemperature=new GasThermalModel(thermal,gas).state(inventory,state.spatialGas().energyAt(cell)).temperatureK();
        double carried=unit*1e-10*(gasMaterial.referenceSpecificEnergyJPerKg()+gasMaterial.sensibleSpecificEnergyAt(demand>=0?gasTemperature:temperature))+gasReference;
        double surfaceEnergy=1e-6*(adsorbate.referenceEnergyJPerMol()+adsorbate.heatCapacityJPerMolK()*(temperature-adsorbate.referenceTemperatureK()));
        double wallCost=demand>=0?surfaceEnergy-carried:carried-surfaceEnergy;
        double wallMargin=device.body().internalEnergyJ()-SurfaceThermalState.energyAtTemperature(thermal,device,minimum);
        if(wallCost>0)requested=Math.min(requested,(long)Math.floor(Math.max(0,wallMargin)/wallCost));
        if(demand>=0) {
            double gasCold=unit*1e-10*(gasMaterial.referenceSpecificEnergyJPerKg()+gasMaterial.sensibleSpecificEnergyAt(minimum))+gasReference;
            double gasCost=carried-gasCold,gasMargin=state.spatialGas().energyAt(cell)-new GasThermalModel(thermal,gas).internalEnergyAtTemperature(inventory,minimum);
            if(gasCost>0)requested=Math.min(requested,(long)Math.floor(Math.max(0,gasMargin)/gasCost));
        }
        long expected=demand>=0?requested:-requested;
        if(label.equals("adsorb")?expected<=0:expected>=0)throw new AssertionError("Surface reference direction is wrong: "+label+" "+expected);
        var input=new FluidStateAccess(){public FluidDomainState.Layout layout(){return state.layout();}public FluidDomainState exportState(){return state;}
            public FiniteThermalDeviceInventory finiteThermalDevices(){return devices;}public List<FluidStateAccess.DevicePort> finiteThermalPorts(){return ports;}};
        try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(0,input,false));var prepared=candidate.claimPreparedDeviceState();var result=prepared.commit()) {
            var after=result.exportState();var next=result.finiteThermalDevices();var nextDevice=next.devices().getFirst();
            long actual=nextDevice.surfaces().getFirst().speciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L)-present;
            if(Math.abs(actual-expected)>1)throw new AssertionError("Surface "+label+" differs from finite-rate reference: actual="+actual+" expected="+expected+" partial="+partial);
            long beforeMass=state.spatialGas().cells().stream().mapToLong(GasInventory::totalMassUnits).sum()+present*unit;
            long afterMass=after.spatialGas().cells().stream().mapToLong(GasInventory::totalMassUnits).sum()+(present+actual)*unit;
            if(beforeMass!=afterMass || present+actual<0 || present+actual>capacity)throw new AssertionError("Surface "+label+" changed mass or exceeded capacity");
            double beforeEnergy=domain.exactInternalEnergy().toDouble()+device.body().internalEnergyJ();
            double afterEnergy=HostFluidState.restoreAuthoritativeState(after).exactInternalEnergy().toDouble()+nextDevice.body().internalEnergyJ();
            if(Math.abs(afterEnergy-beforeEnergy)>1e-9*Math.max(Math.abs(beforeEnergy),Math.abs(afterEnergy)))
                throw new AssertionError("Surface "+label+" changed total energy: "+beforeEnergy+" / "+afterEnergy);
            System.out.println("surface_reference direction="+label+" partial_pa="+partial+" initial="+present+" expected="+expected+" actual="+actual+" capacity="+capacity);
            try(var settlement=backend.evaluate(new FluidStepRequest(1,result,List.of(),false,false,true));
                var preparedSettlement=settlement.claimPreparedDeviceState();var settled=preparedSettlement.commit()) {
                return new SurfaceResult(settled.exportState(),settled.finiteThermalDevices());
            }
        }
    }
    private static void run(VulkanFluidSolverBackend backend,String mode,ThermalRegistry thermal,GasRegistry gas) {
        if(mode.equals("gas-pulse")||mode.equals("gas-pocket")){runLocalGas(backend,mode,thermal,gas);return;}
        if(mode.equals("thermal-interface")){runThermalInterface(backend,thermal,gas);return;}
        var domain=new HostFluidState(4,4,4,new FluidSolverConfig(.5f,0,.05f,200,1e-5f,.002f), mmm.fluid.TestFluidDefinitions.withThermal(thermal));
        boolean ambient=mode.equals("ambient-outgas");
        var fluids=new FluidMaterialRegistry(List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material()));
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(mmm.fluid.TestFluidDefinitions.load().material());if(!ambient)domain.sealDomainBoundary();
        boolean vaporEmpty=mode.equals("vapor-empty");
        var transition=thermal.transitions().stream().filter(t->BuiltinChemicalDefinitions.H2O.equals(t.speciesId())).findFirst().orElseThrow();
        var batch=new GasLiquidPhaseModel(thermal,gas).gasBatch(transition,1).components().getFirst();
        long waterUnit=mmm.units.Mass.exactUnits(java.math.BigInteger.valueOf(18),java.math.BigInteger.valueOf(1_000_000_000L));
        long vaporQuantum=Math.max(1,Math.round((double)batch.massUnits()/waterUnit/transition.lowerVolumeSvqPerBatch()));
        float initialVolume=vaporEmpty?(float)(vaporQuantum*waterUnit*1e-10/(1000.0*.125)):mode.equals("melt")?.001f:mode.equals("multi-contact")?1.0f:.25f;
        domain.setVolume(1,1,1,initialVolume);
        domain.bindChemistry(mmm.fluid.TestFluidDefinitions.load().chemistry());domain.bindGasRegistry(gas);
        boolean film=mode.equals("dissolve")||mode.equals("outgas")||ambient||mode.equals("multi-contact");
        boolean mixed=mode.startsWith("mixed-");
        if(film)domain.setMarkerChemical(0,!mode.equals("dissolve")&&!mode.equals("multi-contact")?Map.of(BuiltinChemicalDefinitions.H2O,1_000_000_000L,BuiltinChemicalDefinitions.CO2,100_000_000L):Map.of(BuiltinChemicalDefinitions.H2O,1_000_000_000L));
        if(vaporEmpty)domain.setMarkerChemical(0,Map.of(BuiltinChemicalDefinitions.H2O,vaporQuantum));
        if(mixed)domain.setMarkerChemical(0,Map.of(BuiltinChemicalDefinitions.H2O,400_000_000L,BuiltinChemicalDefinitions.ETHANOL,400_000_000L));
        double temperature=vaporEmpty?1300:mixed?(mode.equals("mixed-boil")?380:280):film?298.15:switch(mode){case "freeze"->250;case "melt"->350;case "boil"->420;default->280;};
        domain.bindThermal(domain.definitions().thermal(),fluids,temperature);
        if(mode.equals("melt")){
            long batches=1000,mass=mmm.units.Mass.exactUnits(java.math.BigInteger.ONE,java.math.BigInteger.ONE);
            double energy=new ThermalStateFunction(thermal).internalEnergyAtTemperature(thermal.materialHandle("mmm:ice"),1,Map.of(),temperature);
            domain.addCoherentPhaseExternal(1,1,1,"mmm:ice",146*batches,mass,null,energy);
        }
        if(mode.equals("multi-contact")){
            for(double x:new double[]{.25,1.25}){
                int node=domain.gasNodeAt(x,.75,.75);if(node<1)throw new AssertionError("Missing adjacent gas cell");
                domain.editGas(node,"mmm:co2",1_000_000_000L,temperature,false);
            }
        } else if(film){
            int node=domain.gasNodeAt(.75,.95,.75);if(node<1)throw new AssertionError("Unexpected film contact gas node: "+node);
            if(mode.equals("dissolve"))domain.editGas(node,"mmm:co2",1_000_000_000L,temperature,false);
        }
        if(mode.equals("condense")||mode.equals("mixed-condense")){
            int node=domain.gasNodeAt(.75,.95,.75);if(node<1)throw new AssertionError("No finite chamber for condensation");
            domain.editGas(node,"mmm:water_vapor",1_000_000_000L,temperature,false);
            if(mixed)domain.editGas(node,"mmm:ethanol_vapor",1_000_000_000L,temperature,false);
        }
        FluidStateAccess current=domain.captureAuthoritativeState();
        try {
            try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(0,current,false));var prepared=candidate.claimPreparedDeviceState()){
                var next=prepared.commit();current.close();current=next;
            }
            long expectedMass=mass(((ResidentFluidState)current).inventorySummary());
            var initialCheckpoint=film||mixed||vaporEmpty?current.exportState():null;
            long initialWater=mixed?liquidSpecies(initialCheckpoint,BuiltinChemicalDefinitions.H2O):0, initialEthanol=mixed?liquidSpecies(initialCheckpoint,BuiltinChemicalDefinitions.ETHANOL):0;
            long initialLiquidCo2=film?liquidSpecies(initialCheckpoint,BuiltinChemicalDefinitions.CO2):0;
            long leftGas=mode.equals("multi-contact")?gasSpecies(initialCheckpoint,20,BuiltinChemicalDefinitions.CO2):0;
            long rightGas=mode.equals("multi-contact")?gasSpecies(initialCheckpoint,22,BuiltinChemicalDefinitions.CO2):0;
            if(ambient)expectedMass=accountedMass(current);
            var initialSpecies=film||vaporEmpty?accountedSpecies(initialCheckpoint,ambient):Map.<String,Long>of();
            var initialElements=film?elements(initialSpecies,initialCheckpoint.chemicalRegistry()):Map.<String,Long>of();
            int operations=0;
            for(int step=1;step<=2;step++){
                try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(step,current,!film));var prepared=candidate.claimPreparedDeviceState()){
                    var stats=candidate.stats();
                    if(film||mixed||vaporEmpty||mode.equals("boil")||mode.equals("condense")){if(stats.massTransfer().active()&&stats.massTransfer().changedStocks())operations++;}
                    else operations+=mode.equals("freeze")?stats.condensedPhase().frozenCells():stats.condensedPhase().meltedCells();
                    var next=prepared.commit();current.close();current=next;
                }
                check(current,expectedMass,ambient);
            }
            if(film){
                var after=current.exportState();long delta=liquidSpecies(after,BuiltinChemicalDefinitions.CO2)-initialLiquidCo2;
                if(mode.equals("dissolve")||mode.equals("multi-contact")?delta<=0:delta>=0)throw new AssertionError(mode+" did not transfer CO2 in the expected direction");
                if(mode.equals("multi-contact") && !(gasSpecies(after,20,BuiltinChemicalDefinitions.CO2)<leftGas && gasSpecies(after,22,BuiltinChemicalDefinitions.CO2)<rightGas))
                    throw new AssertionError("Both adjacent gas cells must transfer CO2");
                if(!elements(accountedSpecies(after,ambient),after.chemicalRegistry()).equals(initialElements)||!HostFluidState.restoreAuthoritativeState(after).gasBoundary().auditClosed())throw new AssertionError(mode+" species/gas audit failed: initial="+initialSpecies+", actual="+accountedSpecies(after,ambient)+", gasClosed="+HostFluidState.restoreAuthoritativeState(after).gasBoundary().auditClosed());
            }
            if(vaporEmpty){
                var after=current.exportState();
                if(liquidSpecies(after,BuiltinChemicalDefinitions.H2O)!=0 || !species(after).equals(initialSpecies))
                    throw new AssertionError("Complete vaporization did not transfer all water species: liquid="+liquidSpecies(after,BuiltinChemicalDefinitions.H2O)+", before="+initialSpecies+", after="+species(after)+", quantum="+vaporQuantum+", stocks="+after.liquid().stocks());
                for(var stock:after.liquid().stocks())if(stock.massUnits()!=0 || stock.internalEnergy()!=0.0)
                    throw new AssertionError("Evaporated liquid retains mass or energy");
            }
            if(mixed){
                var after=current.exportState();
                long water=liquidSpecies(after,BuiltinChemicalDefinitions.H2O)-initialWater;
                long ethanol=liquidSpecies(after,BuiltinChemicalDefinitions.ETHANOL)-initialEthanol;
                if(mode.equals("mixed-boil") ? !(ethanol < water && water < 0) : !(water > 0 && ethanol > 0))
                    throw new AssertionError(mode+" unexpected mixed-phase transfer: water="+water+", ethanol="+ethanol);
                if(!species(after).equals(species(initialCheckpoint)))throw new AssertionError(mode+" species inventory changed");
            }
            if(operations==0)throw new AssertionError(mode+" did not convert any inventory");
            try(var candidate=backend.evaluate(new FluidStepRequest(3,current,List.of(),false,false,true));var prepared=candidate.claimPreparedDeviceState()){
                var next=prepared.commit();current.close();current=next;
            }
            check(current,expectedMass,ambient);
            var checkpoint=current.exportState();var restored=HostFluidState.restoreAuthoritativeState(checkpoint);
            if((mode.equals("boil")||mode.equals("outgas"))&&checkpoint.spatialGas().phaseSource().isEmpty())
                throw new AssertionError(mode+" did not publish its finite gas phase source");
            if((mode.equals("condense")||mode.equals("dissolve"))&&checkpoint.spatialGas().phaseSink().isEmpty())
                throw new AssertionError(mode+" did not publish its finite gas phase sink");
            if(film&&(!elements(accountedSpecies(checkpoint,ambient),checkpoint.chemicalRegistry()).equals(initialElements)||!restored.gasBoundary().auditClosed()))throw new AssertionError(mode+" continuation species/gas audit failed: initial="+initialSpecies+", actual="+accountedSpecies(checkpoint,ambient)+", gasClosed="+restored.gasBoundary().auditClosed()+", gas="+checkpoint.spatialGas().cells());
            if(!restored.thermalAudit().acceptsInventory(restored.exactInternalEnergy()))throw new AssertionError(mode+" checkpoint audit failed");
            if(ambient){
                var resumed=restored.captureAuthoritativeState();
                try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(4,resumed,false));var prepared=candidate.claimPreparedDeviceState()){
                    var next=prepared.commit();current.close();current=next;
                }finally{resumed.close();}
                check(current,expectedMass,true);
                var continued=current.exportState();
                if(!elements(accountedSpecies(continued,true),continued.chemicalRegistry()).equals(initialElements))throw new AssertionError("Restored ambient transfer repeated or lost chemical postings");
                var continuedDomain=HostFluidState.restoreAuthoritativeState(continued);
                if(!continuedDomain.gasBoundary().auditClosed()||!continuedDomain.thermalAudit().acceptsInventory(continuedDomain.exactInternalEnergy()))throw new AssertionError("Restored ambient transfer audit failed");
            }
            ((ResidentFluidState)current).display();
            System.out.println("GPU thermal phase "+mode+": active_cells="+operations+" exact_mass=true audit_closed=true mechanical_continuation=true checkpoint=true");
        }finally{current.close();}
    }
    /** Pressure and species propagation use the full resident mechanical and caloric chain. */
    private static void runThermalInterface(VulkanFluidSolverBackend backend,ThermalRegistry thermal,GasRegistry gas) {
        var domain=new HostFluidState(4,4,4,new FluidSolverConfig(.5f,0,.05f,200,1e-5f,.002f), mmm.fluid.TestFluidDefinitions.withThermal(thermal));
        var fluids=new FluidMaterialRegistry(List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material()));
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(mmm.fluid.TestFluidDefinitions.load().material());domain.sealDomainBoundary();
        for(int z=0;z<4;z++)for(int y=0;y<4;y++)for(int x=0;x<4;x++)if(x!=1||y!=1||z!=1)domain.setSolid(x,y,z,true);
        domain.setVolume(1,1,1,.5f);domain.bindChemistry(mmm.fluid.TestFluidDefinitions.load().chemistry());domain.bindGasRegistry(gas);
        domain.bindThermal(domain.definitions().thermal(),fluids,293.15);domain.resetAmbientGas();
        int cell=1+(1*4+1)*4;domain.editGas(cell+1,BuiltinGasDefinitions.CO2,100_000_000L,450,false);
        try(var before=domain.captureAuthoritativeState()){
            var initial=before.exportState();double liquidBefore=initial.liquid().physicalStocks().getFirst().internalEnergy();
            double gasBefore=initial.spatialGas().energyAt(cell);
            try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(0,before,false));var prepared=candidate.claimPreparedDeviceState();var after=prepared.commit()){
                var result=after.exportState();double liquidAfter=result.liquid().physicalStocks().getFirst().internalEnergy();
                double gasAfter=result.spatialGas().energyAt(cell);
                if(!(liquidAfter>liquidBefore&&gasAfter<gasBefore))throw new AssertionError("Half-cell gas/liquid interface failed to exchange heat: liquid="+liquidBefore+" -> "+liquidAfter+", gas="+gasBefore+" -> "+gasAfter);
                if(!((ResidentFluidState)after).observation().thermalAuditClosed())throw new AssertionError("Half-cell gas/liquid heat audit did not close");
            }
        }
        System.out.println("GPU half-cell gas/liquid thermal interface: exchange=true energy=true");
    }
    private static void runLocalGas(VulkanFluidSolverBackend backend,String mode,ThermalRegistry thermal,GasRegistry gas) {
        var domain=new HostFluidState(4,4,4,new FluidSolverConfig(.5f,0,.05f,200,1e-5f,.002f), mmm.fluid.TestFluidDefinitions.withThermal(thermal));
        var fluids=new FluidMaterialRegistry(List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material()));
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(mmm.fluid.TestFluidDefinitions.load().material());
        domain.sealDomainBoundary();
        if(mode.equals("gas-pocket"))domain.setVolume(1,1,1,.9999f);
        domain.bindChemistry(mmm.fluid.TestFluidDefinitions.load().chemistry());domain.bindGasRegistry(gas);
        domain.bindThermal(domain.definitions().thermal(),fluids,293.15);domain.resetAmbientGas();
        int pulse=1+(1*4+1)*4,neighbor=pulse+1;
        if(mode.equals("gas-pocket")){
            double volume=domain.gasBoundary().availableVolume(pulse+1);
            if(!(volume>0&&volume<.000125))throw new AssertionError("Fixture did not create a small gas pocket: "+volume);
        }
        var before=domain.captureAuthoritativeState();
        long beforeNeighbor=gasSpecies(before,neighbor,BuiltinChemicalDefinitions.CO2);
        before.close();
        domain.editGas(pulse+1,BuiltinGasDefinitions.CO2,mode.equals("gas-pocket")?1000:200_000_000,450,false);
        FluidStateAccess current=domain.captureAuthoritativeState();
        try{
            var initial=current.exportState();long mass=0;
            for(var stock:initial.liquid().physicalStocks())mass=Math.addExact(mass,stock.massUnits());
            for(var inventory:initial.spatialGas().cells())mass=Math.addExact(mass,inventory.totalMassUnits());
            double initialTemperature=new GasThermalModel(thermal,gas).state(initial.spatialGas().cells().get(pulse),initial.spatialGas().energyAt(pulse)).temperatureK();
            if(initialTemperature<=293.15)throw new AssertionError("Gas pulse did not create its local temperature difference");
            for(int step=1;step<=4;step++){
                try(var candidate=backend.evaluate(new FluidStepRequest(step,current,List.of(),false,false,true));var prepared=candidate.claimPreparedDeviceState()){
                    var next=prepared.commit();current.close();current=next;
                }
                check(current,mass,false);
            }
            var after=current.exportState();
            if(mode.equals("gas-pulse")&&gasSpecies(after,neighbor,BuiltinChemicalDefinitions.CO2)<=beforeNeighbor)
                throw new AssertionError("Pressure pulse did not transport the added CO2 to its open neighbor");
            var restored=HostFluidState.restoreAuthoritativeState(after);
            if(!restored.gasBoundary().auditClosed()||!restored.thermalAudit().acceptsInventory(restored.exactInternalEnergy()))
                throw new AssertionError("Local gas continuation lost its mass or energy ledger");
            System.out.println("GPU local gas "+mode+": pressure_and_heat=true mass=true checkpoint=true");
        }finally{current.close();}
    }
    private static long liquidSpecies(FluidDomainState state,String id){
        long total=0;for(var stock:state.liquid().stocks())if(stock.mixture()!=null)total=Math.addExact(total,stock.mixture().stableAmounts(state.chemicalRegistry()).getOrDefault(id,0L));return total;
    }
    private static long gasSpecies(FluidDomainState state,int cell,String id){
        long result=0;for(var component:state.spatialGas().cells().get(cell).components())for(var amount:component.species())if(amount.speciesId().equals(id))result=Math.addExact(result,amount.amountQuanta());return result;
    }
    private static Map<String,Long> species(FluidDomainState state){
        var totals=new TreeMap<String,Long>();
        for(var stock:state.liquid().stocks())if(stock.mixture()!=null)stock.mixture().stableAmounts(state.chemicalRegistry()).forEach((id,q)->{if(q!=0)totals.merge(id,q,Math::addExact);});
        for(var inventory:state.spatialGas().cells())for(var component:inventory.components())for(var amount:component.species())if(amount.amountQuanta()!=0)totals.merge(amount.speciesId(),amount.amountQuanta(),Math::addExact);
        return totals;
    }
    private static Map<String,Long> accountedSpecies(FluidDomainState state,boolean ambient){
        var result=new TreeMap<>(species(state));
        if(ambient){
            for(var group:state.chemicalSink().groups())group.speciesQuanta().forEach((id,q)->result.merge(id,q,Math::addExact));
            for(var group:state.chemicalSource().groups())group.speciesQuanta().forEach((id,q)->result.merge(id,-q,Math::addExact));
            for(var entry:state.spatialGas().escapedSink().entries())for(var amount:entry.species())result.merge(amount.speciesId(),amount.amountQuanta(),Math::addExact);
            for(var entry:state.spatialGas().externalSource().entries())for(var amount:entry.species())result.merge(amount.speciesId(),-amount.amountQuanta(),Math::addExact);
            result.values().removeIf(q->q==0);
        }
        return result;
    }
    private static Map<String,Long> elements(Map<String,Long> quantities,ChemicalRegistry registry){
        var result=new TreeMap<String,Long>();
        quantities.forEach((id,q)->{
            var species=registry.species(registry.speciesHandle(id));
            species.composition().forEach((element,count)->result.merge(element,Math.multiplyExact(q,count.longValue()),Math::addExact));
            result.merge("@charge",Math.multiplyExact(q,(long)species.charge()),Math::addExact);
        });
        result.values().removeIf(q->q==0);
        return result;
    }
    private static long accountedMass(FluidStateAccess state){
        var checkpoint=state.exportState();
        long total=Math.subtractExact(Math.addExact(mass(((ResidentFluidState)state).inventorySummary()),checkpoint.chemicalSink().totalMassUnits()),checkpoint.chemicalSource().totalMassUnits());
        for(var entry:checkpoint.spatialGas().escapedSink().entries())total=Math.addExact(total,entry.massUnits());
        for(var entry:checkpoint.spatialGas().externalSource().entries())total=Math.subtractExact(total,entry.massUnits());
        return total;
    }
    private static void check(FluidStateAccess state,long mass,boolean ambient){var resident=(ResidentFluidState)state;if((ambient?accountedMass(state):mass(resident.inventorySummary()))!=mass||!resident.observation().thermalAuditClosed())throw new AssertionError("Thermal phase mass/energy accounting failed");}
    private static long mass(ResidentFluidState.InventorySummary s){return Math.addExact(Math.addExact(s.liquidMassUnits(),s.coherentMassUnits()),Math.addExact(s.granularMassUnits(),s.gasMassUnits()));}
    @FunctionalInterface interface Decoder<T>{T decode(String id,java.io.Reader reader);}
    static <T>List<T> read(Path root,String directory,Decoder<T> decoder)throws Exception{
        var result=new ArrayList<T>();try(var files=Files.list(root.resolve(directory))){for(var file:files.filter(p->p.toString().endsWith(".json")).sorted().toList())try(var reader=Files.newBufferedReader(file)){result.add(decoder.decode("mmm:"+file.getFileName().toString().replace(".json",""),reader));}}return result;
    }
}
