package com.example.client;

import com.example.network.FluidProtocol;
import com.example.network.FluidProtocolOfferPayload;
import com.example.network.FluidProtocolResponsePayload;
import com.example.network.FluidSnapshotPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import java.util.concurrent.atomic.AtomicInteger;

public final class RealWaterModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientFluidVolumeRenderer renderer = new ClientFluidVolumeRenderer();
		AtomicInteger negotiatedVersion = new AtomicInteger();
		ClientConfigurationNetworking.registerGlobalReceiver(FluidProtocolOfferPayload.TYPE, (payload, context) -> {
			FluidProtocol.Negotiation negotiation = FluidProtocol.negotiate(payload.offer());
			negotiatedVersion.set(negotiation.accepted() ? negotiation.selectedVersion() : 0);
			context.responseSender().sendPacket(FluidProtocolResponsePayload.from(negotiation));
		});
		ClientPlayNetworking.registerGlobalReceiver(FluidSnapshotPayload.TYPE,
				(payload, context) -> renderer.accept(payload, negotiatedVersion.get()));
		ClientConfigurationConnectionEvents.START.register((listener, client) -> {
			negotiatedVersion.set(0);
			renderer.clear();
		});
		ClientConfigurationConnectionEvents.DISCONNECT.register((listener, client) -> {
			negotiatedVersion.set(0);
			renderer.clear();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
			negotiatedVersion.set(0);
			renderer.clear();
		});
		LevelExtractionEvents.END_EXTRACTION.register(renderer::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(renderer::render);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> renderer.close());
	}
}
