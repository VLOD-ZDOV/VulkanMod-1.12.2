package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.vulkanmodnext.client.ChunkLayers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes which layer each of a chunk's buffers is for.
 *
 * <h2>Why this exists at all</h2>
 *
 * On 1.12.2 the upload hook is handed the layer it is uploading. Here it is
 * handed a {@code BufferBuilder} and a {@code VertexBuffer} and nothing else,
 * and the layer is decided one frame up, inside a lambda. So it is learned from
 * the only place that pairs the two: the accessor a chunk uses to fetch the
 * buffer for a layer.
 *
 * <p>It matters for one thing and one thing only, but that thing is visible:
 * the translucent layer must be sorted back to front, and the copy has to know
 * before it decides whether it may reorder the quads. Getting it wrong the safe
 * way costs an unsorted chunk, which simply draws whole; getting it wrong the
 * other way sorts opaque geometry for nothing.
 */
@Mixin(ChunkRenderDispatcher.ChunkRender.class)
public abstract class ChunkBufferLayerMixin {

    @Inject(method = "getBuffer", at = @At("RETURN"))
    private void vulkanmodnext$note(RenderType layer, CallbackInfoReturnable<VertexBuffer> cir) {
        ChunkLayers.note(cir.getReturnValue(), layer);
    }
}
