package net.vulkanmod112.mixin;

import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.vulkanmod112.client.ChunkMirror;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Captures every VBO upload the game performs. In 1.12.2 all world geometry
 * (chunk sections per render layer, plus sky/star meshes) goes through
 * VertexBuffer#bufferData, so mirroring here gives the Vulkan side a complete
 * copy of the world's vertex data keyed by GL buffer id.
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin {

    @Shadow
    private int glBufferId;

    @Inject(method = "bufferData", at = @At("HEAD"))
    private void vulkanmod112$mirrorUpload(ByteBuffer data, CallbackInfo ci) {
        ChunkMirror.onBufferData(glBufferId, data);
    }

    @Inject(method = "deleteGlBuffers", at = @At("HEAD"))
    private void vulkanmod112$mirrorDelete(CallbackInfo ci) {
        ChunkMirror.onBufferDelete(glBufferId);
    }

}
