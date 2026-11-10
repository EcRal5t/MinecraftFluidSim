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

/** Protects world objects during their pending inventory and ownership transactions. */
@Mixin(Level.class)
public abstract class LevelBlockMutationMixin {
	@Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("HEAD"),cancellable=true)
	private void mmm$protectSoilTransaction(BlockPos position,BlockState state,int flags,int recursion,
			CallbackInfoReturnable<Boolean> callback) {
		if((Object)this instanceof ServerLevel level) {
			var runtime=MmmRuntimeRegistry.find(level.getServer());
            if(runtime!=null&&runtime.pipes()!=null&&!runtime.pipes().allowsBlockChange(level,position,state)) {
                callback.setReturnValue(false);return;
            }
			if (!state.is(mmm.block.MmmBlocks.MATERIAL_BOX)
					&& !mmm.simulation.IndustrialOperations.allowsBoxRemoval(level,position)) {
				callback.setReturnValue(false); return;
			}
			if(runtime!=null && (!runtime.farmlandMoisture().allowsBlockChange(level,position,state)
					|| !runtime.pools().allowsBlockChange(level,position,state))) callback.setReturnValue(false);
		}
	}
	@Inject(method="destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",at=@At("HEAD"),cancellable=true)
	private void mmm$protectBoxBeforeDrops(BlockPos position,boolean drops,net.minecraft.world.entity.Entity source,int recursion,
			CallbackInfoReturnable<Boolean> callback) {
        if((Object)this instanceof ServerLevel level) {
            var runtime=MmmRuntimeRegistry.find(level.getServer());
            if(!mmm.simulation.IndustrialOperations.allowsBoxRemoval(level,position)
                    || runtime!=null&&runtime.pipes()!=null&&!runtime.pipes().allowsBlockChange(level,position,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()))
                callback.setReturnValue(false);
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
