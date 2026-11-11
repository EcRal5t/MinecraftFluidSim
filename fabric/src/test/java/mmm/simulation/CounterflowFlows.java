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

/** Counterflow heat exchange across a finite copper or stone partition, with an ε-NTU reference. */
final class CounterflowFlows {
    private CounterflowFlows() {}
    static void industrialHeat(int steps) throws Exception {
        var metal=counterflowRun(steps,false,false);
        var stone=counterflowRun(metal.ticks(),true,false);
        if(!(metal.temperature()>stone.temperature()))throw new AssertionError("Copper wall did not transfer more heat than stone: "+metal+" / "+stone);
    }

    private record CounterflowResult(double temperature,int ticks) { }

    static CounterflowResult counterflowRun(int steps,boolean stone,boolean diagnostic) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-counterflow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,stone?IndustrialLayout.Scenario.COUNTERFLOW_STONE:IndustrialLayout.Scenario.COUNTERFLOW);
        var region=fixture.region;
        var layout=IndustrialLayout.at(new BlockPos(14,64,20),stone?IndustrialLayout.Scenario.COUNTERFLOW_STONE:IndustrialLayout.Scenario.COUNTERFLOW);
        var hotInputPort=layout.ports().get(0);var hotOutputPort=layout.ports().get(1);
        var coldInputPort=layout.ports().get(2);var coldOutputPort=layout.ports().get(3);
        var hotDrainPort=layout.ports().get(4);
        int[] hotIn=industrialPortCell(region,hotInputPort),hotOut=industrialPortCell(region,hotOutputPort);
        int[] coldIn=industrialPortCell(region,coldInputPort),coldOut=industrialPortCell(region,coldOutputPort);
        ItemStack hot=saltedWater(world,360),cold=pureWater(world,280);
        var hotProducts=new ArrayList<ItemStack>();var coldProducts=new ArrayList<ItemStack>();
        var vented=new ArrayList<ItemStack>();
        var transfers=new TransferEnergyTotals();
        boolean hotFlow=false,coldFlow=false;
        double hotInputEnergy=0,coldInputEnergy=0,referenceColdHeat=0;
        int operatingSteps=0;
        long hotCharge=0,coldCharge=0;
        long hotCapacity=CounterflowLayout.chargeNanos(layout,Direction.NORTH),coldCapacity=CounterflowLayout.chargeNanos(layout,Direction.SOUTH);
        double startupHot=Double.NaN,startupCold=Double.NaN;
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(hot,world)+chemicalMass(cold,world),supplied=0;
        var apparatus=diagnostic?new ApparatusDiagnostics(region,world,layout):null;

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            int runSteps=diagnostic?steps:steps>20?steps:6000;
            int completedSteps=0;
            for(int step=0;step<runSteps;step++) {
                progress("counterflow",step,runSteps);
                double coldTransferred=0;
                {
                    motion.settlePendingPhaseWork(region);
                    fixture.beginTransfers();
                    var transferAudit=fixture.transferState().thermalAudit();
                    counterflowVent(fixture,region,layout,vented,step,!hotFlow,!coldFlow);
                    {
                        if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(hot)){hot=saltedWater(world,360);supplied=Math.addExact(supplied,chemicalMass(hot,world));}
                        double before=liquidBatchEnergy(hot);
                        long volumeBefore=counterflowBatchVolume(hot,world);
                        long inputQuota=hotFlow&&coldFlow?IndustrialOperations.tickQuota(2_000_000L,step):Math.min(hotCapacity-hotCharge,IndustrialOperations.tickQuota(hotInputPort.rate(),step));
                        if(inputQuota>0)hot=fixture.transferBox(hot,hotIn,hotInputPort.outward(),false,inputQuota);
                        if(!hotFlow||!coldFlow)hotCharge+=volumeBefore-counterflowBatchVolume(hot,world);
                        hotInputEnergy+=before-liquidBatchEnergy(hot);
                    }
                    {
                        if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(cold)){cold=pureWater(world,280);supplied=Math.addExact(supplied,chemicalMass(cold,world));}
                        double before=liquidBatchEnergy(cold);
                        long volumeBefore=counterflowBatchVolume(cold,world);
                        long inputQuota=hotFlow&&coldFlow?IndustrialOperations.tickQuota(2_000_000L,step):Math.min(coldCapacity-coldCharge,IndustrialOperations.tickQuota(coldInputPort.rate(),step));
                        if(inputQuota>0)cold=fixture.transferBox(cold,coldIn,coldInputPort.outward(),false,inputQuota);
                        if(!hotFlow||!coldFlow)coldCharge+=volumeBefore-counterflowBatchVolume(cold,world);
                        coldInputEnergy+=before-liquidBatchEnergy(cold);
                    }
                    long quota=IndustrialOperations.tickQuota(hotFlow&&coldFlow?2_000_000L:1_000_000_000L,step);
                    hotFlow=hotCharge>=hotCapacity;
                    coldFlow=coldCharge>=coldCapacity;
                    if(hotFlow && coldFlow && Double.isNaN(startupHot)) {
                        var snapshot=fixture.transferState();
                        startupHot=counterflowLiquidVolume(snapshot,region,layout,Direction.NORTH);
                        startupCold=counterflowLiquidVolume(snapshot,region,layout,Direction.SOUTH);
                    }
                    if(hotFlow && coldFlow)quota=IndustrialOperations.tickQuota(2_000_000L,step);
                    if(hotFlow && coldFlow) {
                        var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),hotOut,hotOutputPort.outward(),true,quota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(batch))hotProducts.add(batch);
                    }
                    if(hotFlow && coldFlow) {
                        var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),coldOut,coldOutputPort.outward(),true,quota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(batch)){coldProducts.add(batch);coldTransferred=Mass.kilograms(chemicalMass(batch,world));}
                    }
                    transfers.add(transferAudit,fixture.transferState().thermalAudit());fixture.commitTransfers();available(region);
                }
                if(hotFlow&&coldFlow)operatingSteps++;
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Counterflow process step rejected");
                if(hotFlow&&coldFlow)referenceColdHeat+=counterflowColdPower(region,world,layout,stone,coldTransferred*20)*.05;
                completedSteps=step+1;
                if(diagnostic && completedSteps%400==0)apparatus.print("counterflow",2e5);
                if(completedSteps%100==0)System.out.println("counterflow_progress seconds="+(completedSteps/20.0)+" charged_nanos="+hotCharge+"/"+coldCharge
                        +" operating="+(hotFlow&&coldFlow)+" latest_cold_K="+(coldProducts.isEmpty()?0:counterflowProducts(List.of(coldProducts.getLast()),world).temperature()));
                if(!diagnostic && !stone && !coldProducts.isEmpty()
                        && counterflowProducts(List.of(coldProducts.getLast()),world).temperature()>=285)break;
            }
            motion.settlePendingPhaseWork(region);
            if(!diagnostic && (hotProducts.isEmpty()||coldProducts.isEmpty()))throw new AssertionError("Counterflow streams did not reach their output ports");
            var hotOutput=counterflowProducts(hotProducts,world);
            var coldOutput=counterflowProducts(coldProducts,world);
            double hotTemperature=hotOutput.temperature();
            double coldTemperature=coldOutput.temperature();
            double latestCold=coldProducts.isEmpty()?Double.NaN:counterflowProducts(List.of(coldProducts.getLast()),world).temperature();
            var finalHost=region.hostSnapshot();
            double hotHold=counterflowLiquidVolume(finalHost,region,layout,Direction.NORTH);
            double coldHold=counterflowLiquidVolume(finalHost,region,layout,Direction.SOUTH);
            double seconds=operatingSteps/20.0;
            double hotRate=seconds>0?hotProducts.stream().mapToLong(batch->counterflowBatchVolume(batch,world)).sum()*1e-9/seconds:0;
            double coldRate=seconds>0?coldProducts.stream().mapToLong(batch->counterflowBatchVolume(batch,world)).sum()*1e-9/seconds:0;
            System.out.printf(Locale.ROOT,"counterflow_operation startup_s=%.3f operation_s=%.3f target_m3=%.9g/%.9g charged_m3=%.9g/%.9g startup_hold_m3=%.9g/%.9g final_hold_m3=%.9g/%.9g output_m3_per_s=%.9g/%.9g residence_s=%.9g/%.9g latest_cold_K=%.9g latest_cold_rise_K=%.9g%n",
                    (completedSteps-operatingSteps)/20.0,seconds,hotCapacity*1e-9,coldCapacity*1e-9,hotCharge*1e-9,coldCharge*1e-9,startupHot,startupCold,hotHold,coldHold,hotRate,coldRate,
                    hotRate>0?hotHold/hotRate:Double.NaN,coldRate>0?coldHold/coldRate:Double.NaN,latestCold,latestCold-280);
            long hotSalt=hotOutput.salt(),coldSalt=coldOutput.salt();
            System.out.println("counterflow_batches stone="+stone+" hot_input_u="+hotInputEnergy+" cold_input_u="+coldInputEnergy
                    +" hot="+hotOutput+" cold="+coldOutput);
            counterflowTheory(region,world,layout,stone,Math.max(operatingSteps,1),hotOutput,coldOutput);
            System.out.printf(Locale.ROOT,"counterflow_heat_window stone=%s full_wet_reference_J=%.9g product_sensible_J=%.9g%n",
                    stone,referenceColdHeat,coldOutput.mass()*world.thermalRegistry().material(world.thermalRegistry().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water")).specificHeatAt(280)*(coldOutput.temperature()-280));
            if(!diagnostic && !stone && !(latestCold>=285))throw new AssertionError("Counterflow cold outlet rise below 5 K: "+latestCold);
            if(!diagnostic && !(hotTemperature<360 && (stone || coldTemperature>280) && hotSalt>0 && coldSalt==0))
                throw new AssertionError("Counterflow output: hot="+hotTemperature+" cold="+coldTemperature+" salt="+hotSalt+"/"+coldSalt);
            long productMass=0;for(var batch:hotProducts)productMass=Math.addExact(productMass,chemicalMass(batch,world));for(var batch:coldProducts)productMass=Math.addExact(productMass,chemicalMass(batch,world));
            long gasMass=0;double gasEnergy=0;var gasComponents=new TreeMap<String,Long>();
            for(var batch:vented){var contents=mmm.item.GasContainerItem.contents(batch);gasMass=Math.addExact(gasMass,contents.inventory().totalMassUnits());gasEnergy+=contents.internalEnergy();for(var component:contents.inventory().components())gasComponents.merge(component.componentId(),component.standardVolumeNanos(),Math::addExact);}
            System.out.println("counterflow_vent mass="+gasMass+" energy="+gasEnergy+" components="+gasComponents);
            double productEnergy=hotOutput.energy()+coldOutput.energy();
            var finalState=region.hostSnapshot();
            long after=processMass(finalState.captureAuthoritativeState())+chemicalMass(hot,world)+chemicalMass(cold,world)+productMass+gasMass;
            if(after!=Math.addExact(initial,supplied))throw new AssertionError("Counterflow changed combined mass");
            if(!finalState.thermalAudit().acceptsInventory(finalState.exactInternalEnergy()))throw new AssertionError("Counterflow fluid energy audit did not close");
            requireEnergyTotal(transfers.source.toDouble(),hotInputEnergy+coldInputEnergy,"Counterflow incoming batch energy");
            requireEnergyTotal(transfers.sink.toDouble(),productEnergy+gasEnergy,"Counterflow outgoing batch energy");
            if(diagnostic)return new CounterflowResult(coldTemperature,completedSteps);
            double beforeStop=counterflowWallEnergy(region,world,layout);
            double peakWallEnergy=beforeStop;
            var activeDrain=new IndustrialLayout.Port(hotDrainPort.position(),hotDrainPort.outward(),mmm.block.FluidPortBlockEntity.Mode.OUTPUT,
                    hotDrainPort.phase(),hotDrainPort.rate(),hotDrainPort.redstone());
            for(int step=0;step<600;step++) {
                progress("counterflow_after_stop",step,600);
                motion.settlePendingPhaseWork(region);
                fixture.beginTransfers();
                var transferAudit=fixture.transferState().thermalAudit();
                long quota=IndustrialOperations.tickQuota(2_000_000L,step);
                if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(cold)){cold=pureWater(world,280);supplied=Math.addExact(supplied,chemicalMass(cold,world));}
                double before=liquidBatchEnergy(cold);
                cold=fixture.transferBox(cold,coldIn,coldInputPort.outward(),false,quota);
                coldInputEnergy+=before-liquidBatchEnergy(cold);
                for(var port:List.of(activeDrain,hotOutputPort,coldOutputPort)) {
                    var cell=industrialPortCell(region,port);
                    var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),cell,port.outward(),true,port==activeDrain?IndustrialOperations.tickQuota(activeDrain.rate(),step):quota);
                    productMass=Math.addExact(productMass,chemicalMass(batch,world));productEnergy+=liquidBatchEnergy(batch);
                }
                transfers.add(transferAudit,fixture.transferState().thermalAudit());fixture.commitTransfers();available(region);
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Counterflow after hot feed stopped rejected");
                peakWallEnergy=Math.max(peakWallEnergy,counterflowWallEnergy(region,world,layout));
            }
            motion.settlePendingPhaseWork(region);
            double afterStop=counterflowWallEnergy(region,world,layout);
            System.out.println("counterflow_after_stop stone="+stone+" wall_energy_initial="+beforeStop
                    +" wall_energy_peak="+peakWallEnergy+" wall_energy_final="+afterStop+" released="+(peakWallEnergy-afterStop));
            if(!(afterStop<peakWallEnergy))throw new AssertionError("Finite wall did not release stored heat after hot feed stopped");
            gasMass=0;for(var batch:vented)gasMass=Math.addExact(gasMass,gasMass(batch));
            var stoppedState=region.hostSnapshot();
            long stopped=processMass(stoppedState.captureAuthoritativeState())+chemicalMass(hot,world)+chemicalMass(cold,world)+productMass+gasMass;
            if(stopped!=Math.addExact(initial,supplied))throw new AssertionError("Counterflow after hot feed stopped changed combined mass");
            if(!stoppedState.thermalAudit().acceptsInventory(stoppedState.exactInternalEnergy()))throw new AssertionError("Counterflow after hot feed stopped energy audit did not close");
            requireEnergyTotal(transfers.source.toDouble(),hotInputEnergy+coldInputEnergy,"Counterflow incoming batch energy after stop");
            requireEnergyTotal(transfers.sink.toDouble(),productEnergy+gasEnergy,"Counterflow outgoing batch energy after stop");
            return new CounterflowResult(coldTemperature,completedSteps);
        }
    }

    private static double counterflowWallEnergy(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout) {
        var wallIds=layout.walls().stream().map(wall->wall.position().asLong()+"@").toList();
        return region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream()
                .filter(device->(device.providerId().equals(WorldThermalEnvironment.PROVIDER)
                        && wallIds.stream().anyMatch(device.objectId()::startsWith))
                        || (device.providerId().equals(FiniteHeatExchangerOperations.PROVIDER)
                        && layout.walls().stream().anyMatch(wall->device.objectId().equals(Long.toString(wall.position().asLong())))))
                .mapToDouble(device->device.body().internalEnergyJ()).sum();
    }

    private static void counterflowVent(GpuControllerFixture fixture,FluidRegion region,IndustrialLayout layout,List<ItemStack> products,int tick,boolean hotOpen,boolean coldOpen) throws Exception {
        for(var port:layout.ports())if(port.phase()==mmm.block.FluidPortBlockEntity.Phase.GAS) {
            if(port.position().getZ()==layout.ports().getFirst().position().getZ()?!hotOpen:!coldOpen)continue;
            int[] cell=industrialPortCell(region,port);
            var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),cell,port.outward(),true,IndustrialOperations.tickQuota(port.rate(),tick));
            if(mmm.item.GasContainerItem.contents(batch)!=null)products.add(batch);
        }
    }

    private static long counterflowBatchVolume(ItemStack batch,CommittedWorldRegistries world) {
        return mmm.item.MaterialBucketItem.isEmptyMaterialBucket(batch)?0:
                mmm.item.ContainerItemStacks.chemicalContents(batch,world.chemicalRegistry()).totalVolumeNanos();
    }

    private static double counterflowLiquidVolume(mmm.fluid.HostFluidState host,FluidRegion region,IndustrialLayout layout,Direction side) {
        double volume=0;int scale=FluidRegionFactory.CELLS_PER_BLOCK;
        for(var wall:layout.walls()) {
            var block=wall.position().relative(side);
            int x0=(block.getX()-region.origin().getX())*scale,z0=(block.getZ()-region.origin().getZ())*scale;
            for(int x=x0;x<x0+scale;x++)for(int z=z0;z<z0+scale;z++)for(int y=0;y<region.sizeY();y++) {
                var liquid=host.observeCellInventory(x,y,z);
                if(liquid!=null)volume+=liquid.liquidVolumeCubicMetres();
            }
        }
        return volume;
    }

    private static double liquidBatchEnergy(ItemStack batch) {
        var thermal=mmm.item.ContainerItemStacks.thermalContents(batch);return thermal==null?0:thermal.internalEnergy();
    }

    private record CounterflowProducts(double mass,double temperature,double energy,long salt) {}

    private static void counterflowTheory(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout,boolean stone,int steps,
                                          CounterflowProducts hot,CounterflowProducts cold) {
        var registry=world.thermalRegistry();
        String wallId=stone?"mmm:ordinary_stone_wall":FiniteHeatExchangerOperations.PROVIDER;
        var wall=registry.finiteDevices().stream().filter(device->device.id().equals(wallId)).findFirst().orElseThrow();
        var wallMaterial=registry.material(registry.materialHandle(wall.thermalMaterialId()));
        var water=registry.material(registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water"));
        var north=wall.ports().stream().filter(port->port.localFace()==FiniteThermalDeviceDefinition.LocalFace.NORTH).findFirst().orElseThrow();
        var south=wall.ports().stream().filter(port->port.localFace()==FiniteThermalDeviceDefinition.LocalFace.SOUTH).findFirst().orElseThrow();
        double area=layout.walls().size();
        double seconds=steps/20.0,cp=water.specificHeatAt(320),hotRate=hot.mass()/seconds*cp,coldRate=cold.mass()/seconds*cp;
        double wallT=counterflowWallTemperature(region,world,layout,wall,wallMaterial);
        double hotFilm=counterflowHeatCoefficient(region,world,layout,360,wallT,hot.mass()/seconds);
        double coldFilm=counterflowHeatCoefficient(region,world,layout,280,wallT,cold.mass()/seconds);
        double hotResistance=north.wallThicknessMeters()/wallMaterial.conductivityWPerMeterK()+1/hotFilm;
        double coldResistance=south.wallThicknessMeters()/wallMaterial.conductivityWPerMeterK()+1/coldFilm;
        double hotG=area/hotResistance,coldG=area/coldResistance,ua=area/(hotResistance+coldResistance);
        double cmin=Math.min(hotRate,coldRate),cmax=Math.max(hotRate,coldRate),effectiveness=0;
        if(cmin>0) {
            double ratio=cmin/cmax,ntu=ua/cmin;
            if(Math.abs(1-ratio)<1e-12)effectiveness=ntu/(1+ntu);
            else {double change=-Math.expm1(-ntu*(1-ratio));effectiveness=change/(1-ratio+ratio*change);}
        }
        double steadyColdRise=coldRate>0?effectiveness*cmin*80/coldRate:0;
        double wallCapacity=layout.walls().size()*Mass.kilograms(wall.bodyMassUnits())*wallMaterial.specificHeatAt(320);
        double coldPower=coldG*(wallT-280);
        System.out.printf(Locale.ROOT,"counterflow_theory stone=%s area_full_m2=%.3f wall_temperature_K=%.9g hot_film_W_per_m2_K=%.9g cold_film_W_per_m2_K=%.9g hot_resistance=%.9g cold_resistance=%.9g UA_W_per_K=%.9g hot_window_capacity_W_per_K=%.9g cold_window_capacity_W_per_K=%.9g steady_cold_rise_K=%.9g wall_capacity_J_per_K=%.9g wall_tau_s=%.9g wall_to_cold_W=%.9g observed_cold_rise_K=%.9g%n",
                stone,area,wallT,hotFilm,coldFilm,hotResistance,coldResistance,ua,hotRate,coldRate,steadyColdRise,wallCapacity,wallCapacity/(hotG+coldG),coldPower,cold.temperature()-280);
    }

    private static double counterflowWallTemperature(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout,
                                                      FiniteThermalDeviceDefinition wall,ThermalMaterialDefinition material) {
        double specific=counterflowWallEnergy(region,world,layout)/(layout.walls().size()*Mass.kilograms(wall.bodyMassUnits()));
        return material.referenceTemperatureK()+(specific-material.referenceSpecificEnergyJPerKg())/material.specificHeatAt(320);
    }

    private static double counterflowHeatCoefficient(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout,
                                                      double liquidT,double wallT,double massRate) {
        var registry=world.thermalRegistry();var water=registry.material(registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water"));
        var fluid=world.materialRegistry().entry("mmm:water");double rho=fluid.material().referenceDensity(),mu=fluid.dynamicViscosityAt(liquidT);
        double cp=water.specificHeatAt(liquidT),k=water.conductivityWPerMeterK(),length=region.solverConfig().cellSize();
        var expansion=fluid.thermalExpansion();double beta=expansion.volumetricCoefficientPerK()/(1+expansion.volumetricCoefficientPerK()*(liquidT-expansion.referenceTemperatureK()));
        int minX=layout.walls().stream().mapToInt(w->w.position().getX()).min().orElseThrow(),maxX=layout.walls().stream().mapToInt(w->w.position().getX()).max().orElseThrow();
        double crossSection=.25*layout.walls().size()/(double)(maxX-minX+1),velocity=massRate/(rho*crossSection);
        double pr=mu*cp/k,ra=region.solverConfig().gravity()*Math.abs(wallT-liquidT)*beta*length*length*length*rho*rho*cp/(mu*k);
        double natural=ra>0?Math.pow(.825+.387*Math.pow(ra,1.0/6)/Math.pow(1+Math.pow(.492/pr,9.0/16),8.0/27),2):0;
        double re=rho*velocity*length/mu,critical=500000,forced=re<=critical?.664*Math.sqrt(re)*Math.cbrt(pr):
                (.664*Math.sqrt(critical)+.037*(Math.pow(re,.8)-Math.pow(critical,.8)))*Math.cbrt(pr);
        return Math.max(k/(length/2),k*Math.cbrt(natural*natural*natural+forced*forced*forced)/length);
    }

    private static double counterflowColdPower(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout,boolean stone,double coldRate) {
        var registry=world.thermalRegistry();String id=stone?"mmm:ordinary_stone_wall":FiniteHeatExchangerOperations.PROVIDER;
        var wall=registry.finiteDevices().stream().filter(device->device.id().equals(id)).findFirst().orElseThrow();
        var material=registry.material(registry.materialHandle(wall.thermalMaterialId()));
        var port=wall.ports().stream().filter(value->value.localFace()==FiniteThermalDeviceDefinition.LocalFace.SOUTH).findFirst().orElseThrow();
        double wallT=counterflowWallTemperature(region,world,layout,wall,material);
        double film=counterflowHeatCoefficient(region,world,layout,280,wallT,coldRate);
        return layout.walls().size()*(wallT-280)/(port.wallThicknessMeters()/material.conductivityWPerMeterK()+1/film);
    }

    private static CounterflowProducts counterflowProducts(List<ItemStack> products,CommittedWorldRegistries world) {
        double weighted=0,mass=0,energy=0;long salt=0;
        for(var batch:products) {
            var contents=mmm.item.ContainerItemStacks.chemicalContents(batch,world.chemicalRegistry());
            salt=Math.addExact(salt,chemicalAmount(contents,BuiltinChemicalDefinitions.NACL)+chemicalAmount(contents,BuiltinChemicalDefinitions.NA_PLUS));
            var thermal=mmm.item.ContainerItemStacks.thermalContents(batch);double kilograms=Mass.kilograms(contents.totalMassUnits());
            weighted+=kilograms*LiquidContainerThermodynamics.temperatureK(world.thermalRegistry(),thermal,contents);mass+=kilograms;energy+=thermal.internalEnergy();
        }
        return new CounterflowProducts(mass,weighted/mass,energy,salt);
    }
}
