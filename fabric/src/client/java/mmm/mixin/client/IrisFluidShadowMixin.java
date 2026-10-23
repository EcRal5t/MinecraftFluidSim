package mmm.mixin.client;

import mmm.client.ClientFluidVolumeRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Submits the same material mesh while Iris owns the translucent shadow target. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisFluidShadowMixin {
	@Inject(method = "renderShadows", at = @At(value = "INVOKE", target =
			"Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
			ordinal = 1, shift = At.Shift.AFTER), remap = false)
	private void mmm$renderFluidShadow(CallbackInfo ci) {
		ClientFluidVolumeRenderer renderer = ClientFluidVolumeRenderer.current();
		if (renderer != null) renderer.renderShadow(Minecraft.getInstance().gameRenderer.mainCamera().position());
	}
}
