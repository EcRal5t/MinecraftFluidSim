package com.example.block;

import com.example.simulation.FluidSimulationService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Empty-hand activation surface for the bounded controller-region feature flag. */
final class FluidControllerBlock extends Block {
	FluidControllerBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected InteractionResult useWithoutItem(
			BlockState state,
			Level level,
			BlockPos position,
			Player player,
			BlockHitResult hitResult
	) {
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

		FluidSimulationService.ControllerActivation result = FluidSimulationService.get(level.getServer())
				.activateController(serverPlayer, position);
		if (result.succeeded()) {
			player.sendSystemMessage(Component.translatable(
					result.selectedExisting()
							? "message.realwater.controller.selected"
							: "message.realwater.controller.activated",
					result.interiorBlocks(), result.sizeX(), result.sizeY(), result.sizeZ(), result.requiredChunks()
			));
		} else if (result.disabled()) {
			player.sendSystemMessage(Component.translatable("message.realwater.controller.disabled"));
		} else {
			player.sendSystemMessage(Component.translatable("message.realwater.controller.failed", result.failure()));
		}
		return InteractionResult.SUCCESS_SERVER;
	}
}
