package mmm.block;

import mmm.simulation.MmmRuntimeRegistry;
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

/** Shell-mounted source of a finite interval of prescribed mechanical motion. */
final class MechanicalDriverBlock extends Block {
    MechanicalDriverBlock(BlockBehaviour.Properties properties){super(properties);}
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos position,Player player,BlockHitResult hit){
        if(level.isClientSide())return InteractionResult.SUCCESS;
        if(!(player instanceof ServerPlayer serverPlayer))return InteractionResult.PASS;
        var operations=MmmRuntimeRegistry.require(serverPlayer.level().getServer()).processes();
        String message=player.isShiftKeyDown()?operations.stopDriveFromBlock(serverPlayer,position):operations.startDriveFromBlock(serverPlayer,position,hit.getDirection());
        serverPlayer.sendSystemMessage(Component.literal(message));
        return InteractionResult.SUCCESS;
    }
}
