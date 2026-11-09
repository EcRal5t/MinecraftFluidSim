package mmm.mixin;

import mmm.simulation.MmmRuntimeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonMovingBlockEntity.class)
abstract class PistonMotionMixin {
    @Inject(method="tick", at=@At("RETURN"))
    private static void mmm$capturePiston(Level level, BlockPos position, BlockState state,
                                          PistonMovingBlockEntity piston, CallbackInfo callback) {
        if (level instanceof ServerLevel server) {
            var runtime = MmmRuntimeRegistry.find(server.getServer());
            if (runtime != null) runtime.worldUpdates().pistonTick(server, position, piston);
        }
    }
}
