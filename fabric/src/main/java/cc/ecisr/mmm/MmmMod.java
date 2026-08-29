package cc.ecisr.mmm;

import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.item.MmmComponents;
import cc.ecisr.mmm.item.MmmCreativeEntries;
import cc.ecisr.mmm.item.MmmItems;
import cc.ecisr.mmm.item.MmmRecipes;
import cc.ecisr.mmm.network.FluidProtocolServer;
import cc.ecisr.mmm.simulation.FluidSimulationService;
import cc.ecisr.mmm.simulation.FluidTransferService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DispenserBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MmmMod implements ModInitializer {
	public static final String MOD_ID = "mmm";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		MmmBlocks.register();
		MmmComponents.register();
		MmmItems.register();
		MmmCreativeEntries.register();
		MmmRecipes.register();
		registerDispenserBehaviors();
		FluidProtocolServer.register();
		MmmCommands.register();
		ServerTickEvents.END_SERVER_TICK.register(server -> FluidSimulationService.get(server).tick(server));
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) ->
				FluidSimulationService.get(level.getServer()).retryUnavailableContainerTransfers(level, chunk.getPos()));
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
		LOGGER.info("Matter, Motion & Mechanics bounded fluid simulation initialized");
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
		DispenserBlock.registerBehavior(MmmItems.PARTIAL_WATER_BUCKET, (source, stack) -> {
			ItemStack result = FluidTransferService.handleDispenserUse(source, stack);
			return result != null ? result : stack;
		});
		DispenserBlock.registerBehavior(MmmItems.MATERIAL_BUCKET, (source, stack) -> {
			ItemStack result = FluidTransferService.handleDispenserUse(source, stack);
			return result != null ? result : stack;
		});
		DispenserBlock.registerBehavior(MmmItems.GRANULAR_CONTAINER, (source, stack) -> {
			ItemStack result = FluidTransferService.handleDispenserUse(source, stack);
			return result != null ? result : stack;
		});
	}


	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
