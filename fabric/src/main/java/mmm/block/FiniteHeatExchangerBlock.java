package mmm.block;

import mmm.simulation.FiniteHeatExchangerOperations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

/** Fixed north/south thermal ports installed in a controller shell slot. */
public final class FiniteHeatExchangerBlock extends Block implements EntityBlock {
	FiniteHeatExchangerBlock(BlockBehaviour.Properties properties) {
		super(properties.strength(5.0F, 3_600_000.0F).pushReaction(PushReaction.BLOCK));
	}

	@Override public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
		return new FiniteHeatExchangerBlockEntity(position, state);
	}

	@Override protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos position,
			Player player, InteractionHand hand, BlockHitResult hitResult) {
		return stack.isEmpty() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
	}

	@Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position,
			Player player, BlockHitResult hitResult) {
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		return player instanceof ServerPlayer serverPlayer
				? (player.isShiftKeyDown()
						? FiniteHeatExchangerOperations.uninstall(serverPlayer, InteractionHand.MAIN_HAND, position)
						: FiniteHeatExchangerOperations.inspect(serverPlayer,position))
				: InteractionResult.PASS;
	}
}
