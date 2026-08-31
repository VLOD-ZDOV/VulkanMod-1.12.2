package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.chunk.RenderChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The frame this chunk was last reached on by the visibility walk.
 *
 * This is the number the whole question turns on. A chunk that is loaded,
 * built, non-empty and still not drawn was either never reached by the walk or
 * was rejected after it — and those two need opposite fixes. Vanilla stamps
 * every chunk it visits with the frame it visited it on, and reading that stamp
 * is the difference between knowing and guessing.
 */
@Mixin(RenderChunk.class)
public interface RenderChunkAccessor {

    @Accessor("frameIndex")
    int vulkanmodnext$frameIndex();
}
