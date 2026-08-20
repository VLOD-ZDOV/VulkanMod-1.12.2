package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.EntityLivingBase;
import net.vulkanmod112.client.EntityGeometry;
import net.vulkanmod112.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The red flash of a creature taking damage, and the white one of a creeper
 * about to go off.
 *
 * <h2>Why it went missing</h2>
 *
 * The game does not draw either of them. It rewrites what its <em>second
 * texture unit computes</em>: for the duration of one creature, unit one stops
 * being a multiply and becomes {@code GL_INTERPOLATE} between a constant colour
 * and whatever unit zero produced, with the constant's alpha deciding how much
 * — and the light map, which normally lives on that unit, is moved along to
 * unit two. In other words the flash is a piece of fixed-function pipeline
 * configuration, carried in no vertex and in no texture, and this renderer has
 * no fixed-function pipeline for it to be carried in. Capturing the geometry
 * captured everything about the creature except the one thing that had changed.
 *
 * That is the same shape of loss as the lighting was: taking over the drawing
 * takes over state nobody wrote down. The cure is the same too — read what the
 * game was about to do, at the moment it decides.
 *
 * <h2>Why the arithmetic is repeated rather than read</h2>
 *
 * {@code getColorMultiplier} is asked again here instead of the answer being
 * lifted out of the method's own frame. Reading a local would bind this to the
 * shape of the method body, which is a thing three of the coremods in a large
 * pack rewrite; asking the same question through {@code @Shadow} binds it only
 * to the name, and the answer is a pure function of the creature either way.
 */
@Mixin(RenderLivingBase.class)
public abstract class EntityOverlayMixin {

    /** Vanilla's own, so a modded creature's colour is the one it asked for. */
    @Shadow
    protected abstract int getColorMultiplier(EntityLivingBase entity, float lightBrightness,
                                              float partialTicks);

    /**
     * @param combineTextures vanilla refuses a plain colour multiplier when
     *                        this is false, and so must this — otherwise a
     *                        creeper would flash in a pass the game leaves
     *                        alone.
     */
    @Inject(method = "setBrightness", at = @At("HEAD"))
    private void vulkanmod112$takeOverlay(EntityLivingBase entity, float partialTicks,
                                          boolean combineTextures,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!VulkanConfig.isVulkanEntities()) {
            return;
        }
        int colour = getColorMultiplier(entity, entity.getBrightness(), partialTicks);
        boolean tinted = (colour >> 24 & 255) > 0;
        boolean hurt = entity.hurtTime > 0 || entity.deathTime > 0;
        if ((!tinted && !hurt) || (!tinted && !combineTextures)) {
            EntityGeometry.setOverlay(0);
            return;
        }
        if (hurt) {
            // (1, 0, 0, 0.3), the game's own constant, packed.
            EntityGeometry.setOverlay(0x4DFF0000);
            return;
        }
        // The game puts red, green and blue straight through and inverts the
        // top byte to get the strength. Inverted, because what it stores there
        // is how much of the creature to keep.
        EntityGeometry.setOverlay(((255 - (colour >>> 24 & 255)) << 24) | (colour & 0xFFFFFF));
    }

    @Inject(method = "unsetBrightness", at = @At("HEAD"))
    private void vulkanmod112$dropOverlay(CallbackInfo ci) {
        EntityGeometry.setOverlay(0);
    }
}
