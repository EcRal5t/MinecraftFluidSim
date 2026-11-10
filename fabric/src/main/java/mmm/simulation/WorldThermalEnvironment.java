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
        if(region==null && !configured.contains(dimension))return;
        dirty.remove(dimension);
        try { rebuild(dimension,region,level::getBlockState,p->MinecraftGroupAvailability.loaded(level,p),level); }
        catch(RuntimeException failure){dirty.add(dimension);throw failure;}
    }
    /** A private air handoff; callers commit only after the matching region state is accepted. */
    public final class AirHandoff {
        private final EnvironmentThermalState base, candidate;
        private final Map<EnvironmentThermalState.Key,Long> masks;
        private final Map<BlockPos, List<EnvironmentThermalState.Node>> air;
        private boolean closed;
        private final long revision;
        private AirHandoff(EnvironmentThermalState base, EnvironmentThermalState candidate,
                           Map<EnvironmentThermalState.Key,Long> masks, Map<BlockPos, List<EnvironmentThermalState.Node>> air) {
            this.base = base; this.candidate=candidate; this.masks=Map.copyOf(masks);
            this.air = Map.copyOf(air); this.revision = revisions.getOrDefault(base.dimensionId(),0L);
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
                    for(int mz=0;mz<2;mz++)for(int my=0;my<2;my++)for(int mx=0;mx<2;mx++) {
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
        return new AirHandoff(base,built.state(),built.masks(),air);
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
            if(region.footprint().containsInterior(pos)){index.put(node.key(),keys.size());keys.add(node.key());}
        }
        var weights=new ArrayList<Map<Integer,Integer>>(spatial.cellCount());
        for(int i=0;i<spatial.cellCount();i++)weights.add(new HashMap<>());
        for(var key:keys) {
            long bits=built.masks().getOrDefault(key,0L); int dest=index.get(key);
            for(int bit=0;bit<64;bit++)if((bits&(1L<<bit))!=0) {
                int x=(key.x()-region.origin().getX())*2+(bit%4)/2;
                int y=(key.y()-region.origin().getY())*2+(bit/4%4)/2;
                int z=(key.z()-region.origin().getZ())*2+(bit/16)/2;
                int cell=spatial.grid().cell(x,y,z);
                if(cell>=0)weights.get(cell).merge(dest,1,Integer::sum);
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
                    var addition=model.inventoryAtPressure(shares[i]/64.0,mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA,TEMPERATURE,definitions.gasRegistry().ambient());
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
                    var targetDefinition = targetState.is(mmm.block.MmmBlocks.SCREEN) ? definitions.thermalRegistry().finiteDevice("mmm:screen") : BlockThermalMaterials.resolve(targetState, definitions.thermalRegistry());
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
        audit = new EnvironmentThermalState.Audit(audit.initial(), audit.source().add(graph.added), audit.sink().add(graph.removed), audit.regionExchange(), audit.roundoff());
        long generation = previous == null ? 0 : previous.generation();
        var next = new EnvironmentThermalState(dimension, generation, definitions.thermalRegistry().fingerprint(), graph.nodes, graph.contacts(), audit);
        return new Built(next,graph.airMasks(),Map.copyOf(graph.screenAxes),Map.copyOf(identities),List.copyOf(completed),List.copyOf(members));
    }
    private boolean project(FluidRegion region, String dimension, int cellsPerBlock) {
        if (bindings.containsKey(region.id()) && bindings.get(region.id()).equals(region.environmentBinding())) return false;
        var state = states.get(dimension);
        var previous = region.finiteThermalDevices(definitions.thermalRegistry().fingerprint());
        var devices = new ArrayList<FiniteThermalDeviceState>();
        for (var device : previous.devices()) if (!device.providerId().equals(PROVIDER)) devices.add(device);
        var refs = new ArrayList<EnvironmentBinding.DeviceReference>();
        for (var node : state.nodes()) {
            if (node.key().kind() != EnvironmentThermalState.Kind.SOLID) continue;
            var pos = new BlockPos(node.key().x(), node.key().y(), node.key().z());
            var material = definitions.thermalRegistry().material(definitions.thermalRegistry().materialHandle(node.materialId()));
            String type = material.carrierId();
            Direction.Axis axis = screenAxes.getOrDefault(dimension, Map.of()).get(pos);
            if (type.equals("mmm:screen") && axis == null) continue;
            if (ports(region, pos, type, definitions.thermalRegistry(), cellsPerBlock, axis).isEmpty()) continue;
            String id = pos.asLong() + "@" + type + (axis == null ? "" : "@" + axis.name());
            var body = new FiniteThermalBodyState(0, definitions.thermalRegistry().fingerprint(), node.materialId(), node.massUnits(), node.internalEnergyJ());
            devices.add(new FiniteThermalDeviceState(PROVIDER, id, type, body, node.surfaces()));
            refs.add(new EnvironmentBinding.DeviceReference(PROVIDER, id, node.key()));
        }
        var binding = new EnvironmentBinding(dimension, refs, WorldThermalGasContacts.collect(region,airMasksFor(dimension)));
        if (binding.equals(bindings.get(region.id()))) return false;
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
