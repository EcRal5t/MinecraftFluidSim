package mmm.mixin;

import mmm.simulation.PlayerContainerTransferAdapter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes bucket use toward the world-bridge transfer service before vanilla item behaviour.
 * Only server-side, world-bridge-enabled bucket-family items aimed at an owned region are
 * intercepted; everything else returns without touching vanilla logic. Command selection is
 * deliberately irrelevant to physical transfer targeting.
 */
@Mixin(ItemStack.class)
abstract class ItemStackMixin {
	@Inject(method = "use", at = @At("HEAD"), cancellable = true)
	private void mmm$handleBucketTransfer(
			Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir
	) {
		if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return;
		InteractionResult result = PlayerContainerTransferAdapter.handle(serverPlayer, hand);
		if (result != null && result != InteractionResult.PASS) {
			cir.setReturnValue(result);
		}
	}
}
