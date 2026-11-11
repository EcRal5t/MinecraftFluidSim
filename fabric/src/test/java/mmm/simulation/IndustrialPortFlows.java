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

/** Material box and port batches, gas loads, checkpoint recovery, and valve opening flow (T01, T02). */
final class IndustrialPortFlows {
    private IndustrialPortFlows() {}
    static void industrial(int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-industrial-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        if(startup.creationLocked())throw new IllegalStateException(startup.blockingSummary());
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.SCREENING);
        var region=fixture.region;var screens=region.hostSnapshot().captureAuthoritativeState().fixedGeometry().screens();
        if(screens.isEmpty())throw new AssertionError("Screen blocks did not configure shared faces");
        ItemStack remainder=fixture.transferBoxDurable(new ItemStack(Items.WATER_BUCKET),fixture.portCell,Direction.WEST,false,50_000_000L);
        long accepted=1_000_000_000L-mmm.item.PartialWaterBucketItem.volume(remainder);
        var host=region.hostSnapshot();long liquid=host.totalMassUnits();
        long actual=MinecraftContainerTransferOperations.cellVolumeToNanos(host.unitScale(),host.totalVolumeExact());
        long expectedMass=Mass.fromKilograms(host.unitScale().cubicMeters(host.totalVolumeExact())*world.resolveMaterial("mmm:water").referenceDensity());
        if(accepted<=0 || accepted>50_000_000L || actual!=accepted || liquid!=expectedMass)
            throw new AssertionError("Box input settlement differs: requested=50000000 accepted="+accepted+" actual="+actual+" mass="+liquid+" expected="+expectedMass);
        var gas=fixture.transferBoxDurable(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),fixture.portCell,Direction.WEST,true,10_000_000L);
        var gasContents=mmm.item.GasContainerItem.contents(gas);
        if(gasContents==null||gasContents.inventory().isEmpty())throw new AssertionError("Box output did not retain the gas mixture");
        fixture.transferBoxDurable(gas,fixture.portCell,Direction.WEST,false,gasContents.inventory().standardVolumeNanos());
        long initialMass=processMass(region.hostSnapshot().captureAuthoritativeState());

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            for(int step=0;step<Math.max(2,steps);step++){progress("industrial",step,Math.max(2,steps));if(!motion.step(region,List.of()).accepted())throw new AssertionError("Industrial process step rejected");}
            if(processMass(region.hostSnapshot().captureAuthoritativeState())!=initialMass)throw new AssertionError("Industrial process changed total mass");
            motion.settlePendingPhaseWork(region);
            long saved=operations.checkpoint(region);
            if(!region.releaseSolverAfterCheckpoint(saved))throw new AssertionError("Industrial checkpoint did not unload");
            var r=startup.registries();var repository=new RegionRepositoryAdapter(startup.worldRoot(),world.profile(),r.chemical(),r.granular(),r.thermal(),r.gas(),startup.config().maximumThermalSubstepsPerStep(),r.material());
            if(!repository.restoreIncrementally(region,0,Integer.MAX_VALUE).restored())throw new AssertionError("Industrial checkpoint did not restore");
            if(!region.hostSnapshot().captureAuthoritativeState().fixedGeometry().screens().equals(screens))throw new AssertionError("Screen faces changed after restore");
            fixture.restoreThermal();
        }
    }

    static void industrialValve(int steps) throws Exception {
        var open=valveRun(false,Math.max(400,steps));
        var closed=valveRun(true,Math.max(400,steps));
        if(!(open.flow()>0 && open.output()>0 && closed.flow()==0 && closed.output()==0))
            throw new AssertionError("Valve flow and output: open="+open+" closed="+closed);
    }

    private record ValveResult(double flow,long output) { }

    private static ValveResult valveRun(boolean closed,int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-valve-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.TWO_TANKS);
        var region=fixture.region;var layout=IndustrialLayout.at(new BlockPos(14,64,20),IndustrialLayout.Scenario.TWO_TANKS);
        var valves=layout.blocks().entrySet().stream().filter(entry->entry.getValue().is(mmm.block.MmmBlocks.VALVE)).toList();
        var faceList=new ArrayList<Integer>();
        for(var valve:valves) {
            int bx=2*(valve.getKey().getX()-region.origin().getX());
            int by=2*(valve.getKey().getY()-region.origin().getY());
            int bz=2*(valve.getKey().getZ()-region.origin().getZ());
            for(int y=0;y<2;y++)for(int z=0;z<2;z++)faceList.add(region.grid().faceAt(bx+1,by+y,bz+z,0));
        }
        int[] faces=faceList.stream().mapToInt(Integer::intValue).toArray();
        if(closed) {
            var state=mmm.block.MmmBlocks.VALVE.defaultBlockState().setValue(mmm.block.ValveBlock.FACING,Direction.EAST).setValue(mmm.block.ValveBlock.OPENING,0);
            var generation=region.solverGeneration();var candidate=region.beginHostEdit();var edits=new ArrayList<FluidDomainContracts.CollisionEdit>();
            for(var valve:valves)for(int x=0;x<2;x++)for(int y=0;y<2;y++)for(int z=0;z<2;z++) {
                int cx=2*(valve.getKey().getX()-region.origin().getX())+x;
                int cy=2*(valve.getKey().getY()-region.origin().getY())+y;
                int cz=2*(valve.getKey().getZ()-region.origin().getZ())+z;
                int mask=FluidRegionWorldInteraction.sampleCutCellMask(mmm.block.ValveBlock.shutter(state),x,y,z,2,1e-7);
                if(candidate.solidMicroMask(cx,cy,cz)!=mask)edits.add(new FluidDomainContracts.CollisionEdit(cx,cy,cz,mask));
            }
            if(edits.isEmpty())throw new AssertionError("Closing the valve did not change its collision geometry");
            candidate.applyCollisionEdits(edits);region.adoptCollisionCandidate(generation,candidate);
        }
        ItemStack batch=new ItemStack(Items.WATER_BUCKET);double flow=0;long output=0,inputMass=0,outputMass=0;
        long initialMass=processMass(region.hostSnapshot().captureAuthoritativeState());

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            for(int step=0;step<steps;step++) {
                progress(closed?"valve_closed":"valve_open",step,steps);
                {
                    motion.settlePendingPhaseWork(region);
                    if(batch.is(Items.BUCKET))batch=new ItemStack(Items.WATER_BUCKET);
                    long beforeInput=processMass(region.hostSnapshot().captureAuthoritativeState());
                    batch=fixture.transferBox(batch,fixture.portCell,Direction.WEST,false,IndustrialOperations.tickQuota(50_000_000L,step));available(region);
                    inputMass=Math.addExact(inputMass,processMass(region.hostSnapshot().captureAuthoritativeState())-beforeInput);
                }
                motion.settlePendingPhaseWork(region);
                int[] outlet=industrialPortCell(region,layout.ports().get(1));long quota=IndustrialOperations.tickQuota(50_000_000L,step);
                var block=region.origin().offset(outlet[0]/2,outlet[1]/2,outlet[2]/2);
                var contact=FluidWorldTargetResolver.transferCell(region,block,Direction.EAST,new mmm.api.transfer.TransferQuantity.Liquid(quota,OptionalDouble.empty()),true);
                if(contact!=null && MinecraftContainerTransferOperations.extractableNanos(region.hostSnapshot(),contact,quota,0,-1)>0) {
                    long beforeOutput=processMass(region.hostSnapshot().captureAuthoritativeState());
                    var product=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),outlet,Direction.EAST,true,quota);
                    long accepted=mmm.item.MaterialBucketItem.volume(product);if(accepted>0)output=Math.addExact(output,accepted);available(region);
                    outputMass=Math.addExact(outputMass,beforeOutput-processMass(region.hostSnapshot().captureAuthoritativeState()));
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Valve process step rejected");
                flow+=Math.abs(region.observeLiquidFlow(faces));
            }
            long finalMass=processMass(region.hostSnapshot().captureAuthoritativeState());
            System.out.println("valve_control_volume closed="+closed+" initial="+initialMass+" input="+inputMass+" output="+outputMass+" final="+finalMass);
            if(inputMass<0 || outputMass<0 || finalMass!=Math.subtractExact(Math.addExact(initialMass,inputMass),outputMass))
                throw new AssertionError("Valve control-volume mass did not close");
            return new ValveResult(flow,output);
        }
    }
}
