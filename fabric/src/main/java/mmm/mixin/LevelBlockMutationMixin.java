package mmm.mixin;

import mmm.simulation.MmmRuntimeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pending soil slots retain their saved state while HEAD recovery owns their transition. */
@Mixin(Level.class)
public abstract class LevelBlockMutationMixin {
	@Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("HEAD"),cancellable=true)
	private void mmm$protectSoilTransaction(BlockPos position,BlockState state,int flags,int recursion,
			CallbackInfoReturnable<Boolean> callback) {
		if((Object)this instanceof ServerLevel level) {
			var runtime=MmmRuntimeRegistry.find(level.getServer());
			if(runtime!=null && (!runtime.farmlandMoisture().allowsBlockChange(level,position,state)
					|| !runtime.pools().allowsBlockChange(level,position,state))) callback.setReturnValue(false);
		}
	}
	@Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("RETURN"))
	private void mmm$recordWorldChange(BlockPos position,BlockState state,int flags,int recursion,
			CallbackInfoReturnable<Boolean> callback) {
		if (callback.getReturnValueZ() && (Object)this instanceof ServerLevel level) {
			var runtime = MmmRuntimeRegistry.find(level.getServer());
			if (runtime != null) runtime.worldUpdates().changed(level, position);
		}
	}

}
