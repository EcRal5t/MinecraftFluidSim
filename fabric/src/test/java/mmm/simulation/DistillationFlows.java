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

/** Batch distillation in the compact still, compared with a Rayleigh equilibrium cut. */
final class DistillationFlows {
    private DistillationFlows() {}
    private record Distillate(ItemStack product,double ethanolFraction,double bottomsFraction,double seconds,double heatIn) { }

    private static final class DistillationInventory {
        final Map<String,Long> species=new TreeMap<>();
        double energy;
        void addSpecies(String id,long amount){if(amount!=0)species.merge(id,amount,Math::addExact);}
        void addLiquid(ItemStack stack,CommittedWorldRegistries world){
            if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(stack))return;
            var contents=mmm.item.ContainerItemStacks.chemicalContents(stack,world.chemicalRegistry());
            for(var group:contents.groups())for(var item:group.species())addSpecies(item.speciesId(),item.amountQuanta());
            var thermal=mmm.item.ContainerItemStacks.thermalContents(stack);
            if(thermal==null)throw new AssertionError("Distillation liquid batch has no thermal inventory");
            energy+=thermal.internalEnergy();
        }
        void addGas(mmm.transfer.GasContents gas){
            for(var component:gas.inventory().components())for(var item:component.species())addSpecies(item.speciesId(),item.amountQuanta());
            energy+=gas.internalEnergy();
        }
        void addRegion(FluidDomainState state){
            for(var stock:state.liquid().physicalStocks())if(stock.mixture()!=null)
                stock.mixture().stableAmounts(state.chemicalRegistry()).forEach(this::addSpecies);
            for(var gas:state.spatialGas().cells())for(var component:gas.components())for(var item:component.species())addSpecies(item.speciesId(),item.amountQuanta());
            for(var group:state.thermalOwner().coherentGroups())if(group.composition()!=null)
                group.composition().stableAmounts().forEach(this::addSpecies);
        }
        Map<String,Long> elements(ChemicalRegistry registry){
            var result=new TreeMap<String,Long>();
            species.forEach((id,amount)->{
                var definition=registry.species(registry.speciesHandle(id));
                definition.composition().forEach((element,count)->result.merge(element,Math.multiplyExact(amount,count.longValue()),Math::addExact));
                result.merge("@charge",Math.multiplyExact(amount,(long)definition.charge()),Math::addExact);
            });
            result.values().removeIf(value->value==0);return result;
        }
    }

    static void industrialDistillation(int steps) throws Exception {
        var first=distillationRun(IndustrialLayout.Scenario.DISTILLATION_SINGLE,Math.max(2400,steps),false,null);
        var second=distillationRun(IndustrialLayout.Scenario.DISTILLATION,Math.max(2400,steps),false,first.product());
        System.out.println("distillation_cascade first_x="+first.ethanolFraction()+" second_x="+second.ethanolFraction()
                +" first_nanos="+Math.max(0,mmm.item.MaterialBucketItem.volume(first.product()))+" second_nanos="+Math.max(0,mmm.item.MaterialBucketItem.volume(second.product()))
                +" seconds="+(first.seconds()+second.seconds())+" heat_in_J="+(first.heatIn()+second.heatIn()));
        if(second.ethanolFraction()<first.ethanolFraction()+.10)
            throw new AssertionError("Second distillation needs a visible enrichment: "+first.ethanolFraction()+" -> "+second.ethanolFraction());
    }

    static void industrialDistillationSingle(int steps) throws Exception {
        var cold=distillationRun(IndustrialLayout.Scenario.DISTILLATION_SINGLE,Math.max(2400,steps),false,null);
        var warm=distillationRun(IndustrialLayout.Scenario.DISTILLATION_WARM,(int)Math.round(cold.seconds()*20),true,null);
        if(Math.max(0,mmm.item.MaterialBucketItem.volume(warm.product()))>=Math.max(0,mmm.item.MaterialBucketItem.volume(cold.product())))
            throw new AssertionError("Warm condenser must collect less liquid");
    }

    static Distillate distillationRun(IndustrialLayout.Scenario scenario,int maximumTicks,boolean diagnostic,ItemStack suppliedFeed) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-distillation-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,scenario);
        var region=fixture.region;var layout=IndustrialLayout.at(new BlockPos(14,64,20),scenario);
        var sensors=new ArrayList<mmm.block.ProcessSensorBlockEntity>();
        for(var configured:layout.sensors()) {
            var sensor=new mmm.block.ProcessSensorBlockEntity(configured.position(),layout.blocks().get(configured.position()));
            sensor.configure(configured.measure(),configured.low(),configured.high(),configured.below(),false,configured.first(),configured.second());
            sensors.add(sensor);
        }
        var temperatures=ThermalBoundaryResolver.sampleField(region.origin(),region.grid(),FluidRegionFactory.CELLS_PER_BLOCK,position ->
                layout.walls().stream().filter(wall->wall.position().equals(position)&&layout.blocks().get(position).is(mmm.block.MmmBlocks.THERMAL_RESERVOIR))
                        .mapToDouble(IndustrialLayout.Wall::temperatureK).findFirst().orElse(Double.NaN));
        ItemStack feed=suppliedFeed==null?ethanolSolution(world,370,.10):suppliedFeed.copy();
        ItemStack product=new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET);
        var feedContents=mmm.item.ContainerItemStacks.chemicalContents(feed,world.chemicalRegistry());
        long feedVolume=feedContents.totalVolumeNanos(),targetVolume=feedVolume/5;
        double feedFraction=liquidEthanolFraction(feedContents);

        var productPort=layout.ports().get(1);int[] productCell=industrialPortCell(region,productPort);
        var initialState=region.hostSnapshot().captureAuthoritativeState();
        long initialMass=processMass(initialState)+chemicalMass(feed,world),ventedMass=0,ventedVolume=0;
        var inputs=new DistillationInventory();inputs.addRegion(initialState);inputs.addLiquid(feed,world);
        var outputs=new DistillationInventory();var transfers=new TransferEnergyTotals();
        var initialAudit=initialState.thermalOwner().audit();
        double initialWallEnergy=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().mapToDouble(value->value.body().internalEnergyJ()).sum();
        var apparatus=new ApparatusDiagnostics(region,world,layout);
        int tick=0;

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);region.replaceThermalBoundaryTemperatures(temperatures);
            while(tick<maximumTicks && (diagnostic || Math.max(0,mmm.item.MaterialBucketItem.volume(product))<targetVolume)) {
                motion.settlePendingPhaseWork(region);
                if(tick%4==0)for(var sensor:sensors)IndustrialOperations.observeSensor(region,sensor,sensor.first(),sensor.second());
                fixture.beginTransfers();var before=fixture.transferState().thermalAudit();
                for(var vent:layout.ports())if(vent.phase()==mmm.block.FluidPortBlockEntity.Phase.GAS && industrialPortPowered(vent,sensors)) {
                    var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),industrialPortCell(region,vent),vent.outward(),true,IndustrialOperations.tickQuota(vent.rate(),tick));
                    var contents=mmm.item.GasContainerItem.contents(batch);
                    if(contents!=null){outputs.addGas(contents);ventedMass=Math.addExact(ventedMass,contents.inventory().totalMassUnits());ventedVolume+=contents.inventory().standardVolumeNanos();}
                }
                var inlet=layout.ports().getFirst();
                if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(feed))
                    feed=fixture.transferBox(feed,fixture.portCell,inlet.outward(),false,IndustrialOperations.tickQuota(inlet.rate(),tick));
                if(industrialPortPowered(productPort,sensors)) {
                    long quota=IndustrialOperations.tickQuota(productPort.rate(),tick);
                    product=fixture.transferBox(product,productCell,productPort.outward(),true,quota);
                }
                transfers.add(before,fixture.transferState().thermalAudit());fixture.commitTransfers();available(region);
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Distillation physical step rejected");
                tick++;
                if(tick==100) {
                    motion.settlePendingPhaseWork(region);
                    var savedState=region.hostSnapshot().captureAuthoritativeState();
                    long saved=operations.checkpoint(region);
                    if(!region.releaseSolverAfterCheckpoint(saved))throw new AssertionError("Distillation checkpoint did not unload");
                    var r=startup.registries();
                    var repository=new RegionRepositoryAdapter(startup.worldRoot(),world.profile(),r.chemical(),r.granular(),r.thermal(),r.gas(),startup.config().maximumThermalSubstepsPerStep(),r.material());
                    if(!repository.restoreIncrementally(region,0,Integer.MAX_VALUE).restored())throw new AssertionError("Distillation checkpoint did not restore");
                    var restored=region.hostSnapshot().captureAuthoritativeState();
                    if(!savedState.liquid().physicalStocks().equals(restored.liquid().physicalStocks())
                            || !savedState.spatialGas().cells().equals(restored.spatialGas().cells())
                            || !Arrays.equals(savedState.spatialGas().internalEnergy(),restored.spatialGas().internalEnergy())
                            || !Arrays.equals(savedState.momentum().copyX(),restored.momentum().copyX())
                            || !Arrays.equals(savedState.momentum().copyY(),restored.momentum().copyY())
                            || !Arrays.equals(savedState.momentum().copyZ(),restored.momentum().copyZ()))
                        throw new AssertionError("Distillation checkpoint changed fluid inventory or momentum");
                    fixture.restoreThermal();
                    region.replaceThermalBoundaryTemperatures(temperatures);
                    region.ensureDeviceResident();region.setPaused(false);available(region);
                    System.out.println("distillation_checkpoint tick="+tick+" generation="+saved);
                }
                if(tick%100==0) {
                    var state=region.hostSnapshot().captureAuthoritativeState();
                    long e=0,w=0;
                    if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(product)) {
                        var contents=mmm.item.ContainerItemStacks.chemicalContents(product,world.chemicalRegistry());
                        e=chemicalAmount(contents,BuiltinChemicalDefinitions.ETHANOL);w=chemicalAmount(contents,BuiltinChemicalDefinitions.H2O);
                    }
                    requireDistillationCondensate(state.spatialGas(),e,w);
                    if(diagnostic) {
                        for(var port:List.of(layout.ports().getFirst(),productPort)) {
                            var block=port.position().relative(port.outward().getOpposite());double mass=0,temperature=0,volume=0;long vapor=0,gasTotal=0;
                            var host=region.hostSnapshot();
                            for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++) {
                                var liquid=host.observeCellInventory(2*(block.getX()-region.origin().getX())+dx,2*(block.getY()-region.origin().getY())+dy,2*(block.getZ()-region.origin().getZ())+dz);
                                int cell=region.grid().cell(2*(block.getX()-region.origin().getX())+dx,2*(block.getY()-region.origin().getY())+dy,2*(block.getZ()-region.origin().getZ())+dz);
                                var gas=state.spatialGas().cells().get(cell);gasTotal+=gas.standardVolumeNanos();
                                for(var component:gas.components())if(component.componentId().equals(BuiltinGasDefinitions.ETHANOL_VAPOR)||component.componentId().equals(BuiltinGasDefinitions.WATER_VAPOR))vapor+=component.standardVolumeNanos();
                                if(liquid==null)continue;
                                mass+=liquid.liquidMassKilograms();volume+=liquid.liquidVolumeCubicMetres();
                                if(liquid.temperatureKelvin().isPresent())temperature+=liquid.temperatureKelvin().getAsDouble()*liquid.liquidMassKilograms();
                            }
                            System.out.println("distillation_liquid port="+port.mode()+" volume_m3="+volume+" mass_kg="+mass+" temperature_K="+(mass>0?temperature/mass:0)+" vapor_nanos="+vapor+" gas_nanos="+gasTotal);
                        }
                        for(var sensor:sensors)System.out.println("distillation_control measure="+sensor.measure()+" signal="+sensor.output());
                        System.out.println("distillation_condensed ethanol="+gasPhaseSinkQuanta(state.spatialGas(),BuiltinGasDefinitions.ETHANOL_VAPOR,BuiltinChemicalDefinitions.ETHANOL)
                                +" water="+gasPhaseSinkQuanta(state.spatialGas(),BuiltinGasDefinitions.WATER_VAPOR,BuiltinChemicalDefinitions.H2O));
                        if(tick%400==0)apparatus.print("distillation",2e5);
                    }
                    System.out.println("distillation_progress scenario="+scenario+" seconds="+(tick/20.0)+" product_nanos="+Math.max(0,mmm.item.MaterialBucketItem.volume(product))
                            +" target_nanos="+targetVolume+" product_x="+(e+w>0?e/(double)(e+w):0)
                            +" heat_in_J="+state.thermalOwner().audit().externalSource().subtract(initialAudit.externalSource()).subtract(transfers.source).toDouble());
                }
            }
            motion.settlePendingPhaseWork(region);
            var finalState=region.hostSnapshot().captureAuthoritativeState();
            long productMass=chemicalMass(product,world),productVolume=Math.max(0,mmm.item.MaterialBucketItem.volume(product));
            long after=processMass(finalState)+chemicalMass(feed,world)+productMass+ventedMass;
            if(after!=initialMass)throw new AssertionError("Distillation changed combined mass");
            var remaining=new DistillationInventory();remaining.addLiquid(feed,world);
            outputs.addLiquid(product,world);outputs.addRegion(finalState);remaining.species.forEach(outputs::addSpecies);
            if(!inputs.elements(world.chemicalRegistry()).equals(outputs.elements(world.chemicalRegistry())))
                throw new AssertionError("Distillation changed combined elements");
            if(!Objects.equals(inputs.species.getOrDefault(BuiltinChemicalDefinitions.ETHANOL,0L),outputs.species.getOrDefault(BuiltinChemicalDefinitions.ETHANOL,0L)))
                throw new AssertionError("Distillation changed combined ethanol inventory");
            var host=region.hostSnapshot();
            if(!host.thermalAudit().acceptsInventory(host.exactInternalEnergy()))throw new AssertionError("Distillation fluid energy audit did not close");
            requireEnergyTotal(transfers.source.toDouble(),inputs.energy-remaining.energy,"Distillation incoming batch energy");
            requireEnergyTotal(transfers.sink.toDouble(),outputs.energy,"Distillation outgoing batch energy");
            double wallEnergy=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().mapToDouble(value->value.body().internalEnergyJ()).sum();
            double deviceExchange=finalState.thermalOwner().audit().finiteDeviceExchange().subtract(initialAudit.finiteDeviceExchange()).toDouble();
            requireEnergyTotal(wallEnergy+deviceExchange,initialWallEnergy,"Distillation finite wall energy");
            long ethanol=0,water=0,bottomEthanol=0,bottomWater=0;
            if(productVolume>0) {
                var contents=mmm.item.ContainerItemStacks.chemicalContents(product,world.chemicalRegistry());
                ethanol=chemicalAmount(contents,BuiltinChemicalDefinitions.ETHANOL);water=chemicalAmount(contents,BuiltinChemicalDefinitions.H2O);
            }
            for(var stock:finalState.liquid().physicalStocks())if(stock.mixture()!=null) {
                var amounts=stock.mixture().stableAmounts(world.chemicalRegistry());
                bottomEthanol+=amounts.getOrDefault(BuiltinChemicalDefinitions.ETHANOL,0L);bottomWater+=amounts.getOrDefault(BuiltinChemicalDefinitions.H2O,0L);
            }
            double fraction=ethanol+water>0?ethanol/(double)(ethanol+water):0;
            double bottomFraction=bottomEthanol+bottomWater>0?bottomEthanol/(double)(bottomEthanol+bottomWater):0;
            double heatIn=finalState.thermalOwner().audit().externalSource().subtract(initialAudit.externalSource()).subtract(transfers.source).toDouble();
            requireDistillationCondensate(finalState.spatialGas(),ethanol,water);
            double idealFraction=batchDistillationReference(world,feedContents,productVolume/(double)feedVolume);
            System.out.println("distillation_batch scenario="+scenario+" seconds="+(tick/20.0)+" feed_nanos="+feedVolume+" feed_x="+feedFraction
                    +" product_nanos="+productVolume+" product_x="+fraction+" ideal_batch_x="+idealFraction+" bottoms_x="+bottomFraction
                    +" volume_recovery="+(productVolume/(double)feedVolume)+" heat_in_J="+heatIn+" wall_to_fluid_J="+deviceExchange+" vented_standard_m3="+(ventedVolume*1e-9));
            if(!diagnostic && (productVolume<targetVolume || fraction<feedFraction+.10 || bottomFraction>=feedFraction))
                throw new AssertionError("Distillation did not meet finite-batch yield and enrichment within "+(maximumTicks/20.0)+" s");
            return new Distillate(product,fraction,bottomFraction,tick/20.0,heatIn);
        }
    }

    private static double liquidEthanolFraction(ChemicalContents contents) {
        long e=chemicalAmount(contents,BuiltinChemicalDefinitions.ETHANOL),w=chemicalAmount(contents,BuiltinChemicalDefinitions.H2O);
        return e/(double)(e+w);
    }

    private static double batchDistillationReference(CommittedWorldRegistries world,ChemicalContents feed,double volumeRecovery) {
        var thermal=world.thermalRegistry();var model=new GasLiquidPhaseModel(thermal,world.gasRegistry());
        var wt=thermal.transitions().stream().filter(t->BuiltinChemicalDefinitions.H2O.equals(t.speciesId())&&t.interfaceRequirement()==ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS).findFirst().orElseThrow();
        var et=thermal.transitions().stream().filter(t->BuiltinChemicalDefinitions.ETHANOL.equals(t.speciesId())&&t.interfaceRequirement()==ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS).findFirst().orElseThrow();
        double ev=world.chemicalRegistry().species(world.chemicalRegistry().speciesHandle(BuiltinChemicalDefinitions.ETHANOL)).effectiveVolumeM3PerMol();
        double wv=world.chemicalRegistry().species(world.chemicalRegistry().speciesHandle(BuiltinChemicalDefinitions.H2O)).effectiveVolumeM3PerMol();
        double e=chemicalAmount(feed,BuiltinChemicalDefinitions.ETHANOL)/1e6,w=chemicalAmount(feed,BuiltinChemicalDefinitions.H2O)/1e6,de=0,dw=0;
        double dv=feed.totalVolumeNanos()*1e-9*volumeRecovery/512;
        for(int sample=0;sample<512;sample++) {
            double x=e/(e+w),low=250,high=450;
            for(int iteration=0;iteration<40;iteration++) {
                double t=(low+high)/2;
                double pe=model.saturation(et,t,mmm.fluid.air.GasInventory.EMPTY,0).saturationPressurePa();
                double pw=model.saturation(wt,t,mmm.fluid.air.GasInventory.EMPTY,0).saturationPressurePa();
                if(x*pe+(1-x)*pw>101325)high=t;else low=t;
            }
            double t=(low+high)/2,pe=model.saturation(et,t,mmm.fluid.air.GasInventory.EMPTY,0).saturationPressurePa();
            double pw=model.saturation(wt,t,mmm.fluid.air.GasInventory.EMPTY,0).saturationPressurePa();
            double y=x*pe/(x*pe+(1-x)*pw),n=dv/(y*ev+(1-y)*wv);
            e-=n*y;w-=n*(1-y);de+=n*y;dw+=n*(1-y);
        }
        return de/(de+dw);
    }

    private static boolean industrialPortPowered(IndustrialLayout.Port port,List<mmm.block.ProcessSensorBlockEntity> sensors) {
        if(!port.redstone())return true;
        for(var sensor:sensors) {
            var a=sensor.getBlockPos();var b=port.position();
            if(Math.abs(a.getX()-b.getX())+Math.abs(a.getY()-b.getY())+Math.abs(a.getZ()-b.getZ())==1 && sensor.output()>0)return true;
        }
        return false;
    }

    private static void requireDistillationCondensate(mmm.fluid.air.SpatialGasState gas,long ethanol,long water) {
        long condensedEthanol=gasPhaseSinkQuanta(gas,BuiltinGasDefinitions.ETHANOL_VAPOR,BuiltinChemicalDefinitions.ETHANOL);
        long condensedWater=gasPhaseSinkQuanta(gas,BuiltinGasDefinitions.WATER_VAPOR,BuiltinChemicalDefinitions.H2O);
        if(ethanol>condensedEthanol || water>condensedWater)
            throw new AssertionError("Distillate exceeds condensed species: ethanol="+ethanol+"/"+condensedEthanol+" water="+water+"/"+condensedWater);
    }

    private static long gasPhaseSinkQuanta(mmm.fluid.air.SpatialGasState gas,String component,String species){
        var entry=gas.phaseSink().component(component);if(entry==null)return 0;
        return entry.species().stream().filter(value->value.speciesId().equals(species)).mapToLong(value->value.amountQuanta()).sum();
    }

    private static ItemStack ethanolSolution(CommittedWorldRegistries world,double temperature,double volume){
        var chemistry=world.chemicalRegistry();
        double waterVolume=chemistry.species(chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)).effectiveVolumeM3PerMol();
        double ethanolVolume=chemistry.species(chemistry.speciesHandle(BuiltinChemicalDefinitions.ETHANOL)).effectiveVolumeM3PerMol();
        var amounts=Map.of(BuiltinChemicalDefinitions.H2O,Math.round(.5*volume/waterVolume*ChemicalLimits.QUANTA_PER_MOL),
                BuiltinChemicalDefinitions.ETHANOL,Math.round(.5*volume/ethanolVolume*ChemicalLimits.QUANTA_PER_MOL));
        var mixture=ChemicalMixtureRecord.fromStableAmounts(chemistry,amounts,0);
        long mass=mixture.speciesMassUnits(chemistry);
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,0,amounts)));
        var item=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,chemistry,Math.round(ContainerFluidLedger.NANOS_PER_BLOCK*volume)));
        world.bindActualContainerProfile(item);world.attachLiquidThermalContents(item,temperature);return item;
    }
}
