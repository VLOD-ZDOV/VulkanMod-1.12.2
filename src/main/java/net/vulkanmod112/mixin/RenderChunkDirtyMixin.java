package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.vulkanmod112.client.DirtyChunks;
import net.vulkanmod112.client.GridSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors a chunk's "needs rebuilding" flag into the flat bitset beside the
 * grid, and carries the chunk's slot in that grid.
 *
 * These two methods are the only way the flag can change — nothing else in the
 * game or in any mod can write the field, since it is private and they are the
 * accessors — so hooking them here catches every transition, including ones
 * this mod knows nothing about. The alternative, watching the places the game
 * happens to call them from, would silently miss whatever a mod does.
 *
 * The cost is a bit write on a path that runs when a block changes, against a
 * cache miss saved on a path that runs 8 600 times a frame. See
 * {@link DirtyChunks} for what the bitset is for.
 */
@Mixin(RenderChunk.class)
public abstract class RenderChunkDirtyMixin implements GridSlot {

    /**
     * One more than the grid position, so that the untouched zero means "not
     * registered" rather than "the corner of the grid".
     */
    @Unique
    private int vulkanmod112$slotPlusOne;

    @Override
    public int vulkanmod112$gridSlot() {
        return this.vulkanmod112$slotPlusOne - 1;
    }

    @Override
    public void vulkanmod112$setGridSlot(int slot) {
        this.vulkanmod112$slotPlusOne = slot + 1;
    }

    @Inject(method = "setNeedsUpdate", at = @At("TAIL"))
    private void vulkanmod112$markDirty(boolean immediate, CallbackInfo ci) {
        DirtyChunks.markDirty(this.vulkanmod112$slotPlusOne - 1);
    }

    @Inject(method = "clearNeedsUpdate", at = @At("TAIL"))
    private void vulkanmod112$markClean(CallbackInfo ci) {
        DirtyChunks.clearDirty(this.vulkanmod112$slotPlusOne - 1);
    }
}
