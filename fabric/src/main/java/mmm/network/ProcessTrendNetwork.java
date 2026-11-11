package mmm.network;

import java.util.List;
import mmm.block.ProcessSensorBlockEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class ProcessTrendNetwork {
    private ProcessTrendNetwork() { }
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ProcessTrendPayload.TYPE, ProcessTrendPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ProcessTrendRequest.TYPE, ProcessTrendRequest.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ProcessTrendRequest.TYPE, (request, context) ->
            context.server().execute(() -> reply(context.player(), request)));
    }
    private static void reply(ServerPlayer player, ProcessTrendRequest request) {
        var result=response(player,request);
        if(result!=null && ServerPlayNetworking.canSend(player,ProcessTrendPayload.TYPE)) ServerPlayNetworking.send(player,result);
    }
    public static ProcessTrendPayload response(ServerPlayer player, ProcessTrendRequest request) {
        var pos=request.position(); var level=player.level();
        if (!level.dimension().identifier().toString().equals(request.dimension())) return null;
        if (!level.hasChunkAt(pos) || player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>64
                || !(level.getBlockEntity(pos) instanceof ProcessSensorBlockEntity sensor))
            return new ProcessTrendPayload(pos,request.dimension(),request.session(),request.sequence(),"","不可用",request.open(),false,List.of());
        sensor.subscribeTrend(new ProcessSensorBlockEntity.TrendObserver(player.getUUID(),request.session()),request.sequence(),level.getGameTime(),request.selected(),request.close());
        return new ProcessTrendPayload(pos,request.dimension(),request.session(),request.sequence(),
                sensor.snapshot().measure().name(),sensor.snapshot().status().name(),request.open(),true,
                sensor.trendCatalog(), request.selected().stream()
                        .map(key -> new ProcessTrendPayload.Series(key,sensor.trendHistory(key).points())).toList());
    }
}
