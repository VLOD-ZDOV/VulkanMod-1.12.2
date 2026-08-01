package net.vulkanmod112.mixin;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.TexturedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The six faces of a model box, which the game keeps to itself.
 *
 * An accessor rather than a copy of the arithmetic that builds them: a box is
 * built from a corner, three sizes and a texture offset, and reproducing that
 * would be a second implementation to keep in step with the first for no gain.
 */
@Mixin(ModelBox.class)
public interface ModelBoxAccessor {

    @Accessor("quadList")
    TexturedQuad[] vulkanmod112$quads();
}
