package mmm.simulation;

import java.util.*;
import java.util.function.Predicate;
import mmm.MmmMod;
import mmm.block.*;
import mmm.pipe.*;
import mmm.thermal.GasThermalModel;
import mmm.units.SignedDyadic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Dimension-owned finite pipe inventories; all world/region exchanges use private candidates. */
public final class PipeRuntime {
    public static boolean allowsRemoval(ServerLevel level,BlockPos position) {
        var runtime=MmmRuntimeRegistry.find(level.getServer());
        return runtime==null || runtime.pipes()==null || runtime.pipes().allowsBlockChange(level,position,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }
    private static final int STEP_TICKS=4, MAX_SEGMENTS=16384;
    private final Map<String,PipeDimensionState> dimensions=new HashMap<>();
    private final Map<String,Set<BlockPos>> changed=new HashMap<>();
    private final Map<String,String> failures=new HashMap<>();
    private final Map<String,Long> displayGenerations=new HashMap<>();
    private final FluidRegionManager regions;
    private final FluidWorldTargetResolver targets;
    private final CommittedWorldRegistries world;
    private final WorldThermalEnvironment environment;
    private final Predicate<FluidRegion> available;
    private final java.util.function.BooleanSupplier writesReady;
    private final PipeSnapshotPublisher snapshots;
    private int ticks;

    PipeRuntime(FluidRegionManager regions,FluidRegionOperations operations,CommittedWorldRegistries world,
                WorldThermalEnvironment environment,Predicate<FluidRegion> available,java.util.function.BooleanSupplier writesReady) {
        this.regions=regions;this.targets=new FluidWorldTargetResolver(regions);this.world=world;
        this.environment=Objects.requireNonNull(environment);this.available=available;this.writesReady=Objects.requireNonNull(writesReady);
        snapshots=new PipeSnapshotPublisher(world);dimensions.putAll(operations.recoveredPipes());
        operations.pipes(this::capture);
        for(var entry:dimensions.entrySet()) {
            changed.put(entry.getKey(),positions(entry.getValue()));
            environment.pipePositions(entry.getKey(),positions(entry.getValue()));
        }
    }
    Map<String,PipeDimensionState> capture() { return Map.copyOf(dimensions); }
    boolean blocks(FluidRegion region) {
        for(var pos:changed.getOrDefault(region.dimension().identifier().toString(),Set.of())) {
            var footprint=region.footprint();
            if(footprint!=null&&footprint.containsInterior(pos))return true;
        }
        return false;
    }
    private static Set<BlockPos> positions(PipeDimensionState state) {
        var result=new HashSet<BlockPos>();
        for(var segment:state.network().segments())result.add(BlockPos.of(Long.parseLong(segment.id())));
        return result;
    }
    public void changed(ServerLevel level,BlockPos position) {
        String dimension=level.dimension().identifier().toString();var current=dimensions.get(dimension);
        if(PipeThermalGeometry.isPipe(level.getBlockState(position)) || current!=null&&current.installations().containsKey(Long.toString(position.asLong())))
            changed.computeIfAbsent(dimension,ignored->new HashSet<>()).add(position.immutable());
    }
    public void chunkLoaded(ServerLevel level,LevelChunk chunk) {
        var sections=chunk.getSections();int base=chunk.getMinY();
        for(int section=0;section<sections.length;section++) {
            var cells=sections[section];if(!cells.maybeHas(PipeThermalGeometry::isPipe))continue;
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
                if(PipeThermalGeometry.isPipe(cells.getBlockState(x,y,z)))
                    changed(level,new BlockPos(chunk.getPos().getMinBlockX()+x,base+section*16+y,chunk.getPos().getMinBlockZ()+z));
        }
        chunksChanged();
    }
    public void chunksChanged() {
        for(var entry:dimensions.entrySet())changed.computeIfAbsent(entry.getKey(),ignored->new HashSet<>()).addAll(positions(entry.getValue()));
    }
    public boolean allowsBlockChange(ServerLevel level,BlockPos position,BlockState next) {
        if(!writesReady.getAsBoolean()&&(PipeThermalGeometry.isPipe(next)||PipeThermalGeometry.isPipe(level.getBlockState(position))))return false;
        return allowsBlockChange(dimensions.get(level.dimension().identifier().toString()),position,next);
    }
    static boolean allowsBlockChange(PipeDimensionState state,BlockPos position,BlockState next) {
        if(state==null)return true;
        String id=Long.toString(position.asLong());
        for(int i=0;i<state.network().segments().size();i++) {
            var segment=state.network().segments().get(i);if(!segment.id().equals(id))continue;
            var row=state.network().liquids().rows().get(i);
            if(!PipeThermalGeometry.isPipe(next))return row.isEmpty()&&segment.deposits().isEmpty();
            double occupied=row.stream().mapToDouble(stock->stock.volume().toDouble()).sum();
            for(var deposit:segment.deposits())occupied+=deposit.inventory().solidVolumeSvq()/(double)mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER;
            return occupied<=PipeWorldGraph.capacity(next);
        }
        return true;
    }
    public String describe(ServerLevel level,BlockPos position) {
        String dimension=level.dimension().identifier().toString();var state=dimensions.get(dimension);
        if(state==null)return "管道正在建立管腔";
        for(int i=0;i<state.network().segments().size();i++) {
            var segment=state.network().segments().get(i);if(!segment.id().equals(Long.toString(position.asLong())))continue;
            var mixture=PipeHydraulics.mixture(segment,state.network().liquids().rows().get(i),new GasThermalModel(world.thermalRegistry(),world.gasRegistry()));
            String value=String.format(Locale.ROOT,"管腔 %.2f L\n液体 %.2f L\n%.2f kPa\n沉积 %.3f kg",segment.capacityM3()*1000,
                    mixture.liquidVolumeM3()*1000,mixture.absolutePressurePa()/1000,segment.deposits().stream().mapToDouble(d->d.inventory().mass()).sum());
            if(!segment.gas().isEmpty())value+=String.format(Locale.ROOT,"\n气温 %.1f ℃",new GasThermalModel(world.thermalRegistry(),world.gasRegistry())
                    .state(segment.gas(),segment.gasInternalEnergy()).temperatureK()-273.15);
            var liquid=state.network().liquids().rows().get(i).stream().max(java.util.Comparator.comparingLong(mmm.inventory.LiquidCellInventory.Stock::massUnits)).orElse(null);
            if(liquid!=null) {
                int material=world.thermalRegistry().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,state.network().materialIds().get(liquid.key().material()));
                var species=liquid.mixture()==null?Map.<String,Long>of():liquid.mixture().stableAmounts(world.chemicalRegistry());
                double temperature=new mmm.thermal.ThermalStateFunction(world.thermalRegistry()).stateForInternalEnergy(material,liquid.massKilograms(),species,liquid.internalEnergy()).temperatureK();
                value+=String.format(Locale.ROOT,"\n主液相 %.1f ℃",temperature-273.15);
            }
            return failures.containsKey(dimension)?value+"\n暂停："+failures.get(dimension):value;
        }
        return "管道正在建立管腔";
    }
    void reconcile(MinecraftServer server) {
        if(!writesReady.getAsBoolean())return;
        for(var level:server.getAllLevels()) {
            String dimension=level.dimension().identifier().toString();
            if(!changed.containsKey(dimension))continue;
            try { reconcile(level);failures.remove(dimension); }
            catch(RuntimeException failure) { failed(dimension,failure); }
        }
    }
    private void reconcile(ServerLevel level) {
        String dimension=level.dimension().identifier().toString();var previous=dimensions.get(dimension);
        var accepted=new HashMap<BlockPos,BlockState>();
        if(previous!=null)for(var entry:previous.installations().entrySet())
            accepted.put(BlockPos.of(Long.parseLong(entry.getKey())),PipeWorldGraph.state(entry.getValue()));
        for(var pos:changed.getOrDefault(dimension,Set.of())) {
            if(!level.hasChunkAt(pos))continue;
            var state=level.getBlockState(pos);if(PipeThermalGeometry.isPipe(state))accepted.put(pos,state);else accepted.remove(pos);
        }
        if(accepted.size()>MAX_SEGMENTS)throw new IllegalStateException("单维度管段数量超过 "+MAX_SEGMENTS);
        var fresh=new HashMap<BlockPos,Double>();var returned=new HashMap<BlockPos,PipeTopologyEdit.Air>();
        for(var entry:accepted.entrySet())if(previous==null||!previous.installations().containsKey(Long.toString(entry.getKey().asLong())))
            fresh.put(entry.getKey(),PipeWorldGraph.capacity(entry.getValue()));
        if(previous!=null)for(var segment:previous.network().segments()) {
            var pos=BlockPos.of(Long.parseLong(segment.id()));
            if(!accepted.containsKey(pos))returned.put(pos,new PipeTopologyEdit.Air(segment.gas(),segment.gasInternalEnergy()));
        }
        var regionFresh=new HashMap<BlockPos,Double>();var regionReturned=new HashMap<BlockPos,PipeTopologyEdit.Air>();
        fresh.entrySet().removeIf(entry->{if(targets.regionAtBlock(level,entry.getKey())==null)return false;regionFresh.put(entry.getKey(),entry.getValue());return true;});
        returned.entrySet().removeIf(entry->{if(targets.regionAtBlock(level,entry.getKey())==null)return false;regionReturned.put(entry.getKey(),entry.getValue());return true;});
        try(var air=environment.preparePipeAir(level,fresh,returned);
            var regional=PipeRegionAirExchange.prepare(level,regions,regionFresh,regionReturned,world.definitions())) {
            var acquired=new HashMap<BlockPos,PipeTopologyEdit.Contents>();
            air.acquired().forEach((pos,value)->acquired.put(pos,PipeTopologyEdit.Contents.air(value)));
            acquired.putAll(regional.inventories());
            var candidate=PipeTopologyEdit.plan(previous,accepted,acquired,level::hasChunkAt,
                    pos->level.getBlockEntity(pos) instanceof ScreenBlockEntity screen?screen.poreDiameter():0,
                    pos->level.getBlockEntity(pos) instanceof ScreenBlockEntity screen?screen.openFraction():1,world.definitions());
            if(!air.isCurrent()||!regional.isCurrent())throw new IllegalStateException("管腔空气交接已变化");
            regional.commit(air.prepared());air.adopted();
            if(candidate.state()==null)dimensions.remove(dimension);else dimensions.put(dimension,candidate.state());
            changed.remove(dimension);environment.pipePositions(dimension,accepted.keySet());environment.refreshDirty(level);
            regional.publishObservations();publish(level,candidate.state());
        }
    }
    void tick(MinecraftServer server) {
        if(!writesReady.getAsBoolean())return;
        if(++ticks<STEP_TICKS)return;ticks=0;
        for(var level:server.getAllLevels()) {
            String dimension=level.dimension().identifier().toString();var before=dimensions.get(dimension);
            if(before==null||changed.containsKey(dimension))continue;
            try { step(level,before,STEP_TICKS/20.);failures.remove(dimension); }
            catch(RuntimeException failure) { failed(dimension,failure); }
        }
    }
    private void step(ServerLevel level,PipeDimensionState before,double seconds) {
        String dimension=level.dimension().identifier().toString();
        var ports=PipeEndpointResolver.resolve(level,before,targets,available).stream().map(p->
                new PipeRegionExchange.Port(p.pipeSegment(),p.region(),p.cell(),p.areaM2(),p.lengthM(),p.riseM(),p.opening(),p.direction())).toList();
        var frozen=new java.util.HashSet<String>();
        for(var segment:before.network().segments())
            if(!level.hasChunkAt(BlockPos.of(Long.parseLong(segment.id()))))frozen.add(segment.id());
        try(var exchange=PipeRegionExchange.prepare(before.network(),ports,seconds,world.definitions(),9.81,frozen)) {
            var walls=new ArrayList<PipeWallHeat.Wall>();var wallKeys=new HashMap<Integer,mmm.thermal.EnvironmentThermalState.Key>();long generation=-1;
            var wallStates=new HashMap<BlockPos,BlockState>();
            for(var segment:before.network().segments()) {
                var pos=BlockPos.of(Long.parseLong(segment.id()));if(level.hasChunkAt(pos))wallStates.put(pos,PipeWorldGraph.state(before.installations().get(segment.id())));
            }
            var worldWalls=environment.pipeWalls(dimension,wallStates);
            for(int i=0;i<before.network().segments().size();i++) {
                var segment=before.network().segments().get(i);var pos=BlockPos.of(Long.parseLong(segment.id()));if(!level.hasChunkAt(pos))continue;
                var wall=worldWalls.get(pos);if(wall==null)throw new IllegalStateException("管壁热场尚未就绪："+pos.toShortString());
                if(generation!=-1&&generation!=wall.generation())throw new IllegalStateException("管壁热状态已变化");generation=wall.generation();
                walls.add(new PipeWallHeat.Wall(i,wall.materialId(),wall.massUnits(),wall.energy(),wall.innerArea(),wall.halfThickness()));wallKeys.put(i,wall.key());
            }
            var heat=PipeWallHeat.exchange(exchange.state(),walls,seconds,world.definitions());
            var deltas=new HashMap<mmm.thermal.EnvironmentThermalState.Key,Double>();
            heat.wallEnergyDeltas().forEach((index,value)->deltas.put(wallKeys.get(index),value));
            try(var wallCandidate=deltas.isEmpty()?null:environment.preparePipeWallExchange(dimension,generation,deltas)) {
                if(!exchange.isCurrent()||dimensions.get(dimension)!=before)throw new IllegalStateException("管路交接已变化");
                var previousEnergy=PipeNetworkSolver.energy(before.network(),world.definitions(),9.81);
                var flowEnergy=PipeNetworkSolver.energy(exchange.state(),world.definitions(),9.81);
                var nextEnergy=PipeNetworkSolver.energy(heat.state(),world.definitions(),9.81);
                var audit=before.audit();var roundoff=exchange.energyRoundoff().add(heat.roundoff());
                var external=flowEnergy.subtract(previousEnergy).subtract(exchange.energyRoundoff()).subtract(exchange.externalWork());
                var heatInto=nextEnergy.subtract(flowEnergy).subtract(heat.roundoff());
                var wallExchange=SignedDyadic.ZERO;for(var value:deltas.values())wallExchange=wallExchange.add(SignedDyadic.of(value));
                if(!heatInto.equals(wallExchange.negate()))throw new IllegalStateException("管壁与管腔热量不一致");
                var nextAudit=new PipeAudit(audit.initialEnergy(),audit.suppliedEnergy().add(external),audit.removedEnergy(),
                        audit.externalWork().add(exchange.externalWork()),audit.environmentHeat().add(heatInto),audit.roundoff().add(roundoff));
                var next=new PipeDimensionState(Math.incrementExact(before.generation()),heat.state(),nextAudit,before.installations());
                exchange.commit(wallCandidate);dimensions.put(dimension,next);
                if(wallCandidate!=null)environment.pipeWallExchangeAdopted(dimension);
                exchange.publishObservations();publish(level,next);
            }
        }
    }
    private void publish(ServerLevel level,PipeDimensionState state) {
        String dimension=level.dimension().identifier().toString();
        long generation=Math.max(state==null?0:state.generation(),Math.incrementExact(displayGenerations.getOrDefault(dimension,0L)));
        displayGenerations.put(dimension,generation);
        if(state==null)snapshots.clear(level,generation);else snapshots.accept(level,generation,state.network(),PipeWorldGraph.bores(state));
    }
    private void failed(String dimension,RuntimeException failure) {
        String detail=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
        if(!detail.equals(failures.put(dimension,detail)))MmmMod.LOGGER.warn("Pipe dimension {} paused: {}",dimension,detail);
    }
}
