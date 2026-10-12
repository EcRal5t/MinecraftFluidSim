package mmm.block;

import mmm.simulation.MinecraftRegionActivation;
import mmm.simulation.MmmRuntimeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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

	/** Held items own their interaction; only an actually empty hand enters controller activation. */
	@Override
	protected InteractionResult useItemOn(
			ItemStack stack, BlockState state, Level level, BlockPos position,
			Player player, InteractionHand hand, BlockHitResult hitResult
	) {
		return stack.isEmpty() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
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

		MinecraftRegionActivation.Result result = MmmRuntimeRegistry.require(level.getServer()).regionActivation()
				.activate(serverPlayer, position, player.isShiftKeyDown());
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
