package cc.ecisr.mmm.client;

import cc.ecisr.mmm.network.FluidProtocol;
import cc.ecisr.mmm.network.FluidProtocolOfferPayload;
import cc.ecisr.mmm.network.FluidProtocolResponsePayload;
import cc.ecisr.mmm.network.FluidMaterialSnapshotPayload;
import cc.ecisr.mmm.network.FluidChemicalSnapshotPayload;
import cc.ecisr.mmm.network.FluidGranularSnapshotPayload;
import cc.ecisr.mmm.network.FluidThermalSnapshotPayload;
import cc.ecisr.mmm.network.FluidSnapshotPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import java.util.concurrent.atomic.AtomicReference;

public final class MmmModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientFluidVolumeRenderer renderer = new ClientFluidVolumeRenderer();
		AtomicReference<FluidProtocol.Negotiation> negotiated = new AtomicReference<>();
		ClientConfigurationNetworking.registerGlobalReceiver(FluidProtocolOfferPayload.TYPE, (payload, context) -> {
			FluidProtocol.Negotiation negotiation = FluidProtocol.negotiate(payload.offer());
			negotiated.set(negotiation.accepted() ? negotiation : null);
			context.responseSender().sendPacket(FluidProtocolResponsePayload.from(negotiation));
		});
		ClientPlayNetworking.registerGlobalReceiver(FluidSnapshotPayload.TYPE, (payload, context) -> {
			FluidProtocol.Negotiation negotiation = negotiated.get();
			renderer.accept(payload, negotiation == null ? null : negotiation.selectedLayout());
		});
		ClientPlayNetworking.registerGlobalReceiver(FluidMaterialSnapshotPayload.TYPE,
				(payload, context) -> renderer.accept(payload, negotiated.get()));
		ClientPlayNetworking.registerGlobalReceiver(FluidChemicalSnapshotPayload.TYPE,
				(payload, context) -> renderer.accept(payload, negotiated.get()));
		ClientPlayNetworking.registerGlobalReceiver(FluidGranularSnapshotPayload.TYPE,
				(payload, context) -> renderer.accept(payload, negotiated.get()));
		ClientPlayNetworking.registerGlobalReceiver(FluidThermalSnapshotPayload.TYPE,
				(payload, context) -> renderer.accept(payload, negotiated.get()));
		ClientConfigurationConnectionEvents.START.register((listener, client) -> {
			negotiated.set(null);
			renderer.clear();
		});
		ClientConfigurationConnectionEvents.DISCONNECT.register((listener, client) -> {
			negotiated.set(null);
			renderer.clear();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
			negotiated.set(null);
			renderer.clear();
		});
		LevelExtractionEvents.END_EXTRACTION.register(renderer::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(renderer::render);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> renderer.close());
	}
}
