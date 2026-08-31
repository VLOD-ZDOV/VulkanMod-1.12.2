package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmodnext.client.AnimatedSprites;
import net.vulkanmodnext.client.AtlasAnimations;
import net.vulkanmodnext.client.TerrainHooks;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

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

    @Shadow
    private List<TextureAtlasSprite> listAnimatedSprites;

    @Inject(method = "updateAnimations", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$skipAnimations(CallbackInfo ci) {
        if (!VulkanConfig.areAnimationsEnabled()) {
            ci.cancel();
            return;
        }
        // From here to the return, every frame the game uploads is going into
        // this atlas — so the mirror does not have to ask the driver which
        // texture is bound, once per sprite. Only for the block atlas: the
        // other sheets that come through here are not the one being mirrored.
        if (((TextureMap) (Object) this) == Minecraft.getMinecraft().getTextureMapBlocks()) {
            AtlasAnimations.beginAtlasStep();
        }
        if (!VulkanConfig.isSmartAnimations()) {
            return;
        }
        // The same work, over the sprites that something on screen is using.
        // The bind is vanilla's own first line and has to be repeated here:
        // every sprite uploads into the atlas that is bound, and this method
        // is the only thing that binds it.
        GlStateManager.bindTexture(((TextureMap) (Object) this).getGlTextureId());
        AnimatedSprites.updateWanted(this.listAnimatedSprites);
        // Cancelling takes the return hook with it, and that hook is what
        // hands the frames to the Vulkan copy of the atlas — without it the
        // blocks this renderer draws would animate in OpenGL and stand still
        // in Vulkan, which is the shape of a bug nobody would connect to a
        // setting called Smart Animations.
        TerrainHooks.flushAtlasAnimations();
        AtlasAnimations.endAtlasStep();
        ci.cancel();
    }

    /**
     * The frames this tick produced, handed to the Vulkan copy of the atlas
     * together rather than one at a time.
     *
     * Here rather than at each upload because a tick may move a dozen sprites,
     * and each hand-over costs a queue submission and a wait however few pixels
     * it carries.
     */
    @Inject(method = "updateAnimations", at = @At("RETURN"))
    private void vulkanmodnext$sendFrames(CallbackInfo ci) {
        TerrainHooks.flushAtlasAnimations();
        AtlasAnimations.endAtlasStep();
    }
}
