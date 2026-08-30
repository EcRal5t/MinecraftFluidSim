package cc.ecisr.mmm;

import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.item.MmmComponents;
import cc.ecisr.mmm.item.MmmCreativeEntries;
import cc.ecisr.mmm.item.MmmItems;
import cc.ecisr.mmm.item.MmmRecipes;
import cc.ecisr.mmm.network.FluidProtocolServer;
import cc.ecisr.mmm.simulation.DispenserContainerTransferAdapter;
import cc.ecisr.mmm.simulation.MmmRuntime;
import cc.ecisr.mmm.simulation.MmmRuntimeRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
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
		ServerLifecycleEvents.SERVER_STARTING.register(MmmRuntimeRegistry::start);
		PlayerBlockBreakEvents.BEFORE.register((level, player, position, state, blockEntity) -> {
			if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)
					|| !MmmRuntimeRegistry.require(serverLevel.getServer()).simulation()
					.isLifecycleManagedBlock(serverLevel, position)) return true;
			player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
					"message.mmm.controller.break_blocked"));
			return false;
		});
		ServerTickEvents.END_SERVER_TICK.register(server ->
				MmmRuntimeRegistry.require(server).simulation().tick(server));
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) ->
				MmmRuntimeRegistry.require(level.getServer()).simulation()
						.retryUnavailableContainerTransfers(level, chunk.getPos()));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> MmmRuntimeRegistry.require(server).recoverWorld(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			MmmRuntime runtime = MmmRuntimeRegistry.find(server);
			if (runtime != null) runtime.debugFixtures().restoreAirDemoScaffolding(server);
			FluidProtocolServer.clearServer(server);
			MmmRuntimeRegistry.stop(server);
		});
		LOGGER.info("Matter, Motion & Mechanics bounded fluid simulation initialized");
	}

	private static void registerDispenserBehaviors() {
		DispenseItemBehavior vanillaWater = DispenserBlock.DISPENSER_REGISTRY.getOrDefault(
				Items.WATER_BUCKET, DispenseItemBehavior.NOOP);
		DispenseItemBehavior vanillaBucket = DispenserBlock.DISPENSER_REGISTRY.getOrDefault(
				Items.BUCKET, DispenseItemBehavior.NOOP);

		DispenserBlock.registerBehavior(Items.WATER_BUCKET, (source, stack) -> {
			ItemStack result = DispenserContainerTransferAdapter.handle(source, stack);
			return result != null ? result : vanillaWater.dispense(source, stack);
		});
		DispenserBlock.registerBehavior(Items.BUCKET, (source, stack) -> {
			ItemStack result = DispenserContainerTransferAdapter.handle(source, stack);
			return result != null ? result : vanillaBucket.dispense(source, stack);
		});
		DispenserBlock.registerBehavior(MmmItems.PARTIAL_WATER_BUCKET, (source, stack) -> {
			ItemStack result = DispenserContainerTransferAdapter.handle(source, stack);
			return result != null ? result : stack;
		});
		DispenserBlock.registerBehavior(MmmItems.MATERIAL_BUCKET, (source, stack) -> {
			ItemStack result = DispenserContainerTransferAdapter.handle(source, stack);
			return result != null ? result : stack;
		});
		DispenserBlock.registerBehavior(MmmItems.GRANULAR_CONTAINER, (source, stack) -> {
			ItemStack result = DispenserContainerTransferAdapter.handle(source, stack);
			return result != null ? result : stack;
		});
	}


	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
