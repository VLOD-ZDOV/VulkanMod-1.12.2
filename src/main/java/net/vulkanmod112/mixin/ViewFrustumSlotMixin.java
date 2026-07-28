package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.vulkanmod112.client.DirtyChunks;
import net.vulkanmod112.client.GridSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells each render chunk where it sits in the grid, once, when the grid is
 * built.
 *
 * Everything downstream indexes by that number: the bitset of chunks waiting to
 * be rebuilt, and the visibility search, which already derives the same index
 * as it steps from one chunk to the next. Doing it here rather than lazily
 * means there is no state to invalidate — the array is built once per world
 * load and a chunk never changes places in it.
 *
 * The grid is rebuilt whenever the render distance changes or a world is
 * loaded, and the bitset is sized and refilled in the same breath, so the two
 * cannot disagree about how big the grid is.
 */
@Mixin(ViewFrustum.class)
public abstract class ViewFrustumSlotMixin {

    @Shadow
    public RenderChunk[] renderChunks;

    @Inject(method = "createRenderChunks", at = @At("TAIL"))
    private void vulkanmod112$assignSlots(CallbackInfo ci) {
        RenderChunk[] chunks = this.renderChunks;
        if (chunks == null) {
            DirtyChunks.grid(0);
            return;
        }
        for (int i = 0; i < chunks.length; i++) {
            RenderChunk chunk = chunks[i];
            if (chunk != null) {
                ((GridSlot) chunk).vulkanmod112$setGridSlot(i);
            }
        }
        // After the slots, not before: a chunk with no slot yet would mark bit
        // -1, and the fill below would overwrite the answer anyway.
        DirtyChunks.grid(chunks.length);
    }
}
