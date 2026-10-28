package mmm.client;

import mmm.item.MmmItems;
import mmm.network.FluidProtocol;
import mmm.network.FluidProtocolOfferPayload;
import mmm.network.FluidProtocolResponsePayload;
import mmm.network.FluidMaterialSnapshotPayload;
import mmm.network.FluidChemicalSnapshotPayload;
import mmm.network.FluidGranularSnapshotPayload;
import mmm.network.FluidThermalSnapshotPayload;
import mmm.network.FluidSnapshotPayload;
import mmm.network.ControllerSummaryPayload;
import mmm.network.LifecycleOperationPreviewPayload;
import mmm.network.ThermalReservoirSummaryPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResult;

import java.util.concurrent.atomic.AtomicReference;

public final class MmmModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FluidDisplaySettings.INSTANCE.load();
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!level.isClientSide() || !player.getItemInHand(hand).is(MmmItems.QUICK_START_GUIDE)) {
				return InteractionResult.PASS;
			}
			net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new QuickStartScreen());
			return InteractionResult.SUCCESS;
		});
		ClientFluidVolumeRenderer renderer = new ClientFluidVolumeRenderer();
		AtomicReference<FluidProtocol.Negotiation> negotiated = new AtomicReference<>();
        var snapshotParts = new mmm.network.FluidSnapshotPartPayload.Assembler();
        ClientPlayNetworking.registerGlobalReceiver(mmm.network.FluidSnapshotPartPayload.TYPE, (part, context) -> {
            var complete = snapshotParts.accept(part);
            if (complete instanceof FluidMaterialSnapshotPayload payload) renderer.accept(payload, negotiated.get());
            else if (complete instanceof FluidChemicalSnapshotPayload payload) renderer.accept(payload, negotiated.get());
            else if (complete instanceof FluidGranularSnapshotPayload payload) renderer.accept(payload, negotiated.get());
            else if (complete instanceof FluidThermalSnapshotPayload payload) renderer.accept(payload, negotiated.get());
        });
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
		ClientPlayNetworking.registerGlobalReceiver(ControllerSummaryPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					var client = context.client();
					if (client.level == null) return;
					String dimension = client.level.dimension().identifier().toString();
					if (payload.requestGeneration() == 0) {
						if (payload.summary().dimensionId().equals(dimension)) client.setScreenAndShow(new ControllerConsoleScreen(payload.summary()));
						return;
					}
					boolean matches = client.gui.screen() instanceof ControllerConsoleScreen screen
							&& screen.inDimension(dimension) && screen.awaiting(payload.regionId(), payload.requestGeneration());
					matches |= client.gui.screen() instanceof LifecycleConfirmationScreen screen
							&& screen.inDimension(dimension) && screen.awaiting(payload.regionId(), payload.requestGeneration());
					if (matches) client.setScreenAndShow(payload.summary() == null ? null : new ControllerConsoleScreen(payload.summary()));
				}));
		ClientPlayNetworking.registerGlobalReceiver(LifecycleOperationPreviewPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					var client = context.client();
					if (client.level != null && client.gui.screen() instanceof ControllerConsoleScreen screen
							&& screen.inDimension(client.level.dimension().identifier().toString())
							&& screen.awaiting(payload.preview().regionId(), payload.requestGeneration()))
						client.setScreenAndShow(new LifecycleConfirmationScreen(payload));
				}));
		ClientPlayNetworking.registerGlobalReceiver(ThermalReservoirSummaryPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					var client = context.client();
					if (client.level == null) return;
					String dimension = client.level.dimension().identifier().toString();
					if (payload.requestGeneration() == 0) {
						if (payload.summary().dimensionId().equals(dimension)) client.setScreenAndShow(new ThermalReservoirScreen(payload.summary()));
						return;
					}
					if (client.gui.screen() instanceof ThermalReservoirScreen screen && screen.inDimension(dimension)
							&& screen.awaiting(payload.position(), payload.requestGeneration()))
						client.setScreenAndShow(payload.summary() == null ? null : new ThermalReservoirScreen(payload.summary()));
				}));
		ClientConfigurationConnectionEvents.START.register((listener, client) -> {
			negotiated.set(null); snapshotParts.clear();
			renderer.clear();
		});
		ClientConfigurationConnectionEvents.DISCONNECT.register((listener, client) -> {
			negotiated.set(null); snapshotParts.clear();
			renderer.clear();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
			negotiated.set(null); snapshotParts.clear();
			renderer.clear();
		});
		LevelExtractionEvents.END_EXTRACTION.register(renderer::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(renderer::render);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> renderer.close());
	}
}

