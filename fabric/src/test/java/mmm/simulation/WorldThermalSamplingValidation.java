package mmm.simulation;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.*;
import mmm.fluid.*;
import mmm.fluid.air.*;
import mmm.thermal.*;
import mmm.worldprofile.WorldCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** World sampling and gas handoff over actual Minecraft block states. */
public final class WorldThermalSamplingValidation {
    public static void main(String[] args) throws Exception {
        GpuControllerFixture.initializeItems(); GpuControllerFixture.initializeIndustrialBlocks();
        var registry=BuiltInRegistries.BLOCK;
        var tags=new HashMap<TagKey<Block>,List<net.minecraft.core.Holder<Block>>>();
        registry.getTags().forEach(tag->tags.put(tag.key(),tag.stream().toList()));
        tags.put(TagKey.create(Registries.BLOCK,Identifier.parse("mmm:ordinary_wood_thermal_walls")),List.of(Blocks.OAK_TRAPDOOR.builtInRegistryHolder()));
        tags.put(TagKey.create(Registries.BLOCK,Identifier.parse("mmm:ordinary_insulation_thermal_walls")),List.of(mmm.block.MmmBlocks.INSULATING_GLASS.builtInRegistryHolder(),mmm.block.MmmBlocks.INSULATED_PIPE.builtInRegistryHolder(),mmm.block.MmmBlocks.INSULATED_GLASS_PIPE.builtInRegistryHolder()));
        tags.put(TagKey.create(Registries.BLOCK,Identifier.parse("mmm:ordinary_glass_thermal_walls")),List.of(mmm.block.MmmBlocks.GLASS_PIPE.builtInRegistryHolder()));
        tags.put(TagKey.create(Registries.BLOCK,Identifier.parse("mmm:ordinary_metal_thermal_walls")),List.of(mmm.block.MmmBlocks.PIPE_SCREEN.builtInRegistryHolder(),mmm.block.MmmBlocks.METAL_PIPE.builtInRegistryHolder(),mmm.block.MmmBlocks.PIPE_VALVE.builtInRegistryHolder()));
        registry.prepareTagReload(new net.minecraft.tags.TagLoader.LoadResult<>(Registries.BLOCK,tags)).apply();
        var d=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-world-sampling-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),d.material(),d.chemical(),d.granular(),d.gas(),d.thermal()));
        var world=new CommittedWorldRegistries(startup);
        pipeScreenTopology(world);
        pipeMovingPartsRetainHeat(world);
        pipeWallGeometry(world);
        var manager=new FluidRegionManager(startup.config().limits());
        var environments=new HashMap<String,EnvironmentThermalState>();
        var backend=(FluidSolverBackend)Proxy.newProxyInstance(FluidSolverBackend.class.getClassLoader(),new Class<?>[]{FluidSolverBackend.class},(proxy,method,argv)->switch(method.getName()) {
            case "id" -> "test";
            case "unavailableReason" -> null;
            case "configureEnvironment" -> {var state=(EnvironmentThermalState)argv[0];environments.put(state.dimensionId(),state);yield null;}
            case "captureEnvironment" -> environments.get((String)argv[0]);
            case "prepareEnvironment" -> {
                var base=(EnvironmentThermalState)argv[0]; var next=new EnvironmentThermalState(base.dimensionId(),base.generation()+1,base.registryFingerprint(),base.nodes(),base.contacts(),base.audit());
                yield new PreparedEnvironmentState() {
                    boolean closed;
                    public String dimensionId(){return base.dimensionId();} public long baseGeneration(){return base.generation();}
                    public EnvironmentThermalState capture(){return next;} public boolean isCurrent(){return !closed && environments.get(base.dimensionId()).generation()==base.generation();}
                    public void commit(){if(!isCurrent())throw new IllegalStateException("stale");environments.put(base.dimensionId(),next);closed=true;}
                    public void close(){closed=true;}
                };
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        pipeAirHandoff(world,backend,manager);
        pipeAirExchange(world,backend,manager);
        var origin=BlockPos.ZERO; var footprint=mmm.simulation.controller.FluidRegionFootprint.box(origin,origin);
        var config=new FluidSolverConfig(0.5F,9.81F,1F/20F,48,1e-4F,2e-3F);
        var host=new HostFluidState(2,2,2,config,world.definitions());
        var slab=Blocks.OAK_TRAPDOOR.defaultBlockState();
        var masks=MinecraftCollisionWorldPort.sampleBlockMasks(slab.getCollisionShape(EmptyBlockGetter.INSTANCE,origin),2);
        for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++)host.setSolidMicroMask(x,y,z,Byte.toUnsignedInt(masks[(z*2+y)*2+x]));
        world.bindConfiguredState(host,8);
        var id=UUID.randomUUID(); var candidate=new FluidRegion(id,Level.OVERWORLD,origin,host,footprint);
        var glass=origin.north(); var iron=origin.east();
        var blocks=Map.of(origin,slab,glass,mmm.block.MmmBlocks.INSULATING_GLASS.defaultBlockState(),iron,Blocks.IRON_BLOCK.defaultBlockState());
        var env=new WorldThermalEnvironment(backend,world,manager);
        var handoff=env.prepareRange(candidate,Set.of(origin),blocks);
        var transfer=handoff.split(origin,candidate.grid());
        var previous=host.captureAuthoritativeState().spatialGas();
        var gas=new SpatialGasState(candidate.grid(),previous.registryFingerprint(),previous.chemicalRegistryFingerprint(),transfer.cells(),transfer.energy(),
                transfer.source(),GasAuditLedger.EMPTY,GasAuditLedger.EMPTY,GasAuditLedger.EMPTY);
        var prepared=handoff.prepare(); prepared.commit(); handoff.adopted();
        var initial=FluidDomainInitialGas.replace(host.captureAuthoritativeState(),gas,transfer.roundoff());
        var region=new FluidRegion(id,Level.OVERWORLD,origin,HostFluidState.restoreAuthoritativeState(initial),footprint);
        if(!manager.add(region).accepted())throw new AssertionError("Region admission failed");
        env.refresh(region,2,blocks);
        var state=env.current(Level.OVERWORLD.identifier().toString());
        var wood=state.nodes().stream().filter(n->n.key().kind()==EnvironmentThermalState.Kind.SOLID && n.key().x()==0 && n.key().y()==0 && n.key().z()==0).findFirst().orElseThrow();
        if(!wood.materialId().equals("mmm:ordinary_wood_wall") || wood.volumeCubicMetres()!=0.25)throw new AssertionError("Partial wood geometry or material lost");
        var insulated=state.nodes().stream().filter(n->n.key().kind()==EnvironmentThermalState.Kind.SOLID && n.key().z()==-1 && n.key().x()==0 && n.key().y()==0).findFirst().orElseThrow();
        int insulatedIndex=state.nodes().indexOf(insulated);
        if(!insulated.materialId().equals("mmm:ordinary_insulation_wall") || state.contacts().stream().anyMatch(c->c.a()==insulatedIndex||c.b()==insulatedIndex))
            throw new AssertionError("Insulating glass conducts heat");
        var wall=region.finiteThermalDevices(world.thermalRegistry().fingerprint()).devices().stream().filter(device->device.objectId().startsWith(origin.asLong()+"@")).findFirst().orElseThrow();
        if(env.portsFor(region,wall,world.thermalRegistry()).stream().noneMatch(FluidDomainContracts.ThermalPortRequest::internalFace))throw new AssertionError("Partial wall lost internal microface");
        manager.observeGeometry(env::regionGeometryChanged);
        var replacement=new FluidRegion(region.id(),region.dimension(),region.origin(),
                HostFluidState.restoreAuthoritativeState(region.hostSnapshot().captureAuthoritativeState()),footprint);
        if(replacement.environmentBinding()!=null || !replacement.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().isEmpty())
            throw new AssertionError("Replacement fixture retained its old projection");
        if(!manager.adoptResize(region,replacement).accepted())throw new AssertionError("Same-id replacement was rejected");
        if(!env.refresh(replacement,2,blocks) || replacement.environmentBinding()==null
                || replacement.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().noneMatch(device->
                        device.providerId().equals(WorldThermalEnvironment.PROVIDER) && device.objectId().startsWith(origin.asLong()+"@")))
            throw new AssertionError("Same-id replacement did not restore shared wall projection");
        region=replacement;
        if(!env.canReturnAir(region))throw new AssertionError("Standard ambient air cannot return");
        int occupied=0; while(gas.cells().get(occupied).isEmpty())occupied++;
        var oxygen=world.gasRegistry().createMixture(100_000_000L,List.of(
                new GasRegistry.AmbientFraction("mmm:oxygen",mmm.chemistry.ExactRational.ONE)));
        var foreignCells=new ArrayList<GasInventory>(Collections.nCopies(gas.cellCount(),GasInventory.EMPTY));
        foreignCells.set(occupied,oxygen); double[] foreignEnergy=new double[gas.cellCount()];
        foreignEnergy[occupied]=new GasThermalModel(world.thermalRegistry(),world.gasRegistry()).internalEnergyAtTemperature(oxygen,293.15);
        var foreignGas=new SpatialGasState(gas.grid(),gas.registryFingerprint(),gas.chemicalRegistryFingerprint(),foreignCells,foreignEnergy,
                GasAuditLedger.of(oxygen),GasAuditLedger.EMPTY,GasAuditLedger.EMPTY,GasAuditLedger.EMPTY);
        var foreignState=FluidDomainInitialGas.replace(host.captureAuthoritativeState(),foreignGas,mmm.units.SignedDyadic.ZERO);
        var contaminated=new FluidRegion(UUID.randomUUID(),Level.OVERWORLD,origin,HostFluidState.restoreAuthoritativeState(foreignState),footprint);
        if(env.canReturnAir(contaminated))throw new AssertionError("Nonstandard gas bypassed explicit venting");
        try(var returned=env.prepareAirReturn(region,blocks)) {
            var next=returned.snapshot();
            var total=GasInventory.EMPTY; double energy=0;
            for(var node:next.nodes())if(node.key().kind()==EnvironmentThermalState.Kind.AIR && node.key().x()==0 && node.key().y()==0 && node.key().z()==0){total=total.merge(node.gas());energy+=node.internalEnergyJ();}
            var source=GasInventory.EMPTY; for(var cell:gas.cells())source=source.merge(cell);
            if(!total.equals(source) || Math.abs(energy-Arrays.stream(gas.internalEnergy()).sum())>1e-8)throw new AssertionError("Returned air changed inventory or energy");
            returned.commit();
        }
        manager.remove(region.id());
        env.markDirty(Level.OVERWORLD.identifier().toString(),origin);
        env.refreshDirty(Level.OVERWORLD.identifier().toString(),blocks);
        var preserved=GasInventory.EMPTY; double preservedEnergy=0;
        for(var node:env.current(Level.OVERWORLD.identifier().toString()).nodes())
            if(node.key().kind()==EnvironmentThermalState.Kind.AIR && node.key().x()==0 && node.key().y()==0 && node.key().z()==0) {
                preserved=preserved.merge(node.gas()); preservedEnergy+=node.internalEnergyJ();
            }
        var source=GasInventory.EMPTY; for(var cell:gas.cells())source=source.merge(cell);
        if(!preserved.equals(source) || Math.abs(preservedEnergy-Arrays.stream(gas.internalEnergy()).sum())>1e-8)
            throw new AssertionError("Region-free refresh changed returned air");
    }
    private static void pipeScreenTopology(CommittedWorldRegistries world) {
        var pos=BlockPos.ZERO;var state=mmm.block.MmmBlocks.PIPE_SCREEN.defaultBlockState();
        var masks=MinecraftCollisionWorldPort.sampleBlockMasks(state.getCollisionShape(EmptyBlockGetter.INSTANCE,pos),2);
        var topology=new WorldThermalTopology(world,EmptyBlockGetter.INSTANCE,new HashMap<>());
        topology.add(pos,state,masks,false);topology.connect();
        var shell=topology.nodes.stream().filter(n->n.key().kind()==EnvironmentThermalState.Kind.SOLID && n.key().component()==0).findFirst().orElseThrow();
        var membrane=topology.nodes.stream().filter(n->n.key().kind()==EnvironmentThermalState.Kind.SOLID && n.key().component()==1).findFirst().orElseThrow();
        var definition=world.thermalRegistry().finiteDevice("mmm:screen");
        if(!shell.materialId().equals("mmm:ordinary_metal_wall") || !shell.owner().isEmpty()
                || !membrane.materialId().equals("mmm:screen") || membrane.owner().isEmpty()
                || membrane.massUnits()!=mmm.units.Mass.fromKilograms(definition.bodyMassKilograms()*.25)
                || membrane.volumeCubicMetres()!=.03125)
            throw new AssertionError("Pipe screen shell or quarter-area membrane inventory differs");
        var savedShell=shell.withEnergy(shell.internalEnergyJ()+123);
        var savedMembrane=membrane.withEnergy(membrane.internalEnergyJ()+45);
        var old=new HashMap<EnvironmentThermalState.Key,EnvironmentThermalState.Node>();
        old.put(savedShell.key(),savedShell);old.put(savedMembrane.key(),savedMembrane);
        var refreshed=new WorldThermalTopology(world,EmptyBlockGetter.INSTANCE,old);
        refreshed.add(pos,state,masks,false);
        if(refreshed.nodes.stream().noneMatch(n->n.equals(savedShell)) || refreshed.nodes.stream().noneMatch(n->n.equals(savedMembrane)))
            throw new AssertionError("Pipe screen refresh reset thermal inventory");
        if(shell.volumeCubicMetres()!=mmm.block.PipeGeometry.wallVolume(state))throw new AssertionError("Pipe screen shell volume differs");
    }

    private static void pipeMovingPartsRetainHeat(CommittedWorldRegistries world) {
        var pos=BlockPos.ZERO;
        var pipe=mmm.block.MmmBlocks.METAL_PIPE.defaultBlockState();
        var valve=mmm.block.MmmBlocks.PIPE_VALVE.defaultBlockState();
        for(var states:List.of(List.of(pipe,pipe.setValue(mmm.block.PipeBlock.port(net.minecraft.core.Direction.EAST),mmm.block.PipeBlock.Port.OPEN),pipe),
                List.of(valve,valve.setValue(mmm.block.PipeValveBlock.OPENING,0),valve.setValue(mmm.block.PipeValveBlock.OPENING,1),valve))) {
            var old=new HashMap<EnvironmentThermalState.Key,EnvironmentThermalState.Node>();
            EnvironmentThermalState.Node heated=null;
            for(var state:states) {
                var masks=MinecraftCollisionWorldPort.sampleBlockMasks(state.getCollisionShape(EmptyBlockGetter.INSTANCE,pos),2);
                var topology=new WorldThermalTopology(world,EmptyBlockGetter.INSTANCE,old);
                topology.add(pos,state,masks,true);
                var solid=topology.nodes.stream().filter(n->n.key().kind()==EnvironmentThermalState.Kind.SOLID).findFirst().orElseThrow();
                if(solid.volumeCubicMetres()!=mmm.block.PipeGeometry.wallVolume(state))throw new AssertionError("Pipe wall volume differs");
                if(heated==null)heated=solid.withEnergy(solid.internalEnergyJ()+12345);
                else {
                    var expected=mmm.units.SignedDyadic.of(heated.internalEnergyJ()).add(topology.added)
                            .subtract(topology.removed).add(topology.roundoff);
                    if(!expected.equals(mmm.units.SignedDyadic.of(solid.internalEnergyJ())))
                        throw new AssertionError("Pipe shape update lost heat accounting");
                    heated=solid;
                }
                old=new HashMap<>();old.put(heated.key(),heated);
            }
        }
    }

    private static void pipeWallGeometry(CommittedWorldRegistries world) {
        var pos=BlockPos.ZERO;
        var blocks=List.of(mmm.block.MmmBlocks.METAL_PIPE,mmm.block.MmmBlocks.GLASS_PIPE,
                mmm.block.MmmBlocks.INSULATED_PIPE,mmm.block.MmmBlocks.INSULATED_GLASS_PIPE);
        for(var block:blocks) {
            var state=block.defaultBlockState();var topology=new WorldThermalTopology(world,EmptyBlockGetter.INSTANCE,new HashMap<>());
            var masks=MinecraftCollisionWorldPort.sampleBlockMasks(state.getCollisionShape(EmptyBlockGetter.INSTANCE,pos),2);
            topology.add(pos,state,masks,false);
            for(var direction:net.minecraft.core.Direction.values())topology.add(pos.relative(direction),Blocks.AIR.defaultBlockState(),new byte[8],false);
            topology.connect();
            var wall=topology.nodes.stream().filter(n->n.key().kind()==EnvironmentThermalState.Kind.SOLID).findFirst().orElseThrow();
            var air=topology.nodes.stream().filter(n->n.key().kind()==EnvironmentThermalState.Kind.AIR).findFirst().orElseThrow();
            double outside=PipeThermalGeometry.outsideVolume(state),parts=Arrays.stream(PipeThermalGeometry.halfCellVolumes(state)).sum();
            if(wall.volumeCubicMetres()!=mmm.block.PipeGeometry.wallVolume(state) || air.volumeCubicMetres()!=outside
                    || Math.abs(outside-parts)>1e-12)throw new AssertionError("Pipe material/air volume differs");
            double projected=PipeThermalGeometry.outsideSurfaces(state).stream().mapToDouble(PipeThermalGeometry.Surface::area).sum();
            if(Math.abs(projected-mmm.block.PipeGeometry.outerArea(state))>1e-12)
                throw new AssertionError("Pipe mechanical wall surface area differs");
            var expected=BlockThermalMaterials.resolve(state,world.thermalRegistry());
            if(!wall.materialId().equals(expected.thermalMaterialId())
                    || wall.massUnits()!=mmm.units.Mass.fromKilograms(expected.bodyMassKilograms()*wall.volumeCubicMetres()))
                throw new AssertionError("Pipe wall material or mass differs");
            int wallIndex=topology.nodes.indexOf(wall);
            double conductance=topology.contacts().stream().filter(c->c.a()==wallIndex||c.b()==wallIndex)
                    .mapToDouble(EnvironmentThermalState.Contact::conductanceWPerK).sum();
            double k=world.thermalRegistry().material(world.thermalRegistry().materialHandle(wall.materialId())).conductivityWPerMeterK();
            double expectedConductance=k==0?0:mmm.block.PipeGeometry.outerArea(state)/(1.0/5+.03125/k);
            if(Math.abs(conductance-expectedConductance)>1e-9)throw new AssertionError("Pipe outside wall conductance differs");
        }
        var straight=mmm.block.MmmBlocks.METAL_PIPE.defaultBlockState()
                .setValue(mmm.block.PipeBlock.port(net.minecraft.core.Direction.WEST),mmm.block.PipeBlock.Port.OPEN)
                .setValue(mmm.block.PipeBlock.port(net.minecraft.core.Direction.EAST),mmm.block.PipeBlock.Port.OPEN);
        double faceAir=0;
        for(int u=0;u<4;u++)for(int v=0;v<4;v++)faceAir+=PipeThermalGeometry.outsideFaceFraction(straight,0,0,u,v)/16.0;
        if(Math.abs(faceAir-(1-.625*.625))>1e-12)throw new AssertionError("Pipe outside face includes cavity or wall");
    }

    private static void pipeAirHandoff(CommittedWorldRegistries world,FluidSolverBackend backend,FluidRegionManager manager) {
        var pos=new BlockPos(20,0,0);var state=mmm.block.MmmBlocks.METAL_PIPE.defaultBlockState();
        var blocks=Map.of(pos,state); var config=new FluidSolverConfig(.5f,9.81f,.05f,48,1e-4f,.002f);
        var host=new HostFluidState(2,2,2,config,world.definitions());world.bindConfiguredState(host,8);
        var footprint=mmm.simulation.controller.FluidRegionFootprint.box(pos,pos);
        var candidate=new FluidRegion(UUID.randomUUID(),Level.OVERWORLD,pos,host,footprint);
        var env=new WorldThermalEnvironment(backend,world,manager);var handoff=env.prepareRange(candidate,Set.of(pos),blocks);
        var transfer=handoff.split(pos,candidate.grid());var total=GasInventory.EMPTY;
        for(var gas:transfer.cells())total=total.merge(gas);
        var model=new GasThermalModel(world.thermalRegistry(),world.gasRegistry());
        var expected=model.inventoryAtPressure(PipeThermalGeometry.outsideVolume(state),GasRegistry.STANDARD_PRESSURE_PA,293.15,world.gasRegistry().ambient());
        if(!total.equals(expected))throw new AssertionError("Pipe outside air handoff changed inventory");
        var current=host.captureAuthoritativeState().spatialGas();
        var gas=new SpatialGasState(candidate.grid(),current.registryFingerprint(),current.chemicalRegistryFingerprint(),transfer.cells(),transfer.energy(),
                transfer.source(),GasAuditLedger.EMPTY,GasAuditLedger.EMPTY,GasAuditLedger.EMPTY);
        try(var prepared=handoff.prepare()) { prepared.commit(); } handoff.adopted();
        String dimension=Level.OVERWORLD.identifier().toString();var wall=env.pipeWall(dimension,pos,state);
        if(wall==null || wall.innerArea()!=mmm.block.PipeGeometry.innerArea(state) || wall.halfThickness()!=.03125)
            throw new AssertionError("Pipe wall snapshot is missing");
        if(!wall.equals(env.pipeWalls(dimension,Map.of(pos,state)).get(pos)))throw new AssertionError("Batch wall snapshot differs");
        try(var prepared=env.preparePipeWallExchange(dimension,wall.generation(),Map.of(wall.key(),-10.0))) {
            if(!prepared.capture().exactEnergy().equals(prepared.capture().audit().expectedEnergy()))throw new AssertionError("Pipe wall exchange audit differs");
            prepared.commit();
        }
        env.pipeWallExchangeAdopted(dimension);
        if(env.pipeWall(dimension,pos,state).energy()!=wall.energy()-10.0)throw new AssertionError("Pipe wall heat was not committed");
        var initial=FluidDomainInitialGas.replace(host.captureAuthoritativeState(),gas,transfer.roundoff());
        var region=new FluidRegion(candidate.id(),Level.OVERWORLD,pos,HostFluidState.restoreAuthoritativeState(initial),footprint);
        if(!manager.add(region).accepted())throw new AssertionError("Pipe region admission failed");
        try(var returned=env.prepareAirReturn(region,blocks)) {
            var after=returned.snapshot().nodes().stream().filter(n->n.key().kind()==EnvironmentThermalState.Kind.AIR
                    && n.key().x()==pos.getX() && n.key().y()==pos.getY() && n.key().z()==pos.getZ()).findFirst().orElseThrow();
            if(!after.gas().equals(expected))throw new AssertionError("Pipe outside air return changed inventory");
        }
        manager.remove(region.id());
    }
    private static void pipeAirExchange(CommittedWorldRegistries world,FluidSolverBackend backend,FluidRegionManager manager) {
        var pos=new BlockPos(40,0,0);String dimension=Level.OVERWORLD.identifier().toString();
        var env=new WorldThermalEnvironment(backend,world,manager);env.pipePositions(dimension,Set.of(pos));
        env.refreshDirty(dimension,Map.of());var old=env.current(dimension);var nodes=new ArrayList<>(old.nodes());
        int index=-1;for(int i=0;i<nodes.size();i++){var key=nodes.get(i).key();
            if(key.kind()==EnvironmentThermalState.Kind.AIR&&key.x()==pos.getX()&&key.y()==pos.getY()&&key.z()==pos.getZ()){index=i;break;}}
        if(index<0)throw new AssertionError("Pipe-only dimension did not initialize world air");
        var before=nodes.get(index);var gas=world.gasRegistry().createMixture(100_000_000L,List.of(
                new GasRegistry.AmbientFraction("mmm:oxygen",mmm.chemistry.ExactRational.ONE)));
        var model=new GasThermalModel(world.thermalRegistry(),world.gasRegistry());double energy=model.internalEnergyAtTemperature(gas,330);
        nodes.set(index,new EnvironmentThermalState.Node(before.key(),before.materialId(),gas.totalMassUnits(),before.volumeCubicMetres(),gas,energy,List.of()));
        backend.configureEnvironment(EnvironmentThermalState.initialized(dimension,old.registryFingerprint(),nodes,old.contacts()),world.definitions());
        var state=mmm.block.MmmBlocks.METAL_PIPE.defaultBlockState();double volume=PipeWorldGraph.capacity(state);
        var planned=env.preparePipeAir(dimension,Map.of(pos,volume),Map.of(),p->state,p->true,p->false);
        var acquired=planned.acquired().get(pos);var successor=planned.prepared().capture();
        var remaining=successor.nodes().stream().filter(n->n.key().equals(before.key())).findFirst().orElseThrow();
        if(!remaining.gas().merge(acquired.inventory()).equals(gas)
                || Math.abs(remaining.internalEnergyJ()+acquired.energy()-energy)>1e-8
                || !successor.exactEnergy().equals(successor.audit().expectedEnergy()))
            throw new AssertionError("Pipe acquisition lost finite world gas or energy");
        planned.commit();planned.close();
        try(var returned=env.preparePipeAir(dimension,Map.of(),Map.of(pos,acquired),p->Blocks.AIR.defaultBlockState(),p->true,p->false)) {
            var restored=returned.prepared().capture();var node=restored.nodes().stream().filter(n->n.key().equals(before.key())).findFirst().orElseThrow();
            if(!node.gas().equals(gas)||Math.abs(node.internalEnergyJ()-energy)>1e-8
                    || !restored.exactEnergy().equals(restored.audit().expectedEnergy()))
                throw new AssertionError("Pipe gas return changed finite world inventory");
        }
    }

}
