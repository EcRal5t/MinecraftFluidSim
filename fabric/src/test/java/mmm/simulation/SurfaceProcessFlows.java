package mmm.simulation;

import mmm.chemistry.*;
import mmm.fluid.*;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.granular.*;
import mmm.thermal.*;
import mmm.transfer.*;
import mmm.units.Mass;
import mmm.worldprofile.WorldCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.util.*;
import static mmm.simulation.ProcessFlowSupport.*;

/** Fixed-surface adsorption breakthrough and regeneration, and contact catalysis on coated baffles. */
final class SurfaceProcessFlows {
    private SurfaceProcessFlows() {}
    static void industrialAdsorption(int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-adsorption-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.ABSORPTION);
        var region=fixture.region;
        var layout=IndustrialLayout.at(new BlockPos(14,64,20),IndustrialLayout.Scenario.ABSORPTION);
        int[] treatedIn=industrialPortCell(region,layout.ports().get(0)),treatedOut=industrialPortCell(region,layout.ports().get(1));
        int[] controlIn=industrialPortCell(region,layout.ports().get(2)),controlOut=industrialPortCell(region,layout.ports().get(3));
        ItemStack treated=gasFeed(world),control=gasFeed(world);
        ItemStack treatedProduct=null,controlProduct=null;
        var products=new ArrayList<ItemStack>();
        double peakSeparation=0;
        long initial=surfaceProcessMass(region,world)+gasMass(treated)+gasMass(control),supplied=0;

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            int duration=Math.max(1000,steps);
            for(int step=0;step<duration;step++) {
                progress("adsorption",step,duration);
                {
                    motion.settlePendingPhaseWork(region);
                    if(mmm.item.GasContainerItem.contents(treated)==null){treated=gasFeed(world);supplied=Math.addExact(supplied,gasMass(treated));}
                    if(mmm.item.GasContainerItem.contents(control)==null){control=gasFeed(world);supplied=Math.addExact(supplied,gasMass(control));}
                    long quota=IndustrialOperations.tickQuota(1_000_000_000L,step);
                    treated=fixture.transferBox(treated,treatedIn,Direction.WEST,false,quota);
                    control=fixture.transferBox(control,controlIn,Direction.WEST,false,quota);
                    {
                        var gasRequest=new mmm.api.transfer.TransferQuantity.Gas(quota,0);
                        var treatedBlock=region.origin().offset(treatedOut[0]/2,treatedOut[1]/2,treatedOut[2]/2);
                        var controlBlock=region.origin().offset(controlOut[0]/2,controlOut[1]/2,controlOut[2]/2);
                        ItemStack a=null,b=null;
                        if(FluidWorldTargetResolver.transferCell(region,treatedBlock,Direction.EAST,gasRequest,true)!=null) {
                            var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),treatedOut,Direction.EAST,true,quota);
                            if(mmm.item.GasContainerItem.contents(batch)!=null){a=batch;products.add(batch);}
                        }
                        if(FluidWorldTargetResolver.transferCell(region,controlBlock,Direction.EAST,gasRequest,true)!=null) {
                            var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),controlOut,Direction.EAST,true,quota);
                            if(mmm.item.GasContainerItem.contents(batch)!=null){b=batch;products.add(batch);}
                        }
                        if(a!=null && b!=null) {
                            treatedProduct=a;controlProduct=b;
                            peakSeparation=Math.max(peakSeparation,gasFraction(controlProduct,BuiltinGasDefinitions.CO2)-gasFraction(treatedProduct,BuiltinGasDefinitions.CO2));
                        }
                    }
                    if(step%100==0) {
                        System.out.println("adsorption_state step="+step+" peak_separation="+peakSeparation+" adsorbed="
                                +region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedSpeciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L));
                    }
                    available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Adsorption process step rejected");
            }
            motion.settlePendingPhaseWork(region);
            long adsorbed=region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedSpeciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L);
            if(treatedProduct==null)throw new AssertionError("Gas feed did not reach the outlet");
            double treatedFraction=gasFraction(treatedProduct,BuiltinGasDefinitions.CO2),controlFraction=gasFraction(controlProduct,BuiltinGasDefinitions.CO2);
            var deviceInventory=region.finiteThermalDevices(world.thermalRegistryFingerprint());double capacity=0;
            for(var device:deviceInventory.devices())for(var surface:device.surfaces())capacity+=Mass.kilograms(surface.loadingMassUnits())*world.thermalRegistry().surfaceMaterial(surface.materialId()).siteCapacityMolesPerKg()*ChemicalLimits.QUANTA_PER_MOL;
            var adsorbent=world.thermalRegistry().surfaceMaterial("mmm:co2_adsorbent");
            var affinity=adsorbent.adsorbates().stream().filter(a->a.componentId().equals(BuiltinGasDefinitions.CO2)).findFirst().orElseThrow();
            double referencePartial=mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA/2;
            double weight=affinity.affinityPerPa()*referencePartial;
            double referenceFraction=weight/(1+weight);
            double referenceTime=adsorbent.siteCapacityMolesPerKg()/(adsorbent.exchangeMolesPerSquareMeterSecond()*adsorbent.effectiveAreaFactor());
            System.out.println("adsorption_reference temperature_k="+affinity.referenceTemperatureK()+" partial_pa="+referencePartial
                    +" equilibrium_fraction="+referenceFraction+" total_capacity_mol="+capacity/ChemicalLimits.QUANTA_PER_MOL
                    +" relaxation_s_per_kg_per_m2="+referenceTime+" actual_quanta="+adsorbed);
            adsorptionSurfaceTargets(region,world,"feed");
            if(adsorbed<=0 || adsorbed>capacity || !(peakSeparation>0))throw new AssertionError("Adsorption did not retain a finite CO2 inventory: adsorbed="+adsorbed+" capacity="+capacity+" peak="+peakSeparation);
            if(!(controlFraction-treatedFraction<peakSeparation))throw new AssertionError("Adsorbent did not approach breakthrough: peak="+peakSeparation+" final="+(controlFraction-treatedFraction));
            long productMass=0;for(var batch:products)productMass=Math.addExact(productMass,gasMass(batch));
            long after=surfaceProcessMass(region,world)+gasMass(treated)+gasMass(control)+productMass;
            if(after!=Math.addExact(initial,supplied))throw new AssertionError("Gas and adsorbed mass changed: "+(initial+supplied)+" / "+after);
            var recovered=new ArrayList<ItemStack>();
            for(int step=0;step<600;step++) {
                progress("regeneration",step,600);
                motion.settlePendingPhaseWork(region);
                long quota=IndustrialOperations.tickQuota(1_000_000_000L,step);
                var outlet=region.origin().offset(treatedOut[0]/2,treatedOut[1]/2,treatedOut[2]/2);
                if(FluidWorldTargetResolver.transferCell(region,outlet,Direction.EAST,new mmm.api.transfer.TransferQuantity.Gas(quota,0),true)!=null) {
                    recovered.add(fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),treatedOut,Direction.EAST,true,quota));available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Adsorbent regeneration step rejected");
            }
            motion.settlePendingPhaseWork(region);
            long remaining=region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedSpeciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L);
            adsorptionSurfaceTargets(region,world,"regenerated");
            if(remaining>=adsorbed)throw new AssertionError("Lower gas pressure did not release adsorbed CO2: "+adsorbed+" / "+remaining);
            long recoveredMass=0;for(var batch:recovered)recoveredMass=Math.addExact(recoveredMass,gasMass(batch));
            long regenerated=surfaceProcessMass(region,world)+gasMass(treated)+gasMass(control)+productMass+recoveredMass;
            if(regenerated!=Math.addExact(initial,supplied))throw new AssertionError("Regeneration changed gas and adsorbed mass");
            for(int step=0;step<600;step++) {
                progress("readsorption",step,600);
                motion.settlePendingPhaseWork(region);
                if(mmm.item.GasContainerItem.contents(treated)==null){treated=gasFeed(world);supplied=Math.addExact(supplied,gasMass(treated));}
                treated=fixture.transferBox(treated,treatedIn,Direction.WEST,false,IndustrialOperations.tickQuota(1_000_000_000L,step));
                available(region);
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Adsorbent reuse step rejected");
            }
            motion.settlePendingPhaseWork(region);
            long reused=region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedSpeciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L);
            adsorptionSurfaceTargets(region,world,"reused");
            if(reused<=remaining)throw new AssertionError("Regenerated adsorbent did not take up CO2 again: "+remaining+" / "+reused);
            long reusedMass=surfaceProcessMass(region,world)+gasMass(treated)+gasMass(control)+productMass+recoveredMass;
            if(reusedMass!=Math.addExact(initial,supplied))throw new AssertionError("Adsorbent reuse changed gas and adsorbed mass");
        }
    }

    private static void adsorptionSurfaceTargets(FluidRegion region,CommittedWorldRegistries world,String phase) {
        var host=region.hostSnapshot();var gas=host.captureAuthoritativeState().spatialGas();
        double actual=0,capacity=0;int surfaces=0;
        for(var device:region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices())for(var surface:device.surfaces()) {
            var material=world.thermalRegistry().surfaceMaterial(surface.materialId());
            if(material.adsorbates().isEmpty())continue;
            double temperature=mmm.thermal.SurfaceThermalState.temperature(world.thermalRegistry(),device);
            var direction=Direction.valueOf(surface.face().name());
            int axis=direction.getAxis()==Direction.Axis.X?0:direction.getAxis()==Direction.Axis.Y?1:2;
            int portDirection=axis*2+(direction.getAxisDirection()==Direction.AxisDirection.POSITIVE?0:1);
            double sites=Mass.kilograms(surface.loadingMassUnits())*material.siteCapacityMolesPerKg();
            int contacts=0;
            for(var port:WorldThermalEnvironment.ports(region,device,world.thermalRegistry())) {
                if(port.direction()!=portDirection)continue;
                int cell=region.grid().cell(port.cellX(),port.cellY(),port.cellZ());
                var inventory=gas.cells().get(cell);double weight=0;
                var weights=new TreeMap<String,Double>();var partials=new TreeMap<String,Double>();
                for(var adsorbate:material.adsorbates()) {
                    var component=inventory.component(adsorbate.componentId());
                    double partial=component==null || inventory.standardVolumeNanos()==0?0:host.pressure(port.cellX(),port.cellY(),port.cellZ())*component.standardVolumeNanos()/(double)inventory.standardVolumeNanos();
                    double affinity=adsorbate.affinityPerPa()*Math.exp(adsorbate.adsorptionHeatJPerMol()/8.31446261815324*(1/temperature-1/adsorbate.referenceTemperatureK()));
                    double value=affinity*partial;weight+=value;
                    weights.put(adsorbate.speciesId(),value);partials.put(adsorbate.speciesId(),partial);
                }
                for(var entry:weights.entrySet()) {
                    double amount=surface.speciesQuanta().getOrDefault(entry.getKey(),0L)/(double)ChemicalLimits.QUANTA_PER_MOL;
                    double target=sites*entry.getValue()/(1+weight);
                    System.out.println("adsorption_port phase="+phase+" device="+device.objectId()+" face="+surface.face()
                            +" cell="+port.cellX()+","+port.cellY()+","+port.cellZ()+" species="+entry.getKey()+" temperature_k="+temperature
                            +" partial_pa="+partials.get(entry.getKey())+" adsorbed_mol="+amount+" local_equilibrium_mol="+target+" capacity_mol="+sites);
                }
                contacts++;
            }
            if(contacts==0)continue;
            capacity+=sites;
            double amount=surface.speciesQuanta().values().stream().mapToLong(Long::longValue).sum()/(double)ChemicalLimits.QUANTA_PER_MOL;
            actual+=amount;
            surfaces++;
        }
        System.out.println("adsorption_inventory phase="+phase+" surfaces="+surfaces+" adsorbed_mol="+actual+" capacity_mol="+capacity);
        if(!(surfaces>0 && actual>=0 && actual<=capacity))throw new AssertionError("Adsorption surface inventory is outside its capacity");
    }

    private static ItemStack gasFeed(CommittedWorldRegistries world) {
        var half=new ExactRational(java.math.BigInteger.ONE,java.math.BigInteger.TWO);
        var registry=world.gasRegistry();var gas=registry.createMixture(1_000_000_000L,List.of(
                new mmm.fluid.air.GasRegistry.AmbientFraction(BuiltinGasDefinitions.CO2,half),
                new mmm.fluid.air.GasRegistry.AmbientFraction(BuiltinGasDefinitions.NITROGEN,half)));
        double energy=new GasThermalModel(world.thermalRegistry(),registry).internalEnergyAtTemperature(gas,293.15);
        return world.bindActualContainerProfile(mmm.item.ContainerItemStacks.toStack(new GasContents(registry.fingerprint(),world.chemicalRegistry().fingerprint(),gas,energy)));
    }

    private static ItemStack catalysisFeed(CommittedWorldRegistries world) {
        var chemistry=world.chemicalRegistry();
        var transfer=chemistry.gasLiquidMassTransfers().stream()
                .filter(value->value.speciesId().equals(BuiltinChemicalDefinitions.CO2)
                        && value.gasComponentId().equals(BuiltinGasDefinitions.CO2)
                        && value.liquidPhaseId().equals(BuiltinChemicalDefinitions.AQUEOUS))
                .findFirst().orElseThrow();
        double ambientFraction=world.gasRegistry().ambient().stream()
                .filter(value->value.componentId().equals(BuiltinGasDefinitions.CO2))
                .findFirst().orElseThrow().fraction().toDouble();
        double temperature=mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K;
        double concentration=transfer.henrySolubilityAt(temperature)
                *mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA*ambientFraction;
        var amounts=Map.of(BuiltinChemicalDefinitions.H2O,waterQuanta(world),BuiltinChemicalDefinitions.CO2,
                Math.round(concentration*ChemicalLimits.QUANTA_PER_MOL));
        var mixture=ChemicalMixtureRecord.fromStableAmounts(chemistry,amounts,0);
        long mass=mixture.speciesMassUnits(chemistry);
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,0,amounts)));
        var item=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,chemistry,ContainerFluidLedger.NANOS_PER_BLOCK));
        world.bindActualContainerProfile(item);world.attachLiquidThermalContents(item,temperature);return item;
    }

    private static double gasFraction(ItemStack stack,String component) {
        var gas=mmm.item.GasContainerItem.contents(stack).inventory();var value=gas.component(component);
        return value==null?0:value.standardVolumeNanos()/(double)gas.standardVolumeNanos();
    }

    private static long surfaceProcessMass(FluidRegion region,CommittedWorldRegistries world) {
        return Math.addExact(processMass(region.hostSnapshot().captureAuthoritativeState()),region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedMassUnits(world.chemicalRegistry()));
    }

    static void industrialCatalysis(int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var reaction=definitions.chemical().reactions().stream().filter(value->value.id().equals("mmm:co2_hydration")).findFirst().orElseThrow();
        var kinetics=reaction.kinetics();
        var catalyst=definitions.thermal().surfaceMaterial("mmm:hydration_catalyst").catalysis().stream()
                .filter(value->value.reactionId().equals(reaction.id())).findFirst().orElseThrow();
        System.out.println("catalysis_reference temperature_k="+kinetics.referenceTemperatureK()+" forward="+kinetics.forwardRate()
                +" reverse="+kinetics.reverseRate()+" equilibrium_ratio="+(kinetics.forwardRate()/kinetics.reverseRate())
                +" multiplier_per_m2="+catalyst.multiplierPerSquareMeter());
        double full=catalysisRun(steps,IndustrialLayout.Scenario.CATALYSIS);
        double partial=catalysisRun(steps,IndustrialLayout.Scenario.CATALYSIS_PARTIAL);
        if(!(full>partial && partial>0))throw new AssertionError("Catalytic contact area did not change the early gain: full="+full+" partial="+partial);
        catalysisClosed();
    }

    private static double catalysisRun(int steps,IndustrialLayout.Scenario scenario) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-catalysis-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,scenario);
        var region=fixture.region;
        var layout=IndustrialLayout.at(new BlockPos(14,64,20),scenario);
        int[] treatedIn=industrialPortCell(region,layout.ports().get(0)),treatedOut=industrialPortCell(region,layout.ports().get(1));
        int[] controlIn=industrialPortCell(region,layout.ports().get(2)),controlOut=industrialPortCell(region,layout.ports().get(3));
        ItemStack treated=catalysisFeed(world),control=catalysisFeed(world);
        int earlyStep=-1;var products=new ArrayList<ItemStack>();
        long sampleVolume=ContainerFluidLedger.NANOS_PER_BLOCK;
        var treatedSamples=new ArrayList<ChemicalContents>();var controlSamples=new ArrayList<ChemicalContents>();long treatedVolume=0,controlVolume=0;
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(treated,world)+chemicalMass(control,world),supplied=0;
        long catalyst=region.finiteThermalDevices(world.thermalRegistryFingerprint()).surfaceLoadingMassUnits();

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            for(int step=0;step<Math.max(1000,steps);step++) {
                progress("catalysis",step,Math.max(1000,steps));
                {
                    motion.settlePendingPhaseWork(region);
                    long quota=IndustrialOperations.tickQuota(1_000_000_000L,step);
                    var request=new mmm.api.transfer.TransferQuantity.Liquid(quota,OptionalDouble.empty());
                    var treatedBlock=region.origin().offset(treatedOut[0]/2,treatedOut[1]/2,treatedOut[2]/2);
                    var controlBlock=region.origin().offset(controlOut[0]/2,controlOut[1]/2,controlOut[2]/2);
                    var t=FluidWorldTargetResolver.transferCell(region,treatedBlock,Direction.EAST,request,true);
                    var c=FluidWorldTargetResolver.transferCell(region,controlBlock,Direction.EAST,request,true);
                    var host=region.hostSnapshot();
                    if(t!=null && MinecraftContainerTransferOperations.extractableNanos(host,t,quota,0,-1)>0) {
                        var a=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),treatedOut,Direction.EAST,true,quota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(a)) {
                            products.add(a);var contents=mmm.item.ContainerItemStacks.chemicalContents(a,world.chemicalRegistry());
                            treatedSamples.add(contents);treatedVolume=Math.addExact(treatedVolume,contents.totalVolumeNanos());
                        }
                    }
                    if(c!=null && MinecraftContainerTransferOperations.extractableNanos(host,c,quota,0,-1)>0) {
                        var b=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),controlOut,Direction.EAST,true,quota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(b)) {
                            products.add(b);var contents=mmm.item.ContainerItemStacks.chemicalContents(b,world.chemicalRegistry());
                            controlSamples.add(contents);controlVolume=Math.addExact(controlVolume,contents.totalVolumeNanos());
                        }
                    }
                    if(earlyStep<0 && treatedVolume>=sampleVolume && controlVolume>=sampleVolume)earlyStep=step;
                    if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(treated)){treated=catalysisFeed(world);supplied=Math.addExact(supplied,chemicalMass(treated,world));}
                    if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(control)){control=catalysisFeed(world);supplied=Math.addExact(supplied,chemicalMass(control,world));}
                    var inA=region.origin().offset(treatedIn[0]/2,treatedIn[1]/2,treatedIn[2]/2);
                    var inB=region.origin().offset(controlIn[0]/2,controlIn[1]/2,controlIn[2]/2);
                    if(FluidWorldTargetResolver.transferCell(region,inA,Direction.WEST,request,false)!=null)
                        treated=fixture.transferBox(treated,treatedIn,Direction.WEST,false,quota);
                    if(FluidWorldTargetResolver.transferCell(region,inB,Direction.WEST,request,false)!=null)
                        control=fixture.transferBox(control,controlIn,Direction.WEST,false,quota);
                    available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Catalysis process step rejected");
                if(earlyStep>=0)break;
            }
            motion.settlePendingPhaseWork(region);
            if(earlyStep<0)throw new AssertionError("Catalysis streams did not produce a common sample volume");
            double converted=speciesAtVolume(treatedSamples,sampleVolume,BuiltinChemicalDefinitions.HCO3_MINUS,world.chemicalRegistry())/(double)sampleVolume;
            double baseline=speciesAtVolume(controlSamples,sampleVolume,BuiltinChemicalDefinitions.HCO3_MINUS,world.chemicalRegistry())/(double)sampleVolume;
            System.out.println("catalysis_product scenario="+scenario+" step="+earlyStep+" treated="+converted+" control="+baseline);
            if(!(converted>baseline))throw new AssertionError("Catalytic contact did not accelerate hydration at step "+earlyStep+": "+converted+" / "+baseline);
            if(region.finiteThermalDevices(world.thermalRegistryFingerprint()).surfaceLoadingMassUnits()!=catalyst)throw new AssertionError("Catalyst inventory changed");
            long productMass=0;for(var batch:products)productMass=Math.addExact(productMass,chemicalMass(batch,world));
            long after=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(treated,world)+chemicalMass(control,world)+productMass;
            if(after!=Math.addExact(initial,supplied))throw new AssertionError("Catalysis changed combined mass");
            return converted-baseline;
        }
    }

    private static void catalysisClosed() throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-catalysis-closed-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.CATALYSIS);
        var region=fixture.region;var layout=IndustrialLayout.at(new BlockPos(14,64,20),IndustrialLayout.Scenario.CATALYSIS);
        int[] treatedIn=industrialPortCell(region,layout.ports().get(0)),controlIn=industrialPortCell(region,layout.ports().get(2));
        ItemStack treated=catalysisFeed(world),control=catalysisFeed(world);
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(treated,world)+chemicalMass(control,world);
        long catalyst=region.finiteThermalDevices(world.thermalRegistryFingerprint()).surfaceLoadingMassUnits();

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            for(int step=0;step<600 && (!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(treated)||!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(control));step++) {
                progress("catalysis_closed_fill",step,600);motion.settlePendingPhaseWork(region);
                long quota=IndustrialOperations.tickQuota(1_000_000_000L,step);
                if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(treated))treated=fixture.transferBox(treated,treatedIn,Direction.WEST,false,quota);
                if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(control))control=fixture.transferBox(control,controlIn,Direction.WEST,false,quota);
                available(region);if(!motion.step(region,List.of()).accepted())throw new AssertionError("Closed catalysis filling rejected");
            }
            if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(treated)||!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(control))
                throw new AssertionError("Closed catalysis did not receive equal finite batches");
            for(int step=0;step<600;step++) {
                progress("catalysis_equilibrium",step,600);
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Catalysis equilibrium step rejected");
            }
            motion.settlePendingPhaseWork(region);var late=region.hostSnapshot().captureAuthoritativeState();
            var treatedState=hydrationInventory(late,region,true);var controlState=hydrationInventory(late,region,false);
            double difference=Math.abs(treatedState.fraction()-controlState.fraction());
            System.out.println("catalysis_equilibrium treated="+treatedState+" control="+controlState+" fraction_difference="+difference);
            hydrationReactionBalance(late,region,world,true);
            hydrationReactionBalance(late,region,world,false);
            if(difference>0.01)throw new AssertionError("Closed catalytic batches differ by more than one percentage point: "+difference);
            if(processMass(late)!=initial || region.finiteThermalDevices(world.thermalRegistryFingerprint()).surfaceLoadingMassUnits()!=catalyst)
                throw new AssertionError("Closed catalysis changed total mass or catalyst inventory");
        }
    }

    private static void hydrationReactionBalance(FluidDomainState state,FluidRegion region,CommittedWorldRegistries world,boolean treated) {
        var reaction=world.chemicalRegistry().reactions().stream().filter(value->value.id().equals("mmm:co2_hydration")).findFirst().orElseThrow();
        var kinetics=reaction.kinetics();var thermal=new ThermalStateFunction(world.thermalRegistry());
        int material=world.thermalRegistry().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water");
        double forward=0,reverse=0,h=region.solverConfig().cellSize();
        for(var stock:state.liquid().physicalStocks()) {
            if((region.grid().z(stock.key().cell())<region.sizeZ()/2)!=treated || stock.mixture()==null || stock.massUnits()==0)continue;
            var amounts=stock.mixture().stableAmounts(world.chemicalRegistry());
            double volume=stock.volumeFraction()*h*h*h;
            double temperature=thermal.stateForInternalEnergy(material,stock.massKilograms(),amounts,stock.internalEnergy()).temperatureK();
            double f=kinetics.forwardRate()*Math.exp(-kinetics.forwardActivationJMol()/8.314462618*(1/temperature-1/kinetics.referenceTemperatureK()))*volume;
            double r=kinetics.reverseRate()*Math.exp(-kinetics.reverseActivationJMol()/8.314462618*(1/temperature-1/kinetics.referenceTemperatureK()))*volume;
            for(var term:reaction.reactants().entrySet())f*=Math.pow(amounts.getOrDefault(term.getKey(),0L)/(volume*ChemicalLimits.QUANTA_PER_MOL),term.getValue());
            for(var term:reaction.products().entrySet())r*=Math.pow(amounts.getOrDefault(term.getKey(),0L)/(volume*ChemicalLimits.QUANTA_PER_MOL),term.getValue());
            forward+=f;reverse+=r;
        }
        System.out.println("catalysis_reaction_balance treated="+treated+" forward_mol_s="+forward+" reverse_mol_s="+reverse
                +" quotient_over_equilibrium="+(forward>0?reverse/forward:Double.NaN));
        if(!(Double.isFinite(forward)&&Double.isFinite(reverse)&&forward>=0&&reverse>=0))throw new AssertionError("Invalid hydration reaction balance");
    }

    private record Hydration(long carbon,long bicarbonate) { double fraction(){return bicarbonate/(double)carbon;} }

    private static Hydration hydrationInventory(FluidDomainState state,FluidRegion region,boolean treated) {
        long co2=0,bicarbonate=0;
        for(var stock:state.liquid().physicalStocks()) {
            int z=region.grid().z(stock.key().cell());if((z<region.sizeZ()/2)!=treated || stock.mixture()==null)continue;
            var species=stock.mixture().stableAmounts(state.chemicalRegistry());
            co2=Math.addExact(co2,species.getOrDefault(BuiltinChemicalDefinitions.CO2,0L));
            bicarbonate=Math.addExact(bicarbonate,species.getOrDefault(BuiltinChemicalDefinitions.HCO3_MINUS,0L));
        }
        if(co2+bicarbonate==0)throw new AssertionError("Catalysis comparison has no liquid carbon");
        return new Hydration(co2+bicarbonate,bicarbonate);
    }
}
