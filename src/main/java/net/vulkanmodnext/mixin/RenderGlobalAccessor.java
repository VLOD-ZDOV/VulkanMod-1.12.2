package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The grid of chunk renderers, so a probe can ask about one by name. */
@Mixin(RenderGlobal.class)
public interface RenderGlobalAccessor {

    @Accessor("viewFrustum")
    ViewFrustum vulkanmodnext$viewFrustum();

    /** What is still waiting to be turned into triangles. */
    @Accessor("chunksToUpdate")
    java.util.Set<net.minecraft.client.renderer.chunk.RenderChunk> vulkanmodnext$chunksToUpdate();

    /**
     * How far the clouds have drifted, counted in ticks by the game itself.
     *
     * Taken rather than counted again. The clouds move by this number and by
     * nothing else, so a shadow cast by them has to read the same one — a
     * counter of our own would start at a different moment and the shadows
     * would sit beside the clouds instead of under them, which reads as a bug
     * rather than as an approximation.
     */
    @Accessor("cloudTickCounter")
    int vulkanmodnext$cloudTicks();
}
