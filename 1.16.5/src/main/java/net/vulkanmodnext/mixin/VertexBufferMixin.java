package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.vulkanmodnext.client.ChunkMirror;
import net.vulkanmodnext.client.ChunkSlots;
import net.vulkanmodnext.client.VertexBufferSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every chunk buffer a small dense index of its own, and takes it back
 * when the buffer dies.
 *
 * <p>Keyed on the buffer object rather than on its OpenGL name, for the reasons
 * written down in {@link ChunkSlots}: the object exists from the moment the
 * chunk is created, while the name only exists once something has uploaded.
 *
 * <p>Unlike its 1.12.2 twin this does not capture the upload itself. There the
 * only place that saw a chunk's bytes was {@code bufferData}; here the geometry
 * is taken one step earlier, on the builder thread, before it is ever queued
 * for OpenGL — so all that is left for this class is the slot.
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin implements VertexBufferSlot {

    /**
     * Volatile: assigned on a chunk builder thread, read by the render thread
     * when it draws.
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
        }
        return vulkanmodnext$slot;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void vulkanmodnext$forget(CallbackInfo ci) {
        int slot;
        synchronized (this) {
            slot = vulkanmodnext$slot;
            vulkanmodnext$slot = ChunkSlots.UNASSIGNED;
        }
        if (slot != ChunkSlots.UNASSIGNED) {
            // Order matters: the mirror drops anything a builder staged for
            // this slot and bumps its epoch, so a builder still copying cannot
            // publish into it, and only then does the slot go back for reuse.
            ChunkMirror.onBufferDelete(slot);
            ChunkSlots.release(slot);
        }
    }
}
