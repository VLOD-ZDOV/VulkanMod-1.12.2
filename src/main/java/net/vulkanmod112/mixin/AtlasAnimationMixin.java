package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.texture.TextureUtil;
import net.vulkanmod112.client.AtlasAnimations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches every animation frame the game uploads into the block atlas.
 *
 * The Vulkan renderer holds its own copy of the atlas, read out of OpenGL once
 * and turned into an image. The game goes on writing new frames into its own
 * texture every tick, and that copy never heard about it — so lava, water,
 * fire, portals, sea lanterns, prismarine and magma were frozen in the terrain
 * while the same blocks held in the hand animated normally, which is what made
 * it look as though the animation setting had stopped working.
 *
 * This is the one place all of it goes through: both the plain frame step and
 * the interpolated one end in {@code TextureUtil.uploadTextureMipmap}, with the
 * pixels and the rectangle they belong in. Reading them here rather than asking
 * the sprite afterwards means a modded sprite with an animation of its own
 * arrives the same way, and nothing has to know which frame is current.
 */
@Mixin(TextureUtil.class)
public abstract class AtlasAnimationMixin {

    @Inject(method = "uploadTextureMipmap", at = @At("HEAD"))
    private static void vulkanmod112$mirrorFrame(int[][] data, int width, int height,
                                                 int originX, int originY,
                                                 boolean linear, boolean clamp, CallbackInfo ci) {
        AtlasAnimations.record(data, width, height, originX, originY);
    }
}
