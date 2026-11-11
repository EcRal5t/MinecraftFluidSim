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

/** Particle screening with cake discharge, and cooled-wall crystallization with collection and redissolution. */
final class ScreeningFlows {
    private ScreeningFlows() {}
    static void industrialScreen(int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-screen-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.SCREENING);
        var region=fixture.region;
        var layout=IndustrialLayout.at(new BlockPos(14,64,20),IndustrialLayout.Scenario.SCREENING);
        int[] solidInput=industrialPortCell(region,layout.ports().get(2)),solidOutput=industrialPortCell(region,layout.ports().get(3));
        double pore=layout.screens().getFirst().poreDiameter();
        double fineDiameter=world.granularRegistry().material(world.granularRegistry().materialHandle(BuiltinGranularDefinitions.FINE_SILT)).primaryGrainDiameterMeters();
        double coarseDiameter=world.granularRegistry().material(world.granularRegistry().materialHandle(BuiltinGranularDefinitions.FINE_SAND)).primaryGrainDiameterMeters();
        if(!(fineDiameter<=pore && coarseDiameter>pore))throw new AssertionError("Screen feed no longer spans the pore size");
        System.out.println("screen_reference fine_m="+fineDiameter+" pore_m="+pore+" coarse_m="+coarseDiameter);
        var source=screenFeed(world);long initial=mmm.item.GranularContainerItem.unvalidatedContents(source).totalMassUnits();
        ItemStack remaining=source;boolean seeded=false;
        ItemStack water=saltedWater(world,293.15);

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            int[] screenFaces=region.fixedGeometry().screens().stream().mapToInt(ScreenFace::face).toArray();
            int duration=Math.max(800,steps);
            for(int step=0;step<duration;step++) {
                progress("screening",step,duration);
                {
                    motion.settlePendingPhaseWork(region);
                    if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(water))water=saltedWater(world,293.15);
                    water=fixture.transferBox(water,fixture.portCell,Direction.WEST,false,IndustrialOperations.tickQuota(50_000_000L,step));available(region);
                }
                if(!seeded) {
                    var solidBlock=region.origin().offset(solidInput[0]/2,solidInput[1]/2,solidInput[2]/2);
                    var solidContact=FluidWorldTargetResolver.transferCell(region,solidBlock,Direction.NORTH,new mmm.api.transfer.TransferQuantity.Granular(262_144L),false);
                    if(solidContact!=null && region.hostSnapshot().volume(solidContact[0],solidContact[1],solidContact[2])>0) {
                        motion.settlePendingPhaseWork(region);
                        remaining=fixture.transferBox(source,solidInput,Direction.NORTH,false,262_144L);seeded=true;available(region);
                    }
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Screen process step rejected");
            }
            motion.settlePendingPhaseWork(region);
            if(!seeded)throw new AssertionError("Liquid did not reach the screening feed port");
            var state=region.hostSnapshot().exportGranularState();
            for(var deposit:state.depositGroups())System.out.println("screen_deposit material="+state.materialPalette().get(deposit.materialPaletteIndex())
                    +" cell="+region.grid().x(deposit.cell())+","+region.grid().y(deposit.cell())+","+region.grid().z(deposit.cell())+" svq="+deposit.solidVolumeSvq());
            int plane=region.grid().faceCoordinate(screenFaces[0],1);
            long fine=downstreamSolid(state,region,BuiltinGranularDefinitions.FINE_SILT,plane);
            long coarse=downstreamSolid(state,region,BuiltinGranularDefinitions.FINE_SAND,plane);
            if(!(fine>0 && coarse==0))throw new AssertionError("Screen did not follow its particle-size cutoff: fine="+fine+" coarse="+coarse);
            var solidBlock=region.origin().offset(solidOutput[0]/2,solidOutput[1]/2,solidOutput[2]/2);
            var contact=FluidWorldTargetResolver.transferCell(region,solidBlock,Direction.NORTH,new mmm.api.transfer.TransferQuantity.Granular(262_144L),true);
            if(contact==null)throw new AssertionError("Screen has no cake at the output port");
            int contactCell=region.grid().cell(contact[0],contact[1],contact[2]);
            int[] cleanedFaces=Arrays.stream(screenFaces).filter(face->region.grid().faceLeft(face)==contactCell || region.grid().faceRight(face)==contactCell).toArray();
            if(cleanedFaces.length==0)throw new AssertionError("Screen cake is outside the output port contact");
            long saved=operations.checkpoint(region);
            double beforeFlow=screenDischarge(region,motion,cleanedFaces);
            if(!region.releaseSolverAfterCheckpoint(saved))throw new AssertionError("Screen comparison checkpoint did not unload");
            var r=startup.registries();var repository=new RegionRepositoryAdapter(startup.worldRoot(),world.profile(),r.chemical(),r.granular(),r.thermal(),r.gas(),startup.config().maximumThermalSubstepsPerStep(),r.material());
            if(!repository.restoreIncrementally(region,0,Integer.MAX_VALUE).restored())throw new AssertionError("Screen comparison checkpoint did not restore");
            fixture.restoreThermal();
            region.ensureDeviceResident();region.setPaused(false);available(region);
            var output=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GRANULAR_CONTAINER),solidOutput,Direction.NORTH,true,262_144L);
            var collected=mmm.item.GranularContainerItem.unvalidatedContents(output);
            if(collected==null || mmm.item.ContainerItemStacks.thermalContents(output)==null)throw new AssertionError("Screen cake extraction lost its inventory or energy");
            if(collected.groups().stream().noneMatch(group->group.materialId().equals(BuiltinGranularDefinitions.FINE_SAND)))
                throw new AssertionError("Screen cake output did not collect the retained coarse material");
            long retained=mmm.item.GranularContainerItem.isEmpty(remaining)?0:mmm.item.GranularContainerItem.unvalidatedContents(remaining).totalMassUnits();
            long after=region.hostSnapshot().exportGranularState().totalMassUnits()+retained+collected.totalMassUnits();
            if(after!=initial)throw new AssertionError("Screen cake transfer changed solid mass");
            double afterFlow=screenDischarge(region,motion,cleanedFaces);
            System.out.println("screen_cleaning clogged_discharge="+beforeFlow+" cleaned_discharge="+afterFlow);
            if(!(afterFlow>beforeFlow))throw new AssertionError("Screen cleaning did not restore discharge: before="+beforeFlow+" after="+afterFlow);
            motion.settlePendingPhaseWork(region);
            var solution=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),industrialPortCell(region,layout.ports().get(1)),Direction.EAST,true,50_000_000L);
            var dissolved=mmm.item.ContainerItemStacks.chemicalContents(solution,world.chemicalRegistry());
            if(chemicalAmount(dissolved,BuiltinChemicalDefinitions.NACL)+chemicalAmount(dissolved,BuiltinChemicalDefinitions.NA_PLUS)<=0)
                throw new AssertionError("Dissolved salt did not pass through the screen");
        }
    }

    private static double screenDischarge(FluidRegion region,MinecraftMotionExecutor motion,int[] faces) {
        double flow=0;
        for(int step=0;step<40;step++) {
            if(!motion.step(region,List.of()).accepted())throw new AssertionError("Screen comparison step rejected");
            flow+=Math.abs(region.observeLiquidFlow(faces));
        }
        return flow;
    }

    private static long downstreamSolid(GranularStateSnapshot state,FluidRegion region,String material,int plane) {
        int id=state.materialPalette().indexOf(material);long total=0;
        for(var parcel:state.activeParcels())if(parcel.materialPaletteIndex()==id && parcel.y()<plane*region.solverConfig().cellSize())total+=parcel.solidVolumeSvq();
        for(var deposit:state.depositGroups())if(deposit.materialPaletteIndex()==id && region.grid().y(deposit.cell())<plane)total+=deposit.solidVolumeSvq();
        return total;
    }

    private static ItemStack screenFeed(CommittedWorldRegistries world) {
        var registry=world.granularRegistry();var groups=new ArrayList<GranularTransferLedger.Group>();double energy=0;
        var function=new ThermalStateFunction(world.thermalRegistry());
        for(String id:List.of(BuiltinGranularDefinitions.FINE_SAND,BuiltinGranularDefinitions.FINE_SILT)) {
            var material=registry.material(registry.materialHandle(id));long volume=131_072L;
            long mass=Mass.fromKilograms(material.referenceDensity()*volume/(double)SolidVolume.SVQ_PER_CUBIC_METER);
            groups.add(new GranularTransferLedger.Group(id,volume,mass,null));
            int handle=world.thermalRegistry().materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR,id);
            energy+=function.internalEnergyAtTemperature(handle,Mass.kilograms(mass),Map.of(),293.15);
        }
        var contents=GranularContents.fromLedger(new GranularTransferLedger(groups),registry,world.chemicalRegistry());
        var item=world.bindActualContainerProfile(mmm.item.ContainerItemStacks.toStack(contents));
        mmm.item.ContainerItemStacks.attachThermal(item,world.thermalRegistryFingerprint(),energy);return item;
    }

    static void industrialCrystallization(int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-crystal-screen-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.SCREENING_CRYSTALLIZATION);
        var region=fixture.region;var layout=IndustrialLayout.at(new BlockPos(14,64,20),IndustrialLayout.Scenario.SCREENING_CRYSTALLIZATION);
        var wall=layout.walls().getFirst();
        int[] solidInput=industrialPortCell(region,layout.ports().get(2)),solidOutput=solidInput;
        ItemStack feed=coolableSaltWater(world,310,wall.temperatureK(),2_000_000L),seed=saltSeed(world,2);
        var reaction=world.granularRegistry().reactions().stream().filter(r->r.solidMaterialId().equals(BuiltinGranularDefinitions.PRECIPITATE)).findFirst().orElseThrow();
        var solubility=reaction.solubility();
        double hotRatio=solubility.ratio()+solubility.slopePerK()*(310-solubility.referenceTemperatureK());
        double coldRatio=solubility.ratio()+solubility.slopePerK()*(wall.temperatureK()-solubility.referenceTemperatureK());
        long water=Math.round(waterQuanta(world)*0.002);
        long salt=Math.round(water*(hotRatio-(hotRatio-coldRatio)*0.02));
        long coefficient=reaction.aqueousStoichiometry().values().iterator().next();
        long maximumGrowth=(salt/(coefficient*reaction.solidExtentPerBatch()))*reaction.solidVolumeSvqPerBatch();
        double coldEquilibriumGrowth=Math.max(0,salt-water*coldRatio)/(coefficient*reaction.solidExtentPerBatch())*reaction.solidVolumeSvqPerBatch();
        System.out.println("crystallization_reference hot_k=310.0 cold_k="+wall.temperatureK()+" hot_salt_per_water="+hotRatio+" cold_salt_per_water="+coldRatio
                +" cold_equilibrium_growth_svq="+coldEquilibriumGrowth+" available_salt_limit_svq="+maximumGrowth);
        long seedMass=mmm.item.GranularContainerItem.unvalidatedContents(seed).totalMassUnits();
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(feed,world)+seedMass,supplied=0,seedVolume=0;
        boolean seeded=false,grown=false;double bulkTemperature=310;
        String wallPrefix=wall.position().asLong()+"@";
        var coldWall=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().filter(device->device.objectId().startsWith(wallPrefix)).findFirst().orElseThrow();
        double wallEnergy=coldWall.body().internalEnergyJ();
        long coolingBudget=Math.max(4000,steps);

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            long coolingSteps=0;int growthSteps=0;
            while(!grown) {
                if(!seeded && coolingSteps++>=coolingBudget)throw new AssertionError("Finite batch did not cool within its heat-transfer budget: bulk="+bulkTemperature);
                if(seeded && growthSteps++>=Math.max(1000,steps))throw new AssertionError("Cooled seeded batch did not grow crystals");
                long step=seeded?growthSteps-1:coolingSteps-1;
                if(step%100==0)System.out.println("process_progress scenario="+(seeded?"crystallization_growth":"crystallization_cooling")+" step="+step);
                {
                    motion.settlePendingPhaseWork(region);
                    if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(feed)) {
                        feed=fixture.transferBox(feed,fixture.portCell,Direction.SOUTH,false,IndustrialOperations.tickQuota(50_000_000L,step));
                    }
                    if(!seeded) {
                        var block=region.origin().offset(solidInput[0]/2,solidInput[1]/2,solidInput[2]/2);
                        var contact=FluidWorldTargetResolver.transferCell(region,block,Direction.NORTH,new mmm.api.transfer.TransferQuantity.Granular(50_000L),false);
                        var liquid=contact==null?null:region.observeCellInventory(contact[0],contact[1],contact[2]);
                        if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(feed) && liquid!=null && liquid.temperatureKelvin().isPresent() && crystalExcess(region,world,reaction,contact,liquid.temperatureKelvin().getAsDouble())>=reaction.solidExtentPerBatch()*2) {
                            seed=fixture.transferBox(seed,solidInput,Direction.NORTH,false,50_000L);
                            if(mmm.item.GranularContainerItem.isEmpty(seed)){seedVolume=region.hostSnapshot().exportGranularState().totalSolidVolumeSvq();seeded=true;}
                        }
                    }
                    available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Crystallization process step rejected");
                if(step%20==0)bulkTemperature=crystalTemperature(region,world);
                if(step%100==0)System.out.println("crystallization_state step="+step+" seeded="+seeded
                        +" solid_svq="+region.hostSnapshot().exportGranularState().totalSolidVolumeSvq()+" temperature="+bulkTemperature);
                if(seeded && region.hostSnapshot().exportGranularState().totalSolidVolumeSvq()>seedVolume)grown=true;
            }
            motion.settlePendingPhaseWork(region);
            long grownVolume=region.hostSnapshot().exportGranularState().totalSolidVolumeSvq()-seedVolume;
            if(!seeded || grownVolume<=0 || grownVolume>maximumGrowth)throw new AssertionError("Cold crystal growth exceeds available salt or did not begin: growth="+grownVolume+" limit="+maximumGrowth);
            double afterWall=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().filter(device->device.objectId().startsWith(wallPrefix)).findFirst().orElseThrow().body().internalEnergyJ();
            if(!(bulkTemperature<310 && afterWall>wallEnergy))throw new AssertionError("Cooling did not drive crystallization: bulk="+bulkTemperature+" wall="+wallEnergy+" / "+afterWall);
            var product=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GRANULAR_CONTAINER),solidOutput,Direction.NORTH,true,262_144L);
            var crystal=mmm.item.GranularContainerItem.unvalidatedContents(product);
            if(crystal==null || crystal.groups().stream().noneMatch(GranularContents.Group::reactive)
                    || mmm.item.ContainerItemStacks.thermalContents(product)==null)throw new AssertionError("Screen output did not retain crystal composition and energy");
            long seedRemaining=mmm.item.GranularContainerItem.isEmpty(seed)?0:mmm.item.GranularContainerItem.unvalidatedContents(seed).totalMassUnits();
            long after=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(feed,world)+seedRemaining+crystal.totalMassUnits();
            if(after!=Math.addExact(initial,supplied))throw new AssertionError("Crystallization and collected cake changed total mass");
            long cakeVolume=crystal.totalSolidVolumeSvq();
            product=fixture.transferBox(product,solidInput,Direction.NORTH,false,Math.max(reaction.solidVolumeSvqPerBatch(),cakeVolume/2));
            var productRemainder=mmm.item.GranularContainerItem.unvalidatedContents(product);
            if(productRemainder!=null && productRemainder.totalSolidVolumeSvq()==cakeVolume)throw new AssertionError("Collected crystals did not return to the washing chamber");
            var valve=layout.minimum().offset(3,2,1);
            var open=mmm.block.MmmBlocks.VALVE.defaultBlockState().setValue(mmm.block.ValveBlock.FACING,Direction.EAST).setValue(mmm.block.ValveBlock.OPENING,4);
            var generation=region.solverGeneration();var candidate=region.beginHostEdit();var edits=new ArrayList<FluidDomainContracts.CollisionEdit>();
            for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
                int cx=(valve.getX()-region.origin().getX())*2+x,cy=(valve.getY()-region.origin().getY())*2+y,cz=(valve.getZ()-region.origin().getZ())*2+z;
                int mask=FluidRegionWorldInteraction.sampleCutCellMask(mmm.block.ValveBlock.shutter(open),x,y,z,2,1e-7);
                if(candidate.solidMicroMask(cx,cy,cz)!=mask)edits.add(new FluidDomainContracts.CollisionEdit(cx,cy,cz,mask));
            }
            if(edits.isEmpty())throw new AssertionError("Opening the valve did not change its collision geometry");
            candidate.applyCollisionEdits(edits);region.adoptCollisionCandidate(generation,candidate);available(region);
            long crystalBefore=region.hostSnapshot().exportGranularState().totalMassUnits();
            var wash=pureWater(world,293.15);supplied=Math.addExact(supplied,chemicalMass(wash,world));
            for(int step=0;step<600;step++) {
                progress("crystal_dissolution",step,600);
                motion.settlePendingPhaseWork(region);
                {
                    if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(wash)){wash=pureWater(world,293.15);supplied=Math.addExact(supplied,chemicalMass(wash,world));}
                    wash=fixture.transferBox(wash,fixture.portCell,Direction.SOUTH,false,IndustrialOperations.tickQuota(50_000_000L,step));available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Crystal dissolution step rejected");
            }
            motion.settlePendingPhaseWork(region);
            long crystalAfter=region.hostSnapshot().exportGranularState().totalMassUnits();
            if(crystalAfter>=crystalBefore)throw new AssertionError("Fresh water did not dissolve retained crystals: "+crystalBefore+" / "+crystalAfter);
            long productMass=productRemainder==null?0:productRemainder.totalMassUnits();
            long washed=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(feed,world)+chemicalMass(wash,world)+seedRemaining+productMass;
            if(washed!=Math.addExact(initial,supplied))throw new AssertionError("Crystal dissolution changed total mass");
        }
    }

    private static long crystalExcess(FluidRegion region,CommittedWorldRegistries world,SolidLiquidReaction reaction,int[] cell,double temperature) {
        int index=region.grid().cell(cell[0],cell[1],cell[2]);var registry=world.chemicalRegistry();
        long water=0,extent=Long.MAX_VALUE;
        var amounts=new TreeMap<String,Long>();
        for(var stock:region.hostSnapshot().captureAuthoritativeState().liquid().physicalStocks())if(stock.key().cell()==index && stock.mixture()!=null)
            stock.mixture().stableAmounts(registry).forEach((key,value)->amounts.merge(key,value,Math::addExact));
        water=amounts.getOrDefault(BuiltinChemicalDefinitions.H2O,0L);
        for(var term:reaction.aqueousStoichiometry().entrySet())extent=Math.min(extent,amounts.getOrDefault(term.getKey(),0L)/term.getValue());
        var solubility=reaction.solubility();double ratio=solubility.ratio()+solubility.slopePerK()*(temperature-solubility.referenceTemperatureK());
        long coefficient=reaction.aqueousStoichiometry().values().iterator().next();
        long excess=extent==Long.MAX_VALUE?0:extent-(long)Math.floor(water*ratio/coefficient);
        return excess;
    }

    private static double crystalTemperature(FluidRegion region,CommittedWorldRegistries world) {
        var registry=world.chemicalRegistry();
        int material=world.thermalRegistry().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water");
        var function=new ThermalStateFunction(world.thermalRegistry());double mass=0,weighted=0;
        for(var stock:region.hostSnapshot().captureAuthoritativeState().liquid().physicalStocks()) {
            if(stock.massUnits()==0)continue;
            var amounts=stock.mixture()==null?Map.<String,Long>of():stock.mixture().stableAmounts(registry);
            double temperature=function.stateForInternalEnergy(material,stock.massKilograms(),amounts,stock.internalEnergy()).temperatureK();
            mass+=stock.massKilograms();weighted+=stock.massKilograms()*temperature;
        }
        return weighted/mass;
    }
}
