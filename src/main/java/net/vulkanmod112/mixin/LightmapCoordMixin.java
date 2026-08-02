package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.OpenGlHelper;
import net.vulkanmod112.client.GlTextureMirror;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * How much light the thing about to be drawn is standing in.
 *
 * A model's vertices do not carry it — the game sets one pair of texture
 * coordinates on the light map unit just before the draw, and the fixed
 * function pipeline multiplies it in. Drawing the model ourselves means
 * carrying that pair into every vertex, and this is the one place it is set.
 *
 * Only the light map's own unit is of interest; the same call is used for the
 * ordinary texture units and answering for those would overwrite it.
 */
@Mixin(OpenGlHelper.class)
public abstract class LightmapCoordMixin {

    @Inject(method = "setLightmapTextureCoords", at = @At("HEAD"))
    private static void vulkanmod112$lightmap(int target, float x, float y, CallbackInfo ci) {
        if (target == OpenGlHelper.lightmapTexUnit) {
            GlTextureMirror.lightmap(x, y);
        }
    }
}
