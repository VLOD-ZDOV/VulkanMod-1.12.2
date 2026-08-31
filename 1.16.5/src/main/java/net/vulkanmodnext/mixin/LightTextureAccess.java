package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The lightmap, by its OpenGL name.
 *
 * The terrain shader samples the same sixteen-by-sixteen table vanilla does, so
 * the light on a block matches the light on everything around it that the game
 * still draws. Reaching it means reaching the texture behind {@code
 * LightTexture}, which the class keeps to itself.
 */
@Mixin(LightTexture.class)
public interface LightTextureAccess {

    @Accessor("lightTexture")
    DynamicTexture vulkanmodnext$texture();
}
