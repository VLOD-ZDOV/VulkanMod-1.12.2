package net.vulkanmodnext.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.vulkanmodnext.client.ChunkLayers;
import net.vulkanmodnext.client.ChunkMirror;
import net.vulkanmodnext.client.VertexBufferSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;

/**
 * Takes a copy of a chunk at the moment the game uploads it to OpenGL.
 *
 * <h2>Why a redirect and not an injection</h2>
 *
 * The bytes exist for exactly one statement: {@code popNextBuffer} hands them
 * over and the upload consumes them. At the head of the method they have not
 * been produced yet, and at the return they are gone. Redirecting the call is
 * the only place both sides can see them, and it hands the same pair straight
 * back, so vanilla's upload is unaffected.
 *
 * <p>This is the render thread, and this is where a mirror entry is actually
 * made. The copy the builder thread staged earlier is a fast path into it, not
 * a replacement for it — dropping this hook produced a renderer that reported
 * success on every layer and drew nothing at all.
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferUploadMixin implements VertexBufferSlot {

    @Redirect(method = "upload_", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/BufferBuilder;popNextBuffer()"
                    + "Lcom/mojang/datafixers/util/Pair;"))
    private Pair<BufferBuilder.DrawState, ByteBuffer> vulkanmodnext$mirror(BufferBuilder builder) {
        Pair<BufferBuilder.DrawState, ByteBuffer> popped = builder.popNextBuffer();
        ByteBuffer data = popped.getSecond();
        if (data != null && data.remaining() > 0
                && ChunkLayers.isChunkBuffer((VertexBuffer) (Object) this)) {
            // Neither position nor limit is touched by the mirror, so the
            // upload on the next line still sees exactly what it expects.
            ChunkMirror.onBufferData(vulkanmodnext$slotOrAssign(), data);
        }
        return popped;
    }
}
