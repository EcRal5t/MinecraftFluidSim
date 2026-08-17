package com.example.client;

import com.example.network.FluidSnapshotPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public final class RealWaterModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientFluidVolumeRenderer renderer = new ClientFluidVolumeRenderer();
		ClientPlayNetworking.registerGlobalReceiver(FluidSnapshotPayload.TYPE, (payload, context) -> renderer.accept(payload));
		LevelExtractionEvents.END_EXTRACTION.register(renderer::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(renderer::render);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> renderer.close());
	}
}
