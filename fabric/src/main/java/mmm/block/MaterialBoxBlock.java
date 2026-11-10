package mmm.block;

import mmm.simulation.MmmRuntimeRegistry;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

public final class MaterialBoxBlock extends Block implements EntityBlock {
    MaterialBoxBlock(Properties properties) { super(properties.pushReaction(PushReaction.BLOCK)); }
    @Override public BlockEntity newBlockEntity(BlockPos position,BlockState state) { return new MaterialBoxBlockEntity(position,state); }
    @Override protected void onExplosionHit(BlockState state,net.minecraft.server.level.ServerLevel level,BlockPos position,
            net.minecraft.world.level.Explosion explosion,java.util.function.BiConsumer<ItemStack,BlockPos> drops) {
        if(mmm.simulation.IndustrialOperations.allowsBoxRemoval(level,position))
            super.onExplosionHit(state,level,position,explosion,drops);
    }
    @Override protected InteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos position,Player player,InteractionHand hand,BlockHitResult hit) {
        if(level.isClientSide())return InteractionResult.SUCCESS;
        return player instanceof ServerPlayer p?MmmRuntimeRegistry.require(p.level().getServer()).industrial().useBox(p,hand,position):InteractionResult.PASS;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos position,Player player,BlockHitResult hit) {
        if(level.isClientSide())return InteractionResult.SUCCESS;
        return player instanceof ServerPlayer p?MmmRuntimeRegistry.require(p.level().getServer()).industrial().useBox(p,InteractionHand.MAIN_HAND,position):InteractionResult.PASS;
    }
}
