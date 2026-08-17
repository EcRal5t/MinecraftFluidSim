package com.example;

import com.example.network.FluidSnapshotPayload;
import com.example.simulation.FluidSimulationService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RealWaterMod implements ModInitializer {
	public static final String MOD_ID = "realwater";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(FluidSnapshotPayload.TYPE, FluidSnapshotPayload.CODEC);
		RealWaterCommands.register();
		ServerTickEvents.END_SERVER_TICK.register(server -> FluidSimulationService.get(server).tick(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(FluidSimulationService::removeServer);
		LOGGER.info("Real Water bounded 3D prototype initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
