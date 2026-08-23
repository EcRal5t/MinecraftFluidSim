package com.example;

import com.example.block.RealWaterBlocks;
import com.example.item.RealWaterComponents;
import com.example.item.RealWaterItems;
import com.example.item.RealWaterRecipes;
import com.example.network.FluidProtocolServer;
import com.example.simulation.FluidSimulationService;
import com.example.simulation.FluidTransferService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DispenserBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RealWaterMod implements ModInitializer {
	public static final String MOD_ID = "realwater";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		RealWaterBlocks.register();
		RealWaterComponents.register();
		RealWaterItems.register();
		RealWaterRecipes.register();
		registerDispenserBehaviors();
		FluidProtocolServer.register();
		RealWaterCommands.register();
		ServerTickEvents.END_SERVER_TICK.register(server -> FluidSimulationService.get(server).tick(server));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			FluidSimulationService.recoverTakeoverBackup(server);
			FluidSimulationService.recoverWorldOwnership(server);
			FluidSimulationService.get(server).recoverContainerTransfers(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			FluidSimulationService.get(server).restoreAirDemoScaffolding(server);
			FluidProtocolServer.clearServer(server);
			FluidSimulationService.removeServer(server);
		});
		LOGGER.info("Real Water bounded 3D prototype initialized");
	}

	private static void registerDispenserBehaviors() {
		DispenseItemBehavior vanillaWater = DispenserBlock.DISPENSER_REGISTRY.getOrDefault(
				Items.WATER_BUCKET, DispenseItemBehavior.NOOP);
		DispenseItemBehavior vanillaBucket = DispenserBlock.DISPENSER_REGISTRY.getOrDefault(
				Items.BUCKET, DispenseItemBehavior.NOOP);

		DispenserBlock.registerBehavior(Items.WATER_BUCKET, (source, stack) -> {
			ItemStack result = FluidTransferService.handleDispenserUse(source, stack);
			return result != null ? result : vanillaWater.dispense(source, stack);
		});
		DispenserBlock.registerBehavior(Items.BUCKET, (source, stack) -> {
			ItemStack result = FluidTransferService.handleDispenserUse(source, stack);
			return result != null ? result : vanillaBucket.dispense(source, stack);
		});
		DispenserBlock.registerBehavior(RealWaterItems.PARTIAL_WATER_BUCKET, (source, stack) -> {
			ItemStack result = FluidTransferService.handleDispenserUse(source, stack);
			return result != null ? result : stack;
		});
		DispenserBlock.registerBehavior(RealWaterItems.MATERIAL_BUCKET, (source, stack) -> {
			ItemStack result = FluidTransferService.handleDispenserUse(source, stack);
			return result != null ? result : stack;
		});
		DispenserBlock.registerBehavior(RealWaterItems.GRANULAR_CONTAINER, (source, stack) -> {
			ItemStack result = FluidTransferService.handleDispenserUse(source, stack);
			return result != null ? result : stack;
		});
	}


	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
