package mmm.simulation;

import java.util.*;
import mmm.fluid.*;
import mmm.thermal.*;
import mmm.units.SignedDyadic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Joins a pipe's owners and newly acquired space under one durable world generation. */
final class RegionMerge {
    private final FluidRegionManager regions;
    private final FluidRegionOperations operations;
    private final WorldThermalEnvironment environment;
    private final FluidSnapshotPublisher snapshots;
    private final CommittedWorldRegistries world;
    private final java.util.function.BooleanSupplier worldBridgeEnabled;
    private final long quarantineEntries,quarantineBytes;

    RegionMerge(FluidRegionManager regions,FluidRegionOperations operations,WorldThermalEnvironment environment,
                FluidSnapshotPublisher snapshots,CommittedWorldRegistries world,java.util.function.BooleanSupplier worldBridgeEnabled,
                long quarantineEntries,long quarantineBytes) {
        this.regions=regions;this.operations=operations;this.environment=environment;this.snapshots=snapshots;
        this.world=world;this.worldBridgeEnabled=worldBridgeEnabled;this.quarantineEntries=quarantineEntries;this.quarantineBytes=quarantineBytes;
    }

    boolean apply(ServerLevel level,PipeNetworkPlan.Plan plan) {
        if(!ready(regions,plan,level::hasChunkAt))return false;
        var primary=plan.sources().getFirst();
        FluidRegion candidate=null;
        WorldThermalEnvironment.AirHandoff handoff=null;
        PreparedEnvironmentState preparedEnvironment=null;
        PreparedFluidState preparedFluid=null;
        boolean published=false,adopted=false,environmentAdopted=false;
        long targetGeneration=Math.addExact(primary.checkpointGeneration(),1);
        try {
            if(environment==null)throw new IllegalStateException("管路需要共享环境热场");
            var footprint=plan.footprint();var grid=FluidRegionFactory.grid(footprint);
            var geometryHost=new HostFluidState(grid,primary.solverConfig(),world.definitions());
            var geometryCandidate=primary.resizedCandidate(geometryHost,footprint);
            var admission=regions.previewMerge(plan.sources(),geometryCandidate);
            if(!admission.accepted())throw new IllegalStateException(admission.failure());
            FluidRegionWorldInteraction.refreshCollisions(level,geometryCandidate,FluidRegionFactory.CELLS_PER_BLOCK);
            var geometry=geometryCandidate.fixedGeometry();
            var sources=new ArrayList<FluidDomainMerge.Source>();
            for(var source:plan.sources()) {
                var state=source.hostSnapshot().captureAuthoritativeState();
                boolean[] retained=new boolean[state.grid().cellCount()];
                for(int cell=0;cell<retained.length;cell++)retained[cell]=source.footprint().containsInterior(source.origin().offset(
                        state.grid().x(cell)/2,state.grid().y(cell)/2,state.grid().z(cell)/2));
                sources.add(new FluidDomainMerge.Source(state,(source.origin().getX()-footprint.minimumInterior().getX())*2,
                        (source.origin().getY()-footprint.minimumInterior().getY())*2,(source.origin().getZ()-footprint.minimumInterior().getZ())*2,retained));
            }
            var added=newlyOwnedBlocks(footprint,plan.sources());
            handoff=environment.prepareRange(level,geometryCandidate,added);
            var air=handoff.split(footprint.minimumInterior(),grid);
            var gas=FluidDomainMerge.gas(sources,grid,air.cells(),air.energy());
            var audit=FluidDomainMerge.thermalAudit(sources).withExternalSourceEnergy(SignedDyadic.sum(air.energy()).subtract(air.roundoff()))
                    .withRoundoff(air.roundoff());
            var boundary=new double[grid.cellCount()*6];Arrays.fill(boundary,Double.NaN);
            var state=FluidDomainMerge.prepare(sources,grid,geometry.copyMasks(),geometry.screens(),gas,audit,boundary);
            var host=HostFluidState.restoreAuthoritativeState(state);
            var edits=new ArrayList<FluidDomainContracts.CollisionEdit>();var oldMasks=state.copyCollisionMasks();
            for(int cell=0;cell<grid.cellCount();cell++)if(Byte.toUnsignedInt(oldMasks[cell])!=geometry.mask(cell))
                edits.add(new FluidDomainContracts.CollisionEdit(grid.x(cell),grid.y(cell),grid.z(cell),geometry.mask(cell)));
            if(!edits.isEmpty())host.applyCollisionEdits(edits);
            var packedAdded=new HashSet<Long>();for(var pos:added)packedAdded.add(pos.asLong());
            var water=operations.scanOwnershipPreimage(level,footprint).stream().filter(e->packedAdded.contains(e.packedPosition())).toList();
            if(!water.isEmpty()&&!worldBridgeEnabled.getAsBoolean())throw new IllegalStateException("管路范围包含原版水，世界物质交接当前关闭");
            FluidRegionFactory.captureAddedWater(level,host,footprint.minimumInterior(),packedAdded);
            var devices=mergeDevices(plan.sources(),world.thermalRegistry().fingerprint());
            candidate=primary.mergedCandidate(host,footprint,devices);
            preparedEnvironment=handoff.prepare();candidate.environmentCandidate(preparedEnvironment.capture());candidate.environmentCandidateExternal(true);
            try(var source=primary.captureSolverState()) {
                preparedFluid=regions.solverBackend().prepareState(source,host.captureAuthoritativeState(),devices,List.of(),null);
            }
            preparedFluid.attachEnvironment(preparedEnvironment);
            operations.acquireMergedOwnership(level,candidate,water,plan.sources(),quarantineEntries,quarantineBytes);
            published=true;
            candidate.adoptResizedDevice(preparedFluid.commit());
            handoff.adopted();environmentAdopted=true;candidate.clearEnvironmentCandidate();
            candidate.requireCollisionRefresh();
            var result=regions.adoptMerge(plan.sources(),candidate);
            if(!result.accepted())throw new IllegalStateException("已保存管路等待登记："+result.failure());
            adopted=true;
            for(var retired:plan.sources())if(!retired.id().equals(candidate.id()))snapshots.sendRemoval(level.getServer(),retired,Math.addExact(retired.networkGeneration(),1));
            snapshots.sendToLevel(level,candidate);
        } catch(Exception failure) {
            published|=candidate!=null && operations.worldCheckpointContains(candidate,targetGeneration);
            if(published && candidate!=null) {
                try {
                    if(candidate.checkpointGeneration()<targetGeneration)candidate.recordPublishedCheckpoint(targetGeneration);
                    if(preparedEnvironment!=null && preparedEnvironment.isCurrent())preparedEnvironment.commit();
                    if(!environmentAdopted && handoff!=null){handoff.adopted();environmentAdopted=true;candidate.clearEnvironmentCandidate();}
                    if(!adopted && regions.region(candidate.id())==candidate)adopted=true;
                    if(!adopted){var result=regions.adoptMerge(plan.sources(),candidate);if(!result.accepted())throw new IllegalStateException(result.failure());adopted=true;}
                    candidate.requireCollisionRefresh();candidate.blockNumerical("已提交管路需要恢复："+failure.getMessage());
                    for(var retired:plan.sources())if(!retired.id().equals(candidate.id()))snapshots.sendRemoval(level.getServer(),retired,Math.addExact(retired.networkGeneration(),1));
                    snapshots.sendToLevel(level,candidate);
                } catch(Exception recovery){failure.addSuppressed(recovery);primary.blockNumerical("已提交管路需从世界存档恢复："+failure.getMessage());}
            } else if(failure instanceof PipeNetworkPlan.UnloadedChunk)
                primary.blockExpansion(mmm.api.RegionAvailabilityReason.CHUNK_MISSING,"管路接通受阻："+failure.getMessage());
            else primary.blockNumerical("管路接通受阻："+failure.getMessage());
            mmm.MmmMod.LOGGER.error("Pipe region join failed for {}",primary.id(),failure);
        } finally {
            if(preparedFluid!=null)preparedFluid.close();
            if(preparedEnvironment!=null)preparedEnvironment.close();
            if(handoff!=null&&!environmentAdopted)handoff.discard();
            if(candidate!=null&&!adopted&&!published)candidate.releaseAfterReplacement();
        }
        return true;
    }

    static Set<BlockPos> newlyOwnedBlocks(mmm.simulation.controller.FluidRegionFootprint footprint,List<FluidRegion> sources) {
        var added=new HashSet<BlockPos>();
        for(long packed:footprint.interiorBlocks()) {
            var pos=BlockPos.of(packed);
            if(footprint.containsInterior(pos) && sources.stream().noneMatch(source->source.footprint().containsInterior(pos)))added.add(pos);
        }
        return added;
    }

    static boolean ready(FluidRegionManager regions,PipeNetworkPlan.Plan plan,java.util.function.Predicate<BlockPos> loaded) {
        for(var source:plan.sources()) {
            if(regions.region(source.id())!=source || !source.solverLoaded() || source.paused()
                    || source.availabilityFailure()!=null || source.hasPendingPhaseMotionWork())return false;
            for(long packed:source.footprint().requiredChunks()) {
                var chunk=net.minecraft.world.level.ChunkPos.unpack(packed);
                if(!loaded.test(new BlockPos(chunk.x()<<4,source.origin().getY(),chunk.z()<<4)))return false;
            }
            for(long block:source.footprint().interiorBlocks())
                if(source.footprint().containsInterior(BlockPos.of(block)) && !loaded.test(BlockPos.of(block)))return false;
        }
        return true;
    }

    static FiniteThermalDeviceInventory mergeDevices(List<FluidRegion> sources,String fingerprint) {
        var devices=new ArrayList<FiniteThermalDeviceState>();
        var initial=SignedDyadic.ZERO;var external=SignedDyadic.ZERO;var sink=SignedDyadic.ZERO;var exchange=SignedDyadic.ZERO;var roundoff=SignedDyadic.ZERO;
        for(var source:sources) {
            var inventory=source.finiteThermalDevices(fingerprint);var audit=inventory.audit();
            initial=initial.add(audit.initialInventory());external=external.add(audit.externalSource());sink=sink.add(audit.externalSink());
            exchange=exchange.add(audit.solverExchange());roundoff=roundoff.add(audit.roundoffResidual());
            for(var device:inventory.devices()) {
                if(device.providerId().equals(WorldThermalEnvironment.PROVIDER))sink=sink.add(SignedDyadic.of(device.body().internalEnergyJ()));
                else devices.add(device);
            }
        }
        return new FiniteThermalDeviceInventory(fingerprint,devices,new FiniteThermalDeviceAudit(initial,external,sink,exchange,roundoff));
    }
}
