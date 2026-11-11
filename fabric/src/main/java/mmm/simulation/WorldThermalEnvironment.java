package mmm.simulation;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import mmm.fluid.EnvironmentBinding;
import mmm.fluid.FluidDomainContracts;
import mmm.fluid.FluidSolverBackend;
import mmm.thermal.*;
import mmm.units.SignedDyadic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Dimension-owned finite world inventories and their projected region contacts. */
public final class WorldThermalEnvironment {
    public static final String PROVIDER = "mmm:world_wall";
    private static final int HALO = 4;
    private static final double TEMPERATURE = 293.15;
    private final FluidSolverBackend backend;
    private final CommittedWorldRegistries definitions;
    private final FluidRegionManager regions;
    private final ThermalBoundaryResolver boundaries;
    private final Set<String> configured = new HashSet<>(), dirty = new HashSet<>();
    private final Map<String,Set<BlockPos>> pipePositions = new HashMap<>();
    private final Map<String, EnvironmentThermalState> states = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private final Map<UUID, EnvironmentBinding> bindings = new HashMap<>();
    private final Map<String, Map<BlockPos, Direction.Axis>> screenAxes = new HashMap<>();
    private final Map<String, Map<EnvironmentThermalState.Key, Long>> airMasks = new HashMap<>();
    private final Map<String, Map<BlockPos, String>> blockIdentities = new HashMap<>();
    private record Sample(BlockState state, byte[] masks) { }
    private final Map<String, Map<BlockPos, Sample>> samples = new HashMap<>();
    private MinecraftWorldUpdates worldUpdates;
    private ExternalMovingStructures externalStructures;
    private final Map<String,List<ExternalMovingStructures.CompletedHandoff>> preparedStructures = new HashMap<>();

    public WorldThermalEnvironment(FluidSolverBackend backend, CommittedWorldRegistries definitions, FluidRegionManager regions) {
        this.backend = Objects.requireNonNull(backend); this.definitions = Objects.requireNonNull(definitions); this.regions = Objects.requireNonNull(regions);
        this.boundaries = new ThermalBoundaryResolver(definitions.thermalRegistry());
    }
    public void worldUpdates(MinecraftWorldUpdates updates) { worldUpdates = Objects.requireNonNull(updates); }
    public void pipePositions(String dimension,Set<BlockPos> positions) {
        var next=Set.copyOf(positions);var previous=pipePositions.put(Objects.requireNonNull(dimension),next);
        if(!next.equals(previous))dirty.add(dimension);
    }
    /** The inner contact belongs to the independent pipe inventory; the solid remains world-owned. */
    public record PipeWall(String dimension,long generation,EnvironmentThermalState.Key key,String materialId,
                           long massUnits,double energy,double innerArea,double conductivity,double halfThickness) { }
    public PipeWall pipeWall(String dimension,BlockPos pos,BlockState state) {
        return pipeWalls(dimension,Map.of(pos,state)).get(pos);
    }
    public Map<BlockPos,PipeWall> pipeWalls(String dimension,Map<BlockPos,BlockState> requested) {
        if(!configured.contains(dimension))return Map.of();
        var environment=backend.captureEnvironment(dimension);
        var index=new HashMap<EnvironmentThermalState.Key,EnvironmentThermalState.Node>();
        for(var node:environment.nodes())if(node.key().kind()==EnvironmentThermalState.Kind.SOLID)index.put(node.key(),node);
        var result=new HashMap<BlockPos,PipeWall>();var sampled=samples.getOrDefault(dimension,Map.of());
        for(var entry:requested.entrySet()) {
            var pos=entry.getKey();var state=entry.getValue();if(!PipeThermalGeometry.isPipe(state))continue;
            var sample=sampled.get(pos);if(sample==null || !sample.state().equals(state))continue;
            var key=new EnvironmentThermalState.Key(pos.getX(),pos.getY(),pos.getZ(),EnvironmentThermalState.Kind.SOLID,0);
            var node=index.get(key);if(node==null)continue;
            var expected=BlockThermalMaterials.resolve(state,definitions.thermalRegistry());
            if(!node.materialId().equals(expected.thermalMaterialId()) || node.volumeCubicMetres()!=mmm.block.PipeGeometry.wallVolume(state))continue;
            var material=definitions.thermalRegistry().material(definitions.thermalRegistry().materialHandle(node.materialId()));
            result.put(pos,new PipeWall(dimension,environment.generation(),key,node.materialId(),node.massUnits(),node.internalEnergyJ(),
                    mmm.block.PipeGeometry.innerArea(state),material.conductivityWPerMeterK(),.03125));
        }
        return Map.copyOf(result);
    }
    /** Prepares the world half of an already calculated pipe/wall heat exchange. */
    public mmm.fluid.PreparedEnvironmentState preparePipeWallExchange(String dimension,long generation,
            Map<EnvironmentThermalState.Key,Double> energyDeltas) {
        var current=backend.captureEnvironment(dimension);
        if(current.generation()!=generation)throw new IllegalStateException("Pipe wall generation changed");
        var remaining=new HashMap<>(energyDeltas); var nodes=new ArrayList<EnvironmentThermalState.Node>();
        SignedDyadic exchange=SignedDyadic.ZERO,roundoff=SignedDyadic.ZERO;
        for(var node:current.nodes()) {
            Double delta=remaining.remove(node.key());
            if(delta==null) { nodes.add(node); continue; }
            var key=node.key(); var pos=new BlockPos(key.x(),key.y(),key.z());
            var sample=samples.getOrDefault(dimension,Map.of()).get(pos);
            if(key.kind()!=EnvironmentThermalState.Kind.SOLID || key.component()!=0 || sample==null
                    || !PipeThermalGeometry.isPipe(sample.state()) || !Double.isFinite(delta))
                throw new IllegalArgumentException("Invalid pipe wall exchange");
            double next=node.internalEnergyJ()+delta;
            nodes.add(node.withEnergy(next)); exchange=exchange.add(SignedDyadic.of(delta));
            roundoff=roundoff.add(SignedDyadic.of(next).subtract(SignedDyadic.of(node.internalEnergyJ())).subtract(SignedDyadic.of(delta)));
        }
        if(!remaining.isEmpty())throw new IllegalArgumentException("Pipe wall node is unavailable");
        var audit=current.audit();
        var nextAudit=new EnvironmentThermalState.Audit(audit.initial(),audit.source(),audit.sink(),
                audit.regionExchange().add(exchange),audit.roundoff().add(roundoff));
        return backend.prepareEnvironment(new EnvironmentThermalState(dimension,current.generation(),current.registryFingerprint(),
                nodes,current.contacts(),nextAudit),definitions.definitions());
    }
    /** Refreshes cached world state after the prepared pipe and environment commits succeed. */
    public void pipeWallExchangeAdopted(String dimension) {
        states.put(dimension,backend.captureEnvironment(dimension)); revisions.merge(dimension,1L,Math::addExact);
        bindings.clear(); dirty.add(dimension);
    }
    public final class PipeAirHandoff implements AutoCloseable {
        private final mmm.fluid.PreparedEnvironmentState prepared;
        private final Map<BlockPos,PipeTopologyEdit.Air> acquired;
        private boolean adopted;
        private PipeAirHandoff(mmm.fluid.PreparedEnvironmentState prepared,Map<BlockPos,PipeTopologyEdit.Air> acquired) {
            this.prepared=prepared;this.acquired=Map.copyOf(acquired);
        }
        public Map<BlockPos,PipeTopologyEdit.Air> acquired(){return acquired;}
        public mmm.fluid.PreparedEnvironmentState prepared(){return prepared;}
        public boolean isCurrent(){return !adopted && prepared.isCurrent();}
        public void commit(){prepared.commit();adopted();}
        public void adopted(){
            if(adopted)throw new IllegalStateException("Pipe air handoff already adopted");
            states.put(prepared.dimensionId(),backend.captureEnvironment(prepared.dimensionId()));
            revisions.merge(prepared.dimensionId(),1L,Math::addExact);bindings.clear();dirty.add(prepared.dimensionId());adopted=true;
        }
        @Override public void close(){prepared.close();}
    }
    /** Stages pipe gas exchange with the finite world air outside mechanical regions. */
    public PipeAirHandoff preparePipeAir(ServerLevel level,Map<BlockPos,Double> newCavityVolumes,
                                          Map<BlockPos,PipeTopologyEdit.Air> returned) {
        String dimension=level.dimension().identifier().toString();
        return preparePipeAir(dimension,newCavityVolumes,returned,level::getBlockState,
                pos->MinecraftGroupAvailability.loaded(level,pos),pos->regions.regions().stream().anyMatch(r->r.dimension().equals(level.dimension())
                        && r.footprint()!=null && r.footprint().containsInterior(pos)));
    }
    PipeAirHandoff preparePipeAir(String dimension,Map<BlockPos,Double> newCavityVolumes,
            Map<BlockPos,PipeTopologyEdit.Air> returned,Function<BlockPos,BlockState> stateAt,Predicate<BlockPos> loaded,
            Predicate<BlockPos> mechanical) {
        var positions=new HashSet<BlockPos>(newCavityVolumes.keySet());positions.addAll(returned.keySet());
        for(var pos:positions) {
            if(!loaded.test(pos))throw new IllegalStateException("Pipe air chunk is unavailable");
            if(mechanical.test(pos))throw new IllegalArgumentException("Pipe air belongs to a mechanical region");
        }
        if(!configured.contains(dimension)) {
            var empty=EnvironmentThermalState.initialized(dimension,definitions.thermalRegistry().fingerprint(),List.of(),List.of());
            backend.configureEnvironment(empty,definitions.definitions());configured.add(dimension);states.put(dimension,empty);
        }
        var current=backend.captureEnvironment(dimension); var nodes=new ArrayList<>(current.nodes());
        var acquired=new HashMap<BlockPos,PipeTopologyEdit.Air>();
        var model=new GasThermalModel(definitions.thermalRegistry(),definitions.gasRegistry());
        var exchange=SignedDyadic.ZERO;var roundoff=SignedDyadic.ZERO;
        for(var entry:newCavityVolumes.entrySet()) {
            var pos=entry.getKey();double volume=entry.getValue();var state=stateAt.apply(pos);
            if(!PipeThermalGeometry.isPipe(state) || !Double.isFinite(volume) || volume<=0)
                throw new IllegalArgumentException("Invalid new pipe cavity");
            if(Math.abs(PipeWorldGraph.capacity(state)-volume)>1e-10)throw new IllegalArgumentException("Pipe cavity volume differs from geometry");
            var sample=samples.getOrDefault(dimension,Map.of()).get(pos);
            var indexes=new ArrayList<Integer>();
            for(int i=0;i<nodes.size();i++) {var key=nodes.get(i).key();if(key.kind()==EnvironmentThermalState.Kind.AIR
                    && key.x()==pos.getX()&&key.y()==pos.getY()&&key.z()==pos.getZ())indexes.add(i);}
            if(indexes.isEmpty()) {
                if(sample!=null)throw new IllegalStateException("Tracked pipe cavity has no available world air");
                var gas=model.inventoryAtPressure(volume,mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA,TEMPERATURE,definitions.gasRegistry().ambient());
                acquired.put(pos,new PipeTopologyEdit.Air(gas,model.internalEnergyAtTemperature(gas,TEMPERATURE)));
                continue;
            }
            if(sample!=null && PipeThermalGeometry.isPipe(sample.state()))
                throw new IllegalStateException("Pipe cavity must be acquired before its world air is rebuilt");
            var gas=mmm.fluid.air.GasInventory.EMPTY;double energy=0,covered=0;
            var oldEnergy=SignedDyadic.ZERO;var remainingEnergy=SignedDyadic.ZERO;
            for(int index:indexes) {
                var old=nodes.get(index);Long mask=airMasksFor(dimension).get(old.key());
                if(mask==null)throw new IllegalStateException("Previous world air topology is unavailable");
                if(Math.abs(old.volumeCubicMetres()-Long.bitCount(mask)/64.0)>1e-12)
                    throw new IllegalStateException("Pipe cavity must be acquired before its world air is rebuilt");
                double part=PipeThermalGeometry.cavityOverlap(state,mask);if(part<=0)continue;
                if(part>old.volumeCubicMetres()+1e-12)throw new IllegalStateException("Pipe cavity exceeds available air");
                var shares=old.gas().partition(mmm.fluid.air.GasPartitionWeights.of(new double[]{part,Math.max(0,old.volumeCubicMetres()-part)}),definitions.gasRegistry(),definitions.chemicalOrNull());
                var energies=model.partitionEnergy(old.gas(),old.internalEnergyJ(),shares);
                if(shares.get(1).isEmpty())throw new IllegalStateException("Pipe acquisition would remove a complete world air component");
                gas=gas.merge(shares.get(0));energy+=energies[0];covered+=part;
                nodes.set(index,new EnvironmentThermalState.Node(old.key(),old.materialId(),shares.get(1).totalMassUnits(),old.volumeCubicMetres(),shares.get(1),energies[1],old.surfaces()));
                oldEnergy=oldEnergy.add(SignedDyadic.of(old.internalEnergyJ()));
                remainingEnergy=remainingEnergy.add(SignedDyadic.of(energies[1]));
            }
            if(Math.abs(covered-volume)>1e-10 || gas.isEmpty())throw new IllegalStateException("Tracked world air does not cover the new pipe cavity");
            exchange=exchange.subtract(SignedDyadic.of(energy));
            roundoff=roundoff.add(remainingEnergy.subtract(oldEnergy).add(SignedDyadic.of(energy)));
            acquired.put(pos,new PipeTopologyEdit.Air(gas,energy));
        }
        for(var entry:returned.entrySet()) {
            var pos=entry.getKey();var air=entry.getValue();if(air.inventory().isEmpty()) {if(air.energy()!=0)throw new IllegalArgumentException("Empty pipe gas has energy");continue;}
            int index=-1;
            for(int i=0;i<nodes.size();i++){var key=nodes.get(i).key();if(key.kind()==EnvironmentThermalState.Kind.AIR && key.x()==pos.getX()&&key.y()==pos.getY()&&key.z()==pos.getZ()){
                if(index>=0)throw new IllegalStateException("Pipe return requires a single external air component");index=i;}}
            if(index<0) {
                var state=stateAt.apply(pos);double volume=PipeThermalGeometry.isPipe(state)?PipeThermalGeometry.outsideVolume(state):1.0;
                var key=new EnvironmentThermalState.Key(pos.getX(),pos.getY(),pos.getZ(),EnvironmentThermalState.Kind.AIR,0);
                nodes.add(new EnvironmentThermalState.Node(key,"mmm:air",air.inventory().totalMassUnits(),volume,air.inventory(),air.energy(),List.of()));
            } else {
                var old=nodes.get(index);var gas=old.gas().merge(air.inventory());double energy=old.internalEnergyJ()+air.energy();
                nodes.set(index,new EnvironmentThermalState.Node(old.key(),old.materialId(),gas.totalMassUnits(),old.volumeCubicMetres(),gas,energy,old.surfaces()));
                roundoff=roundoff.add(SignedDyadic.of(energy).subtract(SignedDyadic.of(old.internalEnergyJ())).subtract(SignedDyadic.of(air.energy())));
            }
            exchange=exchange.add(SignedDyadic.of(air.energy()));
        }
        var audit=current.audit();var nextAudit=new EnvironmentThermalState.Audit(audit.initial(),audit.source(),audit.sink(),
                audit.regionExchange().add(exchange),audit.roundoff().add(roundoff));
        var next=new EnvironmentThermalState(dimension,current.generation(),current.registryFingerprint(),nodes,current.contacts(),nextAudit);
        return new PipeAirHandoff(backend.prepareEnvironment(next,definitions.definitions()),acquired);
    }
    void externalStructures(ExternalMovingStructures external) { externalStructures=Objects.requireNonNull(external); external.environment(this); }
    boolean hasSolid(String dimension, BlockPos position) {
        return configured.contains(dimension) && backend.captureEnvironment(dimension).nodes().stream().anyMatch(node ->
                node.key().kind()==EnvironmentThermalState.Kind.SOLID && node.key().x()==position.getX()
                        && node.key().y()==position.getY() && node.key().z()==position.getZ());
    }
    boolean hasCarried(String dimension,String provider,UUID structure) {
        return configured.contains(dimension) && ExternalStructureThermalHandoff.hasCarried(backend.captureEnvironment(dimension),provider,structure);
    }
    mmm.fluid.PreparedEnvironmentState prepareStructureHandoff(FluidRegion region,List<ExternalMovingStructures.CompletedHandoff> handoffs,
            Function<mmm.api.motion.MovingStructureApi.FixedBlock,String> axisAt) {
        String dimension=region.dimension().identifier().toString();
        if(!configured.contains(dimension))return null;
        var base=backend.captureEnvironment(dimension);
        EnvironmentThermalState next;
        try { next=ExternalStructureThermalHandoff.plan(base,handoffs,block->{
            boolean assembling=handoffs.stream().anyMatch(h->h.handoff().kind()==mmm.api.motion.MovingStructureApi.HandoffKind.ASSEMBLE && h.handoff().blocks().contains(block));
            if(!assembling)return axisAt.apply(block);
            var axis=screenAxes.getOrDefault(dimension,Map.of()).get(new BlockPos(block.x(),block.y(),block.z()));
            return axis==null?null:axis.name();
        }); } catch(IllegalArgumentException invalid) { throw new ExternalMovingStructures.InputRejectedException(invalid.getMessage(),invalid); }
        if(next.equals(base))return null;
        var prepared=backend.prepareEnvironment(next,definitions.definitions());
        preparedStructures.put(dimension,List.copyOf(handoffs));
        return prepared;
    }
    void structureHandoffAccepted(String dimension) {
        var identities=new HashMap<>(blockIdentities.getOrDefault(dimension,Map.of()));
        for(var item:preparedStructures.getOrDefault(dimension,List.of()))for(var block:item.handoff().blocks()) {
            var pos=new BlockPos(block.x(),block.y(),block.z());
            if(item.handoff().kind()==mmm.api.motion.MovingStructureApi.HandoffKind.ASSEMBLE)identities.remove(pos);
            else identities.put(pos,block.blockId());
        }
        preparedStructures.remove(dimension); blockIdentities.put(dimension,Map.copyOf(identities));
        states.put(dimension,backend.captureEnvironment(dimension)); revisions.merge(dimension,1L,Math::addExact);
        bindings.clear(); dirty.add(dimension);
        for(var region:regions.regions())if(region.solverLoaded() && region.hasThermalState()
                && region.dimension().identifier().toString().equals(dimension))
            project(region,dimension,(int)Math.round(1/region.solverConfig().cellSize()));
    }
    public void markDirty(String dimension) { dirty.add(dimension); }
    void regionGeometryChanged(FluidRegion region) {
        bindings.remove(region.id());
        markDirty(region.dimension().identifier().toString());
    }
    public void markDirty(String dimension, BlockPos position) {
        var cache=samples.get(dimension);
        if(cache!=null) { cache.remove(position); for(var direction:Direction.values()) cache.remove(position.relative(direction)); }
        var managed=blockIdentities.getOrDefault(dimension,Map.of());
        if(managed.containsKey(position) || Arrays.stream(Direction.values()).anyMatch(direction->managed.containsKey(position.relative(direction)))) {
            dirty.add(dimension); return;
        }
        for(var region:regions.regions()) if(region.dimension().identifier().toString().equals(dimension)) {
            double h=region.solverConfig().cellSize(); var origin=region.origin();
            if(position.getX()>=origin.getX()-HALO && position.getX()<origin.getX()+Math.ceil(region.sizeX()*h)+HALO
                    && position.getY()>=origin.getY()-HALO && position.getY()<origin.getY()+Math.ceil(region.sizeY()*h)+HALO
                    && position.getZ()>=origin.getZ()-HALO && position.getZ()<origin.getZ()+Math.ceil(region.sizeZ()*h)+HALO) {
                dirty.add(dimension); return;
            }
        }
    }
    public void restore(Map<String, EnvironmentThermalState> restored) {
        for (var entry : restored.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().dimensionId())) throw new IllegalArgumentException("Environment dimension differs");
            backend.configureEnvironment(entry.getValue(), definitions.definitions());
            states.put(entry.getKey(), entry.getValue()); revisions.merge(entry.getKey(), 1L, Math::addExact); configured.add(entry.getKey()); dirty.add(entry.getKey());
        }
    }
    /** Prepares the saved world successor before its durable publication. */
    public mmm.fluid.PreparedEnvironmentState prepareCheckpoint(EnvironmentThermalState candidate) {
        var current=backend.captureEnvironment(candidate.dimensionId());
        if(candidate.generation()!=Math.addExact(current.generation(),1L))
            throw new IllegalStateException("Environment checkpoint candidate is stale");
        var base=new EnvironmentThermalState(candidate.dimensionId(),current.generation(),candidate.registryFingerprint(),candidate.nodes(),candidate.contacts(),candidate.audit());
        return backend.prepareEnvironment(base,definitions.definitions());
    }
    public void adoptCheckpoint(EnvironmentThermalState candidate, mmm.fluid.PreparedEnvironmentState prepared) {
        prepared.commit();
        states.put(candidate.dimensionId(),candidate); revisions.merge(candidate.dimensionId(),1L,Math::addExact);
        bindings.clear(); dirty.add(candidate.dimensionId());
    }
    Map<EnvironmentThermalState.Key,Long> airMasksFor(String dimension) {
        return airMasks.getOrDefault(dimension,Map.of());
    }
    boolean tracksPosition(String dimension, BlockPos pos) {
        var identities=blockIdentities.get(dimension);
        if(identities!=null)return identities.containsKey(pos);
        var state=states.get(dimension); if(state==null)return false;
        return state.nodes().stream().anyMatch(node->node.key().x()==pos.getX() && node.key().y()==pos.getY() && node.key().z()==pos.getZ());
    }
    public EnvironmentThermalState current(String dimension) {
        return backend.captureEnvironment(dimension);
    }
    public Map<String, EnvironmentThermalState> capture() {
        var result = new HashMap<String, EnvironmentThermalState>();
        for (String dimension : configured) result.put(dimension, backend.captureEnvironment(dimension));
        return Map.copyOf(result);
    }
    private void deviceOperation(Runnable operation) {
        if(backend.unavailableReason()!=null)return;
        try { operation.run(); }
        catch(RuntimeException failure) {
            if(backend.unavailableReason()==null)throw failure;
            mmm.MmmMod.LOGGER.error("World thermal execution stopped after Vulkan device loss",failure);
            for(var region:regions.regions())region.pauseForUnavailableDevice();
        }
    }
    public void queueAdvance(String dimension, double seconds) {
        deviceOperation(()->{if(configured.contains(dimension) && !dirty.contains(dimension))backend.queueEnvironmentAdvance(dimension,seconds);});
    }
    public void flushAdvance(String dimension) {
        deviceOperation(()->{if(configured.contains(dimension))backend.flushEnvironmentAdvance(dimension);});
    }
    /** Stages edits of world-owned wall energy and surface inventory for a joint region commit. */
    public mmm.fluid.PreparedEnvironmentState prepareDeviceEdit(EnvironmentBinding binding, FiniteThermalDeviceInventory inventory) {
        if(binding==null)throw new IllegalArgumentException("World thermal binding is missing");
        var current=backend.captureEnvironment(binding.dimensionId());
        var devices=new HashMap<String,FiniteThermalDeviceState>();
        for(var device:inventory.devices())devices.put(device.providerId()+"\0"+device.objectId(),device);
        var updates=new HashMap<EnvironmentThermalState.Key,FiniteThermalDeviceState>();
        for(var ref:binding.devices()) {
            var device=devices.get(ref.providerId()+"\0"+ref.objectId());
            if(device==null || updates.put(ref.node(),device)!=null)throw new IllegalArgumentException("World device binding is incomplete");
        }
        var nodes=new ArrayList<EnvironmentThermalState.Node>(current.nodes().size()); SignedDyadic added=SignedDyadic.ZERO,removed=SignedDyadic.ZERO;
        for(var node:current.nodes()) {
            var device=updates.remove(node.key());
            if(device==null){nodes.add(node);continue;}
            var body=device.body();
            if(!node.materialId().equals(body.thermalMaterialId())||node.massUnits()!=body.massUnits())throw new IllegalArgumentException("World device material changed");
            var delta=SignedDyadic.of(body.internalEnergyJ()).subtract(SignedDyadic.of(node.internalEnergyJ()));
            if(delta.signum()>0)added=added.add(delta); else removed=removed.subtract(delta);
            nodes.add(new EnvironmentThermalState.Node(node.key(),node.materialId(),node.massUnits(),node.volumeCubicMetres(),node.gas(),body.internalEnergyJ(),device.surfaces(),node.owner()));
        }
        if(!updates.isEmpty())throw new IllegalArgumentException("World device node is missing");
        var audit=current.audit(); audit=new EnvironmentThermalState.Audit(audit.initial(),audit.source().add(added),audit.sink().add(removed),audit.regionExchange(),audit.roundoff());
        var next=new EnvironmentThermalState(current.dimensionId(),current.generation(),current.registryFingerprint(),nodes,current.contacts(),audit);
        return backend.prepareEnvironment(next,definitions.definitions());
    }
    /** Reconciles chunk events even when all nearby mechanical regions are waiting on unloaded chunks. */
    public void refreshDirty(ServerLevel level) {
        deviceOperation(()->refreshDirtyAvailable(level));
    }
    void refreshDirty(String dimension, Map<BlockPos,BlockState> blocks) {
        deviceOperation(()->{
            if(!dirty.remove(dimension))return;
            var region=regions.regions().stream().filter(r->r.dimension().identifier().toString().equals(dimension)).findFirst().orElse(null);
            try { rebuild(dimension,region,p->blocks.getOrDefault(p,Blocks.AIR.defaultBlockState()),p->true,EmptyBlockGetter.INSTANCE); }
            catch(RuntimeException failure){dirty.add(dimension);throw failure;}
        });
    }
    private void refreshDirtyAvailable(ServerLevel level) {
        String dimension=level.dimension().identifier().toString();
        if(!dirty.contains(dimension))return;
        if(externalStructures!=null && externalStructures.pendingThermalPositions(dimension)==null)return;
        var region=regions.regions().stream().filter(r->r.dimension().equals(level.dimension())).findFirst().orElse(null);
        if(region==null && !configured.contains(dimension) && pipePositions.getOrDefault(dimension,Set.of()).isEmpty())return;
        dirty.remove(dimension);
        try { rebuild(dimension,region,level::getBlockState,p->MinecraftGroupAvailability.loaded(level,p),level); }
        catch(RuntimeException failure){dirty.add(dimension);throw failure;}
    }
    /** A private air handoff; callers commit only after the matching region state is accepted. */
    public final class AirHandoff {
        private final EnvironmentThermalState base, candidate;
        private final Map<EnvironmentThermalState.Key,Long> masks;
        private final Map<BlockPos, List<EnvironmentThermalState.Node>> air;
        private final Map<BlockPos,double[]> pipeVolumes;
        private boolean closed;
        private final long revision;
        private AirHandoff(EnvironmentThermalState base, EnvironmentThermalState candidate,
                           Map<EnvironmentThermalState.Key,Long> masks, Map<BlockPos, List<EnvironmentThermalState.Node>> air,
                           Map<BlockPos,double[]> pipeVolumes) {
            this.base = base; this.candidate=candidate; this.masks=Map.copyOf(masks);
            this.air = Map.copyOf(air); this.pipeVolumes=Map.copyOf(pipeVolumes); this.revision = revisions.getOrDefault(base.dimensionId(),0L);
        }
        /** Four-by-four-by-four microboxes, bit index x + 4*y + 16*z. */
        public Map<EnvironmentThermalState.Key, Long> microMasks() {
            var result=new HashMap<EnvironmentThermalState.Key,Long>();
            for(var nodes:air.values()) for(var node:nodes) {
                Long mask=masks.get(node.key());
                if(mask==null)throw new IllegalStateException("Air topology must be sampled before handoff");
                result.put(node.key(),mask);
            }
            return Map.copyOf(result);
        }
        public record Transfer(List<mmm.fluid.air.GasInventory> cells, double[] energy,
                               mmm.fluid.air.GasAuditLedger source, SignedDyadic roundoff) {
            public Transfer { cells=List.copyOf(cells); energy=energy.clone(); }
            @Override public double[] energy() { return energy.clone(); }
        }
        /** Partitions each complete air component among target 0.5 m cells using its open microboxes. */
        public Transfer split(BlockPos origin, mmm.spatial.SpatialGrid grid) {
            var masks=microMasks();
            var cells=new ArrayList<mmm.fluid.air.GasInventory>(Collections.nCopies(grid.cellCount(),mmm.fluid.air.GasInventory.EMPTY));
            double[] energy=new double[grid.cellCount()];
            var source=mmm.fluid.air.GasAuditLedger.EMPTY; SignedDyadic inputEnergy=SignedDyadic.ZERO;
            var model=new GasThermalModel(definitions.thermalRegistry(),definitions.gasRegistry());
            for(var entry:air.entrySet()) for(var node:entry.getValue()) {
                long mask=masks.get(node.key()); var weights=new double[8]; int[] destinations=new int[8];
                for(int sub=0;sub<8;sub++) {
                    int sx=sub&1,sy=(sub>>1)&1,sz=(sub>>2)&1;
                    int x=(entry.getKey().getX()-origin.getX())*2+sx;
                    int y=(entry.getKey().getY()-origin.getY())*2+sy;
                    int z=(entry.getKey().getZ()-origin.getZ())*2+sz;
                    destinations[sub]=grid.cell(x,y,z);
                    double[] pipe=pipeVolumes.get(entry.getKey());
                    if(pipe!=null)weights[sub]=pipe[sub];
                    else for(int mz=0;mz<2;mz++)for(int my=0;my<2;my++)for(int mx=0;mx<2;mx++) {
                        int bit=(sx*2+mx)+4*(sy*2+my)+16*(sz*2+mz);
                        if((mask&(1L<<bit))!=0)weights[sub]++;
                    }
                    if(weights[sub]>0 && destinations[sub]<0) throw new IllegalArgumentException("Air handoff does not cover its complete component");
                }
                var parts=node.gas().partition(mmm.fluid.air.GasPartitionWeights.of(weights),definitions.gasRegistry(),definitions.chemicalOrNull());
                var energies=model.partitionEnergy(node.gas(),node.internalEnergyJ(),parts);
                for(int sub=0;sub<8;sub++) if(!parts.get(sub).isEmpty()) {
                    int destination=destinations[sub]; cells.set(destination,cells.get(destination).merge(parts.get(sub))); energy[destination]+=energies[sub];
                }
                source=source.add(node.gas()); inputEnergy=inputEnergy.add(SignedDyadic.of(node.internalEnergyJ()));
            }
            SignedDyadic outputEnergy=SignedDyadic.ZERO; for(double value:energy)outputEnergy=outputEnergy.add(SignedDyadic.of(value));
            return new Transfer(cells,energy,source,outputEnergy.subtract(inputEnergy));
        }
        public void discard() { closed = true; }
        public mmm.fluid.PreparedEnvironmentState prepare() {
            if (closed) throw new IllegalStateException("Air handoff already closed");
            var current = backend.captureEnvironment(base.dimensionId());
            if (current.generation() != base.generation() || revisions.getOrDefault(base.dimensionId(),0L) != revision)
                throw new IllegalStateException("Environment changed during air handoff");
            var removedKeys = new HashSet<EnvironmentThermalState.Key>();
            SignedDyadic energy = SignedDyadic.ZERO;
            for (var list : air.values()) for (var node : list) { removedKeys.add(node.key()); energy = energy.add(SignedDyadic.of(node.internalEnergyJ())); }
            int[] remap = new int[candidate.nodes().size()]; Arrays.fill(remap, -1);
            var nodes = new ArrayList<EnvironmentThermalState.Node>();
            for (int i=0;i<candidate.nodes().size();i++) if (!removedKeys.contains(candidate.nodes().get(i).key())) {
                remap[i]=nodes.size(); nodes.add(candidate.nodes().get(i));
            }
            var contacts = new ArrayList<EnvironmentThermalState.Contact>();
            for (var c : candidate.contacts()) if (remap[c.a()] >= 0 && (c.b() < 0 || remap[c.b()] >= 0))
                contacts.add(new EnvironmentThermalState.Contact(remap[c.a()], c.b() < 0 ? -1 : remap[c.b()], c.conductanceWPerK(), c.farTemperatureK()));
            var audit=candidate.audit();
            audit=new EnvironmentThermalState.Audit(audit.initial(),audit.source(),audit.sink(),audit.regionExchange().subtract(energy),audit.roundoff());
            var next=new EnvironmentThermalState(current.dimensionId(),current.generation(),current.registryFingerprint(),nodes,contacts,audit);
            return backend.prepareEnvironment(next,definitions.definitions());
        }
        /** Called after the attached region and environment candidates have both been adopted. */
        public void adopted() {
            if (closed) throw new IllegalStateException("Air handoff already closed");
            states.put(base.dimensionId(),backend.captureEnvironment(base.dimensionId()));
            revisions.merge(base.dimensionId(),1L,Math::addExact); closed=true;
            bindings.clear(); dirty.add(base.dimensionId());
        }
    }
    /** Samples a prospective region and halo without adopting its graph or air inventory. */
    public AirHandoff prepareRange(ServerLevel level, FluidRegion candidate, Set<BlockPos> acquired) {
        String dimension=level.dimension().identifier().toString();
        if(!candidate.dimension().equals(level.dimension()))throw new IllegalArgumentException("Environment dimension differs from region");
        if(!configured.contains(dimension)) {
            var empty=EnvironmentThermalState.initialized(dimension,definitions.thermalRegistry().fingerprint(),List.of(),List.of());
            backend.configureEnvironment(empty,definitions.definitions()); configured.add(dimension); states.put(dimension,empty);
        }
        return prepareRange(candidate,acquired,level::getBlockState,p->MinecraftGroupAvailability.loaded(level,p),level);
    }
    AirHandoff prepareRange(FluidRegion candidate, Set<BlockPos> acquired, Map<BlockPos,BlockState> blocks) {
        String dimension=candidate.dimension().identifier().toString();
        if(!configured.contains(dimension)) {
            var empty=EnvironmentThermalState.initialized(dimension,definitions.thermalRegistry().fingerprint(),List.of(),List.of());
            backend.configureEnvironment(empty,definitions.definitions()); configured.add(dimension); states.put(dimension,empty);
        }
        return prepareRange(candidate,acquired,p->blocks.getOrDefault(p,Blocks.AIR.defaultBlockState()),p->true,EmptyBlockGetter.INSTANCE);
    }
    private AirHandoff prepareRange(FluidRegion candidate,Set<BlockPos> acquired,Function<BlockPos,BlockState> stateAt,
                                    Predicate<BlockPos> loaded,BlockGetter world) {
        String dimension=candidate.dimension().identifier().toString();
        var base=backend.captureEnvironment(dimension);
        var built=build(dimension,candidate,stateAt,loaded,world,false,null);
        var air=new HashMap<BlockPos,List<EnvironmentThermalState.Node>>();
        for(var node:built.state().nodes()) if(node.key().kind()==EnvironmentThermalState.Kind.AIR) {
            var pos=new BlockPos(node.key().x(),node.key().y(),node.key().z());
            if(acquired.contains(pos))air.computeIfAbsent(pos,ignored->new ArrayList<>()).add(node);
        }
        air.replaceAll((pos,nodes)->List.copyOf(nodes));
        var pipeVolumes=new HashMap<BlockPos,double[]>();
        for(var pos:air.keySet()) { var state=stateAt.apply(pos); if(PipeThermalGeometry.isPipe(state))
            pipeVolumes.put(pos,PipeThermalGeometry.halfCellVolumes(state)); }
        return new AirHandoff(base,built.state(),built.masks(),air,pipeVolumes);
    }
    /** A nonstandard gas composition must be explicitly vented before region removal. */
    public boolean canReturnAir(FluidRegion region) {
        var spatial=region.hostSnapshot().captureAuthoritativeState().spatialGas();
        if(spatial==null)return false;
        var total=mmm.fluid.air.GasInventory.EMPTY;
        for(var cell:spatial.cells())total=total.merge(cell);
        if(total.isEmpty())return true;
        var expected=definitions.gasRegistry().createAmbient(total.standardVolumeNanos());
        if(total.components().size()!=expected.components().size())return false;
        long roundingBound=Math.multiplyExact((long)spatial.cellCount(),16L);
        for(var actual:total.components()) {
            var standard=expected.component(actual.componentId());
            if(standard==null || !near(actual.standardVolumeNanos(),standard.standardVolumeNanos(),roundingBound)
                    || !near(actual.massUnits(),standard.massUnits(),Math.multiplyExact(roundingBound,10000L)) || actual.species().size()!=standard.species().size())return false;
            for(int i=0;i<actual.species().size();i++) {
                var a=actual.species().get(i); var b=standard.species().get(i);
                if(!a.speciesId().equals(b.speciesId()) || !near(a.amountQuanta(),b.amountQuanta(),roundingBound))return false;
            }
        }
        return true;
    }
    private static boolean near(long actual,long expected,long tolerance) {
        return Math.abs((double)actual-expected)<=tolerance;
    }
    public final class AirReturn implements AutoCloseable {
        private final mmm.fluid.PreparedEnvironmentState prepared;
        private AirReturn(mmm.fluid.PreparedEnvironmentState prepared){this.prepared=prepared;}
        public EnvironmentThermalState snapshot(){return prepared.capture();}
        public boolean isCurrent(){return prepared.isCurrent();}
        public void commit(){prepared.commit();states.put(prepared.dimensionId(),backend.captureEnvironment(prepared.dimensionId()));
            revisions.merge(prepared.dimensionId(),1L,Math::addExact);bindings.clear();dirty.add(prepared.dimensionId());}
        @Override public void close(){prepared.close();}
    }
    /** Stages the former region's air and its surrounding graph as a world-owned successor. */
    public AirReturn prepareAirReturn(ServerLevel level, FluidRegion region) {
        return prepareAirReturn(region,level::getBlockState,p->MinecraftGroupAvailability.loaded(level,p),level);
    }
    AirReturn prepareAirReturn(FluidRegion region, Map<BlockPos,BlockState> blocks) {
        return prepareAirReturn(region,p->blocks.getOrDefault(p,Blocks.AIR.defaultBlockState()),p->true,EmptyBlockGetter.INSTANCE);
    }
    private AirReturn prepareAirReturn(FluidRegion region, Function<BlockPos,BlockState> stateAt, Predicate<BlockPos> loaded, BlockGetter world) {
        if(!canReturnAir(region))throw new IllegalArgumentException("Nonstandard gas must be vented before removal");
        String dimension=region.dimension().identifier().toString();
        if(!configured.contains(dimension))throw new IllegalStateException("Environment is unavailable");
        var built=build(dimension,region,stateAt,loaded,world,false,region.id());
        var state=built.state(); var spatial=region.hostSnapshot().captureAuthoritativeState().spatialGas();
        var keys=new ArrayList<EnvironmentThermalState.Key>(); var index=new HashMap<EnvironmentThermalState.Key,Integer>();
        for(var node:state.nodes()) if(node.key().kind()==EnvironmentThermalState.Kind.AIR) {
            var pos=new BlockPos(node.key().x(),node.key().y(),node.key().z());
            if(region.footprint().containsInterior(pos)) {
                index.put(node.key(),keys.size());keys.add(node.key());
            }
        }
        var weights=new ArrayList<Map<Integer,Double>>(spatial.cellCount());
        for(int i=0;i<spatial.cellCount();i++)weights.add(new HashMap<>());
        for(var key:keys) {
            long bits=built.masks().getOrDefault(key,0L); int dest=index.get(key);
            var position=new BlockPos(key.x(),key.y(),key.z()); var block=stateAt.apply(position);
            if(PipeThermalGeometry.isPipe(block)) {
                double[] volumes=PipeThermalGeometry.halfCellVolumes(block);
                for(int sub=0;sub<8;sub++) {
                    int x=(key.x()-region.origin().getX())*2+(sub&1);
                    int y=(key.y()-region.origin().getY())*2+((sub>>1)&1);
                    int z=(key.z()-region.origin().getZ())*2+((sub>>2)&1);
                    int cell=spatial.grid().cell(x,y,z);
                    if(cell>=0 && volumes[sub]>0)weights.get(cell).merge(dest,volumes[sub],Double::sum);
                }
                continue;
            }
            for(int bit=0;bit<64;bit++)if((bits&(1L<<bit))!=0) {
                int x=(key.x()-region.origin().getX())*2+(bit%4)/2;
                int y=(key.y()-region.origin().getY())*2+(bit/4%4)/2;
                int z=(key.z()-region.origin().getZ())*2+(bit/16)/2;
                int cell=spatial.grid().cell(x,y,z);
                if(cell>=0)weights.get(cell).merge(dest,1.0/64.0,Double::sum);
            }
        }
        var inventories=new ArrayList<mmm.fluid.air.GasInventory>(Collections.nCopies(keys.size(),mmm.fluid.air.GasInventory.EMPTY));
        double[] energies=new double[keys.size()]; SignedDyadic imported=SignedDyadic.ZERO, newSource=SignedDyadic.ZERO;
        var model=new GasThermalModel(definitions.thermalRegistry(),definitions.gasRegistry());
        for(int cell=0;cell<spatial.cellCount();cell++) {
            var routes=weights.get(cell); var gas=spatial.cells().get(cell);
            if(routes.isEmpty()) {if(!gas.isEmpty())throw new IllegalStateException("Gas cell has no returning environment volume");continue;}
            var ids=new ArrayList<>(routes.keySet()); Collections.sort(ids); double[] shares=ids.stream().mapToDouble(routes::get).toArray();
            if(gas.isEmpty()) {
                for(int i=0;i<ids.size();i++) {
                    var addition=model.inventoryAtPressure(shares[i],mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA,TEMPERATURE,definitions.gasRegistry().ambient());
                    double energy=model.internalEnergyAtTemperature(addition,TEMPERATURE); int dest=ids.get(i);
                    inventories.set(dest,inventories.get(dest).merge(addition));energies[dest]+=energy;newSource=newSource.add(SignedDyadic.of(energy));
                }
            } else {
                var pieces=gas.partition(mmm.fluid.air.GasPartitionWeights.of(shares),definitions.gasRegistry(),definitions.chemicalOrNull());
                var split=model.partitionEnergy(gas,spatial.energyAt(cell),pieces); imported=imported.add(SignedDyadic.of(spatial.energyAt(cell)));
                for(int i=0;i<ids.size();i++) {int dest=ids.get(i);inventories.set(dest,inventories.get(dest).merge(pieces.get(i)));energies[dest]+=split[i];}
            }
        }
        var nodes=new ArrayList<EnvironmentThermalState.Node>(); SignedDyadic displaced=SignedDyadic.ZERO, actual=SignedDyadic.ZERO;
        for(var node:state.nodes()) {
            Integer at=index.get(node.key()); if(at==null){nodes.add(node);continue;}
            if(inventories.get(at).isEmpty())throw new IllegalStateException("Returning air component has no inventory");
            displaced=displaced.add(SignedDyadic.of(node.internalEnergyJ())); actual=actual.add(SignedDyadic.of(energies[at]));
            nodes.add(new EnvironmentThermalState.Node(node.key(),node.materialId(),inventories.get(at).totalMassUnits(),node.volumeCubicMetres(),inventories.get(at),energies[at],List.of()));
        }
        var audit=state.audit();
        audit=new EnvironmentThermalState.Audit(audit.initial(),audit.source().subtract(displaced).add(newSource),audit.sink(),
                audit.regionExchange().add(imported),audit.roundoff().add(actual.subtract(imported).subtract(newSource)));
        var next=new EnvironmentThermalState(dimension,state.generation(),state.registryFingerprint(),nodes,state.contacts(),audit);
        return new AirReturn(backend.prepareEnvironment(next,definitions.definitions()));
    }
    public boolean refresh(ServerLevel level, FluidRegion region, int cellsPerBlock) {
        return refresh(region, cellsPerBlock, level::getBlockState, p -> MinecraftGroupAvailability.loaded(level, p), level);
    }
    public boolean refresh(FluidRegion region, int cellsPerBlock, Map<BlockPos, BlockState> blocks) {
        return refresh(region, cellsPerBlock, p -> blocks.getOrDefault(p, Blocks.AIR.defaultBlockState()), p -> true, EmptyBlockGetter.INSTANCE);
    }
    private boolean refresh(FluidRegion region, int cellsPerBlock, Function<BlockPos, BlockState> stateAt,
                            Predicate<BlockPos> loaded, BlockGetter world) {
        if (!region.solverLoaded() || !region.hasThermalState()) return false;
        String dimension = region.dimension().identifier().toString();
        if(externalStructures!=null && externalStructures.pendingThermalPositions(dimension)==null)return false;
        boolean needsRebuild = dirty.remove(dimension) || !configured.contains(dimension);
        if (needsRebuild) {
            try { rebuild(dimension, region, stateAt, loaded, world); }
            catch (RuntimeException error) { dirty.add(dimension); throw error; }
        }
        return project(region, dimension, cellsPerBlock);
    }
    private record Built(EnvironmentThermalState state, Map<EnvironmentThermalState.Key,Long> masks,
                         Map<BlockPos,Direction.Axis> axes, Map<BlockPos,String> identities,
                         List<BlockPos> completed, List<FluidRegion> members) { }
    private void rebuild(String dimension, FluidRegion requested, Function<BlockPos, BlockState> stateAt,
                         Predicate<BlockPos> loaded, BlockGetter world) {
        var built=build(dimension,requested,stateAt,loaded,world,true,null);
        backend.configureEnvironment(built.state(),definitions.definitions());
        configured.add(dimension); states.put(dimension,built.state()); revisions.merge(dimension,1L,Math::addExact);
        screenAxes.put(dimension,built.axes()); airMasks.put(dimension,built.masks()); blockIdentities.put(dimension,built.identities());
        if(worldUpdates!=null)built.completed().forEach(source->worldUpdates.finishThermalMove(dimension,source));
        for(var member:built.members())bindings.remove(member.id());
    }
    private Built build(String dimension, FluidRegion requested, Function<BlockPos, BlockState> stateAt,
                        Predicate<BlockPos> loaded, BlockGetter world, boolean includeRequested, UUID excluded) {
        var mechanical = new HashSet<BlockPos>();
        var members = new ArrayList<FluidRegion>();
        for (var region : regions.regions()) if (region.dimension().identifier().toString().equals(dimension) && !region.id().equals(excluded)) members.add(region);
        if (includeRequested && requested != null && !members.contains(requested)) members.add(requested);
        for (var region : members) {
            int n = (int)Math.round(1.0 / region.solverConfig().cellSize());
            for (int i = 0; i < region.grid().cellCount(); i++)
                mechanical.add(region.origin().offset(Math.floorDiv(region.grid().x(i), n), Math.floorDiv(region.grid().y(i), n), Math.floorDiv(region.grid().z(i), n)));
        }
        var positions = new HashSet<>(mechanical);
        positions.addAll(pipePositions.getOrDefault(dimension,Set.of()));
        if(requested!=null) {
            int requestedN=(int)Math.round(1.0/requested.solverConfig().cellSize());
            for(int i=0;i<requested.grid().cellCount();i++) positions.add(requested.origin().offset(
                    Math.floorDiv(requested.grid().x(i),requestedN),Math.floorDiv(requested.grid().y(i),requestedN),Math.floorDiv(requested.grid().z(i),requestedN)));
        }
        for (int axis = 0; axis < 3; axis++) {
            var expanded = new HashSet<BlockPos>();
            for (var pos : positions) for (int offset = -HALO; offset <= HALO; offset++)
                expanded.add(pos.offset(axis == 0 ? offset : 0, axis == 1 ? offset : 0, axis == 2 ? offset : 0));
            positions = expanded;
        }
        var previous = configured.contains(dimension) ? backend.captureEnvironment(dimension) : null;
        positions.addAll(blockIdentities.getOrDefault(dimension,Map.of()).keySet());
        if(previous!=null)for(var node:previous.nodes())if(node.key().kind()!=EnvironmentThermalState.Kind.CARRIED)
            positions.add(new BlockPos(node.key().x(),node.key().y(),node.key().z()));
        var old = new HashMap<EnvironmentThermalState.Key, EnvironmentThermalState.Node>();
        var carried=new ArrayList<EnvironmentThermalState.Node>();
        if (previous != null) for (var node : previous.nodes()) {
            if(node.key().kind()==EnvironmentThermalState.Kind.CARRIED)carried.add(node); else old.put(node.key(), node);
        }
        var moving = new HashSet<BlockPos>();
        if(externalStructures!=null) {
            var pending=externalStructures.pendingThermalPositions(dimension);
            if(pending==null)throw new IllegalStateException("Structure provider unavailable during environment capture");
            moving.addAll(pending);
        }
        var movedTargets = new HashSet<BlockPos>();
        var completed = new ArrayList<BlockPos>();
        if (worldUpdates != null) for (var entry : new ArrayList<>(old.entrySet())) {
            var key = entry.getKey();
            if (key.kind() != EnvironmentThermalState.Kind.SOLID) continue;
            var source = new BlockPos(key.x(), key.y(), key.z());
            {
                var move = worldUpdates.thermalMove(dimension, source, world);
                if (move == null) continue;
                if (move.active() || !loaded.test(move.destination())) {
                    moving.add(source); moving.add(move.destination());
                } else {
                    var destination = move.destination();
                    var targetKey = new EnvironmentThermalState.Key(destination.getX(), destination.getY(), destination.getZ(), key.kind(), key.component());
                    var node = entry.getValue();
                    var targetState = stateAt.apply(destination);
                    var targetDefinition = targetState.is(mmm.block.MmmBlocks.SCREEN) || targetState.is(mmm.block.MmmBlocks.PIPE_SCREEN) && key.component()==1 ? definitions.thermalRegistry().finiteDevice("mmm:screen") : BlockThermalMaterials.resolve(targetState, definitions.thermalRegistry());
                    if (targetDefinition.thermalMaterialId().equals(node.materialId()) && !old.containsKey(targetKey)) {
                        old.remove(key);
                        movedTargets.add(destination);
                        old.put(targetKey, new EnvironmentThermalState.Node(targetKey, node.materialId(), node.massUnits(),
                                node.volumeCubicMetres(), node.gas(), node.internalEnergyJ(), node.surfaces()));
                    }
                    completed.add(source);
                }
            }
        }
        SignedDyadic replaced = SignedDyadic.ZERO;
        var previousBlocks = blockIdentities.getOrDefault(dimension, Map.of());
        for (var entry : new ArrayList<>(old.entrySet())) {
            var key=entry.getKey();
            if(key.kind()!=EnvironmentThermalState.Kind.SOLID)continue;
            var pos=new BlockPos(key.x(),key.y(),key.z()); var identity=previousBlocks.get(pos);
            if(identity!=null && !movedTargets.contains(pos) && !moving.contains(pos) && loaded.test(pos)
                    && !identity.equals(BuiltInRegistries.BLOCK.getKey(stateAt.apply(pos).getBlock()).toString())) {
                old.remove(key); replaced=replaced.add(SignedDyadic.of(entry.getValue().internalEnergyJ()));
            }
        }
        var graph = new WorldThermalTopology(definitions,world,old); graph.removed=replaced;
        var identities = new HashMap<BlockPos,String>();
        var ordered = new ArrayList<>(positions); ordered.sort(Comparator.comparingInt((BlockPos p) -> p.getX()).thenComparingInt(p -> p.getY()).thenComparingInt(p -> p.getZ()));
        for (var pos : ordered) {
            if (!loaded.test(pos) || moving.contains(pos)) {
                graph.unloaded(pos);
                var previousAxis=screenAxes.getOrDefault(dimension,Map.of()).get(pos);
                if(previousAxis==null && previous!=null)for(var node:previous.nodes())
                    if(node.key().kind()==EnvironmentThermalState.Kind.SOLID && node.key().x()==pos.getX()
                            && node.key().y()==pos.getY() && node.key().z()==pos.getZ() && !node.owner().isEmpty()) {
                        previousAxis=Direction.Axis.valueOf(node.owner()); break;
                    }
                if(previousAxis!=null)graph.screenAxes.put(pos,previousAxis);
                if(previousBlocks.containsKey(pos))identities.put(pos,previousBlocks.get(pos));
                continue;
            }
            BlockState state = stateAt.apply(pos);
            identities.put(pos,BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            if (boundaries.hasExplicitDefinition(state)) {
                graph.boundary(pos, world instanceof ServerLevel level ? boundaries.surfaceTemperature(level, pos) : Double.NaN);
                continue;
            }
            if (state.is(mmm.block.MmmBlocks.FINITE_HEAT_EXCHANGER)) { graph.boundary(pos, Double.NaN); continue; }
            var cache=samples.computeIfAbsent(dimension,ignored->new HashMap<>());
            var sample=cache.get(pos);
            if(sample==null || !sample.state().equals(state)) {
                sample=new Sample(state,MinecraftCollisionWorldPort.sampleBlockMasks(state.getCollisionShape(world,pos),2));
                cache.put(pos,sample);
            }
            graph.add(pos, state, sample.masks(), mechanical.contains(pos));
        }
        graph.connect();
        graph.nodes.addAll(carried);
        var audit = previous == null ? new EnvironmentThermalState.Audit(SignedDyadic.ZERO,SignedDyadic.ZERO,SignedDyadic.ZERO,SignedDyadic.ZERO,SignedDyadic.ZERO) : previous.audit();
        for (var node : old.values()) graph.removed = graph.removed.add(SignedDyadic.of(node.internalEnergyJ()));
        audit = new EnvironmentThermalState.Audit(audit.initial(), audit.source().add(graph.added), audit.sink().add(graph.removed), audit.regionExchange(), audit.roundoff().add(graph.roundoff));
        long generation = previous == null ? 0 : previous.generation();
        var next = new EnvironmentThermalState(dimension, generation, definitions.thermalRegistry().fingerprint(), graph.nodes, graph.contacts(), audit);
        return new Built(next,graph.airMasks(),Map.copyOf(graph.screenAxes),Map.copyOf(identities),List.copyOf(completed),List.copyOf(members));
    }
    private boolean project(FluidRegion region, String dimension, int cellsPerBlock) {
        if (bindings.containsKey(region.id()) && bindings.get(region.id()).equals(region.environmentBinding())) return false;
        var state = backend.captureEnvironment(dimension);
        var previous = region.finiteThermalDevices(definitions.thermalRegistry().fingerprint());
        var devices = new ArrayList<FiniteThermalDeviceState>();
        for (var device : previous.devices()) if (!device.providerId().equals(PROVIDER)) devices.add(device);
        var refs = new ArrayList<EnvironmentBinding.DeviceReference>();
        for (var node : state.nodes()) {
            if (node.key().kind() != EnvironmentThermalState.Kind.SOLID) continue;
            var pos = new BlockPos(node.key().x(), node.key().y(), node.key().z());
            var material = definitions.thermalRegistry().material(definitions.thermalRegistry().materialHandle(node.materialId()));
            String type = material.carrierId();
            var sample=samples.getOrDefault(dimension,Map.of()).get(pos);
            if(sample!=null && PipeThermalGeometry.isPipe(sample.state()) && node.key().component()!=0)continue;
            Direction.Axis axis = type.equals("mmm:screen") ? screenAxes.getOrDefault(dimension, Map.of()).get(pos) : null;
            if (type.equals("mmm:screen") && axis == null) continue;
            boolean pipe=sample!=null && PipeThermalGeometry.isPipe(sample.state()) && node.key().component()==0;
            if ((pipe ? pipePorts(region,pos,sample.state(),material) : ports(region, pos, type, definitions.thermalRegistry(), cellsPerBlock, axis)).isEmpty()) continue;
            String id = pos.asLong() + "@" + type + (axis == null ? "" : "@" + axis.name());
            var body = new FiniteThermalBodyState(0, definitions.thermalRegistry().fingerprint(), node.materialId(), node.massUnits(), node.internalEnergyJ());
            devices.add(new FiniteThermalDeviceState(PROVIDER, id, type, body, node.surfaces()));
            refs.add(new EnvironmentBinding.DeviceReference(PROVIDER, id, node.key()));
        }
        var binding = new EnvironmentBinding(dimension, refs, WorldThermalGasContacts.collect(region,airMasksFor(dimension),pos->{
            var sample=samples.getOrDefault(dimension,Map.of()).get(pos);return sample==null?null:sample.state();
        }));
        var oldAudit = previous.audit(); SignedDyadic removed = SignedDyadic.ZERO, added = SignedDyadic.ZERO;
        for (var d : previous.devices()) if (d.providerId().equals(PROVIDER)) removed = removed.add(SignedDyadic.of(d.body().internalEnergyJ()));
        for (var d : devices) if (d.providerId().equals(PROVIDER)) added = added.add(SignedDyadic.of(d.body().internalEnergyJ()));
        var audit = new FiniteThermalDeviceAudit(oldAudit.initialInventory(), oldAudit.externalSource().add(added), oldAudit.externalSink().add(removed), oldAudit.solverExchange(), oldAudit.roundoffResidual());
        region.replaceWorldThermalInventory(new FiniteThermalDeviceInventory(definitions.thermalRegistry().fingerprint(), devices, audit), definitions.thermalRegistry(), binding);
        bindings.put(region.id(), binding); return true;
    }

    /** Applies the sampled world-wall microface to each external region port. */
    List<FluidDomainContracts.ThermalPortRequest> portsFor(FluidRegion region, FiniteThermalDeviceState device, ThermalRegistry registry) {
        var requests=ports(region,device,registry);
        if(device.deviceTypeId().equals("mmm:screen"))return requests;
        var pos=BlockPos.of(position(device));
        var sample=samples.getOrDefault(region.dimension().identifier().toString(),Map.of()).get(pos);
        if(sample==null)return List.of();
        if(PipeThermalGeometry.isPipe(sample.state())) {
            var material=registry.material(registry.materialHandle(device.body().thermalMaterialId()));
            return pipePorts(region,pos,sample.state(),material);
        }
        int cellsPerBlock=(int)Math.round(1.0/region.solverConfig().cellSize());
        if(cellsPerBlock!=2)throw new IllegalArgumentException("World microface requires 0.5 m cells");
        var filtered=new ArrayList<FluidDomainContracts.ThermalPortRequest>();
        for(var request:requests) {
            if(request.internalFace()){filtered.add(request);continue;}
            int axis=request.direction()/2,side=request.direction()&1,mask=0;
            int[] cell={request.cellX(),request.cellY(),request.cellZ()};
            int[] origins={region.origin().getX(),region.origin().getY(),region.origin().getZ()};
            int[] wall={pos.getX(),pos.getY(),pos.getZ()};
            for(int micro=0;micro<8;micro++) {
                if(((micro>>axis)&1)!=side)continue;
                int[] local=new int[3];
                for(int d=0;d<3;d++)local[d]=(origins[d]-wall[d])*4+cell[d]*2+((micro>>d)&1);
                local[axis]=side==0?3:0;
                if(local[0]<0||local[1]<0||local[2]<0||local[0]>=4||local[1]>=4||local[2]>=4)continue;
                int cellIndex=((local[2]/2)*2+local[1]/2)*2+local[0]/2;
                int bit=(local[0]&1)+2*((local[1]&1)+2*(local[2]&1));
                if((sample.masks()[cellIndex]&(1<<bit))!=0)mask|=1<<micro;
            }
            if(mask!=0)filtered.add(new FluidDomainContracts.ThermalPortRequest(request.cellX(),request.cellY(),request.cellZ(),
                    request.direction(),request.wallThicknessMetres(),request.wallConductivityWPerMetreK(),request.filmThicknessMetres(),mask,false));
        }
        return List.copyOf(filtered);
    }
    private static List<FluidDomainContracts.ThermalPortRequest> pipePorts(FluidRegion region,BlockPos pos,BlockState state,ThermalMaterialDefinition material) {
        if(region.solverConfig().cellSize()!=.5f)throw new IllegalArgumentException("Pipe wall contacts require half-metre cells");
        var result=new ArrayList<FluidDomainContracts.ThermalPortRequest>();var origin=region.origin();
        for(var surface:PipeThermalGeometry.outsideSurfaces(state)) {
            int x=(pos.getX()-origin.getX())*2+surface.x(),y=(pos.getY()-origin.getY())*2+surface.y(),z=(pos.getZ()-origin.getZ())*2+surface.z();
            if(region.grid().cell(x,y,z)<0)continue;
            var block=origin.offset(Math.floorDiv(x,2),Math.floorDiv(y,2),Math.floorDiv(z,2));
            if(region.footprint()!=null&&!region.footprint().containsInterior(block))continue;
            int mask=surface.mask()&~region.solidMicroMask(x,y,z)&255;if(mask==0)continue;
            double area=surface.area()*Integer.bitCount(mask)/(double)Integer.bitCount(surface.mask());
            double scale=area/(Integer.bitCount(mask)*.0625);
            result.add(new FluidDomainContracts.ThermalPortRequest(x,y,z,surface.direction(),.03125,
                    material.conductivityWPerMeterK(),.125,mask,true,scale));
        }
        return List.copyOf(result);
    }
    private static long position(FiniteThermalDeviceState device){return Long.parseLong(device.objectId().substring(0,device.objectId().indexOf('@')));}
    static List<FluidDomainContracts.ThermalPortRequest> ports(FluidRegion region,FiniteThermalDeviceState device,ThermalRegistry registry){
        var parts=device.objectId().split("@");
        if(parts.length==3&&parts[2].startsWith("moving:"))return List.of();
        return ports(region,BlockPos.of(position(device)),device.deviceTypeId(),registry,(int)Math.round(1/region.solverConfig().cellSize()),parts.length==3?Direction.Axis.valueOf(parts[2]):null);
    }
    private static List<FluidDomainContracts.ThermalPortRequest> ports(FluidRegion region,BlockPos pos,String type,ThermalRegistry registry,int cellsPerBlock,Direction.Axis screenAxis){
        var requests=new ArrayList<FluidDomainContracts.ThermalPortRequest>();
        var definition=registry.finiteDevice(type);
        var material=registry.material(registry.materialHandle(definition.thermalMaterialId()));
        for(var port:definition.ports()){
            var direction=Direction.valueOf(port.localFace().name());
            if(screenAxis!=null&&direction.getAxis()!=screenAxis)continue;
            var neighbor=screenAxis==null?pos.relative(direction):pos;
            if(region.footprint()!=null&&!region.footprint().containsInterior(neighbor))continue;
            int axis=direction.getAxis()==Direction.Axis.X?0:direction.getAxis()==Direction.Axis.Y?1:2;
            boolean positive=direction.getAxisDirection()==Direction.AxisDirection.POSITIVE;
            for(int a=0;a<cellsPerBlock;a++)for(int b=0;b<cellsPerBlock;b++){
                int[] local={0,0,0};local[axis]=screenAxis==null?(positive?0:cellsPerBlock-1):(positive?cellsPerBlock/2:cellsPerBlock/2-1);local[(axis+1)%3]=a;local[(axis+2)%3]=b;
                int x=(neighbor.getX()-region.origin().getX())*cellsPerBlock+local[0];
                int y=(neighbor.getY()-region.origin().getY())*cellsPerBlock+local[1];
                int z=(neighbor.getZ()-region.origin().getZ())*cellsPerBlock+local[2];
                if(region.grid().cell(x,y,z)<0||region.solidMicroMask(x,y,z)==255)continue;
                requests.add(new FluidDomainContracts.ThermalPortRequest(x,y,z,axis*2+(positive?0:1),port.wallThicknessMeters(),material.conductivityWPerMeterK(),port.fluidFilmThicknessMeters()));
            }
        }
        if (screenAxis == null) {
            for(int z=0;z<cellsPerBlock;z++)for(int y=0;y<cellsPerBlock;y++)for(int x=0;x<cellsPerBlock;x++) {
                int cx=(pos.getX()-region.origin().getX())*cellsPerBlock+x;
                int cy=(pos.getY()-region.origin().getY())*cellsPerBlock+y;
                int cz=(pos.getZ()-region.origin().getZ())*cellsPerBlock+z;
                if(region.grid().cell(cx,cy,cz)<0)continue;
                int solid=region.solidMicroMask(cx,cy,cz); if(solid==0||solid==255)continue;
                for(var port:definition.ports()) {
                    var direction=Direction.valueOf(port.localFace().name());
                    int axis=direction.getAxis()==Direction.Axis.X?0:direction.getAxis()==Direction.Axis.Y?1:2;
                    int side=direction.getAxisDirection()==Direction.AxisDirection.POSITIVE?1:0, open=0;
                    for(int micro=0;micro<8;micro++)if((solid&(1<<micro))==0 && ((micro>>axis)&1)!=side
                            && (solid&(1<<(micro^(1<<axis))))!=0)open|=1<<micro;
                    if(open!=0)requests.add(new FluidDomainContracts.ThermalPortRequest(cx,cy,cz,axis*2+side,
                            port.wallThicknessMeters(),material.conductivityWPerMeterK(),port.fluidFilmThicknessMeters(),open));
                }
            }
        }
        return List.copyOf(requests);
    }
}
