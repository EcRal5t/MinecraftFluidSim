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
    public static void open(ServerPlayer player, ProcessSensorBlockEntity sensor) { send(player,sensor,true); }
    private static void reply(ServerPlayer player, ProcessTrendRequest request) {
        var pos=request.position(); var level=player.level();
        if (!level.hasChunkAt(pos) || player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>64
                || !(level.getBlockEntity(pos) instanceof ProcessSensorBlockEntity sensor)) {
            if(ServerPlayNetworking.canSend(player,ProcessTrendPayload.TYPE))
                ServerPlayNetworking.send(player,new ProcessTrendPayload(pos,level.dimension().identifier().toString(),"","不可用",false,false,List.of()));
            return;
        }
        send(player,sensor,false);
    }
    private static void send(ServerPlayer player,ProcessSensorBlockEntity sensor,boolean open) {
        if(ServerPlayNetworking.canSend(player,ProcessTrendPayload.TYPE))
            ServerPlayNetworking.send(player,new ProcessTrendPayload(sensor.getBlockPos(),player.level().dimension().identifier().toString(),
                sensor.measure().label(),sensor.history().status(),open,true,sensor.history().points()));
    }
}
