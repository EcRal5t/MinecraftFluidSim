package mmm.client;

import mmm.network.ProcessTrendPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ProcessTrendClient {
    private ProcessTrendClient() { }
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ProcessTrendPayload.TYPE,(payload,context)->context.client().execute(()->{
            var client=context.client(); if(client.level==null) return;
            var dimension=client.level.dimension().identifier().toString();
            if(!dimension.equals(payload.dimension())) return;
            if(payload.open()) client.setScreenAndShow(new ProcessTrendScreen(payload));
            else if(client.gui.screen() instanceof ProcessTrendScreen screen && screen.matches(payload,dimension)) screen.accept(payload);
        }));
    }
}
