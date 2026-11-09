package mmm.simulation;

import mmm.fluid.FluidDomainContracts;
import mmm.fluid.FluidStateAccess;
import mmm.thermal.*;
import mmm.units.SignedDyadic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Region-owned heat inventories for ordinary full-block walls. */
final class OrdinaryThermalWalls {
    static final String PROVIDER="mmm:ordinary_wall";
    private record Mapping(TagKey<Block> tag,String type) {}
    private static final List<Mapping> MAPPINGS=List.of(mapping("stone"),mapping("metal"),mapping("glass"));
    private static Mapping mapping(String name){return new Mapping(TagKey.create(Registries.BLOCK,
            Identifier.parse("mmm:ordinary_"+name+"_thermal_walls")),"mmm:ordinary_"+name+"_wall");}
    private final ThermalRegistry registry;
    private final ThermalBoundaryResolver boundaries;
    OrdinaryThermalWalls(ThermalRegistry registry,ThermalBoundaryResolver boundaries){this.registry=registry;this.boundaries=boundaries;}

    boolean refresh(ServerLevel level,FluidRegion region,int cellsPerBlock){
        if(!region.solverLoaded()||!region.hasThermalState())return false;
        var previous=region.finiteThermalDevices(registry.fingerprint());
        var existing=new HashMap<Long,FiniteThermalDeviceState>();
        var others=new ArrayList<FiniteThermalDeviceState>();
        for(var device:previous.devices())if(device.providerId().equals(PROVIDER))existing.put(position(device),device);else others.add(device);
        var positions=new HashSet<Long>();
        var footprint=region.footprint();
        if (footprint != null) {
            for (long packed : footprint.interiorBlocks()) {
                var interior = BlockPos.of(packed);
                if (!footprint.containsInterior(interior)) continue;
                for (var direction : Direction.values()) positions.add(interior.relative(direction).asLong());
            }
        } else {
            for (int cell = 0; cell < region.grid().cellCount(); cell++) {
                var interior = region.origin().offset(region.grid().x(cell)/cellsPerBlock,
                        region.grid().y(cell)/cellsPerBlock, region.grid().z(cell)/cellsPerBlock);
                for (var direction : Direction.values()) positions.add(interior.relative(direction).asLong());
            }
        }
        positions.addAll(existing.keySet());
        var next=new ArrayList<>(others); var added=SignedDyadic.ZERO; var removed=SignedDyadic.ZERO;
        for(long packed:positions){
            var pos=BlockPos.of(packed);var old=existing.get(packed);
            if(!MinecraftGroupAvailability.loaded(level,pos)){if(old!=null)next.add(old);continue;}
            BlockState block=level.getBlockState(pos);String type=null;
            if(!boundaries.hasExplicitDefinition(block)&&block.isCollisionShapeFullBlock(level,pos)){
                for(var candidate:MAPPINGS)if(block.is(candidate.tag())){
                    if(type!=null)throw new IllegalArgumentException("Multiple thermal wall mappings at "+pos.toShortString());
                    type=candidate.type();
                }
            }
            String id=Long.toString(packed)+"@"+BuiltInRegistries.BLOCK.getKey(block.getBlock());
            boolean touches=type!=null&&!ports(region,pos,type,registry,cellsPerBlock).isEmpty();
            if(old!=null&&touches&&old.objectId().equals(id)&&old.deviceTypeId().equals(type)){next.add(old);continue;}
            if(old!=null)removed=removed.add(SignedDyadic.of(old.body().internalEnergyJ()));
            if(touches){
                var definition=registry.finiteDevice(type);var mass=definition.bodyMassUnits();
                double energy=new ThermalStateFunction(registry).internalEnergyAtTemperature(
                        registry.materialHandle(definition.thermalMaterialId()),definition.bodyMassKilograms(),Map.of(),definition.initialTemperatureK());
                var body=new FiniteThermalBodyState(0,registry.fingerprint(),definition.thermalMaterialId(),mass,energy);
                next.add(new FiniteThermalDeviceState(PROVIDER,id,type,body));added=added.add(SignedDyadic.of(energy));
            }
        }
        var audit=previous.audit();
        var result=new FiniteThermalDeviceInventory(registry.fingerprint(),next,new FiniteThermalDeviceAudit(
                audit.initialInventory(),audit.externalSource().add(added),audit.externalSink().add(removed),audit.solverExchange(),audit.roundoffResidual()));
        if(result.equals(previous))return false;
        region.replaceOrdinaryWallInventory(result,registry);return true;
    }
    private static long position(FiniteThermalDeviceState device){return Long.parseLong(device.objectId().substring(0,device.objectId().indexOf('@')));}
    static List<FluidDomainContracts.ThermalPortRequest> ports(FluidRegion region,FiniteThermalDeviceState device,ThermalRegistry registry){
        return ports(region,BlockPos.of(position(device)),device.deviceTypeId(),registry,(int)Math.round(1/region.solverConfig().cellSize()));
    }
    private static List<FluidDomainContracts.ThermalPortRequest> ports(FluidRegion region,BlockPos pos,String type,ThermalRegistry registry,int cellsPerBlock){
        var requests=new ArrayList<FluidDomainContracts.ThermalPortRequest>();
        var definition=registry.finiteDevice(type);
        var material=registry.material(registry.materialHandle(definition.thermalMaterialId()));
        for(var port:definition.ports()){
            var direction=Direction.valueOf(port.localFace().name());
            var neighbor=pos.relative(direction);
            if(region.footprint()!=null&&!region.footprint().containsInterior(neighbor))continue;
            int axis=direction.getAxis()==Direction.Axis.X?0:direction.getAxis()==Direction.Axis.Y?1:2;
            boolean positive=direction.getAxisDirection()==Direction.AxisDirection.POSITIVE;
            for(int a=0;a<cellsPerBlock;a++)for(int b=0;b<cellsPerBlock;b++){
                int[] local={0,0,0};local[axis]=positive?0:cellsPerBlock-1;local[(axis+1)%3]=a;local[(axis+2)%3]=b;
                int x=(neighbor.getX()-region.origin().getX())*cellsPerBlock+local[0];
                int y=(neighbor.getY()-region.origin().getY())*cellsPerBlock+local[1];
                int z=(neighbor.getZ()-region.origin().getZ())*cellsPerBlock+local[2];
                if(region.grid().cell(x,y,z)<0||region.solidMicroMask(x,y,z)==255)continue;
                requests.add(new FluidDomainContracts.ThermalPortRequest(x,y,z,axis*2+(positive?0:1),port.wallThicknessMeters(),material.conductivityWPerMeterK(),port.fluidFilmThicknessMeters()));
            }
        }
        return List.copyOf(requests);
    }
}
