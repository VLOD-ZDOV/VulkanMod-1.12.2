package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.vulkanmodnext.client.ChunkMirror;
import net.vulkanmodnext.client.ChunkSlots;
import net.vulkanmodnext.client.TerrainHooks;
import net.vulkanmodnext.client.VertexBufferSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Captures every VBO upload the game performs. In 1.12.2 all world geometry
 * (chunk sections per render layer, plus sky/star meshes) goes through
 * VertexBuffer#bufferData, so mirroring here gives the Vulkan side a complete
 * copy of the world's vertex data.
 *
 * Each buffer carries the slot its mirror entry lives in, rather than the
 * mirror being keyed by the GL buffer name. The slot belongs to the buffer
 * object, so it is dense, it survives the GL name being reassigned, and it
 * exists before any upload has happened.
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin implements VertexBufferSlot {

    /**
     * Volatile: written by the render thread when the buffer is first
     * uploaded, read by the chunk builder threads deciding whether they can
     * mirror this chunk themselves.
     */
    @Unique
    private volatile int vulkanmodnext$slot = ChunkSlots.UNASSIGNED;

    @Override
    public int vulkanmodnext$slot() {
        return vulkanmodnext$slot;
    }

    @Override
    public synchronized int vulkanmodnext$slotOrAssign() {
        if (vulkanmodnext$slot == ChunkSlots.UNASSIGNED) {
            vulkanmodnext$slot = ChunkSlots.allocate();
            // A number that goes into the packed chunk list has changed, and
            // that list is now kept between frames. An upload into a slot that
            // already exists is not this: the record holds the slot, not what
            // is in it.
            TerrainHooks.noteChunkListChanged();
        }
        return vulkanmodnext$slot;
    }

    /** Vanilla's vertex count, and the reason the whole method is cancelled. */
    @Shadow
    private int count;

    @Inject(method = "bufferData", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$mirrorUpload(ByteBuffer data, CallbackInfo ci) {
        // The mirror takes its copy first, whatever happens next.
        ChunkMirror.onBufferData(vulkanmodnext$slotOrAssign(), data);
        if (!TerrainHooks.dropVanillaBuffers()) {
            return;
        }
        // Skipping only the glBufferData call would leave the count set for a
        // buffer with nothing in it, and any vanilla draw that slipped through
        // would read past the end of an empty buffer. Zeroing it first means
        // the worst case is a chunk that draws nothing.
        //
        // Vanilla's whole method is these four lines: bind, upload, unbind, set
        // the count. With the upload gone the binds have nothing to do either.
        this.count = 0;
        ci.cancel();
    }

    @Inject(method = "deleteGlBuffers", at = @At("HEAD"))
    private void vulkanmodnext$mirrorDelete(CallbackInfo ci) {
        int slot;
        synchronized (this) {
            slot = vulkanmodnext$slot;
            vulkanmodnext$slot = ChunkSlots.UNASSIGNED;
        }
        if (slot != ChunkSlots.UNASSIGNED) {
            // Order matters: the mirror drops any copy a builder staged for
            // this slot and bumps its epoch, so a builder still copying cannot
            // publish into it, and only then does the slot go back for reuse.
            ChunkMirror.onBufferDelete(slot);
            ChunkSlots.release(slot);
            TerrainHooks.noteChunkListChanged();
        }
    }

}
