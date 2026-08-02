package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import net.vulkanmod112.client.ShaderPackSkins;
import net.vulkanmod112.client.SunSkin;
import net.vulkanmod112.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Puts a different picture on the sun, and touches nothing else.
 *
 * The sky is drawn by one long vanilla method that binds several textures in
 * turn — the sky itself, the sun, the moon. Redirecting the bind and answering
 * only for the sun is the narrowest possible change: the quad, its size, its
 * position, the blend, the order and the moon are all still vanilla's, and a
 * mod that draws its own sky never reaches this code at all.
 */
@Mixin(RenderGlobal.class)
public abstract class SkySunMixin {

    private static final String SUN = "textures/environment/sun.png";
    private static final String MOON = "textures/environment/moon_phases.png";

    @Redirect(method = "renderSky(FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/TextureManager;"
                            + "bindTexture(Lnet/minecraft/util/ResourceLocation;)V"))
    private void vulkanmod112$bindSky(TextureManager manager, ResourceLocation texture) {
        String path = texture.getPath();
        if (SUN.equals(path) || MOON.equals(path)) {
            // A picture out of a pack the player already has comes first: they
            // chose it, and it is more specific than anything drawn here.
            ResourceLocation borrowed = ShaderPackSkins.texture(VulkanConfig.getSkinPack(),
                    SUN.equals(path) ? ShaderPackSkins.SUN : ShaderPackSkins.MOON);
            if (borrowed != null) {
                manager.bindTexture(borrowed);
                return;
            }
            ResourceLocation ours = SUN.equals(path) ? SunSkin.current() : SunSkin.currentMoon();
            if (ours != null) {
                manager.bindTexture(ours);
                return;
            }
        }
        manager.bindTexture(texture);
    }
}
