package mmm.client;

import java.util.UUID;
import mmm.block.MmmBlocks;
import mmm.network.ProcessTrendPayload;
import mmm.network.ProcessTrendRequest;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;

public final class ProcessTrendClient {
    private static final ObservationOpen pending = new ObservationOpen();
    private ProcessTrendClient() { }
    public static void register() {
        UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            if (!level.isClientSide() || !player.isShiftKeyDown() || !player.getItemInHand(hand).isEmpty()
                    || !level.getBlockState(hit.getBlockPos()).is(MmmBlocks.PROCESS_SENSOR)
                    || !ClientPlayNetworking.canSend(ProcessTrendRequest.TYPE)) return InteractionResult.PASS;
            String dimension=level.dimension().identifier().toString(); UUID session=pending.start(dimension);
            ClientPlayNetworking.send(new ProcessTrendRequest(hit.getBlockPos(),dimension,session,0,true));
            return InteractionResult.SUCCESS;
        });
        ClientPlayNetworking.registerGlobalReceiver(ProcessTrendPayload.TYPE,(payload,context)->context.client().execute(()->{
            var client=context.client(); if(client.level==null) return;
            var dimension=client.level.dimension().identifier().toString();
            if(!dimension.equals(payload.dimension())) return;
            if(payload.open()) {
                if(client.gui.screen()==null && pending.accept(payload.session(),dimension))
                    client.setScreenAndShow(new ProcessTrendScreen(payload));
            } else if(client.gui.screen() instanceof ProcessTrendScreen screen && screen.matches(payload,dimension)) screen.accept(payload);
        }));
        ClientTickEvents.END_CLIENT_TICK.register(client->pending.update(client.level==null?null:client.level.dimension().identifier().toString(),client.gui.screen()!=null));
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->pending.clear());
    }
}
