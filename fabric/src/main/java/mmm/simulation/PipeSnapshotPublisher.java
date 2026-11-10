package mmm.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import mmm.network.PipeSnapshotPayload;
import mmm.pipe.PipeNetworkState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Publishes accepted pipe inventory and accepted bore shapes for one dimension. */
public final class PipeSnapshotPublisher {
    private static final Map<net.minecraft.server.MinecraftServer,PipeSnapshotPublisher> LIVE=new java.util.WeakHashMap<>();
    private final Map<net.minecraft.resources.Identifier,List<PipeSnapshotPayload>> latest=new java.util.HashMap<>();
    private final CommittedWorldRegistries world;
    public PipeSnapshotPublisher(CommittedWorldRegistries world){this.world=java.util.Objects.requireNonNull(world);}
    public void accept(ServerLevel level,long generation,PipeNetworkState state,Map<BlockPos,VoxelShape> acceptedBores) {
        var values=project(state,acceptedBores);var snapshot=parts(level,generation,values);
        LIVE.put(level.getServer(),this);latest.put(level.dimension().identifier(),snapshot);
        for(var payload:snapshot)for(var player:level.players())mmm.network.FluidProtocolServer.send(player,payload);
    }
    public void sendTo(net.minecraft.server.level.ServerPlayer player,long generation,PipeNetworkState state,Map<BlockPos,VoxelShape> acceptedBores) {
        for(var payload:parts(player.level(),generation,project(state,acceptedBores)))mmm.network.FluidProtocolServer.send(player,payload);
    }
    public void clear(ServerLevel level,long generation) {
        var snapshot=parts(level,generation,List.of());LIVE.put(level.getServer(),this);latest.put(level.dimension().identifier(),snapshot);
        for(var payload:snapshot)for(var player:level.players())mmm.network.FluidProtocolServer.send(player,payload);
    }
    public static void sendCurrent(net.minecraft.server.level.ServerPlayer player) {
        var publisher=LIVE.get(player.level().getServer());
        if(publisher!=null)for(var payload:publisher.latest.getOrDefault(player.level().dimension().identifier(),List.of()))
            mmm.network.FluidProtocolServer.send(player,payload);
    }
    public static void clearServer(net.minecraft.server.MinecraftServer server){LIVE.remove(server);}
    private static List<PipeSnapshotPayload> parts(ServerLevel level,long generation,List<PipeSnapshotPayload.Segment> values) {
        int count=Math.max(1,(values.size()+PipeSnapshotPayload.MAX_SEGMENTS-1)/PipeSnapshotPayload.MAX_SEGMENTS);
        if(count>PipeSnapshotPayload.MAX_PARTS)throw new IllegalArgumentException("Pipe display exceeds segment budget");
        var result=new ArrayList<PipeSnapshotPayload>(count);
        for(int part=0;part<count;part++)result.add(new PipeSnapshotPayload(level.dimension().identifier(),generation,part,count,
                values.subList(part*PipeSnapshotPayload.MAX_SEGMENTS,Math.min(values.size(),(part+1)*PipeSnapshotPayload.MAX_SEGMENTS))));
        return List.copyOf(result);
    }
    List<PipeSnapshotPayload.Segment> project(PipeNetworkState state,Map<BlockPos,VoxelShape> acceptedBores) {
        var result=new ArrayList<PipeSnapshotPayload.Segment>();
        for(int index=0;index<state.segments().size();index++) {
            var segment=state.segments().get(index);var position=BlockPos.of(Long.parseLong(segment.id()));
            var shape=java.util.Objects.requireNonNull(acceptedBores.get(position),"Missing accepted pipe bore");
            var layers=new ArrayList<PipeSnapshotPayload.Layer>();double bed=0,best=0;int bedColor=0;
            for(var deposit:segment.deposits()) {
                var group=deposit.inventory();var material=world.granularRegistry().material(world.granularRegistry().materialHandle(group.materialId()));
                double volume=mmm.granular.SolidVolume.bulkVolumeCeil(group.solidVolumeSvq(),material.restPorosityQ())/(double)mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER;
                bed+=volume;if(volume>best){best=volume;bedColor=material.baseArgb();}
            }
            if(bed>0)layers.add(new PipeSnapshotPayload.Layer("mmm:granular",bedColor,bed));
            var liquids=new ArrayList<>(state.liquids().rows().get(index));
            liquids.sort(Comparator.comparingDouble((mmm.inventory.LiquidCellInventory.Stock stock)->
                    world.resolveMaterial(state.materialIds().get(stock.key().material())).referenceDensity()).reversed());
            for(var stock:liquids) {
                String id=state.materialIds().get(stock.key().material());double volume=stock.volume().toDouble();
                if(volume>0)layers.add(new PipeSnapshotPayload.Layer(id,world.resolveMaterial(id).displayArgb(),volume));
            }
            if(layers.isEmpty())continue;
            var boxes=shape.toAabbs().stream().map(box->new PipeSnapshotPayload.Box(pixel(box.minX),pixel(box.minY),pixel(box.minZ),
                    pixel(box.maxX),pixel(box.maxY),pixel(box.maxZ))).toList();
            result.add(new PipeSnapshotPayload.Segment(position.asLong(),boxes,layers));
        }
        return List.copyOf(result);
    }
    private static int pixel(double value){double pixel=value*16;int rounded=(int)Math.round(pixel);if(Math.abs(pixel-rounded)>1e-7)throw new IllegalArgumentException("Pipe bore is not pixel aligned");return rounded;}
}
