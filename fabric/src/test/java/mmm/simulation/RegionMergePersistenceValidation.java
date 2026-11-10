package mmm.simulation;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mmm.application.RegionCatalog;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.HostFluidState;
import mmm.simulation.controller.FluidRegionFootprint;
import mmm.simulation.ownership.OwnershipCommitted;
import mmm.simulation.ownership.OwnershipFiles;
import mmm.simulation.ownership.OwnershipJournal;
import mmm.simulation.persistence.EnvironmentThermalStore;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/** Restart reads either both original regions or the merged generation and its tombstone. */
public final class RegionMergePersistenceValidation {
    private RegionMergePersistenceValidation() { }
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        for(boolean publish:new boolean[]{false,true}) verify(publish);
        productionRepository();
        System.out.println("RegionMergePersistenceValidation passed");
    }
    private static void verify(boolean publish) throws Exception {
        var root=Files.createTempDirectory("mmm-merge-head-");var definitions=mmm.fluid.TestFluidDefinitions.load();
        var profile=TestProfiles.pure();
        var writer=new RegionRepositoryAdapter(root,profile,definitions.chemistry(),definitions.granular(),
                definitions.thermal(),definitions.gas(),definitions.material());
        var first=region(0,definitions);var second=region(4,definitions);
        writer.checkpoint(first);writer.checkpoint(second);
        var files=new mmm.simulation.persistence.RegionGenerationStore(root);
        var one=files.read(first.id());var two=files.read(second.id());
        var world=new EnvironmentThermalStore(root,definitions.chemistry(),definitions.granular(),definitions.thermal(),definitions.gas());
        world.publish(Map.of(),Map.of(first.id(),one,second.id(),two));
        var water=new CompoundTag();water.putString("Name","minecraft:water");
        var ownership=new OwnershipFiles(root,second.id());
        mmm.simulation.ownership.OwnershipTestFixtures.publish(ownership,new OwnershipCommitted(second.id(),"minecraft:overworld",1,
                List.of(new OwnershipJournal.BlockEntry(new BlockPos(4,1,0).asLong(),water))));
        var removal=writer.prepareRemoval(second,2,UUID.randomUUID(),10000,100000000);
        var merged=writer.capture(first,2);var tombstone=removal.manifest();
        var staged=world.prepare(Map.of(),Map.of(first.id(),merged),Map.of(second.id(),
                new EnvironmentThermalStore.Removal(tombstone.targetGeneration(),tombstone.checksum())));
        if(publish)world.commit(staged);
        var reader=new RegionRepositoryAdapter(root,profile,definitions.chemistry(),definitions.granular(),
                definitions.thermal(),definitions.gas(),definitions.material());
        var manager=new FluidRegionManager(new RegionCatalog.Limits(8,10000,10000));
        var issues=reader.loadMetadata(manager);
        require(issues.isEmpty(),"restart rejected world checkpoint: "+issues);
        require(manager.region(first.id())!=null && manager.region(first.id()).checkpointGeneration()==(publish?2:1),"primary generation differs");
        require((manager.region(second.id())!=null)==!publish,"retired region visibility differs");
        require(ownership.hasCommitted()!=publish,"retired ownership cleanup occurred at wrong HEAD");
        var saved=world.read();
        require(saved.regions().containsKey(first.id()) && saved.regions().containsKey(second.id())!=publish
                && saved.removals().containsKey(second.id())==publish,"world HEAD combined only part of merge");
    }
    private static void productionRepository() throws Exception {
        var root=Files.createTempDirectory("mmm-merge-repository-");
        var production=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(root,()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                java.util.EnumSet.allOf(mmm.worldprofile.WorldCapability.class),production.material(),production.chemical(),
                production.granular(),production.gas(),production.thermal()));
        var world=new CommittedWorldRegistries(startup);
        var definitions=world.definitions();var profile=world.profile();
        var writer=new RegionRepositoryAdapter(root,profile,definitions.chemistry(),definitions.granular(),
                definitions.thermal(),definitions.gas(),definitions.material());
        var first=region(0,definitions);var second=region(4,definitions);
        world.bindConfiguredState(first.editableHost(),8);world.bindConfiguredState(second.editableHost(),8);
        writer.checkpoint(first);writer.checkpoint(second);
        var manager=new FluidRegionManager(new RegionCatalog.Limits(8,10000,10000));
        require(manager.add(first).accepted() && manager.add(second).accepted(),"source admission failed");
        var stored=new java.util.HashMap<String,mmm.thermal.EnvironmentThermalState>();
        var backend=(mmm.fluid.FluidSolverBackend)java.lang.reflect.Proxy.newProxyInstance(
                mmm.fluid.FluidSolverBackend.class.getClassLoader(),new Class<?>[]{mmm.fluid.FluidSolverBackend.class},
                (proxy,method,args)->switch(method.getName()) {
                    case "configureEnvironment" -> {var value=(mmm.thermal.EnvironmentThermalState)args[0];stored.put(value.dimensionId(),value);yield null;}
                    case "captureEnvironment" -> stored.get((String)args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var environment=new WorldThermalEnvironment(backend,world,manager);
        var wall=new mmm.thermal.EnvironmentThermalState.Node(new mmm.thermal.EnvironmentThermalState.Key(1,0,0,
                mmm.thermal.EnvironmentThermalState.Kind.SOLID,0),"mmm:ordinary_metal_wall",100,1,
                mmm.fluid.air.GasInventory.EMPTY,42,List.of());
        var shared=mmm.thermal.EnvironmentThermalState.initialized("minecraft:overworld",world.thermalRegistryFingerprint(),List.of(wall),List.of());
        environment.restore(Map.of(shared.dimensionId(),shared));
        writer.environment(environment,manager);
        writer.checkpoint(first);
        var sources=List.of(first,second);
        var footprint=FluidRegionFootprint.box(new BlockPos(0,0,0),new BlockPos(4,0,0));
        var grid=FluidRegionFactory.grid(footprint);
        var input=new java.util.ArrayList<mmm.fluid.FluidDomainMerge.Source>();
        for(var source:sources) {
            var state=source.hostSnapshot().captureAuthoritativeState();var retained=new boolean[state.grid().cellCount()];
            java.util.Arrays.fill(retained,true);
            input.add(new mmm.fluid.FluidDomainMerge.Source(state,source.origin().getX()*2,0,0,retained));
        }
        // These independent cells are enough to verify both source inventories are written to the successor.
        var gas=mmm.fluid.FluidDomainMerge.gas(input,grid,java.util.Collections.nCopies(grid.cellCount(),mmm.fluid.air.GasInventory.EMPTY),new double[grid.cellCount()]);
        var state=mmm.fluid.FluidDomainMerge.prepare(input,grid,mmm.fluid.FixedGeometrySnapshot.fromMasks(grid,new byte[grid.cellCount()]),gas,
                mmm.fluid.FluidDomainMerge.thermalAudit(input),null);
        var mergedHost=HostFluidState.restoreAuthoritativeState(state);
        double expected=first.hostSnapshot().totalVolumeExact()+second.hostSnapshot().totalVolumeExact();
        require(mergedHost.totalVolumeExact()==expected,"merged host inventory differs");
        var candidate=first.resizedCandidate(mergedHost,footprint);
        writer.prepareMerge(candidate,sources,10000,100000000);
        writer.finishMerge(candidate.id());
        var store=new EnvironmentThermalStore(root,definitions.chemistry(),definitions.granular(),definitions.thermal(),definitions.gas());
        require(store.read().regions().size()==2 && store.read().removals().isEmpty(),"aborted staging changed world HEAD");
        writer.prepareMerge(candidate,sources,10000,100000000);
        writer.checkpoint(candidate);
        writer.finishMerge(candidate.id());
        var snapshot=store.read();
        require(snapshot.regions().size()==1 && snapshot.regions().containsKey(first.id())
                && snapshot.removals().containsKey(second.id()) && shared.equals(snapshot.environments().get(shared.dimensionId())),
                "production publication did not preserve environment and retire the source atomically");
        var reader=new RegionRepositoryAdapter(root,profile,definitions.chemistry(),definitions.granular(),
                definitions.thermal(),definitions.gas(),definitions.material());
        var restoredManager=new FluidRegionManager(new RegionCatalog.Limits(8,10000,10000));
        var issues=reader.loadMetadata(restoredManager);
        require(issues.isEmpty() && restoredManager.regions().size()==1,"production merge restart failed: "+issues);
        var restored=restoredManager.region(first.id());reader.restore(restored);
        require(restored.hostSnapshot().totalVolumeExact()==expected,"production merge restart lost liquid");
    }
    private static FluidRegion region(int x,mmm.fluid.FluidDefinitions definitions) {
        var config=new FluidSolverConfig(.5f,9.81f,.05f,48,1e-4f,.002f);
        var host=new HostFluidState(2,2,2,config,definitions);host.setVolume(0,0,0,.25f);
        var origin=new BlockPos(x,0,0);var footprint=FluidRegionFootprint.box(origin,origin);
        return new FluidRegion(UUID.randomUUID(),Level.OVERWORLD,origin,host,footprint);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
