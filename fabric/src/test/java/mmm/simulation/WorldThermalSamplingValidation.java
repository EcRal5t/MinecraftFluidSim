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
        tags.put(TagKey.create(Registries.BLOCK,Identifier.parse("mmm:ordinary_insulation_thermal_walls")),List.of(mmm.block.MmmBlocks.INSULATING_GLASS.builtInRegistryHolder()));
        registry.prepareTagReload(new net.minecraft.tags.TagLoader.LoadResult<>(Registries.BLOCK,tags)).apply();
        var d=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-world-sampling-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),d.material(),d.chemical(),d.granular(),d.gas(),d.thermal()));
        var world=new CommittedWorldRegistries(startup);
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
}
