package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.texture.TextureMap;
import net.vulkanmod112.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets animated block textures be turned off.
 *
 * Every animated sprite in the atlas uploads a new frame to the GPU each tick,
 * whether or not a single one of its blocks is on screen. Vanilla has no
 * switch for it, and with a modpack's worth of machines and fluids the uploads
 * add up to real frame time. This is the blunt version of what OptiFine calls
 * Smart Animations: off means off, everywhere.
 */
@Mixin(TextureMap.class)
public abstract class TextureMapAnimationMixin {

    @Inject(method = "updateAnimations", at = @At("HEAD"), cancellable = true)
    private void vulkanmod112$skipAnimations(CallbackInfo ci) {
        if (!VulkanConfig.areAnimationsEnabled()) {
            ci.cancel();
        }
    }
}
