package com.example.item;

import com.example.simulation.FluidSimulationService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** Reusable operator indicator/eraser tools; both edit only marker provenance. */
public final class ChemicalIndicatorItem extends Item {
	private final boolean eraser;

	public ChemicalIndicatorItem(Item.Properties properties, boolean eraser) {
		super(properties);
		this.eraser = eraser;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
		FluidSimulationService.IndicatorUseResult result = FluidSimulationService.get(serverPlayer.level().getServer())
				.editIndicatorAtAim(serverPlayer, !eraser);
		if (!result.accepted()) {
			serverPlayer.sendSystemMessage(Component.literal(result.failure()), true);
			return InteractionResult.FAIL;
		}
		String message = String.format("%s %.3f BV（%d markers / %d cells）",
				eraser ? "已抹除指示剂" : "已加入通用指示剂", result.affectedBlockVolume(),
				result.changedMarkers(), result.scannedCells());
		if (result.checkpointWarning() != null) message += "；WARNING " + result.checkpointWarning();
		serverPlayer.sendSystemMessage(Component.literal(message), true);
		return InteractionResult.SUCCESS_SERVER;
	}
}
