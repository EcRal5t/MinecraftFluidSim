package mmm.simulation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mmm.world.CollisionRegion;
import mmm.world.MovingBodyFluidCoupling;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Stationary pipe envelopes reserve the pipe cavity while leaving surrounding fluid available. */
final class PipeCollisionBodies {
    private static final long PREFIX=0x4d4d4d5400000000L;
    private PipeCollisionBodies() { }
    static boolean isPipeBody(UUID id) { return (id.getMostSignificantBits()&0xffffffff00000000L)==PREFIX; }
    static void install(mmm.fluid.HostFluidState host,mmm.fluid.EntityBodyState geometry) {
        var combined=new ArrayList<>(host.entityBodyState().bodies().stream().filter(body->!isPipeBody(body.bodyId())).toList());
        combined.addAll(geometry.bodies().stream().filter(body->isPipeBody(body.bodyId())).toList());
        checkBudget(combined);
        var next=new mmm.fluid.EntityBodyState(combined);
        if(!next.equals(host.entityBodyState()))host.replaceCollisionBodies(List.of(),next);
    }
    static List<MovingBodyFluidCoupling.Request> capture(ServerLevel level,FluidRegion region,java.util.Set<CollisionRegion.Block> members) {
        var bodies=new ArrayList<MovingBodyFluidCoupling.Request>(); var origin=region.origin();
        for(var block:members) {
            var pos=origin.offset(block.x(),block.y(),block.z());
            if(!MinecraftGroupAvailability.loaded(level,pos))continue;
            var state=level.getBlockState(pos);if(!PipeThermalGeometry.isPipe(state))continue;
            for(var body:forBlock(pos,state,origin)) {
                if(MinecraftRegionInputs.bodyAvailable(region,body.bounds()))bodies.add(body);
            }
        }
        for(var old:region.entityBodyState().bodies())if(isPipeBody(old.bodyId()) && !MinecraftRegionInputs.bodyAvailable(region,old.bounds()))
            bodies.add(old.stationary());
        checkBudget(bodies);
        return List.copyOf(bodies);
    }
    static void checkBudget(List<MovingBodyFluidCoupling.Request> bodies) {
        if(bodies.size()>MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP)
            throw new mmm.fluid.FluidDomainContracts.CollisionEditBlockedException("Pipe collision bodies exceed region budget",null);
    }
    static List<MovingBodyFluidCoupling.Request> forBlock(BlockPos pos,net.minecraft.world.level.block.state.BlockState state,BlockPos origin) {
        var bodies=new ArrayList<MovingBodyFluidCoupling.Request>();
        for(var box:mmm.block.PipeGeometry.envelope(state).toAabbs()) {
                var bounds=new MovingBodyFluidCoupling.Bounds(pos.getX()-origin.getX()+box.minX,pos.getY()-origin.getY()+box.minY,
                        pos.getZ()-origin.getZ()+box.minZ,pos.getX()-origin.getX()+box.maxX,
                        pos.getY()-origin.getY()+box.maxY,pos.getZ()-origin.getZ()+box.maxZ);
                var hash=UUID.nameUUIDFromBytes((pos.asLong()+":"+box.minX+":"+box.minY+":"+box.minZ+":"
                        +box.maxX+":"+box.maxY+":"+box.maxZ).getBytes(StandardCharsets.UTF_8));
                bodies.add(new MovingBodyFluidCoupling.Request(new UUID(PREFIX|(hash.getMostSignificantBits()&0xffffffffL),hash.getLeastSignificantBits()),bounds,0,0,0));
        }
        return List.copyOf(bodies);
    }
}
