package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.vulkanmod112.client.ChunkMirror;
import net.vulkanmod112.client.ChunkSlots;
import net.vulkanmod112.client.VertexBufferSlot;
import org.spongepowered.asm.mixin.Mixin;
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

    @Unique
    private int vulkanmod112$slot = ChunkSlots.UNASSIGNED;

    @Override
    public int vulkanmod112$slot() {
        return vulkanmod112$slot;
    }

    @Inject(method = "bufferData", at = @At("HEAD"))
    private void vulkanmod112$mirrorUpload(ByteBuffer data, CallbackInfo ci) {
        if (vulkanmod112$slot == ChunkSlots.UNASSIGNED) {
            vulkanmod112$slot = ChunkSlots.allocate();
        }
        ChunkMirror.onBufferData(vulkanmod112$slot, data);
    }

    @Inject(method = "deleteGlBuffers", at = @At("HEAD"))
    private void vulkanmod112$mirrorDelete(CallbackInfo ci) {
        if (vulkanmod112$slot != ChunkSlots.UNASSIGNED) {
            ChunkMirror.onBufferDelete(vulkanmod112$slot);
            ChunkSlots.release(vulkanmod112$slot);
            vulkanmod112$slot = ChunkSlots.UNASSIGNED;
        }
    }

}
