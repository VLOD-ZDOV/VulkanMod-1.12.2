package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import net.vulkanmod112.client.WeatherHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sends rain and snow to Vulkan instead of to OpenGL.
 *
 * Two redirects rather than a replacement of the method. What the weather looks
 * like is decided by column walking, biome temperature and seeded randomness
 * that this mod has no business owning; what it is drawn <em>by</em> is two
 * calls, and those are the two here. A mod that replaces the weather renderer
 * outright leaves early, before either of them, and is untouched.
 */
@Mixin(EntityRenderer.class)
public abstract class WeatherRenderMixin {

    @Redirect(method = "renderRainSnow",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/TextureManager;"
                            + "bindTexture(Lnet/minecraft/util/ResourceLocation;)V"))
    private void vulkanmod112$noteSheet(TextureManager textures, ResourceLocation location) {
        // Still bound: the batch may yet go to OpenGL, this frame or the next.
        textures.bindTexture(location);
        WeatherHooks.noteTexture(location);
    }

    @Redirect(method = "renderRainSnow",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V"))
    private void vulkanmod112$drawInVulkan(Tessellator tessellator) {
        if (!WeatherHooks.take(tessellator)) {
            tessellator.draw();
        }
    }
}
