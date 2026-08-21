package com.example;

import com.example.block.RealWaterBlocks;
import com.example.network.FluidProtocolServer;
import com.example.simulation.FluidSimulationService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RealWaterMod implements ModInitializer {
	public static final String MOD_ID = "realwater";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		RealWaterBlocks.register();
		FluidProtocolServer.register();
		RealWaterCommands.register();
		ServerTickEvents.END_SERVER_TICK.register(server -> FluidSimulationService.get(server).tick(server));
		ServerLifecycleEvents.SERVER_STARTED.register(FluidSimulationService::recoverTakeoverBackup);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			FluidSimulationService.get(server).restoreAirDemoScaffolding(server);
			FluidProtocolServer.clearServer(server);
			FluidSimulationService.removeServer(server);
		});
		LOGGER.info("Real Water bounded 3D prototype initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
