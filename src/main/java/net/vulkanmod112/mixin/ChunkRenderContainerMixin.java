package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.ChunkRenderContainer;
import net.vulkanmod112.client.TerrainHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the per-frame camera position the game uses to offset chunk geometry. */
@Mixin(ChunkRenderContainer.class)
public abstract class ChunkRenderContainerMixin {

    @Inject(method = "initialize", at = @At("HEAD"))
    private void vulkanmod112$captureViewPos(double viewEntityX, double viewEntityY, double viewEntityZ,
                                             CallbackInfo ci) {
        TerrainHooks.setViewPosition(viewEntityX, viewEntityY, viewEntityZ);
    }

}
