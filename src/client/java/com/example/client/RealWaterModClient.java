package com.example.client;

import com.example.network.FluidSnapshotPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class RealWaterModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(FluidSnapshotPayload.TYPE, (payload, context) -> ClientFluidDebugView.accept(payload));
		ClientTickEvents.END_CLIENT_TICK.register(ClientFluidDebugView::tick);
	}
}
