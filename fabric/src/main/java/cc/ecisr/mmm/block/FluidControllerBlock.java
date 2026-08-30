package cc.ecisr.mmm.block;

import cc.ecisr.mmm.simulation.FluidSimulationService;
import cc.ecisr.mmm.simulation.MmmRuntimeRegistry;
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

		FluidSimulationService.ControllerActivation result = MmmRuntimeRegistry.require(level.getServer()).simulation()
				.activateController(serverPlayer, position, player.isShiftKeyDown());
		if (result.succeeded()) {
			String message = switch (result.action()) {
				case CREATED -> "message.mmm.controller.activated";
				case SELECTED_RUNNING -> "message.mmm.controller.selected_running";
				case SELECTED_PAUSED -> "message.mmm.controller.selected_paused";
				case PAUSED -> "message.mmm.controller.paused";
				case RESUMED -> "message.mmm.controller.resumed";
			};
			player.sendSystemMessage(Component.translatable(message, result.interiorBlocks(), result.sizeX(),
					result.sizeY(), result.sizeZ(), result.requiredChunks()));
		} else if (result.disabled()) {
			player.sendSystemMessage(Component.translatable("message.mmm.controller.disabled"));
		} else {
			player.sendSystemMessage(Component.translatable("message.mmm.controller.failed", result.failure()));
		}
		return InteractionResult.SUCCESS_SERVER;
	}
}
