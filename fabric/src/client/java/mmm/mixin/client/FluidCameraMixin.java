package mmm.mixin.client;

import mmm.client.ClientFluidVolumeRenderer;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes vanilla fog and Iris isEyeInWater use the displayed liquid geometry. */
@Mixin(Camera.class)
public abstract class FluidCameraMixin {
	@Shadow public abstract Vec3 position();

	@Inject(method = "getFluidInCamera", at = @At("RETURN"), cancellable = true)
	private void mmm$fluidAtCamera(CallbackInfoReturnable<FogType> result) {
		if (result.getReturnValue() != FogType.NONE) return;
		ClientFluidVolumeRenderer renderer = ClientFluidVolumeRenderer.current();
		if (renderer == null) return;
		Vec3 position = position();
		if ("mmm:water".equals(renderer.materialAt(position.x, position.y, position.z))) {
			result.setReturnValue(FogType.WATER);
		}
	}
}
