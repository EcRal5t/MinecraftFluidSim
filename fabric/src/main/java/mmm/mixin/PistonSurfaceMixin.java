package mmm.mixin;

import mmm.simulation.MmmRuntimeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fixed surface inventories stay attached until the player removes them. */
@Mixin(PistonBaseBlock.class)
abstract class PistonSurfaceMixin {
    @Inject(method="triggerEvent", at=@At("HEAD"), cancellable=true)
    private void mmm$protectSurfaces(BlockState state, Level level, BlockPos position, int event, int data,
                                    CallbackInfoReturnable<Boolean> callback) {
        if (!(level instanceof ServerLevel server) || (event != 0 && event != 1)) return;
        var runtime = MmmRuntimeRegistry.find(server.getServer());
        if (runtime == null) return;
        Direction direction = state.getValue(PistonBaseBlock.FACING);
        var resolver = new PistonStructureResolver(level, position, direction, event == 0);
        if (!resolver.resolve()) return;
        for (var block : resolver.getToPush())
            if (runtime.finiteHeatExchangers().hasSurfaceAt(server, block)) { callback.setReturnValue(false); return; }
        for (var block : resolver.getToDestroy())
            if (runtime.finiteHeatExchangers().hasSurfaceAt(server, block)) { callback.setReturnValue(false); return; }
    }
}
