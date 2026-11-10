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
    private MinecraftWorldUpdates worldUpdates;
    void worldUpdates(MinecraftWorldUpdates updates){worldUpdates=java.util.Objects.requireNonNull(updates);}
    OrdinaryThermalWalls(ThermalRegistry registry,ThermalBoundaryResolver boundaries){this.registry=registry;this.boundaries=boundaries;}

    boolean refresh(ServerLevel level,FluidRegion region,int cellsPerBlock){
        return refresh(region,cellsPerBlock,level::getBlockState,
                position->MinecraftGroupAvailability.loaded(level,position),level);
    }
    boolean refresh(FluidRegion region,int cellsPerBlock,Map<BlockPos,BlockState> blocks){
        return refresh(region,cellsPerBlock,position->blocks.getOrDefault(position,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()),
                position->true,net.minecraft.world.level.EmptyBlockGetter.INSTANCE);
    }
    private boolean refresh(FluidRegion region,int cellsPerBlock,java.util.function.Function<BlockPos,BlockState> stateAt,
                            java.util.function.Predicate<BlockPos> loaded,net.minecraft.world.level.BlockGetter blocks){
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
        var movingTargets=new HashSet<Long>();
        for(var entry:existing.entrySet()){
            var move=wallMove(region,BlockPos.of(entry.getKey()),entry.getValue(),blocks);
            if(move!=null)movingTargets.add(move.destination().asLong());
        }
        var completedMoves=new ArrayList<BlockPos>();
        var next=new ArrayList<>(others); var added=SignedDyadic.ZERO; var removed=SignedDyadic.ZERO;
        for(long packed:positions){
            var pos=BlockPos.of(packed);var old=existing.get(packed);
            var move=old==null?null:wallMove(region,pos,old,blocks);
            if(old!=null&&move!=null){
                String[] oldIdentity=old.objectId().split("@");
                if(move.active() || !loaded.test(move.destination())) {
                    next.add(new FiniteThermalDeviceState(PROVIDER,packed+"@"+oldIdentity[1]+"@moving:"+move.destination().asLong(),old.deviceTypeId(),old.body(),old.surfaces()));
                    continue;
                }
                var target=move.destination();var targetBlock=stateAt.apply(target);
                String blockId=BuiltInRegistries.BLOCK.getKey(targetBlock.getBlock()).toString();
                boolean same=blockId.equals(oldIdentity[1]) && !ports(region,target,old.deviceTypeId(),registry,cellsPerBlock,null).isEmpty();
                if(same)next.add(new FiniteThermalDeviceState(PROVIDER,target.asLong()+"@"+blockId,old.deviceTypeId(),old.body(),old.surfaces()));
                else removed=removed.add(SignedDyadic.of(old.body().internalEnergyJ()));
                completedMoves.add(pos);
                if(oldIdentity.length==3&&oldIdentity[2].startsWith("moving:"))
                    completedMoves.add(BlockPos.of(Long.parseLong(oldIdentity[2].substring(7))));
                continue;
            }
            if(old==null&&movingTargets.contains(packed))continue;
            if(!loaded.test(pos)){if(old!=null)next.add(old);continue;}
            BlockState block=stateAt.apply(pos);String type=null;
            if(block.is(mmm.block.MmmBlocks.SCREEN))type="mmm:screen";
            else if(!boundaries.hasExplicitDefinition(block)&&block.isCollisionShapeFullBlock(blocks,pos)){
                for(var candidate:MAPPINGS)if(block.is(candidate.tag())){
                    if(type!=null)throw new IllegalArgumentException("Multiple thermal wall mappings at "+pos.toShortString());
                    type=candidate.type();
                }
            }
            var screenAxis=block.is(mmm.block.MmmBlocks.SCREEN)?block.getValue(mmm.block.ScreenBlock.FACING).getAxis():null;
            String id=Long.toString(packed)+"@"+BuiltInRegistries.BLOCK.getKey(block.getBlock())+(screenAxis==null?"":"@"+screenAxis.name());
            boolean touches=type!=null&&!ports(region,pos,type,registry,cellsPerBlock,screenAxis).isEmpty();
            if(old!=null&&type!=null&&old.objectId().equals(id)&&old.deviceTypeId().equals(type)){next.add(old);continue;}
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
        if(result.equals(previous)){if(worldUpdates!=null)for(var source:completedMoves)worldUpdates.finishWallMove(region,source);return false;}
        region.replaceOrdinaryWallInventory(result,registry);
        if(worldUpdates!=null)for(var source:completedMoves)worldUpdates.finishWallMove(region,source);
        return true;
    }

    private MinecraftWorldUpdates.WallMove wallMove(FluidRegion region,BlockPos source,FiniteThermalDeviceState device,
                                                    net.minecraft.world.level.BlockGetter blocks) {
        var direct=worldUpdates==null?null:worldUpdates.wallMove(region,source);
        if(direct!=null)return direct;
        var identity=device.objectId().split("@");
        if(identity.length!=3||!identity[2].startsWith("moving:"))return null;
        var previousTarget=BlockPos.of(Long.parseLong(identity[2].substring(7)));
        var continued=worldUpdates==null?null:worldUpdates.continueWallMove(region,source,previousTarget);
        return continued!=null?continued:new MinecraftWorldUpdates.WallMove(previousTarget,
                blocks.getBlockEntity(previousTarget) instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity);
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
        return List.copyOf(requests);
    }
}
